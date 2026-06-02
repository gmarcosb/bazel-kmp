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

import com.google.devtools.build.lib.unsafe.UnsafeProvider

/**
 * A fixed-capacity concurrent FIFO.
 * 
 * 
 * This class is a higher performance, nearly garbage-free, but less flexible substitute for
 * [ConcurrentLinkedQueue],
 * 
 * 
 *  * The queue capacity is fixed.
 *  * The client must guarantee not to take more than it has added.
 *  * The client must have an fallback if [tryAppend] fails.
 * 
 * 
 * 
 * This class is inspired by Morrison, Adam, and Yehuda Afek. "Fast concurrent queues for x86
 * processors." Proceedings of the 18th ACM SIGPLAN symposium on Principles and practice of parallel
 * programming. 2013.
 */
// TODO: b/386384684 - remove Unsafe usage
internal open class ConcurrentFifo<T>(
    elementType: java.lang.Class<in T?>,
    sizeAddress: Long,
    appendIndexAddress: Long,
    takeIndexAddress: Long
) {
    /**
     * Circular buffer containing tasks and skip metadata.
     * 
     * 
     * The algorithm assigns to each caller of [.tryAppend] or [.take] a monotonically
     * increasing index (not including [.tryAppend] calls that would exceed capacity). [ ][.take] cannot be called more times than successful [.tryAppend] calls by client
     * restriction. Thus each taker is assigned to a previous appender by matching index.
     * 
     * 
     * The naive algorithm based on the above would not be lock-free due to slow or descheduled
     * threads. For example, consider a taker assigned to an index where the corresponding appender's
     * thread has been descheduled before writing its task to the queue. When the taker observes its
     * assigned queue position, it does not see a value. The converse scenario is also possible. An
     * appender could see an occupied queue position while expecting `null` when a taker on the
     * same cyclic offset from a previous epoch is slow.
     * 
     * 
     * To resolve these situations, the threads that encounter them actively place a skip marker
     * into the queue that needs to be consumed by its counterpart. A taker that observes a null value
     * places a [Integer] `1` to mark it, then skips to the next index. On seeing the
     * marker, the appender decrements it (with `1` transitioning back to `null`). The
     * taker, when skipping to the next index, can expect to find a value there because an incomplete
     * append does not count as a successful one so there should be an extra complete append at a
     * subsequent index. Likewise, appenders that have looped all the way around to the same offset,
     * should expect to find an empty queue position due to capacity constraints.
     * 
     * 
     * Appenders that observe a value when expecting an empty position wrap the value with [ ] then skip to the next available index. Slow takers decrement the
     * counts on the wrappers then skip to the next available index.
     * 
     * 
     * The skip marker has a count because the number of threads that could potentially be
     * descheduled at a particular index is only limited by the queue capacity, though more than one
     * should be extremely rare.
     * 
     * 
     * Each queue position contains one of the following.
     * 
     * 
     *  * `null` is an empty position.
     *  * [T] is a position containing a task.
     *  * [Integer] is a count of takers that skipped the position because they observed a
     * null value. The count corresponds to slow appenders at the position.
     *  * [ElementWithSkippedAppends] is a task with a count of appenders that skipped the
     * position due to it being still occupied with a task. The count corresponds to slow takers
     * assigned to the position.
     * 
     * 
     * 
     * Correctness of the algorithm is straightforward. Anytime a taker skips a position, it adds a
     * count so that the same number of appenders skip that position and vice versa. Therefore the
     * number of takers and appenders skipping any given position stays balanced so the take and
     * append indices stay synchronized.
     */
    @get:com.google.common.annotations.VisibleForTesting
    val queueForTesting: Array<Any?> =
        arrayOfNulls<Any>(com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.CAPACITY)

    private val elementType: java.lang.Class<in T?>

    /**
     * Address of int index for appending; incremented by appending.
     * 
     * 
     * The actual array offset is the value modulo [.CAPACITY].
     */
    private val appendIndexAddress: Long

    /**
     * Address of int index for taking; incremented by taking.
     * 
     * 
     * The actual array offset is the value modulo [.CAPACITY].
     */
    private val takeIndexAddress: Long

    /**
     * The queue contains no more than this many tasks.
     * 
     * 
     * This is incremented before appending and decremented after taking.
     */
    private val sizeAddress: Long

    /**
     * Tries to insert a task into the queue.
     * 
     * @return true if successful, false if it would have exceeded the capacity.
     */
    // TODO: b/386384684 - remove Unsafe usage
    open fun tryAppend(task: T?): Boolean {
        // Optimistically increases size, and rolls back if it exceeds capacity.
        if (com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.UNSAFE.getAndAddInt(
                null,
                sizeAddress,
                1
            ) >= com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.MAX_ELEMENTS
        ) {
            com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.UNSAFE.getAndAddInt(null, sizeAddress, -1)
            return false
        }

        do {
            val offset: Int = com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.getQueueOffset(
                com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.UNSAFE.getAndAddInt(
                    null,
                    appendIndexAddress,
                    1
                )
            )
            // In the common case, we can avoid an extra read.
            if (com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.UNSAFE.compareAndSwapObject(
                    this.queueForTesting,
                    offset.toLong(),
                    null,
                    task
                )
            ) {
                return true
            }
            do {
                // A plain read is sufficient here because this is always preceded by a failed CAS of the
                // same memory location.
                val snapshot: Any? = com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.UNSAFE.getObject(
                    this.queueForTesting, offset.toLong()
                )
                // It's possible that the taker outraced the snapshot above.
                if (snapshot == null) {
                    if (com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.UNSAFE.compareAndSwapObject(
                            this.queueForTesting, offset.toLong(), null, task
                        )
                    ) {
                        return true
                    }
                    continue  // Refreshes the snapshot.
                }
                // There's some slowness that has to be resolved.
                val target: Any?
                if (snapshot is Int) {
                    // There were previous takes without corresponding appends. Acknowledges a taker that
                    // skipped this offset.
                    val newCount = snapshot - 1
                    target = if (newCount == 0) null else newCount
                } else if (elementType.isInstance(snapshot)) {
                    // A taker was slow.
                    val castSnapshot = snapshot as T?
                    target = com.google.devtools.build.lib.concurrent.ConcurrentFifo.ElementWithSkippedAppends<T?>(
                        castSnapshot,  /* skippedAppendCount= */
                        1
                    )
                } else {
                    // Multiple takers are slow. This should be very rare. Increments the skip count.
                    target = (snapshot as ElementWithSkippedAppends<*>).incrementSkips()
                }
                if (com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.UNSAFE.compareAndSwapObject(
                        this.queueForTesting,
                        offset.toLong(),
                        snapshot,
                        target
                    )
                ) {
                    break // Success, skips to next.
                } // Otherwise refreshes the snapshot.
            } while (true)
        } while (true)
    }

    /**
     * Takes an available task.
     * 
     * 
     * This must not be called more times than [.tryAppend] has succeeded.
     */
    // TODO: b/386384684 - remove Unsafe usage
    open fun take(): T? {
        do {
            val offset: Int = com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.getQueueOffset(
                com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.UNSAFE.getAndAddInt(
                    null,
                    takeIndexAddress,
                    1
                )
            )
            do {
                // A plain read is sufficient here.
                // 1. The initial read is supported by the client. In most cases, the client establishes the
                //    necessary happens-before relationship in honoring the constraint of no more takes than
                //    successful appends.
                // 2. On subsequent reads, this immediately follows a failed CAS of the same memory
                //    location, which refreshes the memory.
                val snapshot: Any? = com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.UNSAFE.getObject(
                    this.queueForTesting, offset.toLong()
                )
                if (elementType.isInstance(snapshot)) {
                    // Attempts to take ownership of the task.
                    if (com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.UNSAFE.compareAndSwapObject(
                            this.queueForTesting, offset.toLong(), snapshot, null
                        )
                    ) {
                        com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.UNSAFE.getAndAddInt(
                            null,
                            sizeAddress,
                            -1
                        )
                        val castSnapshot = snapshot as T?
                        return castSnapshot
                    }
                } else {
                    val target: Any?
                    if (snapshot == null) {
                        target = com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.SKIP_SLOW_APPENDER
                    } else if (snapshot is Int) {
                        // Increments the count due to multiple slow appenders, which should be very rare.
                        target = snapshot + 1
                    } else {
                        // There have been appends without corresponding takes. Acknowledges one skip.
                        target = (snapshot as ElementWithSkippedAppends<*>).decrementSkips()
                    }
                    if (com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.UNSAFE.compareAndSwapObject(
                            this.queueForTesting, offset.toLong(), snapshot, target
                        )
                    ) {
                        break // Success, skips to next.
                    } // Otherwise refreshes the snapshot.
                }
            } while (true)
        } while (true)
    }

    // TODO: b/386384684 - remove Unsafe usage
    fun size(): Int {
        return com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.UNSAFE.getIntVolatile(
            null,
            sizeAddress
        )
    }

    // TODO: b/386384684 - remove Unsafe usage
    fun clear() {
        com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.UNSAFE.putInt(null, sizeAddress, 0)
        com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.UNSAFE.putInt(null, appendIndexAddress, 0)
        com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.UNSAFE.putInt(null, takeIndexAddress, 0)
        java.util.Arrays.fill(this.queueForTesting, null)
    }

    // TODO: b/386384684 - remove Unsafe usage
    override fun toString(): String {
        val appendIndex: Int = com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.UNSAFE.getIntVolatile(
            null,
            appendIndexAddress
        )
        val takeIndex: Int = com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.UNSAFE.getIntVolatile(
            null,
            takeIndexAddress
        )
        val helper: com.google.common.base.MoreObjects.ToStringHelper =
            com.google.common.base.MoreObjects.toStringHelper(this)
                .add(
                    "size",
                    com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.UNSAFE.getIntVolatile(
                        null,
                        sizeAddress
                    )
                )
                .add(
                    "appendIndex",
                    java.lang.String.format(
                        "%d (%d)",
                        appendIndex,
                        appendIndex and com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.CAPACITY_MASK
                    )
                )
                .add(
                    "takeIndex",
                    java.lang.String.format(
                        "%d (%d)",
                        takeIndex,
                        takeIndex and com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.CAPACITY_MASK
                    )
                )
        val buf: java.lang.StringBuilder = java.lang.StringBuilder("[")
        for (i in 0..<com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.CAPACITY) {
            if (i > 0) {
                buf.append(',')
            }
            if (i % 10 == 0) {
                buf.append(i).append(':')
            }
            val elt = this.queueForTesting[i]
            if (elt == null) {
                buf.append('0')
            } else if (elementType.isInstance(elt)) {
                buf.append('1')
            } else if (elt is Int) {
                buf.append('S').append(elt)
            } else {
                buf.append('T').append((elt as ElementWithSkippedAppends<*>).skippedAppendCount)
            }
        }
        helper.add("queue", buf.append(']').toString())
        return helper.toString()
    }

    @com.google.common.annotations.VisibleForTesting
    @kotlin.jvm.JvmRecord
    internal data class ElementWithSkippedAppends<T>(@kotlin.jvm.JvmField val element: T?, @kotlin.jvm.JvmField val skippedAppendCount: Int) {
        private fun decrementSkips(): Any? {
            if (skippedAppendCount <= 1) {
                return element
            }
            return com.google.devtools.build.lib.concurrent.ConcurrentFifo.ElementWithSkippedAppends<T?>(
                element,
                skippedAppendCount - 1
            )
        }

        private fun incrementSkips(): ElementWithSkippedAppends<T?> {
            return com.google.devtools.build.lib.concurrent.ConcurrentFifo.ElementWithSkippedAppends<T?>(
                element,
                skippedAppendCount + 1
            )
        }
    }

    /**
     * Constructor.
     * 
     * 
     * The caller owns the memory associated with the provided addresses.
     * 
     * @param sizeAddress padded location of the `int` size of this queue.
     * @param takeIndexAddress padded location of the `int` take index.
     * @param appendIndexAddress padded location of the `int` append index.
     */
    // TODO: b/386384684 - remove Unsafe usage
    init {
        this.elementType = elementType
        this.sizeAddress = sizeAddress
        this.appendIndexAddress = appendIndexAddress
        this.takeIndexAddress = takeIndexAddress

        // Explicitly initializes the memory at the provided addresses.
        com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.UNSAFE.putInt(null, sizeAddress, 0)
        com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.UNSAFE.putInt(null, appendIndexAddress, 0)
        com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.UNSAFE.putInt(null, takeIndexAddress, 0)
    }

    companion object {
        // Non-final for testing only.
        private const val SKIP_SLOW_APPENDER = 1

        /** The power of 2 backing array capacity.  */
        @kotlin.jvm.JvmField
        @com.google.common.annotations.VisibleForTesting
        val CAPACITY: Int = 1 shl 16

        /** AND with this mask performs modulo [.CAPACITY].  */
        val CAPACITY_MASK: Int = com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.CAPACITY - 1

        /**
         * Maximum number of elements the FIFO can contain, one less than [.CAPACITY].
         * 
         * 
         * While the backing array's size is a power of 2, this is one less than that, to improve the
         * efficiency of bits used to represent the number of elements enqueued. For example, the number
         * of bits needed to represent the element count for a queue of size 256 is 9, but only 8 bits are
         * needed for a queue of size 255.
         */
        @kotlin.jvm.JvmField
        @com.google.common.annotations.VisibleForTesting
        val MAX_ELEMENTS: Int = com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.CAPACITY - 1

        private fun getQueueOffset(index: Int): Int {
            return com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.ELEMENTS_BASE + com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.ELEMENTS_SCALE * (index and com.google.devtools.build.lib.concurrent.ConcurrentFifo.Companion.CAPACITY_MASK)
        }

        private val UNSAFE: sun.misc.Unsafe = UnsafeProvider.unsafe()

        private val ELEMENTS_BASE: Int = sun.misc.Unsafe.ARRAY_OBJECT_BASE_OFFSET
        private val ELEMENTS_SCALE: Int = sun.misc.Unsafe.ARRAY_OBJECT_INDEX_SCALE
    }
}
