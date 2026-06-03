// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.remote.util.Utils.getFromFuture

/** Tests for [ByteStreamUploader].  */
@RunWith(JUnit4::class)
class ByteStreamUploaderTest {
    private val serviceRegistry: MutableHandlerRegistry = MutableHandlerRegistry()
    private var retryService: com.google.common.util.concurrent.ListeningScheduledExecutorService? = null

    private val serverName = "Server for " + this.javaClass
    private var server: io.grpc.Server? = null
    private var referenceCountedChannel: ReferenceCountedChannel? = null
    private var context: RemoteActionExecutionContext? = null

    @org.mockito.Mock
    private val mockBackoff: Retrier.Backoff? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        server =
            InProcessServerBuilder.forName(serverName)
                .fallbackHandlerRegistry(serviceRegistry)
                .build()
                .start()
        referenceCountedChannel =
            ReferenceCountedChannel(
                object : ChannelConnectionWithServerCapabilitiesFactory() {
                    public override fun create(): Single<ChannelConnectionWithServerCapabilities?>? {
                        return Single.just<ChannelConnectionWithServerCapabilities?>(
                            ChannelConnectionWithServerCapabilities(
                                InProcessChannelBuilder.forName(serverName).build(),
                                Single.just<T?>(ServerCapabilities.getDefaultInstance())
                            )
                        )
                    }

                    public override fun maxConcurrency(): Int {
                        return 100
                    }
                })
        val metadata: RequestMetadata? =
            TracingMetadataUtils.buildMetadata(
                "none",
                "none",
                DIGEST_UTIL.asActionKey(Digest.getDefaultInstance()).digest().getHash(),
                null
            )
        context = RemoteActionExecutionContext.create(metadata)

