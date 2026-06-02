// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.actions.ExecutionRequirements.WorkerProtocolFormat

/** Tests for [ResourceManager].  */
@RunWith(JUnit4::class)
class ResourceManagerTest {
    private val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
    private val resourceOwner: ActionExecutionMetadata = ResourceOwnerStub()
    private val manager: ResourceManager = ResourceManager()
    private var worker: Worker? = null
    private var workerStatus: WorkerProcessStatus? = null
    private var counter: AtomicInteger? = null
    private var machineLoadProvider: MachineLoadProvider? = null
    var sync: CyclicBarrier? = null
    var sync2: CyclicBarrier? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun configureResourceManager() {
        manager.setAvailableResources(
            ResourceSet.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    ResourceSet.MEMORY, 1000.0, ResourceSet.CPU, 1.0, "gpu", 2.0, "fancyresource", 1.5
                ),  /* localTestCount= */
                2
            )
        )
        counter = AtomicInteger(0)
        sync = CyclicBarrier(2)
        sync2 = CyclicBarrier(2)
        manager.resetResourceUsage()
        worker = Mockito.mock<Worker>(Worker::class.java)
        machineLoadProvider = Mockito.mock<MachineLoadProvider>(MachineLoadProvider::class.java)
        workerStatus = spy(WorkerProcessStatus())
        Mockito.`when`<T?>(worker.getStatus()).thenReturn(workerStatus)
        manager.setWorkerPool(WorkerTestUtils.createTestWorkerPool(worker))
    }

    @Throws(java.lang.InterruptedException::class, IOException::class, ExecException::class)
    private fun acquire(ram: Double, cpu: Double, tests: Int, priority: ResourcePriority?): ResourceHandle {
        return manager.acquireResources(resourceOwner, ResourceSet.create(ram, cpu, tests), priority)
    }

    @Throws(java.lang.InterruptedException::class, IOException::class, ExecException::class)
    private fun acquire(ram: Double, cpu: Double, tests: Int): ResourceHandle {
        return acquire(ram, cpu, tests, ResourcePriority.LOCAL)
    }

    @Throws(java.lang.InterruptedException::class, IOException::class, ExecException::class)
    private fun acquire(ram: Double, cpu: Double, tests: Int, mnemonic: String?): ResourceHandle {
        return manager.acquireResources(
            resourceOwner,
            ResourceSet.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>(ResourceSet.MEMORY, ram, ResourceSet.CPU, cpu),
                tests,
                createWorkerKey(mnemonic)
            ),
            ResourcePriority.LOCAL
        )
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(
        java.lang.InterruptedException::class,
        IOException::class,
        java.util.NoSuchElementException::class,
        ExecException::class
    )
    private fun acquire(
        ram: Double,
        cpu: Double,
        extraResources: com.google.common.collect.ImmutableMap<String?, Double?>,
        tests: Int,
        priority: ResourcePriority?
    ): ResourceHandle {
        val resources: com.google.common.collect.ImmutableMap.Builder<String?, Double?> =
            com.google.common.collect.ImmutableMap.builder<String?, Double?>()
        resources.putAll(extraResources).put(ResourceSet.MEMORY, ram).put(ResourceSet.CPU, cpu)
        return manager.acquireResources(
            resourceOwner, ResourceSet.create(resources.buildOrThrow(), tests), priority
        )
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(
        java.lang.InterruptedException::class,
        IOException::class,
        java.util.NoSuchElementException::class,
        ExecException::class
    )
    private fun acquire(
        ram: Double, cpu: Double, extraResources: com.google.common.collect.ImmutableMap<String?, Double?>, tests: Int
    ): ResourceHandle {
        return acquire(ram, cpu, extraResources, tests, ResourcePriority.LOCAL)
    }

    @Throws(IOException::class, java.lang.InterruptedException::class, UserExecException::class)
    private fun release(resourceHandle: ResourceHandle) {
        manager.releaseResources(resourceHandle.getRequest(),  /* worker= */null)
    }

    private fun validate(count: Int) {
        Truth.assertThat(counter.incrementAndGet()).isEqualTo(count)
    }

    private fun createWorkerKey(mnemonic: String?): WorkerKey {
        return WorkerKey( /* args= */
            com.google.common.collect.ImmutableList.of<E?>(),  /* env= */
            com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* execRoot= */
            fs.getPath("/outputbase/execroot/workspace"),  /* mnemonic= */
            mnemonic,  /* workerFilesCombinedHash= */
            com.google.common.hash.HashCode.fromInt(0),  /* workerFilesWithDigests= */
            com.google.common.collect.ImmutableSortedMap.of<K?, V?>(),  /* sandboxed= */
            false,  /* useInMemoryTracking= */
            false,  /* multiplex= */
            false,  /* cancellable= */
            false,
            WorkerProtocolFormat.PROTO
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun allowOneActionOnResourceUnavailable_success() {
        assertThat(manager.inUse()).isFalse()

        // TODO: b/405364605 - Add a test for false case after we start throwing an exception.
        manager.setAllowOneActionOnResourceUnavailable(true)

        // When nothing is consuming RAM, then ResourceManager will successfully acquire an over-budget
        // request for RAM if allow_one_action_on_resource_unavailable is set to true.
        val bigRam = 10000.0
        val bigRamHandle: ResourceHandle = acquire(bigRam, 0.0, 0)
        // When RAM is consumed,
        // Then Resource Manager will be "in use":
        assertThat(manager.inUse()).isTrue()
        release(bigRamHandle)
        // When that RAM is released,
        // Then Resource Manager will not be "in use":
        assertThat(manager.inUse()).isFalse()

        // Ditto, for CPU:
        val bigCpu = 10.0
        val bigCpuHandle: ResourceHandle = acquire(0.0, bigCpu, 0)
        assertThat(manager.inUse()).isTrue()
        release(bigCpuHandle)
        assertThat(manager.inUse()).isFalse()

        // Ditto, for tests:
        val bigTests = 10
        val bigTestsHandle: ResourceHandle = acquire(0.0, 0.0, bigTests)
        assertThat(manager.inUse()).isTrue()
        release(bigTestsHandle)
        assertThat(manager.inUse()).isFalse()

        // Ditto, for extra resources:
        val bigExtraResources: com.google.common.collect.ImmutableMap<String?, Double?> =
            com.google.common.collect.ImmutableMap.of<String?, Double?>("gpu", 10.0, "fancyresource", 10.0)
        val bigGpuHandle: ResourceHandle = acquire(0.0, 0.0, bigExtraResources, 0)
        assertThat(manager.inUse()).isTrue()
        release(bigGpuHandle)
        assertThat(manager.inUse()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noAllowOneActionOnResourceUnavailable_exception() {
        assertThat(manager.inUse()).isFalse()

        // When nothing is consuming RAM, then ResourceManager should fail to acquire an over-budget
        // request for RAM, when allow_one_action_on_resource_unavailable is not explicitly set.
        val bigRam = 10000.0
        org.junit.Assert.assertThrows<T?>(
            UserExecException::class.java,
            org.junit.function.ThrowingRunnable { acquire(bigRam, 0.0, 0) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testThatCpuCanBeOverallocated() {
        assertThat(manager.inUse()).isFalse()

        // Given CPU is partially acquired:
        acquire(0.0, 0.5, 0)

        // When a request for CPU is made that would slightly overallocate CPU,
        // Then the request succeeds:
        val thread1: TestThread = TestThread(TestRunnable { assertThat(acquire(0.0, 0.6, 0)).isNotNull() })
        thread1.start()
        thread1.joinAndAssertState(10000)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testThatCpuAllocationIsNoncommutative() {
        assertThat(manager.inUse()).isFalse()

        // Given that CPU has a small initial allocation:
        val smallCpuHandle: ResourceHandle = acquire(0.0, 0.099, 0)

        // When a request for a large CPU allocation is made,
        // Then the request succeeds:
        val thread1: TestThread =
            TestThread(
                TestRunnable {
                    val handle: ResourceHandle = acquire(0.0, 0.99, 0)
                    // Cleanup
                    release(handle)
                })
        thread1.start()
        thread1.joinAndAssertState(10000)

        // Cleanup
        release(smallCpuHandle)
        assertThat(manager.inUse()).isFalse()

        // Given that CPU has a large initial allocation:
        acquire(0.0, 0.99, 0)

        // When a request for a small CPU allocation is made,
        // Then the request fails:
        val thread2: TestThread = TestThread(TestRunnable { acquire(0.0, 0.099, 0) })
        thread2.start()
        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { thread2.joinAndAssertState(1000) })
        Truth.assertThat(e).hasCauseThat().hasMessageThat().contains("is still alive")
        // Note that this behavior is surprising and probably not intended.
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testThatRamCannotBeOverallocated() {
        assertThat(manager.inUse()).isFalse()

        // Given RAM is partially acquired:
        acquire(500.0, 0.0, 0)

        // When a request for RAM is made that would slightly overallocate RAM,
        // Then the request fails (got timeout):
        val thread1: TestThread = TestThread(TestRunnable { acquire(600.0, 0.0, 0) })
        thread1.start()
        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { thread1.joinAndAssertState(1000) })
        Truth.assertThat(e).hasCauseThat().hasMessageThat().contains("is still alive")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testThatTestsCannotBeOverallocated() {
        assertThat(manager.inUse()).isFalse()

        // Given test count is partially acquired:
        acquire(0.0, 0.0, 1)

        // When a request for tests is made that would slightly overallocate tests,
        // Then the request fails:
        val thread1: TestThread = TestThread(TestRunnable { acquire(0.0, 0.0, 2) })
        thread1.start()
        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { thread1.joinAndAssertState(1000) })
        Truth.assertThat(e).hasCauseThat().hasMessageThat().contains("is still alive")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testThatExtraResourcesCannotBeOverallocated() {
        assertThat(manager.inUse()).isFalse()

        // Given a partially acquired extra resources:
        acquire(0.0, 0.0, com.google.common.collect.ImmutableMap.of<String?, Double?>("gpu", 1.0), 1)

        // When a request for extra resources is made that would overallocate,
        // Then the request fails:
        val thread1: TestThread = TestThread(TestRunnable {
            acquire(
                0.0,
                0.0,
                com.google.common.collect.ImmutableMap.of<String?, Double?>("gpu", 1.1),
                0
            )
        })
        thread1.start()
        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { thread1.joinAndAssertState(1000) })
        Truth.assertThat(e).hasCauseThat().hasMessageThat().contains("is still alive")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHasResources() {
        assertThat(manager.inUse()).isFalse()
        assertThat(manager.threadHasResources()).isFalse()
        val gpuHandle: ResourceHandle =
            acquire(1.0, 0.1, com.google.common.collect.ImmutableMap.of<String?, Double?>("gpu", 1.0), 1)
        assertThat(manager.threadHasResources()).isTrue()

        // We have resources in this thread - make sure other threads
        // are not affected.
        val thread1: TestThread =
            TestThread(
                TestRunnable {
                    var handle: ResourceHandle
                    assertThat(manager.threadHasResources()).isFalse()
                    handle = acquire(1.0, 0.0, 0)
                    assertThat(manager.threadHasResources()).isTrue()
                    release(handle)
                    assertThat(manager.threadHasResources()).isFalse()
                    handle = acquire(0.0, 0.1, 0)
                    assertThat(manager.threadHasResources()).isTrue()
                    release(handle)
                    assertThat(manager.threadHasResources()).isFalse()
                    handle = acquire(0.0, 0.0, 1)
                    assertThat(manager.threadHasResources()).isTrue()
                    release(handle)
                    assertThat(manager.threadHasResources()).isFalse()
                    handle =
                        acquire(0.0, 0.0, com.google.common.collect.ImmutableMap.of<String?, Double?>("gpu", 1.0), 0)
                    assertThat(manager.threadHasResources()).isTrue()
                    release(handle)
                    assertThat(manager.threadHasResources()).isFalse()
                })
        thread1.start()
        thread1.joinAndAssertState(10000)

        release(gpuHandle)
        assertThat(manager.threadHasResources()).isFalse()
        assertThat(manager.inUse()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConcurrentLargeRequests() {
        assertThat(manager.inUse()).isFalse()

        val requestedRam = 700.0
        val requestedCpu = 0.7

        val thread1: TestThread =
            TestThread(
                TestRunnable {
                    val handle1: ResourceHandle = acquire(requestedRam, requestedCpu, 0)
                    sync.await()
                    validate(1)
                    sync.await()
                    // Wait till other thread will be locked.
                    while (manager.getWaitCount() === 0) {
                        java.lang.Thread.yield()
                    }
                    release(handle1)
                    assertThat(manager.getWaitCount()).isEqualTo(0)
                    val handle2: ResourceHandle =
                        acquire(requestedRam, requestedCpu, 0) // Will be blocked by the thread2.
                    validate(3)
                    release(handle2)
                })
        val thread2: TestThread =
            TestThread(
                TestRunnable {
                    sync2.await()
                    Truth.assertThat(isAvailable(manager, requestedRam, requestedCpu, 0)).isFalse()
                    val handle: ResourceHandle =
                        acquire(requestedRam, requestedCpu, 0) // Will be blocked by the thread1.
                    validate(2)
                    sync2.await()
                    // Wait till other thread will be locked.
                    while (manager.getWaitCount() === 0) {
                        java.lang.Thread.yield()
                    }
                    release(handle)
                })

        thread1.start()
        thread2.start()
        sync.await(1, TimeUnit.SECONDS)
        assertThat(manager.inUse()).isTrue()
        assertThat(manager.getWaitCount()).isEqualTo(0)
        sync2.await(1, TimeUnit.SECONDS)
        sync.await(1, TimeUnit.SECONDS)
        sync2.await(1, TimeUnit.SECONDS)
        thread1.joinAndAssertState(1000)
        thread2.joinAndAssertState(1000)
        assertThat(manager.inUse()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInterruptedAcquisitionClearsResources() {
        assertThat(manager.inUse()).isFalse()
        // Acquire a small amount of resources so that future requests can block (the initial request
        // always succeeds even if it's for too much).
        val smallThread: TestThread = TestThread(TestRunnable { acquire(400.0, 0.0, 0) })
        smallThread.start()
        smallThread.joinAndAssertState(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
        val thread1: TestThread =
            TestThread(
                TestRunnable {
                    java.lang.Thread.currentThread().interrupt()
                    org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
                        java.lang.InterruptedException::class.java,
                        org.junit.function.ThrowingRunnable { acquire(700.0, 0.0, 0) })
                })
        thread1.start()
        thread1.joinAndAssertState(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
        // This should process the queue. If the request from above is still present, it will take all
        // the available memory. But it shouldn't.
        manager.setAvailableResources(
            ResourceSet.create( /* memoryMb= */2000,  /* cpu= */1,  /* localTestCount= */2)
        )
        val thread2: TestThread =
            TestThread(
                TestRunnable {
                    val handle: ResourceHandle = acquire(700.0, 0.0, 0)
                    release(handle)
                })
        thread2.start()
        thread2.joinAndAssertState(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutOfOrderAllocation() {
        val sync3: CyclicBarrier = CyclicBarrier(2)
        val sync4: CyclicBarrier = CyclicBarrier(2)

        assertThat(manager.inUse()).isFalse()

        val thread1: TestThread =
            TestThread(
                TestRunnable {
                    sync.await()
                    val handle: ResourceHandle = acquire(900.0, 0.5, 0) // Will be blocked by the main thread.
                    validate(5)
                    release(handle)
                    sync.await()
                })

        val thread2: TestThread =
            TestThread(
                TestRunnable {
                    // Wait till other thread will be locked
                    while (manager.getWaitCount() === 0) {
                        java.lang.Thread.yield()
                    }
                    var handle: ResourceHandle = acquire(100.0, 0.1, 0)
                    validate(2)
                    release(handle)
                    sync2.await()
                    handle = acquire(200.0, 0.5, 0)
                    validate(4)
                    sync2.await()
                    release(handle)
                })

        val thread3: TestThread =
            TestThread(
                TestRunnable {
                    val handle: ResourceHandle = acquire(100.0, 0.4, 0)
                    sync3.await()
                    sync3.await()
                    release(handle)
                })

        val thread4: TestThread =
            TestThread(
                TestRunnable {
                    val handle: ResourceHandle = acquire(750.0, 0.3, 0)
                    sync4.await()
                    sync4.await()
                    release(handle)
                })

        // Lock 900 MB, 0.9 CPU in total (spread over three threads so that we can individually release
        // parts of it).
        val handle: ResourceHandle = acquire(50.0, 0.2, 0)
        thread3.start()
        thread4.start()
        sync3.await(1, TimeUnit.SECONDS)
        sync4.await(1, TimeUnit.SECONDS)
        validate(1)

        // Start thread1, which will try to acquire 900 MB, 0.5 CPU, but can't, so it has to wait.
        thread1.start()
        sync.await(1, TimeUnit.SECONDS)

        // Start thread2, which will successfully acquire and release 100 MB, 0.1 CPU.
        thread2.start()
        // Signal thread2 to acquire 200 MB and 0.5 CPU, which will block.
        sync2.await(1, TimeUnit.SECONDS)

        // Waiting till both threads are locked.
        while (manager.getWaitCount() < 2) {
            java.lang.Thread.yield()
        }

        validate(3) // Thread1 is now first in the queue and Thread2 is second.

        // Release 100 MB, 0.4 CPU. This allows Thread2 to continue out of order.
        sync3.await(1, TimeUnit.SECONDS)
        sync2.await(1, TimeUnit.SECONDS)

        // Release 750 MB, 0.3 CPU. At this point thread1 will finally acquire resources.
        sync4.await(1, TimeUnit.SECONDS)
        sync.await(1, TimeUnit.SECONDS)

        // Release all remaining resources.
        release(handle)
        thread1.join()
        thread2.join()
        thread3.join()
        thread4.join()

        assertThat(manager.inUse()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRelease_highPriorityFirst() {
        assertThat(manager.inUse()).isFalse()

        val thread1: TestThread =
            TestThread(
                TestRunnable {
                    val handle: ResourceHandle = acquire(700.0, 0.0, 0)
                    sync.await()
                    sync2.await()
                    release(handle)
                })
        thread1.start()
        // Wait for thread1 to have acquired its RAM
        sync.await(1, TimeUnit.SECONDS)

        // Set up threads that compete for resources
        val syncDynamicStandalone: CyclicBarrier =
            startAcquireReleaseThread(ResourcePriority.DYNAMIC_STANDALONE)
        while (manager.getWaitCount() < 1) {
            java.lang.Thread.yield()
        }
        val syncDynamicWorker: CyclicBarrier = startAcquireReleaseThread(ResourcePriority.DYNAMIC_WORKER)
        while (manager.getWaitCount() < 2) {
            java.lang.Thread.yield()
        }
        val syncLocal: CyclicBarrier = startAcquireReleaseThread(ResourcePriority.LOCAL)
        while (manager.getWaitCount() < 3) {
            java.lang.Thread.yield()
        }

        sync2.await()

        while ((syncLocal.getNumberWaiting()
                    + syncDynamicWorker.getNumberWaiting()
                    + syncDynamicStandalone.getNumberWaiting())
            == 0
        ) {
            java.lang.Thread.yield()
        }
        assertThat(manager.getWaitCount()).isEqualTo(2)
        Truth.assertThat(syncLocal.getNumberWaiting()).isEqualTo(1)
        syncLocal.await(1, TimeUnit.SECONDS)

        while (syncDynamicWorker.getNumberWaiting() + syncDynamicStandalone.getNumberWaiting() == 0) {
            java.lang.Thread.yield()
        }
        Truth.assertThat(syncDynamicWorker.getNumberWaiting()).isEqualTo(1)
        assertThat(manager.getWaitCount()).isEqualTo(1)

        syncDynamicWorker.await(1, TimeUnit.SECONDS)
        while (syncDynamicStandalone.getNumberWaiting() == 0) {
            java.lang.Thread.yield()
        }
        Truth.assertThat(syncDynamicStandalone.getNumberWaiting()).isEqualTo(1)
        assertThat(manager.getWaitCount()).isEqualTo(0)
        syncDynamicStandalone.await(1, TimeUnit.SECONDS)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRelease_dynamicLifo() {
        assertThat(manager.inUse()).isFalse()

        val thread1: TestThread =
            TestThread(
                TestRunnable {
                    val handle: ResourceHandle = acquire(700.0, 0.0, 0)
                    sync.await()
                    sync2.await()
                    release(handle)
                })
        thread1.start()
        // Wait for thread1 to have acquired enough RAM to block the other threads.
        sync.await(1, TimeUnit.SECONDS)

        // Set up threads that compete for resources
        val syncDynamicStandalone1: CyclicBarrier =
            startAcquireReleaseThread(ResourcePriority.DYNAMIC_STANDALONE)
        while (manager.getWaitCount() < 1) {
            java.lang.Thread.yield()
        }
        val syncDynamicWorker1: CyclicBarrier =
            startAcquireReleaseThread(ResourcePriority.DYNAMIC_WORKER)
        while (manager.getWaitCount() < 2) {
            java.lang.Thread.yield()
        }
        val syncDynamicStandalone2: CyclicBarrier =
            startAcquireReleaseThread(ResourcePriority.DYNAMIC_STANDALONE)
        while (manager.getWaitCount() < 3) {
            java.lang.Thread.yield()
        }
        val syncDynamicWorker2: CyclicBarrier =
            startAcquireReleaseThread(ResourcePriority.DYNAMIC_WORKER)
        while (manager.getWaitCount() < 4) {
            java.lang.Thread.yield()
        }

        // Wewease the kwaken!
        sync2.await()

        while ((syncDynamicStandalone1.getNumberWaiting()
                    + syncDynamicStandalone2.getNumberWaiting()
                    + syncDynamicWorker1.getNumberWaiting()
                    + syncDynamicWorker2.getNumberWaiting())
            == 0
        ) {
            java.lang.Thread.yield()
        }
        assertThat(manager.getWaitCount()).isEqualTo(3)
        Truth.assertThat(syncDynamicWorker2.getNumberWaiting()).isEqualTo(1)
        syncDynamicWorker2.await(1, TimeUnit.SECONDS)

        while ((syncDynamicStandalone1.getNumberWaiting()
                    + syncDynamicStandalone2.getNumberWaiting()
                    + syncDynamicWorker1.getNumberWaiting())
            == 0
        ) {
            java.lang.Thread.yield()
        }
        assertThat(manager.getWaitCount()).isEqualTo(2)
        Truth.assertThat(syncDynamicWorker1.getNumberWaiting()).isEqualTo(1)
        syncDynamicWorker1.await(1, TimeUnit.SECONDS)

        while (syncDynamicStandalone1.getNumberWaiting() + syncDynamicStandalone2.getNumberWaiting()
            == 0
        ) {
            java.lang.Thread.yield()
        }
        assertThat(manager.getWaitCount()).isEqualTo(1)
        Truth.assertThat(syncDynamicStandalone2.getNumberWaiting()).isEqualTo(1)
        syncDynamicStandalone2.await(1, TimeUnit.SECONDS)

        while (syncDynamicStandalone1.getNumberWaiting() == 0) {
            java.lang.Thread.yield()
        }
        assertThat(manager.getWaitCount()).isEqualTo(0)
        Truth.assertThat(syncDynamicStandalone1.getNumberWaiting()).isEqualTo(1)
        syncDynamicStandalone1.await(1, TimeUnit.SECONDS)
    }

    private fun startAcquireReleaseThread(priority: ResourcePriority?): CyclicBarrier {
        val sync: CyclicBarrier = CyclicBarrier(2)
        val thread: TestThread =
            TestThread(
                TestRunnable {
                    val handle: ResourceHandle = acquire(700.0, 0.0, 0, priority)
                    sync.await()
                    release(handle)
                })
        thread.start()
        return sync
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonexistingResource() {
        // If we try to use nonexisting resource we should return an error
        val thread1: TestThread =
            TestThread(
                TestRunnable {
                    org.junit.Assert.assertThrows<T?>(
                        UserExecException::class.java,
                        org.junit.function.ThrowingRunnable {
                            acquire(
                                0.0,
                                0.0,
                                com.google.common.collect.ImmutableMap.of<String?, Double?>("nonexisting", 1.0),
                                0
                            )
                        })
                })
        thread1.start()
        thread1.joinAndAssertState(1000)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAcquireWithWorker_acquireAndRelease() {
        val memory = 100
        Mockito.`when`<T?>(worker.getWorkerKey()).thenReturn(createWorkerKey("dummy"))

        assertThat(manager.inUse()).isFalse()
        val handle: ResourceHandle = acquire(memory.toDouble(), 1.0, 0, "dummy")
        assertThat(manager.inUse()).isTrue()

        assertThat(handle.getWorker().getWorkerKey().getMnemonic()).isEqualTo("dummy")
        release(handle)
        // When that RAM is released,
        // Then Resource Manager will not be "in use":
        assertThat(manager.inUse()).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class, ExecException::class)
    fun testInvalidateAndClose() {
        var handle: ResourceHandle
        Mockito.verify<Any?>(workerStatus, Mockito.times(0)).maybeUpdateStatus(ArgumentMatchers.any<T?>())

        handle = acquire(0.0, 0.0, 0, "dummy")
        handle.invalidateAndClose(java.lang.InterruptedException())
        Mockito.verify<Any?>(workerStatus).maybeUpdateStatus(Status.PENDING_KILL_DUE_TO_INTERRUPTED_EXCEPTION)

        handle = acquire(0.0, 0.0, 0, "dummy")
        handle.invalidateAndClose(IOException())
        Mockito.verify<Any?>(workerStatus).maybeUpdateStatus(Status.PENDING_KILL_DUE_TO_IO_EXCEPTION)

        handle = acquire(0.0, 0.0, 0, "dummy")
        handle.invalidateAndClose(
            UserExecException(
                FailureDetail.newBuilder()
                    .setWorker(FailureDetails.Worker.newBuilder().setCode(Code.NO_RESPONSE))
                    .build()
            )
        )
        Mockito.verify<Any?>(workerStatus)
            .maybeUpdateStatus(Status.PENDING_KILL_DUE_TO_USER_EXEC_EXCEPTION, Code.NO_RESPONSE)

        handle = acquire(0.0, 0.0, 0, "dummy")
        handle.invalidateAndClose(null)
        Mockito.verify<Any?>(workerStatus).maybeUpdateStatus(Status.PENDING_KILL_DUE_TO_UNKNOWN)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCPULoadScheduling_cantAcquireWhileWindowFull() {
        manager.initializeCpuLoadFunctionality(machineLoadProvider, true, java.time.Duration.ofSeconds(5))
        // Acquire 1 CPU
        acquire(0.0, 1.0, 0)
        // Set load only for 0.1 CPU
        Mockito.`when`<T?>(machineLoadProvider.getCurrentCpuUsage()).thenReturn(0.1)
        val thread: TestThread =
            TestThread(
                TestRunnable {
                    val handle: ResourceHandle = acquire(0.0, 1.0, 0)
                    release(handle)
                })

        thread.start()

        // Can't allocate because window contains estimation for the first action
        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { thread.joinAndAssertState(1000) })
        Truth.assertThat(e).hasCauseThat().hasMessageThat().contains("is still alive")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCPULoadScheduling_cantAcquireWhileCpuLoaded() {
        manager.initializeCpuLoadFunctionality(machineLoadProvider, true, java.time.Duration.ofSeconds(5))
        // Acquire 1 CPU
        acquire(0.0, 1.0, 0)
        Mockito.`when`<T?>(machineLoadProvider.getCurrentCpuUsage()).thenReturn(0.9)
        val thread: TestThread =
            TestThread(
                TestRunnable {
                    val handle: ResourceHandle = acquire(0.0, 1.0, 0)
                    release(handle)
                })
        // clean the window
        manager.windowUpdate()

        thread.start()

        // Can't allocate because cpu load is too high.
        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { thread.joinAndAssertState(1000) })
        Truth.assertThat(e).hasCauseThat().hasMessageThat().contains("is still alive")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCPULoadScheduling_success() {
        manager.initializeCpuLoadFunctionality(machineLoadProvider, true, java.time.Duration.ofSeconds(5))
        // Acquire 1 CPU
        acquire(0.0, 1.0, 0)
        // Set load only for 0.1 CPU
        Mockito.`when`<T?>(machineLoadProvider.getCurrentCpuUsage()).thenReturn(0.1)
        val thread: TestThread =
            TestThread(
                TestRunnable {
                    val handle: ResourceHandle = acquire(0.0, 1.0, 0)
                    release(handle)
                })
        manager.windowUpdate()

        thread.start()

        thread.joinAndAssertState(10000)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCPULoadScheduling_cantAcquireX3Cpu() {
        manager.initializeCpuLoadFunctionality(machineLoadProvider, true, java.time.Duration.ofSeconds(5))
        // Set load only for 0.1 CPU
        Mockito.`when`<T?>(machineLoadProvider.getCurrentCpuUsage()).thenReturn(0.1)
        for (i in 0..2) {
            val latch: CountDownLatch = CountDownLatch(1)
            val thread: TestThread =
                TestThread(
                    TestRunnable {
                        acquire(0.0, 1.0, 0)
                        latch.countDown()
                    })
            thread.start()
            latch.await()
            manager.windowUpdate()
        }
        val thread4: TestThread =
            TestThread(
                TestRunnable {
                    val handle: ResourceHandle = acquire(0.0, 1.0, 0)
                    release(handle)
                })

        thread4.start()

        // Can't allocate because there is a hard limit x3 total CPU number.
        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { thread4.joinAndAssertState(1000) })
        Truth.assertThat(e).hasCauseThat().hasMessageThat().contains("is still alive")
    }

    @kotlin.jvm.Synchronized
    @Throws(UserExecException::class)
    fun isAvailable(rm: ResourceManager, ram: Double, cpu: Double, localTestCount: Int): Boolean {
        return rm.areResourcesAvailable(ResourceSet.create(ram, cpu, localTestCount))
    }

    private class ResourceOwnerStub : ActionExecutionMetadata {
        public override fun getProgressMessage(): String? {
            throw java.lang.IllegalStateException()
        }

        public override fun getOwner(): ActionOwner? {
            throw java.lang.IllegalStateException()
        }

        public override fun isShareable(): Boolean {
            throw java.lang.IllegalStateException()
        }

        public override fun prettyPrint(): String? {
            throw java.lang.IllegalStateException()
        }

        public override fun getMnemonic(): String? {
            throw java.lang.IllegalStateException()
        }

        public override fun inputsKnown(): Boolean {
            throw java.lang.IllegalStateException()
        }

        public override fun discoversInputs(): Boolean {
            throw java.lang.IllegalStateException()
        }

        public override fun getTools(): NestedSet<Artifact?>? {
            throw java.lang.IllegalStateException()
        }

        public override fun getInputs(): NestedSet<Artifact?>? {
            throw java.lang.IllegalStateException()
        }

        public override fun getOriginalInputs(): NestedSet<Artifact?>? {
            throw java.lang.IllegalStateException()
        }

        public override fun getSchedulingDependencies(): NestedSet<Artifact?>? {
            throw java.lang.IllegalStateException()
        }

        public override fun getClientEnvironmentVariables(): MutableCollection<String?>? {
            throw java.lang.IllegalStateException()
        }

        public override fun getOutputs(): com.google.common.collect.ImmutableSet<Artifact?>? {
            throw java.lang.IllegalStateException()
        }

        public override fun getPrimaryInput(): Artifact? {
            throw java.lang.IllegalStateException()
        }

        public override fun getPrimaryOutput(): Artifact? {
            throw java.lang.IllegalStateException()
        }

        public override fun getMandatoryInputs(): NestedSet<Artifact?>? {
            throw java.lang.IllegalStateException()
        }

        public override fun getInputFilesForExtraAction(
            actionExecutionContext: ActionExecutionContext?
        ): NestedSet<Artifact?> {
            return NestedSetBuilder.emptySet(Order.STABLE_ORDER)
        }

        public override fun getKey(
            actionKeyContext: ActionKeyContext?, inputMetadataProvider: InputMetadataProvider?
        ): String? {
            throw java.lang.IllegalStateException()
        }

        public override fun describeKey(): String? {
            throw java.lang.IllegalStateException()
        }

        public override fun describe(): String {
            return "ResourceOwnerStubAction"
        }

        public override fun getMandatoryOutputs(): com.google.common.collect.ImmutableSet<Artifact?> {
            return com.google.common.collect.ImmutableSet.of<Artifact?>()
        }

        public override fun getExecProperties(): com.google.common.collect.ImmutableMap<String?, String?>? {
            throw java.lang.IllegalStateException()
        }

        public override fun getExecutionPlatform(): PlatformInfo? {
            throw java.lang.IllegalStateException()
        }
    }
}
