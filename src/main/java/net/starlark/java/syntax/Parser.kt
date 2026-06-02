// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package net.starlark.java.syntax

import com.google.common.base.Preconditions
import com.google.common.base.Throwables
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.errorprone.annotations.CanIgnoreReturnValue
import com.google.errorprone.annotations.FormatMethod
import java.lang.String
import java.math.BigInteger
import java.util.*
import kotlin.Any
import kotlin.Boolean
import kotlin.Double
import kotlin.IllegalStateException
import kotlin.Int
import kotlin.Long
import kotlin.Number

/** Parser is a recursive-descent parser for Starlark.  */
internal class Parser private constructor(lexer: Lexer, errors: MutableList<SyntaxError?>, options: FileOptions) {
    /** Combines the parser result into a single value object.  */
    internal class ParseResult private constructor(
        locs: FileLocations?,
        statements: ImmutableList<Statement?>?,
        comments: ImmutableList<Comment?>?,
        errors: MutableList<SyntaxError?>?
    ) {
        // Maps char offsets in the file to Locations.
        val locs: FileLocations?

        /** The top-level statements of the parsed file.  */
        val statements: ImmutableList<Statement?>

        /** The comments from the parsed file.  */
        @kotlin.jvm.JvmField
        val comments: ImmutableList<Comment?>

        // Errors encountered during scanning or parsing.
        // These lists are ultimately owned by StarlarkFile.
        val errors: MutableList<SyntaxError?>?

        init {
            this.locs = locs
            // No need to copy here; when the object is created, the parser instance is just about to go
            // out of scope and be garbage collected.
            this.statements = Preconditions.checkNotNull<ImmutableList<Statement?>>(statements)
            this.comments = Preconditions.checkNotNull<ImmutableList<Comment?>>(comments)
            this.errors = errors
        }
    }

    /** Current lookahead token. May be mutated by the parser.  */
    private val token: Lexer // token.kind is a prettier alias for lexer.kind

    private val options: FileOptions

    private val lexer: Lexer
    private val locs: FileLocations
    private val errors: MutableList<SyntaxError?>

    /**
     * Doc comment block which may need to be attached to the next assignment statement. Set to null
     * after parsing a statement. *Not* necessarily set to null after a blank or non-doc comment line;
     * so should be accessed via [.getDocCommentBlockOnPreviousLine].
     */
    private var mostRecentDocCommentBlock: DocComments? = null

    // State tracking whether we're currently parsing a type expression.
    // Used for conditionally allowing the Ellipsis token.
    private var insideTypeExpr = false

    private var errorsCount = 0
    private var recoveryMode = false // stop reporting errors until next statement

    // stmt = simple_stmt
    //      | def_stmt
    //      | for_stmt
    //      | if_stmt
    private fun parseStatement(list: ImmutableList.Builder<Statement?>) {
        if (token.kind == TokenKind.DEF) {
            list.add(parseDefStatement())
        } else if (token.kind == TokenKind.IF) {
            list.add(parseIfStatement())
        } else if (token.kind == TokenKind.FOR) {
            list.add(parseForStatement())
        } else {
            parseSimpleStatement(list)
        }
    }

    // Saves the last doc comment block, so that it may be attached to the next assignment.
    private fun maybeParseDocCommentBlock() {
        while (token.kind == TokenKind.DOC_COMMENT_BLOCK) {
            mostRecentDocCommentBlock = token.value as DocComments?
            nextToken()
        }
    }

    private fun getDocCommentBlockOnPreviousLine(line: Int): DocComments? {
        if (mostRecentDocCommentBlock != null
            && mostRecentDocCommentBlock!!.getEndLocation().line() + 1 == line
        ) {
            return mostRecentDocCommentBlock
        }
        return null
    }

    // Parses every kind of expression, including unparenthesized tuples.
    //
    // In Python the corresponding grammar production is called `expressions` (or previously, in
    // Python 3.8 and older, `testlist`).
    //
    // In many cases we need to use parseTest() in place of parseExpr() to avoid ambiguity, e.g.:
    //
    //   f(x, y)  vs  f((x, y))
    //
    // Unlike Python, a trailing comma is disallowed in an unparenthesized tuple.
    // This prevents bugs where a one-element tuple is surprisingly created, e.g.:
    //
    //   foo = f(x),
    private fun parseExpr(): Expression {
        val e = parseTest()
        if (token.kind != TokenKind.COMMA) {
            return e
        }

        // unparenthesized tuple
        val elems = ImmutableList.builder<Expression?>()
        elems.add(e)
        parseExprList(elems,  /* trailingCommaAllowed= */false)
        return ListExpression(locs,  /* isTuple= */true, -1, elems.build(), -1)
    }

    @FormatMethod
    private fun reportError(offset: Int, format: String, vararg args: Any?) {
        errorsCount++
        // Limit the number of reported errors to avoid spamming output.
        if (errorsCount <= 5) {
            val location = locs.getLocation(offset)
            errors.add(SyntaxError(location, String.format(format, *args)))
        }
    }

    private fun syntaxError(message: kotlin.String?) {
        syntaxError(token.start, token.kind, token.value, message)
    }

    private fun syntaxError(offset: Int, tokenKind: TokenKind, tokenValue: Any?, message: kotlin.String?) {
        if (!recoveryMode) {
            if (tokenKind == TokenKind.INDENT) {
                reportError(offset, "indentation error")
            } else {
                reportError(
                    offset, "syntax error at '%s': %s", tokenString(tokenKind, tokenValue), message
                )
            }
            recoveryMode = true
        }
    }

    // Consumes the current token and returns its position, like nextToken.
    // Reports a syntax error if the new token is not of the expected kind.
    private fun expect(kind: TokenKind?): Int {
        if (token.kind != kind) {
            syntaxError("expected " + kind)
        }
        return nextToken()
    }

    // Like expect, but stops recovery mode if the token was expected.
    private fun expectAndRecover(kind: TokenKind?): Int {
        if (token.kind != kind) {
            syntaxError("expected " + kind)
        } else {
            recoveryMode = false
        }
        return nextToken()
    }

    // Consumes tokens past the first token belonging to terminatingTokens.
    // It returns the end offset of the terminating token.
    // TODO(adonovan): always used with makeErrorExpression. Combine and simplify.
    private fun syncPast(terminatingTokens: EnumSet<TokenKind?>): Int {
        Preconditions.checkState(terminatingTokens.contains(TokenKind.EOF))
        while (!terminatingTokens.contains(token.kind)) {
            nextToken()
        }
        val end = token.end
        // read past the synchronization token
        nextToken()
        return end
    }

    /**
     * Consume tokens until we reach the first token that has a kind that is in the set of
     * terminatingTokens.
     * 
     * @param terminatingTokens
     * @return the end offset of the terminating token.
     */
    private fun syncTo(terminatingTokens: EnumSet<TokenKind?>): Int {
        // EOF must be in the set to prevent an infinite loop
        Preconditions.checkState(terminatingTokens.contains(TokenKind.EOF))
        // read past the problematic token
        var previous = token.end
        nextToken()
        var current = previous
        while (!terminatingTokens.contains(token.kind)) {
            nextToken()
            previous = current
            current = token.end
        }
        return previous
    }

    init {
        this.lexer = lexer
        this.locs = lexer.locs
        this.errors = errors
        this.token = lexer
        this.options = options
        nextToken()
    }

    private fun checkForbiddenKeywords() {
        if (!FORBIDDEN_KEYWORDS.contains(token.kind)) {
            return
        }
        reportError(
            token.start,
            "%s",
            when (token.kind) {
                TokenKind.ASSERT -> "'assert' not supported, use 'fail' instead"
                TokenKind.DEL -> "'del' not supported, use '.pop()' to delete an item from a dictionary or a list"
                TokenKind.IMPORT -> "'import' not supported, use 'load' instead"
                TokenKind.IS -> "'is' not supported, use '==' instead"
                TokenKind.RAISE -> "'raise' not supported, use 'fail' instead"
                TokenKind.TRY -> "'try' not supported, all exceptions are fatal"
                TokenKind.WHILE -> "'while' not supported, use 'for' instead"
                else -> "keyword '" + token.kind + "' not supported"
            }
        )
    }

