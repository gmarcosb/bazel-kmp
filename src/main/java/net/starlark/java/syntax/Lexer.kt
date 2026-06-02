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
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import java.lang.Double
import java.util.*
import kotlin.Any
import kotlin.Boolean
import kotlin.Char
import kotlin.CharArray
import kotlin.Int
import kotlin.Number
import kotlin.NumberFormatException
import kotlin.String

/** A scanner for Starlark.  */
internal class Lexer(input: ParserInput, errors: MutableList<SyntaxError?>, options: FileOptions) {
    // --- These fields are accessed directly by the parser: ---
    // Mapping from file offsets to Locations.
    @kotlin.jvm.JvmField
    val locs: FileLocations

    // Information about current token. Updated by nextToken.
    // raw and value are defined only for STRING, INT, FLOAT, IDENTIFIER, and COMMENT.
    // TODO(adonovan): rename s/xyz/tokenXyz/
    @kotlin.jvm.JvmField
    var kind: TokenKind? = null
    @kotlin.jvm.JvmField
    var start: Int = 0 // start offset
    @kotlin.jvm.JvmField
    var end: Int = 0 // end offset
    @kotlin.jvm.JvmField
    var value: Any? = null // String, Integer/Long/BigInteger, or Double value of token

    // --- end of parser-visible fields ---
    private val errors: MutableList<SyntaxError?>

    private val options: FileOptions

    // Input buffer and position
    private val buffer: CharArray
    private var pos: Int

    // The stack of enclosing indentation levels in spaces.
    // The first (outermost) element is always zero.
    private val indentStack = Stack<Int?>()

    private val comments: ImmutableList.Builder<Comment?> = ImmutableList.builder<Comment?>()

    // The number of unclosed open-parens ("(", '{', '[') at the current point in the stream.
    // When this is nonzero, whitespace is handled differently and doc comments are treated as
    // ordinary comments.
    private var openParenStackDepth = 0

    // True after a NEWLINE token. In other words, we are outside an
    // expression and we have to check the indentation.
    private var checkIndentation: Boolean

    // Number of saved INDENT (>0) or OUTDENT (<0) tokens detected but not yet returned.
    private var dents: Int

    // True iff no token other than whitespace or comments (NEWLINE, INDENT, OUTDENT, or
    // DOC_COMMENT_BLOCK, or DOC_COMMENT_TRAILING) has been emitted since the last newline.
    private var lineOnlyWhitespaceOrComments: Boolean

    fun getComments(): ImmutableList<Comment?> {
        return comments.build()
    }

    /**
     * Reads the next token, updating the Lexer's token fields. The end state is EOF, after which any
     * further calls to `nextToken()` will produce only EOF.
     */
    fun nextToken() {
        val afterNewline = kind == TokenKind.NEWLINE || kind == TokenKind.DOC_COMMENT_BLOCK
        tokenize()
        Preconditions.checkState(kind != null)

        // Always end with a NEWLINE (or DOC_COMMENT_BLOCK) token, even if no '\n' in input, to simplify
        // parser's logic. (Note that Python also always ends with a NEWLINE.)
        if (kind == TokenKind.EOF && !afterNewline) {
            kind = TokenKind.NEWLINE
        }
        if (kind != TokenKind.NEWLINE && kind != TokenKind.INDENT && kind != TokenKind.OUTDENT && kind != TokenKind.DOC_COMMENT_BLOCK && kind != TokenKind.DOC_COMMENT_TRAILING) {
            lineOnlyWhitespaceOrComments = false
        }
    }

    private fun popParen() {
        if (openParenStackDepth == 0) {
            // TODO(adonovan): fix: the input ')' should not report an indentation error.
            error("indentation error", pos - 1)
        } else {
            openParenStackDepth--
        }
    }

    private fun error(message: String?, pos: Int) {
        errors.add(SyntaxError(locs.getLocation(pos), message))
    }

    private fun setToken(kind: TokenKind?, start: Int, end: Int) {
        this.kind = kind
        this.start = start
        this.end = end
        this.value = null
    }

