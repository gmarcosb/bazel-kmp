// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization.analysis

import com.google.devtools.build.lib.actions.ActionLookupKey

/**
 * Computes a mapping from [ActionLookupKey]s to [FileOpNodeOrFuture]s, representing the
 * complete set of file system operation dependencies required to evaluate each key.
 * 
 * 
 * This class tracks file dependencies for a particular build. It uses the file and source
 * partitioning in [AbstractNestedFileOpNodes] to provide a view of file dependencies for
 * configured targets and actions. For configured targets, only the analysis dependencies (BUILD,
 * .bzl files) are relevant. For actions, the source (.h, .cpp, .java) files must also be
 * considered.
 * 
 * 
 * **Approximation for Efficiency:** To avoid the excessive overhead of storing precise file
 * dependencies per action, an over-approximation is used. This may lead to occasional spurious
 * cache misses but guarantees no false cache hits. The approximation includes all source
 * dependencies declared by the configured target that were visited during the build.
 * 
 * 
 * Not all actions of a configured target are executed, and include scanning may eliminate
 * dependencies, so the actual set of source files visited by a build may be a subset of the
 * declared ones. This will never skip an actual action file dependency of the build. While this is
 * correct, it's possible that different builds at the same version will have slightly different
 * representations of the sets of sources.
 * 
 * 
 * **Why Approximation?** <br></br>
 * Storing the exact file dependencies for each action individually would be too expensive. It would
 * negate the benefits of the compact nested representation used for configured target dependencies.
 * The chosen approximation balances accuracy with performance.
 * 
 * 
 * **Different Sources in Multiple Builds** <br></br>
 * Suppose there are multiple builds that share configured targets, but request different actions
 * from those configured targets. The configured target data is deterministic and shared, but the
 * invalidation information for source files could differ. When invalidating the configured target,
 * the source files are ignored, so even if a second build overwrites the configured target of the
 * first, invalidation of the configured target still works exactly the same way. For actions,
 * overwriting of the configured target doesn't affect correctness either because each action
 * directly references the invalidation data created by its respective build.
 */
internal class FileOpNodeMemoizingLookup(executor: java.util.concurrent.Executor, graph: InMemoryGraph) {
    private val executor: java.util.concurrent.Executor
    private val graph: InMemoryGraph

    private val nodes: ValueOrFutureMap<SkyKey?, FileOpNodeOrFuture?, FileOpNodeOrEmpty?, FutureFileOpNode?> =
        ValueOrFutureMap<KeyT?, ValueOrFutureT?, ValueT?, FutureT?>(
            ConcurrentHashMap<Any?, Any?>(),
            java.util.function.BiFunction { key: KeyT?, consumer: java.util.function.BiConsumer<KeyT?, ValueT?>? ->
                FutureFileOpNode(
                    key,
                    consumer
                )
            },
            java.util.function.Function { ownedFuture: FutureT? -> this.populateFutureFileOpNode(ownedFuture) },
            FutureFileOpNode::class.java
        )

    init {
        this.executor = executor
        this.graph = graph
    }

    fun computeNode(key: ActionLookupKey?): FileOpNodeOrFuture? {
        return nodes.getValueOrFuture(key)
    }

    private fun populateFutureFileOpNode(ownedFuture: FutureFileOpNode): FileOpNodeOrFuture {
        val collector = FileOpNodeCollector(executor)

        accumulateTransitiveFileSystemOperations(ownedFuture.key(), collector)
        collector.notifyAllFuturesAdded()

        if (collector.isDone()) {
            try {
                return ownedFuture.completeWith(com.google.common.util.concurrent.Futures.getDone<V?>(collector))
            } catch (e: ExecutionException) {
                return ownedFuture.failWith(e)
            }
        }
        return ownedFuture.completeWith(collector)
    }

