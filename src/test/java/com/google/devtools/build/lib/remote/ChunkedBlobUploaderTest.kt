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

/** Tests for [ChunkedBlobUploader].  */
@RunWith(JUnit4::class)
class ChunkedBlobUploaderTest {
    @org.junit.Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    @org.mockito.Mock
    private val grpcCacheClient: GrpcCacheClient? = null

    @org.mockito.Mock
    private val combinedCache: CombinedCache? = null

    @org.mockito.Mock
    private val context: RemoteActionExecutionContext? = null

    private var fs: FileSystem? = null
    private var execRoot: Path? = null
    private var uploader: ChunkedBlobUploader? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        fs = InMemoryFileSystem(com.google.devtools.build.lib.clock.JavaClock(), DigestHashFunction.SHA256)
        execRoot = fs.getPath("/execroot")
        execRoot.createDirectoryAndParents()

        val config: ChunkingConfig = ChunkingConfig(1024, 2, 0)
        uploader = ChunkedBlobUploader(grpcCacheClient, combinedCache, config, DIGEST_UTIL)
    }

    @get:org.junit.Test
    val chunkingThreshold_returnsConfiguredValue: Unit
        get() {
            val config: ChunkingConfig = ChunkingConfig(512, 2, 0)
            val uploader: ChunkedBlobUploader =
                ChunkedBlobUploader(
                    grpcCacheClient,
                    combinedCache,
                    config,
                    DIGEST_UTIL
                )

            assertThat(uploader.getChunkingThreshold()).isEqualTo(512 * 4)
        }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun uploadChunked_allChunksMissing_uploadsAllChunks() {
        val file: Path = execRoot.getRelative("test.txt")
        val data = ByteArray(8192)
        Random(42).nextBytes(data)
        writeFile(file, data)
        val blobDigest: Digest? = DIGEST_UTIL.compute(data)

        val digestsCaptor: ArgumentCaptor<MutableList<Digest?>> =
            ArgumentCaptor.forClass<MutableList<Digest?>?, MutableList<*>?>(
                MutableList::class.java
            )
        T > Mockito.`when`<Boolean?>(
            grpcCacheClient.findMissingDigests(TODO("Cannot convert element"))<T> ArgumentMatchers . any < kotlin . Any ? > (),
            digestsCaptor.capture()
        )
        thenAnswer(
            { invocation ->
                val digests: MutableList<Digest?> = invocation.< List < Digest > > getArgument<MutableList<Digest?>?>(1)
                return@thenAnswer
                com.google.common.util.concurrent.Futures.immediateFuture<com.google.common.collect.ImmutableSet<Digest?>?>(
                    com.google.common.collect.ImmutableSet.copyOf<Digest?>(digests)
                )
            })
        T > Mockito.`when`<Boolean?>(
            combinedCache.uploadBlob(TODO("Cannot convert element"))<T> ArgumentMatchers . any < kotlin . Any ? > (),
            TODO("Cannot convert element")
        )<T> ArgumentMatchers . any < Digest ? > (Digest::class.java)
        T > ArgumentMatchers.any<Blob?>(Blob::class.java)
        thenReturn(com.google.common.util.concurrent.Futures.immediateVoidFuture())
        T > Mockito.`when`<Boolean?>(
            grpcCacheClient.spliceBlob(TODO("Cannot convert element"))<T> ArgumentMatchers . any < kotlin . Any ? > (),
            TODO("Cannot convert element")
        )<T> ArgumentMatchers . any < kotlin . Any ? > ()
        T > ArgumentMatchers.any<Any?>()
        thenReturn(com.google.common.util.concurrent.Futures.immediateVoidFuture())

        uploader.uploadChunked(context, blobDigest, file)

        val chunkDigests: MutableList<Digest?> = digestsCaptor.getValue()
        Truth.assertThat(chunkDigests.size).isGreaterThan(1)
        val totalSize: Long = chunkDigests.stream().mapToLong(Digest::getSizeBytes).sum()
        Truth.assertThat(totalSize).isEqualTo(data.size)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun uploadChunked_noChunksMissing_skipsChunkUpload() {
        val file: Path = execRoot.getRelative("test.txt")
        val data = ByteArray(8192)
        Random(42).nextBytes(data)
        writeFile(file, data)
        val blobDigest: Digest? = DIGEST_UTIL.compute(data)

        Mockito.`when`<T?>(grpcCacheClient.findMissingDigests(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture<V?>(com.google.common.collect.ImmutableSet.of<E?>()))
        Mockito.`when`<T?>(
            grpcCacheClient.spliceBlob(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )
        ).thenReturn(com.google.common.util.concurrent.Futures.immediateVoidFuture())

        uploader.uploadChunked(context, blobDigest, file)

        Mockito.verify<Any?>(combinedCache, Mockito.never()).uploadBlob(
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(Digest::class.java),
            ArgumentMatchers.any<T?>(Blob::class.java)
        )
        Mockito.verify<Any?>(grpcCacheClient)
            .spliceBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (blobDigest), ArgumentMatchers.any<T?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun uploadChunked_someChunksMissing_uploadsOnlyMissingWithCorrectData() {
        val file: Path = execRoot.getRelative("test_partial.txt")
        val fileData = ByteArray(16384)
        Random(42).nextBytes(fileData)
        writeFile(file, fileData)
        val blobDigest: Digest? = DIGEST_UTIL.compute(fileData)

        val config: ChunkingConfig = ChunkingConfig(1024, 2, 0)
        val testChunker: FastCdcChunker = FastCdcChunker(config, DIGEST_UTIL)
        val allChunkDigests: MutableList<Digest>?
        file.getInputStream().use { input ->
            allChunkDigests = testChunker.chunkToDigests(input)
        }
        Truth.assertThat(allChunkDigests!!.size).isAtLeast(5)

        val digestsToReportMissing: MutableSet<Digest?> = LinkedHashSet<Digest?>()
        for (i in allChunkDigests.indices) {
            val isFirst = i == 0
            val isLast = i == allChunkDigests.size - 1
            val isOdd = i % 2 == 1
            if (isFirst || isLast || isOdd) {
                digestsToReportMissing.add(allChunkDigests.get(i))
            }
        }

        val expectedChunkData: MutableMap<Digest?, ByteString?> = LinkedHashMap<Digest?, ByteString?>()
        file.getInputStream().use { input ->
            for (digest in allChunkDigests) {
                val chunkBytes: ByteArray = input.readNBytes(digest.getSizeBytes() as Int)
                if (digestsToReportMissing.contains(digest)) {
                    expectedChunkData.put(digest, ByteString.copyFrom(chunkBytes))
                }
            }
        }
        Mockito.`when`<T?>(grpcCacheClient.findMissingDigests(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture<V?>(com.google.common.collect.ImmutableSet.< E > copyOf < E ? > (digestsToReportMissing)))
        val actualUploads: MutableMap<Digest?, ByteString?> = HashMap<Digest?, ByteString?>()
        Mockito.`when`<T?>(
            combinedCache.uploadBlob(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(Digest::class.java),
                ArgumentMatchers.any<T?>(Blob::class.java)
            )
        )
            .thenAnswer(
                Answer { invocation: InvocationOnMock? ->
                    val d: Digest? = invocation.getArgument<Digest?>(1)
                    val blob: Blob = invocation.getArgument<Blob>(2)
                    blob.get().use { `in` ->
                        actualUploads.put(d, ByteString.readFrom(`in`))
                    }
                    com.google.common.util.concurrent.Futures.immediateVoidFuture()
                })
        Mockito.`when`<T?>(
            grpcCacheClient.spliceBlob(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )
        ).thenReturn(com.google.common.util.concurrent.Futures.immediateVoidFuture())

        uploader.uploadChunked(context, blobDigest, file)

        Truth.assertThat(actualUploads.keys).isEqualTo(expectedChunkData.keys)
        for (entry in expectedChunkData.entries) {
            Truth.assertThat(actualUploads.get(entry.key)).isEqualTo(entry.value)
        }
        Mockito.verify<Any?>(grpcCacheClient)
            .spliceBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (blobDigest), <T>eq<T?>(allChunkDigests))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun uploadChunked_windowRefillsAfterOneChunkCompletes() {
        val file: Path = execRoot.getRelative("test_window.txt")
        val data = ByteArray(262144)
        Random(42).nextBytes(data)
        writeFile(file, data)
        val blobDigest: Digest? = DIGEST_UTIL.compute(data)

        val testChunker: FastCdcChunker = FastCdcChunker(ChunkingConfig(1024, 2, 0), DIGEST_UTIL)
        val chunkDigests: MutableList<Digest>?
        file.getInputStream().use { input ->
            chunkDigests = testChunker.chunkToDigests(input)
        }
        val uniqueChunkDigests: MutableList<Digest?> = java.util.ArrayList<Digest?>()
        val seen: MutableSet<Digest?> = HashSet<Digest?>()
        for (chunkDigest in chunkDigests!!) {
            if (seen.add(chunkDigest)) {
                uniqueChunkDigests.add(chunkDigest)
            }
            if (uniqueChunkDigests.size == MAX_IN_FLIGHT_CHUNK_UPLOADS + 1) {
                break
            }
        }
        Truth.assertThat(uniqueChunkDigests).hasSize(MAX_IN_FLIGHT_CHUNK_UPLOADS + 1)

        Mockito.`when`<T?>(grpcCacheClient.findMissingDigests(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture<V?>(com.google.common.collect.ImmutableSet.< E > copyOf < E ? > (uniqueChunkDigests)))
        Mockito.`when`<T?>(
            grpcCacheClient.spliceBlob(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )
        ).thenReturn(com.google.common.util.concurrent.Futures.immediateVoidFuture())

        val uploads: MutableList<com.google.common.util.concurrent.SettableFuture<java.lang.Void?>> =
            java.util.ArrayList<com.google.common.util.concurrent.SettableFuture<java.lang.Void?>>(uniqueChunkDigests.size)
        for (i in uniqueChunkDigests.indices) {
            uploads.add(com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>())
        }
        val firstWindowRequested: CountDownLatch = CountDownLatch(MAX_IN_FLIGHT_CHUNK_UPLOADS)
        val overflowUploadRequested: CountDownLatch = CountDownLatch(1)

        Mockito.`when`<T?>(
            combinedCache.uploadBlob(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(Digest::class.java),
                ArgumentMatchers.any<T?>(Blob::class.java)
            )
        )
            .thenAnswer(
                Answer { invocation: InvocationOnMock? ->
                    val digest: Digest? = invocation.getArgument<Digest?>(1)
                    val chunkIndex = uniqueChunkDigests.indexOf(digest)
                    if (chunkIndex < MAX_IN_FLIGHT_CHUNK_UPLOADS) {
                        firstWindowRequested.countDown()
                    } else if (chunkIndex == MAX_IN_FLIGHT_CHUNK_UPLOADS) {
                        overflowUploadRequested.countDown()
                    }
                    uploads.get(chunkIndex)
                })

        val uploadThread: java.lang.Thread =
            java.lang.Thread.ofVirtual()
                .unstarted(
                    java.lang.Runnable {
                        try {
                            uploader.uploadChunked(context, blobDigest, file)
                        } catch (e: IOException) {
                            throw java.lang.RuntimeException(e)
                        } catch (e: java.lang.InterruptedException) {
                            throw java.lang.RuntimeException(e)
                        }
                    })
        uploadThread.start()

        Truth.assertThat(firstWindowRequested.await(1, TimeUnit.SECONDS)).isTrue()
        Truth.assertThat(overflowUploadRequested.await(100, TimeUnit.MILLISECONDS)).isFalse()

        uploads.get(1).set(null)
        Truth.assertThat(overflowUploadRequested.await(1, TimeUnit.SECONDS)).isTrue()

        for (upload in uploads) {
            if (!upload.isDone()) {
                upload.set(null)
            }
        }
        uploadThread.join(TimeUnit.SECONDS.toMillis(1))

        Truth.assertThat(uploadThread.isAlive()).isFalse()
        Mockito.verify<Any?>(grpcCacheClient)
            .spliceBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (blobDigest), <T>eq<T?>(chunkDigests))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun uploadChunked_chunkFails_cancelsOtherInFlightUploads() {
        val file: Path = execRoot.getRelative("test_failure.txt")
        val data = ByteArray(16384)
        Random(42).nextBytes(data)
        writeFile(file, data)
        val blobDigest: Digest? = DIGEST_UTIL.compute(data)

        val testChunker: FastCdcChunker = FastCdcChunker(ChunkingConfig(1024, 2, 0), DIGEST_UTIL)
        val chunkDigests: MutableList<Digest>?
        file.getInputStream().use { input ->
            chunkDigests = testChunker.chunkToDigests(input)
        }
        val uniqueChunkDigests: MutableList<Digest?> = java.util.ArrayList<Digest?>()
        val seen: MutableSet<Digest?> = HashSet<Digest?>()
        for (chunkDigest in chunkDigests!!) {
            if (seen.add(chunkDigest)) {
                uniqueChunkDigests.add(chunkDigest)
            }
            if (uniqueChunkDigests.size == 2) {
                break
            }
        }
        Truth.assertThat(uniqueChunkDigests).hasSize(2)

        Mockito.`when`<T?>(grpcCacheClient.findMissingDigests(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture<V?>(com.google.common.collect.ImmutableSet.< E > copyOf < E ? > (uniqueChunkDigests)))

        val failedUpload: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
        val cancelledUpload: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
        val uploadsStarted: CountDownLatch = CountDownLatch(2)
        Mockito.`when`<T?>(
            combinedCache.uploadBlob(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(Digest::class.java),
                ArgumentMatchers.any<T?>(Blob::class.java)
            )
        )
            .thenAnswer(
                Answer { invocation: InvocationOnMock? ->
                    val digest: Digest = invocation.getArgument<Digest>(1)
                    uploadsStarted.countDown()
                    if (digest.equals(uniqueChunkDigests.get(0))) {
                        return@thenAnswer failedUpload
                    }
                    if (digest.equals(uniqueChunkDigests.get(1))) {
                        return@thenAnswer cancelledUpload
                    }
                    com.google.common.util.concurrent.Futures.immediateVoidFuture()
                })

        val uploadThread: java.lang.Thread =
            java.lang.Thread.ofVirtual()
                .unstarted(
                    java.lang.Runnable {
                        try {
                            uploader.uploadChunked(context, blobDigest, file)
                        } catch (e: IOException) {
                            throw java.lang.RuntimeException(e)
                        } catch (e: java.lang.InterruptedException) {
                            throw java.lang.RuntimeException(e)
                        }
                    })
        uploadThread.start()

        Truth.assertThat(uploadsStarted.await(1, TimeUnit.SECONDS)).isTrue()
        failedUpload.setException(IOException("upload failed"))

        uploadThread.join(TimeUnit.SECONDS.toMillis(1))

        Truth.assertThat(uploadThread.isAlive()).isFalse()
        Truth.assertThat(cancelledUpload.isCancelled()).isTrue()
        Mockito.verify<Any?>(grpcCacheClient, Mockito.never())
            .spliceBlob(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun uploadChunked_cancelledUpload_throwsInterruptedException() {
        val file: Path = execRoot.getRelative("test_cancelled.txt")
        val data = ByteArray(8192)
        Random(42).nextBytes(data)
        writeFile(file, data)
        val blobDigest: Digest? = DIGEST_UTIL.compute(data)

        val testChunker: FastCdcChunker = FastCdcChunker(ChunkingConfig(1024, 2, 0), DIGEST_UTIL)
        val chunkDigests: MutableList<Digest>?
        file.getInputStream().use { input ->
            chunkDigests = testChunker.chunkToDigests(input)
        }
        val firstChunkDigest: Digest = chunkDigests!!.get(0)

        Mockito.`when`<T?>(grpcCacheClient.findMissingDigests(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .thenReturn(
                com.google.common.util.concurrent.Futures.immediateFuture<V?>(
                    com.google.common.collect.ImmutableSet.of<E?>(
                        firstChunkDigest
                    )
                )
            )

        val cancelledUpload: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
        cancelledUpload.cancel( /* mayInterruptIfRunning= */true)
        Mockito.`when`<T?>(
            combinedCache.uploadBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (firstChunkDigest),
            ArgumentMatchers.any<T?>(Blob::class.java)
        ))
        .thenReturn(cancelledUpload)

        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable { uploader.uploadChunked(context, blobDigest, file) })
        Mockito.verify<Any?>(grpcCacheClient, Mockito.never())
            .spliceBlob(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun uploadChunked_failedUploadDuringPendingChunks_surfacesBeforeOpeningChunkStream() {
        val data = ByteArray(16384)
        Random(42).nextBytes(data)
        val blobDigest: Digest? = DIGEST_UTIL.compute(data)

        val testChunker: FastCdcChunker = FastCdcChunker(ChunkingConfig(1024, 2, 0), DIGEST_UTIL)
        val chunkDigests: MutableList<Digest>?
        ByteArrayInputStream(data).use { input ->
            chunkDigests = testChunker.chunkToDigests(input)
        }
        Truth.assertThat(chunkDigests!!.size).isAtLeast(2)

        val file: Path = Mockito.mock<Path>(Path::class.java)
        Mockito.`when`<T?>(file.getInputStream()).thenReturn(ByteArrayInputStream(data))

        Mockito.`when`<T?>(grpcCacheClient.findMissingDigests(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .thenReturn(
                com.google.common.util.concurrent.Futures.immediateFuture<V?>(
                    com.google.common.collect.ImmutableSet.of<E?>(
                        chunkDigests.get(0)
                    )
                )
            )

        val failedUpload: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
        failedUpload.setException(IOException("upload failed"))
        Mockito.`when`<T?>(
            combinedCache.uploadBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (chunkDigests.get(0)),
            ArgumentMatchers.any<T?>(Blob::class.java)
        ))
        .thenReturn(failedUpload)

        val uploadThread: java.lang.Thread =
            java.lang.Thread.ofVirtual()
                .unstarted(
                    java.lang.Runnable {
                        try {
                            uploader.uploadChunked(context, blobDigest, file)
                        } catch (e: IOException) {
                            throw java.lang.RuntimeException(e)
                        } catch (e: java.lang.InterruptedException) {
                            throw java.lang.RuntimeException(e)
                        }
                    })
        uploadThread.start()

        uploadThread.join(TimeUnit.SECONDS.toMillis(1))
        Truth.assertThat(uploadThread.isAlive()).isFalse()
        Mockito.verify<Any?>(file, Mockito.times(1)).getInputStream()
        Mockito.verify<Any?>(grpcCacheClient, Mockito.never())
            .spliceBlob(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun uploadChunked_fileTruncatedBeforeChunkUpload_reportsConcurrentModification() {
        val data = ByteArray(8192)
        Random(42).nextBytes(data)
        val blobDigest: Digest? = DIGEST_UTIL.compute(data)

        val testChunker: FastCdcChunker = FastCdcChunker(ChunkingConfig(1024, 2, 0), DIGEST_UTIL)
        val chunkDigests: MutableList<Digest>?
        ByteArrayInputStream(data).use { input ->
            chunkDigests = testChunker.chunkToDigests(input)
        }
        Truth.assertThat(chunkDigests!!.size).isAtLeast(2)

        val secondChunkDigest: Digest = chunkDigests.get(1)
        val file: Path = Mockito.mock<Path>(Path::class.java)
        Mockito.`when`<T?>(file.getInputStream())
            .thenReturn(ByteArrayInputStream(data), ByteArrayInputStream(ByteArray(0)))
        Mockito.`when`<T?>(grpcCacheClient.findMissingDigests(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .thenReturn(
                com.google.common.util.concurrent.Futures.immediateFuture<V?>(
                    com.google.common.collect.ImmutableSet.of<E?>(
                        secondChunkDigest
                    )
                )
            )
        Mockito.`when`<T?>(
            combinedCache.uploadBlob(ArgumentMatchers.any<T?>(), < T > eq < T ? > (secondChunkDigest),
            ArgumentMatchers.any<T?>(Blob::class.java)
        ))
        .thenAnswer(
            Answer { invocation: InvocationOnMock? ->
                val blob: Blob = invocation.getArgument<Blob>(2)
                blob.get().use { `in` ->
                    val unused: ByteString? = ByteString.readFrom(`in`)
                }
                com.google.common.util.concurrent.Futures.immediateVoidFuture()
            })

        val e: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { uploader.uploadChunked(context, blobDigest, file) })

        Truth.assertThat(e).hasMessageThat().contains("file was concurrently modified during upload")
        Truth.assertThat(e).hasCauseThat().isInstanceOf(EOFException::class.java)
        Mockito.verify<Any?>(grpcCacheClient, Mockito.never())
            .spliceBlob(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
    }

    @Throws(IOException::class)
    private fun writeFile(path: Path, data: ByteArray?) {
        path.getOutputStream().use { out ->
            out.write(data)
        }
    }

    companion object {
        private val DIGEST_UTIL: DigestUtil = DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256)
        private const val MAX_IN_FLIGHT_CHUNK_UPLOADS = 16
    }
}
