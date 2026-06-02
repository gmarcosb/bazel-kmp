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

import com.google.devtools.build.lib.analysis.config.BuildOptions

/**
 * Creates the needed [BuildConfigurationKey] instances for the given options.
 * 
 * 
 * This includes merging in platform mappings and platform-based flags.
 * 
 * 
 * The output preserves the iteration order of the input.
 */
class BuildConfigurationKeyMapProducer
    (// -------------------- Input --------------------
    private val sink: ResultSink,
    runAfter: StateMachine?,
    options: MutableMap<String?, BuildOptions?>,
    label: com.google.devtools.build.lib.cmdline.Label?
) : StateMachine, com.google.devtools.build.lib.analysis.producers.BuildConfigurationKeyProducer.ResultSink<String?> {
    /** Interface for clients to accept results of this computation.  */
    interface ResultSink {
        fun acceptOptionsParsingError(e: com.google.devtools.common.options.OptionsParsingException?)

        fun acceptPlatformMappingError(e: PlatformMappingException?)

        fun acceptPlatformFlagsError(error: InvalidPlatformException?)

        fun acceptBuildOptionsScopeFunctionError(e: BuildOptionsScopeFunctionException?)

        fun acceptTransitionedConfigurations(
            transitionedOptions: com.google.common.collect.ImmutableMap<String?, BuildConfigurationKey?>?
        )
    }

    private val runAfter: StateMachine?
    private val options: MutableMap<String?, BuildOptions?>
    private val label: com.google.devtools.build.lib.cmdline.Label?

    // -------------------- Internal State --------------------
    private val results: MutableMap<String?, BuildConfigurationKey?>

    init {
        this.runAfter = runAfter
        this.options = options
        this.results =
            com.google.common.collect.Maps.newHashMapWithExpectedSize<String?, BuildConfigurationKey?>(options.size)
        this.label = label
    }

    override fun step(tasks: StateMachine.Tasks): StateMachine {
        options.forEach { (context: String?, buildOptions: BuildOptions?) ->
            tasks.enqueue(
                BuildConfigurationKeyProducer<String?>(
                    this as com.google.devtools.build.lib.analysis.producers.BuildConfigurationKeyProducer.ResultSink<String?>,
                    StateMachine.DONE,
                    context,
                    buildOptions,
                    label
                )
            )
        }
        return StateMachine { tasks: StateMachine.Tasks? -> this.combineResults(tasks) }
    }

    private fun combineResults(tasks: StateMachine.Tasks?): StateMachine? {
        if (this.results.size != this.options.size) {
            // An error occurred while processing at least one set of options.
            return StateMachine.DONE
        }

        // Ensure that the result keys are in the same order as the original.
        val output: com.google.common.collect.ImmutableMap.Builder<String?, BuildConfigurationKey?> =
            com.google.common.collect.ImmutableMap.builderWithExpectedSize<String?, BuildConfigurationKey?>(this.options.size)
        for (transitionKey in this.options.keys) {
            val resultKey: BuildConfigurationKey? = this.results.get(transitionKey)
            output.put(transitionKey, resultKey)
        }

        this.sink.acceptTransitionedConfigurations(output.buildOrThrow())
        return this.runAfter
    }

    override fun acceptOptionsParsingError(e: com.google.devtools.common.options.OptionsParsingException?) {
        this.sink.acceptOptionsParsingError(e)
    }

    override fun acceptPlatformMappingError(e: PlatformMappingException?) {
        this.sink.acceptPlatformMappingError(e)
    }

    override fun acceptPlatformFlagsError(error: InvalidPlatformException?) {
        this.sink.acceptPlatformFlagsError(error)
    }

    override fun acceptTransitionedConfiguration(
        transitionKey: String?, transitionedOptionKey: BuildConfigurationKey?
    ) {
        this.results.put(transitionKey, transitionedOptionKey)
    }

    override fun acceptBuildOptionsScopeFunctionError(e: BuildOptionsScopeFunctionException?) {
        this.sink.acceptBuildOptionsScopeFunctionError(e)
    }
}
