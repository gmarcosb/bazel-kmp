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

import com.google.common.truth.Truth
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.add
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import net.starlark.java.syntax.FileOptions.Builder.build
import net.starlark.java.syntax.Location.column
import net.starlark.java.syntax.Location.line
import net.starlark.java.syntax.SyntaxError.Exception.errors
import net.starlark.java.syntax.TypeTable.errors
import net.starlark.java.syntax.TypeTable.getType
import net.starlark.java.syntax.TypeTable.ok
import org.junit.runner.RunWith
import java.math.BigInteger

/** Tests of parser.  */
@RunWith(TestParameterInjector::class)
class ParserTest {
    private val events: MutableList<net.starlark.java.syntax.SyntaxError> =
        java.util.ArrayList<net.starlark.java.syntax.SyntaxError>()
    private var failFast = true
    private var fileOptions: net.starlark.java.syntax.FileOptions? = net.starlark.java.syntax.FileOptions.DEFAULT

    private fun assertContainsError(expectedMessage: String?): net.starlark.java.syntax.SyntaxError {
        return net.starlark.java.syntax.TestUtils.assertContainsError(events, expectedMessage)
    }

    private fun setFailFast(failFast: Boolean) {
        this.failFast = failFast
    }

    private fun setFileOptions(fileOptions: net.starlark.java.syntax.FileOptions?) {
        this.fileOptions = fileOptions
    }

    // Joins the lines, parse, and returns an expression.
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    private fun parseExpression(vararg lines: String?): net.starlark.java.syntax.Expression {
        val input: net.starlark.java.syntax.ParserInput? = net.starlark.java.syntax.ParserInput.fromLines(*lines)
        return net.starlark.java.syntax.Expression.parse(input, fileOptions)
    }

    // Parses the expression, asserts that parsing fails,
    // and returns the first error message.
    private fun parseExpressionError(src: String?): String? {
        val input: net.starlark.java.syntax.ParserInput? = net.starlark.java.syntax.ParserInput.fromLines(src)
        try {
            net.starlark.java.syntax.Expression.parse(input, fileOptions)
            throw java.lang.AssertionError("parseExpressionError() succeeded unexpectedly: " + src)
        } catch (ex: net.starlark.java.syntax.SyntaxError.Exception) {
            return ex.errors().get(0).toString()
        }
    }

    // Parses the statement, asserts that parsing fails, and returns the first error message.
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    private fun parseStatementError(src: String?): String? {
        val input: net.starlark.java.syntax.ParserInput? = net.starlark.java.syntax.ParserInput.fromLines(src)
        val file: net.starlark.java.syntax.StarlarkFile =
            net.starlark.java.syntax.StarlarkFile.parse(input, fileOptions)
        if (file.ok()) {
            throw java.lang.AssertionError("parseStatementError() succeeded unexpectedly: " + src)
        }
        return file.errors().get(0).toString()
    }

    // Joins the lines, parse, and returns a type expression.
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    private fun parseTypeExpression(vararg lines: String?): net.starlark.java.syntax.Expression? {
        val input: net.starlark.java.syntax.ParserInput? = net.starlark.java.syntax.ParserInput.fromLines(*lines)
        return net.starlark.java.syntax.Expression.parseTypeExpression(input, fileOptions)
    }

    // Parses the type expression, asserts that parsing fails, and returns the first error message.
    private fun parseTypeExpressionError(src: String?): String? {
        val input: net.starlark.java.syntax.ParserInput? = net.starlark.java.syntax.ParserInput.fromLines(src)
        try {
            net.starlark.java.syntax.Expression.parseTypeExpression(input, fileOptions)
            throw java.lang.AssertionError("parseTypeExpressionError() succeeded unexpectedly: " + src)
        } catch (ex: net.starlark.java.syntax.SyntaxError.Exception) {
            return ex.errors().get(0).toString()
        }
    }

    // Joins the lines, parses, and returns a file.
    // Errors are added to this.events, or thrown if this.failFast;
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    private fun parseFile(vararg lines: String?): net.starlark.java.syntax.StarlarkFile {
        val input: net.starlark.java.syntax.ParserInput? = net.starlark.java.syntax.ParserInput.fromLines(*lines)
        val file: net.starlark.java.syntax.StarlarkFile =
            net.starlark.java.syntax.StarlarkFile.parse(input, fileOptions)
        if (!file.ok()) {
            if (failFast) {
                throw net.starlark.java.syntax.SyntaxError.Exception(file.errors())
            }
            // TODO(adonovan): return these, and eliminate a stateful field.
            events.addAll(file.errors())
        }
        return file
    }

    // Joins the lines, parses, and returns the sole statement.
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    private fun parseStatement(vararg lines: String?): net.starlark.java.syntax.Statement {
        return com.google.common.collect.Iterables.getOnlyElement<net.starlark.java.syntax.Statement>(parseStatements(*lines))
    }

