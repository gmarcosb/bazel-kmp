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

import com.google.devtools.build.lib.buildtool.BuildRequestOptions.MAX_JOBS

/**
 * Action executor: takes care of preparing an action for execution, executing it, validating that
 * all output artifacts were created, error reporting, etc.
 */
class SkyframeActionExecutor internal constructor(
    actionKeyContext: ActionKeyContext?,
    outputArtifactsSeen: MetadataConsumerForMetrics,
    outputArtifactsFromActionCache: MetadataConsumerForMetrics,
    statusReporterRef: AtomicReference<ActionExecutionStatusReporter?>,
    sourceRootSupplier: java.util.function.Supplier<com.google.common.collect.ImmutableList<Root?>?>,
    syscallCache: SyscallCache?,
    threadStateReceiverFactory: java.util.function.Function<SkyKey?, ThreadStateReceiver?>,
    actionLookupValuePeeker: ExistingActionLookupValuePeeker
) {
    private val actionKeyContext: ActionKeyContext?
    private val outputArtifactsSeen: MetadataConsumerForMetrics
    private val outputArtifactsFromActionCache: MetadataConsumerForMetrics
    private val syscallCache: SyscallCache?
    private val threadStateReceiverFactory: java.util.function.Function<SkyKey?, ThreadStateReceiver?>
    private val actionLookupValuePeeker: ExistingActionLookupValuePeeker
    private var reporter: com.google.devtools.build.lib.events.Reporter? = null
    private var clientEnv: com.google.common.collect.ImmutableMap<String?, String?> =
        com.google.common.collect.ImmutableMap.of<String?, String?>()
    private var executorEngine: Executor? = null
    private var progressSuppressingEventHandler: ExtendedEventHandler? = null
    private var actionLogBufferPathGenerator: ActionLogBufferPathGenerator? = null
    private var actionCacheChecker: ActionCacheChecker? = null

    // We keep track of actions already executed this build in order to avoid executing a shared
    // action twice. Note that we may still unnecessarily re-execute the action on a subsequent
    // build: say actions A and B are shared. If A is requested on the first build and then B is
    // requested on the second build, we will execute B even though its output files are up to date.
    // However, we will not re-execute A on a subsequent build.
    // We do not allow the shared action to re-execute in the same build, even after the first
    // action has finished execution, because a downstream action might be reading the output file
    // at the same time as the shared action was writing to it.
    //
    // This map is also used for Actions that try to execute twice because they have discovered
    // headers -- the SkyFunction tries to declare a dep on the missing headers and has to restart.
    // We don't want to execute the action again on the second entry to the SkyFunction.
    // In both cases, we store the already-computed ActionExecutionValue to avoid having to compute it
    // again.
    private var buildActionMap: ConcurrentMap<OwnerlessArtifactWrapper?, ActionExecutionState>? = null

    // We also keep track of actions which were rewound this build, possibly from a
    // previously-completed state. When re-evaluated, these actions should not emit progress events,
    // in order to not confuse the downstream consumers of action-related event streams, which may
    // (reasonably) have expected an action to be executed at most once per build.
    //
    // Note: actions which fail due to lost inputs, and get reset (having not completed successfully),
    // will not have any events suppressed during their second evaluation. Consumers of events which
    // get emitted before execution (e.g. ActionStartedEvent, SpawnExecutedEvent) must support
    // receiving more than one of those events per action.
    private var rewoundActions: MutableSet<OwnerlessArtifactWrapper?>? = null

    private var outputDirectoryHelper: ActionOutputDirectoryHelper? = null

    private var options: com.google.devtools.common.options.OptionsProvider? = null
    private val hadExecutionError: AtomicBoolean = AtomicBoolean(false)
    private var freeDiscoveredInputsAfterExecution = false
    private var perBuildFileCache: InputMetadataProvider? = null
    private var actionInputPrefetcher: ActionInputPrefetcher? = null
    private var actionExecutionSalt: String? = null
    private var maxStdoutErrBytes = 0

    /** These variables are nulled out between executions.  */
    private var progressSupplier: ProgressSupplier? = null

    private var completionReceiver: ActionCompletedReceiver? = null

    private val statusReporterRef: AtomicReference<ActionExecutionStatusReporter?>
    private var outputService: OutputService? = null
    private var finalizeActions = false
    private var rewindingEnabled = false
    private var invocationRetriesEnabled = false
    private val sourceRootSupplier: java.util.function.Supplier<com.google.common.collect.ImmutableList<Root?>?>

    private var discoveredModulesPruner: DiscoveredModulesPruner? = null

    private var cacheHitSemaphore: Semaphore? = null

    /**
     * Meter used to limit the number of concurrent actions.
     * 
     * 
     * With internal changes in JDK19, ForkJoinPool can spawn more threads than requested
     * parallelism which means we couldn't rely on it if we want the number of concurrent actions to
     * be exactly equal to `--jobs`.
     * 
     * 
     * When async exec is enabled, we execute actions with virtual threads and this meter is used
     * to limit the number of concurrent actions.
     */
    private var actionConcurrencyMeter: ActionConcurrencyMeter? = null

    init {
        this.actionKeyContext = actionKeyContext
        this.outputArtifactsSeen = outputArtifactsSeen
        this.outputArtifactsFromActionCache = outputArtifactsFromActionCache
        this.statusReporterRef = statusReporterRef
        this.sourceRootSupplier = sourceRootSupplier
        this.syscallCache = syscallCache
        this.threadStateReceiverFactory = threadStateReceiverFactory
        this.actionLookupValuePeeker = actionLookupValuePeeker
    }

    /**
     * Helper for determining if an [ActionLookupData] has been rewound.
     * 
     * 
     * This is used during action execution when the [ActionLookupData] is available, but the
     * corresponding [Action] is not, to determine if the action has been rewound, without
     * creating a dependency.
     * 
     * 
     * The absence of an [ActionLookupValue] implies that the action has not been rewound,
     * without needing to declare a Skyframe dependency. This is useful in the case where the values
     * can be retrieved remotely.
     */
    internal interface ExistingActionLookupValuePeeker {
        @Throws(java.lang.InterruptedException::class)
        fun getExistingActionLookupValue(key: ActionLookupKey?): ActionLookupValue?
    }

    fun getSharedActionCallback(
        eventHandler: ExtendedEventHandler,
        hasDiscoveredInputs: Boolean,
        action: Action?,
        actionLookupData: ActionLookupData?
    ): SharedActionCallback {
        return object : SharedActionCallback() {
            public override fun actionStarted() {
                if (hasDiscoveredInputs) {
                    eventHandler.post(ActionScanningCompletedEvent(action, actionLookupData))
                }
            }

            public override fun actionCompleted() {
                if (completionReceiver != null) {
                    completionReceiver!!.actionCompleted(actionLookupData)
                }
            }
        }
    }

    fun prepareForExecution(
        reporter: com.google.devtools.build.lib.events.Reporter?,
        executor: Executor?,
        options: com.google.devtools.common.options.OptionsProvider,
        actionCacheChecker: ActionCacheChecker?,
        outputDirectoryHelper: ActionOutputDirectoryHelper?,
        outputService: OutputService?,
        keepStateAfterBuild: Boolean
    ) {
        this.reporter =
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.events.Reporter?>(reporter)
        this.executorEngine = com.google.common.base.Preconditions.checkNotNull<Executor?>(executor)
        this.progressSuppressingEventHandler = ProgressSuppressingEventHandler(reporter)

        val buildRequestOptions: O? = options.getOptions<O?>(BuildRequestOptions::class.java)

        // Start with a new map each build so there's no issue with internal resizing.
        this.buildActionMap =
            com.google.common.collect.Maps.newConcurrentMap<OwnerlessArtifactWrapper?, ActionExecutionState>()
        this.rewoundActions = com.google.common.collect.Sets.newConcurrentHashSet<OwnerlessArtifactWrapper?>()
        this.hadExecutionError.set(false)
        this.actionCacheChecker =
            com.google.common.base.Preconditions.checkNotNull<ActionCacheChecker?>(actionCacheChecker)
        // Don't cache possibly stale data from the last build.
        this.options = options
        // Cache some option values for performance, since we consult them on every action.
        this.finalizeActions = buildRequestOptions.finalizeActions
        this.rewindingEnabled = buildRequestOptions.rewindLostInputs
        this.invocationRetriesEnabled =
            options.getOptions<O?>(ExecutionOptions::class.java).remoteRetryOnTransientCacheError > 0
        this.outputService = com.google.common.base.Preconditions.checkNotNull<OutputService?>(outputService)
        this.outputDirectoryHelper = outputDirectoryHelper

        // Retaining discovered inputs is only worthwhile for incremental builds or builds with extra
        // actions. Starlark actions shadowing others are not a problem, though, because the issue is
        // not computing the inputs of the shadowing action / extra action (
        // getInputFilesForExtraAction() works the same whether input discovery was run or not), but
        // getExtraActionInfo().
        this.freeDiscoveredInputsAfterExecution =
            !keepStateAfterBuild
                    && options.getOptions<O?>(CoreOptions::class.java).getActionListeners().isEmpty()

        val useAsyncExecution: Boolean = buildRequestOptions.useAsyncExecution

        this.cacheHitSemaphore =
            if (!useAsyncExecution && options.getOptions<O?>(CoreOptions::class.java).getThrottleActionCacheCheck())
                Semaphore(java.lang.Runtime.getRuntime().availableProcessors())
            else
                null

        val minActiveAction: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            buildRequestOptions.jobs
        val maxActiveAction: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            if (useAsyncExecution)
        <T > min<T?>(MAX_JOBS, buildRequestOptions.asyncExecutionMaxConcurrentActions)
        else
        minActiveAction
        this.actionConcurrencyMeter =
            ActionConcurrencyMeter(
                minActiveAction,
                com.google.common.collect.Comparators.max<T?>(minActiveAction, maxActiveAction)
            )
    }

    fun setActionLogBufferPathGenerator(
        actionLogBufferPathGenerator: ActionLogBufferPathGenerator
    ) {
        this.actionLogBufferPathGenerator = actionLogBufferPathGenerator
    }

    fun setClientEnv(clientEnv: MutableMap<String?, String?>) {
        // Copy once here, instead of on every construction of ActionExecutionContext.
        this.clientEnv = com.google.common.collect.ImmutableMap.copyOf<String?, String?>(clientEnv)
    }

    fun getOutputService(): OutputService? {
        return outputService
    }

    fun getActionInputPrefetcher(): ActionInputPrefetcher? {
        return actionInputPrefetcher
    }

    fun actionFileSystemType(): ActionFileSystemType? {
        return outputService.actionFileSystemType()
    }

    val execRoot: com.google.devtools.build.lib.vfs.Path
        get() = executorEngine.getExecRoot()

    val actionContextRegistry: ActionContextRegistry?
        get() = executorEngine

    fun useArchivedTreeArtifacts(action: ActionAnalysisMetadata): Boolean {
        // Check that the action produces at least one tree artifact to simplify downstream logic: we
        // don't need to take archived tree artifacts into account if the action doesn't produce at
        // least one of them.
        return archivedTreeArtifactsEnabledForMnemonic(action) && hasTreeArtifactOutputs(action)
    }

    private fun archivedTreeArtifactsEnabledForMnemonic(action: ActionAnalysisMetadata): Boolean {
        return options
            .getOptions<O?>(CoreOptions::class.java)
            .getArchivedArtifactsMnemonicsFilter()
            .test(action.getMnemonic())
    }

    private fun hasTreeArtifactOutputs(action: ActionAnalysisMetadata): Boolean {
        for (output in action.getOutputs()) {
            if (output.isTreeArtifact()) {
                return true
            }
        }
        return false
    }

    fun publishTargetSummaries(): Boolean {
        return options.getOptions<O?>(BuildEventProtocolOptions::class.java).publishTargetSummary
    }

    fun rewindingEnabled(): Boolean {
        return rewindingEnabled
    }

    fun invocationRetriesEnabled(): Boolean {
        return invocationRetriesEnabled
    }

    fun getPerBuildFileCache(): InputMetadataProvider? {
        return perBuildFileCache
    }

    val outputPermissions: OutputPermissions
        get() = if (options.getOptions<O?>(CoreOptions::class.java).getExperimentalWritableOutputs())
            OutputPermissions.WRITABLE
        else
            OutputPermissions.READONLY

    val xattrProvider: XattrProvider
        get() = com.google.common.base.Preconditions.checkNotNull<XattrProvider>(
            outputService.getXattrProvider(
                syscallCache
            )
        )

    /** REQUIRES: [.actionFileSystemType] to be not `DISABLED`.  */
    fun createActionFileSystem(
        relativeOutputPath: String?,
        inputArtifactData: InputMetadataProvider?,
        outputArtifacts: Iterable<Artifact?>?
    ): com.google.devtools.build.lib.vfs.FileSystem? {
        return outputService.createActionFileSystem(
            executorEngine.getFileSystem(),
            executorEngine.getExecRoot().asFragment(),
            relativeOutputPath,
            sourceRootSupplier.get(),
            inputArtifactData,
            outputArtifacts,
            rewindingEnabled
        )
    }

    private fun updateActionFileSystemContext(
        action: Action?,
        actionFileSystem: com.google.devtools.build.lib.vfs.FileSystem?,
        outputMetadataStore: OutputMetadataStore?
    ) {
        outputService.updateActionFileSystemContext(action, actionFileSystem, outputMetadataStore)
    }

    fun executionOver() {
        // These may transitively holds a bunch of heavy objects, so it's important to clear it at the
        // end of a build.
        this.reporter = null
        this.options = null
        this.executorEngine = null
        this.progressSuppressingEventHandler = null
        this.outputService = null
        this.buildActionMap = null
        this.rewoundActions = null
        this.actionCacheChecker = null
        this.outputDirectoryHelper = null
        this.actionConcurrencyMeter.stop()
        this.actionConcurrencyMeter = null
    }

    /**
     * Due to multi-threading, a null return value from this method does not guarantee that there is
     * no such action - a concurrent thread may already be executing the same (shared) action. Any
     * such race is resolved in the subsequent call to [.executeAction].
     */
    fun probeActionExecution(action: Action): ActionExecutionState? {
        return buildActionMap.get(OwnerlessArtifactWrapper(action.getPrimaryOutput()))
    }

    /** Determines whether the given action was rewound during the current build.  */
    fun wasRewound(action: ActionAnalysisMetadata): Boolean {
        return rewoundActions!!.contains(OwnerlessArtifactWrapper(action.getPrimaryOutput()))
    }

    /**
     * True if remote retrieval should be skipped for this `lookupData` because it was rewound.
     * 
     * 
     * This happens when an action fails to execute because one of its inputs was lost. It usually
     * indicates that the remotely retrieved `ActionExecutionValue` references remote data that
     * is inaccessible.
     */
    @Throws(java.lang.InterruptedException::class)
    fun shouldSkipRetrieval(lookupData: ActionLookupData): Boolean {
        val lookupValue: ActionLookupValue? =
            actionLookupValuePeeker.getExistingActionLookupValue(lookupData.getActionLookupKey())
        if (lookupValue == null) {
            // Since rewinding causes the owner of the corresponding action to be analyzed, if the action
            // owner is missing, then rewinding has not occurred.
            return false
        }
        return wasRewound(lookupValue.getActions().get(lookupData.getActionIndex()))
    }

    val rewoundActionCount: Int
        /**
         * Returns the count of actions rewound during the current build.
         * 
         * 
         * If an action is rewound multiple times, it is only counted once.
         */
        get() = rewoundActions!!.size

    /**
     * Determines whether the action should have its progress events emitted.
     * 
     * 
     * Returns `false` for rewound actions, indicating that their progress events should be
     * suppressed.
     */
    fun shouldEmitProgressEvents(action: Action): Boolean {
        return !wasRewound(action)
    }

    /**
     * Called to prepare action execution states for rewinding after `failedAction` observed
     * lost inputs.
     */
    fun prepareForRewinding(
        failedKey: ActionLookupData?,
        failedAction: Action,
        depsToRewind: com.google.common.collect.ImmutableList<ActionAnalysisMetadata>
    ) {
        val ownerlessArtifactWrapper: OwnerlessArtifactWrapper =
            OwnerlessArtifactWrapper(failedAction.getPrimaryOutput())
        val state: ActionExecutionState? = buildActionMap.get(ownerlessArtifactWrapper)
        if (state != null) {
            // If an action failed from lost inputs during input discovery then it won't have a state to
            // obsolete.
            state.obsolete(failedKey, buildActionMap, ownerlessArtifactWrapper)
        }
        if (!actionFileSystemType().inMemoryFileSystem()) {
            outputDirectoryHelper.invalidateTreeArtifactDirectoryCreation(failedAction.getOutputs())
        }
        for (dep in depsToRewind) {
            prepareDepForRewinding(failedKey, dep)
        }
    }

    fun prepareDepForRewinding(failedKey: SkyKey?, dep: ActionAnalysisMetadata) {
        val ownerlessArtifactWrapper: OwnerlessArtifactWrapper =
            OwnerlessArtifactWrapper(dep.getPrimaryOutput())
        if (dep !is Action) {
            // ActionTemplate does not have an ActionExecutionState and it is not executed, so we just
            // mark it as rewound.
            com.google.common.base.Preconditions.checkState(dep is ActionTemplate, "dep of unexpected type %s", dep)
            rewoundActions!!.add(ownerlessArtifactWrapper)
            return
        }
        val actionExecutionState: ActionExecutionState? = buildActionMap.get(ownerlessArtifactWrapper)
        if (actionExecutionState != null) {
            actionExecutionState.obsolete(failedKey, buildActionMap, ownerlessArtifactWrapper)
        }
        rewoundActions!!.add(ownerlessArtifactWrapper)
        if (!actionFileSystemType().inMemoryFileSystem()) {
            outputDirectoryHelper.invalidateTreeArtifactDirectoryCreation(dep.getOutputs())
        }
        // Evict the rewinding action from the action cache to ensure that it is executed.
        if (actionCacheChecker.enabled()) {
            actionCacheChecker.removeCacheEntry(dep)
        }
    }

    /**
     * Executes the provided action on the current thread. Returns the ActionExecutionValue with the
     * result, either computed here or already computed on another thread.
     */
    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
    fun executeAction(
        env: SkyFunction.Environment,
        action: Action,
        compositeInputMetadataProvider: InputMetadataProvider?,
        outputMetadataStore: ActionOutputMetadataStore?,
        actionStartTime: Long,
        actionLookupData: ActionLookupData?,
        actionFileSystem: com.google.devtools.build.lib.vfs.FileSystem?,
        postprocessing: ActionPostprocessing,
        hasDiscoveredInputs: Boolean
    ): ActionExecutionValue? {
        if (actionFileSystem != null) {
            updateActionFileSystemContext(action, actionFileSystem, outputMetadataStore)
        }

        val actionExecutionContext: ActionExecutionContext =
            getContext(
                action,
                compositeInputMetadataProvider,
                outputMetadataStore,
                actionFileSystem,
                actionLookupData
            )

        if (actionCacheChecker.isActionExecutionProhibited(action)) {
            // We can't execute an action (e.g. because --check_???_up_to_date option was used). Fail the
            // build instead.
            val message = action.prettyPrint() + " is not up-to-date"
            val code: DetailedExitCode = createDetailedExitCode(message, Code.ACTION_NOT_UP_TO_DATE)
            val e: ActionExecutionException = ActionExecutionException(message, action, false, code)
            val error: com.google.devtools.build.lib.events.Event? =
                com.google.devtools.build.lib.events.Event.error(e.getMessage())
            synchronized(reporter) {
                reporter.handle(error)
            }
            throw e
        }

        // Use computeIfAbsent to handle concurrent attempts to execute the same shared action.
        val activeAction: ActionExecutionState =
            buildActionMap.computeIfAbsent(
                OwnerlessArtifactWrapper(action.getPrimaryOutput())
            ) { unusedKey: OwnerlessArtifactWrapper? ->
                ActionExecutionState(
                    actionLookupData,
                    ActionRunner(
                        action,
                        compositeInputMetadataProvider,
                        outputMetadataStore,
                        actionStartTime,
                        actionExecutionContext,
                        actionLookupData,
                        postprocessing
                    )
                )
            }

        val callback: SharedActionCallback =
            getSharedActionCallback(env.getListener(), hasDiscoveredInputs, action, actionLookupData)

        var result: ActionExecutionValue? = null
        var finalException: ActionExecutionException? = null

        try {
            result = activeAction.getResultOrDependOnFuture(env, actionLookupData, action, callback)
        } catch (e: ActionExecutionException) {
            finalException = e
        }

        if (result != null || finalException != null) {
            closeContext(actionExecutionContext, action, finalException)
        }
        return result
    }

    fun acquireActionExecutionSemaphore() {
        // Acquire uninterruptibly because ActionExecutionFunction is not expected to check for
        // interrupts. See test SequencedSkyframeExecutorTest#testThreeSharedActionsRacing.
        actionConcurrencyMeter.acquireUninterruptibly()
    }

    fun releaseActionExecutionSemaphore() {
        actionConcurrencyMeter.release()
    }

    private fun selectEventHandler(action: Action): ExtendedEventHandler {
        return selectEventHandler(shouldEmitProgressEvents(action))
    }

    private fun selectEventHandler(emitProgressEvents: Boolean): ExtendedEventHandler {
        return if (emitProgressEvents) reporter else progressSuppressingEventHandler
    }

    private fun getContext(
        action: Action,
        compositeInputMetadataProvider: InputMetadataProvider?,
        outputMetadataStore: OutputMetadataStore?,
        actionFileSystem: com.google.devtools.build.lib.vfs.FileSystem?,
        actionLookupData: ActionLookupData?
    ): ActionExecutionContext {
        val emitProgressEvents = shouldEmitProgressEvents(action)
        val artifactPathResolver: ArtifactPathResolver? =
            ArtifactPathResolver.createPathResolver(actionFileSystem, executorEngine.getExecRoot())
        val fileOutErr: FileOutErr? = actionLogBufferPathGenerator.generate(artifactPathResolver)
        return ActionExecutionContext(
            executorEngine,
            compositeInputMetadataProvider,
            actionInputPrefetcher,
            actionKeyContext,
            outputMetadataStore,
            rewindingEnabled,
            lostInputsCheck(actionFileSystem, action, outputService),
            fileOutErr,
            selectEventHandler(emitProgressEvents),
            clientEnv,
            actionFileSystem,
            discoveredModulesPruner,
            syscallCache,
            threadStateReceiverFactory.apply(actionLookupData)
        )
    }

    /**
     * Checks the action cache to see if `action` needs to be executed, or is up to date.
     * Returns a token with the semantics of [ActionCacheChecker.getTokenIfNeedToExecute]: null
     * if the action is up to date, and non-null if it needs to be executed, in which case that token
     * should be provided to the ActionCacheChecker after execution.
     */
    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
    fun checkActionCache(
        eventHandler: ExtendedEventHandler,
        action: Action,
        inputMetadataProvider: InputMetadataProvider?,
        outputMetadataStore: OutputMetadataStore,
        artifactPathResolver: ArtifactPathResolver?,
        actionStartTime: Long,
        resolvedCacheArtifacts: MutableList<Artifact?>?,
        clientEnv: MutableMap<String?, String?>?
    ): Token? {
        var token: Token?
        val handler: com.google.devtools.build.lib.events.EventHandler?
        var outputChecker: OutputChecker? = null

        if (cacheHitSemaphore != null) {
            val stopwatch: com.google.common.base.Stopwatch = com.google.common.base.Stopwatch.createStarted()
            Profiler.instance().profile(ProfilerTask.ACTION_CHECK, "acquiring semaphore").use { c ->
                cacheHitSemaphore.acquire()
            }
            stopwatch.stop()
            actionCacheChecker.addCacheCheckSemaphoreWaitTime(stopwatch.elapsed().toMillis())
        }
        try {
            Profiler.instance().profile(ProfilerTask.ACTION_CHECK, action.describe()).use { c ->
                outputChecker = outputService.getOutputChecker()
                handler =
                    if (options.getOptions<O?>(BuildRequestOptions::class.java).explanationPath != null)
                        reporter
                    else
                        null
                token =
                    actionCacheChecker.getTokenIfNeedToExecute(
                        action,
                        resolvedCacheArtifacts,
                        clientEnv,
                        this.outputPermissions,
                        handler,
                        inputMetadataProvider,
                        outputMetadataStore,
                        actionExecutionSalt,
                        outputChecker,
                        useArchivedTreeArtifacts(action)
                    )
                if (token == null) {
                    val eventPosted = false

                    if (action is NotifyOnActionCacheHit) {
                        val contextEventHandler: ExtendedEventHandler = selectEventHandler(action)
                        val context: ActionCachedContext =
                            object : ActionCachedContext() {
                                val eventHandler: ExtendedEventHandler
                                    get() = contextEventHandler

                                val execRoot: com.google.devtools.build.lib.vfs.Path
                                    get() = executorEngine.getExecRoot()

                                val pathResolver: ArtifactPathResolver?
                                    get() = artifactPathResolver

                                public override fun <T : ActionContext?> getContext(type: java.lang.Class<out T?>?): T? {
                                    return executorEngine.getContext(type)
                                }
                            }
                        val recordActionCacheHit: Boolean = action.actionCacheHit(context)
                        if (!recordActionCacheHit) {
                            token =
                                actionCacheChecker.getTokenUnconditionallyAfterFailureToRecordActionCacheHit(
                                    action,
                                    resolvedCacheArtifacts,
                                    clientEnv,
                                    this.outputPermissions,
                                    handler,
                                    inputMetadataProvider,
                                    outputMetadataStore,
                                    actionExecutionSalt,
                                    outputChecker,
                                    useArchivedTreeArtifacts(action)
                                )
                        }
                    }

                    // We still need to check the outputs so that output file data is available to the value.
                    // Filesets cannot be cached in the action cache, so it is fine to pass null here.
                    val unused =
                        checkOutputs(
                            action,
                            outputMetadataStore,  /* actionExecutionContext= */
                            null,  /* isActionCacheHitForMetrics= */
                            true
                        )
                    if (!eventPosted) {
                        eventHandler.post(
                            CachedActionEvent(
                                action,
                                inputMetadataProvider,
                                actionStartTime,
                                com.google.devtools.build.lib.clock.BlazeClock.nanoTime()
                            )
                        )
                    }
                }
            }
        } finally {
            if (cacheHitSemaphore != null) {
                cacheHitSemaphore.release()
            }
        }
        return token
    }

    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
    fun updateActionCache(
        action: Action,
        inputMetadataProvider: InputMetadataProvider?,
        outputMetadataStore: OutputMetadataStore?,
        token: Token?,
        clientEnv: MutableMap<String?, String?>?
    ) {
        if (!actionCacheChecker.enabled()) {
            return
        }

        try {
            actionCacheChecker.updateActionCache(
                action,
                token,
                inputMetadataProvider,
                outputMetadataStore,
                clientEnv,
                this.outputPermissions,
                actionExecutionSalt,
                useArchivedTreeArtifacts(action)
            )
        } catch (e: IOException) {
            // Skyframe has already done all the filesystem access needed for outputs and swallows
            // IOExceptions for inputs. So an IOException is impossible here.
            throw java.lang.IllegalStateException(
                ("failed to update action cache for "
                        + action.prettyPrint()
                        + ", but all outputs should already have been checked"),
                e
            )
        }
    }

    @Throws(AlreadyReportedActionExecutionException::class, java.lang.InterruptedException::class)
    fun getActionCachedInputs(action: Action, resolver: PackageRootResolver?): MutableList<Artifact?>? {
        try {
            return actionCacheChecker.getCachedInputs(action, resolver)
        } catch (e: PackageRootResolver.PackageRootException) {
            printError(e.getMessage(), action)
            throw AlreadyReportedActionExecutionException(
                ActionExecutionException(
                    e,
                    action,  /* catastrophe= */
                    false,
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage(e.getMessage())
                            .setIncludeScanning(e.getError())
                            .build()
                    )
                )
            )
        }
    }

    /**
     * Perform dependency discovery for action, which must discover its inputs.
     * 
     * 
     * This method is just a wrapper around [Action.discoverInputs] that properly processes
     * any [ActionExecutionException] thrown before rethrowing it to the caller.
     */
    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
    fun discoverInputs(
        action: Action,
        actionLookupData: ActionLookupData?,
        compositeInputMetadataProvider: InputMetadataProvider?,
        env: SkyFunction.Environment,
        actionFileSystem: com.google.devtools.build.lib.vfs.FileSystem?
    ): NestedSet<Artifact?>? {
        val fileOutErr: FileOutErr =
            actionLogBufferPathGenerator.generate(
                ArtifactPathResolver.createPathResolver(
                    actionFileSystem, executorEngine.getExecRoot()
                )
            )
        val eventHandler: ExtendedEventHandler = selectEventHandler(action)
        val actionExecutionContext: ActionExecutionContext =
            ActionExecutionContext.forInputDiscovery(
                executorEngine,
                compositeInputMetadataProvider,
                actionInputPrefetcher,
                actionKeyContext,
                rewindingEnabled,
                lostInputsCheck(actionFileSystem, action, outputService),
                fileOutErr,
                eventHandler,
                clientEnv,
                env,
                actionFileSystem,
                discoveredModulesPruner,
                syscallCache,
                threadStateReceiverFactory.apply(actionLookupData),
                outputService.actionFileSystemType().supportsInputDiscovery()
            )
        if (actionFileSystem != null) {
            updateActionFileSystemContext(
                action, actionFileSystem, THROWING_OUTPUT_METADATA_STORE_FOR_ACTIONFS
            )
            // Note that when not using ActionFS, a global setup of the parent directories of the OutErr
            // streams is sufficient.
            setupActionFsFileOutErr(fileOutErr, action)
        }
        eventHandler.post(ScanningActionEvent(action))

        var finalException: ActionExecutionException? = null
        try {
            val artifacts: NestedSet<Artifact?>? = action.discoverInputs(actionExecutionContext)

            // Input discovery may have been affected by lost inputs. If an action filesystem is used, it
            // may know whether inputs were lost. We should fail fast if any were; rewinding may be able
            // to fix it.
            checkActionFileSystemForLostInputs(actionFileSystem, action, outputService)

            return artifacts
        } catch (e: ActionExecutionException) {
            // Input discovery failures may be caused by lost inputs. Lost input failures have higher
            // priority because rewinding may be able to restore what was lost and allow the action to
            // complete without error.
            if (e !is LostInputsActionExecutionException) {
                try {
                    checkActionFileSystemForLostInputs(actionFileSystem, action, outputService)
                } catch (lostInputsException: LostInputsActionExecutionException) {
                    e = lostInputsException
                }
            }

            val primaryOutputPath: com.google.devtools.build.lib.vfs.Path? =
                actionExecutionContext.getInputPath(action.getPrimaryOutput())
            if (e is LostInputsActionExecutionException) {
                // If inputs were lost during input discovery, then enrich the exception, informing action
                // rewinding machinery that these lost inputs are now Skyframe deps of the action.
                e.setFromInputDiscovery()
                enrichLostInputsException(
                    primaryOutputPath, actionLookupData, fileOutErr, e
                )
                finalException = e
            } else {
                finalException =
                    processAndGetExceptionToThrow(
                        env.getListener(),
                        primaryOutputPath,
                        action,
                        e,
                        fileOutErr,
                        ErrorTiming.BEFORE_EXECUTION
                    )
            }
            throw finalException
        } finally {
            eventHandler.post(StoppedScanningActionEvent(action))
            closeContext(actionExecutionContext, action, finalException)
        }
    }

    /**
     * This method should be called if the builder encounters an error during execution. This allows
     * the builder to record that it encountered at least one error, and may make it swallow its
     * output to prevent spamming the user any further.
     */
    fun recordExecutionError() {
        hadExecutionError.set(true)
    }

    private val isBuilderAborting: Boolean
        /**
         * Returns true if the Builder is winding down (i.e. cancelling outstanding actions and preparing
         * to abort.) The builder is winding down iff:
         * 
         * 
         *  * we had an execution error
         *  * we are not running with --keep_going
         * 
         */
        get() = hadExecutionError.get() && !options.getOptions<O?>(KeepGoingOption::class.java).getKeepGoing()

    fun configure(
        fileCache: InputMetadataProvider?,
        actionInputPrefetcher: ActionInputPrefetcher?,
        discoveredModulesPruner: DiscoveredModulesPruner?,
        actionExecutionSalt: String?,
        maxStdoutErrBytes: Int
    ) {
        this.perBuildFileCache = fileCache
        this.actionInputPrefetcher = actionInputPrefetcher
        this.discoveredModulesPruner = discoveredModulesPruner
        this.actionExecutionSalt = actionExecutionSalt
        this.maxStdoutErrBytes = maxStdoutErrBytes
    }

    /**
     * Temporary interface to allow delegation of action postprocessing to ActionExecutionFunction.
     * The current implementation requires access to local fields in ActionExecutionFunction.
     */
    internal interface ActionPostprocessing {
        @Throws(java.lang.InterruptedException::class, ActionExecutionException::class)
        fun run(
            env: SkyFunction.Environment?,
            action: Action?,
            inputMetadataProvider: InputMetadataProvider?,
            outputMetadataStore: OutputMetadataStore?,
            clientEnv: MutableMap<String?, String?>?
        )
    }

    /** Represents an action that needs to be run.  */
    private inner class ActionRunner(
        action: Action,
        inputMetadataProvider: InputMetadataProvider?,
        outputMetadataStore: ActionOutputMetadataStore?,
        actionStartTimeNanos: Long,
        actionExecutionContext: ActionExecutionContext,
        actionLookupData: ActionLookupData?,
        postprocessing: ActionPostprocessing
    ) : ActionStep() {
        private val action: Action
        private val inputMetadataProvider: InputMetadataProvider?
        private val outputMetadataStore: ActionOutputMetadataStore?
        private val actionStartTimeNanos: Long
        private val actionExecutionContext: ActionExecutionContext
        private val actionLookupData: ActionLookupData?
        private val statusReporter: ActionExecutionStatusReporter?
        private val postprocessing: ActionPostprocessing

        init {
            this.action = action
            this.inputMetadataProvider = inputMetadataProvider
            this.outputMetadataStore = outputMetadataStore
            this.actionStartTimeNanos = actionStartTimeNanos
            this.actionExecutionContext = actionExecutionContext
            this.actionLookupData = actionLookupData
            this.statusReporter = statusReporterRef.get()
            this.postprocessing = postprocessing
        }

        @Throws(LostInputsActionExecutionException::class, java.lang.InterruptedException::class)
        public override fun run(env: SkyFunction.Environment): ActionStepOrResult? {
            // There are three ExtendedEventHandler instances available while this method is running.
            //   SkyframeActionExecutor.this.reporter
            //   actionExecutionContext.getEventHandler
            //   env.getListener
            // Apparently, one isn't enough.
            //
            // Progress events that are generated in this class should be posted to env.getListener, while
            // progress events that are generated in the Action implementation are posted to
            // actionExecutionContext.getEventHandler. The reason for this is action rewinding, in which
            // case env.getListener may be a ProgressSuppressingEventHandler. See shouldEmitProgressEvents
            // and rewoundActions.
            //
            // It is also unclear why we are posting anything directly to reporter. That probably
            // shouldn't happen.
            Profiler.instance()
                .profileAction(
                    ProfilerTask.ACTION,
                    action.getMnemonic(),
                    action.describe(),
                    action.getPrimaryOutput().getExecPathString(),
                    getOwnerLabelAsString(action),
                    getOwnerConfigurationAsString(action)
                ).use { c ->
                    val message: String? = action.getProgressMessage()
                    if (message != null) {
                        reporter.startTask(null, prependExecPhaseStats(message))
                    }

                    var lostInputs = false
                    try {
                        val event: ActionStartedEvent = ActionStartedEvent(action, actionStartTimeNanos)
                        if (statusReporter != null) {
                            statusReporter.updateStatus(event)
                        }
                        env.getListener().post(event)
                        val rewoundActionSynchronizer: RewoundActionSynchronizer =
                            outputService.getRewoundActionSynchronizer()
                        rewoundActionSynchronizer.enterActionPreparation(action, wasRewound(action)).use { outerLock ->
                            if (actionFileSystemType().shouldDoEagerActionPrep()) {
                                try {
                                    Profiler.instance().profile(ProfilerTask.INFO, "action.prepare").use { d ->
                                        // This call generally deletes any files at locations that are declared outputs of
                                        // the action, although some actions perform additional work, while others
                                        // intentionally keep previous outputs in place.
                                        action.prepare(
                                            actionExecutionContext.getExecRoot(),
                                            actionExecutionContext.getPathResolver(),
                                            outputService.bulkDeleter(),
                                            useArchivedTreeArtifacts(action)
                                        )
                                    }
                                } catch (e: IOException) {
                                    logger.atWarning().withCause(e).log(
                                        "failed to delete output files before executing action: '%s'", action
                                    )
                                    throw toActionExecutionException(
                                        "failed to delete output files before executing action",
                                        e,
                                        action,
                                        null,
                                        Code.ACTION_OUTPUTS_DELETION_FAILURE
                                    )
                                }
                            }
                            if (actionFileSystemType().inMemoryFileSystem()) {
                                // There's nothing to delete when the action file system is used, but we must ensure
                                // that the output directories for stdout and stderr exist.
                                setupActionFsFileOutErr(actionExecutionContext.getFileOutErr(), action)
                                createActionFsOutputDirectories(action, actionExecutionContext.getPathResolver())
                            } else {
                                createOutputDirectories(action)
                            }
                            rewoundActionSynchronizer.enterActionExecution(
                                action, actionExecutionContext.getInputMetadataProvider()
                            ).use { innerLock ->
                                return executeAction(env.getListener(), action)
                            }
                        }
                    } catch (e: LostInputsActionExecutionException) {
                        lostInputs = true
                        throw e
                    } catch (e: ActionExecutionException) {
                        return ActionStepOrResult.of(e)
                    } finally {
                        notifyActionCompletion(env.getListener(), !lostInputs)
                    }
                }
        }

        fun getOwnerLabelAsString(action: Action): String? {
            val owner: ActionOwner? = action.getOwner()
            if (owner == null) {
                return ""
            }
            val ownerLabel: Label? = owner.getLabel()
            if (ownerLabel == null) {
                return ""
            }
            return ownerLabel.getCanonicalForm()
        }

        fun getOwnerConfigurationAsString(action: Action): String? {
            val owner: ActionOwner? = action.getOwner()
            if (owner == null) {
                return ""
            }
            return owner.getConfigurationChecksum()
        }

        fun notifyActionCompletion(
            eventHandler: ExtendedEventHandler, postActionCompletionEvent: Boolean
        ) {
            if (statusReporter != null) {
                statusReporter.remove(action)
            }
            if (postActionCompletionEvent) {
                eventHandler.post(
                    ActionCompletionEvent(
                        actionStartTimeNanos,
                        com.google.devtools.build.lib.clock.BlazeClock.nanoTime(),
                        action,
                        inputMetadataProvider,
                        outputMetadataStore,
                        actionLookupData
                    )
                )
            }
            val message: String? = action.getProgressMessage()
            if (message != null) {
                if (completionReceiver != null) {
                    completionReceiver!!.actionCompleted(actionLookupData)
                }
                reporter.finishTask(null, prependExecPhaseStats(message))
            }
        }

        @Throws(LostInputsActionExecutionException::class)
        fun maybeSignalLostInputs(
            e: ActionExecutionException?,
            primaryOutputPath: com.google.devtools.build.lib.vfs.Path?
        ) {
            var lostInputsException: LostInputsActionExecutionException? = null
            // Action failures may be caused by lost inputs. Lost input failures have higher priority
            // because rewinding may be able to restore what was lost and allow the action to complete
            // without error.
            if (e is LostInputsActionExecutionException) {
                lostInputsException = e
            } else {
                try {
                    checkActionFileSystemForLostInputs(
                        actionExecutionContext.getActionFileSystem(), action, outputService
                    )
                } catch (e2: LostInputsActionExecutionException) {
                    lostInputsException = e2
                }
            }

            if (lostInputsException == null) {
                return
            }

            // If inputs are lost, then avoid publishing ActionExecutedEvent or reporting the error.
            // Action rewinding will rerun this failed action after trying to regenerate the lost
            // inputs.
            lostInputsException.setActionStartedEventAlreadyEmitted()
            enrichLostInputsException(
                primaryOutputPath,
                actionLookupData,
                actionExecutionContext.getFileOutErr(),
                lostInputsException
            )
            throw lostInputsException
        }

        /** Executes the given action.  */
        @Throws(LostInputsActionExecutionException::class, java.lang.InterruptedException::class)
        fun executeAction(eventHandler: ExtendedEventHandler, action: Action): ActionStepOrResult? {
            val result: ActionResult?
            try {
                Profiler.instance().profile(ProfilerTask.INFO, "Action.execute").use { c ->
                    checkForUnsoundDirectoryInputs(action, actionExecutionContext.getInputMetadataProvider())
                    result = action.execute(actionExecutionContext)

                    // An action's result (or intermediate state) may have been affected by lost inputs. If an
                    // action filesystem is used, it may know whether inputs were lost. We should fail fast if
                    // any were; rewinding may be able to fix it.
                    checkActionFileSystemForLostInputs(
                        actionExecutionContext.getActionFileSystem(), action, outputService
                    )
                }
            } catch (e: ActionExecutionException) {
                val primaryOutputPath: com.google.devtools.build.lib.vfs.Path? =
                    actionExecutionContext.getInputPath(action.getPrimaryOutput())
                maybeSignalLostInputs(e, primaryOutputPath)
                return ActionStepOrResult.of(
                    processAndGetExceptionToThrow(
                        eventHandler,
                        primaryOutputPath,
                        action,
                        e,
                        actionExecutionContext.getFileOutErr(),
                        ErrorTiming.AFTER_EXECUTION
                    )
                )
            } catch (e: java.lang.InterruptedException) {
                return ActionStepOrResult.of(e)
            }

            try {
                val actionExecutionValue: ActionExecutionValue?
                Profiler.instance().profile(ProfilerTask.ACTION_COMPLETE, "actuallyCompleteAction").use { c ->
                    actionExecutionValue = actuallyCompleteAction(eventHandler, result)
                }
                eventHandler.post(ActionSuccessEvent(actionExecutionValue))
                return ActionPostprocessingStep(actionExecutionValue)
            } catch (e: ActionExecutionException) {
                return ActionStepOrResult.of(e)
            }
        }

        @Throws(
            ActionExecutionException::class,
            java.lang.InterruptedException::class
        )  // Thrown exception shown in user output, not info logs.
        fun actuallyCompleteAction(
            eventHandler: ExtendedEventHandler, actionResult: ActionResult?
        ): ActionExecutionValue {
            var outputAlreadyDumped = false
            if (actionResult !== ActionResult.EMPTY) {
                eventHandler.post(ActionResultReceivedEvent(action, actionResult))
            }

            // Action terminated fine, now report the output.
            // The .showOutput() method is not necessarily a quick check: in its
            // current implementation it uses regular expression matching.
            val outErrBuffer: FileOutErr = actionExecutionContext.getFileOutErr()
            if (outErrBuffer.hasRecordedOutput()) {
                if (action.showsOutputUnconditionally()
                    || reporter.showOutput(Label.print(action.getOwner().getLabel()))
                ) {
                    dumpRecordedOutErr(reporter, action, outErrBuffer)
                    outputAlreadyDumped = true
                }
            }

            val outputMetadataStore: OutputMetadataStore = actionExecutionContext.getOutputMetadataStore()
            val fileOutErr: FileOutErr = actionExecutionContext.getFileOutErr()
            val primaryOutput: Artifact? = action.getPrimaryOutput()
            val primaryOutputPath: com.google.devtools.build.lib.vfs.Path? =
                actionExecutionContext.getInputPath(primaryOutput)
            try {
                checkState(
                    action.inputsKnown(),
                    "Action %s successfully executed, but inputs still not known",
                    action
                )

                if (!checkOutputs(
                        action,
                        outputMetadataStore,
                        actionExecutionContext,  /* isActionCacheHitForMetrics= */
                        false
                    )
                ) {
                    throw toActionExecutionException(
                        "not all outputs were created or valid",
                        null,
                        action,
                        if (outputAlreadyDumped) null else fileOutErr,
                        Code.ACTION_OUTPUTS_NOT_CREATED
                    )
                }

                if (finalizeActions) {
                    try {
                        Profiler.instance().profile(ProfilerTask.INFO, "outputService.finalizeAction").use { c ->
                            outputService.finalizeAction(action, outputMetadataStore)
                        }
                    } catch (e: EnvironmentalExecException) {
                        logger.atWarning().withCause(e).log("unable to finalize action: '%s'", action)
                        throw toActionExecutionException(
                            "unable to finalize action",
                            e,
                            action,
                            fileOutErr,
                            Code.ACTION_FINALIZATION_FAILURE
                        )
                    } catch (e: IOException) {
                        logger.atWarning().withCause(e).log("unable to finalize action: '%s'", action)
                        throw toActionExecutionException(
                            "unable to finalize action",
                            e,
                            action,
                            fileOutErr,
                            Code.ACTION_FINALIZATION_FAILURE
                        )
                    }
                }
            } catch (actionException: ActionExecutionException) {
                // Success in execution but failure in completion.
                reportActionExecution(
                    eventHandler,
                    primaryOutputPath,  /* primaryOutputMetadata= */
                    null,
                    action,
                    actionResult,
                    actionException,
                    fileOutErr,
                    ErrorTiming.AFTER_EXECUTION
                )
                throw actionException
            } catch (exception: java.lang.IllegalStateException) {
                // More serious internal error, but failure still reported.
                reportActionExecution(
                    eventHandler,
                    primaryOutputPath,  /* primaryOutputMetadata= */
                    null,
                    action,
                    actionResult,
                    ActionExecutionException(
                        exception,
                        action,
                        true,
                        CrashFailureDetails.detailedExitCodeForThrowable(exception)
                    ),
                    fileOutErr,
                    ErrorTiming.AFTER_EXECUTION
                )
                throw exception
            }

            val primaryOutputMetadata: FileArtifactValue?
            try {
                primaryOutputMetadata = outputMetadataStore.getOutputMetadata(primaryOutput)
            } catch (e: IOException) {
                throw java.lang.IllegalStateException("Metadata already obtained for " + primaryOutput, e)
            }

            reportActionExecution(
                eventHandler,
                primaryOutputPath,
                primaryOutputMetadata,
                action,
                actionResult,
                null,
                fileOutErr,
                ErrorTiming.NO_ERROR
            )

            return ActionExecutionValue.create(
                this.outputMetadataStore, actionExecutionContext.getRichArtifactData(), action
            )
        }

        /**
         * A closure to post-process the executed action, doing work like updating cached state with any
         * newly discovered inputs, and writing the result to the action cache.
         */
        private inner class ActionPostprocessingStep(value: ActionExecutionValue?) : ActionStep() {
            private val value: ActionExecutionValue?

            init {
                this.value = value
            }

            public override fun run(env: SkyFunction.Environment): ActionStepOrResult? {
                try {
                    Profiler.instance().profile(ProfilerTask.INFO, "postprocessing.run").use { c ->
                        postprocessing.run(
                            env,
                            action,
                            inputMetadataProvider,
                            outputMetadataStore,
                            actionExecutionContext.getClientEnv()
                        )
                        if (env.valuesMissing()) {
                            return this
                        }
                    }
                } catch (e: java.lang.InterruptedException) {
                    return ActionStepOrResult.of(e)
                } catch (e: ActionExecutionException) {
                    return ActionStepOrResult.of(e)
                }

                // Once the action has been written to the action cache, we can free its discovered inputs.
                // We do this unconditionally for input-pruning actions because it costs too much memory for
                // them to save their set of used inputs - they are already stored in the action cache.
                if (action.prunedInputs()) {
                    checkState(
                        action.discoversInputs(),
                        "Only input-discovering actions may prune inputs: %s",
                        action
                    )
                    action.resetDiscoveredInputs()
                } else if (action.discoversInputs() && freeDiscoveredInputsAfterExecution) {
                    action.resetDiscoveredInputs()
                }
                return ActionStepOrResult.of(value)
            }
        }
    }

    /**
     * Create output directories for an ActionFS. The action-local filesystem starts empty, so we
     * expect the output directory creation to always succeed. There can be no interference from state
     * left behind by prior builds or other actions intra-build.
     */
    @Throws(ActionExecutionException::class)
    private fun createActionFsOutputDirectories(
        action: Action, artifactPathResolver: ArtifactPathResolver?
    ) {
        try {
            outputDirectoryHelper.createActionFsOutputDirectories(
                action.getOutputs(), artifactPathResolver
            )
        } catch (e: CreateOutputDirectoryException) {
            throw toActionExecutionException(
                java.lang.String.format(
                    "failed to create output directory '%s': %s", e.getDirectoryPath(), e.getMessage()
                ),
                e,
                action,
                null,
                Code.ACTION_FS_OUTPUT_DIRECTORY_CREATION_FAILURE
            )
        }
    }

    @Throws(ActionExecutionException::class)
    private fun createOutputDirectories(action: Action) {
        try {
            outputDirectoryHelper.createOutputDirectories(action.getOutputs())
        } catch (e: CreateOutputDirectoryException) {
            throw toActionExecutionException(
                java.lang.String.format(
                    "failed to create output directory '%s': %s", e.getDirectoryPath(), e.getMessage()
                ),
                e,
                action,  /* actionOutput= */
                null,
                Code.ACTION_OUTPUT_DIRECTORY_CREATION_FAILURE
            )
        }
    }

    /**
     * Returns a progress message like:
     * 
     * 
     * [2608/6445] Compiling foo/bar.cc [exec]
     */
    private fun prependExecPhaseStats(message: String?): String {
        if (progressSupplier == null) {
            return ""
        }
        return progressSupplier!!.progressString + " " + message
    }

    /** Must not be called with a [LostInputsActionExecutionException].  */
    fun processAndGetExceptionToThrow(
        eventHandler: ExtendedEventHandler,
        primaryOutputPath: com.google.devtools.build.lib.vfs.Path?,
        action: Action,
        e: ActionExecutionException,
        outErrBuffer: FileOutErr,
        errorTiming: ErrorTiming?
    ): ActionExecutionException? {
        com.google.common.base.Preconditions.checkArgument(
            e !is LostInputsActionExecutionException,
            "unexpected LostInputs exception: %s",
            e
        )

        reportActionExecution(
            eventHandler,
            primaryOutputPath,  /* primaryOutputMetadata= */
            null,
            action,
            null,
            e,
            outErrBuffer,
            errorTiming
        )

        // Return the exception to rethrow. This can have two effects:
        // If we're still building, the exception will get retrieved by the completor and rethrown.
        // If we're aborting, the exception will never be retrieved from the completor, since the
        // completor is waiting for all outstanding jobs to finish. After they have finished, it will
        // only rethrow the exception that initially caused it to abort and not check the exit status of
        // any actions that had finished in the meantime.

        // If we already printed the error for the exception we mark it as already reported
        // so that we do not print it again in upper levels.
        // Note that we need to report it here since we want immediate feedback of the errors
        // and in some cases the upper-level printing mechanism only prints one of the errors.
        return if (printError(e.getMessage(), e.getAction(), outErrBuffer))
            AlreadyReportedActionExecutionException(e)
        else
            e
    }

    /**
     * Validates that all action outputs were created or intentionally omitted. This can result in
     * chmod calls on the output files; see [ActionOutputMetadataStore].
     * 
     * @return false if some outputs are missing or invalid, true - otherwise.
     */
    @Throws(java.lang.InterruptedException::class)
    private fun checkOutputs(
        action: Action,
        outputMetadataStore: OutputMetadataStore,
        actionExecutionContext: ActionExecutionContext?,
        isActionCacheHitForMetrics: Boolean
    ): Boolean {
        var success = true
        Profiler.instance().profile(ProfilerTask.INFO, "checkOutputs").use { c ->
            for (output in action.getOutputs()) {
                // getOutputMetadata() has the side effect of adding the artifact to the cache if it's not
                // there already (e.g., due to a previous call to OutputMetadataStore.injectFile()),
                // therefore we only call it if we know the artifact is not omitted.
                if (!outputMetadataStore.artifactOmitted(output)) {
                    try {
                        val metadata: FileArtifactValue = outputMetadataStore.getOutputMetadata(output)

                        if (!checkForUnsoundDirectoryOutput(action, output, metadata)) {
                            return false
                        }

                        var filesetOutputTree: FilesetOutputTree? = null
                        if (actionExecutionContext != null
                            && actionExecutionContext.getRichArtifactData() is FilesetOutputTree
                        ) {
                            // If isForwarded() is true, this action did not create the Fileset itself and thus
                            // it should not be counted.
                            if (!fot.isForwarded()) {
                                filesetOutputTree = fot
                            }
                        }

                        addOutputToMetrics(
                            output,
                            metadata,
                            outputMetadataStore,
                            filesetOutputTree,
                            isActionCacheHitForMetrics,
                            action
                        )
                    } catch (e: IOException) {
                        success = false
                        if (output.isTreeArtifact()) {
                            reportOutputTreeArtifactErrors(action, output, reporter, e)
                        } else if (output.isSymlink() && e is NotASymlinkException) {
                            reporter.handle(
                                com.google.devtools.build.lib.events.Event.error(
                                    action.getOwner().getLocation(),
                                    java.lang.String.format(
                                        "declared output '%s' is not a symlink", output.prettyPrint()
                                    )
                                )
                            )
                        } else {
                            // Are all other exceptions caught due to missing files?
                            reportMissingOutputFile(
                                action, output, reporter, output.getPath().isSymbolicLink(), e
                            )
                        }
                    }
                }
            }
        }
        return success
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun addOutputToMetrics(
        output: Artifact,
        metadata: FileArtifactValue?,
        outputMetadataStore: OutputMetadataStore,
        filesetOutput: FilesetOutputTree?,
        isActionCacheHit: Boolean,
        actionForDebugging: Action?
    ) {
        if (metadata == null) {
            BugReport.sendBugReport(
                java.lang.IllegalStateException(
                    String.format(
                        "Metadata for %s not present in %s (for %s)",
                        output, outputMetadataStore, actionForDebugging
                    )
                )
            )
            return
        }
        if (output.isFileset() && filesetOutput != null) {
            outputArtifactsSeen.accumulate(filesetOutput)
        } else if (!output.isTreeArtifact()) {
            outputArtifactsSeen.accumulate(metadata)
            if (isActionCacheHit) {
                outputArtifactsFromActionCache.accumulate(metadata)
            }
        } else {
            val treeArtifactValue: TreeArtifactValue?
            try {
                treeArtifactValue = outputMetadataStore.getTreeArtifactValue(output as SpecialArtifact)
            } catch (e: IOException) {
                BugReport.sendBugReport(
                    java.lang.IllegalStateException(
                        String.format(
                            "Unexpected IO exception after metadata %s was retrieved for %s (action %s)",
                            metadata, output, actionForDebugging
                        )
                    )
                )
                throw e
            }
            outputArtifactsSeen.accumulate(treeArtifactValue)
            if (isActionCacheHit) {
                outputArtifactsFromActionCache.accumulate(treeArtifactValue)
            }
        }
    }

    @Throws(ActionExecutionException::class)
    private fun checkForUnsoundDirectoryInputs(action: Action, metadataProvider: InputMetadataProvider) {
        if (TrackSourceDirectoriesFlag.trackSourceDirectories()) {
            return
        }

        if (action.getMnemonic().equals("FilesetTraversal")) {
            // Omit warning for filesets (b/1437948).
            return
        }

        // Report "directory dependency checking" warning only for non-generated directories (generated
        // ones will have been reported earlier, in the checkForUnsoundDirectoryOutput call for the
        // respective producing action).
        for (input in action.getMandatoryInputs().toList()) {
            // Assume that if the file did not exist, we would not have gotten here.
            try {
                if (input.isSourceArtifact()
                    && metadataProvider.getInputMetadata(input).getType().isDirectory()
                ) {
                    // TODO(ulfjack): What about dependency checking of special files?
                    reporter.handle(
                        getEventForUnsoundDirectory(
                            com.google.devtools.build.lib.events.EventKind.WARNING,
                            "input %s is a directory; dependency checking of directories is unsound",
                            input,
                            action.getOwner()
                        )
                    )
                }
            } catch (e: IOException) {
                throw ActionExecutionException.fromExecException(
                    EnvironmentalExecException(
                        e, FailureDetails.Execution.Code.INPUT_DIRECTORY_CHECK_IO_EXCEPTION
                    ),
                    action
                )
            }
        }
    }

    private fun checkForUnsoundDirectoryOutput(
        action: Action, output: Artifact, metadata: FileArtifactValue
    ): Boolean {
        if (output.isDirectory() || output.isSymlink() || !metadata.getType().isDirectory()) {
            return true
        }
        reporter.handle(
            getEventForUnsoundDirectory(
                com.google.devtools.build.lib.events.EventKind.ERROR,
                "output %s is a directory but was not declared as such",
                output,
                action.getOwner()
            )
        )
        return false
    }

    /**
     * Convenience function for creating an ActionExecutionException reporting that the action failed
     * due to the exception cause, if there is an additional explanatory message that clarifies the
     * message of the exception. Combines the user-provided message and the exception's message and
     * reports the combination as error.
     * 
     * @param message A small text that explains why the action failed
     * @param cause The exception that caused the action to fail
     * @param action The action that failed
     * @param actionOutput The output of the failed Action. May be null, if there is no output to
     * display
     * @param detailedCode The fine-grained failure code describing the failure
     */
    private fun toActionExecutionException(
        message: String?,
        cause: Throwable?,
        action: Action,
        actionOutput: FileOutErr?,
        detailedCode: FailureDetails.Execution.Code?
    ): ActionExecutionException {
        val code: DetailedExitCode = createDetailedExitCode(message, detailedCode)
        val ex: ActionExecutionException
        if (cause == null) {
            ex = ActionExecutionException(message, action, false, code)
        } else {
            ex = ActionExecutionException(message, cause, action, false, code)
        }
        var reportMessage: String? = ex.getMessage()
        if (cause != null && cause.message != null) {
            reportMessage += ": " + cause.message
        }
        printError(reportMessage, action, actionOutput)
        return ex
    }

    /**
     * Prints the given error `message` ascribed to `action`. May be called multiple times
     * for the same action if there are multiple errors: will print all of them.
     */
    fun printError(message: String?, action: ActionAnalysisMetadata) {
        printError(message, action, null)
    }

    /**
     * For the action 'action' that failed due to 'message' with the output 'actionOutput', notify the
     * user about the error. To notify the user, the method displays the output of the action and
     * reports an error via the reporter.
     * 
     * @param message The reason why the action failed
     * @param action The action that failed, must not be null.
     * @param actionOutput The output of the failed Action. May be null, if there is no output to
     * display
     * @return whether error was printed
     */
    private fun printError(
        message: String?, action: ActionAnalysisMetadata, actionOutput: FileOutErr?
    ): Boolean {
        var message = message
        message = action.describe() + " failed: " + message
        return dumpRecordedOutErr(
            reporter,
            com.google.devtools.build.lib.events.Event.error(action.getOwner().getLocation(), message),
            actionOutput
        )
    }

    /**
     * Dumps the output from the action.
     * 
     * @param action The action whose output is being dumped
     * @param outErrBuffer The OutErr that recorded the actions output
     */
    private fun dumpRecordedOutErr(
        eventHandler: com.google.devtools.build.lib.events.EventHandler, action: Action, outErrBuffer: FileOutErr?
    ) {
        val event: com.google.devtools.build.lib.events.Event =
            com.google.devtools.build.lib.events.Event.info("From " + action.describe() + ":")
        dumpRecordedOutErr(eventHandler, event, outErrBuffer)
    }

    /**
     * Dumps output from the action along with `prefixEvent` if the build is not aborting.
     * 
     * @param prefixEvent An event to post before dumping the output
     * @param outErrBuffer The OutErr that recorded the actions output
     * @return whether output was displayed (false if aborting)
     */
    private fun dumpRecordedOutErr(
        eventHandler: com.google.devtools.build.lib.events.EventHandler,
        prefixEvent: com.google.devtools.build.lib.events.Event,
        outErrBuffer: FileOutErr?
    ): Boolean {
        // For some actions (e.g., many local actions) the pollInterruptedStatus()
        // won't notice that we had an interrupted job. It will continue.
        // For that reason we must take care to NOT report errors if we're
        // in the 'aborting' mode: Any cancelled action would show up here.
        if (this.isBuilderAborting) {
            return false
        }
        if (outErrBuffer != null && outErrBuffer.hasRecordedOutput()) {
            // Bind the output to the prefix event.
            eventHandler.handle(prefixEvent.withProcessOutput(ActionOutputEventData(outErrBuffer)))
            informImportantOutputHandlerIfNecessary(outErrBuffer)
        } else {
            eventHandler.handle(prefixEvent)
        }
        return true
    }

    /**
     * Promotes stdout/err into important outputs when they are too large to display on the console.
     * 
     * 
     * When they are too large, the UI event handler prints their path instead of their contents.
     * Promoting them to important outputs ensures that the user can access the file at the printed
     * path.
     */
    private fun informImportantOutputHandlerIfNecessary(outErr: FileOutErr) {
        val importantOutputHandler: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            executorEngine.getContext(ImportantOutputHandler::class.java)
        if (importantOutputHandler == null) {
            return
        }
        try {
            if (outErr.outSize() > maxStdoutErrBytes) {
                importantOutputHandler.processTooLargeStdoutErr(outErr.getOutputPath())
            }
            if (outErr.errSize() > maxStdoutErrBytes) {
                importantOutputHandler.processTooLargeStdoutErr(outErr.getErrorPath())
            }
        } catch (e: IOException) {
            logger.atWarning().withCause(e).log(
                "Failure informing important output handler of stdout/stderr"
            )
        } catch (e: ImportantOutputException) {
            logger.atWarning().withCause(e).log(
                "Failure informing important output handler of stdout/stderr"
            )
        } catch (e: java.lang.InterruptedException) {
            logger.atInfo().log("Interrupted while informing important output handler of stdout/stderr")
            java.lang.Thread.currentThread().interrupt()
        }
    }

    /** An object supplying data for action execution progress reporting.  */
    interface ProgressSupplier {
        /** Returns the progress string to prefix action execution messages with.  */
        val progressString: String?
    }

    /** An object that can be notified about action completion.  */
    interface ActionCompletedReceiver {
        /** Receives a completed action.  */
        fun actionCompleted(actionLookupData: ActionLookupData?)
    }

    fun setActionExecutionProgressReportingObjects(
        progressSupplier: ProgressSupplier?,
        completionReceiver: ActionCompletedReceiver?
    ) {
        this.progressSupplier = progressSupplier
        this.completionReceiver = completionReceiver
    }

    /** Adapts a [FileOutErr] to an [Event.ProcessOutput].  */
    private class ActionOutputEventData(fileOutErr: FileOutErr) : ProcessOutput {
        private val fileOutErr: FileOutErr

        init {
            this.fileOutErr = fileOutErr
        }

        val stdOutPath: String?
            get() = fileOutErr.getOutputPathFragment().getPathString()

        @get:Throws(IOException::class)
        val stdOutSize: Long
            get() = fileOutErr.outSize()

        val stdOut: ByteArray?
            get() = fileOutErr.outAsBytes()

        val stdErrPath: String?
            get() = fileOutErr.getErrorPathFragment().getPathString()

        @get:Throws(IOException::class)
        val stdErrSize: Long
            get() = fileOutErr.errSize()

        val stdErr: ByteArray?
            get() = fileOutErr.errAsBytes()
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private val THROWING_OUTPUT_METADATA_STORE_FOR_ACTIONFS: OutputMetadataStore = object : OutputMetadataStore() {
            public override fun getOutputMetadata(artifact: Artifact?): FileArtifactValue? {
                throw java.lang.IllegalStateException()
            }

            public override fun getTreeArtifactValue(treeArtifact: SpecialArtifact?): TreeArtifactValue? {
                throw java.lang.IllegalStateException()
            }

            public override fun markOmitted(output: Artifact?) {
                throw java.lang.IllegalStateException()
            }

            public override fun artifactOmitted(artifact: Artifact?): Boolean {
                throw java.lang.IllegalStateException()
            }

            public override fun resetOutputs(outputs: Iterable<out Artifact?>?) {
                throw java.lang.IllegalStateException()
            }

            public override fun injectFile(output: Artifact?, metadata: FileArtifactValue?) {
                throw java.lang.IllegalStateException(
                    "Unexpected output during input discovery: " + output + " (" + metadata + ")"
                )
            }

            public override fun injectTree(output: SpecialArtifact?, tree: TreeArtifactValue?) {
                // ActionFS injects only metadata for files.
                throw java.lang.UnsupportedOperationException(
                    String.format(
                        "Unexpected injection of: %s for a tree artifact value: %s", output, tree
                    )
                )
            }
        }

        @Throws(ActionExecutionException::class)
        private fun closeContext(
            context: ActionExecutionContext?,
            action: Action?,
            finalException: ActionExecutionException?
        ) {
            try {
                context.use {
                    if (finalException != null) {
                        throw finalException
                    }
                }
            } catch (e: IOException) {
                val message = "Failed to close action output: " + e.message
                val code: DetailedExitCode = createDetailedExitCode(message, Code.ACTION_OUTPUT_CLOSE_FAILURE)
                throw ActionExecutionException(message, e, action,  /* catastrophe= */false, code)
            }
        }

        @Throws(ActionExecutionException::class)
        private fun setupActionFsFileOutErr(fileOutErr: FileOutErr, action: Action?) {
            try {
                fileOutErr.getOutputPath().getParentDirectory().createDirectoryAndParents()
                fileOutErr.getErrorPath().getParentDirectory().createDirectoryAndParents()
            } catch (e: IOException) {
                val message: String? = String.format(
                    "failed to create output directory for output streams '%s': %s",
                    fileOutErr.getErrorPath(), e.message
                )
                val code: DetailedExitCode =
                    createDetailedExitCode(message, Code.ACTION_FS_OUT_ERR_DIRECTORY_CREATION_FAILURE)
                throw ActionExecutionException(message, e, action, false, code)
            }
        }

        /**
         * Enriches the exception so it can be confirmed as the primary action in a shared action set and
         * so that, if rewinding fails, an ActionExecutedEvent can be published, and the error reported.
         */
        private fun enrichLostInputsException(
            primaryOutputPath: com.google.devtools.build.lib.vfs.Path?,
            actionLookupData: ActionLookupData?,
            outErrBuffer: FileOutErr?,
            lostInputsException: LostInputsActionExecutionException
        ) {
            lostInputsException.setPrimaryAction(actionLookupData)
            lostInputsException.setPrimaryOutputPath(primaryOutputPath)
            lostInputsException.setFileOutErr(outErrBuffer)
        }

        private fun reportMissingOutputFile(
            action: Action,
            output: Artifact,
            reporter: com.google.devtools.build.lib.events.Reporter,
            isSymlink: Boolean,
            exception: IOException
        ) {
            val genrule: Boolean = action.getMnemonic().equals("Genrule")
            val prefix = (if (genrule) "declared output '" else "output '") + output.prettyPrint() + "' "
            logger.atWarning().log(
                "Error creating %s%s%s: %s",
                if (isSymlink) "symlink " else "", prefix, if (genrule) " by genrule" else "", exception.message
            )
            if (isSymlink) {
                val msg = prefix + "is a dangling symbolic link"
                reporter.handle(com.google.devtools.build.lib.events.Event.error(action.getOwner().getLocation(), msg))
            } else {
                val suffix =
                    if (genrule)
                        (" by genrule. This is probably because the genrule actually didn't create this"
                                + " output, or because the output was a directory and the genrule was run"
                                + " remotely (note that only the contents of declared file outputs are copied"
                                + " from genrules run remotely)")
                    else
                        ""
                reporter.handle(
                    com.google.devtools.build.lib.events.Event.error(
                        action.getOwner().getLocation(),
                        prefix + "was not created" + suffix
                    )
                )
            }
        }

        private fun reportOutputTreeArtifactErrors(
            action: Action, output: Artifact, reporter: com.google.devtools.build.lib.events.Reporter, e: IOException
        ) {
            val errorMessage: String?
            if (e is FileNotFoundException) {
                errorMessage = java.lang.String.format("output tree artifact %s was not created", output.prettyPrint())
            } else {
                errorMessage =
                    java.lang.String.format(
                        "error while validating output tree artifact %s: %s",
                        output.prettyPrint(), e.message
                    )
            }

            reporter.handle(
                com.google.devtools.build.lib.events.Event.error(
                    action.getOwner().getLocation(),
                    errorMessage
                )
            )
        }

        /**
         * Validates that all action input contents were not lost if they were read, and if an action file
         * system was used. Throws a [LostInputsActionExecutionException] describing the lost inputs
         * if any were.
         */
        @Throws(LostInputsActionExecutionException::class)
        private fun checkActionFileSystemForLostInputs(
            actionFileSystem: com.google.devtools.build.lib.vfs.FileSystem?,
            action: Action?,
            outputService: OutputService
        ) {
            if (actionFileSystem != null) {
                outputService.checkActionFileSystemForLostInputs(actionFileSystem, action)
            }
        }

        private fun lostInputsCheck(
            actionFileSystem: com.google.devtools.build.lib.vfs.FileSystem?,
            action: Action?,
            outputService: OutputService
        ): LostInputsCheck? {
            return if (actionFileSystem == null)
                LostInputsCheck.NONE
            else
                LostInputsCheck { outputService.checkActionFileSystemForLostInputs(actionFileSystem, action) }
        }

        private fun getEventForUnsoundDirectory(
            kind: com.google.devtools.build.lib.events.EventKind?,
            format: String,
            artifact: Artifact,
            owner: ActionOwner
        ): com.google.devtools.build.lib.events.Event? {
            val label: Label? = owner.getLabel()
            val artifactString: String? =
                if (label != null)
                    java.lang.String.format("'%s' of %s", artifact.prettyPrint(), label)
                else
                    artifact.prettyPrint()
            val event: com.google.devtools.build.lib.events.Event = com.google.devtools.build.lib.events.Event.of(
                kind,
                owner.getLocation(),
                String.format(format, artifactString)
            )
            return if (label != null) event.withTag(label.toString()) else event
        }

        private fun createDetailedExitCode(message: String?, detailedCode: Code?): DetailedExitCode {
            return DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setExecution(Execution.newBuilder().setCode(detailedCode))
                    .build()
            )
        }

        private fun reportActionExecution(
            eventHandler: ExtendedEventHandler,
            primaryOutputPath: com.google.devtools.build.lib.vfs.Path?,
            primaryOutputMetadata: FileArtifactValue?,
            action: Action,
            actionResult: ActionResult?,
            exception: ActionExecutionException?,
            outErr: FileOutErr,
            errorTiming: ErrorTiming?
        ) {
            var stdout: com.google.devtools.build.lib.vfs.Path? = null
            var stderr: com.google.devtools.build.lib.vfs.Path? = null

            if (outErr.hasRecordedStdout()) {
                stdout = outErr.getOutputPath()
            }
            if (outErr.hasRecordedStderr()) {
                stderr = outErr.getErrorPath()
            }
            // Collect MetadataLogs and spawn start times/end times from the Action's SpawnResults.
            val spawnResults: com.google.common.collect.ImmutableList<SpawnResult> =
                findSpawnResultsInActionResultAndException(actionResult, exception)
            var firstStartTime: Instant = Instant.MAX
            var lastEndTime: Instant = Instant.MIN
            for (spawnResult in spawnResults) {
                // Not all SpawnResults have a start time, and some use Instant.MIN/MAX instead of null.
                val startTime: Instant? = spawnResult.getStartTime()
                if (startTime != null && (startTime != Instant.MIN) && (startTime != Instant.MAX)) {
                    val endTime: Instant? = startTime.plusMillis(spawnResult.getWallTimeInMs())
                    firstStartTime = com.google.common.collect.Comparators.min<Instant>(firstStartTime, startTime)
                    lastEndTime = com.google.common.collect.Comparators.max<Instant>(lastEndTime, endTime)
                }
            }
            eventHandler.post(
                ActionExecutedEvent(
                    action.getPrimaryOutput().getExecPath(),
                    action,
                    exception,
                    primaryOutputPath,
                    action.getPrimaryOutput(),
                    primaryOutputMetadata,
                    stdout,
                    stderr,
                    errorTiming,
                    if (firstStartTime == Instant.MAX) null else firstStartTime,
                    if (lastEndTime == Instant.MIN) null else lastEndTime
                )
            )
        }

        /**
         * Extracts the [SpawnResults][SpawnResult] from either a completed [ActionResult] or a
         * [SpawnActionExecutionException].
         * 
         * 
         * Returns an empty list for any other kind of [ActionExecutionException].
         */
        private fun findSpawnResultsInActionResultAndException(
            actionResult: ActionResult?, exception: ActionExecutionException?
        ): com.google.common.collect.ImmutableList<SpawnResult> {
            if (actionResult != null) {
                return actionResult.spawnResults()
            }
            if (exception is SpawnActionExecutionException) {
                return com.google.common.collect.ImmutableList.of<E?>(exception.getSpawnResult())
            }
            return com.google.common.collect.ImmutableList.of<SpawnResult?>()
        }
    }
}
