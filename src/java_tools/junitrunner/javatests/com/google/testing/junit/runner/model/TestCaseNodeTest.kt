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

import com.google.common.truth.Truth
import com.google.testing.junit.runner.junit4.JUnit4Bazel.runner
import com.google.testing.junit.runner.junit4.JUnit4Runner.model
import com.google.testing.junit.runner.model.TestCaseNode
import com.google.testing.junit.runner.model.TestCaseNode.finished
import com.google.testing.junit.runner.model.TestCaseNode.isTestCase
import com.google.testing.junit.runner.model.TestCaseNode.pending
import com.google.testing.junit.runner.model.TestCaseNode.started
import com.google.testing.junit.runner.model.TestCaseNodeTest
import com.google.testing.junit.runner.model.TestInstantUtil
import com.google.testing.junit.runner.model.TestInterval.startMillis
import com.google.testing.junit.runner.model.TestInterval.toDurationMillis
import com.google.testing.junit.runner.model.TestNode.result
import com.google.testing.junit.runner.model.TestNode.testFailure
import com.google.testing.junit.runner.model.TestNode.testInterrupted
import com.google.testing.junit.runner.model.TestNode.testSkipped
import com.google.testing.junit.runner.model.TestNode.testSuppressed
import com.google.testing.junit.runner.model.TestResult.getRunTimeInterval
import com.google.testing.junit.runner.model.TestResult.getStatus
import com.google.testing.junit.runner.model.TestSuiteModel.testFailure
import com.google.testing.junit.runner.model.TestSuiteModel.testSkipped
import com.google.testing.junit.runner.model.TestSuiteModel.testSuppressed
import com.google.testing.junit.runner.model.TestSuiteNode
import com.google.testing.junit.runner.model.TestSuiteNode.isTestCase
import com.google.testing.junit.runner.model.TestSuiteNode.testFailure
import com.google.testing.junit.runner.model.TestSuiteNode.testInterrupted
import com.google.testing.junit.runner.model.TestSuiteNode.testSkipped
import com.google.testing.junit.runner.model.TestSuiteNode.testSuppressed
import com.google.testing.junit.runner.util.TestClock.TestInstant
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.time.Instant

/**
 * Unit test for [TestCaseNode].
 */
@RunWith(JUnit4::class)
class TestCaseNodeTest {
    @org.junit.Test
    fun assertIsTestCase() {
        Truth.assertThat(TestCaseNode(testCase, TestSuiteNode(suite)).isTestCase()).isTrue()
    }

    @org.junit.Test
    fun assertIsFilteredIfNeverPending() {
        val testCaseNode: TestCaseNode = TestCaseNode(testCase, TestSuiteNode(suite))
        assertStatusWithoutTiming(testCaseNode, com.google.testing.junit.runner.model.TestResult.Status.FILTERED)
    }

    @org.junit.Test
    fun assertIsCancelledIfNotStarted() {
        val testCaseNode: TestCaseNode = TestCaseNode(testCase, TestSuiteNode(suite))
        testCaseNode.pending()
        assertStatusWithoutTiming(testCaseNode, com.google.testing.junit.runner.model.TestResult.Status.CANCELLED)
    }

    @org.junit.Test
    fun assertIsCancelledIfInterruptedBeforeStart() {
        val testCaseNode: TestCaseNode = TestCaseNode(testCase, TestSuiteNode(suite))
        testCaseNode.pending()
        testCaseNode.testInterrupted(NOW)
        assertStatusAndTiming(testCaseNode, com.google.testing.junit.runner.model.TestResult.Status.CANCELLED, NOW, 0)
    }

    @org.junit.Test
    fun assertIsCompletedIfFailedBeforeStart() {
        val testCaseNode: TestCaseNode = TestCaseNode(testCase, TestSuiteNode(suite))
        testCaseNode.pending()
        testCaseNode.testFailure(java.lang.Exception(), NOW)
        assertStatusAndTiming(testCaseNode, com.google.testing.junit.runner.model.TestResult.Status.COMPLETED, NOW, 0)
    }

    @org.junit.Test
    fun assertInterruptedIfStartedAndNotFinished() {
        val testCaseNode: TestCaseNode = TestCaseNode(testCase, TestSuiteNode(suite))
        testCaseNode.pending()
        testCaseNode.started(NOW)
        assertStatusAndTiming(testCaseNode, com.google.testing.junit.runner.model.TestResult.Status.INTERRUPTED, NOW, 0)
        // Notice: This is an unexpected ending state, as even interrupted test executions should go
        // through the testCaseNode.interrupted() code path.
    }

    @org.junit.Test
    fun assertInterruptedIfStartedAndInterrupted() {
        val testCaseNode: TestCaseNode = TestCaseNode(testCase, TestSuiteNode(suite))
        testCaseNode.pending()
        testCaseNode.started(NOW)
        testCaseNode.testInterrupted(TestInstantUtil.advance(NOW, java.time.Duration.ofMillis(1)))
        assertStatusAndTiming(testCaseNode, com.google.testing.junit.runner.model.TestResult.Status.INTERRUPTED, NOW, 1)
    }

