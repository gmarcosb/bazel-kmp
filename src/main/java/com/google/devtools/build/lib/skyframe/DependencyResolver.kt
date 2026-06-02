// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue.configurationId

/**
 * Helper logic for [ConfiguredTargetFunction] and [AspectFunction]: performs the
 * analysis phase through computation of prerequisites.
 * 
 * 
 * For the [ConfiguredTargetFunction] this includes:
 * 
 * 
 *  * getting this target's [Target] and [BuildConfigurationValue]
 *  * getting this target's `select()` keys ([ConfigConditions]), which are used to
 * evaluate all rule attributes with `select()` and determine exact dependencies
 *  * figuring out which toolchains this target needs
 *  * getting the [ConfiguredTargetValue]s of this target's prerequisites (through
 * recursive calls to [ConfiguredTargetFunction]
 * 
 * 
 * 
 * Figuring out which toolchains are needed and computing the [ConfigConditions] is
 * performed by the [DependencyContextProducerWithCompatibilityCheck], which additionally
 * checks for directly incompatible targets using the [ ].
 * 
 * 
 * Cumulatively, this is enough information to run the target's rule logic.
 * 
 * 
 * This class also provides getters for the above data for subsequent analysis logic to use.
 * 
 * 
 * See [ConfiguredTargetFunction] for more review on analysis implementation.
 * 
 * 
 * [AspectFunction] shares the logic computing a target's prerequisites via the [ ][DependencyResolver.computeDependencies].
 */
class DependencyResolver(targetAndConfiguration: TargetAndConfiguration?) {
    /**
     * Memoizies computation steps of [.evaluate] so they do not need to be repeated on `Skyframe` restart.
     */
    class State
    private constructor(
        storeTransitivePackages: Boolean,
        prerequisitePackages: PrerequisitePackageFunction?,
        transitionCollector: TransitionCollector
    ) : SkyKeyComputeState, DependencyContextProducer.ResultSink, DependencyMapProducer.ResultSink {
        /** Must be set before calling [.evaluate].  */
        @kotlin.jvm.JvmField
        var targetAndConfiguration: TargetAndConfiguration? = null

        /** Set once [.dependencyContextProducer] starts.  */
        @kotlin.jvm.JvmField
        @com.google.common.annotations.VisibleForTesting
        var execGroupCollectionBuilder: ExecGroupCollection.Builder? = null

        /**
         * Computes the dependency context, comprised of the unloaded toolchain contexts and the config
         * conditions.
         * 
         * 
         * One of [.dependencyContext] or [.dependencyContextError] will be set upon
         * completion.
         */
        var dependencyContextProducer:  // Non-null when in-flight.
                com.google.devtools.build.skyframe.state.Driver? = null

        @kotlin.jvm.JvmField
        @com.google.common.annotations.VisibleForTesting // package-private
        var dependencyContext: DependencyContext? = null

        var dependencyContextError: DependencyContextError? = null

        /**
         * Computes the configured target dependency map, including aspects if applicable.
         * 
         * 
         * One of [.dependencyMap] or [.dependencyMapError] will be set upon completion.
         */
        // Non-null when in-flight.
        private var dependencyMapProducer: com.google.devtools.build.skyframe.state.Driver? = null

        private var dependencyMap: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>? = null
        private var dependencyMapError: DependencyError? = null

        private var materializerTargets: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>? = null

        val transitiveState: TransitiveDependencyState
        private val transitionCollector: TransitionCollector

        /**
         * Stores events emitted by memoized computations.
         * 
         * 
         * Both the [.computeDependencies] and the [TargetAndConfigurationProducer] may
         * perform Starlark transitions that emit events. Skyframe uses only the events emitted to
         * `env.getListener()` on a call to [.evaluate] that had no missing deps. Since the
         * computations are memoized, they do not re-emit events when Skyframe restarts. Therefore
         * events are stored and replayed when subsequent Skyframe restarts occur.
         */
        val storedEvents: StoredEventHandler = StoredEventHandler()

        internal constructor(
            storeTransitivePackages: Boolean,
            prerequisitePackages: PrerequisitePackageFunction?
        ) : this(storeTransitivePackages, prerequisitePackages, NULL_TRANSITION_COLLECTOR)

        init {
            this.transitiveState =
                TransitiveDependencyState(storeTransitivePackages, prerequisitePackages)
            this.transitionCollector = transitionCollector
        }

        fun transitiveRootCauses(): NestedSetBuilder<com.google.devtools.build.lib.causes.Cause?> {
            return transitiveState.transitiveRootCauses()
        }

        fun transitivePackages(): NestedSet<Package.Metadata?> {
            return transitiveState.transitivePackages()
        }

        public override fun acceptDependencyContext(value: DependencyContext?) {
            this.dependencyContext = value
        }

        public override fun acceptDependencyContextError(error: DependencyContextError?) {
            this.dependencyContextError = error
        }

        public override fun acceptDependencyMap(
            value: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>?
        ) {
            this.dependencyMap = value
        }

        public override fun acceptMaterializerTargets(
            value: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>?
        ) {
            this.materializerTargets = value
        }

        public override fun acceptDependencyMapError(error: DependencyError?) {
            this.dependencyMapError = error
        }

        public override fun acceptDependencyMapError(error: MissingEdgeError) {
            error.emitCausesAndEvents(targetAndConfiguration, transitiveState, storedEvents)
        }

        public override fun acceptTransition(
            kind: DependencyKind?, label: Label?, transition: ConfigurationTransition?
        ) {
            transitionCollector.acceptTransition(kind, label, transition)
        }

        companion object {
            fun createForTesting(targetAndConfiguration: TargetAndConfiguration): State {
                val state: State =
                    com.google.devtools.build.lib.skyframe.DependencyResolver.State( /* storeTransitivePackages= */false,  /* prerequisitePackages= */
                        PrerequisitePackageFunction { p -> null })
                state.targetAndConfiguration = targetAndConfiguration
                return state
            }

            fun createForCquery(
                targetAndConfiguration: TargetAndConfiguration, transitionCollector: TransitionCollector
            ): State {
                val state: State =
                    com.google.devtools.build.lib.skyframe.DependencyResolver.State( /* storeTransitivePackages= */
                        false,  /* prerequisitePackages= */
                        PrerequisitePackageFunction { p -> null },
                        transitionCollector
                    )
                state.targetAndConfiguration = targetAndConfiguration
                return state
            }
        }
    }

