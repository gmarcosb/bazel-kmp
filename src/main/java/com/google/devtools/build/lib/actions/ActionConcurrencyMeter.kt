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
package com.google.devtools.build.lib.actions

import com.google.common.flogger.GoogleLogger
import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder.get
import com.sun.management.GarbageCollectionNotificationInfo
import com.sun.management.GcInfo
import java.util.HashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import javax.management.ListenerNotFoundException
import javax.management.NotificationEmitter
import javax.management.NotificationListener
import javax.management.openmbean.CompositeData

/**
 * A meter used to limit the number of concurrent actions. Use [.acquireUninterruptibly]
 * before executing the action and use [.release] after the action is completed.
 * 
 * 
 * The meter is initialized with `minActiveAction` and `maxActiveAction`. At any
 * given time, the meter makes sure [.acquireUninterruptibly] returns immediately if the
 * current number of concurrent actions is less than `minActiveAction`, or waits until it is
 * below `maxActiveAction` after other threads call [.release].
 * 
 * 
 * When the current number of concurrent actions is between `minActiveAction` and `maxActiveAction`, the meter measures current heap memory usage to determine whether [ ][.acquireUninterruptibly] should wait based on a heuristic algorithm:
 * 
 * 
 *  * Since Java is a GC language, before a GC event, Bazel can only allocate memories.
 *  * Assuming during execution phrase, the majority of memory allocations are for action
 * execution.
 *  * Assuming after the action is completed, the majority of memory allocations by that action
 * can be collected.
 *  * The meter tracks the number of completed actions between GC events, it also knows how much
 * memory was collected by a GC event. Thus it can estimate how much memory is used by an
 * action.
 *  * Based on the memory usage and the remaining size of the heap, the meter can estimate how
 * many actions can be executed before next GC.
 *  * It uses `minActiveAction` to make sure the execution phrase can make progress in case
 * it over-estimates the memory usage. This can happen with skymeld when execution phrase is
 * mixed with analysis phrase so that the first assumption is wrong.
 *  * In case of under-estimate, it will mostly bounded by `maxActiveAction` and the next
 * GC event should adjust the estimation.
 * 
 * 
 * 
 * If `minActiveAction` is equal to `maxActiveAction`, the meter behaves like a
 * [Semaphore] whose permits is initialized to `maxActiveAction`.
 */
