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

import com.google.devtools.build.lib.actions.FileStateValue

/**
 * Unit tests of specific functionality of PackageFunction. Note that it's already tested indirectly
 * in several other places.
 */
@RunWith(TestParameterInjector::class)
class PackageFunctionTest : BuildViewTestCase() {
    @org.junit.Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    @org.mockito.Mock
    private val mockPackageValidator: PackageValidator? = null

    @org.mockito.Mock
    private val mockPackageOverheadEstimator: PackageOverheadEstimator? = null

    @TestParameter
    private val globUnderSingleDep = false

    // Set by preparePackageLoading().
    private var computationMode = ComputationMode.MONOLITHIC_PACKAGE

    private val fs: CustomInMemoryFs =
        com.google.devtools.build.lib.skyframe.PackageFunctionTest.CustomInMemoryFs(com.google.devtools.build.lib.testutil.ManualClock())

    private enum class ComputationMode {
        // Use PackageIdentifier as the key, and retrieve the result as a Package.
        MONOLITHIC_PACKAGE,

        // Use PackagePieceIdentifier.ForBuildFile as the key, and retrieve the result as a
        // PackagePiece.ForBuildFile.
        PACKAGE_PIECE_FOR_BUILD_FILE,

        // Use PackageIdentifier as the key, expand symbolic macros lazily, and assemble the resulting
        // Package from PackagePieces.
        PACKAGE_FROM_PACKAGE_PIECES
    }

    @Throws(java.lang.Exception::class)
    private fun preparePackageLoading(computationMode: ComputationMode?, vararg roots: Path?) {
        preparePackageLoadingWithCustomStarklarkSemanticsOptions(
            computationMode!!, parseBuildLanguageOptions(), roots
        )
    }

    @Throws(java.lang.InterruptedException::class, AbruptExitException::class)
    private fun preparePackageLoadingWithCustomStarklarkSemanticsOptions(
        computationMode: ComputationMode, buildLanguageOptions: BuildLanguageOptions, vararg roots: Path?
    ) {
        this.computationMode = computationMode
        val packageOptions: PackageOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(PackageOptions::class.java)
        val packagePath: com.google.common.collect.ImmutableList<String?> =
            java.util.Arrays.stream<Path?>(roots).map<Any?>(Path::getPathString)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        if (!packagePath.isEmpty()) {
            packageOptions.setPackagePath(packagePath)
        }
        packageOptions.setDefaultVisibility(RuleVisibility.PUBLIC)
        packageOptions.setShowLoadingProgress(true)
        packageOptions.setGlobbingThreads(7)
        if (computationMode == ComputationMode.PACKAGE_FROM_PACKAGE_PIECES) {
            packageOptions.setLazyMacroExpansionPackages(PackageOptions.LazyMacroExpansionPackages.ALL)
        }
        setPackageAndBuildLanguageOptions(packageOptions, buildLanguageOptions)
    }

    protected override fun createFileSystem(): FileSystem {
        return fs
    }

    override fun getPackageValidator(): PackageValidator {
        return mockPackageValidator
    }

