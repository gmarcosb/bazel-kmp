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
package com.google.devtools.build.lib.pkgcache

import com.google.devtools.build.lib.packages.Attribute.attr

/** Tests for [TargetPatternPreloader].  */
@RunWith(JUnit4::class)
class TargetPatternEvaluatorTest : AbstractTargetPatternEvaluatorTest() {
    private var fooOffset: PathFragment? = null

    private var rulesBeneathFoo: MutableSet<Label?>? = null
    private var rulesInFoo: MutableSet<Label?>? = null
    private var targetsInFoo: MutableSet<Label?>? = null
    private var targetsInFooBar: MutableSet<Label?>? = null
    private var targetsBeneathFoo: MutableSet<Label?>? = null
    private var targetsInOtherrules: MutableSet<Label?>? = null

    protected val extraRules: MutableList<RuleDefinition>
        get() = com.google.common.collect.ImmutableList.of<RuleDefinition?>(FAKE_CC_LIBRARY)

    @Before
    @Throws(java.lang.Exception::class)
    fun createFiles() {
        // TODO(ulfjack): Also disable the implicit C++ outputs in Google's internal version.
        val hasImplicitCcOutputs =
            (ruleClassProvider.getRuleClassMap().get("cc_library").getDefaultImplicitOutputsFunction()
                    !== SafeImplicitOutputsFunction.NONE)

        scratch.file("BUILD", "filegroup(name = 'fg', srcs = glob(['*.cc']))")
        scratch.file("foo.cc")

        scratch.file(
            "foo/BUILD",
            """
        fake_cc_library(
            name = "foo1",
            srcs = ["foo1.cc"],
            hdrs = ["foo1.h"],
        )

        exports_files(["baz/bang"])
        
        """.trimIndent()
        )
        scratch.file(
            "foo/bar/BUILD",
            """
        fake_cc_library(
            name = "bar1",
            alwayslink = 1,
        )

        fake_cc_library(name = "bar2")

        exports_files([
            "wiz/bang",
            "wiz/all",
            "baz",
            "baz/bang",
            "undeclared.h",
        ])
        
        """.trimIndent()
        )

        // 'filegroup' and 'test_suite' are rules, but 'exports_files' is not.
        scratch.file(
            "otherrules/BUILD",
            """
        test_suite(name = "suite1")

        filegroup(
            name = "group",
            srcs = ["suite/somefile"],
        )

        exports_files(["suite/somefile"])

        fake_cc_library(
            name = "wiz",
            linkstatic = 1,
        )
        
        """.trimIndent()
        )
        scratch.file("nosuchpkg/subdir/empty", "")

        val foo: Path = scratch.dir("foo")
        fooOffset = foo.relativeTo(rootDirectory)

        rulesBeneathFoo = labels("//foo:foo1", "//foo/bar:bar1", "//foo/bar:bar2")
        rulesInFoo = labels("//foo:foo1")

        targetsInFoo =
            labels(
                "//foo:foo1",
                "//foo:foo1",
                "//foo:foo1.cc",
                "//foo:foo1.h",
                "//foo:BUILD",
                "//foo:baz/bang"
            )
        if (hasImplicitCcOutputs) {
            targetsInFoo!!.addAll(labels("//foo:libfoo1.a", "//foo:libfoo1.so"))
        }
        targetsInFooBar =
            labels(
                "//foo/bar:bar1",
                "//foo/bar:bar2",
                "//foo/bar:BUILD",
                "//foo/bar:wiz/bang",
                "//foo/bar:wiz/all",
                "//foo/bar:baz",
                "//foo/bar:baz/bang",
                "//foo/bar:undeclared.h"
            )
        if (hasImplicitCcOutputs) {
            targetsInFooBar!!.addAll(labels("//foo/bar:libbar1.lo", "//foo/bar:libbar2.a"))
        }
        targetsBeneathFoo = HashSet<Label?>()
        targetsBeneathFoo!!.addAll(targetsInFoo)
        targetsBeneathFoo!!.addAll(targetsInFooBar)

        targetsInOtherrules =
            labels(
                "//otherrules:group",
                "//otherrules:wiz",
                "//otherrules:suite1",
                "//otherrules:BUILD",
                "//otherrules:suite/somefile",
                "//otherrules:wiz",
                "//otherrules:suite1"
            )
        if (hasImplicitCcOutputs) {
            targetsInOtherrules!!.addAll(labels("//otherrules:libwiz.a"))
        }
    }

