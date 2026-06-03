// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.buildeventservice.client.BuildEventServiceClient.CommandContext

/** Tests [BuildEventServiceGrpcClient].  */
@RunWith(JUnit4::class)
class BuildEventServiceGrpcClientTest {
    private class TestServer(server: io.grpc.Server, channel: ManagedChannel) : java.lang.AutoCloseable {
        private val server: io.grpc.Server
        private val channel: ManagedChannel

        init {
            this.server = server
            this.channel = channel
        }

        fun getChannel(): ManagedChannel {
            return channel
        }

        override fun close() {
            channel.shutdown()
            server.shutdown()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun besHeaders() {
        val seenHeaders: java.util.ArrayList<io.grpc.Metadata> = java.util.ArrayList<io.grpc.Metadata>()
        startTestServer(
            ServerInterceptors.intercept(
                NOOP_SERVER,
                object : ServerInterceptor {
                    override fun <ReqT, RespT> interceptCall(
                        call: ServerCall<ReqT?, RespT?>?,
                        headers: io.grpc.Metadata?,
                        next: ServerCallHandler<ReqT?, RespT?>
                    ): ServerCall.Listener<ReqT?>? {
                        synchronized(seenHeaders) {
                            seenHeaders.add(headers)
                        }
                        return next.startCall(call, headers)
                    }
                })
        ).use { server ->
            val extraHeaders: io.grpc.Metadata = io.grpc.Metadata()
            extraHeaders.put<String?>(
                io.grpc.Metadata.Key.of<String?>(
                    "metadata-foo",
                    io.grpc.Metadata.ASCII_STRING_MARSHALLER
                ), "bar"
            )
            val interceptor: ClientInterceptor = MetadataUtils.newAttachHeadersInterceptor(extraHeaders)
            val grpcClient: BuildEventServiceGrpcClient =
                BuildEventServiceGrpcClient(server.getChannel(), null, interceptor)
            assertThat(grpcClient.openStream(COMMAND_CONTEXT, { ack -> }).status.get().isOk)
                .isTrue()
            Truth.assertThat(seenHeaders).hasSize(1)
            val headers: io.grpc.Metadata = seenHeaders.get(0)
            Truth.assertThat(
                headers.get<String?>(
                    io.grpc.Metadata.Key.of<String?>(
                        "metadata-foo",
                        io.grpc.Metadata.ASCII_STRING_MARSHALLER
                    )
                )
            )
                .isEqualTo("bar")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun immediateSuccess() {
        startTestServer(NOOP_SERVER.bindService()).use { server ->
            assertThat(
                BuildEventServiceGrpcClient(server.getChannel(), null, null)
                    .openStream(COMMAND_CONTEXT, { ack -> }).status
                    .get().isOk
            )
                .isTrue()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun immediateFailure() {
        startTestServer(
            object : PublishBuildEventImplBase() {
                public override fun publishBuildToolEventStream(
                    responseObserver: StreamObserver<PublishBuildToolEventStreamResponse?>
                ): StreamObserver<PublishBuildToolEventStreamRequest?> {
                    responseObserver.onError(StatusException(io.grpc.Status.INTERNAL))
                    return NULL_OBSERVER
                }
            }.bindService()
        ).use { server ->
            com.google.common.truth.Subject.contains("INTERNAL")
        }
    }

    companion object {
        private val COMMAND_CONTEXT: CommandContext? = CommandContext.builder()
            .setBuildId(UUID.randomUUID().toString())
            .setInvocationId(UUID.randomUUID().toString())
            .setAttemptNumber(1)
            .setKeywords(com.google.common.collect.ImmutableSet.of<E?>())
            .setProjectId(null)
            .setCheckPrecedingLifecycleEvents(false)
            .build()

        private val NOOP_SERVER: PublishBuildEventGrpc.PublishBuildEventImplBase =
            object : PublishBuildEventImplBase() {
                public override fun publishBuildToolEventStream(
                    responseObserver: StreamObserver<PublishBuildToolEventStreamResponse?>
                ): StreamObserver<PublishBuildToolEventStreamRequest?> {
                    responseObserver.onCompleted()
                    return NULL_OBSERVER
                }
            }

        private val NULL_OBSERVER: StreamObserver<PublishBuildToolEventStreamRequest?> =
            object : StreamObserver<PublishBuildToolEventStreamRequest?> {
                override fun onNext(value: PublishBuildToolEventStreamRequest?) {}

                override fun onError(t: Throwable?) {}

                override fun onCompleted() {}
            }

        /** Test helper that sets up a in-process test server.  */
        @Throws(java.lang.Exception::class)
        private fun startTestServer(service: ServerServiceDefinition?): TestServer {
            val uniqueName = UUID.randomUUID().toString()
            val server: io.grpc.Server =
                InProcessServerBuilder.forName(uniqueName).directExecutor().addService(service).build()
            server.start()
            return com.google.devtools.build.lib.buildeventservice.BuildEventServiceGrpcClientTest.TestServer(
                server, InProcessChannelBuilder.forName(uniqueName).directExecutor().build()
            )
        }
    }
}
