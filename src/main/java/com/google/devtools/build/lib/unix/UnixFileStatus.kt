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
package com.google.devtools.build.lib.unix

import com.google.devtools.build.lib.unix.NativePosixFilesService.Stat
import com.google.devtools.build.lib.unix.UnixMode
import com.google.devtools.build.lib.vfs.FileStatus

/**
 * An implementation of [FileStatus] backed by the result of a stat(2) system call.
 * 
 * 
 * This class is optimized for memory usage. Fields not required by Bazel are omitted.
 */
class UnixFileStatus internal constructor(stat: Stat) : FileStatus {
    private val mode: Int
    val lastModifiedTime: Long // milliseconds since Unix epoch
    val lastChangeTime: Long // milliseconds since Unix epoch
    val size: Long

    // TODO(tjgq): Consider deriving this value from both st_dev and st_ino.
    val nodeId: Long

    /** Constructs a [UnixFileStatus] from a [NativePosixFilesService.Stat].  */
    init {
        this.mode = stat.mode
        this.lastModifiedTime = stat.mtime
        this.lastChangeTime = stat.ctime
        this.size = stat.size
        this.nodeId = stat.ino
    }

    val direntType: com.google.devtools.build.lib.vfs.Dirent.Type?
        get() = UnixMode.getDirentTypeFromMode(mode)

    val isFile: Boolean
        get() = UnixMode.isFile(mode)

    val isSpecialFile: Boolean
        get() = UnixMode.isSpecialFile(mode)

    val isDirectory: Boolean
        get() = UnixMode.isDirectory(mode)

    val isSymbolicLink: Boolean
        get() = UnixMode.isSymbolicLink(mode)

    val permissions: Int
        get() = UnixMode.getPermissions(mode)

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("mode", java.lang.String.format("0%06o", mode))
            .add("mtime", this.lastModifiedTime)
            .add("ctime", this.lastChangeTime)
            .add("size", size)
            .add("ino", this.nodeId)
            .toString()
    }
}
