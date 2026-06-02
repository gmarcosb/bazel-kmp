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

import com.google.devtools.build.lib.actions.ResourceEstimator

/** An assortment of classes that collects various interesting metrics about the local system.  */
class LocalResourceUsageCollectors(
    bugReporter: BugReporter,
    graph: InMemoryGraph,
    workerProcessMetricsCollector: WorkerProcessMetricsCollector,
    resourceEstimator: ResourceEstimator,
    systemNetworkStatsService: SystemNetworkStatsService?
) {
    private val bugReporter: BugReporter

    private val graph: InMemoryGraph
    private val workerProcessMetricsCollector: WorkerProcessMetricsCollector

    private val resourceEstimator: ResourceEstimator

    private val systemNetworkStatsService: SystemNetworkStatsService?

    init {
        this.bugReporter = bugReporter
        this.graph = graph
        this.workerProcessMetricsCollector = workerProcessMetricsCollector
        this.resourceEstimator = resourceEstimator
        this.systemNetworkStatsService = systemNetworkStatsService
    }

    fun addCollectors(
        collectWorkerDataInProfiler: Boolean,
        collectLoadAverage: Boolean,
        collectSystemNetworkUsage: Boolean,
        collectResourceManagerEstimation: Boolean,
        collectPressureStallIndicators: Boolean,
        collectSkyframeCounts: Boolean
    ) {
        com.google.common.base.Preconditions.checkState(
            !collectSkyframeCounts || graph != null,
            "--experimental_collect_skyframe_counts_in_profiler requires the Skyframe graph."
        )

        val osBean: com.sun.management.OperatingSystemMXBean =
            java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        val memoryBean: java.lang.management.MemoryMXBean = java.lang.management.ManagementFactory.getMemoryMXBean()
        com.google.devtools.build.lib.profiler.Profiler.Companion.instance()
            .registerCounterSeriesCollector(LocalCpuUsageCollector(osBean))
        com.google.devtools.build.lib.profiler.Profiler.Companion.instance()
            .registerCounterSeriesCollector(LocalMemoryUsageCollector(memoryBean, bugReporter))
        com.google.devtools.build.lib.profiler.Profiler.Companion.instance()
            .registerCounterSeriesCollector(SystemCpuUsageCollector(osBean))
        com.google.devtools.build.lib.profiler.Profiler.Companion.instance()
            .registerCounterSeriesCollector(SystemMemoryUsageCollector(osBean))

        if (collectWorkerDataInProfiler
            && (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.LINUX || com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.DARWIN)
        ) {
            // Enabling the WorkerMemoryUsageCollector will cause hangs on Windows. We should only enable
            // it on Linux and Darwin.
            com.google.devtools.build.lib.profiler.Profiler.Companion.instance()
                .registerCounterSeriesCollector(
                    TotalWorkerMemoryUsageCollector(workerProcessMetricsCollector)
                )
            com.google.devtools.build.lib.profiler.Profiler.Companion.instance()
                .registerCounterSeriesCollector(
                    PerMnemonicWorkerMemoryUsageCollector(workerProcessMetricsCollector)
                )
        }
        if (collectLoadAverage) {
            com.google.devtools.build.lib.profiler.Profiler.Companion.instance()
                .registerCounterSeriesCollector(SystemLoadAverageCollector(osBean))
        }
        if (collectSystemNetworkUsage) {
            com.google.devtools.build.lib.profiler.Profiler.Companion.instance()
                .registerCounterSeriesCollector(
                    SystemNetworkUsageCollector(systemNetworkStatsService)
                )
        }
        if (collectResourceManagerEstimation) {
            com.google.devtools.build.lib.profiler.Profiler.Companion.instance()
                .registerCounterSeriesCollector(
                    ResourceManagerEstimationCollector(resourceEstimator)
                )
        }
        // The pressure stall indicators are only available on Linux.
        if (collectPressureStallIndicators && com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.LINUX) {
            com.google.devtools.build.lib.profiler.Profiler.Companion.instance()
                .registerCounterSeriesCollector(PressureStallIndicatorCollector())
        }

        if (collectSkyframeCounts) {
            com.google.devtools.build.lib.profiler.Profiler.Companion.instance()
                .registerCounterSeriesCollector(SkyframeCountsCollector(graph))
        }
    }

    internal class LocalCpuUsageCollector private constructor(osBean: com.sun.management.OperatingSystemMXBean) :
        com.google.devtools.build.lib.profiler.CounterSeriesCollector {
        private val osBean: com.sun.management.OperatingSystemMXBean
        private var previousCpuTimeNanos: Long

        init {
            this.osBean = osBean
            this.previousCpuTimeNanos = osBean.getProcessCpuTime()
        }

        override fun collect(
            deltaNanos: Double,
            consumer: java.util.function.BiConsumer<com.google.devtools.build.lib.profiler.CounterSeriesTask?, Double?>
        ) {
            val nextCpuTimeNanos: Long = osBean.getProcessCpuTime()
            val cpuLevel = (nextCpuTimeNanos - previousCpuTimeNanos) / deltaNanos
            previousCpuTimeNanos = nextCpuTimeNanos
            consumer.accept(LOCAL_CPU_USAGE, cpuLevel)
        }

        companion object {
            private val LOCAL_CPU_USAGE: com.google.devtools.build.lib.profiler.CounterSeriesTask =
                com.google.devtools.build.lib.profiler.CounterSeriesTask(
                    "CPU usage (Bazel)",
                    "cpu",
                    com.google.devtools.build.lib.profiler.CounterSeriesTask.Color.GOOD
                )
        }
    }

    internal class LocalMemoryUsageCollector private constructor(
        memoryBean: java.lang.management.MemoryMXBean,
        bugReporter: BugReporter
    ) : com.google.devtools.build.lib.profiler.CounterSeriesCollector {
        private val memoryBean: java.lang.management.MemoryMXBean
        private val bugReporter: BugReporter

        init {
            this.memoryBean = memoryBean
            this.bugReporter = bugReporter
        }

        override fun collect(
            deltaNanos: Double,
            consumer: java.util.function.BiConsumer<com.google.devtools.build.lib.profiler.CounterSeriesTask?, Double?>
        ) {
            var memoryUsage: Long
            try {
                memoryUsage =
                    (memoryBean.getHeapMemoryUsage().getUsed()
                            + memoryBean.getNonHeapMemoryUsage().getUsed())
            } catch (e: java.lang.IllegalArgumentException) {
                // The JVM may report committed > max. See b/180619163.
                bugReporter.sendBugReport(e)
                memoryUsage = -1
            }
            if (memoryUsage != -1L) {
                memoryUsage = memoryUsage / (1024 * 1024)
                consumer.accept(LOCAL_MEMORY_USAGE, memoryUsage.toDouble())
            }
        }

        companion object {
            private val LOCAL_MEMORY_USAGE: com.google.devtools.build.lib.profiler.CounterSeriesTask =
                com.google.devtools.build.lib.profiler.CounterSeriesTask(
                    "Memory usage (Bazel)",
                    "memory",
                    com.google.devtools.build.lib.profiler.CounterSeriesTask.Color.OLIVE
                )
        }
    }

    internal class SystemCpuUsageCollector private constructor(osBean: com.sun.management.OperatingSystemMXBean) :
        com.google.devtools.build.lib.profiler.CounterSeriesCollector {
        private val osBean: com.sun.management.OperatingSystemMXBean
        private val numProcessors: Int

        init {
            this.osBean = osBean
            this.numProcessors = java.lang.Runtime.getRuntime().availableProcessors()
        }

        override fun collect(
            deltaNanos: Double,
            consumer: java.util.function.BiConsumer<com.google.devtools.build.lib.profiler.CounterSeriesTask?, Double?>
        ) {
            var systemCpuLoad: Double
            try {
                systemCpuLoad = osBean.getCpuLoad()
            } catch (unused: java.lang.NullPointerException) {
                // OperatingSystemMXBean.getCpuLoad() suffers from a TOCTOU issue on Linux that can
                // cause a NullPointerException. See https://github.com/bazelbuild/bazel/issues/24519 for
                // details.
                systemCpuLoad = 0.0
            }
            if (java.lang.Double.isNaN(systemCpuLoad)) {
                // Unlike advertised, on Mac the system CPU load is NaN sometimes.
                // There is no good way to handle this, so to avoid any downstream method crashing on
                // this,
                // we reset the CPU value here.
                systemCpuLoad = 0.0
            }
            consumer.accept(SYSTEM_CPU_USAGE, systemCpuLoad * numProcessors)
        }

        companion object {
            private val SYSTEM_CPU_USAGE: com.google.devtools.build.lib.profiler.CounterSeriesTask =
                com.google.devtools.build.lib.profiler.CounterSeriesTask(
                    "CPU usage (total)",
                    "system cpu",
                    com.google.devtools.build.lib.profiler.CounterSeriesTask.Color.RAIL_LOAD
                )
        }
    }

    internal class SystemMemoryUsageCollector private constructor(osBean: com.sun.management.OperatingSystemMXBean) :
        com.google.devtools.build.lib.profiler.CounterSeriesCollector {
        private val osBean: com.sun.management.OperatingSystemMXBean

        init {
            this.osBean = osBean
        }

        override fun collect(
            deltaNanos: Double,
            consumer: java.util.function.BiConsumer<com.google.devtools.build.lib.profiler.CounterSeriesTask?, Double?>
        ) {
            var systemMemoryUsageMb: Long = -1
            if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.LINUX) {
                // On Linux we get a better estimate by using /proc/meminfo. See
                // https://www.linuxatemyram.com/ for more info on buffer caches.
                try {
                    val procMeminfoParser: ProcMeminfoParser = ProcMeminfoParser("/proc/meminfo")
                    systemMemoryUsageMb =
                        (procMeminfoParser.getTotalKb() - procMeminfoParser.getFreeRamKb()) / 1024
                } catch (e: IOException) {
                    // Silently ignore and fallback.
                }
            }
            if (systemMemoryUsageMb <= 0) {
                // In case we aren't running on Linux or /proc/meminfo parsing went wrong, fall back to
                // the OS bean.
                systemMemoryUsageMb =
                    ((osBean.getTotalPhysicalMemorySize() - osBean.getFreePhysicalMemorySize())
                            / (1024 * 1024))
            }
            consumer.accept(SYSTEM_MEMORY_USAGE, systemMemoryUsageMb.toDouble())
        }

        companion object {
            private val SYSTEM_MEMORY_USAGE: com.google.devtools.build.lib.profiler.CounterSeriesTask =
                com.google.devtools.build.lib.profiler.CounterSeriesTask(
                    "Memory usage (total)",
                    "system memory",
                    com.google.devtools.build.lib.profiler.CounterSeriesTask.Color.BAD
                )
        }
    }

    internal class TotalWorkerMemoryUsageCollector private constructor(workerProcessMetricsCollector: WorkerProcessMetricsCollector) :
        com.google.devtools.build.lib.profiler.CounterSeriesCollector {
        private val workerProcessMetricsCollector: WorkerProcessMetricsCollector

        init {
            this.workerProcessMetricsCollector = workerProcessMetricsCollector
        }

        override fun collect(
            deltaNanos: Double,
            consumer: java.util.function.BiConsumer<com.google.devtools.build.lib.profiler.CounterSeriesTask?, Double?>
        ) {
            var workerMemoryUsageMb = 0
            com.google.devtools.build.lib.profiler.Profiler.Companion.instance().profile("Worker metrics collection")
                .use { c ->
                    workerMemoryUsageMb =
                        (workerProcessMetricsCollector.getLiveWorkerProcessMetrics().stream()
                            .mapToInt(ToIntFunction { obj: WorkerProcessMetrics? -> obj.getUsedMemoryInKb() })
                            .sum()
                                / 1024)
                }
            consumer.accept(WORKERS_MEMORY_USAGE, workerMemoryUsageMb.toDouble())
        }

        companion object {
            private val WORKERS_MEMORY_USAGE: com.google.devtools.build.lib.profiler.CounterSeriesTask =
                com.google.devtools.build.lib.profiler.CounterSeriesTask(
                    "Total worker memory usage",
                    "workers memory",
                    com.google.devtools.build.lib.profiler.CounterSeriesTask.Color.RAIL_ANIMATION
                )
        }
    }

    internal class PerMnemonicWorkerMemoryUsageCollector private constructor(workerProcessMetricsCollector: WorkerProcessMetricsCollector) :
        com.google.devtools.build.lib.profiler.CounterSeriesCollector {
        private val workerProcessMetricsCollector: WorkerProcessMetricsCollector

        init {
            this.workerProcessMetricsCollector = workerProcessMetricsCollector
        }

        override fun collect(
            deltaNanos: Double,
            consumer: java.util.function.BiConsumer<com.google.devtools.build.lib.profiler.CounterSeriesTask?, Double?>
        ) {
            workerProcessMetricsCollector
                .getLiveWorkerProcessMetrics()
                .forEach(
                    java.util.function.Consumer { collector: WorkerProcessMetrics? ->
                        com.google.devtools.build.lib.profiler.Profiler.Companion.instance()
                            .profile(collector.getMnemonic()).use { c ->
                                consumer.accept(
                                    getWorkerMemoryUsageSeries(collector.getMnemonic()),
                                    collector.getUsedMemoryInKb().toDouble() / 1024
                                )
                            }
                    })
        }

        companion object {
            private val workerMemoryUsageSeries: HashMap<String?, com.google.devtools.build.lib.profiler.CounterSeriesTask> =
                HashMap<String?, com.google.devtools.build.lib.profiler.CounterSeriesTask>()
            private const val SERIES_LANE_NAME = "Per-mnemonic worker memory usage"
            private fun getWorkerMemoryUsageSeries(mnemonic: String?): com.google.devtools.build.lib.profiler.CounterSeriesTask {
                return workerMemoryUsageSeries.computeIfAbsent(
                    mnemonic,
                    java.util.function.Function { key: String? ->
                        com.google.devtools.build.lib.profiler.CounterSeriesTask(
                            SERIES_LANE_NAME,
                            mnemonic,
                            com.google.devtools.build.lib.profiler.CounterSeriesTask.Color.RAIL_ANIMATION
                        )
                    })
            }
        }
    }

    internal class SystemLoadAverageCollector private constructor(osBean: com.sun.management.OperatingSystemMXBean) :
        com.google.devtools.build.lib.profiler.CounterSeriesCollector {
        private val osBean: com.sun.management.OperatingSystemMXBean

        init {
            this.osBean = osBean
        }

        override fun collect(
            deltaNanos: Double,
            consumer: java.util.function.BiConsumer<com.google.devtools.build.lib.profiler.CounterSeriesTask?, Double?>
        ) {
            val loadAverage: Double = osBean.getSystemLoadAverage()
            if (loadAverage > 0) {
                consumer.accept(SYSTEM_LOAD_AVERAGE, loadAverage)
            }
        }

        companion object {
            private val SYSTEM_LOAD_AVERAGE: com.google.devtools.build.lib.profiler.CounterSeriesTask =
                com.google.devtools.build.lib.profiler.CounterSeriesTask(
                    "System load average",
                    "load",
                    com.google.devtools.build.lib.profiler.CounterSeriesTask.Color.GENERIC_WORK
                )
        }
    }

    internal class SystemNetworkUsageCollector private constructor(systemNetworkStatsService: SystemNetworkStatsService?) :
        com.google.devtools.build.lib.profiler.CounterSeriesCollector {
        private val systemNetworkStatsService: SystemNetworkStatsService?

        init {
            this.systemNetworkStatsService = systemNetworkStatsService
        }

        override fun collect(
            deltaNanos: Double,
            consumer: java.util.function.BiConsumer<com.google.devtools.build.lib.profiler.CounterSeriesTask?, Double?>
        ) {
            val systemNetworkUsages: SystemNetworkUsages? =
                NetworkMetricsCollector.Companion.instance()
                    .collectSystemNetworkUsages(deltaNanos, systemNetworkStatsService)
            if (systemNetworkUsages != null) {
                consumer.accept(SYSTEM_NETWORK_UP_USAGE, systemNetworkUsages.megabitsSentPerSec())
                consumer.accept(SYSTEM_NETWORK_DOWN_USAGE, systemNetworkUsages.megabitsRecvPerSec())
            }
        }

        companion object {
            private val SYSTEM_NETWORK_UP_USAGE: com.google.devtools.build.lib.profiler.CounterSeriesTask =
                com.google.devtools.build.lib.profiler.CounterSeriesTask(
                    "Network Up usage (total)",
                    "system network up (Mbps)",
                    com.google.devtools.build.lib.profiler.CounterSeriesTask.Color.RAIL_RESPONSE
                )
            private val SYSTEM_NETWORK_DOWN_USAGE: com.google.devtools.build.lib.profiler.CounterSeriesTask =
                com.google.devtools.build.lib.profiler.CounterSeriesTask(
                    "Network Down usage (total)",
                    "system network down (Mbps)",
                    com.google.devtools.build.lib.profiler.CounterSeriesTask.Color.RAIL_RESPONSE
                )
        }
    }

    internal class ResourceManagerEstimationCollector private constructor(resourceEstimator: ResourceEstimator) :
        com.google.devtools.build.lib.profiler.CounterSeriesCollector {
        private val resourceEstimator: ResourceEstimator

        init {
            this.resourceEstimator = resourceEstimator
        }

        override fun collect(
            deltaNanos: Double,
            consumer: java.util.function.BiConsumer<com.google.devtools.build.lib.profiler.CounterSeriesTask?, Double?>
        ) {
            val estimatedCpuUsage: Double = resourceEstimator.getUsedCPU()
            val estimatedMemoryUsageInMb: Double = resourceEstimator.getUsedMemoryInMb()
            consumer.accept(CPU_USAGE_ESTIMATION, estimatedCpuUsage)
            consumer.accept(MEMORY_USAGE_ESTIMATION, estimatedMemoryUsageInMb)
        }

        companion object {
            private val MEMORY_USAGE_ESTIMATION: com.google.devtools.build.lib.profiler.CounterSeriesTask =
                com.google.devtools.build.lib.profiler.CounterSeriesTask(
                    "Memory usage estimation",
                    "estimated memory",
                    com.google.devtools.build.lib.profiler.CounterSeriesTask.Color.RAIL_IDLE
                )
            private val CPU_USAGE_ESTIMATION: com.google.devtools.build.lib.profiler.CounterSeriesTask =
                com.google.devtools.build.lib.profiler.CounterSeriesTask(
                    "CPU usage estimation",
                    "estimated cpu",
                    com.google.devtools.build.lib.profiler.CounterSeriesTask.Color.CQ_BUILD_ATTEMPT_PASSED
                )
        }
    }

    internal class PressureStallIndicatorCollector : com.google.devtools.build.lib.profiler.CounterSeriesCollector {
        override fun collect(
            deltaNanos: Double,
            consumer: java.util.function.BiConsumer<com.google.devtools.build.lib.profiler.CounterSeriesTask?, Double?>
        ) {
            // The pressure stall indicator for full CPU metric is not defined.
            val pressureStallFullIo: Double =
                com.google.devtools.build.lib.util.ResourceUsage.readPressureStallIndicator(
                    PressureStallIndicatorResource.IO,
                    PressureStallIndicatorMetric.FULL
                ).toDouble()
            val pressureStallFullMemory: Double =
                com.google.devtools.build.lib.util.ResourceUsage.readPressureStallIndicator(
                    PressureStallIndicatorResource.MEMORY,
                    PressureStallIndicatorMetric.FULL
                ).toDouble()
            val pressureStallSomeIo: Double =
                com.google.devtools.build.lib.util.ResourceUsage.readPressureStallIndicator(
                    PressureStallIndicatorResource.IO,
                    PressureStallIndicatorMetric.SOME
                ).toDouble()
            val pressureStallSomeMemory: Double =
                com.google.devtools.build.lib.util.ResourceUsage.readPressureStallIndicator(
                    PressureStallIndicatorResource.MEMORY,
                    PressureStallIndicatorMetric.SOME
                ).toDouble()
            val pressureStallSomeCpu: Double =
                com.google.devtools.build.lib.util.ResourceUsage.readPressureStallIndicator(
                    PressureStallIndicatorResource.CPU,
                    PressureStallIndicatorMetric.SOME
                ).toDouble()

            consumer.accept(PRESSURE_STALL_FULL_IO, pressureStallFullIo)
            consumer.accept(PRESSURE_STALL_FULL_MEMORY, pressureStallFullMemory)
            consumer.accept(PRESSURE_STALL_SOME_IO, pressureStallSomeIo)
            consumer.accept(PRESSURE_STALL_SOME_MEMORY, pressureStallSomeMemory)
            consumer.accept(PRESSURE_STALL_SOME_CPU, pressureStallSomeCpu)
        }

        companion object {
            private val PRESSURE_STALL_FULL_IO: com.google.devtools.build.lib.profiler.CounterSeriesTask =
                com.google.devtools.build.lib.profiler.CounterSeriesTask(
                    "I/O pressure stall level",
                    "i/o pressure (full)",
                    com.google.devtools.build.lib.profiler.CounterSeriesTask.Color.RAIL_ANIMATION
                )
            private val PRESSURE_STALL_SOME_IO: com.google.devtools.build.lib.profiler.CounterSeriesTask =
                com.google.devtools.build.lib.profiler.CounterSeriesTask(
                    "I/O pressure stall level",
                    "i/o pressure (some)",
                    com.google.devtools.build.lib.profiler.CounterSeriesTask.Color.CQ_BUILD_ATTEMPT_FAILED
                )
            private val PRESSURE_STALL_FULL_MEMORY: com.google.devtools.build.lib.profiler.CounterSeriesTask =
                com.google.devtools.build.lib.profiler.CounterSeriesTask(
                    "Memory pressure stall level",
                    "memory pressure (full)",
                    com.google.devtools.build.lib.profiler.CounterSeriesTask.Color.THREAD_STATE_UNKNOWN
                )
            private val PRESSURE_STALL_SOME_MEMORY: com.google.devtools.build.lib.profiler.CounterSeriesTask =
                com.google.devtools.build.lib.profiler.CounterSeriesTask(
                    "Memory pressure stall level",
                    "memory pressure (some)",
                    com.google.devtools.build.lib.profiler.CounterSeriesTask.Color.RAIL_IDLE
                )
            private val PRESSURE_STALL_SOME_CPU: com.google.devtools.build.lib.profiler.CounterSeriesTask =
                com.google.devtools.build.lib.profiler.CounterSeriesTask(
                    "CPU pressure stall level",
                    "cpu pressure (some)",
                    com.google.devtools.build.lib.profiler.CounterSeriesTask.Color.THREAD_STATE_RUNNING
                )
        }
    }

    internal class SkyframeCountsCollector private constructor(graph: InMemoryGraph) :
        com.google.devtools.build.lib.profiler.CounterSeriesCollector {
        @kotlin.jvm.JvmRecord
        private data class SkyFunctionProfilerTasks(
            totalCounter: com.google.devtools.build.lib.profiler.CounterSeriesTask?,
            doneCounter: com.google.devtools.build.lib.profiler.CounterSeriesTask?
        ) {
            val totalCounter: com.google.devtools.build.lib.profiler.CounterSeriesTask?
            val doneCounter: com.google.devtools.build.lib.profiler.CounterSeriesTask?

            init {
                this.totalCounter = totalCounter
                this.doneCounter = doneCounter
            }
        }

        private val graph: InMemoryGraph

        init {
            this.graph = graph
        }

        override fun collect(
            deltaNanos: Double,
            consumer: java.util.function.BiConsumer<com.google.devtools.build.lib.profiler.CounterSeriesTask?, Double?>
        ) {
            val skykeyDoneCounter: com.google.common.collect.Multiset<SkyFunctionName?> =
                com.google.common.collect.HashMultiset.create<SkyFunctionName?>()
            val skykeyCounter: com.google.common.collect.Multiset<SkyFunctionName?> =
                com.google.common.collect.HashMultiset.create<SkyFunctionName?>()
            for (entry in graph.getAllNodeEntries()) {
                val name: SkyFunctionName? = entry.getKey().functionName()
                if (SKYFUNCTION_PROFILER_TASKS.containsKey(name)) {
                    skykeyCounter.add(name)
                    if (entry.isDone()) {
                        skykeyDoneCounter.add(name)
                    }
                }
            }
            for (entry in SKYFUNCTION_PROFILER_TASKS.entrySet()) {
                val functionName: SkyFunctionName = entry.getKey()
                consumer.accept(
                    entry.getValue().totalCounter, skykeyCounter.count(functionName).toDouble()
                )
                consumer.accept(
                    entry.getValue().doneCounter, skykeyDoneCounter.count(functionName).toDouble()
                )
            }
        }

        companion object {
            private val SKYFUNCTION_PROFILER_TASKS: com.google.common.collect.ImmutableMap<SkyFunctionName, SkyFunctionProfilerTasks?> =
                com.google.common.collect.ImmutableMap.of<SkyFunctionName?, SkyFunctionProfilerTasks?>(
                    SkyFunctions.PACKAGE,
                    SkyFunctionProfilerTasks(
                        com.google.devtools.build.lib.profiler.CounterSeriesTask(
                            "SkyFunction (PACKAGE)", "package (total)",  /* color= */null
                        ),
                        com.google.devtools.build.lib.profiler.CounterSeriesTask(
                            "SkyFunction (PACKAGE)", "package (done)",  /* color= */null
                        )
                    ),
                    SkyFunctions.BZL_LOAD,
                    SkyFunctionProfilerTasks(
                        com.google.devtools.build.lib.profiler.CounterSeriesTask(
                            "SkyFunction (BZL_LOAD)", "bzl_load (total)",  /* color= */null
                        ),
                        com.google.devtools.build.lib.profiler.CounterSeriesTask(
                            "SkyFunction (BZL_LOAD)", "bzl_load (done)",  /* color= */null
                        )
                    ),
                    SkyFunctions.GLOB,
                    SkyFunctionProfilerTasks(
                        com.google.devtools.build.lib.profiler.CounterSeriesTask(
                            "SkyFunction (GLOB)",
                            "glob (total)",  /* color= */
                            null
                        ),
                        com.google.devtools.build.lib.profiler.CounterSeriesTask(
                            "SkyFunction (GLOB)",
                            "glob (done)",  /* color= */
                            null
                        )
                    ),
                    SkyFunctions.GLOBS,
                    SkyFunctionProfilerTasks(
                        com.google.devtools.build.lib.profiler.CounterSeriesTask(
                            "SkyFunction (GLOBS)", "globs (total)",  /* color= */null
                        ),
                        com.google.devtools.build.lib.profiler.CounterSeriesTask(
                            "SkyFunction (GLOBS)", "globs (done)",  /* color= */null
                        )
                    ),
                    SkyFunctions.CONFIGURED_TARGET,
                    SkyFunctionProfilerTasks(
                        com.google.devtools.build.lib.profiler.CounterSeriesTask(
                            "SkyFunction (CONFIGURED_TARGET)",
                            "configured target (total)",  /* color= */
                            null
                        ),
                        com.google.devtools.build.lib.profiler.CounterSeriesTask(
                            "SkyFunction (CONFIGURED_TARGET)",
                            "configured target (done)",  /* color= */
                            null
                        )
                    ),
                    SkyFunctions.ASPECT,
                    SkyFunctionProfilerTasks(
                        com.google.devtools.build.lib.profiler.CounterSeriesTask(
                            "SkyFunction (ASPECT)", "aspect (total)",  /* color= */null
                        ),
                        com.google.devtools.build.lib.profiler.CounterSeriesTask(
                            "SkyFunction (ASPECT)", "aspect (done)",  /* color= */null
                        )
                    ),
                    SkyFunctions.ACTION_EXECUTION,
                    SkyFunctionProfilerTasks(
                        com.google.devtools.build.lib.profiler.CounterSeriesTask(
                            "SkyFunction (ACTION_EXECUTION)",
                            "action execution (total)",  /* color= */
                            null
                        ),
                        com.google.devtools.build.lib.profiler.CounterSeriesTask(
                            "SkyFunction (ACTION_EXECUTION)",
                            "action execution (done)",  /* color= */
                            null
                        )
                    )
                )
        }
    }
}
