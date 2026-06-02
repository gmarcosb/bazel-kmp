// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.actions.ActionExecutedEvent

/**
 * Streamer in charge of listening to [BuildEvent] and post them to each of the [ ].
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
class BuildEventStreamer private constructor(
    transports: MutableCollection<BuildEventTransport>,
    options: BuildEventStreamOptions,
    outputGroupFileModes: OutputGroupFileModes?,
    publishTargetSummaries: Boolean,
    artifactGroupNamer: CountingArtifactGroupNamer,
    oomMessage: String?
) {
    /** Return value for [.routeBuildEvent].  */
    private enum class RetentionDecision {
        // Delay posting this event until other events post.
        BUFFERED,

        // Only post this event if the build ends before an event that replaces it is posted.
        BUFFERED_FOR_REPLACEMENT,

        // Don't post this event.
        DISCARD,

        // Post this event immediately.
        POST
    }

    private val transports: MutableCollection<BuildEventTransport>
    private val besOptions: BuildEventStreamOptions
    private val outputGroupFileModes: OutputGroupFileModes?
    private val publishTargetSummaries: Boolean

    @com.google.errorprone.annotations.concurrent.GuardedBy("this")
    private var announcedEvents: MutableSet<BuildEventId?>?

    @com.google.errorprone.annotations.concurrent.GuardedBy("this")
    private val postedEvents: MutableSet<BuildEventId?> = HashSet<BuildEventId?>()

    @com.google.errorprone.annotations.concurrent.GuardedBy("this")
    private val configurationsPosted: MutableSet<BuildEventId?> = HashSet<BuildEventId?>()

    @com.google.errorprone.annotations.concurrent.GuardedBy("this")
    private var bufferedStdoutStderrPairs: MutableList<com.google.devtools.build.lib.util.Pair<String?, String?>>? =
        java.util.ArrayList<com.google.devtools.build.lib.util.Pair<String?, String?>>()

    // Use LinkedHashMultimap to maintain a FIFO ordering of pending events.
    // This is important in case of Skymeld, so that the TestAttempt events are resolved in the
    // correct order.
    @com.google.errorprone.annotations.concurrent.GuardedBy("this")
    private val pendingEvents: com.google.common.collect.SetMultimap<BuildEventId, BuildEvent> =
        com.google.common.collect.LinkedHashMultimap.create<BuildEventId, BuildEvent>()

    @com.google.errorprone.annotations.concurrent.GuardedBy("this")
    private var progressCount: Int

    private val artifactGroupNamer: CountingArtifactGroupNamer
    private val oomMessage: String?
    private var outErrProvider: OutErrProvider? = null

    @com.google.errorprone.annotations.concurrent.GuardedBy("this")
    private val abortReasons: MutableSet<AbortReason?> = LinkedHashSet<AbortReason?>()

    // Will be set to true if the build was invoked through "bazel test", "bazel coverage", or
    // "bazel run".
    private var isCommandToSkipBuildCompleteEvent = false

    // After #buildComplete is called, contains the set of events that the streamer is expected to
    // process. The streamer will fully close after seeing them. This field is null until
    // #buildComplete is called.
    // Thread-safety note: in the final, sequential phase of the build, we ignore any events that are
    // announced by events posted after #buildComplete is called.
    private var finalEventsToCome: MutableSet<BuildEventId?>? = null

    // True, if we already closed the stream.
    @get:kotlin.jvm.Synchronized
    @com.google.errorprone.annotations.concurrent.GuardedBy("this")
    var isClosed: Boolean = false
        private set

    /**
     * The current state of buffered progress events.
     * 
     * 
     * The typical case in which stdout and stderr alternate is output of the form:
     * 
     * <pre>
     * INFO: From Executing genrule //:genrule:
     * &lt;genrule stdout output>
     * [123 / 1234] 16 actions running
    </pre> * 
     * 
     * We want the relative order of the stdout and stderr output to be preserved on a best-effort
     * basis and thus flush the two streams into a progress event before we are seeing a second type
     * transition. We are free to declare either stdout or stderr to come first. We choose stderr here
     * as that provides the more natural split in the example above: The INFO line and the stdout of
     * the action it precedes both end up in the same progress event.
     */
    internal enum class ProgressBufferState {
        ACCEPT_STDERR_AND_STDOUT {
            override fun nextStateOnStderr(): ProgressBufferState {
                return ProgressBufferState.ACCEPT_STDERR_AND_STDOUT
            }

            override fun nextStateOnStdout(): ProgressBufferState {
                return ProgressBufferState.ACCEPT_STDOUT
            }
        },
        ACCEPT_STDOUT {
            override fun nextStateOnStderr(): ProgressBufferState {
                return ProgressBufferState.REQUIRE_FLUSH
            }

            override fun nextStateOnStdout(): ProgressBufferState {
                return ProgressBufferState.ACCEPT_STDOUT
            }
        },
        REQUIRE_FLUSH {
            override fun nextStateOnStderr(): ProgressBufferState {
                return ProgressBufferState.REQUIRE_FLUSH
            }

            override fun nextStateOnStdout(): ProgressBufferState {
                return ProgressBufferState.REQUIRE_FLUSH
            }
        };

        abstract fun nextStateOnStderr(): ProgressBufferState?

        abstract fun nextStateOnStdout(): ProgressBufferState?
    }

    private val progressBufferState: AtomicReference<ProgressBufferState?> =
        AtomicReference<ProgressBufferState?>(ProgressBufferState.ACCEPT_STDERR_AND_STDOUT)

    /** Holds the futures for the closing of each transport  */
    private var closeFuturesMap: com.google.common.collect.ImmutableMap<BuildEventTransport?, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?> =
        com.google.common.collect.ImmutableMap.of<BuildEventTransport?, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>()

    /**
     * Holds the half-close futures for the upload of each transport. The completion of the half-close
     * indicates that the client has sent all of the data to the server and is just waiting for
     * acknowledgement. The client must still keep the data buffered locally in case acknowledgement
     * fails.
     */
    private var halfCloseFuturesMap: com.google.common.collect.ImmutableMap<BuildEventTransport?, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?> =
        com.google.common.collect.ImmutableMap.of<BuildEventTransport?, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>()

    /** Provider for stdout and stderr output.  */
    interface OutErrProvider {
        /**
         * Return the chunks of stdout that were produced since the last call to this function (or the
         * beginning of the build, for the first call). It is the responsibility of the class
         * implementing this interface to properly synchronize with simultaneously written output.
         */
        @kotlin.jvm.JvmField
        val out: Iterable<String?>?

        /**
         * Return the chunks of stderr that were produced since the last call to this function (or the
         * beginning of the build, for the first call). It is the responsibility of the class
         * implementing this interface to properly synchronize with simultaneously written output.
         */
        @kotlin.jvm.JvmField
        val err: Iterable<String?>?
    }

    /** Creates a new build event streamer.  */
    init {
        this.transports = transports
        this.besOptions = options
        this.outputGroupFileModes = outputGroupFileModes
        this.publishTargetSummaries = publishTargetSummaries
        this.announcedEvents = null
        this.progressCount = 0
        this.artifactGroupNamer = artifactGroupNamer
        this.oomMessage = oomMessage
    }

    @ThreadCompatible
    fun registerOutErrProvider(outErrProvider: OutErrProvider?) {
        this.outErrProvider = outErrProvider
    }

    // This exists to nop out the announcement of new events after #buildComplete
    @kotlin.jvm.Synchronized
    private fun maybeRegisterAnnouncedEvent(id: BuildEventId?) {
        if (finalEventsToCome != null) {
            return
        }

        announcedEvents!!.add(id)
    }

    // This exists to nop out the announcement of new events after #buildComplete
    @kotlin.jvm.Synchronized
    private fun maybeRegisterAnnouncedEvents(ids: MutableCollection<BuildEventId?>?) {
        if (finalEventsToCome != null) {
            return
        }

        announcedEvents!!.addAll(ids)
    }

    /**
     * Post a new event to all transports; simultaneously keep track of the events we announce to
     * still come.
     * 
     * 
     * Moreover, link unannounced events to the progress stream; we only expect failure events to
     * come before their parents.
     */
    // @GuardedBy annotation is doing lexical analysis that doesn't understand the closures below
    // will be running under the synchronized block.
    @kotlin.jvm.Synchronized
    private fun post(event: BuildEvent) {
        var linkEvents: MutableList<BuildEvent?>? = null
        val id: BuildEventId? = event.eventId
        var flushEvents: MutableList<BuildEvent?>? = null
        var lastEvent = false

        if (announcedEvents == null) {
            announcedEvents = HashSet<BuildEventId?>()
            // The very first event of a stream is implicitly announced by the convention that
            // a complete stream has to have at least one entry. In this way we keep the invariant
            // that the set of posted events is always a subset of the set of announced events.
            maybeRegisterAnnouncedEvent(id)
            if (!event.childrenEvents.contains(ProgressEvent.INITIAL_PROGRESS_UPDATE)) {
                val progress: BuildEvent = ProgressEvent.progressChainIn(progressCount, event.eventId)
                linkEvents = com.google.common.collect.ImmutableList.of<BuildEvent?>(progress)
                progressCount++
                maybeRegisterAnnouncedEvents(progress.childrenEvents)
                // the new first event in the stream, implicitly announced by the fact that complete
                // stream may not be empty.
                maybeRegisterAnnouncedEvent(progress.eventId)
                postedEvents.add(progress.eventId)
            }

            if (!bufferedStdoutStderrPairs!!.isEmpty()) {
                flushEvents = java.util.ArrayList<BuildEvent?>(bufferedStdoutStderrPairs.size())
                for (outErrPair in bufferedStdoutStderrPairs!!) {
                    flushEvents!!.add(flushStdoutStderrEvent(outErrPair.getFirst(), outErrPair.getSecond()))
                }
            }
            bufferedStdoutStderrPairs = null
        } else {
            if (!announcedEvents!!.contains(id)) {
                var allOut: Iterable<String?> = com.google.common.collect.ImmutableList.of<String?>()
                var allErr: Iterable<String?> = com.google.common.collect.ImmutableList.of<String?>()
                if (outErrProvider != null) {
                    allOut = orEmpty<String?>(outErrProvider!!.out)
                    allErr = orEmpty<String?>(outErrProvider!!.err)
                    progressBufferState.set(ProgressBufferState.ACCEPT_STDERR_AND_STDOUT)
                }
                linkEvents = java.util.ArrayList<BuildEvent?>()
                val finalLinkEvents: MutableList<BuildEvent?>? = linkEvents
                consumeAsPairsofStrings(
                    allOut,
                    allErr,
                    java.util.function.BiConsumer { out: String?, err: String? ->
                        val progressEvent: BuildEvent = ProgressEvent.progressChainIn(progressCount, id, out, err)
                        finalLinkEvents!!.add(progressEvent)
                        progressCount++
                        maybeRegisterAnnouncedEvents(progressEvent.childrenEvents)
                        postedEvents.add(progressEvent.eventId)
                    })
            }
        }

        if (event is BuildInfoEvent) {
            // The specification for BuildInfoEvent says that there may be many such events,
            // but all except the first one should be ignored.
            if (postedEvents.contains(id)) {
                return
            }
        }

        postedEvents.add(id)
        maybeRegisterAnnouncedEvents(event.childrenEvents)
        // We keep as an invariant that postedEvents is a subset of announced events, so this is a
        // cheaper test for equality
        if (announcedEvents.size() == postedEvents.size()) {
            lastEvent = true
        }

        var mainEvent: BuildEvent = event
        if (lastEvent) {
            mainEvent = LastBuildEvent(event)
        }

        for (transport in transports) {
            if (linkEvents != null) {
                for (linkEvent in linkEvents) {
                    transport.sendBuildEvent(linkEvent)
                }
            }
            transport.sendBuildEvent(mainEvent)
        }

        if (flushEvents != null) {
            for (flushEvent in flushEvents) {
                for (transport in transports) {
                    transport.sendBuildEvent(flushEvent)
                }
            }
        }
    }

    /**
     * If some events are blocked on the absence of a build_started event, generate such an event;
     * moreover, make that artificial start event announce all events blocked on it, as well as the
     * [BuildCompletingEvent] that caused the early end of the stream.
     */
    @kotlin.jvm.Synchronized
    private fun clearMissingStartEvent(id: BuildEventId?) {
        if (pendingEvents.containsKey(BuildEventIdUtil.buildStartedId())) {
            val children: com.google.common.collect.ImmutableSet.Builder<BuildEventId?> =
                com.google.common.collect.ImmutableSet.builder<BuildEventId?>()
            children.add(ProgressEvent.INITIAL_PROGRESS_UPDATE)
            children.add(id)
            children.addAll(
                pendingEvents.get(BuildEventIdUtil.buildStartedId()).stream()
                    .map<Any?>(BuildEvent::getEventId)
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<E?>())
            )
            buildEvent(
                AbortedEvent(
                    BuildEventIdUtil.buildStartedId(),
                    children.build(),
                    this.lastAbortReason,
                    this.abortReasonDetails
                )
            )
        }
    }

    /** Clear pending events by generating aborted events for all their requests.  */
    @kotlin.jvm.Synchronized
    private fun clearPendingEvents() {
        while (!pendingEvents.isEmpty()) {
            val id: BuildEventId = pendingEvents.keySet().iterator().next()
            val bufferedEventsPendingOnThisType: MutableCollection<BuildEventId> =
                releaseReplaceableBuildEvent(ReleaseReplaceableBuildEvent(id))
            if (!bufferedEventsPendingOnThisType.isEmpty()) {
                // Replaceable (BUFERED_FOR_REPLACEMENT) events finally trigger on build abort, so
                // we don't need a distinct AbortedEvent to acknowledge them. Normal buffered events
                // don't trigger because their trigger event never happened, so they need an
                // AbortedEvent.
                val children: com.google.common.collect.ImmutableList.Builder<BuildEventId?> =
                    com.google.common.collect.ImmutableList.builder<BuildEventId?>()
                for (bufferedId in bufferedEventsPendingOnThisType) {
                    if (announcedEvents == null || !announcedEvents!!.contains(bufferedId)) {
                        children.add(bufferedId)
                    }
                }
                buildEvent(
                    AbortedEvent(id, children.build(), this.lastAbortReason, this.abortReasonDetails)
                )
            }
        }
    }

    /**
     * Clear all events that are still announced; events not naturally closed by the expected event
     * normally only occur if the build is aborted.
     */
    @kotlin.jvm.Synchronized
    private fun clearAnnouncedEvents(dontclear: MutableCollection<BuildEventId?>) {
        if (announcedEvents != null) {
            // create a copy of the identifiers to clear, as the post method
            // will change the set of already announced events.
            val ids: MutableSet<BuildEventId?>?
            synchronized(this) {
                ids = com.google.common.collect.Sets.difference<BuildEventId?>(announcedEvents, postedEvents)
            }
            for (id in ids!!) {
                if (!dontclear.contains(id)) {
                    post(AbortedEvent(id, this.lastAbortReason, this.abortReasonDetails))
                }
            }
        }
    }

    fun closeOnAbort(reason: AbortReason?) {
        close(com.google.common.base.Preconditions.checkNotNull<AbortReason?>(reason))
    }

    fun close() {
        close( /* reason= */null)
    }

    @kotlin.jvm.Synchronized
    private fun close(reason: AbortReason?) {
        if (this.isClosed) {
            return
        }
        this.isClosed = true
        if (reason != null) {
            addAbortReason(reason)
        }

        if (finalEventsToCome == null) {
            // This should only happen if there's a crash. Try to clean up as best we can.
            clearEventsAndPostFinalProgress(null)
        }

        val closeFuturesMapBuilder: com.google.common.collect.ImmutableMap.Builder<BuildEventTransport?, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?> =
            com.google.common.collect.ImmutableMap.builder<BuildEventTransport?, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>()
        for (transport in transports) {
            closeFuturesMapBuilder.put(transport, transport.close())
        }
        closeFuturesMap = closeFuturesMapBuilder.buildOrThrow()

        val halfCloseFuturesMapBuilder: com.google.common.collect.ImmutableMap.Builder<BuildEventTransport?, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?> =
            com.google.common.collect.ImmutableMap.builder<BuildEventTransport?, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>()
        for (transport in transports) {
            halfCloseFuturesMapBuilder.put(transport, transport.getHalfCloseFuture())
        }
        halfCloseFuturesMap = halfCloseFuturesMapBuilder.buildOrThrow()
    }

    private fun maybeReportArtifactSet(ctx: CompletionContext?, set: NestedSet<*>) {
        var set: NestedSet<*> = set
        artifactGroupNamer.maybeName(set).use { lockedName ->
            if (lockedName == null) {
                return
            }
            set = NamedArtifactGroup.expandSet(ctx, set)

            // Invariant: all leaf successors ("direct elements") of set are ExpandedArtifacts.

            // We only split if the max number of entries is at least 2 (it must be at least a binary
            // tree). The method throws for smaller values.
            if (besOptions.maxNamedSetEntries >= 2) {
                // We only split the event after naming it to avoid splitting the same node multiple times.
                // Note that the artifactGroupNames keeps references to the individual pieces, so this can
                // double the memory consumption of large nested sets.
                set = set.splitIfExceedsMaximumSize(besOptions.maxNamedSetEntries)
            }

            for (succ in set.getNonLeaves()) {
                maybeReportArtifactSet(ctx, succ)
            }
            post(NamedArtifactGroup(lockedName.getName(), ctx, set))
        }
    }

    private fun maybeReportConfiguration(configuration: BuildEvent?) {
        val event: BuildEvent = if (configuration == null) NullConfiguration.INSTANCE else configuration
        val id: BuildEventId? = event.eventId
        synchronized(this) {
            if (configurationsPosted.add(id)) {
                post(event)
            }
        }
    }

    @com.google.common.eventbus.Subscribe
    fun buildInterrupted(event: BuildInterruptedEvent?) {
        addAbortReason(AbortReason.USER_INTERRUPTED)
    }

    @com.google.common.eventbus.Subscribe
    fun noAnalyze(event: NoAnalyzeEvent?) {
        addAbortReason(AbortReason.NO_ANALYZE)
    }

    @com.google.common.eventbus.Subscribe
    fun noExecution(event: NoExecutionEvent?) {
        addAbortReason(AbortReason.NO_BUILD)
    }

    /**
     * Posts a `RetensionDecision#BUFFERED_FOR_REPLACEMENT` build event without waiting for its
     * replacement.
     * 
     * 
     * Does nothing if no replaceable event is pending for this event type. Note there can be at
     * most one pending replaceable event for any build type.
     * 
     * @param event event id of the replaceable event to post
     * @return the IDs of normal buffered events which are also waiting on this event id, if any. This
     * is useful when builds abort, as they can become children of the AbortedEvent.
     */
    @com.google.common.eventbus.Subscribe
    fun releaseReplaceableBuildEvent(event: ReleaseReplaceableBuildEvent): MutableCollection<BuildEventId> {
        val bufferedEventIDs: com.google.common.collect.ImmutableList.Builder<BuildEventId?> =
            com.google.common.collect.ImmutableList.builder<BuildEventId?>()
        var replaceable: BuildEvent? = null
        synchronized(this) {
            val pendingEventsThisType: MutableIterator<BuildEvent> = pendingEvents.get(event.getEventId()).iterator()
            while (pendingEventsThisType.hasNext()) {
                val pendingEvent: BuildEvent = pendingEventsThisType.next()
                if (pendingEvent is ReplaceableBuildEvent) {
                    com.google.common.base.Verify.verify(
                        replaceable == null,
                        "Multiple replaceable events not supported for %s ",
                        event.getEventId()
                    )
                    replaceable = pendingEvent
                    pendingEventsThisType.remove()
                } else {
                    bufferedEventIDs.add(pendingEvent.eventId)
                }
            }
        }
        if (replaceable != null) {
            post(replaceable)
        }
        return bufferedEventIDs.build()
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun buildEvent(event: BuildEvent) {
        if (finalEventsToCome != null) {
            synchronized(this) {
                val id: BuildEventId? = event.eventId
                if (finalEventsToCome!!.contains(id)) {
                    finalEventsToCome!!.remove(id)
                } else {
                    return
                }
            }
        }

        if (event is BuildCompleteEvent) {
            if (isCrash(event) || isCatastrophe(event)) {
                if (isOom(event)) {
                    addAbortReason(AbortReason.OUT_OF_MEMORY)
                } else {
                    addAbortReason(AbortReason.INTERNAL)
                }
            } else if (isIncomplete(event)) {
                addAbortReason(AbortReason.INCOMPLETE)
            }
        }

        when (routeBuildEvent(event)) {
            RetentionDecision.DISCARD -> {
                // Check if there are pending events waiting on this event
                maybePostPendingEventsBeforeDiscarding(event)
                return  // bail: we're dropping this event
            }

            RetentionDecision.BUFFERED ->         // Bail: the event was buffered and the BuildEventStreamer is now responsible for eventually
                // posting it.
                return

            RetentionDecision.BUFFERED_FOR_REPLACEMENT ->         // Bail: the event was buffered to possibly be replaced with an updated version. The
                // BuildEventStreamer is now responsible for eventually posting or discarding it.
                return

            RetentionDecision.POST -> {}
        }

        if (event is BuildStartingEvent) {
            val buildRequest: BuildRequest = event.request()
            isCommandToSkipBuildCompleteEvent =
                buildRequest.commandName.equals("test")
                        || buildRequest.commandName.equals("coverage")
                        || buildRequest.commandName.equals("run")
        }

        if (event is BuildEventWithConfiguration) {
            for (configuration in event.configurations) {
                maybeReportConfiguration(configuration)
            }
        }

        if (event is EventReportingArtifacts) {
            val reportedArtifacts: ReportedArtifacts =
                event.reportedArtifacts(outputGroupFileModes)
            for (artifactSet in reportedArtifacts.artifacts) {
                maybeReportArtifactSet(reportedArtifacts.completionContext, artifactSet)
            }
        }

        if (event is BuildCompletingEvent
            && !event.eventId.equals(BuildEventIdUtil.buildStartedId())
        ) {
            clearMissingStartEvent(event.eventId)
        }

        if (event is BuildConfigurationEvent) {
            maybeReportConfiguration(event)
        } else {
            post(event)
        }

        // Reconsider all events blocked by the event just posted.
        val blockedEventsFifo: MutableSet<BuildEvent>?
        synchronized(this) {
            blockedEventsFifo = pendingEvents.removeAll(event.eventId)
        }
        for (freedEvent in blockedEventsFifo!!) {
            // Replaceable events have been replaced, so can be silently dropped.
            if (freedEvent !is ReplaceableBuildEvent) {
                buildEvent(freedEvent)
            }
        }

        // Special-case handling for subclasses of `BuildCompletingEvent`.
        //
        // For most commands, exactly one `BuildCompletingEvent` will be posted to the EventBus. If the
        // command is "run" or "test", a non-crashing/catastrophic `BuildCompleteEvent` will be followed
        // by a RunBuildCompleteEvent/TestingCompleteEvent.
        if (event is BuildCompletingEvent) {
            buildComplete(event)
        }

        if (event is NoBuildEvent) {
            if (!event.separateFinishedEvent()) {
                buildComplete(event)
            }
        }

        if (finalEventsToCome != null && finalEventsToCome!!.isEmpty()) {
            close()
        }
    }

    /**
     * Given an event that will be discarded (not buffered), publishes any events waiting on the given
     * event.
     * 
     * @param event event that is being discarded (not buffered)
     */
    private fun maybePostPendingEventsBeforeDiscarding(event: BuildEvent?) {
        if (publishTargetSummaries && isVacuousTestSummary(event)) {
            // Target summaries should "post after" test summaries, but we can't a priori know whether
            // test summaries will be vacuous (as that depends on test execution progress). So check for
            // and publish any pending (target summary) events here. If we don't do this then
            // clearPendingEvents() will publish "aborted" test_summary events for the very events we're
            // discarding here (b/184580877), followed by the pending target_summary events, which is not
            // only confusing but also delays target_summary events until the end of the build.
            //
            // Technically it seems we should do this with all events we're dropping but that would be
            // a lot of extra locking e.g. for every ActionExecutedEvent and it's only necessary to
            // check for this where events are configured to "post after" events that may be discarded.
            val eventId: BuildEventId? = event.eventId
            val blockedEventsFifo: MutableSet<BuildEvent>?
            synchronized(this) {
                blockedEventsFifo = pendingEvents.removeAll(eventId)
                // Pretend we posted this event so a target summary arriving after this test summary (which
                // is common) doesn't get erroneously buffered in bufferUntilPrerequisitesReceived().
                postedEvents.add(eventId)
            }
            for (freedEvent in blockedEventsFifo!!) {
                buildEvent(freedEvent)
            }
        }
    }

    @kotlin.jvm.Synchronized
    private fun flushStdoutStderrEvent(out: String?, err: String?): BuildEvent {
        val updateEvent: BuildEvent = ProgressEvent.progressUpdate(progressCount, out, err)
        progressCount++
        maybeRegisterAnnouncedEvents(updateEvent.childrenEvents)
        postedEvents.add(updateEvent.eventId)
        return updateEvent
    }

    /** Whether the given output type can be written without first flushing the streamer.  */
    fun canBufferProgressWrite(isStderr: Boolean): Boolean {
        val newState: ProgressBufferState? =
            progressBufferState.updateAndGet(
                if (isStderr) UnaryOperator { obj: ProgressBufferState? -> obj!!.nextStateOnStderr() } else UnaryOperator { obj: ProgressBufferState? -> obj!!.nextStateOnStdout() })
        return newState !== ProgressBufferState.REQUIRE_FLUSH
    }

    // @GuardedBy annotation is doing lexical analysis that doesn't understand the closures below
    // will be running under the synchronized block.
    fun flush() {
        var updateEvents: MutableList<BuildEvent?>? = null
        synchronized(this) {
            var allOut: Iterable<String?> = com.google.common.collect.ImmutableList.of<String?>()
            var allErr: Iterable<String?> = com.google.common.collect.ImmutableList.of<String?>()
            if (outErrProvider != null) {
                allOut = orEmpty<String?>(outErrProvider!!.out)
                allErr = orEmpty<String?>(outErrProvider!!.err)
                progressBufferState.set(ProgressBufferState.ACCEPT_STDERR_AND_STDOUT)
            }
            if (com.google.common.collect.Iterables.isEmpty(allOut) && com.google.common.collect.Iterables.isEmpty(
                    allErr
                )
            ) {
                // Nothing to flush; avoid generating an unneeded progress event.
                return
            }
            if (finalEventsToCome != null) {
                // If we've already announced the final events, we cannot add more progress events. Stdout
                // and stderr are truncated from the event log.
                consumeAsPairsofStrings(allOut, allErr, java.util.function.BiConsumer { s1: String?, s2: String? -> })
            } else if (announcedEvents != null) {
                updateEvents = java.util.ArrayList<BuildEvent?>()
                val finalUpdateEvents: MutableList<BuildEvent?>? = updateEvents
                consumeAsPairsofStrings(
                    allOut,
                    allErr,
                    java.util.function.BiConsumer { s1: String?, s2: String? ->
                        finalUpdateEvents!!.add(flushStdoutStderrEvent(s1, s2))
                    })
            } else {
                consumeAsPairsofStrings(
                    allOut,
                    allErr,
                    java.util.function.BiConsumer { s1: String?, s2: String? ->
                        bufferedStdoutStderrPairs!!.add(
                            com.google.devtools.build.lib.util.Pair.of<String?, String?>(
                                s1,
                                s2
                            )
                        )
                    })
            }
        }
        if (updateEvents != null) {
            for (updateEvent in updateEvents) {
                for (transport in transports) {
                    transport.sendBuildEvent(updateEvent)
                }
            }
        }
    }

    // @GuardedBy annotation is doing lexical analysis that doesn't understand the closures below
    // will be running under the synchronized block.
    @kotlin.jvm.Synchronized
    private fun clearEventsAndPostFinalProgress(event: ChainableEvent?) {
        clearPendingEvents()
        var allOut: Iterable<String?> = com.google.common.collect.ImmutableList.of<String?>()
        var allErr: Iterable<String?> = com.google.common.collect.ImmutableList.of<String?>()
        if (outErrProvider != null) {
            allOut = orEmpty<String?>(outErrProvider!!.out)
            allErr = orEmpty<String?>(outErrProvider!!.err)
            progressBufferState.set(ProgressBufferState.ACCEPT_STDERR_AND_STDOUT)
        }
        consumeAsPairsofStrings(
            allOut,
            allErr,
            java.util.function.BiConsumer { s1: String?, s2: String? -> post(flushStdoutStderrEvent(s1, s2)) },
            java.util.function.BiConsumer { s1: String?, s2: String? ->
                post(
                    ProgressEvent.finalProgressUpdate(
                        progressCount++,
                        s1,
                        s2
                    )
                )
            })
        clearAnnouncedEvents(if (event == null) com.google.common.collect.ImmutableList.of<BuildEventId?>() else event.childrenEvents)
    }

    @kotlin.jvm.Synchronized
    private fun buildComplete(event: ChainableEvent?) {
        clearEventsAndPostFinalProgress(event)

        finalEventsToCome = HashSet<BuildEventId?>(announcedEvents)
        finalEventsToCome!!.removeAll(postedEvents)
        if (finalEventsToCome!!.isEmpty()) {
            close()
        }
    }

    /** Returns whether a [BuildEvent] should be ignored or was buffered.  */
    private fun routeBuildEvent(event: BuildEvent?): RetentionDecision {
        if (event is ActionExecutedEvent
            && !shouldPublishActionExecutedEvent(event)
        ) {
            return RetentionDecision.DISCARD
        }

        val replaceableDecision = decideBufferedForReplacementEvent(event)
        if (replaceableDecision != null) {
            return replaceableDecision
        }

        if (bufferUntilPrerequisitesReceived(event)) {
            return RetentionDecision.BUFFERED
        }

        if (isVacuousTestSummary(event)) {
            return RetentionDecision.DISCARD
        }

        if (isCommandToSkipBuildCompleteEvent
            && event is BuildCompleteEvent
        ) {
            // In case of "bazel test" or "bazel run" ignore the BuildCompleteEvent, as it will be
            // followed by a TestingCompleteEvent (or RunBuildCompleteEvent) that contains the correct
            // exit code.
            return if (isCrash(event)) RetentionDecision.POST else RetentionDecision.DISCARD
        }

        if (event is TargetParsingCompleteEvent) {
            // If there is only one pattern and we have one failed pattern, then we already posted a
            // pattern expanded error, so we don't post the completion event.
            // TODO(b/109727414): This is brittle. It would be better to always post one PatternExpanded
            // event for each pattern given on the command line instead of one event for all of them
            // combined.
            val discard =
                event.getOriginalTargetPattern().size() === 1
                        && !event.getFailedTargetPatterns().isEmpty()
            return if (discard) RetentionDecision.DISCARD else RetentionDecision.POST
        }

        return RetentionDecision.POST
    }

    /** Returns whether an [ActionExecutedEvent] should be published.  */
    private fun shouldPublishActionExecutedEvent(event: ActionExecutedEvent): Boolean {
        if (besOptions.publishAllActions) {
            return true
        }
        if (event.getException() != null) {
            // Publish failed actions
            return true
        }
        return event.getAction() is ExtraAction
    }

    @kotlin.jvm.Synchronized
    private fun decideBufferedForReplacementEvent(event: BuildEvent?): RetentionDecision? {
        if (event !is ReplaceableBuildEvent) {
            return null
        }
        if (!event.replaceable()) {
            // The event's class is replaceable but this instance isn't. Treat it normally.
            return RetentionDecision.POST
        }
        if (postedEvents.contains(event.eventId)) {
            // This event type has already been posted, so the replaceable event is outdated.
            return RetentionDecision.DISCARD
        }
        synchronized(this) {
            com.google.common.base.Verify.verify(
                pendingEvents.get(event.eventId).stream()
                    .filter(java.util.function.Predicate { e: BuildEvent -> e is ReplaceableBuildEvent })
                    .findFirst()
                    .isEmpty(),
                "Multiple replaceable events not supported for %s",
                event.eventId
            )
            pendingEvents.put(event.eventId, event)
        }
        return RetentionDecision.BUFFERED_FOR_REPLACEMENT
    }

    @kotlin.jvm.Synchronized
    private fun bufferUntilPrerequisitesReceived(event: BuildEvent?): Boolean {
        if (event !is BuildEventWithOrderConstraint) {
            return false
        }
        // Check if all prerequisite events are posted already.
        for (prerequisiteId in event.postedAfter()) {
            if (!postedEvents.contains(prerequisiteId)) {
                pendingEvents.put(prerequisiteId, event)
                return true
            }
        }
        return false
    }

    /**
     * Returns the map from BEP transports to their corresponding closing future.
     * 
     * 
     * If this method is called before calling [.close] then it will return an empty map.
     */
    @kotlin.jvm.Synchronized
    fun getCloseFuturesMap(): com.google.common.collect.ImmutableMap<BuildEventTransport?, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?> {
        return closeFuturesMap
    }

    @get:kotlin.jvm.Synchronized
    val halfClosedMap: com.google.common.collect.ImmutableMap<BuildEventTransport?, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>
        /**
         * Returns the map from BEP transports to their corresponding half-close futures.
         * 
         * 
         * Half-close indicates that all client-side data is transmitted but still waiting on
         * server-side acknowledgement. The client must buffer the information in case the server fails to
         * acknowledge.
         * 
         * 
         * If this method is called before calling [.close] then it will return an empty map.
         */
        get() = halfCloseFuturesMap

    /**
     * Stores the [AbortReason] for later reporting on BEP pending events.
     * 
     * 
     * In case of multiple abort reasons:
     * 
     * 
     *  * Only the most recent reason will be reported as the main [AbortReason] in BEP.
     *  * All previous reasons will appear in the [Aborted.getDescription] message.
     * 
     */
    @kotlin.jvm.Synchronized
    private fun addAbortReason(reason: AbortReason?) {
        abortReasons.add(reason)
    }

    @get:kotlin.jvm.Synchronized
    private val lastAbortReason: AbortReason?
        /**
         * Returns the most recent [AbortReason] or [AbortReason.UNKNOWN] if no reason was
         * set.
         */
        get() = com.google.common.collect.Iterables.getLast<AbortReason?>(abortReasons, AbortReason.UNKNOWN)

    @get:kotlin.jvm.Synchronized
    private val abortReasonDetails: String
        /**
         * Returns a detailed message explaining the most recent [AbortReason] (and possibly
         * previous reasons).
         */
        get() {
            if (abortReasons.size() == 1
                && com.google.common.collect.Iterables.getOnlyElement<AbortReason?>(abortReasons) === AbortReason.OUT_OF_MEMORY
            ) {
                return BugReport.constructOomExitMessage(oomMessage)
            }
            return if (abortReasons.size() > 1) "Multiple abort reasons reported: " + abortReasons else ""
        }

    /** A builder for [BuildEventStreamer].  */
    class Builder {
        private var buildEventTransports: MutableSet<BuildEventTransport?>? = null
        private var besStreamOptions: BuildEventStreamOptions? = null
        private var outputGroupFileModes: OutputGroupFileModes? = OutputGroupFileModes.DEFAULT
        private var publishTargetSummaries = false
        private var artifactGroupNamer: CountingArtifactGroupNamer? = null
        private var oomMessage: String? = null

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun buildEventTransports(value: MutableSet<BuildEventTransport?>?): Builder {
            this.buildEventTransports = value
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun besStreamOptions(value: BuildEventStreamOptions?): Builder {
            this.besStreamOptions = value
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun outputGroupFileModes(outputGroupFileModes: OutputGroupFileModes?): Builder {
            this.outputGroupFileModes = outputGroupFileModes
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun publishTargetSummaries(publishTargetSummaries: Boolean): Builder {
            this.publishTargetSummaries = publishTargetSummaries
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun artifactGroupNamer(value: CountingArtifactGroupNamer?): Builder {
            this.artifactGroupNamer = value
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun oomMessage(oomMessage: String?): Builder {
            this.oomMessage = oomMessage
            return this
        }

        fun build(): BuildEventStreamer {
            return BuildEventStreamer(
                com.google.common.base.Preconditions.checkNotNull<MutableSet<BuildEventTransport?>?>(
                    buildEventTransports
                ),
                com.google.common.base.Preconditions.checkNotNull<BuildEventStreamOptions?>(besStreamOptions),
                outputGroupFileModes,
                publishTargetSummaries,
                com.google.common.base.Preconditions.checkNotNull<CountingArtifactGroupNamer?>(artifactGroupNamer),
                com.google.common.base.Strings.nullToEmpty(oomMessage)
            )
        }
    }

    companion object {
        private fun isCrash(event: BuildCompleteEvent): Boolean {
            return event.getResult().getUnhandledThrowable() != null || isOom(event)
        }

        private fun isCatastrophe(event: BuildCompleteEvent): Boolean {
            return event.getResult().wasCatastrophe()
        }

        private fun isIncomplete(event: BuildCompleteEvent): Boolean {
            return !event.getResult().getSuccess() && !event.getResult()
                .wasCatastrophe() && event.getResult().stopOnFirstFailure
        }

        private fun isOom(event: BuildCompleteEvent): Boolean {
            return event.getResult().getDetailedExitCode().getExitCode().equals(ExitCode.OOM_ERROR)
        }

        // Returns the given Iterable, or an empty list if null.
        private fun <T> orEmpty(original: Iterable<T?>?): Iterable<T?> {
            return if (original == null) com.google.common.collect.ImmutableList.of<T?>() else original
        }

        // Given a pair of iterables and {@link BiConsumer}s, emit a sequence of pairs to the consumers.
        // Given the leftIterables [L1, L2, ... LN], and the rightIterable [R1, R2, ... RM], the consumers
        // will see this sequence of calls:
        //  biConsumer.accept(L1, null);
        //  biConsumer.accept(L2, null);
        //  ....
        //  biConsumer.accept(L(N-1), null);
        //  biConsumer.accept(LN, R1);
        //  biConsumer.accept(null, R2);
        //  ...
        //  biConsumer.accept(null, R(M-1);
        //  lastConsumer.accept(null, RM);
        //
        // The lastConsumer is always called exactly once, even if both Iterables are empty.
        @com.google.common.annotations.VisibleForTesting
        fun <T> consumeAsPairs(
            leftIterable: Iterable<T?>,
            rightIterable: Iterable<T?>,
            biConsumer: java.util.function.BiConsumer<T?, T?>?,
            lastConsumer: java.util.function.BiConsumer<T?, T?>
        ) {
            if (com.google.common.collect.Iterables.isEmpty(leftIterable) && com.google.common.collect.Iterables.isEmpty(
                    rightIterable
                )
            ) {
                lastConsumer.accept(null, null)
                return
            }

            val leftIterator: MutableIterator<T?> = leftIterable.iterator()
            val rightIterator: MutableIterator<T?> = rightIterable.iterator()
            while (leftIterator.hasNext()) {
                val left = leftIterator.next()
                val lastT = !leftIterator.hasNext()
                val right = if (lastT && rightIterator.hasNext()) rightIterator.next() else null
                val lastItem = lastT && !rightIterator.hasNext()
                (if (lastItem) lastConsumer else biConsumer).accept(left, right)
            }

            while (rightIterator.hasNext()) {
                val right = rightIterator.next()
                (if (!rightIterator.hasNext()) lastConsumer else biConsumer).accept(null, right)
            }
        }

        private fun consumeAsPairsofStrings(
            leftIterable: Iterable<String?>,
            rightIterable: Iterable<String?>,
            biConsumer: java.util.function.BiConsumer<String?, String?>,
            lastConsumer: java.util.function.BiConsumer<String?, String?> = biConsumer
        ) {
            consumeAsPairs<String?>(
                leftIterable,
                rightIterable,
                java.util.function.BiConsumer { s1: String?, s2: String? ->
                    biConsumer.accept(
                        com.google.common.base.Strings.nullToEmpty(
                            s1
                        ), com.google.common.base.Strings.nullToEmpty(s2)
                    )
                },
                java.util.function.BiConsumer { s1: String?, s2: String? ->
                    lastConsumer.accept(
                        com.google.common.base.Strings.nullToEmpty(
                            s1
                        ), com.google.common.base.Strings.nullToEmpty(s2)
                    )
                })
        }

        /** Return true if the test summary contains no actual test runs.  */
        private fun isVacuousTestSummary(event: BuildEvent?): Boolean {
            return event is TestSummary && event.totalRuns() == 0
        }
    }
}
