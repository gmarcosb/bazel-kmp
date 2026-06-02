// Copyright 2010 The Bazel Authors. All Rights Reserved.
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

import com.google.testing.junit.runner.sharding.ShardingEnvironment
import org.junit.runner.Description
import org.junit.runner.manipulation.Filter
import java.io.OutputStream
import java.io.StringWriter
import java.util.function.Supplier

/**
 * Model of the tests that will be run. The model is agnostic of the particular type of test run
 * (JUnit3 or JUnit4). The test runner uses this class to build the model, and then updates the
 * model during the test run.
 * 
 * 
 * The leaf nodes in the model are test cases; the other nodes are test suites.
 */
class TestSuiteModel private constructor(builder: Builder) {
    private val rootNode: TestSuiteNode
    private val testCaseMap: MutableMap<Description?, TestCaseNode?>
    private val testsMap: MutableMap<Description?, TestNode?>
    private val testClock: TestClock
    private val wroteXml: AtomicBoolean = AtomicBoolean(false)
    private val xmlResultWriter: XmlResultWriter
    private val shardingFilter: Filter?

    init {
        rootNode = builder.rootNode!!
        testsMap = builder.testsMap
        testCaseMap = filterTestCases(builder.testsMap)
        testClock = builder.testClock
        shardingFilter = builder.shardingFilter
        xmlResultWriter = builder.xmlResultWriter
    }

    // VisibleForTesting
    fun getTopLevelTestSuites(): MutableList<TestNode?>? {
        return rootNode.getChildren()
    }

    // VisibleForTesting
    fun getTopLevelDescription(): Description? {
        return rootNode.getDescription()
    }

    /** Gets the sharding filter to use; [Filter.ALL] if not sharding.  */
    fun getShardingFilter(): Filter? {
        return shardingFilter
    }

    /**
     * Returns the test case node with the given test description.
     * 
     * 
     * Note that in theory this should never return `null`, but if it did we would not want
     * to throw a `NullPointerException` because JUnit4 would catch the exception and remove our
     * test listener!
     */
    private fun getTestCase(description: Description?): TestCaseNode? {
        // The description shouldn't be null, but in the test runner code we avoid throwing exceptions.
        return if (description == null) null else testCaseMap.get(description)
    }

    private fun getTest(description: Description?): TestNode? {
        // The description shouldn't be null, but in the test runner code we avoid throwing exceptions.
        return if (description == null) null else testsMap.get(description)
    }

    // VisibleForTesting
    fun getNumTestCases(): Int {
        return testCaseMap.size
    }

    /**
     * Indicate that the test run has started. This should be called after all filtering has been
     * completed.
     * 
     * @param topLevelDescription the root [Description] node.
     */
    fun testRunStarted(topLevelDescription: Description) {
        markChildrenAsPending(topLevelDescription)
    }

    private fun markChildrenAsPending(node: Description) {
        if (node.isTest()) {
            testPending(node)
        } else {
            for (child in node.getChildren()) {
                markChildrenAsPending(child)
            }
        }
    }

    /**
     * Indicate that the test case with the given key is scheduled to start.
     * 
     * @param description key for a test case
     */
    private fun testPending(description: Description?) {
        val testCase = getTestCase(description)
        if (testCase != null) {
            testCase.pending()
        }
    }

    /**
     * Indicate that the test case with the given key has started.
     * 
     * @param description key for a test case
     */
    fun testStarted(description: Description?) {
        val testCase = getTestCase(description)
        if (testCase != null) {
            testCase.started(now())
            TestPropertyRunnerIntegration.setTestCaseForThread(testCase)
            TestIntegrationsRunnerIntegration.setTestCaseForThread(testCase)
        }
    }

    /** Indicate that the entire test run was interrupted.  */
    fun testRunInterrupted() {
        rootNode.testInterrupted(now())
    }

