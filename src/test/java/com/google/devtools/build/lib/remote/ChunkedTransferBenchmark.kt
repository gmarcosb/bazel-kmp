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

/** Benchmark for chunk download/upload with per-chunk latency jitter.  */
@BenchmarkMode(org.openjdk.jmh.annotations.Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1, time = 1, timeUnit = TimeUnit.SECONDS)
@org.openjdk.jmh.annotations.Measurement(iterations = 3, time = 3, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class ChunkedTransferBenchmark {
    @org.openjdk.jmh.annotations.Benchmark
    @Throws(java.lang.Exception::class)
    fun downloadChunked(state: DownloadState) {
        state.downloader.downloadChunked(CONTEXT, state.blobDigest, java.io.OutputStream.nullOutputStream())
    }

    @org.openjdk.jmh.annotations.Benchmark
    @Throws(java.lang.Exception::class)
    fun uploadChunked(state: UploadState) {
        state.uploader.uploadChunked(CONTEXT, state.blobDigest, state.file)
    }

    @org.openjdk.jmh.annotations.State(org.openjdk.jmh.annotations.Scope.Thread)
    class DownloadState {
        @org.openjdk.jmh.annotations.Param("1", "2", "4", "8")
        var schedulerThreads: Int = 0

        @org.openjdk.jmh.annotations.Param("32")
        var chunkCount: Int = 0

        @org.openjdk.jmh.annotations.Param("1024")
        var chunkSizeBytes: Int = 0

        @org.openjdk.jmh.annotations.Param("25")
        var delayMillis: Int = 0

        @org.openjdk.jmh.annotations.Param("10")
        var jitterMillis: Int = 0

        private var scheduler: ScheduledExecutorService? = null
        private var downloader: ChunkedBlobDownloader? = null
        private var blobDigest: Digest? = null
        private var latencyJitter: Random? = null

        @Setup(org.openjdk.jmh.annotations.Level.Trial)
        @Throws(java.lang.Exception::class)
        fun setup() {
            scheduler = Executors.newScheduledThreadPool(schedulerThreads)
            latencyJitter = Random(12345L)

            val grpcCacheClient: GrpcCacheClient = Mockito.mock<GrpcCacheClient>(GrpcCacheClient::class.java)
            val combinedCache: CombinedCache = Mockito.mock<CombinedCache>(CombinedCache::class.java)

            val chunkDigests: MutableList<Digest?> = java.util.ArrayList<Digest?>(chunkCount)
            val chunkDataByDigest: MutableMap<Digest?, ByteArray?> = HashMap<Digest?, ByteArray?>(chunkCount)
            var totalBytes: Long = 0
            for (i in 0..<chunkCount) {
                val chunkData = ByteArray(chunkSizeBytes)
                Random(i.toLong()).nextBytes(chunkData)
                val chunkDigest: Digest? = DIGEST_UTIL.compute(chunkData)
                chunkDigests.add(chunkDigest)
                chunkDataByDigest.put(chunkDigest, chunkData)
                totalBytes += chunkData.size.toLong()
            }

            Mockito.`when`<T?>(
                combinedCache.downloadBlob(
                    ArgumentMatchers.any<T?>(),
                    ArgumentMatchers.any<T?>(Digest::class.java)
                )
            )
                .thenAnswer(
                    Answer { invocation: InvocationOnMock? ->
                        delayedFuture<ByteArray?>(
                            chunkDataByDigest.get(invocation.getArgument<Any?>(1)),
                            delayMillis,
                            jitterMillis,
                            latencyJitter,
                            scheduler
                        )
                    })

            blobDigest =
                Digest.newBuilder()
                    .setHash("chunked-transfer-benchmark-download-" + chunkCount + "-" + chunkSizeBytes)
                    .setSizeBytes(totalBytes)
                    .build()

            val splitBlobResponse: SplitBlobResponse? =
                SplitBlobResponse.newBuilder().addAllChunkDigests(chunkDigests).build()
            Mockito.`when`<T?>(
                grpcCacheClient.splitBlob(
                    ArgumentMatchers.any<T?>(),
                    ArgumentMatchers.any<T?>(Digest::class.java)
                )
            )
                .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture<V?>(splitBlobResponse))

            downloader = ChunkedBlobDownloader(grpcCacheClient, combinedCache, DIGEST_UTIL)
        }

        @org.openjdk.jmh.annotations.TearDown(org.openjdk.jmh.annotations.Level.Trial)
        fun tearDown() {
            scheduler.shutdownNow()
        }
    }

    @org.openjdk.jmh.annotations.State(org.openjdk.jmh.annotations.Scope.Thread)
    class UploadState {
        @org.openjdk.jmh.annotations.Param("1", "2", "4", "8")
        var schedulerThreads: Int = 0

        @org.openjdk.jmh.annotations.Param("32768")
        var fileSizeBytes: Int = 0

        @org.openjdk.jmh.annotations.Param("1024")
        var avgChunkSizeBytes: Int = 0

        @org.openjdk.jmh.annotations.Param("25")
        var delayMillis: Int = 0

        @org.openjdk.jmh.annotations.Param("10")
        var jitterMillis: Int = 0

        private var scheduler: ScheduledExecutorService? = null
        private var uploader: ChunkedBlobUploader? = null
        private var file: Path? = null
        private var blobDigest: Digest? = null
        private var latencyJitter: Random? = null

        @Setup(org.openjdk.jmh.annotations.Level.Trial)
        @Throws(java.lang.Exception::class)
        fun setup() {
            scheduler = Executors.newScheduledThreadPool(schedulerThreads)
            latencyJitter = Random(54321L)

            val grpcCacheClient: GrpcCacheClient = Mockito.mock<GrpcCacheClient>(GrpcCacheClient::class.java)
            val combinedCache: CombinedCache = Mockito.mock<CombinedCache>(CombinedCache::class.java)

            val data = ByteArray(fileSizeBytes)
            Random(42).nextBytes(data)
            blobDigest = DIGEST_UTIL.compute(data)

            val fs: FileSystem =
                InMemoryFileSystem(com.google.devtools.build.lib.clock.JavaClock(), DigestHashFunction.SHA256)
            file = fs.getPath("/bench/blob.bin")
            file.getParentDirectory().createDirectoryAndParents()
            file.getOutputStream().use { out ->
                out.write(data)
            }
            val chunkingConfig: ChunkingConfig = ChunkingConfig(avgChunkSizeBytes, 2, 0)
            uploader =
                ChunkedBlobUploader(grpcCacheClient, combinedCache, chunkingConfig, DIGEST_UTIL)

            val chunkDigests: MutableList<Digest?>?
            file.getInputStream().use { input ->
                chunkDigests = FastCdcChunker(chunkingConfig, DIGEST_UTIL).chunkToDigests(input)
            }
            Mockito.`when`<T?>(
                grpcCacheClient.findMissingDigests(
                    ArgumentMatchers.any<T?>(),
                    ArgumentMatchers.any<T?>()
                )
            )
                .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture<V?>(com.google.common.collect.ImmutableSet.< E > copyOf < E ? > (chunkDigests)))
            Mockito.`when`<T?>(
                grpcCacheClient.spliceBlob(
                    ArgumentMatchers.any<T?>(),
                    ArgumentMatchers.any<T?>(Digest::class.java),
                    ArgumentMatchers.any<T?>()
                )
            )
                .thenReturn(com.google.common.util.concurrent.Futures.immediateVoidFuture())
            Mockito.`when`<T?>(
                combinedCache.uploadBlob(
                    ArgumentMatchers.any<T?>(),
                    ArgumentMatchers.any<T?>(Digest::class.java),
                    ArgumentMatchers.any<T?>(Blob::class.java)
                )
            )
                .thenAnswer(
                    Answer { invocation: InvocationOnMock? ->
                        delayedFuture<Any?>(
                            null,
                            delayMillis,
                            jitterMillis,
                            latencyJitter,
                            scheduler
                        )
                    })
        }

        @org.openjdk.jmh.annotations.TearDown(org.openjdk.jmh.annotations.Level.Trial)
        fun tearDown() {
            scheduler.shutdownNow()
        }
    }

    companion object {
        private val DIGEST_UTIL: DigestUtil = DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256)
        private val CONTEXT: RemoteActionExecutionContext? =
            RemoteActionExecutionContext.create(RequestMetadata.getDefaultInstance())

        private fun <T> delayedFuture(
            value: T?,
            delayMillis: Int,
            jitterMillis: Int,
            latencyJitter: Random,
            scheduler: ScheduledExecutorService
        ): com.google.common.util.concurrent.ListenableFuture<T?> {
            val future: com.google.common.util.concurrent.SettableFuture<T?> =
                com.google.common.util.concurrent.SettableFuture.create<T?>()
            val unused: java.util.concurrent.ScheduledFuture<Boolean?>? =
                scheduler.schedule<Boolean?>(
                    java.util.concurrent.Callable { future.set(value) },
                    jitteredDelayMillis(delayMillis, jitterMillis, latencyJitter).toLong(),
                    TimeUnit.MILLISECONDS
                )
            return future
        }

        private fun jitteredDelayMillis(delayMillis: Int, jitterMillis: Int, latencyJitter: Random): Int {
            if (jitterMillis == 0) {
                return delayMillis
            }
            return max(0, delayMillis + latencyJitter.nextInt((jitterMillis * 2) + 1) - jitterMillis)
        }
    }
}
