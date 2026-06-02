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
package com.google.devtools.build.lib.util

import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A dispenser for up to 'total' simultaneous units of some resource. The resource itself is not
 * accessed through this object; this is basically an asynchronous interface to a counting
 * semaphore.
 */
@com.google.errorprone.annotations.ThreadSafe
class ConcurrencyMeter(name: String?, private val total: Long, clock: com.google.devtools.build.lib.clock.Clock?) {
    private val name: String

    @com.google.errorprone.annotations.concurrent.GuardedBy("this")
    private val clock: com.google.devtools.build.lib.clock.Clock

    @com.google.errorprone.annotations.concurrent.GuardedBy("this")
    private var leased: Long = 0

    @com.google.errorprone.annotations.concurrent.GuardedBy("this")
    private val queue: java.util.Queue<PendingJob?> = java.util.PriorityQueue<PendingJob?>()

    @com.google.errorprone.annotations.concurrent.GuardedBy("this")
    private var maxLeased: Long = 0

    @com.google.errorprone.annotations.concurrent.GuardedBy("this")
    private var maxLeasedTimestamp: Instant? = null

    /**
     * Creates a meter with the given number of units.
     * 
     * @param name an identifier for this meter, for use in [.getStats]
     * @param total total number of permits that may be dispensed
     * @param clock provides the current time for [Stats.maxLeasedTimeMs]
     */
    init {
        this.name = com.google.common.base.Preconditions.checkNotNull<String>(name)
        this.clock = com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.clock.Clock>(clock)
    }

    @com.google.common.annotations.VisibleForTesting
    @kotlin.jvm.Synchronized
    fun queueSize(): Int {
        return queue.size()
    }

    /**
     * Enqueues a request for `quantity` units of the resource managed by this meter. When the
     * request is filled, the result becomes available.
     * 
     * 
     * The resource must be released either by cancelling the future or by calling [ ][Ticket.done] on the ticket after the future completes.
     * 
     * @param quantity number of units of resources to acquire
     * @param priority requests with greater priority complete earlier
     * @param ifQueued a callback to be executed if the request is queued
     * @return a future which grants resources only when it completes successfully
     */
    @kotlin.jvm.JvmOverloads
    fun request(
        quantity: Long,
        priority: Long,
        ifQueued: java.lang.Runnable = java.lang.Runnable {}
    ): com.google.common.util.concurrent.ListenableFuture<Ticket?> {
        com.google.common.base.Preconditions.checkArgument(quantity >= 0)
        val job = PendingJob(quantity, priority)
        val ticket = maybeLease(job)
        if (ticket != null) {
            setTicket(job, ticket)
        } else {
            ifQueued.run()
            synchronized(this) {
                queue.add(job)
            }
        }
        schedule()
        return job.futureTicket
    }

    @kotlin.jvm.Synchronized
    private fun maybeLease(job: PendingJob): ReleasingTicket? {
        if (leased + job.quantity > total && leased > 0) {
            return null
        }

        leased += job.quantity

        if (leased >= maxLeased) {
            maxLeased = leased
            maxLeasedTimestamp = clock.now()
        }

        return ReleasingTicket(job.quantity)
    }

    private fun setTicket(job: PendingJob, ticket: ReleasingTicket?) {
        if (!job.futureTicket.set(ticket)) {
            // The future may have been cancelled. Release immediately. If the build was interrupted, we
            // may encounter a long chain of cancelled tickets - avoid calling ticket.done() or
            // releaseAndSchedule() which would process them recursively.
            release(job.quantity)
        }
    }

    /** Statistics about a ConcurrencyMeter.  */
    @kotlin.jvm.JvmRecord
    data class Stats(
      val name: String?,
      val total: Long,
      val leased: Long,
      @kotlin.jvm.JvmField val maxLeased: Long,
      @kotlin.jvm.JvmField val maxLeasedTimeMs: Long
    )

    @get:kotlin.jvm.Synchronized
    val stats: Stats
        get() = com.google.devtools.build.lib.util.ConcurrencyMeter.Stats(
            name, total, leased, maxLeased, if (maxLeased > 0) maxLeasedTimestamp.toEpochMilli() else 0
        )

    @kotlin.jvm.Synchronized
    private fun release(quantity: Long) {
        com.google.common.base.Preconditions.checkState(
            leased >= quantity,
            "quantity (%s) > leased (%s)",
            quantity,
            leased
        )
        leased -= quantity
    }

    private fun releaseAndSchedule(quantity: Long) {
        release(quantity)
        schedule()
    }

    private fun schedule() {
        while (true) {
            val job: PendingJob?
            val ticket: ReleasingTicket?
            synchronized(this) {
                job = queue.peek()
                if (job == null || (maybeLease(job).also { ticket = it }) == null) {
                    return
                }
                queue.remove()
            }

            // Set the future outside synchronized block to avoid holding the lock when executing future's
            // callbacks which may hold other locks and call into ConcurrencyMeter causing deadlocks.
            // See: b/319411390
            setTicket(job!!, ticket)
        }
    }

    private inner class ReleasingTicket(private val quantity: Long) : Ticket {
        private val released: AtomicBoolean = AtomicBoolean(false)

        override fun done() {
            val alreadyReleased: Boolean = released.getAndSet(true)
            com.google.common.base.Preconditions.checkState(!alreadyReleased, "Already released %s units", quantity)
            releaseAndSchedule(quantity)
        }
    }

    private class PendingJob(private val quantity: Long, private val priority: Long) : Comparable<PendingJob?> {
        private val futureTicket: com.google.common.util.concurrent.SettableFuture<Ticket?> =
            com.google.common.util.concurrent.SettableFuture.create<Ticket?>()

        override fun compareTo(o: PendingJob): Int {
            return java.lang.Long.compare(o.priority, priority)
        }
    }

    /** A ticket denoting resource acquisition.  */
    interface Ticket {
        /** Releases the associated resources. Must be called exactly once.  */
        fun done()
    }
}
