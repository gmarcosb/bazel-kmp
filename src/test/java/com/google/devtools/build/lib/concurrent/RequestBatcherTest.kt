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

import com.google.common.base.Function
import com.google.common.collect.ImmutableList
import com.google.common.collect.Lists
import com.google.common.util.concurrent.*
import com.google.devtools.build.lib.concurrent.PaddedAddresses.createPaddedBaseAddress
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import sun.misc.Unsafe
import java.lang.ref.Cleaner
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import kotlin.collections.ArrayList
import kotlin.collections.MutableList

@RunWith(JUnit4::class)
// TODO: b/359688989 - clean this up
class RequestBatcherTest {
    @Test
    @Throws(Exception::class)
    fun simpleSubmit_executes() {
        // This covers Step 1A in the documentation.
        val batcher: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RequestBatcher.< Request, Response>create<com.google.devtools.build.lib.concurrent.RequestBatcherTest.Request?, com.google.devtools.build.lib.concurrent.RequestBatcherTest.Response?>(
        { requests -> Futures.immediateFuture<V?>(respondTo(requests)) },
        ForkJoinPool.commonPool(),  /* maxBatchSize= */
        255,  /* maxConcurrentRequests= */
        1)
        val response: ListenableFuture<Response?> = batcher.submit(Request(1))
        Truth.assertThat(response.get()).isEqualTo(Response(1))
    }

    @Test
    @Throws(Exception::class)
    fun queueOverflow_sleeps() {
        // This covers the overflow case of Step 1B in the documentation.
        val batchSize = 256

        val multiplexer = SettableMultiplexer()
        val batcher: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RequestBatcher.< Request, Response>create<com.google.devtools.build.lib.concurrent.RequestBatcherTest.Request?, com.google.devtools.build.lib.concurrent.RequestBatcherTest.Response?>(
        multiplexer,
        ForkJoinPool.commonPool(),  /* maxBatchSize= */
        batchSize - 1,  /* maxConcurrentRequests= */
        1)
        val response0: ListenableFuture<Response?> = batcher.submit(Request(0))
        val requestResponses0 = multiplexer.queue.take()

        // The first worker is busy until requestResponse0 is populated.
        val responses = ArrayList<ListenableFuture<Response?>>()
        // With the single available worker being busy, we can completely fill the queue.
        for (i in 0..<ConcurrentFifo.MAX_ELEMENTS) {
            responses.add(batcher.submit(Request(i + 1)))
        }

        // The next request triggers a queue overflow. Since this ends up blocking, we do it in another
        // thread.
        val overflowStarting: CountDownLatch = CountDownLatch(1)
        val overflowAdded: CountDownLatch = CountDownLatch(1)
        // A new thread needs must used here instead of commonPool because there are test environments
        // where commonPool has only a single thread.
        Thread(
            Runnable {
                overflowStarting.countDown()
                responses.add(batcher.submit(RequestBatcherTest.Request(ConcurrentFifo.MAX_ELEMENTS + 1)))
                overflowAdded.countDown()
            })
            .start()

        // The following assertion will occasionally fail if the overflow submit above does not block.
        overflowStarting.await()
        Truth.assertThat(responses).hasSize(ConcurrentFifo.MAX_ELEMENTS)

        // Responding to the first batch enables the overflowing element to enter the queue.
        requestResponses0.setSimpleResponses()
        Truth.assertThat(response0.get()).isEqualTo(Response(0))
        overflowAdded.await()
        Truth.assertThat(responses).hasSize(ConcurrentFifo.MAX_ELEMENTS + 1)

        // Responds to all remaining batches.
        val batchCount = responses.size / batchSize
        Truth.assertThat(responses).hasSize(batchCount * batchSize)

        for (i in 0..<batchCount) {
            multiplexer.queue.take().setSimpleResponses()
        }

        for (i in 0..<ConcurrentFifo.MAX_ELEMENTS + 1) {
            Truth.assertThat(responses.get(i).get()).isEqualTo(Response(i + 1))
        }
    }

