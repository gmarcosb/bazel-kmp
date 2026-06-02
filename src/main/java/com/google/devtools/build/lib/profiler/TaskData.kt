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

import ThreadSafety.ThreadCompatible
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible
import com.google.devtools.build.lib.profiler.ThreadMetadata
import com.google.devtools.build.lib.profiler.TraceData
import com.google.gson.stream.JsonWriter
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Container for the single task record.
 * 
 * 
 * Class itself is not thread safe, but all access to it from Profiler methods is.
 */
@ThreadCompatible
internal open class TaskData : TraceData {
    val threadId: Long
    val startTimeNanos: Long
    val type: com.google.devtools.build.lib.profiler.ProfilerTask?
    val description: String?

    var durationNanos: Long

    constructor(
        threadId: Long,
        startTimeNanos: Long,
        durationNanos: Long,
        eventType: com.google.devtools.build.lib.profiler.ProfilerTask?,
        description: String?
    ) {
        this.threadId = threadId
        this.startTimeNanos = startTimeNanos
        this.durationNanos = durationNanos
        this.type = eventType
        this.description = com.google.common.base.Preconditions.checkNotNull<String?>(description)
    }

    constructor(
        threadId: Long,
        startTimeNanos: Long,
        eventType: com.google.devtools.build.lib.profiler.ProfilerTask?,
        description: String?
    ) : this(threadId, startTimeNanos,  /* durationNanos= */-1, eventType, description)

    constructor(threadId: Long, startTimeNanos: Long, durationNanos: Long, description: String?) {
        this.type = com.google.devtools.build.lib.profiler.ProfilerTask.UNKNOWN
        this.threadId = threadId
        this.startTimeNanos = startTimeNanos
        this.durationNanos = durationNanos
        this.description = description
    }

    override fun toString(): String {
        return "Thread " + threadId + ", type " + type + ", " + description
    }

    @Throws(IOException::class)
    override fun writeTraceData(jsonWriter: JsonWriter, profileStartTimeNanos: Long) {
        val eventType = if (durationNanos == 0L) "i" else "X"
        jsonWriter.setIndent("  ")
        jsonWriter.beginObject()
        jsonWriter.setIndent("")
        if (type == null) {
            jsonWriter.setIndent("    ")
        } else {
            jsonWriter.name("cat").value(type.description)
        }
        jsonWriter.name("name").value(description)
        jsonWriter.name("ph").value(eventType)
        jsonWriter
            .name("ts")
            .value(TimeUnit.NANOSECONDS.toMicros(startTimeNanos - profileStartTimeNanos))
        if (durationNanos != 0L) {
            jsonWriter.name("dur").value(TimeUnit.NANOSECONDS.toMicros(durationNanos))
        }
        jsonWriter.name("pid").value(1)

        if (this is ActionTaskData) {
            if (actionTaskData.primaryOutputPath != null) {
                // Primary outputs are non-mergeable, thus incompatible with slim profiles.
                jsonWriter.name("out").value(actionTaskData.primaryOutputPath)
            }
            if (actionTaskData.targetLabel != null || actionTaskData.mnemonic != null || actionTaskData.configuration != null) {
                jsonWriter.name("args")
                jsonWriter.beginObject()
                if (actionTaskData.targetLabel != null) {
                    jsonWriter.name("target").value(actionTaskData.targetLabel)
                }
                if (actionTaskData.mnemonic != null) {
                    jsonWriter.name("mnemonic").value(actionTaskData.mnemonic)
                }
                if (actionTaskData.configuration != null) {
                    jsonWriter.name("configuration").value(actionTaskData.configuration)
                }
                jsonWriter.endObject()
            }
        }
        if (type == com.google.devtools.build.lib.profiler.ProfilerTask.CRITICAL_PATH_COMPONENT) {
            jsonWriter.name("args")
            jsonWriter.beginObject()
            jsonWriter.name("tid").value(threadId)
            jsonWriter.endObject()
        }
        jsonWriter
            .name("tid")
            .value(
                if (type == com.google.devtools.build.lib.profiler.ProfilerTask.CRITICAL_PATH_COMPONENT)
                    ThreadMetadata.Companion.CRITICAL_PATH_THREAD_ID
                else
                    threadId
            )
        jsonWriter.endObject()
    }

    /**
     * Similar to TaskData, specific for profiled actions. Depending on options, adds additional
     * action specific information such as primary output path and target label. This is only meant to
     * be used for ProfilerTask.ACTION.
     */
    internal class ActionTaskData(
        threadId: Long,
        startTimeNanos: Long,
        durationNanos: Long,
        eventType: com.google.devtools.build.lib.profiler.ProfilerTask?,
        mnemonic: String?,
        description: String?,
        primaryOutputPath: String?,
        targetLabel: String?,
        configuration: String?
    ) : TaskData(threadId, startTimeNanos, durationNanos, eventType, description) {
        val primaryOutputPath: String?
        val targetLabel: String?
        val mnemonic: String?
        val configuration: String?

        init {
            this.primaryOutputPath = primaryOutputPath
            this.targetLabel = targetLabel
            this.mnemonic = mnemonic
            this.configuration = configuration
        }
    }
}
