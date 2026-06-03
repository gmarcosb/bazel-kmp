// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.testutil

import com.google.devtools.build.lib.actions.ActionCompletionEvent

/** Records various action-related events for tests.  */
class ActionEventRecorder {
    private val actionStartedEvents: MutableList<ActionStartedEvent?> =
        Collections.synchronizedList<ActionStartedEvent?>(java.util.ArrayList<ActionStartedEvent?>())
    private val actionCompletionEvents: MutableList<ActionCompletionEvent?> =
        Collections.synchronizedList<ActionCompletionEvent?>(java.util.ArrayList<ActionCompletionEvent?>())
    private val actionExecutedEvents: MutableList<ActionExecutedEvent?> =
        Collections.synchronizedList<ActionExecutedEvent?>(java.util.ArrayList<ActionExecutedEvent?>())
    private val actionResultReceivedEvents: MutableList<ActionResultReceivedEvent?> =
        Collections.synchronizedList<ActionResultReceivedEvent?>(java.util.ArrayList<ActionResultReceivedEvent?>())
    private val cachedActionEvents: MutableList<CachedActionEvent> =
        Collections.synchronizedList<CachedActionEvent>(java.util.ArrayList<CachedActionEvent>())
    private val actionRewoundEvents: MutableList<ActionRewoundEvent?> =
        Collections.synchronizedList<ActionRewoundEvent?>(java.util.ArrayList<ActionRewoundEvent?>())
    private val actionRewindingStatsPosts: MutableList<PostableActionRewindingStats> =
        Collections.synchronizedList<PostableActionRewindingStats>(java.util.ArrayList<PostableActionRewindingStats>())

    private var actionRewoundEventSubscriber: java.util.function.Consumer<ActionRewoundEvent?> =
        java.util.function.Consumer { e: ActionRewoundEvent? -> }

    fun setActionRewoundEventSubscriber(subscriber: java.util.function.Consumer<ActionRewoundEvent?>) {
        actionRewoundEventSubscriber = subscriber
    }

    fun getActionStartedEvents(): MutableList<ActionStartedEvent?> {
        return actionStartedEvents
    }

    fun getActionCompletionEvents(): MutableList<ActionCompletionEvent?> {
        return actionCompletionEvents
    }

    fun getActionExecutedEvents(): MutableList<ActionExecutedEvent?> {
        return actionExecutedEvents
    }

    fun getActionResultReceivedEvents(): MutableList<ActionResultReceivedEvent?> {
        return actionResultReceivedEvents
    }

    fun getActionRewoundEvents(): MutableList<ActionRewoundEvent?> {
        return actionRewoundEvents
    }

    fun getActionRewindingStatsPosts(): MutableList<PostableActionRewindingStats> {
        return actionRewindingStatsPosts
    }

    @Suppress("unused")
    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun actionStarted(event: ActionStartedEvent?) {
        actionStartedEvents.add(event)
    }

    @Suppress("unused")
    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun actionCompleted(event: ActionCompletionEvent?) {
        actionCompletionEvents.add(event)
    }

    @Suppress("unused")
    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun actionExecuted(event: ActionExecutedEvent?) {
        actionExecutedEvents.add(event)
    }

    @Suppress("unused")
    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun actionResultReceived(event: ActionResultReceivedEvent?) {
        actionResultReceivedEvents.add(event)
    }

    @Suppress("unused")
    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun cachedAction(event: CachedActionEvent?) {
        cachedActionEvents.add(event)
    }

    @Suppress("unused")
    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun actionRewound(event: ActionRewoundEvent?) {
        actionRewoundEvents.add(event)
        actionRewoundEventSubscriber.accept(event)
    }

    @Suppress("unused")
    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun actionRewindingStats(actionRewindingStats: PostableActionRewindingStats?) {
        actionRewindingStatsPosts.add(actionRewindingStats)
    }

    fun clear() {
        actionStartedEvents.clear()
        actionCompletionEvents.clear()
        actionExecutedEvents.clear()
        actionResultReceivedEvents.clear()
        cachedActionEvents.clear()
        actionRewoundEvents.clear()
        actionRewindingStatsPosts.clear()
    }

