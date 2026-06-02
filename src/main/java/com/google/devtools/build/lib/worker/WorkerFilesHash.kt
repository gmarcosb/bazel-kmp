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
package com.google.devtools.build.lib.worker

import com.google.devtools.build.lib.actions.ActionInput

/**
 * Calculates the hash based on the files, which should be unchanged on disk for a worker to get
 * reused.
 */
object WorkerFilesHash {
    fun getCombinedHash(workerFilesMap: SortedMap<PathFragment?, ByteArray?>): com.google.common.hash.HashCode {
        val hasher: com.google.common.hash.Hasher = com.google.common.hash.Hashing.sha256().newHasher()
        workerFilesMap.forEach(
            java.util.function.BiConsumer { execPath: PathFragment?, digest: ByteArray? ->
                val execPathBytes: ByteArray = StringUnsafe.getInternalStringBytes(execPath.getPathString())
                hasher.putByte(0.toByte())
                hasher.putInt(execPathBytes.size)
                hasher.putBytes(execPathBytes)

                hasher.putInt(digest!!.size)
                hasher.putBytes(digest)
            })
        return hasher.hash()
    }

    /**
     * Return a map that contains the execroot relative path and hash of each tool and runfiles
     * artifact of the given spawn.
     * 
     * @throws MissingInputException if metadata is missing for any of the worker files.
     */
    @Throws(IOException::class)
    fun getWorkerFilesWithDigests(
        spawn: Spawn, actionInputFileCache: InputMetadataProvider
    ): SortedMap<PathFragment?, ByteArray?> {
        val workerFilesMap: TreeMap<PathFragment?, ByteArray?> = TreeMap<PathFragment?, ByteArray?>()

        val tools: MutableList<ActionInput> =
            InputMetadataProvider.expandArtifacts(
                actionInputFileCache,
                spawn.getToolFiles(),  /* keepEmptyTreeArtifacts= */
                false,  /* keepRunfilesTrees= */
                true
            )
        for (tool in tools) {
            if (tool is Artifact && tool.isRunfilesTree()) {
                val runfilesTree: RunfilesTree =
                    actionInputFileCache.getRunfilesMetadata(tool).getRunfilesTree()
                val root: PathFragment = runfilesTree.getExecPath()
                com.google.common.base.Preconditions.checkState(!root.isAbsolute(), root)
                for (mapping in runfilesTree.getMapping().entrySet()) {
                    val localArtifact: Artifact? = mapping.getValue()
                    if (localArtifact != null) {
                        val metadata: FileArtifactValue? = actionInputFileCache.getInputMetadata(localArtifact)
                        if (metadata == null) {
                            throw MissingInputException(localArtifact)
                        }
                        val digest: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                            metadata.getDigest()
                        if (digest != null) {
                            workerFilesMap.put(
                                spawn.getPathMapper().map(root.getRelative(mapping.getKey())),
                                metadata.getDigest()
                            )
                        } else {
                            // If BAZEL_TRACK_SOURCE_DIRECTORIES is explicitly disabled, the metadata may not have
                            // a digest.
                            com.google.common.base.Preconditions.checkState(metadata.getType() === FileStateType.DIRECTORY)
                        }
                    }
                }

                continue
            }

            val metadata: FileArtifactValue? = actionInputFileCache.getInputMetadata(tool)
            if (metadata == null) {
                throw MissingInputException(tool)
            }
            workerFilesMap.put(spawn.getPathMapper().map(tool.getExecPath()), metadata.getDigest())
        }

        return workerFilesMap
    }

    /** Exception thrown when the metadata for a tool/runfile is missing.  */
    class MissingInputException private constructor(input: ActionInput) : java.lang.RuntimeException(
        java.lang.String.format(
            "Missing input metadata for: '%s'",
            input.getExecPathString()
        )
    )
}
