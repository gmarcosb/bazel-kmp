// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ActionLookupKey

/**
 * Computes the target and configuration for a configured target key.
 * 
 * 
 * If the key has a configuration and the target is configurable, attempts to apply a rule side
 * transition. If the configuration changes, delegates to a target with the new configuration. If
 * the target is not configurable, directly delegates to the null configuration.
 */
class TargetAndConfigurationProducer
    (
    preRuleTransitionKey: ConfiguredTargetKey,
    trimmingTransitionFactory: TransitionFactory<RuleTransitionData?>?,
    toolchainTaggedTrimmingTransition: PatchTransition?,
    transitionCache: StarlarkTransitionCache?,
    transitiveState: TransitiveDependencyState?,
    sink: ResultSink,
    eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler
) : TargetAndConfigurationData, StateMachine, ValueOrExceptionSink<InvalidConfigurationException?>,
    java.util.function.Consumer<SkyValue?>, com.google.devtools.build.lib.analysis.producers.TargetProducer.ResultSink,
    com.google.devtools.build.lib.analysis.producers.RuleTransitionApplier.ResultSink {
    /** Accepts results of this producer.  */
    interface ResultSink {
        fun acceptTargetAndConfiguration(value: TargetAndConfiguration?, fullKey: ConfiguredTargetKey?)

        fun acceptTargetAndConfigurationDelegatedValue(value: ConfiguredTargetValue?)

        fun acceptTargetAndConfigurationError(error: TargetAndConfigurationError?)
    }

    /** Tagged union of possible errors.  */
    @AutoOneOf(com.google.devtools.build.lib.analysis.producers.TargetAndConfigurationProducer.TargetAndConfigurationError.Kind::class)
    abstract class TargetAndConfigurationError {
        /** Tags the error type.  */
        enum class Kind {
            CONFIGURED_VALUE_CREATION,
            NO_SUCH_THING,
            INCONSISTENT_NULL_CONFIG
        }

        abstract fun kind(): Kind?

        abstract fun configuredValueCreation(): ConfiguredValueCreationException?

        abstract fun noSuchThing(): NoSuchThingException?

        abstract fun inconsistentNullConfig(): InconsistentNullConfigException?

        companion object {
            private fun of(e: ConfiguredValueCreationException?): TargetAndConfigurationError {
                return AutoOneOf_TargetAndConfigurationProducer_TargetAndConfigurationError
                    .configuredValueCreation(e)
            }

            private fun of(e: NoSuchThingException?): TargetAndConfigurationError {
                return AutoOneOf_TargetAndConfigurationProducer_TargetAndConfigurationError.noSuchThing(e)
            }

            private fun of(e: InconsistentNullConfigException?): TargetAndConfigurationError {
                return AutoOneOf_TargetAndConfigurationProducer_TargetAndConfigurationError
                    .inconsistentNullConfig(e)
            }
        }
    }

    // -------------------- Input --------------------
    private val preRuleTransitionKey: ConfiguredTargetKey
    private val trimmingTransitionFactory: TransitionFactory<RuleTransitionData?>?
    private val toolchainTaggedTrimmingTransition: PatchTransition?
    private val transitionCache: StarlarkTransitionCache?

    private val transitiveState: TransitiveDependencyState?

    // -------------------- Output --------------------
    private val sink: ResultSink
    private val eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler

    // -------------------- Internal State --------------------
    private var target: com.google.devtools.build.lib.packages.Target? = null
    private var configurationKey: BuildConfigurationKey? = null
    private var idempotencyState: IdempotencyState? = null

    init {
        this.preRuleTransitionKey = preRuleTransitionKey
        this.trimmingTransitionFactory = trimmingTransitionFactory
        this.toolchainTaggedTrimmingTransition = toolchainTaggedTrimmingTransition
        this.transitionCache = transitionCache
        this.transitiveState = transitiveState
        this.sink = sink
        this.eventHandler = eventHandler
    }

    override fun step(tasks: StateMachine.Tasks?): StateMachine {
        return TargetProducer(
            preRuleTransitionKey.getLabel(),
            transitiveState,
            this as com.google.devtools.build.lib.analysis.producers.TargetProducer.ResultSink,  /* runAfter= */
            StateMachine { tasks: StateMachine.Tasks? -> this.determineConfiguration(tasks) })
    }

    override fun acceptTarget(target: com.google.devtools.build.lib.packages.Target?) {
        this.target = target
    }

    override fun acceptTargetError(e: NoSuchPackageException) {
        eventHandler.handle(com.google.devtools.build.lib.events.Event.error(e.message))
        sink.acceptTargetAndConfigurationError(TargetAndConfigurationError.Companion.of(e))
    }

    override fun acceptTargetError(e: NoSuchTargetException, location: net.starlark.java.syntax.Location?) {
        eventHandler.handle(com.google.devtools.build.lib.events.Event.error(location, e.message))
        sink.acceptTargetAndConfigurationError(TargetAndConfigurationError.Companion.of(e))
    }

    override fun getPreRuleTransitionKey(): ConfiguredTargetKey {
        return preRuleTransitionKey
    }

    override fun getTrimmingTransitionFactory(): TransitionFactory<RuleTransitionData?>? {
        return trimmingTransitionFactory
    }

    override fun getToolchainTaggedTrimmingTransition(): PatchTransition? {
        return toolchainTaggedTrimmingTransition
    }

    override fun getTransitionCache(): StarlarkTransitionCache? {
        return transitionCache
    }

    override fun getTransitiveState(): TransitiveDependencyState? {
        return transitiveState
    }

    private fun determineConfiguration(tasks: StateMachine.Tasks): StateMachine {
        if (target == null) {
            return StateMachine.DONE // A target could not be determined.
        }

        val configurationKey: BuildConfigurationKey? = preRuleTransitionKey.getConfigurationKey()
        if (configurationKey == null) {
            if (target.isConfigurable()) {
                // We somehow ended up in a target that requires a non-null configuration but with a key
                // that doesn't have a configuration. This is always an error, but we need to bubble this
                // up to the parent to provide more context.
                sink.acceptTargetAndConfigurationError(
                    TargetAndConfigurationError.Companion.of(InconsistentNullConfigException())
                )
                return StateMachine.DONE
            }
            sink.acceptTargetAndConfiguration(
                TargetAndConfiguration(target,  /* configuration= */null), preRuleTransitionKey
            )
            return StateMachine.DONE
        }

        if (!target.isConfigurable()) {
            // If target is not configurable, but requested with a configuration. Delegates to a key with
            // the null configuration. This is expected to be uncommon. The common case of a
            // non-configurable target is an input file, but those are usually package local and requested
            // correctly with the null configuration.
            delegateTo(
                tasks,
                ConfiguredTargetKey.builder()
                    .setLabel(preRuleTransitionKey.getLabel())
                    .setExecutionPlatformLabel(preRuleTransitionKey.getExecutionPlatformLabel())
                    .build()
            )
            return StateMachine.DONE
        }

        if (!preRuleTransitionKey.shouldApplyRuleTransition()) {
            lookUpConfigurationValue(tasks)
            return StateMachine.DONE
        }

        return RuleTransitionApplier(
            target,
            this as TargetAndConfigurationData,
            this as com.google.devtools.build.lib.analysis.producers.RuleTransitionApplier.ResultSink,
            eventHandler,  /* runAfter= */
            StateMachine { tasks: StateMachine.Tasks? -> this.computeTargetAndConfigurationOrDelegatedValue(tasks) })
    }

    private fun delegateTo(tasks: StateMachine.Tasks, delegate: ActionLookupKey?) {
        tasks.lookUp(delegate, this as java.util.function.Consumer<SkyValue?>)
    }

    override fun accept(value: SkyValue?) {
        sink.acceptTargetAndConfigurationDelegatedValue(value as ConfiguredTargetValue?)
    }

    private fun lookUpConfigurationValue(tasks: StateMachine.Tasks) {
        tasks.lookUp<E?>(
            preRuleTransitionKey.getConfigurationKey(),
            InvalidConfigurationException::class.java,
            this as ValueOrExceptionSink<InvalidConfigurationException?>
        )
    }

    override fun acceptValueOrException(
        value: SkyValue?, error: InvalidConfigurationException?
    ) {
        if (value != null) {
            sink.acceptTargetAndConfiguration(
                TargetAndConfiguration(target, value as BuildConfigurationValue),
                preRuleTransitionKey
            )
            return
        }
        emitError(
            error.getMessage(), TargetUtils.getLocationMaybe(target), error.getDetailedExitCode()
        )
    }

    /**
     * Implementation of [RuleTransitionApplier.ResultSink], where accepting the configuration
     * and idempotency state is needed to compute target and configuration or to delegate (see [ ][TargetAndConfigurationProducer.computeTargetAndConfigurationOrDelegatedValue]).
     */
    override fun acceptConfiguration(
        configurationKey: BuildConfigurationKey?, idempotencyState: IdempotencyState?
    ) {
        this.configurationKey = configurationKey
        this.idempotencyState = idempotencyState
    }

    override fun acceptErrorMessage(
        message: String?, location: net.starlark.java.syntax.Location?, exitCode: DetailedExitCode?
    ) {
        emitError(message, location, exitCode)
    }

    fun computeTargetAndConfigurationOrDelegatedValue(tasks: StateMachine.Tasks): StateMachine {
        if (idempotencyState == null) {
            return StateMachine.DONE // An error was encountered.
        }

        if (idempotencyState == IdempotencyState.IDENTITY) {
            tasks.lookUp<E?>(
                configurationKey,
                InvalidConfigurationException::class.java,
                this as ValueOrExceptionSink<InvalidConfigurationException?>
            )
            return StateMachine.DONE
        }

        val keyBuilder: com.google.devtools.build.lib.skyframe.ConfiguredTargetKey.Builder =
            ConfiguredTargetKey.builder()
                .setLabel(preRuleTransitionKey.getLabel())
                .setExecutionPlatformLabel(preRuleTransitionKey.getExecutionPlatformLabel())
                .setConfigurationKey(configurationKey)

        if (idempotencyState == IdempotencyState.NON_IDEMPOTENT) {
            // The transition was not idempotent. Explicitly informs the delegate to avoid applying a
            // rule transition.
            keyBuilder.setShouldApplyRuleTransition(false)
        }

        tasks.lookUp(keyBuilder.build(), this as java.util.function.Consumer<SkyValue?>)
        return StateMachine.DONE
    }

    private fun emitError(
        message: String?, location: net.starlark.java.syntax.Location?, exitCode: DetailedExitCode?
    ) {
        val cause: com.google.devtools.build.lib.causes.Cause =
            AnalysisFailedCause(
                preRuleTransitionKey.getLabel(),
                configurationIdMessage(preRuleTransitionKey.getConfigurationKey().getOptionsChecksum()),
                if (exitCode != null) exitCode else createDetailedExitCode(message)
            )
        sink.acceptTargetAndConfigurationError(
            TargetAndConfigurationError.Companion.of(
                ConfiguredValueCreationException(
                    location,
                    message,
                    target.getLabel(),
                    BuildEventIdUtil.configurationId(preRuleTransitionKey.getConfigurationKey()),
                    NestedSetBuilder.create<com.google.devtools.build.lib.causes.Cause?>(
                        com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER,
                        cause
                    ),
                    if (exitCode != null) exitCode else createDetailedExitCode(message)
                )
            )
        )
    }

    companion object {
        @kotlin.jvm.JvmStatic
        fun configurationIdMessage(optionsCheckSum: String?): ConfigurationId {
            if (optionsCheckSum == null) {
                return ConfigurationId.newBuilder().setId("none").build()
            }
            return ConfigurationId.newBuilder().setId(optionsCheckSum).build()
        }

        @kotlin.jvm.JvmStatic
        fun createDetailedExitCode(message: String?): DetailedExitCode {
            return DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setAnalysis(
                        Analysis.newBuilder().setCode(Analysis.Code.CONFIGURED_VALUE_CREATION_FAILED)
                    )
                    .build()
            )
        }
    }
}
