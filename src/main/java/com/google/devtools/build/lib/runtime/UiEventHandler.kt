// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.actions.ActionCompletionEvent

/** Presents events to the user in the terminal.  */
class UiEventHandler(
    outErr: OutErr,
    options: UiOptions,
    private val quiet: Boolean,
    clock: com.google.devtools.build.lib.clock.Clock,
    eventBus: com.google.common.eventbus.EventBus?,
    workspacePathFragment: PathFragment?,
    skymeldMode: Boolean,
    newStatsSummary: Boolean
) : com.google.devtools.build.lib.events.EventHandler {
    private val cursorControl: Boolean
    private val clock: com.google.devtools.build.lib.clock.Clock
    private val eventBus: com.google.common.eventbus.EventBus
    private val terminal: AnsiTerminal
    private val debugAllEvents: Boolean
    private val stateTracker: UiStateTracker
    private val locationPrinter: LocationPrinter

    @kotlin.concurrent.Volatile
    private var showProgress: Boolean
    private val progressInTermTitle: Boolean
    private val showTimestamp: Boolean
    private val outErr: OutErr
    private val filteredEventKinds: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.events.EventKind?>
    private val progressRateLimitMillis: Long
    private val minimalUpdateInterval: Long
    private val dateShown: AtomicBoolean
    private var lastRefreshMillis: Long = 0
    private var mustRefreshAfterMillis: Long = 0
    private var numLinesProgressBar: Int

    @kotlin.concurrent.Volatile
    private var buildRunning = false
    private var progressBarNeedsRefresh = false

    @kotlin.concurrent.Volatile
    private var shutdown = false
    private val updateThread: AtomicReference<java.lang.Thread?>
    private val updateLock: java.util.concurrent.locks.Lock
    private var stdoutLineBuffer: java.io.ByteArrayOutputStream
    private var stderrLineBuffer: java.io.ByteArrayOutputStream

    private val maxStdoutErrBytes: Int
    private val terminalWidth: Int

    /**
     * An output stream that wraps another output stream and that fully buffers writes until flushed.
     */
    private class FullyBufferedOutputStream(wrapped: java.io.OutputStream) : java.io.ByteArrayOutputStream() {
        /** The (possibly unbuffered) stream wrapped by this one.  */
        private val wrapped: java.io.OutputStream

        /**
         * Constructs a new fully-buffered output stream that wraps an unbuffered one.
         * 
         * @param wrapped the (possibly unbuffered) stream wrapped by this one
         */
        init {
            this.wrapped = wrapped
        }

        @Throws(IOException::class)
        override fun flush() {
            super.flush()
            try {
                writeTo(wrapped)
                wrapped.flush()
            } finally {
                // If we failed to write our current buffered contents to the output, there is not much
                // we can do because reporting an error would require another write, and that write would
                // probably fail. So, instead, we silently discard whatever was previously buffered in the
                // hopes that the data itself was what caused the problem.
                reset()
            }
        }
    }

    init {
        this.terminalWidth = (if (options.getTerminalColumns() > 0) options.getTerminalColumns() else 80)
        this.maxStdoutErrBytes = options.getMaxStdoutErrBytes()
        this.outErr =
            OutErr.create(
                FullyBufferedOutputStream(outErr.getOutputStream()),
                FullyBufferedOutputStream(outErr.getErrorStream())
            )
        this.cursorControl = options.useCursorControl()
        this.terminal = AnsiTerminal(this.outErr.getErrorStream())
        this.showProgress = options.getShowProgress()
        this.progressInTermTitle = options.getProgressInTermTitle() && options.useCursorControl()
        this.showTimestamp = options.getShowTimestamp()
        this.clock = clock
        this.eventBus = com.google.common.base.Preconditions.checkNotNull<com.google.common.eventbus.EventBus>(eventBus)
        this.debugAllEvents = options.getExperimentalUiDebugAllEvents()
        this.locationPrinter =
            LocationPrinter(options.getAttemptToPrintRelativePaths(), workspacePathFragment)

        // If we have cursor control, we try to fit in the terminal width to avoid having
        // to wrap the progress bar. We will wrap the progress bar to terminalWidth - 2
        // characters to avoid depending on knowing whether the underlying terminal does the
        // line feed already when reaching the last character of the line, or only once an
        // additional character is written. Another column is lost for the continuation character
        // in the wrapping process.
        if (skymeldMode) {
            this.stateTracker =
                if (this.cursorControl)
                    SkymeldUiStateTracker(clock,  /* targetWidth= */this.terminalWidth - 2)
                else
                    SkymeldUiStateTracker(clock)
        } else {
            this.stateTracker =
                if (this.cursorControl)
                    UiStateTracker(clock,  /* targetWidth= */this.terminalWidth - 2)
                else
                    UiStateTracker(clock)
        }
        this.stateTracker.setProgressSampleSize(options.getUiActionsShown())
        this.stateTracker.setNewStatsSummary(newStatsSummary)
        this.numLinesProgressBar = 0
        if (this.cursorControl) {
            this.progressRateLimitMillis = java.lang.Math.round(options.getShowProgressRateLimit() * 1000)
        } else {
            this.progressRateLimitMillis =
                java.lang.Math.max(
                    java.lang.Math.round(options.getShowProgressRateLimit() * 1000),
                    NO_CURSES_MINIMAL_PROGRESS_RATE_LIMIT
                )
        }
        this.minimalUpdateInterval =
            java.lang.Math.max(this.progressRateLimitMillis, MINIMAL_UPDATE_INTERVAL_MILLIS)
        this.stdoutLineBuffer = java.io.ByteArrayOutputStream()
        this.stderrLineBuffer = java.io.ByteArrayOutputStream()
        this.dateShown = AtomicBoolean()
        this.updateThread = AtomicReference<java.lang.Thread?>()
        this.updateLock = ReentrantLock()
        this.filteredEventKinds = options.getFilteredEventKinds()
        // The progress bar has not been updated yet.
        ignoreRefreshLimitOnce()
    }

    /**
     * Disables progress, clearing the progress bar if it is currently shown.
     * 
     * 
     * This can be used to temporarily suppress progress. Call [.enableProgress] to show
     * progress again.
     * 
     * 
     * If [UiOptions.showProgress] is false, or progress is already suppressed, returns
     * false. If progress was enabled before this call, returns true.
     */
    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    fun disableProgress(): Boolean {
        if (!showProgress) {
            return false
        }
        clearProgressBar()
        terminal.flush()
        showProgress = false
        return true
    }

    /**
     * Enables progress and writes the progress bar.
     * 
     * 
     * This is a no-op if progress is already enabled.
     */
    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    fun enableProgress() {
        if (showProgress) {
            return
        }
        showProgress = true
        addProgressBar()
        terminal.flush()
    }

    /**
     * Flush buffers for stdout and stderr. Return if either of them flushed a non-zero number of
     * symbols.
     */
    @kotlin.jvm.Synchronized
    private fun flushStdOutStdErrBuffers(): Boolean {
        var didFlush = false
        try {
            if (stdoutLineBuffer.size() > 0) {
                stdoutLineBuffer.writeTo(outErr.getOutputStream())
                outErr.getOutputStream().flush()
                // Re-initialize the stream not to retain allocated memory.
                stdoutLineBuffer = java.io.ByteArrayOutputStream()
                didFlush = true
            }
            if (stderrLineBuffer.size() > 0) {
                stderrLineBuffer.writeTo(outErr.getErrorStream())
                outErr.getErrorStream().flush()
                // Re-initialize the stream not to retain allocated memory.
                stderrLineBuffer = java.io.ByteArrayOutputStream()
                didFlush = true
            }
        } catch (e: IOException) {
            logger.atWarning().withCause(e).log("IO Error writing to output stream")
        }
        return didFlush
    }

    private fun maybeAddDate() {
        if (!showTimestamp || !buildRunning || dateShown.getAndSet(true)) {
            return
        }
        handle(
            com.google.devtools.build.lib.events.Event.info(
                "Current date is "
                        + DATE_FORMAT.format(
                    Instant.ofEpochMilli(clock.currentTimeMillis())
                        .atZone(ZoneId.systemDefault())
                )
            )
        )
    }

    /**
     * Helper function for [.handleInternal] to process events in debug mode, which causes all
     * events to be dumped to the terminal.
     * 
     * @param event the event to process
     * @param stdout the event's stdout, already read from disk to avoid blocking within the critical
     * section. Null if there is no stdout for this event or if it is empty.
     * @param stderr the event's stderr, already read from disk to avoid blocking within the critical
     * section. Null if there is no stderr for this event or if it is empty.
     */
    @Throws(IOException::class)
    private fun handleLockedDebug(
        event: com.google.devtools.build.lib.events.Event?,
        stdout: ByteArray?,
        stderr: ByteArray?
    ) {
        synchronized(this) {
            // Debugging only: show all events visible to the new UI.
            clearProgressBar()
            terminal.flush()
            val stream: java.io.OutputStream = outErr.getOutputStream()
            stream.write((event.toString() + "\n").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))
            if (stdout != null) {
                stream.write("... with STDOUT: ".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))
                stream.write(stdout)
                stream.write("\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))
            }
            if (stderr != null) {
                stream.write("... with STDERR: ".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))
                stream.write(stderr)
                stream.write("\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))
            }
            stream.flush()
            addProgressBar()
            terminal.flush()
        }
    }

    /**
     * Helper function for [.handleInternal] to process events in non-debug mode, which filters
     * out and pretty-prints some events.
     * 
     * @param event the event to process
     * @param stdout the event's stdout, already read from disk to avoid blocking within the critical
     * section. Null if there is no stdout for this event or if it is empty.
     * @param stderr the event's stderr, already read from disk to avoid blocking within the critical
     * section. Null if there is no stderr for this event or if it is empty.
     */
    @Throws(IOException::class)
    private fun handleLocked(
        event: com.google.devtools.build.lib.events.Event,
        stdout: ByteArray?,
        stderr: ByteArray?
    ) {
        synchronized(this) {
            maybeAddDate()
            when (event.getKind()) {
                com.google.devtools.build.lib.events.EventKind.STDOUT, com.google.devtools.build.lib.events.EventKind.STDERR -> {
                    val stream: java.io.OutputStream =
                        if (event.getKind() == com.google.devtools.build.lib.events.EventKind.STDOUT)
                            outErr.getOutputStream()
                        else
                            outErr.getErrorStream()
                    if (!buildRunning) {
                        stream.write(event.getMessageBytes())
                        stream.flush()
                    } else {
                        val clearedProgress =
                            writeToStream(stream, event.getKind(), event.getMessageBytes())
                        if (clearedProgress && showProgress && cursorControl) {
                            addProgressBar()
                        }
                        terminal.flush()
                    }
                }

                com.google.devtools.build.lib.events.EventKind.FATAL, com.google.devtools.build.lib.events.EventKind.ERROR, com.google.devtools.build.lib.events.EventKind.FAIL, com.google.devtools.build.lib.events.EventKind.WARNING, com.google.devtools.build.lib.events.EventKind.CANCELLED, com.google.devtools.build.lib.events.EventKind.INFO, com.google.devtools.build.lib.events.EventKind.DEBUG, com.google.devtools.build.lib.events.EventKind.SUBCOMMAND -> {
                    val incompleteLine: Boolean
                    if (showProgress && buildRunning) {
                        clearProgressBar()
                    }
                    incompleteLine = flushStdOutStdErrBuffers()
                    if (incompleteLine) {
                        crlf()
                    }
                    if (showTimestamp) {
                        terminal.writeString(
                            TIMESTAMP_FORMAT.format(
                                Instant.ofEpochMilli(clock.currentTimeMillis())
                                    .atZone(ZoneId.systemDefault())
                            )
                        )
                    }
                    setEventKindColor(event.getKind())
                    terminal.writeString(event.getKind().toString() + ": ")
                    terminal.resetTerminal()
                    incompleteLine = true
                    val location: net.starlark.java.syntax.Location? = event.getLocation()
                    if (location != null) {
                        terminal.writeString(locationPrinter.getLocationString(location) + ": ")
                    }
                    if (event.getMessage() != null) {
                        terminal.writeString(event.getMessage())
                        incompleteLine = !event.getMessage().endsWith("\n")
                    }
                    if (incompleteLine) {
                        crlf()
                    }
                    if (stderr != null) {
                        writeToStream(
                            outErr.getErrorStream(),
                            com.google.devtools.build.lib.events.EventKind.STDERR,
                            stderr
                        )
                        outErr.getErrorStream().flush()
                    }
                    if (stdout != null) {
                        writeToStream(
                            outErr.getOutputStream(),
                            com.google.devtools.build.lib.events.EventKind.STDOUT,
                            stdout
                        )
                        outErr.getOutputStream().flush()
                    }
                    if (showProgress && buildRunning && cursorControl) {
                        addProgressBar()
                    }
                    terminal.flush()
                }

                com.google.devtools.build.lib.events.EventKind.PROGRESS -> {
                    if (stateTracker.progressBarTimeDependent()) {
                        refresh()
                    }
                    if (stdout != null || stderr != null) {
                        BugReport.sendBugReport(
                            java.lang.IllegalStateException(
                                "stdout/stderr should not be present for this event " + event
                            )
                        )
                    }
                }

                com.google.devtools.build.lib.events.EventKind.START, com.google.devtools.build.lib.events.EventKind.FINISH, com.google.devtools.build.lib.events.EventKind.PASS, com.google.devtools.build.lib.events.EventKind.TIMEOUT, com.google.devtools.build.lib.events.EventKind.DEPCHECKER -> if (stdout != null || stderr != null) {
                    BugReport.sendBugReport(
                        java.lang.IllegalStateException(
                            "stdout/stderr should not be present for this event " + event
                        )
                    )
                }
            }
        }
    }

    private fun getContentIfSmallEnough(
        name: String?,
        size: Long,
        getContent: java.util.function.Supplier<ByteArray?>,
        getPath: java.util.function.Supplier<String?>
    ): ByteArray? {
        if (size == 0L) {
            // Avoid any possible I/O when we know it'll be empty anyway.
            return null
        }

        if (size <= maxStdoutErrBytes) {
            return getContent.get()
        } else {
            return java.lang.String.format(
                "%s (%s) %d exceeds maximum size of --experimental_ui_max_stdouterr_bytes=%d bytes;"
                        + " skipping\n",
                name, getPath.get(), size, maxStdoutErrBytes
            )
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)
        }
    }

    private fun handleInternal(event: com.google.devtools.build.lib.events.Event) {
        val eventKind: com.google.devtools.build.lib.events.EventKind = event.getKind()
        if (quiet) {
            when (eventKind) {
                com.google.devtools.build.lib.events.EventKind.ERROR, com.google.devtools.build.lib.events.EventKind.FATAL, com.google.devtools.build.lib.events.EventKind.STDOUT, com.google.devtools.build.lib.events.EventKind.STDERR -> {}
                else -> {
                    return
                }
            }
        }

        if (filteredEventKinds.contains(eventKind)) {
            return
        }
        try {
            // stdout and stderr may be files. Buffer them in memory to avoid doing I/O in the critical
            // sections of handleLocked*, at the expense of having to cap their size to avoid using too
            // much memory.
            var stdout: ByteArray? = null
            var stderr: ByteArray? = null
            val processOutput: ProcessOutput? = event.getProcessOutput()
            if (processOutput != null) {
                stdout =
                    getContentIfSmallEnough(
                        "stdout",
                        processOutput.stdOutSize,
                        java.util.function.Supplier { processOutput.getStdOut() },
                        java.util.function.Supplier { processOutput.getStdOutPath() })
                stderr =
                    getContentIfSmallEnough(
                        "stderr",
                        processOutput.stdErrSize,
                        java.util.function.Supplier { processOutput.getStdErr() },
                        java.util.function.Supplier { processOutput.getStdErrPath() })
            }

            if (debugAllEvents) {
                handleLockedDebug(event, stdout, stderr)
            } else {
                handleLocked(event, stdout, stderr)
            }
        } catch (e: IOException) {
            logger.atWarning().withCause(e).log("IO Error writing to output stream")
        }
    }

    override fun handle(event: com.google.devtools.build.lib.events.Event) {
        if (!debugAllEvents && !showTimestamp && (event.getKind() == com.google.devtools.build.lib.events.EventKind.START || event.getKind() == com.google.devtools.build.lib.events.EventKind.FINISH || event.getKind() == com.google.devtools.build.lib.events.EventKind.PASS || event.getKind() == com.google.devtools.build.lib.events.EventKind.TIMEOUT || event.getKind() == com.google.devtools.build.lib.events.EventKind.DEPCHECKER)) {
            // Keep this in sync with the list of no-op event kinds in handleLocked above.
            return
        }

        // Ensure that default progress messages are not displayed after a FATAL event.
        if (event.getKind() == com.google.devtools.build.lib.events.EventKind.FATAL) {
            synchronized(this) {
                buildRunning = false
            }
            stopUpdateThread()
        }

        handleInternal(event)
    }

    @Throws(IOException::class)
    private fun writeToStream(
        stream: java.io.OutputStream,
        eventKind: com.google.devtools.build.lib.events.EventKind?,
        message: ByteArray
    ): Boolean {
        val eolIndex: Int = com.google.common.primitives.Bytes.lastIndexOf(message, '\n'.code.toByte())
        val outLineBuffer: java.io.ByteArrayOutputStream =
            if (eventKind == com.google.devtools.build.lib.events.EventKind.STDOUT) stdoutLineBuffer else stderrLineBuffer
        if (eolIndex < 0) {
            outLineBuffer.write(message)
            return false
        }

        clearProgressBar()
        terminal.flush()

        // Write the buffer so far + the rest of the line (including newline).
        outLineBuffer.writeTo(stream)
        outLineBuffer.reset()

        stream.write(message, 0, eolIndex + 1)
        stream.flush()

        outLineBuffer.write(message, eolIndex + 1, message.size - eolIndex - 1)
        return true
    }

    @Throws(IOException::class)
    private fun setEventKindColor(kind: com.google.devtools.build.lib.events.EventKind) {
        when (kind) {
            com.google.devtools.build.lib.events.EventKind.FATAL, com.google.devtools.build.lib.events.EventKind.ERROR, com.google.devtools.build.lib.events.EventKind.FAIL -> {
                terminal.setTextColor(AnsiTerminal.Color.RED)
                terminal.textBold()
            }

            com.google.devtools.build.lib.events.EventKind.WARNING, com.google.devtools.build.lib.events.EventKind.CANCELLED -> terminal.setTextColor(
                AnsiTerminal.Color.MAGENTA
            )

            com.google.devtools.build.lib.events.EventKind.INFO -> terminal.setTextColor(AnsiTerminal.Color.GREEN)
            com.google.devtools.build.lib.events.EventKind.DEBUG -> terminal.setTextColor(AnsiTerminal.Color.YELLOW)
            com.google.devtools.build.lib.events.EventKind.SUBCOMMAND -> terminal.setTextColor(AnsiTerminal.Color.BLUE)
            else -> terminal.resetTerminal()
        }
    }

    @com.google.common.eventbus.Subscribe
    fun mainRepoMappingComputationStarted(event: MainRepoMappingComputationStartingEvent?) {
        synchronized(this) {
            buildRunning = true
        }
        maybeAddDate()
        stateTracker.mainRepoMappingComputationStarted()
        // As a new phase started, inform immediately.
        ignoreRefreshLimitOnce()
        refresh()
        startUpdateThread()
    }

    @com.google.common.eventbus.Subscribe
    fun buildStarted(event: BuildStartingEvent?) {
        maybeAddDate()
        stateTracker.buildStarted()
        // As a new phase started, inform immediately.
        ignoreRefreshLimitOnce()
        refresh()
    }

    @com.google.common.eventbus.Subscribe
    fun loadingStarted(event: LoadingPhaseStartedEvent) {
        maybeAddDate()
        stateTracker.loadingStarted(event)
        // As a new phase started, inform immediately.
        ignoreRefreshLimitOnce()
        refresh()
        startUpdateThread()
    }

    @com.google.common.eventbus.Subscribe
    fun configurationStarted(event: ConfigurationPhaseStartedEvent) {
        maybeAddDate()
        stateTracker.configurationStarted(event)
        // As a new phase started, inform immediately.
        ignoreRefreshLimitOnce()
        refresh()
        startUpdateThread()
    }

    @com.google.common.eventbus.Subscribe
    fun loadingComplete(event: LoadingPhaseCompleteEvent) {
        stateTracker.loadingComplete(event)
        refresh()
    }

    @com.google.common.eventbus.Subscribe
    @kotlin.jvm.Synchronized
    fun analysisComplete(event: AnalysisPhaseCompleteEvent?) {
        val analysisSummary: String? = stateTracker.analysisComplete()
        handle(com.google.devtools.build.lib.events.Event.info(analysisSummary))
    }

    @com.google.common.eventbus.Subscribe
    fun executionPhaseStarted(event: SomeExecutionStartedEvent) {
        if (event.countedInExecutionTime) {
            stateTracker.executionPhaseStarted()
            refresh()
        }
    }

    @com.google.common.eventbus.Subscribe
    fun progressReceiverAvailable(event: ExecutionProgressReceiverAvailableEvent) {
        stateTracker.progressReceiverAvailable(event)
        // As this is the first time we have a progress message, update immediately.
        ignoreRefreshLimitOnce()
        startUpdateThread()
    }

    @com.google.common.eventbus.Subscribe
    fun buildComplete(event: BuildCompleteEvent) {
        // The final progress bar will flow into the scroll-back buffer, to if treat
        // it as an event and add a timestamp, if events are supposed to have a timestamp.
        var done = false
        synchronized(this) {
            handleInternal(stateTracker.buildComplete(event))
            ignoreRefreshLimitOnce()

            // After a build has completed, only stop updating the UI if there is no more activities.
            if (!stateTracker.hasActivities()) {
                buildRunning = false
                done = true
            }

            // Only refresh after we have determined whether we need to keep the progress bar up.
            refresh()
        }
        if (done) {
            stopUpdateThread()
            flushStdOutStdErrBuffers()
        }
    }

    private fun completeBuild() {
        synchronized(this) {
            if (!buildRunning) {
                return
            }
            buildRunning = false
            // Have to set this, otherwise there's a lingering "checking cached actions" message for the
            // `mod` command, which doesn't even run any actions.
            stateTracker.setBuildComplete()
        }
        stopUpdateThread()
        synchronized(this) {
            try {
                // If a progress bar is currently present, clean it and redraw it.
                val progressBarPresent = numLinesProgressBar > 0
                if (progressBarPresent) {
                    clearProgressBar()
                }
                terminal.flush()
                val incompleteLine = flushStdOutStdErrBuffers()
                if (incompleteLine) {
                    crlf()
                }
                if (progressBarPresent) {
                    addProgressBar()
                }
                terminal.flush()
            } catch (e: IOException) {
                logger.atWarning().withCause(e).log("IO Error writing to output stream")
            }
        }
    }

    @com.google.common.eventbus.Subscribe
    fun packageLocatorCreated(packageLocator: PathPackageLocator) {
        locationPrinter.packageLocatorCreated(packageLocator)
    }

    @com.google.common.eventbus.Subscribe
    fun noBuild(event: NoBuildEvent) {
        if (event.showProgress()) {
            synchronized(this) {
                buildRunning = true
            }
            return
        }
        completeBuild()
    }

    @com.google.common.eventbus.Subscribe
    fun noBuildFinished(event: NoBuildRequestFinishedEvent?) {
        completeBuild()
    }

    @com.google.common.eventbus.Subscribe
    fun afterCommand(event: AfterCommandEvent?) {
        synchronized(this) {
            buildRunning = false
        }
        completeBuild()
        try {
            flushStdOutStdErrBuffers()
            terminal.resetTerminal()
            terminal.flush()
        } catch (e: IOException) {
            logger.atWarning().withCause(e).log("IO Error writing to user terminal")
        }
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun downloadProgress(event: FetchProgress) {
        maybeAddDate()
        stateTracker.downloadProgress(event)
        if (!event.isFinished) {
            refresh()
        } else {
            checkActivities()
        }
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun actionStarted(event: ActionStartedEvent) {
        stateTracker.actionStarted(event)
        refresh()
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun scanningAction(event: ScanningActionEvent) {
        stateTracker.scanningAction(event)
        refresh()
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun stopScanningAction(event: StoppedScanningActionEvent) {
        stateTracker.stopScanningAction(event)
        refresh()
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun checkingActionCache(event: CachingActionEvent) {
        stateTracker.cachingAction(event)
        refresh()
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun schedulingAction(event: SchedulingActionEvent) {
        stateTracker.schedulingAction(event)
        refresh()
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun runningAction(event: RunningActionEvent) {
        stateTracker.runningAction(event)
        refresh()
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun actionProgress(event: ActionProgressEvent) {
        stateTracker.actionProgress(event)
        refreshSoon()
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun actionCompletion(event: ActionScanningCompletedEvent) {
        stateTracker.actionCompletion(event)
        refreshSoon()
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun actionCompletion(event: ActionCompletionEvent) {
        stateTracker.actionCompletion(event)
        refreshSoon()
    }

    @com.google.common.eventbus.Subscribe
    fun crash(event: CrashEvent?) {
        val inflightActions: InflightActionInfo = stateTracker.logAndGetInflightActions()
        eventBus.post(inflightActions)
    }

    private fun checkActivities() {
        if (stateTracker.hasActivities()) {
            refreshSoon()
        } else {
            stopUpdateThread()
            flushStdOutStdErrBuffers()
            ignoreRefreshLimitOnce()
            refresh()
        }
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun actionUploadStarted(event: ActionUploadStartedEvent?) {
        stateTracker.actionUploadStarted(event)
        refreshSoon()
    }

    @com.google.common.eventbus.Subscribe
    fun actionUploadFinished(event: ActionUploadFinishedEvent?) {
        stateTracker.actionUploadFinished(event)
        checkActivities()
    }

    @com.google.common.eventbus.Subscribe
    fun testFilteringComplete(event: TestFilteringCompleteEvent) {
        stateTracker.testFilteringComplete(event)
        refresh()
    }

    @com.google.common.eventbus.Subscribe
    fun singleTestAnalyzed(event: TestAnalyzedEvent) {
        stateTracker.singleTestAnalyzed(event)
        refreshSoon()
    }

    @com.google.common.eventbus.Subscribe
    @kotlin.jvm.Synchronized
    fun testSummary(summary: TestSummary) {
        stateTracker.testSummary(summary)
        if (testSummaryProvidesNewInformation(summary)) {
            // For failed test, write the failure to the scroll-back buffer immediately
            try {
                clearProgressBar()
                crlf()
                setEventKindColor(
                    if (summary.getStatus() === BlazeTestStatus.FLAKY) com.google.devtools.build.lib.events.EventKind.WARNING else com.google.devtools.build.lib.events.EventKind.ERROR
                )
                terminal.writeString(summary.getStatus().toString() + ": ")
                terminal.resetTerminal()
                terminal.writeString(summary.getLabel().toString())
                terminal.writeString(" (Summary)")
                crlf()
                for (logPath in summary.getFailedLogs()) {
                    terminal.writeString("      " + logPath.getPathString())
                    crlf()
                }
                if (showProgress && cursorControl) {
                    addProgressBar()
                }
                terminal.flush()
            } catch (e: IOException) {
                logger.atWarning().withCause(e).log("IO Error writing to output stream")
            }
        } else {
            refresh()
        }
    }

    @com.google.common.eventbus.Subscribe
    @kotlin.jvm.Synchronized
    fun buildEventTransportsAnnounced(event: AnnounceBuildEventTransportsEvent) {
        stateTracker.buildEventTransportsAnnounced(event)
        if (debugAllEvents) {
            val message: java.lang.StringBuilder = java.lang.StringBuilder("Transports announced:")
            for (transport in event.transports()) {
                message.append(" ").append(transport.name())
            }
            this.handle(com.google.devtools.build.lib.events.Event.info(message.toString()))
        }
    }

    @com.google.common.eventbus.Subscribe
    fun buildEventTransportClosed(event: BuildEventTransportClosedEvent) {
        stateTracker.buildEventTransportClosed(event)
        if (debugAllEvents) {
            this.handle(
                com.google.devtools.build.lib.events.Event.info(
                    "Transport " + event.transport().name() + " closed"
                )
            )
        }

        checkActivities()
    }

    private fun refresh() {
        if (showProgress) {
            progressBarNeedsRefresh = true
            doRefresh()
        }
    }

    private fun doRefresh(fromUpdateThread: Boolean = false) {
        if (!buildRunning) {
            return
        }
        val nowMillis: Long = clock.currentTimeMillis()
        if (lastRefreshMillis + progressRateLimitMillis < nowMillis) {
            if (updateLock.tryLock()) {
                try {
                    synchronized(this) {
                        if (showProgress && (progressBarNeedsRefresh || timeBasedRefresh())) {
                            progressBarNeedsRefresh = false
                            clearProgressBar()
                            addProgressBar()
                            terminal.flush()
                        }
                    }
                } catch (e: IOException) {
                    logger.atWarning().withCause(e).log("IO Error writing to output stream")
                } finally {
                    updateLock.unlock()
                }
            }
        } else {
            // We skipped an update due to rate limiting. If this however, turned
            // out to be the last update for a long while, we need to show it in a
            // timely manner, as it best describes the current state.
            if (!fromUpdateThread) {
                startUpdateThread()
            }
        }
    }

    private fun refreshSoon() {
        // Schedule an update of the progress bar in the near future, unless there is already
        // a future update scheduled.
        val nowMillis: Long = clock.currentTimeMillis()
        if (mustRefreshAfterMillis <= lastRefreshMillis) {
            mustRefreshAfterMillis = java.lang.Math.max(nowMillis + 1, lastRefreshMillis + minimalUpdateInterval)
        }
        startUpdateThread()
    }

    /** Decide whether the progress bar should be redrawn only for the reason that time has passed.  */
    @kotlin.jvm.Synchronized
    private fun timeBasedRefresh(): Boolean {
        if (!stateTracker.progressBarTimeDependent()) {
            return false
        }
        // Don't do more updates than are requested through events when there is no cursor control.
        if (!cursorControl) {
            return false
        }
        val nowMillis: Long = clock.currentTimeMillis()
        if (lastRefreshMillis < mustRefreshAfterMillis
            && mustRefreshAfterMillis < nowMillis + progressRateLimitMillis
        ) {
            // Within a small interval from now, an update is scheduled anyway,
            // so don't do a time-based update of the progress bar now, to avoid
            // updates too close to each other.
            return false
        }
        return lastRefreshMillis + SHORT_REFRESH_MILLIS < nowMillis
    }

    private fun ignoreRefreshLimitOnce() {
        // Set refresh time variables in a state such that the next progress bar
        // update will definitely be written out.
        lastRefreshMillis = clock.currentTimeMillis() - progressRateLimitMillis - 1
    }

    private fun startUpdateThread() {
        // Refuse to start an update thread once the build is complete; such a situation might
        // arise if the completion of the build is reported (shortly) before the completion of
        // the last action is reported.
        if (buildRunning && updateThread.get() == null) {
            val threadToStart: java.lang.Thread =
                java.lang.Thread(
                    java.lang.Runnable {
                        try {
                            while (!shutdown) {
                                java.lang.Thread.sleep(minimalUpdateInterval)
                                if (lastRefreshMillis < mustRefreshAfterMillis
                                    && mustRefreshAfterMillis < clock.currentTimeMillis()
                                ) {
                                    progressBarNeedsRefresh = true
                                }
                                doRefresh( /* fromUpdateThread= */true)
                            }
                        } catch (e: java.lang.InterruptedException) {
                            // Ignore
                        } catch (t: Throwable) {
                            // Do not block if a crash is already in progress. The thread that wins the crash
                            // reporting race needs to display a FATAL exception message, which waits for this
                            // thread to terminate in stopUpdateThread(). Blocking can lead to a deadlock.
                            BugReport.handleCrash(
                                Crash.from(t), CrashContext.haltOrReturnIfCrashInProgress()
                            )
                        }
                    },
                    "cli-update-thread"
                )
            if (updateThread.compareAndSet(null, threadToStart)) {
                threadToStart.start()
            }
        }
    }

    /**
     * Stop the update thread and wait for it to terminate. As the update thread, which is a separate
     * thread, might have to call a synchronized method between being interrupted and terminating, DO
     * NOT CALL from a SYNCHRONIZED block, as this will give the opportunity for dead locks.
     * 
     * 
     * If this is called from the updateThread itself, ignore the interrupt/join, as it is
     * hopefully handling a FATAL, and should be terminating anyway.
     */
    private fun stopUpdateThread() {
        shutdown = true
        val threadToWaitFor: java.lang.Thread? = updateThread.getAndSet(null)
        // we could be second to wait here, or be the current thread, which would hang
        if (threadToWaitFor != null && threadToWaitFor !== java.lang.Thread.currentThread()) {
            threadToWaitFor.interrupt()
            com.google.common.util.concurrent.Uninterruptibles.joinUninterruptibly(threadToWaitFor)
        }
    }

    @Throws(IOException::class)
    private fun clearProgressBar() {
        if (!cursorControl) {
            return
        }
        for (i in 0..<numLinesProgressBar) {
            terminal.cr()
            terminal.cursorUp(1)
            terminal.clearLine()
        }
        numLinesProgressBar = 0
    }

    /** Terminate the line in the way appropriate for the operating system.  */
    @Throws(IOException::class)
    private fun crlf() {
        terminal.writeString(java.lang.System.lineSeparator())
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    private fun addProgressBar() {
        if (quiet) {
            return
        }

        val countingTerminalWriter: LineCountingAnsiTerminalWriter =
            LineCountingAnsiTerminalWriter(terminal)
        var terminalWriter: AnsiTerminalWriter = countingTerminalWriter
        lastRefreshMillis = clock.currentTimeMillis()
        if (cursorControl) {
            terminalWriter = LineWrappingAnsiTerminalWriter(terminalWriter, terminalWidth - 1)
        }
        var timestamp: String? = null
        if (showTimestamp) {
            timestamp =
                TIMESTAMP_FORMAT.format(
                    Instant.ofEpochMilli(clock.currentTimeMillis()).atZone(ZoneId.systemDefault())
                )
        }
        if (stateTracker.hasActivities()) {
            stateTracker.writeProgressBar(terminalWriter,  /* shortVersion= */!cursorControl, timestamp)
            terminalWriter.newline()
        }
        numLinesProgressBar = countingTerminalWriter.getWrittenLines()
        if (progressInTermTitle) {
            val stringWriter: LoggingTerminalWriter = LoggingTerminalWriter(true)
            stateTracker.writeProgressBar(stringWriter, true)
            terminal.setTitle(stringWriter.getTranscript())
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /** Minimal time between scheduled updates  */
        private const val MINIMAL_UPDATE_INTERVAL_MILLIS = 200L

        /** Minimal rate limiting (in ms), if the progress bar cannot be updated in place  */
        private const val NO_CURSES_MINIMAL_PROGRESS_RATE_LIMIT = 1000L

        /** Periodic update interval of a time-dependent progress bar if it can be updated in place  */
        private const val SHORT_REFRESH_MILLIS = 1000L

        private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("(HH:mm:ss) ")
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        private val TEST_STATUS_TO_IGNORE_FOR_NEW_INFORMATION: com.google.common.collect.ImmutableSet<BlazeTestStatus?> =
            com.google.common.collect.Sets.immutableEnumSet<BlazeTestStatus?>(
                BlazeTestStatus.PASSED,
                BlazeTestStatus.FAILED_TO_BUILD,
                BlazeTestStatus.BLAZE_HALTED_BEFORE_TESTING,
                BlazeTestStatus.NO_STATUS
            )

        /**
         * Return true, if the test summary provides information that is both worth being shown in the
         * scroll-back buffer and new with respect to the alreay shown failure messages.
         */
        private fun testSummaryProvidesNewInformation(summary: TestSummary): Boolean {
            if (TEST_STATUS_TO_IGNORE_FOR_NEW_INFORMATION.contains(summary.getStatus())) {
                return false
            }
            if (summary.getStatus() === BlazeTestStatus.FAILED) {
                return summary.getFailedLogs().size() != 1
            }
            return true
        }
    }
}
