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
 * Computes the [PackageValue] which depends on a single GLOBS node.
 * 
 * 
 * [PackageFunctionWithSingleGlobsDep] subclass is created when the globbing strategy is
 * [ ][com.google.devtools.build.lib.skyframe.PackageFunction.GlobbingStrategy.SINGLE_GLOBS_HYBRID]. All
 * globs defined in the package's `BUILD` file are combined into a single GLOBS node.
 * 
 * 
 * For an overview of the problem space and our approach, see the https://youtu.be/ZrevTeuU-gQ
 * talk from BazelCon 2024 (slides:
 * https://docs.google.com/presentation/d/e/2PACX-1vSjmiGyHDiCDowgc5ar7f7MLAPCzYAAoH1APmnTjqdTpcWv12ysFvgT_aVwj82vLa7JJA8esnp2jtMJ/pub).
 */
internal class PackageFunctionWithSingleGlobsDep(
    packageFactory: PackageFactory?,
    pkgLocator: CachingPackageLocator?,
    showLoadingProgress: AtomicBoolean?,
    numPackagesSuccessfullyLoaded: AtomicInteger?,
    bzlLoadFunctionForInlining: BzlLoadFunction?,
    packageProgress: PackageProgressReceiver?,
    actionOnIoExceptionReadingBuildFile: ActionOnIOExceptionReadingBuildFile?,
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
    actionOnIoExceptionReadingBuildFile,
    actionOnFilesystemErrorCodeLoadingBzlFile,
    shouldUseRepoDotBazel,
    threadStateReceiverFactoryForMetrics,
    cpuBoundSemaphore
) {
    private class LoadedPackageWithGlobRequests(
        builder: Package.AbstractBuilder?,
        metrics: Metrics?,
        globRequests: com.google.common.collect.ImmutableSet<GlobRequest?>
    ) : LoadedPackage(builder, metrics) {
        private val globRequests: com.google.common.collect.ImmutableSet<GlobRequest?>

        init {
            this.globRequests = globRequests
        }
    }

    /**
     * Performs non-Skyframe globbing operations and prepares the [GlobRequest]s set for
     * subsequent Skyframe-based globbing.
     */
    private class GlobsGlobber(nonSkyframeGlobber: NonSkyframeGlobber) : Globber {
        private val nonSkyframeGlobber: NonSkyframeGlobber
        private val globRequests: MutableSet<GlobRequest?> =
            com.google.common.collect.Sets.newConcurrentHashSet<GlobRequest?>()

        init {
            this.nonSkyframeGlobber = nonSkyframeGlobber
        }

        @Throws(BadGlobException::class)
        public override fun runAsync(
            includes: MutableList<String?>, excludes: MutableList<String?>?, operation: Operation?, allowEmpty: Boolean
        ): Token {
            for (pattern in includes) {
                try {
                    globRequests.add(GlobRequest.create(pattern, operation))
                } catch (e: InvalidGlobPatternException) {
                    throw BadGlobException(e.getMessage())
                }
            }

            val nonSkyframeGlobToken: NonSkyframeGlobber.Token? =
                nonSkyframeGlobber.runAsync(includes, excludes, operation, allowEmpty)
            return GlobsToken(nonSkyframeGlobToken, operation, allowEmpty)
        }

        @Throws(BadGlobException::class, IOException::class, java.lang.InterruptedException::class)
        public override fun fetchUnsorted(token: Token): MutableList<String?> {
            val matches: MutableSet<String?> = com.google.common.collect.Sets.newHashSet<String?>()
            matches.addAll(
                nonSkyframeGlobber.fetchUnsorted((token as GlobsToken).nonSkyframeGlobberIncludesToken)
            )

            val result: MutableList<String?> = java.util.ArrayList<String?>(matches)
            if (!token.allowEmpty && result.isEmpty()) {
                GlobberUtils.throwBadGlobExceptionAllExcluded(token.globberOperation)
            }
            return result
        }

        public override fun onInterrupt() {
            nonSkyframeGlobber.onInterrupt()
        }

        public override fun onCompletion() {
            nonSkyframeGlobber.onCompletion()
        }

        /**
         * Returns an [ImmutableSet] of all package's globs, which will be used to construct
         * [GlobsValue.Key] to be requested in Skyframe downstream.
         * 
         * 
         * An empty [ImmutableSet] is returned if there is no glob is defined in the package's
         * BUILD file. Hence, requesting GLOBS in Skyframe is skipped downstream.
         */
        fun getGlobRequests(): com.google.common.collect.ImmutableSet<GlobRequest?> {
            return com.google.common.collect.ImmutableSet.copyOf<GlobRequest?>(globRequests)
        }

        private class GlobsToken(
            nonSkyframeGlobberIncludesToken: NonSkyframeGlobber.Token?,
            globberOperation: Globber.Operation?,
            allowEmpty: Boolean
        ) : Globber.Token() {
            private val nonSkyframeGlobberIncludesToken: NonSkyframeGlobber.Token?
            private val globberOperation: Globber.Operation?
            private val allowEmpty: Boolean

            init {
                this.nonSkyframeGlobberIncludesToken = nonSkyframeGlobberIncludesToken
                this.globberOperation = globberOperation
                this.allowEmpty = allowEmpty
            }
        }
    }

    @Throws(
        java.lang.InterruptedException::class,
        InternalInconsistentFilesystemException::class,
        FileSymlinkException::class
    )
    override fun handleGlobDepsAndPropagateFilesystemExceptions(
        packageIdentifier: PackageIdentifier?,
        packageRoot: Root?,
        loadedPackage: LoadedPackage,
        env: SkyFunction.Environment,
        packageWasInError: Boolean
    ) {
        val globRequests: com.google.common.collect.ImmutableSet<GlobRequest?> =
            (loadedPackage as LoadedPackageWithGlobRequests).globRequests
        if (globRequests.isEmpty()) {
            return
        }

        val globsKey: GlobsValue.Key? = GlobsValue.key(packageIdentifier, packageRoot, globRequests)
        try {
            env.getValueOrThrow<E1?, E2?>(globsKey, IOException::class.java, BuildFileNotFoundException::class.java)
        } catch (e: InconsistentFilesystemException) {
            throw InternalInconsistentFilesystemException(packageIdentifier, e)
        } catch (e: FileSymlinkException) {
            // Please note that GlobsFunction or its deps FileFunction throws the first
            // `FileSymlinkException` discovered, which is consistent with how
            // PackageFunctionWithMultipleGlobDeps#handleGlobDepsAndPropagateFilesystemExceptions handles
            // FileSymlinkException caught.
            throw e
        } catch (e: IOException) {
            PackageFunction.Companion.maybeThrowFilesystemInconsistency(packageIdentifier, e, packageWasInError)
        } catch (e: BuildFileNotFoundException) {
            PackageFunction.Companion.maybeThrowFilesystemInconsistency(packageIdentifier, e, packageWasInError)
        }
    }

    override fun makeGlobber(
        nonSkyframeGlobber: NonSkyframeGlobber,
        packageId: PackageIdentifier?,
        packageRoot: Root?,
        env: SkyFunction.Environment?
    ): GlobsGlobber {
        return GlobsGlobber(nonSkyframeGlobber)
    }

    override fun newLoadedPackage(
        packageBuilder: Package.AbstractBuilder?, globber: Globber?, metrics: Metrics?
    ): LoadedPackage {
        return LoadedPackageWithGlobRequests(
            packageBuilder, metrics, (globber as GlobsGlobber).getGlobRequests()
        )
    }
}
