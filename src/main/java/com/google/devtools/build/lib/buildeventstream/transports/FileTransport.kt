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
package com.google.devtools.build.lib.buildeventstream.transports

import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Function
import com.google.common.base.Preconditions
import com.google.common.util.concurrent.*
import com.google.devtools.build.lib.buildeventstream.PathConverter
import java.lang.Long
import java.lang.String
import java.time.Duration
import java.util.concurrent.TimeoutException
import javax.annotation.concurrent.ThreadSafe
import kotlin.Any
import kotlin.Boolean
import kotlin.ByteArray
import kotlin.Error
import kotlin.IllegalStateException
import kotlin.RuntimeException
import kotlin.Throwable
import kotlin.plus

/**
 * Non-blocking file transport.
 * 
 * 
 * Implementors of this class need to implement `#sendBuildEvent(BuildEvent)` which
 * serializes the build event and writes it to a file.
 */
internal abstract class FileTransport(
    outputStream: BufferedOutputStream?,
    options: BuildEventProtocolOptions,
    uploader: BuildEventArtifactUploader,
    namer: ArtifactGroupNamer,
    besUploadMode: BesUploadMode?
) : BuildEventTransport {
    private val options: BuildEventProtocolOptions
    private val uploader: BuildEventArtifactUploader
    private val writer: SequentialWriter
    private val namer: ArtifactGroupNamer
    private val besUploadMode: BesUploadMode?

    private val timeoutExecutor: ScheduledExecutorService = MoreExecutors.listeningDecorator(
        Executors.newSingleThreadScheduledExecutor(
            ThreadFactoryBuilder().setNameFormat("file-uploader-timeout-%d").build()
        )
    )

    init {
        this.uploader = uploader
        this.options = options
        this.writer =
            SequentialWriter(
                outputStream,
                Function { buildEvent: BuildEvent? -> this.serializeEvent(buildEvent) },
                uploader,
                timeoutExecutor
            )
        this.namer = namer
        this.besUploadMode = besUploadMode
    }

    @ThreadSafe
    @VisibleForTesting
    internal class SequentialWriter(
        outputStream: BufferedOutputStream?,
        serializeFunc: Function<BuildEvent?, ByteArray?>?,
        uploader: BuildEventArtifactUploader?,
        timeoutExecutor: ScheduledExecutorService?
    ) : Runnable {
        private val writerThread: Thread
        private val out: BufferedOutputStream
        private val serializeFunc: Function<BuildEvent?, ByteArray?>
        private val uploader: BuildEventArtifactUploader
        private val isClosed: AtomicBoolean = AtomicBoolean()
        private val closeFuture: SettableFuture<Void?> = SettableFuture.create<Void?>()

        @VisibleForTesting
        val pendingWrites: BlockingQueue<ListenableFuture<BuildEvent?>?> =
            LinkedBlockingDeque<ListenableFuture<BuildEvent?>?>()

        private val timeoutExecutor: ScheduledExecutorService

        init {
            Preconditions.checkNotNull<BuildEventArtifactUploader?>(uploader)

            this.out = Preconditions.checkNotNull<BufferedOutputStream>(outputStream)
            this.writerThread = Thread(this, "bep-local-writer")
            this.serializeFunc = Preconditions.checkNotNull<Function<BuildEvent?, ByteArray?>>(serializeFunc)
            this.uploader = Preconditions.checkNotNull<BuildEventArtifactUploader>(uploader)
            this.timeoutExecutor = Preconditions.checkNotNull<ScheduledExecutorService>(timeoutExecutor)
            writerThread.start()
        }

        override fun run() {
            var buildEventF: ListenableFuture<BuildEvent?>?
            try {
                var prevFlush: Instant = Instant.now()
                while ((pendingWrites.poll(flushInterval.toMillis(), TimeUnit.MILLISECONDS).also { buildEventF = it })
                    !== CLOSE_EVENT_FUTURE
                ) {
                    if (buildEventF != null) {
                        val buildEvent: BuildEvent? = buildEventF.get()
                        if (buildEvent != null) {
                            val serialized = serializeFunc.apply(buildEvent)
                            out.write(serialized)
                        }
                    }
                    val now: Instant = Instant.now()
                    if (buildEventF == null || now.compareTo(prevFlush.plus(flushInterval)) > 0) {
                        // Some users, e.g. Tulsi, expect prompt BEP stream flushes for interactive use.
                        out.flush()
                        prevFlush = now
                    }
                }
            } catch (e: ExecutionException) {
                if (e.getCause() is RuntimeException || e.getCause() is Error) {
                    closeFuture.setException(e.getCause())
                }
                exitFailure(e)
            } catch (e: IOException) {
                exitFailure(e)
            } catch (e: InterruptedException) {
                exitFailure(e)
            } catch (e: CancellationException) {
                exitFailure(e)
            } finally {
                try {
                    out.flush()
                    out.close()
                } catch (e: IOException) {
                    logger.atSevere().withCause(e).log("Failed to close BEP file output stream.")
                } finally {
                    uploader.release()
                    timeoutExecutor.shutdown()
                }
                closeFuture.set(null)
            }
        }

        private fun exitFailure(e: Throwable) {
            val message: String?
            // Print a more useful error message when the upload times out.
            // An {@link ExecutionException} may be wrapping a {@link TimeoutException} if the
            // Future was created with {@link Futures#withTimeout}.
            if (e is ExecutionException && e.getCause() is TimeoutException) {
                message = "Unable to write all BEP events to file due to timeout"
            } else {
                message =
                    String.format("Unable to write all BEP events to file due to '%s'", e.getMessage())
            }
            closeFuture.setException(
                AbruptExitException(
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage(message)
                            .setBuildProgress(BuildProgress.newBuilder().setCode(getBuildProgressCode(e)))
                            .build()
                    ),
                    e
                )
            )
            pendingWrites.clear()
            logger.atSevere().withCause(e).log("%s", message)
        }

        private fun closeNow() {
            if (closeFuture.isDone()) {
                return
            }
            pendingWrites.clear()
            pendingWrites.add(CLOSE_EVENT_FUTURE)
        }

        fun close(): ListenableFuture<Void?> {
            if (isClosed.getAndSet(true)) {
                return closeFuture
            } else if (closeFuture.isDone()) {
                return closeFuture
            }

            // Close abruptly if the closing future is cancelled.
            closeFuture.addListener(
                Runnable {
                    if (closeFuture.isCancelled()) {
                        closeNow()
                    }
                },
                MoreExecutors.directExecutor()
            )

            pendingWrites.add(CLOSE_EVENT_FUTURE)
            return closeFuture
        }

        companion object {
            private val CLOSE_EVENT_FUTURE: ListenableFuture<BuildEvent?> = Futures.immediateFailedFuture<BuildEvent?>(
                IllegalStateException(
                    "A FileTransport is trying to write CLOSE_EVENT_FUTURE, this is a bug."
                )
            )
            private val flushInterval: Duration = Duration.ofMillis(
                Long.parseLong(System.getProperty("EXPERIMENTAL_BEP_FILE_FLUSH_MILLIS", "250"))
            )
                get() = Companion.field

            private fun getBuildProgressCode(e: Throwable?): BuildProgress.Code {
                if (e is ExecutionException && e.getCause() is TimeoutException) {
                    return Code.BES_FILE_WRITE_TIMEOUT
                }
                val maybeUnwrappedFailure = if (e is ExecutionException) e.getCause() else e
                if (maybeUnwrappedFailure is IOException) {
                    return Code.BES_FILE_WRITE_IO_ERROR
                }
                if (maybeUnwrappedFailure is InterruptedException) {
                    return Code.BES_FILE_WRITE_INTERRUPTED
                }
                if (maybeUnwrappedFailure is CancellationException) {
                    return Code.BES_FILE_WRITE_CANCELED
                }
                return Code.BES_FILE_WRITE_UNKNOWN_ERROR
            }
        }
    }

    override fun sendBuildEvent(event: BuildEvent?) {
        if (writer.isClosed.get()) {
            return
        }
        try {
            if (!writer.pendingWrites.add(asStreamProto(event, namer))) {
                logger.atSevere().log("Failed to add BEP event to the write queue")
            }
        } catch (e: RejectedExecutionException) {
            // If early shutdown races with this event, log but otherwise ignore.
            logger.atWarning().withCause(e).log("Event upload started after shutdown")
        }
    }

    protected abstract fun serializeEvent(buildEvent: BuildEvent?): ByteArray?

    override fun close(): ListenableFuture<Void?> {
        return writer.close()
    }

    /**
     * Converts the given event into a proto object; this may trigger uploading of referenced files as
     * a side effect. May return `null` if there was an interrupt. This method is not
     * thread-safe.
     */
    private fun asStreamProto(
        event: BuildEvent?, namer: ArtifactGroupNamer
    ): ListenableFuture<BuildEvent?> {
        BuildEvent > Preconditions.checkNotNull<BuildEvent?>(event)

        val converterFuture =
            uploader.uploadReferencedLocalFiles(event.referencedLocalFiles())
        val remoteUploads =
            uploader.waitForRemoteUploads(event.remoteUploads(), timeoutExecutor)
        return Futures.transform<MutableList<Any?>?, BuildEvent?>(
            Futures.allAsList<Any?>(converterFuture, remoteUploads),
            Function { results: MutableList<Any?>? ->
                val context: BuildEventContext =
                    object : BuildEventContext {
                        private val outputGroupModes: OutputGroupFileModes = options.getOutputGroupFileModesMapping()

                        override fun pathConverter(): PathConverter? {
                            return@transform Futures.getUnchecked<PathConverter?>(converterFuture)
                        }

                        override fun artifactGroupNamer(): ArtifactGroupNamer {
                            return@transform namer
                        }

                        val options: BuildEventProtocolOptions

                        override fun getFileModeForOutputGroup(outputGroup: kotlin.String?): OutputGroupFileMode? {
                            return@transform outputGroupModes.getMode(outputGroup)
                        }
                    }
                try {
                    return@transform event.asStreamProto(context)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@transform null
                }
            },
            MoreExecutors.directExecutor()
        )
    }

    override fun mayBeSlow(): Boolean {
        return uploader.mayBeSlow()
    }

    override fun getBesUploadMode(): BesUploadMode? {
        return besUploadMode
    }

    override fun getUploader(): BuildEventArtifactUploader {
        return uploader
    }

    val flushInterval: Duration
        /** Determines how often the [FileTransport] flushes events.  */
        get() = writer.flushInterval

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    }
}
