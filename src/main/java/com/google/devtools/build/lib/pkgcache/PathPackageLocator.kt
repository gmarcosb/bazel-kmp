// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.pkgcache

import com.google.devtools.build.lib.analysis.BlazeDirectories

/**
 * A mapping from the name of a package to the location of its BUILD file. The implementation
 * composes an ordered sequence of directories according to the package-path rules.
 * 
 * 
 * All methods are thread-safe, and (assuming no change to the underlying filesystem) idempotent.
 */
class PathPackageLocator @com.google.common.annotations.VisibleForTesting constructor(
    outputBase: com.google.devtools.build.lib.vfs.Path?,
    pathEntries: MutableList<Root?>,
    buildFilesByPriority: MutableList<BuildFileName?>
) {
    private val pathEntries: com.google.common.collect.ImmutableList<Root>

    // Transient because this is an injected value in Skyframe, and as such, its serialized
    // representation is used as a key. We want a change to output base not to invalidate things.
    @Transient
    private val outputBase: com.google.devtools.build.lib.vfs.Path?

    private val buildFilesByPriority: com.google.common.collect.ImmutableList<BuildFileName>

    init {
        this.outputBase = outputBase
        this.pathEntries = com.google.common.collect.ImmutableList.copyOf<Root?>(pathEntries)
        this.buildFilesByPriority = com.google.common.collect.ImmutableList.copyOf<BuildFileName?>(buildFilesByPriority)
    }

    /**
     * Returns the path to the build file for this package, or null if not found.
     * 
     * 
     * The package's root directory may be computed by calling getParentFile() on the result of
     * this function.
     * 
     * 
     * If the same package exists beneath multiple package path entries, the first path that
     * matches always wins.
     * 
     * @param packageIdentifier the name of the package.
     * @param syscallCache a filesystem-level cache of stat() calls.
     * @return the [Path] to the correct build file, or `null` if none was found
     */
    fun getPackageBuildFileNullable(
        packageIdentifier: PackageIdentifier, syscallCache: SyscallCache
    ): com.google.devtools.build.lib.vfs.Path? {
        if (packageIdentifier.getRepository().isMain()) {
            for (buildFileName in buildFilesByPriority) {
                val buildFilePath: com.google.devtools.build.lib.vfs.Path? =
                    getFilePath(
                        packageIdentifier
                            .getPackageFragment()
                            .getRelative(buildFileName.getFilenameFragment()),
                        syscallCache
                    )
                if (buildFilePath != null) {
                    return buildFilePath
                }
            }
        } else {
            com.google.common.base.Verify.verify(
                outputBase != null,
                "External package '%s' needs to be loaded but this PathPackageLocator instance does not "
                        + "support external packages",
                packageIdentifier
            )
            // This works only to some degree, because it relies on the presence of the repository under
            // $OUTPUT_BASE/external, which is created by the appropriate RepositoryDirectoryValue. This
            // is true for the invocation in GlobCache, but not for the locator.getBuildFileForPackage()
            // invocation in Parser#include().
            for (buildFileName in buildFilesByPriority) {
                val buildFile: com.google.devtools.build.lib.vfs.Path =
                    outputBase
                        .getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION)
                        .getRelative(packageIdentifier.getRepository().name)
                        .getRelative(packageIdentifier.getSourceRoot())
                        .getRelative(buildFileName.getFilenameFragment())
                try {
                    val stat: FileStatus? = syscallCache.statIfFound(buildFile, Symlinks.FOLLOW)
                    if (stat != null && stat.isFile()) {
                        return buildFile
                    }
                } catch (e: IOException) {
                    return null
                }
            }
        }

        return null
    }

    /** Returns an immutable ordered list of the directories on the package path.  */
    fun getPathEntries(): com.google.common.collect.ImmutableList<Root> {
        return pathEntries
    }

    override fun toString(): String {
        return "PathPackageLocator" + pathEntries
    }

    /**
     * Returns the path to the WORKSPACE file for this build.
     * 
     * 
     * If there are WORKSPACE files beneath multiple package path entries, the first one always
     * wins.
     */
    fun getWorkspaceFile(syscallCache: SyscallCache): com.google.devtools.build.lib.vfs.Path? {
        // TODO(bazel-team): correctness in the presence of changes to the location of the WORKSPACE
        //  file.
        val workspaceFile: com.google.devtools.build.lib.vfs.Path? =
            getFilePath(LabelConstants.WORKSPACE_DOT_BAZEL_FILE_NAME, syscallCache)
        if (workspaceFile != null) {
            return workspaceFile
        }
        return getFilePath(LabelConstants.WORKSPACE_FILE_NAME, syscallCache)
    }

    private fun getFilePath(suffix: PathFragment?, cache: SyscallCache): com.google.devtools.build.lib.vfs.Path? {
        for (pathEntry in pathEntries) {
            val buildFile: com.google.devtools.build.lib.vfs.Path? = pathEntry.getRelative(suffix)
            try {
                val typeWithSkip: DirentTypeWithSkip? = cache.getType(buildFile, Symlinks.FOLLOW)
                var type: com.google.devtools.build.lib.vfs.Dirent.Type? = null
                if (typeWithSkip == SyscallCache.DirentTypeWithSkip.FILESYSTEM_OP_SKIPPED) {
                    type = SyscallCache.statusToDirentType(cache.statIfFound(buildFile, Symlinks.FOLLOW))
                } else if (typeWithSkip != null) {
                    type = typeWithSkip.getType()
                }
                if (type == com.google.devtools.build.lib.vfs.Dirent.Type.FILE || type == com.google.devtools.build.lib.vfs.Dirent.Type.UNKNOWN) {
                    return buildFile
                }
            } catch (ignored: IOException) {
                // Treat IOException as a missing file.
            }
        }
        return null
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(pathEntries, outputBase)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is PathPackageLocator) {
            return false
        }
        return pathEntries == other.pathEntries
                && outputBase == other.outputBase
    }

    fun getOutputBase(): com.google.devtools.build.lib.vfs.Path? {
        return outputBase
    }

    companion object {
        private const val WORKSPACE_WILDCARD = "%workspace%"

        fun maybeReplaceWorkspaceInString(pathElement: String, workspace: PathFragment): String? {
            return pathElement.replace(WORKSPACE_WILDCARD, workspace.getPathString())
        }

        /**
         * A factory of PathPackageLocators from a list of path elements. Elements may contain
         * "%workspace%", indicating the workspace.
         * 
         * 
         * If any of the paths given do not exist, an exception will be thrown.
         * 
         * @param outputBase the output base. Can be null if remote repositories are not in use.
         * @param pathElements Each element must be an absolute path, relative path, or some string
         * "%workspace%" + relative, where relative is itself a relative path. The special symbol
         * "%workspace%" means to interpret the path relative to the nearest enclosing workspace.
         * Relative paths are interpreted relative to the client's working directory, which may be
         * below the workspace.
         * @param eventHandler The eventHandler.
         * @param workspace The nearest enclosing package root directory.
         * @param clientWorkingDirectory The client's working directory.
         * @param buildFilesByPriority The ordered collection of [BuildFileName]s to check in each
         * potential package directory.
         * @return a [PathPackageLocator] that uses the `outputBase` and `pathElements`
         * provided.
         */
        fun create(
            outputBase: com.google.devtools.build.lib.vfs.Path?,
            pathElements: MutableList<String>,
            eventHandler: EventHandler,
            workspace: PathFragment,
            clientWorkingDirectory: com.google.devtools.build.lib.vfs.Path,
            buildFilesByPriority: MutableList<BuildFileName?>
        ): PathPackageLocator {
            return createInternal(
                outputBase,
                pathElements,
                eventHandler,
                workspace,
                clientWorkingDirectory,
                buildFilesByPriority
            )
        }

        /**
         * A factory of PathPackageLocators from a list of path elements.
         * 
         * @param outputBase the output base. Can be null if remote repositories are not in use.
         * @param pathElements Each element must be a [Root] object.
         * @param buildFilesByPriority The ordered collection of [BuildFileName]s to check in each
         * potential package directory.
         * @return a [PathPackageLocator] that uses the `outputBase` and `pathElements`
         * provided.
         */
        fun createWithoutExistenceCheck(
            outputBase: com.google.devtools.build.lib.vfs.Path?,
            pathElements: MutableList<Root?>,
            buildFilesByPriority: MutableList<BuildFileName?>
        ): PathPackageLocator {
            return PathPackageLocator(outputBase, pathElements, buildFilesByPriority)
        }

        private fun createInternal(
            outputBase: com.google.devtools.build.lib.vfs.Path?,
            pathElements: MutableList<String>,
            eventHandler: EventHandler,
            workspace: PathFragment,
            clientWorkingDirectory: com.google.devtools.build.lib.vfs.Path,
            buildFilesByPriority: MutableList<BuildFileName?>
        ): PathPackageLocator {
            val resolvedPaths: MutableList<Root?> = java.util.ArrayList<Root?>()

            for (pathElement in pathElements) {
                // Replace "%workspace%" with the path of the enclosing workspace directory.
                var pathElement = pathElement
                pathElement = maybeReplaceWorkspaceInString(pathElement, workspace)!!

                val pathElementFragment: PathFragment = PathFragment.create(pathElement)

                // If the path string started with "%workspace%" or "/", it is already absolute, so the
                // following line returns a path pointing to pathElementFragment.
                val rootPath: com.google.devtools.build.lib.vfs.Path =
                    clientWorkingDirectory.getRelative(pathElementFragment)

                if (!pathElementFragment.isAbsolute()
                    && clientWorkingDirectory.asFragment() != workspace
                ) {
                    eventHandler.handle(
                        Event.warn(
                            ("The package path element '"
                                    + pathElementFragment
                                    + "' will be taken relative to your working directory. You may have intended "
                                    + "to have the path taken relative to your workspace directory. If so, please "
                                    + "use the '"
                                    + WORKSPACE_WILDCARD
                                    + "' wildcard.")
                        )
                    )
                }

                if (rootPath.exists()) {
                    resolvedPaths.add(Root.fromPath(rootPath))
                }
            }

            return PathPackageLocator(outputBase, resolvedPaths, buildFilesByPriority)
        }

        /**
         * Extracts the package path from the `--package_path` flag, which is expected to have a
         * single entry.
         * 
         * 
         * May be used to get the real package path when a [ ][BlazeDirectories.getVirtualSourceRoot] is installed.
         */
        @Throws(AbruptExitException::class)
        fun getSingletonPackagePathFromFlag(
            options: com.google.devtools.common.options.OptionsProvider, directories: BlazeDirectories
        ): String? {
            val packagePaths: MutableList<String?> =
                options.getOptions<PackageOptions?>(PackageOptions::class.java).getPackagePath()
            if (packagePaths.size() != 1) {
                throw AbruptExitException(
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage(
                                java.lang.String.format(
                                    "Package path option must have exactly 1 value: %s", packagePaths
                                )
                            )
                            .setPackageOptions(
                                FailureDetails.PackageOptions.newBuilder()
                                    .setCode(Code.NONSINGLETON_PACKAGE_PATH)
                            )
                            .build()
                    )
                )
            }
            return maybeReplaceWorkspaceInString(
                packagePaths.getFirst(), directories.getWorkspace().asFragment()
            )
        }
    }
}
