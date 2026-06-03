// Copyright 2018 The Bazel Authors. All rights reserved.
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

import build.bazel.remote.execution.v2.ActionCacheUpdateCapabilities

/** Tests for [RemoteServerCapabilities].  */
@RunWith(JUnit4::class)
class RemoteServerCapabilitiesTest {
    private val serviceRegistry: MutableHandlerRegistry = MutableHandlerRegistry()
    private val fakeServerName = "fake server for " + getClass()
    private var fakeServer: io.grpc.Server? = null
    private var retryService: com.google.common.util.concurrent.ListeningScheduledExecutorService? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        fakeServer =
            InProcessServerBuilder.forName(fakeServerName)
                .fallbackHandlerRegistry(serviceRegistry)
                .directExecutor()
                .build()
                .start()
        retryService =
            com.google.common.util.concurrent.MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1))
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

    private class RequestCustomHeadersValidator : ServerInterceptor {
        override fun <ReqT, RespT> interceptCall(
            call: ServerCall<ReqT?, RespT?>?, headers: io.grpc.Metadata, next: ServerCallHandler<ReqT?, RespT?>
        ): ServerCall.Listener<ReqT?>? {
            Truth.assertThat(
                headers.get<String?>(
                    io.grpc.Metadata.Key.of<String?>(
                        "Key1",
                        io.grpc.Metadata.ASCII_STRING_MARSHALLER
                    )
                )
            )
                .isEqualTo("Value1")
            Truth.assertThat(
                headers.get<String?>(
                    io.grpc.Metadata.Key.of<String?>(
                        "Key2",
                        io.grpc.Metadata.ASCII_STRING_MARSHALLER
                    )
                )
            )
                .isEqualTo("Value2")
            return next.startCall(call, headers)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomHeadersAreAttached() {
        val caps: ServerCapabilities? =
            ServerCapabilities.newBuilder()
                .setExecutionCapabilities(
                    ExecutionCapabilities.newBuilder().setExecEnabled(true).build()
                )
                .build()
        serviceRegistry.addService(
            ServerInterceptors.intercept(
                object : CapabilitiesImplBase() {
                    public override fun getCapabilities(
                        request: GetCapabilitiesRequest?,
                        responseObserver: StreamObserver<ServerCapabilities?>
                    ) {
                        responseObserver.onNext(caps)
                        responseObserver.onCompleted()
                    }
                },
                RequestCustomHeadersValidator()
            )
        )

        val remoteOptions: RemoteOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        remoteOptions.remoteHeaders =
            com.google.common.collect.ImmutableList.of<MutableMap.MutableEntry<String?, String?>?>(
                com.google.common.collect.Maps.immutableEntry<String?, String?>("Key1", "Value1"),
                com.google.common.collect.Maps.immutableEntry<String?, String?>("Key2", "Value2")
            )

        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { ExponentialBackoff(remoteOptions) },
                RemoteRetrier.EXPERIMENTAL_GRPC_RESULT_CLASSIFIER,
                retryService
            )
        val channel: ManagedChannel? =
            InProcessChannelBuilder.forName(fakeServerName)
                .intercept(TracingMetadataUtils.newExecHeadersInterceptor(remoteOptions))
                .directExecutor()
                .build()
        val client: RemoteServerCapabilities =
            RemoteServerCapabilities("build-req-id", "command-id", "instance", null, 3, retrier)

        assertThat(client.get(channel).get()).isEqualTo(caps)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetCapabilitiesWithRetries() {
        val caps: ServerCapabilities? =
            ServerCapabilities.newBuilder()
                .setExecutionCapabilities(
                    ExecutionCapabilities.newBuilder().setExecEnabled(true).build()
                )
                .build()
        serviceRegistry.addService(
            ServerInterceptors.intercept(
                object : CapabilitiesImplBase() {
                    private var numErrors = 0
                    private val MAX_ERRORS = 3

                    public override fun getCapabilities(
                        request: GetCapabilitiesRequest?,
                        responseObserver: StreamObserver<ServerCapabilities?>
                    ) {
                        if (numErrors < MAX_ERRORS) {
                            numErrors++
                            responseObserver.onError(
                                io.grpc.Status.UNAVAILABLE.asRuntimeException()
                            ) // Retriable error.
                        } else {
                            responseObserver.onNext(caps)
                            responseObserver.onCompleted()
                        }
                    }
                },
                com.google.devtools.build.lib.remote.RemoteServerCapabilitiesTest.RequestHeadersValidator()
            )
        )

        val remoteOptions: RemoteOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(RemoteOptions::class.java)
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { ExponentialBackoff(remoteOptions) },
                RemoteRetrier.EXPERIMENTAL_GRPC_RESULT_CLASSIFIER,
                retryService
            )
        val channel: ManagedChannel? =
            InProcessChannelBuilder.forName(fakeServerName).directExecutor().build()
        val client: RemoteServerCapabilities =
            RemoteServerCapabilities(
                "build-req-id", "command-id", "instance",  /* callCredentials= */null, 3, retrier
            )

        assertThat(client.get(channel).get()).isEqualTo(caps)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCheckClientServerCompatibility_noChecks() {
        val st: RemoteServerCapabilities.ClientServerCompatibilityStatus =
            RemoteServerCapabilities.checkClientServerCompatibility(
                ServerCapabilities.getDefaultInstance(),
                com.google.devtools.common.options.Options.getDefaults<O?>(RemoteOptions::class.java),
                DigestFunction.Value.SHA256,
                ServerCapabilitiesRequirement.NONE
            )
        assertThat(st.isOk()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCheckClientServerCompatibility_apiVersionDeprecated() {
        val caps: ServerCapabilities? =
            ServerCapabilities.newBuilder()
                .setDeprecatedApiVersion(ApiVersion.low.toSemVer())
                .setLowApiVersion(ApiVersion(100, 0, 0, "").toSemVer())
                .setHighApiVersion(ApiVersion(100, 0, 0, "").toSemVer())
                .setCacheCapabilities(
                    CacheCapabilities.newBuilder()
                        .addDigestFunctions(DigestFunction.Value.SHA256)
                        .setActionCacheUpdateCapabilities(
                            ActionCacheUpdateCapabilities.newBuilder().setUpdateEnabled(true).build()
                        )
                        .build()
                )
                .build()
        val remoteOptions: RemoteOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        remoteOptions.remoteCache = "server:port"
        val st: RemoteServerCapabilities.ClientServerCompatibilityStatus =
            RemoteServerCapabilities.checkClientServerCompatibility(
                caps, remoteOptions, DigestFunction.Value.SHA256, ServerCapabilitiesRequirement.CACHE
            )
        assertThat(st.getErrors()).isEmpty()
        assertThat(st.getWarnings()).hasSize(1)
        assertThat(st.getWarnings().get(0)).containsMatch("API.*deprecated.*100.0")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCheckClientServerCompatibility_apiVersionUnsupported() {
        val caps: ServerCapabilities? =
            ServerCapabilities.newBuilder()
                .setLowApiVersion(ApiVersion(100, 0, 0, "").toSemVer())
                .setHighApiVersion(ApiVersion(100, 0, 0, "").toSemVer())
                .setCacheCapabilities(
                    CacheCapabilities.newBuilder()
                        .addDigestFunctions(DigestFunction.Value.SHA256)
                        .setActionCacheUpdateCapabilities(
                            ActionCacheUpdateCapabilities.newBuilder().setUpdateEnabled(true).build()
                        )
                        .build()
                )
                .build()
        val remoteOptions: RemoteOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        remoteOptions.remoteCache = "server:port"
        val st: RemoteServerCapabilities.ClientServerCompatibilityStatus =
            RemoteServerCapabilities.checkClientServerCompatibility(
                caps, remoteOptions, DigestFunction.Value.SHA256, ServerCapabilitiesRequirement.CACHE
            )
        assertThat(st.getErrors()).hasSize(1)
        assertThat(st.getErrors().get(0)).containsMatch("API.*not supported.*100.0")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCheckClientServerCompatibility_remoteCacheDoesNotSupportDigestFunction() {
        val caps: ServerCapabilities? =
            ServerCapabilities.newBuilder()
                .setLowApiVersion(ApiVersion.low.toSemVer())
                .setHighApiVersion(ApiVersion.high.toSemVer())
                .setCacheCapabilities(
                    CacheCapabilities.newBuilder()
                        .addDigestFunctions(DigestFunction.Value.MD5)
                        .setActionCacheUpdateCapabilities(
                            ActionCacheUpdateCapabilities.newBuilder().setUpdateEnabled(true).build()
                        )
                        .build()
                )
                .build()
        val remoteOptions: RemoteOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        remoteOptions.remoteCache = "server:port"
        val st: RemoteServerCapabilities.ClientServerCompatibilityStatus =
            RemoteServerCapabilities.checkClientServerCompatibility(
                caps, remoteOptions, DigestFunction.Value.SHA256, ServerCapabilitiesRequirement.CACHE
            )
        assertThat(st.getErrors()).hasSize(1)
        assertThat(st.getErrors().get(0)).containsMatch("Cannot use hash function")
    }

    @org.junit.Test
    fun testCheckClientServerCompatibility_remoteCacheDoesNotSupportUpdate() {
        val caps: ServerCapabilities? =
            ServerCapabilities.newBuilder()
                .setLowApiVersion(ApiVersion.low.toSemVer())
                .setHighApiVersion(ApiVersion.high.toSemVer())
                .setCacheCapabilities(
                    CacheCapabilities.newBuilder()
                        .addDigestFunctions(DigestFunction.Value.SHA256)
                        .build()
                )
                .build()
        val remoteOptions: RemoteOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        remoteOptions.remoteCache = "server:port"
        var st: RemoteServerCapabilities.ClientServerCompatibilityStatus =
            RemoteServerCapabilities.checkClientServerCompatibility(
                caps, remoteOptions, DigestFunction.Value.SHA256, ServerCapabilitiesRequirement.CACHE
            )
        assertThat(st.getErrors()).isEmpty()
        assertThat(st.getWarnings()).hasSize(1)
        com.google.common.truth.Subject.contains("remote cache does not support uploading action results")

        // Ignored when no local upload.
        remoteOptions.remoteUploadLocalResults = false
        st =
            RemoteServerCapabilities.checkClientServerCompatibility(
                caps, remoteOptions, DigestFunction.Value.SHA256, ServerCapabilitiesRequirement.CACHE
            )
        assertThat(st.isOk()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCheckClientServerCompatibility_remoteExecutionIsDisabled() {
        val caps: ServerCapabilities? =
            ServerCapabilities.newBuilder()
                .setLowApiVersion(ApiVersion.low.toSemVer())
                .setHighApiVersion(ApiVersion.high.toSemVer())
                .setCacheCapabilities(
                    CacheCapabilities.newBuilder()
                        .addDigestFunctions(DigestFunction.Value.SHA256)
                        .setActionCacheUpdateCapabilities(
                            ActionCacheUpdateCapabilities.newBuilder().setUpdateEnabled(true).build()
                        )
                        .build()
                )
                .setExecutionCapabilities(
                    ExecutionCapabilities.newBuilder()
                        .setDigestFunction(DigestFunction.Value.SHA256)
                        .build()
                )
                .build()
        val remoteOptions: RemoteOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        remoteOptions.remoteExecutor = "server:port"
        val st: RemoteServerCapabilities.ClientServerCompatibilityStatus =
            RemoteServerCapabilities.checkClientServerCompatibility(
                caps,
                remoteOptions,
                DigestFunction.Value.SHA256,
                ServerCapabilitiesRequirement.EXECUTION_AND_CACHE
            )
        assertThat(st.getErrors()).hasSize(1)
        assertThat(st.getErrors().get(0)).containsMatch("Remote execution is not supported")
        assertThat(st.getErrors().get(0)).containsMatch("not authorized to use remote execution")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCheckClientServerCompatibility_remoteExecutionDoesNotSupportDigestFunction() {
        val caps: ServerCapabilities? =
            ServerCapabilities.newBuilder()
                .setLowApiVersion(ApiVersion.low.toSemVer())
                .setHighApiVersion(ApiVersion.high.toSemVer())
                .setCacheCapabilities(
                    CacheCapabilities.newBuilder()
                        .addDigestFunctions(DigestFunction.Value.SHA256)
                        .setActionCacheUpdateCapabilities(
                            ActionCacheUpdateCapabilities.newBuilder().setUpdateEnabled(true).build()
                        )
                        .build()
                )
                .setExecutionCapabilities(
                    ExecutionCapabilities.newBuilder()
                        .setDigestFunction(DigestFunction.Value.MD5)
                        .setExecEnabled(true)
                        .build()
                )
                .build()
        val remoteOptions: RemoteOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        remoteOptions.remoteExecutor = "server:port"
        val st: RemoteServerCapabilities.ClientServerCompatibilityStatus =
            RemoteServerCapabilities.checkClientServerCompatibility(
                caps,
                remoteOptions,
                DigestFunction.Value.SHA256,
                ServerCapabilitiesRequirement.EXECUTION_AND_CACHE
            )
        assertThat(st.getErrors()).hasSize(1)
        assertThat(st.getErrors().get(0)).containsMatch("Cannot use hash function")
    }

    @org.junit.Test
    fun testCheckClientServerCompatibility_localFallbackNoRemoteCacheUpdate() {
        val caps: ServerCapabilities? =
            ServerCapabilities.newBuilder()
                .setLowApiVersion(ApiVersion.low.toSemVer())
                .setHighApiVersion(ApiVersion.high.toSemVer())
                .setCacheCapabilities(
                    CacheCapabilities.newBuilder()
                        .addDigestFunctions(DigestFunction.Value.SHA256)
                        .build()
                )
                .setExecutionCapabilities(
                    ExecutionCapabilities.newBuilder()
                        .setDigestFunction(DigestFunction.Value.SHA256)
                        .setExecEnabled(true)
                        .build()
                )
                .build()
        val remoteOptions: RemoteOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        remoteOptions.remoteExecutor = "server:port"
        remoteOptions.remoteLocalFallback = true
        var st: RemoteServerCapabilities.ClientServerCompatibilityStatus =
            RemoteServerCapabilities.checkClientServerCompatibility(
                caps,
                remoteOptions,
                DigestFunction.Value.SHA256,
                ServerCapabilitiesRequirement.EXECUTION_AND_CACHE
            )
        assertThat(st.getErrors()).isEmpty()
        assertThat(st.getWarnings()).hasSize(1)
        com.google.common.truth.Subject.contains("remote cache does not support uploading action results")

        // Ignored when no fallback.
        remoteOptions.remoteLocalFallback = false
        st =
            RemoteServerCapabilities.checkClientServerCompatibility(
                caps,
                remoteOptions,
                DigestFunction.Value.SHA256,
                ServerCapabilitiesRequirement.EXECUTION_AND_CACHE
            )
        assertThat(st.getErrors()).isEmpty()
        assertThat(st.getWarnings()).hasSize(1)
        com.google.common.truth.Subject.contains("remote cache does not support uploading action results")

        // Ignored when no uploading local results.
        remoteOptions.remoteLocalFallback = true
        remoteOptions.remoteUploadLocalResults = false
        st =
            RemoteServerCapabilities.checkClientServerCompatibility(
                caps,
                remoteOptions,
                DigestFunction.Value.SHA256,
                ServerCapabilitiesRequirement.EXECUTION_AND_CACHE
            )
        assertThat(st.isOk()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCheckClientServerCompatibility_cachePriority() {
        val caps: ServerCapabilities? =
            ServerCapabilities.newBuilder()
                .setLowApiVersion(ApiVersion.low.toSemVer())
                .setHighApiVersion(ApiVersion.high.toSemVer())
                .setCacheCapabilities(
                    CacheCapabilities.newBuilder()
                        .addDigestFunctions(DigestFunction.Value.SHA256)
                        .setCachePriorityCapabilities(
                            PriorityCapabilities.newBuilder()
                                .addPriorities(
                                    PriorityRange.newBuilder().setMinPriority(1).setMaxPriority(2)
                                )
                                .addPriorities(
                                    PriorityRange.newBuilder().setMinPriority(5).setMaxPriority(10)
                                )
                        )
                        .build()
                )
                .build()
        val remoteOptions: RemoteOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        remoteOptions.remoteCache = "server:port"
        remoteOptions.remoteUploadLocalResults = false
        remoteOptions.remoteResultCachePriority = 11
        var st: RemoteServerCapabilities.ClientServerCompatibilityStatus =
            RemoteServerCapabilities.checkClientServerCompatibility(
                caps, remoteOptions, DigestFunction.Value.SHA256, ServerCapabilitiesRequirement.CACHE
            )
        assertThat(st.getErrors()).hasSize(1)
        assertThat(st.getErrors().get(0)).containsMatch("remote_result_cache_priority")

        // Valid value in range.
        remoteOptions.remoteResultCachePriority = 10
        st =
            RemoteServerCapabilities.checkClientServerCompatibility(
                caps, remoteOptions, DigestFunction.Value.SHA256, ServerCapabilitiesRequirement.CACHE
            )
        assertThat(st.isOk()).isTrue()

        // Check not performed if the value is 0.
        remoteOptions.remoteResultCachePriority = 0
        st =
            RemoteServerCapabilities.checkClientServerCompatibility(
                caps, remoteOptions, DigestFunction.Value.SHA256, ServerCapabilitiesRequirement.CACHE
            )
        assertThat(st.isOk()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCheckClientServerCompatibility_executionPriority() {
        val caps: ServerCapabilities? =
            ServerCapabilities.newBuilder()
                .setLowApiVersion(ApiVersion.low.toSemVer())
                .setHighApiVersion(ApiVersion.high.toSemVer())
                .setCacheCapabilities(
                    CacheCapabilities.newBuilder()
                        .addDigestFunctions(DigestFunction.Value.SHA256)
                        .build()
                )
                .setExecutionCapabilities(
                    ExecutionCapabilities.newBuilder()
                        .setDigestFunction(DigestFunction.Value.SHA256)
                        .setExecEnabled(true)
                        .setExecutionPriorityCapabilities(
                            PriorityCapabilities.newBuilder()
                                .addPriorities(
                                    PriorityRange.newBuilder().setMinPriority(1).setMaxPriority(2)
                                )
                                .addPriorities(
                                    PriorityRange.newBuilder().setMinPriority(5).setMaxPriority(10)
                                )
                        )
                        .build()
                )
                .build()
        val remoteOptions: RemoteOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        remoteOptions.remoteExecutor = "server:port"
        remoteOptions.remoteUploadLocalResults = false
        remoteOptions.remoteExecutionPriority = 11
        var st: RemoteServerCapabilities.ClientServerCompatibilityStatus =
            RemoteServerCapabilities.checkClientServerCompatibility(
                caps,
                remoteOptions,
                DigestFunction.Value.SHA256,
                ServerCapabilitiesRequirement.EXECUTION_AND_CACHE
            )
        assertThat(st.getErrors()).hasSize(1)
        assertThat(st.getErrors().get(0)).containsMatch("remote_execution_priority")

        // Valid value in range.
        remoteOptions.remoteExecutionPriority = 10
        st =
            RemoteServerCapabilities.checkClientServerCompatibility(
                caps,
                remoteOptions,
                DigestFunction.Value.SHA256,
                ServerCapabilitiesRequirement.EXECUTION_AND_CACHE
            )
        assertThat(st.isOk()).isTrue()

        // Check not performed if the value is 0.
        remoteOptions.remoteExecutionPriority = 0
        st =
            RemoteServerCapabilities.checkClientServerCompatibility(
                caps,
                remoteOptions,
                DigestFunction.Value.SHA256,
                ServerCapabilitiesRequirement.EXECUTION_AND_CACHE
            )
        assertThat(st.isOk()).isTrue()

        // Ignored when no remote execution requested.
        remoteOptions.remoteExecutionPriority = 11
        remoteOptions.remoteExecutor = ""
        remoteOptions.remoteCache = "server:port"
        st =
            RemoteServerCapabilities.checkClientServerCompatibility(
                caps, remoteOptions, DigestFunction.Value.SHA256, ServerCapabilitiesRequirement.CACHE
            )
        assertThat(st.isOk()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCheckClientServerCompatibility_executionCapsOnly() {
        val caps: ServerCapabilities? =
            ServerCapabilities.newBuilder()
                .setLowApiVersion(ApiVersion.low.toSemVer())
                .setHighApiVersion(ApiVersion.high.toSemVer())
                .setExecutionCapabilities(
                    ExecutionCapabilities.newBuilder()
                        .setDigestFunction(DigestFunction.Value.SHA256)
                        .setExecEnabled(true)
                        .build()
                )
                .build()
        val remoteOptions: RemoteOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        remoteOptions.remoteExecutor = "server:port"
        val st: RemoteServerCapabilities.ClientServerCompatibilityStatus =
            RemoteServerCapabilities.checkClientServerCompatibility(
                caps,
                remoteOptions,
                DigestFunction.Value.SHA256,
                ServerCapabilitiesRequirement.EXECUTION
            )
        assertThat(st.isOk()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCheckClientServerCompatibility_executionCapsDigestFunctionsList() {
        val caps: ServerCapabilities? =
            ServerCapabilities.newBuilder()
                .setLowApiVersion(ApiVersion.low.toSemVer())
                .setHighApiVersion(ApiVersion.high.toSemVer())
                .setExecutionCapabilities(
                    ExecutionCapabilities.newBuilder()
                        .addDigestFunctions(DigestFunction.Value.MD5)
                        .addDigestFunctions(DigestFunction.Value.SHA256)
                        .setExecEnabled(true)
                        .build()
                )
                .build()
        val remoteOptions: RemoteOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        remoteOptions.remoteExecutor = "server:port"
        val st: RemoteServerCapabilities.ClientServerCompatibilityStatus =
            RemoteServerCapabilities.checkClientServerCompatibility(
                caps,
                remoteOptions,
                DigestFunction.Value.SHA256,
                ServerCapabilitiesRequirement.EXECUTION
            )
        assertThat(st.isOk()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCheckClientServerCompatibility_cacheCapsOnly() {
        val caps: ServerCapabilities? =
            ServerCapabilities.newBuilder()
                .setLowApiVersion(ApiVersion.low.toSemVer())
                .setHighApiVersion(ApiVersion.high.toSemVer())
                .setCacheCapabilities(
                    CacheCapabilities.newBuilder()
                        .addDigestFunctions(DigestFunction.Value.SHA256)
                        .setActionCacheUpdateCapabilities(
                            ActionCacheUpdateCapabilities.newBuilder().setUpdateEnabled(true).build()
                        )
                        .build()
                )
                .build()
        val remoteOptions: RemoteOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        remoteOptions.remoteCache = "server:port"
        val st: RemoteServerCapabilities.ClientServerCompatibilityStatus =
            RemoteServerCapabilities.checkClientServerCompatibility(
                caps, remoteOptions, DigestFunction.Value.SHA256, ServerCapabilitiesRequirement.CACHE
            )
        assertThat(st.isOk()).isTrue()
    }
}
