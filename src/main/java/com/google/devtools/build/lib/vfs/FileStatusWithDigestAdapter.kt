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
package com.google.devtools.build.lib.vfs

import com.google.devtools.build.lib.vfs.FileStatus
import com.google.devtools.build.lib.vfs.FileStatusWithDigest
import java.io.IOException

/** An adapter from FileStatus to FileStatusWithDigest.  */
class FileStatusWithDigestAdapter private constructor(stat: FileStatus?) : FileStatusWithDigest {
    private val stat: FileStatus

    init {
        this.stat = com.google.common.base.Preconditions.checkNotNull<FileStatus>(stat)
    }

    val digest: ByteArray?
        get() = null

    val isFile: Boolean
        get() = stat.isFile()

    val isSpecialFile: Boolean
        get() = stat.isSpecialFile()

    val isDirectory: Boolean
        get() = stat.isDirectory()

    val isSymbolicLink: Boolean
        get() = stat.isSymbolicLink()

    @get:Throws(IOException::class)
    val size: Long
        get() = stat.getSize()

    @get:Throws(IOException::class)
    val lastModifiedTime: Long
        get() = stat.getLastModifiedTime()

    @get:Throws(IOException::class)
    val lastChangeTime: Long
        get() = stat.getLastChangeTime()

    @get:Throws(IOException::class)
    val nodeId: Long
        get() = stat.getNodeId()

    val permissions: Int
        get() = stat.getPermissions()

    companion object {
        fun maybeAdapt(stat: FileStatus?): FileStatusWithDigest? {
            return if (stat == null)
                null
            else
                if (stat is FileStatusWithDigest)
                    stat
                else
                    FileStatusWithDigestAdapter(stat)
        }
    }
}
