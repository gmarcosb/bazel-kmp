// Copyright 2025 The Bazel Authors. All rights reserved.
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

import build.bazel.remote.execution.v2.RequestMetadata

/**
 * Trivial implementation of [PublishBuildEventImplBase] that can insert sleeps at critical
 * junctures.
 */
class DelayingPublishBuildEventService : PublishBuildEventImplBase() {
    @javax.annotation.concurrent.GuardedBy("this")
    private var delayBeforeClosingStream: java.time.Duration = java.time.Duration.ZERO

    @javax.annotation.concurrent.GuardedBy("this")
    private var delayBeforeHalfClosingStream: java.time.Duration = java.time.Duration.ZERO

    @javax.annotation.concurrent.GuardedBy("this")
    private var errorMessage: String? = null

    @javax.annotation.concurrent.GuardedBy("this")
    private var errorCode: io.grpc.Status? = null

    private val requestsReceived: AtomicInteger = AtomicInteger(0)
    private var errorEarlyInStream = false

    /**
     * Synchronizing this method can lead to deadlocks -- it calls into [ ] which takes a locks on itself. Opposite order of locks
     * happens for [.publishBuildToolEventStream] called while holding the lock on [ ].
     */
    public override fun publishLifecycleEvent(
        request: PublishLifecycleEventRequest?, responseObserver: StreamObserver<Empty?>
    ) {
        val metadata: RequestMetadata = TracingMetadataUtils.fromCurrentContext()
        assertThat(metadata.getToolInvocationId()).isNotEmpty()
        assertThat(metadata.getCorrelatedInvocationsId()).isNotEmpty()
        assertThat(metadata.getActionId()).isEqualTo("publish_lifecycle_event")

        responseObserver.onNext(Empty.getDefaultInstance())
        responseObserver.onCompleted()
    }

    @kotlin.jvm.Synchronized
    public override fun publishBuildToolEventStream(
        responseObserver: StreamObserver<PublishBuildToolEventStreamResponse?>
    ): StreamObserver<PublishBuildToolEventStreamRequest?> {
        requestsReceived.incrementAndGet()
        val metadata: RequestMetadata = TracingMetadataUtils.fromCurrentContext()
        assertThat(metadata.getToolInvocationId()).isNotEmpty()
        assertThat(metadata.getCorrelatedInvocationsId()).isNotEmpty()
        assertThat(metadata.getActionId()).isEqualTo("publish_build_tool_event_stream")

        if (errorMessage != null) {
            return ErroringPublishBuildStreamObserver(
                responseObserver, errorMessage, errorCode, errorEarlyInStream
            )
        }
        val observer =
            DelayingPublishBuildStreamObserver(
                responseObserver, delayBeforeClosingStream, delayBeforeHalfClosingStream
            )
        observer.startAckingThread()
        return observer
    }

    @kotlin.jvm.Synchronized
    fun setErrorMessage(errorMessage: String?) {
        setErrorMessageAndCode(errorMessage, io.grpc.Status.DATA_LOSS)
    }

    @kotlin.jvm.Synchronized
    fun setErrorMessageAndCode(errorMessage: String?, code: io.grpc.Status?) {
        this.errorMessage = errorMessage
        this.errorCode = code
    }

    @kotlin.jvm.Synchronized
    fun setErrorEarlyInStream(errorEarlyInStream: Boolean) {
        this.errorEarlyInStream = errorEarlyInStream
    }

    @kotlin.jvm.Synchronized
    fun setDelayBeforeClosingStream(delay: java.time.Duration) {
        this.delayBeforeClosingStream = delay
    }

    @kotlin.jvm.Synchronized
    fun setDelayBeforeHalfClosingStream(delay: java.time.Duration) {
        this.delayBeforeHalfClosingStream = delay
    }

    val requestsReceivedCount: Int
        get() = requestsReceived.get()

    /**
     * A [StreamObserver] that simulates a server that terminates the stream with an error,
     * either immediately or when the client closes its end of the stream.
     */
    private class ErroringPublishBuildStreamObserver
        (
        responseObserver: StreamObserver<PublishBuildToolEventStreamResponse?>,
        errorMessage: String?,
        errorCode: io.grpc.Status,
        errorEarlyInStream: Boolean
    ) : StreamObserver<PublishBuildToolEventStreamRequest?> {
        private val responseObserver: StreamObserver<PublishBuildToolEventStreamResponse?>
        private val errorMessage: String?
        private val errorCode: io.grpc.Status
        private val errorEarlyInStream: Boolean

        init {
            this.responseObserver = responseObserver
            this.errorMessage = errorMessage
            this.errorCode = errorCode
            this.errorEarlyInStream = errorEarlyInStream
        }

        override fun onNext(value: PublishBuildToolEventStreamRequest) {
            if (errorEarlyInStream) {
                responseObserver.onError(
                    StatusRuntimeException(errorCode.withDescription(errorMessage))
                )
            }
            responseObserver.onNext(
                PublishBuildToolEventStreamResponse.newBuilder()
                    .setStreamId(value.getOrderedBuildEventOrBuilder().getStreamId())
                    .setSequenceNumber(value.getOrderedBuildEvent().getSequenceNumber())
                    .build()
            )
        }

        override fun onError(t: Throwable?) {}

        override fun onCompleted() {
            responseObserver.onError(StatusRuntimeException(errorCode.withDescription(errorMessage)))
        }
    }

