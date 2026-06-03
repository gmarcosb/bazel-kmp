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
package com.google.devtools.build.lib.concurrent

import com.google.common.truth.Truth
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Tests for [TaskDeduplicator].  */
@RunWith(JUnit4::class)
class TaskDeduplicatorTest {
    @Test
    @Throws(Exception::class)
    fun executeIfNew_taskFinished_completed() {
        val deduplicator: TaskDeduplicator<String?, String?> = TaskDeduplicator<String?, String?>()
        val taskFuture = SettableFuture.create<String?>()

        val result: ListenableFuture<String?> = deduplicator.executeIfNew("key1", { taskFuture })

        taskFuture.set("value1")

        Truth.assertThat(result.get()).isEqualTo("value1")
    }

    @Test
    fun executeIfNew_taskHasError_propagateError() {
        val deduplicator: TaskDeduplicator<String?, String?> = TaskDeduplicator<String?, String?>()
        val taskFuture = SettableFuture.create<String?>()
        val error = IllegalStateException("error")

        val result: ListenableFuture<String?> = deduplicator.executeIfNew("key1", { taskFuture })

        taskFuture.setException(error)

        val exception =
            Assert.assertThrows<ExecutionException?>(ExecutionException::class.java, ThrowingRunnable { result.get() })
        Truth.assertThat(exception).hasCauseThat().isSameInstanceAs(error)
    }

    @Test
    @Throws(Exception::class)
    fun executeIfNew_taskInProgress_noReExecution() {
        val deduplicator: TaskDeduplicator<String?, String?> = TaskDeduplicator<String?, String?>()
        val taskFuture = SettableFuture.create<String?>()
        val executionTimes = AtomicInteger(0)

        val result1: ListenableFuture<String?> =
            deduplicator.executeIfNew(
                "key1",
                {
                    executionTimes.incrementAndGet()
                    taskFuture
                })

        // Second call with the same key should return the same future, not re-execute
        val result2: ListenableFuture<String?> =
            deduplicator.executeIfNew(
                "key1",
                {
                    throw IllegalStateException("should not be called")
                })

        Truth.assertThat(result1.isDone()).isFalse()
        Truth.assertThat(result2.isDone()).isFalse()

        taskFuture.set("value1")

        Truth.assertThat(result1.get()).isEqualTo("value1")
        Truth.assertThat(result2.get()).isEqualTo("value1")
        Truth.assertThat(executionTimes.get()).isEqualTo(1)
    }

    @Test
    @Throws(Exception::class)
    fun executeIfNew_taskFinished_reExecution() {
        val deduplicator: TaskDeduplicator<String?, String?> = TaskDeduplicator<String?, String?>()
        val executionTimes = AtomicInteger(0)

        // First execution
        val result1: ListenableFuture<String?> =
            deduplicator.executeIfNew(
                "key1",
                {
                    executionTimes.incrementAndGet()
                    val future = SettableFuture.create<String?>()
                    future.set("value1")
                    future
                })

        Truth.assertThat(result1.get()).isEqualTo("value1")
        Truth.assertThat(executionTimes.get()).isEqualTo(1)

        // Second execution after first is finished should re-execute
        val result2: ListenableFuture<String?> =
            deduplicator.executeIfNew(
                "key1",
                {
                    executionTimes.incrementAndGet()
                    val future = SettableFuture.create<String?>()
                    future.set("value2")
                    future
                })

        Truth.assertThat(result2.get()).isEqualTo("value2")
        Truth.assertThat(executionTimes.get()).isEqualTo(2)
    }

    @Test
    fun executeIfNew_taskCanceled_reExecution() {
        val deduplicator: TaskDeduplicator<String?, String?> = TaskDeduplicator<String?, String?>()
        val executionTimes = AtomicInteger(0)

        // First execution
        val result1: ListenableFuture<String?> =
            deduplicator.executeIfNew(
                "key1",
                {
                    executionTimes.incrementAndGet()
                    Futures.immediateCancelledFuture<V?>()
                })

        Truth.assertThat(result1.isCancelled()).isTrue()
        Truth.assertThat(executionTimes.get()).isEqualTo(1)

        // Second execution after first is finished should re-execute
        val result2: ListenableFuture<String?> =
            deduplicator.executeIfNew(
                "key1",
                {
                    executionTimes.incrementAndGet()
                    Futures.immediateCancelledFuture<V?>()
                })

        Truth.assertThat(result2.isCancelled()).isTrue()
        Truth.assertThat(executionTimes.get()).isEqualTo(2)
    }

    @Test
    @Throws(Exception::class)
    fun executeIfNew_cancel_cancelled() {
        val deduplicator: TaskDeduplicator<String?, String?> = TaskDeduplicator<String?, String?>()
        val taskFuture = SettableFuture.create<String?>()

        val result: ListenableFuture<String?> = deduplicator.executeIfNew("key1", { taskFuture })

        result.cancel(true)

        Truth.assertThat(result.isCancelled()).isTrue()
        Assert.assertThrows<CancellationException?>(
            CancellationException::class.java,
            ThrowingRunnable { result.get() })
    }

