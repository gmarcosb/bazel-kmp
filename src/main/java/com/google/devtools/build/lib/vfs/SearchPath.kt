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
package com.google.devtools.build.lib.vfs

import com.google.devtools.build.lib.vfs.PathFragment
import java.io.IOException

/**
 * Parse and search $PATH, the binary search path for executables.
 */
object SearchPath {
    private val SEPARATOR: com.google.common.base.Splitter = com.google.common.base.Splitter.on(':')

    /**
     * Parses a $PATH value into a list of paths. A Null search path is treated as an empty one.
     * Relative entries in $PATH are ignored.
     */
    fun parse(
        fs: com.google.devtools.build.lib.vfs.FileSystem,
        searchPath: String?
    ): MutableList<com.google.devtools.build.lib.vfs.Path?> {
        val paths: MutableList<com.google.devtools.build.lib.vfs.Path?> =
            java.util.ArrayList<com.google.devtools.build.lib.vfs.Path?>()
        if (searchPath == null) {
            return paths
        }
        for (p in com.google.devtools.build.lib.vfs.SearchPath.SEPARATOR.split(searchPath)) {
            val pf: PathFragment = PathFragment.Companion.create(p)

            if (pf.isAbsolute()) {
                paths.add(fs.getPath(pf))
            }
        }
        return paths
    }

    /**
     * Finds the first executable called `exe` in the searchPath.
     * If `exe` is not a basename, it will always return null. This should be equivalent to
     * running which(1).
     */
    fun which(
        searchPath: MutableList<com.google.devtools.build.lib.vfs.Path>,
        exe: String?
    ): com.google.devtools.build.lib.vfs.Path? {
        val fragment: PathFragment = PathFragment.Companion.create(exe)
        if (fragment.isAbsolute() || !fragment.isSingleSegment()) {
            return null
        }

        for (p in searchPath) {
            val ch: com.google.devtools.build.lib.vfs.Path = p.getChild(exe)

            try {
                if (ch.exists() && ch.isExecutable()) {
                    return ch
                }
            } catch (e: IOException) {
                // Like which(1), we ignore any IO exception (disk on fire, permission denied etc.)
            }
        }
        return null
    }
}
