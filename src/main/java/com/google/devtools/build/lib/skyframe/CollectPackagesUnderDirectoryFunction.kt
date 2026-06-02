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

import com.google.devtools.build.lib.analysis.BlazeDirectories

/**
 * Computes [CollectPackagesUnderDirectoryValue] which describes whether the directory is a
 * package, or would have been a package but for a package loading error, and whether non-excluded
 * packages (or errors) exist below each of the directory's subdirectories. As a side effect, loads
 * all of these packages, in order to interleave the disk-bound work of checking for directories and
 * the CPU-bound work of package loading.
 */
class CollectPackagesUnderDirectoryFunction(directories: BlazeDirectories?) : SkyFunction {
    private val directories: BlazeDirectories?

    init {
        this.directories = directories
    }

    @Throws(java.lang.InterruptedException::class, ProcessPackageDirectorySkyFunctionException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment?): SkyValue? {
        return com.google.devtools.build.lib.skyframe.CollectPackagesUnderDirectoryFunction.MyTraversalFunction(
            directories
        )
            .visitDirectory(skyKey.argument() as RecursivePkgKey?, env)
    }

    /** The [RecursiveDirectoryTraversalFunction] used by our traversal.  */
    class MyTraversalFunction
        (directories: BlazeDirectories?) :
        RecursiveDirectoryTraversalFunction<MyPackageDirectoryConsumer?, CollectPackagesUnderDirectoryValue?>(
            directories
        ) {
        val initialConsumer: MyPackageDirectoryConsumer
            get() = com.google.devtools.build.lib.skyframe.CollectPackagesUnderDirectoryFunction.MyPackageDirectoryConsumer()

        override fun getSkyKeyForSubdirectory(
            repository: RepositoryName?,
            subdirectory: RootedPath?,
            excludedSubdirectoriesBeneathSubdirectory: IgnoredSubdirectories?
        ): SkyKey? {
            return CollectPackagesUnderDirectoryValue.Companion.key(
                repository, subdirectory, excludedSubdirectoriesBeneathSubdirectory
            )
        }

        override fun aggregateWithSubdirectorySkyValues(
            consumer: MyPackageDirectoryConsumer, subdirectorySkyValues: MutableMap<SkyKey, SkyValue?>
        ): CollectPackagesUnderDirectoryValue? {
            // Aggregate the child subdirectory package state.
            val builder: com.google.common.collect.ImmutableList.Builder<RootedPath?> =
                com.google.common.collect.ImmutableList.builder<RootedPath?>()
            for (key in subdirectorySkyValues.keySet()) {
                val recursivePkgKey: RecursivePkgKey = key.argument() as RecursivePkgKey
                val collectPackagesValue: CollectPackagesUnderDirectoryValue =
                    subdirectorySkyValues.get(key) as CollectPackagesUnderDirectoryValue

                val packagesOrErrorsInSubdirectory =
                    collectPackagesValue.isDirectoryPackage()
                            || collectPackagesValue.getErrorMessage() != null || !collectPackagesValue
                        .getSubdirectoryTransitivelyContainsPackagesOrErrors()
                        .isEmpty()

                if (packagesOrErrorsInSubdirectory) {
                    builder.add(recursivePkgKey.getRootedPath())
                }
            }
            val subdirectories: com.google.common.collect.ImmutableList<RootedPath?> = builder.build()
            val errorMessage = consumer.errorMessage
            if (errorMessage != null) {
                return CollectPackagesUnderDirectoryValue.Companion.ofError(errorMessage, subdirectories)
            }
            return CollectPackagesUnderDirectoryValue.Companion.ofNoError(
                consumer.isDirectoryPackage, subdirectories
            )
        }
    }

    private class MyPackageDirectoryConsumer

        : PackageDirectoryConsumer {
        var isDirectoryPackage: Boolean = false
            private set
        var errorMessage: String? = null
            private set

        override fun notePackage(pkgPath: PathFragment?) {
            isDirectoryPackage = true
        }

        override fun notePackageError(noSuchPackageExceptionErrorMessage: String?) {
            this.errorMessage = noSuchPackageExceptionErrorMessage
        }
    }
}
