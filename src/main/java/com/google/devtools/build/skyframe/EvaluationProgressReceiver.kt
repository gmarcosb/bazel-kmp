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

import com.google.devtools.build.lib.concurrent.ThreadSafety

/** Receiver for various stages of the lifetime of a skyframe node evaluation.  */
@ThreadSafety.ThreadSafe
interface EvaluationProgressReceiver {
    /** The state of a node after it was evaluated.  */
    enum class EvaluationState(private val succeeded: Boolean, private val versionChanged: Boolean) {
        SUCCESS_VERSION_CHANGED(true, true),
        SUCCESS_VERSION_UNCHANGED(true, false),
        FAIL_VERSION_CHANGED(false, true),
        FAIL_VERSION_UNCHANGED(false, true);

        /**
         * Whether the node has a value.
         * 
         * 
         * If `false`, the node has only an error and no value.
         */
        fun succeeded(): Boolean {
            return succeeded
        }

        /**
         * Whether the node's [NodeEntry.getVersion] changed as a result of this evaluation.
         * 
         * 
         * If `true`, the node was built at the current version and its [ ][NodeEntry.getVersion] changed, either because it was built incrementally and changed or was
         * built as part of a clean build. Parents need to be rebuilt.
         * 
         * 
         * If `false`, the node's [NodeEntry.getVersion] did not change, either because
         * it was deemed up-to-date and not built or was built incrementally and evaluated to the same
         * value as its prior evaluation. Parents do not necessarily need to be rebuilt.
         */
        fun versionChanged(): Boolean {
            return versionChanged
        }

        companion object {
            fun get(valueMaybeWithMetadata: SkyValue?, versionChanged: Boolean): EvaluationState {
                val success = ValueWithMetadata.Companion.justValue(valueMaybeWithMetadata) != null
                if (versionChanged) {
                    return if (success) EvaluationState.SUCCESS_VERSION_CHANGED else EvaluationState.FAIL_VERSION_CHANGED
                } else {
                    return if (success) EvaluationState.SUCCESS_VERSION_UNCHANGED else EvaluationState.FAIL_VERSION_UNCHANGED
                }
            }
        }
    }

    /** Overall state of the node while it is being evaluated.  */
    enum class NodeState {
        /** The node is undergoing a dirtiness check and may be re-validated.  */
        CHECK_DIRTY,

        /** The node is prepping for evaluation.  */
        INITIALIZING_ENVIRONMENT,

        /** The node is in compute().  */
        COMPUTE,

        /** The node is done evaluation and committing the result.  */
        COMMIT,
    }

    /**
     * Notifies that the node for `skyKey` has been [marked][NodeEntry.markDirty] with the given [DirtyType].
     * 
     * 
     * May be called concurrently from multiple threads.
     * 
     * 
     * Only called after a successful [NodeEntry.markDirty] call: a call that returns a
     * non-null value.
     */
    fun dirtied(skyKey: SkyKey?, dirtyType: DirtyType?) {}

    /** Notifies that the node for `skyKey` was deleted.  */
    fun deleted(skyKey: SkyKey?) {}

    /**
     * Notifies that `skyKey` is about to get queued for evaluation.
     * 
     * 
     * Note that we don't guarantee that it actually got enqueued or will, only that if everything
     * "goes well" (e.g. no interrupts happen) it will.
     * 
     * 
     * This guarantee is intentionally vague to encourage writing robust implementations.
     */
    fun enqueueing(skyKey: SkyKey?) {}

    /**
     * Notifies that the node for `skyKey` is about to enter the given `nodeState`.
     * 
     * 
     * Notably, this includes [SkyFunction.compute] calls due to Skyframe restarts, but also
     * dirtiness checking and node completion.
     */
    fun stateStarting(skyKey: SkyKey?, nodeState: NodeState?) {}

    /**
     * Notifies that the node for `skyKey` is about to complete the given `nodeState`.
     * 
     * 
     * Always called symmetrically with [.stateStarting]}.
     */
    fun stateEnding(skyKey: SkyKey?, nodeState: NodeState?) {}

    /**
     * Notifies that the node for `skyKey` has been evaluated.
     * 
     * @param state the current state of the node for `skyKey`
     * @param newValue the node's value if [EvaluationState.versionChanged] and [     ][EvaluationState.succeeded], otherwise `null`
     * @param newError the node's error if it has one and [EvaluationState.versionChanged]
     * @param directDeps direct dependencies of `skyKey` if the node was just built, otherwise
     * `null`
     */
    fun evaluated(
        skyKey: SkyKey?,
        state: EvaluationState?,
        newValue: SkyValue?,
        newError: com.google.devtools.build.skyframe.ErrorInfo?,
        directDeps: GroupedDeps?
    ) {
    }

    /** Notifies that the node for `skyKey` has been change pruned.  */
    fun changePruned(skyKey: SkyKey?) {}

    companion object {
        /** A no-op [EvaluationProgressReceiver].  */
        @kotlin.jvm.JvmField
        val NULL: EvaluationProgressReceiver = object : EvaluationProgressReceiver {}
    }
}
