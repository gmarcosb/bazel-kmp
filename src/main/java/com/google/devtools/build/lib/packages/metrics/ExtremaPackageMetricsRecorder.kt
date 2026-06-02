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

import com.google.common.base.Joiner
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Streams
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.BzlMetrics
import com.google.devtools.build.lib.util.StringUtilities
import java.lang.String
import java.util.function.Function
import java.util.function.Supplier
import javax.annotation.concurrent.GuardedBy
import kotlin.Comparator
import kotlin.Int
import kotlin.Long

/** Tracks per-invocation extreme package loading events.  */
class ExtremaPackageMetricsRecorder internal constructor(currentNumPackagesToTrack: Int) : PackageMetricsRecorder {
    @kotlin.jvm.JvmField
    private val currentNumPackagesToTrack: Int

    @GuardedBy("this")
    private val slowestPackagesToLoad: Extrema<PackageLoadMetricsContainer?>

    @GuardedBy("this")
    private val packagesWithMostGlobFilesystemOperationCost: Extrema<PackageLoadMetricsContainer?>

    @GuardedBy("this")
    private val largestPackages: Extrema<PackageLoadMetricsContainer?>

    @GuardedBy("this")
    private val packagesWithMostTransitiveLoads: Extrema<PackageLoadMetricsContainer?>

    @GuardedBy("this")
    private val packagesWithMostComputationSteps: Extrema<PackageLoadMetricsContainer?>

    @GuardedBy("this")
    private val packagesWithMostOverhead: Extrema<PackageLoadMetricsContainer?>

    @GuardedBy("this")
    private val largestBzlFiles: Extrema<BzlFileMetrics?>

    @GuardedBy("this")
    private var bzlFileCount = 0

    init {
        Preconditions.checkArgument(currentNumPackagesToTrack >= 0, "num packages must be >= 0")
        this.currentNumPackagesToTrack = currentNumPackagesToTrack
        this.slowestPackagesToLoad =
            Extrema.max(currentNumPackagesToTrack, PackageLoadMetricsContainer.Companion.LOAD_TIMES_COMP)
        this.packagesWithMostGlobFilesystemOperationCost =
            Extrema.max(
                currentNumPackagesToTrack,
                PackageLoadMetricsContainer.Companion.GLOB_FILESYSTEM_OPERATION_COST_COMP
            )
        this.largestPackages =
            Extrema.max(currentNumPackagesToTrack, PackageLoadMetricsContainer.Companion.NUM_TARGETS_COMP)
        this.packagesWithMostTransitiveLoads =
            Extrema.max(currentNumPackagesToTrack, PackageLoadMetricsContainer.Companion.TRANSITIVE_LOADS_COMP)
        this.packagesWithMostComputationSteps =
            Extrema.max(currentNumPackagesToTrack, PackageLoadMetricsContainer.Companion.COMPUTATION_STEPS_COMP)
        this.packagesWithMostOverhead =
            Extrema.max(currentNumPackagesToTrack, PackageLoadMetricsContainer.Companion.OVERHEAD_COMP)
        // Bzl files aren't really packages, but it's not worth having a separate flag.
        this.largestBzlFiles =
            Extrema.max(currentNumPackagesToTrack, Comparator.comparingLong<T?>(BzlFileMetrics::getSize))
    }

    fun getNumPackagesToTrack(): Int {
        return currentNumPackagesToTrack
    }

    @kotlin.jvm.Synchronized
    override fun recordMetrics(pkgId: PackageIdentifier?, metrics: PackageLoadMetrics) {
        val cont: PackageLoadMetricsContainer = PackageLoadMetricsContainer.Companion.create(pkgId, metrics)
        slowestPackagesToLoad.aggregate(cont)
        packagesWithMostGlobFilesystemOperationCost.aggregate(cont)
        packagesWithMostComputationSteps.aggregate(cont)
        largestPackages.aggregate(cont)
        packagesWithMostTransitiveLoads.aggregate(cont)
        if (metrics.hasPackageOverhead()) {
            packagesWithMostOverhead.aggregate(cont)
        }
    }

    @kotlin.jvm.Synchronized
    override fun recordBzlMetrics(metrics: BzlFileMetrics?) {
        bzlFileCount++
        largestBzlFiles.aggregate(metrics)
    }

    @kotlin.jvm.Synchronized
    override fun getLoadTimes(): MutableMap<PackageIdentifier?, Duration?> {
        return slowestPackagesToLoad.extremeElements.stream()
            .collect(
                Collectors.toMap(
                    Function { obj: T? -> obj.getPackageIdentifier() },
                    Function { v: T? -> v.getPackageLoadMetricsInternal().getLoadDuration() },
                    BinaryOperator { k: U?, v: U? -> v },
                    Supplier { LinkedHashMap() })
            ) // use a LinkedHashMap to ensure iteration order is maintained
    }

    @kotlin.jvm.Synchronized
    override fun getGlobFilesystemOperationCost(): MutableMap<PackageIdentifier?, Long?> {
        return toMap(
            packagesWithMostGlobFilesystemOperationCost,
            PackageLoadMetrics::getGlobFilesystemOperationCost
        )
    }

    @kotlin.jvm.Synchronized
    override fun getComputationSteps(): MutableMap<PackageIdentifier?, Long?> {
        return toMap(packagesWithMostComputationSteps, PackageLoadMetrics::getComputationSteps)
    }

    @kotlin.jvm.Synchronized
    override fun getNumTargets(): MutableMap<PackageIdentifier?, Long?> {
        return toMap(largestPackages, PackageLoadMetrics::getNumTargets)
    }