    private fun nextToken(): Int {
        val prev = token.start
        if (token.kind != TokenKind.EOF) {
            lexer.nextToken()
        }
        checkForbiddenKeywords()
        // TODO(adonovan): move this to lexer so we see the first token too.
        if (DEBUGGING) {
            System.err.print(tokenString(token.kind, token.value))
        }
        return prev
    }

    // Returns an "Identifier" whose content is the input from start to end.
    private fun makeErrorExpression(start: Int, end: Int): Identifier {
        // It's tempting to define a dedicated BadExpression type,
        // but it is convenient for parseIdent to return an Identifier
        // even when it fails.
        return Identifier(locs, lexer.bufferSlice(start, end), start)
    }

    // arg = IDENTIFIER '=' test
    //     | expr
    //     | *args
    //     | **kwargs
    private fun parseArgument(): Argument {
        val expr: Expression?

        // parse **expr
        if (token.kind == TokenKind.STAR_STAR) {
            val starStarOffset = nextToken()
            expr = parseTest()
            return Argument.StarStar(locs, starStarOffset, expr)
        }

        // parse *expr
        if (token.kind == TokenKind.STAR) {
            val starOffset = nextToken()
            expr = parseTest()
            return Argument.Star(locs, starOffset, expr)
        }

        // IDENTIFIER  or  IDENTIFIER = test
        expr = parseTest()
        if (expr is Identifier) {
            // parse a named argument
            if (token.kind == TokenKind.EQUALS) {
                nextToken()
                val arg = parseTest()
                return Argument.Keyword(locs, expr, arg)
            }
        }

        // parse a positional argument
        return Argument.Positional(locs, expr)
    }

    // arg = IDENTIFIER [':' TypeExpr] [ '=' test ]
    //     | * [IDENTIFIER [':' TypeExpr]]
    //     | ** IDENTIFIER [':' TypeExpr]
    // Type annotations are only available on def statements (not lambdas)
    private fun parseParameter(defStatement: Boolean): Parameter {
        var type: Expression? = null

        // **kwargs
        if (token.kind == TokenKind.STAR_STAR) {
            val starStarOffset = nextToken()
            val id = parseIdent()
            if (defStatement) {
                type = maybeParseTypeAnnotationAfter(TokenKind.COLON)
            }
            return Parameter.StarStar(locs, starStarOffset, id, type)
        }

        // * or *args
        if (token.kind == TokenKind.STAR) {
            val starOffset = nextToken()
            if (token.kind == TokenKind.IDENTIFIER) {
                val id = parseIdent()
                if (defStatement) {
                    type = maybeParseTypeAnnotationAfter(TokenKind.COLON)
                }
                return Parameter.Star(locs, starOffset, id, type)
            }
            return Parameter.Star(locs, starOffset, null, null)
        }

        // name
        val id = parseIdent()

        // name: type
        if (defStatement) {
            type = maybeParseTypeAnnotationAfter(TokenKind.COLON)
        }

        // name=default
        if (token.kind == TokenKind.EQUALS) {
            nextToken() // TODO: save token pos?
            val expr = parseTest()
            return Parameter.Optional(locs, id, type, expr)
        }

        return Parameter.Mandatory(locs, id, type)
    }

    // call_suffix = '(' arg_list? ')'
    private fun parseCallSuffix(fn: Expression?): Expression {
        var args = ImmutableList.of<Argument?>()
        val lparenOffset = expect(TokenKind.LPAREN)
        if (token.kind != TokenKind.RPAREN) {
            args = parseArguments() // (includes optional trailing comma)
        }
        val rparenOffset = expect(TokenKind.RPAREN)
        return CallExpression(locs, fn, locs.getLocation(lparenOffset), args, rparenOffset)
    }

    // cast_expression = 'cast' '(' TypeExpr ',' expr [','] ')'
    private fun parseCastExpression(): Expression {
        checkAllowTypeSyntax(token.start, token.kind, token.value)
        val startOffset = expect(TokenKind.CAST)
        expect(TokenKind.LPAREN)
        val typeExpr = parseTypeExprWithFallback()
        expect(TokenKind.COMMA)
        val valueExpr = parseTest()
        if (token.kind == TokenKind.COMMA) {
            expect(TokenKind.COMMA)
        }
        val rparenOffset = expect(TokenKind.RPAREN)
        return CastExpression(locs, startOffset, typeExpr, valueExpr, rparenOffset)
    }

    // isinstance_expression = 'isinstance' '(' expr ',' TypeExpr [','] ')'
    private fun parseIsInstanceExpression(): Expression {
        checkAllowTypeSyntax(token.start, token.kind, token.value)
        val startOffset = expect(TokenKind.ISINSTANCE)
        expect(TokenKind.LPAREN)
        val valueExpr = parseTest()
        expect(TokenKind.COMMA)
        val typeExpr = parseTypeExprWithFallback()
        if (token.kind == TokenKind.COMMA) {
            expect(TokenKind.COMMA)
        }
        val rparenOffset = expect(TokenKind.RPAREN)
        return IsInstanceExpression(locs, startOffset, valueExpr, typeExpr, rparenOffset)
    }

    // Parse a list of call arguments.
    //
    // arg_list = ( (arg ',')* arg ','? )?
    private fun parseArguments(): ImmutableList<Argument?> {
        var seenArg = false
        val list = ImmutableList.builder<Argument?>()
        while (token.kind != TokenKind.RPAREN && token.kind != TokenKind.EOF) {
            if (seenArg) {
                // f(expr for vars in expr) -- Python generator expression?
                if (token.kind == TokenKind.FOR) {
                    syntaxError("Starlark does not support Python-style generator expressions")
                }
                expect(TokenKind.COMMA)
                // If nonempty, the list may end with a comma.
                if (token.kind == TokenKind.RPAREN) {
                    break
                }
            }
            list.add(parseArgument())
            seenArg = true
        }
        return list.build()
    }

    // selector_suffix = '.' IDENTIFIER
    private fun parseSelectorSuffix(e: Expression): Expression {
        val dotOffset = expect(TokenKind.DOT)
        if (token.kind == TokenKind.IDENTIFIER) {
            val id = parseIdent()
            return DotExpression(locs, e, dotOffset, id)
        }

        syntaxError("expected identifier after dot")
        syncTo(EXPR_TERMINATOR_SET)
        return e
    }

    // expr_list parses a comma-separated list of expression. It assumes that the
    // first expression was already parsed, so it starts with a comma.
    // It is used to parse tuples and list elements.
    //
    // expr_list = ( ',' expr )* ','?
    private fun parseExprList(list: ImmutableList.Builder<Expression?>, trailingCommaAllowed: Boolean) {
        //  terminating tokens for an expression list
        while (token.kind == TokenKind.COMMA) {
            expect(TokenKind.COMMA)
            if (EXPR_LIST_TERMINATOR_SET.contains(token.kind)) {
                if (!trailingCommaAllowed) {
                    reportError(token.start, "Trailing comma is allowed only in parenthesized tuples.")
                }
                break
            }
            list.add(parseTest())
        }
    }

    // dict_entry_list = ( (dict_entry ',')* dict_entry ','? )?
    private fun parseDictEntryList(): MutableList<DictExpression.Entry?> {
        val list = ImmutableList.builder<DictExpression.Entry?>()
        // the terminating token for a dict entry list
        while (token.kind != TokenKind.RBRACE) {
            list.add(parseDictEntry())
            if (token.kind == TokenKind.COMMA) {
                nextToken()
            } else {
                break
            }
        }
        return list.build()
    }

    // dict_entry = test ':' test
    private fun parseDictEntry(): DictExpression.Entry {
        val key = parseTest()
        val colonOffset = expect(TokenKind.COLON)
        val value = parseTest()
        return DictExpression.Entry(locs, key, colonOffset, value)
    }

    // expr = STRING
    private fun parseStringLiteral(): StringLiteral {
        Preconditions.checkState(token.kind == TokenKind.STRING)
        // Intern string literals, as they tend to be repeated (both intra-file and inter-file).
        val value: kotlin.String? = (token.value as kotlin.String).intern()
        val literal = StringLiteral(locs, token.start, value, token.end)
        nextToken()
        if (token.kind == TokenKind.STRING) {
            reportError(token.start, "Implicit string concatenation is forbidden, use the + operator")
        }
        return literal
    }

