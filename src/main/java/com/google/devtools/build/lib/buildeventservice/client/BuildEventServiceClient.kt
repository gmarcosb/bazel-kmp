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

import com.google.devtools.build.lib.buildeventservice.client.BuildEventServiceClient
import com.google.devtools.build.lib.buildeventservice.client.BuildEventServiceClient.CommandContext
import java.time.Instant

/** Interface used to abstract the Stubby and gRPC client implementations.  */
@com.google.devtools.build.lib.skybridge.SkybridgeInterface
interface BuildEventServiceClient {
    /** Context for a build command.  */
    @kotlin.jvm.JvmRecord
    data class CommandContext(
        val buildId: String?,
        val invocationId: String?,
        val attemptNumber: Int,
        val keywords: MutableSet<String?>?,
        val projectId: String?,
        val checkPrecedingLifecycleEvents: Boolean
    ) {
        /** Builder for [CommandContext].  */
        class Builder private constructor() {
            private var buildId: String? = null
            private var invocationId: String? = null
            private var attemptNumber = 0
            private var keywords: MutableSet<String?>? = null
            private var projectId: String? = null
            private var checkPrecedingLifecycleEvents = false

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun setBuildId(buildId: String?): Builder {
                this.buildId = buildId
                return this
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun setInvocationId(invocationId: String?): Builder {
                this.invocationId = invocationId
                return this
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun setAttemptNumber(attemptNumber: Int): Builder {
                this.attemptNumber = attemptNumber
                return this
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun setKeywords(keywords: MutableSet<String?>?): Builder {
                this.keywords = keywords
                return this
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun setProjectId(projectId: String?): Builder {
                this.projectId = projectId
                return this
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun setCheckPrecedingLifecycleEvents(checkPrecedingLifecycleEvents: Boolean): Builder {
                this.checkPrecedingLifecycleEvents = checkPrecedingLifecycleEvents
                return this
            }

            fun build(): CommandContext {
                return CommandContext(
                    buildId,
                    invocationId,
                    attemptNumber,
                    keywords,
                    projectId,
                    checkPrecedingLifecycleEvents
                )
            }
        }

        init {
            java.util.Objects.requireNonNull<String?>(buildId, "buildId")
            java.util.Objects.requireNonNull<String?>(invocationId, "invocationId")
            java.util.Objects.requireNonNull<MutableSet<String?>?>(keywords, "keywords")
            require(attemptNumber >= 1) { "attemptNumber must be >= 1" }
        }

        companion object {
            @kotlin.jvm.JvmStatic
            fun builder(): Builder {
                return com.google.devtools.build.lib.buildeventservice.client.BuildEventServiceClient.CommandContext.Builder()
            }
        }
    }

    /** The status of an invocation.  */
    enum class InvocationStatus {
        /** No information is available about the invocation status.  */
        UNKNOWN,

        /** The invocation succeeded.  */
        SUCCEEDED,

        /** The invocation failed.  */
        FAILED,
    }

    /** A lifecycle event.  */
    interface LifecycleEvent {
        /** The time at which the event occurred.  */
        fun eventTime(): Instant?

        /** The lifecycle event signalling that the build was enqueued.  */
        class BuildEnqueued(eventTime: Instant?) : LifecycleEvent {
            val eventTime: Instant?

            init {
                this.eventTime = eventTime
            }
        }

        /** The lifecycle event signalling that the invocation was started.  */
        class InvocationStarted(eventTime: Instant?) : LifecycleEvent {
            val eventTime: Instant?

            init {
                this.eventTime = eventTime
            }
        }

        /**
         * The lifecycle event signalling that the invocation was finished.
         * 
         * @param status the invocation status
         */
        class InvocationFinished(eventTime: Instant?, status: InvocationStatus?) : LifecycleEvent {
            val eventTime: Instant?
            val status: InvocationStatus?

            init {
                this.eventTime = eventTime
                this.status = status
            }
        }

        /**
         * The lifecycle event signalling that the build was finished.
         * 
         * @param status the invocation status
         */
        class BuildFinished(eventTime: Instant?, status: InvocationStatus?) : LifecycleEvent {
            val eventTime: Instant?
            val status: InvocationStatus?

            init {
                this.eventTime = eventTime
                this.status = status
            }
        }
    }

    /** An event sent over a [StreamContext].  */
    interface StreamEvent {
        /** The time at which the event occurred.  */
        fun eventTime(): Instant?

        /** The sequence number of the event.  */
        fun sequenceNumber(): Long

        /**
         * An event containing a [BuildEventStreamProtos.BuildEvent].
         * 
         * @param payload the [BuildEventStreamProtos.BuildEvent] in wire format
         */
        class BazelEvent(eventTime: Instant?, sequenceNumber: Long, payload: ByteArray?) : StreamEvent {
            val eventTime: Instant?
            val sequenceNumber: Long
            val payload: ByteArray?

            init {
                this.eventTime = eventTime
                this.sequenceNumber = sequenceNumber
                this.payload = payload
            }
        }

        /** An event signalling the end of the stream.  */
        class StreamFinished(eventTime: Instant?, sequenceNumber: Long) : StreamEvent {
            val eventTime: Instant?
            val sequenceNumber: Long

            init {
                this.eventTime = eventTime
                this.sequenceNumber = sequenceNumber
            }
        }
    }

    /** Callback for ACKed build events.  */
    fun interface AckCallback {
        /**
         * Called whenever an ACK from the BES server is received. ACKs are expected to be received in
         * sequence. Implementations must be thread-safe.
         */
        fun apply(sequenceNumber: Long)
    }

    /** The status of a stream.  */
    interface StreamStatus {
        /** Returns whether the status is successful.  */
        @kotlin.jvm.JvmField
        val isOk: Boolean

        /** Returns whether the status is retriable.  */
        val isRetriable: Boolean

        /** Returns whether the status indicates a failed precondition.  */
        val isFailedPrecondition: Boolean

        /** Returns an error message for this status.  */
        @kotlin.jvm.JvmField
        val errorMessage: String?
    }

    /** An exception with an underlying [StreamStatus].  */
    class StreamException(
        /** Returns the underlying [StreamStatus].  */
        val status: StreamStatus, cause: Throwable?
    ) : java.lang.Exception(status.errorMessage, cause)

    /** The reason why a stream is being aborted.  */
    enum class AbortReason {
        /** The operation was cancelled.  */
        CANCELLED,

        /** A precondition was failed.  */
        FAILED_PRECONDITION,
    }

    /** A handle to a bidirectional stream.  */
    interface StreamContext {
        /**
         * The completed status of the stream. The future will never fail, but in case of error will
         * contain a corresponding status.
         */
        @kotlin.jvm.JvmField
        val status: java.util.concurrent.Future<StreamStatus?>?

        /**
         * Sends a [StreamEvent] over the currently open stream. In case of error, this method
         * will fail silently and report the error via the [Future] returned by [ ][.getStatus].
         * 
         * 
         * This method may block due to flow control.
         */
        @Throws(java.lang.InterruptedException::class)
        fun sendOverStream(streamEvent: StreamEvent?)

        /**
         * Half closes the currently opened stream. This method does not block. Callers should block on
         * the future returned by [.getStatus] in order to make sure that all `ackCallback` calls have been received.
         */
        fun halfCloseStream()

        /**
         * Closes the currently opened stream with an error. This method does not block. Callers should
         * block on the future returned by [.getStatus] in order to make sure that all
         * ackCallback calls have been received. This method is NOOP if the stream was already finished.
         */
        fun abortStream(reason: AbortReason?, description: String?)
    }

    /** Makes a blocking RPC call that publishes a [LifecycleEvent].  */
    @Throws(
        com.google.devtools.build.lib.buildeventservice.client.BuildEventServiceClient.StreamException::class,
        java.lang.InterruptedException::class
    )
    fun publish(commandContext: CommandContext?, lifecycleEvent: LifecycleEvent?)

    /**
     * Starts a new stream with the given [CommandContext] and [AckCallback]. Callers must
     * wait on the returned future contained in the [StreamContext] in order to guarantee that
     * all callback calls have been received.
     */
    @Throws(java.lang.InterruptedException::class)
    fun openStream(commandContext: CommandContext?, callback: AckCallback?): StreamContext?

    /**
     * Called once to dispose resources that this client might be holding (such as thread pools). This
     * should be the last method called on this object.
     */
    fun shutdown()
}
