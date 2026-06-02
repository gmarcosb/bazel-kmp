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

/**
 * A node in a test suite.
 */
abstract class TestNode internal constructor(description: Description) {
    /**
     * [Description] of this test node.
     */
    val description: Description
    var result: TestResult? = null
        get() {
            if (field == null) {
                field = buildResult()
            }
            return field
        }
        private set

    init {
        if (description == null) {
            throw NullPointerException()
        }
        this.description = description
    }

    /**
     * Returns this node's children (test suites or tests cases).
     */
    // VisibleForTesting
    abstract val children: MutableList<TestNode?>?
        /**
         * Returns this node's children (test suites or tests cases).
         */
        get

    /**
     * Returns true if this node is a test case (e.g. junit4 test), false otherwise (e.g. junit4 test
     * suite). The [TestSuiteModel] distinguishes between test cases and suites based on the
     * value returned by [Description.isTest].
     */
    abstract fun isTestCase(): Boolean

    /** Indicates that the test represented by this node was skipped.  */
    abstract fun testSkipped(now: TestInstant?)

    /**
     * Indicates that the test represented by this node was ignored or suppressed due to being
     * annotated with `@Ignore` or `@Suppress`.
     */
    abstract fun testSuppressed(now: TestInstant?)

    /** Indicates that the test represented by this node was interrupted.  */
    abstract fun testInterrupted(now: TestInstant?)

    /** Adds a failure to the test represented by this node.  */
    abstract fun testFailure(throwable: Throwable?, now: TestInstant?)

    /** Indicates that a dynamically generated test case or suite failed.  */
    abstract fun dynamicTestFailure(test: Description?, throwable: Throwable?, now: TestInstant?)

    /**
     * Template-method that creates a [TestResult] object that represents the test outcome of
     * this node.
     */
    protected abstract fun buildResult(): TestResult?
}
