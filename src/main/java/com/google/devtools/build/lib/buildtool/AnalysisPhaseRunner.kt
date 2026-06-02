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

import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue.configurationId

/** Performs target pattern eval, configuration creation, loading and analysis.  */
object AnalysisPhaseRunner {
    private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

    @Throws(
        BuildFailedException::class,
        java.lang.InterruptedException::class,
        ViewCreationFailedException::class,
        AbruptExitException::class,
        InvalidConfigurationException::class,
        RepositoryMappingResolutionException::class
    )
    fun execute(
        env: CommandEnvironment,
        request: BuildRequest,
        targetPatternPhaseValue: TargetPatternPhaseValue?,
        buildOptions: BuildOptions?,
        remoteAnalysisCachingDependenciesProvider: RemoteAnalysisCachingDependenciesProvider?,
        remoteAnalysisCacheReaderDeps: RemoteAnalysisCacheReaderDepsProvider?
    ): AnalysisResult? {
        // Exit if there are any pending exceptions from modules.
        env.throwPendingException()

        var analysisResult: AnalysisResult? = null
        if (request.getBuildOptions().getPerformAnalysisPhase()) {
            MemoryProfiler.instance().markPhase(com.google.devtools.build.lib.profiler.ProfilePhase.ANALYZE)
            com.google.devtools.build.lib.profiler.Profiler.instance()
                .markPhase(com.google.devtools.build.lib.profiler.ProfilePhase.ANALYZE)

            com.google.devtools.build.lib.profiler.Profiler.instance().profile("runAnalysisPhase").use { c ->
                analysisResult =
                    runAnalysisPhase(
                        env,
                        request,
                        targetPatternPhaseValue,
                        buildOptions,
                        remoteAnalysisCachingDependenciesProvider,
                        remoteAnalysisCacheReaderDeps
                    )
            }
            for (module in env.getRuntime().getBlazeModules()) {
                module.afterAnalysis(env, request, buildOptions, analysisResult)
            }

            if (request.shouldRunTests()) {
                reportTargetsWithTests(
                    env,
                    analysisResult.getTargetsToBuild(),
                    com.google.common.base.Preconditions.checkNotNull<T?>(analysisResult.getTargetsToTest())
                )
            } else {
                reportTargets(env, analysisResult.getTargetsToBuild())
            }

            postAbortedEventsForSkippedTargets(env, analysisResult.getTargetsToSkip())
        } else {
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.progress("Loading complete."))
            env.getReporter().post(NoAnalyzeEvent())
            logger.atInfo().log("No analysis requested, so finished")
            val failureDetail: FailureDetail? =
                BuildView.createAnalysisFailureDetail(
                    targetPatternPhaseValue,  /* skyframeAnalysisResult= */null
                )
            if (failureDetail != null) {
                throw BuildFailedException(
                    failureDetail.getMessage(), DetailedExitCode.of(failureDetail)
                )
            }
        }

