// Copyright 2018 The Bazel Authors. All rights reserved.
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
import com.google.devtools.build.lib.testutil.TestThread
import com.google.devtools.build.lib.testutil.TestThread.TestRunnable
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.CountDownLatch

/** Tests for [TimeSeriesImpl].  */
@RunWith(JUnit4::class)
class TimeSeriesTest {
    @org.junit.Test
    fun testAddRange() {
        val timeSeries: com.google.devtools.build.lib.profiler.TimeSeries =
            TimeSeriesImpl(java.time.Duration.ofMillis(42), java.time.Duration.ofMillis(100))
        timeSeries.addRange(java.time.Duration.ofMillis(42), java.time.Duration.ofMillis(142))
        timeSeries.addRange(java.time.Duration.ofMillis(442), java.time.Duration.ofMillis(542))
        val values: DoubleArray? = timeSeries.toDoubleArray(5)
        Truth.assertThat(values).usingTolerance(1.0e-10).containsExactly(1, 0, 0, 0, 1).inOrder()
    }

    @org.junit.Test
    fun testAddRangeWithValue() {
        val timeSeries: com.google.devtools.build.lib.profiler.TimeSeries =
            TimeSeriesImpl(java.time.Duration.ofMillis(42), java.time.Duration.ofMillis(100))
        timeSeries.addRange(java.time.Duration.ofMillis(42), java.time.Duration.ofMillis(242), 3.0)
        timeSeries.addRange(java.time.Duration.ofMillis(442), java.time.Duration.ofMillis(542), 0.5)
        val values: DoubleArray? = timeSeries.toDoubleArray(5)
        Truth.assertThat(values).usingTolerance(1.0e-10).containsExactly(3, 3, 0, 0, .5).inOrder()
    }

    @org.junit.Test
    fun testAddRangeOverlappingWithValue() {
        val timeSeries: com.google.devtools.build.lib.profiler.TimeSeries =
            TimeSeriesImpl(java.time.Duration.ofMillis(42), java.time.Duration.ofMillis(100))
        timeSeries.addRange(java.time.Duration.ofMillis(42), java.time.Duration.ofMillis(242), 3.0)
        timeSeries.addRange(java.time.Duration.ofMillis(142), java.time.Duration.ofMillis(442), 0.5)
        val values: DoubleArray? = timeSeries.toDoubleArray(5)
        Truth.assertThat(values).usingTolerance(1.0e-10).containsExactly(3, 3.5, 0.5, 0.5, 0).inOrder()
    }

    @org.junit.Test
    fun testAddRangeFractions() {
        val timeSeries: com.google.devtools.build.lib.profiler.TimeSeries =
            TimeSeriesImpl(java.time.Duration.ofMillis(42), java.time.Duration.ofMillis(100))
        timeSeries.addRange(java.time.Duration.ofMillis(92), java.time.Duration.ofMillis(267))
        val values: DoubleArray? = timeSeries.toDoubleArray(5)
        Truth.assertThat(values).usingTolerance(1.0e-10).containsExactly(0.5, 1, 0.25, 0, 0).inOrder()
    }

    @org.junit.Test
    fun testAddRangeWithValueFractions() {
        val timeSeries: com.google.devtools.build.lib.profiler.TimeSeries =
            TimeSeriesImpl(java.time.Duration.ofMillis(42), java.time.Duration.ofMillis(100))
        timeSeries.addRange(java.time.Duration.ofMillis(92), java.time.Duration.ofMillis(267), 3.0)
        val values: DoubleArray? = timeSeries.toDoubleArray(5)
        Truth.assertThat(values).usingTolerance(1.0e-10).containsExactly(1.5, 3, 0.75, 0, 0).inOrder()
    }

    @org.junit.Test
    fun testResize() {
        val timeSeries: com.google.devtools.build.lib.profiler.TimeSeries =
            TimeSeriesImpl(java.time.Duration.ZERO, java.time.Duration.ofMillis(100))
        timeSeries.addRange(java.time.Duration.ZERO, java.time.Duration.ofMillis((100 * 100 + 1).toLong()), 42.0)
        val values: DoubleArray? = timeSeries.toDoubleArray(101)
        val expected = DoubleArray(101)
        java.util.Arrays.fill(expected, 0, expected.size - 1, 42.0)
        expected[expected.size - 1] = 0.42
        Truth.assertThat(values).usingTolerance(1.0e-10).containsExactly(expected).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParallelism() {
        // Define two threads. One is writing 1 on odd places, and another writes 2 on even places.
        val timeSeries: com.google.devtools.build.lib.profiler.TimeSeries =
            TimeSeriesImpl(java.time.Duration.ZERO, java.time.Duration.ofMillis(100))
        val latch: CountDownLatch = CountDownLatch(2)
        val thread1: TestThread =
            TestThread(
                TestRunnable {
                    latch.countDown()
                    latch.await()
                    for (i in 0..49) {
                        timeSeries.addRange(
                            java.time.Duration.ofMillis((2 * i * 100).toLong()),
                            java.time.Duration.ofMillis(((2 * i + 1) * 100).toLong()),
                            1.0
                        )
                    }
                })
        val thread2: TestThread =
            TestThread(
                TestRunnable {
                    latch.countDown()
                    latch.await()
                    for (i in 0..49) {
                        timeSeries.addRange(
                            java.time.Duration.ofMillis(((2 * i + 1) * 100).toLong()),
                            java.time.Duration.ofMillis(((2 * i + 2) * 100).toLong()),
                            2.0
                        )
                    }
                })
        val expected = DoubleArray(100)
        for (i in 0..99) {
            if (i % 2 == 0) {
                expected[i] = 1.0
            } else {
                expected[i] = 2.0
            }
        }

        thread1.start()
        thread2.start()

        thread1.joinAndAssertState(10000)
        thread2.joinAndAssertState(10000)
        Truth.assertThat(timeSeries.toDoubleArray(100))
            .usingTolerance(1.0e-10)
            .containsExactly(expected)
            .inOrder()
    }
}
