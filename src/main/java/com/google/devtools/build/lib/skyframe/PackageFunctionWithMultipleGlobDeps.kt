// Copyright 2024 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ThreadStateReceiver

/**
 * Computes the [PackageValue] which depends on multiple GLOB nodes.
 * 
 * 
 * Every glob pattern defined in the package's `BUILD` file is represented as a single GLOB
 * node in the dependency graph.
 * 
 * 
 * [PackageFunctionWithMultipleGlobDeps] subclass is created when the globbing strategy is
 * [ ][com.google.devtools.build.lib.skyframe.PackageFunction.GlobbingStrategy.MULTIPLE_GLOB_HYBRID].
 * Incremental evaluation adopts `SKYFRAME_HYBRID` globbing strategy in order to use the
 * unchanged [GlobValue] stored in Skyframe when incrementally reloading the package.
 */
internal class PackageFunctionWithMultipleGlobDeps(
    packageFactory: PackageFactory?,
    pkgLocator: CachingPackageLocator?,
    showLoadingProgress: AtomicBoolean?,
    numPackagesSuccessfullyLoaded: AtomicInteger?,
    bzlLoadFunctionForInlining: BzlLoadFunction?,
    packageProgress: PackageProgressReceiver?,
    actionOnIOExceptionReadingBuildFile: ActionOnIOExceptionReadingBuildFile?,
    actionOnFilesystemErrorCodeLoadingBzlFile: ActionOnFilesystemErrorCodeLoadingBzlFile?,
    shouldUseRepoDotBazel: Boolean,
    threadStateReceiverFactoryForMetrics: java.util.function.Function<SkyKey?, ThreadStateReceiver?>?,
    cpuBoundSemaphore: AtomicReference<Semaphore?>?
) : PackageFunction(
    packageFactory,
    pkgLocator,
    showLoadingProgress,
    numPackagesSuccessfullyLoaded,
    bzlLoadFunctionForInlining,
    packageProgress,
    actionOnIOExceptionReadingBuildFile,
    actionOnFilesystemErrorCodeLoadingBzlFile,
    shouldUseRepoDotBazel,
    threadStateReceiverFactoryForMetrics,
    cpuBoundSemaphore
) {
    /**
     * If globbing strategy is `GlobbingStrategy#SKYFRAME_HYBRID`, these deps should have
     * already been marked by the [Globber] but we need to properly handle symlink issues that
     * [NonSkyframeGlobber] can't handle gracefully.
     */
    @Throws(
        InternalInconsistentFilesystemException::class,
        FileSymlinkException::class,
        java.lang.InterruptedException::class
    )
    override fun handleGlobDepsAndPropagateFilesystemExceptions(
        packageIdentifier: PackageIdentifier?,
        packageRoot: Root?,
        loadedPackage: LoadedPackage,
        env: SkyFunction.Environment,
        packageWasInError: Boolean
    ) {
        val depKeys: MutableSet<SkyKey> = (loadedPackage as LoadedPackageWithGlobDeps).globDepKeys
        com.google.common.base.Preconditions.checkState(
            com.google.common.collect.Iterables.all<T?>(
                depKeys,
                SkyFunctions.isSkyFunction(SkyFunctions.GLOB)
            ), depKeys
        )
        var arbitraryFse: FileSymlinkException? = null
        val result: SkyframeLookupResult = env.getValuesAndExceptions(depKeys)
        for (key in depKeys) {
            try {
                result.getOrThrow<E1?, E2?>(key, IOException::class.java, BuildFileNotFoundException::class.java)
            } catch (e: InconsistentFilesystemException) {
                throw InternalInconsistentFilesystemException(packageIdentifier, e)
            } catch (e: FileSymlinkException) {
                // Non-Skyframe globbing doesn't explicitly detect symlink issues, but certain filesystems
                // might detect some symlink issues. For example, many filesystems have a hardcoded bound on
                // the number of symlink hops they will follow when resolving paths (e.g. Unix's ELOOP).
                // Since Skyframe globbing does explicitly detect symlink issues, we are able to:
                //   (1) Provide a more informative error message.
                //   (2) Confidently act as though the symlink issue is non-transient.
                arbitraryFse = e
            } catch (e: IOException) {
                PackageFunction.Companion.maybeThrowFilesystemInconsistency(packageIdentifier, e, packageWasInError)
            } catch (e: BuildFileNotFoundException) {
                PackageFunction.Companion.maybeThrowFilesystemInconsistency(packageIdentifier, e, packageWasInError)
            }
        }
        if (arbitraryFse != null) {
            // If there was at least one symlink issue and no inconsistent filesystem issues, arbitrarily
            // rethrow one of the symlink issues.
            throw arbitraryFse
        }
    }

    private class LoadedPackageWithGlobDeps(
        builder: Package.AbstractBuilder?,
        metrics: Metrics?,
        globDepKeys: MutableSet<SkyKey>
    ) : LoadedPackage(builder, metrics) {
        private val globDepKeys: MutableSet<SkyKey>

        init {
            this.globDepKeys = globDepKeys
        }
    }

    /**
     * A [Globber] implemented on top of Skyframe that falls back to a [ ] on a Skyframe cache-miss. This way we don't require a Skyframe restart
     * after a call to [Globber.runAsync] and before/during a call to [ ][Globber.fetchUnsorted].
     * 
     * 
     * There are three advantages to this hybrid approach over the more obvious approach of solely
     * using a [NonSkyframeGlobber]:
     * 
     * 
     *  * We trivially have the proper Skyframe [GlobValue] deps, whereas we would need to
     * request them after-the-fact if we solely used a [NonSkyframeGlobber].
     *  * We don't need to re-evaluate globs whose expression hasn't changed (e.g. in the common
     * case of a BUILD file edit that doesn't change a glob expression), whereas invoking the
     * package loading machinery in [PackageFactory] with a [NonSkyframeGlobber]
     * would naively re-evaluate globs when re-evaluating the BUILD file.
     *  * We don't need to re-evaluate invalidated globs *twice* (the single re-evaluation via our
     * GlobValue deps is sufficient and optimal). See above for why the second evaluation would
     * happen.
     * 
     * 
     * 
     * One general disadvantage of the hybrid approach is that we do the logical globbing work
     * twice on clean builds. A part of this is that we do double the number of 'stat' filesystem
     * operations: non-Skyframe globbing does `stat` operations following symlinks, and Skyframe's
     * [FileStateFunction] does those operations not following symlinks (since [ ] handles symlink chains manually). We used to have a similar concern for `readdir`
     * operations, but we mitigated it by restructuring the non-Skyframe globbing code so that it
     * doesn't follow symlinks for these operations, allowing the results to be cached and used by
     * [DirectoryListingStateFunction].
     * 
     * 
     * This theoretical inefficiency isn't a big deal in practice, and historical attempts to
     * completely remove it by solely using Skyframe's [GlobFunction] have been unsuccessful due
     * to the consequences of Skyframe restarts on package loading performance. If we knew the full
     * set of `glob` calls that would be performed during BUILD file evaluation, then we could
     * precompute those [GlobValue] nodes and not have any Skyframe restarts during. But that's
     * a big "if"; consider glob calls with non-static arguments:
     * 
     * <pre>
     * P = f(42)
     * g(glob(P))
    </pre> * 
     * 
     * Also consider dependent glob calls:
     * 
     * <pre>
     * L = glob(["foo.*"])
     * g(glob([f(x) for x in L])
    </pre> * 
     * 
     * One historical attempt at addressing this issue was to do a first pass of BUILD file evaluation
     * where we tried to encounter as many concrete glob calls as possible but without doing full
     * Starlark evaluation, and then do the real pass of BUILD file evaluation. This approach was a
     * net performance regression, due to the first pass both having non-trivial Starlark evaluation
     * cost (consider a very expensive function 'f' in the first example above) and also not
     * encountering all glob calls (meaning the real pass can still have the core problem with
     * Skyframe restarts).
     */
    private class SkyframeHybridGlobber(
        packageId: PackageIdentifier?,
        packageRoot: Root?,
        env: SkyFunction.Environment,
        nonSkyframeGlobber: NonSkyframeGlobber
    ) : Globber {
        private val packageId: PackageIdentifier?
        private val packageRoot: Root?
        private val env: SkyFunction.Environment
        private val nonSkyframeGlobber: NonSkyframeGlobber
        private val globDepsRequested: MutableSet<SkyKey?> =
            com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()

        init {
            this.packageId = packageId
            this.packageRoot = packageRoot
            this.env = env
            this.nonSkyframeGlobber = nonSkyframeGlobber
        }

        fun getGlobDepsRequested(): MutableSet<SkyKey> {
            return com.google.common.collect.ImmutableSet.copyOf<SkyKey?>(globDepsRequested)
        }

        @Throws(BadGlobException::class)
        fun getGlobKey(pattern: String?, globberOperation: Globber.Operation?): SkyKey {
            try {
                return GlobValue.key(
                    packageId, packageRoot, pattern, globberOperation, PathFragment.EMPTY_FRAGMENT
                )
            } catch (e: InvalidGlobPatternException) {
                throw BadGlobException(e.getMessage())
            }
        }

        @Throws(BadGlobException::class, java.lang.InterruptedException::class)
        public override fun runAsync(
            includes: MutableList<String?>,
            excludes: MutableList<String?>,
            globberOperation: Globber.Operation?,
            allowEmpty: Boolean
        ): Token {
            val globKeys: LinkedHashSet<SkyKey> =
                com.google.common.collect.Sets.newLinkedHashSetWithExpectedSize<SkyKey?>(includes.size())
            val globKeyToPatternMap: MutableMap<SkyKey?, String?> =
                com.google.common.collect.Maps.newHashMapWithExpectedSize<SkyKey?, String?>(includes.size())

            for (pattern in includes) {
                val globKey: SkyKey = getGlobKey(pattern, globberOperation)
                globKeys.add(globKey)
                globKeyToPatternMap.put(globKey, pattern)
            }

            globDepsRequested.addAll(globKeys)

            val globValueMap: SkyframeLookupResult = env.getValuesAndExceptions(globKeys)

            // For each missing glob, evaluate it asynchronously via the delegate.
            val missingKeys: MutableList<SkyKey?> = java.util.ArrayList<SkyKey?>(globKeys.size())
            for (globKey in globKeys) {
                try {
                    val value: SkyValue? =
                        globValueMap.getOrThrow<E1?, E2?>(
                            globKey,
                            IOException::class.java,
                            BuildFileNotFoundException::class.java
                        )
                    if (value == null) {
                        missingKeys.add(globKey)
                    }
                } catch (e: IOException) {
                    logger.atWarning().withCause(e).log("Exception processing %s", globKey)
                } catch (e: BuildFileNotFoundException) {
                    logger.atWarning().withCause(e).log("Exception processing %s", globKey)
                }
            }
            val globsToDelegate: MutableList<String?> = java.util.ArrayList<String?>(missingKeys.size())
            for (missingKey in missingKeys) {
                val missingPattern = globKeyToPatternMap.get(missingKey)
                if (missingPattern != null) {
                    globsToDelegate.add(missingPattern)
                    globKeys.remove(missingKey)
                }
            }
            val nonSkyframeIncludesToken: NonSkyframeGlobber.Token? =
                if (globsToDelegate.isEmpty())
                    null
                else
                    nonSkyframeGlobber.runAsync(
                        globsToDelegate, com.google.common.collect.ImmutableList.of<E?>(), globberOperation, allowEmpty
                    )
            return HybridToken(
                globValueMap, globKeys, nonSkyframeIncludesToken, excludes, globberOperation, allowEmpty
            )
        }

        @Throws(BadGlobException::class, IOException::class, java.lang.InterruptedException::class)
        public override fun fetchUnsorted(token: Token?): MutableList<String?> {
            val hybridToken = token as HybridToken
            return hybridToken.resolve(nonSkyframeGlobber)
        }

        public override fun onInterrupt() {
            nonSkyframeGlobber.onInterrupt()
        }

        public override fun onCompletion() {
            nonSkyframeGlobber.onCompletion()
        }

        /**
         * A [Globber.Token] that encapsulates the result of a single [Globber.runAsync]
         * call via the fetching of some globs from skyframe, and some other globs via a [ ]. 'exclude' patterns are evaluated using [UnixGlob.removeExcludes]
         * after merging the glob results in [.resolve].
         */
        private class HybridToken(
            globValueMap: SkyframeLookupResult,
            includesGlobKeys: Iterable<SkyKey>,
            nonSkyframeGlobberIncludesToken: NonSkyframeGlobber.Token?,
            excludes: MutableList<String?>,
            globberOperation: Globber.Operation?,
            allowEmpty: Boolean
        ) : Globber.Token() {
            // The result of the Skyframe lookup for all the needed glob patterns.
            private val globValueMap: SkyframeLookupResult

            // The skyframe keys corresponding to the 'includes' patterns fetched from Skyframe
            // (this is includes_sky above).
            private val includesGlobKeys: Iterable<SkyKey>
            private val nonSkyframeGlobberIncludesToken: NonSkyframeGlobber.Token?

            private val excludes: MutableList<String?>

            private val globberOperation: Globber.Operation?

            private val allowEmpty: Boolean

            init {
                this.globValueMap = globValueMap
                this.includesGlobKeys = includesGlobKeys
                this.nonSkyframeGlobberIncludesToken = nonSkyframeGlobberIncludesToken
                this.excludes = excludes
                this.globberOperation = globberOperation
                this.allowEmpty = allowEmpty
            }

            @Throws(BadGlobException::class, IOException::class, java.lang.InterruptedException::class)
            fun resolve(nonSkyframeGlobber: NonSkyframeGlobber): MutableList<String?> {
                val matches: HashSet<String?> = HashSet<String?>()
                for (includeGlobKey in includesGlobKeys) {
                    // TODO(bazel-team): NestedSet expansion here is suboptimal.
                    var foundMatch = false
                    for (match in getGlobMatches(includeGlobKey, globValueMap)) {
                        matches.add(match.getPathString())
                        foundMatch = true
                    }
                    if (!allowEmpty && !foundMatch) {
                        GlobberUtils.throwBadGlobExceptionEmptyResult(
                            (includeGlobKey.argument() as GlobDescriptor).pattern, globberOperation
                        )
                    }
                }
                if (nonSkyframeGlobberIncludesToken != null) {
                    matches.addAll(nonSkyframeGlobber.fetchUnsorted(nonSkyframeGlobberIncludesToken))
                }
                try {
                    UnixGlob.removeExcludes(matches, excludes)
                } catch (ex: BadPattern) {
                    throw BadGlobException(ex.getMessage())
                }
                val result: MutableList<String?> = java.util.ArrayList<String?>(matches)

                if (!allowEmpty && result.isEmpty()) {
                    GlobberUtils.throwBadGlobExceptionAllExcluded(globberOperation)
                }
                return result
            }

            companion object {
                @Throws(IOException::class)
                private fun getGlobMatches(
                    globKey: SkyKey?, globValueMap: SkyframeLookupResult
                ): com.google.common.collect.ImmutableSet<PathFragment> {
                    try {
                        return com.google.common.base.Preconditions.checkNotNull<Any?>(
                            globValueMap.getOrThrow<E1?, E2?>(
                                globKey, BuildFileNotFoundException::class.java, IOException::class.java
                            ) as GlobValue?,
                            "%s should not be missing",
                            globKey
                        ).matches
                    } catch (e: BuildFileNotFoundException) {
                        throw SkyframeGlobbingIOException(e)
                    }
                }
            }
        }
    }

    internal class SkyframeGlobbingIOException(cause: BuildFileNotFoundException) :
        IOException(cause.getMessage(), cause)

    override fun makeGlobber(
        nonSkyframeGlobber: NonSkyframeGlobber,
        packageId: PackageIdentifier?,
        packageRoot: Root?,
        env: SkyFunction.Environment
    ): Globber {
        return SkyframeHybridGlobber(packageId, packageRoot, env, nonSkyframeGlobber)
    }

    override fun newLoadedPackage(
        packageBuilder: Package.AbstractBuilder?, globber: Globber?, metrics: Metrics?
    ): LoadedPackage {
        var globDepKeys: MutableSet<SkyKey> = com.google.common.collect.ImmutableSet.of<SkyKey?>()
        if (globber != null) {
            globDepKeys = (globber as SkyframeHybridGlobber).getGlobDepsRequested()
        }
        return LoadedPackageWithGlobDeps(packageBuilder, metrics, globDepKeys)
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    }
}