    /** Lets calling logic provide a semaphore to restrict the number of concurrent analysis calls.  */
    interface SemaphoreAcquirer {
        @Throws(java.lang.InterruptedException::class)
        fun acquireSemaphore()
    }

    private val targetAndConfiguration: TargetAndConfiguration
    private var depValueMap: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>? = null

    private var materializerTargets: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>? = null

    private var configConditions: ConfigConditions? = null
    private var platformInfo: PlatformInfo? = null
    private var unloadedToolchainContexts: ToolchainCollection<UnloadedToolchainContext?>? = null

    init {
        this.targetAndConfiguration =
            com.google.common.base.Preconditions.checkNotNull<TargetAndConfiguration>(targetAndConfiguration)
    }

    /** Return this target's [TargetAndConfiguration].  */
    fun getTargetAndConfiguration(): TargetAndConfiguration {
        return targetAndConfiguration
    }

    /**
     * Return this target's fully resolved dependencies.
     * 
     * 
     * [.evaluate] must be called before this info is available.
     */
    fun getDepValueMap(): OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?> {
        return com.google.common.base.Preconditions.checkNotNull<OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>>(
            depValueMap
        )
    }

    fun getMaterializerTargets(): OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>? {
        return materializerTargets
    }

    /**
     * Return the keys in this target's `select()`s.
     * 
     * 
     * [.evaluate] must be called before this info is available.
     */
    fun getConfigConditions(): ConfigConditions? {
        return com.google.common.base.Preconditions.checkNotNull<ConfigConditions?>(configConditions)
    }

    /**
     * Return this target's platform metadata, or null if it doesn't use platforms.
     * 
     * 
     * [.evaluate] must be called before this info is available.
     */
    fun getPlatformInfo(): PlatformInfo? {
        return platformInfo
    }

    /**
     * Return this target's toolchain requirements, or null if it doesn't use toolchains.
     * 
     * 
     * [.evaluate] must be called before this info is available.
     */
    @com.google.common.annotations.VisibleForTesting
    fun getUnloadedToolchainContexts(): ToolchainCollection<UnloadedToolchainContext?>? {
        return unloadedToolchainContexts
    }