    override fun getPackageOverheadEstimator(): PackageOverheadEstimator {
        return mockPackageOverheadEstimator
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(java.lang.InterruptedException::class)
    private fun validPackageoidWithoutErrors(pkg: String?): Packageoid {
        return validPackageoidInternal(pkg,  /* checkError= */true)
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(java.lang.InterruptedException::class)
    private fun validPackageoid(pkg: String?): Packageoid {
        return validPackageoidInternal(pkg,  /* checkError= */false)
    }

    @Throws(java.lang.InterruptedException::class)
    private fun validPackageoidInternal(pkg: String?, checkError: Boolean): Packageoid {
        val skyKey: SkyKey? = getSkyKey(pkg)
        val skyframeExecutor: SkyframeExecutor = getSkyframeExecutor()
        val result: EvaluationResult<PackageoidValue?> =
            SkyframeExecutorTestUtils.evaluate<SkyValue?>(
                skyframeExecutor, skyKey,  /* keepGoing= */false, reporter
            )
        if (result.hasError()) {
            org.junit.Assert.fail(result.getError(skyKey).getException().getMessage())
        }
        val value: Packageoid = result.get(skyKey).packageoid
        if (skyKey is PackageIdentifier) {
            assertThat(value).isInstanceOf(Package::class.java)
            val buildFile: InputFile = (value as Package).getBuildFile()
            assertThat(buildFile).isNotNull()
            if (computationMode == ComputationMode.PACKAGE_FROM_PACKAGE_PIECES) {
                // Targets are owned by package pieces, not by the package-from-pieces.
                assertThat(buildFile.getPackageoid()).isInstanceOf(PackagePiece.ForBuildFile::class.java)
                for (target in value.getTargets().values()) {
                    assertWithMessage("Packageoid of target %s", target.getLabel())
                        .that(target.getPackageoid())
                        .isNotSameInstanceAs(value)
                    assertWithMessage("Packageoid of target %s", target.getLabel())
                        .that(target.getPackageoid())
                        .isInstanceOf(PackagePiece::class.java)
                }
            }
        } else {
            assertThat(value).isInstanceOf(PackagePiece.ForBuildFile::class.java)
            val buildFile: InputFile? = (value as PackagePiece.ForBuildFile).getBuildFile()
            assertThat(buildFile).isNotNull()
        }
        if (checkError) {
            assertThat(value.containsErrors()).isFalse()
        }
        return value
    }

    private fun getSkyKey(pkg: String?): SkyKey? {
        val pkgId: PackageIdentifier? = PackageIdentifier.createInMainRepo(pkg)
        return if (computationMode == ComputationMode.PACKAGE_PIECE_FOR_BUILD_FILE)
            ForBuildFile(pkgId)
        else
            pkgId
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(java.lang.Exception::class)
    private fun evaluatePackageoidToException(pkg: String?): java.lang.Exception {
        return evaluatePackageoidToException(pkg,  /* keepGoing= */false)
    }

    /**
     * Helper that evaluates the given package or package piece and returns the expected exception.
     * 
     * 
     * Disables the failFastHandler as a side-effect.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(java.lang.Exception::class)
    private fun evaluatePackageoidToException(pkg: String?, keepGoing: Boolean): java.lang.Exception {
        reporter.removeHandler(failFastHandler)

        val skyKey: SkyKey? = getSkyKey(pkg)
        val result: EvaluationResult<PackageoidValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(getSkyframeExecutor(), skyKey, keepGoing, reporter)
        assertThat(result.hasError()).isTrue()
        return result.getError(skyKey).getException()
    }

    @Before
    @Throws(java.lang.Exception::class)
    public override fun initializeSkyframeExecutor() {
        Mockito.`when`<T?>(mockPackageValidator.getPackageLimits())
            .thenReturn(Package.Builder.PackageLimits.DEFAULTS)
        initializeSkyframeExecutor( /* doPackageLoadingChecks= */
            true,  /* diffAwarenessFactories= */
            com.google.common.collect.ImmutableList.of<DiffAwareness.Factory>(),  /* globUnderSingleDep= */
            globUnderSingleDep
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testValidPackage(@TestParameter computationMode: ComputationMode?) {
        scratch.file("pkg/BUILD", "filegroup(name = 'foo')")
        preparePackageLoading(computationMode)
        val pkg: Packageoid = validPackageoidWithoutErrors("pkg")
        assertThat(pkg.getTargets()).containsKey("foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun symbolicMacroExpansion_onlyInFullPackages(
        @TestParameter computationMode: ComputationMode
    ) {
        scratch.file(
            "pkg/macro.bzl",
            """
        def legacy(name, visibility = None, **kwargs):
            native.filegroup(name = name, visibility = visibility, **kwargs)

        symbolic = macro(
            implementation = legacy,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":macro.bzl", "legacy", "symbolic")
        legacy(name = "target_in_legacy_macro")
        symbolic(name = "target_in_symbolic_macro")
        
        """.trimIndent()
        )
        preparePackageLoading(computationMode)
        val pkg: Packageoid = validPackageoidWithoutErrors("pkg")
        assertThat(pkg.getTargets()).containsKey("target_in_legacy_macro")
        if (computationMode == ComputationMode.PACKAGE_PIECE_FOR_BUILD_FILE) {
            assertThat(pkg.getTargets()).doesNotContainKey("target_in_symbolic_macro")
        } else {
            assertThat(pkg.getTargets()).containsKey("target_in_symbolic_macro")
        }
    }

    @org.junit.Test // TODO(https://github.com/bazelbuild/bazel/issues/23852): enable PACKAGE_PIECE_FOR_BUILD_FILE
    // once package piece validation is supported.
    @Throws(java.lang.Exception::class)
    fun testInvalidPackage(
        @TestParameter("MONOLITHIC_PACKAGE", "PACKAGE_FROM_PACKAGE_PIECES") computationMode: ComputationMode?
    ) {
        scratch.file("pkg/BUILD", "filegroup(name='foo', srcs=['foo.sh'])")
        scratch.file("pkg/foo.sh")
        preparePackageLoading(computationMode)

        Mockito.doAnswer(
            Answer { inv: InvocationOnMock? ->
                val pkg: Package = inv.getArgument<Package>(0, Package::class.java)
                if (pkg.getName().equals("pkg")) {
                    inv.getArgument<ExtendedEventHandler?>(2, ExtendedEventHandler::class.java)
                        .handle(com.google.devtools.build.lib.events.Event.warn("warning event"))
                    throw InvalidPackageException(pkg.getPackageIdentifier(), "no good")
                }
                null
            })
            .`when`<Any?>(mockPackageValidator)
            .validate(
                ArgumentMatchers.any<T?>(Package::class.java),
                ArgumentMatchers.any<T?>(Metrics::class.java),
                ArgumentMatchers.any<T?>(ExtendedEventHandler::class.java)
            )

        invalidatePackages()

        val ex: java.lang.Exception = evaluatePackageoidToException("pkg")
        Truth.assertThat(ex).isInstanceOf(InvalidPackageException::class.java)
        Truth.assertThat(ex).hasMessageThat().contains("no such package 'pkg': no good")
        assertContainsEvent("warning event")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun package_withBadMacroImplementation(
        @TestParameter("MONOLITHIC_PACKAGE", "PACKAGE_FROM_PACKAGE_PIECES") computationMode: ComputationMode
    ) {
        scratch.file(
            "pkg/my_macro.bzl",
            """
        def _impl(name, visibility):
            native.filegroup(name = name, visibility = visibility)
            fail("fail fail fail")
        my_macro = macro(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "foo")
        
        """.trimIndent()
        )
        preparePackageLoading(computationMode)
        reporter.removeHandler(failFastHandler)

        val pkg: Package = validPackageoid("pkg") as Package
        assertThat(pkg.containsErrors()).isTrue()
        assertContainsEvent(
            """
        Traceback (most recent call last):
        ${'\t'}File "/workspace/pkg/BUILD", line 2, column 9, in <toplevel>
        ${'\t'}${'\t'}my_macro(name = "foo")
        ${'\t'}File "/workspace/pkg/my_macro.bzl", line 4, column 1, in my_macro
        ${'\t'}${'\t'}my_macro = macro(implementation = _impl)
        ${'\t'}File "/workspace/pkg/my_macro.bzl", line 3, column 9, in _impl
        ${'\t'}${'\t'}fail("fail fail fail")
        Error in fail: fail fail fail
        """.trimIndent()
        )
        if (computationMode == ComputationMode.MONOLITHIC_PACKAGE) {
            assertThat(eventCollector.filtered(com.google.devtools.build.lib.events.EventKind.ERROR)).hasSize(1)
        } else {
            assertContainsEvent(
                "ERROR /workspace/pkg/BUILD: cannot compute package //pkg: error in package piece for"
                        + " macro //pkg:foo defined by //pkg:my_macro.bzl%my_macro"
            )
            assertThat(eventCollector.filtered(com.google.devtools.build.lib.events.EventKind.ERROR)).hasSize(2)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun package_withMacroNameConflict(
        @TestParameter inFinalizer: Boolean,
        @TestParameter("MONOLITHIC_PACKAGE", "PACKAGE_FROM_PACKAGE_PIECES") computationMode: ComputationMode?
    ) {
        scratch.file(
            "pkg/my_macro.bzl",
            String.format(
                """
            def _impl(name, visibility):
                native.filegroup(name = name + "_bar")
            my_macro = macro(implementation = _impl, finalizer = %s)
            
            """.trimIndent(),
                if (inFinalizer) "True" else "False"
            )
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":my_macro.bzl", "my_macro")
        genrule(name = "foo_bar", outs = ["unused"], cmd = "touch ${'$'}@")
        my_macro(name = "foo")  # will try to create another target named "foo_bar"
        
        """.trimIndent()
        )
        preparePackageLoading(computationMode)
        reporter.removeHandler(failFastHandler)

        val pkg: Package = validPackageoid("pkg") as Package
        assertThat(pkg.containsErrors()).isTrue()
        assertContainsEvent(
            "filegroup rule 'foo_bar' conflicts with existing genrule rule, defined at"
                    + " /workspace/pkg/BUILD:2:8"
        )
    }

    @org.junit.Test // TODO(https://github.com/bazelbuild/bazel/issues/23852): enable PACKAGE_PIECE_FOR_BUILD_FILE
    // once package piece validation is supported.
    @Throws(java.lang.Exception::class)
    fun testPackageOverheadPassedToValidationLogic(
        @TestParameter("MONOLITHIC_PACKAGE", "PACKAGE_FROM_PACKAGE_PIECES") computationMode: ComputationMode?
    ) {
        scratch.file("pkg/BUILD", "# Contents doesn't matter, it's all fake")
        preparePackageLoading(computationMode)

        Mockito.`when`<T?>(mockPackageOverheadEstimator.estimatePackageOverhead(ArgumentMatchers.any<T?>(Package::class.java)))
            .thenReturn(OptionalLong.of(42))
        val packageCaptor: ArgumentCaptor<Package?> = ArgumentCaptor.forClass<Package?, Package?>(Package::class.java)

        invalidatePackages(true)
        Mockito.reset<Any?>(mockPackageValidator)
        Mockito.`when`<T?>(mockPackageValidator.getPackageLimits())
            .thenReturn(Package.Builder.PackageLimits.DEFAULTS)

        SkyframeExecutorTestUtils.evaluate<T?>(
            getSkyframeExecutor(), getSkyKey("pkg"),  /* keepGoing= */false, reporter
        )

        Mockito.verify<Any?>(mockPackageValidator)
            .validate(
                packageCaptor.capture(),
                ArgumentMatchers.any<T?>(Metrics::class.java),
                ArgumentMatchers.any<T?>(ExtendedEventHandler::class.java)
            )
        val packages: MutableList<Package?> = packageCaptor.getAllValues()
        assertThat(packages.get(0).getPackageOverhead()).isEqualTo(OptionalLong.of(42))
    }

    @org.junit.Test // TODO(https://github.com/bazelbuild/bazel/issues/23852): enable PACKAGE_PIECE_FOR_BUILD_FILE
    // once package piece validation is supported.
    @Throws(java.lang.Exception::class)
    fun testSkyframeExecutorClearedPackagesResultsInReload(
        @TestParameter("MONOLITHIC_PACKAGE", "PACKAGE_FROM_PACKAGE_PIECES") computationMode: ComputationMode?
    ) {
        scratch.file("pkg/BUILD", "filegroup(name='foo', srcs=['foo.sh'])")
        scratch.file("pkg/foo.sh")
        preparePackageLoading(computationMode)

        invalidatePackages()

        // Use number of times the package was validated as a proxy for number of times it was loaded.
        val validationCount: AtomicInteger = AtomicInteger()
        Mockito.doAnswer(
            Answer { inv: InvocationOnMock? ->
                if (inv.getArgument<Package?>(0, Package::class.java).getName().equals("pkg")) {
                    validationCount.incrementAndGet()
                }
                null
            })
            .`when`<Any?>(mockPackageValidator)
            .validate(
                ArgumentMatchers.any<T?>(Package::class.java),
                ArgumentMatchers.any<T?>(Metrics::class.java),
                ArgumentMatchers.any<T?>(ExtendedEventHandler::class.java)
            )

        val skyKey: SkyKey? = getSkyKey("pkg")
        val result1: EvaluationResult<PackageoidValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), skyKey,  /* keepGoing= */false, reporter
            )
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result1).hasNoError()

        skyframeExecutor.clearLoadedPackages()

        val result2: EvaluationResult<PackageoidValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), skyKey,  /* keepGoing= */false, reporter
            )
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result2).hasNoError()

        Truth.assertThat(validationCount.get()).isEqualTo(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPropagatesFilesystemInconsistencies(
        @TestParameter computationMode: ComputationMode?
    ) {
        val differencer: RecordingDifferencer = getSkyframeExecutor().getDifferencerForTesting()
        val pkgRoot: Root? = getSkyframeExecutor().getPackagePathEntries().getFirst()
        val fooBuildFile: Path = scratch.file("foo/BUILD")
        val fooDir: Path? = fooBuildFile.getParentDirectory()
        preparePackageLoading(computationMode)

        // Our custom filesystem says that fooDir is neither a file nor directory nor symlink
        val inconsistentFileStatus: FileStatus =
            object : FileStatus() {
                public override fun isFile(): Boolean {
                    return false
                }

                public override fun isDirectory(): Boolean {
                    return false
                }

                public override fun isSymbolicLink(): Boolean {
                    return false
                }

                public override fun isSpecialFile(): Boolean {
                    return false
                }

                public override fun getSize(): Long {
                    return 0
                }

                public override fun getLastModifiedTime(): Long {
                    return 0
                }

                public override fun getLastChangeTime(): Long {
                    return 0
                }

                public override fun getNodeId(): Long {
                    return 0
                }
            }

        fs.stubStat(fooBuildFile, inconsistentFileStatus)
        val pkgRootedPath: RootedPath? = RootedPath.toRootedPath(pkgRoot, fooDir)
        val fooDirValue: SkyValue? = FileStateValue.create(pkgRootedPath, SyscallCache.NO_CACHE, tsgm)
        differencer.inject(
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                FileStateValue.key(pkgRootedPath),
                Delta.justNew(fooDirValue)
            )
        )

        val ex: java.lang.Exception = evaluatePackageoidToException("foo")
        val msg: String? = ex.message
        Truth.assertThat(msg).contains("Inconsistent filesystem operations")
        Truth.assertThat(msg)
            .contains(
                "according to stat, existing path /workspace/foo/BUILD is neither"
                        + " a file nor directory nor symlink."
            )
        assertDetailedExitCode(
            ex,
            PackageLoading.Code.PERSISTENT_INCONSISTENT_FILESYSTEM_ERROR,
            ExitCode.LOCAL_ENVIRONMENTAL_ERROR
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPropagatesFilesystemInconsistencies_globbing(
        @TestParameter computationMode: ComputationMode?
    ) {
        val differencer: RecordingDifferencer = getSkyframeExecutor().getDifferencerForTesting()
        val pkgRoot: Root? = getSkyframeExecutor().getPackagePathEntries().getFirst()
        scratch.file(
            "foo/BUILD",
            """
        filegroup(
            name = "foo",
            srcs = glob(["bar/**/baz.sh"]),
        )

        x = 1 // 0
        
        """.trimIndent() // causes 'foo' to be marked in error
        )
        val bazFile: Path = scratch.file("foo/bar/baz/baz.sh")
        val bazDir: Path = bazFile.getParentDirectory()
        val barDir: Path? = bazDir.getParentDirectory()
        preparePackageLoading(computationMode)

        // Our custom filesystem says "foo/bar/baz" does not exist but it also says that "foo/bar"
        // has a child directory "baz".
        fs.stubStat(bazDir, null)
        val barDirRootedPath: RootedPath? = RootedPath.toRootedPath(pkgRoot, barDir)
        differencer.inject(
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                DirectoryListingStateValue.key(barDirRootedPath),
                Delta.justNew(
                    DirectoryListingStateValue.create(
                        com.google.common.collect.ImmutableList.of<E?>(Dirent("baz", Dirent.Type.DIRECTORY))
                    )
                )
            )
        )

        val ex: java.lang.Exception = evaluatePackageoidToException("foo")
        val msg: String? = ex.message
        Truth.assertThat(msg).contains("Inconsistent filesystem operations")
        Truth.assertThat(msg).contains("/workspace/foo/bar/baz is no longer an existing directory")
        assertDetailedExitCode(
            ex,
            PackageLoading.Code.PERSISTENT_INCONSISTENT_FILESYSTEM_ERROR,
            ExitCode.LOCAL_ENVIRONMENTAL_ERROR
        )
    }

    /** Regression test for unexpected exception type from PackageValue.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDiscrepancyBetweenGlobbingErrors(@TestParameter computationMode: ComputationMode?) {
        preparePackageLoading(computationMode)
        val fooBuildFile: Path =
            scratch.file("foo/BUILD", "filegroup(name = 'foo', srcs = glob(['bar/*.sh']))")
        val fooDir: Path = fooBuildFile.getParentDirectory()
        val barDir: Path = fooDir.getRelative("bar")
        scratch.file("foo/bar/baz.sh")
        fs.scheduleMakeUnreadableAfterReaddir(barDir)
        preparePackageLoading(computationMode)

        val ex: java.lang.Exception =
            evaluatePackageoidToException(
                "foo",  // Use --keep_going, not --nokeep_going, semantics so as to exercise the situation we
                // want to exercise.
                //
                // In --nokeep_going semantics, the GlobValue node's error would halt normal evaluation
                // and trigger error bubbling. Then, during error bubbling we would freshly compute the
                // PackageValue node again, meaning we would do non-Skyframe globbing except this time
                // non-Skyframe globbing would encounter the io error, meaning there actually wouldn't
                // be a discrepancy.
                /* keepGoing= */
                true
            )
        val msg: String? = ex.message
        Truth.assertThat(msg).contains("Inconsistent filesystem operations")
        Truth.assertThat(msg).contains("Encountered error '/workspace/foo/bar (Permission denied)'")
        assertDetailedExitCode(
            ex,
            PackageLoading.Code.TRANSIENT_INCONSISTENT_FILESYSTEM_ERROR,
            ExitCode.LOCAL_ENVIRONMENTAL_ERROR
        )
    }

    // Cast of srcs attribute to Iterable<Label>.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobOrderStable(@TestParameter computationMode: ComputationMode?) {
        scratch.file("foo/BUILD", "filegroup(name = 'foo', srcs = glob(['**/*.txt']))")
        scratch.file("foo/b.txt")
        scratch.file("foo/c/c.txt")
        preparePackageLoading(computationMode, rootDirectory)
        var pkg: Packageoid = validPackageoidWithoutErrors("foo")
        Truth.assertThat(pkg.getTarget("foo").getAssociatedRule().getAttr("srcs") as Iterable<Label?>?)
            .containsExactly(
                Label.parseCanonicalUnchecked("//foo:b.txt"),
                Label.parseCanonicalUnchecked("//foo:c/c.txt")
            )
            .inOrder()
        scratch.file("foo/d.txt")
        getSkyframeExecutor()
            .invalidateFilesUnderPathForTesting(
                reporter,
                ModifiedFileSet.builder().modify(PathFragment.create("foo/d.txt")).build(),
                Root.fromPath(rootDirectory)
            )
        pkg = validPackageoidWithoutErrors("foo")
        Truth.assertThat(pkg.getTarget("foo").getAssociatedRule().getAttr("srcs") as Iterable<Label?>?)
            .containsExactly(
                Label.parseCanonicalUnchecked("//foo:b.txt"),
                Label.parseCanonicalUnchecked("//foo:c/c.txt"),
                Label.parseCanonicalUnchecked("//foo:d.txt")
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobOrderStableWithNonSkyframeAndSkyframeComponents(
        @TestParameter computationMode: ComputationMode
    ) {
        scratch.file("foo/BUILD", "filegroup(name = 'foo', srcs = glob(['*.txt']))")
        scratch.file("foo/b.txt")
        scratch.file("foo/a.config")
        preparePackageLoading(computationMode, rootDirectory)
        assertSrcs(validPackageoidWithoutErrors("foo"), "foo", "//foo:b.txt")
        scratch.overwriteFile(
            "foo/BUILD", "filegroup(name = 'foo', srcs = glob(['*.txt', '*.config']))"
        )
        getSkyframeExecutor()
            .invalidateFilesUnderPathForTesting(
                reporter,
                ModifiedFileSet.builder().modify(PathFragment.create("foo/BUILD")).build(),
                Root.fromPath(rootDirectory)
            )
        assertSrcs(validPackageoidWithoutErrors("foo"), "foo", "//foo:a.config", "//foo:b.txt")
        scratch.overwriteFile(
            "foo/BUILD", "filegroup(name = 'foo', srcs = glob(['*.txt', '*.config'])) # comment"
        )
        getSkyframeExecutor()
            .invalidateFilesUnderPathForTesting(
                reporter,
                ModifiedFileSet.builder().modify(PathFragment.create("foo/BUILD")).build(),
                Root.fromPath(rootDirectory)
            )
        assertSrcs(validPackageoidWithoutErrors("foo"), "foo", "//foo:a.config", "//foo:b.txt")
        getSkyframeExecutor().resetEvaluator()
        val packageOptions: PackageOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(PackageOptions::class.java)
        packageOptions.setDefaultVisibility(RuleVisibility.PUBLIC)
        packageOptions.setShowLoadingProgress(true)
        packageOptions.setGlobbingThreads(7)
        if (computationMode == ComputationMode.PACKAGE_FROM_PACKAGE_PIECES) {
            packageOptions.setLazyMacroExpansionPackages(PackageOptions.LazyMacroExpansionPackages.ALL)
        }
        getSkyframeExecutor()
            .preparePackageLoading(
                PathPackageLocator(
                    outputBase,
                    com.google.common.collect.ImmutableList.of<E?>(Root.fromPath(rootDirectory)),
                    BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
                ),
                packageOptions,
                parseBuildLanguageOptions(),
                UUID.randomUUID(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                QuiescingExecutorsImpl.forTesting(),
                tsgm
            )
        getSkyframeExecutor().injectExtraPrecomputedValues(analysisMock.precomputedValues)
        getSkyframeExecutor().setActionEnv(com.google.common.collect.ImmutableMap.of<K?, V?>())
        assertSrcs(validPackageoidWithoutErrors("foo"), "foo", "//foo:a.config", "//foo:b.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun globEscapesAt(@TestParameter computationMode: ComputationMode?) {
        scratch.file("foo/BUILD", "filegroup(name = 'foo', srcs = glob(['*.txt']))")
        scratch.file("foo/@f.txt")
        preparePackageLoading(computationMode, rootDirectory)
        assertSrcs(validPackageoidWithoutErrors("foo"), "foo", "//foo:@f.txt")

        scratch.overwriteFile("foo/BUILD", "filegroup(name = 'foo', srcs = glob(['*.txt'])) # comment")
        getSkyframeExecutor()
            .invalidateFilesUnderPathForTesting(
                reporter,
                ModifiedFileSet.builder().modify(PathFragment.create("foo/BUILD")).build(),
                Root.fromPath(rootDirectory)
            )
        assertSrcs(validPackageoidWithoutErrors("foo"), "foo", "//foo:@f.txt")
    }

    /**
     * Tests that a symlink to a file outside of the package root is handled consistently. If the
     * default behavior of Bazel was changed from `ExternalFileAction#DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS` to `ExternalFileAction#ASSUME_NON_EXISTENT_AND_IMMUTABLE_FOR_EXTERNAL_PATHS` then foo/link.sh
     * should no longer appear in the srcs of //foo:foo. However, either way the srcs should be the
     * same independent of the evaluation being incremental or clean.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobWithExternalSymlink(@TestParameter computationMode: ComputationMode?) {
        scratch.file(
            "foo/BUILD",
            """
        filegroup(
            name = "foo",
            srcs = glob(["*.sh"]),
        )

        filegroup(
            name = "bar",
            srcs = glob(["link.sh"]),
        )

        filegroup(
            name = "baz",
            srcs = glob(["subdir_link/*.txt"]),
        )
        
        """.trimIndent()
        )
        scratch.file("foo/ordinary.sh")
        val externalTarget: Path = scratch.file("../ops/target.txt")
        FileSystemUtils.ensureSymbolicLink(scratch.resolve("foo/link.sh"), externalTarget)
        FileSystemUtils.ensureSymbolicLink(
            scratch.resolve("foo/subdir_link"), externalTarget.getParentDirectory()
        )
        preparePackageLoading(computationMode, rootDirectory)
        val fooPkg: Packageoid = validPackageoidWithoutErrors("foo")
        assertSrcs(fooPkg, "foo", "//foo:link.sh", "//foo:ordinary.sh")
        assertSrcs(fooPkg, "bar", "//foo:link.sh")
        assertSrcs(fooPkg, "baz", "//foo:subdir_link/target.txt")
        scratch.overwriteFile(
            "foo/BUILD",
            """
        filegroup(
            name = "foo",
            srcs = glob(["*.sh"]),
        )  #comment

        filegroup(
            name = "bar",
            srcs = glob(["link.sh"]),
        )

        filegroup(
            name = "baz",
            srcs = glob(["subdir_link/*.txt"]),
        )
        
        """.trimIndent()
        )
        getSkyframeExecutor()
            .invalidateFilesUnderPathForTesting(
                reporter,
                ModifiedFileSet.builder().modify(PathFragment.create("foo/BUILD")).build(),
                Root.fromPath(rootDirectory)
            )
        val fooPkg2: Packageoid = validPackageoidWithoutErrors("foo")
        assertThat(fooPkg2).isNotEqualTo(fooPkg)
        assertSrcs(fooPkg2, "foo", "//foo:link.sh", "//foo:ordinary.sh")
        assertSrcs(fooPkg2, "bar", "//foo:link.sh")
        assertSrcs(fooPkg2, "baz", "//foo:subdir_link/target.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOneNewElementInMultipleGlob(@TestParameter computationMode: ComputationMode?) {
        scratch.file(
            "foo/BUILD",
            """
        filegroup(
            name = "foo",
            srcs = glob(
                ["*.sh"],
                allow_empty = True,
            ),
        )

        filegroup(
            name = "bar",
            srcs = glob(
                [
                    "*.sh",
                    "*.txt",
                ],
                allow_empty = True,
            ),
        )
        
        """.trimIndent()
        )
        preparePackageLoading(computationMode, rootDirectory)
        val pkg: Packageoid = validPackageoidWithoutErrors("foo")
        scratch.file("foo/irrelevant")
        getSkyframeExecutor()
            .invalidateFilesUnderPathForTesting(
                reporter,
                ModifiedFileSet.builder().modify(PathFragment.create("foo/irrelevant")).build(),
                Root.fromPath(rootDirectory)
            )
        assertThat(validPackageoidWithoutErrors("foo")).isSameInstanceAs(pkg)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoNewElementInMultipleGlob(@TestParameter computationMode: ComputationMode?) {
        scratch.file(
            "foo/BUILD",
            """
        filegroup(
            name = "foo",
            srcs = glob(
                [
                    "*.sh",
                    "*.txt",
                ],
                allow_empty = True,
            ),
        )

        filegroup(
            name = "bar",
            srcs = glob(
                [
                    "*.sh",
                    "*.txt",
                ],
                allow_empty = True,
            ),
        )
        
        """.trimIndent()
        )
        preparePackageLoading(computationMode, rootDirectory)
        val pkg: Packageoid = validPackageoidWithoutErrors("foo")
        scratch.file("foo/irrelevant")
        getSkyframeExecutor()
            .invalidateFilesUnderPathForTesting(
                reporter,
                ModifiedFileSet.builder().modify(PathFragment.create("foo/irrelevant")).build(),
                Root.fromPath(rootDirectory)
            )
        assertThat(validPackageoidWithoutErrors("foo")).isSameInstanceAs(pkg)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitiveStarlarkDepsStoredInPackage(
        @TestParameter computationMode: ComputationMode?
    ) {
        scratch.file("foo/BUILD", "load('//bar:ext.bzl', 'a')")
        scratch.file("bar/BUILD")
        scratch.file(
            "bar/ext.bzl",
            """
        load("//baz:ext.scl", "b")

        a = b
        
        """.trimIndent()
        )
        scratch.file("baz/BUILD")
        scratch.file("baz/ext.scl", "b = 1")
        scratch.file("qux/BUILD")
        scratch.file("qux/ext.bzl", "c = 1")

        preparePackageLoading(computationMode, rootDirectory)
        // must be done after preparePackageLoading()
        setBuildLanguageOptions("--experimental_enable_scl_dialect=true")

        var pkg: Packageoid = validPackageoidWithoutErrors("foo")
        assertThat(pkg.getDeclarations().getOrComputeTransitivelyLoadedStarlarkFiles())
            .containsExactly(
                Label.parseCanonical("//bar:ext.bzl"), Label.parseCanonical("//baz:ext.scl")
            )

        scratch.overwriteFile(
            "bar/ext.bzl",
            """
        load("//qux:ext.bzl", "c")

        a = c
        
        """.trimIndent()
        )
        getSkyframeExecutor()
            .invalidateFilesUnderPathForTesting(
                reporter,
                ModifiedFileSet.builder().modify(PathFragment.create("bar/ext.bzl")).build(),
                Root.fromPath(rootDirectory)
            )

        pkg = validPackageoidWithoutErrors("foo")
        assertThat(pkg.getDeclarations().getOrComputeTransitivelyLoadedStarlarkFiles())
            .containsExactly(
                Label.parseCanonical("//bar:ext.bzl"), Label.parseCanonical("//qux:ext.bzl")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonExistingStarlarkExtension(@TestParameter computationMode: ComputationMode?) {
        scratch.file("test/starlark/BUILD", "load('//test/starlark:bad_extension.bzl', 'some_symbol')")
        preparePackageLoading(computationMode)
        invalidatePackages()

        val ex: java.lang.Exception = evaluatePackageoidToException("test/starlark")
        Truth.assertThat(ex)
            .hasMessageThat()
            .isEqualTo(
                "error loading package 'test/starlark': "
                        + "cannot load '//test/starlark:bad_extension.bzl': no such file"
            )
        assertDetailedExitCode(
            ex, PackageLoading.Code.IMPORT_STARLARK_FILE_ERROR, ExitCode.BUILD_FAILURE
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonExistingStarlarkExtensionFromExtension(
        @TestParameter computationMode: ComputationMode?
    ) {
        scratch.file(
            "test/starlark/extension.bzl",
            """
        load("//test/starlark:bad_extension.bzl", "some_symbol")

        a = "a"
        
        """.trimIndent()
        )
        scratch.file("test/starlark/BUILD", "load('//test/starlark:extension.bzl', 'a')")
        preparePackageLoading(computationMode)
        invalidatePackages()

        val ex: java.lang.Exception = evaluatePackageoidToException("test/starlark")
        Truth.assertThat(ex)
            .hasMessageThat()
            .isEqualTo(
                ("error loading package 'test/starlark': "
                        + "at /workspace/test/starlark/extension.bzl:1:6: "
                        + "cannot load '//test/starlark:bad_extension.bzl': no such file")
            )
        assertDetailedExitCode(
            ex, PackageLoading.Code.IMPORT_STARLARK_FILE_ERROR, ExitCode.BUILD_FAILURE
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuiltinsInjectionFailure(@TestParameter computationMode: ComputationMode) {
        preparePackageLoadingWithCustomStarklarkSemanticsOptions(
            computationMode,
            parseBuildLanguageOptions("--experimental_builtins_bzl_path=tools/builtins_staging")
        )
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

        val ex: java.lang.Exception = evaluatePackageoidToException("pkg")
        Truth.assertThat(ex)
            .hasMessageThat()
            .isEqualTo(
                ("error loading package 'pkg': Internal error while loading Starlark builtins: Failed"
                        + " to load builtins sources: initialization of module 'exports.bzl' (internal)"
                        + " failed")
            )
        assertDetailedExitCode(
            ex, PackageLoading.Code.BUILTINS_INJECTION_FAILURE, ExitCode.BUILD_FAILURE
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinkCycleWithStarlarkExtension(@TestParameter computationMode: ComputationMode?) {
        val extensionFilePath: Path? = scratch.resolve("/workspace/test/starlark/extension.bzl")
        FileSystemUtils.ensureSymbolicLink(extensionFilePath, PathFragment.create("extension.bzl"))
        scratch.file("test/starlark/BUILD", "load('//test/starlark:extension.bzl', 'a')")
        preparePackageLoading(computationMode)
        invalidatePackages()

        val ex: java.lang.Exception = evaluatePackageoidToException("test/starlark")
        Truth.assertThat(ex)
            .hasMessageThat()
            .isEqualTo(
                "error loading package 'test/starlark': Encountered error while reading extension "
                        + "file 'test/starlark/extension.bzl': Symlink cycle"
            )
        assertDetailedExitCode(
            ex, PackageLoading.Code.IMPORT_STARLARK_FILE_ERROR, ExitCode.BUILD_FAILURE
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIOErrorLookingForSubpackageForLabelIsHandled(
        @TestParameter computationMode: ComputationMode?
    ) {
        scratch.file(
            "foo/BUILD",  //
            "filegroup(name = 'foo', srcs = ['bar/baz.sh'])"
        )
        val barBuildFile: Path = scratch.file("foo/bar/BUILD")
        fs.stubStatError(barBuildFile, IOException("nope"))
        preparePackageLoading(computationMode)

        evaluatePackageoidToException("foo")
        assertContainsEvent("nope")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadOK(@TestParameter computationMode: ComputationMode?) {
        scratch.file("p/a.bzl", "a = 1; b = 1; d = 1")
        scratch.file("p/subdir/a.bzl", "c = 1; e = 1")
        scratch.file(
            "p/BUILD",
            """
        load("//p:a.bzl", "d")
        load("//p:subdir/a.bzl", "e")
        load(":a.bzl", "a")
        load("a.bzl", "b")
        load("subdir/a.bzl", "c")
        
        """.trimIndent()
        )
        preparePackageLoading(computationMode)
        validPackageoidWithoutErrors("p")
    }

    // See WorkspaceFileFunctionTest for tests that exercise load('@repo...').
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadBadLabel(@TestParameter computationMode: ComputationMode?) {
        scratch.file("p/BUILD", "load('this\tis not a label', 'a')")
        preparePackageLoading(computationMode)
        reporter.removeHandler(failFastHandler)
        val key: SkyKey? = getSkyKey("p")
        SkyframeExecutorTestUtils.evaluate<T?>(skyframeExecutor, key,  /*keepGoing=*/false, reporter)
        assertContainsEvent(
            "in load statement: invalid target name 'this<?>is not a label': target names may not"
                    + " contain non-printable characters"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadFromExternalPackage(@TestParameter computationMode: ComputationMode?) {
        scratch.file("p/BUILD", "load('//external:file.bzl', 'a')")
        preparePackageLoading(computationMode)
        reporter.removeHandler(failFastHandler)
        val key: SkyKey? = getSkyKey("p")
        SkyframeExecutorTestUtils.evaluate<T?>(skyframeExecutor, key,  /*keepGoing=*/false, reporter)
        assertContainsEvent("Starlark files may not be loaded from the //external package")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadWithoutBzlSuffix(@TestParameter computationMode: ComputationMode?) {
        scratch.file("p/BUILD", "load('//p:file.starlark', 'a')")
        preparePackageLoading(computationMode)
        reporter.removeHandler(failFastHandler)
        val key: SkyKey? = getSkyKey("p")
        SkyframeExecutorTestUtils.evaluate<T?>(skyframeExecutor, key,  /*keepGoing=*/false, reporter)
        assertContainsEvent("The label must reference a file with extension \".bzl\"")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlVisibilityViolation(@TestParameter computationMode: ComputationMode) {
        preparePackageLoadingWithCustomStarklarkSemanticsOptions(
            computationMode, parseBuildLanguageOptions("--experimental_bzl_visibility=true")
        )

        scratch.file(
            "a/BUILD",  //
            "load(\"//b:foo.bzl\", \"x\")"
        )
        scratch.file("b/BUILD")
        scratch.file(
            "b/foo.bzl",
            """
        visibility("private")
        x = 1
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        val ex: java.lang.Exception = evaluatePackageoidToException("a")
        Truth.assertThat(ex)
            .hasMessageThat()
            .contains(
                "error loading package 'a': file //a:BUILD contains .bzl load visibility violations"
            )
        assertDetailedExitCode(
            ex, PackageLoading.Code.IMPORT_STARLARK_FILE_ERROR, ExitCode.BUILD_FAILURE
        )
        assertContainsEvent("Starlark file //b:foo.bzl is not visible for loading from package //a.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBzlVisibilityViolationDemotedToWarningWhenBreakGlassFlagIsSet(
        @TestParameter computationMode: ComputationMode
    ) {
        preparePackageLoadingWithCustomStarklarkSemanticsOptions(
            computationMode,
            parseBuildLanguageOptions(
                "--experimental_bzl_visibility=true", "--check_bzl_visibility=false"
            )
        )

        scratch.file(
            "a/BUILD",  //
            "load(\"//b:foo.bzl\", \"x\")"
        )
        scratch.file("b/BUILD")
        scratch.file(
            "b/foo.bzl",
            """
        visibility("private")
        x = 1
        
        """.trimIndent()
        )

        validPackageoidWithoutErrors("a")
        assertContainsEvent("Starlark file //b:foo.bzl is not visible for loading from package //a.")
        assertContainsEvent("Continuing because --nocheck_bzl_visibility is active")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVisibilityCallableNotAvailableInBUILD(
        @TestParameter computationMode: ComputationMode
    ) {
        preparePackageLoadingWithCustomStarklarkSemanticsOptions(
            computationMode, parseBuildLanguageOptions("--experimental_bzl_visibility=true")
        )

        scratch.file(
            "a/BUILD",  //
            "visibility(\"public\")"
        )

        reporter.removeHandler(failFastHandler)
        // The evaluation result ends up being null, probably due to the test framework swallowing
        // exceptions (similar to b/26382502). So let's just look for the error event instead of
        // asserting on the exception.
        SkyframeExecutorTestUtils.evaluate<T?>(
            getSkyframeExecutor(), getSkyKey("a"),  /* keepGoing= */false, reporter
        )
        assertContainsEvent("name 'visibility' is not defined")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVisibilityCallableErroneouslyInvokedInBUILD(
        @TestParameter computationMode: ComputationMode
    ) {
        preparePackageLoadingWithCustomStarklarkSemanticsOptions(
            computationMode, parseBuildLanguageOptions("--experimental_bzl_visibility=true")
        )

        scratch.file(
            "a/BUILD",
            """
        load(":helper.bzl", "helper")

        helper()
        
        """.trimIndent()
        )
        scratch.file(
            "a/helper.bzl",
            """
        def helper():
            visibility("public")
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        SkyframeExecutorTestUtils.evaluate<T?>(
            getSkyframeExecutor(), getSkyKey("a"),  /* keepGoing= */false, reporter
        )
        assertContainsEvent(
            "visibility() can only be used during .bzl initialization (top-level evaluation)"
        )
    }

    // Regression test for the two ugly consequences of a bug where GlobFunction incorrectly matched
    // dangling symlinks.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIncrementalSkyframeHybridGlobbingOnDanglingSymlink(
        @TestParameter computationMode: ComputationMode?
    ) {
        val packageDirPath: Path =
            scratch.file("foo/BUILD", "exports_files(glob(['*.txt']))").getParentDirectory()
        scratch.file("foo/existing.txt")
        FileSystemUtils.ensureSymbolicLink(packageDirPath.getChild("dangling.txt"), "nope")

        preparePackageLoading(computationMode, rootDirectory)

        val pkg: Packageoid = validPackageoidWithoutErrors("foo")
        assertThat(pkg.containsErrors()).isFalse()
        assertThat(pkg.getTarget("existing.txt").getName()).isEqualTo("existing.txt")
        org.junit.Assert.assertThrows<T?>(
            NoSuchTargetException::class.java,
            org.junit.function.ThrowingRunnable { pkg.getTarget("dangling.txt") })

        scratch.overwriteFile(
            "foo/BUILD",
            """
        exports_files(glob(["*.txt"]))
        #some-irrelevant-comment
        
        """.trimIndent()
        )

        getSkyframeExecutor()
            .invalidateFilesUnderPathForTesting(
                reporter,
                ModifiedFileSet.builder().modify(PathFragment.create("foo/BUILD")).build(),
                Root.fromPath(rootDirectory)
            )

        val pkg2: Packageoid = validPackageoidWithoutErrors("foo")
        assertThat(pkg2.containsErrors()).isFalse()
        assertThat(pkg2.getTarget("existing.txt").getName()).isEqualTo("existing.txt")
        org.junit.Assert.assertThrows<T?>(
            NoSuchTargetException::class.java,
            org.junit.function.ThrowingRunnable { pkg2.getTarget("dangling.txt") })

        // One consequence of the bug was that dangling symlinks were matched by globs evaluated by
        // Skyframe globbing, meaning there would incorrectly be corresponding targets in packages
        // that had skyframe cache hits during skyframe hybrid globbing.
        scratch.file("foo/nope")
        getSkyframeExecutor()
            .invalidateFilesUnderPathForTesting(
                reporter,
                ModifiedFileSet.builder().modify(PathFragment.create("foo/nope")).build(),
                Root.fromPath(rootDirectory)
            )

        val newPkg: Packageoid = validPackageoidWithoutErrors("foo")
        assertThat(newPkg.containsErrors()).isFalse()
        assertThat(newPkg.getTarget("existing.txt").getName()).isEqualTo("existing.txt")
        // Another consequence of the bug is that change pruning would incorrectly cut off changes that
        // caused a dangling symlink potentially matched by a glob to come into existence.
        assertThat(newPkg.getTarget("dangling.txt").getName()).isEqualTo("dangling.txt")
        assertThat(newPkg).isNotSameInstanceAs(pkg)
    }

    // Regression test for Skyframe globbing incorrectly matching the package's directory path on
    // 'glob(['**'], exclude_directories = 0)'. We test for this directly by triggering
    // hybrid globbing (gives coverage for both non-skyframe globbing and skyframe globbing).
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRecursiveGlobNeverMatchesPackageDirectory(
        @TestParameter computationMode: ComputationMode?
    ) {
        scratch.file(
            "foo/BUILD",
            "[filegroup(name = x + '-matched') for x in glob(['**'], exclude_directories = 0)]"
        )
        scratch.file("foo/bar")
        preparePackageLoading(computationMode, rootDirectory)

        val pkg: Packageoid = validPackageoidWithoutErrors("foo")
        assertThat(pkg.containsErrors()).isFalse()
        assertThat(pkg.getTarget("bar-matched").getName()).isEqualTo("bar-matched")
        org.junit.Assert.assertThrows<T?>(
            NoSuchTargetException::class.java,
            org.junit.function.ThrowingRunnable { pkg.getTarget("-matched") })

        scratch.overwriteFile(
            "foo/BUILD",
            """
        [filegroup(name = x + "-matched") for x in glob(
            ["**"],
            exclude_directories = 0,
        )]
        #some-irrelevant-comment
        
        """.trimIndent()
        )
        getSkyframeExecutor()
            .invalidateFilesUnderPathForTesting(
                reporter,
                ModifiedFileSet.builder().modify(PathFragment.create("foo/BUILD")).build(),
                Root.fromPath(rootDirectory)
            )

        val pkg2: Packageoid = validPackageoidWithoutErrors("foo")
        assertThat(pkg2.containsErrors()).isFalse()
        assertThat(pkg2.getTarget("bar-matched").getName()).isEqualTo("bar-matched")
        org.junit.Assert.assertThrows<T?>(
            NoSuchTargetException::class.java,
            org.junit.function.ThrowingRunnable { pkg2.getTarget("-matched") })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageLoadingErrorOnIOExceptionReadingBuildFile(
        @TestParameter computationMode: ComputationMode?
    ) {
        val fooBuildFilePath: Path = scratch.file("foo/BUILD")
        val exn: IOException = IOException("nope")
        fs.throwExceptionOnGetInputStream(fooBuildFilePath, exn)
        preparePackageLoading(computationMode)

        val ex: java.lang.Exception = evaluatePackageoidToException("foo")
        Truth.assertThat(ex).hasMessageThat().contains("nope")
        Truth.assertThat(ex).isInstanceOf(NoSuchPackageException::class.java)
        Truth.assertThat(ex).hasCauseThat().isInstanceOf(IOException::class.java)
        assertDetailedExitCode(ex, PackageLoading.Code.BUILD_FILE_MISSING, ExitCode.BUILD_FAILURE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageLoadingErrorOnMissingBuildFile_singlePackagePath(
        @TestParameter computationMode: ComputationMode?
    ) {
        scratch.file("foo/bar")
        preparePackageLoading(computationMode)

        // There is no foo/BUILD file, but we enforce loading package 'foo'.
        val ex: java.lang.Exception = evaluatePackageoidToException("foo")
        Truth.assertThat(ex)
            .hasMessageThat()
            .contains(
                ("BUILD file not found in any of the following directories. "
                        + "Add a BUILD file to a directory to mark it as a package.\n" // Print the package_path relative directory path if only a single `package_path` is
                        // provided.
                        + " - foo")
            )
        Truth.assertThat(ex).isInstanceOf(BuildFileNotFoundException::class.java)
        assertDetailedExitCode(ex, PackageLoading.Code.BUILD_FILE_MISSING, ExitCode.BUILD_FAILURE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageLoadingErrorOnMissingBuildFile_multiplePackagePath(
        @TestParameter computationMode: ComputationMode?
    ) {
        scratch.file("foo/bar")
        val otherRootDir: Path? = scratch.dir("/ws2")
        scratch.file("/ws2/foo/bar")
        preparePackageLoading(computationMode, rootDirectory, otherRootDir)

        // There is no foo/BUILD file under both `package_path`s foo directory, but we enforce loading
        // package 'foo'.
        val ex: java.lang.Exception = evaluatePackageoidToException("foo")

        Truth.assertThat(ex)
            .hasMessageThat()
            .contains(
                "BUILD file not found in any of the following directories. "
                        + "Add a BUILD file to a directory to mark it as a package."
            )
        // Print the absolute directory paths if multiple `package_path`s are provided.
        Truth.assertThat(ex).hasMessageThat().contains("- /workspace/foo")
        Truth.assertThat(ex).hasMessageThat().contains("- /ws2/foo")
        Truth.assertThat(ex).isInstanceOf(BuildFileNotFoundException::class.java)
        assertDetailedExitCode(ex, PackageLoading.Code.BUILD_FILE_MISSING, ExitCode.BUILD_FAILURE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageLoadingErrorOnIOExceptionReadingBzlFile(
        @TestParameter computationMode: ComputationMode?
    ) {
        scratch.file("foo/BUILD", "load('//foo:bzl.bzl', 'x')")
        val fooBzlFilePath: Path = scratch.file("foo/bzl.bzl")
        val exn: IOException = IOException("nope")
        fs.throwExceptionOnGetInputStream(fooBzlFilePath, exn)
        preparePackageLoading(computationMode)

        val ex: java.lang.Exception = evaluatePackageoidToException("foo")
        Truth.assertThat(ex).hasMessageThat().contains("nope")
        Truth.assertThat(ex).isInstanceOf(NoSuchPackageException::class.java)
        Truth.assertThat(ex).hasCauseThat().isInstanceOf(IOException::class.java)
        assertDetailedExitCode(
            ex, PackageLoading.Code.IMPORT_STARLARK_FILE_ERROR, ExitCode.BUILD_FAILURE
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelsCrossesSubpackageBoundaries_singleSubpackageCrossing(
        @TestParameter computationMode: ComputationMode?
    ) {
        reporter.removeHandler(failFastHandler)

        scratch.file("pkg/foo/BUILD", "exports_files(['sub/bar/blah'])")
        scratch.file("pkg/foo/sub/BUILD")
        preparePackageLoading(computationMode)
        invalidatePackages()

        val skyKey: SkyKey? = getSkyKey("pkg/foo")
        val result: EvaluationResult<PackageoidValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), skyKey,  /* keepGoing= */false, reporter
            )
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        assertThat(result.get(skyKey).packageoid.containsErrors()).isTrue()
        assertContainsEvent(
            "Label '//pkg/foo:sub/bar/blah' is invalid because 'pkg/foo/sub' is a subpackage; perhaps"
                    + " you meant to put the colon here: '//pkg/foo/sub:bar/blah'?"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelsCrossesSubpackageBoundaries_complexSubpackageCrossing(
        @TestParameter computationMode: ComputationMode
    ) {
        reporter.removeHandler(failFastHandler)

        scratch.file(
            "pkg/foo/BUILD",
            """
        exports_files(["sub11/sub12/blah1"])

        exports_files(["sub21/sub22/blah2"])
        
        """.trimIndent()
        )
        scratch.file("pkg/foo/sub11/BUILD")
        scratch.file("pkg/foo/sub11/sub12/BUILD")
        scratch.file("pkg/foo/sub21/BUILD")
        scratch.file("pkg/foo/sub21/sub22/BUILD")
        preparePackageLoading(computationMode)
        invalidatePackages()

        val skyKey: SkyKey? = getSkyKey("pkg/foo")
        val result: EvaluationResult<PackageoidValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), skyKey,  /* keepGoing= */false, reporter
            )
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        assertThat(result.get(skyKey).packageoid.containsErrors()).isTrue()

        // Only the deepest package that crosses subpackage boundary should be displayed in the error
        // message.
        assertContainsEvent(
            ("Label '//pkg/foo:sub11/sub12/blah1' is invalid because 'pkg/foo/sub11/sub12' is a"
                    + " subpackage; perhaps you meant to put the colon here:"
                    + " '//pkg/foo/sub11/sub12:blah1'?")
        )
        assertContainsEvent(
            ("Label '//pkg/foo:sub21/sub22/blah2' is invalid because 'pkg/foo/sub21/sub22' is a"
                    + " subpackage; perhaps you meant to put the colon here:"
                    + " '//pkg/foo/sub21/sub22:blah2'?")
        )
        if (computationMode == ComputationMode.PACKAGE_FROM_PACKAGE_PIECES) {
            assertContainsEvent("error in top-level package piece defined by //pkg/foo:BUILD")
            assertThat(eventCollector.filtered(com.google.devtools.build.lib.events.EventKind.ERROR)).hasSize(3)
        } else {
            assertThat(eventCollector.filtered(com.google.devtools.build.lib.events.EventKind.ERROR)).hasSize(2)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinkCycleEncounteredWhileHandlingLabelCrossingSubpackageBoundaries(
        @TestParameter computationMode: ComputationMode?
    ) {
        scratch.file("pkg/BUILD", "exports_files(['sub/blah'])")
        val subBuildFilePath: Path? = scratch.dir("pkg/sub").getChild("BUILD")
        FileSystemUtils.ensureSymbolicLink(subBuildFilePath, subBuildFilePath)
        preparePackageLoading(computationMode)
        invalidatePackages()

        val ex: java.lang.Exception = evaluatePackageoidToException("pkg")
        Truth.assertThat(ex).isInstanceOf(BuildFileNotFoundException::class.java)
        Truth.assertThat(ex)
            .hasMessageThat()
            .contains(
                "no such package 'pkg/sub': Symlink cycle detected while trying to find BUILD file"
            )
        assertContainsEvent("circular symlinks detected")
    }

    // Regression test for b/206459361.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nonSkyframeGlobbingIOException_andLabelCrossingSubpackageBoundaries_withKeepGoing(
        @TestParameter computationMode: ComputationMode?
    ) {
        reporter.removeHandler(failFastHandler)

        // When a package's BUILD file and the relevant filesystem state is such that non-Skyframe
        // globbing will encounter an IOException due to a directory symlink cycle *and* the BUILD file
        // defines a target with a label that crosses subpackage boundaries,
        val pkgBUILDPath: Path =
            scratch.file(
                "pkg/BUILD",
                """
            exports_files(["sub/blah"])  # label crossing subpackage boundaries

            glob(["globcycle/**/foo.txt"])  # triggers non-Skyframe globbing error
            
            """.trimIndent()
            )
        scratch.file("pkg/sub/BUILD")
        val pkgGlobcyclePath: Path = pkgBUILDPath.getParentDirectory().getChild("globcycle")
        FileSystemUtils.ensureSymbolicLink(pkgGlobcyclePath, pkgGlobcyclePath)
        org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { pkgGlobcyclePath.statIfFound(Symlinks.FOLLOW) })
        preparePackageLoading(computationMode)
        invalidatePackages()

        // ... and we evaluate the package with keepGoing == true, we expect the evaluation to fail with
        // the non-Skyframe globbing error, but for the label crossing event to *not* get added (because
        // the globbing IOException would put Package.Builder in a state on which we cannot run
        // handleLabelsCrossingSubpackagesAndPropagateInconsistentFilesystemExceptions).
        val pkgKey: SkyKey? = getSkyKey("pkg")
        val result: EvaluationResult<PackageoidValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), pkgKey,  /* keepGoing= */true, reporter
            )
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(pkgKey)
            .hasExceptionThat()
            .isInstanceOf(NoSuchPackageException::class.java)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(pkgKey)
            .hasExceptionThat()
            .hasMessageThat()
            .contains("Symlink cycle: /workspace/pkg/globcycle")
        assertDoesNotContainEvent(
            "Label '//pkg:sub/blah' is invalid because 'pkg/sub' is a subpackage"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobAllowEmpty_paramValueMustBeBoolean(
        @TestParameter computationMode: ComputationMode?
    ) {
        reporter.removeHandler(failFastHandler)

        scratch.file("pkg/BUILD", "x = " + "glob(['*.foo'], allow_empty = 5)")
        preparePackageLoading(computationMode)
        invalidatePackages()

        validPackageoid("pkg")

        assertContainsEvent("expected boolean for argument `allow_empty`, got `5`")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobAllowEmpty_functionParam(@TestParameter computationMode: ComputationMode?) {
        scratch.file("pkg/BUILD", "x = " + "glob(['*.foo'], allow_empty=True)")
        preparePackageLoading(computationMode)
        invalidatePackages()

        val pkg: Packageoid = validPackageoid("pkg")
        assertThat(pkg.containsErrors()).isFalse()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobAllowEmpty_starlarkOption(@TestParameter computationMode: ComputationMode?) {
        preparePackageLoadingWithCustomStarklarkSemanticsOptions(
            computationMode!!,
            parseBuildLanguageOptions("--incompatible_disallow_empty_glob=false"),
            rootDirectory
        )

        scratch.file("pkg/BUILD", "x = " + "glob(['*.foo'])")
        invalidatePackages()

        val pkg: Packageoid = validPackageoid("pkg")
        assertThat(pkg.containsErrors()).isFalse()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobDisallowEmpty_functionParam_wasNonEmptyAndBecomesEmpty(
        @TestParameter computationMode: ComputationMode?
    ) {
        scratch.file("pkg/BUILD", "x = " + "glob(['*.foo'], allow_empty=False)")
        scratch.file("pkg/blah.foo")
        preparePackageLoading(computationMode)
        invalidatePackages()

        var pkg: Packageoid = validPackageoid("pkg")
        assertThat(pkg.containsErrors()).isFalse()
        assertNoEvents()

        scratch.deleteFile("pkg/blah.foo")
        getSkyframeExecutor()
            .invalidateFilesUnderPathForTesting(
                reporter,
                ModifiedFileSet.builder().modify(PathFragment.create("pkg/blah.foo")).build(),
                Root.fromPath(rootDirectory)
            )

        reporter.removeHandler(failFastHandler)
        pkg = validPackageoid("pkg")
        assertThat(pkg.containsErrors()).isTrue()
        assertContainsEvent(
            "glob pattern '*.foo' didn't match anything, but allow_empty is set to False (the "
                    + "default value of allow_empty can be set with --incompatible_disallow_empty_glob)."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobDisallowEmpty_starlarkOption_wasNonEmptyAndBecomesEmpty(
        @TestParameter computationMode: ComputationMode?
    ) {
        preparePackageLoadingWithCustomStarklarkSemanticsOptions(
            computationMode!!,
            parseBuildLanguageOptions("--incompatible_disallow_empty_glob=true"),
            rootDirectory
        )

        scratch.file("pkg/BUILD", "x = " + "glob(['*.foo'])")
        scratch.file("pkg/blah.foo")
        invalidatePackages()

        var pkg: Packageoid = validPackageoid("pkg")
        assertThat(pkg.containsErrors()).isFalse()
        assertNoEvents()

        scratch.deleteFile("pkg/blah.foo")
        getSkyframeExecutor()
            .invalidateFilesUnderPathForTesting(
                reporter,
                ModifiedFileSet.builder().modify(PathFragment.create("pkg/blah.foo")).build(),
                Root.fromPath(rootDirectory)
            )

        reporter.removeHandler(failFastHandler)
        pkg = validPackageoid("pkg")
        assertThat(pkg.containsErrors()).isTrue()
        assertContainsEvent(
            "glob pattern '*.foo' didn't match anything, but allow_empty is set to False (the "
                    + "default value of allow_empty can be set with --incompatible_disallow_empty_glob)."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobDisallowEmpty_functionParam_wasEmptyAndStaysEmpty(
        @TestParameter computationMode: ComputationMode?
    ) {
        scratch.file("pkg/BUILD", "x = " + "glob(['*.foo'], allow_empty=False)")
        preparePackageLoading(computationMode)
        invalidatePackages()
        reporter.removeHandler(failFastHandler)

        var pkg: Packageoid = validPackageoid("pkg")
        assertThat(pkg.containsErrors()).isTrue()
        val expectedEventString =
            ("glob pattern '*.foo' didn't match anything, but allow_empty is set to False (the "
                    + "default value of allow_empty can be set with --incompatible_disallow_empty_glob).")
        assertContainsEvent(expectedEventString)

        scratch.overwriteFile("pkg/BUILD", "x = " + "glob(['*.foo'], allow_empty=False) #comment")
        getSkyframeExecutor()
            .invalidateFilesUnderPathForTesting(
                reporter,
                ModifiedFileSet.builder().modify(PathFragment.create("pkg/BUILD")).build(),
                Root.fromPath(rootDirectory)
            )

        pkg = validPackageoid("pkg")
        assertThat(pkg.containsErrors()).isTrue()
        assertContainsEvent(expectedEventString)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobDisallowEmpty_starlarkOption_wasEmptyAndStaysEmpty(
        @TestParameter computationMode: ComputationMode?
    ) {
        preparePackageLoadingWithCustomStarklarkSemanticsOptions(
            computationMode!!,
            parseBuildLanguageOptions("--incompatible_disallow_empty_glob=true"),
            rootDirectory
        )

        scratch.file("pkg/BUILD", "x = " + "glob(['*.foo'])")
        invalidatePackages()

        reporter.removeHandler(failFastHandler)

        var pkg: Packageoid = validPackageoid("pkg")
        assertThat(pkg.containsErrors()).isTrue()
        val expectedEventString =
            ("glob pattern '*.foo' didn't match anything, but allow_empty is set to False (the "
                    + "default value of allow_empty can be set with --incompatible_disallow_empty_glob).")
        assertContainsEvent(expectedEventString)

        scratch.overwriteFile("pkg/BUILD", "x = " + "glob(['*.foo']) #comment")
        getSkyframeExecutor()
            .invalidateFilesUnderPathForTesting(
                reporter,
                ModifiedFileSet.builder().modify(PathFragment.create("pkg/BUILD")).build(),
                Root.fromPath(rootDirectory)
            )

        pkg = validPackageoid("pkg")
        assertThat(pkg.containsErrors()).isTrue()
        assertContainsEvent(expectedEventString)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobDisallowEmpty_functionParam_wasEmptyDueToExcludeAndStaysEmpty(
        @TestParameter computationMode: ComputationMode?
    ) {
        scratch.file("pkg/BUILD", "x = glob(include=['*.foo'], exclude=['blah.*'], allow_empty=False)")
        scratch.file("pkg/blah.foo")
        preparePackageLoading(computationMode)
        invalidatePackages()

        reporter.removeHandler(failFastHandler)

        var pkg: Packageoid = validPackageoid("pkg")
        assertThat(pkg.containsErrors()).isTrue()
        val expectedEventString =
            ("all files in the glob have been excluded, but allow_empty is set to False (the "
                    + "default value of allow_empty can be set with --incompatible_disallow_empty_glob).")
        assertContainsEvent(expectedEventString)

        scratch.overwriteFile(
            "pkg/BUILD",
            "x = glob(include=['*.foo'], exclude=['blah.*'], allow_empty=False) # comment"
        )
        getSkyframeExecutor()
            .invalidateFilesUnderPathForTesting(
                reporter,
                ModifiedFileSet.builder().modify(PathFragment.create("pkg/BUILD")).build(),
                Root.fromPath(rootDirectory)
            )

        pkg = validPackageoid("pkg")
        assertThat(pkg.containsErrors()).isTrue()
        assertContainsEvent(expectedEventString)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobDisallowEmpty_starlarkOption_wasEmptyDueToExcludeAndStaysEmpty(
        @TestParameter computationMode: ComputationMode?
    ) {
        preparePackageLoadingWithCustomStarklarkSemanticsOptions(
            computationMode!!,
            parseBuildLanguageOptions("--incompatible_disallow_empty_glob=true"),
            rootDirectory
        )

        scratch.file("pkg/BUILD", "x = glob(include=['*.foo'], exclude=['blah.*'])")
        scratch.file("pkg/blah.foo")
        invalidatePackages()

        reporter.removeHandler(failFastHandler)

        var pkg: Packageoid = validPackageoid("pkg")
        assertThat(pkg.containsErrors()).isTrue()
        val expectedEventString =
            ("all files in the glob have been excluded, but allow_empty is set to False (the "
                    + "default value of allow_empty can be set with --incompatible_disallow_empty_glob).")
        assertContainsEvent(expectedEventString)

        scratch.overwriteFile("pkg/BUILD", "x = glob(include=['*.foo'], exclude=['blah.*']) # comment")
        getSkyframeExecutor()
            .invalidateFilesUnderPathForTesting(
                reporter,
                ModifiedFileSet.builder().modify(PathFragment.create("pkg/BUILD")).build(),
                Root.fromPath(rootDirectory)
            )

        pkg = validPackageoid("pkg")
        assertThat(pkg.containsErrors()).isTrue()
        assertContainsEvent(expectedEventString)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobDisallowEmpty_functionParam_wasEmptyAndBecomesNonEmpty(
        @TestParameter computationMode: ComputationMode?
    ) {
        scratch.file("pkg/BUILD", "x = " + "glob(['*.foo'], allow_empty=False)")
        preparePackageLoading(computationMode)
        invalidatePackages()

        reporter.removeHandler(failFastHandler)
        var pkg: Packageoid = validPackageoid("pkg")
        assertThat(pkg.containsErrors()).isTrue()
        assertContainsEvent(
            "glob pattern '*.foo' didn't match anything, but allow_empty is set to False (the "
                    + "default value of allow_empty can be set with --incompatible_disallow_empty_glob)."
        )

        scratch.file("pkg/blah.foo")
        getSkyframeExecutor()
            .invalidateFilesUnderPathForTesting(
                reporter,
                ModifiedFileSet.builder().modify(PathFragment.create("pkg/blah.foo")).build(),
                Root.fromPath(rootDirectory)
            )

        reporter.addHandler(failFastHandler)
        eventCollector.clear()
        pkg = validPackageoid("pkg")
        assertThat(pkg.containsErrors()).isFalse()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobDisallowEmpty_starlarkOption_wasEmptyAndBecomesNonEmpty(
        @TestParameter computationMode: ComputationMode?
    ) {
        preparePackageLoadingWithCustomStarklarkSemanticsOptions(
            computationMode!!,
            parseBuildLanguageOptions("--incompatible_disallow_empty_glob=true"),
            rootDirectory
        )

        scratch.file("pkg/BUILD", "x = " + "glob(['*.foo'])")
        invalidatePackages()

        reporter.removeHandler(failFastHandler)
        var pkg: Packageoid = validPackageoid("pkg")
        assertThat(pkg.containsErrors()).isTrue()

        assertContainsEvent(
            "glob pattern '*.foo' didn't match anything, but allow_empty is set to False (the "
                    + "default value of allow_empty can be set with --incompatible_disallow_empty_glob)."
        )

        scratch.file("pkg/blah.foo")
        getSkyframeExecutor()
            .invalidateFilesUnderPathForTesting(
                reporter,
                ModifiedFileSet.builder().modify(PathFragment.create("pkg/blah.foo")).build(),
                Root.fromPath(rootDirectory)
            )

        reporter.addHandler(failFastHandler)
        eventCollector.clear()
        pkg = validPackageoid("pkg")
        assertThat(pkg.containsErrors()).isFalse()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageRecordsLoadedModules(@TestParameter computationMode: ComputationMode?) {
        scratch.file("p/BUILD", "load('a.bzl', 'a'); load(':b.bzl', 'b')")
        scratch.file("p/a.bzl", "load('c.bzl', 'c'); a = c")
        scratch.file("p/b.bzl", "load(':c.bzl', 'c'); b = c")
        scratch.file("p/c.bzl", "load(':d.bzl', 'd'); c = d")
        scratch.file("p/d.bzl", "d = 0")

        // load p
        preparePackageLoading(computationMode, rootDirectory)
        val p: Packageoid = validPackageoidWithoutErrors("p")

        Truth.assertThat(toStrings(p.getDeclarations().getOrComputeTransitivelyLoadedStarlarkFiles()))
            .containsExactly("//p:a.bzl", "//p:b.bzl", "//p:c.bzl", "//p:d.bzl")
        assertThat(p.getDeclarations().countTransitivelyLoadedStarlarkFiles()).isEqualTo(4)

        // Custom visitation: c.bzl is visited twice, but the second time we don't recurse, so d.bzl is
        // only visited once.
        val loads: com.google.common.collect.Multiset<Label?> = com.google.common.collect.HashMultiset.create<Label?>()
        p.getDeclarations().visitLoadGraph({ load -> loads.add(load, 1) == 0 })
        Truth.assertThat(toStrings(loads))
            .containsExactly("//p:a.bzl", "//p:b.bzl", "//p:c.bzl", "//p:c.bzl", "//p:d.bzl")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun veryBrokenPackagePostsDoneToProgressReceiver(
        @TestParameter computationMode: ComputationMode?
    ) {
        reporter.removeHandler(failFastHandler)

        // Note: syntax error (recovered), non-existent .bzl file.
        scratch.file("pkg/BUILD", "load('//does_not:exist.bzl', 'broken'")
        preparePackageLoading(computationMode)

        val key: SkyKey? = getSkyKey("pkg")
        val result: EvaluationResult<PackageoidValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(getSkyframeExecutor(), key, false, reporter)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasErrorEntryForKeyThat(key)
        assertContainsEvent("syntax error at 'newline': expected ,")
        assertThat(getSkyframeExecutor().getPackageProgressReceiver().progressState())
            .isEqualTo(Pair("1 packages loaded", ""))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonSkyframeGlobbingEncountersSymlinkCycleAndThrowsIOException(
        @TestParameter computationMode: ComputationMode?
    ) {
        reporter.removeHandler(failFastHandler)

        // When a package's BUILD file and the relevant filesystem state is such that non-Skyframe
        // globbing will encounter an IOException due to a directory symlink cycle,
        val fooBUILDPath: Path = scratch.file("foo/BUILD", "glob(['cycle/**/foo.txt'])")
        val fooCyclePath: Path = fooBUILDPath.getParentDirectory().getChild("cycle")
        FileSystemUtils.ensureSymbolicLink(fooCyclePath, fooCyclePath)
        val ioExnFromFS: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { fooCyclePath.statIfFound(Symlinks.FOLLOW) })
        // And it is indeed the case that the FileSystem throws an IOException when the cycle's Path is
        // stat'd (following symlinks, as non-Skyframe globbing does).
        Truth.assertThat(ioExnFromFS).hasMessageThat().contains("Too many levels of symbolic links")
        preparePackageLoading(computationMode)

        // Then, when we evaluate the PackageValue node for the Package in keepGoing mode,
        val pkgKey: SkyKey? = getSkyKey("foo")
        var result: EvaluationResult<PackageoidValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), pkgKey,  /* keepGoing= */true, reporter
            )
        // The result is a *non-transient* Skyframe error.
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasErrorEntryForKeyThat(pkgKey)
            .isNotTransient()
        // And that error is a NoSuchPackageException
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(pkgKey)
            .hasExceptionThat()
            .isInstanceOf(NoSuchPackageException::class.java)
        // With a useful error message,
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(pkgKey)
            .hasExceptionThat()
            .hasMessageThat()
            .contains("Symlink cycle: /workspace/foo/cycle")
        // And appropriate Skyframe root cause (N.B. since we want PackageFunction to rethrow in
        // situations like this, we want the PackageValue node to be its own root cause).
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasErrorEntryForKeyThat(pkgKey)

        // Then, when we modify the BUILD file so as to force package loading,
        scratch.overwriteFile(
            "foo/BUILD", "glob(['cycle/**/foo.txt']) # dummy comment to force package loading"
        )

        if (!globUnderSingleDep) {
            // When globbing strategy is SKYFRAME_HYBRID (globUnderSingleDep = false), and we don't make
            // any filesystem changes that would invalidate the GlobValues, PackageFunction will observe
            // cache hits from Skyframe globbing.
            //
            // And we also have our filesystem blow up if the directory symlink cycle is encountered
            // (thus, the absence of a crash indicates the lack of non-Skyframe globbing).
            //
            // However, when globbing strategy is GLOBS (globUnderSingleDep = true), and we lose Skyframe
            // Hybrid globbing, we expect package reloading still to always do non-Skyframe globbing which
            // calls stats for symlink `foo/cycle`.
            fs.stubStatError(
                fooCyclePath,
                object : IOException() {
                    override fun getMessage(): String? {
                        throw java.lang.IllegalStateException("shouldn't get here!")
                    }
                })
        }

        // And we evaluate the PackageValue node for the Package in keepGoing mode,
        getSkyframeExecutor()
            .invalidateFilesUnderPathForTesting(
                reporter,
                ModifiedFileSet.builder().modify(PathFragment.create("foo/BUILD")).build(),
                Root.fromPath(rootDirectory)
            )
        // The results are exactly the same as before,
        result =
            SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), pkgKey,  /*keepGoing=*/true, reporter
            )
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasErrorEntryForKeyThat(pkgKey)
            .isNotTransient()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(pkgKey)
            .hasExceptionThat()
            .isInstanceOf(NoSuchPackageException::class.java)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(pkgKey)
            .hasExceptionThat()
            .hasMessageThat()
            .contains("Symlink cycle: /workspace/foo/cycle")
        // Thus showing that clean and incremental package loading have the same semantics in the
        // presence of a symlink cycle encountered during glob evaluation.
    }

    @org.junit.Test // The test checks direct deps of the PackageValue, so testing in PACKAGE_FROM_PACKAGE_PIECES mode
    // doesn't make sense.
    @Throws(java.lang.Exception::class)
    fun testGlobbingSkyframeDependencyStructure(
        @TestParameter("MONOLITHIC_PACKAGE", "PACKAGE_PIECE_FOR_BUILD_FILE") computationMode: ComputationMode?
    ) {
        reporter.removeHandler(failFastHandler)

        val pkgRoot: Root? = getSkyframeExecutor().getPackagePathEntries().getFirst()

        val fooBuildPath: Path =
            scratch.file("foo/BUILD", "glob(['dir/*.sh'])", "subpackages(include = ['subpkg/**'])")

        val fooDirPath: Path? = fooBuildPath.getParentDirectory().getChild("dir")
        scratch.file("foo/dir/bar.sh")
        scratch.file("foo/dir/baz.sh")

        val fooSubpkgPath: Path? = fooBuildPath.getParentDirectory().getChild("subpkg")
        scratch.file("foo/subpkg/BUILD")
        preparePackageLoading(computationMode)

        val pkgKey: SkyKey? = getSkyKey("foo")
        SkyframeExecutorTestUtils.evaluate<T?>(
            getSkyframeExecutor(), pkgKey,  /* keepGoing= */true, reporter
        )

        val graph: InMemoryGraph = getSkyframeExecutor().memoizingEvaluator.getInMemoryGraph()
        val packageNode: InMemoryNodeEntry = graph.getIfPresent(pkgKey)
        if (globUnderSingleDep) {
            // The package subgraph with single Globs node looks like this:
            // PKG["foo"]
            //  |- GLOBS[["dir/*.sh", FILES], ["subpkg/**", SUBPACKAGES]]
            //      |- FILE["foo/dir"]
            //      |- DIRECTORY_LISTING["foo/dir"]
            //      |- PACKAGE_LOOKUP["foo/dir"]
            //      |- FILE["foo/subdir"]
            //      |- PACKAGE_LOOKUP["foo/subdir"]
            val globsKey: GlobsValue.Key? =
                GlobsValue.key(
                    PackageIdentifier.createInMainRepo("foo"),
                    pkgRoot,
                    com.google.common.collect.ImmutableSet.of<E?>(
                        GlobRequest.create("dir/*.sh", Operation.FILES),
                        GlobRequest.create("subpkg/**", Operation.SUBPACKAGES)
                    )
                )
            com.google.common.truth.Subject.contains(globsKey)

            val globsNode: InMemoryNodeEntry = graph.getIfPresent(globsKey)
            val globsValue: SkyValue = globsNode.value
            assertThat(globsValue).isInstanceOf(GlobsValue::class.java)
            assertThat((globsValue as GlobsValue).getMatches())
                .containsExactly(
                    PathFragment.create("subpkg"),
                    PathFragment.create("dir/bar.sh"),
                    PathFragment.create("dir/baz.sh")
                )
            val globsDirectDeps: com.google.common.collect.ImmutableSet<SkyKey?> =
                com.google.common.collect.ImmutableSet.copyOf(globsNode.directDeps)
            Truth.assertThat(globsDirectDeps)
                .containsAtLeast(
                    DirectoryListingValue.key(RootedPath.toRootedPath(pkgRoot, fooDirPath)),
                    FileValue.key(RootedPath.toRootedPath(pkgRoot, fooDirPath)),
                    FileValue.key(RootedPath.toRootedPath(pkgRoot, fooSubpkgPath)),
                    PackageLookupValue.key(PackageIdentifier.createInMainRepo("foo/dir")),
                    PackageLookupValue.key(PackageIdentifier.createInMainRepo("foo/subpkg"))
                )
        } else {
            // The package subgraph with multiple Glob nodes looks like this:
            // PKG["foo"]
            //  |- GLOB["dir/*.sh", FILES]
            //      |- FILE["foo/dir"]
            //      |- DIRECTORY_LISTING["foo/dir"]
            //      |- PACKAGE_LOOKUP["foo/dir"]
            //  |- GLOB["subpkg/**", SUBPACKAGES]
            //      |- FILE["foo/subdir"]
            //      |- PACKAGE_LOOKUP["foo/subdir"]
            val dirGlobDescriptor: GlobDescriptor? =
                GlobValue.key(
                    PackageIdentifier.createInMainRepo("foo"),
                    pkgRoot,  /* pattern= */
                    "dir/*.sh",
                    Operation.FILES,
                    PathFragment.EMPTY_FRAGMENT
                )
            val subdirGlobDescriptor: GlobDescriptor? =
                GlobValue.key(
                    PackageIdentifier.createInMainRepo("foo"),
                    pkgRoot,  /* pattern= */
                    "subpkg/**",
                    Operation.SUBPACKAGES,
                    PathFragment.EMPTY_FRAGMENT
                )
            assertThat(packageNode.directDeps)
                .containsAtLeast(dirGlobDescriptor, subdirGlobDescriptor)

            val dirGlobNodeDeps: com.google.common.collect.ImmutableSet<SkyKey?> =
                com.google.common.collect.ImmutableSet.copyOf(graph.getIfPresent(dirGlobDescriptor).directDeps)
            Truth.assertThat(dirGlobNodeDeps)
                .containsAtLeast(
                    DirectoryListingValue.key(RootedPath.toRootedPath(pkgRoot, fooDirPath)),
                    FileValue.key(RootedPath.toRootedPath(pkgRoot, fooDirPath)),
                    PackageLookupValue.key(PackageIdentifier.createInMainRepo("foo/dir"))
                )

            val subdirGlobNodeDeps: com.google.common.collect.ImmutableSet<SkyKey?> =
                com.google.common.collect.ImmutableSet.copyOf(graph.getIfPresent(subdirGlobDescriptor).directDeps)
            Truth.assertThat(subdirGlobNodeDeps)
                .containsAtLeast(
                    FileValue.key(RootedPath.toRootedPath(pkgRoot, fooSubpkgPath)),
                    PackageLookupValue.key(PackageIdentifier.createInMainRepo("foo/subpkg"))
                )
        }
    }

    /**
     * Tests of the prelude file functionality.
     * 
     * 
     * This is in a separate BuildViewTestCase because we override the prelude label for the test.
     * (The prelude label is configured differently between Bazel and Blaze.)
     */
    @RunWith(JUnit4::class)
    class PreludeTest : BuildViewTestCase() {
        private val fs: CustomInMemoryFs =
            com.google.devtools.build.lib.skyframe.PackageFunctionTest.CustomInMemoryFs(com.google.devtools.build.lib.testutil.ManualClock())

        protected override fun createFileSystem(): FileSystem {
            return fs
        }

        override fun createRuleClassProvider(): ConfiguredRuleClassProvider {
            val builder: ConfiguredRuleClassProvider.Builder = Builder()
            // addStandardRules() may call setPrelude(), so do it first.
            TestRuleClassProvider.addStandardRules(builder)
            builder.setPrelude("//tools/test_build_rules:test_prelude")
            return builder.build()
        }

        @org.junit.Test
        @Throws(java.lang.Exception::class)
        fun testPreludeDefinedSymbolIsUsable() {
            scratch.file("tools/test_build_rules/BUILD")
            scratch.file(
                "tools/test_build_rules/test_prelude",  //
                "foo = 'FOO'"
            )
            scratch.file(
                "pkg/BUILD",  //
                "print(foo)"
            )

            invalidatePackages()

            getConfiguredTarget("//pkg:BUILD")
            assertContainsEvent("FOO")
        }

        @org.junit.Test
        @Throws(java.lang.Exception::class)
        fun testPreludeAutomaticallyReexportsLoadedSymbols() {
            scratch.file("tools/test_build_rules/BUILD")
            scratch.file(
                "tools/test_build_rules/test_prelude",  //
                "load('//util:common.bzl', 'foo')"
            )
            scratch.file("util/BUILD")
            scratch.file(
                "util/common.bzl",  //
                "foo = 'FOO'"
            )
            scratch.file(
                "pkg/BUILD",  //
                "print(foo)"
            )

            invalidatePackages()

            getConfiguredTarget("//pkg:BUILD")
            assertContainsEvent("FOO")
        }

        // TODO(brandjon): Invert this test once the prelude is a module instead of a syntactic
        // mutation on BUILD files.
        @org.junit.Test
        @Throws(java.lang.Exception::class)
        fun testPreludeCanExportUnderscoreSymbols() {
            scratch.file("tools/test_build_rules/BUILD")
            scratch.file(
                "tools/test_build_rules/test_prelude",  //
                "_foo = 'FOO'"
            )
            scratch.file(
                "pkg/BUILD",  //
                "print(_foo)"
            )

            invalidatePackages()

            getConfiguredTarget("//pkg:BUILD")
            assertContainsEvent("FOO")
        }

        @org.junit.Test
        @Throws(java.lang.Exception::class)
        fun testPreludeCanShadowUniversal() {
            scratch.file("tools/test_build_rules/BUILD")
            scratch.file(
                "tools/test_build_rules/test_prelude",  //
                "len = 'FOO'"
            )
            scratch.file(
                "pkg/BUILD",  //
                "print(len)"
            )

            invalidatePackages()

            getConfiguredTarget("//pkg:BUILD")
            assertContainsEvent("FOO")
        }

        @org.junit.Test
        @Throws(java.lang.Exception::class)
        fun testPreludeCanShadowPredeclareds() {
            scratch.file("tools/test_build_rules/BUILD")
            scratch.file(
                "tools/test_build_rules/test_prelude",  //
                "cc_library = 'FOO'"
            )
            scratch.file(
                "pkg/BUILD",  //
                "print(cc_library)"
            )

            invalidatePackages()

            getConfiguredTarget("//pkg:BUILD")
            assertContainsEvent("FOO")
        }

        @org.junit.Test
        @Throws(java.lang.Exception::class)
        fun testPreludeCanShadowInjectedPredeclareds() {
            setBuildLanguageOptions("--experimental_builtins_bzl_path=tools/builtins_staging")
            scratch.file(
                "tools/builtins_staging/exports.bzl",
                """
          exported_toplevels = {}
          exported_rules = {"cc_library": "BAR"}
          exported_to_java = {}
          
          """.trimIndent()
            )
            scratch.file("tools/test_build_rules/BUILD")
            scratch.file(
                "tools/test_build_rules/test_prelude",  //
                "cc_library = 'FOO'"
            )
            scratch.file(
                "pkg/BUILD",  //
                "print(cc_library)"
            )

            try {
                invalidatePackages()
            } catch (e: java.lang.Exception) {
                // Ignore any errors.
            }

            getConfiguredTarget("//pkg:BUILD")
            assertContainsEvent("FOO")
        }

        @org.junit.Test
        @Throws(java.lang.Exception::class)
        fun testPreludeSymbolCannotBeMutated() {
            scratch.file("tools/test_build_rules/BUILD")
            scratch.file(
                "tools/test_build_rules/test_prelude",  //
                "foo = ['FOO']"
            )
            scratch.file(
                "pkg/BUILD",  //
                "foo.append('BAR')"
            )

            reporter.removeHandler(failFastHandler)
            invalidatePackages()

            getConfiguredTarget("//pkg:BUILD")
            assertContainsEvent("trying to mutate a frozen list value")
        }

        @org.junit.Test
        @Throws(java.lang.Exception::class)
        fun testPreludeCanAccessBzlDialectFeatures() {
            scratch.file("tools/test_build_rules/BUILD")
            // Test both bzl symbols and syntax (e.g. function defs).
            scratch.file(
                "tools/test_build_rules/test_prelude",  //
                "def foo():",
                "    return native.glob"
            )
            scratch.file(
                "pkg/BUILD",  //
                "print(foo())"
            )

            invalidatePackages()

            getConfiguredTarget("//pkg:BUILD")
            // Prelude can access native.glob (though only a BUILD thread can call it).
            assertContainsEvent("<built-in method glob of native value>")
        }

        @org.junit.Test
        @Throws(java.lang.Exception::class)
        fun testPreludeNeedNotBePresent() {
            scratch.file(
                "pkg/BUILD",  //
                "print('FOO')"
            )

            getConfiguredTarget("//pkg:BUILD")
            assertContainsEvent("FOO")
        }

        @org.junit.Test
        @Throws(java.lang.Exception::class)
        fun testPreludeNeedNotBePresent_evenWhenPackageIs() {
            scratch.file("tools/test_build_rules/BUILD")
            scratch.file(
                "pkg/BUILD",  //
                "print('FOO')"
            )

            getConfiguredTarget("//pkg:BUILD")
            assertContainsEvent("FOO")
        }

        @org.junit.Test
        @Throws(java.lang.Exception::class)
        fun testPreludeFileNotRecognizedWithoutPackage() {
            scratch.file(
                "tools/test_build_rules/test_prelude",  //
                "foo = 'FOO'"
            )
            scratch.file(
                "pkg/BUILD",  //
                "print(foo)"
            )

            // The prelude file is not found without a corresponding package to contain it. BUILD files
            // get processed as if no prelude file is present.
            reporter.removeHandler(failFastHandler)
            getConfiguredTarget("//pkg:BUILD")
            assertContainsEvent("name 'foo' is not defined")
        }

        @org.junit.Test
        @Throws(java.lang.Exception::class)
        fun testPreludeFailsWhenErrorInPreludeFile() {
            scratch.file("tools/test_build_rules/BUILD")
            scratch.file(
                "tools/test_build_rules/test_prelude",  //
                "1//0",  // <-- dynamic error
                "foo = 'FOO'"
            )
            scratch.file(
                "pkg/BUILD",  //
                "print(foo)"
            )

            reporter.removeHandler(failFastHandler)

            try {
                invalidatePackages()
            } catch (e: java.lang.Exception) {
                // Ignore any errors.
            }

            getConfiguredTarget("//pkg:BUILD")
            assertContainsEvent(
                "File \"/workspace/tools/test_build_rules/test_prelude\", line 1, column 2, in"
                        + " <toplevel>"
            )
            assertContainsEvent("Error: integer division by zero")
        }

        @org.junit.Test
        @Throws(java.lang.Exception::class)
        fun testPreludeWorksEvenWhenPreludePackageInError() {
            scratch.file(
                "tools/test_build_rules/BUILD",  //
                "1//0"
            ) // <-- dynamic error
            scratch.file(
                "tools/test_build_rules/test_prelude",  //
                "foo = 'FOO'"
            )
            scratch.file(
                "pkg/BUILD",  //
                "print(foo)"
            )

            invalidatePackages()

            // Succeeds because prelude loading is only dependent on the prelude package's existence, not
            // its evaluation.
            getConfiguredTarget("//pkg:BUILD")
            assertContainsEvent("FOO")
        } // Another hypothetical test case we could try: Confirm that it's possible to explicitly load
        // the prelude file as a regular .bzl. We don't bother testing this use case because, aside from
        // being arguably pathological, it is currently impossible in practice: The prelude label
        // doesn't end with ".bzl" and isn't configurable by the user. We also want to eliminate the
        // prelude, so there's no intention of adding such a feature.
        // Another possible test case: Verify how prelude applies to WORKSPACE files.
    }

    private class CustomInMemoryFs(manualClock: com.google.devtools.build.lib.testutil.ManualClock) :
        InMemoryFileSystem(manualClock, DigestHashFunction.SHA256) {
        private abstract class FileStatusOrException {
            @Throws(IOException::class)
            abstract fun get(): FileStatus?

            private class ExceptionImpl(exn: IOException) : FileStatusOrException() {
                private val exn: IOException

                init {
                    this.exn = exn
                }

                @Throws(IOException::class)
                override fun get(): FileStatus? {
                    throw exn
                }
            }

            private class FileStatusImpl(fileStatus: FileStatus?) : FileStatusOrException() {
                private val fileStatus: FileStatus?

                init {
                    this.fileStatus = fileStatus
                }

                override fun get(): FileStatus? {
                    return fileStatus
                }
            }
        }

        private val stubbedStats: MutableMap<PathFragment?, FileStatusOrException?> =
            com.google.common.collect.Maps.newHashMap<PathFragment?, FileStatusOrException?>()
        private val makeUnreadableAfterReaddir: MutableSet<PathFragment?> =
            com.google.common.collect.Sets.newHashSet<PathFragment?>()
        private val pathsToErrorOnGetInputStream: MutableMap<PathFragment?, IOException?> =
            com.google.common.collect.Maps.newHashMap<PathFragment?, IOException?>()

        fun stubStat(path: Path, stubbedResult: FileStatus?) {
            stubbedStats.put(path.asFragment(), FileStatusImpl(stubbedResult))
        }

        fun stubStatError(path: Path, stubbedResult: IOException) {
            stubbedStats.put(path.asFragment(), ExceptionImpl(stubbedResult))
        }

        @Throws(IOException::class)
        public override fun statIfFound(path: PathFragment, followSymlinks: Boolean): FileStatus {
            if (stubbedStats.containsKey(path)) {
                return stubbedStats.get(path)!!.get()
            }
            return super.statIfFound(path, followSymlinks)
        }

        fun scheduleMakeUnreadableAfterReaddir(path: Path) {
            makeUnreadableAfterReaddir.add(path.asFragment())
        }

        @Throws(IOException::class)
        public override fun readdir(path: PathFragment, followSymlinks: Boolean): MutableCollection<Dirent?>? {
            val result: MutableCollection<Dirent?>? = super.readdir(path, followSymlinks)
            if (makeUnreadableAfterReaddir.contains(path)) {
                setReadable(path, false)
            }
            return result
        }

        fun throwExceptionOnGetInputStream(path: Path, exn: IOException?) {
            pathsToErrorOnGetInputStream.put(path.asFragment(), exn)
        }

        @kotlin.jvm.Synchronized
        @Throws(IOException::class)
        public override fun getInputStream(path: PathFragment): java.io.InputStream? {
            val exnToThrow: IOException? = pathsToErrorOnGetInputStream.get(path)
            if (exnToThrow != null) {
                throw exnToThrow
            }
            return super.getInputStream(path)
        }
    }

    companion object {
        @Throws(NoSuchTargetException::class)
        private fun assertSrcs(pkg: Packageoid, targetName: String?, vararg expected: String?) {
            val expectedLabels: MutableList<Label?> = java.util.ArrayList<Label?>()
            for (item in expected) {
                expectedLabels.add(Label.parseCanonicalUnchecked(item))
            }
            Truth.assertThat(getSrcs(pkg, targetName)).containsExactlyElementsIn(expectedLabels).inOrder()
        }

        @Throws(NoSuchTargetException::class)
        private fun getSrcs(pkg: Packageoid, targetName: String?): Iterable<Label?>? {
            return pkg.getTarget(targetName).getAssociatedRule().getAttr("srcs") as Iterable<Label?>?
        }

        private fun toStrings(labels: Iterable<Label?>): java.util.stream.Stream<String?>? {
            return com.google.common.collect.Streams.stream<Label?>(labels).map<String?>(Label::toString)
        }

        private fun assertDetailedExitCode(
            exception: java.lang.Exception, expectedPackageLoadingCode: PackageLoading.Code?, exitCode: ExitCode?
        ) {
            Truth.assertThat(exception).isInstanceOf(DetailedException::class.java)
            val detailedExitCode: DetailedExitCode = (exception as DetailedException).detailedExitCode
            assertThat(detailedExitCode.getExitCode()).isEqualTo(exitCode)
            assertThat(detailedExitCode.getFailureDetail().getPackageLoading().getCode())
                .isEqualTo(expectedPackageLoadingCode)
            assertThat(DetailedExitCode.getExitCode(detailedExitCode.getFailureDetail()))
                .isEqualTo(exitCode)
        }
    }
}
