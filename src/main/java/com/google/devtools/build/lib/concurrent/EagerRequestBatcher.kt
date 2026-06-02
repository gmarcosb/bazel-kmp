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
package com.google.devtools.build.lib.concurrent

/**
 * A lock-based, asynchronous request batcher designed for eager execution.
 * 
 * 
 * This class batches unary requests and executes them together. It eagerly dispatches batches as
 * soon as they reach `maxBatchSize` or when the number of active concurrent requests is below
 * `targetConcurrentRequests`.
 * 
 * 
 * Submissions do not block waiting for batch execution or queue capacity. While the class uses
 * lightweight synchronization (synchronized) to ensure thread-safe queue access, the lock is held
 * only for brief in-memory updates, ensuring that calling threads never block on I/O or
 * backpressure.
 * 
 * 
 * **Locking Efficiency:** While the class uses a lock to ensure thread-safe queue operations,
 * the lock hold time is extremely short. Under high load, the relatively more expensive [ ] pool lookup is amortized over the batch size (occurring only once per `maxBatchSize` submissions), keeping the average lock hold time close to a few nanoseconds.
 * 
 * 
 * **Virtual Thread Performance:** While this class will function correctly when called from
 * virtual threads, performance will likely be very poor. It relies on [ThreadLocal] via
 * [QueuePool] for optimization, which does not behave predictably or efficiently with
 * short-lived virtual threads. Additionally, it uses monitor-based locks (synchronized) which can
 * cause pinning of carrier threads.
 */
