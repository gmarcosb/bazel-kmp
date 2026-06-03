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
package com.google.devtools.build.lib.concurrent

import com.google.common.collect.Sets
import com.google.common.util.concurrent.SettableFuture
import com.google.common.util.concurrent.Uninterruptibles
import com.google.devtools.build.lib.concurrent.AbstractQueueVisitor.ExceptionHandlingMode
import com.google.devtools.build.lib.testutil.TestUtils
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import kotlin.collections.ArrayList
import kotlin.collections.MutableList

/**
 * Tests for AbstractQueueVisitor.
 */
@RunWith(JUnit4::class)
class AbstractQueueVisitorTest {
    @Test
    @Throws(Exception::class)
    fun simpleCounter() {
        val counter = CountingQueueVisitor()
        counter.enqueue()
        counter.awaitQuiescence( /*interruptWorkers=*/false)
        Truth.assertThat(counter.count).isSameInstanceAs(10)
    }

    @Test
    @Throws(Exception::class)
    fun externalDep() {
        val future = SettableFuture.create<Any?>()
        val counter: AbstractQueueVisitor =
            AbstractQueueVisitor( /* parallelism= */
                2,  /* keepAliveTime= */
                3L,
                TimeUnit.SECONDS,
                ExceptionHandlingMode.FAIL_FAST,
                "FOO-BAR",
                ErrorClassifier.DEFAULT
            )
        counter.dependOnFuture(future)
        Thread(
            Runnable {
                try {
                    Thread.sleep(5)
                    future.set(Any())
                } catch (e: InterruptedException) {
                    throw RuntimeException(e)
                }
            })
            .start()
        counter.awaitQuiescence( /*interruptWorkers=*/false)
    }

