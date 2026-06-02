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
import kotlin.collections.HashMap
import kotlin.collections.MutableCollection
import kotlin.collections.MutableMap

/**
 * Represents a directory stored in an [InMemoryFileSystem].
 * 
 * 
 * Not thread-safe. Access should be synchronized from the referencing [ ].
 */
internal class InMemoryDirectoryInfo(clock: Clock?) : InMemoryContentInfo(clock) {
    private val directoryContent: MutableMap<String?, InMemoryContentInfo?> = HashMap<String?, InMemoryContentInfo?>()

    init {
        setExecutable(true)
    }

    /**
     * Adds a new child to this directory under the given name. Callers must ensure that no entry of
     * that name exists already.
     */
    fun addChild(name: String?, inode: InMemoryContentInfo?) {
        Preconditions.checkNotNull<String?>(name)
        Preconditions.checkNotNull<InMemoryContentInfo?>(inode)
        require(directoryContent.put(name, inode) == null) { "File already exists: " + name }
        markModificationTime()
    }

    /**
     * Does a directory lookup, and returns the inode for the specified name, or null if the child is
     * not found.
     */
    fun getChild(name: String?): InMemoryContentInfo? {
        return directoryContent.get(name)
    }

    /** Removes a previously existing child from the directory specified by this object.  */
    fun removeChild(name: String?) {
        requireNotNull(directoryContent.remove(name)) { name + " is not a member of this directory" }
        markModificationTime()
    }

    val allChildren: MutableCollection<String?>
        /** Returns the contents of this directory.  */
        get() = directoryContent.keys

    override fun isDirectory(): Boolean {
        return true
    }

    override fun isSymbolicLink(): Boolean {
        return false
    }

    override fun isFile(): Boolean {
        return false
    }

    override fun isSpecialFile(): Boolean {
        return false
    }

    /**
     * In the InMemory hierarchy, the getSize on a directory always returns the number of children in
     * the directory.
     */
    override fun getSize(): Long {
        return directoryContent.size.toLong()
    }

    override fun asDirectory(): InMemoryDirectoryInfo {
        return this
    }
}
