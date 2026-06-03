// Copyright 2021 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.buildeventservice.BuildEventServiceModule.RUNS_PER_TEST_LIMIT

/** Tests for [BazelBuildEventServiceModule].  */
@RunWith(TestParameterInjector::class)
class BazelBuildEventServiceModuleTest : BuildIntegrationTestCase() {
    private val fakeServerName = "fake server for " + javaClass
    private val buildEventService: DelayingPublishBuildEventService = DelayingPublishBuildEventService()
    private val serviceRegistry: MutableHandlerRegistry = MutableHandlerRegistry()
    private var fakeServer: io.grpc.Server? = null

    private var besModule: BazelBuildEventServiceModule? = null
    private var connectivityModule: BlazeModule = NoOpConnectivityModule()
    private val spawnController: SpawnController = SpawnController()

    @org.junit.Rule
    var tmpFolder: TemporaryFolder = TemporaryFolder()

    private var buildEventOutputStreamFactory: BuildEventOutputStreamFactory? = null

    override fun getConnectivityModule(): BlazeModule {
        return connectivityModule
    }

    @get:Throws(java.lang.Exception::class)
    val runtimeBuilder: BlazeRuntime.Builder
        get() = super.getRuntimeBuilder()
            .addBlazeModule(
                object : BlazeModule() {
                    public override fun beforeCommand(env: CommandEnvironment) {
                        this@BazelBuildEventServiceModuleTest.events.initExternal(env.getReporter())
                    }
                })
            .addBlazeModule(NoSpawnCacheModule())
            .addBlazeModule(CredentialModule())
            .addBlazeModule(PackageMetricsModule())
            .addBlazeModule(ControllableActionStrategyModule(spawnController, "standalone"))
            .addBlazeModule(
                object : BazelBuildEventServiceModule() {
                    @Throws(IOException::class)
                    protected override fun newGrpcChannel(config: BackendConfig): ManagedChannel? {
                        if (config.besBackend().equals("inprocess")) {
                            return InProcessChannelBuilder.forName(fakeServerName).build()
                        }
                        return super.newGrpcChannel(config)
                    }

                    protected val maxWaitForPreviousInvocation: java.time.Duration?
                        get() = WAIT_FOR_LAST_INVOCATION_TIMEOUT

                    public override fun createBuildEventOutputStreamFactory(
                        env: CommandEnvironment?
                    ): BuildEventOutputStreamFactory? {
                        return if (buildEventOutputStreamFactory == null)
                            super.createBuildEventOutputStreamFactory(env)
                        else
                            buildEventOutputStreamFactory
                    }
                })

    private var bepTransports: com.google.common.collect.ImmutableSet<BuildEventTransport>? = null
    private val besUploadCompleteEvents: MutableList<BuildEventServiceUploadCompleteEvent> =
        java.util.ArrayList<BuildEventServiceUploadCompleteEvent>()

    private inner class BepTransportLogger {
        @com.google.common.eventbus.Subscribe
        @Suppress("unused")
        fun transportsKnown(event: AnnounceBuildEventTransportsEvent?) {
            bepTransports = besModule.getBepTransports()
        }
    }

    private inner class BuildEventServiceUploadCompleteEventListener {
        @com.google.common.eventbus.Subscribe
        @Suppress("unused")
        fun onBuildEventServiceUploadComplete(event: BuildEventServiceUploadCompleteEvent?) {
            besUploadCompleteEvents.add(event)
        }
    }

    private fun getBepTransports(): com.google.common.collect.ImmutableSet<BuildEventTransport>? {
        return bepTransports
    }

    @Throws(java.lang.Exception::class)
    private fun runBuildWithOptions(vararg options: String?) {
        addOptions(*options)
        besModule = runtimeWrapper.getRuntime().getBlazeModule(BazelBuildEventServiceModule::class.java)
        if (buildEventOutputStreamFactory != null) {
            besModule.setBuildEventOutputStreamFactory(buildEventOutputStreamFactory)
        }
        runtimeWrapper.newCommand()
        runtimeWrapper.getSkyframeExecutor().getEventBus().register(BepTransportLogger())
        runtimeWrapper
            .getSkyframeExecutor()
            .getEventBus()
            .register(BuildEventServiceUploadCompleteEventListener())
        buildTarget()
    }

    @Throws(java.lang.Exception::class)
    private fun afterBuildCommand() {
        runtimeWrapper.newCommand()
    }

    override fun createUncaughtExceptionHandler(): java.lang.Thread.UncaughtExceptionHandler? {
        // Disable the crash handler since this test leaves runaway threads e.g. accessing shut down
        // fakeServer.
        return null
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        serviceRegistry.addService(
            ServerInterceptors.intercept(
                buildEventService, ServerHeadersInterceptor()
            )
        )
        fakeServer =
            InProcessServerBuilder.forName(fakeServerName)
                .fallbackHandlerRegistry(serviceRegistry)
                .directExecutor()
                .build()
                .start()
    }

