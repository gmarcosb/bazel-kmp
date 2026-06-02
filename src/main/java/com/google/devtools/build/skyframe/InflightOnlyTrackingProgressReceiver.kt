// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.skyframe.EvaluationProgressReceiver
import com.google.devtools.build.skyframe.EvaluationProgressReceiver.EvaluationState
import com.google.devtools.build.skyframe.EvaluationProgressReceiver.NodeState
import com.google.devtools.build.skyframe.GroupedDeps
import com.google.devtools.build.skyframe.InflightTrackingProgressReceiver
import com.google.devtools.build.skyframe.NodeEntry.DirtyType
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue

/**
 * A delegating [InflightTrackingProgressReceiver] that tracks inflight keys but not dirty
 * keys.
 * 
 * 
 * Suitable for non-incremental evaluations or evaluators that do not support deletion of dirty
 * nodes.
 */
class InflightOnlyTrackingProgressReceiver(progressReceiver: EvaluationProgressReceiver?) :
    InflightTrackingProgressReceiver {
    protected val progressReceiver: EvaluationProgressReceiver
    private var inflightKeys: MutableSet<SkyKey?> = com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()

    init {
        this.progressReceiver =
            com.google.common.base.Preconditions.checkNotNull<EvaluationProgressReceiver>(progressReceiver)
    }

    /** Called when a node is injected into the graph, and not evaluated.  */
    override fun injected(skyKey: SkyKey?) {
        // This node was never evaluated, but is now clean and need not be re-evaluated.
        inflightKeys.remove(skyKey)
    }

    override fun dirtied(skyKey: SkyKey?, dirtyType: DirtyType?) {
        progressReceiver.dirtied(skyKey, dirtyType)
    }

    override fun deleted(skyKey: SkyKey?) {
        progressReceiver.deleted(skyKey)
    }

    override fun enqueueing(skyKey: SkyKey?) {
        if (inflightKeys.add(skyKey)) {
            // Only tell the external listener the node was enqueued if no there was neither an error
            // nor interrupt.
            progressReceiver.enqueueing(skyKey)
        }
    }

    override fun enqueueAfterError(skyKey: SkyKey?) {
        inflightKeys.add(skyKey)
    }

    override fun stateStarting(skyKey: SkyKey?, nodeState: NodeState?) {
        progressReceiver.stateStarting(skyKey, nodeState)
    }

    override fun stateEnding(skyKey: SkyKey?, nodeState: NodeState?) {
        progressReceiver.stateEnding(skyKey, nodeState)
    }

    override fun evaluated(
        skyKey: SkyKey?,
        state: EvaluationState?,
        newValue: SkyValue?,
        newError: com.google.devtools.build.skyframe.ErrorInfo?,
        directDeps: GroupedDeps?
    ) {
        progressReceiver.evaluated(skyKey, state, newValue, newError, directDeps)

        // This key was either built or marked clean, so we can remove it from both the dirty and
        // inflight nodes.
        inflightKeys.remove(skyKey)
    }

    override fun changePruned(skyKey: SkyKey?) {
        progressReceiver.changePruned(skyKey)
    }

    /** Returns if the key is enqueued for evaluation.  */
    override fun isInflight(skyKey: SkyKey?): Boolean {
        return inflightKeys.contains(skyKey)
    }

    override fun removeFromInflight(skyKey: SkyKey?) {
        inflightKeys.remove(skyKey)
    }

    @get:com.google.errorprone.annotations.CanIgnoreReturnValue
    val andClearInflightKeys: MutableSet<SkyKey>?
        /** Returns the set of all keys that are enqueued for evaluation, and resets the set to empty.  */
        get() {
            val keys: MutableSet<SkyKey?>? = inflightKeys
            inflightKeys = com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()
            return keys
        }
}
