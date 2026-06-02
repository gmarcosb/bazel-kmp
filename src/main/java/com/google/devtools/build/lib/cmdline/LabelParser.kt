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
package com.google.devtools.build.lib.cmdline

import com.google.auto.value.AutoValue
import com.google.devtools.build.lib.cmdline.LabelSyntaxException
import com.google.devtools.build.lib.cmdline.LabelValidator
import com.google.devtools.build.lib.cmdline.RepositoryName

/** Utilities to help parse labels.  */
internal object LabelParser {
    @com.google.errorprone.annotations.FormatMethod
    fun syntaxErrorf(format: String, vararg args: Any?): LabelSyntaxException {
        return LabelSyntaxException(
            com.google.devtools.build.lib.util.StringUtilities.sanitizeControlChars(
                java.lang.String.format(
                    format,
                    *args
                )
            )
        )
    }

    private fun perhapsYouMeantMessage(pkg: String, target: String): String {
        return if (pkg.endsWith('/'.toString() + target)) " (perhaps you meant \":" + target + "\"?)" else ""
    }

    @Throws(LabelSyntaxException::class)
    fun validateAndProcessTargetName(
        pkg: String, target: String, pkgEndsWithTripleDots: Boolean
    ): String {
        if (pkgEndsWithTripleDots && target.isEmpty()) {
            // Allow empty target name if the package part ends in '...'.
            return target
        }
        val targetError: String? = LabelValidator.validateTargetName(target)
        if (targetError != null) {
            throw syntaxErrorf(
                "invalid target name '%s': %s%s",
                target, targetError, perhapsYouMeantMessage(pkg, target)
            )
        }
        // TODO(bazel-team): This should be an error, but we can't make it one for legacy reasons.
        if (target.endsWith("/.")) {
            return target.substring(0, target.length() - 2)
        }
        return target
    }

    /**
     * Contains the parsed elements of a label string. The parts are validated (they don't contain
     * invalid characters). See [.parse] for valid label patterns.
     */
    @AutoValue
    internal abstract class Parts {
        /**
         * The `@repo` or `@@canonical_repo` part of the string (sans any leading
         * @s); can be null if it doesn't have such a part (i.e. if it doesn't start with a
         * @).
         */
        abstract fun repo(): String?

        /**
         * Whether the repo part is using the canonical repo syntax (two @s) or not (one
         * @). If there is no repo part, this is false.
         */
        abstract fun repoIsCanonical(): Boolean

        /**
         * Whether the package part of the string is prefixed by double-slash. This can only be false if
         * the repo part is missing.
         */
        abstract fun pkgIsAbsolute(): Boolean

        /**
         * The package part of the string (sans the leading double-slash, if present; also sans the
         * final '...' segment, if present).
         */
        abstract fun pkg(): String?

        /** Whether the package part of the string ends with a '...' segment.  */
        abstract fun pkgEndsWithTripleDots(): Boolean

        /** The target part of the string (sans colon).  */
        abstract fun target(): String?

        /** The original unparsed raw string.  */
        abstract fun raw(): String?

        @Throws(LabelSyntaxException::class)
        fun checkPkgIsAbsolute() {
            if (!pkgIsAbsolute()) {
                throw syntaxErrorf("invalid label '%s': absolute label must begin with '@' or '//'", raw())
            }
        }

        @Throws(LabelSyntaxException::class)
        fun checkPkgDoesNotEndWithTripleDots() {
            if (pkgEndsWithTripleDots()) {
                throw syntaxErrorf("invalid label '%s': package name cannot contain '...'", raw())
            }
        }

