// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.genquery

import com.google.devtools.build.lib.cmdline.Label

/** Provide packages and targets to the query operations using precomputed transitive closure.  */
internal class GenQueryPackageProvider(
    pkgMap: com.google.common.collect.ImmutableMap<PackageIdentifier?, Package?>,
    labelToTarget: com.google.common.collect.ImmutableMap<Label?, Target?>
) : PackageProvider, CachingPackageLocator {
    private val pkgMap: com.google.common.collect.ImmutableMap<PackageIdentifier?, Package?>
    private val labelToTarget: com.google.common.collect.ImmutableMap<Label?, Target?>

    init {
        this.pkgMap = pkgMap
        this.labelToTarget = labelToTarget
    }

    val validTargetPredicate: com.google.common.base.Predicate<Label?>
        get() = com.google.common.base.Predicates.`in`<Label?>(labelToTarget.keySet())

    @Throws(NoSuchPackageException::class)
    public override fun getPackage(eventHandler: ExtendedEventHandler?, packageId: PackageIdentifier?): Package {
        val pkg: Package? = pkgMap.get(packageId)
        if (pkg != null) {
            return pkg
        }
        // Prefer to throw a checked exception on error; malformed genquery should not crash.
        throw NoSuchPackageException(packageId, "is not within the scope of the query")
    }

    @Throws(NoSuchPackageException::class)
    public override fun getBuildFile(eventHandler: ExtendedEventHandler?, packageId: PackageIdentifier?): InputFile {
        return getPackage(eventHandler, packageId).getBuildFile()
    }

    @Throws(NoSuchPackageException::class, NoSuchTargetException::class)
    public override fun getTarget(eventHandler: ExtendedEventHandler?, label: Label): Target {
        // Try to perform only one map lookup in the common case.
        val target: Target? = labelToTarget.get(label)
        if (target != null) {
            return target
        }
        // Prefer to throw a checked exception on error; malformed genquery should not crash.
        // Because it'd be more valuable, see if NoSuchPackageException should be thrown:
        val unused: Package = getPackage(eventHandler, label.getPackageIdentifier())
        throw NoSuchTargetException(label, "is not within the scope of the query")
    }

    public override fun isPackage(eventHandler: ExtendedEventHandler?, packageName: PackageIdentifier?): Boolean {
        throw java.lang.UnsupportedOperationException()
    }

    public override fun getBuildFileForPackage(packageId: PackageIdentifier?): com.google.devtools.build.lib.vfs.Path? {
        val pkg: Package? = pkgMap.get(packageId)
        if (pkg == null) {
            return null
        }
        return pkg.getBuildFile().getPath()
    }

    public override fun getBaseNameForLoadedPackage(packageName: PackageIdentifier?): String? {
        // TODO(b/123795023): we should have the data here but we don't have all packages for Starlark
        //  loads present here.
        val pkg: Package? = pkgMap.get(packageName)
        return if (pkg == null) null else pkg.getBuildFileLabel().name
    }
}
