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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.util.ConcurrencyMeter.Ticket

/** Tests for [ConcurrencyMeter].  */
@RunWith(JUnit4::class)
class ConcurrencyMeterTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGrant() {
        val scheduler: ConcurrencyMeter =
            ConcurrencyMeter("meter", 3, com.google.devtools.build.lib.clock.BlazeClock.instance())
        val isQueued: AtomicBoolean = AtomicBoolean(false)

        val req1: com.google.common.util.concurrent.ListenableFuture<Ticket?> =
            scheduler.request(2, 0, { isQueued.set(true) })
        assertFutureIsSuccessful(req1)
        assertThat(scheduler.queueSize()).isEqualTo(0)
        Truth.assertThat(isQueued.get()).isFalse()
        req1.get().done()

        val req2: com.google.common.util.concurrent.ListenableFuture<Ticket?> =
            scheduler.request(2, 0, { isQueued.set(true) })
        assertFutureIsSuccessful(req2)

        val req3: com.google.common.util.concurrent.ListenableFuture<Ticket?> =
            scheduler.request(1, 0, { isQueued.set(true) })
        assertFutureIsSuccessful(req3)
        Truth.assertThat(isQueued.get()).isFalse()
        assertThat(scheduler.queueSize()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBlock() {
        val scheduler: ConcurrencyMeter =
            ConcurrencyMeter("meter", 3, com.google.devtools.build.lib.clock.BlazeClock.instance())
        val isQueued: AtomicBoolean = AtomicBoolean(false)

        val req1: com.google.common.util.concurrent.ListenableFuture<Ticket?> =
            scheduler.request(2, 0, { isQueued.set(true) })
        assertFutureIsSuccessful(req1)

        val req2: com.google.common.util.concurrent.ListenableFuture<Ticket?> =
            scheduler.request(2, 0, { isQueued.set(true) })
        Truth.assertThat(req2.isDone()).isFalse()
        assertThat(scheduler.queueSize()).isEqualTo(1)
        Truth.assertThat(isQueued.get()).isTrue()

        req1.get().done()
        assertFutureIsSuccessful(req2)
        assertThat(scheduler.queueSize()).isEqualTo(0)
    }

    @org.junit.Test
    fun testGrantZero() {
        val scheduler: ConcurrencyMeter =
            ConcurrencyMeter("meter", 3, com.google.devtools.build.lib.clock.BlazeClock.instance())
        val req: com.google.common.util.concurrent.ListenableFuture<Ticket?> = scheduler.request(0, 0)
        assertFutureIsSuccessful(req)
    }

    @org.junit.Test
    fun testGrantFromZero() {
        val scheduler: ConcurrencyMeter =
            ConcurrencyMeter("meter", 3, com.google.devtools.build.lib.clock.BlazeClock.instance())

        val req1: com.google.common.util.concurrent.ListenableFuture<Ticket?> = scheduler.request(10, 0)
        assertFutureIsSuccessful(req1)

        val req2: com.google.common.util.concurrent.ListenableFuture<Ticket?> = scheduler.request(0, 0)
        Truth.assertThat(req2.isDone()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPriority() {
        val scheduler: ConcurrencyMeter =
            ConcurrencyMeter("meter", 3, com.google.devtools.build.lib.clock.BlazeClock.instance())

        val req1: com.google.common.util.concurrent.ListenableFuture<Ticket?> = scheduler.request(2, 0)
        assertFutureIsSuccessful(req1)

        val req2: com.google.common.util.concurrent.ListenableFuture<Ticket?> = scheduler.request(2, 0)
        Truth.assertThat(req2.isDone()).isFalse()

        val req3: com.google.common.util.concurrent.ListenableFuture<Ticket?> = scheduler.request(2, 1)
        Truth.assertThat(req3.isDone()).isFalse()

        req1.get().done()
        Truth.assertThat(req2.isDone()).isFalse()
        assertFutureIsSuccessful(req3)

        req3.get().done()
        assertFutureIsSuccessful(req2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testThreadSafety() {
        val requestsPerThread = 10
        val threads = 10
        val r: Random = Random()

        val scheduler: ConcurrencyMeter =
            ConcurrencyMeter("meter", 100, com.google.devtools.build.lib.clock.BlazeClock.instance())
        val exec: ExecutorService = Executors.newFixedThreadPool(threads)
        val unboundedPool: ExecutorService = Executors.newCachedThreadPool()
        val results: MutableList<java.util.concurrent.Future<*>> = java.util.ArrayList<java.util.concurrent.Future<*>>()
        val allJobsDone: CountDownLatch = CountDownLatch(threads * requestsPerThread)

        // For every thread, we'll ask for requestsPerThread resource bundles. For
        // each of those, we'll set up a listener to release the resources after
        // a small, but random amount of time.
        for (i in 0..<threads) {
            results.add(
                exec.submit(
                    java.lang.Runnable {
                        for (j in 0..<requestsPerThread) {
                            val size: Int = r.nextInt(20) + 3
                            val req: com.google.common.util.concurrent.ListenableFuture<Ticket?> =
                                scheduler.request(size, 0)
                            req.addListener(
                                java.lang.Runnable {
                                    val sleepiness: Long = r.nextInt(30).toLong()
                                    try {
                                        java.lang.Thread.sleep(sleepiness)
                                        req.get().done()
                                        allJobsDone.countDown()
                                    } catch (e: java.lang.Exception) {
                                        if (e is java.lang.InterruptedException) {
                                            java.lang.Thread.currentThread().interrupt()
                                        }
                                        throw java.lang.IllegalStateException(e)
                                    }
                                },
                                unboundedPool
                            )
                        }
                    })
            )
        }

        exec.shutdown()
        exec.awaitTermination(Long.Companion.MAX_VALUE, TimeUnit.DAYS)

        Truth.assertThat(results).hasSize(threads)
        for (result in results) {
            assertFutureIsSuccessful(result) // Make sure nothing went wrong.
        }
        allJobsDone.await()

        // Make sure nothing is left to be scheduled
        assertFutureIsSuccessful(scheduler.request(0, 0))
        assertThat(scheduler.queueSize()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cancelledRequest_releasedImmediately() {
        val meter: ConcurrencyMeter =
            ConcurrencyMeter("meter", 1, com.google.devtools.build.lib.clock.BlazeClock.instance())
        val ticket: Ticket = meter.request(1, 1).get()
        val blockedRequest: com.google.common.util.concurrent.ListenableFuture<Ticket?> = meter.request(1, 1)

        blockedRequest.cancel( /* mayInterruptIfRunning= */false)
        Truth.assertThat(blockedRequest.isCancelled()).isTrue()

        ticket.done()
        assertFutureIsSuccessful(meter.request(1, 1))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun manyBlockedAllCancelled_noStackOverflow() {
        val meter: ConcurrencyMeter =
            ConcurrencyMeter("meter", 1, com.google.devtools.build.lib.clock.BlazeClock.instance())
        val liveTicket: Ticket = meter.request(1, 1).get()

        val blockedRequests: MutableList<com.google.common.util.concurrent.ListenableFuture<Ticket?>> =
            java.util.ArrayList<com.google.common.util.concurrent.ListenableFuture<Ticket?>>()
        for (i in 0..100000 - 1) {
            blockedRequests.add(meter.request(1, 1))
        }
        for (blockedRequest in blockedRequests) {
            blockedRequest.cancel( /* mayInterruptIfRunning= */true)
            Truth.assertThat(blockedRequest.isCancelled()).isTrue()
        }

        liveTicket.done()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stats() {
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val meter: ConcurrencyMeter = ConcurrencyMeter("meter", 10, clock)

        val ticket1: Ticket = meter.request(1, 1).get()
        val ticket2: Ticket = meter.request(1, 1).get()
        clock.advance(java.time.Duration.ofMillis(1))

        val timeOfMax: Instant? = clock.now()
        meter.request(1, 1).get() // Unreleased ticket.
        ticket1.done()
        clock.advance(java.time.Duration.ofMillis(1))

        ticket2.done()

        assertThat(meter.getStats())
            .isEqualTo(Stats("meter", 10, 1, 3, timeOfMax.toEpochMilli()))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stats_maxObservedMultipleTimes_maxLeasedTimeMsMatchesLastTime() {
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val meter: ConcurrencyMeter = ConcurrencyMeter("meter", 1, clock)

        val ticket1: Ticket = meter.request(1, 1).get()
        ticket1.done()
        clock.advance(java.time.Duration.ofMillis(1))

        val ticket2: Ticket = meter.request(1, 1).get()
        ticket2.done()
        clock.advance(java.time.Duration.ofMillis(1))

        val timeOfLastMax: Instant? = clock.now()
        val ticket3: Ticket = meter.request(1, 1).get()
        ticket3.done()
        clock.advance(java.time.Duration.ofMillis(1))

        val stats: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? = meter.getStats()
        assertThat(stats.maxLeasedTimeMs).isEqualTo(timeOfLastMax.toEpochMilli())
    }

    @org.junit.Test
    fun stats_noPermitsLeased_noTimestamp() {
        val throwingClock: com.google.devtools.build.lib.clock.Clock =
            object : com.google.devtools.build.lib.clock.Clock() {
                override fun currentTimeMillis(): Long {
                    throw java.lang.UnsupportedOperationException("Should not need to get the current time")
                }

                override fun nanoTime(): Long {
                    throw java.lang.UnsupportedOperationException("Should not need to get the current time")
                }
            }
        val meter: ConcurrencyMeter = ConcurrencyMeter("meter", 1, throwingClock)

        val stats: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? = meter.getStats()
        assertThat(stats.maxLeased).isEqualTo(0)
        assertThat(stats.maxLeasedTimeMs).isEqualTo(0)
    }

    companion object {
        private fun assertFutureIsSuccessful(future: java.util.concurrent.Future<*>) {
            Truth.assertThat<java.util.concurrent.Future.State?>(future.state())
                .isEqualTo(java.util.concurrent.Future.State.SUCCESS)
        }
    }
}
