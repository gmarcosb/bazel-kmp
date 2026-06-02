// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel

import com.google.auto.value.AutoValue

/**
 * Represents a bazel version. The version format supported is {RELEASE[SUFFIX]}, where:
 * 
 * 
 *  * {RELEASE} is a sequence of decimal numbers separated by dots;
 *  * {SUFFIX} could be: `-pre.*`, or any other string (which compares equal to SUFFIX
 * being absent)
 * 
 */
@AutoValue
abstract class BazelVersion {
    /** Returns the "release" part of the version string as a list of integers.  */
    abstract val release: com.google.common.collect.ImmutableList<Int?>?

    /** Returns the "suffix" part of the version that starts after the integers  */
    abstract val suffix: String?

    /** Returns the original version string.  */
    abstract val original: String?

    val isPrerelease: Boolean
        /** Whether this is a prerelease  */
        get() = this.suffix.startsWith("-pre")

    /** Check if class version satisfies compatibility version  */
    fun satisfiesCompatibility(compatVersion: String): Boolean {
        var compatVersion = compatVersion
        val cutIndex = if (compatVersion.contains("=")) 2 else 1
        val sign: String = compatVersion.substring(0, cutIndex)
        compatVersion = compatVersion.substring(cutIndex)

        val compatSplit: com.google.common.collect.ImmutableList<Int?> =
            com.google.common.base.Splitter.on('.')
                .splitToStream(compatVersion)
                .map<Int?>(java.util.function.Function { s: String? -> java.lang.Integer.valueOf(s) })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Int?>())

        var result: Int =
            java.util.Objects.compare<com.google.common.collect.ImmutableList<Int?>?>(
                this.release,
                compatSplit,
                com.google.common.collect.Comparators.lexicographical<Int?, Int?>(java.util.Comparator.naturalOrder<Int?>())
            )
        if (result == 0 && this.isPrerelease) {
            result = -1
        }

        return (result == 0 && sign.contains("="))
                || (result > 0 && (sign.contains(">") || sign.contains("-")))
                || (result < 0 && (sign.contains("<") || sign.contains("-")))
    }

    companion object {
        private val PATTERN: java.util.regex.Pattern =
            java.util.regex.Pattern.compile("(?<release>(?:\\d+\\.)*\\d+)(?<suffix>(.*))?")

        /** Parses a version string into a [BazelVersion] object.  */
        fun parse(version: String?): BazelVersion {
            val matcher: java.util.regex.Matcher = PATTERN.matcher(version)
            com.google.common.base.Preconditions.checkArgument(
                matcher.matches(), "bad version (does not match regex): %s", version
            )

            val release: String = matcher.group("release")
            val suffix: String? = matcher.group("suffix")

            val releaseSplit: com.google.common.collect.ImmutableList<Int?> =
                com.google.common.base.Splitter.on('.').splitToStream(release)
                    .map<Int?>(java.util.function.Function { s: String? -> java.lang.Integer.valueOf(s) })
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<Int?>())
            return AutoValue_BazelVersion(releaseSplit, com.google.common.base.Strings.nullToEmpty(suffix), version)
        }
    }
}
