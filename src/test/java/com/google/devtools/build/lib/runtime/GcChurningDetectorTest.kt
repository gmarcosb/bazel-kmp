// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.runtime.MemoryPressure.MemoryPressureStats

@RunWith(JUnit4::class)
class GcChurningDetectorTest {
    private val mockBugReporter: BugReporter? = Mockito.mock<BugReporter?>(BugReporter::class.java)

    @org.junit.Test
    fun populateStats() {
        val fakeClock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()

        val underTest: GcChurningDetector =
            GcChurningDetector( /* thresholdPercentage= */
                100,  /* thresholdPercentageIfMultipleTopLevelTargets= */
                100,
                fakeClock,
                mockBugReporter
            )

        run {
            val actualBuilder: MemoryPressureStats.Builder = MemoryPressureStats.newBuilder()
            underTest.populateStats(actualBuilder)
            assertThat(actualBuilder.build()).isEqualTo(MemoryPressureStats.getDefaultInstance())
        }

        fakeClock.advance(java.time.Duration.ofMillis(50L))
        underTest.handle(fullGcEvent(java.time.Duration.ofMillis(10L)))

        fakeClock.advance(java.time.Duration.ofMillis(50L))
        underTest.handle(fullGcEvent(java.time.Duration.ofMillis(40L)))

        fakeClock.advance(java.time.Duration.ofMillis(50L))
        underTest.handle(fullGcEvent(java.time.Duration.ofMillis(10L)))

        run {
            val actualBuilder: MemoryPressureStats.Builder = MemoryPressureStats.newBuilder()
            underTest.populateStats(actualBuilder)
            assertThat(actualBuilder.build())
                .isEqualTo(
                    MemoryPressureStats.newBuilder()
                        .addFullGcFractionPoint(
                            FullGcFractionPoint.newBuilder()
                                .setInvocationWallTimeSoFarMs(50)
                                .setFullGcFractionSoFar(0.2)
                                .build()
                        )
                        .addFullGcFractionPoint(
                            FullGcFractionPoint.newBuilder()
                                .setInvocationWallTimeSoFarMs(100)
                                .setFullGcFractionSoFar(0.5)
                                .build()
                        )
                        .addFullGcFractionPoint(
                            FullGcFractionPoint.newBuilder()
                                .setInvocationWallTimeSoFarMs(150)
                                .setFullGcFractionSoFar(0.4)
                                .build()
                        )
                        .setPeakFullGcFractionPoint(
                            FullGcFractionPoint.newBuilder()
                                .setInvocationWallTimeSoFarMs(100)
                                .setFullGcFractionSoFar(0.5)
                                .build()
                        )
                        .build()
                )
        }

        verifyNoOom()
    }

    @org.junit.Test
    fun doesNotRecordDataPointIfInvocationWallTimeSoFarIsLessThanOneMillisecond() {
        val fakeClock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()

        val underTest: GcChurningDetector =
            GcChurningDetector( /* thresholdPercentage= */
                100,  /* thresholdPercentageIfMultipleTopLevelTargets= */
                100,
                fakeClock,
                mockBugReporter
            )

        fakeClock.advance(java.time.Duration.ofNanos(456L))
        underTest.handle(fullGcEvent(java.time.Duration.ofNanos(123L)))

        fakeClock.advance(java.time.Duration.ofMillis(2L))
        underTest.handle(fullGcEvent(java.time.Duration.ofMillis(1L)))

        val actualBuilder: MemoryPressureStats.Builder = MemoryPressureStats.newBuilder()
        underTest.populateStats(actualBuilder)

        assertThat(actualBuilder.build())
            .isEqualTo(
                MemoryPressureStats.newBuilder()
                    .addFullGcFractionPoint(
                        FullGcFractionPoint.newBuilder()
                            .setInvocationWallTimeSoFarMs(2)
                            .setFullGcFractionSoFar(0.5)
                            .build()
                    )
                    .setPeakFullGcFractionPoint(
                        FullGcFractionPoint.newBuilder()
                            .setInvocationWallTimeSoFarMs(2)
                            .setFullGcFractionSoFar(0.5)
                            .build()
                    )
                    .build()
            )
    }

    @org.junit.Test
    fun oom() {
        val fakeClock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()

        val underTest: GcChurningDetector =
            GcChurningDetector( /* thresholdPercentage= */
                50,  /* thresholdPercentageIfMultipleTopLevelTargets= */
                50,
                fakeClock,
                mockBugReporter
            )

        fakeClock.advance(java.time.Duration.ofMinutes(3L))
        underTest.handle(fullGcEvent(java.time.Duration.ofMinutes(1L)))
        verifyNoOom()

        fakeClock.advance(java.time.Duration.ofMinutes(1L))
        underTest.handle(fullGcEvent(java.time.Duration.ofMinutes(1L)))
        verifyOom()
    }

