/** Copyright 2017 The Bazel Authors. All rights reserved. */ //
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

import com.google.devtools.build.lib.remote.GrpcCacheClient.getResourceName

/** Tests for [RemoteSpawnRunner] in combination with [GrpcRemoteExecutor].  */
@RunWith(JUnit4::class)
class RemoteSpawnRunnerWithGrpcRemoteExecutorTest {
    @org.junit.Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    @org.mockito.Mock
    private val remoteOutputChecker: RemoteOutputChecker? = null // download nothing by default.

    private val reporter: com.google.devtools.build.lib.events.Reporter =
        com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus())
    private val serviceRegistry: MutableHandlerRegistry = MutableHandlerRegistry()
    private var fs: FileSystem? = null
    private var execRoot: Path? = null
    private var artifactRoot: ArtifactRoot? = null
    private var tempPathGenerator: TempPathGenerator? = null
    private var logDir: Path? = null
    private var simpleSpawn: SimpleSpawn? = null
    private var fakeFileCache: com.google.devtools.build.lib.remote.FakeActionInputFileCache? = null
    private var inputDigest: Digest? = null
    private var cmdDigest: Digest? = null
    private var command: Command? = null
    private var client: RemoteSpawnRunner? = null
    private var outErr: FileOutErr? = null
    private var remoteOptions: RemoteOptions? = null
    private var remoteCache: RemoteExecutionCache? = null
    private var fakeServer: io.grpc.Server? = null
    private var retryService: com.google.common.util.concurrent.ListeningScheduledExecutorService? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        val fakeServerName = "fake server for " + getClass()
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
        artifactRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "outputs")
        artifactRoot.getRoot().asPath().createDirectoryAndParents()
        tempPathGenerator = TempPathGenerator(fs.getPath("/execroot/_tmp/actions/remote"))
        logDir = fs.getPath("/server-logs")
        fakeFileCache = com.google.devtools.build.lib.remote.FakeActionInputFileCache(execRoot)
        simpleSpawn =
            SimpleSpawn(
                FakeOwner("Mnemonic", "Progress Message", "//dummy:label"),
                com.google.common.collect.ImmutableList.of<E?>("/bin/echo", "Hi!"),
                com.google.common.collect.ImmutableMap.of<K?, V?>("VARIABLE", "value"),  /* executionInfo= */
                com.google.common.collect.ImmutableMap.of<String?, String?>(),  /* inputs= */
                NestedSetBuilder.create(
                    Order.STABLE_ORDER, ActionInputHelper.fromPath("input")
                ),  /* tools= */
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),  /* outputs= */
                com.google.common.collect.ImmutableSet.of<E?>(
                    object : ActionInput() {
                        val execPathString: String
                            get() = "foo"

                        val isDirectory: Boolean
                            get() = false

                        val isSymlink: Boolean
                            get() = false

                        val execPath: PathFragment
                            get() = PathFragment.create("foo")
                    },
                    object : ActionInput() {
                        val execPathString: String
                            get() = "bar"

                        val isDirectory: Boolean
                            get() = false

                        val isSymlink: Boolean
                            get() = false

                        val execPath: PathFragment
                            get() = PathFragment.create("bar")
                    }),  /* mandatoryOutputs= */
                com.google.common.collect.ImmutableSet.of<E?>(),
                ResourceSet.ZERO
            )

        val stdout: Path = fs.getPath("/tmp/stdout")
        val stderr: Path = fs.getPath("/tmp/stderr")
        stdout.getParentDirectory().createDirectoryAndParents()
        stderr.getParentDirectory().createDirectoryAndParents()
        outErr = FileOutErr(stdout, stderr)
        remoteOptions = com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)

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

        retryService =
            com.google.common.util.concurrent.MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1))
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { ExponentialBackoff(remoteOptions) },
                RemoteRetrier.GRPC_RESULT_CLASSIFIER,
                retryService
            )
        val channel: ReferenceCountedChannel =
            ReferenceCountedChannel(
                object : ChannelConnectionWithServerCapabilitiesFactory() {
                    public override fun create(): Single<ChannelConnectionWithServerCapabilities?>? {
                        val ch: ManagedChannel? =
                            InProcessChannelBuilder.forName(fakeServerName)
                                .intercept(TracingMetadataUtils.newExecHeadersInterceptor(remoteOptions))
                                .directExecutor()
                                .build()
                        val caps: ServerCapabilities =
                            ServerCapabilities.newBuilder()
                                .setLowApiVersion(ApiVersion.low.toSemVer())
                                .setHighApiVersion(ApiVersion.high.toSemVer())
                                .setExecutionCapabilities(
                                    ExecutionCapabilities.newBuilder().setExecEnabled(true).build()
                                )
                                .build()
                        return Single.just<ChannelConnectionWithServerCapabilities?>(
                            ChannelConnectionWithServerCapabilities(ch, Single.just<T?>(caps))
                        )
                    }

                    public override fun maxConcurrency(): Int {
                        return 100
                    }
                })

        val executor: GrpcRemoteExecutor =
            GrpcRemoteExecutor(channel.retain(), CallCredentialsProvider.NO_CREDENTIALS, retrier)
        val callCredentialsProvider: CallCredentialsProvider? =
            GoogleAuthUtils.newCallCredentialsProvider(null)
        val cacheProtocol: GrpcCacheClient =
            GrpcCacheClient(
                channel.retain(), callCredentialsProvider, remoteOptions, retrier, DIGEST_UTIL
            )
        remoteCache =
            RemoteExecutionCache(
                cacheProtocol,  /* diskCacheClient= */
                null,  /* symlinkTemplate= */
                null,
                DIGEST_UTIL,  /* chunkingEnabled= */
                false
            )
        val remoteExecutionService: RemoteExecutionService =
            RemoteExecutionService(
                reporter,  /* verboseFailures= */
                true,
                execRoot,
                RemotePathResolver.createDefault(execRoot),
                "build-req-id",
                "command-id",
                TestConstants.WORKSPACE_NAME,
                DIGEST_UTIL,
                remoteOptions,
                com.google.devtools.common.options.Options.getDefaults<O?>(ExecutionOptions::class.java),
                remoteCache,
                executor,
                tempPathGenerator,  /* captureCorruptedOutputsDir= */
                null,
                remoteOutputChecker,
                TODO("Cannot convert element")
            )<T> Mockito . mock < OutputService ? > (OutputService::class.java)
        com.google.common.collect.Sets.newConcurrentHashSet<E?>()

        client =
            RemoteSpawnRunner(
                remoteOptions,  /* verboseFailures= */
                true,  /* cmdlineReporter= */
                null,
                retryService,
                logDir,
                remoteExecutionService,
                DIGEST_UTIL
            )

        inputDigest =
            fakeFileCache.createScratchInput(simpleSpawn.getInputFiles().getSingleton(), "xyz")
        command =
            Command.newBuilder()
                .addAllArguments(
                    com.google.common.collect.ImmutableList.of<E?>(
                        if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS) "\\bin\\echo" else "/bin/echo",
                        "Hi!"
                    )
                )
                .addEnvironmentVariables(
                    Command.EnvironmentVariable.newBuilder()
                        .setName("VARIABLE")
                        .setValue("value")
                        .build()
                )
                .addAllOutputPaths(com.google.common.collect.ImmutableList.of<E?>("bar", "foo"))
                .build()
        cmdDigest = DIGEST_UTIL.compute(command)
        channel.release()
    }

    @org.junit.After
    @Throws(java.lang.Exception::class)
    fun tearDown() {
        retryService.shutdownNow()
        retryService.awaitTermination(
            com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
        )

        fakeServer.shutdownNow()
        fakeServer.awaitTermination()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cacheHit() {
        serviceRegistry.addService(
            object : ActionCacheImplBase() {
                public override fun getActionResult(
                    request: GetActionResultRequest?, responseObserver: StreamObserver<ActionResult?>
                ) {
                    responseObserver.onNext(ActionResult.getDefaultInstance())
                    responseObserver.onCompleted()
                }
            })

        val context: FakeSpawnExecutionContext = getSpawnContext(simpleSpawn)

        val result: SpawnResult = client.exec(simpleSpawn, context)
        assertThat(result.setupSuccess()).isTrue()
        assertThat(result.isCacheHit()).isTrue()
        assertThat(result.exitCode()).isEqualTo(0)
        assertThat(outErr.hasRecordedOutput()).isFalse()
        assertThat(outErr.hasRecordedStderr()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun failedAction() {
        serviceRegistry.addService(
            object : ActionCacheImplBase() {
                public override fun getActionResult(
                    request: GetActionResultRequest?, responseObserver: StreamObserver<ActionResult?>
                ) {
                    responseObserver.onError(io.grpc.Status.NOT_FOUND.asRuntimeException())
                }
            })
        val actionResult: ActionResult? = ActionResult.newBuilder().setExitCode(1).build()
        serviceRegistry.addService(
            object : ExecutionImplBase() {
                public override fun execute(request: ExecuteRequest?, responseObserver: StreamObserver<Operation?>) {
                    responseObserver.onNext(
                        Operation.newBuilder()
                            .setDone(true)
                            .setResponse(
                                Any.pack(ExecuteResponse.newBuilder().setResult(actionResult).build())
                            )
                            .build()
                    )
                    responseObserver.onCompleted()
                }
            })
        serviceRegistry.addService(
            object : ContentAddressableStorageImplBase() {
                public override fun findMissingBlobs(
                    request: FindMissingBlobsRequest?,
                    responseObserver: StreamObserver<FindMissingBlobsResponse?>
                ) {
                    responseObserver.onNext(FindMissingBlobsResponse.getDefaultInstance())
                    responseObserver.onCompleted()
                }
            })

        val context: FakeSpawnExecutionContext = getSpawnContext(simpleSpawn)
        val result: SpawnResult = client.exec(simpleSpawn, context)
        assertThat(result.exitCode()).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noOutputs() {
        serviceRegistry.addService(
            object : ActionCacheImplBase() {
                public override fun getActionResult(
                    request: GetActionResultRequest?, responseObserver: StreamObserver<ActionResult?>
                ) {
                    responseObserver.onError(io.grpc.Status.NOT_FOUND.asRuntimeException())
                }
            })
        val actionResult: ActionResult? = ActionResult.getDefaultInstance()
        serviceRegistry.addService(
            object : ExecutionImplBase() {
                public override fun execute(request: ExecuteRequest?, responseObserver: StreamObserver<Operation?>) {
                    responseObserver.onNext(
                        Operation.newBuilder()
                            .setDone(true)
                            .setResponse(
                                Any.pack(ExecuteResponse.newBuilder().setResult(actionResult).build())
                            )
                            .build()
                    )
                    responseObserver.onCompleted()
                }
            })
        serviceRegistry.addService(
            object : ContentAddressableStorageImplBase() {
                public override fun findMissingBlobs(
                    request: FindMissingBlobsRequest?,
                    responseObserver: StreamObserver<FindMissingBlobsResponse?>
                ) {
                    responseObserver.onNext(FindMissingBlobsResponse.getDefaultInstance())
                    responseObserver.onCompleted()
                }
            })

        val context: FakeSpawnExecutionContext = getSpawnContext(simpleSpawn)
        client.exec(simpleSpawn, context)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cacheHitWithOutput() {
        val stdOutDigest: Digest? = DIGEST_UTIL.computeAsUtf8("stdout")
        val stdErrDigest: Digest? = DIGEST_UTIL.computeAsUtf8("stderr")
        serviceRegistry.addService(
            object : ActionCacheImplBase() {
                public override fun getActionResult(
                    request: GetActionResultRequest?, responseObserver: StreamObserver<ActionResult?>
                ) {
                    responseObserver.onNext(
                        ActionResult.newBuilder()
                            .addOutputFiles(DUMMY_OUTPUT)
                            .setStdoutDigest(stdOutDigest)
                            .setStderrDigest(stdErrDigest)
                            .build()
                    )
                    responseObserver.onCompleted()
                }
            })
        serviceRegistry.addService(
            FakeImmutableCacheByteStreamImpl(stdOutDigest, "stdout", stdErrDigest, "stderr")
        )

        val context: FakeSpawnExecutionContext = getSpawnContext(simpleSpawn)
        val result: SpawnResult = client.exec(simpleSpawn, context)

        assertThat(result.setupSuccess()).isTrue()
        assertThat(result.exitCode()).isEqualTo(0)
        assertThat(result.isCacheHit()).isTrue()
        assertThat(outErr.outAsLatin1()).isEqualTo("stdout")
        assertThat(outErr.errAsLatin1()).isEqualTo("stderr")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cacheHitWithInlineOutput() {
        serviceRegistry.addService(
            object : ActionCacheImplBase() {
                public override fun getActionResult(
                    request: GetActionResultRequest?, responseObserver: StreamObserver<ActionResult?>
                ) {
                    responseObserver.onNext(
                        ActionResult.newBuilder()
                            .addOutputFiles(DUMMY_OUTPUT)
                            .setStdoutRaw(ByteString.copyFromUtf8("stdout"))
                            .setStderrRaw(ByteString.copyFromUtf8("stderr"))
                            .build()
                    )
                    responseObserver.onCompleted()
                }
            })

        val context: FakeSpawnExecutionContext = getSpawnContext(simpleSpawn)
        val result: SpawnResult = client.exec(simpleSpawn, context)

        assertThat(result.setupSuccess()).isTrue()
        assertThat(result.exitCode()).isEqualTo(0)
        assertThat(result.isCacheHit()).isTrue()
        assertThat(outErr.outAsLatin1()).isEqualTo("stdout")
        assertThat(outErr.errAsLatin1()).isEqualTo("stderr")
    }

    /** Capture the request headers from a client. Useful for testing metadata propagation.  */
    private class RequestHeadersValidator : ServerInterceptor {
        override fun <ReqT, RespT> interceptCall(
            call: ServerCall<ReqT?, RespT?>?, headers: io.grpc.Metadata, next: ServerCallHandler<ReqT?, RespT?>
        ): ServerCall.Listener<ReqT?>? {
            val meta: RequestMetadata? = headers.get<RequestMetadata?>(TracingMetadataUtils.METADATA_KEY)
            assertThat(meta.getCorrelatedInvocationsId()).isEqualTo("build-req-id")
            assertThat(meta.getToolInvocationId()).isEqualTo("command-id")
            assertThat(meta.getActionId()).isNotEmpty()
            assertThat(meta.getToolDetails().getToolName()).isEqualTo("bazel")
            assertThat(meta.getToolDetails().getToolVersion())
                .isEqualTo(BlazeVersionInfo.instance().getVersion())
            return next.startCall(call, headers)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extraHeaders() {
        val actionCache: BindableService =
            object : ActionCacheImplBase() {
                public override fun getActionResult(
                    request: GetActionResultRequest?, responseObserver: StreamObserver<ActionResult?>
                ) {
                    responseObserver.onError(io.grpc.Status.NOT_FOUND.asRuntimeException())
                }
            }
        serviceRegistry.addService(actionCache)

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

        val execService: BindableService =
            object : ExecutionImplBase() {
                public override fun execute(request: ExecuteRequest?, responseObserver: StreamObserver<Operation?>) {
                    responseObserver.onNext(Operation.getDefaultInstance())
                    responseObserver.onCompleted()
                }
            }
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
                                "ExecKey1",
                                io.grpc.Metadata.ASCII_STRING_MARSHALLER
                            )
                        )
                    )
                        .isEqualTo("ExecValue1")
                    Truth.assertThat(
                        metadata.get<String?>(
                            io.grpc.Metadata.Key.of<String?>(
                                "ExecKey2",
                                io.grpc.Metadata.ASCII_STRING_MARSHALLER
                            )
                        )
                    )
                        .isEqualTo("ExecValue2")
                    Truth.assertThat(
                        metadata.get<String?>(
                            io.grpc.Metadata.Key.of<String?>(
                                "CacheKey1",
                                io.grpc.Metadata.ASCII_STRING_MARSHALLER
                            )
                        )
                    )
                        .isEqualTo(null)
                    Truth.assertThat(
                        metadata.get<String?>(
                            io.grpc.Metadata.Key.of<String?>(
                                "CacheKey2",
                                io.grpc.Metadata.ASCII_STRING_MARSHALLER
                            )
                        )
                    )
                        .isEqualTo(null)
                    return next.startCall(call, metadata)
                }
            }
        serviceRegistry.addService(ServerInterceptors.intercept(execService, interceptor))

        val context: FakeSpawnExecutionContext = getSpawnContext(simpleSpawn)
        client.exec(simpleSpawn, context)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun remotelyExecute() {
        val actionCache: BindableService =
            object : ActionCacheImplBase() {
                public override fun getActionResult(
                    request: GetActionResultRequest?, responseObserver: StreamObserver<ActionResult?>
                ) {
                    responseObserver.onError(io.grpc.Status.NOT_FOUND.asRuntimeException())
                }
            }
        serviceRegistry.addService(
            ServerInterceptors.intercept(
                actionCache,
                com.google.devtools.build.lib.remote.RemoteSpawnRunnerWithGrpcRemoteExecutorTest.RequestHeadersValidator()
            )
        )
        val actionResult: ActionResult? =
            ActionResult.newBuilder()
                .addOutputFiles(DUMMY_OUTPUT)
                .setStdoutRaw(ByteString.copyFromUtf8("stdout"))
                .setStderrRaw(ByteString.copyFromUtf8("stderr"))
                .build()
        val execService: BindableService =
            object : ExecutionImplBase() {
                public override fun execute(request: ExecuteRequest?, responseObserver: StreamObserver<Operation?>) {
                    responseObserver.onNext(
                        Operation.newBuilder()
                            .setDone(true)
                            .setResponse(
                                Any.pack(ExecuteResponse.newBuilder().setResult(actionResult).build())
                            )
                            .build()
                    )
                    responseObserver.onCompleted()
                }
            }
        serviceRegistry.addService(
            ServerInterceptors.intercept(
                execService,
                com.google.devtools.build.lib.remote.RemoteSpawnRunnerWithGrpcRemoteExecutorTest.RequestHeadersValidator()
            )
        )
        val cas: BindableService =
            object : ContentAddressableStorageImplBase() {
                public override fun findMissingBlobs(
                    request: FindMissingBlobsRequest,
                    responseObserver: StreamObserver<FindMissingBlobsResponse?>
                ) {
                    val requested: MutableSet<Digest?> =
                        com.google.common.collect.ImmutableSet.copyOf(request.getBlobDigestsList())
                    Truth.assertThat(requested).contains(cmdDigest)
                    Truth.assertThat(requested).contains(inputDigest)
                    responseObserver.onNext(
                        FindMissingBlobsResponse.newBuilder().addMissingBlobDigests(inputDigest).build()
                    )
                    responseObserver.onCompleted()
                }
            }
        serviceRegistry.addService(
            ServerInterceptors.intercept(
                cas,
                com.google.devtools.build.lib.remote.RemoteSpawnRunnerWithGrpcRemoteExecutorTest.RequestHeadersValidator()
            )
        )

        val mockByteStreamImpl: ByteStreamImplBase? = Mockito.spy<ByteStreamImplBase?>(ByteStreamImplBase::class.java)
        Mockito.doAnswer(blobWriteAnswer("xyz".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .`when`<Any?>(mockByteStreamImpl).write(ArgumentMatchers.any<T?>())
        serviceRegistry.addService(
            ServerInterceptors.intercept(
                mockByteStreamImpl,
                com.google.devtools.build.lib.remote.RemoteSpawnRunnerWithGrpcRemoteExecutorTest.RequestHeadersValidator()
            )
        )

        val context: FakeSpawnExecutionContext = getSpawnContext(simpleSpawn)

        val result: SpawnResult = client.exec(simpleSpawn, context)
        assertThat(result.setupSuccess()).isTrue()
        assertThat(result.exitCode()).isEqualTo(0)
        assertThat(result.isCacheHit()).isFalse()
        assertThat(outErr.outAsLatin1()).isEqualTo("stdout")
        assertThat(outErr.errAsLatin1()).isEqualTo("stderr")
        Mockito.verify<Any?>(mockByteStreamImpl).write(ArgumentMatchers.any<StreamObserver<WriteResponse?>?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun remotelyExecuteRetries() {
        val execPath: PathFragment? = ArgumentMatchers.any<PathFragment?>()
        Mockito.`when`<T?>(remoteOutputChecker.shouldDownloadOutput(execPath, ArgumentMatchers.any<T?>()))
            .thenReturn(true)

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
        val resultDigest: Digest? = DIGEST_UTIL.compute("bla".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        val actionResult: ActionResult? =
            ActionResult.newBuilder()
                .setStdoutRaw(ByteString.copyFromUtf8("stdout"))
                .setStderrRaw(ByteString.copyFromUtf8("stderr"))
                .addOutputFiles(OutputFile.newBuilder().setPath("foo").setDigest(resultDigest).build())
                .build()
        val opName = "operations/xyz"

        val executeResponseWithError: ExecuteResponse? =
            ExecuteResponse.newBuilder()
                .setStatus(
                    com.google.rpc.Status.newBuilder().setCode(Code.INTERNAL.getNumber()).build()
                )
                .build()
        val operationWithExecuteError: Operation? =
            Operation.newBuilder()
                .setName(opName)
                .setDone(true)
                .setResponse(Any.pack(executeResponseWithError))
                .build()
        val unfinishedOperation: Operation? = Operation.newBuilder().setName(opName).build()
        val opSuccess: Operation? =
            Operation.newBuilder()
                .setName(opName)
                .setDone(true)
                .setResponse(Any.pack(ExecuteResponse.newBuilder().setResult(actionResult).build()))
                .build()

        val mockExecutionImpl: ExecutionImplBase? = Mockito.spy<ExecutionImplBase?>(ExecutionImplBase::class.java)
        // Flow of this test:
        // - call execute, get retriable gRPC error
        // - retry: call execute, get retriable Operation error
        // - retry: call execute, get an Operation, then a retriable gRPC error
        // - retry: call waitExecute, get a retriable gRPC error
        // - retry: call waitExecute, get retriable Operation error
        // - retry: call execute, get successful operation, ignore further errors.
        Mockito.doAnswer(answerWith(null, io.grpc.Status.UNAVAILABLE))
            .doAnswer(answerWith(operationWithExecuteError, io.grpc.Status.OK))
            .doAnswer(answerWith(unfinishedOperation, io.grpc.Status.UNAVAILABLE))
            .doAnswer(answerWith(opSuccess, io.grpc.Status.UNAVAILABLE)) // last status should be ignored.
            .`when`<Any?>(mockExecutionImpl)
            .execute(
                ArgumentMatchers.any<ExecuteRequest?>(),
                ArgumentMatchers.any<StreamObserver<Operation?>?>()
            )
        Mockito.doAnswer(answerWith(null, io.grpc.Status.UNAVAILABLE))
            .doAnswer(answerWith(operationWithExecuteError, io.grpc.Status.OK))
            .`when`<Any?>(mockExecutionImpl)
            .waitExecution(
                ArgumentMatchers.any<WaitExecutionRequest?>(),
                ArgumentMatchers.any<StreamObserver<Operation?>?>()
            )
        serviceRegistry.addService(mockExecutionImpl)

        serviceRegistry.addService(
            object : ContentAddressableStorageImplBase() {
                private var numErrors = 4

                public override fun findMissingBlobs(
                    request: FindMissingBlobsRequest,
                    responseObserver: StreamObserver<FindMissingBlobsResponse?>
                ) {
                    if (numErrors-- > 0) {
                        responseObserver.onError(io.grpc.Status.UNAVAILABLE.asRuntimeException())
                        return
                    }

                    val requested: MutableSet<Digest?> =
                        com.google.common.collect.ImmutableSet.copyOf(request.getBlobDigestsList())
                    Truth.assertThat(requested).contains(cmdDigest)
                    Truth.assertThat(requested).contains(inputDigest)
                    responseObserver.onNext(
                        FindMissingBlobsResponse.newBuilder().addMissingBlobDigests(inputDigest).build()
                    )
                    responseObserver.onCompleted()
                }
            })

        val mockByteStreamImpl: ByteStreamImplBase? = Mockito.spy<ByteStreamImplBase?>(ByteStreamImplBase::class.java)
        Mockito.doAnswer(blobWriteAnswerError()) // Error on the input file.
            .doAnswer(blobWriteAnswerError()) // Error on the input file again.
            .doAnswer(blobWriteAnswer("xyz".getBytes(java.nio.charset.StandardCharsets.UTF_8))) // Upload input file successfully.
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
        Mockito.doAnswer(
            Answer { invocationOnMock: InvocationOnMock? ->
                val responseObserver: StreamObserver<ReadResponse?> =
                    invocationOnMock.getArguments()[1] as StreamObserver<ReadResponse?>
                responseObserver.onError(io.grpc.Status.INTERNAL.asRuntimeException()) // Will retry.
                null
            })
            .doAnswer(
                Answer { invocationOnMock: InvocationOnMock? ->
                    val responseObserver: StreamObserver<ReadResponse?> =
                        invocationOnMock.getArguments()[1] as StreamObserver<ReadResponse?>
                    responseObserver.onNext(
                        ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("bla")).build()
                    )
                    responseObserver.onCompleted()
                    null
                })
            .`when`<Any?>(mockByteStreamImpl)
            .read(
                ArgumentMatchers.any<ReadRequest?>(),
                ArgumentMatchers.any<StreamObserver<ReadResponse?>?>()
            )
        serviceRegistry.addService(mockByteStreamImpl)

        val context: FakeSpawnExecutionContext = getSpawnContext(simpleSpawn)
        val result: SpawnResult = client.exec(simpleSpawn, context)

        assertThat(result.setupSuccess()).isTrue()
        assertThat(result.exitCode()).isEqualTo(0)
        assertThat(result.isCacheHit()).isFalse()
        assertThat(outErr.outAsLatin1()).isEqualTo("stdout")
        assertThat(outErr.errAsLatin1()).isEqualTo("stderr")
        Mockito.verify<Any?>(mockExecutionImpl, Mockito.times(4))
            .execute(
                ArgumentMatchers.any<ExecuteRequest?>(),
                ArgumentMatchers.any<StreamObserver<Operation?>?>()
            )
        Mockito.verify<Any?>(mockExecutionImpl, Mockito.times(2))
            .waitExecution(
                ArgumentMatchers.any<WaitExecutionRequest?>(),
                ArgumentMatchers.any<StreamObserver<Operation?>?>()
            )
        Mockito.verify<Any?>(mockByteStreamImpl, Mockito.times(2))
            .read(
                ArgumentMatchers.any<ReadRequest?>(),
                ArgumentMatchers.any<StreamObserver<ReadResponse?>?>()
            )
        Mockito.verify<Any?>(mockByteStreamImpl, Mockito.times(3))
            .write(ArgumentMatchers.any<StreamObserver<WriteResponse?>?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun remotelyExecuteRetriesWaitResult() {
        val execPath: PathFragment? = ArgumentMatchers.any<PathFragment?>()
        Mockito.`when`<T?>(remoteOutputChecker.shouldDownloadOutput(execPath, ArgumentMatchers.any<T?>()))
            .thenReturn(true)

        // This test's flow is similar to the previous, except the result
        // will eventually be returned by the waitExecute function.
        serviceRegistry.addService(
            object : ActionCacheImplBase() {
                public override fun getActionResult(
                    request: GetActionResultRequest?, responseObserver: StreamObserver<ActionResult?>
                ) {
                    responseObserver.onError(io.grpc.Status.NOT_FOUND.asRuntimeException())
                }
            })
        val resultDigest: Digest? = DIGEST_UTIL.compute("bla".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        val actionResult: ActionResult? =
            ActionResult.newBuilder()
                .setStdoutRaw(ByteString.copyFromUtf8("stdout"))
                .setStderrRaw(ByteString.copyFromUtf8("stderr"))
                .addOutputFiles(OutputFile.newBuilder().setPath("foo").setDigest(resultDigest).build())
                .build()
        val opName = "operations/xyz"

        val unfinishedOperation: Operation? = Operation.newBuilder().setName(opName).build()
        val opSuccess: Operation? =
            Operation.newBuilder()
                .setName(opName)
                .setDone(true)
                .setResponse(Any.pack(ExecuteResponse.newBuilder().setResult(actionResult).build()))
                .build()

        val mockExecutionImpl: ExecutionImplBase? = Mockito.spy<ExecutionImplBase?>(ExecutionImplBase::class.java)
        // Flow of this test:
        // - call execute, get an Operation, then a retriable gRPC error
        // - retry: call waitExecute, get NOT_FOUND (operation lost)
        // - retry: call execute, get NOT_FOUND (operation lost)
        // - retry: call execute, get an Operation, then a retriable gRPC error
        // - retry: call waitExecute, get successful operation, ignore further errors.
        Mockito.doAnswer(answerWith(unfinishedOperation, io.grpc.Status.UNAVAILABLE))
            .doAnswer(answerWith(unfinishedOperation, io.grpc.Status.NOT_FOUND))
            .doAnswer(answerWith(unfinishedOperation, io.grpc.Status.UNAVAILABLE))
            .`when`<Any?>(mockExecutionImpl)
            .execute(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
        Mockito.doAnswer(answerWith(unfinishedOperation, io.grpc.Status.NOT_FOUND))
            .doAnswer(answerWith(opSuccess, io.grpc.Status.UNAVAILABLE)) // This error is ignored.
            .`when`<Any?>(mockExecutionImpl)
            .waitExecution(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
        serviceRegistry.addService(mockExecutionImpl)

        serviceRegistry.addService(
            object : ContentAddressableStorageImplBase() {
                public override fun findMissingBlobs(
                    request: FindMissingBlobsRequest,
                    responseObserver: StreamObserver<FindMissingBlobsResponse?>
                ) {
                    val requested: MutableSet<Digest?> =
                        com.google.common.collect.ImmutableSet.copyOf(request.getBlobDigestsList())
                    Truth.assertThat(requested).contains(cmdDigest)
                    Truth.assertThat(requested).contains(inputDigest)
                    responseObserver.onNext(
                        FindMissingBlobsResponse.newBuilder().addMissingBlobDigests(inputDigest).build()
                    )
                    responseObserver.onCompleted()
                }
            })

        val mockByteStreamImpl: ByteStreamImplBase? = Mockito.spy<ByteStreamImplBase?>(ByteStreamImplBase::class.java)
        Mockito.doAnswer(blobWriteAnswer("xyz".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .`when`<Any?>(mockByteStreamImpl).write(ArgumentMatchers.any<T?>())
        Mockito.doAnswer(
            Answer { invocationOnMock: InvocationOnMock? ->
                val responseObserver: StreamObserver<ReadResponse?> =
                    invocationOnMock.getArguments()[1] as StreamObserver<ReadResponse?>
                responseObserver.onNext(
                    ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("bla")).build()
                )
                responseObserver.onCompleted()
                null
            })
            .`when`<Any?>(mockByteStreamImpl)
            .read(
                ArgumentMatchers.any<ReadRequest?>(),
                ArgumentMatchers.any<StreamObserver<ReadResponse?>?>()
            )
        serviceRegistry.addService(mockByteStreamImpl)

        val context: FakeSpawnExecutionContext = getSpawnContext(simpleSpawn)
        val result: SpawnResult = client.exec(simpleSpawn, context)

        assertThat(result.setupSuccess()).isTrue()
        assertThat(result.exitCode()).isEqualTo(0)
        assertThat(result.isCacheHit()).isFalse()
        assertThat(outErr.outAsLatin1()).isEqualTo("stdout")
        assertThat(outErr.errAsLatin1()).isEqualTo("stderr")
        Mockito.verify<Any?>(mockExecutionImpl, Mockito.times(3))
            .execute(
                ArgumentMatchers.any<ExecuteRequest?>(),
                ArgumentMatchers.any<StreamObserver<Operation?>?>()
            )
        Mockito.verify<Any?>(mockExecutionImpl, Mockito.times(2))
            .waitExecution(
                ArgumentMatchers.any<WaitExecutionRequest?>(),
                ArgumentMatchers.any<StreamObserver<Operation?>?>()
            )
        Mockito.verify<Any?>(mockByteStreamImpl)
            .read(
                ArgumentMatchers.any<ReadRequest?>(),
                ArgumentMatchers.any<StreamObserver<ReadResponse?>?>()
            )
        Mockito.verify<Any?>(mockByteStreamImpl, Mockito.times(1))
            .write(ArgumentMatchers.any<StreamObserver<WriteResponse?>?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun passUnavailableErrorWithStackTrace() {
        serviceRegistry.addService(
            object : ActionCacheImplBase() {
                public override fun getActionResult(
                    request: GetActionResultRequest?, responseObserver: StreamObserver<ActionResult?>
                ) {
                    responseObserver.onError(io.grpc.Status.UNAVAILABLE.asRuntimeException())
                }
            })

        val context: FakeSpawnExecutionContext = getSpawnContext(simpleSpawn)
        val result: SpawnResult = client.exec(simpleSpawn, context)

        assertThat(result.status()).isEqualTo(SpawnResult.Status.EXECUTION_FAILED_CATASTROPHICALLY)
        // Ensure we also got back the stack trace due to verboseFailures=true
        com.google.common.truth.Subject.contains("com.google.devtools.build.lib.remote")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun passInternalErrorWithStackTrace() {
        serviceRegistry.addService(
            object : ActionCacheImplBase() {
                public override fun getActionResult(
                    request: GetActionResultRequest?, responseObserver: StreamObserver<ActionResult?>
                ) {
                    responseObserver.onError(io.grpc.Status.INTERNAL.withDescription("whoa").asRuntimeException())
                }
            })

        val context: FakeSpawnExecutionContext = getSpawnContext(simpleSpawn)
        val result: SpawnResult = client.exec(simpleSpawn, context)

        com.google.common.truth.Subject.contains("whoa") // Error details.
        // Ensure we also got back the stack trace due to verboseFailures=true
        com.google.common.truth.Subject.contains("com.google.devtools.build.lib.remote")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun passCacheMissErrorWithStackTrace() {
        serviceRegistry.addService(
            object : ActionCacheImplBase() {
                public override fun getActionResult(
                    request: GetActionResultRequest?, responseObserver: StreamObserver<ActionResult?>
                ) {
                    responseObserver.onError(io.grpc.Status.NOT_FOUND.asRuntimeException())
                }
            })
        val stdOutDigest: Digest? = DIGEST_UTIL.computeAsUtf8("bla")
        val actionResult: ActionResult? =
            ActionResult.newBuilder()
                .addOutputFiles(DUMMY_OUTPUT)
                .setStdoutDigest(stdOutDigest)
                .build()
        serviceRegistry.addService(
            object : ExecutionImplBase() {
                public override fun execute(request: ExecuteRequest?, responseObserver: StreamObserver<Operation?>) {
                    responseObserver.onNext(
                        Operation.newBuilder()
                            .setDone(true)
                            .setResponse(
                                Any.pack(ExecuteResponse.newBuilder().setResult(actionResult).build())
                            )
                            .build()
                    )
                    responseObserver.onCompleted()
                }
            })
        serviceRegistry.addService(
            object : ContentAddressableStorageImplBase() {
                public override fun findMissingBlobs(
                    request: FindMissingBlobsRequest?,
                    responseObserver: StreamObserver<FindMissingBlobsResponse?>
                ) {
                    responseObserver.onNext(FindMissingBlobsResponse.getDefaultInstance())
                    responseObserver.onCompleted()
                }
            })
        val stdOutResourceName: String? =
            getResourceName(
                remoteOptions.remoteInstanceName,
                stdOutDigest,
                false,
                DigestFunction.Value.SHA256
            )
        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun read(request: ReadRequest, responseObserver: StreamObserver<ReadResponse?>) {
                    assertThat(request.getResourceName()).isEqualTo(stdOutResourceName)
                    responseObserver.onError(io.grpc.Status.NOT_FOUND.asRuntimeException())
                }
            })

        val context: FakeSpawnExecutionContext = getSpawnContext(simpleSpawn)
        val result: SpawnResult = client.exec(simpleSpawn, context)

        assertThat(result.status()).isEqualTo(SpawnResult.Status.REMOTE_CACHE_FAILED)
        com.google.common.truth.Subject.contains(DigestUtil.toString(stdOutDigest))
        // Ensure we also got back the stack trace.
        com.google.common.truth.Subject.contains("RemoteSpawnRunnerWithGrpcRemoteExecutorTest.passCacheMissErrorWithStackTrace")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun passRepeatedOrphanedCacheMissErrorWithStackTrace() {
        val stdOutDigest: Digest? = DIGEST_UTIL.computeAsUtf8("bloo")
        val actionResult: ActionResult? =
            ActionResult.newBuilder()
                .addOutputFiles(DUMMY_OUTPUT)
                .setStdoutDigest(stdOutDigest)
                .build()
        serviceRegistry.addService(
            object : ActionCacheImplBase() {
                public override fun getActionResult(
                    request: GetActionResultRequest?, responseObserver: StreamObserver<ActionResult?>
                ) {
                    responseObserver.onNext(actionResult)
                    responseObserver.onCompleted()
                }
            })
        serviceRegistry.addService(
            object : ExecutionImplBase() {
                public override fun execute(request: ExecuteRequest?, responseObserver: StreamObserver<Operation?>) {
                    responseObserver.onNext(
                        Operation.newBuilder()
                            .setDone(true)
                            .setResponse(
                                Any.pack(ExecuteResponse.newBuilder().setResult(actionResult).build())
                            )
                            .build()
                    )
                    responseObserver.onCompleted()
                }
            })
        serviceRegistry.addService(
            object : ContentAddressableStorageImplBase() {
                public override fun findMissingBlobs(
                    request: FindMissingBlobsRequest?,
                    responseObserver: StreamObserver<FindMissingBlobsResponse?>
                ) {
                    responseObserver.onNext(FindMissingBlobsResponse.getDefaultInstance())
                    responseObserver.onCompleted()
                }
            })
        val stdOutResourceName: String? =
            getResourceName(
                remoteOptions.remoteInstanceName,
                stdOutDigest,
                false,
                DigestFunction.Value.SHA256
            )
        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun read(request: ReadRequest, responseObserver: StreamObserver<ReadResponse?>) {
                    assertThat(request.getResourceName()).isEqualTo(stdOutResourceName)
                    responseObserver.onError(io.grpc.Status.NOT_FOUND.asRuntimeException())
                }
            })

        val context: FakeSpawnExecutionContext = getSpawnContext(simpleSpawn)
        val result: SpawnResult = client.exec(simpleSpawn, context)

        assertThat(result.status()).isEqualTo(SpawnResult.Status.REMOTE_CACHE_FAILED)
        assertThat(result.failureDetail().getSpawn().getCode())
            .isEqualTo(FailureDetails.Spawn.Code.REMOTE_CACHE_FAILED)
        com.google.common.truth.Subject.contains(DigestUtil.toString(stdOutDigest))
        // Ensure we also got back the stack trace because verboseFailures=true
        com.google.common.truth.Subject.contains("passRepeatedOrphanedCacheMissErrorWithStackTrace")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun remotelyReExecuteOrphanedCachedActions() {
        val stdOutDigest: Digest? = DIGEST_UTIL.computeAsUtf8("stdout")
        val actionResult: ActionResult? =
            ActionResult.newBuilder()
                .addOutputFiles(DUMMY_OUTPUT)
                .setStdoutDigest(stdOutDigest)
                .build()
        serviceRegistry.addService(
            object : ActionCacheImplBase() {
                public override fun getActionResult(
                    request: GetActionResultRequest?, responseObserver: StreamObserver<ActionResult?>
                ) {
                    responseObserver.onNext(actionResult)
                    responseObserver.onCompleted()
                }
            })
        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                private var first = true

                public override fun read(request: ReadRequest?, responseObserver: StreamObserver<ReadResponse?>) {
                    // First read is a cache miss, next read succeeds.
                    if (first) {
                        first = false
                        responseObserver.onError(io.grpc.Status.NOT_FOUND.asRuntimeException())
                    } else {
                        responseObserver.onNext(
                            ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("stdout")).build()
                        )
                        responseObserver.onCompleted()
                    }
                }

                public override fun write(
                    responseObserver: StreamObserver<WriteResponse?>
                ): StreamObserver<WriteRequest?> {
                    return object : StreamObserver<WriteRequest?> {
                        override fun onNext(request: WriteRequest?) {}

                        override fun onCompleted() {
                            responseObserver.onCompleted()
                        }

                        override fun onError(t: Throwable?) {
                            org.junit.Assert.fail("An error occurred: " + t)
                        }
                    }
                }
            })
        val numExecuteCalls: AtomicInteger = AtomicInteger()
        serviceRegistry.addService(
            object : ExecutionImplBase() {
                public override fun execute(request: ExecuteRequest, responseObserver: StreamObserver<Operation?>) {
                    numExecuteCalls.incrementAndGet()
                    assertThat(request.getSkipCacheLookup()).isTrue() // Action will be re-executed.
                    responseObserver.onNext(
                        Operation.newBuilder()
                            .setDone(true)
                            .setResponse(
                                Any.pack(ExecuteResponse.newBuilder().setResult(actionResult).build())
                            )
                            .build()
                    )
                    responseObserver.onCompleted()
                }
            })
        serviceRegistry.addService(
            object : ContentAddressableStorageImplBase() {
                public override fun findMissingBlobs(
                    request: FindMissingBlobsRequest?,
                    responseObserver: StreamObserver<FindMissingBlobsResponse?>
                ) {
                    // Nothing is missing.
                    responseObserver.onNext(FindMissingBlobsResponse.getDefaultInstance())
                    responseObserver.onCompleted()
                }
            })

        val context: FakeSpawnExecutionContext = getSpawnContext(simpleSpawn)
        val result: SpawnResult = client.exec(simpleSpawn, context)

        assertThat(result.setupSuccess()).isTrue()
        assertThat(result.exitCode()).isEqualTo(0)
        assertThat(result.isCacheHit()).isFalse()
        assertThat(outErr.outAsLatin1()).isEqualTo("stdout")
        Truth.assertThat(numExecuteCalls.get()).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun remotelyReExecuteOrphanedDirectoryCachedActions() {
        val actionResult: ActionResult? =
            ActionResult.newBuilder().addOutputDirectories(DUMMY_OUTPUT_DIRECTORY).build()
        serviceRegistry.addService(
            object : ActionCacheImplBase() {
                public override fun getActionResult(
                    request: GetActionResultRequest?, responseObserver: StreamObserver<ActionResult?>
                ) {
                    responseObserver.onNext(actionResult)
                    responseObserver.onCompleted()
                }
            })
        val dummyTreeResourceName: String? =
            getResourceName(
                remoteOptions.remoteInstanceName,
                DUMMY_OUTPUT_DIRECTORY.getTreeDigest(),
                false,
                DigestFunction.Value.SHA256
            )
        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                private var first = true

                public override fun read(request: ReadRequest, responseObserver: StreamObserver<ReadResponse?>) {
                    val resourceName: String = request.getResourceName()
                    if (resourceName == dummyTreeResourceName) {
                        // First read is a cache miss, next read succeeds.
                        if (first) {
                            first = false
                            responseObserver.onError(io.grpc.Status.NOT_FOUND.asRuntimeException())
                        } else {
                            responseObserver.onNext(
                                ReadResponse.newBuilder().setData(DUMMY_OUTPUT_TREE.toByteString()).build()
                            )
                            responseObserver.onCompleted()
                        }
                    } else {
                        responseObserver.onNext(ReadResponse.getDefaultInstance())
                    }
                }

                public override fun write(
                    responseObserver: StreamObserver<WriteResponse?>
                ): StreamObserver<WriteRequest?> {
                    return object : StreamObserver<WriteRequest?> {
                        override fun onNext(request: WriteRequest?) {}

                        override fun onCompleted() {
                            responseObserver.onCompleted()
                        }

                        override fun onError(t: Throwable?) {
                            org.junit.Assert.fail("An error occurred: " + t)
                        }
                    }
                }
            })
        val numExecuteCalls: AtomicInteger = AtomicInteger()
        serviceRegistry.addService(
            object : ExecutionImplBase() {
                public override fun execute(request: ExecuteRequest, responseObserver: StreamObserver<Operation?>) {
                    numExecuteCalls.incrementAndGet()
                    assertThat(request.getSkipCacheLookup()).isTrue() // Action will be re-executed.
                    responseObserver.onNext(
                        Operation.newBuilder()
                            .setDone(true)
                            .setResponse(
                                Any.pack(ExecuteResponse.newBuilder().setResult(actionResult).build())
                            )
                            .build()
                    )
                    responseObserver.onCompleted()
                }
            })
        serviceRegistry.addService(
            object : ContentAddressableStorageImplBase() {
                public override fun findMissingBlobs(
                    request: FindMissingBlobsRequest?,
                    responseObserver: StreamObserver<FindMissingBlobsResponse?>
                ) {
                    // Nothing is missing.
                    responseObserver.onNext(FindMissingBlobsResponse.getDefaultInstance())
                    responseObserver.onCompleted()
                }
            })

        val context: FakeSpawnExecutionContext = getSpawnContext(simpleSpawn)
        val result: SpawnResult = client.exec(simpleSpawn, context)

        assertThat(result.setupSuccess()).isTrue()
        assertThat(result.exitCode()).isEqualTo(0)
        assertThat(result.isCacheHit()).isFalse()
        Truth.assertThat(numExecuteCalls.get()).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun retryUploadAndExecuteOnMissingInputs() {
        serviceRegistry.addService(
            object : ActionCacheImplBase() {
                public override fun getActionResult(
                    request: GetActionResultRequest?, responseObserver: StreamObserver<ActionResult?>
                ) {
                    responseObserver.onError(io.grpc.Status.NOT_FOUND.asRuntimeException())
                }
            })
        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun read(request: ReadRequest?, responseObserver: StreamObserver<ReadResponse?>) {
                    responseObserver.onNext(
                        ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("bla")).build()
                    )
                    responseObserver.onCompleted()
                }

                public override fun write(
                    responseObserver: StreamObserver<WriteResponse?>
                ): StreamObserver<WriteRequest?> {
                    return object : StreamObserver<WriteRequest?> {
                        override fun onNext(request: WriteRequest?) {}

                        override fun onCompleted() {
                            responseObserver.onCompleted()
                        }

                        override fun onError(t: Throwable?) {
                            org.junit.Assert.fail("An error occurred: " + t)
                        }
                    }
                }
            })
        val actionResult: ActionResult? =
            ActionResult.newBuilder().addOutputFiles(DUMMY_OUTPUT).build()
        val numExecuteCalls: AtomicInteger = AtomicInteger()
        serviceRegistry.addService(
            object : ExecutionImplBase() {
                public override fun execute(request: ExecuteRequest, responseObserver: StreamObserver<Operation?>) {
                    if (numExecuteCalls.incrementAndGet() == 1) {
                        // Missing input.
                        val viol: Violation? = Violation.newBuilder().setType("MISSING").build()
                        val status: com.google.rpc.Status? =
                            com.google.rpc.Status.newBuilder()
                                .setCode(Code.FAILED_PRECONDITION.getNumber())
                                .addDetails(
                                    Any.pack(PreconditionFailure.newBuilder().addViolations(viol).build())
                                )
                                .build()
                        responseObserver.onNext(
                            Operation.newBuilder()
                                .setDone(true)
                                .setResponse(Any.pack(ExecuteResponse.newBuilder().setStatus(status).build()))
                                .build()
                        )
                        responseObserver.onCompleted()
                    } else {
                        assertThat(request.getSkipCacheLookup()).isFalse()
                        responseObserver.onNext(
                            Operation.newBuilder()
                                .setDone(true)
                                .setResponse(
                                    Any.pack(ExecuteResponse.newBuilder().setResult(actionResult).build())
                                )
                                .build()
                        )
                        responseObserver.onCompleted()
                    }
                }
            })
        val numCacheUploads: AtomicInteger = AtomicInteger()
        serviceRegistry.addService(
            object : ContentAddressableStorageImplBase() {
                public override fun findMissingBlobs(
                    request: FindMissingBlobsRequest?,
                    responseObserver: StreamObserver<FindMissingBlobsResponse?>
                ) {
                    numCacheUploads.incrementAndGet()
                    // Nothing is missing.
                    responseObserver.onNext(FindMissingBlobsResponse.getDefaultInstance())
                    responseObserver.onCompleted()
                }
            })

        val context: FakeSpawnExecutionContext = getSpawnContext(simpleSpawn)
        val result: SpawnResult = client.exec(simpleSpawn, context)

        assertThat(result.setupSuccess()).isTrue()
        assertThat(result.exitCode()).isEqualTo(0)
        assertThat(result.isCacheHit()).isFalse()
        Truth.assertThat(numCacheUploads.get()).isEqualTo(2)
        Truth.assertThat(numExecuteCalls.get()).isEqualTo(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun execWaitsOnUnfinishedCompletion() {
        serviceRegistry.addService(
            object : ActionCacheImplBase() {
                public override fun getActionResult(
                    request: GetActionResultRequest?, responseObserver: StreamObserver<ActionResult?>
                ) {
                    responseObserver.onError(io.grpc.Status.NOT_FOUND.asRuntimeException())
                }
            })

        val opName = "operations/xyz"
        val resultDigest: Digest? = DIGEST_UTIL.compute("bla".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun read(request: ReadRequest?, responseObserver: StreamObserver<ReadResponse?>) {
                    responseObserver.onNext(
                        ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("bla")).build()
                    )
                    responseObserver.onCompleted()
                }
            })
        val actionResult: ActionResult? =
            ActionResult.newBuilder()
                .setStdoutRaw(ByteString.copyFromUtf8("stdout"))
                .setStderrRaw(ByteString.copyFromUtf8("stderr"))
                .addOutputFiles(OutputFile.newBuilder().setPath("foo").setDigest(resultDigest).build())
                .build()
        val unfinishedOperation: Operation = Operation.newBuilder().setName(opName).build()
        val completeOperation: Operation? =
            unfinishedOperation.toBuilder()
                .setDone(true)
                .setResponse(Any.pack(ExecuteResponse.newBuilder().setResult(actionResult).build()))
                .build()
        val waitExecutionRequest: WaitExecutionRequest? =
            WaitExecutionRequest.newBuilder().setName(opName).build()
        val mockExecutionImpl: ExecutionImplBase? = Mockito.spy<ExecutionImplBase?>(ExecutionImplBase::class.java)
        // Flow of this test:
        // - call execute, get an unfinished Operation, then the stream completes
        // - call waitExecute, get an unfinished Operation, then the stream completes
        // - call waitExecute, get a finished Operation
        Mockito.doAnswer(answerWith(unfinishedOperation, io.grpc.Status.OK))
            .`when`<Any?>(mockExecutionImpl)
            .execute(
                ArgumentMatchers.any<ExecuteRequest?>(),
                ArgumentMatchers.any<StreamObserver<Operation?>?>()
            )
        Mockito.doAnswer(answerWith(unfinishedOperation, io.grpc.Status.OK))
            .doAnswer(answerWith(completeOperation, io.grpc.Status.OK))
            .`when`<Any?>(mockExecutionImpl)
            .waitExecution(
                ArgumentMatchers.< T > eq < T ? > (waitExecutionRequest),
                ArgumentMatchers.any<StreamObserver<Operation?>?>()
            )
        serviceRegistry.addService(mockExecutionImpl)

        serviceRegistry.addService(
            object : ContentAddressableStorageImplBase() {
                public override fun findMissingBlobs(
                    request: FindMissingBlobsRequest?,
                    responseObserver: StreamObserver<FindMissingBlobsResponse?>
                ) {
                    responseObserver.onNext(FindMissingBlobsResponse.getDefaultInstance())
                    responseObserver.onCompleted()
                }
            })

        val context: FakeSpawnExecutionContext = getSpawnContext(simpleSpawn)
        val result: SpawnResult = client.exec(simpleSpawn, context)

        assertThat(result.setupSuccess()).isTrue()
        assertThat(result.exitCode()).isEqualTo(0)
        assertThat(result.isCacheHit()).isFalse()
        Mockito.verify<Any?>(mockExecutionImpl, Mockito.times(1))
            .execute(
                ArgumentMatchers.any<ExecuteRequest?>(),
                ArgumentMatchers.any<StreamObserver<Operation?>?>()
            )
        Mockito.verify<Any?>(mockExecutionImpl, Mockito.times(2))
            .waitExecution(
                Mockito.< T > eq < T ? > (waitExecutionRequest), ArgumentMatchers.any<StreamObserver<Operation?>?>()
            )
    }

    private fun getSpawnContext(spawn: Spawn?): FakeSpawnExecutionContext {
        val actionInputFetcher: RemoteActionInputFetcher =
            RemoteActionInputFetcher(
                com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus()),
                "none",
                "none",
                remoteCache,
                execRoot,
                tempPathGenerator,
                remoteOutputChecker,
                ActionOutputDirectoryHelper.createForTesting(),
                OutputPermissions.READONLY
            )

        val actionFileSystem: RemoteActionFileSystem =
            RemoteActionFileSystem(
                fs,
                execRoot.asFragment(),
                artifactRoot.getRoot().asPath().relativeTo(execRoot).getPathString(),
                ActionInputMap(0),
                actionInputFetcher
            )

        return FakeSpawnExecutionContext(
            spawn,
            fakeFileCache,
            execRoot,
            outErr,
            com.google.common.collect.ImmutableClassToInstanceMap.of<ActionContext?>(),
            actionFileSystem
        )
    }

    companion object {
        private val DIGEST_UTIL: DigestUtil = DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256)

        private val DUMMY_OUTPUT: OutputFile = OutputFile.newBuilder()
            .setPath("dummy.txt")
            .setDigest(
                Digest.newBuilder()
                    .setHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
                    .setSizeBytes(0)
                    .build()
            )
            .build()

        private val DUMMY_OUTPUT_TREE: Tree = Tree.newBuilder()
            .setRoot(
                Directory.newBuilder()
                    .addFiles(
                        FileNode.newBuilder()
                            .setName(DUMMY_OUTPUT.getPath())
                            .setDigest(DUMMY_OUTPUT.getDigest())
                            .setIsExecutable(true)
                            .build()
                    )
                    .build()
            )
            .build()

        private val DUMMY_OUTPUT_DIRECTORY: OutputDirectory = OutputDirectory.newBuilder()
            .setPath("dummy")
            .setTreeDigest(DIGEST_UTIL.compute(DUMMY_OUTPUT_TREE))
            .build()

        private fun blobWriteAnswer(data: ByteArray?): Answer<StreamObserver<WriteRequest?>?> {
            val digest: Digest? = DIGEST_UTIL.compute(data)
            return object : Answer<StreamObserver<WriteRequest?>?>() {
                override fun answer(invocation: InvocationOnMock): StreamObserver<WriteRequest?> {
                    val responseObserver: StreamObserver<WriteResponse?> =
                        invocation.getArguments()[0] as StreamObserver<WriteResponse?>
                    return object : StreamObserver<WriteRequest?> {
                        override fun onNext(request: WriteRequest) {
                            com.google.common.truth.Subject.contains(DigestUtil.toString(digest))
                            assertThat(request.getFinishWrite()).isTrue()
                            assertThat(request.getData().toByteArray()).isEqualTo(data)
                            responseObserver.onNext(
                                WriteResponse.newBuilder().setCommittedSize(request.getData().size()).build()
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
            }
        }

        private fun blobWriteAnswerError(): Answer<StreamObserver<WriteRequest?>?> {
            return object : Answer<StreamObserver<WriteRequest?>?>() {
                override fun answer(invocation: InvocationOnMock): StreamObserver<WriteRequest?> {
                    return object : StreamObserver<WriteRequest?> {
                        override fun onNext(request: WriteRequest?) {
                            (invocation.getArguments()[0] as StreamObserver<WriteResponse?>)
                                .onError(io.grpc.Status.UNAVAILABLE.asRuntimeException())
                        }

                        override fun onCompleted() {}

                        override fun onError(t: Throwable?) {
                            org.junit.Assert.fail("An unexpected client-side error occurred: " + t)
                        }
                    }
                }
            }
        }

        private fun answerWith(op: Operation?, status: io.grpc.Status): Answer<java.lang.Void?> {
            return Answer { invocationOnMock: InvocationOnMock? ->
                val responseObserver: StreamObserver<Operation?> =
                    invocationOnMock.getArguments()[1] as StreamObserver<Operation?>
                if (op != null) {
                    responseObserver.onNext(op)
                }
                if (status.isOk()) {
                    responseObserver.onCompleted()
                } else {
                    responseObserver.onError(status.asRuntimeException())
                }
                null
            }
        }
    }
}