    // setValue sets the value associated with a STRING, FLOAT, INT,
    // IDENTIFIER, or COMMENT token, and records the raw text of the token.
    private fun setValue(value: Any?) {
        this.value = value
    }

    /** Returns the raw input text associated with the current token.  */
    fun getRaw(): String {
        return bufferSlice(start, end)
    }

    /**
     * Parses an end-of-line sequence, handling statement indentation correctly.
     * 
     * 
     * UNIX newlines are assumed (LF). Carriage returns are always ignored.
     */
    private fun newline() {
        lineOnlyWhitespaceOrComments = true
        if (openParenStackDepth > 0) {
            newlineInsideExpression() // in an expression: ignore space
        } else {
            checkIndentation = true
            setToken(TokenKind.NEWLINE, pos - 1, pos)
        }
    }

    private fun newlineInsideExpression() {
        while (pos < buffer.size) {
            when (buffer[pos]) {
                ' ', '\t', '\r' -> pos++
                else -> return
            }
        }
    }

    /**
     * Computes indentation (updates dent) and advances [.pos] to the first character that is
     * neither whitespace nor a non-doc comment, after first skipping any lines consisting only of
     * whitespace and/or non-doc comments.
     * 
     * 
     * Invoked at the beginning of a file or after a newline (except inside parenthised
     * expressions).
     */
    private fun computeIndentation() {
        var indentLen = 0
        while (pos < buffer.size) {
            val c = buffer[pos]
            if (c == ' ') {
                indentLen++
                pos++
            } else if (c == '\r') {
                pos++
            } else if (c == '\t') {
                indentLen++
                pos++
                error("Tab characters are not allowed for indentation. Use spaces instead.", pos)
            } else if (c == '\n') { // entirely blank line: discard
                indentLen = 0
                pos++
            } else if (c == '#') { // line containing only indented comment
                if (peek(1) == ':'.code && openParenStackDepth == 0) {
                    // Doc comment. Caller must process it and emit the token for it (and, if this is a
                    // DOC_COMMENT_TRAILING, also emit the token for the following newline).
                    return
                }
                val oldPos = pos
                scanToNewline()
                addComment(oldPos, pos)
                indentLen = 0
            } else { // printing character
                break
            }
        }

        if (pos == buffer.size) {
            indentLen = 0
        } // trailing space on last line


        var peekedIndent = indentStack.peek()!!
        if (peekedIndent < indentLen) { // push a level
            indentStack.push(indentLen)
            dents++
        } else if (peekedIndent > indentLen) { // pop one or more levels
            while (peekedIndent > indentLen) {
                indentStack.pop()
                dents--
                peekedIndent = indentStack.peek()!!
            }

            if (peekedIndent < indentLen) {
                error("indentation error", pos - 1)
            }
        }
    }

    /**
     * Returns true if current position is in the middle of a triple quote
     * delimiter (3 x quot), and advances 'pos' by two if so.
     */
    private fun skipTripleQuote(quot: Char): Boolean {
        if (peek(0) == quot.code && peek(1) == quot.code) {
            pos += 2
            return true
        } else {
            return false
        }
    }

