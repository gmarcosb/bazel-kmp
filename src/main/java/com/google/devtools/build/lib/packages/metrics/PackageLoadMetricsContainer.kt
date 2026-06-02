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

import com.google.devtools.build.lib.cmdline.PackageIdentifier
import java.util.function.Function
import java.util.function.ToLongFunction
import kotlin.Any
import kotlin.Comparator
import kotlin.toString

/** Container class holding a PackageIdentifier and PackageMetrics proto.  */
@AutoValue
abstract class PackageLoadMetricsContainer {
    abstract fun getPackageIdentifier(): PackageIdentifier?

    abstract fun getPackageLoadMetricsInternal(): PackageLoadMetrics?

    /** Construct a full PackageMetrics object with the name set lazily from the PackageIdentifier.  */
    fun getPackageLoadMetrics(): PackageLoadMetrics {
        return getPackageLoadMetricsInternal().toBuilder()
            .setName(getPackageIdentifier().toString())
            .build()
    }

    companion object {
        /** Sorts by LoadTime Duration.  */
        val LOAD_TIMES_COMP: Comparator<PackageLoadMetricsContainer?>? = Comparator.comparing<T?, U?>(
            Function { c: T? -> c.getPackageLoadMetricsInternal().getLoadDuration() }, Durations.comparator()
        )

        /** Sorts by Glob Filesystem Operation Cost.  */
        val GLOB_FILESYSTEM_OPERATION_COST_COMP: Comparator<PackageLoadMetricsContainer?>? =
            Comparator.comparing<PackageLoadMetricsContainer?, Any?>(
                Function { c: PackageLoadMetricsContainer? ->
                    c!!.getPackageLoadMetricsInternal().getGlobFilesystemOperationCost()
                })

        /** Sorts by Num Target count .  */
        val NUM_TARGETS_COMP: Comparator<PackageLoadMetricsContainer?>? =
            Comparator.comparingLong<PackageLoadMetricsContainer?>(
                ToLongFunction { c: PackageLoadMetricsContainer? ->
                    c!!.getPackageLoadMetricsInternal().getNumTargets()
                })

        /** Sorts by Computation Steps count.  */
        val COMPUTATION_STEPS_COMP: Comparator<PackageLoadMetricsContainer?>? =
            Comparator.comparingLong<PackageLoadMetricsContainer?>(
                ToLongFunction { c: PackageLoadMetricsContainer? ->
                    c!!.getPackageLoadMetricsInternal().getComputationSteps()
                })

        /** Sorts by Transitive Load Count.  */
        val TRANSITIVE_LOADS_COMP: Comparator<PackageLoadMetricsContainer?>? =
            Comparator.comparingLong<PackageLoadMetricsContainer?>(
                ToLongFunction { c: PackageLoadMetricsContainer? ->
                    c!!.getPackageLoadMetricsInternal().getNumTransitiveLoads()
                })

        /** Sorts by Package Overhead.  */
        val OVERHEAD_COMP: Comparator<PackageLoadMetricsContainer?>? =
            Comparator.comparingLong<PackageLoadMetricsContainer?>(
                ToLongFunction { c: PackageLoadMetricsContainer? ->
                    c!!.getPackageLoadMetricsInternal().getPackageOverhead()
                })

        fun create(
            pkgId: PackageIdentifier?, metrics: PackageLoadMetrics?
        ): PackageLoadMetricsContainer {
            return AutoValue_PackageLoadMetricsContainer(pkgId, metrics)
        }
    }
}
