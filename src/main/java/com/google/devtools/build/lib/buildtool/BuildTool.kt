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

import com.google.devtools.build.lib.actions.BuildFailedException

/**
 * Provides the bulk of the implementation of the 'blaze build' command.
 * 
 * 
 * The various concrete build command classes handle the command options and request setup, then
 * delegate the handling of the request (the building of targets) to this class.
 * 
 * 
 * The main entry point is [.buildTargets].
 * 
 * 
 * Most of analysis is handled in [com.google.devtools.build.lib.analysis.BuildView], and
 * execution in [ExecutionTool].
 */
class BuildTool @kotlin.jvm.JvmOverloads constructor(
    env: CommandEnvironment,
    postProcessor: AnalysisPostProcessor = NOOP_POST_PROCESSOR
) {
    /** Hook for inserting extra post-analysis-phase processing. Used for implementing {a,c}query.  */
    interface AnalysisPostProcessor {
        @Throws(java.lang.InterruptedException::class, ViewCreationFailedException::class, ExitException::class)
        fun process(
            request: BuildRequest?,
            env: CommandEnvironment?,
            runtime: BlazeRuntime?,
            analysisResult: AnalysisResult?
        )
    }

    private val env: CommandEnvironment
    private val runtime: BlazeRuntime
    private val analysisPostProcessor: AnalysisPostProcessor

    /**
     * Constructs a BuildTool.
     * 
     * @param env a reference to the command environment of the currently executing command
     */
    init {
        this.env = env
        this.runtime = env.getRuntime()
        this.analysisPostProcessor = postProcessor
    }

    /**
     * The crux of the build system: builds the targets specified in the request.
     * 
     * 
     * Performs loading, analysis and execution for the specified set of targets, honoring the
     * configuration options in the BuildRequest. Returns normally iff successful, throws an exception
     * otherwise.
     * 
     * 
     * Callers must ensure that [.stopRequest] is called after this method, even if it
     * throws.
     * 
     * 
     * The caller is responsible for setting up and syncing the package cache.
     * 
     * 
     * During this function's execution, the actualTargets and successfulTargets fields of the
     * request object are set.
     * 
     * @param request the build request that this build tool is servicing, which specifies various
     * options; during this method's execution, the actualTargets and successfulTargets fields of
     * the request object are populated
     * @param result the build result that is the mutable result of this build
     * @param validator target validator
     * @param optionsParser the [OptionsParser] that was used to parse the command line options.
     * Also used to parse the options applied by the project file.
     * @param targetsForProjectResolution if not null, the targets for which to perform project file
     * resolution. If null (the common behavior), derive the targets from the `request`
     * instead.
     */
    @Throws(
        BuildFailedException::class,
        java.lang.InterruptedException::class,
        ViewCreationFailedException::class,
        TargetParsingException::class,
        LoadingFailedException::class,
        AbruptExitException::class,
        InvalidConfigurationException::class,
        TestExecException::class,
        LabelSyntaxException::class,
        ExitException::class,
        PostExecutionDumpException::class,
        RepositoryMappingResolutionException::class,
        com.google.devtools.common.options.OptionsParsingException::class
    )
    fun buildTargets(
        request: BuildRequest,
        result: BuildResult,
        validator: TargetValidator?,
        optionsParser: com.google.devtools.common.options.OptionsParser,
        targetsForProjectResolution: MutableList<String?>?
    ) {
        com.google.devtools.build.lib.profiler.Profiler.instance().profile("validateOptions").use { c ->
            validateOptions(request)
        }
        var buildOptions: BuildOptions
        com.google.devtools.build.lib.profiler.Profiler.instance().profile("createBuildOptions").use { c ->
            buildOptions = runtime.createBuildOptions(request)
        }
        var analysisCachingDeps: RemoteAnalysisCachingDependenciesProvider? = null
        var analysisCacheReaderDeps: RemoteAnalysisCacheReaderDepsProvider? = null
        var serializationDependenciesProvider: SerializationDependenciesProvider? = null
        var catastrophe = false
        try {
            com.google.devtools.build.lib.profiler.Profiler.instance().profile("BuildStartingEvent").use { c ->
                env.getEventBus()
                    .post(BuildStartingEvent.Companion.create(env.getDirectories(), env.getOutputService(), request))
            }
            logger.atInfo().log("Build identifier: %s", request.getId())

            // Exit if there are any pending exceptions from modules.
            env.throwPendingException()

            initializeOutputFilter(request)

            val targetPatternPhaseValue: TargetPatternPhaseValue
            MemoryProfiler.instance().markPhase(com.google.devtools.build.lib.profiler.ProfilePhase.TARGET_PATTERN_EVAL)
            com.google.devtools.build.lib.profiler.Profiler.instance()
                .markPhase(com.google.devtools.build.lib.profiler.ProfilePhase.TARGET_PATTERN_EVAL)
            com.google.devtools.build.lib.profiler.Profiler.instance().profile("evaluateTargetPatterns").use { c ->
                targetPatternPhaseValue =
                    evaluateTargetPatterns(
                        env.getReporter(),
                        env.getSkyframeExecutor(),
                        env.getRelativeWorkingDirectory(),
                        request.getKeepGoing(),
                        request.getTargets(),
                        request.getLoadingOptions(),
                        request.getLoadingPhaseThreadCount(),
                        request.shouldRunTests(),
                        validator
                    )
            }
            env.getEventBus().post(ExecRootEvent(env.getExecRoot()))

            val targetPatternsForProjectResolution: TargetPatternPhaseValue? =
                if (targetsForProjectResolution == null)
                    targetPatternPhaseValue
                else
                    evaluateTargetPatterns(
                        com.google.devtools.build.lib.events.ExtendedEventHandler
                            .NOOP,  // Don't report this because it'll throw off our tracking of the
                        // complete target set.
                        env.getSkyframeExecutor(),
                        env.getRelativeWorkingDirectory(),
                        request.getKeepGoing(),
                        targetsForProjectResolution,
                        request.getLoadingOptions(),
                        request.getLoadingPhaseThreadCount(),
                        request.shouldRunTests(),
                        validator
                    )

            val optionDefinitions: com.google.common.collect.ImmutableSet<com.google.devtools.common.options.OptionDefinition> =
                optionsParser.getOptionsSortedByCategory().values().stream()
                    .flatMap<com.google.devtools.common.options.OptionDefinition?>(java.util.function.Function { obj: MutableList<com.google.devtools.common.options.OptionDefinition?>? -> obj.stream() })
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<com.google.devtools.common.options.OptionDefinition?>())
            val allOptionNames: com.google.common.collect.ImmutableSet.Builder<String?> =
                com.google.common.collect.ImmutableSet.builder<String?>()
            for (optionDefinition in optionDefinitions) {
                allOptionNames.add(optionDefinition.getOptionName())
                // --no[flag_name] is a valid flag only if [flag_name] is a boolean flag.
                if (optionDefinition.usesBooleanValueSyntax()) {
                    allOptionNames.add("no" + optionDefinition.getOptionName())
                }
            }
            val projectEvaluationResult: ProjectEvaluationResult =
                AnalysisPhaseRunner.evaluateProjectFile(
                    request,
                    buildOptions,
                    allOptionNames.build(),
                    request.getUserOptions(),
                    targetPatternsForProjectResolution,
                    env
                )

            if (!projectEvaluationResult.buildOptions.isEmpty()) {
                // First parse the native options from the project file.
                optionsParser.parse(
                    com.google.devtools.common.options.OptionPriority.PriorityCategory.COMMAND_LINE,
                    projectEvaluationResult.projectFile.get().toString(),
                    projectEvaluationResult.buildOptions.stream()
                        .filter(java.util.function.Predicate { o: String? ->
                            com.google.devtools.common.options.OptionsParser.STARLARK_SKIPPED_PREFIXES.stream()
                                .noneMatch(java.util.function.Predicate { prefix: String? -> o.startsWith(prefix) })
                        })
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())
                )
                // Then parse the starlark options from the project file.
                val buildSettingLoader: BuildSettingLoader = SkyframeExecutorTargetLoader(env)
                val starlarkOptionsParser: StarlarkOptionsParser =
                    StarlarkOptionsParser.builder()
                        .buildSettingLoader(buildSettingLoader)
                        .nativeOptionsParser(optionsParser)
                        .build()
                com.google.common.base.Preconditions.checkState(
                    starlarkOptionsParser.parseGivenArgs(
                        java.util.stream.Stream.concat<String?>(
                            projectEvaluationResult.buildOptions.stream()
                                .filter(
                                    java.util.function.Predicate { o: String? ->
                                        com.google.devtools.common.options.OptionsParser.STARLARK_SKIPPED_PREFIXES.stream()
                                            .anyMatch(java.util.function.Predicate { prefix: String? ->
                                                o.startsWith(
                                                    prefix
                                                )
                                            })
                                    }),
                            optionsParser.getSkippedArgs().stream()
                        )
                            .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())
                    )
                )

                env.getEventBus()
                    .post(
                        CanonicalCommandLineEvent(
                            runtime.getProductName(),
                            runtime.getStartupOptionsProvider(),
                            request.getCommandName(),
                            optionsParser.getResidue(),
                            optionsParser
                                .getOptions<BuildEventProtocolOptions?>(BuildEventProtocolOptions::class.java)
                                .getIncludeResidueInRunBepEvent(),
                            optionsParser.getExplicitCommandLineStarlarkOptions(),
                            optionsParser.getStarlarkOptions(),
                            optionsParser.getStarlarkOptionsAllowingMultiple(),
                            optionsParser.asListOfCanonicalOptions(),  // This replaces the tentative CanonicalCommandLineEvent posted earlier in the
                            // build in BlazeCommandDispatcher.
                            /* replaceable= */
                            false
                        )
                    )
                env.getEventBus().post(UpdateOptionsEvent(optionsParser))
            } else {
                // No PROJECT.scl flag updates. Release the original CanonicalCommandLineEvent for posting.
                env.getEventBus()
                    .post(
                        ReleaseReplaceableBuildEvent(
                            BuildEventIdUtil.structuredCommandlineId(
                                CommandLineEvent.CanonicalCommandLineEvent.LABEL
                            )
                        )
                    )
            }
            buildOptions = runtime.createBuildOptions(optionsParser)
            if (request.needsInstrumentationFilter()) {
                applyHeuristicInstrumentationFilter(buildOptions, targetPatternPhaseValue)
            }
            val analysisDeps: AnalysisDeps =
                RemoteAnalysisCacheFactory.create(
                    env,
                    projectEvaluationResult.activeDirectoriesMatcher,
                    targetPatternPhaseValue.getTargetLabels(),
                    BuildView.getTopLevelConfigurationTrimmedOfTestOptions(
                        buildOptions, env.getReporter()
                    ),
                    request.getUserOptions(),
                    projectEvaluationResult.buildOptions
                )
            analysisCachingDeps = analysisDeps.deps
            analysisCacheReaderDeps = analysisDeps.readerDeps
            serializationDependenciesProvider = analysisDeps.serializationDeps

            if (env.withMergedAnalysisAndExecutionSourceOfTruth()) {
                // a.k.a. Skymeld.
                buildTargetsWithMergedAnalysisExecution(
                    request,
                    result,
                    targetPatternPhaseValue,
                    buildOptions,
                    analysisCachingDeps,
                    analysisCacheReaderDeps
                )
            } else {
                buildTargetsWithoutMergedAnalysisExecution(
                    request,
                    result,
                    targetPatternPhaseValue,
                    buildOptions,
                    analysisCachingDeps,
                    analysisCacheReaderDeps
                )
            }

            if (analysisCacheReaderDeps.mode().serializesValues()) {
                com.google.common.base.Preconditions.checkState(!analysisCachingDeps.bailedOut())
                serializeValues(serializationDependenciesProvider)
            }

            if (env.getSkyframeExecutor().getSkyfocusState().enabled) {
                // Skyfocus only works at the end of a successful build.
                val topLevelTargets: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?> =
                    result.getActualTargets().stream()
                        .map<Any?>(ConfiguredTarget::getLabel)
                        .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())
                env.getSkyframeExecutor()
                    .runSkyfocus(
                        topLevelTargets,
                        projectEvaluationResult.activeDirectoriesMatcher,
                        env.getReporter(),
                        env.getBlazeWorkspace().getPersistentActionCache(),
                        env.getOptions()
                    )
            }
        } catch (e: java.lang.Error) {
            // Don't handle the error here. We will do so in stopRequest.
            catastrophe = true
            throw e
        } catch (e: java.lang.RuntimeException) {
            catastrophe = true
            throw e
        } finally {
            if (!catastrophe) {
                // Delete dirty nodes to ensure that they do not accumulate indefinitely.
                val versionWindow: Long = request.getViewOptions().getVersionWindowForDirtyNodeGc()
                if (versionWindow != -1L) {
                    env.getSkyframeExecutor().deleteOldNodes(versionWindow)
                }
                // The workspace status actions will not run with certain flags, or if an error occurs early
                // in the build. Ensure that build info is posted on every build.
                env.ensureBuildInfoPosted()

                // Log stats and sync state even on failure.
                if (analysisCachingDeps != null) {
                    if (analysisCacheReaderDeps.mode() == RemoteAnalysisCacheMode.DOWNLOAD
                        && (analysisCacheReaderDeps.shouldBailOutOnMissingFingerprint()
                                || analysisCachingDeps.bailedOut())
                    ) {
                        reportOnlyBailOutReason(analysisCacheReaderDeps)
                    } else {
                        logAnalysisCachingStats(analysisCacheReaderDeps)
                    }
                }
            }
        }
    }

    @Throws(java.lang.InterruptedException::class, InvalidConfigurationException::class)
    private fun applyHeuristicInstrumentationFilter(
        buildOptions: BuildOptions, targetPatternPhaseValue: TargetPatternPhaseValue
    ) {
        com.google.devtools.build.lib.profiler.Profiler.instance().profile("Compute instrumentation filter").use { c ->
            val instrumentationFilter: String =
                InstrumentationFilterSupport.computeInstrumentationFilter(
                    env.getReporter(),  // TODO(ulfjack): Expensive. Make this part of the TargetPatternPhaseValue or write
                    // a new SkyFunction to compute it?
                    targetPatternPhaseValue.getTestsToRun(env.getReporter(), env.getPackageManager())
                )
            try {
                // We're modifying the buildOptions in place, which is not ideal, but we also don't want
                // to pay the price for making a copy. Maybe reconsider later if this turns out to be a
                // problem (and the performance loss may not be a big deal). Notably, one must not call
                // .checksum() before mutating the BuildOptions instance, lest the checksum and the option
                // values get out of sync.
                buildOptions
                    .get(CoreOptions::class.java)
                    .setInstrumentationFilter(
                        RegexFilterConverter().convert(instrumentationFilter)
                    )
            } catch (e: com.google.devtools.common.options.OptionsParsingException) {
                throw InvalidConfigurationException(Code.HEURISTIC_INSTRUMENTATION_FILTER_INVALID, e)
            }
        }
    }

    @Throws(
        BuildFailedException::class,
        ViewCreationFailedException::class,
        AbruptExitException::class,
        RepositoryMappingResolutionException::class,
        java.lang.InterruptedException::class,
        InvalidConfigurationException::class,
        TestExecException::class,
        ExitException::class,
        PostExecutionDumpException::class
    )
    private fun buildTargetsWithoutMergedAnalysisExecution(
        request: BuildRequest,
        result: BuildResult,
        targetPatternPhaseValue: TargetPatternPhaseValue?,
        buildOptions: BuildOptions?,
        remoteAnalysisCachingDeps: RemoteAnalysisCachingDependenciesProvider?,
        remoteAnalysisCacheReaderDeps: RemoteAnalysisCacheReaderDepsProvider?
    ) {
        var analysisResult: AnalysisResult =
            AnalysisPhaseRunner.execute(
                env,
                request,
                targetPatternPhaseValue,
                buildOptions,
                remoteAnalysisCachingDeps,
                remoteAnalysisCacheReaderDeps
            )
        var executionTool: ExecutionTool? = null
        try {
            // We cannot move the executionTool down to the execution phase part since it does set up the
            // symlinks for tools.
            // TODO(twerth): Extract embedded tool setup from execution tool and move object creation to
            // execution phase.
            executionTool = ExecutionTool(env, request)
            if (request.getBuildOptions().getPerformAnalysisPhase()) {
                if (!analysisResult.getExclusiveTests().isEmpty()
                    && executionTool.getTestActionContext().forceExclusiveTestsInParallel()
                ) {
                    val testStrategy: String? =
                        request.getOptions<ExecutionOptions?>(ExecutionOptions::class.java).getTestStrategy()
                    for (test in analysisResult.getExclusiveTests()) {
                        this.reporter
                            .handle(
                                com.google.devtools.build.lib.events.Event.warn(
                                    (test.getLabel()
                                            + " is tagged exclusive, but --test_strategy="
                                            + testStrategy
                                            + " forces parallel test execution.")
                                )
                            )
                    }
                    analysisResult = analysisResult.withExclusiveTestsAsParallelTests()
                }
                if (!analysisResult.getExclusiveIfLocalTests().isEmpty()
                    && executionTool.getTestActionContext().forceExclusiveIfLocalTestsInParallel()
                ) {
                    analysisResult = analysisResult.withExclusiveIfLocalTestsAsParallelTests()
                }

                result.setBuildConfiguration(analysisResult.getConfiguration())
                result.setActualTargets(analysisResult.getTargetsToBuild())
                result.setTestTargets(analysisResult.getTargetsToTest())

                com.google.devtools.build.lib.profiler.Profiler.instance().profile("analysisPostProcessor.process")
                    .use { c ->
                        analysisPostProcessor.process(request, env, runtime, analysisResult)
                    }
                if (needsExecutionPhase(request.getBuildOptions())) {
                    com.google.devtools.build.lib.profiler.Profiler.instance().profile("ExecutionTool.init")
                        .use { closeable ->
                            executionTool.init()
                        }
                    executionTool.executeBuild(
                        request.getId(),
                        analysisResult,
                        result,
                        analysisResult.getPackageRoots(),
                        request.getTopLevelArtifactContext()
                    )
                } else {
                    env.getReporter().post(NoExecutionEvent())
                }
                val delayedFailureDetail: FailureDetail? = analysisResult.getFailureDetail()
                if (delayedFailureDetail != null) {
                    throw BuildFailedException(
                        delayedFailureDetail.getMessage(), DetailedExitCode.of(delayedFailureDetail)
                    )
                }

                // Only run this post-build step for builds with SequencedSkyframeExecutor. Enabling the
                // aquery dump format feature will disable Skymeld, so it only runs in the non-Skymeld path.
                if ((env.getSkyframeExecutor() is SequencedSkyframeExecutor)
                    && request.getBuildOptions().getAqueryDumpAfterBuildFormat() != null
                ) {
                    try {
                        com.google.devtools.build.lib.profiler.Profiler.instance().profile("postExecutionDumpSkyframe")
                            .use { c ->
                                dumpSkyframeStateAfterBuild(
                                    request.getOptions<BuildEventProtocolOptions?>(BuildEventProtocolOptions::class.java),
                                    request.getBuildOptions().getAqueryDumpAfterBuildFormat(),
                                    request.getBuildOptions().getAqueryDumpAfterBuildOutputFile()
                                )
                            }
                    } catch (e: CommandLineExpansionException) {
                        throw PostExecutionDumpException(e)
                    } catch (e: IOException) {
                        throw PostExecutionDumpException(e)
                    } catch (e: TemplateExpansionException) {
                        throw PostExecutionDumpException(e)
                    } catch (e: InvalidAqueryOutputFormatException) {
                        throw PostExecutionDumpException(
                            "--skyframe_state must be used with "
                                    + "--output=proto|streamed_proto|textproto|jsonproto.",
                            e
                        )
                    }
                }
            }
        } finally {
            if (executionTool != null) {
                executionTool.shutdown()
            }
        }
    }

    /** Performs the merged analysis and execution phase.  */
    @Throws(
        java.lang.InterruptedException::class,
        AbruptExitException::class,
        ViewCreationFailedException::class,
        BuildFailedException::class,
        TestExecException::class,
        InvalidConfigurationException::class,
        RepositoryMappingResolutionException::class
    )
    private fun buildTargetsWithMergedAnalysisExecution(
        request: BuildRequest,
        result: BuildResult,
        targetPatternPhaseValue: TargetPatternPhaseValue?,
        buildOptions: BuildOptions?,
        remoteAnalysisCachingDependenciesProvider: RemoteAnalysisCachingDependenciesProvider?,
        remoteAnalysisCacheReaderDeps: RemoteAnalysisCacheReaderDepsProvider?
    ) {
        // See https://github.com/bazelbuild/rules_nodejs/issues/3693.
        env.getSkyframeExecutor().clearSyscallCache()

        var hasCatastrophe = false

        val executionTool: ExecutionTool = ExecutionTool(env, request)
        // This timer measures time from the first execution activity to the last.
        val executionTimer: com.google.common.base.Stopwatch = com.google.common.base.Stopwatch.createUnstarted()

        // TODO(b/199053098): implement support for --nobuild.
        var analysisAndExecutionResult: AnalysisAndExecutionResult? = null
        var buildCompleted = false
        try {
            analysisAndExecutionResult =
                AnalysisAndExecutionPhaseRunner.execute(
                    env,
                    request,
                    buildOptions,
                    targetPatternPhaseValue,
                    ExecutionSetup { executionTool.prepareForExecution(executionTimer) },
                    BuildConfigurationsCreated { configuration: BuildConfigurationValue? ->
                        result.setBuildConfiguration(
                            configuration
                        )
                    },
                    object : BuildDriverKeyTestContext() {
                        val testStrategy: String?
                            get() = request.getOptions<ExecutionOptions?>(ExecutionOptions::class.java)
                                .getTestStrategy()

                        override fun forceExclusiveTestsInParallel(): Boolean {
                            return executionTool.getTestActionContext().forceExclusiveTestsInParallel()
                        }

                        override fun forceExclusiveIfLocalTestsInParallel(): Boolean {
                            return executionTool
                                .getTestActionContext()
                                .forceExclusiveIfLocalTestsInParallel()
                        }
                    },
                    remoteAnalysisCachingDependenciesProvider,
                    remoteAnalysisCacheReaderDeps
                )
            buildCompleted = true

            // This value is null when there's no analysis.
            if (analysisAndExecutionResult == null) {
                return
            }
        } catch (e: InvalidConfigurationException) {
            // These are non-catastrophic.
            buildCompleted = true
            throw e
        } catch (e: RepositoryMappingResolutionException) {
            buildCompleted = true
            throw e
        } catch (e: ViewCreationFailedException) {
            buildCompleted = true
            throw e
        } catch (e: BuildFailedException) {
            buildCompleted = true
            throw e
        } catch (e: TestExecException) {
            buildCompleted = true
            throw e
        } catch (e: java.lang.Error) {
            // These are catastrophic.
            hasCatastrophe = true
            throw e
        } catch (e: java.lang.RuntimeException) {
            hasCatastrophe = true
            throw e
        } finally {
            if (result.getBuildConfiguration() != null) {
                // We still need to do this even in case of an exception.
                result.setConvenienceSymlinks(
                    executionTool.handleConvenienceSymlinks(
                        env.getBuildResultListener().getAnalyzedTargets(), result.getBuildConfiguration()
                    )
                )
            }
            executionTool.unconditionalExecutionPhaseFinalizations(
                executionTimer, env.getSkyframeExecutor()
            )

            // For the --noskymeld code path, this is done after the analysis phase.
            val buildResultListener: BuildResultListener = env.getBuildResultListener()
            result.setActualTargets(buildResultListener.getAnalyzedTargets())
            result.setTestTargets(buildResultListener.getAnalyzedTests())

            if (!hasCatastrophe) {
                executionTool.nonCatastrophicFinalizations(
                    result,
                    env.getBlazeWorkspace().getPersistentActionCache(),  /* explanationHandler= */
                    null,
                    buildCompleted
                )
            }
        }

        // This is the --keep_going code path: Time to throw the delayed exceptions.
        // Keeping legacy behavior: for execution errors, keep the message of the BuildFailedException
        // empty.
        if (analysisAndExecutionResult.getExecutionDetailedExitCode() != null) {
            throw BuildFailedException(
                null, analysisAndExecutionResult.getExecutionDetailedExitCode()
            )
        }

        val delayedFailureDetail: FailureDetail? = analysisAndExecutionResult.getFailureDetail()
        if (delayedFailureDetail != null) {
            throw BuildFailedException(
                delayedFailureDetail.getMessage(), DetailedExitCode.of(delayedFailureDetail)
            )
        }
    }

    @Throws(PostExecutionDumpException::class, java.lang.InterruptedException::class)
    private fun dumpSkyframeMemory(
        buildResult: BuildResult, bepOptions: BuildEventProtocolOptions, format: String
    ) {
        if (!env.getSkyframeExecutor().tracksStateForIncrementality()) {
            throw PostExecutionDumpException(
                "Skyframe memory dump requested, but incremental state is not tracked", null
            )
        }

        var reportTransient = true
        var reportConfiguration = true
        var reportPrecomputed = true
        var reportWorkspaceStatus = true

        for (flag in com.google.common.base.Splitter.on(",").split(format)) {
            when (flag) {
                "json" -> {}
                "notransient" -> reportTransient = false
                "noconfig" -> reportConfiguration = false
                "noprecomputed" -> reportPrecomputed = false
                "noworkspacestatus" -> reportWorkspaceStatus = false
                else -> throw PostExecutionDumpException("Unknown flag: '" + flag + "'", null)
            }
        }

        try {
            val outputStream: java.io.OutputStream?
            var streamingContext: UploadContext? = null

            if (bepOptions.getStreamingLogFileUploads()) {
                streamingContext =
                    runtime
                        .getBuildEventArtifactUploaderFactoryMap()
                        .select(bepOptions.getBuildEventUploadStrategy())
                        .create(env)
                        .startUpload(LocalFileType.PERFORMANCE_LOG, null)
                outputStream = streamingContext.getOutputStream()
                buildResult
                    .getBuildToolLogCollection()
                    .addUriFuture(SKYFRAME_MEMORY_DUMP_FILE, streamingContext.uriFuture())
            } else {
                val localPath: com.google.devtools.build.lib.vfs.Path = env.getOutputBase().getRelative(
                    SKYFRAME_MEMORY_DUMP_FILE
                )
                outputStream = localPath.getOutputStream()
                buildResult.getBuildToolLogCollection().addLocalFile(SKYFRAME_MEMORY_DUMP_FILE, localPath)
            }

            PrintStream(outputStream).use { printStream ->
                val dumper: SkyframeMemoryDumper =
                    SkyframeMemoryDumper(
                        com.google.devtools.build.lib.buildtool.SkyframeMemoryDumper.DisplayMode.SUMMARY,
                        null,
                        runtime.getRuleClassProvider(),
                        env.getSkyframeExecutor().getEvaluator().getInMemoryGraph(),
                        reportTransient,
                        reportConfiguration,
                        reportPrecomputed,
                        reportWorkspaceStatus
                    )
                dumper.dumpFull(printStream)
            }
        } catch (e: IOException) {
            throw PostExecutionDumpException("cannot write Skyframe dump: " + e.getMessage(), e)
        } catch (e: DumpFailedException) {
            throw PostExecutionDumpException("cannot write Skyframe dump: " + e.getMessage(), e)
        }
    }

    /**
     * Produces an aquery dump of the state of Skyframe.
     * 
     * 
     * There are 2 possible output channels: a local file or a remote FS.
     */
    @Throws(
        CommandLineExpansionException::class,
        IOException::class,
        InvalidAqueryOutputFormatException::class,
        TemplateExpansionException::class
    )
    private fun dumpSkyframeStateAfterBuild(
        besOptions: BuildEventProtocolOptions?,
        format: String,
        outputFilePathFragment: PathFragment?
    ) {
        com.google.common.base.Preconditions.checkState(env.getSkyframeExecutor() is SequencedSkyframeExecutor)

        var streamingContext: UploadContext? = null
        var localOutputFilePath: com.google.devtools.build.lib.vfs.Path? = null
        val outputFileName: String?

        if (outputFilePathFragment == null) {
            outputFileName = getDefaultOutputFileName(format)
            if (besOptions != null && besOptions.getStreamingLogFileUploads()) {
                streamingContext =
                    runtime
                        .getBuildEventArtifactUploaderFactoryMap()
                        .select(besOptions.getBuildEventUploadStrategy())
                        .create(env)
                        .startUpload(LocalFileType.PERFORMANCE_LOG,  /* inputSupplier= */null)
            } else {
                localOutputFilePath = env.getOutputBase().getRelative(outputFileName)
            }
        } else {
            localOutputFilePath = env.getOutputBase().getRelative(outputFilePathFragment)
            outputFileName = localOutputFilePath.getBaseName()
        }

        if (localOutputFilePath != null) {
            this.reporter.handle(com.google.devtools.build.lib.events.Event.info("Writing aquery dump to " + localOutputFilePath))
            this.reporter
                .post(StartingAqueryDumpAfterBuildEvent(localOutputFilePath, outputFileName))
        } else {
            this.reporter.handle(com.google.devtools.build.lib.events.Event.info("Streaming aquery dump."))
            this.reporter.post(StartingAqueryDumpAfterBuildEvent(streamingContext, outputFileName))
        }

        initOutputStream(streamingContext, localOutputFilePath).use { outputStream ->
            PrintStream(outputStream).use { printStream ->
                ActionGraphProtoOutputFormatterCallback.constructAqueryOutputHandler(
                    AqueryOutputHandler.OutputType.fromString(format), outputStream, printStream
                ).use { aqueryOutputHandler ->
                    // These options are fixed for simplicity. We'll add more configurability if the need arises.
                    val actionGraphDump: ActionGraphDump =
                        ActionGraphDump( /* includeActionCmdLine= */
                            false,  /* includeArtifacts= */
                            true,  /* includePrunedInputs= */
                            true,  /* actionFilters= */
                            null,  /* includeParamFiles= */
                            false,  /* includeFileWriteContents= */
                            false,
                            aqueryOutputHandler,
                            this.reporter
                        )
                    AqueryProcessor.Companion.dumpActionGraph(env, aqueryOutputHandler, actionGraphDump)
                }
            }
        }
    }

    private fun reportExceptionError(e: java.lang.Exception) {
        if (e.getMessage() != null) {
            this.reporter.handle(com.google.devtools.build.lib.events.Event.error(e.getMessage()))
        }
    }

    fun processRequest(
        request: BuildRequest,
        validator: TargetValidator?,
        options: com.google.devtools.common.options.OptionsParsingResult?
    ): BuildResult {
        return processRequest(
            request,
            validator,  /* postBuildCallback= */
            null,
            options,  /* targetsForProjectResolution= */
            null
        )
    }

    /**
     * The crux of the build system. Builds the targets specified in the request using the specified
     * Executor.
     * 
     * 
     * Performs loading, analysis and execution for the specified set of targets, honoring the
     * configuration options in the BuildRequest. Returns normally iff successful, throws an exception
     * otherwise.
     * 
     * 
     * The caller is responsible for setting up and syncing the package cache.
     * 
     * 
     * During this function's execution, the actualTargets and successfulTargets fields of the
     * request object are set.
     * 
     * @param request the build request that this build tool is servicing, which specifies various
     * options; during this method's execution, the actualTargets and successfulTargets fields of
     * the request object are populated
     * @param validator an optional target validator
     * @param postBuildCallback an optional callback called after the build has been completed
     * successfully.
     * @param options the options parsing result containing the options parsed so far, excluding those
     * from flagsets. This will be cast to an [OptionsParser] in order to add any options
     * from flagsets.
     * @return the result as a [BuildResult] object
     */
    fun processRequest(
        request: BuildRequest,
        validator: TargetValidator?,
        postBuildCallback: PostBuildCallback?,
        options: com.google.devtools.common.options.OptionsParsingResult?,
        targetsForProjectResolution: MutableList<String?>?
    ): BuildResult {
        val result: BuildResult = BuildResult(request.getStartTime())
        maybeSetStopOnFirstFailure(request, result)
        var crash: Throwable? = null
        var detailedExitCode: DetailedExitCode? = null
        try {
            com.google.devtools.build.lib.profiler.Profiler.instance().profile("buildTargets").use { c ->
                // This OptionsParsingResult is essentially a wrapper around the OptionsParser in
                // https://github.com/bazelbuild/bazel/blob/master/src/main/java/com/google/devtools/build/lib/runtime/BlazeCommandDispatcher.java#L341. Casting it back to
                // an OptionsParser is safe, and necessary in order to add any options from flagsets.
                buildTargets(
                    request,
                    result,
                    validator,
                    options as com.google.devtools.common.options.OptionsParser?,
                    targetsForProjectResolution
                )
            }
            detailedExitCode = DetailedExitCode.success()
            if (postBuildCallback != null) {
                try {
                    com.google.devtools.build.lib.profiler.Profiler.instance().profile("postBuildCallback.process")
                        .use { c ->
                            result.setPostBuildCallbackFailureDetail(
                                postBuildCallback.process(result.getSuccessfulTargets())
                            )
                        }
                } catch (e: java.lang.InterruptedException) {
                    detailedExitCode =
                        InterruptedFailureDetails.detailedExitCode("post build callback interrupted")
                }
            }

            if (env.getSkyframeExecutor() is SequencedSkyframeExecutor
                && request.getBuildOptions().getSkyframeMemoryDump() != null
            ) {
                com.google.devtools.build.lib.profiler.Profiler.instance().profile("BuildTool.dumpSkyframeMemory")
                    .use { c ->
                        dumpSkyframeMemory(
                            result,
                            request.getOptions<BuildEventProtocolOptions?>(BuildEventProtocolOptions::class.java),
                            request.getBuildOptions().getSkyframeMemoryDump()
                        )
                    }
            }
        } catch (e: BuildFailedException) {
            if (!e.isErrorAlreadyShown()) {
                // The actual error has not already been reported by the Builder.
                // TODO(janakr): This is wrong: --keep_going builds with errors don't have a message in
                //  this BuildFailedException, so any error message that is only reported here will be
                //  missing for --keep_going builds. All error reporting should be done at the site of the
                //  error, if only for clearer behavior.
                reportExceptionError(e)
            }
            if (e.isCatastrophic()) {
                result.setCatastrophe()
            }
            detailedExitCode = e.getDetailedExitCode()
        } catch (e: java.lang.InterruptedException) {
            // We may have been interrupted by an error, or the user's interruption may have raced with
            // an error, so check to see if we should report that error code instead.
            detailedExitCode = env.getRuntime().getCrashExitCode()
            val environmentPendingAbruptExitException: AbruptExitException? = env.getPendingException()
            if (detailedExitCode == null && environmentPendingAbruptExitException != null) {
                detailedExitCode = environmentPendingAbruptExitException.getDetailedExitCode()
                // Report the exception from the environment - the exception we're handling here is just an
                // interruption.
                reportExceptionError(environmentPendingAbruptExitException)
            }
            if (detailedExitCode == null) {
                val message = "build interrupted"
                detailedExitCode = InterruptedFailureDetails.detailedExitCode(message)
                env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
                env.getEventBus().post(BuildInterruptedEvent())
            } else {
                result.setCatastrophe()
            }
        } catch (e: TargetParsingException) {
            detailedExitCode = e.getDetailedExitCode()
            reportExceptionError(e)
        } catch (e: LoadingFailedException) {
            detailedExitCode = e.getDetailedExitCode()
            reportExceptionError(e)
        } catch (e: RepositoryMappingResolutionException) {
            detailedExitCode = e.getDetailedExitCode()
            reportExceptionError(e)
        } catch (e: ViewCreationFailedException) {
            detailedExitCode = DetailedExitCode.of(ExitCode.PARSING_FAILURE, e.getFailureDetail())
            reportExceptionError(e)
        } catch (e: ExitException) {
            detailedExitCode = e.getDetailedExitCode()
            reportExceptionError(e)
        } catch (e: TestExecException) {
            // ExitCode.SUCCESS means that build was successful. Real return code of program
            // is going to be calculated in TestCommand.doTest().
            detailedExitCode = DetailedExitCode.success()
            reportExceptionError(e)
        } catch (e: InvalidConfigurationException) {
            detailedExitCode = e.getDetailedExitCode()
            reportExceptionError(e)
            // TODO(gregce): With "global configurations" we cannot tie a configuration creation failure
            // to a single target and have to halt the entire build. Once configurations are genuinely
            // created as part of the analysis phase they should report their error on the level of the
            // target(s) that triggered them.
            result.setCatastrophe()
        } catch (e: AbruptExitException) {
            detailedExitCode = e.getDetailedExitCode()
            reportExceptionError(e)
            result.setCatastrophe()
        } catch (e: PostExecutionDumpException) {
            detailedExitCode =
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(e.getMessage())
                        .setActionQuery(
                            ActionQuery.newBuilder()
                                .setCode(ActionQuery.Code.SKYFRAME_STATE_AFTER_EXECUTION)
                                .build()
                        )
                        .build()
                )
            reportExceptionError(e)
        } catch (throwable: Throwable) {
            crash = throwable
            detailedExitCode = CrashFailureDetails.detailedExitCodeForThrowable(crash)
            com.google.common.base.Throwables.throwIfUnchecked(throwable)
            throw java.lang.IllegalStateException(throwable)
        } finally {
            if (detailedExitCode == null) {
                detailedExitCode =
                    CrashFailureDetails.detailedExitCodeForThrowable(
                        java.lang.IllegalStateException("Unspecified DetailedExitCode")
                    )
            }
            com.google.devtools.build.lib.profiler.Profiler.instance().profile("stopRequest").use { c ->
                stopRequest(result, crash, detailedExitCode)
            }
        }

        return result
    }

    private fun reportRemoteAnalysisServiceStats(
        fingerprintValueService: FingerprintValueService,
        analysisCacheClient: RemoteAnalysisCacheClient?
    ) {
        val fvsStats: FingerprintValueStore.Stats = fingerprintValueService.getStats()
        val raccStats: RemoteAnalysisCacheClient.Stats? =
            if (analysisCacheClient == null)
                RemoteAnalysisCacheClient.EMPTY_STATS
            else
                analysisCacheClient.getStats()
        env.getRemoteAnalysisCachingEventListener().recordServiceStats(fvsStats, raccStats)
    }

    @Throws(java.lang.InterruptedException::class)
    private fun reportOnlyBailOutReason(readerDeps: RemoteAnalysisCacheReaderDepsProvider) {
        val remoteAnalysisCacheClient: RemoteAnalysisCacheClient? = readerDeps.getAnalysisCacheClient()
        if (remoteAnalysisCacheClient == null) {
            return
        }
        env.getRemoteAnalysisCachingEventListener()
            .recordServiceStats( /* fvsStats= */
                FingerprintValueStore.EMPTY_STATS,
                remoteAnalysisCacheClient.getStats()
            )
    }

    /**
     * Handles post-build analysis caching operations.
     * 
     * 
     *  1. If this is a cache-writing build, then this will serialize and upload the frontier
     * Skyframe values.
     *  1. If this is a cache-reading build, then this will report the cache hit stats while
     * downloading the frontier Skyframe values during analysis.
     * 
     */
    @Throws(java.lang.InterruptedException::class)
    private fun logAnalysisCachingStats(dependenciesProvider: RemoteAnalysisCacheReaderDepsProvider) {
        if (env.getSkyframeExecutor() !is SequencedSkyframeExecutor) {
            return
        }

        when (dependenciesProvider.mode()) {
            RemoteAnalysisCacheMode.UPLOAD -> reportRemoteAnalysisServiceStats(
                dependenciesProvider.getFingerprintValueService(),
                dependenciesProvider.getAnalysisCacheClient()
            )

            RemoteAnalysisCacheMode.DOWNLOAD -> {
                reportRemoteAnalysisServiceStats(
                    dependenciesProvider.getFingerprintValueService(),
                    dependenciesProvider.getAnalysisCacheClient()
                )
                reportRemoteAnalysisCachingStats()
                env.getSkyframeExecutor()
                    .syncRemoteAnalysisCachingState(
                        env.getRemoteAnalysisCachingEventListener().getSkyValueVersion(),
                        env.getRemoteAnalysisCachingEventListener().getClientId()
                    )
            }

            RemoteAnalysisCacheMode.DUMP_UPLOAD_MANIFEST_ONLY, RemoteAnalysisCacheMode.OFF -> {}
        }
    }

    @Throws(java.lang.InterruptedException::class)
    private fun tryWriteSkycacheMetadata(
        serializationDependenciesProvider: SerializationDependenciesProvider
    ) {
        var message: String? = "No local crash but the RPC failed in the backend"
        var success = false
        val skycacheMetadataParams: SkycacheMetadataParams? =
            env.getBlazeWorkspace().remoteAnalysisCachingServicesSupplier().getSkycacheMetadataParams()
        if (skycacheMetadataParams == null
            || !env.getOptions()
                .getOptions<RemoteAnalysisCachingOptions?>(RemoteAnalysisCachingOptions::class.java)
                .getAnalysisCacheEnableMetadataQueries()
        ) {
            return
        }
        try {
            com.google.devtools.build.lib.profiler.Profiler.instance().profile("skycache.metadata.upload").use { c ->
                // This is a blocking call. We cannot finish the build until the metadata has been written
                // and at this point there is nothing else to do in the build that could be done in
                // parallel.
                val metadataWriter: RemoteAnalysisMetadataWriter? =
                    serializationDependenciesProvider.getMetadataWriter()
                if (metadataWriter == null) {
                    message = "MetadataAnalysisCacheWriterService is unavailable"
                } else {
                    success =
                        metadataWriter.addTopLevelTargets(
                            env.getCommandId().toString(),
                            skycacheMetadataParams.getEvaluatingVersion(),
                            skycacheMetadataParams.getConfigurationHash(),
                            skycacheMetadataParams.getUseFakeStampData(),
                            skycacheMetadataParams.getBazelVersion(),
                            skycacheMetadataParams.getTargets(),
                            skycacheMetadataParams.getConfigFlags()
                        )
                }
            }
        } catch (e: IOException) {
            // To avoid build failures for a UX-enhancing feature, errors writing build metadata do not
            // cause the build to fail. Instead, we log the error and rely on external monitoring to
            // detect issues with metadata writes.
            message = e.getMessage()
        }
        if (success) {
            env.getReporter()
                .handle(com.google.devtools.build.lib.events.Event.info("Skycache: Successfully wrote metadata to backend"))
        } else {
            env.getReporter()
                .handle(
                    com.google.devtools.build.lib.events.Event.warn(
                        "Skycache: Failed to write metadata to backend"
                                + (if (message != null) ": " + message else "")
                    )
                )
        }
    }

    /** Initializes the output filter to the value given with `--output_filter`.  */
    private fun initializeOutputFilter(request: BuildRequest) {
        val outputFilterOption: com.google.devtools.common.options.RegexPatternOption? =
            request.getBuildOptions().getOutputFilter()
        if (outputFilterOption != null) {
            this.reporter
                .setOutputFilter(
                    com.google.devtools.build.lib.events.OutputFilter.RegexOutputFilter.forPattern(outputFilterOption.regexPattern())
                )
        }
    }

    /**
     * Stops processing the specified request.
     * 
     * 
     * This logs the build result, cleans up and stops the clock.
     * 
     * @param result result to update
     * @param crash any unexpected [RuntimeException] or [Error], may be null
     * @param detailedExitCode describes the exit code and an optional detailed failure value to add
     * to `result`
     */
    fun stopRequest(
        result: BuildResult, crash: Throwable?, detailedExitCode: DetailedExitCode
    ) {
        com.google.common.base.Preconditions.checkState((crash == null) || !detailedExitCode.isSuccess())
        result.setUnhandledThrowable(crash)
        result.setDetailedExitCode(detailedExitCode)
        if (!detailedExitCode.isSuccess()) {
            logger.atInfo().log(
                "Unsuccessful command ended with FailureDetail: %s", detailedExitCode.getFailureDetail()
            )
        }

        var ie: java.lang.InterruptedException? = null

        // The stop time has to be captured before we send the BuildCompleteEvent.
        result.setStopTime(runtime.getClock().currentTimeMillis())

        // Skip the build complete events so that modules can run blazeShutdownOnCrash without thinking
        // that the build completed normally. BlazeCommandDispatcher will call handleCrash.
        if (crash == null) {
            try {
                MemoryProfiler.instance().markPhase(com.google.devtools.build.lib.profiler.ProfilePhase.FINISH)
                com.google.devtools.build.lib.profiler.Profiler.instance()
                    .markPhase(com.google.devtools.build.lib.profiler.ProfilePhase.FINISH)
            } catch (e: java.lang.InterruptedException) {
                env.getReporter()
                    .handle(com.google.devtools.build.lib.events.Event.error("Build interrupted during command completion"))
                ie = e
            }

            env.getEventBus()
                .post(
                    BuildCompleteEvent(
                        result,
                        com.google.common.collect.ImmutableList.of<BuildEventId?>(
                            BuildEventIdUtil.buildToolLogs(), BuildEventIdUtil.buildMetrics()
                        )
                    )
                )
        }
        // Post the build tool logs event; the corresponding local files may be contributed from
        // modules, and this has to happen after posting the BuildCompleteEvent because that's when
        // modules add their data to the collection.
        env.getEventBus().post(result.getBuildToolLogCollection().freeze().toEvent())
        if (ie != null) {
            if (detailedExitCode.isSuccess()) {
                result.setDetailedExitCode(
                    InterruptedFailureDetails.detailedExitCode(
                        "Build interrupted during command completion"
                    )
                )
            } else if (detailedExitCode.getExitCode() != ExitCode.INTERRUPTED) {
                logger.atWarning().withCause(ie).log(
                    "Suppressed interrupted exception during stop request because already failing with: %s",
                    detailedExitCode
                )
            }
        }
    }

    /**
     * Validates the options for this BuildRequest.
     * 
     * 
     * Issues warnings for the use of deprecated options, and warnings or errors for any option
     * settings that conflict.
     */
    @com.google.common.annotations.VisibleForTesting
    fun validateOptions(request: BuildRequest) {
        for (issue in request.validateOptions()) {
            this.reporter.handle(com.google.devtools.build.lib.events.Event.warn(issue))
        }
    }

    private val reporter: com.google.devtools.build.lib.events.Reporter?
        get() = env.getReporter()

    /** Describes a failure that isn't severe enough to halt the command in keep_going mode.  */ // TODO(mschaller): consider promoting this to be a sibling of AbruptExitException.
    class ExitException internal constructor(detailedExitCode: DetailedExitCode) : java.lang.Exception(
        com.google.common.base.Preconditions.checkNotNull<Any?>(
            detailedExitCode.getFailureDetail(),
            "failure detail"
        ).getMessage()
    ) {
        private val detailedExitCode: DetailedExitCode

        init {
            this.detailedExitCode = detailedExitCode
        }

        fun getDetailedExitCode(): DetailedExitCode {
            return detailedExitCode
        }
    }

    @Throws(java.lang.InterruptedException::class, AbruptExitException::class)
    private fun serializeValues(
        serializationDependenciesProvider: SerializationDependenciesProvider
    ) {
        if (env.getSkyframeExecutor() !is SequencedSkyframeExecutor) {
            return
        }

        com.google.common.base.Preconditions.checkState(serializationDependenciesProvider.mode().serializesValues())

        com.google.devtools.build.lib.profiler.Profiler.instance().profile("serializeAndUploadFrontier")
            .use { closeable ->
                val maybeFailureDetail: java.util.Optional<FailureDetail?> =
                    FrontierSerializer.serializeAndUploadFrontier(
                        serializationDependenciesProvider,
                        env.getSkyframeExecutor().getEvaluator(),
                        env.getVersionGetter(),
                        env.getReporter(),
                        env.getEventBus(),
                        env.getOptions()
                            .getOptions<KeepStateAfterBuildOption?>(KeepStateAfterBuildOption::class.java)
                            .getKeepStateAfterBuild()
                    )
                if (maybeFailureDetail.isPresent()) {
                    throw AbruptExitException(DetailedExitCode.of(maybeFailureDetail.get()))
                }
            }
        if (serializationDependenciesProvider.mode() == RemoteAnalysisCacheMode.UPLOAD) {
            tryWriteSkycacheMetadata(serializationDependenciesProvider)
        }
    }

    private fun reportRemoteAnalysisCachingStats() {
        val listener: RemoteAnalysisCachingEventListener = env.getRemoteAnalysisCachingEventListener()
        val hitsByFunction: com.google.common.collect.ImmutableMap<SkyFunctionName?, AtomicLong?> =
            listener.getHitsBySkyFunctionName()
        val missesByFunction: com.google.common.collect.ImmutableMap<SkyFunctionName?, AtomicLong?> =
            listener.getMissesBySkyFunctionName()
        val totalHits: Long = hitsByFunction.values().stream()
            .mapToLong(java.util.function.ToLongFunction { obj: AtomicLong? -> obj.get() }).sum()
        val totalMisses: Long = missesByFunction.values().stream()
            .mapToLong(java.util.function.ToLongFunction { obj: AtomicLong? -> obj.get() }).sum()
        val totalRequests = totalHits + totalMisses

        com.google.common.base.Preconditions.checkState(totalRequests >= 0, "totalRequests should be non-negative")
        if (totalRequests == 0L) {
            // Don't report stats if there were no requests.
            return
        }

        // Combine keys from both maps
        val allFunctionNames: MutableSet<SkyFunctionName?> =
            com.google.common.collect.Sets.union<SkyFunctionName?>(hitsByFunction.keySet(), missesByFunction.keySet())
        // Format the stats per function, sorted alphabetically by function name
        val statsByFunction: String? =
            allFunctionNames.stream()
                .sorted(java.util.Comparator.comparing<SkyFunctionName?, String?>(java.util.function.Function { obj: SkyFunctionName? -> obj.getName() }))
                .map<String?>(
                    java.util.function.Function { functionName: SkyFunctionName? ->
                        val hits: Long = hitsByFunction.getOrDefault(functionName, AtomicLong(0)).get()
                        val misses: Long =
                            missesByFunction.getOrDefault(functionName, AtomicLong(0)).get()
                        val functionTotal = hits + misses
                        val functionHitRate =
                            if (functionTotal == 0L) 0.0 else hits.toDouble() / functionTotal * 100
                        java.lang.String.format(
                            "%s: %d/%d (%.2f%%)",
                            functionName.getName(), hits, functionTotal, functionHitRate
                        )
                    })
                .collect(Collectors.joining(", "))

        val fvsStats: FingerprintValueStore.Stats = listener.getFingerprintValueStoreStats()
        var bytesReceived: Long = fvsStats.valueBytesReceived
        var requests: Long = fvsStats.entriesFound + fvsStats.entriesNotFound

        val raccStats: RemoteAnalysisCacheClient.Stats = listener.getRemoteAnalysisCacheStats()
        bytesReceived += raccStats.bytesReceived
        requests += raccStats.requestsSent

        // totalRequests is already checked to be non-zero above.
        val overallHitRate = totalHits.toDouble() / totalRequests * 100
        env.getReporter()
            .handle(
                com.google.devtools.build.lib.events.Event.info(
                    java.lang.String.format(
                        "Skycache stats: %s received in %s requests, %s/%s cache"
                                + " hits (%.2f%%) [Breakdown: %s]",
                        formatBytes(bytesReceived),
                        requests,
                        totalHits,
                        totalRequests,
                        overallHitRate,
                        statsByFunction
                    )
                )
            )
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private const val SKYFRAME_MEMORY_DUMP_FILE = "skyframe_memory.json"

        private val NOOP_POST_PROCESSOR: AnalysisPostProcessor =
            AnalysisPostProcessor { unusedRequest: BuildRequest?, unusedEnv: CommandEnvironment?, unusedRuntime: BlazeRuntime?, unusedAnalysisResult: AnalysisResult? -> }

        @Throws(LoadingFailedException::class, TargetParsingException::class, java.lang.InterruptedException::class)
        private fun evaluateTargetPatterns(
            reporter: com.google.devtools.build.lib.events.ExtendedEventHandler?,
            skyframeExecutor: SkyframeExecutor,
            relativeWorkingDirectory: PathFragment?,
            keepGoing: Boolean,
            targets: MutableList<String?>?,
            loadingOptions: LoadingOptions,
            loadingPhaseThreadCount: Int,
            shouldRunTests: Boolean,
            validator: TargetValidator?
        ): TargetPatternPhaseValue {
            val result: TargetPatternPhaseValue =
                skyframeExecutor.loadTargetPatternsWithFilters(
                    reporter,
                    targets,
                    relativeWorkingDirectory,
                    loadingOptions,
                    loadingPhaseThreadCount,
                    keepGoing,
                    shouldRunTests
                )
            if (validator != null) {
                val targetLabels: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.packages.Target?> =
                    result.getTargets(reporter, skyframeExecutor.getPackageManager())
                validator.validateTargets(targetLabels, keepGoing)
            }
            return result
        }

        private fun getDefaultOutputFileName(format: String): String {
            return when (format) {
                "proto" -> "aquery_dump.proto"
                "streamed_proto" -> "aquery_dump.pb"
                "textproto" -> "aquery_dump.textproto"
                "jsonproto" -> "aquery_dump.json"
                else -> throw java.lang.IllegalArgumentException("Unsupported format type: " + format)
            }
        }

        @Throws(IOException::class)
        private fun initOutputStream(
            streamingContext: UploadContext?, outputFilePath: com.google.devtools.build.lib.vfs.Path
        ): java.io.OutputStream {
            if (streamingContext != null) {
                return BufferedOutputStream(streamingContext.getOutputStream())
            }
            return BufferedOutputStream(outputFilePath.getOutputStream())
        }

        private fun maybeSetStopOnFirstFailure(request: BuildRequest, result: BuildResult) {
            if (shouldStopOnFailure(request)) {
                result.setStopOnFirstFailure(true)
            }
        }

        private fun shouldStopOnFailure(request: BuildRequest): Boolean {
            return !(request.getKeepGoing() && request.getExecutionOptions().getTestKeepGoing())
        }

        private fun needsExecutionPhase(options: BuildRequestOptions): Boolean {
            return options.getPerformAnalysisPhase() && options.getPerformExecutionPhase()
        }

        /** Returns the project directories found in a project file.  */
        @Throws(InvalidConfigurationException::class)
        fun getActiveDirectoriesMatcher(
            projectFile: com.google.devtools.build.lib.cmdline.Label?,
            skyframeExecutor: SkyframeExecutor,
            eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler?
        ): PathFragmentPrefixTrie {
            val key: com.google.devtools.build.lib.skyframe.ProjectValue.Key =
                com.google.devtools.build.lib.skyframe.ProjectValue.Key(projectFile)
            val result: EvaluationResult<SkyValue?> =
                skyframeExecutor.evaluateSkyKeys(
                    eventHandler,
                    com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.skyframe.ProjectValue.Key?>(
                        key
                    ),  /* keepGoing= */
                    false
                )

            if (result.hasError()) {
                // InvalidConfigurationException is chosen for convenience, and it's distinguished from
                // the other InvalidConfigurationException cases by Code.INVALID_PROJECT.
                throw InvalidConfigurationException(
                    "unexpected error reading project configuration: " + result.getError(),
                    Code.INVALID_PROJECT
                )
            }

            try {
                return PathFragmentPrefixTrie.of(
                    (result.get(key) as ProjectValue).getDefaultProjectDirectories()
                )
            } catch (e: PathFragmentPrefixTrieException) {
                throw InvalidConfigurationException(
                    "Active directories configuration error: " + e.getMessage(), Code.INVALID_PROJECT
                )
            }
        }

        /** Formats a number of bytes in a human-readable prefixed format.  */
        private fun formatBytes(bytes: Long): String? {
            val k = 1024
            if (bytes < k) {
                return bytes.toString() + " B"
            }
            val exponent: Int = (java.lang.Math.log(bytes.toDouble()) / java.lang.Math.log(k.toDouble())).toInt()
            val prefixedUnit = "KMGTPE".charAt(exponent - 1).toString() + "B"
            return java.lang.String.format(
                "%.2f %s",
                bytes / java.lang.Math.pow(k.toDouble(), exponent.toDouble()),
                prefixedUnit
            )
        }
    }
}
