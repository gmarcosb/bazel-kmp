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
package com.google.devtools.build.lib.worker

import com.google.common.flogger.GoogleLogger
import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import com.google.devtools.build.lib.worker.WorkerOptions
import com.google.devtools.build.lib.worker.WorkerPool
import com.google.devtools.build.lib.worker.WorkerProcessMetrics
import com.google.devtools.build.lib.worker.WorkerProcessMetricsCollector
import com.google.devtools.build.lib.worker.WorkerProcessStatus
import java.util.concurrent.TimeUnit
import java.util.function.ToIntFunction
import java.util.stream.Collectors

/**
 * This class kills idle persistent workers at intervals, if the total worker resource usage is
 * above a specified limit. Must be used as singleton.
 */
internal class WorkerLifecycleManager(
    workerPool: WorkerPool,
    options: WorkerOptions,
    reporter: com.google.devtools.build.lib.events.Reporter?
) : java.lang.Thread() {
    @kotlin.concurrent.Volatile
    private var isWorking = false
    private var emptyEvictionWasLogged = false
    private val workerPool: WorkerPool
    private val totalWorkerMemoryLimitKb: Int
    private val workerMemoryLimitKb: Int
    private val shrinkWorkerPool: Boolean
    private val workerVerbose: Boolean
    private val workerMetricsPollInterval: java.time.Duration

    private val reporter: com.google.devtools.build.lib.events.Reporter?

    init {
        this.workerPool = workerPool
        this.totalWorkerMemoryLimitKb = options.getTotalWorkerMemoryLimitMb() * 1000
        this.workerMemoryLimitKb = options.getWorkerMemoryLimitMb() * 1000
        this.shrinkWorkerPool = options.getShrinkWorkerPool()
        this.workerVerbose = options.getWorkerVerbose()
        this.workerMetricsPollInterval = options.getWorkerMetricsPollInterval()
        this.reporter = reporter
    }

    override fun run() {
        if (totalWorkerMemoryLimitKb == 0 && workerMemoryLimitKb == 0) {
            return
        }

        val msg: String? =
            java.lang.String.format(
                "Worker Lifecycle Manager starts work with (total limit: %d KB, individual limit: %d"
                        + " KB, shrinking: %s)",
                totalWorkerMemoryLimitKb,
                workerMemoryLimitKb,
                if (shrinkWorkerPool) "enabled" else "disabled"
            )
        logger.atInfo().log("%s", msg)
        if (workerVerbose && reporter != null) {
            reporter.handle(com.google.devtools.build.lib.events.Event.info(msg))
        }

        isWorking = true

        // This loop works until method stopProcessing() called by WorkerModule.
        while (isWorking) {
            try {
                java.lang.Thread.sleep(workerMetricsPollInterval.toMillis())
            } catch (e: java.lang.InterruptedException) {
                logger.atInfo().withCause(e).log("received interrupt in worker life cycle manager")
                break
            }

            val workerProcessMetrics: com.google.common.collect.ImmutableList<WorkerProcessMetrics?> =
                WorkerProcessMetricsCollector.Companion.instance().getLiveWorkerProcessMetrics()

            if (totalWorkerMemoryLimitKb > 0) {
                try {
                    evictWorkers(workerProcessMetrics)
                } catch (e: java.lang.InterruptedException) {
                    logger.atInfo().withCause(e).log("received interrupt in worker life cycle manager")
                    break
                }
            }

            if (workerMemoryLimitKb > 0) {
                killLargeWorkers(workerProcessMetrics, workerMemoryLimitKb)
            }
        }

        isWorking = false
    }

    fun stopProcessing() {
        isWorking = false
    }

    /** Kills any worker that uses more than `limitKb` KB of memory.  */
    fun killLargeWorkers(
        workerProcessMetrics: com.google.common.collect.ImmutableList<WorkerProcessMetrics?>,
        limitKb: Int
    ) {
        val large: com.google.common.collect.ImmutableList<WorkerProcessMetrics> =
            workerProcessMetrics.stream()
                .filter(java.util.function.Predicate { m: WorkerProcessMetrics? -> m.getUsedMemoryInKb() > limitKb })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<WorkerProcessMetrics?>())

        for (l in large) {
            val msg: String?

            val workerIds: com.google.common.collect.ImmutableList<Int?> = l.getWorkerIds()
            val ph: java.util.Optional<java.lang.ProcessHandle?> = java.lang.ProcessHandle.of(l.getProcessId())
            if (ph.isPresent()) {
                msg =
                    java.lang.String.format(
                        "Killing %s worker %s (pid %d) because it is using more memory than the limit"
                                + " (%,d KB > %,d KB)",
                        l.getMnemonic(),
                        if (workerIds.size() == 1) workerIds.get(0) else workerIds,
                        l.getProcessId(),
                        l.getUsedMemoryInKb(),
                        limitKb
                    )
                logger.atInfo().log("%s", msg)
                // TODO(b/310640400): Converge APIs in killing workers, rather than killing via the process
                //  handle here (resulting in errors in execution), perhaps we want to wait till the worker
                //  is returned before killing it.
                ph.get().destroyForcibly()
                l.getStatus()
                    .maybeUpdateStatus(com.google.devtools.build.lib.worker.WorkerProcessStatus.Status.KILLED_DUE_TO_MEMORY_PRESSURE)
                // We want to always report this as this is a potential source of build failure.
                if (this.reporter != null) {
                    reporter.handle(com.google.devtools.build.lib.events.Event.warn(msg))
                }
            }
        }
    }

    @com.google.common.annotations.VisibleForTesting
    @Throws(java.lang.InterruptedException::class)
    fun evictWorkers(workerProcessMetrics: com.google.common.collect.ImmutableList<WorkerProcessMetrics?>) {
        if (totalWorkerMemoryLimitKb == 0) {
            return
        }

        val workerMemoryUsageKb: Int =
            workerProcessMetrics.stream()
                .mapToInt(ToIntFunction { obj: WorkerProcessMetrics? -> obj.getUsedMemoryInKb() }).sum()

        // TODO: Remove after b/274608075 is fixed.
        if (!workerProcessMetrics.isEmpty()) {
            logger.atInfo().atMostEvery(1, TimeUnit.MINUTES).log(
                "total worker memory %,d KB while limit is %,d KB - details: %s",
                workerMemoryUsageKb,
                totalWorkerMemoryLimitKb,
                workerProcessMetrics.stream()
                    .map<String?>(
                        java.util.function.Function { metric: WorkerProcessMetrics? ->
                            (metric.getWorkerIds()
                                .toString() + " "
                                    + metric.getMnemonic()
                                    + " "
                                    + metric.getUsedMemoryInKb()
                                    + "KB")
                        })
                    .collect(Collectors.joining(", "))
            )
        }

        if (workerMemoryUsageKb <= totalWorkerMemoryLimitKb) {
            return
        }

        val candidates: com.google.common.collect.ImmutableSet<WorkerProcessMetrics?> =
            collectEvictionCandidates(
                workerProcessMetrics, totalWorkerMemoryLimitKb, workerMemoryUsageKb
            )

        if (!candidates.isEmpty() || !emptyEvictionWasLogged) {
            val msg: String?
            if (candidates.isEmpty()) {
                msg =
                    java.lang.String.format(
                        "Could not find any worker eviction candidates. Worker memory usage: %d KB, Memory"
                                + " limit: %d KB",
                        workerMemoryUsageKb, totalWorkerMemoryLimitKb
                    )
            } else {
                val workerIdsToEvict: com.google.common.collect.ImmutableSet<Int?> =
                    candidates.stream().flatMap<Int?>(java.util.function.Function { m: WorkerProcessMetrics? ->
                        m.getWorkerIds().stream()
                    }).collect(com.google.common.collect.ImmutableSet.toImmutableSet<Int?>())
                msg =
                    java.lang.String.format(
                        "Attempting eviction of %d workers with ids: %s",
                        workerIdsToEvict.size(), workerIdsToEvict
                    )
            }

            logger.atInfo().log("%s", msg)
            if (workerVerbose && this.reporter != null) {
                reporter.handle(com.google.devtools.build.lib.events.Event.info(msg))
            }
        }

        val evictedWorkers: com.google.common.collect.ImmutableSet<Int?> = evictCandidates(workerPool, candidates)

        if (!evictedWorkers.isEmpty() || !emptyEvictionWasLogged) {
            val msg: String? =
                java.lang.String.format(
                    "Total evicted idle workers %d. With ids: %s", evictedWorkers.size(), evictedWorkers
                )
            logger.atInfo().log("%s", msg)
            if (workerVerbose && this.reporter != null) {
                reporter.handle(com.google.devtools.build.lib.events.Event.info(msg))
            }

            emptyEvictionWasLogged = candidates.isEmpty()
        }

        // TODO(b/300067854): Shrinking of the worker pool happens on worker keys that are active at the
        //  time of polling, but doesn't shrink the pools of idle workers. We might be wrongly
        //  penalizing lower memory usage workers (but more active) by shrinking their pool sizes
        //  instead of higher memory usage workers (but less active) and are killed directly with
        //  {@code #evictCandidates()} (where shrinking doesn't happen).
        if (shrinkWorkerPool) {
            val notEvictedWorkerProcessMetrics: MutableList<WorkerProcessMetrics?> =
                workerProcessMetrics.stream()
                    .filter(java.util.function.Predicate { metric: WorkerProcessMetrics? ->
                        !evictedWorkers.containsAll(
                            metric.getWorkerIds()
                        )
                    })
                    .collect(Collectors.toList())

            val notEvictedWorkerMemoryUsageKb: Int =
                notEvictedWorkerProcessMetrics.stream()
                    .mapToInt(ToIntFunction { obj: WorkerProcessMetrics? -> obj.getUsedMemoryInKb() })
                    .sum()

            if (notEvictedWorkerMemoryUsageKb <= totalWorkerMemoryLimitKb) {
                return
            }

            postponeInvalidation(notEvictedWorkerProcessMetrics, notEvictedWorkerMemoryUsageKb)
        }
    }

    private fun postponeInvalidation(
        workerProcessMetrics: MutableList<WorkerProcessMetrics?>, notEvictedWorkerMemoryUsageKb: Int
    ) {
        val potentialCandidates: com.google.common.collect.ImmutableSet<WorkerProcessMetrics?> =
            getCandidates(
                workerProcessMetrics, totalWorkerMemoryLimitKb, notEvictedWorkerMemoryUsageKb
            )

        if (!potentialCandidates.isEmpty()) {
            val msg: String? =
                java.lang.String.format(
                    "Postponing eviction of worker ids: %s",
                    potentialCandidates.stream()
                        .flatMap<Int?>(java.util.function.Function { m: WorkerProcessMetrics? ->
                            m.getWorkerIds().stream()
                        })
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<Int?>())
                )
            logger.atInfo().log("%s", msg)
            if (workerVerbose && reporter != null) {
                reporter.handle(com.google.devtools.build.lib.events.Event.info(msg))
            }
            potentialCandidates.forEach(
                java.util.function.Consumer { m: WorkerProcessMetrics? ->
                    m.getStatus()
                        .maybeUpdateStatus(com.google.devtools.build.lib.worker.WorkerProcessStatus.Status.PENDING_KILL_DUE_TO_MEMORY_PRESSURE)
                })
        }
    }

    /** Collects worker candidates to evict. Chooses workers with the largest memory consumption.  */
    @Throws(java.lang.InterruptedException::class)
    fun collectEvictionCandidates(
        workerProcessMetrics: com.google.common.collect.ImmutableList<WorkerProcessMetrics?>,
        memoryLimitKb: Int,
        workerMemoryUsageKb: Int
    ): com.google.common.collect.ImmutableSet<WorkerProcessMetrics?> {
        // TODO(b/300067854): Consider rethinking the strategy here. The current logic kills idle
        //  workers that have lower memory usage if the other higher memory usage workers are active
        //  (where killing them would have brought the memory usage under the limit). This means we
        //  could be killing memory compliant and performant workers unnecessarily; i.e. this strategy
        //  maximizes responsiveness towards being compliant to the memory limit with no guarantees of
        //  making it immediately compliant. Since we can't guarantee immediate compliance, tradeoff
        //  some of this responsiveness by just killing or marking workers as killed in descending
        //  memory usage and waiting for the active workers to be returned later (where they are then
        //  killed).
        val idleWorkers: com.google.common.collect.ImmutableSet<Int?> = workerPool.getIdleWorkers()

        val idleWorkerProcessMetrics: MutableList<WorkerProcessMetrics?> =
            workerProcessMetrics.stream()
                .filter(java.util.function.Predicate { metric: WorkerProcessMetrics? ->
                    metric.getWorkerIds().stream()
                        .anyMatch(java.util.function.Predicate { `object`: Int? -> idleWorkers.contains(`object`) })
                })
                .collect(Collectors.toList())

        return getCandidates(idleWorkerProcessMetrics, memoryLimitKb, workerMemoryUsageKb)
    }

    /** Compare worker metrics by memory consumption in descending order.  */
    private class MemoryComparator : java.util.Comparator<WorkerProcessMetrics?> {
        override fun compare(m1: WorkerProcessMetrics, m2: WorkerProcessMetrics): Int {
            return m2.getUsedMemoryInKb() - m1.getUsedMemoryInKb()
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /**
         * Applies eviction police for candidates. Returns the worker ids of evicted workers. We don't
         * guarantee that every candidate is going to be evicted. Returns worker ids of evicted workers.
         */
        @Throws(java.lang.InterruptedException::class)
        private fun evictCandidates(
            pool: WorkerPool, candidates: com.google.common.collect.ImmutableSet<WorkerProcessMetrics?>
        ): com.google.common.collect.ImmutableSet<Int?> {
            return pool.evictWorkers(
                candidates.stream().flatMap<Int?>(java.util.function.Function { w: WorkerProcessMetrics? ->
                    w.getWorkerIds().stream()
                }).collect(com.google.common.collect.ImmutableSet.toImmutableSet<Int?>())
            )
        }

        /**
         * Chooses the WorkerProcessMetrics of workers with the most usage of memory. Selects workers
         * until total memory usage is less than memoryLimitKb.
         */
        private fun getCandidates(
            workerProcessMetrics: MutableList<WorkerProcessMetrics?>, memoryLimitKb: Int, usedMemoryKb: Int
        ): com.google.common.collect.ImmutableSet<WorkerProcessMetrics?> {
            workerProcessMetrics.sort(MemoryComparator())
            val candidates: com.google.common.collect.ImmutableSet.Builder<WorkerProcessMetrics?> =
                com.google.common.collect.ImmutableSet.builder<WorkerProcessMetrics?>()
            var freeMemoryKb = 0
            for (metric in workerProcessMetrics) {
                candidates.add(metric)
                freeMemoryKb += metric.getUsedMemoryInKb()

                if (usedMemoryKb - freeMemoryKb <= memoryLimitKb) {
                    break
                }
            }

            return candidates.build()
        }
    }
}
