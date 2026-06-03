// Copyright 2024 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.buildeventservice

import com.google.devtools.build.lib.buildeventservice.client.BuildEventServiceClient

/**
 * Tests for Bazel's [BuildEventServiceTransport] with a [BuildEventServiceGrpcClient]
 * transport.
 */
@RunWith(JUnit4::class)
class BuildEventServiceTransportGrpcTest : AbstractBuildEventServiceTransportTest() {
    // This field is `public` to allow subclasses to override #createBesServer().
    var server: BuildEventRecorderGrpc? = null

    override fun createBesServer(): AbstractBuildEventRecorder {
        server = BuildEventRecorderGrpc()
        return server
    }

    override fun createBesClient(): BuildEventServiceClient? {
        com.google.common.base.Preconditions.checkState(
            server != null && server!!.port > 0,
            "gRPC BES server not started."
        )
        return createBesClient(server!!.port)
    }

    override fun createBesClient(serverPort: Int): BuildEventServiceClient? {
        return BuildEventServiceGrpcClient(
            ManagedChannelBuilder.forTarget("localhost:" + serverPort).usePlaintext().build(),  /* callCredentials= */
            null,  /* interceptor= */
            null
        )
    }

    override fun makeVfsHashFunction(): DigestHashFunction {
        return DigestHashFunction.SHA256
    }

    /**
     * A GRPC-protocol [AbstractBuildEventRecorder] that may be subclassed for alternative
     * testing scenarios.
     */
    class BuildEventRecorderGrpc : AbstractBuildEventRecorder() {
        protected var server: io.grpc.Server? = null

        @kotlin.concurrent.Volatile
        private var publishBuildToolEventStreamAccepted = false

        override fun startRpcServer(port: Int) {
            try {
                server =
                    ServerBuilder.forPort(port)
                        .addService(BuildEventService())
                        .build()
                        .start()
            } catch (e: IOException) {
                throw java.lang.RuntimeException(e)
            }
        }

        protected override fun stopRpcServer() {
            try {
                if (server != null) {
                    server.shutdownNow()
                    server.awaitTermination()
                    server = null
                }
            } catch (e: java.lang.InterruptedException) {
                throw java.lang.RuntimeException(e)
            }
        }

        val port: Int
            get() = if (server == null) -1 else server.getPort()

        override fun pickNewPort(): Int {
            try {
                return FreePortFinder.pickUnusedRandomPort()
            } catch (e: IOException) {
                throw java.lang.RuntimeException(e)
            } catch (e: java.lang.InterruptedException) {
                throw java.lang.RuntimeException(e)
            }
        }

        /** Faked `PublishBuildEvent` service, for testing.  */
        private inner class BuildEventService : PublishBuildEventImplBase() {
            public override fun publishLifecycleEvent(
                request: PublishLifecycleEventRequest, streamObserver: StreamObserver<Empty?>
            ) {
                synchronized(this@BuildEventRecorderGrpc) {
                    lifecycleEvents.put(request.getBuildEvent().getStreamId(), request)
                    val status: io.grpc.Status = computeLifecycleResponse(request)
                    if (status.isOk()) {
                        streamObserver.onNext(Empty.getDefaultInstance())
                        streamObserver.onCompleted()
                    } else {
                        streamObserver.onError(status.asException())
                    }
                }
            }

            public override fun publishBuildToolEventStream(
                stream: StreamObserver<PublishBuildToolEventStreamResponse?>
            ): StreamObserver<PublishBuildToolEventStreamRequest?> {
                publishBuildToolEventStreamAccepted = true
                return object : StreamObserver<PublishBuildToolEventStreamRequest?> {
                    override fun onNext(request: PublishBuildToolEventStreamRequest) {
                        synchronized(this@BuildEventRecorderGrpc) {
                            streamEvents.put(request.getOrderedBuildEvent().getStreamId(), request)
                            if (sendOutOfOrderAcknowledgments) {
                                stream.onNext(
                                    PublishBuildToolEventStreamResponse.newBuilder()
                                        .setStreamId(request.getOrderedBuildEvent().getStreamId())
                                        .setSequenceNumber(request.getOrderedBuildEvent().getSequenceNumber() + 1)
                                        .build()
                                )
                                return
                            }
                            val response: Pair<io.grpc.Status?, MutableCollection<PublishBuildToolEventStreamResponse?>?> =
                                computeStreamResponse(request)
                            val status: io.grpc.Status? = response.first
                            if (status == null || status.isOk()) {
                                successfulStreamEvents.put(request.getOrderedBuildEvent().getStreamId(), request)
                                for (messages in response.second) {
                                    stream.onNext(messages)
                                }
                                if (status != null && status.isOk()) {
                                    stream.onCompleted()
                                }
                            } else {
                                stream.onError(status.asException())
                            }
                        }
                    }

                    override fun onError(t: Throwable) {
                        eventStreamError = io.grpc.Status.fromThrowable(t)
                        t.printStackTrace()
                    }

                    override fun onCompleted() {}
                }
            }
        }

        protected override fun publishBuildToolEventStreamAccepted(): Boolean {
            return publishBuildToolEventStreamAccepted
        }
    }
}
