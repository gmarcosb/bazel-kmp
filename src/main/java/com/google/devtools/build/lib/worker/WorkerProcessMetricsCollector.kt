// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.worker

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.WorkerMetrics

/** Collects and populates system metrics about persistent workers.  */
class WorkerProcessMetricsCollector {
    private val psInfoCollector: PsInfoCollector
    private val cgroupsInfoCollector: CgroupsInfoCollector

    private var clock: com.google.devtools.build.lib.clock.Clock? = null

    /**
     * Mapping of worker process ids to their process metrics. This contains all workers that have
     * been alive at any point during the build.
     */
    private val pidToWorkerProcessMetrics: MutableMap<Long?, WorkerProcessMetrics> =
        ConcurrentHashMap<Long?, WorkerProcessMetrics>()

    private val pidToCgroups: MutableMap<Long?, Cgroup?> = ConcurrentHashMap<Long?, Cgroup?>()

    private var useCgroupsOnLinux = false

    private constructor() {
        psInfoCollector = PsInfoCollector.instance()
        cgroupsInfoCollector = CgroupsInfoCollector.instance()
    }

    @com.google.common.annotations.VisibleForTesting
    internal constructor(psInfoCollector: PsInfoCollector, cgroupsInfoCollector: CgroupsInfoCollector) {
        this.psInfoCollector = psInfoCollector
        this.cgroupsInfoCollector = cgroupsInfoCollector
    }

    fun setClock(clock: com.google.devtools.build.lib.clock.Clock) {
        this.clock = clock
    }

    fun setUseCgroupsOnLinux(useCgroupsOnLinux: Boolean) {
        this.useCgroupsOnLinux = useCgroupsOnLinux
    }