        companion object {
            @kotlin.jvm.JvmStatic
            @com.google.common.annotations.VisibleForTesting
            @Throws(LabelSyntaxException::class)
            fun validateAndCreate(
                repo: String?,
                repoIsCanonical: Boolean,
                pkgIsAbsolute: Boolean,
                pkg: String,
                pkgEndsWithTripleDots: Boolean,
                target: String,
                raw: String?
            ): Parts {
                validateRepoName(repo)
                validatePackageName(pkg, target)
                return AutoValue_LabelParser_Parts(
                    repo,
                    repoIsCanonical,
                    pkgIsAbsolute,
                    pkg,
                    pkgEndsWithTripleDots,
                    validateAndProcessTargetName(pkg, target, pkgEndsWithTripleDots),
                    raw
                )
            }

            /**
             * Parses a raw label string into parts. The logic can be summarized by the following table:
             * 
             * <pre>`raw| repo| repoIs-| pkgIs-| pkg| pkgEndsWith- | target                       || Canonical | Absolute || TripleDots| ----------------------+--------+-----------+----------+-----------+--------------+----------- "foo/bar"             | null   | false     | false    | ""        | false        | "foo/bar" "..."                 | null   | false     | false    | ""        | true         | "" "...:all"             | null   | false     | false    | ""        | true         | "all" "foo/..."             | null   | false     | false    | "foo"     | true         | "" "//foo/bar"           | null   | false     | true     | "foo/bar" | false        | "bar" "//foo/..."           | null   | false     | true     | "foo"     | true         | "" "//foo/...:all"       | null   | false     | true     | "foo"     | true         | "all" "//foo/all"           | null   | false     | true     | "foo/all" | false        | "all" "@repo"               | "repo" | false     | true     | ""        | false        | "repo" "@@repo"              | "repo" | true      | true     | ""        | false        | "repo" "@repo//foo/bar"      | "repo" | false     | true     | "foo/bar" | false        | "bar" "@@repo//foo/bar"     | "repo" | true      | true     | "foo/bar" | false        | "bar" ":quux"               | null   | false     | false    | ""        | false        | "quux" "foo/bar:quux"        | null   | false     | false    | "foo/bar" | false        | "quux" "//foo/bar:quux"      | null   | false     | true     | "foo/bar" | false        | "quux" "@repo//foo/bar:quux" | "repo" | false     | true     | "foo/bar" | false        | "quux" `</pre>
             */
            @kotlin.jvm.JvmStatic
            @Throws(LabelSyntaxException::class)
            fun parse(rawLabel: String): Parts {
                val repo: String?
                val repoIsCanonical: Boolean = rawLabel.startsWith("@@")
                val startOfPackage: Int
                val doubleSlashIndex: Int = rawLabel.indexOf("//")
                val pkgIsAbsolute: Boolean
                if (rawLabel.startsWith("@")) {
                    if (doubleSlashIndex < 0) {
                        // Special case: the label "@foo" is synonymous with "@foo//:foo".
                        repo = rawLabel.substring(if (repoIsCanonical) 2 else 1)
                        return Companion.validateAndCreate(
                            repo,
                            repoIsCanonical,  /* pkgIsAbsolute= */
                            true,  /* pkg= */
                            "",  /* pkgEndsWithTripleDots= */
                            false,  /* target= */
                            repo!!,
                            rawLabel
                        )
                    } else {
                        repo = rawLabel.substring(if (repoIsCanonical) 2 else 1, doubleSlashIndex)
                        startOfPackage = doubleSlashIndex + 2
                        pkgIsAbsolute = true
                    }
                } else {
                    // If the label begins with '//', it's an absolute label. Otherwise, treat it as relative
                    // (the command-line kind).
                    pkgIsAbsolute = doubleSlashIndex == 0
                    startOfPackage = if (doubleSlashIndex == 0) 2 else 0
                    repo = null
                }

                val pkg: String
                val target: String
                val colonIndex: Int = rawLabel.indexOf(':'.code, startOfPackage)
                val rawPkg: String =
                    rawLabel.substring(startOfPackage, if (colonIndex >= 0) colonIndex else rawLabel.length())
                val pkgEndsWithTripleDots = rawPkg.endsWith("/...") || rawPkg == "..."
                if (colonIndex < 0 && pkgEndsWithTripleDots) {
                    // Special case: if the entire label ends in '...', the target name is empty.
                    pkg = stripTrailingTripleDots(rawPkg)
                    target = ""
                } else if (colonIndex < 0 && !pkgIsAbsolute) {
                    // Special case: the label "foo/bar" is synonymous with ":foo/bar".
                    pkg = ""
                    target = rawLabel.substring(startOfPackage)
                } else {
                    pkg = stripTrailingTripleDots(rawPkg)
                    if (colonIndex >= 0) {
                        target = rawLabel.substring(colonIndex + 1)
                    } else {
                        // Special case: the label "[@repo]//foo/bar" is synonymous with "[@repo]//foo/bar:bar".
                        // The target name is the last package segment (works even if `pkg` contains no slash)
                        target = pkg.substring(pkg.lastIndexOf('/'.code) + 1)
                    }
                }
                return validateAndCreate(
                    repo, repoIsCanonical, pkgIsAbsolute, pkg, pkgEndsWithTripleDots, target, rawLabel
                )
            }

            private fun stripTrailingTripleDots(pkg: String): String {
                if (pkg.endsWith("/...")) {
                    return pkg.substring(0, pkg.length() - 4)
                }
                if (pkg == "...") {
                    return ""
                }
                return pkg
            }

            @Throws(LabelSyntaxException::class)
            private fun validateRepoName(repo: String?) {
                if (repo != null) {
                    RepositoryName.Companion.validate(repo)
                }
            }

            @Throws(LabelSyntaxException::class)
            private fun validatePackageName(pkg: String, target: String) {
                val pkgError: String? = LabelValidator.validatePackageName(pkg)
                if (pkgError != null) {
                    throw syntaxErrorf(
                        "invalid package name '%s': %s%s", pkg, pkgError, perhapsYouMeantMessage(pkg, target)
                    )
                }
            }
        }
    }
}
