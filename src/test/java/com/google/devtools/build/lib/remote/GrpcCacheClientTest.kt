// Copyright 2015 The Bazel Authors. All rights reserved.
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

/** Tests for [GrpcCacheClient].  */
@RunWith(TestParameterInjector::class)
class GrpcCacheClientTest {
    private var fs: FileSystem? = null
    private var execRoot: Path? = null
    private var outErr: FileOutErr? = null
    private var fakeFileCache: com.google.devtools.build.lib.remote.FakeActionInputFileCache? = null
    private val serviceRegistry: MutableHandlerRegistry = MutableHandlerRegistry()
    private val fakeServerName = "fake server for " + javaClass
    private var fakeServer: io.grpc.Server? = null
    private var context: RemoteActionExecutionContext? = null
    private var remotePathResolver: RemotePathResolver? = null
    private var retryService: com.google.common.util.concurrent.ListeningScheduledExecutorService? = null
    private val channels: java.util.ArrayList<ReferenceCountedChannel?> =
        java.util.ArrayList<ReferenceCountedChannel?>()

    @Throws(IOException::class)
    private fun newClient(): GrpcCacheClient {
        return newClient(com.google.devtools.common.options.Options.getDefaults<O?>(RemoteOptions::class.java))
    }

    @Throws(IOException::class)
    private fun newClient(remoteOptions: RemoteOptions?): GrpcCacheClient {
        return newClient(remoteOptions, java.util.function.Supplier { ExponentialBackoff(remoteOptions) })
    }

