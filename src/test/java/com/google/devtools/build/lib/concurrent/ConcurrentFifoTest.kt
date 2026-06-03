// Copyright 2023 The Bazel Authors. All rights reserved.
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
import com.google.devtools.build.lib.concurrent.ConcurrentFifo.CAPACITY
import com.google.devtools.build.lib.testutil.TestUtils
import org.junit.After
import org.junit.Assert
import org.junit.Test
import sun.misc.Unsafe

@RunWith(TestParameterInjector::class)
// TODO: b/359688989 - clean this up
class ConcurrentFifoTest {
    private val executor: ForkJoinPool = ForkJoinPool(PARALLELISM)

    private var baseAddress: Long = 0
    private var sizeAddress: Long = 0
    private var appendIndexAddress: Long = 0
    private var takeIndexAddress: Long = 0

    private var queue: ConcurrentFifo<Runnable?>? = null

    @Before
    fun setUp() {
        baseAddress = createPaddedBaseAddress( /* count= */3)
        sizeAddress = getAlignedAddress(baseAddress,  /* offset= */0)
        appendIndexAddress = getAlignedAddress(baseAddress,  /* offset= */1)
        takeIndexAddress = getAlignedAddress(baseAddress,  /* offset= */2)
        queue = ConcurrentFifo(Runnable::class.java, sizeAddress, appendIndexAddress, takeIndexAddress)
    }

