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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec
import java.util.function.IntPredicate

/**
 * Represents a version in the Bazel module system. The version format we support is `RELEASE[-PRERELEASE][+BUILD]`, where `RELEASE`, `PRERELEASE`, and `BUILD` are
 * each a sequence of "identifiers" (defined as a non-empty sequence of ASCII alphanumerical
 * characters and hyphens) separated by dots. The `RELEASE` part may not contain hyphens.
 * 
 * 
 * Otherwise, this format is identical to SemVer, especially in terms of the [comparison algorithm](https://semver.org/#spec-item-11). In other words, this format is
 * intentionally looser than SemVer; in particular:
 * 
 * 
 *  * the "release" part isn't limited to exactly 3 segments (major, minor, patch), but can be
 * fewer or more;
 *  * each segment in the "release" part can be identifiers instead of just numbers (so letters
 * are also allowed -- although hyphens are not).
 * 
 * 
 * 
 * Any valid SemVer version is a valid Bazel module version. Additionally, two SemVer versions
 * `a` and `b` compare `a < b` iff the same holds when they're compared as Bazel
 * module versions.
 * 
 * 
 * Versions with a "build" part are generally accepted as input, but they're treated as if the
 * "build" part is completely absent. That is, when Bazel outputs version strings, it never outputs
 * the "build" part (in fact, it doesn't even store it); similarly, when Bazel accesses registries
 * to request versions, the "build" part is never included. This gives us the nice property of
 * "consistent with equals" natural ordering (see [Comparable]); that is, `a.compareTo(b) == 0` iff `a.equals(b)`.
 * 
 * 
 * The special "empty string" version can also be used, and compares higher than everything else.
 * It signifies that there is a [NonRegistryOverride] for a module.
 */
