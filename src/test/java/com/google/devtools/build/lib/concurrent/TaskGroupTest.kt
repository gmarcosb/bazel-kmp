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

import com.google.devtools.build.lib.concurrent.TaskGroup.Joiners
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import kotlin.Any
import kotlin.Boolean
import kotlin.Exception
import kotlin.IllegalStateException
import kotlin.Int
import kotlin.NoSuchElementException
import kotlin.RuntimeException
import kotlin.Throwable
import kotlin.Unit

@RunWith(JUnit4::class)
class TaskGroupTest {
    @Test
    @Throws(Exception::class)
    fun allSuccessful_waitsForAllSubtasks() {
        val group: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            TaskGroup.open(Policies.allSuccessful(), Joiners.voidOrThrow())
        group.use {
            val subtask1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                group.fork(
                    {
                        Thread.sleep(100)
                        1
                    })
            val subtask2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                group.fork(
                    {
                        Thread.sleep(200)
                        2
                    })

            group.join()

            assertThat(subtask1.state()).isEqualTo(TaskGroup.Subtask.State.SUCCESS)
            assertThat(subtask1.get()).isEqualTo(1)
            assertThat(subtask2.state()).isEqualTo(TaskGroup.Subtask.State.SUCCESS)
            assertThat(subtask2.get()).isEqualTo(2)
        }
        assertThat(group.isCancelled()).isFalse()
    }

    @Test
    @Throws(Throwable::class)
    fun allSuccessful_anySubtaskFails_setErrorBeforeCanceling() {
        val subtask2Ready: CountDownLatch = CountDownLatch(1)
        val letSubtask1Fail: CountDownLatch = CountDownLatch(1)
        val latch: CountDownLatch = CountDownLatch(1)
        val joiner: VoidOrThrow = VoidOrThrow()
        val assertErrorRef: AtomicReference<Throwable?> = AtomicReference<Throwable?>(null)
        val policy: TaskGroup.Policy<Any?>? =
            object : Policy<Any?>() {
                public override fun onComplete(subtask: Subtask<out Any?>): Boolean {
                    if (subtask.state() === Subtask.State.FAILED) {
                        // Assert that the joiner has the error from subtask1 before we decide to cancel the
                        // group.
                        try {
                            assertThat(joiner.getError()).isInstanceOf(RuntimeException::class.java)
                            assertThat(joiner.getError()).hasMessageThat().isEqualTo("test")
                        } catch (e2: Throwable) {
                            assertErrorRef.set(e2)
                        }
                        return true
                    }
                    return false
                }
            }
        TaskGroup.open(policy, joiner).use { group ->
            group.fork(
                {
                    letSubtask1Fail.await()
                    throw RuntimeException("test")
                })
            group.fork(
                {
                    subtask2Ready.countDown()
                    latch.await()
                    2
                })

            subtask2Ready.await()
            letSubtask1Fail.countDown()
            Assert.assertThrows<ExecutionException?>(ExecutionException::class.java, ThrowingRunnable { group.join() })
        }
        val assertError: Throwable? = assertErrorRef.get()
        if (assertError != null) {
            throw assertError
        }
    }

    @Test
    @Throws(Exception::class)
    fun allSuccessful_anySubtaskFails_cancelsOthersAndThrows() {
        val subtask2Ready: CountDownLatch = CountDownLatch(1)
        val letSubtask1Fail: CountDownLatch = CountDownLatch(1)
        val latch: CountDownLatch = CountDownLatch(1)
        TaskGroup.open(Policies.allSuccessful(), Joiners.voidOrThrow()).use { group ->
            val subtask1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                group.fork(
                    {
                        letSubtask1Fail.await()
                        throw RuntimeException("test")
                    })
            val subtask2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                group.fork(
                    {
                        subtask2Ready.countDown()
                        latch.await()
                        2
                    })

            subtask2Ready.await()
            letSubtask1Fail.countDown()
            val e: ExecutionException? = Assert.assertThrows<ExecutionException?>(
                ExecutionException::class.java,
                ThrowingRunnable { group.join() })

            assertThat(group.isCancelled()).isTrue()
            assertThat(subtask1.state()).isEqualTo(TaskGroup.Subtask.State.FAILED)
            assertThat(subtask1.exception()).isInstanceOf(RuntimeException::class.java)
            assertThat(subtask1.exception()).hasMessageThat().isEqualTo("test")
            assertThat(subtask2.state()).isEqualTo(TaskGroup.Subtask.State.FAILED)
            assertThat(subtask2.exception()).isInstanceOf(InterruptedException::class.java)
            Truth.assertThat(e).hasCauseThat().isInstanceOf(RuntimeException::class.java)
            Truth.assertThat(e).hasCauseThat().hasMessageThat().isEqualTo("test")
        }
    }

