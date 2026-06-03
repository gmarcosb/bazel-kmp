// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.common.base.Preconditions
import com.google.common.collect.Collections2
import com.google.common.collect.ConcurrentHashMultiset
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Sets
import com.google.common.truth.Truth
import com.google.devtools.build.lib.testutil.TestThread
import com.google.devtools.build.lib.testutil.TestThread.TestRunnable
import com.google.devtools.build.lib.testutil.TestUtils
import com.google.devtools.build.lib.testutil.ThrowableRecordingRunnableWrapper
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import kotlin.collections.ArrayList
import kotlin.collections.HashSet
import kotlin.collections.LinkedHashSet
import kotlin.collections.MutableSet

/** Tests for [MultisetSemaphore].  */
@RunWith(JUnit4::class)
class MultisetSemaphoreTest {
    @Test
    @Throws(Exception::class)
    fun testSimple_serial() {
        // When we have a MultisetSemaphore
        val multisetSemaphore: MultisetSemaphore<String?> =
            MultisetSemaphore.newBuilder() // with 3 max num unique values,
                .maxNumUniqueValues(3)
                .build()

        // Then it initially has 0 unique values.
        assertThat(multisetSemaphore.estimateCurrentNumUniqueValues()).isEqualTo(0)

        // And then when we serially acquire permits for 3 unique values,
        multisetSemaphore.acquireAll(ImmutableSet.of<E?>("a", "b", "c"))
        // Then the MultisetSemaphore thinks it currently has 3 unique values.
        assertThat(multisetSemaphore.estimateCurrentNumUniqueValues()).isEqualTo(3)

        // And then when we attempt to acquire permits for 2 of those same unique values, we don't block
        // forever,
        multisetSemaphore.acquireAll(ImmutableSet.of<E?>("b", "c"))
        // And the MultisetSemaphore still thinks it currently has 3 unique values.
        assertThat(multisetSemaphore.estimateCurrentNumUniqueValues()).isEqualTo(3)

        // And then when we release one of the permit for one of those unique values,
        multisetSemaphore.releaseAll(ImmutableSet.of<E?>("c"))
        // The MultisetSemaphore still thinks it currently has 3 unique values.
        assertThat(multisetSemaphore.estimateCurrentNumUniqueValues()).isEqualTo(3)

        // And then we release the final permit for that unique value,
        multisetSemaphore.releaseAll(ImmutableSet.of<E?>("c"))
        // The MultisetSemaphore thinks it currently has 2 unique values.
        assertThat(multisetSemaphore.estimateCurrentNumUniqueValues()).isEqualTo(2)

        // And then we attempt to acquire a permit for a 4th unique value, we don't block forever,
        multisetSemaphore.acquireAll(ImmutableSet.of<E?>("d"))
        // And the MultisetSemaphore thinks it currently has 3 unique values.
        assertThat(multisetSemaphore.estimateCurrentNumUniqueValues()).isEqualTo(3)

        // And then we release one permit each for the remaining 3 that unique values,
        multisetSemaphore.releaseAll(ImmutableSet.of<E?>("a", "b", "d"))
        // The MultisetSemaphore thinks it currently has 1 unique values.
        assertThat(multisetSemaphore.estimateCurrentNumUniqueValues()).isEqualTo(1)

        // And then we release the final permit for the remaining unique value,
        multisetSemaphore.releaseAll(ImmutableSet.of<E?>("b"))
        // The MultisetSemaphore thinks it currently has 0 unique values.
        assertThat(multisetSemaphore.estimateCurrentNumUniqueValues()).isEqualTo(0)
    }

