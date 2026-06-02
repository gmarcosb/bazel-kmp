// Copyright 2021 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.AliasProvider

/** Aggregates and reports target-wide final statuses in real-time.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
class TargetSummaryPublisher(
    eventBus: com.google.common.eventbus.EventBus,
    mergedSkyframeAnalysisExecution: com.google.common.base.Supplier<Boolean?>
) {
    private val eventBus: com.google.common.eventbus.EventBus
    private val mergedSkyframeAnalysisExecution: com.google.common.base.Supplier<Boolean?>

    /** Whether or not toplevel aspects are present, from [BuildStartingEvent].  */
    private val hasAspects: AtomicBoolean = AtomicBoolean(false)

    private val aggregators: ConcurrentHashMap<ConfiguredTargetKey?, TargetSummaryAggregator?> =
        ConcurrentHashMap<ConfiguredTargetKey?, TargetSummaryAggregator?>()
    private val aspectCountPerTarget: ConcurrentHashMap<ConfiguredTargetKey?, Int?> =
        ConcurrentHashMap<ConfiguredTargetKey?, Int?>()
    private val queuedAspectCompleteEvents: com.google.common.collect.ListMultimap<ConfiguredTargetKey?, AspectCompleteEvent?> =
        com.google.common.collect.Multimaps.synchronizedListMultimap<ConfiguredTargetKey?, AspectCompleteEvent?>(com.google.common.collect.ArrayListMultimap.create<ConfiguredTargetKey?, AspectCompleteEvent?>())

    init {
        this.eventBus = eventBus
        this.mergedSkyframeAnalysisExecution = mergedSkyframeAnalysisExecution
    }

    /**
     * Extracts how many aspect completions per target to expect. This must happen before [ ][.populateTargets].
     * 
     * 
     * This excludes --exec_aspects.
     */
    @com.google.common.eventbus.Subscribe
    fun buildStarting(event: BuildStartingEvent) {
        hasAspects.set(!event.request().getAspects().isEmpty())
    }

    /**
     * Reports the correct number of top-level aspects that were analyzed for a given configured
     * target.
     * 
     * 
     * If analysis is successful, this event will be posted for all targets with any aspects, both
     * with and without skymeld (merged analysis+execution).
     * 
     * 
     * When skymeld is *disabled*, all of these events are posted strictly before the [ ].
     * 
     * 
     * When skymeld is *enabled*, all of these events are posted strictly before any [ ] is posted for the same configured target. They are posted concurrently
     * with the [TopLevelTargetPendingExecutionEvent] that is posted for the same target.
     */
    @com.google.common.eventbus.Subscribe
    fun toplevelAspectsIdentified(event: ToplevelAspectsIdentifiedEvent) {
        val targetKey: ConfiguredTargetKey? = event.baseConfiguredTargetKey
        val numTopLevelAspects: Int = event.numTopLevelAspects
        synchronized(aggregators) {
            aspectCountPerTarget.put(targetKey, numTopLevelAspects)
            if (aggregators.containsKey(targetKey)) {
                // We may have already set the expected aspect completions if this method is racing with
                // #populateTarget(). This is safe because we guarantee that we set the same value, so that
                // no aspect completions have happened when we double-set the expected aspect completions.
                aggregators.get(targetKey).setExpectAspectCompletions(numTopLevelAspects)
            }
        }
    }

    /**
     * Populates the target summary map as soon as test filtering is complete. This is the earliest at
     * which the final set of targets to build and test is known. This must happen after [ ][.buildStarting].
     */
    @com.google.common.eventbus.Subscribe
    fun populateTargets(event: TestFilteringCompleteEvent) {
        val testTargets: com.google.common.collect.ImmutableSet<ConfiguredTarget?> =
            if (event.getTestTargets() != null)
                com.google.common.collect.ImmutableSet.copyOf<ConfiguredTarget?>(event.getTestTargets())
            else
                com.google.common.collect.ImmutableSet.of<ConfiguredTarget?>()
        val skippedTests: com.google.common.collect.ImmutableSet<ConfiguredTarget?> =
            com.google.common.collect.ImmutableSet.copyOf<ConfiguredTarget?>(event.getSkippedTests())
        for (target in event.getTargets()) {
            if (skippedTests.contains(target)) {
                // Skipped tests aren't built, and won't receive completion events, so we ignore them.
                // Note we'll still get (and ignore) a TestSummary event, but that event isn't published to
                // BEP.
                continue
            }
            val configuredTargetKey: ConfiguredTargetKey? = asKey(target)
            val newAggregator =
                createAggregatorForTarget( /* isTest= */testTargets.contains(target), target)
            synchronized(aggregators) {
                if (aspectCountPerTarget.containsKey(configuredTargetKey)) {
                    newAggregator.setExpectAspectCompletions(aspectCountPerTarget.get(configuredTargetKey))
                }
                val oldAggregator: TargetSummaryAggregator? =
                    aggregators.putIfAbsent(configuredTargetKey, newAggregator)
                com.google.common.base.Preconditions.checkState(
                    oldAggregator == null,
                    "target: %s, values: %s %s",
                    target,
                    oldAggregator,
                    newAggregator
                )
            }
        }
    }

    /**
     * Populates the aggregator for a particular top level target, including test targets.
     * 
     * 
     * With skymeld, the corresponding AspectCompleteEvents may arrive before the aggregator is set
     * up. We therefore need to put those events in a queue and resolve them when the aggregator
     * becomes available.
     */
    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun populateTarget(event: TopLevelTargetPendingExecutionEvent) {
        val configuredTargetKey: ConfiguredTargetKey? = asKey(event.configuredTarget)
        synchronized(aggregators) {
            val newAggregator =
                createAggregatorForTarget(event.isTest, event.configuredTarget)
            if (aspectCountPerTarget.containsKey(configuredTargetKey)) {
                newAggregator.setExpectAspectCompletions(aspectCountPerTarget.get(configuredTargetKey))
            }
            val oldAggregator: TargetSummaryAggregator? =
                aggregators.putIfAbsent(configuredTargetKey, newAggregator)
            com.google.common.base.Preconditions.checkState(
                oldAggregator == null,
                "target: %s, values: %s %s",
                configuredTargetKey,
                oldAggregator,
                newAggregator
            )
            if (queuedAspectCompleteEvents.containsKey(configuredTargetKey)) {
                queuedAspectCompleteEvents
                    .get(configuredTargetKey)
                    .forEach(java.util.function.Consumer { e: AspectCompleteEvent? ->
                        newAggregator.addAspectCompletionEvent(
                            !e.failed()
                        )
                    })
                queuedAspectCompleteEvents.removeAll(configuredTargetKey)
            }
        }
    }

    /**
     * Creates a TargetSummaryAggregator for the given target.
     * 
     * @return the created aggregator.
     */
    private fun createAggregatorForTarget(
        isTest: Boolean, target: ConfiguredTarget
    ): TargetSummaryAggregator {
        // We want target summaries for alias targets, but note they don't receive test summaries.
        return TargetSummaryAggregator(
            target, isTest && !AliasProvider.isAlias(target), hasAspects.get()
        )
    }

    @com.google.common.eventbus.Subscribe
    fun buildCompleteEvent(event: BuildCompleteEvent) {
        val result: BuildResult = event.getResult()
        val actualTargets: MutableCollection<ConfiguredTarget>? = result.getActualTargets()
        val successfulTargets: MutableCollection<ConfiguredTarget?>? = result.getSuccessfulTargets()
        if (actualTargets == null || successfulTargets == null) {
            return
        }

        // Count out how many aspects have succeeded for each target
        val aspectSuccesses: com.google.common.collect.ImmutableMap<ConfiguredTargetKey?, Int?> =
            result.getSuccessfulAspects().stream()
                .collect(
                    com.google.common.collect.ImmutableMap.toImmutableMap<T?, K?, V?>(
                        java.util.function.Function { obj: T? -> obj.getBaseConfiguredTargetKey() },
                        java.util.function.Function { unused: T? -> 1 },
                        BinaryOperator { a: Int, b: Int -> java.lang.Integer.sum(a, b) })
                )

        // Now go through all targets and set overall build success. This is a backstop against missing
        // {Target|Aspect}Completed events (e.g., due to interruption or failing fast after failures).
        val builtTargets: com.google.common.collect.ImmutableSet<ConfiguredTarget?> =
            com.google.common.collect.ImmutableSet.copyOf<ConfiguredTarget?>(successfulTargets)
        for (target in actualTargets) {
            val targetKey: ConfiguredTargetKey? = asKey(target)
            // If we have not seen the ToplevelAspectsIdentifiedEvent for a target, and we are expecting
            // aspects, then we know the target failed to build all its aspects as we never even analyzed
            // any aspects. Set expectedAspectSuccesses to Integer.MAX_VALUE to make clear: we failed.
            val expectedAspectSuccesses: Int =
                aspectCountPerTarget.getOrDefault(targetKey, if (hasAspects.get()) java.lang.Integer.MAX_VALUE else 0)
            val aggregator: TargetSummaryAggregator? = aggregators.get(targetKey)
            if (aggregator != null && !aggregator.published.get()) {
                // Overall success means all aspects were successful and the target didn't fail to build
                val successfulAspectCount: Int = aspectSuccesses.getOrDefault(targetKey, 0)
                com.google.common.base.Preconditions.checkState(
                    successfulAspectCount <= expectedAspectSuccesses,
                    "for target %s got %s successful aspects, expected at most %s",
                    targetKey,
                    successfulAspectCount,
                    expectedAspectSuccesses
                )
                aggregator.setOverallBuildSuccess(
                    builtTargets.contains(target) && successfulAspectCount == expectedAspectSuccesses
                )
            }
        }
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun targetComplete(event: TargetCompleteEvent) {
        val aggregator: TargetSummaryAggregator? = aggregators.get(event.getConfiguredTargetKey())
        if (aggregator != null && !aggregator.published.get()) {
            aggregator.addCompletionEvent(!event.failed())
        }
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun aspectComplete(event: AspectCompleteEvent) {
        val aggregator: TargetSummaryAggregator?
        // Prevent a race condition where #populateTarget finishes checking the
        // queuedAspectCompleteEvents before the entries are added by this method:
        // aspectComplete: (sees aggregator == null)                                  (adds to queue)
        // populateTarget:                         (creates aggregator) (checks queue)
        synchronized(aggregators) {
            aggregator = aggregators.get(event.getAspectKey().getBaseConfiguredTargetKey())
            // With skymeld, the corresponding AspectCompleteEvents may arrive before the aggregator is
            // set up. We therefore need to put those events in a queue and resolve them when the
            // aggregator becomes available.
            if (mergedSkyframeAnalysisExecution.get() && aggregator == null) {
                queuedAspectCompleteEvents.put(event.getAspectKey().getBaseConfiguredTargetKey(), event)
                return
            }
        }

        if (aggregator != null && !aggregator.published.get()) {
            aggregator.addAspectCompletionEvent(!event.failed())
        }
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun testSummaryEvent(event: TestSummary) {
        val aggregator: TargetSummaryAggregator? = aggregators.get(asKey(event.getTarget()))
        if (aggregator != null && !aggregator.published.get()) {
            aggregator.setTestSummary(event.getStatus())
        }
    }

    private inner class TargetSummaryAggregator(
        target: ConfiguredTarget,
        expectTestSummary: Boolean,
        expectAspectCompletions: Boolean
    ) {
        private val target: ConfiguredTarget
        private val expectAspectCompletions: Boolean
        private val expectTestSummary: Boolean

        /**
         * Whether a TargetSummary for [.target] has been published. Users of this class can avoid
         * unnecessary synchronization by not calling synchronized methods if this flag is `true`.
         */
        private val published: AtomicBoolean = AtomicBoolean(false)

        /** Whether or not the target has completed being built, or if there was any build failure.  */
        @com.google.errorprone.annotations.concurrent.GuardedBy("this")
        private var targetCompleted: Boolean

        /** Aspect completion events we're still waiting on (always 0 if [.hasBuildFailure]).  */
        @com.google.errorprone.annotations.concurrent.GuardedBy("this")
        private var remainingAspectCompletions: Int

        @com.google.errorprone.annotations.concurrent.GuardedBy("this")
        private var hasBuildFailure = false

        @com.google.errorprone.annotations.concurrent.GuardedBy("this")
        private var testStatus: BlazeTestStatus? = null

        init {
            this.target = target
            this.expectTestSummary = expectTestSummary
            this.expectAspectCompletions = expectAspectCompletions
            targetCompleted = false
            remainingAspectCompletions = -1
        }

        @kotlin.jvm.Synchronized
        fun setExpectAspectCompletions(newRemainingAspectCompletions: Int) {
            com.google.common.base.Preconditions.checkState(
                this.expectAspectCompletions,
                "Cannot track aspects unless --aspects is set."
            )
            com.google.common.base.Preconditions.checkState(
                remainingAspectCompletions < 0 || remainingAspectCompletions == newRemainingAspectCompletions || hasBuildFailure,
                "Cannot call setExpectAspectCompletions() twice on a single target after aspect"
                        + " completions have begun. Was %s, got %s.",
                remainingAspectCompletions,
                newRemainingAspectCompletions
            )
            // If we have already had a build failure (because the target failed) then we have set
            // remainingAspectCompletions = 0 and it should stay at zero.
            if (hasBuildFailure) {
                return
            }

            remainingAspectCompletions = newRemainingAspectCompletions
        }

        @kotlin.jvm.Synchronized
        fun addCompletionEvent(success: Boolean) {
            if (targetCompleted) {
                return  // already published or still waiting on aspects or test summary
            }
            targetCompleted = true
            if (!success) {
                remainingAspectCompletions = 0
                hasBuildFailure = true
            }
            publishOnceWhenReady()
        }

        @kotlin.jvm.Synchronized
        fun addAspectCompletionEvent(success: Boolean) {
            if (remainingAspectCompletions <= 0) {
                return  // already published or still waiting on target or test summary
            }
            if (success) {
                --remainingAspectCompletions
            } else {
                targetCompleted = true
                remainingAspectCompletions = 0
                hasBuildFailure = true
            }
            publishOnceWhenReady()
        }

        @kotlin.jvm.Synchronized
        fun setTestSummary(status: BlazeTestStatus?) {
            if (remainingAspectCompletions <= 0 && targetCompleted
                && (!expectTestSummary || testStatus != null)
            ) {
                return  // already published
            }
            testStatus = com.google.common.base.Preconditions.checkNotNull<BlazeTestStatus?>(status)
            publishOnceWhenReady()
        }

        @kotlin.jvm.Synchronized
        fun setOverallBuildSuccess(success: Boolean) {
            if (remainingAspectCompletions <= 0 && targetCompleted) {
                return  // already published or still waiting on test summary
            }
            targetCompleted = true
            remainingAspectCompletions = 0
            hasBuildFailure = !success
            publishOnceWhenReady()
        }

        /**
         * Publishes [TargetSummaryEvent] for [.target] if [.hasBuildFailure] or when
         * we have any test status as well as all completions ([.targetCompleted] and [ ][.remainingAspectCompletions] == 0).
         */
        @com.google.errorprone.annotations.concurrent.GuardedBy("this")
        fun publishOnceWhenReady() {
            val alreadyPublished: Boolean = published.get()
            val waitingForTargetCompletion = !targetCompleted
            val waitingForAspectCompletions =
                remainingAspectCompletions > 0
                        || (expectAspectCompletions && remainingAspectCompletions == -1)
            val waitingForTestStatus = !hasBuildFailure && expectTestSummary && testStatus == null
            if (waitingForTargetCompletion || waitingForAspectCompletions || waitingForTestStatus) {
                com.google.common.base.Preconditions.checkState(
                    !alreadyPublished,
                    "Shouldn't have published yet: %s",
                    target
                )
                return
            }
            if (alreadyPublished) {
                return
            }
            val event: TargetSummaryEvent =
                TargetSummaryEvent.Companion.create(target, !hasBuildFailure, expectTestSummary, testStatus)
            eventBus.post(event)

            published.set(true)
        }
    }

    companion object {
        private fun asKey(target: ConfiguredTarget): ConfiguredTargetKey? {
            // checkArgument(!isAlias(target));
            return ConfiguredTargetKey.builder()
                .setLabel(AliasProvider.getDependencyLabel(target))
                .setConfigurationKey(target.getConfigurationKey())
                .build()
        }
    }
}
