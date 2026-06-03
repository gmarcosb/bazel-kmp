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

/** Tests for [ChunkedBlobDownloader].  */
@RunWith(JUnit4::class)
class ChunkedBlobDownloaderTest {
    @org.junit.Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    @org.mockito.Mock
    private val grpcCacheClient: GrpcCacheClient? = null

    @org.mockito.Mock
    private val combinedCache: CombinedCache? = null

    @org.mockito.Mock
    private val context: RemoteActionExecutionContext? = null

    private var downloader: ChunkedBlobDownloader? = null

    @Before
    fun setUp() {
        Mockito.`when`<T?>(grpcCacheClient.shouldVerifyDownloads()).thenReturn(true)
        downloader = ChunkedBlobDownloader(grpcCacheClient, combinedCache, DIGEST_UTIL)
    }

    @org.junit.Test
    fun downloadChunked_splitBlobReturnsNull_throwsCacheNotFound() {
        val blobDigest: Digest? = DIGEST_UTIL.compute(byteArrayOf(1, 2, 3))
        Mockito.`when`<T?>(grpcCacheClient.splitBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (blobDigest))).thenReturn(null)

        org.junit.Assert.assertThrows<T?>(
            CacheNotFoundException::class.java,
            org.junit.function.ThrowingRunnable {
                downloader.downloadChunked(
                    context,
                    blobDigest,
                    java.io.ByteArrayOutputStream()
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadChunked_singleChunk_downloadsAndReassembles() {
        val chunkData = byteArrayOf(1, 2, 3, 4, 5)
        val chunkDigest: Digest? = DIGEST_UTIL.compute(chunkData)
        val blobDigest: Digest? = chunkDigest

        val splitResponse: SplitBlobResponse? =
            SplitBlobResponse.newBuilder().addChunkDigests(chunkDigest).build()
        Mockito.`when`<T?>(grpcCacheClient.splitBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (blobDigest)))
        .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture<V?>(splitResponse))
        Mockito.`when`<T?>(combinedCache.downloadBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (chunkDigest)))
        .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture<V?>(chunkData))

        val out: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        downloader.downloadChunked(context, blobDigest, out)

        Truth.assertThat(out.toByteArray()).isEqualTo(chunkData)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadChunked_multipleChunks_downloadsAndReassemblesInOrder() {
        val chunk1Data = byteArrayOf(1, 2, 3)
        val chunk2Data = byteArrayOf(4, 5, 6)
        val chunk3Data = byteArrayOf(7, 8, 9)
        val chunk1Digest: Digest? = DIGEST_UTIL.compute(chunk1Data)
        val chunk2Digest: Digest? = DIGEST_UTIL.compute(chunk2Data)
        val chunk3Digest: Digest? = DIGEST_UTIL.compute(chunk3Data)
        val blobDigest: Digest? = DIGEST_UTIL.compute(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9))

