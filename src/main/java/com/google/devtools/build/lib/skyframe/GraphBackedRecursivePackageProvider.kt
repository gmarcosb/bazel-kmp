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

import com.google.devtools.build.lib.cmdline.BatchCallback.SafeBatchCallback

/**
 * A [com.google.devtools.build.lib.pkgcache.RecursivePackageProvider] backed by a [ ], used by `SkyQueryEnvironment` to look up the packages and targets matching
 * the universe that's been preloaded in `graph`.
 */
@ThreadSafe
class GraphBackedRecursivePackageProvider(
    graph: WalkableGraph?,
    universeTargetPatterns: UniverseTargetPattern?,
    pkgPath: PathPackageLocator,
    rootPackageExtractor: RootPackageExtractor
) : AbstractRecursivePackageProvider() {
    /**
     * Helper interface for clients of GraphBackedRecursivePackageProvider to indicate what universe
     * packages should be resolved in.
     * 
     * 
     * Client can either specify a fixed set of target patterns (using [.of]), or specify
     * that all targets are valid (using [.all]).
     */
    interface UniverseTargetPattern {
        fun patterns(): com.google.common.collect.ImmutableList<TargetPattern>?

        fun allowAll(): Boolean

        companion object {
            fun of(patterns: com.google.common.collect.ImmutableList<TargetPattern?>): UniverseTargetPattern {
                return object : UniverseTargetPattern {
                    override fun patterns(): com.google.common.collect.ImmutableList<TargetPattern?> {
                        return patterns
                    }

                    override fun allowAll(): Boolean {
                        return false
                    }
                }
            }

            @kotlin.jvm.JvmStatic
            fun all(): UniverseTargetPattern {
                return object : UniverseTargetPattern {
                    override fun patterns(): com.google.common.collect.ImmutableList<TargetPattern?> {
                        return com.google.common.collect.ImmutableList.of<TargetPattern?>()
                    }

                    override fun allowAll(): Boolean {
                        return true
                    }
                }
            }
        }
    }

    private val graph: WalkableGraph
    private val pkgRoots: com.google.common.collect.ImmutableList<Root?>?
    private val rootPackageExtractor: RootPackageExtractor
    private val universeTargetPatterns: UniverseTargetPattern

    init {
        this.graph = com.google.common.base.Preconditions.checkNotNull<WalkableGraph>(graph)
        this.pkgRoots = pkgPath.getPathEntries()
        this.universeTargetPatterns =
            com.google.common.base.Preconditions.checkNotNull<UniverseTargetPattern>(universeTargetPatterns)
        this.rootPackageExtractor = rootPackageExtractor
    }

    @Throws(NoSuchPackageException::class, java.lang.InterruptedException::class)
    public override fun getPackage(eventHandler: ExtendedEventHandler?, packageName: PackageIdentifier?): Package {
        val pkg: Package? = getPackageInternal(packageName)
        if (pkg != null) {
            return pkg
        }
        // If the package key does not exist in the graph, then it must not correspond to any package,
        // because the SkyQuery environment has already loaded the universe.
        throw BuildFileNotFoundException(packageName, "BUILD file not found on package path")
    }

    @Throws(NoSuchPackageException::class, java.lang.InterruptedException::class)
    private fun getPackageInternal(packageName: PackageIdentifier?): Package? {
        val pkgValue: PackageValue? = graph.getValue(packageName) as PackageValue?
        if (pkgValue != null) {
            return pkgValue.getPackage()
        }
        val nspe: NoSuchPackageException? = graph.getException(packageName) as NoSuchPackageException?
        if (nspe != null) {
            throw nspe
        }
        if (graph.isCycle(packageName)) {
            throw NoSuchPackageException(packageName, "Package depends on a cycle")
        }
        return null
    }

    @Throws(NoSuchPackageException::class, NoSuchPackagePieceException::class, java.lang.InterruptedException::class)
    public override fun getBuildFile(eventHandler: ExtendedEventHandler?, packageName: PackageIdentifier?): InputFile {
        // Do we have a full package?
        val pkg: Package? = getPackageInternal(packageName)
        if (pkg != null) {
            return pkg.getBuildFile()
        }

        // Do we have a PackagePiece.ForBuildFile?
        val packagePieceIdentifier: ForBuildFile =
            ForBuildFile(packageName)
        val packagePieceValue: ForBuildFile? =
            graph.getValue(packagePieceIdentifier) as ForBuildFile?
        if (packagePieceValue != null) {
            return packagePieceValue.getPackagePiece().getBuildFile()
        }
        val exception: java.lang.Exception? = graph.getException(packagePieceIdentifier)
        if (exception != null) {
            com.google.common.base.Throwables.throwIfInstanceOf<X?>(exception, NoSuchPackageException::class.java)
            com.google.common.base.Throwables.throwIfInstanceOf<X?>(exception, NoSuchPackagePieceException::class.java)
        }
        if (graph.isCycle(packagePieceIdentifier)) {
            throw NoSuchPackageException(packageName, "Package depends on a cycle")
        }

        // If both the package key and the package piece for BUILD file key do not exist in the graph,
        // then packageName must not correspond to any package, because the SkyQuery environment has
        // already loaded the universe.
        throw BuildFileNotFoundException(packageName, "BUILD file not found on package path")
    }

    @Throws(NoSuchPackageException::class, java.lang.InterruptedException::class)
    public override fun bulkGetPackages(
        eventHandler: ExtendedEventHandler?, pkgIds: Iterable<PackageIdentifier?>?
    ): com.google.common.collect.ImmutableMap<PackageIdentifier?, Package?> {
        val pkgKeys: com.google.common.collect.ImmutableSet<SkyKey> =
            com.google.common.collect.ImmutableSet.< E > copyOf < E >(pkgIds)

        val pkgResults: com.google.common.collect.ImmutableMap.Builder<PackageIdentifier?, Package?> =
            com.google.common.collect.ImmutableMap.builder<PackageIdentifier?, Package?>()
        val packages: MutableMap<SkyKey?, SkyValue?> = graph.getSuccessfulValues(pkgKeys)
        for (pkgEntry in packages.entries) {
            val pkgId: PackageIdentifier? = pkgEntry.key.argument() as PackageIdentifier?
            val pkgValue: PackageValue = pkgEntry.value as PackageValue
            pkgResults.put(
                pkgId,
                com.google.common.base.Preconditions.checkNotNull<Package?>(pkgValue.getPackage(), pkgId)
            )
        }

        val unknownKeys: com.google.common.collect.Sets.SetView<SkyKey?> =
            com.google.common.collect.Sets.difference<SkyKey?>(pkgKeys, packages.keys)
        if (!com.google.common.collect.Iterables.isEmpty(unknownKeys)) {
            logger.atWarning().log(
                "Unable to find %s in the batch lookup of %s. Successfully looked up %s",
                unknownKeys, pkgKeys, packages.keys
            )
        }
        for (missingOrExceptionEntry in graph.getMissingAndExceptions(unknownKeys).entries) {
            val pkgIdentifier: PackageIdentifier? =
                missingOrExceptionEntry.key.argument() as PackageIdentifier?
            val exception: java.lang.Exception = missingOrExceptionEntry.value
            if (exception == null) {
                // If the package key does not exist in the graph, then it must not correspond to any
                // package, because the SkyQuery environment has already loaded the universe.
                throw BuildFileNotFoundException(pkgIdentifier, "Package not found")
            }
            com.google.common.base.Throwables.propagateIfInstanceOf<X?>(exception, NoSuchPackageException::class.java)
            com.google.common.base.Throwables.propagate(exception)
        }
        return pkgResults.buildOrThrow()
    }

    @Throws(java.lang.InterruptedException::class)
    public override fun bulkIsPackage(
        eventHandler: ExtendedEventHandler, pkgIds: Iterable<PackageIdentifier?>
    ): com.google.common.collect.ImmutableSet<PackageIdentifier?> {
        val resultBuilder: com.google.common.collect.ImmutableSet.Builder<PackageIdentifier?> =
            com.google.common.collect.ImmutableSet.builder<PackageIdentifier?>()

        val packageLookupKeys: com.google.common.collect.ImmutableList<SkyKey> =
            com.google.common.collect.ImmutableList.copyOf<SkyKey?>(
                com.google.common.collect.Iterables.transform<PackageIdentifier?, com.google.devtools.build.lib.skyframe.PackageLookupValue.Key?>(
                    pkgIds,
                    com.google.common.base.Function { pkgIdentifier: PackageIdentifier? ->
                        PackageLookupValue.Companion.key(pkgIdentifier)
                    })
            )
        val successfulPackageLookupValues: MutableMap<SkyKey?, SkyValue?> =
            graph.getSuccessfulValues(packageLookupKeys)
        val nonSuccessfulPackageLookupKeysBuilder: com.google.common.collect.ImmutableList.Builder<SkyKey?> =
            com.google.common.collect.ImmutableList.builder<SkyKey?>()
        for (packageLookupKey in packageLookupKeys) {
            val packageLookupValue: PackageLookupValue? =
                successfulPackageLookupValues.get(packageLookupKey) as PackageLookupValue?
            if (packageLookupValue == null) {
                // Could be because the node legitimately isn't in the graph (which definitely implies the
                // package doesn't exist) but could also be because the node exists but is in error. In the
                // latter case, we want to print that error message out.
                nonSuccessfulPackageLookupKeysBuilder.add(packageLookupKey)
            } else if (packageLookupValue.packageExists()) {
                resultBuilder.add(packageLookupKey.argument() as PackageIdentifier?)
            }
        }
        val nonSuccessfulPackageLookupKeys: com.google.common.collect.ImmutableList<SkyKey?> =
            nonSuccessfulPackageLookupKeysBuilder.build()
        if (!nonSuccessfulPackageLookupKeys.isEmpty()) {
            val exceptions: MutableMap<SkyKey?, java.lang.Exception> =
                graph.getMissingAndExceptions(nonSuccessfulPackageLookupKeys)
            for (exception in exceptions.values) {
                if (exception is NoSuchPackageException) {
                    eventHandler.handle(Event.error(exception.message))
                }
            }
        }
        return resultBuilder.build()
    }

    @Throws(java.lang.InterruptedException::class)
    public override fun isPackage(eventHandler: ExtendedEventHandler, packageName: PackageIdentifier): Boolean {
        return !bulkIsPackage(
            eventHandler,
            com.google.common.collect.ImmutableList.of<PackageIdentifier?>(packageName)
        ).isEmpty()
    }

    @Throws(java.lang.InterruptedException::class)
    private fun checkValidDirectoryAndGetRoots(
        repository: RepositoryName, directory: PathFragment?
    ): com.google.common.collect.ImmutableList<Root?>? {
        // Check that this package is covered by at least one of our universe patterns.

        var inUniverse = false
        if (universeTargetPatterns.allowAll()) {
            inUniverse = true
        } else {
            for (pattern in universeTargetPatterns.patterns()) {
                if (!pattern.type.equals(TargetPattern.Type.TARGETS_BELOW_DIRECTORY)) {
                    continue
                }
                val packageIdentifier: PackageIdentifier? = PackageIdentifier.create(repository, directory)
                if ((pattern as TargetsBelowDirectory)
                        .containsAllTransitiveSubdirectories(packageIdentifier)
                ) {
                    inUniverse = true
                    break
                }
            }
        }

        if (!inUniverse) {
            return com.google.common.collect.ImmutableList.of<Root?>()
        }

        if (repository.isMain()) {
            return pkgRoots
        } else {
            if (graph.getValue(RepositoryDirectoryValue.key(repository))
                        is Success
            ) {
                return com.google.common.collect.ImmutableList.of<E?>(success.root())
            }
            // If this key doesn't exist, the repository is outside the universe, so we return
            // "nothing".
            return com.google.common.collect.ImmutableList.of<Root?>()
        }
    }

    @Throws(java.lang.InterruptedException::class, com.google.devtools.build.lib.query2.engine.QueryException::class)
    public override fun streamPackagesUnderDirectory(
        results: SafeBatchCallback<PackageIdentifier?>?,
        eventHandler: ExtendedEventHandler?,
        repository: RepositoryName,
        directory: PathFragment?,
        ignoredSubdirectories: IgnoredSubdirectories?,
        excludedSubdirectories: com.google.common.collect.ImmutableSet<PathFragment?>?
    ) {
        rootPackageExtractor.streamPackagesFromRoots(
            results,
            graph,
            checkValidDirectoryAndGetRoots(repository, directory),
            eventHandler,
            repository,
            directory,
            ignoredSubdirectories,
            excludedSubdirectories
        )
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    }
}
