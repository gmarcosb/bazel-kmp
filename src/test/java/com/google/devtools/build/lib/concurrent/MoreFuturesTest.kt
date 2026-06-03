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

import com.google.common.truth.Truth
import com.google.common.util.concurrent.AbstractFuture
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.devtools.build.lib.testutil.TestUtils
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.*
import kotlin.collections.ArrayList
import kotlin.collections.MutableList

/**
 * Tests for MoreFutures
 */
@RunWith(JUnit4::class)
class MoreFuturesTest {
    private var executorService: ExecutorService? = null

    @Before
    @Throws(Exception::class)
    fun createExecutor() {
        executorService = Executors.newFixedThreadPool(5)
    }

    @After
    @Throws(Exception::class)
    fun shutdownExecutor() {
        MoreExecutors.shutdownAndAwaitTermination(
            executorService, TestUtils.WAIT_TIMEOUT_SECONDS,
            TimeUnit.SECONDS
        )
    }

    /** Test the normal path where everything is successful.  */
    @Test
    @Throws(ExecutionException::class, InterruptedException::class)
    fun allAsListOrCancelAllHappy() {
        val futureList: MutableList<DelayedFuture> = ArrayList<DelayedFuture>()
        for (i in 0..4) {
            val future = DelayedFuture(i)
            executorService!!.execute(future)
            futureList.add(future)
        }
        val list: ListenableFuture<MutableList<Any?>?> = MoreFutures.allAsListOrCancelAll(futureList)
        val result = list.get()
        Truth.assertThat(result).hasSize(futureList.size)
        for (delayedFuture in futureList) {
            Truth.assertThat(delayedFuture.wasCanceled).isFalse()
            Truth.assertThat(delayedFuture.wasInterrupted).isFalse()
            Truth.assertThat(delayedFuture.get()).isNotNull()
            Truth.assertThat(result).contains(delayedFuture.get())
        }
    }

    /** Test that if any of the futures in the list fails, we cancel all the futures immediately.  */
    @Test
    @Throws(InterruptedException::class, TimeoutException::class, ExecutionException::class)
    fun allAsListOrCancelAllCancellation() {
        val futureList: MutableList<DelayedFuture> = ArrayList<DelayedFuture>()
        for (i in 1..5) {
            val future = DelayedFuture(i * 1000)
            executorService!!.execute(future)
            futureList.add(future)
        }
        val toFail = DelayedFuture(1000)
        futureList.add(toFail)
        toFail.makeItFail()
        val list: ListenableFuture<MutableList<Any?>?> = MoreFutures.allAsListOrCancelAll(futureList)

        try {
            list.get()
            Assert.fail("This should fail")
        } catch (ignored: InterruptedException) {
        } catch (ignored: ExecutionException) {
        }

        // Wait for all the futures to be cancelled.
        for (future in futureList) {
            if (future !== toFail) {
                try {
                    future.get(TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    Assert.fail("Future should have been cancelled")
                } catch (e: InterruptedException) {
                    // This is expected.
                } catch (e: CancellationException) {
                }
            }
        }

        for (delayedFuture in futureList) {
            Truth.assertThat(delayedFuture.isCancelled() || delayedFuture === toFail).isTrue()
            Truth.assertThat(delayedFuture.wasInterrupted).isFalse()
        }
    }

    /**
     * A future that (if added to an executor) waits `delay` milliseconds before setting a
     * response.
     */
    private class DelayedFuture(private val delay: Int) : AbstractFuture<Any?>(), Runnable {
        private val failOrInterruptLatch = CountDownLatch(1)
        private val getLatch = CountDownLatch(1)
        private var wasCanceled = false
        private var wasInterrupted = false

        override fun run() {
            try {
                wasCanceled = failOrInterruptLatch.await(delay.toLong(), TimeUnit.MILLISECONDS)
                // Not canceled and not done (makeItFail sets the value, so in that case is done).
                if (!wasCanceled && !isDone()) {
                    set(Any())
                }
            } catch (e: InterruptedException) {
                wasInterrupted = true
            }
        }

        fun makeItFail() {
            setException(RuntimeException("I like to fail!!"))
            failOrInterruptLatch.countDown()
        }

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            return super.cancel(mayInterruptIfRunning)
        }

        override fun interruptTask() {
            failOrInterruptLatch.countDown()
        }

        @Throws(InterruptedException::class, TimeoutException::class, ExecutionException::class)
        override fun get(timeout: Long, unit: TimeUnit): Any? {
            getLatch.countDown()
            return super.get(timeout, unit)
        }
    }
}
