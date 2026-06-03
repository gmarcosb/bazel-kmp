// Copyright 2018 The Bazel Authors. All rights reserved.
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
import com.google.devtools.build.lib.testutil.TestUtils
import org.junit.Test
import java.time.Duration

/** Tests for [CommandManager].  */
@RunWith(JUnit4::class)
class CommandManagerTest {
    @Test
    fun testBasicOperationsOnSingleThread() {
        val underTest: CommandManager =
            CommandManager( /*doIdleServerTasks=*/false, "slow interrupt message suffix")
        assertThat(underTest.isEmpty()).isTrue()
        underTest.createCommand().use { firstCommand ->
            assertThat(underTest.isEmpty()).isFalse()
            Truth.assertThat(isValidUuid(firstCommand.id)).isTrue()
            underTest.createCommand().use { secondCommand ->
                assertThat(underTest.isEmpty()).isFalse()
                Truth.assertThat(isValidUuid(secondCommand.id)).isTrue()
                assertThat(firstCommand.id).isNotEqualTo(secondCommand.id)
            }
            assertThat(underTest.isEmpty()).isFalse()
        }
        assertThat(underTest.isEmpty()).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun testNotifiesOnBusyAndIdle() {
        val notificationCounter: AtomicInteger = AtomicInteger(0)
        val underTest: CommandManager =
            CommandManager( /*doIdleServerTasks=*/false, "slow interrupt message suffix")
        val waiting: AtomicBoolean = AtomicBoolean(false)
        val cyclicBarrier: CyclicBarrier = CyclicBarrier(2)

        val thread: TestThread =
            TestThread(
                TestRunnable {
                    try {
                        while (true) {
                            waiting.set(true)
                            underTest.waitForChange()
                            waiting.set(false)
                            notificationCounter.incrementAndGet()
                            cyclicBarrier.await()
                        }
                    } catch (e: InterruptedException) {
                        // Used to terminate the thread.
                    }
                })
        thread.start()

        // We want to ensure at each step that we are actively awaiting notification.
        waitForThreadWaiting(waiting, thread)
        underTest.createCommand().use { firstCommand ->
            cyclicBarrier.await()
            Truth.assertThat(notificationCounter.get()).isEqualTo(1)
            waitForThreadWaiting(waiting, thread)
            underTest.createCommand().use { secondCommand ->
                cyclicBarrier.await()
                Truth.assertThat(notificationCounter.get()).isEqualTo(2)
                waitForThreadWaiting(waiting, thread)
            }
            cyclicBarrier.await()
            Truth.assertThat(notificationCounter.get()).isEqualTo(3)
            waitForThreadWaiting(waiting, thread)
        }
        cyclicBarrier.await()
        Truth.assertThat(notificationCounter.get()).isEqualTo(4)

        thread.interrupt()
        thread.joinAndAssertState(TestUtils.WAIT_TIMEOUT_MILLISECONDS)
    }

    @Test
    @Throws(Exception::class)
    fun testIdleTasksEnabled() {
        val underTest: CommandManager =
            CommandManager( /* doIdleServerTasks= */true, "slow interrupt message suffix")

        val taskRunning: CountDownLatch = CountDownLatch(1)

        val idleTask: IdleTask =
            object : IdleTask() {
                override fun displayName(): String {
                    return "my idle task"
                }

                override fun run() {
                    taskRunning.countDown()
                }
            }

        underTest.createCommand().use { c1 ->
            assertThat(underTest.getIdleTaskResults()).isNull()
            c1.setIdleTasks(ImmutableList.of<E?>(idleTask))
        }
        taskRunning.await()

        underTest.createCommand().use { c2 -> }
        underTest.createCommand().use { c3 ->
            assertThat(
                underTest.getIdleTaskResults().stream()
                    .map({ r -> IdleTask.Result(r.name, r.status, Duration.ZERO) })
            )
                .containsExactly(
                    IdleTask.Result("my idle task", IdleTask.Status.SUCCESS, Duration.ZERO)
                )
        }
        underTest.createCommand().use { c4 ->
            assertThat(underTest.getIdleTaskResults()).isNull()
        }
    }

    @Test
    @Throws(Exception::class)
    fun testIdleTasksDisabled() {
        val underTest: CommandManager =
            CommandManager( /* doIdleServerTasks= */false, "slow interrupt message suffix")

        val idleTask: IdleTask =
            object : IdleTask() {
                override fun displayName(): String {
                    return "my idle task"
                }

                override fun run() {}
            }

        underTest.createCommand().use { c1 ->
            c1.setIdleTasks(ImmutableList.of<E?>(idleTask))
        }
        underTest.createCommand().use { c2 ->
            assertThat(underTest.getIdleTaskResults()).isNull()
        }
    }

    companion object {
        @Throws(InterruptedException::class)
        private fun waitForThreadWaiting(readyToWaitForChange: AtomicBoolean, thread: Thread) {
            while (!(readyToWaitForChange.get() && thread.getState() == Thread.State.WAITING)) {
                Thread.sleep(50)
            }
        }

        private fun isValidUuid(uuidString: String): Boolean {
            try {
                val unused: UUID = UUID.fromString(uuidString)
                return true
            } catch (e: IllegalArgumentException) {
                return false
            }
        }
    }
}
