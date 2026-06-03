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
package com.google.devtools.build.skyframe

import com.google.devtools.build.lib.concurrent.ExecutorUtil

/** Base class for tests on [ProcessableGraph] implementations.  */
@RunWith(TestParameterInjector::class)
abstract class GraphTest {
    protected var graph: ProcessableGraph? = null
    protected var wrapper: TestRunnableWrapper? = null
    private val startingVersion: Version? = getStartingVersion()

    // This code should really be in a @Before method, but @Before methods are executed from the
    // top down, and this class's @Before method calls #getGraph, so makeGraph must have already
    // been called.
    @Throws(java.lang.Exception::class)
    protected abstract fun makeGraph()

    @Throws(java.lang.Exception::class)
    protected abstract fun getGraph(version: Version?): ProcessableGraph

    protected abstract fun getStartingVersion(): Version?

    protected abstract fun getNextVersion(version: Version?): Version?

    /**
     * Can be overridden to return `false` if the graph under test does not support
     * incrementality.
     */
    protected open fun shouldTestIncrementality(): Boolean {
        return true
    }

    protected enum class BatchMethod {
        NODE_BATCH {
            @Throws(java.lang.InterruptedException::class)
            override fun get(
                graph: ProcessableGraph,
                requestor: SkyKey?,
                reason: Reason?,
                keys: Iterable<out SkyKey?>?
            ): NodeBatch {
                return graph.getBatch(requestor, reason, keys)
            }
        },
        MAP {
            @Throws(java.lang.InterruptedException::class)
            override fun get(
                graph: ProcessableGraph,
                requestor: SkyKey?,
                reason: Reason?,
                keys: Iterable<out SkyKey?>?
            ): NodeBatch? {
                return graph.getBatchMap(requestor, reason, keys)::get
            }
        };

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(java.lang.InterruptedException::class)
        abstract fun get(
            graph: ProcessableGraph?,
            requestor: SkyKey?,
            reason: Reason?,
            keys: Iterable<out SkyKey?>?
        ): NodeBatch
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun init() {
        makeGraph()
        val startingVersion: Version? = getStartingVersion()
        this.graph = getGraph(startingVersion)
        this.wrapper = TestRunnableWrapper("GraphConcurrencyTest")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createIfAbsentBatch() {
        val cat: SkyKey = key("cat")
        val dog: SkyKey = key("dog")

        val batch: NodeBatch =
            graph.createIfAbsentBatch(null, Reason.OTHER, com.google.common.collect.ImmutableList.of<E?>(cat, dog))

        val catNode: NodeEntry? = graph.get(null, Reason.OTHER, cat)
        val dogNode: NodeEntry? = graph.get(null, Reason.OTHER, dog)
        assertThat(catNode).isNotNull()
        assertThat(dogNode).isNotNull()
        assertThat(batch.get(cat)).isSameInstanceAs(catNode)
        assertThat(batch.get(dog)).isSameInstanceAs(dogNode)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createIfAbsentBatch_interveningCallToRemove() {
        val key: SkyKey = key("key")
        val batch: NodeBatch =
            graph.createIfAbsentBatch(null, Reason.OTHER, com.google.common.collect.ImmutableList.of<E?>(key))
        graph.remove(key)
        assertThat(batch.get(key)).isNotNull()
    }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val batchAndGetBatchMapConsistency: Unit
        get() {
            val cat: SkyKey = key("cat")
            val dog: SkyKey = key("dog")
            val keys: com.google.common.collect.ImmutableList<SkyKey?> =
                com.google.common.collect.ImmutableList.of<SkyKey?>(cat, dog)
            graph.createIfAbsentBatch(null, Reason.OTHER, keys)

            val batch: NodeBatch = graph.getBatch(null, Reason.OTHER, keys)
            val batchMap: MutableMap<SkyKey?, out NodeEntry?> =
                graph.getBatchMap(null, Reason.OTHER, keys)

            val catEntry: NodeEntry? = batch.get(cat)
            val dogEntry: NodeEntry? = batch.get(dog)
            assertThat(catEntry).isNotNull()
            assertThat(dogEntry).isNotNull()
            Truth.assertThat(batchMap.get(cat)).isSameInstanceAs(catEntry)
            Truth.assertThat(batchMap.get(dog)).isSameInstanceAs(dogEntry)
            Truth.assertThat(batchMap).hasSize(2)
        }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createIfAbsentBatchConcurrentWithGet() {
        val numIters = 50
        val key: SkyKey = key("key")
        for (i in 0..<numIters) {
            val t: java.lang.Thread =
                java.lang.Thread(
                    wrapper.wrap(
                        java.lang.Runnable {
                            try {
                                graph.get(null, Reason.OTHER, key)
                            } catch (e: java.lang.InterruptedException) {
                                throw java.lang.IllegalStateException(e)
                            }
                        })
                )
            t.start()
            val batch: NodeBatch =
                graph.createIfAbsentBatch(null, Reason.OTHER, com.google.common.collect.ImmutableList.of<E?>(key))
            assertThat(batch.get(key)).isNotNull()
            graph.remove(key)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateIfAbsentBatchWithConcurrentGet() {
        val key: SkyKey = key("foo")
        val numThreads = 50
        val startThreads: CountDownLatch = CountDownLatch(1)
        val createRunnable: java.lang.Runnable =
            java.lang.Runnable {
                TrackingAwaiter.Companion.INSTANCE.awaitLatchAndTrackExceptions(
                    startThreads, "threads not started"
                )
                try {
                    graph.createIfAbsentBatch(null, Reason.OTHER, com.google.common.collect.ImmutableList.of<E?>(key))
                } catch (e: java.lang.InterruptedException) {
                    throw java.lang.IllegalStateException(e)
                }
            }
        val noCreateRunnable: java.lang.Runnable =
            java.lang.Runnable {
                TrackingAwaiter.Companion.INSTANCE.awaitLatchAndTrackExceptions(
                    startThreads, "threads not started"
                )
                try {
                    graph.get(null, Reason.OTHER, key)
                } catch (e: java.lang.InterruptedException) {
                    throw java.lang.IllegalStateException(e)
                }
            }
        val threads: MutableList<java.lang.Thread> = java.util.ArrayList<java.lang.Thread>(2 * numThreads)
        for (i in 0..<numThreads) {
            val createThread: java.lang.Thread = java.lang.Thread(createRunnable)
            createThread.start()
            threads.add(createThread)
            val noCreateThread: java.lang.Thread = java.lang.Thread(noCreateRunnable)
            noCreateThread.start()
            threads.add(noCreateThread)
        }
        startThreads.countDown()
        for (thread in threads) {
            thread.join()
        }
    }

    // Tests adding and removing Rdeps of a {@link NodeEntry} while a node transitions from
    // not done to done.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAddRemoveRdeps() {
        val key: SkyKey = key("foo")
        val entry: NodeEntry =
            graph.createIfAbsentBatch(null, Reason.OTHER, com.google.common.collect.ImmutableList.of<E?>(key)).get(key)
        // These numbers are arbitrary.
        val numThreads = 50
        val numKeys = numThreads
        // One chunk will be used to add and remove rdeps before setting the node value.  The second
        // chunk of work will have the node value set and the last chunk will be to add and remove
        // rdeps after the value has been set.
        val chunkSize = 40
        val numIterations = chunkSize * 2
        // This latch is used to signal that the runnables have been submitted to the executor.
        val waitForStart: CountDownLatch = CountDownLatch(1)
        // This latch is used to signal to the main thread that we have begun the second chunk
        // for sufficiently many keys.  The minimum of numThreads and numKeys is used to prevent
        // thread starvation from causing a delay here.
        val waitForAddedRdep: CountDownLatch = CountDownLatch(numThreads)
        // This latch is used to guarantee that we set the node's value before we enter the third
        // chunk for any key.
        val waitForSetValue: CountDownLatch = CountDownLatch(1)
        val pool: ExecutorService = Executors.newFixedThreadPool(numThreads)
        // Add single rdep before transition to done.
        assertThat(entry.addReverseDepAndCheckIfDone(key("rdep")))
            .isEqualTo(DependencyState.NEEDS_SCHEDULING)
        val rdepKeys: MutableList<SkyKey?> = java.util.ArrayList<SkyKey?>()
        for (i in 0..<numKeys) {
            rdepKeys.add(key("rdep" + i))
        }
        graph.createIfAbsentBatch(null, Reason.OTHER, rdepKeys)
        for (i in 0..<numKeys) {
            val j = i
            val r: java.lang.Runnable =
                java.lang.Runnable {
                    try {
                        waitForStart.await(
                            com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS
                        )
                        assertThat(entry.addReverseDepAndCheckIfDone(key("rdep" + j)))
                            .isNotEqualTo(DependencyState.DONE)
                        waitForAddedRdep.countDown()
                        waitForSetValue.await(
                            com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS
                        )
                        for (k in chunkSize..numIterations) {
                            if (shouldTestIncrementality()) {
                                entry.removeReverseDep(key("rdep" + j))
                            }
                            entry.addReverseDepAndCheckIfDone(key("rdep" + j))
                            if (shouldTestIncrementality()) {
                                entry.getReverseDepsForDoneEntry()
                            }
                        }
                    } catch (e: java.lang.InterruptedException) {
                        org.junit.Assert.fail("Test failed: " + e)
                    }
                }
            pool.execute(wrapper.wrap(r))
        }
        waitForStart.countDown()
        waitForAddedRdep.await(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        entry.markRebuilding()
        entry.setValue(com.google.devtools.build.skyframe.GraphTester.StringValue("foo1"), startingVersion, null)
        waitForSetValue.countDown()
        wrapper.waitForTasksAndMaybeThrow()
        assertThat(ExecutorUtil.interruptibleShutdown(pool)).isFalse()
        assertThat(
            graph.get(null, Reason.OTHER, key).getValue()
        ).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("foo1"))

        if (!shouldTestIncrementality()) {
            return
        }

        assertThat(graph.get(null, Reason.OTHER, key).getReverseDepsForDoneEntry())
            .hasSize(numKeys + 1)

        graph = getGraph(getNextVersion(startingVersion))
        val sameEntry: NodeEntry =
            com.google.common.base.Preconditions.checkNotNull<T>(graph.get(null, Reason.OTHER, key))
        // Mark the node as dirty again and check that the reverse deps have been preserved.
        sameEntry.markDirty(DirtyType.CHANGE)
        startEvaluation(sameEntry)
        sameEntry.markRebuilding()
        sameEntry.setValue(
            com.google.devtools.build.skyframe.GraphTester.StringValue("foo2"),
            getNextVersion(startingVersion),
            null
        )
        assertThat(
            graph.get(null, Reason.OTHER, key).getValue()
        ).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("foo2"))
        assertThat(graph.get(null, Reason.OTHER, key).getReverseDepsForDoneEntry())
            .hasSize(numKeys + 1)
    }

    // Tests adding inflight nodes with a given key while an existing node with the same key
    // undergoes a transition from not done to done.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAddingInflightNodes() {
        val numThreads = 50
        val pool: ExecutorService = Executors.newFixedThreadPool(numThreads)
        val numKeys = 500
        // Add each pair of keys 10 times.
        val nodeCreated: MutableSet<SkyKey?> = com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()
        val valuesSet: MutableSet<SkyKey?> = com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()
        for (i in 0..9) {
            for (j in 0..<numKeys) {
                for (k in j + 1..<numKeys) {
                    val keyNum1 = j
                    val keyNum2 = k
                    val key1: SkyKey = key("foo" + keyNum1)
                    val key2: SkyKey = key("foo" + keyNum2)
                    val keys: com.google.common.collect.ImmutableList<SkyKey?> =
                        com.google.common.collect.ImmutableList.of<SkyKey?>(key1, key2)
                    val r: java.lang.Runnable =
                        java.lang.Runnable {
                            for (key in keys) {
                                val entry: NodeEntry?
                                try {
                                    entry = graph.get(null, Reason.OTHER, key)
                                } catch (e: java.lang.InterruptedException) {
                                    throw java.lang.IllegalStateException(e)
                                }
                                if (entry == null) {
                                    nodeCreated.add(key)
                                }
                            }
                            val entries: NodeBatch
                            try {
                                entries = graph.createIfAbsentBatch(null, Reason.OTHER, keys)
                            } catch (e: java.lang.InterruptedException) {
                                throw java.lang.IllegalStateException(e)
                            }
                            for (keyNum in com.google.common.collect.ImmutableList.of<Int?>(keyNum1, keyNum2)) {
                                val key: SkyKey = key("foo" + keyNum)
                                val entry: NodeEntry = entries.get(key)
                                // {@code entry.addReverseDepAndCheckIfDone(null)} should return
                                // NEEDS_SCHEDULING at most once.
                                try {
                                    if (startEvaluation(entry).equals(DependencyState.NEEDS_SCHEDULING)) {
                                        entry.markRebuilding()
                                        Truth.assertThat(valuesSet.add(key)).isTrue()
                                        // Set to done.
                                        entry.setValue(
                                            com.google.devtools.build.skyframe.GraphTester.StringValue("bar" + keyNum),
                                            startingVersion,
                                            null
                                        )
                                        assertThat(entry.isDone()).isTrue()
                                    }
                                } catch (e: java.lang.InterruptedException) {
                                    throw java.lang.IllegalStateException(key.toString() + ", " + entry, e)
                                }
                            }
                            // This shouldn't cause any problems from the other threads.
                            try {
                                graph.createIfAbsentBatch(null, Reason.OTHER, keys)
                            } catch (e: java.lang.InterruptedException) {
                                throw java.lang.IllegalStateException(e)
                            }
                        }
                    pool.execute(wrapper.wrap(r))
                }
            }
        }
        wrapper.waitForTasksAndMaybeThrow()
        assertThat(ExecutorUtil.interruptibleShutdown(pool)).isFalse()
        // Check that all the values are as expected.
        for (i in 0..<numKeys) {
            val key: SkyKey = key("foo" + i)
            Truth.assertThat(nodeCreated).contains(key)
            Truth.assertThat(valuesSet).contains(key)
            assertThat(graph.get(null, Reason.OTHER, key).getValue())
                .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("bar" + i))
            assertThat(graph.get(null, Reason.OTHER, key).getVersion()).isEqualTo(startingVersion)
        }
    }

