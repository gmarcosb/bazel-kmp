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

import com.google.devtools.build.lib.analysis.AliasProvider

/** Aggregates and reports target-wide test statuses in real-time.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
class AggregatingTestListener(
    summaryOptions: TestSummaryOptions,
    executionOptions: ExecutionOptions,
    eventBus: com.google.common.eventbus.EventBus?
) {
    private val summaryOptions: TestSummaryOptions
    private val executionOptions: ExecutionOptions
    private val eventBus: com.google.common.eventbus.EventBus?

    @kotlin.concurrent.Volatile
    private var blazeHalted = false

    // Store information about potential failures in the presence of --nokeep_going or
    // --notest_keep_going.
    private var skipTargetsOnFailure = false

    private val aggregators: ConcurrentHashMap<ConfiguredTargetKey?, TestResultAggregator?>

    init {
        this.summaryOptions = summaryOptions
        this.executionOptions = executionOptions
        this.eventBus = eventBus

        this.aggregators = ConcurrentHashMap<ConfiguredTargetKey?, TestResultAggregator?>()
    }

    /**
     * Populates the test summary map as soon as test filtering is complete. This is the earliest at
     * which the final set of targets to test is known.
     * 
     * 
     * This is used in the non-Skymeld case.
     */
    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun populateTests(event: TestFilteringCompleteEvent) {
        val policy: com.google.devtools.build.lib.runtime.TestResultAggregator.AggregationPolicy =
            com.google.devtools.build.lib.runtime.TestResultAggregator.AggregationPolicy(
                eventBus,
                executionOptions.testCheckUpToDate,
                summaryOptions.getTestVerboseTimeoutWarnings()
            )
        // Add all target runs to the map, assuming 1:1 status artifact <-> result.
        for (target in event.getTestTargets()) {
            if (AliasProvider.isAlias(target)) {
                // It is safe to skip aliases because the actual target will be in event.getTestTargets().
                continue
            }
            val aggregator: TestResultAggregator =
                TestResultAggregator(
                    target,
                    event.getConfigurationForTarget(target),
                    policy,
                    event.getSkippedTests().contains(target)
                )
            val oldAggregator: TestResultAggregator? = aggregators.put(asKey(target), aggregator)
            com.google.common.base.Preconditions.checkState(
                oldAggregator == null, "target: %s, values: %s %s", target, oldAggregator, aggregator
            )
        }
    }

    /**
     * Creates the [TestResultAggregator] for the analyzed test target.
     * 
     * 
     * Since the event is fired from within a SkyFunction, it is possible to receive duplicate
     * events. In case of duplication, simply return without creating any new aggregator.
     * 
     * 
     * This is used in the Skymeld case.
     */
    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun populateTest(event: TestAnalyzedEvent) {
        val target: ConfiguredTarget = event.configuredTarget
        // Even if target is an alias, we still need to ensure that there's an aggregator present.
        // Nothing guarantees that the actual target's TestAnalyzedEvent is posted before the alias
        // completes the test (b/419325593). Using computeIfAbsent ensures that we have a single
        // aggregator, as this method can be called concurrently for an alias and its actual target.
        aggregators.computeIfAbsent(
            asKey(target),
            java.util.function.Function { k: ConfiguredTargetKey? ->
                TestResultAggregator(
                    target.getActual(),  // In case target is an alias.
                    event.buildConfigurationValue,
                    com.google.devtools.build.lib.runtime.TestResultAggregator.AggregationPolicy(
                        eventBus,
                        executionOptions.testCheckUpToDate,
                        summaryOptions.getTestVerboseTimeoutWarnings()
                    ),
                    event.isSkipped
                )
            })
    }

    /**
     * Records a new test run result and incrementally updates the target status. This event is sent
     * upon completion of executed test runs.
     */
    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun testEvent(result: TestResult) {
        val testAction: TestRunnerAction = result.getTestAction()
        val key: ConfiguredTargetKey? =
            ConfiguredTargetKey.builder()
                .setLabel(testAction.getOwner().getLabel())
                .setConfiguration(testAction.getConfiguration())
                .build()
        val aggregator: TestResultAggregator =
            com.google.common.base.Preconditions.checkNotNull<TestResultAggregator>(
                aggregators.get(key),
                "Missing aggregator for %s",
                key
            )
        aggregator.testEvent(result)
    }

    private fun targetFailure(configuredTargetKey: ConfiguredTargetKey?) {
        val aggregator: TestResultAggregator? = aggregators.get(configuredTargetKey)
        if (aggregator != null) {
            aggregator.targetFailure(blazeHalted, skipTargetsOnFailure)
        }
    }

    private fun targetSkipped(configuredTargetKey: ConfiguredTargetKey?) {
        val aggregator: TestResultAggregator? = aggregators.get(configuredTargetKey)
        if (aggregator != null) {
            aggregator.targetSkipped()
        }
    }

    @com.google.common.annotations.VisibleForTesting
    fun buildComplete(
        actualTargets: MutableCollection<ConfiguredTarget>?,
        skippedTargets: MutableCollection<ConfiguredTarget>,
        successfulTargets: MutableCollection<ConfiguredTarget>?
    ) {
        if (actualTargets == null || successfulTargets == null) {
            return
        }

        val nonSuccessfulTargets: com.google.common.collect.ImmutableSet<ConfiguredTarget> =
            com.google.common.collect.Sets.difference<ConfiguredTarget>(
                com.google.common.collect.ImmutableSet.copyOf<ConfiguredTarget?>(
                    actualTargets
                ), com.google.common.collect.ImmutableSet.copyOf<ConfiguredTarget?>(successfulTargets)
            )
                .immutableCopy()
        for (target in com.google.common.collect.Sets.difference<ConfiguredTarget>(
            com.google.common.collect.ImmutableSet.copyOf<ConfiguredTarget?>(nonSuccessfulTargets),
            com.google.common.collect.ImmutableSet.copyOf<ConfiguredTarget?>(skippedTargets)
        )) {
            if (AliasProvider.isAlias(target)) {
                continue
            }
            targetFailure(asKey(target))
        }

        for (target in skippedTargets) {
            if (AliasProvider.isAlias(target)) {
                continue
            }
            targetSkipped(asKey(target))
        }
    }

    @com.google.common.eventbus.Subscribe
    fun buildCompleteEvent(event: BuildCompleteEvent) {
        val result: BuildResult = event.getResult()
        if (result.wasCatastrophe()) {
            blazeHalted = true
        }
        skipTargetsOnFailure = result.stopOnFirstFailure
        buildComplete(
            result.getActualTargets(), result.getSkippedTargets(), result.getSuccessfulTargets()
        )
    }

    @com.google.common.eventbus.Subscribe
    fun analysisFailure(event: AnalysisFailureEvent) {
        targetFailure(event.getFailedTarget())
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun buildInterrupted(event: BuildInterruptedEvent?) {
        blazeHalted = true
    }

    /**
     * Called when a build action is not executed (e.g. because a dependency failed to build). We want
     * to catch such events in order to determine when a test target has failed to build.
     */
    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun targetComplete(event: TargetCompleteEvent) {
        if (event.failed()) {
            targetFailure(event.getConfiguredTargetKey())
        }
    }

    /**
     * Prints out the results of the given tests, and returns a [DetailedExitCode] summarizing
     * those test results. Posts any targets which weren't already completed by the listener to the
     * EventBus. Reports all targets on the console via the given notifier. Run at the end of the
     * build, run only once.
     * 
     * @param testTargets The list of targets being run
     * @param validatedTargets targets with ValidateTarget aspect success or null if aspect not used
     * @param notifier A console notifier to echo results to.
     * @return true if all the tests passed, else false
     */
    fun differentialAnalyzeAndReport(
        testTargets: MutableCollection<ConfiguredTarget>?,
        skippedTargets: MutableCollection<ConfiguredTarget>,
        validatedTargets: com.google.common.collect.ImmutableSet<ConfiguredTargetKey?>?,
        notifier: TestResultNotifier?
    ): DetailedExitCode {
        com.google.common.base.Preconditions.checkNotNull<MutableCollection<ConfiguredTarget?>?>(testTargets)
        com.google.common.base.Preconditions.checkNotNull<TestResultNotifier?>(notifier)

        // The natural ordering of the summaries defines their output order.
        val summaries: MutableSet<TestSummary?> = com.google.common.collect.Sets.newTreeSet<TestSummary?>()

        var totalRun = 0 // Number of targets running at least one non-cached test.
        var passCount = 0

        var systemFailure: DetailedExitCode? = null
        for (testTarget in testTargets!!) {
            val key: ConfiguredTargetKey? = asKey(testTarget)
            val aggregator: TestResultAggregator =
                com.google.common.base.Preconditions.checkNotNull<TestResultAggregator>(
                    aggregators.get(key), "Missing aggregator (key=%s, testTarget=%s)", key, testTarget
                )
            var summary: TestSummary
            if (AliasProvider.isAlias(testTarget)) {
                val summaryBuilder: com.google.devtools.build.lib.runtime.TestSummary.Builder =
                    TestSummary.newBuilder(testTarget)
                summaryBuilder.mergeFrom(aggregator.aggregateAndReportSummary(skipTargetsOnFailure))
                summary = summaryBuilder.build()
            } else {
                summary = aggregator.aggregateAndReportSummary(skipTargetsOnFailure)
            }

            if (validatedTargets != null && summary.getStatus() !== BlazeTestStatus.NO_STATUS && !validatedTargets.contains(
                    key
                )
            ) {
                // Approximate what targetFailure() would do for test targets that failed validation for
                // the purposes of printing test results to console only. Note that absent -k,
                // targetFailure() ends up marking one test as FAILED_TO_BUILD before buildComplete() marks
                // the remaining targets NO_STATUS. While we could approximate that, for simplicity, we
                // just use NO_STATUS for all tests with failed validations for simplicity here (absent -k).
                // Events published on BEP are not affected by this, but validation failures are published
                // as separate events and are additionally accounted in TargetSummary BEP messages.
                val summaryBuilder: com.google.devtools.build.lib.runtime.TestSummary.Builder =
                    TestSummary.newBuilder(summary.getTarget())
                summaryBuilder.mergeFrom(summary)
                summaryBuilder.setStatus(
                    if (skipTargetsOnFailure)
                        BlazeTestStatus.NO_STATUS
                    else
                        TestResultAggregator.aggregateStatus(
                            summary.getStatus(), BlazeTestStatus.FAILED_TO_BUILD
                        )
                )
                summary = summaryBuilder.build()
            }

            summaries.add(summary)

            // Finished aggregating; build the final console output.
            if (summary.actionRan()) {
                totalRun++
            }

            if (TestResult.isBlazeTestStatusPassed(summary.getStatus())) {
                passCount++
            }

            systemFailure =
                DetailedExitCodeComparator.chooseMoreImportantWithFirstIfTie(
                    systemFailure, summary.getSystemFailure()
                )
        }

        val summarySize: Int = summaries.size()
        val testTargetsSize: Int = testTargets.size()
        com.google.common.base.Preconditions.checkState(
            summarySize == testTargetsSize,
            "Unequal sizes: %s vs %s (%s and %s)",
            summarySize,
            testTargetsSize,
            summaries,
            testTargets
        )

        notifier.notify(summaries, totalRun)

        if (systemFailure != null) {
            return systemFailure
        }

        // skipped targets are not in passCount since they have NO_STATUS
        val testTargetsSet: MutableSet<ConfiguredTarget?> = HashSet<ConfiguredTarget?>(testTargets)
        val skippedTargetsSet: MutableSet<ConfiguredTarget?> = HashSet<ConfiguredTarget?>(skippedTargets)

        return if (passCount == com.google.common.collect.Sets.difference<ConfiguredTarget?>(
                testTargetsSet,
                skippedTargetsSet
            ).size()
        )
            DetailedExitCode.success()
        else
            TESTS_FAILED_DETAILED_CODE
    }

    companion object {
        private val TESTS_FAILED_DETAILED_CODE: DetailedExitCode = DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage("tests failed")
                .setTestCommand(TestCommand.newBuilder().setCode(Code.TESTS_FAILED))
                .build()
        )

        private fun asKey(target: ConfiguredTarget): ConfiguredTargetKey? {
            return ConfiguredTargetKey.builder()
                .setLabel(target.getLabel())
                .setConfigurationKey(target.getActual().getConfigurationKey())
                .build()
        }
    }
}