        retryService =
            com.google.common.util.concurrent.MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1))
    }

    @org.junit.After
    @Throws(java.lang.Exception::class)
    fun tearDown() {
        referenceCountedChannel.release()
        retryService.shutdownNow()
        retryService.awaitTermination(
            com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
        )

        server.shutdownNow()
        server.awaitTermination()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun singleBlobUploadShouldWork() {
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { mockBackoff },
                ResultClassifier { e -> Result.TRANSIENT_FAILURE },
                retryService
            )
        val uploader: ByteStreamUploader =
            ByteStreamUploader(
                INSTANCE_NAME,
                referenceCountedChannel,
                CallCredentialsProvider.NO_CREDENTIALS,  /* callTimeoutSecs= */
                60,
                retrier,  /* maximumOpenFiles= */
                -1,  /* digestFunction= */
                DigestFunction.Value.SHA256
            )

        val blob = ByteArray(CHUNK_SIZE * 2 + 1)
        Random().nextBytes(blob)

        val chunker: Chunker? = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build()
        val digest: Digest? = DIGEST_UTIL.compute(blob)

        serviceRegistry.addService(com.google.devtools.build.lib.remote.util.TestUtils.newNoErrorByteStreamService(blob))

        uploadBlob(uploader, context, digest, chunker)

        // This test should not have triggered any retries.
        Mockito.verifyNoInteractions(mockBackoff)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun singleChunkCompressedUploadAlreadyExists() {
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { mockBackoff },
                ResultClassifier { e -> Result.TRANSIENT_FAILURE },
                retryService
            )
        val uploader: ByteStreamUploader =
            ByteStreamUploader(
                INSTANCE_NAME,
                referenceCountedChannel,
                CallCredentialsProvider.NO_CREDENTIALS,  /* callTimeoutSecs= */
                60,
                retrier,  /* maximumOpenFiles= */
                -1,  /* digestFunction= */
                DigestFunction.Value.SHA256
            )

        val blob = byteArrayOf('A'.code.toByte())

        // Set a chunk size that should have no problem accommodating the compressed
        // blob, even though the blob most likely has a compression ratio >= 1.
        val chunker: Chunker? =
            Chunker.builder().setInput(blob).setCompressed(true).setChunkSize(100).build()
        val digest: Digest? = DIGEST_UTIL.compute(blob)

        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun write(streamObserver: StreamObserver<WriteResponse?>): StreamObserver<WriteRequest?> {
                    return object : StreamObserver<WriteRequest?> {
                        private var numChunksReceived = 0

                        override fun onNext(writeRequest: WriteRequest) {
                            // This should be the first and only chunk written.
                            numChunksReceived++
                            Truth.assertThat(numChunksReceived).isEqualTo(1)
                            val data: ByteString = writeRequest.getData()
                            Truth.assertThat(data.size()).isGreaterThan(0)
                            assertThat(writeRequest.getFinishWrite()).isTrue()

                            // On receiving the chunk, respond with a committed size of -1
                            // to indicate that the blob already exists (per the remote API
                            // spec) and close the stream.
                            val response: WriteResponse? = WriteResponse.newBuilder().setCommittedSize(-1).build()
                            streamObserver.onNext(response)
                            streamObserver.onCompleted()
                        }

                        override fun onError(throwable: Throwable?) {
                            org.junit.Assert.fail("onError should never be called.")
                        }

                        override fun onCompleted() {}
                    }
                }
            })

        uploadBlob(uploader, context, digest, chunker)

        // This test should not have triggered any retries.
        Mockito.verifyNoInteractions(mockBackoff)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun progressiveUploadShouldWork() {
        Mockito.`when`<T?>(mockBackoff.getRetryAttempts()).thenReturn(0)
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { mockBackoff },
                ResultClassifier { e -> Result.TRANSIENT_FAILURE },
                retryService
            )
        val uploader: ByteStreamUploader =
            ByteStreamUploader(
                INSTANCE_NAME,
                referenceCountedChannel,
                CallCredentialsProvider.NO_CREDENTIALS,
                3,
                retrier,  /* maximumOpenFiles= */
                -1,  /* digestFunction= */
                DigestFunction.Value.SHA256
            )

        val blob = ByteArray(CHUNK_SIZE * 2 + 1)
        Random().nextBytes(blob)

        val chunker: Chunker? = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build()
        val digest: Digest? = DIGEST_UTIL.compute(blob)

        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                var receivedData: ByteArray = ByteArray(blob.size)
                var receivedResourceName: String? = null
                var receivedComplete: Boolean = false
                var nextOffset: Long = 0
                var initialOffset: Long = 0
                var mustQueryWriteStatus: Boolean = false

                public override fun write(streamObserver: StreamObserver<WriteResponse?>): StreamObserver<WriteRequest?> {
                    return object : StreamObserver<WriteRequest?> {
                        override fun onNext(writeRequest: WriteRequest) {
                            Truth.assertThat(mustQueryWriteStatus).isFalse()

                            val resourceName: String? = writeRequest.getResourceName()
                            if (nextOffset == initialOffset) {
                                if (initialOffset == 0L) {
                                    receivedResourceName = resourceName
                                }
                                Truth.assertThat(resourceName).startsWith(INSTANCE_NAME + "/uploads")
                                Truth.assertThat(resourceName).endsWith(blob.size.toString())
                            } else {
                                Truth.assertThat(resourceName).isEmpty()
                            }

                            assertThat(writeRequest.getWriteOffset()).isEqualTo(nextOffset)

                            val data: ByteString = writeRequest.getData()

                            java.lang.System.arraycopy(
                                data.toByteArray(), 0, receivedData, nextOffset.toInt(), data.size()
                            )

                            nextOffset += data.size().toLong()
                            receivedComplete = blob.size.toLong() == nextOffset
                            assertThat(writeRequest.getFinishWrite()).isEqualTo(receivedComplete)

                            if (initialOffset == 0L) {
                                streamObserver.onError(io.grpc.Status.DEADLINE_EXCEEDED.asException())
                                mustQueryWriteStatus = true
                                initialOffset = nextOffset
                            }
                        }

                        override fun onError(throwable: Throwable?) {
                            org.junit.Assert.fail("onError should never be called.")
                        }

                        override fun onCompleted() {
                            Truth.assertThat(nextOffset).isEqualTo(blob.size)
                            Truth.assertThat(receivedData).isEqualTo(blob)

                            val response: WriteResponse? =
                                WriteResponse.newBuilder().setCommittedSize(nextOffset).build()
                            streamObserver.onNext(response)
                            streamObserver.onCompleted()
                        }
                    }
                }

                public override fun queryWriteStatus(
                    request: QueryWriteStatusRequest, response: StreamObserver<QueryWriteStatusResponse?>
                ) {
                    val resourceName: String? = request.getResourceName()
                    val committedSize: Long
                    val complete: Boolean
                    if (receivedResourceName != null && receivedResourceName == resourceName) {
                        Truth.assertThat(mustQueryWriteStatus).isTrue()
                        mustQueryWriteStatus = false
                        committedSize = nextOffset
                        complete = receivedComplete
                    } else {
                        committedSize = 0
                        complete = false
                    }
                    response.onNext(
                        QueryWriteStatusResponse.newBuilder()
                            .setCommittedSize(committedSize)
                            .setComplete(complete)
                            .build()
                    )
                    response.onCompleted()
                }
            })

        uploadBlob(uploader, context, digest, chunker)

        // This test triggers one retry.
        Mockito.verify<Any?>(mockBackoff, Mockito.times(1))
            .nextDelayMillis(ArgumentMatchers.any<T?>(StatusRuntimeException::class.java))
        Mockito.verify<Any?>(mockBackoff, Mockito.times(1)).getRetryAttempts()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun progressiveCompressedUploadShouldWork() {
        Mockito.`when`<T?>(mockBackoff.getRetryAttempts()).thenReturn(0)
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { mockBackoff },
                ResultClassifier { e -> Result.TRANSIENT_FAILURE },
                retryService
            )
        val uploader: ByteStreamUploader =
            ByteStreamUploader(
                INSTANCE_NAME,
                referenceCountedChannel,
                CallCredentialsProvider.NO_CREDENTIALS,
                300,
                retrier,  /* maximumOpenFiles= */
                -1,  /* digestFunction= */
                DigestFunction.Value.SHA256
            )

        val chunkSize = 1024
        val skipSize = chunkSize + 1
        val blob = ByteArray(chunkSize * 2 + 1)
        Random().nextBytes(blob)

        val chunker: Chunker? =
            Chunker.builder().setInput(blob).setCompressed(true).setChunkSize(chunkSize).build()
        val digest: Digest? = DIGEST_UTIL.compute(blob)

        val output: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                var receivedResourceName: String? = null
                var receivedComplete: Boolean = false
                var nextOffset: Long = 0
                var initialOffset: Long = 0
                var mustQueryWriteStatus: Boolean = false

                public override fun write(streamObserver: StreamObserver<WriteResponse?>): StreamObserver<WriteRequest?> {
                    return object : StreamObserver<WriteRequest?> {
                        override fun onNext(writeRequest: WriteRequest) {
                            Truth.assertThat(mustQueryWriteStatus).isFalse()

                            val resourceName: String? = writeRequest.getResourceName()
                            if (nextOffset == initialOffset) {
                                if (initialOffset == 0L) {
                                    receivedResourceName = resourceName
                                }
                                Truth.assertThat(resourceName).startsWith(INSTANCE_NAME + "/uploads")
                                Truth.assertThat(resourceName).endsWith(blob.size.toString())
                            } else {
                                Truth.assertThat(resourceName).isEmpty()
                            }

                            if (initialOffset == 0L) {
                                streamObserver.onError(io.grpc.Status.DEADLINE_EXCEEDED.asException())
                                mustQueryWriteStatus = true
                                initialOffset = skipSize.toLong()
                                nextOffset = initialOffset
                            } else {
                                val data: ByteString = writeRequest.getData()
                                try {
                                    data.writeTo(output)
                                } catch (e: IOException) {
                                    streamObserver.onError(e)
                                    return
                                }
                                nextOffset += data.size().toLong()
                                receivedComplete = writeRequest.getFinishWrite()
                            }
                        }

                        override fun onError(throwable: Throwable?) {
                            org.junit.Assert.fail("onError should never be called.")
                        }

                        override fun onCompleted() {
                            val response: WriteResponse? =
                                WriteResponse.newBuilder().setCommittedSize(nextOffset).build()
                            streamObserver.onNext(response)
                            streamObserver.onCompleted()
                        }
                    }
                }

                public override fun queryWriteStatus(
                    request: QueryWriteStatusRequest, response: StreamObserver<QueryWriteStatusResponse?>
                ) {
                    val resourceName: String? = request.getResourceName()
                    val committedSize: Long
                    val complete: Boolean
                    if (receivedResourceName != null && receivedResourceName == resourceName) {
                        Truth.assertThat(mustQueryWriteStatus).isTrue()
                        mustQueryWriteStatus = false
                        committedSize = (if (receivedComplete) blob.size else skipSize).toLong()
                        complete = receivedComplete
                    } else {
                        committedSize = 0
                        complete = false
                    }
                    response.onNext(
                        QueryWriteStatusResponse.newBuilder()
                            .setCommittedSize(committedSize)
                            .setComplete(complete)
                            .build()
                    )
                    response.onCompleted()
                }
            })

        uploadBlob(uploader, context, digest, chunker)
        val decompressed: ByteArray = Zstd.decompress(output.toByteArray(), blob.size - skipSize)
        Truth.assertThat(java.util.Arrays.equals(decompressed, 0, decompressed.size, blob, skipSize, blob.size))
            .isTrue()

        // This test triggers one retry.
        Mockito.verify<Any?>(mockBackoff, Mockito.times(1))
            .nextDelayMillis(ArgumentMatchers.any<T?>(java.lang.Exception::class.java))
        Mockito.verify<Any?>(mockBackoff, Mockito.times(1)).getRetryAttempts()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun progressiveCompressedUploadSeesAlreadyExistsAtTheEnd() {
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { FixedBackoff(1, 0) },
                ResultClassifier { e ->
                    if (io.grpc.Status.fromThrowable(e).getCode() == io.grpc.Status.Code.INTERNAL)
                        Result.TRANSIENT_FAILURE
                    else
                        Result.SUCCESS
                },
                retryService
            )
        val uploader: ByteStreamUploader =
            ByteStreamUploader(
                INSTANCE_NAME,
                referenceCountedChannel,
                CallCredentialsProvider.NO_CREDENTIALS,
                300,
                retrier,  /* maximumOpenFiles= */
                -1,  /* digestFunction= */
                DigestFunction.Value.SHA256
            )

        val chunkSize = 1024
        val blob = ByteArray(chunkSize * 2 + 1)
        Random().nextBytes(blob)

        val chunker: Chunker? =
            Chunker.builder().setInput(blob).setCompressed(true).setChunkSize(chunkSize).build()
        val digest: Digest? = DIGEST_UTIL.compute(blob)

        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun write(streamObserver: StreamObserver<WriteResponse?>): StreamObserver<WriteRequest?> {
                    return object : StreamObserver<WriteRequest?> {
                        override fun onNext(writeRequest: WriteRequest?) {}

                        override fun onError(throwable: Throwable?) {
                            org.junit.Assert.fail("onError should never be called.")
                        }

                        override fun onCompleted() {
                            streamObserver.onError(io.grpc.Status.INTERNAL.asException())
                        }
                    }
                }

                public override fun queryWriteStatus(
                    request: QueryWriteStatusRequest?, response: StreamObserver<QueryWriteStatusResponse?>
                ) {
                    response.onNext(
                        QueryWriteStatusResponse.newBuilder()
                            .setCommittedSize(blob.size)
                            .setComplete(true)
                            .build()
                    )
                    response.onCompleted()
                }
            })

        uploadBlob(uploader, context, digest, chunker)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun concurrentlyCompletedUploadIsNotRetried() {
        // Test that after an upload has failed and the QueryWriteStatus call returns
        // that the upload has completed that we'll not retry the upload.
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { FixedBackoff(1, 0) },
                ResultClassifier { e -> Result.TRANSIENT_FAILURE },
                retryService
            )
        val uploader: ByteStreamUploader =
            ByteStreamUploader(
                INSTANCE_NAME,
                referenceCountedChannel,
                CallCredentialsProvider.NO_CREDENTIALS,
                1,
                retrier,  /* maximumOpenFiles= */
                -1,  /* digestFunction= */
                DigestFunction.Value.SHA256
            )

        val blob = ByteArray(CHUNK_SIZE * 2 + 1)
        Random().nextBytes(blob)

        val chunker: Chunker? = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build()
        val digest: Digest? = DIGEST_UTIL.compute(blob)

        val numWriteCalls: AtomicInteger = AtomicInteger(0)

        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun write(streamObserver: StreamObserver<WriteResponse?>): StreamObserver<WriteRequest?> {
                    numWriteCalls.getAndIncrement()
                    streamObserver.onError(io.grpc.Status.DEADLINE_EXCEEDED.asException())
                    return object : StreamObserver<WriteRequest?> {
                        override fun onNext(writeRequest: WriteRequest?) {}

                        override fun onError(throwable: Throwable?) {}

                        override fun onCompleted() {}
                    }
                }

                public override fun queryWriteStatus(
                    request: QueryWriteStatusRequest?, response: StreamObserver<QueryWriteStatusResponse?>
                ) {
                    response.onNext(
                        QueryWriteStatusResponse.newBuilder()
                            .setCommittedSize(blob.size)
                            .setComplete(true)
                            .build()
                    )
                    response.onCompleted()
                }
            })

        uploadBlob(uploader, context, digest, chunker)

        // This test should not have triggered any retries.
        Truth.assertThat(numWriteCalls.get()).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unimplementedQueryShouldRestartUpload() {
        Mockito.`when`<T?>(mockBackoff.getRetryAttempts()).thenReturn(0)
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { mockBackoff },
                ResultClassifier { e -> Result.TRANSIENT_FAILURE },
                retryService
            )
        val uploader: ByteStreamUploader =
            ByteStreamUploader(
                INSTANCE_NAME,
                referenceCountedChannel,
                CallCredentialsProvider.NO_CREDENTIALS,
                3,
                retrier,  /* maximumOpenFiles= */
                -1,  /* digestFunction= */
                DigestFunction.Value.SHA256
            )

        val blob = ByteArray(CHUNK_SIZE * 2 + 1)
        Random().nextBytes(blob)

        val chunker: Chunker? = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build()
        val digest: Digest? = DIGEST_UTIL.compute(blob)

        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                var expireCall: Boolean = true
                var sawReset: Boolean = false

                public override fun write(streamObserver: StreamObserver<WriteResponse?>): StreamObserver<WriteRequest?> {
                    return object : StreamObserver<WriteRequest?> {
                        override fun onNext(writeRequest: WriteRequest) {
                            if (expireCall) {
                                streamObserver.onError(io.grpc.Status.DEADLINE_EXCEEDED.asException())
                                expireCall = false
                            } else if (!sawReset && writeRequest.getWriteOffset() !== 0) {
                                streamObserver.onError(io.grpc.Status.INVALID_ARGUMENT.asException())
                            } else {
                                sawReset = true
                                if (writeRequest.getFinishWrite()) {
                                    val committedSize: Long =
                                        writeRequest.getWriteOffset() + writeRequest.getData().size()
                                    streamObserver.onNext(
                                        WriteResponse.newBuilder().setCommittedSize(committedSize).build()
                                    )
                                    streamObserver.onCompleted()
                                }
                            }
                        }

                        override fun onError(throwable: Throwable?) {
                            org.junit.Assert.fail("onError should never be called.")
                        }

                        override fun onCompleted() {}
                    }
                }

                public override fun queryWriteStatus(
                    request: QueryWriteStatusRequest?, response: StreamObserver<QueryWriteStatusResponse?>
                ) {
                    response.onError(io.grpc.Status.UNIMPLEMENTED.asException())
                }
            })

        uploadBlob(uploader, context, digest, chunker)

        // This test should have triggered a single retry, because it made
        // no progress.
        Mockito.verify<Any?>(mockBackoff, Mockito.times(1))
            .nextDelayMillis(ArgumentMatchers.any<T?>(java.lang.Exception::class.java))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun earlyWriteResponseShouldCompleteUpload() {
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { mockBackoff },
                ResultClassifier { e -> Result.PERMANENT_FAILURE },
                retryService
            )
        val uploader: ByteStreamUploader =
            ByteStreamUploader(
                INSTANCE_NAME,
                referenceCountedChannel,
                CallCredentialsProvider.NO_CREDENTIALS,
                3,
                retrier,  /* maximumOpenFiles= */
                -1,  /* digestFunction= */
                DigestFunction.Value.SHA256
            )

        val blob = ByteArray(CHUNK_SIZE * 2 + 1)
        Random().nextBytes(blob)
        // provide only enough data to write a single chunk
        val `in`: java.io.InputStream = ByteArrayInputStream(blob, 0, CHUNK_SIZE)

        val chunker: Chunker? =
            Chunker.builder().setInput(blob.size, { `in` }).setChunkSize(CHUNK_SIZE).build()
        val digest: Digest? = DIGEST_UTIL.compute(blob)

        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun write(streamObserver: StreamObserver<WriteResponse?>): StreamObserver<WriteRequest?> {
                    streamObserver.onNext(WriteResponse.newBuilder().setCommittedSize(blob.size).build())
                    streamObserver.onCompleted()
                    return com.google.devtools.build.lib.remote.ByteStreamUploaderTest.NoopStreamObserver()
                }
            })

        uploadBlob(uploader, context, digest, chunker)

        // This test should not have triggered any retries.
        Mockito.verifyNoInteractions(mockBackoff)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun incorrectCommittedSizeFailsCompletedUpload() {
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { mockBackoff },
                ResultClassifier { e -> Result.TRANSIENT_FAILURE },
                retryService
            )
        val uploader: ByteStreamUploader =
            ByteStreamUploader(
                INSTANCE_NAME,
                referenceCountedChannel,
                CallCredentialsProvider.NO_CREDENTIALS,
                3,
                retrier,  /* maximumOpenFiles= */
                -1,  /* digestFunction= */
                DigestFunction.Value.SHA256
            )

        val blob = ByteArray(CHUNK_SIZE * 2 + 1)
        Random().nextBytes(blob)

        val chunker: Chunker? = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build()
        val digest: Digest? = DIGEST_UTIL.compute(blob)

        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun write(streamObserver: StreamObserver<WriteResponse?>): StreamObserver<WriteRequest?> {
                    return object : StreamObserver<WriteRequest?> {
                        override fun onNext(writeRequest: WriteRequest?) {}

                        override fun onError(throwable: Throwable?) {
                            org.junit.Assert.fail("onError should never be called.")
                        }

                        override fun onCompleted() {
                            val response: WriteResponse? =
                                WriteResponse.newBuilder().setCommittedSize(blob.size + 1).build()
                            streamObserver.onNext(response)
                            streamObserver.onCompleted()
                        }
                    }
                }
            })

        try {
            uploadBlob(uploader, context, digest, chunker)
            org.junit.Assert.fail("Should have thrown an exception.")
        } catch (e: IOException) {
            // expected
        }

        // This test should not have triggered any retries.
        Mockito.verifyNoInteractions(mockBackoff)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun incorrectCommittedSizeDoesNotFailIncompleteUpload() {
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { mockBackoff },
                ResultClassifier { e -> Result.PERMANENT_FAILURE },
                retryService
            )
        val uploader: ByteStreamUploader =
            ByteStreamUploader(
                INSTANCE_NAME,
                referenceCountedChannel,
                CallCredentialsProvider.NO_CREDENTIALS,
                300,
                retrier,  /* maximumOpenFiles= */
                -1,  /* digestFunction= */
                DigestFunction.Value.SHA256
            )

        val blob = ByteArray(CHUNK_SIZE * 2 + 1)
        Random().nextBytes(blob)

        val chunker: Chunker? = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build()
        val digest: Digest? = DIGEST_UTIL.compute(blob)

        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun write(streamObserver: StreamObserver<WriteResponse?>): StreamObserver<WriteRequest?> {
                    streamObserver.onNext(WriteResponse.newBuilder().setCommittedSize(CHUNK_SIZE).build())
                    streamObserver.onCompleted()
                    return com.google.devtools.build.lib.remote.ByteStreamUploaderTest.NoopStreamObserver()
                }
            })

        uploadBlob(uploader, context, digest, chunker)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleBlobsUploadShouldWork() {
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { FixedBackoff(1, 0) },
                ResultClassifier { e -> Result.TRANSIENT_FAILURE },
                retryService
            )
        val uploader: ByteStreamUploader =
            ByteStreamUploader(
                INSTANCE_NAME,
                referenceCountedChannel,
                CallCredentialsProvider.NO_CREDENTIALS,  /* callTimeoutSecs= */
                60,
                retrier,  /* maximumOpenFiles= */
                -1,  /* digestFunction= */
                DigestFunction.Value.SHA256
            )

        val numUploads = 10
        val blobsByHash: MutableMap<com.google.common.hash.HashCode?, ByteArray?> =
            HashMap<com.google.common.hash.HashCode?, ByteArray?>()
        val chunkers: MutableMap<Digest?, Chunker?> =
            com.google.common.collect.Maps.newHashMapWithExpectedSize<Digest?, Chunker?>(numUploads)
        val rand: Random = Random()
        for (i in 0..<numUploads) {
            val blobSize: Int = rand.nextInt(CHUNK_SIZE * 10) + CHUNK_SIZE
            val blob = ByteArray(blobSize)
            rand.nextBytes(blob)
            val chunker: Chunker? = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build()
            val digest: Digest = DIGEST_UTIL.compute(blob)
            chunkers.put(digest, chunker)
            blobsByHash.put(com.google.common.hash.HashCode.fromString(digest.getHash()), blob)
        }

        serviceRegistry.addService(MaybeFailOnceUploadService(blobsByHash))

        uploadBlobs(uploader, context, chunkers)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun tooManyFilesIOException_adviseMaximumOpenFilesFlag() {
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { FixedBackoff(1, 0) },
                ResultClassifier { e -> Result.TRANSIENT_FAILURE },
                retryService
            )
        val uploader: ByteStreamUploader =
            ByteStreamUploader(
                INSTANCE_NAME,
                referenceCountedChannel,
                CallCredentialsProvider.NO_CREDENTIALS,  /* callTimeoutSecs= */
                60,
                retrier,  /* maximumOpenFiles= */
                -1,  /* digestFunction= */
                DigestFunction.Value.SHA256
            )
        val blob = ByteArray(CHUNK_SIZE)
        val chunker: Chunker = Mockito.mock<Chunker>(Chunker::class.java)
        val digest: Digest = DIGEST_UTIL.compute(blob)
        Mockito.doThrow(IOException("Too many open files")).`when`<Any?>(chunker).seek(0)
        Mockito.`when`<T?>(chunker.getUncompressedSize()).thenReturn(digest.getSizeBytes())
        serviceRegistry.addService(MaybeFailOnceUploadService(com.google.common.collect.ImmutableMap.of<com.google.common.hash.HashCode?, ByteArray?>()))

        val newMessage =
            ("An IOException was thrown because the process opened too many files. We recommend setting"
                    + " --bep_maximum_open_remote_upload_files flag to a number lower than your system"
                    + " default (run 'ulimit -a' for *nix-based operating systems). Original error message:"
                    + " Too many open files")
        Truth.assertThat(
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { uploadBlob(uploader, context, digest, chunker) })
        )
            .hasMessageThat()
            .isEqualTo(newMessage)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun availablePermitsOpenFileSemaphore_fewerPermitsThanUploads_endWithAllPermits() {
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { FixedBackoff(1, 0) },
                ResultClassifier { e -> Result.TRANSIENT_FAILURE },
                retryService
            )
        // number of permits is less than number of uploads to affirm permit is released
        val maximumOpenFiles = 999
        val uploader: ByteStreamUploader =
            ByteStreamUploader(
                INSTANCE_NAME,
                referenceCountedChannel,
                CallCredentialsProvider.NO_CREDENTIALS,  /* callTimeoutSecs= */
                60,
                retrier,
                maximumOpenFiles,  /* digestFunction= */
                DigestFunction.Value.SHA256
            )

        assertThat(uploader.getOpenedFilePermits().availablePermits()).isEqualTo(999)

        val customFileTracker = CustomFileTracker(maximumOpenFiles)
        val numUploads = 1000
        val blobsByHash: MutableMap<com.google.common.hash.HashCode?, ByteArray?> =
            HashMap<com.google.common.hash.HashCode?, ByteArray?>()
        val chunkers: MutableMap<Digest?, Chunker?> =
            com.google.common.collect.Maps.newHashMapWithExpectedSize<Digest?, Chunker?>(numUploads)
        val rand: Random = Random()
        for (i in 0..<numUploads) {
            val blobSize: Int = rand.nextInt(CHUNK_SIZE * 10) + CHUNK_SIZE
            val blob = ByteArray(blobSize)
            rand.nextBytes(blob)
            val chunker: Chunker? =
                TestChunker.Companion.builder(customFileTracker).setInput(blob).setChunkSize(CHUNK_SIZE).build()
            val digest: Digest = DIGEST_UTIL.compute(blob)
            chunkers.put(digest, chunker)
            blobsByHash.put(com.google.common.hash.HashCode.fromString(digest.getHash()), blob)
        }

        serviceRegistry.addService(MaybeFailOnceUploadService(blobsByHash))

        uploadBlobs(uploader, context, chunkers)

        assertThat(uploader.getOpenedFilePermits().availablePermits()).isEqualTo(maximumOpenFiles)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noMaximumOpenFilesFlags_nullSemaphore() {
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { FixedBackoff(1, 0) },
                ResultClassifier { e -> Result.TRANSIENT_FAILURE },
                retryService
            )
        val uploader: ByteStreamUploader =
            ByteStreamUploader(
                INSTANCE_NAME,
                referenceCountedChannel,
                CallCredentialsProvider.NO_CREDENTIALS,  /* callTimeoutSecs= */
                60,
                retrier,  /* maximumOpenFiles= */
                -1,  /* digestFunction= */
                DigestFunction.Value.SHA256
            )
        assertThat(uploader.getOpenedFilePermits()).isNull()

        val numUploads = 10
        val blobsByHash: MutableMap<com.google.common.hash.HashCode?, ByteArray?> =
            HashMap<com.google.common.hash.HashCode?, ByteArray?>()
        val chunkers: MutableMap<Digest?, Chunker?> =
            com.google.common.collect.Maps.newHashMapWithExpectedSize<Digest?, Chunker?>(numUploads)
        val rand: Random = Random()
        for (i in 0..<numUploads) {
            val blobSize: Int = rand.nextInt(CHUNK_SIZE * 10) + CHUNK_SIZE
            val blob = ByteArray(blobSize)
            rand.nextBytes(blob)
            val chunker: Chunker? = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build()
            val digest: Digest = DIGEST_UTIL.compute(blob)
            chunkers.put(digest, chunker)
            blobsByHash.put(com.google.common.hash.HashCode.fromString(digest.getHash()), blob)
        }

        serviceRegistry.addService(MaybeFailOnceUploadService(blobsByHash))

        uploadBlobs(uploader, context, chunkers)
        assertThat(uploader.getOpenedFilePermits()).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun contextShouldBePreservedUponRetries() {
        // We upload blobs with different context, and retry 3 times for each upload.
        // We verify that the correct metadata is passed to the server with every blob.
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { FixedBackoff(5, 0) },
                ResultClassifier { e -> Result.TRANSIENT_FAILURE },
                retryService
            )
        val uploader: ByteStreamUploader =
            ByteStreamUploader(
                INSTANCE_NAME,
                referenceCountedChannel,
                CallCredentialsProvider.NO_CREDENTIALS,  /* callTimeoutSecs= */
                60,
                retrier,  /* maximumOpenFiles= */
                -1,  /* digestFunction= */
                DigestFunction.Value.SHA256
            )

        val toUpload: MutableList<String> =
            com.google.common.collect.ImmutableList.of<String?>("aaaaaaaaaa", "bbbbbbbbbb", "cccccccccc")
        val chunkers: MutableMap<Digest?, Chunker?> =
            com.google.common.collect.Maps.newHashMapWithExpectedSize<Digest?, Chunker?>(toUpload.size)
        val uploadsFailed: MutableMap<String?, Int> = HashMap<String?, Int>()
        for (s in toUpload) {
            val chunker: Chunker? =
                Chunker.builder().setInput(s.toByteArray(java.nio.charset.StandardCharsets.UTF_8)).setChunkSize(3)
                    .build()
            val digest: Digest = DIGEST_UTIL.computeAsUtf8(s)
            chunkers.put(digest, chunker)
            uploadsFailed.put(digest.getHash(), 0)
        }

        val bsService: BindableService =
            object : ByteStreamImplBase() {
                public override fun write(response: StreamObserver<WriteResponse?>): StreamObserver<WriteRequest?> {
                    return object : StreamObserver<WriteRequest?> {
                        private var digestHash: String? = null

                        override fun onNext(writeRequest: WriteRequest) {
                            val resourceName: String = writeRequest.getResourceName()
                            if (!resourceName.isEmpty()) {
                                val components: Array<String?> =
                                    resourceName.split("/".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                                Truth.assertThat<String?>(components).hasLength(6)
                                digestHash = components[4]
                            }
                            Truth.assertThat(digestHash).isNotNull()
                            val meta: RequestMetadata = TracingMetadataUtils.fromCurrentContext()
                            assertThat(meta.getCorrelatedInvocationsId()).isEqualTo("build-req-id")
                            assertThat(meta.getToolInvocationId()).isEqualTo("command-id")
                            assertThat(meta.getActionId()).isEqualTo(digestHash)
                            assertThat(meta.getToolDetails().getToolName()).isEqualTo("bazel")
                            assertThat(meta.getToolDetails().getToolVersion())
                                .isEqualTo(BlazeVersionInfo.instance().getVersion())
                            synchronized(this) {
                                val numFailures: Int = uploadsFailed.get(digestHash)!!
                                if (numFailures < 3) {
                                    uploadsFailed.put(digestHash, numFailures + 1)
                                    response.onError(io.grpc.Status.INTERNAL.asException())
                                    return
                                }
                            }
                        }

                        override fun onError(throwable: Throwable?) {
                            org.junit.Assert.fail("onError should never be called.")
                        }

                        override fun onCompleted() {
                            response.onNext(WriteResponse.newBuilder().setCommittedSize(10).build())
                            response.onCompleted()
                        }
                    }
                }

                public override fun queryWriteStatus(
                    request: QueryWriteStatusRequest?, response: StreamObserver<QueryWriteStatusResponse?>
                ) {
                    response.onNext(
                        QueryWriteStatusResponse.newBuilder()
                            .setCommittedSize(0)
                            .setComplete(false)
                            .build()
                    )
                    response.onCompleted()
                }
            }
        serviceRegistry.addService(
            ServerInterceptors.intercept(
                bsService, ServerHeadersInterceptor()
            )
        )

        val uploads: MutableList<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>> =
            java.util.ArrayList<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>>()

        for (chunkerEntry in chunkers.entries) {
            val actionDigest: Digest? = chunkerEntry.key
            val metadata: RequestMetadata? =
                TracingMetadataUtils.buildMetadata(
                    "build-req-id",
                    "command-id",
                    DIGEST_UTIL.asActionKey(actionDigest).digest().getHash(),
                    null
                )
            val remoteActionExecutionContext: RemoteActionExecutionContext? =
                RemoteActionExecutionContext.create(metadata)
            uploads.add(
                uploader.uploadBlobAsync(
                    remoteActionExecutionContext, actionDigest, chunkerEntry.value
                )
            )
        }

        for (upload in uploads) {
            upload.get()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun customHeadersAreAttachedToRequest() {
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { FixedBackoff(1, 0) },
                ResultClassifier { e -> Result.TRANSIENT_FAILURE },
                retryService
            )

        val metadata: io.grpc.Metadata = io.grpc.Metadata()
        metadata.put<String?>(
            io.grpc.Metadata.Key.of<String?>("Key1", io.grpc.Metadata.ASCII_STRING_MARSHALLER),
            "Value1"
        )
        metadata.put<String?>(
            io.grpc.Metadata.Key.of<String?>("Key2", io.grpc.Metadata.ASCII_STRING_MARSHALLER),
            "Value2"
        )

        referenceCountedChannel.release()
        referenceCountedChannel =
            ReferenceCountedChannel(
                object : ChannelConnectionWithServerCapabilitiesFactory() {
                    public override fun create(): Single<ChannelConnectionWithServerCapabilities?>? {
                        return Single.just<ChannelConnectionWithServerCapabilities?>(
                            ChannelConnectionWithServerCapabilities(
                                InProcessChannelBuilder.forName(serverName)
                                    .intercept(MetadataUtils.newAttachHeadersInterceptor(metadata))
                                    .build(),
                                Single.just<T?>(ServerCapabilities.getDefaultInstance())
                            )
                        )
                    }

                    public override fun maxConcurrency(): Int {
                        return 100
                    }
                })
        val uploader: ByteStreamUploader =
            ByteStreamUploader(
                INSTANCE_NAME,
                referenceCountedChannel,
                CallCredentialsProvider.NO_CREDENTIALS,  /* callTimeoutSecs= */
                60,
                retrier,  /* maximumOpenFiles= */
                -1,  /* digestFunction= */
                DigestFunction.Value.SHA256
            )

        val blob = ByteArray(CHUNK_SIZE)
        val chunker: Chunker? = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build()
        val digest: Digest? = DIGEST_UTIL.compute(blob)

        serviceRegistry.addService(
            ServerInterceptors.intercept(
                object : ByteStreamImplBase() {
                    public override fun write(
                        streamObserver: StreamObserver<WriteResponse?>
                    ): StreamObserver<WriteRequest?> {
                        return object : StreamObserver<WriteRequest?> {
                            override fun onNext(writeRequest: WriteRequest?) {}

                            override fun onError(throwable: Throwable?) {
                                org.junit.Assert.fail("onError should never be called.")
                            }

                            override fun onCompleted() {
                                val response: WriteResponse? =
                                    WriteResponse.newBuilder().setCommittedSize(blob.size).build()
                                streamObserver.onNext(response)
                                streamObserver.onCompleted()
                            }
                        }
                    }
                },
                object : ServerInterceptor {
                    override fun <ReqT, RespT> interceptCall(
                        call: ServerCall<ReqT?, RespT?>?,
                        metadata: io.grpc.Metadata,
                        next: ServerCallHandler<ReqT?, RespT?>
                    ): ServerCall.Listener<ReqT?>? {
                        Truth.assertThat(
                            metadata.get<String?>(
                                io.grpc.Metadata.Key.of<String?>(
                                    "Key1",
                                    io.grpc.Metadata.ASCII_STRING_MARSHALLER
                                )
                            )
                        )
                            .isEqualTo("Value1")
                        Truth.assertThat(
                            metadata.get<String?>(
                                io.grpc.Metadata.Key.of<String?>(
                                    "Key2",
                                    io.grpc.Metadata.ASCII_STRING_MARSHALLER
                                )
                            )
                        )
                            .isEqualTo("Value2")
                        Truth.assertThat(
                            metadata.get<String?>(
                                io.grpc.Metadata.Key.of<String?>(
                                    "Key3",
                                    io.grpc.Metadata.ASCII_STRING_MARSHALLER
                                )
                            )
                        )
                            .isEqualTo(null)
                        return next.startCall(call, metadata)
                    }
                })
        )

        uploadBlob(uploader, context, digest, chunker)
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun errorsShouldBeReported() {
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { FixedBackoff(1, 10) },
                ResultClassifier { e -> Result.TRANSIENT_FAILURE },
                retryService
            )
        val uploader: ByteStreamUploader =
            ByteStreamUploader(
                INSTANCE_NAME,
                referenceCountedChannel,
                CallCredentialsProvider.NO_CREDENTIALS,  /* callTimeoutSecs= */
                60,
                retrier,  /* maximumOpenFiles= */
                -1,  /* digestFunction= */
                DigestFunction.Value.SHA256
            )

        val blob = ByteArray(CHUNK_SIZE)
        val chunker: Chunker? = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build()
        val digest: Digest? = DIGEST_UTIL.compute(blob)

        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun write(response: StreamObserver<WriteResponse?>): StreamObserver<WriteRequest?> {
                    response.onError(io.grpc.Status.INTERNAL.asException())
                    return com.google.devtools.build.lib.remote.ByteStreamUploaderTest.NoopStreamObserver()
                }
            })

        try {
            uploadBlob(uploader, context, digest, chunker)
            org.junit.Assert.fail("Should have thrown an exception.")
        } catch (e: IOException) {
            assertThat(RemoteRetrierUtils.causedByStatus(e, io.grpc.Status.Code.INTERNAL)).isTrue()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun failureInRetryExecutorShouldBeHandled() {
        val retryService: com.google.common.util.concurrent.ListeningScheduledExecutorService =
            com.google.common.util.concurrent.MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1))
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { FixedBackoff(1, 10) },
                ResultClassifier { e -> Result.TRANSIENT_FAILURE },
                retryService
            )
        val uploader: ByteStreamUploader =
            ByteStreamUploader(
                INSTANCE_NAME,
                referenceCountedChannel,
                CallCredentialsProvider.NO_CREDENTIALS,  /* callTimeoutSecs= */
                60,
                retrier,  /* maximumOpenFiles= */
                -1,  /* digestFunction= */
                DigestFunction.Value.SHA256
            )

        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun write(response: StreamObserver<WriteResponse?>): StreamObserver<WriteRequest?> {
                    // Immediately fail the call, so that it is retried.
                    response.onError(io.grpc.Status.ABORTED.asException())
                    return com.google.devtools.build.lib.remote.ByteStreamUploaderTest.NoopStreamObserver()
                }
            })

        retryService.shutdownNow()
        // Random very high timeout, as the test will timeout by itself.
        retryService.awaitTermination(1, TimeUnit.DAYS)
        Truth.assertThat(retryService.isShutdown()).isTrue()

        val blob = ByteArray(1)
        val chunker: Chunker? = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build()
        val digest: Digest? = DIGEST_UTIL.compute(blob)
        try {
            uploadBlob(uploader, context, digest, chunker)
            org.junit.Assert.fail("Should have thrown an exception.")
        } catch (e: IOException) {
            Truth.assertThat(e).hasCauseThat().isInstanceOf(RejectedExecutionException::class.java)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resourceNameWithoutInstanceName() {
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { mockBackoff },
                ResultClassifier { e -> Result.TRANSIENT_FAILURE },
                retryService
            )
        val uploader: ByteStreamUploader =
            ByteStreamUploader( /* instanceName= */
                null,
                referenceCountedChannel,
                CallCredentialsProvider.NO_CREDENTIALS,  /* callTimeoutSecs= */
                60,
                retrier,  /* maximumOpenFiles= */
                -1,  /* digestFunction= */
                DigestFunction.Value.SHA256
            )

        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun write(response: StreamObserver<WriteResponse?>): StreamObserver<WriteRequest?> {
                    return object : StreamObserver<WriteRequest?> {
                        override fun onNext(writeRequest: WriteRequest) {
                            // Test that the resource name doesn't start with an instance name.
                            assertThat(writeRequest.getResourceName()).startsWith("uploads/")
                        }

                        override fun onError(throwable: Throwable?) {}

                        override fun onCompleted() {
                            response.onNext(WriteResponse.newBuilder().setCommittedSize(1).build())
                            response.onCompleted()
                        }
                    }
                }
            })

        val blob = ByteArray(1)
        val chunker: Chunker? = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build()
        val digest: Digest? = DIGEST_UTIL.compute(blob)

        uploadBlob(uploader, context, digest, chunker)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resourceWithNewStyleDigestFunction() {
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { mockBackoff },
                ResultClassifier { e -> Result.TRANSIENT_FAILURE },
                retryService
            )
        val uploader: ByteStreamUploader =
            ByteStreamUploader( /* instanceName= */
                null,
                referenceCountedChannel,
                CallCredentialsProvider.NO_CREDENTIALS,  /* callTimeoutSecs= */
                60,
                retrier,  /* maximumOpenFiles= */
                -1,  /* digestFunction= */
                DigestFunction.Value.BLAKE3
            )

        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun write(response: StreamObserver<WriteResponse?>): StreamObserver<WriteRequest?> {
                    return object : StreamObserver<WriteRequest?> {
                        override fun onNext(writeRequest: WriteRequest) {
                            // Test that the resource name contains the digest function.
                            com.google.common.truth.Subject.contains("blobs/blake3/")
                        }

                        override fun onError(throwable: Throwable?) {}

                        override fun onCompleted() {
                            response.onNext(WriteResponse.newBuilder().setCommittedSize(1).build())
                            response.onCompleted()
                        }
                    }
                }
            })

        val blob = ByteArray(1)
        val chunker: Chunker? = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build()
        val digest: Digest? = DIGEST_UTIL.compute(blob)

        uploadBlob(uploader, context, digest, chunker)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nonRetryableStatusShouldNotBeRetried() {
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { FixedBackoff(1, 0) },  /* No Status is retriable. */
                ResultClassifier { e -> Result.PERMANENT_FAILURE },
                retryService
            )
        val uploader: ByteStreamUploader =
            ByteStreamUploader( /* instanceName= */
                null,
                referenceCountedChannel,
                CallCredentialsProvider.NO_CREDENTIALS,  /* callTimeoutSecs= */
                60,
                retrier,  /* maximumOpenFiles= */
                -1,  /* digestFunction= */
                DigestFunction.Value.SHA256
            )

        val numCalls: AtomicInteger = AtomicInteger()

        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun write(response: StreamObserver<WriteResponse?>): StreamObserver<WriteRequest?> {
                    numCalls.incrementAndGet()
                    response.onError(io.grpc.Status.INTERNAL.asException())
                    return com.google.devtools.build.lib.remote.ByteStreamUploaderTest.NoopStreamObserver()
                }
            })

        val blob = ByteArray(1)
        val chunker: Chunker? = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build()
        val digest: Digest? = DIGEST_UTIL.compute(blob)

        try {
            uploadBlob(uploader, context, digest, chunker)
            org.junit.Assert.fail("Should have thrown an exception.")
        } catch (e: IOException) {
            Truth.assertThat(numCalls.get()).isEqualTo(1)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unauthenticatedErrorShouldNotBeRetried() {
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { mockBackoff },
                RemoteRetrier.EXPERIMENTAL_GRPC_RESULT_CLASSIFIER,
                retryService
            )

        val refreshTimes: AtomicInteger = AtomicInteger()
        val callCredentialsProvider: CallCredentialsProvider =
            object : CallCredentialsProvider() {
                val callCredentials: CallCredentials?
                    get() = null

                @Throws(IOException::class)
                public override fun refresh() {
                    refreshTimes.incrementAndGet()
                }
            }
        val uploader: ByteStreamUploader =
            ByteStreamUploader(
                INSTANCE_NAME,
                referenceCountedChannel,
                callCredentialsProvider,  /* callTimeoutSecs= */
                60,
                retrier,  /* maximumOpenFiles= */
                -1,  /* digestFunction= */
                DigestFunction.Value.SHA256
            )

        val blob = ByteArray(CHUNK_SIZE * 2 + 1)
        Random().nextBytes(blob)

        val chunker: Chunker? = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build()
        val digest: Digest? = DIGEST_UTIL.compute(blob)

        val numUploads: AtomicInteger = AtomicInteger()
        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun write(streamObserver: StreamObserver<WriteResponse?>): StreamObserver<WriteRequest?> {
                    numUploads.incrementAndGet()

                    streamObserver.onError(io.grpc.Status.UNAUTHENTICATED.asException())
                    return com.google.devtools.build.lib.remote.ByteStreamUploaderTest.NoopStreamObserver()
                }
            })

        org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { uploadBlob(uploader, context, digest, chunker) })

        Truth.assertThat(refreshTimes.get()).isEqualTo(1)
        Truth.assertThat(numUploads.get()).isEqualTo(2)

        // This test should not have triggered any retries.
        Mockito.verifyNoInteractions(mockBackoff)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun shouldRefreshCredentialsOnAuthenticationError() {
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { mockBackoff },
                RemoteRetrier.EXPERIMENTAL_GRPC_RESULT_CLASSIFIER,
                retryService
            )

        val refreshTimes: AtomicInteger = AtomicInteger()
        val callCredentialsProvider: CallCredentialsProvider =
            object : CallCredentialsProvider() {
                val callCredentials: CallCredentials?
                    get() = null

                @Throws(IOException::class)
                public override fun refresh() {
                    refreshTimes.incrementAndGet()
                }
            }
        val uploader: ByteStreamUploader =
            ByteStreamUploader(
                INSTANCE_NAME,
                referenceCountedChannel,
                callCredentialsProvider,  /* callTimeoutSecs= */
                60,
                retrier,  /* maximumOpenFiles= */
                -1,  /* digestFunction= */
                DigestFunction.Value.SHA256
            )

        val blob = ByteArray(CHUNK_SIZE * 2 + 1)
        Random().nextBytes(blob)

        val chunker: Chunker? = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build()
        val digest: Digest? = DIGEST_UTIL.compute(blob)

        val numUploads: AtomicInteger = AtomicInteger()
        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun write(streamObserver: StreamObserver<WriteResponse?>): StreamObserver<WriteRequest?> {
                    numUploads.incrementAndGet()

                    if (refreshTimes.get() == 0) {
                        streamObserver.onError(io.grpc.Status.UNAUTHENTICATED.asException())
                        return com.google.devtools.build.lib.remote.ByteStreamUploaderTest.NoopStreamObserver()
                    }

                    return object : StreamObserver<WriteRequest?> {
                        var nextOffset: Long = 0

                        override fun onNext(writeRequest: WriteRequest) {
                            nextOffset += writeRequest.getData().size()
                            val lastWrite = blob.size.toLong() == nextOffset
                            assertThat(writeRequest.getFinishWrite()).isEqualTo(lastWrite)
                        }

                        override fun onError(throwable: Throwable?) {
                            org.junit.Assert.fail("onError should never be called.")
                        }

                        override fun onCompleted() {
                            Truth.assertThat(nextOffset).isEqualTo(blob.size)

                            val response: WriteResponse? =
                                WriteResponse.newBuilder().setCommittedSize(nextOffset).build()
                            streamObserver.onNext(response)
                            streamObserver.onCompleted()
                        }
                    }
                }
            })

        uploadBlob(uploader, context, digest, chunker)

        Truth.assertThat(refreshTimes.get()).isEqualTo(1)
        Truth.assertThat(numUploads.get()).isEqualTo(2)

        // This test should not have triggered any retries.
        Mockito.verifyNoInteractions(mockBackoff)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun failureAfterUploadCompletes() {
        val numUploads: AtomicInteger = AtomicInteger()
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { mockBackoff },
                ResultClassifier { e -> if (e is StatusRuntimeException) Result.TRANSIENT_FAILURE else Result.SUCCESS },
                retryService
            )
        val uploader: ByteStreamUploader =
            ByteStreamUploader(
                INSTANCE_NAME,
                referenceCountedChannel,
                CallCredentialsProvider.NO_CREDENTIALS,  /* callTimeoutSecs= */
                60,
                retrier,
                -1,  /* digestFunction= */
                DigestFunction.Value.SHA256
            )

        val blob = ByteArray(CHUNK_SIZE - 1)
        Random().nextBytes(blob)

        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun write(streamObserver: StreamObserver<WriteResponse?>): StreamObserver<WriteRequest?> {
                    numUploads.incrementAndGet()
                    return object : StreamObserver<WriteRequest?> {
                        override fun onNext(writeRequest: WriteRequest?) {}

                        override fun onError(throwable: Throwable?) {
                            org.junit.Assert.fail("onError should never be called.")
                        }

                        override fun onCompleted() {
                            streamObserver.onNext(
                                WriteResponse.newBuilder().setCommittedSize(blob.size).build()
                            )
                            streamObserver.onError(io.grpc.Status.UNAVAILABLE.asException())
                        }
                    }
                }

                public override fun queryWriteStatus(
                    request: QueryWriteStatusRequest?, response: StreamObserver<QueryWriteStatusResponse?>
                ) {
                    response.onNext(
                        QueryWriteStatusResponse.newBuilder()
                            .setCommittedSize(blob.size)
                            .setComplete(true)
                            .build()
                    )
                    response.onCompleted()
                }
            })

        val chunker: Chunker? = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build()
        val digest: Digest? = DIGEST_UTIL.compute(blob)

        uploadBlob(uploader, context, digest, chunker)

        Truth.assertThat(numUploads.get()).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompressedUploads() {
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { mockBackoff },
                ResultClassifier { e -> Result.TRANSIENT_FAILURE },
                retryService
            )
        val uploader: ByteStreamUploader =
            ByteStreamUploader(
                INSTANCE_NAME,
                referenceCountedChannel,
                CallCredentialsProvider.NO_CREDENTIALS,  /* callTimeoutSecs= */
                60,
                retrier,  /* maximumOpenFiles= */
                -1,  /* digestFunction= */
                DigestFunction.Value.SHA256
            )

        val blob = ByteArray(CHUNK_SIZE * 2 + 1)
        Random().nextBytes(blob)

        val numUploads: AtomicInteger = AtomicInteger()

        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun write(streamObserver: StreamObserver<WriteResponse?>): StreamObserver<WriteRequest?> {
                    return object : StreamObserver<WriteRequest?> {
                        var baos: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
                        var resourceName: String? = null

                        override fun onNext(writeRequest: WriteRequest) {
                            if (!writeRequest.getResourceName().isEmpty()) {
                                if (resourceName != null) {
                                    Truth.assertThat(resourceName).isEqualTo(writeRequest.getResourceName())
                                } else {
                                    resourceName = writeRequest.getResourceName()
                                    Truth.assertThat(resourceName).contains("/compressed-blobs/zstd/")
                                }
                            }
                            try {
                                writeRequest.getData().writeTo(baos)
                                if (writeRequest.getFinishWrite()) {
                                    baos.close()
                                }
                            } catch (e: IOException) {
                                throw java.lang.AssertionError("I/O error on ByteArrayOutputStream.", e)
                            }
                        }

                        override fun onError(throwable: Throwable?) {
                            org.junit.Assert.fail("onError should never be called.")
                        }

                        override fun onCompleted() {
                            val data: ByteArray = baos.toByteArray()
                            try {
                                val zis: ZstdInputStream = ZstdInputStream(ByteArrayInputStream(data))
                                val decompressed: ByteArray = ByteString.readFrom(zis).toByteArray()
                                zis.close()
                                val digest: Digest = DIGEST_UTIL.compute(decompressed)

                                Truth.assertThat(blob).hasLength(decompressed.size)
                                Truth.assertThat(resourceName).isNotNull()
                                Truth.assertThat(resourceName)
                                    .endsWith(
                                        java.lang.String.format(
                                            "/%s/%s",
                                            digest.getHash(),
                                            digest.getSizeBytes()
                                        )
                                    )

                                numUploads.incrementAndGet()
                            } catch (e: IOException) {
                                throw java.lang.AssertionError("Failed decompressing data.", e)
                            } finally {
                                val response: WriteResponse? =
                                    WriteResponse.newBuilder().setCommittedSize(data.size).build()

                                streamObserver.onNext(response)
                                streamObserver.onCompleted()
                            }
                        }
                    }
                }
            })

        val chunker: Chunker? =
            Chunker.builder().setInput(blob).setCompressed(true).setChunkSize(CHUNK_SIZE).build()
        val digest: Digest? = DIGEST_UTIL.compute(blob)

        uploadBlob(uploader, context, digest, chunker)

        // This test should not have triggered any retries.
        Mockito.verifyNoInteractions(mockBackoff)

        Truth.assertThat(numUploads.get()).isEqualTo(1)
    }

    private class NoopStreamObserver : StreamObserver<WriteRequest?> {
        override fun onNext(writeRequest: WriteRequest?) {}

        override fun onError(throwable: Throwable?) {}

        override fun onCompleted() {}
    }

    internal class FixedBackoff(private val maxRetries: Int, private val delayMillis: Int) : Retrier.Backoff {
        var retryAttempts: Int = 0
            private set

        public override fun nextDelayMillis(e: java.lang.Exception?): Long {
            if (this.retryAttempts < maxRetries) {
                this.retryAttempts++
                return delayMillis.toLong()
            }
            return -1
        }
    }

    /**
     * An byte stream service where an upload for a given blob may or may not fail on the first
     * attempt but is guaranteed to succeed on the second try.
     */
    internal open class MaybeFailOnceUploadService(blobsByHash: MutableMap<com.google.common.hash.HashCode?, ByteArray?>) :
        ByteStreamImplBase() {
        private val blobsByHash: MutableMap<com.google.common.hash.HashCode?, ByteArray?>
        private val uploadsFailedOnce: MutableSet<com.google.common.hash.HashCode?> =
            Collections.synchronizedSet<com.google.common.hash.HashCode?>(HashSet<com.google.common.hash.HashCode?>())
        private val rand: Random = Random()

        init {
            this.blobsByHash = blobsByHash
        }

        public override fun write(response: StreamObserver<WriteResponse?>): StreamObserver<WriteRequest?>? {
            return object : StreamObserver<WriteRequest?> {
                private var digestHash: com.google.common.hash.HashCode? = null
                private var receivedData: ByteArray
                private var nextOffset: Long = 0
                private var failed = false

                override fun onNext(writeRequest: WriteRequest) {
                    if (nextOffset == 0L) {
                        val resourceName: String? = writeRequest.getResourceName()
                        Truth.assertThat(resourceName).isNotEmpty()

                        val components: Array<String?> =
                            resourceName.split("/".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                        Truth.assertThat<String?>(components).hasLength(6)
                        digestHash = com.google.common.hash.HashCode.fromString(components[4])
                        Truth.assertThat(blobsByHash).containsKey(digestHash)
                        receivedData = ByteArray(components[5].toInt())
                    }
                    Truth.assertThat(digestHash).isNotNull()
                    // An upload for a given blob has a 10% chance to fail once during its lifetime.
                    // This is to exercise the retry mechanism a bit.
                    val shouldFail = rand.nextInt(10) == 0 && !uploadsFailedOnce.contains(digestHash)
                    if (shouldFail) {
                        uploadsFailedOnce.add(digestHash)
                        response.onError(io.grpc.Status.INTERNAL.asException())
                        failed = true
                        return
                    }

                    val data: ByteString = writeRequest.getData()
                    java.lang.System.arraycopy(data.toByteArray(), 0, receivedData, nextOffset.toInt(), data.size())
                    nextOffset += data.size().toLong()

                    val lastWrite = nextOffset == receivedData.size.toLong()
                    assertThat(writeRequest.getFinishWrite()).isEqualTo(lastWrite)
                }

                override fun onError(throwable: Throwable?) {
                    org.junit.Assert.fail("onError should never be called.")
                }

                override fun onCompleted() {
                    if (failed) {
                        return
                    }
                    val expectedBlob = blobsByHash.get(digestHash)
                    Truth.assertThat(receivedData).isEqualTo(expectedBlob)

                    val writeResponse: WriteResponse? =
                        WriteResponse.newBuilder().setCommittedSize(receivedData.size).build()

                    response.onNext(writeResponse)
                    response.onCompleted()
                }
            }
        }

        public override fun queryWriteStatus(
            request: QueryWriteStatusRequest?, response: StreamObserver<QueryWriteStatusResponse?>
        ) {
            // force the client to reset the write
            response.onNext(
                QueryWriteStatusResponse.newBuilder().setCommittedSize(0).setComplete(false).build()
            )
            response.onCompleted()
        }
    }

    /* Custom Chunker used to track number of open files */
    private class TestChunker(dataSupplier: Blob?, size: Long, chunkSize: Int, compressed: Boolean) :
        Chunker(dataSupplier, size, chunkSize, compressed) {
        private class TestChunkerBuilder(private val customFileTracker: CustomFileTracker) : Chunker.Builder() {
            public override fun setInput(existingData: ByteArray): Chunker.Builder {
                com.google.common.base.Preconditions.checkState(this.inputStream == null)
                this.size = existingData.size
                return setInput(
                    existingData.size,
                    { TestByteArrayInputStream(existingData, customFileTracker) })
            }
        }

        companion object {
            fun builder(customFileTracker: CustomFileTracker): Builder {
                return TestChunkerBuilder(customFileTracker)
            }
        }
    }

    private class TestByteArrayInputStream(buf: ByteArray, private val customFileTracker: CustomFileTracker) :
        ByteArrayInputStream(buf) {
        init {
            customFileTracker.incrementOpenFiles()
        }

        @Throws(IOException::class)
        override fun close() {
            super.close()
            customFileTracker.decrementOpenFiles()
        }
    }

    private class CustomFileTracker(private val maxOpenFiles: Int) {
        private val openFiles: AtomicInteger = AtomicInteger(0)

        fun incrementOpenFiles() {
            openFiles.getAndIncrement()
            com.google.common.base.Preconditions.checkState(openFiles.get() <= maxOpenFiles)
        }

        fun decrementOpenFiles() {
            openFiles.getAndDecrement()
            com.google.common.base.Preconditions.checkState(openFiles.get() >= 0)
        }
    }

    companion object {
        private val DIGEST_UTIL: DigestUtil = DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256)

        private const val CHUNK_SIZE = 10
        private const val INSTANCE_NAME = "foo"

        /**
         * Uploads a BLOB, as provided by the [Chunker], to the remote `ByteStream` service.
         * The call blocks until the upload is complete, or throws an [Exception] in case of error.
         * 
         * 
         * Uploads are retried according to the specified [RemoteRetrier]. Retrying is
         * transparent to the user of this API.
         * 
         * @param digest the digest of the data to upload.
         * @param chunker the data to upload.
         * @throws IOException when reading of the [Chunker]s input source fails
         */
        @Throws(IOException::class, java.lang.InterruptedException::class)
        private fun uploadBlob(
            byteStreamUploader: ByteStreamUploader,
            context: RemoteActionExecutionContext?,
            digest: Digest?,
            chunker: Chunker?
        ) {
            getFromFuture(byteStreamUploader.uploadBlobAsync(context, digest, chunker))
        }

        /**
         * Uploads a list of BLOBs concurrently to the remote `ByteStream` service. The call blocks
         * until the upload of all BLOBs is complete, or throws an [ ] if there are errors.
         * 
         * 
         * Uploads are retried according to the specified [RemoteRetrier]. Retrying is
         * transparent to the user of this API.
         * 
         * @param chunkers the data to upload.
         * @throws IOException when reading of the [Chunker]s input source or uploading fails
         */
        @Throws(IOException::class, java.lang.InterruptedException::class)
        private fun uploadBlobs(
            byteStreamUploader: ByteStreamUploader,
            context: RemoteActionExecutionContext?,
            chunkers: MutableMap<Digest?, Chunker?>
        ) {
            val uploads: MutableList<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?> =
                java.util.ArrayList<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>()

            for (chunkerEntry in chunkers.entries) {
                uploads.add(
                    byteStreamUploader.uploadBlobAsync(
                        context, chunkerEntry.key, chunkerEntry.value
                    )
                )
            }

            waitForBulkTransfer(uploads)
        }
    }
}
