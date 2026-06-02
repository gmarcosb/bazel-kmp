// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildeventservice.client

import com.google.devtools.build.v1.PublishBuildEventGrpc

/** Implementation of BuildEventServiceClient that uploads data using gRPC.  */
class BuildEventServiceGrpcClient : BuildEventServiceClient {
    private val channel: ManagedChannel

    private val besAsync: PublishBuildEventStub
    private val besBlocking: PublishBuildEventBlockingStub

    constructor(
        channel: ManagedChannel,
        callCredentials: CallCredentials?,
        interceptor: ClientInterceptor?
    ) {
        this.besAsync =
            Companion.configureStub<T>(PublishBuildEventGrpc.newStub(channel), callCredentials, interceptor)
        this.besBlocking =
            Companion.configureStub<T>(PublishBuildEventGrpc.newBlockingStub(channel), callCredentials, interceptor)
        this.channel = channel
    }

    @com.google.common.annotations.VisibleForTesting
    protected constructor(
        besAsync: PublishBuildEventStub,
        besBlocking: PublishBuildEventBlockingStub,
        channel: ManagedChannel
    ) {
        this.besAsync = besAsync
        this.besBlocking = besBlocking
        this.channel = channel
    }

    @Throws(
        com.google.devtools.build.lib.buildeventservice.client.BuildEventServiceClient.StreamException::class,
        java.lang.InterruptedException::class
    )
    override fun publish(commandContext: CommandContext, lifecycleEvent: LifecycleEvent) {
        val request: PublishLifecycleEventRequest? =
            BuildEventServiceProtoUtil.publishLifecycleEventRequest(commandContext, lifecycleEvent)
        throwIfInterrupted()
        try {
            besBlocking
                .withDeadlineAfter(RPC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .withInterceptors(
                    TracingMetadataUtils.attachMetadataInterceptor(
                        TracingMetadataUtils.buildMetadata(
                            commandContext.buildId,
                            commandContext.invocationId,
                            "publish_lifecycle_event",  /* actionMetadata= */
                            null
                        )
                    )
                )
                .publishLifecycleEvent(request)
        } catch (e: StatusRuntimeException) {
            com.google.common.base.Throwables.throwIfInstanceOf<java.lang.InterruptedException?>(
                com.google.common.base.Throwables.getRootCause(
                    e
                ), java.lang.InterruptedException::class.java
            )
            val status: io.grpc.Status = io.grpc.Status.fromThrowable(e)
            throw com.google.devtools.build.lib.buildeventservice.client.BuildEventServiceClient.StreamException(
                GrpcStreamStatus(status),
                e
            )
        }
    }

    private class BESGrpcStreamContext(
        besAsync: PublishBuildEventStub,
        commandContext: CommandContext,
        ackCallback: AckCallback
    ) : StreamContext {
        private val stream: StreamObserver<PublishBuildToolEventStreamRequest?>
        private val streamStatus: com.google.common.util.concurrent.SettableFuture<StreamStatus?>
        private val commandContext: CommandContext?

        init {
            this.commandContext = commandContext
            this.streamStatus = com.google.common.util.concurrent.SettableFuture.create<StreamStatus?>()
            this.stream =
                besAsync
                    .withInterceptors(
                        TracingMetadataUtils.attachMetadataInterceptor(
                            TracingMetadataUtils.buildMetadata(
                                commandContext.buildId,
                                commandContext.invocationId,
                                "publish_build_tool_event_stream",  /* actionMetadata= */
                                null
                            )
                        )
                    )
                    .publishBuildToolEventStream(
                        object : StreamObserver<PublishBuildToolEventStreamResponse?>() {
                            override fun onNext(response: PublishBuildToolEventStreamResponse) {
                                ackCallback.apply(response.getSequenceNumber())
                            }

                            override fun onError(t: Throwable) {
                                var status: io.grpc.Status = io.grpc.Status.fromThrowable(t)
                                if (status.getCode() == io.grpc.Status.CANCELLED.getCode() && status.getCause() != null && (io.grpc.Status.fromThrowable(
                                        status.getCause()
                                    ).getCode()
                                            != io.grpc.Status.UNKNOWN.getCode())
                                ) {
                                    // gRPC likes to wrap Status(Runtime)Exceptions in StatusRuntimeExceptions.
                                    // If the status is cancelled and has a Status(Runtime)Exception as a cause,
                                    // it means the error was generated client side.
                                    status = io.grpc.Status.fromThrowable(status.getCause())
                                }
                                streamStatus.set(GrpcStreamStatus(status))
                            }

                            override fun onCompleted() {
                                streamStatus.set(GrpcStreamStatus.Companion.OK)
                            }
                        })
        }

        @Throws(java.lang.InterruptedException::class)
        override fun sendOverStream(streamEvent: StreamEvent) {
            val request: PublishBuildToolEventStreamRequest? =
                BuildEventServiceProtoUtil.publishBuildToolEventStreamRequest(
                    commandContext, streamEvent
                )
            throwIfInterrupted()
            try {
                stream.onNext(request)
            } catch (e: StatusRuntimeException) {
                com.google.common.base.Throwables.throwIfInstanceOf<java.lang.InterruptedException?>(
                    com.google.common.base.Throwables.getRootCause(
                        e
                    ), java.lang.InterruptedException::class.java
                )
                streamStatus.set(GrpcStreamStatus(io.grpc.Status.fromThrowable(e)))
            }
        }

        override fun halfCloseStream() {
            stream.onCompleted()
        }

        override fun abortStream(reason: AbortReason, description: String?) {
            var status: io.grpc.Status =
                when (reason) {
                    AbortReason.CANCELLED -> io.grpc.Status.CANCELLED
                    AbortReason.FAILED_PRECONDITION -> io.grpc.Status.FAILED_PRECONDITION
                }
            if (description != null) {
                status = status.withDescription(description)
            }
            stream.onError(status.asException())
        }

        val status: com.google.common.util.concurrent.ListenableFuture<StreamStatus?>
            get() = streamStatus
    }

    @Throws(java.lang.InterruptedException::class)
    override fun openStream(commandContext: CommandContext, ackCallback: AckCallback): StreamContext {
        try {
            return BESGrpcStreamContext(besAsync, commandContext, ackCallback)
        } catch (e: StatusRuntimeException) {
            com.google.common.base.Throwables.throwIfInstanceOf<java.lang.InterruptedException?>(
                com.google.common.base.Throwables.getRootCause(
                    e
                ), java.lang.InterruptedException::class.java
            )
            val status: com.google.common.util.concurrent.ListenableFuture<StreamStatus?> =
                com.google.common.util.concurrent.Futures.immediateFuture<StreamStatus?>(
                    GrpcStreamStatus(
                        io.grpc.Status.fromThrowable(
                            e
                        )
                    )
                )
            return object : StreamContext {
                val status: com.google.common.util.concurrent.ListenableFuture<StreamStatus?>
                    get() = status

                override fun sendOverStream(streamEvent: StreamEvent?) {}

                override fun halfCloseStream() {}

                override fun abortStream(reason: AbortReason?, description: String?) {}
            }
        }
    }

    private class GrpcStreamStatus(status: io.grpc.Status) : StreamStatus {
        private val status: io.grpc.Status

        init {
            this.status = status
        }

        val isOk: Boolean
            get() = status.isOk()

        val isRetriable: Boolean
            get() = !status.isOk() && !NON_RETRYABLE_STATUS_CODES.contains(status.getCode()) && status.getCode() != io.grpc.Status.Code.FAILED_PRECONDITION

        val isFailedPrecondition: Boolean
            get() = status.getCode() == io.grpc.Status.Code.FAILED_PRECONDITION

        val errorMessage: String
            get() {
                val sb: java.lang.StringBuilder = java.lang.StringBuilder()
                sb.append(status.getCode().name())
                if (!com.google.common.base.Strings.isNullOrEmpty(status.getDescription())) {
                    sb.append(": ").append(status.getDescription())
                }
                return sb.toString()
            }

        companion object {
            private val OK = GrpcStreamStatus(io.grpc.Status.OK)
        }
    }

    override fun shutdown() {
        channel.shutdown()
    }

    companion object {
        private val NON_RETRYABLE_STATUS_CODES: com.google.common.collect.ImmutableSet<io.grpc.Status.Code?> =
            com.google.common.collect.ImmutableSet.of<io.grpc.Status.Code?>(
                io.grpc.Status.Code.INVALID_ARGUMENT,
                io.grpc.Status.Code.PERMISSION_DENIED
            )

        /** Max wait time for a single non-streaming RPC to finish  */
        private val RPC_TIMEOUT: java.time.Duration = java.time.Duration.ofSeconds(15)

        private fun <T : AbstractStub<T?>?> configureStub(
            stub: T?, callCredentials: CallCredentials?, interceptor: ClientInterceptor?
        ): T? {
            var stub = stub
            stub = if (callCredentials != null) stub.withCallCredentials(callCredentials) else stub
            stub = if (interceptor != null) stub.withInterceptors(interceptor) else stub
            return stub
        }

        @Throws(java.lang.InterruptedException::class)
        private fun throwIfInterrupted() {
            if (java.lang.Thread.interrupted()) {
                throw java.lang.InterruptedException()
            }
        }
    }
}
