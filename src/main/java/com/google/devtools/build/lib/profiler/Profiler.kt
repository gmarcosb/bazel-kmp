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

import java.io.IOException
import java.util.Collections
import java.util.UUID

/**
 * Static accessor for the [TraceProfilerService].
 * 
 * 
 * This class provides a global access point to the trace profiler so it doesn't have to be
 * threaded through most of the codebase. Usage typically looks like:
 * 
 * 
 * `
 * try (SilentCloseable c = Profiler.instance().profile("my task")) {
 * // code to be profiled
 * }
` * 
 * 
 * 
 * It's also possible to save the `Profiler.instance()` return value in a variable and
 * re-use it later.
 * 
 * 
 * The purpose of this class is let both the LC and SC use the trace profiler without both of
 * them depending on the full implementation at compile time. At runtime, the symbolic references to
 * [Profiler] on both sides must link against the SC version. Any future additions to the
 * profiler API should mirror the existing methods: a delegating implementation falling back to a
 * no-op, with the actual implementation in [TraceProfilerServiceImpl].
 */
@com.google.devtools.build.lib.skybridge.SkybridgeInterface
// This code is very performance sensitive.
class Profiler private constructor() : com.google.devtools.build.lib.profiler.TraceProfilerService {
    override fun nanoTimeMaybe(): Long {
        if (com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService != null) {
            return com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService.nanoTimeMaybe()
        }
        return -1
    }

    override fun isActive(): Boolean {
        if (com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService != null) {
            return com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService.isActive()
        }
        return false
    }

    override fun profile(
        type: com.google.devtools.build.lib.profiler.ProfilerTask?,
        description: String?
    ): com.google.devtools.build.lib.profiler.SilentCloseable? {
        if (com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService != null) {
            return com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService.profile(
                type,
                description
            )
        }
        return com.google.devtools.build.lib.profiler.Profiler.Companion.NOP_CLOSEABLE
    }

    override fun profile(
        type: com.google.devtools.build.lib.profiler.ProfilerTask?,
        description: java.util.function.Supplier<String?>?
    ): com.google.devtools.build.lib.profiler.SilentCloseable? {
        if (com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService != null) {
            return com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService.profile(
                type,
                description
            )
        }
        return com.google.devtools.build.lib.profiler.Profiler.Companion.NOP_CLOSEABLE
    }

    override fun profile(description: String?): com.google.devtools.build.lib.profiler.SilentCloseable? {
        if (com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService != null) {
            return com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService.profile(description)
        }
        return com.google.devtools.build.lib.profiler.Profiler.Companion.NOP_CLOSEABLE
    }

    override fun logSimpleTask(
        startTimeNanos: Long,
        type: com.google.devtools.build.lib.profiler.ProfilerTask?,
        description: String?
    ) {
        if (com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService != null) {
            com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService.logSimpleTask(
                startTimeNanos,
                type,
                description
            )
        }
    }

    override fun logSimpleTask(
        startTimeNanos: Long,
        stopTimeNanos: Long,
        type: com.google.devtools.build.lib.profiler.ProfilerTask?,
        description: String?
    ) {
        if (com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService != null) {
            com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService.logSimpleTask(
                startTimeNanos,
                stopTimeNanos,
                type,
                description
            )
        }
    }

    override fun logSimpleTaskDuration(
        startTimeNanos: Long,
        duration: java.time.Duration?,
        type: com.google.devtools.build.lib.profiler.ProfilerTask?,
        description: String?
    ) {
        if (com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService != null) {
            com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService.logSimpleTaskDuration(
                startTimeNanos,
                duration,
                type,
                description
            )
        }
    }

    override fun logEventAtTime(
        atTimeNanos: Long,
        type: com.google.devtools.build.lib.profiler.ProfilerTask?,
        description: String?
    ) {
        if (com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService != null) {
            com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService.logEventAtTime(
                atTimeNanos,
                type,
                description
            )
        }
    }

    override fun logEvent(type: com.google.devtools.build.lib.profiler.ProfilerTask?, description: String?) {
        if (com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService != null) {
            com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService.logEvent(type, description)
        }
    }

    override fun setVfsTypeHeuristics(
        vfsTypeHeuristics: MutableMap<String?, out java.util.function.Predicate<in String?>?>?
    ) {
        if (com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService != null) {
            com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService.setVfsTypeHeuristics(
                vfsTypeHeuristics
            )
        }
    }

