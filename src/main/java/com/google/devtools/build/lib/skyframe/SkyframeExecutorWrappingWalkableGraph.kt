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

import com.google.devtools.build.lib.skyframe.SkyframeExecutor
import com.google.devtools.build.skyframe.DelegatingWalkableGraph
import com.google.devtools.build.skyframe.MemoizingEvaluator
import com.google.devtools.build.skyframe.NodeEntry
import com.google.devtools.build.skyframe.QueryableGraph
import com.google.devtools.build.skyframe.QueryableGraph.LookupHint
import com.google.devtools.build.skyframe.SkyKey
import java.util.HashMap

/**
 * [com.google.devtools.build.skyframe.WalkableGraph] backed by a [SkyframeExecutor].
 */
class SkyframeExecutorWrappingWalkableGraph private constructor(evaluator: MemoizingEvaluator) :
    DelegatingWalkableGraph(
        object : QueryableGraph() {
            @Throws(java.lang.InterruptedException::class)
            override fun get(
                requestor: SkyKey?,
                reason: com.google.devtools.build.skyframe.QueryableGraph.Reason?,
                key: SkyKey?
            ): NodeEntry? {
                return evaluator.getExistingEntryAtCurrentlyEvaluatingVersion(key)
            }

            override fun getLookupHint(key: SkyKey?): LookupHint {
                return LookupHint.INDIVIDUAL
            }

            @Throws(java.lang.InterruptedException::class)
            override fun getBatchMap(
                requestor: SkyKey?,
                reason: com.google.devtools.build.skyframe.QueryableGraph.Reason?,
                keys: Iterable<out SkyKey?>
            ): MutableMap<SkyKey?, out NodeEntry?> {
                val result: MutableMap<SkyKey?, NodeEntry?> = HashMap<SkyKey?, NodeEntry?>()
                for (key in keys) {
                    val nodeEntry: NodeEntry? = get(requestor, reason, key)
                    if (nodeEntry != null) {
                        result.put(key, nodeEntry)
                    }
                }
                return result
            }
        }) {
    companion object {
        fun of(skyframeExecutor: SkyframeExecutor): SkyframeExecutorWrappingWalkableGraph {
            // TODO(janakr): Provide the graph in a more principled way.
            return SkyframeExecutorWrappingWalkableGraph(skyframeExecutor.getEvaluator())
        }
    }
}
