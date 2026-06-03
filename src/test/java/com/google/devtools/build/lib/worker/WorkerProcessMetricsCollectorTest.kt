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
package com.google.devtools.build.lib.worker

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.WorkerMetrics

/** Unit tests for the WorkerSpawnRunner.  */
@RunWith(JUnit4::class)
class WorkerProcessMetricsCollectorTest {
    private var psInfoCollector: PsInfoCollector? = null
    private var cgroupsInfoCollector: CgroupsInfoCollector? = null

    private var spyCollector: WorkerProcessMetricsCollector? = null
    var clock: ManualClock = com.google.devtools.build.lib.worker.WorkerProcessMetricsCollectorTest.ManualClock()

    @Before
    fun setUp() {
        psInfoCollector = Mockito.mock<PsInfoCollector>(PsInfoCollector::class.java)
        cgroupsInfoCollector = Mockito.mock<CgroupsInfoCollector>(CgroupsInfoCollector::class.java)
        spyCollector = spy(WorkerProcessMetricsCollector(psInfoCollector, cgroupsInfoCollector))
        spyCollector.clear()
        spyCollector.setClock(clock)
    }

    private fun assertWorkerMetricContains(
        workerMetric: WorkerProcessMetrics,
        expectedWorkerIds: com.google.common.collect.ImmutableList<Int?>?,
        expectedProcessId: Long?,
        expectedMnemonic: String?,
        expectedIsMultiplex: Boolean,
        expectedIsSandboxed: Boolean,
        expectedWorkerKeyHash: Int,
        expectedActionsExecuted: Int,
        expectedIsMeasurable: Boolean,
        expectedLastCallTime: Instant?,
        expectedCollectedTime: Instant?
    ) {
        assertThat(workerMetric).isNotNull()
        assertThat(workerMetric.getWorkerIds()).containsExactlyElementsIn(expectedWorkerIds)
        assertThat(workerMetric.processId).isEqualTo(expectedProcessId)
        assertThat(workerMetric.mnemonic).isEqualTo(expectedMnemonic)
        assertThat(workerMetric.isMultiplex).isEqualTo(expectedIsMultiplex)
        assertThat(workerMetric.isSandboxed()).isEqualTo(expectedIsSandboxed)
        assertThat(workerMetric.workerKeyHash).isEqualTo(expectedWorkerKeyHash)
        assertThat(workerMetric.getActionsExecuted()).isEqualTo(expectedActionsExecuted)
        assertThat(workerMetric.isMeasurable()).isEqualTo(expectedIsMeasurable)
        assertThat(workerMetric.getLastCallTime().get()).isEqualTo(expectedLastCallTime)
        if (expectedCollectedTime == null) {
            assertThat(workerMetric.getLastCollectedTime().isEmpty()).isTrue()
        } else {
            assertThat(workerMetric.getLastCollectedTime().isPresent()).isTrue()
            assertThat(workerMetric.getLastCollectedTime().get()).isEqualTo(expectedCollectedTime)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisterWorker_insertDifferent() {
        spyCollector.registerWorker(
            WORKER_ID_1,
            PROCESS_ID_1,
            WorkerProcessStatus(),
            JAVAC_MNEMONIC,  /* isMultiplex= */
            true,  /* isSandboxed= */
            false,
            WORKER_KEY_HASH_1,  /* cgroup= */
            null
        )
        assertThat(spyCollector.getPidToWorkerProcessMetrics().keySet()).containsExactly(PROCESS_ID_1)
        spyCollector.registerWorker(
            WORKER_ID_2,
            PROCESS_ID_2,
            WorkerProcessStatus(),
            CPP_COMPILE_MNEMONIC,  /* isMultiplex= */
            false,  /* isSandboxed= */
            true,
            WORKER_KEY_HASH_2,  /* cgroup= */
            null
        )
        assertThat(spyCollector.getPidToWorkerProcessMetrics().keySet())
            .containsExactly(PROCESS_ID_1, PROCESS_ID_2)
        assertWorkerMetricContains(
            spyCollector.getPidToWorkerProcessMetrics().get(PROCESS_ID_1),
            com.google.common.collect.ImmutableList.of<Int?>(WORKER_ID_1),
            PROCESS_ID_1,
            JAVAC_MNEMONIC,  /* expectedIsMultiplex= */
            true,  /* expectedIsSandboxed= */
            false,
            WORKER_KEY_HASH_1,  /* expectedActionsExecuted= */
            0,  /* expectedIsMeasurable= */
            false,  /* expectedLastCallTime= */
            DEFAULT_CLOCK_START_INSTANT,  /* expectedCollectedTime= */
            null
        )
        assertWorkerMetricContains(
            spyCollector.getPidToWorkerProcessMetrics().get(PROCESS_ID_2),
            com.google.common.collect.ImmutableList.of<Int?>(WORKER_ID_2),
            PROCESS_ID_2,
            CPP_COMPILE_MNEMONIC,  /* expectedIsMultiplex= */
            false,  /* expectedIsSandboxed= */
            true,
            WORKER_KEY_HASH_2,  /* expectedActionsExecuted= */
            0,  /* expectedIsMeasurable= */
            false,  /* expectedLastCallTime= */
            DEFAULT_CLOCK_START_INSTANT,  /* expectedCollectedTime= */
            null
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisterWorker_insertMultiplex() {
        spyCollector.registerWorker(
            WORKER_ID_1,
            PROCESS_ID_1,
            WorkerProcessStatus(),
            JAVAC_MNEMONIC,  /* isMultiplex= */
            true,  /* isSandboxed= */
            true,
            WORKER_KEY_HASH_1,  /* cgroup= */
            null
        )
        assertThat(spyCollector.getPidToWorkerProcessMetrics().keySet()).containsExactly(PROCESS_ID_1)
        assertWorkerMetricContains(
            spyCollector.getPidToWorkerProcessMetrics().get(PROCESS_ID_1),
            com.google.common.collect.ImmutableList.of<Int?>(WORKER_ID_1),
            PROCESS_ID_1,
            JAVAC_MNEMONIC,  /* expectedIsMultiplex= */
            true,  /* expectedIsSandboxed= */
            true,
            WORKER_KEY_HASH_1,  /* expectedActionsExecuted= */
            0,  /* expectedIsMeasurable= */
            false,  /* expectedLastCallTime= */
            DEFAULT_CLOCK_START_INSTANT,  /* expectedCollectedTime= */
            null
        )

        val secondTime: Instant = DEFAULT_CLOCK_START_INSTANT.plusSeconds(10)
        clock.setTime(secondTime.toEpochMilli())

        spyCollector.registerWorker(
            WORKER_ID_2,
            PROCESS_ID_1,
            WorkerProcessStatus(),
            JAVAC_MNEMONIC,  /* isMultiplex= */
            true,  /* isSandboxed= */
            true,
            WORKER_KEY_HASH_1,  /* cgroup= */
            null
        )
        assertThat(spyCollector.getPidToWorkerProcessMetrics().keySet()).containsExactly(PROCESS_ID_1)
        assertWorkerMetricContains(
            spyCollector.getPidToWorkerProcessMetrics().get(PROCESS_ID_1),
            com.google.common.collect.ImmutableList.of<Int?>(WORKER_ID_1, WORKER_ID_2),
            PROCESS_ID_1,
            JAVAC_MNEMONIC,  /* expectedIsMultiplex= */
            true,  /* expectedIsSandboxed= */
            true,
            WORKER_KEY_HASH_1,  /* expectedActionsExecuted= */
            0,  /* expectedIsMeasurable= */
            false,  /* expectedLastCallTime= */
            secondTime,  /* expectedCollectedTime= */
            null
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisterWorker_insertSame() {
        spyCollector.registerWorker(
            WORKER_ID_1,
            PROCESS_ID_1,
            WorkerProcessStatus(),
            JAVAC_MNEMONIC,  /* isMultiplex= */
            true,  /* isSandboxed= */
            true,
            WORKER_KEY_HASH_1,  /* cgroup= */
            null
        )
        assertThat(spyCollector.getPidToWorkerProcessMetrics().keySet()).containsExactly(PROCESS_ID_1)
        assertWorkerMetricContains(
            spyCollector.getPidToWorkerProcessMetrics().get(PROCESS_ID_1),
            com.google.common.collect.ImmutableList.of<Int?>(WORKER_ID_1),
            PROCESS_ID_1,
            JAVAC_MNEMONIC,  /* expectedIsMultiplex= */
            true,  /* expectedIsSandboxed= */
            true,
            WORKER_KEY_HASH_1,  /* expectedActionsExecuted= */
            0,  /* expectedIsMeasurable= */
            false,  /* expectedLastCallTime= */
            DEFAULT_CLOCK_START_INSTANT,  /* expectedCollectedTime= */
            null
        )

        val secondTime: Instant = DEFAULT_CLOCK_START_INSTANT.plusSeconds(10)
        clock.setTime(secondTime.toEpochMilli())

        // When it is the same worker, it should only update the last call time.
        spyCollector.registerWorker(
            WORKER_ID_1,
            PROCESS_ID_1,
            WorkerProcessStatus(),
            JAVAC_MNEMONIC,  /* isMultiplex= */
            true,  /* isSandboxed= */
            true,
            WORKER_KEY_HASH_1,  /* cgroup= */
            null
        )
        assertThat(spyCollector.getPidToWorkerProcessMetrics().keySet()).containsExactly(PROCESS_ID_1)
        assertWorkerMetricContains(
            spyCollector.getPidToWorkerProcessMetrics().get(PROCESS_ID_1),
            com.google.common.collect.ImmutableList.of<Int?>(WORKER_ID_1),
            PROCESS_ID_1,
            JAVAC_MNEMONIC,  /* expectedIsMultiplex= */
            true,  /* expectedIsSandboxed= */
            true,
            WORKER_KEY_HASH_1,  /* expectedActionsExecuted= */
            0,  /* expectedIsMeasurable= */
            false,  /* expectedLastCallTime= */
            secondTime,  /* expectedCollectedTime= */
            null
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCollectMetrics() {
        // Worker 1 simulates a measurable worker processes has executed some actions.
        spyCollector.registerWorker(
            WORKER_ID_1,
            PROCESS_ID_1,
            WorkerProcessStatus(),
            JAVAC_MNEMONIC,  /* isMultiplex= */
            true,  /* isSandboxed= */
            false,
            WORKER_KEY_HASH_1,  /* cgroup= */
            null
        )
        spyCollector.onWorkerFinishExecution(PROCESS_ID_1)
        // Worker 2 simulates a measurable worker process that has not yet completed execution of any
        // actions.
        spyCollector.registerWorker(
            WORKER_ID_2,
            PROCESS_ID_2,
            WorkerProcessStatus(),
            CPP_COMPILE_MNEMONIC,  /* isMultiplex= */
            false,  /* isSandboxed= */
            true,
            WORKER_KEY_HASH_2,  /* cgroup= */
            null
        )
        // Worker 3 simulates a non-measurable worker that has not executed any actions.
        spyCollector.registerWorker(
            WORKER_ID_3,
            PROCESS_ID_3,
            WorkerProcessStatus(),
            PROTO_MNEMONIC,  /* isMultiplex= */
            true,  /* isSandboxed= */
            true,
            WORKER_KEY_HASH_3,  /* cgroup= */
            null
        )
        // Worker 4 simulates a non-measurable worker that has executed an action and was killed.
        val s4: WorkerProcessStatus = WorkerProcessStatus()
        spyCollector.registerWorker(
            WORKER_ID_4,
            PROCESS_ID_4,  /* status= */
            s4,
            PROTO_MNEMONIC,  /* isMultiplex= */
            true,  /* isSandboxed= */
            true,
            WORKER_KEY_HASH_4,  /* cgroup= */
            null
        )
        spyCollector.onWorkerFinishExecution(PROCESS_ID_4)
        s4.maybeUpdateStatus(Status.KILLED_DUE_TO_MEMORY_PRESSURE)
        // Worker 5 simulates a non-measurable worker that has executed an action, but was not killed.
        val s5: WorkerProcessStatus = WorkerProcessStatus()
        spyCollector.registerWorker(
            WORKER_ID_5,
            PROCESS_ID_5,  /* status= */
            s5,
            PROTO_MNEMONIC,  /* isMultiplex= */
            true,  /* isSandboxed= */
            true,
            WORKER_KEY_HASH_5,  /* cgroup= */
            null
        )
        spyCollector.onWorkerFinishExecution(PROCESS_ID_5)

        val memoryUsageMap: com.google.common.collect.ImmutableMap<Long?, Int?> =
            com.google.common.collect.ImmutableMap.of<Long?, Int?>(
                PROCESS_ID_1, 1234,
                PROCESS_ID_2, 2345
            )
        val collectionTime: Instant = DEFAULT_CLOCK_START_INSTANT.plusSeconds(10)
        val resourceSnapshot: ResourceSnapshot? = ResourceSnapshot.create(memoryUsageMap, collectionTime)
        Mockito.doReturn(resourceSnapshot).`when`<Any?>(spyCollector).collectResourceUsage()
        clock.setTime(collectionTime.toEpochMilli())

        val metrics: com.google.common.collect.ImmutableList<WorkerProcessMetrics?> = spyCollector.collectMetrics()

        // All workers measurable or non-measurable should be reported.
        Truth.assertThat(metrics.stream().flatMap<Any?> { m: WorkerProcessMetrics? -> m.getWorkerIds().stream() }
            .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>()))
            .containsExactly(WORKER_ID_1, WORKER_ID_2, WORKER_ID_3, WORKER_ID_4, WORKER_ID_5)
        assertWorkerMetricContains(
            getWorkerProcessMetricsFromList(WORKER_ID_1, metrics),
            com.google.common.collect.ImmutableList.of<Int?>(WORKER_ID_1),
            PROCESS_ID_1,
            JAVAC_MNEMONIC,  /* expectedIsMultiplex= */
            true,  /* expectedIsSandboxed= */
            false,
            WORKER_KEY_HASH_1,  /* expectedActionsExecuted= */
            1,  /* expectedIsMeasurable= */
            true,  /* expectedLastCallTime= */
            DEFAULT_CLOCK_START_INSTANT,  /* expectedCollectedTime= */
            collectionTime
        )
        assertWorkerMetricContains(
            getWorkerProcessMetricsFromList(WORKER_ID_2, metrics),
            com.google.common.collect.ImmutableList.of<Int?>(WORKER_ID_2),
            PROCESS_ID_2,
            CPP_COMPILE_MNEMONIC,  /* expectedIsMultiplex= */
            false,  /* expectedIsSandboxed= */
            true,
            WORKER_KEY_HASH_2,  /* expectedActionsExecuted= */
            0,  /* expectedIsMeasurable= */
            true,  /* expectedLastCallTime= */
            DEFAULT_CLOCK_START_INSTANT,  /* expectedCollectedTime= */
            collectionTime
        )
        // Worker 3's metrics should not be included since it is both non-measurable and did not execute
        // any actions. Its status shouldn't be unknown because it is possible that
        assertWorkerMetricContains(
            getWorkerProcessMetricsFromList(WORKER_ID_3, metrics),
            com.google.common.collect.ImmutableList.of<Int?>(WORKER_ID_3),
            PROCESS_ID_3,
            PROTO_MNEMONIC,  /* expectedIsMultiplex= */
            true,  /* expectedIsSandboxed= */
            true,
            WORKER_KEY_HASH_3,  /* expectedActionsExecuted= */
            0,  /* expectedIsMeasurable= */
            false,  /* expectedLastCallTime= */
            DEFAULT_CLOCK_START_INSTANT,  /* expectedCollectedTime= */
            null
        )
        assertWorkerMetricContains(
            getWorkerProcessMetricsFromList(WORKER_ID_4, metrics),
            com.google.common.collect.ImmutableList.of<Int?>(WORKER_ID_4),
            PROCESS_ID_4,
            PROTO_MNEMONIC,  /* expectedIsMultiplex= */
            true,  /* expectedIsSandboxed= */
            true,
            WORKER_KEY_HASH_4,  /* expectedActionsExecuted= */
            1,  /* expectedIsMeasurable= */
            false,  /* expectedLastCallTime= */
            DEFAULT_CLOCK_START_INSTANT,  /* expectedCollectedTime= */
            null
        )
        assertWorkerMetricContains(
            getWorkerProcessMetricsFromList(WORKER_ID_5, metrics),
            com.google.common.collect.ImmutableList.of<Int?>(WORKER_ID_5),
            PROCESS_ID_5,
            PROTO_MNEMONIC,  /* expectedIsMultiplex= */
            true,  /* expectedIsSandboxed= */
            true,
            WORKER_KEY_HASH_5,  /* expectedActionsExecuted= */
            1,  /* expectedIsMeasurable= */
            false,  /* expectedLastCallTime= */
            DEFAULT_CLOCK_START_INSTANT,  /* expectedCollectedTime= */
            null
        )
        // Worker 5's status should have been updated to killed_unknown, because it had executed actions
        // but is now non-measurable.
        assertThat(s5.get()).isEqualTo(Status.KILLED_UNKNOWN)
    }

    @org.junit.Test
    fun testCollectResourceUsage_windows() {
        val collectionTime: Instant = DEFAULT_CLOCK_START_INSTANT.plusSeconds(10)
        clock.setTime(collectionTime.toEpochMilli())
        Mockito.`when`<T?>(psInfoCollector.collectResourceUsage(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .thenReturn(
                ResourceSnapshot.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(PROCESS_ID_1, 1000),
                    collectionTime
                )
            )
        Mockito.`when`<T?>(
            cgroupsInfoCollector.collectResourceUsage(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(
                ResourceSnapshot.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(PROCESS_ID_1, 2000),
                    collectionTime
                )
            )

        val snapshot: ResourceSnapshot? =
            spyCollector.collectResourceUsage(
                com.google.devtools.build.lib.util.OS.WINDOWS, com.google.common.collect.ImmutableSet.of<E?>(
                    PROCESS_ID_1
                )
            )

        // On non-linux and non-darwin, it should always return an empty snapshot.
        assertThat(snapshot).isEqualTo(
            ResourceSnapshot.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                collectionTime
            )
        )
    }

    @org.junit.Test
    fun testCollectResourceUsage_darwin_usingPs() {
        val collectionTime: Instant = DEFAULT_CLOCK_START_INSTANT.plusSeconds(10)
        clock.setTime(collectionTime.toEpochMilli())
        Mockito.`when`<T?>(psInfoCollector.collectResourceUsage(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .thenReturn(
                ResourceSnapshot.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(PROCESS_ID_1, 1000),
                    collectionTime
                )
            )
        Mockito.`when`<T?>(
            cgroupsInfoCollector.collectResourceUsage(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(
                ResourceSnapshot.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(PROCESS_ID_1, 2000),
                    collectionTime
                )
            )

        val snapshot: ResourceSnapshot? =
            spyCollector.collectResourceUsage(
                com.google.devtools.build.lib.util.OS.DARWIN, com.google.common.collect.ImmutableSet.of<E?>(
                    PROCESS_ID_1
                )
            )

        // Should return the cgroup information rather than the ps information.
        assertThat(snapshot)
            .isEqualTo(
                ResourceSnapshot.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(PROCESS_ID_1, 1000),
                    collectionTime
                )
            )
    }

    @org.junit.Test
    fun testCollectResourceUsage_linux_usingPs() {
        spyCollector.setUseCgroupsOnLinux( /* useCgroupsOnLinux= */false)
        val collectionTime: Instant = DEFAULT_CLOCK_START_INSTANT.plusSeconds(10)
        clock.setTime(collectionTime.toEpochMilli())
        Mockito.`when`<T?>(psInfoCollector.collectResourceUsage(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .thenReturn(
                ResourceSnapshot.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(PROCESS_ID_1, 1000),
                    collectionTime
                )
            )
        Mockito.`when`<T?>(
            cgroupsInfoCollector.collectResourceUsage(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(
                ResourceSnapshot.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(PROCESS_ID_1, 2000),
                    collectionTime
                )
            )

        val snapshot: ResourceSnapshot? =
            spyCollector.collectResourceUsage(
                com.google.devtools.build.lib.util.OS.LINUX, com.google.common.collect.ImmutableSet.of<E?>(
                    PROCESS_ID_1
                )
            )

        // Should return the ps information rather than the cgroup information.
        assertThat(snapshot)
            .isEqualTo(
                ResourceSnapshot.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(PROCESS_ID_1, 1000),
                    collectionTime
                )
            )
    }

    @org.junit.Test
    fun testCollectResourceUsage_linux_usingCgroups() {
        spyCollector.setUseCgroupsOnLinux( /* useCgroupsOnLinux= */true)
        val collectionTime: Instant = DEFAULT_CLOCK_START_INSTANT.plusSeconds(10)
        clock.setTime(collectionTime.toEpochMilli())
        Mockito.`when`<T?>(psInfoCollector.collectResourceUsage(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .thenReturn(
                ResourceSnapshot.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(PROCESS_ID_1, 1000),
                    collectionTime
                )
            )
        Mockito.`when`<T?>(
            cgroupsInfoCollector.collectResourceUsage(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(
                ResourceSnapshot.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(PROCESS_ID_1, 2000),
                    collectionTime
                )
            )

        val snapshot: ResourceSnapshot? =
            spyCollector.collectResourceUsage(
                com.google.devtools.build.lib.util.OS.LINUX, com.google.common.collect.ImmutableSet.of<E?>(
                    PROCESS_ID_1
                )
            )

        // Should return the cgroup information rather than the ps information.
        assertThat(snapshot)
            .isEqualTo(
                ResourceSnapshot.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(PROCESS_ID_1, 2000),
                    collectionTime
                )
            )
    }

    @org.junit.Test
    fun testWorkerMetricsPublishComparator_compare() {
        val alive1: WorkerMetrics = newWorkerMetrics(1, WorkerStatus.ALIVE, 100)
        val alive2: WorkerMetrics = newWorkerMetrics(2, WorkerStatus.ALIVE, 200)
        val evicted1: WorkerMetrics = newWorkerMetrics(3, WorkerStatus.KILLED_DUE_TO_MEMORY_PRESSURE, 100)
        val evicted2: WorkerMetrics = newWorkerMetrics(4, WorkerStatus.KILLED_DUE_TO_MEMORY_PRESSURE, 200)
        val others1: WorkerMetrics = newWorkerMetrics(5, WorkerStatus.KILLED_UNKNOWN, 100)
        val others2: WorkerMetrics =
            newWorkerMetrics(6, WorkerStatus.KILLED_DUE_TO_USER_EXEC_EXCEPTION, 200)

        val comparator: WorkerMetricsPublishComparator = WorkerMetricsPublishComparator()
        // WorkerMetrics of the same status priority should be compared by their memory usage (higher
        // gets prioritized).
        assertThat(comparator.compare(alive1, alive2)).isEqualTo(1)
        assertThat(comparator.compare(evicted1, evicted2)).isEqualTo(1)
        assertThat(comparator.compare(others1, others2)).isEqualTo(1)

        // WorkerMetrics should be first compared by their status priorities rather than their memory
        // usage.
        assertThat(comparator.compare(alive1, evicted2)).isEqualTo(-1)
        assertThat(comparator.compare(evicted1, others2)).isEqualTo(-1)
        assertThat(comparator.compare(others2, alive1)).isEqualTo(1)
    }

    @org.junit.Test
    fun testLimitWorkerMetricsToPublish() {
        val alive1: WorkerMetrics = newWorkerMetrics(1, WorkerStatus.ALIVE, 200)
        val alive2: WorkerMetrics = newWorkerMetrics(2, WorkerStatus.ALIVE, 100)
        val evicted3: WorkerMetrics = newWorkerMetrics(3, WorkerStatus.KILLED_DUE_TO_MEMORY_PRESSURE, 100)
        val evicted4: WorkerMetrics = newWorkerMetrics(4, WorkerStatus.KILLED_DUE_TO_MEMORY_PRESSURE, 200)
        val others5: WorkerMetrics = newWorkerMetrics(5, WorkerStatus.KILLED_UNKNOWN, 200)
        val others6: WorkerMetrics =
            newWorkerMetrics(6, WorkerStatus.KILLED_DUE_TO_USER_EXEC_EXCEPTION, 100)

        // Based on prioritization and then sorted by worker id.
        assertThat(
            WorkerProcessMetricsCollector.limitWorkerMetricsToPublish(
                com.google.common.collect.ImmutableList.of<E?>(alive1, alive2, evicted3, evicted4, others5, others6), 3
            )
        )
            .containsExactly(alive1, alive2, evicted4)
        assertThat(
            WorkerProcessMetricsCollector.limitWorkerMetricsToPublish(
                com.google.common.collect.ImmutableList.of<E?>(alive1, evicted4, others5, others6), 3
            )
        )
            .containsExactly(alive1, evicted4, others5)
        // If under the limit, it should just report everything.
        assertThat(
            WorkerProcessMetricsCollector.limitWorkerMetricsToPublish(
                com.google.common.collect.ImmutableList.of<E?>(alive1, alive2, evicted4, others6), 10
            )
        )
            .containsExactly(alive1, alive2, evicted4, others6)
    }

    private fun newWorkerMetrics(id: Int, status: WorkerStatus?, memoryInKb: Int): WorkerMetrics {
        return WorkerMetrics.newBuilder()
            .addWorkerIds(id)
            .setWorkerStatus(status)
            .addWorkerStats(
                WorkerMetrics.WorkerStats.newBuilder().setWorkerMemoryInKb(memoryInKb).build()
            )
            .build()
    }

    private fun getWorkerProcessMetricsFromList(
        workerId: Int, metrics: com.google.common.collect.ImmutableList<WorkerProcessMetrics?>
    ): WorkerProcessMetrics? {
        return metrics.stream().filter { wm: WorkerProcessMetrics? -> wm.getWorkerIds().contains(workerId) }.findFirst()
            .get()
    }

    private class ManualClock : com.google.devtools.build.lib.clock.Clock {
        private var currentTime = DEFAULT_CLOCK_START_TIME

        override fun nanoTime(): Long {
            throw java.lang.AssertionError("unexpected method call")
        }

        override fun currentTimeMillis(): Long {
            return currentTime
        }

        fun setTime(currentTime: Long) {
            this.currentTime = currentTime
        }
    }

    companion object {
        private const val WORKER_ID_1 = 1
        private const val WORKER_ID_2 = 2
        private const val WORKER_ID_3 = 3
        private const val WORKER_ID_4 = 4
        private const val WORKER_ID_5 = 5
        private const val PROCESS_ID_1 = 100L
        private const val PROCESS_ID_2 = 200L
        private const val PROCESS_ID_3 = 300L
        private const val PROCESS_ID_4 = 400L
        private const val PROCESS_ID_5 = 500L
        private const val WORKER_KEY_HASH_1 = 1
        private const val WORKER_KEY_HASH_2 = 2
        private const val WORKER_KEY_HASH_3 = 3
        private const val WORKER_KEY_HASH_4 = 4
        private const val WORKER_KEY_HASH_5 = 5
        private const val JAVAC_MNEMONIC = "Javac"
        private const val CPP_COMPILE_MNEMONIC = "CppCompile"
        private const val PROTO_MNEMONIC = "Proto"

        private const val DEFAULT_CLOCK_START_TIME = 0L
        private val DEFAULT_CLOCK_START_INSTANT: Instant = Instant.ofEpochMilli(DEFAULT_CLOCK_START_TIME)
    }
}