    fun collectResourceUsage(): ResourceSnapshot {
        // Only collect for process we know are alive.
        val alivePids: com.google.common.collect.ImmutableSet<Long?> =
            pidToWorkerProcessMetrics.entrySet().stream()
                .filter(java.util.function.Predicate { e: MutableMap.MutableEntry<Long?, WorkerProcessMetrics?>? ->
                    !e.getValue().getStatus().isKilled()
                })
                .map<Long?>(java.util.function.Function { e: MutableMap.MutableEntry<Long?, WorkerProcessMetrics?>? -> e.getKey() })
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Long?>())
        return collectResourceUsage(com.google.devtools.build.lib.util.OS.getCurrent(), alivePids)
    }

    /**
     * Collects memory usage of all ancestors of processes by pid. If a pid does not allow collecting
     * memory usage, it is silently ignored.
     */
    @com.google.common.annotations.VisibleForTesting
    fun collectResourceUsage(
        os: com.google.devtools.build.lib.util.OS,
        alivePids: com.google.common.collect.ImmutableSet<Long?>
    ): ResourceSnapshot {
        // TODO(b/181317827): Support Windows.
        if (alivePids.isEmpty()) {
            return ResourceSnapshot.createEmpty(clock.now())
        }
        if (os == com.google.devtools.build.lib.util.OS.DARWIN) {
            return psInfoCollector.collectResourceUsage(alivePids, clock)
        }
        if (os == com.google.devtools.build.lib.util.OS.LINUX) {
            if (useCgroupsOnLinux) {
                // Remove the killed pids so that we only collect from the cgroups that are alive.
                for (pid in com.google.common.collect.ImmutableSet.copyOf<Long?>(pidToCgroups.keySet())) {
                    if (!alivePids.contains(pid)) {
                        pidToCgroups.remove(pid)
                    }
                }
                return cgroupsInfoCollector.collectResourceUsage(pidToCgroups, clock)
            }
            // Default to using ps if cgroups is not enabled.
            return psInfoCollector.collectResourceUsage(alivePids, clock)
        }
        return ResourceSnapshot.createEmpty(clock.now())
    }

    val liveWorkerProcessMetrics: com.google.common.collect.ImmutableList<WorkerProcessMetrics?>
        get() = collectMetrics().stream()
            .filter(java.util.function.Predicate { m: WorkerProcessMetrics? -> !m.getStatus().isKilled() })
            .collect(com.google.common.collect.ImmutableList.toImmutableList<WorkerProcessMetrics?>())

    fun collectMetrics(): com.google.common.collect.ImmutableList<WorkerProcessMetrics?> {
        val resourceSnapshot: ResourceSnapshot = collectResourceUsage()

        val pidToMemoryInKb: com.google.common.collect.ImmutableMap<Long?, Int?> = resourceSnapshot.pidToMemoryInKb()

        val collectionTime: Instant? = resourceSnapshot.collectionTime()

        val workerMetrics: com.google.common.collect.ImmutableList.Builder<WorkerProcessMetrics?> =
            com.google.common.collect.ImmutableList.Builder<WorkerProcessMetrics?>()
        for (entry in pidToWorkerProcessMetrics.entrySet()) {
            val workerMetric: WorkerProcessMetrics = entry.getValue()

            if (workerMetric.getStatus().isKilled()) {
                // If it was previously killed by Bazel, we don't do anything.
                workerMetrics.add(workerMetric)
                continue
            }

            val pid: Long = workerMetric.getProcessId()
            val memoryInKb: Int = pidToMemoryInKb.getOrDefault(pid, 0)

            if (memoryInKb == 0) {
                // If it is not measurable, not killed by Bazel but has executed actions, then we assume
                // that something has happened to the worker process that is not accounted for by Bazel
                // and set this to KILLED_UNKNOWN. If a separate thread comes along to update the status
                // with a more specific reason why it is killed, then we allow such an update.
                if (workerMetric.getActionsExecuted() > 0) {
                    workerMetric.getStatus()
                        .maybeUpdateStatus(com.google.devtools.build.lib.worker.WorkerProcessStatus.Status.KILLED_UNKNOWN)
                }
                // We want to add the worker metric even if it is not measurable.
                workerMetrics.add(workerMetric)
                continue
            }

            // If it is measurable, we want to update the collected metrics.
            workerMetric.addCollectedMetrics( /* memoryInKb= */
                memoryInKb,  /* collectionTime= */collectionTime
            )
            workerMetrics.add(workerMetric)
        }

        return workerMetrics.build()
    }

    fun onWorkerFinishExecution(processId: Long) {
        val wpm: WorkerProcessMetrics? = pidToWorkerProcessMetrics.get(processId)
        if (wpm == null) {
            return
        }
        wpm.incrementActionsExecuted()
    }

    /**
     * Because we log all worker processes that have been alive at any point during the build, the
     * size of this list might grow out of hand if there is some issue with the build (e.g.
     * kill-create cycles). As such, we enforce rules to prioritize WorkerMetrics before limiting: (1)
     * Prioritize WorkerStatuses ALIVE, then KILLED_DUE_TO_MEMORY_PRESSURE, then all remaining worker
     * statuses. (2) Then prioritize by decreasing memory usage and (3) limit to a fixed number.
     */
    @com.google.common.annotations.VisibleForTesting
    class WorkerMetricsPublishComparator : java.util.Comparator<WorkerMetrics?> {
        private fun getWorkerStatusPriority(status: WorkerMetrics.WorkerStatus?): Int {
            // Lower value is prioritized.
            if (status === WorkerStatus.ALIVE) {
                return 0
            } else if (status === WorkerStatus.KILLED_DUE_TO_MEMORY_PRESSURE) {
                return 1
            }
            return 2
        }

        override fun compare(m1: WorkerMetrics, m2: WorkerMetrics): Int {
            val s1 = getWorkerStatusPriority(m1.getWorkerStatus())
            val s2 = getWorkerStatusPriority(m2.getWorkerStatus())
            if (s1 != s2) {
                return java.lang.Integer.compare(s1, s2)
            }
            return java.lang.Integer.compare(
                m2.getWorkerStats(0).getWorkerMemoryInKb(), m1.getWorkerStats(0).getWorkerMemoryInKb()
            )
        }
    }

    val liveWorkerMetrics: com.google.common.collect.ImmutableList<WorkerMetrics?>
        get() = this.liveWorkerProcessMetrics.stream()
            .map<WorkerMetrics?>(java.util.function.Function { obj: WorkerProcessMetrics? -> obj.toProto() })
            .collect(com.google.common.collect.ImmutableList.toImmutableList<WorkerMetrics?>())

    fun clear() {
        pidToWorkerProcessMetrics.clear()
    }

    @com.google.common.annotations.VisibleForTesting
    fun getPidToWorkerProcessMetrics(): MutableMap<Long?, WorkerProcessMetrics> {
        return pidToWorkerProcessMetrics
    }

    /**
     * Initializes workerIdToWorkerProperties for workers. If worker metrics already exists for this
     * worker, only updates the last call time and maybe adds the multiplex worker id.
     */
    @kotlin.jvm.Synchronized
    fun registerWorker(
        workerId: Int,
        processId: Long,
        status: WorkerProcessStatus?,
        mnemonic: String?,
        isMultiplex: Boolean,
        isSandboxed: Boolean,
        workerKeyHash: Int,
        cgroup: Cgroup?
    ) {
        val workerMetric: WorkerProcessMetrics =
            pidToWorkerProcessMetrics.computeIfAbsent(
                processId,
                java.util.function.Function { pid: Long? ->
                    WorkerProcessMetrics(
                        workerId,
                        processId,
                        status,
                        mnemonic,
                        isMultiplex,
                        isSandboxed,
                        workerKeyHash
                    )
                })
        if (cgroup != null) {
            pidToCgroups.putIfAbsent(processId, cgroup)
        }
        workerMetric.setLastCallTime(Instant.ofEpochMilli(clock.currentTimeMillis()))
        workerMetric.maybeAddWorkerId(workerId, status)
    }

    /** Removes all WorkerProcessMetrics that were marked as killed.  */
    fun clearKilledWorkerProcessMetrics() {
        val pidsToRemove: MutableList<Long?> = java.util.ArrayList<Long?>()
        for (entry in pidToWorkerProcessMetrics.entrySet()) {
            if (entry.getValue().getStatus().isKilled()) {
                pidsToRemove.add(entry.getKey())
            }
        }
        pidToWorkerProcessMetrics.keySet().removeAll(pidsToRemove)
    }

    /** To reset states in each WorkerProcessMetric before each command where applicable.  */
    fun beforeCommand() {
        pidToWorkerProcessMetrics.values()
            .forEach(java.util.function.Consumer { m: WorkerProcessMetrics? -> m.onBeforeCommand() })
    }

    companion object {
        /** The metrics collector (a static singleton instance). Inactive by default.  */
        private val instance = WorkerProcessMetricsCollector()

        @kotlin.jvm.JvmStatic
        fun instance(): WorkerProcessMetricsCollector {
            return instance
        }

        const val MAX_PUBLISHED_WORKER_METRICS: Int = 50

        /** Returns a prioritized and limited list of WorkerMetrics to be published to the BEP.  */
        fun limitWorkerMetricsToPublish(
            metrics: com.google.common.collect.ImmutableList<WorkerMetrics?>, limit: Int
        ): com.google.common.collect.ImmutableList<WorkerMetrics?> {
            return metrics.stream()
                .sorted(WorkerMetricsPublishComparator())
                .limit(limit.toLong())
                .sorted(java.util.Comparator.comparingInt<WorkerMetrics?>(ToIntFunction { m: WorkerMetrics? ->
                    m.getWorkerIdsList().get(0)
                }))
                .collect(com.google.common.collect.ImmutableList.toImmutableList<WorkerMetrics?>())
        }
    }
}
