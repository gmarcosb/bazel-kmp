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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe

/**
 * Encapsulates the filesystem operations needed to get the directory entries of a directory.
 * 
 * 
 * This class is an implementation detail of [DirectoryListingValue].
 */
@com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
class DirectoryListingStateValue private constructor(dirents: MutableCollection<com.google.devtools.build.lib.vfs.Dirent?>) :
    SkyValue {
    private val compactSortedDirents: CompactSortedDirents

    init {
        this.compactSortedDirents = CompactSortedDirents.Companion.create(dirents)
    }

    /** Key type for DirectoryListingStateValue.  */
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    @AutoCodec
    class Key private constructor(arg: RootedPath?) : AbstractSkyKey<RootedPath?>(arg) {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.DIRECTORY_LISTING_STATE
        }

        val skyKeyInterner: SkyKeyInterner<Key?>
            get() = com.google.devtools.build.lib.skyframe.DirectoryListingStateValue.Key.Companion.interner

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            private fun create(arg: RootedPath?): Key {
                return com.google.devtools.build.lib.skyframe.DirectoryListingStateValue.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.skyframe.DirectoryListingStateValue.Key(arg)
                )
            }

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Interner
            fun intern(key: Key?): Key {
                return com.google.devtools.build.lib.skyframe.DirectoryListingStateValue.Key.Companion.interner.intern(
                    key
                )
            }
        }
    }

    val dirents: Dirents
        /**
         * Returns the directory entries for this directory, in a stable order.
         * 
         * 
         * Symlinks are not expanded.
         */
        get() = compactSortedDirents

    override fun hashCode(): Int {
        return compactSortedDirents.hashCode()
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj !is DirectoryListingStateValue) {
            return false
        }
        return compactSortedDirents == obj.compactSortedDirents
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("dirents", com.google.common.collect.Iterables.toString(this.dirents))
            .toString()
    }

    companion object {
        @AutoCodec.Instantiator
        fun create(dirents: MutableCollection<com.google.devtools.build.lib.vfs.Dirent?>): DirectoryListingStateValue {
            return DirectoryListingStateValue(dirents)
        }

        @ThreadSafe
        fun key(rootedPath: RootedPath?): Key {
            return com.google.devtools.build.lib.skyframe.DirectoryListingStateValue.Key.Companion.create(rootedPath)
        }
    }
}
