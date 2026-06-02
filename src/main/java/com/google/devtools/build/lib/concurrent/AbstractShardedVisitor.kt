// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.concurrent

import java.util.concurrent.LinkedBlockingQueue

/**
 * A sharding visitor which uses a [AbstractQueueVisitor]. It shards pending-visit items and
 * aims at reaching maximum parallelism by ensuring all threads are utilized unless number of
 * pending items are fewer than number of threads.
 */
abstract class AbstractShardedVisitor<T>(
    executor: com.google.devtools.build.lib.concurrent.AbstractQueueVisitor,
    numThreads: Int
) {
    private val executor: com.google.devtools.build.lib.concurrent.AbstractQueueVisitor
    private val remainingItemsToVisit: LinkedBlockingQueue<T?>
    private val numThreads: Int

    /**
     * Creates a visitor that uses a [ForkJoinQuiescingExecutor] backed by a [ ] using `DEFAULT_THREAD_COUNT` threads.
     */
    protected constructor(name: String?) : this(
        com.google.devtools.build.lib.concurrent.ForkJoinQuiescingExecutor.Companion.newBuilder()
            .withOwnershipOf(
                com.google.devtools.build.lib.concurrent.NamedForkJoinPool.Companion.newNamedPool(
                    name,
                    com.google.devtools.build.lib.concurrent.AbstractShardedVisitor.Companion.DEFAULT_THREAD_COUNT
                )
            )
            .setErrorClassifier(com.google.devtools.build.lib.concurrent.AbstractShardedVisitor.Companion.ERROR_CLASSIFIER)
            .build(),
        com.google.devtools.build.lib.concurrent.AbstractShardedVisitor.Companion.DEFAULT_THREAD_COUNT
    )

    /**
     * Creates a visitor using an [AbstractQueueVisitor], using up `numThreads` threads.
     */
    init {
        this.executor = executor
        this.numThreads = numThreads
        this.remainingItemsToVisit = LinkedBlockingQueue<T?>()
    }

    /**
     * Starts parallel visitations of items. Waits until queue is drained and there are no more items
     * to visit.
     */
    @Throws(java.lang.InterruptedException::class)
    fun scheduleVisitationsAndAwaitQuiescence(itemsToVisit: MutableCollection<T?>?) {
        remainingItemsToVisit.addAll(itemsToVisit)
        shardAndScheduleRemainingItems()
        executor.awaitQuiescence( /*interruptWorkers=*/true)
    }

    /** Ensures no item is still pending visitation.  */
    fun checkComplete() {
        if (remainingItemsToVisit.isEmpty()) {
            return
        }
        val numUnvisitedItems: Int = remainingItemsToVisit.size()
        val unvisitedItems: java.util.ArrayList<T?> = java.util.ArrayList<T?>(10)
        remainingItemsToVisit.drainTo(unvisitedItems, 10)
        throw java.lang.IllegalStateException(
            java.lang.String.format(
                "There are %s item(s) enqueued for visiting but not visited before quiescence "
                        + "(sample: %s)",
                numUnvisitedItems, com.google.common.collect.Iterables.limit<T?>(unvisitedItems, 10)
            )
        )
    }

    protected val maxBatchSize: Int
        /** Gets max batch size in each shard.  */
        get() = com.google.devtools.build.lib.concurrent.AbstractShardedVisitor.Companion.DEFAULT_MAX_BATCH_SIZE

    /** Shards the work of [.visit]ing `remainingItemsToVisit` across the free threads.  */
    private fun shardAndScheduleRemainingItems() {
        // Note that LinkedBlockingQueue#size() is a constant time operation.
        val numTasksExcludingThis: Int = com.google.common.primitives.Ints.checkedCast(executor.getTaskCount()) - 1
        val freeThreads: Int = java.lang.Math.max(numThreads - numTasksExcludingThis, 1)

        val itemsPerThread: Int = (remainingItemsToVisit.size() / freeThreads) + 1
        val batchSize: Int = java.lang.Math.min(itemsPerThread, this.maxBatchSize)

        for (i in 0..<freeThreads) {
            val items: java.util.ArrayList<T?> = java.util.ArrayList<T?>(batchSize)
            remainingItemsToVisit.drainTo(items, batchSize)
            // We may be done because someone else stole our items or because freeThreads was greater than
            // remainingItemsToVisit.size() and we've finished dealing out the remaining items.
            if (items.isEmpty()) {
                break
            }
            executor.execute(
                java.lang.Runnable {
                    try {
                        remainingItemsToVisit.addAll(visit(items))
                        shardAndScheduleRemainingItems()
                    } catch (e: java.lang.InterruptedException) {
                        // The work thread may get interrupted only when the main thread is interrupted. Stop
                        // doing further work.
                        java.lang.Thread.currentThread().interrupt()
                    }
                })
        }
    }

    /** Visits `itemsToVisit` and returns the next batch of items to visit.  */
    @Throws(java.lang.InterruptedException::class)
    protected abstract fun visit(itemsToVisit: MutableCollection<T?>?): MutableCollection<T?>?

    companion object {
        protected val DEFAULT_THREAD_COUNT: Int = java.lang.Runtime.getRuntime().availableProcessors()

        private const val DEFAULT_MAX_BATCH_SIZE = 8192

        /**
         * Default [ErrorClassifier] used by the visitor returned by [ ][.AbstractShardedVisitor].
         */
        val ERROR_CLASSIFIER: com.google.devtools.build.lib.concurrent.ErrorClassifier =
            object : com.google.devtools.build.lib.concurrent.ErrorClassifier() {
                override fun classifyException(e: java.lang.Exception?): com.google.devtools.build.lib.concurrent.ErrorClassifier.ErrorClassification {
                    return if (e is java.lang.RuntimeException)
                        com.google.devtools.build.lib.concurrent.ErrorClassifier.ErrorClassification.CRITICAL_AND_LOG
                    else
                        com.google.devtools.build.lib.concurrent.ErrorClassifier.ErrorClassification.NOT_CRITICAL
                }
            }
    }
}
