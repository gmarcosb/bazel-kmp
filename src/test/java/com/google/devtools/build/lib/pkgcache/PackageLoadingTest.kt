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

import com.google.devtools.build.lib.actions.ActionKeyContext

/** Tests for package loading.  */
@RunWith(TestParameterInjector::class)
class PackageLoadingTest : FoundationTestCase() {
    private var skyframeExecutor: SkyframeExecutor? = null
    private val actionKeyContext: ActionKeyContext = ActionKeyContext()

    @Before
    @Throws(java.lang.Exception::class)
    fun initializeSkyframeExecutor() {
        initializeSkyframeExecutor( /* doPackageLoadingChecks= */true)
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun fooLibrary() {
        scratch.file("test_defs/BUILD")
        scratch.file(
            "test_defs/foo_library.bzl",
            """
        def _impl(ctx):
          pass
        foo_library = rule(
          implementation = _impl,
          attrs = {
            "srcs": attr.label_list(allow_files=True),
            "deps": attr.label_list(),
          },
        )
        
        """.trimIndent()
        )
    }

    /**
     * @param doPackageLoadingChecks when true, a PackageLoader will be called after each package load
     * this test performs, and the results compared to SkyFrame's result.
     */
    @Throws(java.lang.Exception::class)
    private fun initializeSkyframeExecutor(doPackageLoadingChecks: Boolean) {
        val analysisMock: AnalysisMock = AnalysisMock.getAnalysisMockWithoutBuiltinModules()
        val ruleClassProvider: ConfiguredRuleClassProvider? = analysisMock.createRuleClassProvider()
        val directories: BlazeDirectories =
            BlazeDirectories(
                ServerDirectories(outputBase, outputBase, outputBase),
                rootDirectory,
                analysisMock.productName
            )
        val packageFactoryBuilder: PackageFactory.BuilderForTesting =
            analysisMock.getPackageFactoryBuilderForTesting(directories)
        if (!doPackageLoadingChecks) {
            packageFactoryBuilder.disableChecks()
        }
        skyframeExecutor =
            BazelSkyframeExecutorConstants.newBazelSkyframeExecutorBuilder()
                .setPkgFactory(packageFactoryBuilder.build(ruleClassProvider, fileSystem))
                .setFileSystem(fileSystem)
                .setDirectories(directories)
                .setActionKeyContext(actionKeyContext)
                .setExtraSkyFunctions(analysisMock.getSkyFunctions(directories))
                .setSyscallCache(SyscallCache.NO_CACHE)
                .build()
        SkyframeExecutorTestHelper.process(skyframeExecutor)
        setUpSkyframe(parsePackageOptions(), parseBuildLanguageOptions())
    }

    private fun setUpSkyframe(
        packageOptions: PackageOptions, buildLanguageOptions: BuildLanguageOptions?
    ) {
        val pkgLocator: PathPackageLocator? =
            PathPackageLocator.create( /* outputBase= */
                null,
                packageOptions.getPackagePath(),
                reporter,
                rootDirectory.asFragment(),
                rootDirectory,
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
            )
        packageOptions.setShowLoadingProgress(true)
        packageOptions.setGlobbingThreads(7)
        skyframeExecutor.injectExtraPrecomputedValues(AnalysisMock.get().getPrecomputedValues())
        skyframeExecutor.preparePackageLoading(
            pkgLocator,
            packageOptions,
            buildLanguageOptions,
            UUID.randomUUID(),
            com.google.common.collect.ImmutableMap.of<K?, V?>(),
            QuiescingExecutorsImpl.forTesting(),
            TimestampGranularityMonitor(com.google.devtools.build.lib.clock.BlazeClock.instance())
        )
        skyframeExecutor.setActionEnv(com.google.common.collect.ImmutableMap.of<K?, V?>())
        skyframeExecutor.setDeletedPackages(
            com.google.common.collect.ImmutableSet.copyOf(packageOptions.getDeletedPackagesOrEmptySet())
        )
    }

    @Throws(java.lang.Exception::class)
    protected fun setOptions(vararg options: String?) {
        setUpSkyframe(parsePackageOptions(*options), parseBuildLanguageOptions(*options))
    }

    private val packageManager: PackageManager
        get() = skyframeExecutor.getPackageManager()

    @Throws(java.lang.InterruptedException::class, AbruptExitException::class)
    private fun invalidatePackages() {
        skyframeExecutor.invalidateFilesUnderPathForTesting(
            reporter, ModifiedFileSet.EVERYTHING_MODIFIED, Root.fromPath(rootDirectory)
        )
    }

    @Throws(NoSuchPackageException::class, java.lang.InterruptedException::class)
    private fun getPackage(packageName: String?): Package {
        return this.packageManager
            .getPackage(reporter, PackageIdentifier.createInMainRepo(packageName))
    }

    @Throws(NoSuchPackageException::class, NoSuchTargetException::class, java.lang.InterruptedException::class)
    private fun getTarget(label: Label?): Target {
        return this.packageManager.getTarget(reporter, label)
    }

    @Throws(java.lang.Exception::class)
    private fun getTarget(label: String?): Target? {
        return getTarget(Label.parseCanonical(label))
    }

    @Throws(IOException::class)
    private fun createPkg1() {
        scratch.file(
            "pkg1/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'foo') # a BUILD file"
        )
    }

    // Check that a substring is present in an error message.
    private fun checkGetPackageFails(packageName: String?, expectedMessage: String?) {
        val e: NoSuchPackageException? =
            org.junit.Assert.assertThrows<T?>(
                NoSuchPackageException::class.java,
                org.junit.function.ThrowingRunnable { getPackage(packageName) })
        assertThat(e).hasMessageThat().contains(expectedMessage)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetPackage() {
        createPkg1()
        val pkg1: Package = getPackage("pkg1")
        assertThat(pkg1.getName()).isEqualTo("pkg1")
        assertThat(pkg1.getFilename().asPath().getPathString()).isEqualTo("/workspace/pkg1/BUILD")
        assertThat(this.packageManager.getPackage(reporter, PackageIdentifier.createInMainRepo("pkg1")))
            .isSameInstanceAs(pkg1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testASTIsNotRetained() {
        createPkg1()
        val pkg1: Package = getPackage("pkg1")
        MoreAsserts.assertInstanceOfNotReachable(pkg1, net.starlark.java.syntax.StarlarkFile::class.java)
    }

    @org.junit.Test
    fun testGetNonexistentPackage() {
        checkGetPackageFails("not-there", "no such package 'not-there': BUILD file not found")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetPackageWithInvalidName() {
        scratch.file(
            "invalidpackagename:42/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'foo') # a BUILD file"
        )
        checkGetPackageFails(
            "invalidpackagename:42",
            "no such package 'invalidpackagename:42': Invalid package name 'invalidpackagename:42'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getBuildFile_basicFunctionality(@TestParameter lazyMacroExpansion: Boolean) {
        if (lazyMacroExpansion) {
            setOptions("--experimental_lazy_macro_expansion_packages=*")
        }
        createPkg1()
        val buildFile: InputFile = getPackage("pkg1").getBuildFile()
        assertThat(buildFile.getLabel().name).isEqualTo("BUILD")
        assertThat(
            this.packageManager.getBuildFile(reporter, PackageIdentifier.createInMainRepo("pkg1"))
        )
            .isSameInstanceAs(buildFile)
        if (lazyMacroExpansion) {
            assertThat(buildFile.getPackageoid()).isInstanceOf(PackagePiece.ForBuildFile::class.java)
        } else {
            assertThat(buildFile.getPackageoid()).isInstanceOf(Package::class.java)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getBuildFile_onNonexistentPackage_failsCleanly(
        @TestParameter lazyMacroExpansion: Boolean
    ) {
        if (lazyMacroExpansion) {
            setOptions("--experimental_lazy_macro_expansion_packages=*")
        }
        val e: NoSuchPackageException? =
            org.junit.Assert.assertThrows<T?>(
                NoSuchPackageException::class.java,
                org.junit.function.ThrowingRunnable { getPackage("not-there") })
        assertThat(e).hasMessageThat().contains("no such package 'not-there': BUILD file not found")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getBuildFile_doesNotExpandMacrosInLazyMacroExpansionMode(
        @TestParameter lazyMacroExpansion: Boolean
    ) {
        if (lazyMacroExpansion) {
            setOptions("--experimental_lazy_macro_expansion_packages=*")
        }
        scratch.file(
            "pkg1/BUILD",
            """
        load(":bad_macro.bzl", "bad_macro")
        bad_macro(name = "foo")
        
        """.trimIndent()
        )
        scratch.file(
            "pkg1/bad_macro.bzl",
            """
        def _impl(name, visibility):
            fail("bad_macro is broken")

        bad_macro = macro(implementation = _impl)
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val buildFile: InputFile =
            this.packageManager.getBuildFile(reporter, PackageIdentifier.createInMainRepo("pkg1"))
        if (lazyMacroExpansion) {
            // In lazy mode, getBuildFile() doesn't expand bad_macro and doesn't encounter the fail().
            assertThat(buildFile.getPackageoid().containsErrors()).isFalse()
            assertNoEvents()
        } else {
            assertThat(buildFile.getPackageoid().containsErrors()).isTrue()
            assertContainsEvent("bad_macro is broken")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetTarget() {
        createPkg1()
        val label: Label? = Label.parseCanonical("//pkg1:foo")
        val target: Target = getTarget(label)
        assertThat(target.getLabel()).isEqualTo(label)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetNonexistentTarget() {
        createPkg1()
        val e: NoSuchTargetException? =
            org.junit.Assert.assertThrows<T?>(
                NoSuchTargetException::class.java,
                org.junit.function.ThrowingRunnable { getTarget("//pkg1:not-there") })
        assertThat(e)
            .hasMessageThat()
            .matches(
                com.google.devtools.build.lib.testutil.TestUtils.createMissingTargetAssertionString(
                    "not-there",
                    "pkg1",
                    "/workspace",
                    ""
                )
            )
    }

    /**
     * A missing package is one for which no BUILD file can be found. The PackageCache caches failures
     * of this kind until the next sync.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRepeatedAttemptsToParseMissingPackage() {
        checkGetPackageFails("missing", "no such package 'missing': " + "BUILD file not found")

        // Still missing:
        checkGetPackageFails("missing", "no such package 'missing': " + "BUILD file not found")

        // Update the BUILD file on disk so "missing" is no longer missing:
        scratch.file("missing/BUILD", "# an ok build file")

        // Still missing:
        checkGetPackageFails("missing", "no such package 'missing': " + "BUILD file not found")

        invalidatePackages()

        // Found:
        val missing: Package = getPackage("missing")

        assertThat(missing.getName()).isEqualTo("missing")
    }

    /**
     * A broken package is one that exists but contains lexer/parser/evaluator errors. The
     * PackageCache only makes one attempt to parse each package once found.
     * 
     * 
     * Depending on the strictness of the PackageFactory, parsing a broken package may cause a
     * Package object to be returned (possibly missing some rules) or an exception to be thrown. For
     * this test we need that strict behavior.
     * 
     * 
     * Note: since the PackageCache.setStrictPackageCreation method was deleted (since it wasn't
     * used by any significant clients) creating a "broken" build file got trickier--syntax errors are
     * not enough. For now, we create an unreadable BUILD file, which will cause an IOException to be
     * thrown. This test seems less valuable than it once did.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParseBrokenPackage() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)

        val brokenBuildFile: Path = scratch.file("broken/BUILD")
        brokenBuildFile.setReadable(false)

        val e: BuildFileContainsErrorsException? =
            org.junit.Assert.assertThrows<T?>(
                BuildFileContainsErrorsException::class.java,
                org.junit.function.ThrowingRunnable { getPackage("broken") })
        assertThat(e).hasMessageThat().contains("/workspace/broken/BUILD (Permission denied)")
        eventCollector.clear()

        // Update the BUILD file on disk so "broken" is no longer broken:
        scratch.overwriteFile("broken/BUILD", "# an ok build file")

        invalidatePackages() //  resets cache of failures

        val broken: Package = getPackage("broken")
        assertThat(broken.getName()).isEqualTo("broken")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMovedBuildFileCausesReloadAfterSync() {
        // PackageLoader doesn't support --package_path.
        initializeSkyframeExecutor( /* doPackageLoadingChecks= */false)

        val buildFile1: Path =
            scratch.file(
                "pkg/BUILD",
                "load('//test_defs:foo_library.bzl', 'foo_library')",
                "foo_library(name = 'foo')"
            )
        val buildFile2: Path =
            scratch.file(
                "/otherroot/pkg/BUILD",
                "load('//test_defs:foo_library.bzl', 'foo_library')",
                "foo_library(name = 'bar')"
            )
        setOptions("--package_path=/workspace:/otherroot")

        val oldPkg: Package = getPackage("pkg")
        assertThat(getPackage("pkg")).isSameInstanceAs(oldPkg) // change not yet visible
        assertThat(oldPkg.getFilename().asPath()).isEqualTo(buildFile1)
        assertThat(oldPkg.getSourceRoot()).isEqualTo(Root.fromPath(rootDirectory))

        buildFile1.delete()
        invalidatePackages()

        val newPkg: Package = getPackage("pkg")
        assertThat(newPkg).isNotSameInstanceAs(oldPkg)
        assertThat(newPkg.getFilename().asPath()).isEqualTo(buildFile2)
        assertThat(newPkg.getSourceRoot()).isEqualTo(Root.fromPath(scratch.dir("/otherroot")))

        // TODO(bazel-team): (2009) test BUILD file moves in the other direction too.
    }

    private var rootDir1: Path? = null
    private var rootDir2: Path? = null

    @Throws(java.lang.Exception::class)
    private fun setUpCacheWithTwoRootLocator() {
        // Root 1:
        //   /a/BUILD
        //   /b/BUILD
        //   /c/d
        //   /c/e
        //
        // Root 2:
        //   /b/BUILD
        //   /c/BUILD
        //   /c/d/BUILD
        //   /f/BUILD
        //   /f/g
        //   /f/g/h/BUILD

        rootDir1 = scratch.dir("/workspace")
        rootDir2 = scratch.dir("/otherroot")

        createBuildFile(rootDir1, "a", "foo.txt", "bar/foo.txt")
        createBuildFile(rootDir1, "b", "foo.txt", "bar/foo.txt")

        rootDir1.getRelative("c").createDirectory()
        rootDir1.getRelative("c/d").createDirectory()
        rootDir1.getRelative("c/e").createDirectory()

        createBuildFile(rootDir2, "c", "d", "d/foo.txt", "foo.txt", "bar/foo.txt", "e", "e/foo.txt")
        createBuildFile(rootDir2, "c/d", "foo.txt")
        createBuildFile(rootDir2, "f", "g/foo.txt", "g/h", "g/h/foo.txt", "foo.txt")
        createBuildFile(rootDir2, "f/g/h", "foo.txt")

        setOptions("--package_path=/workspace:/otherroot")
    }

    @Throws(IOException::class)
    protected fun createBuildFile(workspace: Path?, packageName: String?, vararg targets: String?): Path {
        val lines = arrayOfNulls<String>(targets.size + 1)

        lines[0] = "load('//test_defs:foo_library.bzl', 'foo_library')"
        for (i in targets.indices) {
            lines[i + 1] = "foo_library(name='" + targets[i] + "')"
        }

        return scratch.file(workspace.toString() + "/" + packageName + "/BUILD", *lines)
    }

    @Throws(java.lang.Exception::class)
    private fun assertLabelValidity(expected: Boolean, labelString: String?) {
        val label: Label? = Label.parseCanonical(labelString)

        var actual = false
        var error: String? = null
        try {
            getTarget(label)
            actual = true
        } catch (e: NoSuchPackageException) {
            error = e.getMessage()
        } catch (e: NoSuchTargetException) {
            error = e.getMessage()
        }
        if (actual != expected) {
            org.junit.Assert.fail(
                ("assertLabelValidity("
                        + label
                        + ") "
                        + actual
                        + ", not equal to expected value "
                        + expected
                        + " (error="
                        + error
                        + ")")
            )
        }
    }

    @Throws(java.lang.Exception::class)
    private fun assertPackageLoadingFails(pkgName: String?, expectedError: String?) {
        val pkg: Package = getPackage(pkgName)
        assertThat(pkg.containsErrors()).isTrue()
        assertContainsEvent(expectedError)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLocationForLabelCrossingSubpackage() {
        scratch.file("e/f/BUILD")
        scratch.file(
            "e/BUILD",
            """
        # Whatever
        filegroup(
            name = "fg",
            srcs = ["f/g"],
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)

        getPackage("e")

        Truth.assertThat(eventCollector).hasSize(1)
        Truth.assertThat(
            com.google.common.collect.Iterables.getOnlyElement<com.google.devtools.build.lib.events.Event?>(
                eventCollector
            ).getLocation().line()
        ).isEqualTo(2)
    }

    /** Static tests (i.e. no changes to filesystem, nor calls to sync).  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelValidity() {
        // PackageLoader doesn't support --package_path.
        initializeSkyframeExecutor( /* doPackageLoadingChecks= */false)

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        setUpCacheWithTwoRootLocator()

        scratch.file(rootDir2.toString() + "/c/d/foo.txt")

        assertLabelValidity(true, "//a:foo.txt")
        assertLabelValidity(true, "//a:bar/foo.txt")
        assertLabelValidity(false, "//a/bar:foo.txt") //  no such package a/bar

        assertLabelValidity(true, "//b:foo.txt")
        assertLabelValidity(true, "//b:bar/foo.txt")
        assertLabelValidity(false, "//b/bar:foo.txt") // no such package b/bar

        assertLabelValidity(true, "//c:foo.txt")
        assertLabelValidity(true, "//c:bar/foo.txt")
        assertLabelValidity(false, "//c/bar:foo.txt") // no such package c/bar

        assertLabelValidity(true, "//c:foo.txt")

        assertLabelValidity(false, "//c:d/foo.txt") // crosses boundary of c/d
        assertLabelValidity(true, "//c/d:foo.txt")

        assertLabelValidity(true, "//c:foo.txt")
        assertLabelValidity(true, "//c:e")
        assertLabelValidity(true, "//c:e/foo.txt")
        assertLabelValidity(false, "//c/e:foo.txt") // no such package c/e

        assertLabelValidity(true, "//f:foo.txt")
        assertLabelValidity(true, "//f:g/foo.txt")
        assertLabelValidity(false, "//f/g:foo.txt") // no such package f/g
        assertLabelValidity(false, "//f:g/h/foo.txt") // crosses boundary of f/g/h
        assertLabelValidity(false, "//f/g:h/foo.txt") // no such package f/g
        assertLabelValidity(true, "//f/g/h:foo.txt")
    }

    /** Dynamic tests of label validity.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAddedBuildFileCausesLabelToBecomeInvalid() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        scratch.file(
            "pkg/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'foo', srcs = ['x/y.cc'])"
        )

        assertLabelValidity(true, "//pkg:x/y.cc")

        // The existence of this file makes 'x/y.cc' an invalid reference.
        scratch.file("pkg/x/BUILD")

        // but not yet...
        assertLabelValidity(true, "//pkg:x/y.cc")

        invalidatePackages()

        // now:
        assertPackageLoadingFails(
            "pkg", "Label '//pkg:x/y.cc' is invalid because 'pkg/x' is a subpackage"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeletedPackages() {
        // PackageLoader doesn't support --deleted_packages.
        initializeSkyframeExecutor( /* doPackageLoadingChecks= */false)
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        setUpCacheWithTwoRootLocator()
        createBuildFile(rootDir1, "c", "d/x", "e/x")
        createBuildFile(rootDir1, "c/e", "x")

        // Now package c exists in both roots, and c/d exists in only in the second
        // root.  It's as if we've merged c and c/d in the first root.

        // c/d is still a subpackage--found in the second root:
        assertThat(getPackage("c/d").getFilename().asPath())
            .isEqualTo(rootDir2.getRelative("c/d/BUILD"))

        // Subpackage labels are still valid...
        assertLabelValidity(true, "//c/d:foo.txt")
        assertLabelValidity(true, "//c/e:x")
        // ...and this crosses package boundaries:
        assertLabelValidity(false, "//c:d/x")
        assertPackageLoadingFails(
            "c",
            "Label '//c:d/x' is invalid because 'c/d' is a subpackage; have you deleted c/d/BUILD? "
                    + "If so, use the --deleted_packages=c/d option"
        )

        assertThat(this.packageManager.isPackage(reporter, PackageIdentifier.createInMainRepo("c/d")))
            .isTrue()

        setOptions("--package_path=/workspace:/otherroot", "--deleted_packages=c/d")
        invalidatePackages()

        assertThat(this.packageManager.isPackage(reporter, PackageIdentifier.createInMainRepo("c/d")))
            .isFalse()

        // c/d is no longer a subpackage--even though there's a BUILD file in the
        // second root:
        val e: NoSuchPackageException? = org.junit.Assert.assertThrows<T?>(
            NoSuchPackageException::class.java,
            org.junit.function.ThrowingRunnable { getPackage("c/d") })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo(
                "no such package 'c/d': Package is considered deleted due to --deleted_packages"
            )

        // Labels in the subpackage are no longer valid...
        assertLabelValidity(false, "//c/d:x")
        // ...and now d is just a subdirectory of c:
        assertLabelValidity(true, "//c:d/x")

        // Verify that multiple --deleted_packages options are concatenated
        setOptions(
            "--package_path=/workspace:/otherroot", "--deleted_packages=c/d", "--deleted_packages=c/e"
        )
        invalidatePackages()

        assertLabelValidity(false, "//c/d:x")
        assertLabelValidity(false, "//c/e:x")
        assertLabelValidity(true, "//c:d/x")
        assertLabelValidity(true, "//c:e/x")

        // Verify that comma-separated values work, too
        setOptions("--package_path=/workspace:/otherroot", "--deleted_packages=c/d,c/e")
        invalidatePackages()

        assertLabelValidity(false, "//c/d:x")
        assertLabelValidity(false, "//c/e:x")
        assertLabelValidity(true, "//c:d/x")
        assertLabelValidity(true, "//c:e/x")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageFeatures() {
        scratch.file(
            "peach/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        package(features = ["crosstool_default_false"])

        foo_library(
            name = "cc",
            srcs = ["cc.cc"],
        )
        
        """.trimIndent()
        )
        assertThat(getPackage("peach").getPackageArgs().features())
            .isEqualTo(FeatureSet.parse(com.google.common.collect.ImmutableList.of<E?>("crosstool_default_false")))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBrokenPackageOnMultiplePackagePathEntries() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        setOptions("--package_path=.:.")
        scratch.file("x/y/BUILD")
        scratch.file(
            "x/BUILD",
            """
        genrule(
            name = "x",
            srcs = [],
            outs = ["y/z.h"],
            cmd = "",
        )
        
        """.trimIndent()
        )
        val p: Package = getPackage("x")
        assertThat(p.containsErrors()).isTrue()
    }

    // Regression test for b/230791645: non-deterministic location of input file targets.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeterminismOfInputFileLocation() {
        scratch.file(
            "p/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')

        foo_library(
            name = "t1",
            srcs = ["f.sh"],
        )

        foo_library(
            name = "t2",
            srcs = ["f.sh"],
        )
        
        """.trimIndent()
        )
        val p: Package = getPackage("p")
        val f: InputFile = p.getTarget("f.sh") as InputFile
        assertThat(f.getLocation().line()).isEqualTo(3)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeterminismOfFailureDetailOnMultipleLabelCrossingSubpackageBoundaryErrors() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        scratch.file("p/sub/BUILD")
        scratch.file(
            "p/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')

        foo_library(name = "sub/a")

        foo_library(name = "sub/b")
        
        """.trimIndent()
        )
        val p: Package = getPackage("p")
        assertThat(p.getFailureDetail().getPackageLoading().getCode())
            .isEqualTo(PackageLoading.Code.LABEL_CROSSES_PACKAGE_BOUNDARY)
        // We used to non-deterministically pick a target whose label crossed a subpackage boundary, but
        // now we deterministically pick the first one (alphabetically by target name).
        assertThat(p.getFailureDetail().getMessage()).startsWith("Label '//p:sub/a' is invalid")
    }

    companion object {
        @Throws(java.lang.Exception::class)
        private fun parse(vararg options: String?): OptionsParser {
            val parser: OptionsParser =
                OptionsParser.builder()
                    .optionsClasses(PackageOptions::class.java, BuildLanguageOptions::class.java)
                    .build()
            parser.parse(TestConstants.PRODUCT_SPECIFIC_BUILD_LANG_OPTIONS)
            parser.parse("--default_visibility=public")
            parser.parse(*options)

            return parser
        }

        @Throws(java.lang.Exception::class)
        private fun parsePackageOptions(vararg options: String?): PackageOptions? {
            return parse(*options).getOptions<O?>(PackageOptions::class.java)
        }

        @Throws(java.lang.Exception::class)
        private fun parseBuildLanguageOptions(vararg options: String?): BuildLanguageOptions? {
            return parse(*options).getOptions<O?>(BuildLanguageOptions::class.java)
        }
    }
}
