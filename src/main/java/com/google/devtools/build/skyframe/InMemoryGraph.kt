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

import com.google.devtools.build.skyframe.InMemoryGraphImpl
import com.google.devtools.build.skyframe.InMemoryGraphImpl.EdgelessInMemoryGraphImpl
import com.google.devtools.build.skyframe.InMemoryNodeEntry
import com.google.devtools.build.skyframe.NodeBatch
import com.google.devtools.build.skyframe.NodeEntry
import com.google.devtools.build.skyframe.ProcessableGraph
import com.google.devtools.build.skyframe.QueryableGraph
import com.google.devtools.build.skyframe.QueryableGraph.LookupHint
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue

/** [ProcessableGraph] that exposes the contents of the entire graph.  */
interface InMemoryGraph : ProcessableGraph {
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    override fun createIfAbsentBatch(
        requestor: SkyKey?,
        reason: com.google.devtools.build.skyframe.QueryableGraph.Reason?,
        keys: Iterable<out SkyKey?>?
    ): NodeBatch?

    override fun get(
        requestor: SkyKey?,
        reason: com.google.devtools.build.skyframe.QueryableGraph.Reason?,
        key: SkyKey?
    ): NodeEntry?

    override fun getBatch(
        requestor: SkyKey?,
        reason: com.google.devtools.build.skyframe.QueryableGraph.Reason?,
        keys: Iterable<out SkyKey?>?
    ): NodeBatch? {
        return NodeBatch { key: SkyKey? -> getBatchMap(requestor, reason, keys)!!.get(key) }
    }

    override fun getLookupHint(key: SkyKey?): LookupHint? {
        return LookupHint.INDIVIDUAL
    }

    override fun getBatchMap(
        requestor: SkyKey?,
        reason: com.google.devtools.build.skyframe.QueryableGraph.Reason?,
        keys: Iterable<out SkyKey?>?
    ): MutableMap<SkyKey?, out NodeEntry?>?

    /**
     * Returns a read-only live view of the nodes in the graph. All node are included. Dirty values
     * include their Node value. Values in error have a null value.
     */
    @kotlin.jvm.JvmField
    val values: MutableMap<SkyKey, SkyValue>?

    fun valuesSize(): Int {
        return this.values.size()
    }

    /**
     * Returns a read-only live view of the done values in the graph. Dirty, changed, and error values
     * are not present in the returned map
     */
    @kotlin.jvm.JvmField
    val doneValues: MutableMap<SkyKey, SkyValue>?

    /** Returns an unmodifiable collection of all nodes in the graph.  */
    @kotlin.jvm.JvmField
    val allNodeEntries: MutableCollection<InMemoryNodeEntry>?

    /** Applies the given consumer to each node in the graph, potentially in parallel.  */
    fun parallelForEach(consumer: java.util.function.Consumer<InMemoryNodeEntry?>?)

    /**
     * Removes the node entry associated with the given [SkyKey] from the graph if it is done.
     */
    fun removeIfDone(key: SkyKey?)

    /**
     * Cleans up [interning][com.google.devtools.build.lib.concurrent.PooledInterner.Pool] by moving objects to weak interners and uninstalling the current pools.
     * 
     * 
     * May destroy this graph. Only call when the graph is about to be thrown away.
     */
    fun cleanupInterningPools()

    /**
     * Returns the [InMemoryNodeEntry] for a given [SkyKey] if present in the graph.
     * Otherwise, returns null.
     */
    fun getIfPresent(key: SkyKey?): InMemoryNodeEntry?

    /**
     * Minimizes the size of the data structure backing the graph. May be costly to run (O(n)).
     * 
     * 
     * Must NOT be called concurrently with any other methods.
     * 
     * 
     * Useful after removing large numbers of nodes from the in-memory graph, and the data
     * structure used doesn't have automatic resizing (e.g. ConcurrentHashMap).
     * 
     * 
     * WARNING: Implementations have to take care of existing references into the data structure if
     * replaced by a new one (e.g. functions that close over the data structure).
     */
    fun shrinkNodeMap()

    companion object {
        /** Creates a new in-memory graph suitable for incremental builds.  */
        @kotlin.jvm.JvmStatic
        fun create(): InMemoryGraph {
            return InMemoryGraphImpl( /* usePooledInterning= */true)
        }

        @kotlin.jvm.JvmStatic
        fun create(usePooledInterning: Boolean): InMemoryGraph {
            return InMemoryGraphImpl(usePooledInterning)
        }

        /**
         * Creates a new in-memory graph that discards graph edges to save memory and cannot be used for
         * incremental builds.
         */
        @kotlin.jvm.JvmStatic
        fun createEdgeless(usePooledInterning: Boolean): InMemoryGraph {
            return EdgelessInMemoryGraphImpl(usePooledInterning)
        }
    }
}