    @org.junit.After
    @Throws(java.lang.Exception::class)
    fun tearDown() {
        fakeServer.shutdownNow()
        fakeServer.awaitTermination()
        spawnController.verifyAllShimsConsumed()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreatesStreamerForTextFormatFileTransport() {
        runBuildWithOptions("--build_event_text_file=" + tmpFolder.newFile().getAbsolutePath())
        Truth.assertThat(getBepTransports()).hasSize(1)
        assertThat(getBepTransports().asList().get(0)).isInstanceOf(TextFormatFileTransport::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreatesStreamerForBinaryFormatFileTransport() {
        runBuildWithOptions("--build_event_binary_file=" + tmpFolder.newFile().getAbsolutePath())
        Truth.assertThat(getBepTransports()).hasSize(1)
        assertThat(getBepTransports().asList().get(0)).isInstanceOf(BinaryFormatFileTransport::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreatesStreamerForJsonFormatFileTransport() {
        runBuildWithOptions("--build_event_json_file=" + tmpFolder.newFile().getAbsolutePath())
        Truth.assertThat(getBepTransports()).hasSize(1)
        assertThat(getBepTransports().asList().get(0)).isInstanceOf(JsonFormatFileTransport::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreatesStreamerForBesTransport() {
        runBuildWithOptions("--bes_backend=does.not.exist:1234")
        Truth.assertThat(getBepTransports()).hasSize(1)
        assertThat(getBepTransports().asList().get(0)).isInstanceOf(BuildEventServiceTransport::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRetryCount() {
        runBuildWithOptions(
            "--bes_backend=does.not.exist:1234", "--experimental_build_event_upload_max_retries=3"
        )
        afterBuildCommand()

        events.assertContainsError(
            "The Build Event Protocol upload failed: all 3 publishLifecycleEvent retry attempts"
                    + " failed"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConnectivityFailureDisablesBesStreaming() {
        class FailingConnectivityStatusProvider : BlazeModule(), ConnectivityStatusProvider {
            public override fun getStatus(service: String?): ConnectivityStatus {
                return ConnectivityStatus(
                    ConnectivityStatus.Status.NO_CREDENTIALS, "forced connectivity failure"
                )
            }
        }

        connectivityModule = FailingConnectivityStatusProvider()
        reinitializeAndPreserveOptions()
        addOptions("--bes_backend=does.not.exist:1234")
        addOptions("--spawn_strategy=standalone")
        runBuildWithOptions()
        Truth.assertThat(getBepTransports()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreatesStreamerForGrpcBesResultsUrl() {
        runBuildWithOptions(
            "--bes_backend=inprocess",
            "--bes_upload_mode=FULLY_ASYNC",
            "--bes_results_url=http://results-ui/"
        )

        Truth.assertThat(getBepTransports()).hasSize(1)
        assertThat(getBepTransports().asList().get(0)).isInstanceOf(BuildEventServiceTransport::class.java)
    }

    @org.junit.Test
    fun testCreatesStreamerForGrpcRunsPerTestTooHighDisablesStreaming() {
        val expected: AbruptExitException =
            org.junit.Assert.assertThrows<T>(
                AbruptExitException::class.java,
                org.junit.function.ThrowingRunnable {
                    runBuildWithOptions(
                        "--bes_backend=inprocess", "--runs_per_test=" + (RUNS_PER_TEST_LIMIT + 1)
                    )
                })
        assertThat(expected.getExitCode()).isEqualTo(ExitCode.COMMAND_LINE_ERROR)
        Truth.assertThat(getBepTransports()).isEmpty()
        assertContainsError("The value of --runs_per_test")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBeforeCommandGrpcReportsBesResultsUrl() {
        runBuildWithOptions(
            "--color=no",  // disable ANSI color sequences
            "--bes_backend=inprocess",
            "--bes_upload_mode=FULLY_ASYNC",
            "--bes_results_url=http://results-ui/"
        )
        events.assertContainsEventsInOrder(
            "Streaming build results to: http://results-ui/", "Found 0 targets", "Found 0 targets"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAfterCommandGrpcReportsBesResultsUrl() {
        runBuildWithOptions(
            "--color=no",  // disable ANSI color sequences
            "--bes_backend=inprocess",
            "--bes_upload_mode=FULLY_ASYNC",
            "--bes_results_url=http://results-ui/"
        )
        afterBuildCommand()

        events.assertContainsEventsInOrder(
            "Streaming build results to: http://results-ui/",
            "Found 0 targets",
            "Found 0 targets",
            "Streaming build results to: http://results-ui/",
            "Streaming build results to: http://results-ui/"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAfterCommand_waitForUploadComplete() {
        buildEventService.setDelayBeforeClosingStream(java.time.Duration.ZERO)
        runBuildWithOptions(
            "--bes_backend=inprocess",
            "--bes_upload_mode=WAIT_FOR_UPLOAD_COMPLETE",
            "--bes_timeout=5s"
        )
        afterBuildCommand()
        events.assertNoWarningsOrErrors()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAfterCommand_waitForUploadComplete_postsEvent() {
        buildEventService.setDelayBeforeClosingStream(java.time.Duration.ofMillis(100))
        runBuildWithOptions(
            "--bes_backend=inprocess",
            "--bes_upload_mode=WAIT_FOR_UPLOAD_COMPLETE",
            "--bes_timeout=5s"
        )
        afterBuildCommand()
        events.assertNoWarningsOrErrors()
        Truth.assertThat(besUploadCompleteEvents).hasSize(1)
        assertThat(besUploadCompleteEvents.get(0).duration()).isGreaterThan(java.time.Duration.ZERO)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAfterCommand_waitForUploadComplete_slowFullCloseError() {
        buildEventService.setDelayBeforeClosingStream(java.time.Duration.ofSeconds(10))
        runBuildWithOptions(
            "--bes_backend=inprocess",
            "--bes_upload_mode=WAIT_FOR_UPLOAD_COMPLETE",
            "--bes_timeout=5s"
        )
        val bepTransports: com.google.common.collect.ImmutableSet<BuildEventTransport>? = getBepTransports()
        Truth.assertThat(bepTransports).hasSize(1)
        afterBuildCommand()
        assertContainsError("The Build Event Protocol upload timed out")
        for (bepTransport in bepTransports) {
            assertThat(bepTransport.close().isDone()).isTrue()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAfterCommand_waitForUploadComplete_slowHalfCloseError() {
        buildEventService.setDelayBeforeHalfClosingStream(java.time.Duration.ofSeconds(10))
        runBuildWithOptions(
            "--bes_backend=inprocess",
            "--bes_upload_mode=WAIT_FOR_UPLOAD_COMPLETE",
            "--bes_timeout=5s"
        )
        afterBuildCommand()
        assertContainsError("The Build Event Protocol upload timed out")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAfterCommand_noWaitForUploadComplete() {
        buildEventService.setDelayBeforeClosingStream(java.time.Duration.ZERO)
        runBuildWithOptions("--bes_backend=inprocess", "--bes_upload_mode=NOWAIT_FOR_UPLOAD_COMPLETE")
        afterBuildCommand()
        events.assertNoWarningsOrErrors()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAfterCommand_noWaitForUploadComplete_slowFullCloseIgnored() {
        buildEventService.setDelayBeforeClosingStream(java.time.Duration.ofSeconds(10))
        runBuildWithOptions("--bes_backend=inprocess", "--bes_upload_mode=NOWAIT_FOR_UPLOAD_COMPLETE")
        afterBuildCommand()
        events.assertNoWarningsOrErrors()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAfterCommand_noWaitForUploadComplete_slowHalfCloseIgnored() {
        buildEventService.setDelayBeforeHalfClosingStream(java.time.Duration.ofSeconds(10))
        runBuildWithOptions("--bes_backend=inprocess", "--bes_upload_mode=NOWAIT_FOR_UPLOAD_COMPLETE")
        afterBuildCommand()
        events.assertNoWarningsOrErrors()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBeforeSecondCommand_noWaitForUploadComplete_slowFullCloseWarning() {
        buildEventService.setDelayBeforeClosingStream(java.time.Duration.ofSeconds(10))
        runBuildWithOptions("--bes_backend=inprocess", "--bes_upload_mode=NOWAIT_FOR_UPLOAD_COMPLETE")
        afterBuildCommand()
        buildTarget()
        events.assertContainsWarning(
            "The background upload of the Build Event Protocol for the previous "
                    + "invocation failed to complete in"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBeforeSecondCommand_noWaitForUploadComplete_slowHalfCloseWarning() {
        buildEventService.setDelayBeforeHalfClosingStream(java.time.Duration.ofSeconds(10))
        runBuildWithOptions("--bes_backend=inprocess", "--bes_upload_mode=NOWAIT_FOR_UPLOAD_COMPLETE")
        afterBuildCommand()
        buildTarget()
        events.assertContainsWarning(
            "The background upload of the Build Event Protocol for the previous "
                    + "invocation failed to complete in"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBeforeSecondCommand_noWaitForUploadComplete_besTimeout_slowFullCloseWarning() {
        buildEventService.setDelayBeforeClosingStream(java.time.Duration.ofSeconds(10))
        runBuildWithOptions(
            "--bes_backend=inprocess",
            "--bes_upload_mode=NOWAIT_FOR_UPLOAD_COMPLETE",
            "--bes_timeout=1s"
        )
        afterBuildCommand()
        buildTarget()
        events.assertContainsWarning(
            "The background upload of the Build Event Protocol for the previous "
                    + "invocation failed due to a network timeout"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBeforeSecondCommand_noWaitForUpload_besTimeout_slowHalfCloseWarning() {
        buildEventService.setDelayBeforeHalfClosingStream(java.time.Duration.ofSeconds(10))
        runBuildWithOptions(
            "--bes_backend=inprocess",
            "--bes_upload_mode=NOWAIT_FOR_UPLOAD_COMPLETE",
            "--bes_timeout=1s"
        )
        afterBuildCommand()
        buildTarget()
        events.assertContainsWarning(
            "The background upload of the Build Event Protocol for the previous "
                    + "invocation failed due to a network timeout"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAfterCommand_fullyAsync() {
        runBuildWithOptions("--bes_backend=inprocess", "--bes_upload_mode=FULLY_ASYNC")
        afterBuildCommand()
        events.assertNoWarningsOrErrors()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAfterCommand_buildEventFile_waitForUploadComplete(
        @TestParameter buildEventFileType: BuildEventFileType
    ) {
        val outRef: AtomicReference<DelayingCloseBufferedOutputStream?> =
            AtomicReference<DelayingCloseBufferedOutputStream?>(null)
        buildEventOutputStreamFactory =
            BuildEventOutputStreamFactory? { type, filePath ->
            val out =
                DelayingCloseBufferedOutputStream(
                    java.nio.file.Files.newOutputStream(Path.of(filePath)), java.time.Duration.ofSeconds(1)
                )
            outRef.set(out)
            out
        }
        buildEventService.setDelayBeforeClosingStream(java.time.Duration.ofSeconds(10))
        val file: java.io.File = tmpFolder.newFile()

        runBuildWithOptions(
            "--bes_backend=inprocess",
            "--bes_upload_mode=FULLY_ASYNC",
            "--bes_timeout=1s",
            getBuildEventFileFlag(buildEventFileType, file.getAbsolutePath()),
            getBuildEventFileUploadModeFlag(buildEventFileType, "wait_for_upload_complete")
        )
        afterBuildCommand()

        Truth.assertThat(outRef.get().isClosed()).isTrue()
        // Expect Bazel doesn't wait for uploading to bes_backend, otherwise there will be a timeout
        // error.
        events.assertNoWarningsOrErrors()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAfterCommand_fullyAsync_slowHalfCloseIgnored() {
        buildEventService.setDelayBeforeHalfClosingStream(java.time.Duration.ofSeconds(10))
        runBuildWithOptions("--bes_backend=inprocess", "--bes_upload_mode=FULLY_ASYNC")
        afterBuildCommand()
        events.assertNoWarningsOrErrors()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBeforeSecondCommand_fullyAsync_slowFullCloseIgnored() {
        buildEventService.setDelayBeforeClosingStream(java.time.Duration.ofSeconds(10))
        runBuildWithOptions("--bes_backend=inprocess", "--bes_upload_mode=FULLY_ASYNC")
        afterBuildCommand()
        buildTarget()
        events.assertNoWarningsOrErrors()
    }

    // TODO(b/246912214): Deflake this by fixing the threading model to match the upstream gRPC
    // changes in https://github.com/grpc/grpc-java/pull/9319 that affect InProcessTransport.
    @Ignore("b/246912214")
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBeforeSecondCommand_fullyAsync_slowHalfCloseWarning() {
        buildEventService.setDelayBeforeHalfClosingStream(java.time.Duration.ofSeconds(10))
        runBuildWithOptions("--bes_backend=inprocess", "--bes_upload_mode=FULLY_ASYNC")
        afterBuildCommand()
        buildTarget()
        events.assertContainsWarning(
            "The background upload of the Build Event Protocol for the previous "
                    + "invocation failed to complete in"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBeforeSecondCommand_fullyAsync_besTimeout_slowFullCloseIgnored() {
        buildEventService.setDelayBeforeClosingStream(java.time.Duration.ofSeconds(10))
        runBuildWithOptions(
            "--bes_backend=inprocess", "--bes_upload_mode=FULLY_ASYNC", "--bes_timeout=1s"
        )
        afterBuildCommand()
        buildTarget()
        events.assertNoWarningsOrErrors()
    }

    // TODO(b/246912214): Deflake this by fixing the threading model to match the upstream gRPC
    // changes in https://github.com/grpc/grpc-java/pull/9319 that affect InProcessTransport.
    @Ignore("b/246912214")
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBeforeSecondCommand_fullyAsync_besTimeout_slowHalfCloseWarning() {
        buildEventService.setDelayBeforeHalfClosingStream(java.time.Duration.ofSeconds(10))
        runBuildWithOptions(
            "--bes_backend=inprocess", "--bes_upload_mode=FULLY_ASYNC", "--bes_timeout=1s"
        )
        afterBuildCommand()
        buildTarget()
        events.assertContainsWarning(
            "The background upload of the Build Event Protocol for the previous "
                    + "invocation failed due to a network timeout."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAfterCommandStreamerIsClosedNoWarning() {
        runBuildWithOptions("--build_event_text_file=" + tmpFolder.newFile().getAbsolutePath())
        Truth.assertThat(getBepTransports()).hasSize(1)
        afterBuildCommand()
        events.assertNoWarningsOrErrors()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAfterCommand_waitForUploadComplete_retryableErrorEarlyInStream() {
        val numRetries = 3
        buildEventService.setErrorMessageAndCode("Boom8", io.grpc.Status.UNAVAILABLE)
        buildEventService.setErrorEarlyInStream(true)
        runBuildWithOptions(
            "--bes_backend=inprocess",
            "--bes_upload_mode=WAIT_FOR_UPLOAD_COMPLETE",
            "--build_event_upload_max_retries=" + numRetries
        )
        afterBuildCommand()
        events.assertContainsError(
            java.util.regex.Pattern.compile(
                "The Build Event Protocol upload failed: no publishBuildEvents retry attempts left:"
                        + " UNAVAILABLE: Boom8"
            )
        )
        Truth.assertThat(buildEventService.getRequestsReceivedCount()).isEqualTo(numRetries + 1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAfterCommand_waitForUploadComplete_permissionDeniedErrorEarlyInStream() {
        val numRetries = 3
        buildEventService.setErrorMessageAndCode("Boom15", io.grpc.Status.PERMISSION_DENIED)
        buildEventService.setErrorEarlyInStream(true)
        runBuildWithOptions(
            "--bes_backend=inprocess",
            "--bes_upload_mode=WAIT_FOR_UPLOAD_COMPLETE",
            "--build_event_upload_max_retries=" + numRetries
        )
        afterBuildCommand()
        events.assertContainsError(
            java.util.regex.Pattern.compile(
                "The Build Event Protocol upload failed: not retrying publishBuildEvents:"
                        + " PERMISSION_DENIED: Boom15"
            )
        )
        Truth.assertThat(buildEventService.getRequestsReceivedCount()).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAfterCommand_waitForUploadComplete_invalidArgumentErrorEarlyInStream() {
        val numRetries = 3
        buildEventService.setErrorMessageAndCode("Boom15", io.grpc.Status.INVALID_ARGUMENT)
        buildEventService.setErrorEarlyInStream(true)
        runBuildWithOptions(
            "--bes_backend=inprocess",
            "--bes_upload_mode=WAIT_FOR_UPLOAD_COMPLETE",
            "--build_event_upload_max_retries=" + numRetries
        )
        afterBuildCommand()
        events.assertContainsError(
            java.util.regex.Pattern.compile(
                "The Build Event Protocol upload failed: not retrying publishBuildEvents:"
                        + " INVALID_ARGUMENT: Boom15"
            )
        )
        Truth.assertThat(buildEventService.getRequestsReceivedCount()).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAfterCommand_waitForUploadComplete_errorOnComplete() {
        buildEventService.setErrorMessage("Boom1")
        runBuildWithOptions("--bes_backend=inprocess", "--bes_upload_mode=WAIT_FOR_UPLOAD_COMPLETE")
        afterBuildCommand()
        events.assertContainsError("The Build Event Protocol upload failed: DATA_LOSS: Boom1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAfterCommand_waitForUploadComplete_besTimeout_errorOnComplete() {
        buildEventService.setErrorMessage("Boom2")
        runBuildWithOptions(
            "--bes_backend=inprocess",
            "--bes_upload_mode=WAIT_FOR_UPLOAD_COMPLETE",
            "--bes_timeout=5s"
        )
        afterBuildCommand()
        events.assertContainsError("The Build Event Protocol upload failed: DATA_LOSS: Boom2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAfterCommand_noWaitForUploadComplete_errorOnComplete() {
        buildEventService.setErrorMessage("Boom3")
        runBuildWithOptions("--bes_backend=inprocess", "--bes_upload_mode=NOWAIT_FOR_UPLOAD_COMPLETE")
        afterBuildCommand()
        events.assertNoWarningsOrErrors()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBeforeSecondCommand_noWaitForUploadComplete_errorOnComplete() {
        buildEventService.setErrorMessage("Boom4")
        runBuildWithOptions("--bes_backend=inprocess", "--bes_upload_mode=NOWAIT_FOR_UPLOAD_COMPLETE")
        afterBuildCommand()
        buildTarget()
        events.assertContainsWarning("The Build Event Protocol upload failed: DATA_LOSS: Boom4")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAfterCommand_fullyAsync_errorOnComplete() {
        buildEventService.setErrorMessage("Boom5")
        runBuildWithOptions("--bes_backend=inprocess", "--bes_upload_mode=FULLY_ASYNC")
        afterBuildCommand()
        events.assertNoWarningsOrErrors()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBeforeSecondCommand_fullyAsync_errorOnComplete() {
        buildEventService.setErrorMessage("Boom6")
        runBuildWithOptions("--bes_backend=inprocess", "--bes_upload_mode=FULLY_ASYNC")
        afterBuildCommand()
        buildTarget()
        events.assertNoWarningsOrErrors()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreatesStreamerForAllTransports() {
        runBuildWithOptions(
            "--build_event_text_file=" + tmpFolder.newFile().getAbsolutePath(),
            "--build_event_binary_file=" + tmpFolder.newFile().getAbsolutePath(),
            "--build_event_json_file=" + tmpFolder.newFile().getAbsolutePath(),
            "--bes_backend=does.not.exist:1234"
        )

        Truth.assertThat(getBepTransports()).hasSize(4)
        assertThat(getBepTransports().asList().get(0)).isInstanceOf(TextFormatFileTransport::class.java)
        assertThat(getBepTransports().asList().get(1)).isInstanceOf(BinaryFormatFileTransport::class.java)
        assertThat(getBepTransports().asList().get(2)).isInstanceOf(JsonFormatFileTransport::class.java)
        assertThat(getBepTransports().asList().get(3)).isInstanceOf(BuildEventServiceTransport::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUploaderSharing() {
        runBuildWithOptions(
            "--build_event_text_file=" + tmpFolder.newFile().getAbsolutePath(),
            "--build_event_binary_file=" + tmpFolder.newFile().getAbsolutePath(),
            "--build_event_json_file=" + tmpFolder.newFile().getAbsolutePath(),
            "--bes_backend=does.not.exist:1234"
        )

        Truth.assertThat(getBepTransports()).hasSize(4)

        val uploader: BuildEventArtifactUploader? =
            com.google.common.collect.Iterables.getFirst<BuildEventTransport?>(getBepTransports(), null).uploader
        assertThat(uploader).isNotNull()
        for (transport in getBepTransports()) {
            assertThat(uploader).isSameInstanceAs(transport.uploader)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoesNotCreatesStreamerWithoutTransports() {
        runBuildWithOptions()
        Truth.assertThat(getBepTransports()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeywords() {
        runBuildWithOptions()
        val besOptions: BuildEventServiceOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(BuildEventServiceOptions::class.java)
        besOptions.setBesKeywords(com.google.common.collect.ImmutableList.of<E?>("keyword0", "keyword1", "keyword0"))
        besOptions.setBesSystemKeywords(
            com.google.common.collect.ImmutableList.of<E?>("sys_keyword0", "sys_keyword1", "sys_keyword0")
        )

        assertThat(besModule.getBesKeywords("build", besOptions, null))
            .containsExactly(
                "protocol_name=BEP",
                "command_name=build",
                "user_keyword=keyword0",
                "user_keyword=keyword1",
                "sys_keyword0",
                "sys_keyword1"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMakeGrpcMetadata() {
        runBuildWithOptions()
        val besOptions: BuildEventServiceOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(BuildEventServiceOptions::class.java)
        val authAndTLSOptions: AuthAndTLSOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(AuthAndTLSOptions::class.java)
        besOptions.besBackend = "bes-backend"
        besOptions.besProxy = "bes-proxy"
        besOptions.setBesHeaders(
            com.google.common.collect.ImmutableList.of<E?>(
                java.util.Map.entry<K?, V?>("key1", "val1"),
                java.util.Map.entry<K?, V?>("key2", "val2"),
                java.util.Map.entry<K?, V?>("key3", "val3"),
                java.util.Map.entry<K?, V?>("key1", "val4")
            )
        )
        val newConfig: BackendConfig? = BackendConfig.create(besOptions, authAndTLSOptions)

        val metadata: io.grpc.Metadata = BazelBuildEventServiceModule.makeGrpcMetadata(newConfig)
        Truth.assertThat(
            metadata.get<String?>(
                io.grpc.Metadata.Key.of<String?>(
                    "key1",
                    io.grpc.Metadata.ASCII_STRING_MARSHALLER
                )
            )
        )
            .isEqualTo("val4")
        Truth.assertThat(
            metadata.get<String?>(
                io.grpc.Metadata.Key.of<String?>(
                    "key2",
                    io.grpc.Metadata.ASCII_STRING_MARSHALLER
                )
            )
        )
            .isEqualTo("val2")
        Truth.assertThat(
            metadata.get<String?>(
                io.grpc.Metadata.Key.of<String?>(
                    "key3",
                    io.grpc.Metadata.ASCII_STRING_MARSHALLER
                )
            )
        )
            .isEqualTo("val3")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun oom_firstReportedViaHandleCrash() {
        testOom(
            java.lang.Runnable {
                val oom: java.lang.OutOfMemoryError = java.lang.OutOfMemoryError()
                // Simulates an OOM coming from GcThrashingDetector, which reports the error by calling
                // handleCrash. Uses keepAlive() to avoid exiting the JVM and aborting the test, then
                // throw the original oom to ensure control flow terminates.
                BugReport.handleCrash(Crash.from(oom), CrashContext.keepAlive())
                throw oom
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun oom_firstThrownFromSkyframe() {
        testOom(
            java.lang.Runnable {
                throw java.lang.OutOfMemoryError()
            })
    }

    @Throws(java.lang.Exception::class)
    private fun testOom(throwOom: java.lang.Runnable) {
        write("foo/BUILD", "genrule(name = 'gen', outs = ['gen.out'], cmd = 'touch $@')")
        val threwOom: AtomicBoolean = AtomicBoolean(false)
        getSkyframeExecutor()
            .getEvaluator()
            .injectGraphTransformerForTesting(
                NotifyingHelper.makeNotifyingTransformer( // To get the right configuration, some analysis has to already been done.
                    // We're only throwing OOM here for non shareable ActionLookupData to exclude
                    // workspace status actions, which in Skymeld mode can run without any analysis.
                    NotifyingHelper.Listener { key: SkyKey?, type: NotifyingHelper.EventType?, order: NotifyingHelper.Order?, context: Any? ->
                        if (key is ActionLookupData
                            && key.valueIsShareable()
                            && !threwOom.getAndSet(true)
                        ) {
                            throwOom.run()
                        }
                    })
            )
        val buildEventBinaryFile: java.io.File = tmpFolder.newFile()
        addOptions(
            "--build_event_binary_file=" + buildEventBinaryFile.getAbsolutePath(),
            "--oom_message=Please build fewer targets."
        )

        org.junit.Assert.assertThrows<java.lang.OutOfMemoryError?>(
            java.lang.OutOfMemoryError::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//foo:gen") })

        val buildEvents: MutableList<BuildEvent> = java.util.ArrayList<BuildEvent>()
        FileInputStream(buildEventBinaryFile).use { `in` ->
            var ev: BuildEvent?
            while ((BuildEvent.parseDelimitedFrom(`in`).also { ev = it }) != null) {
                buildEvents.add(ev)
            }
        }
        val expectedAbort: Aborted? =
            Aborted.newBuilder()
                .setReason(AbortReason.OUT_OF_MEMORY)
                .setDescription(BugReport.constructOomExitMessage("Please build fewer targets."))
                .build()
        Truth.assertThat(buildEvents)
            .ignoringFields(BuildEvent.LAST_MESSAGE_FIELD_NUMBER, BuildEvent.CHILDREN_FIELD_NUMBER)
            .containsAtLeast(
                BuildEvent.newBuilder()
                    .setId(
                        BuildEventId.newBuilder()
                            .setBuildFinished(BuildFinishedId.getDefaultInstance())
                    )
                    .setAborted(expectedAbort)
                    .build(),
                BuildEvent.newBuilder()
                    .setId(
                        BuildEventId.newBuilder()
                            .setTargetCompleted(
                                TargetCompletedId.newBuilder()
                                    .setLabel("//foo:gen")
                                    .setConfiguration(
                                        ConfigurationId.newBuilder()
                                            .setId(
                                                getConfiguredTarget("//foo:gen")
                                                    .getConfigurationChecksum()
                                            )
                                    )
                            )
                    )
                    .setAborted(expectedAbort)
                    .build()
            )
        Truth.assertThat(runtimeWrapper.getCrashMessages())
            .containsExactly(
                TestConstants.PRODUCT_NAME + " is crashing: Crashed: (java.lang.OutOfMemoryError) "
            )
        BuildIntegrationTestCase.Companion.assertAndClearBugReporterStoredCrash(java.lang.OutOfMemoryError::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun oom_besClosesAfterSpecialCaseTimeoutThrownFromSkyframe() {
        // BES server-side will never finish. The test will pass simply by completing and not waiting
        // until the test timeout.
        buildEventService.setDelayBeforeClosingStream(java.time.Duration.ofHours(10))
        write("foo/BUILD", "genrule(name = 'gen', outs = ['gen.out'], cmd = 'touch $@')")
        val threwOom: AtomicBoolean = AtomicBoolean(false)
        getSkyframeExecutor()
            .getEvaluator()
            .injectGraphTransformerForTesting(
                NotifyingHelper.makeNotifyingTransformer(
                    NotifyingHelper.Listener { key: SkyKey?, type: NotifyingHelper.EventType?, order: NotifyingHelper.Order?, context: Any? ->
                        if (key is ActionLookupData && !threwOom.getAndSet(true)) {
                            throw java.lang.OutOfMemoryError()
                        }
                    })
            )
        addOptions(
            "--bes_backend=inprocess",
            "--bes_upload_mode=WAIT_FOR_UPLOAD_COMPLETE",
            "--bes_oom_finish_upload_timeout=2s",
            "--oom_message=Please build fewer targets."
        )

        org.junit.Assert.assertThrows<java.lang.OutOfMemoryError?>(
            java.lang.OutOfMemoryError::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//foo:gen") })

        Truth.assertThat(runtimeWrapper.getCrashMessages())
            .containsExactly(
                TestConstants.PRODUCT_NAME + " is crashing: Crashed: (java.lang.OutOfMemoryError) "
            )
        BuildIntegrationTestCase.Companion.assertAndClearBugReporterStoredCrash(java.lang.OutOfMemoryError::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun commandLineEvents_includesFlagsFromFlagsets() {
        write(
            "hello/BUILD",
            """
        genrule(name = "hello", outs = ["hello.out"], cmd = "touch ${'$'}@")
        
        """.trimIndent()
        )

        write(
            "flag/flag_def.bzl",
            """
string_flag = rule(
  implementation = lambda ctx: [],
  build_setting = config.string(flag = True),
)

""".trimIndent()
        )
        write(
            "flag/BUILD",
            """
load(":flag_def.bzl", "string_flag")
string_flag(
  name = "my_flag",
  build_setting_default = "default_value",
)

""".trimIndent()
        )
        writeProjectSclDefinition("test/project_proto.scl",  /* alsoWriteBuildFile= */true)
        write(
            "hello/PROJECT.scl",
            """
load(
  "//test:project_proto.scl",
  "buildable_unit_pb2",
  "project_pb2",
)
project = project_pb2.Project.create(
  enforcement_policy = "warn",
  buildable_units = [
      buildable_unit_pb2.BuildableUnit.create(
          name = "default_config",
          flags = ["--define=foo=bar", "--//flag:my_flag=my_value"],
          is_default = True,
      )
  ],
)

""".trimIndent()
        )
        val buildEventBinaryFile: java.io.File = tmpFolder.newFile()
        addOptions(
            "--enforce_project_configs",
            "--build_event_binary_file=" + buildEventBinaryFile.getAbsolutePath()
        )
        buildTarget("//hello:hello")

        val canonicalCommandLineEvent: BuildEvent =
            Companion.findEventInBep(
                buildEventBinaryFile,
                java.util.function.Function { e: BuildEvent? ->
                    if (e.getStructuredCommandLine().getCommandLineLabel().equals("canonical")) e else null
                })
        val sections: com.google.common.collect.ImmutableList<CommandLineSection?> =
            canonicalCommandLineEvent.getStructuredCommandLine().getSectionsList().stream()
                .filter({ s -> s.getSectionLabel().equals("command options") })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())

        val options: com.google.common.collect.ImmutableList<String>? =
            sections.getFirst().getOptionList().getOptionList().stream()
                .map(Option::getCombinedForm)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        Truth.assertThat(options).contains("--define=foo=bar")
        Truth.assertThat(options).contains("--//flag:my_flag=my_value")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun bzlMetrics(
        @TestParameter publishPackageMetrics: Boolean, @TestParameter recordAllPackageMetrics: Boolean
    ) {
        // In bazel there are other bzl files loaded for repo rules, so just skip.
        TruthJUnit.assume().that(AnalysisMock.get().isThisBazel()).isFalse()

        val smallBzlSize: Long = write("foo/small.bzl", "A = 1").getFileSize()
        val bigBzlSize: Long = write("foo/big.bzl", "B = 123456789").getFileSize()
        write(
            "foo/BUILD",
            """
        load(":small.bzl", "A")
        load(":big.bzl", "B")
        filegroup(name = "empty")
        
        """.trimIndent()
        )
        val buildEventBinaryFile: java.io.File = tmpFolder.newFile()
        addOptions(
            "--build_event_binary_file=" + buildEventBinaryFile.getAbsolutePath(),
            "--experimental_publish_package_metrics_in_bep=" + publishPackageMetrics,
            "--record_metrics_for_all_packages=" + recordAllPackageMetrics,
            "--log_top_n_packages=1"
        )

        buildTarget("//foo:empty")

        val bzlMetrics: BzlMetrics = getBuildMetrics(buildEventBinaryFile).getBzlMetrics()

        if (!publishPackageMetrics) {
            assertThat(bzlMetrics).isEqualToDefaultInstance()
            return
        }

        val bigBzlMetrics: BzlFileMetrics? =
            BzlFileMetrics.newBuilder().setPath("foo/big.bzl").setSize(bigBzlSize).build()
        val smallBzlMetrics: BzlFileMetrics? =
            BzlFileMetrics.newBuilder().setPath("foo/small.bzl").setSize(smallBzlSize).build()

        if (recordAllPackageMetrics) {
            assertThat(bzlMetrics.getBzlFileMetricsList())
                .containsExactly(bigBzlMetrics, smallBzlMetrics)
        } else {
            assertThat(bzlMetrics.getBzlFileMetricsList()).containsExactly(bigBzlMetrics)
        }
        assertThat(bzlMetrics.getBzlFileCount()).isEqualTo(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkProviderMetrics() {
        write(
            "foo/defs.bzl",
            """
        A = provider()
        B = provider(fields = ["x", "y"])

        def _impl(ctx):
          return [
            A(some_field = "a"),
            B(x = "x", y = "y"),
            DefaultInfo(files = depset([])),
          ]

        my_rule = rule(implementation = _impl)
        
        """.trimIndent()
        )
        write(
            "foo/BUILD",
            """
        load(":defs.bzl", "my_rule")
        my_rule(name = "example")
        
        """.trimIndent()
        )
        val buildEventBinaryFile: java.io.File = tmpFolder.newFile()
        addOptions(
            "--build_event_binary_file=" + buildEventBinaryFile.getAbsolutePath(),
            "--experimental_record_skyframe_metrics"
        )

        buildTarget("//foo:example")

        val starlarkProviderStats: com.google.common.collect.ImmutableMap<String?, StalarkProvider?> =
            com.google.common.collect.Maps.uniqueIndex(
                getBuildMetrics(buildEventBinaryFile)
                    .getBuildGraphMetrics()
                    .getStarlarkProviderStats()
                    .getProvidersList(),
                StalarkProvider::getName
            )
        Truth.assertThat(starlarkProviderStats.keys).containsExactly("A", "B")
        assertThat(starlarkProviderStats.get("A").getLocation()).startsWith("foo/defs.bzl:1")
        assertThat(starlarkProviderStats.get("A").hasSchema()).isFalse()
        assertThat(starlarkProviderStats.get("B").getLocation()).startsWith("foo/defs.bzl:2")
        assertThat(starlarkProviderStats.get("B").getSchema().getFieldCount()).isEqualTo(2)
    }

    private class DelayingCloseBufferedOutputStream(out: java.io.OutputStream?, delay: java.time.Duration) :
        BufferedOutputStream(out) {
        private val delay: java.time.Duration
        private val closed: AtomicBoolean = AtomicBoolean(false)

        init {
            this.delay = delay
            this.out = out
        }

        @Throws(IOException::class)
        override fun close() {
            com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly(delay)
            super.close()
            closed.set(true)
        }

        fun isClosed(): Boolean {
            return closed.get()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultipleActionsSingleTarget() {
        write(
            "foo/defs.bzl",
            """
        def _multi_action_rule_impl(ctx):
            outputs = []
            for i, out_name in enumerate(ctx.attr.out_names):
                out_file = ctx.actions.declare_file(out_name)
                outputs.append(out_file)
                ctx.actions.run_shell(
                    outputs = [out_file],
                    command = "echo action %d > %s" % (i, out_file.path),
                    mnemonic = "MyAction%d" % i,
                )
            return [DefaultInfo(files = depset(outputs))]

        multi_action_rule = rule(
            implementation = _multi_action_rule_impl,
            attrs = {
                "out_names": attr.string_list(),
            },
        )
        
        """.trimIndent()
        )
        write(
            "foo/BUILD",
            """
        load(":defs.bzl", "multi_action_rule")
        multi_action_rule(
            name = "my_target",
            out_names = ["out1.txt", "out2.txt"],
        )
        
        """.trimIndent()
        )

        buildTarget("//foo:my_target")
        events.assertNoWarningsOrErrors()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSeverityErrorSelection() {
        write(
            "foo/defs.bzl",
            """
        def _multi_action_rule_impl(ctx):
            outputs = []
            for i, out_name in enumerate(ctx.attr.out_names):
                out_file = ctx.actions.declare_file(out_name)
                outputs.append(out_file)
                ctx.actions.run_shell(
                    outputs = [out_file],
                    command = "echo action %d > %s" % (i, out_file.path),
                    mnemonic = "MyAction%d" % i,
                )
            return [DefaultInfo(files = depset(outputs))]

        multi_action_rule = rule(
            implementation = _multi_action_rule_impl,
            attrs = {
                "out_names": attr.string_list(),
            },
        )
        
        """.trimIndent()
        )
        write(
            "foo/BUILD",
            """
        load(":defs.bzl", "multi_action_rule")
        multi_action_rule(
            name = "my_target",
            out_names = ["out1.txt", "out2.txt"],
        )
        
        """.trimIndent()
        )

        spawnController.addSpawnShim(
            "MyAction0 foo/out1.txt",
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                ExecResult.ofException(
                    SpawnExecException(
                        "Action 0 failed",
                        Builder()
                            .setRunnerName("local")
                            .setStatus(SpawnResult.Status.NON_ZERO_EXIT)
                            .setExitCode(1)
                            .setFailureDetail(
                                FailureDetail.newBuilder()
                                    .setMessage("Action 0 failed")
                                    .setSpawn(Spawn.newBuilder().setCode(Code.NON_ZERO_EXIT))
                                    .build()
                            )
                            .build(),  /* forciblyRunRemotely= */
                        false,  /* catastrophe= */
                        false
                    )
                )
            })

        // EXECUTION_FAILED is an infrastructure error and should be prioritized over NON_ZERO_EXIT.
        spawnController.addSpawnShim(
            "MyAction1 foo/out2.txt",
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                ExecResult.ofException(
                    SpawnExecException(
                        "Action 1 failed",
                        Builder()
                            .setRunnerName("local")
                            .setStatus(SpawnResult.Status.EXECUTION_FAILED)
                            .setExitCode(34)
                            .setFailureDetail(
                                FailureDetail.newBuilder()
                                    .setMessage("Action 1 failed")
                                    .setSpawn(Spawn.newBuilder().setCode(Code.EXECUTION_FAILED))
                                    .build()
                            )
                            .build(),  /* forciblyRunRemotely= */
                        false,  /* catastrophe= */
                        false
                    )
                )
            })

        val bep: java.io.File = tmpFolder.newFile()
        addOptions(
            "--keep_going",
            "--spawn_strategy=standalone",
            "--build_event_binary_file=" + bep.getAbsolutePath(),
            "--bes_upload_mode=WAIT_FOR_UPLOAD_COMPLETE"
        )

        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//foo:my_target") })
        afterBuildCommand()

        val buildFinished: BuildFinished = findBuildFinishedEvent(bep)
        assertThat(buildFinished).isNotNull()
        assertThat(buildFinished.getFailureDetail().getSpawn().getCode())
            .isEqualTo(Code.EXECUTION_FAILED)
    }

    companion object {
        private val WAIT_FOR_LAST_INVOCATION_TIMEOUT: java.time.Duration? = java.time.Duration.ofSeconds(2)

        private fun getBuildEventFileFlag(
            buildEventFileType: BuildEventFileType, filePath: String?
        ): String {
            return when (buildEventFileType) {
                TEXT -> "--build_event_text_file=" + filePath
                JSON -> "--build_event_json_file=" + filePath
                BINARY -> "--build_event_binary_file=" + filePath
            }
        }

        private fun getBuildEventFileUploadModeFlag(
            buildEventFileType: BuildEventFileType, mode: String?
        ): String {
            return when (buildEventFileType) {
                TEXT -> "--build_event_text_file_upload_mode=" + mode
                JSON -> "--build_event_json_file_upload_mode=" + mode
                BINARY -> "--build_event_binary_file_upload_mode=" + mode
            }
        }

        @Throws(IOException::class)
        private fun findBuildFinishedEvent(bep: java.io.File): BuildFinished {
            return Companion.findEventInBep(
                bep,
                java.util.function.Function { ev: BuildEvent? -> if (ev.hasFinished()) ev.getFinished() else null })
        }

        @Throws(IOException::class)
        private fun getBuildMetrics(buildEventBinaryFile: java.io.File): BuildMetrics {
            return Companion.findEventInBep(
                buildEventBinaryFile,
                java.util.function.Function { ev: BuildEvent? -> if (ev.hasBuildMetrics()) ev.getBuildMetrics() else null })
        }

        @Throws(IOException::class)
        private fun <T : MessageLite?> findEventInBep(
            bep: java.io.File, extractor: java.util.function.Function<BuildEvent?, T?>
        ): T? {
            FileInputStream(bep).use { `in` ->
                var ev: BuildEvent?
                while ((BuildEvent.parseDelimitedFrom(`in`).also { ev = it }) != null) {
                    val extracted: T? = extractor.apply(ev)
                    if (extracted != null) {
                        return extracted
                    }
                }
            }
            throw java.util.NoSuchElementException()
        }
    }
}