    @Test
    @Throws(Exception::class)
    fun externalDepWithInterrupt() {
        val future = SettableFuture.create<Any?>()
        val counter: AbstractQueueVisitor =
            AbstractQueueVisitor( /* parallelism= */
                2,  /* keepAliveTime= */
                3L,
                TimeUnit.SECONDS,
                ExceptionHandlingMode.FAIL_FAST,
                "FOO-BAR",
                ErrorClassifier.DEFAULT
            )
        counter.dependOnFuture(future)
        Thread.currentThread().interrupt()
        Assert.assertThrows<InterruptedException?>(
            InterruptedException::class.java, ThrowingRunnable { counter.awaitQuiescence( /*interruptWorkers=*/true) })
        Truth.assertThat(future.isCancelled()).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun callerOwnedPool() {
        val executor: ThreadPoolExecutor = ThreadPoolExecutor(
            5, 5, 0, TimeUnit.SECONDS,
            LinkedBlockingQueue<Runnable?>()
        )
        Truth.assertThat(executor.getActiveCount()).isSameInstanceAs(0)

        val counter = CountingQueueVisitor(executor)
        counter.enqueue()
        counter.awaitQuiescence( /*interruptWorkers=*/false)
        Truth.assertThat(counter.count).isSameInstanceAs(10)

        executor.shutdown()
        Truth.assertThat(executor.awaitTermination(TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            .isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun doubleCounter() {
        val counter = CountingQueueVisitor()
        counter.enqueue()
        counter.enqueue()
        counter.awaitQuiescence( /*interruptWorkers=*/false)
        Truth.assertThat(counter.count).isSameInstanceAs(10)
    }

    @Test
    fun exceptionFromWorkerThread() {
        val myException: RuntimeException = IllegalStateException()
        val visitor = ConcreteQueueVisitor()
        visitor.execute(
            object : Runnable {
                override fun run() {
                    throw myException
                }
            })

        // The exception from the worker thread should be re-thrown from the main thread.
        val e =
            Assert.assertThrows<Exception?>(
                Exception::class.java,
                ThrowingRunnable { visitor.awaitQuiescence( /*interruptWorkers=*/false) })
        Truth.assertThat(e).isSameInstanceAs(myException)
    }

    // Regression test for "AbstractQueueVisitor loses track of jobs if thread allocation fails".
    @Test
    @Throws(Exception::class)
    fun threadPoolThrowsSometimes() {
        // In certain cases (for example, if the address space is almost entirely consumed by a huge
        // JVM heap), thread allocation can fail with an OutOfMemoryError. If the queue visitor
        // does not handle this gracefully, we lose track of tasks and hang the visitor indefinitely.

        val executor: ThreadPoolExecutor = object : ThreadPoolExecutor(
            3, 3, 0, TimeUnit.SECONDS,
            LinkedBlockingQueue<Runnable?>()
        ) {
            private val count: AtomicLong = AtomicLong()

            override fun execute(command: Runnable?) {
                val count: Long = this.count.incrementAndGet()
                if (count == 6L) {
                    throw Error("Could not create thread (fakeout)")
                }
                super.execute(command)
            }
        }

        val counter = CountingQueueVisitor(executor)
        counter.enqueue()
        val expected =
            Assert.assertThrows<Error?>(
                Error::class.java,
                ThrowingRunnable { counter.awaitQuiescence( /*interruptWorkers=*/false) })
        Truth.assertThat(expected).hasMessageThat().isEqualTo("Could not create thread (fakeout)")
        Truth.assertThat(counter.count).isSameInstanceAs(5)

        executor.shutdown()
        Truth.assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
    }

    // Regression test to make sure that AbstractQueueVisitor doesn't swallow unchecked exceptions if
    // it is interrupted concurrently with the unchecked exception being thrown.
    @Test
    @Throws(Exception::class)
    fun interruptAndThrownIsInterruptedAndThrown() {
        val visitor = ConcreteQueueVisitor()
        // Use a latch to make sure the thread gets a chance to start.
        val threadStarted: CountDownLatch = CountDownLatch(1)
        visitor.execute(
            object : Runnable {
                override fun run() {
                    threadStarted.countDown()
                    Truth.assertThat(
                        Uninterruptibles.awaitUninterruptibly(
                            visitor.getInterruptionLatchForTestingOnly(), 2, TimeUnit.SECONDS
                        )
                    )
                        .isTrue()
                    throw THROWABLE
                }
            })
        Truth.assertThat(threadStarted.await(TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
        // Interrupt will not be processed until work starts.
        Thread.currentThread().interrupt()
        val e =
            Assert.assertThrows<Exception?>(
                Exception::class.java,
                ThrowingRunnable { visitor.awaitQuiescence( /*interruptWorkers=*/true) })
        Truth.assertThat(e).isEqualTo(THROWABLE)
        Truth.assertThat(Thread.interrupted()).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun interruptionWithoutInterruptingWorkers() {
        val mainThread = Thread.currentThread()
        val latch1: CountDownLatch = CountDownLatch(1)
        val latch2: CountDownLatch = CountDownLatch(1)
        val workerThreadCompleted = booleanArrayOf(false)
        val visitor = ConcreteQueueVisitor()

        visitor.execute(
            object : Runnable {
                override fun run() {
                    try {
                        latch1.countDown()
                        latch2.await()
                        workerThreadCompleted[0] = true
                    } catch (e: InterruptedException) {
                        // Do not set workerThreadCompleted to true
                    }
                }
            })

        val interrupterThread: TestThread =
            TestThread(
                TestRunnable {
                    latch1.await()
                    mainThread.interrupt()
                    assertThat(
                        visitor
                            .getInterruptionLatchForTestingOnly()
                            .await(TestUtils.WAIT_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS)
                    )
                        .isTrue()
                    latch2.countDown()
                })
        interrupterThread.start()

        Assert.assertThrows<InterruptedException?>(
            InterruptedException::class.java, ThrowingRunnable { visitor.awaitQuiescence( /*interruptWorkers=*/false) })

        interrupterThread.joinAndAssertState(400)
        Truth.assertThat(workerThreadCompleted[0]).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun interruptionWithInterruptingWorkers() {
        assertInterruptWorkers(null)

        val executor: ThreadPoolExecutor = ThreadPoolExecutor(
            3, 3, 0, TimeUnit.SECONDS,
            LinkedBlockingQueue<Runnable?>()
        )
        assertInterruptWorkers(executor)
        executor.shutdown()
        executor.awaitTermination(TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    @Test
    @Throws(Exception::class)
    fun failFast() {
        // In failFast mode, we only run actions queued before the exception.
        assertFailFast(null, ExceptionHandlingMode.FAIL_FAST, false, "a", "b")

        // In !failFast mode, we complete all queued actions.
        assertFailFast(null, ExceptionHandlingMode.KEEP_GOING, false, "a", "b", "1", "2")

        // Now check fail-fast on interrupt:
        assertFailFast(null, ExceptionHandlingMode.KEEP_GOING, true, "a", "b")
    }

    @Test
    @Throws(Exception::class)
    fun failFastNoShutdown() {
        val executor: ThreadPoolExecutor = ThreadPoolExecutor(
            5, 5, 0, TimeUnit.SECONDS,
            LinkedBlockingQueue<Runnable?>()
        )
        // In failFast mode, we only run actions queued before the exception.
        assertFailFast(executor, ExceptionHandlingMode.FAIL_FAST, false, "a", "b")

        // In !failFast mode, we complete all queued actions.
        assertFailFast(executor, ExceptionHandlingMode.KEEP_GOING, false, "a", "b", "1", "2")

        // Now check fail-fast on interrupt:
        assertFailFast(executor, ExceptionHandlingMode.KEEP_GOING, true, "a", "b")

        executor.shutdown()
        Truth.assertThat(executor.awaitTermination(TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            .isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun jobIsInterruptedWhenOtherFails() {
        val executor: ThreadPoolExecutor = ThreadPoolExecutor(
            3, 3, 0, TimeUnit.SECONDS,
            LinkedBlockingQueue<Runnable?>()
        )

        val visitor: AbstractQueueVisitor =
            createQueueVisitorWithConstantErrorClassification(executor, ErrorClassification.CRITICAL)
        val latch1: CountDownLatch = CountDownLatch(1)
        val wasInterrupted: AtomicBoolean = AtomicBoolean(false)

        val r1: Runnable = object : Runnable {
            override fun run() {
                latch1.countDown()
                try {
                    // Interruption is expected during a sleep. There is no sense in fail or assert call
                    // because exception is going to be swallowed inside AbstractQueueVisitor.
                    // We are using wasInterrupted flag to assert in the end of test.
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    wasInterrupted.set(true)
                }
            }
        }

        visitor.execute(r1)
        latch1.await()
        visitor.execute(throwingRunnable())
        val exnLatch: CountDownLatch = visitor.getExceptionLatchForTestingOnly()

        val e =
            Assert.assertThrows<Exception?>(
                Exception::class.java,
                ThrowingRunnable { visitor.awaitQuiescence( /*interruptWorkers=*/true) })
        Truth.assertThat(e).isSameInstanceAs(THROWABLE)

        Truth.assertThat(wasInterrupted.get()).isTrue()
        Truth.assertThat(executor.isShutdown()).isTrue()
        Truth.assertThat(exnLatch.await(0, TimeUnit.MILLISECONDS)).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun javaErrorConsideredCriticalNoMatterWhat() {
        val executor: ThreadPoolExecutor = ThreadPoolExecutor(
            2, 2, 0, TimeUnit.SECONDS,
            LinkedBlockingQueue<Runnable?>()
        )
        val error = Error("bad!")
        val visitor: AbstractQueueVisitor =
            createQueueVisitorWithConstantErrorClassification(
                executor, ErrorClassification.NOT_CRITICAL
            )
        val latch: CountDownLatch = CountDownLatch(1)
        val sleepFinished: AtomicBoolean = AtomicBoolean(false)
        val sleepInterrupted: AtomicBoolean = AtomicBoolean(false)
        val errorRunnable: Runnable = object : Runnable {
            override fun run() {
                try {
                    latch.await(TestUtils.WAIT_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS)
                } catch (expected: InterruptedException) {
                    // Should only happen if the test itself is interrupted.
                }
                throw error
            }
        }
        val sleepRunnable: Runnable = object : Runnable {
            override fun run() {
                latch.countDown()
                try {
                    Thread.sleep(TestUtils.WAIT_TIMEOUT_MILLISECONDS)
                    sleepFinished.set(true)
                } catch (unexpected: InterruptedException) {
                    sleepInterrupted.set(true)
                }
            }
        }
        val exnLatch: CountDownLatch = visitor.getExceptionLatchForTestingOnly()
        visitor.execute(errorRunnable)
        visitor.execute(sleepRunnable)
        var thrownError: Error? = null
        // Interrupt workers on a critical error. That way we can test that visitor.work doesn't wait
        // for all workers to finish if one of them already had a critical error.
        try {
            visitor.awaitQuiescence( /*interruptWorkers=*/true)
        } catch (e: Error) {
            thrownError = e
        }
        Truth.assertThat(sleepInterrupted.get()).isTrue()
        Truth.assertThat(sleepFinished.get()).isFalse()
        Truth.assertThat(thrownError).isEqualTo(error)
        Truth.assertThat(exnLatch.await(0, TimeUnit.MILLISECONDS)).isTrue()
    }

    private class ClassifiedException(classification: ErrorClassification?) : RuntimeException() {
        private val classification: ErrorClassification?

        init {
            this.classification = classification
        }
    }

    @Test
    @Throws(Exception::class)
    fun mostSevereErrorPropagated() {
        val executor: ThreadPoolExecutor = ThreadPoolExecutor(
            2, 2, 0, TimeUnit.SECONDS,
            LinkedBlockingQueue<Runnable?>()
        )
        val criticalException =
            ClassifiedException(ErrorClassification.CRITICAL)
        val criticalAndLogException =
            ClassifiedException(ErrorClassification.CRITICAL_AND_LOG)
        val errorClassifier: ErrorClassifier = object : ErrorClassifier() {
            protected override fun classifyException(e: Exception?): ErrorClassification? {
                return if (e is ClassifiedException)
                    e.classification
                else
                    ErrorClassification.NOT_CRITICAL
            }
        }
        val visitor: AbstractQueueVisitor =
            AbstractQueueVisitor(
                executor, ExecutorOwnership.PRIVATE, ExceptionHandlingMode.KEEP_GOING, errorClassifier
            )
        val exnLatch: CountDownLatch = visitor.getExceptionLatchForTestingOnly()
        val criticalExceptionRunnable: Runnable = object : Runnable {
            override fun run() {
                throw criticalException
            }
        }
        val criticalAndLogExceptionRunnable: Runnable = object : Runnable {
            override fun run() {
                // Wait for the critical exception to be thrown. There's a benign race between our 'await'
                // call completing because the exception latch was counted down, and our thread being
                // interrupted by AbstractQueueVisitor because the critical error was encountered. This is
                // completely fine; all that matters is that we have a chance to throw our error _after_
                // the previous one was thrown by the other Runnable.
                try {
                    exnLatch.await()
                } catch (e: InterruptedException) {
                    // Ignored.
                }
                throw criticalAndLogException
            }
        }
        visitor.execute(criticalExceptionRunnable)
        visitor.execute(criticalAndLogExceptionRunnable)
        var exn: ClassifiedException? = null
        try {
            visitor.awaitQuiescence( /*interruptWorkers=*/true)
        } catch (e: ClassifiedException) {
            exn = e
        }
        Truth.assertThat(exn).isEqualTo(criticalAndLogException)
    }

    private class CountingQueueVisitor : AbstractQueueVisitor {
        var count: Int = 0
            private set
        private val lock = Any()

        constructor() : super( /* parallelism= */
            5,  /* keepAliveTime= */
            3L,
            TimeUnit.SECONDS,
            ExceptionHandlingMode.KEEP_GOING,
            THREAD_NAME,
            ErrorClassifier.DEFAULT
        )

        internal constructor(executor: ThreadPoolExecutor?) : super(
            executor,
            ExecutorOwnership.SHARED,
            ExceptionHandlingMode.FAIL_FAST,
            ErrorClassifier.DEFAULT
        )

        fun enqueue() {
            super.execute(
                object : Runnable {
                    override fun run() {
                        synchronized(lock) {
                            if (this.count < 10) {
                                this.count++
                                enqueue()
                            }
                        }
                    }
                })
        }

        companion object {
            private const val THREAD_NAME = "BlazeTest CountingQueueVisitor"
        }
    }

    private class ConcreteQueueVisitor : AbstractQueueVisitor {
        internal constructor() : super(
            5,
            3L,
            TimeUnit.SECONDS,
            ExceptionHandlingMode.KEEP_GOING,
            THREAD_NAME,
            ErrorClassifier.DEFAULT
        )

        internal constructor(exceptionHandlingMode: ExceptionHandlingMode?) : super(
            5,
            3L,
            TimeUnit.SECONDS,
            exceptionHandlingMode,
            THREAD_NAME,
            ErrorClassifier.DEFAULT
        )

        internal constructor(executor: ThreadPoolExecutor?, exceptionHandlingMode: ExceptionHandlingMode?) : super(
            executor,
            ExecutorOwnership.SHARED,
            exceptionHandlingMode,
            ErrorClassifier.DEFAULT
        )

        companion object {
            private const val THREAD_NAME = "BlazeTest ConcreteQueueVisitor"
        }
    }

    companion object {
        private val THROWABLE = RuntimeException()

        @Throws(Exception::class)
        private fun assertInterruptWorkers(executor: ThreadPoolExecutor?) {
            val latch1: CountDownLatch = CountDownLatch(1)
            val latch2: CountDownLatch = CountDownLatch(1)
            val workerThreadInterrupted = booleanArrayOf(false)
            val visitor =
                if (executor == null)
                    ConcreteQueueVisitor()
                else
                    ConcreteQueueVisitor(executor, ExceptionHandlingMode.FAIL_FAST)

            visitor.execute(
                object : Runnable {
                    override fun run() {
                        try {
                            latch1.countDown()
                            latch2.await()
                        } catch (e: InterruptedException) {
                            workerThreadInterrupted[0] = true
                        }
                    }
                })

            latch1.await()
            Thread.currentThread().interrupt()

            Assert.assertThrows<InterruptedException?>(
                InterruptedException::class.java,
                ThrowingRunnable { visitor.awaitQuiescence( /*interruptWorkers=*/true) })

            Truth.assertThat(workerThreadInterrupted[0]).isTrue()
        }

        @Throws(Exception::class)
        private fun assertFailFast(
            executor: ThreadPoolExecutor?,
            exceptionHandlingMode: ExceptionHandlingMode?,
            interrupt: Boolean,
            vararg expectedVisited: String?
        ) {
            Truth.assertThat(executor == null || !executor.isShutdown()).isTrue()
            val visitor: AbstractQueueVisitor =
                if (executor == null)
                    ConcreteQueueVisitor(exceptionHandlingMode)
                else
                    ConcreteQueueVisitor(executor, exceptionHandlingMode)

            val visitedList: MutableList<String?> = Collections.synchronizedList<String?>(ArrayList<String?>())

            // Runnable "ra" will await the uncaught exception from
            // "throwingRunnable", then add "a" to the list and
            // enqueue "r1". Runnable "r1" should be
            // executed iff !failFast.
            val latchA: CountDownLatch = CountDownLatch(1)
            val latchB: CountDownLatch = CountDownLatch(1)

            val r1: Runnable = awaitAddAndEnqueueRunnable(interrupt, visitor, null, visitedList, "1", null)
            val r2: Runnable = awaitAddAndEnqueueRunnable(interrupt, visitor, null, visitedList, "2", null)
            val ra: Runnable = awaitAddAndEnqueueRunnable(interrupt, visitor, latchA, visitedList, "a", r1)
            val rb: Runnable = awaitAddAndEnqueueRunnable(interrupt, visitor, latchB, visitedList, "b", r2)

            visitor.execute(ra)
            visitor.execute(rb)
            latchA.await()
            latchB.await()
            visitor.execute(if (interrupt) interruptingRunnable(Thread.currentThread()) else throwingRunnable())

            val e =
                Assert.assertThrows<Exception?>(
                    Exception::class.java,
                    ThrowingRunnable { visitor.awaitQuiescence( /*interruptWorkers=*/false) })
            if (interrupt) {
                Truth.assertThat(e).isInstanceOf(InterruptedException::class.java)
            } else {
                Truth.assertThat(e).isSameInstanceAs(THROWABLE)
            }
            Truth.assertWithMessage("got: %s\nwant: %s", visitedList, expectedVisited.contentToString())
                .that(HashSet<String?>(visitedList))
                .isEqualTo(Sets.newHashSet<String?>(*expectedVisited))

            if (executor != null) {
                Truth.assertThat(executor.isShutdown()).isFalse()
                assertThat(visitor.getTaskCount()).isEqualTo(0)
            }
        }

        private fun throwingRunnable(): Runnable {
            return object : Runnable {
                override fun run() {
                    throw THROWABLE
                }
            }
        }

        private fun interruptingRunnable(thread: Thread): Runnable {
            return object : Runnable {
                override fun run() {
                    thread.interrupt()
                }
            }
        }

        private fun awaitAddAndEnqueueRunnable(
            interrupt: Boolean,
            visitor: AbstractQueueVisitor,
            started: CountDownLatch?,
            list: MutableList<String?>,
            toAdd: String?,
            toEnqueue: Runnable?
        ): Runnable {
            return object : Runnable {
                override fun run() {
                    if (started != null) {
                        started.countDown()
                    }

                    try {
                        assertThat(
                            if (interrupt)
                                visitor.getInterruptionLatchForTestingOnly().await(1, TimeUnit.MINUTES)
                            else
                                visitor.getExceptionLatchForTestingOnly().await(1, TimeUnit.MINUTES)
                        )
                            .isTrue()
                    } catch (e: InterruptedException) {
                        // Unexpected.
                        throw RuntimeException(e)
                    }
                    list.add(toAdd)
                    if (toEnqueue != null) {
                        visitor.execute(toEnqueue)
                    }
                }
            }
        }

        private fun createQueueVisitorWithConstantErrorClassification(
            executor: ThreadPoolExecutor?, classification: ErrorClassification
        ): AbstractQueueVisitor {
            return AbstractQueueVisitor(
                executor,
                ExecutorOwnership.PRIVATE,
                ExceptionHandlingMode.KEEP_GOING,
                object : ErrorClassifier() {
                    protected override fun classifyException(e: Exception?): ErrorClassification {
                        return classification
                    }
                })
        }
    }
}
