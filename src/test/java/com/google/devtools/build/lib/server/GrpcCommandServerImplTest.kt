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

import com.google.devtools.build.lib.server.CommandProtos.RunRequest
import io.grpc.Server
import org.junit.Test

/** Unit tests for [GrpcCommandServerImpl].  */
@RunWith(JUnit4::class)
class GrpcCommandServerImplTest {
    @Test
    @Throws(Exception::class)
    fun testBlockingStreamObserver() {
        // This test attempts to verify that BlockingStreamObserver successfully blocks after some
        // number of onNext calls (however long it takes to fill up gRPCs internal buffers). In order to
        // trigger this behavior, we intentionally block the client after a few successful calls, then
        // wait a bit, and then check that the server has stopped prematurely. Unfortunately, we cannot
        // deterministically verify that the onNext call is blocking. A faulty implementation of
        // BlockingStreamObserver could pass this test if the sleep is too short. However, a correct
        // implementation should never fail this test. This test could start failing if gRPCs internal
        // buffer size is increased. If it fails after an upgrade of gRPC, you might want to check that.
        val serverDone: CountDownLatch = CountDownLatch(1)
        val clientBlocks: CountDownLatch = CountDownLatch(1)
        val clientUnblocks: CountDownLatch = CountDownLatch(1)
        val clientDone: CountDownLatch = CountDownLatch(1)
        val sentCount: AtomicInteger = AtomicInteger()
        val receiveCount: AtomicInteger = AtomicInteger()
        val serverImpl: CommandServerGrpc.CommandServerImplBase =
            object : CommandServerImplBase() {
                public override fun run(request: RunRequest?, observer: StreamObserver<RunResponse?>?) {
                    val serverCallStreamObserver: ServerCallStreamObserver<RunResponse?>? =
                        observer as ServerCallStreamObserver<RunResponse?>?
                    val blockingStreamObserver: GrpcCommandServerImpl.BlockingStreamObserver<RunResponse?> =
                        BlockingStreamObserver(
                            serverCallStreamObserver, RunResponse.getDefaultInstance()
                        )
                    val t =
                        Thread(
                            Runnable {
                                try {
                                    val response: RunResponse =
                                        RunResponse.newBuilder()
                                            .setStandardOutput(ByteString.copyFrom(ByteArray(1024)))
                                            .build()
                                    for (i in 0..99) {
                                        blockingStreamObserver.onNext(response.toByteArray())
                                        sentCount.incrementAndGet()
                                    }
                                    blockingStreamObserver.onCompleted()
                                    serverDone.countDown()
                                } catch (e: IOException) {
                                    throw IllegalStateException(e)
                                }
                            })
                    t.start()
                }
            }

        val uniqueName: String = InProcessServerBuilder.generateName()
        // Do not use .directExecutor here, as it makes both client and server run in the same thread.
        val server: Server =
            InProcessServerBuilder.forName(uniqueName)
                .addService(serverImpl)
                .executor(Executors.newFixedThreadPool(4))
                .build()
                .start()
        val channel: ManagedChannel =
            InProcessChannelBuilder.forName(uniqueName)
                .executor(Executors.newFixedThreadPool(4))
                .build()

        val stub: CommandServerStub = CommandServerGrpc.newStub(channel)
        stub.run(
            RunRequest.getDefaultInstance(),
            object : StreamObserver<RunResponse?>() {
                override fun onNext(value: RunResponse?) {
                    if (sentCount.get() >= 3) {
                        clientBlocks.countDown()
                        try {
                            clientUnblocks.await()
                        } catch (e: InterruptedException) {
                            throw IllegalStateException(e)
                        }
                    }
                    receiveCount.incrementAndGet()
                }

                override fun onError(t: Throwable?) {
                    throw IllegalStateException(t)
                }

                override fun onCompleted() {
                    clientDone.countDown()
                }
            })
        clientBlocks.await()
        // Wait a bit for the server to (hopefully) block. If the server does not block, then this may
        // be flaky.
        Thread.sleep(10)
        Truth.assertThat(sentCount.get()).isLessThan(5)
        clientUnblocks.countDown()
        serverDone.await()
        clientDone.await()
        server.shutdown()
        server.awaitTermination()
    }

