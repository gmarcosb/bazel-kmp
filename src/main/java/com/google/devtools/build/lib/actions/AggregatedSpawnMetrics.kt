// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule.Builder.build
import com.google.devtools.build.buildjar.javac.plugins.processing.AnnotationProcessingModule.Builder.build
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder.build
import com.google.devtools.build.lib.actions.SpawnMetrics
import com.google.devtools.build.lib.actions.SpawnMetrics.ExecKind
import com.google.testing.junit.runner.junit4.JUnit4Bazel.Builder.build

/** Metrics aggregated per execution kind.  */
// Use ints instead of Durations to improve build time (cl/505728570)
class AggregatedSpawnMetrics private constructor(
    remoteMetrics: SpawnMetrics?,
    localMetrics: SpawnMetrics?,
    workerMetrics: SpawnMetrics?,
    otherMetrics: SpawnMetrics?
) {
    // Note that we are using fields instead of e.g. a map of SpawnMetrics to avoid the map overhead.
    // While this results in a bit more boilerplate code, it is worth the memory savings.
    private val remoteMetrics: SpawnMetrics?
    private val localMetrics: SpawnMetrics?
    private val workerMetrics: SpawnMetrics?
    private val otherMetrics: SpawnMetrics?

    init {
        this.remoteMetrics = remoteMetrics
        this.localMetrics = localMetrics
        this.workerMetrics = workerMetrics
        this.otherMetrics = otherMetrics
    }

    /**
     * Returns all present [SpawnMetrics].
     * 
     * 
     * There will be at most one [SpawnMetrics] object per [SpawnMetrics.ExecKind].
     */
    fun getAllMetrics(): com.google.common.collect.ImmutableCollection<SpawnMetrics?> {
        val metrics: com.google.common.collect.ImmutableList.Builder<SpawnMetrics?> =
            com.google.common.collect.ImmutableList.builder<SpawnMetrics?>()
        if (remoteMetrics != null) {
            metrics.add(remoteMetrics)
        }
        if (localMetrics != null) {
            metrics.add(localMetrics)
        }
        if (workerMetrics != null) {
            metrics.add(workerMetrics)
        }
        if (otherMetrics != null) {
            metrics.add(otherMetrics)
        }
        return metrics.build()
    }

    /**
     * Returns [SpawnMetrics] for the provided execution kind.
     * 
     * 
     * This will never return `null`, but the [SpawnMetrics] can be empty.
     */
    fun getMetrics(kind: ExecKind): SpawnMetrics {
        val result: SpawnMetrics? =
            when (kind) {
                ExecKind.REMOTE -> remoteMetrics
                ExecKind.LOCAL -> localMetrics
                ExecKind.WORKER -> workerMetrics
                ExecKind.OTHER -> otherMetrics
            }
        return if (result != null) result else com.google.devtools.build.lib.actions.SpawnMetrics.Builder.Companion.forExec(
            kind
        ).build()
    }

    /**
     * Returns [SpawnMetrics] for the remote execution.
     * 
     * @see .getMetrics
     */
    fun getRemoteMetrics(): SpawnMetrics {
        return getMetrics(ExecKind.REMOTE)
    }

    /**
     * Returns a new [AggregatedSpawnMetrics] that incorporates the provided metrics by summing
     * the duration ones and taking the maximum for the non-duration ones.
     */
    fun sumDurationsMaxOther(other: SpawnMetrics): AggregatedSpawnMetrics {
        val kind: ExecKind = other.execKind()
        val existing: SpawnMetrics = getMetrics(kind)
        val builder: com.google.devtools.build.lib.actions.SpawnMetrics.Builder =
            com.google.devtools.build.lib.actions.SpawnMetrics.Builder.Companion.forExec(kind)
                .addDurations(existing)
                .addDurations(other)
                .maxNonDurations(existing)
                .maxNonDurations(other)

        val newMetric: SpawnMetrics? = builder.build()

        var newRemoteMetrics: SpawnMetrics? = remoteMetrics
        var newLocalMetrics: SpawnMetrics? = localMetrics
        var newWorkerMetrics: SpawnMetrics? = workerMetrics
        var newOtherMetrics: SpawnMetrics? = otherMetrics

        when (kind) {
            ExecKind.REMOTE -> newRemoteMetrics = newMetric
            ExecKind.LOCAL -> newLocalMetrics = newMetric
            ExecKind.WORKER -> newWorkerMetrics = newMetric
            ExecKind.OTHER -> newOtherMetrics = newMetric
        }

        return AggregatedSpawnMetrics(
            newRemoteMetrics, newLocalMetrics, newWorkerMetrics, newOtherMetrics
        )
    }

    /**
     * Returns the total duration across all execution kinds.
     * 
     * 
     * Example: `getTotalDuration(SpawnMetrics::queueTime)` will give the total queue time
     * across all execution kinds.
     */
    fun getTotalDuration(extract: java.util.function.Function<SpawnMetrics?, Int?>): Int {
        var result = 0
        if (remoteMetrics != null) {
            result += extract.apply(remoteMetrics)
        }
        if (localMetrics != null) {
            result += extract.apply(localMetrics)
        }
        if (workerMetrics != null) {
            result += extract.apply(workerMetrics)
        }
        if (otherMetrics != null) {
            result += extract.apply(otherMetrics)
        }
        return result
    }

    /**
     * Returns the maximum value of a non-duration metric across all execution kinds.
     * 
     * 
     * Example: `getMaxNonDuration(0, SpawnMetrics::inputFiles)` returns the maximum number
     * of input files across all the execution kinds.
     */
    fun getMaxNonDuration(initialValue: Long, extract: java.util.function.ToLongFunction<SpawnMetrics?>): Long {
        var result = initialValue
        if (remoteMetrics != null) {
            result = java.lang.Long.max(result, extract.applyAsLong(remoteMetrics))
        }
        if (localMetrics != null) {
            result = java.lang.Long.max(result, extract.applyAsLong(localMetrics))
        }
        if (workerMetrics != null) {
            result = java.lang.Long.max(result, extract.applyAsLong(workerMetrics))
        }
        if (otherMetrics != null) {
            result = java.lang.Long.max(result, extract.applyAsLong(otherMetrics))
        }
        return result
    }

    fun toString(total: java.time.Duration, summary: Boolean): String {
        // For now keep compatibility with the old output and only report the remote execution.
        // TODO(michalt): Change this once the local and worker executions populate more metrics.
        return (ExecKind.REMOTE
            .toString() + " "
                + getRemoteMetrics().toString(total.toMillis().toInt(), summary))
    }

    /** Builder for [AggregatedSpawnMetrics].  */
    class Builder {
        private var remoteMetricsBuilder: com.google.devtools.build.lib.actions.SpawnMetrics.Builder? = null
        private var localMetricsBuilder: com.google.devtools.build.lib.actions.SpawnMetrics.Builder? = null
        private var workerMetricsBuilder: com.google.devtools.build.lib.actions.SpawnMetrics.Builder? = null
        private var otherMetricsBuilder: com.google.devtools.build.lib.actions.SpawnMetrics.Builder? = null

        fun build(): AggregatedSpawnMetrics {
            return AggregatedSpawnMetrics(
                if (remoteMetricsBuilder != null) remoteMetricsBuilder.build() else null,
                if (localMetricsBuilder != null) localMetricsBuilder.build() else null,
                if (workerMetricsBuilder != null) workerMetricsBuilder.build() else null,
                if (otherMetricsBuilder != null) otherMetricsBuilder.build() else null
            )
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addDurations(metrics: SpawnMetrics): Builder {
            getBuilder(metrics.execKind()).addDurations(metrics)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addDurations(aggregated: AggregatedSpawnMetrics): Builder {
            aggregated.getAllMetrics()
                .forEach(java.util.function.Consumer { metrics: SpawnMetrics? -> this.addDurations(metrics) })
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addNonDurations(metrics: SpawnMetrics): Builder {
            getBuilder(metrics.execKind()).addNonDurations(metrics)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addNonDurations(aggregated: AggregatedSpawnMetrics): Builder {
            aggregated.getAllMetrics()
                .forEach(java.util.function.Consumer { metrics: SpawnMetrics? -> this.addNonDurations(metrics) })
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun maxNonDurations(metrics: SpawnMetrics): Builder {
            getBuilder(metrics.execKind()).maxNonDurations(metrics)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun maxNonDurations(aggregated: AggregatedSpawnMetrics): Builder {
            aggregated.getAllMetrics()
                .forEach(java.util.function.Consumer { metrics: SpawnMetrics? -> this.maxNonDurations(metrics) })
            return this
        }

        private fun getBuilder(kind: ExecKind): com.google.devtools.build.lib.actions.SpawnMetrics.Builder? {
            when (kind) {
                ExecKind.REMOTE -> {
                    if (remoteMetricsBuilder == null) {
                        remoteMetricsBuilder =
                            com.google.devtools.build.lib.actions.SpawnMetrics.Builder.Companion.forRemoteExec()
                    }
                    return remoteMetricsBuilder
                }

                ExecKind.LOCAL -> {
                    if (localMetricsBuilder == null) {
                        localMetricsBuilder =
                            com.google.devtools.build.lib.actions.SpawnMetrics.Builder.Companion.forLocalExec()
                    }
                    return localMetricsBuilder
                }

                ExecKind.WORKER -> {
                    if (workerMetricsBuilder == null) {
                        workerMetricsBuilder =
                            com.google.devtools.build.lib.actions.SpawnMetrics.Builder.Companion.forWorkerExec()
                    }
                    return workerMetricsBuilder
                }

                ExecKind.OTHER -> {
                    if (otherMetricsBuilder == null) {
                        otherMetricsBuilder =
                            com.google.devtools.build.lib.actions.SpawnMetrics.Builder.Companion.forOtherExec()
                    }
                    return otherMetricsBuilder
                }
            }
            throw java.lang.IllegalArgumentException("Unknown ExecKind: " + kind)
        }
    }

    companion object {
        val EMPTY: AggregatedSpawnMetrics = AggregatedSpawnMetrics(null, null, null, null)
    }
}
