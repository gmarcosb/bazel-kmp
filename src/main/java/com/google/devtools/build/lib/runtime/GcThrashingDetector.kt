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

import com.google.devtools.build.lib.server.FailureDetails

/**
 * Per-invocation handler of [MemoryPressureEvent] to detect GC thrashing.
 * 
 * 
 * "GC thrashing" is the situation when Blaze is under memory pressure and there are full GCs but
 * not much memory is being reclaimed. See [GcChurningDetector] for "GC churning". GC
 * thrashing and GC churning can sometimes, but not necessarily, coincide. Consider a situation
 * where Blaze all of a sudden is under memory pressure and full GCs do not alleviate it. By
 * assumption not much time has been spent on full GCs up until this point, so this cannot be GC
 * churning, but if the memory pressure is high enough it could be GC thrashing.
 * 
 * 
 * For each [Limit], maintains a sliding window of the timestamps of consecutive full GCs
 * within [Limit.period] where [MemoryPressureEvent.percentTenuredSpaceUsed] was more
 * than [.threshold]. If [Limit.count] consecutive over-threshold full GCs within [ ][Limit.period] are observed, calls [BugReporter.handleCrash] with an [ ].
 * 
 * 
 * Manual GCs do not contribute to the limit. This is to avoid OOMing on GCs manually triggered
 * for memory metrics.
 */
internal class GcThrashingDetector @com.google.common.annotations.VisibleForTesting constructor(
    private val threshold: Int,
    limits: MutableList<Limit?>,
    clock: com.google.devtools.build.lib.clock.Clock,
    bugReporter: BugReporter
) {
    internal class Limit(period: java.time.Duration, val count: Int) {
        val period: java.time.Duration

        init {
            this.period = period
            java.util.Objects.requireNonNull<java.time.Duration?>(period, "period")
            com.google.common.base.Preconditions.checkArgument(
                !period.isNegative() && !period.isZero(), "period must be positive: %s", period
            )
            com.google.common.base.Preconditions.checkArgument(count > 0, "count must be positive: %s", count)
        }

        companion object {
            fun of(period: java.time.Duration, count: Int): Limit {
                return com.google.devtools.build.lib.runtime.GcThrashingDetector.Limit(period, count)
            }
        }
    }

    private val trackers: com.google.common.collect.ImmutableList<SingleLimitTracker>
    private val clock: com.google.devtools.build.lib.clock.Clock
    private val bugReporter: BugReporter

    init {
        this.trackers = limits.stream()
            .map<SingleLimitTracker?>(java.util.function.Function { limit: Limit? -> SingleLimitTracker(limit!!) })
            .collect(com.google.common.collect.ImmutableList.toImmutableList<SingleLimitTracker?>())
        this.clock = clock
        this.bugReporter = bugReporter
    }

    // This is called from MemoryPressureListener on a single memory-pressure-listener-0 thread, so it
    // should never be called concurrently, but mark it synchronized for good measure.
    @kotlin.jvm.Synchronized
    fun handle(event: MemoryPressureEvent) {
        if (event.percentTenuredSpaceUsed() < threshold) {
            for (tracker in trackers) {
                tracker.underThresholdGc()
            }
            return
        }

        if (!event.wasFullGc || event.wasManualGc) {
            return
        }

        val now: Instant? = clock.now()
        for (tracker in trackers) {
            tracker.overThresholdGc(now)
        }
    }

    /** Tracks GC history for a single [Limit].  */
    private inner class SingleLimitTracker(limit: Limit) {
        private val period: java.time.Duration
        private val count: Int
        private val window: java.util.Queue<Instant?>

        init {
            this.period = limit.period
            this.count = limit.count
            this.window = ArrayDeque<Instant?>(count)
        }

        fun underThresholdGc() {
            window.clear()
        }

        fun overThresholdGc(now: Instant) {
            val periodStart: Instant = now.minus(period)
            while (!window.isEmpty() && window.element().isBefore(periodStart)) {
                window.remove()
            }
            window.add(now)

            if (window.size() == count) {
                val oom: java.lang.OutOfMemoryError =
                    java.lang.OutOfMemoryError(
                        java.lang.String.format(
                            "GcThrashingDetector forcing exit: the tenured space has been more than %s%%"
                                    + " occupied after %s consecutive full GCs within the past %s seconds.",
                            threshold, count, period.toSeconds()
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
                                        .setOomCauseCategory(OomCauseCategory.GC_THRASHING)
                                )
                                .build()
                        )
                    ),
                    CrashContext.halt()
                )
            }
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /** If enabled in [MemoryPressureOptions], creates a [GcThrashingDetector].  */
        fun createForCommand(options: MemoryPressureOptions): GcThrashingDetector? {
            if (options.getGcThrashingLimits().isEmpty() || options.getGcThrashingThreshold() == 100) {
                return null
            }

            return GcThrashingDetector(
                options.getGcThrashingThreshold(),
                options.getGcThrashingLimits(),
                com.google.devtools.build.lib.clock.BlazeClock.instance(),
                BugReporter.defaultInstance()
            )
        }
    }
}
