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
package com.google.devtools.build.lib.io

import com.google.devtools.build.lib.io.FileSymlinkException
import com.google.devtools.build.lib.vfs.RootedPath

/** Exception indicating that a cycle was found in the filesystem.  */
@com.google.common.annotations.VisibleForTesting
class FileSymlinkCycleException @com.google.common.annotations.VisibleForTesting constructor(
    pathToCycle: com.google.common.collect.ImmutableList<RootedPath?>?,
    cycle: com.google.common.collect.ImmutableList<RootedPath?>
) : FileSymlinkException("Symlink cycle") {
    private val pathToCycle: com.google.common.collect.ImmutableList<RootedPath?>?
    private val cycle: com.google.common.collect.ImmutableList<RootedPath?>

    init {
        // The cycle itself has already been reported by FileSymlinkCycleUniquenessValue, but we still
        // want to have a readable #getMessage.
        this.pathToCycle = pathToCycle
        this.cycle = cycle
    }

    /**
     * The symlink path to the symlink cycle. For example, suppose 'a' -> 'b' -> 'c' -> 'd' -> 'c'.
     * The path to the cycle is 'a', 'b'.
     */
    @com.google.common.annotations.VisibleForTesting
    fun getPathToCycle(): com.google.common.collect.ImmutableList<RootedPath?>? {
        return pathToCycle
    }

    /**
     * The symlink cycle. For example, suppose 'a' -> 'b' -> 'c' -> 'd' -> 'c'. The cycle is 'c', 'd'.
     */
    @com.google.common.annotations.VisibleForTesting
    fun getCycle(): com.google.common.collect.ImmutableList<RootedPath?> {
        return cycle
    }

    val userFriendlyMessage: String
        get() = ("Symlink cycle: "
                + com.google.common.base.Joiner.on("- > ").join(
            com.google.common.collect.Iterables.transform<RootedPath?, com.google.devtools.build.lib.vfs.Path?>(
                cycle,
                com.google.common.base.Function { obj: RootedPath? -> obj.asPath() })
        ))
}
