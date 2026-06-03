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

import com.google.devtools.build.lib.vfs.DigestHashFunction

/** Tests WorkerPool.  */
@RunWith(JUnit4::class)
class WorkerPoolTest {
    private var workerIds = 1

    private var workerPool: WorkerPool? = null
    private var factoryMock: WorkerFactory? = null

    private class TestWorker(
        workerKey: WorkerKey?,
        workerId: Int,
        workDir: Path?,
        logFile: Path?,
        options: WorkerOptions?
    ) : SingleplexWorker(workerKey, workerId, workDir, logFile, options, null)

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        factoryMock = spy(WorkerFactory(fileSystem.getPath("/outputbase/bazel-workers"), options))
        workerPool =
            WorkerPoolImpl(
                factoryMock,
                WorkerPoolConfig( /* workerMaxInstances= */
                    com.google.common.collect.ImmutableList.of<E?>(
                        com.google.common.collect.Maps.immutableEntry<String?, Int?>(
                            "mnem",
                            2
                        )
                    ),  /* workerMaxMultiplexInstances= */
                    com.google.common.collect.ImmutableList.of<E?>(
                        com.google.common.collect.Maps.immutableEntry<String?, Int?>("mnem", 2)
                    )
                )
            )
        Mockito.doAnswer(
            Answer { arg: InvocationOnMock? ->
                com.google.devtools.build.lib.worker.WorkerPoolTest.TestWorker(
                    arg.getArgument<WorkerKey?>(0),
                    workerIds++,
                    fileSystem.getPath("/workDir"),
                    fileSystem.getPath("/logDir"),
                    options
                )
            })
            .`when`<Any?>(factoryMock)
            .create(ArgumentMatchers.any<T?>())
        Mockito.doAnswer(
            Answer { args: InvocationOnMock? ->
                val worker: Worker = args.getArgument<Worker>(1)
                worker.getStatus().isValid()
            })
            .`when`<Any?>(factoryMock)
            .validateWorker(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
        Mockito.doAnswer(
            Answer { args: InvocationOnMock? ->
                val worker: Worker = args.getArgument<Worker>(1)
                worker.destroy()
                null
            })
            .`when`<Any?>(factoryMock)
            .destroyWorker(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBorrow_createsWhenNeeded() {
        val workerKey: WorkerKey? = WorkerTestUtils.createWorkerKey(fileSystem, "mnem", false)
        val worker1: Worker = workerPool.borrowWorker(workerKey)
        val worker2: Worker = workerPool.borrowWorker(workerKey)
        assertThat(worker1.workerId).isEqualTo(1)
        assertThat(worker2.workerId).isEqualTo(2)
        Mockito.verify<Any?>(factoryMock, Mockito.times(2)).create(workerKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBorrow_reusesWhenPossible() {
        val workerKey: WorkerKey? = WorkerTestUtils.createWorkerKey(fileSystem, "mnem", false)
        val worker1: Worker? = workerPool.borrowWorker(workerKey)
        workerPool.returnWorker(workerKey, worker1)
        val worker2: Worker? = workerPool.borrowWorker(workerKey)
        assertThat(worker1).isSameInstanceAs(worker2)
        Mockito.verify<Any?>(factoryMock).create(workerKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBorrow_nonSpecifiedKey() {
        val workerKey1: WorkerKey? = WorkerTestUtils.createWorkerKey(fileSystem, "mnem", false)
        val worker1: Worker = workerPool.borrowWorker(workerKey1)
        val worker1a: Worker = workerPool.borrowWorker(workerKey1)
        assertThat(worker1.workerId).isEqualTo(1)
        assertThat(worker1a.workerId).isEqualTo(2)
        val workerKey2: WorkerKey? = WorkerTestUtils.createWorkerKey(fileSystem, "other", false)
        val worker2: Worker = workerPool.borrowWorker(workerKey2)
        assertThat(worker2.workerId).isEqualTo(3)
        Mockito.verify<Any?>(factoryMock, Mockito.times(2)).create(workerKey1)
        Mockito.verify<Any?>(factoryMock).create(workerKey2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBorrow_pooledByKey() {
        val workerKey1: WorkerKey? = WorkerTestUtils.createWorkerKey(fileSystem, "mnem", false)
        val worker1: Worker = workerPool.borrowWorker(workerKey1)
        val worker1a: Worker = workerPool.borrowWorker(workerKey1)
        assertThat(worker1.workerId).isEqualTo(1)
        assertThat(worker1a.workerId).isEqualTo(2)
        val workerKey2: WorkerKey? = WorkerTestUtils.createWorkerKey(fileSystem, "mnem", false, "arg1")
        val worker2: Worker = workerPool.borrowWorker(workerKey2)
        assertThat(worker2.workerId).isEqualTo(3)
        Mockito.verify<Any?>(factoryMock, Mockito.times(2)).create(workerKey1)
        Mockito.verify<Any?>(factoryMock).create(workerKey2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBorrow_separateMultiplexWorkers() {
        val workerKey: WorkerKey? = WorkerTestUtils.createWorkerKey(fileSystem, "mnem", false)
        val worker1: Worker = workerPool.borrowWorker(workerKey)
        assertThat(worker1.workerId).isEqualTo(1)
        workerPool.returnWorker(workerKey, worker1)

        val multiplexKey: WorkerKey? = WorkerTestUtils.createWorkerKey(fileSystem, "mnem", true)
        val multiplexWorker1: Worker = workerPool.borrowWorker(multiplexKey)
        val multiplexWorker2: Worker = workerPool.borrowWorker(multiplexKey)
        val worker1a: Worker = workerPool.borrowWorker(workerKey)

        assertThat(multiplexWorker1.workerId).isEqualTo(2)
        assertThat(multiplexWorker2.workerId).isEqualTo(3)
        assertThat(worker1a.workerId).isEqualTo(1)

        Mockito.verify<Any?>(factoryMock).create(workerKey)
        Mockito.verify<Any?>(factoryMock, Mockito.times(2)).create(multiplexKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBorrow_doomedWorkers() {
        val workerKey: WorkerKey? = WorkerTestUtils.createWorkerKey(fileSystem, "mnem", false)
        val worker1: Worker = workerPool.borrowWorker(workerKey)
        val worker2: Worker = workerPool.borrowWorker(workerKey)

        worker1.getStatus().maybeUpdateStatus(Status.PENDING_KILL_DUE_TO_MEMORY_PRESSURE)

        assertThat(worker1.getStatus().isKilled()).isFalse()
        assertThat(worker2.getStatus().isKilled()).isFalse()

        workerPool.returnWorker(workerKey, worker1)

        assertThat(worker1.getStatus().isKilled()).isTrue()
        assertThat(worker2.getStatus().isKilled()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBorrow_blocksWhenUnavailable() {
        val workerKey: WorkerKey? = WorkerTestUtils.createWorkerKey(fileSystem, "mnem", false)
        val unused1: Worker? = workerPool.borrowWorker(workerKey)
        val unused2: Worker? = workerPool.borrowWorker(workerKey)
        val blockedBorrowThread: TestThread =
            TestThread(
                TestRunnable {
                    val unused: Worker? = workerPool.borrowWorker(workerKey)
                })
        blockedBorrowThread.start()

        val e: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { blockedBorrowThread.joinAndAssertState(1000) })
        Truth.assertThat(e).hasCauseThat().hasMessageThat().contains("is still alive")
        assertThat(workerPool.getNumActive(workerKey)).isEqualTo(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBorrow_blockedThread_getsReturnedWorker() {
        val workerKey: WorkerKey? = WorkerTestUtils.createWorkerKey(fileSystem, "mnem", false)
        val worker1: Worker = workerPool.borrowWorker(workerKey)
        val unused2: Worker? = workerPool.borrowWorker(workerKey)
        val blockedBorrowThread: TestThread =
            TestThread(
                TestRunnable {
                    // This blocks until worker1 returns its object.
                    val worker: Worker? = workerPool.borrowWorker(workerKey)
                    assertThat(worker).isSameInstanceAs(worker1)
                })
        blockedBorrowThread.start()

        // We want to 3rd borrow to be blocked for some time.
        java.lang.Thread.sleep(500)
        workerPool.returnWorker(worker1.getWorkerKey(), worker1)

        blockedBorrowThread.joinAndAssertState(10000)
        assertThat(workerPool.getNumActive(workerKey)).isEqualTo(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBorrow_blockedThread_createsWorkerWhenInvalidated() {
        val workerKey: WorkerKey? = WorkerTestUtils.createWorkerKey(fileSystem, "mnem", false)
        val worker1: Worker? = workerPool.borrowWorker(workerKey)
        val unused2: Worker? = workerPool.borrowWorker(workerKey)
        val blockedBorrowThread: TestThread =
            TestThread(
                TestRunnable {
                    val worker: Worker = workerPool.borrowWorker(workerKey)
                    // Create a new worker instead.
                    assertThat(worker.workerId).isEqualTo(3)
                })
        blockedBorrowThread.start()

        // We want to 3rd borrow to be blocked for some time.
        java.lang.Thread.sleep(500)
        workerPool.invalidateWorker(worker1)

        blockedBorrowThread.joinAndAssertState(10000)
        assertThat(workerPool.getNumActive(workerKey)).isEqualTo(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBorrow_blockedThread_remainsBlockedWhenInvalidatedAndShrunk() {
        Assume.assumeTrue(workerPool is WorkerPoolImpl)
        val workerKey: WorkerKey? = WorkerTestUtils.createWorkerKey(fileSystem, "mnem", false)
        val worker1: Worker = workerPool.borrowWorker(workerKey)
        val unused2: Worker? = workerPool.borrowWorker(workerKey)
        val blockedBorrowThread: TestThread =
            TestThread(
                TestRunnable {
                    val unused: Worker? = workerPool.borrowWorker(workerKey)
                })
        blockedBorrowThread.start()

        // There's no need to wait here as it doesn't matter whether #invalidateObject gets called
        // before or after the 3rd #borrowObject, the pool would not have the quota and borrowing will
        // still get blocked.
        worker1.getStatus().maybeUpdateStatus(Status.PENDING_KILL_DUE_TO_MEMORY_PRESSURE)
        workerPool.invalidateWorker(worker1)

        val e: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { blockedBorrowThread.joinAndAssertState(1000) })
        Truth.assertThat(e).hasCauseThat().hasMessageThat().contains("is still alive")
        assertThat(workerPool.getNumActive(workerKey)).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEvict_evictsIdleWorkers() {
        val workerKey: WorkerKey? = WorkerTestUtils.createWorkerKey(fileSystem, "mnem", false)
        val worker1: Worker = workerPool.borrowWorker(workerKey)
        val worker2: Worker = workerPool.borrowWorker(workerKey)
        workerPool.returnWorker(workerKey, worker1)
        workerPool.returnWorker(workerKey, worker2)
        val evicted: com.google.common.collect.ImmutableSet<Int?>? =
            workerPool.evictWorkers(com.google.common.collect.ImmutableSet.of<E?>(worker1.workerId, worker2.workerId))
        Truth.assertThat(evicted).containsExactly(worker1.workerId, worker2.workerId)
        assertThat(workerPool.getNumActive(workerKey)).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEvict_doesNotEvictActiveWorkers() {
        val workerKey: WorkerKey? = WorkerTestUtils.createWorkerKey(fileSystem, "mnem", false)
        val worker1: Worker = workerPool.borrowWorker(workerKey)
        val worker2: Worker = workerPool.borrowWorker(workerKey)
        workerPool.returnWorker(workerKey, worker1)
        val evicted: com.google.common.collect.ImmutableSet<Int?>? =
            workerPool.evictWorkers(com.google.common.collect.ImmutableSet.of<E?>(worker1.workerId, worker2.workerId))
        // Worker2 does not get evicted because it is still active.
        Truth.assertThat(evicted).containsExactly(worker1.workerId)
        assertThat(workerPool.getNumActive(workerKey)).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetIdleWorkers() {
        val workerKey: WorkerKey? = WorkerTestUtils.createWorkerKey(fileSystem, "mnem", false)
        val worker1: Worker = workerPool.borrowWorker(workerKey)
        val worker2: Worker = workerPool.borrowWorker(workerKey)

        assertThat(workerPool.idleWorkers).isEmpty()
        workerPool.returnWorker(workerKey, worker1)
        workerPool.returnWorker(workerKey, worker2)

        assertThat(workerPool.idleWorkers)
            .containsExactly(worker1.workerId, worker2.workerId)
        assertThat(workerPool.getNumActive(workerKey)).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testShrinkingPool_doesNotShrinkBelowOneWorker() {
        val workerKey: WorkerKey? = WorkerTestUtils.createWorkerKey(fileSystem, "mnem", false)
        assertThat(workerPool.getMaxTotalPerKey(workerKey)).isEqualTo(2)

        val worker1: Worker = workerPool.borrowWorker(workerKey)
        // Shrink the worker pool by 1.
        worker1.getStatus().maybeUpdateStatus(Status.PENDING_KILL_DUE_TO_MEMORY_PRESSURE)
        workerPool.returnWorker(workerKey, worker1)
        assertThat(workerPool.getMaxTotalPerKey(workerKey)).isEqualTo(1)

        val worker2: Worker = workerPool.borrowWorker(workerKey)
        // Attempt to shrink the pool again.
        worker2.getStatus().maybeUpdateStatus(Status.PENDING_KILL_DUE_TO_MEMORY_PRESSURE)
        workerPool.returnWorker(workerKey, worker2)
        // It should not be shrunk below 1.
        assertThat(workerPool.getMaxTotalPerKey(workerKey)).isEqualTo(1)
        assertThat(workerPool.getNumActive(workerKey)).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetNumActive() {
        val workerKey: WorkerKey? = WorkerTestUtils.createWorkerKey(fileSystem, "mnem", false)
        assertThat(workerPool.getNumActive(workerKey)).isEqualTo(0)
        val worker1: Worker? = workerPool.borrowWorker(workerKey)
        val worker2: Worker? = workerPool.borrowWorker(workerKey)
        assertThat(workerPool.getNumActive(workerKey)).isEqualTo(2)
        workerPool.returnWorker(workerKey, worker1)
        workerPool.returnWorker(workerKey, worker2)
        assertThat(workerPool.getNumActive(workerKey)).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReset_removesPreviouslyShrunkValues() {
        val workerKey: WorkerKey? = WorkerTestUtils.createWorkerKey(fileSystem, "mnem", false)
        assertThat(workerPool.getMaxTotalPerKey(workerKey)).isEqualTo(2)

        val worker1: Worker = workerPool.borrowWorker(workerKey)
        // Shrink the worker pool by 1.
        worker1.getStatus().maybeUpdateStatus(Status.PENDING_KILL_DUE_TO_MEMORY_PRESSURE)
        workerPool.returnWorker(workerKey, worker1)
        assertThat(workerPool.getMaxTotalPerKey(workerKey)).isEqualTo(1)

        workerPool.reset()
        assertThat(workerPool.getMaxTotalPerKey(workerKey)).isEqualTo(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testClose_destroysWorkers() {
        val workerKey: WorkerKey? = WorkerTestUtils.createWorkerKey(fileSystem, "mnem", false)
        val worker1: Worker? = workerPool.borrowWorker(workerKey)
        val worker2: Worker? = workerPool.borrowWorker(workerKey)
        workerPool.returnWorker(workerKey, worker1)
        workerPool.returnWorker(workerKey, worker2)
        workerPool.close()
        Mockito.verify<Any?>(factoryMock).destroyWorker(workerKey, worker1)
        Mockito.verify<Any?>(factoryMock).destroyWorker(workerKey, worker2)
    }

    companion object {
        val fileSystem: FileSystem =
            InMemoryFileSystem(com.google.devtools.build.lib.clock.BlazeClock.instance(), DigestHashFunction.SHA256)

        private val options: WorkerOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(WorkerOptions::class.java)
    }
}
