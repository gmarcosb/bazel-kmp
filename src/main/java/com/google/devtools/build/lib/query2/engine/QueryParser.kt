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
package com.google.devtools.build.lib.query2.engine

import com.google.common.base.Preconditions
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.util.stream.Collectors
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.MutableIterator
import kotlin.collections.MutableList
import kotlin.collections.MutableMap

/**
 * LL(1) recursive descent parser for the Blaze query language, revision 2.
 * 
 * 
 * In the grammar below, non-terminals are lowercase and terminals are uppercase, or character
 * literals.
 * 
 * <pre>
 * expr ::= WORD
 * | LET WORD = expr IN expr
 * | '(' expr ')'
 * | WORD '(' expr ( ',' expr ) * ')'
 * | expr INTERSECT expr
 * | expr '^' expr
 * | expr UNION expr
 * | expr '+' expr
 * | expr EXCEPT expr
 * | expr '-' expr
 * | SET '(' WORD * ')'
</pre> * 
 */
class QueryParser(
    private val tokens: MutableList<Lexer.Token?>,
    private val functions: MutableMap<String?, QueryFunction>
) {
    private var token: Lexer.Token? = null // current lookahead token
    private val tokenIterator: MutableIterator<Lexer.Token?>

    init {
        this.tokenIterator = tokens.iterator()
        nextToken()
    }

    /** Throws a syntax error exception.  */
    @CanIgnoreReturnValue
    @Throws(QuerySyntaxException::class)
    private fun syntaxError(token: Lexer.Token): QuerySyntaxException? {
        var message = "premature end of input"
        if (token.kind != Lexer.TokenKind.EOF) {
            val buf = StringBuilder("syntax error at '")
            var sep = ""
            var index = tokens.indexOf(token)
            val max: Int = min(tokens.size - 1, index + 3) // 3 tokens of context
            while (index < max
            ) {
                buf.append(sep).append(tokens.get(index))
                sep = " "
                ++index
            }
            buf.append("'")
            message = buf.toString()
        }
        throw QuerySyntaxException(message)
    }

    /** Throws an exception indicating that the current token is an unknown function name.  */
    @CanIgnoreReturnValue
    @Throws(QuerySyntaxException::class)
    private fun unknownFunctionError(token: Lexer.Token): QuerySyntaxException? {
        Preconditions.checkArgument(token.kind == Lexer.TokenKind.WORD)
        val buf = StringBuilder("unknown function '")
        buf.append(token)
        buf.append("' at '")
        appendInputContext(buf, token)
        buf.append("'; expected one of ['")
        buf.append(functions.keys.stream().sorted().collect(Collectors.joining("', '")))
        buf.append("']")
        throw QuerySyntaxException(buf.toString())
    }

    /**
     * Throws an exception indicating that the current function is being called with the wrong number
     * of arguments.
     */
    @CanIgnoreReturnValue
    @Throws(QuerySyntaxException::class)
    private fun functionArgumentCountError(
        function: QueryFunction, description: String
    ): QuerySyntaxException? {
        val buf = StringBuilder(description)
        buf.append(" arguments to function '")
        buf.append(function.getName())
        buf.append("' at '")
        appendInputContext(buf, token)
        buf.append("'")
        throw QuerySyntaxException(buf.toString())
    }

    /**
     * Throws an exception indicating that the current function is being called with too few
     * arguments.
     */
    @CanIgnoreReturnValue
    @Throws(QuerySyntaxException::class)
    private fun tooFewArgumentsError(function: QueryFunction): QuerySyntaxException? {
        throw functionArgumentCountError(function, "too few")
    }

    /**
     * Throws an exception indicating that the current function is being called with too many
     * arguments.
     */
    @CanIgnoreReturnValue
    @Throws(QuerySyntaxException::class)
    private fun tooManyArgumentsError(function: QueryFunction): QuerySyntaxException? {
        throw functionArgumentCountError(function, "too many")
    }

    private fun appendInputContext(buf: StringBuilder, token: Lexer.Token?) {
        var sep = ""
        var index = tokens.indexOf(token)
        val max: Int = min(tokens.size - 1, index + 3) // 3 tokens of context
        while (index < max
        ) {
            buf.append(sep).append(tokens.get(index))
            sep = " "
            ++index
        }
    }

    /**
     * Consumes the current token. If it is not of the specified (expected) kind, throws [ ]. Returns the value associated with the consumed token, if any.
     */
    @CanIgnoreReturnValue
    @Throws(QuerySyntaxException::class)
    private fun consume(kind: Lexer.TokenKind?): String {
        if (token!!.kind != kind) {
            throw syntaxError(token!!)
        }
        val word = token!!.word
        nextToken()
        return word
    }

    /**
     * Consumes the current token, which must be a WORD containing an integer literal. Returns that
     * integer, or throws a [QuerySyntaxException] otherwise.
     */
    @Throws(QuerySyntaxException::class)
    private fun consumeIntLiteral(): Int {
        val intString = consume(Lexer.TokenKind.WORD)
        try {
            return intString.toInt()
        } catch (e: NumberFormatException) {
            throw QuerySyntaxException("expected an integer literal: '" + intString + "'")
        }
    }

    private fun nextToken() {
        if (token == null || token!!.kind != Lexer.TokenKind.EOF) {
            token = tokenIterator.next()
        }
    }

    /**
     * 
     * 
     * <pre>
     * expr ::= primary
     * | expr INTERSECT expr
     * | expr '^' expr
     * | expr UNION expr
     * | expr '+' expr
     * | expr EXCEPT expr
     * | expr '-' expr
    </pre> * 
     */
    @Throws(QuerySyntaxException::class)
    private fun parseExpression(): QueryExpression {
        // All operators are left-associative and of equal precedence.
        return parseBinaryOperatorTail(parsePrimary()!!)
    }

    /**
     * 
     * 
     * <pre>
     * tail ::= ( <op> <primary> )*
    </primary></op></pre> * 
     * 
     * 
     * All operators have equal precedence. This factoring is required for left-associative binary
     * operators in LL(1).
     */
    @Throws(QuerySyntaxException::class)
    private fun parseBinaryOperatorTail(lhs: QueryExpression): QueryExpression {
        var lhs = lhs
        if (!Lexer.Companion.BINARY_OPERATORS.contains(token!!.kind)) {
            return lhs
        }

        val operands: MutableList<QueryExpression?> = ArrayList<QueryExpression?>()
        operands.add(lhs)
        var lastOperator = token!!.kind

        while (Lexer.Companion.BINARY_OPERATORS.contains(token!!.kind)) {
            val operator = token!!.kind
            consume(operator)
            if (operator != lastOperator) {
                lhs = BinaryOperatorExpression(lastOperator, operands)
                operands.clear()
                operands.add(lhs)
                lastOperator = operator
            }
            val rhs = parsePrimary()
            operands.add(rhs)
        }
        return BinaryOperatorExpression(lastOperator, operands)
    }

    /**
     * 
     * 
     * <pre>
     * primary ::= WORD
     * | WORD '(' arg ( ',' arg ) * ')'
     * | LET WORD = expr IN expr
     * | '(' expr ')'
     * | SET '(' WORD * ')' arg ::= expr
     * | WORD
     * | INT
    </pre> * 
     */
    @Throws(QuerySyntaxException::class)
    private fun parsePrimary(): QueryExpression? {
        when (token!!.kind) {
            Lexer.TokenKind.WORD -> {
                val wordToken = token
                val word = consume(Lexer.TokenKind.WORD)
                if (token!!.kind == Lexer.TokenKind.LPAREN) {
                    val function: QueryFunction = functions.get(word)!!
                    if (function == null) {
                        throw unknownFunctionError(wordToken!!)
                    }
                    val args: MutableList<QueryEnvironment.Argument?> = ArrayList<QueryEnvironment.Argument?>()
                    var tokenKind = Lexer.TokenKind.LPAREN
                    var argsSeen = 0
                    for (type in function.getArgumentTypes()) {
                        if (token!!.kind == Lexer.TokenKind.RPAREN) {
                            // Got rparen instead of argument-separating comma.
                            if (argsSeen >= function.getMandatoryArguments()) {
                                break
                            } else {
                                throw tooFewArgumentsError(function)
                            }
                        }

                        // Consume lparen on first iteration, comma on subsequent iterations.
                        consume(tokenKind)
                        tokenKind = Lexer.TokenKind.COMMA
                        if (argsSeen == 0 && token!!.kind == Lexer.TokenKind.RPAREN) {
                            // Got rparen instead of mandatory first argument.
                            throw tooFewArgumentsError(function)
                        }
                        when (type) {
                            QueryEnvironment.ArgumentType.EXPRESSION -> args.add(
                                QueryEnvironment.Argument.Companion.of(
                                    parseExpression()
                                )
                            )

                            QueryEnvironment.ArgumentType.WORD -> args.add(
                                QueryEnvironment.Argument.Companion.of(
                                    consume(
                                        Lexer.TokenKind.WORD
                                    )
                                )
                            )

                            QueryEnvironment.ArgumentType.INTEGER -> args.add(
                                QueryEnvironment.Argument.Companion.of(
                                    consumeIntLiteral()
                                )
                            )
                        }

                        argsSeen++
                    }

                    if (token!!.kind == Lexer.TokenKind.COMMA && argsSeen > 0) {
                        throw tooManyArgumentsError(function)
                    }
                    consume(Lexer.TokenKind.RPAREN)
                    return FunctionExpression(function, args)
                } else {
                    return validateTargetLiteral(word)
                }
            }

            Lexer.TokenKind.LET -> {
                consume(Lexer.TokenKind.LET)
                val name = consume(Lexer.TokenKind.WORD)
                consume(Lexer.TokenKind.EQUALS)
                val varExpr = parseExpression()
                consume(Lexer.TokenKind.IN)
                val bodyExpr = parseExpression()
                return LetExpression(name, varExpr, bodyExpr)
            }

            Lexer.TokenKind.LPAREN -> {
                consume(Lexer.TokenKind.LPAREN)
                val expr = parseExpression()
                consume(Lexer.TokenKind.RPAREN)
                return expr
            }

            Lexer.TokenKind.SET -> {
                nextToken()
                consume(Lexer.TokenKind.LPAREN)
                val words: MutableList<TargetLiteral?> = ArrayList<TargetLiteral?>()
                while (token!!.kind == Lexer.TokenKind.WORD) {
                    words.add(validateTargetLiteral(consume(Lexer.TokenKind.WORD)))
                }
                consume(Lexer.TokenKind.RPAREN)
                return SetExpression(words)
            }

            else -> throw syntaxError(token!!)
        }
    }

    companion object {
        /** Scan and parse the specified query expression.  */
        @Throws(QuerySyntaxException::class)
        fun parse(query: String?, env: QueryEnvironment<*>): QueryExpression {
            val functions = HashMap<String?, QueryFunction>()
            for (queryFunction in env.getFunctions()) {
                functions.put(queryFunction.getName(), queryFunction)
            }
            return parse(query, functions)
        }

        @Throws(QuerySyntaxException::class)
        fun parse(query: String?, functions: MutableMap<String?, QueryFunction>): QueryExpression {
            val parser = QueryParser(Lexer.Companion.scan(query), functions)
            val expr = parser.parseExpression()
            if (parser.token!!.kind != Lexer.TokenKind.EOF) {
                throw QuerySyntaxException(
                    String.format(
                        "unexpected token '%s' after query expression '%s'",
                        parser.token, expr.toTrunctatedString()
                    )
                )
            }
            return expr
        }

        /**
         * Unquoted words may not start with a hyphen or asterisk, even though relative target names may
         * start with those characters.
         */
        @Throws(QuerySyntaxException::class)
        private fun validateTargetLiteral(word: String): TargetLiteral {
            if (word.startsWith("-") || word.startsWith("*")) {
                throw QuerySyntaxException(
                    "target literal must not begin with " + "(" + word.get(0) + "): " + word
                )
            }
            return TargetLiteral(word)
        }
    }
}
