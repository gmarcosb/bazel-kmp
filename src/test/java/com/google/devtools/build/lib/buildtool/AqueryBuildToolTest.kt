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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.analysis.AnalysisProtosV2.ActionGraphContainer

/** Integration tests for aquery.  */
@RunWith(JUnit4::class)
class AqueryBuildToolTest : BuildIntegrationTestCase() {
    private var functions: com.google.common.collect.ImmutableMap<String?, QueryFunction?>? = null

    @Before
    fun setFunctions() {
        val builder: com.google.common.collect.ImmutableMap.Builder<String?, QueryFunction?> =
            com.google.common.collect.ImmutableMap.builder<String?, QueryFunction?>()

        for (queryFunction in ActionGraphQueryEnvironment.FUNCTIONS) {
            builder.put(queryFunction.name, queryFunction)
        }

        for (queryFunction in ActionGraphQueryEnvironment.AQUERY_FUNCTIONS) {
            builder.put(queryFunction.name, queryFunction)
        }

        functions = builder.buildOrThrow()
        runtimeWrapper.addOptionsClass(AqueryOptions::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConstructor_wrongAqueryFilterFormat_throwsError() {
        val expr: QueryExpression? =
            com.google.devtools.build.lib.query2.engine.QueryParser.parse("deps(inputs('abc', //abc))", functions)

        org.junit.Assert.assertThrows<T?>(
            AqueryActionFilterException::class.java,
            org.junit.function.ThrowingRunnable { AqueryProcessor(expr, TargetPattern.defaultParser()) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConstructor_wrongPatternSyntax_throwsError() {
        val expr: QueryExpression? =
            com.google.devtools.build.lib.query2.engine.QueryParser.parse("inputs('*abc', //abc)", functions)

        val thrown: AqueryActionFilterException? =
            org.junit.Assert.assertThrows<T?>(
                AqueryActionFilterException::class.java,
                org.junit.function.ThrowingRunnable { AqueryProcessor(expr, TargetPattern.defaultParser()) })
        assertThat(thrown).hasMessageThat().contains("Wrong query syntax:")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDmpActionGraphFromSkyframe_wrongOutputFormat_returnsFailure() {
        addOptions("--output=text")
        val env: CommandEnvironment? = runtimeWrapper.newCommand(AqueryCommand::class.java)
        val aqueryProcessor: AqueryProcessor = AqueryProcessor(null, TargetPattern.defaultParser())
        val result: BlazeCommandResult = aqueryProcessor.dumpActionGraphFromSkyframe(env)

        assertThat(result.isSuccess()).isFalse()
        assertThat(result.getDetailedExitCode().getFailureDetail().getActionQuery().getCode())
            .isEqualTo(Code.SKYFRAME_STATE_PREREQ_UNMET)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAquerySkyframeStateProtoNotCutoff() {
        // First, prepare and run the build.
        write(
            "x/BUILD",
            """
        genrule(
            name = "x",
            srcs = ["in"],
            # This has the length 10, so it will include a 0x0a / newline character
            # that triggers the cutoff.
            outs = ["1234567890"],
            cmd = "touch ${'$'}(OUTS)",
        )
        
        """.trimIndent()
        )
        write("x/in", "")
        buildTarget("//x")

        // Then, run aquery and dump the action graph as of the previous skyframe state.
        addOptions("--output=proto", "--skyframe_state")
        val env: CommandEnvironment = runtimeWrapper.newCommand(AqueryCommand::class.java)
        val stdout: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        env.getReporter()
            .addHandler(
                { event ->
                    if (event.getKind().equals(com.google.devtools.build.lib.events.EventKind.STDOUT)) {
                        try {
                            stdout.write(event.getMessageBytes())
                        } catch (e: IOException) {
                            throw java.lang.IllegalStateException(e)
                        }
                    }
                })

        val aqueryProcessor: AqueryProcessor = AqueryProcessor(null, TargetPattern.defaultParser())
        val result: BlazeCommandResult = aqueryProcessor.dumpActionGraphFromSkyframe(env)
        assertThat(result.isSuccess()).isTrue()

        // Test whether stdout is a valid proto.
        Truth.assertThat(stdout.size()).isGreaterThan(0)
        val actionGraphContainer: ActionGraphContainer =
            ActionGraphContainer.parseFrom(stdout.toByteArray(), ExtensionRegistry.getEmptyRegistry())
        assertThat(actionGraphContainer.getActionsList()).isNotEmpty()
    }
}
