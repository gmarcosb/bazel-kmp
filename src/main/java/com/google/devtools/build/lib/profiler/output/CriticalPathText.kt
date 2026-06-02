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
package com.google.devtools.build.lib.profiler.output

import com.google.devtools.build.lib.profiler.output.TextPrinter
import com.google.devtools.build.lib.profiler.statistics.CriticalPathStatistics
import com.google.devtools.build.lib.util.TimeUtilities
import java.io.PrintStream

/**
 * Generate textual output from [CriticalPathStatistics].
 */
class CriticalPathText(out: PrintStream?, critPathStats: CriticalPathStatistics) : TextPrinter(out) {
    private val criticalPathStats: CriticalPathStatistics

    init {
        this.criticalPathStats = critPathStats
    }

    /**
     * Print total and optimal critical paths if available.
     */
    fun printCriticalPaths() {
        val totalPathTimeNanos: Long = criticalPathStats.getTotalDuration().toNanos()
        lnPrintf("%s (%s):", "Critical path", TimeUtilities.prettyTime(totalPathTimeNanos.toDouble()))
        lnPrintf("%11s %8s   %s", "Time", "Percentage", "Description")

        for (traceEvent in criticalPathStats.getCriticalPathEntries()) {
            val description: String? = traceEvent.name.replace(':', ' ')
            lnPrintf(
                "%11s %8s   %s",
                TimeUtilities.prettyTime(traceEvent.duration.toNanos().toDouble()),
                TextPrinter.Companion.prettyPercentage(traceEvent.duration.toNanos().toDouble() / totalPathTimeNanos),
                description
            )
        }
    }
}


