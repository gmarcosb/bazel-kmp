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
package com.google.devtools.build.lib.buildeventservice

import com.google.devtools.build.lib.analysis.test.TestConfiguration.TestOptions

/**
 * Module responsible for the Build Event Transport (BEP) and Build Event Service (BES)
 * functionality.
 */
abstract class BuildEventServiceModule<OptionsT : BuildEventServiceOptions?>
    : BlazeModule() {
    private var bepOptions: BuildEventProtocolOptions? = null
    private var authTlsOptions: AuthAndTLSOptions? = null
    private var besStreamOptions: BuildEventStreamOptions? = null
    private var uiUsesColor = false
    private var isRunsPerTestOverTheLimit = false
    private var uploaderFactoryToCleanup: BuildEventArtifactUploaderFactory? = null

    private var buildEventOutputStreamFactory: BuildEventOutputStreamFactory? = null

    /**
     * Holds the close futures for the upload of each transport with timeouts attached to them using
     * [.constructCloseFuturesMapWithTimeouts] obtained from [ ][BuildEventTransport.getTimeout].
     */
    private var closeFuturesWithTimeoutsMap: com.google.common.collect.ImmutableMap<BuildEventTransport, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>> =
        com.google.common.collect.ImmutableMap.of<BuildEventTransport?, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>()

    /**
     * Holds the half-close futures for the upload of each transport with timeouts attached to them
     * using [.constructCloseFuturesMapWithTimeouts] obtained from [ ][BuildEventTransport.getTimeout].
     * 
     * 
     * The completion of the half-close indicates that the client has sent all of the data to the
     * server and is just waiting for acknowledgement. The client must still keep the data buffered
     * locally in case acknowledgement fails.
     */
    private var halfCloseFuturesWithTimeoutsMap: com.google.common.collect.ImmutableMap<BuildEventTransport, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>> =
        com.google.common.collect.ImmutableMap.of<BuildEventTransport?, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>()

    // TODO(lpino): Use Optional instead of @Nullable for the members below.
    private var outErr: OutErr? = null
    private var bepTransports: com.google.common.collect.ImmutableSet<BuildEventTransport?>? = null
    private var buildRequestId: String? = null
    private var invocationId: String? = null
    private var reporter: com.google.devtools.build.lib.events.Reporter? = null
    private var streamer: BuildEventStreamer? = null
    private var connectivityProvider: ConnectivityStatusProvider? = null
    protected var besOptions: OptionsT? = null

    /** Defines format of the build event file.  */
    internal enum class BuildEventFileType {
        TEXT,
        JSON,
        BINARY
    }

    protected fun reportCommandLineError(
        commandLineReporter: com.google.devtools.build.lib.events.EventHandler,
        exception: java.lang.Exception
    ) {
        // Don't hide unchecked exceptions as part of the error reporting.
        com.google.common.base.Throwables.throwIfUnchecked(exception)
        commandLineReporter.handle(com.google.devtools.build.lib.events.Event.error(exception.getMessage()))
    }

    protected open val maxWaitForPreviousInvocation: java.time.Duration?
        /** Maximum duration Bazel waits for the previous invocation to finish before cancelling it.  */
        get() = java.time.Duration.ofSeconds(5)

    /** Report errors in the command line and possibly fail the build.  */
    private fun reportError(
        commandLineReporter: com.google.devtools.build.lib.events.EventHandler,
        moduleEnvironment: ModuleEnvironment,
        msg: String?,
        exception: java.lang.Exception,
        besCode: BuildProgress.Code?
    ) {
        // Don't hide unchecked exceptions as part of the error reporting.
        com.google.common.base.Throwables.throwIfUnchecked(exception)

        logger.atSevere().withCause(exception).log("%s", msg)
        reportCommandLineError(commandLineReporter, exception)
        moduleEnvironment.exit(createAbruptExitException(exception, msg, besCode))
    }

    val commonCommandOptions: Iterable<java.lang.Class<out com.google.devtools.common.options.OptionsBase>>
        get() = com.google.common.collect.ImmutableList.of<E?>(
            optionsClass(),
            AuthAndTLSOptions::class.java,
            BuildEventStreamOptions::class.java,
            BuildEventProtocolOptions::class.java
        )

    // Resets the maps tracking the state of closing/half-closing BES transports.
    private fun resetPendingUploads() {
        closeFuturesWithTimeoutsMap =
            com.google.common.collect.ImmutableMap.of<BuildEventTransport?, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>()
        halfCloseFuturesWithTimeoutsMap =
            com.google.common.collect.ImmutableMap.of<BuildEventTransport?, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>()
    }

    // Cancels and interrupts any in-flight threads closing BES transports, then resets the maps
    // tracking in-flight close operations.
    private fun cancelAndResetPendingUploads() {
        closeFuturesWithTimeoutsMap
            .values()
            .forEach(java.util.function.Consumer { closeFuture: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>? ->
                closeFuture.cancel( /* mayInterruptIfRunning= */true)
            })
        resetPendingUploads()
    }

    private fun removeFromPendingUploads(
        transportFutures: MutableMap<BuildEventTransport?, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>
    ) {
        transportFutures
            .values()
            .forEach(java.util.function.Consumer { closeFuture: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>? ->
                closeFuture.cancel( /* mayInterruptIfRunning= */true)
            })
        TODO(
            """
            |Cannot convert element
            |With text:
            |closeFuturesWithTimeoutsMap =
            |        closeFuturesWithTimeoutsMap.entrySet().stream()
            |            .filter(entry -> !transportFutures.containsKey(entry.getKey()))
            |            .collect(<Entry<BuildEventTransport, ListenableFuture<Void>>, BuildEventTransport, ListenableFuture<Void>>toImmutableMap(Entry::getKey, Entry::getValue)
            """.trimMargin()
        )

        TODO(
            """
            |Cannot convert element
            |With text:
            |halfCloseFuturesWithTimeoutsMap =
            |        halfCloseFuturesWithTimeoutsMap.entrySet().stream()
            |            .filter(entry -> !transportFutures.containsKey(entry.getKey()))
            |            .collect(<Entry<BuildEventTransport, ListenableFuture<Void>>, BuildEventTransport, ListenableFuture<Void>>toImmutableMap(Entry::getKey, Entry::getValue)
            """.trimMargin()
        )
    }

    private fun waitForPreviousInvocation(isShutdown: Boolean) {
        if (closeFuturesWithTimeoutsMap.isEmpty()) {
            return
        }

        val status: ConnectivityStatus = connectivityProvider.getStatus(CONNECTIVITY_CACHE_KEY)
        if (status.status != ConnectivityStatus.Status.OK) {
            reporter.handle(
                com.google.devtools.build.lib.events.Event.info(
                    java.lang.String.format(
                        "The Build Event Protocol encountered a connectivity problem: %s. Cancelling"
                                + " previous background uploads",
                        status
                    )
                )
            )
            cancelAndResetPendingUploads()
            return
        }

        val waitingFutureMap: com.google.common.collect.ImmutableMap<BuildEventTransport?, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>? =
            closeFuturesWithTimeoutsMap.entrySet().stream()
                .map<AbstractMap.SimpleEntry<BuildEventTransport?, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>?>(
                    java.util.function.Function { entry: MutableMap.MutableEntry<BuildEventTransport, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>>? ->
                        val transport: BuildEventTransport = entry.getKey()
                        val closeFuture: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
                            entry.getValue()
                        var future: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>? = closeFuture
                        if (transport.getBesUploadMode() == BesUploadMode.FULLY_ASYNC) {
                            future =
                                if (isShutdown) closeFuture else halfCloseFuturesWithTimeoutsMap.get(transport)
                            if (future == null) {
                                future = closeFuture
                            }
                        }
                        AbstractMap.SimpleEntry<BuildEventTransport?, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>(
                            transport,
                            future
                        )
                    })
        TODO(
            """
            |Cannot convert element
            |With text:
            |collect(<SimpleEntry<BuildEventTransport, ListenableFuture<Void>>, BuildEventTransport, ListenableFuture<Void>>toImmutableMap(Entry::getKey, Entry::getValue)
            """.trimMargin()
        )

        val cancelCloseFutures: com.google.common.collect.ImmutableMap<BuildEventTransport, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>> =
            closeFuturesWithTimeoutsMap.entrySet().stream()
                .filter(
                    java.util.function.Predicate { entry: MutableMap.MutableEntry<BuildEventTransport, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>? ->
                        val transport: BuildEventTransport = entry.getKey()
                        transport.getBesUploadMode() != BesUploadMode.FULLY_ASYNC
                    })
        TODO(
            """
            |Cannot convert element
            |With text:
            |collect(<Entry<BuildEventTransport, ListenableFuture<Void>>, BuildEventTransport, ListenableFuture<Void>>toImmutableMap(Entry::getKey, Entry::getValue)
            """.trimMargin()
        )


        val stopwatch: com.google.common.base.Stopwatch = com.google.common.base.Stopwatch.createStarted()
        try {
            // TODO(b/234994611): It would be better to report before we wait, but the current
            //  infrastructure does not support that. At least we can report it afterwards.
            com.google.common.util.concurrent.Uninterruptibles.getUninterruptibly<MutableList<java.lang.Void?>?>(
                com.google.common.util.concurrent.Futures.allAsList<java.lang.Void?>(waitingFutureMap.values()),
                this.maxWaitForPreviousInvocation.toMillis(),
                TimeUnit.MILLISECONDS
            )
            val waitedMillis: Long = stopwatch.elapsed().toMillis()
            if (waitedMillis > 100) {
                reporter.handle(
                    com.google.devtools.build.lib.events.Event.info(
                        java.lang.String.format(
                            "Waited for the background upload of the Build Event Protocol for "
                                    + "%d.%03d seconds.",
                            waitedMillis / 1000, waitedMillis % 1000
                        )
                    )
                )
            }
        } catch (exception: java.util.concurrent.TimeoutException) {
            val waitedMillis: Long = stopwatch.elapsed().toMillis()
            val msg: String? =
                java.lang.String.format(
                    ("The background upload of the Build Event Protocol for the previous invocation "
                            + "failed to complete in %d.%03d seconds. "
                            + "Cancelling and starting a new invocation..."),
                    waitedMillis / 1000, waitedMillis % 1000
                )
            reporter.handle(com.google.devtools.build.lib.events.Event.warn(msg))
            logger.atWarning().withCause(exception).log("%s", msg)
            cancelCloseFutures = closeFuturesWithTimeoutsMap
        } catch (e: ExecutionException) {
            val msg: String?
            // Futures.withTimeout wraps the TimeoutException in an ExecutionException when the future
            // times out.
            if (isTimeoutException(e)) {
                msg =
                    ("The background upload of the Build Event Protocol for the previous invocation "
                            + "failed due to a network timeout. Ignoring the failure and starting a new "
                            + "invocation...")
            } else {
                msg =
                    java.lang.String.format(
                        ("The background upload of the Build Event Protocol for the previous invocation "
                                + "failed with the following exception: '%s'. "
                                + "Ignoring the failure and starting a new invocation..."),
                        e.getMessage()
                    )
            }
            reporter.handle(com.google.devtools.build.lib.events.Event.warn(msg))
            logger.atWarning().withCause(e).log("%s", msg)
            cancelCloseFutures = closeFuturesWithTimeoutsMap
        } finally {
            cancelCloseFutures
                .values()
                .forEach(java.util.function.Consumer { closeFuture: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>? ->
                    closeFuture.cancel( /* mayInterruptIfRunning= */true)
                })
            resetPendingUploads()
        }
    }

    @Throws(AbruptExitException::class)
    override fun beforeCommand(cmdEnv: CommandEnvironment) {
        this.invocationId = cmdEnv.getCommandId().toString()
        this.buildRequestId = cmdEnv.getBuildRequestId()
        this.reporter = cmdEnv.getReporter()

        this.connectivityProvider =
            com.google.common.base.Preconditions.checkNotNull<ConnectivityStatusProvider?>(
                cmdEnv.getRuntime().getBlazeModule<ConnectivityStatusProvider?>(ConnectivityStatusProvider::class.java),
                "No ConnectivityStatusProvider found in modules list"
            )

        val parsingResult: com.google.devtools.common.options.OptionsParsingResult = cmdEnv.getOptions()
        this.besOptions = com.google.common.base.Preconditions.checkNotNull<OptionsT?>(
            parsingResult.getOptions<OptionsT?>(optionsClass())
        )
        this.bepOptions =
            com.google.common.base.Preconditions.checkNotNull<BuildEventProtocolOptions>(
                parsingResult.getOptions<BuildEventProtocolOptions?>(
                    BuildEventProtocolOptions::class.java
                )
            )
        this.authTlsOptions =
            com.google.common.base.Preconditions.checkNotNull<T?>(parsingResult.getOptions<O?>(AuthAndTLSOptions::class.java))
        this.besStreamOptions =
            com.google.common.base.Preconditions.checkNotNull<BuildEventStreamOptions?>(
                parsingResult.getOptions<BuildEventStreamOptions?>(
                    BuildEventStreamOptions::class.java
                )
            )
        this.isRunsPerTestOverTheLimit =
            parsingResult.getOptions<O?>(TestOptions::class.java) != null
                    && parsingResult.getOptions<O?>(TestOptions::class.java).getRunsPerTest().stream()
                .anyMatch(
                    { perLabelOptions ->
                        (java.lang.Integer.parseInt(
                            com.google.common.collect.Iterables.getOnlyElement<String?>(
                                perLabelOptions.options
                            )
                        )
                                > RUNS_PER_TEST_LIMIT)
                    })
        this.uiUsesColor =
            com.google.common.base.Preconditions.checkNotNull<UiOptions?>(parsingResult.getOptions<UiOptions?>(UiOptions::class.java))
                .useColor()

        val status: ConnectivityStatus = connectivityProvider.getStatus(CONNECTIVITY_CACHE_KEY)
        val buildEventUploadStrategy: String? =
            if (status.status == ConnectivityStatus.Status.OK)
                this.bepOptions.getBuildEventUploadStrategy()
            else
                "local"

        buildEventOutputStreamFactory = createBuildEventOutputStreamFactory(cmdEnv)
        val artifactGroupNamer: CountingArtifactGroupNamer = CountingArtifactGroupNamer()

        // We need to wait for the previous invocation before we check the list of allowed commands to
        // allow completing previous runs using BES, for example:
        //   bazel build (..run with async BES..)
        //   bazel info <-- Doesn't run with BES unless we wait before checking {@code allowedCommands}.
        val commandIsShutdown = "shutdown" == cmdEnv.getCommandName()
        waitForPreviousInvocation(commandIsShutdown)
        if (commandIsShutdown && uploaderFactoryToCleanup != null) {
            uploaderFactoryToCleanup.shutdown()
        }

        if (!allowedCommands(besOptions)!!.contains(cmdEnv.getCommandName())) {
            // Exit early if the running command isn't supported.
            return
        }

        val uploaderFactory: BuildEventArtifactUploaderFactory =
            cmdEnv
                .getRuntime()
                .getBuildEventArtifactUploaderFactoryMap()
                .select(buildEventUploadStrategy)
        val uploaderSupplier =
            ThrowingBuildEventArtifactUploaderSupplier(java.util.concurrent.Callable { uploaderFactory.create(cmdEnv) })
        this.uploaderFactoryToCleanup = uploaderFactory

        try {
            bepTransports = createBepTransports(cmdEnv, uploaderSupplier, artifactGroupNamer)
        } catch (e: IOException) {
            cmdEnv
                .getBlazeModuleEnvironment()
                .exit(
                    createAbruptExitException(
                        e,
                        "Could not create BEP transports.",
                        BuildProgress.Code.BES_INITIALIZATION_ERROR
                    )
                )
            return
        }
        if (bepTransports.isEmpty()) {
            // Exit early if there are no transports to stream to. However, report that the set of
            // transports has been determined so that interested parties always get this event if there
            // was no error during setting up the transports.
            reporter.post(AnnounceBuildEventTransportsEvent(bepTransports))
            return
        }

        if (bepOptions.getPublishTargetSummary()) {
            cmdEnv
                .getEventBus()
                .register(
                    TargetSummaryPublisher(
                        cmdEnv.getEventBus(),
                        com.google.common.base.Supplier { cmdEnv.withMergedAnalysisAndExecutionSourceOfTruth() })
                )
        }

        streamer =
            com.google.devtools.build.lib.runtime.BuildEventStreamer.Builder()
                .buildEventTransports(bepTransports)
                .besStreamOptions(besStreamOptions)
                .outputGroupFileModes(bepOptions.getOutputGroupFileModesMapping())
                .publishTargetSummaries(bepOptions.getPublishTargetSummary())
                .artifactGroupNamer(artifactGroupNamer)
                .oomMessage(
                    parsingResult.getOptions<CommonCommandOptions?>(CommonCommandOptions::class.java).getOomMessage()
                )
                .build()

        cmdEnv.getEventBus().register(streamer)
        registerOutAndErrOutputStreams()

        // This event should probably be posted in a more general place (e.g. {@link BuildTool};
        // however, so far the BES module is the only module that requires extra work after the build
        // so we post it here until it's needed for other modules.
        reporter.post(AnnounceBuildEventTransportsEvent(bepTransports))
    }

    private fun registerOutAndErrOutputStreams() {
        val bufferSize: Int = besOptions.getBesOuterrBufferSize()
        val chunkSize: Int = besOptions.getBesOuterrChunkSize()
        val out: SynchronizedOutputStream =
            SynchronizedOutputStream(bufferSize, chunkSize,  /* isStderr= */false)
        val err: SynchronizedOutputStream =
            SynchronizedOutputStream(bufferSize, chunkSize,  /* isStderr= */true)

        this.outErr = OutErr.create(out, err)
        streamer.registerOutErrProvider(
            object : OutErrProvider() {
                val out: Iterable<String?>?
                    get() = out.readAndReset()

                val err: Iterable<String?>?
                    get() = err.readAndReset()
            })
        err.registerStreamer(streamer)
        out.registerStreamer(streamer)
    }

    val outputListener: OutErr?
        get() = outErr

    private fun forceShutdownBuildEventStreamer(reason: AbortReason?) {
        streamer.closeOnAbort(reason)
        closeFuturesWithTimeoutsMap =
            constructCloseFuturesMapWithTimeouts(streamer.getCloseFuturesMap())
        try {
            logger.atInfo().log("Closing pending build event transports")
            val besClosedFuture: com.google.common.util.concurrent.ListenableFuture<MutableList<java.lang.Void?>?> =
                com.google.common.util.concurrent.Futures.allAsList<java.lang.Void?>(closeFuturesWithTimeoutsMap.values())
            if (reason === AbortReason.OUT_OF_MEMORY) {
                // GC thrashing during severe OOMs may prevent future completion, so don't wait forever.
                // We do want to wait in case this is a "benign" OOM - a brief high-water-mark - because
                // then we can preserve that information in the BEP being uploaded to BES.
                besClosedFuture.get(besOptions.getBesOomFinishUploadTimeout().toMillis(), TimeUnit.MILLISECONDS)
            } else {
                com.google.common.util.concurrent.Uninterruptibles.getUninterruptibly<MutableList<java.lang.Void?>?>(
                    besClosedFuture
                )
            }
        } catch (e: ExecutionException) {
            // TimeoutException and InterruptedException only thrown while crashing with OUT_OF_MEMORY.
            logger.atSevere().withCause(e).log("Failed to close a build event transport")
        } catch (e: java.util.concurrent.TimeoutException) {
            logger.atSevere().withCause(e).log("Failed to close a build event transport")
        } catch (e: java.lang.InterruptedException) {
            logger.atSevere().withCause(e).log("Failed to close a build event transport")
        } finally {
            cancelAndResetPendingUploads()
        }
    }

    override fun blazeShutdownOnCrash(exitCode: DetailedExitCode) {
        if (streamer != null) {
            logger.atWarning().log("Attempting to close BES streamer on crash")
            forceShutdownBuildEventStreamer(
                if (exitCode.getExitCode() == ExitCode.OOM_ERROR)
                    AbortReason.OUT_OF_MEMORY
                else
                    AbortReason.INTERNAL
            )
            uploaderFactoryToCleanup.shutdown()
        }
    }

    override fun blazeShutdown() {
        if (closeFuturesWithTimeoutsMap.isEmpty()) {
            return
        }

        try {
            com.google.common.util.concurrent.Uninterruptibles.getUninterruptibly<MutableList<java.lang.Void?>?>(
                com.google.common.util.concurrent.Futures.allAsList<java.lang.Void?>(closeFuturesWithTimeoutsMap.values()),
                this.maxWaitForPreviousInvocation.toSeconds(),
                TimeUnit.SECONDS
            )
        } catch (exception: java.util.concurrent.TimeoutException) {
            logger.atWarning().withCause(exception).log(
                "Encountered Exception when closing BEP transports in Blaze's shutting down sequence"
            )
        } catch (exception: ExecutionException) {
            logger.atWarning().withCause(exception).log(
                "Encountered Exception when closing BEP transports in Blaze's shutting down sequence"
            )
        } finally {
            cancelAndResetPendingUploads()
            if (uploaderFactoryToCleanup != null) {
                uploaderFactoryToCleanup.shutdown()
            }
        }
    }

    @Throws(AbruptExitException::class)
    private fun waitForBuildEventTransportsToClose(
        transportFutures: MutableMap<BuildEventTransport?, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>
    ) {
        val executor: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor(
                com.google.common.util.concurrent.ThreadFactoryBuilder().setNameFormat("bes-notify-ui-%d").build()
            )
        try {
            // Notify the UI handler when a transport finished closing.
            transportFutures.forEach(
                java.util.function.BiConsumer { bepTransport: BuildEventTransport?, closeFuture: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>? ->
                    closeFuture.addListener(
                        java.lang.Runnable {
                            reporter.post(BuildEventTransportClosedEvent(bepTransport))
                        },
                        executor
                    )
                })

            val invocationId = this.invocationId
            com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils.loggedAndCustomReceiver(
                "waiting for BES close for invocation " + invocationId,
                java.time.Duration.ZERO,  // Log all BES close times, regardless of duration.
                com.google.devtools.build.lib.profiler.AutoProfiler.ElapsedTimeReceiver { elapsedTimeNanos: Long ->
                    reporter.post(
                        BuildEventServiceUploadCompleteEvent(
                            java.time.Duration.ofNanos(elapsedTimeNanos)
                        )
                    )
                }).use { p ->
                com.google.common.util.concurrent.Uninterruptibles.getUninterruptibly<MutableList<java.lang.Void?>?>(
                    com.google.common.util.concurrent.Futures.allAsList<java.lang.Void?>(
                        transportFutures.values()
                    )
                )
            }
        } catch (e: CancellationException) {
            // This is expected if the upload needs to be cancelled for some reason, e.g. an error
            // interrupting the build.
        } catch (e: ExecutionException) {
            // Futures.withTimeout wraps the TimeoutException in an ExecutionException when the future
            // times out.
            if (isTimeoutException(e)) {
                throw createAbruptExitException(
                    e,
                    "The Build Event Protocol upload timed out.",
                    BuildProgress.Code.BES_UPLOAD_TIMEOUT_ERROR
                )
            }

            com.google.common.base.Throwables.throwIfInstanceOf<AbruptExitException?>(
                e.getCause(),
                AbruptExitException::class.java
            )
            throw java.lang.RuntimeException(
                java.lang.String.format(
                    "Unexpected Exception '%s' when closing BEP transports, this is a bug.",
                    e.getCause().getMessage()
                ),
                e
            )
        } finally {
            removeFromPendingUploads(transportFutures)
            executor.shutdown()
        }
    }

    @Throws(AbruptExitException::class)
    private fun closeBepTransports() {
        closeFuturesWithTimeoutsMap =
            constructCloseFuturesMapWithTimeouts(streamer.getCloseFuturesMap())
        halfCloseFuturesWithTimeoutsMap =
            constructCloseFuturesMapWithTimeouts(streamer.getHalfClosedMap())
        val blockingTransportFutures: MutableMap<BuildEventTransport?, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?> =
            HashMap<BuildEventTransport?, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>()
        for (entry in closeFuturesWithTimeoutsMap.entrySet()) {
            val bepTransport: BuildEventTransport = entry.getKey()
            val besUploadModeIsSynchronous =
                bepTransport.getBesUploadMode() == BesUploadMode.WAIT_FOR_UPLOAD_COMPLETE
            if (!bepTransport.mayBeSlow() || besUploadModeIsSynchronous) {
                blockingTransportFutures.put(bepTransport, entry.getValue())
            } else {
                // When running asynchronously notify the UI immediately since we won't wait for the
                // uploads to close.
                reporter.post(BuildEventTransportClosedEvent(bepTransport))
            }
        }
        if (!blockingTransportFutures.isEmpty()) {
            waitForBuildEventTransportsToClose(blockingTransportFutures)
        }
    }

    @Throws(AbruptExitException::class)
    override fun afterCommand() {
        if (streamer != null) {
            if (!streamer.isClosed()) {
                // This should not occur, but close with an internal error if a {@link BuildEventStreamer}
                // bug manifests as an unclosed streamer.
                logger.atWarning().log("Attempting to close BES streamer after command")
                reporter.handle(com.google.devtools.build.lib.events.Event.warn("BES was not properly closed"))
                forceShutdownBuildEventStreamer(AbortReason.INTERNAL)
            }

            closeBepTransports()

            if (!com.google.common.base.Strings.isNullOrEmpty(besOptions.getBesBackend())) {
                constructAndMaybeReportInvocationIdUrl()
            } else if (!bepTransports.isEmpty()) {
                reporter.handle(com.google.devtools.build.lib.events.Event.info("Build Event Protocol files produced successfully."))
            }
        }

        // besStreamOptions can be null if we are crashing. Don't crash here too.
        if (besStreamOptions != null && !besStreamOptions.getKeepBackendConnections()) {
            clearBesClient()
        } else if (besStreamOptions == null) {
            BugReport.sendNonFatalBugReport(
                java.lang.NullPointerException("besStreamOptions null: in a crash?")
            )
        }
    }

    override fun commandComplete() {
        this.outErr = null
        this.bepTransports = null
        this.invocationId = null
        this.buildRequestId = null
        this.reporter = null
        this.streamer = null
        this.buildEventOutputStreamFactory = null
    }

    private fun constructAndMaybeReportInvocationIdUrl() {
        if (!this.invocationIdPrefix.isEmpty()) {
            val msg: java.lang.StringBuilder = java.lang.StringBuilder()
            msg.append("Streaming build results to: ")
            if (uiUsesColor) {
                msg.append(String(AnsiTerminal.Color.CYAN.getEscapeSeq(), java.nio.charset.StandardCharsets.US_ASCII))
            }
            msg.append(this.invocationIdPrefix)
            msg.append(invocationId)
            if (uiUsesColor) {
                msg.append(
                    String(
                        AnsiTerminal.Color.DEFAULT.getEscapeSeq(),
                        java.nio.charset.StandardCharsets.US_ASCII
                    )
                )
            }

            reporter.handle(com.google.devtools.build.lib.events.Event.info(msg.toString()))
        }
    }

    private fun constructAndMaybeReportBuildRequestIdUrl() {
        if (!this.buildRequestIdPrefix.isEmpty()) {
            reporter.handle(
                com.google.devtools.build.lib.events.Event.info(
                    ("See "
                            + this.buildRequestIdPrefix
                            + buildRequestId
                            + " for more information about your request.")
                )
            )
        }
    }

    private fun logIds() {
        logger.atInfo().log(
            "Streaming Build Event Protocol to '%s' with build_request_id: '%s'"
                    + " and invocation_id: '%s'",
            besOptions.getBesBackend(), buildRequestId, invocationId
        )
    }

    @Throws(IOException::class)
    private fun createBesTransport(
        cmdEnv: CommandEnvironment,
        uploaderSupplier: ThrowingBuildEventArtifactUploaderSupplier,
        artifactGroupNamer: CountingArtifactGroupNamer?
    ): BuildEventServiceTransport? {
        if (com.google.common.base.Strings.isNullOrEmpty(besOptions.getBesBackend())) {
            clearBesClient()
            return null
        }

        if (isRunsPerTestOverTheLimit) {
            val msg: String? =
                java.lang.String.format(
                    "The value of --runs_per_test is bigger than %d and it will produce build events "
                            + "that are too big for the Build Event Service to handle.",
                    RUNS_PER_TEST_LIMIT
                )
            reportError(
                reporter,
                cmdEnv.getBlazeModuleEnvironment(),
                msg,
                com.google.devtools.common.options.OptionsParsingException(msg),
                BuildProgress.Code.BES_RUNS_PER_TEST_LIMIT_UNSUPPORTED
            )
            return null
        }

        logIds()

        val status: ConnectivityStatus = connectivityProvider.getStatus(CONNECTIVITY_CACHE_KEY)
        if (status.status != ConnectivityStatus.Status.OK) {
            clearBesClient()
            val message: String? =
                java.lang.String.format(
                    "Build Event Service uploads disabled due to a connectivity problem: %s", status
                )
            reporter.handle(com.google.devtools.build.lib.events.Event.warn(message))
            logger.atWarning().log("%s", message)
            return null
        }

        val besClient: BuildEventServiceClient?
        try {
            besClient = getBesClient(cmdEnv, besOptions, authTlsOptions)
        } catch (e: IOException) {
            reportError(
                reporter,
                cmdEnv.getBlazeModuleEnvironment(),
                e.getMessage(),
                e,
                BuildProgress.Code.BES_INITIALIZATION_ERROR
            )
            return null
        } catch (e: com.google.devtools.common.options.OptionsParsingException) {
            reportError(
                reporter,
                cmdEnv.getBlazeModuleEnvironment(),
                e.getMessage(),
                e,
                BuildProgress.Code.BES_INITIALIZATION_ERROR
            )
            return null
        }

        val commandContext: CommandContext =
            CommandContext.Companion.builder()
                .setBuildId(buildRequestId)
                .setInvocationId(invocationId)
                .setAttemptNumber(cmdEnv.getAttemptNumber())
                .setKeywords(
                    getBesKeywords(
                        cmdEnv.getCommandName(),
                        besOptions,
                        cmdEnv.getRuntime().getStartupOptionsProvider()
                    )
                )
                .setProjectId(besOptions.getInstanceName())
                .setCheckPrecedingLifecycleEvents(besOptions.getBesCheckPrecedingLifecycleEvents())
                .build()

        return com.google.devtools.build.lib.buildeventservice.BuildEventServiceTransport.Builder()
            .localFileUploader(uploaderSupplier.get())
            .besClient(besClient)
            .besOptions(besOptions)
            .artifactGroupNamer(artifactGroupNamer)
            .bepOptions(bepOptions)
            .clock(cmdEnv.getRuntime().getClock())
            .eventBus(cmdEnv.getEventBus())
            .commandContext(commandContext)
            .commandStartTime(Instant.ofEpochMilli(cmdEnv.getCommandStartTime()))
            .build()
    }

    /**
     * Returns the JSON type registry, used to resolve `Any` type names at serialization time.
     * 
     * 
     * Intended to be overridden by custom build tools with a subclassed [ ] to add additional Any types to be produced.
     */
    protected fun makeJsonTypeRegistry(): TypeRegistry {
        return TypeRegistry.newBuilder().add(SpawnExec.getDescriptor()).build()
    }

    @Throws(IOException::class)
    private fun createBepTransports(
        cmdEnv: CommandEnvironment,
        uploaderSupplier: ThrowingBuildEventArtifactUploaderSupplier,
        artifactGroupNamer: CountingArtifactGroupNamer?
    ): com.google.common.collect.ImmutableSet<BuildEventTransport?> {
        val bepTransportsBuilder: com.google.common.collect.ImmutableSet.Builder<BuildEventTransport?> =
            com.google.common.collect.ImmutableSet.Builder<BuildEventTransport?>()

        if (!com.google.common.base.Strings.isNullOrEmpty(besStreamOptions.getBuildEventTextFile())) {
            try {
                val bepTextOutputStream: BufferedOutputStream? =
                    buildEventOutputStreamFactory!!.create(
                        BuildEventFileType.TEXT, besStreamOptions.getBuildEventTextFile()
                    )
                val localFileUploader: BuildEventArtifactUploader? =
                    if (besStreamOptions.getBuildEventTextFilePathConversion())
                        uploaderSupplier.get()
                    else
                        LocalFilesArtifactUploader()
                bepTransportsBuilder.add(
                    TextFormatFileTransport(
                        bepTextOutputStream,
                        bepOptions,
                        localFileUploader,
                        artifactGroupNamer,
                        besStreamOptions.getBuildEventTextFileUploadMode()
                    )
                )
            } catch (exception: IOException) {
                // TODO(b/125216340): Consider making this a warning instead of an error once the
                //  associated bug has been resolved.
                reportError(
                    reporter,
                    cmdEnv.getBlazeModuleEnvironment(),
                    ("Unable to write to '"
                            + besStreamOptions.getBuildEventTextFile()
                            + "'. Omitting --build_event_text_file."),
                    exception,
                    BuildProgress.Code.BES_LOCAL_WRITE_ERROR
                )
            }
        }

        if (!com.google.common.base.Strings.isNullOrEmpty(besStreamOptions.getBuildEventBinaryFile())) {
            try {
                val bepBinaryOutputStream: BufferedOutputStream? =
                    buildEventOutputStreamFactory!!.create(
                        BuildEventFileType.BINARY, besStreamOptions.getBuildEventBinaryFile()
                    )
                val localFileUploader: BuildEventArtifactUploader? =
                    if (besStreamOptions.getBuildEventBinaryFilePathConversion())
                        uploaderSupplier.get()
                    else
                        LocalFilesArtifactUploader()
                bepTransportsBuilder.add(
                    BinaryFormatFileTransport(
                        bepBinaryOutputStream,
                        bepOptions,
                        localFileUploader,
                        artifactGroupNamer,
                        besStreamOptions.getBuildEventBinaryFileUploadMode()
                    )
                )
            } catch (exception: IOException) {
                // TODO(b/125216340): Consider making this a warning instead of an error once the
                //  associated bug has been resolved.
                reportError(
                    reporter,
                    cmdEnv.getBlazeModuleEnvironment(),
                    ("Unable to write to '"
                            + besStreamOptions.getBuildEventBinaryFile()
                            + "'. Omitting --build_event_binary_file."),
                    exception,
                    BuildProgress.Code.BES_LOCAL_WRITE_ERROR
                )
            }
        }

        if (!com.google.common.base.Strings.isNullOrEmpty(besStreamOptions.getBuildEventJsonFile())) {
            try {
                val bepJsonOutputStream: BufferedOutputStream? =
                    buildEventOutputStreamFactory!!.create(
                        BuildEventFileType.JSON, besStreamOptions.getBuildEventJsonFile()
                    )
                val localFileUploader: BuildEventArtifactUploader? =
                    if (besStreamOptions.getBuildEventJsonFilePathConversion())
                        uploaderSupplier.get()
                    else
                        LocalFilesArtifactUploader()
                bepTransportsBuilder.add(
                    JsonFormatFileTransport(
                        bepJsonOutputStream,
                        bepOptions,
                        localFileUploader,
                        artifactGroupNamer,
                        makeJsonTypeRegistry(),
                        besStreamOptions.getBuildEventJsonFileUploadMode()
                    )
                )
            } catch (exception: IOException) {
                // TODO(b/125216340): Consider making this a warning instead of an error once the
                //  associated bug has been resolved.
                reportError(
                    reporter,
                    cmdEnv.getBlazeModuleEnvironment(),
                    ("Unable to write to '"
                            + besStreamOptions.getBuildEventJsonFile()
                            + "'. Omitting --build_event_json_file."),
                    exception,
                    BuildProgress.Code.BES_LOCAL_WRITE_ERROR
                )
            }
        }

        val besTransport: BuildEventServiceTransport? =
            createBesTransport(cmdEnv, uploaderSupplier, artifactGroupNamer)
        if (besTransport != null) {
            constructAndMaybeReportInvocationIdUrl()
            constructAndMaybeReportBuildRequestIdUrl()
            bepTransportsBuilder.add(besTransport)
        }

        return bepTransportsBuilder.build()
    }

    protected abstract fun optionsClass(): java.lang.Class<OptionsT?>?

    @Throws(IOException::class, com.google.devtools.common.options.OptionsParsingException::class)
    protected abstract fun getBesClient(
        env: CommandEnvironment?, besOptions: OptionsT?, authAndTLSOptions: AuthAndTLSOptions?
    ): BuildEventServiceClient?

    protected abstract fun clearBesClient()

    protected abstract fun allowedCommands(besOptions: OptionsT?): MutableSet<String?>?

    @com.google.common.annotations.VisibleForTesting
    fun setBuildEventOutputStreamFactory(factory: BuildEventOutputStreamFactory?) {
        this.buildEventOutputStreamFactory = factory
    }

    /** Returns the set of keywords to be sent to the Build Event Service.  */
    protected abstract fun getBesKeywords(
        commandName: String?,
        besOptions: OptionsT?,
        startupOptionsProvider: com.google.devtools.common.options.OptionsParsingResult?
    ): com.google.common.collect.ImmutableSet<String?>?

    /** Returns the prefix used when printing the invocation ID in the command line.  */
    protected abstract val invocationIdPrefix: String?

    /** Returns theprefix used when printing the build request ID in the command line.  */
    protected abstract val buildRequestIdPrefix: String?

    // TODO(b/115961387): This method shouldn't exist. It only does because some tests are relying on
    //  the transport creation logic of this module directly.
    @com.google.common.annotations.VisibleForTesting
    fun getBepTransports(): com.google.common.collect.ImmutableSet<BuildEventTransport?>? {
        return bepTransports
    }

    private class ThrowingBuildEventArtifactUploaderSupplier(callable: java.util.concurrent.Callable<BuildEventArtifactUploader?>) {
        private val callable: java.util.concurrent.Callable<BuildEventArtifactUploader?>
        private var memoizedValue: BuildEventArtifactUploader? = null
        private var exception: IOException? = null

        init {
            this.callable = callable
        }

        @Throws(IOException::class)
        fun get(): BuildEventArtifactUploader? {
            val needsInitialization = memoizedValue == null
            if (needsInitialization && exception == null) {
                try {
                    memoizedValue = callable.call()
                } catch (e: IOException) {
                    exception = e
                } catch (e: java.lang.Exception) {
                    com.google.common.base.Throwables.throwIfUnchecked(e)
                    throw java.lang.IllegalStateException(e)
                }
            }
            if (memoizedValue != null) {
                if (!needsInitialization) {
                    memoizedValue.retain()
                }
                return memoizedValue
            }
            throw exception
        }
    }

    @com.google.common.annotations.VisibleForTesting
    open fun createBuildEventOutputStreamFactory(env: CommandEnvironment): BuildEventOutputStreamFactory? {
        return BuildEventOutputStreamFactoryImpl(env)
    }

    @com.google.common.annotations.VisibleForTesting
    internal interface BuildEventOutputStreamFactory {
        @Throws(IOException::class)
        fun create(eventFileType: BuildEventFileType?, filePath: String?): BufferedOutputStream?
    }

    private class BuildEventOutputStreamFactoryImpl(cmdEnv: CommandEnvironment) : BuildEventOutputStreamFactory {
        private val cmdEnv: CommandEnvironment

        init {
            this.cmdEnv = cmdEnv
        }

        @Throws(IOException::class)
        override fun create(eventFileType: BuildEventFileType, filePath: String?): BufferedOutputStream {
            val buildEventFileName =
                when (eventFileType) {
                    BuildEventFileType.TEXT -> "build_event_text_file"
                    BuildEventFileType.BINARY -> "build_event_binary_file"
                    BuildEventFileType.JSON -> "build_event_json_file"
                }
            val output: InstrumentationOutput =
                cmdEnv
                    .getRuntime()
                    .getInstrumentationOutputFactory()
                    .createInstrumentationOutput(
                        buildEventFileName,
                        PathFragment.create(filePath),
                        DestinationRelativeTo.WORKSPACE_OR_HOME,
                        cmdEnv,
                        cmdEnv.getReporter(),  /* append= */
                        null,  /* internal= */
                        null,  /* createParent= */
                        true
                    )
            return BufferedOutputStream(output.createOutputStream())
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /**
         * TargetComplete BEP events scale with the value of --runs_per_tests, thus setting a very large
         * value for can result in BEP events that are too big for BES to handle.
         */
        @com.google.common.annotations.VisibleForTesting
        const val RUNS_PER_TEST_LIMIT: Int = 100000

        private const val CONNECTIVITY_CACHE_KEY = "BES"

        private fun isTimeoutException(e: ExecutionException): Boolean {
            return e.getCause() is java.util.concurrent.TimeoutException
        }

        private fun constructCloseFuturesMapWithTimeouts(
            bepTransportToCloseFuturesMap: com.google.common.collect.ImmutableMap<BuildEventTransport?, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>>
        ): com.google.common.collect.ImmutableMap<BuildEventTransport, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>> {
            val builder: com.google.common.collect.ImmutableMap.Builder<BuildEventTransport?, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?> =
                com.google.common.collect.ImmutableMap.builder<BuildEventTransport?, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>()

            bepTransportToCloseFuturesMap.forEach(
                java.util.function.BiConsumer { bepTransport: BuildEventTransport?, closeFuture: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> ->
                    val closeFutureWithTimeout: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>
                    if (bepTransport.getTimeout().isZero() || bepTransport.getTimeout().isNegative()) {
                        closeFutureWithTimeout = closeFuture
                    } else {
                        val timeoutExecutor: ScheduledExecutorService =
                            Executors.newSingleThreadScheduledExecutor(
                                com.google.common.util.concurrent.ThreadFactoryBuilder()
                                    .setNameFormat("bes-close-" + bepTransport.name() + "-%d")
                                    .build()
                            )

                        // Make sure to avoid propagating the cancellation to the enclosing future since
                        // we handle cancellation ourselves in this class.
                        // Futures.withTimeout may cancel the enclosing future when the timeout is
                        // reached.
                        val enclosingFuture: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
                            com.google.common.util.concurrent.Futures.nonCancellationPropagating<java.lang.Void?>(
                                closeFuture
                            )

                        val timeoutFuture: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
                            com.google.common.util.concurrent.Futures.withTimeout<java.lang.Void?>(
                                enclosingFuture,
                                bepTransport.getTimeout().toMillis(),
                                TimeUnit.MILLISECONDS,
                                timeoutExecutor
                            )
                        timeoutFuture.addListener(
                            java.lang.Runnable { timeoutExecutor.shutdown() },
                            com.google.common.util.concurrent.MoreExecutors.directExecutor()
                        )

                        // Cancellation is not propagated to the `closeFuture` for the reasons above. But in
                        // order to cancel the returned future by our explicit mechanism elsewhere in this
                        // class, we need to delegate the `cancel` to `closeFuture` so that cancellation
                        // from Futures.withTimeout is ignored and cancellation from our mechanism is properly
                        // handled.
                        closeFutureWithTimeout =
                            object :
                                com.google.common.util.concurrent.ForwardingListenableFuture.SimpleForwardingListenableFuture<java.lang.Void?>(
                                    timeoutFuture
                                ) {
                                override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
                                    return@forEach closeFuture.cancel(mayInterruptIfRunning)
                                }
                            }
                    }
                    builder.put(bepTransport, closeFutureWithTimeout)
                })

            return builder.buildOrThrow()
        }

        private fun createAbruptExitException(
            e: java.lang.Exception, message: String?, besCode: BuildProgress.Code?
        ): AbruptExitException {
            return AbruptExitException(
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(message + " " + e.getMessage())
                        .setBuildProgress(BuildProgress.newBuilder().setCode(besCode).build())
                        .build()
                ),
                e
            )
        }
    }
}
