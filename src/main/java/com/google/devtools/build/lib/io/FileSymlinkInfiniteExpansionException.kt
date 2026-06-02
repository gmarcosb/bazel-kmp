// Copyright 2015 The Bazel Authors. All rights reserved.
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

/** Exception indicating that a symlink has an unbounded expansion on resolution.  */
@com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
class FileSymlinkInfiniteExpansionException(
    pathToChain: com.google.common.collect.ImmutableList<RootedPath?>?,
    chain: com.google.common.collect.ImmutableList<RootedPath?>
) : FileSymlinkException("Infinite symlink expansion") {
    private val pathToChain: com.google.common.collect.ImmutableList<RootedPath?>?
    private val chain: com.google.common.collect.ImmutableList<RootedPath?>

    init {
        // The infinite expansion has already been reported by
        // FileSymlinkInfiniteExpansionUniquenessValue, but we still want to have a readable
        // #getMessage.
        this.pathToChain = pathToChain
        this.chain = chain
    }

    /**
     * The symlink path to the symlink that is the root cause of the infinite expansion. For example,
     * suppose 'a' -> 'b' -> 'c' -> 'd' -> 'c/nope'. The path to the chain is 'a', 'b'.
     */
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    fun getPathToChain(): com.google.common.collect.ImmutableList<RootedPath?>? {
        return pathToChain
    }

    /**
     * The symlink chain that is the root cause of the infinite expansion. For example, suppose 'a' ->
     * 'b' -> 'c' -> 'd' -> 'c/nope'. The chain is 'c', 'd', 'c/nope'.
     */
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    fun getChain(): com.google.common.collect.ImmutableList<RootedPath?> {
        return chain
    }

    override fun getUserFriendlyMessage(): String {
        return ("Infinite symlink expansion: "
                + com.google.common.base.Joiner.on("- > ").join(
            com.google.common.collect.Iterables.transform<RootedPath?, com.google.devtools.build.lib.vfs.Path?>(
                chain,
                com.google.common.base.Function { obj: RootedPath? -> obj.asPath() })
        ))
    }
}

