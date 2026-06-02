// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.producers

import com.google.devtools.build.lib.analysis.ExecGroupCollection

/** Applies any requested rule transition before producing the final configuration.  */
class RuleTransitionApplier
    (
    target: com.google.devtools.build.lib.packages.Target,
    targetAndConfigurationData: TargetAndConfigurationData,
    sink: ResultSink,
    eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler,
    runAfter: StateMachine?
) : StateMachine, com.google.devtools.build.lib.analysis.producers.TransitionApplier.ResultSink,
    com.google.devtools.build.lib.analysis.producers.ConfigConditionsProducer.ResultSink,
    com.google.devtools.build.lib.analysis.producers.PlatformProducer.ResultSink {
    /** Interface for accepting values produced by this class.  */
    interface ResultSink {
        fun acceptConfiguration(
            configurationKey: BuildConfigurationKey?, idempotencyState: IdempotencyState?
        )

        fun acceptErrorMessage(
            message: String?, location: net.starlark.java.syntax.Location?, exitCode: DetailedExitCode?
        )
    }

    /**
     * Classifies a transition based on its behavior (no-op, idempotent, or non-idempotent).
     * 
     * 
     * During analysis, when a rule transition modifies the configuration, it triggers a new
     * evaluation of the target with the new configuration. This re-evaluation can inadvertently lead
     * to the rule transition being applied twice. To prevent this, the [ConfiguredTargetKey]
     * includes a [ConfiguredTargetKey.shouldApplyRuleTransition] flag. Setting this flag allows
     * skipping the rule transition during re-evaluation. This flag should be set false when the
     * result is [IdempotencyState.NON_IDEMPOTENT].
     * 
     * 
     * At first glance, it seems like setting `shouldApplyRuleTransition=false` should be benign
     * for ([IdempotencyState.IDEMPOTENT] and [IdempotencyState.NON_IDEMPOTENT]), but it
     * would be an error in the idempotent case.
     * 
     * 
     * Idempotent Case
     * 
     * 
     * If we were to mark the idempotent case with `shouldApplyRuleTransition=false`, it would lead
     * to action conflicts. Let `//foo[123]` be a key that rule transitions to `//foo[abc]` and
     * suppose the outcome is marked `//foo[abc] shouldApplyRuleTransition=false`.
     * 
     * 
     * A different parent might directly request `//foo[abc] shouldApplyRuleTransition=true`. Since
     * the rule transition is a idempotent, it would result in the same actions as `//foo[abc]
     * shouldApplyRuleTransition=false` with a different key, causing action conflicts.
     * 
     * 
     * Non-idempotent Case
     * 
     * 
     * If the transition is non-idempotent, marks [ ][ConfiguredTargetKey.shouldApplyRuleTransition] false in the delegate key.
     * 
     * 
     * In the example of //foo[abc] shouldApplyRuleTransition=false and //foo[abc]
     * shouldApplyRuleTransition=true, there should be no action conflicts because the
     * `shouldApplyRuleTransition=false` is the result of a non-idempotent rule transition and
     * `shouldApplyRuleTransition=true` will produce a different configuration.
     */
    enum class IdempotencyState {
        /** The transition was a no-op.  */
        IDENTITY,

        /** The rule transition is idempotent.  */
        IDEMPOTENT,

        /** The rule transition is non-idempotent.  */
        NON_IDEMPOTENT
    }

    // -------------------- Input --------------------
    private val target: com.google.devtools.build.lib.packages.Target
    private val targetAndConfigurationData: TargetAndConfigurationData

    // -------------------- Output --------------------
    private val sink: ResultSink
    private val eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler

    // -------------------- Sequencing --------------------
    private val runAfter: StateMachine?

    // -------------------- Internal State --------------------
    private var platformInfo: com.google.devtools.build.lib.analysis.platform.PlatformInfo? = null
    private var configConditions: ConfigConditions? = null
    private var ruleTransition: ConfigurationTransition? = null
    private var configurationKey: BuildConfigurationKey? = null

    init {
        this.target = target
        this.targetAndConfigurationData = targetAndConfigurationData
        this.sink = sink
        this.eventHandler = eventHandler
        this.runAfter = runAfter
    }

    @Throws(java.lang.InterruptedException::class)
    override fun step(tasks: StateMachine.Tasks): StateMachine? {
        val unloadedToolchainContextsInputs: UnloadedToolchainContextsInputs?
        var platformConfiguration: PlatformConfiguration? = null
        val preRuleTransitionKey: ConfiguredTargetKey = targetAndConfigurationData.getPreRuleTransitionKey()
        val platformOptions: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            preRuleTransitionKey.getConfigurationKey().getOptions().get(PlatformOptions::class.java)
        if (platformOptions == null) {
            unloadedToolchainContextsInputs = UnloadedToolchainContextsInputs.Companion.empty()
        } else {
            platformConfiguration = PlatformConfiguration(platformOptions)
            try {
                unloadedToolchainContextsInputs =
                    ToolchainContextUtil.getUnloadedToolchainContextsInputs(
                        target,
                        preRuleTransitionKey.getConfigurationKey().getOptions().get(CoreOptions::class.java),
                        platformConfiguration,
                        preRuleTransitionKey.getExecutionPlatformLabel(),
                        computeToolchainConfigurationKey(
                            preRuleTransitionKey.getConfigurationKey().getOptions(),
                            targetAndConfigurationData.getToolchainTaggedTrimmingTransition()
                        )
                    )
            } catch (e: ExecGroupCollection.InvalidExecGroupException) {
                emitErrorMessage(e.getMessage())
                return runAfter
            }
        }

        if (unloadedToolchainContextsInputs.targetToolchainContextKey() != null) {
            tasks.enqueue(
                PlatformProducer(
                    platformConfiguration.getTargetPlatform(),
                    preRuleTransitionKey
                        .getConfigurationKey()
                        .getOptions()
                        .get(CoreOptions::class.java)
                        .getCommandLineFlagAliasesMap(),
                    this as com.google.devtools.build.lib.analysis.producers.PlatformProducer.ResultSink,
                    StateMachine { tasks: StateMachine.Tasks? -> this.computeConfigConditions(tasks) })
            )
        } else {
            this.platformInfo = null
            computeConfigConditions(tasks)
        }

        return runAfter
    }

    @Throws(java.lang.InterruptedException::class)
    private fun computeToolchainConfigurationKey(
        buildOptions: BuildOptions?, toolchainTaggedTrimmingTransition: PatchTransition
    ): BuildConfigurationKey? {
        // The toolchain context's options are the parent rule's options with manual trimming
        // auto-applied. This means toolchains don't inherit feature flags. This helps build
        // performance: if the toolchain context had the exact same configuration of its parent and
        // that
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
                    buildOptions, toolchainTaggedTrimmingTransition.requiresOptionFragments()
                ),
                eventHandler
            )
        return BuildConfigurationKey.create(toolchainOptions)
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun computeConfigConditions(tasks: StateMachine.Tasks): StateMachine {
        val preRuleTransitionKey: ConfiguredTargetKey = targetAndConfigurationData.getPreRuleTransitionKey()
        // TODO @aranguyen b/297077082
        tasks.enqueue(
            ConfigConditionsProducer(
                target,
                preRuleTransitionKey.getLabel(),
                preRuleTransitionKey.getConfigurationKey(),
                platformInfo,
                targetAndConfigurationData.getTransitiveState(),
                this as com.google.devtools.build.lib.analysis.producers.ConfigConditionsProducer.ResultSink,
                StateMachine { tasks: StateMachine.Tasks? -> this.computeTransition(tasks) })
        )
        return StateMachine.DONE
    }

    override fun acceptConfigConditions(configConditions: ConfigConditions?) {
        this.configConditions = configConditions
    }

    override fun acceptConfigConditionsError(e: ConfiguredValueCreationException) {
        emitErrorMessage(e.message)
    }

    // Keep in sync with CqueryTransitionResolver.getRuleTransition.
    fun computeTransition(tasks: StateMachine.Tasks?): StateMachine {
        if (configConditions == null) {
            return StateMachine.DONE
        }
        var transitionFactory: TransitionFactory<RuleTransitionData?> =
            target.getAssociatedRule().getRuleClassObject().getTransitionFactory()
        val trimmingTransitionFactory: TransitionFactory<RuleTransitionData?>? =
            targetAndConfigurationData.getTrimmingTransitionFactory()
        if (trimmingTransitionFactory != null) {
            transitionFactory =
                ComposingTransitionFactory.of(transitionFactory, trimmingTransitionFactory)
        }
        val preRuleTransitionKey: ConfiguredTargetKey = targetAndConfigurationData.getPreRuleTransitionKey()
        val transitionData: RuleTransitionData =
            RuleTransitionData.create(
                target.getAssociatedRule(),
                configConditions.asProviders(),
                preRuleTransitionKey.getConfigurationKey().getOptionsChecksum()
            )
        val transition: ConfigurationTransition? = transitionFactory.create(transitionData)
        this.ruleTransition = transition
        return TransitionApplier(
            target.getLabel(),
            preRuleTransitionKey.getConfigurationKey(),
            ruleTransition,
            targetAndConfigurationData.getTransitionCache(),
            this as com.google.devtools.build.lib.analysis.producers.TransitionApplier.ResultSink,
            eventHandler,  /* runAfter= */
            StateMachine { tasks: StateMachine.Tasks? -> this.processTransitionedKey(tasks) })
    }

    override fun acceptBuildOptionsScopeFunctionError(e: BuildOptionsScopeFunctionException) {
        emitErrorMessage(e.message)
    }

    override fun acceptTransitionedConfigurations(
        transitionResult: com.google.common.collect.ImmutableMap<String?, BuildConfigurationKey?>
    ) {
        com.google.common.base.Preconditions.checkState(
            transitionResult.size == 1,
            "Expected exactly one result: %s",
            transitionResult
        )
        this.configurationKey =
            com.google.common.base.Preconditions.checkNotNull<BuildConfigurationKey?>(
                transitionResult.get(ConfigurationTransition.PATCH_TRANSITION_KEY),
                "Transition result missing patch transition entry: %s",
                transitionResult
            )
    }

    override fun acceptTransitionError(e: TransitionException) {
        emitErrorMessage(e.message)
    }

    override fun acceptOptionsParsingError(e: com.google.devtools.common.options.OptionsParsingException) {
        emitErrorMessage(e.message)
    }

    override fun acceptPlatformMappingError(e: PlatformMappingException) {
        emitErrorMessage(e.message)
    }

    override fun acceptPlatformFlagsError(e: InvalidPlatformException) {
        emitErrorMessage(e.message)
    }

    override fun acceptPlatformValue(value: PlatformValue) {
        this.platformInfo = value.platformInfo
    }

    override fun acceptPlatformInfoError(error: InvalidPlatformException) {
        emitErrorMessage(error.message)
    }

    private fun processTransitionedKey(tasks: StateMachine.Tasks?): StateMachine {
        if (configurationKey == null) {
            return StateMachine.DONE // There was an error.
        }
        val parentConfiguration: BuildConfigurationKey? =
            targetAndConfigurationData.getPreRuleTransitionKey().getConfigurationKey()
        if (configurationKey == parentConfiguration) {
            // This key owns the configuration and the computation completes normally.
            sink.acceptConfiguration(configurationKey, IdempotencyState.IDENTITY)
            return StateMachine.DONE
        }
        eventHandler.post(
            ConfigurationTransitionEvent.create(
                parentConfiguration.getOptionsChecksum(), configurationKey.getOptionsChecksum()
            )
        )
        return IdempotencyChecker()
    }

    /** Checks of transition is idempotent and accepts the configuration accordingly.  */
    private inner class IdempotencyChecker : StateMachine,
        com.google.devtools.build.lib.analysis.producers.TransitionApplier.ResultSink {
        // -------------------- Internal State --------------------
        private var configurationKey2: BuildConfigurationKey? = null

        override fun step(tasks: StateMachine.Tasks?): StateMachine {
            return TransitionApplier(
                target.getLabel(),
                configurationKey,
                ruleTransition,
                targetAndConfigurationData.getTransitionCache(),
                this as com.google.devtools.build.lib.analysis.producers.TransitionApplier.ResultSink,
                eventHandler,  /* runAfter= */
                StateMachine.DONE
            )
        }

        override fun acceptBuildOptionsScopeFunctionError(e: BuildOptionsScopeFunctionException) {
            emitErrorMessage(e.message)
        }

        override fun acceptTransitionedConfigurations(
            transitionResult: com.google.common.collect.ImmutableMap<String?, BuildConfigurationKey?>
        ) {
            com.google.common.base.Preconditions.checkState(
                transitionResult.size == 1,
                "Expected exactly one result: %s",
                transitionResult
            )
            this.configurationKey2 =
                com.google.common.base.Preconditions.checkNotNull<BuildConfigurationKey?>(
                    transitionResult.get(ConfigurationTransition.PATCH_TRANSITION_KEY),
                    "Transition result missing patch transition entry: %s",
                    transitionResult
                )

            val idempotencyState =
                if (configurationKey == configurationKey2)
                    IdempotencyState.IDEMPOTENT
                else
                    IdempotencyState.NON_IDEMPOTENT
            sink.acceptConfiguration(configurationKey, idempotencyState)
        }

        override fun acceptTransitionError(e: TransitionException) {
            emitErrorMessage(e.message)
        }

        override fun acceptOptionsParsingError(e: com.google.devtools.common.options.OptionsParsingException) {
            emitErrorMessage(e.message)
        }

        override fun acceptPlatformMappingError(e: PlatformMappingException) {
            emitErrorMessage(e.message)
        }

        override fun acceptPlatformFlagsError(e: InvalidPlatformException) {
            emitErrorMessage(e.message)
        }
    }

    private fun emitErrorMessage(message: String?) {
        sink.acceptErrorMessage(message, TargetUtils.getLocationMaybe(target),  /* exitCode= */null)
    }
}
