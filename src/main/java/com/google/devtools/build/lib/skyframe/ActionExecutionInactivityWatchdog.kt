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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.ActionExecutionStatusReporter

/**
 * An object that can monitor whether actions are getting completed in a timely manner.
 * 
 * 
 * If there's nothing happening for a while, a background thread will print (and update) the
 * "Still waiting for N actions to complete..." message.
 */
class ActionExecutionInactivityWatchdog @com.google.common.annotations.VisibleForTesting constructor(
    monitor: InactivityMonitor?,
    reporter: InactivityReporter?,
    progressIntervalFlagValue: Int,
    sleeper: Sleep?,
    clock: com.google.devtools.build.lib.clock.Clock
) {
    /** An object used in monitoring action execution inactivity.  */
    interface InactivityMonitor {
        /** Returns whether action execution has started.  */
        fun hasStarted(): Boolean

        /** Returns the number of enqueued but not yet completed actions.  */
        val pending: Int

        /**
         * Waits for any action to complete, or the timeout to elapse.
         * 
         * 
         * The thread must wait at least for the specified timeout, unless some action completes in
         * the meantime. It's not allowed to return 0 too early.
         * 
         * 
         * Note that it's acceptable to return (any value) later than specified by the timeout.
         * 
         * @return the number of actions completed during the wait
         */
        @Throws(java.lang.InterruptedException::class)
        fun waitForNextCompletion(timeoutSeconds: Int): Int
    }

    /** An object that the watchdog can report inactivity to.  */
    interface InactivityReporter {
        /**
         * Report that actions are not getting completed in a timely manner.
         * 
         * 
         * Inactivity is typically not reported if tests with streaming output are being run.
         * 
         * @param lastActionCompletedAt the last time an action completed
         */
        fun maybeReportInactivity(lastActionCompletedAt: Instant?)
    }

    @com.google.common.annotations.VisibleForTesting
    internal interface Sleep {
        @Throws(java.lang.InterruptedException::class)
        fun sleep(durationMilliseconds: Int)
    }

    private class WaitTime(private val progressIntervalFlagValue: Int) {
        private var prev = 0

        fun reset() {
            prev = 0
        }

        fun next(): Int {
            prev = ActionExecutionStatusReporter.getWaitTime(progressIntervalFlagValue, prev)
            return prev
        }
    }

    private val isRunning: AtomicBoolean = AtomicBoolean(false)
    private val monitor: InactivityMonitor
    private val reporter: InactivityReporter
    private val sleeper: Sleep
    private val thread: java.lang.Thread
    private val waitTime: WaitTime
    private val clock: com.google.devtools.build.lib.clock.Clock

    constructor(monitor: InactivityMonitor?, reporter: InactivityReporter?, progressIntervalFlagValue: Int) : this(
        monitor,
        reporter,
        progressIntervalFlagValue,
        object : Sleep {
            @Throws(java.lang.InterruptedException::class)
            override fun sleep(durationMilliseconds: Int) {
                java.lang.Thread.sleep(durationMilliseconds.toLong())
            }
        },
        com.google.devtools.build.lib.clock.BlazeClock.instance()
    )

    init {
        this.monitor = com.google.common.base.Preconditions.checkNotNull<InactivityMonitor>(monitor)
        this.reporter = com.google.common.base.Preconditions.checkNotNull<InactivityReporter>(reporter)
        this.sleeper = com.google.common.base.Preconditions.checkNotNull<Sleep>(sleeper)
        this.waitTime = WaitTime(progressIntervalFlagValue)
        this.thread = java.lang.Thread(java.lang.Runnable { enterWatchdogLoop() }, "action-execution-watchdog")
        this.thread.setDaemon(true)
        this.clock = clock
    }

    /** Starts the watchdog thread. This method should only be called once.  */
    fun start() {
        com.google.common.base.Preconditions.checkState(!isRunning.getAndSet(true))
        thread.start()
    }

    /**
     * Stops the watchdog thread. This method should only be called once.
     * 
     * 
     * The method waits for the thread to terminate. If the caller thread is interrupted in the
     * meantime, the interrupted status will be set.
     */
    fun stop() {
        com.google.common.base.Preconditions.checkState(isRunning.getAndSet(false))
        thread.interrupt()
        try {
            thread.join()
        } catch (e: java.lang.InterruptedException) {
            // When Thread.join throws, the interrupted status is cleared. We need to set it again.
            java.lang.Thread.currentThread().interrupt()
        }
    }

    private fun enterWatchdogLoop() {
        var lastActionCompletedAt: Instant? = clock.now()
        while (isRunning.get()) {
            try {
                // Wait a while for any SkyFunction to finish. The returned number indicates how many
                // actions completed during the wait. It's possible that this is more than 1, since
                // this thread may not immediately regain control.
                val completedActions = monitor.waitForNextCompletion(waitTime.next())
                if (!isRunning.get()) {
                    break
                }

                val pending = monitor.pending
                if (!monitor.hasStarted() || completedActions > 0 || pending == 0) {
                    // If no keys have been enqueued yet (execution hasn't started), or some actions
                    // were completed since this thread was notified (we are making visible progress),
                    // or there are currently no enqueued actions waiting to be processed (perhaps all
                    // have completed and we are about to stop monitoring), then there's no need to
                    // display any messages.
                    waitTime.reset()

                    lastActionCompletedAt = clock.now()

                    // Sleep a while before checking again. Actions might be executing at a nice rate, no
                    // need to worry about inactivity. This extra sleep isn't required but it's nice to
                    // have: without it we would, at times of high action completion rate, unnecessarily
                    // put the monitor into a fast sleep-wake cycle --- not a big problem but wasteful.
                    sleeper.sleep(1000)
                } else {
                    // If actions are executing but we haven't made any progress in a while (no new
                    // action completion), then reassure the user that we're still running. Next time
                    // wait a little longer.
                    reporter.maybeReportInactivity(lastActionCompletedAt)
                }
            } catch (ie: java.lang.InterruptedException) {
                java.lang.Thread.currentThread().interrupt()
                return
            }
        }
    }
}
