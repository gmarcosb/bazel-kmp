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

import com.google.devtools.build.v1.BuildEvent.EventCase.BAZEL_EVENT

/** Integration tests for [BuildEventServiceTransport]  */
@RunWith(JUnit4::class)
abstract class AbstractBuildEventServiceTransportTest : FoundationTestCase() {
    private val artifactGroupNamer: ArtifactGroupNamer? =
        Mockito.mock<ArtifactGroupNamer?>(ArtifactGroupNamer::class.java)
    private val buildRequest: BuildRequest = Mockito.mock<BuildRequest>(BuildRequest::class.java)
    private val buildEventContext: BuildEventContext? = Mockito.mock<BuildEventContext?>(BuildEventContext::class.java)

    private val clock: com.google.devtools.build.lib.testutil.ManualClock =
        com.google.devtools.build.lib.testutil.ManualClock()

    private val fakeBesServer: AbstractBuildEventRecorder = createBesServer()

    private val started: BuildEvent = BuildStartingEvent.create(
        "OutputFileSystemType",  /*usesInMemoryFileSystem=*/
        false,
        buildRequest,  /*workspace=*/
        null,
        "/pwd"
    )
    private val progress: BuildEvent = ProgressEvent.progressUpdate(1)
    private val success: BuildEvent =
        object : BuildCompletingEvent(ExitCode.SUCCESS, java.lang.System.currentTimeMillis()) {}
    private val failed: BuildEvent =
        object : BuildCompletingEvent(ExitCode.BUILD_FAILURE, java.lang.System.currentTimeMillis()) {}

    @Before
    fun setUp() {
        Mockito.`when`<T?>(buildRequest.getId()).thenReturn(UUID.fromString(BUILD_REQUEST_ID))
        Mockito.`when`<Any?>(buildRequest.commandName).thenReturn(COMMAND_NAME)
        Mockito.`when`<Any?>(buildRequest.optionsDescription).thenReturn("")

        fakeBesServer.startRpcServer()
    }

    @org.junit.After
    fun tearDown() {
        Mockito.validateMockitoUsage()
        fakeBesServer.stopRpcServer()
    }

    @org.junit.Test(timeout = TIMEOUT_MILLIS)
    @Throws(java.lang.Exception::class)
    fun testPublishLifecyleEvents_commandSucceeded() {
        testPublishLifecycleEvents(InvocationStatus.SUCCEEDED, success)
    }

    @org.junit.Test(timeout = TIMEOUT_MILLIS)
    @Throws(java.lang.Exception::class)
    fun testPublishLifecycleEvents_commandFailed() {
        testPublishLifecycleEvents(InvocationStatus.FAILED, failed)
    }

    @org.junit.Test(timeout = TIMEOUT_MILLIS)
    @Throws(java.lang.Exception::class)
    fun testPublishLifecycleEvents_statusUnknown() {
        testPublishLifecycleEvents(InvocationStatus.UNKNOWN, progress)
    }

    @Throws(java.lang.Exception::class)
    private fun testPublishLifecycleEvents(expectedStatus: InvocationStatus?, lastEvent: BuildEvent) {
        clock.advanceMillis(750L)
        val invocationStartedTimestamp: Instant? = clock.now()
        val transport: BuildEventServiceTransport =
            newBuildEventServiceTransport( /*publishLifecycleEvents=*/true)
        clock.advanceMillis(250L)
        val timestamp: Instant? = clock.now()
        transport.sendBuildEvent(started)
        transport.sendBuildEvent(progress)
        transport.sendBuildEvent(lastEvent)
        transport.close().get()

        // build lifecycle events
        Truth.assertThat(
            fakeBesServer.getLifecycleEvents(
                BuildEventServiceProtoUtil.streamId(COMMAND_CONTEXT, BUILD_ENQUEUED)
            )
        )
            .containsExactly(
                BuildEventServiceProtoUtil.buildEnqueued(COMMAND_CONTEXT, COMMAND_START_TIME),
                BuildEventServiceProtoUtil.buildFinished(COMMAND_CONTEXT, timestamp, expectedStatus)
            )

        // invocation lifecycle events
        Truth.assertThat(
            fakeBesServer.getLifecycleEvents(
                BuildEventServiceProtoUtil.streamId(COMMAND_CONTEXT, INVOCATION_ATTEMPT_STARTED)
            )
        )
            .containsExactly(
                BuildEventServiceProtoUtil.invocationStarted(
                    COMMAND_CONTEXT, invocationStartedTimestamp
                ),
                BuildEventServiceProtoUtil.invocationFinished(
                    COMMAND_CONTEXT, timestamp, expectedStatus
                )
            )

        // bazel stream events
        Truth.assertThat(
            fakeBesServer.getStreamEvents(
                BuildEventServiceProtoUtil.streamId(COMMAND_CONTEXT, BAZEL_EVENT)
            )
        )
            .containsExactly(
                BuildEventServiceProtoUtil.bazelEvent(
                    COMMAND_CONTEXT,
                    timestamp,
                    1,
                    started.asStreamProto(buildEventContext).toByteArray()
                ),
                BuildEventServiceProtoUtil.bazelEvent(
                    COMMAND_CONTEXT,
                    timestamp,
                    2,
                    progress.asStreamProto(buildEventContext).toByteArray()
                ),
                BuildEventServiceProtoUtil.bazelEvent(
                    COMMAND_CONTEXT,
                    timestamp,
                    3,
                    lastEvent.asStreamProto(buildEventContext).toByteArray()
                ),
                BuildEventServiceProtoUtil.streamFinished(COMMAND_CONTEXT, timestamp, 4)
            )
            .inOrder()
    }

