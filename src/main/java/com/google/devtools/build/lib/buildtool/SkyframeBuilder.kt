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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.actions.ActionCacheChecker

/**
 * A [Builder] implementation driven by Skyframe.
 */
@com.google.common.annotations.VisibleForTesting
class SkyframeBuilder @com.google.common.annotations.VisibleForTesting constructor(
    skyframeExecutor: SkyframeExecutor,
    resourceManager: ResourceManager?,
    actionCacheChecker: ActionCacheChecker?,
    actionExecutionSalt: String?,
    modifiedOutputFiles: ModifiedFileSet?,
    fileCache: InputMetadataProvider?,
    actionInputPrefetcher: ActionInputPrefetcher?,
    actionOutputDirectoryHelper: ActionOutputDirectoryHelper?,
    bugReporter: BugReporter?
) : com.google.devtools.build.lib.skyframe.Builder {
    private val resourceManager: ResourceManager?
    private val skyframeExecutor: SkyframeExecutor
    private val actionExecutionSalt: String?
    private val modifiedOutputFiles: ModifiedFileSet?
    private val fileCache: InputMetadataProvider?
    private val actionInputPrefetcher: ActionInputPrefetcher?
    private val actionOutputDirectoryHelper: ActionOutputDirectoryHelper?
    private val actionCacheChecker: ActionCacheChecker?
    private val bugReporter: BugReporter?

    init {
        this.resourceManager = resourceManager
        this.skyframeExecutor = skyframeExecutor
        this.actionCacheChecker = actionCacheChecker
        this.actionExecutionSalt = actionExecutionSalt
        this.modifiedOutputFiles = modifiedOutputFiles
        this.fileCache = fileCache
        this.actionInputPrefetcher = actionInputPrefetcher
        this.actionOutputDirectoryHelper = actionOutputDirectoryHelper
        this.bugReporter = bugReporter
    }

    @Throws(
        BuildFailedException::class,
        AbruptExitException::class,
        TestExecException::class,
        java.lang.InterruptedException::class
    )
    override fun buildArtifacts(
        reporter: com.google.devtools.build.lib.events.Reporter?,
        artifacts: MutableSet<Artifact?>,
        parallelTests: MutableSet<ConfiguredTarget?>,
        exclusiveTests: MutableSet<ConfiguredTarget?>,
        targetsToBuild: MutableSet<ConfiguredTarget?>,
        targetsToSkip: MutableSet<ConfiguredTarget?>,
        aspects: com.google.common.collect.ImmutableSet<AspectKey?>?,
        executor: Executor?,
        options: com.google.devtools.common.options.OptionsProvider,
        lastExecutionTimeRange: com.google.common.collect.Range<Long?>?,
        topLevelArtifactContext: TopLevelArtifactContext?,
        outputChecker: OutputChecker?
    ) {
        var parallelTests: MutableSet<ConfiguredTarget?> = parallelTests
        var exclusiveTests: MutableSet<ConfiguredTarget?> = exclusiveTests
        var targetsToBuild: MutableSet<ConfiguredTarget?> = targetsToBuild
        val buildRequestOptions: BuildRequestOptions? =
            options.getOptions<BuildRequestOptions?>(BuildRequestOptions::class.java)
        // TODO(bazel-team): Should use --experimental_fsvc_threads instead of the hardcoded constant
        // but plumbing the flag through is hard.
        val fsvcThreads = if (buildRequestOptions == null) 200 else buildRequestOptions.getFsvcThreads()
        val skyframeErrorHandlingRefactor =
            buildRequestOptions != null && buildRequestOptions.getSkyframeErrorHandlingRefactor()
        skyframeExecutor.detectModifiedOutputFiles(
            modifiedOutputFiles, lastExecutionTimeRange, outputChecker, fsvcThreads
        )
        com.google.devtools.build.lib.profiler.Profiler.instance().profile("configureActionExecutor").use { c ->
            skyframeExecutor.configureActionExecutor(
                fileCache,
                actionInputPrefetcher,
                actionExecutionSalt,
                options.getOptions<UiOptions?>(UiOptions::class.java).getMaxStdoutErrBytes()
            )
        }
        // Note that executionProgressReceiver accesses builtTargets concurrently (after wrapping in a
        // synchronized collection), so unsynchronized access to this variable is unsafe while it runs.
        val executionProgressReceiver: ExecutionProgressReceiver =
            ExecutionProgressReceiver(
                countTestActions(exclusiveTests),
                skyframeExecutor.getEventBus()
            )
        skyframeExecutor
            .getEventBus()
            .post(ExecutionProgressReceiverAvailableEvent(executionProgressReceiver))

        val detailedExitCodes: MutableList<DetailedExitCode?> = java.util.ArrayList<DetailedExitCode?>()
        var result: EvaluationResult<*>

        val statusReporter: ActionExecutionStatusReporter = ActionExecutionStatusReporter.create(
            reporter, skyframeExecutor.getEventBus()
        )

        val isBuildingExclusiveArtifacts: AtomicBoolean = AtomicBoolean(false)
        val watchdog: ActionExecutionInactivityWatchdog =
            ActionExecutionInactivityWatchdog(
                executionProgressReceiver.createInactivityMonitor(statusReporter),
                executionProgressReceiver.createInactivityReporter(
                    statusReporter, isBuildingExclusiveArtifacts
                ),
                options.getOptions<BuildRequestOptions?>(BuildRequestOptions::class.java).getProgressReportInterval()
            )

        skyframeExecutor.setActionExecutionProgressReportingObjects(
            executionProgressReceiver,
            executionProgressReceiver, statusReporter
        )
        watchdog.start()

        // We need to extract out artifacts for the combined coverage report; these should only be built
        // after any exclusive tests have been run, otherwise the tests get run as part of the build.
        val coverageReportArtifacts: com.google.common.collect.ImmutableSet<Artifact?> =
            artifacts.stream()
                .filter(java.util.function.Predicate { artifact: Artifact? ->
                    artifact.getArtifactOwner().equals(CoverageReportValue.COVERAGE_REPORT_KEY)
                })
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Artifact?>())
        val artifactsToBuild: MutableSet<Artifact?> =
            com.google.common.collect.Sets.difference<Artifact?>(artifacts, coverageReportArtifacts)

        targetsToBuild = com.google.common.collect.Sets.difference<ConfiguredTarget?>(targetsToBuild, targetsToSkip)
        parallelTests = com.google.common.collect.Sets.difference<ConfiguredTarget?>(parallelTests, targetsToSkip)
        exclusiveTests = com.google.common.collect.Sets.difference<ConfiguredTarget?>(exclusiveTests, targetsToSkip)

        try {
            result =
                skyframeExecutor.buildArtifacts(
                    reporter,
                    resourceManager,
                    executor,
                    artifactsToBuild,
                    targetsToBuild,
                    aspects,
                    parallelTests,
                    exclusiveTests,
                    options,
                    actionCacheChecker,
                    actionOutputDirectoryHelper,
                    executionProgressReceiver,
                    topLevelArtifactContext
                )
            // progressReceiver is finished, so unsynchronized access to builtTargets is now safe.
            var detailedExitCode: DetailedExitCode? =
                SkyframeErrorProcessor.processExecutionErrors(
                    result,
                    skyframeExecutor.getCyclesReporter(),
                    reporter,
                    options.getOptions<KeepGoingOption?>(KeepGoingOption::class.java).getKeepGoing(),
                    skyframeExecutor.tracksStateForIncrementality(),
                    skyframeExecutor.getEventBus(),
                    bugReporter,
                    skyframeErrorHandlingRefactor
                )
                    .executionDetailedExitCode

            if (detailedExitCode != null) {
                detailedExitCodes.add(detailedExitCode)
            }

            // Run exclusive tests: either tagged as "exclusive" or is run in an invocation with
            // --test_output=streamed.
            isBuildingExclusiveArtifacts.set(true)
            for (exclusiveTest in exclusiveTests) {
                // Since only one artifact is being built at a time, we don't worry about an artifact being
                // built and then the build being interrupted.
                result =
                    skyframeExecutor.runExclusiveTest(
                        reporter,
                        resourceManager,
                        executor,
                        exclusiveTest,
                        options,
                        actionCacheChecker,
                        actionOutputDirectoryHelper,
                        topLevelArtifactContext
                    )

                detailedExitCode =
                    SkyframeErrorProcessor.processExecutionErrors(
                        result,
                        skyframeExecutor.getCyclesReporter(),
                        reporter,
                        options.getOptions<KeepGoingOption?>(KeepGoingOption::class.java).getKeepGoing(),
                        skyframeExecutor.tracksStateForIncrementality(),
                        skyframeExecutor.getEventBus(),
                        bugReporter,
                        skyframeErrorHandlingRefactor
                    )
                        .executionDetailedExitCode
                com.google.common.base.Preconditions.checkState(
                    detailedExitCode != null || !result.keyNames<Any?>().isEmpty(),
                    "Build reported as successful but test %s not executed: %s",
                    exclusiveTest,
                    result
                )

                if (detailedExitCode != null) {
                    detailedExitCodes.add(detailedExitCode)
                }
            }
            // Build coverage report
            if (!coverageReportArtifacts.isEmpty()) {
                result =
                    skyframeExecutor.evaluateSkyKeysWithExecution(
                        reporter,
                        executor,
                        Artifact.keys(coverageReportArtifacts),
                        options,
                        actionCacheChecker,
                        actionOutputDirectoryHelper
                    )

                detailedExitCode =
                    SkyframeErrorProcessor.processExecutionErrors(
                        result,
                        skyframeExecutor.getCyclesReporter(),
                        reporter,
                        options.getOptions<KeepGoingOption?>(KeepGoingOption::class.java).getKeepGoing(),
                        skyframeExecutor.tracksStateForIncrementality(),
                        skyframeExecutor.getEventBus(),
                        bugReporter,
                        skyframeErrorHandlingRefactor
                    )
                        .executionDetailedExitCode
                if (detailedExitCode != null) {
                    detailedExitCodes.add(detailedExitCode)
                }
            }
        } finally {
            watchdog.stop()
            skyframeExecutor.setActionExecutionProgressReportingObjects(null, null, null)
            statusReporter.unregisterFromEventBus()
        }

        if (detailedExitCodes.isEmpty()) {
            return
        }

        // Use the exit code with the highest priority.
        throw BuildFailedException(
            null, Collections.max<T?>(detailedExitCodes, DetailedExitCodeComparator.INSTANCE)
        )
    }

    fun getActionCacheChecker(): ActionCacheChecker? {
        return actionCacheChecker
    }

    fun getFileCache(): InputMetadataProvider? {
        return fileCache
    }

    fun getActionOutputDirectoryHelper(): ActionOutputDirectoryHelper? {
        return actionOutputDirectoryHelper
    }

    fun getActionInputPrefetcher(): ActionInputPrefetcher? {
        return actionInputPrefetcher
    }

    companion object {
        private fun countTestActions(testTargets: Iterable<ConfiguredTarget?>): Int {
            var count = 0
            for (testTarget in testTargets) {
                count += TestProvider.getTestStatusArtifacts(testTarget).size()
            }
            return count
        }
    }
}
