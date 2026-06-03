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

import com.google.devtools.build.lib.util.Pair

abstract class AbstractBuildEventRecorder : ExternalResource() {
    /**
     * When processing a build event determines whether to return [.streamEventResponseStatus].
     */
    private var streamEventPredicate: java.util.function.Predicate<PublishBuildToolEventStreamRequest?> =
        java.util.function.Predicate { o: PublishBuildToolEventStreamRequest? -> false }

    private var streamEventResponseStatus: io.grpc.Status? = null

    /**
     * When processing a lifecycle event determines whether to return [ ][.lifecycleEventResponseStatus].
     */
    private var lifecycleEventPredicate: java.util.function.Predicate<PublishLifecycleEventRequest?> =
        java.util.function.Predicate { o: PublishLifecycleEventRequest? -> false }

    private var lifecycleEventResponseStatus: io.grpc.Status? = null

    private var sendResponsesOnRequestPredicate: java.util.function.Predicate<PublishBuildToolEventStreamRequest?> =
        java.util.function.Predicate { o: PublishBuildToolEventStreamRequest? -> true }
    private var responseBuffer: ConcurrentLinkedQueue<PublishBuildToolEventStreamResponse?> =
        ConcurrentLinkedQueue<PublishBuildToolEventStreamResponse?>()

    protected val lifecycleEvents: com.google.common.collect.ListMultimap<StreamId?, PublishLifecycleEventRequest?> =
        com.google.common.collect.LinkedListMultimap.create<StreamId?, PublishLifecycleEventRequest?>()
    protected val streamEvents: com.google.common.collect.ListMultimap<StreamId?, PublishBuildToolEventStreamRequest?> =
        com.google.common.collect.LinkedListMultimap.create<StreamId?, PublishBuildToolEventStreamRequest?>()
    protected val successfulStreamEvents: com.google.common.collect.ListMultimap<StreamId?, PublishBuildToolEventStreamRequest?> =
        com.google.common.collect.LinkedListMultimap.create<StreamId?, PublishBuildToolEventStreamRequest?>()

    /** Tell the server to sends ACKs out of order or for the wrong events  */
    @kotlin.concurrent.Volatile
    protected var sendOutOfOrderAcknowledgments: Boolean = false

    protected var eventStreamError: io.grpc.Status? = null

    /** Starts a server using the specified port. *  */
    protected abstract fun startRpcServer(port: Int)

    /** Starts a server using an arbitrary port *  */
    fun startRpcServer() {
        val port = pickNewPort()
        logger.atInfo().log("Starting BES recorder server on port: %d", port)
        startRpcServer(port)
        logger.atInfo().log("Started BES recorder server on port: %d", port)
    }

    /** Stops a running server. *  */
    abstract fun stopRpcServer()

    /** Returns the port the port the server is running, -1 otherwise. *  */
    protected abstract val port: Int

    /** Returns whether or not a `publishBuildToolEventStream` was observed on this server.  */
    abstract fun publishBuildToolEventStreamAccepted(): Boolean

    @kotlin.jvm.Synchronized
    fun getLifecycleEvents(streamId: StreamId?): com.google.common.collect.ImmutableList<PublishLifecycleEventRequest?> {
        return com.google.common.collect.ImmutableList.copyOf<PublishLifecycleEventRequest?>(
            lifecycleEvents.get(
                streamId
            )
        )
    }

    @kotlin.jvm.Synchronized
    fun getStreamEvents(
        streamId: StreamId?
    ): com.google.common.collect.ImmutableList<PublishBuildToolEventStreamRequest?> {
        return com.google.common.collect.ImmutableList.copyOf<PublishBuildToolEventStreamRequest?>(
            streamEvents.get(
                streamId
            )
        )
    }

    @kotlin.jvm.Synchronized
    fun getSuccessfulStreamEvents(
        streamId: StreamId?
    ): com.google.common.collect.ImmutableList<PublishBuildToolEventStreamRequest?> {
        return com.google.common.collect.ImmutableList.copyOf<PublishBuildToolEventStreamRequest?>(
            successfulStreamEvents.get(streamId)
        )
    }

