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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.runtime.MemoryPressure.MemoryPressureStats

@RunWith(JUnit4::class)
class HighWaterMarkLimiterTest {
    @org.junit.Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    @org.mockito.Mock
    private val skyframeExecutor: SkyframeExecutor? = null

    @org.mockito.Mock
    private val syscallCache: SyscallCache? = null

    @org.junit.Test
    fun testHandle_belowThreshold() {
        val underTest: HighWaterMarkLimiter =
            HighWaterMarkLimiter(
                skyframeExecutor,
                syscallCache,
                createOptions( /* threshold= */
                    90,  /* minorGcDropLimit= */
                    Int.Companion.MAX_VALUE,  /* fullGcDropLimit= */
                    Int.Companion.MAX_VALUE
                )
            )

        val belowThreshold: MemoryPressureEvent? =
            MemoryPressureEvent.newBuilder()
                .setWasManualGc(false)
                .setWasFullGc(false)
                .setTenuredSpaceMaxBytes(100L)
                .setTenuredSpaceUsedBytes(89L)
                .setDuration(java.time.Duration.ofMillis(42L))
                .build()
        underTest.handle(belowThreshold)

        Mockito.verify<Any?>(skyframeExecutor, Mockito.never()).dropUnnecessaryTemporarySkyframeState()
        Mockito.verify<Any?>(syscallCache, Mockito.never()).clear()
        assertStats(underTest, MemoryPressureStats.newBuilder().setMinorGcDrops(0).setFullGcDrops(0))
    }

    @org.junit.Test
    fun testHandle_minorLimitFullUnlimited() {
        val underTest: HighWaterMarkLimiter =
            HighWaterMarkLimiter(
                skyframeExecutor,
                syscallCache,
                createOptions( /* threshold= */
                    90,  /* minorGcDropLimit= */
                    1,  /* fullGcDropLimit= */
                    Int.Companion.MAX_VALUE
                )
            )

        Mockito.verify<Any?>(skyframeExecutor, Mockito.never()).dropUnnecessaryTemporarySkyframeState()
        Mockito.verify<Any?>(syscallCache, Mockito.never()).clear()

        underTest.handle(MINOR)

        Mockito.verify<Any?>(skyframeExecutor, Mockito.times(1)).dropUnnecessaryTemporarySkyframeState()
        Mockito.verify<Any?>(syscallCache, Mockito.times(1)).clear()

        underTest.handle(MINOR)

        Mockito.verify<Any?>(skyframeExecutor, Mockito.times(1)).dropUnnecessaryTemporarySkyframeState()
        Mockito.verify<Any?>(syscallCache, Mockito.times(1)).clear()

        underTest.handle(FULL)

        Mockito.verify<Any?>(skyframeExecutor, Mockito.times(2)).dropUnnecessaryTemporarySkyframeState()
        Mockito.verify<Any?>(syscallCache, Mockito.times(2)).clear()

        underTest.handle(FULL)

        Mockito.verify<Any?>(skyframeExecutor, Mockito.times(3)).dropUnnecessaryTemporarySkyframeState()
        Mockito.verify<Any?>(syscallCache, Mockito.times(3)).clear()

        assertStats(underTest, MemoryPressureStats.newBuilder().setMinorGcDrops(1).setFullGcDrops(2))
    }

    @org.junit.Test
    fun testHandle_minorUnlimitedFullLimit() {
        val underTest: HighWaterMarkLimiter =
            HighWaterMarkLimiter(
                skyframeExecutor,
                syscallCache,
                createOptions( /* threshold= */
                    90,  /* minorGcDropLimit= */
                    Int.Companion.MAX_VALUE,  /* fullGcDropLimit= */
                    1
                )
            )

        Mockito.verify<Any?>(skyframeExecutor, Mockito.never()).dropUnnecessaryTemporarySkyframeState()
        Mockito.verify<Any?>(syscallCache, Mockito.never()).clear()

        underTest.handle(MINOR)

        Mockito.verify<Any?>(skyframeExecutor, Mockito.times(1)).dropUnnecessaryTemporarySkyframeState()
        Mockito.verify<Any?>(syscallCache, Mockito.times(1)).clear()

        underTest.handle(MINOR)

        Mockito.verify<Any?>(skyframeExecutor, Mockito.times(2)).dropUnnecessaryTemporarySkyframeState()
        Mockito.verify<Any?>(syscallCache, Mockito.times(2)).clear()

        underTest.handle(FULL)

        Mockito.verify<Any?>(skyframeExecutor, Mockito.times(3)).dropUnnecessaryTemporarySkyframeState()
        Mockito.verify<Any?>(syscallCache, Mockito.times(3)).clear()

        underTest.handle(FULL)

        Mockito.verify<Any?>(skyframeExecutor, Mockito.times(3)).dropUnnecessaryTemporarySkyframeState()
        Mockito.verify<Any?>(syscallCache, Mockito.times(3)).clear()

        assertStats(underTest, MemoryPressureStats.newBuilder().setMinorGcDrops(2).setFullGcDrops(1))
    }

    companion object {
        private val MINOR: MemoryPressureEvent? = MemoryPressureEvent.newBuilder()
            .setWasManualGc(false)
            .setWasFullGc(false)
            .setTenuredSpaceMaxBytes(100L)
            .setTenuredSpaceUsedBytes(91L)
            .setDuration(java.time.Duration.ofMillis(42L))
            .build()
        private val FULL: MemoryPressureEvent? = MemoryPressureEvent.newBuilder()
            .setWasManualGc(false)
            .setWasFullGc(true)
            .setTenuredSpaceMaxBytes(100L)
            .setTenuredSpaceUsedBytes(91L)
            .setDuration(java.time.Duration.ofSeconds(42L))
            .build()

        private fun createOptions(
            threshold: Int, minorGcDropLimit: Int, fullGcDropLimit: Int
        ): MemoryPressureOptions {
            val options: MemoryPressureOptions =
                com.google.devtools.common.options.Options.getDefaults<O>(MemoryPressureOptions::class.java)
            options.setSkyframeHighWaterMarkMemoryThreshold(threshold)
            options.setSkyframeHighWaterMarkMinorGcDropsPerInvocation(minorGcDropLimit)
            options.setSkyframeHighWaterMarkFullGcDropsPerInvocation(fullGcDropLimit)
            return options
        }

        private fun assertStats(
            underTest: HighWaterMarkLimiter, expectedBuilder: MemoryPressureStats.Builder
        ) {
            val actualBuilder: MemoryPressureStats.Builder = MemoryPressureStats.newBuilder()
            underTest.populateStats(actualBuilder)
            assertThat(actualBuilder.build()).isEqualTo(expectedBuilder.build())
        }
    }
}
