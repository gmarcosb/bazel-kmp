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
package com.google.devtools.build.lib.skyframe


import com.google.devtools.build.lib.actions.FileValue

/**
 * Processes a directory that may contain a package and subdirectories for the benefit of processes
 * that traverse directories recursively, looking for packages.
 */
class ProcessPackageDirectory(directories: BlazeDirectories, skyKeyTransformer: SkyKeyTransformer) {
    /** Produces a [SkyKey] for the recursive traversal into the specified subdirectory.  */
    interface SkyKeyTransformer {
        fun makeSkyKey(
            repository: RepositoryName?,
            subdirectory: RootedPath?,
            excludedSubdirectoriesBeneathSubdirectory: IgnoredSubdirectories?
        ): SkyKey?
    }

    private val directories: BlazeDirectories
    private val skyKeyTransformer: SkyKeyTransformer

    init {
        this.directories = directories
        this.skyKeyTransformer = skyKeyTransformer
    }

    /**
     * Examines `rootedPath` to see if it is the location of a package, and to see if it has any
     * subdirectory children that should also be examined. Returns a [ ], or `null` if required dependencies were missing.
     */
    @Throws(java.lang.InterruptedException::class, ProcessPackageDirectorySkyFunctionException::class)
    fun getPackageExistenceAndSubdirDeps(
        rootedPath: RootedPath,
        repositoryName: RepositoryName,
        excludedPaths: IgnoredSubdirectories,
        env: SkyFunction.Environment
    ): ProcessPackageDirectoryResult? {
        val rootRelativePath: PathFragment = rootedPath.getRootRelativePath()

        val fileKey: SkyKey? = FileValue.key(rootedPath)
        val fileValue: FileValue?
        try {
            fileValue = env.getValueOrThrow<IOException?>(fileKey, IOException::class.java) as FileValue?
        } catch (e: InconsistentFilesystemException) {
            throw ProcessPackageDirectorySkyFunctionException(rootedPath, e)
        } catch (e: IOException) {
            return reportErrorAndReturn(
                "Failed to get information about path", e, rootRelativePath, env.getListener()
            )
        }
        if (env.valuesMissing()) {
            return null
        }

        if (!fileValue.isDirectory()) {
            return ProcessPackageDirectoryResult.Companion.EMPTY_RESULT
        }

        if (fileValue.unboundedAncestorSymlinkExpansionChain() != null) {
            val uniquenessKey: SkyKey? =
                FileSymlinkInfiniteExpansionUniquenessFunction.key(
                    fileValue.unboundedAncestorSymlinkExpansionChain()
                )
            env.getValue(uniquenessKey)
            if (env.valuesMissing()) {
                return null
            }

            val symlinkException: FileSymlinkInfiniteExpansionException =
                FileSymlinkInfiniteExpansionException(
                    fileValue.pathToUnboundedAncestorSymlinkExpansionChain(),
                    fileValue.unboundedAncestorSymlinkExpansionChain()
                )
            return reportErrorAndReturn(
                symlinkException.getMessage(), symlinkException, rootRelativePath, env.getListener()
            )
        }

        val packageId: PackageIdentifier = PackageIdentifier.create(repositoryName, rootRelativePath)

        if (packageId.getRepository().isMain() && isConvenienceSymlink(fileValue, rootedPath, env)) {
            return ProcessPackageDirectoryResult.Companion.EMPTY_RESULT
        }

        if (env.valuesMissing()) {
            return null
        }

        val pkgLookupKey: SkyKey? = PackageLookupValue.Companion.key(packageId)
        val dirListingKey: SkyKey? = DirectoryListingValue.Companion.key(rootedPath)
        val pkgLookupAndDirectoryListingDeps: SkyframeLookupResult =
            env.getValuesAndExceptions(com.google.common.collect.ImmutableList.of<SkyKey?>(pkgLookupKey, dirListingKey))
        val pkgLookupValue: PackageLookupValue?
        try {
            pkgLookupValue =
                pkgLookupAndDirectoryListingDeps.getOrThrow<E1?, E2?>(
                    pkgLookupKey,
                    NoSuchPackageException::class.java,
                    InconsistentFilesystemException::class.java
                ) as PackageLookupValue?
        } catch (e: NoSuchPackageException) {
            return reportErrorAndReturn("Failed to load package", e, rootRelativePath, env.getListener())
        } catch (e: InconsistentFilesystemException) {
            throw ProcessPackageDirectorySkyFunctionException(rootedPath, e)
        }
        val dirListingValue: DirectoryListingValue?
        try {
            dirListingValue =
                pkgLookupAndDirectoryListingDeps.getOrThrow<IOException?>(
                    dirListingKey,
                    IOException::class.java
                ) as DirectoryListingValue?
        } catch (e: FileSymlinkException) {
            // DirectoryListingFunction only throws FileSymlinkCycleException when FileFunction throws it,
            // but FileFunction was evaluated for rootedPath above, and didn't throw there. It shouldn't
            // be able to avoid throwing there but throw here.
            throw java.lang.IllegalStateException(
                "Symlink cycle found after not being found for \"" + rootedPath + "\"", e
            )
        } catch (e: IOException) {
            return reportErrorAndReturn(
                "Failed to list directory contents", e, rootRelativePath, env.getListener()
            )
        }
        val starlarkSemantics: net.starlark.java.eval.StarlarkSemantics? =
            PrecomputedValue.Companion.STARLARK_SEMANTICS.get(env)
        if (env.valuesMissing()) {
            return null
        }
        com.google.common.base.Preconditions.checkNotNull<PackageLookupValue?>(
            pkgLookupValue, "%s %s %s", rootedPath, repositoryName, pkgLookupKey
        )
        com.google.common.base.Preconditions.checkNotNull<DirectoryListingValue?>(
            dirListingValue, "%s %s %s", rootedPath, repositoryName, dirListingKey
        )
        return ProcessPackageDirectoryResult(
            pkgLookupValue.packageExists() && pkgLookupValue.getRoot() == rootedPath.getRoot(),
            getSubdirDeps(
                dirListingValue,
                rootedPath,
                repositoryName,
                excludedPaths,
                starlarkSemantics.getBool(BuildLanguageOptions.EXPERIMENTAL_SIBLING_REPOSITORY_LAYOUT)
            ),  /*additionalValuesToAggregate=*/
            com.google.common.collect.ImmutableMap.of<SkyKey?, SkyValue?>()
        )
    }

