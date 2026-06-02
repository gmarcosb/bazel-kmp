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
 * A [RecursivePackageProvider] backed by an [Environment]. Its methods may throw [ ] if the package values this depends on haven't been calculated and added to
 * its environment.
 * 
 * 
 * This implementation never emits events through the [ExtendedEventHandler]s passed to its
 * methods. Instead, it emits events through its environment's [Environment.getListener].
 * 
 * 
 * This implementation expands symbolic macros eagerly; in other words, [.getBuildFile] and
 * [.getTarget] force the full package to be expanded. This is intentional, since this
 * implementation is used for retrieving the full list of targets in packages.
 * 
 * 
 * This implementation suppresses most [NoSuchPackageException]s discovered during package
 * loading, since target pattern expansion may tolerate point failures in packages. The first one
 * found is stored so that inside a nokeep-going build it can be retrieved, wrapped, and rethrown.
 * The exception(!) to the rule is errors loading a package via [.getPackage], since the
 * corresponding target pattern does throw eagerly if the package cannot be loaded.
 * 
 * 
 * On the other hand, exceptions indicating a bad filesystem are propagated eagerly, since they
 * are catastrophic failures that should terminate the evaluation.
 */
class EnvironmentBackedRecursivePackageProvider
internal constructor(env: SkyFunction.Environment) : AbstractRecursivePackageProvider() {
    private val env: SkyFunction.Environment
    private val encounteredPackageErrors: AtomicBoolean = AtomicBoolean(false)
    private val noSuchPackageException: AtomicReference<NoSuchPackageException?> =
        AtomicReference<NoSuchPackageException?>()

    init {
        this.env = env
    }

    /**
     * Whether any of the calls to [.getPackage], [.getTarget], [.bulkGetPackages],
     * or [RecursivePackageProvider.streamPackagesUnderDirectory] encountered a package in
     * error.
     * 
     * 
     * The client of [EnvironmentBackedRecursivePackageProvider] may want to check this. See
     * comments in [.getPackage] for details.
     */
    fun encounteredPackageErrors(): Boolean {
        return encounteredPackageErrors.get()
    }

    fun maybeGetNoSuchPackageException(): NoSuchPackageException? {
        return noSuchPackageException.get()
    }

    @Throws(NoSuchPackageException::class, MissingDepException::class, java.lang.InterruptedException::class)
    public override fun getPackage(eventHandler: ExtendedEventHandler?, packageName: PackageIdentifier?): Package {
        val pkgValue: PackageValue?
        try {
            pkgValue = env.getValueOrThrow<E?>(packageName, NoSuchPackageException::class.java) as PackageValue?
            if (pkgValue == null) {
                throw MissingDepException()
            }
        } catch (e: NoSuchPackageException) {
            encounteredPackageErrors.set(true)
            throw e
        }

        val pkg: Package = pkgValue.getPackage()
        handlePackageoidErrors(pkg)
        return pkg
    }

    @Throws(NoSuchPackageException::class, MissingDepException::class, java.lang.InterruptedException::class)
    public override fun getBuildFile(eventHandler: ExtendedEventHandler?, packageName: PackageIdentifier?): InputFile {
        return getPackage(eventHandler, packageName).getBuildFile()
    }

    @Throws(
        MissingDepException::class,
        java.lang.InterruptedException::class
    )  // Good for callers to know about MissingDep.
    private fun handlePackageoidErrors(packageoid: Packageoid) {
        if (packageoid.containsErrors()) {
            // If this is a nokeep_going build, we must shut the build down by throwing an exception. To
            // do that, we request a node that will throw an exception, and then try to catch it and
            // continue. This gives the framework notification to shut down the build if it should.
            try {
                env.getValueOrThrow<E?>(
                    PackageErrorFunction.key(packageoid.getPackageIdentifier()),
                    BuildFileContainsErrorsException::class.java
                )
                com.google.common.base.Preconditions.checkState(
                    env.valuesMissing(), "Should have thrown for %s", packageoid.getPackageIdentifier()
                )
                throw MissingDepException()
            } catch (e: BuildFileContainsErrorsException) {
                // If this is a keep_going build, then the user of this RecursivePackageProvider has two
                // options for handling the "package in error" case. The user must either inspect the
                // package returned by this method, or else determine whether any errors have been seen via
                // the "encounteredPackageErrors" method.
                encounteredPackageErrors.set(true)
                noSuchPackageException.compareAndSet(null, e)
            }
        }
    }

    @Throws(MissingDepException::class, InconsistentFilesystemException::class, java.lang.InterruptedException::class)
    public override fun isPackage(eventHandler: ExtendedEventHandler?, packageId: PackageIdentifier?): Boolean {
        val packageLookupKey: SkyKey? = PackageLookupValue.key(packageId)
        try {
            val packageLookupValue: PackageLookupValue? =
                env.getValueOrThrow<E1?, E2?>(
                    packageLookupKey,
                    NoSuchPackageException::class.java,
                    InconsistentFilesystemException::class.java
                ) as PackageLookupValue?
            if (packageLookupValue == null) {
                throw MissingDepException()
            }
            return packageLookupValue.packageExists()
        } catch (e: NoSuchPackageException) {
            noSuchPackageException.compareAndSet(null, e)
            encounteredPackageErrors.set(true)
            return false
        }
    }

    @Throws(
        java.lang.InterruptedException::class,
        NoSuchPackageException::class,
        ProcessPackageDirectoryException::class
    )
    public override fun streamPackagesUnderDirectory(
        results: SafeBatchCallback<PackageIdentifier?>,
        eventHandler: ExtendedEventHandler,
        repository: RepositoryName,
        directory: PathFragment,
        ignoredSubdirectories: IgnoredSubdirectories,
        excludedSubdirectories: com.google.common.collect.ImmutableSet<PathFragment?>
    ) {
        val packageLocator: PathPackageLocator = PrecomputedValue.PATH_PACKAGE_LOCATOR.get(env)
        if (packageLocator == null) {
            throw MissingDepException()
        }

        val roots: MutableList<Root?> = java.util.ArrayList<Root?>()
        if (repository.isMain()) {
            roots.addAll(packageLocator.getPathEntries())
        } else {
            val repositoryValue: RepositoryDirectoryValue? =
                env.getValue(RepositoryDirectoryValue.key(repository)) as RepositoryDirectoryValue?
            if (repositoryValue == null) {
                throw MissingDepException()
            }

            if (repositoryValue is) {
                eventHandler.handle(
                    com.google.devtools.build.lib.events.Event.error(
                        java.lang.String.format(
                            "No such repository '%s': %s",
                            repository,
                            errorMsg
                        )
                    )
                )
                return
            }
            roots.add((repositoryValue as Success).root())
        }

        val filteredIgnoredSubdirectories: IgnoredSubdirectories? =
            ignoredSubdirectories.filterForDirectory(directory)

        val recursivePackageKeys: Iterable<RecursivePkgValue.Key?> =
            com.google.common.collect.Iterables.transform<Root?, RecursivePkgValue.Key?>(
                roots,
                com.google.common.base.Function { r: Root? ->
                    RecursivePkgValue.key(
                        repository,
                        RootedPath.toRootedPath(r, directory),
                        filteredIgnoredSubdirectories
                    )
                })
        val recursivePackageValues: SkyframeLookupResult = env.getValuesAndExceptions(recursivePackageKeys)
        var firstNspe: NoSuchPackageException? = null
        for (key in recursivePackageKeys) {
            val lookup: RecursivePkgValue?
            try {
                lookup =
                    recursivePackageValues.getOrThrow<E1?, E2?>(
                        key, NoSuchPackageException::class.java, ProcessPackageDirectoryException::class.java
                    ) as RecursivePkgValue?
            } catch (e: NoSuchPackageException) {
                // NoSuchPackageException can happen during error bubbling in a no-keep-going build.
                if (firstNspe == null) {
                    firstNspe = e
                }
                encounteredPackageErrors.set(true)
                noSuchPackageException.compareAndSet(null, e)
                continue
            }
            if (lookup == null) {
                continue
            }
            if (lookup.hasErrors()) {
                encounteredPackageErrors.set(true)
            }

            if (env.valuesMissing()) {
                // If values are missing, we're only checking for errors, not constructing a result.
                continue
            }
            for (packageName in lookup.getPackages().toList()) {
                // TODO(bazel-team): Make RecursivePkgValue return NestedSet<PathFragment> so this transform
                // is unnecessary.
                val packageNamePathFragment: PathFragment = PathFragment.create(packageName)
                if (!com.google.common.collect.Iterables.any<PathFragment?>(
                        excludedSubdirectories,
                        com.google.common.base.Predicate { other: PathFragment? ->
                            packageNamePathFragment.startsWith(other)
                        })
                ) {
                    results.process(
                        com.google.common.collect.ImmutableList.of<E?>(
                            PackageIdentifier.create(
                                repository,
                                packageNamePathFragment
                            )
                        )
                    )
                }
            }
        }
        if (firstNspe != null) {
            throw firstNspe
        }
        if (env.valuesMissing()) {
            throw MissingDepException()
        }
    }
}
