// Copyright 2016 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.junit.runner

import com.google.testing.junit.runner.junit4.JUnit4Bazel.runner
import org.junit.runner.notification.RunListener

/**
 * A straightforward listener that prints to stdout/stderr whenever a test changes its state (e.g.
 * started, finished, failed).
 */
internal class TestListener : RunListener() {
    /**
     * Called before any tests have been run. Prints to stdout the number of test cases found.
     * 
     * @param description describes the tests to be run
     */
    @Throws(java.lang.Exception::class)
    override fun testRunStarted(description: org.junit.runner.Description) {
        println("Found " + com.google.testing.junit.runner.TestListener.Companion.formatTestCaseCount(description.testCount()) + ".")
    }

    /**
     * Called when all tests have finished. Prints to stdout if the tests were successful or not. If
     * not, it also prints the number of failed test cases. Finally, it prints the number of
     * ignored test cases.
     * 
     * @param result the summary of the test run, including all the tests that failed
     */
    @Throws(java.lang.Exception::class)
    override fun testRunFinished(result: org.junit.runner.Result) {
        if (result.wasSuccessful()) {
            println(
                ("Successfully finished running "
                        + com.google.testing.junit.runner.TestListener.Companion.formatTestCaseCount(result.getRunCount()) + " in " + result.getRunTime() + " ms.")
            )
        } else {
            println(
                ("Finished running " + com.google.testing.junit.runner.TestListener.Companion.formatTestCaseCount(result.getRunCount())
                        + " in " + result.getRunTime() + " ms.")
            )
            val failureCount: Int = result.getFailureCount()
            if (failureCount == 1) {
                println("There was 1 failed test.")
            } else {
                println("There were " + failureCount + " failed tests.")
            }
        }
        val ignoredCount: Int = result.getIgnoreCount()
        if (ignoredCount == 1) {
            println(result.getIgnoreCount().toString() + " test case was ignored.")
        } else if (ignoredCount > 1) {
            println(result.getIgnoreCount().toString() + " test cases were ignored.")
        }
    }

    /**
     * Called when an atomic test is about to be started. Prints to stdout the name of the test that
     * started with the corresponding information.
     * 
     * @param description the description of the test that is about to be run
     * (generally a class and method name)
     */
    @Throws(java.lang.Exception::class)
    override fun testStarted(description: org.junit.runner.Description) {
        println("Test case started: " + description.getDisplayName())
    }

    /**
     * Called when an atomic test fails. Prints to stderr the name of the test that failed
     * (including its class) and the reason why, including the stack trace.
     * 
     * @param failure describes the test that failed and the exception that was thrown
     */
    @Throws(java.lang.Exception::class)
    override fun testFailure(failure: org.junit.runner.notification.Failure) {
        java.lang.System.err.println(
            ("Failure in " + failure.getTestHeader() + ": " + failure.getMessage()
                    + "\n" + failure.getTrace())
        )
    }

    /**
     * Called when an atomic test flags that it assumes a condition that is false. Prints to stderr
     * that a test case assumed false condition, including the corresponding message containing
     * the context.
     * 
     * @param failure describes the test that failed and the
     * [AssumptionViolatedException] that was thrown
     */
    override fun testAssumptionFailure(failure: org.junit.runner.notification.Failure) {
        java.lang.System.err.println("Test case assumed false condition: " + failure.getMessage())
    }

    /**
     * Called when a test will not be run, generally because a test method is annotated with
     * Ignore. Prints to stderr that a test case was ignored, alongside with the test name.
     */
    @Throws(java.lang.Exception::class)
    override fun testIgnored(description: org.junit.runner.Description) {
        java.lang.System.err.println("Test case " + description.getMethodName() + " ignored.")
    }

    companion object {
        private fun formatTestCaseCount(count: Int): String {
            if (count == 1) {
                return "1 test case"
            }
            return count.toString() + " test cases"
        }
    }
}