    @Throws(IOException::class)
    override fun start(
        profiledTasks: MutableSet<com.google.devtools.build.lib.profiler.ProfilerTask?>?,
        stream: java.io.OutputStream?,
        format: com.google.devtools.build.lib.profiler.TraceProfilerService.Format?,
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
    ) {
        checkNotNull(com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService) { "cannot call start before setTraceProfilerService" }
        com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService.start(
            profiledTasks,
            stream,
            format,
            outputBase,
            buildID,
            recordAllDurations,
            clock,
            execStartTimeNanos,  /* slimProfile= */
            slimProfile,  /* includePrimaryOutput= */
            includePrimaryOutput,  /* includeTargetLabel= */
            includeTargetLabel,  /* includeConfiguration= */
            includeConfiguration,  /* collectTaskHistograms= */
            collectTaskHistograms
        )
    }

    @Throws(IOException::class)
    override fun stop() {
        if (com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService != null) {
            com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService.stop()
        }
    }

    override fun clear() {
        if (com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService != null) {
            com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService.clear()
        }
    }

    override fun getTasksHistograms(): MutableList<com.google.devtools.build.lib.profiler.StatRecorder?>? {
        if (com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService != null) {
            return com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService.getTasksHistograms()
        }
        return Collections.emptyList<com.google.devtools.build.lib.profiler.StatRecorder?>()
    }

    override fun getSlowestTasks(): Iterable<com.google.devtools.build.lib.profiler.SlowTask?>? {
        if (com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService != null) {
            return com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService.getSlowestTasks()
        }
        return Collections.emptyList<com.google.devtools.build.lib.profiler.SlowTask?>()
    }

    override fun isProfiling(type: com.google.devtools.build.lib.profiler.ProfilerTask?): Boolean {
        if (com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService != null) {
            return com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService.isProfiling(type)
        }
        return false
    }

    @Throws(java.lang.InterruptedException::class)
    override fun markPhase(phase: com.google.devtools.build.lib.profiler.ProfilePhase?) {
        if (com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService != null) {
            com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService.markPhase(phase)
        }
    }

    override fun profileAction(
        type: com.google.devtools.build.lib.profiler.ProfilerTask?,
        mnemonic: String?,
        description: String?,
        primaryOutput: String?,
        targetLabel: String?,
        configuration: String?
    ): com.google.devtools.build.lib.profiler.SilentCloseable? {
        if (com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService != null) {
            return com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService.profileAction(
                type, mnemonic, description, primaryOutput, targetLabel, configuration
            )
        }
        return com.google.devtools.build.lib.profiler.Profiler.Companion.NOP_CLOSEABLE
    }

    override fun completeTask(
        startTimeNanos: Long,
        type: com.google.devtools.build.lib.profiler.ProfilerTask?,
        description: String?
    ) {
        if (com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService != null) {
            com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService.completeTask(
                startTimeNanos,
                type,
                description
            )
        }
    }

    override fun registerCounterSeriesCollector(collector: com.google.devtools.build.lib.profiler.CounterSeriesCollector?) {
        if (com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService != null) {
            com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService.registerCounterSeriesCollector(
                collector
            )
        }
    }

    override fun unregisterCounterSeriesCollector(collector: com.google.devtools.build.lib.profiler.CounterSeriesCollector?) {
        if (com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService != null) {
            com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService.unregisterCounterSeriesCollector(
                collector
            )
        }
    }

    override fun logCounters(
        counterSeriesMap: MutableMap<com.google.devtools.build.lib.profiler.CounterSeriesTask?, DoubleArray?>?,
        profileStart: java.time.Duration?,
        bucketDuration: java.time.Duration?
    ) {
        if (com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService != null) {
            com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService.logCounters(
                counterSeriesMap,
                profileStart,
                bucketDuration
            )
        }
    }

    override fun getProfileElapsedTime(): java.time.Duration? {
        if (com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService != null) {
            return com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService.getProfileElapsedTime()
        }
        return java.time.Duration.ZERO
    }

