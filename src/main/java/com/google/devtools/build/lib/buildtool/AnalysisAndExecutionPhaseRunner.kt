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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.actions.BuildFailedException

/**
 * Intended drop-in replacement for AnalysisPhaseRunner after we're done with merging Skyframe's
 * analysis and execution phases. This is part of https://github.com/bazelbuild/bazel/issues/14057.
 * Internal: b/147350683.
 * 
 * 
 * TODO(leba): Consider removing this class altogether to reduce complexity.
 */
object AnalysisAndExecutionPhaseRunner {
    private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

    @Throws(
        BuildFailedException::class,
        java.lang.InterruptedException::class,
        ViewCreationFailedException::class,
        AbruptExitException::class,
        InvalidConfigurationException::class,
        TestExecException::class,
        RepositoryMappingResolutionException::class
    )
    fun execute(
        env: CommandEnvironment,
        request: BuildRequest,
        buildOptions: BuildOptions?,
        loadingResult: TargetPatternPhaseValue?,
        executionSetupCallback: ExecutionSetup?,
        buildConfigurationCreatedCallback: BuildConfigurationsCreated?,
        buildDriverKeyTestContext: BuildDriverKeyTestContext?,
        remoteAnalysisCachingDependenciesProvider: RemoteAnalysisCachingDependenciesProvider?,
        remoteAnalysisCacheReaderDeps: RemoteAnalysisCacheReaderDepsProvider?
    ): AnalysisAndExecutionResult? {
        // Exit if there are any pending exceptions from modules.
        env.throwPendingException()

        var analysisAndExecutionResult: AnalysisAndExecutionResult? = null
        if (request.getBuildOptions().getPerformAnalysisPhase()) {
            MemoryProfiler.instance().markPhase(com.google.devtools.build.lib.profiler.ProfilePhase.ANALYZE_AND_EXECUTE)
            com.google.devtools.build.lib.profiler.Profiler.instance()
                .markPhase(com.google.devtools.build.lib.profiler.ProfilePhase.ANALYZE_AND_EXECUTE)

            com.google.devtools.build.lib.profiler.Profiler.instance().profile("runAnalysisAndExecutionPhase")
                .use { c ->
                    TopLevelTargetAnalysisWatcher.Companion.createAndRegisterWithEventBus(
                        env.getRuntime().getBlazeModules(), env, request, buildOptions
                    ).use { watcher ->
                        analysisAndExecutionResult =
                            runAnalysisAndExecutionPhase(
                                env,
                                request,
                                loadingResult,
                                buildOptions,
                                executionSetupCallback,
                                buildConfigurationCreatedCallback,
                                buildDriverKeyTestContext,
                                remoteAnalysisCachingDependenciesProvider,
                                remoteAnalysisCacheReaderDeps
                            )
                    }
                }
            val buildResultListener: BuildResultListener = env.getBuildResultListener()
            if (request.shouldRunTests()) {
                AnalysisPhaseRunner.reportTargetsWithTests(
                    env, buildResultListener.getAnalyzedTargets(), buildResultListener.getAnalyzedTests()
                )
            } else {
                AnalysisPhaseRunner.reportTargets(env, buildResultListener.getAnalyzedTargets())
            }

            AnalysisPhaseRunner.postAbortedEventsForSkippedTargets(
                env, buildResultListener.getSkippedTargets()
            )
        } else {
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.progress("Loading complete."))
            env.getReporter().post(NoAnalyzeEvent())
            logger.atInfo().log("No analysis requested, so finished")
            val failureDetail: FailureDetail? =
                BuildView.createAnalysisFailureDetail(loadingResult,  /* skyframeAnalysisResult= */null)
            if (failureDetail != null) {
                throw BuildFailedException(
                    failureDetail.getMessage(), DetailedExitCode.of(failureDetail)
                )
            }
        }

