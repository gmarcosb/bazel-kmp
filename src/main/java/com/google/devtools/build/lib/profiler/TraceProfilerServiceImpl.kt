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
package com.google.devtools.build.lib.profiler

import com.google.devtools.build.lib.collect.Extrema

/** Blaze internal profiler implementation.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
// This code is very performance sensitive.
class TraceProfilerServiceImpl : com.google.devtools.build.lib.profiler.TraceProfilerService {
    /**
     * Aggregator class that keeps track of the slowest tasks of the specified type.
     * 
     * 
     * `extremaAggregators` is sharded so that all threads need not compete for the same
     * lock if they do the same operation at the same time. Access to an individual [Extrema]
     * is synchronized on the [Extrema] instance itself.
     */
    private class SlowestTaskAggregator {
        private val extremaAggregators: Array<Extrema<com.google.devtools.build.lib.profiler.SlowTask?>> =
            arrayOfNulls<Extrema>(
                SHARDS
            )

        init {
            for (i in 0..<SHARDS) {
                extremaAggregators[i] = Extrema.max(SIZE)
            }
        }

        // @ThreadSafe
        fun add(taskData: TaskData) {
            val extrema: Extrema<com.google.devtools.build.lib.profiler.SlowTask?> =
                extremaAggregators[(taskData.threadId % SHARDS).toInt()]
            synchronized(extrema) {
                extrema.aggregate(
                    com.google.devtools.build.lib.profiler.SlowTask(
                        taskData.durationNanos,
                        taskData.description,
                        taskData.type
                    )
                )
            }
        }

        // @ThreadSafe
        fun clear() {
            for (i in 0..<SHARDS) {
                val extrema: Extrema<com.google.devtools.build.lib.profiler.SlowTask?> = extremaAggregators[i]
                synchronized(extrema) {
                    extrema.clear()
                }
            }
        }

        // @ThreadSafe
        fun getSlowestTasks(): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.profiler.SlowTask?> {
            // This is slow, but since it only happens during the end of the invocation, it's OK.
            val mergedExtrema: Extrema<com.google.devtools.build.lib.profiler.SlowTask?> = Extrema.max(SIZE)
            for (i in 0..<SHARDS) {
                val extrema: Extrema<com.google.devtools.build.lib.profiler.SlowTask?> = extremaAggregators[i]
                synchronized(extrema) {
                    for (task in extrema.extremeElements) {
                        mergedExtrema.aggregate(task)
                    }
                }
            }
            return mergedExtrema.extremeElements
        }

        companion object {
            private const val SHARDS = 16
            private const val SIZE = 30
        }
    }

    private var clock: com.google.devtools.build.lib.clock.Clock? = null
    private var profiledTasks: MutableSet<com.google.devtools.build.lib.profiler.ProfilerTask?>? = null

    @kotlin.concurrent.Volatile
    private var active = false

    @kotlin.concurrent.Volatile
    private var recordAllDurations = false
    private var profileCpuStartTime: java.time.Duration = java.time.Duration.ZERO
    private var profileCpuEndTime: java.time.Duration = java.time.Duration.ZERO
    private var profileStartTime: java.time.Duration = java.time.Duration.ZERO
    private var profileEndTime: java.time.Duration = java.time.Duration.ZERO

    /** Heuristics for determining the filesystem type of a given path.  */
    private var vfsTypeHeuristics: com.google.common.collect.ImmutableMap<String?, out java.util.function.Predicate<in String?>?> =
        DEFAULT_VFS_TYPE_HEURISTICS

    /**
     * The reference to the current writer, if any. If the referenced writer is null, then disk writes
     * are disabled. This can happen when slowest task recording is enabled.
     */
    private val writerRef: AtomicReference<JsonTraceFileWriter?> = AtomicReference<JsonTraceFileWriter?>()

    private val slowestTasks =
        arrayOfNulls<SlowestTaskAggregator>(com.google.devtools.build.lib.profiler.ProfilerTask.entries.toTypedArray().length)

    @com.google.common.annotations.VisibleForTesting
    val tasksHistograms: Array<com.google.devtools.build.lib.profiler.StatRecorder?> =
        arrayOfNulls<com.google.devtools.build.lib.profiler.StatRecorder>(com.google.devtools.build.lib.profiler.ProfilerTask.entries.toTypedArray().length)

    /** Collects local cpu usage data (if enabled).  */
    private val resourceCollector: ResourceCollector = ResourceCollector()

    private val actionCountTimeSeriesRef: AtomicReference<com.google.devtools.build.lib.profiler.TimeSeries?> =
        AtomicReference<com.google.devtools.build.lib.profiler.TimeSeries?>()
    private val actionCacheCountTimeSeriesRef: AtomicReference<com.google.devtools.build.lib.profiler.TimeSeries?> =
        AtomicReference<com.google.devtools.build.lib.profiler.TimeSeries?>()
    private val localActionCountTimeSeriesRef: AtomicReference<com.google.devtools.build.lib.profiler.TimeSeries?> =
        AtomicReference<com.google.devtools.build.lib.profiler.TimeSeries?>()
    private val inflightRpcTimeSeriesMapRef: AtomicReference<MutableMap<String?, com.google.devtools.build.lib.profiler.TimeSeries>?> =
        AtomicReference<MutableMap<String?, com.google.devtools.build.lib.profiler.TimeSeries>?>()

    private var actionCountStartTime: java.time.Duration? = null
    private var collectTaskHistograms = false
    private var includePrimaryOutput = false
    private var includeTargetLabel = false
    private var includeConfiguration = false

    private fun initHistograms() {
        for (task in com.google.devtools.build.lib.profiler.ProfilerTask.entries) {
            if (task.isVfs()) {
                val recorders: MutableList<RecorderAndPredicate?> =
                    java.util.ArrayList<RecorderAndPredicate?>(vfsTypeHeuristics.size())
                for (e in vfsTypeHeuristics.entrySet()) {
                    recorders.add(
                        RecorderAndPredicate(
                            SingleStatRecorder(task.toString() + " " + e.getKey(), HISTOGRAM_BUCKETS),
                            e.getValue()
                        )
                    )
                }
                tasksHistograms[task.ordinal()] = PredicateBasedStatRecorder(recorders)
            } else {
                tasksHistograms[task.ordinal()] = SingleStatRecorder(task, HISTOGRAM_BUCKETS)
            }
        }
    }

    override fun globalInit(
        startupOptions: com.google.devtools.common.options.OptionsProvider?,
        blazeServices: Iterable<com.google.devtools.build.lib.runtime.BlazeService?>?
    ) {
        // This is to ensure that the profiler is available as early as possible during the server
        // startup.
        com.google.devtools.build.lib.profiler.Profiler.setTraceProfilerService(this)
    }

    // TODO(ulfjack): This returns incomplete data by design. Maybe we should return the histograms on
    // stop instead? However, this is currently only called from one location in a module, and that
    // can't call stop itself. What to do?
    @kotlin.jvm.Synchronized
    override fun getTasksHistograms(): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.profiler.StatRecorder?> {
        com.google.common.base.Preconditions.checkState(isActive())
        return com.google.common.collect.ImmutableList.copyOf<com.google.devtools.build.lib.profiler.StatRecorder?>(
            tasksHistograms
        )
    }

    override fun nanoTimeMaybe(): Long {
        // Note that we fall back to an actual clock instead of disabling nanoTime entirely if the
        // profiler is not active. This is because some callers of the profiler service may use
        // nanoTime for latency tracking even without starting the rest of the profiler features.
        return if (isActive()) clock.nanoTime() else com.google.devtools.build.lib.clock.BlazeClock.nanoTime()
    }

    override fun getProfileElapsedTime(): java.time.Duration? {
        val endTime: java.time.Duration =
            if (isActive()) java.time.Duration.ofNanos(clock.nanoTime()) else profileEndTime

        return endTime.minus(profileStartTime)
    }

    override fun getServerProcessCpuTime(): java.time.Duration? {
        val cpuEndTime: java.time.Duration = if (isActive()) getProcessCpuTime() else profileCpuEndTime
        return cpuEndTime.minus(profileCpuStartTime)
    }

    override fun setVfsTypeHeuristics(
        vfsTypeHeuristics: MutableMap<String?, out java.util.function.Predicate<in String?>?>
    ) {
        this.vfsTypeHeuristics = com.google.common.collect.ImmutableMap.copyOf(vfsTypeHeuristics)
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    override fun start(
        profiledTasks: MutableSet<com.google.devtools.build.lib.profiler.ProfilerTask?>,
        stream: java.io.OutputStream?,
        format: com.google.devtools.build.lib.profiler.TraceProfilerService.Format?,
        outputBase: String?,
        buildID: UUID?,
        recordAllDurations: Boolean,
        clock: com.google.devtools.build.lib.clock.Clock,
        execStartTimeNanos: Long,
        slimProfile: Boolean,
        includePrimaryOutput: Boolean,
        includeTargetLabel: Boolean,
        includeConfiguration: Boolean,
        collectTaskHistograms: Boolean
    ) {
        com.google.common.base.Preconditions.checkState(!active, "Profiler already active")

        initHistograms()

        this.profiledTasks =
            if (profiledTasks.isEmpty()) profiledTasks else EnumSet.copyOf<com.google.devtools.build.lib.profiler.ProfilerTask?>(
                profiledTasks
            )
        this.clock = clock
        this.actionCountStartTime = java.time.Duration.ofNanos(clock.nanoTime())
        this.actionCountTimeSeriesRef.set(
            createTimeSeries(actionCountStartTime, ACTION_COUNT_BUCKET_DURATION)
        )
        this.actionCacheCountTimeSeriesRef.set(
            createTimeSeries(actionCountStartTime, ACTION_COUNT_BUCKET_DURATION)
        )
        this.localActionCountTimeSeriesRef.set(
            createTimeSeries(actionCountStartTime, ACTION_COUNT_BUCKET_DURATION)
        )
        this.inflightRpcTimeSeriesMapRef.set(ConcurrentHashMap<String?, com.google.devtools.build.lib.profiler.TimeSeries?>())
        this.collectTaskHistograms = collectTaskHistograms
        this.includePrimaryOutput = includePrimaryOutput
        this.includeTargetLabel = includeTargetLabel
        this.includeConfiguration = includeConfiguration
        this.recordAllDurations = recordAllDurations

        var writer: JsonTraceFileWriter? = null
        if (stream != null && format != null) {
            writer =
                when (format) {
                    com.google.devtools.build.lib.profiler.TraceProfilerService.Format.JSON_TRACE_FILE_FORMAT -> JsonTraceFileWriter(
                        stream, execStartTimeNanos, slimProfile, outputBase, buildID
                    )

                    com.google.devtools.build.lib.profiler.TraceProfilerService.Format.JSON_TRACE_FILE_COMPRESSED_FORMAT -> JsonTraceFileWriter(
                        GZIPOutputStream(stream),
                        execStartTimeNanos,
                        slimProfile,
                        outputBase,
                        buildID
                    )
                }
            writer.start()
        }
        this.writerRef.set(writer)

        // Activate profiler.
        profileStartTime = java.time.Duration.ofNanos(execStartTimeNanos)
        profileCpuStartTime = getProcessCpuTime()
        active = true

        // Start collecting Bazel and system-wide CPU metric collection.
        this.resourceCollector.start()
    }

    // TODO(ulfjack): This returns incomplete data by design. Also see getTasksHistograms.
    @kotlin.jvm.Synchronized
    override fun getSlowestTasks(): Iterable<com.google.devtools.build.lib.profiler.SlowTask?> {
        val slowestTasksByType: MutableList<Iterable<com.google.devtools.build.lib.profiler.SlowTask?>?> =
            java.util.ArrayList<Iterable<com.google.devtools.build.lib.profiler.SlowTask?>?>()
        for (aggregator in slowestTasks) {
            if (aggregator != null) {
                slowestTasksByType.add(aggregator.getSlowestTasks())
            }
        }
        return com.google.common.collect.Iterables.concat<com.google.devtools.build.lib.profiler.SlowTask?>(
            slowestTasksByType
        )
    }

    private fun collectActionCounts() {
        val endTime: java.time.Duration = java.time.Duration.ofNanos(clock.nanoTime())
        val len: Int = endTime.minus(actionCountStartTime).dividedBy(ACTION_COUNT_BUCKET_DURATION).toInt() + 1
        val counterSeriesMap: MutableMap<com.google.devtools.build.lib.profiler.CounterSeriesTask?, DoubleArray?> =
            LinkedHashMap<com.google.devtools.build.lib.profiler.CounterSeriesTask?, DoubleArray?>()
        val actionCountTimeSeries: com.google.devtools.build.lib.profiler.TimeSeries? = actionCountTimeSeriesRef.get()
        if (actionCountTimeSeries != null) {
            val actionCountValues: DoubleArray? = actionCountTimeSeries.toDoubleArray(len)
            actionCountTimeSeriesRef.set(null)
            counterSeriesMap.put(
                com.google.devtools.build.lib.profiler.CounterSeriesTask("action count", "action",  /* color= */null),
                actionCountValues
            )
        }
        val actionCacheCountTimeSeries: com.google.devtools.build.lib.profiler.TimeSeries? =
            actionCacheCountTimeSeriesRef.get()
        if (actionCacheCountTimeSeries != null) {
            val actionCacheCountValues: DoubleArray? = actionCacheCountTimeSeries.toDoubleArray(len)
            actionCacheCountTimeSeriesRef.set(null)
            counterSeriesMap.put(
                com.google.devtools.build.lib.profiler.CounterSeriesTask(
                    "action cache count",
                    "local action cache",  /* color= */
                    null
                ),
                actionCacheCountValues
            )
        }
        if (!counterSeriesMap.isEmpty()) {
            logCounters(counterSeriesMap, actionCountStartTime, ACTION_COUNT_BUCKET_DURATION)
        }

        val localCounterSeriesMap: MutableMap<com.google.devtools.build.lib.profiler.CounterSeriesTask?, DoubleArray?> =
            LinkedHashMap<com.google.devtools.build.lib.profiler.CounterSeriesTask?, DoubleArray?>()
        val localActionCountTimeSeries: com.google.devtools.build.lib.profiler.TimeSeries? =
            localActionCountTimeSeriesRef.get()
        if (localActionCountTimeSeries != null) {
            val localActionCountValues: DoubleArray? = localActionCountTimeSeries.toDoubleArray(len)
            localActionCountTimeSeriesRef.set(null)
            localCounterSeriesMap.put(
                com.google.devtools.build.lib.profiler.CounterSeriesTask(
                    "action count (local)",
                    "local action",
                    com.google.devtools.build.lib.profiler.CounterSeriesTask.Color.DETAILED_MEMORY_DUMP
                ),
                localActionCountValues
            )
        }
        if (hasNonZeroValues(localCounterSeriesMap)) {
            logCounters(localCounterSeriesMap, actionCountStartTime, ACTION_COUNT_BUCKET_DURATION)
        }

        val inflightRpcTimeSeriesMap: MutableMap<String?, com.google.devtools.build.lib.profiler.TimeSeries>? =
            inflightRpcTimeSeriesMapRef.getAndSet(null)
        if (inflightRpcTimeSeriesMap != null) {
            for (entry in inflightRpcTimeSeriesMap.entrySet()) {
                val inflightRpcCounterSeriesMap: MutableMap<com.google.devtools.build.lib.profiler.CounterSeriesTask?, DoubleArray?> =
                    LinkedHashMap<com.google.devtools.build.lib.profiler.CounterSeriesTask?, DoubleArray?>()
                val name: String? = entry.getKey()
                val timeSeries: com.google.devtools.build.lib.profiler.TimeSeries = entry.getValue()
                val values: DoubleArray? = timeSeries.toDoubleArray(len)
                inflightRpcCounterSeriesMap.put(
                    com.google.devtools.build.lib.profiler.CounterSeriesTask(
                        "Inflight RPCs - " + name,
                        name,  /* color= */
                        null
                    ), values
                )
                logCounters(
                    inflightRpcCounterSeriesMap, actionCountStartTime, ACTION_COUNT_BUCKET_DURATION
                )
            }
        }
    }

    private fun hasNonZeroValues(countersSeriesMap: MutableMap<com.google.devtools.build.lib.profiler.CounterSeriesTask?, DoubleArray?>): Boolean {
        return countersSeriesMap.values().stream()
            .flatMapToDouble(java.util.function.Function { array: DoubleArray? -> java.util.Arrays.stream(array) })
            .anyMatch(DoublePredicate { v: Double -> v != 0.0 })
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    override fun stop() {
        if (!active) {
            return
        }
        collectActionCounts()
        resourceCollector.stop()
        // Log a final event to update the duration of ProfilePhase.FINISH.
        logEvent(com.google.devtools.build.lib.profiler.ProfilerTask.INFO, "Finishing")
        try {
            var writer: JsonTraceFileWriter? = writerRef.getAndSet(null)
            if (writer != null) {
                writer.shutdown()
                writer = null
            }
        } finally {
            profileCpuEndTime = getProcessCpuTime()
            profileEndTime = java.time.Duration.ofNanos(clock.nanoTime())
            active = false
        }
    }

    @kotlin.jvm.Synchronized
    override fun clear() {
        com.google.common.base.Preconditions.checkState(!active)
        java.util.Arrays.fill(tasksHistograms, null)
        profileStartTime = java.time.Duration.ZERO
        profileEndTime = java.time.Duration.ZERO
        profileCpuStartTime = java.time.Duration.ZERO
        profileCpuEndTime = java.time.Duration.ZERO
        for (aggregator in slowestTasks) {
            if (aggregator != null) {
                aggregator.clear()
            }
        }
        multiLaneGenerator.reset()
    }

    override fun isActive(): Boolean {
        return active
    }

    override fun isProfiling(type: com.google.devtools.build.lib.profiler.ProfilerTask?): Boolean {
        return profiledTasks!!.contains(type)
    }

    /**
     * Unless --record_full_profiler_data is given we drop small tasks and add their time to the
     * parents duration.
     */
    private fun wasTaskSlowEnoughToRecord(
        type: com.google.devtools.build.lib.profiler.ProfilerTask,
        duration: Long
    ): Boolean {
        return (recordAllDurations || duration >= type.minDuration)
    }

    override fun registerCounterSeriesCollector(collector: com.google.devtools.build.lib.profiler.CounterSeriesCollector?) {
        resourceCollector.registerCounterSeriesCollector(collector)
    }

    override fun unregisterCounterSeriesCollector(collector: com.google.devtools.build.lib.profiler.CounterSeriesCollector?) {
        resourceCollector.unregisterCounterSeriesCollector(collector)
    }

    override fun logCounters(
        counterSeriesMap: MutableMap<com.google.devtools.build.lib.profiler.CounterSeriesTask?, DoubleArray?>,
        profileStart: java.time.Duration?,
        bucketDuration: java.time.Duration?
    ) {
        val currentWriter: JsonTraceFileWriter? = writerRef.get()
        if (isActive() && currentWriter != null) {
            val counterSeriesTraceData: CounterSeriesTraceData =
                CounterSeriesTraceData(counterSeriesMap, profileStart, bucketDuration)
            currentWriter.enqueue(counterSeriesTraceData)
        }
    }

    /**
     * Adds task directly to the main queue bypassing task stack. Used for simple tasks that are known
     * to not have any subtasks.
     * 
     * @param startTimeNanos task start time (obtained through [Profiler.nanoTimeMaybe])
     * @param duration task duration
     * @param type task type
     * @param description task description. May be stored until end of build.
     */
    private fun logTask(
        startTimeNanos: Long,
        duration: Long,
        type: com.google.devtools.build.lib.profiler.ProfilerTask,
        description: String?
    ) {
        var duration = duration
        val lane = borrowLane()
        try {
            com.google.common.base.Preconditions.checkNotNull<String?>(description)
            com.google.common.base.Preconditions.checkState(!description.isEmpty(), "No description -> not helpful")
            if (duration < 0) {
                // See note in Clock#nanoTime, which is used by Profiler#nanoTimeMaybe.
                duration = 0
            }

            val statRecorder: com.google.devtools.build.lib.profiler.StatRecorder? = tasksHistograms[type.ordinal()]
            if (collectTaskHistograms && statRecorder != null) {
                statRecorder.addStat(java.time.Duration.ofNanos(duration).toMillis().toInt(), description)
            }

            if (isActive() && startTimeNanos >= 0 && isProfiling(type)) {
                // Store instance fields as local variables so they are not nulled out from under us by
                // #clear.
                val currentWriter: JsonTraceFileWriter? = writerRef.get()
                if (wasTaskSlowEnoughToRecord(type, duration)) {
                    val data: TaskData = TaskData(getLaneId(lane), startTimeNanos, type, description)
                    data.durationNanos = duration
                    if (currentWriter != null) {
                        currentWriter.enqueue(data)
                    }

                    val aggregator = slowestTasks[type.ordinal()]

                    if (aggregator != null) {
                        aggregator.add(data)
                    }
                }
            }
        } finally {
            releaseLane(lane)
        }
    }

    override fun logSimpleTask(
        startTimeNanos: Long,
        type: com.google.devtools.build.lib.profiler.ProfilerTask,
        description: String?
    ) {
        if (clock != null) {
            logTask(startTimeNanos, clock.nanoTime() - startTimeNanos, type, description)
        }
    }

    override fun logSimpleTask(
        startTimeNanos: Long,
        stopTimeNanos: Long,
        type: com.google.devtools.build.lib.profiler.ProfilerTask,
        description: String?
    ) {
        logTask(startTimeNanos, stopTimeNanos - startTimeNanos, type, description)
    }

    override fun logSimpleTaskDuration(
        startTimeNanos: Long,
        duration: java.time.Duration,
        type: com.google.devtools.build.lib.profiler.ProfilerTask,
        description: String?
    ) {
        logTask(startTimeNanos, duration.toNanos(), type, description)
    }

    override fun logEventAtTime(
        atTimeNanos: Long,
        type: com.google.devtools.build.lib.profiler.ProfilerTask,
        description: String?
    ) {
        logTask(atTimeNanos, 0, type, description)
    }

    override fun logEvent(type: com.google.devtools.build.lib.profiler.ProfilerTask, description: String?) {
        logEventAtTime(clock.nanoTime(), type, description)
    }

    private fun reallyProfile(
        type: com.google.devtools.build.lib.profiler.ProfilerTask,
        description: String?
    ): com.google.devtools.build.lib.profiler.SilentCloseable {
        val startTimeNanos: Long = clock.nanoTime()
        val lane = borrowLane()
        return com.google.devtools.build.lib.profiler.SilentCloseable {
            try {
                completeTask(getLaneId(lane), startTimeNanos, type, description)
            } finally {
                releaseLane(lane)
            }
        }
    }

    override fun profile(
        type: com.google.devtools.build.lib.profiler.ProfilerTask,
        description: String?
    ): com.google.devtools.build.lib.profiler.SilentCloseable {
        return if (isActive() && isProfiling(type)) reallyProfile(type, description) else NOP
    }

    override fun profile(
        type: com.google.devtools.build.lib.profiler.ProfilerTask,
        description: java.util.function.Supplier<String?>
    ): com.google.devtools.build.lib.profiler.SilentCloseable {
        return if (isActive() && isProfiling(type)) reallyProfile(type, description.get()) else NOP
    }

    override fun profile(description: String?): com.google.devtools.build.lib.profiler.SilentCloseable {
        return profile(com.google.devtools.build.lib.profiler.ProfilerTask.INFO, description)
    }

    override fun profileAction(
        type: com.google.devtools.build.lib.profiler.ProfilerTask,
        mnemonic: String?,
        description: String?,
        primaryOutput: String?,
        targetLabel: String?,
        configuration: String?
    ): com.google.devtools.build.lib.profiler.SilentCloseable {
        com.google.common.base.Preconditions.checkNotNull<String?>(description)
        if (isActive() && isProfiling(type)) {
            val startTimeNanos: Long = clock.nanoTime()
            val lane = borrowLane()
            return com.google.devtools.build.lib.profiler.SilentCloseable {
                try {
                    completeAction(
                        getLaneId(lane),
                        startTimeNanos,
                        type,
                        description,
                        mnemonic,
                        if (includePrimaryOutput) primaryOutput else null,
                        if (includeTargetLabel) targetLabel else null,
                        if (includeConfiguration) configuration else null
                    )
                } finally {
                    releaseLane(lane)
                }
            }
        } else {
            return NOP
        }
    }

    private fun countAction(type: com.google.devtools.build.lib.profiler.ProfilerTask?): Boolean {
        return type == com.google.devtools.build.lib.profiler.ProfilerTask.ACTION || type == com.google.devtools.build.lib.profiler.ProfilerTask.DISCOVER_INPUTS
    }

    override fun completeTask(
        startTimeNanos: Long,
        type: com.google.devtools.build.lib.profiler.ProfilerTask,
        description: String?
    ) {
        val lane = borrowLane()
        try {
            completeTask(getLaneId(lane), startTimeNanos, type, description)
        } finally {
            releaseLane(lane)
        }
    }

    private fun completeTask(
        laneId: Long,
        startTimeNanos: Long,
        type: com.google.devtools.build.lib.profiler.ProfilerTask,
        description: String?
    ) {
        if (isActive()) {
            val endTimeNanos: Long = clock.nanoTime()
            val duration = endTimeNanos - startTimeNanos
            if (wasTaskSlowEnoughToRecord(type, duration)) {
                recordTask(TaskData(laneId, startTimeNanos, duration, type, description))
            }

            if (type == com.google.devtools.build.lib.profiler.ProfilerTask.RPC) {
                val inflightRpcTimeSerieMap: MutableMap<String?, com.google.devtools.build.lib.profiler.TimeSeries>? =
                    inflightRpcTimeSeriesMapRef.get()
                if (inflightRpcTimeSerieMap != null) {
                    val timeSeries: com.google.devtools.build.lib.profiler.TimeSeries =
                        inflightRpcTimeSerieMap.computeIfAbsent(
                            description,
                            java.util.function.Function { unused: String? ->
                                createTimeSeries(
                                    actionCountStartTime,
                                    ACTION_COUNT_BUCKET_DURATION
                                )
                            })
                    timeSeries.addRange(
                        java.time.Duration.ofNanos(startTimeNanos),
                        java.time.Duration.ofNanos(endTimeNanos)
                    )
                }
            }
        }
    }

    private fun completeAction(
        threadId: Long,
        startTimeNanos: Long,
        type: com.google.devtools.build.lib.profiler.ProfilerTask,
        description: String?,
        mnemonic: String?,
        primaryOutput: String?,
        targetLabel: String?,
        configuration: String?
    ) {
        if (isActive()) {
            val endTimeNanos: Long = clock.nanoTime()
            val duration = endTimeNanos - startTimeNanos
            val shouldRecordTask = wasTaskSlowEnoughToRecord(type, duration)
            if (shouldRecordTask) {
                recordTask(
                    ActionTaskData(
                        threadId,
                        startTimeNanos,
                        duration,
                        type,
                        mnemonic,
                        description,
                        primaryOutput,
                        targetLabel,
                        configuration
                    )
                )
            }
        }
    }

    private fun recordTask(data: TaskData) {
        val writer: JsonTraceFileWriter? = writerRef.get()
        if (writer != null) {
            writer.enqueue(data)
        }
        val endTimeNanos: Long = data.startTimeNanos + data.durationNanos
        val actionCountTimeSeries: com.google.devtools.build.lib.profiler.TimeSeries? = actionCountTimeSeriesRef.get()
        val actionCacheCountTimeSeries: com.google.devtools.build.lib.profiler.TimeSeries? =
            actionCacheCountTimeSeriesRef.get()
        val localActionCountTimeSeries: com.google.devtools.build.lib.profiler.TimeSeries? =
            localActionCountTimeSeriesRef.get()
        if (actionCountTimeSeries != null && countAction(data.type)) {
            actionCountTimeSeries.addRange(
                java.time.Duration.ofNanos(data.startTimeNanos), java.time.Duration.ofNanos(endTimeNanos)
            )
        }
        if (actionCacheCountTimeSeries != null && data.type == com.google.devtools.build.lib.profiler.ProfilerTask.ACTION_CHECK) {
            actionCacheCountTimeSeries.addRange(
                java.time.Duration.ofNanos(data.startTimeNanos), java.time.Duration.ofNanos(endTimeNanos)
            )
        }

        if (localActionCountTimeSeries != null && data.type == com.google.devtools.build.lib.profiler.ProfilerTask.LOCAL_ACTION_COUNTS) {
            localActionCountTimeSeries.addRange(
                java.time.Duration.ofNanos(data.startTimeNanos), java.time.Duration.ofNanos(endTimeNanos)
            )
        }
        val aggregator = slowestTasks[data.type.ordinal()]
        if (aggregator != null) {
            aggregator.add(data)
        }
    }

    @Throws(java.lang.InterruptedException::class)
    override fun markPhase(phase: com.google.devtools.build.lib.profiler.ProfilePhase) {
        if (isActive() && isProfiling(com.google.devtools.build.lib.profiler.ProfilerTask.PHASE)) {
            logEvent(com.google.devtools.build.lib.profiler.ProfilerTask.PHASE, phase.description)
        }
    }

    private val nextLaneId: AtomicLong = AtomicLong(1000000)
    private val multiLaneGenerator = MultiLaneGenerator()

    private inner class MultiLaneGenerator {
        private val laneGenerators: MutableMap<String?, LaneGenerator> = ConcurrentHashMap<String?, LaneGenerator>()

        /**
         * @return the lane if it's active, otherwise null.
         */
        fun acquire(prefix: String): Lane? {
            if (!isActive()) {
                return null
            }
            val laneGenerator: LaneGenerator =
                laneGenerators.computeIfAbsent(
                    prefix,
                    java.util.function.Function { unused: String? -> LaneGenerator(prefix) })
            return laneGenerator.acquire()
        }

        fun release(lane: Lane) {
            val laneGenerator = lane.laneGenerator
            laneGenerator.release(lane)
        }

        fun reset() {
            multiLaneGenerator.laneGenerators.clear()
        }
    }

    private class Lane(laneGenerator: LaneGenerator, id: Long) : Comparable<Lane?> {
        private val laneGenerator: LaneGenerator
        private val id: Long
        private var refCount = 0

        init {
            this.laneGenerator = laneGenerator
            this.id = id
        }

        override fun compareTo(o: Lane): Int {
            return java.lang.Long.compare(id, o.id)
        }
    }

    private inner class LaneGenerator(prefix: String) {
        private val prefix: String
        private val availableLanes: java.util.Queue<Lane?> = ConcurrentLinkedQueue<Lane?>()
        private val count: AtomicInteger = AtomicInteger(0)

        init {
            this.prefix = prefix
        }

        fun acquire(): Lane {
            var lane: Lane? = availableLanes.poll()
            // It might create more virtual lanes, but it's fine for our purpose.
            if (lane == null) {
                lane = Lane(this, nextLaneId.getAndIncrement())
                val newLaneIndex: Int = count.getAndIncrement()
                val newLaneName =
                    if (prefix.endsWith("-"))
                        prefix + newLaneIndex + " (Virtual)"
                    else
                        prefix + "-" + newLaneIndex + " (Virtual)"
                val threadMetadata: ThreadMetadata = ThreadMetadata(newLaneName, lane.id)
                val writer: JsonTraceFileWriter? = this@TraceProfilerServiceImpl.writerRef.get()
                if (writer != null) {
                    writer.enqueue(threadMetadata)
                }
            }
            return lane
        }

        fun release(lane: Lane?) {
            availableLanes.offer(lane)
        }
    }

    private val virtualThreadPrefix: java.lang.ThreadLocal<String> =
        java.lang.ThreadLocal.withInitial<String?>(java.util.function.Supplier { this.guessThreadPrefix() })
    private val borrowedLane: java.lang.ThreadLocal<Lane?> = java.lang.ThreadLocal.withInitial<Lane?>(
        java.util.function.Supplier {
            val prefix: String = virtualThreadPrefix.get()
            val lane = multiLaneGenerator.acquire(prefix)
            if (lane == null) {
                return@withInitial null
            }
            com.google.common.base.Preconditions.checkState(lane.refCount == 0)
            lane
        })

    init {
        initHistograms()
        for (task in com.google.devtools.build.lib.profiler.ProfilerTask.entries) {
            if (task.collectsSlowestInstances) {
                slowestTasks[task.ordinal()] = SlowestTaskAggregator()
            }
        }
    }

    private fun borrowLane(): Lane? {
        if (!java.lang.Thread.currentThread().isVirtual() || !isActive()) {
            return null
        }
        val lane: Lane? = borrowedLane.get()
        if (lane == null) {
            return null
        }
        lane.refCount += 1
        return lane
    }

    private fun getLaneId(lane: Lane?): Long {
        if (lane == null) {
            return java.lang.Thread.currentThread().threadId()
        }
        return lane.id
    }

    private fun releaseLane(lane: Lane?) {
        if (lane == null) {
            return
        }
        lane.refCount -= 1
        if (lane.refCount == 0) {
            borrowedLane.remove()
            multiLaneGenerator.release(lane)
        }
    }

    private fun guessThreadPrefix(): String {
        val currentThread: java.lang.Thread = java.lang.Thread.currentThread()
        com.google.common.base.Preconditions.checkState(currentThread.isVirtual())
        val threadName: String = currentThread.getName()

        // Assume the thread name has format "prefix%d"
        for (i in threadName.length() - 1 downTo 1) {
            val ch: Char = threadName.charAt(i)
            if (ch < '0' || ch > '9') {
                if (i < threadName.length() - 1) {
                    return threadName.substring(0, i + 1)
                }
            }
        }
        return "Other"
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    override fun <T> profileFuture(
        future: com.google.common.util.concurrent.ListenableFuture<T?>,
        prefix: String,
        type: com.google.devtools.build.lib.profiler.ProfilerTask,
        description: String?
    ): com.google.common.util.concurrent.ListenableFuture<T?> {
        val lane = multiLaneGenerator.acquire(prefix)
        if (lane == null) {
            return future
        }

        val startTimeNanos: Long = clock.nanoTime()
        future.addListener(
            java.lang.Runnable {
                try {
                    completeTask(lane.id, startTimeNanos, type, description)
                } finally {
                    multiLaneGenerator.release(lane)
                }
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
        return future
    }

    /**
     * Implementation of [AsyncProfiler].
     * 
     * 
     * This class is thread-compatible but not thread-safe. You should create one profiler per
     * task.
     */
    inner class AsyncProfilerImpl private constructor(prefix: String, description: String?) :
        com.google.devtools.build.lib.profiler.AsyncProfiler {
        private val lane: Lane?
        private val startTimeNanos: Long
        private val description: String?

        init {
            this.lane = multiLaneGenerator.acquire(prefix)
            this.startTimeNanos = clock.nanoTime()
            this.description = description
        }

        override fun profile(
            type: com.google.devtools.build.lib.profiler.ProfilerTask,
            description: String?
        ): com.google.devtools.build.lib.profiler.SilentCloseable {
            if (!(lane != null && isProfiling(type))) {
                return NOP
            }
            val startTimeNanos: Long = clock.nanoTime()
            return com.google.devtools.build.lib.profiler.SilentCloseable {
                completeTask(
                    lane.id,
                    startTimeNanos,
                    type,
                    description
                )
            }
        }

        override fun profile(description: String?): com.google.devtools.build.lib.profiler.SilentCloseable {
            return profile(com.google.devtools.build.lib.profiler.ProfilerTask.INFO, description)
        }

        override fun <T> profileFuture(
            future: com.google.common.util.concurrent.ListenableFuture<T?>,
            description: String?
        ): com.google.common.util.concurrent.ListenableFuture<T?> {
            return profileFuture<T?>(future, com.google.devtools.build.lib.profiler.ProfilerTask.INFO, description)
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun <T> profileFuture(
            future: com.google.common.util.concurrent.ListenableFuture<T?>,
            type: com.google.devtools.build.lib.profiler.ProfilerTask,
            description: String?
        ): com.google.common.util.concurrent.ListenableFuture<T?> {
            val s: com.google.devtools.build.lib.profiler.SilentCloseable = profile(type, description)
            future.addListener(
                java.lang.Runnable { s.close() },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
            return future
        }

        override fun profileCallback(runnable: java.lang.Runnable, description: String?): java.lang.Runnable {
            return profileCallback(runnable, com.google.devtools.build.lib.profiler.ProfilerTask.INFO, description)
        }

        override fun profileCallback(
            runnable: java.lang.Runnable,
            type: com.google.devtools.build.lib.profiler.ProfilerTask,
            description: String?
        ): java.lang.Runnable {
            val s: com.google.devtools.build.lib.profiler.SilentCloseable = profile(type, description)
            return java.lang.Runnable {
                s.close()
                runnable.run()
            }
        }

        override fun <T> profileCallback(
            consumer: java.util.function.Consumer<T?>,
            description: String?
        ): java.util.function.Consumer<T?> {
            return profileCallback<T?>(consumer, com.google.devtools.build.lib.profiler.ProfilerTask.INFO, description)
        }

        override fun <T> profileCallback(
            consumer: java.util.function.Consumer<T?>,
            type: com.google.devtools.build.lib.profiler.ProfilerTask,
            description: String?
        ): java.util.function.Consumer<T?> {
            val s: com.google.devtools.build.lib.profiler.SilentCloseable = profile(type, description)
            return java.util.function.Consumer { t: T? ->
                s.close()
                consumer.accept(t)
            }
        }

        override fun close() {
            completeTask(lane.id, startTimeNanos, com.google.devtools.build.lib.profiler.ProfilerTask.INFO, description)
            multiLaneGenerator.release(lane!!)
        }
    }

    override fun profileAsync(
        prefix: String,
        description: String?
    ): com.google.devtools.build.lib.profiler.AsyncProfiler {
        return AsyncProfilerImpl(prefix, description)
    }

    override fun createTimeSeries(
        startTime: java.time.Duration?,
        bucketDuration: java.time.Duration
    ): com.google.devtools.build.lib.profiler.TimeSeries {
        return TimeSeriesImpl(startTime, bucketDuration)
    }

    companion object {
        private const val HISTOGRAM_BUCKETS = 20

        private val ACTION_COUNT_BUCKET_DURATION: java.time.Duration = java.time.Duration.ofMillis(200)

        private val DEFAULT_VFS_TYPE_HEURISTICS: com.google.common.collect.ImmutableMap<String?, java.util.function.Predicate<in String?>?> =
            com.google.common.collect.ImmutableMap.of<String?, java.util.function.Predicate<in String?>?>(
                "blaze-out", java.util.regex.Pattern.compile("/blaze-out/").asPredicate(),
                "source", com.google.common.base.Predicates.alwaysTrue<CharSequence?>()
            )

        private fun getProcessCpuTime(): java.time.Duration {
            val bean: com.sun.management.OperatingSystemMXBean =
                java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
            return java.time.Duration.ofNanos(bean.getProcessCpuTime())
        }

        private val NOP: com.google.devtools.build.lib.profiler.SilentCloseable =
            com.google.devtools.build.lib.profiler.SilentCloseable {}
    }
}
