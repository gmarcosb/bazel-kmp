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
package net.starlark.java.eval

import com.google.common.truth.Truth
import com.google.devtools.build.lib.analysis.util.ConfigurationTestCase.create
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.build.lib.packages.util.MockToolsConfig.create
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.add
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.eval.Dict
import net.starlark.java.eval.EvaluationTest
import net.starlark.java.eval.EvaluationTestCase
import net.starlark.java.eval.Mutability
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkCallable
import net.starlark.java.eval.StarlarkInt
import net.starlark.java.eval.StarlarkSemantics
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.eval.StarlarkValue
import net.starlark.java.syntax.FileOptions.Builder.allowToplevelRebinding
import net.starlark.java.syntax.FileOptions.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.loadBindsGlobally
import net.starlark.java.syntax.FileOptions.toBuilder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Test of evaluation behavior. (Implicitly uses lexer + parser.)  */
@RunWith(JUnit4::class)
class EvaluationTest {
    private val ev: EvaluationTestCase = EvaluationTestCase()

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecutionStopsAtFirstError() {
        val printEvents: MutableList<String?> = java.util.ArrayList<String?>()
        val input: net.starlark.java.syntax.ParserInput? =
            net.starlark.java.syntax.ParserInput.fromLines("print('hello'); x = 1//0; print('goodbye')")
        val interrupt = InterruptFunction()
        org.junit.Assert.assertThrows<T?>(
            EvalException::class.java,
            org.junit.function.ThrowingRunnable { execWithInterrupt(input, interrupt, printEvents) })

        // Only expect hello, should have been an error before goodbye.
        Truth.assertThat(printEvents.toString()).isEqualTo("[hello]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecutionNotStartedOnInterrupt() {
        val input: net.starlark.java.syntax.ParserInput? =
            net.starlark.java.syntax.ParserInput.fromLines("print('hello')")
        val printEvents: MutableList<String?> = java.util.ArrayList<String?>()
        java.lang.Thread.currentThread().interrupt()
        val interrupt = InterruptFunction()
        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable { execWithInterrupt(input, interrupt, printEvents) })

        // Execution didn't reach print.
        Truth.assertThat(printEvents).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForLoopAbortedOnInterrupt() {
        val input: net.starlark.java.syntax.ParserInput? =
            net.starlark.java.syntax.ParserInput.fromLines(
                "def f():",  //
                "  for i in range(100):",
                "    interrupt(i == 5)",
                "f()"
            )
        val interrupt = InterruptFunction()
        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable { execWithInterrupt(input, interrupt, java.util.ArrayList<String?>()) })

        Truth.assertThat(interrupt.callCount).isEqualTo(6)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForComprehensionAbortedOnInterrupt() {
        val input: net.starlark.java.syntax.ParserInput? =
            net.starlark.java.syntax.ParserInput.fromLines("[interrupt(i == 5) for i in range(100)]")
        val interrupt = InterruptFunction()
        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable { execWithInterrupt(input, interrupt, java.util.ArrayList<String?>()) })

        Truth.assertThat(interrupt.callCount).isEqualTo(6)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionCallsNotStartedOnInterrupt() {
        val input: net.starlark.java.syntax.ParserInput? =
            net.starlark.java.syntax.ParserInput.fromLines("interrupt(False); interrupt(True); interrupt(False);")
        val interrupt = InterruptFunction()
        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable { execWithInterrupt(input, interrupt, java.util.ArrayList<String?>()) })

        // Third call shouldn't happen.
        Truth.assertThat(interrupt.callCount).isEqualTo(2)
    }

    private class InterruptFunction : StarlarkCallable {
        private var callCount = 0

        val name: String
            get() = "interrupt"

