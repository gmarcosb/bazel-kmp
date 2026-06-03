// Copyright 2006 The Bazel Authors. All Rights Reserved.
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

import com.google.common.truth.Truth
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.add
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.build
import net.starlark.java.syntax.Location.column
import net.starlark.java.syntax.Location.line
import net.starlark.java.syntax.SyntaxError.location
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Tests of tokenization behavior of the [Lexer].
 */
@RunWith(JUnit4::class)
class LexerTest {
    // TODO(adonovan): make these these tests less unnecessarily stateful.
    private val errors: MutableList<net.starlark.java.syntax.SyntaxError> =
        java.util.ArrayList<net.starlark.java.syntax.SyntaxError>()

    // Reassign in test case to inject non-default options to the Lexer.
    // Doesn't leak between test cases since each case is its own instance.
    private var options: net.starlark.java.syntax.FileOptions? = net.starlark.java.syntax.FileOptions.DEFAULT

    /**
     * Create a lexer which takes input from the specified string. Resets the error handler
     * beforehand. Uses the current state of [.options].
     */
    private fun createLexer(input: String?): net.starlark.java.syntax.Lexer {
        val inputSource: net.starlark.java.syntax.ParserInput? =
            net.starlark.java.syntax.ParserInput.fromString(input, "")
        errors.clear()
        return net.starlark.java.syntax.Lexer(inputSource, errors, options)
    }

    private class Token {
        var kind: net.starlark.java.syntax.TokenKind? = null
        var start: Int = 0
        var end: Int = 0
        var value: Any? = null

        override fun toString(): String {
            return if (kind == net.starlark.java.syntax.TokenKind.STRING)
                "\"" + value + "\""
            else
                if (value == null) kind.toString() else value.toString()
        }
    }

    private fun allTokens(lexer: net.starlark.java.syntax.Lexer): java.util.ArrayList<Token> {
        val result: java.util.ArrayList<Token> = java.util.ArrayList<Token>()
        do {
            lexer.nextToken()
            val tok: Token = net.starlark.java.syntax.LexerTest.Token()
            tok.kind = lexer.kind
            tok.start = lexer.start
            tok.end = lexer.end
            tok.value = lexer.value
            result.add(tok)
        } while (lexer.kind != net.starlark.java.syntax.TokenKind.EOF)
        return result
    }

    private fun tokens(input: String?): Array<Token> {
        val result: java.util.ArrayList<Token> = allTokens(createLexer(input))
        return result.toTypedArray<Token?>()
    }

    /**
     * Lexes the specified input string, and returns a string containing just the line numbers of each
     * token.
     */
    private fun linenums(input: String?): String {
        val lexer: net.starlark.java.syntax.Lexer = createLexer(input)
        val buf: java.lang.StringBuilder = java.lang.StringBuilder()
        for (tok in allTokens(lexer)) {
            if (buf.length > 0) {
                buf.append(' ')
            }
            val line: Int = lexer.locs.getLocation(tok.start).line()
            buf.append(line)
        }
        return buf.toString()
    }

    // Scans src, and asserts that the tokens match wantTokens
    // and that there are no errors.
    private fun check(src: String?, wantTokens: String?) {
        Truth.assertThat(net.starlark.java.syntax.LexerTest.Companion.values(tokens(src))).isEqualTo(wantTokens)
        Truth.assertThat(errors).isEmpty()
    }

