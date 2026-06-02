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
package com.google.devtools.build.lib.profiler.statistics

/**
 * Extracts and keeps summary statistics from all [ProfilePhase]s for formatting to various
 * outputs.
 */
class PhaseSummaryStatistics : Iterable<com.google.devtools.build.lib.profiler.ProfilePhase?> {
    private var totalDurationNanos: Long
    private val durations: java.util.EnumMap<com.google.devtools.build.lib.profiler.ProfilePhase?, Long?>

    init {
        durations =
            java.util.EnumMap<com.google.devtools.build.lib.profiler.ProfilePhase?, Long?>(com.google.devtools.build.lib.profiler.ProfilePhase::class.java)
        totalDurationNanos = 0
    }

    /** Add a single profile phase.  */
    fun addProfilePhase(phase: com.google.devtools.build.lib.profiler.ProfilePhase?, duration: java.time.Duration) {
        totalDurationNanos += duration.toNanos()
        durations.put(phase, duration.toNanos())
    }

    /** @return whether the given [ProfilePhase] was executed
     */
    private fun contains(phase: com.google.devtools.build.lib.profiler.ProfilePhase?): Boolean {
        return durations.containsKey(phase)
    }

    /**
     * @return the execution duration of a given [ProfilePhase]
     * @throws NoSuchElementException if the given [ProfilePhase] was not executed
     */
    fun getDurationNanos(phase: com.google.devtools.build.lib.profiler.ProfilePhase?): Long {
        checkContains(phase)
        return durations.get(phase)
    }

    /**
     * @return The duration of the phase relative to the sum of all phase durations
     * @throws NoSuchElementException if the given [ProfilePhase] was not executed
     */
    fun getRelativeDuration(phase: com.google.devtools.build.lib.profiler.ProfilePhase?): Double {
        checkContains(phase)
        return getDurationNanos(phase).toDouble() / totalDurationNanos
    }

    fun getTotalDuration(): Long {
        return totalDurationNanos
    }

    override fun iterator(): MutableIterator<com.google.devtools.build.lib.profiler.ProfilePhase?>? {
        return durations.keySet().iterator()
    }

    private fun checkContains(phase: com.google.devtools.build.lib.profiler.ProfilePhase?) {
        if (!contains(phase)) {
            throw java.util.NoSuchElementException("Phase " + phase + " was not executed")
        }
    }
}

