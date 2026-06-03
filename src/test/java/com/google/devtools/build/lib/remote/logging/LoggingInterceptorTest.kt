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
package com.google.devtools.build.lib.remote.logging

import build.bazel.remote.execution.v2.ActionCacheGrpc
import com.google.common.collect.Iterators
import com.google.devtools.build.lib.testutil.ManualClock
import io.grpc.Channel
import io.grpc.MethodDescriptor
import io.grpc.Server
import io.grpc.Status
import org.junit.After
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.function.ThrowingRunnable
import org.mockito.Mock

/** Tests for [LoggingInterceptor]  */
@RunWith(JUnit4::class)
class LoggingInterceptorTest {
    private val fakeServerName = "fake server for " + javaClass
    private val serviceRegistry: MutableHandlerRegistry = MutableHandlerRegistry()
    private var fakeServer: Server? = null
    private var loggedChannel: Channel? = null
    private var interceptor: LoggingInterceptor? = null
    private var clock: ManualClock? = null

    @Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    @Mock
    private val logStream: AsynchronousMessageOutputStream<LogEntry?>? = null

    // This returns a logging interceptor where all calls are handled by the given handler.
    private fun getInterceptorWithAlwaysThisHandler(
        handler: LoggingHandler, outputFile: AsynchronousMessageOutputStream<LogEntry?>
    ): LoggingInterceptor {
        return object : LoggingInterceptor(outputFile, clock!!) {
            public override fun <ReqT, RespT> selectHandler(
                method: MethodDescriptor<ReqT?, RespT?>?
            ): LoggingHandler<ReqT?, RespT?> {
                return handler
            }
        }
    }

    @Before
    @Throws(Exception::class)
    fun setUp() {
        // Use a mutable service registry for later registering the service impl for each test case.
        fakeServer =
            InProcessServerBuilder.forName(fakeServerName)
                .fallbackHandlerRegistry(serviceRegistry)
                .directExecutor()
                .build()
                .start()
        clock = ManualClock()
        interceptor = LoggingInterceptor(logStream, clock!!)
        loggedChannel =
            ClientInterceptors.intercept(
                InProcessChannelBuilder.forName(fakeServerName).directExecutor().build(), interceptor
            )
    }

    @After
    @Throws(Exception::class)
    fun tearDown() {
        fakeServer!!.shutdownNow()
        fakeServer!!.awaitTermination()
    }

