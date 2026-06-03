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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.buildtool.BuildResult

/** Tests [SkymeldUiStateTracker].  */
@RunWith(JUnit4::class)
class SkymeldUiStateTrackerTest : FoundationTestCase() {
    @org.junit.Test
    fun buildStarted_stateChanges() {
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val uiStateTracker: SkymeldUiStateTracker = SkymeldUiStateTracker(clock)

        assertThat(uiStateTracker.getBuildStatus()).isEqualTo(BuildStatus.BUILD_NOT_STARTED)
        uiStateTracker.buildStarted()
        assertThat(uiStateTracker.getBuildStatus()).isEqualTo(BuildStatus.BUILD_STARTED)
    }

    @org.junit.Test
    fun loadingStarted_stateChanges() {
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val uiStateTracker: SkymeldUiStateTracker = SkymeldUiStateTracker(clock)

        uiStateTracker.loadingStarted(
            LoadingPhaseStartedEvent(< T > mock < T ? > (PackageProgressReceiver::class.java)
        ))

        assertThat(uiStateTracker.getBuildStatus()).isEqualTo(BuildStatus.TARGET_PATTERN_PARSING)
    }

    @org.junit.Test
    fun loadingComplete_stateChanges() {
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val uiStateTracker: SkymeldUiStateTracker = SkymeldUiStateTracker(clock)

        uiStateTracker.loadingComplete(
            LoadingPhaseCompleteEvent(
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                RepositoryMapping.EMPTY
            )
        )

        assertThat(uiStateTracker.getBuildStatus()).isEqualTo(BuildStatus.LOADING_COMPLETE)
    }

