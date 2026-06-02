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

import com.google.devtools.build.lib.cmdline.RepositoryMapping

/**
 * Print test statistics in human readable form.
 */
object TestSummaryPrinter {
    /** Print the cached test log to the given printer.  */
    fun printCachedOutput(
        summary: TestSummary,
        testOutput: TestOutputFormat?,
        printer: AnsiTerminalPrinter,
        testLogPathFormatter: TestLogPathFormatter,
        maxTestOutputBytes: Int
    ) {
        val testName: String? = summary.getLabel().toString()
        val allLogs: MutableList<String?> = java.util.ArrayList<String?>()
        for (path in summary.getFailedLogs()) {
            allLogs.add(testLogPathFormatter.getPathStringToPrint(path))
        }
        for (path in summary.getPassedLogs()) {
            allLogs.add(testLogPathFormatter.getPathStringToPrint(path))
        }
        printer.printLn(
            (""
                    + summary.getStatusMode()
                    + summary.getStatus()
                    + ": "
                    + AnsiTerminalPrinter.Mode.DEFAULT
                    + testName
                    + " (see "
                    + com.google.common.base.Joiner.on(' ').join(allLogs)
                    + ")")
        )
        printer.printLn(AnsiTerminalPrinter.Mode.INFO.toString() + "INFO: " + AnsiTerminalPrinter.Mode.DEFAULT + "From Testing " + testName)

        // Whether to output the target at all was checked by the caller.
        // Now check whether to output failing shards.
        if (TestLogHelper.shouldOutputTestLog(testOutput, false)) {
            for (path in summary.getFailedLogs()) {
                try {
                    TestLogHelper.writeTestLog(path, testName, printer.getOutputStream(), maxTestOutputBytes)
                } catch (e: IOException) {
                    printer.printLn("==================== Could not read test output for " + testName)
                }
            }
        }

        // And passing shards, independently.
        if (TestLogHelper.shouldOutputTestLog(testOutput, true)) {
            for (path in summary.getPassedLogs()) {
                try {
                    TestLogHelper.writeTestLog(path, testName, printer.getOutputStream(), maxTestOutputBytes)
                } catch (e: java.lang.Exception) {
                    printer.printLn("==================== Could not read test output for " + testName)
                }
            }
        }
    }

    private fun statusString(summary: TestSummary): String {
        if (summary.isSkipped()) {
            // If the test was skipped then its status will be something like NO_STATUS. That's not
            // informative enough to a user. Instead, return "SKIPPED" for skipped tests.
            return "SKIPPED"
        }
        return summary.getStatus().toString().replace('_', ' ')
    }

    /**
     * Prints summary status for a single test.
     * 
     * @param terminalPrinter The printer to print to
     */
    /**
     * Prints summary status for a single test.
     * 
     * @param terminalPrinter The printer to print to
     */
    @kotlin.jvm.JvmOverloads
    fun print(
        summary: TestSummary,
        terminalPrinter: AnsiTerminalPrinter,
        testLogPathFormatter: TestLogPathFormatter,
        verboseSummary: Boolean,
        showAllTestCases: Boolean,
        withConfigurationName: Boolean = false,
        mainRepoMapping: RepositoryMapping? = RepositoryMapping.EMPTY
    ) {
        val status: BlazeTestStatus? = summary.getStatus()
        // Skip output for tests that failed to build.
        if ((!verboseSummary && status === BlazeTestStatus.FAILED_TO_BUILD)
            || status === BlazeTestStatus.BLAZE_HALTED_BEFORE_TESTING
        ) {
            return
        }
        val message = getCacheMessage(summary) + statusString(summary)
        var targetName: String = summary.getLabel().getDisplayForm(mainRepoMapping)
        if (withConfigurationName) {
            targetName += " (" + summary.getConfiguration().getMnemonic() + ")"
        }
        terminalPrinter.print(
            (com.google.common.base.Strings.padEnd(targetName, 78 - message.length(), ' ')
                    + " "
                    + summary.getStatusMode()
                    + message
                    + AnsiTerminalPrinter.Mode.DEFAULT
                    + (if (verboseSummary) getAttemptSummary(summary) + getTimeSummary(summary) else "")
                    + "\n")
        )

        if (showAllTestCases) {
            for (testCase in summary.getPassedTestCases()) {
                printTestCase(terminalPrinter, testCase)
            }
            for (testCase in summary.getSkippedTestCases()) {
                printTestCase(terminalPrinter, testCase)
            }

            if (summary.getStatus() === BlazeTestStatus.FAILED) {
                if (summary.getFailedTestCasesStatus() === FailedTestCasesStatus.NOT_AVAILABLE) {
                    terminalPrinter.print(
                        (AnsiTerminalPrinter.Mode.WARNING
                            .toString() + "    (individual test case information not available) "
                                + AnsiTerminalPrinter.Mode.DEFAULT
                                + "\n")
                    )
                } else {
                    for (testCase in summary.getFailedTestCases()) {
                        if (testCase.getStatus() !== TestCase.Status.PASSED) {
                            printTestCase(terminalPrinter, testCase)
                        }
                    }

                    if (summary.getFailedTestCasesStatus() !== FailedTestCasesStatus.FULL) {
                        terminalPrinter.print(
                            (AnsiTerminalPrinter.Mode.WARNING
                                .toString() + "    (some shards did not report details, list of failed test"
                                    + " cases incomplete)\n"
                                    + AnsiTerminalPrinter.Mode.DEFAULT)
                        )
                    }
                }
            }
        } else {
            for (warning in summary.getWarnings()) {
                terminalPrinter.print(
                    ("  " + AnsiTerminalPrinter.Mode.WARNING + "WARNING: "
                            + AnsiTerminalPrinter.Mode.DEFAULT + warning + "\n")
                )
            }

            for (path in summary.getFailedLogs()) {
                if (path.exists()) {
                    terminalPrinter.print("  " + testLogPathFormatter.getPathStringToPrint(path) + "\n")
                }
            }
        }
        for (path in summary.getCoverageFiles()) {
            // Print only non-trivial coverage files.
            try {
                if (path.exists() && path.getFileSize() > 0) {
                    terminalPrinter.print("  " + testLogPathFormatter.getPathStringToPrint(path) + "\n")
                }
            } catch (e: IOException) {
                LoggingUtil.logToRemote(
                    java.util.logging.Level.WARNING, "Error while reading coverage data file size",
                    e
                )
            }
        }
    }

