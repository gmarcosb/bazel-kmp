// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.rewinding

import com.google.devtools.build.lib.actions.Artifact

/**
 * Integration tests for action rewinding.
 * 
 * 
 * Uses [TestParameter]s to run tests with all combinations of `--track_incremental_state`, `--keep_going`, and `--experimental_merged_skyframe_analysis_execution`.
 */
// TODO(b/228090759): Consider asserting on graph structure to improve coverage for incrementality.
// TODO(b/228090759): Add back actionFromPreviousBuildReevaluated.
@RunWith(TestParameterInjector::class)
class RewindingTest : BuildIntegrationTestCase() {
    @TestParameter
    private val trackIncrementalState = false

    @TestParameter
    private val keepGoing = false

    @TestParameter
    private val skymeld = false

    private val actionEventRecorder: ActionEventRecorder = ActionEventRecorder()
    private val helper: RewindingTestsHelper = RewindingTestsHelper(this, actionEventRecorder)

    @get:Throws(java.lang.Exception::class)
    val runtimeBuilder: BlazeRuntime.Builder
        get() = super.runtimeBuilder
            .addBlazeModule(RemoteModule())
            .addBlazeModule(BlockWaitingModule())
            .addBlazeModule(IncludeScanningModule())
            .addBlazeModule(helper.makeControllableActionStrategyModule("remote", "standalone"))
            .addBlazeModule(helper.getLostOutputsModule())
            .addBlazeModule(
                object : BlazeModule() {
                    public override fun workspaceInit(
                        runtime: BlazeRuntime?, directories: BlazeDirectories?, builder: WorkspaceBuilder
                    ) {
                        // Disable external repositories so that we don't trigger
                        // RepoMappingManifestAction. This preserves action graph structure between blaze
                        // and bazel, which is important for this test's assertions.
                        //
                        // IMPORTANT: As a result of this, external repositories are not symlinked under
                        // the execroot with Skymeld enabled. See onTargetAnalyzed for how to manually
                        // create such a symlink.
                        builder.allowExternalRepositories(false)
                    }
                })

    val spawnModules: com.google.common.collect.ImmutableList<BlazeModule?>
        get() = com.google.common.collect.ImmutableList.builder<BlazeModule?>()
            .addAll(super.spawnModules)
            .add(CredentialModule())
            .build()

    val startupOptionClasses: com.google.common.collect.ImmutableList<java.lang.Class<out OptionsBase?>?>
        get() = com.google.common.collect.ImmutableList.builder<java.lang.Class<out OptionsBase?>?>()
            .addAll(super.startupOptionClasses)
            .add(RemoteStartupOptions::class.java)
            .build()

    @Throws(java.lang.Exception::class)
    override fun setupOptions() {
        super.setupOptions()
        addOptions(
            "--enable_runfiles",
            "--spawn_strategy=remote",
            "--remote_executor=grpc://localhost:" + worker.getPort(),
            "--remote_download_regex=.*\\.inlined$",
            "--noexperimental_merged_skyframe_analysis_execution",
            "--rewind_lost_inputs",
            "--features=cc_include_scanning",
            "--experimental_remote_include_extraction_size_threshold=0",
            "--experimental_inmemory_dotincludes_files",
            "--experimental_remote_cache_eviction_retries=0",
            "--track_incremental_state=" + trackIncrementalState,
            "--keep_going=" + keepGoing,
            "--experimental_merged_skyframe_analysis_execution=" + skymeld
        )
        runtimeWrapper.registerSubscriber(actionEventRecorder)
        runtimeWrapper.registerSubscriber(this)
    }

