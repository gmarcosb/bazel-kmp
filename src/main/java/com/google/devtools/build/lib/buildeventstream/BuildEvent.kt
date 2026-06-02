// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.buildeventstream

import com.google.devtools.build.lib.actions.FileArtifactValue.RUNFILES_TREE_MARKER

/**
 * Interface for objects that can be posted on the public event stream.
 * 
 * 
 * Objects posted on the build-event stream will implement this interface. This allows
 * pass-through of events, as well as proper chaining of events.
 */
interface BuildEvent : ChainableEvent, com.google.devtools.build.lib.events.ExtendedEventHandler.Postable {
    /**
     * A local file that is referenced by the build event. These can be uploaded to a separate backend
     * storage.
     * 
     * 
     * Despite the name, it is possible that a `LocalFile` is already stored remotely. If
     * [.artifactMetadata] [FileArtifactValue.isRemote], the upload may be skipped.
     */
    class LocalFile(
        path: com.google.devtools.build.lib.vfs.Path?,
        type: LocalFileType?,
        compression: LocalFileCompression?,
        artifactMetadata: FileArtifactValue?
    ) {
        /**
         * The type of the local file. This is used by uploaders to determine how long to store the
         * associated files for.
         */
        enum class LocalFileType {
            OUTPUT_FILE,
            OUTPUT_DIRECTORY,
            OUTPUT_SYMLINK,
            SUCCESSFUL_TEST_OUTPUT,
            FAILED_TEST_OUTPUT,
            COVERAGE_OUTPUT,
            QUERY_OUTPUT,
            STDOUT,
            STDERR,
            LOG,
            PERFORMANCE_LOG;

            val isOutput: Boolean
                /** Returns whether the LocalFile is a declared action output.  */
                get() = this == LocalFileType.OUTPUT_FILE || this == LocalFileType.OUTPUT_DIRECTORY || this == LocalFileType.OUTPUT_SYMLINK

            companion object {
                /**
                 * Returns the [LocalFileType] implied by a [FileArtifactValue], or the associated
                 * [Artifact] if metadata is not available.
                 */
                fun forArtifact(
                    artifact: Artifact, metadata: FileArtifactValue?
                ): LocalFileType {
                    if (metadata != null) {
                        if (metadata.equals(RUNFILES_TREE_MARKER)) {
                            // TODO(tjgq): Remove RUNFILES_TREE_MARKER in favor of RunfilesProxyArtifactValue,
                            // which would make this special case unnecessary.
                            return LocalFileType.OUTPUT_DIRECTORY
                        }
                        return when (metadata.getType()) {
                            DIRECTORY -> LocalFileType.OUTPUT_DIRECTORY
                            SYMLINK -> LocalFileType.OUTPUT_SYMLINK
                            else -> LocalFileType.OUTPUT_FILE
                        }
                    }
                    if (artifact.isDirectory()) {
                        return LocalFileType.OUTPUT_DIRECTORY
                    } else if (artifact.isSymlink()) {
                        return LocalFileType.OUTPUT_SYMLINK
                    }
                    return LocalFileType.OUTPUT_FILE
                }
            }
        }

        /** Indicates the type of compression the local file should have.  */
        enum class LocalFileCompression {
            NONE,
            GZIP,
        }

        @kotlin.jvm.JvmField
        val path: com.google.devtools.build.lib.vfs.Path
        @kotlin.jvm.JvmField
        val type: LocalFileType
        val compression: LocalFileCompression
        val artifactMetadata: FileArtifactValue?

        constructor(
            path: com.google.devtools.build.lib.vfs.Path?,
            type: LocalFileType?,
            artifactMetadata: FileArtifactValue?
        ) : this(path, type, LocalFileCompression.NONE, artifactMetadata)

        init {
            this.path = com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path>(path)
            this.type = com.google.common.base.Preconditions.checkNotNull<LocalFileType>(type)
            this.compression = com.google.common.base.Preconditions.checkNotNull<LocalFileCompression>(compression)
            this.artifactMetadata = artifactMetadata
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is LocalFile) {
                return false
            }
            return path == o.path
                    && type == o.type && compression == o.compression && com.google.common.base.Objects.equal(
                artifactMetadata,
                o.artifactMetadata
            )
        }

        override fun hashCode(): Int {
            return HashCodes.hashObjects(path, type, compression, artifactMetadata)
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("path", path)
                .add("type", type)
                .add("compression", compression)
                .add("artifactMetadata", artifactMetadata)
                .toString()
        }
    }

    /**
     * Returns a list of files that are referenced in the protobuf representation returned by [ ][.asStreamProto].
     * 
     * 
     * This method is different from `EventReportingArtifacts#reportedArtifacts()` in that it
     * only returns files directly referenced in the protobuf returned by [ ][.asStreamProto].
     * 
     * 
     * Note the consistency requirement - you must not attempt to pass Path objects to the [ ] unless you have returned a corresponding [LocalFile] object here.
     */
    fun referencedLocalFiles(): MutableCollection<LocalFile?>? {
        return com.google.common.collect.ImmutableList.of<LocalFile?>()
    }

    /**
     * Returns a collection of URI futures corresponding to in-flight file uploads.
     * 
     * 
     * The files here are considered "remote" in that they may not correspond to on-disk files.
     */
    fun remoteUploads(): MutableCollection<com.google.common.util.concurrent.ListenableFuture<String?>?>? {
        return com.google.common.collect.ImmutableList.of<com.google.common.util.concurrent.ListenableFuture<String?>?>()
    }

    /**
     * Provide a binary representation of the event.
     * 
     * 
     * Provide a presentation of the event according to the specified binary format, as appropriate
     * protocol buffer.
     */
    @Throws(java.lang.InterruptedException::class)
    fun asStreamProto(context: BuildEventContext?): BuildEvent?
}