    /**
     * Scans a string literal delimited by 'quot', containing escape sequences.
     * 
     * 
     * ON ENTRY: 'pos' is 1 + the index of the first delimiter
     * ON EXIT: 'pos' is 1 + the index of the last delimiter.
     */
    private fun escapedStringLiteral(quot: Char, isRaw: Boolean) {
        val literalStartPos = if (isRaw) pos - 2 else pos - 1
        val inTriplequote = skipTripleQuote(quot)
        // more expensive second choice that expands escaped into a buffer
        val literal = StringBuilder()
        while (pos < buffer.size) {
            var c = buffer[pos]
            pos++
            when (c) {
                '\n' -> if (inTriplequote) {
                    literal.append(c)
                    break
                } else {
                    error("unclosed string literal", literalStartPos)
                    setToken(TokenKind.STRING, literalStartPos, pos)
                    setValue(literal.toString())
                    return
                }

                '\\' -> {
                    if (pos == buffer.size) {
                        error("unclosed string literal", literalStartPos)
                        setToken(TokenKind.STRING, literalStartPos, pos)
                        setValue(literal.toString())
                        return
                    }
                    if (isRaw) {
                        // Insert \ and the following character.
                        // As in Python, it means that a raw string can never end with a single \.
                        literal.append('\\')
                        if (peek(0) == '\r'.code && peek(1) == '\n'.code) {
                            literal.append("\n")
                            pos += 2
                        } else if (buffer[pos] == '\r' || buffer[pos] == '\n') {
                            literal.append("\n")
                            pos += 1
                        } else {
                            literal.append(buffer[pos])
                            pos += 1
                        }
                        break
                    }
                    c = buffer[pos]
                    pos++
                    when (c) {
                        '\r' -> if (peek(0) == '\n'.code) {
                            pos += 1
                            break
                        } else {
                            break
                        }

                        '\n' -> {}
                        'a' -> literal.append('\u0007')
                        'b' -> literal.append('\b')
                        'f' -> literal.append('\f')
                        'n' -> literal.append('\n')
                        'r' -> literal.append('\r')
                        't' -> literal.append('\t')
                        'v' -> literal.append('\u000b')
                        '\\' -> literal.append('\\')
                        '\'' -> literal.append('\'')
                        '"' -> literal.append('"')
                        '0', '1', '2', '3', '4', '5', '6', '7' -> {
                            // octal escape
                            var octal: Int = c.code - '0'.code
                            if (pos < buffer.size) {
                                c = buffer[pos]
                                if (c >= '0' && c <= '7') {
                                    pos++
                                    octal = (octal shl 3) or (c.code - '0'.code)
                                    if (pos < buffer.size) {
                                        c = buffer[pos]
                                        if (c >= '0' && c <= '7') {
                                            pos++
                                            octal = (octal shl 3) or (c.code - '0'.code)
                                        }
                                    }
                                }
                            }
                            if (octal > 0xff) {
                                error("octal escape sequence out of range (maximum is \\377)", pos - 1)
                            } else if (options.stringLiteralsAreAsciiOnly() && octal >= 0x80) {
                                error("octal escape sequence denotes non-ASCII character", pos - 1)
                            }
                            literal.append((octal and 0xff).toChar())
                        }

                        'N', 'u', 'U' -> {
                            // unknown char escape => "\literal"
                            error("invalid escape sequence: \\" + c + ". Use '\\\\' to insert '\\'.", pos - 1)
                            literal.append('\\')
                            literal.append(c)
                        }

                        else -> {
                            error("invalid escape sequence: \\" + c + ". Use '\\\\' to insert '\\'.", pos - 1)
                            literal.append('\\')
                            literal.append(c)
                        }
                    }
                }

                '\'', '"' -> if (c != quot || (inTriplequote && !skipTripleQuote(quot))) {
                    // Non-matching quote, treat it like a regular char.
                    literal.append(c)
                } else {
                    // Matching close-delimiter, all done.
                    setToken(TokenKind.STRING, literalStartPos, pos)
                    setValue(literal.toString())
                    return
                }

                else -> {
                    literal.append(c)
                    if (options.stringLiteralsAreAsciiOnly() && c.code >= 0x80) {
                        error("string literal contains non-ASCII character", pos - 1)
                    }
                }
            }
        }
        error("unclosed string literal", literalStartPos)
        setToken(TokenKind.STRING, literalStartPos, pos)
        setValue(literal.toString())
    }

