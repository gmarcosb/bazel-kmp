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

import com.google.testing.junit.runner.util.TestPropertyExporter.INITIAL_INDEX_FOR_REPEATED_PROPERTY
import org.junit.runner.Description
import java.util.Queue
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet

/** A leaf in the test suite model.  */
internal class TestCaseNode(description: Description?, private val parent: TestSuiteNode) : TestNode(description),
    TestPropertyExporter.Callback, TestIntegrationsExporter.Callback {
    private val properties: MutableMap<String?, String?> = ConcurrentHashMap<String?, String?>()
    private val repeatedPropertyNamesToRepetitions: MutableMap<String?, Int?> = HashMap<String?, Int?>()
    private val globalFailures: Queue<Throwable?> = ConcurrentLinkedQueue<Throwable?>()
    private val dynamicTestToFailures: ConcurrentMap<Description?, MutableList<Throwable?>?> =
        ConcurrentHashMap<Description?, MutableList<Throwable?>?>()
    private val integrations: MutableSet<TestIntegration?> =
        Collections.newSetFromMap<TestIntegration?>(ConcurrentHashMap<TestIntegration?, Boolean?>())

    @kotlin.concurrent.Volatile
    var runtime: TestInterval? = null
        private set

    @kotlin.concurrent.Volatile
    private var state = State.INITIAL

    // VisibleForTesting
    override fun getChildren(): MutableList<TestNode?> {
        return mutableListOf<TestNode?>()
    }

    /**
     * Indicates that the test represented by this node is scheduled to start.
     */
    fun pending() {
        compareAndSetState(State.INITIAL, State.PENDING, TestInstant.UNKNOWN)
    }

    /**
     * Indicates that the test represented by this node has started.
     * 
     * @param now Time that the test started
     */
    fun started(now: TestInstant) {
        compareAndSetState(INITIAL_STATES, State.STARTED, now)
    }

    override fun testInterrupted(now: TestInstant) {
        if (compareAndSetState(State.STARTED, State.INTERRUPTED, now)) {
            globalFailures.add(Exception("Test interrupted"))
            return
        }
        if (compareAndSetState(INITIAL_STATES, State.CANCELLED, now)) {
            globalFailures.add(Exception("Test cancelled"))
        }
    }

    public override fun exportProperty(name: String?, value: String?) {
        properties.put(name, value)
    }

    public override fun exportRepeatedProperty(name: String, value: String?): String {
        val propertyName = getRepeatedPropertyName(name)
        properties.put(propertyName, value)
        return propertyName
    }

    public override fun exportTestIntegration(testIntegration: TestIntegration?) {
        integrations.add(testIntegration)
    }

    override fun testSkipped(now: TestInstant) {
        compareAndSetState(State.STARTED, State.SKIPPED, now)
    }

    override fun testSuppressed(now: TestInstant) {
        compareAndSetState(INITIAL_STATES, State.SUPPRESSED, now)
    }

    /**
     * Indicates that the test represented by this node has finished.
     * 
     * @param now Time that the test finished
     */
    fun finished(now: TestInstant) {
        compareAndSetState(State.STARTED, State.FINISHED, now)
    }

    override fun testFailure(throwable: Throwable?, now: TestInstant) {
        compareAndSetState(INITIAL_STATES, State.FINISHED, now)
        globalFailures.add(throwable)
    }

    override fun dynamicTestFailure(test: Description?, throwable: Throwable?, now: TestInstant) {
        compareAndSetState(INITIAL_STATES, State.FINISHED, now)
        addThrowableToDynamicTestToFailures(test, throwable)
    }

    private fun getRepeatedPropertyName(name: String): String {
        val index: Int = (addNameToRepeatedPropertyNamesAndGetRepetitionsNr(name)
                + INITIAL_INDEX_FOR_REPEATED_PROPERTY)
        return name + index
    }

    override fun isTestCase(): Boolean {
        return true
    }

    @kotlin.jvm.Synchronized
    private fun addThrowableToDynamicTestToFailures(
        test: Description?, throwable: Throwable?
    ) {
        var throwables = dynamicTestToFailures.get(test)
        if (throwables == null) {
            throwables = ArrayList<Throwable?>()
            dynamicTestToFailures.put(test, throwables)
        }
        throwables.add(throwable)
    }

    @kotlin.jvm.Synchronized
    private fun addNameToRepeatedPropertyNamesAndGetRepetitionsNr(name: String?): Int {
        var previousRepetitionsNr = repeatedPropertyNamesToRepetitions.get(name)
        if (previousRepetitionsNr == null) {
            previousRepetitionsNr = 0
        }
        repeatedPropertyNamesToRepetitions.put(name, previousRepetitionsNr + 1)
        return previousRepetitionsNr
    }

    private fun compareAndSetState(fromState: State, toState: State, now: TestInstant): Boolean {
        if (fromState == null) {
            throw NullPointerException()
        }
        return compareAndSetState(mutableSetOf<State?>(fromState), toState, now)
    }

    // TODO(bazel-team): Use AtomicReference instead of a synchronized method.
    @kotlin.jvm.Synchronized
    private fun compareAndSetState(
        fromStates: MutableSet<State?>, toState: State, now: TestInstant
    ): Boolean {
        if (fromStates == null || toState == null || state == null) {
            throw NullPointerException()
        }
        require(!fromStates.isEmpty())
        if (fromStates.contains(state) && toState != state) {
            state = toState
            if (toState != State.PENDING) {
                this.runtime =
                    if (this.runtime == null)
                        TestInterval(now, now)
                    else
                        runtime!!.withEndMillis(now)
            }
            return true
        }
        return false
    }

    val testResultStatus: TestResult.Status?
        /**
         * @return The equivalent [TestResult.Status] if the test execution ends with the FSM
         * at this state.
         */
        get() = state.testResultStatus

    override fun buildResult(): TestResult {
        // Some test descriptions, like those provided by JavaScript tests, are
        // constructed by Description.createSuiteDescription, not
        // createTestDescription, because they don't have a "class" per se.
        // In this case, getMethodName returns null and we fill in the className
        // attribute with the name of the parent test suite.
        var name = getDescription().getMethodName()
        var className = getDescription().getClassName()
        if (name == null) {
            name = className
            className = parent.getDescription().getDisplayName()
        }

        // For now, we give each dynamic test an empty properties map and the same
        // run time and status as its parent test case, but this may change.
        val childResults: MutableList<TestResult?> = ArrayList<TestResult?>()
        for (dynamicTest in getDescription().getChildren()) {
            childResults.add(buildDynamicResult(dynamicTest, this.runtime, this.testResultStatus))
        }

        val numTests = if (getDescription().isTest()) 1 else getDescription().getChildren().size
        val numFailures = if (globalFailures.isEmpty()) dynamicTestToFailures.keys.size else numTests
        return TestResult.Builder()
            .name(name)
            .className(className)
            .properties(properties)
            .failures(ArrayList<Throwable?>(globalFailures))
            .runTimeInterval(this.runtime)
            .status(this.testResultStatus)
            .numTests(numTests)
            .numFailures(numFailures)
            .childResults(childResults)
            .integrations(integrations)
            .build()
    }

    private fun buildDynamicResult(
        test: Description, runTime: TestInterval?, status: TestResult.Status?
    ): TestResult {
        // The dynamic test fails if the testcase itself fails or there is
        // a dynamic failure specifically for the dynamic test.
        var dynamicFailures = dynamicTestToFailures.get(test)
        if (dynamicFailures == null) {
            dynamicFailures = ArrayList<Throwable?>()
        }
        val failed = !globalFailures.isEmpty() || !dynamicFailures.isEmpty()
        return TestResult.Builder()
            .name(test.getDisplayName())
            .className(getDescription().getDisplayName())
            .properties(mutableMapOf<String?, String?>())
            .failures(dynamicFailures)
            .runTimeInterval(runTime)
            .status(status)
            .numTests(1)
            .numFailures(if (failed) 1 else 0)
            .childResults(mutableListOf<TestResult?>())
            .integrations(mutableSetOf<TestIntegration?>())
            .build()
    }

    /**
     * States of a TestCaseNode (see (link) for all the transitions and states descriptions).
     */
    private enum class State(status: TestResult.Status) {
        INITIAL(TestResult.Status.FILTERED),
        PENDING(TestResult.Status.CANCELLED),
        STARTED(TestResult.Status.INTERRUPTED),
        SKIPPED(TestResult.Status.SKIPPED),
        SUPPRESSED(TestResult.Status.SUPPRESSED),
        CANCELLED(TestResult.Status.CANCELLED),
        INTERRUPTED(TestResult.Status.INTERRUPTED),
        FINISHED(TestResult.Status.COMPLETED);

        /**
         * @return The equivalent [TestResult.Status] if the test execution ends with the FSM
         * at this state.
         */
        val testResultStatus: TestResult.Status?

        init {
            this.testResultStatus = status
        }
    }

    companion object {
        private val INITIAL_STATES: MutableSet<State?> = Collections.unmodifiableSet<State?>(
            EnumSet.of<State?>(State.INITIAL, State.PENDING)
        )
    }
}
