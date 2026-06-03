// Copyright 2020 The Bazel Authors. All rights reserved.
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

import com.google.common.truth.Truth
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for the query expression lexer.  */
@RunWith(JUnit4::class)
class LexerTest {
    private fun asString(tokens: Array<Lexer.Token?>): String {
        val buffer = StringBuilder()
        for (token in tokens) {
            if (buffer.length > 0) {
                buffer.append(' ')
            }
            buffer.append(token)
        }
        return buffer.toString()
    }

    @Throws(QuerySyntaxException::class)
    private fun scan(input: String?): Array<Lexer.Token?> {
        return Lexer.scan(input).toArray(arrayOfNulls<Lexer.Token>(0))
    }

    @Test
    @Throws(QuerySyntaxException::class)
    fun testBasics() {
        Truth.assertThat(asString(scan(""))).isEqualTo("EOF")
    }

    @Test
    @Throws(QuerySyntaxException::class)
    fun testWordsAndKeywords() {
        val tokens = scan("foo bar wiz intersect")
        Truth.assertThat(asString(tokens)).isEqualTo("foo bar wiz intersect EOF")
        Truth.assertThat<Lexer.TokenKind>(tokens[0].kind).isEqualTo(Lexer.TokenKind.WORD)
        Truth.assertThat<Lexer.TokenKind>(tokens[1].kind).isEqualTo(Lexer.TokenKind.WORD)
        Truth.assertThat<Lexer.TokenKind>(tokens[2].kind).isEqualTo(Lexer.TokenKind.WORD)
        Truth.assertThat<Lexer.TokenKind>(tokens[3].kind).isEqualTo(Lexer.TokenKind.INTERSECT)
        Truth.assertThat<Lexer.TokenKind>(tokens[4].kind).isEqualTo(Lexer.TokenKind.EOF)
    }

    @Test
    @Throws(QuerySyntaxException::class)
    fun testPunctuationAndWordBoundaries() {
        Truth.assertThat(asString(scan("foo(bar,wiz)deps=intersect")))
            .isEqualTo("foo ( bar , wiz ) deps = intersect EOF")
        Truth.assertThat(asString(scan("deps(//pkg:target)"))).isEqualTo("deps ( //pkg:target ) EOF")
    }

    @Test
    @Throws(QuerySyntaxException::class)
    fun testWordsMayContainDashOrStarButNotStartWithThem() {
        Truth.assertThat(asString(scan("* foo*"))).isEqualTo("* foo* EOF")
        Truth.assertThat(asString(scan("-foo foo-bar"))).isEqualTo("- foo foo-bar EOF")
    }

    @Test
    @Throws(QuerySyntaxException::class)
    fun testDotDotDot() {
        Truth.assertThat(asString(scan("..."))).isEqualTo("... EOF")
    }

    @Test
    @Throws(QuerySyntaxException::class)
    fun testQuotation() {
        val tokens = scan("foo bar 'foo bar'")
        Truth.assertThat(asString(tokens)).isEqualTo("foo bar foo bar EOF")
        Truth.assertThat<Lexer.TokenKind>(tokens[0].kind).isEqualTo(Lexer.TokenKind.WORD)
        Truth.assertThat<Lexer.TokenKind>(tokens[1].kind).isEqualTo(Lexer.TokenKind.WORD)
        Truth.assertThat<Lexer.TokenKind>(tokens[2].kind).isEqualTo(Lexer.TokenKind.WORD)
        Truth.assertThat(tokens[2].word).isEqualTo("foo bar")
    }

    @Test
    @Throws(QuerySyntaxException::class)
    fun testQuotedWordsAreNotIdentifiers() {
        val tokens = scan("set 'set' \"set\"")
        Truth.assertThat(asString(tokens)).isEqualTo("set set set EOF")
        Truth.assertThat<Lexer.TokenKind>(tokens[0].kind).isEqualTo(Lexer.TokenKind.SET)
        Truth.assertThat<Lexer.TokenKind>(tokens[1].kind).isEqualTo(Lexer.TokenKind.WORD)
        Truth.assertThat<Lexer.TokenKind>(tokens[2].kind).isEqualTo(Lexer.TokenKind.WORD)
    }

    @Test
    fun testUnterminatedQuotation() {
        val e = Assert.assertThrows<QuerySyntaxException?>(
            QuerySyntaxException::class.java,
            ThrowingRunnable { scan("'foo") })
        Truth.assertThat(e).hasMessageThat().isEqualTo("unclosed quotation")
    }

    @Test
    @Throws(QuerySyntaxException::class)
    fun testOperatorWithSpecialCharacters() {
        val tokens = scan("set(//foo_bar:.*@4)")
        Truth.assertThat(asString(tokens)).isEqualTo("set ( //foo_bar:.*@4 ) EOF")
        Truth.assertThat<Lexer.TokenKind>(tokens[0].kind).isEqualTo(Lexer.TokenKind.SET)
        Truth.assertThat<Lexer.TokenKind>(tokens[1].kind).isEqualTo(Lexer.TokenKind.LPAREN)
        Truth.assertThat<Lexer.TokenKind>(tokens[2].kind).isEqualTo(Lexer.TokenKind.WORD)
        Truth.assertThat<Lexer.TokenKind>(tokens[3].kind).isEqualTo(Lexer.TokenKind.RPAREN)
    }