    @Test
    @Throws(Exception::class)
    fun submitWithWorkersFull_enqueuesThenExecutes() {
        // This covers Step 1B, Step 2A and Step 3 of the documentation.

        val multiplexer = SettableMultiplexer()
        val batcher: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RequestBatcher.< Request, Response>create<com.google.devtools.build.lib.concurrent.RequestBatcherTest.Request?, com.google.devtools.build.lib.concurrent.RequestBatcherTest.Response?>(
        multiplexer, ForkJoinPool.commonPool(),  /* maxBatchSize= */255,  /* maxConcurrentRequests= */1)
        val response1: ListenableFuture<Response?> = batcher.submit(Request(1))
        val requestResponses1 = multiplexer.queue.take()

        val response2: ListenableFuture<Response?> = batcher.submit(Request(2))
        // The first batch is not yet complete. The 2nd request waits in an internal queue. Ideally, we
        // could make a stronger assertion here, that the 2nd batch executes only after the first one is
        // done.
        Truth.assertThat(multiplexer.queue).isEmpty()

        requestResponses1.setSimpleResponses()

        // With the first batch done, the worker picks up the enqueued 2nd request and executes it.
        val requestResponses2 = multiplexer.queue.take()
        requestResponses2.setSimpleResponses()

        Truth.assertThat(response1.get()).isEqualTo(Response(1))
        Truth.assertThat(response2.get()).isEqualTo(Response(2))
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Test
    @Throws(Exception::class)
    fun concurrentWorkCompletion_startsNewWorker() {
        // This covers Step 1B and Step 2B of the documentation.

        // This test uses fakes to achieve the narrow set of conditions needed to reach this code path.

        val baseAddress: Long = createPaddedBaseAddress(4)

        val queueDrainingExecutor = FakeExecutor()
        val fifo =
            FakeConcurrentFifo(
                getAlignedAddress(baseAddress,  /* offset= */1),
                getAlignedAddress(baseAddress,  /* offset= */2),
                getAlignedAddress(baseAddress,  /* offset= */3)
            )
        val countersAddress: Long = getAlignedAddress(baseAddress,  /* offset= */0)
        val batcher: RequestBatcher<Request?, Response?> =
            RequestBatcher<Request?, Response?>( /* queueDrainingExecutor= */
                queueDrainingExecutor,
                createBatchExecutionStrategy(
                    { requests -> Futures.immediateFuture<V?>(respondTo(requests)) }, ForkJoinPool.commonPool()
                ),  /* maxBatchSize= */
                255,  /* maxConcurrentRequests= */
                1,
                countersAddress,
                fifo
            )
        cleaner.register(batcher, AddressFreer(baseAddress))

        // Submits a request. This starts a worker to run the batch, but it gets blocked on
        // `queueDrainingExecutor` and can't continue.
        val response1: ListenableFuture<Response?> = batcher.submit(Request(1))

        // Submits a 2nd request. This request observes that there are enough active workers so it tries
        // to enqueue an element. It gets blocked at the queue.
        val response2 = SettableFuture.create<ListenableFuture<Response?>?>()
        ForkJoinPool.commonPool().execute(Runnable { response2.set(batcher.submit(Request(2))) })
        // Waits until the 2nd request starts enqueuing.
        fifo.tryAppendTokens.acquireUninterruptibly()

        // Allows the 1st worker to continue. This calls an enqueued `continueToNextBatchOrBecomeIdle`
        // invocation that will cause the 1st worker to go idle.
        queueDrainingExecutor.queue.take().run()

        Truth.assertThat(response1.get()).isEqualTo(Response(1))

        // Verifies that there's absolutely nothing inflight in the batcher.
        Truth.assertThat(UNSAFE.getIntVolatile(null, countersAddress)).isEqualTo(0)

        // Allows the 2nd request to enqueue and complete processing.
        fifo.appendPermits.release()
        queueDrainingExecutor.queue.take().run()

        Truth.assertThat(response2.get()!!.get()).isEqualTo(Response(2))
    }

    @Test
    @Throws(Exception::class)
    fun randomRaces_executeCorrectly() {
        val batcher: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RequestBatcher.< Request, Response>create<com.google.devtools.build.lib.concurrent.RequestBatcherTest.Request?, com.google.devtools.build.lib.concurrent.RequestBatcherTest.Response?>(
        { requests -> Futures.immediateFuture<V?>(respondTo(requests)) },
        ForkJoinPool.commonPool(),  /* maxBatchSize= */
        255,  /* maxConcurrentRequests= */
        4)

        val results: ConcurrentLinkedQueue<ListenableFuture<Void?>?> = ConcurrentLinkedQueue<ListenableFuture<Void?>?>()
        val requestCount = 4000000
        val allStarted: CountDownLatch = CountDownLatch(requestCount)
        for (i in 0..<requestCount) {
            val iForCapture = i
            ForkJoinPool.commonPool()
                .execute(
                    Runnable {
                        results.add(
                            Futures.transformAsync<I?, O?>(
                                batcher.submit(Request(iForCapture)),
                                AsyncFunction { response: I? ->
                                    if (response.x() === iForCapture)
                                        Futures.immediateVoidFuture()
                                    else
                                        Futures.immediateFailedFuture<Any?>(
                                            AssertionError(
                                                String.format(
                                                    "expected %d got %s", iForCapture, response
                                                )
                                            )
                                        )
                                },
                                MoreExecutors.directExecutor()
                            )
                        )
                        allStarted.countDown()
                    })
        }
        allStarted.await()
        // Throws ExecutionException if there are any errors.
        val unused: Any? =
            Futures.whenAllSucceed<Void?>(results).call<Any?>(Callable { null }, MoreExecutors.directExecutor()).get()
    }

    private class FakeExecutor : Executor {
        private val queue: LinkedBlockingQueue<Runnable?> = LinkedBlockingQueue<Runnable?>()

        override fun execute(runnable: Runnable?) {
            queue.add(runnable)
        }
    }

    @Test
    @Throws(Exception::class)
    fun perResponseMultiplexer_simpleSubmit_executes() {
        val batcher: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RequestBatcher.< Request, Response>createWithFutureMultiplexer<com.google.devtools.build.lib.concurrent.RequestBatcherTest.Request?, com.google.devtools.build.lib.concurrent.RequestBatcherTest.Response?>(
        { requests, sinks ->
            assertThat(requests).hasSize(1)
            assertThat(sinks).hasSize(1)
            sinks.get(0).acceptFuture(Futures.immediateFuture<V?>(Response(1)))
        },  /* maxBatchSize= */
        255,  /* maxConcurrentRequests= */
        1)

        val response: ListenableFuture<Response?> = batcher.submit(Request(1))

        Truth.assertThat(response.get()).isEqualTo(Response(1))
    }

    @Test
    @Throws(Exception::class)
    fun perResponseMultiplexer_batching_succeeds() {
        val multiplexer = FutureSettableMultiplexer()
        val batcher: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RequestBatcher.< Request, Response>createWithFutureMultiplexer<com.google.devtools.build.lib.concurrent.RequestBatcherTest.Request?, com.google.devtools.build.lib.concurrent.RequestBatcherTest.Response?>(
        multiplexer,  /* maxBatchSize= */
        1,  // actual batch size is 2
        /* maxConcurrentRequests= */
        1)

        // Block the first worker
        val response1: ListenableFuture<Response?> = batcher.submit(Request(1))
        val batch1 = multiplexer.queue.take()
        Truth.assertThat(batch1.requests).hasSize(1)

        // These will get enqueued because the worker is busy
        val response2: ListenableFuture<Response?> = batcher.submit(Request(2))
        val response3: ListenableFuture<Response?> = batcher.submit(Request(3))
        val response4: ListenableFuture<Response?> = batcher.submit(Request(4))

        // Unblock the first worker, allowing the next batch to be processed.
        batch1.setSimpleSuccessResponses()
        Truth.assertThat(response1.get()).isEqualTo(Response(1))

        // The next batch should contain requests 2 and 3.
        val batch2 = multiplexer.queue.take()
        Truth.assertThat(batch2.requests).hasSize(2)
        Truth.assertThat(batch2.requests!!.stream().map<Int?>(Request::x)).containsExactly(2, 3).inOrder()
        batch2.setSimpleSuccessResponses()

        // The final batch should contain request 4.
        val batch3 = multiplexer.queue.take()
        Truth.assertThat(batch3.requests).hasSize(1)
        Truth.assertThat(batch3.requests!!.get(0).x).isEqualTo(4)
        batch3.setSimpleSuccessResponses()

        // Verify all responses
        Truth.assertThat(response2.get()).isEqualTo(Response(2))
        Truth.assertThat(response3.get()).isEqualTo(Response(3))
        Truth.assertThat(response4.get()).isEqualTo(Response(4))
    }

    @Test
    @Throws(Exception::class)
    fun perResponseMultiplexer_individualResponseFailure_isIsolated() {
        val multiplexer = FutureSettableMultiplexer()
        val batcher: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RequestBatcher.< Request, Response>createWithFutureMultiplexer<com.google.devtools.build.lib.concurrent.RequestBatcherTest.Request?, com.google.devtools.build.lib.concurrent.RequestBatcherTest.Response?>(
        multiplexer,  /* maxBatchSize= */
        1,  // actual batch size is 2
        /* maxConcurrentRequests= */
        1)

        // Blocks the first worker to allow a batch to form.
        val response0: ListenableFuture<Response?> = batcher.submit(Request(0))
        val batch0 = multiplexer.queue.take()
        Truth.assertThat(batch0.requests).hasSize(1)

        // These two will be enqueued and batched together because the worker is busy.
        val response1: ListenableFuture<Response?> = batcher.submit(Request(1))
        val response2: ListenableFuture<Response?> = batcher.submit(Request(2))

        // Unblocks the first worker, allowing the next batch to be processed.
        batch0.setSimpleSuccessResponses()
        Truth.assertThat(response0.get()).isEqualTo(Response(0))

        // Wait for the batch to be sent to the multiplexer.
        val batch = multiplexer.queue.take()
        Truth.assertThat(batch.requests).hasSize(2)

        // Fulfill the first future with success and the second with failure.
        val failure = IllegalStateException("Individual failure")
        batch.settableFutures!!.get(0)!!.set(Response(1))
        batch.settableFutures!!.get(1)!!.setException(failure)

        // Assert the first future succeeded and the second failed correctly.
        Truth.assertThat(response1.get()).isEqualTo(Response(1))
        val thrown: ExecutionException? = Assert.assertThrows<ExecutionException?>(
            ExecutionException::class.java,
            ThrowingRunnable { response2.get() })
        Truth.assertThat(thrown).hasCauseThat().isEqualTo(failure)
    }

    @Test
    @Throws(Exception::class)
    fun perResponseMultiplexer_missingFuture_throwsIllegalState() {
        val futureResponses: LinkedBlockingQueue<SettableFuture<Response?>> =
            LinkedBlockingQueue<SettableFuture<Response?>>()
        val multiplexer: FutureMultiplexer<Request?, Response?>? =
            object : FutureMultiplexer<Request?, Response?>() {
                public override fun execute(
                    requests: MutableList<Request?>?, sinks: ImmutableList<out FutureSink<Response?>?>
                ) {
                    // Faulty implementation: only sets the first future in the batch, and "forgets" to set
                    // the rest.
                    val futureResponse = SettableFuture.create<Response?>()
                    futureResponses.add(futureResponse)
                    sinks.get(0).acceptFuture(futureResponse)
                }
            }

        val batcher: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RequestBatcher.< Request, Response>createWithFutureMultiplexer<com.google.devtools.build.lib.concurrent.RequestBatcherTest.Request?, com.google.devtools.build.lib.concurrent.RequestBatcherTest.Response?>(
        multiplexer,  /* maxBatchSize= */255,  /* maxConcurrentRequests= */1)

        // Blocks the first worker to allow a batch to form.
        val response0: ListenableFuture<Response?> = batcher.submit(Request(0))
        val responseSetter0: SettableFuture<Response?> = futureResponses.take()

        // These two will be batched together.
        val response1: ListenableFuture<Response?> = batcher.submit(Request(1))
        val response2: ListenableFuture<Response?> = batcher.submit(Request(2))

        // Unblocks the first worker, allowing the next batch to be processed.
        responseSetter0.set(Response(0))
        Truth.assertThat(response0.get()).isEqualTo(Response(0))

        // The multiplexer will set the future for request 1, but not for 2.
        futureResponses.take().set(Response(1))
        Truth.assertThat(response1.get()).isEqualTo(Response(1))
        Truth.assertThat(futureResponses).isEmpty()

        val thrown: ExecutionException? = Assert.assertThrows<ExecutionException?>(
            ExecutionException::class.java,
            ThrowingRunnable { response2.get() })
        Truth.assertThat(thrown).hasCauseThat().isInstanceOf(IllegalStateException::class.java)
        Truth.assertThat(thrown)
            .hasCauseThat()
            .hasMessageThat()
            .contains("Future for Request[x=2] is unexpectedly not set")
    }

    @Test
    @Throws(Exception::class)
    fun cancelledRequest_doesNotCrash() {
        val uncaughtException: AtomicReference<Throwable?> = AtomicReference<Throwable?>()
        val crashDetectingExecutor: Executor? =
            object : Executor {
                override fun execute(command: Runnable) {
                    try {
                        command.run()
                    } catch (t: Throwable) {
                        uncaughtException.set(t)
                    }
                }
            }
        val multiplexer = SettableMultiplexer()
        val batcher: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RequestBatcher.< Request, Response>create<com.google.devtools.build.lib.concurrent.RequestBatcherTest.Request?, com.google.devtools.build.lib.concurrent.RequestBatcherTest.Response?>(
        multiplexer,
        crashDetectingExecutor,  /* maxBatchSize= */
        255,  /* maxConcurrentRequests= */
        1)

        val response: ListenableFuture<Response?> = batcher.submit(Request(1))
        response.cancel(true)

        val requestResponses = multiplexer.queue.take()
        requestResponses.setSimpleResponses()

        Truth.assertThat(response.isCancelled()).isTrue()
        Truth.assertThat(uncaughtException.get()).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun callbackMultiplexer_allSucceed() {
        val events: LinkedBlockingQueue<BatcherEvent?> = LinkedBlockingQueue<BatcherEvent?>()
        val batcher: RequestBatcher<Request?, Response?> = createCallbackMultiplexerBatcher(events)

        val response1: ListenableFuture<Response?> = batcher.submit(Request(1))
        // A batch begins executing immediately with response1.
        val firstBatch = events.poll() as BatchOperation

        // The next 2 requests are enqueued.
        val response2: ListenableFuture<Response?> = batcher.submit(Request(2))
        val response3: ListenableFuture<Response?> = batcher.submit(Request(3))

        // No other batches have started and no callbacks have been called.
        Truth.assertThat(events.poll()).isNull()

        firstBatch.defaultReplyAll()
        Truth.assertThat(response1.get()).isEqualTo(Response(1))

        // The done callback is always called first.
        Truth.assertThat(events.take()).isEqualTo(DoneCallbackCalled.INSTANCE)

        val secondBatch = events.take() as BatchOperation
        secondBatch.defaultReplyAll()
        Truth.assertThat(response2.get()).isEqualTo(Response(2))
        Truth.assertThat(response3.get()).isEqualTo(Response(3))

        // The done callback is called after the 2nd batch completes.
        Truth.assertThat(events.take()).isEqualTo(DoneCallbackCalled.INSTANCE)
    }

    private interface BatcherEvent

    private enum class DoneCallbackCalled : BatcherEvent {
        INSTANCE
    }

    private class BatchOperation(
        val requests: MutableList<Request>?,
        sinks: ImmutableList<out ResponseSink<Request?, Response?>>?
    ) : BatcherEvent {
        fun defaultReplyAll() {
            for (i in requests.indices) {
                sinks!!.get(i).acceptResponse(Response(requests!!.get(i).x))
            }
        }

        fun failAll(t: Throwable?) {
            for (sink in sinks!!) {
                sink.acceptFailure(t)
            }
        }

        val sinks: ImmutableList<out ResponseSink<Request?, Response?>>?

        init {
            this.sinks = sinks
        }
    }

    @Test
    @Throws(Exception::class)
    fun callbackMultiplexer_allFail() {
        val events: LinkedBlockingQueue<BatcherEvent?> = LinkedBlockingQueue<BatcherEvent?>()
        val batcher: RequestBatcher<Request?, Response?> = createCallbackMultiplexerBatcher(events)

        val failure = RuntimeException("Test Failure")

        val response1: ListenableFuture<Response?> = batcher.submit(Request(1))

        // A batch begins executing immediately with response1.
        val firstBatch = events.poll() as BatchOperation

        // The next 2 requests are enqueued.
        val response2: ListenableFuture<Response?> = batcher.submit(Request(2))
        val response3: ListenableFuture<Response?> = batcher.submit(Request(3))

        Truth.assertThat(events.poll()).isNull() // No new events have occurred.

        firstBatch.failAll(failure)
        val e1: ExecutionException? = Assert.assertThrows<ExecutionException?>(
            ExecutionException::class.java,
            ThrowingRunnable { response1.get() })
        Truth.assertThat(e1).hasCauseThat().isEqualTo(failure)

        // The done callback is always called before the next batch starts.
        Truth.assertThat(events.take()).isEqualTo(DoneCallbackCalled.INSTANCE)

        val secondBatch = events.take() as BatchOperation
        secondBatch.failAll(failure)

        val e2: ExecutionException? = Assert.assertThrows<ExecutionException?>(
            ExecutionException::class.java,
            ThrowingRunnable { response2.get() })
        Truth.assertThat(e2).hasCauseThat().isEqualTo(failure)
        val e3: ExecutionException? = Assert.assertThrows<ExecutionException?>(
            ExecutionException::class.java,
            ThrowingRunnable { response3.get() })
        Truth.assertThat(e3).hasCauseThat().isEqualTo(failure)

        // The done callback is called after the 2nd batch completes.
        Truth.assertThat(events.take()).isEqualTo(DoneCallbackCalled.INSTANCE)
    }

    @Test
    @Throws(Exception::class)
    fun callbackMultiplexer_mixedSuccessFailure() {
        val events: LinkedBlockingQueue<BatcherEvent?> = LinkedBlockingQueue<BatcherEvent?>()
        val batcher: RequestBatcher<Request?, Response?> = createCallbackMultiplexerBatcher(events)

        val response1: ListenableFuture<Response?> = batcher.submit(Request(1))
        // A batch begins executing immediately with response1.
        val firstBatch = events.poll() as BatchOperation

        // The next 2 requests are enqueued.
        val response2: ListenableFuture<Response?> = batcher.submit(Request(2))
        val response3: ListenableFuture<Response?> = batcher.submit(Request(3))

        Truth.assertThat(events.poll()).isNull() // No new events have occurred.

        firstBatch.defaultReplyAll()
        Truth.assertThat(response1.get()).isEqualTo(Response(1))

        // The done callback is always called before the next batch starts.
        Truth.assertThat(events.take()).isEqualTo(DoneCallbackCalled.INSTANCE)

        val secondBatch = events.take() as BatchOperation

        val failure = IllegalArgumentException("Bad Request")

        Truth.assertThat(secondBatch.sinks).hasSize(2)

        secondBatch.sinks!!.get(0).acceptResponse(Response(secondBatch.requests!!.get(0).x))
        secondBatch.sinks.get(1).acceptFailure(failure)

        Truth.assertThat(response2.get()).isEqualTo(Response(2))
        val e3: ExecutionException? = Assert.assertThrows<ExecutionException?>(
            ExecutionException::class.java,
            ThrowingRunnable { response3.get() })
        Truth.assertThat(e3).hasCauseThat().isEqualTo(failure)

        // The done callback is called after the 2nd batch completes.
        Truth.assertThat(events.take()).isEqualTo(DoneCallbackCalled.INSTANCE)
    }

    @Test
    @Throws(Exception::class)
    fun callbackMultiplexer_nullResponse() {
        val events: LinkedBlockingQueue<BatcherEvent?> = LinkedBlockingQueue<BatcherEvent?>()
        val batcher: RequestBatcher<Request?, Response?> = createCallbackMultiplexerBatcher(events)

        val response1: ListenableFuture<Response?> = batcher.submit(Request(1))

        val batch = events.poll() as BatchOperation
        batch.sinks!!.get(0).acceptResponse(null)

        Truth.assertThat(response1.get()).isNull()

        Truth.assertThat(events.take()).isEqualTo(DoneCallbackCalled.INSTANCE)
    }

    @Test
    @Throws(Exception::class)
    fun callbackMultiplexer_batching() {
        val events: LinkedBlockingQueue<BatcherEvent?> = LinkedBlockingQueue<BatcherEvent?>()
        val batcher: RequestBatcher<Request?, Response?> = createCallbackMultiplexerBatcher(events)

        // These should form three batches: (1), (2, 3), (4, 5)
        val response1: ListenableFuture<Response?> = batcher.submit(Request(1))
        val response2: ListenableFuture<Response?> = batcher.submit(Request(2))
        val response3: ListenableFuture<Response?> = batcher.submit(Request(3))
        val response4: ListenableFuture<Response?> = batcher.submit(Request(4))
        val response5: ListenableFuture<Response?> = batcher.submit(Request(5))

        val batch1 = events.take() as BatchOperation

        Truth.assertThat(batch1.requests).containsExactly(Request(1))
        batch1.defaultReplyAll()

        Truth.assertThat(response1.get()).isEqualTo(Response(1))
        Truth.assertThat(events.take()).isEqualTo(DoneCallbackCalled.INSTANCE)

        val batch2 = events.take() as BatchOperation
        Truth.assertThat(batch2.requests).containsExactly(Request(2), Request(3)).inOrder()
        batch2.defaultReplyAll()

        Truth.assertThat(response2.get()).isEqualTo(Response(2))
        Truth.assertThat(response3.get()).isEqualTo(Response(3))

        Truth.assertThat(events.take()).isEqualTo(DoneCallbackCalled.INSTANCE)

        val batch3 = events.take() as BatchOperation
        Truth.assertThat(batch3.requests).containsExactly(Request(4), Request(5)).inOrder()
        batch3.defaultReplyAll()

        Truth.assertThat(response4.get()).isEqualTo(Response(4))
        Truth.assertThat(response5.get()).isEqualTo(Response(5))

        Truth.assertThat(events.take()).isEqualTo(DoneCallbackCalled.INSTANCE)
    }

    @Test
    @Throws(Exception::class)
    fun synchronousException_failsFuturesAndAllowsSubsequentSubmissions() {
        val failure = RuntimeException("Sync Failure")
        val faultyMultiplexer: Multiplexer<Request?, Response?>? =
            object : Multiplexer<Request?, Response?>() {
                public override fun execute(requests: MutableList<Request?>?): ListenableFuture<MutableList<Response?>?>? {
                    throw failure
                }
            }

        val delegatingMultiplexer = DelegatingMultiplexer(faultyMultiplexer)

        val batcher: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RequestBatcher.< Request, Response>create<com.google.devtools.build.lib.concurrent.RequestBatcherTest.Request?, com.google.devtools.build.lib.concurrent.RequestBatcherTest.Response?>(
        delegatingMultiplexer,
        ForkJoinPool.commonPool(),  /* maxBatchSize= */
        255,  /* maxConcurrentRequests= */
        1)

        val response: ListenableFuture<Response?> = batcher.submit(Request(1))

        val thrown: ExecutionException? = Assert.assertThrows<ExecutionException?>(
            ExecutionException::class.java,
            ThrowingRunnable { response.get() })
        Truth.assertThat(thrown).hasCauseThat().isEqualTo(failure)

        // Now switch to a good multiplexer and verify we can still submit
        val goodMultiplexer = SettableMultiplexer()
        delegatingMultiplexer.delegate = goodMultiplexer

        val goodResponse: ListenableFuture<Response?> = batcher.submit(Request(2))
        goodMultiplexer.queue.take().setSimpleResponses()
        Truth.assertThat(goodResponse.get()).isEqualTo(Response(2))
    }

    private class FakeConcurrentFifo(sizeAddress: Long, appendIndexAddress: Long, takeIndexAddress: Long) :
        ConcurrentFifo<Operation<Request?, Response?>?>(
            Operation::class.java,
            sizeAddress,
            appendIndexAddress,
            takeIndexAddress
        ) {
        private val queue: ConcurrentLinkedQueue<Operation<Request?, Response?>?> =
            ConcurrentLinkedQueue<Operation<Request?, Response?>?>()

        private val tryAppendTokens: Semaphore = Semaphore(0)
        private val appendPermits: Semaphore = Semaphore(0)

        public override fun tryAppend(task: Operation<Request?, Response?>?): Boolean {
            tryAppendTokens.release()
            appendPermits.acquireUninterruptibly()
            queue.add(task)
            return true
        }

        public override fun take(): Operation<Request?, Response?>? {
            return queue.poll()
        }
    }

    private class SettableMultiplexer : Multiplexer<Request?, Response?> {
        private val queue: LinkedBlockingQueue<BatchedOperations> = LinkedBlockingQueue<BatchedOperations>()

        public override fun execute(requests: MutableList<Request?>?): ListenableFuture<MutableList<Response?>?> {
            val responses = SettableFuture.create<MutableList<Response?>?>()
            queue.add(BatchedOperations(requests, responses))
            return responses
        }
    }

    @kotlin.jvm.JvmRecord
    private data class BatchedOperations(
        val requests: MutableList<Request?>?,
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

    private class FutureSettableMultiplexer : FutureMultiplexer<Request?, Response?> {
        private val queue: LinkedBlockingQueue<BatchedPerResponseRequests> =
            LinkedBlockingQueue<BatchedPerResponseRequests>()

        public override fun execute(
            requests: MutableList<Request>?, sinks: ImmutableList<out FutureSink<Response?>?>
        ) {
            Truth.assertThat(requests).hasSize(sinks.size)

            val settableFutures: MutableList<SettableFuture<Response?>?> = ArrayList<SettableFuture<Response?>?>()
            for (i in sinks.indices) {
                val settableFuture = SettableFuture.create<Response?>()
                settableFutures.add(settableFuture)
                // This links the batcher's internal future to our controllable future.
                sinks.get(i).acceptFuture(settableFuture)
            }
            queue.add(BatchedPerResponseRequests(requests, settableFutures))
        }
    }

    @kotlin.jvm.JvmRecord
    private data class BatchedPerResponseRequests(
        val requests: MutableList<Request>?,
        val settableFutures: MutableList<SettableFuture<Response?>?>?
    ) {
        fun setSimpleSuccessResponses() {
            Truth.assertThat(requests).hasSize(settableFutures!!.size)
            for (i in requests.indices) {
                settableFutures.get(i)!!.set(Response(requests!!.get(i).x))
            }
        }
    }

    private class DelegatingMultiplexer(delegate: Multiplexer<Request?, Response?>) : Multiplexer<Request?, Response?> {
        @kotlin.concurrent.Volatile
        private var delegate: Multiplexer<Request?, Response?>

        init {
            this.delegate = delegate
        }

        public override fun execute(requests: MutableList<Request?>?): ListenableFuture<MutableList<Response?>?> {
            return delegate.execute(requests)
        }
    }

    companion object {
        private val cleaner: Cleaner = Cleaner.create()

        private fun createCallbackMultiplexerBatcher(
            events: LinkedBlockingQueue<BatcherEvent?>
        ): RequestBatcher<Request?, Response?> {
            val multiplexer: CallbackMultiplexer<Request?, Response?>? =
                object : CallbackMultiplexer<Request?, Response?>() {
                    public override fun execute(
                        requests: MutableList<Request>?,
                        sinks: ImmutableList<out ResponseSink<Request?, Response?>>
                    ): Runnable {
                        Truth.assertThat(requests).hasSize(sinks.size)
                        events.offer(BatchOperation(requests, sinks))
                        return Runnable { events.offer(DoneCallbackCalled.INSTANCE) }
                    }
                }

            return RequestBatcher.< Request, Response>createWithCallbackMultiplexer<com.google.devtools.build.lib.concurrent.RequestBatcherTest.Request?, com.google.devtools.build.lib.concurrent.RequestBatcherTest.Response?>(
            multiplexer,  /* maxBatchSize= */1,  /* maxConcurrentRequests= */1)
        }

        private fun respondTo(requests: MutableList<Request?>): MutableList<Response?> {
            return Lists.transform<Request?, Response?>(
                requests,
                Function { request: Request? -> Response(request!!.x) })
        }

        private val UNSAFE: Unsafe = UnsafeProvider.unsafe()
    }
}
