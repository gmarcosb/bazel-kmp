// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.profiler

import com.google.common.truth.Truth
import com.google.devtools.build.lib.clock.BlazeClock.setClock
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.build.lib.profiler.AutoProfiler
import com.google.devtools.build.lib.profiler.AutoProfiler.ElapsedTimeReceiver
import com.google.devtools.build.lib.remote.grpc.ConnectionFactory.create
import com.google.devtools.build.lib.remote.grpc.DynamicConnectionPool.create
import com.google.devtools.build.lib.remote.grpc.SharedConnectionFactory.create
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.atomic.AtomicLong

/** Tests for [AutoProfiler].  */
@RunWith(JUnit4::class)
class AutoProfilerTest {
    private var clock: com.google.devtools.build.lib.testutil.ManualClock? = null

    @Before
    fun init() {
        clock = com.google.devtools.build.lib.testutil.ManualClock()
        AutoProfiler.setClock(clock)
    }

    @org.junit.Test
    fun simple() {
        val elapsedTime: AtomicLong = AtomicLong()
        val receiver: ElapsedTimeReceiver = object : ElapsedTimeReceiver {
            override fun accept(elapsedTimeNanos: Long) {
                elapsedTime.set(elapsedTimeNanos)
            }
        }
        AutoProfiler.create(receiver).use { profiler ->
            clock.advanceMillis(42)
        }
        Truth.assertThat(elapsedTime.get()).isEqualTo(42 * 1000 * 1000)
    }
}
