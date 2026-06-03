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

import com.google.common.base.Function
import com.google.common.collect.ImmutableList
import com.google.common.collect.Lists
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import com.google.devtools.build.lib.concurrent.RequestBatching.CallbackMultiplexer
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import java.util.concurrent.Executor
import kotlin.collections.ArrayList
import kotlin.collections.MutableList

@RunWith(JUnit4::class)
class EagerRequestBatcherTest {
    @Test
    @Throws(Exception::class)
    fun simpleSubmit_executes() {
        val batcher: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            EagerRequestBatcher.< Request, Response>create<com.google.devtools.build.lib.concurrent.EagerRequestBatcherTest.Request?, com.google.devtools.build.lib.concurrent.EagerRequestBatcherTest.Response?>(
        { requests -> Futures.immediateFuture<V?>(respondTo(requests)) },
        MoreExecutors.directExecutor(),
        QueuePool<Request?, Response?>(10),  /* targetConcurrentRequests= */
        1,
        MoreExecutors.directExecutor())
        val response: ListenableFuture<Response?> = batcher.submit(Request(1))
        Truth.assertThat(response.get()).isEqualTo(Response(1))
    }

    @Test
    @Throws(Exception::class)
    fun verifyEagerSendingBatchingAndCompletionFlows() {
        val multiplexer = SettableMultiplexer()
        val strategy: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RequestBatching.createBatchExecutionStrategy(multiplexer, MoreExecutors.directExecutor())
        val batcher: EagerRequestBatcher =
            EagerRequestBatcher(
                strategy,
                QueuePool<Request?, Response?>(10),  /* targetConcurrentRequests= */
                2,
                MoreExecutors.directExecutor()
            )

        // Scenario A: Eager sending due to low concurrency.
        // State established:
        // - targetConcurrentRequests = 2, maxBatchSize = 10.
        // - R1 and R2 are submitted and eagerly executed immediately as single-item batches
        //   because inFlightCount < targetConcurrentRequests.
        // - Active batches: [R1], [R2] -> inFlightCount = 2.
        // - R3 and R4 are submitted but queued because inFlightCount (2)
        //   >= targetConcurrentRequests (2) and queue size (2) < maxBatchSize (10).
        val r1: ListenableFuture<Response?> = batcher.submit(Request(1))
        assertThat(batcher.getInFlightCount()).isEqualTo(1)
        assertThat(batcher.getQueueSize()).isEqualTo(0)
        val batch1 = multiplexer.queue.take()
        Truth.assertThat(batch1.requests).containsExactly(Request(1))

        val r2: ListenableFuture<Response?> = batcher.submit(Request(2))
        assertThat(batcher.getInFlightCount()).isEqualTo(2)
        assertThat(batcher.getQueueSize()).isEqualTo(0)
        val batch2 = multiplexer.queue.take()
        Truth.assertThat(batch2.requests).containsExactly(Request(2))

        val r3: ListenableFuture<Response?>? = batcher.submit(Request(3))
        assertThat(batcher.getInFlightCount()).isEqualTo(2)
        assertThat(batcher.getQueueSize()).isEqualTo(1)
        Truth.assertThat(multiplexer.queue).isEmpty()

        val r4: ListenableFuture<Response?>? = batcher.submit(Request(4))
        assertThat(batcher.getInFlightCount()).isEqualTo(2)
        assertThat(batcher.getQueueSize()).isEqualTo(2)
        Truth.assertThat(multiplexer.queue).isEmpty()

        // Scenario B: Batching due to high concurrency (Max Batch Size trigger).
        // State carried over from A:
        // - Active batches: [R1], [R2] -> inFlightCount = 2.
        // - Queued requests: [R3, R4] -> queueSize = 2.
        // Action: Submit 8 more requests (R5 to R12) to reach maxBatchSize (10).
        // State established:
        // - The queue reaches maxBatchSize (10) and is flushed immediately as a batch [R3-R12].
        // - Active batches: [R1], [R2], [R3-R12] -> inFlightCount = 3.
        // - R13 is submitted and queued because inFlightCount (3) >= targetConcurrentRequests (2)
        //   and queue size (1) < maxBatchSize (10).
        val queuedResponses: MutableList<ListenableFuture<Response?>?> = ArrayList<ListenableFuture<Response?>?>()
        queuedResponses.add(r3)
        queuedResponses.add(r4)
        for (i in 5..12) {
            queuedResponses.add(batcher.submit(Request(i)))
        }
        assertThat(batcher.getInFlightCount()).isEqualTo(3)
        assertThat(batcher.getQueueSize()).isEqualTo(0)
        val batch3 = multiplexer.queue.take()
        Truth.assertThat(batch3.requests).hasSize(10)
        Truth.assertThat(
            batch3.requests!!.stream().map<Int?>(Request::x).collect(ImmutableList.toImmutableList<Int?>())
        )
            .containsExactly(3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
            .inOrder()

        val r13: ListenableFuture<Response?> = batcher.submit(Request(13))
        assertThat(batcher.getInFlightCount()).isEqualTo(3)
        assertThat(batcher.getQueueSize()).isEqualTo(1)

        // Scenario C: Completion triggering queued work.
        // State carried over from B:
        // - Active batches: [R1], [R2], [R3-R12] -> inFlightCount = 3.
        // - Queued requests: [R13] -> queueSize = 1.
        // Action: Complete active batches and observe queue draining.
        // State transitions:
        // 1. Complete [R1] -> inFlightCount decrements to 2. R13 remains queued because
        //    inFlightCount (2) is not < targetConcurrentRequests (2).
        // 2. Complete [R2] -> inFlightCount decrements to 1. Since inFlightCount (1) <
        //    targetConcurrentRequests (2), the queued R13 is eagerly flushed and executed.
        // - Active batches: [R3-R12], [R13] -> inFlightCount = 2.
        // - Queued requests: none.
        batch1.setSimpleResponses()
        Truth.assertThat(r1.get()).isEqualTo(Response(1))
        assertThat(batcher.getInFlightCount()).isEqualTo(2)
        assertThat(batcher.getQueueSize()).isEqualTo(1)
        Truth.assertThat(multiplexer.queue).isEmpty()

        batch2.setSimpleResponses()
        Truth.assertThat(r2.get()).isEqualTo(Response(2))
        assertThat(batcher.getInFlightCount()).isEqualTo(2)
        assertThat(batcher.getQueueSize()).isEqualTo(0)
        val batch4 = multiplexer.queue.take()
        Truth.assertThat(batch4.requests).containsExactly(Request(13))

        batch3.setSimpleResponses()
        batch4.setSimpleResponses()
        Truth.assertThat(r13.get()).isEqualTo(Response(13))
        for (i in queuedResponses.indices) {
            Truth.assertThat(queuedResponses.get(i)!!.get()).isEqualTo(EagerRequestBatcherTest.Response(i + 3))
        }
    }

    @Test
    @Throws(Exception::class)
    fun synchronousException_decrementsInFlightAndFailsFutures() {
        val failure = RuntimeException("Sync Failure")
        val faultyMultiplexer: Multiplexer<Request?, Response?> =
            Multiplexer { requests ->
                throw failure
            }
        val strategy: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RequestBatching.createBatchExecutionStrategy(faultyMultiplexer, MoreExecutors.directExecutor())
        val batcher: EagerRequestBatcher =
            EagerRequestBatcher(
                strategy,
                QueuePool<Request?, Response?>(10),  /* targetConcurrentRequests= */
                1,
                MoreExecutors.directExecutor()
            )

        val response: ListenableFuture<Response?> = batcher.submit(Request(1))

        assertThat(batcher.getInFlightCount()).isEqualTo(0)
        val thrown: ExecutionException? = Assert.assertThrows<ExecutionException?>(
            ExecutionException::class.java,
            ThrowingRunnable { response.get() })
        Truth.assertThat(thrown).hasCauseThat().isEqualTo(failure)

        // Verify we can still submit after failure
        val multiplexer = SettableMultiplexer()
        val goodStrategy: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RequestBatching.createBatchExecutionStrategy(multiplexer, MoreExecutors.directExecutor())
        val goodBatcher: EagerRequestBatcher =
            EagerRequestBatcher(
                goodStrategy,
                QueuePool<Request?, Response?>(10),  /* targetConcurrentRequests= */
                1,
                MoreExecutors.directExecutor()
            )

        val goodResponse: ListenableFuture<Response?> = goodBatcher.submit(Request(2))
        assertThat(goodBatcher.getInFlightCount()).isEqualTo(1)
        multiplexer.queue.take().setSimpleResponses()
        Truth.assertThat(goodResponse.get()).isEqualTo(Response(2))
    }

    @Test
    @Throws(Exception::class)
    fun executor_runsCallbacksOnInjectedExecutor() {
        val multiplexer = SettableMultiplexer()
        val strategy: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RequestBatching.createBatchExecutionStrategy(multiplexer, MoreExecutors.directExecutor())
        val executorThreads: ConcurrentLinkedQueue<Thread> = ConcurrentLinkedQueue<Thread>()
        val recordingExecutor =
            Executor { command: Runnable? ->
                Thread(
                    Runnable {
                        executorThreads.add(Thread.currentThread())
                        command!!.run()
                    })
                    .start()
            }

        val batcher: EagerRequestBatcher =
            EagerRequestBatcher(
                strategy,
                QueuePool<Request?, Response?>(10),  /* targetConcurrentRequests= */
                1,
                recordingExecutor
            )

        val r1: ListenableFuture<Response?> = batcher.submit(Request(1))
        val batch1 = multiplexer.queue.take()

        // Queue a second request
        val r2: ListenableFuture<Response?> = batcher.submit(Request(2))
        assertThat(batcher.getQueueSize()).isEqualTo(1)

        // Complete the first batch. This should trigger onBatchComplete on the recordingExecutor.
        batch1.setSimpleResponses()
        r1.get() // Wait for completion

        // Wait for the second batch to be executed (it should be triggered by onBatchComplete)
        val batch2 = multiplexer.queue.take()
        Truth.assertThat(batch2.requests).containsExactly(Request(2))

        // Verify that onBatchComplete ran on a thread from recordingExecutor
        Truth.assertThat(executorThreads).isNotEmpty()
        val callbackThread: Thread? = executorThreads.peek()
        Truth.assertThat(callbackThread).isNotEqualTo(Thread.currentThread())

        batch2.setSimpleResponses()
        Truth.assertThat(r2.get()).isEqualTo(Response(2))
    }

    @Test
    @Throws(Exception::class)
    fun queuePool_safety_nestedSubmissions() {
        val multiplexer = SettableMultiplexer()
        val batcherRef: AtomicReference<EagerRequestBatcher<Request?, Response?>?> =
            AtomicReference<EagerRequestBatcher<Request?, Response?>?>()
        val nestedResponseRef: AtomicReference<ListenableFuture<Response?>?> =
            AtomicReference<ListenableFuture<Response?>?>()

        val interceptingMultiplexer: Multiplexer<Request?, Response?>? =
            object : Multiplexer<Request?, Response?>() {
                private var submittedNested = false

                public override fun execute(requests: MutableList<Request>?): ListenableFuture<MutableList<Response?>?> {
                    if (!submittedNested) {
                        submittedNested = true
                        // Submit a nested request. This will run on the same thread.
                        nestedResponseRef.set(batchRefRef<Any?, Any?>(batcherRef).submit(Request(99)))
                    }
                    return multiplexer.execute(requests)
                }
            }

        val goodStrategy: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RequestBatching.createBatchExecutionStrategy(interceptingMultiplexer, MoreExecutors.directExecutor())
        val batcher: EagerRequestBatcher =
            EagerRequestBatcher(
                goodStrategy,
                QueuePool<Request?, Response?>(10),  /* targetConcurrentRequests= */
                1,
                MoreExecutors.directExecutor()
            )
        batcherRef.set(batcher)

        val r1: ListenableFuture<Response?> = batcher.submit(Request(1))

        // At this point, interceptingMultiplexer should have run, and submitted R99.
        // R1 triggered immediate execution, so it called execute().
        // Inside execute(), R99 was submitted.
        // Since targetConcurrentRequests is 1, and inFlightCount is 1 (for R1), R99 should be queued.
        assertThat(batcher.getQueueSize()).isEqualTo(1)
        assertThat(batcher.getInFlightCount()).isEqualTo(1)

        val batch1 = multiplexer.queue.take()
        Truth.assertThat(batch1.requests).containsExactly(Request(1))

        // Complete batch 1. This should trigger execution of R99.
        batch1.setSimpleResponses()
        r1.get()

        val batch2 = multiplexer.queue.take()
        Truth.assertThat(batch2.requests).containsExactly(Request(99))
        batch2.setSimpleResponses()

        Truth.assertThat(nestedResponseRef.get().get()).isEqualTo(Response(99))
    }

    @Test
    @Throws(Exception::class)
    fun callbackMultiplexer_integration() {
        val events: LinkedBlockingQueue<String?> = LinkedBlockingQueue<String?>()
        val callbackMultiplexer: CallbackMultiplexer<Request?, Response?> =
            CallbackMultiplexer { requests, sinks ->
                events.add("execute")
                for (i in 0..<requests.size()) {
                    sinks.get(i).acceptResponse(Response(requests.get(i).x()))
                }
                { events.add("cleanup") }
            }

        val batcher: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            EagerRequestBatcher.< Request, Response>createWithCallbackMultiplexer<com.google.devtools.build.lib.concurrent.EagerRequestBatcherTest.Request?, com.google.devtools.build.lib.concurrent.EagerRequestBatcherTest.Response?>(
        callbackMultiplexer,
        QueuePool<Request?, Response?>(2),  /* targetConcurrentRequests= */
        1,
        MoreExecutors.directExecutor())

        val r1: ListenableFuture<Response?> = batcher.submit(Request(1))
        Truth.assertThat(r1.get()).isEqualTo(Response(1))
        Truth.assertThat(events.take()).isEqualTo("execute")
        Truth.assertThat(events.take()).isEqualTo("cleanup")
    }

    @Test
    @Throws(Exception::class)
    fun futureMultiplexer_integration() {
        val futureMultiplexer: FutureMultiplexer<Request?, Response?> =
            FutureMultiplexer { requests, sinks ->
                for (i in 0..<requests.size()) {
                    sinks.get(i).acceptFuture(Futures.immediateFuture<V?>(Response(requests.get(i).x())))
                }
            }

        val batcher: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            EagerRequestBatcher.< Request, Response>createWithFutureMultiplexer<com.google.devtools.build.lib.concurrent.EagerRequestBatcherTest.Request?, com.google.devtools.build.lib.concurrent.EagerRequestBatcherTest.Response?>(
        futureMultiplexer,
        QueuePool<Request?, Response?>(2),  /* targetConcurrentRequests= */
        1,
        MoreExecutors.directExecutor())

        val r1: ListenableFuture<Response?> = batcher.submit(Request(1))
        Truth.assertThat(r1.get()).isEqualTo(Response(1))
    }

    @Test
    @Throws(Exception::class)
    fun sharedQueuePool_worksWithoutIssues() {
        val pool: QueuePool<Request?, Response?> = QueuePool<Request?, Response?>(10)
        val multiplexer1 = SettableMultiplexer()
        val multiplexer2 = SettableMultiplexer()

        val strategy1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RequestBatching.createBatchExecutionStrategy(multiplexer1, MoreExecutors.directExecutor())
        val strategy2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RequestBatching.createBatchExecutionStrategy(multiplexer2, MoreExecutors.directExecutor())

        val batcher1: EagerRequestBatcher =
            EagerRequestBatcher(
                strategy1, pool,  /* targetConcurrentRequests= */1, MoreExecutors.directExecutor()
            )
        val batcher2: EagerRequestBatcher =
            EagerRequestBatcher(
                strategy2, pool,  /* targetConcurrentRequests= */1, MoreExecutors.directExecutor()
            )

        val testThread =
            Thread(
                Runnable {
                    try {
                        val r1: ListenableFuture<Response?> = batcher1.submit(Request(1))
                        val batch1 = multiplexer1.queue.take()
                        batch1.setSimpleResponses()
                        r1.get()

                        val r2: ListenableFuture<Response?> = batcher2.submit(Request(2))
                        val batch2 = multiplexer2.queue.take()
                        batch2.setSimpleResponses()
                        r2.get()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw RuntimeException(e)
                    } catch (e: ExecutionException) {
                        throw RuntimeException(e)
                    }
                })
        testThread.start()
        testThread.join()
    }

    @Test
    fun parameterValidation() {
        Assert.assertThrows<IllegalArgumentException?>(
            IllegalArgumentException::class.java,
            ThrowingRunnable { QueuePool<Any?, Any?>(0) })
        Assert.assertThrows<IllegalArgumentException?>(
            IllegalArgumentException::class.java,
            ThrowingRunnable { QueuePool<Any?, Any?>(-1) })

        val pool: QueuePool<Request?, Response?> = QueuePool<Request?, Response?>(10)
        val strategy: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RequestBatching.createBatchExecutionStrategy(SettableMultiplexer(), MoreExecutors.directExecutor())

        Assert.assertThrows<IllegalArgumentException?>(
            IllegalArgumentException::class.java,
            ThrowingRunnable { EagerRequestBatcher(strategy, pool, 0, MoreExecutors.directExecutor()) })
        Assert.assertThrows<IllegalArgumentException?>(
            IllegalArgumentException::class.java,
            ThrowingRunnable { EagerRequestBatcher(strategy, pool, -1, MoreExecutors.directExecutor()) })
    }

    private class SettableMultiplexer : Multiplexer<Request?, Response?> {
        private val queue: LinkedBlockingQueue<BatchedOperations> = LinkedBlockingQueue<BatchedOperations>()

        public override fun execute(requests: MutableList<Request>?): ListenableFuture<MutableList<Response?>?> {
            val responses = SettableFuture.create<MutableList<Response?>?>()
            queue.add(BatchedOperations(requests, responses))
            return responses
        }
    }

    @kotlin.jvm.JvmRecord
    private data class BatchedOperations(
        val requests: MutableList<Request>?,
        val responses: SettableFuture<MutableList<Response?>?>?
    ) {
        fun setSimpleResponses() {
            this.responses!!.set(Companion.respondTo(this.requests!!))
        }
    }

    @kotlin.jvm.JvmRecord
    private data class Request(val x: Int)

    @kotlin.jvm.JvmRecord
    private data class Response(val x: Int)

    companion object {
        private fun <T, R> batchRefRef(
            ref: AtomicReference<EagerRequestBatcher<T?, R?>?>
        ): EagerRequestBatcher<T?, R?>? {
            return ref.get()
        }

        private fun respondTo(requests: MutableList<Request>): MutableList<Response?> {
            return Lists.transform<Request?, Response?>(
                requests,
                Function { request: Request? -> Response(request!!.x) })
        }
    }
}
