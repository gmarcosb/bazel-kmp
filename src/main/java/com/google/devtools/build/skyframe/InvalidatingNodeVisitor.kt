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

import com.google.devtools.build.lib.concurrent.ErrorClassifier

/**
 * A visitor that is useful for invalidating transitive dependencies of Skyframe nodes.
 * 
 * 
 * Interruptibility: It is safe to interrupt the invalidation process at any time. Consider a
 * graph and a set of modified nodes. Then the reverse transitive closure of the modified nodes is
 * the set of dirty nodes. We provide interruptibility by making sure that the following invariant
 * holds at any time:
 * 
 * 
 * If a node is dirty, but not removed (or marked as dirty) yet, then either it or any of its
 * transitive dependencies must be in the [.pendingVisitations] set. Furthermore, reverse dep
 * pointers must always point to existing nodes.
 * 
 * 
 * Thread-safety: This class should only be instantiated and called on a single thread, but
 * internally it spawns many worker threads to process the graph. The thread-safety of the workers
 * on the graph can be delicate, and is documented below. Moreover, no other modifications to the
 * graph can take place while invalidation occurs.
 */
abstract class InvalidatingNodeVisitor<GraphT : QueryableGraph?> protected constructor(
    graph: GraphT?,
    progressReceiver: DirtyAndInflightTrackingProgressReceiver?,
    state: InvalidationState,
    forkJoinPool: ForkJoinPool?
) {
    protected val graph: GraphT?
    protected val progressReceiver: DirtyAndInflightTrackingProgressReceiver

    // Aliased to InvalidationState.pendingVisitations.
    protected val pendingVisitations: MutableSet<com.google.devtools.build.lib.util.Pair<SkyKey?, InvalidationType?>?>
    protected val executor: QuiescingExecutor

    init {
        this.executor = ForkJoinQuiescingExecutor.newBuilder()
            .withOwnershipOf(forkJoinPool)
            .setErrorClassifier(errorClassifier)
            .build()
        this.graph = com.google.common.base.Preconditions.checkNotNull<GraphT?>(graph)
        this.progressReceiver =
            com.google.common.base.Preconditions.checkNotNull<DirtyAndInflightTrackingProgressReceiver>(progressReceiver)
        this.pendingVisitations = state.pendingValues
    }

    /** Initiates visitation and waits for completion.  */
    @Throws(java.lang.InterruptedException::class)
    fun run() {
        GoogleAutoProfilerUtils.logged(
            "invalidation of " + pendingVisitations.size() + " nodes", MIN_TIME_FOR_LOGGING
        ).use { ignored ->
            // Make a copy to avoid concurrent modification confusing us as to which nodes were passed by
            // the caller, and which are added by other threads during the run. Since no tasks have been
            // started yet, this is thread-safe.
            runInternal(
                com.google.common.collect.ImmutableList.copyOf<com.google.devtools.build.lib.util.Pair<SkyKey?, InvalidationType?>?>(
                    pendingVisitations
                )
            )
        }
        com.google.common.base.Preconditions.checkState(
            pendingVisitations.isEmpty(),
            "All dirty nodes should have been processed: %s",
            pendingVisitations
        )
    }

    @com.google.errorprone.annotations.ForOverride
    @Throws(java.lang.InterruptedException::class)
    protected open fun runInternal(pendingList: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.util.Pair<SkyKey?, InvalidationType?>>) {
        GoogleAutoProfilerUtils.logged("invalidation enqueuing", MIN_TIME_FOR_LOGGING).use { ignored ->
            for (visitData in pendingList) {
                executor.execute({
                    visit(
                        com.google.common.collect.ImmutableList.of<SkyKey?>(visitData.first),
                        visitData.second
                    )
                })
            }
        }
        try {
            executor.awaitQuiescence( /*interruptWorkers=*/true)
        } catch (e: java.lang.IllegalStateException) {
            // TODO(mschaller): Remove this wrapping after debugging the invalidation-after-OOMing-eval
            // problem. The wrapping provides a stack trace showing what caused the invalidation.
            throw java.lang.IllegalStateException(e)
        }
    }

    @get:com.google.common.annotations.VisibleForTesting
    val interruptionLatchForTestingOnly: CountDownLatch
        get() = executor.interruptionLatchForTestingOnly

    /** Enqueues nodes for invalidation. Elements of `keys` may not exist in the graph.  */
    @ThreadSafe
    abstract fun visit(keys: MutableCollection<SkyKey?>?, invalidationType: InvalidationType?)

    @com.google.common.annotations.VisibleForTesting
    internal enum class InvalidationType {
        /** The node is dirty and must be recomputed.  */
        CHANGED,

        /** The node is dirty, but may be marked clean later during change pruning.  */
        DIRTIED,

        /** The node is deleted.  */
        DELETED
    }

    /**
     * Invalidation state object that keeps track of which nodes need to be invalidated, but have not
     * been dirtied/deleted yet. This supports interrupts - by only deleting a node from this set
     * when all its parents have been invalidated, we ensure that no information is lost when an
     * interrupt comes in.
     */
    internal open class InvalidationState private constructor(defaultUpdateType: InvalidationType?) {
        private val pendingValues: MutableSet<com.google.devtools.build.lib.util.Pair<SkyKey?, InvalidationType?>?> =
            Collections.newSetFromMap<com.google.devtools.build.lib.util.Pair<SkyKey?, InvalidationType?>?>(
                ConcurrentHashMap<com.google.devtools.build.lib.util.Pair<SkyKey?, InvalidationType?>?, Boolean?>(
                    EXPECTED_PENDING_SET_SIZE, .75f, DEFAULT_THREAD_COUNT
                )
            )
        private val defaultUpdateType: InvalidationType

        init {
            this.defaultUpdateType =
                com.google.common.base.Preconditions.checkNotNull<InvalidationType>(defaultUpdateType)
        }

        fun update(diff: Iterable<SkyKey?>) {
            com.google.common.collect.Iterables.addAll<com.google.devtools.build.lib.util.Pair<SkyKey?, InvalidationType?>?>(
                pendingValues,
                com.google.common.collect.Iterables.transform<SkyKey?, com.google.devtools.build.lib.util.Pair<SkyKey?, InvalidationType?>?>(
                    diff,
                    com.google.common.base.Function { skyKey: SkyKey? ->
                        com.google.devtools.build.lib.util.Pair.Companion.of<SkyKey?, InvalidationType?>(
                            skyKey,
                            defaultUpdateType
                        )
                    })
            )
        }

        @get:com.google.common.annotations.VisibleForTesting
        open val isEmpty: Boolean
            get() = pendingValues.isEmpty()

        @get:com.google.common.annotations.VisibleForTesting
        val invalidationsForTesting: MutableSet<com.google.devtools.build.lib.util.Pair<SkyKey, InvalidationType?>>
            get() = com.google.common.collect.ImmutableSet.copyOf<com.google.devtools.build.lib.util.Pair<SkyKey?, InvalidationType?>?>(
                pendingValues
            )
    }

    internal class DirtyingInvalidationState : InvalidationState(InvalidationType.CHANGED)

    internal class DeletingInvalidationState : InvalidationState(InvalidationType.DELETED) {
        private var doneKeysWithRdepsToRemove: ConcurrentHashMap<SkyKey?, Boolean?>? = null
        private var visitedKeysAcrossInterruptions: ConcurrentHashMap<SkyKey?, Boolean?>? = null

        init {
            initializeFields()
        }

        private fun initializeFields() {
            doneKeysWithRdepsToRemove =
                ConcurrentHashMap<SkyKey?, Boolean?>(EXPECTED_PENDING_SET_SIZE, .75f, DEFAULT_THREAD_COUNT)
            visitedKeysAcrossInterruptions =
                ConcurrentHashMap<SkyKey?, Boolean?>(EXPECTED_PENDING_SET_SIZE, .75f, DEFAULT_THREAD_COUNT)
        }

        override fun isEmpty(): Boolean {
            return super.isEmpty && doneKeysWithRdepsToRemove.isEmpty()
        }

        fun clear() {
            initializeFields()
        }
    }

    /** A node-deleting implementation.  */
    internal class DeletingNodeVisitor(
        graph: InMemoryGraph?,
        progressReceiver: DirtyAndInflightTrackingProgressReceiver?,
        private val state: DeletingInvalidationState,
        private val traverseGraph: Boolean
    ) : InvalidatingNodeVisitor<InMemoryGraph?>(
        graph,
        progressReceiver,
        state,
        NamedForkJoinPool.newNamedPool("deleting node visitor", DEFAULT_THREAD_COUNT)
    ) {
        private val visited: MutableSet<SkyKey?> = com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()

        @Throws(java.lang.InterruptedException::class)
        override fun runInternal(pendingList: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.util.Pair<SkyKey?, InvalidationType?>?>) {
            GoogleAutoProfilerUtils.logged(
                "invalidation enqueuing for " + pendingList.size() + " nodes",
                MIN_TIME_FOR_LOGGING
            ).use { ignored ->
                // To avoid contention and scheduling too many jobs for our #cpus, we start
                // DEFAULT_THREAD_COUNT jobs, each processing a chunk of the pending visitations.
                val listSize: Long = pendingList.size().toLong()
                val numThreads: Long = java.lang.Math.min(DEFAULT_THREAD_COUNT.toLong(), listSize)
                for (i in 0..<numThreads) {
                    // Use long multiplication to avoid possible overflow, as numThreads * listSize might be
                    // larger than max int.
                    val startIndex = ((i * listSize) / numThreads).toInt()
                    val endIndex = (((i + 1) * listSize) / numThreads).toInt()
                    executor.execute(
                        {
                            visit(
                                com.google.common.collect.Collections2.transform<com.google.devtools.build.lib.util.Pair<SkyKey?, InvalidationType?>?, SkyKey?>(
                                    pendingList.subList(startIndex, endIndex),
                                    com.google.common.base.Function { obj: com.google.devtools.build.lib.util.Pair<*, *>? -> obj.getFirst() }),
                                InvalidationType.DELETED
                            )
                        })
                }
            }
            GoogleAutoProfilerUtils.logged("invalidation graph traversal", MIN_TIME_FOR_LOGGING).use { ignored ->
                executor.awaitQuiescence( /*interruptWorkers=*/true)
            }
            val deletedKeys: ConcurrentHashMap.KeySetView<SkyKey?, Boolean?> =
                state.visitedKeysAcrossInterruptions.keySet()
            GoogleAutoProfilerUtils.logged(
                ("reverse dep removal of "
                        + deletedKeys.size()
                        + " deleted rdeps from "
                        + state.doneKeysWithRdepsToRemove.size()
                        + " deps"),
                MIN_TIME_FOR_LOGGING
            ).use { ignored ->
                state.doneKeysWithRdepsToRemove.forEachEntry( /*parallelismThreshold=*/
                    1024,
                    java.util.function.Consumer { e: MutableMap.MutableEntry<SkyKey?, Boolean?>? ->
                        val entry: NodeEntry? = graph.get(
                            null,
                            com.google.devtools.build.skyframe.QueryableGraph.Reason.RDEP_REMOVAL,
                            e.getKey()
                        )
                        if (entry != null) {
                            entry.removeReverseDepsFromDoneEntryDueToDeletion(deletedKeys)
                        }
                    })
                state.clear()
            }
        }

        public override fun visit(keys: MutableCollection<SkyKey>, invalidationType: InvalidationType?) {
            com.google.common.base.Preconditions.checkState(invalidationType == InvalidationType.DELETED, keys)
            val unvisitedKeysBuilder: com.google.common.collect.ImmutableList.Builder<SkyKey?> =
                com.google.common.collect.ImmutableList.builder<SkyKey?>()
            for (key in keys) {
                if (visited.add(key)) {
                    unvisitedKeysBuilder.add(key)
                }
            }
            val unvisitedKeys: com.google.common.collect.ImmutableList<SkyKey?> = unvisitedKeysBuilder.build()
            for (key in unvisitedKeys) {
                pendingVisitations.add(
                    com.google.devtools.build.lib.util.Pair.Companion.of<SkyKey?, InvalidationType?>(
                        key,
                        InvalidationType.DELETED
                    )
                )
            }
            val entries: NodeBatch = graph.getBatch(
                null,
                com.google.devtools.build.skyframe.QueryableGraph.Reason.INVALIDATION,
                unvisitedKeys
            )
            for (key in unvisitedKeys) {
                executor.execute(
                    {
                        val entry: NodeEntry? = entries.get(key)
                        val invalidationPair: com.google.devtools.build.lib.util.Pair<SkyKey?, InvalidationType?> =
                            com.google.devtools.build.lib.util.Pair.Companion.of<SkyKey?, InvalidationType?>(
                                key,
                                InvalidationType.DELETED
                            )
                        if (entry == null) {
                            pendingVisitations.remove(invalidationPair)
                            return@execute
                        }

                        if (traverseGraph) {
                            // Propagate deletion upwards.
                            visit(entry.getAllReverseDepsForNodeBeingDeleted(), InvalidationType.DELETED)

                            // Unregister this node as an rdep from its direct deps, since reverse dep edges
                            // cannot point to non-existent nodes. To know whether the child has this node as an
                            // "in-progress" rdep to be signaled, or just as a known rdep, we look at the deps
                            // that this node declared during its last (presumably interrupted) evaluation. If a
                            // dep is in this set, then it was notified to signal this node, and so the rdep
                            // will be an in-progress rdep, if the dep itself isn't done. Otherwise it will be a
                            // normal rdep. That information is used to remove this node as an rdep from the
                            // correct list of rdeps in the child -- because of our compact storage of rdeps,
                            // checking which list contains this parent could be expensive.
                            val directDeps: Iterable<SkyKey?>
                            try {
                                directDeps =
                                    if (entry.isDone())
                                        entry.getDirectDeps()
                                    else
                                        entry.getAllDirectDepsForIncompleteNode()
                            } catch (e: java.lang.InterruptedException) {
                                throw java.lang.IllegalStateException(
                                    ("Deletion cannot happen on a graph that may have blocking operations: "
                                            + key
                                            + ", "
                                            + entry),
                                    e
                                )
                            }
                            // No need to do reverse dep surgery on nodes that are deleted/about to be deleted
                            // anyway.
                            val depMap: MutableMap<SkyKey?, out NodeEntry?> =
                                graph.getBatchMap(
                                    key,
                                    com.google.devtools.build.skyframe.QueryableGraph.Reason.INVALIDATION,
                                    com.google.common.collect.Iterables.filter<SkyKey?>(
                                        directDeps,
                                        com.google.common.base.Predicate { k: SkyKey? ->
                                            !state.visitedKeysAcrossInterruptions.containsKey(k)
                                                    && !pendingVisitations.contains(
                                                com.google.devtools.build.lib.util.Pair.Companion.of<SkyKey?, InvalidationType?>(
                                                    k,
                                                    InvalidationType.DELETED
                                                )
                                            )
                                        })
                                )
                            if (!depMap.isEmpty()) {
                                for (directDepEntry in depMap.entrySet()) {
                                    val dep: NodeEntry? = directDepEntry.getValue()
                                    if (dep == null) {
                                        continue
                                    }
                                    if (dep.isDone()) {
                                        state.doneKeysWithRdepsToRemove.putIfAbsent(
                                            directDepEntry.getKey(), java.lang.Boolean.TRUE
                                        )
                                        continue
                                    }
                                    try {
                                        dep.removeReverseDep(key)
                                    } catch (e: java.lang.InterruptedException) {
                                        throw java.lang.IllegalStateException(
                                            ("Deletion cannot happen on a graph that may have blocking operations: "
                                                    + key
                                                    + ", "
                                                    + entry),
                                            e
                                        )
                                    }
                                }
                            }
                        }

                        // Allow custom key-specific logic to update dirtiness status.
                        progressReceiver.deleted(key)
                        // Actually remove the node.
                        graph.remove(key)

                        // Remove the node from the set and add it to global visited as the last operation.
                        state.visitedKeysAcrossInterruptions.put(key, java.lang.Boolean.TRUE)
                        pendingVisitations.remove(invalidationPair)
                    })
            }
        }
    }

    /** A node-dirtying implementation.  */
    internal class DirtyingNodeVisitor(
        graph: QueryableGraph?,
        progressReceiver: DirtyAndInflightTrackingProgressReceiver?,
        state: InvalidationState
    ) : InvalidatingNodeVisitor<QueryableGraph?>(
        graph,
        progressReceiver,
        state,
        NamedForkJoinPool.newNamedPool("dirty node visitor", DEFAULT_THREAD_COUNT)
    ) {
        private val changed: MutableSet<SkyKey?> = Collections.newSetFromMap<SkyKey?>(
            ConcurrentHashMap<SkyKey?, Boolean?>(EXPECTED_VISITED_SET_SIZE, .75f, DEFAULT_THREAD_COUNT)
        )
        private val dirtied: MutableSet<SkyKey?> = Collections.newSetFromMap<SkyKey?>(
            ConcurrentHashMap<SkyKey?, Boolean?>(EXPECTED_VISITED_SET_SIZE, .75f, DEFAULT_THREAD_COUNT)
        )

        override fun visit(keys: MutableCollection<SkyKey>, invalidationType: InvalidationType?) {
            com.google.common.base.Preconditions.checkState(invalidationType != InvalidationType.DELETED, keys)
            visit(keys, invalidationType,  /* depthForOverflowCheck= */0, null)
        }

        /**
         * Queues a task to dirty the nodes named by {@param keys}. May be called from multiple threads.
         * It is possible that the same node is enqueued many times. However, we require that a node is
         * only actually marked dirty/changed once, with two exceptions:
         * 
         * 
         * (1) If a node is marked dirty, it can subsequently be marked changed. This can occur if,
         * for instance, FileValue workspace/foo/foo.cc is marked dirty because FileValue workspace/foo
         * is marked changed (and every FileValue depends on its parent). Then FileValue
         * workspace/foo/foo.cc is itself changed (this can even happen on the same build).
         * 
         * 
         * (2) If a node is going to be marked both dirty and changed, as, for example, in the
         * previous case if both workspace/foo/foo.cc and workspace/foo have been changed in the same
         * build, the thread marking workspace/foo/foo.cc dirty may race with the one marking it
         * changed, and so try to mark it dirty after it has already been marked changed. In that case,
         * the [NodeEntry] ignores the second marking.
         * 
         * 
         * The invariant that we do not process a (SkyKey, InvalidationType) pair twice is enforced
         * by the [.changed] and [.dirtied] sets.
         * 
         * 
         * The "invariant" is also enforced across builds by checking to see if the entry is already
         * marked changed, or if it is already marked dirty and we are just going to mark it dirty
         * again.
         * 
         * 
         * If either of the above tests shows that we have already started a task to mark this entry
         * dirty/changed, or that it is already marked dirty/changed, we do not continue this task.
         */
        @ThreadSafe
        private fun visit(
            keys: MutableCollection<SkyKey>,
            invalidationType: InvalidationType?,
            depthForOverflowCheck: Int,
            enqueueingKeyForExistenceCheck: SkyKey?
        ) {
            // Code from here until pendingVisitations#add is called below must be uninterruptible.
            val isChanged = (invalidationType == InvalidationType.CHANGED)
            val setToCheck: MutableSet<SkyKey?> = if (isChanged) changed else dirtied
            val keysToGet: java.util.ArrayList<SkyKey?> = java.util.ArrayList<SkyKey?>(keys.size())
            for (key in keys) {
                if (setToCheck.add(key)) {
                    check(!(isChanged && key.functionName().getHermeticity() == FunctionHermeticity.HERMETIC)) {
                        ("Nodes with hermetic functions cannot be marked 'changed': "
                                + "%s function:%s hermeticity:%s"
                            .formatted(key, key.functionName(), key.functionName().getHermeticity()))
                    }
                    keysToGet.add(key)
                }
            }
            for (key in keysToGet) {
                pendingVisitations.add(
                    com.google.devtools.build.lib.util.Pair.Companion.of<SkyKey?, InvalidationType?>(
                        key,
                        invalidationType
                    )
                )
            }
            val entries: MutableMap<SkyKey?, out NodeEntry?>
            try {
                entries = graph.getBatchMap(
                    null,
                    com.google.devtools.build.skyframe.QueryableGraph.Reason.INVALIDATION,
                    keysToGet
                )
            } catch (e: java.lang.InterruptedException) {
                java.lang.Thread.currentThread().interrupt()
                // This can only happen if the main thread has been interrupted, and so the
                // AbstractQueueVisitor is shutting down. We haven't yet removed the pending visitations, so
                // we can resume next time.
                return
            }
            if (enqueueingKeyForExistenceCheck != null && entries.size() != keysToGet.size()) {
                val missingKeys: MutableSet<SkyKey?> = com.google.common.collect.Sets.difference<SkyKey?>(
                    com.google.common.collect.ImmutableSet.copyOf<SkyKey?>(keysToGet), entries.keySet()
                )
                throw java.lang.IllegalStateException(
                    java.lang.String.format(
                        "key(s) %s not in the graph, but enqueued for dirtying by %s",
                        com.google.common.collect.Iterables.limit<SkyKey?>(missingKeys, 10),
                        enqueueingKeyForExistenceCheck
                    )
                )
            }
            // We take a deeper thread stack in exchange for less contention in the executor.
            val lastIndex: Int = keysToGet.size() - 1
            if (lastIndex == -1) {
                return
            }
            for (i in 0..<lastIndex) {
                val key: SkyKey? = keysToGet.get(i)
                executor.execute({ dirtyKeyAndVisitParents(key, entries, invalidationType, 0) })
            }
            val lastParent: SkyKey? = keysToGet.get(lastIndex)
            if (depthForOverflowCheck > SAFE_STACK_DEPTH) {
                logger.atInfo().atMostEvery(1, TimeUnit.MINUTES).log(
                    "Stack depth too deep to safely recurse for %s (%s)",
                    lastParent, enqueueingKeyForExistenceCheck
                )
                executor.execute({ dirtyKeyAndVisitParents(lastParent, entries, invalidationType, 0) })
                return
            }
            if (!java.lang.Thread.interrupted()) {
                // Emulate what would happen if we'd submitted this to the executor: skip on interrupt.
                dirtyKeyAndVisitParents(lastParent, entries, invalidationType, depthForOverflowCheck + 1)
            }
        }

        private fun dirtyKeyAndVisitParents(
            key: SkyKey?,
            entries: MutableMap<SkyKey?, out NodeEntry?>,
            invalidationType: InvalidationType?,
            depthForOverflowCheck: Int
        ) {
            val entry: NodeEntry? = entries.get(key)

            if (entry == null) {
                pendingVisitations.remove(
                    com.google.devtools.build.lib.util.Pair.Companion.of<SkyKey?, InvalidationType?>(
                        key,
                        invalidationType
                    )
                )
                return
            }

            val isChanged = invalidationType == InvalidationType.CHANGED
            if (entry.isChanged() || (!isChanged && entry.isDirty())) {
                // If this node is already marked changed, or we are only marking this node
                // dirty, and it already is, move along.
                pendingVisitations.remove(
                    com.google.devtools.build.lib.util.Pair.Companion.of<SkyKey?, InvalidationType?>(
                        key,
                        invalidationType
                    )
                )
                return
            }

            val dirtyType: DirtyType = if (isChanged) DirtyType.CHANGE else DirtyType.DIRTY

            // This entry remains in the graph in this dirty state until it is re-evaluated.
            val markedDirtyResult: MarkedDirtyResult?
            try {
                markedDirtyResult = entry.markDirty(dirtyType)
            } catch (e: java.lang.InterruptedException) {
                java.lang.Thread.currentThread().interrupt()
                // This can only happen if the main thread has been interrupted, and so the
                // AbstractQueueVisitor is shutting down. We haven't yet removed the pending
                // visitation, so we can resume next time.
                return
            }
            if (markedDirtyResult == null) {
                // Another thread has already dirtied this node. Don't do anything in this thread.
                pendingVisitations.remove(
                    com.google.devtools.build.lib.util.Pair.Companion.of<SkyKey?, InvalidationType?>(
                        key,
                        invalidationType
                    )
                )
                return
            }

            progressReceiver.dirtied(key, dirtyType)
            pendingVisitations.remove(
                com.google.devtools.build.lib.util.Pair.Companion.of<SkyKey?, InvalidationType?>(
                    key,
                    invalidationType
                )
            )

            // Propagate dirtiness upwards and mark this node dirty/changed. Reverse deps should
            // only be marked dirty (because only a dependency of theirs has changed).
            visit(
                markedDirtyResult.getReverseDepsUnsafe(),
                InvalidationType.DIRTIED,
                depthForOverflowCheck,
                key
            )
        }

        companion object {
            private val SAFE_STACK_DEPTH = 1 shl 9
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        // Default thread count is equal to the number of cores to exploit
        // that level of hardware parallelism, since invalidation should be CPU-bound.
        // We may consider increasing this in the future.
        @kotlin.jvm.JvmField
        @com.google.common.annotations.VisibleForTesting
        val DEFAULT_THREAD_COUNT: Int = java.lang.Runtime.getRuntime().availableProcessors()

        private val EXPECTED_PENDING_SET_SIZE = DEFAULT_THREAD_COUNT * 8
        private const val EXPECTED_VISITED_SET_SIZE = 1024

        private val errorClassifier: ErrorClassifier = object : ErrorClassifier() {
            protected override fun classifyException(e: java.lang.Exception?): ErrorClassification {
                return if (e is java.lang.RuntimeException)
                    ErrorClassification.CRITICAL_AND_LOG
                else
                    ErrorClassification.NOT_CRITICAL
            }
        }

        private val MIN_TIME_FOR_LOGGING: java.time.Duration? = java.time.Duration.ofMillis(10)
    }
}
