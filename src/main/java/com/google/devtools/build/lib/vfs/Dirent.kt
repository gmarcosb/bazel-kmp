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

/** Directory entry representation returned by [Path.readdir].  */
class Dirent(name: String?, type: Type?) : Comparable<Dirent?> {
    /** Type of the directory entry  */
    enum class Type {
        // A regular file.
        FILE,

        // A directory.
        DIRECTORY,

        // A symlink.
        SYMLINK,

        // None of the above.
        // For example, a special file, or a path that could not be resolved while following symlinks.
        UNKNOWN
    }

    @kotlin.jvm.JvmField
    val name: String
    @kotlin.jvm.JvmField
    val type: Type

    /** Creates a new dirent with the given name and type, both of which must be non-null.  */
    init {
        this.name = com.google.common.base.Preconditions.checkNotNull<String>(name)
        this.type = com.google.common.base.Preconditions.checkNotNull<Type>(type)
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(name, type)
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Dirent) {
            return false
        }
        if (this === other) {
            return true
        }
        return name == other.name && type == other.type
    }

    override fun toString(): String {
        return name + "[" + type.toString().toLowerCase() + "]"
    }

    override fun compareTo(other: Dirent): Int {
        return this.name.compareTo(other.name)
    }
}
