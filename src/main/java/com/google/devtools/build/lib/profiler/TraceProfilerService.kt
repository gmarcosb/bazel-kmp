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
package com.google.devtools.build.lib.profiler

import java.io.IOException
import java.util.UUID

/**
 * Interface for the Blaze internal profiler. Provides facility to report various Blaze tasks and
 * store them (asynchronously) in the file for future analysis.
 * 
 * 
 * Implemented as singleton so any caller should use Profiler.instance() to obtain reference.
 * 
 * 
 * Internally, profiler uses two data structures - ThreadLocal task stack to track nested tasks
 * and single ConcurrentLinkedQueue to gather all completed tasks.
 * 
 * 
 * Also, due to the nature of the provided functionality (instrumentation of all Blaze
 * components), build.lib.profiler package will be used by almost every other Blaze package, so
 * special attention should be paid to avoid any dependencies on the rest of the Blaze code,
 * including build.lib.util and build.lib.vfs. This is important because build.lib.util and
 * build.lib.vfs contain Profiler invocations and any dependency on those two packages would create
 * circular relationship.
 * 
 * @see ProfilerTask enum for recognized task types.
 */
@com.google.devtools.build.lib.skybridge.SkybridgeInterface
// This code is very performance sensitive.
interface TraceProfilerService : com.google.devtools.build.lib.runtime.BlazeService {
    /** File format enum.  */
    enum class Format {
        JSON_TRACE_FILE_FORMAT,
        JSON_TRACE_FILE_COMPRESSED_FORMAT
    }

    /** Returns the nanoTime of the current profiler instance, or -1 if not active.  */
    fun nanoTimeMaybe(): Long

    /** Returns true iff profiling is currently enabled.  */
    fun isActive(): Boolean

    /**
     * Records the beginning of a task as specified, and returns a [SilentCloseable] instance
     * that ends the task. This lets the system do the work of ending the task, with the compiler
     * giving a warning if the returned instance is not closed.
     * 
     * 
     * Use of this method allows to support nested task monitoring. For tasks that are known to not
     * have any subtasks, logSimpleTask() should be used instead.
     * 
     * 
     * Use like this:
     * 
     * <pre>`try (SilentCloseable c = Profiler.instance().profile(type, "description")) {   // Your code here. } `</pre>
     * 
     * @param type predefined task type - see ProfilerTask for available types.
     * @param description task description. May be stored until the end of the build.
     */
    fun profile(
        type: com.google.devtools.build.lib.profiler.ProfilerTask?,
        description: String?
    ): com.google.devtools.build.lib.profiler.SilentCloseable?

    /**
     * Version of [.profile] that avoids creating string unless actually
     * profiling.
     */
    fun profile(
        type: com.google.devtools.build.lib.profiler.ProfilerTask?,
        description: java.util.function.Supplier<String?>?
    ): com.google.devtools.build.lib.profiler.SilentCloseable?

    /**
     * Records the beginning of a task as specified, and returns a [SilentCloseable] instance
     * that ends the task. This lets the system do the work of ending the task, with the compiler
     * giving a warning if the returned instance is not closed.
     * 
     * 
     * Use of this method allows to support nested task monitoring. For tasks that are known to not
     * have any subtasks, logSimpleTask() should be used instead.
     * 
     * 
     * This is a convenience method that uses [ProfilerTask.INFO].
     * 
     * 
     * Use like this:
     * 
     * <pre>`try (SilentCloseable c = Profiler.instance().profile("description")) {   // Your code here. } `</pre>
     * 
     * @param description task description. May be stored until the end of the build.
     */
    fun profile(description: String?): com.google.devtools.build.lib.profiler.SilentCloseable?

    /**
     * Used externally to submit simple task (one that does not have any subtasks). Depending on the
     * minDuration attribute of the task type, task may be just aggregated into the parent task and
     * not stored directly.
     * 
     * @param startTimeNanos task start time (obtained through [Profiler.nanoTimeMaybe])
     * @param type task type
     * @param description task description. May be stored until the end of the build.
     */
    fun logSimpleTask(
        startTimeNanos: Long,
        type: com.google.devtools.build.lib.profiler.ProfilerTask?,
        description: String?
    )

    /**
     * Used externally to submit simple task (one that does not have any subtasks). Depending on the
     * minDuration attribute of the task type, task may be just aggregated into the parent task and
     * not stored directly.
     * 
     * 
     * Note that start and stop time must both be acquired from the same clock instance.
     * 
     * @param startTimeNanos task start time
     * @param stopTimeNanos task stop time
     * @param type task type
     * @param description task description. May be stored until the end of the build.
     */
    fun logSimpleTask(
        startTimeNanos: Long,
        stopTimeNanos: Long,
        type: com.google.devtools.build.lib.profiler.ProfilerTask?,
        description: String?
    )

    /**
     * Used externally to submit simple task (one that does not have any subtasks). Depending on the
     * minDuration attribute of the task type, task may be just aggregated into the parent task and
     * not stored directly.
     * 
     * @param startTimeNanos task start time (obtained through [Profiler.nanoTimeMaybe])
     * @param duration the duration of the task
     * @param type task type
     * @param description task description. May be stored until the end of the build.
     */
    fun logSimpleTaskDuration(
        startTimeNanos: Long,
        duration: java.time.Duration?,
        type: com.google.devtools.build.lib.profiler.ProfilerTask?,
        description: String?
    )

    /** Used to log "events" happening at a specific time - tasks with zero duration.  */
    fun logEventAtTime(
        atTimeNanos: Long,
        type: com.google.devtools.build.lib.profiler.ProfilerTask?,
        description: String?
    )

