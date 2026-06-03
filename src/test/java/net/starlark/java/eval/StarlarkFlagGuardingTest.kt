// Copyright 2018 The Bazel Authors. All rights reserved.
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

import net.starlark.java.annot.Param

/**
 * Starlark evaluation tests which verify the infrastructure which toggles build API methods and
 * parameters with semantic flags.
 */
@RunWith(JUnit4::class)
class StarlarkFlagGuardingTest {
    private var ev: EvaluationTestCase = EvaluationTestCase()

    /** Mock containing exposed methods for flag-guarding tests.  */
    @StarlarkBuiltin(name = "Mock", doc = "")
    class Mock : StarlarkValue {
        @StarlarkMethod(
            name = "positionals_only_method",
            documented = false,
            parameters = [Param(name = "a", positional = true, named = false), Param(
                name = "b",
                positional = true,
                named = false,
                enableOnlyWithFlag = EXPERIMENTAL_FLAG,
                defaultValue = "False"
            ), Param(name = "c", positional = true, named = false, defaultValue = "3")],
            useStarlarkThread = true
        )
        fun positionalsOnlyMethod(
            a: StarlarkInt?, b: Boolean, c: StarlarkInt?, thread: StarlarkThread?
        ): String {
            return "positionals_only_method(" + a + ", " + b + ", " + c + ")"
        }

        @StarlarkMethod(
            name = "keywords_only_method",
            documented = false,
            parameters = [Param(name = "a", positional = false, named = true), Param(
                name = "b",
                positional = false,
                named = true,
                enableOnlyWithFlag = EXPERIMENTAL_FLAG,
                defaultValue = "False"
            ), Param(name = "c", positional = false, named = true)],
            useStarlarkThread = true
        )
        fun keywordsOnlyMethod(
            a: StarlarkInt?, b: Boolean, c: StarlarkInt?, thread: StarlarkThread?
        ): String {
            return "keywords_only_method(" + a + ", " + b + ", " + c + ")"
        }