    @Test
    @Throws(Exception::class)
    fun testBlockingStreamObserverClientCancel() {
        // This test attempts to verify that FlowControl unblocks if the client prematurely closes the
        // connection. In that case, FlowControl should observe the onCancel event and interrupt the
        // calling thread. I have observed this test failing with an intentionally introduced bug in
        // FlowControl.
        val serverDone: CountDownLatch = CountDownLatch(1)
        val clientDone: CountDownLatch = CountDownLatch(1)
        val sentCount: AtomicInteger = AtomicInteger()
        val receiveCount: AtomicInteger = AtomicInteger()
        val serverImpl: CommandServerGrpc.CommandServerImplBase =
            object : CommandServerImplBase() {
                public override fun run(request: RunRequest?, observer: StreamObserver<RunResponse?>?) {
                    val serverCallStreamObserver: ServerCallStreamObserver<RunResponse?>? =
                        observer as ServerCallStreamObserver<RunResponse?>?
                    val blockingStreamObserver: GrpcCommandServerImpl.BlockingStreamObserver<RunResponse?> =
                        BlockingStreamObserver(
                            serverCallStreamObserver, RunResponse.getDefaultInstance()
                        )
                    val t =
                        Thread(
                            Runnable {
                                try {
                                    val response: RunResponse =
                                        RunResponse.newBuilder()
                                            .setStandardOutput(ByteString.copyFrom(ByteArray(1024)))
                                            .build()
                                    for (i in 0..99) {
                                        blockingStreamObserver.onNext(response.toByteArray())
                                        sentCount.incrementAndGet()
                                    }
                                    // FlowControl should have interrupted the current thread after learning of
                                    // the server
                                    // cancel.
                                    Truth.assertThat(Thread.currentThread().isInterrupted()).isTrue()
                                    blockingStreamObserver.onCompleted()
                                    serverDone.countDown()
                                } catch (e: IOException) {
                                    throw IllegalStateException(e)
                                }
                            })
                    t.start()
                }
            }

        val uniqueName: String = InProcessServerBuilder.generateName()
        // Do not use .directExecutor here, as it makes both client and server run in the same thread.
        val server: Server =
            InProcessServerBuilder.forName(uniqueName)
                .addService(serverImpl)
                .executor(Executors.newFixedThreadPool(4))
                .build()
                .start()
        val channel: ManagedChannel =
            InProcessChannelBuilder.forName(uniqueName)
                .executor(Executors.newFixedThreadPool(4))
                .build()

        val stub: CommandServerStub = CommandServerGrpc.newStub(channel)
        stub.run(
            RunRequest.getDefaultInstance(),
            object : StreamObserver<RunResponse?>() {
                override fun onNext(value: RunResponse?) {
                    if (receiveCount.get() > 3) {
                        channel.shutdownNow()
                    }
                    receiveCount.incrementAndGet()
                }

                override fun onError(t: Throwable?) {
                    clientDone.countDown()
                }

                override fun onCompleted() {
                    clientDone.countDown()
                }
            })
        serverDone.await()
        clientDone.await()
        server.shutdown()
        server.awaitTermination()
    }

    @Test
    @Throws(Exception::class)
    fun testBlockingStreamObserverInterrupt() {
        // This test attempts to verify that BlockingStreamObserver does not hang if the current thread
        // is interrupted. The initial implementation of BlockingStreamObserver (which was never
        // submitted) would go into an infinite loop holding the lock on BlockingStreamObserver. This
        // would prevent any other thread from obtaining the lock on BlockingStreamObserver, and hang
        // the entire process. I have confirmed that this test fails with the original faulty
        // implementation of BlockingStreamObserver.
        val serverDone: CountDownLatch = CountDownLatch(1)
        val clientDone: CountDownLatch = CountDownLatch(1)
        val sentCount: AtomicInteger = AtomicInteger()
        val receiveCount: AtomicInteger = AtomicInteger()
        val serverImpl: CommandServerGrpc.CommandServerImplBase =
            object : CommandServerImplBase() {
                public override fun run(request: RunRequest?, observer: StreamObserver<RunResponse?>?) {
                    val serverCallStreamObserver: ServerCallStreamObserver<RunResponse?> =
                        observer as ServerCallStreamObserver<RunResponse?>
                    val blockingStreamObserver: BlockingStreamObserver<RunResponse?> =
                        BlockingStreamObserver(
                            serverCallStreamObserver, RunResponse.getDefaultInstance()
                        )
                    val t =
                        Thread(
                            Runnable {
                                try {
                                    val response: RunResponse =
                                        RunResponse.newBuilder()
                                            .setStandardOutput(ByteString.copyFrom(ByteArray(1024)))
                                            .build()
                                    // We want to trigger isReady() -> false, and we use sentCount to control
                                    // whether to sleep on the client side. Therefore, we only set sentCount
                                    // after
                                    // isReady() changes.
                                    var sent = 0
                                    while (serverCallStreamObserver.isReady()) {
                                        blockingStreamObserver.onNext(response.toByteArray())
                                        sent++
                                    }
                                    sentCount.set(sent)
                                    // If the current thread is interrupted, the subsequent onNext calls should
                                    // not hang, but complete eventually (they may block on flow control).
                                    Thread.currentThread().interrupt()
                                    for (i in 0..9) {
                                        blockingStreamObserver.onNext(response.toByteArray())
                                        sentCount.incrementAndGet()
                                    }
                                    blockingStreamObserver.onCompleted()
                                    serverDone.countDown()
                                } catch (e: IOException) {
                                    throw IllegalStateException(e)
                                }
                            })
                    t.start()
                }
            }

        val uniqueName: String = InProcessServerBuilder.generateName()
        // Do not use .directExecutor here, as it makes both client and server run in the same thread.
        val server: Server =
            InProcessServerBuilder.forName(uniqueName)
                .addService(serverImpl)
                .executor(Executors.newFixedThreadPool(4))
                .build()
                .start()
        val channel: ManagedChannel =
            InProcessChannelBuilder.forName(uniqueName)
                .executor(Executors.newFixedThreadPool(4))
                .build()

        val stub: CommandServerStub = CommandServerGrpc.newStub(channel)
        stub.run(
            RunRequest.getDefaultInstance(),
            object : StreamObserver<RunResponse?>() {
                override fun onNext(value: RunResponse?) {
                    if (sentCount.get() == 0) {
                        try {
                            Thread.sleep(1)
                        } catch (e: InterruptedException) {
                            throw IllegalStateException(e)
                        }
                    }
                    receiveCount.incrementAndGet()
                }

                override fun onError(t: Throwable?) {
                    throw IllegalStateException(t)
                }

                override fun onCompleted() {
                    clientDone.countDown()
                }
            })
        serverDone.await()
        clientDone.await()
        Truth.assertThat(sentCount.get()).isEqualTo(receiveCount.get())
        server.shutdown()
        server.awaitTermination()
    }
}
