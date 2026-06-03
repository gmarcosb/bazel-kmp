// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.TargetPattern.InterpretPathAsTarget

/** Tests for [com.google.devtools.build.lib.cmdline.TargetPattern].  */
@RunWith(JUnit4::class)
class TargetPatternTest {
    @org.junit.Test
    @Throws(TargetParsingException::class)
    fun validPatterns_mainRepo_atRepoRoot() {
        val parser: TargetPattern.Parser =
            Parser(
                PathFragment.EMPTY_FRAGMENT,
                RepositoryName.MAIN,
                RepositoryMapping.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "repo",
                        RepositoryName.createUnvalidated("canonical_repo")
                    ),
                    RepositoryName.MAIN
                )
            )

        assertThat(parser.parse(":foo")).isEqualTo(SingleTarget(":foo", label("@@//:foo")))
        assertThat(parser.parse("foo:bar"))
            .isEqualTo(SingleTarget("foo:bar", label("@@//foo:bar")))
        assertThat(parser.parse("foo:all"))
            .isEqualTo(TargetsInPackage("foo:all", pkg("@@//foo"), "all", false, true))
        assertThat(parser.parse("foo/...:all"))
            .isEqualTo(TargetsBelowDirectory("foo/...:all", pkg("@@//foo"), true))
        assertThat(parser.parse("foo:*"))
            .isEqualTo(TargetsInPackage("foo:*", pkg("@@//foo"), "*", false, false))
        assertThat(parser.parse("foo")).isEqualTo(InterpretPathAsTarget("foo", "foo"))
        assertThat(parser.parse("...")).isEqualTo(TargetsBelowDirectory("...", pkg("@@//"), true))
        assertThat(parser.parse("foo/bar")).isEqualTo(InterpretPathAsTarget("foo/bar", "foo/bar"))

        assertThat(parser.parse("//foo")).isEqualTo(SingleTarget("//foo", label("@@//foo:foo")))
        assertThat(parser.parse("//foo:bar"))
            .isEqualTo(SingleTarget("//foo:bar", label("@@//foo:bar")))
        assertThat(parser.parse("//foo:all"))
            .isEqualTo(TargetsInPackage("//foo:all", pkg("@@//foo"), "all", true, true))

        assertThat(parser.parse("//foo/all"))
            .isEqualTo(SingleTarget("//foo/all", label("@@//foo/all:all")))
        assertThat(parser.parse("//foo/...:all"))
            .isEqualTo(TargetsBelowDirectory("//foo/...:all", pkg("@@//foo"), true))
        assertThat(parser.parse("//..."))
            .isEqualTo(TargetsBelowDirectory("//...", pkg("@@//"), true))

        assertThat(parser.parse("@repo"))
            .isEqualTo(SingleTarget("@repo", label("@@canonical_repo//:repo")))
        assertThat(parser.parse("@repo//foo:bar"))
            .isEqualTo(SingleTarget("@repo//foo:bar", label("@@canonical_repo//foo:bar")))
        assertThat(parser.parse("@repo//foo:all"))
            .isEqualTo(
                TargetsInPackage(
                    "@repo//foo:all", pkg("@@canonical_repo//foo"), "all", true, true
                )
            )
        assertThat(parser.parse("@repo//:bar"))
            .isEqualTo(SingleTarget("@repo//:bar", label("@@canonical_repo//:bar")))
        assertThat(parser.parse("@repo//..."))
            .isEqualTo(TargetsBelowDirectory("@repo//...", pkg("@@canonical_repo//"), true))

        assertThat(parser.parse("@@repo"))
            .isEqualTo(SingleTarget("@@repo", label("@@repo//:repo")))
        assertThat(parser.parse("@@repo//foo:all"))
            .isEqualTo(TargetsInPackage("@@repo//foo:all", pkg("@@repo//foo"), "all", true, true))
        assertThat(parser.parse("@@repo//:bar"))
            .isEqualTo(SingleTarget("@@repo//:bar", label("@@repo//:bar")))
    }

    @org.junit.Test
    @Throws(TargetParsingException::class)
    fun validPatterns_mainRepo_inSomeRelativeDirectory() {
        val parser: TargetPattern.Parser =
            Parser(
                PathFragment.create("base"),
                RepositoryName.MAIN,
                RepositoryMapping.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "repo",
                        RepositoryName.createUnvalidated("canonical_repo")
                    ),
                    RepositoryName.MAIN
                )
            )

        assertThat(parser.parse(":foo")).isEqualTo(SingleTarget(":foo", label("@@//base:foo")))
        assertThat(parser.parse("foo:bar"))
            .isEqualTo(SingleTarget("foo:bar", label("@@//base/foo:bar")))
        assertThat(parser.parse("foo:all"))
            .isEqualTo(TargetsInPackage("foo:all", pkg("@@//base/foo"), "all", false, true))
        assertThat(parser.parse("foo/...:all"))
            .isEqualTo(TargetsBelowDirectory("foo/...:all", pkg("@@//base/foo"), true))
        assertThat(parser.parse("foo:*"))
            .isEqualTo(TargetsInPackage("foo:*", pkg("@@//base/foo"), "*", false, false))
        assertThat(parser.parse("foo")).isEqualTo(InterpretPathAsTarget("foo", "base/foo"))
        assertThat(parser.parse("..."))
            .isEqualTo(TargetsBelowDirectory("...", pkg("@@//base"), true))
        assertThat(parser.parse("foo/bar"))
            .isEqualTo(InterpretPathAsTarget("foo/bar", "base/foo/bar"))

        assertThat(parser.parse("//foo")).isEqualTo(SingleTarget("//foo", label("@@//foo:foo")))
        assertThat(parser.parse("//foo:bar"))
            .isEqualTo(SingleTarget("//foo:bar", label("@@//foo:bar")))
        assertThat(parser.parse("//foo:all"))
            .isEqualTo(TargetsInPackage("//foo:all", pkg("@@//foo"), "all", true, true))

        assertThat(parser.parse("//foo/all"))
            .isEqualTo(SingleTarget("//foo/all", label("@@//foo/all:all")))
        assertThat(parser.parse("//foo/...:all"))
            .isEqualTo(TargetsBelowDirectory("//foo/...:all", pkg("@@//foo"), true))
        assertThat(parser.parse("//..."))
            .isEqualTo(TargetsBelowDirectory("//...", pkg("@@//"), true))

        assertThat(parser.parse("@repo"))
            .isEqualTo(SingleTarget("@repo", label("@@canonical_repo//:repo")))
        assertThat(parser.parse("@repo//foo:bar"))
            .isEqualTo(SingleTarget("@repo//foo:bar", label("@@canonical_repo//foo:bar")))
        assertThat(parser.parse("@repo//foo:all"))
            .isEqualTo(
                TargetsInPackage(
                    "@repo//foo:all", pkg("@@canonical_repo//foo"), "all", true, true
                )
            )
        assertThat(parser.parse("@repo//:bar"))
            .isEqualTo(SingleTarget("@repo//:bar", label("@@canonical_repo//:bar")))
        assertThat(parser.parse("@repo//..."))
            .isEqualTo(TargetsBelowDirectory("@repo//...", pkg("@@canonical_repo//"), true))

        assertThat(parser.parse("@@repo"))
            .isEqualTo(SingleTarget("@@repo", label("@@repo//:repo")))
        assertThat(parser.parse("@@repo//foo:all"))
            .isEqualTo(TargetsInPackage("@@repo//foo:all", pkg("@@repo//foo"), "all", true, true))
        assertThat(parser.parse("@@repo//:bar"))
            .isEqualTo(SingleTarget("@@repo//:bar", label("@@repo//:bar")))
    }

    @org.junit.Test
    @Throws(TargetParsingException::class)
    fun validPatterns_nonMainRepo() {
        val parser: TargetPattern.Parser =
            Parser(
                PathFragment.EMPTY_FRAGMENT,
                RepositoryName.createUnvalidated("my_repo"),
                RepositoryMapping.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "repo",
                        RepositoryName.createUnvalidated("canonical_repo")
                    ),
                    RepositoryName.createUnvalidated("my_repo")
                )
            )

        assertThat(parser.parse(":foo")).isEqualTo(SingleTarget(":foo", label("@@my_repo//:foo")))
        assertThat(parser.parse("foo:bar"))
            .isEqualTo(SingleTarget("foo:bar", label("@@my_repo//foo:bar")))
        assertThat(parser.parse("foo:all"))
            .isEqualTo(TargetsInPackage("foo:all", pkg("@@my_repo//foo"), "all", false, true))
        assertThat(parser.parse("foo/...:all"))
            .isEqualTo(TargetsBelowDirectory("foo/...:all", pkg("@@my_repo//foo"), true))
        assertThat(parser.parse("foo:*"))
            .isEqualTo(TargetsInPackage("foo:*", pkg("@@my_repo//foo"), "*", false, false))
        assertThat(parser.parse("foo")).isEqualTo(SingleTarget("foo", label("@@my_repo//:foo")))
        assertThat(parser.parse("..."))
            .isEqualTo(TargetsBelowDirectory("...", pkg("@@my_repo//"), true))
        assertThat(parser.parse("foo/bar"))
            .isEqualTo(SingleTarget("foo/bar", label("@@my_repo//:foo/bar")))

        assertThat(parser.parse("//foo"))
            .isEqualTo(SingleTarget("//foo", label("@@my_repo//foo:foo")))
        assertThat(parser.parse("//foo:bar"))
            .isEqualTo(SingleTarget("//foo:bar", label("@@my_repo//foo:bar")))
        assertThat(parser.parse("//foo:all"))
            .isEqualTo(TargetsInPackage("//foo:all", pkg("@@my_repo//foo"), "all", true, true))

        assertThat(parser.parse("//foo/all"))
            .isEqualTo(SingleTarget("//foo/all", label("@@my_repo//foo/all:all")))
        assertThat(parser.parse("//foo/...:all"))
            .isEqualTo(TargetsBelowDirectory("//foo/...:all", pkg("@@my_repo//foo"), true))
        assertThat(parser.parse("//..."))
            .isEqualTo(TargetsBelowDirectory("//...", pkg("@@my_repo//"), true))

        assertThat(parser.parse("@repo"))
            .isEqualTo(SingleTarget("@repo", label("@@canonical_repo//:repo")))
        assertThat(parser.parse("@repo//foo:bar"))
            .isEqualTo(SingleTarget("@repo//foo:bar", label("@@canonical_repo//foo:bar")))
        assertThat(parser.parse("@repo//foo:all"))
            .isEqualTo(
                TargetsInPackage(
                    "@repo//foo:all", pkg("@@canonical_repo//foo"), "all", true, true
                )
            )
        assertThat(parser.parse("@repo//:bar"))
            .isEqualTo(SingleTarget("@repo//:bar", label("@@canonical_repo//:bar")))
        assertThat(parser.parse("@repo//..."))
            .isEqualTo(TargetsBelowDirectory("@repo//...", pkg("@@canonical_repo//"), true))

        assertThat(parser.parse("@@repo"))
            .isEqualTo(SingleTarget("@@repo", label("@@repo//:repo")))
        assertThat(parser.parse("@@repo//foo:all"))
            .isEqualTo(TargetsInPackage("@@repo//foo:all", pkg("@@repo//foo"), "all", true, true))
        assertThat(parser.parse("@@repo//:bar"))
            .isEqualTo(SingleTarget("@@repo//:bar", label("@@repo//:bar")))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidPatterns() {
        val badPatterns: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "//Bar\\java",
                "",
                "/foo",
                "///foo",
                "@",
                "@foo//",
                "@@"
            )
        val repoMappingEntries: com.google.common.collect.ImmutableMap<String?, RepositoryName?> =
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                "repo",
                RepositoryName.createUnvalidated("canonical_repo")
            )
        for (parser in com.google.common.collect.ImmutableList.of<Any?>(
            Parser(
                PathFragment.EMPTY_FRAGMENT,
                RepositoryName.MAIN,
                RepositoryMapping.create(repoMappingEntries, RepositoryName.MAIN)
            ),
            Parser(
                PathFragment.create("base"),
                RepositoryName.MAIN,
                RepositoryMapping.create(repoMappingEntries, RepositoryName.MAIN)
            ),
            Parser(
                PathFragment.EMPTY_FRAGMENT,
                RepositoryName.create("my_repo"),
                RepositoryMapping.create(repoMappingEntries, RepositoryName.create("my_repo"))
            )
        )) {
            for (pattern in badPatterns) {
                try {
                    val parsed: TargetPattern? = parser.parse(pattern)
                    org.junit.Assert.fail(
                        java.lang.String.format(
                            "parsing should have failed for pattern \"%s\" with parser in repo %s at"
                                    + " relative directory [%s], but succeeded with the result:\n%s",
                            pattern, parser.getCurrentRepo(), parser.getRelativeDirectory(), parsed
                        )
                    )
                } catch (expected: TargetParsingException) {
                }
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidParser_nonMainRepo_nonEmptyRelativeDirectory() {
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                Parser(
                    PathFragment.create("base"),
                    RepositoryName.create("my_repo"),
                    RepositoryMapping.EMPTY
                )
            })
    }

    @org.junit.Test
    fun testNormalize() {
        // Good cases.
        assertThat(TargetPattern.normalize("empty")).isEqualTo("empty")
        assertThat(TargetPattern.normalize("a/b")).isEqualTo("a/b")
        assertThat(TargetPattern.normalize("a/b/c")).isEqualTo("a/b/c")
        assertThat(TargetPattern.normalize("a/b/c.d")).isEqualTo("a/b/c.d")
        assertThat(TargetPattern.normalize("a/b/c..")).isEqualTo("a/b/c..")
        assertThat(TargetPattern.normalize("a/b/c...")).isEqualTo("a/b/c...")

        assertThat(TargetPattern.normalize("a/b/")).isEqualTo("a/b") // Remove trailing empty segments
        assertThat(TargetPattern.normalize("a//c")).isEqualTo("a/c") // Remove empty inner segments
        assertThat(TargetPattern.normalize("a/./d")).isEqualTo("a/d") // Remove inner dot segments
        assertThat(TargetPattern.normalize("a/.")).isEqualTo("a") // Remove trailing dot segments
        // Remove .. segment and its predecessor
        assertThat(TargetPattern.normalize("a/b/../e")).isEqualTo("a/e")
        // Remove trailing .. segment and its predecessor
        assertThat(TargetPattern.normalize("a/g/b/..")).isEqualTo("a/g")
        // Remove double .. segments and two predecessors
        assertThat(TargetPattern.normalize("a/b/c/../../h")).isEqualTo("a/h")
        // Don't remove leading .. segments
        assertThat(TargetPattern.normalize("../a")).isEqualTo("../a")
        assertThat(TargetPattern.normalize("../../a")).isEqualTo("../../a")
        assertThat(TargetPattern.normalize("../../../a")).isEqualTo("../../../a")
        assertThat(TargetPattern.normalize("a/../../../b")).isEqualTo("../../b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetsBelowDirectoryContainsColonStar() {
        // Given an outer pattern '//foo/...', that matches rules only,
        val outerPattern: TargetsBelowDirectory = parseAsTBD("//foo/...")
        // And a nested inner pattern '//foo/bar/...:*', that matches all targets,
        val innerPattern: TargetsBelowDirectory = parseAsTBD("//foo/bar/...:*")
        // Then a directory exclusion would exactly describe the subtraction of the inner pattern from
        // the outer pattern,
        assertThat(outerPattern.contains(innerPattern))
            .isEqualTo(ContainsResult.DIRECTORY_EXCLUSION_WOULD_BE_EXACT)
        // And the inner pattern does not contain the outer pattern.
        assertThat(innerPattern.contains(outerPattern)).isEqualTo(ContainsResult.NOT_CONTAINED)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetsBelowDirectoryColonStarContains() {
        // Given an outer pattern '//foo/...:*', that matches all targets,
        val outerPattern: TargetsBelowDirectory = parseAsTBD("//foo/...:*")
        // And a nested inner pattern '//foo/bar/...', that matches rules only,
        val innerPattern: TargetsBelowDirectory = parseAsTBD("//foo/bar/...")
        // Then a directory exclusion would be too broad,
        assertThat(outerPattern.contains(innerPattern))
            .isEqualTo(ContainsResult.DIRECTORY_EXCLUSION_WOULD_BE_TOO_BROAD)
        // And the inner pattern does not contain the outer pattern.
        assertThat(innerPattern.contains(outerPattern)).isEqualTo(ContainsResult.NOT_CONTAINED)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetsBelowDirectoryContainsNestedPatterns() {
        // Given an outer pattern '//foo/...',
        val outerPattern: TargetsBelowDirectory = parseAsTBD("//foo/...")
        // And a nested inner pattern '//foo/bar/...',
        val innerPattern: TargetsBelowDirectory = parseAsTBD("//foo/bar/...")
        // Then the outer pattern contains the inner pattern,
        assertThat(outerPattern.contains(innerPattern))
            .isEqualTo(ContainsResult.DIRECTORY_EXCLUSION_WOULD_BE_EXACT)
        // And the inner pattern does not contain the outer pattern.
        assertThat(innerPattern.contains(outerPattern)).isEqualTo(ContainsResult.NOT_CONTAINED)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetsBelowDirectoryIsExcludableFromForIndependentPatterns() {
        // Given a pattern '//foo/...',
        val patternFoo: TargetsBelowDirectory = parseAsTBD("//foo/...")
        // And a pattern '//bar/...',
        val patternBar: TargetsBelowDirectory = parseAsTBD("//bar/...")
        // Then neither pattern contains the other.
        assertThat(patternFoo.contains(patternBar)).isEqualTo(ContainsResult.NOT_CONTAINED)
        assertThat(patternBar.contains(patternFoo)).isEqualTo(ContainsResult.NOT_CONTAINED)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetsBelowDirectoryDoesNotContainCoincidentPrefixPatterns() {
        // Given a TargetsBelowDirectory pattern, tbdFoo of '//foo/...',
        val tbdFoo: TargetsBelowDirectory = parseAsTBD("//foo/...")

        // And a target pattern with prefix equal to the directory of the TBD pattern, but not below it,
        val targetsBelowDirectoryPattern: TargetsBelowDirectory = parseAsTBD("//food/...")

        // Then it is not contained in the first pattern.
        assertThat(tbdFoo.contains(targetsBelowDirectoryPattern))
            .isEqualTo(ContainsResult.NOT_CONTAINED)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDepotRootTargetsBelowDirectoryContainsPatterns() {
        // Given a TargetsBelowDirectory pattern, tbdDepot of '//...',
        val tbdDepot: TargetsBelowDirectory = parseAsTBD("//...")

        // And a target pattern for a directory,
        val tbdFoo: TargetsBelowDirectory = parseAsTBD("//foo/...")

        // Then the pattern is contained by tbdDepot, and does not contain tbdDepot.
        assertThat(tbdDepot.contains(tbdFoo))
            .isEqualTo(ContainsResult.DIRECTORY_EXCLUSION_WOULD_BE_EXACT)
        assertThat(tbdFoo.contains(tbdDepot)).isEqualTo(ContainsResult.NOT_CONTAINED)
    }

    companion object {
        private fun label(raw: String?): Label {
            return Label.parseCanonicalUnchecked(raw)
        }

        private fun pkg(raw: String?): PackageIdentifier {
            try {
                return PackageIdentifier.parse(raw)
            } catch (e: LabelSyntaxException) {
                throw java.lang.RuntimeException(e)
            }
        }

        @Throws(TargetParsingException::class)
        private fun parseAsTBD(pattern: String?): TargetsBelowDirectory {
            val parsedPattern: TargetPattern = TargetPattern.defaultParser().parse(pattern)
            assertThat(parsedPattern.type).isEqualTo(TargetPattern.Type.TARGETS_BELOW_DIRECTORY)
            assertThat(parsedPattern.originalPattern).isEqualTo(pattern)
            return parsedPattern as TargetsBelowDirectory
        }
    }
}