        val splitResponse: SplitBlobResponse? =
            SplitBlobResponse.newBuilder()
                .addChunkDigests(chunk1Digest)
                .addChunkDigests(chunk2Digest)
                .addChunkDigests(chunk3Digest)
                .build()
        Mockito.`when`<T?>(grpcCacheClient.splitBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (blobDigest)))
        .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture<V?>(splitResponse))
        Mockito.`when`<T?>(combinedCache.downloadBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (chunk1Digest)))
        .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture<V?>(chunk1Data))
        Mockito.`when`<T?>(combinedCache.downloadBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (chunk2Digest)))
        .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture<V?>(chunk2Data))
        Mockito.`when`<T?>(combinedCache.downloadBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (chunk3Digest)))
        .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture<V?>(chunk3Data))

        val out: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        downloader.downloadChunked(context, blobDigest, out)

        Truth.assertThat(out.toByteArray()).isEqualTo(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9))
        Mockito.verify<Any?>(combinedCache).downloadBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (chunk1Digest))
        Mockito.verify<Any?>(combinedCache).downloadBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (chunk2Digest))
        Mockito.verify<Any?>(combinedCache).downloadBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (chunk3Digest))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadChunked_windowRefillsAfterOneChunkCompletes() {
        val chunkDigests: MutableList<Digest?> = java.util.ArrayList<Digest?>(MAX_IN_FLIGHT_CHUNK_DOWNLOADS + 1)
        val chunkFutures: MutableList<com.google.common.util.concurrent.SettableFuture<ByteArray?>> =
            java.util.ArrayList<com.google.common.util.concurrent.SettableFuture<ByteArray?>>(
                MAX_IN_FLIGHT_CHUNK_DOWNLOADS + 1
            )
        val expectedData = ByteArray(MAX_IN_FLIGHT_CHUNK_DOWNLOADS + 1)
        val splitResponse: SplitBlobResponse.Builder = SplitBlobResponse.newBuilder()
        for (i in 0..<MAX_IN_FLIGHT_CHUNK_DOWNLOADS + 1) {
            val chunkData = byteArrayOf((i + 1).toByte())
            expectedData[i] = chunkData[0]
            chunkDigests.add(DIGEST_UTIL.compute(chunkData))
            chunkFutures.add(com.google.common.util.concurrent.SettableFuture.create<ByteArray?>())
            splitResponse.addChunkDigests(chunkDigests.get(i))
        }
        val blobDigest: Digest? = DIGEST_UTIL.compute(expectedData)

        Mockito.`when`<T?>(grpcCacheClient.splitBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (blobDigest)))
        .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture<V?>(splitResponse.build()))

        val firstWindowRequested: CountDownLatch = CountDownLatch(MAX_IN_FLIGHT_CHUNK_DOWNLOADS)
        val overflowChunkRequested: CountDownLatch = CountDownLatch(1)

        Mockito.`when`<T?>(
            combinedCache.downloadBlob(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(Digest::class.java)
            )
        )
            .thenAnswer(
                Answer { invocation: InvocationOnMock? ->
                    val digest: Digest? = invocation.getArgument<Digest?>(1)
                    val chunkIndex = chunkDigests.indexOf(digest)
                    if (chunkIndex < MAX_IN_FLIGHT_CHUNK_DOWNLOADS) {
                        firstWindowRequested.countDown()
                    } else if (chunkIndex == MAX_IN_FLIGHT_CHUNK_DOWNLOADS) {
                        overflowChunkRequested.countDown()
                    }
                    chunkFutures.get(chunkIndex)
                })

        val out: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val downloadThread: java.lang.Thread =
            java.lang.Thread.ofVirtual()
                .unstarted(
                    java.lang.Runnable {
                        try {
                            downloader.downloadChunked(context, blobDigest, out)
                        } catch (e: IOException) {
                            throw java.lang.RuntimeException(e)
                        } catch (e: java.lang.InterruptedException) {
                            throw java.lang.RuntimeException(e)
                        }
                    })
        downloadThread.start()

        Truth.assertThat(firstWindowRequested.await(1, TimeUnit.SECONDS)).isTrue()
        Truth.assertThat(overflowChunkRequested.await(100, TimeUnit.MILLISECONDS)).isFalse()

        chunkFutures.get(0).set(byteArrayOf(expectedData[0]))
        Truth.assertThat(overflowChunkRequested.await(1, TimeUnit.SECONDS)).isTrue()

        for (i in chunkFutures.indices) {
            val future: com.google.common.util.concurrent.SettableFuture<ByteArray?> = chunkFutures.get(i)
            if (!future.isDone()) {
                future.set(byteArrayOf(expectedData[i]))
            }
        }
        downloadThread.join(TimeUnit.SECONDS.toMillis(1))

        Truth.assertThat(downloadThread.isAlive()).isFalse()
        Truth.assertThat(out.toByteArray()).isEqualTo(expectedData)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadChunked_duplicateInFlightChunks_reusesDownload() {
        val chunkData = byteArrayOf(1, 2, 3)
        val chunkDigest: Digest? = DIGEST_UTIL.compute(chunkData)
        val blobDigest: Digest? = DIGEST_UTIL.compute(byteArrayOf(1, 2, 3, 1, 2, 3))

        val splitResponse: SplitBlobResponse? =
            SplitBlobResponse.newBuilder()
                .addChunkDigests(chunkDigest)
                .addChunkDigests(chunkDigest)
                .build()
        Mockito.`when`<T?>(grpcCacheClient.splitBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (blobDigest)))
        .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture<V?>(splitResponse))

        val chunkFuture: com.google.common.util.concurrent.SettableFuture<ByteArray?> =
            com.google.common.util.concurrent.SettableFuture.create<ByteArray?>()
        Mockito.`when`<T?>(combinedCache.downloadBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (chunkDigest))).thenReturn(chunkFuture)

        val out: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val downloadThread: java.lang.Thread =
            java.lang.Thread.ofVirtual()
                .unstarted(
                    java.lang.Runnable {
                        try {
                            downloader.downloadChunked(context, blobDigest, out)
                        } catch (e: IOException) {
                            throw java.lang.RuntimeException(e)
                        } catch (e: java.lang.InterruptedException) {
                            throw java.lang.RuntimeException(e)
                        }
                    })
        downloadThread.start()

        chunkFuture.set(chunkData)
        downloadThread.join(TimeUnit.SECONDS.toMillis(1))

        Truth.assertThat(downloadThread.isAlive()).isFalse()
        Truth.assertThat(out.toByteArray()).isEqualTo(byteArrayOf(1, 2, 3, 1, 2, 3))
        Mockito.verify<Any?>(combinedCache, Mockito.times(1))
            .downloadBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (chunkDigest))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadChunked_longDuplicateRun_resumesAfterDrain() {
        val firstChunkData = byteArrayOf(1)
        val duplicateChunkData = byteArrayOf(2)
        val finalChunkData = byteArrayOf(3)
        val firstChunkDigest: Digest? = DIGEST_UTIL.compute(firstChunkData)
        val duplicateChunkDigest: Digest? = DIGEST_UTIL.compute(duplicateChunkData)
        val finalChunkDigest: Digest? = DIGEST_UTIL.compute(finalChunkData)

        val blobData = ByteArray(MAX_IN_FLIGHT_CHUNK_DOWNLOADS + 1)
        blobData[0] = firstChunkData[0]
        for (i in 1..<MAX_IN_FLIGHT_CHUNK_DOWNLOADS) {
            blobData[i] = duplicateChunkData[0]
        }
        blobData[MAX_IN_FLIGHT_CHUNK_DOWNLOADS] = finalChunkData[0]
        val blobDigest: Digest? = DIGEST_UTIL.compute(blobData)

        val splitResponse: SplitBlobResponse.Builder = SplitBlobResponse.newBuilder()
        splitResponse.addChunkDigests(firstChunkDigest)
        for (i in 1..<MAX_IN_FLIGHT_CHUNK_DOWNLOADS) {
            splitResponse.addChunkDigests(duplicateChunkDigest)
        }
        splitResponse.addChunkDigests(finalChunkDigest)
        Mockito.`when`<T?>(grpcCacheClient.splitBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (blobDigest)))
        .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture<V?>(splitResponse.build()))

        val firstChunkFuture: com.google.common.util.concurrent.SettableFuture<ByteArray?> =
            com.google.common.util.concurrent.SettableFuture.create<ByteArray?>()
        val duplicateChunkFuture: com.google.common.util.concurrent.SettableFuture<ByteArray?> =
            com.google.common.util.concurrent.SettableFuture.create<ByteArray?>()
        val finalChunkFuture: com.google.common.util.concurrent.SettableFuture<ByteArray?> =
            com.google.common.util.concurrent.SettableFuture.create<ByteArray?>()
        val initialDownloadsRequested: CountDownLatch = CountDownLatch(2)
        val finalChunkRequested: CountDownLatch = CountDownLatch(1)

        Mockito.`when`<T?>(combinedCache.downloadBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (firstChunkDigest)))
        .thenAnswer(
            Answer { invocation: InvocationOnMock? ->
                initialDownloadsRequested.countDown()
                firstChunkFuture
            })
        Mockito.`when`<T?>(combinedCache.downloadBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (duplicateChunkDigest)))
        .thenAnswer(
            Answer { invocation: InvocationOnMock? ->
                initialDownloadsRequested.countDown()
                duplicateChunkFuture
            })
        Mockito.`when`<T?>(combinedCache.downloadBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (finalChunkDigest)))
        .thenAnswer(
            Answer { invocation: InvocationOnMock? ->
                finalChunkRequested.countDown()
                finalChunkFuture
            })

        val out: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val downloadThread: java.lang.Thread =
            java.lang.Thread.ofVirtual()
                .unstarted(
                    java.lang.Runnable {
                        try {
                            downloader.downloadChunked(context, blobDigest, out)
                        } catch (e: IOException) {
                            throw java.lang.RuntimeException(e)
                        } catch (e: java.lang.InterruptedException) {
                            throw java.lang.RuntimeException(e)
                        }
                    })
        downloadThread.start()

        Truth.assertThat(initialDownloadsRequested.await(1, TimeUnit.SECONDS)).isTrue()
        Truth.assertThat(finalChunkRequested.await(100, TimeUnit.MILLISECONDS)).isFalse()

        duplicateChunkFuture.set(duplicateChunkData)
        Truth.assertThat(finalChunkRequested.await(100, TimeUnit.MILLISECONDS)).isFalse()

        firstChunkFuture.set(firstChunkData)
        Truth.assertThat(finalChunkRequested.await(1, TimeUnit.SECONDS)).isTrue()

        finalChunkFuture.set(finalChunkData)
        downloadThread.join(TimeUnit.SECONDS.toMillis(1))

        Truth.assertThat(downloadThread.isAlive()).isFalse()
        Truth.assertThat(out.toByteArray()).isEqualTo(blobData)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadChunked_emptyChunkList_producesEmptyOutput() {
        val blobDigest: Digest? = DIGEST_UTIL.compute(ByteArray(0))

        val splitResponse: SplitBlobResponse? = SplitBlobResponse.getDefaultInstance()
        Mockito.`when`<T?>(grpcCacheClient.splitBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (blobDigest)))
        .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture<V?>(splitResponse))

        val out: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        downloader.downloadChunked(context, blobDigest, out)

        Truth.assertThat(out.toByteArray()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadChunked_chunkFails_throwsIOException() {
        val chunk1Data = byteArrayOf(1, 2, 3)
        val chunk2Data = byteArrayOf(4, 5, 6)
        val chunk1Digest: Digest? = DIGEST_UTIL.compute(chunk1Data)
        val chunk2Digest: Digest? = DIGEST_UTIL.compute(chunk2Data)
        val blobDigest: Digest? = DIGEST_UTIL.compute(byteArrayOf(1, 2, 3, 4, 5, 6))

        val splitResponse: SplitBlobResponse? =
            SplitBlobResponse.newBuilder()
                .addChunkDigests(chunk1Digest)
                .addChunkDigests(chunk2Digest)
                .build()
        Mockito.`when`<T?>(grpcCacheClient.splitBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (blobDigest)))
        .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture<V?>(splitResponse))
        Mockito.`when`<T?>(combinedCache.downloadBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (chunk1Digest)))
        .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture<V?>(chunk1Data))
        Mockito.`when`<T?>(combinedCache.downloadBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (chunk2Digest)))
        .thenReturn(com.google.common.util.concurrent.Futures.immediateFailedFuture<V?>(IOException("connection reset")))

        val out: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { downloader.downloadChunked(context, blobDigest, out) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadChunked_blobDigestMismatch_throwsOutputDigestMismatch() {
        val chunkData = byteArrayOf(1, 2, 3)
        val chunkDigest: Digest = DIGEST_UTIL.compute(chunkData)
        val blobDigest: Digest = DIGEST_UTIL.compute(byteArrayOf(4, 5, 6))

        val splitResponse: SplitBlobResponse? =
            SplitBlobResponse.newBuilder().addChunkDigests(chunkDigest).build()
        Mockito.`when`<T?>(grpcCacheClient.splitBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (blobDigest)))
        .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture<V?>(splitResponse))
        Mockito.`when`<T?>(combinedCache.downloadBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (chunkDigest)))
        .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture<V?>(chunkData))

        val e: OutputDigestMismatchException? =
            org.junit.Assert.assertThrows<T?>(
                OutputDigestMismatchException::class.java,
                org.junit.function.ThrowingRunnable {
                    downloader.downloadChunked(
                        context,
                        blobDigest,
                        java.io.ByteArrayOutputStream()
                    )
                })

        assertThat(e).hasMessageThat().contains(blobDigest.getHash())
        assertThat(e).hasMessageThat().contains(chunkDigest.getHash())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadChunked_blobDigestMismatchVerificationDisabled_succeeds() {
        Mockito.`when`<T?>(grpcCacheClient.shouldVerifyDownloads()).thenReturn(false)
        val chunkData = byteArrayOf(1, 2, 3)
        val chunkDigest: Digest? = DIGEST_UTIL.compute(chunkData)
        val blobDigest: Digest? = DIGEST_UTIL.compute(byteArrayOf(4, 5, 6))

        val splitResponse: SplitBlobResponse? =
            SplitBlobResponse.newBuilder().addChunkDigests(chunkDigest).build()
        Mockito.`when`<T?>(grpcCacheClient.splitBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (blobDigest)))
        .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture<V?>(splitResponse))
        Mockito.`when`<T?>(combinedCache.downloadBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (chunkDigest)))
        .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture<V?>(chunkData))

        val out: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        downloader.downloadChunked(context, blobDigest, out)

        Truth.assertThat(out.toByteArray()).isEqualTo(chunkData)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadChunked_cancelledChunk_throwsInterruptedException() {
        val chunkData = byteArrayOf(1, 2, 3)
        val chunkDigest: Digest? = DIGEST_UTIL.compute(chunkData)
        val blobDigest: Digest? = chunkDigest

        val splitResponse: SplitBlobResponse? =
            SplitBlobResponse.newBuilder().addChunkDigests(chunkDigest).build()
        Mockito.`when`<T?>(grpcCacheClient.splitBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (blobDigest)))
        .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture<V?>(splitResponse))

        val cancelledDownload: com.google.common.util.concurrent.SettableFuture<ByteArray?> =
            com.google.common.util.concurrent.SettableFuture.create<ByteArray?>()
        cancelledDownload.cancel( /* mayInterruptIfRunning= */true)
        Mockito.`when`<T?>(combinedCache.downloadBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (chunkDigest))).thenReturn(cancelledDownload)

        val out: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable { downloader.downloadChunked(context, blobDigest, out) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadChunked_chunkFails_cancelsOtherInFlightDownloads() {
        val chunk1Data = byteArrayOf(1, 2, 3)
        val chunk2Data = byteArrayOf(4, 5, 6)
        val chunk1Digest: Digest? = DIGEST_UTIL.compute(chunk1Data)
        val chunk2Digest: Digest? = DIGEST_UTIL.compute(chunk2Data)
        val blobDigest: Digest? = DIGEST_UTIL.compute(byteArrayOf(1, 2, 3, 4, 5, 6))

        val splitResponse: SplitBlobResponse? =
            SplitBlobResponse.newBuilder()
                .addChunkDigests(chunk1Digest)
                .addChunkDigests(chunk2Digest)
                .build()
        Mockito.`when`<T?>(grpcCacheClient.splitBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (blobDigest)))
        .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture<V?>(splitResponse))

        val failedDownload: com.google.common.util.concurrent.SettableFuture<ByteArray?> =
            com.google.common.util.concurrent.SettableFuture.create<ByteArray?>()
        val cancelledDownload: com.google.common.util.concurrent.SettableFuture<ByteArray?> =
            com.google.common.util.concurrent.SettableFuture.create<ByteArray?>()
        val downloadsStarted: CountDownLatch = CountDownLatch(2)
        Mockito.`when`<T?>(combinedCache.downloadBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (chunk1Digest)))
        .thenAnswer(
            Answer { invocation: InvocationOnMock? ->
                downloadsStarted.countDown()
                failedDownload
            })
        Mockito.`when`<T?>(combinedCache.downloadBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (chunk2Digest)))
        .thenAnswer(
            Answer { invocation: InvocationOnMock? ->
                downloadsStarted.countDown()
                cancelledDownload
            })

        val out: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val downloadThread: java.lang.Thread =
            java.lang.Thread.ofVirtual()
                .unstarted(
                    java.lang.Runnable {
                        try {
                            downloader.downloadChunked(context, blobDigest, out)
                        } catch (e: IOException) {
                            throw java.lang.RuntimeException(e)
                        } catch (e: java.lang.InterruptedException) {
                            throw java.lang.RuntimeException(e)
                        }
                    })
        downloadThread.start()

        Truth.assertThat(downloadsStarted.await(1, TimeUnit.SECONDS)).isTrue()
        failedDownload.setException(IOException("connection reset"))

        downloadThread.join(TimeUnit.SECONDS.toMillis(1))

        Truth.assertThat(downloadThread.isAlive()).isFalse()
        Truth.assertThat(cancelledDownload.isCancelled()).isTrue()
    }

    companion object {
        private val DIGEST_UTIL: DigestUtil = DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256)
        private const val MAX_IN_FLIGHT_CHUNK_DOWNLOADS = 16
    }
}
