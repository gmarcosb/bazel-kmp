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
import com.google.common.collect.Maps
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.BzlMetrics
import java.util.function.Function
import javax.annotation.concurrent.GuardedBy
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.collections.MutableMap

/** PackageMetricsRecorder that records all available metrics for all package loads.  */
internal class CompletePackageMetricsRecorder : PackageMetricsRecorder {
    @GuardedBy("this")
    private val metrics: HashMap<PackageIdentifier?, PackageLoadMetrics?> =
        HashMap<PackageIdentifier?, PackageLoadMetrics?>()

    @GuardedBy("this")
    private val bzlMetrics: MutableList<BzlFileMetrics?> = ArrayList<BzlFileMetrics?>()

    @kotlin.jvm.Synchronized
    override fun recordMetrics(pkgId: PackageIdentifier?, metrics: PackageLoadMetrics?) {
        this.metrics.put(pkgId, metrics)
    }

    @kotlin.jvm.Synchronized
    override fun recordBzlMetrics(metrics: BzlFileMetrics?) {
        bzlMetrics.add(metrics)
    }

    @kotlin.jvm.Synchronized
    override fun getLoadTimes(): MutableMap<PackageIdentifier?, Duration?> {
        return Maps.transformValues(metrics, PackageLoadMetrics::getLoadDuration)
    }

    @kotlin.jvm.Synchronized
    override fun getGlobFilesystemOperationCost(): MutableMap<PackageIdentifier?, Long?> {
        return Maps.transformValues(metrics, PackageLoadMetrics::getGlobFilesystemOperationCost)
    }

    @kotlin.jvm.Synchronized
    override fun getComputationSteps(): MutableMap<PackageIdentifier?, Long?> {
        return Maps.transformValues(metrics, PackageLoadMetrics::getComputationSteps)
    }

    @kotlin.jvm.Synchronized
    override fun getNumTargets(): MutableMap<PackageIdentifier?, Long?> {
        return Maps.transformValues(metrics, PackageLoadMetrics::getNumTargets)
    }

    @kotlin.jvm.Synchronized
    override fun getNumTransitiveLoads(): MutableMap<PackageIdentifier?, Long?> {
        return Maps.transformValues(metrics, PackageLoadMetrics::getNumTransitiveLoads)
    }

    @kotlin.jvm.Synchronized
    override fun getPackageOverhead(): MutableMap<PackageIdentifier?, Long?> {
        return Maps.transformValues(
            Maps.filterValues(metrics, PackageLoadMetrics::hasPackageOverhead),
            PackageLoadMetrics::getPackageOverhead
        )
    }

    @kotlin.jvm.Synchronized
    override fun clear() {
        metrics.clear()
        bzlMetrics.clear()
    }

    override fun loadingFinished() {
        clear()
    }

    override fun getRecorderType(): PackageMetricsRecorder.Type {
        return PackageMetricsRecorder.Type.ALL
    }

    @kotlin.jvm.Synchronized
    override fun getPackageLoadMetrics(): ImmutableCollection<PackageLoadMetrics?> {
        // lazily set the pkgName when requested.
        return metrics.entrySet().stream()
            .map<Any?>(Function { e: MutableMap.MutableEntry<PackageIdentifier?, PackageLoadMetrics?>? ->
                e.getValue().toBuilder().setName(e.getKey().toString()).build()
            })
            .collect(ImmutableList.toImmutableList<Any?>())
    }

    @kotlin.jvm.Synchronized
    override fun getBzlMetrics(): BzlMetrics {
        return BzlMetrics.newBuilder()
            .setBzlFileCount(bzlMetrics.size())
            .addAllBzlFileMetrics(bzlMetrics)
            .build()
    }
}