    /**
     * Scans a string literal delimited by 'quot'.
     * 
     * 
     *  *  ON ENTRY: 'pos' is 1 + the index of the first delimiter
     *  *  ON EXIT: 'pos' is 1 + the index of the last delimiter.
     * 
     * 
     * @param isRaw if true, do not escape the string.
     */
    private fun stringLiteral(quot: Char, isRaw: Boolean) {
        val literalStartPos = if (isRaw) pos - 2 else pos - 1
        val contentStartPos = pos

        // Don't even attempt to parse triple-quotes here.
        if (skipTripleQuote(quot)) {
            pos -= 2
            escapedStringLiteral(quot, isRaw)
            return
        }

        // first quick optimistic scan for a simple non-escaped string
        while (pos < buffer.size) {
            val c = buffer[pos++]
            when (c) {
                '\n' -> {
                    error("unclosed string literal", literalStartPos)
                    setToken(TokenKind.STRING, literalStartPos, pos)
                    setValue(bufferSlice(contentStartPos, pos - 1))
                    return
                }

                '\\' -> {
                    if (isRaw) {
                        if (peek(0) == '\r'.code && peek(1) == '\n'.code) {
                            // There was a CRLF after the newline. No shortcut possible, since it needs to be
                            // transformed into a single LF.
                            pos = contentStartPos
                            escapedStringLiteral(quot, true)
                            return
                        } else {
                            pos++
                            break
                        }
                    }
                    // oops, hit an escape, need to start over & build a new string buffer
                    pos = contentStartPos
                    escapedStringLiteral(quot, false)
                    return
                }

                '\'', '"' -> if (c == quot) {
                    // close-quote, all done.
                    setToken(TokenKind.STRING, literalStartPos, pos)
                    setValue(bufferSlice(contentStartPos, pos - 1))
                    // If we're requiring ASCII-only, do another scan for validation.
                    if (options.stringLiteralsAreAsciiOnly()) {
                        var i = contentStartPos
                        while (i < pos - 1) {
                            if (buffer[i].code >= 0x80) {
                                // Can report multiple errors per string literal.
                                error("string literal contains non-ASCII character", i)
                            }
                            i++
                        }
                    }
                    return
                }

                else -> {}
            }
        }

        // If the current position is beyond the end of the file, need to move it backwards
        // Possible if the file ends with `r"\` (unclosed raw string literal with a backslash)
        if (pos > buffer.size) {
            pos = buffer.size
        }

        error("unclosed string literal", literalStartPos)
        setToken(TokenKind.STRING, literalStartPos, pos)
        setValue(bufferSlice(contentStartPos, pos))
    }

    /**
     * Scans a doc comment block.
     * 
     * 
     *  * ON ENTRY: 'pos' is at newline (or EOF) terminating the doc comment's first line.
     *  * ON EXIT: for a doc comment block, 'pos' is the index of the first following non-comment,
     * non-whitespace character (or of EOF); for a trailing doc comment, 'pos' is unchanged.
     * 
     */
    private fun docComments(first: Comment, firstStartPos: Int, isBlock: Boolean) {
        var lastEndPos = pos
        val docComments = ArrayList<Comment?>()
        docComments.add(first)
        if (isBlock) {
            var prevLine = first.getStartLocation().line()
            while (peek(0) == '\n'.code) {
                checkIndentation = false
                computeIndentation()
                if (peek(0) != '#'.code || peek(1) != ':'.code) {
                    // Not a doc comment; terminate the doc comment block.
                    break
                }
                val line = locs.getLocation(pos).line()
                if (line != prevLine + 1) {
                    // We are at "#:", but computeIndentation() skipped one or more lines containing only
                    // whitespace and non-doc comments. Terminate the doc comment block.
                    break
                }
                prevLine = line
                val startPos = pos
                scanToNewline()
                val comment = addComment(startPos, pos)
                lastEndPos = pos
                docComments.add(comment)
            }
            setToken(TokenKind.DOC_COMMENT_BLOCK, firstStartPos, lastEndPos)
        } else {
            setToken(TokenKind.DOC_COMMENT_TRAILING, firstStartPos, lastEndPos)
        }
        setValue(DocComments(docComments))
    }

    private fun scanToNewline() {
        while (pos < buffer.size) {
            if (buffer[pos] == '\n') {
                break
            }
            pos++
        }
    }

    // Constructs a lexer which tokenizes the parser input.
    // Errors are appended to errors.
    init {
        this.locs = FileLocations.Companion.create(input.getContent(), input.getFile())
        this.buffer = input.getContent()
        this.pos = 0
        this.errors = errors
        this.options = options
        this.checkIndentation = true
        this.dents = 0
        this.lineOnlyWhitespaceOrComments = true

        indentStack.push(0)
    }

