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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.runtime.GcThrashingDetector.Limit

/** Tests for [GcThrashingDetector].  */
@RunWith(TestParameterInjector::class)
class GcThrashingDetectorTest {
    private val bugReporter: BugReporter? = Mockito.mock<BugReporter?>(BugReporter::class.java)
    private val clock: com.google.devtools.build.lib.testutil.ManualClock =
        com.google.devtools.build.lib.testutil.ManualClock()

    private enum class GcType {
        ORGANIC_FULL,
        MINOR,
        MANUAL
    }

    @Before
    fun setClock() {
        clock.advanceMillis(100000)
    }

    @org.junit.After
    fun verifyNoMoreBugReports() {
        Mockito.verifyNoMoreInteractions(bugReporter)
    }

    @org.junit.Test
    fun limitViolated_oom() {
        val detector: GcThrashingDetector =
            createDetector( /* threshold= */90, Limit.of(java.time.Duration.ofSeconds(10), 2))

        detector.handle(percentUsedAfterGc(91, GcType.ORGANIC_FULL))
        clock.advance(java.time.Duration.ofSeconds(5))
        detector.handle(percentUsedAfterGc(91, GcType.ORGANIC_FULL))

        verifyOom()
    }

    @org.junit.Test
    fun underThreshold_noOom() {
        val detector: GcThrashingDetector =
            createDetector( /* threshold= */90, Limit.of(java.time.Duration.ofSeconds(10), 2))

        detector.handle(percentUsedAfterGc(89, GcType.ORGANIC_FULL))
        clock.advance(java.time.Duration.ofSeconds(5))
        detector.handle(percentUsedAfterGc(89, GcType.ORGANIC_FULL))

        verifyNoOom()
    }

    @org.junit.Test
    fun limitViolatedAfterUnderThreshold_oom() {
        val detector: GcThrashingDetector =
            createDetector( /* threshold= */90, Limit.of(java.time.Duration.ofSeconds(10), 2))

        detector.handle(percentUsedAfterGc(89, GcType.ORGANIC_FULL))
        clock.advance(java.time.Duration.ofSeconds(1))
        detector.handle(percentUsedAfterGc(91, GcType.ORGANIC_FULL))
        clock.advance(java.time.Duration.ofSeconds(1))
        detector.handle(percentUsedAfterGc(91, GcType.ORGANIC_FULL))

        verifyOom()
    }

    @org.junit.Test
    fun outsideOfPeriod_noOom() {
        val detector: GcThrashingDetector =
            createDetector( /* threshold= */90, Limit.of(java.time.Duration.ofSeconds(10), 2))

        detector.handle(percentUsedAfterGc(91, GcType.ORGANIC_FULL))
        clock.advance(java.time.Duration.ofSeconds(11))
        detector.handle(percentUsedAfterGc(91, GcType.ORGANIC_FULL))

        verifyNoOom()
    }

    @org.junit.Test
    fun backUnderThreshold_noOom(@TestParameter type: GcType) {
        val detector: GcThrashingDetector =
            createDetector( /* threshold= */90, Limit.of(java.time.Duration.ofSeconds(10), 2))

        detector.handle(percentUsedAfterGc(91, GcType.ORGANIC_FULL))
        clock.advance(java.time.Duration.ofSeconds(1))
        detector.handle(percentUsedAfterGc(89, type))
        clock.advance(java.time.Duration.ofSeconds(1))
        detector.handle(percentUsedAfterGc(91, GcType.ORGANIC_FULL))

        verifyNoOom()
    }

    @org.junit.Test
    fun notOrganicFullGc_noOom(@TestParameter("MINOR", "MANUAL") type: GcType) {
        val detector: GcThrashingDetector =
            createDetector( /* threshold= */90, Limit.of(java.time.Duration.ofSeconds(10), 2))

        detector.handle(percentUsedAfterGc(91, type))
        clock.advance(java.time.Duration.ofSeconds(5))
        detector.handle(percentUsedAfterGc(91, GcType.ORGANIC_FULL))

        verifyNoOom()
    }

