// Copyright 2021 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.FileArtifactValue

/** Sink for file-related metadata to be used for metrics gathering.  */
@ThreadSafety.ThreadSafe
interface MetadataConsumerForMetrics {
    fun accumulate(metadata: FileArtifactValue?)

    fun accumulate(treeArtifactValue: TreeArtifactValue?)

    fun accumulate(filesetOutput: FilesetOutputTree?)

    /** Accumulates file metadata for later export to a [ArtifactMetrics.FilesMetric] object.  */
    class FilesMetricConsumer : MetadataConsumerForMetrics {
        private val size: AtomicLong = AtomicLong()
        private val count: AtomicInteger = AtomicInteger()

        override fun accumulate(metadata: FileArtifactValue) {
            // Exclude directories (might throw in future) and symlinks (duplicate data). In practice,
            // most symlinks' metadata is that of their target, so they still get duplicated.
            if (metadata.getType() === FileStateType.REGULAR_FILE) {
                size.addAndGet(metadata.getSize())
                count.incrementAndGet()
            }
        }

        override fun accumulate(treeArtifactValue: TreeArtifactValue) {
            val totalChildBytes: Long = treeArtifactValue.getTotalChildBytes()
            size.addAndGet(totalChildBytes)
            if (totalChildBytes > 0) {
                // Skip omitted/missing tree artifacts: they will throw here.
                count.addAndGet(treeArtifactValue.getChildren().size)
            }
        }

        override fun accumulate(filesetOutput: FilesetOutputTree) {
            // This is a bit of a fudge: we include the symlinks as a count, but don't count their
            // targets' sizes, because (a) plumbing the data is hard, (b) it would double-count symlinks
            // to output files, and (c) it's not even uniquely generated content for input files.
            count.addAndGet(filesetOutput.size())
        }

        @ThreadSafety.ThreadSafe
        fun mergeIn(otherConsumer: FilesMetricConsumer) {
            this.size.addAndGet(otherConsumer.size.get())
            this.count.addAndGet(otherConsumer.count.get())
        }

        fun toFilesMetricAndReset(): ArtifactMetrics.FilesMetric {
            return ArtifactMetrics.FilesMetric.newBuilder()
                .setSizeInBytes(size.getAndSet(0L))
                .setCount(count.getAndSet(0))
                .build()
        }

        fun reset() {
            size.set(0L)
            count.set(0)
        }
    }

    companion object {
        @kotlin.jvm.JvmField
        val NO_OP: MetadataConsumerForMetrics = object : MetadataConsumerForMetrics {
            override fun accumulate(metadata: FileArtifactValue?) {}

            override fun accumulate(treeArtifactValue: TreeArtifactValue?) {}

            override fun accumulate(filesetOutput: FilesetOutputTree?) {}
        }
    }
}
