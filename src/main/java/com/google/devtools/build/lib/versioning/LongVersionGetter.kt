// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.versioning

import com.google.devtools.build.lib.vfs.Path
import java.io.IOException

/** Strategy for retrieving the version number for paths.  */
interface LongVersionGetter {
    /**
     * Returns version number when the provided file/symlink was last modified (or added).
     * 
     * 
     * Special value of [.CURRENT_VERSION] is used to indicate a file/symlink modified in
     * current client snapshot.
     */
    @Throws(IOException::class)
    fun getFilePathOrSymlinkVersion(path: Path?): Long

    /**
     * Returns version number when the listing of given directory has last changed (or when the
     * directory was created if there were no changes since then).
     * 
     * 
     * Special value of [.CURRENT_VERSION] is used to indicate the listing has changed in
     * current client snapshot.
     */
    @Throws(IOException::class)
    fun getDirectoryListingVersion(path: Path?): Long

    /**
     * Returns a version number for a currently nonexistent item.
     * 
     * 
     * This can be the version at which it was most recently deleted or one of the special cases
     * below.
     * 
     * 
     *  * **Deleted in Current Snapshot**: returns [.CURRENT_VERSION]
     *  * **External, unversioned, paths**: returns [.CURRENT_VERSION]
     *  * **Never existed in the first place**: returns [.MINIMAL]
     *  * **Parent directory doesn't exist**: returns [.MINIMAL]
     * 
     */
    @Throws(IOException::class)
    fun getNonexistentPathVersion(path: Path?): Long

    companion object {
        /** Indicates the item was affected in currently evaluated version.  */
        val CURRENT_VERSION: Long = Long.Companion.MAX_VALUE

        /**
         * Version for a file that has never changed.
         * 
         * 
         * We use -1 because valid versions are positive longs.
         */
        val MINIMAL: Long = -1
    }
}
