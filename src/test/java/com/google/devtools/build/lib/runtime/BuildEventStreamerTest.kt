// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ActionEnvironment

/** Tests [BuildEventStreamer].  */
@RunWith(TestParameterInjector::class)
class BuildEventStreamerTest : FoundationTestCase() {
    private val artifactGroupNamer: CountingArtifactGroupNamer = CountingArtifactGroupNamer()
    private val transport = RecordingBuildEventTransport(artifactGroupNamer)

    private val streamer: BuildEventStreamer = Builder()
        .artifactGroupNamer(artifactGroupNamer)
        .buildEventTransports(com.google.common.collect.ImmutableSet.of<E?>(transport))
        .besStreamOptions(com.google.devtools.common.options.Options.getDefaults<O?>(BuildEventStreamOptions::class.java))
        .oomMessage(OOM_MESSAGE)
        .build()

    private class RecordingBuildEventTransport(namer: ArtifactGroupNamer) : BuildEventTransport {
        private val events: MutableList<BuildEvent> = java.util.ArrayList<BuildEvent>()
        private val eventsAsProtos: MutableList<BuildEventStreamProtos.BuildEvent> =
            java.util.ArrayList<BuildEventStreamProtos.BuildEvent>()
        private val artifactGroupNamer: ArtifactGroupNamer

        init {
            this.artifactGroupNamer = namer
        }

        public override fun name(): String {
            return this.javaClass.getSimpleName()
        }

        public override fun mayBeSlow(): Boolean {
            return false
        }

        val besUploadMode: BesUploadMode
            get() = BesUploadMode.WAIT_FOR_UPLOAD_COMPLETE

        @kotlin.jvm.Synchronized
        public override fun sendBuildEvent(event: BuildEvent) {
            events.add(event)
            try {
                eventsAsProtos.add(event.asStreamProto(getTestBuildEventContext(this.artifactGroupNamer)))
            } catch (e: java.lang.InterruptedException) {
                java.lang.Thread.currentThread().interrupt()
                throw java.lang.IllegalStateException("interrupts not supported in test instance")
            }
        }

        public override fun close(): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
            return com.google.common.util.concurrent.Futures.immediateFuture<java.lang.Void?>(null)
        }

        val uploader: BuildEventArtifactUploader?
            get() {
                throw java.lang.IllegalStateException()
            }

        fun getEvents(): MutableList<BuildEvent> {
            return events
        }