    @org.junit.Test(timeout = TIMEOUT_MILLIS)
    @Throws(java.lang.Exception::class)
    fun disablingLifecycleEventsWorks() {
        clock.advanceMillis(1000L)
        val timestamp: Instant? = clock.now()
        val transport: BuildEventServiceTransport =
            newBuildEventServiceTransport( /*publishLifecycleEvents=*/false)
        transport.sendBuildEvent(started)
        transport.sendBuildEvent(progress)
        transport.sendBuildEvent(success)
        transport.close().get()

        // bazel stream events
        Truth.assertThat(
            fakeBesServer.getStreamEvents(
                BuildEventServiceProtoUtil.streamId(COMMAND_CONTEXT, BAZEL_EVENT)
            )
        )
            .containsExactly(
                BuildEventServiceProtoUtil.bazelEvent(
                    COMMAND_CONTEXT,
                    timestamp,
                    1,
                    started.asStreamProto(buildEventContext).toByteArray()
                ),
                BuildEventServiceProtoUtil.bazelEvent(
                    COMMAND_CONTEXT,
                    timestamp,
                    2,
                    progress.asStreamProto(buildEventContext).toByteArray()
                ),
                BuildEventServiceProtoUtil.bazelEvent(
                    COMMAND_CONTEXT,
                    timestamp,
                    3,
                    success.asStreamProto(buildEventContext).toByteArray()
                ),
                BuildEventServiceProtoUtil.streamFinished(COMMAND_CONTEXT, timestamp, 4)
            )
            .inOrder()
    }

    @org.junit.Test(timeout = TIMEOUT_MILLIS)
    @Throws(java.lang.Exception::class)
    fun sendEventsInLockStep() {
        // A test that only sends the next build event after the previous build event has been
        // ACKed by the server.
        val transport: BuildEventServiceTransport =
            newBuildEventServiceTransport( /*publishLifecycleEvents=*/false)

        val toSend: MutableList<BuildEvent?> = java.util.Arrays.asList<BuildEvent?>(started, progress, success)
        for (i in toSend.indices) {
            transport.sendBuildEvent(toSend.get(i))
            while (fakeBesServer
                    .getSuccessfulStreamEvents(
                        BuildEventServiceProtoUtil.streamId(COMMAND_CONTEXT, BAZEL_EVENT)
                    )
                    .size
                != i + 1
            ) {
                java.lang.Thread.sleep(10)
            }
        }

        transport.close().get()
    }

    @org.junit.Test(timeout = TIMEOUT_MILLIS)
    @Throws(java.lang.Exception::class)
    fun testAcksInBatchMode() {
        clock.advanceMillis(1000L)
        val timestamp: Instant? = clock.now()
        // Send the first ACK only after the last event has been received.
        fakeBesServer.setSendResponsesOnRequestPredicate(
            java.util.function.Predicate { req: PublishBuildToolEventStreamRequest? ->
                req == BuildEventServiceProtoUtil.streamFinished(
                    COMMAND_CONTEXT, timestamp, 4
                )
            })
        val transport: BuildEventServiceTransport =
            newBuildEventServiceTransport( /*publishLifecycleEvents=*/false)
        transport.sendBuildEvent(started)
        transport.sendBuildEvent(progress)
        transport.sendBuildEvent(success)
        transport.close().get()
    }

    @org.junit.Test(timeout = TIMEOUT_MILLIS)
    @Throws(java.lang.Exception::class)
    fun retriesForLastEventShouldWork() {
        clock.advanceMillis(1000L)
        val timestamp: Instant? = clock.now()
        // Send UNAVAILABLE on streamFinished event
        fakeBesServer.setStreamEventPredicateAndResponseStatus(
            java.util.function.Predicate { req: PublishBuildToolEventStreamRequest? ->
                req == BuildEventServiceProtoUtil.streamFinished(
                    COMMAND_CONTEXT, timestamp, 4
                )
            },
            io.grpc.Status.UNAVAILABLE
        )

        val transport: BuildEventServiceTransport =
            newBuildEventServiceTransport( /*publishLifecycleEvents=*/false)
        transport.sendBuildEvent(started)
        transport.sendBuildEvent(progress)
        transport.sendBuildEvent(success)

        val exception: ExecutionException =
            org.junit.Assert.assertThrows<ExecutionException>(
                ExecutionException::class.java,
                org.junit.function.ThrowingRunnable { transport.close().get() })
        assertTransientError(exception, BuildProgress.Code.BES_UPLOAD_RETRY_LIMIT_EXCEEDED_FAILURE)
        Truth.assertThat(exception.message)
            .containsMatch(
                "The Build Event Protocol upload failed: no publishBuildEvents retry attempts left:"
                        + " .*UNAVAILABLE"
            )

        Truth.assertThat(
            fakeBesServer.getStreamEvents(
                BuildEventServiceProtoUtil.streamId(COMMAND_CONTEXT, BAZEL_EVENT)
            )
        )
            .containsAtLeast(
                BuildEventServiceProtoUtil.bazelEvent(
                    COMMAND_CONTEXT,
                    timestamp,
                    1,
                    started.asStreamProto(buildEventContext).toByteArray()
                ),
                BuildEventServiceProtoUtil.bazelEvent(
                    COMMAND_CONTEXT,
                    timestamp,
                    2,
                    progress.asStreamProto(buildEventContext).toByteArray()
                ),
                BuildEventServiceProtoUtil.bazelEvent(
                    COMMAND_CONTEXT,
                    timestamp,
                    3,
                    success.asStreamProto(buildEventContext).toByteArray()
                ),
                BuildEventServiceProtoUtil.streamFinished(
                    COMMAND_CONTEXT,
                    timestamp,
                    4
                ),  // Verify retry on streamFinished message
                BuildEventServiceProtoUtil.streamFinished(COMMAND_CONTEXT, timestamp, 4)
            )
            .inOrder()
    }

