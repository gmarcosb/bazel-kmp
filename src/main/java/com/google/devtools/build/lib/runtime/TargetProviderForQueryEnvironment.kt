// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.cmdline.Label

/**
 * Serves as TargetProvider using WalkableGraph as direct access to graph. Refers to delegate in
 * case if WalkableGraph has not value for specific key.
 */
class TargetProviderForQueryEnvironment(walkableGraph: WalkableGraph?, delegate: PackageProvider?) : TargetProvider {
    private val walkableGraph: WalkableGraph

    /** If WalkableGraph has not node requested, then delegate used as fall back strategy.  */
    private val delegate: PackageProvider

    init {
        this.walkableGraph = com.google.common.base.Preconditions.checkNotNull<WalkableGraph>(walkableGraph)
        this.delegate = com.google.common.base.Preconditions.checkNotNull<PackageProvider>(delegate)
    }

    @Throws(NoSuchPackageException::class, NoSuchTargetException::class, java.lang.InterruptedException::class)
    public override fun getTarget(eventHandler: ExtendedEventHandler?, label: Label): Target {
        val optional: java.util.Optional<Package?> = getPackageFromWalkableGraph(label.getPackageIdentifier())
        if (optional.isPresent()) {
            return optional.get().getTarget(label.name)
        }

        return delegate.getTarget(eventHandler, label)
    }

    @Throws(java.lang.InterruptedException::class)
    public override fun getBuildFile(target: Target): InputFile {
        if (target.getPackageoid() is Package) {
            // Monolithic package.
            return pkg.getBuildFile()
        } else if (target.getPackageoid() is ForBuildFile) {
            // Lazy macro expansion, target is top-level.
            return forBuildFile.getBuildFile()
        }
        // Lazy macro expansion mode, target is in a PackagePiece.ForMacro.
        val skyKey: ForBuildFile =
            ForBuildFile(target.getPackageMetadata().packageIdentifier())
        val skyValue: SkyValue? = walkableGraph.getValue(skyKey)
        if (skyValue != null) {
            val packageValue: ForBuildFile = skyValue as ForBuildFile
            return packageValue.getPackagePiece().getBuildFile()
        }
        if (walkableGraph.getException(skyKey) != null) {
            throw illegalErrorInPackagePieceForBuildFile(target, walkableGraph.getException(skyKey))
        }
        try {
            checkCycle(skyKey)
        } catch (e: NoSuchPackageException) {
            throw illegalErrorInPackagePieceForBuildFile(target, e)
        }

        return delegate.getBuildFile(target)
    }

    @Throws(NoSuchPackageException::class, java.lang.InterruptedException::class)
    public override fun getSiblingTargetsInPackage(
        eventHandler: ExtendedEventHandler?, target: Target
    ): com.google.common.collect.ImmutableCollection<Target?> {
        val optional: java.util.Optional<Package?> =
            getPackageFromWalkableGraph(target.getPackageMetadata().packageIdentifier())
        if (optional.isPresent()) {
            return optional.get().getTargets().values()
        }
        return delegate.getSiblingTargetsInPackage(eventHandler, target)
    }

    @Throws(java.lang.InterruptedException::class, NoSuchPackageException::class)
    private fun getPackageFromWalkableGraph(pkgId: PackageIdentifier?): java.util.Optional<Package?> {
        val skyValue: SkyValue? = walkableGraph.getValue(pkgId)

        if (skyValue != null) {
            val packageValue: PackageValue = skyValue as PackageValue
            return java.util.Optional.of<Package?>(packageValue.getPackage())
        }

        val exception: java.lang.Exception? = walkableGraph.getException(pkgId)
        if (exception != null) {
            // PackageFunction should be catching, swallowing, and rethrowing all transitive
            // errors as NoSuchPackageExceptions or constructing packages with errors.
            com.google.common.base.Throwables.throwIfInstanceOf<X?>(exception, NoSuchPackageException::class.java)
            com.google.common.base.Throwables.throwIfUnchecked(exception)
            throw java.lang.IllegalStateException(
                java.lang.String.format("Unexpected Exception type from PackageValue for %s", pkgId)
            )
        }
        checkCycle(pkgId)
        return java.util.Optional.empty<Package?>()
    }

    @Throws(java.lang.InterruptedException::class, NoSuchPackageException::class)
    private fun checkCycle(key: SkyKey?) {
        if (walkableGraph.isCycle(key)) {
            val pkgId: PackageIdentifier? =
                if (key is ForBuildFile)
                    key.getPackageIdentifier()
                else
                    key as PackageIdentifier?
            throw BuildFileContainsErrorsException(
                pkgId, "Cycle encountered while loading package " + pkgId
            )
        }
    }

    companion object {
        private fun illegalErrorInPackagePieceForBuildFile(
            target: Target?, cause: java.lang.Exception?
        ): java.lang.IllegalStateException {
            return java.lang.IllegalStateException(
                java.lang.String.format(
                    "Bug in package loading machinery: failed to load package piece for BUILD file of"
                            + " already-loaded target %s",
                    target
                ),
                cause
            )
        }
    }
}
