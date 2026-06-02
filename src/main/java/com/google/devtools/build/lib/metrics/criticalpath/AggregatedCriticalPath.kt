// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.metrics.criticalpath

import com.google.common.base.Joiner
import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.actions.AggregatedSpawnMetrics
import java.time.Duration

/**
 * Aggregates all the critical path components in one object. This allows us to easily access the
 * components data and have a proper toString().
 */
// Use ints instead of Durations to improve build time (cl/505728570)
class AggregatedCriticalPath(
  @kotlin.jvm.JvmField private val totalTime: Duration,
  aggregatedSpawnMetrics: AggregatedSpawnMetrics,
  criticalPathComponents: ImmutableList<CriticalPathComponent?>
) {
    private val aggregatedSpawnMetrics: AggregatedSpawnMetrics
    private val criticalPathComponents: ImmutableList<CriticalPathComponent?>

    init {
        this.aggregatedSpawnMetrics = aggregatedSpawnMetrics
        this.criticalPathComponents = criticalPathComponents
    }

    /** Total wall time spent running the critical path actions.  */
    fun getAggregatedElapsedTime(): Duration {
        return totalTime
    }

    fun getSpawnMetrics(): AggregatedSpawnMetrics {
        return aggregatedSpawnMetrics
    }

    /** Returns a list of all the component stats for the critical path.  */
    fun components(): ImmutableList<CriticalPathComponent?> {
        return criticalPathComponents
    }

    fun getNewStringSummary(): String? {
        val executionWallTimeInMs: Int =
            aggregatedSpawnMetrics.getTotalDuration(SpawnMetrics::executionWallTimeInMs)
        val overheadTimeInMs: Int =
            (aggregatedSpawnMetrics.getTotalDuration(SpawnMetrics::totalTimeInMs)
                    - executionWallTimeInMs)
        return String.format(
            Locale.US,
            "Execution critical path %.2fs (setup %.2fs, action wall time %.2fs)",
            totalTime.toMillis() / 1000.0,
            overheadTimeInMs / 1000.0,
            executionWallTimeInMs / 1000.0
        )
    }

    override fun toString(): String {
        return toString(false, true)
    }

    private fun toString(summary: Boolean, remote: Boolean): String {
        val sb = StringBuilder("Critical Path: ")
        sb.append(String.format(Locale.US, "%.2f", totalTime.toMillis() / 1000.0))
        sb.append("s")
        if (remote) {
            sb.append(", ")
            sb.append(getSpawnMetrics().toString(totalTime, summary))
        }
        if (summary || criticalPathComponents.isEmpty()) {
            return sb.toString()
        }
        sb.append("\n  ")
        Joiner.on("\n  ").appendTo(sb, criticalPathComponents)
        return sb.toString()
    }

    /**
     * Returns a summary version of the critical path stats that omits stats that are not useful to
     * the user.
     */
    fun toStringSummary(): String {
        return toString(true, true)
    }

    /**
     * Same as toStringSummary but also omits remote stats. This is to be used in Bazel because
     * currently the Remote stats are not calculated correctly.
     */
    fun toStringSummaryNoRemote(): String {
        return toString(true, false)
    }

    companion object {
        @kotlin.jvm.JvmField
        val EMPTY: AggregatedCriticalPath = AggregatedCriticalPath(
            Duration.ZERO,
            AggregatedSpawnMetrics.EMPTY,
            ImmutableList.of<CriticalPathComponent?>()
        )
    }
}
