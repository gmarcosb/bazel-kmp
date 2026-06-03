// Copyright 2024 The Bazel Authors. All rights reserved.
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(JUnit4::class)
class QuiescingFutureTest {
    @Test
    @Throws(Exception::class)
    fun immediateCompletion() {
        val future = ConstantQuiescingFuture()
        assertThat(future.isDone()).isFalse()

        future.decrement()

        assertThat(future.isDone()).isTrue()
        assertThat(future.get()).isEqualTo("result")
    }

    @Test
    @Throws(Exception::class)
    fun exceptionPropagates() {
        val future = ConstantQuiescingFuture()
        assertThat(future.isDone()).isFalse()

        val error = Throwable("failure")
        future.notifyException(error)

        assertThat(future.isDone()).isTrue()
        val thrown = Assert.assertThrows<ExecutionException?>(ExecutionException::class.java, future::get)
        Truth.assertThat(thrown).hasCauseThat().isSameInstanceAs(error)
    }

    @Test
    @Throws(Exception::class)
    fun transientZeroing_doesNotPrematurelyComplete() {
        val future = ConstantQuiescingFuture()
        assertThat(future.isDone()).isFalse()

        future.increment()
        future.decrement() // count reaches "0"

        assertThat(future.isDone()).isFalse()

        future.decrement()
        assertThat(future.isDone()).isTrue()
        assertThat(future.get()).isEqualTo("result")
    }

    private class ConstantQuiescingFuture : QuiescingFuture<String?>(MoreExecutors.directExecutor()) {
        protected val value: String
            get() = "result"
    }

    @Test
    @Throws(Exception::class)
    fun concurrentRecursiveTasks() {
        val completionCount = AtomicInteger()
        val future = CountingQuiescingFuture(completionCount)

        ForkJoinPool.commonPool().execute(RecurrentTask(future, completionCount, 0))
        future.decrement()

        // If this passes, it means the counter value at the time of completion included all tasks,
        // showing that there was no early completion.
        assertThat(future.get()).isEqualTo(1023)
    }

    private class RecurrentTask(future: QuiescingFuture<*>, counter: AtomicInteger, depth: Int) : Runnable {
        private val future: QuiescingFuture<*>
        private val counter: AtomicInteger
        private val depth: Int

        init {
            this.future = future
            this.counter = counter
            this.depth = depth

            future.increment()
        }

        override fun run() {
            if (depth < MAX_DEPTH) {
                for (i in 0..1) {
                    ForkJoinPool.commonPool().execute(RecurrentTask(future, counter, depth + 1))
                }
            }
            counter.getAndIncrement()
            future.decrement()
        }
    }

    private class CountingQuiescingFuture(private val counter: AtomicInteger) :
        QuiescingFuture<Int?>(MoreExecutors.directExecutor()) {
        protected val value: Int
            get() = counter.get()
    }

    @Test
    @Throws(Exception::class)
    fun notifyException_callsDoneWithError_notGetValue() {
        val doneWithErrorCalled = AtomicBoolean(false)
        val getValueCalled = AtomicBoolean(false)
        val future = TestQuiescingFuture(doneWithErrorCalled, getValueCalled)

        val error = RuntimeException("oops")
        future.notifyException(error)

        assertThat(future.isDone()).isTrue()
        Truth.assertThat(doneWithErrorCalled.get()).isTrue()
        Truth.assertThat(getValueCalled.get()).isFalse()

        // Future should be in an error state
        val thrown = Assert.assertThrows<ExecutionException?>(ExecutionException::class.java, future::get)
        Truth.assertThat(thrown).hasCauseThat().isSameInstanceAs(error)
    }

    @Test
    @Throws(Exception::class)
    fun notifyException_multipleErrors_callsDoneWithErrorOnce() {
        val doneWithErrorCallCount = AtomicInteger(0)
        val getValueCalled = AtomicBoolean(false)
        val future =
            TestQuiescingFuture(
                Runnable { doneWithErrorCallCount.getAndIncrement() }, Runnable { getValueCalled.set(true) })

        future.increment() // Add an extra task
        future.notifyException(RuntimeException("error1"))
        assertThat(future.isDone()).isTrue() // Done after first exception

        future.notifyException(RuntimeException("error2")) // Second error

        // Wait for all decrements to complete
        Assert.assertThrows<ExecutionException?>(ExecutionException::class.java, future::get)
        Truth.assertThat(doneWithErrorCallCount.get()).isEqualTo(1)
        Truth.assertThat(getValueCalled.get()).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun mixNotifyExceptionAndDecrement_callsDoneWithError() {
        val doneWithErrorCalled = AtomicBoolean(false)
        val getValueCalled = AtomicBoolean(false)
        val future = TestQuiescingFuture(doneWithErrorCalled, getValueCalled)

        future.increment()
        future.increment()

        future.notifyException(RuntimeException("error"))
        assertThat(future.isDone()).isTrue() // Done after first exception

        future.decrement()
        Truth.assertThat(doneWithErrorCalled.get()).isFalse() // Not called yet

        future.decrement()
        Truth.assertThat(doneWithErrorCalled.get()).isTrue() // Called after all decrements
        Truth.assertThat(getValueCalled.get()).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun executorTest() {
        val executorCalled = AtomicBoolean(false)
        val future: QuiescingFuture<String?>? =
            object : QuiescingFuture<String?>(
                { command ->
                    executorCalled.set(true)
                    command.run()
                }) {
                protected val value: String
                    get() = "executed"
            }

        future.decrement()
        assertThat(future.get()).isEqualTo("executed")
        Truth.assertThat(executorCalled.get()).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun concurrentNotifyExceptionAndDecrement() {
        val doneWithErrorCalled = CountDownLatch(1)
        val getValueCalled = AtomicBoolean(false)
        val future =
            TestQuiescingFuture(
                Runnable { doneWithErrorCalled.countDown() }, Runnable { getValueCalled.set(true) })

        val error = RuntimeException("concurrent error")
        for (i in 0..9) {
            future.increment()
            val capturedIndex = i
            ForkJoinPool.commonPool()
                .execute(
                    Runnable {
                        if (capturedIndex % 2 == 0) {
                            future.notifyException(error)
                        } else {
                            future.decrement()
                        }
                    })
        }
        future.decrement() // Clears the pre-increment.

        // Waits for completion
        val thrown = Assert.assertThrows<ExecutionException?>(ExecutionException::class.java, future::get)
        Truth.assertThat(thrown).hasCauseThat().isSameInstanceAs(error)

        assertThat(future.isDone()).isTrue()

        Truth.assertThat(doneWithErrorCalled.await(60, TimeUnit.SECONDS)).isTrue()
    }

    private class TestQuiescingFuture(
        private val doneWithErrorCallback: Runnable,
        private val getValueCallback: Runnable
    ) : QuiescingFuture<String?>(
        MoreExecutors.directExecutor()
    ) {
        private constructor(
            doneWithErrorCalled: AtomicBoolean,
            getValueCalled: AtomicBoolean
        ) : this(Runnable { doneWithErrorCalled.set(true) }, Runnable { getValueCalled.set(true) })

        protected val value: String
            get() {
                getValueCallback.run()
                return "result"
            }

        protected override fun doneWithError() {
            doneWithErrorCallback.run()
        }
    }

    companion object {
        private const val MAX_DEPTH = 9
    }
}
