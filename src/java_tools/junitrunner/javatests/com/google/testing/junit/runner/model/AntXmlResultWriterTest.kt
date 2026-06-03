// Copyright 2018 The Bazel Authors. All Rights Reserved.
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
import com.google.devtools.build.lib.clock.Clock.now
import com.google.testing.junit.runner.junit4.JUnit4Bazel.runner
import com.google.testing.junit.runner.junit4.JUnit4Runner.model
import com.google.testing.junit.runner.model.AntXmlResultWriter
import com.google.testing.junit.runner.model.AntXmlResultWriter.writeTestSuites
import com.google.testing.junit.runner.model.TestCaseNode
import com.google.testing.junit.runner.model.TestCaseNode.finished
import com.google.testing.junit.runner.model.TestCaseNode.started
import com.google.testing.junit.runner.model.TestCaseNodeTest
import com.google.testing.junit.runner.model.TestInstantUtil
import com.google.testing.junit.runner.model.TestNode.result
import com.google.testing.junit.runner.model.TestNode.testSkipped
import com.google.testing.junit.runner.model.TestNode.testSuppressed
import com.google.testing.junit.runner.model.TestSuiteModel.testSkipped
import com.google.testing.junit.runner.model.TestSuiteModel.testSuppressed
import com.google.testing.junit.runner.model.TestSuiteNode
import com.google.testing.junit.runner.model.TestSuiteNode.addTestCase
import com.google.testing.junit.runner.model.TestSuiteNode.addTestSuite
import com.google.testing.junit.runner.model.TestSuiteNode.getChildren
import com.google.testing.junit.runner.model.TestSuiteNode.testSkipped
import com.google.testing.junit.runner.model.TestSuiteNode.testSuppressed
import com.google.testing.junit.runner.util.FakeTestClock
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

