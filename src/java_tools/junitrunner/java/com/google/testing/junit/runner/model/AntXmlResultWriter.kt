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

import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.collections.ArrayList
import kotlin.collections.Iterable
import kotlin.collections.MutableList

/**
 * Writes the JUnit test nodes and their results into Ant-JUnit XML. Ant-JUnit XML is not a
 * standardized format. For this implementation the
 * [XML schema](http://windyroad.com.au/dl/Open%20Source/JUnit.xsd) that is generally
 * referred to as the best available source was used as a reference.
 */
class AntXmlResultWriter : XmlResultWriter {
    private var testSuiteId = 0

    @Throws(IOException::class)
    override fun writeTestSuites(writer: XmlWriter, result: TestResult) {
        testSuiteId = 0
        writer.startDocument()
        writer.startElement(JUNIT_ELEMENT_TESTSUITES)
        for (child in result.getChildResults()) {
            writeTestSuite(writer, child, result.getFailures())
        }
        writer.endElement()
        writer.close()
    }

    @Throws(IOException::class)
    private fun writeTestSuite(
        writer: XmlWriter, result: TestResult,
        parentFailures: Iterable<Throwable>
    ) {
        var parentFailures = parentFailures
        val allFailures: MutableList<Throwable?> = ArrayList<Throwable?>()
        for (failure in parentFailures) {
            allFailures.add(failure)
        }
        allFailures.addAll(result.getFailures())
        parentFailures = allFailures

        writer.startElement(JUNIT_ELEMENT_TESTSUITE)

        writeTestSuiteAttributes(writer, result)
        writeTestSuiteProperties(writer, result)
        writeTestCases(writer, result, parentFailures)
        writeTestSuiteOutput(writer)

        writer.endElement()

        for (child in result.getChildResults()) {
            if (!child.getChildResults().isEmpty()) {
                writeTestSuite(writer, child, parentFailures)
            }
        }
    }

    @Throws(IOException::class)
    private fun writeTestSuiteProperties(writer: XmlWriter, result: TestResult) {
        writer.startElement(JUNIT_ELEMENT_TESTSUITE_PROPERTIES)
        for (entry in result.getProperties().entries) {
            writer.startElement(JUNIT_ELEMENT_PROPERTY)
            writer.writeAttribute(JUNIT_ATTR_PROPERTY_NAME, entry.key)
            writer.writeAttribute(JUNIT_ATTR_PROPERTY_VALUE, entry.value)
            writer.endElement()
        }
        writer.endElement()
    }

    @Throws(IOException::class)
    private fun writeTestCases(
        writer: XmlWriter, result: TestResult,
        parentFailures: Iterable<Throwable>
    ) {
        for (child in result.getChildResults()) {
            if (child.getStatus() == TestResult.Status.FILTERED) {
                continue
            }
            if (child.getChildResults().isEmpty()) {
                writeTestCase(writer, child, parentFailures)
            }
        }
    }

    @Throws(IOException::class)
    private fun writeTestSuiteOutput(writer: XmlWriter) {
        writer.startElement(JUNIT_ELEMENT_TESTSUITE_SYSTEM_OUT)
        // TODO(bazel-team) - where to get this from?
        writer.endElement()
        writer.startElement(JUNIT_ELEMENT_TESTSUITE_SYSTEM_ERR)
        // TODO(bazel-team) - where to get this from?
        writer.endElement()
    }

    @Throws(IOException::class)
    private fun writeTestSuiteAttributes(writer: XmlWriter, result: TestResult) {
        writer.writeAttribute(JUNIT_ATTR_TESTSUITE_NAME, result.getName())
        writer.writeAttribute(
            JUNIT_ATTR_TESTSUITE_TIMESTAMP, getFormattedTimestamp(
                result.getRunTimeInterval()
            )
        )
        writer.writeAttribute(JUNIT_ATTR_TESTSUITE_HOSTNAME, "localhost")
        writer.writeAttribute(JUNIT_ATTR_TESTSUITE_TESTS, result.getNumTests())
        writer.writeAttribute(JUNIT_ATTR_TESTSUITE_FAILURES, result.getNumFailures())
        // JUnit 4.x no longer distinguishes between errors and failures, so it should be safe to just
        // report errors as 0 and put everything into failures.
        writer.writeAttribute(JUNIT_ATTR_TESTSUITE_ERRORS, 0)
        writer.writeAttribute(
            JUNIT_ATTR_TESTSUITE_TIME, getFormattedRunTime(
                result.getRunTimeInterval()
            )
        )
        // TODO(bazel-team) - do we want to report the package name here? Could we simply get it from
        // result.getClassName() by stripping the last element of the class name?
        writer.writeAttribute(JUNIT_ATTR_TESTSUITE_PACKAGE, "")
        writer.writeAttribute(JUNIT_ATTR_TESTSUITE_ID, this.testSuiteId++)
    }

