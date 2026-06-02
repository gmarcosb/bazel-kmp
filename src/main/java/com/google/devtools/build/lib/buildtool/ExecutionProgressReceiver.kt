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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.actions.ActionChangePrunedEvent

/**
 * Listener for executed actions and built artifacts. We use a listener so that we have an accurate
 * set of successfully run actions and built artifacts, even if the build is interrupted.
 */
class ExecutionProgressReceiver
    (
    /** Number of exclusive tests. To be accounted for in progress messages.  */
    private val exclusiveTestsCount: Int, eventBus: com.google.common.eventbus.EventBus
) : ProgressSupplier, ActionCompletedReceiver, EvaluationProgressReceiver {
    private val enqueuedActions: MutableSet<ActionLookupData?> =
        com.google.common.collect.Sets.newConcurrentHashSet<ActionLookupData?>()
    private val completedActions: MutableSet<ActionLookupData?> =
        com.google.common.collect.Sets.newConcurrentHashSet<ActionLookupData?>()
    private val eventBus: com.google.common.eventbus.EventBus

    /**
     * `builtTargets` is accessed through a synchronized set, and so no other access to it is
     * permitted while this receiver is active.
     */
    init {
        this.eventBus = eventBus
    }

    override fun enqueueing(skyKey: SkyKey) {
        if (skyKey.functionName() == SkyFunctions.ACTION_EXECUTION) {
            val actionLookupData: ActionLookupData? = skyKey.argument() as ActionLookupData?
            // Remember all enqueued actions for the benefit of progress reporting.
            // We discover most actions early in the build, well before we start executing them.
            // Some of these will be cache hits and won't be executed, so we'll need to account for them
            // in the evaluated method too.
            enqueuedActions.add(actionLookupData)
        }
    }

    override fun evaluated(
        skyKey: SkyKey,
        state: EvaluationState,
        newValue: SkyValue?,
        newError: com.google.devtools.build.skyframe.ErrorInfo?,
        directDeps: GroupedDeps?
    ) {
        val type: SkyFunctionName = skyKey.functionName()
        if (type == SkyFunctions.ACTION_EXECUTION) {
            // Remember all completed actions, even those in error, regardless of having been cached or
            // really executed.
            actionCompleted(skyKey.argument() as ActionLookupData?)
            return
        }

        if (!state.succeeded()) {
            return
        }

        if (type == SkyFunctions.TARGET_COMPLETION) {
            val configuredTargetKey: ConfiguredTargetKey? =
                (skyKey as TargetCompletionKey).actionLookupKey()
            eventBus.post(TopLevelTargetBuiltEvent.create(configuredTargetKey))
            return
        }

        if (type == SkyFunctions.ASPECT_COMPLETION) {
            val aspectKey: AspectKey? =
                (skyKey as AspectCompletionKey).actionLookupKey()
            eventBus.post(AspectBuiltEvent.create(aspectKey))
            return
        }

        if (type == SkyFunctions.BUILD_DRIVER) {
            val buildDriverKey: BuildDriverKey = skyKey as BuildDriverKey
            // BuildDriverKeys are re-evaluated every build.
            val buildDriverValue: BuildDriverValue =
                com.google.common.base.Preconditions.checkNotNull<SkyValue?>(newValue) as BuildDriverValue

            if (buildDriverValue.isSkipped()) {
                return
            }

            if (buildDriverKey.isTopLevelAspectDriver()) {
                (buildDriverValue.getWrappedSkyValue() as TopLevelAspectsValue)
                    .getTopLevelAspectsMap()
                    .keySet()
                    .forEach(java.util.function.Consumer { x: AspectKey? -> eventBus.post(AspectBuiltEvent.create(x)) })
                return
            }

            eventBus.post(
                TopLevelTargetBuiltEvent.create(
                    ConfiguredTargetKey.fromConfiguredTarget(
                        (buildDriverValue.getWrappedSkyValue() as ConfiguredTargetValue)
                            .getConfiguredTarget()
                    )
                )
            )
        }
    }

    override fun changePruned(skyKey: SkyKey) {
        if (skyKey.functionName() == SkyFunctions.ACTION_EXECUTION) {
            eventBus.post(
                ActionChangePrunedEvent(
                    skyKey.argument() as ActionLookupData?,
                    com.google.devtools.build.lib.clock.BlazeClock.nanoTime()
                )
            )
        }
    }

    /**
     * {@inheritDoc}
     * 
     * 
     * This method adds the action lookup data to [.completedActions] and notifies the [ ][.activityIndicator].
     * 
     * 
     * We could do this only in the [EvaluationProgressReceiver.evaluated] method too, but as
     * it happens the action executor tells the reporter about the completed action before the node is
     * inserted into the graph, so the reporter would find out about the completed action sooner than
     * we could have updated [.completedActions], which would result in incorrect numbers on the
     * progress messages. However we have to store completed actions in [ ][EvaluationProgressReceiver.evaluated] too, because that's the only place we get notified about
     * completed cached actions.
     */
    override fun actionCompleted(actionLookupData: ActionLookupData?) {
        enqueuedActions.add(actionLookupData)
        completedActions.add(actionLookupData)
    }

    val progressString: String?
        get() = java.lang.String.format(
            "[%s / %s]",
            PROGRESS_MESSAGE_NUMBER_FORMATTER.get()
                .format(completedActions.size().toLong()),
            PROGRESS_MESSAGE_NUMBER_FORMATTER
                .get()
                .format((exclusiveTestsCount + enqueuedActions.size()).toLong())
        )

    fun createInactivityMonitor(
        statusReporter: ActionExecutionStatusReporter
    ): InactivityMonitor {
        return object : InactivityMonitor() {
            override fun hasStarted(): Boolean {
                return !enqueuedActions.isEmpty()
            }

            val pending: Int
                get() = statusReporter.getCount()

            @Throws(java.lang.InterruptedException::class)
            override fun waitForNextCompletion(timeoutSeconds: Int): Int {
                val before: Int = completedActions.size()
                // Otherwise, wake up once per second to see whether something completed.
                for (i in 0..<timeoutSeconds) {
                    java.lang.Thread.sleep(1000)
                    val count: Int = completedActions.size() - before
                    if (count > 0) {
                        return count
                    }
                }
                return 0
            }
        }
    }

    fun createInactivityReporter(
        statusReporter: ActionExecutionStatusReporter,
        isBuildingExclusiveArtifacts: AtomicBoolean
    ): InactivityReporter {
        return object : InactivityReporter() {
            override fun maybeReportInactivity(lastActionCompletedAt: Instant?) {
                // Do not report inactivity if we are currently running an exclusive test or a streaming
                // action (in practice only tests can stream and it implicitly makes them exclusive).
                if (!isBuildingExclusiveArtifacts.get()) {
                    statusReporter.showCurrentlyExecutingActions(
                        this@ExecutionProgressReceiver.progressString + " "
                    )
                    eventBus.post(ActionExecutionInactivityEvent(lastActionCompletedAt))
                }
            }
        }
    }

    fun hasActionsInFlight(): Boolean {
        return completedActions.size() < exclusiveTestsCount + enqueuedActions.size()
    }

    companion object {
        private val PROGRESS_MESSAGE_NUMBER_FORMATTER: java.lang.ThreadLocal<NumberFormat?> =
            java.lang.ThreadLocal.withInitial<NumberFormat?>(
                java.util.function.Supplier {
                    val numberFormat: NumberFormat = NumberFormat.getIntegerInstance(Locale.ENGLISH)
                    numberFormat.setGroupingUsed(true)
                    numberFormat
                })
    }
}
