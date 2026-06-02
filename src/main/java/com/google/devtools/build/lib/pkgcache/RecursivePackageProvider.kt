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

import com.google.devtools.build.lib.cmdline.BatchCallback.SafeBatchCallback

/** Support for resolving `package/...` target patterns.  */
interface RecursivePackageProvider : PackageProvider {
    /**
     * Calls the supplied callback with the name of each package under a given directory, as soon as
     * that package is identified.
     * 
     * 
     * Packages yielded by this method and passed into [.bulkGetPackages] are expected to
     * return successful [Package] values.
     * 
     * @param results callback invoked *from a single thread* for every eligible, loaded
     * package as it is discovered
     * @param eventHandler any errors emitted during package lookup and loading for `directory`
     * and non-excluded directories beneath it will be reported here
     * @param directory a [RootedPath] specifying the directory to search
     * @param ignoredSubdirectories a set of [PathFragment]s specifying transitive
     * subdirectories that are ignored. `directory` must not be a subdirectory of any of
     * these
     * @param excludedSubdirectories a set of [PathFragment]s specifying transitive
     * subdirectories that are excluded from this traversal. Different from `ignoredSubdirectories` only in that these directories should not be embedded in any `SkyKey`s that are created during the traversal, instead filtered out later
     */
    @Throws(
        java.lang.InterruptedException::class,
        com.google.devtools.build.lib.query2.engine.QueryException::class,
        NoSuchPackageException::class,
        ProcessPackageDirectoryException::class
    )
    fun streamPackagesUnderDirectory(
        results: SafeBatchCallback<PackageIdentifier?>?,
        eventHandler: ExtendedEventHandler?,
        repository: RepositoryName?,
        directory: PathFragment?,
        ignoredSubdirectories: IgnoredSubdirectories?,
        excludedSubdirectories: com.google.common.collect.ImmutableSet<PathFragment?>?
    )

    /**
     * Returns the [Package] corresponding to each Package in "pkgIds". If any of the packages
     * does not exist (e.g. `isPackage(pkgIds)` returns false), throws a [ ].
     * 
     * 
     * The returned package may contain lexical/grammatical errors, in which case `
     * pkg.containsErrors() == true`. Such packages may be missing some rules. Any rules that
     * are present may soundly be used for builds, though.
     * 
     * @param pkgIds an Iterable of PackageIdentifier objects.
     * @throws NoSuchPackageException if any package could not be found.
     * @throws InterruptedException if the package loading was interrupted.
     */
    @Throws(NoSuchPackageException::class, java.lang.InterruptedException::class)
    fun bulkGetPackages(
        eventHandler: ExtendedEventHandler?, pkgIds: Iterable<PackageIdentifier?>
    ): MutableMap<PackageIdentifier?, com.google.devtools.build.lib.packages.Package?> {
        val builder: com.google.common.collect.ImmutableMap.Builder<PackageIdentifier?, com.google.devtools.build.lib.packages.Package?> =
            com.google.common.collect.ImmutableMap.builder<PackageIdentifier?, com.google.devtools.build.lib.packages.Package?>()
        for (pkgId in pkgIds) {
            builder.put(pkgId, getPackage(eventHandler, pkgId))
        }
        return builder.buildOrThrow()
    }

    /**
     * Bulk variant of [.isPackage]. Given some `pkgIds`, returns the set of the [ ] for which there are existing packages.
     */
    @Throws(InconsistentFilesystemException::class, java.lang.InterruptedException::class)
    fun bulkIsPackage(
        eventHandler: ExtendedEventHandler?, pkgIds: Iterable<PackageIdentifier?>
    ): MutableSet<PackageIdentifier?> {
        val builder: com.google.common.collect.ImmutableSet.Builder<PackageIdentifier?> =
            com.google.common.collect.ImmutableSet.builder<PackageIdentifier?>()
        for (pkgId in pkgIds) {
            if (isPackage(eventHandler, pkgId)) {
                builder.add(pkgId)
            }
        }
        return builder.build()
    }

    /**
     * A [RecursivePackageProvider] in terms of a map of pre-fetched, fully macro-expanded
     * packages.
     * 
     * 
     * Note that this class implements neither [.streamPackagesUnderDirectory] nor [ ][.bulkGetPackages], so it can only be used for use cases that do not call either of these
     * methods. When used for target pattern resolution, it can be used to resolve SINGLE_TARGET and
     * TARGETS_IN_PACKAGE patterns by pre-fetching the corresponding packages. It can also be used to
     * resolve PATH_AS_TARGET patterns either by finding the outermost package or by pre-fetching all
     * possible packages.
     * 
     * @see com.google.devtools.build.lib.cmdline.TargetPattern.Type
     */
    // TODO(bazel-team): should we avoid forcing symbolic macro expansion, and use a backing map of
    // packageoids-for-build-file instead?
    class PackageBackedRecursivePackageProvider(packages: MutableMap<PackageIdentifier?, com.google.devtools.build.lib.packages.Package>) :
        RecursivePackageProvider {
        private val packages: MutableMap<PackageIdentifier?, com.google.devtools.build.lib.packages.Package>

        init {
            this.packages = packages
        }

        @Throws(NoSuchPackageException::class)
        override fun getPackage(
            eventHandler: ExtendedEventHandler?,
            packageName: PackageIdentifier?
        ): com.google.devtools.build.lib.packages.Package {
            val pkg: com.google.devtools.build.lib.packages.Package = packages.get(packageName)
            if (pkg == null) {
                throw NoSuchPackageException(packageName, "")
            }
            return pkg
        }

        @Throws(NoSuchPackageException::class)
        override fun getBuildFile(eventHandler: ExtendedEventHandler?, packageName: PackageIdentifier?): InputFile? {
            return getPackage(eventHandler, packageName).getBuildFile()
        }

        override fun isPackage(eventHandler: ExtendedEventHandler?, packageName: PackageIdentifier?): Boolean {
            return packages.containsKey(packageName)
        }

        @Throws(NoSuchPackageException::class, NoSuchTargetException::class)
        override fun getTarget(
            eventHandler: ExtendedEventHandler?,
            label: Label
        ): com.google.devtools.build.lib.packages.Target? {
            return getPackage(eventHandler, label.getPackageIdentifier()).getTarget(label.name)
        }

        override fun streamPackagesUnderDirectory(
            results: SafeBatchCallback<PackageIdentifier?>?,
            eventHandler: ExtendedEventHandler?,
            repository: RepositoryName?,
            directory: PathFragment?,
            ignoredSubdirectories: IgnoredSubdirectories?,
            excludedSubdirectories: com.google.common.collect.ImmutableSet<PathFragment?>?
        ) {
            throw java.lang.UnsupportedOperationException()
        }
    }
}
