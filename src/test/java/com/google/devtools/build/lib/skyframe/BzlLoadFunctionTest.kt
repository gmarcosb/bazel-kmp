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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey

/** Tests for BzlLoadFunction.  */
@RunWith(JUnit4::class)
class BzlLoadFunctionTest : BuildViewTestCase() {
    protected override fun createFileSystem(): FileSystem? {
        return com.google.devtools.build.lib.skyframe.BzlLoadFunctionTest.CustomInMemoryFs()
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun preparePackageLoading() {
        val alternativeRoot: Path? = scratch.dir("/root_2")
        val packageOptions: PackageOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(PackageOptions::class.java)
        packageOptions.setDefaultVisibility(RuleVisibility.PUBLIC)
        packageOptions.setShowLoadingProgress(true)
        packageOptions.setGlobbingThreads(7)
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(BuildLanguageOptions::class.java).build()
        parser.parse(TestConstants.PRODUCT_SPECIFIC_BUILD_LANG_OPTIONS)
        val options: BuildLanguageOptions? = parser.getOptions<O?>(BuildLanguageOptions::class.java)
        getSkyframeExecutor()
            .preparePackageLoading(
                PathPackageLocator(
                    outputBase,
                    com.google.common.collect.ImmutableList.of<E?>(
                        Root.fromPath(rootDirectory),
                        Root.fromPath(alternativeRoot)
                    ),
                    BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
                ),
                packageOptions,
                options,
                UUID.randomUUID(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                QuiescingExecutorsImpl.forTesting(),
                TimestampGranularityMonitor(com.google.devtools.build.lib.clock.BlazeClock.instance())
            )
        skyframeExecutor.setActionEnv(com.google.common.collect.ImmutableMap.of<K?, V?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlLoadLabels() {
        scratch.file("pkg1/BUILD")
        scratch.file("pkg1/ext.bzl")
        checkSuccessfulLookup("//pkg1:ext.bzl")

        scratch.file("pkg2/BUILD")
        scratch.file("pkg2/dir/ext.bzl")
        checkSuccessfulLookup("//pkg2:dir/ext.bzl")

        scratch.file("dir/pkg3/BUILD")
        scratch.file("dir/pkg3/dir/ext.bzl")
        checkSuccessfulLookup("//dir/pkg3:dir/ext.bzl")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlLoadLabelsAlternativeRoot() {
        scratch.file("/root_2/pkg4/BUILD")
        scratch.file("/root_2/pkg4/ext.bzl")
        checkSuccessfulLookup("//pkg4:ext.bzl")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlLoadLabelsMultipleBuildFiles() {
        scratch.file("dir1/BUILD")
        scratch.file("dir1/dir2/BUILD")
        scratch.file("dir1/dir2/ext.bzl")
        checkSuccessfulLookup("//dir1/dir2:ext.bzl")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadFromStarlarkFileInRemoteRepo() {
        scratch.overwriteFile(
            "MODULE.bazel",
            "bazel_dep(name = 'a_remote_repo')",
            "local_path_override(module_name = 'a_remote_repo', path = '/a_remote_repo')"
        )
        scratch.file("/a_remote_repo/MODULE.bazel", "module(name = 'a_remote_repo')")
        scratch.file("/a_remote_repo/remote_pkg/BUILD")
        scratch.file("/a_remote_repo/remote_pkg/ext1.bzl", "load(':ext2.bzl', 'CONST')")
        scratch.file("/a_remote_repo/remote_pkg/ext2.bzl", "CONST = 17")
        checkSuccessfulLookup("@@a_remote_repo+//remote_pkg:ext1.bzl")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadRelativeLabel() {
        scratch.file("pkg/BUILD")
        scratch.file("pkg/ext1.bzl", "a = 1")
        scratch.file("pkg/ext2.bzl", "load(':ext1.bzl', 'a')")
        checkSuccessfulLookup("//pkg:ext2.bzl")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadAbsoluteLabel() {
        scratch.file("pkg2/BUILD")
        scratch.file("pkg3/BUILD")
        scratch.file("pkg2/ext.bzl", "b = 1")
        scratch.file("pkg3/ext.bzl", "load('//pkg2:ext.bzl', 'b')")
        checkSuccessfulLookup("//pkg3:ext.bzl")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadFromSameAbsoluteLabelTwice() {
        scratch.file("pkg1/BUILD")
        scratch.file("pkg2/BUILD")
        scratch.file(
            "pkg1/ext.bzl",
            """
        a = 1
        b = 2
        
        """.trimIndent()
        )
        scratch.file(
            "pkg2/ext.bzl",
            """
        load("//pkg1:ext.bzl", "a", "b")
        
        """.trimIndent()
        )
        checkSuccessfulLookup("//pkg2:ext.bzl")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadFromSameRelativeLabelTwice() {
        scratch.file("pkg/BUILD")
        scratch.file(
            "pkg/ext1.bzl",
            """
        a = 1
        b = 2
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/ext2.bzl",
            """
        load(":ext1.bzl", "a", "b")
        
        """.trimIndent()
        )
        checkSuccessfulLookup("//pkg:ext2.bzl")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadFromRelativeLabelInSubdir() {
        scratch.file("pkg/BUILD")
        scratch.file("pkg/subdir/ext1.bzl", "a = 1")
        scratch.file("pkg/subdir/ext2.bzl", "load(':subdir/ext1.bzl', 'a')")
        checkSuccessfulLookup("//pkg:subdir/ext2.bzl")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadBadExtension_sclDisabled() {
        setBuildLanguageOptions("--experimental_enable_scl_dialect=false")

        scratch.file("pkg/BUILD")
        scratch.file("pkg/ext.bzl", "load(':foo.garbage', 'a')")
        reporter.removeHandler(failFastHandler)
        checkFailingLookup("//pkg:ext.bzl", "has invalid load statements")
        assertContainsEvent("The label must reference a file with extension \".bzl\"")
        assertDoesNotContainEvent(".scl")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadBadExtension_sclEnabled() {
        setBuildLanguageOptions("--experimental_enable_scl_dialect=true")

        scratch.file("pkg/BUILD")
        scratch.file("pkg/ext.bzl", "load(':foo.garbage', 'a')")
        reporter.removeHandler(failFastHandler)
        checkFailingLookup("//pkg:ext.bzl", "has invalid load statements")
        assertContainsEvent("The label must reference a file with extension \".bzl\" or \".scl\"")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadingSclRequiresExperimentalFlag() {
        setBuildLanguageOptions("--experimental_enable_scl_dialect=false")

        scratch.file("pkg/BUILD")
        scratch.file("pkg/ext.scl")
        reporter.removeHandler(failFastHandler)
        checkFailingLookup(
            "//pkg:ext.scl", "loading .scl files requires setting --experimental_enable_scl_dialect"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCanLoadScl() {
        setBuildLanguageOptions("--experimental_enable_scl_dialect=true")

        scratch.file("pkg/BUILD")
        scratch.file("pkg/ext.scl")
        checkSuccessfulLookup("//pkg:ext.scl")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCanLoadSclFromBzlAndScl() {
        setBuildLanguageOptions("--experimental_enable_scl_dialect=true")

        scratch.file("pkg/BUILD")
        scratch.file("pkg/ext1.scl", "a = 1")
        // Can use relative load label syntax from ext2a.bzl, but not from ext2b.scl.
        scratch.file("pkg/ext2a.bzl", "load(':ext1.scl', 'a')")
        scratch.file("pkg/ext2b.scl", "load('//pkg:ext1.scl', 'a')")

        checkSuccessfulLookup("//pkg:ext2a.bzl")
        checkSuccessfulLookup("//pkg:ext2b.scl")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSclCannotLoadNonSclFiles() {
        setBuildLanguageOptions("--experimental_enable_scl_dialect=true")

        scratch.file("pkg/BUILD")
        scratch.file("pkg/ext1a.bzl", "a = 1")
        scratch.file("pkg/ext1a.garbage", "a = 1")
        // Cannot use relative label.
        scratch.file("pkg/ext2a.scl", "load('//pkg:ext1a.bzl', 'a')")
        scratch.file("pkg/ext2b.scl", "load('//pkg:ext1b.garbage', 'a')")

        reporter.removeHandler(failFastHandler)
        checkFailingLookup("//pkg:ext2a.scl", "has invalid load statements")
        assertContainsEvent(
            "The label must reference a file with extension \".scl\" (.scl files cannot load .bzl"
                    + " files)"
        )
        eventCollector.clear()
        checkFailingLookup("//pkg:ext2b.scl", "has invalid load statements")
        assertContainsEvent("The label must reference a file with extension \".scl\"")
        assertDoesNotContainEvent(".bzl")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSclCanOnlyLoadLabelsRelativeToDefaultRepoRoot() {
        setBuildLanguageOptions("--experimental_enable_scl_dialect=true")

        scratch.file("pkg/BUILD")
        scratch.file("pkg/ext1.scl", "load(':foo.scl', 'a')")
        scratch.file("pkg/ext2.scl", "load('@repo//:foo.scl', 'a')")

        reporter.removeHandler(failFastHandler)
        checkFailingLookup("//pkg:ext1.scl", "has invalid load statements")
        assertContainsEvent("in .scl files, load labels must begin with \"//\"")
        eventCollector.clear()
        checkFailingLookup("//pkg:ext2.scl", "has invalid load statements")
        assertContainsEvent("in .scl files, load labels must begin with \"//\"")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSclSupportsStructAndVisibility() {
        setBuildLanguageOptions("--experimental_enable_scl_dialect=true")

        scratch.file("pkg/BUILD")
        scratch.file(
            "pkg/ext1.scl",  //
            "visibility('private')",
            "a = struct()"
        )
        scratch.file(
            "pkg/ext2.scl",  //
            "load('//pkg:ext1.scl', 'a')"
        )
        scratch.file("pkg2/BUILD")
        scratch.file(
            "pkg2/ext3.scl",  //
            "load('//pkg:ext1.scl', 'a')"
        )

        checkSuccessfulLookup("//pkg:ext2.scl")
        reporter.removeHandler(failFastHandler)
        checkFailingLookup(
            "//pkg2:ext3.scl", "module //pkg2:ext3.scl contains .bzl load visibility violations"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSclDoesNotSupportOtherBazelSymbols() {
        setBuildLanguageOptions("--experimental_enable_scl_dialect=true")

        scratch.file("pkg/BUILD")
        scratch.file(
            "pkg/ext.scl",  //
            "a = depset([])"
        )

        reporter.removeHandler(failFastHandler)
        checkFailingLookup("//pkg:ext.scl", "compilation of module 'pkg/ext.scl' failed")
        assertContainsEvent("name 'depset' is not defined")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSclDisallowsNonAsciiStringLiterals() {
        setBuildLanguageOptions("--experimental_enable_scl_dialect=true")

        scratch.file("pkg/BUILD")
        scratch.file(
            "pkg/ext1.bzl",  //
            "'x\u00ffz'"
        ) // xÿz
        scratch.file(
            "pkg/ext2.scl",  //
            "'x\u00ffz'"
        )

        checkSuccessfulLookup("//pkg:ext1.bzl")
        reporter.removeHandler(failFastHandler)
        checkFailingLookup("//pkg:ext2.scl", "compilation of module 'pkg/ext2.scl' failed")
        assertContainsEvent("string literal contains non-ASCII character")
    }

    @Throws(java.lang.Exception::class)
    private fun get(skyKey: SkyKey?): EvaluationResult<BzlLoadValue?> {
        val result: EvaluationResult<BzlLoadValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), skyKey,  /*keepGoing=*/false, reporter
            )
        if (result.hasError()) {
            org.junit.Assert.fail(result.getError(skyKey).getException().getMessage())
        }
        return result
    }

    /** Loads a .bzl with the given label and asserts success.  */
    @Throws(java.lang.Exception::class)
    private fun checkSuccessfulLookup(label: String?) {
        val skyKey: SkyKey = key(label)
        val result: EvaluationResult<BzlLoadValue?> = get(skyKey)
        // Ensure that the file has been processed by checking its Module for the label field.
        assertThat(Label.parseCanonicalUnchecked(label))
            .isEqualTo(BazelModuleContext.of(result.get(skyKey).getModule()).label())
    }

    /* Loads a .bzl with the given label and asserts BzlLoadFailedException with the given message. */
    @Throws(java.lang.InterruptedException::class)
    private fun checkFailingLookup(label: String?, expectedMessage: String?) {
        val skyKey: SkyKey = key(label)
        val result: EvaluationResult<BzlLoadValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), skyKey,  /*keepGoing=*/false, reporter
            )
        assertThat(result.hasError()).isTrue()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(skyKey)
            .hasExceptionThat()
            .isInstanceOf(BzlLoadFailedException::class.java)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(skyKey)
            .hasExceptionThat()
            .hasMessageThat()
            .contains(expectedMessage)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlLoadNoBuildFile() {
        scratch.file("pkg/ext.bzl", "")
        val skyKey: SkyKey = key("//pkg:ext.bzl")
        val result: EvaluationResult<BzlLoadValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), skyKey,  /*keepGoing=*/false, reporter
            )
        assertThat(result.hasError()).isTrue()
        val errorInfo: ErrorInfo = result.getError(skyKey)
        val errorMessage: String? = errorInfo.getException().getMessage()
        Truth.assertThat(errorMessage)
            .contains(
                "Every .bzl file must have a corresponding package, but '//pkg:ext.bzl' does not"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlLoadNoBuildFileForLoad() {
        scratch.file("pkg2/BUILD")
        scratch.file("pkg1/ext.bzl", "a = 1")
        scratch.file("pkg2/ext.bzl", "load('//pkg1:ext.bzl', 'a')")
        val skyKey: SkyKey = key("//pkg:ext.bzl")
        val result: EvaluationResult<BzlLoadValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), skyKey,  /*keepGoing=*/false, reporter
            )
        assertThat(result.hasError()).isTrue()
        val errorInfo: ErrorInfo = result.getError(skyKey)
        val errorMessage: String? = errorInfo.getException().getMessage()
        Truth.assertThat(errorMessage).contains("Every .bzl file must have a corresponding package")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlLoadFilenameWithControlChars() {
        scratch.file("pkg/BUILD", "")
        scratch.file("pkg/ext.bzl", "load('//pkg:oops\u0000.bzl', 'a')")
        val skyKey: SkyKey = key("//pkg:ext.bzl")
        val e: java.lang.AssertionError =
            org.junit.Assert.assertThrows<java.lang.AssertionError>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable {
                    SkyframeExecutorTestUtils.evaluate<T?>(
                        getSkyframeExecutor(), skyKey,  /*keepGoing=*/false, reporter
                    )
                })
        val errorMessage: String? = e.message
        Truth.assertThat(errorMessage)
            .contains(
                "invalid target name 'oops<?>.bzl': "
                        + "target names may not contain non-printable characters: '\\x00'"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadFromSubdirInSamePackageIsOk() {
        scratch.file("a/BUILD")
        scratch.file("a/a.bzl", "load('//a:b/b.bzl', 'b')")
        scratch.file("a/b/b.bzl", "b = 42")

        checkSuccessfulLookup("//a:a.bzl")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadMustRespectPackageBoundary_ofSubpkg() {
        scratch.file("a/BUILD")
        scratch.file("a/a.bzl", "load('//a:b/b.bzl', 'b')")
        scratch.file("a/b/BUILD", "")
        scratch.file("a/b/b.bzl", "b = 42")
        checkFailingLookup(
            "//a:a.bzl",
            "Label '//a:b/b.bzl' is invalid because 'a/b' is a subpackage; perhaps you meant to"
                    + " put the colon here: '//a/b:b.bzl'?"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadMustRespectPackageBoundary_ofSubpkg_relative() {
        scratch.file("a/BUILD")
        scratch.file("a/a.bzl", "load('b/b.bzl', 'b')")
        scratch.file("a/b/BUILD", "")
        scratch.file("a/b/b.bzl", "b = 42")
        checkFailingLookup(
            "//a:a.bzl",
            "Label '//a:b/b.bzl' is invalid because 'a/b' is a subpackage; perhaps you meant to"
                    + " put the colon here: '//a/b:b.bzl'?"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadMustRespectPackageBoundary_ofIndirectSubpkg() {
        scratch.file("a/BUILD")
        scratch.file("a/a.bzl", "load('//a/b:c/c.bzl', 'c')")
        scratch.file("a/b/BUILD", "")
        scratch.file("a/b/c/BUILD", "")
        scratch.file("a/b/c/c.bzl", "c = 42")
        checkFailingLookup(
            "//a:a.bzl",
            "Label '//a/b:c/c.bzl' is invalid because 'a/b/c' is a subpackage; perhaps you meant"
                    + " to put the colon here: '//a/b/c:c.bzl'?"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadMustRespectPackageBoundary_ofParentPkg() {
        scratch.file("a/b/BUILD")
        scratch.file("a/b/b.bzl", "load('//a/c:c/c.bzl', 'c')")
        scratch.file("a/BUILD")
        scratch.file("a/c/c/c.bzl", "c = 42")
        checkFailingLookup(
            "//a/b:b.bzl",
            "Label '//a/c:c/c.bzl' is invalid because 'a/c' is not a package; perhaps you meant to "
                    + "put the colon here: '//a:c/c/c.bzl'?"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlVisibility_disabledWithoutFlag() {
        setBuildLanguageOptions("--experimental_bzl_visibility=false")

        scratch.file("a/BUILD")
        scratch.file(
            "a/foo.bzl",  //
            "load(\"//b:bar.bzl\", \"x\")"
        )
        scratch.file("b/BUILD")
        scratch.file(
            "b/bar.bzl",
            """
        visibility("private")
        x = 1
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        checkFailingLookup("//a:foo.bzl", "initialization of module 'b/bar.bzl' failed")
        assertContainsEvent("Use of `visibility()` requires --experimental_bzl_visibility")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlVisibility_publicExplicit() {
        setBuildLanguageOptions("--experimental_bzl_visibility=true")

        scratch.file("a/BUILD")
        scratch.file(
            "a/foo.bzl",  //
            "load(\"//b:bar.bzl\", \"x\")"
        )
        scratch.file("b/BUILD")
        scratch.file(
            "b/bar.bzl",
            """
        visibility("public")
        x = 1
        
        """.trimIndent()
        )

        checkSuccessfulLookup("//a:foo.bzl")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlVisibility_publicImplicit() {
        setBuildLanguageOptions("--experimental_bzl_visibility=true")

        scratch.file("a/BUILD")
        scratch.file(
            "a/foo.bzl",  //
            "load(\"//b:bar.bzl\", \"x\")"
        )
        scratch.file("b/BUILD")
        scratch.file(
            "b/bar.bzl",  // No visibility() declaration, defaults to public.
            "x = 1"
        )

        checkSuccessfulLookup("//a:foo.bzl")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlVisibility_privateSamePackage() {
        setBuildLanguageOptions("--experimental_bzl_visibility=true")

        scratch.file("a/BUILD")
        scratch.file(
            "a/foo.bzl",  //
            "load(\"//a:bar.bzl\", \"x\")"
        )
        scratch.file(
            "a/bar.bzl",
            """
        visibility("private")
        x = 1
        
        """.trimIndent()
        )

        checkSuccessfulLookup("//a:foo.bzl")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlVisibility_privateDifferentPackage() {
        setBuildLanguageOptions("--experimental_bzl_visibility=true")

        scratch.file("a/BUILD")
        scratch.file(
            "a/foo.bzl",  //
            "load(\"//b:bar.bzl\", \"x\")"
        )
        scratch.file("b/BUILD")
        scratch.file(
            "b/bar.bzl",
            """
        visibility("private")
        x = 1
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        checkFailingLookup(
            "//a:foo.bzl", "module //a:foo.bzl contains .bzl load visibility violations"
        )
        assertContainsEvent("Starlark file //b:bar.bzl is not visible for loading from package //a.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlVisibility_emptyListMeansPrivate() {
        setBuildLanguageOptions("--experimental_bzl_visibility=true")

        scratch.file("a/BUILD")
        scratch.file(
            "a/foo.bzl",  //
            "load(\"//b:bar.bzl\", \"x\")"
        )
        scratch.file("b/BUILD")
        scratch.file(
            "b/bar.bzl",
            """
        visibility([])
        x = 1
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        checkFailingLookup(
            "//a:foo.bzl", "module //a:foo.bzl contains .bzl load visibility violations"
        )
        assertContainsEvent("Starlark file //b:bar.bzl is not visible for loading from package //a.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlVisibility_publicListElement() {
        setBuildLanguageOptions("--experimental_bzl_visibility=true")

        scratch.file("a/BUILD")
        scratch.file(
            "a/foo.bzl",  //
            "load(\"//b:bar.bzl\", \"x\")"
        )
        scratch.file("b/BUILD")
        scratch.file(
            "b/bar.bzl",
            """
        # Tests "public" as a list item, and alongside other list items.
        visibility(["public", "//c"])
        x = 1
        
        """.trimIndent()
        )

        checkSuccessfulLookup("//a:foo.bzl")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlVisibility_privateListElement() {
        setBuildLanguageOptions("--experimental_bzl_visibility=true")

        scratch.file("a1/BUILD")
        scratch.file(
            "a1/foo.bzl",  //
            "load(\"//b:bar.bzl\", \"x\")"
        )
        scratch.file("a2/BUILD")
        scratch.file(
            "a2/foo.bzl",  //
            "load(\"//b:bar.bzl\", \"x\")"
        )
        scratch.file("b/BUILD")
        scratch.file(
            "b/bar.bzl",
            """
        # Tests "private" as a list item, and alongside other list items.
        visibility(["private", "//a1"])
        x = 1
        
        """.trimIndent()
        )

        checkSuccessfulLookup("//a1:foo.bzl")
        assertNoEvents()
        reporter.removeHandler(failFastHandler)
        checkFailingLookup(
            "//a2:foo.bzl", "module //a2:foo.bzl contains .bzl load visibility violations"
        )
        assertContainsEvent("Starlark file //b:bar.bzl is not visible for loading from package //a2.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlVisibility_failureInDependency() {
        setBuildLanguageOptions("--experimental_bzl_visibility=true")

        scratch.file("a/BUILD")
        scratch.file(
            "a/foo.bzl",  //
            "load(\"//b:bar.bzl\", \"x\")"
        )
        scratch.file("b/BUILD")
        scratch.file(
            "b/bar.bzl",
            """
        load("//c:baz.bzl", "y")

        visibility("public")
        x = y
        
        """.trimIndent()
        )
        scratch.file("c/BUILD")
        scratch.file(
            "c/baz.bzl",
            """
        visibility("private")
        y = 1
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        checkFailingLookup(
            "//a:foo.bzl",
            "at /workspace/a/foo.bzl:1:6: module //b:bar.bzl contains .bzl load visibility violations"
        )
        assertContainsEvent("Starlark file //c:baz.bzl is not visible for loading from package //b.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlVisibility_cannotBeSetInFunction() {
        setBuildLanguageOptions("--experimental_bzl_visibility=true")

        scratch.file("a/BUILD")
        scratch.file(
            "a/foo.bzl",
            """
        def helper():
            visibility("public")

        helper()
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        checkFailingLookup("//a:foo.bzl", "initialization of module 'a/foo.bzl' failed")
        assertContainsEvent("load visibility may only be set at the top level")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlVisibility_cannotBeSetTwice() {
        setBuildLanguageOptions("--experimental_bzl_visibility=true")

        scratch.file("a/BUILD")
        scratch.file(
            "a/foo.bzl",
            """
        visibility("public")
        visibility("public")
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        checkFailingLookup("//a:foo.bzl", "initialization of module 'a/foo.bzl' failed")
        assertContainsEvent("load visibility may not be set more than once")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlVisibility_enumeratedPackages() {
        setBuildLanguageOptions("--experimental_bzl_visibility=true")

        scratch.file("a1/BUILD")
        scratch.file(
            "a1/foo1.bzl",  //
            "load(\"//b:bar.bzl\", \"x\")"
        )
        scratch.file("a2/BUILD")
        scratch.file(
            "a2/foo2.bzl",  //
            "load(\"//b:bar.bzl\", \"x\")"
        )
        scratch.file("b/BUILD")
        scratch.file(
            "b/bar.bzl",
            """
        visibility(["//a1"])
        x = 1
        
        """.trimIndent()
        )

        checkSuccessfulLookup("//a1:foo1.bzl")
        assertNoEvents()

        reporter.removeHandler(failFastHandler)
        checkFailingLookup(
            "//a2:foo2.bzl", "module //a2:foo2.bzl contains .bzl load visibility violations"
        )
        assertContainsEvent("Starlark file //b:bar.bzl is not visible for loading from package //a2.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlVisibility_singleEnumeratedPackageAsString() {
        setBuildLanguageOptions("--experimental_bzl_visibility=true")

        scratch.file("a1/BUILD")
        scratch.file(
            "a1/foo1.bzl",  //
            "load(\"//b:bar.bzl\", \"x\")"
        )
        scratch.file("a2/BUILD")
        scratch.file(
            "a2/foo2.bzl",  //
            "load(\"//b:bar.bzl\", \"x\")"
        )
        scratch.file("b/BUILD")
        scratch.file(
            "b/bar.bzl",
            """
        # Note: "//a1", not ["//a1"]
        visibility("//a1")
        x = 1
        
        """.trimIndent()
        )

        checkSuccessfulLookup("//a1:foo1.bzl")
        assertNoEvents()

        reporter.removeHandler(failFastHandler)
        checkFailingLookup(
            "//a2:foo2.bzl", "module //a2:foo2.bzl contains .bzl load visibility violations"
        )
        assertContainsEvent("Starlark file //b:bar.bzl is not visible for loading from package //a2.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlVisibility_enumeratedPackagesMultipleRepos() {
        setBuildLanguageOptions("--experimental_bzl_visibility=true")

        // @repo//pkg:foo1.bzl and @//pkg:foo2.bzl both try to access @repo//lib:bar.bzl. Test that when
        // bar.bzl declares a visibility allowing "//pkg", it means @repo//pkg and *not* @//pkg.
        scratch.overwriteFile(
            "MODULE.bazel",  //
            "bazel_dep(name = 'repo')",
            "local_path_override(module_name = 'repo', path = 'repo')"
        )
        scratch.file("repo/MODULE.bazel", "module(name = 'repo')")
        scratch.file("repo/pkg/BUILD")
        scratch.file(
            "repo/pkg/foo1.bzl",  //
            "load(\"//lib:bar.bzl\", \"x\")"
        )
        scratch.file("repo/lib/BUILD")
        scratch.file(
            "repo/lib/bar.bzl",
            """
        visibility(["//pkg"])
        x = 1
        
        """.trimIndent()
        )
        scratch.file("pkg/BUILD")
        scratch.file(
            "pkg/foo2.bzl",  //
            "load(\"@repo//lib:bar.bzl\", \"x\")"
        )

        checkSuccessfulLookup("@@repo+//pkg:foo1.bzl")
        assertNoEvents()

        reporter.removeHandler(failFastHandler)
        checkFailingLookup(
            "//pkg:foo2.bzl", "module //pkg:foo2.bzl contains .bzl load visibility violations"
        )
        assertContainsEvent(
            "Starlark file @@repo+//lib:bar.bzl is not visible for loading from package //pkg."
        )
    }

    // TODO(#16365): This test case can be deleted once --incompatible_package_group_has_public_syntax
    // is deleted (not just flipped).
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlVisibility_canUsePublicPrivate_regardlessOfFlag() {
        setBuildLanguageOptions(
            "--experimental_bzl_visibility=true",  // Test that we can use "public" and "private" visibility for .bzl files even when the
            // incompatible flag is disabled.
            "--incompatible_package_group_has_public_syntax=false"
        )

        scratch.file("a/BUILD")
        scratch.file(
            "a/foo1.bzl",  //
            "visibility(\"public\")"
        )
        scratch.file(
            "a/foo2.bzl",  //
            "visibility(\"private\")"
        )

        checkSuccessfulLookup("//a:foo1.bzl")
        checkSuccessfulLookup("//a:foo2.bzl")
        assertNoEvents()
    }

    // TODO(#16324): Once --incompatible_fix_package_group_reporoot_syntax is deleted (not just
    // flipped), this test case will be redundant with tests for //... in PackageGroupTest. At that
    // point we'll just delete this test case.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlVisibility_repoRootSubpackagesIsNotPublic_regardlessOfFlag() {
        setBuildLanguageOptions(
            "--experimental_bzl_visibility=true",  // Test that we get the fixed behavior even when the incompatible flag is disabled.
            "--incompatible_fix_package_group_reporoot_syntax=false"
        )

        scratch.overwriteFile(
            "MODULE.bazel",  //
            "bazel_dep(name = 'repo')",
            "local_path_override(module_name = 'repo', path = 'repo')"
        )
        scratch.file("repo/MODULE.bazel", "module(name = 'repo')")
        scratch.file("repo/a/BUILD")
        scratch.file(
            "repo/a/foo.bzl",  //
            "load(\"@@//b:bar.bzl\", \"x\")"
        )
        scratch.file("b/BUILD")
        scratch.file(
            "b/bar.bzl",
            """
        visibility(["//..."])
        x = 1
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        checkFailingLookup(
            "@@repo+//a:foo.bzl", "module @@repo+//a:foo.bzl contains .bzl load visibility violations"
        )
        assertContainsEvent(
            "Starlark file //b:bar.bzl is not visible for loading from package @@repo+//a."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlVisibility_disallowsSubpackagesWithoutWildcard() {
        setBuildLanguageOptions("--experimental_bzl_visibility=true")

        scratch.file("a/BUILD")
        scratch.file(
            "a/foo1.bzl",  //
            "load(\"//b:bar.bzl\", \"x\")"
        )
        scratch.file("a/subpkg/BUILD")
        scratch.file(
            "a/subpkg/foo2.bzl",  //
            "load(\"//b:bar.bzl\", \"x\")"
        )
        scratch.file("b/BUILD")
        scratch.file(
            "b/bar.bzl",
            """
        visibility(["//a"])
        x = 1
        
        """.trimIndent()
        )

        checkSuccessfulLookup("//a:foo1.bzl")
        assertNoEvents()

        reporter.removeHandler(failFastHandler)
        checkFailingLookup(
            "//a/subpkg:foo2.bzl",
            "module //a/subpkg:foo2.bzl contains .bzl load visibility violations"
        )
        assertContainsEvent(
            "Starlark file //b:bar.bzl is not visible for loading from package //a/subpkg."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlVisibility_allowsSubpackagesWithWildcard() {
        setBuildLanguageOptions("--experimental_bzl_visibility=true")

        scratch.file("a/BUILD")
        scratch.file(
            "a/foo1.bzl",  //
            "load(\"//b:bar.bzl\", \"x\")"
        )
        scratch.file("a/subpkg/BUILD")
        scratch.file(
            "a/subpkg/foo2.bzl",  //
            "load(\"//b:bar.bzl\", \"x\")"
        )
        scratch.file("b/BUILD")
        scratch.file(
            "b/bar.bzl",
            """
        visibility(["//a/..."])
        x = 1
        
        """.trimIndent()
        )

        checkSuccessfulLookup("//a:foo1.bzl")
        assertNoEvents()

        checkSuccessfulLookup("//a/subpkg:foo2.bzl")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlVisibility_invalid_badType() {
        setBuildLanguageOptions("--experimental_bzl_visibility=true")

        scratch.file("a/BUILD")
        scratch.file(
            "a/foo.bzl",  //
            "visibility(123)"
        )

        reporter.removeHandler(failFastHandler)
        checkFailingLookup("//a:foo.bzl", "initialization of module 'a/foo.bzl' failed")
        assertContainsEvent("Invalid visibility: got 'int', want string or list of strings")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlVisibility_invalid_badElementType() {
        setBuildLanguageOptions("--experimental_bzl_visibility=true")

        scratch.file("a/BUILD")
        scratch.file(
            "a/foo.bzl",  //
            "visibility([\"//a\", 123])"
        )

        reporter.removeHandler(failFastHandler)
        checkFailingLookup("//a:foo.bzl", "initialization of module 'a/foo.bzl' failed")
        assertContainsEvent("at index 1 of visibility list, got element of type int, want string")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlVisibility_packageWithRepoWorks() {
        setBuildLanguageOptions("--experimental_bzl_visibility=true")

        scratch.file("a/BUILD")
        scratch.file(
            "a/foo.bzl",  //
            "visibility([\"@repo//b\"])"
        )

        checkSuccessfulLookup("//a:foo.bzl")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlVisibility_invalid_negationNotSupported() {
        setBuildLanguageOptions("--experimental_bzl_visibility=true")

        scratch.file("a/BUILD")
        scratch.file(
            "a/foo.bzl",  //
            "visibility([\"-//a\"])"
        )

        reporter.removeHandler(failFastHandler)
        checkFailingLookup("//a:foo.bzl", "initialization of module 'a/foo.bzl' failed")
        assertContainsEvent("Cannot use negative package patterns here")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlVisibility_errorsDemotedToWarningWhenBreakGlassFlagIsSet() {
        setBuildLanguageOptions("--experimental_bzl_visibility=true", "--check_bzl_visibility=false")

        scratch.file("a/BUILD")
        scratch.file(
            "a/foo.bzl",  //
            "load(\"//b:bar.bzl\", \"x\")"
        )
        scratch.file("b/BUILD")
        scratch.file(
            "b/bar.bzl",
            """
        visibility("private")
        x = 1
        
        """.trimIndent()
        )

        checkSuccessfulLookup("//a:foo.bzl")
        assertContainsEvent("Starlark file //b:bar.bzl is not visible for loading from package //a.")
        assertContainsEvent("Continuing because --nocheck_bzl_visibility is active")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadFromNonExistentRepository_producesMeaningfulError() {
        scratch.file("BUILD", "load(\"@repository//dir:file.bzl\", \"foo\")")

        val skyKey: SkyKey = key("@repository//dir:file.bzl")
        val result: EvaluationResult<BzlLoadValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), skyKey,  /*keepGoing=*/false, reporter
            )
        assertThat(result.hasError()).isTrue()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(skyKey)
            .hasExceptionThat()
            .isInstanceOf(BzlLoadFailedException::class.java)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(skyKey)
            .hasExceptionThat()
            .hasMessageThat()
            .contains(
                "Unable to find package for @@repository//dir:file.bzl: The repository '@@repository' "
                        + "could not be resolved: Repository '@@repository' is not defined."
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadBzlFileFromBzlmod() {
        setBuildLanguageOptions("--experimental_enable_scl_dialect")
        scratch.overwriteFile("MODULE.bazel", "bazel_dep(name='foo',version='1.0')")
        registry
            .addModule(
                createModuleKey("foo", "1.0"),
                "module(name='foo',version='1.0')",
                "bazel_dep(name='bar',version='2.0',repo_name='bar_alias')"
            )
            .addModule(createModuleKey("bar", "2.0"), "module(name='bar',version='2.0')")
        val fooDir: Path = moduleRoot.getRelative("foo+1.0")
        scratch.file(fooDir.getRelative("REPO.bazel").getPathString())
        scratch.file(fooDir.getRelative("BUILD").getPathString())
        scratch.file(
            fooDir.getRelative("test.bzl").getPathString(),  // Also test that bzlmod .bzl files can load .scl files.
            "load('@bar_alias//:test.scl', 'haha')",
            "l = Label('@foo//:whatever')",
            "hoho = haha"
        )
        val barDir: Path = moduleRoot.getRelative("bar+2.0")
        scratch.file(barDir.getRelative("REPO.bazel").getPathString())
        scratch.file(barDir.getRelative("BUILD").getPathString())
        scratch.file(barDir.getRelative("test.scl").getPathString(), "haha = 5")

        val skyKey: SkyKey? = BzlLoadValue.keyForBzlmod(Label.parseCanonical("@@foo+//:test.bzl"))
        val result: EvaluationResult<BzlLoadValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), skyKey,  /*keepGoing=*/false, reporter
            )

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val bzlLoadValue: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            result.get(skyKey)
        assertThat(bzlLoadValue.getModule().getGlobals()).containsEntry("hoho", StarlarkInt.of(5))
        assertThat(bzlLoadValue.recordedRepoMappings.cellSet())
            .containsExactly(
                com.google.common.collect.Tables.immutableCell<R?, C?, V?>(
                    RepositoryName.create("foo+"), "bar_alias", RepositoryName.create("bar+")
                ),
                com.google.common.collect.Tables.immutableCell<R?, C?, V?>(
                    RepositoryName.create("foo+"), "foo", RepositoryName.create("foo+")
                )
            )
            .inOrder()
        // Note that we're not testing the case of a non-registry override using @bazel_tools here, but
        // that is incredibly hard to set up in a unit test. So we should just rely on integration tests
        // for that.
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuiltinsInjectionFailure() {
        setBuildLanguageOptions("--experimental_builtins_bzl_path=tools/builtins_staging")
        scratch.file(
            "tools/builtins_staging/exports.bzl",
            """
        1 // 0  # <-- dynamic error
        exported_toplevels = {}
        exported_rules = {}
        exported_to_java = {}
        
        """.trimIndent()
        )
        scratch.file("pkg/BUILD")
        scratch.file("pkg/foo.bzl")
        reporter.removeHandler(failFastHandler)

        val key: SkyKey = key("//pkg:foo.bzl")
        val result: EvaluationResult<BzlLoadValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), key,  /*keepGoing=*/false, reporter
            )

        assertContainsEvent(
            "File \"/workspace/tools/builtins_staging/exports.bzl\", line 1, column 3, in <toplevel>"
        )
        assertContainsEvent("Error: integer division by zero")
        val ex: java.lang.Exception? = result.getError(key).getException()
        Truth.assertThat(ex)
            .hasMessageThat()
            .contains(
                "Internal error while loading Starlark builtins for //pkg:foo.bzl: Failed to load"
                        + " builtins sources: initialization of module 'exports.bzl' (internal) failed"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testErrorReadingBzlFileIsTransientWhenUsingASTInlining() {
        val fs = fileSystem as CustomInMemoryFs
        scratch.file("a/BUILD")
        fs.badPathForRead = scratch.file("a/a1.bzl", "doesntmatter")

        val key: SkyKey = key("//a:a1.bzl")
        val result: EvaluationResult<BzlLoadValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), key,  /*keepGoing=*/false, reporter
            )
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasErrorEntryForKeyThat(key).isTransient()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testErrorReadingOtherBzlFileIsPersistentFromPerspectiveOfParent() {
        val fs = fileSystem as CustomInMemoryFs
        scratch.file("a/BUILD")
        scratch.file("a/a1.bzl", "load('//a:a2.bzl', 'a2')")
        fs.badPathForRead = scratch.file("a/a2.bzl", "doesntmatter")

        val key: SkyKey = key("//a:a1.bzl")
        val result: EvaluationResult<BzlLoadValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), key,  /*keepGoing=*/false, reporter
            )
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasErrorEntryForKeyThat(key).isNotTransient()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testErrorStatingBzlFileInFileStateFunctionIsPersistent() {
        val fs = fileSystem as CustomInMemoryFs
        scratch.file("a/BUILD")
        fs.badPathForStat = scratch.file("a/a1.bzl", "doesntmatter")

        val key: SkyKey = key("//a:a1.bzl")
        val result: EvaluationResult<BzlLoadValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), key,  /*keepGoing=*/false, reporter
            )
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasErrorEntryForKeyThat(key).isNotTransient()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTypeTagger_notRunByDefault() {
        setBuildLanguageOptions("--experimental_starlark_type_syntax")
        scratch.file("a/BUILD")
        scratch.file("a/foo.bzl", "x: NoSuchType = [1, 2, 3]")

        val key: SkyKey = key("//a:foo.bzl")
        val result: EvaluationResult<BzlLoadValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), key,  /* keepGoing= */false, reporter
            )
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTypeTagger_detectsErrors_ifDynamicTypeCheckingEnabled() {
        setBuildLanguageOptions(
            "--experimental_starlark_type_syntax", "--experimental_starlark_dynamic_type_checking"
        )
        scratch.file("a/BUILD")
        scratch.file("a/foo.bzl", "x: NoSuchType = [1, 2, 3]")
        val key: SkyKey = key("//a:foo.bzl")
        reporter.removeHandler(failFastHandler)

        SkyframeExecutorTestUtils.evaluate<T?>(
            getSkyframeExecutor(), key,  /* keepGoing= */false, reporter
        )
        assertContainsEvent("name 'NoSuchType' is not defined")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTypeTagger_detectsErrors_ifStaticTypeCheckingEnabled() {
        setBuildLanguageOptions(
            "--experimental_starlark_type_syntax", "--experimental_starlark_static_type_checking"
        )
        scratch.file("a/BUILD")
        scratch.file("a/foo.bzl", "x: NoSuchType = [1, 2, 3]")
        val key: SkyKey = key("//a:foo.bzl")
        reporter.removeHandler(failFastHandler)

        SkyframeExecutorTestUtils.evaluate<T?>(
            getSkyframeExecutor(), key,  /* keepGoing= */false, reporter
        )
        assertContainsEvent("name 'NoSuchType' is not defined")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStaticTypeChecker_basicUsage() {
        setBuildLanguageOptions(
            "--experimental_starlark_type_syntax", "--experimental_starlark_static_type_checking"
        )
        scratch.file("a/BUILD")
        scratch.file("a/foo.bzl", "x: list[int]|list[str] = [1, 2, 3]")
        val key: SkyKey = key("//a:foo.bzl")

        val result: EvaluationResult<BzlLoadValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), key,  /* keepGoing= */false, reporter
            )
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        assertThat(result.get(key).getModule().getExportType("x"))
            .isEqualTo(
                net.starlark.java.syntax.Types.union(
                    net.starlark.java.syntax.Types.list(net.starlark.java.syntax.Types.INT),
                    net.starlark.java.syntax.Types.list(net.starlark.java.syntax.Types.STR)
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStaticTypeChecker_transitiveDeps() {
        setBuildLanguageOptions(
            "--experimental_starlark_type_syntax", "--experimental_starlark_static_type_checking"
        )
        scratch.file("a/BUILD")
        scratch.file("a/foo.bzl", "x: list[int] | list[str] = [1, 2, 3]")
        scratch.file(
            "a/bar.bzl",
            """
        load(":foo.bzl", "x")
        y: list[int] = x
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)

        val key: SkyKey = key("//a:bar.bzl")
        SkyframeExecutorTestUtils.evaluate<T?>(
            getSkyframeExecutor(), key,  /* keepGoing= */false, reporter
        )
        assertContainsEvent("cannot assign type 'list[int]|list[str]' to 'y' of type 'list[int]'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStaticTypeChecker_notRunByDefault() {
        setBuildLanguageOptions(
            "--experimental_starlark_type_syntax", "--experimental_starlark_dynamic_type_checking"
        )
        scratch.file("a/BUILD")
        scratch.file("a/foo.bzl", "x: list[int] = ['a', 'b', 'c']")

        val key: SkyKey = key("//a:foo.bzl")
        val result: EvaluationResult<BzlLoadValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), key,  /* keepGoing= */false, reporter
            )
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStaticTypeChecker_detectsErrors() {
        setBuildLanguageOptions(
            "--experimental_starlark_type_syntax", "--experimental_starlark_static_type_checking"
        )
        scratch.file("a/BUILD")
        scratch.file("a/foo.bzl", "x: list[int] = ['a', 'b', 'c']")
        reporter.removeHandler(failFastHandler)

        val key: SkyKey = key("//a:foo.bzl")
        SkyframeExecutorTestUtils.evaluate<T?>(
            getSkyframeExecutor(), key,  /* keepGoing= */false, reporter
        )
        assertContainsEvent("cannot assign type 'list[str]' to 'x' of type 'list[int]'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDynamicTypeChecker_detectsErrors() {
        setBuildLanguageOptions(
            "--experimental_starlark_type_syntax", "--experimental_starlark_dynamic_type_checking"
        )
        scratch.file("a/BUILD")
        scratch.file(
            "a/foo.bzl",
            """
        def requires_int(x: int):
            return x + 1

        def provides_str():
            return "a"

        requires_int(provides_str())
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)

        val key: SkyKey = key("//a:foo.bzl")
        SkyframeExecutorTestUtils.evaluate<T?>(
            getSkyframeExecutor(), key,  /* keepGoing= */false, reporter
        )
        assertContainsEvent(
            "in call to requires_int(), parameter 'x' got value of type 'str', want 'int'"
        )
    }

    private class CustomInMemoryFs : InMemoryFileSystem(DigestHashFunction.SHA256) {
        private var badPathForStat: Path? = null
        private var badPathForRead: Path? = null

        @Throws(IOException::class)
        public override fun statIfFound(path: PathFragment, followSymlinks: Boolean): FileStatus {
            if (badPathForStat != null && badPathForStat.asFragment().equals(path)) {
                throw IOException("bad")
            }
            return super.statIfFound(path, followSymlinks)
        }

        @kotlin.jvm.Synchronized
        @Throws(IOException::class)
        public override fun getInputStream(path: PathFragment): java.io.InputStream? {
            if (badPathForRead != null && badPathForRead.asFragment().equals(path)) {
                throw IOException("bad")
            }
            return super.getInputStream(path)
        }
    }

    companion object {
        private fun key(label: String?): SkyKey {
            return BzlLoadValue.keyForBuild(Label.parseCanonicalUnchecked(label))
        }
    }
}
