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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.unix.ProcMeminfoParser

/**
 * Provides methods to measure the current resource usage of the current process. Also provides some
 * convenience methods to obtain several system characteristics, like number of processors , total
 * memory, etc.
 */
object ResourceUsage {
    /*
   * Use com.sun.management.OperatingSystemMXBean instead of
   * java.lang.management.OperatingSystemMXBean because the latter does not
   * support getTotalPhysicalMemorySize() and getFreePhysicalMemorySize().
   */
    private val OS_BEAN: com.sun.management.OperatingSystemMXBean =
        java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean

    private val MEM_BEAN: java.lang.management.MemoryMXBean = java.lang.management.ManagementFactory.getMemoryMXBean()
    private val WHITESPACE_SPLITTER: com.google.common.base.Splitter =
        com.google.common.base.Splitter.on(com.google.common.base.CharMatcher.whitespace())
    private val PSI_AVG10_VALUE_PATTERN_FULL: java.util.regex.Pattern =
        java.util.regex.Pattern.compile("^full avg10=([\\d.]+).*")
    private val PSI_AVG10_VALUE_PATTERN_SOME: java.util.regex.Pattern =
        java.util.regex.Pattern.compile("^some avg10=([\\d.]+).*")
    private const val PSI_AVG10_START_FULL = "full avg10"
    private const val PSI_AVG10_START_SOME = "some avg10"

    val availableProcessors: Int
        /** Returns the number of processors available to the Java virtual machine.  */
        get() = OS_BEAN.getAvailableProcessors()

    val totalPhysicalMemorySize: Long
        /** Returns the total physical memory in bytes.  */
        get() = OS_BEAN.getTotalPhysicalMemorySize()

    val osArchitecture: String?
        /** Returns the operating system architecture.  */
        get() = OS_BEAN.getArch()

    val osName: String?
        /** Returns the operating system name.  */
        get() = OS_BEAN.getName()

    val osVersion: String?
        /** Returns the operating system version.  */
        get() = OS_BEAN.getVersion()

    val heapMemoryInit: Long
        /**
         * Returns the initial size of heap memory in bytes.
         * 
         * @see MemoryMXBean.getHeapMemoryUsage
         */
        get() = MEM_BEAN.getHeapMemoryUsage().getInit()

    val nonHeapMemoryInit: Long
        /**
         * Returns the initial size of non heap memory in bytes.
         * 
         * @see MemoryMXBean.getNonHeapMemoryUsage
         */
        get() = MEM_BEAN.getNonHeapMemoryUsage().getInit()

    val heapMemoryMax: Long
        /**
         * Returns the maximum size of heap memory in bytes.
         * 
         * @see MemoryMXBean.getHeapMemoryUsage
         */
        get() = MEM_BEAN.getHeapMemoryUsage().getMax()

    val nonHeapMemoryMax: Long
        /**
         * Returns the maximum size of non heap memory in bytes.
         * 
         * @see MemoryMXBean.getNonHeapMemoryUsage
         */
        get() = MEM_BEAN.getNonHeapMemoryUsage().getMax()

    /** Returns a measurement of the current resource usage of the current process.  */
    fun measureCurrentResourceUsage(): Measurement {
        return Measurement(
            java.lang.System.nanoTime(),
            MEM_BEAN.getHeapMemoryUsage().getUsed(),
            MEM_BEAN.getHeapMemoryUsage().getCommitted(),
            MEM_BEAN.getNonHeapMemoryUsage().getUsed(),
            MEM_BEAN.getNonHeapMemoryUsage().getCommitted(),
            OS_BEAN.getSystemLoadAverage().toFloat(),
            readPressureStallIndicator(
                PressureStallIndicatorResource.MEMORY, PressureStallIndicatorMetric.FULL
            ),
            readPressureStallIndicator(
                PressureStallIndicatorResource.IO, PressureStallIndicatorMetric.FULL
            ),
            availableMemory,
            currentCpuUtilizationInMs
        )
    }

    private val currentCpuUtilizationInMs: LongArray
        /**
         * Returns the current cpu utilization of the current process with the given id in ms. The
         * returned array contains the following information: The 1st entry is the number of ms that the
         * process has executed in user mode, and the 2nd entry is the number of ms that the process has
         * executed in kernel mode. Reads /proc/self/stat to obtain this information. The values may not
         * have millisecond accuracy.
         */
        get() {
            try {
                val file: java.io.File = java.io.File("/proc/self/stat")
                if (file.isDirectory() || !file.canRead()) {
                    return LongArray(2)
                }
                val stat: MutableList<String?> =
                    WHITESPACE_SPLITTER.splitToList(
                        com.google.common.io.Files.asCharSource(
                            file,
                            java.nio.charset.StandardCharsets.US_ASCII
                        ).read()
                    )
                if (stat.size < 15) {
                    return LongArray(2) // Tolerate malformed input.
                }
                // /proc/self/stat contains values in jiffies, which are 10 ms.
                return longArrayOf(stat.get(13).toLong() * 10, stat.get(14).toLong() * 10)
            } catch (e: java.lang.NumberFormatException) {
                return LongArray(2)
            } catch (e: IOException) {
                return LongArray(2)
            }
        }

