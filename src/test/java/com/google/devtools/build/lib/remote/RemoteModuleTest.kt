// Copyright 2020 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.util.io.CommandExtensionReporter.NO_OP_COMMAND_EXTENSION_REPORTER

/** Tests for [RemoteModule].  */
@RunWith(JUnit4::class)
class RemoteModuleTest {
    internal class CapabilitiesImpl(caps: ServerCapabilities?) : CapabilitiesImplBase() {
        var requestCount: Int = 0
            private set
        private val caps: ServerCapabilities?

        init {
            this.caps = caps
        }

        public override fun getCapabilities(
            request: GetCapabilitiesRequest?, responseObserver: StreamObserver<ServerCapabilities?>
        ) {
            ++requestCount
            responseObserver.onNext(caps)
            responseObserver.onCompleted()
        }
    }

    private var remoteModule: RemoteModule? = null
    private var remoteOptions: RemoteOptions? = null

    @Before
    fun initialize() {
        remoteModule = RemoteModule()
        remoteModule.setChannelFactory(
            { target, proxy, options, interceptors ->
                InProcessChannelBuilder.forName(target).directExecutor().build()
            })
        remoteOptions = com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVerifyCapabilities_none() {
        // Test that Bazel doesn't issue GetCapabilities calls if the requirement is NONE.
        // Regression test for https://github.com/bazelbuild/bazel/issues/20342.
        val executionServerCapabilitiesImpl = CapabilitiesImpl(EXEC_AND_CACHE_CAPS)
        val executionServer: io.grpc.Server =
            createFakeServer(EXECUTION_SERVER_NAME, executionServerCapabilitiesImpl)
        executionServer.start()

        val cacheCapabilitiesImpl = CapabilitiesImpl(CACHE_ONLY_CAPS)
        val cacheServer: io.grpc.Server = createFakeServer(CACHE_SERVER_NAME, cacheCapabilitiesImpl)
        cacheServer.start()

        try {
            remoteOptions.remoteExecutor = EXECUTION_SERVER_NAME
            remoteOptions.remoteDownloader = CACHE_SERVER_NAME

            beforeCommand()

            // Wait for the channel to be connected.
            val downloader: GrpcRemoteDownloader = remoteModule.getRemoteDownloader() as GrpcRemoteDownloader
            val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                downloader.getChannel().withChannelBlocking({ ch -> Any() })

            // Remote downloader uses Remote Asset API, and Bazel doesn't have any capability requirement
            // on the endpoint. Expecting the request count is 0.
            Truth.assertThat(cacheCapabilitiesImpl.requestCount).isEqualTo(0)

            // Retrieve the execution capabilities so that the asynchronous task that eagerly requests
            // them doesn't leak and accidentally interfere with other test cases.
            ProtoTruth.assertThat(
                remoteModule
                    .getActionContextProvider()
                    .getCombinedCache()
                    .getRemoteCacheCapabilities()
            )
                .isEqualTo(EXEC_AND_CACHE_CAPS.getCacheCapabilities())

            assertCircuitBreakerInstance()
        } finally {
            executionServer.shutdownNow()
            cacheServer.shutdownNow()

            executionServer.awaitTermination()
            cacheServer.awaitTermination()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVerifyCapabilities_executionAndCacheForSingleEndpoint() {
        val executionServerCapabilitiesImpl = CapabilitiesImpl(EXEC_AND_CACHE_CAPS)
        val executionServer: io.grpc.Server =
            createFakeServer(EXECUTION_SERVER_NAME, executionServerCapabilitiesImpl)
        executionServer.start()

        try {
            remoteOptions.remoteExecutor = EXECUTION_SERVER_NAME

            beforeCommand()

            assertThat(
                remoteModule
                    .getActionContextProvider()
                    .getCombinedCache()
                    .getRemoteCacheCapabilities()
            )
                .isEqualTo(EXEC_AND_CACHE_CAPS.getCacheCapabilities())
            assertThat(
                remoteModule
                    .getActionContextProvider()
                    .getRemoteExecutionClient()
                    .getServerCapabilities()
            )
                .isEqualTo(EXEC_AND_CACHE_CAPS)
            Truth.assertThat(java.lang.Thread.interrupted()).isFalse()
            Truth.assertThat(executionServerCapabilitiesImpl.requestCount).isEqualTo(1)
            assertCircuitBreakerInstance()
        } finally {
            executionServer.shutdownNow()
            executionServer.awaitTermination()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVerifyCapabilities_cacheOnlyEndpoint() {
        val cacheServerCapabilitiesImpl = CapabilitiesImpl(CACHE_ONLY_CAPS)
        val cacheServer: io.grpc.Server = createFakeServer(CACHE_SERVER_NAME, cacheServerCapabilitiesImpl)
        cacheServer.start()

        try {
            remoteOptions.remoteCache = CACHE_SERVER_NAME

            beforeCommand()

            assertThat(
                remoteModule
                    .getActionContextProvider()
                    .getCombinedCache()
                    .getRemoteCacheCapabilities()
            )
                .isEqualTo(CACHE_ONLY_CAPS.getCacheCapabilities())
            Truth.assertThat(java.lang.Thread.interrupted()).isFalse()
            Truth.assertThat(cacheServerCapabilitiesImpl.requestCount).isEqualTo(1)
            assertCircuitBreakerInstance()
        } finally {
            cacheServer.shutdownNow()
            cacheServer.awaitTermination()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVerifyCapabilities_executionAndCacheForDifferentEndpoints() {
        val executionServerCapabilitiesImpl = CapabilitiesImpl(EXEC_AND_CACHE_CAPS)
        val executionServer: io.grpc.Server =
            createFakeServer(EXECUTION_SERVER_NAME, executionServerCapabilitiesImpl)
        executionServer.start()

        val cacheServerCapabilitiesImpl = CapabilitiesImpl(EXEC_AND_CACHE_CAPS)
        val cacheServer: io.grpc.Server = createFakeServer(CACHE_SERVER_NAME, cacheServerCapabilitiesImpl)
        cacheServer.start()

        try {
            remoteOptions.remoteExecutor = EXECUTION_SERVER_NAME
            remoteOptions.remoteCache = CACHE_SERVER_NAME

            beforeCommand()

            assertThat(
                remoteModule
                    .getActionContextProvider()
                    .getCombinedCache()
                    .getRemoteCacheCapabilities()
            )
                .isEqualTo(EXEC_AND_CACHE_CAPS.getCacheCapabilities())
            assertThat(
                remoteModule
                    .getActionContextProvider()
                    .getRemoteExecutionClient()
                    .getServerCapabilities()
            )
                .isEqualTo(EXEC_AND_CACHE_CAPS)
            Truth.assertThat(java.lang.Thread.interrupted()).isFalse()
            Truth.assertThat(executionServerCapabilitiesImpl.requestCount).isEqualTo(1)
            Truth.assertThat(cacheServerCapabilitiesImpl.requestCount).isEqualTo(1)
            assertCircuitBreakerInstance()
        } finally {
            executionServer.shutdownNow()
            cacheServer.shutdownNow()

            executionServer.awaitTermination()
            cacheServer.awaitTermination()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVerifyCapabilities_executionOnlyAndCacheOnlyEndpoints() {
        val executionServerCapabilitiesImpl = CapabilitiesImpl(EXEC_ONLY_CAPS)
        val executionServer: io.grpc.Server =
            createFakeServer(EXECUTION_SERVER_NAME, executionServerCapabilitiesImpl)
        executionServer.start()

        val cacheServerCapabilitiesImpl = CapabilitiesImpl(CACHE_ONLY_CAPS)
        val cacheServer: io.grpc.Server = createFakeServer(CACHE_SERVER_NAME, cacheServerCapabilitiesImpl)
        cacheServer.start()

        try {
            remoteOptions.remoteExecutor = EXECUTION_SERVER_NAME
            remoteOptions.remoteCache = CACHE_SERVER_NAME

            beforeCommand()

            assertThat(
                remoteModule
                    .getActionContextProvider()
                    .getCombinedCache()
                    .getRemoteCacheCapabilities()
            )
                .isEqualTo(CACHE_ONLY_CAPS.getCacheCapabilities())
            assertThat(
                remoteModule
                    .getActionContextProvider()
                    .getRemoteExecutionClient()
                    .getServerCapabilities()
            )
                .isEqualTo(EXEC_ONLY_CAPS)
            Truth.assertThat(java.lang.Thread.interrupted()).isFalse()
            Truth.assertThat(executionServerCapabilitiesImpl.requestCount).isEqualTo(1)
            Truth.assertThat(cacheServerCapabilitiesImpl.requestCount).isEqualTo(1)
            assertCircuitBreakerInstance()
        } finally {
            executionServer.shutdownNow()
            cacheServer.shutdownNow()

            executionServer.awaitTermination()
            cacheServer.awaitTermination()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNetrc_netrcWithoutRemoteCache() {
        val netrc = "/.netrc"
        val fileSystem: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
        val scratch: Scratch = Scratch(fileSystem)
        scratch.file(netrc, "machine foo.example.org login baruser password barpass")
        val authAndTLSOptions: AuthAndTLSOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(AuthAndTLSOptions::class.java)
        val credentialCache: com.github.benmanes.caffeine.cache.Cache<java.net.URI?, GetCredentialsResponse?> =
            Caffeine.newBuilder().build<java.net.URI?, GetCredentialsResponse?>()

        val credentials: com.google.auth.Credentials? =
            RemoteModule.createCredentials(
                CredentialHelperEnvironment.newBuilder()
                    .setEventReporter(com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus()))
                    .setWorkspacePath(fileSystem.getPath("/workspace"))
                    .setClientEnvironment(com.google.common.collect.ImmutableMap.of<K?, V?>("NETRC", netrc))
                    .setHelperExecutionTimeout(java.time.Duration.ZERO)
                    .build(),
                credentialCache,
                CommandLinePathFactory(fileSystem, com.google.common.collect.ImmutableMap.of<K?, V?>()),
                fileSystem,
                authAndTLSOptions,
                remoteOptions
            )

        Truth.assertThat(credentials).isNotNull()
        Truth.assertThat(credentials.getRequestMetadata(java.net.URI.create("https://foo.example.org"))).isNotEmpty()
        Truth.assertThat(credentials.getRequestMetadata(java.net.URI.create("https://bar.example.org"))).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCacheCapabilities_propagatedToRemoteCache() {
        val cacheServerCapabilitiesImpl = CapabilitiesImpl(CACHE_ONLY_CAPS)
        val cacheServer: io.grpc.Server = createFakeServer(CACHE_SERVER_NAME, cacheServerCapabilitiesImpl)
        cacheServer.start()

        try {
            remoteOptions.remoteCache = CACHE_SERVER_NAME

            beforeCommand()

            Truth.assertThat(java.lang.Thread.interrupted()).isFalse()
            val actionContextProvider: RemoteActionContextProvider = remoteModule.getActionContextProvider()
            assertThat(actionContextProvider).isNotNull()
            assertThat(actionContextProvider.getCombinedCache()).isNotNull()
            assertThat(actionContextProvider.getCombinedCache().getRemoteCacheCapabilities())
                .isEqualTo(CACHE_ONLY_CAPS.getCacheCapabilities())
        } finally {
            cacheServer.shutdownNow()
            cacheServer.awaitTermination()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCacheCapabilities_propagatedToRemoteExecutionCache() {
        val executionServerCapabilitiesImpl = CapabilitiesImpl(EXEC_AND_CACHE_CAPS)
        val executionServer: io.grpc.Server =
            createFakeServer(EXECUTION_SERVER_NAME, executionServerCapabilitiesImpl)
        executionServer.start()

        try {
            remoteOptions.remoteExecutor = EXECUTION_SERVER_NAME

            beforeCommand()

            Truth.assertThat(java.lang.Thread.interrupted()).isFalse()
            val actionContextProvider: RemoteActionContextProvider = remoteModule.getActionContextProvider()
            assertThat(actionContextProvider).isNotNull()
            assertThat(actionContextProvider.getCombinedCache()).isNotNull()
            assertThat(actionContextProvider.getCombinedCache().getRemoteCacheCapabilities())
                .isEqualTo(EXEC_AND_CACHE_CAPS.getCacheCapabilities())
        } finally {
            executionServer.shutdownNow()
            executionServer.awaitTermination()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVerifyCapabilities_executionAndCacheForSingleEndpointWithCircuitBreaker() {
        val executionServerCapabilitiesImpl = CapabilitiesImpl(EXEC_AND_CACHE_CAPS)
        val executionServer: io.grpc.Server =
            createFakeServer(EXECUTION_SERVER_NAME, executionServerCapabilitiesImpl)
        executionServer.start()

        try {
            remoteOptions.remoteExecutor = EXECUTION_SERVER_NAME
            remoteOptions.circuitBreakerStrategy = RemoteOptions.CircuitBreakerStrategy.FAILURE

            beforeCommand()

            assertThat(
                remoteModule
                    .getActionContextProvider()
                    .getRemoteExecutionClient()
                    .getServerCapabilities()
            )
                .isEqualTo(EXEC_AND_CACHE_CAPS)
            Truth.assertThat(java.lang.Thread.interrupted()).isFalse()
            Truth.assertThat(executionServerCapabilitiesImpl.requestCount).isEqualTo(1)
            assertCircuitBreakerInstance()
        } finally {
            executionServer.shutdownNow()
            executionServer.awaitTermination()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVerifyCapabilities_cacheOnlyEndpointWithCircuitBreaker() {
        val cacheServerCapabilitiesImpl = CapabilitiesImpl(CACHE_ONLY_CAPS)
        val cacheServer: io.grpc.Server = createFakeServer(CACHE_SERVER_NAME, cacheServerCapabilitiesImpl)
        cacheServer.start()

        try {
            remoteOptions.remoteCache = CACHE_SERVER_NAME
            remoteOptions.circuitBreakerStrategy = RemoteOptions.CircuitBreakerStrategy.FAILURE

            beforeCommand()

            assertThat(
                remoteModule
                    .getActionContextProvider()
                    .getCombinedCache()
                    .getRemoteCacheCapabilities()
            )
                .isEqualTo(CACHE_ONLY_CAPS.getCacheCapabilities())
            Truth.assertThat(java.lang.Thread.interrupted()).isFalse()
            Truth.assertThat(cacheServerCapabilitiesImpl.requestCount).isEqualTo(1)
            assertCircuitBreakerInstance()
        } finally {
            cacheServer.shutdownNow()
            cacheServer.awaitTermination()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun bazelOutputService_noRemoteCache_exit() {
        val outputServiceService: io.grpc.Server = createFakeServer(OUTPUT_SERVICE_SERVER_NAME)
        try {
            remoteOptions.remoteOutputService = OUTPUT_SERVICE_SERVER_NAME

            val exception: T? = org.junit.Assert.assertThrows<T?>(
                AbruptExitException::class.java,
                org.junit.function.ThrowingRunnable { this.beforeCommand() })

            assertThat(exception).hasMessageThat().contains("--experimental_remote_output_service")
        } finally {
            outputServiceService.shutdownNow()
            outputServiceService.awaitTermination()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun diskCacheGarbageCollectionIdleTask_disabled() {
        val diskCacheDir: Path = com.google.devtools.build.lib.testutil.TestUtils.createUniqueTmpDir(null)
        remoteOptions.diskCache = diskCacheDir.asFragment()

        val env: CommandEnvironment = beforeCommand()

        assertThat(env.getIdleTasks()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun diskCacheGarbageCollectionIdleTask_enabled() {
        val diskCacheDir: Path = com.google.devtools.build.lib.testutil.TestUtils.createUniqueTmpDir(null)
        remoteOptions.diskCache = diskCacheDir.asFragment()
        remoteOptions.diskCacheGcIdleDelay = java.time.Duration.ofMinutes(2)
        remoteOptions.diskCacheGcMaxSize = 1234567890L
        remoteOptions.diskCacheGcMaxAge = java.time.Duration.ofDays(7)

        val env: CommandEnvironment = beforeCommand()

        assertThat(env.getIdleTasks()).hasSize(1)
        assertThat(env.getIdleTasks().get(0)).isInstanceOf(DiskCacheGarbageCollectorIdleTask::class.java)
        val idleTask: DiskCacheGarbageCollectorIdleTask = env.getIdleTasks().get(0) as DiskCacheGarbageCollectorIdleTask
        Truth.assertThat<java.time.Duration?>(idleTask.delay()).isEqualTo(java.time.Duration.ofMinutes(2))
        assertThat(idleTask.garbageCollector.root.getPathString())
            .isEqualTo(diskCacheDir.getPathString())
        Truth.assertThat(idleTask.garbageCollector.policy)
            .isEqualTo(
                CollectionPolicy(
                    java.util.Optional.of<Long?>(1234567890L),
                    java.util.Optional.of<java.time.Duration?>(java.time.Duration.ofDays(7))
                )
            )
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class, AbruptExitException::class)
    private fun beforeCommand(): CommandEnvironment {
        val env: CommandEnvironment = createTestCommandEnvironment(remoteModule, remoteOptions)
        remoteModule.beforeCommand(env)
        env.throwPendingException()
        return env
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun diskCache_defaultLocation_resolvesToOutputUserRoot() {
        remoteOptions.diskCache = PathFragment.EMPTY_FRAGMENT

        val env: CommandEnvironment = beforeCommand()

        // The disk cache should be resolved to <outputUserRoot>/cache/disk.
        val outputUserRoot: Path = env.getDirectories().getServerDirectories().getOutputUserRoot()
        val resolved: PathFragment = remoteOptions.getDiskCachePath(outputUserRoot)
        assertThat(resolved).isNotNull()
        assertThat(resolved.getPathString())
            .isEqualTo(outputUserRoot.getRelative("cache/disk").getPathString())
        assertThat(resolved.isAbsolute()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun diskCache_defaultLocation_withGarbageCollection() {
        remoteOptions.diskCache = PathFragment.EMPTY_FRAGMENT
        remoteOptions.diskCacheGcIdleDelay = java.time.Duration.ofMinutes(2)
        remoteOptions.diskCacheGcMaxSize = 1234567890L

        val env: CommandEnvironment = beforeCommand()

        val outputUserRoot: Path = env.getDirectories().getServerDirectories().getOutputUserRoot()
        val expectedPath: Path = outputUserRoot.getRelative("cache/disk")
        assertThat(env.getIdleTasks()).hasSize(1)
        assertThat(env.getIdleTasks().get(0)).isInstanceOf(DiskCacheGarbageCollectorIdleTask::class.java)
        val idleTask: DiskCacheGarbageCollectorIdleTask = env.getIdleTasks().get(0) as DiskCacheGarbageCollectorIdleTask
        assertThat(idleTask.garbageCollector.root.getPathString())
            .isEqualTo(expectedPath.getPathString())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun diskCacheUnset_disablesDiskCache() {
        remoteOptions.diskCache = null

        val env: CommandEnvironment = beforeCommand()

        assertThat(remoteOptions.diskCache).isNull()
        assertThat(env.getIdleTasks()).isEmpty()
    }

    private fun assertCircuitBreakerInstance() {
        val actionContextProvider: RemoteActionContextProvider = remoteModule.getActionContextProvider()
        assertThat(actionContextProvider).isNotNull()

        val circuitBreaker: CircuitBreaker?
        if (actionContextProvider.getCombinedCache() != null) {
            circuitBreaker =
                (actionContextProvider.getCombinedCache().remoteCacheClient as GrpcCacheClient)
                    .getRetrier()
                    .getCircuitBreaker()
        } else if (actionContextProvider.getRemoteExecutionClient() != null) {
            circuitBreaker =
                (actionContextProvider.getRemoteExecutionClient() as GrpcRemoteExecutor)
                    .getRetrier()
                    .getCircuitBreaker()
        } else {
            // no remote cache or execution configured, circuitBreaker is null
            return
        }

        if (remoteOptions.circuitBreakerStrategy == RemoteOptions.CircuitBreakerStrategy.FAILURE) {
            assertThat(circuitBreaker).isInstanceOf(FailureCircuitBreaker::class.java)
        }
        if (remoteOptions.circuitBreakerStrategy == null) {
            assertThat(circuitBreaker).isEqualTo(Retrier.ALLOW_ALL_CALLS)
        }
    }

    companion object {
        private const val EXECUTION_SERVER_NAME = "execution-server"
        private const val CACHE_SERVER_NAME = "cache-server"
        private const val OUTPUT_SERVICE_SERVER_NAME = "output-service"
        private val CACHE_ONLY_CAPS: ServerCapabilities = ServerCapabilities.newBuilder()
            .setLowApiVersion(ApiVersion.low.toSemVer())
            .setHighApiVersion(ApiVersion.high.toSemVer())
            .setCacheCapabilities(
                CacheCapabilities.newBuilder()
                    .addDigestFunctions(Value.SHA256)
                    .setActionCacheUpdateCapabilities(
                        ActionCacheUpdateCapabilities.newBuilder().setUpdateEnabled(true).build()
                    )
                    .setSymlinkAbsolutePathStrategy(SymlinkAbsolutePathStrategy.Value.ALLOWED)
                    .build()
            )
            .build()

        private val EXEC_AND_CACHE_CAPS: ServerCapabilities = ServerCapabilities.newBuilder()
            .setLowApiVersion(ApiVersion.low.toSemVer())
            .setHighApiVersion(ApiVersion.high.toSemVer())
            .setExecutionCapabilities(
                ExecutionCapabilities.newBuilder()
                    .setExecEnabled(true)
                    .setDigestFunction(Value.SHA256)
                    .build()
            )
            .setCacheCapabilities(
                CacheCapabilities.newBuilder().addDigestFunctions(Value.SHA256).build()
            )
            .build()

        private val EXEC_ONLY_CAPS: ServerCapabilities? = ServerCapabilities.newBuilder()
            .setLowApiVersion(ApiVersion.low.toSemVer())
            .setHighApiVersion(ApiVersion.high.toSemVer())
            .setExecutionCapabilities(
                ExecutionCapabilities.newBuilder()
                    .setExecEnabled(true)
                    .setDigestFunction(Value.SHA256)
                    .build()
            )
            .build()

        @Throws(IOException::class, AbruptExitException::class)
        private fun createTestCommandEnvironment(
            remoteModule: RemoteModule?, remoteOptions: RemoteOptions?
        ): CommandEnvironment {
            val coreOptions: CoreOptions? =
                com.google.devtools.common.options.Options.getDefaults<O?>(CoreOptions::class.java)
            val commonCommandOptions: CommonCommandOptions? =
                com.google.devtools.common.options.Options.getDefaults<O?>(CommonCommandOptions::class.java)
            val packageOptions: PackageOptions? =
                com.google.devtools.common.options.Options.getDefaults<O?>(PackageOptions::class.java)
            val clientOptions: ClientOptions? =
                com.google.devtools.common.options.Options.getDefaults<O?>(ClientOptions::class.java)
            val executionOptions: ExecutionOptions? =
                com.google.devtools.common.options.Options.getDefaults<O?>(ExecutionOptions::class.java)
            val testOptions: TestOptions? =
                com.google.devtools.common.options.Options.getDefaults<O?>(TestOptions::class.java)

            val authAndTLSOptions: AuthAndTLSOptions? =
                com.google.devtools.common.options.Options.getDefaults<O?>(AuthAndTLSOptions::class.java)

            val options: OptionsParsingResult = Mockito.mock<OptionsParsingResult>(OptionsParsingResult::class.java)
            Mockito.`when`<T?>(options.getOptions<O?>(CoreOptions::class.java)).thenReturn(coreOptions)
            Mockito.`when`<T?>(options.getOptions<O?>(CommonCommandOptions::class.java))
                .thenReturn(commonCommandOptions)
            Mockito.`when`<T?>(options.getOptions<O?>(PackageOptions::class.java)).thenReturn(packageOptions)
            Mockito.`when`<T?>(options.getOptions<O?>(ClientOptions::class.java)).thenReturn(clientOptions)
            Mockito.`when`<T?>(options.getOptions<O?>(RemoteOptions::class.java)).thenReturn(remoteOptions)
            Mockito.`when`<T?>(options.getOptions<O?>(AuthAndTLSOptions::class.java)).thenReturn(authAndTLSOptions)
            Mockito.`when`<T?>(options.getOptions<O?>(ExecutionOptions::class.java)).thenReturn(executionOptions)
            Mockito.`when`<T?>(options.getOptions<O?>(TestOptions::class.java)).thenReturn(testOptions)

            val productName = "bazel"
            val scratch: Scratch = Scratch(InMemoryFileSystem(DigestHashFunction.SHA256))
            val serverDirectories: ServerDirectories =
                ServerDirectories(
                    scratch.dir("install"), scratch.dir("output"), scratch.dir("user_root")
                )

            val runtime: BlazeRuntime =
                Builder()
                    .setProductName(productName)
                    .setFileSystem(scratch.getFileSystem())
                    .setServerDirectories(serverDirectories)
                    .setStartupOptionsProvider(
                        OptionsParser.builder().optionsClasses(BlazeServerStartupOptions::class.java).build()
                    )
                    .addBlazeModule(CredentialModule())
                    .addBlazeModule(remoteModule)
                    .addBlazeModule(BlockWaitingModule())
                    .addBlazeModule(
                        object : BlazeModule() {
                            public override fun initializeRuleClasses(builder: ConfiguredRuleClassProvider.Builder) {
                                builder.setRunfilesPrefix(TestConstants.WORKSPACE_NAME)
                            }
                        })
                    .build()

            val directories: BlazeDirectories =
                BlazeDirectories(
                    serverDirectories,
                    scratch.dir("/workspace"),
                    productName
                )
            val workspace: BlazeWorkspace = runtime.initWorkspace(directories, BinTools.empty(directories))
            val command: Command? = BuildCommand::class.java.getAnnotation<A?>(Command::class.java)
            return workspace.initCommand(
                command,
                options,
                InvocationPolicy.getDefaultInstance(),  /* warnings= */
                java.util.ArrayList<E?>(),  /* waitTimeInMs= */
                0,  /* commandStartTime= */
                0,  /* idleTaskResultsFromPreviousIdlePeriod= */
                com.google.common.collect.ImmutableList.of<E?>(),  /* shutdownReasonConsumer= */
                { s -> },  /* commandExtensions= */
                com.google.common.collect.ImmutableList.of<E?>(),
                NO_OP_COMMAND_EXTENSION_REPORTER,  /* attemptNumber= */
                1,  /* buildRequestIdOverride= */
                null,
                ConfigFlagDefinitions.NONE
            )
        }

        private fun createFakeServer(serverName: String, vararg services: BindableService): io.grpc.Server {
            val executionServerRegistry: MutableHandlerRegistry = MutableHandlerRegistry()
            for (service in services) {
                executionServerRegistry.addService(ServerInterceptors.intercept(service))
            }
            return InProcessServerBuilder.forName(serverName)
                .fallbackHandlerRegistry(executionServerRegistry)
                .directExecutor()
                .build()
        }
    }
}