        val eventProtos: MutableList<BuildEventStreamProtos.BuildEvent>
            get() = eventsAsProtos
    }

    private class GenericOrderEvent(
        id: BuildEventId?,
        children: MutableCollection<BuildEventId?>?,
        after: MutableCollection<BuildEventId?>?
    ) : BuildEventWithOrderConstraint {
        private val id: BuildEventId?
        private val children: MutableCollection<BuildEventId?>?
        private val after: MutableCollection<BuildEventId?>?

        init {
            this.id = id
            this.children = children
            this.after = after
        }

        internal constructor(id: BuildEventId?, children: MutableCollection<BuildEventId?>?) : this(
            id,
            children,
            children
        )

        val eventId: BuildEventId?
            get() = id

        val childrenEvents: MutableCollection<BuildEventId>?
            get() = children

        public override fun asStreamProto(converters: BuildEventContext?): BuildEventStreamProtos.BuildEvent {
            return GenericBuildEvent.protoChaining(this).build()
        }

        public override fun postedAfter(): MutableCollection<BuildEventId?>? {
            return after
        }
    }

    private class GenericArtifactReportingEvent(
        id: BuildEventId?,
        children: MutableCollection<BuildEventId?>?,
        artifacts: MutableCollection<NestedSet<Artifact?>>
    ) : EventReportingArtifacts {
        private val id: BuildEventId?
        private val children: MutableCollection<BuildEventId?>?
        private val artifacts: MutableCollection<NestedSet<Artifact?>>

        init {
            this.id = id
            this.children = children
            this.artifacts = artifacts
        }

        internal constructor(id: BuildEventId?, artifacts: MutableCollection<NestedSet<Artifact?>>) : this(
            id,
            com.google.common.collect.ImmutableSet.of<BuildEventId?>(),
            artifacts
        )

        val eventId: BuildEventId?
            get() = id

        val childrenEvents: MutableCollection<BuildEventId>?
            get() = children

        public override fun reportedArtifacts(outputGroupFileModes: OutputGroupFileModes?): ReportedArtifacts {
            val importantInputMap: ActionInputMap = ActionInputMap(0)
            for (artifactSet in artifacts) {
                for (artifact in artifactSet.toList()) {
                    // This is good enough to make the tests pass because they don't care about the metadata.
                    importantInputMap.put(artifact, FileArtifactValue.MISSING_FILE_MARKER)
                }
            }
            return ReportedArtifacts(
                artifacts,
                CompletionContext(
                    ArtifactPathResolver.IDENTITY, importantInputMap,  /* expandFilesets= */false
                )
            )
        }

        public override fun asStreamProto(converters: BuildEventContext): BuildEventStreamProtos.BuildEvent {
            val builder: BuildEventStreamProtos.NamedSetOfFiles.Builder =
                BuildEventStreamProtos.NamedSetOfFiles.newBuilder()
            for (artifactset in artifacts) {
                builder.addFileSets(converters.artifactGroupNamer().apply(artifactset.toNode()))
            }
            return GenericBuildEvent.protoChaining(this).setNamedSetOfFiles(builder.build()).build()
        }
    }

    private class GenericConfigurationEvent(
        id: BuildEventId?,
        children: MutableCollection<BuildEventId?>?,
        configurations: MutableCollection<BuildEvent?>?
    ) : BuildEventWithConfiguration {
        private val id: BuildEventId?
        private val children: MutableCollection<BuildEventId?>?
        private val configurations: MutableCollection<BuildEvent?>?

        init {
            this.id = id
            this.children = children
            this.configurations = configurations
        }

        internal constructor(id: BuildEventId?, configuration: BuildEvent) : this(
            id,
            com.google.common.collect.ImmutableSet.of<BuildEventId?>(),
            com.google.common.collect.ImmutableSet.of<BuildEvent?>(configuration)
        )

        val eventId: BuildEventId?
            get() = id

        val childrenEvents: MutableCollection<BuildEventId>?
            get() = children

        public override fun getConfigurations(): MutableCollection<BuildEvent?>? {
            return configurations
        }

        public override fun asStreamProto(converters: BuildEventContext?): BuildEventStreamProtos.BuildEvent {
            return GenericBuildEvent.protoChaining(this).build()
        }
    }

    private class EventBusHandler {
        var transportSet: MutableSet<BuildEventTransport>? = null

        @com.google.common.eventbus.Subscribe
        fun transportsAnnounced(evt: AnnounceBuildEventTransportsEvent) {
            transportSet = Collections.synchronizedSet<T?>(HashSet<Any?>(evt.transports()))
        }

        @com.google.common.eventbus.Subscribe
        fun transportClosed(evt: BuildEventTransportClosedEvent) {
            transportSet!!.remove(evt.transport())
        }
    }

    @org.junit.Test(timeout = 5000)
    fun testSimpleStream() {
        // Verify that a well-formed event is passed through and that completion of the
        // build clears the pending progress-update event. However, there is no guarantee
        // on the order of the flushed events.
        // Additionally, assert that the actual last event has the last_message flag set.

        val handler = EventBusHandler()
        eventBus.register(handler)
        Truth.assertThat(handler.transportSet).isNull()

        eventBus.post(AnnounceBuildEventTransportsEvent(com.google.common.collect.ImmutableSet.of<E?>(transport)))

        val startEvent: BuildEvent =
            GenericBuildEvent(
                testId("Initial"),
                com.google.common.collect.ImmutableSet.of<E?>(
                    ProgressEvent.INITIAL_PROGRESS_UPDATE, BuildEventIdUtil.buildFinished()
                )
            )

        streamer.buildEvent(startEvent)

        assertThat(streamer.isClosed()).isFalse()
        val afterFirstEvent: MutableList<BuildEvent> = transport.getEvents()
        Truth.assertThat(afterFirstEvent).hasSize(1)
        assertThat(afterFirstEvent.get(0).eventId).isEqualTo(startEvent.eventId)
        Truth.assertThat(handler.transportSet).hasSize(1)

        streamer.buildEvent(BuildCompleteEvent(BuildResult(0)))

        assertThat(streamer.isClosed()).isTrue()
        eventBus.post(BuildEventTransportClosedEvent(transport))

        val finalStream: MutableList<BuildEvent> = transport.getEvents()
        Truth.assertThat(finalStream).hasSize(3)
        ProtoTruth.assertThat<com.google.protobuf.Message?>(
            com.google.common.collect.ImmutableSet.of<com.google.protobuf.Message?>(
                finalStream.get(1).eventId,
                finalStream.get(2).eventId
            )
        )
            .isEqualTo(
                com.google.common.collect.ImmutableSet.of<E?>(
                    BuildEventIdUtil.buildFinished(), ProgressEvent.INITIAL_PROGRESS_UPDATE
                )
            )

        // verify the "last_message" flag.
        assertThat(transport.eventProtos.get(0).getLastMessage()).isFalse()
        assertThat(transport.eventProtos.get(1).getLastMessage()).isFalse()
        assertThat(transport.eventProtos.get(2).getLastMessage()).isTrue()

        while (!handler.transportSet!!.isEmpty()) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100))
        }
    }

    @org.junit.Test
    fun testChaining() {
        // Verify that unannounced events are linked in with progress update events, assuming
        // a correctly formed initial event.
        val startEvent: BuildEvent =
            GenericBuildEvent(
                testId("Initial"), com.google.common.collect.ImmutableSet.of<E?>(ProgressEvent.INITIAL_PROGRESS_UPDATE)
            )
        val unexpectedEvent: BuildEvent =
            GenericBuildEvent(testId("unexpected"), com.google.common.collect.ImmutableSet.of<E?>())

        streamer.buildEvent(startEvent)
        streamer.buildEvent(unexpectedEvent)

        assertThat(streamer.isClosed()).isFalse()
        val eventsSeen: MutableList<BuildEvent> = transport.getEvents()
        Truth.assertThat(eventsSeen).hasSize(3)
        assertThat(eventsSeen.get(0).eventId).isEqualTo(startEvent.eventId)
        assertThat(eventsSeen.get(2).eventId).isEqualTo(unexpectedEvent.eventId)
        val linkEvent: BuildEvent = eventsSeen.get(1)
        assertThat(linkEvent.eventId).isEqualTo(ProgressEvent.INITIAL_PROGRESS_UPDATE)
        Truth.assertWithMessage("Unexpected events should be linked")
            .that(linkEvent.childrenEvents.contains(unexpectedEvent.eventId))
            .isTrue()
    }

    @org.junit.Test
    fun testBadInitialEvent() {
        // Verify that, if the initial event does not announce the initial progress update event,
        // the initial progress event is used instead to chain that event; in this way, new
        // progress updates can always be chained in.
        val unexpectedStartEvent: BuildEvent =
            GenericBuildEvent(testId("unexpected start"), com.google.common.collect.ImmutableSet.of<E?>())

        streamer.buildEvent(unexpectedStartEvent)

        val eventsSeen: MutableList<BuildEvent> = transport.getEvents()
        Truth.assertThat(eventsSeen).hasSize(2)
        assertThat(eventsSeen.get(1).eventId).isEqualTo(unexpectedStartEvent.eventId)
        val initial: BuildEvent = eventsSeen.get(0)
        assertThat(initial.eventId).isEqualTo(ProgressEvent.INITIAL_PROGRESS_UPDATE)
        Truth.assertWithMessage("Event should be linked")
            .that(initial.childrenEvents.contains(unexpectedStartEvent.eventId))
            .isTrue()

        // The initial event should also announce a new progress event; we test this
        // by streaming another unannounced event.
        val unexpectedEvent: BuildEvent =
            GenericBuildEvent(testId("unexpected"), com.google.common.collect.ImmutableSet.of<E?>())

        streamer.buildEvent(unexpectedEvent)

        assertThat(streamer.isClosed()).isFalse()
        val allEventsSeen: MutableList<BuildEvent> = transport.getEvents()
        Truth.assertThat(allEventsSeen).hasSize(4)
        assertThat(allEventsSeen.get(3).eventId).isEqualTo(unexpectedEvent.eventId)
        val secondLinkEvent: BuildEvent = allEventsSeen.get(2)
        Truth.assertWithMessage("Progress should have been announced")
            .that(initial.childrenEvents.contains(secondLinkEvent.eventId))
            .isTrue()
        Truth.assertWithMessage("Second event should be linked")
            .that(secondLinkEvent.childrenEvents.contains(unexpectedEvent.eventId))
            .isTrue()
    }

    @org.junit.Test
    fun testReferPastEvent() {
        // Verify that, if an event is refers to a previously done event, that duplicated
        // late-referenced event is not expected again.
        val startEvent: BuildEvent =
            GenericBuildEvent(
                testId("Initial"),
                com.google.common.collect.ImmutableSet.of<E?>(
                    ProgressEvent.INITIAL_PROGRESS_UPDATE, BuildEventIdUtil.buildFinished()
                )
            )
        val earlyEvent: BuildEvent =
            GenericBuildEvent(testId("unexpected"), com.google.common.collect.ImmutableSet.of<E?>())
        val lateReference: BuildEvent =
            GenericBuildEvent(
                testId("late reference"),
                com.google.common.collect.ImmutableSet.of<E?>(earlyEvent.eventId)
            )

        streamer.buildEvent(startEvent)
        streamer.buildEvent(earlyEvent)
        streamer.buildEvent(lateReference)
        streamer.buildEvent(BuildCompleteEvent(BuildResult(0)))

        assertThat(streamer.isClosed()).isTrue()
        val eventsSeen: MutableList<BuildEvent> = transport.getEvents()
        var earlyEventCount = 0
        for (event in eventsSeen) {
            if (event.eventId.equals(earlyEvent.eventId)) {
                earlyEventCount++
            }
        }
        // The early event should be reported precisely once.
        Truth.assertThat(earlyEventCount).isEqualTo(1)
    }

    @org.junit.Test
    fun testReordering() {
        // Verify that an event requiring to be posted after another one is indeed.
        val expectedId: BuildEventId = testId("the target")
        val startEvent: BuildEvent =
            GenericBuildEvent(
                testId("Initial"),
                com.google.common.collect.ImmutableSet.of<E?>(ProgressEvent.INITIAL_PROGRESS_UPDATE, expectedId)
            )
        val rootCause: BuildEvent =
            GenericBuildEvent(testId("failure event"), com.google.common.collect.ImmutableSet.of<E?>())
        val failedTarget: BuildEvent =
            GenericOrderEvent(expectedId, com.google.common.collect.ImmutableSet.of<BuildEventId?>(rootCause.eventId))

        streamer.buildEvent(startEvent)
        streamer.buildEvent(failedTarget)
        streamer.buildEvent(rootCause)

        assertThat(streamer.isClosed()).isFalse()
        val allEventsSeen: MutableList<BuildEvent> = transport.getEvents()
        Truth.assertThat(allEventsSeen).hasSize(4)
        assertThat(allEventsSeen.get(0).eventId).isEqualTo(startEvent.eventId)
        val linkEvent: BuildEvent = allEventsSeen.get(1)
        assertThat(linkEvent.eventId).isEqualTo(ProgressEvent.INITIAL_PROGRESS_UPDATE)
        assertThat(allEventsSeen.get(2).eventId).isEqualTo(rootCause.eventId)
        assertThat(allEventsSeen.get(3).eventId).isEqualTo(failedTarget.eventId)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConcurrency() {
        // Verify that we can blast the BuildEventStreamer with many build events in parallel without
        // violating internal consistency. The thread-safety under test is primarily sensitive to the
        // pendingEvents field constructed when there are ordering constraints, so we make sure to
        // include such ordering constraints in this test.
        val startEvent: BuildEvent =
            GenericBuildEvent(
                testId("Initial"),
                com.google.common.collect.ImmutableSet.of<E?>(
                    ProgressEvent.INITIAL_PROGRESS_UPDATE, BuildEventIdUtil.buildFinished()
                )
            )
        streamer.buildEvent(startEvent)

        val numThreads = 12
        val numEventsPerThread = 10000
        val totalEvents = numThreads * numEventsPerThread
        val idIndex: AtomicInteger = AtomicInteger()
        val pool: ThreadPoolExecutor =
            ThreadPoolExecutor(
                numThreads,
                numThreads,  /* keepAliveTime= */
                0,
                TimeUnit.SECONDS,  /* workQueue= */
                LinkedBlockingQueue<java.lang.Runnable?>()
            )

        for (i in 0..<numThreads) {
            pool.execute(
                java.lang.Runnable {
                    for (j in 0..<numEventsPerThread) {
                        val index: Int = idIndex.getAndIncrement()
                        // Arrange for half of the events to have an ordering constraint on the subsequent
                        // event. The ordering graph must avoid cycles.
                        val afterIndex = if (index % 2 == 0) (index + 1) % totalEvents else -1
                        streamer.buildEvent(indexOrderedBuildEvent(index, afterIndex))
                    }
                })
        }

        pool.shutdown()
        pool.awaitTermination(1, TimeUnit.DAYS)

        val lateId: BuildEventId = testId("late event")
        streamer.buildEvent(
            BuildCompleteEvent(
                BuildResult(0),
                com.google.common.collect.ImmutableList.of<BuildEventId>(lateId)
            )
        )
        assertThat(streamer.isClosed()).isFalse()
        streamer.buildEvent(GenericBuildEvent(lateId, com.google.common.collect.ImmutableSet.of<E?>()))
        assertThat(streamer.isClosed()).isTrue()

        val eventsSeen: MutableList<BuildEvent> = transport.getEvents()
        assertThat(eventsSeen.get(0).eventId).isEqualTo(startEvent.eventId)
        Truth.assertThat(eventsSeen).hasSize(4 + totalEvents * 2)
    }

    // Re-enable this "test" for ad-hoc benchmarking of many concurrent build events.
    @Ignore
    @Throws(java.lang.Exception::class)
    fun concurrencyBenchmark() {
        var time: Long = 0
        for (iteration in 0..2) {
            val watch: com.google.common.base.Stopwatch = com.google.common.base.Stopwatch.createStarted()

            val startEvent: BuildEvent =
                GenericBuildEvent(
                    testId("Initial"),
                    com.google.common.collect.ImmutableSet.of<E?>(
                        ProgressEvent.INITIAL_PROGRESS_UPDATE, BuildEventIdUtil.buildFinished()
                    )
                )
            streamer.buildEvent(startEvent)

            val numThreads = 12
            val numEventsPerThread = 100000
            val totalEvents = numThreads * numEventsPerThread
            val idIndex: AtomicInteger = AtomicInteger()
            val pool: ThreadPoolExecutor =
                ThreadPoolExecutor(
                    numThreads, numThreads, 0, TimeUnit.SECONDS, LinkedBlockingQueue<java.lang.Runnable?>()
                )

            for (i in 0..<numThreads) {
                pool.execute(
                    java.lang.Runnable {
                        for (j in 0..<numEventsPerThread) {
                            val index: Int = idIndex.getAndIncrement()
                            // Arrange for half of the events to have an ordering constraint on the subsequent
                            // event. The ordering graph must avoid cycles.
                            val afterIndex = if (index % 2 == 0) (index + 1) % totalEvents else -1
                            streamer.buildEvent(indexOrderedBuildEvent(index, afterIndex))
                        }
                    })
            }

            pool.shutdown()
            pool.awaitTermination(1, TimeUnit.DAYS)
            watch.stop()

            time += watch.elapsed().toMillis()

            val lateId: BuildEventId = testId("late event")
            streamer.buildEvent(
                BuildCompleteEvent(
                    BuildResult(0),
                    com.google.common.collect.ImmutableList.of<BuildEventId>(lateId)
                )
            )
            assertThat(streamer.isClosed()).isFalse()
            streamer.buildEvent(GenericBuildEvent(lateId, com.google.common.collect.ImmutableSet.of<E?>()))
            assertThat(streamer.isClosed()).isTrue()
        }

        java.lang.System.err.println()
        java.lang.System.err.println("=============================================================")
        java.lang.System.err.println("Concurrent performance of BEP build event processing: " + time + "ms")
        java.lang.System.err.println("=============================================================")
    }

    @org.junit.Test
    fun testMissingPrerequisites() {
        // Verify that an event where the prerequisite is never coming till the end of
        // the build still gets posted, with the prerequisite aborted.
        val expectedId: BuildEventId = testId("the target")

        val startEvent: BuildEvent =
            GenericBuildEvent(
                testId("Initial"),
                com.google.common.collect.ImmutableSet.of<E?>(
                    ProgressEvent.INITIAL_PROGRESS_UPDATE,
                    expectedId,
                    BuildEventIdUtil.buildFinished()
                )
            )
        val rootCauseId: BuildEventId = testId("failure event")
        val failedTarget: BuildEvent =
            GenericOrderEvent(expectedId, com.google.common.collect.ImmutableSet.of<BuildEventId?>(rootCauseId))

        streamer.buildEvent(startEvent)
        streamer.buildEvent(failedTarget)
        streamer.buildEvent(BuildCompleteEvent(BuildResult(0)))

        assertThat(streamer.isClosed()).isTrue()
        val allEventsSeen: MutableList<BuildEvent> = transport.getEvents()
        Truth.assertThat(allEventsSeen).hasSize(6)
        assertThat(allEventsSeen.get(0).eventId).isEqualTo(startEvent.eventId)
        assertThat(allEventsSeen.get(1).eventId).isEqualTo(BuildEventIdUtil.buildFinished())
        val linkEvent: BuildEvent = allEventsSeen.get(2)
        assertThat(linkEvent.eventId).isEqualTo(ProgressEvent.INITIAL_PROGRESS_UPDATE)
        assertThat(allEventsSeen.get(3).eventId).isEqualTo(rootCauseId)
        assertThat(allEventsSeen.get(4).eventId).isEqualTo(failedTarget.eventId)
    }

    @org.junit.Test
    fun testVeryFirstEventNeedsToWait() {
        // Verify that we can handle an first event waiting for another event.
        val initialId: BuildEventId = testId("Initial")
        val waitId: BuildEventId = testId("Waiting for initial event")
        val startEvent: BuildEvent =
            GenericBuildEvent(
                initialId, com.google.common.collect.ImmutableSet.of<E?>(ProgressEvent.INITIAL_PROGRESS_UPDATE, waitId)
            )
        val waitingForStart: BuildEvent =
            GenericOrderEvent(
                waitId,
                com.google.common.collect.ImmutableSet.of<BuildEventId?>(),
                com.google.common.collect.ImmutableSet.of<BuildEventId?>(initialId)
            )

        streamer.buildEvent(waitingForStart)
        streamer.buildEvent(startEvent)

        assertThat(streamer.isClosed()).isFalse()
        val allEventsSeen: MutableList<BuildEvent> = transport.getEvents()
        Truth.assertThat(allEventsSeen).hasSize(2)
        assertThat(allEventsSeen.get(0).eventId).isEqualTo(startEvent.eventId)
        assertThat(allEventsSeen.get(1).eventId).isEqualTo(waitingForStart.eventId)
    }

    private fun makeArtifact(pathString: String?): Artifact {
        val path: Path? = outputBase.getRelative(PathFragment.create(pathString))
        return ActionsTestUtil.createArtifact(
            ArtifactRoot.asSourceRoot(Root.fromPath(outputBase)), path
        )
    }

    @org.junit.Test
    fun testReportedArtifacts() {
        // Verify that reported artifacts are correctly unfolded into the stream
        val startEvent: BuildEvent =
            GenericBuildEvent(
                testId("Initial"), com.google.common.collect.ImmutableSet.of<E?>(ProgressEvent.INITIAL_PROGRESS_UPDATE)
            )

        val a: Artifact = makeArtifact("path/a")
        val b: Artifact = makeArtifact("path/b")
        val c: Artifact = makeArtifact("path/c")
        val innerGroup: NestedSet<Artifact?>? =
            NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ().add(a).add(b).build()
        val group: NestedSet<Artifact?> =
            NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ().addTransitive(innerGroup).add(c).build()
        val reportingArtifacts: BuildEvent =
            GenericArtifactReportingEvent(
                testId("reporting"),
                com.google.common.collect.ImmutableSet.of<NestedSet<Artifact?>?>(group)
            )

        streamer.buildEvent(startEvent)
        streamer.buildEvent(reportingArtifacts)

        assertThat(streamer.isClosed()).isFalse()
        val allEventsSeen: MutableList<BuildEvent> = transport.getEvents()
        val eventProtos: MutableList<BuildEventStreamProtos.BuildEvent> = transport.eventProtos
        Truth.assertThat(allEventsSeen).hasSize(7)
        assertThat(allEventsSeen.get(0).eventId).isEqualTo(startEvent.eventId)
        assertThat(allEventsSeen.get(1).eventId).isEqualTo(ProgressEvent.INITIAL_PROGRESS_UPDATE)
        val firstSetDirects: MutableList<BuildEventStreamProtos.File>? =
            eventProtos.get(2).getNamedSetOfFiles().getFilesList()
        Truth.assertThat(firstSetDirects).hasSize(2)
        Truth.assertThat(
            com.google.common.collect.ImmutableSet.of<E?>(
                firstSetDirects!!.get(0).getUri(),
                firstSetDirects.get(1).getUri()
            )
        )
            .isEqualTo(com.google.common.collect.ImmutableSet.of<E?>(a.getPath().toString(), b.getPath().toString()))
        val secondSetTransitives: MutableList<NamedSetOfFilesId>? =
            eventProtos.get(4).getNamedSetOfFiles().getFileSetsList()
        Truth.assertThat(secondSetTransitives).hasSize(1)
        assertThat(secondSetTransitives!!.get(0)).isEqualTo(eventProtos.get(2).getId().getNamedSet())
        val reportedArtifactSets: MutableList<NamedSetOfFilesId>? =
            eventProtos.get(6).getNamedSetOfFiles().getFileSetsList()
        Truth.assertThat(reportedArtifactSets).hasSize(1)
        assertThat(reportedArtifactSets!!.get(0)).isEqualTo(eventProtos.get(4).getId().getNamedSet())
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun testArtifactSetsPrecedeReportingEvent() {
        // Verify that reported artifacts appear as named_set_of_files before their ID is referenced by
        // a reporting event.
        val startEvent: BuildEvent =
            GenericBuildEvent(
                testId("Initial"), com.google.common.collect.ImmutableSet.of<E?>(ProgressEvent.INITIAL_PROGRESS_UPDATE)
            )

        // Prepare a dense NestedSet DAG with lots of shared references.
        val baseSets: MutableList<NestedSet<Artifact?>?> = java.util.ArrayList<NestedSet<Artifact?>?>()
        baseSets.add(NestedSetBuilder.create(Order.STABLE_ORDER, makeArtifact("path/a")))
        baseSets.add(NestedSetBuilder.create(Order.STABLE_ORDER, makeArtifact("path/b")))
        baseSets.add(NestedSetBuilder.create(Order.STABLE_ORDER, makeArtifact("path/c")))
        baseSets.add(NestedSetBuilder.create(Order.STABLE_ORDER, makeArtifact("path/d")))
        val depth2Sets: MutableList<NestedSet<Artifact?>?> = java.util.ArrayList<NestedSet<Artifact?>?>()
        for (i in baseSets.indices) {
            depth2Sets.add(
                NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
                    .addTransitive(baseSets.get(i))
                    .addTransitive(baseSets.get((i + 1) % baseSets.size))
                    .build()
            )
        }
        val depth3Sets: MutableList<NestedSet<Artifact?>?> = java.util.ArrayList<NestedSet<Artifact?>?>()
        for (i in depth2Sets.indices) {
            depth3Sets.add(
                NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
                    .addTransitive(depth2Sets.get(i))
                    .addTransitive(depth2Sets.get((i + 1) % depth2Sets.size))
                    .build()
            )
        }
        val depth4Sets: MutableList<NestedSet<Artifact?>?> = java.util.ArrayList<NestedSet<Artifact?>?>()
        for (i in depth3Sets.indices) {
            depth4Sets.add(
                NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
                    .addTransitive(depth3Sets.get(i))
                    .addTransitive(depth3Sets.get((i + 1) % depth3Sets.size))
                    .build()
            )
        }
        val numEvents = 20
        val eventsToPost: MutableList<BuildEvent?> = java.util.ArrayList<BuildEvent?>()
        for (i in 0..<numEvents) {
            eventsToPost.add(
                GenericArtifactReportingEvent(
                    testId("reporting" + i),
                    com.google.common.collect.ImmutableSet.of<NestedSet<Artifact?>?>(depth4Sets.get(i % depth4Sets.size))
                )
            )
        }

        streamer.buildEvent(startEvent)
        // Publish `numEvents` different events that all report the same NamedSet of artifacts on
        // `numEvents` different threads. Use a CyclicBarrier and latch to ensure:
        //
        // 1. all threads have started, before:
        // 2. all threads send their event, before:
        // 3. verifying the recorded events.
        val readyToPublishLatch: CyclicBarrier = CyclicBarrier(numEvents)
        val donePublishingLatch: CountDownLatch = CountDownLatch(numEvents)
        for (i in 0..<numEvents) {
            val reportingArtifacts: BuildEvent? = eventsToPost.get(i)
            java.lang.Thread(
                java.lang.Runnable {
                    try {
                        readyToPublishLatch.await()
                        streamer.buildEvent(reportingArtifacts)
                    } catch (e: java.lang.InterruptedException) {
                        throw java.lang.RuntimeException(e)
                    } catch (e: BrokenBarrierException) {
                        throw java.lang.RuntimeException(e)
                    }
                    donePublishingLatch.countDown()
                })
                .start()
        }
        donePublishingLatch.await()

        assertThat(streamer.isClosed()).isFalse()
        val allEventsSeen: MutableList<BuildEvent> = transport.getEvents()
        val eventProtos: MutableList<BuildEventStreamProtos.BuildEvent> = transport.eventProtos
        // Each GenericArtifactReportingEvent and NamedArtifactGroup event has a corresponding Progress
        // event posted immediately before.
        Truth.assertThat(allEventsSeen)
            .hasSize(1 + ((numEvents + baseSets.size + depth2Sets.size + depth3Sets.size) * 2))
        assertThat(allEventsSeen.get(0).eventId).isEqualTo(startEvent.eventId)
        // Verify that each named_set_of_files event is sent before all of the events that report that
        // named_set.
        val seenFileSets: MutableSet<String> = HashSet<String>()
        for (i in 1..<eventProtos.size) {
            val buildEvent: BuildEventStreamProtos.BuildEvent = eventProtos.get(i)
            if (buildEvent.getId().hasNamedSet()) {
                // These are the separately-posted contents of reported artifacts.
                seenFileSets.add(buildEvent.getId().getNamedSet().getId())
                for (nestedSetId in buildEvent.getNamedSetOfFiles().getFileSetsList()) {
                    Truth.assertThat(seenFileSets).contains(nestedSetId.getId())
                }
            } else if (buildEvent.getId().hasUnknown()) {
                // These are the GenericArtifactReportingEvent that report artifacts.
                for (nestedSetId in buildEvent.getNamedSetOfFiles().getFileSetsList()) {
                    Truth.assertThat(seenFileSets).contains(nestedSetId.getId())
                }
            }
        }
    }

    @org.junit.Test
    fun testStdoutReported() {
        // Verify that stdout and stderr are reported in the build-event stream on progress
        // events.
        val outErr: BuildEventStreamer.OutErrProvider =
            Mockito.mock<BuildEventStreamer.OutErrProvider>(BuildEventStreamer.OutErrProvider::class.java)
        val stdoutMsg = "Some text that was written to stdout."
        val stderrMsg = "The UI text that bazel wrote to stderr."
        Mockito.`when`<Any?>(outErr.out).thenReturn(com.google.common.collect.ImmutableList.of<String?>(stdoutMsg))
        Mockito.`when`<Any?>(outErr.err).thenReturn(com.google.common.collect.ImmutableList.of<String?>(stderrMsg))
        val startEvent: BuildEvent =
            GenericBuildEvent(
                testId("Initial"), com.google.common.collect.ImmutableSet.of<E?>(ProgressEvent.INITIAL_PROGRESS_UPDATE)
            )
        val unexpectedEvent: BuildEvent =
            GenericBuildEvent(testId("unexpected"), com.google.common.collect.ImmutableSet.of<E?>())

        streamer.registerOutErrProvider(outErr)
        streamer.buildEvent(startEvent)
        streamer.buildEvent(unexpectedEvent)

        assertThat(streamer.isClosed()).isFalse()
        val eventsSeen: MutableList<BuildEvent> = transport.getEvents()
        Truth.assertThat(eventsSeen).hasSize(3)
        assertThat(eventsSeen.get(0).eventId).isEqualTo(startEvent.eventId)
        assertThat(eventsSeen.get(2).eventId).isEqualTo(unexpectedEvent.eventId)
        val linkEvent: BuildEvent = eventsSeen.get(1)
        val linkEventProto: BuildEventStreamProtos.BuildEvent = transport.eventProtos.get(1)
        assertThat(linkEvent.eventId).isEqualTo(ProgressEvent.INITIAL_PROGRESS_UPDATE)
        Truth.assertWithMessage("Unexpected events should be linked")
            .that(linkEvent.childrenEvents.contains(unexpectedEvent.eventId))
            .isTrue()
        assertThat(linkEventProto.getProgress().getStdout()).isEqualTo(stdoutMsg)
        assertThat(linkEventProto.getProgress().getStderr()).isEqualTo(stderrMsg)

        // As there is only one progress event, the OutErrProvider should be queried
        // only once for stdout and stderr.
        Mockito.verify<Any?>(outErr, Mockito.times(1)).out
        Mockito.verify<Any?>(outErr, Mockito.times(1)).err
    }

    @org.junit.Test
    fun testStdoutReportedAfterCrash() {
        // Verify that stdout and stderr are reported in the build-event stream on progress
        // events.
        val outErr: BuildEventStreamer.OutErrProvider =
            Mockito.mock<BuildEventStreamer.OutErrProvider>(BuildEventStreamer.OutErrProvider::class.java)
        val stdoutMsg = "Some text that was written to stdout."
        val stderrMsg = "The UI text that bazel wrote to stderr."
        Mockito.`when`<Any?>(outErr.out).thenReturn(com.google.common.collect.ImmutableList.of<String?>(stdoutMsg))
        Mockito.`when`<Any?>(outErr.err).thenReturn(com.google.common.collect.ImmutableList.of<String?>(stderrMsg))
        val startEvent: BuildEvent =
            GenericBuildEvent(
                testId("Initial"), com.google.common.collect.ImmutableSet.of<E?>(ProgressEvent.INITIAL_PROGRESS_UPDATE)
            )

        streamer.registerOutErrProvider(outErr)
        streamer.buildEvent(startEvent)
        // Simulate a crash with an abrupt call to #closeOnAbort().
        streamer.closeOnAbort(AbortReason.INTERNAL)
        assertThat(streamer.isClosed()).isTrue()

        val eventsSeen: MutableList<BuildEvent> = transport.getEvents()
        Truth.assertThat(eventsSeen).hasSize(2)
        assertThat(eventsSeen.get(0).eventId).isEqualTo(startEvent.eventId)
        val linkEvent: BuildEvent = eventsSeen.get(1)
        val linkEventProto: BuildEventStreamProtos.BuildEvent = transport.eventProtos.get(1)
        assertThat(linkEvent.eventId).isEqualTo(ProgressEvent.INITIAL_PROGRESS_UPDATE)
        assertThat(linkEventProto.getProgress().getStdout()).isEqualTo(stdoutMsg)
        assertThat(linkEventProto.getProgress().getStderr()).isEqualTo(stderrMsg)

        // As there is only one progress event, the OutErrProvider should be queried
        // only once for stdout and stderr.
        Mockito.verify<Any?>(outErr, Mockito.times(1)).out
        Mockito.verify<Any?>(outErr, Mockito.times(1)).err
    }

    @org.junit.Test
    fun testConsumeAsPairs() {
        Truth.assertThat(
            consumeToLists<Int?>(
                com.google.common.collect.ImmutableList.of<Int?>(1, 2, 3),
                com.google.common.collect.ImmutableList.of<Int?>(4, 5, 6)
            )
        )
            .containsExactly(
                com.google.common.collect.ImmutableList.of<E?>(
                    Pair.of(1, null),
                    Pair.of(2, null),
                    Pair.of(3, 4),
                    Pair.of(null, 5)
                ),
                com.google.common.collect.ImmutableList.of<E?>(Pair.of(null, 6))
            )
            .inOrder()

        Truth.assertThat(
            consumeToLists<Any?>(
                com.google.common.collect.ImmutableList.of<Any?>(),
                com.google.common.collect.ImmutableList.of<Any?>()
            )
        )
            .containsExactly(
                com.google.common.collect.ImmutableList.of<Any?>(),
                com.google.common.collect.ImmutableList.of<E?>(Pair.of(null, null))
            )
            .inOrder()

        Truth.assertThat(
            consumeToLists<Int?>(
                com.google.common.collect.ImmutableList.of<Int?>(1),
                com.google.common.collect.ImmutableList.of<Int?>(2)
            )
        )
            .containsExactly(
                com.google.common.collect.ImmutableList.of<Any?>(),
                com.google.common.collect.ImmutableList.of<E?>(Pair.of(1, 2))
            )
            .inOrder()

        Truth.assertThat(
            consumeToLists<Int?>(
                com.google.common.collect.ImmutableList.of<Int?>(1),
                com.google.common.collect.ImmutableList.of<Int?>(2, 3)
            )
        )
            .containsExactly(
                com.google.common.collect.ImmutableList.of<E?>(Pair.of(1, 2)),
                com.google.common.collect.ImmutableList.of<E?>(Pair.of(null, 3))
            )
            .inOrder()

        Truth.assertThat(
            consumeToLists<Int?>(
                com.google.common.collect.ImmutableList.of<Int?>(1, 2),
                com.google.common.collect.ImmutableList.of<Int?>()
            )
        )
            .containsExactly(
                com.google.common.collect.ImmutableList.of<E?>(Pair.of(1, null)),
                com.google.common.collect.ImmutableList.of<E?>(Pair.of(2, null))
            )
            .inOrder()

        Truth.assertThat(
            consumeToLists<Int?>(
                com.google.common.collect.ImmutableList.of<Int?>(),
                com.google.common.collect.ImmutableList.of<Int?>(1)
            )
        )
            .containsExactly(
                com.google.common.collect.ImmutableList.of<Any?>(),
                com.google.common.collect.ImmutableList.of<E?>(Pair.of(null, 1))
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReportedConfigurations() {
        // Verify that configuration events are posted, but only once.
        val startEvent: BuildEvent =
            GenericBuildEvent(
                testId("Initial"), com.google.common.collect.ImmutableSet.of<E?>(ProgressEvent.INITIAL_PROGRESS_UPDATE)
            )
        val configuration: BuildConfigurationValue = makeTestingBuildConfigurationValue()
        val firstWithConfiguration: BuildEvent =
            GenericConfigurationEvent(testId("first"), configuration.toBuildEvent())
        val secondWithConfiguration: BuildEvent =
            GenericConfigurationEvent(testId("second"), configuration.toBuildEvent())

        streamer.buildEvent(startEvent)
        streamer.buildEvent(firstWithConfiguration)
        streamer.buildEvent(secondWithConfiguration)

        assertThat(streamer.isClosed()).isFalse()
        val allEventsSeen: MutableList<BuildEvent> = transport.getEvents()
        Truth.assertThat(allEventsSeen).hasSize(7)
        assertThat(allEventsSeen.get(0).eventId).isEqualTo(startEvent.eventId)
        assertThat(allEventsSeen.get(1).eventId).isEqualTo(ProgressEvent.INITIAL_PROGRESS_UPDATE)
        assertThat(allEventsSeen.get(2)).isEqualTo(configuration.toBuildEvent())
        assertThat(allEventsSeen.get(3).eventId).isEqualTo(BuildEventIdUtil.progressId(1))
        assertThat(allEventsSeen.get(4)).isEqualTo(firstWithConfiguration)
        assertThat(allEventsSeen.get(5).eventId).isEqualTo(BuildEventIdUtil.progressId(2))
        assertThat(allEventsSeen.get(6)).isEqualTo(secondWithConfiguration)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReportedConfigurations_concurrent() {
        // Verify that configuration events are posted, but only once.
        val startEvent: BuildEvent =
            GenericBuildEvent(
                testId("Initial"), com.google.common.collect.ImmutableSet.of<E?>(ProgressEvent.INITIAL_PROGRESS_UPDATE)
            )
        val configuration: BuildConfigurationValue = makeTestingBuildConfigurationValue()

        val numEvents = 100
        val eventsToPost: MutableList<BuildEvent?> = java.util.ArrayList<BuildEvent?>()
        for (i in 0..<numEvents) {
            eventsToPost.add(
                GenericConfigurationEvent(testId("has_config_" + i), configuration.toBuildEvent())
            )
        }

        streamer.buildEvent(startEvent)
        // Publish `numEvents` different events that all report the same configuration on `numEvents`
        // different threads. Use a CyclicBarrier and latch to ensure:
        //
        // 1. all threads have started, before:
        // 2. all threads send their event, before:
        // 3. verifying the recorded events.
        val readyToPublishLatch: CyclicBarrier = CyclicBarrier(numEvents)
        val donePublishingLatch: CountDownLatch = CountDownLatch(numEvents)
        for (i in 0..<numEvents) {
            val hasConfigEvent: BuildEvent? = eventsToPost.get(i)
            java.lang.Thread(
                java.lang.Runnable {
                    try {
                        readyToPublishLatch.await()
                        streamer.buildEvent(hasConfigEvent)
                    } catch (e: java.lang.InterruptedException) {
                        throw java.lang.RuntimeException(e)
                    } catch (e: BrokenBarrierException) {
                        throw java.lang.RuntimeException(e)
                    }
                    donePublishingLatch.countDown()
                })
                .start()
        }
        donePublishingLatch.await()

        assertThat(streamer.isClosed()).isFalse()

        val allEventsSeen: MutableList<BuildEvent> = transport.getEvents()

        // Two events for each GenericConfigurationEvent: a progress event announcing it and the
        // actual GenericConfigurationEvent itself.
        Truth.assertThat(allEventsSeen).hasSize(3 + (numEvents * 2))
        assertThat(allEventsSeen.get(0).eventId).isEqualTo(startEvent.eventId)
        assertThat(allEventsSeen.get(1).eventId).isEqualTo(ProgressEvent.INITIAL_PROGRESS_UPDATE)
        assertThat(allEventsSeen.get(2)).isEqualTo(configuration.toBuildEvent())
        for (idx in 3..<allEventsSeen.size) {
            assertThat(allEventsSeen.get(idx).eventId.getIdCase())
                .isNotEqualTo(IdCase.CONFIGURATION)
        }
    }

    @org.junit.Test
    fun testEarlyFlush() {
        // Verify that the streamer can handle early calls to flush() and still correctly
        // reports stdout and stderr in the build-event stream.
        val outErr: BuildEventStreamer.OutErrProvider =
            Mockito.mock<BuildEventStreamer.OutErrProvider>(BuildEventStreamer.OutErrProvider::class.java)
        val firstStdoutMsg = "Some text that was written to stdout."
        val firstStderrMsg = "The UI text that bazel wrote to stderr."
        val secondStdoutMsg = "More text that was written to stdout, still before the start event."
        val secondStderrMsg = "More text written to stderr, still before the start event."
        Mockito.`when`<Any?>(outErr.out)
            .thenReturn(com.google.common.collect.ImmutableList.of<String?>(firstStdoutMsg))
            .thenReturn(com.google.common.collect.ImmutableList.of<String?>(secondStdoutMsg))
        Mockito.`when`<Any?>(outErr.err)
            .thenReturn(com.google.common.collect.ImmutableList.of<String?>(firstStderrMsg))
            .thenReturn(com.google.common.collect.ImmutableList.of<String?>(secondStderrMsg))
        val startEvent: BuildEvent =
            GenericBuildEvent(
                testId("Initial"), com.google.common.collect.ImmutableSet.of<E?>(ProgressEvent.INITIAL_PROGRESS_UPDATE)
            )

        streamer.registerOutErrProvider(outErr)
        streamer.flush()
        streamer.flush()
        streamer.buildEvent(startEvent)

        assertThat(streamer.isClosed()).isFalse()
        val eventsSeen: MutableList<BuildEvent> = transport.getEvents()
        Truth.assertThat(eventsSeen).hasSize(3)
        assertThat(eventsSeen.get(0).eventId).isEqualTo(startEvent.eventId)
        val progressEvent: BuildEvent = eventsSeen.get(1)
        assertThat(progressEvent.eventId).isEqualTo(ProgressEvent.INITIAL_PROGRESS_UPDATE)
        val progressEventProto: BuildEventStreamProtos.BuildEvent = transport.eventProtos.get(1)
        assertThat(progressEventProto.getProgress().getStdout()).isEqualTo(firstStdoutMsg)
        assertThat(progressEventProto.getProgress().getStderr()).isEqualTo(firstStderrMsg)
        val secondProgressEventProto: BuildEventStreamProtos.BuildEvent = transport.eventProtos.get(2)
        assertThat(secondProgressEventProto.getProgress().getStdout()).isEqualTo(secondStdoutMsg)
        assertThat(secondProgressEventProto.getProgress().getStderr()).isEqualTo(secondStderrMsg)

        // As there is only one progress event, the OutErrProvider should be queried
        // only once per flush() for stdout and stderr.
        Mockito.verify<Any?>(outErr, Mockito.times(2)).out
        Mockito.verify<Any?>(outErr, Mockito.times(2)).err
    }

    @org.junit.Test
    fun testChunkedFlush() {
        // Verify that the streamer calls to flush() that return multiple chunked buffers.
        val outErr: BuildEventStreamer.OutErrProvider =
            Mockito.mock<BuildEventStreamer.OutErrProvider>(BuildEventStreamer.OutErrProvider::class.java)
        val firstStdoutMsg = "Some text that was written to stdout."
        val firstStderrMsg = "The UI text that bazel wrote to stderr."
        val secondStdoutMsg = "More text that was written to stdout, still before the start event."
        val secondStderrMsg = "More text written to stderr, still before the start event."
        Mockito.`when`<Any?>(outErr.out)
            .thenReturn(com.google.common.collect.ImmutableList.of<String?>(firstStdoutMsg, secondStdoutMsg))
        Mockito.`when`<Any?>(outErr.err)
            .thenReturn(com.google.common.collect.ImmutableList.of<String?>(firstStderrMsg, secondStderrMsg))
        val startEvent: BuildEvent =
            GenericBuildEvent(
                testId("Initial"), com.google.common.collect.ImmutableSet.of<E?>(ProgressEvent.INITIAL_PROGRESS_UPDATE)
            )

        streamer.registerOutErrProvider(outErr)
        streamer.buildEvent(startEvent)
        streamer.flush()

        assertThat(streamer.isClosed()).isFalse()
        val eventsSeen: MutableList<BuildEvent> = transport.getEvents()
        Truth.assertThat(eventsSeen).hasSize(4)
        assertThat(eventsSeen.get(0).eventId).isEqualTo(startEvent.eventId)

        // Expect to find 3 progress messages: (firstStdout, ""), (secondStdout, firstStderr),
        // ("", secondStdErr). Assuming UIs display stdout first, this maintains ordering.
        val progressEvent: BuildEvent = eventsSeen.get(1)
        assertThat(progressEvent.eventId).isEqualTo(ProgressEvent.INITIAL_PROGRESS_UPDATE)
        val progressEventProto: BuildEventStreamProtos.BuildEvent = transport.eventProtos.get(1)
        assertThat(progressEventProto.getProgress().getStdout()).isEqualTo(firstStdoutMsg)
        assertThat(progressEventProto.getProgress().getStderr()).isEmpty()

        val secondProgressEventProto: BuildEventStreamProtos.BuildEvent = transport.eventProtos.get(2)
        assertThat(secondProgressEventProto.getProgress().getStdout()).isEqualTo(secondStdoutMsg)
        assertThat(secondProgressEventProto.getProgress().getStderr()).isEqualTo(firstStderrMsg)

        val thirdProgressEventProto: BuildEventStreamProtos.BuildEvent = transport.eventProtos.get(3)
        assertThat(thirdProgressEventProto.getProgress().getStdout()).isEmpty()
        assertThat(thirdProgressEventProto.getProgress().getStderr()).isEqualTo(secondStderrMsg)

        // The OutErrProvider should be queried only once per flush().
        Mockito.verify<Any?>(outErr, Mockito.times(1)).out
        Mockito.verify<Any?>(outErr, Mockito.times(1)).err
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testFlushPreservesStdoutStderrOrder(
        @TestParameter("5", "30", "10000") maxBufferedLength: Int,
        @TestParameter("5", "30", "10000") maxChunkSize: Int
    ) {
        val stdout: SynchronizedOutputStream =
            SynchronizedOutputStream(maxBufferedLength, maxChunkSize,  /* isStderr= */false)
        val stderr: SynchronizedOutputStream =
            SynchronizedOutputStream(maxBufferedLength, maxChunkSize,  /* isStderr= */true)
        val outErr: BuildEventStreamer.OutErrProvider? =
            object : OutErrProvider() {
                val out: Iterable<String?>
                    get() = stdout.readAndReset()

                val err: Iterable<String?>
                    get() = stderr.readAndReset()
            }
        streamer.registerOutErrProvider(outErr)
        stdout.registerStreamer(streamer)
        stderr.registerStreamer(streamer)

        val startEvent: GenericBuildEvent =
            GenericBuildEvent(
                testId("Initial"), com.google.common.collect.ImmutableSet.of<E?>(ProgressEvent.INITIAL_PROGRESS_UPDATE)
            )
        streamer.buildEvent(startEvent)

        stderr.write("[0 / 3] 3 actions running\n".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        stderr.write("INFO: From Executing genrule //:1:\n".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        stdout.write("Hello from genrule //:1 on stdout\n".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        stderr.write("Hello from genrule //:1 on stderr\n".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        stderr.write("[1 / 3] 2 actions running\n".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        stderr.write("INFO: From Executing genrule //:2:\n".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        stdout.write("Hello from genrule //:2 on stderr\n".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        stderr.write("Hello from genrule //:2 on stdout\n".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        stderr.write("[2 / 3] 1 actions running\n".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        stderr.write("INFO: From Executing genrule //:3:\n".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        stdout.write("Hello from genrule //:3 on stdout\n".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        stderr.write("Hello from genrule //:3 on stderr\n".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        stdout.write("Hello again from genrule //:3 on stdout\n".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        stderr.write("INFO: Build completed successfully, 3 total actions\n".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        streamer.close()

        val reconstructedOutput: String? =
            transport.eventProtos.stream()
                .map<Any?>(BuildEventStreamProtos.BuildEvent::getProgress)
                .flatMap<Any?> { progress: Any? ->
                    java.util.stream.Stream.of<T?>(
                        progress.getStderr(),
                        progress.getStdout()
                    )
                }
                .collect(Collectors.joining())
        Truth.assertThat(reconstructedOutput)
            .isEqualTo(
                """
            [0 / 3] 3 actions running
            INFO: From Executing genrule //:1:
            Hello from genrule //:1 on stdout
            Hello from genrule //:1 on stderr
            [1 / 3] 2 actions running
            INFO: From Executing genrule //:2:
            Hello from genrule //:2 on stderr
            Hello from genrule //:2 on stdout
            [2 / 3] 1 actions running
            INFO: From Executing genrule //:3:
            Hello from genrule //:3 on stdout
            Hello from genrule //:3 on stderr
            Hello again from genrule //:3 on stdout
            INFO: Build completed successfully, 3 total actions
            
            """.trimIndent()
            )
    }

    @org.junit.Test
    fun testNoopFlush() {
        // Verify that the streamer ignores a flush, if neither stream produces any output.
        val outErr: BuildEventStreamer.OutErrProvider =
            Mockito.mock<BuildEventStreamer.OutErrProvider>(BuildEventStreamer.OutErrProvider::class.java)
        val stdoutMsg = "Some text that was written to stdout."
        val stderrMsg = "The UI text that bazel wrote to stderr."
        Mockito.`when`<Any?>(outErr.out).thenReturn(com.google.common.collect.ImmutableList.of<String?>(stdoutMsg))
            .thenReturn(com.google.common.collect.ImmutableList.of<Any?>())
        Mockito.`when`<Any?>(outErr.err).thenReturn(com.google.common.collect.ImmutableList.of<String?>(stderrMsg))
            .thenReturn(com.google.common.collect.ImmutableList.of<Any?>())
        val startEvent: BuildEvent =
            GenericBuildEvent(
                testId("Initial"), com.google.common.collect.ImmutableSet.of<E?>(ProgressEvent.INITIAL_PROGRESS_UPDATE)
            )

        streamer.registerOutErrProvider(outErr)
        streamer.buildEvent(startEvent)
        Truth.assertThat(transport.getEvents()).hasSize(1)
        streamer.flush() // Output, so a new progress event has to be added
        Truth.assertThat(transport.getEvents()).hasSize(2)
        streamer.flush() // No further output, so no additional event should be generated.
        Truth.assertThat(transport.getEvents()).hasSize(2)

        assertThat(transport.getEvents().get(0)).isEqualTo(startEvent)
        assertThat(transport.eventProtos.get(1).getProgress().getStdout()).isEqualTo(stdoutMsg)
        assertThat(transport.eventProtos.get(1).getProgress().getStderr()).isEqualTo(stderrMsg)
    }

    @org.junit.Test
    fun testEarlyFlushBadInitialEvent() {
        // Verify that an early flush works correctly with an unusual start event.
        // In this case, we expect 3 events in the stream, in that order:
        // - an artificial progress event as initial event, to properly link in
        //   all events
        // - the unusual first event we have seen, and
        // - a progress event reporting the flushed messages.
        val outErr: BuildEventStreamer.OutErrProvider =
            Mockito.mock<BuildEventStreamer.OutErrProvider>(BuildEventStreamer.OutErrProvider::class.java)
        val stdoutMsg = "Some text that was written to stdout."
        val stderrMsg = "The UI text that bazel wrote to stderr."
        Mockito.`when`<Any?>(outErr.out).thenReturn(com.google.common.collect.ImmutableList.of<String?>(stdoutMsg))
        Mockito.`when`<Any?>(outErr.err).thenReturn(com.google.common.collect.ImmutableList.of<String?>(stderrMsg))

        val unexpectedStartEvent: BuildEvent =
            GenericBuildEvent(testId("unexpected start"), com.google.common.collect.ImmutableSet.of<E?>())

        streamer.registerOutErrProvider(outErr)
        streamer.flush()
        streamer.buildEvent(unexpectedStartEvent)

        assertThat(streamer.isClosed()).isFalse()

        val eventsSeen: MutableList<BuildEvent> = transport.getEvents()
        Truth.assertThat(eventsSeen).hasSize(3)

        val initial: BuildEvent = eventsSeen.get(0)
        assertThat(initial.eventId).isEqualTo(ProgressEvent.INITIAL_PROGRESS_UPDATE)
        val initialProto: BuildEventStreamProtos.BuildEvent = transport.eventProtos.get(0)
        assertThat(initialProto.getProgress().getStdout()).isEmpty()
        assertThat(initialProto.getProgress().getStderr()).isEmpty()

        assertThat(eventsSeen.get(1).eventId).isEqualTo(unexpectedStartEvent.eventId)
        Truth.assertWithMessage("Unexpected event should be linked")
            .that(initial.childrenEvents.contains(unexpectedStartEvent.eventId))
            .isTrue()

        val progressProto: BuildEventStreamProtos.BuildEvent = transport.eventProtos.get(2)
        assertThat(progressProto.getProgress().getStdout()).isEqualTo(stdoutMsg)
        assertThat(progressProto.getProgress().getStderr()).isEqualTo(stderrMsg)
        Truth.assertWithMessage("flushed progress should be linked")
            .that(initial.childrenEvents.contains(eventsSeen.get(2).eventId))
            .isTrue()

        Mockito.verify<Any?>(outErr, Mockito.times(1)).out
        Mockito.verify<Any?>(outErr, Mockito.times(1)).err
    }

    @org.junit.Test
    fun testEarlyAbort() {
        // For a build that is aborted before a build-started event is generated,
        // we still expect that, if a build-started event is forced by some order
        // constraint (e.g., CommandLine wants to come after build started), then
        // that gets sorted to the beginning.
        val orderEvent: BuildEvent =
            GenericOrderEvent(
                testId("event depending on start"),
                com.google.common.collect.ImmutableList.of<BuildEventId?>(),
                com.google.common.collect.ImmutableList.of<E?>(BuildEventIdUtil.buildStartedId())
            )

        streamer.buildEvent(orderEvent)
        streamer.buildEvent(BuildCompleteEvent(BuildResult(0)))

        assertThat(streamer.isClosed()).isTrue()
        val eventsSeen: MutableList<BuildEvent> = transport.getEvents()
        Truth.assertThat(eventsSeen).hasSize(4)
        assertThat(eventsSeen.get(0).eventId).isEqualTo(BuildEventIdUtil.buildStartedId())
        assertThat(eventsSeen.get(1).eventId).isEqualTo(orderEvent.eventId)
        ProtoTruth.assertThat<com.google.protobuf.Message?>(
            com.google.common.collect.ImmutableSet.of<com.google.protobuf.Message?>(
                eventsSeen.get(2).eventId,
                eventsSeen.get(3).eventId
            )
        )
            .isEqualTo(
                com.google.common.collect.ImmutableSet.of<E?>(
                    BuildEventIdUtil.buildFinished(), ProgressEvent.INITIAL_PROGRESS_UPDATE
                )
            )
        assertThat(transport.eventProtos.get(3).getLastMessage()).isTrue()
    }

    @org.junit.Test
    fun testEventAfterBuildCompleteEvent() {
        val lateId: BuildEventId = testId("late")
        val startEvent: BuildEvent =
            GenericBuildEvent(
                testId("initial"),
                com.google.common.collect.ImmutableSet.of<E?>(
                    ProgressEvent.INITIAL_PROGRESS_UPDATE, BuildEventIdUtil.buildFinished()
                )
            )
        val lateEvent: BuildEvent =
            GenericBuildEvent(lateId, com.google.common.collect.ImmutableSet.of<E?>(testId("nonexistent")))
        val finishedEvent: BuildEvent =
            BuildCompleteEvent(BuildResult(0), com.google.common.collect.ImmutableList.of<BuildEventId>(lateId))

        streamer.buildEvent(startEvent)
        streamer.buildEvent(finishedEvent)
        streamer.buildEvent(lateEvent)
        assertThat(streamer.isClosed()).isTrue()
        Truth.assertThat(transport.eventProtos).hasSize(4)
        assertThat(transport.eventProtos.get(3).getLastMessage()).isTrue()
    }

    @org.junit.Test
    fun testFinalEventsLate() {
        // Verify that we correctly handle late events (i.e., events coming only after the
        // BuildCompleteEvent) that are sent to the streamer after the BuildCompleteEvent.
        val startEvent: BuildEvent =
            GenericBuildEvent(
                testId("Initial"),
                com.google.common.collect.ImmutableSet.of<E?>(
                    ProgressEvent.INITIAL_PROGRESS_UPDATE, BuildEventIdUtil.buildFinished()
                )
            )
        val lateId: BuildEventId = testId("late event")
        val finishedEvent: BuildEvent =
            BuildCompleteEvent(BuildResult(0), com.google.common.collect.ImmutableList.of<BuildEventId>(lateId))

        streamer.buildEvent(startEvent)
        streamer.buildEvent(finishedEvent)
        assertThat(streamer.isClosed()).isFalse()
        streamer.buildEvent(GenericBuildEvent(lateId, com.google.common.collect.ImmutableSet.of<E?>()))
        assertThat(streamer.isClosed()).isTrue()

        val eventsSeen: MutableList<BuildEvent> = transport.getEvents()
        Truth.assertThat(eventsSeen).hasSize(4)
        assertThat(eventsSeen.get(0).eventId).isEqualTo(startEvent.eventId)
        assertThat(eventsSeen.get(1).eventId).isEqualTo(BuildEventIdUtil.buildFinished())
        ProtoTruth.assertThat<com.google.protobuf.Message?>(
            com.google.common.collect.ImmutableSet.of<com.google.protobuf.Message?>(
                eventsSeen.get(2).eventId,
                eventsSeen.get(3).eventId
            )
        )
            .isEqualTo(com.google.common.collect.ImmutableSet.of<Any?>(lateId, ProgressEvent.INITIAL_PROGRESS_UPDATE))
    }

    @org.junit.Test
    fun testFinalEventsEarly() {
        // Verify that we correctly handle late events (i.e., events coming only after the
        // BuildCompleteEvent) that are sent to the streamer before the BuildCompleteEvent,
        // but with an order constraint to come afterwards.
        val startEvent: BuildEvent =
            GenericBuildEvent(
                testId("Initial"),
                com.google.common.collect.ImmutableSet.of<E?>(
                    ProgressEvent.INITIAL_PROGRESS_UPDATE, BuildEventIdUtil.buildFinished()
                )
            )
        val lateId: BuildEventId = testId("late event")
        val finishedEvent: BuildEvent =
            BuildCompleteEvent(BuildResult(0), com.google.common.collect.ImmutableList.of<BuildEventId>(lateId))

        streamer.buildEvent(startEvent)
        streamer.buildEvent(
            GenericOrderEvent(
                lateId,
                com.google.common.collect.ImmutableSet.of<BuildEventId?>(),
                com.google.common.collect.ImmutableList.of<E?>(BuildEventIdUtil.buildFinished())
            )
        )
        streamer.buildEvent(finishedEvent)
        assertThat(streamer.isClosed()).isTrue()

        val eventsSeen: MutableList<BuildEvent> = transport.getEvents()
        Truth.assertThat(eventsSeen).hasSize(4)
        assertThat(eventsSeen.get(0).eventId).isEqualTo(startEvent.eventId)
        assertThat(eventsSeen.get(1).eventId).isEqualTo(BuildEventIdUtil.buildFinished())
        ProtoTruth.assertThat<com.google.protobuf.Message?>(
            com.google.common.collect.ImmutableSet.of<com.google.protobuf.Message?>(
                eventsSeen.get(2).eventId,
                eventsSeen.get(3).eventId
            )
        )
            .isEqualTo(com.google.common.collect.ImmutableSet.of<Any?>(lateId, ProgressEvent.INITIAL_PROGRESS_UPDATE))
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun testProgressAfterFinalEvents() {
        val outErr: BuildEventStreamer.OutErrProvider =
            Mockito.mock<BuildEventStreamer.OutErrProvider>(BuildEventStreamer.OutErrProvider::class.java)
        val earlyStdout = "Stdout before finishing."
        val earlyStderr = "Stderr before finishing."
        val middleStdout = "Stdout *while* finishing."
        val middleStderr = "Stderr *while* finishing."
        val lateStdout = "Stdout after finishing."
        val lateStderr = "Stderr after finishing."
        Mockito.`when`<Any?>(outErr.out)
            .thenReturn(com.google.common.collect.ImmutableList.of<String?>(earlyStdout))
            .thenReturn(com.google.common.collect.ImmutableList.of<String?>(middleStdout))
            .thenReturn(com.google.common.collect.ImmutableList.of<String?>(lateStdout))
        Mockito.`when`<Any?>(outErr.err)
            .thenReturn(com.google.common.collect.ImmutableList.of<String?>(earlyStderr))
            .thenReturn(com.google.common.collect.ImmutableList.of<String?>(middleStderr))
            .thenReturn(com.google.common.collect.ImmutableList.of<String?>(lateStderr))
        streamer.registerOutErrProvider(outErr)

        // Verify that we correctly handle progress events that are sent to the streamer after the
        // BuildCompleteEvent.
        val startEvent: BuildEvent =
            GenericBuildEvent(
                testId("Initial"),
                com.google.common.collect.ImmutableSet.of<E?>(
                    ProgressEvent.INITIAL_PROGRESS_UPDATE, BuildEventIdUtil.buildFinished()
                )
            )
        val lateId: BuildEventId = testId("late event")
        val finishedEvent: BuildEvent =
            buildCompleteEvent(
                DetailedExitCode.success(),  /* stopOnFailure= */
                false,  /* crash= */
                null,  /* catastrophe= */
                false,
                com.google.common.collect.ImmutableList.of<BuildEventId?>(lateId)
            )

        streamer.buildEvent(startEvent)
        streamer.flush()
        streamer.buildEvent(finishedEvent)
        // Flushing after the finished event should discard stdout/stderr, not post progress events.
        streamer.flush()
        assertThat(streamer.isClosed()).isFalse()
        streamer.buildEvent(GenericBuildEvent(lateId, com.google.common.collect.ImmutableSet.of<E?>()))
        assertThat(streamer.isClosed()).isTrue()

        val eventsSeen: MutableList<BuildEvent> = transport.getEvents()
        val formatter: BuildEventContext = getTestBuildEventContext(artifactGroupNamer)
        // Verify that the event IDs are as expected.
        Truth.assertThat(eventsSeen).hasSize(5)
        assertThat(eventsSeen.get(0).eventId).isEqualTo(startEvent.eventId)
        assertThat(eventsSeen.get(1).eventId).isEqualTo(ProgressEvent.INITIAL_PROGRESS_UPDATE)
        assertThat(eventsSeen.get(2).eventId).isEqualTo(BuildEventIdUtil.buildFinished())
        assertThat(eventsSeen.get(3).eventId)
            .isEqualTo(ProgressEvent.progressUpdate(1).eventId)
        // Progress events received after the build is finished do not have an incremented progress ID.
        assertThat(eventsSeen.get(4).eventId).isEqualTo(lateId)

        // Verify that the progress events have the correct stdout/stderr and that the last event has
        // the "last_message" bit set true.
        val earlyProgressProto: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            eventsSeen.get(1).asStreamProto(formatter).getProgress()
        assertThat(earlyProgressProto.getStdout()).isEqualTo(earlyStdout)
        assertThat(earlyProgressProto.getStderr()).isEqualTo(earlyStderr)
        val middleProgressProto: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            eventsSeen.get(3).asStreamProto(formatter).getProgress()
        assertThat(middleProgressProto.getStdout()).isEqualTo(middleStdout)
        assertThat(middleProgressProto.getStderr()).isEqualTo(middleStderr)
        assertThat(eventsSeen.get(4).asStreamProto(formatter).getLastMessage()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun testLotsOfProgressAfterFinalEvents() {
        val outErr: BuildEventStreamer.OutErrProvider =
            Mockito.mock<BuildEventStreamer.OutErrProvider>(BuildEventStreamer.OutErrProvider::class.java)
        val earlyStdout = "Stdout before finishing."
        val earlyStderr = "Stderr before finishing."
        val middleStdout = "Stdout *while* finishing."
        val middleStderr = "Stderr *while* finishing."
        val lateStdout = "DoneStdout"
        val lateStderr = "DoneStderr"
        Mockito.`when`<Any?>(outErr.out)
            .thenReturn(com.google.common.collect.ImmutableList.of<String?>(earlyStdout))
            .thenReturn(com.google.common.collect.ImmutableList.of<String?>(middleStdout))
            .thenReturn(
                com.google.common.collect.ImmutableList.of<String?>(
                    lateStdout + 1,
                    lateStdout + 2,
                    lateStdout + 3
                )
            )
        Mockito.`when`<Any?>(outErr.err)
            .thenReturn(com.google.common.collect.ImmutableList.of<String?>(earlyStderr))
            .thenReturn(com.google.common.collect.ImmutableList.of<String?>(middleStderr))
            .thenReturn(
                com.google.common.collect.ImmutableList.of<String?>(
                    lateStderr + 1,
                    lateStderr + 2,
                    lateStderr + 3
                )
            )
        streamer.registerOutErrProvider(outErr)

        // Verify that we correctly handle progress events that are sent to the streamer after the
        // BuildCompleteEvent.
        val startEvent: BuildEvent =
            GenericBuildEvent(
                testId("Initial"),
                com.google.common.collect.ImmutableSet.of<E?>(
                    ProgressEvent.INITIAL_PROGRESS_UPDATE, BuildEventIdUtil.buildFinished()
                )
            )
        val lateId: BuildEventId = testId("late event")
        val finishedEvent: BuildEvent =
            buildCompleteEvent(
                DetailedExitCode.success(),  /* stopOnFailure= */
                false,  /* crash= */
                null,  /* catastrophe= */
                false,
                com.google.common.collect.ImmutableList.of<BuildEventId?>(lateId)
            )

        streamer.buildEvent(startEvent)
        streamer.flush()
        streamer.buildEvent(finishedEvent)
        streamer.flush()
        assertThat(streamer.isClosed()).isFalse()
        streamer.buildEvent(GenericBuildEvent(lateId, com.google.common.collect.ImmutableSet.of<E?>()))
        assertThat(streamer.isClosed()).isTrue()

        val eventsSeen: MutableList<BuildEvent> = transport.getEvents()
        val formatter: BuildEventContext = getTestBuildEventContext(artifactGroupNamer)
        // Verify that the last event has the "last_message" bit set true.
        assertThat(eventsSeen.get(eventsSeen.size - 1).asStreamProto(formatter).getLastMessage())
            .isTrue()
    }

    @org.junit.Test
    fun testSuccessfulActionsAreNotPublishedByDefault() {
        val handler = EventBusHandler()
        eventBus.register(handler)
        val failedActionExecutedEvent: ActionExecutedEvent =
            ActionExecutedEvent(
                ActionsTestUtil.DUMMY_ARTIFACT.getExecPath(),
                NullAction(),
                ActionExecutionException(
                    "Exception",  /* action= */
                    null,  /* catastrophe= */
                    false,
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setSpawn(Spawn.newBuilder().setCode(Code.EXECUTION_DENIED))
                            .build()
                    )
                ),
                ActionsTestUtil.DUMMY_ARTIFACT.getPath(),
                ActionsTestUtil.DUMMY_ARTIFACT,  /* primaryOutputMetadata= */
                null,  /* stdout= */
                null,  /* stderr= */
                null,
                ErrorTiming.BEFORE_EXECUTION,  /* startTime= */
                null,  /* endTime= */
                null
            )

        streamer.buildEvent(SUCCESSFUL_ACTION_EXECUTED_EVENT)
        streamer.buildEvent(failedActionExecutedEvent)

        val transportedEvents: MutableList<BuildEvent> = transport.getEvents()

        Truth.assertThat(transportedEvents).doesNotContain(SUCCESSFUL_ACTION_EXECUTED_EVENT)
        Truth.assertThat(transportedEvents).contains(failedActionExecutedEvent)
    }

    @org.junit.Test
    fun testSuccessfulActionsCanBePublished() {
        val handler = EventBusHandler()
        eventBus.register(handler)

        val options: BuildEventStreamOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(BuildEventStreamOptions::class.java)
        options.publishAllActions = true

        val streamer: BuildEventStreamer =
            Builder()
                .artifactGroupNamer(artifactGroupNamer)
                .besStreamOptions(options)
                .buildEventTransports(com.google.common.collect.ImmutableSet.of<E?>(transport))
                .build()

        val failedActionExecutedEvent: ActionExecutedEvent =
            ActionExecutedEvent(
                ActionsTestUtil.DUMMY_ARTIFACT.getExecPath(),
                NullAction(),
                ActionExecutionException(
                    "Exception",  /* action= */
                    null,  /* catastrophe= */
                    false,
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setSpawn(Spawn.newBuilder().setCode(Code.EXECUTION_DENIED))
                            .build()
                    )
                ),
                ActionsTestUtil.DUMMY_ARTIFACT.getPath(),
                ActionsTestUtil.DUMMY_ARTIFACT,  /* primaryOutputMetadata= */
                null,  /* stdout= */
                null,  /* stderr= */
                null,
                ErrorTiming.BEFORE_EXECUTION,  /* startTime= */
                null,  /* endTime= */
                null
            )

        streamer.buildEvent(SUCCESSFUL_ACTION_EXECUTED_EVENT)
        streamer.buildEvent(failedActionExecutedEvent)

        val transportedEvents: MutableList<BuildEvent> = transport.getEvents()

        Truth.assertThat(transportedEvents).contains(SUCCESSFUL_ACTION_EXECUTED_EVENT)
        Truth.assertThat(transportedEvents).contains(failedActionExecutedEvent)
    }

    @org.junit.Test
    fun testBuildIncomplete() {
        val buildEventId: BuildEventId = testId("abort_expected")
        val startEvent: BuildEvent =
            GenericBuildEvent(
                BuildEventIdUtil.buildStartedId(),
                com.google.common.collect.ImmutableSet.of<E?>(
                    buildEventId,
                    ProgressEvent.INITIAL_PROGRESS_UPDATE,
                    BuildEventIdUtil.buildFinished()
                )
            )
        val buildCompleteEvent: BuildCompleteEvent =
            buildCompleteEvent(createGenericDetailedExitCode(), true, null, false)

        streamer.buildEvent(startEvent)
        streamer.buildEvent(buildCompleteEvent)
        streamer.close()

        val aborted: BuildEventStreamProtos.BuildEvent? = getBepEvent(buildEventId)
        assertThat(aborted).isNotNull()
        assertThat(aborted.hasAborted()).isTrue()
        assertThat(aborted.getAborted().getReason()).isEqualTo(AbortReason.INCOMPLETE)
        assertThat(aborted.getAborted().getDescription()).isEmpty()
    }

    @org.junit.Test
    fun testBuildCrash() {
        val buildEventId: BuildEventId = testId("abort_expected")
        val startEvent: BuildEvent =
            GenericBuildEvent(
                BuildEventIdUtil.buildStartedId(),
                com.google.common.collect.ImmutableSet.of<E?>(
                    buildEventId,
                    ProgressEvent.INITIAL_PROGRESS_UPDATE,
                    BuildEventIdUtil.buildFinished()
                )
            )
        val buildCompleteEvent: BuildCompleteEvent =
            buildCompleteEvent(createGenericDetailedExitCode(), true, java.lang.RuntimeException(), false)

        streamer.buildEvent(startEvent)
        streamer.buildEvent(buildCompleteEvent)
        streamer.close()

        val aborted: BuildEventStreamProtos.BuildEvent? = getBepEvent(buildEventId)
        assertThat(aborted).isNotNull()
        assertThat(aborted.hasAborted()).isTrue()
        assertThat(aborted.getAborted().getReason()).isEqualTo(AbortReason.INTERNAL)
        assertThat(aborted.getAborted().getDescription()).isEmpty()
    }

    @org.junit.Test
    fun testBuildCatastrophe() {
        val buildEventId: BuildEventId = testId("abort_expected")
        val startEvent: BuildEvent =
            GenericBuildEvent(
                BuildEventIdUtil.buildStartedId(),
                com.google.common.collect.ImmutableSet.of<E?>(
                    buildEventId,
                    ProgressEvent.INITIAL_PROGRESS_UPDATE,
                    BuildEventIdUtil.buildFinished()
                )
            )
        val buildCompleteEvent: BuildCompleteEvent =
            buildCompleteEvent(createGenericDetailedExitCode(), true, null, true)

        streamer.buildEvent(startEvent)
        streamer.buildEvent(buildCompleteEvent)
        streamer.close()

        val aborted: BuildEventStreamProtos.BuildEvent? = getBepEvent(buildEventId)
        assertThat(aborted).isNotNull()
        assertThat(aborted.hasAborted()).isTrue()
        assertThat(aborted.getAborted().getReason()).isEqualTo(AbortReason.INTERNAL)
        assertThat(aborted.getAborted().getDescription()).isEmpty()
    }

    @org.junit.Test
    fun testBuildCatastropheOom_testCommand() {
        val abortedEventId: BuildEventId? =
            BuildEventIdUtil.targetPatternExpanded(com.google.common.collect.ImmutableList.of<E?>("//foo:bar"))
        val startEvent: BuildEvent? =
            BuildStartingEvent.create(
                "tmpfs",
                true,
                BuildRequest.builder()
                    .setCommandName("test")
                    .setRunTests(true)
                    .setTargets(com.google.common.collect.ImmutableList.of<E?>("//foo:bar"))
                    .setOptions(createMockOptions())
                    .setId(UUID.randomUUID())
                    .setStartTimeMillis(10842L)
                    .build(),
                null,
                "/tmp/build"
            )
        val buildCompleteEvent: BuildCompleteEvent =
            buildCompleteEvent(
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setCrash(Crash.newBuilder().setCode(Crash.Code.CRASH_OOM))
                        .build()
                ),
                true,
                null,
                true
            )

        streamer.buildEvent(startEvent)
        streamer.buildEvent(buildCompleteEvent)
        streamer.close()

        val aborted: BuildEventStreamProtos.BuildEvent? = getBepEvent(abortedEventId)
        assertThat(aborted).isNotNull()
        assertThat(aborted.hasAborted()).isTrue()
        assertThat(aborted.getAborted().getReason()).isEqualTo(AbortReason.OUT_OF_MEMORY)
        assertThat(aborted.getAborted().getDescription())
            .isEqualTo(constructOomExitMessage(OOM_MESSAGE))
    }

    @org.junit.Test
    fun testBuildFailsToComplete_testCommand() {
        val abortedEventId: BuildEventId? =
            BuildEventIdUtil.targetPatternExpanded(com.google.common.collect.ImmutableList.of<E?>("//foo:bar"))
        val startEvent: BuildEvent? =
            BuildStartingEvent.create(
                "tmpfs",
                true,
                BuildRequest.builder()
                    .setCommandName("test")
                    .setRunTests(true)
                    .setTargets(com.google.common.collect.ImmutableList.of<E?>("//foo:bar"))
                    .setOptions(createMockOptions())
                    .setId(UUID.randomUUID())
                    .setStartTimeMillis(10842L)
                    .build(),
                null,
                "/tmp/build"
            )
        val buildCompleteEvent: BuildCompleteEvent =
            buildCompleteEvent(
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setSpawn(Spawn.newBuilder().setCode(Spawn.Code.NON_ZERO_EXIT))
                        .build()
                ),
                true,
                null,
                false
            )

        streamer.buildEvent(startEvent)
        streamer.buildEvent(buildCompleteEvent)
        streamer.close()

        val aborted: BuildEventStreamProtos.BuildEvent? = getBepEvent(abortedEventId)
        assertThat(aborted).isNotNull()
        assertThat(aborted.hasAborted()).isTrue()
        assertThat(aborted.getAborted().getReason()).isEqualTo(AbortReason.INCOMPLETE)
    }

    @org.junit.Test
    fun testStreamAbortedWithTimeout() {
        val buildEventId: BuildEventId = testId("abort_expected")
        val startEvent: BuildEvent =
            GenericBuildEvent(
                BuildEventIdUtil.buildStartedId(),
                com.google.common.collect.ImmutableSet.of<E?>(
                    buildEventId,
                    ProgressEvent.INITIAL_PROGRESS_UPDATE,
                    BuildEventIdUtil.buildFinished()
                )
            )

        streamer.buildEvent(startEvent)
        streamer.closeOnAbort(AbortReason.TIME_OUT)

        val aborted0: BuildEventStreamProtos.BuildEvent? = getBepEvent(buildEventId)
        assertThat(aborted0).isNotNull()
        assertThat(aborted0.hasAborted()).isTrue()
        assertThat(aborted0.getAborted().getReason()).isEqualTo(AbortReason.TIME_OUT)
        assertThat(aborted0.getAborted().getDescription()).isEmpty()

        val aborted1: BuildEventStreamProtos.BuildEvent? = getBepEvent(BuildEventIdUtil.buildFinished())
        assertThat(aborted1).isNotNull()
        assertThat(aborted1.hasAborted()).isTrue()
        assertThat(aborted1.getAborted().getReason()).isEqualTo(AbortReason.TIME_OUT)
        assertThat(aborted1.getAborted().getDescription()).isEmpty()
    }

    @org.junit.Test
    fun testBuildFailureMultipleReasons() {
        val buildEventId: BuildEventId = testId("abort_expected")
        val startEvent: BuildEvent =
            GenericBuildEvent(
                BuildEventIdUtil.buildStartedId(),
                com.google.common.collect.ImmutableSet.of<E?>(
                    buildEventId,
                    ProgressEvent.INITIAL_PROGRESS_UPDATE,
                    BuildEventIdUtil.buildFinished()
                )
            )
        val buildCompleteEvent: BuildCompleteEvent =
            buildCompleteEvent(createGenericDetailedExitCode(), false, java.lang.RuntimeException(), false)

        streamer.buildEvent(startEvent)
        streamer.noAnalyze(NoAnalyzeEvent())
        streamer.buildEvent(buildCompleteEvent)
        streamer.close()

        val aborted: BuildEventStreamProtos.BuildEvent? = getBepEvent(buildEventId)
        assertThat(aborted).isNotNull()
        assertThat(aborted.hasAborted()).isTrue()
        assertThat(aborted.getAborted().getReason()).isEqualTo(AbortReason.INTERNAL)
        assertThat(aborted.getAborted().getDescription())
            .isEqualTo("Multiple abort reasons reported: [NO_ANALYZE, INTERNAL]")
    }

    @org.junit.Test
    fun nonOomAbortReason_doesNotIncludeOomMessage() {
        val buildEventId: BuildEventId = testId("abort_expected")
        val startEvent: BuildEvent =
            GenericBuildEvent(
                BuildEventIdUtil.buildStartedId(),
                com.google.common.collect.ImmutableSet.of<E?>(
                    buildEventId,
                    ProgressEvent.INITIAL_PROGRESS_UPDATE,
                    BuildEventIdUtil.buildFinished()
                )
            )

        streamer.buildEvent(startEvent)
        streamer.closeOnAbort(AbortReason.INTERNAL)

        assertThat(getBepEvent(buildEventId).getAborted())
            .isEqualTo(Aborted.newBuilder().setReason(AbortReason.INTERNAL).build())
    }

    @org.junit.Test
    fun oomAbortReason_includesOomMessage() {
        val buildEventId: BuildEventId = testId("abort_expected")
        val startEvent: BuildEvent =
            GenericBuildEvent(
                BuildEventIdUtil.buildStartedId(),
                com.google.common.collect.ImmutableSet.of<E?>(
                    buildEventId,
                    ProgressEvent.INITIAL_PROGRESS_UPDATE,
                    BuildEventIdUtil.buildFinished()
                )
            )

        streamer.buildEvent(startEvent)
        streamer.closeOnAbort(AbortReason.OUT_OF_MEMORY)

        assertThat(getBepEvent(buildEventId).getAborted())
            .isEqualTo(
                Aborted.newBuilder()
                    .setReason(AbortReason.OUT_OF_MEMORY)
                    .setDescription(constructOomExitMessage(OOM_MESSAGE))
                    .build()
            )
    }

    private class ReplaceableTestBuildEvent(id: BuildEventId?, private val replaceable: Boolean) :
        GenericBuildEvent(id, com.google.common.collect.ImmutableSet.of<E?>()), ReplaceableBuildEvent {
        public override fun replaceable(): Boolean {
            return replaceable
        }
    }

    @org.junit.Test
    fun replaceableEvent_doesNotPostBecauseisReplaced() {
        val buildEventId: BuildEventId = testId("replaceable_event")
        val replaceable: BuildEvent = ReplaceableTestBuildEvent(buildEventId,  /* replaceable= */true)
        val replacedBy: BuildEvent = ReplaceableTestBuildEvent(buildEventId,  /* replaceable= */false)

        streamer.buildEvent(replaceable)
        Truth.assertThat(transport.getEvents()).isEmpty()
        streamer.buildEvent(replacedBy)
        Truth.assertThat(transport.getEvents()).doesNotContain(replaceable)
        Truth.assertThat(transport.getEvents()).contains(replacedBy)
    }

    @org.junit.Test
    fun replaceableEvent_postsBecauseisNotReplacedAndBuildAborts() {
        val buildEventId: BuildEventId = testId("replaceable_event")
        val replaceable: BuildEvent = ReplaceableTestBuildEvent(buildEventId,  /* replaceable= */true)

        streamer.buildEvent(replaceable)
        Truth.assertThat(transport.getEvents()).isEmpty()
        streamer.noAnalyze(NoAnalyzeEvent())
        streamer.close()
        Truth.assertThat(transport.getEvents()).contains(replaceable)
    }

    @org.junit.Test
    fun replaceableEvent_postsBecauseisNotReplacedAndBuildCompletes() {
        val buildEventId: BuildEventId = testId("replaceable_event")
        val replaceable: BuildEvent = ReplaceableTestBuildEvent(buildEventId,  /* replaceable= */true)

        streamer.buildEvent(replaceable)
        Truth.assertThat(transport.getEvents()).isEmpty()
        streamer.buildEvent(BuildCompleteEvent(BuildResult(0)))
        Truth.assertThat(transport.getEvents()).contains(replaceable)
    }

    private class OrderedTestBuildEvent(id: BuildEventId?, postedAfter: MutableCollection<BuildEventId?>?) :
        GenericBuildEvent(id, com.google.common.collect.ImmutableSet.of<E?>()), BuildEventWithOrderConstraint {
        private val postedAfter: MutableCollection<BuildEventId?>?

        init {
            this.postedAfter = postedAfter
        }

        public override fun postedAfter(): MutableCollection<BuildEventId?>? {
            return postedAfter
        }
    }

    @org.junit.Test
    fun testAbortHasUnblockedChildren() {
        val abortedEventId: BuildEventId = testId("aborted_event")
        val bufferedEvent1Id: BuildEventId = testId("buffered_event_1")
        val bufferedEvent2Id: BuildEventId = testId("buffered_event_2")
        val bufferedEvent3Id: BuildEventId = testId("buffered_event_3")
        val startEvent: BuildEvent =
            GenericBuildEvent(
                BuildEventIdUtil.buildStartedId(),
                com.google.common.collect.ImmutableSet.of<E?>(
                    abortedEventId,
                    bufferedEvent2Id,  // We announce one of the three events.
                    ProgressEvent.INITIAL_PROGRESS_UPDATE,
                    BuildEventIdUtil.buildFinished()
                )
            )

        streamer.buildEvent(startEvent)
        val postedAfter: com.google.common.collect.ImmutableSet<BuildEventId?> =
            com.google.common.collect.ImmutableSet.of<BuildEventId?>(abortedEventId)
        streamer.buildEvent(OrderedTestBuildEvent(bufferedEvent1Id, postedAfter))
        streamer.buildEvent(OrderedTestBuildEvent(bufferedEvent2Id, postedAfter))
        streamer.buildEvent(OrderedTestBuildEvent(bufferedEvent3Id, postedAfter))
        streamer.close()

        // The children have all been posted.
        val event1: BuildEventStreamProtos.BuildEvent? = getBepEvent(bufferedEvent1Id)
        assertThat(event1).isNotNull()
        assertThat(event1.hasAborted()).isFalse()
        assertThat(getBepEvent(bufferedEvent2Id)).isNotNull()
        assertThat(getBepEvent(bufferedEvent3Id)).isNotNull()
        // The aborted blocking event has two of the buffered events as children, but not the third one
        // that had already been announced.
        val aborted: BuildEventStreamProtos.BuildEvent? = getBepEvent(abortedEventId)
        assertThat(aborted.hasAborted()).isTrue()
        assertThat(aborted.getChildrenList()).containsExactly(bufferedEvent1Id, bufferedEvent3Id)
    }

    private fun getBepEvent(buildEventId: BuildEventId?): BuildEventStreamProtos.BuildEvent? {
        return transport.eventProtos.stream()
            .filter { e: BuildEventStreamProtos.BuildEvent -> e.getId().equals(buildEventId) }
            .findFirst()
            .orElse(null)
    }

    private fun createMockOptions(): OptionsParsingResult {
        val options: OptionsParsingResult = Mockito.mock<OptionsParsingResult>(OptionsParsingResult::class.java)
        Mockito.`when`<Any?>(options.getOptions<OptionsBase?>(ArgumentMatchers.any<java.lang.Class<OptionsBase?>?>()))
            .thenAnswer(
                Answer { inv: InvocationOnMock? ->
                    val optionsClass: java.lang.Class<out OptionsBase?>? =
                        inv.getArgument<java.lang.Class<out OptionsBase?>?>(0)
                    com.google.devtools.common.options.Options.getDefaults(optionsClass)
                })
        Mockito.`when`<MutableList<ParsedOptionDescription?>?>(options.asCompleteListOfParsedOptions())
            .thenReturn(com.google.common.collect.ImmutableList.of<ParsedOptionDescription?>())
        return options
    }

    @Throws(InvalidConfigurationException::class, OptionsParsingException::class)
    private fun makeTestingBuildConfigurationValue(): BuildConfigurationValue {
        return BuildConfigurationValue.createForTesting(
            BuildOptions.of(com.google.common.collect.ImmutableList.of<E?>(CoreOptions::class.java)),
            "some_mnemonic",  /* siblingRepositoryLayout= */
            false,
            BlazeDirectories(
                ServerDirectories(outputBase, outputBase, outputBase),
                rootDirectory,
                "productName"
            ),
            object : GlobalStateProvider() {
                public override fun getActionEnvironment(buildOptions: BuildOptions?): ActionEnvironment {
                    return ActionEnvironment.EMPTY
                }

                val fragmentRegistry: FragmentRegistry
                    get() = FragmentRegistry.create(
                        com.google.common.collect.ImmutableList.of<E?>(),
                        com.google.common.collect.ImmutableList.of<E?>(),
                        com.google.common.collect.ImmutableList.of<E?>()
                    )

                val reservedActionMnemonics: com.google.common.collect.ImmutableSet<String?>
                    get() = com.google.common.collect.ImmutableSet.of<String?>()

                val runfilesPrefix: String
                    get() = "bleh"
            },
            FragmentFactory()
        )
    }

    companion object {
        private const val OOM_MESSAGE = "Please build fewer targets."

        private fun getTestBuildEventContext(artifactGroupNamer: ArtifactGroupNamer): BuildEventContext {
            return object : BuildEventContext() {
                public override fun artifactGroupNamer(): ArtifactGroupNamer {
                    return artifactGroupNamer
                }

                public override fun pathConverter(): PathConverter? {
                    return Path::toString
                }

                val options: BuildEventProtocolOptions?
                    get() = com.google.devtools.common.options.Options.getDefaults<O?>(BuildEventProtocolOptions::class.java)
            }
        }

        private val SUCCESSFUL_ACTION_EXECUTED_EVENT: ActionExecutedEvent = ActionExecutedEvent(
            ActionsTestUtil.DUMMY_ARTIFACT.getExecPath(),
            NullAction(),  /* exception= */
            null,
            ActionsTestUtil.DUMMY_ARTIFACT.getPath(),
            ActionsTestUtil.DUMMY_ARTIFACT,
            FileArtifactValue.MISSING_FILE_MARKER,  /* stdout= */
            null,  /* stderr= */
            null,
            ErrorTiming.NO_ERROR,  /* startTime= */
            null,  /* endTime= */
            null
        )

        private fun testId(opaque: String?): BuildEventId {
            return BuildEventIdUtil.unknownBuildEventId(opaque)
        }

        private fun indexOrderedBuildEvent(index: Int, afterIndex: Int): BuildEvent {
            return GenericOrderEvent(
                testId("Concurrent-" + index),
                com.google.common.collect.ImmutableList.of<BuildEventId?>(),
                if (afterIndex == -1)
                    com.google.common.collect.ImmutableList.of<BuildEventId?>()
                else
                    com.google.common.collect.ImmutableList.of<BuildEventId?>(testId("Concurrent-" + afterIndex))
            )
        }

        private fun <T> consumeToLists(
            left: Iterable<T?>?, right: Iterable<T?>?
        ): com.google.common.collect.ImmutableList<com.google.common.collect.ImmutableList<Pair<T?, T?>?>> {
            val consumerBuilder: com.google.common.collect.ImmutableList.Builder<Pair<T?, T?>?> =
                com.google.common.collect.ImmutableList.builder<Pair<T?, T?>?>()
            val lastConsumerBuilder: com.google.common.collect.ImmutableList.Builder<Pair<T?, T?>?> =
                com.google.common.collect.ImmutableList.builder<Pair<T?, T?>?>()

            BuildEventStreamer.consumeAsPairs(
                left,
                right,
                { t1, t2 -> consumerBuilder.add(Pair.of(t1, t2)) },
                { t1, t2 -> lastConsumerBuilder.add(Pair.of(t1, t2)) })

            return com.google.common.collect.ImmutableList.of<com.google.common.collect.ImmutableList<Pair<T?, T?>?>?>(
                consumerBuilder.build(),
                lastConsumerBuilder.build()
            )
        }

        private fun buildCompleteEvent(
            detailedExitCode: DetailedExitCode?,
            stopOnFailure: Boolean,
            crash: Throwable?,
            catastrophe: Boolean
        ): BuildCompleteEvent {
            return buildCompleteEvent(
                detailedExitCode,
                stopOnFailure,
                crash,
                catastrophe,
                com.google.common.collect.ImmutableList.of<BuildEventId?>()
            )
        }

        private fun buildCompleteEvent(
            detailedExitCode: DetailedExitCode?,
            stopOnFailure: Boolean,
            crash: Throwable?,
            catastrophe: Boolean,
            childrenEvents: MutableCollection<BuildEventId?>
        ): BuildCompleteEvent {
            val result: BuildResult = BuildResult(0)
            result.setDetailedExitCode(detailedExitCode)
            result.stopOnFirstFailure = stopOnFailure
            if (catastrophe) {
                result.setCatastrophe()
            }
            if (crash != null) {
                result.setUnhandledThrowable(crash)
            }
            if (childrenEvents.isEmpty()) {
                return BuildCompleteEvent(result)
            }
            return BuildCompleteEvent(result, childrenEvents)
        }

        private fun createGenericDetailedExitCode(): DetailedExitCode {
            return DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setSpawn(Spawn.newBuilder().setCode(Code.NON_ZERO_EXIT))
                    .build()
            )
        }
    }
}