    @org.junit.Test
    fun minInvocationWallTimeDuration() {
        val fakeClock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()

        val underTest: GcChurningDetector =
            GcChurningDetector( /* thresholdPercentage= */
                50,  /* thresholdPercentageIfMultipleTopLevelTargets= */
                50,
                fakeClock,
                mockBugReporter
            )

        fakeClock.advance(java.time.Duration.ofSeconds(30L))
        underTest.handle(fullGcEvent(java.time.Duration.ofSeconds(15L)))
        verifyNoOom()

        fakeClock.advance(java.time.Duration.ofSeconds(29L))
        underTest.handle(fullGcEvent(java.time.Duration.ofSeconds(14L)))
        verifyNoOom()

        fakeClock.advance(java.time.Duration.ofSeconds(1L))
        underTest.handle(fullGcEvent(java.time.Duration.ofSeconds(1L)))
        verifyOom()
    }

    @org.junit.Test
    fun thresholdPercentageIfMultipleTopLevelTargets_onlySingleTarget() {
        val fakeClock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()

        val underTest: GcChurningDetector =
            GcChurningDetector( /* thresholdPercentage= */
                100,  /* thresholdPercentageIfMultipleTopLevelTargets= */
                50,
                fakeClock,
                mockBugReporter
            )

        fakeClock.advance(java.time.Duration.ofSeconds(60L))
        underTest.handle(fullGcEvent(java.time.Duration.ofSeconds(30L)))
        verifyNoOom()

        underTest.targetParsingComplete(1)
        fakeClock.advance(java.time.Duration.ofSeconds(30L))
        underTest.handle(fullGcEvent(java.time.Duration.ofSeconds(20L)))
        verifyNoOom()
    }

    @org.junit.Test
    fun thresholdPercentageIfMultipleTopLevelTargets() {
        val fakeClock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()

        val underTest: GcChurningDetector =
            GcChurningDetector( /* thresholdPercentage= */
                100,  /* thresholdPercentageIfMultipleTopLevelTargets= */
                50,
                fakeClock,
                mockBugReporter
            )

        fakeClock.advance(java.time.Duration.ofSeconds(60L))
        underTest.handle(fullGcEvent(java.time.Duration.ofSeconds(40L)))
        verifyNoOom()

        underTest.targetParsingComplete(2)
        fakeClock.advance(java.time.Duration.ofSeconds(30L))
        underTest.handle(fullGcEvent(java.time.Duration.ofSeconds(20L)))
        verifyOom()
    }

    @org.junit.Test
    fun fullGcStartedBeforeInvocationStarted() {
        val fakeClock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()

        val underTest: GcChurningDetector =
            GcChurningDetector( /* thresholdPercentage= */
                100,  /* thresholdPercentageIfMultipleTopLevelTargets= */
                100,
                fakeClock,
                mockBugReporter
            )

        fakeClock.advance(java.time.Duration.ofMillis(1L))
        underTest.handle(fullGcEvent(java.time.Duration.ofSeconds(2L)))

        val actualBuilder: MemoryPressureStats.Builder = MemoryPressureStats.newBuilder()
        underTest.populateStats(actualBuilder)

        assertThat(actualBuilder.build())
            .isEqualTo(
                MemoryPressureStats.newBuilder()
                    .addFullGcFractionPoint(
                        FullGcFractionPoint.newBuilder()
                            .setInvocationWallTimeSoFarMs(1)
                            .setFullGcFractionSoFar(1.0)
                            .build()
                    )
                    .setPeakFullGcFractionPoint(
                        FullGcFractionPoint.newBuilder()
                            .setInvocationWallTimeSoFarMs(1)
                            .setFullGcFractionSoFar(1.0)
                            .build()
                    )
                    .build()
            )
    }

    private fun verifyNoOom() {
        Mockito.verifyNoInteractions(mockBugReporter)
    }

    private fun verifyOom() {
        val crashArgument: ArgumentCaptor<Crash> = ArgumentCaptor.forClass<Crash?, Crash?>(Crash::class.java)
        Mockito.verify<BugReporter?>(mockBugReporter)
            .handleCrash(crashArgument.capture(), ArgumentMatchers.any<CrashContext?>())
        val crash: Crash = crashArgument.getValue()
        val oom: Throwable = crash.throwable
        Truth.assertThat(oom).isInstanceOf(java.lang.OutOfMemoryError::class.java)
        assertThat(crash.detailedExitCode.getFailureDetail().getCrash().getOomCauseCategory())
            .isEqualTo(OomCauseCategory.GC_CHURNING)
    }

    @org.junit.After
    fun verifyNoMoreBugReports() {
        Mockito.verifyNoMoreInteractions(mockBugReporter)
    }

    companion object {
        private fun fullGcEvent(duration: java.time.Duration?): MemoryPressureEvent {
            return MemoryPressureEvent.newBuilder()
                .setWasFullGc(true)
                .setTenuredSpaceUsedBytes(1234L)
                .setTenuredSpaceMaxBytes(5678L)
                .setDuration(duration)
                .build()
        }
    }
}
