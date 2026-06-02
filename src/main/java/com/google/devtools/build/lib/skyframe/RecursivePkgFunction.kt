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
 * RecursivePkgFunction builds up the set of packages underneath a given directory transitively.
 * 
 * 
 * Example: foo/BUILD, foo/sub/x, foo/subpkg/BUILD would yield transitive packages "foo" and
 * "foo/subpkg".
 */
class RecursivePkgFunction(directories: BlazeDirectories?) : SkyFunction {
    private val directories: BlazeDirectories?

    init {
        this.directories = directories
    }

    /**
     * N.B.: May silently throw [com.google.devtools.build.lib.packages.NoSuchPackageException]
     * in nokeep_going mode!
     */
    @Throws(java.lang.InterruptedException::class, ProcessPackageDirectorySkyFunctionException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment?): SkyValue? {
        return MyTraversalFunction().visitDirectory(skyKey.argument() as RecursivePkgKey?, env)
    }

    private inner class MyTraversalFunction

        : RecursiveDirectoryTraversalFunction<MyPackageDirectoryConsumer?, RecursivePkgValue?>(directories) {
        val initialConsumer: MyPackageDirectoryConsumer
            get() = MyPackageDirectoryConsumer()

        override fun getSkyKeyForSubdirectory(
            repository: RepositoryName?,
            subdirectory: RootedPath?,
            excludedSubdirectoriesBeneathSubdirectory: IgnoredSubdirectories?
        ): SkyKey? {
            return RecursivePkgValue.Companion.key(
                repository, subdirectory, excludedSubdirectoriesBeneathSubdirectory
            )
        }

        override fun aggregateWithSubdirectorySkyValues(
            consumer: MyPackageDirectoryConsumer, subdirectorySkyValues: MutableMap<SkyKey?, SkyValue>
        ): RecursivePkgValue? {
            // Aggregate the transitive subpackages.
            for (childValue in subdirectorySkyValues.values()) {
                consumer.addTransitivePackages((childValue as RecursivePkgValue).getPackages())
                if ((childValue as RecursivePkgValue).hasErrors()) {
                    consumer.addTransitiveErrors()
                }
            }
            return consumer.createRecursivePkgValue()
        }
    }

    private class MyPackageDirectoryConsumer

        : PackageDirectoryConsumer {
        private val packages: NestedSetBuilder<String?> = NestedSetBuilder.newBuilder(Order.STABLE_ORDER)
        private var hasErrors = false

        override fun notePackage(pkgPath: PathFragment) {
            packages.add(pkgPath.getPathString())
        }

        override fun notePackageError(noSuchPackageExceptionErrorMessage: String?) {
            hasErrors = true
        }

        fun addTransitivePackages(transitivePackages: NestedSet<String?>?) {
            packages.addTransitive(transitivePackages)
        }

        fun addTransitiveErrors() {
            hasErrors = true
        }

        fun createRecursivePkgValue(): RecursivePkgValue? {
            return RecursivePkgValue.Companion.create(packages, hasErrors)
        }
    }
}