class EagerRequestBatcher<RequestT, ResponseT> @com.google.common.annotations.VisibleForTesting internal constructor(
    batchExecutionStrategy: com.google.devtools.build.lib.concurrent.RequestBatching.BatchExecutionStrategy<RequestT?, ResponseT?>,
    pool: com.google.devtools.build.lib.concurrent.QueuePool<RequestT?, ResponseT?>,
    targetConcurrentRequests: Int,
    executor: java.util.concurrent.Executor
) {
    private val lock = Any()

    @javax.annotation.concurrent.GuardedBy("lock")
    private var queue: MutableList<com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>?>

    @javax.annotation.concurrent.GuardedBy("lock")
    private var inFlightCount = 0

    private val maxBatchSize: Int
    private val targetConcurrentRequests: Int

    /** Executor used for batch completion work, which may include sending batches.  */
    private val executor: java.util.concurrent.Executor

    private val batchExecutionStrategy: com.google.devtools.build.lib.concurrent.RequestBatching.BatchExecutionStrategy<RequestT?, ResponseT?>
    private val pool: com.google.devtools.build.lib.concurrent.QueuePool<RequestT?, ResponseT?>

    // Package-private constructor for testing and internal use
    init {
        this.batchExecutionStrategy = batchExecutionStrategy
        this.pool = pool
        this.maxBatchSize = pool.getMaxBatchSize()
        com.google.common.base.Preconditions.checkArgument(
            targetConcurrentRequests >= 1,
            "targetConcurrentRequests must be >= 1"
        )
        this.targetConcurrentRequests = targetConcurrentRequests
        this.executor = executor
        this.queue =
            java.util.ArrayList<com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>?>(
                maxBatchSize
            )
    }

    fun submit(request: RequestT?): com.google.common.util.concurrent.ListenableFuture<ResponseT?> {
        val operation: com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?> =
            com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>(request)
        var batch: MutableList<com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>?>? =
            null

        synchronized(lock) {
            queue.add(operation)
            // Rule 1 (Eager): Execute immediately if queue reaches maxBatchSize.
            // Rule 2 (Target Concurrency): Execute immediately if in-flight count is below target.
            if (queue.size() >= maxBatchSize || inFlightCount < targetConcurrentRequests) {
                batch = swapQueue()
                inFlightCount++
            }
        }

        if (batch != null) {
            execute(copyAndRecycle(batch))
        }

        return operation
    }

    private fun onBatchComplete() {
        var batch: MutableList<com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>?>? =
            null
        synchronized(lock) {
            if (!queue.isEmpty() && inFlightCount <= targetConcurrentRequests) {
                batch = swapQueue()
                // A batch has just completed, but the queue contents will be sent immediately so
                // inFlightCount does not change.
            } else {
                inFlightCount--
            }
        }

        if (batch != null) {
            execute(copyAndRecycle(batch))
        }
    }

    /**
     * Swaps the queue with a clean one from the pool.
     * 
     * 
     * IMPORTANT: after this swap, a batch must be recycled into [.pool] before any other
     * calls to [QueuePool.getQueue] from this thread.
     * 
     * @return the queue at the moment this method was called
     */
    @javax.annotation.concurrent.GuardedBy("lock")
    private fun swapQueue(): MutableList<com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>?>? {
        val batch: MutableList<com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>?>? =
            queue
        queue = pool.getQueue()
        return batch
    }

    private fun copyAndRecycle(
        batch: MutableList<com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>?>
    ): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>> {
        val copy: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>> =
            com.google.common.collect.ImmutableList.copyOf<com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>?>(
                batch
            )
        pool.recycleQueue(batch)
        return copy
    }

    private fun execute(batch: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>>) {
        val batchFuture: com.google.common.util.concurrent.ListenableFuture<*>
        try {
            batchFuture =
                batchExecutionStrategy.executeBatch(
                    com.google.common.collect.Lists.transform<com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>?, RequestT?>(
                        batch,
                        com.google.common.base.Function { obj: com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>? -> obj.request() }),
                    batch
                )
        } catch (t: Throwable) {
            handleSynchronousException(batch, t)
            return
        }

        batchFuture.addListener(java.lang.Runnable { this.onBatchComplete() }, executor)
    }

    private fun handleSynchronousException(
        operations: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>>,
        t: Throwable?
    ) {
        synchronized(lock) {
            inFlightCount--
        }
        for (operation in operations) {
            operation.acceptFailure(t)
        }
    }

    // Package-private for testing
    @com.google.common.annotations.VisibleForTesting
    fun getInFlightCount(): Int {
        synchronized(lock) {
            return inFlightCount
        }
    }

    @get:com.google.common.annotations.VisibleForTesting
    val queueSize: Int
        get() {
            synchronized(lock) {
                return queue.size()
            }
        }

    companion object {
        /** Creates a batcher with standard Multiplexer.  */
        fun <RequestT, ResponseT> create(
            multiplexer: com.google.devtools.build.lib.concurrent.RequestBatching.Multiplexer<RequestT?, ResponseT?>?,
            responseDistributionExecutor: java.util.concurrent.Executor?,
            pool: com.google.devtools.build.lib.concurrent.QueuePool<RequestT?, ResponseT?>,
            targetConcurrentRequests: Int,
            executor: java.util.concurrent.Executor
        ): EagerRequestBatcher<RequestT?, ResponseT?> {
            return com.google.devtools.build.lib.concurrent.EagerRequestBatcher<RequestT?, ResponseT?>(
                com.google.devtools.build.lib.concurrent.RequestBatching.createBatchExecutionStrategy<RequestT?, ResponseT?>(
                    multiplexer,
                    responseDistributionExecutor
                ),
                pool,
                targetConcurrentRequests,
                executor
            )
        }

        /** Creates a batcher with CallbackMultiplexer.  */
        fun <RequestT, ResponseT>
                createWithCallbackMultiplexer(
            multiplexer: com.google.devtools.build.lib.concurrent.RequestBatching.CallbackMultiplexer<RequestT?, ResponseT?>?,
            pool: com.google.devtools.build.lib.concurrent.QueuePool<RequestT?, ResponseT?>,
            targetConcurrentRequests: Int,
            executor: java.util.concurrent.Executor
        ): EagerRequestBatcher<RequestT?, ResponseT?> {
            return com.google.devtools.build.lib.concurrent.EagerRequestBatcher<RequestT?, ResponseT?>(
                com.google.devtools.build.lib.concurrent.RequestBatching.createCallbackBatchExecutionStrategy<RequestT?, ResponseT?>(
                    multiplexer
                ),
                pool,
                targetConcurrentRequests,
                executor
            )
        }

        /** Creates a batcher with FutureMultiplexer.  */
        fun <RequestT, ResponseT>
                createWithFutureMultiplexer(
            multiplexer: com.google.devtools.build.lib.concurrent.RequestBatching.FutureMultiplexer<RequestT?, ResponseT?>?,
            pool: com.google.devtools.build.lib.concurrent.QueuePool<RequestT?, ResponseT?>,
            targetConcurrentRequests: Int,
            executor: java.util.concurrent.Executor
        ): EagerRequestBatcher<RequestT?, ResponseT?> {
            return com.google.devtools.build.lib.concurrent.EagerRequestBatcher<RequestT?, ResponseT?>(
                com.google.devtools.build.lib.concurrent.RequestBatching.createFutureBatchExecutionStrategy<RequestT?, ResponseT?>(
                    multiplexer
                ),
                pool,
                targetConcurrentRequests,
                executor
            )
        }
    }
}
