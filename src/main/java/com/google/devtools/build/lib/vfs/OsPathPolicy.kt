// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.vfs.UnixOsPathPolicy
import com.google.devtools.build.lib.vfs.WindowsOsPathPolicy

/**
 * An interface class representing the differences in path style between different OSs.
 * 
 * 
 * Eg. case sensitivity, '/' mounts vs. 'C:/', etc.
 */
interface OsPathPolicy {
    /** Returns required normalization level, passed to [.normalize].  */
    fun needsToNormalize(path: String?): Int

    /**
     * Returns the required normalization level if an already normalized string is concatenated with
     * another normalized path fragment.
     * 
     * 
     * This method may be faster than [.needsToNormalize].
     */
    fun needsToNormalizeSuffix(normalizedSuffix: String?): Int

    /**
     * Normalizes the passed string according to the passed normalization level.
     * 
     * @param normalizationLevel The normalizationLevel from [.needsToNormalize]
     */
    fun normalize(path: String?, normalizationLevel: Int): String?

    /**
     * Returns the length of the mount, eg. 1 for unix '/', 3 for Windows 'C:/'.
     * 
     * 
     * If the path is relative, 0 is returned
     */
    fun getDriveStrLength(path: String?): Int

    /** Returns whether the unnormalized character c is a separator.  */
    fun isSeparator(c: Char): Boolean

    /**
     * Returns an additional character besides '/' for which [.isSeparator] is true. 0 means
     * there is no such additional character.
     */
    fun additionalSeparator(): Char

    /**
     * Modifies the given string to be suitable for execution on the OS represented by this policy.
     */
    fun postProcessPathStringForExecution(callablePathString: String?): String?

    /** Utilities for implementations of [OsPathPolicy].  */
    object Utils {
        /**
         * Normalizes any '.' and '..' in-place in the segment array by shifting other segments to the
         * front. Returns the remaining number of items.
         */
        fun removeRelativePaths(segments: Array<String>, starti: Int, isAbsolute: Boolean): Int {
            var segmentCount = 0
            var shift = starti
            val n = segments.size
            for (i in starti..<n) {
                val segment = segments[i]
                when (segment) {
                    "." -> ++shift
                    ".." -> {
                        if (segmentCount > 0 && segments[segmentCount - 1] != "..") {
                            // Remove the last segment, if there is one and it is not "..". This
                            // means that the resulting path can still contain ".."
                            // segments at the beginning.
                            segmentCount--
                            shift += 2
                            break
                        } else if (isAbsolute) {
                            // If this is absolute, then just pop it the ".." off and remain at root
                            ++shift
                            break
                        }
                        ++segmentCount
                        if (shift > 0) {
                            segments[i - shift] = segments[i]
                        }
                    }

                    else -> {
                        ++segmentCount
                        if (shift > 0) {
                            segments[i - shift] = segments[i]
                        }
                    }
                }
            }
            return segmentCount
        }
    }

    companion object {
        fun of(os: com.google.devtools.build.lib.util.OS?): OsPathPolicy {
            return if (os == com.google.devtools.build.lib.util.OS.WINDOWS) WindowsOsPathPolicy.Companion.INSTANCE else UnixOsPathPolicy.Companion.INSTANCE
        }

        fun getFilePathOs(os: com.google.devtools.build.lib.util.OS?): OsPathPolicy {
            if (os != com.google.devtools.build.lib.util.OS.WINDOWS) {
                // We *should* use a case-insensitive policy for OS.DARWIN, but we currently don't handle
                // this.
                return UnixOsPathPolicy.Companion.INSTANCE
            }
            return if (os == com.google.devtools.build.lib.util.OS.Companion.getCurrent())
                WindowsOsPathPolicy.Companion.INSTANCE
            else
                WindowsOsPathPolicy.Companion.CROSS_PLATFORM_INSTANCE
        }

        const val NORMALIZED: Int = 0 // Path is normalized
        const val NEEDS_NORMALIZE: Int = 1 // Path requires normalization

        /** The policy for the OS of the machine running the Bazel server's JVM.  */
        val filePathOs: OsPathPolicy = getFilePathOs(com.google.devtools.build.lib.util.OS.Companion.getCurrent())
    }
}
