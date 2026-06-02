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

import com.google.devtools.build.lib.actions.ActionConflictException

/**
 * The Skyframe function that generates aspects.
 * 
 * 
 * This class, together with [ConfiguredTargetFunction] drives the analysis phase. For more
 * information, see [com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory].
 * 
 * 
 * [AspectFunction] takes a SkyKey containing an [AspectKey] [a tuple of (target
 * label, configurations, aspect class and aspect parameters)], loads an [Aspect] from aspect
 * class and aspect parameters, gets a [ConfiguredTarget] for label and configurations, and
 * then creates a [ConfiguredAspect] for a given [AspectKey].
 * 
 * 
 * See [com.google.devtools.build.lib.packages.AspectClass] documentation for an overview
 * of aspect-related classes
 * 
 * @see com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory
 * 
 * @see com.google.devtools.build.lib.packages.AspectClass
 */
internal class AspectFunction(
    buildViewProvider: BuildViewProvider,
    ruleClassProvider: RuleClassProvider?,
    storeTransitivePackages: Boolean,
    prerequisitePackages: PrerequisitePackageFunction?,
    baseTargetPrerequisitesSupplier: BaseTargetPrerequisitesSupplier?,
    cachingDependenciesSupplier: com.google.common.base.Supplier<RemoteAnalysisCacheReaderDepsProvider?>,
    analysisProgressReceiver: AnalysisProgressReceiver
) : SkyFunction {
    private val buildViewProvider: BuildViewProvider
    private val ruleClassProvider: RuleClassProvider?

    /**
     * Indicates whether the set of packages transitively loaded for a given [AspectValue] will
     * be needed later (see [ ][com.google.devtools.build.lib.analysis.ConfiguredObjectValue.getTransitivePackages]). If not,
     * they are not collected and stored.
     */
    private val storeTransitivePackages: Boolean

    /**
     * Packages of prerequisites.
     * 
     * 
     * See [ConfiguredTargetFunction.prerequisitePackages] for more details.
     */
    private val prerequisitePackages: PrerequisitePackageFunction?

    /**
     * Used to look up configured targets and configurations of the underlying target dependencies
     * without adding dependency edges to them.
     * 
     * 
     * A regular `Skyframe` lookup of the target's dependencies while evaluating the aspect
     * propagation logic adds unnecessary dependency edges between the aspect and its target's
     * dependencies. Instead [BaseTargetPrerequisitesSupplier] function is used to directly look
     * up the dependency values.
     * 
     * 
     * Regular `Skyframe` lookup is used to get the [ConfiguredTargetValue]s of the
     * aspect's implicit dependencies and its underlying target to establish a dependency from the
     * [AspectValue] to them. While aspect explicit attributes can only be of types: string,
     * integer or boolean so no dependencies will be created from them.
     * 
     * 
     * This is safe from incrementality perspective because if a dependency is invalidated, the
     * underlying target will be invalidated and transitively invalidates the [AspectValue].
     */
    private val baseTargetPrerequisitesSupplier: BaseTargetPrerequisitesSupplier?

    private val cachingDependenciesSupplier: com.google.common.base.Supplier<RemoteAnalysisCacheReaderDepsProvider?>
    private val analysisProgressReceiver: AnalysisProgressReceiver

    init {
        this.buildViewProvider = buildViewProvider
        this.ruleClassProvider = ruleClassProvider
        this.storeTransitivePackages = storeTransitivePackages
        this.prerequisitePackages = prerequisitePackages
        this.baseTargetPrerequisitesSupplier = baseTargetPrerequisitesSupplier
        this.cachingDependenciesSupplier = cachingDependenciesSupplier
        this.analysisProgressReceiver = analysisProgressReceiver
    }

    internal class State
    private constructor(storeTransitivePackages: Boolean, prerequisitePackages: PrerequisitePackageFunction?) :
        SerializableSkyKeyComputeState, UnloadedToolchainContextsProducer.ResultSink {
        var initialValues: InitialValues? = null

        val computeDependenciesState: com.google.devtools.build.lib.skyframe.DependencyResolver.State

        /**
         * Computes the [UnloadedToolchainContext] collection for the underlying target of the
         * aspect.
         * 
         * 
         * One of [.baseTargetUnloadedToolchainContexts], [ ][.baseTargetUnloadedToolchainContextsError] or [.baseTargetHasNoToolchains] will be set
         * upon completion.
         */
        var baseTargetUnloadedToolchainContextsProducer:  // Non-null when in-flight.
                com.google.devtools.build.skyframe.state.Driver? = null

        var baseTargetUnloadedToolchainContexts: ToolchainCollection<UnloadedToolchainContext?>? = null

        var baseTargetUnloadedToolchainContextsError: ToolchainException? = null

        // Will be true if the target doesn't require toolchain resolution.
        var baseTargetHasNoToolchains: Boolean = false

        // Initialized lazily
        var retrievalContext: RetrievalContext? = null
            get() {
                if (field == null) {
                    field = RetrievalContext()
                }

                return field
            }
            private set

        init {
            this.computeDependenciesState =
                com.google.devtools.build.lib.skyframe.DependencyResolver.State(
                    storeTransitivePackages,
                    prerequisitePackages
                )
        }

        public override fun acceptUnloadedToolchainContexts(
            value: ToolchainCollection<UnloadedToolchainContext?>?
        ) {
            this.baseTargetUnloadedToolchainContexts = value
            if (this.baseTargetUnloadedToolchainContexts == null) {
                this.baseTargetHasNoToolchains = true
            }
        }

        public override fun acceptUnloadedToolchainContextsError(error: ToolchainException?) {
            this.baseTargetUnloadedToolchainContextsError = error
        }
    }

    private class InitialValues(
        aspect: Aspect?,
        aspectFactory: ConfiguredAspectFactory?,
        baseConfiguredTarget: ConfiguredTarget
    ) {
        private val aspect: Aspect?
        private val aspectFactory: ConfiguredAspectFactory?
        private val baseConfiguredTarget: ConfiguredTarget

        init {
            this.aspect = aspect
            this.aspectFactory = aspectFactory
            this.baseConfiguredTarget = baseConfiguredTarget
        }
    }

    @Throws(AspectFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val key: AspectKey = skyKey.argument() as AspectKey
        val stateSupplier: java.util.function.Supplier<State?> =
            java.util.function.Supplier {
                com.google.devtools.build.lib.skyframe.AspectFunction.State(
                    storeTransitivePackages,
                    prerequisitePackages
                )
            }

        val remoteCachingDependencies: RemoteAnalysisCacheReaderDepsProvider? =
            cachingDependenciesSupplier.get()
        if (remoteCachingDependencies.mode().isRetrievalEnabled()) {
            when (SkyValueRetrieverUtils.retrieveRemoteSkyValue(key, env, remoteCachingDependencies, stateSupplier)) {
                -> return null
                -> {
                    analysisProgressReceiver.doneDownloadedConfiguredAspect()
                    return v.value()
                }

                -> {}
            }
        }

        val state: State = env.getState<T>(stateSupplier)
        val computeDependenciesState: com.google.devtools.build.lib.skyframe.DependencyResolver.State =
            state.computeDependenciesState
        if (state.initialValues == null) {
            val initialValues = getInitialValues(computeDependenciesState, key, env)
            if (initialValues == null) {
                return null
            }
            state.initialValues = initialValues
        }
        val aspect: Aspect? = state.initialValues.aspect
        val aspectFactory: ConfiguredAspectFactory? = state.initialValues.aspectFactory
        var associatedTarget: ConfiguredTarget = state.initialValues.baseConfiguredTarget
        val targetAndConfiguration: TargetAndConfiguration = computeDependenciesState.targetAndConfiguration
        val target: Target = targetAndConfiguration.getTarget()
        val configuration: BuildConfigurationValue? = targetAndConfiguration.getConfiguration()

        // PrerequisitesProducer should skip creating aspects on materializer targets.
        com.google.common.base.Preconditions.checkState(!target.isMaterializerRule())

        // If the target is incompatible, then there's not much to do. The intent here is to create an
        // AspectValue that doesn't trigger any of the associated target's dependencies to be evaluated
        // against this aspect.
        if (associatedTarget.get(IncompatiblePlatformProvider.PROVIDER) != null
            ||  // Similarly, aspects that propagate into post-NoConfigTransition targets can't access
            // most flags or dependencies and are likely to be unsound. So make aspects propagating to
            // these configurations no-ops.
            (configuration != null && configuration.getOptions().hasNoConfig())
        ) {
            return AspectValue.create(
                key,
                aspect,
                ConfiguredAspect.NonApplicableAspect.INSTANCE,
                computeDependenciesState.transitivePackages()
            )
        }

        if (AliasProvider.isAlias(associatedTarget)) {
            return createAliasAspect(
                env,
                targetAndConfiguration,
                aspect,
                key,
                associatedTarget,
                computeDependenciesState.transitiveState
            )
        }
        // If we get here, label should match original label, and therefore the target we looked up
        // above indeed corresponds to associatedTarget.getLabel().
        com.google.common.base.Preconditions.checkState(
            associatedTarget.getOriginalLabel().equals(associatedTarget.getLabel()),
            "Non-alias %s should have matching label but found %s",
            associatedTarget.getOriginalLabel(),
            associatedTarget.getLabel()
        )

        if (associatedTarget.getConfigurationKey() == null || !targetSatisfiesAspect(target, aspect)) {
            // Aspects cannot apply to PackageGroups or InputFiles, the only cases where configuration key
            // is null. They also cannot apply to targets that don't satisfy the aspect's requirements.
            return AspectValue.create(
                key,
                aspect,
                ConfiguredAspect.NonApplicableAspect.INSTANCE,
                computeDependenciesState.transitivePackages()
            )
        }

        val topologicalAspectPath: com.google.common.collect.ImmutableList<Aspect?>?
        if (key.baseKeys.isEmpty()) {
            topologicalAspectPath = com.google.common.collect.ImmutableList.of<Aspect?>(aspect)
        } else {
            val orderedKeys: LinkedHashSet<AspectKey?> = LinkedHashSet<AspectKey?>()
            collectAspectKeysInTopologicalOrder(key.baseKeys, orderedKeys)
            val aspectValues: SkyframeLookupResult = env.getValuesAndExceptions(orderedKeys)
            if (env.valuesMissing()) {
                return null
            }
            val topologicalAspectPathBuilder: com.google.common.collect.ImmutableList.Builder<Aspect?> =
                com.google.common.collect.ImmutableList.builderWithExpectedSize<Aspect?>(orderedKeys.size() + 1)
            for (aspectKey in orderedKeys) {
                val aspectValue: AspectValue? = aspectValues.get(aspectKey) as AspectValue?
                if (aspectValue == null) {
                    BugReport.logUnexpected(
                        "aspectValue for: '%s' was missing, this should never happen", aspectKey
                    )
                    return null
                }
                topologicalAspectPathBuilder.add(aspectValue.getAspect())
            }
            topologicalAspectPath = topologicalAspectPathBuilder.add(aspect).build()

            val directlyRequiredAspects: MutableList<ConfiguredAspect?> =
                com.google.common.collect.Lists.transform<Any?, ConfiguredAspect?>(
                    key.baseKeys,
                    com.google.common.base.Function { k: Any? -> (aspectValues.get(k) as AspectValue?) })
            try {
                associatedTarget = MergedConfiguredTarget.of(associatedTarget, directlyRequiredAspects)
            } catch (e: MergingException) {
                env.getListener()
                    .handle(com.google.devtools.build.lib.events.Event.error(target.getLocation(), e.getMessage()))
                throw AspectFunctionException(
                    AspectCreationException(e.getMessage(), target.getLabel(), configuration)
                )
            }
        }

        try {
            val dependencyContext: DependencyContext? = getDependencyContext(computeDependenciesState, key, aspect, env)
            if (dependencyContext == null) {
                return null
            }

            var baseTargetUnloadedToolchainContexts: ToolchainCollection<UnloadedToolchainContext?>? = null
            if (target.isRule()) {
                val contextOrRestart: com.google.devtools.build.lib.util.Pair<ToolchainCollection<UnloadedToolchainContext?>?, Boolean?> =
                    getBaseTargetUnloadedToolchainContexts(
                        state, targetAndConfiguration, key.getBaseConfiguredTargetKey(), env
                    )
                if (contextOrRestart.second) {
                    return null // Need Skyframe deps.
                } else {
                    baseTargetUnloadedToolchainContexts = contextOrRestart.first
                }
            }

            val starlarkExecTransition: java.util.Optional<StarlarkAttributeTransitionProvider?>?
            try {
                starlarkExecTransition =
                    StarlarkExecTransitionLoader.loadStarlarkExecTransition(
                        if (targetAndConfiguration.getConfiguration() == null)
                            null
                        else
                            targetAndConfiguration.getConfiguration().getOptions(),
                        { bzlKey ->
                            env.getValueOrThrow<E?>(
                                bzlKey,
                                BzlLoadFailedException::class.java
                            ) as BzlLoadValue?
                        })
                if (starlarkExecTransition == null) {
                    return null // Need Skyframe deps.
                }
            } catch (e: StarlarkExecTransitionLoadingException) {
                throw AspectCreationException(e.getMessage(), key.getLabel(), configuration)
            } catch (e: java.lang.InterruptedException) {
                throw AspectCreationException(e.getMessage(), key.getLabel(), configuration)
            }

            val depValueMap: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>? =
                DependencyResolver.Companion.computeDependencies(
                    computeDependenciesState,
                    ConfiguredTargetKey.fromConfiguredTarget(associatedTarget),
                    topologicalAspectPath,  // Relevant for exec-config deps of the aspect itself. May need to pass the actual
                    // key if there's a use case for an aspect to be applied to exec-config deps of an
                    // aspect.
                    /* loadExecAspectsKey= */
                    null,
                    buildViewProvider.getSkyframeBuildView().getStarlarkTransitionCache(),
                    starlarkExecTransition.orElse(null),
                    env,
                    env.getListener(),
                    baseTargetPrerequisitesSupplier,
                    baseTargetUnloadedToolchainContexts
                )
            if (!computeDependenciesState.transitiveRootCauses().isEmpty()) {
                val causes: NestedSet<com.google.devtools.build.lib.causes.Cause?> =
                    computeDependenciesState.transitiveRootCauses().build()
                throw AspectFunctionException(
                    AspectCreationException(
                        "Loading failed", causes, DependencyResolver.Companion.getPrioritizedDetailedExitCode(causes)
                    )
                )
            }
            if (depValueMap == null) {
                return null
            }

            // Load the requested toolchains into the ToolchainContext, now that we have dependencies.
            val unloadedToolchainContexts: ToolchainCollection<UnloadedToolchainContext?>? =
                dependencyContext.unloadedToolchainContexts()
            var toolchainContexts: ToolchainCollection<ResolvedToolchainContext?>? = null
            if (unloadedToolchainContexts != null) {
                val targetDescription =
                    "aspect " + aspect.getDescriptor().getDescription() + " applied to " + target
                val contextsBuilder: ToolchainCollection.Builder<ResolvedToolchainContext?> =
                    ToolchainCollection.builder()
                for (unloadedContext in unloadedToolchainContexts.contextMap().entrySet()) {
                    val toolchainDependencies: com.google.common.collect.ImmutableSet<ConfiguredTargetAndData?> =
                        com.google.common.collect.ImmutableSet.copyOf<ConfiguredTargetAndData?>(
                            depValueMap.get(DependencyKind.forExecGroup(unloadedContext.getKey()))
                        )
                    contextsBuilder.addContext(
                        unloadedContext.getKey(),
                        ResolvedToolchainContext.load(
                            unloadedContext.getValue(), targetDescription, toolchainDependencies
                        )
                    )
                }
                toolchainContexts = contextsBuilder.build()
            }
            val baseTargetToolchainContexts: ToolchainCollection<AspectBaseTargetResolvedToolchainContext?>?
            try {
                baseTargetToolchainContexts =
                    getBaseTargetToolchainContexts(
                        baseTargetUnloadedToolchainContexts, aspect, target, depValueMap
                    )
            } catch (e: MergingException) {
                env.getListener()
                    .handle(com.google.devtools.build.lib.events.Event.error(target.getLocation(), e.getMessage()))
                throw AspectFunctionException(
                    AspectCreationException(e.getMessage(), target.getLabel(), configuration)
                )
            }
            return createAspect(
                env,
                key,
                topologicalAspectPath,
                aspect,
                aspectFactory,
                target,
                associatedTarget,
                configuration,
                dependencyContext.configConditions(),
                toolchainContexts,
                baseTargetToolchainContexts,
                computeDependenciesState.execGroupCollectionBuilder,
                depValueMap,
                computeDependenciesState.transitiveState,
                starlarkExecTransition.orElse(null)
            )
        } catch (e: DependencyEvaluationException) {
            // TODO(bazel-team): consolidate all env.getListener().handle() calls in this method, like in
            // ConfiguredTargetFunction. This encourages clear, consistent user messages (ideally without
            // the programmer having to think about it).
            if (!e.depReportedOwnError()) {
                env.getListener()
                    .handle(com.google.devtools.build.lib.events.Event.error(e.getLocation(), e.getMessage()))
            }
            if (e.getCause() is ConfiguredValueCreationException) {
                throw AspectFunctionException(
                    AspectCreationException(
                        cause.getMessage(), cause.getRootCauses(), cause.getDetailedExitCode()
                    )
                )
            }
            // Exception while evaluating the aspect {@code attr_aspects} and {@code toolchains_aspects}
            // functions.
            if (e.getCause() is net.starlark.java.eval.EvalException) {
                throw AspectFunctionException(
                    AspectCreationException(cause.getMessage(), key.getLabel(), configuration)
                )
            }
            // Cast to InconsistentAspectOrderException as a consistency check. If you add any
            // DependencyEvaluationException constructors, you may need to change this code, too.
            val cause: InconsistentAspectOrderException = e.getCause() as InconsistentAspectOrderException
            env.getListener()
                .handle(com.google.devtools.build.lib.events.Event.error(cause.getLocation(), cause.getMessage()))
            throw AspectFunctionException(
                AspectCreationException(cause.getMessage(), key.getLabel(), configuration)
            )
        } catch (e: AspectCreationException) {
            throw AspectFunctionException(e)
        } catch (e: ConfiguredValueCreationException) {
            throw AspectFunctionException(e)
        } catch (e: ToolchainException) {
            throw AspectFunctionException(
                AspectCreationException(
                    e.getMessage(), LabelCause(key.getLabel(), e.getDetailedExitCode())
                )
            )
        } catch (e: InvalidExecGroupException) {
            throw AspectFunctionException(
                AspectCreationException(
                    e.getMessage(), LabelCause(key.getLabel(), e.getDetailedExitCode())
                )
            )
        }
    }

    /**
     * Returns the [ToolchainCollection] of [UnloadedToolchainContext]s for the base
     * target and whether a Skyframe restart is needed.
     * 
     * 
     * `state.baseTargetUnloadedToolchainContexts` can be evaluated to `null` if the
     * base target doesn't require toolchain resolution (see [ ]). That's why an extra
     * boolean is returned to distinguish between the case when `state.baseTargetUnloadedToolchainContexts` is null because a Skyframe restart is needed and
     * when it is already evaluated to null.
     */
    @Throws(java.lang.InterruptedException::class, ToolchainException::class, InvalidExecGroupException::class)
    private fun getBaseTargetUnloadedToolchainContexts(
        state: State,
        targetAndConfiguration: TargetAndConfiguration,
        configuredTargetKey: ConfiguredTargetKey,
        env: SkyFunction.Environment
    ): com.google.devtools.build.lib.util.Pair<ToolchainCollection<UnloadedToolchainContext?>?, Boolean?> {
        // if the base target's toolchain contexts are already evaluated, return them.

        if (state.baseTargetUnloadedToolchainContexts != null || state.baseTargetHasNoToolchains) {
            return com.google.devtools.build.lib.util.Pair.of<ToolchainCollection<UnloadedToolchainContext?>?, Boolean?>(
                state.baseTargetUnloadedToolchainContexts,
                false
            )
        }

        // initiate evaluating the base target's toolchain contexts.
        if (state.baseTargetUnloadedToolchainContextsProducer == null) {
            val unloadedToolchainContextsInputs: UnloadedToolchainContextsInputs? =
                DependencyResolver.Companion.getUnloadedToolchainContextsInputs(
                    targetAndConfiguration,
                    configuredTargetKey.getExecutionPlatformLabel(),
                    ruleClassProvider,
                    env.getListener()
                )
            state.baseTargetUnloadedToolchainContextsProducer =
                com.google.devtools.build.skyframe.state.Driver(
                    UnloadedToolchainContextsProducer(
                        unloadedToolchainContextsInputs,
                        baseTargetPrerequisitesSupplier,
                        state as UnloadedToolchainContextsProducer.ResultSink,
                        { t -> StateMachine.DONE })
                )
        }
        if (state.baseTargetUnloadedToolchainContextsProducer.drive(env)) {
            state.baseTargetUnloadedToolchainContextsProducer = null
        } else {
            // Skyframe restart is needed
            return com.google.devtools.build.lib.util.Pair.of<ToolchainCollection<UnloadedToolchainContext?>?, Boolean?>(
                null,
                true
            )
        }
        val error: ToolchainException? = state.baseTargetUnloadedToolchainContextsError
        if (error != null) {
            throw error
        }

        // base target's toolchain contexts are evaluated in this iteration without requiring a
        // Skyframe restart.
        return com.google.devtools.build.lib.util.Pair.of<ToolchainCollection<UnloadedToolchainContext?>?, Boolean?>(
            state.baseTargetUnloadedToolchainContexts,
            false
        )
    }

    /** Populates `state.execGroupCollection` as a side effect.  */
    @Throws(
        java.lang.InterruptedException::class,
        ConfiguredValueCreationException::class,
        ToolchainException::class
    )  // Null if a Skyframe restart is needed.
    private fun getDependencyContext(
        state: com.google.devtools.build.lib.skyframe.DependencyResolver.State,
        key: AspectKey,
        aspect: Aspect,
        env: SkyFunction.Environment?
    ): DependencyContext? {
        if (state.dependencyContext != null) {
            return state.dependencyContext
        }
        if (state.dependencyContextProducer == null) {
            val targetAndConfiguration: TargetAndConfiguration = state.targetAndConfiguration
            val unloadedToolchainContextsInputs: UnloadedToolchainContextsInputs =
                getUnloadedToolchainContextsInputs(
                    aspect.getDefinition(),
                    key.getConfigurationKey(),
                    targetAndConfiguration.getConfiguration()
                )
            state.execGroupCollectionBuilder = unloadedToolchainContextsInputs
            state.dependencyContextProducer =
                com.google.devtools.build.skyframe.state.Driver(
                    DependencyContextProducer(
                        unloadedToolchainContextsInputs,
                        targetAndConfiguration,
                        key.getConfigurationKey(),
                        state.transitiveState,
                        state as DependencyContextProducer.ResultSink
                    )
                )
        }
        if (state.dependencyContextProducer.drive(env)) {
            state.dependencyContextProducer = null
        }

        // During error bubbling, the state machine might not be done, but still emit an error.
        val error: DependencyContextError? = state.dependencyContextError
        if (error != null) {
            when (error.kind()) {
                TOOLCHAIN -> throw error.toolchain()
                CONFIGURED_VALUE_CREATION -> throw error.configuredValueCreation()
                INCOMPATIBLE_TARGET -> throw java.lang.IllegalStateException("Unexpected error: " + error.incompatibleTarget())
                VALIDATION -> throw java.lang.IllegalStateException("Unexpected error: " + error.validation())
            }
            throw java.lang.IllegalStateException("unreachable")
        }

        return state.dependencyContext // Null if not yet done.
    }

    /**
     * Computes the given aspectKey of an alias-like target, by depending on the corresponding key of
     * the next target in the alias chain (if there are more), or the "real" configured target.
     */
    @Throws(java.lang.InterruptedException::class)
    private fun createAliasAspect(
        env: SkyFunction.Environment?,
        targetAndConfiguration: TargetAndConfiguration,
        aspect: Aspect?,
        originalKey: AspectKey,
        baseConfiguredTarget: ConfiguredTarget,
        transitiveState: TransitiveDependencyState?
    ): AspectValue? {
        val aliasChain: com.google.common.collect.ImmutableList<Label?> =
            baseConfiguredTarget.getProvider(AliasProvider::class.java).getAliasChain()

        val nextTarget: ConfiguredTarget = baseConfiguredTarget.getActualNoFollow()

        if (aliasChain.size() > 1) {
            com.google.common.base.Preconditions.checkState(aliasChain.get(1).equals(nextTarget.getOriginalLabel()))
        }

        val actualKey: AspectKey =
            buildAliasAspectKey(
                originalKey, nextTarget.getOriginalLabel(), nextTarget.getConfigurationKey()
            )

        return createAliasAspect(
            env, targetAndConfiguration.getTarget(), originalKey, aspect, actualKey, transitiveState
        )
    }

    @Throws(java.lang.InterruptedException::class)
    private fun createAliasAspect(
        env: SkyFunction.Environment,
        originalTarget: Target,
        originalKey: AspectKey?,
        aspect: Aspect?,
        depKey: AspectKey?,
        transitiveState: TransitiveDependencyState
    ): AspectValue? {
        // Compute the AspectValue of the target the alias refers to (which can itself be either an
        // alias or a real target)
        val real: AspectValue? = env.getValue(depKey) as AspectValue?
        if (env.valuesMissing()) {
            return null
        }

        val transitivePackages: NestedSet<Package.Metadata?>? =
            if (storeTransitivePackages)
                NestedSetBuilder.Metadata > stableOrder<Package.Metadata?>()
                    .add(originalTarget.getPackageMetadata())
                    .addTransitive(transitiveState.transitivePackages())
                    .addTransitive(real.getTransitivePackages())
                    .build()
            else
                null

        analysisProgressReceiver.doneConfigureAspect()
        return AspectValue.createForAlias(
            originalKey, aspect, ConfiguredAspect.forAlias(real), transitivePackages
        )
    }

    @Throws(AspectFunctionException::class, java.lang.InterruptedException::class)
    private fun createAspect(
        env: SkyFunction.Environment,
        key: AspectKey,
        topologicalAspectPath: com.google.common.collect.ImmutableList<Aspect?>?,
        aspect: Aspect,
        aspectFactory: ConfiguredAspectFactory?,
        associatedTarget: Target,
        associatedConfiguredTarget: ConfiguredTarget?,
        configuration: BuildConfigurationValue?,
        configConditions: ConfigConditions?,
        toolchainContexts: ToolchainCollection<ResolvedToolchainContext?>?,
        baseTargetToolchainContexts: ToolchainCollection<AspectBaseTargetResolvedToolchainContext?>?,
        execGroupCollectionBuilder: ExecGroupCollection.Builder?,
        directDeps: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>?,
        transitiveState: TransitiveDependencyState,
        starlarkExecTransition: StarlarkAttributeTransitionProvider?
    ): AspectValue? {
        // Should be successfully evaluated and cached from the loading phase.
        val starlarkBuiltinsValue: StarlarkBuiltinsValue? =
            env.getValue(StarlarkBuiltinsValue.Companion.key()) as StarlarkBuiltinsValue?
        if (env.valuesMissing()) {
            return null
        }

        val view: SkyframeBuildView = buildViewProvider.getSkyframeBuildView()

        val events: StoredEventHandler = StoredEventHandler()
        val analysisEnvironment: CachingAnalysisEnvironment =
            view.createAnalysisEnvironment(key, events, env, configuration, starlarkBuiltinsValue)

        var configuredAspect: ConfiguredAspect?
        if (aspect.getDefinition().applyToGeneratingRules()
            && associatedTarget is OutputFile
        ) {
            val label: Label? = associatedTarget.getGeneratingRule().getLabel()
            return createAliasAspect(
                env, associatedTarget, key, aspect, key.withLabel(label), transitiveState
            )
        } else {
            try {
                CurrentRuleTracker.beginConfiguredAspect(aspect.getAspectClass())
                configuredAspect =
                    view.getConfiguredTargetFactory()
                        .createAspect(
                            analysisEnvironment,
                            associatedTarget,
                            associatedConfiguredTarget,
                            topologicalAspectPath,
                            aspectFactory,
                            aspect,
                            directDeps,
                            configConditions,
                            toolchainContexts,
                            baseTargetToolchainContexts,
                            execGroupCollectionBuilder,
                            configuration,
                            transitiveState.transitivePackages(),
                            key,
                            starlarkExecTransition
                        )
            } catch (e: MissingDepException) {
                com.google.common.base.Preconditions.checkState(env.valuesMissing())
                return null
            } catch (e: ActionConflictException) {
                throw AspectFunctionException(e)
            } catch (e: RuleErrorException) {
                throw AspectFunctionException(e)
            } catch (e: InvalidExecGroupException) {
                throw AspectFunctionException(e)
            } finally {
                CurrentRuleTracker.endConfiguredAspect()
            }
        }

        events.replayOn(env.getListener())
        if (events.hasErrors()) {
            analysisEnvironment.disable(associatedTarget)
            val msg: String? =
                "Analysis of target '%s' (config: %s) failed"
                    .formatted(
                        associatedTarget.getLabel(),
                        if (configuration != null) configuration.getOptions().shortId() else "none"
                    )
            throw AspectFunctionException(
                AspectCreationException(msg, key.getLabel(), configuration)
            )
        }
        com.google.common.base.Preconditions.checkState(
            !analysisEnvironment.hasErrors(), "Analysis environment hasError() but no errors reported"
        )

        if (env.valuesMissing()) {
            return null
        }

        analysisEnvironment.disable(associatedTarget)
        com.google.common.base.Preconditions.checkNotNull<Any?>(configuredAspect)

        if (configuredAspect !== NonApplicableAspect.INSTANCE) {
            analysisProgressReceiver.doneConfigureAspect()
        }
        return AspectValue.create(key, aspect, configuredAspect, transitiveState.transitivePackages())
    }

    override fun extractTag(skyKey: SkyKey): String {
        val aspectKey: AspectKey = skyKey.argument() as AspectKey
        return Label.print(aspectKey.getLabel())
    }

    /** Used to indicate errors during the computation of an [AspectValue].  */
    class AspectFunctionException : SkyFunctionException {
        constructor(e: NoSuchThingException?) : super(e, Transience.PERSISTENT)

        constructor(e: AspectCreationException?) : super(e, Transience.PERSISTENT)

        constructor(e: ConfiguredValueCreationException?) : super(e, Transience.PERSISTENT)

        constructor(e: InvalidExecGroupException?) : super(e, Transience.PERSISTENT)

        constructor(cause: ActionConflictException?) : super(cause, Transience.PERSISTENT)

        constructor(cause: RuleErrorException?) : super(cause, Transience.PERSISTENT)
    }

    companion object {
        /**
         * Returns the [ToolchainCollection] of [AspectBaseTargetResolvedToolchainContext]s
         * for the base target.
         */
        @Throws(MergingException::class)
        private fun getBaseTargetToolchainContexts(
            baseTargetUnloadedToolchainContexts: ToolchainCollection<UnloadedToolchainContext?>?,
            aspect: Aspect,
            target: Target?,
            depValueMap: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>
        ): ToolchainCollection<AspectBaseTargetResolvedToolchainContext?>? {
            if (baseTargetUnloadedToolchainContexts == null) {
                return null
            }
            val description =
                "aspect " + aspect.getDescriptor().getDescription() + " applied to " + target

            val targetContextsBuilder: ToolchainCollection.Builder<AspectBaseTargetResolvedToolchainContext?> =
                ToolchainCollection.builder()

            for (unloadedContext in baseTargetUnloadedToolchainContexts.contextMap().entrySet()) {
                // For each requested toolchain type, collect the targets of its resolved toolchains. If
                // multiple types are resolved to the same toolchain, the `ConfiguredTargetAndData`
                // of the toolchain can be different for each of them depending on the aspects
                // propagating to each toolchain type.
                val toolchainsDeps: com.google.common.collect.ImmutableMultimap.Builder<ToolchainTypeInfo?, ConfiguredTargetAndData?> =
                    com.google.common.collect.ImmutableMultimap.builder<ToolchainTypeInfo?, ConfiguredTargetAndData?>()

                for (toolchainTypeInfo in unloadedContext.getValue().toolchainTypeToResolved().keySet()) {
                    toolchainsDeps.putAll(
                        toolchainTypeInfo,
                        com.google.common.collect.ImmutableSet.copyOf<ConfiguredTargetAndData?>(
                            depValueMap.get(
                                DependencyKind.forBaseTargetExecGroup(
                                    unloadedContext.getKey(), toolchainTypeInfo.typeLabel()
                                )
                            )
                        )
                    )
                }

                targetContextsBuilder.addContext(
                    unloadedContext.getKey(),
                    AspectBaseTargetResolvedToolchainContext.load(
                        unloadedContext.getValue(), description, toolchainsDeps.build()
                    )
                )
            }

            return targetContextsBuilder.build()
        }

        fun bzlLoadKeyForStarlarkAspect(starlarkAspectClass: StarlarkAspectClass): BzlLoadValue.Key {
            val extensionLabel: Label = starlarkAspectClass.getExtensionLabel()
            return if (StarlarkBuiltinsValue.Companion.isBuiltinsRepo(extensionLabel.getRepository()))
                BzlLoadValue.keyForBuiltins(extensionLabel)
            else
                BzlLoadValue.keyForBuild(extensionLabel)
        }

        @Throws(AspectFunctionException::class, java.lang.InterruptedException::class)
        private fun getInitialValues(
            state: com.google.devtools.build.lib.skyframe.DependencyResolver.State,
            key: AspectKey,
            env: SkyFunction.Environment
        ): InitialValues? {
            val configuredTargetLookupKey: ActionLookupKey? = key.getBaseConfiguredTargetKey()
            val basePackageKey: PackageIdentifier? =
                key.getBaseConfiguredTargetKey().getLabel().getPackageIdentifier()
            val initialKeys: com.google.common.collect.ImmutableSet.Builder<SkyKey?> =
                com.google.common.collect.ImmutableSet.builder<SkyKey?>().add(configuredTargetLookupKey)
                    .add(basePackageKey)

            val configurationKey: BuildConfigurationKey? = key.getConfigurationKey()
            if (configurationKey != null) {
                initialKeys.add(configurationKey)
            }

            val starlarkAspectClass: StarlarkAspectClass?
            val bzlLoadKey: BzlLoadValue.Key?
            if (key.getAspectClass() is NativeAspectClass) {
                starlarkAspectClass = null
                bzlLoadKey = null
            } else {
                com.google.common.base.Preconditions.checkState(
                    key.getAspectClass() is StarlarkAspectClass, "Unknown aspect class: %s", key
                )
                starlarkAspectClass = key.getAspectClass() as StarlarkAspectClass?
                initialKeys.add(bzlLoadKeyForStarlarkAspect(starlarkAspectClass).also { bzlLoadKey = it })
            }

            val initialValues: SkyframeLookupResult = env.getValuesAndExceptions(initialKeys.build())
            if (env.valuesMissing()) {
                return null
            }

            val baseConfiguredTarget: ConfiguredTarget
            try {
                val baseConfiguredTargetValue: ConfiguredTargetValue? =
                    initialValues.getOrThrow<E?>(
                        configuredTargetLookupKey, ConfiguredValueCreationException::class.java
                    ) as ConfiguredTargetValue?
                if (baseConfiguredTargetValue == null) {
                    // Assigned target might not be configured yet, in which case Skyframe restart is needed.
                    return null
                }
                baseConfiguredTarget = baseConfiguredTargetValue.getConfiguredTarget()
            } catch (e: ConfiguredValueCreationException) {
                throw AspectFunctionException(
                    AspectCreationException(e.getMessage(), e.getRootCauses(), e.getDetailedExitCode())
                )
            }
            com.google.common.base.Preconditions.checkState(
                key.getConfigurationKey() == baseConfiguredTarget.getConfigurationKey(),
                "Aspect not in same configuration as base configured target: %s, %s",
                key,
                baseConfiguredTarget
            )

            // Keep this in sync with the same code in ConfiguredTargetFunction.
            val basePackage: Package = (initialValues.get(basePackageKey) as PackageValue).getPackage()
            if (basePackage.containsErrors()) {
                throw AspectFunctionException(
                    BuildFileContainsErrorsException(key.getLabel().getPackageIdentifier())
                )
            }
            val target: Target?
            try {
                target = basePackage.getTarget(baseConfiguredTarget.getOriginalLabel().getName())
            } catch (e: NoSuchTargetException) {
                throw java.lang.IllegalStateException("Name already verified", e)
            }

            val configuration: BuildConfigurationValue? =
                if (configurationKey == null)
                    null
                else
                    initialValues.get(configurationKey) as BuildConfigurationValue?

            state.targetAndConfiguration = TargetAndConfiguration(target, configuration)

            val aspectFactory: ConfiguredAspectFactory?
            val aspect: Aspect?
            if (bzlLoadKey == null) {
                val nativeAspectClass: NativeAspectClass? = key.getAspectClass() as NativeAspectClass?
                aspectFactory = nativeAspectClass as ConfiguredAspectFactory?
                aspect = Aspect.forNative(nativeAspectClass, key.getParameters())
            } else {
                val starlarkAspect: StarlarkDefinedAspect?
                try {
                    val bzlLoadvalue: BzlLoadValue?
                    try {
                        bzlLoadvalue =
                            initialValues.getOrThrow<E?>(
                                bzlLoadKey,
                                BzlLoadFailedException::class.java
                            ) as BzlLoadValue?
                        if (bzlLoadvalue == null) {
                            BugReport.logUnexpected(
                                "Unexpected exception with %s and AspectKey %s", bzlLoadKey, key
                            )
                            return null
                        }
                    } catch (e: BzlLoadFailedException) {
                        throw AspectCreationException(
                            e.getMessage(), starlarkAspectClass.getExtensionLabel(), e.getDetailedExitCode()
                        )
                    }
                    starlarkAspect = loadAspectFromBzl(starlarkAspectClass, bzlLoadvalue)
                } catch (e: AspectCreationException) {
                    env.getListener().handle(com.google.devtools.build.lib.events.Event.error(e.getMessage()))
                    throw AspectFunctionException(e)
                }
                aspectFactory = StarlarkAspectFactory(starlarkAspect)
                aspect =
                    Aspect.forStarlark(
                        starlarkAspect.getAspectClass(),
                        starlarkAspect.getDefinition(key.getParameters()),
                        key.getParameters()
                    )
            }

            return InitialValues(aspect, aspectFactory, baseConfiguredTarget)
        }

        /**
         * Loads a Starlark-defined aspect from an extension file.
         * 
         * @throws AspectCreationException if the value loaded is not a [StarlarkDefinedAspect]
         */
        @Throws(AspectCreationException::class)
        fun loadAspectFromBzl(
            starlarkAspectClass: StarlarkAspectClass, bzlLoadValue: BzlLoadValue
        ): StarlarkDefinedAspect {
            val extensionLabel: Label? = starlarkAspectClass.getExtensionLabel()
            val starlarkValueName: String? = starlarkAspectClass.exportedName
            val starlarkValue: Any? = bzlLoadValue.getModule().getGlobal(starlarkValueName)
            if (starlarkValue !is StarlarkDefinedAspect) {
                throw AspectCreationException(
                    java.lang.String.format(
                        if (starlarkValue == null) "%s is not exported from %s" else "%s from %s is not an aspect",
                        starlarkValueName,
                        extensionLabel
                    ),
                    extensionLabel
                )
            }
            return starlarkValue as StarlarkDefinedAspect
        }

        private fun getUnloadedToolchainContextsInputs(
            aspectDefinition: AspectDefinition,
            configurationKey: BuildConfigurationKey?,
            configuration: BuildConfigurationValue?
        ): UnloadedToolchainContextsInputs {
            if (configuration == null) {
                // Configuration can be null in the case of aspects applied to input files. In this case,
                // there are no toolchains being used.
                return UnloadedToolchainContextsInputs.empty()
            }

            val useAutoExecGroups = shouldUseAutoExecGroups(aspectDefinition, configuration)
            val processedExecGroups: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                DeclaredExecGroup.process(
                    aspectDefinition.execGroups(),
                    aspectDefinition.execCompatibleWith(),  /* execGroupExecWith= */
                    com.google.common.collect.ImmutableMultimap.of<K?, V?>(),
                    aspectDefinition.getToolchainTypes(),
                    useAutoExecGroups
                )
            // Note: `configuration.getOptions().hasNoConfig()` is handled early in #compute.
            return UnloadedToolchainContextsInputs.create(
                processedExecGroups,
                ToolchainContextUtil.createDefaultToolchainContextKey(
                    configurationKey,
                    aspectDefinition.execCompatibleWith(),  /* debugTarget= */
                    false,  /* useAutoExecGroups= */
                    useAutoExecGroups,
                    aspectDefinition.getToolchainTypes(),  /* parentExecutionPlatformLabel= */
                    null
                )
            )
        }

        private fun shouldUseAutoExecGroups(
            aspectDefinition: AspectDefinition, configuration: BuildConfigurationValue
        ): Boolean {
            // TODO: b/370558813 - Use AutoExecGroupsMode for aspects, as well.
            val aspectAttributes: com.google.common.collect.ImmutableMap<String?, Attribute?> =
                aspectDefinition.getAttributes()
            if (aspectAttributes.containsKey("\$use_auto_exec_groups")) {
                return aspectAttributes.get("\$use_auto_exec_groups").defaultValueUnchecked as Boolean
            }
            return configuration.useAutoExecGroups()
        }

        /**
         * Collects [AspectKey] dependencies by performing a postorder traversal over [ ][AspectKey.getBaseKeys].
         * 
         * 
         * The resulting set of `orderedKeys` is topologically ordered: each aspect key appears
         * after all of its dependencies.
         */
        private fun collectAspectKeysInTopologicalOrder(
            baseKeys: MutableList<AspectKey>, orderedKeys: LinkedHashSet<AspectKey?>
        ) {
            for (key in baseKeys) {
                if (!orderedKeys.contains(key)) {
                    collectAspectKeysInTopologicalOrder(key.baseKeys, orderedKeys)
                    orderedKeys.add(key)
                }
            }
        }

        private fun buildAliasAspectKey(
            originalKey: AspectKey, aliasLabel: Label?, configurationKey: BuildConfigurationKey?
        ): AspectKey {
            val aliasedBaseKeys: com.google.common.collect.ImmutableList<AspectKey?>? =
                originalKey.baseKeys.stream()
                    .map({ baseKey -> buildAliasAspectKey(baseKey, aliasLabel, configurationKey) })
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
            return AspectKeyCreator.createAspectKey(
                originalKey.getAspectDescriptor(),
                aliasedBaseKeys,
                ConfiguredTargetKey.builder()
                    .setLabel(aliasLabel)
                    .setConfigurationKey(configurationKey)
                    .build()
            )
        }

        private fun targetSatisfiesAspect(target: Target, aspect: Aspect): Boolean {
            if (target.isRule()) {
                return (target as Rule).satisfies(aspect.getDefinition().getRequiredProviders())
            }

            if (aspect.getDefinition().applyToFiles() || aspect.getDefinition().applyToGeneratingRules()) {
                return true
            }

            return false
        }
    }
}
