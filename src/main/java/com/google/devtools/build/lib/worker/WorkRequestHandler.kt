// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.worker

import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest

/**
 * A helper class that handles WorkRequests (https://bazel.build/docs/persistent-workers), including
 * multiplex workers (https://bazel.build/docs/multiplex-worker).
 */
open class WorkRequestHandler private constructor(
    /** The function to be called after each [WorkRequest] is read.  */
    private val callback: WorkRequestCallback,
    stderr: PrintStream,
    messageProcessor: WorkerMessageProcessor,
    cpuUsageBeforeGc: java.time.Duration,
    cancelCallback: java.util.function.BiConsumer<Int?, java.lang.Thread?>?,
    idleTimeBeforeGc: java.time.Duration
) : java.lang.AutoCloseable {
    /** Contains the logic for reading [WorkRequest]s and writing [WorkResponse]s.  */
    interface WorkerMessageProcessor {
        /** Reads the next incoming request from this worker's stdin.  */
        @Throws(IOException::class)
        fun readWorkRequest(): WorkRequest?

        /**
         * Writes the provided [WorkResponse] to this worker's stdout. This function is also
         * responsible for flushing the stdout.
         */
        @Throws(IOException::class)
        fun writeWorkResponse(workResponse: WorkResponse?)

        /** Clean up.  */
        @Throws(IOException::class)
        fun close()
    }

    /** Holds information necessary to properly handle a request, especially for cancellation.  */
    internal class RequestInfo(thread: java.lang.Thread) {
        /** The thread handling the request.  */
        val thread: java.lang.Thread

        /** If true, we have received a cancel request for this request.  */
        private val cancelled: AtomicBoolean = AtomicBoolean(false)

        /**
         * The builder for the response to this request. Since only one response must be sent per
         * request, this builder must be accessed through takeBuilder(), which zeroes this field and
         * returns the builder.
         */
        private var responseBuilder: WorkResponse.Builder? = WorkResponse.newBuilder()

        init {
            this.thread = thread
        }

        /** Sets whether this request has been cancelled.  */
        fun setCancelled() {
            cancelled.set(true)
        }

        /** Returns true if this request has been cancelled.  */
        fun isCancelled(): Boolean {
            return cancelled.get()
        }

        /**
         * Returns the response builder. If called more than once on the same instance, subsequent calls
         * will return `null`.
         */
        @kotlin.jvm.Synchronized
        fun takeBuilder(): java.util.Optional<WorkResponse.Builder> {
            val b: WorkResponse.Builder? = responseBuilder
            responseBuilder = null
            return java.util.Optional.ofNullable<WorkResponse.Builder?>(b)
        }

        /**
         * Adds `s` as output to when the response eventually gets built. Does nothing if the
         * response has already been taken. There is no guarantee that the response hasn't already been
         * taken, making this call a no-op. This may be called multiple times. No delimiters are added
         * between strings from multiple calls.
         */
        @kotlin.jvm.Synchronized
        fun addOutput(s: String?) {
            if (responseBuilder != null) {
                responseBuilder.setOutput(responseBuilder.getOutput() + s)
            }
        }
    }

    /** Requests that are currently being processed. Visible for testing.  */
    @kotlin.jvm.JvmField
    val activeRequests: ConcurrentMap<Int?, RequestInfo> = ConcurrentHashMap<Int?, RequestInfo>()

    /** This worker's stderr.  */
    private val stderr: PrintStream

    @kotlin.jvm.JvmField
    val messageProcessor: WorkerMessageProcessor

    private val cancelCallback: java.util.function.BiConsumer<Int?, java.lang.Thread?>?

    /**
     * A scheduler that runs garbage collection after a certain amount of CPU time has passed. In our
     * experience, explicit GC reclaims much more than implicit GC. This scheduler helps make sure
     * very busy workers don't grow ridiculously large.
     */
    private val gcScheduler: CpuTimeBasedGcScheduler

    /**
     * A scheduler that runs garbage collection after a certain amount of time without any activity.
     * In our experience, explicit GC reclaims much more than implicit GC. This scheduler helps make
     * sure workers don't hang on to excessive memory after they are done working.
     */
    private val idleGcScheduler: IdleGcScheduler

    /**
     * If set, this worker will stop handling requests and shut itself down. This can happen if
     * something throws an [Error].
     */
    private val shutdownWorker: AtomicBoolean = AtomicBoolean(false)

    /**
     * Creates a `WorkRequestHandler` that will call `callback` for each WorkRequest
     * received.
     * 
     * @param callback Callback method for executing a single WorkRequest in a thread. The first
     * argument to `callback` is the set of command-line arguments, the second is where all
     * error messages and other user-oriented messages should be written to. The callback must
     * return an exit code indicating success (zero) or failure (nonzero).
     * @param stderr Stream that log messages should be written to, typically the process' stderr.
     * @param messageProcessor Object responsible for parsing `WorkRequest`s from the server and
     * writing `WorkResponses` to the server.
     */
    @Deprecated("")
    constructor(
        callback: java.util.function.BiFunction<MutableList<String?>?, PrintWriter?, Int?>,
        stderr: PrintStream,
        messageProcessor: WorkerMessageProcessor
    ) : this(callback, stderr, messageProcessor, java.time.Duration.ZERO, null)

    /**
     * Creates a `WorkRequestHandler` that will call `callback` for each WorkRequest
     * received.
     * 
     * @param callback Callback method for executing a single WorkRequest in a thread. The first
     * argument to `callback` is the set of command-line arguments, the second is where all
     * error messages and other user-oriented messages should be written to. The callback must
     * return an exit code indicating success (zero) or failure (nonzero).
     * @param stderr Stream that log messages should be written to, typically the process' stderr.
     * @param messageProcessor Object responsible for parsing `WorkRequest`s from the server and
     * writing `WorkResponses` to the server.
     * @param cpuUsageBeforeGc The minimum amount of CPU time between explicit garbage collection
     * calls. Pass Duration.ZERO to not do explicit garbage collection.
     */
    @Deprecated("Use WorkRequestHandlerBuilder instead.")
    constructor(
        callback: java.util.function.BiFunction<MutableList<String?>?, PrintWriter?, Int?>,
        stderr: PrintStream,
        messageProcessor: WorkerMessageProcessor,
        cpuUsageBeforeGc: java.time.Duration
    ) : this(callback, stderr, messageProcessor, cpuUsageBeforeGc, null)

    /**
     * Creates a `WorkRequestHandler` that will call `callback` for each WorkRequest
     * received. Only used for the Builder.
     * 
     */
    @Deprecated("Use WorkRequestHandlerBuilder instead.")
    private constructor(
        callback: java.util.function.BiFunction<MutableList<String?>?, PrintWriter?, Int?>,
        stderr: PrintStream,
        messageProcessor: WorkerMessageProcessor,
        cpuUsageBeforeGc: java.time.Duration,
        cancelCallback: java.util.function.BiConsumer<Int?, java.lang.Thread?>?
    ) : this(
        WorkRequestCallback(java.util.function.BiFunction { request: WorkRequest?, pw: PrintWriter? ->
            callback.apply(
                request.getArgumentsList(),
                pw
            )
        }),
        stderr,
        messageProcessor,
        cpuUsageBeforeGc,
        cancelCallback,
        java.time.Duration.ZERO
    )

    /**
     * Creates a `WorkRequestHandler` that will call `callback` for each WorkRequest
     * received. Only used for the Builder.
     * 
     * @param callback WorkRequestCallback object with Callback method for executing a single
     * WorkRequest in a thread. The first argument to `callback` is the WorkRequest, the
     * second is where all error messages and other user-oriented messages should be written to.
     * The callback must return an exit code indicating success (zero) or failure (nonzero).
     */
    init {
        this.stderr = stderr
        this.messageProcessor = messageProcessor
        this.gcScheduler = CpuTimeBasedGcScheduler(cpuUsageBeforeGc)
        this.cancelCallback = cancelCallback
        this.idleGcScheduler = IdleGcScheduler(idleTimeBeforeGc)
    }

    /** A wrapper class for the callback BiFunction  */
    class WorkRequestCallback(callback: java.util.function.BiFunction<WorkRequest?, PrintWriter?, Int?>) {
        /**
         * Callback method for executing a single WorkRequest in a thread. The first argument to `callback` is the WorkRequest, the second is where all error messages and other user-oriented
         * messages should be written to. The callback must return an exit code indicating success
         * (zero) or failure (nonzero).
         */
        private val callback: java.util.function.BiFunction<WorkRequest?, PrintWriter?, Int?>

        init {
            this.callback = callback
        }

        @Throws(java.lang.InterruptedException::class)
        fun apply(workRequest: WorkRequest, printWriter: PrintWriter?): Int? {
            val result: Int? = callback.apply(workRequest, printWriter)
            if (java.lang.Thread.interrupted()) {
                throw java.lang.InterruptedException("Work request interrupted: " + workRequest.getRequestId())
            }
            return result
        }
    }

    /** Builder class for WorkRequestHandler. Required parameters are passed to the constructor.  */
    class WorkRequestHandlerBuilder(
        private val callback: WorkRequestCallback,
        stderr: PrintStream,
        messageProcessor: WorkerMessageProcessor
    ) {
        private val stderr: PrintStream
        private val messageProcessor: WorkerMessageProcessor
        private var cpuUsageBeforeGc: java.time.Duration = java.time.Duration.ZERO
        private var cancelCallback: java.util.function.BiConsumer<Int?, java.lang.Thread?>? = null
        private var idleTimeBeforeGc: java.time.Duration = java.time.Duration.ZERO

        /**
         * Creates a `WorkRequestHandlerBuilder`.
         * 
         * @param callback Callback method for executing a single WorkRequest in a thread. The first
         * argument to `callback` is the set of command-line arguments, the second is where
         * all error messages and other user-oriented messages should be written to. The callback
         * must return an exit code indicating success (zero) or failure (nonzero).
         * @param stderr Stream that log messages should be written to, typically the process' stderr.
         * @param messageProcessor Object responsible for parsing `WorkRequest`s from the server
         * and writing `WorkResponses` to the server.
         */
        @Deprecated("use WorkRequestHandlerBuilder with WorkRequestCallback instead")
        constructor(
            callback: java.util.function.BiFunction<MutableList<String?>?, PrintWriter?, Int?>,
            stderr: PrintStream,
            messageProcessor: WorkerMessageProcessor
        ) : this(
            WorkRequestCallback(java.util.function.BiFunction { request: WorkRequest?, pw: PrintWriter? ->
                callback.apply(
                    request.getArgumentsList(),
                    pw
                )
            }),
            stderr,
            messageProcessor
        )

        /**
         * Creates a `WorkRequestHandlerBuilder`.
         * 
         * @param callback WorkRequestCallback object with Callback method for executing a single
         * WorkRequest in a thread. The first argument to `callback` is the WorkRequest, the
         * second is where all error messages and other user-oriented messages should be written to.
         * The callback must return an exit code indicating success (zero) or failure (nonzero).
         * @param stderr Stream that log messages should be written to, typically the process' stderr.
         * @param messageProcessor Object responsible for parsing `WorkRequest`s from the server
         * and writing `WorkResponses` to the server.
         */
        init {
            this.stderr = stderr
            this.messageProcessor = messageProcessor
        }

        /**
         * Sets the minimum amount of CPU time between explicit garbage collection calls. Pass
         * Duration.ZERO to not do explicit garbage collection (the default).
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setCpuUsageBeforeGc(cpuUsageBeforeGc: java.time.Duration): WorkRequestHandlerBuilder {
            this.cpuUsageBeforeGc = cpuUsageBeforeGc
            return this
        }

        /**
         * Sets a callback will be called when a cancellation message has been received. The callback
         * will be call with the request ID and the thread executing the request.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setCancelCallback(cancelCallback: java.util.function.BiConsumer<Int?, java.lang.Thread?>?): WorkRequestHandlerBuilder {
            this.cancelCallback = cancelCallback
            return this
        }

        /** Sets the time without any work that should elapse before forcing a GC.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setIdleTimeBeforeGc(idleTimeBeforeGc: java.time.Duration): WorkRequestHandlerBuilder {
            this.idleTimeBeforeGc = idleTimeBeforeGc
            return this
        }

        /** Returns a WorkRequestHandler instance with the values in this Builder.  */
        fun build(): WorkRequestHandler {
            return WorkRequestHandler(
                callback, stderr, messageProcessor, cpuUsageBeforeGc, cancelCallback, idleTimeBeforeGc
            )
        }
    }

    /**
     * Runs an infinite loop of reading [WorkRequest] from `in`, running the callback,
     * then writing the corresponding [WorkResponse] to `out`. If there is an error
     * reading or writing the requests or responses, it writes an error message on `err` and
     * returns. If `in` reaches EOF, it also returns.
     * 
     * 
     * This function also wraps the system streams in a [WorkerIO] instance that prevents the
     * underlying tool from writing to [System.out] or reading from [System. in], which
     * would corrupt the worker worker protocol. When the while loop exits, the original system
     * streams will be swapped back into [System].
     */
    @Throws(IOException::class)
    open fun processRequests() {
        // Wrap the system streams into a WorkerIO instance to prevent unexpected reads and writes on
        // stdin/stdout.
        val workerIO = WorkerIO.Companion.capture()

        try {
            while (!shutdownWorker.get()) {
                val request: WorkRequest? = messageProcessor.readWorkRequest()
                idleGcScheduler.markActivity(true)
                if (request == null) {
                    break
                }
                if (request.getCancel()) {
                    respondToCancelRequest(request)
                } else {
                    startResponseThread(workerIO, request)
                }
            }
        } catch (e: IOException) {
            stderr.println("Error reading next WorkRequest: " + e)
            e.printStackTrace(stderr)
        } finally {
            idleGcScheduler.stop()
            // TODO(b/220878242): Give the outstanding requests a chance to send a "shutdown" response,
            // but also try to kill stuck threads. For now, we just interrupt the remaining threads.
            // We considered doing System.exit here, but that is hard to test and would deny the callers
            // of this method a chance to clean up. Instead, we initiate the cleanup of our resources here
            // and the caller can decide whether to wait for an orderly shutdown or now.
            for (ri in activeRequests.values()) {
                if (ri.thread.isAlive()) {
                    try {
                        ri.thread.interrupt()
                    } catch (e: java.lang.RuntimeException) {
                        // If we can't interrupt, we can't do much else.
                    }
                }
            }

            try {
                // Unwrap the system streams placing the original streams back
                workerIO.close()
            } catch (e: java.lang.Exception) {
                stderr.println(e.getMessage())
            }
        }
    }

    /** Starts a thread for the given request.  */
    fun startResponseThread(workerIO: WorkerIO, request: WorkRequest) {
        val currentThread: java.lang.Thread = java.lang.Thread.currentThread()
        val threadName =
            if (request.getRequestId() > 0)
                "multiplex-request-" + request.getRequestId()
            else
                "singleplex-request"
        // TODO(larsrc): See if this can be handled with a queue instead, without introducing more
        // race conditions.
        if (request.getRequestId() === 0) {
            while (activeRequests.containsKey(request.getRequestId())) {
                // b/194051480: Previous singleplex requests can still be in activeRequests for a bit after
                // the response has been sent. We need to wait for them to vanish.
                try {
                    java.lang.Thread.sleep(1)
                } catch (e: java.lang.InterruptedException) {
                    java.lang.Thread.currentThread().interrupt()
                    return
                }
            }
        }
        val t: java.lang.Thread =
            java.lang.Thread(
                java.lang.Runnable {
                    val requestInfo: RequestInfo? = activeRequests.get(request.getRequestId())
                    if (requestInfo == null) {
                        // Already cancelled
                        idleGcScheduler.markActivity(!activeRequests.isEmpty())
                        return@Runnable
                    }
                    try {
                        respondToRequest(workerIO, request, requestInfo)
                    } catch (e: IOException) {
                        // IOExceptions here means a problem talking to the server, so we must shut down.
                        if (!shutdownWorker.compareAndSet(false, true)) {
                            stderr.println("Error communicating with server, shutting down worker.")
                            e.printStackTrace(stderr)
                            currentThread.interrupt()
                        }
                    } finally {
                        activeRequests.remove(request.getRequestId())
                        idleGcScheduler.markActivity(!activeRequests.isEmpty())
                    }
                },
                threadName
            )
        t.setUncaughtExceptionHandler(
            java.lang.Thread.UncaughtExceptionHandler { t1: java.lang.Thread?, e: Throwable? ->
                // Shut down the worker in case of severe issues. We don't handle RuntimeException here,
                // as those are not serious enough to merit shutting down the worker.
                if (e is java.lang.Error && shutdownWorker.compareAndSet(false, true)) {
                    stderr.println("Error thrown by worker thread, shutting down worker.")
                    e.printStackTrace(stderr)
                    currentThread.interrupt()
                    idleGcScheduler.stop()
                    java.lang.System.exit(1)
                }
            })
        val previous: RequestInfo? = activeRequests.putIfAbsent(
            request.getRequestId(),
            com.google.devtools.build.lib.worker.WorkRequestHandler.RequestInfo(t)
        )
        check(previous == null) { "Request still active: " + request.getRequestId() }
        t.start()
    }

    /**
     * Handles and responds to the given [WorkRequest].
     * 
     * @throws IOException if there is an error talking to the server. Errors from calling the [     ][.callback] are reported with exit code 1.
     */
    @com.google.common.annotations.VisibleForTesting
    @Throws(IOException::class)
    fun respondToRequest(workerIO: WorkerIO, request: WorkRequest, requestInfo: RequestInfo) {
        var exitCode: Int
        val sw: java.io.StringWriter = java.io.StringWriter()
        PrintWriter(sw).use { pw ->
            try {
                exitCode = callback.apply(request, pw)!!
            } catch (e: java.lang.InterruptedException) {
                exitCode = 1
            } catch (e: java.lang.Exception) {
                e.printStackTrace(pw)
                exitCode = 1
            }
            try {
                // Read out the captured string for the final WorkResponse output
                val captured: String = workerIO.readCapturedAsUtf8String().trim()
                if (!captured.isEmpty()) {
                    pw.write(captured)
                }
            } catch (e: IOException) {
                stderr.println(e.getMessage())
            }
        }
        val optBuilder: java.util.Optional<WorkResponse.Builder> = requestInfo.takeBuilder()
        if (optBuilder.isPresent()) {
            val builder: WorkResponse.Builder = optBuilder.get()
            builder.setRequestId(request.getRequestId())
            if (requestInfo.isCancelled()) {
                builder.setWasCancelled(true)
            } else {
                builder.setOutput(builder.getOutput() + sw).setExitCode(exitCode)
            }
            val response: WorkResponse? = builder.build()
            synchronized(this) {
                messageProcessor.writeWorkResponse(response)
            }
        }
        gcScheduler.maybePerformGc()
    }

    /**
     * Marks the given request as cancelled and uses [.cancelCallback] to request cancellation.
     * 
     * 
     * For simplicity, and to avoid blocking in [.cancelCallback], response to cancellation
     * is still handled by [.respondToRequest] once the canceled request aborts (or finishes).
     */
    fun respondToCancelRequest(request: WorkRequest) {
        // Theoretically, we could have gotten two singleplex requests, and we can't tell those apart.
        // However, that's a violation of the protocol, so we don't try to handle it (not least because
        // handling it would be quite error-prone).
        val ri: RequestInfo? = activeRequests.get(request.getRequestId())

        if (ri == null) {
            return
        }
        if (cancelCallback == null) {
            ri.setCancelled()
            // This is either an error on the server side or a version mismatch between the server setup
            // and the binary. It's better to wait for the regular work to finish instead of breaking the
            // build, but we should inform the user about the bad setup.
            ri.addOutput(
                java.lang.String.format(
                    "Cancellation request received for worker request %d, but this worker does not"
                            + " support cancellation.\n",
                    request.getRequestId()
                )
            )
        } else {
            if (ri.thread.isAlive() && !ri.isCancelled()) {
                ri.setCancelled()
                val t: java.lang.Thread =
                    java.lang.Thread( // Response will be sent from request thread once request handler returns.
                        // We can ignore any exceptions in cancel callback since it's best effort.
                        java.lang.Runnable { cancelCallback.accept(request.getRequestId(), ri.thread) })
                t.start()
            }
        }
    }

    @Throws(IOException::class)
    override fun close() {
        messageProcessor.close()
    }

    /** Schedules GC when the worker has been idle for a while  */
    private class IdleGcScheduler(idleTimeBeforeGc: java.time.Duration) {
        private var lastActivity: Instant = Instant.EPOCH
        private var lastGc: Instant = Instant.EPOCH

        /** Minimum duration from the end of activity until we perform an idle GC.  */
        private val idleTimeBeforeGc: java.time.Duration

        private val executor: ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(1)
        private var futureGc: java.util.concurrent.ScheduledFuture<*>? = null

        /**
         * Creates a new scheduler.
         * 
         * @param idleTimeBeforeGc The time from the last activity until attempting GC.
         */
        init {
            this.idleTimeBeforeGc = idleTimeBeforeGc
        }

        @kotlin.jvm.Synchronized
        fun start() {
            if (!idleTimeBeforeGc.isZero()) {
                futureGc =
                    executor.schedule(
                        java.lang.Runnable { this.maybeDoGc() },
                        idleTimeBeforeGc.toMillis(),
                        TimeUnit.MILLISECONDS
                    )
            }
        }

        /**
         * Should be called whenever there is some sort of activity starting or ending. Better to call
         * too often.
         */
        @kotlin.jvm.Synchronized
        fun markActivity(anythingActive: Boolean) {
            lastActivity = Instant.now()
            if (futureGc != null) {
                futureGc.cancel(false)
                futureGc = null
            }
            if (!anythingActive) {
                start()
            }
        }

        fun maybeDoGc() {
            if (lastGc.isBefore(lastActivity)
                && lastActivity.isBefore(Instant.now().minus(idleTimeBeforeGc))
            ) {
                java.lang.System.gc()
                lastGc = Instant.now()
            } else {
                start()
            }
        }

        @kotlin.jvm.Synchronized
        fun stop() {
            if (futureGc != null) {
                futureGc.cancel(false)
                futureGc = null
            }
            executor.shutdown()
        }
    }

    /**
     * Class that performs GC occasionally, based on how much CPU time has passed. This strikes a
     * compromise between blindly doing GC after e.g. every request, which takes too much CPU, and not
     * doing explicit GC at all, which causes poor garbage collection in some cases.
     */
    private class CpuTimeBasedGcScheduler(cpuUsageBeforeGc: java.time.Duration) {
        /**
         * After this much CPU time has elapsed, we may force a GC run. Set to [Duration.ZERO] to
         * disable.
         */
        private val cpuUsageBeforeGc: java.time.Duration

        /** The total process CPU time at the last GC run (or from the start of the worker).  */
        private val cpuTimeAtLastGc: AtomicReference<java.time.Duration>

        /**
         * Creates a new [CpuTimeBasedGcScheduler] that may perform GC after `cpuUsageBeforeGc` amount of CPU time has been used.
         */
        init {
            this.cpuUsageBeforeGc = cpuUsageBeforeGc
            this.cpuTimeAtLastGc = AtomicReference<java.time.Duration>(this.cpuTime)
        }

        val cpuTime: java.time.Duration
            get() = if (!cpuUsageBeforeGc.isZero())
                java.time.Duration.ofNanos(bean.getProcessCpuTime())
            else
                java.time.Duration.ZERO

        /** Call occasionally to perform a GC if enough CPU time has been used.  */
        fun maybePerformGc() {
            if (!cpuUsageBeforeGc.isZero()) {
                val currentCpuTime: java.time.Duration = this.cpuTime
                val lastCpuTime: java.time.Duration = cpuTimeAtLastGc.get()
                // Do GC when enough CPU time has been used, but only if nobody else beat us to it.
                if (currentCpuTime.minus(lastCpuTime).compareTo(cpuUsageBeforeGc) > 0
                    && cpuTimeAtLastGc.compareAndSet(lastCpuTime, currentCpuTime)
                ) {
                    java.lang.System.gc()
                    // Avoid counting GC CPU time against CPU time before next GC.
                    cpuTimeAtLastGc.compareAndSet(currentCpuTime, this.cpuTime)
                }
            }
        }

        companion object {
            /** Used to get the CPU time used by this process.  */
            private val bean: com.sun.management.OperatingSystemMXBean =
                java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        }
    }

    /**
     * Class that wraps the standard [System. in], [System.out], and [System.err]
     * with our own ByteArrayOutputStream that allows [WorkRequestHandler] to safely capture
     * outputs that can't be directly captured by the PrintStream associated with the work request.
     * 
     * 
     * This is most useful when integrating JVM tools that write exceptions and logs directly to
     * [System.out] and [System.err], which would corrupt the persistent worker protocol.
     * We also redirect [System. in], just in case a tool should attempt to read it.
     * 
     * 
     * WorkerIO implements [AutoCloseable] and will swap the original streams back into
     * [System] once close has been called.
     */
    class WorkerIO @com.google.common.annotations.VisibleForTesting internal constructor(
        originalInputStream: java.io.InputStream?,
        originalOutputStream: PrintStream?,
        originalErrorStream: PrintStream?,
        capturedStream: java.io.ByteArrayOutputStream,
        restore: java.lang.AutoCloseable
    ) : java.lang.AutoCloseable {
        private val originalInputStream: java.io.InputStream?
        private val originalOutputStream: PrintStream?
        private val originalErrorStream: PrintStream?
        private val capturedStream: java.io.ByteArrayOutputStream
        private val restore: java.lang.AutoCloseable

        /**
         * Creates a new [WorkerIO] that allows [WorkRequestHandler] to capture standard
         * output and error streams that can't be directly captured by the PrintStream associated with
         * the work request.
         */
        init {
            this.originalInputStream = originalInputStream
            this.originalOutputStream = originalOutputStream
            this.originalErrorStream = originalErrorStream
            this.capturedStream = capturedStream
            this.restore = restore
        }

        /** Returns the original input stream most commonly provided by [System. in]  */
        @com.google.common.annotations.VisibleForTesting
        fun getOriginalInputStream(): java.io.InputStream? {
            return originalInputStream
        }

        /** Returns the original output stream most commonly provided by [System.out]  */
        @com.google.common.annotations.VisibleForTesting
        fun getOriginalOutputStream(): PrintStream? {
            return originalOutputStream
        }

        /** Returns the original error stream most commonly provided by [System.err]  */
        @com.google.common.annotations.VisibleForTesting
        fun getOriginalErrorStream(): PrintStream? {
            return originalErrorStream
        }

        /** Returns the captured outputs as a UTF-8 string  */
        @com.google.common.annotations.VisibleForTesting
        @Throws(IOException::class)
        fun readCapturedAsUtf8String(): String? {
            capturedStream.flush()
            val captureOutput: String? = capturedStream.toString(java.nio.charset.StandardCharsets.UTF_8)
            capturedStream.reset()
            return captureOutput
        }

        @Throws(java.lang.Exception::class)
        override fun close() {
            restore.close()
        }

        companion object {
            /** Wraps the standard System streams and WorkerIO instance  */
            @kotlin.jvm.JvmStatic
            fun capture(): WorkerIO {
                // Save the original streams
                val originalInputStream: java.io.InputStream? = java.lang.System.`in`
                val originalOutputStream: PrintStream? = java.lang.System.out
                val originalErrorStream: PrintStream? = java.lang.System.err

                // Replace the original streams with our own instances
                val capturedStream: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
                val outputBuffer: PrintStream = PrintStream(capturedStream, true)
                val byteArrayInputStream: ByteArrayInputStream = ByteArrayInputStream(ByteArray(0))
                java.lang.System.setIn(byteArrayInputStream)
                java.lang.System.setOut(outputBuffer)
                java.lang.System.setErr(outputBuffer)

                return WorkerIO(
                    originalInputStream,
                    originalOutputStream,
                    originalErrorStream,
                    capturedStream,
                    java.lang.AutoCloseable {
                        java.lang.System.setIn(originalInputStream)
                        java.lang.System.setOut(originalOutputStream)
                        java.lang.System.setErr(originalErrorStream)
                        outputBuffer.close()
                        byteArrayInputStream.close()
                    })
            }
        }
    }
}
