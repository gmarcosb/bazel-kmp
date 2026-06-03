// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.remote.Retrier.Backoff

/** Tests for [Retrier].  */
@RunWith(JUnit4::class)
class RetrierTest {
    @org.mockito.Mock
    private val alwaysOpen: CircuitBreaker? = null

    private var retryService: com.google.common.util.concurrent.ListeningScheduledExecutorService? = null

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        Mockito.`when`<T?>(alwaysOpen.state()).thenReturn(State.ACCEPT_CALLS)

        retryService =
            com.google.common.util.concurrent.MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1))
    }

    @org.junit.After
    @Throws(java.lang.InterruptedException::class)
    fun afterEverything() {
        retryService.shutdownNow()
        retryService.awaitTermination(
            com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS,
            TimeUnit.SECONDS
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun retryShouldWork_failure() {
        // Test that a call is retried according to the backoff.
        // All calls fail.

        val s: java.util.function.Supplier<Backoff?> = java.util.function.Supplier { ZeroBackoff( /* maxRetries= */2) }
        val r: Retrier = Retrier(s, RETRY_ALL, retryService, alwaysOpen)
        val numCalls: AtomicInteger = AtomicInteger()
        val e: java.lang.Exception? =
            org.junit.Assert.assertThrows<java.lang.Exception?>(
                java.lang.Exception::class.java,
                org.junit.function.ThrowingRunnable {
                    r.execute(
                        {
                            numCalls.incrementAndGet()
                            throw java.lang.Exception("call failed")
                        })
                })
        Truth.assertThat(e).hasMessageThat().isEqualTo("call failed")

        Truth.assertThat(numCalls.get()).isEqualTo(3)
        Mockito.verify<Any?>(alwaysOpen, Mockito.times(3)).recordFailure()
        Mockito.verify<Any?>(alwaysOpen, Mockito.never()).recordSuccess()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun retryShouldWorkNoRetries_failure() {
        // Test that a non-retriable error is not retried.
        // All calls fail.

        val s: java.util.function.Supplier<Backoff?> = java.util.function.Supplier { ZeroBackoff( /* maxRetries= */2) }
        val r: Retrier = Retrier(s, RETRY_NONE, retryService, alwaysOpen)
        val numCalls: AtomicInteger = AtomicInteger()
        val e: java.lang.Exception? =
            org.junit.Assert.assertThrows<java.lang.Exception?>(
                java.lang.Exception::class.java,
                org.junit.function.ThrowingRunnable {
                    r.execute(
                        {
                            numCalls.incrementAndGet()
                            throw java.lang.Exception("call failed")
                        })
                })
        Truth.assertThat(e).hasMessageThat().isEqualTo("call failed")

        Truth.assertThat(numCalls.get()).isEqualTo(1)
        Mockito.verify<Any?>(alwaysOpen, Mockito.never()).recordFailure()
        Mockito.verify<Any?>(alwaysOpen, Mockito.times(1)).recordSuccess()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun retryShouldWork_success() {
        // Test that a call is retried according to the backoff.
        // The last call succeeds.

        val s: java.util.function.Supplier<Backoff?> = java.util.function.Supplier { ZeroBackoff( /* maxRetries= */2) }
        val r: Retrier = Retrier(s, RETRY_ALL, retryService, alwaysOpen)
        val numCalls: AtomicInteger = AtomicInteger()
        val `val`: Int =
            r.execute(
                {
                    numCalls.incrementAndGet()
                    if (numCalls.get() == 3) {
                        return@execute 1
                    }
                    throw java.lang.Exception("call failed")
                })
        Truth.assertThat(`val`).isEqualTo(1)

        Mockito.verify<Any?>(alwaysOpen, Mockito.times(2)).recordFailure()
        Mockito.verify<Any?>(alwaysOpen, Mockito.times(1)).recordSuccess()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nestedRetriesShouldWork() {
        // Test that nested calls using retries compose as expected.

        val s: java.util.function.Supplier<Backoff?> = java.util.function.Supplier { ZeroBackoff( /* maxRetries= */1) }
        val r: Retrier = Retrier(s, RETRY_ALL, retryService, alwaysOpen)

        val attemptsLvl0: AtomicInteger = AtomicInteger()
        val attemptsLvl1: AtomicInteger = AtomicInteger()
        val attemptsLvl2: AtomicInteger = AtomicInteger()
        try {
            val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                r.execute(
                    {
                        attemptsLvl0.incrementAndGet()
                        r.execute(
                            {
                                attemptsLvl1.incrementAndGet()
                                r.execute(
                                    {
                                        attemptsLvl2.incrementAndGet()
                                        throw java.lang.Exception("failure message")
                                    })
                            })
                    })
        } catch (e: java.lang.Exception) {
            Truth.assertThat(e).hasMessageThat().isEqualTo("failure message")
            Truth.assertThat(attemptsLvl0.get()).isEqualTo(2)
            Truth.assertThat(attemptsLvl1.get()).isEqualTo(4)
            Truth.assertThat(attemptsLvl2.get()).isEqualTo(8)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun circuitBreakerShouldTrip() {
        // Test that a circuit breaker can trip.

        val s: java.util.function.Supplier<Backoff?> = java.util.function.Supplier { ZeroBackoff( /* maxRetries= */3) }
        val cb = TripAfterNCircuitBreaker( /* maxConsecutiveFailures= */2)
        val r: Retrier = Retrier(s, RETRY_ALL, retryService, cb)

        org.junit.Assert.assertThrows<T?>(
            CircuitBreakerException::class.java,
            org.junit.function.ThrowingRunnable {
                r.execute(
                    {
                        throw java.lang.Exception("call failed")
                    })
            })

        assertThat(cb.state()).isEqualTo(State.REJECT_CALLS)
        Truth.assertThat(cb.consecutiveFailures).isEqualTo(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun circuitBreakerCanRecover() {
        // Test that a circuit breaker can recover from REJECT_CALLS to ACCEPT_CALLS by
        // utilizing the TRIAL_CALL state.

        val s: java.util.function.Supplier<Backoff?> = java.util.function.Supplier { ZeroBackoff( /* maxRetries= */3) }
        val cb = TripAfterNCircuitBreaker( /* maxConsecutiveFailures= */2)
        val r: Retrier = Retrier(s, RETRY_ALL, retryService, cb)

        cb.trialCall()

        assertThat(cb.state()).isEqualTo(State.TRIAL_CALL)

        val `val`: Int = r.execute({ 10 })
        Truth.assertThat(`val`).isEqualTo(10)
        assertThat(cb.state()).isEqualTo(State.ACCEPT_CALLS)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun circuitBreakerHalfOpenIsNotRetried() {
        // Test that a call executed in TRIAL_CALL state is not retried
        // in case of failure.

        val s: java.util.function.Supplier<Backoff?> = java.util.function.Supplier { ZeroBackoff( /* maxRetries= */3) }
        val cb = TripAfterNCircuitBreaker( /* maxConsecutiveFailures= */2)
        val r: Retrier = Retrier(s, RETRY_ALL, retryService, cb)

        cb.trialCall()

        try {
            val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                r.execute(
                    {
                        throw java.lang.Exception("call failed")
                    })
        } catch (expected: java.lang.Exception) {
            // Intentionally left empty.
        }

        Truth.assertThat(cb.consecutiveFailures).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun interruptsShouldNotBeRetried_flag() {
        // Test that a call is not executed / retried if the current thread
        // is interrupted.

        val s: java.util.function.Supplier<Backoff?> = java.util.function.Supplier { ZeroBackoff( /* maxRetries= */3) }
        val cb = TripAfterNCircuitBreaker( /* maxConsecutiveFailures= */2)
        val r: Retrier = Retrier(s, RETRY_ALL, retryService, cb)

        val numCalls: AtomicInteger = AtomicInteger()
        java.lang.Thread.currentThread().interrupt()
        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable {
                r.execute(
                    {
                        numCalls.incrementAndGet()
                        10
                    })
            })
        Truth.assertThat(numCalls.get()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun interruptsShouldNotBeRetried_exception() {
        // Test that a call is not retried if an InterruptedException is thrown.

        val s: java.util.function.Supplier<Backoff?> = java.util.function.Supplier { ZeroBackoff( /* maxRetries= */3) }
        val cb = TripAfterNCircuitBreaker( /* maxConsecutiveFailures= */2)
        val r: Retrier = Retrier(s, RETRY_ALL, retryService, cb)

        val numCalls: AtomicInteger = AtomicInteger()
        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable {
                r.execute(
                    {
                        numCalls.incrementAndGet()
                        throw java.lang.InterruptedException()
                    })
            })
        Truth.assertThat(numCalls.get()).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun asyncRetryExhaustRetries() {
        // Test that a call is retried according to the backoff.
        // All calls fail.

        val s: java.util.function.Supplier<Backoff?> = java.util.function.Supplier { ZeroBackoff( /* maxRetries= */2) }
        val r: Retrier = Retrier(s, RETRY_ALL, retryService, alwaysOpen)
        val numCalls: AtomicInteger = AtomicInteger()
        val res: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
            r.executeAsync(
                {
                    numCalls.incrementAndGet()
                    throw java.lang.Exception("call failed")
                })
        val e: ExecutionException? = org.junit.Assert.assertThrows<ExecutionException?>(
            ExecutionException::class.java,
            org.junit.function.ThrowingRunnable { res.get() })
        Truth.assertThat(numCalls.get()).isEqualTo(3)
        Truth.assertThat(e).hasCauseThat().hasMessageThat().isEqualTo("call failed")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun asyncRetryNonRetriable() {
        // Test that a call is retried according to the backoff.
        // All calls fail.

        val s: java.util.function.Supplier<Backoff?> = java.util.function.Supplier { ZeroBackoff( /* maxRetries= */2) }
        val r: Retrier = Retrier(s, RETRY_NONE, retryService, alwaysOpen)
        val numCalls: AtomicInteger = AtomicInteger()
        val res: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
            r.executeAsync(
                {
                    numCalls.incrementAndGet()
                    throw java.lang.Exception("call failed")
                })
        val e: ExecutionException? = org.junit.Assert.assertThrows<ExecutionException?>(
            ExecutionException::class.java,
            org.junit.function.ThrowingRunnable { res.get() })
        Truth.assertThat(e).hasCauseThat().hasMessageThat().isEqualTo("call failed")
        Truth.assertThat(numCalls.get()).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun asyncRetryEmptyError() {
        // Test that a call is retried according to the backoff.
        // All calls fail.

        val s: java.util.function.Supplier<Backoff?> = java.util.function.Supplier { ZeroBackoff( /* maxRetries= */2) }
        val r: Retrier = Retrier(s, RETRY_NONE, retryService, alwaysOpen)
        val res: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
            r.executeAsync(
                {
                    throw java.lang.Exception("")
                })
        val e: ExecutionException? = org.junit.Assert.assertThrows<ExecutionException?>(
            ExecutionException::class.java,
            org.junit.function.ThrowingRunnable { res.get() })
        Truth.assertThat(e).hasCauseThat().hasMessageThat().isEqualTo("")
    }

    @org.junit.Test
    fun testCircuitBreakerRetriableFailures() {
        val maxRetries = 2
        val s: java.util.function.Supplier<Backoff?> = java.util.function.Supplier { ZeroBackoff(maxRetries) }
        val retriableStatuses: MutableList<io.grpc.Status> =
            java.util.Arrays.asList<io.grpc.Status?>(
                io.grpc.Status.ABORTED,
                io.grpc.Status.UNKNOWN,
                io.grpc.Status.DEADLINE_EXCEEDED
            )
        val successfulStatuses: MutableList<io.grpc.Status> =
            java.util.Arrays.asList<io.grpc.Status?>(io.grpc.Status.NOT_FOUND, io.grpc.Status.ALREADY_EXISTS)
        val cb =
            TripAfterNCircuitBreaker(retriableStatuses.size() * (maxRetries + 1))
        val r: Retrier = Retrier(s, RemoteRetrier.EXPERIMENTAL_GRPC_RESULT_CLASSIFIER, retryService, cb)

        var expectedConsecutiveFailures = 0

        for (status in retriableStatuses) {
            assertThat(cb.state).isEqualTo(State.ACCEPT_CALLS)
            val res: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
                r.executeAsync(
                    {
                        throw StatusRuntimeException(status)
                    })
            expectedConsecutiveFailures += maxRetries + 1
            org.junit.Assert.assertThrows<ExecutionException?>(
                ExecutionException::class.java,
                org.junit.function.ThrowingRunnable { res.get() })
            Truth.assertThat(cb.consecutiveFailures).isEqualTo(expectedConsecutiveFailures)
        }

        assertThat(cb.state).isEqualTo(State.REJECT_CALLS)
        cb.trialCall()

        for (status in successfulStatuses) {
            val res: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
                r.executeAsync(
                    {
                        throw StatusRuntimeException(status)
                    })
            Truth.assertThat(cb.consecutiveFailures).isEqualTo(0)
            org.junit.Assert.assertThrows<ExecutionException?>(
                ExecutionException::class.java,
                org.junit.function.ThrowingRunnable { res.get() })
            assertThat(cb.state).isEqualTo(State.ACCEPT_CALLS)
        }
    }

    @org.junit.Test
    fun testCircuitBreakerNonRetriableFailures() {
        val s: java.util.function.Supplier<Backoff?> = java.util.function.Supplier { ZeroBackoff( /* maxRetries= */2) }
        val nonRetriableStatuses: MutableList<io.grpc.Status> =
            java.util.Arrays.asList<io.grpc.Status?>(
                io.grpc.Status.PERMISSION_DENIED,
                io.grpc.Status.UNIMPLEMENTED,
                io.grpc.Status.DATA_LOSS,
                io.grpc.Status.OUT_OF_RANGE
            )
        val successfulStatues: MutableList<io.grpc.Status> =
            java.util.Arrays.asList<io.grpc.Status?>(io.grpc.Status.NOT_FOUND, io.grpc.Status.ALREADY_EXISTS)
        val cb = TripAfterNCircuitBreaker(nonRetriableStatuses.size())
        val r: Retrier = Retrier(s, RemoteRetrier.EXPERIMENTAL_GRPC_RESULT_CLASSIFIER, retryService, cb)

        var expectedConsecutiveFailures = 0

        for (status in nonRetriableStatuses) {
            val res: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
                r.executeAsync(
                    {
                        throw StatusRuntimeException(status)
                    })
            expectedConsecutiveFailures += 1
            org.junit.Assert.assertThrows<ExecutionException?>(
                ExecutionException::class.java,
                org.junit.function.ThrowingRunnable { res.get() })
            Truth.assertThat(cb.consecutiveFailures).isEqualTo(expectedConsecutiveFailures)
        }

        assertThat(cb.state).isEqualTo(State.REJECT_CALLS)
        cb.trialCall()

        for (status in successfulStatues) {
            val res: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
                r.executeAsync(
                    {
                        throw StatusRuntimeException(status)
                    })
            Truth.assertThat(cb.consecutiveFailures).isEqualTo(0)
            org.junit.Assert.assertThrows<ExecutionException?>(
                ExecutionException::class.java,
                org.junit.function.ThrowingRunnable { res.get() })
            assertThat(cb.state).isEqualTo(State.ACCEPT_CALLS)
        }
    }

    /** Simple circuit breaker that trips after N consecutive failures.  */
    @javax.annotation.concurrent.ThreadSafe
    private class TripAfterNCircuitBreaker(private val maxConsecutiveFailures: Int) : CircuitBreaker {
        private var state: State? = State.ACCEPT_CALLS
        private var consecutiveFailures = 0

        @kotlin.jvm.Synchronized
        public override fun state(): State? {
            return state
        }

        @kotlin.jvm.Synchronized
        public override fun recordFailure() {
            consecutiveFailures++
            if (consecutiveFailures >= maxConsecutiveFailures) {
                state = State.REJECT_CALLS
            }
        }

        @kotlin.jvm.Synchronized
        public override fun recordSuccess() {
            consecutiveFailures = 0
            state = State.ACCEPT_CALLS
        }

        fun trialCall() {
            state = State.TRIAL_CALL
        }
    }

    companion object {
        private val RETRY_ALL: ResultClassifier = ResultClassifier { e -> Result.TRANSIENT_FAILURE }
        private val RETRY_NONE: ResultClassifier = ResultClassifier { e -> Result.SUCCESS }
    }
}
