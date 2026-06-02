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

import java.io.Serial
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.AbstractQueuedSynchronizer

/**
 * A synchronization aid that allows one or more threads to wait until a set of operations being
 * performed in other threads completes.
 * 
 * 
 * This class is functionally identical to [CountDownLatch], but additionally supports
 * incrementing the count, using method [.increment]. This allows more tasks to be
 * executed before a thread waiting in [.await] is released.
 * 
 * 
 * For example, here is a way to wait for the completion of a collection of tasks when the number
 * of tasks is not known in advance:
 * 
 * {@snippet :
 * * final IncrementableCountDownLatch latch
 * *     = new IncrementableCountDownLatch(1);  // "lock"
 * * for (...) {
 * *   latch.increment();
 * *   executor.submit(new Runnable() {
 * *     public void run() {
 * *       // do work
 * *       latch.countDown();
 * *     }
 * *   });
 * * }
 * * latch.countDown();  // "unlock"
 * * latch.await();
 * * // all of the work is done
 * * }
 * 
 * 
 * Consider instead using [Phaser] or [ CountedCompleter][java.util.concurrent.CountedCompleter]. These are standard higher level synchronizers that also provide an
 * incrementable counter whose countdown can be awaited, while providing additional capabilities.
 * CountedCompleter, available since Java 8, may be useful when controlling tasks running in a fork
 * join pool.
 * 
 * @author Doug Lea
 * @author Martin Buchholz
 * @author Shay Raz
 */
class IncrementableCountDownLatch(count: Int) {
    /** Synchronization control for IncrementableCountDownLatch. Uses AQS state to represent count.  */
    private class Sync(count: Int) : AbstractQueuedSynchronizer() {
        val count: Int
            get() = getState()

        public override fun tryAcquireShared(acquires: Int): Int {
            return if (getState() == 0) 1 else -1
        }

        public override fun tryReleaseShared(releases: Int): Boolean {
            // Decrement count; signal when transition to zero
            while (true) {
                val c: Int = getState()
                if (c == 0) {
                    return false
                }
                if (releases > c) {
                    return false
                }
                val nextc = c - releases
                if (compareAndSetState(c, nextc)) {
                    return nextc == 0
                }
            }
        }

        fun increaseCount(delta: Int) {
            while (true) {
                val current: Int = getState()
                val next = current + delta
                check(current != 0) { "already counted down to zero" }
                require(next >= current) { "count overflow" }
                if (compareAndSetState(current, next)) {
                    return
                }
            }
        }

        init {
            setState(count)
        }

        companion object {
            @Serial
            private const val serialVersionUID = 0L
        }
    }

    private val sync: Sync

    /**
     * Constructs a `IncrementableCountDownLatch` initialized with the given count.
     * 
     * @param count the number of times [.countDown] must be invoked before threads can pass
     * through [.await]
     * @throws IllegalArgumentException if `count` is negative
     */
    init {
        com.google.common.base.Preconditions.checkArgument(count >= 0, "count (%s) must be >= 0", count)
        this.sync = com.google.devtools.build.lib.concurrent.IncrementableCountDownLatch.Sync(count)
    }

