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

import com.google.devtools.build.lib.pkgcache.LoadingPhaseCompleteEvent

/** Tracks the state of Skymeld builds and determines what to display at each state in the UI.  */
internal class SkymeldUiStateTracker : UiStateTracker {
    internal enum class BuildStatus {
        // We explicitly define a starting status, which can be used to determine what to display in
        // cases before the build has started.
        BUILD_NOT_STARTED,
        COMPUTING_MAIN_REPO_MAPPING,
        BUILD_STARTED,
        TARGET_PATTERN_PARSING,
        LOADING_COMPLETE,
        CONFIGURATION,  // Analysis with configuration.

        // The order of the AnalysisCompleteEvent and ExecutionProgressReceiverAvailableEvent is not
        // certain, this splits the possible paths of the change in BuildStatus into two.
        ANALYSIS_COMPLETE,  // After analysis but before execution.
        ANALYSIS_AND_EXECUTION,  // During analysis and execution.
        EXECUTION,  // Only execution.
        BUILD_COMPLETED
    }

    // Prevent a race condition with the thread that runs writeProgressBar.
    @get:kotlin.jvm.Synchronized
    @javax.annotation.concurrent.GuardedBy("this")
    var buildStatus: BuildStatus = BuildStatus.BUILD_NOT_STARTED
        private set

    constructor(clock: com.google.devtools.build.lib.clock.Clock?, targetWidth: Int) : super(clock, targetWidth)

    constructor(clock: com.google.devtools.build.lib.clock.Clock?) : super(clock)

    /**
     * Main method that writes the progress of the build.
     * 
     * @param rawTerminalWriter used to write to the terminal.
     * @param shortVersion whether to write a short version of the output.
     * @param timestamp null if the UiOptions specifies not to show timestamps.
     * @throws IOException when attempting to write to the terminal writer.
     */
    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    override fun writeProgressBar(
        rawTerminalWriter: AnsiTerminalWriter?, shortVersion: Boolean, timestamp: String?
    ) {
        val terminalWriter: PositionAwareAnsiTerminalWriter =
            PositionAwareAnsiTerminalWriter(rawTerminalWriter)
        if (timestamp != null) {
            terminalWriter.append(timestamp)
        }
        when (buildStatus) {
            BuildStatus.BUILD_NOT_STARTED -> return
            BuildStatus.COMPUTING_MAIN_REPO_MAPPING -> writeBaseProgress(
                "Computing main repo mapping",
                "",
                terminalWriter
            )

            BuildStatus.BUILD_STARTED -> writeBaseProgress("Loading", "", terminalWriter)
            BuildStatus.TARGET_PATTERN_PARSING -> writeLoadingAnalysisPhaseProgress(
                "Loading",
                "",
                terminalWriter,
                false
            )

            BuildStatus.LOADING_COMPLETE, BuildStatus.CONFIGURATION -> writeLoadingAnalysisPhaseProgress(
                "Analyzing", additionalMessage, terminalWriter, shortVersion
            )

            BuildStatus.ANALYSIS_COMPLETE -> {}
            BuildStatus.ANALYSIS_AND_EXECUTION -> {
                writeLoadingAnalysisPhaseProgress(
                    "Analyzing", additionalMessage, terminalWriter, shortVersion
                )
                terminalWriter.newline()
                if (executionPhaseStarted) {
                    writeExecutionProgress(terminalWriter, shortVersion)
                }
            }

            BuildStatus.EXECUTION -> if (executionPhaseStarted) {
                writeExecutionProgress(terminalWriter, shortVersion)
            }

            BuildStatus.BUILD_COMPLETED -> writeBaseProgress(
                if (ok) "INFO" else "FAILED",
                additionalMessage,
                terminalWriter
            )
        }

        if (!shortVersion) {
            reportOnDownloads(terminalWriter)
            maybeReportActiveUploadsOrDownloads(terminalWriter)
            maybeReportBepTransports(terminalWriter)
        }
    }

    @Throws(IOException::class)
    fun writeBaseProgress(
        status: String?, message: String?, terminalWriter: PositionAwareAnsiTerminalWriter
    ) {
        if (ok) {
            terminalWriter.okStatus()
        } else {
            terminalWriter.failStatus()
        }
        terminalWriter.append(status + ":").normal().append(" " + message)
    }

