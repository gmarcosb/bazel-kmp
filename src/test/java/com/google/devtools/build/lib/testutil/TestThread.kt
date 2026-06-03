// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.testutil

import com.google.common.truth.Truth

/**
 * Test thread implementation that allows the use of assertions within spawned threads.
 * 
 * 
 * Main test method must call [TestThread.joinAndAssertState] for each spawned test
 * thread.
 */
class TestThread
/** Constructs a new test thread that will run the given runnable.  */(private val runnable: TestRunnable) :
    java.lang.Thread() {
    private var testException: Throwable? = null
    private var isSucceeded = false

    /** Same as a [Runnable] but allowed to throw any exception.  */
    fun interface TestRunnable {
        @Throws(java.lang.Exception::class)
        fun run()
    }

    override fun run() {
        try {
            runnable.run()
            isSucceeded = true
        } catch (e: java.lang.Exception) {
            testException = e
        } catch (e: java.lang.AssertionError) {
            testException = e
        }
    }

    /**
     * Joins test thread (waiting specified number of ms) and validates that
     * it has been completed successfully.
     */
    @Throws(java.lang.InterruptedException::class)
    fun joinAndAssertState(timeout: Long) {
        join(timeout)
        var exception = this.testException
        if (isAlive()) {
            exception = java.lang.AssertionError(
                "Test thread " + getName() + " is still alive"
            )
            exception.setStackTrace(getStackTrace())
        }
        if (exception != null) {
            val error: java.lang.AssertionError =
                java.lang.AssertionError("Test thread " + getName() + " failed to execute")
            error.initCause(exception)
            throw error
        }
        Truth.assertWithMessage("Test thread %s has not run successfully", getName())
            .that(isSucceeded)
            .isTrue()
    }
}
