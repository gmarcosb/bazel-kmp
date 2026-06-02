// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.testing.common

import com.google.devtools.build.lib.vfs.Symlinks
import java.io.IOException

/** Namespace for helpers to test recursive directory listings.  */
object DirectoryListingHelper {
    /** Shorthand for [Dirent] of [Dirent.Type.FILE] type with a given name.  */
    @kotlin.jvm.JvmStatic
    fun file(name: String?): com.google.devtools.build.lib.vfs.Dirent {
        return com.google.devtools.build.lib.vfs.Dirent(name, com.google.devtools.build.lib.vfs.Dirent.Type.FILE)
    }

    /** Shorthand for [Dirent] of [Dirent.Type.SYMLINK] type with a given name.  */
    @kotlin.jvm.JvmStatic
    fun symlink(name: String?): com.google.devtools.build.lib.vfs.Dirent {
        return com.google.devtools.build.lib.vfs.Dirent(name, com.google.devtools.build.lib.vfs.Dirent.Type.SYMLINK)
    }

    /** Shorthand for [Dirent] of [Dirent.Type.DIRECTORY] type with a given name.  */
    @kotlin.jvm.JvmStatic
    fun directory(name: String?): com.google.devtools.build.lib.vfs.Dirent {
        return com.google.devtools.build.lib.vfs.Dirent(name, com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY)
    }

    /**
     * Returns all of the leaf [dirents][Dirent] under a given directory.
     * 
     * 
     * For directory structure of:
     * 
     * <pre>
     * dir/dir2
     * dir/file1
     * dir/subdir/file2
    </pre> * 
     * 
     * will return: `FILE(dir/file1), FILE(dir/subdir/file2), DIRECTORY(dir/dir2)`.
     */
    @Throws(IOException::class)
    fun leafDirectoryEntries(path: com.google.devtools.build.lib.vfs.Path): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.vfs.Dirent?> {
        val entries: com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.vfs.Dirent?> =
            com.google.common.collect.ImmutableList.builder<com.google.devtools.build.lib.vfs.Dirent?>()
        leafDirectoryEntriesInternal(path, "", entries)
        return entries.build()
    }

    @Throws(IOException::class)
    private fun leafDirectoryEntriesInternal(
        path: com.google.devtools.build.lib.vfs.Path,
        prefix: String,
        entries: com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.vfs.Dirent?>
    ) {
        var isEmpty = true
        for (dirent in path.readdir(Symlinks.NOFOLLOW)) {
            isEmpty = false
            val entryName = if (prefix.isEmpty()) dirent.getName() else prefix + "/" + dirent.getName()

            if (dirent.getType() == com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY) {
                leafDirectoryEntriesInternal(path.getChild(dirent.getName()), entryName, entries)
                continue
            }

            entries.add(com.google.devtools.build.lib.vfs.Dirent(entryName, dirent.getType()))
        }

        // Skip adding the root if it's empty.
        if (isEmpty && !prefix.isEmpty()) {
            entries.add(
                com.google.devtools.build.lib.vfs.Dirent(
                    prefix,
                    com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY
                )
            )
        }
    }
}