    // Joins the lines, parses, and returns the statements.
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    private fun parseStatements(vararg lines: String?): com.google.common.collect.ImmutableList<net.starlark.java.syntax.Statement> {
        return parseFile(*lines).getStatements()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPrecedence1() {
        val e: net.starlark.java.syntax.BinaryOperatorExpression =
            parseExpression("'%sx' % 'foo' + 'bar'") as net.starlark.java.syntax.BinaryOperatorExpression

        Truth.assertThat<net.starlark.java.syntax.TokenKind?>(e.getOperator())
            .isEqualTo(net.starlark.java.syntax.TokenKind.PLUS)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPrecedence2() {
        val e: net.starlark.java.syntax.BinaryOperatorExpression =
            parseExpression("('%sx' % 'foo') + 'bar'") as net.starlark.java.syntax.BinaryOperatorExpression
        Truth.assertThat<net.starlark.java.syntax.TokenKind?>(e.getOperator())
            .isEqualTo(net.starlark.java.syntax.TokenKind.PLUS)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPrecedence3() {
        val e: net.starlark.java.syntax.BinaryOperatorExpression =
            parseExpression("'%sx' % ('foo' + 'bar')") as net.starlark.java.syntax.BinaryOperatorExpression
        Truth.assertThat<net.starlark.java.syntax.TokenKind?>(e.getOperator())
            .isEqualTo(net.starlark.java.syntax.TokenKind.PERCENT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPrecedence4() {
        val e: net.starlark.java.syntax.BinaryOperatorExpression =
            parseExpression("1 + - (2 - 3)") as net.starlark.java.syntax.BinaryOperatorExpression
        Truth.assertThat<net.starlark.java.syntax.TokenKind?>(e.getOperator())
            .isEqualTo(net.starlark.java.syntax.TokenKind.PLUS)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPrecedence5() {
        val e: net.starlark.java.syntax.BinaryOperatorExpression =
            parseExpression("2 * x | y + 1") as net.starlark.java.syntax.BinaryOperatorExpression
        Truth.assertThat<net.starlark.java.syntax.TokenKind?>(e.getOperator())
            .isEqualTo(net.starlark.java.syntax.TokenKind.PIPE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonAssociativeOperators() {
        Truth.assertThat(parseExpressionError("0 < 2 < 4"))
            .contains("Operator '<' is not associative with operator '<'")
        Truth.assertThat(parseExpressionError("0 == 2 < 4"))
            .contains("Operator '==' is not associative with operator '<'")
        Truth.assertThat(parseExpressionError("1 in [1, 2] == True"))
            .contains("Operator 'in' is not associative with operator '=='")
        Truth.assertThat(parseExpressionError("1 >= 2 <= 3"))
            .contains("Operator '>=' is not associative with operator '<='")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonAssociativeOperatorsWithParens() {
        parseExpression("(0 < 2) < 4")
        parseExpression("(0 == 2) < 4")
        parseExpression("(1 in [1, 2]) == True")
        parseExpression("1 >= (2 <= 3)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUnaryMinusForNonLiteral() {
        val expression: net.starlark.java.syntax.Expression = parseExpression("-(5 + 3)")
        Truth.assertThat(expression).isInstanceOf(net.starlark.java.syntax.UnaryOperatorExpression::class.java)
        val unaryExpression: net.starlark.java.syntax.UnaryOperatorExpression =
            expression as net.starlark.java.syntax.UnaryOperatorExpression
        Truth.assertThat<net.starlark.java.syntax.TokenKind?>(unaryExpression.getOperator())
            .isEqualTo(net.starlark.java.syntax.TokenKind.MINUS)
        Truth.assertThat(unaryExpression.getX())
            .isInstanceOf(net.starlark.java.syntax.BinaryOperatorExpression::class.java)
        val binaryExpression: net.starlark.java.syntax.BinaryOperatorExpression =
            unaryExpression.getX() as net.starlark.java.syntax.BinaryOperatorExpression
        Truth.assertThat(binaryExpression.getX()).isInstanceOf(net.starlark.java.syntax.IntLiteral::class.java)
        val x: net.starlark.java.syntax.IntLiteral = binaryExpression.getX() as net.starlark.java.syntax.IntLiteral
        Truth.assertThat(x.getValue()).isEqualTo(5)
        Truth.assertThat(binaryExpression.getY()).isInstanceOf(net.starlark.java.syntax.IntLiteral::class.java)
        val y: net.starlark.java.syntax.IntLiteral = binaryExpression.getY() as net.starlark.java.syntax.IntLiteral
        Truth.assertThat(y.getValue()).isEqualTo(3)

        assertLocation(0, 7, expression)
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    private fun parseAndVerifyIntLiteral(input: String, expectedValue: Number): net.starlark.java.syntax.IntLiteral {
        val expression: net.starlark.java.syntax.Expression = parseExpression(input)
        Truth.assertWithMessage("parseExpression(\"%s\")", input)
            .that(expression)
            .isInstanceOf(net.starlark.java.syntax.IntLiteral::class.java)
        val intLiteral: net.starlark.java.syntax.IntLiteral = expression as net.starlark.java.syntax.IntLiteral
        Truth.assertWithMessage("parseExpression(\"%s\")", input)
            .that(intLiteral.getValue())
            .isInstanceOf(expectedValue.javaClass)
        Truth.assertWithMessage("parseExpression(\"%s\")", input)
            .that(intLiteral.getValue())
            .isEqualTo(expectedValue)
        return intLiteral
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUnaryMinusWithIntLiteral() {
        parseAndVerifyIntLiteral("-0", 0)
        parseAndVerifyIntLiteral("--0", 0)
        parseAndVerifyIntLiteral("-5", -5)
        parseAndVerifyIntLiteral("--5", 5)
        parseAndVerifyIntLiteral("-3000000000", -3000000000L)
        parseAndVerifyIntLiteral("--3000000000", 3000000000L)
        parseAndVerifyIntLiteral("-10000000000000000000", BigInteger("-10000000000000000000"))
        parseAndVerifyIntLiteral("--10000000000000000000", BigInteger("10000000000000000000"))
        // Edge cases
        parseAndVerifyIntLiteral(String.format("-%d", Int.Companion.MAX_VALUE), Int.Companion.MIN_VALUE + 1)
        parseAndVerifyIntLiteral(String.format("%d", Int.Companion.MIN_VALUE), Int.Companion.MIN_VALUE)
        parseAndVerifyIntLiteral(
            String.format("-%d", Int.Companion.MIN_VALUE), Int.Companion.MAX_VALUE.toLong() + 1L
        )
        parseAndVerifyIntLiteral(String.format("-%d", Long.Companion.MAX_VALUE), Long.Companion.MIN_VALUE + 1L)
        parseAndVerifyIntLiteral(String.format("%d", Long.Companion.MIN_VALUE), Long.Companion.MIN_VALUE)
        parseAndVerifyIntLiteral(
            String.format("-%d", Long.Companion.MIN_VALUE),
            BigInteger.valueOf(Long.Companion.MAX_VALUE).add(BigInteger.ONE)
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUnaryMinusWithIntLiteralLocation() {
        val intLiteral: net.starlark.java.syntax.IntLiteral = parseAndVerifyIntLiteral("- 5", -5)
        assertLocation(0, 3, intLiteral)
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    private fun parseAndVerifyFloatLiteral(
        input: String,
        expectedValue: Double
    ): net.starlark.java.syntax.FloatLiteral {
        val expression: net.starlark.java.syntax.Expression = parseExpression(input)
        Truth.assertWithMessage(input).that(expression).isInstanceOf(net.starlark.java.syntax.FloatLiteral::class.java)
        val floatLiteral: net.starlark.java.syntax.FloatLiteral = expression as net.starlark.java.syntax.FloatLiteral
        Truth.assertWithMessage("parseExpression(\"%s\") expected to equal %s", input, expectedValue)
            .that(floatLiteral.getValue() == expectedValue) // For doubles, == and equals() differ
            .isTrue()
        return floatLiteral
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUnaryMinusWithFloatLiteral() {
        parseAndVerifyFloatLiteral("-0.0", -0.0)
        parseAndVerifyFloatLiteral("--0.0", 0.0)
        parseAndVerifyFloatLiteral("-5.5", -5.5)
        parseAndVerifyFloatLiteral("--5.5", 5.5)
        // 0.0 vs. -0.0
        val zero: net.starlark.java.syntax.FloatLiteral = parseAndVerifyFloatLiteral("0.0", 0.0)
        val minusZero: net.starlark.java.syntax.FloatLiteral = parseAndVerifyFloatLiteral("-0.0", -0.0)
        val minusMinusZero: net.starlark.java.syntax.FloatLiteral = parseAndVerifyFloatLiteral("--0.0", 0.0)
        Truth.assertThat(minusMinusZero.getValue()).isEqualTo(zero.getValue())
        Truth.assertThat(minusZero.getValue()).isNotEqualTo(zero.getValue())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testZeroFloatLiteralDistinguishesSign() {
        val positiveZero: net.starlark.java.syntax.FloatLiteral =
            parseExpression("0.0") as net.starlark.java.syntax.FloatLiteral
        val negativeZero: net.starlark.java.syntax.FloatLiteral =
            parseExpression("-0.0") as net.starlark.java.syntax.FloatLiteral
        Truth.assertThat(positiveZero.getValue() == 0.0).isTrue()
        Truth.assertThat(negativeZero.getValue() == 0.0).isTrue()
        Truth.assertThat(positiveZero.getValue()).isNotEqualTo(negativeZero.getValue())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFuncallExpr() {
        val e: net.starlark.java.syntax.CallExpression =
            parseExpression("foo[0](1, 2, bar=wiz)") as net.starlark.java.syntax.CallExpression

        val function: net.starlark.java.syntax.IndexExpression =
            e.getFunction() as net.starlark.java.syntax.IndexExpression
        val functionList: net.starlark.java.syntax.Identifier =
            function.getObject() as net.starlark.java.syntax.Identifier
        Truth.assertThat(functionList.getName()).isEqualTo("foo")
        val listIndex: net.starlark.java.syntax.IntLiteral = function.getKey() as net.starlark.java.syntax.IntLiteral
        Truth.assertThat(listIndex.getValue()).isEqualTo(0)

        Truth.assertThat(e.getArguments()).hasSize(3)
        Truth.assertThat(e.getNumPositionalArguments()).isEqualTo(2)

        val arg0: net.starlark.java.syntax.IntLiteral =
            e.getArguments().get(0).getValue() as net.starlark.java.syntax.IntLiteral
        Truth.assertThat(arg0.getValue() as Int).isEqualTo(1)

        val arg1: net.starlark.java.syntax.IntLiteral =
            e.getArguments().get(1).getValue() as net.starlark.java.syntax.IntLiteral
        Truth.assertThat(arg1.getValue() as Int).isEqualTo(2)

        val arg2: net.starlark.java.syntax.Argument = e.getArguments().get(2)
        Truth.assertThat(arg2.getName()).isEqualTo("bar")
        val arg2val: net.starlark.java.syntax.Identifier = arg2.getValue() as net.starlark.java.syntax.Identifier
        Truth.assertThat(arg2val.getName()).isEqualTo("wiz")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMethCallExpr() {
        val e: net.starlark.java.syntax.CallExpression =
            parseExpression("foo.foo(1, 2, bar=wiz)") as net.starlark.java.syntax.CallExpression

        val dotExpression: net.starlark.java.syntax.DotExpression =
            e.getFunction() as net.starlark.java.syntax.DotExpression
        Truth.assertThat(dotExpression.getField().getName()).isEqualTo("foo")

        Truth.assertThat(e.getArguments()).hasSize(3)
        Truth.assertThat(e.getNumPositionalArguments()).isEqualTo(2)

        val arg0: net.starlark.java.syntax.IntLiteral =
            e.getArguments().get(0).getValue() as net.starlark.java.syntax.IntLiteral
        Truth.assertThat(arg0.getValue() as Int).isEqualTo(1)

        val arg1: net.starlark.java.syntax.IntLiteral =
            e.getArguments().get(1).getValue() as net.starlark.java.syntax.IntLiteral
        Truth.assertThat(arg1.getValue() as Int).isEqualTo(2)

        val arg2: net.starlark.java.syntax.Argument = e.getArguments().get(2)
        Truth.assertThat(arg2.getName()).isEqualTo("bar")
        val arg2val: net.starlark.java.syntax.Identifier = arg2.getValue() as net.starlark.java.syntax.Identifier
        Truth.assertThat(arg2val.getName()).isEqualTo("wiz")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testChainedMethCallExpr() {
        val e: net.starlark.java.syntax.CallExpression =
            parseExpression("foo.replace().split(1)") as net.starlark.java.syntax.CallExpression

        val dotExpr: net.starlark.java.syntax.DotExpression = e.getFunction() as net.starlark.java.syntax.DotExpression
        Truth.assertThat(dotExpr.getField().getName()).isEqualTo("split")

        Truth.assertThat(e.getArguments()).hasSize(1)
        Truth.assertThat(e.getNumPositionalArguments()).isEqualTo(1)

        val arg0: net.starlark.java.syntax.IntLiteral =
            e.getArguments().get(0).getValue() as net.starlark.java.syntax.IntLiteral
        Truth.assertThat(arg0.getValue() as Int).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPropRefExpr() {
        val e: net.starlark.java.syntax.DotExpression =
            parseExpression("foo.foo") as net.starlark.java.syntax.DotExpression

        val ident: net.starlark.java.syntax.Identifier = e.getField()
        Truth.assertThat(ident.getName()).isEqualTo("foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringMethExpr() {
        val e: net.starlark.java.syntax.CallExpression =
            parseExpression("'foo'.foo()") as net.starlark.java.syntax.CallExpression

        val dotExpression: net.starlark.java.syntax.DotExpression =
            e.getFunction() as net.starlark.java.syntax.DotExpression
        Truth.assertThat(dotExpression.getField().getName()).isEqualTo("foo")

        Truth.assertThat(e.getArguments()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringLiteralOptimizationValue() {
        val l: net.starlark.java.syntax.StringLiteral =
            parseExpression("'abc' + 'def'") as net.starlark.java.syntax.StringLiteral
        Truth.assertThat(l.getValue()).isEqualTo("abcdef")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringLiteralOptimizationToString() {
        val l: net.starlark.java.syntax.StringLiteral =
            parseExpression("'abc' + 'def'") as net.starlark.java.syntax.StringLiteral
        Truth.assertThat(l.toString()).isEqualTo("\"abcdef\"")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringLiteralOptimizationLocation() {
        val l: net.starlark.java.syntax.StringLiteral =
            parseExpression("'abc' + 'def'") as net.starlark.java.syntax.StringLiteral
        Truth.assertThat(l.getStartOffset()).isEqualTo(0)
        Truth.assertThat(l.getEndOffset()).isEqualTo(13)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringLiteralOptimizationDifferentQuote() {
        val l: net.starlark.java.syntax.StringLiteral =
            parseExpression("'abc' + \"def\"") as net.starlark.java.syntax.StringLiteral
        Truth.assertThat(l.getStartOffset()).isEqualTo(0)
        Truth.assertThat(l.getEndOffset()).isEqualTo(13)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIndex() {
        val e: net.starlark.java.syntax.IndexExpression =
            parseExpression("a[i]") as net.starlark.java.syntax.IndexExpression
        Truth.assertThat(e.getObject().toString()).isEqualTo("a")
        Truth.assertThat(e.getKey().toString()).isEqualTo("i")
        assertLocation(0, 4, e)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubstring() {
        var s: net.starlark.java.syntax.SliceExpression =
            parseExpression("'FOO.CC'[:].lower()[1:]") as net.starlark.java.syntax.SliceExpression
        Truth.assertThat((s.start as net.starlark.java.syntax.IntLiteral).getValue()).isEqualTo(1)

        val e: net.starlark.java.syntax.CallExpression =
            parseExpression("'FOO.CC'.lower()[1:].startswith('oo')") as net.starlark.java.syntax.CallExpression
        val dotExpression: net.starlark.java.syntax.DotExpression =
            e.getFunction() as net.starlark.java.syntax.DotExpression
        Truth.assertThat(dotExpression.getField().getName()).isEqualTo("startswith")
        Truth.assertThat(e.getArguments()).hasSize(1)

        s = parseExpression("'FOO.CC'[1:][:2]") as net.starlark.java.syntax.SliceExpression
        Truth.assertThat((s.stop as net.starlark.java.syntax.IntLiteral).getValue()).isEqualTo(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSlice() {
        evalSlice("'0123'[:]", "", "", "")
        evalSlice("'0123'[1:]", 1, "", "")
        evalSlice("'0123'[:3]", "", 3, "")
        evalSlice("'0123'[::]", "", "", "")
        evalSlice("'0123'[1::]", 1, "", "")
        evalSlice("'0123'[:3:]", "", 3, "")
        evalSlice("'0123'[::-1]", "", "", -1)
        evalSlice("'0123'[1:3:]", 1, 3, "")
        evalSlice("'0123'[1::-1]", 1, "", -1)
        evalSlice("'0123'[:3:-1]", "", 3, -1)
        evalSlice("'0123'[1:3:-1]", 1, 3, -1)

        val slice: net.starlark.java.syntax.Expression = parseExpression("'0123'[1:3:-1]")
        assertLocation(0, 14, slice)
    }

    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    private fun evalSlice(statement: String?, vararg expectedArgs: Any?) {
        val e: net.starlark.java.syntax.SliceExpression =
            parseExpression(statement) as net.starlark.java.syntax.SliceExpression

        // There is no way to evaluate the expression here, so we rely on string comparison.
        val start: String? = if (e.getStart() == null) "" else e.getStart().toString()
        val stop: String? = if (e.getStop() == null) "" else e.getStop().toString()
        val step: String? = if (e.getStep() == null) "" else e.getStep().toString()

        Truth.assertThat(start).isEqualTo(expectedArgs[0].toString())
        Truth.assertThat(stop).isEqualTo(expectedArgs[1].toString())
        Truth.assertThat(step).isEqualTo(expectedArgs[2].toString())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testErrorRecovery() {
        setFailFast(false)

        // We call parseFile, not parseExpression, as the latter is all-or-nothing.
        val src = "f(1, [x for foo foo foo foo], 3)"
        val e: net.starlark.java.syntax.CallExpression =
            (parseStatement(src) as net.starlark.java.syntax.ExpressionStatement).getExpression() as net.starlark.java.syntax.CallExpression

        assertContainsError("syntax error at 'foo'")

        // Test that the arguments are (1, '[x for foo foo foo foo]', 3),
        // where the second, errant one is represented as an Identifier.
        val ident: net.starlark.java.syntax.Identifier = e.getFunction() as net.starlark.java.syntax.Identifier
        Truth.assertThat(ident.getName()).isEqualTo("f")

        Truth.assertThat(e.getArguments()).hasSize(3)
        Truth.assertThat(e.getNumPositionalArguments()).isEqualTo(3)

        val arg0: net.starlark.java.syntax.IntLiteral =
            e.getArguments().get(0).getValue() as net.starlark.java.syntax.IntLiteral
        Truth.assertThat(arg0.getValue() as Int).isEqualTo(1)

        val arg1: net.starlark.java.syntax.Argument = e.getArguments().get(1)
        val arg1val: net.starlark.java.syntax.Identifier = (arg1.getValue() as net.starlark.java.syntax.Identifier)
        Truth.assertThat(arg1val.getName()).isEqualTo("[x for foo foo foo foo]")

        assertLocation(5, 28, arg1val)
        Truth.assertThat(src.substring(5, 28)).isEqualTo("[x for foo foo foo foo]")
        Truth.assertThat(arg1val.getEndLocation().column()).isEqualTo(29)

        val arg2: net.starlark.java.syntax.IntLiteral =
            e.getArguments().get(2).getValue() as net.starlark.java.syntax.IntLiteral
        Truth.assertThat(arg2.getValue() as Int).isEqualTo(3)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoesntGetStuck() {
        // Make sure the parser does not get stuck when trying
        // to parse an expression containing a syntax error.
        // This usually results in OutOfMemoryError because the
        // parser keeps filling up the error log.
        // We need to make sure that we will always advance
        // in the token stream.
        parseExpressionError("f(1, ], 3)")
        parseExpressionError("f(1, ), 3)")
        parseExpressionError("[ ) for v in 3)")
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun testPrimaryLocation() {
        val expr = "f(1 + 2)"
        val call: net.starlark.java.syntax.CallExpression =
            parseExpression(expr) as net.starlark.java.syntax.CallExpression
        val arg: net.starlark.java.syntax.Argument = call.getArguments().get(0)
        Truth.assertThat<net.starlark.java.syntax.Location?>(arg.getEndLocation()).isLessThan(call.getEndLocation())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAssignLocation() {
        val statements: MutableList<net.starlark.java.syntax.Statement> = parseStatements("a = b;c = d\n")
        val statement: net.starlark.java.syntax.Statement = statements.get(0)
        Truth.assertThat(statement.getEndOffset()).isEqualTo(5)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAssignKeyword() {
        Truth.assertThat(parseExpressionError("with = 4")).contains("keyword 'with' not supported")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBreak() {
        Truth.assertThat(parseExpressionError("break"))
            .contains("syntax error at 'break': expected expression")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTry() {
        Truth.assertThat(parseExpressionError("try: 1 + 1"))
            .contains("'try' not supported, all exceptions are fatal")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDel() {
        Truth.assertThat(parseExpressionError("del d['a']"))
            .contains("'del' not supported, use '.pop()' to delete")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTupleAssign() {
        val statements: MutableList<net.starlark.java.syntax.Statement> =
            parseStatements("list[0] = 5; dict['key'] = value\n")
        Truth.assertThat(statements).hasSize(2)
        Truth.assertThat(statements.get(0)).isInstanceOf(net.starlark.java.syntax.AssignmentStatement::class.java)
        Truth.assertThat(statements.get(1)).isInstanceOf(net.starlark.java.syntax.AssignmentStatement::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAssign() {
        val statements: MutableList<net.starlark.java.syntax.Statement> = parseStatements("a, b = 5\n")
        Truth.assertThat(statements).hasSize(1)
        Truth.assertThat(statements.get(0)).isInstanceOf(net.starlark.java.syntax.AssignmentStatement::class.java)
        val assign: net.starlark.java.syntax.AssignmentStatement =
            statements.get(0) as net.starlark.java.syntax.AssignmentStatement
        Truth.assertThat(assign.getLHS()).isInstanceOf(net.starlark.java.syntax.ListExpression::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidAssign() {
        Truth.assertThat(parseExpressionError("1 + (b = c)")).contains("syntax error")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAugmentedAssign() {
        Truth.assertThat(parseStatements("x += 1").toString()).isEqualTo("[x += 1\n]")
        Truth.assertThat(parseStatements("x -= 1").toString()).isEqualTo("[x -= 1\n]")
        Truth.assertThat(parseStatements("x *= 1").toString()).isEqualTo("[x *= 1\n]")
        Truth.assertThat(parseStatements("x /= 1").toString()).isEqualTo("[x /= 1\n]")
        Truth.assertThat(parseStatements("x %= 1").toString()).isEqualTo("[x %= 1\n]")
        Truth.assertThat(parseStatements("x |= 1").toString()).isEqualTo("[x |= 1\n]")
        Truth.assertThat(parseStatements("x &= 1").toString()).isEqualTo("[x &= 1\n]")
        Truth.assertThat(parseStatements("x <<= 1").toString()).isEqualTo("[x <<= 1\n]")
        Truth.assertThat(parseStatements("x >>= 1").toString()).isEqualTo("[x >>= 1\n]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVarAnnotation_basic() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())

        var stmt: net.starlark.java.syntax.Statement = parseStatement("x : T")
        Truth.assertThat(stmt).isInstanceOf(net.starlark.java.syntax.VarStatement::class.java)
        Truth.assertThat((stmt as net.starlark.java.syntax.VarStatement).getType().toString()).isEqualTo("T")

        stmt = parseStatement("x : T = 123")
        Truth.assertThat(stmt).isInstanceOf(net.starlark.java.syntax.AssignmentStatement::class.java)
        Truth.assertThat((stmt as net.starlark.java.syntax.AssignmentStatement).getType().toString()).isEqualTo("T")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVarAnnotation_requiresTypeSyntax() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(false).build())
        Truth.assertThat(parseStatementError("x : T"))
            .contains("syntax error at ':': type annotations are disallowed")
        Truth.assertThat(parseStatementError("x : T = 123"))
            .contains("syntax error at ':': type annotations are disallowed")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVarAnnotation_takesOneIdentifier() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())

        // Complaint located at colon at column 6.
        val errMessage =
            (":1:6: syntax error at ':': type annotations must have a single identifier on the"
                    + " left-hand side")

        Truth.assertThat(parseStatementError("x, y : T")).contains(errMessage)
        Truth.assertThat(parseStatementError("x, y : T = 123")).contains(errMessage)

        // This is *not* parsed as `x : (T, y)`, even though it might look unambiguous. Doing so would
        // require knowing what the comma is before we know whether we're in a VarStatement or
        // assignment statement. It's also not allowed by Python.
        Truth.assertThat(parseStatementError("x : T, y"))
            .contains(":1:6: syntax error at ',': expected newline")
        Truth.assertThat(parseStatementError("x : T, y = 123"))
            .contains(":1:6: syntax error at ',': expected newline")

        Truth.assertThat(parseStatementError("x[0] : T")).contains(errMessage)
        Truth.assertThat(parseStatementError("x[0] : T = 123")).contains(errMessage)

        // Only applicable to assignment, not VarStatement.
        Truth.assertThat(parseStatementError("(x : T, y) = 123")) // TODO: #27370 - Is there a reasonable way to produce a more informative error message
            // here, e.g. "type annotations are only allowed in assignment statements or variable
            // declarations"?
            .contains(":1:4: syntax error at ':': expected )")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVarAnnotation_notAllowedOnAugmentedAssignment() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        Truth.assertThat(parseStatementError("x : T += 123"))
            .contains(
                ":1:3: syntax error at ':': type annotations not allowed on augmented assignment"
                        + " statements"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVarAnnotation_illegalTypeExpression_allowedWithFlag() {
        setFileOptions(
            net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).tolerateInvalidTypeExpressions(true)
                .build()
        )
        Truth.assertThat((parseStatement("x : (lambda x: x)") as net.starlark.java.syntax.VarStatement).getType())
            .isInstanceOf(net.starlark.java.syntax.LambdaExpression::class.java)
        Truth.assertThat((parseStatement("x : (lambda x: x) = 123") as net.starlark.java.syntax.AssignmentStatement).getType())
            .isInstanceOf(net.starlark.java.syntax.LambdaExpression::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAssignWithAnnotation_illegalTypeExpression_disallowedWithoutFlag() {
        setFileOptions(
            net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).tolerateInvalidTypeExpressions(false)
                .build()
        )
        Truth.assertThat(parseStatementError("x : (lambda x: x)"))
            .contains(":1:5: syntax error at '(': expected a type")
        Truth.assertThat(parseStatementError("x : (lambda x: x) = 123"))
            .contains(":1:5: syntax error at '(': expected a type")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPrettyPrintFunctions() {
        Truth.assertThat(parseStatements("x[1:3]").toString()).isEqualTo("[x[1:3]\n]")
        Truth.assertThat(parseStatements("x[1:3:1]").toString()).isEqualTo("[x[1:3:1]\n]")
        Truth.assertThat(parseStatements("x[1:3:2]").toString()).isEqualTo("[x[1:3:2]\n]")
        Truth.assertThat(parseStatements("x[1::2]").toString()).isEqualTo("[x[1::2]\n]")
        Truth.assertThat(parseStatements("x[1:]").toString()).isEqualTo("[x[1:]\n]")
        Truth.assertThat(parseStatements("str[42]").toString()).isEqualTo("[str[42]\n]")
        Truth.assertThat(parseStatements("ctx.actions.declare_file('hello')").toString())
            .isEqualTo("[ctx.actions.declare_file(\"hello\")\n]")
        Truth.assertThat(parseStatements("new_file(\"hello\")").toString())
            .isEqualTo("[new_file(\"hello\")\n]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEndLineAndColumnIsExclusive() {
        // The behavior was 'inclusive' for a couple of years (see CL 170723732),
        // but this was a mistake. Arithmetic on half-open intervals is much simpler.
        val stmt: net.starlark.java.syntax.AssignmentStatement =
            parseStatement("a = b") as net.starlark.java.syntax.AssignmentStatement
        Truth.assertThat(stmt.getLHS().getEndLocation().toString()).isEqualTo(":1:2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFuncallLocation() {
        val statements: MutableList<net.starlark.java.syntax.Statement> = parseStatements("a(b);c = d\n")
        val statement: net.starlark.java.syntax.Statement = statements.get(0)
        Truth.assertThat(statement.getEndOffset()).isEqualTo(4)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListPositions() {
        val expr = "[0,f(1),2]"
        assertExpressionLocationCorrect(expr)
        val list: net.starlark.java.syntax.ListExpression =
            parseExpression(expr) as net.starlark.java.syntax.ListExpression
        Truth.assertThat(getText(expr, Companion.getElem(list, 0))).isEqualTo("0")
        Truth.assertThat(getText(expr, Companion.getElem(list, 1))).isEqualTo("f(1)")
        Truth.assertThat(getText(expr, Companion.getElem(list, 2))).isEqualTo("2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDictPositions() {
        val expr = "{1:2,2:f(1),3:4}"
        assertExpressionLocationCorrect(expr)
        val list: net.starlark.java.syntax.DictExpression =
            parseExpression(expr) as net.starlark.java.syntax.DictExpression
        Truth.assertThat(getText(expr, Companion.getElem(list, 0))).isEqualTo("1:2")
        Truth.assertThat(getText(expr, Companion.getElem(list, 1))).isEqualTo("2:f(1)")
        Truth.assertThat(getText(expr, Companion.getElem(list, 2))).isEqualTo("3:4")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArgumentPositions() {
        val expr = "f(0,g(1,2),2)"
        assertExpressionLocationCorrect(expr)
        val f: net.starlark.java.syntax.CallExpression =
            parseExpression(expr) as net.starlark.java.syntax.CallExpression
        Truth.assertThat(getText(expr, getArg(f, 0))).isEqualTo("0")
        Truth.assertThat(getText(expr, getArg(f, 1))).isEqualTo("g(1,2)")
        Truth.assertThat(getText(expr, getArg(f, 2))).isEqualTo("2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSuffixPosition() {
        assertExpressionLocationCorrect("'a'.len")
        assertExpressionLocationCorrect("'a'[0]")
        assertExpressionLocationCorrect("'a'[0:1]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTuplePosition() {
        var input = "for a,b in []: pass"
        var stmt: net.starlark.java.syntax.ForStatement = parseStatement(input) as net.starlark.java.syntax.ForStatement
        Truth.assertThat(getText(input, stmt.getVars())).isEqualTo("a,b")

        input = "for (a,b) in []: pass"
        stmt = parseStatement(input) as net.starlark.java.syntax.ForStatement
        Truth.assertThat(getText(input, stmt.getVars())).isEqualTo("(a,b)")

        assertExpressionLocationCorrect("a, b")
        assertExpressionLocationCorrect("(a, b)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testComprehensionPosition() {
        assertExpressionLocationCorrect("[[] for x in []]")
        assertExpressionLocationCorrect("{1: [] for x in []}")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUnaryOperationPosition() {
        assertExpressionLocationCorrect("not True")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadStatementPosition() {
        val input = "load(':foo.bzl', 'bar')"
        var stmt: net.starlark.java.syntax.LoadStatement =
            parseStatement(input) as net.starlark.java.syntax.LoadStatement
        Truth.assertThat(getText(input, stmt)).isEqualTo(input)
        // Also try it with another token at the end (newline), which broke the location in the past.
        stmt = parseStatement(input + "\n") as net.starlark.java.syntax.LoadStatement
        Truth.assertThat(getText(input, stmt)).isEqualTo(input)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testElif() {
        var ifA: net.starlark.java.syntax.IfStatement =
            parseStatement(
                "if a:",  //
                "  pass", "elif b:", "  pass", "else:", "  pass", ""
            ) as net.starlark.java.syntax.IfStatement
        var ifB: net.starlark.java.syntax.IfStatement? =
            com.google.common.collect.Iterables.getOnlyElement<net.starlark.java.syntax.Statement?>(ifA.getElseBlock()) as net.starlark.java.syntax.IfStatement?
        Truth.assertThat(ifB.isElif()).isTrue()

        ifA =
            parseStatement(
                "if a:",  //
                "  pass",
                "else:",
                "  if b:",
                "    pass",
                "  else:",
                "    pass",
                ""
            ) as net.starlark.java.syntax.IfStatement
        ifB =
            com.google.common.collect.Iterables.getOnlyElement<net.starlark.java.syntax.Statement?>(ifA.getElseBlock()) as net.starlark.java.syntax.IfStatement?
        Truth.assertThat(ifB.isElif()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIfStatementPosition() {
        assertStatementLocationCorrect("if True:\n  pass")
        assertStatementLocationCorrect("if True:\n  pass\nelif True:\n  pass")
        assertStatementLocationCorrect("if True:\n  pass\nelse:\n  pass")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForStatementPosition() {
        assertStatementLocationCorrect("for x in []:\n  pass")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefStatementPosition() {
        assertStatementLocationCorrect("def foo():\n  pass")
    }

    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    private fun assertStatementLocationCorrect(stmtStr: String) {
        var stmt: net.starlark.java.syntax.Statement = parseStatement(stmtStr)
        Truth.assertThat(getText(stmtStr, stmt)).isEqualTo(stmtStr)
        // Also try it with another token at the end (newline), which broke the location in the past.
        stmt = parseStatement(stmtStr + "\n")
        Truth.assertThat(getText(stmtStr, stmt)).isEqualTo(stmtStr)
    }

    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    private fun assertExpressionLocationCorrect(exprStr: String) {
        var expr: net.starlark.java.syntax.Expression = parseExpression(exprStr)
        Truth.assertThat(getText(exprStr, expr)).isEqualTo(exprStr)
        // Also try it with another token at the end (newline), which broke the location in the past.
        expr = parseExpression(exprStr + "\n")
        Truth.assertThat(getText(exprStr, expr)).isEqualTo(exprStr)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForBreakContinuePass() {
        val file: MutableList<net.starlark.java.syntax.Statement> =
            parseStatements(
                "def foo():",  //
                "  for i in [1, 2]:",
                "    break",
                "    continue",
                "    pass",
                "    break"
            )
        Truth.assertThat(file).hasSize(1)
        val body: MutableList<net.starlark.java.syntax.Statement>? =
            (file.get(0) as net.starlark.java.syntax.DefStatement).getBody()
        Truth.assertThat(body).hasSize(1)

        val loop: MutableList<net.starlark.java.syntax.Statement>? =
            (body!!.get(0) as net.starlark.java.syntax.ForStatement).getBody()
        Truth.assertThat(loop).hasSize(4)

        Truth.assertThat<net.starlark.java.syntax.TokenKind?>((loop!!.get(0) as net.starlark.java.syntax.FlowStatement).getFlowKind())
            .isEqualTo(net.starlark.java.syntax.TokenKind.BREAK)
        assertLocation(34, 39, loop.get(0))

        Truth.assertThat<net.starlark.java.syntax.TokenKind?>((loop.get(1) as net.starlark.java.syntax.FlowStatement).getFlowKind())
            .isEqualTo(net.starlark.java.syntax.TokenKind.CONTINUE)
        assertLocation(44, 52, loop.get(1))

        Truth.assertThat<net.starlark.java.syntax.TokenKind?>((loop.get(2) as net.starlark.java.syntax.FlowStatement).getFlowKind())
            .isEqualTo(net.starlark.java.syntax.TokenKind.PASS)
        assertLocation(57, 61, loop.get(2))

        Truth.assertThat<net.starlark.java.syntax.TokenKind?>((loop.get(3) as net.starlark.java.syntax.FlowStatement).getFlowKind())
            .isEqualTo(net.starlark.java.syntax.TokenKind.BREAK)
        assertLocation(66, 71, loop.get(3))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListExpressions1() {
        val list: net.starlark.java.syntax.ListExpression =
            parseExpression("[0,1,2]") as net.starlark.java.syntax.ListExpression
        Truth.assertThat(list.isTuple()).isFalse()
        Truth.assertThat(list.getElements()).hasSize(3)
        Truth.assertThat(list.isTuple()).isFalse()
        for (i in 0..2) {
            Truth.assertThat(getIntElem(list, i)).isEqualTo(i)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTupleLiterals2() {
        val tuple: net.starlark.java.syntax.ListExpression =
            parseExpression("(0,1,2)") as net.starlark.java.syntax.ListExpression
        Truth.assertThat(tuple.isTuple()).isTrue()
        Truth.assertThat(tuple.getElements()).hasSize(3)
        Truth.assertThat(tuple.isTuple()).isTrue()
        for (i in 0..2) {
            Truth.assertThat(getIntElem(tuple, i)).isEqualTo(i)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTupleWithoutParens() {
        val tuple: net.starlark.java.syntax.ListExpression =
            parseExpression("0, 1, 2") as net.starlark.java.syntax.ListExpression
        Truth.assertThat(tuple.isTuple()).isTrue()
        Truth.assertThat(tuple.getElements()).hasSize(3)
        Truth.assertThat(tuple.isTuple()).isTrue()
        for (i in 0..2) {
            Truth.assertThat(getIntElem(tuple, i)).isEqualTo(i)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTupleWithTrailingComma() {
        // Unlike Python, we require parens here.
        Truth.assertThat(parseExpressionError("0, 1, 2, 3,")).contains("Trailing comma")
        Truth.assertThat(parseExpressionError("1 + 2,")).contains("Trailing comma")

        val tuple: net.starlark.java.syntax.ListExpression =
            parseExpression("(0, 1, 2, 3,)") as net.starlark.java.syntax.ListExpression
        Truth.assertThat(tuple.isTuple()).isTrue()
        Truth.assertThat(tuple.getElements()).hasSize(4)
        Truth.assertThat(tuple.isTuple()).isTrue()
        for (i in 0..3) {
            Truth.assertThat(getIntElem(tuple, i)).isEqualTo(i)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTupleLiterals3() {
        val emptyTuple: net.starlark.java.syntax.ListExpression =
            parseExpression("()") as net.starlark.java.syntax.ListExpression
        Truth.assertThat(emptyTuple.isTuple()).isTrue()
        Truth.assertThat(emptyTuple.getElements()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTupleLiterals4() {
        val singletonTuple: net.starlark.java.syntax.ListExpression =
            parseExpression("(42,)") as net.starlark.java.syntax.ListExpression
        Truth.assertThat(singletonTuple.isTuple()).isTrue()
        Truth.assertThat(singletonTuple.getElements()).hasSize(1)
        Truth.assertThat(getIntElem(singletonTuple, 0)).isEqualTo(42)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTupleLiterals5() {
        val intLit: net.starlark.java.syntax.IntLiteral =
            parseExpression("(42)") as net.starlark.java.syntax.IntLiteral // not a tuple!
        Truth.assertThat(intLit.getValue() as Int).isEqualTo(42)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListExpressions6() {
        val emptyList: net.starlark.java.syntax.ListExpression =
            parseExpression("[]") as net.starlark.java.syntax.ListExpression
        Truth.assertThat(emptyList.isTuple()).isFalse()
        Truth.assertThat(emptyList.getElements()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListExpressions7() {
        val singletonList: net.starlark.java.syntax.ListExpression =
            parseExpression("[42,]") as net.starlark.java.syntax.ListExpression
        Truth.assertThat(singletonList.isTuple()).isFalse()
        Truth.assertThat(singletonList.getElements()).hasSize(1)
        Truth.assertThat(getIntElem(singletonList, 0)).isEqualTo(42)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListExpressions8() {
        val singletonList: net.starlark.java.syntax.ListExpression =
            parseExpression("[42]") as net.starlark.java.syntax.ListExpression // a singleton
        Truth.assertThat(singletonList.isTuple()).isFalse()
        Truth.assertThat(singletonList.getElements()).hasSize(1)
        Truth.assertThat(getIntElem(singletonList, 0)).isEqualTo(42)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDictExpressions() {
        val dictionaryList: net.starlark.java.syntax.DictExpression =
            parseExpression("{1:42}") as net.starlark.java.syntax.DictExpression // a singleton dictionary
        Truth.assertThat(dictionaryList.getEntries()).hasSize(1)
        val tuple: net.starlark.java.syntax.DictExpression.Entry = Companion.getElem(dictionaryList, 0)
        Truth.assertThat(getIntElem(tuple, true)).isEqualTo(1)
        Truth.assertThat(getIntElem(tuple, false)).isEqualTo(42)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDictExpressions1() {
        val dictionaryList: net.starlark.java.syntax.DictExpression =
            parseExpression("{}") as net.starlark.java.syntax.DictExpression // an empty dictionary
        Truth.assertThat(dictionaryList.getEntries()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDictExpressions2() {
        val dictionaryList: net.starlark.java.syntax.DictExpression =
            parseExpression("{1:42,}") as net.starlark.java.syntax.DictExpression // a singleton dictionary
        Truth.assertThat(dictionaryList.getEntries()).hasSize(1)
        val tuple: net.starlark.java.syntax.DictExpression.Entry = Companion.getElem(dictionaryList, 0)
        Truth.assertThat(getIntElem(tuple, true)).isEqualTo(1)
        Truth.assertThat(getIntElem(tuple, false)).isEqualTo(42)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDictExpressions3() {
        val dictionaryList: net.starlark.java.syntax.DictExpression =
            parseExpression("{1:42,2:43,3:44}") as net.starlark.java.syntax.DictExpression
        Truth.assertThat(dictionaryList.getEntries()).hasSize(3)
        for (i in 0..2) {
            val tuple: net.starlark.java.syntax.DictExpression.Entry = Companion.getElem(dictionaryList, i)
            Truth.assertThat(getIntElem(tuple, true)).isEqualTo(i + 1)
            Truth.assertThat(getIntElem(tuple, false)).isEqualTo(i + 42)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListExpressions9() {
        val singletonList: net.starlark.java.syntax.ListExpression =
            parseExpression("[ abi + opt_level + \'/include\' ]") as net.starlark.java.syntax.ListExpression
        Truth.assertThat(singletonList.isTuple()).isFalse()
        Truth.assertThat(singletonList.getElements()).hasSize(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListComprehensionSyntax() {
        Truth.assertThat(parseExpressionError("[x for")).contains("syntax error at 'newline'")
        Truth.assertThat(parseExpressionError("[x for x")).contains("syntax error at 'newline'")
        Truth.assertThat(parseExpressionError("[x for x in")).contains("syntax error at 'newline'")
        Truth.assertThat(parseExpressionError("[x for x in []")).contains("syntax error at 'newline'")
        Truth.assertThat(parseExpressionError("[x for x for y in ['a']]")).contains("syntax error at 'for'")
        Truth.assertThat(parseExpressionError("[x for x for y in 1, 2]")).contains("syntax error at 'for'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListComprehensionEmptyList() {
        val clauses: MutableList<net.starlark.java.syntax.Comprehension.Clause>? =
            (parseExpression("['foo/%s.java' % x for x in []]") as net.starlark.java.syntax.Comprehension).getClauses()
        Truth.assertThat(clauses).hasSize(1)
        val for0: net.starlark.java.syntax.Comprehension.For =
            clauses!!.get(0) as net.starlark.java.syntax.Comprehension.For
        Truth.assertThat(for0.getIterable().toString()).isEqualTo("[]")
        Truth.assertThat(for0.getVars().toString()).isEqualTo("x")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListComprehension() {
        val clauses: MutableList<net.starlark.java.syntax.Comprehension.Clause>? =
            (parseExpression("['foo/%s.java' % x for x in ['bar', 'wiz', 'quux']]") as net.starlark.java.syntax.Comprehension)
                .getClauses()
        Truth.assertThat(clauses).hasSize(1)
        val for0: net.starlark.java.syntax.Comprehension.For =
            clauses!!.get(0) as net.starlark.java.syntax.Comprehension.For
        Truth.assertThat(for0.getVars().toString()).isEqualTo("x")
        Truth.assertThat(for0.getIterable()).isInstanceOf(net.starlark.java.syntax.ListExpression::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForForListComprehension() {
        val clauses: MutableList<net.starlark.java.syntax.Comprehension.Clause>? =
            (parseExpression("['%s/%s.java' % (x, y) for x in ['foo', 'bar'] for y in list]") as net.starlark.java.syntax.Comprehension)
                .getClauses()
        Truth.assertThat(clauses).hasSize(2)
        val for0: net.starlark.java.syntax.Comprehension.For =
            clauses!!.get(0) as net.starlark.java.syntax.Comprehension.For
        Truth.assertThat(for0.getVars().toString()).isEqualTo("x")
        Truth.assertThat(for0.getIterable()).isInstanceOf(net.starlark.java.syntax.ListExpression::class.java)
        val for1: net.starlark.java.syntax.Comprehension.For =
            clauses.get(1) as net.starlark.java.syntax.Comprehension.For
        Truth.assertThat(for1.getVars().toString()).isEqualTo("y")
        Truth.assertThat(for1.getIterable()).isInstanceOf(net.starlark.java.syntax.Identifier::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParserRecovery() {
        setFailFast(false)
        val statements: MutableList<net.starlark.java.syntax.Statement> =
            parseStatements(
                "def foo():",
                "  a = 2 for 4",  // parse error
                "  b = [3, 4]",
                "",
                "d = 4 ada",  // parse error
                "",
                "def bar():",
                "  a = [3, 4]",
                "  b = 2 * * 5",  // parse error
                ""
            )

        assertContainsError("syntax error at 'for': expected newline")
        assertContainsError("syntax error at 'ada': expected newline")
        assertContainsError("syntax error at '*': expected expression")
        Truth.assertThat(events).hasSize(3)
        Truth.assertThat(statements).hasSize(3)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParserContainsErrors() {
        setFailFast(false)
        parseFile("*")
        assertContainsError("syntax error at '*'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSemicolonAndNewline() {
        val stmts: MutableList<net.starlark.java.syntax.Statement> =
            parseStatements(
                "foo='bar'; foo(bar)",  //
                "",
                "foo='bar'; foo(bar)"
            )
        Truth.assertThat(stmts).hasSize(4)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSemicolonAndNewline2() {
        setFailFast(false)
        val stmts: MutableList<net.starlark.java.syntax.Statement> = parseStatements("foo='foo' error(bar)", "", "")
        assertContainsError("syntax error at 'error'")
        Truth.assertThat(stmts).hasSize(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExprAsStatement() {
        val stmts: MutableList<net.starlark.java.syntax.Statement> =
            parseStatements(
                "li = []",  //
                "li.append('a.c')",
                "\"\"\" string comment \"\"\"",
                "foo(bar)"
            )
        Truth.assertThat(stmts).hasSize(4)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParseBuildFileWithSingleRule() {
        val stmts: MutableList<net.starlark.java.syntax.Statement> =
            parseStatements(
                "genrule(name = 'foo',",  //
                "   srcs = ['input.csv'],",
                "   outs = [ 'result.txt',",
                "           'result.log'],",
                "   cmd = 'touch result.txt result.log')",
                ""
            )
        Truth.assertThat(stmts).hasSize(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParseBuildFileWithMultipleRules() {
        val stmts: MutableList<net.starlark.java.syntax.Statement> =
            parseStatements(
                "genrule(name = 'foo',",  //
                "   srcs = ['input.csv'],",
                "   outs = [ 'result.txt',",
                "           'result.log'],",
                "   cmd = 'touch result.txt result.log')",
                "",
                "genrule(name = 'bar',",
                "   srcs = ['input.csv'],",
                "   outs = [ 'graph.svg'],",
                "   cmd = 'touch graph.svg')"
            )
        Truth.assertThat(stmts).hasSize(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParseBuildFileWithComments() {
        val result: net.starlark.java.syntax.StarlarkFile =
            parseFile(
                "# Test BUILD file",  //
                "# with multi-line comment",
                "",
                "genrule(name = 'foo',",
                "   srcs = ['input.csv'],",
                "   outs = [ 'result.txt',",
                "           'result.log'],",
                "   cmd = 'touch result.txt result.log')"
            )
        Truth.assertThat(result.getStatements()).hasSize(1)
        Truth.assertThat(result.getComments()).hasSize(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParseBuildFileWithManyComments() {
        val result: net.starlark.java.syntax.StarlarkFile =
            parseFile(
                "# 1",  //
                "# 2",
                "",
                "# 4 ",
                "# 5",
                "#",  // 6 - find empty comment for syntax highlighting
                "# 7 ",
                "# 8",
                "genrule(name = 'foo',",
                "   srcs = ['input.csv'],",
                "   # 11",
                "   outs = [ 'result.txt',",
                "           'result.log'], # 13",
                "   cmd = 'touch result.txt result.log')",
                "# 15"
            )
        Truth.assertThat(result.getStatements()).hasSize(1) // Single genrule
        val commentLines: java.lang.StringBuilder = java.lang.StringBuilder()
        for (comment in result.getComments()) {
            // Comments start and end on the same line
            val start: net.starlark.java.syntax.Location = comment.getStartLocation()
            val end: net.starlark.java.syntax.Location = comment.getEndLocation()
            Truth.assertWithMessage("%s ends on %s", start.line(), end.line())
                .that(end.line())
                .isEqualTo(start.line())
            commentLines.append('(')
            commentLines.append(start.line())
            commentLines.append(',')
            commentLines.append(start.column())
            commentLines.append(") ")
        }
        Truth.assertWithMessage("Found: %s", commentLines)
            .that(result.getComments().size)
            .isEqualTo(10) // One per '#'
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCommentNodes_doNotContainTerminatingNewline() {
        val source: String =
            """
        # Ordinary comment 1
        a = 1  #: Trailing doc comment 2
        #: Doc comment 3
        #: Doc comment continued 4
        b = 2  # Ordinary trailing comment 5
        # Comment ending with EOF 6
        """.trimIndent()
        val result: net.starlark.java.syntax.StarlarkFile = parseFile(source)
        Truth.assertThat(result.getComments()).hasSize(6)
        val lastComment: net.starlark.java.syntax.Comment = result.getComments().getLast()
        for (comment in result.getComments()) {
            assertThat(comment.text).doesNotContain("\n")
            if (comment != lastComment) {
                Truth.assertThat<Char?>(source.get(comment.getEndOffset())).isEqualTo('\n')
            }
        }
        Truth.assertThat(lastComment.getEndOffset()).isEqualTo(source.length)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testComments_inMultilineExpressionWithSyntaxError() {
        setFailFast(false)
        val result: net.starlark.java.syntax.StarlarkFile =
            parseFile(
                """
            x = (1 + *  # Ordinary comment
            # Ordinary comment
            2)

            y = (1 + *  #: Doc comment
            #: Doc comment
            2)
            
            """.trimIndent()
            )
        Truth.assertThat(result.getComments()).hasSize(4)
        assertContainsError(":1:10: syntax error at '*': expected expression")
        assertContainsError(":5:10: syntax error at '*': expected expression")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDocComments_indentationDoesNotMatter() {
        val result: net.starlark.java.syntax.StarlarkFile =
            parseFile(
                """
            #: Doc comment for a
                #: indent doesn't matter
            a = 1
            
            """.trimIndent()
            )
        Truth.assertThat(result.getStatements()).hasSize(1)
        Truth.assertThat(result.getComments()).hasSize(2)
        Truth.assertThat(getDocComment(result.statements.get(0)))
            .isEqualTo("Doc comment for a\nindent doesn't matter")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDocComments_complexLhs() {
        val result: net.starlark.java.syntax.StarlarkFile =
            parseFile(
                """
            #: Doc comment for a and b
            a, b = [1, 2]
            #: Doc comment for c and d
            c, d = [3, 4]
            #: Doc comment for e and f
            (       #: ignored
                e,  #: ignored
                f,  #: ignored
            ) = [5, 6]
            
            """.trimIndent()
            )
        Truth.assertThat(result.getStatements()).hasSize(3)
        Truth.assertThat(result.getComments()).hasSize(6)
        Truth.assertThat(getDocComment(result.statements.get(0))).isEqualTo("Doc comment for a and b")
        Truth.assertThat(getDocComment(result.statements.get(1))).isEqualTo("Doc comment for c and d")
        Truth.assertThat(getDocComment(result.statements.get(2))).isEqualTo("Doc comment for e and f")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDocComments_terminatedByBlankLineOrNonDocCommentLine() {
        val result: net.starlark.java.syntax.StarlarkFile =
            parseFile(
                """
            #: Ignored - separated by newline from assignment statement

            a = 1
            #: Ignored - separated by non-doc comment line from assignment statement
            #
            b = 1
            #: Ignored

            #: Doc comment for c
            c = 2
            #: Ignored
            #
            #: Doc comment for d
            d = 2
            
            """.trimIndent()
            )
        Truth.assertThat(result.getStatements()).hasSize(4)
        Truth.assertThat(result.getComments()).hasSize(8)
        Truth.assertThat(getDocComment(result.statements.get(0))).isNull()
        Truth.assertThat(getDocComment(result.statements.get(1))).isNull()
        Truth.assertThat(getDocComment(result.statements.get(2))).isEqualTo("Doc comment for c")
        Truth.assertThat(getDocComment(result.statements.get(3))).isEqualTo("Doc comment for d")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDocComments_trailing() {
        val result: net.starlark.java.syntax.StarlarkFile =
            parseFile(
                """
            a = 1 #: Doc comment for a
                  #: Ignored; trailing comments are one-line only

            #: Ignored; trailing comments override leading comments
            b = 2 #: Doc comment for b
            
            """.trimIndent()
            )
        Truth.assertThat(result.getStatements()).hasSize(2)
        Truth.assertThat(result.getComments()).hasSize(4)
        Truth.assertThat(getDocComment(result.statements.get(0))).isEqualTo("Doc comment for a")
        Truth.assertThat(getDocComment(result.statements.get(1))).isEqualTo("Doc comment for b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDocComments_trailing_multistatementLine() {
        val result: net.starlark.java.syntax.StarlarkFile =
            parseFile(
                """
            a = 1; b = 2; c = 3 #: Doc comment for c only

            d = 4; #: Ignored - no statement after the `;`
            
            """.trimIndent()
            )
        Truth.assertThat(result.getStatements()).hasSize(4)
        Truth.assertThat(result.getComments()).hasSize(2)
        Truth.assertThat(getDocComment(result.statements.get(0))).isNull()
        Truth.assertThat(getDocComment(result.statements.get(1))).isNull()
        Truth.assertThat(getDocComment(result.statements.get(2))).isEqualTo("Doc comment for c only")
        Truth.assertThat(getDocComment(result.statements.get(3))).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDocComments_trailing_multilineStatement() {
        val result: net.starlark.java.syntax.StarlarkFile =
            parseFile(
                """
            ( #: Ignored
                a, #: Ignored
                b  #: Ignored
            ) = foo(  #: Ignored
                #: Ignored
                x = 42  #: Ignored
            )[  #: Ignored
                1:2  #: Ignored
            ] #: Doc comment for a
            
            """.trimIndent()
            )
        Truth.assertThat(result.getStatements()).hasSize(1)
        Truth.assertThat(result.getComments()).hasSize(9)
        Truth.assertThat(getDocComment(result.statements.get(0))).isEqualTo("Doc comment for a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDocComments_leadingSpacesNormalized() {
        val result: net.starlark.java.syntax.StarlarkFile =
            parseFile(
                """
            #:zero or
            #: one leading spaces
            #:  get stripped from each doc comment line
            a = 1
            
            """.trimIndent()
            )
        Truth.assertThat(result.getStatements()).hasSize(1)
        Truth.assertThat(result.getComments()).hasSize(3)
        Truth.assertThat(getDocComment(result.statements.get(0)))
            .isEqualTo(
                ("zero or\n" //
                        + "one leading spaces\n" //
                        + " get stripped from each doc comment line")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDocComments_inSuite() {
        val result: net.starlark.java.syntax.StarlarkFile =
            parseFile(
                """
            def foo(): #: ignored
            #: Doc comment for a
                #: indent doesn't matter
                a = 1
                    #: Doc comment for b ignores indentation
                b = 2
                #: Applies to next statement
            #: indent doesn't matter
            x = 3
            
            """.trimIndent()
            )
        Truth.assertThat(result.getStatements()).hasSize(2)
        Truth.assertThat(result.getComments()).hasSize(6)
        val body: com.google.common.collect.ImmutableList<net.starlark.java.syntax.Statement>? =
            (result.statements.getFirst() as net.starlark.java.syntax.DefStatement).getBody()
        Truth.assertThat(body).hasSize(2)
        Truth.assertThat(getDocComment(body.get(0))).isEqualTo("Doc comment for a\nindent doesn't matter")
        Truth.assertThat(getDocComment(body.get(1))).isEqualTo("Doc comment for b ignores indentation")
        Truth.assertThat(getDocComment(result.statements.get(1)))
            .isEqualTo("Applies to next statement\nindent doesn't matter")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDocComments_allowedInNonAssignments() {
        val result: net.starlark.java.syntax.StarlarkFile =
            parseFile(
                """
            #: ignored
            def foo( #: ignored
                #: ignored
                x, #: ignored
                #: ignored
                **kwags): #: ignored
                #: ignored
                return x #: ignored
                #: ignored
            #: ignored
            foo( #: ignored
                x = [ #: ignored
                    #: ignored
                    1, #: ignored
                    #: ignored
                    2, #: ignored
                    #: ignored
                ], #: ignored
                #: ignored
                y = { #: ignored
                    "z": 3, #: ignored
                    #: ignored
                }, #: ignored
                #: ignored
            ) #: ignored
            
            """.trimIndent()
            )
        Truth.assertThat(result.getComments()).hasSize(25)
        Truth.assertThat(getDocComment(result.statements.get(0))).isNull()
        Truth.assertThat(getDocComment(result.statements.get(1))).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDocComments_notParsedInsideStrings() {
        val result: net.starlark.java.syntax.StarlarkFile =
            parseFile(
                """
            "#: not parsed as doc comment"
            a = 1
            ${'"'}${'"'}${'"'}
            #: not parsed as doc comment
            ${'"'}${'"'}${'"'}
            b = 2
            '''
            #: not parsed as doc comment
            '''
            c = 3
            r'''
            #: not parsed as doc comment
            '''
            d = 4
            r${'"'}${'"'}${'"'}
            #: not parsed as doc comment
            ${'"'}${'"'}${'"'}
            e = 5
            
            """.trimIndent()
            )
        Truth.assertThat(result.getComments()).isEmpty()
        for (stmt in result.getStatements()) {
            Truth.assertThat(getDocComment(stmt)).isNull()
        }
    }

    private fun getDocComment(stmt: net.starlark.java.syntax.Statement?): String? {
        if (stmt is net.starlark.java.syntax.AssignmentStatement) {
            val docComments: net.starlark.java.syntax.DocComments? = stmt.getDocComments()
            if (docComments != null) {
                return docComments.getText()
            }
        }
        return null
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMissingComma() {
        setFailFast(false)
        // Regression test.
        // Note: missing comma after name='foo'
        parseFile(
            "genrule(name = 'foo'\n"
                    + "      srcs = ['in'])"
        )
        assertContainsError("syntax error at 'srcs'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoubleSemicolon() {
        setFailFast(false)
        // Regression test.
        parseFile("x = 1; ; x = 2;")
        assertContainsError("syntax error at ';'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefSingleLine() {
        val statements: MutableList<net.starlark.java.syntax.Statement> = parseStatements("def foo(): x = 1; y = 2\n")
        val stmt: net.starlark.java.syntax.DefStatement = statements.get(0) as net.starlark.java.syntax.DefStatement
        Truth.assertThat(stmt.getBody()).hasSize(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDef() {
        val stmt: net.starlark.java.syntax.DefStatement =
            parseStatement(
                "def f(a, *, b=1, *args, **kwargs):",  //
                "  pass"
            ) as net.starlark.java.syntax.DefStatement

        Truth.assertThat(stmt.getParameters()).hasSize(5)

        assertThat(stmt.parameters.get(0).getName()).isEqualTo("a")
        Truth.assertThat(stmt.getParameters().get(0))
            .isInstanceOf(net.starlark.java.syntax.Parameter.Mandatory::class.java)

        assertThat(stmt.parameters.get(1).getName()).isNull()
        Truth.assertThat(stmt.getParameters().get(1)).isInstanceOf(net.starlark.java.syntax.Parameter.Star::class.java)

        assertThat(stmt.parameters.get(2).getName()).isEqualTo("b")
        Truth.assertThat(stmt.getParameters().get(2))
            .isInstanceOf(net.starlark.java.syntax.Parameter.Optional::class.java)
        assertThat(stmt.parameters.get(2).getDefaultValue().toString()).isEqualTo("1")

        assertThat(stmt.parameters.get(3).getName()).isEqualTo("args")
        Truth.assertThat(stmt.getParameters().get(3)).isInstanceOf(net.starlark.java.syntax.Parameter.Star::class.java)

        assertThat(stmt.parameters.get(4).getName()).isEqualTo("kwargs")
        Truth.assertThat(stmt.getParameters().get(4))
            .isInstanceOf(net.starlark.java.syntax.Parameter.StarStar::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefAssignmentToStarArgs() {
        setFailFast(false)
        parseStatement("def f(*args=1): pass")
        assertContainsError("syntax error at '=': expected ,")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefAssignmentToBareStar() {
        setFailFast(false)
        parseStatement("def f(* = 1): pass")
        assertContainsError("syntax error at '=': expected ,")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefAssignmentToKwargs() {
        setFailFast(false)
        parseStatement("def f(**kwargs=1): pass")
        assertContainsError("syntax error at '=': expected ,")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTypeExpression() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        // basic examples
        Truth.assertThat(parseTypeExpression("int")).isInstanceOf(net.starlark.java.syntax.Identifier::class.java)
        Truth.assertThat(parseTypeExpression("list[str]"))
            .isInstanceOf(net.starlark.java.syntax.TypeApplication::class.java)
        Truth.assertThat(parseTypeExpression("dict[str, int]"))
            .isInstanceOf(net.starlark.java.syntax.TypeApplication::class.java)
        // type applications must have at least one argument
        Truth.assertThat(
            org.junit.Assert.assertThrows<net.starlark.java.syntax.SyntaxError.Exception?>(
                net.starlark.java.syntax.SyntaxError.Exception::class.java,
                org.junit.function.ThrowingRunnable { parseTypeExpression("tuple[]") })
        )
            .hasMessageThat()
            .contains("syntax error at ']': expected a type argument")
        // type expressions can use list literals
        Truth.assertThat(parseTypeExpression("Callable[[int, str], int]"))
            .isInstanceOf(net.starlark.java.syntax.TypeApplication::class.java)
        // type expressions can use dict literals with string keys and type expression values
        Truth.assertThat(parseTypeExpression("TypedDict[{'a': int, 'b': bool}]"))
            .isInstanceOf(net.starlark.java.syntax.TypeApplication::class.java)
        // (non-string keys, or non-type-expression values, are a parse-time error)
        Truth.assertThat(
            org.junit.Assert.assertThrows<net.starlark.java.syntax.SyntaxError.Exception?>(
                net.starlark.java.syntax.SyntaxError.Exception::class.java,
                org.junit.function.ThrowingRunnable { parseTypeExpression("TypedDict[{x: y}]") })
        )
            .hasMessageThat()
            .contains("syntax error at 'x': expected string literal")
        Truth.assertThat(
            org.junit.Assert.assertThrows<net.starlark.java.syntax.SyntaxError.Exception?>(
                net.starlark.java.syntax.SyntaxError.Exception::class.java,
                org.junit.function.ThrowingRunnable { parseTypeExpression("TypedDict[{'x': foo()}]") })
        )
            .hasMessageThat()
            .contains("syntax error at '(': expected ,")
        // type expressions can use empty tuple literals
        Truth.assertThat(parseTypeExpression("tuple[()]"))
            .isInstanceOf(net.starlark.java.syntax.TypeApplication::class.java)
        // ...but not non-empty tuples
        Truth.assertThat(
            org.junit.Assert.assertThrows<net.starlark.java.syntax.SyntaxError.Exception?>(
                net.starlark.java.syntax.SyntaxError.Exception::class.java,
                org.junit.function.ThrowingRunnable { parseTypeExpression("tuple[(int, str)]") })
        )
            .hasMessageThat()
            .contains("syntax error at 'int': expected )")
        // type expressions can use string literals
        Truth.assertThat(parseTypeExpression("Literal['abc']"))
            .isInstanceOf(net.starlark.java.syntax.TypeApplication::class.java)
        // composition
        Truth.assertThat(parseTypeExpression("list[str, dict[str, bool]]"))
            .isInstanceOf(net.starlark.java.syntax.TypeApplication::class.java)
        // type unions
        Truth.assertThat(parseTypeExpression("str | int"))
            .isInstanceOf(net.starlark.java.syntax.BinaryOperatorExpression::class.java)
        Truth.assertThat(parseTypeExpression("str | int | bool"))
            .isInstanceOf(net.starlark.java.syntax.BinaryOperatorExpression::class.java)
        // empty dict and list literals
        Truth.assertThat(parseTypeExpression("Callable[[], TypeDict[{}]]"))
            .isInstanceOf(net.starlark.java.syntax.TypeApplication::class.java)
        // trailing commas in dict and list arguments
        Truth.assertThat(parseTypeExpression("Callable[[int,],bool]"))
            .isInstanceOf(net.starlark.java.syntax.TypeApplication::class.java)
        Truth.assertThat(parseTypeExpression("TypeDict[{'foo': int, }]"))
            .isInstanceOf(net.starlark.java.syntax.TypeApplication::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIllegalTypeExpression_disallowed() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        setFailFast(false)
        parseStatement("def f(a : (lambda x: x)): pass")
        assertContainsError("syntax error at '(': expected a type")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIllegalTypeExpression_allowedWithFlag() {
        setFileOptions(
            net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).tolerateInvalidTypeExpressions(true)
                .build()
        )

        parseStatement("def f(a : (lambda x: x)): pass")
        Truth.assertThat(parseTypeExpression("lambda x: x"))
            .isInstanceOf(net.starlark.java.syntax.LambdaExpression::class.java)

        // Annotations shouldn't consume adjacent params.
        val stmt: net.starlark.java.syntax.Statement = parseStatement("def f(p1 : x, p2): pass")
        Truth.assertThat<net.starlark.java.syntax.Statement.Kind?>(stmt.kind())
            .isEqualTo(net.starlark.java.syntax.Statement.Kind.DEF)
        assertThat((stmt as net.starlark.java.syntax.DefStatement).parameters.stream().map({ p -> p.getName() }))
            .containsExactly("p1", "p2")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefWithTypeAnnotations() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        parseStatement("def f(a: int): pass")
        parseStatement("def f(a: tuple[()]): pass")
        parseStatement("def f(a: list[str]): pass")
        parseStatement("def f(a: dict[str, int]): pass")

        // test with default values
        parseStatement("def f(a: int, *, b: bool = True, c): pass")

        // test args and kwargs
        parseStatement("def f(*args: list[int]): pass")
        parseStatement("def f(**kwargs: dict[str, Any]): pass")

        // Return type
        parseStatement("def f() -> int: pass")

        // Type parameters
        parseStatement("def f[T, U](x: dict[T, U]) -> dict[U, T]: pass")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefBareStarCannotHaveTypeAnnotation() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        setFailFast(false)
        parseStatement("def f(a, *: int, b: bool): pass")
        assertContainsError("syntax error at ':': expected )")
    }

    // TODO(ilist): Python allows trailing commas in type arguments - we probably should too.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTrailingCommaNotAllowedInTypeArgumentList() {
        Truth.assertThat(parseTypeExpressionError("list[int,]"))
            .contains("syntax error at ']': expected a type argument")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefWithDisallowedTypeAnnotations() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(false).build())
        setFailFast(false)
        parseStatement("def f(a: int): pass")
        assertContainsError("syntax error at ':': type annotations are disallowed")
        events.clear()
        parseStatement("def f[T](): pass")
        assertContainsError("syntax error at '[': type annotations are disallowed")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTypeApplicationsRequireConstructor() {
        Truth.assertThat(parseTypeExpressionError("[int]")).contains("syntax error at '[': expected a type")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionCallsNotAllowedInTypeExpressions() {
        Truth.assertThat(parseTypeExpressionError("int[f(1)]")).contains("syntax error at '(': expected ,")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOnlyPipeOperatorsAllowedInTypeExpressions() {
        val badOperators: com.google.common.collect.ImmutableList<net.starlark.java.syntax.TokenKind?> =
            com.google.common.collect.ImmutableList.of<net.starlark.java.syntax.TokenKind?>(
                net.starlark.java.syntax.TokenKind.AMPERSAND,
                net.starlark.java.syntax.TokenKind.EQUALS,
                net.starlark.java.syntax.TokenKind.GREATER,
                net.starlark.java.syntax.TokenKind.LESS,
                net.starlark.java.syntax.TokenKind.MINUS,
                net.starlark.java.syntax.TokenKind.PLUS,
                net.starlark.java.syntax.TokenKind.SLASH,
                net.starlark.java.syntax.TokenKind.STAR
            )
        for (op in badOperators) {
            Truth.assertThat(parseTypeExpressionError("int " + op + " str"))
                .contains("syntax error at '" + op + "'")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTypeAliasStatement_basicFunctionality() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        val stmt: net.starlark.java.syntax.TypeAliasStatement =
            parseStatement("type X = list") as net.starlark.java.syntax.TypeAliasStatement
        Truth.assertThat(stmt.getIdentifier().getName()).isEqualTo("X")
        Truth.assertThat(stmt.getParameters()).isEmpty()
        Truth.assertThat(stmt.getDefinition()).isInstanceOf(net.starlark.java.syntax.Identifier::class.java)
        Truth.assertThat((stmt.definition as net.starlark.java.syntax.Identifier).getName()).isEqualTo("list")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTypeAliasStatement_typeParams() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        val stmt: net.starlark.java.syntax.TypeAliasStatement =
            parseStatement("type my_nullable_dict[T, U] = dict[T, U] | None") as net.starlark.java.syntax.TypeAliasStatement
        Truth.assertThat(stmt.getIdentifier().getName()).isEqualTo("my_nullable_dict")
        assertThat(stmt.parameters.stream().map({ p -> p.name }))
            .containsExactly("T", "U")
            .inOrder()
        Truth.assertThat(stmt.getDefinition())
            .isInstanceOf(net.starlark.java.syntax.BinaryOperatorExpression::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTypeAliasStatement_requiresTypeSyntax() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(false).build())
        setFailFast(false)
        parseStatement("type X = list")
        assertContainsError("syntax error at 'type': type annotations are disallowed")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTypeAliasStatement_requiresExactlyOneName() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        setFailFast(false)
        parseStatement("type X, Y = list, int")
        assertContainsError("syntax error at ',': expected =")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTypeAliasStatement_requiresDefinition() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        setFailFast(false)
        parseStatement("type X # = define_later")
        assertContainsError("syntax error at 'newline': expected =")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTypeAliasStatement_allowsParsingWithUnresolvableDefinition() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        parseStatement("type x = no_such_type")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTypeAliasStatement_disallowsIllegalDefinition() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        setFailFast(false)
        parseStatement("type X = lambda x: x")
        assertContainsError("syntax error at 'lambda': expected a type")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTypeAliasStatement_allowsIllegalDefinition_withFlag() {
        setFileOptions(
            net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).tolerateInvalidTypeExpressions(true)
                .build()
        )
        val stmt: net.starlark.java.syntax.TypeAliasStatement =
            parseStatement("type X = lambda x: x") as net.starlark.java.syntax.TypeAliasStatement
        Truth.assertThat(stmt.getDefinition()).isInstanceOf(net.starlark.java.syntax.LambdaExpression::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTypeIsSoftKeyword() {
        // Test that `type` may be used as an identifier in any context where identifiers are allowed.
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        parseStatement("type = type.type(type)")
        parseStatement("type type = type")
    }

    private enum class TypeParamTestKind {
        DEF_STATEMENT,
        TYPE_ALIAS_STATEMENT
    }

    @Throws(java.lang.Exception::class)
    private fun parseTypeParamTestCaseStatement(
        testKind: TypeParamTestKind,
        typeParams: String?
    ): net.starlark.java.syntax.Statement? {
        return when (testKind) {
            TypeParamTestKind.DEF_STATEMENT -> parseStatement(String.format("def  f%s(): pass", typeParams))
            TypeParamTestKind.TYPE_ALIAS_STATEMENT -> parseStatement(String.format("type X%s = int", typeParams))
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTypeParams_mayBeUnused(@TestParameter testKind: TypeParamTestKind) {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        parseTypeParamTestCaseStatement(testKind, "[T, U]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTypeParams_allowOnlyIdentifiers(@TestParameter testKind: TypeParamTestKind) {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        setFailFast(false)
        parseTypeParamTestCaseStatement(testKind, "[1]")
        assertContainsError("syntax error at '1': expected identifier")
        events.clear()
        parseTypeParamTestCaseStatement(testKind, "['two']")
        assertContainsError("syntax error at '\"two\"': expected identifier")
        events.clear()
        parseTypeParamTestCaseStatement(testKind, "[(THREE)]")
        assertContainsError("syntax error at '(': expected identifier")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTypeParams_disallowDuplicates(@TestParameter testKind: TypeParamTestKind) {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        setFailFast(false)
        parseTypeParamTestCaseStatement(testKind, "[T, U, T]")
        assertContainsError("1:14: syntax error at 'T': duplicate type parameter")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTypeParams_allowsTrailingCommas(@TestParameter testKind: TypeParamTestKind) {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        parseTypeParamTestCaseStatement(testKind, "[T, U,]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTypeParams_cannotBeEmptyIfPresent(@TestParameter testKind: TypeParamTestKind) {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        setFailFast(false)
        parseTypeParamTestCaseStatement(testKind, "[]")
        assertContainsError("syntax error at ']': expected identifier")
        events.clear()
        parseTypeParamTestCaseStatement(testKind, "[,]")
        assertContainsError("syntax error at ',': expected identifier")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEllipsisNotAllowedInValueExpressions() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        Truth.assertThat(parseExpressionError("print(...)"))
            .contains("ellipsis ('...') is not allowed outside type expressions")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEllipsisAllowedInTypeExpressionArgumentsOnly() {
        setFileOptions(
            net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).tolerateInvalidTypeExpressions(false)
                .build()
        )
        parseStatement("x : tuple[int, ...]")
        Truth.assertThat(parseStatementError("x : ...")).contains("syntax error at '...': expected a type")
        Truth.assertThat(parseStatementError("x : int | ..."))
            .contains("syntax error at '...': expected identifier")
        Truth.assertThat(parseStatementError("x : tuple[int | ...]"))
            .contains("syntax error at '...': expected identifier")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCastExpression_basicFunctionality() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        val cast: net.starlark.java.syntax.CastExpression =
            parseExpression("cast(list[int], foo())") as net.starlark.java.syntax.CastExpression
        Truth.assertThat(cast.getType()).isInstanceOf(net.starlark.java.syntax.TypeApplication::class.java)
        Truth.assertThat(cast.getValue()).isInstanceOf(net.starlark.java.syntax.CallExpression::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCastExpression_isExpression() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        parseStatement("cast(list, x) += cast(list[list], (cast(struct, y)).foo())[cast(int, z)]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCast_isKeyword() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        setFailFast(false)
        Truth.assertThat(parseExpressionError("something.cast(list, x)"))
            .contains("syntax error at 'cast': expected identifier after dot")
        Truth.assertThat(parseExpressionError("(cast)(list, x)")).contains("expected (")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCastExpression_requiresTypeSyntax() {
        // If type syntax is disabled, `cast` is treated as an ordinary identifier.
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(false).build())
        Truth.assertThat(parseExpression("cast(list[str], foo())"))
            .isInstanceOf(net.starlark.java.syntax.CallExpression::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCastExpression_goodSyntax() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        parseExpression(
            """
        cast(
            int,
            n
        )
        """.trimIndent()
        )
        parseExpression("cast(int, x,)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCastExpression_badSyntax() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        setFailFast(false)
        Truth.assertThat(parseExpressionError("cast int, x")).contains("syntax error at 'int': expected (")
        Truth.assertThat(parseExpressionError("cast(int, x, y)")).contains("syntax error at 'y': expected )")
        Truth.assertThat(parseExpressionError("cast(int, x"))
            .contains("syntax error at 'newline': expected )")
        Truth.assertThat(parseExpressionError("cast(*args)"))
            .contains("syntax error at '*': expected a type")
        Truth.assertThat(parseExpressionError("cast(type=int, value=x"))
            .contains("syntax error at '=': expected ,")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIsInstanceExpression_basicFunctionality() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        val cast: net.starlark.java.syntax.IsInstanceExpression =
            parseExpression("isinstance(foo(), list | tuple)") as net.starlark.java.syntax.IsInstanceExpression
        Truth.assertThat(cast.getType()).isInstanceOf(net.starlark.java.syntax.BinaryOperatorExpression::class.java)
        Truth.assertThat(cast.getValue()).isInstanceOf(net.starlark.java.syntax.CallExpression::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIsInstanceExpression_isExpression() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        parseStatement("if isinstance(isinstance(y, list), bool): isinstance(z, str)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIsInstance_isKeyword() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        setFailFast(false)
        Truth.assertThat(parseExpressionError("something.isinstance(x, list)"))
            .contains("syntax error at 'isinstance': expected identifier after dot")
        Truth.assertThat(parseExpressionError("(isinstance)(x, list)")).contains("expected (")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIsInstanceExpression_requiresTypeSyntax() {
        // If type syntax is disabled, `isinstance` is treated as an ordinary identifier.
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(false).build())
        Truth.assertThat(parseExpression("isinstance(x, T)"))
            .isInstanceOf(net.starlark.java.syntax.CallExpression::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIsInstanceExpression_goodSyntax() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        parseExpression(
            """
        isinstance(
            x,
            T | U[V]
        )
        """.trimIndent()
        )
        parseExpression("isinstance(x, tuple,)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIsInstanceExpression_badSyntax() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        setFailFast(false)
        Truth.assertThat(parseExpressionError("isinstance x, int"))
            .contains("syntax error at 'x': expected (")
        Truth.assertThat(parseExpressionError("isinstance(x, y, int)"))
            .contains("syntax error at 'int': expected )")
        Truth.assertThat(parseExpressionError("isinstance(x, int"))
            .contains("syntax error at 'newline': expected )")
        Truth.assertThat(parseExpressionError("isinstance(*args)"))
            .contains("syntax error at '*': expected expression")
        Truth.assertThat(parseExpressionError("isinstance(value=x, type=int)"))
            .contains("syntax error at '=': expected ,")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLambda() {
        parseExpression("lambda a, b=1, *args, **kwargs: a+b")
        parseExpression("lambda *, a, *b: 0")

        // lambda has lower predecence than binary or.
        Truth.assertThat(parseExpression("lambda: x or y").toString()).isEqualTo("lambda: (x or y)")

        // This is a well known parsing ambiguity in Python.
        // Python 2.7 accepts it but Python3 and Starlark reject it.
        parseExpressionError("[x for x in lambda: True, lambda: False if x()]")

        // ok in all dialects:
        parseExpression("[x for x in (lambda: True, lambda: False) if x()]")

        // An unparenthesized tuple is not allowed as the operand
        // of an 'if' clause in a comprehension, but a lambda is ok.
        Truth.assertThat(parseExpressionError("[a for b in c if 1, 2]"))
            .contains("expected ']', 'for' or 'if'")
        parseExpression("[a for b in c if lambda: d]")
        // But the body of the unparenthesized lambda may not be a conditional:
        parseExpression("[a for b in c if (lambda: d if e else f)]")
        Truth.assertThat(parseExpressionError("[a for b in c if lambda: d if e else f]"))
            .contains("expected ']', 'for' or 'if'")

        // A lambda is not allowed as the operand of a 'for' clause.
        Truth.assertThat(parseExpressionError("[a for b in lambda: c]")).contains("syntax error at 'lambda'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForPass() {
        val statements: MutableList<net.starlark.java.syntax.Statement> = parseStatements("def foo():", "  pass\n")

        Truth.assertThat(statements).hasSize(1)
        val stmt: net.starlark.java.syntax.DefStatement = statements.get(0) as net.starlark.java.syntax.DefStatement
        Truth.assertThat(stmt.getBody().get(0)).isInstanceOf(net.starlark.java.syntax.FlowStatement::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForLoopMultipleVariables() {
        val stmts1: MutableList<net.starlark.java.syntax.Statement> =
            parseStatements("[ i for i, j, k in [(1, 2, 3)] ]\n")
        Truth.assertThat(stmts1).hasSize(1)

        val stmts2: MutableList<net.starlark.java.syntax.Statement> = parseStatements("[ i for i, j in [(1, 2, 3)] ]\n")
        Truth.assertThat(stmts2).hasSize(1)

        val stmts3: MutableList<net.starlark.java.syntax.Statement> =
            parseStatements("[ i for (i, j, k) in [(1, 2, 3)] ]\n")
        Truth.assertThat(stmts3).hasSize(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReturnNone() {
        val defNone: MutableList<net.starlark.java.syntax.Statement> = parseStatements("def foo():", "  return None\n")
        Truth.assertThat(defNone).hasSize(1)

        val bodyNone: MutableList<net.starlark.java.syntax.Statement>? =
            (defNone.get(0) as net.starlark.java.syntax.DefStatement).getBody()
        Truth.assertThat(bodyNone).hasSize(1)

        val returnNone: net.starlark.java.syntax.ReturnStatement =
            bodyNone!!.get(0) as net.starlark.java.syntax.ReturnStatement
        Truth.assertThat((returnNone.result as net.starlark.java.syntax.Identifier).getName()).isEqualTo("None")

        var i = 0
        for (end in arrayOf<String>(";", "\n")) {
            val defNoExpr: MutableList<net.starlark.java.syntax.Statement> =
                parseStatements("def bar" + i + "():", "  return" + end)
            i++
            Truth.assertThat(defNoExpr).hasSize(1)

            val bodyNoExpr: MutableList<net.starlark.java.syntax.Statement>? =
                (defNoExpr.get(0) as net.starlark.java.syntax.DefStatement).getBody()
            Truth.assertThat(bodyNoExpr).hasSize(1)

            val returnNoExpr: net.starlark.java.syntax.ReturnStatement =
                bodyNoExpr!!.get(0) as net.starlark.java.syntax.ReturnStatement
            assertThat(returnNoExpr.result).isNull()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForLoopBadSyntax() {
        setFailFast(false)
        parseFile("[1 for (a, b, c in var]\n")
        assertContainsError("syntax error")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForLoopBadSyntax2() {
        setFailFast(false)
        parseFile("[1 for in var]\n")
        assertContainsError("syntax error")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunCallBadSyntax() {
        setFailFast(false)
        parseFile("f(1,\n")
        assertContainsError("syntax error")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunCallBadSyntax2() {
        setFailFast(false)
        parseFile("f(1, 5, ,)\n")
        assertContainsError("syntax error")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadNoSymbol() {
        setFailFast(false)
        parseFile("load('//foo/bar:file.bzl')\n")
        assertContainsError("expected at least one symbol to load")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadOneSymbol() {
        val text = "load('//foo/bar:file.bzl', 'fun_test')\n"
        val statements: MutableList<net.starlark.java.syntax.Statement> = parseStatements(text)
        val stmt: net.starlark.java.syntax.LoadStatement = statements.get(0) as net.starlark.java.syntax.LoadStatement
        Truth.assertThat(stmt.getImport().getValue()).isEqualTo("//foo/bar:file.bzl")
        Truth.assertThat(stmt.getBindings()).hasSize(1)
        val sym: net.starlark.java.syntax.Identifier = stmt.getBindings().get(0).getLocalName()
        Truth.assertThat(getText(text, sym)).isEqualTo("fun_test") // apparent location within string literal
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadOneSymbolWithTrailingComma() {
        val statements: MutableList<net.starlark.java.syntax.Statement> =
            parseStatements("load('//foo/bar:file.bzl', 'fun_test',)\n")
        val stmt: net.starlark.java.syntax.LoadStatement = statements.get(0) as net.starlark.java.syntax.LoadStatement
        Truth.assertThat(stmt.getImport().getValue()).isEqualTo("//foo/bar:file.bzl")
        Truth.assertThat(stmt.getBindings()).hasSize(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadMultipleSymbols() {
        val statements: MutableList<net.starlark.java.syntax.Statement> =
            parseStatements("load(':file.bzl', 'foo', 'bar')\n")
        val stmt: net.starlark.java.syntax.LoadStatement = statements.get(0) as net.starlark.java.syntax.LoadStatement
        Truth.assertThat(stmt.getImport().getValue()).isEqualTo(":file.bzl")
        Truth.assertThat(stmt.getBindings()).hasSize(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadLabelQuoteError() {
        setFailFast(false)
        parseFile("load(non_quoted, 'a')\n")
        assertContainsError("syntax error")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadSymbolQuoteError() {
        setFailFast(false)
        parseFile("load('label', non_quoted)\n")
        assertContainsError("syntax error")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadDisallowSameLine() {
        setFailFast(false)
        parseFile("load('foo.bzl', 'foo') load('bar.bzl', 'bar')")
        assertContainsError("syntax error")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadNotAtTopLevel() {
        // "This is not a parse error." --Magritte
        parseFile("if 1: load('', 'x')\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadModuleNotStringLiteral() {
        setFailFast(false)
        parseFile("load(123, 'x')")
        assertContainsError("syntax error at '123': expected string literal")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadAlias() {
        val statements: MutableList<net.starlark.java.syntax.Statement> =
            parseStatements("load('//foo/bar:file.bzl', my_alias = 'lawl')\n")
        val stmt: net.starlark.java.syntax.LoadStatement = statements.get(0) as net.starlark.java.syntax.LoadStatement
        val actualSymbols: com.google.common.collect.ImmutableList<net.starlark.java.syntax.LoadStatement.Binding>? =
            stmt.getBindings()

        Truth.assertThat(actualSymbols).hasSize(1)
        val sym: net.starlark.java.syntax.Identifier = actualSymbols.get(0).getLocalName()
        Truth.assertThat(sym.getName()).isEqualTo("my_alias")
        val startOffset: Int = sym.getStartOffset()
        Truth.assertWithMessage("getStartOffset()").that(startOffset).isEqualTo(27)
        Truth.assertWithMessage("getEndOffset()").that(sym.getEndOffset()).isEqualTo(startOffset + 8)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadAliasMultiple() {
        runLoadAliasTestForSymbols(
            "my_alias = 'lawl', 'lol', next_alias = 'rofl'", "my_alias", "lol", "next_alias"
        )
    }

    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    private fun runLoadAliasTestForSymbols(loadSymbolString: String?, vararg expectedSymbols: String?) {
        val statements: MutableList<net.starlark.java.syntax.Statement> =
            parseStatements(String.format("load('//foo/bar:file.bzl', %s)\n", loadSymbolString))
        val stmt: net.starlark.java.syntax.LoadStatement = statements.get(0) as net.starlark.java.syntax.LoadStatement

        val actualSymbolNames: MutableList<String> = java.util.ArrayList<String>()
        for (binding in stmt.getBindings()) {
            actualSymbolNames.add(binding.localName.getName())
        }
        Truth.assertThat(actualSymbolNames).containsExactly(*expectedSymbols as Array<Any?>?)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadAliasSyntaxError() {
        setFailFast(false)
        parseFile("load('//foo:bzl', test1 = )\n")
        assertContainsError("syntax error at ')': expected string")

        parseFile("load(':foo.bzl', test2 = 1)\n")
        assertContainsError("syntax error at '1': expected string")

        parseFile("load(':foo.bzl', test3 = old)\n")
        assertContainsError("syntax error at 'old': expected string")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadIsASmallStatement() {
        // Regression test for b/148802200.
        parseFile("a=1; load('file', 'b'); c=3")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParseErrorNotComparison() {
        setFailFast(false)
        parseFile("2 < not 3")
        assertContainsError("syntax error at 'not'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNotWithArithmeticOperatorsBadSyntax() {
        setFailFast(false)
        parseFile("0 + not 0")
        assertContainsError("syntax error at 'not'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testElseWithoutIf() {
        setFailFast(false)
        parseFile(
            "def func(a):",  // no if
            "  else: return a"
        )
        assertContainsError("syntax error at 'else': expected expression")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForElse() {
        setFailFast(false)
        parseFile(
            "def func(a):",  //
            "  for i in range(a):",
            "    print(i)",
            "  else: return a"
        )
        assertContainsError("syntax error at 'else': expected expression")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTryStatementInBuild() {
        setFailFast(false)
        parseFile("try: pass")
        assertContainsError("'try' not supported, all exceptions are fatal")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testClassDefinitionInBuild() {
        setFailFast(false)
        parseFile("class test(object): pass")
        assertContainsError("keyword 'class' not supported")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testClassDefinitionInStarlark() {
        setFailFast(false)
        parseFile("class test(object): pass")
        assertContainsError("keyword 'class' not supported")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringsAreDeduped() {
        val file: net.starlark.java.syntax.StarlarkFile =
            parseFile("L1 = ['cat', 'dog', 'fish']", "L2 = ['dog', 'fish', 'cat']")
        val uniqueStringInstances: MutableSet<String> = com.google.common.collect.Sets.newIdentityHashSet<String?>()
        val collectAllStringsInStringLiteralsVisitor: net.starlark.java.syntax.NodeVisitor =
            object : net.starlark.java.syntax.NodeVisitor() {
                override fun visit(stringLiteral: net.starlark.java.syntax.StringLiteral) {
                    uniqueStringInstances.add(stringLiteral.value)
                }
            }
        collectAllStringsInStringLiteralsVisitor.visit(file)
        Truth.assertThat(uniqueStringInstances).containsExactly("cat", "dog", "fish")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConditionalExpressions() {
        Truth.assertThat(parseExpressionError("1 if 2"))
            .contains("missing else clause in conditional expression or semicolon before if")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPythonGeneratorsAreNotSupported() {
        setFailFast(false)
        parseFile("y = (expr for vars in expr)")
        assertContainsError(
            "syntax error at 'for': Starlark does not support Python-style generator expressions"
        )

        events.clear()
        parseFile("f(expr for vars in expr)")
        assertContainsError(
            "syntax error at 'for': Starlark does not support Python-style generator expressions"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParseFileStackOverflow() {
        val file: net.starlark.java.syntax.StarlarkFile =
            net.starlark.java.syntax.StarlarkFile.parse(veryDeepExpression())
        val ex: net.starlark.java.syntax.SyntaxError =
            net.starlark.java.syntax.TestUtils.assertContainsError(file.errors(), "internal error: stack overflow")
        Truth.assertThat(ex.message()).contains("parseDictEntry") // includes stack
        Truth.assertThat(ex.message()).contains("Please report the bug")
        Truth.assertThat(ex.message()).contains("include the text of foo.star") // includes file name
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParseExpressionStackOverflow() {
        val ex: net.starlark.java.syntax.SyntaxError.Exception =
            org.junit.Assert.assertThrows<net.starlark.java.syntax.SyntaxError.Exception>(
                net.starlark.java.syntax.SyntaxError.Exception::class.java,
                org.junit.function.ThrowingRunnable {
                    net.starlark.java.syntax.Expression.parse(
                        veryDeepExpression()
                    )
                })
        val err: net.starlark.java.syntax.SyntaxError =
            net.starlark.java.syntax.TestUtils.assertContainsError(ex.errors(), "internal error: stack overflow")
        Truth.assertThat(err.message()).contains("parseDictEntry") // includes stack
        Truth.assertThat(err.message())
            .contains("while parsing Starlark expression <<{{{{") // includes expression
        Truth.assertThat(err.message()).contains("Please report the bug")
    }

    companion object {
        private fun getText(text: String, node: net.starlark.java.syntax.Node): String {
            return text.substring(node.getStartOffset(), node.getEndOffset())
        }

        @Throws(java.lang.Exception::class)
        private fun assertLocation(start: Int, end: Int, node: net.starlark.java.syntax.Node) {
            val actualStart: Int = node.getStartOffset()
            val actualEnd: Int = node.getEndOffset()

            if (actualStart != start || actualEnd != end) {
                org.junit.Assert.fail(
                    ("Expected location = [" + start + ", " + end + "), found ["
                            + actualStart + ", " + actualEnd + ")")
                )
            }
        }

        // helper func for testListExpressions:
        private fun getIntElem(entry: net.starlark.java.syntax.DictExpression.Entry, key: Boolean): Number? {
            return ((if (key) entry.getKey() else entry.getValue()) as net.starlark.java.syntax.IntLiteral).getValue()
        }

        // helper func for testListExpressions:
        private fun getIntElem(list: net.starlark.java.syntax.ListExpression, index: Int): Number? {
            return (list.getElements().get(index) as net.starlark.java.syntax.IntLiteral).getValue()
        }

        // helper func for testListExpressions:
        private fun getElem(
            list: net.starlark.java.syntax.DictExpression,
            index: Int
        ): net.starlark.java.syntax.DictExpression.Entry {
            return list.getEntries().get(index)
        }

        // helper func for testListExpressions:
        private fun getElem(
            list: net.starlark.java.syntax.ListExpression,
            index: Int
        ): net.starlark.java.syntax.Expression? {
            return list.getElements().get(index)
        }

        // helper func for testing arguments:
        private fun getArg(
            f: net.starlark.java.syntax.CallExpression,
            index: Int
        ): net.starlark.java.syntax.Expression? {
            return f.getArguments().get(index).getValue()
        }

        private fun veryDeepExpression(): net.starlark.java.syntax.ParserInput? {
            val s: java.lang.StringBuilder = java.lang.StringBuilder()
            for (i in 0..4999) {
                s.append("{")
            }
            return net.starlark.java.syntax.ParserInput.fromString(s.toString(), "foo.star")
        }
    }
}
