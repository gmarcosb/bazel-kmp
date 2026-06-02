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

import com.google.devtools.build.lib.actions.Action

/**
 * This class manages the execution phase. The entry point is [.executeBuild].
 * 
 * 
 * This is only intended for use by [BuildTool].
 * 
 * 
 * This class contains an ActionCache, and refers to the Blaze Runtime's BuildView and
 * PackageCache.
 * 
 * 
 * Lifetime of an instance: 1 invocation.
 * 
 * @see BuildTool
 * 
 * @see com.google.devtools.build.lib.analysis.BuildView
 */
class ExecutionTool internal constructor(env: CommandEnvironment, request: BuildRequest) {
    private val env: CommandEnvironment
    private val runtime: BlazeRuntime
    private val request: BuildRequest
    private var executor: BlazeExecutor? = null
    private val prefetcher: ActionInputPrefetcher?
    private val executorLifecycleListeners: com.google.common.collect.ImmutableSet<ExecutorLifecycleListener>

    private val spawnStrategyRegistry: SpawnStrategyRegistry
    private val actionContextRegistry: ModuleActionContextRegistry

    private var actionCacheChecker: ActionCacheChecker? = null
    private val actionExecutionSalt: String?

    private var informedOutputServiceToStartTheBuild = false
    private var incrementalPackageRoots: IncrementalPackageRoots? = null

    init {
        this.env = env
        this.runtime = env.getRuntime()
        this.request = request

        try {
            env.getExecRoot().createDirectoryAndParents()
        } catch (e: IOException) {
            throw createExitException("Execroot creation failed", Code.EXECROOT_CREATION_FAILURE, e)
        }

        val executorBuilder: ExecutorBuilder = ExecutorBuilder()
        val actionContextRegistryBuilder: com.google.devtools.build.lib.exec.ModuleActionContextRegistry.Builder =
            ModuleActionContextRegistry.builder()
        val spawnStrategyRegistryBuilder: com.google.devtools.build.lib.exec.SpawnStrategyRegistry.Builder =
            SpawnStrategyRegistry.builder(env.getInvocationPolicy().getStrategyPolicy())
        actionContextRegistryBuilder.register<T?>(SpawnStrategyResolver::class.java, SpawnStrategyResolver())

        for (module in runtime.getBlazeModules()) {
            com.google.devtools.build.lib.profiler.Profiler.instance().profile(module.toString() + ".executorInit")
                .use { ignored ->
                    module.executorInit(env, request, executorBuilder)
                }
            com.google.devtools.build.lib.profiler.Profiler.instance()
                .profile(module.toString() + ".registerActionContexts").use { ignored ->
                    module.registerActionContexts(actionContextRegistryBuilder, env, request)
                }
            com.google.devtools.build.lib.profiler.Profiler.instance()
                .profile(module.toString() + ".registerSpawnStrategies").use { ignored ->
                    module.registerSpawnStrategies(spawnStrategyRegistryBuilder, env)
                }
        }
        actionContextRegistryBuilder.register<T?>(
            SymlinkTreeActionContext::class.java,
            SymlinkTreeStrategy(env.getOutputService(), env.getWorkspaceName())
        )
        // TODO(philwo) - the ExecutionTool should not add arbitrary dependencies on its own, instead
        // these dependencies should be added to the ActionContextConsumer of the module that actually
        // depends on them.
        actionContextRegistryBuilder
            .restrictTo(WorkspaceStatusAction.Context::class.java, "")
            .restrictTo(SymlinkTreeActionContext::class.java, "")

        this.prefetcher = executorBuilder.getActionInputPrefetcher()
        this.executorLifecycleListeners = executorBuilder.getExecutorLifecycleListeners()
        this.actionExecutionSalt = executorBuilder.getActionExecutionSalt()

        // There are many different SpawnActions, and we want to control the action context they use
        // independently from each other, for example, to run genrules locally and Java compile action
        // in prod. Thus, for SpawnActions, we decide the action context to use not only based on the
        // context class, but also the mnemonic of the action.
        val options: ExecutionOptions? = request.getOptions<ExecutionOptions?>(ExecutionOptions::class.java)
        // TODO(jmmv): This should live in some testing-related Blaze module, not here.
        actionContextRegistryBuilder.restrictTo(TestActionContext::class.java, options.getTestStrategy())

        val spawnStrategyRegistry: SpawnStrategyRegistry = spawnStrategyRegistryBuilder.build()
        actionContextRegistryBuilder.register<T?>(SpawnStrategyRegistry::class.java, spawnStrategyRegistry)
        actionContextRegistryBuilder.register<T?>(DynamicStrategyRegistry::class.java, spawnStrategyRegistry)
        actionContextRegistryBuilder.register<T?>(RemoteLocalFallbackRegistry::class.java, spawnStrategyRegistry)

        this.actionContextRegistry = actionContextRegistryBuilder.build()
        this.spawnStrategyRegistry = spawnStrategyRegistry
    }

    @Throws(AbruptExitException::class)
    fun getExecutor(): Executor? {
        if (executor == null) {
            executor = createExecutor()
            for (executorLifecycleListener in executorLifecycleListeners) {
                executorLifecycleListener.executorCreated()
            }
        }
        return executor
    }

    /** Creates an executor for the current set of blaze runtime, execution options, and request.  */
    private fun createExecutor(): BlazeExecutor {
        return BlazeExecutor(
            runtime.getFileSystem(),
            env.getExecRoot(),
            this.reporter,
            runtime.getClock(),
            runtime.getBugReporter(),
            request,
            actionContextRegistry,
            spawnStrategyRegistry
        )
    }

