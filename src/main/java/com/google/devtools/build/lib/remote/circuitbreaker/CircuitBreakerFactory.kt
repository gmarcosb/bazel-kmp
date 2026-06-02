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
import com.google.devtools.build.lib.remote.options.RemoteOptions
import com.google.devtools.build.lib.remote.options.RemoteOptions.CircuitBreakerStrategy
import java.util.concurrent.Executors

/** Factory for [Retrier.CircuitBreaker]  */
object CircuitBreakerFactory {
    const val DEFAULT_MIN_CALL_COUNT_TO_COMPUTE_FAILURE_RATE: Int = 100
    const val DEFAULT_MIN_FAIL_COUNT_TO_COMPUTE_FAILURE_RATE: Int = 12

    /**
     * Creates the instance of the [Retrier.CircuitBreaker] as per the strategy defined in
     * [RemoteOptions]. In case of undefined strategy defaults to [ ] implementation.
     * 
     * @param remoteOptions The configuration for the CircuitBreaker implementation.
     * @return an instance of CircuitBreaker.
     */
    fun createCircuitBreaker(remoteOptions: RemoteOptions): Retrier.CircuitBreaker {
        if (remoteOptions.getCircuitBreakerStrategy() == CircuitBreakerStrategy.FAILURE) {
            val slidingWindowMillis = remoteOptions.getRemoteFailureWindowInterval().toMillis().toInt()
            return FailureCircuitBreaker(
                remoteOptions.getRemoteFailureRateThreshold(),
                slidingWindowMillis,
                if (slidingWindowMillis > 0) Executors.newSingleThreadScheduledExecutor() else null
            )
        }
        return Retrier.Companion.ALLOW_ALL_CALLS
    }
}
