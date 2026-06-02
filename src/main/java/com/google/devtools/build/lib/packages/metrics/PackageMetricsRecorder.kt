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
package com.google.devtools.build.lib.packages.metrics

import com.google.common.collect.ImmutableCollection
import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.BzlMetrics
import java.util.function.Function

/** Interface encapsulating the strategy used for recording Package Metrics.  */
interface PackageMetricsRecorder {
    /** What type of packages are metrics being recorded for?  */
    enum class Type {
        ONLY_EXTREMES,
        ALL,
    }

    /** Records the metrics for a given package.  */
    fun recordMetrics(pkgId: PackageIdentifier?, metrics: PackageLoadMetrics?)

    /** Records the metrics for a single bzl file.  */
    fun recordBzlMetrics(metrics: BzlFileMetrics?)

    /**
     * Returns a `Map<PackageIdentifier, Duration>` of recorded load durations. This may contain
     * only a subset of all packages loaded based on the implementation.
     */
    fun getLoadTimes(): MutableMap<PackageIdentifier?, Duration?>?

    /**
     * Returns a `Map<PackageIdentifier, Long>` of glob costs. This may contain only a subset of
     * all packages loaded based on the implementation.
     */
    fun getGlobFilesystemOperationCost(): MutableMap<PackageIdentifier?, Long?>?

    /**
     * Returns a `Map<PackageIdentifier, Long>` of computation steps. This may contain only a
     * subset of all packages loaded based on the implementation.
     */
    fun getComputationSteps(): MutableMap<PackageIdentifier?, Long?>?

    /**
     * Returns a `Map<PackageIdentifier, Long>` of num targets. This may contain only a subset
     * of all packages loaded based on the implementation.
     */
    fun getNumTargets(): MutableMap<PackageIdentifier?, Long?>?

    /**
     * Returns a `Map<PackageIdentifier, Long>` of num targets. This may contain only a subset
     * of all packages loaded based on the implementation.
     */
    fun getNumTransitiveLoads(): MutableMap<PackageIdentifier?, Long?>?

    /** Returns map of package overhead. This may contain only a subset of all packages loaded.  */
    fun getPackageOverhead(): MutableMap<PackageIdentifier?, Long?>?

    /** Clears the contents of the PackageMetricsRecorder.  */
    fun clear()

    /**
     * Called after package loading is complete to allow handlers to perform post-loading phase
     * processing.
     */
    fun loadingFinished()

    /** Returns the type of package metrics being recorded.  */
    fun getRecorderType(): Type?

    /** If Type is ALL returns metrics for all Packages loaded.  */
    fun getPackageLoadMetrics(): MutableCollection<PackageLoadMetrics?>

    /** Returns recorded bzl metrics.  */
    fun getBzlMetrics(): BzlMetrics?

    /* TODO(twerth): Remove method after migration is complete. */
    fun getPackageMetrics(): ImmutableCollection<PackageMetrics?> {
        val packageLoadMetrics: MutableCollection<PackageLoadMetrics?> = getPackageLoadMetrics()
        return packageLoadMetrics.stream()
            .map<Any?>(
                Function { plm: PackageLoadMetrics? ->
                    PackageMetrics.newBuilder()
                        .setName(plm.getName())
                        .setPackageOverhead(plm.getPackageOverhead())
                        .setComputationSteps(plm.getComputationSteps())
                        .setLoadDuration(plm.getLoadDuration())
                        .setNumTargets(plm.getNumTargets())
                        .setNumTransitiveLoads(plm.getNumTransitiveLoads())
                        .setGlobFilesystemOperationCost(plm.getGlobFilesystemOperationCost())
                        .build()
                })
            .collect(ImmutableList.toImmutableList<Any?>())
    }
}