    private fun accumulateTransitiveFileSystemOperations(key: SkyKey, collector: FileOpNodeCollector) {
        val nodeEntry: InMemoryNodeEntry? = graph.getIfPresent(key)
        if (nodeEntry == null) {
            collector.failWith(MissingSkyframeEntryException(key))
            return
        }

        if (key is ActionLookupKey) {
            // If the corresponding value is an InputFileConfiguredTarget, it indicates an execution time
            // file dependency.
            if ((com.google.common.base.Preconditions.checkNotNull<SkyValue?>(nodeEntry.getValue(), key)
                        is NonRuleConfiguredTargetValue)
                && (nonRuleConfiguredTargetValue.getConfiguredTarget()
                        is InputFileConfiguredTarget)
            ) {
                // The source artifact's file becomes an execution time dependency of actions owned by
                // configured targets with this InputFileConfiguredTarget as a dependency.
                val source: SourceArtifact = inputFileConfiguredTarget.getArtifact()
                val fileKey: com.google.devtools.build.lib.skyframe.FileKey? =
                    com.google.devtools.build.lib.skyframe.FileKey.Companion.create(
                        RootedPath.toRootedPath(
                            source.getRoot().getRoot(), source.getPath()
                        )
                    )
                if (graph.getIfPresent(fileKey) != null) {
                    // If the file value is not present in the graph, it means that no action executed
                    // actually depended on that file.
                    //
                    // TODO: b/364831651 - for greater determinism, consider performing additional Skyframe
                    // evaluations for these unused dependencies.
                    collector.setSource(fileKey)
                }
            }
        }

        for (dep in nodeEntry.getDirectDeps()) {
            when (dep) {
                -> collector.addNode(immediateNode)
                else -> addNodeForKey(dep, collector)
            }
        }
    }

    private fun addNodeForKey(key: SkyKey?, collector: FileOpNodeCollector) {
        // TODO: b/364831651 - This adds all traversed SkyKeys to `nodes`. Consider if certain types
        // should be excluded from memoization.
        when (nodes.getValueOrFuture(key)) {
            EmptyFileOpNode.EMPTY_FILE_OP_NODE -> {}
            -> collector.addNode(node)
            -> collector.addFuture(future)
        }
    }

    private class FileOpNodeCollector(executor: java.util.concurrent.Executor) :
        QuiescingFuture<FileOpNodeOrEmpty?>(com.google.common.util.concurrent.MoreExecutors.directExecutor()),
        com.google.common.util.concurrent.FutureCallback<FileOpNodeOrEmpty?> {
        private val executor: java.util.concurrent.Executor
        private val nodes: MutableSet<FileOpNode?> = ConcurrentHashMap.newKeySet<FileOpNode?>()
        private var sourceFile: com.google.devtools.build.lib.skyframe.FileKey? = null

        init {
            this.executor = executor
        }

        val value: FileOpNodeOrEmpty?
            get() = AbstractNestedFileOpNodes.Companion.from(nodes, sourceFile)

        fun addNode(node: FileOpNode?) {
            nodes.add(node)
        }

        fun setSource(sourceFile: com.google.devtools.build.lib.skyframe.FileKey?) {
            com.google.common.base.Preconditions.checkState(
                this.sourceFile == null,
                "Attempted to set source to %s but source already set to %s.",
                sourceFile,
                this.sourceFile
            )
            this.sourceFile = sourceFile
        }

        fun addFuture(future: FutureFileOpNode) {
            increment()
            // There is a graph made of futures that parallels the Skyframe dependency graph. Therefore,
            // it's a bad idea to use directExecutor() here because the amount of work that the
            // the completion of the future unblocks can be quite large.
            com.google.common.util.concurrent.Futures.addCallback<V?>(
                future,
                this as com.google.common.util.concurrent.FutureCallback<FileOpNodeOrEmpty?>,
                executor
            )
        }

        fun notifyAllFuturesAdded() {
            decrement()
        }

        fun failWith(e: MissingSkyframeEntryException?) {
            notifyException(e)
        }

        /**
         * Implementation of [<].
         * 
         */
        @Deprecated("do not call, only used for callback processing")
        override fun onSuccess(nodeOrEmpty: FileOpNodeOrEmpty) {
            when (nodeOrEmpty) {
                EmptyFileOpNode.EMPTY_FILE_OP_NODE -> {}
                -> addNode(node)
            }
            decrement()
        }

        /**
         * Implementation of [<].
         * 
         */
        @Deprecated("do not call, only used for callback processing")
        override fun onFailure(t: Throwable) {
            notifyException(t)
        }
    }
}