    //  primary = INT
    //          | FLOAT
    //          | STRING
    //          | IDENTIFIER
    //          | list_expression
    //          | '(' ')'                    // a tuple with zero elements
    //          | '(' expr ')'               // a parenthesized expression
    //          | dict_expression
    //          | '-' primary_with_suffix
    //          | cast_expression
    //          | ellipsis                   // if in type expression
    private fun parsePrimary(): Expression {
        when (token.kind) {
            TokenKind.INT -> {
                val literal = IntLiteral(locs, token.start, token.end, token.value as Number?)
                nextToken()
                return literal
            }

            TokenKind.FLOAT -> {
                val literal =
                    FloatLiteral(locs, token.start, token.end, token.value as Double)
                nextToken()
                return literal
            }

            TokenKind.STRING -> return parseStringLiteral()

            TokenKind.IDENTIFIER -> return parseIdent()

            TokenKind.LBRACKET -> return parseListMaker()

            TokenKind.LBRACE -> return parseDictExpression()

            TokenKind.LPAREN -> {
                val lparenOffset = nextToken()

                // empty tuple: ()
                if (token.kind == TokenKind.RPAREN) {
                    val rparen = nextToken()
                    return ListExpression(
                        locs,  /* isTuple= */true, lparenOffset, ImmutableList.of<Expression?>(), rparen
                    )
                }

                val e = parseTest()

                // parenthesized expression: (e)
                // TODO(adonovan): materialize paren expressions (for fidelity).
                if (token.kind == TokenKind.RPAREN) {
                    nextToken()
                    return e
                }

                // non-empty tuple: (e,) or (e, ..., e)
                if (token.kind == TokenKind.COMMA) {
                    val elems = ImmutableList.builder<Expression?>()
                    elems.add(e)
                    parseExprList(elems,  /* trailingCommaAllowed= */true)
                    val rparenOffset = expect(TokenKind.RPAREN)
                    return ListExpression(
                        locs,  /* isTuple= */true, lparenOffset, elems.build(), rparenOffset
                    )
                }

                // (expr for vars in expr) -- Python generator expression?
                if (token.kind == TokenKind.FOR) {
                    syntaxError("Starlark does not support Python-style generator expressions")
                }

                expect(TokenKind.RPAREN)
                val end = syncTo(EXPR_TERMINATOR_SET)
                return makeErrorExpression(lparenOffset, end)
            }

            TokenKind.MINUS -> {
                val offset = nextToken()
                val x = parsePrimaryWithSuffix()

                // Optimize int and float literals to contain the negative value directly
                // instead of being wrapped in a UnaryOperatorExpression
                if (x is IntLiteral) {
                    val negatedValue: Number =
                        when (x.getValue()) {
                            -> Companion.narrowNumberType(-intValue.toLong())
                            -> narrowNumberType(BigInteger.valueOf(longValue).negate())
                            -> Companion.narrowNumberType(bigIntegerValue.negate())
                            else -> throw IllegalStateException(
                                "int literal does not contain an Integer, Long or BigInteger"
                            )
                        }
                    return IntLiteral(locs, offset, x.getEndOffset(), negatedValue)
                } else if (x is FloatLiteral) {
                    return FloatLiteral(
                        locs, offset, x.getEndOffset(), -x.getValue()
                    )
                }

                return UnaryOperatorExpression(locs, TokenKind.MINUS, offset, x)
            }

            TokenKind.PLUS, TokenKind.TILDE -> {
                val op = token.kind
                val offset = nextToken()
                val x = parsePrimaryWithSuffix()

                return UnaryOperatorExpression(locs, op, offset, x)
            }

            TokenKind.CAST -> return parseCastExpression()

            TokenKind.ISINSTANCE -> return parseIsInstanceExpression()

            TokenKind.ELLIPSIS -> {
                if (!insideTypeExpr) {
                    syntaxError("ellipsis ('...') is not allowed outside type expressions")
                    // Fall-through, may as well emit this instead of makeErrorExpression().
                }
                val offset = nextToken()
                return Ellipsis(locs, offset)
            }

            else -> {
                val start = token.start
                syntaxError("expected expression")
                val end = syncTo(EXPR_TERMINATOR_SET)
                return makeErrorExpression(start, end)
            }
        }
    }

    // primary_with_suffix = primary (selector_suffix | slice_suffix | call_suffix)*
    private fun parsePrimaryWithSuffix(): Expression {
        var e = parsePrimary()
        while (true) {
            if (token.kind == TokenKind.DOT) {
                e = parseSelectorSuffix(e)
            } else if (token.kind == TokenKind.LBRACKET) {
                e = parseSliceSuffix(e)
            } else if (token.kind == TokenKind.LPAREN) {
                e = parseCallSuffix(e)
            } else {
                return e
            }
        }
    }

    // slice_suffix = '[' expr? ':' expr?  ':' expr? ']'
    //              | '[' expr? ':' expr? ']'
    //              | '[' expr ']'
    private fun parseSliceSuffix(e: Expression?): Expression {
        val lbracketOffset = expect(TokenKind.LBRACKET)
        var start: Expression? = null
        var end: Expression? = null
        var step: Expression? = null

        if (token.kind != TokenKind.COLON) {
            start = parseExpr()

            // index x[i]
            if (token.kind == TokenKind.RBRACKET) {
                val rbracketOffset = expect(TokenKind.RBRACKET)
                return IndexExpression(locs, e, lbracketOffset, start, rbracketOffset)
            }
        }

        // slice or substring x[i:j] or x[i:j:k]
        expect(TokenKind.COLON)
        if (token.kind != TokenKind.COLON && token.kind != TokenKind.RBRACKET) {
            end = parseTest()
        }
        if (token.kind == TokenKind.COLON) {
            expect(TokenKind.COLON)
            if (token.kind != TokenKind.RBRACKET) {
                step = parseTest()
            }
        }
        val rbracketOffset = expect(TokenKind.RBRACKET)
        return SliceExpression(locs, e, lbracketOffset, start, end, step, rbracketOffset)
    }

    // Equivalent to 'exprlist' rule in Python grammar.
    // loop_variables = primary_with_suffix ( ',' primary_with_suffix )* ','?
    private fun parseForLoopVariables(): Expression? {
        // We cannot reuse parseExpr because it would parse the 'in' operator.
        // e.g.  "for i in e: pass"  -> we want to parse only "i" here.
        val e1 = parsePrimaryWithSuffix()
        if (token.kind != TokenKind.COMMA) {
            return e1
        }

        // unparenthesized tuple
        val elems = ImmutableList.builder<Expression?>()
        elems.add(e1)
        while (token.kind == TokenKind.COMMA) {
            expect(TokenKind.COMMA)
            if (EXPR_LIST_TERMINATOR_SET.contains(token.kind)) {
                break
            }
            elems.add(parsePrimaryWithSuffix())
        }
        return ListExpression(locs,  /* isTuple= */true, -1, elems.build(), -1)
    }

    // comprehension_suffix = 'FOR' loop_variables 'IN' expr comprehension_suffix
    //                      | 'IF' expr comprehension_suffix
    //                      | ']' | '}'
    private fun parseComprehensionSuffix(loffset: Int, body: Node?, closingBracket: TokenKind?): Expression {
        val clauses = ImmutableList.builder<Comprehension.Clause?>()
        while (true) {
            if (token.kind == TokenKind.FOR) {
                val forOffset = nextToken()
                val vars = parseForLoopVariables()
                expect(TokenKind.IN)
                // The expression cannot be a ternary expression ('x if y else z') due to
                // conflicts in Python grammar ('if' is used by the comprehension).
                val seq = parseTest(0)
                clauses.add(Comprehension.For(locs, forOffset, vars, seq))
            } else if (token.kind == TokenKind.IF) {
                val ifOffset = nextToken()
                // [x for x in li if 1, 2]  # parse error
                // [x for x in li if (1, 2)]  # ok
                val cond = parseTestNoCond()
                clauses.add(Comprehension.If(locs, ifOffset, cond))
            } else if (token.kind == closingBracket) {
                break
            } else {
                syntaxError("expected '" + closingBracket + "', 'for' or 'if'")
                val end = syncPast(LIST_TERMINATOR_SET)
                return makeErrorExpression(loffset, end)
            }
        }

        val isDict = closingBracket == TokenKind.RBRACE
        val roffset = expect(closingBracket)
        return Comprehension(locs, isDict, loffset, body, clauses.build(), roffset)
    }

