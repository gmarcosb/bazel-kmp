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

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.NetworkMetrics

/** Collects and populates network metrics during an invocation.  */
class NetworkMetricsCollector private constructor() {
    private var loopbackInterfaceNames: com.google.common.collect.ImmutableSet<String?>? = null
    private var previousNetworkIoCounters: MutableMap<String?, NetIoCounter?>? = null
    private val systemNetworkStats: NetworkMetrics.SystemNetworkStats.Builder =
        NetworkMetrics.SystemNetworkStats.newBuilder()

    fun collectMetrics(): NetworkMetrics? {
        if (systemNetworkStats.getBytesRecv() === 0 || systemNetworkStats.getBytesSent() === 0) {
            return null
        }

        return NetworkMetrics.newBuilder().setSystemNetworkStats(systemNetworkStats.build()).build()
    }

    fun collectSystemNetworkUsages(
        deltaNanos: Double, systemNetworkStatsService: SystemNetworkStatsService
    ): SystemNetworkUsages? {
        if (loopbackInterfaceNames == null) {
            try {
                loopbackInterfaceNames = getLoopbackInterfaceNames()
            } catch (e: IOException) {
                logger.atWarning().withCause(e).log("Failed to get loopback interface names")
            }
        }

        var nextNetworkIoCounters: MutableMap<String?, NetIoCounter?>? = null
        try {
            nextNetworkIoCounters = systemNetworkStatsService.getNetIoCounters()
        } catch (e: IOException) {
            logger.atWarning().withCause(e).log("Failed to get Net IO counters")
        }

        if (previousNetworkIoCounters == null) {
            previousNetworkIoCounters = nextNetworkIoCounters
        }

        var usages: SystemNetworkUsages? = null
        if (previousNetworkIoCounters != null && nextNetworkIoCounters != null) {
            var deltaBytesSent: Long = 0
            var deltaBytesRecv: Long = 0
            var deltaPacketsSent: Long = 0
            var deltaPacketsRecv: Long = 0
            for (entry in previousNetworkIoCounters.entrySet()) {
                val name: String? = entry.getKey()
                if (loopbackInterfaceNames.contains(name)) {
                    continue
                }
                val previous: NetIoCounter = entry.getValue()
                val next: NetIoCounter? = nextNetworkIoCounters.get(name)
                if (next != null) {
                    deltaBytesSent += calcDelta(previous.bytesSent, next.bytesSent)
                    deltaBytesRecv += calcDelta(previous.bytesRecv, next.bytesRecv)
                    deltaPacketsSent += calcDelta(previous.packetsSent, next.packetsSent)
                    deltaPacketsRecv += calcDelta(previous.packetsRecv, next.packetsRecv)
                }
            }

            systemNetworkStats.setBytesRecv(systemNetworkStats.getBytesRecv() + deltaBytesRecv)
            systemNetworkStats.setBytesSent(systemNetworkStats.getBytesSent() + deltaBytesSent)
            systemNetworkStats.setPacketsRecv(systemNetworkStats.getPacketsRecv() + deltaPacketsRecv)
            systemNetworkStats.setPacketsSent(systemNetworkStats.getPacketsSent() + deltaPacketsSent)

            usages =
                SystemNetworkUsages.Companion.create(
                    calcValuePerSec(deltaBytesSent, deltaNanos),
                    calcValuePerSec(deltaBytesRecv, deltaNanos),
                    calcValuePerSec(deltaPacketsSent, deltaNanos),
                    calcValuePerSec(deltaPacketsRecv, deltaNanos)
                )

            if (usages.bytesSentPerSec > systemNetworkStats.getPeakBytesSentPerSec()) {
                systemNetworkStats.setPeakBytesSentPerSec(usages.bytesSentPerSec.toLong())
            }
            if (usages.bytesRecvPerSec > systemNetworkStats.getPeakBytesRecvPerSec()) {
                systemNetworkStats.setPeakBytesRecvPerSec(usages.bytesRecvPerSec.toLong())
            }
            if (usages.packetsSentPerSec > systemNetworkStats.getPeakPacketsSentPerSec()) {
                systemNetworkStats.setPeakPacketsSentPerSec(usages.packetsSentPerSec.toLong())
            }
            if (usages.packetsRecvPerSec > systemNetworkStats.getPeakPacketsRecvPerSec()) {
                systemNetworkStats.setPeakPacketsRecvPerSec(usages.packetsRecvPerSec.toLong())
            }
        }

        previousNetworkIoCounters = nextNetworkIoCounters

        return usages
    }

    /** Aggregated system network usages over all interfaces except local loopback.  */
    @kotlin.jvm.JvmRecord
    data class SystemNetworkUsages(
        bytesSentPerSec: Double,
        bytesRecvPerSec: Double,
        packetsSentPerSec: Double,
        packetsRecvPerSec: Double
    ) {
        fun megabitsSentPerSec(): Double {
            return bytesPerSecToMegabitsPerSec(this.bytesSentPerSec)
        }

        fun megabitsRecvPerSec(): Double {
            return bytesPerSecToMegabitsPerSec(this.bytesRecvPerSec)
        }

        val bytesSentPerSec: Double
        val bytesRecvPerSec: Double
        val packetsSentPerSec: Double
        val packetsRecvPerSec: Double

        init {
            this.bytesSentPerSec = bytesSentPerSec
            this.bytesRecvPerSec = bytesRecvPerSec
            this.packetsSentPerSec = packetsSentPerSec
            this.packetsRecvPerSec = packetsRecvPerSec
        }

        companion object {
            fun create(
                bytesSentPerSec: Double,
                bytesRecvPerSec: Double,
                packetsSentPerSec: Double,
                packetsRecvPerSec: Double
            ): SystemNetworkUsages {
                return SystemNetworkUsages(
                    bytesSentPerSec, bytesRecvPerSec, packetsSentPerSec, packetsRecvPerSec
                )
            }
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /** The metrics collector (a static singleton instance). Inactive by default.  */
        private val instance = NetworkMetricsCollector()

        fun instance(): NetworkMetricsCollector {
            return instance
        }

        @Throws(IOException::class)
        private fun getLoopbackInterfaceNames(): com.google.common.collect.ImmutableSet<String?> {
            val result: com.google.common.collect.ImmutableSet.Builder<String?> =
                com.google.common.collect.ImmutableSet.builder<String?>()
            val ifaces: Enumeration<NetworkInterface> = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val iface: NetworkInterface = ifaces.nextElement()
                if (iface.isLoopback()) {
                    result.add(iface.getName())
                }
            }
            return result.build()
        }

        private fun calcDelta(prev: Long, next: Long): Long {
            if (java.lang.Long.compareUnsigned(next, prev) < 0) {
                return next
            }
            return next - prev
        }

        private fun calcValuePerSec(deltaValue: Long, deltaNanos: Double): Double {
            return deltaValue / deltaNanos * 1e9
        }

        private fun bytesPerSecToMegabitsPerSec(bytesPerSec: Double): Double {
            return bytesPerSec / 1e6 * 8
        }
    }
}
