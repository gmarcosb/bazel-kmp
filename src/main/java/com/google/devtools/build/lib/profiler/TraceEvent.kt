// Copyright 2020 The Bazel Authors. All rights reserved.
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

import com.google.gson.stream.JsonReader
import java.io.IOException

/**
 * Represents a single trace event in a JSON profile.
 * 
 * 
 * Format is documented in
 * https://docs.google.com/document/d/1CvAClvFfyA5R-PhYUmn5OOQtYMH4h6I0nSsKchNAySU/preview
 */
@kotlin.jvm.JvmRecord
data class TraceEvent(
    category: String?,
    name: String?,
    type: String?,
    timestamp: java.time.Duration?,
    duration: java.time.Duration?,
    processId: Long,
    threadId: Long,
    args: com.google.common.collect.ImmutableMap<String?, Any?>?,
    primaryOutputPath: String?,
    targetLabel: String?,
    mnemonic: String?,
    configuration: String?
) {
    val category: String?
    val name: String?
    val type: String?
    val timestamp: java.time.Duration?
    val duration: java.time.Duration?
    val processId: Long
    val threadId: Long
    val args: com.google.common.collect.ImmutableMap<String?, Any?>?
    val primaryOutputPath: String?
    val targetLabel: String?
    val mnemonic: String?
    val configuration: String?

    init {
        this.configuration = configuration
        this.mnemonic = mnemonic
        this.targetLabel = targetLabel
        this.primaryOutputPath = primaryOutputPath
        this.args = args
        this.threadId = threadId
        this.processId = processId
        this.duration = duration
        this.timestamp = timestamp
        this.type = type
        this.name = name
        this.category = category
        java.util.Objects.requireNonNull<String?>(name, "name")
    }

    companion object {
        fun create(
            category: String?,
            name: String?,
            type: String?,
            timestamp: java.time.Duration?,
            duration: java.time.Duration?,
            processId: Long,
            threadId: Long,
            args: com.google.common.collect.ImmutableMap<String?, Any?>?,
            primaryOutputPath: String?,
            targetLabel: String?,
            mnemonic: String?,
            configuration: String?
        ): TraceEvent {
            return TraceEvent(
                category,
                name,
                type,
                timestamp,
                duration,
                processId,
                threadId,
                args,
                primaryOutputPath,
                targetLabel,
                mnemonic,
                configuration
            )
        }

        // Only applicable to action-related TraceEvents.
        @Throws(IOException::class)
        private fun createFromJsonReader(reader: JsonReader): TraceEvent {
            var category: String? = null
            var name: String? = null
            var timestamp: java.time.Duration? = null
            var duration: java.time.Duration? = null
            var processId: Long = -1
            var threadId: Long = -1
            var primaryOutputPath: String? = null
            var targetLabel: String? = null
            var mnemonic: String? = null
            var type: String? = null
            var configuration: String? = null
            var args: com.google.common.collect.ImmutableMap<String?, Any?>? = null

            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "cat" -> category = reader.nextString()
                    "name" -> name = reader.nextString()
                    "ph" -> type = reader.nextString()
                    "ts" ->  // Duration has no microseconds :-/.
                        timestamp = java.time.Duration.ofNanos(reader.nextLong() * 1000)

                    "dur" -> duration = java.time.Duration.ofNanos(reader.nextLong() * 1000)
                    "pid" -> processId = reader.nextLong()
                    "tid" -> threadId = reader.nextLong()
                    "out" -> primaryOutputPath = reader.nextString()
                    "args" -> {
                        args = parseMap(reader)
                        val target: Any? = args.get("target")
                        targetLabel = if (target is String) target else null
                        val mnemonicValue: Any? = args.get("mnemonic")
                        mnemonic = if (mnemonicValue is String) mnemonicValue else null
                        val configurationValue: Any? = args.get("configuration")
                        configuration = if (configurationValue is String) configurationValue else null
                    }

                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            return create(
                category,
                name,
                type,
                timestamp,
                duration,
                processId,
                threadId,
                args,
                primaryOutputPath,
                targetLabel,
                mnemonic,
                configuration
            )
        }

        @Throws(IOException::class)
        private fun parseMap(reader: JsonReader): com.google.common.collect.ImmutableMap<String?, Any?> {
            val builder: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
                com.google.common.collect.ImmutableMap.builder<String?, Any?>()

            reader.beginObject()
            while (reader.peek() != com.google.gson.stream.JsonToken.END_OBJECT) {
                val name: String? = reader.nextName()
                val `val` = parseSingleValueRecursively(reader)
                builder.put(name, `val`)
            }
            reader.endObject()

            return builder.buildOrThrow()
        }

        @Throws(IOException::class)
        private fun parseArray(reader: JsonReader): com.google.common.collect.ImmutableList<Any?> {
            val builder: com.google.common.collect.ImmutableList.Builder<Any?> =
                com.google.common.collect.ImmutableList.builder<Any?>()

            reader.beginArray()
            while (reader.peek() != com.google.gson.stream.JsonToken.END_ARRAY) {
                val `val` = parseSingleValueRecursively(reader)
                builder.add(`val`)
            }
            reader.endArray()

            return builder.build()
        }

        @Throws(IOException::class)
        private fun parseSingleValueRecursively(reader: JsonReader): Any? {
            val nextToken: com.google.gson.stream.JsonToken? = reader.peek()
            return when (nextToken) {
                com.google.gson.stream.JsonToken.BOOLEAN -> reader.nextBoolean()
                com.google.gson.stream.JsonToken.NULL -> {
                    reader.nextNull()
                    null
                }

                com.google.gson.stream.JsonToken.NUMBER ->  // Json's only numeric type is number, using Double to accommodate all types
                    reader.nextDouble()

                com.google.gson.stream.JsonToken.STRING -> reader.nextString()
                com.google.gson.stream.JsonToken.BEGIN_OBJECT -> parseMap(reader)
                com.google.gson.stream.JsonToken.BEGIN_ARRAY -> parseArray(reader)
                else -> throw IOException("Unexpected token " + nextToken.name())
            }
        }

        @Throws(IOException::class)
        fun parseTraceEvents(reader: JsonReader): MutableList<TraceEvent?> {
            val traceEvents: MutableList<TraceEvent?> = java.util.ArrayList<TraceEvent?>()
            reader.beginArray()
            while (reader.hasNext()) {
                traceEvents.add(createFromJsonReader(reader))
            }
            reader.endArray()
            return traceEvents
        }
    }
}