    // list_maker = '[' ']'
    //            | '[' expr ']'
    //            | '[' expr expr_list ']'
    //            | '[' expr comprehension_suffix ']'
    private fun parseListMaker(): Expression {
        val lbracketOffset = expect(TokenKind.LBRACKET)
        if (token.kind == TokenKind.RBRACKET) { // empty List
            val rbracketOffset = nextToken()
            return ListExpression(
                locs,  /* isTuple= */false, lbracketOffset, ImmutableList.of<Expression?>(), rbracketOffset
            )
        }

        val expression = parseTest()
        when (token.kind) {
            TokenKind.RBRACKET ->         // [e], singleton list
            {
                val rbracketOffset = nextToken()
                return ListExpression(
                    locs,  /* isTuple= */
                    false,
                    lbracketOffset,
                    ImmutableList.of<Expression?>(expression),
                    rbracketOffset
                )
            }

            TokenKind.FOR ->         // [e for x in y], list comprehension
                return parseComprehensionSuffix(lbracketOffset, expression, TokenKind.RBRACKET)

            TokenKind.COMMA ->         // [e, ...], list expression
            {
                val elems = ImmutableList.builder<Expression?>()
                elems.add(expression)
                parseExprList(elems,  /* trailingCommaAllowed= */true)
                if (token.kind == TokenKind.RBRACKET) {
                    val rbracketOffset = nextToken()
                    return ListExpression(
                        locs,  /* isTuple= */false, lbracketOffset, elems.build(), rbracketOffset
                    )
                }

                expect(TokenKind.RBRACKET)
                val end = syncPast(LIST_TERMINATOR_SET)
                return makeErrorExpression(lbracketOffset, end)
            }

            else -> {
                syntaxError("expected ',', 'for' or ']'")
                val end = syncPast(LIST_TERMINATOR_SET)
                return makeErrorExpression(lbracketOffset, end)
            }
        }
    }

    // dict_expression = '{' '}'
    //                 | '{' dict_entry_list '}'
    //                 | '{' dict_entry comprehension_suffix '}'
    private fun parseDictExpression(): Expression {
        val lbraceOffset = expect(TokenKind.LBRACE)
        if (token.kind == TokenKind.RBRACE) { // empty Dict
            val rbraceOffset = nextToken()
            return DictExpression(locs, lbraceOffset, ImmutableList.of<DictExpression.Entry?>(), rbraceOffset)
        }

        val entry = parseDictEntry()
        if (token.kind == TokenKind.FOR) {
            // Dict comprehension
            return parseComprehensionSuffix(lbraceOffset, entry, TokenKind.RBRACE)
        }

        val entries = ImmutableList.builder<DictExpression.Entry?>()
        entries.add(entry)
        if (token.kind == TokenKind.COMMA) {
            expect(TokenKind.COMMA)
            entries.addAll(parseDictEntryList())
        }
        if (token.kind == TokenKind.RBRACE) {
            val rbraceOffset = nextToken()
            return DictExpression(locs, lbraceOffset, entries.build(), rbraceOffset)
        }

        expect(TokenKind.RBRACE)
        val end = syncPast(DICT_TERMINATOR_SET)
        return makeErrorExpression(lbraceOffset, end)
    }

    private fun parseIdent(): Identifier {
        if (token.kind != TokenKind.IDENTIFIER) {
            val start = token.start
            val end = expect(TokenKind.IDENTIFIER)
            return makeErrorExpression(start, end)
        }

        val name = token.value as kotlin.String?
        val offset = nextToken()
        return Identifier(locs, name, offset)
    }

    // binop_expression = binop_expression OP binop_expression
    //                  | parsePrimaryWithSuffix
    // This function takes care of precedence between operators (see operatorPrecedence for
    // the order), and it assumes left-to-right associativity.
    private fun parseBinOpExpression(prec: Int): Expression {
        var x = parseTest(prec + 1)
        // The loop is not strictly needed, but it prevents risks of stack overflow. Depth is
        // limited to number of different precedence levels (operatorPrecedence.size()).
        var lastOp: TokenKind? = null
        while (true) {
            if (token.kind == TokenKind.NOT) {
                // If NOT appears when we expect a binary operator, it must be followed by IN.
                // Since the code expects every operator to be a single token, we push a NOT_IN token.
                expect(TokenKind.NOT)
                if (token.kind != TokenKind.IN) {
                    syntaxError("expected 'in'")
                }
                token.kind = TokenKind.NOT_IN
            }

            val op = token.kind
            if (!operatorPrecedence.get(prec)!!.contains(op)) {
                return x
            }

            // Operator '==' and other operators of the same precedence (e.g. '<', 'in')
            // are not associative.
            if (lastOp != null && operatorPrecedence.get(prec)!!.contains(TokenKind.EQUALS_EQUALS)) {
                reportError(
                    token.start,
                    "Operator '%s' is not associative with operator '%s'. Use parens.",
                    lastOp,
                    op
                )
            }

            val opOffset = nextToken()
            val y = parseTest(prec + 1)
            x = optimizeBinOpExpression(x, op, opOffset, y)
            lastOp = op
        }
    }

    // Optimize binary expressions.
    // string literal + string literal can be concatenated into one string literal
    // so we don't have to do the expensive string concatenation at runtime.
    private fun optimizeBinOpExpression(
        x: Expression?, op: TokenKind?, opOffset: Int, y: Expression?
    ): Expression {
        if (op == TokenKind.PLUS && x is StringLiteral
            && y is StringLiteral
        ) {
            // Intern the concatenation of string literals.
            val concat: kotlin.String? = (x.getValue() + y.getValue()).intern()
            return StringLiteral(locs, x.getStartOffset(), concat, y.getEndOffset())
        }
        return BinaryOperatorExpression(locs, x, op, opOffset, y)
    }

    /**
     * Returns true if type syntax is allowed. Otherwise, reports a syntax error for the given offset
     * and token kind and value, and returns false.
     */
    @CanIgnoreReturnValue
    private fun checkAllowTypeSyntax(offset: Int, tokenKind: TokenKind, tokenValue: Any?): Boolean {
        if (options.allowTypeSyntax()) {
            return true
        } else {
            syntaxError(offset, tokenKind, tokenValue, "type annotations are disallowed")
            return false
        }
    }

    private fun maybeParseTypeAnnotationAfter(expectedToken: TokenKind?): Expression? {
        if (token.kind == expectedToken && checkAllowTypeSyntax(token.start, token.kind, token.value)) {
            nextToken()
            return parseTypeExprWithFallback()
        }
        return null
    }

    // Hook for parsing either a structured type expression, or an unstructured arbitrary expression
    // (except for unparenthesized tuples). The latter is useless for type checking but allows the
    // parser to never fail on parsing a type annotation it doesn't recognize (e.g. supported by a
    // future version of Bazel), so long as it's valid expression syntax.
    private fun parseTypeExprWithFallback(): Expression? {
        val result: Expression?
        this.insideTypeExpr = true
        if (options.tolerateInvalidTypeExpressions()) {
            // parseTest, because allowing unparenthesized tuples here would consume subsequent params in
            // function signatures.
            result = parseTest()
        } else {
            result = parseTypeExpr()
        }
        this.insideTypeExpr = false
        return result
    }

