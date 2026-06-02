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

import java.io.IOException

/**
 * File status: mode, mtime, size, etc.
 * 
 * 
 * The result of calling any `FileStatus` instance method is not
 * guaranteed to result in I/O to the file system at the moment of the call.
 * The I/O providing the result (and hence the throwing of an I/O exception,
 * where applicable) may occur at any moment between the call to [ ][FileSystem.stat] and the call of the `FileStatus` instance method.
 * 
 * 
 * Callers therefore cannot assume that all the values are populated
 * atomically, or that the results of any two `FileStatus` methods
 * correspond to state of the file system at a single moment in time.  Nor may
 * they assume that repeated successful calls to any method of the same
 * instance will return the same value.
 * 
 * 
 * (This permits conforming implementations to use an atomic `stat(2)`
 * call on file systems where it is available, and individual accessor methods
 * on those where it is not.  Caching is possible but not required.)
 */
interface FileStatus {
    /**
     * Returns true iff this file is a regular file or `isSpecial()`.
     */
    @kotlin.jvm.JvmField
    val isFile: Boolean

    /**
     * Returns true iff this file is a directory.
     */
    @kotlin.jvm.JvmField
    val isDirectory: Boolean

    /**
     * Returns true iff this file is a symbolic link.
     */
    @kotlin.jvm.JvmField
    val isSymbolicLink: Boolean

    /**
     * Returns true iff this file is a special file (e.g. socket, fifo or device). [.getSize]
     * can't be trusted for such files.
     */
    @kotlin.jvm.JvmField
    val isSpecialFile: Boolean

    @kotlin.jvm.JvmField
    @get:Throws(IOException::class)
    val size: Long

    @kotlin.jvm.JvmField
    @get:Throws(IOException::class)
    val lastModifiedTime: Long

    @kotlin.jvm.JvmField
    @get:Throws(IOException::class)
    val lastChangeTime: Long

    @kotlin.jvm.JvmField
    @get:Throws(IOException::class)
    val nodeId: Long

    val permissions: Int
        /**
         * Returns the file's permissions in POSIX format (e.g. 0755) if possible without performing
         * additional IO, otherwise (or if unsupported by the file system) returns -1.
         * 
         * 
         * If accurate group and other permissions aren't available, the returned value should attempt
         * to mimic a umask of 022 (i.e. read and execute permissions extend to group and other, write
         * does not).
         */
        get() = -1
}
