// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.skyframe.RecursivePackageProviderBackedTargetPatternResolver.MAX_PACKAGES_BULK_GET

/** Looks up values under [TraversalInfo]s of given roots in a [WalkableGraph].  */
class TraversalInfoRootPackageExtractor : RootPackageExtractor {
    @Throws(java.lang.InterruptedException::class)
    public override fun streamPackagesFromRoots(
        results: SafeBatchCallback<PackageIdentifier?>?,
        graph: WalkableGraph,
        roots: MutableList<Root?>,
        eventHandler: ExtendedEventHandler,
        repository: RepositoryName?,
        directory: PathFragment,
        forbiddenSubdirectories: IgnoredSubdirectories,
        excludedSubdirectories: com.google.common.collect.ImmutableSet<PathFragment?>
    ) {
        val dirsToCheckForPackages: TreeSet<TraversalInfo?> = TreeSet<TraversalInfo?>(TRAVERSAL_INFO_COMPARATOR)
        for (root in roots) {
            val rootedDir: RootedPath = RootedPath.toRootedPath(root, directory)
            dirsToCheckForPackages.add(
                TraversalInfo(rootedDir, forbiddenSubdirectories, excludedSubdirectories)
            )
        }
        val visitor =
            PackageCollectingParallelVisitor(
                results,  /*visitBatchSize=*/
                MAX_PACKAGES_BULK_GET,  /*processResultsBatchSize=*/
                PACKAGE_ID_OUTPUT_BATCH_SIZE,  /*minPendingTasks=*/
                3 * DEFAULT_THREAD_COUNT,  /*resultBatchSize=*/
                PACKAGE_ID_OUTPUT_BATCH_SIZE,
                eventHandler,
                repository,
                graph
            )
        visitor.visitAndWaitForCompletion(dirsToCheckForPackages)
    }

    /**
     * A ParallelVisitor that reports every [PackageIdentifier] by querying the WalkableGraph
     * for a [CollectPackagesUnderDirectoryValue] for each [TraversalInfo] it visits.
     */
    internal class PackageCollectingParallelVisitor
        (
        callback: SafeBatchCallback<PackageIdentifier?>?,
        visitBatchSize: Int,
        processResultsBatchSize: Int,
        minPendingTasks: Int,
        resultBatchSize: Int,
        eventHandler: ExtendedEventHandler,
        repository: RepositoryName?,
        graph: WalkableGraph
    ) : ParallelVisitor<TraversalInfo?, TraversalInfo?, TraversalInfo?, PackageIdentifier?, MarkerRuntimeException?, SafeBatchCallback<PackageIdentifier?>?>(
        callback,
        MarkerRuntimeException::class.java,
        visitBatchSize,
        processResultsBatchSize,
        minPendingTasks,
        resultBatchSize,
        PACKAGE_ID_COLLECTING_EXECUTOR,
        VisitTaskStatusCallback.NULL_INSTANCE
    ) {
        private val eventHandler: ExtendedEventHandler
        private val repository: RepositoryName?
        private val graph: WalkableGraph

        init {
            this.eventHandler = eventHandler
            this.repository = repository
            this.graph = graph
        }

        protected override fun outputKeysToOutputValues(
            targetKeys: Iterable<TraversalInfo>
        ): Iterable<PackageIdentifier?> {
            val results: com.google.common.collect.ImmutableList.Builder<PackageIdentifier?> =
                com.google.common.collect.ImmutableList.builderWithExpectedSize<PackageIdentifier?>(resultBatchSize)
            for (resultInfo in targetKeys) {
                results.add(
                    PackageIdentifier.create(repository, resultInfo.rootedDir.getRootRelativePath())
                )
            }
            return results.build()
        }

        @Throws(java.lang.InterruptedException::class)
        protected override fun getVisitResult(dirsToCheckForPackages: Iterable<TraversalInfo?>): Visit? {
            val traversalToKeyMapBuilder: com.google.common.collect.ImmutableMap.Builder<TraversalInfo?, SkyKey?> =
                com.google.common.collect.ImmutableMap.builder<TraversalInfo?, SkyKey?>()
            for (traversalInfo in dirsToCheckForPackages) {
                traversalToKeyMapBuilder.put(
                    traversalInfo,
                    CollectPackagesUnderDirectoryValue.key(
                        repository, traversalInfo!!.rootedDir, traversalInfo.forbiddenSubdirectories
                    )
                )
            }
            val traversalToKeyMap: com.google.common.collect.ImmutableMap<TraversalInfo?, SkyKey?> =
                traversalToKeyMapBuilder.buildOrThrow()
            val values: MutableMap<SkyKey?, SkyValue?> = graph.getSuccessfulValues(traversalToKeyMap.values)

            // NOTE: Use a TreeSet to ensure a deterministic (sorted) iteration order when we recurse.
            val resultPackageIds: MutableList<TraversalInfo?> = java.util.ArrayList<TraversalInfo?>()
            val subdirsToCheckForPackages: TreeSet<TraversalInfo?> = TreeSet<TraversalInfo?>(TRAVERSAL_INFO_COMPARATOR)
            for (entry in traversalToKeyMap.entries) {
                val info: TraversalInfo = entry.key
                val key: SkyKey? = entry.value
                val `val`: SkyValue? = values.get(key)
                val collectPackagesValue: CollectPackagesUnderDirectoryValue? =
                    `val` as CollectPackagesUnderDirectoryValue?
                if (collectPackagesValue != null) {
                    if (collectPackagesValue.isDirectoryPackage) {
                        resultPackageIds.add(info)
                    }

                    if (collectPackagesValue.errorMessage != null) {
                        eventHandler.handle(Event.error(collectPackagesValue.errorMessage))
                    }

                    val subdirectoryRootedPaths: com.google.common.collect.ImmutableList<RootedPath> =
                        collectPackagesValue.getSubdirectoryTransitivelyContainsPackagesOrErrors()
                    for (subdirectory in subdirectoryRootedPaths) {
                        val subdirectoryRelativePath: PathFragment = subdirectory.getRootRelativePath()
                        val forbiddenSubdirectoriesBeneathThisSubdirectory: IgnoredSubdirectories =
                            info.forbiddenSubdirectories.filterForDirectory(subdirectoryRelativePath)
                        val excludedSubdirectoriesBeneathThisSubdirectory: com.google.common.collect.ImmutableSet<PathFragment?> =
                            info.excludedSubdirectories.stream()
                                .filter { pathFragment: PathFragment? ->
                                    pathFragment.startsWith(
                                        subdirectoryRelativePath
                                    )
                                }
                                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<PathFragment?>())
                        if (!excludedSubdirectoriesBeneathThisSubdirectory.contains(subdirectoryRelativePath)) {
                            subdirsToCheckForPackages.add(
                                TraversalInfo(
                                    subdirectory,
                                    forbiddenSubdirectoriesBeneathThisSubdirectory,
                                    excludedSubdirectoriesBeneathThisSubdirectory
                                )
                            )
                        }
                    }
                }
            }
            return Visit( /*keysToUseForResult=*/
                resultPackageIds,  /*keysToVisit=*/subdirsToCheckForPackages
            )
        }