    @org.junit.Test
    fun assertSkippedIfStartedAndSkipped() {
        val testCaseNode: TestCaseNode = TestCaseNode(testCase, TestSuiteNode(suite))
        testCaseNode.pending()
        testCaseNode.started(NOW)
        testCaseNode.testSkipped(TestInstantUtil.advance(NOW, java.time.Duration.ofMillis(1)))
        assertStatusAndTiming(testCaseNode, com.google.testing.junit.runner.model.TestResult.Status.SKIPPED, NOW, 1)
    }

    @org.junit.Test
    fun assertCompletedIfStartedAndFinished() {
        val testCaseNode: TestCaseNode = TestCaseNode(testCase, TestSuiteNode(suite))
        testCaseNode.pending()
        testCaseNode.started(NOW)
        testCaseNode.finished(TestInstantUtil.advance(NOW, java.time.Duration.ofMillis(1)))
        assertStatusAndTiming(testCaseNode, com.google.testing.junit.runner.model.TestResult.Status.COMPLETED, NOW, 1)
    }

    @org.junit.Test
    fun assertCompletedIfStartedAndFailedAndFinished() {
        val testCaseNode: TestCaseNode = TestCaseNode(testCase, TestSuiteNode(suite))
        testCaseNode.pending()
        testCaseNode.started(NOW)
        testCaseNode.testFailure(java.lang.Exception(), TestInstantUtil.advance(NOW, java.time.Duration.ofMillis(1)))
        testCaseNode.finished(TestInstantUtil.advance(NOW, java.time.Duration.ofMillis(2)))
        assertStatusAndTiming(testCaseNode, com.google.testing.junit.runner.model.TestResult.Status.COMPLETED, NOW, 2)
    }

    @org.junit.Test
    fun assertInterruptedIfStartedAndFailedAndInterrupted() {
        val testCaseNode: TestCaseNode = TestCaseNode(testCase, TestSuiteNode(suite))
        testCaseNode.pending()
        testCaseNode.started(NOW)
        testCaseNode.testFailure(java.lang.Exception(), TestInstantUtil.advance(NOW, java.time.Duration.ofMillis(1)))
        testCaseNode.testInterrupted(TestInstantUtil.advance(NOW, java.time.Duration.ofMillis(2)))
        assertStatusAndTiming(testCaseNode, com.google.testing.junit.runner.model.TestResult.Status.INTERRUPTED, NOW, 2)
    }

    @org.junit.Test
    fun assertTestSuppressedIfNotStartedAndSuppressed() {
        val testCaseNode: TestCaseNode = TestCaseNode(testCase, TestSuiteNode(suite))
        testCaseNode.pending()
        testCaseNode.testSuppressed(NOW)
        assertStatusAndTiming(testCaseNode, com.google.testing.junit.runner.model.TestResult.Status.SUPPRESSED, NOW, 0)
    }

    private fun assertStatusAndTiming(
        testCase: TestCaseNode,
        status: com.google.testing.junit.runner.model.TestResult.Status?,
        start: TestInstant,
        duration: Long
    ) {
        val result: com.google.testing.junit.runner.model.TestResult? = testCase.result
        Truth.assertThat<com.google.testing.junit.runner.model.TestResult.Status?>(result.getStatus()).isEqualTo(status)
        Truth.assertThat(result.getRunTimeInterval()).isNotNull()
        Truth.assertThat(result.getRunTimeInterval().startMillis)
            .isEqualTo(start.wallTime().toEpochMilli())
        Truth.assertThat(result.getRunTimeInterval().toDurationMillis()).isEqualTo(duration)
    }

    private fun assertStatusWithoutTiming(
        testCase: TestCaseNode,
        status: com.google.testing.junit.runner.model.TestResult.Status?
    ) {
        val result: com.google.testing.junit.runner.model.TestResult? = testCase.result
        Truth.assertThat<com.google.testing.junit.runner.model.TestResult.Status?>(result.getStatus()).isEqualTo(status)
        Truth.assertThat(result.getRunTimeInterval()).isNull()
    }

    internal class TestSuite {
        @org.junit.Test
        fun testCase() {
        }
    }

    companion object {
        private val NOW: TestInstant = TestInstantUtil.testInstant(Instant.ofEpochMilli(1))
        private var suite: org.junit.runner.Description? = null
        private var testCase: org.junit.runner.Description? = null

        @BeforeClass
        fun createDescriptions() {
            suite = org.junit.runner.Description.createSuiteDescription(TestSuiteNode::class.java)
            testCase = org.junit.runner.Description.createTestDescription(
                com.google.testing.junit.runner.model.TestCaseNodeTest.TestSuite::class.java,
                "testCase"
            )
            suite.addChild(testCase)
        }
    }
}
