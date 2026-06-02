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

import com.google.devtools.build.lib.actions.FileValue

/**
 * A value that represents the dirents (name and type of child entries) in a given directory under a
 * given package path root, fully accounting for symlinks in the directory's path. Anything in
 * Skyframe that cares about the contents of a directory should have a dependency on the
 * corresponding [DirectoryListingValue].
 * 
 * 
 * Note that dirents that are themselves symlinks are **not** resolved. Consumers of such a
 * dirent are responsible for resolving the symlink entry via an appropriate [FileValue].
 * This is a little onerous, but correct: we do not need to reread the directory when a symlink
 * inside it changes (or, more generally, when the *contents* of a dirent changes), therefore the
 * [DirectoryListingValue] value should not be invalidated in that case.
 */
@Immutable
@ThreadSafe
abstract class DirectoryListingValue : SkyValue {
    val dirents: Dirents?
        /**
         * Returns the directory entries for this directory, in a stable order.
         * 
         * 
         * Symlinks are not expanded.
         */
        get() = this.directoryListingStateValue.getDirents()

    abstract val directoryListingStateValue: DirectoryListingStateValue?

    /** Normal [DirectoryListingValue].  */
    @ThreadSafe
    class RegularDirectoryListingValue(directoryListingStateValue: DirectoryListingStateValue) :
        DirectoryListingValue() {
        private val directoryListingStateValue: DirectoryListingStateValue

        init {
            this.directoryListingStateValue = directoryListingStateValue
        }

        override fun getDirectoryListingStateValue(): DirectoryListingStateValue {
            return directoryListingStateValue
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj !is RegularDirectoryListingValue) {
                return false
            }
            return directoryListingStateValue == obj.directoryListingStateValue
        }

        override fun hashCode(): Int {
            return directoryListingStateValue.hashCode()
        }
    }

    /** A [DirectoryListingValue] with a different root.  */
    @ThreadSafe
    class DifferentRealPathDirectoryListingValue(
        realDirRootedPath: RootedPath,
        directoryListingStateValue: DirectoryListingStateValue
    ) : DirectoryListingValue() {
        private val realDirRootedPath: RootedPath
        private val directoryListingStateValue: DirectoryListingStateValue

        init {
            this.realDirRootedPath = realDirRootedPath
            this.directoryListingStateValue = directoryListingStateValue
        }

        fun getRealDirRootedPath(): RootedPath {
            return realDirRootedPath
        }

        override fun getDirectoryListingStateValue(): DirectoryListingStateValue {
            return directoryListingStateValue
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj !is DifferentRealPathDirectoryListingValue) {
                return false
            }
            return realDirRootedPath == obj.realDirRootedPath
                    && directoryListingStateValue == obj.directoryListingStateValue
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(realDirRootedPath, directoryListingStateValue)
        }
    }

    companion object {
        /**
         * Returns a [Key] for getting the directory entries of the given directory. The given path
         * is assumed to be an existing directory (e.g. via [FileValue.isDirectory] or from a
         * directory listing on its parent directory).
         */
        @ThreadSafe
        fun key(directoryUnderRoot: RootedPath?): DirectoryListingKey? {
            return DirectoryListingKey.Companion.create(directoryUnderRoot)
        }

        fun value(
            dirRootedPath: RootedPath?, dirFileValue: FileValue,
            realDirectoryListingStateValue: DirectoryListingStateValue
        ): DirectoryListingValue {
            val realRootedPath: RootedPath = dirFileValue.realRootedPath(dirRootedPath)
            return if (realRootedPath == dirRootedPath)
                RegularDirectoryListingValue(realDirectoryListingStateValue)
            else
                DifferentRealPathDirectoryListingValue(
                    realRootedPath, realDirectoryListingStateValue
                )
        }
    }
}
