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
import com.google.testing.junit.runner.model.TestCaseNode
import com.google.testing.junit.runner.model.TestCaseNode.isTestCase
import com.google.testing.junit.runner.model.TestNode.dynamicTestFailure
import com.google.testing.junit.runner.model.TestNode.result
import com.google.testing.junit.runner.model.TestNode.testFailure
import com.google.testing.junit.runner.model.TestNode.testInterrupted
import com.google.testing.junit.runner.model.TestNode.testSkipped
import com.google.testing.junit.runner.model.TestNode.testSuppressed
import com.google.testing.junit.runner.model.TestResult.getProperties
import com.google.testing.junit.runner.model.TestSuiteModel.testFailure
import com.google.testing.junit.runner.model.TestSuiteModel.testSkipped
import com.google.testing.junit.runner.model.TestSuiteModel.testSuppressed
import com.google.testing.junit.runner.model.TestSuiteNode
import com.google.testing.junit.runner.model.TestSuiteNode.addTestCase
import com.google.testing.junit.runner.model.TestSuiteNode.dynamicTestFailure
import com.google.testing.junit.runner.model.TestSuiteNode.isTestCase
import com.google.testing.junit.runner.model.TestSuiteNode.testFailure
import com.google.testing.junit.runner.model.TestSuiteNode.testInterrupted
import com.google.testing.junit.runner.model.TestSuiteNode.testSkipped
import com.google.testing.junit.runner.model.TestSuiteNode.testSuppressed
import com.google.testing.junit.runner.util.TestClock.TestInstant
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnitRunner
import java.time.Instant

/** Unit test for [TestSuiteNode].  */
@RunWith(MockitoJUnitRunner::class)
class TestSuiteNodeTest {
    @Mock
    private val testCaseNode: TestCaseNode? = null
    private var testSuiteNode: TestSuiteNode? = null

    @Before
    fun createTestSuiteNode() {
        testSuiteNode = TestSuiteNode(org.junit.runner.Description.createSuiteDescription("suite"))
        testSuiteNode.addTestCase(testCaseNode)
    }

    @org.junit.Test
    fun testIsTestCase() {
        Truth.assertThat(testSuiteNode.isTestCase()).isFalse()
        Mockito.verifyNoMoreInteractions(testCaseNode)
    }

    @org.junit.Test
    fun testInterrupted() {
        testSuiteNode.testInterrupted(NOW)
        Mockito.verify<TestCaseNode?>(testCaseNode, Mockito.times(1)).testInterrupted(NOW)
    }

    @org.junit.Test
    fun testTestSkipped() {
        testSuiteNode.testSkipped(NOW)
        Mockito.verify<TestCaseNode?>(testCaseNode, Mockito.times(1)).testSkipped(NOW)
    }

    @org.junit.Test
    fun testTestIgnored() {
        testSuiteNode.testSuppressed(NOW)
        Mockito.verify<TestCaseNode?>(testCaseNode, Mockito.times(1)).testSuppressed(NOW)
    }

    @org.junit.Test
    fun testTestFailure() {
        val failure: java.lang.Exception = java.lang.Exception()
        testSuiteNode.testFailure(failure, NOW)
        Mockito.verify<TestCaseNode?>(testCaseNode, Mockito.times(1)).testFailure(failure, NOW)
    }

    @org.junit.Test
    fun testDynamicFailure() {
        val dynamicTestCaseDescription: org.junit.runner.Description? =
            Mockito.mock<org.junit.runner.Description?>(org.junit.runner.Description::class.java)
        val failure: java.lang.Exception = java.lang.Exception()
        testSuiteNode.dynamicTestFailure(dynamicTestCaseDescription, failure, NOW)
        Mockito.verify<TestCaseNode?>(testCaseNode, Mockito.times(1))
            .dynamicTestFailure(dynamicTestCaseDescription, failure, NOW)
        Mockito.verifyNoMoreInteractions(dynamicTestCaseDescription)
    }

    @org.junit.Test
    fun testProperties() {
        val properties: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>("key", "value")
        testSuiteNode = TestSuiteNode(org.junit.runner.Description.createSuiteDescription("suite"), properties)

        val result: com.google.testing.junit.runner.model.TestResult? = testSuiteNode.result
        Truth.assertThat(result.getProperties()).containsExactlyEntriesIn(properties)
    }

    companion object {
        private val NOW: TestInstant = TestInstant(Instant.EPOCH, java.time.Duration.ZERO)
    }
}
