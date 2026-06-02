// Copyright 2011 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.junit.runner.internal.junit4

import com.google.testing.junit.runner.util.TestNameProvider
import org.junit.runner.Description

/**
 * A listener to get the name of a JUnit4 test.
 */
class JUnit4TestNameListener(private val currentRunningTest: SettableCurrentRunningTest) : RunListener() {
    private val runningTest = ThreadLocal<Description?>()

    @Throws(Exception::class)
    override fun testRunStarted(description: Description?) {
        currentRunningTest.setGlobalTestNameProvider(object : TestNameProvider() {
            public override fun get(): Description? {
                return runningTest.get()
            }
        })
    }

    @Throws(Exception::class)
    override fun testStarted(description: Description?) {
        runningTest.set(description)
    }

    @Throws(Exception::class)
    override fun testFinished(description: Description?) {
        runningTest.set(null)
    }
}
