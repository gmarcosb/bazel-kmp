// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildeventservice

/**
 * Uploader of Build Events to the Build Event Service (BES).
 * 
 * 
 * The purpose is of this class is to manage the interaction between the BES client and the BES
 * server. It implements an event loop pattern based on the commands defined by [Command].
 */
// TODO(lpino): This class should be package-private but there are unit tests that are in the
//  different packages and rely on this.
@com.google.common.annotations.VisibleForTesting
class BuildEventServiceUploader private constructor(
    besClient: BuildEventServiceClient,
    localFileUploader: BuildEventArtifactUploader,
    buildEventProtocolOptions: BuildEventProtocolOptions,
    publishLifecycleEvents: Boolean,
    sleeper: com.google.devtools.build.lib.util.Sleeper,
    clock: com.google.devtools.build.lib.clock.Clock,
    namer: ArtifactGroupNamer?,
    eventBus: com.google.common.eventbus.EventBus,
    commandContext: CommandContext?,
    commandStartTime: Instant?
) : java.lang.Runnable {
    /** Commands to drive the event loop.  */
    private interface Command {
        /** Tells the event loop to open a new BES stream.  */
        class OpenStream : Command

        /** Tells the event loop that the streaming RPC completed.  */
        class StreamComplete(status: StreamStatus) : Command {
            val status: StreamStatus

            init {
                this.status = status
            }
        }

        /** Tells the event loop that an ACK was received.  */
        @kotlin.jvm.JvmRecord
        data class AckReceived(val sequenceNumber: Long) : Command

        /** Tells the event loop to send a build event.  */
        class SendRegularBuildEvent(
            val sequenceNumber: Long,
            creationTime: Instant?,
            event: BuildEvent?,
            localFileUploadProgress: com.google.common.util.concurrent.ListenableFuture<com.google.devtools.build.lib.buildeventstream.PathConverter>
        ) : Command {
            val creationTime: Instant?
            val event: BuildEvent?
            val localFileUploadProgress: com.google.common.util.concurrent.ListenableFuture<com.google.devtools.build.lib.buildeventstream.PathConverter>

            init {
                this.creationTime = creationTime
                this.event = event
                this.localFileUploadProgress = localFileUploadProgress
            }
        }

        /** Tells the event loop that this is the last event of the stream.  */
        class SendLastBuildEvent(val sequenceNumber: Long, creationTime: Instant?) : Command {
            val creationTime: Instant?

            init {
                this.creationTime = creationTime
            }
        }

        /** Tells the event loop to retransmit a serialized build event.  */
        class SendSerializedBuildEvent(request: StreamEvent?) : Command {
            val request: StreamEvent?

            init {
                this.request = request
            }
        }
    }

    private val besClient: BuildEventServiceClient
    private val buildEventUploader: BuildEventArtifactUploader
    private val buildEventProtocolOptions: BuildEventProtocolOptions
    private val commandContext: CommandContext?
    private val publishLifecycleEvents: Boolean
    private val sleeper: com.google.devtools.build.lib.util.Sleeper
    private val clock: com.google.devtools.build.lib.clock.Clock
    private val namer: ArtifactGroupNamer?
    private val eventBus: com.google.common.eventbus.EventBus

    // `commandStartTime` is an instant in time determined by the build tool's native launcher and
    // matches `BuildStartingEvent.getRequest().getStartTime()`.
    private val commandStartTime: Instant?

    // `eventStreamStartTime` is an instant *after* `commandStartTime` indicating when the
    // BuildEventServiceUploader was initialized to begin reporting build events. This instant should
    // be *before* the event_time for any BuildEvents uploaded after they are received via
    // `#enqueueEvent(BuildEvent)`.
    private val eventStreamStartTime: Instant?
    private var startedClose = false

    private val timeoutExecutor: ScheduledExecutorService =
        com.google.common.util.concurrent.MoreExecutors.listeningDecorator(
            Executors.newSingleThreadScheduledExecutor(
                com.google.common.util.concurrent.ThreadFactoryBuilder().setNameFormat("bes-uploader-timeout-%d")
                    .build()
            )
        )

    /**
     * The command queue contains two types of commands:
     * 
     * 
     *  * Commands containing build events, sorted by sequence number, to be sent to the server.
     *  * Commands that are used by [.publishBuildEvents] to change state.
     */
    private val commandQueue: BlockingDeque<Command?> = LinkedBlockingDeque<Command?>()

    /**
     * Computes sequence numbers for build events. As per the BES protocol, sequence numbers must be
     * consecutive monotonically increasing natural numbers.
     */
    private val nextSeqNum: AtomicLong = AtomicLong(1)

    private val lock = Any()

    @javax.annotation.concurrent.GuardedBy("lock")
    private var invocationStatus: InvocationStatus = InvocationStatus.UNKNOWN

    private val closeFuture: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
        com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
    private val halfCloseFuture: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
        com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()

    /**
     * The thread that calls the lifecycle RPCs and does the build event upload. It's started lazily
     * on the first call to [.enqueueEvent] or [.close] (which ever comes
     * first).
     */
    @javax.annotation.concurrent.GuardedBy("lock")
    private var uploadThread: java.lang.Thread? = null

    @javax.annotation.concurrent.GuardedBy("lock")
    private var interruptCausedByCancel = false

    private var streamContext: StreamContext? = null

    init {
        this.besClient = besClient
        this.buildEventUploader = localFileUploader
        this.buildEventProtocolOptions = buildEventProtocolOptions
        this.publishLifecycleEvents = publishLifecycleEvents
        this.sleeper = sleeper
        this.clock = clock
        this.namer = namer
        this.eventBus = eventBus
        this.commandContext = commandContext
        this.commandStartTime = commandStartTime
        this.eventStreamStartTime = clock.now()
        // Ensure the half-close future is closed once the upload is complete. This is usually a no-op,
        // but makes sure we half-close in case of error / interrupt.
        closeFuture.addListener(
            java.lang.Runnable { halfCloseFuture.setFuture(closeFuture) },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
    }

    fun getBuildEventUploader(): BuildEventArtifactUploader {
        return buildEventUploader
    }

    /** Enqueues an event for uploading to a BES backend.  */
    fun enqueueEvent(event: BuildEvent) {
        // This needs to happen outside a synchronized block as it may trigger
        // stdout/stderr and lead to a deadlock. See b/109725432
        val localFileUploadFuture: com.google.common.util.concurrent.ListenableFuture<com.google.devtools.build.lib.buildeventstream.PathConverter> =
            buildEventUploader.uploadReferencedLocalFiles(event.referencedLocalFiles())

        // The generation of the sequence number and the addition to the {@link #commandQueue} should be
        // atomic since BES expects the events in that exact order.
        // More details can be found in b/131393380.
        // TODO(bazel-team): Consider relaxing this invariant by having a more relaxed order.
        synchronized(lock) {
            if (startedClose) {
                return
            }
            // BuildCompletingEvent marks the end of the build in the BEP event stream.
            if (event is BuildCompletingEvent) {
                val exitCode: ExitCode? = event.getExitCode()
                if (exitCode != null && exitCode.getNumericExitCode() == 0) {
                    invocationStatus = InvocationStatus.SUCCEEDED
                } else {
                    invocationStatus = InvocationStatus.FAILED
                }
            } else if (event is AbortedEvent && event.getEventId().hasBuildFinished()) {
                // An AbortedEvent with a build finished ID means we are crashing.
                invocationStatus = InvocationStatus.FAILED
            }
            ensureUploadThreadStarted()

            // TODO(b/131393380): {@link #nextSeqNum} doesn't need to be an AtomicInteger if it's
            //  always used under lock. It would be cleaner and more performant to update the sequence
            //  number when we take the item off the queue.
            commandQueue.addLast(
                SendRegularBuildEvent(
                    nextSeqNum.getAndIncrement(), clock.now(), event, localFileUploadFuture
                )
            )
        }
    }

    /**
     * Gracefully stops the BES upload. All events enqueued before the call to close will be uploaded
     * and events enqueued after the call will be discarded.
     * 
     * 
     * The returned future completes when the upload completes. It's guaranteed to never fail.
     */
    fun close(): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        ensureUploadThreadStarted()

        // The generation of the sequence number and the addition to the {@link #commandQueue} should be
        // atomic since BES expects the events in that exact order.
        // More details can be found in b/131393380.
        // TODO(bazel-team): Consider relaxing this invariant by having a more relaxed order.
        synchronized(lock) {
            if (startedClose) {
                return closeFuture
            }
            startedClose = true
            // Enqueue the last event which will terminate the upload.
            // TODO(b/131393380): {@link #nextSeqNum} doesn't need to be an AtomicInteger if it's
            //  always used under lock. It would be cleaner and more performant to update the sequence
            //  number when we take the item off the queue.
            commandQueue.addLast(
                SendLastBuildEvent(nextSeqNum.getAndIncrement(), clock.now())
            )
        }

        val finalCloseFuture: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> = closeFuture
        closeFuture.addListener(
            java.lang.Runnable {
                // Make sure to cancel any pending uploads if the closing is cancelled.
                if (finalCloseFuture.isCancelled()) {
                    closeOnCancel()
                }
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )

        return closeFuture
    }

    private fun closeOnCancel() {
        synchronized(lock) {
            interruptCausedByCancel = true
            closeNow()
        }
    }

    /** Stops the upload immediately. Enqueued events that have not been sent yet will be lost.  */
    private fun closeNow() {
        synchronized(lock) {
            if (uploadThread != null) {
                if (uploadThread.isInterrupted()) {
                    return
                }
                uploadThread.interrupt()
            }
        }
    }

    fun getHalfCloseFuture(): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        return halfCloseFuture
    }

    override fun run() {
        try {
            if (publishLifecycleEvents) {
                publishLifecycleEvent(BuildEnqueued(commandStartTime))
                publishLifecycleEvent(InvocationStarted(eventStreamStartTime))
            }

            try {
                publishBuildEvents()
            } finally {
                if (publishLifecycleEvents) {
                    val invocationStatus: InvocationStatus?
                    synchronized(lock) {
                        invocationStatus = this.invocationStatus
                    }
                    val now: Instant? = clock.now()
                    publishLifecycleEvent(InvocationFinished(now, invocationStatus))
                    publishLifecycleEvent(BuildFinished(now, invocationStatus))
                }
            }
            eventBus.post(BuildEventServiceAvailabilityEvent.Companion.ofSuccess())
        } catch (e: java.lang.InterruptedException) {
            synchronized(lock) {
                com.google.common.base.Preconditions.checkState(
                    interruptCausedByCancel, "Unexpected interrupt on BES uploader thread"
                )
            }
        } catch (e: BuildEventUploadException) {
            val isTransient: Boolean = e.getStatus().isRetriable()
            val exitCode: ExitCode =
                if (isTransient)
                    ExitCode.TRANSIENT_BUILD_EVENT_SERVICE_UPLOAD_ERROR
                else
                    ExitCode.PERSISTENT_BUILD_EVENT_SERVICE_UPLOAD_ERROR
            val detailedExitCode: DetailedExitCode =
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(e.getMessage())
                        .setBuildProgress(BuildProgress.newBuilder().setCode(e.getCode()).build())
                        .build()
                )
            logger.atSevere().withCause(e).log()
            closeFuture.setException(AbruptExitException(detailedExitCode, e))
            eventBus.post(
                BuildEventServiceAvailabilityEvent(exitCode, detailedExitCode.getFailureDetail())
            )
        } catch (e: LocalFileUploadException) {
            com.google.common.base.Throwables.throwIfUnchecked(e.getCause())
            val detailedExitCode: DetailedExitCode =
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(e.getMessage())
                        .setBuildProgress(
                            BuildProgress.newBuilder()
                                .setCode(BuildProgress.Code.BES_UPLOAD_LOCAL_FILE_ERROR)
                                .build()
                        )
                        .build()
                )
            logger.atSevere().withCause(e).log()
            closeFuture.setException(AbruptExitException(detailedExitCode, e))
            eventBus.post(
                BuildEventServiceAvailabilityEvent(
                    ExitCode.TRANSIENT_BUILD_EVENT_SERVICE_UPLOAD_ERROR,
                    detailedExitCode.getFailureDetail()
                )
            )
        } catch (e: Throwable) {
            closeFuture.setException(e)
            logger.atSevere().log("BES upload failed due to a RuntimeException / Error. This is a bug.")
            throw e
        } finally {
            buildEventUploader.release()
            com.google.common.util.concurrent.MoreExecutors.shutdownAndAwaitTermination(
                timeoutExecutor,
                0,
                TimeUnit.MILLISECONDS
            )
            closeFuture.set(null)
        }
    }

    @Throws(java.lang.InterruptedException::class)
    private fun createSerializedRegularBuildEvent(
        pathConverter: com.google.devtools.build.lib.buildeventstream.PathConverter, cmd: SendRegularBuildEvent
    ): BuildEvent {
        val ctx: BuildEventContext =
            object : BuildEventContext {
                private val outputGroupModes: OutputGroupFileModes =
                    buildEventProtocolOptions.getOutputGroupFileModesMapping()

                override fun pathConverter(): com.google.devtools.build.lib.buildeventstream.PathConverter {
                    return pathConverter
                }

                override fun artifactGroupNamer(): ArtifactGroupNamer? {
                    return namer
                }

                val options: BuildEventProtocolOptions
                    get() = buildEventProtocolOptions

                override fun getFileModeForOutputGroup(outputGroup: String?): OutputGroupFileMode? {
                    return outputGroupModes.getMode(outputGroup)
                }
            }
        val serializedBepEvent: BuildEvent = cmd.event.asStreamProto(ctx)

        // TODO(lpino): Remove this logging once we can make every single event smaller than 1MB
        // as protobuf recommends.
        if (serializedBepEvent.getSerializedSize()
            > LargeBuildEventSerializedEvent.Companion.SIZE_OF_LARGE_BUILD_EVENTS_IN_BYTES
        ) {
            eventBus.post(
                LargeBuildEventSerializedEvent(
                    serializedBepEvent.getId().toString(), serializedBepEvent.getSerializedSize()
                )
            )
        }

        return serializedBepEvent
    }

    @Throws(BuildEventUploadException::class, LocalFileUploadException::class, java.lang.InterruptedException::class)
    private fun publishBuildEvents() {
        commandQueue.addFirst(OpenStream())

        // Every build event sent to the server needs to be acknowledged by it. This queue stores
        // the build events that have been sent and still have to be acknowledged by the server.
        // The build events are stored in the order they were sent.
        val ackQueue: Deque<SendSerializedBuildEvent> = ArrayDeque<SendSerializedBuildEvent>()
        var lastEventSent = false
        var acksReceived = 0
        var retryAttempt = 0
        var cmd: Command? = null
        try {
            // {@link Command.OpenStream} is the first command and opens a bidirectional streaming RPC for
            // sending build events and receiving ACKs.
            // {@link Command.SendRegularBuildEvent} sends a build event to the server. Sending a build
            // event does does not wait for the previous build event to have been ACKed.
            // {@link Command.SendLastBuildEvent} sends the last build event and half closes the RPC.
            // {@link Command.AckReceived} is executed for every ACK from the server and checks that the
            // ACKs are in the correct order.
            // {@link Command.StreamComplete} checks that all build events have been sent and all ACKs
            // have been received. If not, it invokes a retry logic that may decide to re-send every build
            // event that have not been ACKed. If so, it enqueues a {@link Command.OpenStream} command.
            while (true) {
                cmd = commandQueue.takeFirst()
                when (cmd) {
                    -> {
                        // Invariant: commandQueue only contains commands of type SendRegularBuildEvent or
                        // SendLastBuildEvent
                        logger.atInfo().log(
                            "Starting publishBuildEvents: commandQueue=%d", commandQueue.size()
                        )
                        streamContext =
                            besClient.openStream(
                                commandContext, AckCallback { ack: Long -> commandQueue.addLast(AckReceived(ack)) })
                        addStreamStatusListener(
                            streamContext.getStatus(),
                            java.util.function.Consumer { status: StreamStatus? ->
                                commandQueue.addLast(
                                    StreamComplete(
                                        status
                                    )
                                )
                            })
                    }

                    -> {
                        // Invariant: commandQueue may contain commands of any type

                        val pathConverter: com.google.devtools.build.lib.buildeventstream.PathConverter =
                            waitForUploads(sendRegularBuildEventCmd)

                        val serializedRegularBuildEvent: BuildEvent =
                            createSerializedRegularBuildEvent(pathConverter, sendRegularBuildEventCmd)

                        val bazelEvent: BazelEvent =
                            BazelEvent(
                                sendRegularBuildEventCmd.creationTime,
                                sendRegularBuildEventCmd.sequenceNumber,
                                serializedRegularBuildEvent.toByteArray()
                            )
                        ackQueue.addLast(SendSerializedBuildEvent(bazelEvent))
                        streamContext.sendOverStream(bazelEvent)
                    }

                    -> {
                        ackQueue.addLast(sendSerializedBuildEvent)
                        streamContext.sendOverStream(sendSerializedBuildEvent.request)
                        // Re-close the stream if we are re-sending the last event.
                        if (sendSerializedBuildEvent.request is StreamFinished) {
                            halfCloseEventUploadingStream()
                        }
                    }

                    -> {
                        // Invariant: the commandQueue may contain commands of any type
                        lastEventSent = true
                        val streamFinishedEvent: StreamFinished =
                            StreamFinished(
                                sendLastBuildEventCmd.creationTime, sendLastBuildEventCmd.sequenceNumber
                            )
                        ackQueue.addLast(SendSerializedBuildEvent(streamFinishedEvent))
                        streamContext.sendOverStream(streamFinishedEvent)
                        halfCloseEventUploadingStream()
                    }

                    -> {
                        // Invariant: the commandQueue may contain commands of any type
                        if (!ackQueue.isEmpty()) {
                            val expected: SendSerializedBuildEvent = ackQueue.removeFirst()
                            val actualSeqNum: Long = ackReceivedCmd.sequenceNumber
                            if (expected.request.sequenceNumber() == actualSeqNum) {
                                acksReceived++
                            } else {
                                ackQueue.addFirst(expected)
                                val message: String? =
                                    java.lang.String.format(
                                        "expected ACK with seqNum=%d but received ACK with seqNum=%d",
                                        expected.request.sequenceNumber(), actualSeqNum
                                    )
                                logger.atInfo().log("%s", message)
                                streamContext.abortStream(AbortReason.FAILED_PRECONDITION, message)
                            }
                        } else {
                            val message: String? =
                                java.lang.String.format(
                                    "received ACK (seqNum=%d) when no ACK was expected",
                                    ackReceivedCmd.sequenceNumber
                                )
                            logger.atInfo().log("%s", message)
                            streamContext.abortStream(AbortReason.FAILED_PRECONDITION, message)
                        }
                    }

                    -> {
                        // Invariant: the commandQueue only contains commands of type SendRegularBuildEvent or
                        // SendLastBuildEvent.
                        streamContext = null
                        val streamStatus: StreamStatus = streamCompleteCmd.status
                        if (streamStatus.isOk()) {
                            if (lastEventSent && ackQueue.isEmpty()) {
                                logger.atInfo().log("publishBuildEvents was successful")
                                // Upload successful. Break out from the while(true) loop.
                                return
                            } else {
                                val status: StreamStatus =
                                    if (lastEventSent)
                                        ackQueueNotEmptyStatus(ackQueue.size())
                                    else
                                        lastEventNotSentStatus()
                                val bpCode: BuildProgress.Code? =
                                    if (lastEventSent)
                                        BuildProgress.Code.BES_STREAM_COMPLETED_WITH_UNACK_EVENTS_ERROR
                                    else
                                        BuildProgress.Code.BES_STREAM_COMPLETED_WITH_UNSENT_EVENTS_ERROR
                                throw BuildEventUploadException(status, bpCode)
                            }
                        } else if (lastEventSent && ackQueue.isEmpty()) {
                            throw BuildEventUploadException(
                                streamStatus, BuildProgress.Code.BES_STREAM_COMPLETED_WITH_REMOTE_ERROR
                            )
                        }

                        if (!streamStatus.isRetriable() || streamStatus.isFailedPrecondition()) {
                            val bpCode: BuildProgress.Code? =
                                if (streamStatus.isFailedPrecondition())
                                    BuildProgress.Code.BES_UPLOAD_TIMEOUT_ERROR
                                else
                                    BuildProgress.Code.BES_STREAM_NOT_RETRYING_FAILURE
                            throw BuildEventUploadException(
                                streamStatus, bpCode, "not retrying publishBuildEvents"
                            )
                        }
                        if (retryAttempt == buildEventProtocolOptions.getBesUploadMaxRetries()) {
                            throw BuildEventUploadException(
                                streamStatus,
                                BuildProgress.Code.BES_UPLOAD_RETRY_LIMIT_EXCEEDED_FAILURE,
                                "no publishBuildEvents retry attempts left"
                            )
                        }

                        // Retry logic
                        // Adds build event commands from the ackQueue to the front of the commandQueue, so that
                        // the commands in the commandQueue are sorted by sequence number (ascending).
                        var unacked: SendSerializedBuildEvent?
                        while ((ackQueue.pollLast().also { unacked = it }) != null) {
                            commandQueue.addFirst(unacked)
                        }

                        val sleepMillis = retrySleepMillis(retryAttempt)
                        logger.atInfo().log(
                            "Retrying stream: status='%s', sleepMillis=%d", streamStatus, sleepMillis
                        )
                        sleeper.sleepMillis(sleepMillis)

                        // If we made progress, meaning the server ACKed events that we sent, then reset
                        // the retry counter to 0.
                        if (acksReceived > 0) {
                            retryAttempt = 0
                        } else {
                            retryAttempt++
                        }
                        acksReceived = 0
                        commandQueue.addFirst(OpenStream())
                    }
                }
            }
        } catch (e: java.lang.InterruptedException) {
            val limit = 30
            logger.atInfo().log(
                "Publish interrupt. Showing up to %d items from queues: ack_queue_size: %d, "
                        + "ack_queue: %s, command_queue_size: %d, command_queue: %s",
                limit,
                ackQueue.size(),
                com.google.common.collect.Iterables.limit<SendSerializedBuildEvent?>(ackQueue, limit),
                commandQueue.size(),
                com.google.common.collect.Iterables.limit<Command?>(commandQueue, limit)
            )
            if (streamContext != null) {
                streamContext.abortStream(AbortReason.CANCELLED, null)
            }
            throw e
        } catch (e: LocalFileUploadException) {
            val limit = 30
            logger.atInfo().log(
                "Publish interrupt. Showing up to %d items from queues: ack_queue_size: %d, "
                        + "ack_queue: %s, command_queue_size: %d, command_queue: %s",
                limit,
                ackQueue.size(),
                com.google.common.collect.Iterables.limit<SendSerializedBuildEvent?>(ackQueue, limit),
                commandQueue.size(),
                com.google.common.collect.Iterables.limit<Command?>(commandQueue, limit)
            )
            if (streamContext != null) {
                streamContext.abortStream(AbortReason.CANCELLED, null)
            }
            throw e
        } finally {
            logger.atInfo().log("About to cancel all local file uploads")
            com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils.logged("local file upload cancellation")
                .use { ignored ->
                    // If we failed in the middle of an event with uploads, cancel those.
                    if (cmd is SendRegularBuildEvent) {
                        cancelLocalFileUpload(cmd)
                    }
                    // Drain ackQueue and commandQueue, cancelling all pending local file uploads.
                    ackQueue.clear()
                    var queuedCmd: Command?
                    while ((commandQueue.pollFirst().also { queuedCmd = it }) != null) {
                        if (queuedCmd is SendRegularBuildEvent) {
                            cancelLocalFileUpload(queuedCmd)
                        }
                    }
                }
        }
    }

    /**
     * Half-closes the uploading stream, which can happen when we send the final event or when we
     * re-send the final event.
     */
    private fun halfCloseEventUploadingStream() {
        streamContext.halfCloseStream()
        halfCloseFuture.set(null)
        logger.atInfo().log("BES uploader is half-closed")
    }

    private fun cancelLocalFileUpload(cmd: SendRegularBuildEvent) {
        val localFileUploaderFuture: com.google.common.util.concurrent.ListenableFuture<com.google.devtools.build.lib.buildeventstream.PathConverter> =
            cmd.localFileUploadProgress
        if (!localFileUploaderFuture.isDone()) {
            localFileUploaderFuture.cancel(true)
        }
    }

    /** Sends a [LifecycleEvent] to the BES backend.  */
    @Throws(BuildEventUploadException::class, java.lang.InterruptedException::class)
    private fun publishLifecycleEvent(lifecycleEvent: LifecycleEvent?) {
        var retryAttempt = 0
        var cause: com.google.devtools.build.lib.buildeventservice.client.BuildEventServiceClient.StreamException? =
            null
        while (retryAttempt <= this.buildEventProtocolOptions.getBesUploadMaxRetries()) {
            try {
                besClient.publish(commandContext, lifecycleEvent)
                return
            } catch (e: com.google.devtools.build.lib.buildeventservice.client.BuildEventServiceClient.StreamException) {
                val status: StreamStatus = e.getStatus()
                if (!status.isRetriable() || status.isFailedPrecondition()) {
                    throw BuildEventUploadException(
                        status,
                        BuildProgress.Code.BES_STREAM_NOT_RETRYING_FAILURE,
                        "not retrying publishLifecycleEvent"
                    )
                }

                cause = e

                val sleepMillis = retrySleepMillis(retryAttempt)
                logger.atInfo().log(
                    "Retrying publishLifecycleEvent: status='%s', sleepMillis=%d", status, sleepMillis
                )

                sleeper.sleepMillis(sleepMillis)
                retryAttempt++
            }
        }

        // All retry attempts failed
        throw BuildEventUploadException(
            cause.getStatus(),
            BuildProgress.Code.BES_UPLOAD_RETRY_LIMIT_EXCEEDED_FAILURE,
            java.lang.String.format("all %d publishLifecycleEvent retry attempts failed", retryAttempt - 1)
        )
    }

    private fun ensureUploadThreadStarted() {
        synchronized(lock) {
            if (uploadThread == null) {
                uploadThread = java.lang.Thread(this, "bes-uploader")
                uploadThread.start()
            }
        }
    }

    @Throws(
        LocalFileUploadException::class,
        java.lang.InterruptedException::class
    )  // Not confident in BES's error-handling.
    private fun waitForUploads(sendRegularBuildEventCmd: SendRegularBuildEvent): com.google.devtools.build.lib.buildeventstream.PathConverter {
        try {
            // Wait for the local file and pending remote uploads to complete.
            buildEventUploader
                .waitForRemoteUploads(sendRegularBuildEventCmd.event.remoteUploads(), timeoutExecutor)
                .get()
            return sendRegularBuildEventCmd.localFileUploadProgress.get()
        } catch (e: ExecutionException) {
            logger.atWarning().withCause(e).log(
                "Failed to upload files referenced by build event: %s", e.getMessage()
            )
            com.google.common.base.Throwables.throwIfUnchecked(e.getCause())
            throw LocalFileUploadException(e.getCause())
        }
    }

    private fun lastEventNotSentStatus(): StreamStatus {
        return LocalStreamStatus(
            "server closed stream with status OK but not all events have been sent",  /* isRetriable= */
            false,  /* isFailedPrecondition= */
            true
        )
    }

    private fun ackQueueNotEmptyStatus(ackQueueSize: Int): StreamStatus {
        return LocalStreamStatus(
            java.lang.String.format(
                "server closed stream with status OK but not all ACKs have been"
                        + " received (ackQueue=%d)",
                ackQueueSize
            ),  /* isRetriable= */
            false,  /* isFailedPrecondition= */
            true
        )
    }

    private fun retrySleepMillis(attempt: Int): Long {
        com.google.common.base.Preconditions.checkArgument(attempt >= 0, "attempt must be nonnegative: %s", attempt)
        // This somewhat matches the backoff used for gRPC connection backoffs.
        return (this.buildEventProtocolOptions.getBesUploadRetryInitialDelay().toMillis()
                * java.lang.Math.pow(1.6, attempt.toDouble())).toLong()
    }

    /** Throws when a problem is encountered while uploading a build event.  */
    private class BuildEventUploadException(
        status: StreamStatus,
        code: BuildProgress.Code?,
        additionalMessage: String?
    ) : java.lang.Exception(
        getMessage(status, additionalMessage)
    ) {
        private val code: BuildProgress.Code?
        private val status: StreamStatus?

        internal constructor(status: StreamStatus, code: BuildProgress.Code?) : this(status, code, null)

        init {
            this.status = status
            this.code = code
        }

        fun getCode(): BuildProgress.Code? {
            return code
        }

        fun getStatus(): StreamStatus? {
            return status
        }

        companion object {
            private fun getMessage(status: StreamStatus, additionalMessage: String?): String {
                val sb: java.lang.StringBuilder = java.lang.StringBuilder()
                sb.append("The Build Event Protocol upload failed")
                if (additionalMessage != null) {
                    sb.append(": ").append(additionalMessage)
                }
                sb.append(": ").append(status.getErrorMessage())
                return sb.toString()
            }
        }
    }

    /**
     * Thrown when a problem is encountered while uploading a local file associated with a build
     * event.
     */
    private class LocalFileUploadException(cause: Throwable) :
        java.lang.Exception("The Build Event Protocol local file upload failed: " + cause.getMessage(), cause)

    internal class Builder {
        private var besClient: BuildEventServiceClient? = null
        private var localFileUploader: BuildEventArtifactUploader? = null
        private var bepOptions: BuildEventProtocolOptions? = null
        private var publishLifecycleEvents = false
        private var sleeper: com.google.devtools.build.lib.util.Sleeper? = null
        private var clock: com.google.devtools.build.lib.clock.Clock? = null
        private var artifactGroupNamer: ArtifactGroupNamer? = null
        private var eventBus: com.google.common.eventbus.EventBus? = null
        private var commandContext: CommandContext? = null
        private var commandStartTime: Instant? = null

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun besClient(value: BuildEventServiceClient?): Builder {
            this.besClient = value
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun localFileUploader(value: BuildEventArtifactUploader?): Builder {
            this.localFileUploader = value
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun bepOptions(value: BuildEventProtocolOptions?): Builder {
            this.bepOptions = value
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun publishLifecycleEvents(value: Boolean): Builder {
            this.publishLifecycleEvents = value
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun clock(value: com.google.devtools.build.lib.clock.Clock?): Builder {
            this.clock = value
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun sleeper(value: com.google.devtools.build.lib.util.Sleeper?): Builder {
            this.sleeper = value
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun artifactGroupNamer(value: ArtifactGroupNamer?): Builder {
            this.artifactGroupNamer = value
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun eventBus(value: com.google.common.eventbus.EventBus?): Builder {
            this.eventBus = value
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun commandContext(value: CommandContext?): Builder {
            this.commandContext = value
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun commandStartTime(value: Instant?): Builder {
            this.commandStartTime = value
            return this
        }

        fun build(): BuildEventServiceUploader {
            return BuildEventServiceUploader(
                com.google.common.base.Preconditions.checkNotNull<BuildEventServiceClient?>(besClient),
                com.google.common.base.Preconditions.checkNotNull<BuildEventArtifactUploader?>(localFileUploader),
                com.google.common.base.Preconditions.checkNotNull<BuildEventProtocolOptions?>(bepOptions),
                publishLifecycleEvents,
                com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.util.Sleeper?>(sleeper),
                com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.clock.Clock?>(clock),
                com.google.common.base.Preconditions.checkNotNull<ArtifactGroupNamer?>(artifactGroupNamer),
                com.google.common.base.Preconditions.checkNotNull<com.google.common.eventbus.EventBus?>(eventBus),
                com.google.common.base.Preconditions.checkNotNull<CommandContext?>(commandContext),
                com.google.common.base.Preconditions.checkNotNull<Instant?>(commandStartTime)
            )
        }
    }

    private class LocalStreamStatus(
        val errorMessage: String?,
        val isRetriable: Boolean,
        val isFailedPrecondition: Boolean
    ) : StreamStatus {
        val isOk: Boolean
            get() = false
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private fun addStreamStatusListener(
            stream: java.util.concurrent.Future<StreamStatus?>, onDone: java.util.function.Consumer<StreamStatus?>
        ) {
            com.google.common.util.concurrent.Futures.addCallback<StreamStatus?>(
                com.google.common.util.concurrent.JdkFutureAdapters.listenInPoolThread<StreamStatus?>(stream),
                object : com.google.common.util.concurrent.FutureCallback<StreamStatus?> {
                    override fun onSuccess(result: StreamStatus?) {
                        onDone.accept(result)
                    }

                    override fun onFailure(t: Throwable) {}
                },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        }
    }
}
