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

import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.util.StringEncoding
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import java.lang.String
import java.util.stream.Stream
import kotlin.Exception
import kotlin.IllegalStateException
import kotlin.Int
import kotlin.toString

/** Tests of parser and pretty-printer.  */
@RunWith(JUnit4::class)
class QueryParserTest {
    private class MockFunction(
        val name: String?,
        val mandatoryArguments: Int,
        vararg arguments: QueryEnvironment.ArgumentType?
    ) : QueryFunction {
        val argumentTypes: MutableList<QueryEnvironment.ArgumentType?>

        init {
            this.argumentTypes = ImmutableList.copyOf<QueryEnvironment.ArgumentType?>(arguments)
        }

        override fun <T> eval(
            env: QueryEnvironment<T?>?,
            context: QueryExpressionContext<T?>?,
            expression: QueryExpression?,
            args: MutableList<QueryEnvironment.Argument?>?,
            callback: Callback<T?>?
        ): QueryTaskFuture<Void?>? {
            throw IllegalStateException()
        }
    }

    @Test
    @Throws(Exception::class)
    fun testOptionalArguments() {
        checkPrettyPrint("opt('foo', 'bar')")
        checkPrettyPrint("opt('foo', 'bar', 'qux')")
        checkParseFails(
            "opt('foo', 'bar', 'qux', 'zyc')", "too many arguments to function 'opt' at ', zyc )'"
        )
        checkParseFails("opt('foo')", "too few arguments to function 'opt' at ')'")
        checkParseFails("opt()", "too few arguments to function 'opt' at ')'")
    }

    @Test
    @Throws(Exception::class)
    fun testUnknownFunction() {
        val knownFunctions: String? =
            Stream.concat<T?>(
                QueryEnvironment.DEFAULT_QUERY_FUNCTIONS.stream()
                    .map({ f -> String.format("'%s'", f.getName()) }),
                Stream.of<kotlin.String?>("'opt'")
            )
                .sorted()
                .collect(Collectors.joining(", "))
        checkParseFails(
            "badfunc('foo', 'bar', 'qux', 'zyc')",
            kotlin.String.format(
                "unknown function 'badfunc' at 'badfunc ( foo'; expected one of [%s]", knownFunctions
            )
        )
    }

    @Test
    @Throws(Exception::class)
    fun testTargetLiterals() {
        checkPrettyPrint("x")
        checkPrettyPrint("//x")
        checkPrettyPrint("//x:y")
        checkPrettyPrint("x/...:all-targets")
        checkPrettyPrint("\"set\"") // reserved word
        checkPrettyPrint("\"\"")
    }

    @Test
    fun checkParseErrors() {
        checkParseFails("rdeps(", "premature end of input")
        checkParseFails("rdeps(,", "syntax error at ','")
        checkParseFails("rdeps(a", "premature end of input")
        checkParseFails("rdeps(a, ", "premature end of input")
        checkParseFails("rdeps(a, )", "syntax error at ')'")
        checkParseFails("rdeps(a, b", "premature end of input")
        checkParseFails("rdeps(a, b, ", "premature end of input")
        checkParseFails("rdeps(a, b, )", "syntax error at ')'")
        checkParseFails("rdeps(a, b, 3", "premature end of input")
        checkParseFails("rdeps(a, b, 3, ", "too many arguments to function 'rdeps' at ','")
        checkParseFails("rdeps(a, b, c, d)", "expected an integer literal: 'c'")
        checkParseFails("set(", "premature end of input")
        checkParseFails("set(a", "premature end of input")
        checkParseFails("set(a b", "premature end of input")
        checkParseFails("set(a, ", "syntax error at ','")
        checkParseFails("set(a, b)", "syntax error at ', b )'")
    }

    @Test
    @Throws(Exception::class)
    fun testBinaryOperators() {
        checkParseFails("foo intersect", "premature end of input")

        checkPrettyPrint("(a - b)", "a - b")

        checkPrettyPrint("(a intersect b)", "a intersect b")
        checkPrettyPrint("(a intersect b intersect c)", "a intersect b intersect c")
        checkPrettyPrint("(a union b)", "a union b")
        checkPrettyPrint("(a union b union c)", "a union b union c")
        checkPrettyPrint("(a except b)", "a except b")
        checkPrettyPrint("(a except b except c)", "a except b except c")
        checkPrettyPrint("((a union b) except c)", "a union b except c")
        checkPrettyPrint("((a except b) union c)", "a except b union c")
    }

