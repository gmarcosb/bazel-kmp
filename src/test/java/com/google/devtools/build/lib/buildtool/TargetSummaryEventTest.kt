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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.actions.Artifact

/**
 * Integration test verifying behavior of `com.google.devtools.build.lib.runtime.TargetSummaryEvent` event.
 */
@RunWith(JUnit4::class)
class TargetSummaryEventTest : BuildIntegrationTestCase() {
    @org.junit.Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    private val actionEventRecorder: com.google.devtools.build.lib.testutil.ActionEventRecorder =
        com.google.devtools.build.lib.testutil.ActionEventRecorder()
    private val helper: RewindingTestsHelper = RewindingTestsHelper(this, actionEventRecorder)

    @Before
    @Throws(java.lang.Exception::class)
    fun stageEmbeddedTools() {
        AnalysisMock.get().setupMockToolsRepository(mockToolsConfig)
    }

    @org.junit.After
    fun verifyAllSpawnShimsConsumed() {
        helper.verifyAllSpawnShimsConsumed()
    }

    @get:Throws(java.lang.Exception::class)
    val runtimeBuilder: BlazeRuntime.Builder
        get() = super.getRuntimeBuilder()
            .addBlazeModule(NoSpawnCacheModule())
            .addBlazeModule(CredentialModule())
            .addBlazeModule(BazelBuildEventServiceModule())
            .addBlazeModule(helper.makeControllableActionStrategyModule("standalone"))

    @Throws(java.lang.Exception::class)
    override fun setupOptions() {
        super.setupOptions()
        addOptions("--spawn_strategy=standalone", "--test_strategy=standalone")
        runtimeWrapper.registerSubscriber(actionEventRecorder)
    }

