// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.circuitbreaker

import com.google.devtools.build.lib.remote.Retrier.CircuitBreaker.State
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.ScheduledFuture
import java.util.function.Consumer
import kotlin.collections.ArrayList
import kotlin.collections.MutableList

@RunWith(JUnit4::class)
class FailureCircuitBreakerTest {
    @Test // Suppress unchecked warnings because any(Callable.class) uses a raw type,
    // which causes Javac to fail with -Werror.
    @Throws(InterruptedException::class)
    fun testRecordFailure_circuitTrips() {
        val failureRateThreshold = 10
        val windowInterval = 100
        val mockScheduler: ScheduledExecutorService =
            Mockito.mock<ScheduledExecutorService>(ScheduledExecutorService::class.java)
        val capturedRunnables: MutableList<Runnable?> = Collections.synchronizedList<Runnable?>(ArrayList<Runnable?>())

        // Stub both schedule overloads to capture the scheduled tasks.
        // This allows us to simulate window expiration by running them manually.
        // We need to stub Callable because method references like failures::decrementAndGet
        // return a value and can be matched to Callable by the compiler.
        Mockito.`when`(
            mockScheduler.schedule(
                ArgumentMatchers.any<Runnable?>(Runnable::class.java),
                ArgumentMatchers.anyLong(),
                ArgumentMatchers.any<TimeUnit?>(TimeUnit::class.java)
            )
        )
            .thenAnswer(
                Answer { invocation: InvocationOnMock? ->
                    capturedRunnables.add(invocation.getArgument<Runnable?>(0))
                    null
                })
        Mockito.`when`<ScheduledFuture<*>?>(
            mockScheduler.schedule<Any?>(
                ArgumentMatchers.any<Callable<*>?>(Callable::class.java),
                ArgumentMatchers.anyLong(),
                ArgumentMatchers.any<TimeUnit?>(TimeUnit::class.java)
            )
        )
            .thenAnswer(
                Answer { invocation: InvocationOnMock? ->
                    val callable: Callable<*> = invocation.getArgument<Callable<*>?>(0)
                    capturedRunnables.add(
                        Runnable {
                            try {
                                callable.call()
                            } catch (e: Exception) {
                                throw RuntimeException(e)
                            }
                        })
                    null
                })
        val failureCircuitBreaker =
            FailureCircuitBreaker(failureRateThreshold, windowInterval, mockScheduler)

        val listOfSuccessAndFailureCalls: MutableList<Runnable?> = ArrayList<Runnable?>()
        for (index in 0..<failureRateThreshold) {
            listOfSuccessAndFailureCalls.add(Runnable { failureCircuitBreaker.recordFailure() })
        }

        for (index in 0..<failureRateThreshold * 9) {
            listOfSuccessAndFailureCalls.add(Runnable { failureCircuitBreaker.recordSuccess() })
        }

        Collections.shuffle(listOfSuccessAndFailureCalls)

        // make calls equals to threshold number of not ignored failure calls in parallel.
        listOfSuccessAndFailureCalls.stream().parallel().forEach { obj: Runnable? -> obj!!.run() }
        assertThat(failureCircuitBreaker.state()).isEqualTo(State.ACCEPT_CALLS)

        val expectedCalls = failureRateThreshold * 10
        // Run all captured runnables to simulate window expiration.
        Truth.assertThat(capturedRunnables).hasSize(expectedCalls)
        capturedRunnables.forEach(Consumer { obj: Runnable? -> obj!!.run() })
        capturedRunnables.clear() // Clear for the next round

        // make calls equals to threshold number of not ignored failure calls in parallel.
        listOfSuccessAndFailureCalls.stream().parallel().forEach { obj: Runnable? -> obj!!.run() }
        assertThat(failureCircuitBreaker.state()).isEqualTo(State.ACCEPT_CALLS)

        // We don't run the new scheduled tasks, simulating being within the window.
        failureCircuitBreaker.recordFailure()
        assertThat(failureCircuitBreaker.state()).isEqualTo(State.REJECT_CALLS)
    }

    @Test
    @Throws(InterruptedException::class)
    fun testRecordFailure_minCallCriteriaNotMet() {
        val failureRateThreshold = 0
        val windowInterval = 100
        val minCallToComputeFailure =
            CircuitBreakerFactory.DEFAULT_MIN_CALL_COUNT_TO_COMPUTE_FAILURE_RATE
        val mockScheduler: ScheduledExecutorService =
            Mockito.mock<ScheduledExecutorService>(ScheduledExecutorService::class.java)
        val failureCircuitBreaker =
            FailureCircuitBreaker(failureRateThreshold, windowInterval, mockScheduler)

        // make success calls, failure call and number of total calls less than
        // minCallToComputeFailure.
        for (index in 0..<minCallToComputeFailure - 2) {
            failureCircuitBreaker.recordSuccess()
        }
        failureCircuitBreaker.recordFailure()
        assertThat(failureCircuitBreaker.state()).isEqualTo(State.ACCEPT_CALLS)

        // We don't run the scheduled tasks, simulating being within the window.
        failureCircuitBreaker.recordFailure()
        assertThat(failureCircuitBreaker.state()).isEqualTo(State.REJECT_CALLS)
    }

    @Test
    @Throws(InterruptedException::class)
    fun testRecordFailure_minFailCriteriaNotMet() {
        val failureRateThreshold = 10
        val windowInterval = 100
        val minFailToComputeFailure =
            CircuitBreakerFactory.DEFAULT_MIN_FAIL_COUNT_TO_COMPUTE_FAILURE_RATE
        val mockScheduler: ScheduledExecutorService =
            Mockito.mock<ScheduledExecutorService>(ScheduledExecutorService::class.java)
        val failureCircuitBreaker =
            FailureCircuitBreaker(failureRateThreshold, windowInterval, mockScheduler)

        // make number of failure calls less than minFailToComputeFailure.
        for (index in 0..<minFailToComputeFailure - 1) {
            failureCircuitBreaker.recordFailure()
        }
        assertThat(failureCircuitBreaker.state()).isEqualTo(State.ACCEPT_CALLS)

        // We don't run the scheduled tasks, simulating being within the window.
        failureCircuitBreaker.recordFailure()
        assertThat(failureCircuitBreaker.state()).isEqualTo(State.REJECT_CALLS)
    }
}