    /**
     * Scans an identifier or keyword.
     * 
     * 
     * ON ENTRY: 'pos' is 1 + the index of the first char in the identifier.
     * ON EXIT: 'pos' is 1 + the index of the last char in the identifier.
     */
    private fun identifierOrKeyword() {
        val oldPos = pos - 1
        // We intern identifiers and keywords to avoid retaining redundant String objects via the AST.
        //
        // The parser handles interning of string literal values. Benchmarking did not show significant
        // benefit to any further internment. See discussion on Google-internal cl/385193833 for
        // details.
        val id: String? = scanIdentifier().intern()
        var kind: TokenKind? = keywordMap.get(id)
        if (kind == null && options.allowTypeSyntax()) {
            kind = typeSyntaxExtraKeywordMap.get(id)
        }
        if (kind == null) {
            setToken(TokenKind.IDENTIFIER, oldPos, pos)
            // setValue allocates a new String for the raw text, but it's not retained so we don't
            // bother interning it.
            setValue(id)
        } else {
            setToken(kind, oldPos, pos)
        }
    }

    private fun scanIdentifier(): String {
        // Keep consistent with Identifier.isValid.
        // TODO(laurentlb): Handle Unicode letters.
        val oldPos = pos - 1
        while (pos < buffer.size) {
            when (buffer[pos]) {
                '_', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> pos++
                else -> return bufferSlice(oldPos, pos)
            }
        }
        return bufferSlice(oldPos, pos)
    }

    /**
     * Tokenizes a two-char operator.
     * @return true if it tokenized an operator
     */
    private fun tokenizeTwoChars(): Boolean {
        if (pos + 2 >= buffer.size) {
            return false
        }
        val c1 = buffer[pos]
        val c2 = buffer[pos + 1]
        var tok: TokenKind? = null
        if (c2 == '=') {
            tok = EQUAL_TOKENS.get(c1)
        } else if (c2 == '*' && c1 == '*') {
            tok = TokenKind.STAR_STAR
        }
        if (tok == null) {
            return false
        } else {
            setToken(tok, pos, pos + 2)
            return true
        }
    }

    // Returns the ith unconsumed char, or -1 for EOF.
    private fun peek(i: Int): Int {
        return if (pos + i < buffer.size) buffer[pos + i].code else -1
    }

    // Consumes a char and returns the next unconsumed char, or -1 for EOF.
    private fun next(): Int {
        pos++
        return peek(0)
    }