    @Test
    fun testCallOk() {
        val request: ReadRequest? = ReadRequest.newBuilder().setResourceName("test").build()
        val response: ReadResponse? =
            ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("abc")).build()

        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun read(request: ReadRequest?, responseObserver: StreamObserver<ReadResponse?>) {
                    clock!!.advanceMillis(1234)
                    responseObserver.onNext(response)
                    responseObserver.onCompleted()
                }
            })

        val handler: LoggingHandler<ReadRequest?, ReadResponse?> =
            Mockito.mock<LoggingHandler>(LoggingHandler::class.java)
        val details: RpcCallDetails? = RpcCallDetails.getDefaultInstance()
        Mockito.`when`<Any?>(handler.details).thenReturn(details)

        val interceptor = getInterceptorWithAlwaysThisHandler(handler, logStream)
        val channel: Channel? =
            ClientInterceptors.intercept(
                InProcessChannelBuilder.forName(fakeServerName).directExecutor().build(), interceptor
            )
        val stub: ByteStreamBlockingStub = ByteStreamGrpc.newBlockingStub(channel)

        val expectedEntry: LogEntry? =
            LogEntry.newBuilder()
                .setMethodName(ByteStreamGrpc.getReadMethod().getFullMethodName())
                .setDetails(details)
                .setStatus(com.google.rpc.Status.getDefaultInstance())
                .setStartTime(Timestamp.newBuilder().setSeconds(12).setNanos(300000000))
                .setEndTime(Timestamp.newBuilder().setSeconds(13).setNanos(534000000))
                .build()

        clock!!.advanceMillis(12300)
        stub.read(request).next()
        Mockito.verify<LoggingHandler<ReadRequest?, ReadResponse?>?>(handler).handleReq(request)
        Mockito.verify<LoggingHandler<ReadRequest?, ReadResponse?>?>(handler).handleResp(response)
        Mockito.verify<LoggingHandler<ReadRequest?, ReadResponse?>?>(handler).details
        Mockito.verify<Any?>(logStream).write(expectedEntry)
    }

    @Test
    fun testCallOkMultipleResponses() {
        val request: ReadRequest? = ReadRequest.newBuilder().setResourceName("test").build()
        val response1: ReadResponse? =
            ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("abc")).build()
        val response2: ReadResponse? =
            ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("def")).build()
        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun read(request: ReadRequest?, responseObserver: StreamObserver<ReadResponse?>) {
                    clock!!.advanceMillis(50)
                    responseObserver.onNext(response1)
                    clock!!.advanceMillis(1500)
                    responseObserver.onNext(response2)
                    responseObserver.onCompleted()
                }
            })

        val handler: LoggingHandler<ReadRequest?, ReadResponse?> =
            Mockito.mock<LoggingHandler>(LoggingHandler::class.java)
        val details: RpcCallDetails? = RpcCallDetails.getDefaultInstance()
        Mockito.`when`<Any?>(handler.details).thenReturn(details)

        val interceptor = getInterceptorWithAlwaysThisHandler(handler, logStream)
        val channel: Channel? =
            ClientInterceptors.intercept(
                InProcessChannelBuilder.forName(fakeServerName).directExecutor().build(), interceptor
            )
        val stub: ByteStreamBlockingStub = ByteStreamGrpc.newBlockingStub(channel)

        // Read both responses.
        Iterators.advance(stub.read(request), 2)

        val resultCaptor: ArgumentCaptor<ReadResponse?> =
            ArgumentCaptor.forClass<ReadResponse?, ReadResponse?>(ReadResponse::class.java)

        val expectedEntry: LogEntry? =
            LogEntry.newBuilder()
                .setMethodName(ByteStreamGrpc.getReadMethod().getFullMethodName())
                .setDetails(details)
                .setStatus(com.google.rpc.Status.getDefaultInstance())
                .setStartTime(Timestamp.getDefaultInstance())
                .setEndTime(Timestamp.newBuilder().setSeconds(1).setNanos(550000000))
                .build()

        Mockito.verify<LoggingHandler<ReadRequest?, ReadResponse?>?>(handler).handleReq(request)
        Mockito.verify<LoggingHandler<ReadRequest?, ReadResponse?>?>(handler, Mockito.times(2))
            .handleResp(resultCaptor.capture())
        assertThat(resultCaptor.getAllValues().get(0)).isEqualTo(response1)
        assertThat(resultCaptor.getAllValues().get(1)).isEqualTo(response2)
        Mockito.verify<LoggingHandler<ReadRequest?, ReadResponse?>?>(handler).details
        Mockito.verify<Any?>(logStream).write(expectedEntry)
    }

    @Test
    fun testCallOkMultipleRequests() {
        val request1: WriteRequest? =
            WriteRequest.newBuilder()
                .setResourceName("test")
                .setData(ByteString.copyFromUtf8("abc"))
                .build()
        val request2: WriteRequest? =
            WriteRequest.newBuilder()
                .setResourceName("test")
                .setData(ByteString.copyFromUtf8("def"))
                .build()
        val response: WriteResponse? = WriteResponse.newBuilder().setCommittedSize(6).build()
        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun write(streamObserver: StreamObserver<WriteResponse?>): StreamObserver<WriteRequest?> {
                    return object : StreamObserver<WriteRequest?> {
                        override fun onNext(writeRequest: WriteRequest?) {}

                        override fun onError(throwable: Throwable?) {}

                        override fun onCompleted() {
                            streamObserver.onNext(response)
                            streamObserver.onCompleted()
                        }
                    }
                }
            })

        val handler: LoggingHandler<WriteRequest?, WriteResponse?> =
            Mockito.mock<LoggingHandler>(LoggingHandler::class.java)
        val details: RpcCallDetails? = RpcCallDetails.getDefaultInstance()
        Mockito.`when`<Any?>(handler.details).thenReturn(details)

        val interceptor = getInterceptorWithAlwaysThisHandler(handler, logStream)
        val channel: Channel? =
            ClientInterceptors.intercept(
                InProcessChannelBuilder.forName(fakeServerName).directExecutor().build(), interceptor
            )
        val stub: ByteStreamStub = ByteStreamGrpc.newStub(channel)

        clock!!.advanceMillis(1000)
        val responseObserver: StreamObserver<WriteResponse?>? =
            Mockito.mock<StreamObserver<*>?>(StreamObserver::class.java)
        // Write both responses.
        val requester: StreamObserver<WriteRequest?> = stub.write(responseObserver)
        requester.onNext(request1)
        requester.onNext(request2)
        clock!!.advanceMillis(1000)
        requester.onCompleted()

        val resultCaptor: ArgumentCaptor<WriteRequest?> =
            ArgumentCaptor.forClass<WriteRequest?, WriteRequest?>(WriteRequest::class.java)

        val expectedEntry: LogEntry? =
            LogEntry.newBuilder()
                .setMethodName(ByteStreamGrpc.getWriteMethod().getFullMethodName())
                .setDetails(details)
                .setStatus(com.google.rpc.Status.getDefaultInstance())
                .setStartTime(Timestamp.newBuilder().setSeconds(1))
                .setEndTime(Timestamp.newBuilder().setSeconds(2))
                .build()

        Mockito.verify<LoggingHandler<WriteRequest?, WriteResponse?>?>(handler, Mockito.times(2))
            .handleReq(resultCaptor.capture())
        assertThat(resultCaptor.getAllValues().get(0)).isEqualTo(request1)
        assertThat(resultCaptor.getAllValues().get(1)).isEqualTo(request2)
        Mockito.verify<LoggingHandler<WriteRequest?, WriteResponse?>?>(handler).handleResp(response)
        Mockito.verify<LoggingHandler<WriteRequest?, WriteResponse?>?>(handler).details
        Mockito.verify<Any?>(logStream).write(expectedEntry)
    }

    @Test
    fun testCallWithError() {
        val request: ReadRequest? = ReadRequest.newBuilder().setResourceName("test").build()
        val error = Status.NOT_FOUND.withDescription("not found")

        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun read(request: ReadRequest?, responseObserver: StreamObserver<ReadResponse?>) {
                    clock!!.advanceMillis(100)
                    responseObserver.onError(error.asRuntimeException())
                }
            })

        val handler: LoggingHandler<ReadRequest?, ReadResponse?> =
            Mockito.mock<LoggingHandler>(LoggingHandler::class.java)
        val details: RpcCallDetails? = RpcCallDetails.getDefaultInstance()
        Mockito.`when`<Any?>(handler.details).thenReturn(details)

        val interceptor = getInterceptorWithAlwaysThisHandler(handler, logStream)
        val channel: Channel? =
            ClientInterceptors.intercept(
                InProcessChannelBuilder.forName(fakeServerName).directExecutor().build(), interceptor
            )
        val stub: ByteStreamBlockingStub = ByteStreamGrpc.newBlockingStub(channel)

        clock!!.advanceMillis(1500)
        Assert.assertThrows<StatusRuntimeException?>(
            StatusRuntimeException::class.java,
            ThrowingRunnable { stub.read(request).next() })

        val expectedEntry: LogEntry? =
            LogEntry.newBuilder()
                .setMethodName(ByteStreamGrpc.getReadMethod().getFullMethodName())
                .setDetails(details)
                .setStatus(
                    com.google.rpc.Status.newBuilder()
                        .setCode(error.getCode().value())
                        .setMessage(error.getDescription())
                )
                .setStartTime(Timestamp.newBuilder().setSeconds(1).setNanos(500000000))
                .setEndTime(Timestamp.newBuilder().setSeconds(1).setNanos(600000000))
                .build()

        Mockito.verify<LoggingHandler<ReadRequest?, ReadResponse?>?>(handler).handleReq(request)
        Mockito.verify<LoggingHandler<ReadRequest?, ReadResponse?>?>(handler, Mockito.never())
            .handleResp(ArgumentMatchers.any<ReadResponse?>())
        Mockito.verify<LoggingHandler<ReadRequest?, ReadResponse?>?>(handler).details
        Mockito.verify<Any?>(logStream).write(expectedEntry)
    }

    @Test
    fun testExecuteCallOk() {
        val request: ExecuteRequest? =
            ExecuteRequest.newBuilder()
                .setInstanceName("test-instance")
                .setActionDigest(DigestUtil.buildDigest("test", 8))
                .build()
        val response1: Operation? = Operation.newBuilder().setName("test-name").build()
        val response2: Operation? =
            Operation.newBuilder()
                .setName("test-name")
                .setDone(true)
                .setResponse(Any.pack(request))
                .build()

        serviceRegistry.addService(
            object : ExecutionImplBase() {
                public override fun execute(request: ExecuteRequest?, responseObserver: StreamObserver<Operation?>) {
                    responseObserver.onNext(response1)
                    clock!!.advanceMillis(2200)
                    responseObserver.onNext(response2)
                    clock!!.advanceMillis(1100)
                    responseObserver.onCompleted()
                }
            })

        clock!!.advanceMillis(50000)
        val replies: MutableIterator<Operation?> =
            ExecutionGrpc.newBlockingStub(loggedChannel).execute(request)

        // Read both responses.
        while (replies.hasNext()) {
            replies.next()
        }

        val expectedEntry: LogEntry? =
            LogEntry.newBuilder()
                .setMethodName(ExecutionGrpc.getExecuteMethod().getFullMethodName())
                .setDetails(
                    RpcCallDetails.newBuilder()
                        .setExecute(
                            ExecuteDetails.newBuilder()
                                .setRequest(request)
                                .addResponses(response1)
                                .addResponses(response2)
                        )
                )
                .setStatus(com.google.rpc.Status.getDefaultInstance())
                .setStartTime(Timestamp.newBuilder().setSeconds(50))
                .setEndTime(Timestamp.newBuilder().setSeconds(53).setNanos(300000000))
                .build()
        Mockito.verify<Any?>(logStream).write(expectedEntry)
    }

    @Test
    fun testExecuteCallFail() {
        val request: ExecuteRequest? =
            ExecuteRequest.newBuilder()
                .setInstanceName("test-instance")
                .setActionDigest(DigestUtil.buildDigest("test", 8))
                .build()
        val error = Status.NOT_FOUND.withDescription("not found")
        serviceRegistry.addService(
            object : ExecutionImplBase() {
                public override fun execute(request: ExecuteRequest?, responseObserver: StreamObserver<Operation?>) {
                    clock!!.advanceMillis(1100)
                    responseObserver.onError(error.asRuntimeException())
                }
            })
        clock!!.advanceMillis(20000000000001L)
        val replies: MutableIterator<Operation?> = ExecutionGrpc.newBlockingStub(loggedChannel).execute(request)
        Assert.assertThrows<StatusRuntimeException?>(
            StatusRuntimeException::class.java,
            ThrowingRunnable { replies.hasNext() })
        val expectedEntry: LogEntry? =
            LogEntry.newBuilder()
                .setMethodName(ExecutionGrpc.getExecuteMethod().getFullMethodName())
                .setDetails(
                    RpcCallDetails.newBuilder()
                        .setExecute(ExecuteDetails.newBuilder().setRequest(request))
                )
                .setStatus(
                    com.google.rpc.Status.newBuilder()
                        .setCode(error.getCode().value())
                        .setMessage(error.getDescription())
                )
                .setStartTime(Timestamp.newBuilder().setSeconds(20000000000L).setNanos(1000000))
                .setEndTime(Timestamp.newBuilder().setSeconds(20000000001L).setNanos(101000000))
                .build()
        Mockito.verify<Any?>(logStream).write(expectedEntry)
    }

    @Test
    fun testFindMissingBlobsCallOk() {
        val testDigest: Digest? = DigestUtil.buildDigest("test", 8)
        val request: FindMissingBlobsRequest? =
            FindMissingBlobsRequest.newBuilder()
                .addBlobDigests(testDigest)
                .setInstanceName("test-instance")
                .build()
        val response: FindMissingBlobsResponse? =
            FindMissingBlobsResponse.newBuilder().addMissingBlobDigests(testDigest).build()
        serviceRegistry.addService(
            object : ContentAddressableStorageImplBase() {
                public override fun findMissingBlobs(
                    request: FindMissingBlobsRequest?,
                    responseObserver: StreamObserver<FindMissingBlobsResponse?>
                ) {
                    clock!!.advanceMillis(200)
                    responseObserver.onNext(response)
                    responseObserver.onCompleted()
                }
            })

        val stub: ContentAddressableStorageBlockingStub =
            ContentAddressableStorageGrpc.newBlockingStub(loggedChannel)

        clock!!.advanceMillis(14900)
        stub.findMissingBlobs(request)
        val expectedEntry: LogEntry? =
            LogEntry.newBuilder()
                .setMethodName(
                    ContentAddressableStorageGrpc.getFindMissingBlobsMethod().getFullMethodName()
                )
                .setDetails(
                    RpcCallDetails.newBuilder()
                        .setFindMissingBlobs(
                            FindMissingBlobsDetails.newBuilder()
                                .setRequest(request)
                                .setResponse(response)
                        )
                )
                .setStatus(com.google.rpc.Status.getDefaultInstance())
                .setStartTime(Timestamp.newBuilder().setSeconds(14).setNanos(900000000))
                .setEndTime(Timestamp.newBuilder().setSeconds(15).setNanos(100000000))
                .build()
        Mockito.verify<Any?>(logStream).write(expectedEntry)
    }

    @Test
    fun testGetActionResultCallOk() {
        val testDigest: Digest? = DigestUtil.buildDigest("test", 8)
        val request: GetActionResultRequest? =
            GetActionResultRequest.newBuilder()
                .setActionDigest(testDigest)
                .setInstanceName("test-instance")
                .build()
        val response: ActionResult? =
            ActionResult.newBuilder()
                .addOutputFiles(OutputFile.newBuilder().setDigest(testDigest).setPath("root/test"))
                .setExitCode(1)
                .build()

        serviceRegistry.addService(
            object : ActionCacheImplBase() {
                public override fun getActionResult(
                    request: GetActionResultRequest?, responseObserver: StreamObserver<ActionResult?>
                ) {
                    clock!!.advanceMillis(22222)
                    responseObserver.onNext(response)
                    responseObserver.onCompleted()
                }
            })
        val stub: ActionCacheBlockingStub = ActionCacheGrpc.newBlockingStub(loggedChannel)

        clock!!.advanceMillis(11111)
        stub.getActionResult(request)
        val expectedEntry: LogEntry? =
            LogEntry.newBuilder()
                .setMethodName(ActionCacheGrpc.getGetActionResultMethod().getFullMethodName())
                .setDetails(
                    RpcCallDetails.newBuilder()
                        .setGetActionResult(
                            GetActionResultDetails.newBuilder()
                                .setRequest(request)
                                .setResponse(response)
                        )
                )
                .setStatus(com.google.rpc.Status.getDefaultInstance())
                .setStartTime(Timestamp.newBuilder().setSeconds(11).setNanos(111000000))
                .setEndTime(Timestamp.newBuilder().setSeconds(33).setNanos(333000000))
                .build()
        Mockito.verify<Any?>(logStream).write(expectedEntry)
    }

    @Test
    fun testUpdateActionResultCallOk() {
        val testDigest: Digest? = DigestUtil.buildDigest("test", 8)
        val actionResult: ActionResult? =
            ActionResult.newBuilder()
                .addOutputFiles(OutputFile.newBuilder().setDigest(testDigest).setPath("root/test"))
                .setExitCode(1)
                .build()

        val request: UpdateActionResultRequest? =
            UpdateActionResultRequest.newBuilder()
                .setActionDigest(testDigest)
                .setInstanceName("test-instance")
                .setActionResult(actionResult)
                .build()

        serviceRegistry.addService(
            object : ActionCacheImplBase() {
                public override fun updateActionResult(
                    request: UpdateActionResultRequest?, responseObserver: StreamObserver<ActionResult?>
                ) {
                    clock!!.advanceMillis(22222)
                    responseObserver.onNext(actionResult)
                    responseObserver.onCompleted()
                }
            })
        val stub: ActionCacheBlockingStub = ActionCacheGrpc.newBlockingStub(loggedChannel)

        clock!!.advanceMillis(11111)
        stub.updateActionResult(request)
        val expectedEntry: LogEntry? =
            LogEntry.newBuilder()
                .setMethodName(ActionCacheGrpc.getUpdateActionResultMethod().getFullMethodName())
                .setDetails(
                    RpcCallDetails.newBuilder()
                        .setUpdateActionResult(
                            UpdateActionResultDetails.newBuilder()
                                .setRequest(request)
                                .setResponse(actionResult)
                        )
                )
                .setStatus(com.google.rpc.Status.getDefaultInstance())
                .setStartTime(Timestamp.newBuilder().setSeconds(11).setNanos(111000000))
                .setEndTime(Timestamp.newBuilder().setSeconds(33).setNanos(333000000))
                .build()
        Mockito.verify<Any?>(logStream).write(expectedEntry)
    }

    @Test
    fun testGetCapabilitiesCallOk() {
        val request: GetCapabilitiesRequest? =
            GetCapabilitiesRequest.newBuilder()
                .setInstanceName("test-instance")
                .build()
        val response: ServerCapabilities? =
            ServerCapabilities.newBuilder()
                .setExecutionCapabilities(
                    ExecutionCapabilities.newBuilder().setExecEnabled(true).build()
                )
                .build()
        serviceRegistry.addService(
            object : CapabilitiesImplBase() {
                public override fun getCapabilities(
                    request: GetCapabilitiesRequest?, responseObserver: StreamObserver<ServerCapabilities?>
                ) {
                    clock!!.advanceMillis(22222)
                    responseObserver.onNext(response)
                    responseObserver.onCompleted()
                }
            })
        val stub: CapabilitiesBlockingStub = CapabilitiesGrpc.newBlockingStub(loggedChannel)

        clock!!.advanceMillis(11111)
        stub.getCapabilities(request)
        val expectedEntry: LogEntry? =
            LogEntry.newBuilder()
                .setMethodName(CapabilitiesGrpc.getGetCapabilitiesMethod().getFullMethodName())
                .setDetails(
                    RpcCallDetails.newBuilder()
                        .setGetCapabilities(
                            GetCapabilitiesDetails.newBuilder()
                                .setRequest(request)
                                .setResponse(response)
                        )
                )
                .setStatus(com.google.rpc.Status.getDefaultInstance())
                .setStartTime(Timestamp.newBuilder().setSeconds(11).setNanos(111000000))
                .setEndTime(Timestamp.newBuilder().setSeconds(33).setNanos(333000000))
                .build()
        Mockito.verify<Any?>(logStream).write(expectedEntry)
    }

    @Test
    fun testWaitExecutionCallOk() {
        val request: WaitExecutionRequest? = WaitExecutionRequest.newBuilder().setName("test-name").build()
        val response1: Operation? = Operation.newBuilder().setName("test-name").build()
        val response2: Operation? =
            Operation.newBuilder()
                .setName("test-name")
                .setDone(true)
                .setResponse(Any.pack(request))
                .build()

        serviceRegistry.addService(
            object : ExecutionImplBase() {
                public override fun waitExecution(
                    request: WaitExecutionRequest?, responseObserver: StreamObserver<Operation?>
                ) {
                    responseObserver.onNext(response1)
                    clock!!.advanceMillis(2200)
                    responseObserver.onNext(response2)
                    clock!!.advanceMillis(1100)
                    responseObserver.onCompleted()
                }
            })

        clock!!.advanceMillis(50000)
        val replies: MutableIterator<Operation?> =
            ExecutionGrpc.newBlockingStub(loggedChannel).waitExecution(request)

        // Read both responses.
        while (replies.hasNext()) {
            replies.next()
        }

        val expectedEntry: LogEntry? =
            LogEntry.newBuilder()
                .setMethodName(ExecutionGrpc.getWaitExecutionMethod().getFullMethodName())
                .setDetails(
                    RpcCallDetails.newBuilder()
                        .setWaitExecution(
                            WaitExecutionDetails.newBuilder()
                                .setRequest(request)
                                .addResponses(response1)
                                .addResponses(response2)
                        )
                )
                .setStatus(com.google.rpc.Status.getDefaultInstance())
                .setStartTime(Timestamp.newBuilder().setSeconds(50))
                .setEndTime(Timestamp.newBuilder().setSeconds(53).setNanos(300000000))
                .build()
        Mockito.verify<Any?>(logStream).write(expectedEntry)
    }

    @Test
    fun testWaitExecutionCallFail() {
        val request: WaitExecutionRequest? = WaitExecutionRequest.newBuilder().setName("test-name").build()
        val response: Operation? = Operation.newBuilder().setName("test-name").build()
        val error = Status.DEADLINE_EXCEEDED.withDescription("timed out")

        serviceRegistry.addService(
            object : ExecutionImplBase() {
                public override fun waitExecution(
                    request: WaitExecutionRequest?, responseObserver: StreamObserver<Operation?>
                ) {
                    clock!!.advanceMillis(100)
                    responseObserver.onNext(response)
                    clock!!.advanceMillis(100)
                    responseObserver.onError(error.asRuntimeException())
                }
            })

        clock!!.advanceMillis(2000)
        val replies: MutableIterator<Operation?> =
            ExecutionGrpc.newBlockingStub(loggedChannel).waitExecution(request)
        Truth.assertThat(replies.hasNext()).isTrue()
        assertThat(replies.next()).isEqualTo(response)
        Assert.assertThrows<StatusRuntimeException?>(
            StatusRuntimeException::class.java,
            ThrowingRunnable { replies.hasNext() })

        val expectedEntry: LogEntry? =
            LogEntry.newBuilder()
                .setMethodName(ExecutionGrpc.getWaitExecutionMethod().getFullMethodName())
                .setDetails(
                    RpcCallDetails.newBuilder()
                        .setWaitExecution(
                            WaitExecutionDetails.newBuilder()
                                .setRequest(request)
                                .addResponses(response)
                        )
                )
                .setStatus(
                    com.google.rpc.Status.newBuilder()
                        .setCode(error.getCode().value())
                        .setMessage(error.getDescription())
                )
                .setStartTime(Timestamp.newBuilder().setSeconds(2))
                .setEndTime(Timestamp.newBuilder().setSeconds(2).setNanos(200000000))
                .build()
        Mockito.verify<Any?>(logStream).write(expectedEntry)
    }

    @Test
    fun testReadCallOk() {
        val request: ReadRequest? = ReadRequest.newBuilder().setResourceName("test-resource").build()
        val response1: ReadResponse? =
            ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("abc")).build()
        val response2: ReadResponse? =
            ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("def")).build()

        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun read(request: ReadRequest?, responseObserver: StreamObserver<ReadResponse?>) {
                    responseObserver.onNext(response1)
                    responseObserver.onNext(response2)
                    clock!!.advanceMillis(2000)
                    responseObserver.onCompleted()
                }
            })

        clock!!.advanceMillis(500000)
        val replies: MutableIterator<ReadResponse?> = ByteStreamGrpc.newBlockingStub(loggedChannel).read(request)

        // Read both responses.
        while (replies.hasNext()) {
            replies.next()
        }

        val expectedEntry: LogEntry? =
            LogEntry.newBuilder()
                .setMethodName(ByteStreamGrpc.getReadMethod().getFullMethodName())
                .setDetails(
                    RpcCallDetails.newBuilder()
                        .setRead(
                            ReadDetails.newBuilder()
                                .setRequest(request)
                                .setNumReads(2)
                                .setBytesRead(6)
                        )
                )
                .setStatus(com.google.rpc.Status.getDefaultInstance())
                .setStartTime(Timestamp.newBuilder().setSeconds(500))
                .setEndTime(Timestamp.newBuilder().setSeconds(502))
                .build()
        Mockito.verify<Any?>(logStream).write(expectedEntry)
    }

    @Test
    fun testReadCallFail() {
        val request: ReadRequest? = ReadRequest.newBuilder().setResourceName("test-resource").build()
        val response1: ReadResponse? =
            ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("abc")).build()
        val error = Status.DEADLINE_EXCEEDED.withDescription("timeout")

        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun read(request: ReadRequest?, responseObserver: StreamObserver<ReadResponse?>) {
                    responseObserver.onNext(response1)
                    clock!!.advanceMillis(100)
                    responseObserver.onError(error.asRuntimeException())
                }
            })
        val replies: MutableIterator<ReadResponse?> = ByteStreamGrpc.newBlockingStub(loggedChannel).read(request)
        Truth.assertThat(replies.hasNext()).isTrue()
        assertThat(replies.next()).isEqualTo(response1)
        Assert.assertThrows<StatusRuntimeException?>(
            StatusRuntimeException::class.java,
            ThrowingRunnable { replies.hasNext() })

        val expectedEntry: LogEntry? =
            LogEntry.newBuilder()
                .setMethodName(ByteStreamGrpc.getReadMethod().getFullMethodName())
                .setDetails(
                    RpcCallDetails.newBuilder()
                        .setRead(
                            ReadDetails.newBuilder()
                                .setRequest(request)
                                .setNumReads(1)
                                .setBytesRead(3)
                        )
                )
                .setStatus(
                    com.google.rpc.Status.newBuilder()
                        .setCode(error.getCode().value())
                        .setMessage(error.getDescription())
                )
                .setStartTime(Timestamp.getDefaultInstance())
                .setEndTime(Timestamp.newBuilder().setNanos(100000000))
                .build()
        Mockito.verify<Any?>(logStream).write(expectedEntry)
    }

    @Test
    fun testWriteCallOk() {
        val request1: WriteRequest? =
            WriteRequest.newBuilder()
                .setResourceName("test1")
                .setData(ByteString.copyFromUtf8("abc"))
                .build()
        val request2: WriteRequest? =
            WriteRequest.newBuilder()
                .setResourceName("test2")
                .setData(ByteString.copyFromUtf8("def"))
                .build()
        val response: WriteResponse? = WriteResponse.newBuilder().setCommittedSize(6).build()
        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun write(streamObserver: StreamObserver<WriteResponse?>): StreamObserver<WriteRequest?> {
                    return object : StreamObserver<WriteRequest?> {
                        override fun onNext(writeRequest: WriteRequest?) {}

                        override fun onError(throwable: Throwable?) {}

                        override fun onCompleted() {
                            streamObserver.onNext(response)
                            streamObserver.onCompleted()
                        }
                    }
                }
            })

        val stub: ByteStreamStub = ByteStreamGrpc.newStub(loggedChannel)
        val responseObserver: StreamObserver<WriteResponse?>? =
            Mockito.mock<StreamObserver<*>?>(StreamObserver::class.java)

        clock!!.advanceMillis(10000)
        // Request three writes, the first identical with the third.
        val requester: StreamObserver<WriteRequest?> = stub.write(responseObserver)
        requester.onNext(request1)
        clock!!.advanceMillis(100)
        requester.onNext(request2)
        clock!!.advanceMillis(200)
        requester.onNext(request1)
        clock!!.advanceMillis(100)
        requester.onCompleted()

        val expectedEntry: LogEntry? =
            LogEntry.newBuilder()
                .setMethodName(ByteStreamGrpc.getWriteMethod().getFullMethodName())
                .setDetails(
                    RpcCallDetails.newBuilder()
                        .setWrite(
                            WriteDetails.newBuilder()
                                .addResourceNames("test1")
                                .addResourceNames("test2")
                                .addOffsets(0)
                                .addOffsets(0)
                                .addOffsets(0) // finish write is empty
                                .setResponse(response)
                                .setBytesSent(9)
                                .setNumWrites(3)
                        )
                )
                .setStatus(com.google.rpc.Status.getDefaultInstance())
                .setStartTime(Timestamp.newBuilder().setSeconds(10))
                .setEndTime(Timestamp.newBuilder().setSeconds(10).setNanos(400000000))
                .build()

        Mockito.verify<Any?>(logStream).write(expectedEntry)
    }

    @Test
    fun testWriteCallOffsetAndFinishWriteCompounding() {
        val request1: WriteRequest =
            WriteRequest.newBuilder()
                .setResourceName("test1")
                .setData(ByteString.copyFromUtf8("abc"))
                .setWriteOffset(10)
                .build()
        val request2: WriteRequest =
            WriteRequest.newBuilder()
                .setData(ByteString.copyFromUtf8("def"))
                .setWriteOffset(request1.getWriteOffset() + request1.getData().size())
                .build()
        val response: WriteResponse? = WriteResponse.newBuilder().setCommittedSize(6).build()
        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun write(streamObserver: StreamObserver<WriteResponse?>): StreamObserver<WriteRequest?> {
                    return object : StreamObserver<WriteRequest?> {
                        override fun onNext(writeRequest: WriteRequest?) {}

                        override fun onError(throwable: Throwable?) {}

                        override fun onCompleted() {
                            streamObserver.onNext(response)
                            streamObserver.onCompleted()
                        }
                    }
                }
            })
        val stub: ByteStreamStub = ByteStreamGrpc.newStub(loggedChannel)
        val responseObserver: StreamObserver<WriteResponse?>? =
            Mockito.mock<StreamObserver<*>?>(StreamObserver::class.java)

        clock!!.advanceMillis(10000)
        // Request three writes, the first identical with the third, but offset correctly and
        // finish_writing
        val requester: StreamObserver<WriteRequest?> = stub.write(responseObserver)
        requester.onNext(request1)
        clock!!.advanceMillis(100)
        requester.onNext(request2)
        clock!!.advanceMillis(200)
        requester.onNext(
            request1.toBuilder()
                .setWriteOffset(request2.getWriteOffset() + request2.getData().size())
                .setFinishWrite(true)
                .build()
        )
        clock!!.advanceMillis(100)
        requester.onCompleted()

        val expectedEntry: LogEntry? =
            LogEntry.newBuilder()
                .setMethodName(ByteStreamGrpc.getWriteMethod().getFullMethodName())
                .setDetails(
                    RpcCallDetails.newBuilder()
                        .setWrite(
                            WriteDetails.newBuilder()
                                .addResourceNames("test1")
                                .addResourceNames("")
                                .addOffsets(request1.getWriteOffset())
                                .addFinishWrites(
                                    10 + request1.getData().size() * 2 + request2.getData().size()
                                )
                                .setResponse(response)
                                .setBytesSent(9)
                                .setNumWrites(3)
                        )
                )
                .setStatus(com.google.rpc.Status.getDefaultInstance())
                .setStartTime(Timestamp.newBuilder().setSeconds(10))
                .setEndTime(Timestamp.newBuilder().setSeconds(10).setNanos(400000000))
                .build()
        Mockito.verify<Any?>(logStream).write(expectedEntry)
    }

    @Test
    fun testWriteCallFail() {
        val request: WriteRequest? =
            WriteRequest.newBuilder()
                .setResourceName("test")
                .setData(ByteString.copyFromUtf8("abc"))
                .build()
        val error = Status.DEADLINE_EXCEEDED.withDescription("timeout")
        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun write(streamObserver: StreamObserver<WriteResponse?>?): StreamObserver<WriteRequest?>? {
                    return Mockito.mock<StreamObserver<*>?>(StreamObserver::class.java)
                }
            })
        val stub: ByteStreamStub = ByteStreamGrpc.newStub(loggedChannel)
        val responseObserver: StreamObserver<WriteResponse?>? =
            Mockito.mock<StreamObserver<*>?>(StreamObserver::class.java)
        clock!!.advanceMillis(10000000000L)

        // Write both responses.
        val requester: StreamObserver<WriteRequest?> = stub.write(responseObserver)
        requester.onNext(request)
        clock!!.advanceMillis(10000000000L)
        requester.onError(error.asRuntimeException())

        val expectedCancel = Status.CANCELLED.withCause(error.asRuntimeException())
        val expectedEntry: LogEntry? =
            LogEntry.newBuilder()
                .setMethodName(ByteStreamGrpc.getWriteMethod().getFullMethodName())
                .setStatus(
                    com.google.rpc.Status.newBuilder()
                        .setCode(expectedCancel.getCode().value())
                        .setMessage(expectedCancel.getCause().toString())
                )
                .setDetails(
                    RpcCallDetails.newBuilder()
                        .setWrite(
                            WriteDetails.newBuilder()
                                .addResourceNames("test")
                                .addOffsets(0)
                                .setNumWrites(1)
                                .setBytesSent(3)
                        )
                )
                .setStartTime(Timestamp.newBuilder().setSeconds(10000000))
                .setEndTime(Timestamp.newBuilder().setSeconds(20000000))
                .build()
        Mockito.verify<Any?>(logStream).write(expectedEntry)
    }

    @Test
    fun testQueryWriteStatusCallOk() {
        val request: QueryWriteStatusRequest? =
            QueryWriteStatusRequest.newBuilder().setResourceName("test").build()
        val response: QueryWriteStatusResponse? =
            QueryWriteStatusResponse.newBuilder().setCommittedSize(10).build()
        serviceRegistry.addService(
            object : ByteStreamImplBase() {
                public override fun queryWriteStatus(
                    request: QueryWriteStatusRequest?,
                    responseObserver: StreamObserver<QueryWriteStatusResponse?>
                ) {
                    clock!!.advanceMillis(22222)
                    responseObserver.onNext(response)
                    responseObserver.onCompleted()
                }
            })
        val stub: ByteStreamBlockingStub = ByteStreamGrpc.newBlockingStub(loggedChannel)

        clock!!.advanceMillis(11111)
        stub.queryWriteStatus(request)

        val expectedEntry: LogEntry? =
            LogEntry.newBuilder()
                .setMethodName(ByteStreamGrpc.getQueryWriteStatusMethod().getFullMethodName())
                .setDetails(
                    RpcCallDetails.newBuilder()
                        .setQueryWriteStatus(
                            QueryWriteStatusDetails.newBuilder()
                                .setRequest(request)
                                .setResponse(response)
                        )
                )
                .setStatus(com.google.rpc.Status.getDefaultInstance())
                .setStartTime(Timestamp.newBuilder().setSeconds(11).setNanos(111000000))
                .setEndTime(Timestamp.newBuilder().setSeconds(33).setNanos(333000000))
                .build()
        Mockito.verify<Any?>(logStream).write(expectedEntry)
    }
}
