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

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.BzlMetrics.BzlFileMetrics
import com.google.devtools.build.lib.packages.Package
import com.google.devtools.build.lib.packages.PackageLoadingListener
import net.starlark.java.eval.StarlarkSemantics
import javax.annotation.concurrent.GuardedBy

/** Tracks per-invocation extreme package loading events.  */
class PackageMetricsPackageLoadingListener private constructor() : PackageLoadingListener {
    @kotlin.jvm.JvmField
    @kotlin.concurrent.Volatile
    private var recorder: PackageMetricsRecorder? = null

    private var publishPackageMetricsInBep = false

    override fun onLoadingCompleteAndSuccessful(
        pkg: Package,
        starlarkSemantics: StarlarkSemantics?,
        lazyMacroExpansionPackages: LazyMacroExpansionPackages?,
        metrics: PackageLoadingListener.Metrics
    ) {
        val currentRecorder = recorder
        if (currentRecorder == null) {
            // Micro-optimization - no need to track.
            return
        }

        val builder: PackageLoadMetrics.Builder =
            PackageLoadMetrics.newBuilder()
                .setLoadDuration(Durations.fromNanos(metrics.loadTimeNanos))
                .setGlobFilesystemOperationCost(metrics.globFilesystemOperationCost)
                .setComputationSteps(pkg.getComputationSteps())
                .setNumTargets(pkg.getTargets().size())
                .setNumTransitiveLoads(pkg.getDeclarations().countTransitivelyLoadedStarlarkFiles())

        if (pkg.getPackageOverhead().isPresent()) {
            builder.setPackageOverhead(pkg.getPackageOverhead().getAsLong())
        }

        currentRecorder.recordMetrics(pkg.getPackageIdentifier(), builder.build())
    }

    override fun onBzlCompileCompleteAndSuccessful(path: RootedPath, fileSize: Long) {
        val currentRecorder = recorder
        if (currentRecorder == null) {
            return
        }

        currentRecorder.recordBzlMetrics(
            BzlFileMetrics.newBuilder()
                .setPath(path.getRootRelativePath().getPathString())
                .setSize(fileSize)
                .build()
        )
    }

    /** Set the PackageMetricsRecorder for this listener.  */
    fun setPackageMetricsRecorder(recorder: PackageMetricsRecorder?) {
        this.recorder = recorder
    }

    fun setPublishPackageMetricsInBep(publishPackageMetricsInBep: Boolean) {
        this.publishPackageMetricsInBep = publishPackageMetricsInBep
    }

    fun getPublishPackageMetricsInBep(): Boolean {
        return publishPackageMetricsInBep
    }

    /** Returns the PackageMetricsRecorder, if any, for the PackageLoadingListener.  */
    fun getPackageMetricsRecorder(): PackageMetricsRecorder? {
        return recorder
    }

    companion object {
        @kotlin.jvm.JvmField
        @GuardedBy("PackageMetricsPackageLoadingListener.class")
        private var instance: PackageMetricsPackageLoadingListener? = null

        @kotlin.jvm.Synchronized
        fun getInstance(): PackageMetricsPackageLoadingListener {
            if (instance == null) {
                instance = PackageMetricsPackageLoadingListener()
            }
            return instance!!
        }
    }
}