    /**
     * Indicate that the test case with the given key has requested that a property be written in the
     * XML.
     * 
     * 
     * 
     * 
     * @param description key for a test case
     * @param name The property name.
     * @param value The property value.
     */
    fun testEmittedProperty(description: Description?, name: String?, value: String?) {
        val testCase = getTestCase(description)
        if (testCase != null) {
            testCase.exportProperty(name, value)
        }
    }

    /**
     * Adds a failure to the test with the given key. If the specified test is suite, the failure will
     * be added to all its children.
     * 
     * @param description key for a test case
     */
    fun testFailure(description: Description, throwable: Throwable?) {
        val test = getTest(description)
        if (test != null) {
            if (throwable is DynamicTestException) {
                val dynamicFailure: DynamicTestException = throwable as DynamicTestException
                test.dynamicTestFailure(dynamicFailure.getTest(), dynamicFailure.cause, now())
            } else {
                test.testFailure(throwable, now())
            }
        } else {
            // this is a test case dynamically added by the suite runner (such as mockito)
            val testSuite =
                rootNode.getChildren().stream()
                    .filter { node: TestNode? -> node is TestSuiteNode }
                    .filter { node: TestNode? -> node!!.getDescription().getTestClass() == description.getTestClass() }
                    .findAny()
                    .orElseThrow<IllegalStateException?>(Supplier { IllegalStateException("expected to find test suite node") }) as TestSuiteNode
            val testCase = TestCaseNode(description, testSuite)
            testsMap.put(description, testCase)
            testCaseMap.put(description, testCase)
            testSuite.addTestCase(testCase)
            // since this is the first time we're learning of this, the timing data will be incorrect :(
            testCase.testFailure(throwable, now())
        }
    }

    /**
     * Indicates that the test case with the given key was skipped
     * 
     * @param description key for a test case
     */
    fun testSkipped(description: Description?) {
        val test = getTest(description)
        if (test != null) {
            test.testSkipped(now())
        }
    }

    /**
     * Indicates that the test case with the given key was ignored or suppressed
     * 
     * @param description key for a test case
     */
    fun testSuppressed(description: Description?) {
        val test = getTest(description)
        if (test != null) {
            test.testSuppressed(now())
        }
    }

    /** Indicate that the test case with the given description has finished.  */
    fun testFinished(description: Description?) {
        val testCase = getTestCase(description)
        if (testCase != null) {
            testCase.finished(now())
        }

        /*
     * Note: we don't call TestPropertyExporter, so if any properties are
     * exported before the next test runs, they will be associated with the
     * current test.
     */
    }

    private fun now(): TestInstant {
        return testClock.now()
    }

    /**
     * Writes the model to XML
     * 
     * @param outputStream stream to output to
     * @throws IOException if the underlying writer throws an exception
     */
    @Throws(IOException::class)
    fun writeAsXml(outputStream: OutputStream?) {
        write(XmlWriter(outputStream))
    }

    // VisibleForTesting
    @Throws(IOException::class)
    fun write(writer: XmlWriter?) {
        if (wroteXml.compareAndSet(false, true)) {
            xmlResultWriter.writeTestSuites(writer, rootNode.getResult())
        }
    }

    override fun hashCode(): Int {
        return toString().hashCode()
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj !is TestSuiteModel) {
            return false
        }
        val that = obj