    @Test
    @Throws(Exception::class)
    fun testOperators() {
        checkPrettyPrint("some(x)")
        checkPrettyPrint("somepath(x, y)")
        checkPrettyPrint("allpaths(x, y)")
        checkPrettyPrint("deps(x)")
        checkPrettyPrint("deps(x, 1)")
        checkPrettyPrint("rdeps(x, y)")
        checkPrettyPrint("rdeps(x, y, 1)")
        checkPrettyPrint("kind('rule', x)", "kind(rule, x)")
        checkPrettyPrint("kind('source file', x)")
        checkPrettyPrint("kind('.*', x)")
        checkPrettyPrint("attr('linkshared', '1', x)", "attr(linkshared,1,x)")
        checkPrettyPrint("filter('jar$', x)", "filter(jar$, x)")
        checkPrettyPrint("let x = e1 in e2")
        checkPrettyPrint("labels('srcs', x)")
        checkPrettyPrint("tests(x)")
        checkPrettyPrint("executables(x)")
        checkPrettyPrint("set()")
        checkPrettyPrint("set(//a)")
        checkPrettyPrint("set(//a //b)")
    }

    @Test
    @Throws(Exception::class)
    fun testMultipleOperatorParsing() {
        checkPrettyPrint(checkPrettyPrint("kind('rule', x)", "kind(rule, x)"))
        checkPrettyPrint(checkPrettyPrint("attr('linkshared', '1', x)", "attr(linkshared,1,x)"))
        checkPrettyPrint(checkPrettyPrint("filter('jar$', x)", "filter(jar$, x)"))
    }

    @Test
    @Throws(Exception::class)
    fun testMultipleBinaryOperatorParsing() {
        checkPrettyPrint(checkPrettyPrint("((a union b) except c)", "a union b except c"))
        checkPrettyPrint(checkPrettyPrint("(a intersect b intersect c)", "a intersect b intersect c"))
        checkPrettyPrint(checkPrettyPrint("(a union b union c)", "a union b union c"))
        checkPrettyPrint(
            checkPrettyPrint(
                "((((a union b) intersect c) except d) intersect e)",
                "a union b intersect c except d intersect e"
            )
        )
    }

    @Test
    @Throws(Exception::class)
    fun testMultipleTargetLiteralParsing() {
        checkPrettyPrint(checkPrettyPrint("//foo:.*@4", "\"//foo:.*@4\""))
        checkPrettyPrint(checkPrettyPrint("set(//foo)", "set(\"//foo\")"))
        checkPrettyPrint("\"set(//foo)\"")
        checkPrettyPrint("\"set('//foo')\"")
    }

    @Test
    @Throws(Exception::class)
    fun testQuotedAndUnquotedMetacharacters() {
        checkPrettyPrint("\"//foo:xx+xx\"")
        checkPrettyPrint(checkPrettyPrint("(//foo:xx + xx)", "//foo:xx+xx"))
        checkPrettyPrint("\"//foo:xx=xx\"")
        checkParseFails("//foo:xx=xx", "unexpected token '=' after query expression '//foo:xx'")
    }

    @Test
    @Throws(Exception::class)
    fun testQuotedSpecialCharacters() {
        checkPrettyPrint("\"foo[]^\$asd.|asd?*+{})_asd()2\"", "'foo[]^\$asd.|asd?*+{})_asd()2'")
        checkPrettyPrint("\"foo[]^\$asd.|asd?*+{})_asd()2\"")
        checkPrettyPrint("\" #&()+,;<=>?[]{|}\"")
    }

    @Test
    @Throws(Exception::class)
    fun testUnquotedSpecialCharacters() {
        // All special characters in the Lexer#scanWord ./@_:~-*$
        checkPrettyPrint("a.b")
        checkPrettyPrint("a/b")
        checkPrettyPrint("a@b")
        checkPrettyPrint("a_b")
        checkPrettyPrint("a:b")
        checkPrettyPrint("a~b")
        checkPrettyPrint("a-b")
        checkPrettyPrint("a*b")
        checkPrettyPrint("a\$b")
    }

    @Test
    @Throws(Exception::class)
    fun testPreserveQuoting() {
        checkPrettyPrint(checkPrettyPrint("\"a+b\""))
        // this should preserve quoting without being quoted in TargetLiteral#toString
        checkPrettyPrint(checkPrettyPrint("aaa", "\"aaa\""))
    }