    @Throws(AbruptExitException::class)
    fun init() {
        getExecutor()
    }

    fun shutdown() {
        for (executorLifecycleListener in executorLifecycleListeners) {
            executorLifecycleListener.executionPhaseEnding()
        }
    }

    val testActionContext: TestActionContext?
        get() = actionContextRegistry.getContext<T?>(TestActionContext::class.java)

    /**
     * Sets up for execution.
     * 
     * 
     * This method concentrates the setup steps for execution, which were previously scattered over
     * several classes. We need this in order to merge analysis & execution phases.
     * 
     * 
     * TODO(b/213040766): Write tests for these setup steps.
     */
    @Throws(
        AbruptExitException::class,
        BuildFailedException::class,
        java.lang.InterruptedException::class,
        InvalidConfigurationException::class
    )
    fun prepareForExecution(executionTimer: com.google.common.base.Stopwatch) {
        init()
        val buildRequestOptions: BuildRequestOptions = request.getBuildOptions()
        val skyframeExecutor: SkyframeExecutor = env.getSkyframeExecutor()

        com.google.devtools.build.lib.profiler.Profiler.instance().profile("preparingExecroot").use { c ->
            incrementalPackageRoots =
                IncrementalPackageRoots.createAndRegisterToEventBus(
                    this.execRoot,  // Single package path is a Skymeld prerequisite.
                    com.google.common.collect.Iterables.getOnlyElement<Root?>(env.getPackageLocator().getPathEntries()),
                    env.getEventBus(),
                    env.getDirectories().getProductName() + "-",
                    skyframeExecutor.getIgnoredPaths(),
                    request
                        .getOptions<BuildLanguageOptions?>(BuildLanguageOptions::class.java)
                        .getExperimentalSiblingRepositoryLayout(),
                    runtime.getWorkspace().doesAllowExternalRepositories()
                )
            incrementalPackageRoots.eagerlyPlantSymlinksToSingleSourceRoot()
            env.getSkyframeBuildView()
                .getArtifactFactory()
                .setPackageRoots(incrementalPackageRoots.getPackageRootLookup())
        }
        val outputService: OutputService? = env.getOutputService()
        val modifiedOutputFiles: ModifiedFileSet? =
            startBuildAndDetermineModifiedOutputFiles(request.getId(), outputService)
        if (outputService.actionFileSystemType().supportsLocalActions()) {
            // Must be created after the output path is created above.
            com.google.devtools.build.lib.profiler.Profiler.instance().profile("createActionLogDirectory").use { c ->
                createActionLogDirectory(outputService.bulkDeleter())
            }
        }

        var actionCache: ActionCache? = null
        if (buildRequestOptions.getUseActionCache()) {
            com.google.devtools.build.lib.profiler.Profiler.instance().profile("load/reset action cache").use { c ->
                actionCache = this.orLoadActionCache
                actionCache.resetStatistics()
            }
        }
        val skyframeBuilder: SkyframeBuilder?
        com.google.devtools.build.lib.profiler.Profiler.instance().profile("createBuilder").use { c ->
            skyframeBuilder =
                createBuilder(request, actionCache, skyframeExecutor, modifiedOutputFiles) as SkyframeBuilder
        }
        actionCacheChecker = skyframeBuilder.getActionCacheChecker()

        skyframeExecutor.drainChangedFiles()

        val outputChecker: OutputChecker? =
            if (env.getOutputService() != null)
                env.getOutputService().getOutputChecker()
            else
                OutputChecker.TRUST_LOCAL_ONLY
        skyframeExecutor.detectModifiedOutputFiles(
            modifiedOutputFiles,
            env.getBlazeWorkspace().getLastExecutionTimeRange(),
            outputChecker,
            buildRequestOptions.getFsvcThreads()
        )
        com.google.devtools.build.lib.profiler.Profiler.instance().profile("configureActionExecutor").use { c ->
            skyframeExecutor.configureActionExecutor(
                skyframeBuilder.getFileCache(),
                skyframeBuilder.getActionInputPrefetcher(),
                actionExecutionSalt,
                env.getOptions().getOptions<UiOptions?>(UiOptions::class.java).getMaxStdoutErrBytes()
            )
        }
        skyframeExecutor.setSaltAndDeleteActionsIfChanged(actionExecutionSalt)
        com.google.devtools.build.lib.profiler.Profiler.instance().profile("prepareSkyframeActionExecutorForExecution")
            .use { c ->
                skyframeExecutor.prepareSkyframeActionExecutorForExecution(
                    env.getReporter(),
                    executor,
                    request,
                    skyframeBuilder.getActionCacheChecker(),
                    skyframeBuilder.getActionOutputDirectoryHelper()
                )
            }
        env.getEventBus()
            .register(
                ExecutionProgressReceiverSetup(
                    skyframeExecutor,
                    env,
                    executionTimer,
                    buildRequestOptions.getProgressReportInterval()
                )
            )
        for (executorLifecycleListener in executorLifecycleListeners) {
            com.google.devtools.build.lib.profiler.Profiler.instance()
                .profile(executorLifecycleListener.toString() + ".executionPhaseStarting").use { c ->
                    executorLifecycleListener.executionPhaseStarting(
                        null, java.util.function.Supplier { null }, skyframeExecutor.getEphemeralCheckIfOutputConsumed()
                    )
                }
        }
        com.google.devtools.build.lib.profiler.Profiler.instance().profile("configureResourceManager").use { c ->
            configureResourceManager(env.getLocalResourceManager(), request)
        }
        announceEnteringDirIfEmacs()
    }

