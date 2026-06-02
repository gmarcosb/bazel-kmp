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

import com.google.devtools.build.lib.analysis.AspectCollection.buildAspectKey

/** Computes [ConfiguredAspect]s and merges them into a prerequisite.  */
internal class ConfiguredAspectProducer
    (
    aspects: AspectCollection,
    prerequisite: ConfiguredTargetAndData,
    sink: ResultSink,
    outputIndex: Int,
    transitiveState: TransitiveDependencyState
) : StateMachine, ValueOrExceptionSink<AspectCreationException?> {
    internal interface ResultSink {
        fun acceptConfiguredAspectMergedTarget(outputIndex: Int, mergedTarget: ConfiguredTargetAndData?)

        fun acceptConfiguredAspectError(error: AspectCreationException?)

        fun acceptConfiguredAspectError(error: MergingException?)
    }

    // -------------------- Input --------------------
    private val aspects: AspectCollection
    private val prerequisite: ConfiguredTargetAndData

    // -------------------- Output --------------------
    private val transitiveState: TransitiveDependencyState
    private val sink: ResultSink
    private val outputIndex: Int

    // -------------------- Internal State --------------------
    private val aspectValues: HashMap<AspectDescriptor?, AspectValue> = HashMap<AspectDescriptor?, AspectValue>()

    init {
        this.aspects = aspects
        this.prerequisite = prerequisite
        this.sink = sink
        this.outputIndex = outputIndex
        this.transitiveState = transitiveState
    }

    override fun step(tasks: StateMachine.Tasks): StateMachine {
        val baseKey: ConfiguredTargetKey? = ConfiguredTargetKey.fromConfiguredTarget(prerequisite.getConfiguredTarget())
        val memoTable: HashMap<AspectDescriptor?, AspectKey?> = HashMap<AspectDescriptor?, AspectKey?>()
        for (deps in aspects.getUsedAspects()) {
            tasks.lookUp<E?>(
                buildAspectKey(deps, memoTable, baseKey),
                AspectCreationException::class.java,
                this as ValueOrExceptionSink<AspectCreationException?>
            )
        }
        return StateMachine { tasks: StateMachine.Tasks? -> this.processResult(tasks) }
    }

    override fun acceptValueOrException(
        untypedValue: SkyValue?, error: AspectCreationException?
    ) {
        if (untypedValue != null) {
            val value: AspectValue = untypedValue as AspectValue
            aspectValues.put(value.getAspect().getDescriptor(), value)
            return
        }
        sink.acceptConfiguredAspectError(error)
    }

    private fun processResult(tasks: StateMachine.Tasks?): StateMachine {
        val usedAspects: com.google.common.collect.ImmutableSet<AspectDeps> = aspects.getUsedAspects()
        if (aspectValues.size < usedAspects.size) {
            return StateMachine.DONE // There was an error.
        }

        val configuredAspects: java.util.ArrayList<ConfiguredAspect?> =
            java.util.ArrayList<ConfiguredAspect?>(usedAspects.size)
        for (depAspect in usedAspects) {
            val value: AspectValue = aspectValues.get(depAspect.aspect())
            if (value === ConfiguredAspect.NonApplicableAspect.INSTANCE) {
                continue
            }
            configuredAspects.add(value)
            if (transitiveState.storeTransitivePackages()) {
                transitiveState.updateTransitivePackages(
                    value.getKeyForTransitivePackageTracking(), value.getTransitivePackages()
                )
            }
        }
        try {
            sink.acceptConfiguredAspectMergedTarget(
                outputIndex,
                prerequisite.fromConfiguredTarget(
                    MergedConfiguredTarget.of(prerequisite.getConfiguredTarget(), configuredAspects)
                )
            )
        } catch (e: MergingException) {
            sink.acceptConfiguredAspectError(e)
        }
        return StateMachine.DONE
    }
}
