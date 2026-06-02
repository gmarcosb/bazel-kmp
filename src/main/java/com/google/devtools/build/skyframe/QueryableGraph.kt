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

import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe

/**
 * A graph that exposes its entries and structure, for use by classes that must traverse it.
 * 
 * 
 * Certain graph implementations can throw [InterruptedException] when trying to retrieve
 * node entries. Such exceptions should not be caught locally -- they should be allowed to propagate
 * up.
 */
@ThreadSafe
interface QueryableGraph {
    /**
     * Returns the node with the given `key`, or `null` if the node does not exist.
     * 
     * @param requestor if non-`null`, the node on behalf of which `key` is being
     * requested.
     * @param reason the reason the node is being requested.
     */
    @Throws(java.lang.InterruptedException::class)
    fun get(requestor: SkyKey?, reason: Reason?, key: SkyKey?): NodeEntry?

    /**
     * Fetches all the given nodes. Returns a [NodeBatch] `b` such that, for all `k`
     * in `keys`, `b.get(k) == get(k)`.
     * 
     * 
     * Prefer calling this method over [.getBatchMap] if it is not necessary to represent the
     * result as a [Map], as it may be significantly more efficient.
     * 
     * @param requestor if non-`null`, the node on behalf of which the given `keys` are
     * being requested.
     * @param reason the reason the nodes are being requested.
     */
    @Throws(java.lang.InterruptedException::class)
    fun getBatch(
        requestor: SkyKey?, reason: Reason?, keys: Iterable<out SkyKey?>?
    ): NodeBatch? {
        return NodeBatch { key: SkyKey? -> getBatchMap(requestor, reason, keys)!!.get(key) }
    }

    /** A hint about the most efficient way to look up a key in the graph.  */
    enum class LookupHint {
        INDIVIDUAL,
        BATCH
    }

    /**
     * Hints to the caller about the most efficient way to look up a key in this graph.
     * 
     * 
     * A return of [LookupHint.INDIVIDUAL] indicates that the given key can efficiently be
     * looked up by calling [.get]. In such a case, it is not worth the effort to aggregate the
     * key into a collection with other keys for a [.getBatch] call.
     * 
     * 
     * A return of [LookupHint.BATCH] indicates that the given key should ideally be
     * requested with other keys as part of a call to [.getBatch]. This may be the case if, for
     * example, the corresponding node is stored remotely, and requesting keys in a single batch
     * reduces trips to remote storage.
     */
    fun getLookupHint(key: SkyKey?): LookupHint?

    /**
     * A version of [.getBatch] that returns an [InterruptibleSupplier] to possibly
     * retrieve the results later.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun getBatchAsync(
        requestor: SkyKey?, reason: Reason?, keys: Iterable<out SkyKey?>?
    ): InterruptibleSupplier<NodeBatch?> {
        return MemoizingInterruptibleSupplier.of({ getBatch(requestor, reason, keys) })
    }

    /**
     * Fetches all the given nodes. Returns a map `m` such that, for all `k` in `keys`, `m.get(k) == get(k)` and `!m.containsKey(k)` iff `get(k) == null`.
     * 
     * 
     * Prefer calling [.getBatch] over this method if it is not necessary to represent the
     * result as a [Map], as it may be significantly more efficient.
     * 
     * @param requestor if non-`null`, the node on behalf of which the given `keys` are
     * being requested.
     * @param reason the reason the nodes are being requested.
     */
    @Throws(java.lang.InterruptedException::class)
    fun getBatchMap(
        requestor: SkyKey?, reason: Reason?, keys: Iterable<out SkyKey?>?
    ): MutableMap<SkyKey?, out NodeEntry?>?

    /**
     * Identical to [.getBatchMap], except that it includes a parameter for a maximum
     * deserialization limit per-node (in bytes). If the serialized form of any fetched node read from
     * storage exceeds this limit, then this method throws [NodeEntryTooBigException].
     * 
     * 
     * Note that not every subclass of [QueryableGraph] supports this method, especially as
     * not all graphs use node serialization. This is identical to [.getBatchMap] if this graph
     * does not support limits.
     */
    @Throws(java.lang.InterruptedException::class, NodeEntryTooBigException::class)
    fun getBatchMapWithSizeLimit(
        requestor: SkyKey?,
        reason: Reason?,
        keys: Iterable<out SkyKey?>?,
        nodeSizeLimitInBytes: Long
    ): MutableMap<SkyKey?, out NodeEntry?>? {
        return getBatchMap(requestor, reason, keys)
    }

    /**
     * A version of [.getBatchMap] that returns an [InterruptibleSupplier] to possibly
     * retrieve the results later.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun getBatchMapAsync(
        requestor: SkyKey?, reason: Reason?, keys: Iterable<out SkyKey?>?
    ): InterruptibleSupplier<MutableMap<SkyKey?, out NodeEntry?>?> {
        return MemoizingInterruptibleSupplier.of({ getBatchMap(requestor, reason, keys) })
    }

    /**
     * Optimistically prefetches dependencies.
     * 
     * @param requestor the key whose deps to fetch
     * @param oldDeps deps from the previous build
     * @param previouslyRequestedDeps deps that have already been requested during this build and
     * should not be prefetched because they will be subsequently fetched anyway
     * @return `previouslyRequestedDeps` as a set if the implementation called [     ][GroupedDeps.toSet] (so that the caller may reuse it), otherwise `null`
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(java.lang.InterruptedException::class)
    fun prefetchDeps(
        requestor: SkyKey?, oldDeps: MutableSet<SkyKey?>?, previouslyRequestedDeps: GroupedDeps?
    ): com.google.common.collect.ImmutableSet<SkyKey?>? {
        return null
    }

    fun getAllKeysForTesting(): com.google.common.collect.ImmutableSet<SkyKey?>? {
        throw java.lang.UnsupportedOperationException()
    }

    /**
     * Cancel all in-flight graph lookups. This may be a no-op for many graph implementations, but is
     * particularly useful to clean up pending work when graph lookups consist of I/O operations or
     * RPCs.
     */
    fun cancelLookups() {}