    @Test
    @Throws(Exception::class)
    fun testSimple_concurrent() {
        // When we have N and M, with M > N and M|N.
        val n = 10
        val m = n * 2
        Preconditions.checkState(m > n && m % n == 0, "M=%s N=%s", m, n)
        // When we have a MultisetSemaphore
        val multisetSemaphore: MultisetSemaphore<String?> =
            MultisetSemaphore.newBuilder() // with N max num unique values,
                .maxNumUniqueValues(n)
                .build()

        // And a ExecutorService with M threads,
        val executorService = Executors.newFixedThreadPool(m)
        // And a recorder for thrown exceptions,
        val wrapper =
            ThrowableRecordingRunnableWrapper("testSimple_Concurrent")
        val numThreadsJustAfterAcquireInFirstRound = AtomicInteger(0)
        val numThreadsJustAfterAcquireInSecondRound = AtomicInteger(0)
        val secondRoundCompleted = AtomicInteger(0)
        val napTimeMs = 42
        for (i in 0..<m) {
            val `val` = "val" + i
            // And we submit M Runnables, each of which
            @Suppress("unused") val possiblyIgnoredError =
                executorService.submit(
                    wrapper.wrap(
                        object : Runnable {
                            override fun run() {
                                try {
                                    // Has two rounds

                                    // Wherein the first round
                                    //   The Runnable acquire a permit for a unique value (among M values),

                                    val valSet = ImmutableSet.of<String?>(`val`)
                                    multisetSemaphore.acquireAll(valSet)
                                    Truth.assertThat(numThreadsJustAfterAcquireInFirstRound.getAndIncrement())
                                        .isLessThan(n)
                                    //   And then sleeps,
                                    Thread.sleep(napTimeMs.toLong())
                                    numThreadsJustAfterAcquireInFirstRound.decrementAndGet()
                                    multisetSemaphore.releaseAll(valSet)

                                    // And wherein the second round
                                    //   The Runnable again acquires a permit for its unique value,
                                    multisetSemaphore.acquireAll(valSet)
                                    Truth.assertThat(numThreadsJustAfterAcquireInSecondRound.getAndIncrement())
                                        .isLessThan(n)
                                    //   And then sleeps,
                                    Thread.sleep(napTimeMs.toLong())
                                    numThreadsJustAfterAcquireInSecondRound.decrementAndGet()
                                    //   And notes that it has completed the second round,
                                    secondRoundCompleted.incrementAndGet()
                                    multisetSemaphore.releaseAll(valSet)
                                } catch (e: InterruptedException) {
                                    throw IllegalStateException(e)
                                }
                            }
                        })
                )
        }
        // And we wait for all M Runnables to complete (that is, none of them were deadlocked),
        val interrupted: Boolean = ExecutorUtil.interruptibleShutdown(executorService)
        // Then none of our Runnables threw any Exceptions.
        Truth.assertThat(wrapper.getFirstThrownError()).isNull()
        if (interrupted) {
            Thread.currentThread().interrupt()
            throw InterruptedException()
        }
        // And the counters were correctly reset to 0.
        Truth.assertThat(numThreadsJustAfterAcquireInFirstRound.get()).isEqualTo(0)
        Truth.assertThat(numThreadsJustAfterAcquireInSecondRound.get()).isEqualTo(0)
        // And all M Runnables completed the second round.
        Truth.assertThat(secondRoundCompleted.get()).isEqualTo(m)
        val newVals: MutableSet<String?> = HashSet<String?>()
        for (i in 0..<n) {
            newVals.add("newval" + i)
        }
        // And the main test thread is able to acquire permits for N new unique values (indirectly
        // confirming that the MultisetSemaphore previously had no outstanding permits).
        multisetSemaphore.acquireAll(newVals)
    }

