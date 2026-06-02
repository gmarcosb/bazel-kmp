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

import com.google.devtools.build.lib.exec.ExecutionOptions.TestSummaryFormat.DETAILED

/**
 * Prints the test results to a terminal.
 */
class TerminalTestResultNotifier(
    printer: AnsiTerminalPrinter,
    testLogPathFormatter: TestLogPathFormatter?,
    options: com.google.devtools.common.options.OptionsParsingResult,
    mainRepoMapping: RepositoryMapping?
) : TestResultNotifier {
    private class TestResultStats {
        var numberOfTargets: Int = 0
        var passCount: Int = 0
        var failedToBuildCount: Int = 0
        var failedCount: Int = 0
        var failedRemotelyCount: Int = 0
        var failedLocallyCount: Int = 0
        var noStatusCount: Int = 0
        var numberOfExecutedTargets: Int = 0

        var totalTestCases: Int = 0
        var totalFailedTestCases: Int = 0
        var totalSkippedTestCases: Int = 0
        var totalUnknownTestCases: Int = 0
    }

    private val printer: AnsiTerminalPrinter
    private val testLogPathFormatter: TestLogPathFormatter?
    private val options: com.google.devtools.common.options.OptionsParsingResult
    private val summaryOptions: TestSummaryOptions?
    private val mainRepoMapping: RepositoryMapping?

    /**
     * Decide if two tests with the same label are contained in the set of test summaries
     */
    private fun duplicateLabels(summaries: MutableSet<TestSummary>): Boolean {
        val labelsSeen: MutableSet<Label?> = HashSet<Label?>()
        for (summary in summaries) {
            if (labelsSeen.contains(summary.getLabel())) {
                return true
            }
            labelsSeen.add(summary.getLabel())
        }
        return false
    }

    /**
     * Prints test result summary.
     * 
     * @param summaries summaries of tests [TestSummary]
     * @param showAllTests if true, print information about each test regardless of its status
     * @param showNoStatusTests if true, print information about not executed tests (no status tests)
     * @param showAllTestCases if true, print all test cases status and detailed information
     */
    private fun printSummary(
        summaries: MutableSet<TestSummary>,
        showAllTests: Boolean,
        showNoStatusTests: Boolean,
        showAllTestCases: Boolean,
        showCachedTests: Boolean
    ) {
        val withConfig = duplicateLabels(summaries)
        var numFailedToBuildReported = 0
        for (summary in summaries) {
            if (!showAllTests
                && (BlazeTestStatus.PASSED === summary.getStatus()
                        || (!showNoStatusTests && BlazeTestStatus.NO_STATUS === summary.getStatus()))
            ) {
                continue
            }
            if (BlazeTestStatus.FAILED_TO_BUILD === summary.getStatus()) {
                if (numFailedToBuildReported == NUM_FAILED_TO_BUILD) {
                    printer.printLn("(Skipping other failed to build tests)")
                }
                numFailedToBuildReported++
                if (numFailedToBuildReported > NUM_FAILED_TO_BUILD) {
                    continue
                }
            }

            if (!showCachedTests && summary.getStatus() === BlazeTestStatus.PASSED && !summary.actionRan()) {
                continue
            }

            TestSummaryPrinter.print(
                summary,
                printer,
                testLogPathFormatter,
                summaryOptions.getVerboseSummary(),
                showAllTestCases,
                withConfig,
                mainRepoMapping
            )
        }
    }

    /**
     * Returns true iff the --check_tests_up_to_date option is enabled.
     */
    private fun optionCheckTestsUpToDate(): Boolean {
        return options.getOptions<O?>(ExecutionOptions::class.java).testCheckUpToDate
    }

    /**
     * @param printer The terminal to print to
     */
    init {
        this.printer = printer
        this.testLogPathFormatter = testLogPathFormatter
        this.options = options
        this.summaryOptions = options.getOptions<TestSummaryOptions?>(TestSummaryOptions::class.java)
        this.mainRepoMapping = mainRepoMapping
    }

    /**
     * Prints a test summary information for all tests to the terminal.
     * 
     * @param summaries Summary of all targets that were ran
     * @param numberOfExecutedTargets the number of targets that were actually ran
     */
    override fun notify(summaries: MutableSet<TestSummary>, numberOfExecutedTargets: Int) {
        val stats = TestResultStats()
        stats.numberOfTargets = summaries.size()
        stats.numberOfExecutedTargets = numberOfExecutedTargets

        val executionOptions: ExecutionOptions =
            com.google.common.base.Preconditions.checkNotNull<T>(options.getOptions<O?>(ExecutionOptions::class.java))
        val testOutput: TestOutputFormat? = executionOptions.testOutput

        for (summary in summaries) {
            if (summary.isLocalActionCached()
                && TestLogHelper.shouldOutputTestLog(
                    testOutput,
                    TestResult.isBlazeTestStatusPassed(summary.getStatus())
                )
            ) {
                TestSummaryPrinter.printCachedOutput(
                    summary,
                    testOutput,
                    printer,
                    testLogPathFormatter,
                    executionOptions.maxTestOutputBytes
                )
            }
        }

        for (summary in summaries) {
            if (TestResult.isBlazeTestStatusPassed(summary.getStatus())) {
                stats.passCount++
            } else if (summary.getStatus() === BlazeTestStatus.NO_STATUS
                || summary.getStatus() === BlazeTestStatus.BLAZE_HALTED_BEFORE_TESTING
            ) {
                stats.noStatusCount++
            } else if (summary.getStatus() === BlazeTestStatus.FAILED_TO_BUILD) {
                stats.failedToBuildCount++
            } else if (summary.ranRemotely()) {
                stats.failedRemotelyCount++
            } else {
                stats.failedLocallyCount++
            }

            stats.totalTestCases += summary.getTotalTestCases()
            stats.totalUnknownTestCases += summary.getUnknownTestCases()
            stats.totalFailedTestCases += summary.getFailedTestCases().size()
            stats.totalSkippedTestCases += summary.getSkippedTestCases().size()
        }

        stats.failedCount = summaries.size() - stats.passCount

        val testSummaryFormat: TestSummaryFormat = executionOptions.testSummary
        when (testSummaryFormat) {
            DETAILED, DETAILED_UNCACHED, SHORT, SHORT_UNCACHED, TERSE -> {
                val showAllTests: Boolean = SHOW_ALL_TESTS_FORMATS.contains(testSummaryFormat)
                val showNoStatusTests: Boolean = SHOW_NO_STATUS_TESTS_FORMATS.contains(testSummaryFormat)
                val showAllTestCases: Boolean = SHOW_ALL_TEST_CASES_FORMATS.contains(testSummaryFormat)
                val showCachedTests: Boolean = SHOW_CACHED_TESTS_FORMATS.contains(testSummaryFormat)
                printSummary(
                    summaries, showAllTests, showNoStatusTests, showAllTestCases, showCachedTests
                )
            }

            TESTCASE, NONE -> {}
        }

        printStats(stats)
    }

    private fun addFailureToErrorList(list: MutableList<String?>, failureDescription: String?, count: Int) {
        addToList(list, AnsiTerminalPrinter.Mode.ERROR, "fails", "fail", failureDescription, count)
    }

    private fun addToWarningList(
        list: MutableList<String?>, singularPrefix: String?, pluralPrefix: String?, message: String?, count: Int
    ) {
        addToList(list, AnsiTerminalPrinter.Mode.WARNING, singularPrefix, pluralPrefix, message, count)
    }

    private fun addToList(
        list: MutableList<String?>,
        mode: AnsiTerminalPrinter.Mode?,
        singularPrefix: String?,
        pluralPrefix: String?,
        message: String?,
        count: Int
    ) {
        if (count > 0) {
            list.add(
                java.lang.String.format(
                    "%s%d %s %s%s",
                    mode,
                    count,
                    if (count == 1) singularPrefix else pluralPrefix,
                    message,
                    AnsiTerminalPrinter.Mode.DEFAULT
                )
            )
        }
    }

    private fun printStats(stats: TestResultStats) {
        val testSummaryFormat: TestSummaryFormat? =
            options.getOptions<O?>(ExecutionOptions::class.java).testSummary
        if (testSummaryFormat === DETAILED || testSummaryFormat === DETAILED_UNCACHED || testSummaryFormat === TESTCASE) {
            val passCount =
                (stats.totalTestCases
                        - stats.totalFailedTestCases
                        - stats.totalUnknownTestCases
                        - stats.totalSkippedTestCases)
            var message: String? =
                java.lang.String.format(
                    "Test cases: finished with %s%d passing%s, %s%d skipped%s and %s%d failing%s out of"
                            + " %d test cases",
                    if (passCount > 0) AnsiTerminalPrinter.Mode.INFO else "",
                    passCount,
                    AnsiTerminalPrinter.Mode.DEFAULT,
                    if (stats.totalSkippedTestCases > 0) AnsiTerminalPrinter.Mode.WARNING else "",
                    stats.totalSkippedTestCases,
                    AnsiTerminalPrinter.Mode.DEFAULT,
                    if (stats.totalFailedTestCases > 0) AnsiTerminalPrinter.Mode.ERROR else "",
                    stats.totalFailedTestCases,
                    AnsiTerminalPrinter.Mode.DEFAULT,
                    stats.totalTestCases
                )
            if (stats.totalUnknownTestCases != 0) {
                // It is possible for a target to fail even if all of its test cases pass. To avoid
                // confusion, we append the following disclaimer.
                message += " (some targets did not have test case information)"
            }
            printer.printLn(message)
        }

        if (!optionCheckTestsUpToDate()) {
            val results: MutableList<String?> = java.util.ArrayList<String?>()
            if (stats.passCount == 1) {
                results.add(stats.passCount.toString() + " test passes")
            } else if (stats.passCount > 0) {
                results.add(stats.passCount.toString() + " tests pass")
            }
            addFailureToErrorList(results, "to build", stats.failedToBuildCount)
            addFailureToErrorList(results, "locally", stats.failedLocallyCount)
            addFailureToErrorList(results, "remotely", stats.failedRemotelyCount)
            addToWarningList(results, "was", "were", "skipped", stats.noStatusCount)
            printer.print(
                java.lang.String.format(
                    "\nExecuted %d out of %d %s: %s.\n",
                    stats.numberOfExecutedTargets,
                    stats.numberOfTargets,
                    if (stats.numberOfTargets == 1) "test" else "tests",
                    com.google.devtools.build.lib.util.StringUtil.joinEnglishList(results, "and")
                )
            )
        } else {
            val failingUpToDateCount = stats.failedCount - stats.noStatusCount
            printer.print(
                java.lang.String.format(
                    "\nFinished with %d passing and %s%d failing%s tests up to date, %s%d out of date.%s\n",
                    stats.passCount,
                    if (failingUpToDateCount > 0) AnsiTerminalPrinter.Mode.ERROR else "",
                    failingUpToDateCount,
                    AnsiTerminalPrinter.Mode.DEFAULT,
                    if (stats.noStatusCount > 0) AnsiTerminalPrinter.Mode.ERROR else "",
                    stats.noStatusCount,
                    AnsiTerminalPrinter.Mode.DEFAULT
                )
            )
        }
    }

    companion object {
        // The number of failed-to-build tests to report.
        // (We do not want to report hundreds of failed-to-build tests as it would probably be caused
        // by some intermediate target not related to tests themselves.)
        // The total number of failed-to-build tests will be reported in any case.
        @com.google.common.annotations.VisibleForTesting
        const val NUM_FAILED_TO_BUILD: Int = 5

        private val SHOW_ALL_TESTS_FORMATS: com.google.common.collect.ImmutableSet<TestSummaryFormat?> =
            com.google.common.collect.Sets.immutableEnumSet<TestSummaryFormat?>(
                DETAILED,
                DETAILED_UNCACHED,
                SHORT,
                SHORT_UNCACHED
            )
        private val SHOW_NO_STATUS_TESTS_FORMATS: com.google.common.collect.ImmutableSet<TestSummaryFormat?> =
            com.google.common.collect.Sets.< TestSummaryFormat > immutableEnumSet < TestSummaryFormat ? > (DETAILED, DETAILED_UNCACHED)
        private val SHOW_ALL_TEST_CASES_FORMATS: com.google.common.collect.ImmutableSet<TestSummaryFormat?> =
            com.google.common.collect.Sets.< TestSummaryFormat > immutableEnumSet < TestSummaryFormat ? > (DETAILED, DETAILED_UNCACHED)
        private val SHOW_CACHED_TESTS_FORMATS: com.google.common.collect.ImmutableSet<TestSummaryFormat?> =
            com.google.common.collect.Sets.< TestSummaryFormat > immutableEnumSet < TestSummaryFormat ? > (DETAILED, SHORT)
    }
}
