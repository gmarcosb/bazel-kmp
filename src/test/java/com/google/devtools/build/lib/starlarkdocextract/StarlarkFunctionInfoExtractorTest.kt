// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.starlarkdocextract

import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.FunctionParamRole.PARAM_ROLE_KEYWORD_ONLY

@RunWith(JUnit4::class)
class StarlarkFunctionInfoExtractorTest {
    private var fakeLabelString: String? = null // set by exec()

    /**
     * Executes the given Starlark code and returns the value of the first global variable, which must
     * be a function.
     */
    @Throws(java.lang.Exception::class)
    private fun exec(vararg lines: String?): StarlarkFunction {
        val ev: BazelEvaluationTestCase = BazelEvaluationTestCase()
        val module: net.starlark.java.eval.Module? = ev.getModule()
        val fakeLabel: Label = BazelModuleContext.of(module).label()
        fakeLabelString = fakeLabel.getCanonicalForm()
        val input: net.starlark.java.syntax.ParserInput? = net.starlark.java.syntax.ParserInput.fromLines(lines)
        val file: net.starlark.java.syntax.StarlarkFile? =
            net.starlark.java.syntax.StarlarkFile.parse(input, net.starlark.java.syntax.FileOptions.DEFAULT)
        val program: net.starlark.java.syntax.Program? = net.starlark.java.syntax.Program.compileFile(file, module)
        BzlLoadFunction.execAndExport(
            program, fakeLabel, ev.getEventHandler(), module, ev.getStarlarkThread()
        )
        return ev.getModule().getGlobals().values.stream().findFirst().get() as StarlarkFunction
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun basicFunctionality() {
        val fn: StarlarkFunction =
            exec(
                """
            def fn(x):
                pass
            
            """.trimIndent()
            )
        val info: StarlarkFunctionInfo? =
            StarlarkFunctionInfoExtractor.fromNameAndFunction(
                "namespace.fn", fn, LabelRenderer.DEFAULT
            )

        assertThat(info)
            .isEqualTo(
                StarlarkFunctionInfo.newBuilder()
                    .setFunctionName("namespace.fn")
                    .addParameter(
                        FunctionParamInfo.newBuilder()
                            .setName("x")
                            .setRole(PARAM_ROLE_ORDINARY)
                            .setMandatory(true)
                            .build()
                    )
                    .setOriginKey(OriginKey.newBuilder().setName("fn").setFile(fakeLabelString).build())
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun summary_canStartOnFirstOrSecondLine() {
        val fn1: StarlarkFunction =
            exec(
                """
            def fn1(x):
                "Summary."
                pass
            
            """.trimIndent()
            )
        val fn2: StarlarkFunction =
            exec(
                """
            def fn2(x):
                '''
                Summary.
                '''
                pass
            
            """.trimIndent()
            )
        val info1: StarlarkFunctionInfo =
            StarlarkFunctionInfoExtractor.fromNameAndFunction("fn", fn1, LabelRenderer.DEFAULT)
        val info2: StarlarkFunctionInfo =
            StarlarkFunctionInfoExtractor.fromNameAndFunction("fn", fn2, LabelRenderer.DEFAULT)

        assertThat(info1.getDocString()).isEqualTo("Summary.")
        assertThat(info2.getDocString()).isEqualTo("Summary.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun summary_mustBeFollowedByBlankLine() {
        val good: StarlarkFunction =
            exec(
                """
            def good(x):
                '''
                Summary.

                Details.'''
                pass
            
            """.trimIndent()
            )
        val badNoBlankLine: StarlarkFunction =
            exec(
                """
            def bad_no_blank_line(x):
                '''
                Summary.
                Details.
                '''
                pass
            
            """.trimIndent()
            )

        assertThat(
            StarlarkFunctionInfoExtractor.fromNameAndFunction("good", good, LabelRenderer.DEFAULT)
                .getDocString()
        )
            .isEqualTo("Summary.\n\nDetails.")
        val noBlankLineException: ExtractionException? =
            org.junit.Assert.assertThrows<T?>(
                ExtractionException::class.java,
                org.junit.function.ThrowingRunnable {
                    StarlarkFunctionInfoExtractor.fromNameAndFunction(
                        "bad_no_blank_line", badNoBlankLine, LabelRenderer.DEFAULT
                    )
                })
        assertThat(noBlankLineException)
            .hasMessageThat()
            .contains("the one-line summary should be followed by a blank line")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun keywordOnly() {
        val fn: StarlarkFunction =
            exec(
                """
            def fn(a, b=1, *, c, d=2):
                '''This function does stuff.

                Args:
                  a: A value.
                  b: B value
                  c: C value.
                  d: D value.
                '''
                pass
            
            """.trimIndent()
            )
        val info: StarlarkFunctionInfo =
            StarlarkFunctionInfoExtractor.fromNameAndFunction("fn", fn, LabelRenderer.DEFAULT)
        assertThat(info.getParameterList())
            .containsExactly(
                FunctionParamInfo.newBuilder()
                    .setName("a")
                    .setRole(PARAM_ROLE_ORDINARY)
                    .setDocString("A value.")
                    .setMandatory(true)
                    .build(),
                FunctionParamInfo.newBuilder()
                    .setName("b")
                    .setRole(PARAM_ROLE_ORDINARY)
                    .setDocString("B value")
                    .setMandatory(false)
                    .setDefaultValue("1")
                    .build(),
                FunctionParamInfo.newBuilder()
                    .setName("c")
                    .setRole(PARAM_ROLE_KEYWORD_ONLY)
                    .setDocString("C value.")
                    .setMandatory(true)
                    .build(),
                FunctionParamInfo.newBuilder()
                    .setName("d")
                    .setRole(PARAM_ROLE_KEYWORD_ONLY)
                    .setDocString("D value.")
                    .setMandatory(false)
                    .setDefaultValue("2")
                    .build()
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun keywordOnly_withVarargs() {
        val fn: StarlarkFunction =
            exec(
                """
            def fn(a, b=1, *args, c, d=2):
                '''This function does stuff.

                Args:
                  a: A value.
                  b: B value
                  c: C value.
                  d: D value.
                  *args: Remaining positional arguments.
                '''
                pass
            
            """.trimIndent()
            )
        val info: StarlarkFunctionInfo =
            StarlarkFunctionInfoExtractor.fromNameAndFunction("fn", fn, LabelRenderer.DEFAULT)
        assertThat(info.getParameterList())
            .containsExactly(
                FunctionParamInfo.newBuilder()
                    .setName("a")
                    .setRole(PARAM_ROLE_ORDINARY)
                    .setDocString("A value.")
                    .setMandatory(true)
                    .build(),
                FunctionParamInfo.newBuilder()
                    .setName("b")
                    .setRole(PARAM_ROLE_ORDINARY)
                    .setDocString("B value")
                    .setMandatory(false)
                    .setDefaultValue("1")
                    .build(),
                FunctionParamInfo.newBuilder()
                    .setName("c")
                    .setRole(PARAM_ROLE_KEYWORD_ONLY)
                    .setDocString("C value.")
                    .setMandatory(true)
                    .build(),
                FunctionParamInfo.newBuilder()
                    .setName("d")
                    .setRole(PARAM_ROLE_KEYWORD_ONLY)
                    .setDocString("D value.")
                    .setMandatory(false)
                    .setDefaultValue("2")
                    .build(),
                FunctionParamInfo.newBuilder()
                    .setName("args")
                    .setRole(PARAM_ROLE_VARARGS)
                    .setDocString("Remaining positional arguments.")
                    .setMandatory(false)
                    .build()
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun keywordOnly_withVarargsAndKwargs() {
        val fn: StarlarkFunction =
            exec(
                """
            def fn(a, b=1, *args, c, d=2, **kwargs):
                '''This function does stuff.

                Args:
                  a: A value.
                  b: B value
                  c: C value.
                  d: D value.
                '''
                pass
            
            """.trimIndent()
            )
        val info: StarlarkFunctionInfo =
            StarlarkFunctionInfoExtractor.fromNameAndFunction("fn", fn, LabelRenderer.DEFAULT)
        assertThat(info.getParameterList())
            .containsExactly(
                FunctionParamInfo.newBuilder()
                    .setName("a")
                    .setRole(PARAM_ROLE_ORDINARY)
                    .setDocString("A value.")
                    .setMandatory(true)
                    .build(),
                FunctionParamInfo.newBuilder()
                    .setName("b")
                    .setRole(PARAM_ROLE_ORDINARY)
                    .setDocString("B value")
                    .setMandatory(false)
                    .setDefaultValue("1")
                    .build(),
                FunctionParamInfo.newBuilder()
                    .setName("c")
                    .setRole(PARAM_ROLE_KEYWORD_ONLY)
                    .setDocString("C value.")
                    .setMandatory(true)
                    .build(),
                FunctionParamInfo.newBuilder()
                    .setName("d")
                    .setRole(PARAM_ROLE_KEYWORD_ONLY)
                    .setDocString("D value.")
                    .setMandatory(false)
                    .setDefaultValue("2")
                    .build(),
                FunctionParamInfo.newBuilder()
                    .setName("args")
                    .setRole(PARAM_ROLE_VARARGS)
                    .setMandatory(false)
                    .build(),
                FunctionParamInfo.newBuilder()
                    .setName("kwargs")
                    .setRole(PARAM_ROLE_KWARGS)
                    .setMandatory(false)
                    .build()
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun returns() {
        val fn: StarlarkFunction =
            exec(
                """
            def fn(x):
                '''
                My function.

                Returns:
                  The value of x.
                '''
                return x
            
            """.trimIndent()
            )
        val info: StarlarkFunctionInfo =
            StarlarkFunctionInfoExtractor.fromNameAndFunction("fn", fn, LabelRenderer.DEFAULT)
        assertThat(info.getReturn())
            .isEqualTo(FunctionReturnInfo.newBuilder().setDocString("The value of x.").build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun deprecation() {
        val fn: StarlarkFunction =
            exec(
                """
            def fn(x):
                '''
                My function.

                Deprecated:
                  Do not use.
                  Use something else instead.
                '''
                pass
            
            """.trimIndent()
            )
        val info: StarlarkFunctionInfo =
            StarlarkFunctionInfoExtractor.fromNameAndFunction("fn", fn, LabelRenderer.DEFAULT)
        assertThat(info.getDeprecated())
            .isEqualTo(
                FunctionDeprecationInfo.newBuilder()
                    .setDocString("Do not use.\nUse something else instead.")
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun specialSections_canBeSeparatedByAnyNumberOfBlankLines() {
        var extraBlankLines = ""
        var i = 0
        while (i < 2) {
            val fn: StarlarkFunction =
                exec(
                    String.format(
                        """
                  def fn%d(x):
                      '''
                      My function.

                      Args:
                        x: X value.%s
                      Returns:
                        The value of x.%s
                      Deprecated:
                        Do not use.
                      '''
                      return x
                  
                  """.trimIndent(),
                        i, extraBlankLines, extraBlankLines
                    )
                )
            val info: StarlarkFunctionInfo? =
                StarlarkFunctionInfoExtractor.fromNameAndFunction("fn" + i, fn, LabelRenderer.DEFAULT)
            assertThat(info)
                .isEqualTo(
                    StarlarkFunctionInfo.newBuilder()
                        .setFunctionName("fn" + i)
                        .setDocString("My function.")
                        .addParameter(
                            FunctionParamInfo.newBuilder()
                                .setName("x")
                                .setRole(PARAM_ROLE_ORDINARY)
                                .setDocString("X value.")
                                .setMandatory(true)
                                .build()
                        )
                        .setReturn(
                            FunctionReturnInfo.newBuilder().setDocString("The value of x.").build()
                        )
                        .setDeprecated(
                            FunctionDeprecationInfo.newBuilder().setDocString("Do not use.").build()
                        )
                        .setOriginKey(
                            OriginKey.newBuilder().setName("fn" + i).setFile(fakeLabelString).build()
                        )
                        .build()
                )
            i++
            extraBlankLines += "\n"
        }
    }
}
