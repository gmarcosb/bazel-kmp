// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.profiler.TraceData
import com.google.gson.stream.JsonWriter
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Used to inject a whole counter series (or even multiple) in one go in the JSON trace profile;
 * these could be used to represent CPU or memory usages over the course of an invocation (as
 * opposed to individual tasks such as executing an action).
 */
internal class CounterSeriesTraceData(
    counterSeriesMap: MutableMap<com.google.devtools.build.lib.profiler.CounterSeriesTask, DoubleArray?>,
    profileStart: java.time.Duration,
    bucketDuration: java.time.Duration
) : TraceData {
    private val counterSeriesMap: MutableMap<com.google.devtools.build.lib.profiler.CounterSeriesTask, DoubleArray?>
    private val profileStart: java.time.Duration
    private val bucketDuration: java.time.Duration
    private val len: Int
    private val threadId: Long
    private var displayName: String? = null

    private var colorName: String? = null

    /**
     * If multiple series are passed: - they will be rendered as stacked chart; - we assume they all
     * have the same length; - display name and color are picked from the first profile task in the
     * map. However, colors the remaining series are picked arbitrarily by the Trace renderer.
     */
    init {
        var len = -1
        for (entry in counterSeriesMap.entrySet()) {
            val task: com.google.devtools.build.lib.profiler.CounterSeriesTask = entry.getKey()
            if (len == -1) {
                len = entry.getValue().length

                this.displayName = task.laneName()
                if (task.color() != null) {
                    this.colorName = task.color().value()
                }
            } else {
                // Check that second and subsequent series have the same length as the first.
                com.google.common.base.Preconditions.checkState(len == entry.getValue().length)
            }
        }
        this.len = len
        this.threadId = java.lang.Thread.currentThread().getId()
        this.counterSeriesMap = counterSeriesMap
        this.profileStart = profileStart
        this.bucketDuration = bucketDuration
    }

    @Throws(IOException::class)
    override fun writeTraceData(jsonWriter: JsonWriter, profileStartTimeNanos: Long) {
        // See
        // https://docs.google.com/document/d/1CvAClvFfyA5R-PhYUmn5OOQtYMH4h6I0nSsKchNAySU/preview#heading=h.msg3086636uq
        // for how counter series are represented in the Chrome Trace Event format.
        var recorded = false
        for (i in 0..<len) {
            val timeNanos: Long = profileStart.plus(bucketDuration.multipliedBy(i.toLong())).toNanos()
            jsonWriter.setIndent("  ")
            jsonWriter.beginObject()
            jsonWriter.setIndent("")
            jsonWriter.name("name").value(displayName)
            jsonWriter.name("pid").value(PROCESS_ID)
            jsonWriter.name("tid").value(threadId)
            if (colorName != null) {
                jsonWriter.name("cname").value(colorName)
            }
            jsonWriter.name("ph").value("C")
            jsonWriter.name("ts").value(TimeUnit.NANOSECONDS.toMicros(timeNanos - profileStartTimeNanos))
            jsonWriter.name("args")

            jsonWriter.beginObject()
            for (task in counterSeriesMap.keySet()) {
                val value = counterSeriesMap.get(task)!![i]
                // Skip counts equal to zero. They will show up as a thin line in the profile. Once we
                // record the profile task we need to post it until the end.
                if (java.lang.Math.abs(value) > 0.00001 || recorded) {
                    jsonWriter.name(task.seriesName()).value(value)
                    recorded = true
                }
            }
            jsonWriter.endObject()

            jsonWriter.endObject()
        }
    }

    companion object {
        @com.google.common.annotations.VisibleForTesting
        const val PROCESS_ID: Long = 1
    }
}