        protected override fun preprocessInitialVisit(infos: Iterable<TraversalInfo?>?): Iterable<TraversalInfo?>? {
            return infos
        }

        protected override fun noteAndReturnUniqueVisitationKeys(
            prospectiveVisitationKeys: Iterable<TraversalInfo?>?
        ): Iterable<TraversalInfo?>? {
            return prospectiveVisitationKeys
        }
    }

    /** Value type used as visitation and output key for [PackageCollectingParallelVisitor].  */
    private class TraversalInfo(
        rootedDir: RootedPath,
        forbiddenSubdirectories: IgnoredSubdirectories,
        excludedSubdirectories: com.google.common.collect.ImmutableSet<PathFragment?>
    ) {
        val rootedDir: RootedPath

        // Set of forbidden directories. The graph is assumed to be prepopulated with
        // CollectPackagesUnderDirectoryValue nodes whose keys have forbidden packages embedded in
        // them. Therefore, we need to be careful to request and use the same sort of keys here in our
        // traversal.
        val forbiddenSubdirectories: IgnoredSubdirectories

        // Set of directories, targets under which should be excluded from the traversal results.
        // Excluded directory information isn't part of the graph keys in the prepopulated graph, so we
        // need to perform the filtering ourselves.
        val excludedSubdirectories: com.google.common.collect.ImmutableSet<PathFragment?>

        init {
            this.rootedDir = rootedDir
            this.forbiddenSubdirectories = forbiddenSubdirectories
            this.excludedSubdirectories = excludedSubdirectories
        }

        override fun hashCode(): Int {
            return com.google.common.base.Objects.hashCode(rootedDir, forbiddenSubdirectories, excludedSubdirectories)
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj is TraversalInfo) {
                return com.google.common.base.Objects.equal(rootedDir, obj.rootedDir)
                        && com.google.common.base.Objects.equal(forbiddenSubdirectories, obj.forbiddenSubdirectories)
                        && com.google.common.base.Objects.equal(excludedSubdirectories, obj.excludedSubdirectories)
            }
            return false
        }
    }

    companion object {
        private val TRAVERSAL_INFO_COMPARATOR: java.util.Comparator<TraversalInfo?>? =
            java.util.Comparator.comparing<TraversalInfo?, PathFragment?>(java.util.function.Function { ti: TraversalInfo? -> ti!!.rootedDir.getRootRelativePath() })

        private const val PACKAGE_ID_OUTPUT_BATCH_SIZE = 100
        private val DEFAULT_THREAD_COUNT: Int = java.lang.Runtime.getRuntime().availableProcessors()

        private val PACKAGE_ID_COLLECTING_EXECUTOR: ExecutorService = Executors.newFixedThreadPool( /*numThreads=*/
            DEFAULT_THREAD_COUNT,
            com.google.common.util.concurrent.ThreadFactoryBuilder().setNameFormat("package-id-traversal-%d").build()
        )
    }
}
