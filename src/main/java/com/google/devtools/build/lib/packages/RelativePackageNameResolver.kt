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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.PackageIdentifier

/**
 * Resolves relative package names to absolute ones. Handles the absolute
 * package path marker ("//") and uplevel references ("..").
 */
class RelativePackageNameResolver(offset: PathFragment, discardBuild: Boolean) {
    private val offset: PathFragment
    private val discardBuild: Boolean

    /**
     * @param offset the base package path used to resolve relative paths
     * @param discardBuild if true, discards the last package path segment if
     * it is called "BUILD"
     */
    init {
        com.google.common.base.Preconditions.checkArgument(
            !offset.containsUplevelReferences(),
            "offset should not contain uplevel references"
        )

        this.offset = offset
        this.discardBuild = discardBuild
    }

    /**
     * Resolves the given package name with respect to the offset given in the
     * constructor.
     * 
     * @param pkg the relative package name to be resolved
     * @return the absolute package name
     * @throws InvalidPackageNameException if the package name cannot be resolved
     * (only syntactic checks are done -- it is not checked if the package
     * really exists or not)
     */
    @Throws(InvalidPackageNameException::class)
    fun resolve(pkg: String): String? {
        val isAbsolute: Boolean
        val relativePkg: String?

        if (pkg.startsWith("//")) {
            isAbsolute = true
            relativePkg = pkg.substring(2)
        } else if (pkg.startsWith("/")) {
            throw InvalidPackageNameException(
                PackageIdentifier.createInMainRepo(pkg),
                "package name cannot start with a single slash"
            )
        } else {
            isAbsolute = false
            relativePkg = pkg
        }

        var relative: PathFragment? = PathFragment.create(relativePkg)

        if (discardBuild && relative.getBaseName() == "BUILD") {
            relative = relative.getParentDirectory()
        }

        val result: PathFragment = if (isAbsolute) relative else offset.getRelative(relative)
        if (result.containsUplevelReferences()) {
            throw InvalidPackageNameException(
                PackageIdentifier.createInMainRepo(pkg),
                "package name contains too many '..' segments"
            )
        }

        return result.getPathString()
    }
}