    // TypeExpr = TypeAtom {'|' TypeAtom}.
    // TypeAtom = identifier [TypeArguments].
    private fun parseTypeExpr(): Expression {
        if (token.kind != TokenKind.IDENTIFIER) {
            val start = token.start
            syntaxError("expected a type")
            val end = syncTo(EXPR_TERMINATOR_SET)
            return makeErrorExpression(start, end)
        }
        val typeOrConstructor = parseIdent()
        var expr: Expression
        if (token.kind == TokenKind.LBRACKET) {
            expr = parseTypeApplication(typeOrConstructor)
        } else {
            expr = typeOrConstructor
        }
        while (token.kind == TokenKind.PIPE) {
            val opOffset = nextToken()
            val secondTypeOrConstructor = parseIdent()
            val y: Expression?
            if (token.kind == TokenKind.LBRACKET) {
                y = parseTypeApplication(secondTypeOrConstructor)
            } else {
                y = secondTypeOrConstructor
            }
            expr = BinaryOperatorExpression(locs, expr, TokenKind.PIPE, opOffset, y)
        }
        return expr
    }

    // TypeArgument = TypeExpr | ListOfTypes | DictOfTypes | '(' ')' | string | ellipsis
    private fun parseTypeArgument(): Expression? {
        when (token.kind) {
            TokenKind.LBRACKET -> return parseTypeList()
            TokenKind.LBRACE -> return parseTypeDict()
            TokenKind.LPAREN -> {
                val lparenOffset = expect(TokenKind.LPAREN)
                val rparenOffset = expect(TokenKind.RPAREN)
                return ListExpression(
                    locs,  /* isTuple= */true, lparenOffset, ImmutableList.of<Expression?>(), rparenOffset
                )
            }

            TokenKind.STRING -> return parseStringLiteral()
            TokenKind.ELLIPSIS -> return parsePrimary()
            else -> {}
        }
        if (token.kind != TokenKind.IDENTIFIER) {
            val start = token.start
            syntaxError("expected a type argument")
            val end = syncTo(EXPR_TERMINATOR_SET)
            return makeErrorExpression(start, end)
        }
        return parseTypeExpr()
    }

    // ListOfTypes = '[' [TypeArgument {',' TypeArgument} [',']] ']'.
    private fun parseTypeList(): Expression {
        val lbracketOffset = expect(TokenKind.LBRACKET)
        val elems = ImmutableList.builder<Expression?>()
        if (token.kind != TokenKind.RBRACKET) {
            elems.add(parseTypeArgument())
        }
        while (token.kind != TokenKind.RBRACKET && token.kind != TokenKind.EOF) {
            expect(TokenKind.COMMA)
            if (token.kind == TokenKind.RBRACKET) {
                break
            }
            elems.add(parseTypeArgument())
        }
        val rbracketOffset = nextToken()
        return ListExpression(
            locs,  /* isTuple= */false, lbracketOffset, elems.build(), rbracketOffset
        )
    }

    // TypeEntry = string ':' TypeArgument .
    private fun parseTypeDictEntry(): DictExpression.Entry {
        val key: Expression?
        if (token.kind == TokenKind.STRING) {
            key = parseStringLiteral()
        } else {
            val start = token.start
            syntaxError(String.format("expected %s", TokenKind.STRING))
            val end = syncTo(EXPR_TERMINATOR_SET)
            key = makeErrorExpression(start, end)
        }
        val colonOffset = expect(TokenKind.COLON)
        val value = parseTypeArgument()
        return DictExpression.Entry(locs, key, colonOffset, value)
    }

    // DictOfTypes = '{' [TypeEntry {',' TypeEntry} [',']] '}' .
    private fun parseTypeDict(): Expression {
        val lbraceOffset = expect(TokenKind.LBRACE)

        val entries = ImmutableList.builder<DictExpression.Entry?>()
        if (token.kind != TokenKind.RBRACE) {
            entries.add(parseTypeDictEntry())
        }
        while (token.kind != TokenKind.RBRACE && token.kind != TokenKind.EOF) {
            expect(TokenKind.COMMA)
            if (token.kind == TokenKind.RBRACE) {
                break
            }
            entries.add(parseTypeDictEntry())
        }

        val rbraceOffset = nextToken()
        return DictExpression(locs, lbraceOffset, entries.build(), rbraceOffset)
    }

    // TypeArguments = '[' TypeArgument {',' TypeArgument} ']'.
    private fun parseTypeApplication(constructor: Identifier?): Expression {
        expect(TokenKind.LBRACKET)
        val args = ImmutableList.builder<Expression?>()
        args.add(parseTypeArgument())
        while (token.kind != TokenKind.RBRACKET && token.kind != TokenKind.EOF) {
            expect(TokenKind.COMMA)
            args.add(parseTypeArgument())
        }
        val rbracketOffset = expect(TokenKind.RBRACKET)
        return TypeApplication(locs, constructor, args.build(), rbracketOffset)
    }

    // type_alias_stmt = 'type' type_alias_stmt_tail
    // type_alias_stmt_tail = identifier optional_type_params '=' TypeExpr
    //
    // This method assumes that 'type' has already been consumed to produce typeSoftKeywordNode.
    private fun parseTypeAliasStatementTail(typeSoftKeywordNode: Node?): Statement {
        Preconditions.checkArgument(isTypeSoftKeyword(typeSoftKeywordNode))
        val startOffset = typeSoftKeywordNode!!.getStartOffset()
        // For user-friendliness, mark the error as if it was detected at 'type'
        checkAllowTypeSyntax(startOffset, TokenKind.IDENTIFIER, TYPE_SOFT_KEYWORD)
        val identifier = parseIdent()
        val parameters = parseOptionalTypeParameters()
        expect(TokenKind.EQUALS)
        val definition = parseTypeExprWithFallback()
        return TypeAliasStatement(locs, startOffset, identifier, parameters, definition)
    }

    // optional_type_params = ['[' identifier {',' identifier} [','] ']']
    //
    // For syntactic compatibility with Python, the list of identifiers in optional_type_params cannot
    // contain duplicates; duplicate identifiers are treated as a syntax error.
    //
    // If the optional_type_params is absent (in other words, if the initial token is not '['), this
    // method returns an empty list. (Note that if optional_type_params is present, it must contain at
    // least one identifier.)
    private fun parseOptionalTypeParameters(): ImmutableList<Identifier?> {
        if (token.kind == TokenKind.LBRACKET) {
            checkAllowTypeSyntax(token.start, token.kind, token.value)
            nextToken()
            val parameters = ImmutableList.builder<Identifier?>()
            val uniqueParameterNames: MutableSet<kotlin.String?> = HashSet<kotlin.String?>()
            parameters.add(parseTypeParameter(uniqueParameterNames))
            while (token.kind != TokenKind.RBRACKET && token.kind != TokenKind.EOF) {
                expect(TokenKind.COMMA)
                if (token.kind == TokenKind.RBRACKET) {
                    break
                }
                parameters.add(parseTypeParameter(uniqueParameterNames))
            }
            expect(TokenKind.RBRACKET)
            return parameters.build()
        } else {
            return ImmutableList.of<Identifier?>()
        }
    }

    private fun parseTypeParameter(uniqueParameterNames: MutableSet<kotlin.String?>): Identifier {
        val tokenStart = token.start
        val tokenKind = token.kind
        val tokenValue = token.value
        val ident = parseIdent()
        // If parseIdent() encountered a syntax error, Identifier.isValid(param.getName()) would be
        // false, and in that case, there's no need to check for the param's uniqueness.
        if (Identifier.Companion.isValid(ident.getName()) && !uniqueParameterNames.add(ident.getName())) {
            syntaxError(tokenStart, tokenKind, tokenValue, "duplicate type parameter")
        }
        return ident
    }

    // Parses any expression except for an unparenthesized tuple.
    //
    // In Python the corresponding grammar production is called `expression` (or previously, in
    // Python 3.8 and older, `test`).
    private fun parseTest(): Expression {
        val start = token.start
        if (token.kind == TokenKind.LAMBDA) {
            return parseLambda( /* allowCond= */true)
        }

        val expr = parseTest(0)
        if (token.kind == TokenKind.IF) {
            nextToken()
            val condition = parseTest(0)
            if (token.kind == TokenKind.ELSE) {
                nextToken()
                val elseClause = parseTest()
                return ConditionalExpression(locs, expr, condition, elseClause)
            } else {
                reportError(start, "missing else clause in conditional expression or semicolon before if")
                return expr // Try to recover from error: drop the if and the expression after it. Ouch.
            }
        }
        return expr
    }