    @Throws(IOException::class)
    private fun writeTestCase(
        writer: XmlWriter, result: TestResult,
        parentFailures: Iterable<Throwable>
    ) {
        writer.startElement(JUNIT_ELEMENT_TESTCASE)
        writer.writeAttribute(JUNIT_ATTR_TESTCASE_NAME, result.getName())
        writer.writeAttribute(JUNIT_ATTR_TESTCASE_CLASSNAME, result.getClassName())
        writer.writeAttribute(
            JUNIT_ATTR_TESTCASE_TIME, getFormattedRunTime(
                result.getRunTimeInterval()
            )
        )

        for (failure in parentFailures) {
            writeThrowableToXmlWriter(writer, failure)
        }

        for (failure in result.getFailures()) {
            writeThrowableToXmlWriter(writer, failure)
        }

        if (result.getStatus() == TestResult.Status.SKIPPED
            || result.getStatus() == TestResult.Status.SUPPRESSED
        ) {
            writer.startElement(JUNIT_ELEMENT_SKIPPED)
            writer.endElement()
        }

        writer.endElement()
    }

    companion object {
        private const val JUNIT_ELEMENT_TESTSUITES = "testsuites"
        private const val JUNIT_ELEMENT_TESTSUITE = "testsuite"
        private const val JUNIT_ELEMENT_TESTSUITE_PROPERTIES = "properties"
        private const val JUNIT_ELEMENT_TESTSUITE_SYSTEM_OUT = "system-out"
        private const val JUNIT_ELEMENT_TESTSUITE_SYSTEM_ERR = "system-err"
        private const val JUNIT_ELEMENT_PROPERTY = "property"
        private const val JUNIT_ELEMENT_TESTCASE = "testcase"
        private const val JUNIT_ELEMENT_FAILURE = "failure"
        private const val JUNIT_ELEMENT_SKIPPED = "skipped"

        private const val JUNIT_ATTR_TESTSUITE_ERRORS = "errors"
        private const val JUNIT_ATTR_TESTSUITE_FAILURES = "failures"
        private const val JUNIT_ATTR_TESTSUITE_HOSTNAME = "hostname"
        private const val JUNIT_ATTR_TESTSUITE_NAME = "name"
        private const val JUNIT_ATTR_TESTSUITE_TESTS = "tests"
        private const val JUNIT_ATTR_TESTSUITE_TIME = "time"
        private const val JUNIT_ATTR_TESTSUITE_TIMESTAMP = "timestamp"
        private const val JUNIT_ATTR_TESTSUITE_ID = "id"
        private const val JUNIT_ATTR_TESTSUITE_PACKAGE = "package"
        private const val JUNIT_ATTR_PROPERTY_NAME = "name"
        private const val JUNIT_ATTR_PROPERTY_VALUE = "value"
        private const val JUNIT_ATTR_FAILURE_MESSAGE = "message"
        private const val JUNIT_ATTR_FAILURE_TYPE = "type"
        private const val JUNIT_ATTR_TESTCASE_NAME = "name"
        private const val JUNIT_ATTR_TESTCASE_CLASSNAME = "classname"
        private const val JUNIT_ATTR_TESTCASE_TIME = "time"

        private fun getFormattedRunTime(runTimeInterval: TestInterval?): String {
            return if (runTimeInterval == null)
                "0.0"
            else (runTimeInterval.toDurationMillis() / 1000.0).toString()
        }

        private fun getFormattedTimestamp(runTimeInterval: TestInterval?): String {
            return if (runTimeInterval == null) "" else runTimeInterval.startInstantToString()
        }

        @Throws(IOException::class)
        private fun writeThrowableToXmlWriter(writer: XmlWriter, failure: Throwable) {
            writer.startElement(JUNIT_ELEMENT_FAILURE)
            writer.writeAttribute(
                JUNIT_ATTR_FAILURE_MESSAGE, if (failure.message == null) "" else failure.message
            )
            writer.writeAttribute(JUNIT_ATTR_FAILURE_TYPE, failure.javaClass.getName())
            writer.writeCharacters(formatStackTrace(failure))
            writer.endElement()
        }

        private fun formatStackTrace(throwable: Throwable): String {
            val stringWriter = StringWriter()
            val writer = PrintWriter(stringWriter)
            throwable.printStackTrace(writer)
            return stringWriter.getBuffer().toString()
        }
    }
}
