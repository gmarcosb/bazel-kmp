// Copyright 2025 The Bazel Authors. All rights reserved.
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
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Tests for [WorkStealingThreadPoolExecutor].  */
@RunWith(JUnit4::class)
class WorkStealingThreadPoolExecutorTest {
    @Test
    @Throws(Exception::class)
    fun execute_allTasksAreExecuted_numTasksLessThanNumWorkers() {
        val interrupted = AtomicBoolean(false)
        val sum = AtomicInteger(0)
        val numTasks: Int = PARALLELISM / 2
        WorkStealingThreadPoolExecutor(PARALLELISM, Thread.ofPlatform().factory()).use { executor ->
            val countDown = CountDownLatch(numTasks)
            for (i in 0..<numTasks) {
                executor.execute(
                    {
                        sum.incrementAndGet()
                        try {
                            Thread.sleep(1)
                        } catch (e: InterruptedException) {
                            interrupted.set(true)
                        }
                        countDown.countDown()
                    })
            }
            countDown.await()
        }
        Truth.assertThat(interrupted.get()).isFalse()
        Truth.assertThat(sum.get()).isEqualTo(numTasks)
    }

    @Test
    @Throws(Exception::class)
    fun execute_allTasksAreExecuted_numTasksMoreThanNumWorkers() {
        val interrupted = AtomicBoolean(false)
        val sum = AtomicInteger(0)
        val numTasks: Int = PARALLELISM * 5
        WorkStealingThreadPoolExecutor(PARALLELISM, Thread.ofPlatform().factory()).use { executor ->
            val countDown = CountDownLatch(numTasks)
            for (i in 0..<numTasks) {
                executor.execute(
                    {
                        sum.incrementAndGet()
                        try {
                            Thread.sleep(1)
                        } catch (e: InterruptedException) {
                            interrupted.set(true)
                        }
                        countDown.countDown()
                    })
            }
            countDown.await()
        }
        Truth.assertThat(interrupted.get()).isFalse()
        Truth.assertThat(sum.get()).isEqualTo(numTasks)
    }

    @Test
    @Throws(Exception::class)
    fun execute_reachParallelism() {
        val interrupted = AtomicBoolean(false)
        val sum = AtomicInteger(0)
        val numBatches = 5
        WorkStealingThreadPoolExecutor(PARALLELISM, Thread.ofPlatform().factory()).use { executor ->
            for (i in 0..<numBatches) {
                val startedCountDown = CountDownLatch(PARALLELISM)
                val continueCountDown = CountDownLatch(1)
                for (j in 0..<PARALLELISM) {
                    executor.execute(
                        {
                            startedCountDown.countDown()
                            sum.incrementAndGet()

                            // Clear the interruption bit
                            val unused = Thread.interrupted()
                            try {
                                Thread.sleep(1)
                                continueCountDown.await()
                            } catch (e: InterruptedException) {
                                interrupted.set(true)
                            }

                            // Set the interruption bit to test that pool can continue scheduling tasks even if
                            // the task left the interruption bit set.
                            Thread.currentThread().interrupt()
                        })
                }
                startedCountDown.await()
                continueCountDown.countDown()
            }
        }
        Truth.assertThat(interrupted.get()).isFalse()
        Truth.assertThat(sum.get()).isEqualTo(numBatches * PARALLELISM)
    }

