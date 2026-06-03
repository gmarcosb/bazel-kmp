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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.util.Fingerprint

/**
 * In case we can't get a fast digest from the filesystem, we store this metadata as a proxy to the
 * file contents. Currently it is up to two timestamps and a "node id". On Linux, macOS and Windows
 * we use both ctime and mtime, on Linux and macOS also the inode number. On other OSes, only the
 * mtime is used. We might want to add the device number in the future.
 * 
 * 
 * For a Linux example of why mtime alone is insufficient, note that 'mv' preserves mtime. So if
 * files 'a' and 'b' initially have the same timestamp, then we would think 'b' is unchanged after
 * the user executes `mv a b` between two builds.
 * 
 * 
 * On Linux we also need mtime for hardlinking sandbox, since updating the inode reference
 * counter preserves mtime, but updates ctime. isModified() call can be used to compare two
 * FileContentsProxys of hardlinked files.
 */
class FileContentsProxy(private val ctime: Long, private val mtime: Long, private val nodeId: Long) {
    override fun equals(other: Any?): Boolean {
        if (other === this) {
            return true
        }

        if (other !is FileContentsProxy) {
            return false
        }

        return ctime == other.ctime && mtime == other.mtime && nodeId == other.nodeId
    }

    /**
     * Can be used when hardlink reference counter changes should not be considered a file
     * modification. Is only comparing mtime and not ctime and is therefore not detecting changed
     * metadata like permission.
     */
    fun isModified(other: FileContentsProxy): Boolean {
        if (other === this) {
            return false
        }
        // true if nodeId are different or inode has a new mtime
        return nodeId != other.nodeId || mtime != other.mtime
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(ctime, mtime, nodeId)
    }

    fun addToFingerprint(fp: Fingerprint) {
        fp.addLong(ctime)
        fp.addLong(mtime)
        fp.addLong(nodeId)
    }

    override fun toString(): String {
        return prettyPrint()!!
    }

    fun prettyPrint(): String? {
        return String.format("ctime of %d and mtime of %d and nodeId of %d", ctime, mtime, nodeId)
    }

    companion object {
        @Throws(IOException::class)
        fun create(stat: FileStatus): FileContentsProxy {
            return FileContentsProxy( // Note: there are file systems that return mtime for getLastChangeTime() instead of ctime,
                // such as the JavaIoFileSystem.
                stat.getLastChangeTime(), stat.getLastModifiedTime(), stat.getNodeId()
            )
        }
    }
}
