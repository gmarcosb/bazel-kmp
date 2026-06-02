// Copyright 2026 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.unix

/** Constants and utilities for working with Unix file modes.  */
object UnixMode {
    // Note: even though POSIX doesn't specify the concrete values of these constants, all Unix
    // implementations we care about (Linux, macOS and BSDs) agree on them.
    const val S_IFMT: Int = 61440 // mask: filetype bitfields
    const val S_IFSOCK: Int = 49152 // socket
    const val S_IFLNK: Int = 40960 // symbolic link
    const val S_IFREG: Int = 32768 // regular file
    const val S_IFBLK: Int = 24576 // block device
    const val S_IFDIR: Int = 16384 // directory
    const val S_IFCHR: Int = 8192 // character device
    const val S_IFIFO: Int = 4096 // fifo
    const val S_ISUID: Int = 2048 // set UID bit
    const val S_ISGID: Int = 1024 // set GID bit (see below)
    const val S_ISVTX: Int = 512 // sticky bit (see below)
    const val S_IRWXA: Int = 511 // mask: all permissions
    const val S_IRWXU: Int = 448 // mask: file owner permissions
    const val S_IRUSR: Int = 256 // owner has read permission
    const val S_IWUSR: Int = 128 // owner has write permission
    const val S_IXUSR: Int = 64 // owner has execute permission
    const val S_IRWXG: Int = 56 // mask: group permissions
    const val S_IRGRP: Int = 32 // group has read permission
    const val S_IWGRP: Int = 16 // group has write permission
    const val S_IXGRP: Int = 8 // group has execute permission
    const val S_IRWXO: Int = 7 // mask: other permissions
    const val S_IROTH: Int = 4 // others have read permission
    const val S_IWOTH: Int = 2 // others have write permission
    const val S_IXOTH: Int = 1 // others have execute permission
    const val S_IEXEC: Int = 73 // owner, group, world execute

    /** Returns the [Dirent.Type] for the given mode.  */
    fun getDirentTypeFromMode(mode: Int): com.google.devtools.build.lib.vfs.Dirent.Type {
        if (isSpecialFile(mode)) {
            return com.google.devtools.build.lib.vfs.Dirent.Type.UNKNOWN
        } else if (isFile(mode)) {
            return com.google.devtools.build.lib.vfs.Dirent.Type.FILE
        } else if (isDirectory(mode)) {
            return com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY
        } else if (isSymbolicLink(mode)) {
            return com.google.devtools.build.lib.vfs.Dirent.Type.SYMLINK
        } else {
            return com.google.devtools.build.lib.vfs.Dirent.Type.UNKNOWN
        }
    }

    /** Returns whether the mode represents a file, including a special file.  */
    fun isFile(mode: Int): Boolean {
        val type = mode and S_IFMT
        return type == S_IFREG || isSpecialFile(mode)
    }

    /** Returns whether the mode represents a special file.  */
    fun isSpecialFile(mode: Int): Boolean {
        val type = mode and S_IFMT
        return type == S_IFSOCK || type == S_IFBLK || type == S_IFCHR || type == S_IFIFO
    }

    /** Returns whether the mode represents a directory.  */
    fun isDirectory(mode: Int): Boolean {
        val type = mode and S_IFMT
        return type == S_IFDIR
    }

    /** Returns whether the mode represents a symbolic link.  */
    fun isSymbolicLink(mode: Int): Boolean {
        val type = mode and S_IFMT
        return type == S_IFLNK
    }

    /** Returns the permissions of the mode.  */
    fun getPermissions(mode: Int): Int {
        return mode and S_IRWXA
    }
}
