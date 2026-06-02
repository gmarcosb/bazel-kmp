// Copyright 2019 The Bazel Authors. All rights reserved.
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

/** This class aggregates and reports target-wide test statuses in real-time.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
internal class TestResultAggregator(
    target: ConfiguredTarget?,
    configuration: BuildConfigurationValue?,
    private val policy: AggregationPolicy,
    skippedThisTest: Boolean
) {
    /**
     * Settings for the aggregator; there are usually many aggregator instances with the same set of
     * settings, so we move them to a separate object.
     */
    internal class AggregationPolicy(
        eventBus: com.google.common.eventbus.EventBus,
        testCheckUpToDate: Boolean,
        testVerboseTimeoutWarnings: Boolean
    ) {
        private val eventBus: com.google.common.eventbus.EventBus
        private val testCheckUpToDate: Boolean
        private val testVerboseTimeoutWarnings: Boolean

        init {
            this.eventBus = eventBus
            this.testCheckUpToDate = testCheckUpToDate
            this.testVerboseTimeoutWarnings = testVerboseTimeoutWarnings
        }
    }

    private val summary: com.google.devtools.build.lib.runtime.TestSummary.Builder
    private var remainingRuns: Int
    private var summaryPosted = false

    init {
        this.summary =
            TestSummary.Companion.newBuilder(target)
                .setConfiguration(configuration)
                .setStatus(BlazeTestStatus.NO_STATUS)
                .setSkipped(skippedThisTest)
        this.remainingRuns = TestProvider.getTestStatusArtifacts(target).size()
    }

    /**
     * Records a new test run result and incrementally updates the target status. This event is sent
     * upon completion of executed test runs.
     */
    @kotlin.jvm.Synchronized
    fun testEvent(result: TestResult) {
        // If a test result was cached, then post the cached attempts to the event bus.
        if (result.isCached()) {
            for (attempt in result.getCachedTestAttempts()) {
                policy.eventBus.post(attempt)
            }
        }

        remainingRuns--
        if (summaryPosted) {
            // This can happen if a buildCompleteEvent() was processed before this event reached us.
            // This situation is likely to happen if --notest_keep_going is set with multiple targets.
            return
        }

        incrementalAnalyze(result)

        // If all runs are processed, the target is finished and ready to report.
        if (remainingRuns == 0) {
            postSummary()
        }
    }

    private fun postSummary(): TestSummary {
        val result: TestSummary = summary.build()
        policy.eventBus.post(result)
        summaryPosted = true
        return result
    }

    @kotlin.jvm.Synchronized
    fun targetFailure(blazeHalted: Boolean, skipTargetsOnFailure: Boolean) {
        if (summaryPosted) {
            // Blaze does not guarantee that BuildResult.getSuccessfulTargets() and posted TestResult
            // events are in sync. Thus, it is possible that a test event was posted, but the target is
            // not present in the set of successful targets.
            return
        }

        markUnbuilt(blazeHalted, skipTargetsOnFailure)

        // These are never going to run; removing them marks the target complete.
        postSummary()
    }

    @kotlin.jvm.Synchronized
    fun targetSkipped() {
        if (summaryPosted) {
            // Blaze does not guarantee that BuildResult.getSuccessfulTargets() and posted TestResult
            // events are in sync. Thus, it is possible that a test event was posted, but the target is
            // not present in the set of successful targets.
            return
        }

        summary.setStatus(BlazeTestStatus.NO_STATUS)

        // These are never going to run; removing them marks the target complete.
        postSummary()
    }

    /**
     * Helper for differential analysis which aggregates the TestSummary for an individual target,
     * reporting runs on the EventBus if necessary.
     */
    @kotlin.jvm.Synchronized
    fun aggregateAndReportSummary(skipTargetsOnFailure: Boolean): TestSummary {
        // If already reported by the listener, no work remains for this target.
        if (summaryPosted) {
            return summary.build() // Reuses the same summary if nothing has changed.
        }

        // Build may have been interrupted.
        if (remainingRuns > 0) {
            markIncomplete(skipTargetsOnFailure)
        }

        return postSummary()
    }

    /**
     * Incrementally updates a TestSummary given an existing summary and a new TestResult. Only call
     * on built targets.
     * 
     * @param result New test result to aggregate into the summary.
     */
    private fun incrementalAnalyze(result: TestResult) {
        // Cache retrieval should have been performed already.
        val existingSummary: TestSummary =
            com.google.common.base.Preconditions.checkNotNull<TestSummary>(summary.peek())

        var status: BlazeTestStatus = existingSummary.getStatus()
        var numCached: Int = existingSummary.numCached()
        var numLocalActionCached: Int = existingSummary.numLocalActionCached()

        // If a test was neither cached locally nor remotely we say action was taken.
        if (!(result.isCached() || result.getData().getRemotelyCached())) {
            summary.setActionRan(true)
        } else {
            numCached++
        }

        if (result.isCached()) {
            numLocalActionCached++
        }

        val coverageData: com.google.devtools.build.lib.vfs.Path? = result.getCoverageData()
        if (coverageData != null) {
            summary.addCoverageFiles(
                com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.vfs.Path?>(
                    coverageData
                )
            )
        }

        val target: TransitiveInfoCollection? = existingSummary.getTarget()
        com.google.common.base.Preconditions.checkNotNull<Any?>(
            target,
            "The existing TestSummary must be associated with a target"
        )
        val testParams: TestParams = target.getProvider(TestProvider::class.java).getTestParams()

        val shardNumber: Int = result.getShardNum()
        summary.addShardAttempts(shardNumber, result.getData().getTestTimesCount())

        if (!testParams.runsDetectsFlakes()) {
            status = aggregateStatus(status, result.getData().getStatus())
        } else {
            val runsPerTestForLabel: Int = testParams.getRuns()
            val singleShardStatuses: MutableList<BlazeTestStatus> =
                summary.addShardStatus(shardNumber, result.getData().getStatus())
            if (singleShardStatuses.size() == runsPerTestForLabel) {
                // Aggregation is based on the order of status enums where larger values take precedence
                // over smaller ones (NO_STATUS = 0, PASSED = 1, etc.). However, there are some special
                // cases:
                // 1. Tests that have some passing, some not passing shard are marked as flaky
                // 2. The INCOMPLETE status is ignored - it is used for tests runs that are cancelled by
                //    Bazel if --cancel_concurrent_tests is set, otherwise INCOMPLETE is not used
                // 3. Individual test shards can be FLAKY if the test is marked flaky and
                //    --flaky_test_attempts is not zero for this test
                var shardStatus: BlazeTestStatus = BlazeTestStatus.NO_STATUS
                var passes = 0
                var cancelled = 0
                for (runStatusForShard in singleShardStatuses) {
                    if (runStatusForShard === BlazeTestStatus.INCOMPLETE) {
                        // If runs_per_test_detects_flakes is enabled, then INCOMPLETE status indicates
                        // cancelled test runs. We count them separately so that they don't result in a
                        // flaky status below.
                        cancelled++
                    } else {
                        shardStatus = aggregateStatus(shardStatus, runStatusForShard)
                        if (TestResult.isBlazeTestStatusPassed(runStatusForShard)) {
                            passes++
                        }
                    }
                }
                // Under the RunsPerTestDetectsFlakes option, return flaky if 0 < p < (n-cancelled) shards
                // pass. Otherwise, we aggregate the shardStatus.
                if (passes == 0 || (passes + cancelled) == runsPerTestForLabel) {
                    status = aggregateStatus(status, shardStatus)
                } else {
                    status = aggregateStatus(status, BlazeTestStatus.FLAKY)
                }
            }
        }

        if (result.getData().hasPassedLog()) {
            summary.addPassedLog(result.getTestLogPath().getRelative(result.getData().getPassedLog()))
        }
        for (path in result.getData().getFailedLogsList()) {
            summary.addFailedLog(result.getTestLogPath().getRelative(path))
        }

        summary
            .addTestTimes(result.getData().getTestTimesList())
            .mergeTiming(
                result.getData().getStartTimeMillisEpoch(), result.getData().getRunDurationMillis()
            )
            .addWarnings(result.getData().getWarningList())
            .collectTestCases(if (result.getData().hasTestCase()) result.getData().getTestCase() else null)
            .setRanRemotely(result.getData().getIsRemoteStrategy())

        val warnings: MutableList<String?> = java.util.ArrayList<String?>()
        if (status === BlazeTestStatus.PASSED) {
            val unused =
                shouldEmitTestSizeWarningInSummary(
                    policy.testVerboseTimeoutWarnings,
                    warnings,
                    result.getData().getTestProcessTimesList(),
                    target
                )
        }

        summary
            .mergeSystemFailure(result.getSystemFailure())
            .setStatus(status)
            .setNumCached(numCached)
            .setNumLocalActionCached(numLocalActionCached)
            .addWarnings(warnings)
    }

    private fun markIncomplete(skipTargetsOnFailure: Boolean) {
        // TODO(bazel-team): (2010) Make NotRunTestResult support both tests failed to built and
        // tests with no status and post it here.
        val peekSummary: TestSummary = summary.peek()
        var status: BlazeTestStatus = peekSummary.getStatus()
        if (skipTargetsOnFailure) {
            status = BlazeTestStatus.NO_STATUS
        } else if (status !== BlazeTestStatus.NO_STATUS) {
            status = aggregateStatus(status, BlazeTestStatus.INCOMPLETE)
        }

        summary.setStatus(status)
    }

    private fun markUnbuilt(blazeHalted: Boolean, skipTargetsOnFailure: Boolean) {
        val runStatus: BlazeTestStatus? =
            if (blazeHalted)
                BlazeTestStatus.BLAZE_HALTED_BEFORE_TESTING
            else
                (if (policy.testCheckUpToDate || skipTargetsOnFailure)
                    BlazeTestStatus.NO_STATUS
                else
                    BlazeTestStatus.FAILED_TO_BUILD)

        summary.setStatus(runStatus)
    }

    companion object {
        fun aggregateStatus(status: BlazeTestStatus, other: BlazeTestStatus): BlazeTestStatus {
            return if (status.getNumber() > other.getNumber()) status else other
        }

        /**
         * Checks whether the specified test timeout could have been smaller or is too small and adds a
         * warning message if verbose is true.
         * 
         * 
         * Returns true if there was a test with the wrong timeout, but if was not reported.
         */
        private fun shouldEmitTestSizeWarningInSummary(
            verbose: Boolean,
            warnings: MutableList<String?>,
            testTimes: MutableList<Long?>,
            target: TransitiveInfoCollection
        ): Boolean {
            val specifiedTimeout: TestTimeout =
                target.getProvider(TestProvider::class.java).getTestParams().getTimeout()
            var maxTimeOfShard: Long = 0

            for (shardTime in testTimes) {
                if (shardTime != null) {
                    maxTimeOfShard = java.lang.Math.max(maxTimeOfShard, shardTime)
                }
            }

            val maxTimeInSeconds = (maxTimeOfShard / 1000).toInt()

            if (!specifiedTimeout.isInRangeFuzzy(maxTimeInSeconds)) {
                val expectedTimeout: TestTimeout? = TestTimeout.getSuggestedTestTimeout(maxTimeInSeconds)
                val expectedSize: TestSize? = TestSize.getTestSize(expectedTimeout)
                if (verbose) {
                    val builder: java.lang.StringBuilder =
                        java.lang.StringBuilder(
                            java.lang.String.format(
                                "%s: Test execution time (%.1fs excluding execution overhead) outside of "
                                        + "range for %s tests. Consider setting timeout=\"%s\"",
                                AliasProvider.getDependencyLabel(target),
                                maxTimeOfShard / 1000.0,
                                specifiedTimeout.prettyPrint(),
                                expectedTimeout
                            )
                        )
                    if (expectedSize != null) {
                        builder.append(" or size=\"").append(expectedSize).append("\"")
                    }
                    builder.append(".")
                    warnings.add(builder.toString())
                    return false
                }
                return true
            } else {
                return false
            }
        }
    }
}
