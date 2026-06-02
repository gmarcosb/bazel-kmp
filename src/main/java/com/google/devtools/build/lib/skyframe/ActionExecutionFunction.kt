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

import com.google.devtools.build.lib.actions.Action

/**
 * A [SkyFunction] that creates [ActionExecutionValue]s. There are four points where
 * this function can abort due to missing values in the graph:
 * 
 * 
 *  1. For actions that discover inputs, if missing metadata needed to resolve an artifact from a
 * string input in the action cache.
 *  1. If missing metadata for artifacts in inputs (including the artifacts above).
 *  1. For actions that discover inputs, if missing metadata for inputs discovered prior to
 * execution.
 *  1. For actions that discover inputs, but do so during execution, if missing metadata for
 * inputs discovered during execution.
 * 
 * 
 * 
 * If async action execution is enabled, or if a non-primary shared action coalesces with an
 * in-flight primary shared action's execution, this function can abort after declaring an external
 * dep on the execution's completion future.
 */
class ActionExecutionFunction(
    actionRewindStrategy: ActionRewindStrategy?,
    skyframeActionExecutor: SkyframeActionExecutor?,
    evaluator: java.util.function.Supplier<MemoizingEvaluator?>?,
    directories: BlazeDirectories?,
    tsgm: java.util.function.Supplier<TimestampGranularityMonitor?>?,
    bugReporter: BugReporter?,
    cachingDependenciesSupplier: java.util.function.Supplier<RemoteAnalysisCacheReaderDepsProvider>,
    consumedArtifactsTrackerSupplier: java.util.function.Supplier<ConsumedArtifactsTracker?>
) : SkyFunction {
    private val actionRewindStrategy: ActionRewindStrategy
    private val skyframeActionExecutor: SkyframeActionExecutor

    // Direct access to the MemoizingEvaluator should typically not be allowed in SkyFunctions. We
    // allow it here as an optimization for accessing inputs that are under an ArtifactNestedSet node
    // without adding a direct Skyframe edge on the input or its generating action.
    private val evaluator: java.util.function.Supplier<MemoizingEvaluator?>

    private val directories: BlazeDirectories
    private val tsgm: java.util.function.Supplier<TimestampGranularityMonitor?>
    private val bugReporter: BugReporter
    private val consumedArtifactsTrackerSupplier: java.util.function.Supplier<ConsumedArtifactsTracker?>
    private val cachingDependenciesSupplier: java.util.function.Supplier<RemoteAnalysisCacheReaderDepsProvider>

    init {
        this.actionRewindStrategy =
            com.google.common.base.Preconditions.checkNotNull<ActionRewindStrategy>(actionRewindStrategy)
        this.skyframeActionExecutor =
            com.google.common.base.Preconditions.checkNotNull<SkyframeActionExecutor>(skyframeActionExecutor)
        this.evaluator =
            com.google.common.base.Preconditions.checkNotNull<java.util.function.Supplier<MemoizingEvaluator?>>(
                evaluator
            )
        this.directories = com.google.common.base.Preconditions.checkNotNull<BlazeDirectories>(directories)
        this.tsgm =
            com.google.common.base.Preconditions.checkNotNull<java.util.function.Supplier<TimestampGranularityMonitor?>>(
                tsgm
            )
        this.bugReporter = com.google.common.base.Preconditions.checkNotNull<BugReporter>(bugReporter)
        this.cachingDependenciesSupplier = cachingDependenciesSupplier
        this.consumedArtifactsTrackerSupplier = consumedArtifactsTrackerSupplier
    }

    @Throws(ActionExecutionFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val actionLookupData: ActionLookupData = skyKey.argument() as ActionLookupData
        val remoteCachingDependencies: RemoteAnalysisCacheReaderDepsProvider =
            cachingDependenciesSupplier.get()
        if (remoteCachingDependencies.mode().isRetrievalEnabled()
            && !remoteCachingDependencies.skycacheAnalysisOnly && !skyframeActionExecutor.shouldSkipRetrieval(
                actionLookupData
            )
        ) {
            when (SkyValueRetrieverUtils.retrieveRemoteSkyValue(
                actionLookupData,
                env,
                remoteCachingDependencies,
                java.util.function.Supplier { InputDiscoveryState() })) {
                -> return null
                -> return v.value()
                -> {}
            }
        }
        val action: Action? =
            ActionUtils.getActionForLookupData(
                env,
                actionLookupData,  /* crashIfActionOwnerMissing= */
                !remoteCachingDependencies
                    .mode()
                    .isRetrievalEnabled()
            )
        if (action == null) {
            return null
        }

        try {
            return computeInternal(actionLookupData, action, env)
        } catch (e: ActionExecutionFunctionException) {
            skyframeActionExecutor.recordExecutionError()
            throw e
        } catch (e: UndoneInputsException) {
            return actionRewindStrategy.patchNestedSetGraphToPropagateError(
                actionLookupData, action, e.undoneInputs, e.inputDepKeys
            )
        }
    }

    @Throws(
        ActionExecutionFunctionException::class,
        java.lang.InterruptedException::class,
        UndoneInputsException::class
    )
    private fun computeInternal(
        actionLookupData: ActionLookupData?, action: Action, env: SkyFunction.Environment
    ): SkyValue? {
        var env: SkyFunction.Environment = env
        if (Actions.dependsOnBuildId(action)) {
            PrecomputedValue.BUILD_ID.get(env)
        }

        // Look up the parts of the environment that influence the action.
        val clientEnvironmentVariables: MutableCollection<String?> = action.getClientEnvironmentVariables()
        val clientEnv: com.google.common.collect.ImmutableMap<String?, String?>?
        if (!clientEnvironmentVariables.isEmpty()) {
            val clientEnvironmentVariablesSet: com.google.common.collect.ImmutableSet<String?> =
                com.google.common.collect.ImmutableSet.copyOf<String?>(clientEnvironmentVariables)
            val depKeys: Iterable<SkyKey> =
                com.google.common.collect.Iterables.transform<String?, SkyKey?>(
                    clientEnvironmentVariablesSet,
                    ClientEnvironmentFunction::key
                )
            val clientEnvLookup: SkyframeLookupResult = env.getValuesAndExceptions(depKeys)
            if (env.valuesMissing()) {
                return null
            }
            val builder: com.google.common.collect.ImmutableMap.Builder<String?, String?> =
                com.google.common.collect.ImmutableMap.builderWithExpectedSize<String?, String?>(
                    clientEnvironmentVariablesSet.size()
                )
            for (depKey in depKeys) {
                val envValue: EnvironmentVariableValue? = clientEnvLookup.get(depKey) as EnvironmentVariableValue?
                if (envValue.value != null) {
                    builder.put(depKey.argument() as String?, envValue.value)
                }
            }
            clientEnv = builder.buildOrThrow()
        } else {
            clientEnv = com.google.common.collect.ImmutableMap.of<String?, String?>()
        }

        // If two actions are shared and the first one executes, when the second one goes to execute, we
        // should detect that and short-circuit.
        //
        // Additionally, if an action restarted (in the Skyframe sense) after it executed because it
        // discovered new inputs during execution, we should detect that and short-circuit.
        //
        // Separately, we use InputDiscoveryState to avoid redoing work on Skyframe restarts for actions
        // that discover inputs. This is not [currently] relevant here, because it is [currently] not
        // possible for an action to both be shared and also discover inputs; see b/72764586.
        val previousExecution: ActionExecutionState? = skyframeActionExecutor.probeActionExecution(action)

        // If this action was previously completed this build, then this evaluation must be happening
        // because of rewinding. Prevent any progress events from being published a second time for this
        // action; downstream consumers of action events reasonably don't expect them.
        if (!skyframeActionExecutor.shouldEmitProgressEvents(action)) {
            env = ProgressEventSuppressingEnvironment(env)
        }

        val state: InputDiscoveryState
        if (action.discoversInputs()) {
            state = env.getState<T>(java.util.function.Supplier { InputDiscoveryState() })
        } else {
            // Because this is a new state, all conditionals below about whether state has already done
            // something will return false, and so we will execute all necessary steps.
            state = InputDiscoveryState()
        }
        if (!state.hasCollectedInputs()) {
            try {
                state.allInputs = collectInputs(action, env)
            } catch (e: AlreadyReportedActionExecutionException) {
                throw ActionExecutionFunctionException(e)
            }
            if (state.allInputs == null) {
                // Missing deps.
                return null
            }
        }

        var checkedInputs: CheckInputResults? = null
        val allInputs: NestedSet<Artifact?> = state.allInputs!!.allInputs

        if (!state.actionInputCollectedEventSent) {
            env.getListener()
                .post(
                    ActionInputCollectedEvent.create(
                        action, allInputs, skyframeActionExecutor.getActionContextRegistry()
                    )
                )
            state.actionInputCollectedEventSent = true
        }

        if (!state.hasArtifactData()) {
            val inputDepKeys: com.google.common.collect.ImmutableSet<SkyKey> =
                getInputDepKeys(
                    consumedArtifactsTrackerSupplier.get(),
                    allInputs,
                    action.getSchedulingDependencies(),
                    state
                )

            val inputDepsResult: SkyframeLookupResult = env.getValuesAndExceptions(inputDepKeys)
            if (previousExecution == null) {
                // Do we actually need to find our metadata?
                try {
                    checkedInputs = checkInputs(env, action, inputDepsResult, allInputs, inputDepKeys)
                } catch (e: ActionExecutionException) {
                    throw ActionExecutionFunctionException(e)
                }
            }
            if (env.valuesMissing()) {
                // There was missing artifact metadata in the graph. Wait for it to be present.
                // We must check this and return here before attempting to establish any Skyframe
                // dependencies of the action; see establishSkyframeDependencies why.
                return null
            }
        }

        if (checkedInputs != null) {
            com.google.common.base.Preconditions.checkState(!state.hasArtifactData(), "%s %s", state, action)
            state.inputArtifactData = checkedInputs.actionInputMap
            state.actionInputMetadataProvider = ActionInputMetadataProvider(state.inputArtifactData)
            state.skyframeInputMetadataProvider =
                SkyframeInputMetadataProvider(
                    evaluator.get(),
                    skyframeActionExecutor.getPerBuildFileCache(),
                    directories.getRelativeOutputPath()
                )
            state.compositeInputMetadataProvider =
                DelegatingPairInputMetadataProvider(
                    state.actionInputMetadataProvider, state.skyframeInputMetadataProvider
                )
            if (skyframeActionExecutor.actionFileSystemType().isEnabled()) {
                state.actionFileSystem =
                    skyframeActionExecutor.createActionFileSystem(
                        directories.getRelativeOutputPath(),
                        state.compositeInputMetadataProvider,
                        action.getOutputs()
                    )
            }
        }

        skyframeActionExecutor.acquireActionExecutionSemaphore()
        val actionStartTime: Long = com.google.devtools.build.lib.clock.BlazeClock.nanoTime()
        var result: ActionExecutionValue?
        try {
            result =
                checkCacheAndExecuteIfNeeded(
                    action, state, env, clientEnv, actionLookupData, previousExecution, actionStartTime
                )
        } catch (e: LostInputsActionExecutionException) {
            val inputDepKeys: com.google.common.collect.ImmutableSet<SkyKey> =
                getInputDepKeys( /* consumedArtifactsTracker= */
                    null,
                    allInputs,
                    action.getSchedulingDependencies(),  /* state= */
                    null
                )
            var inputArtifactData: ActionInputMap? = state.inputArtifactData
            if (inputArtifactData == null) {
                // Reconstitute inputArtifactData if it was not present in `state`.
                //
                // This can happen after Skyframe restarts inside `handleLostInputs`, when remote analysis
                // is enabled. This is primarily for non-input discovering actions, but could potentially
                // occur for input-discovering actions if SkyKeyComputeState is evicted.
                try {
                    // Since `checkInputs` must have succeeded prior to `checkCacheAndExecuteIfNeeded`, it
                    // should succeed here.
                    inputArtifactData =
                        checkInputs(
                            env,
                            action,
                            env.getValuesAndExceptions(inputDepKeys),
                            allInputs,
                            inputDepKeys
                        )
                            .actionInputMap
                } catch (e2: ActionExecutionException) {
                    // This should be impossible since metadata was already checked once, but we handle it
                    // for completeness.
                    throw ActionExecutionFunctionException(e2)
                }
            }
            var discoveredInputs: NestedSet<Artifact?>? = null
            if (action.discoversInputs()) {
                if (state.discoveredInputs != null) {
                    discoveredInputs = state.discoveredInputs
                } else if (action.inputsKnown()) {
                    discoveredInputs = action.getInputs()
                }
            }

            return handleLostInputs(
                e,
                actionLookupData,
                action,
                actionStartTime,
                env,
                inputDepKeys,
                discoveredInputs,
                inputArtifactData
            )
        } catch (e: ActionExecutionException) {
            // In this case we do not report the error to the action reporter because we have already
            // done it in SkyframeActionExecutor.reportErrorIfNotAbortingMode() method. That method
            // prints the error in the top-level reporter and also dumps the recorded StdErr for the
            // action. Label can be null in the case of, e.g., the SystemActionOwner (for build-info.txt).
            throw ActionExecutionFunctionException(AlreadyReportedActionExecutionException(e))
        } finally {
            skyframeActionExecutor.releaseActionExecutionSemaphore()
        }

        if (env.valuesMissing()) {
            // This usually happens only for input-discovering actions. Other actions may have
            // valuesMissing() here in rare circumstances related to Fileset inputs being unavailable.
            // See comments in ActionInputMapHelper#getFilesets().
            return null
        }

        // We're done with the action. Clear the cached NestedSet list representations to save memory.
        action.getInputs().clearCachedListRepresentation()
        allInputs.clearCachedListRepresentation()

        // After the action execution is finalized, unregister the outputs from the consumed set to save
        // memory.
        // Note: This can theoretically lead to infinite action rewinding if we're unlucky enough.
        // Consider an action foo whose outputs A and B are needed by 2 separate actions consumerA and
        // consumerB. If these 2 actions trigger rewinding alternately, at the correct timing, e.g.:
        // 1. consumerA requests for A. A is registered. foo produces only A since B isn't registered. A
        // is de-registered. consumerA isn't executed yet.
        // 2. consumerB requests for B. B is registered. foo is rewound and produces only B since A
        // isn't registered. B is de-registered. consumerB isn't executed yet.
        // 3. Before consumerA enters execution, A falls out of the CAS. consumerA sees that A is
        // missing and triggers rewinding for A. Repeat step (1).
        // 4. Before consumerB enters execution, B falls out of the CAS. consumerB sees that B is
        // missing and triggers rewinding for B. Repeat step (2).
        if (consumedArtifactsTrackerSupplier.get() != null) {
            consumedArtifactsTrackerSupplier
                .get()
                .unregisterOutputsAfterExecutionDone(action.getOutputs())
        }

        return result
    }

    /**
     * Cleans up state associated with the current action execution attempt and returns a [ ] value which rewinds the actions that generate the lost inputs.
     */
    @Throws(
        java.lang.InterruptedException::class,
        ActionExecutionFunctionException::class
    )  // null if there were missing dependencies
    private fun handleLostInputs(
        e: LostInputsActionExecutionException,
        actionLookupData: ActionLookupData?,
        action: Action?,
        actionStartTimeNanos: Long,
        env: SkyFunction.Environment,
        inputDepKeys: com.google.common.collect.ImmutableSet<SkyKey>,
        discoveredInputs: NestedSet<Artifact?>?,
        inputArtifactData: ActionInputMap?
    ): Reset? {
        checkState(
            e.isPrimaryAction(actionLookupData),
            "Non-primary action handling lost inputs exception: %s %s",
            actionLookupData,
            e
        )

        // inputDepKeys only contains keys in the initial, pre-input-discovery Skyframe request. If the
        // action discovers inputs, we must combine them with discovered input keys.
        val failedActionDeps: com.google.common.collect.ImmutableSet<SkyKey>?
        if (e.isFromInputDiscovery()) {
            // The action failed during input discovery. We don't know the discovered inputs, so just add
            // keys of lost inputs in case any of them were discovered.
            failedActionDeps =
                com.google.common.collect.ImmutableSet.builder<SkyKey?>()
                    .addAll(inputDepKeys)
                    .addAll(
                        com.google.common.collect.Collections2.transform<F?, T?>(
                            e.getLostInputs().values(),
                            com.google.common.base.Function { input: F? -> Artifact.key(input as Artifact?) })
                    )
                    .build()
        } else if (discoveredInputs != null) {
            failedActionDeps =
                com.google.common.collect.ImmutableSet.builder<SkyKey?>()
                    .addAll(inputDepKeys)
                    .addAll(Artifact.keys(discoveredInputs.toList()))
                    .build()
        } else {
            failedActionDeps = inputDepKeys
        }

        var rewindPlanResult: RewindPlanResult? = null
        try {
            rewindPlanResult =
                actionRewindStrategy.prepareRewindPlanForLostInputs(
                    actionLookupData,
                    action,
                    failedActionDeps,
                    e,
                    inputArtifactData,
                    env,
                    actionStartTimeNanos
                )
        } catch (rewindingFailedException: ActionRewindException) {
            throw ActionExecutionFunctionException(
                AlreadyReportedActionExecutionException(
                    skyframeActionExecutor.processAndGetExceptionToThrow(
                        env.getListener(),
                        e.getPrimaryOutputPath(),
                        action,
                        ActionExecutionException(
                            rewindingFailedException,
                            action,  /* catastrophe= */
                            false,
                            rewindingFailedException.detailedExitCode
                        ),
                        e.getFileOutErr(),
                        ErrorTiming.AFTER_EXECUTION
                    )
                )
            )
        } finally {
            if (e.isActionStartedEventAlreadyEmitted() && rewindPlanResult == null) {
                // Rewinding was unsuccessful. SkyframeActionExecutor's ActionRunner didn't emit an
                // ActionCompletionEvent because it hoped rewinding would fix things. Because it won't, this
                // must emit one to compensate.
                val inputMetadataProvider: ActionInputMetadataProvider =
                    ActionInputMetadataProvider(inputArtifactData)
                env.getListener()
                    .post(
                        ActionCompletionEvent(
                            actionStartTimeNanos,
                            com.google.devtools.build.lib.clock.BlazeClock.nanoTime(),
                            action,
                            inputMetadataProvider,  /* outputMetadataStore= */
                            null,
                            actionLookupData
                        )
                    )
            }
        }
        return rewindPlanResult.toNullIfMissingDependenciesElseReset()
    }

    /**
     * An action's inputs needed for execution. May not just be the result of Action#getInputs(). If
     * the action cache's view of this action contains additional inputs, it will request metadata for
     * them, so we consider those inputs as dependencies of this action as well. Returns null if some
     * dependencies were missing and this ActionExecutionFunction needs to restart.
     */
    @Throws(java.lang.InterruptedException::class, AlreadyReportedActionExecutionException::class)
    private fun collectInputs(action: Action, env: SkyFunction.Environment): AllInputs? {
        if (action.inputsKnown()) {
            return AllInputs(action.getInputs())
        }

        checkState(action.discoversInputs(), action)
        val actionCacheInputs: MutableList<Artifact?>? =
            skyframeActionExecutor.getActionCachedInputs(
                action, PackageRootResolverWithEnvironment(env)
            )
        if (actionCacheInputs == null) {
            com.google.common.base.Preconditions.checkState(env.valuesMissing(), action)
            return null
        }

        // Actions which pruned their inputs may be able to get an action cache hit without requesting
        // the full set of original inputs. We'll request them later on if there is no action cache hit.
        val allKnownInputs: NestedSet<Artifact?>? =
            if (action.prunedInputs()) NestedSetBuilder.emptySet(Order.STABLE_ORDER) else action.getInputs()
        return AllInputs(allKnownInputs, actionCacheInputs)
    }

    internal class AllInputs {
        val defaultInputs: NestedSet<Artifact?>
        val actionCacheInputs: MutableList<Artifact?>?

        constructor(defaultInputs: NestedSet<Artifact?>?) {
            this.defaultInputs = com.google.common.base.Preconditions.checkNotNull<NestedSet<Artifact?>>(defaultInputs)
            this.actionCacheInputs = null
        }

        constructor(defaultInputs: NestedSet<Artifact?>?, actionCacheInputs: MutableList<Artifact?>?) {
            this.defaultInputs = com.google.common.base.Preconditions.checkNotNull<NestedSet<Artifact?>>(defaultInputs)
            this.actionCacheInputs =
                com.google.common.base.Preconditions.checkNotNull<MutableList<Artifact?>?>(actionCacheInputs)
        }

        val allInputs: NestedSet<Artifact?>
            /** Compute the inputs to request from Skyframe.  */
            get() {
                if (actionCacheInputs == null || actionCacheInputs.isEmpty()) {
                    return defaultInputs
                }
                return NestedSetBuilder.< Artifact > newBuilder < Artifact ? > (Order.STABLE_ORDER)
                    .addTransitive(defaultInputs)
                    .addAll(actionCacheInputs)
                    .build()
            }
    }

    /**
     * Skyframe implementation of [PackageRootResolver]. Should be used only from SkyFunctions,
     * because it uses SkyFunction.Environment for evaluation of ContainingPackageLookupValue.
     */
    private class PackageRootResolverWithEnvironment(env: SkyFunction.Environment) : PackageRootResolver {
        private val packageLookupsRequested: MutableList<ContainingPackageLookupValue.Key?> =
            java.util.ArrayList<ContainingPackageLookupValue.Key?>()
        private val env: SkyFunction.Environment

        init {
            this.env = env
        }

        @Throws(PackageRootException::class, java.lang.InterruptedException::class)
        public override fun findPackageRootsForFiles(execPaths: Iterable<PathFragment>): MutableMap<PathFragment?, Root?>? {
            com.google.common.base.Preconditions.checkState(
                packageLookupsRequested.isEmpty(),
                "resolver should only be called once: %s %s",
                packageLookupsRequested,
                execPaths
            )
            val starlarkSemantics: net.starlark.java.eval.StarlarkSemantics? =
                PrecomputedValue.STARLARK_SEMANTICS.get(env)
            if (starlarkSemantics == null) {
                return null
            }

            val siblingRepositoryLayout: Boolean =
                starlarkSemantics.getBool(BuildLanguageOptions.EXPERIMENTAL_SIBLING_REPOSITORY_LAYOUT)

            // Create SkyKeys list based on execPaths.
            val depKeys: MutableMap<PathFragment?, ContainingPackageLookupValue.Key?> =
                HashMap<PathFragment?, ContainingPackageLookupValue.Key?>()
            for (path in execPaths) {
                val parent: PathFragment =
                    com.google.common.base.Preconditions.checkNotNull<PathFragment>(
                        path.getParentDirectory(),
                        "Must pass in files, not root directory"
                    )
                com.google.common.base.Preconditions.checkArgument(!parent.isAbsolute(), path)
                val pkgId: java.util.Optional<PackageIdentifier?> =
                    PackageIdentifier.discoverFromExecPath(path, true, siblingRepositoryLayout)
                if (pkgId.isPresent()) {
                    val depKey: ContainingPackageLookupValue.Key? = ContainingPackageLookupValue.key(pkgId.get())
                    depKeys.put(path, depKey)
                    packageLookupsRequested.add(depKey)
                }
            }

            val values: SkyframeLookupResult = env.getValuesAndExceptions(depKeys.values())
            val result: MutableMap<PathFragment?, Root?> = HashMap<PathFragment?, Root?>()
            for (path in execPaths) {
                if (!depKeys.containsKey(path)) {
                    continue
                }
                val value: ContainingPackageLookupValue?
                try {
                    value =
                        values.getOrThrow<E1?, E2?>(
                            depKeys.get(path),
                            BuildFileNotFoundException::class.java,
                            InconsistentFilesystemException::class.java
                        ) as ContainingPackageLookupValue?
                } catch (e: BuildFileNotFoundException) {
                    throw PackageRootException.create(path, e)
                } catch (e: InconsistentFilesystemException) {
                    throw PackageRootException.create(path, e)
                }
                if (value != null && value.hasContainingPackage()) {
                    // We have found corresponding root for current execPath.
                    result.put(path, value.containingPackageRoot)
                } else {
                    // We haven't found corresponding root for current execPath.
                    result.put(path, null)
                }
            }
            return if (env.valuesMissing()) null else result
        }
    }

    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
    private fun checkCacheAndExecuteIfNeeded(
        action: Action,
        state: InputDiscoveryState,
        env: SkyFunction.Environment,
        clientEnv: MutableMap<String?, String?>?,
        actionLookupData: ActionLookupData?,
        previousAction: ActionExecutionState?,
        actionStartTime: Long
    ): ActionExecutionValue? {
        if (previousAction != null) {
            // There are two cases where we can already have an ActionExecutionState for a specific
            // output:
            // 1. Another instance of a shared action won the race and got executed first.
            // 2. The action was already started earlier, and this SkyFunction got restarted since
            //    there's progress to be made.
            // In either case, we must use this ActionExecutionState to continue. Note that in the first
            // case, we don't have any input metadata available, so we couldn't re-execute the action even
            // if we wanted to.
            if (state.discoveredInputs != null
                && action is ActionWithDiscoveredInputsState
            ) {
                // Re-inject discovered inputs from the SkyKeyComputeState if missing
                // dependencies of this action were rewinded, causing this action's
                // restart. We want to avoid recomputing them. See b/505164988 for more
                // details.
                action.setAdditionalInputs(state.discoveredInputs)
            }
            return previousAction.getResultOrDependOnFuture(
                env,
                actionLookupData,
                action,
                skyframeActionExecutor.getSharedActionCallback(
                    env.getListener(), state.discoveredInputs != null, action, actionLookupData
                )
            )
        }

        val pathResolver: ArtifactPathResolver? =
            ArtifactPathResolver.createPathResolver(
                state.actionFileSystem, skyframeActionExecutor.getExecRoot()
            )

        val outputMetadataStore: ActionOutputMetadataStore =
            ActionOutputMetadataStore.create(
                skyframeActionExecutor.useArchivedTreeArtifacts(action),
                skyframeActionExecutor.getOutputPermissions(),
                com.google.common.collect.ImmutableSet.copyOf(action.getOutputs()),
                skyframeActionExecutor.getXattrProvider(),
                tsgm.get(),
                pathResolver
            )

        // We only need to check the action cache if we haven't done it on a previous run.
        if (!state.hasCheckedActionCache()) {
            state.token =
                skyframeActionExecutor.checkActionCache(
                    env.getListener(),
                    action,
                    state.actionInputMetadataProvider,
                    outputMetadataStore,
                    pathResolver,
                    actionStartTime,
                    state.allInputs!!.actionCacheInputs,
                    clientEnv
                )
        }

        if (state.token == null) {
            val reconstructedRichArtifactData: RichArtifactData? =
                if (action is RichDataProducingAction)
                    action.reconstructRichDataOnActionCacheHit(state.actionInputMetadataProvider)
                else
                    null
            return ActionExecutionValue.create(
                outputMetadataStore, reconstructedRichArtifactData, action
            )
        }

        outputMetadataStore.prepareForActionExecution()

        if (action.discoversInputs()) {
            var discoveredInputsDuration: java.time.Duration? = java.time.Duration.ZERO
            if (state.discoveredInputs == null) {
                if (!state.preparedInputDiscovery) {
                    action.prepareInputDiscovery()
                    state.preparedInputDiscovery = true
                }

                Profiler.instance().profile(ProfilerTask.DISCOVER_INPUTS, "discoverInputs").use { c ->
                    state.skyframeInputMetadataProvider.withSkyframeAllowed(env).use { unused ->
                        state.discoveredInputs =
                            skyframeActionExecutor.discoverInputs(
                                action,
                                actionLookupData,
                                state.compositeInputMetadataProvider,
                                env,
                                state.actionFileSystem
                            )
                    }
                }
                discoveredInputsDuration =
                    java.time.Duration.ofNanos(com.google.devtools.build.lib.clock.BlazeClock.nanoTime() - actionStartTime)
                if (env.valuesMissing()) {
                    com.google.common.base.Preconditions.checkState(
                        state.discoveredInputs == null,
                        "Inputs were discovered but more deps were requested by %s",
                        action
                    )
                    return null
                }
                com.google.common.base.Preconditions.checkNotNull<Any?>(
                    state.discoveredInputs,
                    "Input discovery returned null but no more deps were requested by %s",
                    action
                )
            }

            addDiscoveredInputs(state, env, action)
            if (env.valuesMissing()) {
                return null
            }

            // When discover inputs completes, post an event with the duration values.
            env.getListener()
                .post(
                    DiscoveredInputsEvent(
                        SpawnMetrics.Builder.forOtherExec()
                            .setParseTime(discoveredInputsDuration)
                            .setTotalTime(discoveredInputsDuration)
                            .build(),
                        action,
                        actionStartTime
                    )
                )
        }

        return skyframeActionExecutor.executeAction(
            env,
            action,
            state.compositeInputMetadataProvider,
            outputMetadataStore,
            actionStartTime,
            actionLookupData,
            state.actionFileSystem,
            ActionPostprocessingImpl(state),
            state.discoveredInputs != null
        )
    }

    /** Implementation of [ActionPostprocessing].  */
    private inner class ActionPostprocessingImpl(private val state: InputDiscoveryState) : ActionPostprocessing {
        @Throws(java.lang.InterruptedException::class, ActionExecutionException::class)
        override fun run(
            env: SkyFunction.Environment,
            action: Action,
            inputMetadataProvider: InputMetadataProvider?,
            outputMetadataStore: OutputMetadataStore?,
            clientEnv: MutableMap<String?, String?>?
        ) {
            if (action.discoversInputs()) {
                state.discoveredInputs = action.getInputs()
                addDiscoveredInputs(state, env, action)
                if (env.valuesMissing()) {
                    return
                }
            }
            com.google.common.base.Preconditions.checkState(!env.valuesMissing(), action)
            skyframeActionExecutor.updateActionCache(
                action, inputMetadataProvider, outputMetadataStore, state.token, clientEnv
            )
        }
    }

    @Throws(java.lang.InterruptedException::class, ActionExecutionException::class)
    private fun addDiscoveredInputs(
        state: InputDiscoveryState, env: SkyFunction.Environment, actionForError: Action
    ) {
        // TODO(janakr): This code's assumptions are wrong in the face of Starlark actions with unused
        //  inputs, since ActionExecutionExceptions can come through here and should be aggregated. Fix.

        val inputData: ActionInputMap? = state.inputArtifactData

        // Filter down to unknown discovered inputs eagerly instead of using a lazy Iterables#filter to
        // reduce iteration cost.
        val unknownDiscoveredInputs: MutableList<Artifact> = java.util.ArrayList<Artifact>()
        for (input in state.discoveredInputs.toList()) {
            if (inputData.getInputMetadata(input) == null) {
                unknownDiscoveredInputs.add(input)
            }
        }

        if (unknownDiscoveredInputs.isEmpty()) {
            return
        }

        val nonMandatoryDiscovered: SkyframeLookupResult =
            env.getValuesAndExceptions(Artifact.keys(unknownDiscoveredInputs))
        for (input in unknownDiscoveredInputs) {
            var retrievedMetadata: SkyValue?
            try {
                retrievedMetadata =
                    nonMandatoryDiscovered.getOrThrow<E?>(Artifact.key(input), SourceArtifactException::class.java)
            } catch (e: SourceArtifactException) {
                if (!input.isSourceArtifact()) {
                    throw java.lang.IllegalStateException(
                        java.lang.String.format(
                            "Non-source artifact had SourceArtifactException %s %s",
                            input.toDebugString(), actionForError.prettyPrint()
                        ),
                        e
                    )
                }

                skyframeActionExecutor.printError(e.getMessage(), actionForError)
                // We don't create a specific cause for the artifact as we do in #handleMissingFile because
                // it likely has no label, so we'd have to use the Action's label anyway. Just use the
                // default ActionFailed event constructed by ActionExecutionException.
                val message = "discovered input file does not exist"
                val code: DetailedExitCode = createDetailedExitCodeForMissingDiscoveredInput(message)
                throw ActionExecutionException(message, actionForError, false, code)
            }
            if (retrievedMetadata == null) {
                com.google.common.base.Preconditions.checkState(
                    env.valuesMissing(),
                    "%s had no metadata but all values were present for %s",
                    input,
                    actionForError
                )
                continue
            }
            if (retrievedMetadata is MissingArtifactValue) {
                retrievedMetadata = FileArtifactValue.MISSING_FILE_MARKER
            }
            ActionInputMapHelper.addToMap(
                inputData, input, retrievedMetadata, MetadataConsumerForMetrics.NO_OP
            )
        }
    }

    private class CheckInputResults(actionInputMap: ActionInputMap) {
        /** Metadata about Artifacts consumed by this Action.  */
        private val actionInputMap: ActionInputMap

        init {
            this.actionInputMap = actionInputMap
        }
    }

    /**
     * Declares a dependency on all known inputs of the action. Throws an exception if any are known
     * to be missing.
     * 
     * 
     * Returns `null` if [Environment.valuesMissing] is true and no inputs result in
     * [ActionExecutionException]s.
     */
    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class, UndoneInputsException::class)
    private fun checkInputs(
        env: SkyFunction.Environment,
        action: Action,
        inputDepsResult: SkyframeLookupResult,
        allInputs: NestedSet<Artifact?>,
        inputDepKeys: com.google.common.collect.ImmutableSet<SkyKey>
    ): CheckInputResults? {
        val isMandatoryInput: java.util.function.Predicate<Artifact?> = makeMandatoryInputPredicate(action)

        val actionExecutionFunctionExceptionHandler =
            ActionExecutionFunctionExceptionHandler(
                com.google.common.base.Suppliers.memoize<com.google.common.collect.SetMultimap<SkyKey?, Artifact?>?>(
                    com.google.common.base.Supplier {
                        val allInputsSet: com.google.common.collect.ImmutableSet<Artifact?> =
                            com.google.common.collect.ImmutableSet.builder<Artifact?>()
                                .addAll(allInputs.toList())
                                .addAll(action.getSchedulingDependencies().toList())
                                .build()
                        val skyKeyToArtifactSet: com.google.common.collect.SetMultimap<SkyKey?, Artifact?> =
                            com.google.common.collect.MultimapBuilder.hashKeys().hashSetValues()
                                .build<SkyKey?, Artifact?>()
                        allInputsSet.forEach(
                            java.util.function.Consumer { input: Artifact? ->
                                val key: SkyKey? = Artifact.key(input)
                                if (key !== input) {
                                    skyKeyToArtifactSet.put(key, input)
                                }
                            })
                        skyKeyToArtifactSet
                    }),
                inputDepsResult,
                action,
                isMandatoryInput,
                inputDepKeys
            )
        val hasMissingInputs =
            actionExecutionFunctionExceptionHandler.accumulateAndMaybeThrowExceptions()

        if (env.valuesMissing()) {
            return null
        }

        val allInputsList: com.google.common.collect.ImmutableList<Artifact> = allInputs.toList()

        // When there are no missing values or there was an error, we can start checking individual
        // files. We don't bother to optimize the error-ful case since it's rare.
        val inputArtifactData: ActionInputMap = ActionInputMap(allInputsList.size())
        val undoneInputs: MutableList<Artifact?> = java.util.ArrayList<Artifact?>(0)

        for (input in allInputsList) {
            val value: SkyValue? =
                getAndCheckInputSkyValue(
                    env,
                    action,
                    input,
                    inputDepKeys,
                    isMandatoryInput,
                    actionExecutionFunctionExceptionHandler
                )

            if (value != null) {
                ActionInputMapHelper.addToMap(
                    inputArtifactData, input, value, MetadataConsumerForMetrics.NO_OP
                )
            } else if (!hasMissingInputs && input.hasKnownGeneratingAction()) {
                // Derived inputs are mandatory, but we did not detect any missing inputs. This is only
                // possible for indirect inputs (beneath an ArtifactNestedSetKey) when, between the time the
                // associated direct dependency ArtifactNestedSetKey completes successfully and the call to
                // lookupInput, the input's key was rewound and completed with an error.
                undoneInputs.add(input)
            }
        }

        if (!undoneInputs.isEmpty()) {
            throw UndoneInputsException(
                com.google.common.collect.ImmutableSet.copyOf<Artifact?>(undoneInputs),
                inputDepKeys
            )
        }

        // If there were no errors, we don't go through the scheduling dependencies because the only
        // reason to do so is to find and report missing input source files.
        if (hasMissingInputs) {
            // We unwrap the nested set like in getInputDepKeys(); apparently, if we don't do this, it's
            // a significant memory use hit due to the memoized graph traversal in NestedSet. This only
            // matters when a build encounters a missing source file which then gets resolved in a
            // subsequent build without re-analysis (and thus the memo fields in NestedSet survive)
            val seen: com.google.devtools.build.lib.collect.compacthashset.CompactHashSet<Artifact?> =
                com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.create<Artifact?>()
            for (input in action.getSchedulingDependencies().getLeaves()) {
                com.google.common.base.Verify.verify(seen.add(input))
                getAndCheckInputSkyValue(
                    env,
                    action,
                    input,
                    inputDepKeys,
                    isMandatoryInput,
                    actionExecutionFunctionExceptionHandler
                )
            }

            for (nonLeaf in action.getSchedulingDependencies().getNonLeaves()) {
                for (input in nonLeaf.toList()) {
                    if (seen.add(input)) {
                        getAndCheckInputSkyValue(
                            env,
                            action,
                            input,
                            inputDepKeys,
                            isMandatoryInput,
                            actionExecutionFunctionExceptionHandler
                        )
                    }
                }
            }
        }

        // After accumulating the inputs, we might find some mandatory artifact with
        // SourceFileInErrorArtifactValue.
        actionExecutionFunctionExceptionHandler.maybeThrowException()

        return CheckInputResults(inputArtifactData)
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(java.lang.InterruptedException::class)
    private fun getAndCheckInputSkyValue(
        env: SkyFunction.Environment,
        action: Action?,
        input: Artifact,
        inputDepKeys: com.google.common.collect.ImmutableSet<SkyKey>,
        isMandatoryInput: java.util.function.Predicate<Artifact?>,
        actionExecutionFunctionExceptionHandler: ActionExecutionFunctionExceptionHandler?
    ): SkyValue? {
        val value: SkyValue? = lookupInput(input, inputDepKeys, env)
        if (value == null) {
            // Undone mandatory inputs are only expected for generated artifacts when rewinding is
            // enabled. Returning null allows the caller to use UndoneInputsException to recover.
            com.google.common.base.Preconditions.checkState(
                !isMandatoryInput.test(input)
                        || (input.hasKnownGeneratingAction() && skyframeActionExecutor.rewindingEnabled()),
                "Unexpected undone mandatory input: %s",
                input
            )
            return null
        }
        if (value is MissingArtifactValue) {
            if (!isMandatoryInput.test(input)) {
                return FileArtifactValue.MISSING_FILE_MARKER
            }
            com.google.common.base.Preconditions.checkNotNull<ActionExecutionFunctionExceptionHandler?>(
                actionExecutionFunctionExceptionHandler,
                "Missing artifact should have been caught already %s %s %s",
                input,
                value,
                action
            )
                .accumulateMissingFileArtifactValue(input, value as MissingArtifactValue)
            return null
        }
        return value
    }

    /**
     * Looks up the value for an input without adding additional Skyframe dependencies.
     * 
     * 
     * If the input's [Artifact.key] is already a direct dependency, looks up its value in
     * the [Environment]. Otherwise, the input is assumed to be beneath an already-requested
     * [ArtifactNestedSetKey], and [ ][MemoizingEvaluator.getExistingEntryAtCurrentlyEvaluatingVersion] is used.
     */
    @Throws(java.lang.InterruptedException::class)
    private fun lookupInput(
        input: Artifact?,
        inputDepKeys: com.google.common.collect.ImmutableSet<SkyKey>,
        env: SkyFunction.Environment
    ): SkyValue? {
        val key: SkyKey? = Artifact.key(input)
        if (inputDepKeys.contains(key)) {
            return env.getLookupHandleForPreviouslyRequestedDeps().get(key)
        }
        val entry: NodeEntry? = evaluator.get().getExistingEntryAtCurrentlyEvaluatingVersion(key)
        if (entry == null) {
            return null
        }
        // Use toValue() so that in case the input's generating action was rewound, we still get some
        // value. It might end up being a lost input when we execute the consuming action, but it may be
        // available if its generating action was rewound due to losing a different output. In the rare
        // case that rewinding completed with an error, this will return null.
        return entry.toValue()
    }

    /**
     * State to save work across restarts of ActionExecutionFunction due to missing values in the
     * graph for actions that discover inputs. There are three places where we save work, all for
     * actions that discover inputs:
     * 
     * 
     *  1. If not all known input metadata (coming from Action#getInputs) is available yet, then the
     * calculated set of inputs (including the inputs resolved from the action cache) is saved.
     *  1. If not all discovered inputs' metadata is available yet, then the known input metadata
     * together with the set of discovered inputs is saved, as well as the Token used to
     * identify this action to the action cache.
     *  1. If, after execution, new inputs are discovered whose metadata is not yet available, then
     * the same data as in the previous case is saved, along with the actual result of
     * execution.
     * 
     */
    internal class InputDiscoveryState : SerializableSkyKeyComputeState {
        var allInputs: AllInputs? = null

        /** Mutable map containing metadata for known artifacts.  */
        var inputArtifactData: ActionInputMap? = null

        /** A thin wrapper around ActionInputMap for Fileset-related caching.  */
        var actionInputMetadataProvider: ActionInputMetadataProvider? = null

        /** An input metadata provider that does Skyframe lookups.  */
        var skyframeInputMetadataProvider: SkyframeInputMetadataProvider? = null

        /**
         * The input metadata provider that knows everything required to look up action inputs. It
         * consists of these parts:
         * 
         * 
         *  * The set of direct action inputs ([.inputArtifactData])
         *  * Skyframe lookups for generated artifacts that are not direct inputs
         *  * File system lookups for source artifacts that are not direct inputs
         * 
         * 
         * The latter two exist to support input discovery, when an action may well read files that are
         * not direct inputs. The metadata is actually in Skyframe so we could conceivably create the
         * equivalent of an [ActionInputMap] with scheduling dependencies and then these two would
         * not be needed. However, it would incur a huge performance hit because the most significant
         * use of input discovery is C++ include scanning, where the vast majority of scheduling
         * dependencies are not actually accessed.
         */
        var compositeInputMetadataProvider: DelegatingPairInputMetadataProvider? = null

        var token: Token? = null
        var discoveredInputs: NestedSet<Artifact?>? = null
        var actionFileSystem: com.google.devtools.build.lib.vfs.FileSystem? = null
        var preparedInputDiscovery: Boolean = false
        var actionInputCollectedEventSent: Boolean = false

        var checkedForConsumedArtifactRegistration: Boolean = false

        // Initialized lazily
        var retrievalContext: RetrievalContext? = null
            get() {
                if (field == null) {
                    field = RetrievalContext()
                }

                return field
            }
            private set

        fun hasCollectedInputs(): Boolean {
            return allInputs != null
        }

        fun hasArtifactData(): Boolean {
            return inputArtifactData != null
        }

        fun hasCheckedActionCache(): Boolean {
            // If token is null because there was an action cache hit, this method is never called again
            // because we return immediately.
            return token != null
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("token", token)
                .add("allInputs", allInputs)
                .add("inputArtifactData", inputArtifactData)
                .add("discoveredInputs", discoveredInputs)
                .toString()
        }
    }

    /**
     * Used to declare all the exception types that can be wrapped in the exception thrown by [ ][ActionExecutionFunction.compute].
     */
    internal class ActionExecutionFunctionException(e: ActionExecutionException) :
        SkyFunctionException(e, Transience.TRANSIENT) {
        private val actionException: ActionExecutionException

        init {
            // We conservatively assume that the error is transient. We don't have enough information to
            // distinguish non-transient errors (e.g. compilation error from a deterministic compiler)
            // from transient ones (e.g. IO error).
            // TODO(bazel-team): Have ActionExecutionExceptions declare their transience.
            this.actionException = e
        }

        val isCatastrophic: Boolean
            get() = actionException.isCatastrophe()
    }

    /**
     * Thrown when all direct dependencies are available but [.lookupInput] returns `null`
     * for one or more generated inputs.
     * 
     * 
     * This is only possible for indirect inputs (beneath an [ArtifactNestedSetKey]) when,
     * between the time the associated direct dependency [ArtifactNestedSetKey] is observed to
     * be done and the call to [.lookupInput], the input's [Artifact.key] was rewound and
     * completed with an error.
     */
    private class UndoneInputsException(
        undoneInputs: com.google.common.collect.ImmutableSet<Artifact?>?,
        inputDepKeys: com.google.common.collect.ImmutableSet<SkyKey>?
    ) : java.lang.Exception() {
        private val undoneInputs: com.google.common.collect.ImmutableSet<Artifact?>?
        private val inputDepKeys: com.google.common.collect.ImmutableSet<SkyKey>?

        init {
            this.undoneInputs = undoneInputs
            this.inputDepKeys = inputDepKeys
        }
    }

    /** Helper subclass for the error-handling logic for [.checkInputs].  */
    private inner class ActionExecutionFunctionExceptionHandler(
        skyKeyToDerivedArtifactSetForExceptions: java.util.function.Supplier<com.google.common.collect.SetMultimap<SkyKey?, Artifact?>?>,
        inputDepsResult: SkyframeLookupResult,
        action: Action,
        isMandatoryInput: java.util.function.Predicate<Artifact?>,
        inputDepKeys: com.google.common.collect.ImmutableSet<SkyKey>
    ) {
        private val skyKeyToDerivedArtifactSetForExceptions: java.util.function.Supplier<com.google.common.collect.SetMultimap<SkyKey?, Artifact?>?>
        private val inputDepsResult: SkyframeLookupResult
        private val action: Action
        private val isMandatoryInput: java.util.function.Predicate<Artifact?>
        private val inputDepKeys: com.google.common.collect.ImmutableSet<SkyKey>
        private val missingArtifactCauses: MutableList<LabelCause> =
            com.google.common.collect.Lists.newArrayListWithCapacity<LabelCause?>(0)
        private val transitiveCauses: MutableList<NestedSet<com.google.devtools.build.lib.causes.Cause?>?> =
            com.google.common.collect.Lists.newArrayListWithCapacity<NestedSet<com.google.devtools.build.lib.causes.Cause?>?>(
                0
            )
        private var firstActionExecutionException: ActionExecutionException? = null

        init {
            this.skyKeyToDerivedArtifactSetForExceptions = skyKeyToDerivedArtifactSetForExceptions
            this.inputDepsResult = inputDepsResult
            this.action = action
            this.isMandatoryInput = isMandatoryInput
            this.inputDepKeys = inputDepKeys
        }

        /**
         * Goes through the list of evaluated SkyKeys and handles any exception that arises, taking into
         * account whether the corresponding artifact(s) is a mandatory input.
         * 
         * 
         * Also updates ArtifactNestedSetFunction#skyKeyToSkyValue if an Artifact's value is
         * non-null.
         * 
         * @throws ActionExecutionException if the eval of any mandatory artifact threw an exception
         * @return true if there is at least one input artifact that is missing
         */
        @Throws(ActionExecutionException::class)
        fun accumulateAndMaybeThrowExceptions(): Boolean {
            var someInputsMissing = false
            for (key in inputDepKeys) {
                try {
                    val value: SkyValue? =
                        inputDepsResult.getOrThrow<E1?, E2?, E3?>(
                            key,
                            SourceArtifactException::class.java,
                            ActionExecutionException::class.java,
                            ArtifactNestedSetEvalException::class.java
                        )
                    if (value == null) {
                        continue
                    }
                    if (key is ArtifactNestedSetKey) {
                        if (value === ArtifactNestedSetValue.SOME_MISSING) {
                            someInputsMissing = true
                        }
                        continue
                    }

                    if (value is MissingArtifactValue) {
                        someInputsMissing = true
                    }
                } catch (e: SourceArtifactException) {
                    handleSourceArtifactExceptionFromSkykey(key, e)
                } catch (e: ActionExecutionException) {
                    handleActionExecutionExceptionFromSkykey(key, e)
                } catch (e: ArtifactNestedSetEvalException) {
                    for (skyKeyAndException in e.getNestedExceptions().toList()) {
                        val skyKey: SkyKey? = skyKeyAndException.getFirst()
                        val inputException: java.lang.Exception? = skyKeyAndException.getSecond()
                        com.google.common.base.Preconditions.checkState(
                            inputException is SourceArtifactException
                                    || inputException is ActionExecutionException,
                            "Unexpected exception type: %s, key: %s",
                            inputException,
                            skyKey
                        )
                        if (inputException is SourceArtifactException) {
                            handleSourceArtifactExceptionFromSkykey(
                                skyKey, inputException as SourceArtifactException?
                            )
                            continue
                        }
                        handleActionExecutionExceptionFromSkykey(
                            skyKey, inputException as ActionExecutionException?
                        )
                    }
                }
            }
            maybeThrowException()
            return someInputsMissing
        }

        fun handleActionExecutionExceptionFromSkykey(key: SkyKey?, e: ActionExecutionException) {
            if (key is Artifact) {
                handleActionExecutionExceptionPerArtifact(key, e)
                return
            }
            val associatedInputs: MutableSet<Artifact?> = skyKeyToDerivedArtifactSetForExceptions.get().get(key)
            if (associatedInputs.isEmpty()) {
                // This can happen if an action prunes its inputs, e.g. the way StarlarkAction implements
                // unused_inputs_list. An input may no longer be present in getInputs(), but its generating
                // action could still be a Skyframe dependency because Skyframe eagerly adds a dep group to
                // a dirty node if all prior dep groups are clean. If the pruned input is in error, it
                // propagates during error bubbling, and we reach this point.
                // TODO(lberki): Can inputs be immutable instead?
                logger.atWarning().log(
                    "While handling errors for %s, encountered error from %s which is not associated with"
                            + " any inputs",
                    action.prettyPrint(), key
                )
                if (firstActionExecutionException == null) {
                    firstActionExecutionException = e
                    transitiveCauses.add(e.getRootCauses())
                }
            } else {
                for (input in associatedInputs) {
                    handleActionExecutionExceptionPerArtifact(input, e)
                }
            }
        }

        fun handleSourceArtifactExceptionFromSkykey(key: SkyKey, e: SourceArtifactException) {
            if (key !is Artifact || !(key as Artifact).isSourceArtifact()) {
                bugReporter.logUnexpected(
                    e, "Unexpected SourceArtifactException for key: %s, %s", key, action.prettyPrint()
                )
                missingArtifactCauses.add(
                    LabelCause(action.getOwner().getLabel(), e.getDetailedExitCode())
                )
                return
            }

            if (isMandatoryInput.test(key as Artifact)) {
                missingArtifactCauses.add(
                    createLabelCauseNullOwnerOk(
                        key as Artifact,
                        e.getDetailedExitCode(),
                        action.getOwner().getLabel(),
                        bugReporter
                    )
                )
            }
        }

        fun accumulateMissingFileArtifactValue(input: Artifact, value: MissingArtifactValue) {
            missingArtifactCauses.add(
                createLabelCause(
                    input, value.getDetailedExitCode(), action.getOwner().getLabel(), bugReporter
                )
            )
        }

        /**
         * @throws ActionExecutionException if there is any accumulated exception from the inputs.
         */
        @Throws(ActionExecutionException::class)
        fun maybeThrowException() {
            for (missingInput in missingArtifactCauses) {
                skyframeActionExecutor.printError(missingInput.message, action)
            }
            // We need to rethrow the first exception because it can contain a useful error message.
            if (firstActionExecutionException != null) {
                if (missingArtifactCauses.isEmpty()
                    && (com.google.common.base.Preconditions.checkNotNull<MutableList<NestedSet<com.google.devtools.build.lib.causes.Cause?>?>?>(
                        transitiveCauses,
                        action
                    ).size() == 1)
                ) {
                    // In the case a single action failed, just propagate the exception upward. This avoids
                    // having to copy the root causes to the upwards transitive closure.
                    throw firstActionExecutionException
                }
                val allCauses: NestedSetBuilder<com.google.devtools.build.lib.causes.Cause?> =
                    NestedSetBuilder.< Cause > stableOrder < com . google . devtools . build . lib . causes . Cause ? > ().addAll(
                        missingArtifactCauses
                    )
                transitiveCauses.forEach(allCauses::addTransitive)
                throw ActionExecutionException(
                    firstActionExecutionException.getMessage(),
                    firstActionExecutionException.getCause(),
                    action,
                    allCauses.build(),
                    firstActionExecutionException.isCatastrophe(),
                    firstActionExecutionException.getDetailedExitCode()
                )
            }

            if (!missingArtifactCauses.isEmpty()) {
                throw throwSourceErrorException(action, missingArtifactCauses)
            }
        }

        fun handleActionExecutionExceptionPerArtifact(
            input: Artifact?, e: ActionExecutionException
        ) {
            if (isMandatoryInput.test(input)) {
                // Prefer a catastrophic exception as the one we propagate.
                if (firstActionExecutionException == null
                    || (!firstActionExecutionException.isCatastrophe() && e.isCatastrophe())
                ) {
                    firstActionExecutionException = e
                }
                transitiveCauses.add(e.getRootCauses())
            }
        }
    }

    @Throws(ActionExecutionException::class)
    private fun throwSourceErrorException(
        action: Action?, sourceArtifactErrorCauses: MutableList<out com.google.devtools.build.lib.causes.Cause>
    ): ActionExecutionException? {
        val codeAndMessage: com.google.devtools.build.lib.util.Pair<DetailedExitCode?, String?> =
            createSourceErrorCodeAndMessage(sourceArtifactErrorCauses, action)
        val ex: ActionExecutionException =
            ActionExecutionException(
                codeAndMessage.getSecond(),
                action,
                NestedSetBuilder.wrap(Order.STABLE_ORDER, sourceArtifactErrorCauses),  /* catastrophe= */
                false,
                codeAndMessage.getFirst()
            )
        skyframeActionExecutor.printError(ex.getMessage(), action)
        // Don't actually return: throw exception directly so caller can't get it wrong.
        throw ex
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private fun getInputDepKeys(
            consumedArtifactsTracker: ConsumedArtifactsTracker?,
            allInputs: NestedSet<Artifact?>,
            schedulingDependencies: NestedSet<Artifact?>,
            state: InputDiscoveryState?
        ): com.google.common.collect.ImmutableSet<SkyKey> {
            val result: com.google.common.collect.ImmutableSet.Builder<SkyKey?> =
                com.google.common.collect.ImmutableSet.builder<SkyKey?>()

            // Register the action's inputs and scheduling deps as "consumed" in the build.
            // As a general rule, we do it before requesting for the evaluation of these artifacts. This
            // would provide a good estimate of which outputs are consumed.
            if (consumedArtifactsTracker != null && !state!!.checkedForConsumedArtifactRegistration) {
                // Only registering the leaves here, since the Artifacts under non-leaves will be registered
                // in ArtifactNestedSetFunction. Similarly for the non-singleton Scheduling Dependencies.
                for (input in allInputs.getLeaves()) {
                    consumedArtifactsTracker.registerConsumedArtifact(input)
                }
                if (schedulingDependencies.isSingleton()) {
                    consumedArtifactsTracker.registerConsumedArtifact(schedulingDependencies.getSingleton())
                }
                state.checkedForConsumedArtifactRegistration = true
            }

            // We "unwrap" the NestedSet and evaluate the first layer of direct Artifacts here in order to
            // save memory:
            // - This top layer costs 1 extra ArtifactNestedSetKey node.
            // - It's uncommon that 2 actions share the exact same set of inputs
            //   => the top layer offers little in terms of reusability.
            // More details: b/143205147.
            for (leaf in allInputs.getLeaves()) {
                result.add(Artifact.key(leaf))
            }

            if (schedulingDependencies.isSingleton()) {
                result.add(Artifact.key(schedulingDependencies.getSingleton()))
            } else if (!schedulingDependencies.isEmpty()) {
                result.add(ArtifactNestedSetKey.create(schedulingDependencies))
            }

            for (nonLeaf in allInputs.getNonLeaves()) {
                result.add(ArtifactNestedSetKey.create(nonLeaf))
            }

            return result.build()
        }

        private fun makeMandatoryInputPredicate(action: Action): java.util.function.Predicate<Artifact?> {
            if (!action.discoversInputs()) {
                return com.google.common.base.Predicates.alwaysTrue<Artifact?>()
            }

            return object : java.util.function.Predicate<Artifact?> {
                // Lazily flatten the NestedSet in case the predicate is never needed. It's only used in the
                // exceptional case of a missing artifact.
                private var mandatoryInputs: com.google.common.collect.ImmutableSet<Artifact?>? = null
                private var schedulingDependencies: com.google.common.collect.ImmutableSet<Artifact?>? = null

                override fun test(input: Artifact): Boolean {
                    if (!input.isSourceArtifact()) {
                        return true
                    }
                    if (mandatoryInputs == null) {
                        mandatoryInputs = action.getMandatoryInputs().toSet()
                    }

                    if (mandatoryInputs.contains(input)) {
                        return true
                    }

                    if (schedulingDependencies == null) {
                        schedulingDependencies = action.getSchedulingDependencies().toSet()
                    }

                    if (schedulingDependencies.contains(input)) {
                        return true
                    }

                    return false
                }
            }
        }

        fun createLabelCause(
            input: Artifact,
            detailedExitCode: DetailedExitCode,
            labelInCaseOfBug: Label?,
            bugReporter: BugReporter
        ): LabelCause {
            if (input.getOwner() == null) {
                bugReporter.sendBugReport(
                    java.lang.IllegalStateException(
                        java.lang.String.format(
                            "Mandatory artifact %s with exit code %s should have owner (%s)",
                            input, detailedExitCode, labelInCaseOfBug
                        )
                    )
                )
            }
            return createLabelCauseNullOwnerOk(input, detailedExitCode, labelInCaseOfBug, bugReporter)
        }

        private fun createLabelCauseNullOwnerOk(
            input: Artifact,
            detailedExitCode: DetailedExitCode,
            actionLabel: Label?,
            bugReporter: BugReporter
        ): LabelCause {
            if (!input.isSourceArtifact()) {
                bugReporter.logUnexpected(
                    "Unexpected exit code %s for generated artifact %s (%s)",
                    detailedExitCode, input, actionLabel
                )
            }
            return LabelCause(
                com.google.common.base.MoreObjects.firstNonNull<T?>(input.getOwner(), actionLabel), detailedExitCode
            )
        }

        /**
         * Called when there are no action execution errors (whose reporting hides missing sources), but
         * there was at least one missing/io exception-triggering source artifact. Returns a [ ] constructed from `sourceArtifactErrorCauses` specific to a single such
         * artifact and an error message suitable as the message to a thrown exception that summarizes the
         * findings.
         */
        fun createSourceErrorCodeAndMessage(
            sourceArtifactErrorCauses: MutableList<out com.google.devtools.build.lib.causes.Cause>, debugInfo: Any?
        ): com.google.devtools.build.lib.util.Pair<DetailedExitCode?, String?> {
            val sawSourceArtifactException: AtomicBoolean = AtomicBoolean()
            val sawMissingFile: AtomicBoolean = AtomicBoolean()
            val prioritizedDetailedExitCode: DetailedExitCode =
                sourceArtifactErrorCauses.stream()
                    .map<DetailedExitCode>(com.google.devtools.build.lib.causes.Cause::detailedExitCode)
                    .peek(
                        java.util.function.Consumer { code: DetailedExitCode ->
                            if (code.getFailureDetail() == null) {
                                BugReport.sendBugReport(
                                    java.lang.NullPointerException(
                                        "Code " + code + " had no failure detail for " + debugInfo
                                    )
                                )
                                return@peek
                            }
                            if (code.getFailureDetail().hasFilesystem()) {
                                sawSourceArtifactException.set(true)
                                return@peek
                            }
                            when (code.getFailureDetail().getExecution().getCode()) {
                                SOURCE_INPUT_IO_EXCEPTION -> sawSourceArtifactException.set(true)
                                SOURCE_INPUT_MISSING -> sawMissingFile.set(true)
                                else -> BugReport.sendNonFatalBugReport(
                                    java.lang.IllegalStateException(
                                        "Unexpected error code in " + code + " for " + debugInfo
                                    )
                                )
                            }
                        })
                    .max(DetailedExitCodeComparator.INSTANCE)
                    .get()
            val errorMessage =
                (sourceArtifactErrorCauses.size()
                    .toString() + " input file(s) "
                        + com.google.common.base.Joiner.on(" or ")
                    .skipNulls()
                    .join(
                        if (sawSourceArtifactException.get()) "are in error" else null,
                        if (sawMissingFile.get()) "do not exist" else null
                    ))
            return com.google.devtools.build.lib.util.Pair.of<DetailedExitCode?, String?>(
                prioritizedDetailedExitCode,
                errorMessage
            )
        }

        private fun createDetailedExitCodeForMissingDiscoveredInput(message: String?): DetailedExitCode {
            return DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setExecution(Execution.newBuilder().setCode(Code.DISCOVERED_INPUT_DOES_NOT_EXIST))
                    .build()
            )
        }
    }
}