    @Test
    @Throws(QuerySyntaxException::class)
    fun testOperatorWithQuotedExprWithSpecialCharacters() {
        val tokens = scan("set(\"//foo_bar:.*@4\")")
        Truth.assertThat(asString(tokens)).isEqualTo("set ( //foo_bar:.*@4 ) EOF")
        Truth.assertThat<Lexer.TokenKind>(tokens[0].kind).isEqualTo(Lexer.TokenKind.SET)
        Truth.assertThat<Lexer.TokenKind>(tokens[1].kind).isEqualTo(Lexer.TokenKind.LPAREN)
        Truth.assertThat<Lexer.TokenKind>(tokens[2].kind).isEqualTo(Lexer.TokenKind.WORD)
        Truth.assertThat<Lexer.TokenKind>(tokens[3].kind).isEqualTo(Lexer.TokenKind.RPAREN)
    }

    @Test
    @Throws(QuerySyntaxException::class)
    fun testOperatorWithQuotedExprWithMoreSpecialCharacters() {
        val tokens = scan("set(\"//foo:foo=base/2~123[]+asd\")")
        Truth.assertThat(asString(tokens)).isEqualTo("set ( //foo:foo=base/2~123[]+asd ) EOF")
        Truth.assertThat<Lexer.TokenKind>(tokens[0].kind).isEqualTo(Lexer.TokenKind.SET)
        Truth.assertThat<Lexer.TokenKind>(tokens[1].kind).isEqualTo(Lexer.TokenKind.LPAREN)
        Truth.assertThat<Lexer.TokenKind>(tokens[2].kind).isEqualTo(Lexer.TokenKind.WORD)
        Truth.assertThat<Lexer.TokenKind>(tokens[3].kind).isEqualTo(Lexer.TokenKind.RPAREN)
    }

    @Test
    @Throws(QuerySyntaxException::class)
    fun testOperatorWithUnquotedExprWithSpecialCharacters() {
        val tokens = scan("set(//a:b=bar./@_:~-*$123[]+asd)")
        Truth.assertThat(asString(tokens)).isEqualTo("set ( //a:b = bar./@_:~-*$123[] + asd ) EOF")
        Truth.assertThat<Lexer.TokenKind>(tokens[0].kind).isEqualTo(Lexer.TokenKind.SET)
        Truth.assertThat<Lexer.TokenKind>(tokens[1].kind).isEqualTo(Lexer.TokenKind.LPAREN)
        Truth.assertThat<Lexer.TokenKind>(tokens[2].kind).isEqualTo(Lexer.TokenKind.WORD)
        Truth.assertThat<Lexer.TokenKind>(tokens[3].kind).isEqualTo(Lexer.TokenKind.EQUALS)
        Truth.assertThat<Lexer.TokenKind>(tokens[4].kind).isEqualTo(Lexer.TokenKind.WORD)
        Truth.assertThat<Lexer.TokenKind>(tokens[5].kind).isEqualTo(Lexer.TokenKind.PLUS)
        Truth.assertThat<Lexer.TokenKind>(tokens[6].kind).isEqualTo(Lexer.TokenKind.WORD)
        Truth.assertThat<Lexer.TokenKind>(tokens[7].kind).isEqualTo(Lexer.TokenKind.RPAREN)
    }

    @Test
    @Throws(QuerySyntaxException::class)
    fun testUnquotedCanonicalLabels() {
        val tokens =
            scan("somepath(@foo+@bar+//baz+@@foo +bar,  @@rules_jvm_external++maven+maven//:bar)")
        Truth.assertThat(asString(tokens))
            .isEqualTo(
                "somepath ( @foo + @bar + //baz + @@foo + bar , @@rules_jvm_external++maven+maven//:bar"
                        + " ) EOF"
            )
        Truth.assertThat<Lexer.TokenKind>(tokens[0].kind).isEqualTo(Lexer.TokenKind.WORD)
        Truth.assertThat<Lexer.TokenKind>(tokens[1].kind).isEqualTo(Lexer.TokenKind.LPAREN)
        Truth.assertThat<Lexer.TokenKind>(tokens[2].kind).isEqualTo(Lexer.TokenKind.WORD)
        Truth.assertThat<Lexer.TokenKind>(tokens[3].kind).isEqualTo(Lexer.TokenKind.PLUS)
        Truth.assertThat<Lexer.TokenKind>(tokens[4].kind).isEqualTo(Lexer.TokenKind.WORD)
        Truth.assertThat<Lexer.TokenKind>(tokens[5].kind).isEqualTo(Lexer.TokenKind.PLUS)
        Truth.assertThat<Lexer.TokenKind>(tokens[6].kind).isEqualTo(Lexer.TokenKind.WORD)
        Truth.assertThat<Lexer.TokenKind>(tokens[7].kind).isEqualTo(Lexer.TokenKind.PLUS)
        Truth.assertThat<Lexer.TokenKind>(tokens[8].kind).isEqualTo(Lexer.TokenKind.WORD)
        Truth.assertThat<Lexer.TokenKind>(tokens[9].kind).isEqualTo(Lexer.TokenKind.PLUS)
        Truth.assertThat<Lexer.TokenKind>(tokens[10].kind).isEqualTo(Lexer.TokenKind.WORD)
        Truth.assertThat<Lexer.TokenKind>(tokens[11].kind).isEqualTo(Lexer.TokenKind.COMMA)
        Truth.assertThat<Lexer.TokenKind>(tokens[12].kind).isEqualTo(Lexer.TokenKind.WORD)
        Truth.assertThat<Lexer.TokenKind>(tokens[13].kind).isEqualTo(Lexer.TokenKind.RPAREN)
    }
}
