// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.testutil

import com.google.common.truth.Truth
import com.google.devtools.build.lib.clock.Clock.currentTimeMillis
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.build.lib.testutil.ManualSleeper
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.build
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.atomic.AtomicInteger

/** Tests for `ManualSleeper`.  */
@RunWith(JUnit4::class)
class ManualSleeperTest {
    private val clock: com.google.devtools.build.lib.testutil.ManualClock =
        com.google.devtools.build.lib.testutil.ManualClock()
    private val sleeper: ManualSleeper = ManualSleeper(clock)

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun sleepMillis_0_ok() {
        sleeper.sleepMillis(0)
        Truth.assertThat(clock.currentTimeMillis()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun sleepMillis_100_ok() {
        sleeper.sleepMillis(100)
        Truth.assertThat(clock.currentTimeMillis()).isEqualTo(100)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun sleepMillis_minus1_throws() {
        try {
            sleeper.sleepMillis(-1)
            org.junit.Assert.fail("Should have thrown")
        } catch (expected: java.lang.IllegalArgumentException) {
            Truth.assertThat(expected).hasMessageThat().isEqualTo("sleeper can't time travel")
        }
    }

    @org.junit.Test
    fun scheduleRunnable_0_doesNotRunItImmediately() {
        val counter: AtomicInteger = AtomicInteger()
        sleeper.scheduleRunnable(java.lang.Runnable { counter.incrementAndGet() }, 0)

        Truth.assertThat(counter.get()).isEqualTo(0)
    }

    @org.junit.Test
    fun scheduleRunnable_100_doesNotRunItImmediately() {
        val counter: AtomicInteger = AtomicInteger()
        sleeper.scheduleRunnable(java.lang.Runnable { counter.incrementAndGet() }, 100)

        Truth.assertThat(counter.get()).isEqualTo(0)
    }

    @org.junit.Test
    fun scheduleRunnable_minus1_throws() {
        val counter: AtomicInteger = AtomicInteger()

        try {
            sleeper.scheduleRunnable(java.lang.Runnable { counter.incrementAndGet() }, -1)
            org.junit.Assert.fail("Should have thrown")
        } catch (expected: java.lang.IllegalArgumentException) {
            Truth.assertThat(expected).hasMessageThat().isEqualTo("sleeper can't time travel")
            Truth.assertThat(counter.get()).isEqualTo(0)
        }
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun scheduleRunnable_0_runsAfterSleep0() {
        val counter: AtomicInteger = AtomicInteger()
        sleeper.scheduleRunnable(java.lang.Runnable { counter.incrementAndGet() }, 0)

        sleeper.sleepMillis(0)

        Truth.assertThat(counter.get()).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun scheduleRunnable_0_runsAfterSleep0_doesNotRunSecondTime() {
        val counter: AtomicInteger = AtomicInteger()
        sleeper.scheduleRunnable(java.lang.Runnable { counter.incrementAndGet() }, 0)

        sleeper.sleepMillis(0)
        sleeper.sleepMillis(0)

        Truth.assertThat(counter.get()).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun scheduleRunnable_100_runsAfterSleepExactly100() {
        val counter: AtomicInteger = AtomicInteger()
        sleeper.scheduleRunnable(java.lang.Runnable { counter.incrementAndGet() }, 100)

        sleeper.sleepMillis(50)
        Truth.assertThat(counter.get()).isEqualTo(0)

        sleeper.sleepMillis(50)
        Truth.assertThat(counter.get()).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun scheduleRunnable_100_runsAfterSleepOver100() {
        val counter: AtomicInteger = AtomicInteger()
        sleeper.scheduleRunnable(java.lang.Runnable { counter.incrementAndGet() }, 100)

        sleeper.sleepMillis(50)
        Truth.assertThat(counter.get()).isEqualTo(0)

        sleeper.sleepMillis(150)
        Truth.assertThat(counter.get()).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun scheduleRunnable_100_doesNotRunAgain() {
        val counter: AtomicInteger = AtomicInteger()
        sleeper.scheduleRunnable(java.lang.Runnable { counter.incrementAndGet() }, 100)

        sleeper.sleepMillis(150)
        Truth.assertThat(counter.get()).isEqualTo(1)

        sleeper.sleepMillis(100)
        Truth.assertThat(counter.get()).isEqualTo(1)
    }
}