    @org.junit.Test
    fun configurationStarted_stateChanges() {
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val uiStateTracker: SkymeldUiStateTracker = SkymeldUiStateTracker(clock)

        uiStateTracker.configurationStarted(
            ConfigurationPhaseStartedEvent(< T > mock < T ? > (AnalysisProgressReceiver::class.java)
        ))

        assertThat(uiStateTracker.getBuildStatus()).isEqualTo(BuildStatus.CONFIGURATION)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun analysisAndExecution_stateChangesAndWriteProgressBar() {
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val uiStateTracker: SkymeldUiStateTracker = SkymeldUiStateTracker(clock)
        val additionalMessage = "5 targets"
        uiStateTracker.setBuildStatusForTestingOnly(BuildStatus.CONFIGURATION)
        uiStateTracker.additionalMessage = additionalMessage

        // First we need to set up the state tracker to already be analysing.
        val loadingState = "42 packages loaded"
        val loadingActivity = "currently loading //src/foo/bar and 17 more"
        uiStateTracker.packageProgressReceiver =
            mockPackageProgressReceiver(loadingState, loadingActivity)

        val analysisProgressString = "5 targets and 0 aspects configured"
        uiStateTracker.analysisProgressReceiver = mockAnalysisProgressReceiver(analysisProgressString)

        // Mock starting execution while configuring (before analysis complete).
        val executionProgressReceiver: ExecutionProgressReceiver = ExecutionProgressReceiver(0, null)
        uiStateTracker.progressReceiverAvailable(
            ExecutionProgressReceiverAvailableEvent(executionProgressReceiver)
        )

        assertThat(uiStateTracker.getBuildStatus()).isEqualTo(BuildStatus.ANALYSIS_AND_EXECUTION)

        val terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter( /*discardHighlight=*/true)
        uiStateTracker.writeProgressBar(terminalWriter)
        val output: String? = terminalWriter.getTranscript()
        // Should write analysis and execution information.
        Truth.assertThat(output).contains("Analyzing")
        Truth.assertThat(output).contains(additionalMessage)
        Truth.assertThat(output).contains(loadingState)
        Truth.assertThat(output).contains(loadingActivity)
        Truth.assertThat(output).contains(analysisProgressString)
        Truth.assertThat(output).doesNotContain("[0 / 0]")
    }

    @org.junit.Test
    fun executionFromAnalysisAndExecution_stateChanges() {
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val uiStateTracker: SkymeldUiStateTracker = SkymeldUiStateTracker(clock)

        uiStateTracker.analysisComplete()

        assertThat(uiStateTracker.getBuildStatus()).isEqualTo(BuildStatus.EXECUTION)
    }

    @org.junit.Test
    fun buildCompleted_stateChanges() {
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val uiStateTracker: SkymeldUiStateTracker = SkymeldUiStateTracker(clock)

        val buildResult: BuildResult = BuildResult(clock.currentTimeMillis())
        buildResult.setDetailedExitCode(DetailedExitCode.success())
        clock.advanceMillis(TimeUnit.SECONDS.toMillis(1))
        buildResult.setStopTime(clock.currentTimeMillis())
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            uiStateTracker.buildComplete(BuildCompleteEvent(buildResult))

        assertThat(uiStateTracker.getBuildStatus()).isEqualTo(BuildStatus.BUILD_COMPLETED)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testWriteBaseProgress() {
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val uiStateTracker: SkymeldUiStateTracker = SkymeldUiStateTracker(clock)
        val status = "status"
        val message = "hello"

        uiStateTracker.buildStarted()
        uiStateTracker.ok = true
        val okTerminalWriter: LoggingTerminalWriter = LoggingTerminalWriter( /*discardHighlight=*/false)
        uiStateTracker.writeBaseProgress(
            status, message, PositionAwareAnsiTerminalWriter(okTerminalWriter)
        )
        assertOutputContainsBaseProgress(
            okTerminalWriter.getTranscript(), status, message,  /*ok=*/true
        )

        uiStateTracker.ok = false
        val notOkTerminalWriter: LoggingTerminalWriter =
            LoggingTerminalWriter( /*discardHighlight=*/false)
        uiStateTracker.writeBaseProgress(
            status, message, PositionAwareAnsiTerminalWriter(notOkTerminalWriter)
        )
        assertOutputContainsBaseProgress(
            notOkTerminalWriter.getTranscript(), status, message,  /*ok=*/false
        )
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testWriteLoadingAnalysisPhaseProgress() {
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val uiStateTracker: SkymeldUiStateTracker = SkymeldUiStateTracker(clock)
        uiStateTracker.ok = true
        val status = "status"
        val message = "message"
        val loadingState = "42 packages loaded"
        val loadingActivity = "currently loading //src/foo/bar and 17 more"
        val analysisProgressString = "5 targets and 0 aspects configured"

        // Mock starting loading.
        val terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter( /*discardHighlight=*/false)
        uiStateTracker.packageProgressReceiver =
            mockPackageProgressReceiver(loadingState, loadingActivity)

        // Output should only contain loading-related output.
        uiStateTracker.writeLoadingAnalysisPhaseProgress(
            status,
            message,
            PositionAwareAnsiTerminalWriter(terminalWriter),  /*shortVersion=*/
            false
        )
        val loadingOutput: String? = terminalWriter.getTranscript()
        assertOutputContainsBaseProgress(loadingOutput, status, message,  /*ok=*/true)
        Truth.assertThat(loadingOutput).contains("(" + loadingState + ")")
        Truth.assertThat(loadingOutput).contains(loadingActivity)
        Truth.assertThat(loadingOutput).doesNotContain(analysisProgressString)

        terminalWriter.reset()
        // When there is an empty message (only happens during target pattern parsing).
        uiStateTracker.writeLoadingAnalysisPhaseProgress(
            status,  /*message=*/
            "",
            PositionAwareAnsiTerminalWriter(terminalWriter),  /*shortVersion=*/
            false
        )
        val emptyMessageLoadingOutput: String? = terminalWriter.getTranscript()
        assertOutputContainsBaseProgress(
            emptyMessageLoadingOutput, status,  /*message=*/"",  /*ok=*/true
        )
        // The loading state should not be parenthesized.
        Truth.assertThat(emptyMessageLoadingOutput).doesNotContain("(" + loadingState + ")")
        Truth.assertThat(emptyMessageLoadingOutput).contains(loadingState)
        Truth.assertThat(emptyMessageLoadingOutput).contains(loadingActivity)
        Truth.assertThat(emptyMessageLoadingOutput).doesNotContain(analysisProgressString)

        terminalWriter.reset()
        // When writing as a short version.
        uiStateTracker.writeLoadingAnalysisPhaseProgress(
            status,
            message,
            PositionAwareAnsiTerminalWriter(terminalWriter),  /*shortVersion=*/
            true
        )
        val shortVersionLoadingOutput: String? = terminalWriter.getTranscript()
        assertOutputContainsBaseProgress(shortVersionLoadingOutput, status, message,  /*ok=*/true)
        // Output should only contain the loading state but not the activity.
        Truth.assertThat(shortVersionLoadingOutput).contains(loadingState)
        Truth.assertThat(shortVersionLoadingOutput).doesNotContain(loadingActivity)
        Truth.assertThat(emptyMessageLoadingOutput).doesNotContain(analysisProgressString)

        terminalWriter.reset()
        // Mock starting configuration.
        uiStateTracker.analysisProgressReceiver = mockAnalysisProgressReceiver(analysisProgressString)

        // Output should contain both loading and analysis related output.
        uiStateTracker.writeLoadingAnalysisPhaseProgress(
            status,
            message,
            PositionAwareAnsiTerminalWriter(terminalWriter),  /*shortVersion=*/
            false
        )
        val loadingAnalysisOutput: String? = terminalWriter.getTranscript()
        assertOutputContainsBaseProgress(loadingAnalysisOutput, status, message,  /*ok=*/true)
        Truth.assertThat(loadingAnalysisOutput).contains(loadingState)
        Truth.assertThat(loadingAnalysisOutput).contains(loadingActivity)
        Truth.assertThat(loadingAnalysisOutput).contains(analysisProgressString)
    }

    companion object {
        private fun assertOutputContainsBaseProgress(
            output: String?, status: String?, message: String?, ok: Boolean
        ) {
            val okIndicator: String? = if (ok) LoggingTerminalWriter.OK else LoggingTerminalWriter.FAIL
            Truth.assertThat(output)
                .contains(okIndicator + status + ":" + LoggingTerminalWriter.NORMAL + " " + message)
        }

        private fun mockPackageProgressReceiver(
            state: String?, activity: String?
        ): PackageProgressReceiver {
            val packageProgressReceiver: PackageProgressReceiver =
                Mockito.mock<PackageProgressReceiver>(PackageProgressReceiver::class.java)
            Mockito.`when`<T?>(packageProgressReceiver.progressState()).thenReturn(Pair(state, activity))
            return packageProgressReceiver
        }

        private fun mockAnalysisProgressReceiver(progress: String?): AnalysisProgressReceiver {
            val analysisProgressReceiver: AnalysisProgressReceiver =
                Mockito.mock<AnalysisProgressReceiver>(AnalysisProgressReceiver::class.java)
            Mockito.`when`<T?>(analysisProgressReceiver.getProgressString()).thenReturn(progress)
            return analysisProgressReceiver
        }
    }
}
