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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.ArtifactMetrics

/**
 * Event signaling the end of the execution phase. Contains statistics about the action cache, the
 * metadata cache and about last file save times.
 */
class ExecutionFinishedEvent(
    outputDirtyFiles: Int,
    outputDirtyFileExecPathSample: com.google.common.collect.ImmutableList<String?>?,
    outputModifiedFilesDuringPreviousBuild: Int,
    sourceDiffCheckingDuration: java.time.Duration?,
    numSourceFilesCheckedBecauseOfMissingDiffs: Int,
    outputTreeDiffCheckingDuration: java.time.Duration?,
    sourceArtifactsRead: ArtifactMetrics.FilesMetric?,
    outputArtifactsSeen: ArtifactMetrics.FilesMetric?,
    outputArtifactsFromActionCache: ArtifactMetrics.FilesMetric?,
    topLevelArtifacts: ArtifactMetrics.FilesMetric?
) {
    @AutoBuilder
    internal abstract class Builder {
        abstract fun setOutputDirtyFiles(outputDirtyFiles: Int): Builder?

        abstract fun setOutputDirtyFileExecPathSample(
            outputDirtyFileExecPathSample: com.google.common.collect.ImmutableList<String?>?
        ): Builder?

        abstract fun setOutputModifiedFilesDuringPreviousBuild(
            outputModifiedFilesDuringPreviousBuild: Int
        ): Builder?

        abstract fun setSourceDiffCheckingDuration(sourceDiffCheckingDuration: java.time.Duration?): Builder?

        abstract fun setNumSourceFilesCheckedBecauseOfMissingDiffs(
            numSourceFilesCheckedBecauseOfMissingDiffs: Int
        ): Builder?

        abstract fun setOutputTreeDiffCheckingDuration(outputTreeDiffCheckingDuration: java.time.Duration?): Builder?

        abstract fun setSourceArtifactsRead(value: ArtifactMetrics.FilesMetric?): Builder?

        abstract fun setOutputArtifactsSeen(value: ArtifactMetrics.FilesMetric?): Builder?

        abstract fun setOutputArtifactsFromActionCache(value: ArtifactMetrics.FilesMetric?): Builder?

        abstract fun setTopLevelArtifacts(value: ArtifactMetrics.FilesMetric?): Builder?

        abstract fun build(): ExecutionFinishedEvent?
    }

    val outputDirtyFiles: Int
    val outputDirtyFileExecPathSample: com.google.common.collect.ImmutableList<String?>?
    val outputModifiedFilesDuringPreviousBuild: Int
    val sourceDiffCheckingDuration: java.time.Duration?
    val numSourceFilesCheckedBecauseOfMissingDiffs: Int
    val outputTreeDiffCheckingDuration: java.time.Duration?
    val sourceArtifactsRead: ArtifactMetrics.FilesMetric?
    val outputArtifactsSeen: ArtifactMetrics.FilesMetric?
    val outputArtifactsFromActionCache: ArtifactMetrics.FilesMetric?
    val topLevelArtifacts: ArtifactMetrics.FilesMetric?

    init {
        this.topLevelArtifacts = topLevelArtifacts
        this.outputArtifactsFromActionCache = outputArtifactsFromActionCache
        this.outputArtifactsSeen = outputArtifactsSeen
        this.sourceArtifactsRead = sourceArtifactsRead
        this.outputTreeDiffCheckingDuration = outputTreeDiffCheckingDuration
        this.numSourceFilesCheckedBecauseOfMissingDiffs = numSourceFilesCheckedBecauseOfMissingDiffs
        this.sourceDiffCheckingDuration = sourceDiffCheckingDuration
        this.outputModifiedFilesDuringPreviousBuild = outputModifiedFilesDuringPreviousBuild
        this.outputDirtyFileExecPathSample = outputDirtyFileExecPathSample
        this.outputDirtyFiles = outputDirtyFiles
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableList<String?>?>(
            outputDirtyFileExecPathSample,
            "outputDirtyFileExecPathSample"
        )
        java.util.Objects.requireNonNull<java.time.Duration?>(sourceDiffCheckingDuration, "sourceDiffCheckingDuration")
        java.util.Objects.requireNonNull<java.time.Duration?>(
            outputTreeDiffCheckingDuration,
            "outputTreeDiffCheckingDuration"
        )
        java.util.Objects.requireNonNull<Any?>(sourceArtifactsRead, "sourceArtifactsRead")
        java.util.Objects.requireNonNull<Any?>(outputArtifactsSeen, "outputArtifactsSeen")
        java.util.Objects.requireNonNull<Any?>(outputArtifactsFromActionCache, "outputArtifactsFromActionCache")
        java.util.Objects.requireNonNull<Any?>(topLevelArtifacts, "topLevelArtifacts")
    }

    companion object {
        // AutoValue Builders require that all fields are populated, so we provide a default.
        @kotlin.jvm.JvmStatic
        fun builderWithDefaults(): Builder? {
            val emptyFilesMetric: ArtifactMetrics.FilesMetric? = ArtifactMetrics.FilesMetric.getDefaultInstance()
            return builder()
                .setOutputDirtyFiles(0)!!
                .setOutputDirtyFileExecPathSample(com.google.common.collect.ImmutableList.of<kotlin.String?>())!!
                .setOutputModifiedFilesDuringPreviousBuild(0)!!
                .setSourceDiffCheckingDuration(java.time.Duration.ZERO)!!
                .setNumSourceFilesCheckedBecauseOfMissingDiffs(0)!!
                .setOutputTreeDiffCheckingDuration(java.time.Duration.ZERO)!!
                .setSourceArtifactsRead(emptyFilesMetric)!!
                .setOutputArtifactsSeen(emptyFilesMetric)!!
                .setOutputArtifactsFromActionCache(emptyFilesMetric)!!
                .setTopLevelArtifacts(emptyFilesMetric)
        }

        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return AutoBuilder_ExecutionFinishedEvent_Builder()
        }
    }
}