        return analysisAndExecutionResult
    }

    /**
     * Performs all phases of the build: Setup, Loading, Analysis & Execution.
     * 
     * 
     * Postcondition: On success, populates the BuildRequest's set of targets to build.
     * 
     * @return null if the build were successful; a useful error message if errors were encountered
     * and request.keepGoing.
     * @throws InterruptedException if the current thread was interrupted.
     * @throws ViewCreationFailedException if analysis failed for any reason.
     * @throws InvalidConfigurationException if the configuration can't be determined.
     * @throws BuildFailedException if action execution failed.
     * @throws TestExecException if test execution failed.
     */
    @Throws(
        java.lang.InterruptedException::class,
        InvalidConfigurationException::class,
        ViewCreationFailedException::class,
        BuildFailedException::class,
        TestExecException::class,
        RepositoryMappingResolutionException::class,
        AbruptExitException::class
    )
    private fun runAnalysisAndExecutionPhase(
        env: CommandEnvironment,
        request: BuildRequest,
        loadingResult: TargetPatternPhaseValue?,
        targetOptions: BuildOptions?,
        executionSetupCallback: ExecutionSetup?,
        buildConfigurationCreatedCallback: BuildConfigurationsCreated?,
        buildDriverKeyTestContext: BuildDriverKeyTestContext?,
        remoteAnalysisCachingDependenciesProvider: RemoteAnalysisCachingDependenciesProvider?,
        remoteAnalysisCacheReaderDeps: RemoteAnalysisCacheReaderDepsProvider?
    ): AnalysisAndExecutionResult? {
        env.getReporter().handle(com.google.devtools.build.lib.events.Event.progress("Loading complete.  Analyzing..."))

        val explicitTargetPatterns: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?> =
            getExplicitTargetPatterns(
                env,
                request.getTargets(),
                request.getKeepGoing(),
                request.getLoadingPhaseThreadCount()
            )

        val view: BuildView =
            BuildView(
                env.getDirectories(),
                env.getRuntime().getRuleClassProvider(),
                env.getSkyframeExecutor(),
                env.getRuntime().getCoverageReportActionFactory(request)
            )
        // TODO(b/199053098) TestFilteringCompleteEvent.
        return view.update(
            loadingResult,
            targetOptions,
            explicitTargetPatterns,
            request.getAspects(),
            request.getAspectsParameters(),
            request.getViewOptions(),
            request.getKeepGoing(),
            request.getViewOptions().getSkipIncompatibleExplicitTargets(),
            request.getCheckForActionConflicts(),
            env.getQuiescingExecutors(),
            request.getTopLevelArtifactContext(),
            request.reportIncompatibleTargets(),
            env.getReporter(),
            env.getEventBus(),
            env.getRuntime().getBugReporter(),  /* includeExecutionPhase= */
            true,
            request.getBuildOptions().getSkymeldAnalysisOverlapPercentage(),
            env.getLocalResourceManager(),
            env.getBuildResultListener(),
            executionSetupCallback,
            buildConfigurationCreatedCallback,
            buildDriverKeyTestContext,
            env.getAdditionalConfigurationChangeEvent(),
            remoteAnalysisCachingDependenciesProvider,
            remoteAnalysisCacheReaderDeps
        ) as AnalysisAndExecutionResult?
    }

    /**
     * Turns target patterns from the command line into parsed equivalents for single targets.
     * 
     * 
     * Globbing targets like ":all" and "..." are ignored here and will not be in the returned set.
     * 
     * @param env the action's environment.
     * @param requestedTargetPatterns the list of target patterns specified on the command line.
     * @param keepGoing --keep_going command line option
     * @param loadingPhaseThreads no of threads to be used in execution
     * @return the set of stringified labels of target patterns that represent single targets. The
     * stringified labels are in the "unambiguous canonical form".
     * @throws ViewCreationFailedException if a pattern fails to parse for some reason.
     */
    @Throws(
        ViewCreationFailedException::class,
        RepositoryMappingResolutionException::class,
        java.lang.InterruptedException::class
    )
    private fun getExplicitTargetPatterns(
        env: CommandEnvironment,
        requestedTargetPatterns: MutableList<String>,
        keepGoing: Boolean,
        loadingPhaseThreads: Int
    ): com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?> {
        val explicitTargetPatterns: com.google.common.collect.ImmutableSet.Builder<com.google.devtools.build.lib.cmdline.Label?> =
            com.google.common.collect.ImmutableSet.builder<com.google.devtools.build.lib.cmdline.Label?>()

        // TODO(andreisolo): Don't re-compute these here as they should be already computed inside the
        //  TargetPatternPhaseValue
        val mainRepoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping? =
            env.getSkyframeExecutor()
                .getMainRepoMapping(keepGoing, loadingPhaseThreads, env.getReporter())
        val parser: com.google.devtools.build.lib.cmdline.TargetPattern.Parser =
            com.google.devtools.build.lib.cmdline.TargetPattern.Parser(
                env.getRelativeWorkingDirectory(),
                RepositoryName.Companion.MAIN,
                mainRepoMapping
            )

        for (requestedTargetPattern in requestedTargetPatterns) {
            if (requestedTargetPattern.startsWith("-")) {
                // Excluded patterns are by definition not explicitly requested so we can move on to the
                // next target pattern.
                continue
            }

            // Parse the pattern. This should always work because this is at least the second time we're
            // doing it. The previous time is in runAnalysisAndExecutionPhase(). Still, if parsing does
            // fail we propagate the exception up.
            val parsedPattern: TargetPattern?
            try {
                parsedPattern = parser.parse(requestedTargetPattern)
            } catch (e: TargetParsingException) {
                throw ViewCreationFailedException(
                    "Failed to parse target pattern even though it was previously parsed successfully",
                    e.getDetailedExitCode().getFailureDetail(),
                    e
                )
            }

            if (parsedPattern.getType() == com.google.devtools.build.lib.cmdline.TargetPattern.Type.SINGLE_TARGET) {
                explicitTargetPatterns.add(parsedPattern.getSingleTargetLabel())
            }
        }

        return com.google.common.collect.ImmutableSet.copyOf<com.google.devtools.build.lib.cmdline.Label?>(
            explicitTargetPatterns.build()
        )
    }

    private class TopLevelTargetAnalysisWatcher(
        blazeModules: Iterable<BlazeModule>,
        env: CommandEnvironment,
        buildRequest: BuildRequest?,
        buildOptions: BuildOptions?
    ) : java.lang.AutoCloseable {
        private val blazeModules: Iterable<BlazeModule>
        private val env: CommandEnvironment
        private val buildRequest: BuildRequest?
        private val buildOptions: BuildOptions?

        init {
            this.blazeModules = blazeModules
            this.env = env
            this.buildRequest = buildRequest
            this.buildOptions = buildOptions
        }

        @com.google.common.eventbus.Subscribe
        @Throws(ViewCreationFailedException::class, java.lang.InterruptedException::class)
        fun handleTopLevelTargetAnalysisConcluded(e: TopLevelTargetAnalyzedEvent) {
            for (blazeModule in blazeModules) {
                blazeModule.afterTopLevelTargetAnalysis(
                    env, buildRequest, buildOptions, e.configuredTarget
                )
            }
        }

        @com.google.common.eventbus.Subscribe
        fun handleAspectAnalyzed(e: AspectAnalyzedEvent) {
            for (blazeModule in blazeModules) {
                blazeModule.afterSingleAspectAnalysis(buildRequest, e.configuredAspect())
            }
        }

        @com.google.common.eventbus.Subscribe
        fun handleTestAnalyzed(e: TestAnalyzedEvent) {
            for (blazeModule in blazeModules) {
                blazeModule.afterSingleTestAnalysis(buildRequest, e.configuredTarget)
            }
        }

        @com.google.common.eventbus.Subscribe
        fun handleKnownCoverageArtifacts(e: CoverageArtifactsKnownEvent) {
            for (blazeModule in blazeModules) {
                blazeModule.coverageArtifactsKnown(e.coverageArtifacts())
            }
        }

        override fun close() {
            env.getEventBus().unregister(this)
        }

        companion object {
            /** Creates an AnalysisOperationWatcher and registers it with the provided eventBus.  */
            fun createAndRegisterWithEventBus(
                blazeModules: Iterable<BlazeModule>,
                env: CommandEnvironment,
                buildRequest: BuildRequest?,
                buildOptions: BuildOptions?
            ): TopLevelTargetAnalysisWatcher {
                val watcher =
                    TopLevelTargetAnalysisWatcher(blazeModules, env, buildRequest, buildOptions)
                env.getEventBus().register(watcher)
                return watcher
            }
        }
    }
}
