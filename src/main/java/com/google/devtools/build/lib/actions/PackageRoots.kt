// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.cmdline.PackageIdentifier

/**
 * An interface that provides information about package's source roots, that is, the paths on disk
 * that their BUILD files can be found at. Usually this information is not needed except for when
 * planting the symlink forest in the exec root, and when resolving source exec paths to artifacts
 * in an [ArtifactResolver].
 */
interface PackageRoots {
    /**
     * Returns a map from [PackageIdentifier] to [Path]. Should only be needed for
     * [planting the symlink forest][com.google.devtools.build.lib.buildtool.SymlinkForest].
     * 
     * 
     * If [PackageIdentifier.EMPTY_PACKAGE_ID] is present, then all top-level path entries
     * under the corresponding root are to be linked.
     */
    fun getPackageRootsMap(): com.google.common.collect.ImmutableMap<PackageIdentifier?, Root?>?

    fun getPackageRootLookup(): PackageRootLookup?

    /** Interface for getting the source root of a package, given its [PackageIdentifier].  */
    interface PackageRootLookup {
        /**
         * Returns the [ArtifactRoot] of a package, given its [PackageIdentifier]. May be
         * null if the given `packageIdentifier` does not correspond to a package in this build.
         * However, if there is a unique source root for all packages, this may return that root even if
         * the `packageIdentifier` given does not correspond to any packages.
         */
        fun getRootForPackage(packageIdentifier: PackageIdentifier?): Root?
    }
}