    @org.junit.Test
    fun multipleLimits_noneViolated_noOom() {
        val detector: GcThrashingDetector =
            createDetector( /* threshold= */
                90,
                Limit.of(java.time.Duration.ofSeconds(10), 2),
                Limit.of(java.time.Duration.ofMinutes(1), 3)
            )

        detector.handle(percentUsedAfterGc(91, GcType.ORGANIC_FULL))
        clock.advance(java.time.Duration.ofSeconds(11))
        detector.handle(percentUsedAfterGc(91, GcType.ORGANIC_FULL))
        clock.advance(java.time.Duration.ofSeconds(50))
        detector.handle(percentUsedAfterGc(91, GcType.ORGANIC_FULL))

        verifyNoOom()
    }

    @org.junit.Test
    fun multipleLimits_firstViolated_oom() {
        val detector: GcThrashingDetector =
            createDetector( /* threshold= */
                90,
                Limit.of(java.time.Duration.ofSeconds(10), 2),
                Limit.of(java.time.Duration.ofMinutes(1), 3)
            )

        detector.handle(percentUsedAfterGc(91, GcType.ORGANIC_FULL))
        clock.advance(java.time.Duration.ofSeconds(5))
        detector.handle(percentUsedAfterGc(91, GcType.ORGANIC_FULL))

        verifyOomWithMessage("2 consecutive full GCs within the past 10 seconds")
    }

    @org.junit.Test
    fun multipleLimits_secondViolated_oom() {
        val detector: GcThrashingDetector =
            createDetector( /* threshold= */
                90,
                Limit.of(java.time.Duration.ofSeconds(10), 2),
                Limit.of(java.time.Duration.ofMinutes(1), 3)
            )

        detector.handle(percentUsedAfterGc(91, GcType.ORGANIC_FULL))
        clock.advance(java.time.Duration.ofSeconds(11))
        detector.handle(percentUsedAfterGc(91, GcType.ORGANIC_FULL))
        clock.advance(java.time.Duration.ofSeconds(11))
        detector.handle(percentUsedAfterGc(91, GcType.ORGANIC_FULL))

        verifyOomWithMessage("3 consecutive full GCs within the past 60 seconds")
    }

    private fun createDetector(threshold: Int, vararg limits: Limit?): GcThrashingDetector {
        return GcThrashingDetector(
            threshold,
            com.google.common.collect.ImmutableList.< E > copyOf < E ? > (limits),
            clock,
            bugReporter
        )
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    private fun verifyOom(): java.lang.OutOfMemoryError {
        val crashArgument: ArgumentCaptor<Crash> = ArgumentCaptor.forClass<Crash?, Crash?>(Crash::class.java)
        Mockito.verify<BugReporter?>(bugReporter)
            .handleCrash(crashArgument.capture(), ArgumentMatchers.any<CrashContext?>())
        val crash: Crash = crashArgument.getValue()
        val oom: Throwable = crash.throwable
        Truth.assertThat(oom).isInstanceOf(java.lang.OutOfMemoryError::class.java)
        assertThat(crash.detailedExitCode.getFailureDetail().getCrash().getOomCauseCategory())
            .isEqualTo(OomCauseCategory.GC_THRASHING)
        return oom as java.lang.OutOfMemoryError
    }

    private fun verifyOomWithMessage(message: String?) {
        val oom: java.lang.OutOfMemoryError = verifyOom()
        Truth.assertThat(oom).hasMessageThat().contains(message)
    }

    private fun verifyNoOom() {
        Mockito.verifyNoInteractions(bugReporter)
    }

    companion object {
        private fun percentUsedAfterGc(percentUsed: Int, type: GcType): MemoryPressureEvent {
            com.google.common.base.Preconditions.checkArgument(percentUsed >= 0, percentUsed)
            val event: MemoryPressureEvent.Builder =
                MemoryPressureEvent.newBuilder()
                    .setTenuredSpaceUsedBytes(percentUsed)
                    .setTenuredSpaceMaxBytes(100L)
                    .setDuration(java.time.Duration.ofMillis(42L))
            when (type) {
                GcType.ORGANIC_FULL -> event.setWasFullGc(true)
                GcType.MINOR -> {}
                GcType.MANUAL -> event.setWasManualGc(true).setWasFullGc(true)
            }
            return event.build()
        }
    }
}