    private fun parseTest(prec: Int): Expression {
        if (prec >= operatorPrecedence.size()) {
            return parsePrimaryWithSuffix()
        }
        if (token.kind == TokenKind.NOT && operatorPrecedence.get(prec)!!.contains(TokenKind.NOT)) {
            return parseNotExpression(prec)
        }
        return parseBinOpExpression(prec)
    }

    // parseLambda parses a lambda expression.
    // The allowCond flag allows the body to be an 'a if b else c' conditional.
    private fun parseLambda(allowCond: Boolean): LambdaExpression {
        val lambdaOffset = expect(TokenKind.LAMBDA)
        val params = parseParameters( /* defStatement= */false)
        expect(TokenKind.COLON)
        val body = if (allowCond) parseTest() else parseTestNoCond()
        return LambdaExpression(locs, lambdaOffset, params, body)
    }

    // parseTestNoCond parses a single-component expression without
    // consuming a trailing 'if expr else expr'.
    private fun parseTestNoCond(): Expression {
        if (token.kind == TokenKind.LAMBDA) {
            return parseLambda( /* allowCond= */false)
        }
        return parseTest(0)
    }

    // not_expr = 'not' expr
    private fun parseNotExpression(prec: Int): Expression {
        val notOffset = expect(TokenKind.NOT)
        val x = parseTest(prec)
        return UnaryOperatorExpression(locs, TokenKind.NOT, notOffset, x)
    }

    // file_input = EOF
    //            | ('\n' | DOC_COMMENT_BLOCK | stmt)* '\n' EOF
    // The terminating newline is injected by the lexer even if not present in the input.
    private fun parseFileInput(): ImmutableList<Statement?> {
        val list = ImmutableList.builder<Statement?>()
        try {
            while (token.kind != TokenKind.EOF) {
                if (token.kind == TokenKind.NEWLINE) {
                    expectAndRecover(TokenKind.NEWLINE)
                } else if (recoveryMode) {
                    // If there was a parse error, we want to recover here
                    // before starting a new top-level statement.
                    syncTo(STATEMENT_TERMINATOR_SET)
                    recoveryMode = false
                } else {
                    maybeParseDocCommentBlock()
                    if (token.kind == TokenKind.EOF) {
                        break
                    }
                    parseStatement(list)
                }
            }
        } catch (ex: StackOverflowError) {
            // JVM threads have very limited stack, and deeply nested inputs can
            // easily cause the parser to consume all available stack. It is hard
            // to anticipate all the possible recursions in the parser, especially
            // when considering error recovery. Consider a long list of dicts:
            // even if the intended parse tree has a depth of only two,
            // if each dict contains a syntax error, the parser will go into recovery
            // and may discard each dict's closing '}', turning a shallow tree
            // into a deep one (see b/157470754).
            //
            // So, for robustness, the parser treats StackOverflowError as a parse
            // error, exhorting the user to report a bug.
            reportError(
                token.end,
                ("internal error: stack overflow in Starlark parser. Please report the bug and include"
                        + " the text of %s.\n"
                        + "%s"),
                locs.file(),
                Throwables.getStackTraceAsString(ex)
            )
        }
        return list.build()
    }

    // load '(' STRING (COMMA [IDENTIFIER EQUALS] STRING)+ COMMA? ')'
    private fun parseLoadStatement(): Statement {
        val loadOffset = expect(TokenKind.LOAD)
        expect(TokenKind.LPAREN)
        if (token.kind != TokenKind.STRING) {
            // error: module is not a string literal.
            val module = StringLiteral(locs, token.start, "", token.end)
            expect(TokenKind.STRING)
            return LoadStatement(locs, loadOffset, module, ImmutableList.of<LoadStatement.Binding?>(), token.end)
        }

        val module = parseStringLiteral()
        if (token.kind == TokenKind.RPAREN) {
            syntaxError("expected at least one symbol to load")
            return LoadStatement(locs, loadOffset, module, ImmutableList.of<LoadStatement.Binding?>(), token.end)
        }
        expect(TokenKind.COMMA)

        val bindings = ImmutableList.builder<LoadStatement.Binding?>()
        // At least one symbol is required.
        parseLoadSymbol(bindings)
        while (token.kind != TokenKind.RPAREN && token.kind != TokenKind.EOF) {
            // A trailing comma is permitted after the last symbol.
            expect(TokenKind.COMMA)
            if (token.kind == TokenKind.RPAREN) {
                break
            }
            parseLoadSymbol(bindings)
        }

        val rparen = expect(TokenKind.RPAREN)
        return LoadStatement(locs, loadOffset, module, bindings.build(), rparen)
    }

    /**
     * Parses the next symbol argument of a load statement and puts it into the output map.
     * 
     * 
     * The symbol is either "name" (STRING) or name = "declared" (IDENTIFIER EQUALS STRING). If no
     * alias is used, "name" and "declared" will be identical. "Declared" refers to the original name
     * in the Bazel file that should be loaded, while "name" will be the key of the entry in the map.
     */
    private fun parseLoadSymbol(symbols: ImmutableList.Builder<LoadStatement.Binding?>) {
        if (token.kind != TokenKind.STRING && token.kind != TokenKind.IDENTIFIER) {
            syntaxError("expected either a literal string or an identifier")
            return
        }

        val name = token.value as kotlin.String?
        val nameOffset = token.start + (if (token.kind == TokenKind.STRING) 1 else 0)
        val local = Identifier(locs, name, nameOffset)

        val original: Identifier?
        if (token.kind == TokenKind.STRING) {
            // load(..., "name")
            original = local
        } else {
            // load(..., local = "orig")
            // The name "orig" is morally an identifier but, for legacy reasons (specifically,
            // a partial implementation of Starlark embedded in a Python interpreter used by
            // tests of Blaze), it must be a quoted string literal.
            expect(TokenKind.IDENTIFIER)
            expect(TokenKind.EQUALS)
            if (token.kind != TokenKind.STRING) {
                syntaxError("expected string")
                return
            }
            original = Identifier(locs, token.value as kotlin.String?, token.start + 1)
        }
        nextToken()
        symbols.add(LoadStatement.Binding(local, original))
    }

    // simple_stmt = small_stmt (';' small_stmt)* ';'? DOC_COMMENT_TRAILING? NEWLINE
    // Note that the DOC_COMMENT_TRAILING will be absorbed by the first small_stmt iff it is an
    // assign_stmt and there are no other tokens between it and the DOC_COMMENT_TRAILING.
    private fun parseSimpleStatement(list: ImmutableList.Builder<Statement?>) {
        list.add(parseSmallStatement())
        mostRecentDocCommentBlock = null

        while (token.kind == TokenKind.SEMI) {
            nextToken()
            if (token.kind == TokenKind.NEWLINE || token.kind == TokenKind.DOC_COMMENT_TRAILING) {
                break
            }
            list.add(parseSmallStatement())
        }
        if (token.kind == TokenKind.DOC_COMMENT_TRAILING) {
            // Absorb trailing doc comments that weren't attached to an assignment.
            nextToken()
        }
        expectAndRecover(TokenKind.NEWLINE)
    }

    /**
     * Parses a trailing doc comment if we're at one.
     * 
     * 
     * Returns that comment if it exists, or else the currently applicable block comment if it
     * exists, or else null.
     * 
     * 
     * `statementStart` is the location of the first token in the statement, used to
     * determine the currently applicable block comment.
     */
    private fun maybeParseTrailingDocComment(statementStart: Location): DocComments? {
        val result: DocComments?
        if (token.kind == TokenKind.DOC_COMMENT_TRAILING) {
            result = token.value as DocComments?
            nextToken()
        } else {
            result = getDocCommentBlockOnPreviousLine(statementStart.line())
        }
        return result
    }

