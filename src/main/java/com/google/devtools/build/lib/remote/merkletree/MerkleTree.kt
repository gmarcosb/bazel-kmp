// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.merkletree

import build.bazel.remote.execution.v2.Digest
import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Function
import com.google.common.collect.Collections2
import com.google.common.collect.ImmutableSortedMap
import com.google.common.primitives.UnsignedBytes
import com.google.common.util.concurrent.ListenableFuture
import java.util.*
import java.util.Map

/**
 * A representation of the inputs to a remotely executed action represented as a Merkle tree.
 * 
 * 
 * Every tree has a digest, which is the digest of the tree's root directory. The subtrees and
 * the blobs they contain may have been discarded or never computed in the first place, for example,
 * because they have already been uploaded to the remote cache or because the tree is being built
 * only to check for a remote cache hit.
 */
interface MerkleTree {
    /** The digest of the tree's root directory.  */
    fun digest(): Digest?

    /** The total number of regular files and symlinks in this tree, including all subtrees.  */
    fun inputFiles(): Long

    /**
     * The total number of content bytes in this tree, including all subtrees. This includes both file
     * contents and the protos describing directories.
     */
    fun inputBytes(): Long

    /** Returns the root of this tree, which may be the current instance.  */
    fun root(): RootOnly?

    /**
     * A [MerkleTree] that doesn't retain any blobs, either because they have already been
     * uploaded or because only the root digest is needed (e.g., for a remote cache check).
     */
    interface RootOnly : MerkleTree {
        override fun root(): RootOnly {
            return this
        }

        /**
         * A [MerkleTree] that retains no blobs since all of them have recently been uploaded to
         * the remote cache.
         */
        class BlobsUploaded(digest: Digest?, inputFiles: Long, inputBytes: Long) : RootOnly {
            val digest: Digest?
            val inputFiles: Long
            val inputBytes: Long

            init {
                this.digest = digest
                this.inputFiles = inputFiles
                this.inputBytes = inputBytes
            }
        }

        /**
         * A [MerkleTree] that retains no blobs since they were discarded during the computation
         * (e.g., because they aren't needed for a remote cache check).
         */
        class BlobsDiscarded(digest: Digest?, inputFiles: Long, inputBytes: Long) : RootOnly {
            val digest: Digest?
            val inputFiles: Long
            val inputBytes: Long

            init {
                this.digest = digest
                this.inputFiles = inputFiles
                this.inputBytes = inputBytes
            }
        }
    }

    /**
     * A [MerkleTree] that retains all blobs that still need to be uploaded.
     * 
     * 
     * The empty blob doesn't have to be uploaded and is thus never included in the blobs map.
     * 
     * 
     * See [ ][com.google.devtools.build.lib.remote.RemoteExecutionServiceTest.buildRemoteAction_goldenTest]
     * for a test that verifies the memory footprint of this class. Since there can be thousands of
     * inflight remote executions that may have to retain their blobs until all inputs have been
     * uploaded, it's crucial to keep the memory footprint of this class as low as possible.
     */
    class Uploadable internal constructor(
        root: BlobsUploaded?,
        blobs: SortedMap<Any?, Any?>
    ) : MerkleTree {
        private val root: BlobsUploaded?
        private val blobs: ImmutableSortedMap<Any?, Any?>


        init {
            this.root = root
            // A sorted map requires less memory than a regular hash map as it only stores two flat sorted
            // arrays. Access performance is not critical since it's only used to find missing blobs,
            // which always require network access.
            this.blobs = ImmutableSortedMap.copyOfSorted<Any?, Any?>(blobs)
        }

        override fun digest(): Digest? {
            return root()!!.digest()
        }

        override fun inputFiles(): Long {
            return root()!!.inputFiles()
        }

        override fun inputBytes(): Long {
            return root()!!.inputBytes()
        }

        fun allDigests(): MutableCollection<Digest?> {
            return Collections2.transform<Any?, Digest?>(
                blobs.keySet(),
                Function { key: Any? -> Companion.adaptToDigest(key!!) })
        }

        @VisibleForTesting
        fun blobs(): MutableMap<Digest?, Any?> {
            return blobs.entrySet().stream()
                .collect(TODO("Cannot convert element")) < Map.Entry < Object
            TODO(
                """
                |Cannot convert element
                |With text:
                |Object>, Digest, Object>toImmutableMap(e -> adaptToDigest(e.getKey()), Map.Entry::getValue)
                """.trimMargin()
            )
        }

        override fun root(): RootOnly? {
            return root
        }

        /**
         * Returns a future that tracks the upload of the blob with the given digest, or [ ][Optional.empty] if there is no blob with the given digest.
         */
        fun upload(
            uploader: MerkleTreeUploader,
            context: RemoteActionExecutionContext,
            remotePathResolver: RemotePathResolver?,
            digest: Digest?,
            force: Boolean
        ): Optional<ListenableFuture<Void?>?> {
            return when (blobs.get(digest)) {
                -> Optional.of<ListenableFuture<Void?>?>(uploader.uploadBlob(context, digest, data))
                -> Optional.of<ListenableFuture<Void?>?>(
                    uploader.uploadVirtualActionInput(
                        context,
                        digest,
                        virtualActionInput
                    )
                )

                -> {
                    val spawnExecutionContext: SpawnExecutionContext? = context.getSpawnExecutionContext()
                    val pathResolver: ArtifactPathResolver =  // This can only be null when uploading a tree created by
                    // MerkleTreeComputer#buildForFiles, which only happens for remote repo execution and
                    // tests. Only the latter actually reach this code path since remote repo execution
                        // doesn't upload any inputs.
                        if (spawnExecutionContext != null)
                            spawnExecutionContext.getPathResolver()
                        else
                            MerkleTreeComputer.Companion.PATH_ACTION_INPUT_RESOLVER
                    Optional.of<ListenableFuture<Void?>?>(
                        uploader.uploadFile(
                            context, remotePathResolver, digest, pathResolver.toPath(actionInput), force
                        )
                    )
                }

                null -> Optional.empty<ListenableFuture<Void?>?>()
                else -> throw IllegalStateException("Unexpected blob type: " + blobs.get(digest))
            }
        }

        companion object {
            private val FILE_ARTIFACT_VALUE_COMPARATOR: Comparator<FileArtifactValue?> =
                Comparator.comparing<Any?, ByteArray?>(
                    FileArtifactValue::getDigest,
                    UnsignedBytes.lexicographicalComparator()
                )
                    .thenComparing(FileArtifactValue::getSize)
            val DIGEST_AND_METADATA_COMPARATOR: Comparator<Any?> = Comparator { o1: Any?, o2: Any? ->
                when (o1) {
                    -> DigestUtil.DIGEST_COMPARATOR.compare(
                        digest1,
                        when (o2) {
                            -> digest2
                            -> adaptToDigest(metadata2)
                            else -> throw IllegalStateException("Unexpected blob type: " + o2)
                        }
                    )

                    -> when (o2) {
                        -> FILE_ARTIFACT_VALUE_COMPARATOR.compare(metadata1, metadata2)
                        -> DigestUtil.DIGEST_COMPARATOR.compare(adaptToDigest(metadata1), digest2)
                        else -> throw IllegalStateException("Unexpected blob type: " + o2)
                    }

                    else -> throw IllegalStateException("Unexpected blob type: " + o1)
                }
            }

            private fun adaptToDigest(key: Any): Digest? {
                return when (key) {
                    -> digest
                    -> DigestUtil.buildDigest(metadata.getDigest(), metadata.getSize())
                    else -> throw IllegalStateException("Unexpected blob type: " + key)
                }
            }
        }
    }
}