    @org.junit.Test(timeout = TIMEOUT_MILLIS)
    @Throws(java.lang.Exception::class)
    fun retriesForInvocationStartedEventShouldWork() {
        clock.advanceMillis(750L)
        val invocationStartedTimestamp: Instant? = clock.now()
        // Respond with UNAVAILABLE to invocation started lifecycle event
        fakeBesServer.setLifecycleEventPredicateAndResponseStatus(
            java.util.function.Predicate { req: PublishLifecycleEventRequest? ->
                req == BuildEventServiceProtoUtil.invocationStarted(
                    COMMAND_CONTEXT, invocationStartedTimestamp
                )
            },
            io.grpc.Status.UNAVAILABLE
        )

        val transport: BuildEventServiceTransport =
            newBuildEventServiceTransport( /*publishLifecycleEvents=*/true)

        val exception: ExecutionException =
            org.junit.Assert.assertThrows<ExecutionException>(
                ExecutionException::class.java,
                org.junit.function.ThrowingRunnable { transport.close().get() })
        assertTransientError(exception, BuildProgress.Code.BES_UPLOAD_RETRY_LIMIT_EXCEEDED_FAILURE)
        Truth.assertThat(exception.message)
            .contains(
                "The Build Event Protocol upload failed: all 4 publishLifecycleEvent retry attempts"
                        + " failed: UNAVAILABLE"
            )

        // should not proceed as lifecycle event failed
        Truth.assertThat(
            fakeBesServer.getLifecycleEvents(
                BuildEventServiceProtoUtil.streamId(COMMAND_CONTEXT, BUILD_ENQUEUED)
            )
        )
            .containsExactly(
                BuildEventServiceProtoUtil.buildEnqueued(COMMAND_CONTEXT, COMMAND_START_TIME)
            )

        // should retry only the rpc that failed
        Truth.assertThat(
            fakeBesServer.getLifecycleEvents(
                BuildEventServiceProtoUtil.streamId(COMMAND_CONTEXT, INVOCATION_ATTEMPT_STARTED)
            )
        )
            .containsExactly(
                BuildEventServiceProtoUtil.invocationStarted(
                    COMMAND_CONTEXT, invocationStartedTimestamp
                ),
                BuildEventServiceProtoUtil.invocationStarted(
                    COMMAND_CONTEXT, invocationStartedTimestamp
                ),
                BuildEventServiceProtoUtil.invocationStarted(
                    COMMAND_CONTEXT, invocationStartedTimestamp
                ),
                BuildEventServiceProtoUtil.invocationStarted(
                    COMMAND_CONTEXT, invocationStartedTimestamp
                ),
                BuildEventServiceProtoUtil.invocationStarted(
                    COMMAND_CONTEXT, invocationStartedTimestamp
                )
            )
    }

    @org.junit.Test(timeout = TIMEOUT_MILLIS)
    @Throws(java.lang.Exception::class)
    fun testRetriesForBuildEvents_oneEventFailsAlways() {
        clock.advanceMillis(1000L)
        val timestamp: Instant? = clock.now()

        val expectedPayload: ByteArray? = progress.asStreamProto(buildEventContext).toByteArray()
        fakeBesServer.setStreamEventPredicateAndResponseStatus(
            java.util.function.Predicate { req: PublishBuildToolEventStreamRequest? ->
                req == BuildEventServiceProtoUtil.bazelEvent(
                    COMMAND_CONTEXT, timestamp, 2, expectedPayload
                )
            },
            io.grpc.Status.CANCELLED
        )

        val transport: BuildEventServiceTransport =
            newBuildEventServiceTransport( /*publishLifecycleEvents=*/false)
        transport.sendBuildEvent(started)
        transport.sendBuildEvent(progress)
        transport.sendBuildEvent(success)

        val exception: ExecutionException =
            org.junit.Assert.assertThrows<ExecutionException>(
                ExecutionException::class.java,
                org.junit.function.ThrowingRunnable { transport.close().get() })
        assertTransientError(exception, BuildProgress.Code.BES_UPLOAD_RETRY_LIMIT_EXCEEDED_FAILURE)
        Truth.assertThat(exception.message)
            .contains(
                "The Build Event Protocol upload failed: no publishBuildEvents retry attempts left:"
                        + " CANCELLED"
            )

        Truth.assertThat(
            fakeBesServer.getSuccessfulStreamEvents(
                BuildEventServiceProtoUtil.streamId(COMMAND_CONTEXT, BAZEL_EVENT)
            )
        )
            .contains(
                BuildEventServiceProtoUtil.bazelEvent(
                    COMMAND_CONTEXT,
                    timestamp,
                    1,
                    started.asStreamProto(buildEventContext).toByteArray()
                )
            )

        Truth.assertThat(
            fakeBesServer.getStreamEvents(
                BuildEventServiceProtoUtil.streamId(COMMAND_CONTEXT, BAZEL_EVENT)
            )
        )
            .containsAtLeast(
                BuildEventServiceProtoUtil.bazelEvent(COMMAND_CONTEXT, timestamp, 2, expectedPayload),
                BuildEventServiceProtoUtil.bazelEvent(COMMAND_CONTEXT, timestamp, 2, expectedPayload),
                BuildEventServiceProtoUtil.bazelEvent(COMMAND_CONTEXT, timestamp, 2, expectedPayload),
                BuildEventServiceProtoUtil.bazelEvent(COMMAND_CONTEXT, timestamp, 2, expectedPayload),
                BuildEventServiceProtoUtil.bazelEvent(COMMAND_CONTEXT, timestamp, 2, expectedPayload)
            )
    }