        return analysisResult
    }

    /**
     * Evaluates the PROJECT.scl file and set corresponding options, if required by specific feature
     * flags (e.g. canonical configurations, remote analysis caching).
     * 
     * 
     * May have a side-effect from updating the fields in the [CommandEnvironment] `env` parameter.
     * 
     * 
     * Shared by both Skymeld and non-Skymeld analysis.
     */
    @Throws(LoadingFailedException::class, InvalidConfigurationException::class)
    fun evaluateProjectFile(
        request: BuildRequest,
        buildOptions: BuildOptions,
        allOptionNames: com.google.common.collect.ImmutableSet<String?>?,
        userOptions: com.google.common.collect.ImmutableMap<String?, String?>?,
        targetPatternPhaseValue: TargetPatternPhaseValue,
        env: CommandEnvironment
    ): ProjectEvaluationResult? {
        val featureFlags: EnumSet<FeaturesUsingProjectFile?> =
            EnumSet.noneOf<FeaturesUsingProjectFile?>(FeaturesUsingProjectFile::class.java)
        val resultBuilder =
            ProjectEvaluationResult.Companion.builder()
                .buildOptions(com.google.common.collect.ImmutableSet.of<String?>())!!
                .activeDirectoriesMatcher(java.util.Optional.empty<PathFragmentPrefixTrie?>())!!
                .projectFile(java.util.Optional.empty<com.google.devtools.build.lib.cmdline.Label?>())

        if (env.getCommand().buildPhase.executes()) {
            // RemoteAnalysisCachingOptions is never null because it's a build command flag, and this
            // method only runs for build commands.
            when (env.getOptions().getOptions<RemoteAnalysisCachingOptions?>(RemoteAnalysisCachingOptions::class.java)
                .getMode()) {
                RemoteAnalysisCacheMode.DUMP_UPLOAD_MANIFEST_ONLY -> featureFlags.add(FeaturesUsingProjectFile.ANALYSIS_CACHING_UPLOAD)
                RemoteAnalysisCacheMode.UPLOAD -> featureFlags.add(FeaturesUsingProjectFile.ANALYSIS_CACHING_UPLOAD)
                RemoteAnalysisCacheMode.DOWNLOAD -> featureFlags.add(FeaturesUsingProjectFile.ANALYSIS_CACHING_DOWNLOAD)
                RemoteAnalysisCacheMode.OFF -> {}
            }
        }

        if (!com.google.common.base.Strings.isNullOrEmpty(buildOptions.get(CoreOptions::class.java).getSclConfig())
            || request.getBuildOptions().getEnforceProjectConfigs()
        ) {
            featureFlags.add(FeaturesUsingProjectFile.SCL_CONFIG)
        }

        if (env.getSkyframeExecutor().getSkyfocusState().enabled) {
            featureFlags.add(FeaturesUsingProjectFile.SKYFOCUS)
        }

        if (featureFlags.isEmpty()) {
            return resultBuilder.build()
        }

        if (featureFlags.contains(FeaturesUsingProjectFile.SKYFOCUS)
            && (featureFlags.contains(FeaturesUsingProjectFile.ANALYSIS_CACHING_UPLOAD)
                    || featureFlags.contains(FeaturesUsingProjectFile.ANALYSIS_CACHING_DOWNLOAD))
        ) {
            val message =
                "Skyfocus and remote analysis caching are incompatible. Enable one or the other."
            throw LoadingFailedException(
                message,
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(message)
                        .setRemoteAnalysisCaching(
                            RemoteAnalysisCaching.newBuilder().setCode(INCOMPATIBLE_OPTIONS)
                        )
                        .build()
                )
            )
        }

        val activeProjects: Project.ActiveProjects
        try {
            activeProjects =
                Project.getProjectFiles(
                    targetPatternPhaseValue.getNonExpandedLabels(),
                    env.getSkyframeExecutor(),
                    env.getReporter()
                )
        } catch (e: ProjectResolutionException) {
            throw LoadingFailedException(
                e.getMessage(),
                DetailedExitCode.of(ExitCode.PARSING_FAILURE, FailureDetail.getDefaultInstance())
            )
        }

        if (featureFlags.contains(FeaturesUsingProjectFile.ANALYSIS_CACHING_UPLOAD) || featureFlags.contains(
                FeaturesUsingProjectFile.SKYFOCUS
            )
        ) {
            // Features that can work with zero or one project file.
            if (activeProjects.projectFilesToTargetLabels().size() > 1) {
                val message: String? =
                    "This is a %s. %s"
                        .formatted(activeProjects.buildType(), activeProjects.differentProjectsDetails())
                throw LoadingFailedException(
                    message,
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage(message)
                            .setRemoteAnalysisCaching(
                                RemoteAnalysisCaching.newBuilder().setCode(PROJECT_FILE_NOT_FOUND)
                            )
                            .build()
                    )
                )
            }
            val projectMatcher: PathFragmentPrefixTrie? =
                if (activeProjects.isEmpty())
                    null
                else
                    BuildTool.Companion.getActiveDirectoriesMatcher(
                        activeProjects.projectFilesToTargetLabels().keySet().iterator().next(),
                        env.getSkyframeExecutor(),
                        env.getReporter()
                    )

            resultBuilder.activeDirectoriesMatcher(java.util.Optional.ofNullable<PathFragmentPrefixTrie?>(projectMatcher))
        }

        if (featureFlags.contains(FeaturesUsingProjectFile.SCL_CONFIG) && !activeProjects.isEmpty()) {
            // Do not apply canonical configurations if there are no project files.
            val options: com.google.common.collect.ImmutableSet<String?>? =
                Project.applySclConfig(
                    buildOptions,
                    activeProjects,
                    buildOptions.get(CoreOptions::class.java).getSclConfig(),
                    allOptionNames,
                    userOptions,
                    env.getConfigFlagDefinitions(),
                    request.getBuildOptions().getEnforceProjectConfigs(),
                    env.getReporter(),
                    env.getSkyframeExecutor()
                )
            resultBuilder.buildOptions(options)
            resultBuilder.projectFile(
                java.util.Optional.ofNullable<T?>(
                    if (activeProjects.isEmpty())
                        null
                    else
                        activeProjects.projectFilesToTargetLabels().keySet().iterator().next()
                )
            )
        }

        return resultBuilder.build()
    }

    fun postAbortedEventsForSkippedTargets(
        env: CommandEnvironment, targetsToSkip: com.google.common.collect.ImmutableSet<ConfiguredTarget>
    ) {
        for (target in targetsToSkip) {
            val config: BuildConfigurationValue? =
                env.getSkyframeExecutor()
                    .getConfiguration(env.getReporter(), target.getConfigurationKey())
            val label: com.google.devtools.build.lib.cmdline.Label = target.getOriginalLabel()
            env.getEventBus()
                .post(
                    AbortedEvent(
                        BuildEventIdUtil.targetCompleted(label, configurationId(config)),
                        AbortReason.SKIPPED,
                        java.lang.String.format("Target %s build was skipped.", label),
                        label
                    )
                )
        }
    }

    /**
     * Performs the initial phases 0-2 of the build: Setup, Loading and Analysis.
     * 
     * 
     * Postcondition: On success, populates the BuildRequest's set of targets to build.
     * 
     * @return null if loading / analysis phases were successful; a useful error message if loading or
     * analysis phase errors were encountered and request.keepGoing.
     * @throws InterruptedException if the current thread was interrupted.
     * @throws ViewCreationFailedException if analysis failed for any reason.
     */
    @Throws(
        java.lang.InterruptedException::class,
        InvalidConfigurationException::class,
        RepositoryMappingResolutionException::class,
        ViewCreationFailedException::class,
        AbruptExitException::class
    )
    private fun runAnalysisPhase(
        env: CommandEnvironment,
        request: BuildRequest,
        loadingResult: TargetPatternPhaseValue?,
        targetOptions: BuildOptions?,
        remoteAnalysisCachingDependenciesProvider: RemoteAnalysisCachingDependenciesProvider?,
        remoteAnalysisCacheReaderDeps: RemoteAnalysisCacheReaderDepsProvider?
    ): AnalysisResult {
        val timer: com.google.common.base.Stopwatch = com.google.common.base.Stopwatch.createStarted()
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
        val analysisResult: AnalysisResult
        try {
            analysisResult =
                view.update(
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
                    false,  /* skymeldAnalysisOverlapPercentage= */
                    0,  /* resourceManager= */
                    null,  /* buildResultListener= */
                    null,  /* executionSetupCallback= */
                    null,  /* buildConfigurationsCreatedCallback= */
                    null,  /* buildDriverKeyTestContext= */
                    null,
                    env.getAdditionalConfigurationChangeEvent(),
                    remoteAnalysisCachingDependenciesProvider,
                    remoteAnalysisCacheReaderDeps
                )
        } catch (unexpected: BuildFailedException) {
            throw java.lang.IllegalStateException("Unexpected execution exception type: ", unexpected)
        } catch (unexpected: TestExecException) {
            throw java.lang.IllegalStateException("Unexpected execution exception type: ", unexpected)
        }

        // TODO(bazel-team): Merge these into one event.
        env.getEventBus()
            .post(
                AnalysisPhaseCompleteEvent(
                    analysisResult.getTargetsToBuild(),
                    view.getEvaluatedCounts(),
                    view.getEvaluatedActionsCounts(),
                    view.getEvaluatedActionsCountsByMnemonic(),
                    timer.stop().elapsed(TimeUnit.MILLISECONDS),
                    view.getAndClearPkgManagerStatistics(),
                    env.getSkyframeExecutor().wasAnalysisCacheInvalidatedAndResetBit()
                )
            )
        val configurationKeys: com.google.common.collect.ImmutableSet<BuildConfigurationKey?> =
            java.util.stream.Stream.concat<T?>(
                analysisResult.getTargetsToBuild().stream()
                    .map(ConfiguredTarget::getConfigurationKey)
                    .distinct(),
                if (analysisResult.getTargetsToTest() == null)
                    java.util.stream.Stream.empty<Any?>()
                else
                    analysisResult.getTargetsToTest().stream()
                        .map(ConfiguredTarget::getConfigurationKey)
                        .distinct()
            )
                .filter(java.util.function.Predicate { obj: T? -> java.util.Objects.nonNull(obj) })
                .distinct()
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())
        val configurationMap: MutableMap<BuildConfigurationKey?, BuildConfigurationValue?> =
            env.getSkyframeExecutor().getConfigurations(env.getReporter(), configurationKeys)
        env.getEventBus()
            .post(
                TestFilteringCompleteEvent(
                    analysisResult.getTargetsToBuild(),
                    analysisResult.getTargetsToTest(),
                    analysisResult.getTargetsToSkip(),
                    configurationMap
                )
            )
        postTopLevelStatusEvents(env, analysisResult, configurationMap)

        return analysisResult
    }

    /** Post the appropriate [com.google.devtools.build.lib.skyframe.TopLevelStatusEvents].  */
    private fun postTopLevelStatusEvents(
        env: CommandEnvironment,
        analysisResult: AnalysisResult,
        configurationMap: MutableMap<BuildConfigurationKey?, BuildConfigurationValue?>
    ) {
        for (configuredTarget in analysisResult.getTargetsToBuild()) {
            env.getEventBus().post(TopLevelTargetAnalyzedEvent.create(configuredTarget))
            if (analysisResult.getTargetsToSkip().contains(configuredTarget)) {
                env.getEventBus().post(TopLevelTargetSkippedEvent.create(configuredTarget))
            }

            if (analysisResult.getTargetsToTest() != null
                && analysisResult.getTargetsToTest().contains(configuredTarget)
            ) {
                env.getEventBus()
                    .post(
                        TestAnalyzedEvent.create(
                            configuredTarget,
                            configurationMap.get(configuredTarget.getConfigurationKey()),  /* isSkipped= */
                            analysisResult.getTargetsToSkip().contains(configuredTarget)
                        )
                    )
            }
        }

        for (entry in analysisResult.getAspectsMap().entrySet()) {
            env.getEventBus().post(AspectAnalyzedEvent.create(entry.getKey(), entry.getValue()))
        }
    }

    fun reportTargetsWithTests(
        env: CommandEnvironment,
        targetsToBuild: MutableCollection<ConfiguredTarget?>,
        targetsToTest: MutableCollection<ConfiguredTarget?>
    ) {
        val testCount: Int = targetsToTest.size()
        val targetCount: Int = targetsToBuild.size() - testCount
        if (targetCount == 0) {
            env.getReporter()
                .handle(
                    com.google.devtools.build.lib.events.Event.info(
                        ("Found "
                                + testCount
                                + (if (testCount == 1) " test target..." else " test targets..."))
                    )
                )
        } else {
            env.getReporter()
                .handle(
                    com.google.devtools.build.lib.events.Event.info(
                        ("Found "
                                + targetCount
                                + (if (targetCount == 1) " target and " else " targets and ")
                                + testCount
                                + (if (testCount == 1) " test target..." else " test targets..."))
                    )
                )
        }
    }

    fun reportTargets(env: CommandEnvironment, targetsToBuild: MutableCollection<ConfiguredTarget?>) {
        val targetCount: Int = targetsToBuild.size()
        env.getReporter()
            .handle(
                com.google.devtools.build.lib.events.Event.info("Found " + targetCount + (if (targetCount == 1) " target..." else " targets..."))
            )
    }

    /**
     * Turns target patterns from the command line into parsed equivalents for single targets.
     * 
     * 
     * Globbing targets like ":all" and "..." are ignored here and will not be in the returned set.
     * 
     * @param env the action's environment.
     * @param requestedTargetPatterns the list of target patterns specified on the command line.
     * @param keepGoing --keep_going command line option.
     * @param loadingPhaseThreads no of threads to be used in execution.
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
            // doing it. The previous time is in runAnalysisPhase(). Still, if parsing does fail we
            // propagate the exception up.
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

    /** A simple container for storing processed evaluation results of the PROJECT.scl file.  */
    internal class ProjectEvaluationResult(
        buildOptions: com.google.common.collect.ImmutableSet<String?>?,
        activeDirectoriesMatcher: java.util.Optional<PathFragmentPrefixTrie?>?,
        projectFile: java.util.Optional<com.google.devtools.build.lib.cmdline.Label?>?
    ) {
        @AutoBuilder
        interface Builder {
            fun buildOptions(buildOptions: com.google.common.collect.ImmutableSet<String?>?): Builder?

            fun activeDirectoriesMatcher(activeDirectoriesMatcher: java.util.Optional<PathFragmentPrefixTrie?>?): Builder?

            fun projectFile(projectFile: java.util.Optional<com.google.devtools.build.lib.cmdline.Label?>?): Builder

            fun build(): ProjectEvaluationResult?
        }

        val buildOptions: com.google.common.collect.ImmutableSet<String?>?
        val activeDirectoriesMatcher: java.util.Optional<PathFragmentPrefixTrie?>?
        val projectFile: java.util.Optional<com.google.devtools.build.lib.cmdline.Label?>?

        init {
            this.projectFile = projectFile
            this.activeDirectoriesMatcher = activeDirectoriesMatcher
            this.buildOptions = buildOptions
            com.google.common.base.Preconditions.checkArgument(buildOptions != null, "buildOptions cannot be null.")
            com.google.common.base.Preconditions.checkArgument(
                activeDirectoriesMatcher != null,
                "activeDirectoriesMatcher cannot be null."
            )
            com.google.common.base.Preconditions.checkArgument(projectFile != null, "projectFile cannot be null.")
        }

        companion object {
            fun builder(): Builder {
                return AutoBuilder_AnalysisPhaseRunner_ProjectEvaluationResult_Builder()
            }
        }
    }

    internal enum class FeaturesUsingProjectFile {
        ANALYSIS_CACHING_UPLOAD,
        ANALYSIS_CACHING_DOWNLOAD,
        SCL_CONFIG,
        SKYFOCUS
    }
}
