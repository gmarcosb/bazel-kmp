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
import com.google.devtools.build.lib.windows.WindowsPathOperations
import java.io.IOException

@com.google.common.annotations.VisibleForTesting
internal class WindowsOsPathPolicy @com.google.common.annotations.VisibleForTesting constructor(private val shortPathResolver: ShortPathResolver) :
    OsPathPolicy {
    internal interface ShortPathResolver {
        fun resolveShortPath(path: String?): String?
    }

    internal class DefaultShortPathResolver : ShortPathResolver {
        override fun resolveShortPath(path: String?): String? {
            if (com.google.devtools.build.lib.util.OS.Companion.getCurrent() != com.google.devtools.build.lib.util.OS.WINDOWS) {
                // Short path resolution only makes sense on a Windows host.
                return path
            }
            try {
                return WindowsPathOperations.getLongPath(path)
            } catch (e: IOException) {
                return path
            }
        }
    }

    internal class CrossPlatformShortPathResolver : ShortPathResolver {
        override fun resolveShortPath(path: String?): String? {
            // Short paths can only be resolved on a Windows host.
            // Skipping short path resolution when running on a non-Windows host can
            // result in paths considered different that are actually the same.
            // TODO: Consider failing when a short path is detected on a non-Windows
            //  host. Since short path segments can arise from most operations on
            //  PathFragment, this would however require exception handling in many
            //  places.
            return path
        }
    }

    override fun needsToNormalize(path: String): Int {
        val n: Int = path.length()
        var normalizationLevel: Int = OsPathPolicy.Companion.NORMALIZED
        var dotCount = 0
        var prevChar = 0.toChar()
        var segmentBeginIndex = 0 // The start index of the current path index
        var segmentHasShortPathChar = false // Triggers more expensive short path regex test
        for (i in 0..<n) {
            val c: Char = path.charAt(i)
            if (isSeparator(c)) {
                if (c == '\\') {
                    normalizationLevel = java.lang.Math.max(normalizationLevel, OsPathPolicy.Companion.NEEDS_NORMALIZE)
                }
                // No need to check for '\\' here because that already causes normalization
                if (prevChar == '/') {
                    normalizationLevel = java.lang.Math.max(normalizationLevel, OsPathPolicy.Companion.NEEDS_NORMALIZE)
                }
                if (dotCount == 1 || dotCount == 2) {
                    normalizationLevel = java.lang.Math.max(normalizationLevel, OsPathPolicy.Companion.NEEDS_NORMALIZE)
                }
                if (segmentHasShortPathChar) {
                    if (WindowsPathOperations.isShortPath(path.substring(segmentBeginIndex, i))) {
                        normalizationLevel = java.lang.Math.max(normalizationLevel, NEEDS_SHORT_PATH_NORMALIZATION)
                    }
                }
                segmentBeginIndex = i + 1
                segmentHasShortPathChar = false
            } else if (c == '~') {
                // This path segment might be a Windows short path segment
                segmentHasShortPathChar = true
            }
            dotCount = if (c == '.') dotCount + 1 else 0
            prevChar = c
        }
        if (segmentHasShortPathChar) {
            if (WindowsPathOperations.isShortPath(path.substring(segmentBeginIndex))) {
                normalizationLevel = java.lang.Math.max(normalizationLevel, NEEDS_SHORT_PATH_NORMALIZATION)
            }
        }
        if ((n > 1 && isSeparator(prevChar)) || dotCount == 1 || dotCount == 2) {
            normalizationLevel = java.lang.Math.max(normalizationLevel, OsPathPolicy.Companion.NEEDS_NORMALIZE)
        }
        return normalizationLevel
    }

    override fun needsToNormalizeSuffix(normalizedSuffix: String): Int {
        // On Windows, all bets are off because of short paths, so we have to check the entire string
        return needsToNormalize(normalizedSuffix)
    }

    override fun normalize(path: String, normalizationLevel: Int): String? {
        var path = path
        if (normalizationLevel == OsPathPolicy.Companion.NORMALIZED) {
            return path
        }
        if (normalizationLevel == NEEDS_SHORT_PATH_NORMALIZATION) {
            val resolvedPath = shortPathResolver.resolveShortPath(path)
            if (resolvedPath != null) {
                path = resolvedPath
            }
        }
        val segments: Array<String?> = com.google.common.collect.Iterables.toArray<String?>(
            WINDOWS_PATH_SPLITTER.splitToList(path),
            String::class.java
        )
        val driveStrLength = getDriveStrLength(path)
        val isAbsolute = driveStrLength > 0
        val segmentSkipCount = if (isAbsolute && driveStrLength > 1) 1 else 0

        val sb: java.lang.StringBuilder = java.lang.StringBuilder(path.length())
        if (isAbsolute) {
            val c: Char = path.charAt(0)
            if (isSeparator(c)) {
                sb.append('/')
            } else {
                sb.append(java.lang.Character.toUpperCase(c))
                sb.append(":/")
            }
        }
        val segmentCount: Int = com.google.devtools.build.lib.vfs.OsPathPolicy.Utils.removeRelativePaths(
            segments,
            segmentSkipCount,
            isAbsolute
        )
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
        val n: Int = path.length()
        if (n == 0) {
            return 0
        }
        val c0: Char = path.charAt(0)
        if (isSeparator(c0)) {
            return 1
        }
        if (n < 3) {
            return 0
        }
        val c1: Char = path.charAt(1)
        val c2: Char = path.charAt(2)
        if (isDriveLetter(c0) && c1 == ':' && isSeparator(c2)) {
            return 3
        }
        return 0
    }

    override fun isSeparator(c: Char): Boolean {
        return c == '/' || c == '\\'
    }

    override fun additionalSeparator(): Char {
        return '\\'
    }

    override fun postProcessPathStringForExecution(callablePathString: String): String? {
        // On Windows, .bat scripts (and possibly others) cannot be executed with forward slashes in
        // the path. Since backslashes are the standard path separator on Windows, we replace all
        // forward slashes with backslashes instead of trying to enumerate these special cases.
        return callablePathString.replace('/', '\\')
    }

    companion object {
        val INSTANCE: WindowsOsPathPolicy = WindowsOsPathPolicy(DefaultShortPathResolver())

        val CROSS_PLATFORM_INSTANCE: WindowsOsPathPolicy = WindowsOsPathPolicy(CrossPlatformShortPathResolver())

        @kotlin.jvm.JvmField
        val NEEDS_SHORT_PATH_NORMALIZATION: Int = OsPathPolicy.Companion.NEEDS_NORMALIZE + 1

        private val WINDOWS_PATH_SPLITTER: com.google.common.base.Splitter =
            com.google.common.base.Splitter.onPattern("[\\\\/]+").omitEmptyStrings()

        private fun isDriveLetter(c: Char): Boolean {
            return ((c >= 'a') && (c <= 'z')) || ((c >= 'A') && (c <= 'Z'))
        }
    }
}
