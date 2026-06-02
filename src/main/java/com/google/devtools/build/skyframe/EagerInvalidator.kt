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

import com.google.devtools.build.skyframe.DirtyAndInflightTrackingProgressReceiver
import com.google.devtools.build.skyframe.InMemoryGraph
import com.google.devtools.build.skyframe.InvalidatingNodeVisitor
import com.google.devtools.build.skyframe.InvalidatingNodeVisitor.DeletingInvalidationState
import com.google.devtools.build.skyframe.InvalidatingNodeVisitor.DeletingNodeVisitor
import com.google.devtools.build.skyframe.InvalidatingNodeVisitor.DirtyingNodeVisitor
import com.google.devtools.build.skyframe.QueryableGraph
import com.google.devtools.build.skyframe.SkyKey

/**
 * Utility class for performing eager invalidation on Skyframe graphs.
 * 
 * 
 * This is intended only for use in alternative `MemoizingEvaluator` implementations.
 */
object EagerInvalidator {
    /**
     * Deletes given values. The `traverseGraph` parameter controls whether this method deletes
     * (transitive) dependents of these nodes and relevant graph edges, or just the nodes themselves.
     * Deleting just the nodes is inconsistent unless the graph will not be used for incremental
     * builds in the future, but unfortunately there is a case where we delete nodes intra-build. As
     * long as the full upward transitive closure of the nodes is specified for deletion, the graph
     * remains consistent.
     */
    @Throws(java.lang.InterruptedException::class)
    fun delete(
        graph: InMemoryGraph?,
        diff: Iterable<SkyKey?>?,
        progressReceiver: DirtyAndInflightTrackingProgressReceiver?,
        state: DeletingInvalidationState,
        traverseGraph: Boolean
    ) {
        val visitor: DeletingNodeVisitor? =
            createDeletingVisitorIfNeeded(
                graph, diff, progressReceiver, state, traverseGraph
            )
        if (visitor != null) {
            visitor.run()
        }
    }

    @com.google.common.annotations.VisibleForTesting
    fun createDeletingVisitorIfNeeded(
        graph: InMemoryGraph?,
        diff: Iterable<SkyKey?>?,
        progressReceiver: DirtyAndInflightTrackingProgressReceiver?,
        state: DeletingInvalidationState,
        traverseGraph: Boolean
    ): DeletingNodeVisitor? {
        state.update(diff)
        return if (state.isEmpty())
            null
        else
            DeletingNodeVisitor(graph, progressReceiver, state, traverseGraph)
    }

    @com.google.common.annotations.VisibleForTesting
    fun createInvalidatingVisitorIfNeeded(
        graph: QueryableGraph?,
        diff: Iterable<SkyKey?>?,
        progressReceiver: DirtyAndInflightTrackingProgressReceiver?,
        state: com.google.devtools.build.skyframe.InvalidatingNodeVisitor.InvalidationState
    ): DirtyingNodeVisitor? {
        state.update(diff)
        return if (state.isEmpty()) null else DirtyingNodeVisitor(graph, progressReceiver, state)
    }

    /** Invalidates given values and their upward transitive closure in the graph if necessary.  */
    @Throws(java.lang.InterruptedException::class)
    fun invalidate(
        graph: QueryableGraph?,
        diff: Iterable<SkyKey?>?,
        progressReceiver: DirtyAndInflightTrackingProgressReceiver?,
        state: com.google.devtools.build.skyframe.InvalidatingNodeVisitor.InvalidationState
    ) {
        val visitor: DirtyingNodeVisitor? =
            createInvalidatingVisitorIfNeeded(graph, diff, progressReceiver, state)
        if (visitor != null) {
            visitor.run()
        }
    }
}