    /**
     * Scans for one token starting at the current position in the character buffer of file contents
     * provided to the constructor. Updates the current token, and sets [.pos] to the next
     * scanning position.
     */
    private fun tokenize() {
        if (checkIndentation) {
            checkIndentation = false
            computeIndentation()
        }

        // Return saved indentation tokens.
        if (dents != 0) {
            if (dents < 0) {
                dents++
                setToken(TokenKind.OUTDENT, pos - 1, pos)
            } else {
                dents--
                setToken(TokenKind.INDENT, pos - 1, pos)
            }
            return
        }

        // TODO(adonovan): cleanup: replace break after setToken with return,
        // and eliminate null-check of this.kind.
        kind = null
        while (pos < buffer.size) {
            if (tokenizeTwoChars()) {
                pos += 2
                return
            }
            val c = buffer[pos]
            pos++
            when (c) {
                '{' -> {
                    setToken(TokenKind.LBRACE, pos - 1, pos)
                    openParenStackDepth++
                }

                '}' -> {
                    setToken(TokenKind.RBRACE, pos - 1, pos)
                    popParen()
                }

                '(' -> {
                    setToken(TokenKind.LPAREN, pos - 1, pos)
                    openParenStackDepth++
                }

                ')' -> {
                    setToken(TokenKind.RPAREN, pos - 1, pos)
                    popParen()
                }

                '[' -> {
                    setToken(TokenKind.LBRACKET, pos - 1, pos)
                    openParenStackDepth++
                }

                ']' -> {
                    setToken(TokenKind.RBRACKET, pos - 1, pos)
                    popParen()
                }

                '>' -> if (peek(0) == '>'.code && peek(1) == '='.code) {
                    setToken(TokenKind.GREATER_GREATER_EQUALS, pos - 1, pos + 2)
                    pos += 2
                } else if (peek(0) == '>'.code) {
                    setToken(TokenKind.GREATER_GREATER, pos - 1, pos + 1)
                    pos += 1
                } else {
                    setToken(TokenKind.GREATER, pos - 1, pos)
                }

                '<' -> if (peek(0) == '<'.code && peek(1) == '='.code) {
                    setToken(TokenKind.LESS_LESS_EQUALS, pos - 1, pos + 2)
                    pos += 2
                } else if (peek(0) == '<'.code) {
                    setToken(TokenKind.LESS_LESS, pos - 1, pos + 1)
                    pos += 1
                } else {
                    setToken(TokenKind.LESS, pos - 1, pos)
                }

                ':' -> setToken(TokenKind.COLON, pos - 1, pos)
                ',' -> setToken(TokenKind.COMMA, pos - 1, pos)
                '+' -> setToken(TokenKind.PLUS, pos - 1, pos)
                '-' -> if (peek(0) == '>'.code) {
                    setToken(TokenKind.RARROW, pos - 1, pos + 1)
                    pos += 1
                } else {
                    setToken(TokenKind.MINUS, pos - 1, pos)
                }

                '|' -> setToken(TokenKind.PIPE, pos - 1, pos)
                '=' -> setToken(TokenKind.EQUALS, pos - 1, pos)
                '%' -> setToken(TokenKind.PERCENT, pos - 1, pos)
                '~' -> setToken(TokenKind.TILDE, pos - 1, pos)
                '&' -> setToken(TokenKind.AMPERSAND, pos - 1, pos)
                '^' -> setToken(TokenKind.CARET, pos - 1, pos)
                '/' -> if (peek(0) == '/'.code && peek(1) == '='.code) {
                    setToken(TokenKind.SLASH_SLASH_EQUALS, pos - 1, pos + 2)
                    pos += 2
                } else if (peek(0) == '/'.code) {
                    setToken(TokenKind.SLASH_SLASH, pos - 1, pos + 1)
                    pos += 1
                } else {
                    // /= is handled by tokenizeTwoChars.
                    setToken(TokenKind.SLASH, pos - 1, pos)
                }

                ';' -> setToken(TokenKind.SEMI, pos - 1, pos)
                '*' -> setToken(TokenKind.STAR, pos - 1, pos)
                ' ', '\t', '\r' -> {}
                '\\' ->           // Backslash character is valid only at the end of a line (or in a string)
                    if (peek(0) == '\n'.code) {
                        pos += 1 // skip the end of line character
                    } else if (peek(0) == '\r'.code && peek(1) == '\n'.code) {
                        pos += 2 // skip the CRLF at the end of line
                    } else {
                        setToken(TokenKind.ILLEGAL, pos - 1, pos)
                        setValue(c.toString())
                    }

                '\n' -> newline()
                '#' -> {
                    val oldPos = pos - 1
                    scanToNewline()
                    val comment = addComment(oldPos, pos)
                    if (comment.hasDocCommentPrefix() && openParenStackDepth == 0) {
                        docComments(comment, oldPos,  /* isBlock= */lineOnlyWhitespaceOrComments)
                    }
                }

                '\'', '\"' -> stringLiteral(c, false)
                else -> {
                    // detect raw strings, e.g. r"str"
                    if (c == 'r') {
                        val c0 = peek(0)
                        if (c0 == '\''.code || c0 == '\"'.code) {
                            pos++
                            stringLiteral(c0.toChar(), true)
                            break
                        }
                    }

                    // int or float literal, or dot, or ellipsis
                    if (c == '.' || isdigit(c.code)) {
                        pos-- // unconsume
                        scanNumberOrDotOrEllipsis(c.code)
                        break
                    }

                    if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_') {
                        identifierOrKeyword()
                    } else {
                        error("invalid character: '" + c + "'", pos - 1)
                    }
                }
            } // switch
            if (kind != null) { // stop here if we scanned a token
                return
            }
        } // while


