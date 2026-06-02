// Copyright 2014 The Bazel Authors. All rights reserved.
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

/** Blaze module for the build summary message that reports various stats to the user.  */
class BuildSummaryStatsModule : BlazeModule() {
    private var actionKeyContext: ActionKeyContext? = null
    private var criticalPathComputer: CriticalPathComputer? = null
    private var eventBus: com.google.common.eventbus.EventBus? = null
    private var reporter: com.google.devtools.build.lib.events.Reporter? = null
    private var enabled = false

    private var statsSummary = false
    private var commandStartMillis: Long = 0
    private var executionStartMillis: Long = 0
    private var executionEndMillis: Long = 0
    private var spawnStats: SpawnStats? = null
    private var profileEvent: ProfilerStartedEvent? = null
    private var executionStarted: AtomicBoolean? = null

    public override fun beforeCommand(env: CommandEnvironment) {
        this.reporter = env.getReporter()
        this.eventBus = env.getEventBus()
        this.actionKeyContext = env.getSkyframeExecutor().getActionKeyContext()
        commandStartMillis = env.commandStartTime
        this.spawnStats = SpawnStats()
        eventBus.register(this)
        executionStarted = AtomicBoolean(false)
    }

    public override fun afterCommand() {
        this.criticalPathComputer = null
        this.eventBus = null
        this.reporter = null
        this.spawnStats = null
        executionStarted.set(false)
    }

    public override fun executorInit(env: CommandEnvironment, request: BuildRequest?, builder: ExecutorBuilder?) {
        enabled = env.getOptions().getOptions(ExecutionOptions::class.java).enableCriticalPathProfiling
        statsSummary = env.getOptions().getOptions(ExecutionOptions::class.java).statsSummary
        if (enabled) {
            criticalPathComputer =
                CriticalPathComputer(
                    actionKeyContext,
                    SkyframeExecutorWrappingWalkableGraph.of(env.getSkyframeExecutor())
                )
            eventBus.register(criticalPathComputer)
        }
    }

    @com.google.common.eventbus.Subscribe
    fun executionPhaseStarting(event: ExecutionStartingEvent?) {
        markExecutionPhaseStarted()
    }

    /**
     * Skymeld-specific marking of the start of execution. Multiple instances of this event might be
     * fired during the build, but we make sure to only mark the start of the execution phase when the
     * first one is received.
     */
    @com.google.common.eventbus.Subscribe
    fun executionPhaseStarting(
        @Suppress("unused") event: TopLevelTargetPendingExecutionEvent?
    ) {
        if (executionStarted.compareAndSet( /* expectedValue= */false,  /* newValue= */true)) {
            markExecutionPhaseStarted()
        }
    }

    private fun markExecutionPhaseStarted() {
        // TODO(ulfjack): Make sure to use the same clock as for commandStartMillis.
        executionStartMillis = com.google.devtools.build.lib.clock.BlazeClock.instance().currentTimeMillis()
    }

    @com.google.common.eventbus.Subscribe
    fun profileStarting(event: ProfilerStartedEvent?) {
        this.profileEvent = event
    }