    /** Prints the result of an individual test case.  */
    fun printTestCase(terminalPrinter: AnsiTerminalPrinter, testCase: TestCase) {
        val timeSummary: String?
        if (testCase.hasRunDurationMillis()) {
            timeSummary = (" ("
                    + timeInSec(testCase.getRunDurationMillis(), TimeUnit.MILLISECONDS)
                    + ")")
        } else {
            timeSummary = ""
        }

        val mode: AnsiTerminalPrinter.Mode =
            when (testCase.getStatus()) {
                PASSED -> AnsiTerminalPrinter.Mode.INFO
                SKIPPED -> AnsiTerminalPrinter.Mode.WARNING
                else -> AnsiTerminalPrinter.Mode.ERROR
            }
        terminalPrinter.print(
            ("    "
                    + mode
                    + com.google.common.base.Strings.padEnd(testCase.getStatus().toString(), 8, ' ')
                    + AnsiTerminalPrinter.Mode.DEFAULT
                    + testCase.getClassName()
                    + "."
                    + testCase.getName()
                    + timeSummary
                    + "\n")
        )
    }

    /**
     * Return the given time in seconds, to 1 decimal place,
     * i.e. "32.1s".
     */
    fun timeInSec(time: Long, unit: TimeUnit?): String? {
        val ms: Double = TimeUnit.MILLISECONDS.convert(time, unit).toDouble()
        return java.lang.String.format(Locale.US, "%.1fs", ms / 1000.0)
    }

    fun getAttemptSummary(summary: TestSummary): String {
        val attempts: Int = summary.getPassedLogs().size() + summary.getFailedLogs().size()
        if (attempts > 1) {
            // Print number of failed runs for failed tests if testing was completed.
            if (summary.getStatus() === BlazeTestStatus.FLAKY) {
                return ", failed in " + summary.getFailedLogs().size() + " out of " + attempts
            }
            if (summary.getStatus() === BlazeTestStatus.TIMEOUT
                || summary.getStatus() === BlazeTestStatus.FAILED
            ) {
                return " in " + summary.getFailedLogs().size() + " out of " + attempts
            }
        }
        return ""
    }

    fun getCacheMessage(summary: TestSummary): String? {
        if (summary.getNumCached() == 0 || summary.getStatus() === BlazeTestStatus.INCOMPLETE || summary.getStatus() === BlazeTestStatus.NO_STATUS || summary.getStatus() === BlazeTestStatus.FAILED_TO_BUILD) {
            return "" // either no caching, or information isn't useful
        } else if (summary.getNumCached() == summary.totalRuns()) {
            return "(cached) "
        } else {
            return java.lang.String.format(
                Locale.US, "(%d/%d cached) ", summary.getNumCached(), summary.totalRuns()
            )
        }
    }

    fun getTimeSummary(summary: TestSummary): String? {
        if (summary.getTestTimes().isEmpty()
            || summary.getStatus() === BlazeTestStatus.NO_STATUS || summary.getStatus() === BlazeTestStatus.FAILED_TO_BUILD
        ) {
            return "" // either no tests ran, or information isn't useful
        } else if (summary.getTestTimes().size() == 1) {
            return " in " + timeInSec(summary.getTestTimes().get(0), TimeUnit.MILLISECONDS)
        } else {
            // We previously used com.google.math for this, which added about 1 MB of deps to the total
            // size. If we re-introduce a dependency on that package, we could revert this change.
            var min: Long = summary.getTestTimes().get(0)
            var max = min
            var sum: Long = 0
            var sumOfSquares = 0.0
            for (l in summary.getTestTimes()) {
                val value: Long = l
                min = java.lang.Math.min(value, min)
                max = java.lang.Math.max(value, max)
                sum += value
                sumOfSquares += (value.toDouble()) * value.toDouble()
            }
            val mean: Double = (sum.toDouble()) / summary.getTestTimes().size()
            val stddev: Double = java.lang.Math.sqrt((sumOfSquares - sum * mean) / summary.getTestTimes().size())
            // For sharded tests, we print the max time on the same line as
            // the test, and then print more detailed info about the
            // distribution of times on the next line.
            val maxTime = timeInSec(max, TimeUnit.MILLISECONDS)
            return java.lang.String.format(
                Locale.US,
                " in %s\n  Stats over %d runs: max = %s, min = %s, avg = %s, dev = %s",
                maxTime,
                summary.getTestTimes().size(),
                maxTime,
                timeInSec(min, TimeUnit.MILLISECONDS),
                timeInSec(mean.toLong(), TimeUnit.MILLISECONDS),
                timeInSec(stddev.toLong(), TimeUnit.MILLISECONDS)
            )
        }
    }

    /**
     * Interface for getting the [String] to display to the user for a [Path]
     * corresponding to a test output (e.g. test log).
     */
    interface TestLogPathFormatter {
        fun getPathStringToPrint(path: com.google.devtools.build.lib.vfs.Path?): String?
    }
}