    /**
     * Performs the execution phase (phase 3) of the build, in which the Builder is applied to the
     * action graph to bring the targets up to date. (This function will return prior to
     * execution-proper if --nobuild was specified.)
     * 
     * @param buildId UUID of the build id
     * @param analysisResult the analysis phase output
     * @param buildResult the mutable build result
     * @param packageRoots package roots collected from loading phase and [     ] creation. May be empty if using virtual roots.
     */
    @Throws(
        BuildFailedException::class,
        java.lang.InterruptedException::class,
        TestExecException::class,
        AbruptExitException::class
    )
    fun executeBuild(
        buildId: UUID?,
        analysisResult: AnalysisResult,
        buildResult: BuildResult,
        packageRoots: PackageRoots,
        topLevelArtifactContext: TopLevelArtifactContext?
    ) {
        val timer: com.google.common.base.Stopwatch = com.google.common.base.Stopwatch.createStarted()
        prepare(packageRoots)

        val actionGraph: ActionGraph? = analysisResult.getActionGraph()

        val outputService: OutputService? = env.getOutputService()
        val modifiedOutputFiles: ModifiedFileSet? =
            startBuildAndDetermineModifiedOutputFiles(buildId, outputService)

        if (outputService.actionFileSystemType().supportsLocalActions()) {
            // Must be created after the output path is created above.
            createActionLogDirectory(outputService.bulkDeleter())
        }

        buildResult.setConvenienceSymlinks(
            handleConvenienceSymlinks(
                analysisResult.getTargetsToBuild(), analysisResult.getConfiguration()
            )
        )

        val options: BuildRequestOptions = request.getBuildOptions()
        var actionCache: ActionCache? = null
        if (options.getUseActionCache()) {
            actionCache = this.orLoadActionCache
            actionCache.resetStatistics()
        }
        val skyframeExecutor: SkyframeExecutor = env.getSkyframeExecutor()
        val builder: com.google.devtools.build.lib.skyframe.Builder?
        com.google.devtools.build.lib.profiler.Profiler.instance().profile("createBuilder").use { c ->
            builder = createBuilder(request, actionCache, skyframeExecutor, modifiedOutputFiles)
        }
        //
        // Execution proper.  All statements below are logically nested in
        // begin/end pairs.  No early returns or exceptions please!
        //
        val configuredTargets: MutableCollection<ConfiguredTarget?>? = buildResult.getActualTargets()
        com.google.devtools.build.lib.profiler.Profiler.instance().profile("ExecutionStartingEvent").use { c ->
            env.getEventBus().post(ExecutionStartingEvent(configuredTargets))
        }
        this.reporter.handle(com.google.devtools.build.lib.events.Event.progress("Building..."))

        // Conditionally record dependency-checker log:
        val explanationHandler =
            installExplanationHandler(
                request.getBuildOptions().getExplanationPath(), request.getOptionsDescription()
            )

        announceEnteringDirIfEmacs()

        var catastrophe: Throwable? = null
        var buildCompleted = false
        try {
            var shouldDiscardAnalysisCache =
                request.getViewOptions().getDiscardAnalysisCache()
                        || !skyframeExecutor.tracksStateForIncrementality()
            if (shouldDiscardAnalysisCache) {
                if (skyframeExecutor
                        .getRemoteAnalysisCacheReaderDepsProvider()
                        .mode()
                        .isRetrievalEnabled()
                ) {
                    // When remote analysis value retrieval is enabled, it is possible for analysis to occur
                    // during the logical execution phase. Discarding the analysis cache can lead to crashes.
                    //
                    // TODO: b/466388360 - consider alternatives
                    this.reporter
                        .handle(
                            com.google.devtools.build.lib.events.Event.warn(
                                "Remote analysis caching is enabled. Not discarding the analysis cache."
                            )
                        )
                    shouldDiscardAnalysisCache = false
                }
            }
            if (shouldDiscardAnalysisCache) {
                // Free memory by removing cache entries that aren't going to be needed.
                com.google.devtools.build.lib.profiler.Profiler.instance().profile("clearAnalysisCache").use { c ->
                    env.getSkyframeBuildView()
                        .clearAnalysisCache(
                            analysisResult.getTargetsToBuild(), analysisResult.getAspectsMap().keySet()
                        )
                }
            }

            for (executorLifecycleListener in executorLifecycleListeners) {
                com.google.devtools.build.lib.profiler.Profiler.instance()
                    .profile(executorLifecycleListener.toString() + ".executionPhaseStarting").use { c ->
                        executorLifecycleListener.executionPhaseStarting(
                            actionGraph,  // If this supplier is ever consumed by more than one ActionContextProvider, it can be
                            // pulled out of the loop and made a memoizing supplier.
                            java.util.function.Supplier { TopLevelArtifactHelper.findAllTopLevelArtifacts(analysisResult) },  /* ephemeralCheckIfOutputConsumed= */
                            null
                        )
                    }
            }
            skyframeExecutor.drainChangedFiles()

            com.google.devtools.build.lib.profiler.Profiler.instance().profile("configureResourceManager").use { c ->
                configureResourceManager(env.getLocalResourceManager(), request)
            }
            MemoryProfiler.instance().markPhase(com.google.devtools.build.lib.profiler.ProfilePhase.EXECUTE)
            com.google.devtools.build.lib.profiler.Profiler.instance()
                .markPhase(com.google.devtools.build.lib.profiler.ProfilePhase.EXECUTE)
            val outputChecker: OutputChecker? =
                if (env.getOutputService() != null)
                    env.getOutputService().getOutputChecker()
                else
                    OutputChecker.TRUST_LOCAL_ONLY
            builder.buildArtifacts(
                env.getReporter(),
                analysisResult.getArtifactsToBuild(),
                analysisResult.getParallelTests(),
                com.google.common.collect.Sets.union<E?>(
                    analysisResult.getExclusiveTests(),
                    analysisResult.getExclusiveIfLocalTests()
                ),
                analysisResult.getTargetsToBuild(),
                analysisResult.getTargetsToSkip(),
                analysisResult.getAspectsMap().keySet(),
                executor,
                request,
                env.getBlazeWorkspace().getLastExecutionTimeRange(),
                topLevelArtifactContext,
                outputChecker
            )
            buildCompleted = true
        } catch (e: BuildFailedException) {
            buildCompleted = true
            throw e
        } catch (e: TestExecException) {
            buildCompleted = true
            throw e
        } catch (e: java.lang.Error) {
            catastrophe = e
        } catch (e: java.lang.RuntimeException) {
            catastrophe = e
        } finally {
            unconditionalExecutionPhaseFinalizations(timer, skyframeExecutor)

            if (catastrophe != null) {
                com.google.common.base.Throwables.throwIfUnchecked(catastrophe)
            }
            // NOTE: No finalization activities below will run in the event of a catastrophic error!
            nonCatastrophicFinalizations(buildResult, actionCache, explanationHandler, buildCompleted)
        }
    }

