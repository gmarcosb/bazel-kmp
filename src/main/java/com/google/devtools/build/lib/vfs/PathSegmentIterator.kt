// Copyright 2021 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.vfs.PathFragment
import java.util.Collections

/**
 * Lazily iterates over the segments of a path string.
 * 
 * 
 * Expects the path string to already be normalized.
 */
internal class PathSegmentIterator private constructor(private val normalizedPath: String, private var start: Int) :
    MutableIterator<String?> {
    override fun hasNext(): Boolean {
        return start < normalizedPath.length()
    }

    override fun next(): String {
        if (!hasNext()) {
            throw java.util.NoSuchElementException("No more segments: " + normalizedPath)
        }
        var end = start + 1
        while (end < normalizedPath.length()
            && normalizedPath.charAt(end) != PathFragment.Companion.SEPARATOR_CHAR
        ) {
            end++
        }
        val segment: String = normalizedPath.substring(start, end)
        start = end + 1
        return segment
    }

    companion object {
        @kotlin.jvm.JvmStatic
        fun create(normalizedPath: String, driveStrLength: Int): MutableIterator<String?>? {
            return if (normalizedPath.length() > driveStrLength)
                PathSegmentIterator(normalizedPath, driveStrLength)
            else
                Collections.emptyIterator<String?>()
        }
    }
}
