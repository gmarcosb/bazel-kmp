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
package com.google.devtools.build.lib.skyframe

/** SkyFunction for [PackageLookupValue]s.  */
class PackageLookupFunction(
    deletedPackages: AtomicReference<com.google.common.collect.ImmutableSet<PackageIdentifier?>?>,
    crossRepositoryLabelViolationStrategy: CrossRepositoryLabelViolationStrategy?,
    buildFilesByPriority: com.google.common.collect.ImmutableList<BuildFileName>
) : SkyFunction {
    /** Lists possible ways to handle a package label which crosses into a new repository.  */
    enum class CrossRepositoryLabelViolationStrategy {
        /** Ignore the violation.  */
        IGNORE,

        /** Generate an error.  */
        ERROR
    }

    private val deletedPackages: AtomicReference<com.google.common.collect.ImmutableSet<PackageIdentifier?>?>
    private val crossRepositoryLabelViolationStrategy: CrossRepositoryLabelViolationStrategy?
    private val buildFilesByPriority: com.google.common.collect.ImmutableList<BuildFileName>

    init {
        this.deletedPackages = deletedPackages
        this.crossRepositoryLabelViolationStrategy = crossRepositoryLabelViolationStrategy
        this.buildFilesByPriority = buildFilesByPriority
    }

    private class State : SkyKeyComputeState {
        private var packagePathEntryPos = 0
        private var buildFileNamePos = 0
    }

    @Throws(PackageLookupFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val pkgLocator: PathPackageLocator? = PrecomputedValue.Companion.PATH_PACKAGE_LOCATOR.get(env)

        val packageKey: PackageIdentifier = skyKey.argument() as PackageIdentifier

        val packageNameErrorMsg: String? =
            LabelValidator.validatePackageName(packageKey.getPackageFragment().getPathString())
        if (packageNameErrorMsg != null) {
            return PackageLookupValue.Companion.invalidPackageName(
                "Invalid package name '" + packageKey + "': " + packageNameErrorMsg
            )
        }

        if (deletedPackages.get().contains(packageKey)) {
            return PackageLookupValue.Companion.DELETED_PACKAGE_VALUE
        }

        if (!packageKey.getRepository().isMain()) {
            return computeExternalPackageLookupValue(skyKey, env, packageKey)
        }

        if (packageKey.equals(LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER)) {
            return PackageLookupValue.Companion.NO_BUILD_FILE_VALUE
        }

        // Check .bazelignore file under main repository.
        val ignoredPatternsValue: IgnoredSubdirectoriesValue? =
            env.getValue(IgnoredSubdirectoriesValue.Companion.key()) as IgnoredSubdirectoriesValue?
        if (ignoredPatternsValue == null) {
            return null
        }

        val packageFragment: PathFragment? = packageKey.getPackageFragment()
        if (ignoredPatternsValue.asIgnoredSubdirectories().matchingEntry(packageFragment) != null) {
            return PackageLookupValue.Companion.DELETED_PACKAGE_VALUE
        }

        return findPackageByBuildFile(env, pkgLocator, packageKey)
    }

    @Throws(PackageLookupFunctionException::class, java.lang.InterruptedException::class)
    private fun findPackageByBuildFile(
        env: SkyFunction.Environment, pkgLocator: PathPackageLocator, packageKey: PackageIdentifier
    ): PackageLookupValue? {
        val state: State =
            env.getState<State>(java.util.function.Supplier { com.google.devtools.build.lib.skyframe.PackageLookupFunction.State() })
        while (state.packagePathEntryPos < pkgLocator.getPathEntries().size()) {
            while (state.buildFileNamePos < buildFilesByPriority.size) {
                val packagePathEntry: Root = pkgLocator.getPathEntries().get(state.packagePathEntryPos)
                val buildFileName: BuildFileName = buildFilesByPriority.get(state.buildFileNamePos)
                val result: PackageLookupValue? =
                    getPackageLookupValue(env, packagePathEntry, packageKey, buildFileName)
                if (result == null) {
                    return null
                }
                if (result !== PackageLookupValue.Companion.NO_BUILD_FILE_VALUE) {
                    return result
                }
                state.buildFileNamePos++
            }
            state.buildFileNamePos = 0
            state.packagePathEntryPos++
        }
        return PackageLookupValue.Companion.NO_BUILD_FILE_VALUE
    }

    @Throws(java.lang.InterruptedException::class, PackageLookupFunctionException::class)
    private fun getPackageLookupValue(
        env: SkyFunction.Environment,
        packagePathEntry: Root,
        packageIdentifier: PackageIdentifier,
        buildFileName: BuildFileName
    ): PackageLookupValue? {
        val buildFileFragment: PathFragment = buildFileName.getBuildFileFragment(packageIdentifier)

        if (crossRepositoryLabelViolationStrategy == CrossRepositoryLabelViolationStrategy.ERROR) {
            // Is this path part of a local repository?
            val currentPath: RootedPath =
                RootedPath.toRootedPath(packagePathEntry, buildFileFragment.getParentDirectory())
            val repositoryLookupKey: SkyKey? = LocalRepositoryLookupValue.Companion.key(currentPath)

            // TODO(jcater): Consider parallelizing these lookups.
            val localRepository: LocalRepositoryLookupValue?
            try {
                localRepository =
                    env.getValueOrThrow<E?>(
                        repositoryLookupKey,
                        ErrorDeterminingRepositoryException::class.java
                    ) as LocalRepositoryLookupValue?
                if (localRepository == null) {
                    return null
                }
            } catch (e: ErrorDeterminingRepositoryException) {
                // If the directory selected isn't part of a repository, that's an error.
                // TODO(katre): Improve the error message given here.
                throw PackageLookupFunctionException(
                    BuildFileNotFoundException(
                        packageIdentifier,
                        "Unable to determine the local repository for directory "
                                + currentPath.asPath().getPathString()
                    ),
                    Transience.PERSISTENT
                )
            }

            if (localRepository.exists()
                && !localRepository.getRepository().equals(packageIdentifier.getRepository())
            ) {
                // There is a repository mismatch, this is an error.
                // The correct package path is the one originally given, minus the part that is the local
                // repository.
                var pathToRequestedPackage: PathFragment = packageIdentifier.getSourceRoot()
                val localRepositoryPath: PathFragment = localRepository.getPath()
                if (localRepositoryPath.isAbsolute()) {
                    // We need the package path to also be absolute.
                    pathToRequestedPackage =
                        packagePathEntry.getRelative(pathToRequestedPackage).asFragment()
                }
                val remainingPath: PathFragment? = pathToRequestedPackage.relativeTo(localRepositoryPath)
                val correctPackage: PackageIdentifier? =
                    PackageIdentifier.create(localRepository.getRepository(), remainingPath)
                return PackageLookupValue.Companion.incorrectRepositoryReference(packageIdentifier, correctPackage)
            }

            // There's no local repository, keep going.
        } else {
            // Future-proof against adding future values to CrossRepositoryLabelViolationStrategy.
            com.google.common.base.Preconditions.checkState(
                crossRepositoryLabelViolationStrategy == CrossRepositoryLabelViolationStrategy.IGNORE,
                crossRepositoryLabelViolationStrategy
            )
        }

        // Check for the existence of the build file.
        val buildFileRootedPath: RootedPath = RootedPath.toRootedPath(packagePathEntry, buildFileFragment)
        val fileValue: FileValue? = getFileValue(buildFileRootedPath, env, packageIdentifier)
        if (fileValue == null) {
            return null
        }

        if (fileValue.isFile()) {
            return PackageLookupValue.Companion.success(buildFileRootedPath.getRoot(), buildFileName)
        }

        return PackageLookupValue.Companion.NO_BUILD_FILE_VALUE
    }

    /**
     * Gets a PackageLookupValue from a different Bazel repository.
     * 
     * 
     * To do this, it looks up the "external" package and finds a path mapping for the repository
     * name.
     */
    @Throws(PackageLookupFunctionException::class, java.lang.InterruptedException::class)
    private fun computeExternalPackageLookupValue(
        skyKey: SkyKey, env: SkyFunction.Environment, packageIdentifier: PackageIdentifier?
    ): PackageLookupValue? {
        val id: PackageIdentifier = skyKey.argument() as PackageIdentifier
        val repositoryKey: SkyKey? = RepositoryDirectoryValue.key(id.getRepository())
        val repositoryValue: RepositoryDirectoryValue?
        try {
            repositoryValue =
                env.getValueOrThrow<E1?, E2?, E3?, E4?>(
                    repositoryKey,
                    NoSuchPackageException::class.java,
                    IOException::class.java,
                    net.starlark.java.eval.EvalException::class.java,
                    AlreadyReportedException::class.java
                ) as RepositoryDirectoryValue?
            if (repositoryValue == null) {
                return null
            }
        } catch (e: NoSuchPackageException) {
            throw PackageLookupFunctionException(
                BuildFileNotFoundException(id, e.getMessage()), Transience.PERSISTENT
            )
        } catch (e: IOException) {
            throw PackageLookupFunctionException(
                RepositoryFetchException(id, e.getMessage()), Transience.PERSISTENT
            )
        } catch (e: net.starlark.java.eval.EvalException) {
            throw PackageLookupFunctionException(
                RepositoryFetchException(id, e.getMessage()), Transience.PERSISTENT
            )
        } catch (e: AlreadyReportedException) {
            throw PackageLookupFunctionException(
                RepositoryFetchException(id, e.getMessage()), Transience.PERSISTENT
            )
        }
        if (repositoryValue is) {
            return NoRepositoryPackageLookupValue(id.getRepository(), errorMsg)
        }

        // Check .bazelignore file after fetching the external repository.
        val ignoredPatternsValue: IgnoredSubdirectoriesValue? =
            env.getValue(IgnoredSubdirectoriesValue.Companion.key(id.getRepository())) as IgnoredSubdirectoriesValue?
        if (ignoredPatternsValue == null) {
            return null
        }

        val packageFragment: PathFragment? = id.getPackageFragment()
        if (ignoredPatternsValue.asIgnoredSubdirectories().matchingEntry(packageFragment) != null) {
            return PackageLookupValue.Companion.DELETED_PACKAGE_VALUE
        }

        val root: Root? = (repositoryValue as Success).root()

        // This checks for the build file names in the correct precedence order.
        for (buildFileName in buildFilesByPriority) {
            val buildFileFragment: PathFragment =
                id.getPackageFragment().getRelative(buildFileName.getFilenameFragment())
            val buildFileRootedPath: RootedPath = RootedPath.toRootedPath(root, buildFileFragment)
            val fileValue: FileValue? = getFileValue(buildFileRootedPath, env, packageIdentifier)
            if (fileValue == null) {
                return null
            }

            if (fileValue.isFile()) {
                return PackageLookupValue.Companion.success(root, buildFileName)
            }
        }

        return PackageLookupValue.Companion.NO_BUILD_FILE_VALUE
    }

    /**
     * Used to declare all the exception types that can be wrapped in the exception thrown by [ ][PackageLookupFunction.compute]. Note that [InconsistentFilesystemException] can only be
     * thrown during target pattern parsing because of Bazel's end-to-end behavior: [ ] throws [InconsistentFilesystemException] only if a
     * cached-on-this-evaluation directory listing said that an entry was a file but the stat had no
     * result. However, the only time Bazel lists a directory without first accessing its
     * BUILD/BUILD.bazel file is during evaluation of a recursive target pattern (like foo/...).
     */
    private class PackageLookupFunctionException : SkyFunctionException {
        internal constructor(e: BuildFileNotFoundException?, transience: Transience?) : super(e, transience)

        internal constructor(e: RepositoryFetchException?, transience: Transience?) : super(e, transience)

        internal constructor(e: InconsistentFilesystemException?, transience: Transience?) : super(e, transience)
    }

    companion object {
        /**
         * For a package identifier `packageKey` such that the compute for `PackageLookupValue.key(packageKey)` returned `NO_BUILD_FILE_VALUE`, provide a
         * human-readable error message with more details on where we searched for the package.
         */
        @Throws(java.lang.InterruptedException::class)
        fun explainNoBuildFileValue(packageKey: PackageIdentifier, env: SkyFunction.Environment): String {
            val educationalMessage = "Add a BUILD file to a directory to mark it as a package."
            if (packageKey.getRepository().isMain()) {
                val pkgLocator: PathPackageLocator? = PrecomputedValue.Companion.PATH_PACKAGE_LOCATOR.get(env)
                val message: java.lang.StringBuilder = java.lang.StringBuilder()
                message.append("BUILD file not found in any of the following directories. ")
                message.append(educationalMessage)
                for (root in pkgLocator.getPathEntries()) {
                    message
                        .append("\n - ")
                        .append(
                            if (pkgLocator.getPathEntries().size() === 1)
                                packageKey.getPackageFragment().getPathString()
                            else
                                root.asPath().getRelative(packageKey.getPackageFragment()).getPathString()
                        )
                }
                return message.toString()
            } else {
                return ("BUILD file not found in directory '"
                        + packageKey.getPackageFragment()
                        + "' of external repository "
                        + packageKey.getRepository()
                        + ". "
                        + educationalMessage)
            }
        }

        @Throws(PackageLookupFunctionException::class, java.lang.InterruptedException::class)
        private fun getFileValue(
            fileRootedPath: RootedPath, env: SkyFunction.Environment, packageIdentifier: PackageIdentifier?
        ): FileValue? {
            val basename: String = fileRootedPath.asPath().getBaseName()
            val fileSkyKey: SkyKey? = FileValue.key(fileRootedPath)
            val fileValue: FileValue?
            try {
                fileValue = env.getValueOrThrow<IOException?>(fileSkyKey, IOException::class.java) as FileValue?
            } catch (e: InconsistentFilesystemException) {
                // This error is not transient from the perspective of the PackageLookupFunction.
                throw PackageLookupFunctionException(e, Transience.PERSISTENT)
            } catch (e: FileSymlinkException) {
                val message =
                    (e.getMessage()
                            + " detected while trying to find "
                            + basename
                            + " file "
                            + fileRootedPath.asPath())
                throw PackageLookupFunctionException(
                    BuildFileNotFoundException(
                        packageIdentifier,
                        message,
                        DetailedExitCode.of(
                            FailureDetail.newBuilder()
                                .setMessage(message)
                                .setPackageLoading(
                                    PackageLoading.newBuilder()
                                        .setCode(Code.SYMLINK_CYCLE_OR_INFINITE_EXPANSION)
                                )
                                .build()
                        )
                    ),
                    Transience.PERSISTENT
                )
            } catch (e: DetailedIOException) {
                val message =
                    ("IO errors while looking for "
                            + basename
                            + " file reading "
                            + fileRootedPath.asPath()
                            + ": "
                            + e.getMessage())
                throw PackageLookupFunctionException(
                    BuildFileNotFoundException(
                        packageIdentifier,
                        message,
                        DetailedExitCode.of(
                            e.getDetailedExitCode().getFailureDetail().toBuilder()
                                .setMessage(message)
                                .build()
                        )
                    ),
                    e.getTransience()
                )
            } catch (e: IOException) {
                val message =
                    ("IO errors while looking for "
                            + basename
                            + " file reading "
                            + fileRootedPath.asPath()
                            + ": "
                            + e.getMessage())
                throw PackageLookupFunctionException(
                    BuildFileNotFoundException(
                        packageIdentifier,
                        message,
                        DetailedExitCode.of(
                            FailureDetail.newBuilder()
                                .setMessage(message)
                                .setPackageLoading(
                                    PackageLoading.newBuilder().setCode(Code.OTHER_IO_EXCEPTION)
                                )
                                .build()
                        )
                    ),
                    Transience.PERSISTENT
                )
            }
            return fileValue
        }
    }
}
