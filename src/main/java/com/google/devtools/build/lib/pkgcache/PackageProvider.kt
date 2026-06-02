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

import com.google.devtools.build.lib.cmdline.PackageIdentifier

/**
 * API for retrieving packages. Implementations generally load packages to fulfill requests.
 * 
 * 
 * **Concurrency**: Implementations should be thread safe for [.getPackage].
 */
interface PackageProvider : TargetProvider {
    /**
     * Returns the [Package] named "packageName". If there is no such package (e.g. `isPackage(packageName)` returns false), throws a [NoSuchPackageException].
     * 
     * 
     * The returned package may contain lexical/grammatical errors, in which case `
     * pkg.containsErrors() == true`. Such packages may be missing some rules. Any rules that
     * are present may soundly be used for builds, though.
     * 
     * @param eventHandler the eventHandler on which to report warnings and errors associated with
     * loading the package, but only if the package has not already been loaded
     * @param packageName a legal package name.
     * @throws NoSuchPackageException if the package could not be found.
     * @throws InterruptedException if the package loading was interrupted.
     */
    @Throws(NoSuchPackageException::class, java.lang.InterruptedException::class)
    fun getPackage(
        eventHandler: ExtendedEventHandler?,
        packageName: PackageIdentifier?
    ): com.google.devtools.build.lib.packages.Package?

    /**
     * If a [Target] is owned by a monolithic [Package], returns it; otherwise, loads and
     * returns the full package encompassing the target's package piece.
     * 
     * @throws NoSuchPackageException if target is owned by a [PackagePiece], and the full
     * package could not be loaded due to an error while loading a different package piece.
     * @throws InterruptedException if the package loading was interrupted.
     */
    @Throws(NoSuchPackageException::class, java.lang.InterruptedException::class)
    fun getPackage(
        eventHandler: ExtendedEventHandler?,
        target: com.google.devtools.build.lib.packages.Target
    ): com.google.devtools.build.lib.packages.Package? {
        val packageoid: Packageoid = target.getPackageoid()
        if (packageoid is com.google.devtools.build.lib.packages.Package) {
            // Monolithic package.
            return packageoid
        }
        return getPackage(eventHandler, packageoid.getPackageIdentifier())
    }

    /**
     * Returns whether a package with the given name exists. That is, returns whether all the
     * following hold
     * 
     * 
     *  1. `packageName` is a valid package name
     *  1. there is a BUILD file for the package
     *  1. the package is not considered deleted via --deleted_packages
     * 
     * 
     * 
     * If these don't hold, then attempting to read the package with [.getPackage] may fail
     * or may return a package containing errors.
     * 
     * @param eventHandler if `packageName` specifies a package that could not be looked up
     * because of a symlink cycle or IO error, the error is reported here
     * @param packageName the name of the package.
     */
    @Throws(InconsistentFilesystemException::class, java.lang.InterruptedException::class)
    fun isPackage(eventHandler: ExtendedEventHandler?, packageName: PackageIdentifier?): Boolean

    /**
     * Returns the BUILD file target of the given package, loading, parsing and evaluating either the
     * full package (if lazy macro expansion is disabled) or just the package piece owning the BUILD
     * file (if lazy macro expansion is enabled) if it is not already loaded.
     * 
     * @throws NoSuchPackageException if the package could not be found
     * @throws NoSuchPackagePieceException if lazy macro expansion is enabled, and the package piece
     * owning the BUILD file failed validation
     * @throws InterruptedException if the package loading was interrupted
     */
    @Throws(NoSuchPackageException::class, NoSuchPackagePieceException::class, java.lang.InterruptedException::class)
    fun getBuildFile(eventHandler: ExtendedEventHandler?, packageName: PackageIdentifier?): InputFile?

    @Throws(java.lang.InterruptedException::class)
    override fun getBuildFile(target: com.google.devtools.build.lib.packages.Target): InputFile? {
        val packageoid: Packageoid = target.getPackageoid()
        if (packageoid is com.google.devtools.build.lib.packages.Package) {
            // Monolithic package.
            return packageoid.getBuildFile()
        } else if (packageoid is com.google.devtools.build.lib.packages.PackagePiece.ForBuildFile) {
            // Lazy macro expansion, target is top-level.
            return packageoid.getBuildFile()
        } else {
            // Lazy macro expansion, target is in a PackagePiece.ForMacro, we need to retrieve the
            // BUILD file from the (already loaded) PackagePiece.ForBuildFile.
            val localEventHandler: StoredEventHandler = StoredEventHandler()
            val buildFile: InputFile?
            try {
                buildFile =
                    getBuildFile(localEventHandler, target.getPackageMetadata().packageIdentifier)
            } catch (e: NoSuchPackageException) {
                // If a PackagePiece.ForMacro exists, its corresponding PackagePiece.ForBuildFile must also
                // exist (and already be loaded).
                throw java.lang.IllegalStateException(
                    java.lang.String.format(
                        "Bug in package loading machinery: failed to load package piece for BUILD file of"
                                + " already-loaded target %s",
                        target
                    ),
                    e
                )
            } catch (e: NoSuchPackagePieceException) {
                throw java.lang.IllegalStateException(
                    java.lang.String.format(
                        "Bug in package loading machinery: failed to load package piece for BUILD file of"
                                + " already-loaded target %s",
                        target
                    ),
                    e
                )
            }
            // If PackagePiece.ForMacro was loaded, its corresponding PackagePiece.ForBuildFile could not
            // be in error.
            checkState(
                !localEventHandler.hasErrors(),
                "Bug in package loading machinery: unexpected error while retrieving package piece for"
                        + " BUILD file of already-loaded target %s: %s",
                target,
                localEventHandler.getEvents()
            )
            return buildFile
        }
    }

    @Throws(NoSuchPackageException::class, java.lang.InterruptedException::class)
    override fun getSiblingTargetsInPackage(
        eventHandler: ExtendedEventHandler?, target: com.google.devtools.build.lib.packages.Target
    ): com.google.common.collect.ImmutableCollection<com.google.devtools.build.lib.packages.Target?> {
        return getPackage(eventHandler, target).getTargets().values()
    }
}
