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

import com.google.devtools.build.lib.analysis.BlazeDirectories

/**
 * RecursiveDirectoryTraversalFunction allows for a custom recursive traversal of the subdirectories
 * of a directory, building up a value based on package existence and results of the recursive
 * traversal.
 * 
 * 
 * It attempts to ignore package-definition-related errors, and even file symlink cycles, which
 * means that in keep-going mode, it will produce a result even if the traversed directory contains
 * such errors. In no-keep-going mode, such exceptions will shut down the build, so callers must be
 * prepared to handle [com.google.devtools.build.lib.packages.NoSuchPackageException] and
 * [com.google.devtools.build.lib.io.FileSymlinkException].
 * 
 * 
 * It will always eagerly fail on exceptions indicating filesystem inconsistencies, since they
 * indicate bad disk that may make results unreliable.
 */
abstract class RecursiveDirectoryTraversalFunction<ConsumerT : PackageDirectoryConsumer?, ReturnT> protected constructor(
    directories: BlazeDirectories?
) {
    private val directories: BlazeDirectories?

    init {
        this.directories = directories
    }

    /** Called by [.visitDirectory], which will then recursive traverse the directory.  */
    @com.google.errorprone.annotations.ForOverride
    @Throws(java.lang.InterruptedException::class, ProcessPackageDirectorySkyFunctionException::class)
    protected fun getProcessPackageDirectoryResult(
        recursivePkgKey: RecursivePkgKey, env: SkyFunction.Environment
    ): ProcessPackageDirectoryResult? {
        return ProcessPackageDirectory(
            directories,
            SkyKeyTransformer { repository: RepositoryName?, subdirectory: RootedPath?, excludedSubdirectoriesBeneathSubdirectory: IgnoredSubdirectories? ->
                this.getSkyKeyForSubdirectory(
                    repository,
                    subdirectory,
                    excludedSubdirectoriesBeneathSubdirectory
                )
            })
            .getPackageExistenceAndSubdirDeps(
                recursivePkgKey.getRootedPath(),
                recursivePkgKey.getRepositoryName(),
                recursivePkgKey.getExcludedPaths(),
                env
            )
    }

    /**
     * Called by [.visitDirectory], which will next call [ ][PackageDirectoryConsumer.notePackage] if the `recursivePkgKey` specifies a directory with
     * a package, and which will lastly be provided to [.aggregateWithSubdirectorySkyValues] to
     * compute the `TReturn` value returned by [.visitDirectory].
     */
    protected abstract val initialConsumer: ConsumerT?

    /**
     * Called by [.visitDirectory] to get the [SkyKey]s associated with recursive
     * computation in subdirectories of `subdirectory`, excluding directories in `excludedSubdirectoriesBeneathSubdirectory`, all of which must be proper subdirectories of
     * `subdirectory`.
     */
    protected abstract fun getSkyKeyForSubdirectory(
        repository: RepositoryName?,
        subdirectory: RootedPath?,
        excludedSubdirectoriesBeneathSubdirectory: IgnoredSubdirectories?
    ): SkyKey?

    /**
     * Called by [.visitDirectory] to compute the `TReturn` value it returns, as a
     * function of `consumer` and the [SkyValue]s computed for subdirectories of the
     * directory specified by `recursivePkgKey`, contained in `subdirectorySkyValues`.
     */
    protected abstract fun aggregateWithSubdirectorySkyValues(
        consumer: ConsumerT?, subdirectorySkyValues: MutableMap<SkyKey?, SkyValue?>?
    ): ReturnT?

    /**
     * A type of consumer used by [.visitDirectory] as it checks for a package in the directory
     * specified by `recursivePkgKey`; if such a package exists, [.notePackage] is called.
     * 
     * 
     * The consumer is then provided to [.aggregateWithSubdirectorySkyValues] to compute the
     * value returned by [.visitDirectory].
     */
    interface PackageDirectoryConsumer {
        /** Called iff the directory contains a package.  */
        @Throws(java.lang.InterruptedException::class)
        fun notePackage(pkgPath: PathFragment?)

        /**
         * Called iff the directory contains a BUILD file but *not* a package, which can happen under
         * the following circumstances:
         * 
         * 
         *  1. The BUILD file contains a Starlark load statement that is in error
         *  1. TODO(mschaller), not yet implemented: The BUILD file is a symlink that points into a
         * cycle
         * 
         */
        fun notePackageError(noSuchPackageExceptionErrorMessage: String?)
    }

    /**
     * Uses [.getProcessPackageDirectoryResult] to look for a package in the directory specified
     * by `recursivePkgKey`, does some work as specified by [PackageDirectoryConsumer] if
     * such a package exists, then recursively does work in each non-excluded subdirectory as
     * specified by [.getSkyKeyForSubdirectory], and finally aggregates the [ ] value along with values from each subdirectory as specified by [ ][.aggregateWithSubdirectorySkyValues], and returns that aggregation.
     * 
     * 
     * Returns null if `env.valuesMissing()` is true, checked after each call to one of
     * [RecursiveDirectoryTraversalFunction]'s abstract methods that were given `env`.
     * 
     * 
     * Will propagate [com.google.devtools.build.lib.packages.NoSuchPackageException] during
     * a no-keep-going evaluation
     */
    @Throws(java.lang.InterruptedException::class, ProcessPackageDirectorySkyFunctionException::class)
    fun visitDirectory(recursivePkgKey: RecursivePkgKey, env: SkyFunction.Environment): ReturnT? {
        val processPackageDirectoryResult: ProcessPackageDirectoryResult? =
            getProcessPackageDirectoryResult(recursivePkgKey, env)
        if (env.valuesMissing()) {
            return null
        }

        val childDeps: Iterable<SkyKey?> = processPackageDirectoryResult.getChildDeps()
        val consumer = this.initialConsumer

        val dependentSkyValues: SkyframeLookupResult
        if (processPackageDirectoryResult.packageExists()) {
            val rootRelativePath: PathFragment = recursivePkgKey.getRootedPath().getRootRelativePath()
            val packageErrorMessageKey: SkyKey =
                PackageErrorMessageValue.Companion.key(
                    PackageIdentifier.create(recursivePkgKey.getRepositoryName(), rootRelativePath)
                )
            // In a no-keep-going build during error bubbling, PackageErrorMessageFunction may throw a
            // NoSuchPackageException. Since we don't catch such an exception here, this SkyFunction will
            // return immediately with a missing value, and the NoSuchPackageException will propagate up.
            dependentSkyValues =
                env.getValuesAndExceptions(
                    com.google.common.collect.Iterables.concat<SkyKey?>(
                        com.google.common.collect.ImmutableList.of<SkyKey?>(
                            packageErrorMessageKey
                        ), childDeps
                    )
                )
            if (env.valuesMissing()) {
                return null
            }
            val pkgErrorMessageValue: PackageErrorMessageValue? =
                dependentSkyValues.get(packageErrorMessageKey) as PackageErrorMessageValue?
            if (pkgErrorMessageValue == null) {
                return null
            }
            when (pkgErrorMessageValue.getResult()) {
                com.google.devtools.build.lib.skyframe.PackageErrorMessageValue.Result.NO_ERROR -> consumer.notePackage(
                    rootRelativePath
                )

                com.google.devtools.build.lib.skyframe.PackageErrorMessageValue.Result.ERROR -> {
                    env.getListener()
                        .handle(Event.error("package contains errors: " + rootRelativePath.getPathString()))
                    consumer.notePackage(rootRelativePath)
                }

                com.google.devtools.build.lib.skyframe.PackageErrorMessageValue.Result.NO_SUCH_PACKAGE_EXCEPTION -> {
                    // The package had errors, but don't fail-fast as there might be subpackages below the
                    // current directory.
                    val msg: String? = pkgErrorMessageValue.getNoSuchPackageExceptionMessage()
                    env.getListener().handle(Event.error(msg))
                    consumer.notePackageError(msg)
                }

                else -> throw java.lang.IllegalStateException(pkgErrorMessageValue.getResult().toString())
            }
        } else {
            dependentSkyValues = env.getValuesAndExceptions(childDeps)
            if (env.valuesMissing()) {
                return null
            }
        }
        val subdirectorySkyValuesFromDeps: com.google.common.collect.ImmutableMap.Builder<SkyKey?, SkyValue?> =
            com.google.common.collect.ImmutableMap.builderWithExpectedSize<SkyKey?, SkyValue?>(
                com.google.common.collect.Iterables.size(
                    childDeps
                )
            )
        for (skyKey in childDeps) {
            val skyValue: SkyValue? = dependentSkyValues.get(skyKey)
            if (skyValue == null) {
                return null
            }
            subdirectorySkyValuesFromDeps.put(skyKey, skyValue)
        }

        subdirectorySkyValuesFromDeps.putAll(
            processPackageDirectoryResult.getAdditionalValuesToAggregate()
        )
        return aggregateWithSubdirectorySkyValues(
            consumer, subdirectorySkyValuesFromDeps.buildOrThrow()
        )
    }
}