    @com.google.common.eventbus.Subscribe
    fun executionPhaseFinish(@Suppress("unused") event: ExecutionFinishedEvent?) {
        executionEndMillis = com.google.devtools.build.lib.clock.BlazeClock.instance().currentTimeMillis()
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun actionResultReceived(event: ActionResultReceivedEvent) {
        spawnStats.countActionResult(event.getActionResult())
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun actionCompletion(event: ActionCompletionEvent?) {
        spawnStats.incrementActionCount()
    }

    @com.google.common.eventbus.Subscribe
    fun actionCacheStats(event: PostableActionCacheStats) {
        spawnStats.recordActionCacheStats(event.asProto())
    }

    @com.google.common.eventbus.Subscribe
    fun buildComplete(event: BuildCompleteEvent) {
        try {
            // We might want to make this conditional on a flag; it can sometimes be a bit of a nuisance.
            val items: MutableList<String?> = java.util.ArrayList<String?>()
            items.add(java.lang.String.format("Elapsed time: %.3fs", event.getResult().getElapsedSeconds()))
            event
                .getResult().buildToolLogCollection
                .addDirectValue(
                    "elapsed time",
                    java.lang.String.format("%f", event.getResult().getElapsedSeconds())
                        .toByteArray(java.nio.charset.StandardCharsets.UTF_8)
                )

            var criticalPath: AggregatedCriticalPath = AggregatedCriticalPath.EMPTY
            if (criticalPathComputer != null) {
                com.google.devtools.build.lib.profiler.Profiler.instance()
                    .profile(ProfilerTask.CRITICAL_PATH, "Critical path").use { c ->
                        criticalPath = criticalPathComputer.aggregate()
                        reporter.post(CriticalPathEvent(criticalPath))
                        items.add(criticalPath.toStringSummaryNoRemote())
                        event
                            .getResult().buildToolLogCollection
                            .addDirectValue(
                                "critical path",
                                criticalPath.toString().toByteArray(java.nio.charset.StandardCharsets.UTF_8)
                            )
                        logger.atInfo().log("%s", criticalPath)
                        logger.atInfo().log(
                            "Slowest actions:\n  %s",
                            com.google.common.base.Joiner.on("\n  ").join(criticalPathComputer.getSlowestComponents())
                        )
                        // We reverse the critical path because the profiler expect events ordered by the time
                        // when the actions were executed while critical path computation is stored in the reverse
                        // way.
                        for (stat in criticalPath.components().reverse()) {
                            com.google.devtools.build.lib.profiler.Profiler.instance()
                                .logSimpleTaskDuration(
                                    stat.startTimeNanos,
                                    stat.getElapsedTime(),
                                    ProfilerTask.CRITICAL_PATH_COMPONENT,
                                    stat.prettyPrintAction()
                                )
                        }
                    }
            }
            if (profileEvent != null && profileEvent.profile != null) {
                // The profiler has to be stopped before `BuildEventServiceModule#afterCommand` is called,
                // especially when it is a bep artifact. An unstopped bep artifact could lead to a deadlock
                // in `BuildEventServiceModule#afterCommand`.
                //
                // We choose to stop profiler here instead of in `BuildSummaryStatsModule#afterCommand` so
                // that no ordering between GoogleBuildSummaryStatsModule and BuildEventServiceModule's
                // `afterCommand`s needs to be assumed. See b/253394502.
                //
                // Stopping the profiler here leads to missing the afterCommand profiles of the other
                // modules in the profile, which is a compromise we are willing to make.
                try {
                    com.google.devtools.build.lib.profiler.Profiler.instance().stop()
                    profileEvent.profile.publish(event.getResult().buildToolLogCollection)
                } catch (e: IOException) {
                    reporter.handle(com.google.devtools.build.lib.events.Event.error("Error while writing profile file: " + e.message))
                }
            }

            val spawnSummary: com.google.common.collect.ImmutableMap<String?, Int?> = spawnStats.getSummary()
            val spawnSummaryString: String = SpawnStats.Companion.convertSummaryToString(spawnSummary)
            if (statsSummary) {
                reporter.handle(com.google.devtools.build.lib.events.Event.info(spawnSummaryString))
                reporter.handle(
                    com.google.devtools.build.lib.events.Event.info(
                        String.format(
                            "Total action wall time %.2fs", spawnStats.getTotalWallTimeMillis() / 1000.0
                        )
                    )
                )
                if (criticalPath != AggregatedCriticalPath.EMPTY) {
                    reporter.handle(com.google.devtools.build.lib.events.Event.info(criticalPath.getNewStringSummary()))
                }
                val now: Long = event.getResult().getStopTime()
                val executionTime = executionEndMillis - executionStartMillis
                val overheadTime = now - commandStartMillis - executionTime
                reporter.handle(
                    com.google.devtools.build.lib.events.Event.info(
                        String.format(
                            "Elapsed time %.2fs (preparation %.2fs, execution %.2fs)",
                            (now - commandStartMillis) / 1000.0,
                            overheadTime / 1000.0,
                            executionTime / 1000.0
                        )
                    )
                )
                logger.atInfo().log("Stats summary: %s", com.google.common.base.Joiner.on(", ").join(items))
            } else {
                reporter.handle(
                    com.google.devtools.build.lib.events.Event.info(
                        com.google.common.base.Joiner.on(", ").join(items)
                    )
                )
                reporter.handle(com.google.devtools.build.lib.events.Event.info(spawnSummaryString))
            }

            event
                .getResult().buildToolLogCollection
                .addDirectValue(
                    "process stats",
                    spawnSummaryString.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
                )
        } finally {
            if (criticalPathComputer != null) {
                eventBus.unregister(criticalPathComputer)
                criticalPathComputer = null
            }
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    }
}
