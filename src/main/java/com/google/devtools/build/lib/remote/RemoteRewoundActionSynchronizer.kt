// Copyright 2026 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote

import com.google.devtools.build.lib.actions.Action

/**
 * A [RewoundActionSynchronizer] implementation for Bazel's remote filesystem, which is backed
 * by actual files on disk and requires synchronization to ensure that action outputs aren't deleted
 * while they are being read.
 */
internal class RemoteRewoundActionSynchronizer(actionInputFetcher: RemoteActionInputFetcher) :
    RewoundActionSynchronizer {
    /** A task with a cancellation callback.  */
    interface Cancellable {
        @Throws(java.lang.InterruptedException::class)
        fun cancel()
    }

    private val actionInputFetcher: RemoteActionInputFetcher
    private val outputUploadTasks: ConcurrentHashMap<ActionExecutionMetadata?, Cancellable?> =
        ConcurrentHashMap<ActionExecutionMetadata?, Cancellable?>()

    // A single coarse lock is used to synchronize rewound actions (writers) and both rewound and
    // non-rewound actions (readers) as long as no rewound action has attempted to prepare for its
    // execution.
    // This ensures high throughput and low memory footprint for the common case of no rewound
    // actions. In this case, there won't be any writers and the performance characteristics of a
    // ReentrantReadWriteLock are comparable to that of an atomic counter. A StampedLock would not be
    // a good fit as its performance regresses with 127 or more concurrent readers.
    // Note that it wouldn't be correct to only start using this lock once an action is rewound,
    // because a non-rewound action consuming its non-lost outputs could have already started
    // executing.
    @kotlin.concurrent.Volatile
    private var coarseLock: ReadWriteLock? = ReentrantReadWriteLock()

    // A fine-grained lock structure that is switched to when the first rewound action attempts to
    // prepare for its execution. This structure is used to ensure that rewound actions do not
    // delete their outputs while they are being read by other actions, while still allowing
    // rewound actions and non-rewound actions to run concurrently (i.e., not force the equivalent
    // of --jobs=1 for as long as a rewound action is running, as the coarse lock would).
    // A rewound action will acquire a write lock on its lookup data before it prepares for
    // execution, while any action will acquire a read lock on the lookup data of any generating
    // action of its inputs before it starts executing.
    // The values of this cache are weakly referenced to ensure that locks are cleaned up when they
    // are no longer needed.
    @kotlin.concurrent.Volatile
    private var fineLocks: com.github.benmanes.caffeine.cache.LoadingCache<ActionLookupData?, ReadWriteLock>? = null

    init {
        this.actionInputFetcher = actionInputFetcher
    }

    /*
  Proof of deadlock freedom:

  As long as the coarse lock is used, there can't be any deadlock because there is only a single
  read-write lock.

  Now assume that there is a deadlock while the fine locks are used. First, note that the logic in
  ImportantOutputHandler that is guarded by enterProcessOutputsAndGetLostArtifacts does not block
  on any (rewound or non-rewound) action executions while it holds read locks and can thus be
  ignored in the following. Consider the directed labeled "wait-for" graph defined as follows:

  * Nodes are given by the currently active Skyframe action execution threads, each of which is
    identified with the action it is (or will be) executing. Actions are in one-to-one
    correspondence with the ActionLookupData that is used as the key in the fine locks map.
  * For each pair of actions A_1 and A_2, there is an edge from A_1 to A_2 labeled with XY(A_3)
    if A_1 is waiting for the X lock of A_3 and A_2 currently holds the Y lock of A_3, where X and
    Y are either R (for read) or W (for write). The resulting graph may have parallel edges with
    distinct labels.

  Let C be any directed cycle in the graph representing a deadlock, let A_1 -[XY(A_3)]-> A_2 be an
  edge in C and consider the following cases for the pair XY:

  * RR: Since a read-write lock whose read lock is held by at least one thread doesn't
        block any other thread from acquiring its read lock, this case doesn't occur.
  * WW: The write lock of A_3 is only ever (attempted to be) acquired by A_3 itself when it is
        rewound, which means that the edge would necessarily be of the shape A_3 -[WW(A_3)]-> A_3.
        But this isn't possible since the write lock for an action is only acquired in one place (
        enterActionPreparationForRewinding) and not recursively.
  * WR: In this case, A_1 attempts to acquire a write lock, which only happens when A_1 is a
        rewound action about to prepare for its (re-)execution. This means that the edge is
        necessarily of the shape A_1 -[WR(A_1)]-> A_2. While a rewound action is waiting for its
        own write lock in enterActionPreparation, it doesn't hold any locks since
        enterActionExecution hasn't been called yet in SkyframeActionExecutor and all past
        executions of the action have released all their locks due to use of try-with-resources.
        This means that A_1 can't have any incoming edges in the wait-for graph, which is a
        contradiction to the assumption that it is contained in the directed cycle C.

   We conclude that XY = RW. Since the write lock of A_3 is only ever acquired by A_3 itself, all
   edges in C are of the form A_1 -[RW(A_2)]-> A_2. But by construction of inputKeysFor, the
   action A_1 is attempting to acquire the read locks of all its inputs' generating actions, and
   thus the action A_1 depends on one of the outputs of A_2 (*).

   Applied to all edges of C, we conclude that there is a corresponding directed cycle in the
   action graph, which is a contradiction since Bazel disallows dependency cycles.

   Notes:
   * The proof would not go through at (*) if fineLocks were replaced by a Striped lock structure
     with a fixed number of locks. In fact, this gives rise to a deadlock if the number of stripes
     is at least 2, but low enough that distinct generating actions hash to the same stripe.
   */
    @Throws(java.lang.InterruptedException::class)
    override fun enterActionPreparation(action: Action, wasRewound: Boolean): SilentCloseable {
        // Skyframe schedules non-rewound actions such that they never run concurrently with actions
        // that consume their outputs.
        if (!wasRewound) {
            return SilentCloseable {}
        }
        Profiler.instance().profile(ProfilerTask.ACTION_LOCK, "action.enterActionPreparation").use { c ->
            return enterActionPreparationForRewinding(action)
        }
    }

    @Throws(java.lang.InterruptedException::class)
    private fun enterActionPreparationForRewinding(action: Action): SilentCloseable {
        val localCoarseLock: ReadWriteLock? = coarseLock
        if (localCoarseLock != null) {
            // This is the first time a rewound action has attempted to prepare for its execution.
            // Switch to using the fine locks under the protection of the coarse write lock.
            localCoarseLock.writeLock().lockInterruptibly()
            try {
                // Check again under the lock to avoid a race between multiple rewound actions attempting
                // to prepare for execution at the same time.
                if (fineLocks == null) {
                    fineLocks =
                        Caffeine.newBuilder()
                            .weakValues() // ReentrantReadWriteLock would not work here as its individual read and write
                            // locks do not strongly reference the parent lock, which would lead to locks
                            // being cleaned up while they are still held
                            // (https://bugs.openjdk.org/browse/JDK-8189598). This can be worked around by
                            // using a construction similar to Guava's Striped helpers. StampedLock is both
                            // more memory-efficient and its views do strongly reference the parent lock
                            // (https://github.com/openjdk/jdk/blob/b349f661ea5f14b258191134714a7e712c90ef3e/src/java.base/share/classes/java/util/concurrent/locks/StampedLock.java#L1039),
                            // TODO: Investigate the effect of fair locks on build wall time.
                            .build<ActionLookupData?, ReadWriteLock?>(com.github.benmanes.caffeine.cache.CacheLoader { unused: ActionLookupData? -> StampedLock().asReadWriteLock() })
                    coarseLock = null
                }
            } finally {
                localCoarseLock.writeLock().unlock()
            }
        }

        val writeLock: java.util.concurrent.locks.Lock = fineLocks.get(outputKeyFor(action)).writeLock()
        writeLock.lockInterruptibly()
        prepareOutputsForRewinding(action)
        return SilentCloseable { writeLock.unlock() }
    }

    /**
     * Cancels all async tasks that operate on the action's outputs and resets any cached data about
     * their prefetching state.
     */
    @Throws(java.lang.InterruptedException::class)
    private fun prepareOutputsForRewinding(action: Action) {
        val task: Cancellable? = outputUploadTasks.remove(action)
        if (task != null) {
            task.cancel()
        }
        actionInputFetcher.handleRewoundActionOutputs(action.getOutputs())
    }

    @Throws(java.lang.InterruptedException::class)
    override fun enterActionExecution(action: Action, metadataProvider: InputMetadataProvider): SilentCloseable {
        Profiler.instance().profile(ProfilerTask.ACTION_LOCK, "action.enterActionExecution").use { c ->
            return lockArtifactsForConsumption(
                Iterable { action.getInputs().toList().iterator() }, metadataProvider
            )
        }
    }

    /**
     * Guards a call to [ ][com.google.devtools.build.lib.remote.RemoteImportantOutputHandler.processOutputsAndGetLostArtifacts].
     */
    @Throws(java.lang.InterruptedException::class)
    fun enterProcessOutputsAndGetLostArtifacts(
        importantOutputs: Iterable<Artifact?>, fullMetadataProvider: InputMetadataProvider
    ): SilentCloseable {
        Profiler.instance()
            .profile(ProfilerTask.ACTION_LOCK, "action.enterProcessOutputsAndGetLostArtifacts").use { c ->
                return lockArtifactsForConsumption(importantOutputs, fullMetadataProvider)
            }
    }

    /**
     * Registers a cancellation callback for an upload of action outputs that may still be running
     * after the action has completed.
     */
    fun registerOutputUploadTask(action: ActionExecutionMetadata?, task: Cancellable?) {
        // We don't expect to have multiple output upload tasks for the same action registered at the
        // same time.
        outputUploadTasks.merge(
            action,
            task,
            java.util.function.BiFunction { oldTask: Cancellable?, newTask: Cancellable? ->
                throw java.lang.IllegalStateException(
                    "Attempted to register multiple output upload tasks for %s: %s and %s"
                        .formatted(action, oldTask, newTask)
                )
            })
    }

    @Throws(java.lang.InterruptedException::class)
    private fun lockArtifactsForConsumption(
        artifacts: Iterable<Artifact?>, metadataProvider: InputMetadataProvider
    ): SilentCloseable {
        val localCoarseLock: ReadWriteLock? = coarseLock
        if (localCoarseLock != null) {
            // Common case for builds without any rewound actions: acquire the single lock that is never
            // acquired by a writer.
            localCoarseLock.readLock().lockInterruptibly()
        }
        // Read the fine locks after acquiring the coarse lock to allow the fine locks to be inflated
        // lazily.
        val localFineLocks: com.github.benmanes.caffeine.cache.LoadingCache<ActionLookupData?, ReadWriteLock>? =
            fineLocks
        if (localFineLocks == null) {
            // Continuation of the common case for builds without any rewound actions: the fine locks
            // have not been inflated.
            return SilentCloseable { localCoarseLock.readLock().unlock() }
        }

        // At this point, there has been at least one rewound action that has inflated the fine locks.
        // We need to switch to it.
        if (localCoarseLock != null) {
            localCoarseLock.readLock().unlock()
        }
        val allReadWriteLocks: MutableCollection<ReadWriteLock> =
            localFineLocks.getAll(inputKeysFor(artifacts, metadataProvider)).values()
        val locksToUnlockBuilder: com.google.common.collect.ImmutableList.Builder<java.util.concurrent.locks.Lock?> =
            com.google.common.collect.ImmutableList.builderWithExpectedSize<java.util.concurrent.locks.Lock?>(
                allReadWriteLocks.size()
            )
        try {
            for (readWriteLock in allReadWriteLocks) {
                val readLock: java.util.concurrent.locks.Lock = readWriteLock.readLock()
                readLock.lockInterruptibly()
                locksToUnlockBuilder.add(readLock)
            }
        } catch (e: java.lang.InterruptedException) {
            for (readLock in locksToUnlockBuilder.build()) {
                readLock.unlock()
            }
            throw e
        }
        val locksToUnlock: com.google.common.collect.ImmutableList<java.util.concurrent.locks.Lock?> =
            locksToUnlockBuilder.build()
        return SilentCloseable { locksToUnlock.forEach(java.util.function.Consumer { obj: java.util.concurrent.locks.Lock? -> obj.unlock() }) }
    }

    companion object {
        private fun inputKeysFor(
            artifacts: Iterable<Artifact?>, metadataProvider: InputMetadataProvider
        ): Iterable<ActionLookupData?> {
            val allArtifacts: Iterable<Any?> =
                com.google.common.collect.Iterables.concat<Any?>(
                    artifacts,
                    com.google.common.collect.Iterables.concat<T?>(
                        com.google.common.collect.Iterables.transform<F?, T?>(
                            metadataProvider.getRunfilesTrees(),
                            com.google.common.base.Function { runfilesTree: F? ->
                                runfilesTree.getArtifacts().toList()
                            })
                    )
                )
            return com.google.common.collect.Iterables.transform<Any?, ActionLookupData?>(
                com.google.common.collect.Iterables.filter<Any?>(
                    allArtifacts,
                    com.google.common.base.Predicate { artifact: Any? -> artifact is DerivedArtifact }),
                com.google.common.base.Function { artifact: Any? -> (artifact as DerivedArtifact).getGeneratingActionKey() })
        }

        private fun outputKeyFor(action: Action): ActionLookupData {
            return (action.getPrimaryOutput() as DerivedArtifact).getGeneratingActionKey()
        }
    }
}
