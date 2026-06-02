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
 * Interface between a single version of the graph and the evaluator. Supports mutation of that
 * single version of the graph.
 * 
 * 
 * Certain graph implementations can throw [InterruptedException] when trying to retrieve
 * node entries. Such exceptions should not be caught locally -- they should be allowed to propagate
 * up.
 * 
 * 
 * This class is not intended for direct use, and is only exposed as public for use in evaluation
 * implementations outside of this package.
 */
@ThreadSafe
interface ProcessableGraph : QueryableGraph {
    /** Remove the value with given name from the graph.  */
    fun remove(key: SkyKey?)

    /**
     * Like [QueryableGraph.getBatch], except creates a new node for each key not already
     * present in the graph.
     * 
     * 
     * By the time this method returns, nodes are guaranteed to have been created if necessary for
     * each requested key. It is not necessary to call [NodeBatch.get] to trigger node creation.
     * 
     * 
     * Calling [NodeBatch.get] on the returned batch will never return `null` for any
     * key in `keys`. Even if there is an intervening call to [.remove], the call to
     * [NodeBatch.get] will re-create a [NodeEntry] if necessary.
     * 
     * @param requestor if non-`null`, the node on behalf of which the given `keys` are
     * being requested.
     * @param reason the reason the nodes are being requested.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(java.lang.InterruptedException::class)
    fun createIfAbsentBatch(
        requestor: SkyKey?,
        reason: com.google.devtools.build.skyframe.QueryableGraph.Reason?,
        keys: Iterable<out SkyKey?>?
    ): NodeBatch?

    /**
     * Like [QueryableGraph.getBatchAsync], except creates a new node for each key not already
     * present in the graph. Thus, calling [NodeBatch.get] on the returned batch will never
     * return `null` for any of the requested `keys`.
     * 
     * @param requestor if non-`null`, the node on behalf of which the given `keys` are
     * being requested.
     * @param reason the reason the nodes are being requested.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun createIfAbsentBatchAsync(
        requestor: SkyKey?, reason: com.google.devtools.build.skyframe.QueryableGraph.Reason?, keys: Iterable<SkyKey?>?
    ): InterruptibleSupplier<NodeBatch?> {
        return MemoizingInterruptibleSupplier.of({ createIfAbsentBatch(requestor, reason, keys) })
    }

    /**
     * Cancel all in-flight graph lookups. This may be a no-op for many graph implementations, but is
     * particularly useful to clean up pending work when graph lookups consist of I/O operations or
     * RPCs.
     */
    override fun cancelLookups() {}

    /**
     * Optional optimization: graph may use internal knowledge to filter out keys in `deps` that
     * have not been recomputed since the last computation of `parent`. When determining if
     * `parent` needs to be re-evaluated, this may be used to avoid unnecessary graph accesses.
     * 
     * 
     * If this graph partakes in the optional optimization, returns deps that may have new values
     * since the node of `parent` was last computed, and therefore which may force re-evaluation
     * of the node of `parent`. Otherwise, returns [DepsReport.NO_INFORMATION].
     * 
     * @param parent the key in [NodeEntry.LifecycleState.CHECK_DEPENDENCIES]
     * @param deps the [next dirty dep group][NodeEntry.getNextDirtyDirectDeps] of `parent`; only called when all previous dep groups were clean, so it is known that `deps` are still dependencies of `parent` on the incremental build
     */
    @Throws(java.lang.InterruptedException::class)
    fun analyzeDepsDoneness(parent: SkyKey?, deps: MutableList<SkyKey?>?): DepsReport?
}