    /**
     * Reads the Pressure Staller Indicator file for a given type and returns the double value for
     * `avg10`, or -1 if we couldn't read that value.
     */
    fun readPressureStallIndicator(
        resource: PressureStallIndicatorResource, metric: PressureStallIndicatorMetric
    ): Float {
        val fileName = "/proc/pressure/" + resource.resource
        val procFile: java.io.File = java.io.File(fileName)
        if (!procFile.canRead()) {
            return -1.0f
        }
        try {
            val lines: MutableList<String> =
                com.google.common.io.Files.readLines(procFile, java.nio.charset.Charset.defaultCharset())
            for (line in lines) {
                when (metric) {
                    PressureStallIndicatorMetric.FULL -> {
                        // Tries to find a line in file with the `full` metrics
                        if (!line.startsWith(PSI_AVG10_START_FULL)) {
                            break
                        }
                        val fullMatcher: java.util.regex.Matcher = PSI_AVG10_VALUE_PATTERN_FULL.matcher(line)
                        if (!fullMatcher.matches()) {
                            return -1.0f
                        }
                        return fullMatcher.group(1).toFloat()
                    }

                    PressureStallIndicatorMetric.SOME -> {
                        // Tries to find a line in file with the `some` metrics
                        if (!line.startsWith(PSI_AVG10_START_SOME)) {
                            break
                        }
                        val someMatcher: java.util.regex.Matcher = PSI_AVG10_VALUE_PATTERN_SOME.matcher(line)
                        if (!someMatcher.matches()) {
                            return -1.0f
                        }
                        return someMatcher.group(1).toFloat()
                    }
                }
            }
            return -1.0f
        } catch (e: IOException) {
            return -1.0f
        }
    }

    val availableMemory: Long
        get() {
            var availableMemory: Long
            try {
                // TODO(larsrc): Use control flow instead of execptions
                val meminfo: ProcMeminfoParser = ProcMeminfoParser()
                // Convert to bytes so that the fallback units are consistent.
                availableMemory = meminfo.getFreeRamKb() shl 10
            } catch (e: IOException) {
                // /proc/meminfo isn't available outside Linux. On OS X, the OperatingSystem bean returns the
                // number of free pages multiplied by the page size, which is still incorrect. What we really
                // want here is (vm_stats.inactive_count + vm_stats.free_count) * page_size, but Java gives us
                // only free.
                // Seems like some virtual Ganeti machines also have issues getting this.
                availableMemory = OS_BEAN.getFreePhysicalMemorySize()
            }
            return availableMemory
        }

    /**
     * Represents a type of resource which pressure stall indicator could be collected.
     * 
     * 
     * Indicators for only this 3 types of resources are available in Linux machines.
     */
    enum class PressureStallIndicatorResource(val resource: String) {
        MEMORY("memory"),
        IO("io"),
        CPU("cpu")
    }

    /**
     * Represents a type of metric for pressure stall indicators. The "some" metric indicates the
     * share of time in which at least some tasks are stalled on a given resource. The "full" metric
     * indicates the share of time in which all non-idle tasks are stalled on a given resource
     * simultaneously. (CPU full is undefined at the system level, by default always zero)
     */
    enum class PressureStallIndicatorMetric(metric: String) {
        FULL("full"),
        SOME("some");

        val metric: String?

        init {
            this.metric = metric
        }
    }

    /**
     * A snapshot of the resource usage of the current process at a point in time.
     * 
     * @attr timeInNanos The time of the measurement in nanoseconds.
     * @attr heapMemoryUsed The amount of heap memory used in bytes.
     * @attr heapMemoryCommitted The amount of heap memory committed in bytes.
     * @attr nonHeapMemoryUsed The amount of non-heap memory used in bytes.
     * @attr nonHeapMemoryCommitted The amount of non-heap memory committed in bytes.
     * @attr loadAverageLastMinute The load average of the system in the last minute.
     * @attr memoryPressureLast10Sec The memory pressure from the Linux Pressure Stall Indicator
     * system, or -1 if PSI cannot be read.
     * @attr ioPressureLast10Sec The IO pressure from the Linux Pressure Stall Indicator system, or -1
     * if PSI cannot be read.
     * @attr freePhysicalMemory The amount of free physical memory in bytes.
     * @attr cpuUtilizationInMs The current cpu utilization of the current process in ms. The returned
     * array contains the following information: The 1st entry is the number of ms that the
     * process has executed in user mode, and the 2nd entry is the number of ms that the process
     * has executed in kernel mode. Reads /proc/self/stat to obtain this information.
     */
    @kotlin.jvm.JvmRecord
    data class Measurement(
        val timeInNanos: Long,
        val heapMemoryUsed: Long,
        val heapMemoryCommitted: Long,
        val nonHeapMemoryUsed: Long,
        val nonHeapMemoryCommitted: Long,
        val loadAverageLastMinute: Float,
        val memoryPressureLast10Sec: Float,
        val ioPressureLast10Sec: Float,
        val freePhysicalMemory: Long,
        val cpuUtilizationInMs: LongArray?
    ) {
        /** Returns the time of the measurement in ms.  */
        fun timeInMs(): Long {
            return timeInNanos / 1000000
        }

        public override fun cpuUtilizationInMs(): LongArray {
            return longArrayOf(cpuUtilizationInMs!![0], cpuUtilizationInMs[1])
        }
    }
}