    @com.google.common.eventbus.Subscribe
    @Throws(IOException::class)
    fun onTargetAnalyzed(event: TargetConfiguredEvent?) {
        if (skymeld) {
            // Necessary due to the RepositoryHelpersHolder nulling above, simulates the effect of
            // TopLevelTargetReadyForSymlinkPlanting.
            FileSystemUtils.ensureSymbolicLink(
                directories.getExecRoot(TestConstants.WORKSPACE_NAME).getRelative("external/bazel_tools"),
                getOutputBase().getRelative("external/bazel_tools")
            )
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noLossSmokeTest() {
        helper.runNoLossSmokeTest()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun lostInputWithRewindingDisabled() {
        helper.runLostInputWithRewindingDisabled()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildingParentFoundUndoneChildNotToleratedWithoutRewinding() {
        helper.runBuildingParentFoundUndoneChildNotToleratedWithoutRewinding()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dependentActionsReevaluated() {
        helper.runDependentActionsReevaluated_spawnFailed()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleLostInputsForRewindPlan(
        @TestParameter("standalone", "remote") producerStrategy: String?,
        @TestParameter("standalone", "remote") consumerStrategy: String?
    ) {
        if (!AnalysisMock.get().isThisBazel()) {
            // TODO: without this, test running internally hangs forever. Need to investigate why.
            addOptions("--remote_cache_async=false")
        }
        addOptions(
            "--strategy_regexp=.*//test:rule.*=" + producerStrategy,
            "--strategy_regexp=.*//test:consume.*=" + consumerStrategy
        )
        helper.runMultipleLostInputsForRewindPlan()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ineffectiveRewindingResultsInLostInputTooManyTimes() {
        helper.runIneffectiveRewindingResultsInLostInputTooManyTimes()
        assertOutputForRule2NotCreated()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun interruptedDuringRewindStopsNormally() {
        helper.runInterruptedDuringRewindStopsNormally()
        assertOutputForRule2NotCreated()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun failureDuringRewindStopsNormally() {
        helper.runFailureDuringRewindStopsNormally()
        assertOutputForRule2NotCreated()
    }

    /**
     * Because this test infrastructure allows builds to write outputs to the filesystem, these
     * "fail"/"stops normally" tests can assert that the build's output file was not written.
     */
    @Throws(java.lang.Exception::class)
    private fun assertOutputForRule2NotCreated() {
        val output: Artifact? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                getFilesToBuild(getExistingConfiguredTarget("//test:rule2")).toList()
            )
        assertThat(output.getPath().exists()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun intermediateActionRewound() {
        helper.runIntermediateActionRewound()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun chainOfActionsRewound() {
        helper.runChainOfActionsRewound()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nondeterministicActionRewound() {
        helper.runNondeterministicActionRewound()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun parallelTrackSharedActionsRewound() {
        helper.runParallelTrackSharedActionsRewound()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun treeFileArtifactRewound() {
        skipIfNotLinux()
        helper.runTreeFileArtifactRewound_spawnFailed()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun treeArtifactRewound_allFilesLost() {
        skipIfNotLinux()
        helper.runTreeArtifactRewound_allFilesLost_spawnFailed()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun treeArtifactRewound_oneFileLost() {
        skipIfNotLinux()
        helper.runTreeArtifactRewound_oneFileLost_spawnFailed()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun generatedRunfilesRewound_allFilesLost() {
        helper.runGeneratedRunfilesRewound_allFilesLost_spawnFailed()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun generatedRunfilesRewound_oneFileLost() {
        helper.runGeneratedRunfilesRewound_oneFileLost_spawnFailed()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dupeDirectAndRunfilesDependencyRewound() {
        helper.runDupeDirectAndRunfilesDependencyRewound_spawnFailed()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun treeInRunfilesRewound() {
        helper.runTreeInRunfilesRewound_spawnFailed()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun inputsFromSameGeneratingActionSplitAmongNestedSetChildren() {
        helper.runInputsFromSameGeneratingActionSplitAmongNestedSetChildren()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun generatedHeaderRewound_lostInInputDiscovery() {
        skipIfBazel()
        helper.runGeneratedHeaderRewound_lostInInputDiscovery_spawnFailed()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun generatedHeaderRewound_lostInActionExecution() {
        skipIfNotLinux()
        helper.runGeneratedHeaderRewound_lostInActionExecution_spawnFailed()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun generatedTransitiveHeaderRewound_lostInInputDiscovery() {
        skipIfBazel()
        helper.runGeneratedTransitiveHeaderRewound_lostInInputDiscovery_spawnFailed()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun generatedTransitiveHeaderRewound_lostInActionExecution() {
        skipIfNotLinux()
        helper.runGeneratedTransitiveHeaderRewound_lostInActionExecution_spawnFailed()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun doneToDirtyDepForNodeInError() {
        helper.runDoneToDirtyDepForNodeInError()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flakyActionFailsAfterRewind_raceWithIndirectConsumer_undoneDuringInputChecking() {
        helper.runFlakyActionFailsAfterRewind_raceWithIndirectConsumer_undoneDuringInputChecking()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun discoveredCppModuleLost() {
        skipIfBazel()
        helper.runDiscoveredCppModuleLost()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleLostInputsWithSameDigest_rewoundTogether() {
        helper.runMultipleLostInputsWithSameDigest_rewoundTogether()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun lostTopLevelOutputWithRewindingDisabled() {
        helper.runLostTopLevelOutputWithRewindingDisabled()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun topLevelOutputRewound_regularFile() {
        helper.runTopLevelOutputRewound_regularFile()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun topLevelOutputRewound_aspectOwned() {
        helper.runTopLevelOutputRewound_aspectOwned()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun topLevelOutputRewound_fileInTreeArtifact() {
        helper.runTopLevelOutputRewound_fileInTreeArtifact()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun topLevelOutputRewound_partiallyBuiltTarget_regularFile() {
        helper.runTopLevelOutputRewound_partiallyBuiltTarget_regularFile()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun topLevelOutputRewound_partiallyBuiltTarget_fileInTreeArtifact() {
        helper.runTopLevelOutputRewound_partiallyBuiltTarget_fileInTreeArtifact()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun topLevelOutputRewound_ineffectiveRewinding() {
        helper.runTopLevelOutputRewound_ineffectiveRewinding()
    }

    companion object {
        @ClassRule
        @org.junit.Rule
        val worker: WorkerInstance = IntegrationTestUtils.createWorker()

        /**
         * Skips test cases that cannot run with bazel.
         * 
         * 
         * [BuildIntegrationTestCase] currently does not support include scanning or header
         * modules on bazel.
         */
        private fun skipIfBazel() {
            TruthJUnit.assume().that(AnalysisMock.get().isThisBazel()).isFalse()
        }

        /**
         * Skips test cases that cannot run on non-Linux platforms.
         * 
         * 
         * The macOS linker does not support --start-lib/--end-lib and nodeps dynamic libraries, which
         * throws off the assertions.
         */
        private fun skipIfNotLinux() {
            TruthJUnit.assume()
                .that<com.google.devtools.build.lib.util.OS?>(com.google.devtools.build.lib.util.OS.getCurrent())
                .isEqualTo(com.google.devtools.build.lib.util.OS.LINUX)
        }
    }
}
