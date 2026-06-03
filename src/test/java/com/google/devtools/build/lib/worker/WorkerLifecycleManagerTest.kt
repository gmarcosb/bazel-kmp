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

import com.google.devtools.build.lib.events.EventBusEventHandler

@RunWith(JUnit4::class)
class WorkerLifecycleManagerTest {
    @org.junit.Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    @org.mockito.Mock
    var factoryMock: WorkerFactory? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        factoryMock = spy(WorkerFactory(fileSystem.getPath("/outputbase/bazel-workers"), options))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEvictWorkers_doNothing_lowMemoryUsage() {
        val workerPool: WorkerPoolImpl =
            WorkerPoolImpl(
                factoryMock, WorkerPoolConfig(entryList(DUMMY_MNEMONIC, 1), emptyEntryList())
            )
        val key: WorkerKey? = WorkerTestUtils.createWorkerKey(DUMMY_MNEMONIC, fileSystem)
        val w1: Worker = workerPool.borrowWorker(key)
        workerPool.returnWorker(key, w1)
        val workerMetrics: com.google.common.collect.ImmutableList<WorkerProcessMetrics?> =
            com.google.common.collect.ImmutableList.of<WorkerProcessMetrics?>(
                createWorkerMetric(
                    w1,
                    PROCESS_ID_1,  /* memoryInKb= */
                    1000
                )
            )
        val options: WorkerOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(WorkerOptions::class.java)
        options.totalWorkerMemoryLimitMb = 1000 * 100

        val manager: WorkerLifecycleManager =
            WorkerLifecycleManager(
                workerPool,
                options,
                com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus())
            )

        assertThat(workerPool.getIdleWorkers()).hasSize(1)
        assertThat(workerPool.getNumActive(key)).isEqualTo(0)

        manager.evictWorkers(workerMetrics)

