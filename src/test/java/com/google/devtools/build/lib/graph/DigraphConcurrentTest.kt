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
package com.google.devtools.build.lib.graph

import com.google.common.base.Preconditions
import com.google.common.truth.Truth
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream

/**
 * Tests that DiGraph after concurrent access is in consistent state.
 * 
 * 
 * Inconsistent state means that any of edges is half added.
 */
@RunWith(JUnit4::class)
class DigraphConcurrentTest {
    /** Created 100_000 nodes randomly. Adds 10 outgoing and 10 incoming edges for every node.  */
    @Test
    @Throws(InterruptedException::class)
    fun testValidStateOfGraphAfterConcurrentAccess() {
        val digraph = Digraph<Int?>()
        val numberOfNodes = 100000

        val workerBoth10: Runnable = CreateNodeWorker(digraph, 10, 10, AtomicInteger())

        runWorkers(numberOfNodes, workerBoth10)
        Truth.assertThat(digraph.nodeCount).isEqualTo(numberOfNodes)

        assertGraphIsInConsistentState(digraph)
    }

    /**
     * Created 10_000 nodes randomly. Adds different number outgoing and incoming edges for every
     * node.
     */
    @Test
    @Throws(InterruptedException::class)
    fun testValidStateOfGraphAfterConcurrentAccessWithVariertyOfNodes() {
        val digraph = Digraph<Int?>()
        val numberOfNodes = 50000
        val idGenerator = AtomicInteger()

        val workerBoth3: Runnable = CreateNodeWorker(digraph, 3, 3, idGenerator)
        val workerBoth10: Runnable = CreateNodeWorker(digraph, 10, 10, idGenerator)
        val workerBoth100: Runnable = CreateNodeWorker(digraph, 100, 100, idGenerator)
        val workerOutgoing20: Runnable = CreateNodeWorker(digraph, 20, 0, idGenerator)
        val workerIncoming20: Runnable = CreateNodeWorker(digraph, 0, 20, idGenerator)

        runWorkers(
            numberOfNodes / 5,  // have already 5 workers
            workerBoth3,
            workerBoth10,
            workerBoth100,
            workerIncoming20,
            workerOutgoing20
        )

        Truth.assertThat(digraph.nodeCount).isEqualTo(numberOfNodes)
        assertGraphIsInConsistentState(digraph)
    }

    /**
     * Creates 20_000 nodes. Then iterate over node and for every node does: Creates 100 tasks: 50
     * tasks for adding 10 outgoing edges and 50 tasks for 10 incoming edges per each tasks. Then
     * executes all tasks in parallel in high contention. Only after all tasks for this node have
     * finished, switch to next node. this behaviour introduces very high contention around one single
     * node.
     */
    @Test
    @Throws(InterruptedException::class, ExecutionException::class, TimeoutException::class)
    fun testAddEdgeWithHighContention() {
        val numberOfNodes = 20000
        val edgePerNode = 1000

        val digraph = Digraph<Int?>()
        val workerZeroEdges = CreateNodeWorker(digraph, 0, 0, AtomicInteger())
        runWorkers(numberOfNodes, workerZeroEdges)
        Truth.assertThat(digraph.nodeCount).isEqualTo(numberOfNodes)

        val executorService = Executors.newFixedThreadPool(THREAD_COUNT)

        for (node in digraph.getNodes()) {
            // TODO(dbabkin): think about moving this interrupt callback logic to common library or make
            // research, may be one exists already in any open source project.

            val isTestInterrupted = AtomicBoolean(false)
            val interruptedCallBack =
                Runnable {
                    isTestInterrupted.set(true)
                    executorService.shutdownNow()
                }

            val countDownLatch = CountDownLatch(1)
            val futures: MutableList<Future<*>> = ArrayList<Future<*>>()
            // fork 100 tasks executed in parallel in high contention for one node:
            // 100 =  50 tasks for adding 10 outgoing edges and 50 tasks for adding 10 incoming edges
            for (i in 0..<edgePerNode / 20) {
                // add 10 outgoing edges
                val add10Outgoing: Runnable =
                    CreateEdgeNightContention(
                        digraph, countDownLatch, node!!.label!!, 10, true, interruptedCallBack
                    )
                futures.add(executorService.submit(add10Outgoing))

                // add 10 incoming edges
                val add10Incoming: Runnable =
                    CreateEdgeNightContention(
                        digraph, countDownLatch, node.label!!, 10, false, interruptedCallBack
                    )
                futures.add(executorService.submit(add10Incoming))
            }

            // red lights go out, race has begun. http://www.formula1-dictionary.net/start_sequence.html
            countDownLatch.countDown()

            // wait for every tasks completed before switch to the next node.
            for (future in futures) {
                if (isTestInterrupted.get()) {
                    break
                }
                future.get(1, TimeUnit.MINUTES)
            }
            if (isTestInterrupted.get()) {
                Assert.fail("Test had been interrupted.")
            }
        }

        assertGraphIsInConsistentState(digraph)
    }

