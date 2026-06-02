// Copyright 2018 The Bazel Authors. All rights reserved.
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
import com.google.devtools.build.lib.profiler.ResourceCollector
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors

/** Monitors a number of counter series collectors and logs them in the profile as a time series.  */
class ResourceCollector {
    @kotlin.concurrent.Volatile
    private var stopCollection = false

    @kotlin.concurrent.Volatile
    private var profilingStarted = false

    private val collectors: ConcurrentLinkedQueue<com.google.devtools.build.lib.profiler.CounterSeriesCollector> =
        ConcurrentLinkedQueue<com.google.devtools.build.lib.profiler.CounterSeriesCollector>()

    @com.google.errorprone.annotations.concurrent.GuardedBy("this")
    private var timeSeries: MutableMap<com.google.devtools.build.lib.profiler.CounterSeriesTask?, com.google.devtools.build.lib.profiler.TimeSeries>? =
        null

    private var stopwatch: com.google.common.base.Stopwatch? = null

    private var collector: Collector? = null

    fun start() {
        com.google.common.base.Preconditions.checkState(collector == null)
        collector = com.google.devtools.build.lib.profiler.ResourceCollector.Collector()
        collector.setDaemon(true)
        collector.start()
    }

    fun registerCounterSeriesCollector(collector: com.google.devtools.build.lib.profiler.CounterSeriesCollector?) {
        collectors.add(collector)
    }

    fun unregisterCounterSeriesCollector(collector: com.google.devtools.build.lib.profiler.CounterSeriesCollector?) {
        collectors.remove(collector)
    }

    /** Thread that does the collection.  */
    private inner class Collector : java.lang.Thread("collect-local-resources") {
        override fun run() {
            synchronized(this@ResourceCollector) {
                timeSeries =
                    LinkedHashMap<com.google.devtools.build.lib.profiler.CounterSeriesTask?, com.google.devtools.build.lib.profiler.TimeSeries>()
            }

            stopwatch = com.google.common.base.Stopwatch.createStarted()
            val startTime: java.time.Duration = stopwatch.elapsed()
            var previousElapsed: java.time.Duration = stopwatch.elapsed()
            profilingStarted = true
            while (!stopCollection) {
                try {
                    java.lang.Thread.sleep(COLLECT_SLEEP_INTERVAL.toMillis())
                } catch (e: java.lang.InterruptedException) {
                    return
                }
                val nextElapsed: java.time.Duration = stopwatch.elapsed()
                val deltaNanos: Double = nextElapsed.minus(previousElapsed).toNanos().toDouble()
                val finalPreviousElapsed: java.time.Duration = previousElapsed
                synchronized(this@ResourceCollector) {
                    for (collector in collectors) {
                        collector.collect(
                            deltaNanos,
                            java.util.function.BiConsumer { type: com.google.devtools.build.lib.profiler.CounterSeriesTask?, value: Double? ->
                                addRange(
                                    type,
                                    startTime,
                                    finalPreviousElapsed,
                                    nextElapsed,
                                    value!!
                                )
                            })
                    }
                }
                previousElapsed = nextElapsed
            }
        }
    }

    fun stop() {
        if (collector != null) {
            com.google.common.base.Preconditions.checkArgument(!stopCollection)
            stopCollection = true
            collector.interrupt()
            try {
                collector.join()
            } catch (e: java.lang.InterruptedException) {
                java.lang.Thread.currentThread().interrupt()
            }
            logCollectedData()
            collector = null
            stopCollection = false
            profilingStarted = false

            synchronized(this) {
                timeSeries = null
            }
        }
    }

    @kotlin.jvm.Synchronized
    fun logCollectedData() {
        if (!profilingStarted) {
            return
        }
        com.google.common.base.Preconditions.checkArgument(stopCollection)
        val endTimeNanos: Long = java.lang.System.nanoTime()
        val elapsedNanos: Long = stopwatch.elapsed(TimeUnit.NANOSECONDS)
        val startTimeNanos = endTimeNanos - elapsedNanos
        val profileStart: java.time.Duration? = java.time.Duration.ofNanos(startTimeNanos)
        val len: Int = (elapsedNanos / BUCKET_DURATION.toNanos()).toInt() + 1

        val stackedTaskGroups: MutableMap<String?, MutableList<MutableMap.MutableEntry<com.google.devtools.build.lib.profiler.CounterSeriesTask?, com.google.devtools.build.lib.profiler.TimeSeries?>>> =
            timeSeries.entrySet().stream()
                .collect(Collectors.groupingBy(java.util.function.Function { e: MutableMap.MutableEntry<com.google.devtools.build.lib.profiler.CounterSeriesTask?, com.google.devtools.build.lib.profiler.TimeSeries?>? ->
                    e.getKey().laneName()
                }))

        for (taskGroup in stackedTaskGroups.values()) {
            val stackedCounters: com.google.common.collect.ImmutableMap.Builder<com.google.devtools.build.lib.profiler.CounterSeriesTask?, DoubleArray?> =
                com.google.common.collect.ImmutableMap.builderWithExpectedSize<com.google.devtools.build.lib.profiler.CounterSeriesTask?, DoubleArray?>(
                    taskGroup.size()
                )
            for (task in taskGroup) {
                stackedCounters.put(task.getKey(), task.getValue().toDoubleArray(len))
            }
            com.google.devtools.build.lib.profiler.Profiler.instance()
                .logCounters(stackedCounters.buildOrThrow(), profileStart, BUCKET_DURATION)
        }

        collectors.clear()
        timeSeries = null
    }

    private fun addRange(
        type: com.google.devtools.build.lib.profiler.CounterSeriesTask?,
        startTime: java.time.Duration?,
        previousElapsed: java.time.Duration?,
        nextElapsed: java.time.Duration?,
        value: Double
    ) {
        synchronized(this) {
            if (timeSeries == null) {
                return
            }
            val series: com.google.devtools.build.lib.profiler.TimeSeries =
                timeSeries.computeIfAbsent(
                    type,
                    java.util.function.Function { unused: com.google.devtools.build.lib.profiler.CounterSeriesTask? ->
                        com.google.devtools.build.lib.profiler.Profiler.instance()
                            .createTimeSeries(startTime, BUCKET_DURATION)
                    })
            series.addRange(previousElapsed, nextElapsed, value)
        }
    }

    companion object {
        // TODO(twerth): Make these configurable.
        private val BUCKET_DURATION: java.time.Duration = java.time.Duration.ofSeconds(1)
        private val COLLECT_SLEEP_INTERVAL: java.time.Duration = java.time.Duration.ofMillis(200)
    }
}