        assertThat(workerPool.getIdleWorkers()).hasSize(1)
        assertThat(workerPool.getNumActive(key)).isEqualTo(0)
        // It should still have a valid status since it was not killed / marked to be killed.
        assertThat(w1.getStatus().isValid()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEvictWorkers_doNothing_zeroThreshold() {
        val workerPool: WorkerPoolImpl =
            WorkerPoolImpl(
                factoryMock, WorkerPoolConfig(entryList(DUMMY_MNEMONIC, 1), emptyEntryList())
            )
        val key: WorkerKey? = WorkerTestUtils.createWorkerKey(DUMMY_MNEMONIC, fileSystem)
        val w1: Worker = workerPool.borrowWorker(key)
        workerPool.returnWorker(key, w1)

        val workerMetrics: com.google.common.collect.ImmutableList<WorkerProcessMetrics?> =
            com.google.common.collect.ImmutableList.of<WorkerProcessMetrics?>(
                createWorkerMetric(
                    w1,
                    PROCESS_ID_1,  /* memoryInKb= */
                    1000
                )
            )
        val options: WorkerOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(WorkerOptions::class.java)
        options.totalWorkerMemoryLimitMb = 0

        val manager: WorkerLifecycleManager =
            WorkerLifecycleManager(
                workerPool,
                options,
                com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus())
            )

        assertThat(workerPool.getIdleWorkers()).hasSize(1)
        assertThat(workerPool.getNumActive(key)).isEqualTo(0)

        manager.evictWorkers(workerMetrics)

        assertThat(workerPool.getIdleWorkers()).hasSize(1)
        assertThat(workerPool.getNumActive(key)).isEqualTo(0)
        // It should still have a valid status since it was not killed / marked to be killed.
        assertThat(w1.getStatus().isValid()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEvictWorkers_doNothing_emptyMetrics() {
        val workerPool: WorkerPoolImpl =
            WorkerPoolImpl(
                factoryMock, WorkerPoolConfig(entryList(DUMMY_MNEMONIC, 1), emptyEntryList())
            )
        val key: WorkerKey? = WorkerTestUtils.createWorkerKey(DUMMY_MNEMONIC, fileSystem)
        val w1: Worker = workerPool.borrowWorker(key)
        workerPool.returnWorker(key, w1)

        val workerMetrics: com.google.common.collect.ImmutableList<WorkerProcessMetrics?> =
            com.google.common.collect.ImmutableList.of<WorkerProcessMetrics?>()
        val options: WorkerOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(WorkerOptions::class.java)
        options.totalWorkerMemoryLimitMb = 1

        val manager: WorkerLifecycleManager =
            WorkerLifecycleManager(
                workerPool,
                options,
                com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus())
            )

        assertThat(workerPool.getIdleWorkers()).hasSize(1)
        assertThat(workerPool.getNumActive(key)).isEqualTo(0)

        manager.evictWorkers(workerMetrics)

        assertThat(workerPool.getIdleWorkers()).hasSize(1)
        assertThat(workerPool.getNumActive(key)).isEqualTo(0)
        // It should still have a valid status since it was not killed / marked to be killed.
        assertThat(w1.getStatus().isValid()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetEvictionCandidates_selectOnlyWorker() {
        val workerPool: WorkerPoolImpl =
            WorkerPoolImpl(
                factoryMock, WorkerPoolConfig(entryList(DUMMY_MNEMONIC, 1), emptyEntryList())
            )
        val key: WorkerKey? = WorkerTestUtils.createWorkerKey(DUMMY_MNEMONIC, fileSystem)
        val w1: Worker = workerPool.borrowWorker(key)
        workerPool.returnWorker(key, w1)
        val workerMetrics: com.google.common.collect.ImmutableList<WorkerProcessMetrics?> =
            com.google.common.collect.ImmutableList.of<WorkerProcessMetrics?>(
                createWorkerMetric(
                    w1,
                    PROCESS_ID_1,  /* memoryInKb= */
                    2000
                )
            )
        val options: WorkerOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(WorkerOptions::class.java)
        options.totalWorkerMemoryLimitMb = 1
        val manager: WorkerLifecycleManager =
            WorkerLifecycleManager(
                workerPool,
                options,
                com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus())
            )

        assertThat(workerPool.getIdleWorkers()).hasSize(1)
        assertThat(workerPool.getNumActive(key)).isEqualTo(0)
        // It should still have a valid status since it was not killed / marked to be killed.
        assertThat(w1.getStatus().isValid()).isTrue()

        manager.evictWorkers(workerMetrics)

        assertThat(workerPool.getIdleWorkers()).isEmpty()
        assertThat(workerPool.getNumActive(key)).isEqualTo(0)
        // Directly killed since it is already returned.
        assertThat(w1.getStatus().get()).isEqualTo(Status.KILLED_DUE_TO_MEMORY_PRESSURE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetEvictionCandidates_evictLargestWorkers() {
        val workerPool: WorkerPoolImpl =
            WorkerPoolImpl(
                factoryMock, WorkerPoolConfig(entryList(DUMMY_MNEMONIC, 3), emptyEntryList())
            )
        val key: WorkerKey? = WorkerTestUtils.createWorkerKey(DUMMY_MNEMONIC, fileSystem)
        val w1: Worker = workerPool.borrowWorker(key)
        val w2: Worker = workerPool.borrowWorker(key)
        val w3: Worker = workerPool.borrowWorker(key)
        workerPool.returnWorker(key, w1)
        workerPool.returnWorker(key, w2)
        workerPool.returnWorker(key, w3)

        val workerMetrics: com.google.common.collect.ImmutableList<WorkerProcessMetrics?> =
            com.google.common.collect.ImmutableList.of<WorkerProcessMetrics?>(
                createWorkerMetric(w1, PROCESS_ID_1,  /* memoryInKb= */2000),
                createWorkerMetric(w2, PROCESS_ID_2,  /* memoryInKb= */1000),
                createWorkerMetric(w3, PROCESS_ID_3,  /* memoryInKb= */4000)
            )

        val options: WorkerOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(WorkerOptions::class.java)
        options.totalWorkerMemoryLimitMb = 2
        val manager: WorkerLifecycleManager =
            WorkerLifecycleManager(
                workerPool,
                options,
                com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus())
            )

        assertThat(workerPool.getIdleWorkers()).hasSize(3)
        assertThat(workerPool.getNumActive(key)).isEqualTo(0)
        assertThat(w1.getStatus().isValid()).isTrue()
        assertThat(w2.getStatus().isValid()).isTrue()
        assertThat(w3.getStatus().isValid()).isTrue()

        manager.evictWorkers(workerMetrics)

        assertThat(workerPool.getIdleWorkers()).hasSize(1)
        assertThat(workerPool.getNumActive(key)).isEqualTo(0)
        // Only w1 and w3 should have been killed.
        assertThat(w1.getStatus().get()).isEqualTo(Status.KILLED_DUE_TO_MEMORY_PRESSURE)
        assertThat(w2.getStatus().isValid()).isTrue()
        assertThat(w3.getStatus().get()).isEqualTo(Status.KILLED_DUE_TO_MEMORY_PRESSURE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetEvictionCandidates_numberOfWorkersIsMoreThanDefaultNumTests() {
        val workerPool: WorkerPoolImpl =
            WorkerPoolImpl(
                factoryMock, WorkerPoolConfig(entryList(DUMMY_MNEMONIC, 4), emptyEntryList())
            )
        val key: WorkerKey? = WorkerTestUtils.createWorkerKey(DUMMY_MNEMONIC, fileSystem)
        val w1: Worker = workerPool.borrowWorker(key)
        val w2: Worker = workerPool.borrowWorker(key)
        val w3: Worker = workerPool.borrowWorker(key)
        val w4: Worker = workerPool.borrowWorker(key)
        workerPool.returnWorker(key, w1)
        workerPool.returnWorker(key, w2)
        workerPool.returnWorker(key, w3)
        workerPool.returnWorker(key, w4)

        val workerMetrics: com.google.common.collect.ImmutableList<WorkerProcessMetrics?> =
            com.google.common.collect.ImmutableList.of<WorkerProcessMetrics?>(
                createWorkerMetric(w1, PROCESS_ID_1,  /* memoryInKb= */2000),
                createWorkerMetric(w2, PROCESS_ID_2,  /* memoryInKb= */2000),
                createWorkerMetric(w3, PROCESS_ID_3,  /* memoryInKb= */4000),
                createWorkerMetric(w4, PROCESS_ID_4,  /* memoryInKb= */4000)
            )

        val options: WorkerOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(WorkerOptions::class.java)
        options.totalWorkerMemoryLimitMb = 1
        val manager: WorkerLifecycleManager =
            WorkerLifecycleManager(
                workerPool,
                options,
                com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus())
            )

        assertThat(workerPool.getIdleWorkers()).hasSize(4)
        assertThat(workerPool.getNumActive(key)).isEqualTo(0)

        manager.evictWorkers(workerMetrics)

        assertThat(workerPool.getIdleWorkers()).isEmpty()
        assertThat(workerPool.getNumActive(key)).isEqualTo(0)
        assertThat(w1.getStatus().get()).isEqualTo(Status.KILLED_DUE_TO_MEMORY_PRESSURE)
        assertThat(w2.getStatus().get()).isEqualTo(Status.KILLED_DUE_TO_MEMORY_PRESSURE)
        assertThat(w3.getStatus().get()).isEqualTo(Status.KILLED_DUE_TO_MEMORY_PRESSURE)
        assertThat(w4.getStatus().get()).isEqualTo(Status.KILLED_DUE_TO_MEMORY_PRESSURE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetEvictionCandidates_evictWorkerWithSameMenmonicButDifferentKeys() {
        val workerPool: WorkerPoolImpl =
            WorkerPoolImpl(
                factoryMock, WorkerPoolConfig(entryList(DUMMY_MNEMONIC, 3), emptyEntryList())
            )
        val key1: WorkerKey? = WorkerTestUtils.createWorkerKey(DUMMY_MNEMONIC, fileSystem)
        val key2: WorkerKey? = WorkerTestUtils.createWorkerKey(DUMMY_MNEMONIC, fileSystem, true)

        val w1: Worker = workerPool.borrowWorker(key1)
        val w2: Worker = workerPool.borrowWorker(key2)
        val w3: Worker = workerPool.borrowWorker(key2)
        workerPool.returnWorker(key1, w1)
        workerPool.returnWorker(key2, w2)
        workerPool.returnWorker(key2, w3)

        val workerMetrics: com.google.common.collect.ImmutableList<WorkerProcessMetrics?> =
            com.google.common.collect.ImmutableList.of<WorkerProcessMetrics?>(
                createWorkerMetric(w1, PROCESS_ID_1,  /* memoryInKb= */3000),
                createWorkerMetric(w2, PROCESS_ID_2,  /* memoryInKb= */3000),
                createWorkerMetric(w3, PROCESS_ID_3,  /* memoryInKb= */1000)
            )

        val options: WorkerOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(WorkerOptions::class.java)
        options.totalWorkerMemoryLimitMb = 2
        options.workerVerbose = true
        val manager: WorkerLifecycleManager =
            WorkerLifecycleManager(
                workerPool,
                options,
                com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus())
            )

        assertThat(workerPool.getIdleWorkers())
            .containsExactly(w1.workerId, w2.workerId, w3.workerId)
        assertThat(w1.getStatus().isValid()).isTrue()
        assertThat(w2.getStatus().isValid()).isTrue()
        assertThat(w3.getStatus().isValid()).isTrue()

        manager.evictWorkers(workerMetrics)

        // Only w3 shouldn't be killed.
        assertThat(workerPool.getIdleWorkers()).containsExactly(w3.workerId)
        assertThat(workerPool.getNumActive(key1)).isEqualTo(0)
        assertThat(workerPool.getNumActive(key2)).isEqualTo(0)
        assertThat(w1.getStatus().get()).isEqualTo(Status.KILLED_DUE_TO_MEMORY_PRESSURE)
        assertThat(w2.getStatus().get()).isEqualTo(Status.KILLED_DUE_TO_MEMORY_PRESSURE)
        assertThat(w3.getStatus().isValid()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetEvictionCandidates_evictOnlyIdleWorkers() {
        val workerPool: WorkerPoolImpl =
            WorkerPoolImpl(
                factoryMock, WorkerPoolConfig(entryList(DUMMY_MNEMONIC, 3), emptyEntryList())
            )
        val key: WorkerKey? = WorkerTestUtils.createWorkerKey(DUMMY_MNEMONIC, fileSystem)
        val w1: Worker = workerPool.borrowWorker(key)
        val w2: Worker = workerPool.borrowWorker(key)
        val w3: Worker = workerPool.borrowWorker(key)
        workerPool.returnWorker(key, w1)
        workerPool.returnWorker(key, w2)

        val workerMetrics: com.google.common.collect.ImmutableList<WorkerProcessMetrics?> =
            com.google.common.collect.ImmutableList.of<WorkerProcessMetrics?>(
                createWorkerMetric(w1, PROCESS_ID_1,  /* memoryInKb= */2000),
                createWorkerMetric(w2, PROCESS_ID_2,  /* memoryInKb= */1000),
                createWorkerMetric(w3, PROCESS_ID_3,  /* memoryInKb= */4000)
            )

        val options: WorkerOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(WorkerOptions::class.java)
        options.totalWorkerMemoryLimitMb = 2

        val manager: WorkerLifecycleManager =
            WorkerLifecycleManager(
                workerPool,
                options,
                com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus())
            )

        assertThat(workerPool.getIdleWorkers()).hasSize(2)
        assertThat(workerPool.getNumActive(key)).isEqualTo(1)
        assertThat(w1.getStatus().isValid()).isTrue()
        assertThat(w2.getStatus().isValid()).isTrue()
        assertThat(w3.getStatus().isValid()).isTrue()

        manager.evictWorkers(workerMetrics)

        assertThat(workerPool.getIdleWorkers()).isEmpty()
        assertThat(workerPool.getNumActive(key)).isEqualTo(1)
        assertThat(w1.getStatus().get()).isEqualTo(Status.KILLED_DUE_TO_MEMORY_PRESSURE)
        assertThat(w2.getStatus().get()).isEqualTo(Status.KILLED_DUE_TO_MEMORY_PRESSURE)
        // w3 is not killed because we're not shrinking the worker pool.
        assertThat(w3.getStatus().isValid()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetEvictionCandidates_evictDifferentWorkerKeys() {
        val workerPool: WorkerPoolImpl =
            WorkerPoolImpl(
                factoryMock,
                WorkerPoolConfig(entryList(DUMMY_MNEMONIC, 2, "smart", 2), emptyEntryList())
            )
        val key1: WorkerKey? = WorkerTestUtils.createWorkerKey(DUMMY_MNEMONIC, fileSystem)
        val key2: WorkerKey? = WorkerTestUtils.createWorkerKey("smart", fileSystem)
        val w1: Worker = workerPool.borrowWorker(key1)
        val w2: Worker = workerPool.borrowWorker(key1)
        val w3: Worker = workerPool.borrowWorker(key2)
        val w4: Worker = workerPool.borrowWorker(key2)
        workerPool.returnWorker(key1, w1)
        workerPool.returnWorker(key1, w2)
        workerPool.returnWorker(key2, w3)
        workerPool.returnWorker(key2, w4)

        val workerMetrics: com.google.common.collect.ImmutableList<WorkerProcessMetrics?> =
            com.google.common.collect.ImmutableList.of<WorkerProcessMetrics?>(
                createWorkerMetric(w1, PROCESS_ID_1,  /* memoryInKb= */1000),
                createWorkerMetric(w2, PROCESS_ID_2,  /* memoryInKb= */4000),
                createWorkerMetric(w3, PROCESS_ID_3,  /* memoryInKb= */3000),
                createWorkerMetric(w4, PROCESS_ID_4,  /* memoryInKb= */1000)
            )

        val options: WorkerOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(WorkerOptions::class.java)
        options.totalWorkerMemoryLimitMb = 2

        val manager: WorkerLifecycleManager =
            WorkerLifecycleManager(
                workerPool,
                options,
                com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus())
            )

        assertThat(workerPool.getIdleWorkers()).hasSize(4)
        assertThat(workerPool.getNumActive(key1)).isEqualTo(0)
        assertThat(workerPool.getNumActive(key2)).isEqualTo(0)
        assertThat(w1.getStatus().isValid()).isTrue()
        assertThat(w2.getStatus().isValid()).isTrue()
        assertThat(w3.getStatus().isValid()).isTrue()
        assertThat(w4.getStatus().isValid()).isTrue()

        manager.evictWorkers(workerMetrics)

        // Only w1 and w4 should be alive.
        assertThat(workerPool.getIdleWorkers()).containsExactly(w1.workerId, w4.workerId)
        assertThat(workerPool.getNumActive(key1)).isEqualTo(0)
        assertThat(workerPool.getNumActive(key2)).isEqualTo(0)
        assertThat(workerPool.borrowWorker(key1).workerId).isEqualTo(w1.workerId)
        assertThat(workerPool.borrowWorker(key2).workerId).isEqualTo(w4.workerId)
        assertThat(w1.getStatus().isValid()).isTrue()
        assertThat(w2.getStatus().get()).isEqualTo(Status.KILLED_DUE_TO_MEMORY_PRESSURE)
        assertThat(w3.getStatus().get()).isEqualTo(Status.KILLED_DUE_TO_MEMORY_PRESSURE)
        assertThat(w4.getStatus().isValid()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetEvictionCandidates_testDoomedWorkers() {
        val workerPool: WorkerPoolImpl =
            WorkerPoolImpl(
                factoryMock, WorkerPoolConfig(entryList(DUMMY_MNEMONIC, 2), emptyEntryList())
            )
        val key: WorkerKey? = WorkerTestUtils.createWorkerKey(DUMMY_MNEMONIC, fileSystem)
        val w1: Worker = workerPool.borrowWorker(key)
        val w2: Worker = workerPool.borrowWorker(key)

        val workerMetrics: com.google.common.collect.ImmutableList<WorkerProcessMetrics?> =
            com.google.common.collect.ImmutableList.of<WorkerProcessMetrics?>(
                createWorkerMetric(w1, PROCESS_ID_1,  /* memoryInKb= */2000),
                createWorkerMetric(w2, PROCESS_ID_2,  /* memoryInKb= */2000)
            )

        val options: WorkerOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(WorkerOptions::class.java)
        options.totalWorkerMemoryLimitMb = 1
        options.shrinkWorkerPool = true

        val manager: WorkerLifecycleManager =
            WorkerLifecycleManager(
                workerPool,
                options,
                com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus())
            )

        assertThat(workerPool.getIdleWorkers()).isEmpty()
        assertThat(workerPool.getNumActive(key)).isEqualTo(2)
        assertThat(w1.getStatus().isValid()).isTrue()
        assertThat(w2.getStatus().isValid()).isTrue()

        manager.evictWorkers(workerMetrics)

        assertThat(w1.getStatus().get()).isEqualTo(Status.PENDING_KILL_DUE_TO_MEMORY_PRESSURE)
        assertThat(w2.getStatus().get()).isEqualTo(Status.PENDING_KILL_DUE_TO_MEMORY_PRESSURE)

        // Return only one worker.
        workerPool.returnWorker(key, w1)

        // w1 gets destroyed when it is returned, so there are 0 idle workers.
        assertThat(workerPool.getIdleWorkers()).isEmpty()
        assertThat(workerPool.getNumActive(key)).isEqualTo(1)
        // Since w1 is already returned, it is killed on return.
        assertThat(w1.getStatus().get()).isEqualTo(Status.KILLED_DUE_TO_MEMORY_PRESSURE)
        // Since w2 is still active, it is marked to be killed which will happen when it is returned.
        assertThat(w2.getStatus().get()).isEqualTo(Status.PENDING_KILL_DUE_TO_MEMORY_PRESSURE)

        // Return the remaining worker.
        workerPool.returnWorker(key, w2)
        assertThat(w2.getStatus().get()).isEqualTo(Status.KILLED_DUE_TO_MEMORY_PRESSURE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetEvictionCandidates_testDoomedAndIdleWorkers() {
        val workerPool: WorkerPoolImpl =
            WorkerPoolImpl(
                factoryMock, WorkerPoolConfig(entryList(DUMMY_MNEMONIC, 5), emptyEntryList())
            )
        val key: WorkerKey? = WorkerTestUtils.createWorkerKey(DUMMY_MNEMONIC, fileSystem)
        val w1: Worker = workerPool.borrowWorker(key)
        val w2: Worker = workerPool.borrowWorker(key)
        val w3: Worker = workerPool.borrowWorker(key)
        val w4: Worker = workerPool.borrowWorker(key)
        val w5: Worker = workerPool.borrowWorker(key)
        workerPool.returnWorker(key, w1)
        workerPool.returnWorker(key, w2)

        val workerMetrics: com.google.common.collect.ImmutableList<WorkerProcessMetrics?> =
            com.google.common.collect.ImmutableList.of<WorkerProcessMetrics?>(
                createWorkerMetric(w1, PROCESS_ID_1,  /* memoryInKb= */2000),
                createWorkerMetric(w2, PROCESS_ID_2,  /* memoryInKb= */1000),
                createWorkerMetric(w3, PROCESS_ID_3,  /* memoryInKb= */4000),
                createWorkerMetric(w4, PROCESS_ID_4,  /* memoryInKb= */5000),
                createWorkerMetric(w5, PROCESS_ID_5,  /* memoryInKb= */1000)
            )

        val options: WorkerOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(WorkerOptions::class.java)
        options.totalWorkerMemoryLimitMb = 2
        options.shrinkWorkerPool = true

        val manager: WorkerLifecycleManager =
            WorkerLifecycleManager(
                workerPool,
                options,
                com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus())
            )

        assertThat(workerPool.getIdleWorkers()).hasSize(2)
        assertThat(workerPool.getNumActive(key)).isEqualTo(3)
        assertThat(w1.getStatus().isValid()).isTrue()
        assertThat(w2.getStatus().isValid()).isTrue()
        assertThat(w3.getStatus().isValid()).isTrue()
        assertThat(w4.getStatus().isValid()).isTrue()
        assertThat(w5.getStatus().isValid()).isTrue()

        manager.evictWorkers(workerMetrics)

        assertThat(workerPool.getIdleWorkers()).isEmpty()
        assertThat(workerPool.getNumActive(key)).isEqualTo(3)
        // w1 and w2 are killed immediately.
        assertThat(w1.getStatus().get()).isEqualTo(Status.KILLED_DUE_TO_MEMORY_PRESSURE)
        assertThat(w2.getStatus().get()).isEqualTo(Status.KILLED_DUE_TO_MEMORY_PRESSURE)
        // w3 and w4 are killed only when returned.
        assertThat(w3.getStatus().get()).isEqualTo(Status.PENDING_KILL_DUE_TO_MEMORY_PRESSURE)
        assertThat(w4.getStatus().get()).isEqualTo(Status.PENDING_KILL_DUE_TO_MEMORY_PRESSURE)
        assertThat(w5.getStatus().isValid()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun evictWorkers_testMultiplexWorkers() {
        val workerPool: WorkerPoolImpl =
            WorkerPoolImpl(
                factoryMock, WorkerPoolConfig(emptyEntryList(), entryList(DUMMY_MNEMONIC, 2))
            )
        val key: WorkerKey? =
            WorkerTestUtils.createWorkerKey(DUMMY_MNEMONIC, fileSystem,  /* multiplex= */true,  /* sandboxed= */false)
        val w1: Worker = workerPool.borrowWorker(key)
        val w2: Worker = workerPool.borrowWorker(key)

        // Multiplex workers should share the same status instance.
        assertThat(w1.getStatus()).isSameInstanceAs(w2.getStatus())

        workerPool.returnWorker(key, w1)
        workerPool.returnWorker(key, w2)
        val workerMetrics: com.google.common.collect.ImmutableList<WorkerProcessMetrics?> =
            com.google.common.collect.ImmutableList.of<WorkerProcessMetrics?>(
                createMultiplexWorkerMetric(
                    com.google.common.collect.ImmutableList.of<Worker?>(w1, w2), PROCESS_ID_1,  /* memoryInKb= */4000
                )
            )
        val options: WorkerOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(WorkerOptions::class.java)
        options.totalWorkerMemoryLimitMb = 1
        val manager: WorkerLifecycleManager =
            WorkerLifecycleManager(
                workerPool,
                options,
                com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus())
            )

        manager.evictWorkers(workerMetrics)

        assertThat(workerPool.getIdleWorkers()).isEmpty()
        assertThat(workerPool.getNumActive(key)).isEqualTo(0)
        // Since both w1 and w2 have been returned, it is killed.
        assertThat(w1.getStatus().get()).isEqualTo(Status.KILLED_DUE_TO_MEMORY_PRESSURE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun evictWorkers_doomMultiplexWorker() {
        val workerPool: WorkerPoolImpl =
            WorkerPoolImpl(
                factoryMock, WorkerPoolConfig(emptyEntryList(), entryList(DUMMY_MNEMONIC, 2))
            )
        val key: WorkerKey? =
            WorkerTestUtils.createWorkerKey(DUMMY_MNEMONIC, fileSystem,  /* multiplex= */true,  /* sandboxed= */false)
        val w1: Worker = workerPool.borrowWorker(key)
        val w2: Worker = workerPool.borrowWorker(key)

        // Multiplex workers should share the same status instance.
        assertThat(w1.getStatus()).isSameInstanceAs(w2.getStatus())

        workerPool.returnWorker(key, w1)
        val workerMetrics: com.google.common.collect.ImmutableList<WorkerProcessMetrics?> =
            com.google.common.collect.ImmutableList.of<WorkerProcessMetrics?>(
                createMultiplexWorkerMetric(
                    com.google.common.collect.ImmutableList.of<Worker?>(w1, w2), PROCESS_ID_1,  /* memoryInKb= */4000
                )
            )
        val options: WorkerOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(WorkerOptions::class.java)
        options.totalWorkerMemoryLimitMb = 1
        options.shrinkWorkerPool = true
        val manager: WorkerLifecycleManager =
            WorkerLifecycleManager(
                workerPool,
                options,
                com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus())
            )

        manager.evictWorkers(workerMetrics)

        // w1 should have been evicted already.
        assertThat(workerPool.getIdleWorkers()).isEmpty()
        assertThat(workerPool.getNumActive(key)).isEqualTo(1)
        // Not yet killed because w2 is still alive (and both share a WorkerProcessStatus).
        assertThat(w1.getStatus().get()).isEqualTo(Status.PENDING_KILL_DUE_TO_MEMORY_PRESSURE)

        workerPool.returnWorker(key, w2)
        assertThat(workerPool.getIdleWorkers()).isEmpty()
        assertThat(workerPool.getNumActive(key)).isEqualTo(0)
        // Status is only set to killed after the last worker proxy is destroyed.
        assertThat(w2.getStatus().get()).isEqualTo(Status.KILLED_DUE_TO_MEMORY_PRESSURE)
    }

    companion object {
        val fileSystem: FileSystem =
            InMemoryFileSystem(com.google.devtools.build.lib.clock.BlazeClock.instance(), DigestHashFunction.SHA256)
        private val options: WorkerOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(WorkerOptions::class.java)
        private const val DUMMY_MNEMONIC = "dummy"
        private const val PROCESS_ID_1 = 1L
        private const val PROCESS_ID_2 = 2L
        private const val PROCESS_ID_3 = 3L
        private const val PROCESS_ID_4 = 4L
        private const val PROCESS_ID_5 = 5L

        private val DEFAULT_INSTANT: Instant? = com.google.devtools.build.lib.clock.BlazeClock.instance().now()

        private fun createWorkerMetric(
            worker: Worker, processId: Long, memoryInKb: Int
        ): WorkerProcessMetrics {
            // We need to override the processId.
            val wm: WorkerProcessMetrics =
                WorkerProcessMetrics(
                    worker.workerId,
                    processId,
                    worker.getStatus(),
                    worker.getWorkerKey().mnemonic,
                    worker.getWorkerKey().isMultiplex(),
                    worker.getWorkerKey().isSandboxed(),
                    worker.getWorkerKey().hashCode()
                )
            wm.addCollectedMetrics(memoryInKb,  /* collectionTime= */DEFAULT_INSTANT)
            return wm
        }

        private fun createMultiplexWorkerMetric(
            workers: com.google.common.collect.ImmutableList<Worker?>, processId: Long, memoryInKb: Int
        ): WorkerProcessMetrics {
            val workerProcessMetrics: WorkerProcessMetrics =
                WorkerProcessMetrics(
                    workers.stream().map<Any?>(Worker::getWorkerId)
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>()),
                    processId,
                    workers.get(0).getStatus(),
                    workers.get(0).getWorkerKey().mnemonic,
                    workers.get(0).getWorkerKey().isMultiplex(),
                    workers.get(0).getWorkerKey().isSandboxed(),
                    workers.get(0).getWorkerKey().hashCode()
                )
            workerProcessMetrics.addCollectedMetrics(memoryInKb,  /* collectionTime= */DEFAULT_INSTANT)
            return workerProcessMetrics
        }

        private fun emptyEntryList(): com.google.common.collect.ImmutableList<MutableMap.MutableEntry<String?, Int?>?> {
            return com.google.common.collect.ImmutableList.of<MutableMap.MutableEntry<String?, Int?>?>()
        }

        private fun entryList(
            key1: String?,
            value1: Int
        ): com.google.common.collect.ImmutableList<MutableMap.MutableEntry<String?, Int?>?> {
            return com.google.common.collect.ImmutableList.of<MutableMap.MutableEntry<String?, Int?>?>(
                com.google.common.collect.Maps.immutableEntry<String?, Int?>(
                    key1,
                    value1
                )
            )
        }

        private fun entryList(
            key1: String?, value1: Int, key2: String?, value2: Int
        ): com.google.common.collect.ImmutableList<MutableMap.MutableEntry<String?, Int?>?> {
            return com.google.common.collect.ImmutableList.of<MutableMap.MutableEntry<String?, Int?>?>(
                com.google.common.collect.Maps.immutableEntry<String?, Int?>(
                    key1,
                    value1
                ), com.google.common.collect.Maps.immutableEntry<String?, Int?>(key2, value2)
            )
        }
    }
}
