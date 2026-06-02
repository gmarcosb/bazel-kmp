// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.blackbox.junit

import com.google.devtools.build.lib.bazel.repository.decompressor.DecompressorDescriptor.Builder.build
import com.google.devtools.build.lib.bazel.repository.decompressor.PatchUtil.apply
import com.google.devtools.build.lib.blackbox.junit.TimeoutTestWatcher
import org.junit.rules.TestWatcher

/**
 * Test watcher, which sets a timeout for the JUnit test and allows to execute some action on
 * timeout. Uses JUnit's org.junit.rules.Timeout rule to set up a timeout; catches timeout exception
 * thrown fromTimeout rule, calls the [onTimeout] method, and re-throws the exception.
 * 
 * 
 * Useful to dump test state information before failing on timeout.
 */
abstract class TimeoutTestWatcher : TestWatcher() {
    var name: String? = null
        private set

    protected abstract val timeoutMillis: Long

    protected abstract fun onTimeout(): Boolean

    override fun starting(description: org.junit.runner.Description) {
        name = description.getMethodName()
    }

    override fun finished(description: org.junit.runner.Description?) {
        name = null
    }

    override fun apply(
        base: org.junit.runners.model.Statement,
        description: org.junit.runner.Description?
    ): org.junit.runners.model.Statement {
        // we are using exception wrapping, because unfortunately JUnit's Timeout throws
        // java.util.Exception on timeout, which is hard to distinguish from other cases
        val wrapper: org.junit.runners.model.Statement =
            object : org.junit.runners.model.Statement() {
                @Throws(Throwable::class)
                override fun evaluate() {
                    try {
                        base.evaluate()
                    } catch (th: Throwable) {
                        throw com.google.devtools.build.lib.blackbox.junit.TimeoutTestWatcher.ExceptionWrapper(th)
                    }
                }
            }

        return object : org.junit.runners.model.Statement() {
            @Throws(Throwable::class)
            override fun evaluate() {
                try {
                    org.junit.rules.Timeout(this.timeoutMillis.toInt()).apply(wrapper, description).evaluate()
                } catch (wrapper: ExceptionWrapper) {
                    // original test exception
                    throw wrapper.cause
                } catch (e: java.lang.Exception) {
                    // timeout exception
                    if (!onTimeout()) {
                        throw java.util.concurrent.TimeoutException(e.message)
                    }
                }
            }
        }
    }

    /**
     * Exception wrapper wrap-and-caught any exception from the test; this guarantees that we
     * differentiate timeout exception thrown just as java.util.Exception from the test exceptions
     */
    private class ExceptionWrapper(cause: Throwable?) : Throwable(cause)
}
