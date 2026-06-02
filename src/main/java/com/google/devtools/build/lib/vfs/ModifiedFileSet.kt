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

import com.google.devtools.build.lib.events.Event

/**
 * An immutable set of modified source files. The scope of these files is context-dependent; in some
 * uses this may mean information about all files in the client, while in other uses this may mean
 * information about some specific subset of files. [.EVERYTHING_MODIFIED] can be used to
 * indicate that all files of interest have been modified.
 */
open class ModifiedFileSet protected constructor(modified: com.google.common.collect.ImmutableSet<PathFragment?>?) {
    /**
     * Allows issuing instructions to clean up the client. This could be in order to revert the client
     * back to its baseline (hypothetical use case) or a real use case at the time of writing this
     * comment which is to clean up Skycache violations of files modified outside of the project's
     * frontier compared to the baseline.
     */
    fun getInstructionsMessage(modified: MutableSet<String?>?): String? {
        return null
    }

    fun getInstructionsPrelude(modified: MutableSet<String?>?): String? {
        return null
    }

    val messages: MutableList<Event>
        /**
         * Returns events related to this modified file set, for example, files whose state violates some
         * condition or require a warning.
         */
        get() = com.google.common.collect.ImmutableList.of<Event?>()

    private val modified: com.google.common.collect.ImmutableSet<PathFragment?>?

    /**
     * Whether all files of interest should be treated as potentially modified.
     */
    fun treatEverythingAsModified(): Boolean {
        return modified == null
    }

    /**
     * Returns whether the diff indicates the whole tree has been deleted.
     * 
     * 
     * This precludes any optimizations like skipping invalidation when we do not check modified
     * outputs.
     */
    open fun treatEverythingAsDeleted(): Boolean {
        return false
    }

    /**
     * The set of files of interest that were modified.
     * 
     * @throws IllegalStateException if [.treatEverythingAsModified] returns true.
     */
    fun modifiedSourceFiles(): com.google.common.collect.ImmutableSet<PathFragment?>? {
        check(!treatEverythingAsModified())
        return modified
    }

    override fun equals(o: Any?): Boolean {
        if (o === this) {
            return true
        }
        if (o !is ModifiedFileSet) {
            return false
        }
        return treatEverythingAsModified() == o.treatEverythingAsModified() && treatEverythingAsDeleted() == o.treatEverythingAsDeleted() && modified == o.modified
    }

    override fun hashCode(): Int {
        return 31 * java.util.Objects.hashCode(modified) + java.lang.Boolean.hashCode(treatEverythingAsDeleted())
    }

    override fun toString(): String {
        if (this == EVERYTHING_DELETED) {
            return "EVERYTHING_DELETED"
        } else if (this == EVERYTHING_MODIFIED) {
            return "EVERYTHING_MODIFIED"
        } else if (this == NOTHING_MODIFIED) {
            return "NOTHING_MODIFIED"
        } else {
            return modified.toString()
        }
    }

    init {
        this.modified = modified
    }

    /** The builder for [ModifiedFileSet].  */
    class Builder {
        private val setBuilder: com.google.common.collect.ImmutableSet.Builder<PathFragment?> =
            com.google.common.collect.ImmutableSet.builder<PathFragment?>()

        fun build(): ModifiedFileSet {
            val modified: com.google.common.collect.ImmutableSet<PathFragment?> = setBuilder.build()
            return if (modified.isEmpty()) NOTHING_MODIFIED else ModifiedFileSet(modified)
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun modify(pathFragment: PathFragment?): Builder {
            setBuilder.add(pathFragment)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun modifyAll(pathFragments: Iterable<PathFragment?>): Builder {
            setBuilder.addAll(pathFragments)
            return this
        }
    }

    companion object {
        // When everything is modified that naturally includes all directories.
        @kotlin.jvm.JvmField
        val EVERYTHING_MODIFIED: ModifiedFileSet = ModifiedFileSet(null)

        /**
         * Special case of [.EVERYTHING_MODIFIED], which indicates that the entire tree has been
         * deleted.
         */
        @kotlin.jvm.JvmField
        val EVERYTHING_DELETED: ModifiedFileSet = object : ModifiedFileSet(null) {
            override fun treatEverythingAsDeleted(): Boolean {
                return true
            }
        }

        @kotlin.jvm.JvmField
        val NOTHING_MODIFIED: ModifiedFileSet =
            ModifiedFileSet(com.google.common.collect.ImmutableSet.of<PathFragment?>())

        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return com.google.devtools.build.lib.vfs.ModifiedFileSet.Builder()
        }
    }
}
