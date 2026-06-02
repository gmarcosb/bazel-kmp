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
 * This class computes the unloaded toolchain context and [ConfigConditions].
 * 
 * 
 * It uses [PlatformInfo] derived from the unloaded toolchain contexts to compute config
 * conditions, creating a sequential dependency between the two.
 * 
 * 
 * It's possible to use [DependencyContextProducerWithCompatibilityCheck] here instead but
 * that necessarily evaluates [ConfigConditions] before computing the unloaded toolchain
 * contexts, which in turn requires evaluating [PlatformInfo] in advance. This ordering is
 * necessary because the compatibility check must precede the unloaded toolchain contexts
 * computation.
 * 
 * 
 * This producer optimizes for the case where no compatibility check is needed and saves memory
 * by using the [PlatformInfo] computed as a side effect of the unloaded toolchain contexts.
 */
class DependencyContextProducer
    (
    unloadedToolchainContextsInputs: UnloadedToolchainContextsInputs?,
    targetAndConfiguration: TargetAndConfiguration,
    buildConfigurationKey: BuildConfigurationKey?,
    transitiveState: TransitiveDependencyState?,
    sink: ResultSink
) : StateMachine, com.google.devtools.build.lib.analysis.producers.UnloadedToolchainContextsProducer.ResultSink,
    com.google.devtools.build.lib.analysis.producers.ConfigConditionsProducer.ResultSink {
    /**
     * Accepts results for both [DependencyContextProducer] and [ ].
     */
    interface ResultSink {
        fun acceptDependencyContext(value: DependencyContext?)

        fun acceptDependencyContextError(error: DependencyContextError?)
    }

    // -------------------- Input --------------------
    private val unloadedToolchainContextsInputs: UnloadedToolchainContextsInputs?
    private val targetAndConfiguration: TargetAndConfiguration
    private val buildConfigurationKey: BuildConfigurationKey?
    private val transitiveState: TransitiveDependencyState?

    // -------------------- Output --------------------
    private val sink: ResultSink

    // -------------------- Internal State --------------------
    // Will be null if the target doesn't require toolchain resolution.
    private var unloadedToolchainContexts: ToolchainCollection<UnloadedToolchainContext?>?
    private var configConditions: ConfigConditions? = null
    var hasError: Boolean = false

    init {
        this.unloadedToolchainContextsInputs = unloadedToolchainContextsInputs
        this.buildConfigurationKey = buildConfigurationKey
        this.unloadedToolchainContexts = null
        this.targetAndConfiguration = targetAndConfiguration
        this.transitiveState = transitiveState
        this.sink = sink
    }

    override fun step(tasks: StateMachine.Tasks?): StateMachine {
        return UnloadedToolchainContextsProducer(
            unloadedToolchainContextsInputs,
            this as com.google.devtools.build.lib.analysis.producers.UnloadedToolchainContextsProducer.ResultSink,  /* runAfter= */
            StateMachine { tasks: StateMachine.Tasks? -> this.computeConfigConditions(tasks) })
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

    private fun computeConfigConditions(tasks: StateMachine.Tasks?): StateMachine {
        if (hasError) {
            return StateMachine.DONE
        }

        return ConfigConditionsProducer(
            targetAndConfiguration.getTarget(),
            targetAndConfiguration.getTarget().getLabel(),
            buildConfigurationKey,
            if (unloadedToolchainContexts == null) null else unloadedToolchainContexts.getTargetPlatform(),
            transitiveState,
            this as com.google.devtools.build.lib.analysis.producers.ConfigConditionsProducer.ResultSink,  /* runAfter= */
            StateMachine { tasks: StateMachine.Tasks? -> this.constructResult(tasks) })
    }

    override fun acceptConfigConditions(configConditions: ConfigConditions?) {
        this.configConditions = configConditions
    }

    override fun acceptConfigConditionsError(error: ConfiguredValueCreationException?) {
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
