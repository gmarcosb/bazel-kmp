// Copyright 2026 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote

import build.bazel.remote.execution.v2.Digest

/**
 * Uploads blobs in chunks using Content-Defined Chunking with FastCDC 2020.
 * 
 * 
 * Upload flow for blobs above threshold:
 * 
 * 
 *  1. Chunk file with FastCDC
 *  1. Call findMissingDigests on chunk digests
 *  1. Upload only missing chunks
 *  1. Call SpliceBlob to register the blob as the concatenation of chunks
 * 
 */
class ChunkedBlobUploader(
    grpcCacheClient: GrpcCacheClient,
    combinedCache: CombinedCache,
    config: ChunkingConfig,
    digestUtil: DigestUtil?
) {
    private val grpcCacheClient: GrpcCacheClient
    private val combinedCache: CombinedCache
    private val chunker: FastCdcChunker

    /** Returns the minimum blob size for chunked upload.  */
    val chunkingThreshold: Long

    /**
     * Creates a new uploader with the given chunking configuration.
     * 
     * @param grpcCacheClient client used for `FindMissingDigests` and `SpliceBlob` RPCs
     * @param combinedCache cache used to upload individual chunks
     * @param config chunking parameters negotiated from server capabilities
     * @param digestUtil utility for computing chunk digests
     */
    init {
        this.grpcCacheClient = grpcCacheClient
        this.combinedCache = combinedCache
        this.chunker = FastCdcChunker(config, digestUtil)
        this.chunkingThreshold = config.chunkingThreshold()
    }

    /**
     * Uploads a blob in content-defined chunks. The file is chunked with FastCDC, missing chunks are
     * uploaded, and `SpliceBlob` is called to register the blob as the concatenation of its
     * chunks.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun uploadChunked(
        context: RemoteActionExecutionContext?,
        blobDigest: Digest?,
        file: com.google.devtools.build.lib.vfs.Path
    ) {
        val chunkDigests: MutableList<Digest>?
        file.getInputStream().use { input ->
            chunkDigests = chunker.chunkToDigests(input)
        }
        if (chunkDigests!!.isEmpty()) {
            return
        }

        val missingDigests: com.google.common.collect.ImmutableSet<Digest?> =
            com.google.devtools.build.lib.remote.util.Utils.getFromFuture<com.google.common.collect.ImmutableSet<Digest?>>(
                grpcCacheClient.findMissingDigests(context, chunkDigests)
            )
        uploadMissingChunks(context, missingDigests, chunkDigests, file)
        com.google.devtools.build.lib.remote.util.Utils.getFromFuture<java.lang.Void?>(
            grpcCacheClient.spliceBlob(
                context,
                blobDigest,
                chunkDigests
            )
        )
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun uploadMissingChunks(
        context: RemoteActionExecutionContext?,
        missingDigests: com.google.common.collect.ImmutableSet<Digest?>,
        chunkDigests: MutableList<Digest>,
        file: com.google.devtools.build.lib.vfs.Path
    ) {
        if (missingDigests.isEmpty()) {
            return
        }
        UploadSession(context, missingDigests, chunkDigests).run(file)
    }

    private inner class UploadSession(
        context: RemoteActionExecutionContext?,
        missingDigests: com.google.common.collect.ImmutableSet<Digest?>,
        chunkDigests: MutableList<Digest>
    ) {
        private val completedUploads: LinkedBlockingQueue<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?> =
            LinkedBlockingQueue<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>()
        private val inFlightUploads: MutableSet<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>> =
            HashSet<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>>(MAX_IN_FLIGHT_CHUNK_UPLOADS)
        private val scheduledDigests: MutableSet<Digest?> = HashSet<Digest?>()
        private val context: RemoteActionExecutionContext?
        private val missingDigests: com.google.common.collect.ImmutableSet<Digest?>
        private val chunkDigests: MutableList<Digest>

        init {
            this.context = context
            this.missingDigests = missingDigests
            this.chunkDigests = chunkDigests
        }

        @Throws(IOException::class, java.lang.InterruptedException::class)
        fun run(file: com.google.devtools.build.lib.vfs.Path) {
            try {
                var offset: Long = 0
                for (chunkDigest in chunkDigests) {
                    drainCompletedUploads()
                    val chunkOffset = offset
                    offset += chunkDigest.getSizeBytes()
                    if (!shouldScheduleUpload(chunkDigest)) {
                        continue
                    }
                    if (inFlightUploads.size() >= MAX_IN_FLIGHT_CHUNK_UPLOADS) {
                        awaitCompletedUpload()
                    }
                    startUpload(file, chunkOffset, chunkDigest)
                }
                while (!inFlightUploads.isEmpty()) {
                    awaitCompletedUpload()
                }
            } finally {
                cancelAllUploads()
            }
        }

        fun shouldScheduleUpload(chunkDigest: Digest?): Boolean {
            return missingDigests.contains(chunkDigest) && scheduledDigests.add(chunkDigest)
        }

        fun startUpload(file: com.google.devtools.build.lib.vfs.Path, chunkOffset: Long, chunkDigest: Digest) {
            val upload: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
                combinedCache.uploadBlob(
                    context, chunkDigest, ChunkBlob(file, chunkOffset, chunkDigest)
                )
            inFlightUploads.add(upload)
            upload.addListener(
                java.lang.Runnable { completedUploads.add(upload) },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        }

        @Throws(IOException::class, java.lang.InterruptedException::class)
        fun drainCompletedUploads() {
            while (true) {
                val upload: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>? =
                    completedUploads.poll()
                if (upload == null) {
                    return
                }
                finishUpload(upload)
            }
        }

        @Throws(IOException::class, java.lang.InterruptedException::class)
        fun awaitCompletedUpload() {
            finishUpload(completedUploads.take())
            drainCompletedUploads()
        }

        @Throws(IOException::class, java.lang.InterruptedException::class)
        fun finishUpload(upload: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?) {
            inFlightUploads.remove(upload)
            com.google.devtools.build.lib.remote.util.Utils.getFromFuture<java.lang.Void?>(upload)
        }

        fun cancelAllUploads() {
            for (upload in inFlightUploads) {
                upload.cancel( /* mayInterruptIfRunning= */true)
            }
        }
    }

    private class ChunkBlob(file: com.google.devtools.build.lib.vfs.Path, offset: Long, digest: Digest) :
        com.google.devtools.build.lib.remote.common.RemoteCacheClient.Blob {
        private val file: com.google.devtools.build.lib.vfs.Path
        private val offset: Long
        private val digest: Digest

        init {
            this.file = file
            this.offset = offset
            this.digest = digest
        }

        @Throws(IOException::class)
        override fun get(): java.io.InputStream {
            val input: java.io.InputStream = file.getInputStream()
            var success = false
            try {
                seekOrSkip(input, offset)
                val limitedInput: java.io.InputStream =
                    com.google.common.io.ByteStreams.limit(input, digest.getSizeBytes())
                success = true
                return limitedInput
            } catch (e: EOFException) {
                throw IOException("file was concurrently modified during upload: " + file, e)
            } finally {
                if (!success) {
                    input.close()
                }
            }
        }

        override fun description(): String? {
            return "chunk %s at offset %d of file %s"
                .formatted(DigestUtil.toString(digest), offset, file)
        }
    }

    companion object {
        // Guard against pathological fanout from a single large chunked blob. This is only a per-blob
        // cap; chunk uploads still flow through CombinedCache and the shared remote cache transport
        // stack below it, which is what bounds active remote RPC concurrency across blobs.
        private const val MAX_IN_FLIGHT_CHUNK_UPLOADS = 16

        @Throws(IOException::class)
        private fun seekOrSkip(input: java.io.InputStream, offset: Long) {
            if (offset == 0L) {
                return
            }
            if (input is FileInputStream) {
                val channel: FileChannel = input.getChannel()
                if (channel.size() < offset) {
                    throw EOFException()
                }
                channel.position(offset)
                return
            }
            input.skipNBytes(offset)
        }
    }
}