    @Test
    @Throws(Exception::class)
    fun testConcurrentAtomicity() {
        val n = 100
        // When we have a MultisetSemaphore
        val multisetSemaphore: MultisetSemaphore<String?> =
            MultisetSemaphore.newBuilder() // with 2 max num unique values,
                .maxNumUniqueValues(2)
                .build()
        // And a ExecutorService with N threads,
        val executorService = Executors.newFixedThreadPool(n)
        // And a recorder for thrown exceptions,
        val wrapper =
            ThrowableRecordingRunnableWrapper("testConcurrentAtomicity")
        val napTimeMs = 42
        // And a done latch with initial count N,
        val allDoneLatch = CountDownLatch(n)
        val sameVal = "same-val"
        for (i in 0..<n) {
            val differentVal = "different-val" + i
            // And we submit N Runnables, each of which
            @Suppress("unused") val possiblyIgnoredError =
                executorService.submit(
                    wrapper.wrap(
                        object : Runnable {
                            override fun run() {
                                try {
                                    val vals: MutableSet<String?> = ImmutableSet.of<String?>(sameVal, differentVal)
                                    // Tries to acquire permits for a set of two values, one of which is the
                                    // same for all the N Runnables and one of which is unique across all N
                                    // Runnables,
                                    multisetSemaphore.acquireAll(vals)
                                    // And then sleeps,
                                    Thread.sleep(napTimeMs.toLong())
                                    // And then releases its permits,
                                    multisetSemaphore.releaseAll(vals)
                                    // And then counts down the done latch,
                                    allDoneLatch.countDown()
                                } catch (e: InterruptedException) {
                                    throw IllegalStateException(e)
                                }
                            }
                        })
                )
        }
        // Then all of our Runnables completed (without deadlock!), as expected,
        val interrupted: Boolean = ExecutorUtil.interruptibleShutdown(executorService)
        // And thus were able to count down the done latch,
        allDoneLatch.await()
        // And also none of them threw any Exceptions.
        Truth.assertThat(wrapper.getFirstThrownError()).isNull()
        if (interrupted) {
            Thread.currentThread().interrupt()
            throw InterruptedException()
        }
    }

    @Test
    @Throws(Exception::class)
    fun testConcurrentRace_allPermuations() {
        // When we have N values
        val n = 6
        val vals = ArrayList<String?>()
        for (i in 0..<n) {
            vals.add("val-" + i)
        }
        // And we have all permutations of these N values
        val permutations = Collections2.orderedPermutations<String?>(vals)
        val numPermutations = permutations.size
        // And we have a MultisetSemaphore
        val multisetSemaphore: MultisetSemaphore<String?> =
            MultisetSemaphore.newBuilder() // with N max num unique values,
                .maxNumUniqueValues(n)
                .build()
        // And a ExecutorService with N! threads,
        val executorService = Executors.newFixedThreadPool(numPermutations)
        // And a recorder for thrown exceptions,
        val wrapper =
            ThrowableRecordingRunnableWrapper("testConcurrentRace_AllPermuations")
        for (orderedVals in permutations) {
            val orderedSet: MutableSet<String?> = LinkedHashSet<String?>(orderedVals)
            // And we submit N! Runnables, each of which
            @Suppress("unused") val possiblyIgnoredError =
                executorService.submit(
                    wrapper.wrap(
                        object : Runnable {
                            override fun run() {
                                try {
                                    // Tries to acquire permits for the set of N values, with a unique
                                    // iteration order (across all the N! different permutations),
                                    multisetSemaphore.acquireAll(orderedSet)
                                    // And then immediately releases the permits.
                                    multisetSemaphore.releaseAll(orderedSet)
                                } catch (e: InterruptedException) {
                                    throw IllegalStateException(e)
                                }
                            }
                        })
                )
        }
        // Then all of our Runnables completed (without deadlock!), as expected,
        val interrupted: Boolean = ExecutorUtil.interruptibleShutdown(executorService)
        // And also none of them threw any Exceptions.
        Truth.assertThat(wrapper.getFirstThrownError()).isNull()
        if (interrupted) {
            Thread.currentThread().interrupt()
            throw InterruptedException()
        }
    }

