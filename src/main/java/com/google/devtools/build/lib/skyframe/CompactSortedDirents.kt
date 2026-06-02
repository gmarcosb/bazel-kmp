// Copyright 2025 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.skyframe.Dirents
import java.util.AbstractCollection
import java.util.BitSet

/** A space-efficient, sorted, immutable dirent structure.  */
internal class CompactSortedDirents private constructor(private val names: Array<String?>, packedTypes: BitSet) :
    AbstractCollection<com.google.devtools.build.lib.vfs.Dirent?>(), Dirents {
    private val packedTypes: BitSet

    init {
        this.packedTypes = packedTypes
    }

    override fun equals(obj: Any?): Boolean {
        if (obj !is CompactSortedDirents) {
            return false
        }
        if (this === obj) {
            return true
        }
        return names.contentEquals(obj.names) && packedTypes == obj.packedTypes
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(names.contentHashCode(), packedTypes)
    }

    override fun maybeGetDirent(baseName: String?): com.google.devtools.build.lib.vfs.Dirent? {
        val pos: Int = java.util.Arrays.binarySearch(names, baseName)
        return if (pos < 0) null else direntAt(pos)
    }

    override fun iterator(): MutableIterator<com.google.devtools.build.lib.vfs.Dirent?> {
        return object : MutableIterator<com.google.devtools.build.lib.vfs.Dirent?> {
            private var i = 0

            override fun hasNext(): Boolean {
                return i < size
            }

            override fun next(): com.google.devtools.build.lib.vfs.Dirent {
                return direntAt(i++)
            }

            override fun remove() {
                throw java.lang.UnsupportedOperationException()
            }
        }
    }

    override fun size(): Int {
        return names.size
    }

    /** Returns the type of the ith dirent.  */
    private fun unpackType(i: Int): com.google.devtools.build.lib.vfs.Dirent.Type {
        val start = i * 2
        val upper: Boolean = packedTypes.get(start)
        val lower: Boolean = packedTypes.get(start + 1)
        if (!upper && !lower) {
            return com.google.devtools.build.lib.vfs.Dirent.Type.FILE
        } else if (!upper && lower) {
            return com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY
        } else if (upper && !lower) {
            return com.google.devtools.build.lib.vfs.Dirent.Type.SYMLINK
        } else {
            return com.google.devtools.build.lib.vfs.Dirent.Type.UNKNOWN
        }
    }

    private fun direntAt(i: Int): com.google.devtools.build.lib.vfs.Dirent {
        com.google.common.base.Preconditions.checkState(i >= 0 && i < size, "i: %s, size: %s", i, size)
        return com.google.devtools.build.lib.vfs.Dirent(names[i], unpackType(i))
    }

    companion object {
        fun create(dirents: MutableCollection<com.google.devtools.build.lib.vfs.Dirent?>): CompactSortedDirents {
            val direntArray: Array<com.google.devtools.build.lib.vfs.Dirent> =
                dirents.toArray<com.google.devtools.build.lib.vfs.Dirent?>(java.util.function.IntFunction { _Dummy_.__Array__() })
            val indices = arrayOfNulls<Int>(dirents.size)
            for (i in dirents.indices) {
                indices[i] = i
            }
            java.util.Arrays.sort<Int?>(
                indices,
                java.util.Comparator.comparing<Int?, com.google.devtools.build.lib.vfs.Dirent?>(java.util.function.Function { o: Int? -> direntArray[o!!] })
            )
            val names = arrayOfNulls<String>(dirents.size)
            val packedTypes: BitSet = BitSet(dirents.size * 2)
            for (i in dirents.indices) {
                val dirent: com.google.devtools.build.lib.vfs.Dirent = direntArray[indices[i]!!]
                names[i] = dirent.getName()
                packType(packedTypes, dirent.getType(), i)
            }
            return CompactSortedDirents(names, packedTypes)
        }

        /** Sets the type of the ith dirent.  */
        private fun packType(bitSet: BitSet, type: com.google.devtools.build.lib.vfs.Dirent.Type, i: Int) {
            val start = i * 2
            when (type) {
                com.google.devtools.build.lib.vfs.Dirent.Type.FILE -> pack(bitSet, start, false, false)
                com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY -> pack(bitSet, start, false, true)
                com.google.devtools.build.lib.vfs.Dirent.Type.SYMLINK -> pack(bitSet, start, true, false)
                com.google.devtools.build.lib.vfs.Dirent.Type.UNKNOWN -> pack(bitSet, start, true, true)
            }
        }

        private fun pack(bitSet: BitSet, start: Int, upper: Boolean, lower: Boolean) {
            bitSet.set(start, upper)
            bitSet.set(start + 1, lower)
        }
    }
}
