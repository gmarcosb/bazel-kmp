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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.PackageIdentifier

/** The file (BUILD, WORKSPACE, etc.) that defines this package, referred to as the "build file".  */
enum class BuildFileName(filename: String) {
    WORKSPACE("WORKSPACE") {
        override fun getBuildFileFragment(packageIdentifier: PackageIdentifier?): PathFragment? {
            return getFilenameFragment()
        }
    },
    WORKSPACE_DOT_BAZEL("WORKSPACE.bazel") {
        override fun getBuildFileFragment(packageIdentifier: PackageIdentifier?): PathFragment? {
            return getFilenameFragment()
        }
    },
    WORKSPACE_DOT_BZLMOD("WORKSPACE.bzlmod") {
        override fun getBuildFileFragment(packageIdentifier: PackageIdentifier?): PathFragment? {
            return getFilenameFragment()
        }
    },
    MODULE_DOT_BAZEL("MODULE.bazel") {
        override fun getBuildFileFragment(packageIdentifier: PackageIdentifier?): PathFragment? {
            return getFilenameFragment()
        }
    },
    BUILD("BUILD") {
        override fun getBuildFileFragment(packageIdentifier: PackageIdentifier): PathFragment {
            return packageIdentifier.getPackageFragment().getRelative(getFilenameFragment())
        }
    },
    BUILD_DOT_BAZEL("BUILD.bazel") {
        override fun getBuildFileFragment(packageIdentifier: PackageIdentifier): PathFragment {
            return packageIdentifier.getPackageFragment().getRelative(getFilenameFragment())
        }
    };

    private val filenameFragment: PathFragment?

    init {
        this.filenameFragment = PathFragment.create(filename)
    }

    fun getFilenameFragment(): PathFragment? {
        return filenameFragment
    }

    /**
     * Returns a [PathFragment] to the build file that defines the package.
     * 
     * @param packageIdentifier the identifier for this package
     */
    abstract fun getBuildFileFragment(packageIdentifier: PackageIdentifier?): PathFragment?
}