    /**
     * Runs the analysis phase for this target through prerequisite evaluation.
     * 
     * 
     * See [DependencyResolver] javadoc for details.
     * 
     * 
     * This is the main entry point to [DependencyResolver]. This method runs its share of
     * the analysis phase, after which all the data is computes is accessible to calling code through
     * related getters.
     * 
     * 
     * After instantiating this class, this method should be called once. It returns false when any
     * Skyframe dependencies need to be evaluated, else true.
     */
    @Throws(
        ReportedException::class,
        UnreportedException::class,
        IncompatibleTargetException::class,
        java.lang.InterruptedException::class
    )
    fun evaluate(
        state: State,
        configuredTargetKey: ConfiguredTargetKey,
        ruleClassProvider: RuleClassProvider,
        transitionCache: StarlarkTransitionCache?,
        semaphoreLocker: SemaphoreAcquirer,
        env: LookupEnvironment,
        listener: ExtendedEventHandler
    ): Boolean {
        // TODO(janakr): this call may tie up this thread indefinitely, reducing the parallelism of
        //  Skyframe. This is a strict improvement over the prior state of the code, in which we ran
        //  with #processors threads, but ideally we would call #tryAcquire here, and if we failed,
        //  would exit this SkyFunction and restart it when permits were available.
        semaphoreLocker.acquireSemaphore()
        try {
            val dependencyContext: DependencyContext? =
                getDependencyContext(state, configuredTargetKey, ruleClassProvider, env, listener)
            if (dependencyContext == null) {
                return false
            }
            this.unloadedToolchainContexts = dependencyContext.unloadedToolchainContexts()
            this.platformInfo =
                if (unloadedToolchainContexts != null) unloadedToolchainContexts.getTargetPlatform() else null
            this.configConditions = dependencyContext.configConditions()

            // TODO(ulfjack): ConfiguredAttributeMapper (indirectly used from computeDependencies) isn't
            // safe to use if there are missing config conditions, so we stop here, but only if there are
            // config conditions - though note that we can't check if configConditions is non-empty - it
            // may be empty for other reasons. It would be better to continue here so that we can collect
            // more root causes during computeDependencies.
            // Note that this doesn't apply to AspectFunction, because aspects can't have configurable
            // attributes.
            val transitiveRootCauses: NestedSetBuilder<com.google.devtools.build.lib.causes.Cause?> =
                state.transitiveRootCauses()
            if (!transitiveRootCauses.isEmpty()
                && configConditions != ConfigConditions.EMPTY
            ) {
                val causes: NestedSet<com.google.devtools.build.lib.causes.Cause?> = transitiveRootCauses.build()
                listener.handle(
                    com.google.devtools.build.lib.events.Event.error(
                        targetAndConfiguration.getTarget().getLocation(),
                        "Cannot compute config conditions"
                    )
                )
                throw ReportedException(
                    ConfiguredValueCreationException(
                        targetAndConfiguration.getTarget(),
                        configurationId(targetAndConfiguration.getConfiguration()),
                        "Cannot compute config conditions",
                        causes,
                        getPrioritizedDetailedExitCode(causes)
                    )
                )
            }

            val starlarkExecTransition: java.util.Optional<StarlarkAttributeTransitionProvider?>? =
                StarlarkExecTransitionLoader.loadStarlarkExecTransition(
                    if (targetAndConfiguration.getConfiguration() == null)
                        null
                    else
                        targetAndConfiguration.getConfiguration().getOptions(),
                    { bzlKey -> env.getValueOrThrow<E?>(bzlKey, BzlLoadFailedException::class.java) as BzlLoadValue? })
            if (starlarkExecTransition == null) {
                return false
            }

            var loadExecAspectsKey: LoadAspectsKey? = null
            if (configuredTargetKey.getConfigurationKey() != null
                && !configuredTargetKey
                    .getConfigurationKey()
                    .getOptions()
                    .get(CoreOptions::class.java)
                    .getExecAspects()
                    .isEmpty()
            ) {
                val aspectClasses: com.google.common.collect.ImmutableList<AspectClass?> =
                    createAspectClasses(
                        configuredTargetKey
                            .getConfigurationKey()
                            .getOptions()
                            .get(CoreOptions::class.java)
                            .getExecAspects()
                    )
                if (!aspectClasses.isEmpty()) {
                    loadExecAspectsKey =
                        LoadAspectsKey.create(
                            aspectClasses,  /* topLevelAspectsParameters= */
                            com.google.common.collect.ImmutableMap.of<K?, V?>()
                        )
                }
            }
            // Calculate the dependencies of this target.
            depValueMap =
                computeDependencies(
                    state,
                    configuredTargetKey,  /* aspects= */
                    com.google.common.collect.ImmutableList.of<Aspect?>(),
                    loadExecAspectsKey,
                    transitionCache,
                    starlarkExecTransition.orElse(null),
                    env,
                    listener,  /* baseTargetPrerequisitesSupplier= */
                    null,  /* baseTargetUnloadedToolchainContexts= */
                    null
                )
            if (!transitiveRootCauses.isEmpty()) {
                val causes: NestedSet<com.google.devtools.build.lib.causes.Cause?> = transitiveRootCauses.build()
                // TODO(bazel-team): consider reporting the error in this class vs. exporting it for
                // BuildTool to handle. Calling code needs to be untangled for that to work and pass tests.
                throw UnreportedException(
                    ConfiguredValueCreationException(
                        targetAndConfiguration.getTarget(),
                        configurationId(targetAndConfiguration.getConfiguration()),
                        "Analysis failed",
                        causes,
                        getPrioritizedDetailedExitCode(causes)
                    )
                )
            }
            if (depValueMap == null) {
                return false
            }

            this.materializerTargets = state.materializerTargets
        } catch (e: DependencyEvaluationException) {
            // We handle exceptions in a dedicated method to keep this method concise and readable.
            handleException(listener, targetAndConfiguration.getTarget(), e)
        } catch (e: ConfiguredValueCreationException) {
            handleException(listener, targetAndConfiguration.getTarget(), e)
        } catch (e: AspectCreationException) {
            handleException(listener, targetAndConfiguration.getTarget(), e)
        } catch (e: StarlarkExecTransitionLoadingException) {
            handleException(listener, targetAndConfiguration.getTarget(), e)
        } catch (e: ToolchainException) {
            handleException(listener, targetAndConfiguration.getTarget(), e)
        } catch (e: ExecGroupCollection.InvalidExecGroupException) {
            handleException(listener, targetAndConfiguration.getTarget(), e)
        }
        return true
    }

