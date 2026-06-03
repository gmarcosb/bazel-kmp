// Copyright 2026 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.concurrent

import com.google.common.truth.Truth
import com.google.common.util.concurrent.MoreExecutors
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(JUnit4::class)
class QuiescingFutureTaskTest {
    @Test
    @Throws(Exception::class)
    fun runOnce() {
        val callCount = AtomicInteger(0)
        val task: QuiescingFutureTask<String?>? =
            object : QuiescingFutureTask<String?>(MoreExecutors.directExecutor()) {
                protected override fun arrangeSubtasks() {
                    callCount.incrementAndGet()
                }

                protected val value: String
                    get() = "result"
            }

        assertThat(task.isDone()).isFalse()
        task.run()
        assertThat(task.isDone()).isTrue()
        assertThat(task.get()).isEqualTo("result")
        Truth.assertThat(callCount.get()).isEqualTo(1)

        // Running again should not call arrangeSubtasks but still result in the same value (already
        // done)
        task.run()
        Truth.assertThat(callCount.get()).isEqualTo(1)
    }

    @Test
    @Throws(Exception::class)
    fun subtasksCompletion() {
        val subtaskCallCount = AtomicInteger(0)
        val task: QuiescingFutureTask<String?>? =
            object : QuiescingFutureTask<String?>(MoreExecutors.directExecutor()) {
                protected override fun arrangeSubtasks() {
                    increment()
                    subtaskCallCount.incrementAndGet()
                }

                protected val value: String
                    get() = "result"
            }

        task.run()
        assertThat(task.isDone()).isFalse()
        Truth.assertThat(subtaskCallCount.get()).isEqualTo(1)

        task.decrement()
        assertThat(task.isDone()).isTrue()
        assertThat(task.get()).isEqualTo("result")
    }

    @Test
    @Throws(Exception::class)
    fun exceptionInArrangeSubtasks() {
        val error = RuntimeException("oops")
        val task: QuiescingFutureTask<String?>? =
            object : QuiescingFutureTask<String?>(MoreExecutors.directExecutor()) {
                protected override fun arrangeSubtasks() {
                    throw error
                }

                protected val value: String
                    get() = "result"
            }

        task.run()
        assertThat(task.isDone()).isTrue()
        val thrown = Assert.assertThrows<ExecutionException?>(ExecutionException::class.java, task::get)
        Truth.assertThat(thrown).hasCauseThat().isSameInstanceAs(error)
    }

    @Test
    @Throws(Exception::class)
    fun doneWithErrorCalled() {
        val doneWithErrorCalled = AtomicBoolean(false)
        val task: QuiescingFutureTask<String?>? =
            object : QuiescingFutureTask<String?>(MoreExecutors.directExecutor()) {
                protected override fun arrangeSubtasks() {
                    notifyException(RuntimeException("error"))
                }

                protected val value: String
                    get() = "result"

                protected override fun doneWithError() {
                    doneWithErrorCalled.set(true)
                }
            }

        task.run()
        assertThat(task.isDone()).isTrue()
        Truth.assertThat(doneWithErrorCalled.get()).isTrue()
    }
}