    @Test
    @Throws(Exception::class)
    fun execute_taskThrowsRuntimeException_reachParallelism() {
        val interrupted = AtomicBoolean(false)
        val numBatches = 5
        val uncaughtExceptionHandlerCountDown = CountDownLatch(numBatches * PARALLELISM)
        val errorFromUncaughtExceptionHandler = AtomicReference<Throwable?>()
        WorkStealingThreadPoolExecutor(PARALLELISM, Thread.ofPlatform().factory()).use { executor ->
            for (i in 0..<numBatches) {
                val startedCountDown = CountDownLatch(PARALLELISM)
                val continueCountDown = CountDownLatch(1)
                for (j in 0..<PARALLELISM) {
                    executor.execute(
                        {
                            val thread = Thread.currentThread()
                            thread.setUncaughtExceptionHandler(
                                Thread.UncaughtExceptionHandler { t: Thread?, e: Throwable? ->
                                    try {
                                        Truth.assertThat(t).isEqualTo(thread)
                                        Truth.assertThat(e).isInstanceOf(IllegalStateException::class.java)
                                        Truth.assertThat(e).hasMessageThat().isEqualTo("test")
                                    } catch (error: Throwable) {
                                        errorFromUncaughtExceptionHandler.set(error)
                                    } finally {
                                        uncaughtExceptionHandlerCountDown.countDown()
                                    }
                                })
                            startedCountDown.countDown()

                            try {
                                continueCountDown.await()
                            } catch (e: InterruptedException) {
                                interrupted.set(true)
                            }
                            throw IllegalStateException("test")
                        })
                }
                startedCountDown.await()
                continueCountDown.countDown()
            }
        }
        uncaughtExceptionHandlerCountDown.await()
        Truth.assertThat(interrupted.get()).isFalse()
        Truth.assertThat(errorFromUncaughtExceptionHandler.get()).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun shutdown_remainingTasksExecuted() {
        val numTasks: Int = PARALLELISM * 5
        val interrupted = AtomicBoolean(false)
        val numExecuted = AtomicInteger(0)
        val executor: WorkStealingThreadPoolExecutor =
            WorkStealingThreadPoolExecutor(PARALLELISM, Thread.ofPlatform().factory())
        val shutdownCalled = CountDownLatch(1)
        for (i in 0..<numTasks) {
            executor.execute(
                {
                    try {
                        shutdownCalled.await()
                        numExecuted.incrementAndGet()
                    } catch (e: InterruptedException) {
                        interrupted.set(true)
                    }
                })
        }

        executor.shutdown()
        shutdownCalled.countDown()

        assertThat(executor.isShutdown()).isTrue()
        Assert.assertThrows<RejectedExecutionException?>(
            RejectedExecutionException::class.java,
            ThrowingRunnable { executor.execute({}) })

        val terminated: Boolean = executor.awaitTermination(1L, TimeUnit.DAYS)
        Truth.assertThat(terminated).isTrue()
        assertThat(executor.isTerminated()).isTrue()
        Truth.assertThat(interrupted.get()).isFalse()
        Truth.assertThat(numExecuted.get()).isEqualTo(numTasks)
    }

    @Test
    @Throws(Exception::class)
    fun shutdownNow_interruptTasks() {
        val numTasks: Int = PARALLELISM * 5
        val numExecuted = AtomicInteger(0)
        val executor: WorkStealingThreadPoolExecutor =
            WorkStealingThreadPoolExecutor(PARALLELISM, Thread.ofPlatform().factory())
        val neverAwake = CountDownLatch(1)
        val startedCountDown = CountDownLatch(PARALLELISM)
        for (i in 0..<numTasks) {
            executor.execute(
                {
                    try {
                        startedCountDown.countDown()
                        neverAwake.await()
                        numExecuted.incrementAndGet()
                    } catch (e: InterruptedException) {
                        // Intentionally ignored
                    }
                })
        }

        startedCountDown.await()
        val remainingTasks: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            executor.shutdownNow()
        assertThat(remainingTasks).hasSize(numTasks - PARALLELISM)

        assertThat(executor.isShutdown()).isTrue()
        Assert.assertThrows<RejectedExecutionException?>(
            RejectedExecutionException::class.java,
            ThrowingRunnable { executor.execute({}) })

        val terminated: Boolean = executor.awaitTermination(1L, TimeUnit.DAYS)
        Truth.assertThat(terminated).isTrue()
        assertThat(executor.isTerminated()).isTrue()
        Truth.assertThat(numExecuted.get()).isEqualTo(0)
    }

    companion object {
        private const val PARALLELISM = 100
    }
}
