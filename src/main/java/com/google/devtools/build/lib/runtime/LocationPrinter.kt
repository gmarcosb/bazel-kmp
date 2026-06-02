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

import com.google.devtools.build.lib.pkgcache.PathPackageLocator

/** Converts [Location] objects to a human-friendly string.  */
class LocationPrinter(private val attemptToPrintRelativePaths: Boolean, workspacePathFragment: PathFragment?) {
    private val workspacePathFragment: PathFragment?
    private val packagePathRootsRef: AtomicReference<com.google.common.collect.ImmutableList<Root?>?> =
        AtomicReference<com.google.common.collect.ImmutableList<Root?>?>(com.google.common.collect.ImmutableList.of<Root?>())

    init {
        this.workspacePathFragment = workspacePathFragment
    }

    fun packageLocatorCreated(packageLocator: PathPackageLocator) {
        packagePathRootsRef.set(packageLocator.getPathEntries())
    }

    fun getLocationString(location: net.starlark.java.syntax.Location): String {
        return if (attemptToPrintRelativePaths)
            getRelativeLocationString(location, workspacePathFragment, packagePathRootsRef.get())
        else
            location.toString()
    }

    companion object {
        @com.google.common.annotations.VisibleForTesting
        fun getRelativeLocationString(
            location: net.starlark.java.syntax.Location,
            workspacePathFragment: PathFragment?,
            packagePathRoots: com.google.common.collect.ImmutableList<Root>
        ): String {
            var relativePathToUse: PathFragment? = null
            val locationPathFragment: PathFragment = PathFragment.create(location.file())
            if (locationPathFragment.isAbsolute()) {
                if (workspacePathFragment != null && locationPathFragment.startsWith(workspacePathFragment)) {
                    relativePathToUse = locationPathFragment.relativeTo(workspacePathFragment)
                } else {
                    for (packagePathRoot in packagePathRoots) {
                        if (packagePathRoot.contains(locationPathFragment)) {
                            relativePathToUse = packagePathRoot.relativize(locationPathFragment)
                            break
                        }
                    }
                }
            }

            val b: java.lang.StringBuilder = java.lang.StringBuilder()
            b.append(if (relativePathToUse != null) relativePathToUse else locationPathFragment)
            val line: Int = location.line()
            if (line != 0) {
                b.append(':').append(line)
                val column: Int = location.column()
                if (column != 0) {
                    b.append(':').append(column)
                }
            }
            return b.toString()
        }
    }
}
