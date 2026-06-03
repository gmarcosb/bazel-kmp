// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.testutil.ThrowableRecordingRunnableWrapper
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ThrowableRecordingRunnableWrapper] that can throw if one task has thrown an exception but
 * others are still processing.
 */
class TestRunnableWrapper(name: String?) : ThrowableRecordingRunnableWrapper(name) {
    // Because IncrementableCountDownLatch isn't public, we have to use a hacky AtomicInteger.
    private val runningTasks: AtomicInteger = AtomicInteger(0)

    @Throws(java.lang.Exception::class)
    fun waitForTasksAndMaybeThrow() {
        var firstThrownError: Throwable?
        do {
            firstThrownError = getFirstThrownError()
            if (firstThrownError != null) {
                com.google.common.base.Throwables.propagateIfPossible(firstThrownError)
                throw java.lang.RuntimeException(firstThrownError)
            }
            java.lang.Thread.sleep(100)
        } while (runningTasks.get() > 0)
    }

    override fun wrap(runnable: java.lang.Runnable?): java.lang.Runnable {
        val wrapped: java.lang.Runnable = super.wrap(runnable)
        return object : java.lang.Runnable {
            override fun run() {
                runningTasks.incrementAndGet()
                try {
                    wrapped.run()
                } finally {
                    runningTasks.decrementAndGet()
                }
            }
        }
    }
}