    @org.junit.Test(timeout = TIMEOUT_MILLIS)
    @Throws(java.lang.Exception::class)
    fun testRetriesForBuildEvents_everyEventFailsOnce() {
        clock.advanceMillis(1000L)
        val timestamp: Instant? = clock.now()
        fakeBesServer.setStreamEventPredicateAndResponseStatus(
            Companion.everyEventFailsOnce<PublishBuildToolEventStreamRequest?>(), io.grpc.Status.UNAVAILABLE
        )

        val transport: BuildEventServiceTransport =
            newBuildEventServiceTransport( /*publishLifecycleEvents=*/false)
        transport.sendBuildEvent(started)
        transport.sendBuildEvent(success)
        transport.close().get()

        Truth.assertThat(
            fakeBesServer.getSuccessfulStreamEvents(
                BuildEventServiceProtoUtil.streamId(COMMAND_CONTEXT, BAZEL_EVENT)
            )
        )
            .containsAtLeast(
                BuildEventServiceProtoUtil.bazelEvent(
                    COMMAND_CONTEXT,
                    timestamp,
                    1,
                    started.asStreamProto(buildEventContext).toByteArray()
                ),
                BuildEventServiceProtoUtil.bazelEvent(
                    COMMAND_CONTEXT,
                    timestamp,
                    2,
                    success.asStreamProto(buildEventContext).toByteArray()
                ),
                BuildEventServiceProtoUtil.streamFinished(COMMAND_CONTEXT, timestamp, 3)
            )

        Truth.assertThat(
            fakeBesServer.getStreamEvents(
                BuildEventServiceProtoUtil.streamId(COMMAND_CONTEXT, BAZEL_EVENT)
            )
        )
            .containsAtLeast(
                BuildEventServiceProtoUtil.bazelEvent(
                    COMMAND_CONTEXT,
                    timestamp,
                    1,
                    started.asStreamProto(buildEventContext).toByteArray()
                ),
                BuildEventServiceProtoUtil.bazelEvent(
                    COMMAND_CONTEXT,
                    timestamp,
                    1,
                    started.asStreamProto(buildEventContext).toByteArray()
                ),
                BuildEventServiceProtoUtil.bazelEvent(
                    COMMAND_CONTEXT,
                    timestamp,
                    2,
                    success.asStreamProto(buildEventContext).toByteArray()
                ),
                BuildEventServiceProtoUtil.bazelEvent(
                    COMMAND_CONTEXT,
                    timestamp,
                    2,
                    success.asStreamProto(buildEventContext).toByteArray()
                ),
                BuildEventServiceProtoUtil.streamFinished(COMMAND_CONTEXT, timestamp, 3),
                BuildEventServiceProtoUtil.streamFinished(COMMAND_CONTEXT, timestamp, 3)
            )
    }

    /** Tests that a successfully transmitted build event resets the retry counter.  */
    @org.junit.Test(timeout = TIMEOUT_MILLIS)
    @Throws(java.lang.Exception::class)
    fun testRetriesForBuildEvents_acksResetsAttempts() {
        val failedSeqNumbers: MutableSet<Long?> = Collections.synchronizedSet<Long?>(LinkedHashSet<Long?>())
        // Fail once with UNAVAILABLE (transient error) for every build event.
        fakeBesServer.setStreamEventPredicateAndResponseStatus(
            java.util.function.Predicate { req: PublishBuildToolEventStreamRequest? ->
                val seqNumber: Long = req.getOrderedBuildEvent().getSequenceNumber()
                failedSeqNumbers.add(seqNumber)
            },
            io.grpc.Status.UNAVAILABLE
        )

        val transport: BuildEventServiceTransport =
            newBuildEventServiceTransport( /*publishLifecycleEvents=*/false)

        transport.sendBuildEvent(started)
        for (i in 0..9) {
            transport.sendBuildEvent(progress)
        }
        transport.sendBuildEvent(success)

        transport.close().get()

        val successfulSequenceNumbers: MutableSet<Long?> =
            fakeBesServer
                .getSuccessfulStreamEvents(
                    BuildEventServiceProtoUtil.streamId(COMMAND_CONTEXT, BAZEL_EVENT)
                )
                .stream()
                .map<Any?> { e: PublishBuildToolEventStreamRequest? -> e.getOrderedBuildEvent().getSequenceNumber() }
                .collect(Collectors.toSet())

        Truth.assertThat(successfulSequenceNumbers).containsExactlyElementsIn(failedSeqNumbers)
        Truth.assertThat(successfulSequenceNumbers).hasSize(13)
    }

    @org.junit.Test(timeout = TIMEOUT_MILLIS)
    @Throws(java.lang.Exception::class)
    fun persistentErrorsShouldNotBeRetried_eventStream_invalidArgument() {
        testPermanentErrorsCauseBlazeExit(
            io.grpc.Status.INVALID_ARGUMENT,
            ExitCode.PERSISTENT_BUILD_EVENT_SERVICE_UPLOAD_ERROR,
            BuildProgress.Code.BES_STREAM_NOT_RETRYING_FAILURE
        )
    }

    @org.junit.Test(timeout = TIMEOUT_MILLIS)
    @Throws(java.lang.Exception::class)
    fun persistentErrorsShouldNotBeRetried_eventStream_failedPrecondition() {
        testPermanentErrorsCauseBlazeExit(
            io.grpc.Status.FAILED_PRECONDITION,
            ExitCode.TRANSIENT_BUILD_EVENT_SERVICE_UPLOAD_ERROR,
            BuildProgress.Code.BES_UPLOAD_TIMEOUT_ERROR
        )
    }

