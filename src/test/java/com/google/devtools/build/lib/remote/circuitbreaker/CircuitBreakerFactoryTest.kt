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
import com.google.devtools.common.options.Options
import org.junit.Test

/** Tests for [CircuitBreakerFactory].  */
@RunWith(JUnit4::class)
class CircuitBreakerFactoryTest {
    @Test
    fun testCreateCircuitBreaker_failureStrategy() {
        val remoteOptions: RemoteOptions = Options.getDefaults<O>(RemoteOptions::class.java)
        remoteOptions.circuitBreakerStrategy = CircuitBreakerStrategy.FAILURE

        assertThat(CircuitBreakerFactory.createCircuitBreaker(remoteOptions))
            .isInstanceOf(FailureCircuitBreaker::class.java)
    }

    @Test
    fun testCreateCircuitBreaker_nullStrategy() {
        val remoteOptions: RemoteOptions = Options.getDefaults<O>(RemoteOptions::class.java)
        assertThat(CircuitBreakerFactory.createCircuitBreaker(remoteOptions))
            .isEqualTo(Retrier.ALLOW_ALL_CALLS)
    }
}
