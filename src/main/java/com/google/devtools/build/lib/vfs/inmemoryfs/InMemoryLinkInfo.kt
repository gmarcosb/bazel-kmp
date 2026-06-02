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
package com.google.devtools.build.lib.vfs.inmemoryfs

import com.google.devtools.build.lib.clock.Clock
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable

/**
 * This interface represents a symbolic link to an absolute or relative path, stored in an
 * InMemoryFileSystem.
 */
@ThreadSafe
@Immutable
internal class InMemoryLinkInfo(clock: Clock?, linkContent: PathFragment) : InMemoryContentInfo(clock) {
    private val linkContent: PathFragment
    private val normalizedLinkContent: PathFragment?

    init {
        this.linkContent = linkContent
        this.normalizedLinkContent = linkContent
    }

    override fun isDirectory(): Boolean {
        return false
    }

    override fun isSymbolicLink(): Boolean {
        return true
    }

    override fun isFile(): Boolean {
        return false
    }

    override fun isSpecialFile(): Boolean {
        return false
    }

    override fun getSize(): Long {
        return linkContent.getSafePathString().length().toLong()
    }

    /**
     * Returns the content of the symbolic link.
     */
    fun getLinkContent(): PathFragment {
        return linkContent
    }

    /**
     * Returns the content of the symbolic link, with ".." and "." removed
     * (except for the possibility of necessary ".." segments at the beginning).
     */
    fun getNormalizedLinkContent(): PathFragment? {
        return normalizedLinkContent
    }

    override fun toString(): String {
        return super.toString() + " -> " + linkContent
    }
}
