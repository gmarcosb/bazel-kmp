// Copyright 2024 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.unsafe.UnsafeProvider
import java.util.concurrent.Executors

/**
 * Provides a unary request-response interface but implements batching.
 * 
 * 
 * Clients should provide a [Multiplexer] implementation that performs the actual batched
 * operations.
 * 
 * 
 * This class is thread-safe.
 * 
 * 
 * Non-final for mockability.
 */
// TODO: b/359688989 - clean this up
class RequestBatcher<RequestT, ResponseT> @com.google.common.annotations.VisibleForTesting internal constructor(
    queueDrainingExecutor: java.util.concurrent.Executor,
    batchExecutionStrategy: com.google.devtools.build.lib.concurrent.RequestBatching.BatchExecutionStrategy<RequestT?, ResponseT?>,
    maxBatchSize: Int,
    maxConcurrentRequests: Int,
    countersAddress: Long,
    queue: com.google.devtools.build.lib.concurrent.ConcurrentFifo<com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>?>
) {
    /**
     * Executor dedicated to draining the queue, specifically the [ ][.continueToNextBatchOrBecomeIdle] method.
     * 
     * 
     * **Purpose of Isolation:** This executor is isolated to prevent potential deadlocks. The
     * [.submit] method can block if the task queue is full. If all threads in the client's
     * executor become blocked waiting to submit tasks, only [.continueToNextBatchOrBecomeIdle]
     * can free up space in the queue. Scheduling this continuation logic on the same, potentially
     * blocked, client executor would lead to a deadlock.
     * 
     * 
     * **Deadlock Avoidance:** As long as [.continueToNextBatchOrBecomeIdle] does not
     * contain blocking operations (which is true in the current implementation), using a separate
     * executor is sufficient to prevent this specific deadlock scenario.
     */
    private val queueDrainingExecutor: java.util.concurrent.Executor

    private val batchExecutionStrategy: com.google.devtools.build.lib.concurrent.RequestBatching.BatchExecutionStrategy<RequestT?, ResponseT?>

    /**
     * Reads this many at a time when constructing a batch.
     * 
     * 
     * Note that since [.populateBatch] always begins with 1 pair, the resulting batch size
     * is one more than this.
     */
    private val maxBatchSize: Int

    /** Number of active workers to target.  */
    private val maxConcurrentRequests: Int

    /**
     * Address of an integer containing two counters.
     * 
     * 
     * Having two counters in the same integer enables simultaneous, atomic updates of both values.
     * 
     * 
     *  * **request-responses count**: the lower 20-bits (occupying the bits of [       ][.REQUEST_COUNT_MASK]) contain a lower bound of request-responses in [.queue]. This
     * is incremented after successful enqueuing and decremented before dequeuing. This counter
     * value is never more than the size of the queue so it can be used to guarantee that the
     * number of calls to [ConcurrentFifo.take] do not exceed the number of successful
     * [ConcurrentFifo.tryAppend] calls.
     *  * **active-workers count**: the upper 12-bits (starting from [       ][.ACTIVE_WORKERS_COUNT_BIT_OFFSET]) contain the number of active workers.
     * 
     */
    private val countersAddress: Long

    private val queue: com.google.devtools.build.lib.concurrent.ConcurrentFifo<com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>?>

    /**
     * Submits a request, subject to batching.
     * 
     * 
     * This method *blocks* when the queue is full.
     * 
     * 
     * Callers should consider processing the response on a different executor if processing is
     * expensive to avoid delaying work pending other responses in the batch.
     */
    // TODO: b/386384684 - remove Unsafe usage
    fun submit(request: RequestT?): com.google.common.util.concurrent.ListenableFuture<ResponseT?> {
        val requestResponse: com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?> =
            com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>(request)

        // Tries to start a worker as long as the active worker count is less than
        // `maxConcurrentRequests`.
        while (true) {
            val snapshot: Int = com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.UNSAFE.getIntVolatile(
                null,
                countersAddress
            )
            val activeWorkers =
                snapshot ushr com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.ACTIVE_WORKERS_COUNT_BIT_OFFSET
            if (activeWorkers >= maxConcurrentRequests) {
                break
            }
            if (com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.UNSAFE.compareAndSwapInt(
                    null,
                    countersAddress,
                    snapshot,
                    snapshot + com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.ONE_ACTIVE_WORKER
                )
            ) {
                // An active worker was reserved. Starts the worker by executing a batch.
                executeBatch(requestResponse)
                return requestResponse
            }
        }

        while (!queue.tryAppend(requestResponse)) {
            // As of 09/11/2024, this class is only used for remote cache interactions (see
            // b/358347099#comment18). Here, the queue filling up is primarily caused by insufficient
            // network bandwidth. Experiments show that sleeping here improves overall system throughput,
            // even more than increasing the buffer size.
            try {
                java.lang.Thread.sleep(com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.QUEUE_FULL_SLEEP_MS)
            } catch (e: java.lang.InterruptedException) {
                return com.google.common.util.concurrent.Futures.immediateFailedFuture<ResponseT?>(e)
            }
        }

        // Enqueuing succeeded.
        while (true) {
            val snapshot: Int = com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.UNSAFE.getIntVolatile(
                null,
                countersAddress
            ) // pessimistic read
            val activeWorkers =
                snapshot ushr com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.ACTIVE_WORKERS_COUNT_BIT_OFFSET
            if (activeWorkers >= maxConcurrentRequests) {
                // Increments the request-responses count.
                if (com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.UNSAFE.compareAndSwapInt(
                        null,
                        countersAddress,
                        snapshot,
                        snapshot + com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.ONE_REQUEST
                    )
                ) {
                    // This must not be reached if `activeWorkers` is 0. Guaranteed by the enclosing check.
                    return requestResponse
                }
            } else {
                // This is a less common case where the task was enqueued, but the number of active workers
                // immediately dipped below `targetWorkersCount`. Starts a worker.
                if (com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.UNSAFE.compareAndSwapInt(
                        null,
                        countersAddress,
                        snapshot,
                        snapshot + com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.ONE_ACTIVE_WORKER
                    )
                ) {
                    // Usually, decrementing the request-responses count must precede taking from the queue.
                    // Here, a request-response was just enqueued and the count has not yet been incremented.
                    executeBatch(queue.take())
                    return requestResponse
                }
            }
        }
    }

    fun maxConcurrentRequests(): Int {
        return maxConcurrentRequests
    }

    // TODO: b/386384684 - remove Unsafe usage
    override fun toString(): String {
        val snapshot: Int = com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.UNSAFE.getIntVolatile(
            null,
            countersAddress
        )
        return java.lang.String.format(
            "activeWorkers=%d, requestCount=%d\nqueue=%s\n",
            snapshot ushr com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.ACTIVE_WORKERS_COUNT_BIT_OFFSET,
            snapshot and com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.REQUEST_COUNT_MASK,
            queue
        )
    }

    /**
     * Constructs a batch by polling elements from the queue until it is empty, then executes it.
     * 
     * 
     * After the batch is executed, arranges follow-up work by calling `#continueToNextBatchOrBecomeIdle`.
     * 
     * @param requestResponse a single element to be included in the batch. This ensures the batch is
     * non-empty.
     */
    private fun executeBatch(requestResponse: com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>) {
        val batch: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>> =
            populateBatch(requestResponse)
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
            // Guard against synchronous exceptions from the multiplexer. Fail the batch's futures and
            // schedule continuation to prevent leaking worker slots.
            for (operation in batch) {
                operation.acceptFailure(t)
            }
            queueDrainingExecutor.execute(java.lang.Runnable { this.continueToNextBatchOrBecomeIdle() })
            return
        }
        batchFuture.addListener(java.lang.Runnable { this.continueToNextBatchOrBecomeIdle() }, queueDrainingExecutor)
    }

    /**
     * Polls at most [.maxBatchSize] elements from the [.queue] and creates a batch.
     * 
     * @param requestResponse an element to add to the batch.
     */
    // TODO: b/386384684 - remove Unsafe usage
    private fun populateBatch(
        requestResponse: com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>
    ): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>> {
        val accumulator: com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>?> =
            com.google.common.collect.ImmutableList.builder<com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>?>()
                .add(requestResponse)
        while (true) {
            val snapshot: Int = com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.UNSAFE.getIntVolatile(
                null,
                countersAddress
            )
            val requestCount =
                snapshot and com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.REQUEST_COUNT_MASK
            if (requestCount == 0) {
                break
            }
            val toRead: Int = java.lang.Math.min(maxBatchSize, requestCount)
            if (!com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.UNSAFE.compareAndSwapInt(
                    null,
                    countersAddress,
                    snapshot,
                    snapshot - toRead
                )
            ) {
                continue
            }
            for (i in 0..<toRead) {
                accumulator.add(queue.take())
            }
            break
        }
        return accumulator.build()
    }

    /**
     * Either processes the next batch or releases the held token.
     * 
     * 
     * Tries to process the next batch if enqueued requests are available. Otherwise, stops working
     * and decrements the active worker count.
     */
    // TODO: b/386384684 - remove Unsafe usage
    private fun continueToNextBatchOrBecomeIdle() {
        while (true) {
            val snapshot: Int = com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.UNSAFE.getIntVolatile(
                null,
                countersAddress
            )
            if ((snapshot and com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.REQUEST_COUNT_MASK) == 0) {
                // There are no enqueued requests. Tries to become idle.
                if (com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.UNSAFE.compareAndSwapInt(
                        null,
                        countersAddress,
                        snapshot,
                        snapshot - com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.ONE_ACTIVE_WORKER
                    )
                ) {
                    return
                }
            } else {
                // Tries to reserve an enqueued request-response to begin another batch.
                if (com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.UNSAFE.compareAndSwapInt(
                        null,
                        countersAddress,
                        snapshot,
                        snapshot - com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.ONE_REQUEST
                    )
                ) {
                    executeBatch(queue.take())
                    return
                }
            }
        }
    }


    /**
     * Low-level constructor.
     * 
     * 
     * Caller owns memory addresses used by `queue` and cleanup of memory at `countersAddress`.
     */
    // TODO: b/386384684 - remove Unsafe usage
    init {
        com.google.common.base.Preconditions.checkArgument(
            maxConcurrentRequests > 0,
            "maxConcurrentRequests=%s < 1",
            maxConcurrentRequests
        )
        com.google.common.base.Preconditions.checkArgument(
            maxConcurrentRequests <= com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.ACTIVE_WORKERS_COUNT_MAX,
            "maxConcurrentRequests=%s > %s",
            maxConcurrentRequests,
            com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.ACTIVE_WORKERS_COUNT_MAX
        )
        com.google.common.base.Preconditions.checkArgument(maxBatchSize > 0)
        this.queueDrainingExecutor = queueDrainingExecutor
        this.batchExecutionStrategy = batchExecutionStrategy
        this.maxBatchSize = maxBatchSize
        this.maxConcurrentRequests = maxConcurrentRequests
        this.countersAddress = countersAddress
        this.queue = queue

        // Initializes memory at countersAddress.
        com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.UNSAFE.putInt(null, countersAddress, 0)
    }

    companion object {
        /* This class employs concurrent workers that perform the following cycle:
   *
   *   1. Collect all available request-response pairs from the queue up to `maxBatchSize`.
   *   2. Execute the collected pairs as a batch.
   *
   * We guarantee that every submitted request is handled. The following traces all possible paths a
   * request-response pair can take through the batcher to demonstrate this guarantee.
   *
   * Possible Paths:
   *
   *   1. The pair is present in some `submit` call.
   *   2. The pair is enqueued, but not yet reflected in the request-responses count.
   *   3. The pair is enqueued, and request-responses count has been incremented.
   *
   * Step 1: Initial part of `submit`
   *
   * A. We check the active-workers count. If it's less than `maxConcurrentRequests`, a new worker
   *    is started and the pair is directly assigned to it.
   *
   * B. Otherwise, we enqueue the pair. When the queue is full, we sleep and try again until
   *    enqueuing succeeds. After enqueuing, we proceed to Step 2.
   *
   * Case A bypasses Step 2, and the pair is immediately assigned a worker.
   *
   * Step 2: Request-response Enqueued
   *
   * Step 2 is not atomic with Step 1, so the counters might have changed. We re-check
   * active-workers count.
   *
   * A. If it's already at `maxConcurrentRequests`, we attempt to increment request-responses count
   *    atomically, ensuring active-workers count remains unchanged during the increment. Success
   *    leads to Step 3.
   *
   * B. If active-workers count is below the target (due to concurrent activity), we start a new
   *    worker like in Step 1, and dequeue an arbitrary element to assign to it. This maintains
   *    consistency between queue size and request-responses count. The new worker guarantees
   *    processing of all enqueued request-responses (including the one we just added), even if that
   *    specific request ends up handled by a different worker.
   *
   * Step 3: Request-response Enqueued and request-responses count Incremented
   *
   * The atomic request-responses count increment only happens in Step 2 if active-workers count is
   * already at the target. Workers only stop if request-responses count is 0. Since
   * `maxConcurrentRequests` > 0, there's always at least one active worker to handle the
   * request-response.
   */
        /**
         * A common cleaner shared by all instances.
         * 
         * 
         * Used to free memory allocated by [PaddedAddresses].
         */
        private val cleaner: java.lang.ref.Cleaner = java.lang.ref.Cleaner.create()

        private const val QUEUE_FULL_SLEEP_MS: Long = 100

        /**
         * Creates a batcher that uses a standard [Multiplexer], where a single batch request
         * returns a single future containing a list of all responses positionally aligned with the
         * requests.
         */
        fun <RequestT, ResponseT> create(
            multiplexer: com.google.devtools.build.lib.concurrent.RequestBatching.Multiplexer<RequestT?, ResponseT?>?,
            responseDistributionExecutor: java.util.concurrent.Executor?,
            maxBatchSize: Int,
            maxConcurrentRequests: Int
        ): RequestBatcher<RequestT?, ResponseT?> {
            return com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.createWithStrategy<RequestT?, ResponseT?>(
                com.google.devtools.build.lib.concurrent.RequestBatching.createBatchExecutionStrategy<RequestT?, ResponseT?>(
                    multiplexer,
                    responseDistributionExecutor
                ),
                maxBatchSize,
                maxConcurrentRequests
            )
        }

        /**
         * Creates a batcher that uses a [CallbackMultiplexer], where the implementation pushes
         * results asynchronously to individual [ResponseSink] callbacks for each request in the
         * batch.
         */
        fun <RequestT, ResponseT>
                createWithCallbackMultiplexer(
            multiplexer: com.google.devtools.build.lib.concurrent.RequestBatching.CallbackMultiplexer<RequestT?, ResponseT?>?,
            maxBatchSize: Int,
            maxConcurrentRequests: Int
        ): RequestBatcher<RequestT?, ResponseT?> {
            return com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.createWithStrategy<RequestT?, ResponseT?>(
                com.google.devtools.build.lib.concurrent.RequestBatching.createCallbackBatchExecutionStrategy<RequestT?, ResponseT?>(
                    multiplexer
                ),
                maxBatchSize,
                maxConcurrentRequests
            )
        }

        /**
         * Creates a batcher that uses a [FutureMultiplexer], where the implementation populates
         * individual response futures for each request in the batch.
         */
        fun <RequestT, ResponseT>
                createWithFutureMultiplexer(
            multiplexer: com.google.devtools.build.lib.concurrent.RequestBatching.FutureMultiplexer<RequestT?, ResponseT?>?,
            maxBatchSize: Int,
            maxConcurrentRequests: Int
        ): RequestBatcher<RequestT?, ResponseT?> {
            return com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.createWithStrategy<RequestT?, ResponseT?>(
                com.google.devtools.build.lib.concurrent.RequestBatching.createFutureBatchExecutionStrategy<RequestT?, ResponseT?>(
                    multiplexer
                ),
                maxBatchSize,
                maxConcurrentRequests
            )
        }

        fun <RequestT, ResponseT> createWithStrategy(
            batchExecutionStrategy: com.google.devtools.build.lib.concurrent.RequestBatching.BatchExecutionStrategy<RequestT?, ResponseT?>,
            maxBatchSize: Int,
            maxConcurrentRequests: Int
        ): RequestBatcher<RequestT?, ResponseT?> {
            val baseAddress: Long = com.google.devtools.build.lib.concurrent.PaddedAddresses.createPaddedBaseAddress(4)
            val countersAddress: Long =
                com.google.devtools.build.lib.concurrent.PaddedAddresses.getAlignedAddress(baseAddress,  /* offset= */0)

            val queue: com.google.devtools.build.lib.concurrent.ConcurrentFifo<com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>?> =
                com.google.devtools.build.lib.concurrent.ConcurrentFifo<com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>?>(
                    com.google.devtools.build.lib.concurrent.RequestBatching.Operation::class.java,  /* sizeAddress= */
                    com.google.devtools.build.lib.concurrent.PaddedAddresses.getAlignedAddress(
                        baseAddress,  /* offset= */
                        1
                    ),  /* appendIndexAddress= */
                    com.google.devtools.build.lib.concurrent.PaddedAddresses.getAlignedAddress(
                        baseAddress,  /* offset= */
                        2
                    ),  /* takeIndexAddress= */
                    com.google.devtools.build.lib.concurrent.PaddedAddresses.getAlignedAddress(
                        baseAddress,  /* offset= */
                        3
                    )
                )

            val batcher: RequestBatcher<RequestT?, ResponseT?> =
                com.google.devtools.build.lib.concurrent.RequestBatcher<RequestT?, ResponseT?>( /* queueDrainingExecutor= */
                    Executors.newVirtualThreadPerTaskExecutor(),
                    batchExecutionStrategy,
                    maxBatchSize,
                    maxConcurrentRequests,
                    countersAddress,
                    queue
                )

            com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.cleaner.register(
                batcher,
                com.google.devtools.build.lib.concurrent.AddressFreer(baseAddress)
            )

            return batcher
        }

        private const val REQUEST_COUNT_MASK = 0x0000FFFF
        private const val ONE_REQUEST = 1

        private const val ACTIVE_WORKERS_COUNT_BIT_OFFSET = 20
        private val ONE_ACTIVE_WORKER =
            1 shl com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.ACTIVE_WORKERS_COUNT_BIT_OFFSET
        private const val ACTIVE_WORKERS_COUNT_MAX = 0x00000FFF

        init {
            com.google.common.base.Preconditions.checkState(
                com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.REQUEST_COUNT_MASK == com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.CAPACITY_MASK,
                "Request Count Constants inconsistent with ConcurrentFifo"
            )
            com.google.common.base.Preconditions.checkState(
                com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.ONE_REQUEST == (com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.REQUEST_COUNT_MASK and -com.google.devtools.build.lib.concurrent.RequestBatcher.Companion.REQUEST_COUNT_MASK),
                "Inconsistent Request Count Constants"
            )
        }

        private val UNSAFE: sun.misc.Unsafe = UnsafeProvider.unsafe()
    }
}