    //     small_stmt = assign_stmt
    //                | type_alias_stmt
    //                | expr
    //                | load_stmt
    //                | return_stmt
    //                | var_stmt
    //                | BREAK | CONTINUE | PASS
    //
    //     assign_stmt = expr (':' expr)? ('=' | augassign) expr DOC_COMMENT_TRAILING?
    //
    //     augassign = '+=' | '-=' | '*=' | '/=' | '%=' | '//=' | '&=' | '|=' | '^=' |'<<=' | '>>='
    //
    //     var_stmt = IDENTIFIER ':' expr DOC_COMMENT_TRAILING?
    private fun parseSmallStatement(): Statement {
        // return
        if (token.kind == TokenKind.RETURN) {
            return parseReturnStatement()
        }

        // control flow
        if (token.kind == TokenKind.BREAK || token.kind == TokenKind.CONTINUE || token.kind == TokenKind.PASS) {
            val kind = token.kind
            val offset = nextToken()
            return FlowStatement(locs, kind, offset)
        }

        // load
        if (token.kind == TokenKind.LOAD) {
            return parseLoadStatement()
        }

        // All other cases require an expression. Parse it now.
        val lhs = parseExpr()

        // Type alias. This is the only context in which an identifier can be immediately followed by
        // another identifier; the first identifier is the soft keyword `type`.
        if (token.kind == TokenKind.IDENTIFIER && isTypeSoftKeyword(lhs)) {
            return parseTypeAliasStatementTail(lhs)
        }

        // Possible type expression for var statement or assignment.
        val colonOffset = token.start // valid only if type != null below.
        var type = maybeParseTypeAnnotationAfter(TokenKind.COLON)

        // If it's an assignment, the equals or augmented-equals operator will be next.
        // op == null for ordinary assignment. TODO(adonovan): represent as EQUALS.
        val op: TokenKind? = augmentedAssignments.get(token.kind)
        if (token.kind == TokenKind.EQUALS || op != null) {
            // Assignment.
            val opOffset = nextToken()
            val rhs = parseExpr()
            val docComments = maybeParseTrailingDocComment(lhs.getStartLocation())
            // Validate usage of type annotation if present.
            if (type != null) {
                if (lhs !is Identifier) {
                    syntaxError(
                        colonOffset,
                        TokenKind.COLON,
                        null,
                        "type annotations must have a single identifier on the left-hand side"
                    )
                    type = null
                }
                if (op != null) {
                    syntaxError(
                        colonOffset,
                        TokenKind.COLON,
                        null,
                        "type annotations not allowed on augmented assignment statements"
                    )
                    type = null
                }
            }
            return AssignmentStatement(locs, lhs, type, op, opOffset, rhs, docComments)
        } else if (type != null) {
            // Var statement.
            val docComments = maybeParseTrailingDocComment(lhs.getStartLocation())
            if (lhs !is Identifier) {
                syntaxError(
                    colonOffset,
                    TokenKind.COLON,
                    null,
                    "type annotations must have a single identifier on the left-hand side"
                )
                return ExpressionStatement(
                    locs, makeErrorExpression(lhs.getStartOffset(), type.getEndOffset())
                )
            }
            return VarStatement(locs, lhs, type, docComments)
        } else {
            // Not an assignment or var statement, so must be an expression.
            return ExpressionStatement(locs, lhs)
        }
    }

    // if_stmt = IF expr ':' suite [ELIF expr ':' suite]* [ELSE ':' suite]?
    private fun parseIfStatement(): IfStatement {
        val ifOffset = expect(TokenKind.IF)
        var cond = parseTest()
        expect(TokenKind.COLON)
        var body = parseSuite()
        val ifStmt = IfStatement(locs, TokenKind.IF, ifOffset, cond, body)
        var tail = ifStmt
        while (token.kind == TokenKind.ELIF) {
            val elifOffset = expect(TokenKind.ELIF)
            cond = parseTest()
            expect(TokenKind.COLON)
            body = parseSuite()
            val elif = IfStatement(locs, TokenKind.ELIF, elifOffset, cond, body)
            tail.setElseBlock(ImmutableList.of<Statement?>(elif))
            tail = elif
        }
        if (token.kind == TokenKind.ELSE) {
            expect(TokenKind.ELSE)
            expect(TokenKind.COLON)
            body = parseSuite()
            tail.setElseBlock(body)
        }
        return ifStmt
    }

    // for_stmt = FOR IDENTIFIER IN expr ':' suite
    private fun parseForStatement(): ForStatement {
        val forOffset = expect(TokenKind.FOR)
        val vars = parseForLoopVariables()
        expect(TokenKind.IN)
        val collection = parseExpr()
        expect(TokenKind.COLON)
        val body = parseSuite()
        return ForStatement(locs, forOffset, vars, collection, body)
    }

    // def_stmt = DEF IDENTIFIER optional_type_parameters '(' arguments ')' ['->' TypeExpr] ':' suite
    private fun parseDefStatement(): DefStatement {
        val defOffset = expect(TokenKind.DEF)
        val ident = parseIdent()
        val typeParams = parseOptionalTypeParameters()
        expect(TokenKind.LPAREN)
        val params = parseParameters( /* defStatement= */true)
        expect(TokenKind.RPAREN)
        val returnType = maybeParseTypeAnnotationAfter(TokenKind.RARROW)
        expect(TokenKind.COLON)
        val block = parseSuite()
        return DefStatement(locs, defOffset, ident, typeParams, params, returnType, block)
    }

    // Parse a list of function parameters.
    // Validation of parameter ordering and uniqueness is the job of the Resolver.
    private fun parseParameters(defStatement: Boolean): ImmutableList<Parameter?> {
        var hasParam = false
        val list = ImmutableList.builder<Parameter?>()

        while (token.kind != TokenKind.RPAREN && token.kind != TokenKind.COLON && token.kind != TokenKind.EOF) {
            if (hasParam) {
                expect(TokenKind.COMMA)
                // The list may end with a comma.
                if (token.kind == TokenKind.RPAREN) {
                    break
                }
            }
            val param = parseParameter(defStatement)
            hasParam = true
            list.add(param)
        }
        return list.build()
    }

    // suite is typically what follows a colon (e.g. after def or for).
    // suite = simple_stmt
    //       | DOC_COMMENT_TRAILING? NEWLINE DOC_COMMENT_BLOCK? INDENT (stmt DOC_COMMENT_BLOCK?)+ \
    //         OUTDENT
    private fun parseSuite(): ImmutableList<Statement?> {
        val list = ImmutableList.builder<Statement?>()
        if (token.kind == TokenKind.DOC_COMMENT_TRAILING) {
            nextToken()
        }
        if (token.kind == TokenKind.NEWLINE) {
            expect(TokenKind.NEWLINE)
            maybeParseDocCommentBlock()
            if (token.kind != TokenKind.INDENT) {
                reportError(token.start, "expected an indented block")
                return list.build()
            }
            expect(TokenKind.INDENT)
            while (token.kind != TokenKind.OUTDENT && token.kind != TokenKind.EOF) {
                parseStatement(list)
                // Note that on the final loop iteration, we may encounter a doc comment block that will
                // need to be attached to the (dedented) assignment statement after the end of the suite.
                maybeParseDocCommentBlock()
            }
            expectAndRecover(TokenKind.OUTDENT)
        } else {
            parseSimpleStatement(list)
        }
        return list.build()
    }

    // return_stmt = RETURN [expr]
    private fun parseReturnStatement(): ReturnStatement {
        val returnOffset = expect(TokenKind.RETURN)

        var result: Expression? = null
        if (!STATEMENT_TERMINATOR_SET.contains(token.kind)) {
            result = parseExpr()
        }
        return ReturnStatement(locs, returnOffset, result)
    }