        if (indentStack.size > 1) { // top of stack is always zero
            setToken(TokenKind.NEWLINE, pos - 1, pos)
            while (indentStack.size > 1) {
                indentStack.pop()
                dents--
            }
            return
        }

        setToken(TokenKind.EOF, pos, pos)
    }

    // Scans a number (INT or FLOAT) or DOT or ELLIPSIS.
    // Precondition: c == peek(0) (a dot or digit)
    //
    // TODO(adonovan): make this the precondition for all scan functions;
    // currently most assume their argument c has been consumed already.
    private fun scanNumberOrDotOrEllipsis(c: Int) {
        var c = c
        val start = this.pos
        var fraction = false
        var exponent = false

        if (c == '.'.code) {
            // dot or ellipsis or start of fraction
            if (!isdigit(peek(1))) {
                if (peek(1) == '.'.code && peek(2) == '.'.code) {
                    pos += 3 // consume '...'
                    setToken(TokenKind.ELLIPSIS, start, pos)
                    return
                } else {
                    pos++ // consume '.'
                    setToken(TokenKind.DOT, start, pos)
                    return
                }
            }
            fraction = true
        } else if (c == '0'.code) {
            // hex, octal, binary or float
            c = next()
            if (c == '.'.code) {
                fraction = true
            } else if (c == 'x'.code || c == 'X'.code) {
                // hex
                c = next()
                if (!isxdigit(c)) {
                    error("invalid hex literal", start)
                }
                while (isxdigit(c)) {
                    c = next()
                }
            } else if (c == 'o'.code || c == 'O'.code) {
                // octal
                c = next()
                while (isdigit(c)) {
                    c = next()
                }
            } else if (c == 'b'.code || c == 'B'.code) {
                // binary
                c = next()
                if (!isbdigit(c)) {
                    error("invalid binary literal", start)
                }
                while (isbdigit(c)) {
                    c = next()
                }
            } else {
                // "0" or float or obsolete octal "0755"
                while (isdigit(c)) {
                    c = next()
                }
                if (c == '.'.code) {
                    fraction = true
                } else if (c == 'e'.code || c == 'E'.code) {
                    exponent = true
                }
            }
        } else {
            // decimal
            while (isdigit(c)) {
                c = next()
            }
            if (c == '.'.code) {
                fraction = true
            } else if (c == 'e'.code || c == 'E'.code) {
                exponent = true
            }
        }

        if (fraction) {
            c = next() // consume '.'
            while (isdigit(c)) {
                c = next()
            }

            if (c == 'e'.code || c == 'E'.code) {
                exponent = true
            }
        }

        if (exponent) {
            c = next() // consume [eE]
            if (c == '+'.code || c == '-'.code) {
                c = next()
            }
            while (isdigit(c)) {
                c = next()
            }
        }

        // float?
        if (fraction || exponent) {
            setToken(TokenKind.FLOAT, start, pos)
            var value = 0.0
            try {
                value = bufferSlice(start, pos).toDouble()
                if (!Double.isFinite(value)) {
                    error("floating-point literal too large", start)
                }
            } catch (ex: NumberFormatException) {
                error("invalid float literal", start)
            }
            setValue(value)
            return
        }

        // int
        setToken(TokenKind.INT, start, pos)
        val literal = bufferSlice(start, pos)
        var value: Number? = 0
        try {
            value = IntLiteral.Companion.scan(literal)
        } catch (ex: NumberFormatException) {
            error(ex.message, start)
        }
        setValue(value)
    }

    /**
     * Returns a string containing the part of the source buffer beginning at offset `start` and
     * ending immediately before offset `end` (so the length of the resulting string is `end - start`).
     */
    fun bufferSlice(start: Int, end: Int): String {
        return String(this.buffer, start, end - start)
    }

    // TODO(adonovan): don't retain comments unconditionally.
    private fun addComment(start: Int, end: Int): Comment {
        val content = bufferSlice(start, end)
        val comment = Comment(locs, start, content)
        comments.add(comment)
        return comment
    }

    companion object {
        // Characters that can come immediately prior to an '=' character to generate
        // a different token
        private val EQUAL_TOKENS: ImmutableMap<Char?, TokenKind?> = ImmutableMap.builder<Char?, TokenKind?>()
            .put('=', TokenKind.EQUALS_EQUALS)
            .put('!', TokenKind.NOT_EQUALS)
            .put('>', TokenKind.GREATER_EQUALS)
            .put('<', TokenKind.LESS_EQUALS)
            .put('+', TokenKind.PLUS_EQUALS)
            .put('-', TokenKind.MINUS_EQUALS)
            .put('*', TokenKind.STAR_EQUALS)
            .put('/', TokenKind.SLASH_EQUALS)
            .put('%', TokenKind.PERCENT_EQUALS)
            .put('^', TokenKind.CARET_EQUALS)
            .put('&', TokenKind.AMPERSAND_EQUALS)
            .put('|', TokenKind.PIPE_EQUALS)
            .buildOrThrow()

        private val keywordMap: MutableMap<String?, TokenKind?> = HashMap<String?, TokenKind?>()

        /** Additional keywords that are only recognized if --experimental_starlark_type_syntax is set.  */
        private val typeSyntaxExtraKeywordMap: MutableMap<String?, TokenKind?> = HashMap<String?, TokenKind?>()

        init {
            keywordMap.put("and", TokenKind.AND)
            keywordMap.put("as", TokenKind.AS)
            keywordMap.put("assert", TokenKind.ASSERT)
            keywordMap.put("break", TokenKind.BREAK)
            keywordMap.put("class", TokenKind.CLASS)
            keywordMap.put("continue", TokenKind.CONTINUE)
            keywordMap.put("def", TokenKind.DEF)
            keywordMap.put("del", TokenKind.DEL)
            keywordMap.put("elif", TokenKind.ELIF)
            keywordMap.put("else", TokenKind.ELSE)
            keywordMap.put("except", TokenKind.EXCEPT)
            keywordMap.put("finally", TokenKind.FINALLY)
            keywordMap.put("for", TokenKind.FOR)
            keywordMap.put("from", TokenKind.FROM)
            keywordMap.put("global", TokenKind.GLOBAL)
            keywordMap.put("if", TokenKind.IF)
            keywordMap.put("import", TokenKind.IMPORT)
            keywordMap.put("in", TokenKind.IN)
            keywordMap.put("is", TokenKind.IS)
            keywordMap.put("lambda", TokenKind.LAMBDA)
            keywordMap.put("load", TokenKind.LOAD)
            keywordMap.put("nonlocal", TokenKind.NONLOCAL)
            keywordMap.put("not", TokenKind.NOT)
            keywordMap.put("or", TokenKind.OR)
            keywordMap.put("pass", TokenKind.PASS)
            keywordMap.put("raise", TokenKind.RAISE)
            keywordMap.put("return", TokenKind.RETURN)
            keywordMap.put("try", TokenKind.TRY)
            keywordMap.put("while", TokenKind.WHILE)
            keywordMap.put("with", TokenKind.WITH)
            keywordMap.put("yield", TokenKind.YIELD)

            typeSyntaxExtraKeywordMap.put("cast", TokenKind.CAST)
            typeSyntaxExtraKeywordMap.put("isinstance", TokenKind.ISINSTANCE)
        }

        private fun isdigit(c: Int): Boolean {
            return '0'.code <= c && c <= '9'.code
        }

        private fun isxdigit(c: Int): Boolean {
            return isdigit(c) || ('A'.code <= c && c <= 'F'.code) || ('a'.code <= c && c <= 'f'.code)
        }

        private fun isbdigit(c: Int): Boolean {
            return c == '0'.code || c == '1'.code
        }
    }
}
