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

import com.google.devtools.build.lib.analysis.config.ConfigConditions

/**
 * Computes the [DependencyContext] while checking for platform compatibility.
 * 
 * 
 * See [Skipping
 * Incompatible Targets](https://bazel.build/extending/platforms#skipping-incompatible-targets) for more details on platform compatibility.
 */
class DependencyContextProducerWithCompatibilityCheck
    (
    targetAndConfiguration: TargetAndConfiguration,
    configuredTargetKey: ConfiguredTargetKey,
    unloadedToolchainContextsInputs: UnloadedToolchainContextsInputs,
    transitiveState: TransitiveDependencyState?,
    sink: com.google.devtools.build.lib.analysis.producers.DependencyContextProducer.ResultSink
) : StateMachine, com.google.devtools.build.lib.analysis.producers.PlatformProducer.ResultSink,
    com.google.devtools.build.lib.analysis.producers.ConfigConditionsProducer.ResultSink,
    IncompatibleTargetProducer.ResultSink,
    com.google.devtools.build.lib.analysis.producers.UnloadedToolchainContextsProducer.ResultSink {
    // -------------------- Input --------------------
    private val targetAndConfiguration: TargetAndConfiguration
    private val configuredTargetKey: ConfiguredTargetKey
    private val unloadedToolchainContextsInputs: UnloadedToolchainContextsInputs

    private val transitiveState: TransitiveDependencyState?

    // -------------------- Output --------------------
    private val sink: com.google.devtools.build.lib.analysis.producers.DependencyContextProducer.ResultSink

    // -------------------- Internal State --------------------
    private var targetPlatformInfo: com.google.devtools.build.lib.analysis.platform.PlatformInfo? = null
    private var configConditions: ConfigConditions? = null

    // Will be null if the target doesn't require toolchain resolution.
    private var unloadedToolchainContexts: ToolchainCollection<UnloadedToolchainContext?>? = null
    private var hasError = false

    init {
        this.targetAndConfiguration = targetAndConfiguration
        this.configuredTargetKey = configuredTargetKey
        this.unloadedToolchainContextsInputs = unloadedToolchainContextsInputs
        this.transitiveState = transitiveState
        this.sink = sink
    }

    override fun step(tasks: StateMachine.Tasks?): StateMachine {
        val defaultToolchainContextKey: ToolchainContextKey? =
            unloadedToolchainContextsInputs.targetToolchainContextKey()
        if (defaultToolchainContextKey == null) {
            // If `defaultToolchainContextKey` is null, there's no platform info, incompatibility check
            // or toolchain resolution. Short-circuits and computes only the ConfigConditions.
            return ConfigConditionsProducer(
                targetAndConfiguration.getTarget(),
                targetAndConfiguration.getTarget().getLabel(),
                configuredTargetKey.getConfigurationKey(),  /* targetPlatformInfo= */
                null,
                transitiveState,
                this as com.google.devtools.build.lib.analysis.producers.ConfigConditionsProducer.ResultSink,  /* runAfter= */
                StateMachine { tasks: StateMachine.Tasks? -> this.constructResult(tasks) })
        }

        // Non-null `defaultToolchainContextKey` guarantees that `platformConfiguration` is non-null.
        val platformConfiguration: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            targetAndConfiguration.getConfiguration().getFragment(PlatformConfiguration::class.java)
        // Checks for incompatibility before toolchain resolution so that known missing
        // toolchains mark the target incompatible instead of failing the build.
        return PlatformProducer(
            platformConfiguration.getTargetPlatform(),
            targetAndConfiguration
                .getConfiguration()
                .getOptions()
                .get(CoreOptions::class.java)
                .getCommandLineFlagAliasesMap(),
            this as com.google.devtools.build.lib.analysis.producers.PlatformProducer.ResultSink,  /* runAfter= */
            StateMachine { tasks: StateMachine.Tasks? -> this.computeConfigConditions(tasks) })
    }

    override fun acceptPlatformValue(value: PlatformValue) {
        this.targetPlatformInfo = value.platformInfo
    }

    override fun acceptPlatformInfoError(error: InvalidPlatformException?) {
        this.hasError = true
        sink.acceptDependencyContextError(DependencyContextError.Companion.of(error))
    }

    override fun acceptOptionsParsingError(error: com.google.devtools.common.options.OptionsParsingException) {
        this.hasError = true
        sink.acceptDependencyContextError(
            DependencyContextError.Companion.of(
                ConfiguredValueCreationException(
                    targetAndConfiguration.getTarget(), error.message
                )
            )
        )
    }

    private fun computeConfigConditions(tasks: StateMachine.Tasks?): StateMachine {
        if (hasError) {
            return StateMachine.DONE
        }

        return ConfigConditionsProducer(
            targetAndConfiguration.getTarget(),
            targetAndConfiguration.getTarget().getLabel(),
            configuredTargetKey.getConfigurationKey(),
            targetPlatformInfo,
            transitiveState,
            this as com.google.devtools.build.lib.analysis.producers.ConfigConditionsProducer.ResultSink,  /* runAfter= */
            StateMachine { tasks: StateMachine.Tasks? -> this.checkCompatibility(tasks) })
    }

    // -------------------- ConfigConditionsProducer.ResultSink --------------------
    override fun acceptConfigConditions(configConditions: ConfigConditions?) {
        this.configConditions = configConditions
    }

    override fun acceptConfigConditionsError(error: ConfiguredValueCreationException?) {
        this.hasError = true
        sink.acceptDependencyContextError(DependencyContextError.Companion.of(error))
    }

    private fun checkCompatibility(tasks: StateMachine.Tasks?): StateMachine {
        if (hasError) {
            return StateMachine.DONE
        }

        return IncompatibleTargetProducer(
            targetAndConfiguration,
            configuredTargetKey,
            configConditions,
            targetPlatformInfo,
            transitiveState,
            this as IncompatibleTargetProducer.ResultSink,  /* runAfter= */
            { tasks: StateMachine.Tasks? -> this.computeUnloadedToolchainContexts(tasks) })
    }

    public override fun acceptIncompatibleTarget(incompatibleTarget: java.util.Optional<RuleConfiguredTargetValue?>) {
        if (incompatibleTarget.isPresent()) {
            this.hasError = true
            sink.acceptDependencyContextError(
                DependencyContextError.Companion.of(IncompatibleTargetException(incompatibleTarget.get()))
            )
        }
    }

    public override fun acceptValidationException(e: com.google.devtools.build.lib.packages.ConfiguredAttributeMapper.ValidationException?) {
        this.hasError = true
        sink.acceptDependencyContextError(DependencyContextError.Companion.of(e))
    }

    private fun computeUnloadedToolchainContexts(tasks: StateMachine.Tasks?): StateMachine {
        if (hasError) {
            return StateMachine.DONE
        }

        return UnloadedToolchainContextsProducer(
            unloadedToolchainContextsInputs,
            this as com.google.devtools.build.lib.analysis.producers.UnloadedToolchainContextsProducer.ResultSink,  /* runAfter= */
            StateMachine { tasks: StateMachine.Tasks? -> this.constructResult(tasks) })
    }

    override fun acceptUnloadedToolchainContexts(
        unloadedToolchainContexts: ToolchainCollection<UnloadedToolchainContext?>?
    ) {
        this.unloadedToolchainContexts = unloadedToolchainContexts
    }

    override fun acceptUnloadedToolchainContextsError(error: ToolchainException?) {
        this.hasError = true
        sink.acceptDependencyContextError(DependencyContextError.Companion.of(error))
    }

    private fun constructResult(tasks: StateMachine.Tasks?): StateMachine {
        if (hasError) {
            return StateMachine.DONE
        }

        sink.acceptDependencyContext(
            DependencyContext.Companion.create(unloadedToolchainContexts, configConditions)
        )
        return StateMachine.DONE
    }
}