    @Test
    @Throws(Exception::class)
    fun testQuotedIllegalCharacters() {
        checkParseFails("\"-x\"", "target literal must not begin with (-): -x")
        checkParseFails("\"*x\"", "target literal must not begin with (*): *x")
    }

    @Test
    @Throws(Exception::class)
    fun testIllegalQuoting() {
        checkParseFails("\"a", "unclosed quotation")
        checkParseFails("\'a", "unclosed quotation")
        checkParseFails("a\"a", "unclosed quotation")
        checkParseFails("a\'a", "unclosed quotation")
        checkParseFails("a\'\"a", "unclosed quotation")
        checkParseFails("a\"\'a", "unclosed quotation")
        checkParseFails("\'a\"\'a\'", "unclosed quotation")
        checkParseFails("\"a\'\"a\"", "unclosed quotation")
        checkParseFails(
            "\'\"a\" + \'a\'\'", "unexpected token 'a' after query expression ''\"a\" + ''"
        )
        checkParseFails(
            "\"\'a\' + \"a\"\"", "unexpected token 'a' after query expression '\"'a' + \"'"
        )
        checkParseFails(
            "\"set(\"//foo\" + \"bar\")\"",
            "unexpected token '//foo' after query expression '\"set(\"'"
        )
        checkParseFails(
            "'set('//foo' + 'bar')'", "unexpected token '//foo' after query expression '\"set(\"'"
        )
    }

    @Test
    @Throws(Exception::class)
    fun testUsingCorrectQuotingInTargetLiteralToString() {
        // These tests all fall into the needsQuoting == true use case in TargetLiteral#toString
        checkPrettyPrint("'set(\"//foo\" + \"bar\")'")
        checkPrettyPrint("\"set('//foo' + 'bar')\"")
        checkPrettyPrint("\"a'a\"")
        checkPrettyPrint("\'a\"a\'")
    }

    @Test
    @Throws(Exception::class)
    fun testUnicodeLabels() {
        checkPrettyPrint(
            StringEncoding.unicodeToInternal("//:äöüÄÖÜß🌱"),
            StringEncoding.unicodeToInternal("'//:äöüÄÖÜß🌱'")
        )
        checkPrettyPrint(
            StringEncoding.unicodeToInternal("//:äöüÄÖÜß🌱"),
            StringEncoding.unicodeToInternal("//:äöüÄÖÜß🌱")
        )
    }

    companion object {
        private fun mockEnvironment(): QueryEnvironment<*> {
            val functions: ImmutableList.Builder<QueryFunction?> = ImmutableList.builder<QueryFunction?>()
            functions.addAll(QueryEnvironment.DEFAULT_QUERY_FUNCTIONS)
            functions.add(
                MockFunction(
                    "opt",
                    2,
                    QueryEnvironment.ArgumentType.WORD,
                    QueryEnvironment.ArgumentType.WORD,
                    QueryEnvironment.ArgumentType.WORD
                )
            )

            val result: QueryEnvironment<*> = Mockito.mock<QueryEnvironment<*>>(QueryEnvironment::class.java)
            Mockito.`when`<Iterable<QueryFunction?>?>(result.functions).thenReturn(functions.build())
            return result
        }

        // Asserts that 'query' parses, and that when pretty-printed, yields 'query'.
        @Throws(Exception::class)
        private fun checkPrettyPrint(query: kotlin.String?): kotlin.String? {
            return checkPrettyPrint(query, query)
        }

        // Asserts that 'query' parses, and that when pretty-printed, yields
        // 'expectedPrettyPrintOutput'.
        @Throws(Exception::class)
        private fun checkPrettyPrint(expectedPrettyPrintOutput: kotlin.String?, query: kotlin.String?): kotlin.String? {
            assertThat(QueryExpression.parse(query, mockEnvironment()).toString())
                .isEqualTo(expectedPrettyPrintOutput)
            return expectedPrettyPrintOutput
        }

        fun checkParseFails(query: kotlin.String?, expectedError: kotlin.String?) {
            val e =
                Assert.assertThrows<QuerySyntaxException?>(
                    QuerySyntaxException::class.java,
                    ThrowingRunnable { QueryExpression.parse(query, mockEnvironment()) })
            Truth.assertThat(e).hasMessageThat().isEqualTo(expectedError)
        }
    }
}
