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

/** Service for querying system network stats.  */
@com.google.devtools.build.lib.skybridge.SkybridgeInterface
interface SystemNetworkStatsService : com.google.devtools.build.lib.runtime.BlazeService {
    /** Returns a map from network interface name to the respective I/O counters.  */
    @Throws(IOException::class)
    fun getNetIoCounters(): MutableMap<String?, NetIoCounter?>?

    /**
     * Value class for network IO counters.
     * 
     * @param bytesSent Number of bytes sent.
     * @param bytesRecv Number of bytes received.
     * @param packetsSent Number of packets sent.
     * @param packetsRecv Number of packets received.
     */
    @kotlin.jvm.JvmRecord
    data class NetIoCounter(bytesSent: Long, bytesRecv: Long, packetsSent: Long, packetsRecv: Long) {
        val bytesSent: Long
        val bytesRecv: Long
        val packetsSent: Long
        val packetsRecv: Long

        init {
            this.bytesSent = bytesSent
            this.bytesRecv = bytesRecv
            this.packetsSent = packetsSent
            this.packetsRecv = packetsRecv
        }

        companion object {
            fun create(
                bytesSent: Long, bytesRecv: Long, packetsSent: Long, packetsRecv: Long
            ): NetIoCounter {
                return NetIoCounter(bytesSent, bytesRecv, packetsSent, packetsRecv)
            }
        }
    }
}
