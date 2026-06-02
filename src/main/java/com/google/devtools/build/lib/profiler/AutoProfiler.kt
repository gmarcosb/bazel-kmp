// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.profiler

import com.google.devtools.build.lib.clock.BlazeClock.nanoTime
import com.google.devtools.build.lib.clock.Clock.nanoTime
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A convenient way to actively get access to timing information (e.g. for logging and/or profiling
 * purposes) with minimal boilerplate. The lack of boilerplate comes at a performance cost; do not
 * use [AutoProfiler] on performance critical code.
 * 
 * 
 * The intended usage is:
 * 
 * <pre>`try (AutoProfiler p = GoogleAutoProfilerUtils.logged("<description of your code>")) {   // Your code here. } `</pre>
 * 
 * 
 * but if the try-with-resources pattern is too cumbersome, you can also do
 * 
 * <pre>`AutoProfiler p = GoogleAutoProfilerUtils.logged("<description of your code>"); // Your code here. long elapsedTimeNanos = p.completeAndGetElapsedTimeNanos(); `</pre>
 * 
 * 
 * An [AutoProfiler] can also automatically talk to the active [Profiler] instance:
 * 
 * <pre>`try (AutoProfiler p = AutoProfiler.profiled("<description of your code>")) {   // Your code here. } `</pre>
 */
class AutoProfiler private constructor(elapsedTimeReceiver: ElapsedTimeReceiver, startTimeNanos: Long) :
    com.google.devtools.build.lib.profiler.SilentCloseable {
    private val elapsedTimeReceiver: ElapsedTimeReceiver
    private val startTimeNanos: Long
    private val closed: AtomicBoolean = AtomicBoolean(false)

    init {
        this.elapsedTimeReceiver = elapsedTimeReceiver
        this.startTimeNanos = startTimeNanos
    }

    /** A opaque receiver of elapsed time information.  */
    interface ElapsedTimeReceiver {
        /**
         * Receives the elapsed time of the lifetime of an [AutoProfiler] instance.
         * 
         * 
         * Note that System#nanoTime isn't guaranteed to be non-decreasing, so implementations should
         * check for non-positive `elapsedTimeNanos` if they care about this sort of thing.
         */
        fun accept(elapsedTimeNanos: Long)
    }

    /**
     * Manually completes the profiling (useful to trigger the underlying action on completion).
     * 
     * 
     * At most one of [.complete], [.completeAndGetElapsedTimeNanos] and [.close]
     * may be called.
     */
    fun complete() {
        close()
    }

    /**
     * Manually completes the profiling and returns the elapsed time in nanoseconds.
     * 
     * 
     * At most one of [.complete], [.completeAndGetElapsedTimeNanos] and [.close]
     * may be called.
     */
    fun completeAndGetElapsedTimeNanos(): Long {
        val elapsedTimeNanos: Long =
            com.google.devtools.build.lib.profiler.AutoProfiler.Companion.nanoTime() - startTimeNanos
        com.google.common.base.Preconditions.checkState(closed.compareAndSet(false, true))
        elapsedTimeReceiver.accept(elapsedTimeNanos)
        return elapsedTimeNanos
    }

    /**
     * Automatically completes the profiling.
     * 
     * 
     * At most one of [.complete], [.completeAndGetElapsedTimeNanos] and [.close]
     * may be called.
     */
    override fun close() {
        val elapsedTimeNanos: Long =
            com.google.devtools.build.lib.profiler.AutoProfiler.Companion.nanoTime() - startTimeNanos
        com.google.common.base.Preconditions.checkState(closed.compareAndSet(false, true))
        elapsedTimeReceiver.accept(elapsedTimeNanos)
    }

    internal class ProfilingElapsedTimeReceiver(
        description: String?,
        profilerTaskType: com.google.devtools.build.lib.profiler.ProfilerTask?
    ) : ElapsedTimeReceiver {
        private val startTimeNanos: Long
        private val description: String?
        private val profilerTaskType: com.google.devtools.build.lib.profiler.ProfilerTask?

        init {
            this.startTimeNanos = com.google.devtools.build.lib.profiler.AutoProfiler.Companion.nanoTime()
            this.description = description
            this.profilerTaskType = profilerTaskType
        }

        override fun accept(elapsedTimeNanos: Long) {
            if (elapsedTimeNanos > 0) {
                com.google.devtools.build.lib.profiler.AutoProfiler.Companion.profiler.logSimpleTaskDuration(
                    startTimeNanos, java.time.Duration.ofNanos(elapsedTimeNanos), profilerTaskType, description
                )
            }
        }
    }

    companion object {
        private val profiler: com.google.devtools.build.lib.profiler.Profiler =
            com.google.devtools.build.lib.profiler.Profiler.Companion.instance()

        @kotlin.concurrent.Volatile
        private var clockForTesting: com.google.devtools.build.lib.clock.Clock? = null

        /** Sets the clock to use for testing. By default, [Profiler.nanoTimeMaybe] is used.  */
        @com.google.common.annotations.VisibleForTesting
        fun setClock(clock: com.google.devtools.build.lib.clock.Clock?) {
            com.google.devtools.build.lib.profiler.AutoProfiler.Companion.clockForTesting = clock
        }

        private fun nanoTime(): Long {
            return if (com.google.devtools.build.lib.profiler.AutoProfiler.Companion.clockForTesting != null) com.google.devtools.build.lib.profiler.AutoProfiler.Companion.clockForTesting.nanoTime() else com.google.devtools.build.lib.profiler.AutoProfiler.Companion.profiler.nanoTimeMaybe()
        }

        /**
         * Returns an [AutoProfiler] that, when closed, records the elapsed time using [ ].
         * 
         * 
         * The returned [AutoProfiler] is thread-safe.
         */
        fun profiled(
            description: String?,
            profilerTaskType: com.google.devtools.build.lib.profiler.ProfilerTask?
        ): AutoProfiler {
            return com.google.devtools.build.lib.profiler.AutoProfiler.Companion.create(
                com.google.devtools.build.lib.profiler.AutoProfiler.ProfilingElapsedTimeReceiver(
                    description,
                    profilerTaskType
                )
            )
        }

        /**
         * Returns an [AutoProfiler] that, when closed, invokes the given
         * [ElapsedTimeReceiver].
         * 
         * 
         * The returned [AutoProfiler] is as thread-safe as the given
         * [ElapsedTimeReceiver] is.
         */
        @kotlin.jvm.JvmStatic
        fun create(elapsedTimeReceiver: ElapsedTimeReceiver): AutoProfiler {
            return com.google.devtools.build.lib.profiler.AutoProfiler(
                elapsedTimeReceiver,
                com.google.devtools.build.lib.profiler.AutoProfiler.Companion.nanoTime()
            )
        }
    }
}