        // We only use this for testing, so using toString() is good enough
        return this.toString() == that.toString()
    }

    override fun toString(): String {
        try {
            val stringWriter = StringWriter()
            write(XmlWriter.Companion.createForTesting(stringWriter))
            return stringWriter.toString()
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    /** A builder for creating a model of a test suite.  */
    class Builder(
        testClock: TestClock,
        shardingFilters: ShardingFilters,
        shardingEnvironment: ShardingEnvironment,
        xmlResultWriter: XmlResultWriter
    ) {
        private val testClock: TestClock
        private val testsMap: MutableMap<Description?, TestNode?> = ConcurrentHashMap<Description?, TestNode?>()
        private val shardingEnvironment: ShardingEnvironment
        private val shardingFilters: ShardingFilters
        private val xmlResultWriter: XmlResultWriter
        private var rootNode: TestSuiteNode? = null
        private var shardingFilter: Filter = Filter.ALL
        private var buildWasCalled = false

        init {
            this.testClock = testClock
            this.shardingFilters = shardingFilters
            this.shardingEnvironment = shardingEnvironment
            this.xmlResultWriter = xmlResultWriter
        }

        /**
         * Build a model with the given name, including the given suites. This method should be called
         * before any command line filters are applied.
         */
        fun build(suiteName: String, vararg topLevelSuites: Description): TestSuiteModel {
            return build(suiteName, mutableMapOf<String?, String?>(), *topLevelSuites)
        }

        /**
         * Build a model with the given name, including the given suites. This method should be called
         * before any command line filters are applied.
         * 
         * 
         * The given `properties` map will be applied to the root [TestSuiteNode].
         */
        fun build(
            suiteName: String, properties: MutableMap<String?, String?>?, vararg topLevelSuites: Description
        ): TestSuiteModel {
            check(!buildWasCalled) { "Builder.build() was already called" }
            buildWasCalled = true
            if (shardingEnvironment.isShardingEnabled()) {
                shardingFilter = getShardingFilter(*topLevelSuites)
            }
            rootNode = TestSuiteNode(Description.createSuiteDescription(suiteName), properties)
            for (topLevelSuite in topLevelSuites) {
                addTestSuite(rootNode!!, topLevelSuite)
                rootNode!!.getDescription().addChild(topLevelSuite)
            }
            return TestSuiteModel(this)
        }

        private fun getShardingFilter(vararg topLevelSuites: Description): Filter {
            val tests: MutableCollection<Description?> = LinkedList<Description?>()
            for (suite in topLevelSuites) {
                collectTests(suite, tests)
            }
            shardingEnvironment.touchShardFile()
            return shardingFilters.createShardingFilter(tests)
        }

        private fun addTestSuite(parentSuite: TestSuiteNode, suiteDescription: Description) {
            val suite = TestSuiteNode(suiteDescription)
            for (childDesc in suiteDescription.getChildren()) {
                if (childDesc.isTest()) {
                    addTestCase(suite, childDesc)
                } else {
                    addTestSuite(suite, childDesc)
                }
            }
            // Empty suites are pruned when sharding.
            if (shardingFilter === Filter.ALL || !suite.getChildren().isEmpty()) {
                parentSuite.addTestSuite(suite)
                testsMap.put(suiteDescription, suite)
            }
        }

        private fun addTestCase(parentSuite: TestSuiteNode, testCaseDesc: Description) {
            require(testCaseDesc.isTest())
            if (!shardingFilter.shouldRun(testCaseDesc)) {
                return
            }
            val testCase = TestCaseNode(testCaseDesc, parentSuite)
            testsMap.put(testCaseDesc, testCase)
            parentSuite.addTestCase(testCase)
        }

        companion object {
            private fun collectTests(desc: Description, tests: MutableCollection<Description?>) {
                if (desc.isTest()) {
                    tests.add(desc)
                } else {
                    for (child in desc.getChildren()) {
                        collectTests(child, tests)
                    }
                }
            }
        }
    }

    companion object {
        /**
         * Converts the values of the Map from [TestNode] to [TestCaseNode] filtering out null
         * values.
         */
        private fun filterTestCases(tests: MutableMap<Description?, TestNode?>): MutableMap<Description?, TestCaseNode?> {
            val filteredAndConvertedTests: MutableMap<Description?, TestCaseNode?> =
                HashMap<Description?, TestCaseNode?>()
            for (key in tests.keys) {
                val testNode = tests.get(key)
                if (testNode is TestCaseNode) {
                    filteredAndConvertedTests.put(key, testNode)
                }
            }
            return filteredAndConvertedTests
        }
    }
}