@RunWith(JUnit4::class)
class AntXmlResultWriterTest {
    @Before
    fun before() {
        stringWriter = java.io.StringWriter()
        writer = com.google.testing.junit.runner.model.XmlWriter.createForTesting(stringWriter)
        resultWriter = AntXmlResultWriter()
        root = TestSuiteNode(org.junit.runner.Description.createSuiteDescription("root"))
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun allPassingTestCasesWritten() {
        val parent: TestSuiteNode = createTestSuite()
        val test1: TestCaseNode = createTestCase(parent)
        val test2: TestCaseNode = createTestCase(parent)
        runToCompletion(test1)
        runToCompletion(test2)

        resultWriter.writeTestSuites(writer, root.result)
        val resultXml: String? = stringWriter.toString()
        Truth.assertThat(resultXml).contains("<testcase name='testCase1'")
        Truth.assertThat(resultXml).contains("<testcase name='testCase2'")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testFilteredCasesNotWritten() {
        val parent: TestSuiteNode = createTestSuite()
        val test1: TestCaseNode = createTestCase(parent)
        runToCompletion(test1)

        createTestCase(parent) // creates a test case that is FILTERED by default

        resultWriter.writeTestSuites(writer, root.result)

        val resultXml: String? = stringWriter.toString()
        Truth.assertThat(resultXml).contains("<testcase name='testCase1'")
        Truth.assertThat(resultXml).doesNotContain("<testcase name='testCase2'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWallTimeAndMonotonicTimestamp() {
        val clock: FakeTestClock = FakeTestClock()
        val startTime: Instant = Instant.ofEpochMilli(1560786184600L)
        clock.setWallTimeOffset(startTime)
        val parent: TestSuiteNode = createTestSuite()
        val test: TestCaseNode = createTestCase(parent)

        test.started(clock.now())
        // wall time may appear to go back in time in exceptional cases (e.g. daylight saving time)
        clock.advance(java.time.Duration.ofMillis(1L))
        clock.setWallTimeOffset(startTime.minus(java.time.Duration.ofHours(1)))
        test.finished(clock.now())

        resultWriter.writeTestSuites(writer, root.result)

        val resultXml: String? = stringWriter.toString()
        Truth.assertThat(resultXml).contains("time=")
        Truth.assertThat(resultXml).contains("timestamp=")

        val document: org.w3c.dom.Document = Companion.parseXml(resultXml!!)
        val testSuites: org.w3c.dom.Element = document.getDocumentElement()
        val testSuite: org.w3c.dom.Element = testSuites.getElementsByTagName("testsuite").item(0) as org.w3c.dom.Element
        Truth.assertThat(testSuite.getTagName()).isEqualTo("testsuite")
        Truth.assertThat(testSuite.getAttribute("name"))
            .isEqualTo("com.google.testing.junit.runner.model.TestCaseNodeTest\$TestSuite")
        Truth.assertThat(testSuite.getAttribute("time")).isEqualTo("0.001")
        Truth.assertThat<Instant?>(
            Instant.from(
                DateTimeFormatter.ISO_DATE_TIME.parse(testSuite.getAttribute("timestamp"))
            )
        )
            .isEqualTo(startTime)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSkippedOrSuppressedReportedAsSkipped() {
        val parent: TestSuiteNode = createTestSuite()
        val skipped: TestCaseNode = createTestCase(parent)
        skipped.started(TestInstantUtil.testInstant(Instant.ofEpochMilli(1)))
        skipped.testSkipped(TestInstantUtil.testInstant(Instant.ofEpochMilli(2)))
        val suppressed: TestCaseNode = createTestCase(parent)
        suppressed.testSuppressed(TestInstantUtil.testInstant(Instant.ofEpochMilli(4)))

        resultWriter.writeTestSuites(writer, root.result)

        val document: org.w3c.dom.Document = parseXml(stringWriter.toString())
        val caseElems: org.w3c.dom.NodeList = document.getElementsByTagName("testcase")
        Truth.assertThat(caseElems.getLength()).isEqualTo(2)
        for (i in 0..1) {
            val caseElem: org.w3c.dom.Element = caseElems.item(i) as org.w3c.dom.Element
            val skippedElems: org.w3c.dom.NodeList = caseElem.getElementsByTagName("skipped")
            Truth.assertThat(skippedElems.getLength()).isEqualTo(1)
        }
    }

    private fun runToCompletion(test: TestCaseNode) {
        test.started(TestInstantUtil.testInstant(Instant.ofEpochMilli(1)))
        test.finished(TestInstantUtil.testInstant(Instant.ofEpochMilli(2)))
    }

    private fun createTestCase(parent: TestSuiteNode): TestCaseNode {
        val idx: Int = parent.getChildren().size + 1
        val testCase: TestCaseNode =
            TestCaseNode(org.junit.runner.Description.createSuiteDescription("testCase" + idx), parent)
        parent.addTestCase(testCase)
        return testCase
    }

    private fun createTestSuite(): TestSuiteNode {
        val suite: org.junit.runner.Description =
            org.junit.runner.Description.createSuiteDescription(com.google.testing.junit.runner.model.TestCaseNodeTest.TestSuite::class.java)
        val parent: TestSuiteNode = TestSuiteNode(suite)
        root.addTestSuite(parent)
        return parent
    }

    companion object {
        private var root: TestSuiteNode? = null
        private var writer: com.google.testing.junit.runner.model.XmlWriter? = null
        private var resultWriter: AntXmlResultWriter? = null
        private var stringWriter: java.io.StringWriter? = null

        @Throws(org.xml.sax.SAXException::class, ParserConfigurationException::class, IOException::class)
        private fun parseXml(testXml: String): org.w3c.dom.Document {
            val factory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance()
            factory.setIgnoringElementContentWhitespace(true)
            val documentBuilder: DocumentBuilder = factory.newDocumentBuilder()
            return documentBuilder.parse(ByteArrayInputStream(testXml.toByteArray(java.nio.charset.StandardCharsets.UTF_8)))
        }
    }
}
