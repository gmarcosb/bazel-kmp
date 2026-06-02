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

import com.google.devtools.build.lib.cmdline.BatchCallback

/**
 * PrepareDepsOfPatternFunction ensures the graph loads targets matching the pattern and its
 * transitive dependencies.
 */
class PrepareDepsOfPatternFunction(pkgPath: AtomicReference<PathPackageLocator?>) : SkyFunction {
    private val pkgPath: AtomicReference<PathPackageLocator?>

    init {
        this.pkgPath = pkgPath
    }

    @Throws(PrepareDepsOfPatternFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(key: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val patternKey: TargetPatternKey = (key.argument() as TargetPatternKey)

        // DepsOfPatternPreparer below expects to be able to ignore the filtering policy from the
        // TargetPatternKey, which should be valid because PrepareDepsOfPatternValue.keys
        // unconditionally creates TargetPatternKeys with the NO_FILTER filtering policy. (Compare
        // with SkyframeTargetPatternEvaluator, which can create TargetPatternKeys with other
        // filtering policies like FILTER_TESTS or FILTER_MANUAL.) This check makes sure that the
        // key's filtering policy is NO_FILTER as expected.
        com.google.common.base.Preconditions.checkState(
            patternKey.getPolicy().equals(FilteringPolicies.NO_FILTER), patternKey.getPolicy()
        )

        val parsedPattern: TargetPattern = patternKey.getParsedPattern()

        val repositoryIgnoredPrefixes: IgnoredSubdirectoriesValue? =
            env.getValue(IgnoredSubdirectoriesValue.key(parsedPattern.repository)) as IgnoredSubdirectoriesValue?
        if (repositoryIgnoredPrefixes == null) {
            return null
        }
        // This SkyFunction is used to load the universe, so we want both the ignored directories from
        // the global list of exclusions (set with .bazelignore in Bazel and set statically in other
        // binaries) and the excluded directories from the TargetPatternKey itself to be embedded in the
        // SkyKeys created and used by the DepsOfPatternPreparer. The DepsOfPatternPreparer ignores
        // excludedSubdirectories and embeds repositoryIgnoredPatterns in SkyKeys it creates and uses.
        //
        // This consolidation of excluded into ignored means that parsedPattern.eval below does a bit of
        // extra work when parsePattern is a TargetsBelowDirectory and there are excluded directories,
        // since it has to iterate over those exclusions to see if they fully exclude the directory even
        // though TargetPatternKey guarantees that the exclusions will not fully exclude the directory.
        val excludedPatterns: com.google.common.collect.ImmutableSet<PathFragment?>? =
            patternKey.getExcludedSubdirectories()
        val repositoryIgnoredPatterns: IgnoredSubdirectories? =
            repositoryIgnoredPrefixes
                .asIgnoredSubdirectories()
                .union(IgnoredSubdirectories.of(excludedPatterns))

        val preparer = DepsOfPatternPreparer(env, pkgPath.get())

        try {
            parsedPattern.eval(
                preparer,
                { repositoryIgnoredPatterns },
                com.google.common.collect.ImmutableSet.of<E?>(),
                NullCallback.instance(),
                MarkerRuntimeException::class.java
            )
        } catch (e: TargetParsingException) {
            throw PrepareDepsOfPatternFunctionException(e)
        } catch (e: MissingDepException) {
            // The DepsOfPatternPreparer constructed above might throw MissingDepException to signal
            // when it has a dependency on a missing Environment value.
            return null
        } catch (e: ProcessPackageDirectoryException) {
            throw PrepareDepsOfPatternFunctionException(parsedPattern, e)
        } catch (e: InconsistentFilesystemException) {
            throw PrepareDepsOfPatternFunctionException(parsedPattern, e)
        }
        return PrepareDepsOfPatternValue.INSTANCE
    }

    /**
     * Used to declare all the exception types that can be wrapped in the exception thrown by [ ][PrepareDepsOfPatternFunction.compute].
     */
    private class PrepareDepsOfPatternFunctionException : SkyFunctionException {
        internal constructor(e: TargetParsingException?) : super(e, Transience.PERSISTENT)

        internal constructor(pattern: TargetPattern, e: ProcessPackageDirectoryException) : super(
            PrepareDepsOfPatternException(pattern, e),
            Transience.PERSISTENT
        )

        internal constructor(pattern: TargetPattern, e: InconsistentFilesystemException) : super(
            PrepareDepsOfPatternException(pattern, e),
            Transience.PERSISTENT
        )
    }

    private class PrepareDepsOfPatternException : java.lang.Exception, DetailedException {
        private val detailedExitCode: DetailedExitCode?

        internal constructor(pattern: TargetPattern, e: ProcessPackageDirectoryException) : super(
            ("Preparing deps of pattern '"
                    + pattern.originalPattern
                    + "' failed: "
                    + e.getMessage()),
            e
        ) {
            detailedExitCode = e.getDetailedExitCode()
        }

        constructor(pattern: TargetPattern, e: InconsistentFilesystemException) : super(
            ("Preparing deps of pattern '"
                    + pattern.originalPattern
                    + "' failed: "
                    + e.getMessage()),
            e
        ) {
            detailedExitCode =
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(getMessage())
                        .setPackageLoading(
                            PackageLoading.newBuilder()
                                .setCode(Code.TRANSIENT_INCONSISTENT_FILESYSTEM_ERROR)
                        )
                        .build()
                )
        }

        public override fun getDetailedExitCode(): DetailedExitCode? {
            return detailedExitCode
        }
    }

