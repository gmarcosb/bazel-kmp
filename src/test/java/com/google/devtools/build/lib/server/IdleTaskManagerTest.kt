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
package com.google.devtools.build.lib.server

import com.google.common.collect.ImmutableList
import com.google.common.truth.Truth
import com.google.common.util.concurrent.Uninterruptibles
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** Test for [IdleTaskManager].  */
@RunWith(JUnit4::class)
class IdleTaskManagerTest {
    @Test
    @Throws(Exception::class)
    fun registeredTask_taskSuccessful() {
        val taskRunning = CountDownLatch(1)
        val taskDone = AtomicBoolean()
        val task: IdleTask =
            makeTask(
                "task",
                IdleTaskRunnable {
                    taskRunning.countDown()
                    Uninterruptibles.sleepUninterruptibly(Duration.ofMillis(200))
                    taskDone.set(true)
                })
        val manager: IdleTaskManager = IdleTaskManager(ImmutableList.of<E?>(task))

        manager.idle()
        taskRunning.await() // wait for task to start
        val stats: ImmutableList<IdleTask.Result?> = manager.busy()

        Truth.assertThat(taskDone.get()).isTrue()

        Truth.assertThat(
            stats.stream()
                .map<IdleTask.Result?> { s: IdleTask.Result? -> IdleTask.Result(s!!.name, s.status, Duration.ZERO) })
            .containsExactly(IdleTask.Result("task", IdleTask.Status.SUCCESS, Duration.ZERO))
    }

    @Test
    @Throws(Exception::class)
    fun registeredTask_taskFailed() {
        val taskRunning = CountDownLatch(1)
        val taskDone = AtomicBoolean()
        val task: IdleTask =
            makeTask(
                "task",
                IdleTaskRunnable {
                    taskRunning.countDown()
                    Uninterruptibles.sleepUninterruptibly(Duration.ofMillis(200))
                    try {
                        throw IdleTaskException(RuntimeException("failed"))
                    } finally {
                        taskDone.set(true)
                    }
                })
        val manager: IdleTaskManager = IdleTaskManager(ImmutableList.of<E?>(task))

        manager.idle()
        taskRunning.await() // wait for task to start
        val stats: ImmutableList<IdleTask.Result?> = manager.busy()

        Truth.assertThat(taskDone.get()).isTrue()

        Truth.assertThat(
            stats.stream()
                .map<IdleTask.Result?> { s: IdleTask.Result? -> IdleTask.Result(s!!.name, s.status, Duration.ZERO) })
            .containsExactly(IdleTask.Result("task", IdleTask.Status.FAILURE, Duration.ZERO))
    }

    @Test
    @Throws(Exception::class)
    fun registeredTask_taskInterrupted() {
        val taskRunning = CountDownLatch(1)
        val taskInterrupted = AtomicBoolean()
        val task: IdleTask =
            makeTask(
                "task",
                IdleTaskRunnable {
                    taskRunning.countDown()
                    try {
                        Thread.sleep(Duration.ofDays(1))
                    } catch (e: InterruptedException) {
                        taskInterrupted.set(true)
                        throw e
                    }
                })
        val manager: IdleTaskManager = IdleTaskManager(ImmutableList.of<E?>(task))

        manager.idle()
        taskRunning.await() // wait for task to start
        val stats: ImmutableList<IdleTask.Result?> = manager.busy()

        Truth.assertThat(taskInterrupted.get()).isTrue()

        Truth.assertThat(
            stats.stream()
                .map<IdleTask.Result?> { s: IdleTask.Result? -> IdleTask.Result(s!!.name, s.status, Duration.ZERO) })
            .containsExactly(IdleTask.Result("task", IdleTask.Status.INTERRUPTED, Duration.ZERO))
    }

    @Test
    @Throws(Exception::class)
    fun registeredTask_taskNotStarted() {
        val taskStarted = AtomicBoolean()
        val task: IdleTask = makeTask("task", Duration.ofDays(1), IdleTaskRunnable { taskStarted.set(true) })
        val manager: IdleTaskManager = IdleTaskManager(ImmutableList.of<E?>(task))

        manager.idle()
        Thread.sleep(Duration.ofMillis(200)) // make it more likely that a bug will be caught
        val stats: ImmutableList<IdleTask.Result?>? = manager.busy()

        Truth.assertThat(taskStarted.get()).isFalse()

        Truth.assertThat(stats)
            .containsExactly(IdleTask.Result("task", IdleTask.Status.NOT_STARTED, Duration.ZERO))
    }

    @Test
    @Throws(Exception::class)
    fun registeredTask_multipleTasks() {
        val taskRunning = AtomicBoolean(false)
        val concurrentTasksDetected = AtomicBoolean(false)
        val finishedTasks = CountDownLatch(3)

        val tasks =
            ImmutableList.of<IdleTask?>(
                makeTask("a", IdleTaskRunnable { runTask(taskRunning, concurrentTasksDetected, finishedTasks) }),
                makeTask("b", IdleTaskRunnable { runTask(taskRunning, concurrentTasksDetected, finishedTasks) }),
                makeTask("c", IdleTaskRunnable { runTask(taskRunning, concurrentTasksDetected, finishedTasks) })
            )

        val manager: IdleTaskManager = IdleTaskManager(tasks)

        manager.idle()
        finishedTasks.await()
        val stats: ImmutableList<IdleTask.Result?> = manager.busy()

        Truth.assertThat(concurrentTasksDetected.get()).isFalse()

        Truth.assertThat(
            stats.stream()
                .map<IdleTask.Result?> { s: IdleTask.Result? -> IdleTask.Result(s!!.name, s.status, Duration.ZERO) })
            .containsExactly(
                IdleTask.Result("a", IdleTask.Status.SUCCESS, Duration.ZERO),
                IdleTask.Result("b", IdleTask.Status.SUCCESS, Duration.ZERO),
                IdleTask.Result("c", IdleTask.Status.SUCCESS, Duration.ZERO)
            )
            .inOrder()
    }

    private interface IdleTaskRunnable {
        @Throws(IdleTaskException::class, InterruptedException::class)
        fun run()
    }

    companion object {
        @Throws(InterruptedException::class)
        private fun runTask(
            taskRunning: AtomicBoolean,
            concurrentTasksDetected: AtomicBoolean,
            finishedTasks: CountDownLatch
        ) {
            if (!taskRunning.compareAndSet(false, true)) {
                concurrentTasksDetected.set(true)
            }
            Thread.sleep(Duration.ofMillis(200)) // make it more likely that a bug will be caught
            finishedTasks.countDown()
            if (!taskRunning.compareAndSet(true, false)) {
                concurrentTasksDetected.set(true)
            }
        }

        private fun makeTask(name: String, runnable: IdleTaskRunnable): IdleTask {
            return makeTask(name, Duration.ZERO, runnable)
        }

        private fun makeTask(name: String, delay: Duration, runnable: IdleTaskRunnable): IdleTask {
            return object : IdleTask() {
                override fun displayName(): String {
                    return name
                }

                override fun delay(): Duration {
                    return delay
                }

                @Throws(IdleTaskException::class, InterruptedException::class)
                override fun run() {
                    runnable.run()
                }
            }
        }
    }
}