    @Throws(IOException::class)
    fun writeLoadingAnalysisPhaseProgress(
        status: String?,
        message: String,
        terminalWriter: PositionAwareAnsiTerminalWriter,
        shortVersion: Boolean
    ) {
        writeBaseProgress(status, message, terminalWriter)

        if (packageProgressReceiver != null) {
            val progress: com.google.devtools.build.lib.util.Pair<String?, String?> =
                packageProgressReceiver.progressState()
            var analysisProgress: String? = progress.getFirst()

            if (analysisProgressReceiver != null) {
                analysisProgress += ", " + analysisProgressReceiver.getProgressString()
            }

            if (message.isEmpty()) {
                terminalWriter.append(analysisProgress)
            } else {
                terminalWriter.append(" (" + analysisProgress + ")")
            }
            if (!progress.getSecond().isEmpty() && !shortVersion) {
                terminalWriter.newline().append("    " + progress.getSecond())
            }
        }
    }

    @kotlin.jvm.Synchronized
    override fun mainRepoMappingComputationStarted() {
        buildStatus = BuildStatus.COMPUTING_MAIN_REPO_MAPPING
    }

    @kotlin.jvm.Synchronized
    override fun buildStarted() {
        buildStatus = BuildStatus.BUILD_STARTED
    }

    @kotlin.jvm.Synchronized
    override fun loadingStarted(event: LoadingPhaseStartedEvent) {
        buildStatus = BuildStatus.TARGET_PATTERN_PARSING
        packageProgressReceiver = event.getPackageProgressReceiver()
    }

    @kotlin.jvm.Synchronized
    override fun loadingComplete(event: LoadingPhaseCompleteEvent) {
        buildStatus = BuildStatus.LOADING_COMPLETE
        val labelsCount: Int = event.getLabels().size()
        if (labelsCount == 1) {
            additionalMessage = "target " + com.google.common.collect.Iterables.getOnlyElement<T?>(event.getLabels())
        } else {
            additionalMessage =
                com.google.devtools.build.lib.util.StringUtil.formatCount(labelsCount.toLong()) + " targets"
        }
        mainRepositoryMapping = event.getMainRepositoryMapping()
    }

    @kotlin.jvm.Synchronized
    override fun configurationStarted(event: ConfigurationPhaseStartedEvent) {
        buildStatus = BuildStatus.CONFIGURATION
        analysisProgressReceiver = event.getAnalysisProgressReceiver()
    }

    /**
     * Make the state tracker aware of the fact that the analysis has finished. Return a summary of
     * the work done in the analysis phase.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @kotlin.jvm.Synchronized
    override fun analysisComplete(): String {
        // This is where the path of the BuildStatus splits, the BuildStatus at this point could be
        // either CONFIGURATION or ANALYSIS_AND_EXECUTION.
        buildStatus =
            if (BuildStatus.CONFIGURATION == buildStatus)
                BuildStatus.ANALYSIS_COMPLETE
            else
                BuildStatus.EXECUTION
        var workDone = "Analyzed " + additionalMessage
        if (packageProgressReceiver != null) {
            val progress: com.google.devtools.build.lib.util.Pair<String?, String?> =
                packageProgressReceiver.progressState()
            workDone += " (" + progress.getFirst()
            if (analysisProgressReceiver != null) {
                workDone += ", " + analysisProgressReceiver.getProgressString()
            }
            workDone += ")"
        }
        workDone += "."
        packageProgressReceiver = null
        analysisProgressReceiver = null
        return workDone
    }

    @kotlin.jvm.Synchronized
    override fun progressReceiverAvailable(event: ExecutionProgressReceiverAvailableEvent) {
        executionProgressReceiver = event.executionProgressReceiver
        // This is where the path of the BuildStatus splits, the BuildStatus at this point could be
        // either CONFIGURATION or ANALYSIS_COMPLETE.
        buildStatus =
            if (BuildStatus.CONFIGURATION == buildStatus)
                BuildStatus.ANALYSIS_AND_EXECUTION
            else
                BuildStatus.EXECUTION
    }

    @kotlin.jvm.Synchronized
    override fun buildComplete(event: BuildCompleteEvent): com.google.devtools.build.lib.events.Event? {
        buildStatus = BuildStatus.BUILD_COMPLETED
        return super.buildComplete(event)
    }

    @kotlin.jvm.Synchronized
    fun setBuildStatusForTestingOnly(newStatus: BuildStatus) {
        buildStatus = newStatus
    }

    @kotlin.jvm.Synchronized
    override fun buildCompleted(): Boolean {
        return BuildStatus.BUILD_COMPLETED == buildStatus
    }
}