    @Throws(java.lang.InterruptedException::class, AbruptExitException::class)
    private fun invalidate(file: String?) {
        skyframeExecutor.invalidateFilesUnderPathForTesting(
            reporter,
            ModifiedFileSet.builder().modify(PathFragment.create(file)).build(),
            Root.fromPath(rootDirectory)
        )
    }

    @Throws(java.lang.InterruptedException::class, AbruptExitException::class)
    private fun invalidate(modifiedFileSet: ModifiedFileSet?) {
        skyframeExecutor.invalidateFilesUnderPathForTesting(
            reporter, modifiedFileSet, Root.fromPath(rootDirectory)
        )
    }

    private fun setDeletedPackages(deletedPackages: MutableSet<PackageIdentifier?>?) {
        skyframeExecutor.setDeletedPackages(deletedPackages)
    }

    @Throws(TargetParsingException::class, java.lang.InterruptedException::class)
    private fun parseList(vararg patterns: String?): MutableSet<Label?> {
        return targetsToLabels(
            getFailFast(
                parseTargetPatternList(parser, parsingListener, java.util.Arrays.< T > asList < T ? > (patterns), false)
            )
        )
    }

    @Throws(TargetParsingException::class, java.lang.InterruptedException::class)
    private fun parseListKeepGoingExpectFailure(vararg patterns: String?): MutableSet<Label?> {
        val result: ResolvedTargets<Target?> =
            parseTargetPatternList(parser, parsingListener, java.util.Arrays.< T > asList < T ? > (patterns), true)
        return targetsToLabels(result.getTargets())
    }

    @Throws(TargetParsingException::class, java.lang.InterruptedException::class)
    private fun parseListRelative(vararg patterns: String?): MutableSet<Label?> {
        return targetsToLabels(
            getFailFast(
                parseTargetPatternList(
                    fooOffset, parser, parsingListener, java.util.Arrays.< T > asList < T ? > (patterns), false
                )
            )
        )
    }

