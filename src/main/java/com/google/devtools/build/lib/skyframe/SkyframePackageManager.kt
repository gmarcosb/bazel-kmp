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

import com.google.devtools.build.lib.cmdline.Label

internal class SkyframePackageManager(
    packageLoader: SkyframePackageLoader,
    syscallCache: SyscallCache?,
    pkgLocator: java.util.function.Supplier<PathPackageLocator?>,
    numPackagesSuccessfullyLoaded: AtomicInteger
) : PackageManager, CachingPackageLocator {
    private val packageLoader: SkyframePackageLoader
    private val syscallCache: SyscallCache?
    private val pkgLocator: java.util.function.Supplier<PathPackageLocator?>
    private val numPackagesSuccessfullyLoaded: AtomicInteger

    init {
        this.packageLoader = packageLoader
        this.pkgLocator = pkgLocator
        this.syscallCache = syscallCache
        this.numPackagesSuccessfullyLoaded = numPackagesSuccessfullyLoaded
    }

    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    @Throws(NoSuchPackageException::class, java.lang.InterruptedException::class)
    public override fun getPackage(
        eventHandler: ExtendedEventHandler?,
        packageIdentifier: PackageIdentifier?
    ): Package? {
        return packageLoader.getPackage(eventHandler, packageIdentifier)
    }

    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    @Throws(NoSuchPackageException::class, NoSuchPackagePieceException::class, java.lang.InterruptedException::class)
    public override fun getBuildFile(
        eventHandler: ExtendedEventHandler?, packageIdentifier: PackageIdentifier?
    ): InputFile? {
        return packageLoader.getBuildFile(eventHandler, packageIdentifier)
    }

    @Throws(NoSuchPackageException::class, NoSuchTargetException::class, java.lang.InterruptedException::class)
    public override fun getTarget(eventHandler: ExtendedEventHandler?, label: Label): Target {
        // TODO(https://github.com/bazelbuild/bazel/issues/23852): don't expand the full package if lazy
        // macro expansion is enabled.
        return com.google.common.base.Preconditions.checkNotNull<Any?>(
            getPackage(
                eventHandler,
                label.getPackageIdentifier()
            ), label
        )
            .getTarget(label.name)
    }

    val andClearStatistics: PackageManagerStatistics?
        get() {
            val packagesSuccessfullyLoaded: Int = numPackagesSuccessfullyLoaded.getAndSet(0)
            return PackageManagerStatistics { packagesSuccessfullyLoaded }
        }

    @Throws(InconsistentFilesystemException::class, java.lang.InterruptedException::class)
    public override fun isPackage(eventHandler: ExtendedEventHandler?, packageName: PackageIdentifier?): Boolean {
        return getBuildFileForPackage(packageName) != null
    }

    public override fun dump(printStream: PrintStream?) {
        packageLoader.dumpPackages(printStream)
    }

    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    public override fun getBuildFileForPackage(packageName: PackageIdentifier?): com.google.devtools.build.lib.vfs.Path? {
        // Note that this method needs to be thread-safe, as it is currently used concurrently by
        // legacy blaze code.
        if (packageLoader.isPackageDeleted(packageName)) {
            return null
        }
        // TODO(bazel-team): Use a PackageLookupValue here [skyframe-loading]
        // TODO(bazel-team): The implementation in PackageCache also checks for duplicate packages, see
        // BuildFileCache#getBuildFile [skyframe-loading]
        return pkgLocator.get().getPackageBuildFileNullable(packageName, syscallCache)
    }

    public override fun getBaseNameForLoadedPackage(packageName: PackageIdentifier?): String {
        val pkgLookupValue: PackageLookupValue =
            com.google.common.base.Preconditions.checkNotNull<PackageLookupValue>(
                packageLoader.getPackageLookupValue(packageName),
                "Package should already have been visited: %s",
                packageName
            )
        checkState(
            pkgLookupValue.packageExists(), "Package must exist: %s %s", packageName, pkgLookupValue
        )
        return pkgLookupValue.buildFileName.getFilenameFragment().getBaseName()
    }

    val packagePath: PathPackageLocator?
        get() = pkgLocator.get()
}