    @Throws(java.lang.Exception::class)
    private fun testPermanentErrorsCauseBlazeExit(
        status: io.grpc.Status, exitCode: ExitCode?, buildProgressCode: BuildProgress.Code?
    ) {
        clock.advanceMillis(1000L)
        val timestamp: Instant? = clock.now()
        fakeBesServer.setStreamEventPredicateAndResponseStatus(
            java.util.function.Predicate { req: PublishBuildToolEventStreamRequest? -> true },
            status
        )

        val transport: BuildEventServiceTransport =
            newBuildEventServiceTransport( /* publishLifecycleEvents= */false)
        transport.sendBuildEvent(started)

        val exception: ExecutionException =
            org.junit.Assert.assertThrows<ExecutionException>(
                ExecutionException::class.java,
                org.junit.function.ThrowingRunnable { transport.close().get() })
        assertExecutionException(exception, exitCode, buildProgressCode)
        Truth.assertThat(exception.message)
            .contains(
                "The Build Event Protocol upload failed: not retrying publishBuildEvents: "
                        + status.getCode().name
            )

        Truth.assertThat(
            fakeBesServer.getStreamEvents(
                BuildEventServiceProtoUtil.streamId(COMMAND_CONTEXT, BAZEL_EVENT)
            )
        )
            .contains(
                BuildEventServiceProtoUtil.bazelEvent(
                    COMMAND_CONTEXT,
                    timestamp,
                    1,
                    started.asStreamProto(buildEventContext).toByteArray()
                )
            )

        Truth.assertThat(
            fakeBesServer.getSuccessfulStreamEvents(
                BuildEventServiceProtoUtil.streamId(COMMAND_CONTEXT, BAZEL_EVENT)
            )
        )
            .isEmpty()
    }

    @org.junit.Test(timeout = TIMEOUT_MILLIS)
    @Throws(java.lang.Exception::class)
    fun persistentErrorsShouldNotBeRetried_lifecycleEvents() {
        fakeBesServer.setLifecycleEventPredicateAndResponseStatus(
            java.util.function.Predicate { req: PublishLifecycleEventRequest? -> true },
            io.grpc.Status.FAILED_PRECONDITION
        )

        val transport: BuildEventServiceTransport =
            newBuildEventServiceTransport( /*publishLifecycleEvents=*/true)
        transport.sendBuildEvent(started)

        val exception: ExecutionException =
            org.junit.Assert.assertThrows<ExecutionException>(
                ExecutionException::class.java,
                org.junit.function.ThrowingRunnable { transport.close().get() })
        assertPersistentError(exception, BuildProgress.Code.BES_STREAM_NOT_RETRYING_FAILURE)
        Truth.assertThat(exception.message)
            .contains(
                "The Build Event Protocol upload failed: not retrying publishLifecycleEvent:"
                        + " FAILED_PRECONDITION"
            )

        Truth.assertThat(
            fakeBesServer.getLifecycleEvents(
                BuildEventServiceProtoUtil.streamId(COMMAND_CONTEXT, BUILD_ENQUEUED)
            )
        )
            .containsExactly(
                BuildEventServiceProtoUtil.buildEnqueued(COMMAND_CONTEXT, COMMAND_START_TIME)
            )
    }

    @org.junit.Test(timeout = TIMEOUT_MILLIS)
    @Throws(java.lang.Exception::class)
    fun lifecycleEventsAreRetried() {
        clock.advanceMillis(750L)
        val invocationStartedTimestamp: Instant? = clock.now()
        fakeBesServer.setLifecycleEventPredicateAndResponseStatus(
            Companion.everyEventFailsOnce<PublishLifecycleEventRequest?>(), io.grpc.Status.UNAVAILABLE
        )

        val transport: BuildEventServiceTransport =
            newBuildEventServiceTransport( /*publishLifecycleEvents=*/true)
        clock.advanceMillis(250L)
        val timestamp: Instant? = clock.now()
        transport.close().get()

        // all  build lifecycle events
        Truth.assertThat(
            fakeBesServer.getLifecycleEvents(
                BuildEventServiceProtoUtil.streamId(COMMAND_CONTEXT, BUILD_ENQUEUED)
            )
        )
            .containsExactly(
                BuildEventServiceProtoUtil.buildEnqueued(COMMAND_CONTEXT, COMMAND_START_TIME),
                BuildEventServiceProtoUtil.buildEnqueued(COMMAND_CONTEXT, COMMAND_START_TIME),
                BuildEventServiceProtoUtil.buildFinished(
                    COMMAND_CONTEXT, timestamp, InvocationStatus.UNKNOWN
                ),
                BuildEventServiceProtoUtil.buildFinished(
                    COMMAND_CONTEXT, timestamp, InvocationStatus.UNKNOWN
                )
            )
            .inOrder()

        // all invocation lifecycle events
        Truth.assertThat(
            fakeBesServer.getLifecycleEvents(
                BuildEventServiceProtoUtil.streamId(COMMAND_CONTEXT, INVOCATION_ATTEMPT_STARTED)
            )
        )
            .containsExactly(
                BuildEventServiceProtoUtil.invocationStarted(
                    COMMAND_CONTEXT, invocationStartedTimestamp
                ),
                BuildEventServiceProtoUtil.invocationStarted(
                    COMMAND_CONTEXT, invocationStartedTimestamp
                ),
                BuildEventServiceProtoUtil.invocationFinished(
                    COMMAND_CONTEXT, timestamp, InvocationStatus.UNKNOWN
                ),
                BuildEventServiceProtoUtil.invocationFinished(
                    COMMAND_CONTEXT, timestamp, InvocationStatus.UNKNOWN
                )
            )
            .inOrder()

        // All event stream.
        Truth.assertThat(
            fakeBesServer.getStreamEvents(
                BuildEventServiceProtoUtil.streamId(COMMAND_CONTEXT, BAZEL_EVENT)
            )
        )
            .containsExactly(BuildEventServiceProtoUtil.streamFinished(COMMAND_CONTEXT, timestamp, 1))
    }