    fun setStreamEventPredicateAndResponseStatus(
        predicate: java.util.function.Predicate<PublishBuildToolEventStreamRequest?>, responseStatus: io.grpc.Status?
    ) {
        this.streamEventPredicate = predicate
        this.streamEventResponseStatus = responseStatus
    }

    fun setLifecycleEventPredicateAndResponseStatus(
        predicate: java.util.function.Predicate<PublishLifecycleEventRequest?>, responseStatus: io.grpc.Status?
    ) {
        this.lifecycleEventPredicate = predicate
        this.lifecycleEventResponseStatus = responseStatus
    }

    fun setSendResponsesOnRequestPredicate(
        sendResponsesOnRequestPredicate: java.util.function.Predicate<PublishBuildToolEventStreamRequest?>
    ) {
        this.sendResponsesOnRequestPredicate = sendResponsesOnRequestPredicate
    }

    fun sendOutOfOrderAcknowledgments() {
        sendOutOfOrderAcknowledgments = true
    }

    @kotlin.jvm.Synchronized
    fun eventStreamError(): io.grpc.Status? {
        return eventStreamError
    }

    /** Picks a free port to use for a test using platform-specific logic.  */
    protected abstract fun pickNewPort(): Int

    fun computeLifecycleResponse(request: PublishLifecycleEventRequest): io.grpc.Status? {
        try {
            if (lifecycleEventPredicate.test(request)) {
                return lifecycleEventResponseStatus
            } else {
                return Companion.statusFor(request)
            }
        } catch (e: java.lang.Exception) {
            return io.grpc.Status.INTERNAL.withDescription(e.message)
        }
    }

    fun computeStreamResponse(
        request: PublishBuildToolEventStreamRequest
    ): Pair<io.grpc.Status?, MutableCollection<PublishBuildToolEventStreamResponse?>?> {
        if (streamEventPredicate.test(request)) {
            return Pair.of(streamEventResponseStatus, mutableListOf<T?>())
        } else if (sendResponsesOnRequestPredicate.test(request)) {
            val response: com.google.common.collect.ImmutableList<PublishBuildToolEventStreamResponse?> =
                com.google.common.collect.ImmutableList.builder<PublishBuildToolEventStreamResponse?>()
                    .addAll(responseBuffer)
                    .add(responseFor(request))
                    .build()
            responseBuffer = ConcurrentLinkedQueue<PublishBuildToolEventStreamResponse?>()
            return Pair.of(Companion.statusFor(request), response)
        } else {
            responseBuffer.add(responseFor(request))
            return Pair.of(Companion.statusFor(request), mutableListOf<T?>())
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private fun responseFor(
            request: PublishBuildToolEventStreamRequest
        ): PublishBuildToolEventStreamResponse {
            return PublishBuildToolEventStreamResponse.newBuilder()
                .setStreamId(request.getOrderedBuildEvent().getStreamId())
                .setSequenceNumber(request.getOrderedBuildEvent().getSequenceNumber())
                .build()
        }

        private fun statusFor(request: PublishLifecycleEventRequest): io.grpc.Status? {
            when (request.getBuildEvent().getEvent().getEventCase()) {
                INVOCATION_ATTEMPT_STARTED, BUILD_ENQUEUED -> if (request.getBuildEvent().getSequenceNumber() === 1) {
                    return io.grpc.Status.OK
                }

                INVOCATION_ATTEMPT_FINISHED, BUILD_FINISHED -> if (request.getBuildEvent().getSequenceNumber() === 2) {
                    return io.grpc.Status.OK
                }

                else -> {}
            }
            return io.grpc.Status.UNKNOWN
        }

        private fun statusFor(request: PublishBuildToolEventStreamRequest): io.grpc.Status? {
            if (request.getOrderedBuildEvent().getEvent().getEventCase()
                === EventCase.COMPONENT_STREAM_FINISHED
            ) {
                return io.grpc.Status.OK
            }
            return null
        }
    }
}
