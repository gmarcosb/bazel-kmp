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

import com.google.devtools.build.lib.actions.BuildFailedException

/** Tests related to "missing input file" errors.  */
@RunWith(JUnit4::class)
class MissingInputActionTest : BuildIntegrationTestCase() {
    val buildInfoModule: BlazeModule
        get() = BazelWorkspaceStatusModule()

    // Regression test for bug #904676: Blaze does not consider missing inputs
    // an error.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoInput() {
        // Multiple missing inputs means error is non-deterministic in --nokeep_going case.
        this.addOptions("--keep_going")
        write(
            "dummy/BUILD",
            """
        genrule(name = 'dummy',
                srcs = ['in1', 'in2', 'in3'],
                outs = ['out1', 'out2'],
                cmd = '/bin/true')
        
        """.trimIndent()
        )
        write("dummy/in1")

        assertMissingInputOnBuild("//dummy", 2)
        events.assertDoesNotContainEvent("missing input file '" + "//" + "dummy" + ":" + "in1'")
        events.assertContainsError("missing input file '" + "//" + "dummy" + ":" + "in2'")
        events.assertContainsError("missing input file '" + "//" + "dummy" + ":" + "in3'")
    }

    // The next two tests are inherently flakily successful with respect to the workspace status
    // action: even if we don't correctly suppress the workspace status action error message, we might
    // not have started it at all because Skyframe aborted quickly. That doesn't happen in practice,
    // though: the workspace status action starts right away.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMissingInputRacesWithWorkspaceStatusAction() {
        write(
            "dummy/BUILD",
            "genrule(name = 'dummy', srcs = ['in'], outs = ['out'], cmd = '/bin/false')"
        )
        val sleepPath: Path = write("sleep.sh", "sleep 300")
        sleepPath.setExecutable(true)
        addOptions("--workspace_status_command=" + sleepPath.getPathString())
        for (i in 0..1) {
            assertMissingInputOnBuild("//dummy", 1)
            events.assertContainsError(
                "dummy/BUILD:1:8: Executing genrule //dummy:dummy failed: missing input file"
                        + " '//dummy:in'"
            )
            events.assertContainsEventWithFrequency("missing input file", 1)
            events.assertDoesNotContainEvent("Failed to determine build info")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMissingTopLevelInputRacesWithWorkspaceStatusAction() {
        // Create a rule that exports a missing source file as a top-level artifact so that the missing
        // file will be detected by the TargetCompletion function, not an ActionExecution function.
        write(
            "foo/missing.bzl",
            """
        def _missing_impl(ctx):
            return DefaultInfo(files = depset(ctx.files.srcs))

        missing = rule(
                       implementation = _missing_impl,
                       attrs = { 'srcs': attr.label_list(allow_files = True) }
        )
        
        """.trimIndent()
        )
        write(
            "foo/BUILD",
            """
        load('missing.bzl', 'missing')
        missing(name = 'foo', srcs = ['missing.sh'])
        
        """.trimIndent()
        )
        val sleepPath: Path = write("sleep.sh", "sleep 300")
        sleepPath.setExecutable(true)
        addOptions("--workspace_status_command=" + sleepPath.getPathString())
        for (i in 0..1) {
            assertMissingInputOnBuild("//foo:foo", 1)
            events.assertContainsError("foo/BUILD:2:8: //foo:foo: missing input file '//foo:missing.sh'")
            events.assertContainsEventWithFrequency("missing input file", 1)
            events.assertDoesNotContainEvent("Failed to determine build info")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMissingTopLevelInput() {
        // Create a rule that exports a missing source file as a top-level artifact so that the missing
        // file will be detected by the TargetCompletion function, not an ActionExecution function.
        write(
            "foo/missing.bzl",
            """
        def _missing_impl(ctx):
            return DefaultInfo(files = depset(ctx.files.srcs))

        missing = rule(
                       implementation = _missing_impl,
                       attrs = { 'srcs': attr.label_list(allow_files = True) }
        )
        
        """.trimIndent()
        )
        write(
            "foo/BUILD",
            """
        load('missing.bzl', 'missing')
        missing(name = 'foo', srcs = ['missing.sh'])
        
        """.trimIndent()
        )
        addOptions("--keep_going")
        assertMissingInputOnBuild("//foo:foo", 1)
        events.assertContainsError("foo/BUILD:2:8: //foo:foo: missing input file '//foo:missing.sh'")
        events.assertContainsEventWithFrequency("missing input file", 1)
    }

    private fun assertMissingInputOnBuild(label: String?, numMissing: Int) {
        val e: BuildFailedException = org.junit.Assert.assertThrows<T>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget(label) })
        val failureDetail: FailureDetail = e.getDetailedExitCode().getFailureDetail()
        assertThat(failureDetail.getExecution().getCode()).isEqualTo(Code.SOURCE_INPUT_MISSING)
        val expected = numMissing.toString() + " input file(s) do not exist"
        events.assertContainsError(expected)
        events.assertContainsEventWithFrequency(expected, 1)
        events.assertContainsError(label)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun allErrorsAggregated() {
        write(
            "foo/BUILD",
            """
        genrule(name = 'foo', srcs = [':in', ':genin'], outs = ['out'], cmd = 'touch ${'$'}@')
        genrule(name = 'gen', outs = ['genin'], cmd = 'false')
        
        """.trimIndent()
        )
        val targetCompleteEventRef: AtomicReference<TargetCompleteEvent?> = AtomicReference<TargetCompleteEvent?>()
        runtimeWrapper.registerSubscriber(
            object : Any() {
                @Suppress("unused")
                @com.google.common.eventbus.Subscribe
                fun accept(event: TargetCompleteEvent?) {
                    targetCompleteEventRef.set(event)
                }
            })
        val outErr: RecordingOutErr = RecordingOutErr()
        this.outErr = outErr
        addOptions("--keep_going")
        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//foo:foo") })
        assertThat(targetCompleteEventRef.get().getRootCauses().toList()).hasSize(1)
        com.google.common.truth.Subject.contains("Executing genrule //foo:gen failed")
    }
}