    /** Used to log "events" - tasks with zero duration.  */
    fun logEvent(type: com.google.devtools.build.lib.profiler.ProfilerTask?, description: String?)

    /** Sets the heuristics for determining the filesystem type of a given path.  */
    fun setVfsTypeHeuristics(vfsTypeHeuristics: MutableMap<String?, out java.util.function.Predicate<in String?>?>?)

    /**
     * Enable profiling.
     * 
     * 
     * Subsequent calls to beginTask/endTask will be recorded in the provided output stream. Please
     * note that stream performance is extremely important and buffered streams should be utilized.
     * 
     * @param profiledTasks which of [ProfilerTask]s to track
     * @param stream output stream to store profile data. Note: passing unbuffered stream object
     * reference may result in significant performance penalties
     * @param recordAllDurations iff true, record all tasks regardless of their duration; otherwise
     * some tasks may get aggregated if they finished quick enough
     * @param clock a `BlazeClock.instance()`
     * @param execStartTimeNanos execution start time in nanos obtained from `clock.nanoTime()`
     */
    @Throws(IOException::class)
    fun start(
        profiledTasks: MutableSet<com.google.devtools.build.lib.profiler.ProfilerTask?>?,
        stream: java.io.OutputStream?,
        format: Format?,
        outputBase: String?,
        buildID: UUID?,
        recordAllDurations: Boolean,
        clock: com.google.devtools.build.lib.clock.Clock?,
        execStartTimeNanos: Long,
        slimProfile: Boolean,
        includePrimaryOutput: Boolean,
        includeTargetLabel: Boolean,
        includeConfiguration: Boolean,
        collectTaskHistograms: Boolean
    )

    /**
     * Disable profiling and complete profile file creation. Subsequent calls to beginTask/endTask
     * will no longer be recorded in the profile.
     */
    @Throws(IOException::class)
    fun stop()

    /**
     * Clears the records the profiler instance keeps.
     * 
     * 
     * Should always be called between a [.stop] and a subsequent [.start].
     */
    fun clear()

    /**
     * Returns task histograms. This must be called between calls to [.start] and [.stop],
     * or the returned recorders are all empty. Note that the returned recorders may still be modified
     * concurrently (but at least they are thread-safe, so that's good).
     * 
     * 
     * The stat recorders are indexed by `ProfilerTask#ordinal`. //TODO(b/458037154): Maybe
     * make the enums stable.
     */
    fun getTasksHistograms(): MutableList<com.google.devtools.build.lib.profiler.StatRecorder?>?

    /**
     * Returns task histograms. This must be called between calls to [.start] and [.stop],
     * or the returned list is empty.
     */
    fun getSlowestTasks(): Iterable<com.google.devtools.build.lib.profiler.SlowTask?>?

    fun isProfiling(type: com.google.devtools.build.lib.profiler.ProfilerTask?): Boolean

    /** Convenience method to log phase marker tasks.  */
    @Throws(java.lang.InterruptedException::class)
    fun markPhase(phase: com.google.devtools.build.lib.profiler.ProfilePhase?)

    /**
     * Similar to [.profile], but specific to action-related events. Takes an extra argument:
     * primaryOutput.
     */
    fun profileAction(
        type: com.google.devtools.build.lib.profiler.ProfilerTask?,
        mnemonic: String?,
        description: String?,
        primaryOutput: String?,
        targetLabel: String?,
        configuration: String?
    ): com.google.devtools.build.lib.profiler.SilentCloseable?

    /**
     * Records the end of a task as specified.
     * 
     * @param startTimeNanos task start time
     * @param type task type
     * @param description task description
     */
    fun completeTask(
        startTimeNanos: Long,
        type: com.google.devtools.build.lib.profiler.ProfilerTask?,
        description: String?
    )

    fun registerCounterSeriesCollector(collector: com.google.devtools.build.lib.profiler.CounterSeriesCollector?)

    fun unregisterCounterSeriesCollector(collector: com.google.devtools.build.lib.profiler.CounterSeriesCollector?)

    /** Adds a whole action count series to the writer bypassing histogram and subtask creation.  */
    fun logCounters(
        counterSeriesMap: MutableMap<com.google.devtools.build.lib.profiler.CounterSeriesTask?, DoubleArray?>?,
        profileStart: java.time.Duration?,
        bucketDuration: java.time.Duration?
    )

    fun getProfileElapsedTime(): java.time.Duration?

    fun getServerProcessCpuTime(): java.time.Duration?

    /**
     * Profiles a future.
     * 
     * @param future the future to profile
     * @param prefix the prefix of the virtual lanes. Similar to the thread name prefix.
     * @param type task type
     * @param description task description. May be stored until the end of the build.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun <T> profileFuture(
        future: com.google.common.util.concurrent.ListenableFuture<T?>?,
        prefix: String?,
        type: com.google.devtools.build.lib.profiler.ProfilerTask?,
        description: String?
    ): com.google.common.util.concurrent.ListenableFuture<T?>?

    /**
     * Creates a profiler that can be used to profile async operations of a task.
     * 
     * @param prefix the prefix of the virtual lanes. Similar to the thread name prefix.
     * @param description the description of task.
     */
    fun profileAsync(prefix: String?, description: String?): com.google.devtools.build.lib.profiler.AsyncProfiler?

    /** Creates a time series with the given start time and bucket duration.  */
    fun createTimeSeries(
        startTime: java.time.Duration?,
        bucketDuration: java.time.Duration?
    ): com.google.devtools.build.lib.profiler.TimeSeries?
}
