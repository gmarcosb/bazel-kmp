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

import java.util.*

/**
 * A tokenizer for the Blaze query language, revision 2.
 * 
 * No string escapes are allowed ("\").  Given the domain, that's not currently
 * a problem.
 */
class Lexer private constructor(// Input buffer and position
    private var input: String?
) {
    /**
     * Discriminator for different kinds of tokens.
     */
    enum class TokenKind(prettyName: String) {
        WORD("word"),
        EOF("EOF"),

        COMMA(","),
        EQUALS("="),
        LPAREN("("),
        MINUS("-"),
        PLUS("+"),
        RPAREN(")"),
        CARET("^"),

        __ALL_IDENTIFIERS_FOLLOW(""),  // See below

        IN("in"),
        LET("let"),
        SET("set"),

        INTERSECT("intersect"),
        EXCEPT("except"),
        UNION("union");

        val prettyName: String?

        init {
            this.prettyName = prettyName
        }
    }

    /**
     * Tokens returned by the Lexer.
     */
    internal class Token {
        @kotlin.jvm.JvmField
        val kind: TokenKind
        @kotlin.jvm.JvmField
        val word: String?

        constructor(kind: TokenKind) {
            this.kind = kind
            this.word = null
        }

        constructor(word: String?) {
            this.kind = TokenKind.WORD
            this.word = word
        }

        override fun toString(): String {
            return (if (kind == com.google.devtools.build.lib.query2.engine.Lexer.TokenKind.WORD) word else kind.prettyName)!!
        }
    }

    private var pos = 0

    private val tokens: MutableList<Token?> = ArrayList<Token?>()

    private fun addToken(s: Token?) {
        tokens.add(s)
    }

    /**
     * Scans a quoted word delimited by 'quot'.
     * 
     * 
     * ON ENTRY: 'pos' is 1 + the index of the first delimiter ON EXIT: 'pos' is 1 + the index of
     * the last delimiter.
     * 
     * @return the word token.
     */
    @Throws(QuerySyntaxException::class)
    private fun quotedWord(quot: Char): Token {
        val oldPos = pos - 1
        while (pos < input!!.length) {
            val c = input!!.get(pos++)
            when (c) {
                '\'', '"' -> {
                    if (c == quot) {
                        // close-quote, all done.
                        return Token(bufferSlice(oldPos + 1, pos - 1))
                    }
                }

                else -> {}
            }
        }
        throw QuerySyntaxException("unclosed quotation")
    }

    private fun getTokenKindForWord(word: String?): TokenKind {
        val kind: TokenKind? = keywordMap.get(word)
        return if (kind == null) TokenKind.WORD else kind
    }

    private fun scanWord(firstChar: Char): String {
        val oldPos = pos - 1
        val startsWithDoubleAt =
            firstChar == '@' && pos < input!!.length && input!!.get(pos) == '@'
        while (pos < input!!.length) {
            val c = input!!.get(pos)
            when (c) {
                'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '*', '/', '@', '.', '-', '_', ':', '$', '~', '[', ']' -> pos++

                '+' -> {
                    if (startsWithDoubleAt) {
                        // Allow unquoted canonical labels such as
                        // @@rules_jvm_external++maven+maven//:bar, but still parse @foo+@bar as two separate
                        // labels (here @foo refers to the @foo//:foo target).
                        // If @@foo+bar is intended to mean @@foo + bar, it can be written as such with spaces.
                        pos++
                    } else {
                        return bufferSlice(oldPos, pos)
                    }
                }

                else -> {
                    // All remaining ASCII characters (and only ASCII characters) are query operators.
                    if (c.code <= 0x7F) {
                        return bufferSlice(oldPos, pos)
                    }
                    // Unicode characters are allowed in words, so we continue scanning.
                    pos++
                }
            }
        }
        return bufferSlice(oldPos, pos)
    }

    /**
     * Scans a word or keyword.
     * 
     * 
     * ON ENTRY: 'pos' is 1 + the index of the first char in the word. ON EXIT: 'pos' is 1 + the
     * index of the last char in the word.
     * 
     * @return the word or keyword token.
     */
    private fun wordOrKeyword(firstChar: Char): Token {
        val word = scanWord(firstChar)
        val kind = getTokenKindForWord(word)
        return if (kind == TokenKind.WORD) Token(word) else Token(kind)
    }

    /** Performs tokenization of the character buffer of file contents provided to the constructor.  */
    @Throws(QuerySyntaxException::class)
    private fun tokenize() {
        while (pos < input!!.length) {
            val c = input!!.get(pos)
            pos++
            when (c) {
                '(' -> addToken(Token(TokenKind.LPAREN))
                ')' -> addToken(Token(TokenKind.RPAREN))
                ',' -> addToken(Token(TokenKind.COMMA))
                '+' -> addToken(Token(TokenKind.PLUS))
                '-' -> addToken(Token(TokenKind.MINUS))
                '=' -> addToken(Token(TokenKind.EQUALS))
                '^' -> addToken(Token(TokenKind.CARET))
                '\n', ' ', '\t', '\r' -> {
                    /* ignore */
                }

                '\'', '\"' -> addToken(quotedWord(c))
                else -> addToken(wordOrKeyword(c))
            }
        }

        addToken(Token(TokenKind.EOF))

        this.input = null // release buffer now that we have our tokens
    }

    private fun bufferSlice(start: Int, end: Int): String {
        return this.input.substring(start, end)
    }

    companion object {
        val BINARY_OPERATORS: MutableSet<TokenKind?> = EnumSet.of<TokenKind?>(
            TokenKind.INTERSECT,
            TokenKind.CARET,
            TokenKind.UNION,
            TokenKind.PLUS,
            TokenKind.EXCEPT,
            TokenKind.MINUS
        )

        private val keywordMap: MutableMap<String?, TokenKind?> = HashMap<String?, TokenKind?>()

        init {
            for (kind in EnumSet.allOf<TokenKind>(TokenKind::class.java)) {
                if (kind.ordinal > TokenKind.__ALL_IDENTIFIERS_FOLLOW.ordinal) {
                    keywordMap.put(kind.prettyName, kind)
                }
            }
        }

        /**
         * Returns true iff 'word' is a reserved word of the language.
         */
        fun isReservedWord(word: String?): Boolean {
            return keywordMap.containsKey(word)
        }

        /**
         * Entry point to the lexer. Returns the list of tokens for the specified input, or throws
         * QueryException.
         */
        @kotlin.jvm.JvmStatic
        @Throws(QuerySyntaxException::class)
        fun scan(input: String?): MutableList<Token?> {
            val lexer = Lexer(input)
            lexer.tokenize()
            return lexer.tokens
        }
    }
}
