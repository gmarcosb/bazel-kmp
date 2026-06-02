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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.ActionKeyContext

/**
 * A SkyframeExecutor that implicitly assumes that builds can be done incrementally from the most
 * recent build. In other words, builds are "sequenced".
 */
class SequencedSkyframeExecutor protected constructor(
    skyframeExecutorConsumerOnInit: java.util.function.Consumer<SkyframeExecutor?>?,
    pkgFactory: PackageFactory,
    fileSystem: com.google.devtools.build.lib.vfs.FileSystem?,
    directories: BlazeDirectories?,
    actionKeyContext: ActionKeyContext?,
    workspaceStatusActionFactory: Factory?,
    diffAwarenessFactories: Iterable<out DiffAwareness.Factory?>?,
    workspaceInfoFromDiffReceiver: WorkspaceInfoFromDiffReceiver?,
    extraSkyFunctions: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>?,
    syscallCache: SyscallCache?,
    ignoredSubdirectoriesFunction: SkyFunction?,
    crossRepositoryLabelViolationStrategy: CrossRepositoryLabelViolationStrategy?,
    buildFilesByPriority: com.google.common.collect.ImmutableList<BuildFileName?>?,
    allowExternalRepositories: Boolean,
    repoContentsCachePathSupplier: java.util.function.Supplier<com.google.devtools.build.lib.vfs.Path?>?,
    actionOnIOExceptionReadingBuildFile: ActionOnIOExceptionReadingBuildFile?,
    actionOnFilesystemErrorCodeLoadingBzlFile: ActionOnFilesystemErrorCodeLoadingBzlFile?,
    shouldUseRepoDotBazel: Boolean,
    skyKeyStateReceiver: SkyKeyStateReceiver,
    bugReporter: BugReporter?,
    globUnderSingleDep: Boolean,
    diffCheckNotificationOptions: java.util.Optional<DiffCheckNotificationOptions?>?
) : SkyframeExecutor(
    skyframeExecutorConsumerOnInit,
    pkgFactory,
    fileSystem,
    directories,
    actionKeyContext,
    workspaceStatusActionFactory,
    extraSkyFunctions,
    syscallCache,
    ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
    ignoredSubdirectoriesFunction,
    crossRepositoryLabelViolationStrategy,
    buildFilesByPriority,
    actionOnIOExceptionReadingBuildFile,
    actionOnFilesystemErrorCodeLoadingBzlFile,
    shouldUseRepoDotBazel,  /* shouldUnblockCpuWorkWhenFetchingDeps= */
    false,
    PackageProgressReceiver(),
    AnalysisProgressReceiver(),
    skyKeyStateReceiver,
    bugReporter,
    diffAwarenessFactories,
    workspaceInfoFromDiffReceiver,
    SequencedRecordingDifferencer(),
    allowExternalRepositories,
    repoContentsCachePathSupplier,
    globUnderSingleDep,
    diffCheckNotificationOptions
) {
    /**
     * If false, the graph will not store state useful for incremental builds, saving memory but
     * leaving the graph un-reusable. Subsequent builds will therefore not be incremental.
     * 
     * 
     * Avoids storing edges entirely and dereferences each action after execution.
     */
    private var trackIncrementalState = true

    private var evaluatorNeedsReset = false
    private var lastCommandKeptState = false
    private var needGcAfterResettingEvaluator = false

    private val outputDirtyFiles: AtomicInteger = AtomicInteger()
    private val outputDirtyFilesExecPathSample: ArrayBlockingQueue<String?> = ArrayBlockingQueue<String?>(
        MODIFIED_OUTPUT_PATHS_SAMPLE_SIZE
    )
    private val modifiedFilesDuringPreviousBuild: AtomicInteger = AtomicInteger()

    private var outputTreeDiffCheckingDuration: java.time.Duration? = java.time.Duration.ofSeconds(-1L)

    // Use delegation so that the underlying inconsistency receiver can be changed per-command without
    // recreating the evaluator.
    protected val inconsistencyReceiver: DelegatingGraphInconsistencyReceiver =
        DelegatingGraphInconsistencyReceiver(GraphInconsistencyReceiver.THROWING)

    override fun resetEvaluator() {
        super.resetEvaluator()
        diffAwarenessManager.reset()
    }

    override fun createEvaluator(
        skyFunctions: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>?,
        progressReceiver: SkyframeProgressReceiver?,
        emittedEventState: EmittedEventState?
    ): MemoizingEvaluator {
        return InMemoryMemoizingEvaluator(
            skyFunctions,
            recordingDiffer,
            progressReceiver,
            inconsistencyReceiver,
            if (trackIncrementalState) SkyframeExecutor.Companion.DEFAULT_EVENT_FILTER_WITH_ACTIONS else com.google.devtools.build.skyframe.EventFilter.NO_STORAGE,
            emittedEventState,
            trackIncrementalState,  /* usePooledInterning= */
            true
        )
    }

    public override fun injectable(): Injectable? {
        return recordingDiffer
    }

    @get:com.google.common.annotations.VisibleForTesting
    val differencerForTesting: RecordingDifferencer?
        get() = recordingDiffer

    override fun newSkyframeProgressReceiver(): SkyframeProgressReceiver {
        return SequencedSkyframeProgressReceiver()
    }

    /** A [SkyframeProgressReceiver] tracks dirty [FileKey]s.  */
    protected inner class SequencedSkyframeProgressReceiver : SkyframeProgressReceiver() {
        override fun dirtied(skyKey: SkyKey?, dirtyType: DirtyType?) {
            super.dirtied(skyKey, dirtyType)
            if (skyKey is FileKey) {
                incrementalBuildMonitor.reportInvalidatedFileValue()
            }
        }
    }

    @Throws(java.lang.InterruptedException::class, AbruptExitException::class)
    override fun sync(
        eventHandler: ExtendedEventHandler,
        packageLocator: PathPackageLocator,
        commandId: UUID?,
        clientEnv: MutableMap<String?, String?>?,
        tsgm: TimestampGranularityMonitor?,
        executors: QuiescingExecutors?,
        options: com.google.devtools.common.options.OptionsProvider,
        commandName: String?,
        commandExecutes: Boolean
    ): WorkspaceInfoFromDiff? {
        inconsistencyReceiver.setDelegate(getGraphInconsistencyReceiverForCommand(options))

        if (diffAwarenessManager != null) {
            for (pkgRoot in packageLocator.getPathEntries()) {
                val evaluatingVersionDiff: java.util.Optional<EvaluatingVersionDiff> =
                    diffAwarenessManager.getEvaluatingVersionDiff(pkgRoot, options)
                if (evaluatingVersionDiff.isPresent()) {
                    val versionDiff: EvaluatingVersionDiff = evaluatingVersionDiff.get()
                    eventHandler.post(versionDiff)
                    if (!evaluatorNeedsReset && diffCheckNotificationOptions.isPresent()
                        && !diffCheckNotificationOptions
                            .get()
                            .allowDiffCheck(versionDiff, eventHandler, options)
                    ) {
                        evaluatorNeedsReset = true
                        needGcAfterResettingEvaluator = true
                    }
                }
            }
        }

        if (evaluatorNeedsReset) {
            // Recreate MemoizingEvaluator so that graph is recreated with correct edge-clearing status,
            // or if the graph doesn't have edges, so that a fresh graph can be used.
            resetEvaluator()
            evaluatorNeedsReset = false
            if (needGcAfterResettingEvaluator) {
                // Collect weakly reachable objects to avoid resurrection. See b/291641466.
                GoogleAutoProfilerUtils.logged(
                    "manual GC to clean up from --keep_state_after_build command"
                ).use { profiler ->
                    java.lang.System.gc()
                }
                GoogleAutoProfilerUtils.logged(
                    "shrinking pooled interners after resetting evaluator"
                ).use { profiler ->
                    PooledInterner.shrinkAll()
                }
                needGcAfterResettingEvaluator = false
            }
        }
        super.sync(
            eventHandler,
            packageLocator,
            commandId,
            clientEnv,
            tsgm,
            executors,
            options,
            commandName,
            commandExecutes
        )
        val startTime: Long = java.lang.System.nanoTime()
        val workspaceInfo: WorkspaceInfoFromDiff? = handleDiffs(eventHandler, options)
        val stopTime: Long = java.lang.System.nanoTime()
        Profiler.instance().logSimpleTask(startTime, stopTime, ProfilerTask.INFO, "handleDiffs")
        val duration = stopTime - startTime
        sourceDiffCheckingDuration = if (duration > 0) java.time.Duration.ofNanos(duration) else java.time.Duration.ZERO
        return workspaceInfo
    }

    private fun getGraphInconsistencyReceiverForCommand(
        options: com.google.devtools.common.options.OptionsProvider
    ): GraphInconsistencyReceiver {
        val someNodeDroppingExpected =
            (options.getOptions<O?>(AnalysisOptions::class.java) != null
                    && options.getOptions<O?>(AnalysisOptions::class.java).getDiscardAnalysisCache())
                    || !trackIncrementalState || heuristicallyDropNodes
        val skymeldInconsistenciesExpected =
            someNodeDroppingExpected && isMergedSkyframeAnalysisExecution()
        if (rewindingEnabled(options)) {
            return RewindableGraphInconsistencyReceiver(
                heuristicallyDropNodes, skymeldInconsistenciesExpected
            )
        }

        if (heuristicallyDropNodes || skymeldInconsistenciesExpected) {
            return NodeDroppingInconsistencyReceiver(
                heuristicallyDropNodes, skymeldInconsistenciesExpected
            )
        }
        return GraphInconsistencyReceiver.THROWING
    }

    override fun onPkgLocatorChange() {
        invalidate(SkyFunctionName.functionIsIn(PACKAGE_LOCATOR_DEPENDENT_VALUES))
    }

    fun invalidate(pred: com.google.common.base.Predicate<SkyKey?>) {
        recordingDiffer.invalidate(
            com.google.common.collect.Iterables.filter<SkyKey?>(
                memoizingEvaluator.getValues().keySet(), pred
            )
        )
    }

    /** Sets the packages that should be treated as deleted and ignored.  */
    @com.google.common.annotations.VisibleForTesting // productionVisibility = Visibility.PRIVATE
    override fun setDeletedPackages(pkgs: Iterable<PackageIdentifier?>) {
        val newDeletedPackagesSet: com.google.common.collect.ImmutableSet<PackageIdentifier?> =
            com.google.common.collect.ImmutableSet.copyOf<PackageIdentifier?>(pkgs)

        val newlyDeletedOrNotDeletedPackages: MutableSet<PackageIdentifier?> =
            com.google.common.collect.Sets.symmetricDifference<PackageIdentifier?>(
                deletedPackages.get(),
                newDeletedPackagesSet
            )
        if (!newlyDeletedOrNotDeletedPackages.isEmpty()) {
            // PackageLookupValue is a HERMETIC node type, so we can't invalidate it.
            memoizingEvaluator.delete(
                java.util.function.Predicate { k: SkyKey? ->
                    PackageLookupValue.appliesToKey(
                        k,
                        newlyDeletedOrNotDeletedPackages::contains
                    )
                })
        }

        deletedPackages.set(newDeletedPackagesSet)
    }

    /**
     * {@inheritDoc}
     * 
     * 
     * Necessary conditions to not store graph edges are either
     * 
     * 
     *  1. batch (since incremental builds are not possible) and discard_analysis_cache (since
     * otherwise user isn't concerned about saving memory this way).
     *  1. track_incremental_state set to false.
     * 
     */
    override fun decideKeepIncrementalState(
        batch: Boolean,
        keepStateAfterBuild: Boolean,
        shouldTrackIncrementalState: Boolean,
        heuristicallyDropNodes: Boolean,
        discardAnalysisCache: Boolean,
        eventHandler: com.google.devtools.build.lib.events.EventHandler
    ) {
        com.google.common.base.Preconditions.checkState(!active)
        val oldValueOfTrackIncrementalState = trackIncrementalState

        // First check if the incrementality state should be kept around during the build.
        val explicitlyRequestedNoIncrementalData = !shouldTrackIncrementalState
        val implicitlyRequestedNoIncrementalData = (batch && discardAnalysisCache)
        trackIncrementalState =
            !explicitlyRequestedNoIncrementalData && !implicitlyRequestedNoIncrementalData
        if (explicitlyRequestedNoIncrementalData != implicitlyRequestedNoIncrementalData) {
            if (!explicitlyRequestedNoIncrementalData) {
                eventHandler.handle(
                    com.google.devtools.build.lib.events.Event.warn(
                        ("--batch and --discard_analysis_cache specified, but --notrack_incremental_state "
                                + "not specified: incrementality data is implicitly discarded, but you may need"
                                + " to specify --notrack_incremental_state in the future if you want to "
                                + "maximize memory savings.")
                    )
                )
            }
            if (!batch && keepStateAfterBuild) {
                eventHandler.handle(
                    com.google.devtools.build.lib.events.Event.warn(
                        ("--notrack_incremental_state was specified, but without "
                                + "--nokeep_state_after_build. Inmemory state from this build will not be "
                                + "reusable, but it will not get fully wiped until the beginning of the next "
                                + "build. Use --nokeep_state_after_build to clean up eagerly.")
                    )
                )
            }
        }

        if (trackIncrementalState) {
            if (heuristicallyDropNodes) {
                eventHandler.handle(
                    com.google.devtools.build.lib.events.Event.warn(
                        ("--heuristically_drop_nodes was specified with track incremental state also being"
                                + " true. The flag is ignored and no node is heuristically dropped in the track"
                                + " incremental mode.")
                    )
                )
            }
            this.heuristicallyDropNodes = false
        } else {
            this.heuristicallyDropNodes = heuristicallyDropNodes
        }

        // Now check if it is necessary to wipe the previous state. We do this if either the previous
        // or current command requires the build to have been isolated.
        if (oldValueOfTrackIncrementalState != trackIncrementalState) {
            logger.atInfo().log("Set incremental state to %b", trackIncrementalState)
            evaluatorNeedsReset = true
        } else if (!trackIncrementalState) {
            evaluatorNeedsReset = true
        }
        if (evaluatorNeedsReset && lastCommandKeptState) {
            needGcAfterResettingEvaluator = true
        }
        lastCommandKeptState = keepStateAfterBuild
    }

    override fun tracksStateForIncrementality(): Boolean {
        return trackIncrementalState
    }

    public override fun clearAnalysisCacheImpl(
        topLevelTargets: com.google.common.collect.ImmutableSet<ConfiguredTarget?>?,
        topLevelAspects: com.google.common.collect.ImmutableSet<AspectKey?>?
    ) {
        discardPreExecutionCache(
            topLevelTargets,
            topLevelAspects,
            if (trackIncrementalState) DiscardType.ANALYSIS_REFS_ONLY else DiscardType.ALL
        )
    }

    override fun invalidateTransientErrors() {
        checkActive()
        recordingDiffer.invalidateTransientErrors()
    }

    @Throws(java.lang.InterruptedException::class)
    override fun detectModifiedOutputFiles(
        modifiedOutputFiles: ModifiedFileSet?,
        lastExecutionTimeRange: com.google.common.collect.Range<Long?>?,
        outputChecker: OutputChecker?,
        fsvcThreads: Int
    ) {
        val startTime: Long = java.lang.System.nanoTime()
        val fsvc: FilesystemValueChecker =
            FilesystemValueChecker(
                com.google.common.base.Preconditions.checkNotNull<T?>(tsgm.get()),
                syscallCache,
                { delegate: XattrProvider? -> outputService.getXattrProvider(delegate) },
                fsvcThreads
            )
        val batchStatter: BatchStat? = outputService.getBatchStatter()
        recordingDiffer.invalidate(
            fsvc.getDirtyActionValues(
                memoizingEvaluator.getValues(),
                batchStatter,
                modifiedOutputFiles,
                outputChecker,
                { maybeModifiedTime, artifact ->
                    modifiedFiles.incrementAndGet()
                    val dirtyOutputsCount: Int = outputDirtyFiles.incrementAndGet()
                    if (lastExecutionTimeRange != null
                        && lastExecutionTimeRange.contains(maybeModifiedTime)
                    ) {
                        modifiedFilesDuringPreviousBuild.incrementAndGet()
                    }
                    if (dirtyOutputsCount <= MODIFIED_OUTPUT_PATHS_SAMPLE_SIZE) {
                        outputDirtyFilesExecPathSample.offer(artifact.getExecPathString())
                    }
                })
        )
        logger.atInfo().log("Found %d modified files from last build", modifiedFiles.get())
        val stopTime: Long = java.lang.System.nanoTime()
        Profiler.instance()
            .logSimpleTask(startTime, stopTime, ProfilerTask.INFO, "detectModifiedOutputFiles")
        val duration = stopTime - startTime
        outputTreeDiffCheckingDuration =
            if (duration > 0) java.time.Duration.ofNanos(duration) else java.time.Duration.ZERO
    }

    val skyframeStats: SkyframeStats?
        get() {
            val ruleStats: MutableMap<String?, SkyKeyStats> =
                HashMap<String?, SkyKeyStats>()
            val aspectStats: MutableMap<String?, SkyKeyStats> =
                HashMap<String?, SkyKeyStats>()
            val starlarkProviders: com.google.common.collect.Multiset<StarlarkProvider?> =
                com.google.common.collect.HashMultiset.create<StarlarkProvider?>()
            for (skyKeyAndValue in memoizingEvaluator.getDoneValues().entrySet()) {
                val value: SkyValue? = skyKeyAndValue.getValue()
                val key: SkyKey = skyKeyAndValue.getKey()
                val functionName: SkyFunctionName = key.functionName()
                if (value is RuleConfiguredTargetValue) {
                    val configuredTarget: ConfiguredTarget? = value.getConfiguredTarget()
                    if (configuredTarget is RuleConfiguredTarget) {
                        val ruleClassId: RuleClassId = configuredTarget.getRuleClassId()
                        val ruleStat: SkyKeyStats =
                            ruleStats.computeIfAbsent(
                                ruleClassId.key(),
                                java.util.function.Function { k: String? ->
                                    SkyKeyStats(
                                        k,
                                        ruleClassId.name()
                                    )
                                })
                        ruleStat.countWithActions(value.getActions().size())
                        addStarlarkProviders(
                            configuredTarget.getProvidersForMetrics(),
                            starlarkProviders
                        )
                    }
                } else if (functionName == SkyFunctions.ASPECT) {
                    val aspectValue: AspectValue = value as AspectValue

                    // Aspect can't be retrieved from the value, move on.
                    if (aspectValue.isCleared()) {
                        continue
                    }
                    val aspectClass: AspectClass = aspectValue.getAspect().getAspectClass()
                    val aspectStat: SkyKeyStats =
                        aspectStats.computeIfAbsent(
                            aspectClass.getKey(),
                            java.util.function.Function { k: String? -> SkyKeyStats(k, aspectClass.getName()) })
                    aspectStat.countWithActions(aspectValue.getActions().size())
                    addStarlarkProviders(
                        aspectValue.getProviders(),
                        starlarkProviders
                    )
                }
            }
            return SkyframeStats( /* ruleStats= */
                com.google.common.collect.ImmutableList.sortedCopyOf<SkyKeyStats?>(
                    SkyKeyStats.BY_COUNT_DESC,
                    ruleStats.values()
                ),  /* aspectStats= */
                com.google.common.collect.ImmutableList.sortedCopyOf<SkyKeyStats?>(
                    SkyKeyStats.BY_COUNT_DESC, aspectStats.values()
                ),
                com.google.common.collect.Multisets.copyHighestCountFirst<StarlarkProvider?>(starlarkProviders)
            )
        }

    @Throws(CommandLineExpansionException::class, IOException::class, TemplateExpansionException::class)
    fun dumpSkyframeStateInParallel(
        actionGraphDump: ActionGraphDump, aqueryConsumingOutputHandler: AqueryConsumingOutputHandler
    ) {
        val tasks: com.google.common.collect.ImmutableList.Builder<java.util.concurrent.Callable<java.lang.Void?>?> =
            com.google.common.collect.ImmutableList.builder<java.util.concurrent.Callable<java.lang.Void?>?>()

        try {
            for (skyKeyAndValue in memoizingEvaluator.getDoneValues().entrySet()) {
                val key: SkyKey = skyKeyAndValue.getKey()
                val skyValue: SkyValue? = skyKeyAndValue.getValue()
                if (skyValue == null) {
                    // The skyValue may be null in case analysis of the previous build failed.
                    continue
                }
                if (skyValue is RuleConfiguredTargetValue) {
                    tasks.add(
                        java.util.concurrent.Callable {
                            val configuredTarget: RuleConfiguredTargetValue = skyValue as RuleConfiguredTargetValue
                            // Only dumps the value for non-delegating keys.
                            if (configuredTarget.getConfiguredTarget().getLookupKey().equals(key)) {
                                actionGraphDump.dumpConfiguredTarget(configuredTarget)
                            }
                            null
                        })
                } else if (key.functionName() == SkyFunctions.ASPECT) {
                    val aspectValue: AspectValue = skyValue as AspectValue
                    val aspectKey: AspectKey = key as AspectKey
                    val configuredTargetValue: ConfiguredTargetValue? =
                        memoizingEvaluator.getExistingValue(aspectKey.getBaseConfiguredTargetKey()) as ConfiguredTargetValue?
                    tasks.add(
                        java.util.concurrent.Callable {
                            actionGraphDump.dumpAspect(aspectValue, configuredTargetValue)
                            null
                        })
                }
            }
            val executor: ForkJoinPool =
                NamedForkJoinPool.newNamedPool(
                    "action-graph-dump", java.lang.Runtime.getRuntime().availableProcessors()
                )
            try {
                val consumerFuture: java.util.concurrent.Future<java.lang.Void?> =
                    executor.submit<java.lang.Void?>(aqueryConsumingOutputHandler.startConsumer())
                val futures: MutableList<java.util.concurrent.Future<java.lang.Void?>> =
                    executor.invokeAll<java.lang.Void?>(tasks.build())
                for (future in futures) {
                    future.get()
                }
                aqueryConsumingOutputHandler.stopConsumer( /* discardRemainingTasks= */false)
                // Get any possible exception from the consumer.
                consumerFuture.get()
            } catch (e: ExecutionException) {
                aqueryConsumingOutputHandler.stopConsumer( /* discardRemainingTasks= */true)
                val cause: Throwable = com.google.common.base.Throwables.getRootCause(e)
                com.google.common.base.Throwables.throwIfInstanceOf<X?>(
                    cause,
                    CommandLineExpansionException::class.java
                )
                com.google.common.base.Throwables.throwIfInstanceOf<X?>(cause, TemplateExpansionException::class.java)
                com.google.common.base.Throwables.throwIfInstanceOf<IOException?>(cause, IOException::class.java)
                com.google.common.base.Throwables.throwIfInstanceOf<java.lang.InterruptedException?>(
                    cause,
                    java.lang.InterruptedException::class.java
                )
                com.google.common.base.Throwables.throwIfUnchecked(cause)
                throw java.lang.IllegalStateException("Unexpected exception type: ", e)
            } finally {
                executor.shutdown()
            }
        } catch (e: java.lang.InterruptedException) {
            java.lang.Thread.currentThread().interrupt()
        }
    }

    /** Support for aquery output.  */
    @Throws(CommandLineExpansionException::class, IOException::class, TemplateExpansionException::class)
    fun dumpSkyframeState(actionGraphDump: ActionGraphDump) {
        for (skyKeyAndValue in memoizingEvaluator.getDoneValues().entrySet()) {
            val key: SkyKey = skyKeyAndValue.getKey()
            val skyValue: SkyValue? = skyKeyAndValue.getValue()
            if (skyValue == null) {
                // The skyValue may be null in case analysis of the previous build failed.
                continue
            }
            try {
                if (skyValue is RuleConfiguredTargetValue) {
                    // Only dumps the value for non-delegating keys.
                    if (skyValue.getConfiguredTarget().getLookupKey().equals(key)) {
                        actionGraphDump.dumpConfiguredTarget(skyValue)
                    }
                } else if (key.functionName() == SkyFunctions.ASPECT) {
                    val aspectValue: AspectValue = skyValue as AspectValue
                    val aspectKey: AspectKey = key as AspectKey
                    val configuredTargetValue: ConfiguredTargetValue? =
                        memoizingEvaluator.getExistingValue(aspectKey.getBaseConfiguredTargetKey()) as ConfiguredTargetValue?
                    actionGraphDump.dumpAspect(aspectValue, configuredTargetValue)
                }
            } catch (e: java.lang.InterruptedException) {
                java.lang.Thread.currentThread().interrupt()
                throw java.lang.IllegalStateException("No interruption in sequenced evaluation", e)
            }
        }
    }

    /**
     * In addition to calling the superclass method, deletes all analysis-related values from the
     * Skyframe cache. This is done to save memory (e.g. on a configuration change); since the
     * configuration is part of the key, these key/value pairs will be sitting around doing nothing
     * until the configuration changes back to the previous value.
     * 
     * 
     * The next evaluation will delete all invalid values.
     */
    override fun handleAnalysisInvalidatingChange() {
        super.handleAnalysisInvalidatingChange()
        memoizingEvaluator.delete(java.util.function.BiPredicate { k: SkyKey?, v: SkyValue? ->
            this.shouldDeleteOnAnalysisInvalidatingChange(
                k,
                v
            )
        })
    }

    @com.google.errorprone.annotations.ForOverride
    protected fun shouldDeleteOnAnalysisInvalidatingChange(k: SkyKey, v: SkyValue?): Boolean {
        if (v != null && v.isCleared()) {
            // Anything that had memory cleared should be discarded and re-evaluated.
            return true
        }

        // TODO: b/330770905 - Rewrite this to use pattern matching when available.
        // Also remove ActionLookupData since all such nodes depend on ActionLookupKey nodes and
        // deleting en masse is cheaper than deleting via graph traversal (b/192863968).
        if (k is ArtifactNestedSetKey || k is ActionLookupData) {
            return true
        }
        // Remove BuildConfigurationKeys except for the currently active key and the key for
        // EMPTY_OPTIONS, which is a constant and will be re-used frequently.
        if (k is BuildConfigurationKey) {
            if (SkyframeExecutor.Companion.isEmptyOptionsKey(k)) {
                return false
            }
            if (getSkyframeBuildView().getBuildConfiguration() != null
                && k == getSkyframeBuildView().getBuildConfiguration().getKey()
            ) {
                return false
            }
            if (isExecConfig(k)) {
                return false
            }
            return true
        }
        // Remove ActionLookupKeys unless they are for the empty options config, in which case they will
        // be re-used frequently and we can avoid re-creating them. They are dependencies of the empty
        // configuration key and will never change.
        if (k is ActionLookupKey) {
            if (SkyframeExecutor.Companion.isEmptyOptionsKey(k.getConfigurationKey())) {
                return false
            }
            if (isExecConfig(k.getConfigurationKey())) {
                return false
            }
            if (k.getConfigurationKey() == null) {
                return false
            }
            return true
        }
        return false
    }

    /**
     * Deletes all ConfiguredTarget values from the Skyframe cache.
     * 
     * 
     * After the execution of this method all invalidated and marked for deletion values (and the
     * values depending on them) will be deleted from the cache.
     * 
     * 
     * WARNING: Note that a call to this method leaves legacy data inconsistent with Skyframe. The
     * next build should clear the legacy caches.
     */
    override fun dropConfiguredTargetsNow(eventHandler: ExtendedEventHandler?) {
        handleAnalysisInvalidatingChange()
        // Run the invalidator to actually delete the values.
        try {
            progressReceiver.ignoreInvalidations = true
            com.google.devtools.build.lib.concurrent.Uninterruptibles.callUninterruptibly<Any?>(
                java.util.concurrent.Callable {
                    val evaluationContext: com.google.devtools.build.skyframe.EvaluationContext? =
                        newEvaluationContextBuilder()
                            .setKeepGoing(false)
                            .setParallelism(java.lang.Runtime.getRuntime().availableProcessors())
                            .setEventHandler(eventHandler)
                            .build()
                    memoizingEvaluator.evaluate<SkyValue?>(
                        com.google.common.collect.ImmutableList.of<SkyKey?>(),
                        evaluationContext
                    )
                    null
                })
        } catch (e: java.lang.Exception) {
            throw java.lang.IllegalStateException(e)
        } finally {
            progressReceiver.ignoreInvalidations = false
        }
    }

    override fun createExecutionFinishedEventInternal(): ExecutionFinishedEvent.Builder? {
        val builder: ExecutionFinishedEvent.Builder? =
            ExecutionFinishedEvent.builder()
                .setOutputDirtyFiles(outputDirtyFiles.getAndSet(0))
                .setOutputDirtyFileExecPathSample(com.google.common.collect.ImmutableList.< E > copyOf < E ? > (outputDirtyFilesExecPathSample))
                .setOutputModifiedFilesDuringPreviousBuild(
                    modifiedFilesDuringPreviousBuild.getAndSet(0)
                )
                .setSourceDiffCheckingDuration(sourceDiffCheckingDuration)
                .setNumSourceFilesCheckedBecauseOfMissingDiffs(
                    numSourceFilesCheckedBecauseOfMissingDiffs
                )
                .setOutputTreeDiffCheckingDuration(outputTreeDiffCheckingDuration)
        outputDirtyFilesExecPathSample.clear()
        sourceDiffCheckingDuration = java.time.Duration.ZERO
        outputTreeDiffCheckingDuration = java.time.Duration.ZERO
        return builder
    }

    override fun deleteOldNodes(versionWindowForDirtyGc: Long) {
        // TODO(bazel-team): perhaps we should come up with a separate GC class dedicated to maintaining
        // value garbage. If we ever do so, this logic should be moved there.
        if (trackIncrementalState) {
            memoizingEvaluator.deleteDirty(versionWindowForDirtyGc)
        }
    }

    override fun dumpPackages(out: PrintStream) {
        val packageSkyKeys: Iterable<SkyKey?> =
            com.google.common.collect.Iterables.filter(
                memoizingEvaluator.getValues().keySet(),
                SkyFunctions.isSkyFunction(SkyFunctions.PACKAGE)
            )
        out.println(com.google.common.collect.Iterables.size(packageSkyKeys).toString() + " packages")
        for (packageSkyKey in packageSkyKeys) {
            val pkgVal: PackageValue? = (memoizingEvaluator.getValues().get(packageSkyKey) as PackageValue?)
            if (pkgVal != null) {
                val pkg: Package =
                    (memoizingEvaluator.getValues().get(packageSkyKey) as PackageValue).getPackage()
                pkg.dump(out)
            } else {
                out.println("  Package " + packageSkyKey + " is in error.")
            }
        }
    }

    /**
     * Builder class for [SequencedSkyframeExecutor].
     * 
     * 
     * Allows addition of the new arguments to [SequencedSkyframeExecutor] constructor
     * without the need to modify all the places, where [SequencedSkyframeExecutor] is
     * constructed (if the default value can be provided for the new argument in Builder).
     */
    class Builder private constructor() {
        var pkgFactory: PackageFactory? = null
        var fileSystem: com.google.devtools.build.lib.vfs.FileSystem? = null
        var directories: BlazeDirectories? = null
        var actionKeyContext: ActionKeyContext? = null
        private var crossRepositoryLabelViolationStrategy: CrossRepositoryLabelViolationStrategy? = null
        private var buildFilesByPriority: com.google.common.collect.ImmutableList<BuildFileName?>? = null
        private var actionOnIOExceptionReadingBuildFile: ActionOnIOExceptionReadingBuildFile? = null
        private var actionOnFilesystemErrorCodeLoadingBzlFile: ActionOnFilesystemErrorCodeLoadingBzlFile? = null
        private var shouldUseRepoDotBazel = true

        // Fields with default values.
        private var extraSkyFunctions: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>? =
            com.google.common.collect.ImmutableMap.of<SkyFunctionName?, SkyFunction?>()
        private var workspaceStatusActionFactory: Factory? = null
        private var diffAwarenessFactories: Iterable<out DiffAwareness.Factory?>? =
            com.google.common.collect.ImmutableList.of<DiffAwareness.Factory?>()
        private var workspaceInfoFromDiffReceiver: WorkspaceInfoFromDiffReceiver? =
            WorkspaceInfoFromDiffReceiver { ignored1: PathFragment?, ignored2: WorkspaceInfoFromDiff? -> }
        private var allowExternalRepositories = false
        private var repoContentsCachePathSupplier: java.util.function.Supplier<com.google.devtools.build.lib.vfs.Path?>? =
            java.util.function.Supplier { null }
        private var skyframeExecutorConsumerOnInit: java.util.function.Consumer<SkyframeExecutor?>? =
            java.util.function.Consumer { skyframeExecutor: SkyframeExecutor? -> }
        private var ignoredSubdirectoriesFunction: SkyFunction? = null
        private var bugReporter: BugReporter? = BugReporter.defaultInstance()
        private var skyKeyStateReceiver: SkyKeyStateReceiver = SkyKeyStateReceiver.Companion.NULL_INSTANCE
        private var syscallCache: SyscallCache? = null
        private var globUnderSingleDep = true
        private var diffCheckNotificationOptions: DiffCheckNotificationOptions? = null

        fun build(): SequencedSkyframeExecutor {
            // Check that the values were explicitly set.
            com.google.common.base.Preconditions.checkNotNull<Any?>(pkgFactory)
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.FileSystem?>(fileSystem)
            com.google.common.base.Preconditions.checkNotNull<Any?>(directories)
            com.google.common.base.Preconditions.checkNotNull<Any?>(actionKeyContext)
            com.google.common.base.Preconditions.checkNotNull<Any?>(crossRepositoryLabelViolationStrategy)
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<BuildFileName?>?>(
                buildFilesByPriority
            )
            com.google.common.base.Preconditions.checkNotNull<ActionOnIOExceptionReadingBuildFile?>(
                actionOnIOExceptionReadingBuildFile
            )
            com.google.common.base.Preconditions.checkNotNull<ActionOnFilesystemErrorCodeLoadingBzlFile?>(
                actionOnFilesystemErrorCodeLoadingBzlFile
            )
            com.google.common.base.Preconditions.checkNotNull<SkyFunction?>(ignoredSubdirectoriesFunction)

            val skyframeExecutor =
                SequencedSkyframeExecutor(
                    skyframeExecutorConsumerOnInit,
                    pkgFactory,
                    fileSystem,
                    directories,
                    actionKeyContext,
                    workspaceStatusActionFactory,
                    diffAwarenessFactories,
                    workspaceInfoFromDiffReceiver,
                    extraSkyFunctions,
                    com.google.common.base.Preconditions.checkNotNull<SyscallCache?>(syscallCache),
                    ignoredSubdirectoriesFunction,
                    crossRepositoryLabelViolationStrategy,
                    buildFilesByPriority,
                    allowExternalRepositories,
                    repoContentsCachePathSupplier,
                    actionOnIOExceptionReadingBuildFile,
                    actionOnFilesystemErrorCodeLoadingBzlFile,
                    shouldUseRepoDotBazel,
                    skyKeyStateReceiver,
                    bugReporter,
                    globUnderSingleDep,
                    java.util.Optional.ofNullable<DiffCheckNotificationOptions?>(diffCheckNotificationOptions)
                )
            skyframeExecutor.init()
            return skyframeExecutor
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setPkgFactory(pkgFactory: PackageFactory?): Builder {
            this.pkgFactory = pkgFactory
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setFileSystem(fileSystem: com.google.devtools.build.lib.vfs.FileSystem?): Builder {
            this.fileSystem = fileSystem
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setDirectories(directories: BlazeDirectories?): Builder {
            this.directories = directories
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setActionKeyContext(actionKeyContext: ActionKeyContext?): Builder {
            this.actionKeyContext = actionKeyContext
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setIgnoredSubdirectories(ignoredSubdirectoriesFunction: SkyFunction?): Builder {
            this.ignoredSubdirectoriesFunction = ignoredSubdirectoriesFunction
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setBugReporter(bugReporter: BugReporter?): Builder {
            this.bugReporter = bugReporter
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setExtraSkyFunctions(
            extraSkyFunctions: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>?
        ): Builder {
            this.extraSkyFunctions = extraSkyFunctions
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setWorkspaceStatusActionFactory(workspaceStatusActionFactory: Factory?): Builder {
            this.workspaceStatusActionFactory = workspaceStatusActionFactory
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setDiffAwarenessFactories(
            diffAwarenessFactories: Iterable<out DiffAwareness.Factory?>?
        ): Builder {
            this.diffAwarenessFactories = diffAwarenessFactories
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setWorkspaceInfoFromDiffReceiver(
            workspaceInfoFromDiffReceiver: WorkspaceInfoFromDiffReceiver?
        ): Builder {
            this.workspaceInfoFromDiffReceiver = workspaceInfoFromDiffReceiver
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun allowExternalRepositories(allowExternalRepositories: Boolean): Builder {
            this.allowExternalRepositories = allowExternalRepositories
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setRepoContentsCachePathSupplier(repoContentsCachePathSupplier: java.util.function.Supplier<com.google.devtools.build.lib.vfs.Path?>?): Builder {
            this.repoContentsCachePathSupplier = repoContentsCachePathSupplier
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setCrossRepositoryLabelViolationStrategy(
            crossRepositoryLabelViolationStrategy: CrossRepositoryLabelViolationStrategy?
        ): Builder {
            this.crossRepositoryLabelViolationStrategy = crossRepositoryLabelViolationStrategy
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setBuildFilesByPriority(buildFilesByPriority: com.google.common.collect.ImmutableList<BuildFileName?>?): Builder {
            this.buildFilesByPriority = buildFilesByPriority
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setActionOnIOExceptionReadingBuildFile(
            actionOnIOExceptionReadingBuildFile: ActionOnIOExceptionReadingBuildFile?
        ): Builder {
            this.actionOnIOExceptionReadingBuildFile = actionOnIOExceptionReadingBuildFile
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setActionOnFilesystemErrorCodeLoadingBzlFile(
            actionOnFilesystemErrorCodeLoadingBzlFile: ActionOnFilesystemErrorCodeLoadingBzlFile?
        ): Builder {
            this.actionOnFilesystemErrorCodeLoadingBzlFile = actionOnFilesystemErrorCodeLoadingBzlFile
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setShouldUseRepoDotBazel(shouldUseRepoDotBazel: Boolean): Builder {
            this.shouldUseRepoDotBazel = shouldUseRepoDotBazel
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setSkyframeExecutorConsumerOnInit(
            skyframeExecutorConsumerOnInit: java.util.function.Consumer<SkyframeExecutor?>?
        ): Builder {
            this.skyframeExecutorConsumerOnInit = skyframeExecutorConsumerOnInit
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setSkyKeyStateReceiver(skyKeyStateReceiver: SkyKeyStateReceiver?): Builder {
            this.skyKeyStateReceiver =
                com.google.common.base.Preconditions.checkNotNull<SkyKeyStateReceiver>(skyKeyStateReceiver)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setSyscallCache(syscallCache: SyscallCache?): Builder {
            this.syscallCache = syscallCache
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setGlobUnderSingleDep(globUnderSingleDep: Boolean): Builder {
            this.globUnderSingleDep = globUnderSingleDep
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setDiffCheckNotificationOptions(
            diffCheckNotificationOptions: DiffCheckNotificationOptions?
        ): Builder {
            this.diffCheckNotificationOptions = diffCheckNotificationOptions
            return this
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
        private const val MODIFIED_OUTPUT_PATHS_SAMPLE_SIZE = 100

        private fun rewindingEnabled(options: com.google.devtools.common.options.OptionsProvider): Boolean {
            val buildRequestOptions: O? = options.getOptions<O?>(BuildRequestOptions::class.java)
            return buildRequestOptions != null && buildRequestOptions.rewindLostInputs
        }

        /**
         * The value types whose builders have direct access to the package locator, rather than accessing
         * it via an explicit Skyframe dependency. They need to be invalidated if the package locator
         * changes.
         */
        private val PACKAGE_LOCATOR_DEPENDENT_VALUES: com.google.common.collect.ImmutableSet<SkyFunctionName?> =
            com.google.common.collect.ImmutableSet.of<SkyFunctionName?>(
                FileStateKey.FILE_STATE,
                SkyFunctions.FILE,
                SkyFunctions.DIRECTORY_LISTING_STATE,
                SkyFunctions.PREPARE_DEPS_OF_PATTERN,
                SkyFunctions.TARGET_PATTERN,
                SkyFunctions.TARGET_PATTERN_PHASE
            )

        private fun addStarlarkProviders(
            providers: TransitiveInfoProviderMap,
            starlarkProviders: com.google.common.collect.Multiset<StarlarkProvider?>
        ) {
            for (i in 0..<providers.providerCount) {
                if (providers.getProviderInstanceAt(i) is StarlarkInfo
                    && info.getProvider() is StarlarkProvider
                    && !provider.getLocation().file().startsWith("/virtual_builtins_bzl/")
                ) {
                    starlarkProviders.add(provider)
                }
            }
        }

        private fun isExecConfig(bck: BuildConfigurationKey?): Boolean {
            return bck != null && bck.getOptions().get(CoreOptions::class.java).getIsExec()
        }

        fun builder(): Builder {
            return com.google.devtools.build.lib.skyframe.SequencedSkyframeExecutor.Builder()
        }
    }
}