    /**
     * The reason that a node is being looked up in the Skyframe graph.
     * 
     * 
     * Alternate graph implementations may wish to make use of this information.
     */
    enum class Reason {
        /**
         * The node is being fetched in order to see if it needs to be evaluated or because it was just
         * evaluated, but *not* because it was just requested during evaluation of a SkyFunction (see
         * [.DEP_REQUESTED]).
         */
        PRE_OR_POST_EVALUATION,

        /**
         * The node is being looked up as part of the prefetch step before evaluation of a SkyFunction.
         */
        PREFETCH,

        /**
         * The node is being fetched because it is about to be evaluated, but *not* because it was just
         * requested during evaluation of a SkyFunction (see [.DEP_REQUESTED]).
         */
        EVALUATION,

        /** The node is being looked up because it was requested during evaluation of a SkyFunction.  */
        DEP_REQUESTED,

        /** The node is being looked up during the invalidation phase of Skyframe evaluation.  */
        INVALIDATION,

        /** The node is being looked up during the cycle checking phase of Skyframe evaluation.  */
        CYCLE_CHECKING,

        /** The node is being looked up so that an rdep can be added to it.  */
        RDEP_ADDITION,

        /** The node is being looked up so that an rdep can be removed from it.  */
        RDEP_REMOVAL,

        /** The node is being looked up for any graph clean-up effort that may be necessary.  */
        CLEAN_UP,

        /** The node is being looked up so it can be enqueued for evaluation or change pruning.  */
        ENQUEUING_CHILD,

        /** The node is being looked up so that it can be signaled that a dependency is now complete.  */
        SIGNAL_DEP,

        /**
         * The node is being looking up as part of the error bubbling phase of fail-fast Skyframe
         * evaluation.
         */
        ERROR_BUBBLING,

        /** The node is being looked up merely for an existence check.  */
        EXISTENCE_CHECKING,

        /** The node is being looked up merely to see if it is done or not.  */
        DONE_CHECKING,

        /** The node is being looked up so that it can be [rewound][DirtyType.REWIND].  */
        REWINDING,

        /**
         * The node is being looked up to service [WalkableGraph.getValue], [ ][WalkableGraph.getException], [WalkableGraph.getMissingAndExceptions], or [ ][WalkableGraph.getSuccessfulValues].
         */
        WALKABLE_GRAPH_VALUE,

        /** The node is being looked up to service [WalkableGraph.getDirectDeps].  */
        WALKABLE_GRAPH_DEPS,

        /** The node is being looked up to service [WalkableGraph.getReverseDeps].  */
        WALKABLE_GRAPH_RDEPS,

        /** The node is being looked up to service [WalkableGraph.getValueAndRdeps].  */
        WALKABLE_GRAPH_VALUE_AND_RDEPS,

        /** The node is being looked up to service another "graph lookup" function.  */
        WALKABLE_GRAPH_OTHER,

        /** The node is being looked up to vendor external repos from its dependencies.  */
        VENDOR_EXTERNAL_REPOS,

        /** Some other reason than one of the above that needs the node's value and deps.  */
        OTHER_NEEDING_VALUE_AND_DEPS,

        /** Some other reason than one of the above that needs the node's reverse deps.  */
        OTHER_NEEDING_REVERSE_DEPS,

        /** Some other reason than one of the above that needs the node's value and reverse deps.  */
        OTHER_NEEDING_VALUE_AND_REVERSE_DEPS,

        /** Some other reason than one of the above.  */
        OTHER;

        fun isWalkable(): Boolean {
            return this == com.google.devtools.build.skyframe.QueryableGraph.Reason.WALKABLE_GRAPH_VALUE || this == com.google.devtools.build.skyframe.QueryableGraph.Reason.WALKABLE_GRAPH_DEPS || this == com.google.devtools.build.skyframe.QueryableGraph.Reason.WALKABLE_GRAPH_RDEPS || this == com.google.devtools.build.skyframe.QueryableGraph.Reason.WALKABLE_GRAPH_VALUE_AND_RDEPS || this == com.google.devtools.build.skyframe.QueryableGraph.Reason.WALKABLE_GRAPH_OTHER
        }
    }

    /**
     * An exception thrown if the serialized form of a node read from storage exceeds the limit as set
     * by the limit parameter to [.getBatchMapWithSizeLimit].
     */
    class NodeEntryTooBigException(key: SkyKey?) : java.lang.Exception() {
        private val key: SkyKey?

        init {
            this.key = key
        }

        /** Returns the [SkyKey] of the node which violated the size limit.  */
        fun getSkyKey(): SkyKey? {
            return key
        }
    }
}
