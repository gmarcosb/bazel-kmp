// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.producers.TargetAndConfigurationProducer.configurationIdMessage

/**
 * SkyFunction to run the aspects path obtained from top-level aspects on the list of top-level
 * targets.
 * 
 * 
 * Used for loading top-level aspects, filtering them based on their required providers, and
 * computing the relationship between top-level aspects.
 * 
 * 
 * At top level, in [com.google.devtools.build.lib.analysis.BuildView], we cannot invoke
 * two SkyFunctions one after another, so BuildView calls this function to do the work.
 */
internal class ToplevelStarlarkAspectFunction(
    buildViewProvider: BuildViewProvider,
    ruleClassProvider: RuleClassProvider?,
    storeTransitivePackages: Boolean,
    prerequisitePackages: PrerequisitePackageFunction?
) : SkyFunction {
    private val buildViewProvider: BuildViewProvider
    private val ruleClassProvider: RuleClassProvider?
    private val storeTransitivePackages: Boolean

    // Do not use this field for package retrieval of the base configured target since it will cause
    // incrementality errors because an essential dependency edge would not be registered.
    private val prerequisitePackages: PrerequisitePackageFunction?

    init {
        this.buildViewProvider = buildViewProvider
        this.ruleClassProvider = ruleClassProvider
        this.storeTransitivePackages = storeTransitivePackages
        this.prerequisitePackages = prerequisitePackages
    }

    @Throws(
        java.lang.InterruptedException::class,
        TopLevelStarlarkAspectFunctionException::class,
        DependencyException::class,
        ReportedException::class
    )
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val topLevelAspectsKey: TopLevelAspectsKey = skyKey.argument() as TopLevelAspectsKey

        val loadAspectsKey: LoadAspectsKey? =
            LoadAspectsKey.create(
                topLevelAspectsKey.getTopLevelAspectsClasses(),
                topLevelAspectsKey.getTopLevelAspectsParameters()
            )
        val packageIdentifier: PackageIdentifier? =
            topLevelAspectsKey.getBaseConfiguredTargetKey().getLabel().getPackageIdentifier()

        val initialLookupResult: SkyframeLookupResult =
            env.getValuesAndExceptions(
                com.google.common.collect.ImmutableList.of<SkyKey?>(
                    loadAspectsKey,
                    packageIdentifier
                )
            )

        val loadAspectsValue: LoadAspectsValue? = initialLookupResult.get(loadAspectsKey) as LoadAspectsValue?
        if (loadAspectsValue == null) {
            return null // aspects are not ready
        }

        val packageValue: PackageValue? = initialLookupResult.get(packageIdentifier) as PackageValue?
        if (packageValue == null) {
            return null // package is not ready
        }
        val target =
            getTarget(packageValue, topLevelAspectsKey.getBaseConfiguredTargetKey().getLabel())

        val state: State = env.getState<State>(java.util.function.Supplier {
            com.google.devtools.build.lib.skyframe.ToplevelStarlarkAspectFunction.State(
                storeTransitivePackages,
                prerequisitePackages
            )
        })

        // Configuration of top level target could change during the analysis phase with rule
        // transitions. In order not to wait for the complete configuration of the assigned target,
        // {@link RuleTransitionApplier} is used to apply potentially requested rule transitions
        // upfront. Configuration can be `null` if the target is not configurable, in which case the
        // Skyframe restart is needed.
        val baseConfiguredTargetKey: ConfiguredTargetKey? =
            getConfiguredTargetKey(state, topLevelAspectsKey.getBaseConfiguredTargetKey(), target, env)
        if (baseConfiguredTargetKey == null) {
            return null
        }

        val aspectsKeys: com.google.common.collect.ImmutableList<AspectKey?>? =
            createAspectsKeys(
                state, target, loadAspectsValue.getAspects(), baseConfiguredTargetKey, env
            )
        if (aspectsKeys == null) {
            return null // alias target needs to be resolved
        }

        val result: SkyframeLookupResult = env.getValuesAndExceptions(aspectsKeys)
        if (env.valuesMissing()) {
            return null // some aspects keys are not evaluated
        }
        val valuesMap: com.google.common.collect.ImmutableMap.Builder<AspectKey?, AspectValue?> =
            com.google.common.collect.ImmutableMap.builderWithExpectedSize<AspectKey?, AspectValue?>(aspectsKeys.size())
        for (aspectKey in aspectsKeys) {
            try {
                val value: AspectValue? =
                    result.getOrThrow<E?>(aspectKey, ActionConflictException::class.java) as AspectValue?
                if (value == null) {
                    return null
                }
                valuesMap.put(aspectKey, value)
            } catch (e: ActionConflictException) {
                // Required in case of skymeld: the AspectKey isn't accessible from the BuildDriverKey.
                throw TopLevelStarlarkAspectFunctionException(
                    ActionConflictException.withAspectKeyInfo(e, aspectKey)
                )
            }
        }
        return TopLevelAspectsValue(valuesMap.buildOrThrow())
    }

    /**
     * Returns `` `baseConfiguredTargetKey` `` if the configuration didn't change with potential
     * transitions ([IdempotencyState.IDENTITY]). Otherwise, returns a new [ ] with the new configuration (`` `buildConfigurationKey` ``).
     */
    private fun createConfiguredTargetKey(
        buildConfigurationKey: BuildConfigurationKey?,
        baseConfiguredTargetKey: ConfiguredTargetKey,
        idempotencyState: IdempotencyState?
    ): ConfiguredTargetKey? {
        if (idempotencyState === IdempotencyState.IDENTITY) {
            return baseConfiguredTargetKey
        }
        val keyBuilder: ConfiguredTargetKey.Builder =
            ConfiguredTargetKey.builder()
                .setLabel(baseConfiguredTargetKey.getLabel())
                .setConfigurationKey(buildConfigurationKey)

        if (idempotencyState === IdempotencyState.NON_IDEMPOTENT) {
            // The transition was not idempotent. Explicitly informs the delegate to avoid applying a
            // rule transition.
            keyBuilder.setShouldApplyRuleTransition(false)
        }
        return keyBuilder.build()
    }

    /**
     * Computes configuration of the target by driving the state machine of [ ].
     */
    @Throws(java.lang.InterruptedException::class)
    fun computeConfiguration(
        env: SkyFunction.Environment?,
        state: State,
        baseConfiguredTargetKey: ConfiguredTargetKey?,
        target: Target?,
        ruleClassProvider: ConfiguredRuleClassProvider,
        buildViewProvider: BuildViewProvider
    ) {
        if (state.myProducer == null) {
            state.myProducer =
                com.google.devtools.build.skyframe.state.Driver(
                    TransitionedBaseConfigurationProducer(
                        baseConfiguredTargetKey,
                        ruleClassProvider.getTrimmingTransitionFactory(),
                        ruleClassProvider.getToolchainTaggedTrimmingTransition(),
                        buildViewProvider.getSkyframeBuildView().getStarlarkTransitionCache(),
                        target,
                        state
                    )
                )
        }
        if (state.myProducer.drive(env)) {
            state.myProducer = null
        }
    }

    // Computes {@link BuildConfigurationKey} by driving the state machine of {@link
    // RuleTransitionApplier} and returns the new {@link ConfiguredTargetKey} with the obtained build
    // configuration. In case configuration key is still not ready, returns `null` since Skyframe
    // restart is needed.
    @Throws(java.lang.InterruptedException::class, ReportedException::class)
    private fun getConfiguredTargetKey(
        state: State, baseConfiguredTargetKey: ConfiguredTargetKey, target: Target, env: SkyFunction.Environment
    ): ConfiguredTargetKey? {
        if (!target.isConfigurable()) {
            return baseConfiguredTargetKey.toBuilder().setConfigurationKey(null).build()
        }

        computeConfiguration(
            env,
            state,
            baseConfiguredTargetKey,
            target,
            ruleClassProvider as ConfiguredRuleClassProvider?,
            buildViewProvider
        )

        if (state.hasError()) {
            val exception: ConfiguredValueCreationException =
                state.createException(baseConfiguredTargetKey, target)
            if (!exception.getMessage().isEmpty()) {
                // Report the error to the user.
                env.getListener().handle(
                    com.google.devtools.build.lib.events.Event.error(
                        exception.getLocation(),
                        exception.getMessage()
                    )
                )
            }
            throw ReportedException(exception)
        }

        if (state.configurationKey == null) {
            // Skyframe restart is needed since configuration is still not ready.
            return null
        }
        return createConfiguredTargetKey(
            state.configurationKey, baseConfiguredTargetKey, state.idempotencyState
        )
    }

    private class TopLevelStarlarkAspectFunctionException : SkyFunctionException {
        protected constructor(cause: ActionConflictException?) : super(cause, Transience.PERSISTENT)

        constructor(cause: TopLevelAspectsDetailsBuildFailedException?) : super(cause, Transience.PERSISTENT)
    }

    /**
     * [StateMachine] which drives [RuleTransitionApplier] to apply potentially requested
     * rule transitions and accepts the configuration key in [State].
     */
    private class TransitionedBaseConfigurationProducer
        (
        preRuleTransitionKey: ConfiguredTargetKey?,
        trimmingTransitionFactory: TransitionFactory<RuleTransitionData?>?,
        toolchainTaggedTrimmingTransition: PatchTransition?,
        transitionCache: StarlarkTransitionCache?,
        target: Target?,
        state: State
    ) : StateMachine, TargetAndConfigurationData {
        var preRuleTransitionKey: ConfiguredTargetKey?
        var trimmingTransitionFactory: TransitionFactory<RuleTransitionData?>?
        var toolchainTaggedTrimmingTransition: PatchTransition?
        var transitionCache: StarlarkTransitionCache?
        var target: Target?
        var state: State

        init {
            this.preRuleTransitionKey = preRuleTransitionKey
            this.trimmingTransitionFactory = trimmingTransitionFactory
            this.toolchainTaggedTrimmingTransition = toolchainTaggedTrimmingTransition
            this.transitionCache = transitionCache
            this.target = target
            this.state = state
        }

        override fun step(tasks: com.google.devtools.build.skyframe.state.StateMachine.Tasks?): StateMachine {
            return RuleTransitionApplier(
                target,
                this as TargetAndConfigurationData,
                state as RuleTransitionApplier.ResultSink?,
                state.storedEvents,  /* runAfter= */
                StateMachine.DONE
            )
        }

        public override fun getPreRuleTransitionKey(): ConfiguredTargetKey? {
            return preRuleTransitionKey
        }

        public override fun getTrimmingTransitionFactory(): TransitionFactory<RuleTransitionData?>? {
            return trimmingTransitionFactory
        }

        public override fun getToolchainTaggedTrimmingTransition(): PatchTransition? {
            return toolchainTaggedTrimmingTransition
        }

        public override fun getTransitionCache(): StarlarkTransitionCache? {
            return transitionCache
        }

        val transitiveState: TransitiveDependencyState
            get() = state.transitiveState
    }

    /**
     * State which drives a [TransitionedBaseConfigurationProducer] and accepts the
     * configuration when complete.
     */
    class State internal constructor(
        storeTransitivePackages: Boolean,
        prerequisitePackages: PrerequisitePackageFunction?
    ) : SkyKeyComputeState, RuleTransitionApplier.ResultSink {
        // Non-null while in-flight.
        private var myProducer: com.google.devtools.build.skyframe.state.Driver? = null
        private val transitiveState: TransitiveDependencyState
        private val storedEvents: StoredEventHandler

        // --------------- Configuration fields ------------------
        private var configurationKey: BuildConfigurationKey? = null
        private var idempotencyState: IdempotencyState? = null

        // --------------- Aspect fields ------------------
        private var aspectKeys: com.google.common.collect.ImmutableList<AspectKey?>? = null

        // --------------- Error handling fields ------------------
        private var message: String? = null
        private var location: net.starlark.java.syntax.Location? = null
        private var exitCode: DetailedExitCode? = null

        init {
            this.transitiveState =
                TransitiveDependencyState(storeTransitivePackages, prerequisitePackages)
            this.storedEvents = StoredEventHandler()
        }

        /**
         * Implementation of [RuleTransitionApplier.ResultSink], where accepting the configuration
         * and idempotency state is needed to compute [ConfiguredTargetKey].
         */
        public override fun acceptConfiguration(
            configurationKey: BuildConfigurationKey?, idempotencyState: IdempotencyState?
        ) {
            this.configurationKey = configurationKey
            this.idempotencyState = idempotencyState
        }

        /**
         * Implementation of [RuleTransitionApplier.ResultSink], where accepting the error message
         * is needed to throw [ReportedException].
         */
        public override fun acceptErrorMessage(
            message: String?, location: net.starlark.java.syntax.Location?, exitCode: DetailedExitCode?
        ) {
            this.message = message
            this.location = location
            this.exitCode = exitCode
        }

        fun hasError(): Boolean {
            return this.message != null || this.location != null || this.exitCode != null
        }

        /**
         * Handles an exception thrown during the rule transition application in [ ]
         */
        fun createException(
            baseConfiguredTargetKey: ConfiguredTargetKey, target: Target
        ): ConfiguredValueCreationException {
            val cause: com.google.devtools.build.lib.causes.Cause =
                AnalysisFailedCause(
                    baseConfiguredTargetKey.getLabel(),
                    configurationIdMessage(
                        baseConfiguredTargetKey.getConfigurationKey().getOptionsChecksum()
                    ),
                    if (exitCode != null) exitCode else createDetailedExitCode(message)
                )
            return ConfiguredValueCreationException(
                location,
                message,
                target.getLabel(),
                configurationId(baseConfiguredTargetKey.getConfigurationKey()),
                NestedSetBuilder.create(Order.STABLE_ORDER, cause),
                if (exitCode != null) exitCode else createDetailedExitCode(message)
            )
        }
    }

    companion object {
        @Throws(DependencyException::class)
        private fun getTarget(packageValue: PackageValue, targetLabel: Label): Target {
            val pkg: Package = packageValue.getPackage()
            try {
                return pkg.getTarget(targetLabel.name)
            } catch (e: NoSuchTargetException) {
                throw DependencyException(e)
            }
        }

        @Throws(
            java.lang.InterruptedException::class,
            DependencyException::class,
            TopLevelStarlarkAspectFunctionException::class
        )
        private fun createAspectsKeys(
            state: State,
            target: Target,
            aspects: com.google.common.collect.ImmutableList<Aspect?>?,
            baseConfiguredTargetKey: ConfiguredTargetKey?,
            env: SkyFunction.Environment
        ): com.google.common.collect.ImmutableList<AspectKey?>? {
            var target = target
            if (state.aspectKeys != null) {
                return state.aspectKeys
            }

            // In case the target is an alias, we need to resolve its actual target.
            if (AliasProvider.mayBeAlias(target)) {
                val aliasConfiguredValue: ConfiguredTargetValue? =
                    env.getValue(baseConfiguredTargetKey) as ConfiguredTargetValue?
                if (env.valuesMissing()) {
                    return null
                }

                val actualLabel: Label = aliasConfiguredValue.getConfiguredTarget().getActual().getLabel()
                val packageValue: PackageValue? = env.getValue(actualLabel.getPackageIdentifier()) as PackageValue?
                if (env.valuesMissing()) {
                    return null
                }
                target = getTarget(packageValue, actualLabel)
            }

            val aspectCollection: AspectCollection
            try {
                // TODO(bazel-team): Filter aspects more based on rule type. For example, aspect key should
                // not be created for a file target if the aspect does not apply to files or their generating
                // rules. Currently, some tests depend on such keys being created, so they need to be modified
                // first.
                if (target.isRule()) {
                    val ruleTarget: Rule = target as Rule
                    aspectCollection =
                        AspectResolutionHelpers.computeAspectCollection(
                            aspects,
                            ruleTarget.getAdvertisedProviders(),
                            ruleTarget.getLabel(),
                            ruleTarget.getRuleDefinitionEnvironmentLabel(),
                            ruleTarget.getRuleClass(),
                            ruleTarget.getOnlyTagsAttribute(),
                            ruleTarget.getLocation(),
                            env.getListener()
                        )
                } else {
                    aspectCollection =
                        AspectResolutionHelpers.computeAspectCollectionNoAspectsFiltering(
                            aspects, target.getLabel(), target.getLocation()
                        )
                }
            } catch (e: InconsistentAspectOrderException) {
                // This is very unlikely, because AspectCollection should have deduplicated top level aspects.
                env.getListener().handle(com.google.devtools.build.lib.events.Event.error(e.getMessage()))
                throw TopLevelStarlarkAspectFunctionException(
                    TopLevelAspectsDetailsBuildFailedException(
                        e.getMessage(), Code.ASPECT_CREATION_FAILED
                    )
                )
            } catch (e: net.starlark.java.eval.EvalException) {
                env.getListener().handle(com.google.devtools.build.lib.events.Event.error(e.getMessageWithStack()))
                throw TopLevelStarlarkAspectFunctionException(
                    TopLevelAspectsDetailsBuildFailedException(
                        e.getMessage(), Code.ASPECT_CREATION_FAILED
                    )
                )
            }

            state.aspectKeys = aspectCollection.createAspectKeys(baseConfiguredTargetKey)
            return state.aspectKeys
        }
    }
}