        @StarlarkMethod(
            name = "keywords_multiple_flags",
            documented = false,
            parameters = [Param(name = "a", positional = false, named = true), Param(
                name = "b",
                positional = false,
                named = true,
                disableWithFlag = FLAG2,
                defaultValue = "False"
            ), Param(name = "c", positional = false, named = true, enableOnlyWithFlag = FLAG1, defaultValue = "3")],
            useStarlarkThread = true
        )
        fun keywordsMultipleFlags(
            a: StarlarkInt?, b: Boolean, c: StarlarkInt?, thread: StarlarkThread?
        ): String {
            return "keywords_multiple_flags(" + a + ", " + b + ", " + c + ")"
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPositionalsOnlyGuardedMethod() {
        ev.Scenario(FLAG1_TRUE)
            .update("mock", net.starlark.java.eval.StarlarkFlagGuardingTest.Mock())
            .testEval(
                "mock.positionals_only_method(1, True, 3)", "'positionals_only_method(1, true, 3)'"
            )

        ev.Scenario(FLAG1_TRUE)
            .update("mock", net.starlark.java.eval.StarlarkFlagGuardingTest.Mock())
            .testIfErrorContains(
                "in call to positionals_only_method(), parameter 'b' got value of type 'int', want"
                        + " 'bool'",
                "mock.positionals_only_method(1, 3)"
            )

        ev.Scenario(FLAG1_FALSE)
            .update("mock", net.starlark.java.eval.StarlarkFlagGuardingTest.Mock())
            .testEval("mock.positionals_only_method(1, 3)", "'positionals_only_method(1, false, 3)'")

        ev.Scenario(FLAG1_FALSE)
            .update("mock", net.starlark.java.eval.StarlarkFlagGuardingTest.Mock())
            .testIfErrorContains(
                "in call to positionals_only_method(), parameter 'c' got value of type 'bool', want"
                        + " 'int'",
                "mock.positionals_only_method(1, True, 3)"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeywordOnlyGuardedMethod() {
        ev.Scenario(FLAG1_TRUE)
            .update("mock", net.starlark.java.eval.StarlarkFlagGuardingTest.Mock())
            .testEval(
                "mock.keywords_only_method(a=1, b=True, c=3)", "'keywords_only_method(1, true, 3)'"
            )

        ev.Scenario(FLAG1_TRUE)
            .update("mock", net.starlark.java.eval.StarlarkFlagGuardingTest.Mock())
            .testEval("mock.keywords_only_method(a=1, c=3)", "'keywords_only_method(1, false, 3)'")

        ev.Scenario(FLAG1_FALSE)
            .update("mock", net.starlark.java.eval.StarlarkFlagGuardingTest.Mock())
            .testEval("mock.keywords_only_method(a=1, c=3)", "'keywords_only_method(1, false, 3)'")

        ev.Scenario(FLAG1_FALSE)
            .update("mock", net.starlark.java.eval.StarlarkFlagGuardingTest.Mock())
            .testIfErrorContains(
                "parameter 'b' is experimental and thus unavailable with the current "
                        + "flags. It may be enabled by setting --experimental_flag",
                "mock.keywords_only_method(a=1, b=True, c=3)"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeywordsMultipleFlags() {
        val tf: StarlarkSemantics? = FLAG1_TRUE.toBuilder().setBool(FLAG2, false).build()
        ev.Scenario(tf)
            .update("mock", net.starlark.java.eval.StarlarkFlagGuardingTest.Mock())
            .testEval(
                "mock.keywords_multiple_flags(a=42, b=True, c=0)",
                "'keywords_multiple_flags(42, true, 0)'"
            )

        ev.Scenario(tf)
            .update("mock", net.starlark.java.eval.StarlarkFlagGuardingTest.Mock())
            .testEval("mock.keywords_multiple_flags(a=42)", "'keywords_multiple_flags(42, false, 3)'")

        val ft: StarlarkSemantics? = FLAG1_FALSE.toBuilder().setBool(FLAG2, true).build()
        ev.Scenario(ft)
            .update("mock", net.starlark.java.eval.StarlarkFlagGuardingTest.Mock())
            .testEval("mock.keywords_multiple_flags(a=42)", "'keywords_multiple_flags(42, false, 3)'")
            .testIfErrorContains(
                "parameter 'b' is deprecated and will be removed soon. It may be "
                        + "temporarily re-enabled by setting --incompatible_flag=false",
                "mock.keywords_multiple_flags(a=42, b=True, c=0)"
            )

        ev.Scenario(ft)
            .update("mock", net.starlark.java.eval.StarlarkFlagGuardingTest.Mock())
            .testEval("mock.keywords_multiple_flags(a=42)", "'keywords_multiple_flags(42, false, 3)'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExperimentalFlagGuardedValue() {
        // This test uses an arbitrary experimental flag to verify this functionality. If this
        // experimental flag were to go away, this test may be updated to use any experimental flag.
        // The flag itself is unimportant to the test.

        // clumsy way to predeclare

        ev =
            object : EvaluationTestCase() {
                override fun newModuleHook(predeclared: com.google.common.collect.ImmutableMap.Builder<String?, Any?>) {
                    predeclared.put(
                        "GlobalSymbol",
                        FlagGuardedValue.onlyWhenExperimentalFlagIsTrue(EXPERIMENTAL_FLAG, "foo")
                    )
                }
            }

        val errorMessage =
            ("GlobalSymbol is experimental and thus unavailable with the current "
                    + "flags. It may be enabled by setting --experimental_flag")

        ev.Scenario(FLAG1_TRUE).setUp("var = GlobalSymbol").testLookup("var", "foo")

        ev.Scenario(FLAG1_FALSE).testIfErrorContains(errorMessage, "var = GlobalSymbol")

        ev.Scenario(FLAG1_FALSE)
            .testIfErrorContains(errorMessage, "def my_function():", "  var = GlobalSymbol")

        ev.Scenario(FLAG1_FALSE)
            .setUp("GlobalSymbol = 'other'", "var = GlobalSymbol")
            .testLookup("var", "other")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIncompatibleFlagGuardedValue() {
        // This test uses an arbitrary incompatible flag to verify this functionality. If this
        // incompatible flag were to go away, this test may be updated to use any incompatible flag.
        // The flag itself is unimportant to the test.

        ev =
            object : EvaluationTestCase() {
                override fun newModuleHook(predeclared: com.google.common.collect.ImmutableMap.Builder<String?, Any?>) {
                    predeclared.put(
                        "GlobalSymbol", FlagGuardedValue.onlyWhenIncompatibleFlagIsFalse(FLAG2, "foo")
                    )
                }
            }

        val errorMessage =
            ("GlobalSymbol is deprecated and will be removed soon. It may be "
                    + "temporarily re-enabled by setting --"
                    + FLAG2.substring(1)
                    + "=false")

        ev.Scenario(FLAG2_FALSE).setUp("var = GlobalSymbol").testLookup("var", "foo")

        ev.Scenario(FLAG2_TRUE).testIfErrorContains(errorMessage, "var = GlobalSymbol")

        ev.Scenario(FLAG2_TRUE)
            .testIfErrorContains(errorMessage, "def my_function():", "  var = GlobalSymbol")

        ev.Scenario(FLAG2_TRUE)
            .setUp("GlobalSymbol = 'other'", "var = GlobalSymbol")
            .testLookup("var", "other")
    }

    companion object {
        // We define two arbitrary flags (one experimental, one incompatible) for our testing.
        private const val EXPERIMENTAL_FLAG = "-experimental_flag"
        private const val INCOMPATIBLE_FLAG = "+incompatible_flag"

        private val FLAG1 = EXPERIMENTAL_FLAG
        private val FLAG1_TRUE: StarlarkSemantics = StarlarkSemantics.builder().setBool(EXPERIMENTAL_FLAG, true).build()
        private val FLAG1_FALSE: StarlarkSemantics =
            StarlarkSemantics.builder().setBool(EXPERIMENTAL_FLAG, false).build()

        private val FLAG2 = INCOMPATIBLE_FLAG
        private val FLAG2_TRUE: StarlarkSemantics? =
            StarlarkSemantics.builder().setBool(INCOMPATIBLE_FLAG, true).build()
        private val FLAG2_FALSE: StarlarkSemantics? =
            StarlarkSemantics.builder().setBool(INCOMPATIBLE_FLAG, false).build()
    }
}