    /**
     * Check how many of each type of event was emitted during a successful build.
     * 
     * @param runOnce Actions which ran and are not rewound
     * @param completedRewound Actions which ran and then are rewound by a later failed action
     * @param failedRewound Actions which fail because of lost inputs and which rewind themselves and
     * the actions that generate those lost inputs
     */
    fun assertEvents(
        runOnce: com.google.common.collect.ImmutableList<String>,
        completedRewound: com.google.common.collect.ImmutableList<String>,
        failedRewound: com.google.common.collect.ImmutableList<String>,
        actionRewindingPostLostInputCounts: com.google.common.collect.ImmutableList<Int?>
    ) {
        assertEvents(
            runOnce,
            completedRewound,
            failedRewound,  /* expectResultReceivedForFailedRewound= */
            true,
            actionRewindingPostLostInputCounts
        )
    }

    /**
     * Like [.assertEvents]. The
     * `expectResultReceivedForFailedRewound` should be true iff the failed rewound actions ever
     * successfully complete.
     * 
     * @param expectResultReceivedForFailedRewound whether the failed rewound actions ever
     * successfully complete, because no [ActionResultReceivedEvent] is emitted for a failed
     * action
     */
    fun assertEvents(
        runOnce: com.google.common.collect.ImmutableList<String>,
        completedRewound: com.google.common.collect.ImmutableList<String>,
        failedRewound: com.google.common.collect.ImmutableList<String>,
        expectResultReceivedForFailedRewound: Boolean,
        actionRewindingPostLostInputCounts: com.google.common.collect.ImmutableList<Int?>
    ) {
        val eventCountAsserter =
            EventCountAsserter(runOnce, completedRewound, failedRewound)

        eventCountAsserter.assertEventCounts<ActionStartedEvent?>( /* events= */
            actionStartedEvents,  /* eventsName= */
            "actionStartedEvents",  /* converter= */
            java.util.function.Function { e: ActionStartedEvent? -> progressMessageOrPrettyPrint(e.getAction()) },  /* expectedRunOnceEventCount= */
            1,  /* expectedCompletedRewoundEventCount= */
            1,  /* expectedFailedRewoundEventCount= */
            2
        )

        eventCountAsserter.assertEventCounts<ActionCompletionEvent?>( /*events=*/
            actionCompletionEvents,  /*eventsName=*/
            "actionCompletionEvents",  /*converter=*/
            java.util.function.Function { e: ActionCompletionEvent? -> progressMessageOrPrettyPrint(e.getAction()) },  /*expectedRunOnceEventCount=*/
            1,  /*expectedCompletedRewoundEventCount=*/
            1,  /*expectedFailedRewoundEventCount=*/
            1
        )

        eventCountAsserter.assertEventCounts<ActionExecutedEvent?>( /*events=*/
            actionExecutedEvents,  /*eventsName=*/
            "actionExecutedEvents",  /*converter=*/
            java.util.function.Function { e: ActionExecutedEvent? -> progressMessageOrPrettyPrint(e.getAction()) },  /*expectedRunOnceEventCount=*/
            1,  /*expectedCompletedRewoundEventCount=*/
            1,  /*expectedFailedRewoundEventCount=*/
            1
        )

        eventCountAsserter.assertEventCounts<ActionResultReceivedEvent?>( /*events=*/
            actionResultReceivedEvents,  /*eventsName=*/
            "actionResultReceivedEvents",  /*converter=*/
            java.util.function.Function { e: ActionResultReceivedEvent? -> progressMessageOrPrettyPrint(e.getAction()) },  /*expectedRunOnceEventCount=*/
            1,  /*expectedCompletedRewoundEventCount=*/
            1,  /*expectedFailedRewoundEventCount=*/
            if (expectResultReceivedForFailedRewound) 1 else 0
        )

        eventCountAsserter.assertEventCounts<ActionRewoundEvent?>( /*events=*/
            actionRewoundEvents,  /*eventsName=*/
            "actionRewoundEvents",  /*converter=*/
            java.util.function.Function { e: ActionRewoundEvent? -> progressMessageOrPrettyPrint(e.getFailedRewoundAction()) },  /*expectedRunOnceEventCount=*/
            0,  /*expectedCompletedRewoundEventCount=*/
            0,  /*expectedFailedRewoundEventCount=*/
            1
        )

        assertTotalLostInputCountsFromStats(actionRewindingPostLostInputCounts)
        Truth.assertThat(cachedActionEvents).isEmpty()
    }