    /**
     * Initially calling [NodeEntry.setValue] and then making sure concurrent calls to [ ][QueryableGraph.get] and [QueryableGraph.getBatchMap] do not interfere with the node.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoneToDirty(@TestParameter batchMethod: BatchMethod) {
        Assume.assumeTrue(shouldTestIncrementality())
        val numKeys = 1000
        val numThreads = 50
        val numBatchRequests = 100
        // Create a bunch of done nodes.
        val keys: java.util.ArrayList<SkyKey?> = java.util.ArrayList<SkyKey?>()
        for (i in 0..<numKeys) {
            keys.add(key("foo" + i))
        }
        val entries: NodeBatch = graph.createIfAbsentBatch(null, Reason.OTHER, keys)
        for (i in 0..<numKeys) {
            val entry: NodeEntry = entries.get(key("foo" + i))
            startEvaluation(entry)
            entry.markRebuilding()
            entry.setValue(com.google.devtools.build.skyframe.GraphTester.StringValue("bar"), startingVersion, null)
        }

        assertThat(graph.get(null, Reason.OTHER, key("foo" + 0))).isNotNull()
        graph = getGraph(getNextVersion(startingVersion))
        assertThat(graph.get(null, Reason.OTHER, key("foo" + 0))).isNotNull()
        val pool1: ExecutorService = Executors.newFixedThreadPool(numThreads)
        val pool2: ExecutorService = Executors.newFixedThreadPool(numThreads)
        val pool3: ExecutorService = Executors.newFixedThreadPool(numThreads)

        // Only start all the threads once the batch requests are ready.
        val makeBatchCountDownLatch: CountDownLatch = CountDownLatch(numBatchRequests)
        // Do at least 5 single requests and batch requests before transitioning node.
        val getBatchCountDownLatch: CountDownLatch = CountDownLatch(5)
        val getCountDownLatch: CountDownLatch = CountDownLatch(5)

        val dep: SkyKey = key("dep")
        for (i in 0..<numKeys) {
            val keyNum = i
            // Transition the nodes from done to dirty and then back to done.
            val r1: java.lang.Runnable =
                java.lang.Runnable {
                    try {
                        makeBatchCountDownLatch.await()
                        getBatchCountDownLatch.await()
                        getCountDownLatch.await()
                    } catch (e: java.lang.InterruptedException) {
                        throw java.lang.AssertionError(e)
                    }
                    val entry: NodeEntry
                    try {
                        entry = graph.get(null, Reason.OTHER, key("foo" + keyNum))
                    } catch (e: java.lang.InterruptedException) {
                        throw java.lang.IllegalStateException(e)
                    }
                    try {
                        entry.markDirty(DirtyType.CHANGE)

                        // Make some changes, like adding a dep and rdep.
                        entry.addReverseDepAndCheckIfDone(key("rdep"))
                        entry.markRebuilding()
                        entry.addSingletonTemporaryDirectDep(dep)
                        val nextVersion: Version? = getNextVersion(startingVersion)
                        entry.signalDep(nextVersion, dep)

                        entry.setValue(
                            com.google.devtools.build.skyframe.GraphTester.StringValue("bar" + keyNum),
                            nextVersion,
                            null
                        )
                    } catch (e: java.lang.InterruptedException) {
                        throw java.lang.IllegalStateException(keyNum.toString() + ", " + entry, e)
                    }
                }

            // Start a bunch of get() calls while the node transitions from dirty to done and back.
            val r2: java.lang.Runnable =
                java.lang.Runnable {
                    try {
                        makeBatchCountDownLatch.await()
                    } catch (e: java.lang.InterruptedException) {
                        throw java.lang.AssertionError(e)
                    }
                    val entry: NodeEntry
                    try {
                        entry = graph.get(null, Reason.OTHER, key("foo" + keyNum))
                    } catch (e: java.lang.InterruptedException) {
                        throw java.lang.IllegalStateException(e)
                    }
                    assertThat(entry).isNotNull()
                    // Requests for the value are made at the same time that the version increments from
                    // the base. Check that there is no problem in requesting the version and that the
                    // number is sane.
                    assertThat(entry.getVersion())
                        .isAnyOf(startingVersion, getNextVersion(startingVersion))
                    getCountDownLatch.countDown()
                }
            pool1.execute(wrapper.wrap(r1))
            pool2.execute(wrapper.wrap(r2))
        }
        val r: Random = Random(com.google.devtools.build.lib.testutil.TestUtils.getRandomSeed().toLong())
        // Start a bunch of getBatch() calls while the node transitions from dirty to done and back.
        for (i in 0..<numBatchRequests) {
            val batch: MutableList<SkyKey?> = java.util.ArrayList<SkyKey?>(numKeys)
            // Pseudorandomly uniformly sample the powerset of the keys.
            for (j in 0..<numKeys) {
                if (r.nextBoolean()) {
                    batch.add(key("foo" + j))
                }
            }
            makeBatchCountDownLatch.countDown()
            val r3: java.lang.Runnable =
                java.lang.Runnable {
                    try {
                        makeBatchCountDownLatch.await()
                    } catch (e: java.lang.InterruptedException) {
                        throw java.lang.AssertionError(e)
                    }
                    val result: NodeBatch
                    try {
                        result = batchMethod.get(graph, null, Reason.OTHER, batch)
                    } catch (e: java.lang.InterruptedException) {
                        throw java.lang.IllegalStateException(e)
                    }
                    getBatchCountDownLatch.countDown()
                    for (key in batch) {
                        // Batch requests are made at the same time that the version increments from the
                        // base. Check that there is no problem in requesting the version and that the
                        // number is sane.
                        val nodeEntry: NodeEntry = result.get(key)
                        assertThat(nodeEntry).isNotNull()
                        assertThat(nodeEntry.getVersion())
                            .isAnyOf(startingVersion, getNextVersion(startingVersion))
                    }
                }
            pool3.execute(wrapper.wrap(r3))
        }
        wrapper.waitForTasksAndMaybeThrow()
        assertThat(ExecutorUtil.interruptibleShutdown(pool1)).isFalse()
        assertThat(ExecutorUtil.interruptibleShutdown(pool2)).isFalse()
        assertThat(ExecutorUtil.interruptibleShutdown(pool3)).isFalse()
        for (i in 0..<numKeys) {
            val entry: NodeEntry = graph.get(null, Reason.OTHER, key("foo" + i))
            assertThat(entry.getValue()).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("bar" + i))
            assertThat(entry.getVersion()).isEqualTo(getNextVersion(startingVersion))
            assertThat(entry.getReverseDepsForDoneEntry()).containsExactly(key("rdep"))
            assertThat(entry.getDirectDeps()).containsExactly(dep)
        }
    }

    companion object {
        protected fun key(name: String?): SkyKey {
            return GraphTester.Companion.skyKey(name)
        }

        @Throws(java.lang.InterruptedException::class)
        protected fun startEvaluation(entry: NodeEntry): DependencyState {
            return entry.addReverseDepAndCheckIfDone(null)
        }
    }
}