    @Test
    @Throws(Exception::class)
    fun testConcurrentRace_allSameSizedCombinations() {
        // When we have n values
        val n = 10
        val valsBuilder = ImmutableSet.builder<String?>()
        for (i in 0..<n) {
            valsBuilder.add("val-" + i)
        }
        val vals = valsBuilder.build()
        val k = 5
        // And we have all combinations of size k of these n values
        val combinations = Sets.combinations<String?>(vals, k)
        val numCombinations = combinations.size
        // And we have a MultisetSemaphore
        val multisetSemaphore: MultisetSemaphore<String?> =
            MultisetSemaphore.newBuilder() // with K max num unique values,
                .maxNumUniqueValues(k)
                .build()
        // And a ExecutorService with nCk threads,
        val executorService = Executors.newFixedThreadPool(numCombinations)
        // And a recorder for thrown exceptions,
        val wrapper =
            ThrowableRecordingRunnableWrapper("testConcurrentRace_AllSameSizedCombinations")
        // And a ConcurrentHashMultiset for counting the multiplicities of the values ourselves,
        val counts = ConcurrentHashMultiset.create<String?>()
        for (combination in combinations) {
            // And, for each of the nCk combinations, we submit a Runnable, that
            @Suppress("unused") val possiblyIgnoredError =
                executorService.submit(
                    wrapper.wrap(
                        object : Runnable {
                            override fun run() {
                                try {
                                    // Tries to acquire permits for its set of k values,
                                    multisetSemaphore.acquireAll(combination)
                                    // And then verifies that the multiplicities are as expected,
                                    combination.forEach(Consumer { element: String? -> counts.add(element) })
                                    Truth.assertThat(counts.entrySet().size).isAtMost(k)
                                    combination.forEach(Consumer { element: String? -> counts.remove(element) })
                                    // And then releases the permits.
                                    multisetSemaphore.releaseAll(combination)
                                } catch (e: InterruptedException) {
                                    throw IllegalStateException(e)
                                }
                            }
                        })
                )
        }
        // Then all of our Runnables completed (without deadlock!), as expected,
        val interrupted: Boolean = ExecutorUtil.interruptibleShutdown(executorService)
        // And also none of them threw any Exceptions.
        Truth.assertThat(wrapper.getFirstThrownError()).isNull()
        if (interrupted) {
            Thread.currentThread().interrupt()
            throw InterruptedException()
        }
    }

    @Test
    @Throws(Exception::class)
    fun testSimpleDeadlock() {
        val multisetSemaphore: MultisetSemaphore<String?> = MultisetSemaphore.newBuilder()
            .maxNumUniqueValues(2)
            .build()

        val thread1AcquiredLatch = CountDownLatch(1)
        val thread2AboutToAcquireLatch = CountDownLatch(1)
        val thread3AboutToAcquireLatch = CountDownLatch(1)

        val thread1 =
            TestThread(
                TestRunnable {
                    multisetSemaphore.acquireAll(ImmutableSet.of<E?>("a", "b"))
                    thread1AcquiredLatch.countDown()
                    thread2AboutToAcquireLatch.await(
                        TestUtils.WAIT_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS
                    )
                    thread3AboutToAcquireLatch.await(
                        TestUtils.WAIT_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS
                    )
                    Thread.sleep(1000)
                    multisetSemaphore.releaseAll(ImmutableSet.of<E?>("a", "b"))
                })
        thread1.setName("Thread1")

        val thread2 =
            TestThread(
                TestRunnable {
                    thread1AcquiredLatch.await(
                        TestUtils.WAIT_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS
                    )
                    thread2AboutToAcquireLatch.countDown()
                    multisetSemaphore.acquireAll(ImmutableSet.of<E?>("b", "c"))
                    multisetSemaphore.releaseAll(ImmutableSet.of<E?>("b", "c"))
                })
        thread2.setName("Thread2")

        val thread3 =
            TestThread(
                TestRunnable {
                    thread2AboutToAcquireLatch.await(
                        TestUtils.WAIT_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS
                    )
                    Thread.sleep(1000)
                    thread3AboutToAcquireLatch.countDown()
                    multisetSemaphore.acquireAll(ImmutableSet.of<E?>("a", "d"))
                    multisetSemaphore.releaseAll(ImmutableSet.of<E?>("a", "d"))
                })
        thread3.setName("Thread3")

        thread1.start()
        thread2.start()
        thread3.start()

        thread1.joinAndAssertState(TestUtils.WAIT_TIMEOUT_MILLISECONDS)
        thread2.joinAndAssertState(TestUtils.WAIT_TIMEOUT_MILLISECONDS)
        thread3.joinAndAssertState(TestUtils.WAIT_TIMEOUT_MILLISECONDS)
    }
}