    // Note that it's not enough to just check for the convenience symlinks themselves,
    // because if the value of --symlink_prefix changes, the old symlinks are left in place. It
    // is also not sufficient to check whether the symlink points to a directory in the current
    // exec root, since this can change between bazel invocations. Therefore we check if the
    // suffix of the symlink source suggests it is a convenience symlink, then see if the symlink
    // target is in a directory that looks like an execroot. This algorithm also covers more
    // creative use cases where people create convenience symlinks somewhere in the directory
    // tree manually.
    @Throws(java.lang.InterruptedException::class)
    private fun isConvenienceSymlink(
        fileValue: FileValue, rootedPath: RootedPath, env: SkyFunction.Environment
    ): Boolean {
        if (!fileValue.isSymlink()) {
            return false
        }

        val linkTarget: PathFragment = fileValue.getUnresolvedLinkTarget()

        if (linkTarget.startsWith(directories.getExecRootBase().asFragment())) {
            return true
        }

        val rootRelativePath: PathFragment = rootedPath.getRootRelativePath()
        val root: Root = rootedPath.getRoot()

        if (rootRelativePath.getBaseName().endsWith("-bin") && isInExecRoot(linkTarget, root, 4, env)) {
            return true
        }

        if (rootRelativePath.getBaseName().endsWith("-genfiles")
            && isInExecRoot(linkTarget, root, 4, env)
        ) {
            return true
        }

        if (rootRelativePath.getBaseName().endsWith("-out") && isInExecRoot(linkTarget, root, 2, env)) {
            return true
        }

        if (rootRelativePath.getBaseName().endsWith("-testlogs")
            && isInExecRoot(linkTarget, root, 4, env)
        ) {
            return true
        }

        if (rootRelativePath
                .getBaseName()
                .endsWith("-" + directories.getWorkingDirectory().getBaseName())
            && isInExecRoot(linkTarget, root, 1, env)
        ) {
            return true
        }

        return false
    }

    @Throws(java.lang.InterruptedException::class)
    private fun isInExecRoot(path: PathFragment, root: Root, depth: Int, env: SkyFunction.Environment): Boolean {
        val segmentCount: Int = path.segmentCount()

        if (segmentCount <= depth) {
            return false
        }

        val candidateExecRoot: PathFragment = path.subFragment(0, segmentCount - depth)

        if (candidateExecRoot.getBaseName() != "execroot") {
            return false
        }

        val absoluteRoot: Root? = Root.absoluteRoot(root.getFileSystem())
        val doNotBuildPath: RootedPath? =
            RootedPath.toRootedPath(absoluteRoot, candidateExecRoot.getChild("DO_NOT_BUILD_HERE"))
        val doNotBuildValue: FileValue? = env.getValue(FileValue.key(doNotBuildPath)) as FileValue?
        if (doNotBuildValue == null) {
            return false
        }

        return doNotBuildValue.exists()
    }

