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

/**
 * Per-invocation handler of [MemoryPressureEvent] to detect GC churning.
 * 
 * 
 * "GC churning" is the situation when the time spent doing full GCs is a big fraction of the
 * overall invocation wall time. See [GcThrashingDetector] for "GC thrashing". GC churning and
 * GC thrashing can sometimes, but not necessarily, coincide. Consider a situation where Blaze does
 * many full GCs all of which are fruitful. By definition that cannot be GC thrashing, but if the
 * full GCs are numerous and long enough it could be GC churning.
 */
internal class GcChurningDetector @com.google.common.annotations.VisibleForTesting constructor(
    @field:kotlin.concurrent.Volatile private var thresholdPercentage: Int,
    private val thresholdPercentageIfMultipleTopLevelTargets: Int,
    clock: com.google.devtools.build.lib.clock.Clock,
    bugReporter: BugReporter
) {
    private var cumulativeFullGcDuration: java.time.Duration = java.time.Duration.ZERO
    private val clock: com.google.devtools.build.lib.clock.Clock
    private val start: Instant?
    private val fullGcFractionPoints: java.util.ArrayList<FullGcFractionPoint?> =
        java.util.ArrayList<FullGcFractionPoint?>()

    private var peakFullGcPractionPoint: FullGcFractionPoint = FullGcFractionPoint.getDefaultInstance()
    private val bugReporter: BugReporter

    init {
        this.clock = clock
        this.start = clock.now()
        this.bugReporter = bugReporter
    }

    fun targetParsingComplete(numTopLevelTargets: Int) {
        if (numTopLevelTargets > 1) {
            thresholdPercentage = thresholdPercentageIfMultipleTopLevelTargets
            logger.atInfo().log(
                "Switched to thresholdPercentage of %s because there were %s top-level targets",
                thresholdPercentage, numTopLevelTargets
            )
        }
    }

    // This is called from MemoryPressureListener on a single memory-pressure-listener-0 thread, so it
    // should never be called concurrently, but mark it synchronized for good measure.
    @kotlin.jvm.Synchronized
    fun handle(event: MemoryPressureEvent) {
        if (!event.wasFullGc || event.wasManualGc) {
            return
        }
        val invocationWallTimeDuration: java.time.Duration = java.time.Duration.between(start, clock.now())
        var gcEventDuration: java.time.Duration? = event.duration
        if (event.duration.compareTo(invocationWallTimeDuration) > 0) {
            // Clamp the GC event's duration to the duration of the current invocation in case this is an
            // event for a full GC that started before the current invocation started.
            gcEventDuration = invocationWallTimeDuration
        }
        cumulativeFullGcDuration = cumulativeFullGcDuration.plus(gcEventDuration)

        // This narrowing conversion is fine in practice since MAX_INT ms is almost 25 days, and
        // we don't care about supporting an invocation running for that long.
        val invocationWallTimeSoFarMs: Int = invocationWallTimeDuration.toMillis().toInt()
        if (invocationWallTimeSoFarMs == 0) {
            // Given that our data points have millisecond resolution, don't bother recording a data point
            // if it's been less than a full millisecond so far.
            return
        }

        val gcFraction: Double = cumulativeFullGcDuration.toMillis() * 1.0 / invocationWallTimeSoFarMs
        val fullGcFractionPoint: FullGcFractionPoint =
            FullGcFractionPoint.newBuilder()
                .setInvocationWallTimeSoFarMs(invocationWallTimeSoFarMs)
                .setFullGcFractionSoFar(gcFraction)
                .build()
        if (gcFraction > peakFullGcPractionPoint.getFullGcFractionSoFar()) {
            peakFullGcPractionPoint = fullGcFractionPoint
        }
        fullGcFractionPoints.add(fullGcFractionPoint)
        logger.atInfo().log(
            "cumulativeFullGcDuration=%s invocationWallTimeDuration=%s gcFraction=%.3f",
            cumulativeFullGcDuration, invocationWallTimeDuration, gcFraction
        )

        val gcFractionPercentage = gcFraction * 100
        if (gcFractionPercentage >= thresholdPercentage
            && invocationWallTimeDuration.compareTo(MIN_INVOCATION_WALL_TIME_DURATION) >= 0
        ) {
            val oom: java.lang.OutOfMemoryError =
                java.lang.OutOfMemoryError(
                    java.lang.String.format(
                        "GcChurningDetector forcing exit: %.1f%% of the invocation's wall time so far"
                                + " (%ss) has been spent doing full GCs",
                        gcFractionPercentage, invocationWallTimeDuration.toSeconds()
                    )
                )
            logger.atInfo().log("Calling handleCrash")
            bugReporter.handleCrash(
                Crash.from(
                    oom,
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage(oom.getMessage())
                            .setCrash(
                                FailureDetails.Crash.newBuilder()
                                    .setCode(Code.CRASH_OOM)
                                    .setOomCauseCategory(OomCauseCategory.GC_CHURNING)
                            )
                            .build()
                    )
                ),
                CrashContext.halt()
            )
        }
    }

    fun populateStats(memoryPressureStatsBuilder: MemoryPressureStats.Builder) {
        memoryPressureStatsBuilder.addAllFullGcFractionPoint(fullGcFractionPoints)
        if (!fullGcFractionPoints.isEmpty()) {
            memoryPressureStatsBuilder.setPeakFullGcFractionPoint(peakFullGcPractionPoint)
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
        private val MIN_INVOCATION_WALL_TIME_DURATION: java.time.Duration = java.time.Duration.ofMinutes(1)

        fun createForCommand(options: MemoryPressureOptions): GcChurningDetector {
            return GcChurningDetector(
                options.getGcChurningThreshold(),
                options
                    .getGcChurningThresholdIfMultipleTopLevelTargets()
                    .orElse(options.getGcChurningThreshold()),
                com.google.devtools.build.lib.clock.BlazeClock.instance(),
                BugReporter.defaultInstance()
            )
        }
    }
}
