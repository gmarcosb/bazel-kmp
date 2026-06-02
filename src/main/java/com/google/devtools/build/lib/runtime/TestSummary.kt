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

/**
 * Test summary entry. Stores summary information for a single test rule. Also used to sort summary
 * output by status.
 * 
 * 
 * Invariant: All TestSummary mutations should be performed through the Builder. No direct
 * TestSummary methods (except the constructor) may mutate the object.
 */
class TestSummary private constructor(target: ConfiguredTarget) : Comparable<TestSummary?>,
    BuildEventWithOrderConstraint {
    /**
     * Builder class responsible for creating and altering TestSummary objects.
     */
    class Builder private constructor(target: ConfiguredTarget) {
        private var summary: TestSummary
        private var built: Boolean

        init {
            summary = TestSummary(target)
            built = false
        }

        fun mergeFrom(existingSummary: TestSummary) {
            // Yuck, manually fill in fields.
            for (i in existingSummary.shardRunStatuses.indices) {
                summary.shardRunStatuses.get(i).addAll(existingSummary.shardRunStatuses.get(i))
            }
            summary.firstStartTimeMillis = existingSummary.firstStartTimeMillis
            summary.lastStopTimeMillis = existingSummary.lastStopTimeMillis
            summary.totalRunDurationMillis = existingSummary.totalRunDurationMillis
            setConfiguration(existingSummary.configuration)
            setStatus(existingSummary.status)
            addCoverageFiles(existingSummary.coverageFiles)
            addPassedLogs(existingSummary.passedLogs)
            addFailedLogs(existingSummary.failedLogs)
            summary.totalTestCases += existingSummary.totalTestCases
            summary.unknownTestCases += existingSummary.unknownTestCases

            if (existingSummary.failedTestCasesStatus != null) {
                addFailedTestCases(
                    existingSummary.getFailedTestCases(), existingSummary.getFailedTestCasesStatus()
                )
            }

            addTestTimes(existingSummary.testTimes)
            addWarnings(existingSummary.warnings)
            setActionRan(existingSummary.actionRan)
            setNumCached(existingSummary.numCached)
            setRanRemotely(existingSummary.ranRemotely)
            mergeSystemFailure(existingSummary.getSystemFailure())
        }

        // Implements copy on write logic, allowing reuse of the same builder.
        private fun checkMutation() {
            // If mutating the builder after an object was built, create another copy.
            if (built) {
                built = false
                val lastSummary = summary
                summary = TestSummary(lastSummary.target)
                mergeFrom(lastSummary)
            }
        }

        // This used to return a reference to the value on success.
        // However, since it can alter the summary member, inlining it in an
        // assignment to a property of summary was unsafe.
        private fun checkMutation(value: Any?) {
            com.google.common.base.Preconditions.checkNotNull<Any?>(value)
            checkMutation()
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setConfiguration(configuration: BuildConfigurationValue?): Builder {
            checkMutation(configuration)
            summary.configuration =
                com.google.common.base.Preconditions.checkNotNull<BuildConfigurationValue?>(configuration, summary)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setStatus(status: BlazeTestStatus): Builder {
            checkMutation(status)
            summary.status = status
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setSkipped(skipped: Boolean): Builder {
            checkMutation(skipped)
            summary.isSkipped = skipped
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addCoverageFiles(coverageFiles: MutableList<com.google.devtools.build.lib.vfs.Path?>?): Builder {
            checkMutation(coverageFiles)
            summary.coverageFiles.addAll(coverageFiles)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addPassedLogs(passedLogs: MutableList<com.google.devtools.build.lib.vfs.Path?>?): Builder {
            checkMutation(passedLogs)
            summary.passedLogs.addAll(passedLogs)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addPassedLog(passedLog: com.google.devtools.build.lib.vfs.Path?): Builder {
            checkMutation(passedLog)
            summary.passedLogs.add(passedLog)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addFailedLogs(failedLogs: MutableList<com.google.devtools.build.lib.vfs.Path?>?): Builder {
            checkMutation(failedLogs)
            summary.failedLogs.addAll(failedLogs)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addFailedLog(failedLog: com.google.devtools.build.lib.vfs.Path?): Builder {
            checkMutation(failedLog)
            summary.failedLogs.add(failedLog)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun collectTestCases(testCase: TestCase?): Builder {
            // Maintain the invariant: failedTestCases + totalUnknownTestCases <= totalTestCases
            if (testCase == null) {
                // If we don't have test case information, count each test as one case with unknown status.
                summary.failedTestCasesStatus = FailedTestCasesStatus.NOT_AVAILABLE
                summary.totalTestCases++
                summary.unknownTestCases++
            } else {
                summary.failedTestCasesStatus = FailedTestCasesStatus.FULL
                summary.totalTestCases += traverseTestCases(testCase)
            }
            return this
        }

        private fun traverseTestCases(testCase: TestCase): Int {
            if (testCase.getChildCount() > 0) {
                // This is a non-leaf result. Traverse its children, but do not add its
                // name to the output list. It should not contain any 'failure' or
                // 'error' tags, but we want to be lax here, because the syntax of the
                // test.xml file is also lax.
                // don't count container of test cases as test
                var res = 0
                for (child in testCase.getChildList()) {
                    res += traverseTestCases(child)
                }
                return res
            } else if (testCase.getType() !== TestCase.Type.TEST_CASE) {
                return 0
            }

            // This is a leaf result.
            if (!testCase.getRun()) {
                // Don't count test cases that were not run.
                return 0
            }
            when (testCase.getStatus()) {
                PASSED -> this.summary.passedTestCases.add(testCase)
                SKIPPED -> this.summary.skippedTestCases.add(testCase)
                else -> this.summary.failedTestCases!!.add(testCase)
            }

            return 1
        }

        fun addPassedTestCases(testCases: MutableList<TestCase?>?): Builder {
            checkMutation(testCases)
            summary.passedTestCases.addAll(testCases)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addFailedTestCases(testCases: MutableList<TestCase>, status: FailedTestCasesStatus?): Builder {
            checkMutation(status)
            checkMutation(testCases)

            if (summary.failedTestCasesStatus == null) {
                summary.failedTestCasesStatus = status
            } else if (summary.failedTestCasesStatus !== status) {
                summary.failedTestCasesStatus = FailedTestCasesStatus.PARTIAL
            }

            if (testCases.isEmpty()) {
                return this
            }

            // union of summary.failedTestCases, testCases
            val allCases: MutableMap<String?, TestCase?> = TreeMap<String?, TestCase?>()
            if (summary.failedTestCases != null) {
                for (detail in summary.failedTestCases) {
                    allCases.put(detail.getClassName() + "." + detail.getName(), detail)
                }
            }
            for (detail in testCases) {
                allCases.put(detail.getClassName() + "." + detail.getName(), detail)
            }

            summary.failedTestCases = java.util.ArrayList<TestCase>(allCases.values())
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addTestTimes(testTimes: MutableList<Long?>?): Builder {
            checkMutation(testTimes)
            summary.testTimes.addAll(testTimes!!)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun mergeTiming(startTimeMillis: Long, runDurationMillis: Long): Builder {
            checkMutation()
            summary.firstStartTimeMillis = java.lang.Math.min(summary.firstStartTimeMillis, startTimeMillis)
            summary.lastStopTimeMillis =
                java.lang.Math.max(summary.lastStopTimeMillis, startTimeMillis + runDurationMillis)
            summary.totalRunDurationMillis += runDurationMillis
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addWarnings(warnings: MutableList<String?>?): Builder {
            checkMutation(warnings)
            summary.warnings.addAll(warnings!!)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setActionRan(actionRan: Boolean): Builder {
            checkMutation()
            summary.actionRan = actionRan
            return this
        }

        /**
         * Set the number of results cached, locally or remotely.
         * 
         * @param numCached number of results cached locally or remotely
         * @return this Builder
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setNumCached(numCached: Int): Builder {
            checkMutation()
            summary.numCached = numCached
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setNumLocalActionCached(numLocalActionCached: Int): Builder {
            checkMutation()
            summary.numLocalActionCached = numLocalActionCached
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setRanRemotely(ranRemotely: Boolean): Builder {
            checkMutation()
            summary.ranRemotely = ranRemotely
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun mergeSystemFailure(systemFailure: DetailedExitCode?): Builder {
            checkMutation()
            summary.systemFailure =
                DetailedExitCodeComparator.chooseMoreImportantWithFirstIfTie(
                    summary.systemFailure, systemFailure
                )
            return this
        }

        /**
         * Records a new result for the given shard of the test.
         * 
         * @return an immutable view of the statuses associated with the shard, with the new element.
         */
        fun addShardStatus(
            shardNumber: Int,
            status: BlazeTestStatus?
        ): com.google.common.collect.ImmutableList<BlazeTestStatus?> {
            val statuses: MutableList<BlazeTestStatus?> = summary.shardRunStatuses.get(shardNumber)
            statuses.add(status)
            return com.google.common.collect.ImmutableList.copyOf<BlazeTestStatus?>(statuses)
        }

        /** Records new attempts for the given shard of the target.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addShardAttempts(shardNumber: Int, newAtttempts: Int): Builder {
            checkMutation()
            summary.shardAttempts[shardNumber] += newAtttempts
            return this
        }

        /**
         * Returns the created TestSummary object.
         * Any actions following a build() will create another copy of the same values.
         * Since no mutators are provided directly by TestSummary, a copy will not
         * be produced if two builds are invoked in a row without calling a setter.
         */
        fun build(): TestSummary {
            peek()
            if (!built) {
                makeSummaryImmutable()
                // else: it is already immutable.
            }
            com.google.common.base.Preconditions.checkState(built, "Built flag was not set")
            return summary
        }

        /**
         * Within-package, it is possible to read directly from an
         * incompletely-built TestSummary. Used to pass Builders around directly.
         */
        fun peek(): TestSummary {
            com.google.common.base.Preconditions.checkNotNull<Any?>(summary.target, "Target cannot be null")
            com.google.common.base.Preconditions.checkNotNull<Any?>(summary.status, "Status cannot be null")
            return summary
        }

        private fun makeSummaryImmutable() {
            // Once finalized, the list types are immutable.
            summary.passedLogs =
                Collections.unmodifiableList<com.google.devtools.build.lib.vfs.Path?>(summary.passedLogs)
            summary.failedLogs =
                Collections.unmodifiableList<com.google.devtools.build.lib.vfs.Path?>(summary.failedLogs)
            summary.warnings = Collections.unmodifiableList<String?>(summary.warnings)
            summary.coverageFiles =
                Collections.unmodifiableList<com.google.devtools.build.lib.vfs.Path?>(summary.coverageFiles)
            summary.testTimes = Collections.unmodifiableList<Long?>(summary.testTimes)

            built = true
        }
    }

    private val target: ConfiguredTarget

    // Currently only populated if --runs_per_test_detects_flakes is enabled.
    private val shardRunStatuses: com.google.common.collect.ImmutableList<java.util.ArrayList<BlazeTestStatus?>>

    private var configuration: BuildConfigurationValue? = null
    private var status: BlazeTestStatus? = null
    var isSkipped: Boolean = false
        private set
    private val shardAttempts: IntArray
    var numCached: Int = 0
        private set
    private var numLocalActionCached = 0
    private var actionRan = false
    private var ranRemotely = false
    private var failedTestCases: MutableList<TestCase>? = java.util.ArrayList<TestCase>()
    private val passedTestCases: MutableList<TestCase?> = java.util.ArrayList<TestCase?>()
    private val skippedTestCases: MutableList<TestCase?> = java.util.ArrayList<TestCase?>()
    private var passedLogs: MutableList<com.google.devtools.build.lib.vfs.Path?> =
        java.util.ArrayList<com.google.devtools.build.lib.vfs.Path?>()
    private var failedLogs: MutableList<com.google.devtools.build.lib.vfs.Path?> =
        java.util.ArrayList<com.google.devtools.build.lib.vfs.Path?>()
    private var warnings: MutableList<String?> = java.util.ArrayList<String?>()
    private var coverageFiles: MutableList<com.google.devtools.build.lib.vfs.Path?> =
        java.util.ArrayList<com.google.devtools.build.lib.vfs.Path?>()

    // The return result is unmodifiable (UnmodifiableList instance)
    var testTimes: MutableList<Long?> = java.util.ArrayList<Long?>()
        private set
    var totalRunDurationMillis: Long = 0
        private set
    var firstStartTimeMillis: Long = java.lang.Long.MAX_VALUE
        private set
    var lastStopTimeMillis: Long = java.lang.Long.MIN_VALUE
        private set
    private var failedTestCasesStatus: FailedTestCasesStatus? = null
    var totalTestCases: Int = 0
        private set
    var unknownTestCases: Int = 0
        private set
    private var systemFailure: DetailedExitCode? = null

    // Don't allow public instantiation; go through the Builder.
    init {
        this.target = target
        val testParams: TestParams = this.testParams
        val sz: Int = java.lang.Math.max(testParams.getShards(), 1)
        shardAttempts = IntArray(sz)
        shardRunStatuses = createAndInitialize(if (testParams.runsDetectsFlakes()) sz else 0)
    }

    val label: Label
        get() = AliasProvider.getDependencyLabel(target)

    fun getTarget(): ConfiguredTarget {
        return target
    }

    fun getConfiguration(): BuildConfigurationValue? {
        return configuration
    }

    fun getStatus(): BlazeTestStatus {
        return status
    }

    val isCached: Boolean
        /**
         * Whether or not any results associated with this test were cached locally or remotely.
         * 
         * @return true if any results were cached, false if not
         */
        get() = numCached > 0

    val isLocalActionCached: Boolean
        get() = numLocalActionCached > 0

    fun numLocalActionCached(): Int {
        return numLocalActionCached
    }

    /**
     * @return number of results that were cached locally or remotely
     */
    fun numCached(): Int {
        return numCached
    }

    private fun numUncached(): Int {
        return totalRuns() - numCached
    }

    /**
     * Whether or not any action was taken for this test, that is there was some result that was
     * *not cached*.
     * 
     * @return true if some action was taken for this test, false if not
     */
    fun actionRan(): Boolean {
        return actionRan
    }

    fun ranRemotely(): Boolean {
        return ranRemotely
    }

    fun getFailedTestCases(): MutableList<TestCase>? {
        return failedTestCases
    }

    fun getSkippedTestCases(): MutableList<TestCase?> {
        return skippedTestCases
    }

    fun getPassedTestCases(): MutableList<TestCase?> {
        return passedTestCases
    }

    fun getCoverageFiles(): MutableList<com.google.devtools.build.lib.vfs.Path?> {
        return coverageFiles
    }

    fun getPassedLogs(): MutableList<com.google.devtools.build.lib.vfs.Path?> {
        return passedLogs
    }

    fun getFailedLogs(): MutableList<com.google.devtools.build.lib.vfs.Path?> {
        return failedLogs
    }

    fun getFailedTestCasesStatus(): FailedTestCasesStatus? {
        return failedTestCasesStatus
    }

    fun getSystemFailure(): DetailedExitCode? {
        return systemFailure
    }

    /**
     * Returns an immutable view of the warnings associated with this test.
     */
    fun getWarnings(): MutableList<String?> {
        return Collections.unmodifiableList<String?>(warnings)
    }

    override fun compareTo(that: TestSummary): Int {
        return com.google.common.collect.ComparisonChain.start()
            .compareTrueFirst(this.isCached, that.isCached)
            .compare(this.numUncached(), that.numUncached())
            .compare(getSortKey(this.status), getSortKey(that.status))
            .compare(this.label, that.label)
            .compare(
                this.getTarget().getConfigurationChecksum(),
                that.getTarget().getConfigurationChecksum()
            )
            .compare(this.totalTestCases, that.totalTestCases)
            .result()
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("target", this.getTarget())
            .add("status", status)
            .add("numCached", numCached)
            .add("numLocalActionCached", numLocalActionCached)
            .add("actionRan", actionRan)
            .add("ranRemotely", ranRemotely)
            .toString()
    }

    val numAttempts: Int
        get() = java.util.Arrays.stream(this.shardAttempts).max().getAsInt()

    fun totalRuns(): Int {
        return testTimes.size()
    }

    val statusMode: AnsiTerminalPrinter.Mode
        get() {
            if (this.isSkipped) {
                return AnsiTerminalPrinter.Mode.WARNING
            }
            return if (status === BlazeTestStatus.PASSED)
                AnsiTerminalPrinter.Mode.INFO
            else
                (if (status === BlazeTestStatus.FLAKY) AnsiTerminalPrinter.Mode.WARNING else AnsiTerminalPrinter.Mode.ERROR)
        }

    val eventId: BuildEventId
        get() = BuildEventIdUtil.testSummary(
            AliasProvider.getDependencyLabel(target),
            BuildEventIdUtil.configurationId(target.getConfigurationChecksum())
        )

    val childrenEvents: MutableCollection<BuildEventId>
        get() = com.google.common.collect.ImmutableList.of<BuildEventId?>()

    public override fun postedAfter(): MutableCollection<BuildEventId?> {
        return com.google.common.collect.ImmutableList.of<E?>(
            BuildEventIdUtil.targetCompleted(
                AliasProvider.getDependencyLabel(target),
                BuildEventIdUtil.configurationId(target.getConfigurationChecksum())
            )
        )
    }

    public override fun referencedLocalFiles(): com.google.common.collect.ImmutableList<LocalFile?> {
        val localFiles: com.google.common.collect.ImmutableList.Builder<LocalFile?> =
            com.google.common.collect.ImmutableList.builder<LocalFile?>()
        // TODO(b/199940216): Can we populate metadata for these files?
        for (path in getFailedLogs()) {
            localFiles.add(
                LocalFile(path, LocalFileType.FAILED_TEST_OUTPUT,  /* artifactMetadata= */null)
            )
        }
        for (path in getPassedLogs()) {
            localFiles.add(
                LocalFile(path, LocalFileType.SUCCESSFUL_TEST_OUTPUT,  /* artifactMetadata= */null)
            )
        }
        return localFiles.build()
    }

    public override fun asStreamProto(converters: BuildEventContext): BuildEventStreamProtos.BuildEvent {
        val pathConverter: PathConverter = converters.pathConverter()
        val testParams: TestParams = this.testParams
        val summaryBuilder: BuildEventStreamProtos.TestSummary.Builder =
            BuildEventStreamProtos.TestSummary.newBuilder()
                .setOverallStatus(BuildEventStreamerUtils.bepStatus(status))
                .setTotalNumCached(this.numCached)
                .setTotalRunCount(totalRuns())
                .setAttemptCount(this.numAttempts)
                .setRunCount(testParams.getRuns())
                .setShardCount(testParams.getShards())
                .setFirstStartTime(Timestamps.fromMillis(firstStartTimeMillis))
                .setFirstStartTimeMillis(firstStartTimeMillis)
                .setLastStopTime(Timestamps.fromMillis(lastStopTimeMillis))
                .setLastStopTimeMillis(lastStopTimeMillis)
                .setTotalRunDuration(Durations.fromMillis(totalRunDurationMillis))
                .setTotalRunDurationMillis(totalRunDurationMillis)
        for (path in getFailedLogs()) {
            val uri: String? = pathConverter.apply(path)
            if (uri != null) {
                summaryBuilder.addFailed(BuildEventStreamProtos.File.newBuilder().setUri(uri).build())
            }
        }
        for (path in getPassedLogs()) {
            val uri: String? = pathConverter.apply(path)
            if (uri != null) {
                summaryBuilder.addPassed(BuildEventStreamProtos.File.newBuilder().setUri(uri).build())
            }
        }
        return GenericBuildEvent.protoChaining(this).setTestSummary(summaryBuilder.build()).build()
    }

    private val testParams: TestParams
        get() = checkNotNull(target.getProvider(TestProvider::class.java).getTestParams(), target)

    companion object {
        private fun createAndInitialize(sz: Int): com.google.common.collect.ImmutableList<java.util.ArrayList<BlazeTestStatus?>> {
            return java.util.stream.Stream.generate<java.util.ArrayList<BlazeTestStatus?>?>(java.util.function.Supplier {
                java.util.ArrayList<BlazeTestStatus?>(
                    1
                )
            })
                .limit(sz.toLong())
                .collect(com.google.common.collect.ImmutableList.toImmutableList<java.util.ArrayList<BlazeTestStatus?>?>())
        }

        /** Creates a new Builder allowing construction of a new TestSummary object.  */
        fun newBuilder(target: ConfiguredTarget): Builder {
            return com.google.devtools.build.lib.runtime.TestSummary.Builder(target)
        }

        private fun getSortKey(status: BlazeTestStatus): Int {
            return if (status === BlazeTestStatus.PASSED) -1 else status.getNumber()
        }
    }
}