    @Throws(java.lang.InterruptedException::class)
    private fun expectError(
        offset: PathFragment?, parser: TargetPatternPreloader?, expectedError: String?, target: String
    ) {
        val e: TargetParsingException? =
            org.junit.Assert.assertThrows<T?>(
                "target='" + target + "', expected error: " + expectedError,
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    parseTargetPatternList(
                        offset, parser, parsingListener, com.google.common.collect.ImmutableList.of<E?>(target), false
                    )
                })
        assertThat(e).hasMessageThat().contains(expectedError)
    }

    @Throws(java.lang.InterruptedException::class)
    private fun expectError(expectedError: String?, target: String) {
        expectError(PathFragment.EMPTY_FRAGMENT, parser, expectedError, target)
    }

    @Throws(java.lang.Exception::class)
    private fun parseIndividualTarget(targetLabel: String): Label {
        return com.google.common.collect.Iterables.getOnlyElement<Target?>(
            getFailFast(
                parseTargetPatternList(
                    parser, parsingListener, com.google.common.collect.ImmutableList.of<E?>(targetLabel), false
                )
            )
        )
            .getLabel()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testModifiedBuildFile() {
        Truth.assertThat(parseList("foo:all")).containsExactlyElementsIn(rulesInFoo)
        assertNoEvents()

        scratch.overwriteFile(
            "foo/BUILD",
            """
        fake_cc_library(
            name = "foo1",
            srcs = ["foo1.cc"],
            hdrs = ["foo1.h"],
        )

        fake_cc_library(
            name = "foo2",
            srcs = ["foo1.cc"],
            hdrs = ["foo1.h"],
        )
        
        """.trimIndent()
        )
        invalidate("foo/BUILD")
        Truth.assertThat(parseList("foo:all")).containsExactlyElementsIn(labels("//foo:foo1", "//foo:foo2"))
    }

    /**
     * Test that the relative path label parsing behaves as stated in the target-syntax documentation.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRelativePathLabel() {
        scratch.file("sub/BUILD", "exports_files(['dir2/dir2'])")
        scratch.file("sub/dir/BUILD", "exports_files(['dir2'])")
        scratch.file("sub/dir/dir/BUILD", "exports_files(['dir'])")
        // sub/dir/dir is a package
        assertThat(parseIndividualTarget("sub/dir/dir").toString()).isEqualTo("//sub/dir/dir:dir")
        // sub/dir is a package but not sub/dir/dir2
        assertThat(parseIndividualTarget("sub/dir/dir2").toString()).isEqualTo("//sub/dir:dir2")
        // sub is a package but not sub/dir2
        assertThat(parseIndividualTarget("sub/dir2/dir2").toString()).isEqualTo("//sub:dir2/dir2")
    }

    /** Regression test for a bug.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDotDotDotDoesntMatchDeletedPackages() {
        scratch.file("x/y/BUILD", "fake_cc_library(name='y')")
        scratch.file("x/z/BUILD", "fake_cc_library(name='z')")
        setDeletedPackages(com.google.common.collect.Sets.newHashSet(PackageIdentifier.createInMainRepo("x/y")))
        Truth.assertThat(parseList("x/..."))
            .isEqualTo(com.google.common.collect.Sets.newHashSet(Label.parseCanonical("//x/z")))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDotDotDotDoesntMatchDeletedPackagesRelative() {
        scratch.file("x/y/BUILD", "fake_cc_library(name='y')")
        scratch.file("x/z/BUILD", "fake_cc_library(name='z')")
        setDeletedPackages(com.google.common.collect.Sets.newHashSet(PackageIdentifier.createInMainRepo("x/y")))

        assertThat(
            targetsToLabels(
                getFailFast(
                    parseTargetPatternList(
                        PathFragment.create("x"),
                        parser,
                        parsingListener,
                        com.google.common.collect.ImmutableList.of<E?>("..."),
                        false
                    )
                )
            )
        )
            .isEqualTo(com.google.common.collect.Sets.newHashSet(Label.parseCanonical("//x/z")))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeletedPackagesIncrementality() {
        scratch.file("x/y/BUILD", "fake_cc_library(name='y')")
        scratch.file("x/z/BUILD", "fake_cc_library(name='z')")

        Truth.assertThat(parseList("x/..."))
            .containsExactly(Label.parseCanonical("//x/y"), Label.parseCanonical("//x/z"))

        setDeletedPackages(com.google.common.collect.Sets.newHashSet(PackageIdentifier.createInMainRepo("x/y")))
        Truth.assertThat(parseList("x/...")).containsExactly(Label.parseCanonical("//x/z"))

        setDeletedPackages(com.google.common.collect.ImmutableSet.of<PackageIdentifier?>())
        Truth.assertThat(parseList("x/..."))
            .containsExactly(Label.parseCanonical("//x/y"), Label.parseCanonical("//x/z"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSequenceOfTargetPatterns_union() {
        // No prefix negation operator => union.  Order is not significant.
        Truth.assertThat(parseList("foo/...", "foo/bar/...")).containsExactlyElementsIn(rulesBeneathFoo)
        Truth.assertThat(parseList("foo/bar/...", "foo/...")).containsExactlyElementsIn(rulesBeneathFoo)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSequenceOfTargetPatterns_setDifference() {
        // Prefix negation operator => set difference.  Order is significant.
        Truth.assertThat(parseList("foo/...", "-foo/bar/...")).containsExactlyElementsIn(rulesInFoo)
        Truth.assertThat(parseList("-foo/bar/...", "foo/...")).containsExactlyElementsIn(rulesBeneathFoo)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSequenceOfTargetPatterns_setDifferenceRelative() {
        // Prefix negation operator => set difference.  Order is significant.
        Truth.assertThat(parseListRelative("...", "-bar/...")).containsExactlyElementsIn(rulesInFoo)
        Truth.assertThat(parseListRelative("-bar/...", "...")).containsExactlyElementsIn(rulesBeneathFoo)
    }

    /** Regression test for bug: "Bogus 'helpful' error message"  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHelpfulMessageForDirectoryWhichIsASubdirectoryOfAPackage() {
        scratch.file("bar/BUILD")
        scratch.file("bar/quux/somefile")
        expectError(
            ("no such target '//bar:quux': target 'quux' not declared in package 'bar' defined by "
                    + "/workspace/bar/BUILD; however, a source directory of this name exists.  (Perhaps "
                    + "add 'exports_files([\"quux\"])' to bar/BUILD, or define a filegroup?)"),
            "bar/quux"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeepGoingPartiallyBadPackage() {
        scratch.file(
            "x/y/BUILD",
            """
        filegroup(name = "a")

        # dynamic error
        x = 1 // 0

        filegroup(name = "b")
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        val result: Pair<MutableSet<Label?>?, Boolean?> = parseListKeepGoing("//x/...")

        assertContainsEvent("division by zero")
        // Execution stops at the first error,
        // Subsequent rule statements are not executed,
        // But thanks to --keep_going, we learn about the ones before the error.
        assertThat(result.first).containsExactly(Label.parseCanonical("//x/y:a"))
        assertThat(result.second).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeepGoingMissingRecursiveDirectory() {
        assertKeepGoing(
            rulesBeneathFoo,
            "Skipping 'nosuchpkg/...': no targets found beneath 'nosuchpkg'",
            "nosuchpkg/...",
            "foo/..."
        )
        eventCollector.clear()
        assertKeepGoing(
            rulesBeneathFoo,
            "Skipping 'nosuchdirectory/...': no targets found beneath 'nosuchdirectory'",
            "nosuchdirectory/...",
            "foo/..."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeepGoingMissingTarget() {
        assertKeepGoing(
            rulesBeneathFoo,
            ("Skipping '//otherrules:missing_target': no such target "
                    + "'//otherrules:missing_target': target 'missing_target' not declared in "
                    + "package 'otherrules'"),
            "//otherrules:missing_target",
            "foo/..."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeepGoingOnAllRulesBeneath() {
        scratch.file("foo/bar/bad/BUILD", "invalid build file")

        reporter.removeHandler(failFastHandler)
        val result: Pair<MutableSet<Label?>?, Boolean?> = parseListKeepGoing("foo/...")
        assertThat(result.first).containsExactlyElementsIn(rulesBeneathFoo)
        assertContainsEvent("syntax error at 'build'")

        reporter.addHandler(failFastHandler)

        // Even though there was a loading error in the package, parsing the target pattern was
        // successful.
        assertThat(result.second).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noKeepGoingOnAllRulesBeneath() {
        scratch.file("foo/bar/bad/BUILD", "invalid build file")

        reporter.removeHandler(failFastHandler)
        val e: TargetParsingException =
            org.junit.Assert.assertThrows<T>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable { parseList("foo/...") })
        assertThat(e.getDetailedExitCode())
            .isEqualTo(
                DetailedExitCode.of(
                    FailureDetails.FailureDetail.newBuilder()
                        .setMessage(
                            "Error evaluating 'foo/...': error loading package 'foo/bar/bad': Package"
                                    + " 'foo/bar/bad' contains errors"
                        )
                        .setTargetPatterns(
                            FailureDetails.TargetPatterns.newBuilder()
                                .setCode(FailureDetails.TargetPatterns.Code.PACKAGE_NOT_FOUND)
                        )
                        .build()
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noKeepGoingOnAllRulesBeneathForMultipleBrokenPackages() {
        // This test uses two broken packages beneath the "foo/..." target pattern because the Skyframe
        // state for each package will be different during Skyframe error bubbling. Specifically, the
        // first broken package encountered during standard Skyframe evaluation will have an associated
        // PackageError node, but any subsequent broken package won't. This may affect error bubbling
        // behavior (and did, in b/211901614).
        scratch.file("foo/bar/bad1/BUILD", "invalid build file")
        scratch.file("foo/bar/bad2/BUILD", "invalid build file")

        reporter.removeHandler(failFastHandler)
        val e: TargetParsingException =
            org.junit.Assert.assertThrows<T>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable { parseList("foo/...") })
        assertThat(e.getDetailedExitCode())
            .isIn(
                com.google.common.collect.ImmutableList.of<Int?>(1, 2).stream()
                    .map<Any?> { i: Int? ->
                        DetailedExitCode.of(
                            FailureDetails.FailureDetail.newBuilder()
                                .setMessage(
                                    String.format(
                                        ("Error evaluating 'foo/...': error loading package"
                                                + " 'foo/bar/bad%d': Package 'foo/bar/bad%d' contains"
                                                + " errors"),
                                        i, i
                                    )
                                )
                                .setTargetPatterns(
                                    FailureDetails.TargetPatterns.newBuilder()
                                        .setCode(
                                            FailureDetails.TargetPatterns.Code.PACKAGE_NOT_FOUND
                                        )
                                )
                                .build()
                        )
                    }
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>()))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeepGoingBadFilenameTarget() {
        assertKeepGoing(
            rulesBeneathFoo,
            "no such target '//:bad/filename/target'",
            "bad/filename/target",
            "foo/..."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMoreThanOneBadPatternFailFast() {
        val e: TargetParsingException? =
            org.junit.Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    parseTargetPatternList(
                        parser,
                        parsingListener,
                        com.google.common.collect.ImmutableList.of<E?>(
                            "bad/filename/target",
                            "other/bad/filename/target"
                        ),  /* keepGoing= */
                        false
                    )
                })
        assertThat(e).hasMessageThat().contains("no such target")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMentioningBuildFile() {
        val result: ResolvedTargets<Target?> =
            parseTargetPatternList(
                parser,
                parsingListener,
                java.util.Arrays.< T > asList < T ? > ("foo/bar/BUILD"),
                false
            )

        assertThat(result.hasError()).isFalse()
        assertThat(result.getTargets()).hasSize(1)

        val label: Label = com.google.common.collect.Iterables.getOnlyElement<T?>(result.getTargets()).getLabel()
        assertThat(label.name).isEqualTo("BUILD")
        assertThat(label.getPackageName()).isEqualTo("foo/bar")
    }

    /**
     * Regression test for bug: '"Target pattern parsing failed. Continuing anyway" appears, even
     * without --keep_going'
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadingErrorsAreNotParsingErrors() {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "loading/BUILD",
            """
        fake_cc_library(
            name = "y",
            deps = ["a"],
        )

        fake_cc_library(
            name = "a",
            deps = ["b"],
        )

        fake_cc_library(
            name = "b",
            deps = ["c"],
        )

        genrule(
            name = "c",
            cmd = "",
        )
        
        """.trimIndent()
        )

        val result: Pair<MutableSet<Label?>?, Boolean?> = parseListKeepGoing("//loading:y")
        assertThat(result.first).containsExactly(Label.parseCanonical("//loading:y"))
        assertContainsEvent("missing value for mandatory attribute")
        assertThat(result.second).isFalse()
    }

    @Throws(java.lang.Exception::class)
    private fun assertKeepGoing(expectedLabels: MutableSet<Label?>?, expectedEvent: String?, vararg toParse: String?) {
        reporter.removeHandler(failFastHandler)
        Truth.assertThat(parseListKeepGoingExpectFailure(*toParse)).containsExactlyElementsIn(expectedLabels)
        assertContainsEvent(expectedEvent)
        reporter.addHandler(failFastHandler)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAddedPkg() {
        invalidate(ModifiedFileSet.EVERYTHING_MODIFIED)
        scratch.dir("h/i/j/k/BUILD")
        scratch.file("h/BUILD", "filegroup(name='h')")
        Truth.assertThat(parseList("//h/...")).containsExactlyElementsIn(labels("//h"))

        scratch.file("h/i/j/BUILD", "filegroup(name='j')")

        // Modifications not yet known.
        Truth.assertThat(parseList("//h/...")).containsExactlyElementsIn(labels("//h"))

        val modifiedFileSet: ModifiedFileSet? =
            ModifiedFileSet.builder().modify(PathFragment.create("h/i/j/BUILD")).build()
        invalidate(modifiedFileSet)

        Truth.assertThat(parseList("//h/..."))
            .containsExactly(Label.parseCanonical("//h/i/j:j"), Label.parseCanonical("//h"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAddedFilesAndDotDotDot() {
        invalidate(ModifiedFileSet.EVERYTHING_MODIFIED)
        reporter.removeHandler(failFastHandler)
        scratch.dir("h")
        org.junit.Assert.assertThrows<T?>(
            TargetParsingException::class.java,
            org.junit.function.ThrowingRunnable { parseList("//h/...") })

        scratch.file("h/i/j/k/BUILD", "filegroup(name='l')")
        val modifiedFileSet: ModifiedFileSet? =
            ModifiedFileSet.builder()
                .modify(PathFragment.create("h"))
                .modify(PathFragment.create("h/i"))
                .modify(PathFragment.create("h/i/j"))
                .modify(PathFragment.create("h/i/j/k"))
                .modify(PathFragment.create("h/i/j/k/BUILD"))
                .build()
        invalidate(modifiedFileSet)
        reporter.addHandler(failFastHandler)
        val nonEmptyResult: MutableSet<Label?> = parseList("//h/...")
        Truth.assertThat(nonEmptyResult).containsExactly(Label.parseCanonical("//h/i/j/k:l"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBrokenSymlinkRepaired() {
        reporter.removeHandler(failFastHandler)
        val tuv: Path = scratch.dir("t/u/v")
        tuv.getChild("BUILD").createSymbolicLink(PathFragment.create("../../BUILD"))

        org.junit.Assert.assertThrows<T?>(
            TargetParsingException::class.java,
            org.junit.function.ThrowingRunnable { parseList("//t/...") })

        scratch.file("t/BUILD", "filegroup(name='t')")
        val modifiedFileSet: ModifiedFileSet? =
            ModifiedFileSet.builder().modify(PathFragment.create("t/BUILD")).build()

        invalidate(modifiedFileSet)
        reporter.addHandler(failFastHandler)
        val result: MutableSet<Label?> = parseList("//t/...")

        Truth.assertThat(result)
            .containsExactly(Label.parseCanonical("//t:t"), Label.parseCanonical("//t/u/v:t"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInfiniteTreeFromSymlinks() {
        reporter.removeHandler(failFastHandler)
        val ab: Path = scratch.dir("a/b")
        ab.getChild("c").createSymbolicLink(PathFragment.create("../b"))
        scratch.file("a/b/BUILD", "filegroup(name='g')")
        val result: ResolvedTargets<Target?> =
            parseTargetPatternList(
                parser,
                parsingListener,
                com.google.common.collect.ImmutableList.of<E?>("//a/b/..."),
                true
            )
        assertThat(targetsToLabels(result.getTargets()))
            .containsExactly(Label.parseCanonical("//a/b:g"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinkCycle() {
        reporter.removeHandler(failFastHandler)
        val ab: Path = scratch.dir("a/b")
        ab.getChild("c").createSymbolicLink(PathFragment.create("c"))
        scratch.file("a/b/BUILD", "filegroup(name='g')")
        val result: ResolvedTargets<Target?> =
            parseTargetPatternList(
                parser,
                parsingListener,
                com.google.common.collect.ImmutableList.of<E?>("//a/b/..."),
                true
            )
        com.google.common.truth.Subject.contains(Label.parseCanonical("//a/b:g"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPerDirectorySymlinkTraversalOptOut() {
        scratch.dir("from-b")
        scratch.file("from-b/BUILD", "filegroup(name = 'from-b')")
        scratch.dir("from-c")
        scratch.file("from-c/BUILD", "filegroup(name = 'from-c')")
        val ab: Path = scratch.dir("a/b")
        ab.getChild("symlink").createSymbolicLink(PathFragment.create("../../from-b"))
        scratch.dir("a/b/not-a-symlink")
        scratch.file("a/b/not-a-symlink/BUILD", "filegroup(name = 'not-a-symlink')")
        scratch.file(
            "a/b/DONT_FOLLOW_SYMLINKS_WHEN_TRAVERSING_THIS_DIRECTORY_VIA_A_RECURSIVE_TARGET_PATTERN"
        )
        val ac: Path = scratch.dir("a/c")
        ac.getChild("symlink").createSymbolicLink(PathFragment.create("../../from-c"))
        val result: ResolvedTargets<Target?> =
            parseTargetPatternList(
                parser,
                parsingListener,
                com.google.common.collect.ImmutableList.of<E?>("//a/..."),
                true
            )
        assertThat(targetsToLabels(result.getTargets()))
            .containsExactly(
                Label.parseCanonical("//a/c/symlink:from-c"),
                Label.parseCanonical("//a/b/not-a-symlink:not-a-symlink")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoesNotRecurseIntoSymlinksToOutputBase() {
        val outputBaseBuildFile: Path = outputBase.getRelative("execroot/workspace/test/BUILD")
        scratch.file(outputBaseBuildFile.getPathString(), "filegroup(name='c')")
        val targetFragment: PathFragment? = outputBase.asFragment().getRelative("execroot/workspace/test")
        val d: Path = scratch.dir("d")
        d.getChild("c").createSymbolicLink(targetFragment)
        rootDirectory.getChild("convenience").createSymbolicLink(targetFragment)
        val result: MutableSet<Label?> = parseList("//...")
        Truth.assertThat(result).doesNotContain(Label.parseCanonical("//convenience:c"))
        Truth.assertThat(result).doesNotContain(Label.parseCanonical("//d/c:c"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelPackage_relative_buildFile() {
        val result: MutableSet<Label?> = parseList("BUILD")
        Truth.assertThat(result).containsExactly(Label.parseCanonical("//:BUILD"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelPackage_relative_declaredTarget() {
        val result: MutableSet<Label?> = parseList("fg")
        Truth.assertThat(result).containsExactly(Label.parseCanonical("//:fg"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelPackage_relative_all() {
        expectError("no such target '//:all'", "all")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelPackage_relative_colonAll() {
        val result: MutableSet<Label?> = parseList(":all")
        Truth.assertThat(result).containsExactly(Label.parseCanonical("//:fg"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelPackage_relative_inputFile() {
        val result: MutableSet<Label?> = parseList("foo.cc")
        Truth.assertThat(result).containsExactly(Label.parseCanonical("//:foo.cc"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelPackage_relative_inputFile_noSuchInputFile() {
        expectError("no such target '//:nope.cc'", "nope.cc")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelPackage_absolute_buildFile() {
        val result: MutableSet<Label?> = parseList("//:BUILD")
        Truth.assertThat(result).containsExactly(Label.parseCanonical("//:BUILD"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelPackage_absolute_declaredTarget() {
        val result: MutableSet<Label?> = parseList("//:fg")
        Truth.assertThat(result).containsExactly(Label.parseCanonical("//:fg"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelPackage_absolute_all() {
        val result: MutableSet<Label?> = parseList("//:all")
        Truth.assertThat(result).containsExactly(Label.parseCanonical("//:fg"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelPackage_absolute_inputFile() {
        val result: MutableSet<Label?> = parseList("//:foo.cc")
        Truth.assertThat(result).containsExactly(Label.parseCanonical("//:foo.cc"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelPackage_absolute_inputFile_noSuchInputFile() {
        expectError("no such target '//:nope.cc'", "//:nope.cc")
    }

    companion object {
        private val FAKE_CC_LIBRARY: RuleDefinition = MockRule {
            MockRule.define(
                "fake_cc_library",
                { builder, env ->
                    builder
                        .add(attr("srcs", LABEL_LIST).legacyAllowAnyFileType())
                        .add(attr("hdrs", LABEL_LIST).legacyAllowAnyFileType())
                        .add(attr("linkstatic", BOOLEAN))
                        .add(attr("alwayslink", BOOLEAN))
                })
        } as MockRule

        private fun getFailFast(result: ResolvedTargets<Target?>): MutableSet<Target?> {
            assertThat(result.hasError()).isFalse()
            return result.getTargets()
        }
    }
}
