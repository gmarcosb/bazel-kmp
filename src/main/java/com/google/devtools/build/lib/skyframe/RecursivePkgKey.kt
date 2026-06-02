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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories

/**
 * A RecursivePkgKey is a tuple of a [RootedPath], `rootedPath`, defining the directory
 * to recurse beneath in search of packages, and an [ImmutableSet] of [PathFragment]s,
 * `excludedPaths`, relative to `rootedPath.getRoot`, defining the set of subdirectories
 * strictly beneath `rootedPath` to skip.
 * 
 * 
 * Throws [IllegalArgumentException] if `excludedPaths` contains any paths that are
 * equal to `rootedPath` or that are not beneath `rootedPath`.
 */
@ThreadSafe
open class RecursivePkgKey(
    repositoryName: RepositoryName,
    rootedPath: RootedPath,
    excludedPaths: IgnoredSubdirectories
) {
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    val repositoryName: RepositoryName

    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    val rootedPath: RootedPath

    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    val excludedPaths: IgnoredSubdirectories

    init {
        com.google.common.base.Preconditions.checkArgument(excludedPaths.allPathsAreUnder(rootedPath.getRootRelativePath()))
        this.repositoryName = repositoryName
        this.rootedPath = com.google.common.base.Preconditions.checkNotNull<RootedPath>(rootedPath)
        this.excludedPaths = com.google.common.base.Preconditions.checkNotNull<IgnoredSubdirectories>(excludedPaths)
    }

    fun getRepositoryName(): RepositoryName {
        return repositoryName
    }

    fun getRootedPath(): RootedPath {
        return rootedPath
    }

    fun getExcludedPaths(): IgnoredSubdirectories {
        return excludedPaths
    }

    override fun toString(): String {
        return "rootedPath=" + rootedPath + ", excludedPaths=<omitted>"
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o !is RecursivePkgKey) {
            return false
        }

        return excludedPaths.equals(o.excludedPaths)
                && rootedPath == o.rootedPath
                && repositoryName.equals(o.repositoryName)
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(rootedPath, excludedPaths, repositoryName)
    }
}