    /**
     * Handles all exceptions that [.evaluate] may throw.
     * 
     * 
     * This is its own method because there's a lot of logic here and when directly inlined it
     * makes it harder to follow the calling method's control flow.
     */
    @Throws(ReportedException::class)
    private fun handleException(listener: ExtendedEventHandler, target: Target, untyped: java.lang.Exception) {
        throw when (untyped) {
            -> {
                var errorMessage: String? = e.getMessage()
                if (!e.depReportedOwnError()) {
                    listener.handle(com.google.devtools.build.lib.events.Event.error(e.getLocation(), e.getMessage()))
                }

                var cvce: ConfiguredValueCreationException? = null
                if (e.getCause() is ConfiguredValueCreationException) {
                    cvce = e.getCause() as ConfiguredValueCreationException?

                    // Check if this is caused by an unresolved toolchain, and report it as such.
                    if (unloadedToolchainContexts != null) {
                        val requiredToolchains: com.google.common.collect.ImmutableSet<Label?> =
                            unloadedToolchainContexts.getResolvedToolchains()
                        val toolchainDependencyErrors: com.google.common.collect.ImmutableSet<Label?> =
                            cvce.getRootCauses().toList().stream()
                                .map(com.google.devtools.build.lib.causes.Cause::label)
                                .filter(requiredToolchains::contains)
                                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<E?>())

                        if (!toolchainDependencyErrors.isEmpty()) {
                            errorMessage = "errors encountered resolving toolchains for " + target.getLabel()
                            listener.handle(
                                com.google.devtools.build.lib.events.Event.error(
                                    target.getLocation(),
                                    errorMessage
                                )
                            )
                        }
                    }
                }

                ReportedException(
                    if (cvce != null)
                        cvce
                    else
                        ConfiguredValueCreationException(
                            targetAndConfiguration.getTarget(),
                            configurationId(targetAndConfiguration.getConfiguration()),
                            errorMessage,
                            null,
                            e.getDetailedExitCode()
                        )
                )
            }

            -> {
                if (!e.getMessage().isEmpty()) {
                    // Report the error to the user.
                    listener.handle(com.google.devtools.build.lib.events.Event.error(e.getLocation(), e.getMessage()))
                }
                ReportedException(e)
            }

            -> {
                if (!e.getMessage().isEmpty()) {
                    // Report the error to the user.
                    listener.handle(com.google.devtools.build.lib.events.Event.error(null, e.getMessage()))
                }
                ReportedException(
                    ConfiguredValueCreationException(
                        targetAndConfiguration.getTarget(),
                        configurationId(targetAndConfiguration.getConfiguration()),
                        e.getMessage(),
                        e.getCauses(),
                        e.getDetailedExitCode()
                    )
                )
            }

            -> {
                val cvce: ConfiguredValueCreationException =
                    e.asConfiguredValueCreationException(targetAndConfiguration)
                listener.handle(
                    com.google.devtools.build.lib.events.Event.error(
                        target.getLocation(),
                        cvce.getMessage()
                    )
                )
                ReportedException(cvce)
            }

            -> {
                if (!e.getMessage().isEmpty()) {
                    // Report the error to the user.
                    listener.handle(com.google.devtools.build.lib.events.Event.error(null, e.getMessage()))
                }
                ReportedException(
                    ConfiguredValueCreationException(
                        targetAndConfiguration.getTarget(),
                        configurationId(targetAndConfiguration.getConfiguration()),
                        e.getMessage(),  /* rootCauses= */
                        null,  /* detailedExitCode= */
                        null
                    )
                )
            }

            -> {
                listener.handle(com.google.devtools.build.lib.events.Event.error(target.getLocation(), e.getMessage()))
                ReportedException(
                    ConfiguredValueCreationException(
                        targetAndConfiguration.getTarget(),
                        configurationId(targetAndConfiguration.getConfiguration()),
                        e.getMessage(),  /* rootCauses= */
                        null,  /* detailedExitCode= */
                        null
                    )
                )
            }

            else -> throw java.lang.IllegalStateException(
                "unexpected exception with no appropriate handler", untyped
            )
        }
    }

    @Throws(AspectCreationException::class)
    private fun createAspectClasses(aspectNames: MutableList<String?>): com.google.common.collect.ImmutableList<AspectClass?> {
        val aspectClassesBuilder: com.google.common.collect.ImmutableList.Builder<AspectClass?> =
            com.google.common.collect.ImmutableList.builder<AspectClass?>()
        for (aspect in aspectNames) {
            try {
                aspectClassesBuilder.add(StarlarkAspectClass.getAspectClassFromName(aspect))
            } catch (e: StarlarkAspectClass.AspectClassCreationException) {
                throw AspectCreationException(e.getMessage(), getTargetAndConfiguration().getLabel())
            }
        }
        return aspectClassesBuilder.build()
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        @com.google.common.annotations.VisibleForTesting
        @Throws(
            java.lang.InterruptedException::class,
            ToolchainException::class,
            ConfiguredValueCreationException::class,
            IncompatibleTargetException::class,
            DependencyEvaluationException::class,
            ExecGroupCollection.InvalidExecGroupException::class
        )  // Null when a Skyframe restart is needed.
        fun getDependencyContext(
            state: State,
            configuredTargetKey: ConfiguredTargetKey,
            ruleClassProvider: RuleClassProvider,
            env: LookupEnvironment?,
            listener: ExtendedEventHandler
        ): DependencyContext? {
            if (state.dependencyContext != null) {
                return state.dependencyContext
            }
            if (state.dependencyContextProducer == null) {
                val targetAndConfiguration: TargetAndConfiguration = state.targetAndConfiguration
                val unloadedToolchainContextsInputs: UnloadedToolchainContextsInputs? =
                    getUnloadedToolchainContextsInputs(
                        targetAndConfiguration,
                        configuredTargetKey.getExecutionPlatformLabel(),
                        ruleClassProvider,
                        listener
                    )
                state.execGroupCollectionBuilder = unloadedToolchainContextsInputs
                state.dependencyContextProducer =
                    com.google.devtools.build.skyframe.state.Driver(
                        DependencyContextProducerWithCompatibilityCheck(
                            targetAndConfiguration,
                            configuredTargetKey,
                            unloadedToolchainContextsInputs,
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
                    INCOMPATIBLE_TARGET -> throw error.incompatibleTarget()
                    VALIDATION -> {
                        val validationException: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                            error.validation()
                        val targetAndConfiguration: TargetAndConfiguration = state.targetAndConfiguration
                        throw handleDependencyRootCauseError(
                            targetAndConfiguration,
                            targetAndConfiguration.getTarget().getLocation(),
                            validationException.getMessage(),
                            listener
                        )
                    }
                }
                throw java.lang.IllegalStateException("unreachable")
            }

            return state.dependencyContext // Null if not yet done.
        }

        /**
         * Computes the direct dependencies of a node in the configured target graph (a configured target
         * or an aspects).
         * 
         * 
         * Returns null if Skyframe hasn't evaluated the required dependencies yet. In this case, the
         * caller should also return null to Skyframe.
         * 
         * 
         * REQUIRES: `state.dependencyContext` is populated.
         * 
         * @param state the compute state
         * @param loadExecAspectsKey key associated with the aspects passed to the --exec_aspects flag
         * that are attached to exec-configured targets.
         * @param configuredTargetKey key associated with `state.targetAndConfiguration`'s
         * configuration
         * @param starlarkTransitionProvider the Starlark transition that implements exec transition
         * logic, if specified. Null if Bazel uses native logic.
         * @param env the Skyframe environment
         * @param baseTargetPrerequisitesSupplier not null only in case of aspect evaluation. It provides
         * a way to get the [ConfiguredTargetValue]s and [BuildConfigurationValue]s of the
         * underlying target dependencies without creating a dependency edge from the aspect to them.
         * @param baseTargetUnloadedToolchainContexts not null only in case of aspect evaluation. It's the
         * [UnloadedToolchainContext]s of the underlying target to support aspects toolchains
         * propagation.
         */
        // TODO(b/213351014): Make the control flow of this helper function more readable. This will
        //   involve making a corresponding change to State to match the control flow.
        @Throws(
            DependencyEvaluationException::class,
            ConfiguredValueCreationException::class,
            AspectCreationException::class,
            java.lang.InterruptedException::class
        )
        fun computeDependencies(
            state: State,
            configuredTargetKey: ConfiguredTargetKey?,
            aspects: com.google.common.collect.ImmutableList<Aspect?>?,
            loadExecAspectsKey: LoadAspectsKey?,
            transitionCache: StarlarkTransitionCache?,
            starlarkTransitionProvider: StarlarkAttributeTransitionProvider?,
            env: LookupEnvironment?,
            listener: ExtendedEventHandler,
            baseTargetPrerequisitesSupplier: BaseTargetPrerequisitesSupplier?,
            baseTargetUnloadedToolchainContexts: ToolchainCollection<UnloadedToolchainContext?>?
        ): OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>? {
            // Replays stored events unless a Skyframe restart is immediately needed and the events would
            // be unused anyway.
            var shouldReplayStoredEvents = true
            try {
                if (state.dependencyMap != null) {
                    return state.dependencyMap
                }
                if (state.dependencyMapProducer == null) {
                    val ctgValue: TargetAndConfiguration = state.targetAndConfiguration
                    val dependencyContext: DependencyContext? = state.dependencyContext
                    val toolchainContexts: ToolchainCollection<ToolchainContext?>? =
                        dependencyContext.toolchainContexts()
                    val dependencyLabels: DependencyResolutionHelpers.DependencyLabels
                    try {
                        dependencyLabels =
                            DependencyResolutionHelpers.computeDependencyLabels(
                                ctgValue,
                                aspects,
                                dependencyContext.configConditions().asProviders(),
                                toolchainContexts,
                                baseTargetUnloadedToolchainContexts
                            )
                    } catch (e: DependencyResolutionHelpers.Failure) {
                        throw handleDependencyRootCauseError(ctgValue, e.getLocation(), e.getMessage(), listener)
                    }
                    state.dependencyMapProducer =
                        com.google.devtools.build.skyframe.state.Driver(
                            DependencyMapProducer(
                                PrerequisiteParameters(
                                    configuredTargetKey,
                                    ctgValue.getTarget(),
                                    aspects,
                                    loadExecAspectsKey,
                                    starlarkTransitionProvider,
                                    transitionCache,
                                    toolchainContexts,
                                    dependencyLabels.attributeMap(),
                                    state.transitiveState,
                                    state.storedEvents,
                                    baseTargetPrerequisitesSupplier,
                                    baseTargetUnloadedToolchainContexts
                                ),
                                dependencyLabels.labels(),
                                state as DependencyMapProducer.ResultSink
                            )
                        )
                }
                try {
                    if (state.dependencyMapProducer.drive(env)) {
                        state.dependencyMapProducer = null
                    }
                } catch (e: java.lang.InterruptedException) {
                    // In practice, this comes from resolveConfigurations: other InterruptedExceptions are
                    // declared for Skyframe value retrievals, which don't throw in reality.
                    if (state.transitiveState.hasRootCause()) {
                        // TODO: b/418000794 - remove this logging once the underlying bug is resolved
                        if (state.dependencyMapError != null) {
                            logger.atWarning().log(
                                "There was an error %s but signaling missing deps. This could trigger a crash.",
                                state.dependencyMapError
                            )
                        } else {
                            logger.atWarning().atMostEvery(5, TimeUnit.SECONDS).log(
                                "Dependency resolution was interrupted."
                            )
                        }
                        // Allow caller to throw, don't prioritize interrupt: we may be error bubbling.
                        java.lang.Thread.currentThread().interrupt()
                        return null
                    }
                    throw e
                }

                val error: DependencyError? = state.dependencyMapError
                if (error != null) {
                    val ctgValue: TargetAndConfiguration = state.targetAndConfiguration
                    when (error.kind()) {
                        DEPENDENCY_TRANSITION -> {
                            val e: TransitionException = error.dependencyTransition()
                            throw ConfiguredValueCreationException(ctgValue.getTarget(), e.getMessage())
                        }

                        DEPENDENCY_OPTIONS_PARSING -> {
                            val e: com.google.devtools.common.options.OptionsParsingException =
                                error.dependencyOptionsParsing()
                            throw ConfiguredValueCreationException(ctgValue.getTarget(), e.getMessage())
                        }

                        MATERIALIZER -> {
                            val e: MaterializerException = error.materializer()
                            throw ConfiguredValueCreationException(ctgValue.getTarget(), e.getMessage())
                        }

                        INVALID_VISIBILITY -> {
                            val e: InvalidVisibilityDependencyException = error.invalidVisibility()
                            throw handleDependencyRootCauseError(
                                ctgValue,
                                ctgValue.getTarget().getLocation(),
                                java.lang.String.format("Label '%s' does not refer to a package group.", e.label()),
                                listener
                            )
                        }

                        ASPECT_EVALUATION -> throw error.aspectEvaluation()
                        ASPECT_CREATION -> throw error.aspectCreation()
                        PLATFORM_MAPPING -> {
                            val platformMappingException: PlatformMappingException = error.platformMapping()
                            throw ConfiguredValueCreationException(
                                ctgValue.getTarget(), platformMappingException.getMessage()
                            )
                        }

                        INVALID_PLATFORM -> {
                            val invalidPlatformException: InvalidPlatformException = error.invalidPlatform()
                            throw ConfiguredValueCreationException(
                                ctgValue.getTarget(), invalidPlatformException.getMessage()
                            )
                        }

                        TRANSITION_CREATION -> {
                            val transitionCreationException: TransitionCreationException = error.transitionCreation()
                            throw ConfiguredValueCreationException(
                                ctgValue.getTarget(), transitionCreationException.getMessage()
                            )
                        }

                        BUILD_OPTIONS_SCOPE -> {
                            val buildOptionsScopeFunctionException: BuildOptionsScopeFunctionException =
                                error.buildOptionsScope()
                            throw ConfiguredValueCreationException(
                                ctgValue.getTarget(), buildOptionsScopeFunctionException.getMessage()
                            )
                        }
                    }
                }
                if (!state.transitiveState.hasRootCause() && state.dependencyMap == null) {
                    shouldReplayStoredEvents = false // Skyframe restart is needed.
                }
                return state.dependencyMap
            } finally {
                if (shouldReplayStoredEvents) {
                    state.storedEvents.replayOn(listener)
                }
            }
        }

        @Throws(java.lang.InterruptedException::class, ExecGroupCollection.InvalidExecGroupException::class)
        fun getUnloadedToolchainContextsInputs(
            targetAndConfiguration: TargetAndConfiguration,
            parentExecutionPlatformLabel: Label?,
            ruleClassProvider: RuleClassProvider,
            listener: ExtendedEventHandler?
        ): UnloadedToolchainContextsInputs? {
            if (targetAndConfiguration.getConfiguration() == null) {
                return UnloadedToolchainContextsInputs.empty()
            }
            return ToolchainContextUtil.getUnloadedToolchainContextsInputs(
                targetAndConfiguration.getTarget(),
                targetAndConfiguration.getConfiguration().getOptions().get(CoreOptions::class.java),
                targetAndConfiguration.getConfiguration().getFragment(PlatformConfiguration::class.java),
                parentExecutionPlatformLabel,
                computeToolchainConfigurationKey(
                    targetAndConfiguration.getConfiguration(),
                    (ruleClassProvider as ConfiguredRuleClassProvider)
                        .getToolchainTaggedTrimmingTransition(),
                    listener
                )
            )
        }

        @Throws(java.lang.InterruptedException::class)
        private fun computeToolchainConfigurationKey(
            configuration: BuildConfigurationValue,
            toolchainTaggedTrimmingTransition: PatchTransition,
            listener: ExtendedEventHandler?
        ): BuildConfigurationKey {
            // The toolchain context's options are the parent rule's options with manual trimming
            // auto-applied. This means toolchains don't inherit feature flags. This helps build
            // performance: if the toolchain context had the exact same configuration of its parent and that
            // included feature flags, all the toolchain's dependencies would apply this transition
            // individually. That creates a lot more potentially expensive applications of that transition
            // (especially since manual trimming applies to every configured target in the build).
            //
            // In other words: without this modification:
            // parent rule -> toolchain context -> toolchain
            //     -> toolchain dep 1 # applies manual trimming to remove feature flags
            //     -> toolchain dep 2 # applies manual trimming to remove feature flags
            //     ...
            //
            // With this modification:
            // parent rule -> toolchain context # applies manual trimming to remove feature flags
            //     -> toolchain
            //         -> toolchain dep 1
            //         -> toolchain dep 2
            //         ...
            //
            // None of this has any effect on rules that don't utilize manual trimming.
            val toolchainOptions: BuildOptions? =
                toolchainTaggedTrimmingTransition.patch(
                    BuildOptionsView(
                        configuration.getOptions(),
                        toolchainTaggedTrimmingTransition.requiresOptionFragments()
                    ),
                    listener
                )
            return BuildConfigurationKey.create(toolchainOptions)
        }

        private fun handleDependencyRootCauseError(
            targetAndConfiguration: TargetAndConfiguration,
            location: net.starlark.java.syntax.Location?,
            message: String?,
            listener: ExtendedEventHandler
        ): DependencyEvaluationException {
            val configuration: BuildConfigurationValue? = targetAndConfiguration.getConfiguration()
            val label: Label? = targetAndConfiguration.getLabel()
            listener.post(AnalysisRootCauseEvent.withConfigurationValue(configuration, label, message))
            val cause: com.google.devtools.build.lib.causes.Cause =
                AnalysisFailedCause(
                    targetAndConfiguration.getLabel(),
                    configurationIdMessage(targetAndConfiguration.getConfiguration()),
                    createDetailedExitCode(message)
                )
            return DependencyEvaluationException(
                ConfiguredValueCreationException(
                    location,
                    message,
                    label,
                    configurationId(configuration),
                    NestedSetBuilder.create(Order.STABLE_ORDER, cause),
                    cause.detailedExitCode
                ),  // These errors occur in dependency resolution, which is attached to the current target.
                // i.e. no dependent ConfiguredTargetFunction call happens to report its own error.
                /* depReportedOwnError= */
                false
            )
        }

        fun getPrioritizedDetailedExitCode(causes: NestedSet<com.google.devtools.build.lib.causes.Cause?>): DetailedExitCode? {
            var prioritizedDetailedExitCode: DetailedExitCode? = null
            for (c in causes.toList()) {
                prioritizedDetailedExitCode =
                    DetailedExitCodeComparator.chooseMoreImportantWithFirstIfTie(
                        prioritizedDetailedExitCode, c.detailedExitCode
                    )
            }
            return prioritizedDetailedExitCode
        }
    }
}
