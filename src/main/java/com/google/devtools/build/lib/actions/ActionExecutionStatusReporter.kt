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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe

/**
 * Implements "Still waiting..." message functionality, displaying current status for "in-flight"
 * actions. Used by the ParallelBuilder.
 * 
 * TODO(bazel-team): (2010) It would be nice if "duplicated" actions (e.g. test shards and multiple
 * test runs) were merged into the single line.
 */
@ThreadSafe
class ActionExecutionStatusReporter private constructor(
    eventHandler: EventHandler?,
    eventBus: com.google.common.eventbus.EventBus?,
    clock: com.google.devtools.build.lib.clock.Clock?
) {
    private val eventHandler: EventHandler
    private val eventBus: com.google.common.eventbus.EventBus?
    private val clock: com.google.devtools.build.lib.clock.Clock

    /**
     * The status of each action "in flight", i.e. whose ExecuteBuildAction.call() method is active.
     * Used for implementing the "still waiting" message.
     */
    private val actionStatus: MutableMap<ActionExecutionMetadata?, Pair<String?, Long?>?> =
        ConcurrentHashMap<ActionExecutionMetadata?, Pair<String?, Long?>?>(100)

    init {
        this.eventHandler = com.google.common.base.Preconditions.checkNotNull<EventHandler>(eventHandler)
        this.eventBus = eventBus
        this.clock = com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.clock.Clock>(clock)
    }

    fun unregisterFromEventBus() {
        if (eventBus != null) {
            eventBus.unregister(this)
        }
    }

    private fun setStatus(action: ActionExecutionMetadata?, message: String?) {
        actionStatus.put(action, Pair.of(message, clock.nanoTime()))
    }

    /** Remove action from the list of active actions. Action must be present.  */
    fun remove(action: com.google.devtools.build.lib.actions.Action) {
        val status: Pair<String?, Long?>? = actionStatus.remove(action)
        if (status == null) {
            BugReport.sendNonFatalBugReport(
                java.lang.IllegalStateException("Action not present: " + action.prettyPrint())
            )
        }
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun updateStatus(event: ActionStartedEvent) {
        val action: ActionExecutionMetadata? = event.getAction()
        setStatus(action, PREPARING_MESSAGE)
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun updateStatus(event: ScanningActionEvent) {
        val action: ActionExecutionMetadata? = event.getActionMetadata()
        setStatus(action, "Scanning")
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun updateStatus(event: SchedulingActionEvent) {
        val action: ActionExecutionMetadata? = event.getActionMetadata()
        setStatus(action, "Scheduling")
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun updateStatus(event: RunningActionEvent) {
        val action: ActionExecutionMetadata? = event.getActionMetadata()
        setStatus(action, String.format("Running (%s)", event.getStrategy()))
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun updateStatus(event: StoppedScanningActionEvent) {
        remove(event.getAction())
    }

    fun getCount(): Int {
        return actionStatus.size
    }

    /**
     * Get message showing currently executing actions.
     */
    private fun getExecutionStatusMessage(
        statusMap: MutableMap<ActionExecutionMetadata?, Pair<String?, Long?>?>
    ): String {
        val count = statusMap.size
        val s: java.lang.StringBuilder = if (count != 1)
            java.lang.StringBuilder("Still waiting for ").append(count).append(" jobs to complete:")
        else
            java.lang.StringBuilder("Still waiting for 1 job to complete:")

        val currentTime: Long = clock.nanoTime()

        // A tree is just as fast as HashSet for small data sets.
        val statuses: MutableSet<String?> = TreeSet<String?>()
        for (entry in statusMap.entries) {
            statuses.add(entry.value.first)
        }

        for (status in statuses) {
            appendGroupStatus(s, statusMap, status, currentTime)
        }
        return s.toString()
    }

    /**
     * Show currently executing actions.
     */
    fun showCurrentlyExecutingActions(progressPercentageMessage: String?) {
        // Defensive copy to ensure thread safety.
        val statusMap: MutableMap<ActionExecutionMetadata?, Pair<String?, Long?>?> =
            HashMap<ActionExecutionMetadata?, Pair<String?, Long?>?>(actionStatus)
        if (!statusMap.isEmpty()) {
            eventHandler.handle(
                Event.progress(progressPercentageMessage + getExecutionStatusMessage(statusMap))
            )
        }
    }

    /**
     * Warn about actions that are still being executed.
     * Method is used to produce informative message when build is interrupted.
     */
    fun warnAboutCurrentlyExecutingActions() {
        // Defensive copy to ensure thread safety.
        val statusMap: MutableMap<ActionExecutionMetadata?, Pair<String?, Long?>?> =
            HashMap<ActionExecutionMetadata?, Pair<String?, Long?>?>(actionStatus)
        if (statusMap.isEmpty()) {
            // There are no tasks in the queue so there is nothing to report.
            eventHandler.handle(Event.warn("There are no active jobs - stopping the build"))
            return
        }
        val iterator: MutableIterator<ActionExecutionMetadata?> = statusMap.keys.iterator()
        while (iterator.hasNext()) {
            // Filter out actions that are not executed yet.
            if (PREPARING_MESSAGE == statusMap.get(iterator.next()).first) {
                iterator.remove()
            }
        }
        if (!statusMap.isEmpty()) {
            eventHandler.handle(
                Event.warn(
                    getExecutionStatusMessage(statusMap)
                            + "\nBuild will be stopped after these tasks terminate"
                )
            )
        } else {
            // It is possible that one or more tasks in "Preparing" state just started being executed.
            // So warn user just in case.
            eventHandler.handle(Event.warn("Still waiting for unfinished jobs"))
        }
    }

    companion object {
        // Maximum number of lines to output per each status category before truncation.
        private const val MAX_LINES = 10

        private const val PREPARING_MESSAGE = "Preparing"

        fun create(eventHandler: EventHandler?): ActionExecutionStatusReporter {
            return create(eventHandler, null, null)
        }

        @com.google.common.annotations.VisibleForTesting
        fun create(
            eventHandler: EventHandler?,
            clock: com.google.devtools.build.lib.clock.Clock?
        ): ActionExecutionStatusReporter {
            return create(eventHandler, null, clock)
        }

        fun create(
            eventHandler: EventHandler?, eventBus: com.google.common.eventbus.EventBus?
        ): ActionExecutionStatusReporter {
            return create(eventHandler, eventBus, null)
        }

        private fun create(
            eventHandler: EventHandler?,
            eventBus: com.google.common.eventbus.EventBus?,
            clock: com.google.devtools.build.lib.clock.Clock?
        ): ActionExecutionStatusReporter {
            val result =
                ActionExecutionStatusReporter(
                    eventHandler,
                    eventBus,
                    if (clock == null) com.google.devtools.build.lib.clock.BlazeClock.instance() else clock
                )
            if (eventBus != null) {
                eventBus.register(result)
            }
            return result
        }

        private fun appendGroupStatus(
            buffer: java.lang.StringBuilder,
            statusMap: MutableMap<ActionExecutionMetadata?, Pair<String?, Long?>?>, status: String?,
            currentTime: Long
        ) {
            val actions: MutableList<Pair<Long?, ActionExecutionMetadata?>> =
                java.util.ArrayList<Pair<Long?, ActionExecutionMetadata?>>()
            for (entry in statusMap.entries) {
                if (entry.value.first.equals(status)) {
                    actions.add(Pair.of(entry.value.second, entry.key))
                }
            }
            if (actions.isEmpty()) {
                return
            }
            TODO(
                """
                |Cannot convert element
                |With text:
                |Collections.<Pair<Long,ActionExecutionMetadata>>sort(actions, <Pair<Long,ActionExecutionMetadata>, U>comparing(arg -> arg.first)
                """.trimMargin()
            )


            buffer.append("\n      " + status + ":")

            val truncateList = actions.size > MAX_LINES
            for (entry in actions.subList(
                0,
                if (truncateList) MAX_LINES - 1 else actions.size
            )) {
                var message: String? = entry.second.getProgressMessage()
                if (message == null) {
                    // Actions will a null progress message should run so
                    // fast we never see them here.  In any case...
                    message = entry.second.prettyPrint()
                }
                buffer.append("\n        ").append(message)
                val runTime: Long = (currentTime - entry.first) / 1000000000L // Convert to seconds.
                buffer.append(", ").append(runTime).append(" s")
            }
            if (truncateList) {
                buffer.append("\n        ... ").append(actions.size - MAX_LINES + 1).append(" more jobs")
            }
        }

        /**
         * Returns the number of seconds to wait before reporting slow progress again.
         * 
         * @param userSpecifiedProgressInterval value of the --progress_report_interval flag; 0 means
         * use default 10, then 30, then 60 seconds wait times
         * @param previousWaitTime previous value returned by this method
         */
        fun getWaitTime(userSpecifiedProgressInterval: Int, previousWaitTime: Int): Int {
            if (userSpecifiedProgressInterval > 0) {
                return userSpecifiedProgressInterval
            }

            // Increase waitTime to 10, then to 30 and then to 60 seconds to reduce
            // spamming during long wait periods.  If the user specified a
            // waitTime directly through progressReportInterval, then use
            // that value.
            if (previousWaitTime == 0) {
                return 10
            } else if (previousWaitTime == 10) {
                return 30
            } else {
                return 60
            }
        }
    }
}