    @kotlin.jvm.Synchronized
    override fun getNumTransitiveLoads(): MutableMap<PackageIdentifier?, Long?> {
        return toMap(packagesWithMostTransitiveLoads, PackageLoadMetrics::getNumTransitiveLoads)
    }

    @kotlin.jvm.Synchronized
    override fun getPackageOverhead(): MutableMap<PackageIdentifier?, Long?> {
        return toMap(packagesWithMostOverhead, PackageLoadMetrics::getPackageOverhead)
    }

    @kotlin.jvm.Synchronized
    private fun toMap(
        ext: Extrema<PackageLoadMetricsContainer?>, fn: Function<PackageLoadMetrics?, Long?>
    ): MutableMap<PackageIdentifier?, Long?> {
        return ext.extremeElements.stream()
            .collect(
                Collectors.toMap(
                    Function { obj: T? -> obj.getPackageIdentifier() },
                    Function { v: T? -> fn.apply(v.getPackageLoadMetricsInternal()) },
                    BinaryOperator { k: U?, v: U? -> v },
                    Supplier { LinkedHashMap() })
            ) // use a LinkedHashMap to ensure iteration order is maintained
    }

    @kotlin.jvm.Synchronized
    override fun clear() {
        slowestPackagesToLoad.clear()
        packagesWithMostGlobFilesystemOperationCost.clear()
        packagesWithMostComputationSteps.clear()
        largestPackages.clear()
        packagesWithMostTransitiveLoads.clear()
        packagesWithMostOverhead.clear()
        largestBzlFiles.clear()
        bzlFileCount = 0
    }

    @kotlin.jvm.Synchronized
    override fun loadingFinished() {
        logIfNonEmpty(
            "Slowest packages (ms)",
            slowestPackagesToLoad.extremeElements,
            Function { c: PackageLoadMetricsContainer? ->
                Durations.toMillis(
                    c!!.getPackageLoadMetricsInternal().getLoadDuration()
                )
            })
        logIfNonEmpty(
            "Packages with highest glob filesystem operation cost",
            packagesWithMostGlobFilesystemOperationCost.extremeElements,
            Function { c: PackageLoadMetricsContainer? ->
                c!!.getPackageLoadMetricsInternal().getGlobFilesystemOperationCost()
            })
        logIfNonEmpty(
            "Largest packages (num targets)",
            largestPackages.extremeElements,
            Function { c: PackageLoadMetricsContainer? -> c!!.getPackageLoadMetricsInternal().getNumTargets() })
        logIfNonEmpty(
            "Packages with most computation steps",
            packagesWithMostComputationSteps.extremeElements,
            Function { c: PackageLoadMetricsContainer? -> c!!.getPackageLoadMetricsInternal().getComputationSteps() })
        logIfNonEmpty(
            "Packages with most transitive loads (num bzl files)",
            packagesWithMostTransitiveLoads.extremeElements,
            Function { c: PackageLoadMetricsContainer? -> c!!.getPackageLoadMetricsInternal().getNumTransitiveLoads() })
        logIfNonEmpty(
            "Packages with most overhead",
            packagesWithMostOverhead.extremeElements,
            Function { c: PackageLoadMetricsContainer? -> c!!.getPackageLoadMetricsInternal().getPackageOverhead() })
        if (!largestBzlFiles.isEmpty) {
            logger.atInfo().log(
                "Largest bzl files: %s",
                largestBzlFiles.extremeElements.stream()
                    .map({ f -> String.format("%s (%s)", f.getPath(), StringUtilities.prettyPrintBytes(f.getSize())) })
                    .collect(Collectors.joining(", "))
            )
        }
        clear()
    }

    override fun getRecorderType(): PackageMetricsRecorder.Type {
        return PackageMetricsRecorder.Type.ONLY_EXTREMES
    }

    @kotlin.jvm.Synchronized
    override fun getPackageLoadMetrics(): MutableCollection<PackageLoadMetrics?> {
        return Streams.concat(
            slowestPackagesToLoad.extremeElements.stream(),
            packagesWithMostGlobFilesystemOperationCost.extremeElements.stream(),
            packagesWithMostComputationSteps.extremeElements.stream(),
            largestPackages.extremeElements.stream(),
            packagesWithMostTransitiveLoads.extremeElements.stream(),
            packagesWithMostOverhead.extremeElements.stream()
        )
            .map({ obj: PackageLoadMetricsContainer? -> obj!!.getPackageLoadMetrics() })
            .collect(ImmutableSet.toImmutableSet<E?>())
    }

    @kotlin.jvm.Synchronized
    override fun getBzlMetrics(): BzlMetrics {
        return BzlMetrics.newBuilder()
            .setBzlFileCount(bzlFileCount)
            .addAllBzlFileMetrics(largestBzlFiles.extremeElements)
            .build()
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private fun logIfNonEmpty(
            logLinePrefix: kotlin.String?,
            extremeElements: MutableList<PackageLoadMetricsContainer?>,
            valueMapper: Function<PackageLoadMetricsContainer?, Long?>
        ) {
            val logString: MutableList<kotlin.String?> =
                extremeElements.stream()
                    .map<kotlin.String?>(Function { v: PackageLoadMetricsContainer? ->
                        String.format(
                            "%s (%d)",
                            v!!.getPackageIdentifier(),
                            valueMapper.apply(v)
                        )
                    })
                    .collect(ImmutableList.toImmutableList<kotlin.String?>())
            if (!extremeElements.isEmpty()) {
                logger.atInfo().log("%s: %s", logLinePrefix, Joiner.on(", ").join(logString))
            }
        }
    }
}