        public override fun call(thread: StarlarkThread?, args: Tuple, kwargs: Dict<String?, Any?>?): Any {
            callCount++
            if (!args.isEmpty() && Starlark.truth(args.get(0))) {
                java.lang.Thread.currentThread().interrupt()
            }
            return Starlark.NONE
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecutionSteps() {
        val mu: Mutability? = Mutability.create("test")
        val thread: StarlarkThread = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT)
        val input: net.starlark.java.syntax.ParserInput? =
            net.starlark.java.syntax.ParserInput.fromLines("squares = [x*x for x in range(n)]")

        class C {
            @Throws(
                net.starlark.java.syntax.SyntaxError.Exception::class,
                EvalException::class,
                java.lang.InterruptedException::class
            )
            fun run(n: Int): Long {
                val module: java.lang.Module? =
                    java.lang.Module.withPredeclared(
                        StarlarkSemantics.DEFAULT,
                        com.google.common.collect.ImmutableMap.of<K?, V?>("n", StarlarkInt.of(n))
                    )
                val steps0: Long = thread.executedSteps
                Starlark.execFile(input, net.starlark.java.syntax.FileOptions.DEFAULT, module, thread)
                return thread.executedSteps - steps0
            }
        }

        // A thread records the number of computation steps.
        val steps1000 = C().run(1000)
        val steps10000 = C().run(10000)
        val ratio = steps10000.toDouble() / steps1000.toDouble()
        if (ratio < 9.9 || ratio > 10.1) {
            throw java.lang.AssertionError(
                String.format(
                    "computation steps did not increase linearly: f(1000)=%d, f(10000)=%d, ratio=%g, want"
                            + " ~10",
                    steps1000, steps10000, ratio
                )
            )
        }

        // Exceeding the limit causes cancellation.
        thread.maxExecutionSteps = 1000
        val ex: EvalException? = org.junit.Assert.assertThrows<T?>(
            EvalException::class.java,
            org.junit.function.ThrowingRunnable { C().run(1000) })
        assertThat(ex).hasMessageThat().contains("Starlark computation cancelled: too many steps")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExprs() {
        ev.Scenario()
            .testExpression("'%sx' % 'foo' + 'bar1'", "fooxbar1")
            .testExpression("('%sx' % 'foo') + 'bar2'", "fooxbar2")
            .testExpression("'%sx' % ('foo' + 'bar3')", "foobar3x")
            .testExpression("123 + 456", StarlarkInt.of(579))
            .testExpression("456 - 123", StarlarkInt.of(333))
            .testExpression("8 % 3", StarlarkInt.of(2))
            .testIfErrorContains("unsupported binary operation: int % string", "3 % 'foo'")
            .testExpression("-5", StarlarkInt.of(-5))
            .testIfErrorContains("unsupported unary operation: -string", "-'foo'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListExprs() {
        ev.Scenario()
            .testExactOrder("[1, 2, 3]", StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3))
            .testExactOrder("(1, 2, 3)", StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringFormatMultipleArgs() {
        ev.Scenario().testExpression("'%sY%s' % ('X', 'Z')", "XYZ")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConditionalExpressions() {
        ev.Scenario()
            .testExpression("1 if True else 2", StarlarkInt.of(1))
            .testExpression("1 if False else 2", StarlarkInt.of(2))
            .testExpression("1 + 2 if 3 + 4 else 5 + 6", StarlarkInt.of(3))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListComparison() {
        ev.Scenario()
            .testExpression("[] < [1]", true)
            .testExpression("[1] < [1, 1]", true)
            .testExpression("[1, 1] < [1, 2]", true)
            .testExpression("[1, 2] < [1, 2, 3]", true)
            .testExpression("[1, 2, 3] <= [1, 2, 3]", true)
            .testExpression("['a', 'b'] > ['a']", true)
            .testExpression("['a', 'b'] >= ['a']", true)
            .testExpression("['a', 'b'] < ['a']", false)
            .testExpression("['a', 'b'] <= ['a']", false)
            .testExpression("('a', 'b') > ('a', 'b')", false)
            .testExpression("('a', 'b') >= ('a', 'b')", true)
            .testExpression("('a', 'b') < ('a', 'b')", false)
            .testExpression("('a', 'b') <= ('a', 'b')", true)
            .testExpression("[[1, 1]] > [[1, 1], []]", false)
            .testExpression("[[1, 1]] < [[1, 1], []]", true)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSumFunction() {
        val sum: StarlarkCallable =
            object : StarlarkCallable() {
                val name: String
                    get() = "sum"

                @Throws(EvalException::class)
                public override fun call(
                    thread: StarlarkThread?,
                    args: Tuple,
                    kwargs: Dict<String?, Any?>?
                ): StarlarkInt? {
                    var sum: StarlarkInt? = StarlarkInt.of(0)
                    for (arg in args) {
                        sum = StarlarkInt.add(sum, arg as StarlarkInt?)
                    }
                    return sum
                }
            }

        ev.Scenario()
            .update(sum.getName(), sum)
            .testExpression("sum(1, 2, 3, 4, 5, 6)", StarlarkInt.of(21))
            .testExpression("sum", sum)
            .testExpression("sum(a=1, b=2)", StarlarkInt.of(0))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNotCallInt() {
        ev.Scenario()
            .setUp("sum = 123456")
            .testLookup("sum", StarlarkInt.of(123456))
            .testIfExactError("'int' object is not callable", "sum(1, 2, 3, 4, 5, 6)")
            .testExpression("sum", StarlarkInt.of(123456))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testComplexFunctionCall() {
        ev.Scenario()
            .setUp("functions = [min, max]", "l = [1,2]")
            .testEval("(functions[0](l), functions[1](l))", "(1, 2)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeywordArgs() {
        // This function returns the map of keyword arguments passed to it.
        val kwargs: StarlarkCallable =
            object : StarlarkCallable() {
                val name: String
                    get() = "kwargs"

                public override fun call(thread: StarlarkThread?, args: Tuple?, kwargs: Dict<String?, Any?>?): Any? {
                    return kwargs
                }
            }

        ev.Scenario()
            .update(kwargs.getName(), kwargs)
            .testEval(
                "kwargs(foo=1, bar='bar', wiz=[1,2,3]).items()",
                "[('foo', 1), ('bar', 'bar'), ('wiz', [1, 2, 3])]"
            )
            .testEval(
                "kwargs(wiz=[1,2,3], bar='bar', foo=1).items()",
                "[('wiz', [1, 2, 3]), ('bar', 'bar'), ('foo', 1)]"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testModulo() {
        ev.Scenario()
            .testExpression("6 % 2", StarlarkInt.of(0))
            .testExpression("6 % 4", StarlarkInt.of(2))
            .testExpression("3 % 6", StarlarkInt.of(3))
            .testExpression("7 % -4", StarlarkInt.of(-1))
            .testExpression("-7 % 4", StarlarkInt.of(1))
            .testExpression("-7 % -4", StarlarkInt.of(-3))
            .testIfExactError("integer modulo by zero", "5 % 0")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFloorDivision() {
        ev.Scenario()
            .testExpression("6 // 2", StarlarkInt.of(3))
            .testExpression("6 // 4", StarlarkInt.of(1))
            .testExpression("3 // 6", StarlarkInt.of(0))
            .testExpression("7 // -2", StarlarkInt.of(-4))
            .testExpression("-7 // 2", StarlarkInt.of(-4))
            .testExpression("-7 // -2", StarlarkInt.of(3))
            .testExpression("2147483647 // 2", StarlarkInt.of(1073741823))
            .testIfErrorContains("unsupported binary operation: string // int", "'str' // 2")
            .testIfExactError("integer division by zero", "5 // 0")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArithmeticDoesNotOverflow() {
        ev.Scenario()
            .testEval("2000000000 + 2000000000", "1000000000 + 1000000000 + 1000000000 + 1000000000")
            .testExpression("1234567890 * 987654321", StarlarkInt.of(1219326311126352690L))
            .testExpression(
                "1234567890 * 987654321 * 987654321",
                StarlarkInt.multiply(StarlarkInt.of(1219326311126352690L), StarlarkInt.of(987654321))
            )
            .testEval(
                "- 2000000000 - 2000000000",
                "-1000000000 - 1000000000 - 1000000000 - 1000000000"
            ) // literal 2147483648 is not allowed, so we compute it

            .setUp("minint = - 2147483647 - 1")
            .testEval("-minint", "2147483647+1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOperatorPrecedence() {
        ev.Scenario()
            .testExpression("2 + 3 * 4", StarlarkInt.of(14))
            .testExpression("2 + 3 // 4", StarlarkInt.of(2))
            .testExpression("2 * 3 + 4 // -2", StarlarkInt.of(4))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConcatStrings() {
        ev.Scenario().testExpression("'foo' + 'bar'", "foobar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConcatLists() {
        ev.Scenario()
            .testExactOrder(
                "[1,2] + [3,4]",
                StarlarkInt.of(1),
                StarlarkInt.of(2),
                StarlarkInt.of(3),
                StarlarkInt.of(4)
            )
            .testExactOrder("(1,2)", StarlarkInt.of(1), StarlarkInt.of(2))
            .testExactOrder(
                "(1,2) + (3,4)",
                StarlarkInt.of(1),
                StarlarkInt.of(2),
                StarlarkInt.of(3),
                StarlarkInt.of(4)
            )

        // TODO(fwe): cannot be handled by current testing suite
        // list
        var x: Any? = ev.eval("[1,2] + [3,4]")
        Truth.assertThat(x as Iterable<*>?)
            .containsExactly(StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3), StarlarkInt.of(4))
            .inOrder()
        Truth.assertThat(x).isInstanceOf(StarlarkList::class.java)
        assertThat(Starlark.isImmutable(x)).isFalse()

        // tuple
        x = ev.eval("(1,2) + (3,4)")
        Truth.assertThat(x as Iterable<*>?)
            .containsExactly(StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3), StarlarkInt.of(4))
            .inOrder()
        Truth.assertThat(x).isInstanceOf(Tuple::class.java)
        Truth.assertThat(x)
            .isEqualTo(
                Tuple.of(StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3), StarlarkInt.of(4))
            )
        assertThat(Starlark.isImmutable(x)).isTrue()

        ev.checkEvalError("unsupported binary operation: tuple + list", "(1,2) + [3,4]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListComprehensionDefinitionOrder() {
        ev.Scenario()
            .testIfErrorContains(
                "local variable 'y' is referenced before assignment",
                "[x for x in (1, 2) if y for y in (3, 4)]"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTupleDestructuring() {
        ev.Scenario()
            .setUp("a, b = 1, 2")
            .testLookup("a", StarlarkInt.of(1))
            .testLookup("b", StarlarkInt.of(2))
            .setUp("c, d = {'key1':2, 'key2':3}")
            .testLookup("c", "key1")
            .testLookup("d", "key2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSingleTuple() {
        ev.Scenario().setUp("(a,) = [1]").testLookup("a", StarlarkInt.of(1))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHeterogeneousDict() {
        ev.Scenario()
            .setUp("d = {'str': 1, 2: 3}", "a = d['str']", "b = d[2]")
            .testLookup("a", StarlarkInt.of(1))
            .testLookup("b", StarlarkInt.of(3))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAccessDictWithATupleKey() {
        ev.Scenario().setUp("x = {(1, 2): 3}[1, 2]").testLookup("x", StarlarkInt.of(3))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDictWithDuplicatedKey() {
        ev.Scenario()
            .testIfErrorContains(
                "dictionary expression has duplicate key: \"str\"", "{'str': 1, 'x': 2, 'str': 3}"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRecursiveTupleDestructuring() {
        ev.Scenario()
            .setUp("((a, b), (c, d)) = [(1, 2), (3, 4)]")
            .testLookup("a", StarlarkInt.of(1))
            .testLookup("b", StarlarkInt.of(2))
            .testLookup("c", StarlarkInt.of(3))
            .testLookup("d", StarlarkInt.of(4))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListComprehensionAtTopLevel() {
        // It is allowed to have a loop variable with the same name as a global variable.
        ev.Scenario()
            .update("x", StarlarkInt.of(42))
            .setUp("y = [x + 1 for x in [1,2,3]]")
            .testExactOrder("y", StarlarkInt.of(2), StarlarkInt.of(3), StarlarkInt.of(4))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDictComprehensions() {
        ev.Scenario()
            .testExpression("{a : a for a in []}", mutableMapOf<Any?, Any?>())
            .testExpression(
                "{b : b for b in [1, 2]}",
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    StarlarkInt.of(1), StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(2)
                )
            )
            .testExpression(
                "{c : 'v_' + c for c in ['a', 'b']}",
                com.google.common.collect.ImmutableMap.of<String?, String?>("a", "v_a", "b", "v_b")
            )
            .testExpression(
                "{'k_' + d : d for d in ['a', 'b']}",
                com.google.common.collect.ImmutableMap.of<String?, String?>("k_a", "a", "k_b", "b")
            )
            .testExpression(
                "{'k_' + e : 'v_' + e for e in ['a', 'b']}",
                com.google.common.collect.ImmutableMap.of<String?, String?>("k_a", "v_a", "k_b", "v_b")
            )
            .testExpression(
                "{x+y : x*y for x, y in [[2, 3]]}",
                com.google.common.collect.ImmutableMap.of<K?, V?>(StarlarkInt.of(5), StarlarkInt.of(6))
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDictComprehensionOnNonIterable() {
        ev.Scenario()
            .testIfExactErrorAtLocation("type 'int' is not iterable", 1, 17, "{k : k for k in 3}")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDictComprehension_manyClauses() {
        ev.Scenario()
            .testExpression(
                "{x : x * y for x in range(1, 10) if x % 2 == 0 for y in range(1, 10) if y == x}",
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    StarlarkInt.of(2),
                    StarlarkInt.of(4),
                    StarlarkInt.of(4),
                    StarlarkInt.of(16),
                    StarlarkInt.of(6),
                    StarlarkInt.of(36),
                    StarlarkInt.of(8),
                    StarlarkInt.of(64)
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDictComprehensions_multipleKey() {
        ev.Scenario()
            .testExpression(
                "{x : x for x in [1, 2, 1]}",
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    StarlarkInt.of(1), StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(2)
                )
            )
            .testExpression(
                "{y : y for y in ['ab', 'c', 'a' + 'b']}",
                com.google.common.collect.ImmutableMap.of<String?, String?>("ab", "ab", "c", "c")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListConcatenation() {
        ev.Scenario()
            .testEval("[1, 2] + [3, 4]", "[1, 2, 3, 4]")
            .testEval("(1, 2) + (3, 4)", "(1, 2, 3, 4)")
            .testIfExactError("unsupported binary operation: list + tuple", "[1, 2] + (3, 4)")
            .testIfExactError("unsupported binary operation: tuple + list", "(1, 2) + [3, 4]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListComprehensionFailsOnNonSequence() {
        ev.Scenario()
            .testIfExactErrorAtLocation("type 'int' is not iterable", 1, 17, "[x + 1 for x in 123]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListComprehensionOnStringIsForbidden() {
        ev.Scenario()
            .testIfExactErrorAtLocation("type 'string' is not iterable", 1, 13, "[x for x in 'abc']")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidAssignment() {
        ev.Scenario().testIfErrorContains("cannot assign to 'x + 1'", "x + 1 = 2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListComprehensionOnDictionary() {
        ev.Scenario().testExactOrder("['var_' + n for n in {'a':1,'b':2}]", "var_a", "var_b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListComprehensionOnDictionaryCompositeExpression() {
        ev.Scenario()
            .setUp("d = {1:'a',2:'b'}", "l = [d[x] for x in d]")
            .testLookup("l", StarlarkList.of(null, "a", "b"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListComprehensionUpdate() {
        ev.Scenario()
            .setUp("xs = [1, 2, 3]")
            .testIfErrorContains(
                "list value is temporarily immutable due to active for-loop iteration",
                "[xs.append(4) for x in xs]"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNestedListComprehensionUpdate() {
        ev.Scenario()
            .setUp("xs = [1, 2, 3]")
            .testIfErrorContains(
                "list value is temporarily immutable due to active for-loop iteration",
                "[xs.append(4) for x in xs for y in xs]"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListComprehensionUpdateInClause() {
        ev.Scenario()
            .setUp("xs = [1, 2, 3]")
            .testIfErrorContains(
                "list value is temporarily immutable due to active for-loop iteration",  // Use short-circuiting to produce valid output in the event
                // the exception is not raised.
                "[y for x in xs for y in (xs.append(4) or xs)]"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDictComprehensionUpdate() {
        ev.Scenario()
            .setUp("xs = {1:1, 2:2, 3:3}")
            .testIfErrorContains(
                "dict value is temporarily immutable due to active for-loop iteration",
                "[xs.popitem() for x in xs]"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListComprehensionScope() {
        // Test list comprehension creates a scope, so outer variables kept unchanged
        ev.Scenario()
            .setUp("x = 1", "l = [x * 3 for x in [2]]", "y = x")
            .testEval("y", "1")
            .testEval("l", "[6]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInOperator() {
        ev.Scenario()
            .testExpression("'b' in ['a', 'b']", java.lang.Boolean.TRUE)
            .testExpression("'c' in ['a', 'b']", java.lang.Boolean.FALSE)
            .testExpression("'b' in ('a', 'b')", java.lang.Boolean.TRUE)
            .testExpression("'c' in ('a', 'b')", java.lang.Boolean.FALSE)
            .testExpression("'b' in {'a' : 1, 'b' : 2}", java.lang.Boolean.TRUE)
            .testExpression("'c' in {'a' : 1, 'b' : 2}", java.lang.Boolean.FALSE)
            .testExpression("1 in {'a' : 1, 'b' : 2}", java.lang.Boolean.FALSE)
            .testExpression("'b' in 'abc'", java.lang.Boolean.TRUE)
            .testExpression("'d' in 'abc'", java.lang.Boolean.FALSE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNotInOperator() {
        ev.Scenario()
            .testExpression("'b' not in ['a', 'b']", java.lang.Boolean.FALSE)
            .testExpression("'c' not in ['a', 'b']", java.lang.Boolean.TRUE)
            .testExpression("'b' not in ('a', 'b')", java.lang.Boolean.FALSE)
            .testExpression("'c' not in ('a', 'b')", java.lang.Boolean.TRUE)
            .testExpression("'b' not in {'a' : 1, 'b' : 2}", java.lang.Boolean.FALSE)
            .testExpression("'c' not in {'a' : 1, 'b' : 2}", java.lang.Boolean.TRUE)
            .testExpression("1 not in {'a' : 1, 'b' : 2}", java.lang.Boolean.TRUE)
            .testExpression("'b' not in 'abc'", java.lang.Boolean.FALSE)
            .testExpression("'d' not in 'abc'", java.lang.Boolean.TRUE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInFail() {
        ev.Scenario()
            .testIfErrorContains(
                "'in <string>' requires string as left operand, not 'int'", "1 in '123'"
            )
            .testIfErrorContains("unsupported binary operation: string in int", "'a' in 1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInCompositeForPrecedence() {
        ev.Scenario().testExpression("not 'a' in ['a'] or 0", StarlarkInt.of(0))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPercentOnValueWithRepr() {
        val obj: Any =
            object : StarlarkValue() {
                public override fun repr(printer: Printer, semantics: StarlarkSemantics?) {
                    printer.append("<str marker>")
                }
            }
        ev.Scenario().update("obj", obj).testExpression("'%s' % obj", "<str marker>")
    }

    private class Dummy : StarlarkValue

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringRepresentationsOfArbitraryObjects() {
        val dummy = "<unknown object net.starlark.java.eval.EvaluationTest\$Dummy>"
        ev.Scenario()
            .update("dummy", net.starlark.java.eval.EvaluationTest.Dummy())
            .testExpression("str(dummy)", dummy)
            .testExpression("repr(dummy)", dummy)
            .testExpression("'{}'.format(dummy)", dummy)
            .testExpression("'%s' % dummy", dummy)
            .testExpression("'%r' % dummy", dummy)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPercentOnTupleOfDummyValues() {
        val obj: Any =
            object : StarlarkValue() {
                public override fun repr(printer: Printer, semantics: StarlarkSemantics?) {
                    printer.append("<str marker>")
                }
            }
        ev.Scenario()
            .update("obj", obj)
            .testExpression("'%s %s' % (obj, obj)", "<str marker> <str marker>")
        ev.Scenario()
            .update("unknown", net.starlark.java.eval.EvaluationTest.Dummy())
            .testExpression(
                "'%s %s' % (unknown, unknown)",
                "<unknown object net.starlark.java.eval.EvaluationTest\$Dummy> <unknown"
                        + " object net.starlark.java.eval.EvaluationTest\$Dummy>"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDictKeys() {
        ev.Scenario().testExactOrder("{'a': 1}.keys() + ['b', 'c']", "a", "b", "c")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDictKeysTooManyArgs() {
        ev.Scenario()
            .testIfExactError("keys() got unexpected positional argument", "{'a': 1}.keys('abc')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDictKeysTooManyKeyArgs() {
        ev.Scenario()
            .testIfExactError(
                "keys() got unexpected keyword argument 'arg'", "{'a': 1}.keys(arg='abc')"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDictKeysDuplicateKeyArgs() {
        // f(a=1, a=2) is caught statically by the resolver.
        ev.Scenario()
            .testIfExactError(
                "int() got multiple values for argument 'base'", "int('1', base=10, **dict(base=16))"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArgBothPosKey() {
        ev.Scenario()
            .testIfErrorContains(
                "int() got multiple values for argument 'base'", "int('2', 3, base=3)"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExec() {
        val input: net.starlark.java.syntax.ParserInput? =
            net.starlark.java.syntax.ParserInput.fromLines(
                "# a file in the build language",
                "",
                "x = [1, 2, 'foo', 4] + [1, 2, \"%s%d\" % ('foo', 1)]"
            )
        val module: java.lang.Module = java.lang.Module.create()
        Mutability.create("test").use { mu ->
            val thread: StarlarkThread? = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT)
            Starlark.execFile(input, net.starlark.java.syntax.FileOptions.DEFAULT, module, thread)
        }
        assertThat(module.getGlobal("x"))
            .isEqualTo(
                StarlarkList.of( /*mutability=*/
                    null,
                    StarlarkInt.of(1),
                    StarlarkInt.of(2),
                    "foo",
                    StarlarkInt.of(4),
                    StarlarkInt.of(1),
                    StarlarkInt.of(2),
                    "foo1"
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadsBindLocally() {
        val a: java.lang.Module? = java.lang.Module.create()
        Starlark.execFile(
            net.starlark.java.syntax.ParserInput.fromString("x = 1", "a.bzl"),
            net.starlark.java.syntax.FileOptions.DEFAULT,
            a,
            StarlarkThread.createTransient(Mutability.create(), StarlarkSemantics.DEFAULT)
        )

        val bThread: StarlarkThread =
            StarlarkThread.createTransient(Mutability.create(), StarlarkSemantics.DEFAULT)
        bThread.loader = { module ->
            assertThat(module).isEqualTo("a.bzl")
            a
        }
        val b: java.lang.Module? = java.lang.Module.create()
        Starlark.execFile(
            net.starlark.java.syntax.ParserInput.fromString("load('a.bzl', 'x')", "b.bzl"),
            net.starlark.java.syntax.FileOptions.DEFAULT,
            b,
            bThread
        )

        val cThread: StarlarkThread =
            StarlarkThread.createTransient(Mutability.create(), StarlarkSemantics.DEFAULT)
        cThread.loader = { module ->
            assertThat(module).isEqualTo("b.bzl")
            b
        }
        val ex: EvalException? =
            org.junit.Assert.assertThrows<T?>(
                EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    Starlark.execFile(
                        net.starlark.java.syntax.ParserInput.fromString("load('b.bzl', 'x')", "c.bzl"),
                        net.starlark.java.syntax.FileOptions.DEFAULT,
                        java.lang.Module.create(),
                        cThread
                    )
                })
        assertThat(ex).hasMessageThat().contains("file 'b.bzl' does not contain symbol 'x'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelRebinding() {
        val options: net.starlark.java.syntax.FileOptions? =
            net.starlark.java.syntax.FileOptions.DEFAULT.toBuilder()
                .allowToplevelRebinding(true)
                .loadBindsGlobally(true)
                .build()

        val m1: java.lang.Module = java.lang.Module.create()
        m1.setGlobal("x", "one")

        val input: net.starlark.java.syntax.ParserInput? =
            net.starlark.java.syntax.ParserInput.fromLines("load('m1', 'x'); x = 'two'")
        val m2: java.lang.Module = java.lang.Module.create()
        Mutability.create("test").use { mu ->
            val thread: StarlarkThread = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT)
            thread.loader = { name -> m1 }
            Starlark.execFile(input, options, m2, thread)
        }
        assertThat(m2.getGlobal("x")).isEqualTo("two")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun moduleWithDocString() {
        val module: java.lang.Module = java.lang.Module.create()
        assertThat(module.documentation).isNull()
        val input: net.starlark.java.syntax.ParserInput? =
            net.starlark.java.syntax.ParserInput.fromLines(
                "\"\"\"",
                "Module doc header",  //
                "",
                "Module doc details",
                "\"\"\"",
                "",
                "\"\"\"Not module doc\"\"\"",
                "x = \"Not module doc\"",
                "def foo():",
                "  \"\"\"Not module doc\"\"\"",
                "  pass"
            )
        Mutability.create("test").use { mu ->
            val thread: StarlarkThread? = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT)
            Starlark.execFile(input, net.starlark.java.syntax.FileOptions.DEFAULT, module, thread)
        }
        assertThat(module.documentation).isEqualTo("Module doc header\n\nModule doc details")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun moduleWithoutDocString() {
        val module: java.lang.Module = java.lang.Module.create()
        val input: net.starlark.java.syntax.ParserInput? =
            net.starlark.java.syntax.ParserInput.fromLines(
                "x = \"Not module doc\"",  //
                "\"\"\"Not module doc\"\"\"",
                "def foo():",
                "  \"\"\"Not module doc\"\"\"",
                "  pass"
            )
        Mutability.create("test").use { mu ->
            val thread: StarlarkThread? = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT)
            Starlark.execFile(input, net.starlark.java.syntax.FileOptions.DEFAULT, module, thread)
        }
        assertThat(module.documentation).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun moduleWithMultiplePrograms_usesFirstNonNullDocString() {
        val module: java.lang.Module = java.lang.Module.create()
        assertThat(module.documentation).isNull()
        val inputWithoutModuleDocstring: net.starlark.java.syntax.ParserInput? =
            net.starlark.java.syntax.ParserInput.fromLines("x = \"Not a module doc\"")
        val inputWithModuleDocstring1: net.starlark.java.syntax.ParserInput? =
            net.starlark.java.syntax.ParserInput.fromLines(
                "\"\"\"First non-null module doc\"\"\"",  //
                "y = \"foo\""
            )
        val inputWithModuleDocstring2: net.starlark.java.syntax.ParserInput? =
            net.starlark.java.syntax.ParserInput.fromLines(
                "\"\"\"Second non-null module doc\"\"\"",  //
                "z = \"bar\""
            )
        Mutability.create("test").use { mu ->
            val thread: StarlarkThread? = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT)
            Starlark.execFile(inputWithoutModuleDocstring, net.starlark.java.syntax.FileOptions.DEFAULT, module, thread)
            Starlark.execFile(inputWithModuleDocstring1, net.starlark.java.syntax.FileOptions.DEFAULT, module, thread)
            Starlark.execFile(inputWithModuleDocstring2, net.starlark.java.syntax.FileOptions.DEFAULT, module, thread)
        }
        assertThat(module.documentation).isEqualTo("First non-null module doc")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun moduleWithPresetDocstring() {
        val module: java.lang.Module = java.lang.Module.create()
        module.documentation = "preset docstring"
        assertThat(module.documentation).isEqualTo("preset docstring")
        val input: net.starlark.java.syntax.ParserInput? =
            net.starlark.java.syntax.ParserInput.fromLines(
                "\"\"\"Module doc from file\"\"\"",  //
                "x = \"foo\""
            )
        Mutability.create("test").use { mu ->
            val thread: StarlarkThread? = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT)
            Starlark.execFile(input, net.starlark.java.syntax.FileOptions.DEFAULT, module, thread)
        }
        assertThat(module.documentation).isEqualTo("preset docstring")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun typeAliasStatement_evalsAsNoop() {
        ev.setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        ev.Scenario().setUp("type X = int").testLookup("X", null)
        ev.Scenario().setUp("Y = 'foo'; type Y = bool").testLookup("Y", "foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun varStatement_evalsAsNoop() {
        ev.setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        ev.Scenario().setUp("X : int").testLookup("X", null)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun varStatement_canLeaveToplevelSymbolcUninitialized() {
        ev.setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        ev.Scenario()
            .setUp(
                """
            X : int
            def f():
                print(X)
            
            """.trimIndent()
            )
            .testIfErrorContains("global variable 'X' is referenced before assignment", "f()")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun castExpression_evalsAsIdentity() {
        // The dynamic behavior of `cast` (disregarding type checking) is to return its value unchanged.
        ev.setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        ev.Scenario()
            .setUp(
                """
            x = cast(list, [1])
            y = cast(int, "this is not an int")
            z = cast(dict[str, str], 42)
            
            """.trimIndent()
            )
            .testEval("x", "[1]")
            .testEval("y", "\"this is not an int\"")
            .testEval("z", "42")
    }

    // TODO(b/350661266): resolve types in isinstance().
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun isinstanceExpression_notYetSupported() {
        ev.setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        ev.Scenario().testIfExactError("isinstance() is not yet supported", "isinstance(x, list)")
    }

    companion object {
        // Executes input, with the specified 'interrupt' predeclared built-in, gather print events in
        // printEvents.
        @Throws(java.lang.Exception::class)
        private fun execWithInterrupt(
            input: net.starlark.java.syntax.ParserInput?,
            interrupt: InterruptFunction,
            printEvents: MutableList<String?>
        ) {
            val module: java.lang.Module? =
                java.lang.Module.withPredeclared(
                    StarlarkSemantics.DEFAULT,
                    com.google.common.collect.ImmutableMap.of<K?, V?>("interrupt", interrupt)
                )
            try {
                Mutability.create("test").use { mu ->
                    val thread: StarlarkThread = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT)
                    thread.printHandler = { _thread, msg -> printEvents.add(msg) }
                    Starlark.execFile(input, net.starlark.java.syntax.FileOptions.DEFAULT, module, thread)
                }
            } finally {
                // Reset interrupt bit in case the test failed to do so.
                java.lang.Thread.interrupted()
            }
        }
    }
}
