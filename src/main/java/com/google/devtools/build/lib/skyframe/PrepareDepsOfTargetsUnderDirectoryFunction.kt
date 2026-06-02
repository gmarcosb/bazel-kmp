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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.analysis.BlazeDirectories

/**
 * Ensures the graph contains the targets in the directory's package, if any, and in the
 * non-excluded packages in its subdirectories, and all those targets' transitive dependencies,
 * after a successful evaluation.
 */
class PrepareDepsOfTargetsUnderDirectoryFunction internal constructor(directories: BlazeDirectories?) : SkyFunction {
    private val directories: BlazeDirectories?

    init {
        this.directories = directories
    }

    @Throws(java.lang.InterruptedException::class, ProcessPackageDirectorySkyFunctionException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val argument: PrepareDepsOfTargetsUnderDirectoryKey =
            skyKey.argument() as PrepareDepsOfTargetsUnderDirectoryKey
        val filteringPolicy: FilteringPolicy = argument.getFilteringPolicy()
        val recursivePkgKey: RecursivePkgKey = argument.getRecursivePkgKey()
        val processPackageDirectory: ProcessPackageDirectory =
            ProcessPackageDirectory(
                directories,
                SkyKeyTransformer { repository: RepositoryName?, subdirectory: RootedPath?, excludedSubdirectoriesBeneathSubdirectory: IgnoredSubdirectories? ->
                    PrepareDepsOfTargetsUnderDirectoryValue.key(
                        repository,
                        subdirectory,
                        excludedSubdirectoriesBeneathSubdirectory,
                        filteringPolicy
                    )
                })
        val packageExistenceAndSubdirDeps: ProcessPackageDirectoryResult? =
            processPackageDirectory.getPackageExistenceAndSubdirDeps(
                recursivePkgKey.getRootedPath(),
                recursivePkgKey.getRepositoryName(),
                recursivePkgKey.getExcludedPaths(),
                env
            )
        if (env.valuesMissing()) {
            return null
        }
        var keysToRequest: Iterable<SkyKey?> = packageExistenceAndSubdirDeps.getChildDeps()
        if (packageExistenceAndSubdirDeps.packageExists()) {
            keysToRequest =
                com.google.common.collect.Iterables.concat<T?>(
                    com.google.common.collect.ImmutableList.of<E?>(
                        CollectTargetsInPackageValue.key(
                            PackageIdentifier.create(
                                recursivePkgKey.getRepositoryName(),
                                recursivePkgKey.getRootedPath().getRootRelativePath()
                            ),
                            filteringPolicy
                        )
                    ),
                    keysToRequest
                )
        }
        return if (GraphTraversingHelper.declareDependenciesAndCheckIfValuesMissing<E1?, E2?>(
                env,
                keysToRequest,
                NoSuchPackageException::class.java,
                ProcessPackageDirectoryException::class.java
            )
        )
            null
        else
            PrepareDepsOfTargetsUnderDirectoryValue.INSTANCE
    }
}
