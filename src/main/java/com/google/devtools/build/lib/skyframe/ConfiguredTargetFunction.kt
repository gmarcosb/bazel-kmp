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

import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue.configurationIdMessage

/**
 * SkyFunction for [ConfiguredTargetValue]s.
 * 
 * 
 * This class drives the analysis phase. For a review of the analysis phase, see [ ].
 * 
 * 
 * This function computes a target's complete analysis: its input is a target label and
 * configuration and its output is the target's actions. This implicitly constructs the build's
 * configured target and action graphs because a target's dependencies must be evaluated before the
 * target itself. If the build has multiple top-level targets, this is called for each one, and the
 * build-wide configured target and action graphs are the merged combination of each top-level call.
 * 
 * 
 * Multiple helper classes support this work, all called directly or indirectly from here:
 * 
 * 
 *  1. [DependencyResolver]: Analysis consists of two important steps: computing the
 * target's prerequisite dependencies and executing its rule logic. This class performs the
 * first step. It also performs supporting computations like `config_setting` and
 * toolchain resolution.
 *  1. [DependencyResolutionHelpers]: Helper for [DependencyResolver]: figures out
 * what this target's dependencies are and what their configurations should be.
 *  1. [DependencyKind]: Structured representation of a dependency's type (e.g. rule
 * attribute vs. toolchain dependency).
 *  1. [AspectFunction]: Evaluates aspects attached to this target's dependencies.
 *  1. [ConfiguredTargetFactory]: Executes this target's rule logic (and generally
 * constructs its [ConfiguredTarget] once all prerequisites are ready).
 * 
 * 
 * 
 * This list is not exhaustive.
 * 
 * @see com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory
 */