    @Throws(BuildFailedException::class, AbruptExitException::class, java.lang.InterruptedException::class)
    private fun startBuildAndDetermineModifiedOutputFiles(
        buildId: UUID?, outputService: OutputService
    ): ModifiedFileSet? {
        val modifiedOutputFiles: ModifiedFileSet
        com.google.devtools.build.lib.profiler.Profiler.instance().profile("outputService.startBuild").use { c ->
            modifiedOutputFiles =
                outputService.startBuild(
                    buildId,
                    env.getWorkspaceName(),
                    env.getReporter(),
                    request.getBuildOptions().getFinalizeActions()
                )
            informedOutputServiceToStartTheBuild = true
        }
        if (!request.getPackageOptions().getCheckOutputFiles()) {
            // Do not skip output invalidation if the output tree is empty: this can happen after it's
            // cleaned or corrupted.
            if (!modifiedOutputFiles.treatEverythingAsDeleted()) {
                return ModifiedFileSet.NOTHING_MODIFIED
            }
        }
        return modifiedOutputFiles
    }

    private fun announceEnteringDirIfEmacs() {
        if (request.isRunningInEmacs()) {
            // The syntax of this message is tightly constrained by lisp/progmodes/compile.el in emacs
            request
                .getOutErr()
                .printErrLn(runtime.getProductName() + ": Entering directory `" + this.execRoot + "/'")
        }
    }

    private fun announceLeavingDirIfEmacs() {
        if (request.isRunningInEmacs()) {
            request
                .getOutErr()
                .printErrLn(runtime.getProductName() + ": Leaving directory `" + this.execRoot + "/'")
        }
    }

    /** These steps get performed after execution, if there's no catastrophic exception.  */
    @Throws(BuildFailedException::class, AbruptExitException::class, java.lang.InterruptedException::class)
    fun nonCatastrophicFinalizations(
        buildResult: BuildResult,
        actionCache: ActionCache?,
        explanationHandler: ExplanationHandler?,
        buildCompleted: Boolean
    ) {
        env.recordLastExecutionTime()

        announceLeavingDirIfEmacs()
        if (buildCompleted) {
            this.reporter.handle(com.google.devtools.build.lib.events.Event.progress("Building complete."))
        }

        if (buildCompleted) {
            saveActionCache(actionCache)
        }

        val buildResultListener: BuildResultListener = env.getBuildResultListener()
        com.google.devtools.build.lib.profiler.Profiler.instance().profile("Show results").use { c ->
            buildResult.setSuccessfulTargets(
                determineSuccessfulTargets(
                    buildResultListener.getAnalyzedTargets(), buildResultListener.getBuiltTargets()
                )
            )
            buildResult.setSuccessfulAspects(
                determineSuccessfulAspects(
                    buildResultListener.getAnalyzedAspects().keySet(),
                    buildResultListener.getBuiltAspects()
                )
            )
            buildResult.setSkippedTargets(buildResultListener.getSkippedTargets())
            val buildResultPrinter: BuildResultPrinter = BuildResultPrinter(env)
            buildResultPrinter.showBuildResult(
                request,
                buildResult,
                buildResultListener.getAnalyzedTargets(),
                buildResultListener.getSkippedTargets(),
                buildResultListener.getAnalyzedAspects()
            )
        }
        if (explanationHandler != null) {
            uninstallExplanationHandler(explanationHandler)
            try {
                explanationHandler.close()
            } catch (ignored: IOException) {
                // Ignored
            }
        }
        // Finalize the output service last if required, so that if we do throw an exception, we know
        // that all the other code has already run.
        if (informedOutputServiceToStartTheBuild) {
            val isBuildSuccessful =
                (buildResult.getSuccessfulTargets().size()
                        == buildResultListener.getAnalyzedTargets().size())
            env.getOutputService().finalizeBuild(isBuildSuccessful)
        }
    }