    // TODO: b/386384684 - remove Unsafe usage
    @After
    fun freeMemory() {
        UNSAFE.freeMemory(baseAddress)
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Test
    fun queue_initializesAddresss() {
        Truth.assertThat(UNSAFE.getInt(sizeAddress)).isEqualTo(0)
        Truth.assertThat(UNSAFE.getInt(appendIndexAddress)).isEqualTo(0)
        Truth.assertThat(UNSAFE.getInt(takeIndexAddress)).isEqualTo(0)
    }

    /**
     * Sets the starting address to ensure certain corner cases are exercised.
     * 
     * 
     * The queue isn't sensitive to the starting address as long as append and take start at the
     * same value.
     */
    private enum class StartingAddressParameter(private val value: Int) {
        /** Does the queue work with default values?  */
        ZERO(0),

        /** Does the queue work when overflowing positive values?  */
        MAX_INT(Int.Companion.MAX_VALUE),

        /** Does the queue work when overflowing unsigned integers?  */
        ALL_ONES(-0x1); // -1.

        fun value(): Int {
            return value
        }
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Test
    @Throws(InterruptedException::class)
    fun queue_handlesConcurrentTasks(@TestParameter startingAddress: StartingAddressParameter) {
        UNSAFE.putInt(null, appendIndexAddress, startingAddress.value())
        UNSAFE.putInt(null, takeIndexAddress, startingAddress.value())

        // Count for the inner loop within each thread that performs queue operations. This is
        // deliberately higher than the queue capacity to cover multiple epochs.
        val inner: Int = CAPACITY + 1

        val untaken = Sets.newConcurrentHashSet<Runnable?>()

        val workerCount: Int = PARALLELISM / 2 // Workers are either producers or consumers.

        // Each worker performs `inner` operations making the total number of consumer operations
        // `workerCount * inner`.
        val consumersDone: CountDownLatch = CountDownLatch(workerCount * inner)
        val released: Semaphore = Semaphore(0)
        for (i in 0..<workerCount) {
            val index = i
            executor.execute(
                Runnable {
                    for (j in 0..<inner) {
                        val task = TaskWithId(index * inner + j)
                        untaken.add(task)
                        while (!queue.tryAppend(task)) {
                        }
                        released.release()
                    }
                })

            executor.execute(
                Runnable {
                    for (j in 0..<inner) {
                        try {
                            released.acquire()
                        } catch (e: InterruptedException) {
                            throw IllegalStateException(e)
                        }
                        val task: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                            queue.take()
                        if (!untaken.remove(task)) {
                            logger.atSevere().log("duplicate %s: %s\n", task, queue)
                        }
                        consumersDone.countDown()
                    }
                })
        }

        if (!consumersDone.await(TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            Assert.fail("timed out: " + queue)
        }
        Truth.assertThat(untaken).isEmpty()
    }

    @Test
    fun queue_restrictsCapacity() {
        for (i in 0..<CAPACITY - 1) {
            assertThat(queue.tryAppend(TaskWithId(i))).isTrue()
        }
        val task = TaskWithId(CAPACITY - 1)

        // With CAPACITY-1 tasks added, the queue is full and cannot support any more elements.
        assertThat(queue.size()).isEqualTo(CAPACITY - 1)
        assertThat(queue.tryAppend(task)).isFalse()

        val first = queue.take() as TaskWithId
        Truth.assertThat(first.id).isEqualTo(0)

        assertThat(queue.size()).isEqualTo(CAPACITY - 2)

        // After removing one task, the queue can accept another task again.
        assertThat(queue.tryAppend(task)).isTrue()
    }

    @Test
    fun queue_behavesAfterClear() {
        for (i in 0..<CAPACITY - 1) {
            assertThat(queue.tryAppend(TaskWithId(i))).isTrue()
        }
        assertThat(queue.size()).isEqualTo(CAPACITY - 1)

        queue.clear()
        assertThat(queue.size()).isEqualTo(0)

        // Fully loads then empties the queue.
        for (i in 0..<CAPACITY - 1) {
            assertThat(queue.tryAppend(TaskWithId(i + CAPACITY))).isTrue()
        }
        for (i in 0..<CAPACITY - 1) {
            Truth.assertThat((queue.take() as TaskWithId).id).isEqualTo(i + CAPACITY)
        }
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Test
    fun slowAppends_areSkippedByTake_thenUnmarkedByAppends() {
        // This test covers the state machine transitions that handle slow appenders observed by takers.
        // This test stacks two slow appends on the same offset, exposes them to take code then
        // "unwinds" it with real appends applied at those offsets. Descheduling threads is hard to
        // capture without mutilating the code so this fakes a lot of behavior.
        fakeSlowAppend()
        for (i in 0..<CAPACITY - 1) {
            assertThat(queue.tryAppend(TaskWithId(i))).isTrue()
            Truth.assertThat((queue.take() as TaskWithId).id).isEqualTo(i)
        }
        // The slow append has a skip marker.
        assertThat(queue.getQueueForTesting()[0]).isEqualTo(1)

        // Fakes a 2nd slow append that will eventually become a +2.
        fakeSlowAppend()

        // Does a real append so that take will receive something.
        var testTask = TaskWithId(1234)
        assertThat(queue.tryAppend(testTask)).isTrue()
        // Take skips over the fake slow append and increments the skip marker.
        assertThat(queue.take()).isEqualTo(testTask)

        // Verifies that the skip marker has been incremented.
        assertThat(queue.getQueueForTesting()[0]).isEqualTo(2)

        // The next section verifies that a real append decrements the skip counter.

        // Fakes completion of the append by setting the index at the correct position and calling
        // tryAppend. The difference between this and having a real descheduled append is the index
        // after execution could be different from 1 + the one it starts on and it won't increment the
        // queue size again. Neither of these matter for this test.
        UNSAFE.putInt(null, appendIndexAddress, 2 * CAPACITY)
        testTask = TaskWithId(5678)
        assertThat(queue.tryAppend(testTask)).isTrue()
        // Verifies the decrement from 2 down to 1.
        assertThat(queue.getQueueForTesting()[0]).isEqualTo(1)
        // Verifies that the actual append occurs in the next position.
        assertThat(queue.getQueueForTesting()[1]).isEqualTo(testTask)

        // Resets the index and the receiving location of the append and verifies that append decrements
        // from 1 down to null.
        UNSAFE.putInt(null, appendIndexAddress, 2 * CAPACITY)
        queue.getQueueForTesting()[1] = null

        testTask = TaskWithId(101)
        assertThat(queue.tryAppend(testTask)).isTrue()
        assertThat(queue.getQueueForTesting()[0]).isNull()
        assertThat(queue.getQueueForTesting()[1]).isEqualTo(testTask)
    }

    // Fakes a slow append by incrementing the size and append indices. These are the only visible
    // side effects of slow appends.
    // TODO: b/386384684 - remove Unsafe usage
    private fun fakeSlowAppend() {
        UNSAFE.getAndAddInt(null, sizeAddress, 1)
        UNSAFE.getAndAddInt(null, appendIndexAddress, 1)
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Test
    fun slowTakes_areSkippedByAppend_thenUnmarkedByTakes() {
        // This test covers the state machine transitions that handle slow takers observed by
        // appenders. Descheduled threads at precise moments is hard to model without mutilating the
        // code so this test fakes a lot of behavior to cover the applicable code paths.

        // Appends an initial task.

        val task0 = TaskWithId(0)
        assertThat(queue.tryAppend(task0)).isTrue()

        // To simulate a slow take, rewinds the append index and appends again. Ordinarily, take should
        // consume the underlying task before another append.
        UNSAFE.putInt(null, appendIndexAddress, 0)
        val task1 = TaskWithId(1)
        assertThat(queue.tryAppend(task1)).isTrue()

        // Verifies that append adds a wrapper to the task.
        var wrappedTask: ElementWithSkippedAppends = queue.getQueueForTesting()[0] as ElementWithSkippedAppends
        assertThat(wrappedTask.element).isEqualTo(task0)
        // Verifies that the skip count is 1.
        assertThat(wrappedTask.skippedAppendCount).isEqualTo(1)

        // Verifies that append in fact skips to the next index and appends there.
        assertThat(queue.getQueueForTesting()[1]).isEqualTo(task1)

        // Resets the position after the one being tested and rewinds the append index once more.
        queue.getQueueForTesting()[1] = null
        UNSAFE.putInt(null, appendIndexAddress, 0)

        // Appends yet again (without an intervening take) to simulate a 2nd slow take. This should be
        // incredibly rare in the real world but can happen in theory because there's no certain
        // guarantees on thread scheduling.
        val task2 = TaskWithId(2)
        assertThat(queue.tryAppend(task2)).isTrue()

        // Verifies that the skip count has been incremented to 2.
        wrappedTask = queue.getQueueForTesting()[0] as ElementWithSkippedAppends
        assertThat(wrappedTask.element).isEqualTo(task0)
        assertThat(wrappedTask.skippedAppendCount).isEqualTo(2)
        // Verifies that the append actually skipped to the next index.
        assertThat(queue.getQueueForTesting()[1]).isEqualTo(task2)

        // The next part of the test verifies that take undoes the wrapping skip counting of append.

        // Take skips to the task in the next position when it observes the wrapper.
        assertThat(queue.take()).isEqualTo(task2)
        wrappedTask = queue.getQueueForTesting()[0] as ElementWithSkippedAppends
        assertThat(wrappedTask.element).isEqualTo(task0)
        // Take decrements the skip counter.
        assertThat(wrappedTask.skippedAppendCount).isEqualTo(1)
        // Verifies that it took the task in the next position out of the queue.
        assertThat(queue.getQueueForTesting()[1]).isNull()

        // Replaces a task in the next position of the queue for take to consume.
        queue.getQueueForTesting()[1] = task2
        // Resets the take indeox.
        UNSAFE.putInt(null, takeIndexAddress, 0)

        // Take indeed takes the task from the next available position when it sees the wrapper.
        assertThat(queue.take()).isEqualTo(task2)

        // Take has fully unwrapped the element.
        assertThat(queue.getQueueForTesting()[0]).isEqualTo(task0)
    }

    private class TaskWithId(private val id: Int) : Runnable {
        override fun run() {
            throw UnsupportedOperationException()
        }

        override fun hashCode(): Int {
            return id
        }

        override fun equals(obj: Any?): Boolean {
            if (obj !is TaskWithId) {
                return false
            }
            return this.id == obj.id
        }

        override fun toString(): String {
            return "T{" + id + "}"
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private const val PARALLELISM = 10

        private val UNSAFE: Unsafe = UnsafeProvider.unsafe()
    }
}
