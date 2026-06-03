// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.skyframe.EvaluationResult

/**
 * A [SkyFunction.Environment] backed by a [SkyframeExecutor] that can be used to
 * evaluate arbitrary [SkyKey]s for testing.
 */
class SkyFunctionEnvironmentForTesting
    (eventHandler: ExtendedEventHandler?, skyframeExecutor: SkyframeExecutor) :
    AbstractSkyFunctionEnvironmentForTesting() {
    private val eventHandler: ExtendedEventHandler?
    private val skyframeExecutor: SkyframeExecutor

    init {
        this.eventHandler = eventHandler
        this.skyframeExecutor = skyframeExecutor
    }

    override fun getValueOrUntypedExceptions(
        depKeys: Iterable<out SkyKey?>
    ): com.google.common.collect.ImmutableMap<SkyKey?, ValueOrUntypedException?> {
        val resultMap: com.google.common.collect.ImmutableMap.Builder<SkyKey?, ValueOrUntypedException?> =
            com.google.common.collect.ImmutableMap.builder<SkyKey?, ValueOrUntypedException?>()
        val evaluationResult: EvaluationResult<SkyValue?> =
            skyframeExecutor.evaluateSkyKeys(eventHandler, depKeys,  /* keepGoing= */true)
        for (depKey in com.google.common.collect.ImmutableSet.copyOf(depKeys)) {
            resultMap.put(depKey, ValueOrUntypedException.ofValueUntyped(evaluationResult.get(depKey)))
        }
        return resultMap.buildOrThrow()
    }

    val listener: ExtendedEventHandler?
        get() = eventHandler
}
