// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2

import com.google.devtools.build.lib.actions.FileStateValue

/**
 * A helper class that visits packages of affected files by BFS and is represented by the
 * queryfunction 'rbuildfiles'. Other query functions may also use functionality provided by this
 * visitor.
 */
class RBuildFilesVisitor(
    env: SkyQueryEnvironment,
    visitUniquifier: Uniquifier<SkyKey?>,
    resultUniquifier: Uniquifier<SkyKey?>,
    context: QueryExpressionContext<Target?>?,
    callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>?
) : ParallelQueryVisitor<SkyKey?, PackageIdentifier?, Target?>(
    callback,
    env.getVisitBatchSizeForParallelVisitation(),
    PROCESS_RESULTS_BATCH_SIZE,
    env.getVisitTaskStatusCallback()
) {
    private val env: SkyQueryEnvironment
    private val context: QueryExpressionContext<Target?>?
    private val visitUniquifier: Uniquifier<SkyKey?>
    protected val resultUniquifier: Uniquifier<SkyKey?>

    init {
        this.env = env
        this.visitUniquifier = visitUniquifier
        this.resultUniquifier = resultUniquifier
        this.context = context
    }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class, java.lang.InterruptedException::class)
    protected override fun getVisitResult(values: Iterable<SkyKey?>?): Visit? {
        val reverseDeps: MutableCollection<Iterable<SkyKey?>?> = env.graph.getReverseDeps(values).values
        val keysToUseForResult: MutableSet<PackageIdentifier?> =
            com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.create<PackageIdentifier?>()
        val keysToVisitNext: MutableSet<SkyKey?> =
            com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.create<SkyKey?>()
        for (rdep in com.google.common.collect.Iterables.concat<SkyKey>(reverseDeps)) {
            // This loop is cpu bound, make sure we bail if asked.
            if (java.lang.Thread.interrupted()) {
                throw java.lang.InterruptedException()
            }

            if (rdep.functionName() == SkyFunctions.PACKAGE) {
                if (resultUniquifier.unique(rdep)) {
                    keysToUseForResult.add(rdep.argument() as PackageIdentifier?)
                }
                // PackageValue(//p) has a transitive dep on the PackageValue(//external), so we need to
                // make sure these dep paths are traversed. These dep paths go through the singleton
                // WorkspaceNameValue(), and that node has a direct dep on PackageValue(//external), so it
                // suffices to ensure we visit PackageValue(//external).
                if (rdep == EXTERNAL_PACKAGE_KEY) {
                    keysToVisitNext.add(rdep)
                }
            } else if (!NODES_TO_PRUNE_TRAVERSAL.contains(rdep.functionName())) {
                processNonPackageRdepAndDetermineVisitations(rdep, keysToVisitNext, keysToUseForResult)
            }
        }
        return Visit(keysToUseForResult, keysToVisitNext)
    }

    protected override fun preprocessInitialVisit(visitationKeys: Iterable<SkyKey?>?): Iterable<SkyKey?>? {
        return visitationKeys
    }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class)
    protected fun processNonPackageRdepAndDetermineVisitations(
        rdep: SkyKey, keysToVisitNext: MutableSet<SkyKey?>, keysToUseForResult: MutableSet<PackageIdentifier?>?
    ) {
        // Packages may depend on the existence of subpackages, but these edges aren't
        // relevant to rbuildfiles. They may also depend on files transitively through
        // globs, but these cannot be included in load statements and so we don't traverse
        // through these either.
        if ((rdep.functionName() != SkyFunctions.PACKAGE_LOOKUP) && (rdep.functionName() != SkyFunctions.GLOB) && (rdep.functionName() != SkyFunctions.GLOBS)) {
            keysToVisitNext.add(rdep)
        }
    }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class, java.lang.InterruptedException::class)
    protected override fun outputKeysToOutputValues(targetKeys: Iterable<PackageIdentifier?>): Iterable<Target?>? {
        return env.getBuildFileTargetsForPackageKeys(
            com.google.common.collect.ImmutableSet.copyOf<PackageIdentifier?>(
                targetKeys
            ), context
        )
    }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class)
    protected override fun noteAndReturnUniqueVisitationKeys(
        prospectiveVisitationKeys: Iterable<SkyKey?>?
    ): Iterable<SkyKey?>? {
        return visitUniquifier.unique(prospectiveVisitationKeys)
    }

    /** Initiates the graph visitation algorithm seeded by a set of file paths.  */
    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class, java.lang.InterruptedException::class)
    fun visitFileIdentifiersAndWaitForCompletion(
        graph: WalkableGraph, fileKeys: Iterable<PathFragment>
    ) {
        visitAndWaitForCompletion(
            getSkyKeysForFileFragments(graph, fileKeys,  /* includeAncestorKeys= */false)
        )
    }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class, java.lang.InterruptedException::class)
    fun visitFileAndDirectoryKeysAndWaitForCompletion(
        graph: WalkableGraph, fileKeys: Iterable<PathFragment>
    ) {
        visitAndWaitForCompletion(
            getSkyKeysForFileFragments(graph, fileKeys,  /* includeAncestorKeys= */true)
        )
    }

    companion object {
        // Each target in the full output of this visitor corresponds to BUILD file InputFile of a
        // unique package. So the processResultsBatchSize we choose to pass to the ParallelVisitor ctor
        // influences how many packages each leaf task doing processPartialResults will have to
        // deal with at once. A value of 100 was chosen experimentally.
        private const val PROCESS_RESULTS_BATCH_SIZE = 100

        // We don't expect to find any additional BUILD files so we skip visitation of the following
        // nodes.
        private val NODES_TO_PRUNE_TRAVERSAL: com.google.common.collect.ImmutableSet<SkyFunctionName?> =
            com.google.common.collect.ImmutableSet.of<SkyFunctionName?>(
                Label.TRANSITIVE_TRAVERSAL,
                SkyFunctions.COLLECT_TARGETS_IN_PACKAGE,
                SkyFunctions.PREPARE_DEPS_OF_TARGETS_UNDER_DIRECTORY,
                SkyFunctions.PACKAGE_ERROR_MESSAGE,
                SkyFunctions.PREPARE_DEPS_OF_PATTERN,
                SkyFunctions.PREPARE_DEPS_OF_PATTERNS
            )

        private val EXTERNAL_PACKAGE_KEY: SkyKey? = LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER

        /**
         * The passed in [PathFragment]s can be associated uniquely to a [FileStateValue] with
         * the following logic (the same logic that's in [ContainingPackageLookupFunction]): For
         * each given file path, we look for the nearest ancestor directory (starting with its parent
         * directory), if any, that has a package. The [PackageLookupValue] for this package tells
         * us the package root that we should use for the [RootedPath] for the [ ] key.
         * 
         * 
         * For the reverse graph traversal, we are looking for all packages that are transitively
         * reverse dependencies of those [FileStateValue] keys. This function returns a collection
         * of SkyKeys whose transitive reverse dependencies must contain the exact same set of packages.
         * 
         * 
         * Note that there may not be nodes in the graph corresponding to the returned SkyKeys.
         * 
         * 
         * Note also that we assume there'll be a PackageLookupValue node for some ancestor directory
         * of every file to which a symlink could possibly point otherwise the file will not be included.
         * 
         * 
         * If includeAncestorKeys is true, we will include a directory listing state of the first
         * ancestor directory that exists and file states for non-existent ancestors.
         */
        @Throws(java.lang.InterruptedException::class)
        fun getSkyKeysForFileFragments(
            graph: WalkableGraph, pathFragments: Iterable<PathFragment>, includeAncestorKeys: Boolean
        ): MutableSet<SkyKey?> {
            if (com.google.common.collect.Iterables.isEmpty(pathFragments)) {
                return com.google.common.collect.ImmutableSet.of<SkyKey?>()
            }

            val result: MutableSet<SkyKey?> = HashSet<SkyKey?>()
            var currentAncestorToOriginalPath: com.google.common.collect.ListMultimap<PathFragment, PathFragment?> =
                com.google.common.collect.ArrayListMultimap.create<PathFragment?, PathFragment?>()
            for (pathFragment in pathFragments) {
                checkWorkspaceFile(result, pathFragment)
                val parentPathFragment: PathFragment? = pathFragment.getParentDirectory()
                if (parentPathFragment != null) {
                    currentAncestorToOriginalPath.put(parentPathFragment, pathFragment)
                }
            }

            // Used to find directories that have been added in the diff.
            val pathsToCheckForNewlyAddedDirectories: MutableSet<RootedPath?> = HashSet<RootedPath?>()

            // We look at each ancestor directory of every file, and use the currentAncestorToOriginalPath
            // map to avoid doing unnecessary work with common ancestors. If we don't find a package
            // with the first level of ancestors, we go up a level, until we find the first package
            // for every file. If a file doesn't have a parent package, the file is ignored.
            while (!currentAncestorToOriginalPath.isEmpty()) {
                val pkgLookupKeys: com.google.common.collect.ImmutableSet<SkyKey?> =
                    currentAncestorToOriginalPath.keySet().stream()
                        .map<SkyKey?> { pathFragment: PathFragment? -> getPkgLookupKeyForDirectory(pathFragment) }
                        .collect(com.google.common.collect.ImmutableSet.toImmutableSet<SkyKey?>())
                val lookupValues: MutableMap<SkyKey?, SkyValue?> = graph.getSuccessfulValues(pkgLookupKeys)
                for (entry in lookupValues.entries) {
                    val packageLookupValue: PackageLookupValue = entry.value as PackageLookupValue
                    if (packageLookupValue.packageExists()) {
                        val packageLookupKey: SkyKey = entry.key
                        val currentFragment: PathFragment =
                            (packageLookupKey.argument() as PackageIdentifier).getPackageFragment()
                        val originalFiles: MutableList<PathFragment> =
                            currentAncestorToOriginalPath.get(currentFragment)
                        com.google.common.base.Preconditions.checkState(!originalFiles.isEmpty(), entry)
                        for (fileName in originalFiles) {
                            val rootedPath: RootedPath = RootedPath.toRootedPath(packageLookupValue.getRoot(), fileName)
                            result.add(FileStateValue.key(rootedPath))
                            // Include the File key too in case the FileState is never considered due to a
                            // missing parent directory.
                            result.add(FileValue.key(rootedPath))

                            if (includeAncestorKeys) {
                                val parentPath: RootedPath? = rootedPath.getParentDirectory()
                                result.add(DirectoryListingStateValue.key(parentPath))
                                // Include the DirectoryListing key too in case the DirectoryListingState is never
                                // considered due to a missing parent directory.
                                result.add(DirectoryListingValue.key(parentPath))
                                var pathToCheckIfNewlyAdded: PathFragment? = fileName
                                while (pathToCheckIfNewlyAdded.getPathString().length
                                    > currentFragment.getPathString().length
                                ) {
                                    pathsToCheckForNewlyAddedDirectories.add(
                                        RootedPath.toRootedPath(packageLookupValue.getRoot(), pathToCheckIfNewlyAdded)
                                    )
                                    pathToCheckIfNewlyAdded = pathToCheckIfNewlyAdded.getParentDirectory()
                                }
                            }
                        }
                        currentAncestorToOriginalPath.removeAll(currentFragment)
                    }
                }
                currentAncestorToOriginalPath = goUpOneDirectory(currentAncestorToOriginalPath)
            }
            if (includeAncestorKeys) {
                includeAncestorKeysInResult(graph, result, pathsToCheckForNewlyAddedDirectories)
            }
            return result
        }

        private fun goUpOneDirectory(
            currentToOriginal: com.google.common.collect.Multimap<PathFragment, PathFragment?>
        ): com.google.common.collect.ListMultimap<PathFragment, PathFragment?> {
            val newCurrentToOriginal: com.google.common.collect.ListMultimap<PathFragment, PathFragment?> =
                com.google.common.collect.ArrayListMultimap.create<PathFragment?, PathFragment?>()
            for (pathFragment in currentToOriginal.keySet()) {
                val parent: PathFragment? = pathFragment.getParentDirectory()
                if (parent != null) {
                    newCurrentToOriginal.putAll(parent, currentToOriginal.get(pathFragment))
                }
            }
            return newCurrentToOriginal
        }

        private fun checkWorkspaceFile(result: MutableSet<SkyKey?>, file: PathFragment) {
            // The WORKSPACE file is a transitive dependency of every package. Unfortunately, there is
            // no specific SkyValue that we can use to figure out under which package path entries it
            // lives so we add a dependency on the main repo mapping key.
            if (file == LabelConstants.WORKSPACE_FILE_NAME
                || file == LabelConstants.WORKSPACE_DOT_BAZEL_FILE_NAME
            ) {
                // TODO(mschaller): this should not be checked at runtime. These are constants!
                com.google.common.base.Preconditions.checkState(
                    LabelConstants.WORKSPACE_FILE_NAME
                        .getParentDirectory()
                        .equals(PathFragment.EMPTY_FRAGMENT),
                    LabelConstants.WORKSPACE_FILE_NAME
                )
                result.add(RepositoryMappingValue.key(RepositoryName.MAIN))
            }
        }

        @Throws(java.lang.InterruptedException::class)
        private fun includeAncestorKeysInResult(
            graph: WalkableGraph, result: MutableSet<SkyKey?>, fileStateKeysToFetch: MutableSet<out FileStateKey?>
        ) {
            // Do a single batch fetch of all FileState's corresponding to directories with
            // failed package lookups.
            val fileStateValues: MutableMap<SkyKey?, SkyValue?> = graph.getSuccessfulValues(fileStateKeysToFetch)
            for (fileStateKey in fileStateKeysToFetch) {
                if (fileStateValues.containsKey(fileStateKey)) {
                    val fsv: FileStateValue = fileStateValues.get(fileStateKey) as FileStateValue
                    if (!fsv.getType().exists() && !fsv.getType().isDirectory()) {
                        processFileStateKeyForMissingDirectory(result, fileStateKey as FileStateKey?)
                    }
                } else {
                    processFileStateKeyForMissingDirectory(result, fileStateKey as FileStateKey?)
                }
            }
        }

        private fun processFileStateKeyForMissingDirectory(result: MutableSet<SkyKey?>, key: FileStateKey) {
            val rootedPath: RootedPath = key.argument()
            result.add(key)
            result.add(FileValue.key(rootedPath))
            // Add a DirectoryListingState node to our traversal even if the ancestor path too didn't exist
            // prior to the diff. This will have no effect on the results if the ancestor directory was also
            // newly created doesn't exist but has the consequence that the first ancestor path that did
            // exist prior to the diff will be correctly marked as having a changed directory listing state.
            val parentPath: RootedPath? = rootedPath.getParentDirectory()
            if (parentPath != null) {
                result.add(DirectoryListingStateValue.key(parentPath))
                result.add(DirectoryListingValue.key(parentPath))
            }
        }

        /**
         * Returns package lookup key for looking up the package root for which there may be a relevant
         * [FileStateValue] node in the graph for `originalFileFragment`, which is assumed to
         * be a file path.
         * 
         * 
         * This is a helper function for [.getSkyKeysForFileFragments].
         */
        private fun getPkgLookupKeyForDirectory(pathFragment: PathFragment?): SkyKey? {
            return PackageLookupValue.key(
                PackageIdentifier.createInMainRepo(com.google.common.base.Preconditions.checkNotNull<T?>(pathFragment))
            )
        }
    }
}