class ActionConcurrencyMeter private constructor(
    memoryBean: java.lang.management.MemoryMXBean,
    mxBeans: Iterable<java.lang.management.GarbageCollectorMXBean?>,
    minActiveAction: Int,
    maxActiveAction: Int
) : NotificationListener {
    private val lock: ReentrantLock = ReentrantLock()
    private val cond: java.util.concurrent.locks.Condition = lock.newCondition()
    private val activeAction: AtomicInteger = AtomicInteger(0)
    private val maxTotalActionSinceLastGc: AtomicInteger = AtomicInteger(0)
    private val totalActionSinceLastGc: AtomicInteger = AtomicInteger(0)
    private val stopped: AtomicBoolean = AtomicBoolean(false)

    private val memoryBean: java.lang.management.MemoryMXBean
    private val garbageCollectorBeans: com.google.common.collect.ImmutableList<java.lang.management.GarbageCollectorMXBean>
    private val minActiveAction: Int
    private val maxActiveActionSemaphore: Semaphore
    private val enabled: Boolean

    constructor(minActiveAction: Int, maxActiveAction: Int) : this(
        java.lang.management.ManagementFactory.getMemoryMXBean(),
        java.lang.management.ManagementFactory.getGarbageCollectorMXBeans(),
        minActiveAction,
        maxActiveAction
    )

    init {
        com.google.common.base.Preconditions.checkArgument(minActiveAction > 0)
        com.google.common.base.Preconditions.checkArgument(minActiveAction <= maxActiveAction)

        this.garbageCollectorBeans =
            com.google.common.collect.ImmutableList.copyOf<java.lang.management.GarbageCollectorMXBean?>(mxBeans)
        this.memoryBean = memoryBean
        this.minActiveAction = minActiveAction
        this.maxActiveActionSemaphore = Semaphore(maxActiveAction)
        this.enabled = maxActiveAction > minActiveAction

        if (enabled) {
            for (mxBean in this.garbageCollectorBeans) {
                (mxBean as NotificationEmitter).addNotificationListener(this, null, null)
            }
        }
    }

    fun stop() {
        check(stopped.compareAndSet(false, true)) { "Already stopped" }
        if (enabled) {
            for (mxBean in garbageCollectorBeans) {
                try {
                    (mxBean as NotificationEmitter).removeNotificationListener(this)
                } catch (e: ListenerNotFoundException) {
                    throw java.lang.AssertionError("Unexpected ListenerNotFoundException", e)
                }
            }
        }
    }

    /** Acquire a permit to execute an action, blocking until one is available.  */
    fun acquireUninterruptibly() {
        com.google.common.base.Preconditions.checkState(!stopped.get(), "Already stopped")

        maxActiveActionSemaphore.acquireUninterruptibly()

        // If current number of active actions exceeds the min watermark, queue the action.
        if (activeAction.incrementAndGet() > minActiveAction) {
            activeAction.decrementAndGet()

            lock.lock()
            try {
                // Queue the action until:
                //    1. number of active actions is below the min watermark, or
                //    2. we are allowed to schedule more actions based on memory estimation.
                while (true) {
                    val currentActiveAction: Int = activeAction.incrementAndGet()
                    if (currentActiveAction <= minActiveAction) {
                        break
                    }

                    if (enabled && totalActionSinceLastGc.get() < maxTotalActionSinceLastGc.get()) {
                        break
                    }

                    activeAction.decrementAndGet()
                    cond.awaitUninterruptibly()
                }
            } finally {
                lock.unlock()
            }
        }

        totalActionSinceLastGc.incrementAndGet()
    }

    /**
     * Releases a permit, allowing other threads blocking on [.acquireUninterruptibly] to
     * continue.
     */
    fun release() {
        // If current number of active actions is below the minimal watermark, wake up one action in the
        // queue.
        if (activeAction.decrementAndGet() < minActiveAction) {
            lock.lock()
            try {
                cond.signal()
            } finally {
                lock.unlock()
            }
        }

        maxActiveActionSemaphore.release()
    }

    override fun handleNotification(notification: javax.management.Notification, handback: Any?) {
        if (notification
                .getType()
            != GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION
        ) {
            return
        }

        var collectedMemoryBytes: Long = 0

        val info: GarbageCollectionNotificationInfo =
            GarbageCollectionNotificationInfo.from(notification.getUserData() as CompositeData?)
        val gcInfo: GcInfo = info.getGcInfo()
        val usedMemoryUsageBeforeGc: MutableMap<String?, Long?> = HashMap<String?, Long?>()
        for (entry in gcInfo.getMemoryUsageBeforeGc().entries) {
            usedMemoryUsageBeforeGc.put(entry.key, entry.value.getUsed())
        }
        for (entry in gcInfo.getMemoryUsageAfterGc().entries) {
            var before = usedMemoryUsageBeforeGc.remove(entry.key)
            if (before == null) {
                before = 0L
            }
            collectedMemoryBytes += before - entry.value.getUsed()
        }
        for (entry in usedMemoryUsageBeforeGc.entries) {
            collectedMemoryBytes += entry.value!!
        }

        // Ignore this GC event if no memory is collected.
        if (collectedMemoryBytes <= 0) {
            return
        }

        val heapMemoryUsedBytes: Long
        var heapMemoryMaxBytes: Long
        try {
            val heapMemoryUsage: java.lang.management.MemoryUsage = memoryBean.getHeapMemoryUsage()
            heapMemoryUsedBytes = heapMemoryUsage.getUsed()
            heapMemoryMaxBytes = heapMemoryUsage.getMax()
            if (heapMemoryMaxBytes < 0) {
                heapMemoryMaxBytes = heapMemoryUsage.getCommitted()
            }
        } catch (e: java.lang.IllegalArgumentException) {
            // The JVM may report committed > max. See b/180619163.
            return
        }

        // Leave some headroom in case of underestimation to avoid triggering too many GCs. 0.8 is an
        // arbitrary chosen value.
        val heapMemoryMaxBytesRatio = 0.8
        val heapMemoryAvailableBytes: Long =
            max(0, java.lang.Math.round(heapMemoryMaxBytes.toDouble() * heapMemoryMaxBytesRatio - heapMemoryUsedBytes))

        val currentActiveAction: Int = activeAction.get()
        // currentActiveAction might be out of sync with activeAction, but it's fine for our purpose:
        // it's an estimation anyway.
        val doneAction: Int = totalActionSinceLastGc.getAndSet(currentActiveAction) - currentActiveAction
        var additionalActions = 0
        var estimatedBytesPerAction = 0.0
        if (doneAction > 0) {
            estimatedBytesPerAction = (collectedMemoryBytes.toDouble() / doneAction)
            val estimatedAdditionalActions = heapMemoryAvailableBytes / estimatedBytesPerAction
            additionalActions = estimatedAdditionalActions.toInt()
        }
        val newMaxTotalActionSinceLastGc = currentActiveAction + additionalActions
        maxTotalActionSinceLastGc.set(newMaxTotalActionSinceLastGc)

        lock.lock()
        try {
            cond.signalAll()
        } finally {
            lock.unlock()
        }

        logger.atInfo().log(
            "Collected %.1f MB memory over %s actions, %.1f MB / action",
            collectedMemoryBytes.toDouble() / 1024 / 1024,
            doneAction,
            estimatedBytesPerAction / 1024 / 1024
        )
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    }
}
