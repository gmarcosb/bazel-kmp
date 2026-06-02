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

import com.google.devtools.build.lib.remote.Retrier
import java.util.concurrent.Callable
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The [FailureCircuitBreaker] implementation of the [Retrier.CircuitBreaker] prevents
 * further calls to a remote cache once the failures rate within a given window exceeds a specified
 * threshold for a build. In the context of Bazel, a new instance of [Retrier.CircuitBreaker]
 * is created for each build. Therefore, if the circuit breaker trips during a build, the remote
 * cache will be disabled for that build. However, it will be enabled again for the next build as a
 * new instance of [Retrier.CircuitBreaker] will be created.
 */
class FailureCircuitBreaker(
    failureRateThreshold: Int,
    slidingWindowSize: Int,
    scheduledExecutor: ScheduledExecutorService
) : Retrier.CircuitBreaker {
    private var state: Retrier.CircuitBreaker.State?
    private val successes: AtomicInteger
    private val failures: AtomicInteger
    private val failureRateThreshold: Int
    private val slidingWindowSize: Int
    private val minCallCountToComputeFailureRate: Int
    private val minFailCountToComputeFailureRate: Int
    private val scheduledExecutor: ScheduledExecutorService

    /**
     * Creates a [FailureCircuitBreaker].
     * 
     * @param failureRateThreshold is used to set the min percentage of failure required to trip the
     * circuit breaker in given time window.
     * @param slidingWindowSize the size of the sliding window in milliseconds to calculate the number
     * of failures.
     * @param scheduledExecutor executor for scheduling tasks to decrement success and failure counts.
     */
    init {
        this.failures = AtomicInteger(0)
        this.successes = AtomicInteger(0)
        this.failureRateThreshold = failureRateThreshold
        this.slidingWindowSize = slidingWindowSize
        this.minCallCountToComputeFailureRate =
            CircuitBreakerFactory.DEFAULT_MIN_CALL_COUNT_TO_COMPUTE_FAILURE_RATE
        this.minFailCountToComputeFailureRate =
            CircuitBreakerFactory.DEFAULT_MIN_FAIL_COUNT_TO_COMPUTE_FAILURE_RATE
        this.state = Retrier.CircuitBreaker.State.ACCEPT_CALLS
        this.scheduledExecutor = scheduledExecutor
    }

    override fun state(): Retrier.CircuitBreaker.State? {
        return this.state
    }

    override fun recordFailure() {
        val failureCount = failures.incrementAndGet()
        val totalCallCount = successes.get() + failureCount
        if (slidingWindowSize > 0) {
            val unused =
                scheduledExecutor.schedule<Int?>(
                    Callable { failures.decrementAndGet() }, slidingWindowSize.toLong(), TimeUnit.MILLISECONDS
                )
        }

        if (totalCallCount < minCallCountToComputeFailureRate
            && failureCount < minFailCountToComputeFailureRate
        ) {
            // The remote call count is below the threshold required to calculate the failure rate.
            return
        }
        val failureRate = (failureCount * 100.0) / totalCallCount

        // Since the state can only be changed to the open state, synchronization is not required.
        if (failureRate > this.failureRateThreshold) {
            this.state = Retrier.CircuitBreaker.State.REJECT_CALLS
        }
    }

    override fun recordSuccess() {
        successes.incrementAndGet()
        if (slidingWindowSize > 0) {
            val unused =
                scheduledExecutor.schedule<Int?>(
                    Callable { successes.decrementAndGet() }, slidingWindowSize.toLong(), TimeUnit.MILLISECONDS
                )
        }
    }
}