    override fun getServerProcessCpuTime(): java.time.Duration? {
        if (com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService != null) {
            return com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService.getServerProcessCpuTime()
        }
        return java.time.Duration.ZERO
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    override fun <T> profileFuture(
        future: com.google.common.util.concurrent.ListenableFuture<T?>?,
        prefix: String?,
        type: com.google.devtools.build.lib.profiler.ProfilerTask?,
        description: String?
    ): com.google.common.util.concurrent.ListenableFuture<T?>? {
        if (com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService != null) {
            return com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService.profileFuture<T?>(
                future,
                prefix,
                type,
                description
            )
        }
        return future
    }

    override fun profileAsync(
        prefix: String?,
        description: String?
    ): com.google.devtools.build.lib.profiler.AsyncProfiler? {
        if (com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService != null) {
            return com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService.profileAsync(
                prefix,
                description
            )
        }
        return com.google.devtools.build.lib.profiler.Profiler.Companion.NOP_ASYNC_PROFILER
    }

    override fun createTimeSeries(
        startTime: java.time.Duration?,
        bucketDuration: java.time.Duration?
    ): com.google.devtools.build.lib.profiler.TimeSeries? {
        if (com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService != null) {
            return com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService.createTimeSeries(
                startTime,
                bucketDuration
            )
        }
        return com.google.devtools.build.lib.profiler.Profiler.Companion.NOP_TIME_SERIES
    }

    private class NoOpTimeSeries : com.google.devtools.build.lib.profiler.TimeSeries {
        override fun addRange(startTime: java.time.Duration?, endTime: java.time.Duration?) {}

        override fun addRange(rangeStart: java.time.Duration?, rangeEnd: java.time.Duration?, value: Double) {}

        override fun toDoubleArray(len: Int): DoubleArray {
            return DoubleArray(len)
        }
    }

    private class NoOpAsyncProfiler : com.google.devtools.build.lib.profiler.AsyncProfiler {
        override fun profile(
            type: com.google.devtools.build.lib.profiler.ProfilerTask?,
            description: String?
        ): com.google.devtools.build.lib.profiler.SilentCloseable {
            return com.google.devtools.build.lib.profiler.Profiler.Companion.NOP_CLOSEABLE
        }

        override fun profile(description: String?): com.google.devtools.build.lib.profiler.SilentCloseable {
            return com.google.devtools.build.lib.profiler.Profiler.Companion.NOP_CLOSEABLE
        }

        override fun <T> profileFuture(
            future: com.google.common.util.concurrent.ListenableFuture<T?>?,
            description: String?
        ): com.google.common.util.concurrent.ListenableFuture<T?>? {
            return future
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun <T> profileFuture(
            future: com.google.common.util.concurrent.ListenableFuture<T?>?,
            type: com.google.devtools.build.lib.profiler.ProfilerTask?,
            description: String?
        ): com.google.common.util.concurrent.ListenableFuture<T?>? {
            return future
        }

        override fun profileCallback(runnable: java.lang.Runnable?, description: String?): java.lang.Runnable? {
            return runnable
        }

        override fun profileCallback(
            runnable: java.lang.Runnable?,
            type: com.google.devtools.build.lib.profiler.ProfilerTask?,
            description: String?
        ): java.lang.Runnable? {
            return runnable
        }

        override fun <T> profileCallback(
            consumer: java.util.function.Consumer<T?>?,
            description: String?
        ): java.util.function.Consumer<T?>? {
            return consumer
        }

        override fun <T> profileCallback(
            consumer: java.util.function.Consumer<T?>?,
            type: com.google.devtools.build.lib.profiler.ProfilerTask?,
            description: String?
        ): java.util.function.Consumer<T?>? {
            return consumer
        }

        override fun close() {}
    }

    companion object {
        private val instance: Profiler = com.google.devtools.build.lib.profiler.Profiler()

        @kotlin.concurrent.Volatile
        private var traceProfilerService: com.google.devtools.build.lib.profiler.TraceProfilerService? = null

        private val NOP_CLOSEABLE: com.google.devtools.build.lib.profiler.SilentCloseable =
            com.google.devtools.build.lib.profiler.SilentCloseable {}
        private val NOP_TIME_SERIES: com.google.devtools.build.lib.profiler.TimeSeries =
            com.google.devtools.build.lib.profiler.Profiler.NoOpTimeSeries()
        private val NOP_ASYNC_PROFILER: com.google.devtools.build.lib.profiler.AsyncProfiler =
            com.google.devtools.build.lib.profiler.Profiler.NoOpAsyncProfiler()

        /**
         * Returns the singleton [Profiler] instance, which is valid for the entire lifetime of the
         * server.
         * 
         * 
         * With the exception of the [.start] method, the singleton instance provides a no-op
         * implementation of [TraceProfilerService] until [.setTraceProfilerService] is
         * called, after which it forwards all instance method calls to the implementation thus installed.
         * Calling [.start] before [.setTraceProfilerService] will throw an exception.
         * 
         * 
         * With this arrangement, [Profiler] methods other than [.start] may be called
         * liberally anywhere in the codebase, even if [.setTraceProfilerService] is called after
         * the [Profiler] singleton has already been retrieved, or if it is never called (as might
         * be the case in a test or a non-Bazel binary incorporating parts of the Bazel codebase).
         */
        @kotlin.jvm.JvmStatic
        fun instance(): Profiler {
            return com.google.devtools.build.lib.profiler.Profiler.Companion.instance
        }

        /**
         * Installs the [TraceProfilerService]. In a production context, this is expected to be
         * called exactly once during server startup.
         * 
         * 
         * From this point onwards, methods called on the singleton [Profiler] instance will be
         * forwarded to this [TraceProfilerService].
         */
        fun setTraceProfilerService(traceProfilerService: com.google.devtools.build.lib.profiler.TraceProfilerService?) {
            com.google.devtools.build.lib.profiler.Profiler.Companion.traceProfilerService = traceProfilerService
        }
    }
}
