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
import com.google.devtools.build.skyframe.SkyFunctionName
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue
import com.google.devtools.build.skyframe.SkyframeGraphStatsEvent.EvaluationStats
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A delegating [InflightTrackingProgressReceiver] that tracks both inflight and dirty keys.
 */
class DirtyAndInflightTrackingProgressReceiver(progressReceiver: EvaluationProgressReceiver?) :
    InflightTrackingProgressReceiver {
    protected val progressReceiver: EvaluationProgressReceiver
    private val dirtyKeys: MutableSet<SkyKey?> = com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()
    private var inflightKeys: MutableSet<SkyKey?> = com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()
    private var unsuccessfullyRewoundKeys: MutableSet<SkyKey?> =
        com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()

    // Nodes that were dirtied because one of their transitive dependencies changed
    private val dirtied: com.google.common.collect.ConcurrentHashMultiset<SkyFunctionName?>

    // Nodes that were dirtied because they themselves changed (for example, a leaf node that
    // represents a file and that changed between builds)
    private val changed: com.google.common.collect.ConcurrentHashMultiset<SkyFunctionName?>

    // Nodes that were built and found different from the previous version
    private val built: com.google.common.collect.ConcurrentHashMultiset<SkyFunctionName?>

    // Nodes that were built and found to be same as the previous version
    private val cleaned: com.google.common.collect.ConcurrentHashMultiset<SkyFunctionName?>

    // Nodes that were computed during the build
    private val evaluated: com.google.common.collect.ConcurrentHashMultiset<SkyFunctionName?>

    init {
        this.progressReceiver =
            com.google.common.base.Preconditions.checkNotNull<EvaluationProgressReceiver>(progressReceiver)
        this.dirtied = createMultiset()
        this.changed = createMultiset()
        this.built = createMultiset()
        this.cleaned = createMultiset()
        this.evaluated = createMultiset()
    }

    override fun injected(skyKey: SkyKey?) {
        // This node was never evaluated, but is now clean and need not be re-evaluated.
        inflightKeys.remove(skyKey)
        removeFromDirtySet(skyKey)
    }

    override fun dirtied(skyKey: SkyKey, dirtyType: DirtyType) {
        progressReceiver.dirtied(skyKey, dirtyType)
        addToDirtySet(skyKey, dirtyType)

        when (dirtyType) {
            DirtyType.DIRTY -> dirtied.add(skyKey.functionName())
            DirtyType.CHANGE -> changed.add(skyKey.functionName())
            DirtyType.REWIND -> {}
        }
    }

    override fun deleted(skyKey: SkyKey?) {
        progressReceiver.deleted(skyKey)
        // This key was removed from the graph, so no longer needs to be marked as dirty.
        removeFromDirtySet(skyKey)
    }

    override fun enqueueing(skyKey: SkyKey?) {
        enqueueing(skyKey, false)
    }

    private fun enqueueing(skyKey: SkyKey?, afterError: Boolean) {
        // We unconditionally add the key to the set of in-flight nodes even if evaluation is never
        // scheduled, because we still want to remove the previously created NodeEntry from the graph.
        // Otherwise we would leave the graph in a weird state (wasteful garbage in the best case and
        // inconsistent in the worst case).
        val newlyEnqueued = inflightKeys.add(skyKey)
        if (newlyEnqueued) {
            // All nodes enqueued for evaluation will be either verified clean, re-evaluated, or cleaned
            // up after being in-flight when an error happens in nokeep_going mode or in the event of an
            // interrupt. In any of these cases, they won't be dirty anymore. Note that we don't remove
            // from unsuccessfullyRewoundKeys here - that is only done when the key completes
            // successfully.
            dirtyKeys.remove(skyKey)
            if (!afterError) {
                // Only tell the external listener the node was enqueued if no there was neither an error
                // or interrupt.
                progressReceiver.enqueueing(skyKey)
            }
        }
    }

    override fun changePruned(skyKey: SkyKey?) {
        progressReceiver.changePruned(skyKey)
    }

    /**
     * Called when a node was requested to be enqueued but wasn't because either an interrupt or an
     * error (in nokeep_going mode) had occurred.
     */
    override fun enqueueAfterError(skyKey: SkyKey?) {
        enqueueing(skyKey, true)
    }

    override fun stateStarting(skyKey: SkyKey?, nodeState: NodeState?) {
        progressReceiver.stateStarting(skyKey, nodeState)
    }

    override fun stateEnding(skyKey: SkyKey, nodeState: NodeState?) {
        progressReceiver.stateEnding(skyKey, nodeState)
        if (nodeState == NodeState.COMPUTE) {
            evaluated.add(skyKey.functionName())
        }
    }

    override fun evaluated(
        skyKey: SkyKey,
        state: EvaluationState,
        newValue: SkyValue?,
        newError: com.google.devtools.build.skyframe.ErrorInfo?,
        directDeps: GroupedDeps?
    ) {
        progressReceiver.evaluated(skyKey, state, newValue, newError, directDeps)

        // This key was either built or marked clean, so we can remove it from both the dirty and
        // inflight nodes.
        inflightKeys.remove(skyKey)

        if (state.succeeded()) {
            removeFromDirtySet(skyKey)
        } else {
            // Leave unsuccessful keys in unsuccessfullyRewoundKeys. Only remove them from dirtyKeys.
            dirtyKeys.remove(skyKey)
        }

        if (directDeps == null) {
            // In this case, no actual evaluation work was done so let's not record it.
        } else if (state.versionChanged()) {
            built.add(skyKey.functionName(), 1)
        } else {
            cleaned.add(skyKey.functionName(), 1)
        }
    }

    /** Returns if the key is enqueued for evaluation.  */
    override fun isInflight(skyKey: SkyKey?): Boolean {
        return inflightKeys.contains(skyKey)
    }

    override fun removeFromInflight(skyKey: SkyKey?) {
        inflightKeys.remove(skyKey)
    }

    val andClearInflightKeys: MutableSet<SkyKey>?
        get() {
            val keys: MutableSet<SkyKey?>? = inflightKeys
            inflightKeys = com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()
            return keys
        }

    val andClearUnsuccessfullyRewoundKeys: MutableSet<SkyKey>?
        /**
         * Returns the set of all keys that were [rewound][DirtyType.REWIND] but did not
         * complete successfully, and resets the set to empty.
         * 
         * 
         * The returned set includes keys that were rewound and were either:
         * 
         * 
         *  * not yet enqueued
         *  * enqueued but not evaluated
         *  * evaluated to an error
         * 
         */
        get() {
            val keys: MutableSet<SkyKey?>? = unsuccessfullyRewoundKeys
            unsuccessfullyRewoundKeys = com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()
            return keys
        }

    val unenqueuedDirtyKeys: com.google.common.collect.ImmutableSet<SkyKey?>
        /**
         * Returns the set of all dirty keys that have not been enqueued. This is useful for garbage
         * collection, where we would not want to remove dirty nodes that are needed for evaluation (in
         * the downward transitive closure of the set of the evaluation's top level nodes).
         */
        get() = com.google.common.collect.ImmutableSet.copyOf<SkyKey?>(dirtyKeys)

    private fun addToDirtySet(skyKey: SkyKey?, dirtyType: DirtyType?) {
        if (dirtyType == DirtyType.REWIND) {
            unsuccessfullyRewoundKeys.add(skyKey)
        } else {
            dirtyKeys.add(skyKey)
        }
    }

    private fun removeFromDirtySet(skyKey: SkyKey?) {
        // A key will never be present in both sets because EvaluationProgressReceiver#dirtied is only
        // called after successful NodeEntry#markDirty calls, i.e. a call that transitioned the node
        // from done to dirty.
        if (!dirtyKeys.remove(skyKey)) {
            unsuccessfullyRewoundKeys.remove(skyKey)
        }
    }

    fun aggregateAndReset(): EvaluationStats {
        val result: EvaluationStats =
            EvaluationStats(
                fromMultiset(dirtied),
                fromMultiset(changed),
                fromMultiset(built),
                fromMultiset(cleaned),
                fromMultiset(evaluated)
            )
        dirtied.clear()
        changed.clear()
        built.clear()
        cleaned.clear()
        evaluated.clear()
        return result
    }

    companion object {
        private fun createMultiset(): com.google.common.collect.ConcurrentHashMultiset<SkyFunctionName?> {
            return com.google.common.collect.ConcurrentHashMultiset.create<SkyFunctionName?>(
                ConcurrentHashMap<SkyFunctionName?, AtomicInteger?>(
                    java.lang.Runtime.getRuntime().availableProcessors(), 0.75f
                )
            )
        }

        private fun fromMultiset(
            s: com.google.common.collect.ConcurrentHashMultiset<SkyFunctionName?>
        ): com.google.common.collect.ImmutableMap<SkyFunctionName?, Int?> {
            return s.entrySet().stream()
                .collect(TODO("Cannot convert element")) < com.google.common.collect.Multiset.Entry<SkyFunctionName>
            TODO(
                """
                |Cannot convert element
                |With text:
                |SkyFunctionName, Integer>toImmutableMap(Multiset.Entry::getElement, Multiset.Entry::getCount)
                """.trimMargin()
            )
        }
    }
}