@AutoCodec
class Version @Deprecated("Use {@link Version#parse(String)} instead.") constructor(
    release: com.google.common.collect.ImmutableList<Identifier?>?,
    prerelease: com.google.common.collect.ImmutableList<Identifier?>?,
    /** Returns the normalized version string (that is, with any "build" part stripped).  */
    @kotlin.jvm.JvmField val normalized: String?
) : Comparable<Version?> {
    /**
     * Represents an "identifier", a dot-separated segment in the version string. An identifier is
     * compared differently based on whether it's digits-only or not.
     */
    @AutoCodec
    @kotlin.jvm.JvmRecord
    internal data class Identifier(val isDigitsOnly: Boolean, val asNumber: Long, val asString: String?) :
        Comparable<Identifier?> {
        override fun compareTo(o: Identifier?): Int {
            return java.util.Objects.compare<Identifier?>(
                this,
                o,
                com.google.devtools.build.lib.bazel.bzlmod.Version.Identifier.Companion.COMPARATOR
            )
        }

        companion object {
            @Throws(com.google.devtools.build.lib.bazel.bzlmod.Version.ParseException::class)
            fun from(string: String?): Identifier {
                if (com.google.common.base.Strings.isNullOrEmpty(string)) {
                    throw com.google.devtools.build.lib.bazel.bzlmod.Version.ParseException("identifier is empty")
                }
                if (string.chars()
                        .allMatch(IntPredicate { codePoint: Int -> java.lang.Character.isDigit(codePoint) })
                ) {
                    try {
                        return com.google.devtools.build.lib.bazel.bzlmod.Version.Identifier(
                            true,
                            java.lang.Long.parseUnsignedLong(string),
                            string
                        )
                    } catch (e: java.lang.NumberFormatException) {
                        throw com.google.devtools.build.lib.bazel.bzlmod.Version.ParseException(
                            "numeric version segment is too large: " + string,
                            e
                        )
                    }
                } else {
                    return com.google.devtools.build.lib.bazel.bzlmod.Version.Identifier(false, 0, string)
                }
            }

            private val COMPARATOR: java.util.Comparator<Identifier?> =
                java.util.Comparator.comparing<Identifier?, Boolean?>(
                    com.google.devtools.build.lib.bazel.bzlmod.Version.Identifier::isDigitsOnly,
                    com.google.common.primitives.Booleans.trueFirst()
                )
                    .thenComparing(java.util.Comparator { a: Identifier?, b: Identifier? ->
                        java.lang.Long.compareUnsigned(
                            a!!.asNumber,
                            b!!.asNumber
                        )
                    })
                    .thenComparing<String?>(com.google.devtools.build.lib.bazel.bzlmod.Version.Identifier::asString)
        }
    }

    val isEmpty: Boolean
        /**
         * Whether this is just the "empty string" version, which signifies a non-registry override for
         * the module.
         */
        get() = this.normalized.isEmpty()

    /**
     * Whether this is a prerelease version (i.e. the prerelease part of the version string is
     * non-empty). A prerelease version compares lower than the same version without the prerelease
     * part.
     */
    fun isPrerelease(): Boolean {
        return !prerelease.isEmpty()
    }

    override fun compareTo(o: Version?): Int {
        return java.util.Objects.compare<Version?>(
            this,
            o,
            com.google.devtools.build.lib.bazel.bzlmod.Version.Companion.COMPARATOR
        )
    }

    override fun toString(): String {
        return this.normalized!!
    }

    override fun equals(o: Any?): Boolean {
        return this === o || (o is Version && o.normalized == this.normalized)
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash("version", this.normalized!!.hashCode())
    }

    /** An exception encountered while trying to [parse][Version.parse] a version.  */
    class ParseException : java.lang.Exception {
        constructor(message: String?) : super(message)

        constructor(message: String?, cause: Throwable?) : super(message, cause)
    }

    val release: com.google.common.collect.ImmutableList<Identifier?>?
    val prerelease: com.google.common.collect.ImmutableList<Identifier?>?

    init {
        this.prerelease = prerelease
        this.release = release
    }

    companion object {
        // We don't care about the "build" part at all so don't capture it.
        private val PATTERN: java.util.regex.Pattern = java.util.regex.Pattern.compile(
            "(?<release>[a-zA-Z0-9.]+)(?:-(?<prerelease>[a-zA-Z0-9.-]+))?(?:\\+[a-zA-Z0-9.-]+)?"
        )

        private val DOT_SPLITTER: com.google.common.base.Splitter = com.google.common.base.Splitter.on('.')

        /**
         * Represents the special "empty string" version, which compares higher than everything else and
         * signifies that there is a [NonRegistryOverride] for the module.
         */
        @kotlin.jvm.JvmField
        @Suppress("deprecation") // private usage of constructor
        val EMPTY: Version = com.google.devtools.build.lib.bazel.bzlmod.Version(
            com.google.common.collect.ImmutableList.of<Identifier?>(),
            com.google.common.collect.ImmutableList.of<Identifier?>(),
            ""
        )

        /** Parses a version string into a [Version] object.  */
        @kotlin.jvm.JvmStatic
        @Throws(com.google.devtools.build.lib.bazel.bzlmod.Version.ParseException::class)
        fun parse(version: String): Version {
            if (version.isEmpty()) {
                return com.google.devtools.build.lib.bazel.bzlmod.Version.Companion.EMPTY
            }
            val matcher: java.util.regex.Matcher =
                com.google.devtools.build.lib.bazel.bzlmod.Version.Companion.PATTERN.matcher(version)
            if (!matcher.matches()) {
                throw com.google.devtools.build.lib.bazel.bzlmod.Version.ParseException("bad version (does not match regex): " + version)
            }
            val release: String = matcher.group("release")
            val prerelease: String? = matcher.group("prerelease")

            val releaseSplit: com.google.common.collect.ImmutableList.Builder<Identifier?> =
                com.google.common.collect.ImmutableList.Builder<Identifier?>()
            for (ident in com.google.devtools.build.lib.bazel.bzlmod.Version.Companion.DOT_SPLITTER.split(release)) {
                try {
                    releaseSplit.add(com.google.devtools.build.lib.bazel.bzlmod.Version.Identifier.Companion.from(ident))
                } catch (e: ParseException) {
                    throw com.google.devtools.build.lib.bazel.bzlmod.Version.ParseException(
                        "error parsing version: " + version,
                        e
                    )
                }
            }

            val prereleaseSplit: com.google.common.collect.ImmutableList.Builder<Identifier?> =
                com.google.common.collect.ImmutableList.Builder<Identifier?>()
            if (!com.google.common.base.Strings.isNullOrEmpty(prerelease)) {
                for (ident in com.google.devtools.build.lib.bazel.bzlmod.Version.Companion.DOT_SPLITTER.split(prerelease)) {
                    try {
                        prereleaseSplit.add(
                            com.google.devtools.build.lib.bazel.bzlmod.Version.Identifier.Companion.from(
                                ident
                            )
                        )
                    } catch (e: ParseException) {
                        throw com.google.devtools.build.lib.bazel.bzlmod.Version.ParseException(
                            "error parsing version: " + version,
                            e
                        )
                    }
                }
            }

            val normalized =
                if (com.google.common.base.Strings.isNullOrEmpty(prerelease)) release else release + '-' + prerelease
            @Suppress("deprecation") val result:  // private usage of constructor
                    Version = com.google.devtools.build.lib.bazel.bzlmod.Version(
                releaseSplit.build(),
                prereleaseSplit.build(),
                normalized
            )
            return result
        }

        private val COMPARATOR: java.util.Comparator<Version?>? = java.util.Comparator.comparing<Version?, Boolean?>(
            java.util.function.Function { obj: Version? -> obj!!.isEmpty },
            com.google.common.primitives.Booleans.falseFirst()
        )
            .thenComparing<com.google.common.collect.ImmutableList<Identifier?>?>(
                com.google.devtools.build.lib.bazel.bzlmod.Version::release,
                com.google.common.collect.Comparators.lexicographical<Identifier?, Identifier?>(com.google.devtools.build.lib.bazel.bzlmod.Version.Identifier.Companion.COMPARATOR)
            )
            .thenComparing<Boolean?>(
                java.util.function.Function { obj: Version? -> obj!!.isPrerelease() },
                com.google.common.primitives.Booleans.trueFirst()
            )
            .thenComparing<com.google.common.collect.ImmutableList<Identifier?>?>(
                com.google.devtools.build.lib.bazel.bzlmod.Version::prerelease,
                com.google.common.collect.Comparators.lexicographical<Identifier?, Identifier?>(com.google.devtools.build.lib.bazel.bzlmod.Version.Identifier.Companion.COMPARATOR)
            )
    }
}
