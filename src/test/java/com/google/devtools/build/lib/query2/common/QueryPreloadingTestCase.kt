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
package com.google.devtools.build.lib.query2.common

import com.google.devtools.build.lib.cmdline.Label

/** Base test class for query preloading tests.  */
abstract class QueryPreloadingTestCase : PackageLoadingTestCase() {
    protected var visitor: QueryTransitivePackagePreloader? = null
    protected var fs: CustomInMemoryFs =
        com.google.devtools.build.lib.query2.common.QueryPreloadingTestCase.CustomInMemoryFs(com.google.devtools.build.lib.testutil.ManualClock())

    protected override fun createFileSystem(): FileSystem {
        return fs
    }

    /**
     * Asserts all labels in expectedLabels are visited by walking
     * the dependency trees starting at startingLabels, and no other labels are visited.
     * 
     * 
     * Errors are expected.  We keep going after errors are encountered.
     */
    @Throws(java.lang.Exception::class)
    protected fun assertLabelsVisitedWithErrors(
        expectedLabels: MutableSet<String?>?, startingLabels: MutableSet<String?>?
    ) {
        assertLabelsVisited(expectedLabels, startingLabels, KEEP_GOING)
    }

    /**
     * Check that the expected targets were exactly those visited, and that the packages of these
     * expected targets were exactly those packages visited.
     */
    @Throws(java.lang.Exception::class)
    protected fun assertExpectedTargets(expectedLabels: MutableSet<String?>?, startingLabels: MutableSet<Label?>) {
        val visitedLabels: MutableSet<Label?> = getVisitedLabels(startingLabels, getSkyframeExecutor())
        Truth.assertThat(visitedLabels).containsExactlyElementsIn(asLabelSet(expectedLabels))
    }

    /**
     * Asserts all labels in expectedLabels are visited by walking
     * the dependency trees starting at startingLabels, and no other labels are visited.
     * 
     * @param expectedLabels The expected set of labels visited.
     * @param startingLabelStrings Visit the transitive closure of each of these labels.
     * @param keepGoing Whether the visitation continues after encountering
     * errors.
     */
    @Throws(java.lang.Exception::class)
    protected fun assertLabelsVisited(
        expectedLabels: MutableSet<String?>?,
        startingLabelStrings: MutableSet<String?>?,
        keepGoing: Boolean
    ) {
        val startingLabels: MutableSet<Label?> = asLabelSet(startingLabelStrings)

        // Spawn a lot of threads to help uncover concurrency issues
        visitor.preloadTransitiveTargets(
            reporter, startingLabels, keepGoing,  /*parallelThreads=*/200,  /*callerForError=*/null
        )

        assertExpectedTargets(expectedLabels, startingLabels)
    }

    /**
     * Asserts all labels in expectedLabels are visited by walking
     * the dependency trees starting at startingLabels, other labels may also be visited.
     * This is for cases where we don't care what the transitive closure of the labels is,
     * except for the labels we've specified must be within the closure.
     * 
     * @param expectedLabels The expected set of labels visited.
     * @param startingLabels Visit the transitive closure of each of these labels.
     * @param keepGoing Whether the visitation continues after encountering
     * errors.
     */
    @Throws(java.lang.Exception::class)
    protected fun assertLabelsAreSubsetOfLabelsVisited(
        expectedLabels: MutableSet<String?>?,
        startingLabels: MutableSet<String?>?,
        keepGoing: Boolean
    ) {
        val labels: MutableSet<Label?>? = asLabelSet(startingLabels)

        // Spawn a lot of threads to help uncover concurrency issues
        visitor.preloadTransitiveTargets(reporter, labels, keepGoing, 200,  /*callerForError=*/null)
        Truth.assertThat(getVisitedLabels(asLabelSet(startingLabels), skyframeExecutor))
            .containsAtLeastElementsIn(asLabelSet(expectedLabels))
    }

    @Throws(java.lang.InterruptedException::class, AbruptExitException::class)
    protected fun syncPackages(modifiedFileSet: ModifiedFileSet? = ModifiedFileSet.EVERYTHING_MODIFIED) {
        getSkyframeExecutor()
            .invalidateFilesUnderPathForTesting(
                reporter, modifiedFileSet, Root.fromPath(rootDirectory)
            )
    }

    @Before
    fun initializeVisitor() {
        setUpSkyframe(RuleVisibility.PRIVATE)
        this.visitor = skyframeExecutor.getQueryTransitivePackagePreloader()
    }

    protected class CustomInMemoryFs(manualClock: com.google.devtools.build.lib.testutil.ManualClock) :
        InMemoryFileSystem(manualClock, DigestHashFunction.SHA256) {
        private val stubbedStats: MutableMap<PathFragment?, FileStatus> = HashMap<PathFragment?, FileStatus>()

        @Throws(IOException::class)
        public override fun statIfFound(path: PathFragment, followSymlinks: Boolean): FileStatus {
            if (stubbedStats.containsKey(path)) {
                return stubbedStats.get(path)
            }
            return super.statIfFound(path, followSymlinks)
        }
    }

    companion object {
        // Convenience constant, so test args are readable vs true/false
        protected const val KEEP_GOING: Boolean = true

        /**
         * Returns the set of labels that were visited in the loading of the given starting labels.
         * Semantics are somewhat subtle in case of errors. The returned set always contains the starting
         * labels, even if they were not successfully loaded, but does not contain other unsuccessfully
         * loaded targets.
         */
        @Throws(java.lang.InterruptedException::class)
        fun getVisitedLabels(
            startingLabels: Iterable<Label?>, skyframeExecutor: SkyframeExecutor
        ): com.google.common.collect.ImmutableSet<Label?> {
            // Do an empty evaluation just to get access to the WalkableGraph.
            val graph: WalkableGraph =
                skyframeExecutor
                    .getEvaluator()
                    .evaluate(
                        com.google.common.collect.ImmutableSet.of<E?>(),
                        EvaluationContext.newBuilder()
                            .setParallelism(1)
                            .setEventHandler(NullEventHandler.INSTANCE)
                            .build()
                    )
                    .getWalkableGraph()
            val startingKeys: MutableList<SkyKey?> = java.util.ArrayList<SkyKey?>()
            for (label in startingLabels) {
                startingKeys.add(TransitiveTargetKey.of(label))
            }
            var nodesToVisit: Iterable<SkyKey?> = java.util.ArrayList<SkyKey?>(startingKeys)
            val visitedNodes: MutableSet<SkyKey?> = HashSet<SkyKey?>()
            while (!com.google.common.collect.Iterables.isEmpty(nodesToVisit)) {
                val existingNodes: MutableList<SkyKey?> = java.util.ArrayList<SkyKey?>()
                for (key in nodesToVisit) {
                    if (WalkableGraphUtils.exists(key, graph) && graph.getValue(key) != null && visitedNodes.add(key)) {
                        existingNodes.add(key)
                    }
                }
                nodesToVisit =
                    com.google.common.collect.Iterables.filter<T?>(
                        com.google.common.collect.Iterables.concat(graph.getDirectDeps(existingNodes).values()),
                        com.google.common.base.Predicate { skyKey: T? ->
                            skyKey.functionName().equals(TransitiveTargetKey.NAME)
                        })
            }
            visitedNodes.addAll(startingKeys)
            return com.google.common.collect.ImmutableSet.< Label > copyOf < Label ? > (
                    com.google.common.collect.Collections2.transform<SkyKey?, Any?>(
                        visitedNodes,
                        com.google.common.base.Function { skyKey: SkyKey? -> (skyKey as TransitiveTargetKey).getLabel() }))
        }
    }
}