    /**
     * Causes the current thread to wait until the latch has counted down to zero, unless the thread
     * is [interrupted][Thread.interrupt].
     * 
     * 
     * If the current count is zero then this method returns immediately.
     * 
     * 
     * If the current count is greater than zero then the current thread becomes disabled for
     * thread scheduling purposes and lies dormant until one of two things happen:
     * 
     * 
     *  * The count reaches zero due to invocations of the [.countDown] method; or
     *  * Some other thread [interrupts][Thread.interrupt] the current thread.
     * 
     * 
     * 
     * If the current thread:
     * 
     * 
     *  * has its interrupted status set on entry to this method; or
     *  * is [interrupted][Thread.interrupt] while waiting,
     * 
     * 
     * 
     * then [InterruptedException] is thrown and the current thread's interrupted status is
     * cleared.
     * 
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    @Throws(java.lang.InterruptedException::class)
    fun await() {
        sync.acquireSharedInterruptibly(1)
    }

    /**
     * Causes the current thread to wait until the latch has counted down to zero, unless the thread
     * is [interrupted][Thread.interrupt], or the specified waiting time elapses.
     * 
     * 
     * If the current count is zero then this method returns immediately with the value `true`.
     * 
     * 
     * If the current count is greater than zero then the current thread becomes disabled for
     * thread scheduling purposes and lies dormant until one of three things happen:
     * 
     * 
     *  * The count reaches zero due to invocations of the [.countDown] method; or
     *  * Some other thread [interrupts][Thread.interrupt] the current thread; or
     *  * The specified waiting time elapses.
     * 
     * 
     * 
     * If the count reaches zero, then the method returns with the value `true`.
     * 
     * 
     * If the current thread:
     * 
     * 
     *  * has its interrupted status set on entry to this method; or
     *  * is [interrupted][Thread.interrupt] while waiting,
     * 
     * 
     * 
     * then [InterruptedException] is thrown and the current thread's interrupted status is
     * cleared.
     * 
     * 
     * If the specified waiting time elapses then the value `false` is returned. If the time
     * is less than or equal to zero, the method will not wait at all.
     * 
     * @param timeout the maximum time to wait
     * @return `true` if the count reached zero and `false` if the waiting time elapsed
     * before the count reached zero
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    @Throws(java.lang.InterruptedException::class)
    fun await(timeout: java.time.Duration): Boolean {
        return await(timeout.toNanos(), TimeUnit.NANOSECONDS)
    }

    /**
     * Causes the current thread to wait until the latch has counted down to zero, unless the thread
     * is [interrupted][Thread.interrupt], or the specified waiting time elapses.
     * 
     * 
     * If the current count is zero then this method returns immediately with the value `true`.
     * 
     * 
     * If the current count is greater than zero then the current thread becomes disabled for
     * thread scheduling purposes and lies dormant until one of three things happen:
     * 
     * 
     *  * The count reaches zero due to invocations of the [.countDown] method; or
     *  * Some other thread [interrupts][Thread.interrupt] the current thread; or
     *  * The specified waiting time elapses.
     * 
     * 
     * 
     * If the count reaches zero, then the method returns with the value `true`.
     * 
     * 
     * If the current thread:
     * 
     * 
     *  * has its interrupted status set on entry to this method; or
     *  * is [interrupted][Thread.interrupt] while waiting,
     * 
     * 
     * 
     * then [InterruptedException] is thrown and the current thread's interrupted status is
     * cleared.
     * 
     * 
     * If the specified waiting time elapses then the value `false` is returned. If the time
     * is less than or equal to zero, the method will not wait at all.
     * 
     * @param timeout the maximum time to wait
     * @param unit the time unit of the `timeout` argument
     * @return `true` if the count reached zero and `false` if the waiting time elapsed
     * before the count reached zero
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    @Throws(java.lang.InterruptedException::class)  // should accept a java.time.Duration
    fun await(timeout: Long, unit: TimeUnit): Boolean {
        return sync.tryAcquireSharedNanos(1, unit.toNanos(timeout))
    }

    /**
     * Decrements the count of the latch, releasing all waiting threads if the count reaches zero.
     * 
     * 
     * If the current count is greater than zero then it is decremented. If the new count is zero
     * then all waiting threads are re-enabled for thread scheduling purposes.
     * 
     * 
     * If the current count equals zero, then nothing happens.
     */
    fun countDown() {
        sync.releaseShared(1)
    }

    /**
     * Increases the count of the latch by 1.
     * 
     * 
     * The caller must ensure that the count of the latch is greater than zero.
     * 
     * 
     * This is a convenience method, equivalent to [increment(1)][.increment].
     * 
     * @throws IllegalStateException if the latch has already counted down to 0
     */
    fun increment() {
        sync.increaseCount(1)
    }

    /**
     * Increases the count of the latch by `delta`.
     * 
     * 
     * The caller must ensure that the count of the latch is greater than zero.
     * 
     * @param delta number of additional calls to [.countDown] required till waiting threads are
     * released
     * @throws IllegalArgumentException if `delta` is negative
     * @throws IllegalStateException if the latch has already counted down to 0
     */
    fun increment(delta: Int) {
        com.google.common.base.Preconditions.checkArgument(delta >= 0, "delta (%s) must be >= 0", delta)
        sync.increaseCount(delta)
    }

    val count: Long
        /**
         * Returns the current count.
         * 
         * 
         * This method is typically used for debugging and testing purposes.
         * 
         * @return the current count
         */
        get() = sync.count.toLong()

    /**
     * Returns a string identifying this latch, as well as its state. The state, in brackets, includes
     * the String `"Count ="` followed by the current count.
     * 
     * @return a string identifying this latch, as well as its state
     */
    override fun toString(): String {
        return super.toString() + "[Count = " + sync.count + "]"
    }

    /** Invokes [.await] uninterruptibly.  */
    fun awaitUninterruptibly() {
        var interrupted = false
        try {
            while (true) {
                try {
                    await()
                    return
                } catch (e: java.lang.InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) {
                java.lang.Thread.currentThread().interrupt()
            }
        }
    }
}