    @Test
    @Throws(Exception::class)
    fun executeIfNew_cancelWhenMultipleFutures_notCancelled() {
        val deduplicator: TaskDeduplicator<String?, String?> = TaskDeduplicator<String?, String?>()
        val taskFuture = SettableFuture.create<String?>()

        val result1: ListenableFuture<String?> = deduplicator.executeIfNew("key1", { taskFuture })
        val result2: ListenableFuture<String?> = deduplicator.executeIfNew("key1", { taskFuture })

        // Cancel one future multiple times
        result1.cancel(true)
        result1.cancel(true)

        // The task should still be running for the second future
        Truth.assertThat(result1.isCancelled()).isTrue()
        Truth.assertThat(result2.isDone()).isFalse()

        taskFuture.set("value1")

        Truth.assertThat(result2.get()).isEqualTo("value1")
    }

    @Test
    fun executeIfNew_cancelWhenMultipleFutures_allCancelled() {
        val deduplicator: TaskDeduplicator<String?, String?> = TaskDeduplicator<String?, String?>()
        val taskFuture = SettableFuture.create<String?>()

        val result1: ListenableFuture<String?> = deduplicator.executeIfNew("key1", { taskFuture })
        val result2: ListenableFuture<String?> = deduplicator.executeIfNew("key1", { taskFuture })

        // Cancel both futures
        result1.cancel(true)
        result2.cancel(true)

        Truth.assertThat(result1.isCancelled()).isTrue()
        Truth.assertThat(result2.isCancelled()).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun executeIfNew_multipleTasks_completeOne() {
        val deduplicator: TaskDeduplicator<String?, String?> = TaskDeduplicator<String?, String?>()
        val taskFuture1 = SettableFuture.create<String?>()
        val taskFuture2 = SettableFuture.create<String?>()

        val result1: ListenableFuture<String?> = deduplicator.executeIfNew("key1", { taskFuture1 })
        val result2: ListenableFuture<String?> = deduplicator.executeIfNew("key2", { taskFuture2 })

        taskFuture1.set("value1")

        Truth.assertThat(result1.get()).isEqualTo("value1")
        Truth.assertThat(result2.isDone()).isFalse()

        taskFuture2.set("value2")

        Truth.assertThat(result2.get()).isEqualTo("value2")
    }

    @Test
    fun executeIfNeeded_executeAndCancelLoop_noErrors() {
        val taskCount = 1000
        val maxKey = 20
        val random = Random()
        val deduplicator: TaskDeduplicator<String?, Void?> = TaskDeduplicator<String?, Void?>()
        val throwables = ConcurrentLinkedQueue<Throwable>()

        Executors.newFixedThreadPool(50).use { taskExecutorService ->
            Executors.newVirtualThreadPerTaskExecutor().use { testExecutorService ->
                for (i in 0..<taskCount) {
                    testExecutorService.execute(
                        Runnable {
                            try {
                                val future: ListenableFuture<Void?> =
                                    deduplicator.executeIfNew(
                                        "key" + random.nextInt(maxKey),
                                        {
                                            Futures.submit<O?>(
                                                Callable {
                                                    Thread.sleep((Math.random() * 100).toLong())
                                                    null as Void?
                                                },
                                                taskExecutorService
                                            )
                                        })

                                if (!future.isDone() && random.nextBoolean()) {
                                    future.cancel(true)
                                } else {
                                    future.get()
                                }
                            } catch (e: Throwable) {
                                if (e is InterruptedException) {
                                    Thread.currentThread().interrupt()
                                }
                                throwables.add(e)
                            }
                        })
                }
            }
        }
        if (!throwables.isEmpty()) {
            val combinedError = AssertionError()
            for (throwable in throwables) {
                combinedError.addSuppressed(throwable)
            }
            throw combinedError
        }
    }

    @Test
    @Throws(Exception::class)
    fun executeIfNew_taskCompletedBeforeSecondCall_bothGetSameResult() {
        val deduplicator: TaskDeduplicator<String?, String?> = TaskDeduplicator<String?, String?>()
        val taskStarted = AtomicBoolean(false)

        // First call - task completes immediately in-flight
        val result1: ListenableFuture<String?> =
            deduplicator.executeIfNew(
                "key1",
                {
                    taskStarted.set(true)
                    val future = SettableFuture.create<String?>()
                    future.set("value1")
                    future
                })

        Truth.assertThat(result1.get()).isEqualTo("value1")
        Truth.assertThat(taskStarted.get()).isTrue()
        taskStarted.set(false)

        // Second call - task should execute again since first is done
        val result2: ListenableFuture<String?> =
            deduplicator.executeIfNew(
                "key1",
                {
                    taskStarted.set(true)
                    val future = SettableFuture.create<String?>()
                    future.set("value2")
                    future
                })

        Truth.assertThat(result2.get()).isEqualTo("value2")
        Truth.assertThat(taskStarted.get()).isTrue()
    }

    @Test
    fun executeIfNew_errorInAsyncCallable_propagated() {
        val deduplicator: TaskDeduplicator<String?, String?> = TaskDeduplicator<String?, String?>()
        val expectedException = RuntimeException("task creation failed")

        val actualException =
            Assert.assertThrows<RuntimeException?>(
                RuntimeException::class.java,
                ThrowingRunnable {
                    deduplicator.executeIfNew(
                        "key1",
                        {
                            throw expectedException
                        })
                })
        Truth.assertThat(actualException).isSameInstanceAs(expectedException)
    }
}
