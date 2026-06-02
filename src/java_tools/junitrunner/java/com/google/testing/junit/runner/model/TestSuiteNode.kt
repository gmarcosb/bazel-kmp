// Copyright 2015 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.junit.runner.model

import com.google.testing.junit.runner.util.TestClock.TestInstant
import org.junit.runner.Description
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.collections.MutableMap

/**
 * A parent node in the test suite model.
 */
internal class TestSuiteNode @kotlin.jvm.JvmOverloads constructor(
    description: Description?,
    private val properties: MutableMap<String?, String?>? = mutableMapOf<String?, String?>()
) : TestNode(description) {
    private val children: MutableList<TestNode> = ArrayList<TestNode>()

    // VisibleForTesting
    override fun getChildren(): MutableList<TestNode> {
        return Collections.unmodifiableList<TestNode?>(children)
    }

    override fun isTestCase(): Boolean {
        return false
    }

    override fun testFailure(throwable: Throwable?, now: TestInstant?) {
        for (child in getChildren()) {
            child.testFailure(throwable, now)
        }
    }

    override fun dynamicTestFailure(test: Description?, throwable: Throwable?, now: TestInstant?) {
        for (child in getChildren()) {
            child.dynamicTestFailure(test, throwable, now)
        }
    }

    override fun testInterrupted(now: TestInstant?) {
        for (child in getChildren()) {
            child.testInterrupted(now)
        }
    }

    override fun testSkipped(now: TestInstant?) {
        for (child in getChildren()) {
            child.testSkipped(now)
        }
    }

    override fun testSuppressed(now: TestInstant?) {
        for (child in getChildren()) {
            child.testSuppressed(now)
        }
    }

    fun addTestSuite(suite: TestSuiteNode?) {
        children.add(suite!!)
    }

    fun addTestCase(testCase: TestCaseNode?) {
        children.add(testCase!!)
    }

    override fun buildResult(): TestResult {
        var runTime: TestInterval? = null
        var numTests = 0
        var numFailures = 0
        val childResults: LinkedList<TestResult?> = LinkedList<TestResult?>()

        for (child in children) {
            val childResult = child.getResult()
            childResults.add(childResult)
            numTests += childResult.getNumTests()
            numFailures += childResult.getNumFailures()

            val childRunTime = childResult.getRunTimeInterval()
            if (childRunTime != null) {
                runTime = if (runTime == null) childRunTime else TestInterval.Companion.around(runTime, childRunTime)
            }
        }

        return TestResult.Builder()
            .name(getDescription().getDisplayName())
            .className("")
            .properties(properties)
            .failures(mutableListOf<Throwable?>())
            .runTimeInterval(runTime)
            .status(TestResult.Status.SKIPPED)
            .numTests(numTests)
            .numFailures(numFailures)
            .childResults(childResults)
            .integrations(mutableSetOf<TestIntegration?>())
            .build()
    }
}
