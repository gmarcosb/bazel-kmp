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

import com.google.devtools.build.lib.vfs.OsPathPolicy

@com.google.common.annotations.VisibleForTesting
internal class UnixOsPathPolicy : OsPathPolicy {
    override fun needsToNormalize(path: String): Int {
        val n: Int = path.length()
        var dotCount = 0
        var prevChar = 0.toChar()
        for (i in 0..<n) {
            val c: Char = path.charAt(i)
            if (c == '\\') {
                return OsPathPolicy.Companion.NEEDS_NORMALIZE
            }
            if (c == '/') {
                if (prevChar == '/') {
                    return OsPathPolicy.Companion.NEEDS_NORMALIZE
                }
                if (dotCount == 1 || dotCount == 2) {
                    return OsPathPolicy.Companion.NEEDS_NORMALIZE
                }
            }
            dotCount = if (c == '.') dotCount + 1 else 0
            prevChar = c
        }
        if (prevChar == '/' || dotCount == 1 || dotCount == 2) {
            return OsPathPolicy.Companion.NEEDS_NORMALIZE
        }
        return OsPathPolicy.Companion.NORMALIZED
    }

    override fun needsToNormalizeSuffix(normalizedSuffix: String): Int {
        // We know that the string is normalized
        // In this case only suffixes starting with ".." may cause
        // normalization once concatenated with other strings
        return if (normalizedSuffix.startsWith("..")) OsPathPolicy.Companion.NEEDS_NORMALIZE else OsPathPolicy.Companion.NORMALIZED
    }

    override fun normalize(path: String, normalizationLevel: Int): String? {
        if (normalizationLevel == OsPathPolicy.Companion.NORMALIZED) {
            return path
        }
        if (path.isEmpty()) {
            return path
        }
        val isAbsolute = path.charAt(0) == '/'
        val sb: java.lang.StringBuilder = java.lang.StringBuilder(path.length())
        if (isAbsolute) {
            sb.append('/')
        }
        val segments: Array<String?> =
            com.google.common.collect.Iterables.toArray<String?>(PATH_SPLITTER.splitToList(path), String::class.java)
        val segmentCount: Int =
            com.google.devtools.build.lib.vfs.OsPathPolicy.Utils.removeRelativePaths(segments, 0, isAbsolute)
        for (i in 0..<segmentCount) {
            sb.append(segments[i])
            sb.append('/')
        }
        if (segmentCount > 0) {
            sb.deleteCharAt(sb.length() - 1)
        }
        return sb.toString()
    }

    override fun getDriveStrLength(path: String): Int {
        if (path.length() == 0) {
            return 0
        }
        return if (path.charAt(0) == '/') 1 else 0
    }

    override fun isSeparator(c: Char): Boolean {
        return c == '/'
    }

    override fun additionalSeparator(): Char {
        return 0.toChar()
    }

    override fun postProcessPathStringForExecution(callablePathString: String?): String? {
        return callablePathString
    }

    companion object {
        val INSTANCE: UnixOsPathPolicy = UnixOsPathPolicy()
        private val PATH_SPLITTER: com.google.common.base.Splitter =
            com.google.common.base.Splitter.onPattern("/+").omitEmptyStrings()
    }
}
