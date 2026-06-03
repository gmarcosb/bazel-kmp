// Copyright 2023 The Bazel Authors. All rights reserved.
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

import build.bazel.remote.execution.v2.ActionResult

/** Tests for [GrpcRemoteExecutor].  */
@RunWith(JUnit4::class)
class GrpcRemoteExecutorTest {
    // ---------------------------------------------------------------------------
    // Test fixture fields
    // ---------------------------------------------------------------------------
    private var context: RemoteActionExecutionContext? = null
    private var executionService: FakeExecutionService? = null
    private var remoteOptions: RemoteOptions? = null
    private var fakeServer: io.grpc.Server? = null
    private var executor: RemoteExecutionClient? = null

    private var retryService: com.google.common.util.concurrent.ListeningScheduledExecutorService? = null

    // ---------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------
    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        // The derived test previously created the retryService before invoking the
        // base setUp(). We replicate the same ordering here.
        retryService =
            com.google.common.util.concurrent.MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1))

        context = RemoteActionExecutionContext.create(RequestMetadata.getDefaultInstance())

        executionService = FakeExecutionService()

        val fakeServerName = "fake server for " + javaClass
        fakeServer =
            InProcessServerBuilder.forName(fakeServerName)
                .addService(executionService)
                .directExecutor()
                .build()
                .start()

        remoteOptions = com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        remoteOptions.remoteMaxRetryAttempts = MAX_RETRY_ATTEMPTS

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

        executor = createExecutionService(channel)
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

        executor.close()
    }

    @Throws(java.lang.Exception::class)
    private fun createExecutionService(channel: ReferenceCountedChannel?): RemoteExecutionClient {
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { ExponentialBackoff(remoteOptions) },
                RemoteRetrier.GRPC_RESULT_CLASSIFIER,
                retryService
            )

        return GrpcRemoteExecutor(channel, CallCredentialsProvider.NO_CREDENTIALS, retrier)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun executeRemotely_smoke() {
        executionService.whenExecute(DUMMY_REQUEST).thenAck().thenAck().thenDone(DUMMY_RESPONSE)

        val response: ExecuteResponse? =
            executor.executeRemotely(context, DUMMY_REQUEST, OperationObserver.NO_OP)

        assertThat(response).isEqualTo(DUMMY_RESPONSE)
        Truth.assertThat(executionService.getExecTimes()).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun executeRemotely_errorInOperation_retryExecute() {
        executionService.whenExecute(DUMMY_REQUEST).thenError(java.lang.RuntimeException("Unavailable"))
        executionService.whenExecute(DUMMY_REQUEST).thenError(Code.UNAVAILABLE)
        executionService.whenExecute(DUMMY_REQUEST).thenAck().thenDone(DUMMY_RESPONSE)

        val response: ExecuteResponse? =
            executor.executeRemotely(context, DUMMY_REQUEST, OperationObserver.NO_OP)

        Truth.assertThat(executionService.getExecTimes()).isEqualTo(3)
        assertThat(response).isEqualTo(DUMMY_RESPONSE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun executeRemotely_errorInResponse_retryExecute() {
        executionService
            .whenExecute(DUMMY_REQUEST)
            .thenDone(
                ExecuteResponse.newBuilder()
                    .setStatus(com.google.rpc.Status.newBuilder().setCode(Code.UNAVAILABLE_VALUE))
                    .build()
            )
        executionService.whenExecute(DUMMY_REQUEST).thenAck().thenDone(DUMMY_RESPONSE)

        val response: ExecuteResponse? =
            executor.executeRemotely(context, DUMMY_REQUEST, OperationObserver.NO_OP)

        Truth.assertThat(executionService.getExecTimes()).isEqualTo(2)
        assertThat(response).isEqualTo(DUMMY_RESPONSE)
    }

    @org.junit.Test
    fun executeRemotely_unretriableErrorInResponse_reportError() {
        executionService
            .whenExecute(DUMMY_REQUEST)
            .thenDone(
                ExecuteResponse.newBuilder()
                    .setStatus(com.google.rpc.Status.newBuilder().setCode(Code.INVALID_ARGUMENT_VALUE))
                    .build()
            )
        executionService.whenExecute(DUMMY_REQUEST).thenAck().thenDone(DUMMY_RESPONSE)

        val e: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable {
                    executor.executeRemotely(
                        context,
                        DUMMY_REQUEST,
                        OperationObserver.NO_OP
                    )
                })

        Truth.assertThat(e).hasMessageThat().contains("INVALID_ARGUMENT")
        Truth.assertThat(executionService.getExecTimes()).isEqualTo(1)
    }

    @org.junit.Test
    fun executeRemotely_retryExecuteAndFail() {
        for (i in 0..MAX_RETRY_ATTEMPTS * 2) {
            executionService.whenExecute(DUMMY_REQUEST).thenError(Code.UNAVAILABLE)
        }

        val exception: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable {
                    executor.executeRemotely(
                        context,
                        DUMMY_REQUEST,
                        OperationObserver.NO_OP
                    )
                })

        Truth.assertThat(executionService.getExecTimes()).isEqualTo(MAX_RETRY_ATTEMPTS + 1)
        Truth.assertThat(exception).hasMessageThat().contains("UNAVAILABLE")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun executeRemotely_executeAndWait() {
        executionService.whenExecute(DUMMY_REQUEST).thenAck().finish()
        executionService.whenWaitExecution(DUMMY_REQUEST).thenDone(DUMMY_RESPONSE)

        val response: ExecuteResponse? =
            executor.executeRemotely(context, DUMMY_REQUEST, OperationObserver.NO_OP)

        Truth.assertThat(executionService.getExecTimes()).isEqualTo(1)
        Truth.assertThat(executionService.getWaitTimes()).isEqualTo(1)
        assertThat(response).isEqualTo(DUMMY_RESPONSE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun executeRemotely_executeAndRetryWait() {
        executionService.whenExecute(DUMMY_REQUEST).thenAck().finish()
        executionService.whenWaitExecution(DUMMY_REQUEST).thenDone(DUMMY_RESPONSE)

        val response: ExecuteResponse? =
            executor.executeRemotely(context, DUMMY_REQUEST, OperationObserver.NO_OP)

        Truth.assertThat(executionService.getExecTimes()).isEqualTo(1)
        Truth.assertThat(executionService.getWaitTimes()).isEqualTo(1)
        assertThat(response).isEqualTo(DUMMY_RESPONSE)
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun executeRemotely_retryExecuteWhenUnauthenticated() {
        executionService.whenExecute(DUMMY_REQUEST).thenError(Code.UNAUTHENTICATED)
        executionService.whenExecute(DUMMY_REQUEST).thenAck().thenDone(DUMMY_RESPONSE)

        val response: ExecuteResponse? =
            executor.executeRemotely(context, DUMMY_REQUEST, OperationObserver.NO_OP)

        Truth.assertThat(executionService.getExecTimes()).isEqualTo(2)
        assertThat(response).isEqualTo(DUMMY_RESPONSE)
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun executeRemotely_retryWaitExecutionWhenUnauthenticated_errorRuntimeException() {
        // This test corresponds to the one in the original base class that used a
        // Status runtime exception while waiting.
        executionService.whenExecute(DUMMY_REQUEST).thenAck().finish()
        executionService
            .whenWaitExecution(DUMMY_REQUEST)
            .thenError(io.grpc.Status.UNAUTHENTICATED.asRuntimeException())
        executionService.whenWaitExecution(DUMMY_REQUEST).thenAck().thenDone(DUMMY_RESPONSE)

        val response: ExecuteResponse? =
            executor.executeRemotely(context, DUMMY_REQUEST, OperationObserver.NO_OP)

        Truth.assertThat(executionService.getExecTimes()).isEqualTo(1)
        Truth.assertThat(executionService.getWaitTimes()).isEqualTo(2)
        assertThat(response).isEqualTo(DUMMY_RESPONSE)
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun executeRemotely_retryWaitExecutionWhenUnauthenticated_errorCodeUnauthenticated() {
        // Variant from the former derived test that injected an UNAUTHENTICATED
        // status through the fake execution service using Code.UNAUTHENTICATED.
        executionService.whenExecute(DUMMY_REQUEST).thenAck().finish()
        executionService.whenWaitExecution(DUMMY_REQUEST).thenError(Code.UNAUTHENTICATED)
        executionService.whenExecute(DUMMY_REQUEST).thenAck().thenDone(DUMMY_RESPONSE)

        val response: ExecuteResponse? =
            executor.executeRemotely(context, DUMMY_REQUEST, OperationObserver.NO_OP)

        Truth.assertThat(executionService.getExecTimes()).isEqualTo(2)
        Truth.assertThat(executionService.getWaitTimes()).isEqualTo(1)
        assertThat(response).isEqualTo(DUMMY_RESPONSE)
    }

    @org.junit.Test
    fun executeRemotely_operationWithoutResult_crashes() {
        executionService.whenExecute(DUMMY_REQUEST).thenDone()

        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable {
                executor.executeRemotely(
                    context,
                    DUMMY_REQUEST,
                    OperationObserver.NO_OP
                )
            })

        // Shouldn't retry in this case
        Truth.assertThat(executionService.getExecTimes()).isEqualTo(1)
    }

    @org.junit.Test
    fun executeRemotely_responseWithoutResult_crashes() {
        executionService.whenExecute(DUMMY_REQUEST).thenDone(ExecuteResponse.getDefaultInstance())

        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable {
                executor.executeRemotely(
                    context,
                    DUMMY_REQUEST,
                    OperationObserver.NO_OP
                )
            })

        Truth.assertThat(executionService.getExecTimes()).isEqualTo(1)
    }

    @org.junit.Test
    fun executeRemotely_operationWithoutResult_shouldNotCrash() {
        executionService.whenExecute(DUMMY_REQUEST).thenDone()

        Truth.assertThat(
            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                org.junit.function.ThrowingRunnable {
                    executor.executeRemotely(
                        context,
                        DUMMY_REQUEST,
                        OperationObserver.NO_OP
                    )
                })
        )
            .hasMessageThat()
            .contains("Unexpected result of remote execution: result not set")

        // Shouldn't retry in this case
        Truth.assertThat(executionService.getExecTimes()).isEqualTo(1)
    }

    @org.junit.Test
    fun executeRemotely_responseWithoutResult_shouldNotRetry() {
        executionService.whenExecute(DUMMY_REQUEST).thenDone(ExecuteResponse.getDefaultInstance())

        Truth.assertThat(
            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                org.junit.function.ThrowingRunnable {
                    executor.executeRemotely(
                        context,
                        DUMMY_REQUEST,
                        OperationObserver.NO_OP
                    )
                })
        )
            .hasMessageThat()
            .contains("Unexpected result of remote execution: no result")

        // Shouldn't retry in this case
        Truth.assertThat(executionService.getExecTimes()).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun executeRemotely_retryExecuteIfNotFound() {
        executionService.whenExecute(DUMMY_REQUEST).thenAck().finish()
        executionService.whenWaitExecution(DUMMY_REQUEST).thenError(Code.NOT_FOUND)
        executionService.whenExecute(DUMMY_REQUEST).thenAck().finish()
        executionService.whenWaitExecution(DUMMY_REQUEST).thenDone(DUMMY_RESPONSE)

        val response: ExecuteResponse? =
            executor.executeRemotely(context, DUMMY_REQUEST, OperationObserver.NO_OP)

        Truth.assertThat(executionService.getExecTimes()).isEqualTo(2)
        Truth.assertThat(executionService.getWaitTimes()).isEqualTo(2)
        assertThat(response).isEqualTo(DUMMY_RESPONSE)
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun executeRemotely_retryExecuteIfNotFoundStream() {
        executionService.whenExecute(DUMMY_REQUEST).thenAck().finish()
        executionService
            .whenWaitExecution(DUMMY_REQUEST)
            .thenError(io.grpc.Status.NOT_FOUND.asRuntimeException())
        executionService.whenExecute(DUMMY_REQUEST).thenAck().finish()
        executionService.whenWaitExecution(DUMMY_REQUEST).thenDone(DUMMY_RESPONSE)

        val response: ExecuteResponse? =
            executor.executeRemotely(context, DUMMY_REQUEST, OperationObserver.NO_OP)

        Truth.assertThat(executionService.getExecTimes()).isEqualTo(2)
        Truth.assertThat(executionService.getWaitTimes()).isEqualTo(2)
        assertThat(response).isEqualTo(DUMMY_RESPONSE)
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun executeRemotely_retryExecuteOnFinish() {
        executionService.whenExecute(DUMMY_REQUEST).thenAck().finish()
        executionService.whenWaitExecution(DUMMY_REQUEST).thenAck().finish()
        executionService.whenWaitExecution(DUMMY_REQUEST).thenAck().thenDone(DUMMY_RESPONSE)

        val response: ExecuteResponse? =
            executor.executeRemotely(context, DUMMY_REQUEST, OperationObserver.NO_OP)

        Truth.assertThat(executionService.getExecTimes()).isEqualTo(1)
        Truth.assertThat(executionService.getWaitTimes()).isEqualTo(2)
        assertThat(response).isEqualTo(DUMMY_RESPONSE)
    }

    @org.junit.Test
    fun executeRemotely_notFoundLoop_reportError() {
        for (i in 0..MAX_RETRY_ATTEMPTS) {
            executionService.whenExecute(DUMMY_REQUEST).thenAck().finish()
            executionService.whenWaitExecution(DUMMY_REQUEST).thenError(Code.NOT_FOUND)
        }

        val e: IOException =
            org.junit.Assert.assertThrows<IOException>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable {
                    executor.executeRemotely(
                        context,
                        DUMMY_REQUEST,
                        OperationObserver.NO_OP
                    )
                })

        Truth.assertThat(e).hasCauseThat().isInstanceOf(ExecutionStatusException::class.java)
        val executionStatusException: ExecutionStatusException = e.cause as ExecutionStatusException
        assertThat(executionStatusException.getStatus().getCode()).isEqualTo(io.grpc.Status.Code.NOT_FOUND)
        Truth.assertThat(executionService.getExecTimes()).isEqualTo(MAX_RETRY_ATTEMPTS + 1)
        Truth.assertThat(executionService.getWaitTimes()).isEqualTo(MAX_RETRY_ATTEMPTS + 1)
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun executeRemotely_notifyObserver() {
        executionService.whenExecute(DUMMY_REQUEST).thenAck().thenDone(DUMMY_RESPONSE)

        val notified: MutableList<Operation?> = java.util.ArrayList<Operation?>()
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            executor.executeRemotely(context, DUMMY_REQUEST, notified::add)

        Truth.assertThat(notified)
            .containsExactly(
                FakeExecutionService.Companion.ackOperation(DUMMY_REQUEST),
                FakeExecutionService.Companion.doneOperation(DUMMY_REQUEST, DUMMY_RESPONSE)
            )
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun executeRemotely_retryExecuteOnNoResultDoneOperation() {
        executionService.whenExecute(DUMMY_REQUEST).thenError(Code.UNAVAILABLE)
        executionService.whenExecute(DUMMY_REQUEST).thenAck().thenDone(DUMMY_RESPONSE)

        val response: ExecuteResponse? =
            executor.executeRemotely(context, DUMMY_REQUEST, OperationObserver.NO_OP)

        Truth.assertThat(executionService.getExecTimes()).isEqualTo(2)
        Truth.assertThat(executionService.getWaitTimes()).isEqualTo(0)
        assertThat(response).isEqualTo(DUMMY_RESPONSE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun executeRemotely_executeAndRetryWait_forever() {
        executionService.whenExecute(DUMMY_REQUEST).thenAck().finish()
        val errorTimes = MAX_RETRY_ATTEMPTS
        for (i in 0..<errorTimes) {
            executionService
                .whenWaitExecution(DUMMY_REQUEST)
                .thenError(io.grpc.Status.DEADLINE_EXCEEDED.asRuntimeException())
        }
        executionService.whenWaitExecution(DUMMY_REQUEST).thenDone(DUMMY_RESPONSE)

        val response: ExecuteResponse? =
            executor.executeRemotely(context, DUMMY_REQUEST, OperationObserver.NO_OP)

        Truth.assertThat(executionService.getExecTimes()).isEqualTo(1)
        Truth.assertThat(executionService.getWaitTimes()).isEqualTo(errorTimes + 1)
        assertThat(response).isEqualTo(DUMMY_RESPONSE)
    }

    @org.junit.Test
    fun executeRemotely_executeAndRetryWait_failForConsecutiveErrors() {
        executionService.whenExecute(DUMMY_REQUEST).thenAck().finish()
        for (i in 0..<MAX_RETRY_ATTEMPTS * 2) {
            executionService
                .whenWaitExecution(DUMMY_REQUEST)
                .thenError(io.grpc.Status.UNAVAILABLE.asRuntimeException())
        }

        org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable {
                executor.executeRemotely(
                    context,
                    DUMMY_REQUEST,
                    OperationObserver.NO_OP
                )
            })

        Truth.assertThat(executionService.getExecTimes()).isEqualTo(1)
        Truth.assertThat(executionService.getWaitTimes()).isEqualTo(MAX_RETRY_ATTEMPTS + 1)
    }

    companion object {
        private const val MAX_RETRY_ATTEMPTS = 5

        // ---------------------------------------------------------------------------
        // Test constants
        // ---------------------------------------------------------------------------
        private val DUMMY_OUTPUT: OutputFile? = OutputFile.newBuilder()
            .setPath("dummy.txt")
            .setDigest(
                Digest.newBuilder()
                    .setHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
                    .setSizeBytes(0)
                    .build()
            )
            .build()

        private val DUMMY_REQUEST: ExecuteRequest? = ExecuteRequest.newBuilder()
            .setInstanceName("dummy")
            .setActionDigest(
                Digest.newBuilder()
                    .setHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
                    .setSizeBytes(0)
                    .build()
            )
            .build()

        private val DUMMY_RESPONSE: ExecuteResponse? = ExecuteResponse.newBuilder()
            .setResult(ActionResult.newBuilder().addOutputFiles(DUMMY_OUTPUT).build())
            .build()
    }
}
