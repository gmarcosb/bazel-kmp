// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.util.TestType
import com.google.devtools.build.skyframe.AbstractInMemoryMemoizingEvaluator
import com.google.devtools.build.skyframe.Differencer
import com.google.devtools.build.skyframe.DirtyAndInflightTrackingProgressReceiver
import com.google.devtools.build.skyframe.EmittedEventState
import com.google.devtools.build.skyframe.EvaluationProgressReceiver
import com.google.devtools.build.skyframe.GraphInconsistencyReceiver
import com.google.devtools.build.skyframe.InMemoryGraph
import com.google.devtools.build.skyframe.MemoizingEvaluator.GraphTransformerForTesting
import com.google.devtools.build.skyframe.SkyFunction
import com.google.devtools.build.skyframe.SkyFunctionName

/**
 * An in-memory [MemoizingEvaluator] that uses the eager invalidation strategy. This class is,
 * by itself, not thread-safe. Neither is it thread-safe to use this class in parallel with any of
 * the returned graphs. However, it is allowed to access the graph from multiple threads as long as
 * that does not happen in parallel with an [.evaluate] call.
 * 
 * 
 * This memoizing evaluator uses a monotonically increasing [IntVersion] for incremental
 * evaluations and [Version.constant] for non-incremental evaluations.
 */
class InMemoryMemoizingEvaluator @kotlin.jvm.JvmOverloads constructor(
    skyFunctions: MutableMap<SkyFunctionName?, SkyFunction?>,
    differencer: Differencer?,
    progressReceiver: EvaluationProgressReceiver? = EvaluationProgressReceiver.Companion.NULL,
    graphInconsistencyReceiver: GraphInconsistencyReceiver? = GraphInconsistencyReceiver.Companion.THROWING,
    eventFilter: com.google.devtools.build.skyframe.EventFilter? = com.google.devtools.build.skyframe.EventFilter.Companion.FULL_STORAGE,
    emittedEventState: EmittedEventState? = EmittedEventState(),
    keepEdges: Boolean = true,
    usePooledInterning: Boolean = true
) : AbstractInMemoryMemoizingEvaluator(
    com.google.common.collect.ImmutableMap.copyOf<SkyFunctionName?, SkyFunction?>(skyFunctions),
    differencer,
    DirtyAndInflightTrackingProgressReceiver(progressReceiver),
    eventFilter,
    emittedEventState,
    graphInconsistencyReceiver,
    keepEdges,
    com.google.devtools.build.skyframe.Version.Companion.minimal()
) {
    // Not final only for testing.
    private var graph: InMemoryGraph?

    init {
        this.graph =
            if (keepEdges)
                InMemoryGraph.Companion.create(usePooledInterning)
            else
                InMemoryGraph.Companion.createEdgeless(usePooledInterning)
    }

    override fun injectGraphTransformerForTesting(transformer: GraphTransformerForTesting) {
        com.google.common.base.Preconditions.checkState(TestType.Companion.isInTest())
        this.graph = transformer.transform(this.graph)
    }

    val inMemoryGraph: InMemoryGraph?
        get() = graph

    @get:com.google.common.annotations.VisibleForTesting
    val skyFunctionsForTesting: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>?
        get() = skyFunctions
}
