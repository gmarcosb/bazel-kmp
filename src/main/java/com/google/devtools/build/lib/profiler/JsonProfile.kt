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

import com.google.devtools.build.lib.profiler.TraceEvent
import com.google.devtools.build.lib.profiler.statistics.PhaseSummaryStatistics
import com.google.gson.stream.JsonReader
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * Utility class to handle parsing the JSON trace profiles.
 * 
 * 
 * The format itself is documented in
 * https://docs.google.com/document/d/1CvAClvFfyA5R-PhYUmn5OOQtYMH4h6I0nSsKchNAySU/preview
 */
class JsonProfile(inputStream: java.io.InputStream) {
    private var buildMetadata: BuildMetadata? = null
    private var phaseSummaryStatistics: PhaseSummaryStatistics? = null
    private var traceEvents: MutableList<TraceEvent>? = null

    constructor(profileFile: java.io.File) : this(getInputStream(profileFile))

    init {
        JsonReader(
            BufferedReader(
                java.io.InputStreamReader(
                    inputStream,
                    java.nio.charset.StandardCharsets.ISO_8859_1
                )
            )
        ).use { reader ->
            if (reader.peek() == com.google.gson.stream.JsonToken.BEGIN_OBJECT) {
                reader.beginObject()
                while (reader.hasNext()) {
                    val objectKey: String? = reader.nextName()
                    if ("otherData" == objectKey) {
                        buildMetadata = parseBuildMetadata(reader)
                    } else if ("traceEvents" == objectKey) {
                        traceEvents = TraceEvent.Companion.parseTraceEvents(reader)
                        phaseSummaryStatistics = PhaseSummaryStatistics()
                        var lastPhaseEvent: TraceEvent? = null
                        var maxEndTime: java.time.Duration = java.time.Duration.ZERO
                        for (traceEvent in traceEvents!!) {
                            if (traceEvent.timestamp != null) {
                                var curEndTime: java.time.Duration = traceEvent.timestamp
                                if (traceEvent.duration != null) {
                                    curEndTime = curEndTime.plus(traceEvent.duration)
                                }
                                if (curEndTime.compareTo(maxEndTime) > 0) {
                                    maxEndTime = curEndTime
                                }
                            }
                            if (com.google.devtools.build.lib.profiler.ProfilerTask.PHASE.description == traceEvent.category) {
                                if (lastPhaseEvent != null) {
                                    phaseSummaryStatistics.addProfilePhase(
                                        com.google.devtools.build.lib.profiler.ProfilePhase.Companion.getPhaseFromDescription(
                                            lastPhaseEvent.name
                                        ),
                                        traceEvent.timestamp.minus(lastPhaseEvent.timestamp)
                                    )
                                }
                                lastPhaseEvent = traceEvent
                            }
                        }
                        if (lastPhaseEvent != null) {
                            phaseSummaryStatistics.addProfilePhase(
                                com.google.devtools.build.lib.profiler.ProfilePhase.Companion.getPhaseFromDescription(
                                    lastPhaseEvent.name
                                ),
                                maxEndTime.minus(lastPhaseEvent.timestamp)
                            )
                        }
                    } else {
                        reader.skipValue()
                    }
                }
            }
        }
        if (traceEvents == null) {
            throw IOException("Corrupted profile file: couldn't find 'traceEvents'.")
        }
    }

    fun getPhaseSummaryStatistics(): PhaseSummaryStatistics {
        return phaseSummaryStatistics
    }

    fun getTraceEvents(): MutableList<TraceEvent> {
        return traceEvents
    }

    fun getBuildMetadata(): BuildMetadata? {
        return buildMetadata
    }

    /** Value class to hold build metadata (id, date, output base) if available.  */
    @kotlin.jvm.JvmRecord
    data class BuildMetadata(buildId: String?, date: String?, outputBase: String?) {
        val buildId: String?
        val date: String?
        val outputBase: String?

        init {
            this.buildId = buildId
            this.date = date
            this.outputBase = outputBase
        }

        companion object {
            fun create(
                buildId: String?, date: String?, outputBase: String?
            ): BuildMetadata {
                return BuildMetadata(buildId, date, outputBase)
            }
        }
    }

    companion object {
        @Throws(IOException::class)
        private fun getInputStream(profileFile: java.io.File): java.io.InputStream {
            var inputStream: java.io.InputStream = FileInputStream(profileFile)
            if (profileFile.getName().endsWith(".gz")) {
                inputStream = GZIPInputStream(inputStream)
            }
            return inputStream
        }

        @Throws(IOException::class)
        private fun parseBuildMetadata(reader: JsonReader): BuildMetadata {
            reader.beginObject()
            var buildId: String? = null
            var date: String? = null
            var outputBase: String? = null
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "build_id" -> buildId = reader.nextString()
                    "date" -> date = reader.nextString()
                    "output_base" -> outputBase = reader.nextString()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            return BuildMetadata.Companion.create(buildId, date, outputBase)
        }
    }
}
