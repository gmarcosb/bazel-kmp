// Copyright 2023 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.metrics

import com.google.common.flogger.GoogleLogger
import com.google.devtools.build.lib.clock.Clock.now
import com.google.devtools.build.lib.metrics.ResourceSnapshot
import java.io.BufferedReader
import java.io.IOException
import java.time.Instant

/**
 * Helps to collect information about all process using ps command. Works for Linux and MacOS
 * systems.
 */
class PsInfoCollector  // prevent construction
private constructor() {
    private var currentPsSnapshot: PsSnapshot? = null

    /**
     * If ps snapshot was outdated will update it, and then returns resource consumption snapshot of
     * processes subtrees based on collected ps snapshot.
     */
    @kotlin.jvm.Synchronized
    fun collectResourceUsage(
        processIds: com.google.common.collect.ImmutableSet<Long?>, clock: com.google.devtools.build.lib.clock.Clock
    ): ResourceSnapshot {
        val now: Instant? = clock.now()
        if (currentPsSnapshot == null || (java.time.Duration.between(currentPsSnapshot!!.collectionTime, now)
                .compareTo(MIN_COLLECTION_INTERVAL)
                    > 0) || currentPsSnapshot!!.processPidsHash != processIds.hashCode().toLong()
        ) {
            updatePsSnapshot(clock, processIds)
        }

        val pidToMemoryInKb: com.google.common.collect.ImmutableMap.Builder<Long?, Int?> =
            com.google.common.collect.ImmutableMap.builder<Long?, Int?>()
        for (pid in processIds) {
            val psInfo: PsInfo? = currentPsSnapshot!!.pidToPsInfo.get(pid)
            if (psInfo == null) {
                continue
            }
            pidToMemoryInKb.put(pid, Companion.collectMemoryUsageOfDescendants(psInfo, currentPsSnapshot!!))
        }

        return ResourceSnapshot.Companion.create(
            pidToMemoryInKb.buildOrThrow(), currentPsSnapshot!!.collectionTime
        )
    }

    /** Updates current snapshot of all processes state, using ps command.  */
    private fun updatePsSnapshot(
        clock: com.google.devtools.build.lib.clock.Clock,
        processIds: com.google.common.collect.ImmutableSet<Long?>
    ) {
        val pidToPsInfo: com.google.common.collect.ImmutableMap<Long?, PsInfo?> = collectDataFromPs()

        val pidToChildrenPsInfo: com.google.common.collect.ImmutableSetMultimap<Long?, PsInfo?> =
            pidToPsInfo.values.stream()
                .collect(
                    com.google.common.collect.ImmutableSetMultimap.toImmutableSetMultimap<PsInfo?, Long?, PsInfo?>(
                        PsInfo::parentPid,
                        java.util.function.Function.identity<PsInfo?>()
                    )
                )

        currentPsSnapshot =
            PsSnapshot(pidToPsInfo, pidToChildrenPsInfo, clock.now(), processIds.hashCode().toLong())
    }

    /** Collects memory usage for every process.  */
    @com.google.common.annotations.VisibleForTesting
    fun collectDataFromPs(): com.google.common.collect.ImmutableMap<Long?, PsInfo?> {
        try {
            val psProcess: java.lang.Process = buildPsProcess()
            return collectDataFromPsProcess(psProcess)
        } catch (e: IOException) {
            logger.atWarning().withCause(e).log("Error while executing command ps")
            return com.google.common.collect.ImmutableMap.of<Long?, PsInfo?>()
        }
    }

    /** Parsed information about process collected after ps command call.  */
    @kotlin.jvm.JvmRecord
    internal data class PsInfo(val pid: Long, val parentPid: Long, val memoryInKb: Int)

    /** Contains structurized information from ps command.  */
    private class PsSnapshot(
        pidToPsInfo: com.google.common.collect.ImmutableMap<Long?, PsInfo?>?,
        pidToChildrenPsInfo: com.google.common.collect.ImmutableSetMultimap<Long?, PsInfo?>?,
        collectionTime: Instant?,
        val processPidsHash: Long
    ) {
        val pidToPsInfo: com.google.common.collect.ImmutableMap<Long?, PsInfo?>?
        val pidToChildrenPsInfo: com.google.common.collect.ImmutableSetMultimap<Long?, PsInfo?>?
        val collectionTime: Instant?

        init {
            this.collectionTime = collectionTime
            this.pidToChildrenPsInfo = pidToChildrenPsInfo
            this.pidToPsInfo = pidToPsInfo
            java.util.Objects.requireNonNull<com.google.common.collect.ImmutableMap<Long?, PsInfo?>?>(pidToPsInfo)
            java.util.Objects.requireNonNull<com.google.common.collect.ImmutableSetMultimap<Long?, PsInfo?>?>(
                pidToChildrenPsInfo
            )
            java.util.Objects.requireNonNull<Instant?>(collectionTime)
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        // Updates snapshots no more than once per interval. Running ps is somewhat slow and should not be
        // done too often.
        private val MIN_COLLECTION_INTERVAL: java.time.Duration = java.time.Duration.ofMillis(500)
        private val instance = PsInfoCollector()

        fun instance(): PsInfoCollector {
            return instance
        }

        fun collectDataFromPsProcess(psProcess: java.lang.Process): com.google.common.collect.ImmutableMap<Long?, PsInfo?> {
            val psOutput: BufferedReader =
                BufferedReader(
                    java.io.InputStreamReader(
                        psProcess.getInputStream(),
                        java.nio.charset.StandardCharsets.UTF_8
                    )
                )

            val psInfos: com.google.common.collect.ImmutableMap.Builder<Long?, PsInfo?> =
                com.google.common.collect.ImmutableMap.builder<Long?, PsInfo?>()

            try {
                // The output of the above ps command looks similar to this:
                // PID     PPID   RSS
                // 211706  1      222972
                // 2612333 211706 6180
                // We skip over the first line (the header) and then parse the PID and the resident memory
                // size in kilobytes.
                var output: String? = null
                var isFirst = true
                while ((psOutput.readLine().also { output = it }) != null) {
                    if (isFirst) {
                        isFirst = false
                        continue
                    }
                    val line: MutableList<String?> =
                        com.google.common.base.Splitter.on(" ").trimResults().omitEmptyStrings().splitToList(output)
                    if (line.size != 3) {
                        logger.atWarning().log("Unexpected length of split line %s %d", output, line.size)
                        continue
                    }

                    val pid: Long = line.get(0).toLong()
                    val parentPid: Long = line.get(1).toLong()
                    val memoryInKb: Int = line.get(2).toInt()

                    psInfos.put(pid, PsInfo(pid, parentPid, memoryInKb))
                }
            } catch (e: java.lang.IllegalArgumentException) {
                logger.atWarning().withCause(e).log("Error while parsing psOutput: %s", psOutput)
            } catch (e: IOException) {
                logger.atWarning().withCause(e).log("Error while parsing psOutput: %s", psOutput)
            }

            // In rare cases a PID might get reused while `ps` is scanning `/proc`. Avoid a crash.
            return psInfos.buildKeepingLast()
        }

        @Throws(IOException::class)
        private fun buildPsProcess(): java.lang.Process {
            return java.lang.ProcessBuilder("ps", "-e", "-o", "pid,ppid,rss").start()
        }

        /** Recursively collects total memory usage of all descendants of the process.  */
        private fun collectMemoryUsageOfDescendants(psInfo: PsInfo, psSnapshot: PsSnapshot): Int {
            var currentMemoryInKb = psInfo.memoryInKb
            for (childrenPsInfo in psSnapshot.pidToChildrenPsInfo.get(psInfo.pid)) {
                currentMemoryInKb += collectMemoryUsageOfDescendants(childrenPsInfo, psSnapshot)
            }

            return currentMemoryInKb
        }
    }
}