    @Throws(InterruptedException::class)
    private fun runWorkers(count: Int, vararg workers: Runnable?) {
        val executorService = Executors.newFixedThreadPool(THREAD_COUNT)

        for (i in 0..<count) {
            Stream.of<Runnable?>(*workers).forEach { command: Runnable? -> executorService.execute(command) }
        }

        executorService.shutdown()
        while (!executorService.isTerminated()) {
            check(executorService.awaitTermination(1, TimeUnit.HOURS)) { "executor service termination wait time out" }
        }
    }

    /** Asserts that there are no half added edge in the graph.  */
    private fun assertGraphIsInConsistentState(digraph: Digraph<Int?>) {
        for (node in digraph.getNodes()) {
            assertNodeIsInConsistentState(node!!)
        }
    }

    private fun assertNodeIsInConsistentState(node: Node<Int?>) {
        for (succ in node.successors!!) {
            Truth.assertThat(succ!!.predecessors).contains(node)
        }

        for (pred in node.predecessors!!) {
            Truth.assertThat(pred!!.successors).contains(node)
        }
    }

    private class CreateNodeWorker(
        digraph: Digraph<Int?>?,
        outgoingEdgesPerNode: Int,
        incomingEdgesPerNode: Int,
        idGenerator: AtomicInteger
    ) : Runnable {
        private val digraph: Digraph<Int?>
        private val outgoingEdgesPerNode: Int
        private val incomingEdgesPerNode: Int
        private val idGenerator: AtomicInteger

        init {
            this.digraph = Objects.requireNonNull<Digraph<Int?>>(digraph)
            Preconditions.checkArgument(outgoingEdgesPerNode >= 0)
            this.outgoingEdgesPerNode = outgoingEdgesPerNode
            Preconditions.checkArgument(incomingEdgesPerNode >= 0)
            this.incomingEdgesPerNode = incomingEdgesPerNode
            this.idGenerator = idGenerator
        }

        override fun run() {
            val newNodeId = idGenerator.getAndIncrement()
            digraph.createNode(newNodeId)
            addEdgesFromNew(newNodeId)
            addEdgesToNew(newNodeId)
        }

        fun addEdgesFromNew(newNodeId: Int) {
            val count: Int = min(newNodeId, outgoingEdgesPerNode)
            addEdges(digraph, newNodeId, count,  /*outgoing*/true)
        }

        fun addEdgesToNew(newNodeId: Int) {
            val count: Int = min(newNodeId, incomingEdgesPerNode)
            addEdges(digraph, newNodeId, count,  /*outgoing*/false)
        }
    }

    private class CreateEdgeNightContention(
        digraph: Digraph<Int?>?,
        countDownLatch: CountDownLatch?,
        nodeId: Int,
        numberEdgeToAdd: Int,
        outgoing: Boolean,
        inerruptCallBack: Runnable?
    ) : Runnable {
        private val digraph: Digraph<Int?>
        private val countDownLatch: CountDownLatch
        private val nodeId: Int
        private val numberEdgeToAdd: Int
        private val outgoing: Boolean
        private val inerruptCallBack: Runnable

        init {
            this.digraph = Objects.requireNonNull<Digraph<Int?>>(digraph)
            this.countDownLatch = Objects.requireNonNull<CountDownLatch>(countDownLatch)
            Preconditions.checkArgument(nodeId >= 0)
            this.nodeId = nodeId
            Preconditions.checkArgument(numberEdgeToAdd >= 0)
            this.numberEdgeToAdd = numberEdgeToAdd
            this.outgoing = outgoing
            this.inerruptCallBack = Objects.requireNonNull<Runnable>(inerruptCallBack)
        }

        override fun run() {
            try {
                countDownLatch.await()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                inerruptCallBack.run()
                // hardly ever will see that in the log. Who cares?
                Assert.fail(e.message)
            }
            addEdges(digraph, nodeId, numberEdgeToAdd, outgoing)
        }
    }

    companion object {
        private val RANDOM = Random()

        // need to have contention.
        private val THREAD_COUNT = Runtime.getRuntime().availableProcessors() * 3

        private fun addEdges(digraph: Digraph<Int?>, nodeId: Int, count: Int, outgoing: Boolean) {
            for (i in 0..<count) {
                val id: Int = RANDOM.nextInt(digraph.nodeCount)
                if (outgoing) {
                    digraph.addEdge(nodeId, id)
                } else {
                    digraph.addEdge(id, nodeId)
                }
            }
        }
    }
}