    /**
     * These steps get performed after the end of execution, regardless of whether there's a
     * catastrophe or not.
     */
    fun unconditionalExecutionPhaseFinalizations(
        executionTimer: com.google.common.base.Stopwatch, skyframeExecutor: SkyframeExecutor
    ) {
        // These may flush logs, which may help if there is a catastrophic failure.
        for (executorLifecycleListener in executorLifecycleListeners) {
            executorLifecycleListener.executionPhaseEnding()
        }
        if (incrementalPackageRoots != null) {
            incrementalPackageRoots.shutdown()
        }

        // Handlers process these events and others (e.g. CommandCompleteEvent), even in the event of
        // a catastrophic failure. Posting these is consistent with other behavior.
        env.getEventBus().post(skyframeExecutor.createExecutionFinishedEvent())

        // With Skymeld, the timer is started with the first execution activity in the build and ends
        // when the build is done. A running timer indicates that some execution activity happened.
        //
        // Sometimes there's no execution in the build: e.g. when there's only 1 target, and we fail at
        // the analysis phase. In such a case, we shouldn't send out this event. This is consistent with
        // the noskymeld behavior.
        if (executionTimer.isRunning()) {
            env.getEventBus()
                .post(ExecutionPhaseCompleteEvent(executionTimer.stop().elapsed().toMillis()))
        }
    }

