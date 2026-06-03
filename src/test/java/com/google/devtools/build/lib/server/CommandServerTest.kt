// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.server

import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import com.google.devtools.build.lib.clock.JavaClock
import com.google.devtools.build.lib.runtime.BlazeCommandResult
import com.google.devtools.build.lib.testutil.TestUtils
import io.grpc.Server
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import java.io.OutputStream
import java.time.Duration
import java.util.*
import java.util.function.Supplier

/** Unit tests for [CommandServer].  */
@RunWith(JUnit4::class)
class CommandServerTest {
    private val fileSystem: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)

    internal class ServerAndStub(server: CommandServer, stub: CommandServerStub) {
        val server: CommandServer
        val stub: CommandServerStub

        init {
            this.server = server
            this.stub = stub
        }
    }

    @Throws(Exception::class)
    private fun createServerAndStub(dispatcher: CommandDispatcher?): ServerAndStub {
        val serverDirectory: Path = fileSystem.getPath("/bazel_server_directory")
        serverDirectory.createDirectoryAndParents()

        val name: String = InProcessServerBuilder.generateName()

        val grpcCommandServer: GrpcCommandServer =
            object : GrpcCommandServerImpl() {
                @Throws(IOException::class)
                protected override fun bind(port: Int): Server? {
                    return InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .addService(this)
                        .build()
                        .start()
                }
            }

        val server: CommandServer =
            CommandServer(
                grpcCommandServer,
                dispatcher,
                ShutdownHooks.createUnregistered(),
                PidFileWatcher(fileSystem.getPath("/thread-not-running-dont-need"), SERVER_PID),
                JavaClock(),  /* port= */
                0,
                REQUEST_COOKIE,
                RESPONSE_COOKIE,
                serverDirectory,
                SERVER_PID,  /* maxIdleSeconds= */
                1000,  /* shutdownOnLowSysMem= */
                false,  /* doIdleServerTasks= */
                true,
                "slow interrupt message suffix"
            )

        server.serve()

        val stub: CommandServerStub =
            CommandServerGrpc.newStub(InProcessChannelBuilder.forName(name).directExecutor().build())

        return ServerAndStub(server, stub)
    }

    @Test
    @Throws(Exception::class)
    fun testSendingSimpleMessage() {
        val commandExtension: Any? = Any.pack(EnvironmentVariable.getDefaultInstance()) // Arbitrary message.
        val argsReceived: AtomicReference<MutableList<String?>?> = AtomicReference<MutableList<String?>?>()
        val commandExtensionsReceived: AtomicReference<MutableList<Any?>?> = AtomicReference<MutableList<Any?>?>()
        val dispatcher: CommandDispatcher =
            object : CommandDispatcher() {
                public override fun exec(
                    invocationPolicy: InvocationPolicy?,
                    args: MutableList<String?>?,
                    outErr: OutErr?,
                    lockingMode: LockingMode?,
                    uiVerbosity: UiVerbosity?,
                    clientDescription: String?,
                    firstContactTimeMillis: Long,
                    startupOptionsTaggedWithBazelRc: Optional<MutableList<Pair<String?, String?>?>?>?,
                    idleTaskResultsSupplier: Supplier<ImmutableList<IdleTask.Result?>?>?,
                    commandExtensions: MutableList<Any?>?,
                    commandExtensionReporter: CommandExtensionReporter?
                ): BlazeCommandResult {
                    argsReceived.set(args)
                    commandExtensionsReceived.set(commandExtensions)
                    return BlazeCommandResult.success()
                }
            }

        val serverAndStub = createServerAndStub(dispatcher)
        val server: CommandServer = serverAndStub.server
        val stub: CommandServerStub = serverAndStub.stub

        val done: CountDownLatch = CountDownLatch(1)
        val responses: MutableList<RunResponse?> = ArrayList<RunResponse?>()
        stub.run(
            createRequest("Foo").toBuilder().addCommandExtensions(commandExtension).build(),
            createResponseObserver(responses, done)
        )
        done.await()
        server.shutdown()
        server.awaitTermination()

        Truth.assertThat(argsReceived.get()).containsExactly("Foo")
        Truth.assertThat(commandExtensionsReceived.get()).containsExactly(commandExtension)

        Truth.assertThat(responses).hasSize(2)
        assertThat(responses.get(0).getFinished()).isFalse()
        assertThat(responses.get(0).getCookie()).isNotEmpty()
        assertThat(responses.get(1).getFinished()).isTrue()
        assertThat(responses.get(1).getExitCode()).isEqualTo(0)
        assertThat(responses.get(1).hasFailureDetail()).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun testReceiveStreamingCommandExtensions() {
        // Arrange: Set up a command that streams back three command extensions, using latches to
        // pause between each extension sent back.
        val commandExtension1: Any? = Any.pack(Int32Value.of(4))
        val commandExtension2: Any? = Any.pack(Int32Value.of(8))
        val commandExtension3: Any? = Any.pack(Int32Value.of(15))

        val afterFirstExtensionLatch: CountDownLatch = CountDownLatch(1)
        val beforeSecondExtensionLatch: CountDownLatch = CountDownLatch(1)
        val afterSecondExtensionLatch: CountDownLatch = CountDownLatch(1)
        val beforeThirdExtensionLatch: CountDownLatch = CountDownLatch(1)
        val afterThirdExtensionLatch: CountDownLatch = CountDownLatch(1)
        val dispatcher: CommandDispatcher =
            CommandDispatcher { policy, args, outErr, lockMode, uiVerbosity, clientDesc, startMs, startOpts, idleTaskResultsSupplier, cmdExts, cmdExtOut ->
                try {
                    // Send the first extension.
                    cmdExtOut.report(commandExtension1)
                    afterFirstExtensionLatch.countDown()
                    // Send the second extension.
                    beforeSecondExtensionLatch.await(TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    cmdExtOut.report(commandExtension2)
                    afterSecondExtensionLatch.countDown()
                    // Send the third extension.
                    beforeThirdExtensionLatch.await(TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    cmdExtOut.report(commandExtension3)
                    afterThirdExtensionLatch.countDown()
                } catch (e: IOException) {
                    throw IllegalStateException(e)
                }
                BlazeCommandResult.success()
            }

        val serverAndStub = createServerAndStub(dispatcher)
        val server: CommandServer = serverAndStub.server
        val stub: CommandServerStub = serverAndStub.stub

        // Act: Start the streaming RPC.
        val responses: MutableList<RunResponse?> = ArrayList<RunResponse?>()
        val done: CountDownLatch = CountDownLatch(1)
        stub.run(createRequest("Foo"), createResponseObserver(responses, done))

        // Assert: Verify extensions arrive in a streaming fashion.
        // Wait for the first extension and check it.
        afterFirstExtensionLatch.await()
        assertThat(Iterables.getLast<RunResponse?>(responses).getCommandExtensionsList())
            .containsExactly(commandExtension1)
        beforeSecondExtensionLatch.countDown()
        // Wait for the second extension and check it.
        afterSecondExtensionLatch.await()
        assertThat(Iterables.getLast<RunResponse?>(responses).getCommandExtensionsList())
            .containsExactly(commandExtension2)
        beforeThirdExtensionLatch.countDown()
        // Wait for the RPC to complete and look for the third extension in the second-to-last response.
        afterThirdExtensionLatch.await()
        done.await()
        assertThat(responses.get(responses.size - 2).getCommandExtensionsList())
            .containsExactly(commandExtension3)

        // Clean up RPC and server.
        server.shutdown()
        server.awaitTermination()
    }

    @Test
    @Throws(Exception::class)
    fun testClosingClientShouldInterrupt() {
        val done: CountDownLatch = CountDownLatch(1)
        val dispatcher: CommandDispatcher =
            object : CommandDispatcher() {
                public override fun exec(
                    invocationPolicy: InvocationPolicy?,
                    args: MutableList<String?>?,
                    outErr: OutErr?,
                    lockingMode: LockingMode?,
                    uiVerbosity: UiVerbosity?,
                    clientDescription: String?,
                    firstContactTimeMillis: Long,
                    startupOptionsTaggedWithBazelRc: Optional<MutableList<Pair<String?, String?>?>?>?,
                    idleTaskResultsSupplier: Supplier<ImmutableList<IdleTask.Result?>?>?,
                    commandExtensions: MutableList<Any?>?,
                    commandExtensionReporter: CommandExtensionReporter?
                ): BlazeCommandResult {
                    synchronized(this) {
                        Assert.assertThrows<InterruptedException?>(
                            InterruptedException::class.java,
                            ThrowingRunnable { (this as Object).wait() })
                    }
                    // The only way this can happen is if the current thread is interrupted.
                    done.countDown()
                    return BlazeCommandResult.failureDetail(
                        FailureDetail.newBuilder()
                            .setInterrupted(Interrupted.newBuilder().setCode(Code.INTERRUPTED_UNKNOWN))
                            .build()
                    )
                }
            }

        val serverAndStub = createServerAndStub(dispatcher)
        val server: CommandServer = serverAndStub.server
        val stub: CommandServerStub = serverAndStub.stub

        stub.run(
            createRequest("Foo"),
            object : StreamObserver<RunResponse?>() {
                override fun onNext(value: RunResponse?) {
                    server.shutdownNow()
                    done.countDown()
                }

                override fun onError(t: Throwable?) {}

                override fun onCompleted() {}
            })
        server.awaitTermination()
        done.await()
    }

    @Test
    @Throws(Exception::class)
    fun testStream() {
        val dispatcher: CommandDispatcher =
            object : CommandDispatcher() {
                public override fun exec(
                    invocationPolicy: InvocationPolicy?,
                    args: MutableList<String?>?,
                    outErr: OutErr,
                    lockingMode: LockingMode?,
                    uiVerbosity: UiVerbosity?,
                    clientDescription: String?,
                    firstContactTimeMillis: Long,
                    startupOptionsTaggedWithBazelRc: Optional<MutableList<Pair<String?, String?>?>?>?,
                    idleTaskResultsSupplier: Supplier<ImmutableList<IdleTask.Result?>?>?,
                    commandExtensions: MutableList<Any?>?,
                    commandExtensionReporter: CommandExtensionReporter
                ): BlazeCommandResult {
                    val out: OutputStream = outErr.getOutputStream()
                    try {
                        commandExtensionReporter.report(Any.pack(Int32Value.of(23)))
                        for (i in 0..9) {
                            out.write(ByteArray(1024))
                        }
                        commandExtensionReporter.report(Any.pack(Int32Value.of(42)))
                    } catch (e: IOException) {
                        throw IllegalStateException(e)
                    }
                    return BlazeCommandResult.withResponseExtensions(
                        BlazeCommandResult.success(),
                        ImmutableList.of<E?>(
                            Any.pack(StringValue.of("foo")),
                            Any.pack(BytesValue.of(ByteString.copyFromUtf8("bar")))
                        )
                    )
                }
            }

        val serverAndStub = createServerAndStub(dispatcher)
        val server: CommandServer = serverAndStub.server
        val stub: CommandServerStub = serverAndStub.stub

        val done: CountDownLatch = CountDownLatch(1)
        val responses: MutableList<RunResponse?> = ArrayList<RunResponse?>()
        stub.run(createRequest("Foo"), createResponseObserver(responses, done))
        done.await()
        server.shutdown()
        server.awaitTermination()

        Truth.assertThat(responses).hasSize(14)
        assertThat(responses.get(0).getFinished()).isFalse()
        assertThat(responses.get(0).getCookie()).isNotEmpty()
        assertThat(responses.get(1).getFinished()).isFalse()
        assertThat(responses.get(1).getCookie()).isNotEmpty()
        assertThat(responses.get(1).getCommandExtensionsList())
            .containsExactly(Any.pack(Int32Value.of(23)))
        for (i in 2..11) {
            assertThat(responses.get(i).getFinished()).isFalse()
            assertThat(responses.get(i).getStandardOutput().toByteArray()).isEqualTo(ByteArray(1024))
            assertThat(responses.get(i).getCommandExtensionsList()).isEmpty()
        }
        assertThat(responses.get(12).getFinished()).isFalse()
        assertThat(responses.get(12).getCookie()).isNotEmpty()
        assertThat(responses.get(12).getCommandExtensionsList())
            .containsExactly(Any.pack(Int32Value.of(42)))
        assertThat(responses.get(13).getFinished()).isTrue()
        assertThat(responses.get(13).getExitCode()).isEqualTo(0)
        assertThat(responses.get(13).hasFailureDetail()).isFalse()
        assertThat(responses.get(13).getCommandExtensionsList())
            .containsExactly(
                Any.pack(StringValue.of("foo")),
                Any.pack(BytesValue.of(ByteString.copyFromUtf8("bar")))
            )
    }

    @Test
    @Throws(Exception::class)
    fun badCookie() {
        runBadCommandTest(
            RunRequest.newBuilder().setCookie("bad-cookie").setClientDescription("client-description"),
            FailureDetail.newBuilder()
                .setMessage("Invalid RunRequest: bad cookie")
                .setGrpcServer(GrpcServer.newBuilder().setCode(GrpcServer.Code.BAD_COOKIE))
                .build()
        )
    }

    @Test
    @Throws(Exception::class)
    fun emptyClientDescription() {
        runBadCommandTest(
            RunRequest.newBuilder().setCookie(REQUEST_COOKIE).setClientDescription(""),
            FailureDetail.newBuilder()
                .setMessage("Invalid RunRequest: no client description")
                .setGrpcServer(GrpcServer.newBuilder().setCode(GrpcServer.Code.NO_CLIENT_DESCRIPTION))
                .build()
        )
    }

    @Throws(Exception::class)
    private fun runBadCommandTest(runRequestBuilder: RunRequest.Builder, failureDetail: FailureDetail?) {
        val serverAndStub = createServerAndStub(throwingDispatcher())
        val server: CommandServer = serverAndStub.server
        val stub: CommandServerStub = serverAndStub.stub

        val done: CountDownLatch = CountDownLatch(1)
        val responses: MutableList<RunResponse?> = ArrayList<RunResponse?>()

        stub.run(
            runRequestBuilder.addArg(ByteString.copyFromUtf8("Foo")).build(),
            createResponseObserver(responses, done)
        )
        done.await()
        server.shutdown()
        server.awaitTermination()

        Truth.assertThat(responses).hasSize(1)
        assertThat(responses.get(0).getFinished()).isTrue()
        assertThat(responses.get(0).getExitCode()).isEqualTo(36)
        assertThat(responses.get(0).hasFailureDetail()).isTrue()
        assertThat(responses.get(0).getFailureDetail()).isEqualTo(failureDetail)
    }

    @Test
    @Throws(Exception::class)
    fun unparseableInvocationPolicy() {
        val serverAndStub = createServerAndStub(throwingDispatcher())
        val server: CommandServer = serverAndStub.server
        val stub: CommandServerStub = serverAndStub.stub

        val done: CountDownLatch = CountDownLatch(1)
        val responses: MutableList<RunResponse?> = ArrayList<RunResponse?>()

        stub.run(
            RunRequest.newBuilder()
                .setCookie(REQUEST_COOKIE)
                .setClientDescription("client-description")
                .setInvocationPolicy("invalid-invocation-policy")
                .addArg(ByteString.copyFromUtf8("Foo"))
                .build(),
            createResponseObserver(responses, done)
        )
        done.await()
        server.shutdown()
        server.awaitTermination()

        Truth.assertThat(responses).hasSize(3)
        assertThat(responses.get(2).getFinished()).isTrue()
        assertThat(responses.get(2).getExitCode()).isEqualTo(2)
        assertThat(responses.get(2).hasFailureDetail()).isTrue()
        assertThat(responses.get(2).getFailureDetail())
            .isEqualTo(
                FailureDetail.newBuilder()
                    .setMessage(
                        "Invocation policy parsing failed: Malformed value of --invocation_policy: "
                                + "invalid-invocation-policy"
                    )
                    .setCommand(
                        Command.newBuilder().setCode(Command.Code.INVOCATION_POLICY_PARSE_FAILURE)
                    )
                    .build()
            )
    }

    @Test
    @Throws(Exception::class)
    fun testInterruptStream() {
        val done: CountDownLatch = CountDownLatch(1)
        val dispatcher: CommandDispatcher =
            object : CommandDispatcher() {
                public override fun exec(
                    invocationPolicy: InvocationPolicy?,
                    args: MutableList<String?>?,
                    outErr: OutErr,
                    lockingMode: LockingMode?,
                    uiVerbosity: UiVerbosity?,
                    clientDescription: String?,
                    firstContactTimeMillis: Long,
                    startupOptionsTaggedWithBazelRc: Optional<MutableList<Pair<String?, String?>?>?>?,
                    idleTaskResultsSupplier: Supplier<ImmutableList<IdleTask.Result?>?>?,
                    commandExtensions: MutableList<Any?>?,
                    commandExtensionReporter: CommandExtensionReporter?
                ): BlazeCommandResult {
                    val out: OutputStream = outErr.getOutputStream()
                    try {
                        while (true) {
                            if (Thread.interrupted()) {
                                return BlazeCommandResult.failureDetail(
                                    FailureDetail.newBuilder()
                                        .setInterrupted(
                                            Interrupted.newBuilder().setCode(Code.INTERRUPTED_UNKNOWN)
                                        )
                                        .build()
                                )
                            }
                            out.write(ByteArray(1024))
                        }
                    } catch (e: IOException) {
                        throw IllegalStateException(e)
                    }
                }
            }

        val serverAndStub = createServerAndStub(dispatcher)
        val server: CommandServer = serverAndStub.server
        val stub: CommandServerStub = serverAndStub.stub

        val responses: MutableList<RunResponse?> = ArrayList<RunResponse?>()
        stub.run(
            createRequest("Foo"),
            object : StreamObserver<RunResponse?>() {
                override fun onNext(value: RunResponse?) {
                    responses.add(value)
                    if (responses.size == 10) {
                        server.shutdownNow()
                    }
                }

                override fun onError(t: Throwable?) {
                    done.countDown()
                }

                override fun onCompleted() {
                    done.countDown()
                }
            })
        server.awaitTermination()
        done.await()
    }

    @Test
    @Throws(Exception::class)
    fun testCancel() {
        val dispatcher: CommandDispatcher =
            object : CommandDispatcher() {
                @Throws(InterruptedException::class)
                public override fun exec(
                    invocationPolicy: InvocationPolicy?,
                    args: MutableList<String?>?,
                    outErr: OutErr?,
                    lockingMode: LockingMode?,
                    uiVerbosity: UiVerbosity?,
                    clientDescription: String?,
                    firstContactTimeMillis: Long,
                    startupOptionsTaggedWithBazelRc: Optional<MutableList<Pair<String?, String?>?>?>?,
                    idleTaskResultsSupplier: Supplier<ImmutableList<IdleTask.Result?>?>?,
                    commandExtensions: MutableList<Any?>?,
                    commandExtensionReporter: CommandExtensionReporter?
                ): BlazeCommandResult? {
                    synchronized(this) {
                        (this as Object).wait()
                    }
                    // Interruption expected before this is reached.
                    throw IllegalStateException()
                }
            }

        val serverAndStub = createServerAndStub(dispatcher)
        val server: CommandServer = serverAndStub.server
        val stub: CommandServerStub = serverAndStub.stub

        val commandId: AtomicReference<String?> = AtomicReference<String?>()
        val gotCommandId: CountDownLatch = CountDownLatch(1)
        val secondResponse: AtomicReference<RunResponse?> = AtomicReference<RunResponse?>()
        val gotSecondResponse: CountDownLatch = CountDownLatch(1)

        stub.run(
            createRequest("Foo"),
            object : StreamObserver<RunResponse?>() {
                override fun onNext(value: RunResponse) {
                    val previousCommandId: String? = commandId.getAndSet(value.getCommandId())
                    if (previousCommandId == null) {
                        gotCommandId.countDown()
                    } else {
                        secondResponse.set(value)
                        gotSecondResponse.countDown()
                    }
                }

                override fun onError(t: Throwable?) {}

                override fun onCompleted() {}
            })
        // Wait until we've got the command id.
        gotCommandId.await()

        val cancelRequestComplete: CountDownLatch = CountDownLatch(1)
        val cancelRequest: CancelRequest? =
            CancelRequest.newBuilder().setCookie(REQUEST_COOKIE).setCommandId(commandId.get()).build()
        stub.cancel(
            cancelRequest,
            object : StreamObserver<CancelResponse?>() {
                override fun onNext(value: CancelResponse?) {}

                override fun onError(t: Throwable?) {}

                override fun onCompleted() {
                    cancelRequestComplete.countDown()
                }
            })
        cancelRequestComplete.await()
        gotSecondResponse.await()
        server.shutdown()
        server.awaitTermination()

        assertThat(secondResponse.get().getFinished()).isTrue()
        assertThat(secondResponse.get().getExitCode()).isEqualTo(8)
        assertThat(secondResponse.get().hasFailureDetail()).isTrue()
        assertThat(secondResponse.get().getFailureDetail().hasInterrupted()).isTrue()
        assertThat(secondResponse.get().getFailureDetail().getInterrupted().getCode())
            .isEqualTo(Code.INTERRUPTED)
    }

    /**
     * Ensure that if a command is marked as preemptible, running a second command interrupts the
     * first command.
     */
    @Test
    @Throws(Exception::class)
    fun testPreeempt() {
        val firstCommandArg = "Foo"
        val secondCommandArg = "Bar"

        val dispatcher: CommandDispatcher =
            object : CommandDispatcher() {
                public override fun exec(
                    invocationPolicy: InvocationPolicy?,
                    args: MutableList<String?>,
                    outErr: OutErr?,
                    lockingMode: LockingMode?,
                    uiVerbosity: UiVerbosity?,
                    clientDescription: String?,
                    firstContactTimeMillis: Long,
                    startupOptionsTaggedWithBazelRc: Optional<MutableList<Pair<String?, String?>?>?>?,
                    idleTaskResultsSupplier: Supplier<ImmutableList<IdleTask.Result?>?>?,
                    commandExtensions: MutableList<Any?>?,
                    commandExtensionReporter: CommandExtensionReporter?
                ): BlazeCommandResult {
                    if (args.contains(firstCommandArg)) {
                        while (true) {
                            try {
                                Thread.sleep(TestUtils.WAIT_TIMEOUT_MILLISECONDS)
                            } catch (e: InterruptedException) {
                                return BlazeCommandResult.failureDetail(
                                    FailureDetail.newBuilder()
                                        .setInterrupted(Interrupted.newBuilder().setCode(Code.INTERRUPTED))
                                        .build()
                                )
                            }
                        }
                    } else {
                        return BlazeCommandResult.success()
                    }
                }
            }

        val serverAndStub = createServerAndStub(dispatcher)
        val server: CommandServer = serverAndStub.server
        val stub: CommandServerStub = serverAndStub.stub

        val gotFoo: CountDownLatch = CountDownLatch(1)
        val lastFooResponse: AtomicReference<RunResponse?> = AtomicReference<RunResponse?>()
        val lastBarResponse: AtomicReference<RunResponse?> = AtomicReference<RunResponse?>()

        stub.run(
            createPreemptibleRequest(firstCommandArg),
            object : StreamObserver<RunResponse?>() {
                override fun onNext(value: RunResponse?) {
                    gotFoo.countDown()
                    lastFooResponse.set(value)
                }

                override fun onError(t: Throwable?) {}

                override fun onCompleted() {}
            })

        // Wait for the first command to startup
        gotFoo.await()

        val gotBar: CountDownLatch = CountDownLatch(1)
        stub.run(
            createRequest(secondCommandArg),
            object : StreamObserver<RunResponse?>() {
                override fun onNext(value: RunResponse?) {
                    gotBar.countDown()
                    lastBarResponse.set(value)
                }

                override fun onError(t: Throwable?) {}

                override fun onCompleted() {}
            })

        gotBar.await()
        server.shutdown()
        server.awaitTermination()

        assertThat(lastBarResponse.get().getFinished()).isTrue()
        assertThat(lastBarResponse.get().getExitCode()).isEqualTo(0)
        assertThat(lastFooResponse.get().getFinished()).isTrue()
        assertThat(lastFooResponse.get().getExitCode()).isEqualTo(8)
        assertThat(lastFooResponse.get().hasFailureDetail()).isTrue()
        assertThat(lastFooResponse.get().getFailureDetail().hasInterrupted()).isTrue()
        assertThat(lastFooResponse.get().getFailureDetail().getInterrupted().getCode())
            .isEqualTo(Code.INTERRUPTED)
    }

    /**
     * Ensure that if a command is marked as preemptible, running a second preemptible command
     * interrupts the first command.
     */
    @Test
    @Throws(Exception::class)
    fun testMultiPreeempt() {
        val firstCommandArg = "Foo"
        val secondCommandArg = "Bar"

        val dispatcher: CommandDispatcher =
            object : CommandDispatcher() {
                @Throws(InterruptedException::class)
                public override fun exec(
                    invocationPolicy: InvocationPolicy?,
                    args: MutableList<String?>,
                    outErr: OutErr?,
                    lockingMode: LockingMode?,
                    uiVerbosity: UiVerbosity?,
                    clientDescription: String?,
                    firstContactTimeMillis: Long,
                    startupOptionsTaggedWithBazelRc: Optional<MutableList<Pair<String?, String?>?>?>?,
                    idleTaskResultsSupplier: Supplier<ImmutableList<IdleTask.Result?>?>?,
                    commandExtensions: MutableList<Any?>?,
                    commandExtensionReporter: CommandExtensionReporter?
                ): BlazeCommandResult {
                    if (args.contains(firstCommandArg)) {
                        while (true) {
                            try {
                                Thread.sleep(TestUtils.WAIT_TIMEOUT_MILLISECONDS)
                            } catch (e: InterruptedException) {
                                return BlazeCommandResult.failureDetail(
                                    FailureDetail.newBuilder()
                                        .setInterrupted(Interrupted.newBuilder().setCode(Code.INTERRUPTED))
                                        .build()
                                )
                            }
                        }
                    } else {
                        return BlazeCommandResult.success()
                    }
                }
            }

        val serverAndStub = createServerAndStub(dispatcher)
        val server: CommandServer = serverAndStub.server
        val stub: CommandServerStub = serverAndStub.stub

        val gotFoo: CountDownLatch = CountDownLatch(1)
        val lastFooResponse: AtomicReference<RunResponse?> = AtomicReference<RunResponse?>()
        val lastBarResponse: AtomicReference<RunResponse?> = AtomicReference<RunResponse?>()

        stub.run(
            createPreemptibleRequest(firstCommandArg),
            object : StreamObserver<RunResponse?>() {
                override fun onNext(value: RunResponse?) {
                    gotFoo.countDown()
                    lastFooResponse.set(value)
                }

                override fun onError(t: Throwable?) {}

                override fun onCompleted() {}
            })

        // Wait for the first command to startup
        gotFoo.await()

        val gotBar: CountDownLatch = CountDownLatch(1)
        stub.run(
            createPreemptibleRequest(secondCommandArg),
            object : StreamObserver<RunResponse?>() {
                override fun onNext(value: RunResponse?) {
                    gotBar.countDown()
                    lastBarResponse.set(value)
                }

                override fun onError(t: Throwable?) {}

                override fun onCompleted() {}
            })

        gotBar.await()
        server.shutdown()
        server.awaitTermination()

        assertThat(lastBarResponse.get().getFinished()).isTrue()
        assertThat(lastBarResponse.get().getExitCode()).isEqualTo(0)
        assertThat(lastFooResponse.get().getFinished()).isTrue()
        assertThat(lastFooResponse.get().getExitCode()).isEqualTo(8)
        assertThat(lastFooResponse.get().hasFailureDetail()).isTrue()
        assertThat(lastFooResponse.get().getFailureDetail().hasInterrupted()).isTrue()
        assertThat(lastFooResponse.get().getFailureDetail().getInterrupted().getCode())
            .isEqualTo(Code.INTERRUPTED)
    }

    /**
     * Ensure that when a command is not marked as preemptible, running a second command does not
     * interrupt the first command.
     */
    @Test
    @Throws(Exception::class)
    fun testNoPreeempt() {
        val firstCommandArg = "Foo"
        val secondCommandArg = "Bar"

        val fooBlocked: CountDownLatch = CountDownLatch(1)
        val fooProceed: CountDownLatch = CountDownLatch(1)
        val barBlocked: CountDownLatch = CountDownLatch(1)
        val barProceed: CountDownLatch = CountDownLatch(1)

        val dispatcher: CommandDispatcher =
            object : CommandDispatcher() {
                @Throws(InterruptedException::class)
                public override fun exec(
                    invocationPolicy: InvocationPolicy?,
                    args: MutableList<String?>,
                    outErr: OutErr?,
                    lockingMode: LockingMode?,
                    uiVerbosity: UiVerbosity?,
                    clientDescription: String?,
                    firstContactTimeMillis: Long,
                    startupOptionsTaggedWithBazelRc: Optional<MutableList<Pair<String?, String?>?>?>?,
                    idleTaskResultsSupplier: Supplier<ImmutableList<IdleTask.Result?>?>?,
                    commandExtensions: MutableList<Any?>?,
                    commandExtensionReporter: CommandExtensionReporter?
                ): BlazeCommandResult {
                    if (args.contains(firstCommandArg)) {
                        fooBlocked.countDown()
                        fooProceed.await()
                    } else {
                        barBlocked.countDown()
                        barProceed.await()
                    }
                    return BlazeCommandResult.success()
                }
            }

        val serverAndStub = createServerAndStub(dispatcher)
        val server: CommandServer = serverAndStub.server
        val stub: CommandServerStub = serverAndStub.stub

        val lastFooResponse: AtomicReference<RunResponse?> = AtomicReference<RunResponse?>()
        val lastBarResponse: AtomicReference<RunResponse?> = AtomicReference<RunResponse?>()

        stub.run(
            createRequest(firstCommandArg),
            object : StreamObserver<RunResponse?>() {
                override fun onNext(value: RunResponse?) {
                    lastFooResponse.set(value)
                }

                override fun onError(t: Throwable?) {}

                override fun onCompleted() {}
            })
        fooBlocked.await()

        stub.run(
            createRequest(secondCommandArg),
            object : StreamObserver<RunResponse?>() {
                override fun onNext(value: RunResponse?) {
                    lastBarResponse.set(value)
                }

                override fun onError(t: Throwable?) {}

                override fun onCompleted() {}
            })
        barBlocked.await()

        // At this point both commands should be blocked on proceed latch, carry on...
        fooProceed.countDown()
        barProceed.countDown()

        server.shutdown()
        server.awaitTermination()

        assertThat(lastFooResponse.get().getFinished()).isTrue()
        assertThat(lastFooResponse.get().getExitCode()).isEqualTo(0)
        assertThat(lastBarResponse.get().getFinished()).isTrue()
        assertThat(lastBarResponse.get().getExitCode()).isEqualTo(0)
    }

    @Test
    @Throws(Exception::class)
    fun testIdleTasks() {
        val idleTaskRunning: CountDownLatch = CountDownLatch(1)
        val idleTaskResults: AtomicReference<ImmutableList<IdleTask.Result?>?> =
            AtomicReference<ImmutableList<IdleTask.Result?>?>()

        val idleTask: IdleTask =
            object : IdleTask() {
                override fun displayName(): String {
                    return "task"
                }

                override fun run() {
                    idleTaskRunning.countDown()
                }
            }

        val dispatcher: CommandDispatcher =
            CommandDispatcher { invocationPolicy, args, outErr, lockingMode, uiVerbosity, clientDescription, firstContactTimeMillis, startupOptionsTaggedWithBazelRc, idleTaskResultsSupplier, commandExtensions, commandExtensionReporter ->
                if (args.contains("1")) {
                    return@CommandDispatcher BlazeCommandResult.withIdleTasks(
                        BlazeCommandResult.success(), ImmutableList.of<E?>(idleTask)
                    )
                } else if (args.contains("2")) {
                    idleTaskResults.set(idleTaskResultsSupplier.get())
                    return@CommandDispatcher BlazeCommandResult.success()
                }
                throw IllegalStateException("Unexpected command")
            }

        val serverAndStub = createServerAndStub(dispatcher)
        val server: CommandServer = serverAndStub.server
        val stub: CommandServerStub = serverAndStub.stub

        val firstCmdResponses: MutableList<RunResponse?> = ArrayList<RunResponse?>()
        val firstCmdDone: CountDownLatch = CountDownLatch(1)
        stub.run(createRequest("1"), createResponseObserver(firstCmdResponses, firstCmdDone))
        firstCmdDone.await()

        idleTaskRunning.await()

        val secondCmdResponses: MutableList<RunResponse?> = ArrayList<RunResponse?>()
        val secondCmdDone: CountDownLatch = CountDownLatch(1)
        stub.run(createRequest("2"), createResponseObserver(secondCmdResponses, secondCmdDone))
        secondCmdDone.await()

        server.shutdown()
        server.awaitTermination()

        Truth.assertThat(
            idleTaskResults.get().stream()
                .map<IdleTask.Result?> { s: IdleTask.Result? -> IdleTask.Result(s!!.name, s.status, Duration.ZERO) })
            .containsExactly(IdleTask.Result("task", IdleTask.Status.SUCCESS, Duration.ZERO))
    }

    companion object {
        private const val SERVER_PID = 42
        private const val REQUEST_COOKIE = "request-cookie"
        private const val RESPONSE_COOKIE = "response-cookie"

        private fun createRequest(vararg args: String?): RunRequest {
            return RunRequest.newBuilder()
                .setCookie(REQUEST_COOKIE)
                .setClientDescription("client-description")
                .addAllArg(
                    Arrays.stream<String?>(args).map<ByteString?> { text: String? -> ByteString.copyFromUtf8(text) }
                        .collect(Collectors.toList()))
                .build()
        }

        private fun createPreemptibleRequest(vararg args: String?): RunRequest {
            return RunRequest.newBuilder()
                .setCookie(REQUEST_COOKIE)
                .setClientDescription("client-description")
                .setPreemptible(true)
                .addAllArg(
                    Arrays.stream<String?>(args).map<ByteString?> { text: String? -> ByteString.copyFromUtf8(text) }
                        .collect(Collectors.toList()))
                .build()
        }

        private fun createResponseObserver(
            responses: MutableList<RunResponse?>, done: CountDownLatch
        ): StreamObserver<RunResponse?> {
            return object : StreamObserver<RunResponse?>() {
                override fun onNext(value: RunResponse?) {
                    responses.add(value)
                }

                override fun onError(t: Throwable?) {
                    done.countDown()
                }

                override fun onCompleted() {
                    done.countDown()
                }
            }
        }

        private fun throwingDispatcher(): CommandDispatcher {
            return CommandDispatcher { invocationPolicy, args, outErr, lockingMode, uiVerbosity, clientDescription, firstContactTimeMillis, startupOptionsTaggedWithBazelRc, idleTaskResultsSupplier, commandExtensions, commandExtensionReporter ->
                throw IllegalStateException("Command exec not expected")
            }
        }
    }
}
