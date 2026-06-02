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
 * Computes the [PackageValue] without performing Skyframe globbing.
 * 
 * 
 * [PackageFunctionWithoutGlobDeps] subclass is created when the globbing strategy is
 * [com.google.devtools.build.lib.skyframe.PackageFunction.GlobbingStrategy.NON_SKYFRAME],
 * which is used for non-incremental evaluations with no GLOB nodes queried and stored in Skyframe.
 */
internal class PackageFunctionWithoutGlobDeps(
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
    private class LoadedPackageWithoutDeps(builder: Package.AbstractBuilder?, metrics: Metrics?) :
        LoadedPackage(builder, metrics)

    override fun handleGlobDepsAndPropagateFilesystemExceptions(
        packageIdentifier: PackageIdentifier?,
        packageRoot: Root?,
        loadedPackage: LoadedPackage?,
        env: SkyFunction.Environment?,
        packageWasInError: Boolean
    ) {
        // No-op for non-Skyframe globbing.
    }

    override fun makeGlobber(
        nonSkyframeGlobber: NonSkyframeGlobber?,
        packageId: PackageIdentifier?,
        packageRoot: Root?,
        env: SkyFunction.Environment?
    ): Globber? {
        return nonSkyframeGlobber
    }

    override fun newLoadedPackage(
        packageBuilder: Package.AbstractBuilder?, globber: Globber?, metrics: Metrics?
    ): LoadedPackage {
        return LoadedPackageWithoutDeps(packageBuilder, metrics)
    }
}
