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

import com.google.devtools.build.lib.profiler.TraceEvent

/**
 * Keeps a predefined list of [TraceEvent]'s cumulative durations and allows iterating over
 * pairs of their descriptions and relative durations.
 */
class CriticalPathStatistics(traceEvents: MutableList<TraceEvent>) {
    private val criticalPathEntries: com.google.common.collect.ImmutableList<TraceEvent?>
    private var totalDuration: java.time.Duration = java.time.Duration.ZERO

    init {
        val criticalPathEntriesBuilder: com.google.common.collect.ImmutableList.Builder<TraceEvent?> =
            com.google.common.collect.ImmutableList.Builder<TraceEvent?>()
        for (traceEvent in traceEvents) {
            if (com.google.devtools.build.lib.profiler.ProfilerTask.CRITICAL_PATH_COMPONENT.description == traceEvent.category) {
                criticalPathEntriesBuilder.add(traceEvent)
                totalDuration = totalDuration.plus(traceEvent.duration)
            }
        }
        this.criticalPathEntries = criticalPathEntriesBuilder.build()
    }

    fun getTotalDuration(): java.time.Duration {
        return totalDuration
    }

    fun getCriticalPathEntries(): com.google.common.collect.ImmutableList<TraceEvent?> {
        return criticalPathEntries
    }
}

