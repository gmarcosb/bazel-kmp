// Copyright 2009 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.junit.runner.util

import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder.get
import com.google.testing.junit.runner.util.TestNameProvider

/**
 * Utility class for recording and retrieving information about what test
 * is running in the current thread. This class is currently compatible
 * with JUnit 3 and JUnit4.
 */
open class CurrentRunningTest {
    companion object {
        protected var testNameProvider: TestNameProvider? = null

        /**
         * If called during a JUnit test run with our test runner, returns the test running in the current
         * thread. Otherwise (for example, when the test is run directly in an IDE), returns `null`.
         * 
         * 
         * Our test runner is special only in that it installs [.testNameProvider] to listen for
         * test start/stop events using
         * [org.junit.runner.JUnitCore.addListener].
         */
        fun get(): org.junit.runner.Description? {
            return if (testNameProvider != null) testNameProvider.get() else null
        }
    }
}