    @Throws(java.lang.Exception::class)
    private fun afterBuildCommand() {
        runtimeWrapper.newCommand()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun plainTarget_buildSuccess() {
        write("foo/BUILD", "genrule(name = 'foobin', outs = ['out.txt'], cmd = 'echo -n Hello > $@')")

        val bep: java.io.File = buildTargetAndCaptureBuildEventProtocol("//foo:foobin")

        val summary: TargetSummary =
            com.google.devtools.build.lib.buildtool.TargetSummaryEventTest.Companion.findTargetSummaryEventInBuildEventStream(
                bep
            )
        assertThat(summary.getOverallBuildSuccess()).isTrue()
        assertThat(summary.getOverallTestStatus()).isEqualTo(TestStatus.NO_STATUS)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun plainTarget_buildFails() {
        write("foo/BUILD", "genrule(name = 'foobin', outs = ['out.txt'], cmd = 'false')")

        val bep: java.io.File = buildFailingTargetAndCaptureBuildEventProtocol("//foo:foobin")

        val summary: TargetSummary =
            com.google.devtools.build.lib.buildtool.TargetSummaryEventTest.Companion.findTargetSummaryEventInBuildEventStream(
                bep
            )
        assertThat(summary.getOverallBuildSuccess()).isFalse()
        assertThat(summary.getOverallTestStatus()).isEqualTo(TestStatus.NO_STATUS)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun test_buildSucceeds_testSucceeds() {
        write("foo/good_test.sh", "#!/bin/bash", "true").setExecutable(true)
        write(
            "foo/BUILD",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "foo_test(name = 'good_test', srcs = ['good_test.sh'])"
        )

        val bep: java.io.File = testTargetAndCaptureBuildEventProtocol("//foo:good_test")

        val targetSummary: TargetSummary =
            com.google.devtools.build.lib.buildtool.TargetSummaryEventTest.Companion.findTargetSummaryEventInBuildEventStream(
                bep
            )
        assertThat(targetSummary.getOverallBuildSuccess()).isTrue()
        assertThat(targetSummary.getOverallTestStatus()).isEqualTo(TestStatus.PASSED)

        val testSummary: TestSummary? =
            com.google.devtools.build.lib.buildtool.TargetSummaryEventTest.Companion.findTestSummaryEventInBuildEventStream(
                bep
            )
        assertThat(testSummary.getOverallStatus()).isEqualTo(TestStatus.PASSED)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun test_buildSucceeds_testFails() {
        write("foo/bad_test.sh", "#!/bin/bash", "false").setExecutable(true)
        write(
            "foo/BUILD",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "foo_test(name = 'bad_test', srcs = ['bad_test.sh'])"
        )

        val bep: java.io.File = testTargetAndCaptureBuildEventProtocol("//foo:bad_test")

        val targetSummary: TargetSummary =
            com.google.devtools.build.lib.buildtool.TargetSummaryEventTest.Companion.findTargetSummaryEventInBuildEventStream(
                bep
            )
        assertThat(targetSummary.getOverallBuildSuccess()).isTrue()
        assertThat(targetSummary.getOverallTestStatus()).isEqualTo(TestStatus.FAILED)

        val testSummary: TestSummary? =
            com.google.devtools.build.lib.buildtool.TargetSummaryEventTest.Companion.findTestSummaryEventInBuildEventStream(
                bep
            )
        assertThat(testSummary.getOverallStatus()).isEqualTo(TestStatus.FAILED)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun test_buildSucceeds_testRuntimeFailsToBuild() {
        write("foo/good_test.sh", "#!/bin/bash", "true").setExecutable(true)
        write(
            "foo/BUILD",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "foo_test(name = 'good_test', srcs = ['good_test.sh'])"
        )

        // Hack: the path to the tools/test/BUILD file is prefixed in the Bazel tests.
        val pathToToolsTestBuildPrefix = if (AnalysisMock.get().isThisBazel()) "embedded_tools/" else ""
        val toolsTestBuildPath: Path? =
            mockToolsConfig.getPath(pathToToolsTestBuildPrefix + "tools/test/BUILD")
        // Delete the test-setup.sh file and introduce a broken genrule to create test-setup.sh.
        mockToolsConfig.getPath(pathToToolsTestBuildPrefix + "tools/test/test-setup.sh").delete()
        val bogusTestSetupGenrule: String =
            """
        genrule(
            name = 'bogus-make-test-setup',
            outs = ['test-setup.sh'],
            cmd = 'false',
        )
        
        """.trimIndent()
        FileSystemUtils.appendIsoLatin1(toolsTestBuildPath, bogusTestSetupGenrule)

        val bep: java.io.File = testTargetAndCaptureBuildEventProtocol("//foo:good_test")

        val targetSummary: TargetSummary =
            com.google.devtools.build.lib.buildtool.TargetSummaryEventTest.Companion.findTargetSummaryEventInBuildEventStream(
                bep
            )
        assertThat(targetSummary.getOverallBuildSuccess()).isTrue()
        assertThat(targetSummary.getOverallTestStatus()).isEqualTo(TestStatus.FAILED_TO_BUILD)

        // TODO: b/186996003 - TestSummary is a child of TargetComplete and should be posted.
        val testSummary: TestSummary? =
            com.google.devtools.build.lib.buildtool.TargetSummaryEventTest.Companion.findTestSummaryEventInBuildEventStream(
                bep
            )
        assertThat(testSummary).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun test_testActionThrowsExecException() {
        addOptions("--rewind_lost_inputs")
        write(
            "foo/BUILD",
            """
        load("//test_defs:foo_test.bzl", "foo_test")
        foo_test(name = "test", srcs = ["test.sh"], tags = ["cpu:invalid"])
        
        """.trimIndent()
        )
        write("foo/test.sh", "#!/bin/bash", "true").setExecutable(true)
        helper.addSpawnShim(
            "Testing //foo:test",
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                ExecResult.ofException(
                    UserExecException(
                        FailureDetail.newBuilder()
                            .setMessage("Invalid cpu tag: 'cpu:invalid'")
                            .setTestAction(
                                TestAction.newBuilder().setCode(TestAction.Code.INVALID_CPU_TAG)
                            )
                            .build()
                    )
                )
            })

        val bep: java.io.File = testTargetAndCaptureBuildEventProtocol("//foo:test")

        val targetSummary: TargetSummary =
            com.google.devtools.build.lib.buildtool.TargetSummaryEventTest.Companion.findTargetSummaryEventInBuildEventStream(
                bep
            )
        assertThat(targetSummary.getOverallBuildSuccess()).isTrue()
        assertThat(targetSummary.getOverallTestStatus()).isEqualTo(TestStatus.FAILED_TO_BUILD)

        // TODO: b/186996003 - TestSummary is a child of TargetComplete and should be posted.
        val testSummary: TestSummary? =
            com.google.devtools.build.lib.buildtool.TargetSummaryEventTest.Companion.findTestSummaryEventInBuildEventStream(
                bep
            )
        assertThat(testSummary).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun test_testActionLosesInput_rewindingSucceeds() {
        addOptions("--rewind_lost_inputs")
        write(
            "foo/BUILD",
            """
        load("//test_defs:foo_test.bzl", "foo_test")
        foo_test(name = "test", srcs = ["test.sh"], data = [":lost"])
        genrule(name = "lost", outs = ["lost.out"], cmd = "echo lost > ${'$'}@")
        
        """.trimIndent()
        )
        write("foo/test.sh", "#!/bin/bash", "true").setExecutable(true)
        helper.addSpawnShim(
            "Testing //foo:test",
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                val lost: Artifact? = SpawnInputUtils.getRunfilesArtifactWithName(spawn, context, "lost.out")
                helper.createLostInputsExecException(context, lost)
            })

        val bep: java.io.File = testTargetAndCaptureBuildEventProtocol("//foo:test")

        val targetSummary: TargetSummary =
            com.google.devtools.build.lib.buildtool.TargetSummaryEventTest.Companion.findTargetSummaryEventInBuildEventStream(
                bep
            )
        assertThat(targetSummary.getOverallBuildSuccess()).isTrue()
        assertThat(targetSummary.getOverallTestStatus()).isEqualTo(TestStatus.PASSED)

        val testSummary: TestSummary? =
            com.google.devtools.build.lib.buildtool.TargetSummaryEventTest.Companion.findTestSummaryEventInBuildEventStream(
                bep
            )
        assertThat(testSummary.getOverallStatus()).isEqualTo(TestStatus.PASSED)

        Truth.assertThat(com.google.common.collect.ImmutableMultiset.copyOf<String?>(helper.getExecutedSpawnDescriptions()))
            .hasCount("Executing genrule //foo:lost", 2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun test_testActionLosesInput_flakyActionFailsAfterRewind() {
        addOptions("--rewind_lost_inputs")
        write(
            "foo/BUILD",
            """
        load("//test_defs:foo_test.bzl", "foo_test")
        foo_test(name = "test", srcs = ["test.sh"], data = [":flaky_lost"])
        genrule(name = "flaky_lost", outs = ["flaky_lost.out"], cmd = "echo flaky_lost > ${'$'}@")
        
        """.trimIndent()
        )
        write("foo/test.sh", "#!/bin/bash", "true").setExecutable(true)
        helper.addSpawnShim(
            "Testing //foo:test",
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                helper.addSpawnShim(
                    "Executing genrule //foo:flaky_lost",
                    SpawnShim { spawn2: Spawn?, context2: ActionExecutionContext? ->
                        ExecResult.ofException(
                            SpawnExecException(
                                "Flaky action failure",
                                com.google.devtools.build.lib.buildtool.TargetSummaryEventTest.Companion.FAILED_RESULT,  /* forciblyRunRemotely= */
                                false,  /* catastrophe= */
                                false
                            )
                        )
                    })
                val flakyLost: Artifact? =
                    SpawnInputUtils.getRunfilesArtifactWithName(spawn, context, "flaky_lost.out")
                helper.createLostInputsExecException(context, flakyLost)
            })

        val bep: java.io.File = testTargetAndCaptureBuildEventProtocol("//foo:test")

        val targetSummary: TargetSummary =
            com.google.devtools.build.lib.buildtool.TargetSummaryEventTest.Companion.findTargetSummaryEventInBuildEventStream(
                bep
            )
        assertThat(targetSummary.getOverallBuildSuccess()).isTrue()
        assertThat(targetSummary.getOverallTestStatus()).isEqualTo(TestStatus.FAILED_TO_BUILD)

        // TODO: b/186996003 - TestSummary is a child of TargetComplete and should be posted.
        val testSummary: TestSummary? =
            com.google.devtools.build.lib.buildtool.TargetSummaryEventTest.Companion.findTestSummaryEventInBuildEventStream(
                bep
            )
        assertThat(testSummary).isNull()

        Truth.assertThat(com.google.common.collect.ImmutableMultiset.copyOf<String?>(helper.getExecutedSpawnDescriptions()))
            .hasCount("Executing genrule //foo:flaky_lost", 2)
    }

    @Throws(java.lang.Exception::class)
    private fun buildTargetAndCaptureBuildEventProtocol(target: String?): java.io.File {
        val bep: java.io.File = tmpFolder.newFile()
        // We use WAIT_FOR_UPLOAD_COMPLETE because it's the easiest way to force the BES module to
        // wait until the BEP binary file has been written.
        addOptions(
            "--keep_going",
            "--experimental_bep_target_summary",
            "--build_event_binary_file=" + bep.getAbsolutePath(),
            "--bes_upload_mode=WAIT_FOR_UPLOAD_COMPLETE"
        )
        buildTarget(target)
        // We need to wait for all events to be written to the file, which is done in #afterCommand()
        // if --bes_upload_mode=WAIT_FOR_UPLOAD_COMPLETE.
        afterBuildCommand()
        return bep
    }

    @Throws(java.lang.Exception::class)
    private fun buildFailingTargetAndCaptureBuildEventProtocol(target: String?): java.io.File {
        val bep: java.io.File = tmpFolder.newFile()
        // We use WAIT_FOR_UPLOAD_COMPLETE because it's the easiest way to force the BES module to
        // wait until the BEP binary file has been written.
        addOptions(
            "--keep_going",
            "--experimental_bep_target_summary",
            "--build_event_binary_file=" + bep.getAbsolutePath(),
            "--bes_upload_mode=WAIT_FOR_UPLOAD_COMPLETE"
        )
        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget(target) })
        // We need to wait for all events to be written to the file, which is done in #afterCommand()
        // if --bes_upload_mode=WAIT_FOR_UPLOAD_COMPLETE.
        afterBuildCommand()
        return bep
    }

    @Throws(java.lang.Exception::class)
    private fun testTargetAndCaptureBuildEventProtocol(target: String?): java.io.File {
        val bep: java.io.File = tmpFolder.newFile()
        val dispatcher: BlazeCommandDispatcher = BlazeCommandDispatcher(getRuntime())
        val args: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        args.add("test", target)
        args.addAll(runtimeWrapper.getOptions())
        // We use WAIT_FOR_UPLOAD_COMPLETE because it's the easiest way to force the BES module to
        // wait until the BEP binary file has been written.
        args.add(
            "--default_visibility=public",
            "--test_output=all",
            "--keep_going",
            "--client_env=PATH=/bin:/usr/bin:/usr/sbin:/sbin",
            "--experimental_bep_target_summary",
            "--build_event_binary_file=" + bep.getAbsolutePath(),
            "--bes_upload_mode=WAIT_FOR_UPLOAD_COMPLETE"
        )
        dispatcher.exec(args.build(),  /* clientDescription= */"test", outErr)
        return bep
    }

    companion object {
        private val FAILED_RESULT: SpawnResult? = Builder()
            .setStatus(SpawnResult.Status.NON_ZERO_EXIT)
            .setExitCode(1)
            .setFailureDetail(
                FailureDetail.newBuilder()
                    .setSpawn(FailureDetails.Spawn.newBuilder().setCode(Code.NON_ZERO_EXIT))
                    .build()
            )
            .setRunnerName("remote")
            .build()

        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        @Throws(IOException::class)
        private fun parseBuildEventsFromBuildEventStream(bep: java.io.File): com.google.common.collect.ImmutableList<BuildEvent?> {
            val buildEvents: com.google.common.collect.ImmutableList.Builder<BuildEvent?> =
                com.google.common.collect.ImmutableList.builder<BuildEvent?>()
            FileInputStream(bep).use { `in` ->
                var ev: BuildEvent?
                while ((BuildEvent.parseDelimitedFrom(`in`).also { ev = it }) != null) {
                    buildEvents.add(ev)
                }
            }
            return buildEvents.build()
        }

        @Throws(IOException::class)
        private fun findTargetSummaryEventInBuildEventStream(bep: java.io.File): TargetSummary {
            val events: com.google.common.collect.ImmutableList<BuildEvent?> =
                com.google.devtools.build.lib.buildtool.TargetSummaryEventTest.Companion.parseBuildEventsFromBuildEventStream(
                    bep
                )
            val targetSummary: java.util.Optional<TargetSummary> =
                events.stream()
                    .filter { e: BuildEvent? -> e.getId().getIdCase() === IdCase.TARGET_SUMMARY }
                    .map<Any?>(BuildEvent::getTargetSummary)
                    .collect(com.google.common.collect.MoreCollectors.toOptional<Any?>())
            if (targetSummary.isEmpty()) {
                com.google.devtools.build.lib.buildtool.TargetSummaryEventTest.Companion.logger.atSevere().log(
                    "No TargetSummary event found, dumping BEP:\n%s",
                    events.stream().map<Any?>(BuildEvent::toString).collect(Collectors.joining("\n"))
                )
                throw java.util.NoSuchElementException("No TargetSummary event found, see test log for full BEP")
            }
            return targetSummary.get()
        }

        @Throws(IOException::class)
        private fun findTestSummaryEventInBuildEventStream(bep: java.io.File): TestSummary? {
            return com.google.devtools.build.lib.buildtool.TargetSummaryEventTest.Companion.parseBuildEventsFromBuildEventStream(
                bep
            ).stream()
                .filter { e: BuildEvent? -> e.getId().getIdCase() === IdCase.TEST_SUMMARY }
                .map<Any?>(BuildEvent::getTestSummary)
                .collect(com.google.common.collect.MoreCollectors.toOptional<Any?>())
                .orElse(null)
        }
    }
}
