// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization.analysis

import com.google.devtools.build.lib.skyframe.FileOpNodeOrFuture.EmptyFileOpNode.EMPTY_FILE_OP_NODE

@RunWith(JUnit4::class)
class FileOpNodeMemoizingLookupTest : BuildIntegrationTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fileOpNodes_areConsistent() {
        // This test case contains a glob to exercise DirectoryListingKey.
        write("hello/x.txt", "x")
        write(
            "hello/BUILD",
            """
        genrule(
            name = "target",
            srcs = glob(["*.txt"]),
            outs = ["out"],
            cmd = "cat ${'$'}(SRCS) > ${'$'}@",
        )
        
        """.trimIndent()
        )

        buildTarget("//hello:target")

        val graph: InMemoryGraph = skyframeExecutor.getEvaluator().getInMemoryGraph()

        val pool: ForkJoinPool = ForkJoinPool(CONCURRENCY)

        val fileOpDataMap: FileOpNodeMemoizingLookup = FileOpNodeMemoizingLookup(pool, graph)

        val actionLookups: java.util.ArrayList<ActionLookupKey> = java.util.ArrayList<ActionLookupKey>()
        val actions: java.util.ArrayList<ActionLookupData> = java.util.ArrayList<ActionLookupData>()

        for (key in graph.doneValues.keySet()) {
            if (key is ActionLookupKey) {
                actionLookups.add(key)
            }
            if (key is ActionLookupData) {
                actions.add(key)
            }
        }

        val futures: ConcurrentLinkedQueue<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?> =
            ConcurrentLinkedQueue<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>()
        val allAdded: CountDownLatch = CountDownLatch(actionLookups.size() + actions.size())

        for (lookupKey in actionLookups) {
            pool.execute(
                java.lang.Runnable {
                    futures.add(verifyFileOpNodeForActionLookupKey(graph, fileOpDataMap, lookupKey))
                    allAdded.countDown()
                })
        }
        for (lookupData in actions) {
            pool.execute(
                java.lang.Runnable {
                    futures.add(verifyFileOpNodeForActionLookupData(graph, fileOpDataMap, lookupData))
                    allAdded.countDown()
                })
        }