    @Throws(IOException::class)
    private fun newClient(
        remoteOptions: RemoteOptions?,
        backoffSupplier: java.util.function.Supplier<Backoff?>?
    ): GrpcCacheClient {
        val authTlsOptions: AuthAndTLSOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(AuthAndTLSOptions::class.java)
        authTlsOptions.useGoogleDefaultCredentials = true
        authTlsOptions.googleCredentials = "/execroot/main/creds.json"
        authTlsOptions.setGoogleAuthScopes(com.google.common.collect.ImmutableList.of<E?>("dummy.scope"))

        val json: JsonObject = JsonObject()
        json.addProperty("type", "authorized_user")
        json.addProperty("client_id", "some_client")
        json.addProperty("client_secret", "foo")
        json.addProperty("refresh_token", "bar")
        val scratch: Scratch = Scratch()
        scratch.file(authTlsOptions.googleCredentials, json.toString())

        val callCredentialsProvider: CallCredentialsProvider
        scratch.resolve(authTlsOptions.googleCredentials).getInputStream().use { `in` ->
            callCredentialsProvider =
                GoogleAuthUtils.newCallCredentialsProvider(
                    GoogleAuthUtils.newGoogleCredentialsFromFile(
                        `in`, authTlsOptions.googleAuthScopes
                    )
                )
        }
        val creds: CallCredentials? = callCredentialsProvider.callCredentials

        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                backoffSupplier, RemoteRetrier.EXPERIMENTAL_GRPC_RESULT_CLASSIFIER, retryService
            )
        val channel: ReferenceCountedChannel =
            ReferenceCountedChannel(
                object : ChannelConnectionWithServerCapabilitiesFactory() {
                    public override fun create(): Single<ChannelConnectionWithServerCapabilities?>? {
                        val ch: ManagedChannel? =
                            InProcessChannelBuilder.forName(fakeServerName)
                                .directExecutor()
                                .intercept(CallCredentialsInterceptor(creds))
                                .intercept(TracingMetadataUtils.newCacheHeadersInterceptor(remoteOptions))
                                .build()
                        return Single.just<ChannelConnectionWithServerCapabilities?>(
                            ChannelConnectionWithServerCapabilities(
                                ch, Single.just<T?>(ServerCapabilities.getDefaultInstance())
                            )
                        )
                    }

                    public override fun maxConcurrency(): Int {
                        return 100
                    }
                })
        channels.add(channel)
        return GrpcCacheClient(
            channel, callCredentialsProvider, remoteOptions, retrier, DIGEST_UTIL
        )
    }

    private class CallCredentialsInterceptor(credentials: CallCredentials?) : ClientInterceptor {
        private val credentials: CallCredentials?

        init {
            this.credentials = credentials
        }

        override fun <RequestT, ResponseT> interceptCall(
            method: io.grpc.MethodDescriptor<RequestT?, ResponseT?>?, callOptions: CallOptions, next: io.grpc.Channel
        ): ClientCall<RequestT?, ResponseT?>? {
            Truth.assertThat(callOptions.getCredentials()).isEqualTo(credentials)
            // Remove the call credentials to allow testing with dummy ones.
            return next.newCall<RequestT?, ResponseT?>(method, callOptions.withCallCredentials(null))
        }
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        // Use a mutable service registry for later registering the service impl for each test case.
        fakeServer =
            InProcessServerBuilder.forName(fakeServerName)
                .fallbackHandlerRegistry(serviceRegistry)
                .directExecutor()
                .build()
                .start()
        Chunker.setDefaultChunkSizeForTesting(1000) // Enough for everything to be one chunk.
        fs = InMemoryFileSystem(com.google.devtools.build.lib.clock.JavaClock(), DigestHashFunction.SHA256)
        execRoot = fs.getPath("/execroot/main")
        execRoot.createDirectoryAndParents()
        fakeFileCache = com.google.devtools.build.lib.remote.FakeActionInputFileCache(execRoot)
        remotePathResolver = RemotePathResolver.createDefault(execRoot)

        val stdout: Path = fs.getPath("/tmp/stdout")
        val stderr: Path = fs.getPath("/tmp/stderr")
        stdout.getParentDirectory().createDirectoryAndParents()
        stderr.getParentDirectory().createDirectoryAndParents()
        outErr = FileOutErr(stdout, stderr)
        val metadata: RequestMetadata? =
            TracingMetadataUtils.buildMetadata(
                "none", "none", Digest.getDefaultInstance().getHash(), null
            )
        context =
            RemoteActionExecutionContext.create(
                < T > mock < T ? > (Spawn::class.java), <T>mock<T?>(SpawnExecutionContext::class.java), metadata)
        retryService =
            com.google.common.util.concurrent.MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1))
    }

    @org.junit.After
    @Throws(java.lang.Exception::class)
    fun tearDown() {
        channels.forEach(ReferenceCountedChannel::release)
        retryService.shutdownNow()
        retryService.awaitTermination(
            com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
        )

        fakeServer.shutdownNow()
        fakeServer.awaitTermination()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVirtualActionInputSupport() {
        val options: RemoteOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(RemoteOptions::class.java)
        val client: RemoteExecutionCache =
            RemoteExecutionCache(
                newClient(options),  /* diskCacheClient= */
                null,  /* symlinkTemplate= */
                null,
                DIGEST_UTIL,  /* chunkingEnabled= */
                false
            )
        val execPath: PathFragment = PathFragment.create("my/exec/path")
        val virtualActionInput: VirtualActionInput? =
            object : VirtualActionInput() {
                val execPathString: String
                    get() = execPath.getPathString()

                val execPath: PathFragment
                    get() = execPath

                @Throws(IOException::class)
                public override fun writeTo(out: java.io.OutputStream) {
                    // Use a fixed seed to ensure deterministic content across multiple calls.
                    val random: Random = Random(123456)
                    // Use primes to exercise chunking logic. Keeping the full output in memory requires at
                    // least 64MB of heap.
                    for (i in 0..1030) {
                        val bytes = ByteArray(65537)
                        random.nextBytes(bytes)
                        out.write(bytes)
                    }
                }
            }
        val merkleTreeComputer: MerkleTreeComputer =
            MerkleTreeComputer(
                DIGEST_UTIL, client, "buildRequestId", "commandId", TestConstants.WORKSPACE_NAME
            )
        val spawn: @NotNull Spawn = SpawnBuilder().withInput(virtualActionInput).build()
        val merkleTree: Uploadable? =
            merkleTreeComputer.buildForSpawn(
                spawn,
                com.google.common.collect.ImmutableSet.of<PathFragment>(),  /* scrubber= */
                null,
                context.getSpawnExecutionContext(),
                remotePathResolver,
                MerkleTreeComputer.BlobPolicy.KEEP
            ) as Uploadable?
        val digest: Digest = DIGEST_UTIL.compute(virtualActionInput)

        // Add a fake CAS that responds saying that the above virtual action input is missing
        serviceRegistry.addService(
            object : ContentAddressableStorageImplBase() {
                public override fun findMissingBlobs(
                    request: FindMissingBlobsRequest?,
                    responseObserver: StreamObserver<FindMissingBlobsResponse?>
                ) {
                    responseObserver.onNext(
                        FindMissingBlobsResponse.newBuilder().addMissingBlobDigests(digest).build()
                    )
                    responseObserver.onCompleted()
                }
            })

        val serviceError: AtomicReference<Throwable?> = AtomicReference<Throwable?>()
        val countingOut: com.google.common.io.CountingOutputStream =
            com.google.common.io.CountingOutputStream(java.io.OutputStream.nullOutputStream())
        val digestOut: DigestOutputStream =
            DigestOutputStream(DigestHashFunction.SHA256.getHashFunction(), countingOut)
        val sawFinalChunk: CountDownLatch = CountDownLatch(1)
        val delayFinalChunk: CountDownLatch = CountDownLatch(1)
        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun write(
                    responseObserver: StreamObserver<WriteResponse?>
                ): StreamObserver<WriteRequest?> {
                    return object : StreamObserver<WriteRequest?> {
                        val firstRequest: AtomicBoolean = AtomicBoolean(true)

                        override fun onNext(request: WriteRequest) {
                            try {
                                if (firstRequest.getAndSet(false)) {
                                    com.google.common.truth.Subject.contains(digest.getHash())
                                }
                                assertThat(request.getWriteOffset()).isEqualTo(countingOut.getCount())
                                try {
                                    request.getData().newInput().transferTo(digestOut)
                                } catch (e: IOException) {
                                    throw java.lang.IllegalStateException(e)
                                }
                                if (countingOut.getCount() == digest.getSizeBytes()) {
                                    sawFinalChunk.countDown()
                                    delayFinalChunk.await()
                                    assertThat(request.getFinishWrite()).isTrue()
                                } else {
                                    assertThat(request.getFinishWrite()).isFalse()
                                }
                            } catch (t: Throwable) {
                                if (t is java.lang.InterruptedException) {
                                    java.lang.Thread.currentThread().interrupt()
                                }
                                serviceError.set(t)
                                responseObserver.onError(io.grpc.Status.INTERNAL.withCause(t).asRuntimeException())
                            }
                        }

                        override fun onCompleted() {
                            responseObserver.onNext(
                                WriteResponse.newBuilder().setCommittedSize(digest.getSizeBytes()).build()
                            )
                            responseObserver.onCompleted()
                        }

                        override fun onError(t: Throwable?) {
                            serviceError.set(t)
                        }
                    }
                }
            })

        java.lang.System.gc()
        val usedMemoryBefore: Long =
            java.lang.Runtime.getRuntime().totalMemory() - java.lang.Runtime.getRuntime().freeMemory()

        val uploadError: AtomicReference<Throwable?> = AtomicReference<Throwable?>()
        val uploadThread: java.lang.Thread =
            java.lang.Thread.ofPlatform()
                .start(
                    java.lang.Runnable {
                        try {
                            client.ensureInputsPresent(
                                context,
                                merkleTree,
                                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* force= */
                                true,
                                remotePathResolver
                            )
                        } catch (e: Throwable) {
                            if (e is java.lang.InterruptedException) {
                                java.lang.Thread.currentThread().interrupt()
                            }
                            uploadError.set(e)
                        }
                    })

        sawFinalChunk.await()
        java.lang.System.gc()
        val usedMemoryAfter: Long =
            java.lang.Runtime.getRuntime().totalMemory() - java.lang.Runtime.getRuntime().freeMemory()

        delayFinalChunk.countDown()
        uploadThread.join()

        if (uploadError.get() != null) {
            throw java.lang.AssertionError(uploadError.get())
        }
        if (serviceError.get() != null) {
            throw java.lang.AssertionError(serviceError.get())
        }
        assertThat(digestOut.digest()).isEqualTo(digest)
        // Ensure that memory usage didn't spike by the size of the virtual input (about 64MB).
        Truth.assertThat(usedMemoryAfter - usedMemoryBefore).isLessThan(10 * 1024 * 1024)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun downloadBlob_cancelled_cancelRequest() {
        // Test that if the download future is cancelled, the download itself is also cancelled.

        // arrange

        val digest: Digest? = DIGEST_UTIL.computeAsUtf8("abcdefg")
        val cancelled: AtomicBoolean = AtomicBoolean()
        // Mock a byte stream whose read method never finish.
        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun read(request: ReadRequest?, responseObserver: StreamObserver<ReadResponse?>) {
                    (responseObserver as ServerCallStreamObserver<ReadResponse?>)
                        .setOnCancelHandler(java.lang.Runnable { cancelled.set(true) })
                }
            })
        val cacheClient: GrpcCacheClient = newClient()

        java.io.ByteArrayOutputStream().use { out ->
            val download: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
                cacheClient.downloadBlob(context, digest, out)
            download.cancel( /* mayInterruptIfRunning= */true)
        }
        // assert
        Truth.assertThat(cancelled.get()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testChunkerResetAfterError() {
        // arrange
        val client: GrpcCacheClient = newClient()
        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun write(
                    responseObserver: StreamObserver<WriteResponse?>
                ): StreamObserver<WriteRequest?> {
                    return object : StreamObserver<WriteRequest?> {
                        override fun onNext(request: WriteRequest?) {
                            responseObserver.onError(io.grpc.Status.DATA_LOSS.asRuntimeException())
                        }

                        override fun onCompleted() {}

                        override fun onError(t: Throwable?) {}
                    }
                }
            })
        val data = ByteArray(20)
        val digest: Digest? = DIGEST_UTIL.compute(data)
        val latch: CountDownLatch = CountDownLatch(1)
        val chunker: Chunker =
            Chunker(
                {
                    object : ByteArrayInputStream(data) {
                        @Throws(IOException::class)
                        override fun close() {
                            super.close()
                            latch.countDown()
                        }
                    }
                },
                data.size,
                2,
                false
            )

        // act
        val t: Throwable =
            org.junit.Assert.assertThrows<ExecutionException>(
                ExecutionException::class.java,
                client.uploadChunker(context, digest, chunker)::get
            )

        // assert
        Truth.assertThat<io.grpc.Status.Code?>(io.grpc.Status.fromThrowable(t.cause).getCode())
            .isEqualTo(io.grpc.Status.Code.DATA_LOSS)
        latch.await()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDownloadEmptyBlob() {
        val client: GrpcCacheClient = newClient()
        val emptyDigest: Digest? = DIGEST_UTIL.compute(ByteArray(0))
        // Will not call the mock Bytestream interface at all.
        Truth.assertThat(downloadBlob(context, client, emptyDigest)).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDownloadBlobSingleChunk() {
        val client: GrpcCacheClient = newClient()
        val digest: Digest = DIGEST_UTIL.computeAsUtf8("abcdefg")
        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun read(request: ReadRequest, responseObserver: StreamObserver<ReadResponse?>) {
                    assertThat(request.getResourceName().contains(digest.getHash())).isTrue()
                    responseObserver.onNext(
                        ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("abcdefg")).build()
                    )
                    responseObserver.onCompleted()
                }
            })
        Truth.assertThat(String(downloadBlob(context, client, digest), java.nio.charset.StandardCharsets.UTF_8))
            .isEqualTo("abcdefg")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDownloadBlobMultipleChunks() {
        val client: GrpcCacheClient = newClient()
        val digest: Digest = DIGEST_UTIL.computeAsUtf8("abcdefg")
        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun read(request: ReadRequest, responseObserver: StreamObserver<ReadResponse?>) {
                    assertThat(request.getResourceName().contains(digest.getHash())).isTrue()
                    responseObserver.onNext(
                        ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("abc")).build()
                    )
                    responseObserver.onNext(
                        ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("def")).build()
                    )
                    responseObserver.onNext(
                        ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("g")).build()
                    )
                    responseObserver.onCompleted()
                }
            })
        Truth.assertThat(String(downloadBlob(context, client, digest), java.nio.charset.StandardCharsets.UTF_8))
            .isEqualTo("abcdefg")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDownloadAllResults() {
        // arrange
        val remoteOptions: RemoteOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(RemoteOptions::class.java)
        val client: GrpcCacheClient = newClient(remoteOptions)
        val combinedCache: CombinedCache =
            CombinedCache(
                client,  /* diskCacheClient= */
                null,  /* symlinkTemplate= */
                null,
                DIGEST_UTIL,  /* chunkingEnabled= */
                false
            )

        val fooDigest: Digest? = DIGEST_UTIL.computeAsUtf8("foo-contents")
        val barDigest: Digest? = DIGEST_UTIL.computeAsUtf8("bar-contents")
        val emptyDigest: Digest? = DIGEST_UTIL.compute(ByteArray(0))
        serviceRegistry.addService(
            FakeImmutableCacheByteStreamImpl(fooDigest, "foo-contents", barDigest, "bar-contents")
        )

        // act
        getFromFuture(combinedCache.downloadFile(context, execRoot.getRelative("a/foo"), fooDigest))
        getFromFuture(
            combinedCache.downloadFile(context, execRoot.getRelative("b/empty"), emptyDigest)
        )
        getFromFuture(combinedCache.downloadFile(context, execRoot.getRelative("a/bar"), barDigest))

        // assert
        assertThat(DIGEST_UTIL.compute(execRoot.getRelative("a/foo"))).isEqualTo(fooDigest)
        assertThat(DIGEST_UTIL.compute(execRoot.getRelative("b/empty"))).isEqualTo(emptyDigest)
        assertThat(DIGEST_UTIL.compute(execRoot.getRelative("a/bar"))).isEqualTo(barDigest)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUploadDirectory() {
        val remoteOptions: RemoteOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(RemoteOptions::class.java)
        val client: GrpcCacheClient = newClient(remoteOptions)
        val combinedCache: CombinedCache =
            CombinedCache(
                client,  /* diskCacheClient= */
                null,  /* symlinkTemplate= */
                null,
                DIGEST_UTIL,  /* chunkingEnabled= */
                false
            )

        val fooDigest: Digest =
            fakeFileCache.createScratchInput(ActionInputHelper.fromPath("a/foo"), "xyz")
        val quxDigest: Digest =
            fakeFileCache.createScratchInput(ActionInputHelper.fromPath("bar/qux"), "abc")
        val barDigest: Digest? =
            fakeFileCache.createScratchInputDirectory(
                ActionInputHelper.fromPath("bar"),
                Tree.newBuilder()
                    .setRoot(
                        Directory.newBuilder()
                            .addFiles(
                                FileNode.newBuilder()
                                    .setIsExecutable(true)
                                    .setName("qux")
                                    .setDigest(quxDigest)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
        val fooFile: Path? = execRoot.getRelative("a/foo")
        val quxFile: Path = execRoot.getRelative("bar/qux")
        quxFile.setExecutable(true)
        val barDir: Path? = execRoot.getRelative("bar")
        serviceRegistry.addService(
            object : ContentAddressableStorageImplBase() {
                public override fun findMissingBlobs(
                    request: FindMissingBlobsRequest,
                    responseObserver: StreamObserver<FindMissingBlobsResponse?>
                ) {
                    assertThat(request.getBlobDigestsList())
                        .containsAtLeast(fooDigest, quxDigest, barDigest)
                    // Nothing is missing.
                    responseObserver.onNext(FindMissingBlobsResponse.getDefaultInstance())
                    responseObserver.onCompleted()
                }
            })
        serviceRegistry.addService(
            object : ActionCacheImplBase() {
                public override fun updateActionResult(
                    request: UpdateActionResultRequest, responseObserver: StreamObserver<ActionResult?>
                ) {
                    responseObserver.onNext(request.getActionResult())
                    responseObserver.onCompleted()
                }
            })

        val result: ActionResult =
            uploadDirectory(combinedCache, com.google.common.collect.ImmutableList.of<Path?>(fooFile, barDir))
        val expectedResult: ActionResult.Builder = ActionResult.newBuilder()
        // output files will have permission 0555 after action execution regardless the current
        // permission
        expectedResult
            .addOutputFilesBuilder()
            .setPath("a/foo")
            .setDigest(fooDigest)
            .setIsExecutable(true)
        expectedResult
            .addOutputDirectoriesBuilder()
            .setPath("bar")
            .setTreeDigest(barDigest)
            .setIsTopologicallySorted(true)
        assertThat(result).isEqualTo(expectedResult.build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUploadDirectoryEmpty() {
        val remoteOptions: RemoteOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(RemoteOptions::class.java)
        val client: GrpcCacheClient = newClient(remoteOptions)
        val combinedCache: CombinedCache =
            CombinedCache(
                client,  /* diskCacheClient= */
                null,  /* symlinkTemplate= */
                null,
                DIGEST_UTIL,  /* chunkingEnabled= */
                false
            )

        val barDigest: Digest? =
            fakeFileCache.createScratchInputDirectory(
                ActionInputHelper.fromPath("bar"),
                Tree.newBuilder().setRoot(Directory.newBuilder().build()).build()
            )
        val barDir: Path = execRoot.getRelative("bar")
        serviceRegistry.addService(
            object : ContentAddressableStorageImplBase() {
                public override fun findMissingBlobs(
                    request: FindMissingBlobsRequest,
                    responseObserver: StreamObserver<FindMissingBlobsResponse?>
                ) {
                    com.google.common.truth.Subject.contains(barDigest)
                    // Nothing is missing.
                    responseObserver.onNext(FindMissingBlobsResponse.getDefaultInstance())
                    responseObserver.onCompleted()
                }
            })
        serviceRegistry.addService(
            object : ActionCacheImplBase() {
                public override fun updateActionResult(
                    request: UpdateActionResultRequest, responseObserver: StreamObserver<ActionResult?>
                ) {
                    responseObserver.onNext(request.getActionResult())
                    responseObserver.onCompleted()
                }
            })

        val result: ActionResult =
            uploadDirectory(combinedCache, com.google.common.collect.ImmutableList.of<Path?>(barDir))
        val expectedResult: ActionResult.Builder = ActionResult.newBuilder()
        expectedResult
            .addOutputDirectoriesBuilder()
            .setPath("bar")
            .setTreeDigest(barDigest)
            .setIsTopologicallySorted(true)
        assertThat(result).isEqualTo(expectedResult.build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUploadDirectoryNested() {
        val remoteOptions: RemoteOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(RemoteOptions::class.java)
        val client: GrpcCacheClient = newClient(remoteOptions)
        val combinedCache: CombinedCache =
            CombinedCache(
                client,  /* diskCacheClient= */
                null,  /* symlinkTemplate= */
                null,
                DIGEST_UTIL,  /* chunkingEnabled= */
                false
            )

        val wobbleDigest: Digest =
            fakeFileCache.createScratchInput(ActionInputHelper.fromPath("bar/test/wobble"), "xyz")
        val quxDigest: Digest =
            fakeFileCache.createScratchInput(ActionInputHelper.fromPath("bar/qux"), "abc")
        val testDirMessage: Directory? =
            Directory.newBuilder()
                .addFiles(
                    FileNode.newBuilder()
                        .setName("wobble")
                        .setDigest(wobbleDigest)
                        .setIsExecutable(true)
                        .build()
                )
                .build()
        val testDigest: Digest? = DIGEST_UTIL.compute(testDirMessage)
        val barTree: Tree? =
            Tree.newBuilder()
                .setRoot(
                    Directory.newBuilder()
                        .addFiles(
                            FileNode.newBuilder()
                                .setName("qux")
                                .setDigest(quxDigest)
                                .setIsExecutable(true)
                        )
                        .addDirectories(
                            DirectoryNode.newBuilder().setName("test").setDigest(testDigest)
                        )
                )
                .addChildren(testDirMessage)
                .build()
        val barDigest: Digest? =
            fakeFileCache.createScratchInputDirectory(ActionInputHelper.fromPath("bar"), barTree)
        val quxFile: Path = execRoot.getRelative("bar/qux")
        quxFile.setExecutable(true)
        val barDir: Path = execRoot.getRelative("bar")
        serviceRegistry.addService(
            object : ContentAddressableStorageImplBase() {
                public override fun findMissingBlobs(
                    request: FindMissingBlobsRequest,
                    responseObserver: StreamObserver<FindMissingBlobsResponse?>
                ) {
                    assertThat(request.getBlobDigestsList())
                        .containsAtLeast(quxDigest, barDigest, wobbleDigest)
                    // Nothing is missing.
                    responseObserver.onNext(FindMissingBlobsResponse.getDefaultInstance())
                    responseObserver.onCompleted()
                }
            })
        serviceRegistry.addService(
            object : ActionCacheImplBase() {
                public override fun updateActionResult(
                    request: UpdateActionResultRequest, responseObserver: StreamObserver<ActionResult?>
                ) {
                    responseObserver.onNext(request.getActionResult())
                    responseObserver.onCompleted()
                }
            })

        val result: ActionResult =
            uploadDirectory(combinedCache, com.google.common.collect.ImmutableList.of<Path?>(barDir))
        val expectedResult: ActionResult.Builder = ActionResult.newBuilder()
        expectedResult
            .addOutputDirectoriesBuilder()
            .setPath("bar")
            .setTreeDigest(barDigest)
            .setIsTopologicallySorted(true)
        assertThat(result).isEqualTo(expectedResult.build())
    }

    @Throws(java.lang.Exception::class)
    private fun upload(
        combinedCache: CombinedCache,
        actionKey: ActionKey?,
        action: Action?,
        command: Command?,
        outputs: MutableList<Path?>?
    ): ActionResult {
        val uploadManifest: UploadManifest =
            UploadManifest.create(
                combinedCache.getRemoteCacheCapabilities(),
                combinedCache.digestUtil,
                remotePathResolver,
                actionKey,
                action,
                command,
                outputs,
                outErr,  /* exitCode= */
                0,  /* startTime= */
                null,  /* wallTimeInMs= */
                0,  /* preserveExecutableBit= */
                false
            )
        return uploadManifest.upload(context, combinedCache, NullEventHandler.INSTANCE)
    }

    @Throws(java.lang.Exception::class)
    private fun uploadDirectory(combinedCache: CombinedCache, outputs: MutableList<Path?>?): ActionResult {
        val action: Action? = Action.getDefaultInstance()
        val actionKey: ActionKey? = DIGEST_UTIL.computeActionKey(action)
        val cmd: Command? = Command.getDefaultInstance()
        return upload(combinedCache, actionKey, action, cmd, outputs)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extraHeaders() {
        val remoteOptions: RemoteOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        remoteOptions.remoteHeaders =
            com.google.common.collect.ImmutableList.of<MutableMap.MutableEntry<String?, String?>?>(
                com.google.common.collect.Maps.immutableEntry<String?, String?>("CommonKey1", "CommonValue1"),
                com.google.common.collect.Maps.immutableEntry<String?, String?>("CommonKey2", "CommonValue2")
            )
        remoteOptions.remoteExecHeaders =
            com.google.common.collect.ImmutableList.of<MutableMap.MutableEntry<String?, String?>?>(
                com.google.common.collect.Maps.immutableEntry<String?, String?>("ExecKey1", "ExecValue1"),
                com.google.common.collect.Maps.immutableEntry<String?, String?>("ExecKey2", "ExecValue2")
            )
        remoteOptions.remoteCacheHeaders =
            com.google.common.collect.ImmutableList.of<MutableMap.MutableEntry<String?, String?>?>(
                com.google.common.collect.Maps.immutableEntry<String?, String?>("CacheKey1", "CacheValue1"),
                com.google.common.collect.Maps.immutableEntry<String?, String?>("CacheKey2", "CacheValue2")
            )

        val interceptor: ServerInterceptor =
            object : ServerInterceptor {
                override fun <ReqT, RespT> interceptCall(
                    call: ServerCall<ReqT?, RespT?>?,
                    metadata: io.grpc.Metadata,
                    next: ServerCallHandler<ReqT?, RespT?>
                ): ServerCall.Listener<ReqT?>? {
                    Truth.assertThat(
                        metadata.get<String?>(
                            io.grpc.Metadata.Key.of<String?>(
                                "CommonKey1",
                                io.grpc.Metadata.ASCII_STRING_MARSHALLER
                            )
                        )
                    )
                        .isEqualTo("CommonValue1")
                    Truth.assertThat(
                        metadata.get<String?>(
                            io.grpc.Metadata.Key.of<String?>(
                                "CommonKey2",
                                io.grpc.Metadata.ASCII_STRING_MARSHALLER
                            )
                        )
                    )
                        .isEqualTo("CommonValue2")
                    Truth.assertThat(
                        metadata.get<String?>(
                            io.grpc.Metadata.Key.of<String?>(
                                "CacheKey1",
                                io.grpc.Metadata.ASCII_STRING_MARSHALLER
                            )
                        )
                    )
                        .isEqualTo("CacheValue1")
                    Truth.assertThat(
                        metadata.get<String?>(
                            io.grpc.Metadata.Key.of<String?>(
                                "CacheKey2",
                                io.grpc.Metadata.ASCII_STRING_MARSHALLER
                            )
                        )
                    )
                        .isEqualTo("CacheValue2")
                    Truth.assertThat(
                        metadata.get<String?>(
                            io.grpc.Metadata.Key.of<String?>(
                                "ExecKey1",
                                io.grpc.Metadata.ASCII_STRING_MARSHALLER
                            )
                        )
                    )
                        .isEqualTo(null)
                    Truth.assertThat(
                        metadata.get<String?>(
                            io.grpc.Metadata.Key.of<String?>(
                                "ExecKey2",
                                io.grpc.Metadata.ASCII_STRING_MARSHALLER
                            )
                        )
                    )
                        .isEqualTo(null)
                    return next.startCall(call, metadata)
                }
            }

        val cas: BindableService =
            object : ContentAddressableStorageImplBase() {
                public override fun findMissingBlobs(
                    request: FindMissingBlobsRequest?,
                    responseObserver: StreamObserver<FindMissingBlobsResponse?>
                ) {
                    responseObserver.onNext(FindMissingBlobsResponse.getDefaultInstance())
                    responseObserver.onCompleted()
                }
            }
        serviceRegistry.addService(cas)
        val actionCache: BindableService =
            object : ActionCacheImplBase() {
                public override fun getActionResult(
                    request: GetActionResultRequest?, responseObserver: StreamObserver<ActionResult?>
                ) {
                    responseObserver.onNext(ActionResult.getDefaultInstance())
                    responseObserver.onCompleted()
                }
            }
        serviceRegistry.addService(ServerInterceptors.intercept(actionCache, interceptor))

        val client: GrpcCacheClient = newClient(remoteOptions)
        val combinedCache: CombinedCache =
            CombinedCache(
                client,  /* diskCacheClient= */
                null,  /* symlinkTemplate= */
                null,
                DIGEST_UTIL,  /* chunkingEnabled= */
                false
            )
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            combinedCache.downloadActionResult(
                context,
                DIGEST_UTIL.asActionKey(DIGEST_UTIL.computeAsUtf8("key")),  /* inlineOutErr= */
                false,  /* inlineOutputFiles= */
                com.google.common.collect.ImmutableSet.of<E?>()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUpload() {
        val remoteOptions: RemoteOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(RemoteOptions::class.java)
        val client: GrpcCacheClient = newClient(remoteOptions)
        val combinedCache: CombinedCache =
            CombinedCache(
                client,  /* diskCacheClient= */
                null,  /* symlinkTemplate= */
                null,
                DIGEST_UTIL,  /* chunkingEnabled= */
                false
            )

        val fooDigest: Digest =
            fakeFileCache.createScratchInput(ActionInputHelper.fromPath("a/foo"), "xyz")
        val barDigest: Digest =
            fakeFileCache.createScratchInput(ActionInputHelper.fromPath("bar"), "x")
        val fooFile: Path? = execRoot.getRelative("a/foo")
        val barFile: Path = execRoot.getRelative("bar")
        barFile.setExecutable(true)
        val command: Command = Command.newBuilder().addOutputFiles("a/foo").build()
        val cmdDigest: Digest? = DIGEST_UTIL.compute(command.toByteArray())
        val action: Action = Action.newBuilder().setCommandDigest(cmdDigest).build()
        val actionDigest: Digest? = DIGEST_UTIL.compute(action.toByteArray())

        outErr.getOutputStream().write("foo out".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        outErr.getOutputStream().close()
        outErr.getErrorStream().write("foo err".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        outErr.getOutputStream().close()

        val stdoutDigest: Digest? = DIGEST_UTIL.compute(outErr.getOutputPath())
        val stderrDigest: Digest? = DIGEST_UTIL.compute(outErr.getErrorPath())

        serviceRegistry.addService(
            object : ContentAddressableStorageImplBase() {
                public override fun findMissingBlobs(
                    request: FindMissingBlobsRequest,
                    responseObserver: StreamObserver<FindMissingBlobsResponse?>
                ) {
                    assertThat(request.getBlobDigestsList())
                        .containsExactly(
                            cmdDigest, actionDigest, fooDigest, barDigest, stdoutDigest, stderrDigest
                        )
                    // Nothing is missing.
                    responseObserver.onNext(FindMissingBlobsResponse.getDefaultInstance())
                    responseObserver.onCompleted()
                }
            })
        serviceRegistry.addService(
            object : ActionCacheImplBase() {
                public override fun updateActionResult(
                    request: UpdateActionResultRequest, responseObserver: StreamObserver<ActionResult?>
                ) {
                    responseObserver.onNext(request.getActionResult())
                    responseObserver.onCompleted()
                }
            })

        val result: ActionResult =
            upload(
                combinedCache,
                DIGEST_UTIL.asActionKey(actionDigest),
                action,
                command,
                com.google.common.collect.ImmutableList.of<Path?>(fooFile, barFile)
            )
        val expectedResult: ActionResult.Builder = ActionResult.newBuilder()
        expectedResult.setStdoutDigest(stdoutDigest)
        expectedResult.setStderrDigest(stderrDigest)
        // output files will have permission 0555 after action execution regardless the current
        // permission
        expectedResult
            .addOutputFilesBuilder()
            .setPath("a/foo")
            .setDigest(fooDigest)
            .setIsExecutable(true)
        expectedResult
            .addOutputFilesBuilder()
            .setPath("bar")
            .setDigest(barDigest)
            .setIsExecutable(true)
        assertThat(result).isEqualTo(expectedResult.build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUploadSplitMissingDigestsCall() {
        val remoteOptions: RemoteOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        remoteOptions.maxOutboundMessageSize = 80 // Enough for one digest, but not two.
        val client: GrpcCacheClient = newClient(remoteOptions)
        val combinedCache: CombinedCache =
            CombinedCache(
                client,  /* diskCacheClient= */
                null,  /* symlinkTemplate= */
                null,
                DIGEST_UTIL,  /* chunkingEnabled= */
                false
            )

        val fooDigest: Digest =
            fakeFileCache.createScratchInput(ActionInputHelper.fromPath("a/foo"), "xyz")
        val barDigest: Digest =
            fakeFileCache.createScratchInput(ActionInputHelper.fromPath("bar"), "x")
        val fooFile: Path? = execRoot.getRelative("a/foo")
        val barFile: Path = execRoot.getRelative("bar")
        barFile.setExecutable(true)
        val command: Command = Command.newBuilder().addOutputFiles("a/foo").build()
        val cmdDigest: Digest? = DIGEST_UTIL.compute(command.toByteArray())
        val action: Action = Action.newBuilder().setCommandDigest(cmdDigest).build()
        val actionDigest: Digest? = DIGEST_UTIL.compute(action.toByteArray())
        val numGetMissingCalls: AtomicInteger = AtomicInteger()
        serviceRegistry.addService(
            object : ContentAddressableStorageImplBase() {
                public override fun findMissingBlobs(
                    request: FindMissingBlobsRequest,
                    responseObserver: StreamObserver<FindMissingBlobsResponse?>
                ) {
                    numGetMissingCalls.incrementAndGet()
                    assertThat(request.getBlobDigestsCount()).isEqualTo(1)
                    // Nothing is missing.
                    responseObserver.onNext(FindMissingBlobsResponse.getDefaultInstance())
                    responseObserver.onCompleted()
                }
            })
        serviceRegistry.addService(
            object : ActionCacheImplBase() {
                public override fun updateActionResult(
                    request: UpdateActionResultRequest, responseObserver: StreamObserver<ActionResult?>
                ) {
                    responseObserver.onNext(request.getActionResult())
                    responseObserver.onCompleted()
                }
            })

        val result: ActionResult =
            upload(
                combinedCache,
                DIGEST_UTIL.asActionKey(actionDigest),
                action,
                command,
                com.google.common.collect.ImmutableList.of<Path?>(fooFile, barFile)
            )
        val expectedResult: ActionResult.Builder = ActionResult.newBuilder()
        // output files will have permission 0555 after action execution regardless the current
        // permission
        expectedResult
            .addOutputFilesBuilder()
            .setPath("a/foo")
            .setDigest(fooDigest)
            .setIsExecutable(true)
        expectedResult
            .addOutputFilesBuilder()
            .setPath("bar")
            .setDigest(barDigest)
            .setIsExecutable(true)
        assertThat(result).isEqualTo(expectedResult.build())
        Truth.assertThat(numGetMissingCalls.get()).isEqualTo(4)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUploadCacheMissesWithRetries() {
        val remoteOptions: RemoteOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(RemoteOptions::class.java)
        val client: GrpcCacheClient = newClient(remoteOptions)
        val combinedCache: CombinedCache =
            CombinedCache(
                client,  /* diskCacheClient= */
                null,  /* symlinkTemplate= */
                null,
                DIGEST_UTIL,  /* chunkingEnabled= */
                false
            )

        val fooDigest: Digest =
            fakeFileCache.createScratchInput(ActionInputHelper.fromPath("a/foo"), "xyz")
        val barDigest: Digest =
            fakeFileCache.createScratchInput(ActionInputHelper.fromPath("bar"), "x")
        val bazDigest: Digest =
            fakeFileCache.createScratchInput(ActionInputHelper.fromPath("baz"), "z")
        val foobarDigest: Digest =
            fakeFileCache.createScratchInput(ActionInputHelper.fromPath("foobar"), "foobar")
        val fooFile: Path? = execRoot.getRelative("a/foo")
        val barFile: Path = execRoot.getRelative("bar")
        val bazFile: Path? = execRoot.getRelative("baz")
        val foobarFile: Path? = execRoot.getRelative("foobar")
        val actionKey: ActionKey? = DIGEST_UTIL.asActionKey(fooDigest) // Could be any key.
        barFile.setExecutable(true)
        serviceRegistry.addService(
            object : ContentAddressableStorageImplBase() {
                private var numErrors = 4

                public override fun findMissingBlobs(
                    request: FindMissingBlobsRequest?,
                    responseObserver: StreamObserver<FindMissingBlobsResponse?>
                ) {
                    if (numErrors-- <= 0) {
                        // All outputs are missing.
                        responseObserver.onNext(
                            FindMissingBlobsResponse.newBuilder()
                                .addMissingBlobDigests(fooDigest)
                                .addMissingBlobDigests(barDigest)
                                .addMissingBlobDigests(bazDigest)
                                .addMissingBlobDigests(foobarDigest)
                                .build()
                        )
                        responseObserver.onCompleted()
                    } else {
                        responseObserver.onError(io.grpc.Status.UNAVAILABLE.asRuntimeException())
                    }
                }
            })
        val rb: ActionResult.Builder = ActionResult.newBuilder()
        // output files will have permission 0555 after action execution regardless the current
        // permission
        rb.addOutputFilesBuilder().setPath("a/foo").setDigest(fooDigest).setIsExecutable(true)
        rb.addOutputFilesBuilder().setPath("bar").setDigest(barDigest).setIsExecutable(true)
        rb.addOutputFilesBuilder().setPath("baz").setDigest(bazDigest).setIsExecutable(true)
        rb.addOutputFilesBuilder().setPath("foobar").setDigest(foobarDigest).setIsExecutable(true)
        val result: ActionResult? = rb.build()
        serviceRegistry.addService(
            object : ActionCacheImplBase() {
                private var numErrors = 4

                public override fun updateActionResult(
                    request: UpdateActionResultRequest?, responseObserver: StreamObserver<ActionResult?>
                ) {
                    assertThat(request)
                        .isEqualTo(
                            UpdateActionResultRequest.newBuilder()
                                .setDigestFunction(DigestFunction.Value.SHA256)
                                .setActionDigest(fooDigest)
                                .setActionResult(result)
                                .build()
                        )
                    if (numErrors-- <= 0) {
                        responseObserver.onNext(result)
                        responseObserver.onCompleted()
                    } else {
                        responseObserver.onError(io.grpc.Status.UNAVAILABLE.asRuntimeException())
                    }
                }
            })
        val mockByteStreamImpl: ByteStreamImplBase? = Mockito.spy<ByteStreamImplBase?>(ByteStreamImplBase::class.java)
        serviceRegistry.addService(mockByteStreamImpl)
        Mockito.doAnswer(
            object : Answer<StreamObserver<WriteRequest?>?>() {
                private var numErrors = 4

                override fun answer(invocation: InvocationOnMock): StreamObserver<WriteRequest?> {
                    val responseObserver: StreamObserver<WriteResponse?> =
                        invocation.getArguments()[0] as StreamObserver<WriteResponse?>
                    return object : StreamObserver<WriteRequest?> {
                        override fun onNext(request: WriteRequest) {
                            numErrors--
                            if (numErrors >= 0) {
                                responseObserver.onError(io.grpc.Status.UNAVAILABLE.asRuntimeException())
                                return
                            }
                            assertThat(request.getFinishWrite()).isTrue()
                            val resourceName: String = request.getResourceName()
                            val dataStr: String? = request.getData().toStringUtf8()
                            var size = 0
                            if (resourceName.contains(fooDigest.getHash())) {
                                Truth.assertThat(dataStr).isEqualTo("xyz")
                                size = 3
                            } else if (resourceName.contains(barDigest.getHash())) {
                                Truth.assertThat(dataStr).isEqualTo("x")
                                size = 1
                            } else if (resourceName.contains(bazDigest.getHash())) {
                                Truth.assertThat(dataStr).isEqualTo("z")
                                size = 1
                            } else if (resourceName.contains(foobarDigest.getHash())) {
                                responseObserver.onError(io.grpc.Status.ALREADY_EXISTS.asRuntimeException())
                                return
                            } else {
                                org.junit.Assert.fail("Unexpected resource name in upload: " + resourceName)
                            }
                            responseObserver.onNext(
                                WriteResponse.newBuilder().setCommittedSize(size).build()
                            )
                        }

                        override fun onCompleted() {
                            responseObserver.onCompleted()
                        }

                        override fun onError(t: Throwable?) {
                            org.junit.Assert.fail("An error occurred: " + t)
                        }
                    }
                }
            })
            .`when`<Any?>(mockByteStreamImpl)
            .write(ArgumentMatchers.any<T?>())
        Mockito.doAnswer(
            AdditionalAnswers.answerVoid<QueryWriteStatusRequest?, StreamObserver<QueryWriteStatusResponse?>?>(
                VoidAnswer2 { request: QueryWriteStatusRequest?, responseObserver: StreamObserver<QueryWriteStatusResponse?>? ->
                    responseObserver.onNext(
                        QueryWriteStatusResponse.newBuilder()
                            .setCommittedSize(0)
                            .setComplete(false)
                            .build()
                    )
                    responseObserver.onCompleted()
                })
        )
            .`when`<Any?>(mockByteStreamImpl)
            .queryWriteStatus(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
        val unused: ActionResult =
            upload(
                combinedCache,
                actionKey,
                Action.getDefaultInstance(),
                Command.getDefaultInstance(),
                com.google.common.collect.ImmutableList.of<Path?>(fooFile, barFile, bazFile, foobarFile)
            )
        // 4 times for the errors, 4 times for the successful uploads.
        Mockito.verify<Any?>(mockByteStreamImpl, Mockito.times(8))
            .write(ArgumentMatchers.any<StreamObserver<WriteResponse?>?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetCachedActionResultWithRetries() {
        val client: GrpcCacheClient = newClient()
        val actionKey: ActionKey? = DIGEST_UTIL.asActionKey(DIGEST_UTIL.computeAsUtf8("key"))
        serviceRegistry.addService(
            object : ActionCacheImplBase() {
                private var numErrors = 4

                public override fun getActionResult(
                    request: GetActionResultRequest?, responseObserver: StreamObserver<ActionResult?>
                ) {
                    responseObserver.onError(
                        (if (numErrors-- <= 0) io.grpc.Status.NOT_FOUND else io.grpc.Status.UNAVAILABLE).asRuntimeException()
                    )
                }
            })
        assertThat(
            getFromFuture(
                client.downloadActionResult(
                    context,
                    actionKey,  /* inlineOutErr= */
                    false,  /* inlineOutputFiles= */
                    com.google.common.collect.ImmutableSet.of<E?>()
                )
            )
        )
            .isNull()
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun downloadBlobIsRetriedWithProgress() {
        val mockBackoff: Backoff? = Mockito.mock<Backoff?>(Backoff::class.java)
        val client: GrpcCacheClient = newClient(
            com.google.devtools.common.options.Options.getDefaults<O?>(RemoteOptions::class.java),
            java.util.function.Supplier { mockBackoff })
        val digest: Digest = DIGEST_UTIL.computeAsUtf8("abcdefg")
        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun read(request: ReadRequest, responseObserver: StreamObserver<ReadResponse?>) {
                    assertThat(request.getResourceName().contains(digest.getHash())).isTrue()
                    var data: ByteString? = ByteString.copyFromUtf8("abcdefg")
                    val off = request.getReadOffset() as Int
                    if (off == 0) {
                        data = data.substring(0, 1)
                    } else {
                        data = data.substring(off)
                    }
                    responseObserver.onNext(ReadResponse.newBuilder().setData(data).build())
                    if (off == 0) {
                        responseObserver.onError(io.grpc.Status.DEADLINE_EXCEEDED.asException())
                    } else {
                        responseObserver.onCompleted()
                    }
                }
            })
        Truth.assertThat(String(downloadBlob(context, client, digest), java.nio.charset.StandardCharsets.UTF_8))
            .isEqualTo("abcdefg")
        Mockito.verify<Any?>(mockBackoff, Mockito.never())
            .nextDelayMillis(ArgumentMatchers.any<T?>(java.lang.Exception::class.java))
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun downloadBlobDoesNotRetryZeroLengthRequests() {
        val mockBackoff: Backoff? = Mockito.mock<Backoff?>(Backoff::class.java)
        val client: GrpcCacheClient = newClient(
            com.google.devtools.common.options.Options.getDefaults<O?>(RemoteOptions::class.java),
            java.util.function.Supplier { mockBackoff })
        val digest: Digest = DIGEST_UTIL.computeAsUtf8("abcdefg")
        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun read(request: ReadRequest, responseObserver: StreamObserver<ReadResponse?>) {
                    com.google.common.truth.Subject.contains(digest.getHash())
                    assertThat(request.getReadOffset()).isEqualTo(0)
                    val data: ByteString = ByteString.copyFromUtf8("abcdefg")
                    responseObserver.onNext(ReadResponse.newBuilder().setData(data).build())
                    responseObserver.onError(io.grpc.Status.INTERNAL.asException())
                }
            })
        Truth.assertThat(String(downloadBlob(context, client, digest), java.nio.charset.StandardCharsets.UTF_8))
            .isEqualTo("abcdefg")
        Mockito.verify<Any?>(mockBackoff, Mockito.never())
            .nextDelayMillis(ArgumentMatchers.any<T?>(java.lang.Exception::class.java))
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun downloadBlobPassesThroughDeadlineExceededWithoutProgress() {
        val mockBackoff: Backoff = Mockito.mock<Backoff>(Backoff::class.java)
        Mockito.`when`<T?>(mockBackoff.nextDelayMillis(ArgumentMatchers.any<T?>(java.lang.Exception::class.java)))
            .thenReturn(-1L)
        val client: GrpcCacheClient = newClient(
            com.google.devtools.common.options.Options.getDefaults<O?>(RemoteOptions::class.java),
            java.util.function.Supplier { mockBackoff })
        val digest: Digest = DIGEST_UTIL.computeAsUtf8("abcdefg")
        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun read(request: ReadRequest, responseObserver: StreamObserver<ReadResponse?>) {
                    assertThat(request.getResourceName().contains(digest.getHash())).isTrue()
                    val data: ByteString = ByteString.copyFromUtf8("abcdefg")
                    if (request.getReadOffset() === 0) {
                        responseObserver.onNext(
                            ReadResponse.newBuilder().setData(data.substring(0, 2)).build()
                        )
                    }
                    responseObserver.onError(io.grpc.Status.DEADLINE_EXCEEDED.asException())
                }
            })
        val e: IOException = org.junit.Assert.assertThrows<IOException>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { downloadBlob(context, client, digest) })
        val st: io.grpc.Status = io.grpc.Status.fromThrowable(e)
        Truth.assertThat<io.grpc.Status.Code?>(st.getCode()).isEqualTo(io.grpc.Status.Code.DEADLINE_EXCEEDED)
        Mockito.verify<Any?>(mockBackoff, Mockito.times(1))
            .nextDelayMillis(ArgumentMatchers.any<T?>(java.lang.Exception::class.java))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDownloadFailsOnDigestMismatch() {
        // Test that the download fails when a blob/file has a different content hash than expected.

        val client: GrpcCacheClient = newClient()
        val digest: Digest = DIGEST_UTIL.computeAsUtf8("foo")
        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun read(request: ReadRequest?, responseObserver: StreamObserver<ReadResponse?>) {
                    val data: ByteString = ByteString.copyFromUtf8("bar")
                    responseObserver.onNext(ReadResponse.newBuilder().setData(data).build())
                    responseObserver.onCompleted()
                }
            })
        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { downloadBlob(context, client, digest) })
        Truth.assertThat(e).hasMessageThat().contains(digest.getHash())
        Truth.assertThat(e).hasMessageThat().contains(DIGEST_UTIL.computeAsUtf8("bar").getHash())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDisablingDigestVerification() {
        // Test that when digest verification is disabled a corrupted download works.

        val remoteOptions: RemoteOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        remoteOptions.remoteVerifyDownloads = false

        val client: GrpcCacheClient = newClient(remoteOptions)
        val digest: Digest? = DIGEST_UTIL.computeAsUtf8("foo")
        val downloadContents: ByteString = ByteString.copyFromUtf8("bar")
        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun read(request: ReadRequest?, responseObserver: StreamObserver<ReadResponse?>) {
                    responseObserver.onNext(ReadResponse.newBuilder().setData(downloadContents).build())
                    responseObserver.onCompleted()
                }
            })

        Truth.assertThat(downloadBlob(context, client, digest)).isEqualTo(downloadContents.toByteArray())
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun compressedDownloadBlobIsRetriedWithProgress() {
        val options: RemoteOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        options.cacheCompression = true
        options.cacheCompressionThreshold = 0
        val client: GrpcCacheClient = newClient(options)
        val digest: Digest = DIGEST_UTIL.computeAsUtf8("abcdefg")
        val chunk1: ByteString? =
            ByteString.copyFrom(Zstd.compress("abc".toByteArray(java.nio.charset.StandardCharsets.UTF_8)))
        val chunk2: ByteString? =
            ByteString.copyFrom(Zstd.compress("def".toByteArray(java.nio.charset.StandardCharsets.UTF_8)))
        val chunk3: ByteString? =
            ByteString.copyFrom(Zstd.compress("g".toByteArray(java.nio.charset.StandardCharsets.UTF_8)))
        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                private var first = true

                public override fun read(request: ReadRequest, responseObserver: StreamObserver<ReadResponse?>) {
                    com.google.common.truth.Subject.contains(digest.getHash())
                    if (first) {
                        first = false
                        responseObserver.onError(io.grpc.Status.DEADLINE_EXCEEDED.asException())
                        return
                    }
                    when (java.lang.Math.toIntExact(request.getReadOffset())) {
                        0 -> responseObserver.onNext(ReadResponse.newBuilder().setData(chunk1).build())
                        3 -> responseObserver.onNext(ReadResponse.newBuilder().setData(chunk2).build())
                        6 -> {
                            responseObserver.onNext(ReadResponse.newBuilder().setData(chunk3).build())
                            responseObserver.onCompleted()
                            return
                        }

                        else -> throw java.lang.IllegalStateException("unexpected offset " + request.getReadOffset())
                    }
                    responseObserver.onError(io.grpc.Status.DEADLINE_EXCEEDED.asException())
                }
            })
        Truth.assertThat(String(downloadBlob(context, client, digest), java.nio.charset.StandardCharsets.UTF_8))
            .isEqualTo("abcdefg")
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun testCompressedDownload(@TestParameter overThreshold: Boolean) {
        val options: RemoteOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        options.cacheCompression = true
        options.cacheCompressionThreshold = 100
        val client: GrpcCacheClient = newClient(options)
        val data: ByteArray? =
            if (overThreshold) "0123456789".repeat(10)
                .toByteArray(java.nio.charset.StandardCharsets.UTF_8) else "0123456789".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        val digest: Digest = DIGEST_UTIL.compute(data)
        val bytes: ByteArray = (if (overThreshold) Zstd.compress(data) else data)!!

        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun read(request: ReadRequest, responseObserver: StreamObserver<ReadResponse?>) {
                    com.google.common.truth.Subject.contains(digest.getHash())
                    if (overThreshold) {
                        com.google.common.truth.Subject.contains("compressed-blobs/zstd")
                    } else {
                        assertThat(request.getResourceName()).doesNotContain("compressed-blobs/zstd")
                    }
                    responseObserver.onNext(
                        ReadResponse.newBuilder()
                            .setData(ByteString.copyFrom(bytes.copyOf(bytes.size / 3)))
                            .build()
                    )
                    responseObserver.onNext(
                        ReadResponse.newBuilder()
                            .setData(
                                ByteString.copyFrom(
                                    java.util.Arrays.copyOfRange(bytes, bytes.size / 3, bytes.size / 3 * 2)
                                )
                            )
                            .build()
                    )
                    responseObserver.onNext(
                        ReadResponse.newBuilder()
                            .setData(
                                ByteString.copyFrom(
                                    java.util.Arrays.copyOfRange(bytes, bytes.size / 3 * 2, bytes.size)
                                )
                            )
                            .build()
                    )
                    responseObserver.onCompleted()
                }
            })
        Truth.assertThat(downloadBlob(context, client, digest)).isEqualTo(data)
    }

    @get:org.junit.Test
    val isRemoteCacheOptionsWhenGrpcEnabled: Unit
        get() {
            val options: RemoteOptions =
                com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
            options.remoteCache = "grpc://some-host.com"

            assertThat(GrpcCacheClient.isRemoteCacheOptions(options)).isTrue()
        }

    @get:org.junit.Test
    val isRemoteCacheOptionsWhenGrpcEnabledUpperCase: Unit
        get() {
            val options: RemoteOptions =
                com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
            options.remoteCache = "GRPC://some-host.com"

            assertThat(GrpcCacheClient.isRemoteCacheOptions(options)).isTrue()
        }

    @get:org.junit.Test
    val isRemoteCacheOptionsWhenDefaultRemoteCacheEnabledForLocalhost: Unit
        get() {
            val options: RemoteOptions =
                com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
            options.remoteCache = "localhost:1234"

            assertThat(GrpcCacheClient.isRemoteCacheOptions(options)).isTrue()
        }

    @get:org.junit.Test
    val isRemoteCacheOptionsWhenDefaultRemoteCacheEnabled: Unit
        get() {
            val options: RemoteOptions =
                com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
            options.remoteCache = "some-host.com:1234"

            assertThat(GrpcCacheClient.isRemoteCacheOptions(options)).isTrue()
        }

    @get:org.junit.Test
    val isRemoteCacheOptionsWhenHttpEnabled: Unit
        get() {
            val options: RemoteOptions =
                com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
            options.remoteCache = "http://some-host.com"

            assertThat(GrpcCacheClient.isRemoteCacheOptions(options)).isFalse()
        }

    @get:org.junit.Test
    val isRemoteCacheOptionsWhenHttpEnabledWithUpperCase: Unit
        get() {
            val options: RemoteOptions =
                com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
            options.remoteCache = "HTTP://some-host.com"

            assertThat(GrpcCacheClient.isRemoteCacheOptions(options)).isFalse()
        }

    @get:org.junit.Test
    val isRemoteCacheOptionsWhenHttpsEnabled: Unit
        get() {
            val options: RemoteOptions =
                com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
            options.remoteCache = "https://some-host.com"

            assertThat(GrpcCacheClient.isRemoteCacheOptions(options)).isFalse()
        }

    @get:org.junit.Test
    val isRemoteCacheOptionsWhenUnknownScheme: Unit
        get() {
            val options: RemoteOptions =
                com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
            options.remoteCache = "grp://some-host.com"

            // TODO(ishikhman): add proper vaildation and flip to false
            assertThat(GrpcCacheClient.isRemoteCacheOptions(options)).isTrue()
        }

    @get:org.junit.Test
    val isRemoteCacheOptionsWhenUnknownSchemeStartsAsGrpc: Unit
        get() {
            val options: RemoteOptions =
                com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
            options.remoteCache = "grpcsss://some-host.com"

            // TODO(ishikhman): add proper vaildation and flip to false
            assertThat(GrpcCacheClient.isRemoteCacheOptions(options)).isTrue()
        }

    @get:org.junit.Test
    val isRemoteCacheOptionsWhenEmptyCacheProvided: Unit
        get() {
            val options: RemoteOptions =
                com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
            options.remoteCache = ""

            assertThat(GrpcCacheClient.isRemoteCacheOptions(options)).isFalse()
        }

    @get:org.junit.Test
    val isRemoteCacheOptionsWhenRemoteCacheDisabled: Unit
        get() {
            val options: RemoteOptions? =
                com.google.devtools.common.options.Options.getDefaults<O?>(RemoteOptions::class.java)

            assertThat(GrpcCacheClient.isRemoteCacheOptions(options)).isFalse()
        }

    companion object {
        private val DIGEST_UTIL: DigestUtil = DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256)

        @Throws(IOException::class, java.lang.InterruptedException::class)
        private fun downloadBlob(
            context: RemoteActionExecutionContext?, cacheClient: GrpcCacheClient, digest: Digest?
        ): ByteArray? {
            java.io.ByteArrayOutputStream().use { out ->
                getFromFuture(cacheClient.downloadBlob(context, digest, out))
                return out.toByteArray()
            }
        }
    }
}