    @Test
    @Throws(Exception::class)
    fun allSuccessful_interrupted_cancelsRunningSubtasks() {
        val latch: CountDownLatch = CountDownLatch(1)
        val subtask1Done: CountDownLatch = CountDownLatch(1)
        val subtask2Ready: CountDownLatch = CountDownLatch(1)
        val subtask3Ready: CountDownLatch = CountDownLatch(1)
        val groupRef: AtomicReference<TaskGroup<Any?, Void?>?> = AtomicReference<TaskGroup<Any?, Void?>?>(null)
        val subtask1Ref: AtomicReference<Subtask<Int?>?> = AtomicReference<Subtask<Int?>?>(null)
        val subtask2Ref: AtomicReference<Subtask<Int?>?> = AtomicReference<Subtask<Int?>?>(null)
        val subtask3Ref: AtomicReference<Subtask<Int?>?> = AtomicReference<Subtask<Int?>?>(null)
        val interrupted: AtomicBoolean = AtomicBoolean(false)
        val errorRef: AtomicReference<Throwable?> = AtomicReference<Throwable?>(null)
        val thread =
            Thread.ofPlatform()
                .start(
                    Runnable {
                        val group: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                            TaskGroup.open(Policies.allSuccessful(), Joiners.voidOrThrow())
                        groupRef.set(group)
                        try {
                            group.use {
                                val subtask1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                                    group.fork(
                                        {
                                            subtask1Done.countDown()
                                            1
                                        })
                                subtask1Ref.set(subtask1)
                                val subtask2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                                    group.fork(
                                        {
                                            subtask2Ready.countDown()
                                            latch.await()
                                            2
                                        })
                                subtask2Ref.set(subtask2)
                                val subtask3: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                                    group.fork(
                                        {
                                            subtask3Ready.countDown()
                                            latch.await()
                                            3
                                        })
                                subtask3Ref.set(subtask3)
                                try {
                                    group.join()
                                } catch (e: InterruptedException) {
                                    interrupted.set(true)
                                }
                            }
                        } catch (e: Throwable) {
                            errorRef.set(e)
                        }
                    })

        subtask1Done.await()
        subtask2Ready.await()
        subtask3Ready.await()
        thread.interrupt()
        thread.join()

        Truth.assertThat(interrupted.get()).isTrue()
        assertThat(groupRef.get().isCancelled()).isTrue()
        assertThat(subtask1Ref.get().state()).isEqualTo(Subtask.State.SUCCESS)
        assertThat(subtask1Ref.get().get()).isEqualTo(1)
        assertThat(subtask2Ref.get().state()).isEqualTo(Subtask.State.FAILED)
        assertThat(subtask2Ref.get().exception()).isInstanceOf(InterruptedException::class.java)
        assertThat(subtask3Ref.get().state()).isEqualTo(Subtask.State.FAILED)
        assertThat(subtask3Ref.get().exception()).isInstanceOf(InterruptedException::class.java)
        Truth.assertThat(errorRef.get()).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun anySuccessful_returnsFirstSuccessfulAndCancelsOthers() {
        val latch: CountDownLatch = CountDownLatch(1)
        TaskGroup.open(Policies.anySuccessful(), Joiners.anySuccessfulOrThrow()).use { group ->
            val subtask1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                group.fork(
                    {
                        Thread.sleep(100)
                        1
                    })
            val subtask2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                group.fork(
                    {
                        latch.await()
                        2
                    })
            val subtask3: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                group.fork(
                    {
                        latch.await()
                        3
                    })

            val result: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? = group.join()

            assertThat(group.isCancelled()).isTrue()
            assertThat(result).isEqualTo(1)
            assertThat(subtask1.state()).isEqualTo(TaskGroup.Subtask.State.SUCCESS)
            assertThat(subtask1.get()).isEqualTo(1)
            assertThat(subtask2.state()).isEqualTo(TaskGroup.Subtask.State.FAILED)
            assertThat(subtask2.exception()).isInstanceOf(InterruptedException::class.java)
            assertThat(subtask3.state()).isEqualTo(TaskGroup.Subtask.State.FAILED)
            assertThat(subtask3.exception()).isInstanceOf(InterruptedException::class.java)
        }
    }

    @Test
    fun anySuccessful_allSubtaskFails_throws() {
        TaskGroup.open(Policies.anySuccessful(), Joiners.voidOrThrow()).use { group ->
            val subtask1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                group.fork(
                    {
                        Thread.sleep(100)
                        throw RuntimeException("test1")
                    })
            val subtask2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                group.fork(
                    {
                        Thread.sleep(200)
                        throw RuntimeException("test2")
                    })

            val e: ExecutionException? = Assert.assertThrows<ExecutionException?>(
                ExecutionException::class.java,
                ThrowingRunnable { group.join() })

            assertThat(group.isCancelled()).isFalse()
            assertThat(subtask1.state()).isEqualTo(TaskGroup.Subtask.State.FAILED)
            assertThat(subtask1.exception()).isInstanceOf(RuntimeException::class.java)
            assertThat(subtask1.exception()).hasMessageThat().isEqualTo("test1")
            assertThat(subtask2.state()).isEqualTo(TaskGroup.Subtask.State.FAILED)
            assertThat(subtask2.exception()).isInstanceOf(RuntimeException::class.java)
            assertThat(subtask2.exception()).hasMessageThat().isEqualTo("test2")
            Truth.assertThat(e).hasCauseThat().isInstanceOf(RuntimeException::class.java)
            Truth.assertThat(e).hasCauseThat().hasMessageThat().contains("test")
        }
    }

    @Test
    fun anySuccessfulOrThrow_notForked_throws() {
        TaskGroup.open(Policies.anySuccessful(), Joiners.anySuccessfulOrThrow()).use { group ->
            val e: ExecutionException? = Assert.assertThrows<ExecutionException?>(
                ExecutionException::class.java,
                ThrowingRunnable { group.join() })
            Truth.assertThat(e).hasCauseThat().isInstanceOf(NoSuchElementException::class.java)
            Truth.assertThat(e).hasCauseThat().hasMessageThat().isEqualTo("No subtasks completed")
        }
    }

    @Test
    @Throws(Exception::class)
    fun fork_afterJoined_throws() {
        TaskGroup.open(Policies.allSuccessful(), Joiners.voidOrThrow()).use { group ->
            group.join()
            val e = Assert.assertThrows<IllegalStateException?>(
                IllegalStateException::class.java,
                ThrowingRunnable { group.fork({}) })
            Truth.assertThat(e).hasMessageThat().contains("Already joined or task group is closed")
        }
    }

    @Test
    @Throws(Exception::class)
    fun fork_fromDifferentThread_throws() {
        TaskGroup.open(Policies.allSuccessful(), Joiners.voidOrThrow()).use { group ->
            val errorRef: AtomicReference<Throwable?> = AtomicReference<Throwable?>(null)
            val thread =
                Thread.ofPlatform()
                    .start(
                        Runnable {
                            try {
                                group.fork({})
                            } catch (e: Throwable) {
                                errorRef.set(e)
                            }
                        })
            thread.join()
            val e: Throwable? = errorRef.get()
            Truth.assertThat(e).isNotNull()
            Truth.assertThat(e).hasMessageThat().contains("Current thread not owner")
        }
    }

    @Test
    @Throws(Exception::class)
    fun join_afterJoined_throws() {
        TaskGroup.open(Policies.allSuccessful(), Joiners.voidOrThrow()).use { group ->
            group.join()
            val e = Assert.assertThrows<IllegalStateException?>(
                IllegalStateException::class.java,
                ThrowingRunnable { group.join() })
            Truth.assertThat(e).hasMessageThat().contains("Already joined or task group is closed")
        }
    }

    @Test
    @Throws(Exception::class)
    fun join_fromDifferentThread_throws() {
        TaskGroup.open(Policies.allSuccessful(), Joiners.voidOrThrow()).use { group ->
            val errorRef: AtomicReference<Throwable?> = AtomicReference<Throwable?>(null)
            val thread =
                Thread.ofPlatform()
                    .start(
                        Runnable {
                            try {
                                group.join()
                            } catch (e: Throwable) {
                                errorRef.set(e)
                            }
                        })
            thread.join()
            val e: Throwable? = errorRef.get()
            Truth.assertThat(e).isNotNull()
            Truth.assertThat(e).hasMessageThat().contains("Current thread not owner")
        }
    }

    @Test
    fun close_notForkedAndNotJoined_doesNotThrow() {
        TaskGroup.open(Policies.allSuccessful(), Joiners.voidOrThrow()).use { group -> }
    }

    @Test
    fun close_forkedButNotJoined_throws() {
        val e =
            Assert.assertThrows<IllegalStateException?>(
                IllegalStateException::class.java,
                ThrowingRunnable {
                    TaskGroup.open(Policies.allSuccessful(), Joiners.voidOrThrow()).use { group ->
                        group.fork(
                            {
                                Thread.sleep(1)
                                1
                            })
                    }
                })
        Truth.assertThat(e).hasMessageThat().contains("Owner did not join after forking")
    }

    @Test
    @Throws(Exception::class)
    fun afterSubtaskCompleted_removesThreadFromSet() {
        val subtask1Ready: CountDownLatch = CountDownLatch(1)
        val letSubtask1Complete: CountDownLatch = CountDownLatch(1)
        val subtask2Ready: CountDownLatch = CountDownLatch(1)
        val letSubtask2Complete: CountDownLatch = CountDownLatch(1)
        TaskGroup.open(Policies.allSuccessful(), Joiners.voidOrThrow()).use { group ->
            group.fork(
                {
                    subtask1Ready.countDown()
                    letSubtask1Complete.await()
                    1
                })
            subtask1Ready.await()
            assertThat(group.getThreads()).hasSize(1)

            group.fork(
                {
                    subtask2Ready.countDown()
                    letSubtask2Complete.await()
                    2
                })
            subtask2Ready.await()
            assertThat(group.getThreads()).hasSize(2)

            letSubtask1Complete.countDown()
            letSubtask2Complete.countDown()

            group.join()
            assertThat(group.getThreads()).hasSize(0)
        }
    }

    @Test
    @Throws(Exception::class)
    fun joinOrThrow_checkedException_throwsIt() {
        TaskGroup.open(Policies.allSuccessful(), Joiners.voidOrThrow()).use { group ->
            group.fork(
                {
                    throw Exception("test")
                })
            val e = Assert.assertThrows<Exception?>(
                Exception::class.java,
                ThrowingRunnable { group.joinOrThrow(Exception::class.java) })
            Truth.assertThat(e).hasMessageThat().isEqualTo("test")
        }
    }

    @Test
    @Throws(Exception::class)
    fun joinOrThrow_runtimeException_throwsIt() {
        TaskGroup.open(Policies.allSuccessful(), Joiners.voidOrThrow()).use { group ->
            group.fork(
                {
                    throw RuntimeException("test")
                })
            val e = Assert.assertThrows<RuntimeException?>(RuntimeException::class.java, ThrowingRunnable {
                group.joinOrThrow(
                    Exception::class.java
                )
            })
            Truth.assertThat(e).hasMessageThat().isEqualTo("test")
        }
    }

    @Test
    @Throws(Exception::class)
    fun joinOrThrow_unexpectedCheckedException_throwsIllegalStateException() {
        class MyException1 : Exception()
        class MyException2 : Exception()

        TaskGroup.open(Policies.allSuccessful(), Joiners.voidOrThrow()).use { group ->
            group.fork(
                {
                    throw MyException1()
                })
            val e =
                Assert.assertThrows<IllegalStateException?>(
                    IllegalStateException::class.java,
                    ThrowingRunnable { group.joinOrThrow(MyException2::class.java) })
            Truth.assertThat(e).hasCauseThat().isInstanceOf(MyException1::class.java)
        }
    }

    @Test
    @Throws(Exception::class)
    fun close_forkedButNotJoined_cancelsSubtasksAndThrows() {
        val latch: CountDownLatch = CountDownLatch(1)
        val subtaskStarted: CountDownLatch = CountDownLatch(1)
        val interrupted: AtomicBoolean = AtomicBoolean(false)

        val e =
            Assert.assertThrows<IllegalStateException?>(
                IllegalStateException::class.java,
                ThrowingRunnable {
                    TaskGroup.open(Policies.allSuccessful(), Joiners.voidOrThrow()).use { group ->
                        group.fork(
                            {
                                subtaskStarted.countDown()
                                try {
                                    latch.await()
                                } catch (ex: InterruptedException) {
                                    interrupted.set(true)
                                }
                                1
                            })
                        subtaskStarted.await()
                    }
                })
        Truth.assertThat(e).hasMessageThat().contains("Owner did not join after forking")
        Truth.assertThat(interrupted.get()).isTrue()
    }
}