    /**
     * Sending a response status OK with ACKs outstanding is a protocol error and should fail the
     * stream without retries.
     */
    @org.junit.Test(timeout = TIMEOUT_MILLIS)
    @Throws(java.lang.Exception::class)
    fun responseStatusOkWithAcksMissing() {
        fakeBesServer.setStreamEventPredicateAndResponseStatus(
            Companion.everyEventFailsOnce<PublishBuildToolEventStreamRequest?>(),
            io.grpc.Status.OK
        )

        val transport: BuildEventServiceTransport =
            newBuildEventServiceTransport( /*publishLifecycleEvents=*/false)
        transport.sendBuildEvent(started)
        transport.sendBuildEvent(progress)
        transport.sendBuildEvent(success)

        val exception: ExecutionException =
            org.junit.Assert.assertThrows<ExecutionException>(
                ExecutionException::class.java,
                org.junit.function.ThrowingRunnable { transport.close().get() })
        assertPersistentError(
            exception, BuildProgress.Code.BES_STREAM_COMPLETED_WITH_UNACK_EVENTS_ERROR
        )
        Truth.assertThat(exception.message)
            .contains(
                "The Build Event Protocol upload failed: server closed stream with status OK but not"
                        + " all ACKs have been received"
            )
    }

    /** Tests that uploading files referenced by a build event works.  */
    @org.junit.Test(timeout = TIMEOUT_MILLIS)
    @Throws(java.lang.Exception::class)
    fun testFileUpload() {
        val inMemoryFs: InMemoryFileSystem = InMemoryFileSystem(makeVfsHashFunction())
        val file1: Path = inMemoryFs.getPath("/file1")
        val file2: Path = inMemoryFs.getPath("/file2")
        FileSystemUtils.writeContentAsLatin1(file1, "file1")
        FileSystemUtils.writeContentAsLatin1(file2, "file2")
        val withFiles: BuildEvent =
            BuildEventWithFiles(
                com.google.common.collect.ImmutableList.of<LocalFile?>(
                    LocalFile(file1, LocalFileType.OUTPUT_FILE,  /* artifactMetadata= */null),
                    LocalFile(file2, LocalFileType.OUTPUT_FILE,  /* artifactMetadata= */null)
                )
            )

        var uploader: BuildEventArtifactUploader? =
            object : BuildEventArtifactUploaderWithRefCounting() {
                public override fun upload(files: MutableMap<Path, LocalFile?>): com.google.common.util.concurrent.ListenableFuture<PathConverter?> {
                    val conversion: MutableMap<Path?, String?> = HashMap<Path?, String?>()
                    for (file in files.keys) {
                        try {
                            conversion.put(file, "cas://" + com.google.common.hash.HashCode.fromBytes(file.getDigest()))
                        } catch (e: IOException) {
                            return com.google.common.util.concurrent.Futures.immediateFailedFuture<PathConverter?>(e)
                        }
                    }
                    return com.google.common.util.concurrent.Futures.immediateFuture<PathConverter?>(PathConverter { key: Any? ->
                        conversion.get(
                            key
                        )
                    })
                }

                public override fun mayBeSlow(): Boolean {
                    return false
                }
            }
        uploader = Mockito.spy(uploader)

        val transport: BuildEventServiceTransport =
            newBuildEventServiceTransport(createBesClient(), true, java.time.Duration.ZERO, uploader)
        transport.sendBuildEvent(started)
        transport.sendBuildEvent(progress)
        transport.sendBuildEvent(withFiles)
        transport.sendBuildEvent(success)

        transport.close().get()

        Mockito.verify<Any?>(uploader)
            .upload(
                < T > eq < T ? > (
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    file1,
                    LocalFile(file1, LocalFileType.OUTPUT_FILE,  /* artifactMetadata= */null),
                    file2,
                    LocalFile(
                        file2, LocalFileType.OUTPUT_FILE,  /* artifactMetadata= */null
                    )
                )))

        val events: MutableList<PublishBuildToolEventStreamRequest?> =
            fakeBesServer.getStreamEvents(
                BuildEventServiceProtoUtil.streamId(COMMAND_CONTEXT, BAZEL_EVENT)
            )

        val anyEvent: Any = events.get(2).getOrderedBuildEvent().getEvent().getBazelEvent()
        val buildEvent: BuildEventStreamProtos.BuildEvent =
            anyEvent.unpack(BuildEventStreamProtos.BuildEvent::class.java)
        assertThat(buildEvent).isNotNull()
        assertThat(buildEvent.hasNamedSetOfFiles()).isTrue()
        assertThat(buildEvent.getNamedSetOfFiles().getFilesCount()).isEqualTo(2)

