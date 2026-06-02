// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.skyframe

/**
 * Postable transporting data about the size/shape of the Skyframe graph. Note that the graph may
 * depend on the sequence of Bazel invocations prior to this one, not just the current one.
 */
class SkyframeGraphStatsEvent internal constructor(
    private val graphSize: Int,
    private val evaluationStats: EvaluationStats?
) : Postable {
    /** Data about the Skyframe evaluations that happened during this command.  */
    class EvaluationStats(
        dirtied: com.google.common.collect.ImmutableMap<SkyFunctionName?, Int?>?,
        changed: com.google.common.collect.ImmutableMap<SkyFunctionName?, Int?>?,
        built: com.google.common.collect.ImmutableMap<SkyFunctionName?, Int?>?,
        cleaned: com.google.common.collect.ImmutableMap<SkyFunctionName?, Int?>?,
        evaluated: com.google.common.collect.ImmutableMap<SkyFunctionName?, Int?>?
    ) {
        val dirtied: com.google.common.collect.ImmutableMap<SkyFunctionName?, Int?>?
        val changed: com.google.common.collect.ImmutableMap<SkyFunctionName?, Int?>?
        val built: com.google.common.collect.ImmutableMap<SkyFunctionName?, Int?>?
        val cleaned: com.google.common.collect.ImmutableMap<SkyFunctionName?, Int?>?
        val evaluated: com.google.common.collect.ImmutableMap<SkyFunctionName?, Int?>?

        init {
            this.dirtied = dirtied
            this.changed = changed
            this.built = built
            this.cleaned = cleaned
            this.evaluated = evaluated
        }
    }

    fun getGraphSize(): Int {
        return graphSize
    }

    fun getEvaluationStats(): EvaluationStats? {
        return evaluationStats
    }
}
