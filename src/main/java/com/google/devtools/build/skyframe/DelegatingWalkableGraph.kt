// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import com.google.devtools.build.skyframe.NodeEntry
import com.google.devtools.build.skyframe.QueryableGraph
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue
import com.google.devtools.build.skyframe.WalkableGraph
import java.util.HashMap

/**
 * [WalkableGraph] that looks nodes up in a [QueryableGraph].
 */
class DelegatingWalkableGraph(graph: QueryableGraph) : WalkableGraph {
    protected val graph: QueryableGraph

    init {
        this.graph = graph
    }

    override fun cancelLookups() {
        graph.cancelLookups()
    }

    @Throws(java.lang.InterruptedException::class)
    private fun getEntryForValue(key: SkyKey?): NodeEntry? {
        val entry: NodeEntry? =
            graph.get(null, com.google.devtools.build.skyframe.QueryableGraph.Reason.WALKABLE_GRAPH_VALUE, key)
        return if (entry != null && entry.isDone()) entry else null
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getValue(key: SkyKey?): SkyValue? {
        val entry: NodeEntry? = getEntryForValue(key)
        return if (entry == null) null else entry.getValue()
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getSuccessfulValues(keys: Iterable<out SkyKey?>?): MutableMap<SkyKey?, SkyValue?> {
        val batchGet: MutableMap<SkyKey?, out NodeEntry?> =
            getBatch(null, com.google.devtools.build.skyframe.QueryableGraph.Reason.WALKABLE_GRAPH_VALUE, keys)
        val result: MutableMap<SkyKey?, SkyValue?> =
            com.google.common.collect.Maps.newHashMapWithExpectedSize<SkyKey?, SkyValue?>(batchGet.size())
        for (entryPair in batchGet.entrySet()) {
            val value: SkyValue? = getValueFromNodeEntry(entryPair.getValue())
            if (value != null) {
                result.put(entryPair.getKey(), value)
            }
        }
        return result
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getMissingAndExceptions(keys: Iterable<SkyKey?>): MutableMap<SkyKey?, java.lang.Exception?> {
        val result: MutableMap<SkyKey?, java.lang.Exception?> = HashMap<SkyKey?, java.lang.Exception?>()
        val graphResult: MutableMap<SkyKey?, out NodeEntry?> =
            getBatch(null, com.google.devtools.build.skyframe.QueryableGraph.Reason.WALKABLE_GRAPH_VALUE, keys)
        for (key in keys) {
            val nodeEntry: NodeEntry? = graphResult.get(key)
            if (nodeEntry == null || !nodeEntry.isDone()) {
                result.put(key, null)
            } else {
                val errorInfo: com.google.devtools.build.skyframe.ErrorInfo? = nodeEntry.getErrorInfo()
                if (errorInfo != null) {
                    result.put(key, errorInfo.getException())
                }
            }
        }
        return result
    }

    @Throws(java.lang.InterruptedException::class)
    override fun isCycle(key: SkyKey?): Boolean {
        val entry: NodeEntry? = getEntryForValue(key)
        if (entry == null) {
            return false
        }
        val errorInfo: com.google.devtools.build.skyframe.ErrorInfo? = entry.getErrorInfo()
        return errorInfo != null && !errorInfo.getCycleInfo().isEmpty()
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getException(key: SkyKey?): java.lang.Exception? {
        val entry: NodeEntry? = getEntryForValue(key)
        if (entry == null) {
            return null
        }
        val errorInfo: com.google.devtools.build.skyframe.ErrorInfo? = entry.getErrorInfo()
        return if (errorInfo == null) null else errorInfo.getException()
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getDirectDeps(keys: Iterable<SkyKey?>?): MutableMap<SkyKey?, Iterable<SkyKey?>?> {
        val entries: MutableMap<SkyKey?, out NodeEntry?> =
            getBatch(null, com.google.devtools.build.skyframe.QueryableGraph.Reason.WALKABLE_GRAPH_DEPS, keys)
        val result: MutableMap<SkyKey?, Iterable<SkyKey?>?> = HashMap<SkyKey?, Iterable<SkyKey?>?>(entries.size())
        for (entry in entries.entrySet()) {
            // Note that the situation described in #getReverseDeps doesn't apply here. If the nodes for
            // `keys` are done, then their direct deps must be done too.
            com.google.common.base.Preconditions.checkState(entry.getValue().isDone(), entry)
            result.put(entry.getKey(), entry.getValue().getDirectDeps())
        }
        return result
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getDirectDeps(key: SkyKey?): Iterable<SkyKey?>? {
        val entry: NodeEntry? = getEntryForValue(key)
        com.google.common.base.Preconditions.checkNotNull<NodeEntry?>(entry, key)
        // Note that the situation described in #getReverseDeps doesn't apply here. If the node for
        // `key` is done, then its direct deps must be done too.
        com.google.common.base.Preconditions.checkState(
            entry.isDone(),
            "Node %s (with key %s) isn't done yet.",
            entry,
            key
        )
        return entry.getDirectDeps()
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getReverseDeps(keys: Iterable<out SkyKey?>?): MutableMap<SkyKey?, Iterable<SkyKey?>?> {
        val entries: MutableMap<SkyKey?, out NodeEntry?> =
            getBatch(null, com.google.devtools.build.skyframe.QueryableGraph.Reason.WALKABLE_GRAPH_RDEPS, keys)
        val result: MutableMap<SkyKey?, Iterable<SkyKey?>?> = HashMap<SkyKey?, Iterable<SkyKey?>?>(entries.size())
        for (entry in entries.entrySet()) {
            // SkyQuery may be operating on a Skyframe graph that contains more nodes and edges than its
            // universe. In this situation, Blaze's eager invalidation strategy may mean here we can
            // observe a rdep edge from a not-done node (because that node may have been invalidated but
            // not re-evaluated). Therefore, we tolerate this case gracefully.
            //
            // More generally, the fact that the Skyframe graph may be larger than SkyQuery's universe
            // means that SkyQuery may be traversing edges irrelevant for query evaluation.
            // TODO(bazel-team): Get rid of this wasted work. One approach is to hardcode the Skyframe
            // *type* graph structure, and follow only edges for relevant node types. This would work, but
            // is brittle so we'd want a strong regression testing story.
            if (entry.getValue().isDone()) {
                result.put(entry.getKey(), entry.getValue().getReverseDepsForDoneEntry())
            }
        }
        return result
    }

    @Throws(java.lang.InterruptedException::class)
    protected fun getBatch(
        requestor: SkyKey?,
        reason: com.google.devtools.build.skyframe.QueryableGraph.Reason?,
        keys: Iterable<out SkyKey?>?
    ): MutableMap<SkyKey?, out NodeEntry?> {
        return graph.getBatchMap(requestor, reason, keys)
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getValueAndRdeps(keys: Iterable<SkyKey?>?): MutableMap<SkyKey?, com.google.devtools.build.lib.util.Pair<SkyValue?, Iterable<SkyKey?>?>?> {
        val entries: MutableMap<SkyKey?, out NodeEntry?> =
            getBatch(
                null,
                com.google.devtools.build.skyframe.QueryableGraph.Reason.WALKABLE_GRAPH_VALUE_AND_RDEPS,
                keys
            )
        val result: MutableMap<SkyKey?, com.google.devtools.build.lib.util.Pair<SkyValue?, Iterable<SkyKey?>?>?> =
            com.google.common.collect.Maps.newHashMapWithExpectedSize<SkyKey?, com.google.devtools.build.lib.util.Pair<SkyValue?, Iterable<SkyKey?>?>?>(
                entries.size()
            )
        for (entry in entries.entrySet()) {
            // See comment in #getReverseDeps.
            if (entry.getValue().isDone()) {
                result.put(
                    entry.getKey(),
                    com.google.devtools.build.lib.util.Pair.Companion.of<SkyValue?, Iterable<SkyKey?>?>(
                        getValueFromNodeEntry(entry.getValue()),
                        entry.getValue().getReverseDepsForDoneEntry()
                    )
                )
            }
        }
        return result
    }

    val allKeysForTesting: com.google.common.collect.ImmutableSet<SkyKey?>?
        get() = graph.getAllKeysForTesting()

    companion object {
        @Throws(java.lang.InterruptedException::class)
        private fun getValueFromNodeEntry(entry: NodeEntry): SkyValue? {
            return if (entry.isDone()) entry.getValue() else null
        }
    }
}