        allAdded.await()
        // Should not raise any exceptions.
        val unused: Any? = com.google.common.util.concurrent.Futures.whenAllSucceed<java.lang.Void?>(futures)
            .call<Any?>(
                java.util.concurrent.Callable { null },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            ).get()
    }

    companion object {
        // TODO: b/364831651 - consider adding test cases covering other scenarios, like symlinks.
        private const val CONCURRENCY = 4

        private fun verifyFileOpNodeForActionLookupKey(
            graph: InMemoryGraph, fileOpDataMap: FileOpNodeMemoizingLookup, lookupKey: ActionLookupKey
        ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>? {
            // For action lookup values, verifies that the file dependencies are an exact match for the ones
            // in the transitive closure.
            val verify: com.google.common.base.Function<FileOpNodeOrEmpty?, java.lang.Void?> =
                com.google.common.base.Function { node: FileOpNodeOrEmpty? ->
                    val nodes: HashSet<FileOpNode?> = HashSet<FileOpNode?>()
                    val sources: HashSet<FileKey?> = HashSet<FileKey?>()
                    flattenNodeOrEmpty(node, nodes, sources, HashSet<FileOpNode?>())
                    Truth.assertWithMessage("for key=%s", lookupKey)
                        .that(nodes)
                        .isEqualTo(collectTransitiveFileOpNodes(graph, lookupKey))
                    null
                }
            when (fileOpDataMap.computeNode(lookupKey)) {
                -> {
                    val unusedNull: java.lang.Void? = verify.apply(nodeOrEmpty)
                    return com.google.common.util.concurrent.Futures.immediateVoidFuture()
                }

                -> return com.google.common.util.concurrent.Futures.transform<FileOpNodeOrEmpty?, java.lang.Void?>(
                    future,
                    verify,
                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                )
            }
        }

        private fun verifyFileOpNodeForActionLookupData(
            graph: InMemoryGraph, fileOpDataMap: FileOpNodeMemoizingLookup, lookupData: ActionLookupData
        ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>? {
            // For actions, verifies that the union of the files and sources of the file op data of the
            // action's owner is a superset of the file dependencies of the action. There's a small
            // overapproximation here.
            val verify: com.google.common.base.Function<FileOpNodeOrEmpty?, java.lang.Void?> =
                com.google.common.base.Function { node: FileOpNodeOrEmpty? ->
                    val nodes: HashSet<FileOpNode?> = HashSet<FileOpNode?>()
                    val sources: HashSet<FileKey?> = HashSet<FileKey?>()
                    flattenNodeOrEmpty(node, nodes, sources, HashSet<FileOpNode?>())
                    val realFileDeps: com.google.common.collect.ImmutableSet<FileOpNode?> =
                        collectTransitiveFileOpNodes(graph, lookupData)

                    val assertBuilder: StandardSubjectBuilder = Truth.assertWithMessage("for key=%s", lookupData)
                    assertBuilder.that(nodes).containsNoneIn(sources) // Sources are distinct from nodes.
                    // All sources are contained in the real file deps.
                    assertBuilder.that(realFileDeps).containsAtLeastElementsIn(sources)

                    nodes.addAll(sources)
                    // Sources may be an overapproximation by design. In this particular case, it happens to
                    // be an exact match, but that could conceivably change with code changes.
                    assertBuilder.that(nodes).containsAtLeastElementsIn(realFileDeps)
                    null
                }
            // Note, that this looks up the incrementality data for the action by its ActionLookupKey.
            when (fileOpDataMap.computeNode(lookupData.getActionLookupKey())) {
                -> {
                    val unusedNull: java.lang.Void? = verify.apply(nodeOrEmpty)
                    return com.google.common.util.concurrent.Futures.immediateVoidFuture()
                }

                -> return com.google.common.util.concurrent.Futures.transform<FileOpNodeOrEmpty?, java.lang.Void?>(
                    future,
                    verify,
                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                )
            }
        }

        /**
         * Flattens the given node or empty node into the given sets of nodes and sources.
         * 
         * 
         * The given sets are modified in place.
         */
        private fun flattenNodeOrEmpty(
            maybeNode: FileOpNodeOrEmpty,
            nodes: MutableSet<FileOpNode?>,
            sources: MutableSet<FileKey?>,
            visited: MutableSet<FileOpNode?>
        ) {
            when (maybeNode) {
                EMPTY_FILE_OP_NODE -> return
                -> {
                    flattenNode(node, nodes, sources, visited)
                    return
                }
            }
        }

        private fun flattenNode(
            node: FileOpNode,
            nodes: MutableSet<FileOpNode?>,
            sources: MutableSet<FileKey?>,
            visited: MutableSet<FileOpNode?>
        ) {
            if (!visited.add(node)) {
                return
            }
            when (node) {
                -> nodes.add(file)
                -> nodes.add(directory)
                -> {
                    val i = 0
                    while (i < nested.analysisDependenciesCount()) {
                        flattenNode(nested.getAnalysisDependency(i), nodes, sources, visited)
                        i++
                    }
                }

                -> {
                    val i = 0
                    while (i < withSources.analysisDependenciesCount()) {
                        flattenNode(withSources.getAnalysisDependency(i), nodes, sources, visited)
                        i++
                    }
                    sources.add(withSources.source())
                }
            }
        }

        private fun collectTransitiveFileOpNodes(
            graph: InMemoryGraph, key: SkyKey?
        ): com.google.common.collect.ImmutableSet<FileOpNode?> {
            val visited: HashSet<SkyKey?> = HashSet<SkyKey?>()
            val nodes: HashSet<FileOpNode?> = HashSet<FileOpNode?>()
            collectTransitiveFileOpNodes(graph, key, visited, nodes)
            return com.google.common.collect.ImmutableSet.copyOf<FileOpNode?>(nodes)
        }

        private fun collectTransitiveFileOpNodes(
            graph: InMemoryGraph, key: SkyKey?, visited: MutableSet<SkyKey?>, nodes: MutableSet<FileOpNode?>
        ) {
            if (!visited.add(key)) {
                return
            }
            if (key is FileOpNode) {
                // The FileOpNodeMemoizingLookup doesn't recurse beyond FileKey or DirectoryListingKeys. The
                // inner details
                // of those entries are handled by FileDependencySerializer.
                nodes.add(key)
                return
            }
            for (dep in graph.getIfPresent(key).directDeps) {
                collectTransitiveFileOpNodes(graph, dep, visited, nodes)
            }
        }
    }
}
