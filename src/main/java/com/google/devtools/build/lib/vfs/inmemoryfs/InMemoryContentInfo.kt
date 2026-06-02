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

import com.google.common.base.Preconditions
import com.google.devtools.build.lib.clock.Clock
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
import javax.annotation.concurrent.GuardedBy

/**
 * This interface defines the function directly supported by the "files" stored in a
 * InMemoryFileSystem. This corresponds to a file or inode in UNIX: it doesn't have a path (it could
 * have many paths due to hard links, or none if it's unlinked, i.e. garbage).
 * 
 * 
 * This class is thread-safe: instances may be accessed and modified from concurrent threads.
 * Subclasses must preserve this property.
 */
@ThreadSafe
abstract class InMemoryContentInfo protected constructor(clock: Clock?) : FileStatus, InodeOrErrno {
    protected val clock: Clock

    /**
     * Stores the time when the file was last modified. This is atomically updated whenever the file
     * changes, so all accesses must be synchronized.
     */
    @GuardedBy("this")
    private var lastModifiedTime: Long = 0

    /**
     * Returns the time when the entity denoted by the current object was last
     * changed.
     */
    /**
     * Stores the time when the file information was changed. This is atomically updated whenever the
     * file changes, so all accesses must be synchronized.
     */
    @get:kotlin.jvm.Synchronized
    @GuardedBy("this")
    var lastChangeTime: Long = 0
        private set

    /** Stores the file's permission bits.  */
    @get:kotlin.jvm.Synchronized
    @GuardedBy("this")
    var permissions: Int = 420
        private set

    init {
        this.clock = Preconditions.checkNotNull<Clock>(clock, "clock")
        // When we create the file, it is modified.
        markModificationTime()
    }

    /**
     * Returns true if the current object is a directory.
     */
    abstract val isDirectory: Boolean

    /**
     * Returns true if the current object is a symbolic link.
     */
    abstract val isSymbolicLink: Boolean

    /**
     * Returns true if the current object is a regular or special file.
     */
    abstract val isFile: Boolean

    /**
     * Returns true if the current object is a special file.
     */
    abstract val isSpecialFile: Boolean

    /**
     * Returns the size of the entity denoted by the current object. For files,
     * this is the length in bytes, for directories the number of children. The
     * size of links is unspecified.
     */
    abstract val size: Long

    /**
     * Returns the time when the entity denoted by the current object was last
     * modified.
     */
    @kotlin.jvm.Synchronized
    override fun getLastModifiedTime(): Long {
        return lastModifiedTime
    }

    val nodeId: Long
        /**
         * Returns the file node id for the given instance, emulated by the
         * identity hash code.
         */
        get() = System.identityHashCode(this).toLong()

    override fun inodeOrThrow(path: PathFragment?): InMemoryContentInfo {
        return this
    }

    /**
     * Sets the time that denotes when the entity denoted by this object was last
     * modified.
     */
    @kotlin.jvm.Synchronized
    fun setLastModifiedTime(newTime: Long) {
        lastModifiedTime = newTime
        markChangeTime()
    }

    /** Sets the last modification and change times to the current time.  */
    @kotlin.jvm.Synchronized
    fun markModificationTime() {
        lastModifiedTime = clock.currentTimeMillis()
        lastChangeTime = lastModifiedTime
    }

    /** Sets the last change time to the current time.  */
    @kotlin.jvm.Synchronized
    private fun markChangeTime() {
        lastChangeTime = clock.currentTimeMillis()
    }

    var isReadable: Boolean
        /** Returns whether the current file is readable.  */
        get() = checkPermissions(256)
        /** Sets whether the current file is readable.  */
        set(readable) {
            updatePermissions(256, readable)
        }

    var isWritable: Boolean
        /** Returns whether the current file is writable.  */
        get() = checkPermissions(128)
        /** Sets whether the current file is writable.  */
        set(writable) {
            updatePermissions(128, writable)
        }

    var isExecutable: Boolean
        /** Returns whether the current file is executable.  */
        get() = checkPermissions(64)
        /** Sets whether the current file is executable.  */
        set(executable) {
            updatePermissions(73, executable)
        }

    /** Sets the permissions on the current file.  */
    @kotlin.jvm.Synchronized
    fun chmod(permissions: Int) {
        this.permissions = permissions
        markChangeTime()
    }

    @kotlin.jvm.Synchronized
    private fun checkPermissions(mask: Int): Boolean {
        return (permissions and mask) != 0
    }

    @kotlin.jvm.Synchronized
    private fun updatePermissions(mask: Int, set: Boolean) {
        chmod(if (set) (permissions or mask) else (permissions and mask.inv()))
    }

    open fun asDirectory(): InMemoryDirectoryInfo? {
        throw IllegalStateException("Not a directory: " + this)
    }
}