    companion object {
        private val STATEMENT_TERMINATOR_SET: EnumSet<TokenKind?> =
            EnumSet.of<TokenKind?>(TokenKind.EOF, TokenKind.NEWLINE, TokenKind.DOC_COMMENT_TRAILING, TokenKind.SEMI)

        private val LIST_TERMINATOR_SET: EnumSet<TokenKind?> =
            EnumSet.of<TokenKind?>(TokenKind.EOF, TokenKind.RBRACKET, TokenKind.SEMI)

        private val DICT_TERMINATOR_SET: EnumSet<TokenKind?> =
            EnumSet.of<TokenKind?>(TokenKind.EOF, TokenKind.RBRACE, TokenKind.SEMI)

        private val EXPR_LIST_TERMINATOR_SET: EnumSet<TokenKind?> = EnumSet.of<TokenKind?>(
            TokenKind.EOF,
            TokenKind.NEWLINE,
            TokenKind.DOC_COMMENT_TRAILING,
            TokenKind.EQUALS,
            TokenKind.RBRACE,
            TokenKind.RBRACKET,
            TokenKind.RPAREN,
            TokenKind.SEMI
        )

        private val EXPR_TERMINATOR_SET: EnumSet<TokenKind?> = EnumSet.of<TokenKind?>(
            TokenKind.COLON,
            TokenKind.COMMA,
            TokenKind.EOF,
            TokenKind.FOR,
            TokenKind.MINUS,
            TokenKind.PERCENT,
            TokenKind.PLUS,
            TokenKind.RBRACKET,
            TokenKind.RPAREN,
            TokenKind.SLASH
        )

        /** "type" is a keyword iff it precedes an identifier (such as in a type alias expression).  */
        private const val TYPE_SOFT_KEYWORD = "type"

        private const val DEBUGGING = false

        // TODO(adonovan): opt: compute this by subtraction.
        private val augmentedAssignments: MutableMap<TokenKind?, TokenKind?> =
            ImmutableMap.Builder<TokenKind?, TokenKind?>()
                .put(TokenKind.PLUS_EQUALS, TokenKind.PLUS)
                .put(TokenKind.MINUS_EQUALS, TokenKind.MINUS)
                .put(TokenKind.STAR_EQUALS, TokenKind.STAR)
                .put(TokenKind.SLASH_EQUALS, TokenKind.SLASH)
                .put(TokenKind.SLASH_SLASH_EQUALS, TokenKind.SLASH_SLASH)
                .put(TokenKind.PERCENT_EQUALS, TokenKind.PERCENT)
                .put(TokenKind.AMPERSAND_EQUALS, TokenKind.AMPERSAND)
                .put(TokenKind.CARET_EQUALS, TokenKind.CARET)
                .put(TokenKind.PIPE_EQUALS, TokenKind.PIPE)
                .put(TokenKind.GREATER_GREATER_EQUALS, TokenKind.GREATER_GREATER)
                .put(TokenKind.LESS_LESS_EQUALS, TokenKind.LESS_LESS)
                .buildOrThrow()

        /**
         * Highest precedence goes last. Based on:
         * http://docs.python.org/2/reference/expressions.html#operator-precedence
         */
        private val operatorPrecedence: MutableList<EnumSet<TokenKind?>?> = ImmutableList.of<EnumSet<TokenKind?>?>(
            EnumSet.of<TokenKind?>(TokenKind.OR),
            EnumSet.of<TokenKind?>(TokenKind.AND),
            EnumSet.of<TokenKind?>(TokenKind.NOT),
            EnumSet.of<TokenKind?>(
                TokenKind.EQUALS_EQUALS,
                TokenKind.NOT_EQUALS,
                TokenKind.LESS,
                TokenKind.LESS_EQUALS,
                TokenKind.GREATER,
                TokenKind.GREATER_EQUALS,
                TokenKind.IN,
                TokenKind.NOT_IN
            ),
            EnumSet.of<TokenKind?>(TokenKind.PIPE),
            EnumSet.of<TokenKind?>(TokenKind.CARET),
            EnumSet.of<TokenKind?>(TokenKind.AMPERSAND),
            EnumSet.of<TokenKind?>(TokenKind.GREATER_GREATER, TokenKind.LESS_LESS),
            EnumSet.of<TokenKind?>(TokenKind.MINUS, TokenKind.PLUS),
            EnumSet.of<TokenKind?>(TokenKind.SLASH, TokenKind.SLASH_SLASH, TokenKind.STAR, TokenKind.PERCENT)
        )

        // Returns a token's string form as used in error messages.
        private fun tokenString(kind: TokenKind, value: Any?): kotlin.String? {
            return if (kind == TokenKind.STRING)
                "\"" + value + "\"" // TODO(adonovan): do proper quotation
            else
                if (value == null) kind.toString() else value.toString()
        }

        // Main entry point for parsing a file.
        @kotlin.jvm.JvmStatic
        fun parseFile(input: ParserInput, options: FileOptions): ParseResult {
            val errors: MutableList<SyntaxError?> = ArrayList<SyntaxError?>()
            val lexer = Lexer(input, errors, options)
            val parser = Parser(lexer, errors, options)

            val profiler: StarlarkFile.ParseProfiler? = profiler
            val profileStartNanos = if (profiler != null) profiler.start() else -1
            try {
                val statements = parser.parseFileInput()
                return Parser.ParseResult(lexer.locs, statements, lexer.getComments(), errors)
            } finally {
                if (profileStartNanos != -1L) {
                    profiler!!.end(profileStartNanos, input.getFile())
                }
            }
        }

        var profiler: StarlarkFile.ParseProfiler? = null

        /** Parses an expression, possibly preceded or followed by comments or whitespace.  */
        @Throws(SyntaxError.Exception::class)
        fun parseExpression(input: ParserInput, options: FileOptions): Expression? {
            return parseValueOrTypeExpr(input, options,  /* isTypeExpr= */false)
        }

        /** Parses a type expression, possibly preceded or followed by comments or whitespace.  */
        @Throws(SyntaxError.Exception::class)
        fun parseTypeExpression(input: ParserInput, options: FileOptions): Expression? {
            return parseValueOrTypeExpr(input, options,  /* isTypeExpr= */true)
        }

        @Throws(SyntaxError.Exception::class)
        private fun parseValueOrTypeExpr(
            input: ParserInput, options: FileOptions, isTypeExpr: Boolean
        ): Expression? {
            val errors: MutableList<SyntaxError?> = ArrayList<SyntaxError?>()
            val lexer = Lexer(input, errors, options)
            val parser = Parser(lexer, errors, options)
            var result: Expression? = null
            try {
                // Skip preceding doc comments (no-ops for an expression).
                while (parser.token.kind == TokenKind.DOC_COMMENT_BLOCK) {
                    parser.nextToken()
                }
                result = if (isTypeExpr) parser.parseTypeExprWithFallback() else parser.parseExpr()
                // Skip following doc comments and newlines (no-ops for an expression).
                while (parser.token.kind == TokenKind.NEWLINE || parser.token.kind == TokenKind.DOC_COMMENT_BLOCK || parser.token.kind == TokenKind.DOC_COMMENT_TRAILING) {
                    parser.nextToken()
                }
                parser.expect(TokenKind.EOF)
            } catch (ex: StackOverflowError) {
                // See rationale at parseFile.
                parser.reportError(
                    lexer.end,
                    ("internal error: stack overflow while parsing Starlark expression <<%s>>. Please report"
                            + " the bug.\n"
                            + "%s"),
                    kotlin.String(input.getContent()),
                    Throwables.getStackTraceAsString(ex)
                )
            }
            if (!errors.isEmpty()) {
                throw SyntaxError.Exception(errors)
            }
            return result
        }

        // Keywords that exist in Python and that we don't parse.
        private val FORBIDDEN_KEYWORDS: EnumSet<TokenKind?> = EnumSet.of<TokenKind?>(
            TokenKind.AS,
            TokenKind.ASSERT,
            TokenKind.CLASS,
            TokenKind.DEL,
            TokenKind.EXCEPT,
            TokenKind.FINALLY,
            TokenKind.FROM,
            TokenKind.GLOBAL,
            TokenKind.IMPORT,
            TokenKind.IS,
            TokenKind.NONLOCAL,
            TokenKind.RAISE,
            TokenKind.TRY,
            TokenKind.WITH,
            TokenKind.WHILE,
            TokenKind.YIELD
        )

        /** Narrows a long to an int if possible.  */
        private fun narrowNumberType(value: Long): Number {
            if (value == value.toInt().toLong()) {
                return value.toInt()
            } else {
                return value
            }
        }

        /** Narrows a BigInteger to an int or long if possible.  */
        private fun narrowNumberType(value: BigInteger): Number {
            if (value.bitLength() >= 64) {
                return value
            } else if (value.bitLength() >= 32) {
                return value.longValueExact()
            } else {
                return value.intValueExact()
            }
        }

        private fun isTypeSoftKeyword(node: Node?): Boolean {
            return node is Identifier && node.getName() == TYPE_SOFT_KEYWORD
        }
    }
}
