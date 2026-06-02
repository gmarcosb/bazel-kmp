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

import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe

/**
 * Read-only graph that exposes the dependents, dependencies (reverse dependents), and value and
 * exception (if any) of a given node.
 * 
 * 
 * Certain graph implementations can throw [InterruptedException] when trying to retrieve
 * node entries. Such exceptions should not be caught locally -- they should be allowed to propagate
 * up.
 */
@ThreadSafe
interface WalkableGraph {
    /**
     * Returns the value of the given key, or `null` if it has no value due to an error during
     * its computation or it is not done in the graph.
     * 
     * 
     * A node that is done in the graph must have either a non-null getValue, a non-null [ ][.getException], or a true [.isCycle].
     * 
     * 
     * These three methods should all be reading the same [ ][NodeEntry.getValueMaybeWithMetadata] value internally, so once that value is indirectly
     * retrieved via one of these methods, the others can read it for free. This is relevant for graph
     * implementations that may throw an [InterruptedException] on retrieving entries and value.
     */
    @Throws(java.lang.InterruptedException::class)
    fun getValue(key: SkyKey?): SkyValue?

    /**
     * Returns a map giving the values of the given keys for done keys that were successfully
     * computed. Or in other words, it filters out non-existent nodes, pending nodes and nodes that
     * produced an exception.
     */
    @Throws(java.lang.InterruptedException::class)
    fun getSuccessfulValues(keys: Iterable<out SkyKey?>?): MutableMap<SkyKey?, SkyValue?>?

    /**
     * Returns a map giving exceptions associated to the given keys for done keys. Keys not present in
     * the graph or whose nodes are not done will be present in the returned map, with null value. In
     * other words, if `key` is in {@param keys}, then the returned map will contain an entry
     * for `key` if and only if the node for `key` did *not* evaluate successfully
     * without error.
     */
    @Throws(java.lang.InterruptedException::class)
    fun getMissingAndExceptions(keys: Iterable<SkyKey?>?): MutableMap<SkyKey?, java.lang.Exception?>?

    /**
     * Returns the exception thrown when computing the node with the given key, if any. If the node
     * was computed successfully, depends on a cycle without any other error, or is not done in the
     * graph, returns null.
     */
    @Throws(java.lang.InterruptedException::class)
    fun getException(key: SkyKey?): java.lang.Exception?

    /**
     * Returns true if the node with the given `key` depends on a cycle. Returns false if the
     * node does not depend on a cycle, or is not done in the graph.
     */
    @Throws(java.lang.InterruptedException::class)
    fun isCycle(key: SkyKey?): Boolean

    /**
     * Returns a map giving the direct dependencies of the nodes with the given keys. A node for each
     * given key must be done in the graph if it exists.
     */
    @Throws(java.lang.InterruptedException::class)
    fun getDirectDeps(keys: Iterable<SkyKey?>?): MutableMap<SkyKey?, Iterable<SkyKey?>?>?

    /**
     * Returns the direct dependencies of the node with the given key. A node for that key must exist
     * in the graph and be done.
     */
    @Throws(java.lang.InterruptedException::class)
    fun getDirectDeps(key: SkyKey?): Iterable<SkyKey?>?

    /**
     * Returns a map giving the reverse dependencies of the nodes with the given keys. A node for each
     * given key must be done in the graph if it exists.
     */
    @Throws(java.lang.InterruptedException::class)
    fun getReverseDeps(keys: Iterable<out SkyKey?>?): MutableMap<SkyKey?, Iterable<SkyKey?>?>?

    /**
     * Returns a map giving the reverse dependencies of the nodes with the given keys as well as the
     * value
     */
    @Throws(java.lang.InterruptedException::class)
    fun getValueAndRdeps(keys: Iterable<SkyKey?>?): MutableMap<SkyKey?, com.google.devtools.build.lib.util.Pair<SkyValue?, Iterable<SkyKey?>?>?>?

    fun getAllKeysForTesting(): com.google.common.collect.ImmutableSet<SkyKey?>? {
        throw java.lang.UnsupportedOperationException()
    }

    /** Provides a WalkableGraph on demand after preparing it.  */
    interface WalkableGraphFactory {
        @Throws(java.lang.InterruptedException::class)
        fun prepareAndGet(
            roots: MutableSet<SkyKey?>?,
            evaluationContext: com.google.devtools.build.skyframe.EvaluationContext?
        ): EvaluationResult<SkyValue?>?
    }

    /**
     * Cancel all in-flight graph reads. This may be a no-op for many graph implementations, but is
     * particularly useful to clean up pending work when graph lookups consist of I/O operations or
     * RPCs.
     */
    fun cancelLookups() {}
}