    private fun getSubdirDeps(
        dirListingValue: DirectoryListingValue,
        rootedPath: RootedPath,
        repositoryName: RepositoryName,
        excludedPaths: IgnoredSubdirectories,
        siblingRepositoryLayout: Boolean
    ): Iterable<SkyKey?> {
        val root: Root = rootedPath.getRoot()
        val rootRelativePath: PathFragment = rootedPath.getRootRelativePath()
        val followSymlinks = shouldFollowSymlinksWhenTraversing(dirListingValue.getDirents())
        val childDeps: MutableList<SkyKey?> = java.util.ArrayList<SkyKey?>()
        for (dirent in dirListingValue.getDirents()) {
            val type: com.google.devtools.build.lib.vfs.Dirent.Type = dirent.getType()
            if (type != com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY && (type != com.google.devtools.build.lib.vfs.Dirent.Type.SYMLINK || !followSymlinks)) {
                // Non-directories can never host packages. Symlinks to non-directories are weeded out at
                // the next level of recursion when we check if its FileValue is a directory. This is slower
                // if there are a lot of symlinks in the tree, but faster if there are only a few, which is
                // the case most of the time.
                //
                // We are not afraid of weird symlink structure here: both cyclical ones and ones that give
                // rise to infinite directory trees are diagnosed by FileValue.
                continue
            }
            val basename: String = dirent.getName()
            val subdirectory: PathFragment = rootRelativePath.getRelative(basename)
            if (!siblingRepositoryLayout && subdirectory == LabelConstants.EXTERNAL_PACKAGE_NAME
                && repositoryName.isMain()
            ) {
                // Subpackages under //external in the main repo can be processed only
                // when --experimental_sibling_repository_layout is set.
                continue
            }

            // If this subdirectory is one of the excluded paths, don't recurse into it.
            if (excludedPaths.matchingEntryForTraversal(subdirectory)) {
                continue
            }

            childDeps.add(
                skyKeyTransformer.makeSkyKey(
                    repositoryName,
                    RootedPath.toRootedPath(root, subdirectory),
                    excludedPaths.filterForDirectory(subdirectory)
                )
            )
        }
        return childDeps
    }

    /** Wraps [InconsistentFilesystemException] in [ProcessPackageDirectoryException].  */
    class ProcessPackageDirectorySkyFunctionException
        (directory: RootedPath?, e: InconsistentFilesystemException?) :
        SkyFunctionException(ProcessPackageDirectoryException(directory, e), Transience.PERSISTENT) {
        val isCatastrophic: Boolean
            get() = true
    }

    companion object {
        private const val SENTINEL_FILE_NAME_FOR_NOT_TRAVERSING_SYMLINKS =
            "DONT_FOLLOW_SYMLINKS_WHEN_TRAVERSING_THIS_DIRECTORY_VIA_A_RECURSIVE_TARGET_PATTERN"

        /**
         * Returns the 'excludedPaths' set to use when recursing below this subdirectory. If we have an
         * excluded path that isn't below this subdirectory, we shouldn't pass that excluded path to our
         * evaluation of the subdirectory, because the exclusion can't possibly match anything beneath the
         * subdirectory.
         * 
         * 
         * For example, if we're currently evaluating directory "a", are looking at its subdirectory
         * "a/b", and we have an excluded path "a/c/d", there's no need to pass the excluded path "a/c/d"
         * to our evaluation of "a/b". This strategy should help to get more skyframe sharing. In our
         * example, a subsequent request of "a/b/...", without any excluded paths, will be a cache hit.
         * 
         * 
         * TODO(bazel-team): Replace the excludedPaths set with a trie or a SortedSet for better
         * efficiency.
         */
        fun getExcludedSubdirectoriesBeneathSubdirectory(
            subdirectory: PathFragment?, excludedPaths: IgnoredSubdirectories
        ): IgnoredSubdirectories {
            return excludedPaths.filterForDirectory(subdirectory)
        }

        private fun reportErrorAndReturn(
            errorPrefix: String?, e: java.lang.Exception, rootRelativePath: PathFragment?, handler: EventHandler
        ): ProcessPackageDirectoryResult {
            handler.handle(
                Event.error(errorPrefix + ", for " + rootRelativePath + ", skipping: " + e.getMessage())
            )
            return ProcessPackageDirectoryResult.Companion.EMPTY_RESULT
        }

        private fun shouldFollowSymlinksWhenTraversing(dirents: Dirents): Boolean {
            for (dirent in dirents) {
                // This is a special sentinel file whose existence tells Blaze not to follow symlinks when
                // recursively traversing through this directory.
                //
                // This admittedly ugly feature is used to support workspaces with directories with weird
                // symlink structures that aren't intended to be consumed by Blaze.
                if (dirent.getName() == SENTINEL_FILE_NAME_FOR_NOT_TRAVERSING_SYMLINKS) {
                    return false
                }
            }
            return true
        }
    }
}
