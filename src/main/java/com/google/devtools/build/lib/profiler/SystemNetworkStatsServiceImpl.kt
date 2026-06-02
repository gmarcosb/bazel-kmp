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

import com.google.devtools.build.lib.profiler.SystemNetworkStatsService
import com.google.devtools.build.lib.profiler.SystemNetworkStatsService.NetIoCounter
import java.io.IOException
import java.nio.file.Paths
import java.util.HashMap

/** Utility class for query system network stats.  */
class SystemNetworkStatsServiceImpl : SystemNetworkStatsService {
    @Throws(IOException::class)
    override fun getNetIoCounters(): MutableMap<String?, NetIoCounter?> {
        val countersMap: HashMap<String?, NetIoCounter?> = HashMap<String?, NetIoCounter?>()
        when (com.google.devtools.build.lib.util.OS.getCurrent()) {
            com.google.devtools.build.lib.util.OS.LINUX -> getNetIoCountersLinux(countersMap)
            else -> getNetIoCountersNative(countersMap)
        }
        return countersMap
    }

    companion object {
        private val SPLITTER: com.google.common.base.Splitter =
            com.google.common.base.Splitter.on(" ").omitEmptyStrings().trimResults()

        init {
            com.google.devtools.build.lib.jni.JniLoader.loadJni()
        }

        @Throws(IOException::class)
        private fun getNetIoCountersLinux(countersMap: MutableMap<String?, NetIoCounter?>) {
            val lines: MutableList<String> =
                java.nio.file.Files.readAllLines(Paths.get("/proc/net/dev"), java.nio.charset.StandardCharsets.UTF_8)

            // skip table header (first 2 lines)
            for (line in lines.subList(2, lines.size())) {
                val colonAt: Int = line.indexOf(':'.code)
                if (colonAt < 0) {
                    continue
                }
                val name: String = line.substring(0, colonAt).strip()
                val fields: LongArray =
                    SPLITTER
                        .splitToStream(line.substring(colonAt + 1))
                        .mapToLong(java.util.function.ToLongFunction { s: String? -> java.lang.Long.parseUnsignedLong(s) })
                        .toArray()
                if (fields.length > 9) {
                    val bytesRecv = fields[0]
                    val packetsRecv = fields[1]
                    val bytesSent = fields[8]
                    val packetsSent = fields[9]
                    countersMap.put(name, NetIoCounter.Companion.create(bytesSent, bytesRecv, packetsSent, packetsRecv))
                }
            }
        }

        @Throws(IOException::class)
        private external fun getNetIoCountersNative(countersMap: MutableMap<String?, NetIoCounter?>?)
    }
}
