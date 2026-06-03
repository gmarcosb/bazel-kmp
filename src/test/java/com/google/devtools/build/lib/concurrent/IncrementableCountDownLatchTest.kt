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

import com.google.common.util.concurrent.MoreExecutors
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.function.ThrowingRunnable
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.*

/**
 * Test cases for [IncrementableCountDownLatch]
 * 
 * @author Shay Raz
 * @author Martin Buchholz
 */
@RunWith(JUnit4::class)
class IncrementableCountDownLatchTest {
    private var executor: ExecutorService? = null

    @Before
    fun setUp() {
        executor = Executors.newSingleThreadExecutor()
    }

    @After
    fun tearDown() {
        Assert.assertTrue(MoreExecutors.shutdownAndAwaitTermination(executor, 10, TimeUnit.SECONDS))
    }

    @Test
    @Throws(Exception::class)
    fun testIncrementableCountDownLatch() {
        var icdl = CountingIncrementableCountDownLatch(2, 2)

        var result: Future<*> = executor!!.submit<Void?>(WaitSuccessfully(icdl))
        icdl.countDown()
        icdl.countDown()

        Assert.assertTrue(icdl.match())
        result.get()

        // increment by one
        icdl = CountingIncrementableCountDownLatch(2, 3)

        result = executor!!.submit<Void?>(WaitSuccessfully(icdl))
        icdl.countDown()
        icdl.increment(1)
        icdl.countDown()
        icdl.countDown()

        Assert.assertTrue(icdl.match())
        result.get()
    }

    @Test
    @Throws(Exception::class)
    fun testIncrementableCountDownLatchTooLate() {
        val icdl = CountingIncrementableCountDownLatch(2, 2)

        val result: Future<*> = executor!!.submit<Void?>(WaitSuccessfully(icdl))
        icdl.countDown()
        icdl.countDown()
        Assert.assertThrows<IllegalStateException?>(
            IllegalStateException::class.java,
            ThrowingRunnable { icdl.increment(1) })
        Assert.assertTrue(icdl.match())
        result.get()
    }

    @Test
    @Throws(Exception::class)
    fun testIncrementableCountDownLatchWithTimeout() {
        var icdl = CountingIncrementableCountDownLatch(2, 2)

        var result: Future<*> = executor!!.submit<Void?>(WaitSuccessfullyWithTimeout(icdl))
        icdl.countDown()
        icdl.countDown()
        Assert.assertTrue(icdl.match())
        result.get()

        // increment by one
        icdl = CountingIncrementableCountDownLatch(2, 3)

        result = executor!!.submit<Void?>(WaitSuccessfullyWithTimeout(icdl))
        icdl.countDown()
        icdl.increment(1)
        icdl.countDown()
        icdl.countDown()

        Assert.assertTrue(icdl.match())
        result.get()
    }

    @Test
    @Throws(Exception::class)
    fun testIncrementableCountDownLatchWithTimeoutTimedOut() {
        var icdl = CountingIncrementableCountDownLatch(2, 1)

        var result: Future<*> = executor!!.submit<Void?>(WaitUnsuccessfullyWithTimeout(icdl))
        icdl.countDown()
        Assert.assertTrue(icdl.match())
        result.get()

        // increment by one
        icdl = CountingIncrementableCountDownLatch(2, 2)

        result = executor!!.submit<Void?>(WaitUnsuccessfullyWithTimeout(icdl))
        icdl.countDown()
        icdl.increment(1)
        icdl.countDown()

        Assert.assertTrue(icdl.match())
        result.get()
    }

    /** increment() is equivalent to increment(1)  */
    @Test
    @Throws(InterruptedException::class)
    fun testNullaryIncrement() {
        val icdl: IncrementableCountDownLatch = IncrementableCountDownLatch(1)
        assertEquals(1, icdl.getCount())
        icdl.increment()
        assertEquals(2, icdl.getCount())
        icdl.increment()
        assertEquals(3, icdl.getCount())
        icdl.countDown()
        icdl.countDown()
        icdl.countDown()
        assertEquals(0, icdl.getCount())
        icdl.await()
    }

    /**
     * Incrementing past Integer.MAX_VALUE throws IllegalStateException, and leaves count unchanged.
     */
    @Test
    fun testCountOverflow() {
        val icdl: IncrementableCountDownLatch = IncrementableCountDownLatch(1)
        Assert.assertThrows<IllegalArgumentException?>(
            IllegalArgumentException::class.java,
            ThrowingRunnable { icdl.increment(Int.Companion.MAX_VALUE) })
        assertEquals(1, icdl.getCount())
    }

    /** Incrementing the count to Integer.MAX_VALUE succeeds.  */
    @Test
    fun testIncrementCountToMaxValue() {
        val icdl: IncrementableCountDownLatch = IncrementableCountDownLatch(42)

        icdl.increment(Int.Companion.MAX_VALUE - 42)
        assertEquals(Int.Companion.MAX_VALUE, icdl.getCount())
    }

    private class WaitSuccessfully(val icdl: CountingIncrementableCountDownLatch) : Callable<Void?> {
        @Throws(Exception::class)
        override fun call(): Void? {
            icdl.await()
            return null
        }
    }

    private class WaitSuccessfullyWithTimeout(val icdl: CountingIncrementableCountDownLatch) : Callable<Void?> {
        @Throws(Exception::class)
        override fun call(): Void? {
            Assert.assertTrue(icdl.await(10, TimeUnit.SECONDS))
            return null
        }
    }

    private class WaitUnsuccessfullyWithTimeout(val icdl: CountingIncrementableCountDownLatch) : Callable<Void?> {
        @Throws(Exception::class)
        override fun call(): Void? {
            Assert.assertFalse(icdl.await(12, TimeUnit.MILLISECONDS))
            return null
        }
    }

    private class CountingIncrementableCountDownLatch(count: Int, expected: Int) {
        val expected: Int
        var actual: Int = 0
        val latch: IncrementableCountDownLatch

        init {
            latch = IncrementableCountDownLatch(count)
            this.expected = expected
        }

        @Throws(InterruptedException::class)
        fun await(timeout: Int, unit: TimeUnit?): Boolean {
            return latch.await(timeout, unit)
        }

        @Throws(InterruptedException::class)
        fun await() {
            latch.await()
        }

        fun increment(i: Int) {
            latch.increment(i)
        }

        fun countDown() {
            actual++
            latch.countDown()
        }

        fun match(): Boolean {
            return expected == actual
        }
    }
}