    // Scans src, and asserts that the tokens match wantTokens
    // and the errors match wantErrors.
    // Errors are formatted with a caret ^ under the errant column.
    private fun checkErrors(src: String?, wantTokens: String?, vararg wantErrors: String?) {
        Truth.assertThat(net.starlark.java.syntax.LexerTest.Companion.values(tokens(src))).isEqualTo(wantTokens)

        val gotErrors: MutableList<String> = java.util.ArrayList<String>()
        for (err in errors) {
            var msg: String? =
                net.starlark.java.syntax.LexerTest.Companion.spaces(err.location().column() - 1) + "^ " + err.message()
            if (err.location().line() != 1) {
                msg = String.format("%s (line %d)", msg, err.location().line())
            }
            gotErrors.add(msg!!)
        }
        Truth.assertThat(gotErrors).isEqualTo(java.util.Arrays.asList<String?>(*wantErrors))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBasics1() {
        checkErrors(
            "wiz) ",  //
            "IDENTIFIER(wiz) RPAREN NEWLINE EOF",
            "   ^ indentation error"
        )
        checkErrors(
            "wiz )",  //
            "IDENTIFIER(wiz) RPAREN NEWLINE EOF",
            "    ^ indentation error"
        )
        checkErrors(
            " wiz)",  //
            "INDENT IDENTIFIER(wiz) RPAREN NEWLINE OUTDENT NEWLINE EOF",
            "    ^ indentation error"
        )
        checkErrors(
            " wiz ) ",  //
            "INDENT IDENTIFIER(wiz) RPAREN NEWLINE OUTDENT NEWLINE EOF",
            "     ^ indentation error"
        )
        checkErrors(
            "wiz\t)",  //
            "IDENTIFIER(wiz) RPAREN NEWLINE EOF",
            "    ^ indentation error"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBasics2() {
        checkErrors(
            ")",  //
            "RPAREN NEWLINE EOF",
            "^ indentation error"
        )
        checkErrors(
            " )",  //
            "INDENT RPAREN NEWLINE OUTDENT NEWLINE EOF",
            " ^ indentation error"
        )
        checkErrors(
            " ) ",  //
            "INDENT RPAREN NEWLINE OUTDENT NEWLINE EOF",
            " ^ indentation error"
        )
        checkErrors(
            ") ",  //
            "RPAREN NEWLINE EOF",
            "^ indentation error"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBasics3() {
        check("123#456\n789", "INT(123) NEWLINE INT(789) NEWLINE EOF")
        check("123 #456\n789", "INT(123) NEWLINE INT(789) NEWLINE EOF")
        check("123#456 \n789", "INT(123) NEWLINE INT(789) NEWLINE EOF")
        check("123#456\n 789", "INT(123) NEWLINE INDENT INT(789) NEWLINE OUTDENT NEWLINE EOF")
        check("123#456\n789 ", "INT(123) NEWLINE INT(789) NEWLINE EOF")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBasics4() {
        check("", "NEWLINE EOF")
        check("# foo", "NEWLINE EOF")
        check("1 2 3 4", "INT(1) INT(2) INT(3) INT(4) NEWLINE EOF")
        check("1.234", "FLOAT(1.234) NEWLINE EOF")
        check(
            "foo(bar, wiz)",
            "IDENTIFIER(foo) LPAREN IDENTIFIER(bar) COMMA IDENTIFIER(wiz) RPAREN NEWLINE EOF"
        )
        check(
            "1.0e308 1" + net.starlark.java.syntax.LexerTest.Companion.zeroes(308) + ".0",
            "FLOAT(1.0E308) FLOAT(1.0E308) NEWLINE EOF"
        )
        checkErrors(
            "1.0e309 1" + net.starlark.java.syntax.LexerTest.Companion.zeroes(309) + ".0",
            "FLOAT(Infinity) FLOAT(Infinity) NEWLINE EOF",
            "^ floating-point literal too large",
            "        ^ floating-point literal too large"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoWhiteSpaceBetweenTokens() {
        check("6or()", "INT(6) OR LPAREN RPAREN NEWLINE EOF")
        check("0in(''and[])", "INT(0) IN LPAREN STRING() AND LBRACKET RBRACKET RPAREN NEWLINE EOF")

        checkErrors(
            "0or()",
            "INT(0) IDENTIFIER(r) LPAREN RPAREN NEWLINE EOF",
            "^ invalid base-8 integer literal: 0o"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonAsciiIdentifiers() {
        checkErrors(
            "ümlaut",  //
            "IDENTIFIER(mlaut) NEWLINE EOF",
            "^ invalid character: 'ü'"
        )
        checkErrors(
            "umläut",  //
            "IDENTIFIER(uml) IDENTIFIER(ut) NEWLINE EOF",
            "   ^ invalid character: 'ä'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCrLf() {
        check("\r\n\r\n", "NEWLINE EOF")
        check("\r\n\r1\r\r\n", "INT(1) NEWLINE EOF")
        check("# foo\r\n# bar\r\n", "NEWLINE EOF")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIntegers() {
        // Detection of MINUS immediately following integer constant proves we
        // don't consume too many chars.

        // decimal

        check("12345-", "INT(12345) MINUS NEWLINE EOF")

        // TODO(adonovan): add tests for 0b binary literals

        // octal
        check("0o12345-", "INT(5349) MINUS NEWLINE EOF")
        check("0O77", "INT(63) NEWLINE EOF")
        check("0o1o2349-", "INT(1) IDENTIFIER(o2349) MINUS NEWLINE EOF")
        checkErrors(
            "0o12349-",  //
            "INT(0) MINUS NEWLINE EOF",
            "^ invalid base-8 integer literal: 0o12349"
        )
        checkErrors(
            "0o",  //
            "INT(0) NEWLINE EOF",
            "^ invalid base-8 integer literal: 0o"
        )
        checkErrors(
            "012345",  //
            "INT(0) NEWLINE EOF",
            "^ invalid octal literal: 012345 (use '0o12345')"
        )

        // hexadecimal (uppercase)
        check("0X12345F-", "INT(1193055) MINUS NEWLINE EOF")

        // hexadecimal (lowercase)
        check("0x12345f-", "INT(1193055) MINUS NEWLINE EOF")

        // hexadecimal (lowercase) [note: "g" cause termination of token]
        check("0x12345g-", "INT(74565) IDENTIFIER(g) MINUS NEWLINE EOF")

        // long
        check("1234567890 0x123456789ABCDEF", "INT(1234567890) INT(81985529216486895) NEWLINE EOF")
        // big
        check(
            "123456789123456789123456789 0xABCDEFABCDEFABCDEFABCDEFABCDEF",
            "INT(123456789123456789123456789) INT(892059645479943313385225296292859375) NEWLINE EOF"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNumbersAndDot() {
        check("0", "INT(0) NEWLINE EOF")
        check("0.", "FLOAT(0.0) NEWLINE EOF")
        check(".0", "FLOAT(0.0) NEWLINE EOF")
        checkErrors(
            "1e",  //
            "FLOAT(0.0) NEWLINE EOF",
            "^ invalid float literal"
        )
        checkErrors(
            "1e+x",  //
            "FLOAT(0.0) IDENTIFIER(x) NEWLINE EOF",
            "^ invalid float literal"
        )
        check("1e1", "FLOAT(10.0) NEWLINE EOF")
        check(".e1", "DOT IDENTIFIER(e1) NEWLINE EOF")
        check("1.e1", "FLOAT(10.0) NEWLINE EOF")
        check("1.e+1", "FLOAT(10.0) NEWLINE EOF")
        check("1.e-1", "FLOAT(0.1) NEWLINE EOF")

        check("1.2345", "FLOAT(1.2345) NEWLINE EOF")
        check("1.2.345", "FLOAT(1.2) FLOAT(0.345) NEWLINE EOF")

        check("1.0E10", "FLOAT(1.0E10) NEWLINE EOF")
        check("1.03E-10", "FLOAT(1.03E-10) NEWLINE EOF")

        check(". 123", "DOT INT(123) NEWLINE EOF")
        check(".123", "FLOAT(0.123) NEWLINE EOF")
        check(".abc", "DOT IDENTIFIER(abc) NEWLINE EOF")

        check("foo.123", "IDENTIFIER(foo) FLOAT(0.123) NEWLINE EOF")
        check("foo.bcd", "IDENTIFIER(foo) DOT IDENTIFIER(bcd) NEWLINE EOF") // 'b' are hex chars
        check("foo.xyz", "IDENTIFIER(foo) DOT IDENTIFIER(xyz) NEWLINE EOF")

        check("..", "DOT DOT NEWLINE EOF")
        check("...", "ELLIPSIS NEWLINE EOF")
        check("....", "ELLIPSIS DOT NEWLINE EOF") // ellipsis is consumed greedily before dot
        check(".......", "ELLIPSIS ELLIPSIS DOT NEWLINE EOF")
        check(". . . ", "DOT DOT DOT NEWLINE EOF")

        check("1...", "FLOAT(1.0) DOT DOT NEWLINE EOF")
        check("1...1", "FLOAT(1.0) DOT FLOAT(0.1) NEWLINE EOF")
        check("1....1", "FLOAT(1.0) ELLIPSIS INT(1) NEWLINE EOF")
        check("foo...bcd", "IDENTIFIER(foo) ELLIPSIS IDENTIFIER(bcd) NEWLINE EOF")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringDelimiters() {
        check("\"foo\"", "STRING(foo) NEWLINE EOF")
        check("'foo'", "STRING(foo) NEWLINE EOF")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testQuotesInStrings() {
        check("'foo\\'bar'", "STRING(foo'bar) NEWLINE EOF")
        check("\"foo'bar\"", "STRING(foo'bar) NEWLINE EOF")
        check("'foo\"bar'", "STRING(foo\"bar) NEWLINE EOF")
        check("\"foo\\\"bar\"", "STRING(foo\"bar) NEWLINE EOF")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringEscapes() {
        check(
            "'a\\tb\\nc\\rd\\fe\\vf\\ag\\bh'",
            "STRING(a\tb\nc\rd\u000ce\u000bf\u0007g\bh) NEWLINE EOF"
        ) // \t \r \n \f \v \a \b
        checkErrors(
            "'x\\hx'",  //
            "STRING(x\\hx) NEWLINE EOF",
            "   ^ invalid escape sequence: \\h. Use '\\\\' to insert '\\'."
        )
        checkErrors(
            "'\\$$'",  //
            "STRING(\\$$) NEWLINE EOF",
            "  ^ invalid escape sequence: \\$. Use '\\\\' to insert '\\'."
        )
        check("'a\\\nb'", "STRING(ab) NEWLINE EOF") // escape end of line
        checkErrors(
            "\"ab\\ucd\"",  //
            "STRING(ab\\ucd) NEWLINE EOF",
            "    ^ invalid escape sequence: \\u. Use '\\\\' to insert '\\'."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEscapedCrlfInString() {
        check("'a\\\r\nb'", "STRING(ab) NEWLINE EOF")
        check("\"a\\\r\nb\"", "STRING(ab) NEWLINE EOF")
        check("\"\"\"a\\\r\nb\"\"\"", "STRING(ab) NEWLINE EOF")
        check("'''a\\\r\nb'''", "STRING(ab) NEWLINE EOF")
        check("r'a\\\r\nb'", "STRING(a\\\nb) NEWLINE EOF")
        check("r\"a\\\r\nb\"", "STRING(a\\\nb) NEWLINE EOF")
        check("r\"a\\\r\n\\\nb\"", "STRING(a\\\n\\\nb) NEWLINE EOF")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRawString() {
        check("r'abcd'", "STRING(abcd) NEWLINE EOF")
        check("r\"abcd\"", "STRING(abcd) NEWLINE EOF")
        check("r'a\\tb\\nc\\rd'", "STRING(a\\tb\\nc\\rd) NEWLINE EOF") // r'a\tb\nc\rd'
        check("r\"a\\\"\"", "STRING(a\\\") NEWLINE EOF") // r"a\""
        check("r'a\\\\b'", "STRING(a\\\\b) NEWLINE EOF") // r'a\\b'
        check("r'ab'r", "STRING(ab) IDENTIFIER(r) NEWLINE EOF")

        // Unclosed raw string
        checkErrors(
            "+ r'\\'",  // r'\'
            "PLUS STRING(\\') NEWLINE EOF",
            "  ^ unclosed string literal"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTripleRawString() {
        // r'''a\ncd'''
        check("r'''ab\\ncd'''", "STRING(ab\\ncd) NEWLINE EOF")
        // r"""ab
        // cd"""
        check("\"\"\"ab\ncd\"\"\"", "STRING(ab\ncd) NEWLINE EOF")

        // Unclosed raw string
        checkErrors(
            "r'''\\'''",  // r'''\'''
            "STRING(\\''') NEWLINE EOF",
            "^ unclosed string literal"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOctalEscapes() {
        // Regression test for a bug.
        check(
            "'\\0 \\1 \\11 \\77 \\111 \\1111 \\377'",
            "STRING(\u0000 \u0001 \t \u003f I I1 \u00ff) NEWLINE EOF"
        )
        // Test boundaries (non-octal char, EOF).
        check("'\\1b \\1'", "STRING(\u0001b \u0001) NEWLINE EOF")
        // Test first digit out-of-range.
        checkErrors(
            "'\\800'",
            "STRING(\\800) NEWLINE EOF",
            "  ^ invalid escape sequence: \\8. Use '\\\\' to insert '\\'."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOctalEscapeOutOfRange() {
        // Capped at U+FF.
        checkErrors(
            "'\\777'",
            "STRING(\u00ff) NEWLINE EOF",
            "    ^ octal escape sequence out of range (maximum is \\377)"
        )
        // Emitted value is masked by (not capped to) 0xFF.
        checkErrors(
            "'\\401'",
            "STRING(\u0001) NEWLINE EOF",
            "    ^ octal escape sequence out of range (maximum is \\377)"
        )
        // Multiple errors.
        checkErrors(
            "'\\401\\402'",
            "STRING(\u0001\u0002) NEWLINE EOF",
            "    ^ octal escape sequence out of range (maximum is \\377)",
            "        ^ octal escape sequence out of range (maximum is \\377)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTripleQuotedStrings() {
        check("\"\"\"a\"b'c \n d\"\"e\"\"\"", "STRING(a\"b'c \n d\"\"e) NEWLINE EOF")
        check("'''a\"b'c \n d\"\"e'''", "STRING(a\"b'c \n d\"\"e) NEWLINE EOF")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringContainingNonAsciiRawCharacter() {
        // Lexer is fine with U+80 to U+FF by default.
        check("'\u0080\u00ff'", "STRING(\u0080\u00ff) NEWLINE EOF")
        // If the ParserInput provides content greater than 8 bits wide, the Lexer tolerates it.
        check("'\u0100\uffff'", "STRING(\u0100\uffff) NEWLINE EOF")

        options = net.starlark.java.syntax.FileOptions.builder().stringLiteralsAreAsciiOnly(true).build()
        // Ok, U+7F is ASCII.
        check("'\u007f'", "STRING(\u007f) NEWLINE EOF")
        // With U+80 and higher, we error but still emit the token with the original value (no masking
        // down to ASCII).
        checkErrors(
            "'abc\u0080xyz'",
            "STRING(abc\u0080xyz) NEWLINE EOF",
            "    ^ string literal contains non-ASCII character"
        )
        checkErrors(
            "'abc\u0100xyz'",
            "STRING(abc\u0100xyz) NEWLINE EOF",
            "    ^ string literal contains non-ASCII character"
        )
        // Test a case with an escape sequence to trigger the longer code path.
        checkErrors(
            "'abc\u0080xyz\\n'",
            "STRING(abc\u0080xyz\n) NEWLINE EOF",
            "    ^ string literal contains non-ASCII character"
        )
        // Multiple errors.
        checkErrors(
            "'\u0080\u0081'",
            "STRING(\u0080\u0081) NEWLINE EOF",
            " ^ string literal contains non-ASCII character",
            "  ^ string literal contains non-ASCII character"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringContainingNonAsciiOctalEscapes() {
        // Lexer is fine with U+80 to U+FF by default.
        check("'\\200\\377'", "STRING(\u0080\u00ff) NEWLINE EOF")

        options = net.starlark.java.syntax.FileOptions.builder().stringLiteralsAreAsciiOnly(true).build()
        // Ok, U+7F is ASCII.
        check("'\\177'", "STRING(\u007f) NEWLINE EOF")
        // With U+80 to U+FF, we error but still emit the token with the original value (no masking
        // down to ASCII).
        checkErrors(
            "'\\200'",
            "STRING(\u0080) NEWLINE EOF",
            "    ^ octal escape sequence denotes non-ASCII character"
        )
        // Out-of-range error takes priority over non-ASCII error. As in the case without the ASCII-only
        // option, the value is masked down to U+FF.
        checkErrors(
            "'\\400'",
            "STRING(\u0000) NEWLINE EOF",
            "    ^ octal escape sequence out of range (maximum is \\377)"
        )
        // Multiple errors.
        checkErrors(
            "'\\200\\201'",
            "STRING(\u0080\u0081) NEWLINE EOF",
            "    ^ octal escape sequence denotes non-ASCII character",
            "        ^ octal escape sequence denotes non-ASCII character"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBadChar() {
        checkErrors(
            "a\$b",  //
            "IDENTIFIER(a) IDENTIFIER(b) NEWLINE EOF",
            " ^ invalid character: '$'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIndentation() {
        check("1\n2\n3", "INT(1) NEWLINE INT(2) NEWLINE INT(3) NEWLINE EOF")
        check(
            "1\n  2\n  3\n4 ",
            "INT(1) NEWLINE INDENT INT(2) NEWLINE INT(3) NEWLINE OUTDENT " + "INT(4) NEWLINE EOF"
        )
        check(
            "1\n  2\n  3",
            "INT(1) NEWLINE INDENT INT(2) NEWLINE INT(3) NEWLINE OUTDENT " + "NEWLINE EOF"
        )
        check(
            "1\n  2\n    3",
            "INT(1) NEWLINE INDENT INT(2) NEWLINE INDENT INT(3) NEWLINE "
                    + "OUTDENT OUTDENT NEWLINE EOF"
        )
        check(
            "1\n  2\n    3\n  4\n5",
            "INT(1) NEWLINE INDENT INT(2) NEWLINE INDENT INT(3) NEWLINE "
                    + "OUTDENT INT(4) NEWLINE OUTDENT INT(5) NEWLINE EOF"
        )

        checkErrors(
            "1\n  2\n    3\n   4\n5",
            "INT(1) NEWLINE INDENT INT(2) NEWLINE INDENT INT(3) NEWLINE "
                    + "OUTDENT INT(4) NEWLINE OUTDENT INT(5) NEWLINE EOF",
            "  ^ indentation error (line 4)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIndentationWithTab() {
        checkErrors(
            "def x():\n" + "\tpass",  //
            "DEF IDENTIFIER(x) LPAREN RPAREN COLON NEWLINE "
                    + "INDENT PASS NEWLINE OUTDENT NEWLINE EOF",
            " ^ Tab characters are not allowed for indentation. Use spaces instead. (line 2)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIndentationWithCrLf() {
        check("1\r\n  2\r\n", "INT(1) NEWLINE INDENT INT(2) NEWLINE OUTDENT NEWLINE EOF")
        check("1\r\n  2\r\n\r\n", "INT(1) NEWLINE INDENT INT(2) NEWLINE OUTDENT NEWLINE EOF")
        check(
            "1\r\n  2\r\n    3\r\n  4\r\n5",
            "INT(1) NEWLINE INDENT INT(2) NEWLINE INDENT INT(3) NEWLINE OUTDENT INT(4) "
                    + "NEWLINE OUTDENT INT(5) NEWLINE EOF"
        )
        check(
            "1\r\n  2\r\n\r\n  3\r\n4",
            "INT(1) NEWLINE INDENT INT(2) NEWLINE INT(3) NEWLINE OUTDENT INT(4) NEWLINE EOF"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIndentationInsideParens() {
        // Indentation is ignored inside parens:
        check("1 (\n  2\n    3\n  4\n5", "INT(1) LPAREN INT(2) INT(3) INT(4) INT(5) NEWLINE EOF")
        check("1 {\n  2\n    3\n  4\n5", "INT(1) LBRACE INT(2) INT(3) INT(4) INT(5) NEWLINE EOF")
        check("1 [\n  2\n    3\n  4\n5", "INT(1) LBRACKET INT(2) INT(3) INT(4) INT(5) NEWLINE EOF")
        check(
            "1 [\n  2]\n    3\n    4\n5",
            "INT(1) LBRACKET INT(2) RBRACKET NEWLINE INDENT INT(3) "
                    + "NEWLINE INT(4) NEWLINE OUTDENT INT(5) NEWLINE EOF"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIndentationAtEOF() {
        // Matching OUTDENTS are created at EOF:
        check("\n  1", "INDENT INT(1) NEWLINE OUTDENT NEWLINE EOF")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIndentationOnFirstLine() {
        check("    1", "INDENT INT(1) NEWLINE OUTDENT NEWLINE EOF")
        check("\n\n    1", "INDENT INT(1) NEWLINE OUTDENT NEWLINE EOF")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBlankLineIndentation() {
        // Blank lines and comment lines should not generate any newlines indents
        // (but note that every input ends with NEWLINE EOF).
        check("\n      #\n", "NEWLINE EOF")
        check("      #", "NEWLINE EOF")
        check("      #\n", "NEWLINE EOF")
        check("      #comment\n", "NEWLINE EOF")
        check(
            ("def f(x):\n"
                    +  //
                    "  # comment\n"
                    +  //
                    "\n"
                    +  //
                    "  \n"
                    +  //
                    "  return x\n"),
            ("DEF IDENTIFIER(f) LPAREN IDENTIFIER(x) RPAREN COLON NEWLINE "
                    + "INDENT RETURN IDENTIFIER(x) NEWLINE "
                    + "OUTDENT NEWLINE EOF")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBackslash() {
        check("a\\\nb", "IDENTIFIER(a) IDENTIFIER(b) NEWLINE EOF")
        check("a\\\r\nb", "IDENTIFIER(a) IDENTIFIER(b) NEWLINE EOF")
        check("a\\ b", "IDENTIFIER(a) ILLEGAL(\\) IDENTIFIER(b) NEWLINE EOF")
        check("a(\\\n2)", "IDENTIFIER(a) LPAREN INT(2) RPAREN NEWLINE EOF")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTokenPositions() {
        Truth.assertThat(net.starlark.java.syntax.LexerTest.Companion.positions(tokens("foo(bar, {1: 'quux'}, \"\"\"b\"\"\", r\"\")")))
            .isEqualTo( // foo (     bar   ,     {      1       :
                "[0,3) [3,4) [4,7) [7,8) [9,10) [10,11) [11,12)" //  'quux'  }       ,       """b""" ,       r""     )       NEWLINE EOF
                        + " [13,19) [19,20) [20,21) [22,29) [29,30) [31,34) [34,35) [35,35) [35,35)"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLineNumbers() {
        Truth.assertThat(linenums("foo = 1\nbar = 2\n\nwiz = 3")).isEqualTo("1 1 1 1 2 2 2 2 4 4 4 4 4")

        checkErrors(
            "foo = 1\n" + "bar = 2\n" + "\n" + "wiz = $\n" + "bar = 2",
            ("IDENTIFIER(foo) EQUALS INT(1) NEWLINE "
                    + "IDENTIFIER(bar) EQUALS INT(2) NEWLINE "
                    + "IDENTIFIER(wiz) EQUALS NEWLINE "
                    + "IDENTIFIER(bar) EQUALS INT(2) NEWLINE EOF"),
            "      ^ invalid character: '$' (line 4)"
        )

        // '\\n' in string should not increment linenum:
        val s =  //
            "1\n'foo\\nbar'\u0003"
        checkErrors(
            s,  //
            "INT(1) NEWLINE STRING(foo\nbar) NEWLINE EOF",
            "          ^ invalid character: '\u0003' (line 2)"
        )
        Truth.assertThat(linenums(s)).isEqualTo("1 1 2 2 2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testContainsErrors() {
        check("foo", "IDENTIFIER(foo) NEWLINE EOF")
        checkErrors(
            "f\$o",  //
            "IDENTIFIER(f) IDENTIFIER(o) NEWLINE EOF",
            " ^ invalid character: '$'"
        )
        checkErrors(
            "+ 'unterminated", "PLUS STRING(unterminated) NEWLINE EOF", "  ^ unclosed string literal"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUnclosedRawStringWithEscapingError() {
        checkErrors(
            "r'\\",
            "STRING(\\) NEWLINE EOF",  //
            "^ unclosed string literal"
        )
    }

    @org.junit.Test
    fun testFirstCharIsTab() {
        checkErrors(
            "\t",  //
            "NEWLINE EOF",
            " ^ Tab characters are not allowed for indentation. Use spaces instead."
        )
    }

    @org.junit.Test
    fun testStringLiteralUnquote() {
        // Coverage here needn't be exhaustive,
        // as the underlying logic is that of the Lexer.
        net.starlark.java.syntax.LexerTest.Companion.assertUnquoteEquals("'hello'", "hello")
        net.starlark.java.syntax.LexerTest.Companion.assertUnquoteEquals("\"hello\"", "hello")
        net.starlark.java.syntax.LexerTest.Companion.assertUnquoteEquals("r'a\\b\"c'", "a\\b\"c")

        net.starlark.java.syntax.LexerTest.Companion.assertUnquoteError("", "invalid syntax") // empty
        net.starlark.java.syntax.LexerTest.Companion.assertUnquoteError(" 'hello'", "invalid syntax") // leading space
        net.starlark.java.syntax.LexerTest.Companion.assertUnquoteError("'hello' ", "invalid syntax") // trailing space
        net.starlark.java.syntax.LexerTest.Companion.assertUnquoteError("x", "invalid syntax") // identifier
        net.starlark.java.syntax.LexerTest.Companion.assertUnquoteError(
            "r",
            "invalid syntax"
        ) // identifier (same prefix as r'...')
        net.starlark.java.syntax.LexerTest.Companion.assertUnquoteError("r2", "invalid syntax") // identifier
        net.starlark.java.syntax.LexerTest.Companion.assertUnquoteError("1", "invalid syntax") // number
        net.starlark.java.syntax.LexerTest.Companion.assertUnquoteError("'", "unclosed string literal")
        net.starlark.java.syntax.LexerTest.Companion.assertUnquoteError("\"", "unclosed string literal")
        net.starlark.java.syntax.LexerTest.Companion.assertUnquoteError("'abc", "unclosed string literal")
        net.starlark.java.syntax.LexerTest.Companion.assertUnquoteError(
            "'\\g'",
            "invalid escape sequence: \\g. Use '\\\\' to insert '\\'."
        )
    }

    companion object {
        /**
         * Returns a string containing the names of the tokens and their associated
         * values. (String-literals are printed without escaping.)
         */
        private fun values(tokens: Array<Token>): String {
            val buffer: java.lang.StringBuilder = java.lang.StringBuilder()
            for (token in tokens) {
                if (buffer.length > 0) {
                    buffer.append(' ')
                }
                buffer.append(token.kind.name)
                if (token.value != null) {
                    buffer.append('(').append(token.value).append(')')
                }
            }
            return buffer.toString()
        }

        private fun spaces(n: Int): String? {
            return String(CharArray(n)).replace('\u0000', ' ')
        }

        /**
         * Returns a string containing just the half-open position intervals of each
         * token. e.g. "[3,4) [4,9)".
         */
        private fun positions(tokens: Array<Token>): String {
            val buf: java.lang.StringBuilder = java.lang.StringBuilder()
            for (tok in tokens) {
                if (buf.length > 0) {
                    buf.append(' ')
                }
                buf.append('[').append(tok.start).append(',').append(tok.end).append(')')
            }
            return buf.toString()
        }

        private fun zeroes(n: Int): String? {
            return String(CharArray(n)).replace('\u0000', '0')
        }

        private fun assertUnquoteEquals(literal: String?, value: String?) {
            Truth.assertThat(net.starlark.java.syntax.StringLiteral.unquote(literal)).isEqualTo(value)
        }

        private fun assertUnquoteError(badLiteral: String?, errorSubstring: String?) {
            val ex: java.lang.IllegalArgumentException? =
                org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                    java.lang.IllegalArgumentException::class.java,
                    org.junit.function.ThrowingRunnable { net.starlark.java.syntax.StringLiteral.unquote(badLiteral) })
            Truth.assertThat(ex).hasMessageThat().contains(errorSubstring)
        }
    }
}