class ConfiguredTargetFunction internal constructor(
    buildViewProvider: BuildViewProvider,
    ruleClassProvider: RuleClassProvider,
    cpuBoundSemaphore: AtomicReference<Semaphore?>,
    storeTransitivePackages: Boolean,
    shouldUnblockCpuWorkWhenFetchingDeps: Boolean,
    analysisProgress: AnalysisProgressReceiver?,
    prerequisitePackages: PrerequisitePackageFunction?,
    cachingDependenciesSupplier: java.util.function.Supplier<RemoteAnalysisCacheReaderDepsProvider>
) : SkyFunction {
    private val buildViewProvider: BuildViewProvider
    private val ruleClassProvider: RuleClassProvider

    // TODO(b/185987566): Remove this semaphore.
    private val cpuBoundSemaphore: AtomicReference<Semaphore?>
    private val analysisProgress: AnalysisProgressReceiver?

    /**
     * Indicates whether the set of packages transitively loaded for a given [ ] will be needed later (see [ ][com.google.devtools.build.lib.analysis.ConfiguredObjectValue.getTransitivePackages]). If not,
     * they are not collected and stored.
     */
    private val storeTransitivePackages: Boolean

    private val shouldUnblockCpuWorkWhenFetchingDeps: Boolean

    private val cachingDependenciesSupplier: java.util.function.Supplier<RemoteAnalysisCacheReaderDepsProvider>

    /**
     * Packages of prerequisites.
     * 
     * 
     * These packages are needed by [ConfiguredTarget]s that depend on them. Instead of
     * declaring dependency edges on them in `Skyframe`, they can be looked up directly. The
     * package dependency edge is already implied by configured target dependency edge.
     * 
     * 
     * It is only valid to use this to lookup packages of prerequisites. Using this to lookup the
     * package of the primary configured target would cause incrementality errors because an essential
     * dependency edge would not be registered.
     */
    private val prerequisitePackages: PrerequisitePackageFunction?

    init {
        this.buildViewProvider = buildViewProvider
        this.ruleClassProvider = ruleClassProvider
        this.cpuBoundSemaphore = cpuBoundSemaphore
        this.storeTransitivePackages = storeTransitivePackages
        this.shouldUnblockCpuWorkWhenFetchingDeps = shouldUnblockCpuWorkWhenFetchingDeps
        this.analysisProgress = analysisProgress
        this.prerequisitePackages = prerequisitePackages
        this.cachingDependenciesSupplier = cachingDependenciesSupplier
    }

    @Throws(java.lang.InterruptedException::class)
    private fun maybeAcquireSemaphoreWithLogging(key: SkyKey?) {
        if (cpuBoundSemaphore.get() == null) {
            return
        }
        val stopwatch: com.google.common.base.Stopwatch = com.google.common.base.Stopwatch.createStarted()
        cpuBoundSemaphore.get().acquire()
        val elapsedTime: Long = stopwatch.elapsed().toMillis()
        if (elapsedTime > 5) {
            logger.atInfo().atMostEvery(10, TimeUnit.SECONDS).log(
                "Spent %s milliseconds waiting for lock acquisition for %s", elapsedTime, key
            )
        }
    }

    private fun maybeReleaseSemaphore() {
        if (cpuBoundSemaphore.get() != null) {
            cpuBoundSemaphore.get().release()
        }
    }

    private class State
        (storeTransitivePackages: Boolean, prerequisitePackages: PrerequisitePackageFunction?) :
        SerializableSkyKeyComputeState, TargetAndConfigurationProducer.ResultSink {
        /**
         * Drives a [TargetAndConfigurationProducer] that sets the [ ][.targetAndConfigurationResult] when complete.
         */
        // Non-null while in-flight.
        private var targetAndConfigurationProducer: com.google.devtools.build.skyframe.state.Driver? = null

        /**
         * Union-type output of [.targetAndConfigurationProducer].
         * 
         * 
         *  * [ConfiguredTargetKey]: if the result was a [TargetAndConfiguration], set in
         * [DependencyResolver.State.targetAndConfiguration].
         *  * [ConfiguredTargetValue]: an immediate value. This occurs when applying the rule
         * transition to the [ConfiguredTargetKey] results in a previously computed key.
         *  * [TargetAndConfigurationError]: if an error occurred.
         * 
         */
        private var targetAndConfigurationResult: Any? = null

        val computeDependenciesState: com.google.devtools.build.lib.skyframe.DependencyResolver.State

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

        public override fun acceptTargetAndConfiguration(
            value: TargetAndConfiguration?, fullKey: ConfiguredTargetKey?
        ) {
            computeDependenciesState.targetAndConfiguration = value
            this.targetAndConfigurationResult = fullKey
        }

        public override fun acceptTargetAndConfigurationDelegatedValue(value: ConfiguredTargetValue?) {
            this.targetAndConfigurationResult = value
        }

        public override fun acceptTargetAndConfigurationError(error: TargetAndConfigurationError?) {
            this.targetAndConfigurationResult = error
        }
    }

    @Throws(
        ReportedException::class,
        UnreportedException::class,
        DependencyException::class,
        java.lang.InterruptedException::class
    )
    override fun compute(key: SkyKey, env: SkyFunction.Environment): SkyValue? {
        var env: SkyFunction.Environment = env
        val stateSupplier: java.util.function.Supplier<State?> = java.util.function.Supplier {
            com.google.devtools.build.lib.skyframe.ConfiguredTargetFunction.State(
                storeTransitivePackages,
                prerequisitePackages
            )
        }
        var configuredTargetKey: ConfiguredTargetKey = key.argument() as ConfiguredTargetKey
        val view: SkyframeBuildView = buildViewProvider.getSkyframeBuildView()

        if (shouldUnblockCpuWorkWhenFetchingDeps) {
            // Fetching blocks on other resources, so we don't want to hold on to the semaphore meanwhile.
            // TODO(b/194319860): remove this and DependencyResolver.SemaphoreAcquirer when we no need
            // semaphore locking.
            env =
                StateInformingSkyFunctionEnvironment(
                    env,  /* preFetch= */
                    Informee { this.maybeReleaseSemaphore() },  /* postFetch= */
                    Informee { maybeAcquireSemaphoreWithLogging(key) })
        }

        val remoteCachingDependencies: RemoteAnalysisCacheReaderDepsProvider =
            cachingDependenciesSupplier.get()
        if (remoteCachingDependencies.mode().isRetrievalEnabled()) {
            when (SkyValueRetrieverUtils.retrieveRemoteSkyValue(
                configuredTargetKey, env, remoteCachingDependencies, stateSupplier
            )) {
                -> return null
                -> {
                    analysisProgress.doneDownloadedConfiguredTarget()
                    return v.value()
                }

                -> {}
            }
        }

        val state: State = env.getState<T>(stateSupplier)
        val computeDependenciesState: com.google.devtools.build.lib.skyframe.DependencyResolver.State =
            state.computeDependenciesState
        if (computeDependenciesState.targetAndConfiguration == null) {
            computeTargetAndConfiguration(env, state, configuredTargetKey)
            // Any `TargetAndConfigurationError` has already been handled, so `result` can only
            // be null, a `ConfiguredTargetKey` or a `ConfiguredTargetValue`.
            val result = state.targetAndConfigurationResult
            if (result !is ConfiguredTargetKey) {
                return result as ConfiguredTargetValue? // Null or an immediate `ConfiguredTargetValue`.
            }
            // Otherwise, `result` contains a `ConfiguredTargetKey`.
        }

        configuredTargetKey = state.targetAndConfigurationResult as ConfiguredTargetKey
        val prereqs: DependencyResolver =
            DependencyResolver(computeDependenciesState.targetAndConfiguration)
        try {
            // Perform all analysis through dependency evaluation.
            if (!prereqs.evaluate(
                    state.computeDependenciesState,
                    configuredTargetKey,
                    ruleClassProvider,
                    view.getStarlarkTransitionCache(),
                    SemaphoreAcquirer { maybeAcquireSemaphoreWithLogging(key) },
                    env,
                    env.getListener()
                )
            ) {
                return null
            }
            com.google.common.base.Preconditions.checkNotNull<OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>?>(
                prereqs.getDepValueMap()
            )

            // If one of our dependencies is platform-incompatible with this build, so are we.
            val incompatibleTarget: java.util.Optional<RuleConfiguredTargetValue?> =
                IncompatibleTargetChecker.createIndirectlyIncompatibleTarget(
                    prereqs.getTargetAndConfiguration(),
                    configuredTargetKey,
                    prereqs.getDepValueMap(),
                    prereqs.getConfigConditions(),
                    prereqs.getPlatformInfo(),
                    computeDependenciesState.transitiveState
                )
            if (incompatibleTarget.isPresent()) {
                return incompatibleTarget.get()
            }

            // IF this build has a --run_under target, check it's an executable. We have to check this at
            // the parent: --run_under targets are configured in the exec configuration, but the
            // --run_under build option doesn't pass to the exec config.
            val config: BuildConfigurationValue? = prereqs.getTargetAndConfiguration().getConfiguration()
            if (config != null && config.getRunUnder() is LabelRunUnder) {
                val runUnderTarget: java.util.Optional<ConfiguredTarget?> =
                    prereqs.getDepValueMap().values().stream()
                        .map<Any?>(ConfiguredTargetAndData::getConfiguredTarget)
                        .filter(java.util.function.Predicate { d: Any? -> d.getLabel().equals(runUnder.label()) })
                        .findAny()
                if (runUnderTarget.isPresent()
                    && runUnderTarget.get().getProvider(FilesToRunProvider::class.java).getExecutable() == null
                ) {
                    throw ConfiguredValueCreationException(
                        prereqs.getTargetAndConfiguration().getTarget(),
                        "run_under target " + runUnder.label() + " is not executable"
                    )
                }
            }

            // Load the requested toolchains into the ToolchainContext, now that we have dependencies.
            var toolchainContexts: ToolchainCollection<ResolvedToolchainContext?>? = null
            if (prereqs.getUnloadedToolchainContexts() != null) {
                val targetDescription: String? = prereqs.getTargetAndConfiguration().getTarget().toString()
                val contextsBuilder: ToolchainCollection.Builder<ResolvedToolchainContext?> =
                    ToolchainCollection.builder()
                for (unloadedContext in prereqs.getUnloadedToolchainContexts().contextMap().entrySet()) {
                    val toolchainDependencies: com.google.common.collect.ImmutableSet<ConfiguredTargetAndData?> =
                        com.google.common.collect.ImmutableSet.copyOf<ConfiguredTargetAndData?>(
                            prereqs
                                .getDepValueMap()
                                .get(DependencyKind.forExecGroup(unloadedContext.getKey()))
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

            // Run this target's rule logic to create its actions and return its ConfiguredTargetValue.
            val ans: ConfiguredTargetValue? =
                createConfiguredTarget(
                    view,
                    env,
                    prereqs.getTargetAndConfiguration(),
                    configuredTargetKey,
                    prereqs.getDepValueMap(),
                    prereqs.getMaterializerTargets(),
                    prereqs.getConfigConditions(),
                    toolchainContexts,
                    computeDependenciesState.execGroupCollectionBuilder,
                    state.computeDependenciesState.transitivePackages(),  /* crashIfExecutionPhase= */
                    !remoteCachingDependencies.mode().isRetrievalEnabled(),
                    remoteCachingDependencies.mode()
                )
            if (ans != null && analysisProgress != null) {
                analysisProgress.doneConfigureTarget()
            }
            return ans
        } catch (e: IncompatibleTargetChecker.IncompatibleTargetException) {
            return e.target()
        } catch (e: ConfiguredValueCreationException) {
            if (!e.getMessage().isEmpty()) {
                // Report the error to the user.
                env.getListener()
                    .handle(com.google.devtools.build.lib.events.Event.error(e.getLocation(), e.getMessage()))
            }
            throw ReportedException(e)
        } catch (e: ToolchainException) {
            val cvce: ConfiguredValueCreationException =
                e.asConfiguredValueCreationException(prereqs.getTargetAndConfiguration())
            env.getListener()
                .handle(
                    com.google.devtools.build.lib.events.Event.error(
                        prereqs.getTargetAndConfiguration().getTarget().getLocation(),
                        cvce.getMessage()
                    )
                )
            throw ReportedException(cvce)
        } catch (e: ActionConflictException) {
            // The reporting will be done when going through errors in the build.
            throw UnreportedException(e)
        } finally {
            maybeReleaseSemaphore()
        }
    }

    override fun extractTag(skyKey: SkyKey): String {
        return Label.print((skyKey.argument() as ConfiguredTargetKey).getLabel())
    }

    @Throws(DependencyException::class, ReportedException::class, java.lang.InterruptedException::class)
    private fun computeTargetAndConfiguration(
        env: SkyFunction.Environment, state: State, configuredTargetKey: ConfiguredTargetKey
    ) {
        val storedEvents: StoredEventHandler = state.computeDependenciesState.storedEvents
        var result: Any? = null
        var completedWithoutExceptions = false
        try {
            if (state.targetAndConfigurationProducer == null) {
                state.targetAndConfigurationProducer =
                    com.google.devtools.build.skyframe.state.Driver(
                        TargetAndConfigurationProducer(
                            configuredTargetKey,
                            (ruleClassProvider as ConfiguredRuleClassProvider)
                                .getTrimmingTransitionFactory(),
                            (ruleClassProvider as ConfiguredRuleClassProvider)
                                .getToolchainTaggedTrimmingTransition(),
                            buildViewProvider.getSkyframeBuildView().getStarlarkTransitionCache(),
                            state.computeDependenciesState.transitiveState,
                            state as TargetAndConfigurationProducer.ResultSink,
                            storedEvents
                        )
                    )
            }
            if (state.targetAndConfigurationProducer.drive(env)) {
                state.targetAndConfigurationProducer = null
            }
            result = state.targetAndConfigurationResult
            if (result is TargetAndConfigurationError) {
                when (result.kind()) {
                    CONFIGURED_VALUE_CREATION -> {
                        val e: ConfiguredValueCreationException = result.configuredValueCreation()
                        if (!e.getMessage().isEmpty()) {
                            // Reports the error to the user on storedEvents to preserve ordering. These will
                            // be immediately replayed in the finally clause.
                            storedEvents.post( // Even without an error here, the configuration key might not be turned into a
                                // configuration value by the build because it does not include the rule
                                // transition. It's therefore marked unavailable.
                                AnalysisRootCauseEvent.withUnavailableConfiguration(
                                    configurationIdMessage(configuredTargetKey.getConfigurationKey()),
                                    configuredTargetKey.getLabel(),
                                    e.getMessage()
                                )
                            )
                            storedEvents.handle(
                                com.google.devtools.build.lib.events.Event.error(
                                    e.getLocation(),
                                    e.getMessage()
                                )
                            )
                        }
                        throw ReportedException(e)
                    }

                    NO_SUCH_THING -> throw DependencyException(result.noSuchThing())
                    INCONSISTENT_NULL_CONFIG -> throw DependencyException(result.inconsistentNullConfig())
                }
            }
            completedWithoutExceptions = true // Marks the fact that there were no exceptions.
        } finally {
            // If there is exception or an immediate value ...
            if (!completedWithoutExceptions || result is ConfiguredTargetValue) {
                // ... replays events because `ConfiguredTargetFunction.compute` will promptly end.
                storedEvents.replayOn(env.getListener())
            }
            // Otherwise either:
            // 1. the result is null for a restart, so replayed events would not be used anyway; or
            // 2. the result is a `TargetAndConfiguration` value and
            //    `DependencyResolver.computeDependencies` takes ownership of stored events.
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        @Throws(
            ConfiguredValueCreationException::class,
            java.lang.InterruptedException::class,
            ActionConflictException::class
        )
        private fun createConfiguredTarget(
            view: SkyframeBuildView,
            env: SkyFunction.Environment,
            ctgValue: TargetAndConfiguration,
            configuredTargetKey: ConfiguredTargetKey?,
            depValueMap: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>?,
            materializerTargets: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>?,
            configConditions: ConfigConditions?,
            toolchainContexts: ToolchainCollection<ResolvedToolchainContext?>?,
            execGroupCollectionBuilder: ExecGroupCollection.Builder?,
            transitivePackages: NestedSet<Package.Metadata?>?,
            crashIfExecutionPhase: Boolean,
            remoteAnalysisCacheMode: RemoteAnalysisCacheMode?
        ): ConfiguredTargetValue? {
            val target: Target = ctgValue.getTarget()
            val configuration: BuildConfigurationValue? = ctgValue.getConfiguration()

            // Should be successfully evaluated and cached from the loading phase.
            val starlarkBuiltinsValue: StarlarkBuiltinsValue? =
                env.getValue(StarlarkBuiltinsValue.Companion.key()) as StarlarkBuiltinsValue?
            if (starlarkBuiltinsValue == null) {
                return null
            }

            val events: StoredEventHandler = StoredEventHandler()
            val analysisEnvironment: CachingAnalysisEnvironment =
                view.createAnalysisEnvironment(
                    configuredTargetKey, events, env, configuration, starlarkBuiltinsValue
                )

            com.google.common.base.Preconditions.checkNotNull<OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>?>(
                depValueMap
            )
            val configuredTarget: ConfiguredTarget?
            try {
                configuredTarget =
                    view.createConfiguredTarget(
                        target,
                        configuration,
                        analysisEnvironment,
                        configuredTargetKey,
                        depValueMap,
                        materializerTargets,
                        configConditions,
                        toolchainContexts,
                        transitivePackages,
                        execGroupCollectionBuilder,
                        crashIfExecutionPhase
                    )
            } catch (e: MissingDepException) {
                com.google.common.base.Preconditions.checkState(env.valuesMissing(), e.getMessage())
                return null
            } catch (e: InvalidExecGroupException) {
                throw ConfiguredValueCreationException(ctgValue.getTarget(), e.getMessage())
            } catch (e: StarlarkExecTransitionLoadingException) {
                throw ConfiguredValueCreationException(ctgValue.getTarget(), e.getMessage())
            } catch (e: AnalysisFailurePropagationException) {
                throw ConfiguredValueCreationException(
                    ctgValue.getTarget(),  /* buildEventId */
                    null,
                    e.getMessage(),  /* rootCauses= */
                    null,
                    e.getDetailedExitCode()
                )
            }

            events.replayOn(env.getListener())
            if (events.hasErrors()) {
                analysisEnvironment.disable(target)
                val rootCauses: NestedSet<com.google.devtools.build.lib.causes.Cause?>? =
                    NestedSetBuilder.wrap(
                        Order.STABLE_ORDER,
                        events.getEvents().stream()
                            .filter(java.util.function.Predicate { event: com.google.devtools.build.lib.events.Event? -> event.getKind() == com.google.devtools.build.lib.events.EventKind.ERROR })
                            .map<AnalysisFailedCause?>(
                                java.util.function.Function { event: com.google.devtools.build.lib.events.Event? ->
                                    AnalysisFailedCause(
                                        target.getLabel(),
                                        configurationIdMessage(configuration),
                                        createDetailedExitCode(event.getMessage())
                                    )
                                })
                            .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
                    )
                throw ConfiguredValueCreationException(
                    ctgValue.getTarget(),
                    null,
                    "Analysis of target '%s' (config: %s) failed"
                        .formatted(
                            target.getLabel(),
                            if (configuration != null) configuration.getOptions().shortId() else "none"
                        ),
                    rootCauses,
                    null
                )
            }
            com.google.common.base.Preconditions.checkState(
                !analysisEnvironment.hasErrors(), "Analysis environment hasError() but no errors reported"
            )
            if (env.valuesMissing()) {
                return null
            }

            analysisEnvironment.disable(target)
            com.google.common.base.Preconditions.checkNotNull<Any?>(configuredTarget, target)

            if (configuredTarget is RuleConfiguredTarget) {
                return RuleConfiguredTargetValue(configuredTarget, transitivePackages)
            } else {
                // Expected 4 args, but got 3.
                com.google.common.base.Preconditions.checkState(
                    analysisEnvironment.getRegisteredActions().isEmpty(),
                    "Non-rule can't have actions: %s %s %s",
                    configuredTargetKey,
                    analysisEnvironment.getRegisteredActions(),
                    configuredTarget
                )
                // If this is a Skycache download build, we check if it's an alias. For remote values, the
                // package isn't present but the target data is present
                if (remoteAnalysisCacheMode === RemoteAnalysisCacheMode.DOWNLOAD
                    && configuredTarget is AliasConfiguredTarget
                ) {
                    val configuredTargetValue: ConfiguredTargetValue? =
                        env.getValue(configuredTarget.getActual().getLookupKey()) as ConfiguredTargetValue?
                    // TODO: b/431749743 - The actual target's ConfiguredTargetValue is not a dependency of the
                    // alias's ConfiguredTargetValue. Still need to clarify why.
                    if (configuredTargetValue == null) {
                        return null
                    }
                    if (configuredTargetValue
                                is RemoteConfiguredTargetValue
                    ) {
                        return NonRuleConfiguredTargetValue(
                            configuredTarget, transitivePackages, configuredTargetValue.getTargetData()
                        )
                    }
                }
                return NonRuleConfiguredTargetValue(configuredTarget, transitivePackages)
            }
        }

        private fun createDetailedExitCode(message: String?): DetailedExitCode {
            return DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setAnalysis(Analysis.newBuilder().setCode(Code.CONFIGURED_VALUE_CREATION_FAILED))
                    .build()
            )
        }
    }
}