        val referencedFiles: MutableSet<String?>? =
            buildEvent.getNamedSetOfFiles().getFilesList().stream()
                .map(File::getUri)
                .collect(Collectors.toSet())
        val file1Hash: String? =
            makeVfsHashFunction().getHashFunction().hashString("file1", java.nio.charset.StandardCharsets.UTF_8)
                .toString()
        val file2Hash: String? =
            makeVfsHashFunction().getHashFunction().hashString("file2", java.nio.charset.StandardCharsets.UTF_8)
                .toString()
        Truth.assertThat(referencedFiles).containsExactly("cas://" + file1Hash, "cas://" + file2Hash)
    }

    /** Regression test for b/112189077.  */
    @org.junit.Test(timeout = TIMEOUT_MILLIS)
    @Throws(java.lang.Exception::class)
    fun testFileUploadWithDuplicatePaths() {
        val inMemoryFs: InMemoryFileSystem =
            InMemoryFileSystem(com.google.devtools.build.lib.clock.JavaClock(), makeVfsHashFunction())
        val file1: Path = inMemoryFs.getPath("/file1")
        FileSystemUtils.writeContentAsLatin1(file1, "file1")
        val withFiles: BuildEvent =
            BuildEventWithFiles(
                com.google.common.collect.ImmutableList.of<LocalFile?>(
                    LocalFile(file1, LocalFileType.OUTPUT_FILE,  /* artifactMetadata= */null),
                    LocalFile(file1, LocalFileType.OUTPUT_FILE,  /* artifactMetadata= */null)
                )
            )

        var uploader: BuildEventArtifactUploader? =
            object : BuildEventArtifactUploaderWithRefCounting() {
                public override fun upload(files: MutableMap<Path?, LocalFile?>?): com.google.common.util.concurrent.ListenableFuture<PathConverter?> {
                    return com.google.common.util.concurrent.Futures.immediateFuture<PathConverter?>(
                        FileUriPathConverter()
                    )
                }

                public override fun mayBeSlow(): Boolean {
                    return false
                }
            }
        uploader = Mockito.spy(uploader)

        val transport: BuildEventServiceTransport =
            newBuildEventServiceTransport(createBesClient(), true, java.time.Duration.ZERO, uploader)
        transport.sendBuildEvent(withFiles)
        transport.close().get()

        // Check to make sure the code path was exercised
        Mockito.verify<Any?>(uploader)
            .upload(
                < T > eq < T ? > (
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    file1,
                    LocalFile(
                        file1, LocalFileType.OUTPUT_FILE,  /* artifactMetadata= */null
                    )
                )))
    }

    /** Regression test for b/111389420.  */
    @org.junit.Test(timeout = TIMEOUT_MILLIS)
    @Throws(java.lang.Exception::class)
    fun testFileUploadFails() {
        // Test that a failed file upload is not retried and fails the whole upload.
        val uploadFailed: java.lang.Exception = IOException("File upload failed.")
        var uploader: BuildEventArtifactUploader? =
            object : BuildEventArtifactUploaderWithRefCounting() {
                private var callCount = 0

                public override fun upload(files: MutableMap<Path?, LocalFile?>?): com.google.common.util.concurrent.ListenableFuture<PathConverter?> {
                    callCount++
                    // Apparently, Stubby behaves like this:
                    // When we create a connection but immediately abort it, it seems like the server is not
                    // notified at all, so we need to post at least one event before we abort.
                    if (callCount == 1) {
                        return com.google.common.util.concurrent.Futures.immediateFuture<PathConverter?>(PathConverter.NO_CONVERSION)
                    } else if (callCount == 2) {
                        return com.google.common.util.concurrent.Futures.immediateFailedFuture<PathConverter?>(
                            uploadFailed
                        )
                    } else {
                        org.junit.Assert.fail("Expected exactly two calls to upload.")
                        return null
                    }
                }

                public override fun mayBeSlow(): Boolean {
                    return false
                }
            }
        uploader = Mockito.spy(uploader)

        val transport: BuildEventServiceTransport =
            newBuildEventServiceTransport(createBesClient(), true, java.time.Duration.ZERO, uploader)
        transport.sendBuildEvent(started)

        // Wait for lifecycle events to be sent.
        while (!fakeBesServer.publishBuildToolEventStreamAccepted()) {
            java.lang.Thread.sleep(10)
        }

        // This event will trigger a upload that fails.
        transport.sendBuildEvent(success)

        // Wait until the server error is found _before_ we shut down the transport. Otherwise the close
        // might race with the error.
        while (fakeBesServer.eventStreamError() == null) {
            java.lang.Thread.sleep(10)
        }

        val exception: ExecutionException =
            org.junit.Assert.assertThrows<ExecutionException>(
                ExecutionException::class.java,
                org.junit.function.ThrowingRunnable { transport.close().get() })
        assertTransientError(exception, BuildProgress.Code.BES_UPLOAD_LOCAL_FILE_ERROR)
        Truth.assertThat(exception.message)
            .contains("The Build Event Protocol local file upload failed: File upload failed.")

        Truth.assertThat<io.grpc.Status.Code?>(fakeBesServer.eventStreamError().getCode())
            .isAnyOf(io.grpc.Status.CANCELLED.getCode(), io.grpc.Status.INTERNAL.getCode())

        Mockito.verify<Any?>(uploader, Mockito.times(2)).upload(ArgumentMatchers.anyMap<K?, V?>())
    }

    /**
     * Tests that sending ACKS out of order or for non-existing events fails the upload without
     * retries, as this signals a bug in the server code.
     * 
     * 
     * Note that we do not retry within the invocation, but we return a *transient* exit
     * code. The `FAILED_PRECONDITION` error indicates the protocol has broken; retrying the
     * entire Blaze invocation would construct a new instance of the protocol and might work.
     */
    @org.junit.Test(timeout = TIMEOUT_MILLIS)
    @Throws(java.lang.Exception::class)
    fun testWrongAckShouldFailTheUpload() {
        fakeBesServer.sendOutOfOrderAcknowledgments()

        val transport: BuildEventServiceTransport =
            newBuildEventServiceTransport( /*publishLifecycleEvents=*/true)
        transport.sendBuildEvent(started)

        val exception: ExecutionException =
            org.junit.Assert.assertThrows<ExecutionException>(
                ExecutionException::class.java,
                org.junit.function.ThrowingRunnable { transport.close().get() })
        assertTransientError(exception, BuildProgress.Code.BES_UPLOAD_TIMEOUT_ERROR)
        Truth.assertThat(exception.message)
            .contains(
                ("The Build Event Protocol upload failed: not retrying publishBuildEvents:"
                        + " FAILED_PRECONDITION: expected ACK with seqNum=1 but received ACK with"
                        + " seqNum=2")
            )
    }

    /**
     * Don't ACK build events, and never half-close the stream from the server side thus forcing a
     * timeout on the client.
     */
    @org.junit.Test(timeout = TIMEOUT_MILLIS)
    @Throws(java.lang.Exception::class)
    fun testCloseTimeout() {
        fakeBesServer.setStreamEventPredicateAndResponseStatus(
            java.util.function.Predicate { req: PublishBuildToolEventStreamRequest? -> true },
            null
        )

        // Timeout 1 second after calling close()
        val transport: BuildEventServiceTransport =
            newBuildEventServiceTransport(
                createBesClient(),  /*publishLifecycleEvents=*/
                true,
                java.time.Duration.ofSeconds(1),
                LocalFilesArtifactUploader()
            )
        transport.sendBuildEvent(started)

        org.junit.Assert.assertThrows<java.util.concurrent.TimeoutException?>(
            java.util.concurrent.TimeoutException::class.java,
            org.junit.function.ThrowingRunnable {
                transport.close().get(transport.getTimeout().toMillis(), TimeUnit.MILLISECONDS)
            })
    }

    protected abstract fun createBesServer(): AbstractBuildEventRecorder

    @Throws(IOException::class)
    protected abstract fun createBesClient(): BuildEventServiceClient?

    @Throws(IOException::class)
    protected abstract fun createBesClient(serverPort: Int): BuildEventServiceClient?

    protected abstract fun makeVfsHashFunction(): DigestHashFunction?

    @Throws(IOException::class)
    private fun newBuildEventServiceTransport(publishLifecycleEvents: Boolean): BuildEventServiceTransport {
        return newBuildEventServiceTransport(
            createBesClient(), publishLifecycleEvents, java.time.Duration.ZERO, LocalFilesArtifactUploader()
        )
    }

    private fun newBuildEventServiceTransport(
        client: BuildEventServiceClient?,
        publishLifecycleEvents: Boolean,
        closeTimeout: java.time.Duration?,
        artifactUploader: BuildEventArtifactUploader?
    ): BuildEventServiceTransport {
        val besOptions: BuildEventServiceOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(BuildEventServiceOptions::class.java)
        besOptions.setBesTimeout(closeTimeout)
        besOptions.besLifecycleEvents = publishLifecycleEvents

        return Builder()
            .besOptions(besOptions) // Reduce exponential backoff sleep times to speed up testing
            .sleeper(
                { sleepMillis -> TimeUnit.MILLISECONDS.sleep(if (sleepMillis > 10) sleepMillis / 10 else sleepMillis) })
            .eventBus(eventBus)
            .besClient(client)
            .artifactGroupNamer(artifactGroupNamer)
            .localFileUploader(
                if (artifactUploader != null) artifactUploader else LocalFilesArtifactUploader()
            )
            .bepOptions(com.google.devtools.common.options.Options.getDefaults<O?>(BuildEventProtocolOptions::class.java))
            .clock(clock)
            .commandContext(COMMAND_CONTEXT)
            .commandStartTime(COMMAND_START_TIME)
            .build()
    }

    private class BuildEventWithFiles(files: MutableCollection<LocalFile>) : BuildEvent {
        private val files: MutableCollection<LocalFile>

        init {
            this.files = files
        }

        public override fun asStreamProto(converters: BuildEventContext): BuildEventStreamProtos.BuildEvent {
            val builder: NamedSetOfFiles.Builder = NamedSetOfFiles.newBuilder()
            for (file in files) {
                val uri: String? = converters.pathConverter().apply(file.path)
                if (uri != null) {
                    builder.addFiles(File.newBuilder().setName(file.path.getBaseName()).setUri(uri))
                }
            }
            return GenericBuildEvent.protoChaining(this).setNamedSetOfFiles(builder.build()).build()
        }

        val eventId: BuildEventId
            get() = BuildEventIdUtil.fromArtifactGroupName("list-of-files")

        public override fun referencedLocalFiles(): MutableCollection<LocalFile> {
            return files
        }

        val childrenEvents: MutableCollection<BuildEventId>
            get() = com.google.common.collect.ImmutableSet.of<BuildEventId?>()
    }

    private abstract class BuildEventArtifactUploaderWithRefCounting

        : io.netty.util.AbstractReferenceCounted(), BuildEventArtifactUploader {
        override fun deallocate() {}

        override fun touch(o: Any?): io.netty.util.ReferenceCounted {
            return this
        }
    }

    companion object {
        private const val TIMEOUT_MILLIS: Long = 20000

        private const val BUILD_REQUEST_ID = "feedbeef-dead-4321-beef-deaddeaddead"
        private const val BUILD_INVOCATION_ID = "feedbeef-dead-4444-beef-deaddeaddead"
        private const val COMMAND_NAME = "test"
        private val KEYWORDS: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>("foo=bar", "spam=eggs")
        private val COMMAND_START_TIME: Instant? = Instant.ofEpochMilli(500L)
        private val COMMAND_CONTEXT: CommandContext? = CommandContext.builder()
            .setBuildId(BUILD_REQUEST_ID)
            .setInvocationId(BUILD_INVOCATION_ID)
            .setAttemptNumber(1)
            .setKeywords(KEYWORDS)
            .setProjectId(null)
            .setCheckPrecedingLifecycleEvents(false)
            .build()

        private fun assertTransientError(e: java.lang.Exception, bpCode: BuildProgress.Code?) {
            assertExecutionException(e, ExitCode.TRANSIENT_BUILD_EVENT_SERVICE_UPLOAD_ERROR, bpCode)
        }

        private fun assertPersistentError(e: java.lang.Exception, bpCode: BuildProgress.Code?) {
            assertExecutionException(e, ExitCode.PERSISTENT_BUILD_EVENT_SERVICE_UPLOAD_ERROR, bpCode)
        }

        private fun assertExecutionException(
            e: java.lang.Exception, exitCode: ExitCode?, bpCode: BuildProgress.Code?
        ) {
            Truth.assertThat(e).hasCauseThat().isInstanceOf(AbruptExitException::class.java)
            val detailedExitCode: DetailedExitCode = (e.cause as AbruptExitException).getDetailedExitCode()
            val failureDetail: FailureDetail = detailedExitCode.getFailureDetail()
            assertThat(detailedExitCode.getExitCode()).isEqualTo(exitCode)
            assertThat(failureDetail.getBuildProgress().getCode()).isEqualTo(bpCode)
        }

        /** Utility method that produces a stateful predicate that matches a parameter only once.  */
        private fun <T> everyEventFailsOnce(): java.util.function.Predicate<T?> {
            return object : java.util.function.Predicate<T?> {
                private val alreadyMatched: MutableSet<T?> = HashSet<T?>()

                override fun test(o: T?): Boolean {
                    return alreadyMatched.add(o)
                }
            }
        }
    }
}