    /**
     * Asserts that the total lost input counts from posted [PostableActionRewindingStats]
     * matches expected results.
     * 
     * @param totalLostInputCounts - The list of the counts of all lost inputs logged with each [     ] post.
     */
    fun assertTotalLostInputCountsFromStats(totalLostInputCounts: com.google.common.collect.ImmutableList<Int?>) {
        Truth.assertThat(actionRewindingStatsPosts).hasSize(totalLostInputCounts.size)
        for (postIndex in totalLostInputCounts.indices) {
            val actionRewindingStats: PostableActionRewindingStats = actionRewindingStatsPosts.get(postIndex)
            assertThat(actionRewindingStats.lostInputsCount())
                .isEqualTo(totalLostInputCounts.get(postIndex))
        }
    }

    /**
     * Asserts that the total lost output counts from posted [PostableActionRewindingStats]
     * matches expected results.
     * 
     * @param totalLostOutputCounts - The list of the counts of all lost outputs logged with each
     * [PostableActionRewindingStats] post.
     */
    fun assertTotalLostOutputCountsFromStats(totalLostOutputCounts: com.google.common.collect.ImmutableList<Int?>) {
        Truth.assertThat(actionRewindingStatsPosts).hasSize(totalLostOutputCounts.size)
        for (postIndex in totalLostOutputCounts.indices) {
            val actionRewindingStats: PostableActionRewindingStats = actionRewindingStatsPosts.get(postIndex)
            assertThat(actionRewindingStats.lostOutputsCount())
                .isEqualTo(totalLostOutputCounts.get(postIndex))
        }
    }

    private class EventCountAsserter(
        runOnce: com.google.common.collect.ImmutableList<String>,
        completedRewound: com.google.common.collect.ImmutableList<String>,
        failedRewound: com.google.common.collect.ImmutableList<String>
    ) {
        private val runOnce: com.google.common.collect.ImmutableList<String>
        private val completedRewound: com.google.common.collect.ImmutableList<String>
        private val failedRewound: com.google.common.collect.ImmutableList<String>

        init {
            this.runOnce = runOnce
            this.completedRewound = completedRewound
            this.failedRewound = failedRewound
        }

        fun <T> assertEventCounts(
            events: MutableList<T?>,
            eventsName: String?,
            converter: java.util.function.Function<T?, String?>?,
            expectedRunOnceEventCount: Int,
            expectedCompletedRewoundEventCount: Int,
            expectedFailedRewoundEventCount: Int
        ) {
            val eventDescriptions: com.google.common.collect.ImmutableList<String?> =
                events.stream().map<String?>(converter)
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())
            for (runOnceAction in runOnce) {
                Truth.assertWithMessage("Run-once action \"%s\" in %s", runOnceAction, eventsName)
                    .that(eventDescriptions.stream().filter { d: String? -> d == runOnceAction }.count())
                    .isEqualTo(expectedRunOnceEventCount)
            }
            for (rewoundAction in completedRewound) {
                Truth.assertWithMessage("Completed rewound action \"%s\" in %s", rewoundAction, eventsName)
                    .that(eventDescriptions.stream().filter { d: String? -> d == rewoundAction }.count())
                    .isEqualTo(expectedCompletedRewoundEventCount)
            }
            for (failedRewoundAction in failedRewound) {
                Truth.assertWithMessage("Failed rewound action \"%s\" in %s", failedRewoundAction, eventsName)
                    .that(eventDescriptions.stream().filter { d: String? -> d == failedRewoundAction }.count())
                    .isEqualTo(expectedFailedRewoundEventCount)
            }
        }
    }

    companion object {
        fun progressMessageOrPrettyPrint(action: ActionExecutionMetadata): String? {
            val progressMessage: String? = action.getProgressMessage()
            return if (progressMessage != null) progressMessage else action.prettyPrint()
        }
    }
}