    /**
     * Trivial, in-memory implementation of a PublishBuildToolEventStream handler that can have
     * pre-configured sleeps triggered at critical junctures.
     */
    private class DelayingPublishBuildStreamObserver
        (
        responseObserver: StreamObserver<PublishBuildToolEventStreamResponse?>,
        delayBeforeClosingStream: java.time.Duration,
        delayBeforeHalfClosingStream: java.time.Duration
    ) : StreamObserver<PublishBuildToolEventStreamRequest?> {
        private val responseObserver: StreamObserver<PublishBuildToolEventStreamResponse?>
        private val delayBeforeClosingStream: java.time.Duration
        private val delayBeforeHalfClosingStream: java.time.Duration

        @javax.annotation.concurrent.GuardedBy("this")
        private val unackedSequenceNumbers: SortedSet<Long?> = com.google.common.collect.Sets.newTreeSet<Long?>()

        private val ackQueue: BlockingQueue<Long?> = ArrayBlockingQueue<Long?>(10)

        @javax.annotation.concurrent.GuardedBy("this")
        private var ackingThread: java.lang.Thread? = null

        @javax.annotation.concurrent.GuardedBy("this")
        private var streamId: StreamId? = null

        @javax.annotation.concurrent.GuardedBy("this")
        private var finished = false

        /** Creates the acking thread, safely callable after the constructor finishes.  */
        @kotlin.jvm.Synchronized
        fun startAckingThread() {
            com.google.common.base.Preconditions.checkState(ackingThread == null, "startAckingThread() called twice")
            ackingThread = java.lang.Thread(AckingThread())
            ackingThread.start()
        }

        override fun onNext(req: PublishBuildToolEventStreamRequest) {
            val longsToPut: MutableList<Long?> = java.util.ArrayList<Long?>()
            synchronized(this) {
                if (!unackedSequenceNumbers.add(req.getOrderedBuildEvent().getSequenceNumber())) {
                    return  // dupe, ignore
                }
                streamId = com.google.common.base.MoreObjects.firstNonNull<T?>(
                    streamId,
                    req.getOrderedBuildEvent().getStreamId()
                )
                if (req.getOrderedBuildEvent().getEvent().getComponentStreamFinished().getType()
                    === FinishType.FINISH_TYPE_UNSPECIFIED
                ) {
                    // We did not get the final event. Ack the *previous* event, if there is a previous event.
                    if (unackedSequenceNumbers.size > 1) {
                        longsToPut.add(ackLowestSequenceNumber())
                    }
                } else {
                    com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly(delayBeforeHalfClosingStream)
                    // final event. ack everything remaining.
                    while (!unackedSequenceNumbers.isEmpty()) {
                        longsToPut.add(ackLowestSequenceNumber())
                    }
                    if (finished) {
                        longsToPut.add(SENTINEL_VALUE)
                    }
                }
            }
            for (seqNum in longsToPut) {
                com.google.common.util.concurrent.Uninterruptibles.putUninterruptibly<Long?>(ackQueue, seqNum)
            }
        }

        @javax.annotation.concurrent.GuardedBy("this")
        fun ackLowestSequenceNumber(): Long? {
            val firstUnacked: Long? = unackedSequenceNumbers.first()
            unackedSequenceNumbers.remove(firstUnacked)
            return firstUnacked
        }

        @kotlin.jvm.Synchronized
        override fun onError(t: Throwable?) {
            finished = true
            responseObserver.onError(t)
        }

        override fun onCompleted() {
            val putSentinel: Boolean
            synchronized(this) {
                finished = true
                putSentinel = unackedSequenceNumbers.isEmpty()
            }
            if (putSentinel) {
                com.google.common.util.concurrent.Uninterruptibles.putUninterruptibly<Long?>(ackQueue, SENTINEL_VALUE)
            }
        }

        init {
            this.responseObserver = responseObserver
            this.delayBeforeClosingStream = delayBeforeClosingStream
            this.delayBeforeHalfClosingStream = delayBeforeHalfClosingStream
        }

        private inner class AckingThread : java.lang.Runnable {
            override fun run() {
                while (true) {
                    val firstUnacked: Long =
                        com.google.common.util.concurrent.Uninterruptibles.takeUninterruptibly<Long>(ackQueue)
                    synchronized(this@DelayingPublishBuildStreamObserver) {
                        if (firstUnacked == SENTINEL_VALUE) {
                            com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly(
                                delayBeforeClosingStream
                            )
                            responseObserver.onCompleted()
                            return
                        }
                        responseObserver.onNext(
                            PublishBuildToolEventStreamResponse.newBuilder()
                                .setStreamId(streamId)
                                .setSequenceNumber(firstUnacked)
                                .build()
                        )
                    }
                }
            }
        }

        companion object {
            val SENTINEL_VALUE: Long = -1L
        }
    }
}