    /**
     * A [TargetPatternResolver] backed by an [Environment] whose methods do not actually
     * return resolved targets, but that ensures the graph loads the matching targets **and** their
     * transitive dependencies. Its methods may throw [MissingDepException] if the package
     * values this depends on haven't been calculated and added to its environment.
     */
    internal class DepsOfPatternPreparer(env: SkyFunction.Environment, pkgPath: PathPackageLocator) :
        TargetPatternResolver<java.lang.Void?>() {
        // Because PrepareDepsOfPatternFunction's only goal is to ensure the proper Skyframe nodes and
        // edges are in the graph, we don't need to worry about
        // EnvironmentBackedRecursivePackageProvider#encounteredPackageErrors.
        private val packageProvider: EnvironmentBackedRecursivePackageProvider
        private val env: SkyFunction.Environment
        private val pkgRoots: com.google.common.collect.ImmutableList<Root?>?

        init {
            this.env = env
            this.packageProvider = EnvironmentBackedRecursivePackageProvider(env)
            this.pkgRoots = pkgPath.getPathEntries()
        }

        public override fun warn(msg: String?) {
            env.getListener().handle(com.google.devtools.build.lib.events.Event.warn(msg))
        }

        public override fun getTargetOrNull(label: Label?): java.lang.Void? {
            // Note:
            // This method is used in just one place, TargetPattern.TargetsInPackage#getWildcardConflict.
            // Returning null tells #getWildcardConflict that there is not a target with a name like
            // "all" or "all-targets", which means that TargetPattern.TargetsInPackage will end up
            // calling DepsOfTargetPreparer#getTargetsInPackage.
            // TODO (bazel-team): Consider replacing this with an isTarget method on the interface.
            return null
        }

        @Throws(TargetParsingException::class, java.lang.InterruptedException::class)
        public override fun getExplicitTarget(label: Label?): ResolvedTargets<java.lang.Void?> {
            try {
                val target: Target = packageProvider.getTarget(env.getListener(), label)
                val key: SkyKey? = TransitiveTraversalValue.key(target.getLabel())
                val token: SkyValue? =
                    env.getValueOrThrow<E1?, E2?>(
                        key,
                        NoSuchPackageException::class.java,
                        NoSuchTargetException::class.java
                    )
                if (token == null) {
                    throw MissingDepException()
                }
                return ResolvedTargets.empty()
            } catch (e: NoSuchThingException) {
                throw TargetParsingException(e.getMessage(), e, e.getDetailedExitCode())
            }
        }

        @Throws(TargetParsingException::class, java.lang.InterruptedException::class)
        public override fun getTargetsInPackage(
            originalPattern: String?, packageIdentifier: PackageIdentifier?, rulesOnly: Boolean
        ): MutableCollection<java.lang.Void?> {
            val policy: FilteringPolicy? =
                if (rulesOnly) FilteringPolicies.RULES_ONLY else FilteringPolicies.NO_FILTER
            return getTargetsInPackage(originalPattern, packageIdentifier, policy)
        }

        @Throws(TargetParsingException::class, java.lang.InterruptedException::class)
        private fun getTargetsInPackage(
            originalPattern: String?, packageIdentifier: PackageIdentifier?, policy: FilteringPolicy?
        ): MutableCollection<java.lang.Void?> {
            try {
                val pkg: Package? = packageProvider.getPackage(env.getListener(), packageIdentifier)
                val packageTargets: MutableCollection<Target> =
                    TargetPatternResolverUtil.resolvePackageTargets(pkg, policy)
                val builder: com.google.common.collect.ImmutableList.Builder<SkyKey?> =
                    com.google.common.collect.ImmutableList.builder<SkyKey?>()
                for (target in packageTargets) {
                    builder.add(TransitiveTraversalValue.key(target.getLabel()))
                }
                val skyKeys: com.google.common.collect.ImmutableList<SkyKey?> = builder.build()
                if (GraphTraversingHelper.declareDependenciesAndCheckIfValuesMissing<E1?, E2?>(
                        env, skyKeys, NoSuchPackageException::class.java, NoSuchTargetException::class.java
                    )
                ) {
                    throw MissingDepException()
                }
                return com.google.common.collect.ImmutableSet.of<java.lang.Void?>()
            } catch (e: NoSuchThingException) {
                val message: String? =
                    TargetPatternResolverUtil.getParsingErrorMessage(
                        "package contains errors", originalPattern
                    )
                throw TargetParsingException(message, e, e.getDetailedExitCode())
            }
        }

        @Throws(java.lang.InterruptedException::class, InconsistentFilesystemException::class)
        public override fun isPackage(packageIdentifier: PackageIdentifier?): Boolean {
            return packageProvider.isPackage(env.getListener(), packageIdentifier)
        }

        public override fun getTargetKind(target: java.lang.Void?): String? {
            // Note:
            // This method is used in just one place, TargetPattern.TargetsInPackage#getWildcardConflict.
            // Because DepsOfPatternPreparer#getTargetOrNull always returns null, this method is never
            // called.
            throw java.lang.UnsupportedOperationException()
        }

        @Throws(TargetParsingException::class, E::class, java.lang.InterruptedException::class)
        public override fun <E> findTargetsBeneathDirectory(
            repository: RepositoryName,
            originalPattern: String?,
            directory: String?,
            rulesOnly: Boolean,
            repositoryIgnoredSubdirectories: IgnoredSubdirectories?,
            excludedSubdirectories: com.google.common.collect.ImmutableSet<PathFragment?>,
            callback: BatchCallback<java.lang.Void?, E?>?,
            exceptionClass: java.lang.Class<E?>?
        ) where E : java.lang.Exception?, E : QueryExceptionMarkerInterface? {
            val directoryPathFragment: PathFragment = TargetPatternResolverUtil.getPathFragment(directory)
            com.google.common.base.Preconditions.checkArgument(excludedSubdirectories.isEmpty(), excludedSubdirectories)
            val policy: FilteringPolicy? =
                if (rulesOnly) FilteringPolicies.RULES_ONLY else FilteringPolicies.NO_FILTER
            val roots: MutableList<Root?> = java.util.ArrayList<Root?>()
            if (repository.isMain()) {
                roots.addAll(pkgRoots)
            } else {
                val repositoryValue: RepositoryDirectoryValue? =
                    env.getValue(RepositoryDirectoryValue.key(repository)) as RepositoryDirectoryValue?
                if (repositoryValue == null) {
                    throw MissingDepException()
                }

                check(!repositoryValue is) {
                    java.lang.String.format(
                        "No such repository '%s': %s",
                        repository,
                        errorMsg
                    )
                }
                roots.add((repositoryValue as Success).root())
            }

            for (root in roots) {
                val rootedPath: RootedPath? = RootedPath.toRootedPath(root, directoryPathFragment)
                if (GraphTraversingHelper.declareDependenciesAndCheckIfValuesMissing(
                        env, getDeps(repository, repositoryIgnoredSubdirectories, policy, rootedPath)
                    )
                ) {
                    throw MissingDepException()
                }
            }
        }

        private fun getDeps(
            repository: RepositoryName?,
            repositoryIgnoredSubdirectories: IgnoredSubdirectories?,
            policy: FilteringPolicy?,
            rootedPath: RootedPath?
        ): com.google.common.collect.ImmutableList<SkyKey?> {
            val keys: MutableList<SkyKey?> = java.util.ArrayList<SkyKey?>()
            keys.add(
                PrepareDepsOfTargetsUnderDirectoryValue.key(
                    repository, rootedPath, repositoryIgnoredSubdirectories, policy
                )
            )
            keys.add(
                CollectPackagesUnderDirectoryValue.key(
                    repository, rootedPath, repositoryIgnoredSubdirectories
                )
            )
            return com.google.common.collect.ImmutableList.copyOf<SkyKey?>(keys)
        }
    }
}
