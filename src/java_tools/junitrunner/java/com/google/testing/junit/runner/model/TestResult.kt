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

import com.google.errorprone.annotations.CanIgnoreReturnValue
import com.google.testing.junit.runner.util.TestIntegration

/** Result of executing a test suite or test case.  */
class TestResult private constructor(builder: Builder) {
    /**
     * Possible result values to a test.
     */
    enum class Status(private val wasRun: Boolean) {
        /**
         * Test case was not run because the test decided that it should not be run
         * (e.g.: due to a failed assumption in a JUnit4-style tests).
         */
        SKIPPED(false),

        /**
         * Test case was not run because the user specified that it should be filtered out of the
         * test run.
         */
        FILTERED(false),

        /**
         * Test case was not run because the test was labeled in the code as suppressed
         * (e.g.: the test was annotated with `@Suppress` or `@Ignore`).
         */
        SUPPRESSED(false),

        /**
         * Test case was not started because the test harness run was interrupted by a
         * signal or timed out.
         */
        CANCELLED(false),

        /**
         * Test case was started but not finished because the test harness run was interrupted by a
         * signal or timed out.
         */
        INTERRUPTED(true),

        /**
         * Test case was run and completed (possibly failing or throwing an exception, but not
         * interrupted).
         */
        COMPLETED(true);

        /**
         * Equivalent semantic value to wasRun `status="run|notrun"` on
         * the XML schema.
         */
        fun wasRun(): Boolean {
            return wasRun
        }
    }

    private val name: String?
    private val className: String?
    private val properties: MutableMap<String?, String?>? = null
    private val failures: MutableList<Throwable?>?
    private val runTime: TestInterval?
    private val integrations: MutableSet<TestIntegration?>?
    private val status: Status?
    private val numTests: Int
    private val numFailures: Int
    private val childResults: MutableList<TestResult?>?

    init {
        .also {
            name = it
        }<String> com . google . testing . junit . runner . model . TestResult . Companion . checkNotNull < kotlin . String ? > (builder.name, "name not set")
        .also {
            className = it
        }<String> com . google . testing . junit . runner . model . TestResult . Companion . checkNotNull < kotlin . String ? > (builder.className, "className not set")
        TODO(
            """
            |Cannot convert element
            |With text:
            |properties = <Map<String, String>>checkNotNull(builder.properties, "properties not set");
            """.trimMargin()
        )
            .also { failures = it } < List < Throwable shr checkNotNull<MutableList<Throwable?>?>(
            builder.failures,
            "failures not set"
        )
        runTime = builder.runTime
            .also {
                status = it
            }<Status> com . google . testing . junit . runner . model . TestResult . Companion . checkNotNull < com . google . testing . junit . runner . model . TestResult . Status ? > (builder.status, "status not set")
        .also {
            numTests = it
        }<Integer> com . google . testing . junit . runner . model . TestResult . Companion . checkNotNull < Int ? > (builder.numTests, "numTests not set")
        .also {
            numFailures = it
        }<Integer> com . google . testing . junit . runner . model . TestResult . Companion . checkNotNull < Int ? > (builder.numFailures, "numFailures not set")
        .also {
            childResults = it
        } < List < TestResult shr checkNotNull<MutableList<TestResult?>?>(builder.childResults, "childResults not set")
            .also {
                integrations = it
            } < Set < TestIntegration shr checkNotNull<MutableSet<TestIntegration?>?>(
            builder.integrations,
            "integrations not set"
        )
    }

    fun getName(): String? {
        return name
    }

    fun getClassName(): String? {
        return className
    }

    fun getProperties(): MutableMap<String?, String?>? {
        return properties
    }

    fun getFailures(): MutableList<Throwable?>? {
        return failures
    }

    fun getIntegrations(): MutableSet<TestIntegration?>? {
        return integrations
    }

    fun getRunTimeInterval(): TestInterval? {
        return runTime
    }

    fun getStatus(): Status? {
        return status
    }

    fun wasRun(): Boolean {
        return getStatus()!!.wasRun()
    }

    fun getNumTests(): Int {
        return numTests
    }

    fun getNumFailures(): Int {
        return numFailures
    }

    fun getChildResults(): MutableList<TestResult?>? {
        return childResults
    }

    class Builder {
        private var name: String? = null
        private var className: String? = null
        private var properties: MutableMap<String?, String?>? = null
        private var failures: MutableList<Throwable?>? = null
        private var runTime: TestInterval? = null
        private var integrations: MutableSet<TestIntegration?>? = null
        private var status: Status? = null
        private var numTests: Int? = null
        private var numFailures: Int? = null
        private var childResults: MutableList<TestResult?>? = null

        @CanIgnoreReturnValue
        fun name(name: String?): Builder {
            this.name = checkNullToNotNull<String?>(this.name, name, "name")
            return this
        }

        @CanIgnoreReturnValue
        fun className(className: String?): Builder {
            this.className = checkNullToNotNull<String?>(this.className, className, "className")
            return this
        }

        @CanIgnoreReturnValue
        fun properties(properties: MutableMap<String?, String?>?): Builder {
            TODO(
                """
                |Cannot convert element
                |With text:
                |this.properties = <Map<String, String>>checkNullToNotNull(this.properties, properties, "properties");
                """.trimMargin()
            )
            return this
        }

        @CanIgnoreReturnValue
        fun integrations(integrations: MutableSet<TestIntegration?>?): Builder {
            this.integrations =
                checkNullToNotNull<MutableSet<TestIntegration?>?>(this.integrations, integrations, "integrations")
            return this
        }

        @CanIgnoreReturnValue
        fun failures(failures: MutableList<Throwable?>?): Builder {
            this.failures = checkNullToNotNull<MutableList<Throwable?>?>(this.failures, failures, "failures")
            return this
        }

        @CanIgnoreReturnValue
        fun runTimeInterval(runTime: TestInterval?): Builder {
            check(this.runTime == null) { "runTime already set" }
            this.runTime = runTime
            return this
        }

        @CanIgnoreReturnValue
        fun status(status: Status?): Builder {
            this.status = checkNullToNotNull<Status?>(this.status, status, "status")
            return this
        }

        @CanIgnoreReturnValue
        fun numTests(numTests: Int): Builder {
            this.numTests = checkNullToNotNull<Int?>(this.numTests, numTests, "numTests")
            return this
        }

        @CanIgnoreReturnValue
        fun numFailures(numFailures: Int): Builder {
            this.numFailures = checkNullToNotNull<Int?>(this.numFailures, numFailures, "numFailures")
            return this
        }

        @CanIgnoreReturnValue
        fun childResults(childResults: MutableList<TestResult?>?): Builder {
            this.childResults =
                checkNullToNotNull<MutableList<TestResult?>?>(this.childResults, childResults, "childResults")
            return this
        }

        fun build(): TestResult {
            return TestResult(this)
        }

        companion object {
            private fun <T> checkNullToNotNull(currValue: T?, newValue: T?, desc: String?): T? {
                check(currValue == null) { desc + " already set" }
                return checkNotNull<T?>(newValue, desc + " is null")
            }
        }
    }

    companion object {
        private fun <T> checkNotNull(reference: T?, errorMessage: String?): T? {
            if (reference == null) {
                throw NullPointerException(errorMessage)
            }
            return reference
        }
    }
}