    @Throws(AbruptExitException::class, java.lang.InterruptedException::class)
    private fun prepare(packageRoots: PackageRoots) {
        // Prepare for build.
        MemoryProfiler.instance().markPhase(com.google.devtools.build.lib.profiler.ProfilePhase.PREPARE)
        com.google.devtools.build.lib.profiler.Profiler.instance()
            .markPhase(com.google.devtools.build.lib.profiler.ProfilePhase.PREPARE)

        // Plant the symlink forest.
        try {
            com.google.devtools.build.lib.profiler.Profiler.instance().profile("plantSymlinkForest").use { c ->
                val symlinkForest: SymlinkForest =
                    SymlinkForest(
                        packageRoots.getPackageRootsMap(),
                        this.execRoot,
                        runtime.getProductName(),
                        request
                            .getOptions<BuildLanguageOptions?>(BuildLanguageOptions::class.java)
                            .getExperimentalSiblingRepositoryLayout()
                    )
                symlinkForest.plantSymlinkForest()
            }
        } catch (e: IOException) {
            val message: String? = java.lang.String.format("Source forest creation failed: %s", e.getMessage())
            throw AbruptExitException(
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(message)
                        .setSymlinkForest(
                            FailureDetails.SymlinkForest.newBuilder()
                                .setCode(FailureDetails.SymlinkForest.Code.CREATION_FAILED)
                        )
                        .build()
                ),
                e
            )
        }
    }

    @Throws(AbruptExitException::class, java.lang.InterruptedException::class)
    private fun createActionLogDirectory(bulkDeleter: BulkDeleter?) {
        val directory: com.google.devtools.build.lib.vfs.Path = env.getActionTempsDirectory()
        if (directory.exists()) {
            try {
                com.google.devtools.build.lib.profiler.Profiler.instance().profile("directory.deleteTree").use { c ->
                    if (bulkDeleter != null) {
                        bulkDeleter.bulkDelete(
                            com.google.common.collect.ImmutableList.of<PathFragment?>(
                                directory.relativeTo(
                                    this.execRoot
                                )
                            )
                        )
                    } else {
                        directory.deleteTree()
                    }
                }
            } catch (e: IOException) {
                // TODO(b/140567980): Remove when we determine the cause of occasional deleteTree() failure.
                logDeleteTreeFailure(directory, "action output directory", e)
                throw createExitException(
                    "Couldn't delete action output directory",
                    Code.TEMP_ACTION_OUTPUT_DIRECTORY_DELETION_FAILURE,
                    e
                )
            }
        }

        try {
            com.google.devtools.build.lib.profiler.Profiler.instance().profile("directory.createDirectoryAndParents")
                .use { c ->
                    directory.createDirectoryAndParents()
                }
        } catch (e: IOException) {
            throw createExitException(
                "Couldn't create action output directory",
                Code.TEMP_ACTION_OUTPUT_DIRECTORY_CREATION_FAILURE,
                e
            )
        }
    }

    /**
     * Handles what action to perform on the convenience symlinks. If the mode is [ ][ConvenienceSymlinksMode.IGNORE], then skip any creating or cleaning of convenience symlinks.
     * Otherwise, manage the convenience symlinks and then post a [ ] build event.
     * 
     * @return map of convenience symlink name to target
     */
    fun handleConvenienceSymlinks(
        targetsToBuild: com.google.common.collect.ImmutableSet<ConfiguredTarget?>,
        configuration: BuildConfigurationValue
    ): com.google.common.collect.ImmutableMap<PathFragment?, PathFragment?>? {
        com.google.devtools.build.lib.profiler.Profiler.instance().profile("ExecutionTool.handleConvenienceSymlinks")
            .use { c ->
                var convenienceSymlinks: SymlinkCreationResult =
                    OutputDirectoryLinksUtils.EMPTY_SYMLINK_CREATION_RESULT
                if (request.getBuildOptions().getExperimentalConvenienceSymlinks()
                    != ConvenienceSymlinksMode.IGNORE
                ) {
                    convenienceSymlinks =
                        createConvenienceSymlinks(request.getBuildOptions(), targetsToBuild, configuration)
                }
                if (request.getBuildOptions().getExperimentalConvenienceSymlinksBepEvent()) {
                    env.getEventBus()
                        .post(
                            ConvenienceSymlinksIdentifiedEvent(
                                convenienceSymlinks.getConvenienceSymlinkProtos()
                            )
                        )
                }
                return convenienceSymlinks.getCreatedSymlinks()
            }
    }

    /**
     * Creates convenience symlinks based on the target configurations.
     * 
     * 
     * Top-level targets may have different configurations than the top-level configuration. This
     * is because targets may apply configuration transitions.
     * 
     * 
     * If all top-level targets have the same configuration - even if that isn't the top-level
     * configuration - symlinks point to that configuration.
     * 
     * 
     * If top-level targets have mixed configurations and at least one of them has the top-level
     * configuration, symliks point to the top-level configuration.
     * 
     * 
     * If top-level targets have mixed configurations and none has the top-level configuration,
     * symlinks aren't created. Furthermore, lingering symlinks from the last build are deleted. This
     * is to prevent confusion by pointing to an outdated directory the current build never used.
     */
    private fun createConvenienceSymlinks(
        buildRequestOptions: BuildRequestOptions,
        targetsToBuild: com.google.common.collect.ImmutableSet<ConfiguredTarget?>,
        configuration: BuildConfigurationValue
    ): SymlinkCreationResult {
        val executor: SkyframeExecutor = env.getSkyframeExecutor()
        val reporter: com.google.devtools.build.lib.events.Reporter? = env.getReporter()

        // Gather configurations to consider.
        val targetConfigs: com.google.common.collect.ImmutableSet<BuildConfigurationValue?>?
        if (targetsToBuild.isEmpty()) {
            targetConfigs = com.google.common.collect.ImmutableSet.of<BuildConfigurationValue?>(configuration)
        } else {
            // Collect the configuration of each top-level requested target. These may be different than
            // the build's top-level configuration because of self-transitions.
            val configurationKeys: com.google.common.collect.ImmutableSet<BuildConfigurationKey?> =
                targetsToBuild.stream()
                    .map<Any?>(ConfiguredTarget::getActual)
                    .map<Any?>(ConfiguredTarget::getConfigurationKey)
                    .filter(java.util.function.Predicate { obj: Any? -> java.util.Objects.nonNull(obj) })
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())
            val requestedTargetConfigs: com.google.common.collect.ImmutableSet<BuildConfigurationValue?> =
                com.google.common.collect.ImmutableSet.copyOf<BuildConfigurationValue?>(
                    executor.getConfigurations(
                        reporter,
                        configurationKeys
                    ).values()
                )
            if (requestedTargetConfigs.size() == 1) {
                // All top-level targets have the same configuration, so use that one.
                targetConfigs = requestedTargetConfigs
            } else if (requestedTargetConfigs.stream()
                    .anyMatch(
                        java.util.function.Predicate { c: BuildConfigurationValue? ->
                            c.getOutputDirectoryName().equals(configuration.getOutputDirectoryName())
                        })
            ) {
                // Mixed configs but at least one matches the top-level config's output path (this doesn't
                // mean it's the same as the top-level config: --trim_test_configuration means non-test
                // targets use the default output path but lack the top-level config's TestOptions). Set
                // symlinks to the top-level config so at least non-transitioned targets resolve. See
                // https://github.com/bazelbuild/bazel/issues/17081.
                targetConfigs = com.google.common.collect.ImmutableSet.of<BuildConfigurationValue?>(configuration)
            } else {
                // Mixed configs, none of which include the top-level config. Delete the symlinks because
                // they won't contain any relevant data. This is handled in the
                // createOutputDirectorySymlinks call below.
                targetConfigs = requestedTargetConfigs
            }
        }

        val productName: String? = runtime.getProductName()
        com.google.devtools.build.lib.profiler.Profiler.instance()
            .profile("OutputDirectoryLinksUtils.createOutputDirectoryLinks").use { c ->
                return OutputDirectoryLinksUtils.createOutputDirectoryLinks(
                    runtime.getRuleClassProvider().getSymlinkDefinitions(),
                    buildRequestOptions,
                    env.getWorkspaceName(),
                    env.getWorkspace(),
                    env.getDirectories(),
                    this.reporter,
                    targetConfigs,
                    productName
                )
            }
    }

    /**
     * If a path is supplied, creates and installs an ExplanationHandler. Returns an instance on
     * success. Reports an error and returns null otherwise.
     */
    private fun installExplanationHandler(
        explanationPath: PathFragment?, allOptions: String?
    ): ExplanationHandler? {
        if (explanationPath == null) {
            return null
        }
        val handler: ExplanationHandler?
        try {
            val instrumentationOutput: InstrumentationOutput =
                runtime
                    .getInstrumentationOutputFactory()
                    .createInstrumentationOutput( /* name= */
                        "explain",  /* destination= */
                        explanationPath,
                        DestinationRelativeTo.WORKSPACE_OR_HOME,
                        env,
                        this.reporter,  /* append= */
                        null,  /* internal= */
                        null
                    )
            handler = ExplanationHandler(instrumentationOutput.createOutputStream(), allOptions)
        } catch (e: IOException) {
            this.reporter
                .handle(
                    com.google.devtools.build.lib.events.Event.warn(
                        java.lang.String.format(
                            "Cannot write explanation of rebuilds to file '%s': %s",
                            explanationPath, e.getMessage()
                        )
                    )
                )
            return null
        }
        this.reporter
            .handle(com.google.devtools.build.lib.events.Event.info("Writing explanation of rebuilds to '" + explanationPath + "'"))
        this.reporter.addHandler(handler)
        return handler
    }

    /** Uninstalls the specified ExplanationHandler (if any) and closes the log file.  */
    private fun uninstallExplanationHandler(handler: ExplanationHandler?) {
        if (handler != null) {
            this.reporter.removeHandler(handler)
            handler.log.close()
        }
    }

    /**
     * An ErrorEventListener implementation that records DEPCHECKER events into a log file, iff the
     * --explain flag is specified during a build.
     */
    private class ExplanationHandler(log: java.io.OutputStream, optionsDescription: String?) :
        com.google.devtools.build.lib.events.EventHandler, java.lang.AutoCloseable {
        private val log: PrintWriter

        init {
            this.log = PrintWriter(OutputStreamWriter(log, java.nio.charset.StandardCharsets.UTF_8))
            this.log.println("Build options: " + optionsDescription)
        }

        @Throws(IOException::class)
        override fun close() {
            this.log.close()
        }

        override fun handle(event: com.google.devtools.build.lib.events.Event) {
            if (event.getKind() == com.google.devtools.build.lib.events.EventKind.DEPCHECKER) {
                log.println(event.getMessage())
            }
        }
    }

    @get:Throws(AbruptExitException::class)
    private val orLoadActionCache: ActionCache?
        /** Get action cache if present or reload it from the on-disk cache.  */
        get() {
            try {
                return env.getBlazeWorkspace().getOrLoadPersistentActionCache(this.reporter)
            } catch (e: IOException) {
                val message: String? =
                    java.lang.String.format(
                        "Couldn't create action cache: %s. If error persists, use 'bazel clean'.",
                        e.getMessage()
                    )
                throw AbruptExitException(
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage(message)
                            .setActionCache(
                                FailureDetails.ActionCache.newBuilder()
                                    .setCode(FailureDetails.ActionCache.Code.INITIALIZATION_FAILURE)
                            )
                            .build()
                    ),
                    e
                )
            }
        }

    private fun createBuilder(
        request: BuildRequest,
        actionCache: ActionCache?,
        skyframeExecutor: SkyframeExecutor,
        modifiedOutputFiles: ModifiedFileSet?
    ): com.google.devtools.build.lib.skyframe.Builder {
        val options: BuildRequestOptions = request.getBuildOptions()

        skyframeExecutor.setActionOutputRoot(env.getActionTempsDirectory())

        val executionFilter: com.google.common.base.Predicate<Action?> =
            CheckUpToDateFilter.fromOptions(request.getOptions<ExecutionOptions?>(ExecutionOptions::class.java))
        val artifactFactory: ArtifactFactory? = env.getSkyframeBuildView().getArtifactFactory()
        val outputService: OutputService? = env.getOutputService()
        return SkyframeBuilder(
            skyframeExecutor,
            env.getLocalResourceManager(),
            ActionCacheChecker(
                actionCache,
                artifactFactory,
                skyframeExecutor.getActionKeyContext(),
                executionFilter,
                outputService.getProxyMetadataFactory(),
                ActionCacheChecker.CacheConfig.builder()
                    .setEnabled(options.getUseActionCache())
                    .setStoreOutputMetadata(
                        outputService.shouldStoreRemoteOutputMetadataInActionCache()
                    )
                    .build()
            ),
            actionExecutionSalt,
            modifiedOutputFiles,
            env.getFileCache(),
            prefetcher,
            env.getOutputDirectoryHelper(),
            env.getRuntime().getBugReporter()
        )
    }

    /**
     * Writes the action cache files to disk, reporting any errors that occurred during writing and
     * capturing statistics.
     */
    private fun saveActionCache(actionCache: ActionCache?) {
        val builder: ActionCacheStatistics.Builder = ActionCacheStatistics.newBuilder()

        if (actionCache != null) {
            actionCache.mergeIntoActionCacheStatistics(builder)
            val duration: java.time.Duration? = actionCache.getLoadTime()
            if (duration != null) {
                builder.setLoadTimeInMs(duration.toMillis())
            }
            if (actionCacheChecker != null) {
                val totalCacheCheckSemaphoreWaitMillis: Long =
                    actionCacheChecker.getTotalCacheCheckSemaphoreWaitMillis()
                if (totalCacheCheckSemaphoreWaitMillis > 0) {
                    builder.setCacheCheckSemaphoreWaitTimeInMs(totalCacheCheckSemaphoreWaitMillis)
                    logger.atInfo().log(
                        "Total action cache check semaphore wait time: %,d ms",
                        totalCacheCheckSemaphoreWaitMillis
                    )
                }
            }

            val p: com.google.devtools.build.lib.profiler.AutoProfiler =
                com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils.profiledAndLogged(
                    "Saving action cache",
                    com.google.devtools.build.lib.profiler.ProfilerTask.INFO
                )
            try {
                builder.setSizeInBytes(actionCache.save())
            } catch (e: IOException) {
                builder.setSizeInBytes(0)
                this.reporter.handle(com.google.devtools.build.lib.events.Event.error("I/O error while writing action log: " + e.getMessage()))
            } finally {
                builder.setSaveTimeInMs(java.time.Duration.ofNanos(p.completeAndGetElapsedTimeNanos()).toMillis())
            }
        }
        env.getReporter().post(PostableActionCacheStats(builder.build()))
    }

    private val reporter: com.google.devtools.build.lib.events.Reporter?
        get() = env.getReporter()

    private val execRoot: com.google.devtools.build.lib.vfs.Path?
        get() = env.getExecRoot()

    /**
     * A listener that prepares the ExecutionProgressReceiver upon receiving the first
     * SomeExecutionStartedEvent. Only activated once a build.
     */
    private class ExecutionProgressReceiverSetup(
        skyframeExecutor: SkyframeExecutor,
        env: CommandEnvironment,
        executionUnstartedTimer: com.google.common.base.Stopwatch,
        progressReportInterval: Int
    ) {
        private val skyframeExecutor: SkyframeExecutor
        private val env: CommandEnvironment

        private val executionUnstartedTimer: com.google.common.base.Stopwatch
        private val progressReceiverStarted: AtomicBoolean = AtomicBoolean(false)

        private val progressReportInterval: Int

        init {
            this.skyframeExecutor = skyframeExecutor
            this.env = env
            this.executionUnstartedTimer = executionUnstartedTimer
            this.progressReportInterval = progressReportInterval
        }

        @com.google.common.eventbus.Subscribe
        fun setupExecutionProgressReceiver(event: SomeExecutionStartedEvent) {
            if (progressReceiverStarted.compareAndSet(false, true)) {
                // TODO(leba): count test actions
                val executionProgressReceiver: ExecutionProgressReceiver =
                    ExecutionProgressReceiver( /* exclusiveTestsCount= */0, env.getEventBus())
                env.getEventBus()
                    .post(ExecutionProgressReceiverAvailableEvent(executionProgressReceiver))

                val statusReporter: ActionExecutionStatusReporter? =
                    ActionExecutionStatusReporter.create(env.getReporter(), skyframeExecutor.getEventBus())
                skyframeExecutor.setActionExecutionProgressReportingObjects(
                    executionProgressReceiver, executionProgressReceiver, statusReporter
                )
                skyframeExecutor.setExecutionProgressReceiver(executionProgressReceiver)

                skyframeExecutor.setAndStartWatchdog(
                    ActionExecutionInactivityWatchdog(
                        executionProgressReceiver.createInactivityMonitor(statusReporter),
                        executionProgressReceiver.createInactivityReporter(
                            statusReporter, skyframeExecutor.getIsBuildingExclusiveArtifacts()
                        ),
                        progressReportInterval
                    )
                )
            }
            // no lock necessary since this method is thread-safe.
            if (event.countedInExecutionTime && !executionUnstartedTimer.isRunning()) {
                executionUnstartedTimer.start()
                env.getEventBus().unregister(this)
            }
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private fun logDeleteTreeFailure(
            directory: com.google.devtools.build.lib.vfs.Path, description: String?, deleteTreeFailure: IOException?
        ) {
            logger.atWarning().withCause(deleteTreeFailure).log(
                "Failed to delete %s '%s'", description, directory
            )
            if (directory.exists()) {
                try {
                    val entries: MutableCollection<com.google.devtools.build.lib.vfs.Path> =
                        directory.getDirectoryEntries()
                    val directoryDetails: java.lang.StringBuilder =
                        java.lang.StringBuilder("'")
                            .append(directory)
                            .append("' contains ")
                            .append(entries.size())
                            .append(" entries:")
                    for (entry in entries) {
                        directoryDetails.append(" '").append(entry.getBaseName()).append("'")
                    }
                    logger.atWarning().log("%s", directoryDetails)
                } catch (e: IOException) {
                    logger.atWarning().withCause(e).log("'%s' exists but could not be read", directory)
                }
            } else {
                logger.atWarning().log("'%s' does not exist", directory)
            }
        }

        /**
         * Computes the result of the build. Sets the list of successful (up-to-date) targets in the
         * request object.
         * 
         * @param configuredTargets The configured targets whose artifacts are to be built.
         */
        private fun determineSuccessfulTargets(
            configuredTargets: MutableCollection<ConfiguredTarget>, builtTargets: MutableSet<ConfiguredTargetKey?>
        ): com.google.common.collect.ImmutableSet<ConfiguredTarget?> {
            // Maintain the ordering by copying builtTargets into an ImmutableSet.Builder in the same
            // iteration order as configuredTargets.
            val successfulTargets: com.google.common.collect.ImmutableSet.Builder<ConfiguredTarget?> =
                com.google.common.collect.ImmutableSet.builder<ConfiguredTarget?>()
            for (target in configuredTargets) {
                if (builtTargets.contains(ConfiguredTargetKey.fromConfiguredTarget(target))) {
                    successfulTargets.add(target)
                }
            }
            return successfulTargets.build()
        }

        private fun determineSuccessfulAspects(
            aspects: com.google.common.collect.ImmutableSet<AspectKey?>, builtAspects: MutableSet<AspectKey?>
        ): com.google.common.collect.ImmutableSet<AspectKey?> {
            // Maintain the ordering.
            return aspects.stream().filter(java.util.function.Predicate { o: AspectKey? -> builtAspects.contains(o) })
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<AspectKey?>())
        }

        @com.google.common.annotations.VisibleForTesting
        fun configureResourceManager(resourceMgr: ResourceManager, request: BuildRequest) {
            val options: ExecutionOptions? = request.getOptions<ExecutionOptions?>(ExecutionOptions::class.java)
            resourceMgr.setAvailableResources(
                ResourceSet.create(
                    options.getLocalResources(),
                    if (options.usingLocalTestJobs()) options.getLocalTestJobs() else java.lang.Integer.MAX_VALUE
                )
            )

            resourceMgr.initializeCpuLoadFunctionality(
                MachineLoadProvider.instance(),
                options.getExperimentalCpuLoadScheduling(),
                options.getExperimentalCpuLoadSchedulingWindowSize()
            )
            resourceMgr.scheduleCpuLoadWindowUpdate()

            resourceMgr.setAllowOneActionOnResourceUnavailable(
                options.getAllowOneActionOnResourceUnavailable()
            )
        }

        private fun createExitException(
            messagePrefix: String?, detailedCode: Code?, e: IOException
        ): AbruptExitException {
            return AbruptExitException(
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(java.lang.String.format("%s: %s", messagePrefix, e.getMessage()))
                        .setExecution(Execution.newBuilder().setCode(detailedCode))
                        .build()
                ),
                e
            )
        }
    }
}
