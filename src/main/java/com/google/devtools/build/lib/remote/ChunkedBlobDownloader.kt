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

/** Downloads blobs by fetching chunks through a per-blob sliding window via the SplitBlob API.  */
class ChunkedBlobDownloader(grpcCacheClient: GrpcCacheClient, combinedCache: CombinedCache, digestUtil: DigestUtil) {
    private val grpcCacheClient: GrpcCacheClient
    private val combinedCache: CombinedCache
    private val digestUtil: DigestUtil

    init {
        this.grpcCacheClient = grpcCacheClient
        this.combinedCache = combinedCache
        this.digestUtil = digestUtil
    }

    /**
     * Downloads a blob using chunked download via the SplitBlob API. This should be called with
     * virtual threads, as it may block while waiting for chunk metadata and completed chunk
     * downloads.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun downloadChunked(
        context: RemoteActionExecutionContext?, blobDigest: Digest, out: java.io.OutputStream
    ) {
        var out: java.io.OutputStream = out
        var digestOut: com.google.devtools.build.lib.remote.util.DigestOutputStream? = null
        if (grpcCacheClient.shouldVerifyDownloads()) {
            digestOut = digestUtil.newDigestOutputStream(out)
            out = digestOut
        }

        val chunkDigests: MutableList<Digest?> = getChunkDigests(context, blobDigest)
        downloadAndReassembleChunks(context, chunkDigests, out)
        if (digestOut != null) {
            com.google.devtools.build.lib.remote.util.Utils.verifyBlobContents(blobDigest, digestOut.digest())
        }
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun getChunkDigests(context: RemoteActionExecutionContext?, blobDigest: Digest): MutableList<Digest?> {
        if (blobDigest.getSizeBytes() === 0) {
            return com.google.common.collect.ImmutableList.of<Digest?>()
        }
        val splitResponseFuture: com.google.common.util.concurrent.ListenableFuture<SplitBlobResponse?>? =
            grpcCacheClient.splitBlob(context, blobDigest)
        if (splitResponseFuture == null) {
            throw CacheNotFoundException(blobDigest)
        }
        val chunkDigests: MutableList<Digest?> =
            com.google.devtools.build.lib.remote.util.Utils.getFromFuture<SplitBlobResponse?>(splitResponseFuture)
                .getChunkDigestsList()
        if (chunkDigests.isEmpty()) {
            throw CacheNotFoundException(blobDigest)
        }
        return chunkDigests
    }

    private class PendingDownload(
        digest: Digest?,
        future: com.google.common.util.concurrent.ListenableFuture<ByteArray?>?,
        firstChunkIndex: Int
    ) {
        private val digest: Digest?
        private val future: com.google.common.util.concurrent.ListenableFuture<ByteArray?>?
        private val chunkIndices: MutableList<Int?> = java.util.ArrayList<Int?>(1)

        init {
            this.digest = digest
            this.future = future
            chunkIndices.add(firstChunkIndex)
        }

        fun addChunkIndex(chunkIndex: Int) {
            chunkIndices.add(chunkIndex)
        }

        fun digest(): Digest? {
            return digest
        }

        fun future(): com.google.common.util.concurrent.ListenableFuture<ByteArray?>? {
            return future
        }

        fun chunkIndices(): MutableList<Int?> {
            return chunkIndices
        }
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun downloadAndReassembleChunks(
        context: RemoteActionExecutionContext?, chunkDigests: MutableList<Digest?>, out: java.io.OutputStream
    ) {
        DownloadSession(context, chunkDigests, out).run()
    }

    private inner class DownloadSession(
        context: RemoteActionExecutionContext?,
        chunkDigests: MutableList<Digest?>,
        out: java.io.OutputStream
    ) {
        private val completedDownloads: LinkedBlockingQueue<PendingDownload?> = LinkedBlockingQueue<PendingDownload?>()
        private val activeDownloads: MutableMap<Digest?, PendingDownload> = HashMap<Digest?, PendingDownload>(
            MAX_IN_FLIGHT_CHUNK_DOWNLOADS
        )
        private val readyChunks: MutableMap<Int?, ByteArray?> = HashMap<Int?, ByteArray?>(MAX_IN_FLIGHT_CHUNK_DOWNLOADS)
        private val context: RemoteActionExecutionContext?
        private val chunkDigests: MutableList<Digest?>
        private val out: java.io.OutputStream
        private var nextToStart = 0
        private var nextToWrite = 0

        init {
            this.context = context
            this.chunkDigests = chunkDigests
            this.out = out
        }

        @Throws(IOException::class, java.lang.InterruptedException::class)
        fun run() {
            try {
                fillWindow()
                while (nextToWrite < chunkDigests.size()) {
                    drainCompletedDownloads()
                    drainReadyChunks()
                    fillWindow()
                }
            } finally {
                cancelAllDownloads()
            }
        }

        fun fillWindow() {
            while (nextToStart < chunkDigests.size()) {
                if (nextToStart - nextToWrite >= MAX_IN_FLIGHT_CHUNK_DOWNLOADS) {
                    return
                }
                val chunkDigest: Digest? = chunkDigests.get(nextToStart)
                val existing = activeDownloads.get(chunkDigest)
                if (existing != null) {
                    existing.addChunkIndex(nextToStart)
                    nextToStart++
                    continue
                }
                startDownload(chunkDigest, nextToStart)
                nextToStart++
            }
        }

        fun startDownload(chunkDigest: Digest?, chunkIndex: Int) {
            val download: PendingDownload =
                com.google.devtools.build.lib.remote.ChunkedBlobDownloader.PendingDownload(
                    chunkDigest, combinedCache.downloadBlob(context, chunkDigest), chunkIndex
                )
            activeDownloads.put(chunkDigest, download)
            download.future().addListener(
                java.lang.Runnable { completedDownloads.add(download) },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        }

        @Throws(IOException::class, java.lang.InterruptedException::class)
        fun drainCompletedDownloads() {
            var download: PendingDownload? = completedDownloads.take()
            do {
                processCompletedDownload(download!!)
                download = completedDownloads.poll()
            } while (download != null)
        }

        @Throws(IOException::class, java.lang.InterruptedException::class)
        fun processCompletedDownload(download: PendingDownload) {
            activeDownloads.remove(download.digest())
            val chunkData: ByteArray? =
                com.google.devtools.build.lib.remote.util.Utils.getFromFuture<ByteArray?>(download.future())
            for (chunkIndex in download.chunkIndices()) {
                if (chunkIndex == nextToWrite) {
                    out.write(chunkData)
                    nextToWrite++
                } else {
                    readyChunks.put(chunkIndex, chunkData)
                }
            }
        }

        @Throws(IOException::class)
        fun drainReadyChunks() {
            while (true) {
                val chunk = readyChunks.remove(nextToWrite)
                if (chunk == null) {
                    return
                }
                out.write(chunk)
                nextToWrite++
            }
        }

        fun cancelAllDownloads() {
            for (download in activeDownloads.values()) {
                download.future().cancel( /* mayInterruptIfRunning= */true)
            }
        }
    }

    companion object {
        // Guard against pathological fanout from a single large chunked blob. This is only a per-blob
        // cap; chunk requests still flow through CombinedCache and the shared remote cache transport
        // stack below it, which is what bounds active remote RPC concurrency across blobs.
        private const val MAX_IN_FLIGHT_CHUNK_DOWNLOADS = 16
    }
}
