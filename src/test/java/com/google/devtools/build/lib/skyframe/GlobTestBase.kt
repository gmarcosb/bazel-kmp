// Copyright 2024 The Bazel Authors. All rights reserved.
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

abstract class GlobTestBase {
    private var fs: CustomInMemoryFs? = null
    protected var evaluator: MemoizingEvaluator? = null
    private var differencer: RecordingDifferencer? = null
    protected var root: Path? = null
    private var writableRoot: Path? = null
    protected var pkgPath: Path? = null
    private var pkgLocator: AtomicReference<PathPackageLocator?>? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        fs =
            com.google.devtools.build.lib.skyframe.GlobTestBase.CustomInMemoryFs(com.google.devtools.build.lib.testutil.ManualClock())
        root = fs.getPath("/root/workspace")
        writableRoot = fs.getPath("/writableRoot/workspace")
        pkgPath = root.getRelative(PKG_ID.getPackageFragment())

        pkgLocator =
            AtomicReference<PathPackageLocator?>(
                PathPackageLocator(
                    fs.getPath("/output_base"),
                    com.google.common.collect.ImmutableList.of<E?>(Root.fromPath(writableRoot), Root.fromPath(root)),
                    BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
                )
            )

        differencer = SequencedRecordingDifferencer()
        evaluator = InMemoryMemoizingEvaluator(createFunctionMap(), differencer)
        PrecomputedValue.BUILD_ID.set(differencer, UUID.randomUUID())
        PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, pkgLocator.get())
        PrecomputedValue.STARLARK_SEMANTICS.set(differencer, StarlarkSemantics.DEFAULT)
        RepositoryDirectoryValue.VENDOR_DIRECTORY.set(differencer, java.util.Optional.empty<T?>())

        createTestFiles()
    }

    private fun createFunctionMap(): MutableMap<SkyFunctionName?, SkyFunction?> {
        val deletedPackages: AtomicReference<com.google.common.collect.ImmutableSet<PackageIdentifier?>?> =
            AtomicReference<com.google.common.collect.ImmutableSet<PackageIdentifier?>?>(com.google.common.collect.ImmutableSet.of<PackageIdentifier?>())
        val directories: BlazeDirectories =
            BlazeDirectories(
                ServerDirectories(root, root, root), root, TestConstants.PRODUCT_NAME
            )
        val externalFilesHelper: ExternalFilesHelper? =
            ExternalFilesHelper.createForTesting(
                pkgLocator,
                ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
                directories
            )

        val analysisMock: AnalysisMock = AnalysisMock.get()
        val ruleClassProvider: RuleClassProvider = analysisMock.createRuleClassProvider()
        val skyFunctions: MutableMap<SkyFunctionName?, SkyFunction?> = HashMap<SkyFunctionName?, SkyFunction?>()
        createGlobSkyFunction(skyFunctions)
        skyFunctions.put(
            SkyFunctions.DIRECTORY_LISTING_STATE,
            DirectoryListingStateFunction(externalFilesHelper, SyscallCache.NO_CACHE)
        )
        skyFunctions.put(SkyFunctions.DIRECTORY_LISTING, DirectoryListingFunction())
        skyFunctions.put(
            SkyFunctions.PACKAGE_LOOKUP,
            PackageLookupFunction(
                deletedPackages,
                CrossRepositoryLabelViolationStrategy.ERROR,
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
            )
        )
        skyFunctions.put(
            SkyFunctions.REPO_FILE,
            RepoFileFunction(
                ruleClassProvider.getBazelStarlarkEnvironment(),
                Root.fromPath(directories.getWorkspace())
            )
        )
        skyFunctions.put(SkyFunctions.IGNORED_SUBDIRECTORIES, IgnoredSubdirectoriesFunction.INSTANCE)
        skyFunctions.put(
            FileStateKey.FILE_STATE,
            FileStateFunction(
                com.google.common.base.Suppliers.ofInstance<T?>(TimestampGranularityMonitor(com.google.devtools.build.lib.clock.BlazeClock.instance())),
                SyscallCache.NO_CACHE,
                externalFilesHelper
            )
        )
        skyFunctions.put(
            FileSymlinkInfiniteExpansionUniquenessFunction.NAME,
            FileSymlinkCycleUniquenessFunction()
        )
        skyFunctions.put(SkyFunctions.FILE, FileFunction(pkgLocator, directories))
        skyFunctions.put(
            FileSymlinkCycleUniquenessFunction.NAME, FileSymlinkCycleUniquenessFunction()
        )
        skyFunctions.put(SkyFunctions.LOCAL_REPOSITORY_LOOKUP, LocalRepositoryLookupFunction())
        skyFunctions.put(
            SkyFunctions.REPOSITORY_MAPPING,
            object : SkyFunction() {
                public override fun compute(skyKey: SkyKey?, env: Environment?): SkyValue {
                    return RepositoryMappingValue.VALUE_FOR_EMPTY_ROOT_MODULE
                }
            })
        skyFunctions.put(
            RepoDefinitionValue.REPO_DEFINITION,
            object : SkyFunction() {
                public override fun compute(skyKey: SkyKey?, env: Environment?): SkyValue {
                    return RepoDefinitionValue.NOT_FOUND
                }
            })
        return skyFunctions
    }

    @com.google.errorprone.annotations.ForOverride
    protected abstract fun createGlobSkyFunction(skyFunctions: MutableMap<SkyFunctionName?, SkyFunction?>?)

    protected open fun alwaysUsesDirListing(): Boolean {
        return false
    }

    @Throws(IOException::class)
    private fun createTestFiles() {
        pkgPath.createDirectoryAndParents()
        FileSystemUtils.createEmptyFile(pkgPath.getRelative("BUILD"))
        for (dir in com.google.common.collect.ImmutableList.of<String?>(
            "foo/bar/wiz", "foo/barnacle/wiz", "food/barnacle/wiz", "fool/barnacle/wiz"
        )) {
            pkgPath.getRelative(dir).createDirectoryAndParents()
        }
        FileSystemUtils.createEmptyFile(pkgPath.getRelative("foo/bar/wiz/file"))

        // Used for testing the behavior of globbing into nested subpackages.
        for (dir in com.google.common.collect.ImmutableList.of<String?>("a1/b1/c", "a2/b2/c")) {
            pkgPath.getRelative(dir).createDirectoryAndParents()
        }
        FileSystemUtils.createEmptyFile(pkgPath.getRelative("a2/b2/BUILD"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSimple() {
        assertSingleGlobMatches("food",  /* => */"food")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIgnoreList() {
        FileSystemUtils.writeContentAsLatin1(root.getRelative(".bazelignore"), "pkg/foo/bar")
        assertSingleGlobMatches("foo/**", "foo/barnacle/wiz", "foo/barnacle", "foo")
        differencer.invalidate(
            com.google.common.collect.ImmutableList.of<E?>(
                FileStateValue.key(
                    RootedPath.toRootedPath(
                        Root.fromPath(root), PathFragment.create(".bazelignore")
                    )
                )
            )
        )

        FileSystemUtils.createEmptyFile(root.getRelative(".bazelignore"))
        assertSingleGlobMatches(
            "foo/**",
            "foo/bar/wiz",
            "foo/bar/wiz/file",
            "foo/bar",
            "foo/barnacle/wiz",
            "foo/barnacle",
            "foo"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStartsWithStar() {
        assertSingleGlobMatches("*oo",  /* => */"foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStartsWithStarWithMiddleStar() {
        assertSingleGlobMatches("*f*o",  /* => */"foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSingleMatchEqual() {
        assertGlobsEqual("*oo", "*f*o") // both produce "foo"
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEndsWithStar() {
        assertSingleGlobMatches("foo*",  /* => */"foo", "food", "fool")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEndsWithStarWithMiddleStar() {
        assertSingleGlobMatches("f*oo*",  /* => */"foo", "food", "fool")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultipleMatchesEqual() {
        assertGlobsEqual("foo*", "f*oo*") // both produce "foo", "food", "fool"
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMiddleStar() {
        assertSingleGlobMatches("f*o",  /* => */"foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTwoMiddleStars() {
        assertSingleGlobMatches("f*o*o",  /* => */"foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSingleStarPatternWithNamedChild() {
        assertSingleGlobMatches("*/bar",  /* => */"foo/bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeepSubpackages() {
        assertSingleGlobMatches("*/*/c",  /* => */"a1/b1/c")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSingleStarPatternWithChildGlob() {
        assertSingleGlobMatches(
            "*/bar*",  /* => */"foo/bar", "foo/barnacle", "food/barnacle", "fool/barnacle"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSingleStarAsChildGlob() {
        assertSingleGlobMatches("foo/*/wiz",  /* => */"foo/bar/wiz", "foo/barnacle/wiz")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoAsteriskAndFilesDontExist() {
        // Note un-UNIX like semantics:
        assertSingleGlobMatches("ceci/n'est/pas/une/globbe" /* => nothing */)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSingleAsteriskUnderNonexistentDirectory() {
        // Note un-UNIX like semantics:
        assertSingleGlobMatches("not-there/*" /* => nothing */)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDifferentGlobsSameResultEqual() {
        // Once the globs are run, it doesn't matter what pattern ran; only the output.
        assertGlobsEqual("not-there/*", "syzygy/*") // Both produce nothing.
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobUnderFile() {
        assertSingleGlobMatches("foo/bar/wiz/file/*" /* => nothing */)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobEqualsHashCode() {
        // Each "equality group" forms a set of elements that are all equals() to one another,
        // and also produce the same hashCode.
        EqualsTester()
            .addEqualityGroup(
                runSingleGlob("no-such-file", Globber.Operation.FILES_AND_DIRS)
            ) // Matches nothing.
            .addEqualityGroup(
                runSingleGlob("BUILD", Globber.Operation.FILES_AND_DIRS),
                runSingleGlob("BUILD", Globber.Operation.FILES)
            ) // Matches BUILD.
            .addEqualityGroup(
                runSingleGlob("**", Globber.Operation.FILES_AND_DIRS)
            ) // Matches lots of things.
            .addEqualityGroup(
                runSingleGlob("f*o/bar*", Globber.Operation.FILES_AND_DIRS),
                runSingleGlob(
                    "foo/bar*", Globber.Operation.FILES_AND_DIRS
                )
            ) // Matches foo/bar and foo/barnacle.
            .testEquals()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobDoesNotCrossPackageBoundary() {
        FileSystemUtils.createEmptyFile(pkgPath.getRelative("foo/BUILD"))
        // "foo/bar" should not be in the results because foo is a separate package.
        assertSingleGlobMatches("f*/*",  /* => */"food/barnacle", "fool/barnacle")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobDirectoryMatchDoesNotCrossPackageBoundary() {
        FileSystemUtils.createEmptyFile(pkgPath.getRelative("foo/bar/BUILD"))
        // "foo/bar" should not be in the results because foo/bar is a separate package.
        assertSingleGlobMatches("foo/*",  /* => */"foo/barnacle")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarStarDoesNotCrossPackageBoundary() {
        FileSystemUtils.createEmptyFile(pkgPath.getRelative("foo/bar/BUILD"))
        // "foo/bar" should not be in the results because foo/bar is a separate package.
        assertSingleGlobMatches("foo/**",  /* => */"foo/barnacle/wiz", "foo/barnacle", "foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobDoesNotCrossPackageBoundaryUnderOtherPackagePath() {
        writableRoot.getRelative("pkg/foo/bar").createDirectoryAndParents()
        FileSystemUtils.createEmptyFile(writableRoot.getRelative("pkg/foo/bar/BUILD"))
        // "foo/bar" should not be in the results because foo/bar is detected as a separate package,
        // even though it is under a different package path.
        assertSingleGlobMatches("foo/**",  /* => */"foo/barnacle/wiz", "foo/barnacle", "foo")
    }

    /**
     * For [GlobFunctionTest], creates a [GlobDescriptor] using the input pattern.
     * 
     * 
     * For [GlobsFunctionTest], creates a [GlobsValue.Key] whose `globRequests`
     * member contains only one element. The sole element's pattern is the input one.
     * 
     * 
     * Queries the [GlobDescriptor] or [GlobsValue.Key] in Skyframe and asserts that
     * matches in the result [GlobValue] or [GlobsValue] is equal to the input `expecteds`.
     */
    @Throws(java.lang.Exception::class)
    private fun assertSingleGlobMatches(pattern: String?, vararg expecteds: String?) {
        assertSingleGlobMatches(pattern, Operation.FILES_AND_DIRS, expecteds)
    }

    @Throws(java.lang.Exception::class)
    protected abstract fun assertSingleGlobMatches(
        pattern: String?, globberOperation: Globber.Operation?, vararg expecteds: String?
    )

    @Throws(java.lang.Exception::class)
    private fun assertGlobWithoutDirsMatches(pattern: String?, vararg expecteds: String?) {
        assertSingleGlobMatches(pattern, Globber.Operation.FILES, expecteds)
    }

    @Throws(java.lang.Exception::class)
    protected fun assertGlobsEqual(pattern1: String?, pattern2: String?) {
        val value1: SkyValue? = runSingleGlob(pattern1, Globber.Operation.FILES_AND_DIRS)
        val value2: SkyValue? = runSingleGlob(pattern2, Globber.Operation.FILES_AND_DIRS)
        EqualsTester().addEqualityGroup(value1, value2).testEquals()
    }

    @Throws(java.lang.Exception::class)
    protected abstract fun runSingleGlob(pattern: String?, globberOperation: Globber.Operation?): SkyValue?

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobWithoutWildcards() {
        val pattern = "foo/bar/wiz/file"

        assertSingleGlobMatches(pattern, "foo/bar/wiz/file")
        // Ensure that the glob depends on the FileValue and not on the DirectoryListingValue.
        pkgPath.getRelative("foo/bar/wiz/file").delete()

        // Nothing has been invalidated yet, so the cached result is returned.
        assertSingleGlobMatches(pattern, "foo/bar/wiz/file")

        if (alwaysUsesDirListing()) {
            differencer.invalidate(
                com.google.common.collect.ImmutableList.of<E?>(
                    FileStateValue.key(
                        RootedPath.toRootedPath(
                            Root.fromPath(root), pkgPath.getRelative("foo/bar/wiz/file")
                        )
                    )
                )
            )
            // The result should not rely on the FileStateValue, so it's still a cache hit.
            assertSingleGlobMatches(pattern, "foo/bar/wiz/file")

            differencer.invalidate(
                com.google.common.collect.ImmutableList.of<E?>(
                    DirectoryListingStateValue.key(
                        RootedPath.toRootedPath(
                            Root.fromPath(root), pkgPath.getRelative("foo/bar/wiz")
                        )
                    )
                )
            )
        } else {
            differencer.invalidate(
                com.google.common.collect.ImmutableList.of<E?>(
                    DirectoryListingStateValue.key(
                        RootedPath.toRootedPath(
                            Root.fromPath(root), pkgPath.getRelative("foo/bar/wiz")
                        )
                    )
                )
            )
            // The result should not rely on the DirectoryListingValue, so it's still a cache hit.
            assertSingleGlobMatches(pattern, "foo/bar/wiz/file")

            differencer.invalidate(
                com.google.common.collect.ImmutableList.of<E?>(
                    FileStateValue.key(
                        RootedPath.toRootedPath(
                            Root.fromPath(root), pkgPath.getRelative("foo/bar/wiz/file")
                        )
                    )
                )
            )
        }

        // This should have invalidated the glob result.
        assertSingleGlobMatches(pattern /* => nothing */)
    }

    @org.junit.Test
    fun testIllegalPatterns() {
        assertIllegalPattern("foo**bar")
        assertIllegalPattern("?")
        assertIllegalPattern("")
        assertIllegalPattern(".")
        assertIllegalPattern("/foo")
        assertIllegalPattern("./foo")
        assertIllegalPattern("foo/")
        assertIllegalPattern("foo/./bar")
        assertIllegalPattern("../foo/bar")
        assertIllegalPattern("foo//bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIllegalRecursivePatterns() {
        for (prefix in com.google.common.collect.Lists.newArrayList<String>("", "*/", "**/", "ba/")) {
            val suffix: String = ("/" + prefix).substring(0, prefix.length)
            for (pattern in com.google.common.collect.Lists.newArrayList<String?>(
                "**fo",
                "fo**",
                "**fo**",
                "fo**fo",
                "fo**fo**fo"
            )) {
                assertIllegalPattern(prefix + pattern)
                assertIllegalPattern(pattern + suffix)
            }
        }
    }

    protected abstract fun assertIllegalPattern(pattern: String?)

    /** Tests that globs can contain Java regular expression special characters  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSpecialRegexCharacter() {
        val aDotB: Path? = pkgPath.getChild("a.b")
        FileSystemUtils.createEmptyFile(aDotB)
        FileSystemUtils.createEmptyFile(pkgPath.getChild("aab"))
        // Note: this contains two asterisks because otherwise a RE is not built,
        // as an optimization.
        assertThat(
            Builder(pkgPath, FilesystemOps.DIRECT)
                .addPattern("*a.b*")
                .globInterruptible()
        )
            .containsExactly(aDotB)
    }

    @org.junit.Test
    fun testMatchesCallWithNoCache() {
        assertThat(UnixGlob.matches("*a*b", "CaCb", null)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHiddenFiles() {
        for (dir in com.google.common.collect.ImmutableList.of<String?>(".hidden", "..also.hidden", "not.hidden")) {
            pkgPath.getRelative(dir).createDirectoryAndParents()
        }
        // Note that these are not in the result: ".", ".."
        assertSingleGlobMatches(
            "*", "..also.hidden", ".hidden", "BUILD", "a1", "a2", "foo", "food", "fool", "not.hidden"
        )
        assertSingleGlobMatches("*.hidden", "not.hidden")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoubleStar() {
        assertSingleGlobMatches(
            "**",
            "a1/b1/c",
            "a1/b1",
            "a1",
            "a2",
            "foo/bar/wiz",
            "foo/bar/wiz/file",
            "foo/bar",
            "foo/barnacle/wiz",
            "foo/barnacle",
            "foo",
            "food/barnacle/wiz",
            "food/barnacle",
            "food",
            "fool/barnacle/wiz",
            "fool/barnacle",
            "fool",
            "BUILD"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoubleStarExcludeDirs() {
        assertGlobWithoutDirsMatches("**", "foo/bar/wiz/file", "BUILD")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoubleDoubleStar() {
        assertSingleGlobMatches(
            "**/**",
            "a1/b1/c",
            "a1/b1",
            "a1",
            "a2",
            "foo/bar/wiz",
            "foo/bar/wiz/file",
            "foo/bar",
            "foo/barnacle/wiz",
            "foo/barnacle",
            "foo",
            "food/barnacle/wiz",
            "food/barnacle",
            "food",
            "fool/barnacle/wiz",
            "fool/barnacle",
            "fool",
            "BUILD"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDirectoryWithDoubleStar() {
        assertSingleGlobMatches(
            "foo/**",
            "foo/bar/wiz",
            "foo/bar/wiz/file",
            "foo/bar",
            "foo/barnacle/wiz",
            "foo/barnacle",
            "foo"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoubleStarPatternWithNamedChild() {
        assertSingleGlobMatches("**/bar", "foo/bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoubleStarPatternWithErrorChild() {
        FileSystemUtils.ensureSymbolicLink(pkgPath.getChild("self"), "self")

        val ioException: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { runSingleGlob("**/self", Operation.FILES) })
        Truth.assertThat(ioException).hasMessageThat().matches("Symlink cycle")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoubleStarPatternWithChildGlob() {
        assertSingleGlobMatches("**/ba*", "foo/bar", "foo/barnacle", "food/barnacle", "fool/barnacle")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoubleStarAsChildGlob() {
        FileSystemUtils.createEmptyFile(pkgPath.getRelative("foo/barnacle/wiz/wiz"))
        pkgPath.getRelative("foo/barnacle/baz/wiz").createDirectoryAndParents()

        assertSingleGlobMatches(
            "foo/**/wiz",
            "foo/bar/wiz",
            "foo/barnacle/wiz",
            "foo/barnacle/baz/wiz",
            "foo/barnacle/wiz/wiz"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoubleStarUnderNonexistentDirectory() {
        assertSingleGlobMatches("not-there/**" /* => nothing */)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoubleStarUnderFile() {
        assertSingleGlobMatches("foo/bar/wiz/file/**" /* => nothing */)
    }

    @Throws(InvalidGlobPatternException::class)
    protected abstract fun createdGlobRelatedSkyKey(
        pattern: String?, globberOperation: Globber.Operation?
    ): SkyKey

    /** Regression test for b/13319874: Directory listing crash.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testResilienceToFilesystemInconsistencies_directoryExistence() {
        // Our custom filesystem says "pkgPath/BUILD" exists but "pkgPath" does not exist.
        fs!!.stubStat(pkgPath, null)
        val pkgRootedPath: RootedPath = RootedPath.toRootedPath(Root.fromPath(root), pkgPath)
        val pkgDirFileStateValue: FileStateValue? =
            FileStateValue.create(pkgRootedPath, SyscallCache.NO_CACHE,  /* tsgm= */null)
        val pkgDirValue: FileValue? =
            FileValue.value(
                com.google.common.collect.ImmutableList.of<E?>(pkgRootedPath),
                null,
                null,
                pkgRootedPath,
                pkgDirFileStateValue,
                pkgRootedPath,
                pkgDirFileStateValue
            )
        differencer.inject(
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                FileValue.key(pkgRootedPath),
                Delta.justNew(pkgDirValue)
            )
        )
        val expectedMessage = "/root/workspace/pkg is no longer an existing directory"
        val skyKey: SkyKey = createdGlobRelatedSkyKey("*/foo", Operation.FILES_AND_DIRS)
        val result: EvaluationResult<GlobValue?> =
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(skyKey), EVALUATION_OPTIONS)
        assertThat(result.hasError()).isTrue()
        val errorInfo: ErrorInfo = result.getError(skyKey)
        assertThat(errorInfo.getException()).isInstanceOf(InconsistentFilesystemException::class.java)
        assertThat(errorInfo.getException()).hasMessageThat().contains(expectedMessage)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testResilienceToFilesystemInconsistencies_subdirectoryExistence() {
        // Our custom filesystem says directory "pkgPath/foo/bar" contains a subdirectory "wiz" but a
        // direct stat on "pkgPath/foo/bar/wiz" says it does not exist.
        val fooBarDir: Path = pkgPath.getRelative("foo/bar")
        fs!!.stubStat(fooBarDir.getRelative("wiz"), null)
        val fooBarDirRootedPath: RootedPath? = RootedPath.toRootedPath(Root.fromPath(root), fooBarDir)
        val fooBarDirListingValue: SkyValue? =
            DirectoryListingStateValue.create(
                com.google.common.collect.ImmutableList.of<E?>(Dirent("wiz", Dirent.Type.DIRECTORY))
            )
        differencer.inject(
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                DirectoryListingStateValue.key(fooBarDirRootedPath),
                Delta.justNew(fooBarDirListingValue)
            )
        )
        val expectedMessage = "/root/workspace/pkg/foo/bar/wiz is no longer an existing directory."
        val skyKey: SkyKey = createdGlobRelatedSkyKey("**/wiz", Globber.Operation.FILES_AND_DIRS)
        val result: EvaluationResult<GlobValue?> =
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(skyKey), EVALUATION_OPTIONS)
        assertThat(result.hasError()).isTrue()
        val errorInfo: ErrorInfo = result.getError(skyKey)
        assertThat(errorInfo.getException()).isInstanceOf(InconsistentFilesystemException::class.java)
        assertThat(errorInfo.getException()).hasMessageThat().contains(expectedMessage)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testResilienceToFilesystemInconsistencies_symlinkType() {
        val wizRootedPath: RootedPath? =
            RootedPath.toRootedPath(Root.fromPath(root), pkgPath.getRelative("foo/bar/wiz"))
        val fileRootedPath: RootedPath =
            RootedPath.toRootedPath(Root.fromPath(root), pkgPath.getRelative("foo/bar/wiz/file"))
        val realStat: FileStatus = fileRootedPath.asPath().stat()
        fs!!.stubStat(
            fileRootedPath.asPath(),
            object : FileStatus() {
                val isFile: Boolean
                    get() =// The stat says foo/bar/wiz/file is a real file, not a symlink.
                        true

                val isSpecialFile: Boolean
                    get() = false

                val isDirectory: Boolean
                    get() = false

                val isSymbolicLink: Boolean
                    get() = false

                @get:Throws(IOException::class)
                val size: Long
                    get() = realStat.size

                @get:Throws(IOException::class)
                val lastModifiedTime: Long
                    get() = realStat.lastModifiedTime

                @get:Throws(IOException::class)
                val lastChangeTime: Long
                    get() = realStat.lastChangeTime

                @get:Throws(IOException::class)
                val nodeId: Long
                    get() = realStat.nodeId
            })
        // But the dir listing say foo/bar/wiz/file is a symlink.
        val wizDirListingValue: SkyValue? =
            DirectoryListingStateValue.create(
                com.google.common.collect.ImmutableList.of<E?>(Dirent("file", Dirent.Type.SYMLINK))
            )
        differencer.inject(
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                DirectoryListingStateValue.key(wizRootedPath), Delta.justNew(wizDirListingValue)
            )
        )
        val expectedMessage =
            "readdir and stat disagree about whether " + fileRootedPath.asPath() + " is a symlink"
        val skyKey: SkyKey = createdGlobRelatedSkyKey("foo/bar/wiz/*", Globber.Operation.FILES_AND_DIRS)
        val result: EvaluationResult<GlobValue?> =
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(skyKey), EVALUATION_OPTIONS)
        assertThat(result.hasError()).isTrue()
        val errorInfo: ErrorInfo = result.getError(skyKey)
        assertThat(errorInfo.getException()).isInstanceOf(InconsistentFilesystemException::class.java)
        assertThat(errorInfo.getException()).hasMessageThat().contains(expectedMessage)
    }

    /**
     * When globbing symlinks, the returned path should use the path of the symlink source instead of
     * the symlink target, regardless of whether glob pattern contains wildcard character or not.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinks(@TestParameter withWildcard: Boolean) {
        pkgPath.getRelative("symlinks").createDirectoryAndParents()
        FileSystemUtils.ensureSymbolicLink(pkgPath.getRelative("symlinks/dangling.txt"), "nope")
        FileSystemUtils.createEmptyFile(pkgPath.getRelative("symlinks/yup"))
        FileSystemUtils.ensureSymbolicLink(pkgPath.getRelative("symlinks/existing.txt"), "yup")

        val globPattern = if (withWildcard) "symlinks/*.txt" else "symlinks/existing.txt"
        assertSingleGlobMatches(globPattern, "symlinks/existing.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinks_symlinkPointToDirectory() {
        root.getRelative("target_direc").createDirectoryAndParents()
        FileSystemUtils.createEmptyFile(root.getRelative("target_direc/file1"))
        root.getRelative("target_direc/sub").createDirectoryAndParents()
        FileSystemUtils.createEmptyFile(root.getRelative("target_direc/sub/file2"))

        FileSystemUtils.ensureSymbolicLink(
            pkgPath.getRelative("symlink"), root.getRelative("target_direc")
        )
        assertSingleGlobMatches(
            "symlink/**", "symlink/sub", "symlink/sub/file2", "symlink", "symlink/file1"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun symlinkFileValueWithError_symlinkCycleToSelf() {
        FileSystemUtils.ensureSymbolicLink(pkgPath.getChild("self"), "self")

        val ioException: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { runSingleGlob("self", Operation.FILES_AND_DIRS) })
        Truth.assertThat(ioException).hasMessageThat().matches("Symlink cycle")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun symlinkFileValueWithError_symlinkCycleBetweenTwoSymlinks(
        @TestParameter withWildcard: Boolean
    ) {
        pkgPath.getRelative("foo").createDirectoryAndParents()
        pkgPath.getRelative("bar").createDirectoryAndParents()

        FileSystemUtils.ensureSymbolicLink(pkgPath.getRelative("foo/a"), pkgPath.getRelative("bar/b"))
        FileSystemUtils.ensureSymbolicLink(pkgPath.getRelative("bar/b"), pkgPath.getRelative("foo/a"))

        val globPattern = if (withWildcard) "foo/*" else "foo/a"
        val ioException: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { runSingleGlob(globPattern, Operation.FILES_AND_DIRS) })
        Truth.assertThat(ioException).hasMessageThat().matches("Symlink cycle")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun symlinkSubdirValueWithError() {
        val cycle: Path = pkgPath.getChild("cycle")
        FileSystemUtils.ensureSymbolicLink(cycle.getChild("self"), "self")
        FileSystemUtils.ensureSymbolicLink(pkgPath.getChild("symlink"), cycle)

        val ioException: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { runSingleGlob("symlink/self", Operation.FILES_AND_DIRS) })
        Truth.assertThat(ioException).hasMessageThat().matches("Symlink cycle")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinks_unboundedSymlinkExpansion(@TestParameter withRecursiveWildcard: Boolean) {
        pkgPath.getRelative("parent/sub").createDirectoryAndParents()
        FileSystemUtils.ensureSymbolicLink(
            pkgPath.getRelative("parent/sub/symlink"), pkgPath.getRelative("parent")
        )

        val globPattern = if (withRecursiveWildcard) "parent/**" else "parent/sub/symlink"
        val skyKey: SkyKey = createdGlobRelatedSkyKey(globPattern, Globber.Operation.FILES_AND_DIRS)

        val result: EvaluationResult<GlobValue?> =
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(skyKey), EVALUATION_OPTIONS)

        if (withRecursiveWildcard || alwaysUsesDirListing()) {
            assertThat(result.hasError()).isTrue()
            val errorInfo: ErrorInfo = result.getError(skyKey)
            assertThat(errorInfo.getException())
                .isInstanceOf(FileSymlinkInfiniteExpansionException::class.java)
            assertThat(errorInfo.getException()).hasMessageThat().contains("Infinite symlink expansion")
        } else {
            assertThat(result.hasError()).isFalse()
        }
    }

    /**
     * Covers the scenario when a directory has two symlinks of different status.
     * 
     * 
     * One of the symlinks is a normal one whose path should be accepted by `SymlinkProducer`.
     * 
     * 
     * The other symlink shows different `readdir` and `stat` status. `readdir`
     * shows that it is a symlink but `stat` shows that it is a normal file. A [ ] should be accepted for this path by `SymlinkProducer`.
     * 
     * 
     * `PatternWithWildcardProducer` immediately returns `DONE` when knowing the number
     * of accepted symlink paths (1) is smaller than the number of symlink queried (2). The size
     * mismatch indicates that one of the symlinks goes wrong.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinks_oneNormalOneInconsistencyFilesystemError() {
        pkgPath.getRelative("inconsistent").createDirectoryAndParents()
        FileSystemUtils.createEmptyFile(pkgPath.getRelative("target"))
        FileSystemUtils.ensureSymbolicLink(
            pkgPath.getRelative("inconsistent/good"), pkgPath.getRelative("target")
        )
        FileSystemUtils.ensureSymbolicLink(
            pkgPath.getRelative("inconsistent/bad"), pkgPath.getRelative("target")
        )

        val badRootedPath: RootedPath =
            RootedPath.toRootedPath(Root.fromPath(root), pkgPath.getRelative("inconsistent/bad"))
        val realStat: FileStatus = badRootedPath.asPath().stat()
        fs!!.stubStat(
            badRootedPath.asPath(),
            object : FileStatus() {
                val isFile: Boolean
                    get() =// Intentionally set `isFile` as true, which disagree with filesystem.
                        true

                public override fun isSpecialFile(): Boolean {
                    return false
                }

                public override fun isDirectory(): Boolean {
                    return false
                }

                public override fun isSymbolicLink(): Boolean {
                    // Intentionally set `isSymbolicLink` as false, which disagree with filesystem.
                    return false
                }

                @Throws(IOException::class)
                public override fun getSize(): Long {
                    return realStat.size
                }

                @Throws(IOException::class)
                public override fun getLastModifiedTime(): Long {
                    return realStat.lastModifiedTime
                }

                @Throws(IOException::class)
                public override fun getLastChangeTime(): Long {
                    return realStat.lastChangeTime
                }

                @Throws(IOException::class)
                public override fun getNodeId(): Long {
                    return realStat.nodeId
                }
            })

        val skyKey: SkyKey = createdGlobRelatedSkyKey("inconsistent/*", Globber.Operation.FILES_AND_DIRS)
        val result: EvaluationResult<GlobValue?> =
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(skyKey), EVALUATION_OPTIONS)
        assertThat(result.hasError()).isTrue()
        val errorInfo: ErrorInfo = result.getError(skyKey)
        assertThat(errorInfo.getException()).isInstanceOf(InconsistentFilesystemException::class.java)
        assertThat(errorInfo.getException())
            .hasMessageThat()
            .contains("Inconsistent filesystem operations. readdir and stat disagree")
    }

    /**
     * The test below covers the case when [DirectoryListingValue] contains multiple symlinks,
     * which is common for bazel shell integration tests. Bazel shell integration tests usually create
     * symlinks for all source files.
     * 
     * 
     * Expect all matches to be returned when globbing multiple symlinks under the directory.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinksUnderDirectory_shouldAllBeGlobbed() {
        root.getRelative("targets").createDirectoryAndParents()
        pkgPath.getRelative("symlinks").createDirectoryAndParents()
        var c = 'a'
        while (c <= 'z') {
            FileSystemUtils.createEmptyFile(root.getRelative("targets/" + c + ".ext"))
            FileSystemUtils.ensureSymbolicLink(
                pkgPath.getRelative("symlinks/" + c + ".ext"), root.getRelative("targets/" + c + ".ext")
            )
            ++c
        }

        val allExpectedPathsInStr = arrayOfNulls<String>(26)
        for (i in 0..25) {
            allExpectedPathsInStr[i] = "symlinks/" + ('a'.code + i).toChar() + ".ext"
        }
        assertSingleGlobMatches("symlinks/*.ext", Operation.FILES_AND_DIRS, allExpectedPathsInStr)
    }

    internal class CustomInMemoryFs(manualClock: com.google.devtools.build.lib.testutil.ManualClock) :
        InMemoryFileSystem(manualClock, DigestHashFunction.SHA256) {
        private val stubbedStats: MutableMap<PathFragment?, FileStatus> =
            com.google.common.collect.Maps.newHashMap<PathFragment?, FileStatus>()

        fun stubStat(path: Path, stubbedResult: FileStatus?) {
            stubbedStats.put(path.asFragment(), stubbedResult)
        }

        @Throws(IOException::class)
        public override fun statIfFound(path: PathFragment, followSymlinks: Boolean): FileStatus {
            if (stubbedStats.containsKey(path)) {
                return stubbedStats.get(path)
            }
            return super.statIfFound(path, followSymlinks)
        }
    }

    @Throws(java.lang.Exception::class)
    private fun assertSubpackageMatches(pattern: String?, vararg expecteds: String?) {
        Truth.assertThat(getSubpackagesMatches(pattern))
            .containsExactlyElementsIn(com.google.common.collect.ImmutableList.copyOf<String?>(expecteds))
    }

    @Throws(java.lang.Exception::class)
    protected abstract fun getSubpackagesMatches(pattern: String?): Iterable<String?>?

    @Throws(java.lang.Exception::class)
    private fun makeEmptyPackage(newPackagePath: Path) {
        newPackagePath.createDirectoryAndParents()
        FileSystemUtils.createEmptyFile(newPackagePath.getRelative("BUILD"))
    }

    @Throws(java.lang.Exception::class)
    private fun makeEmptyPackage(path: String?) {
        makeEmptyPackage(pkgPath.getRelative(path))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun subpackages_simple() {
        makeEmptyPackage("horse")
        makeEmptyPackage("monkey")
        makeEmptyPackage("horse/saddle")

        // "horse/saddle" should not be in the results because horse/saddle is too deep. a2/b2 added by
        // setup().
        assertSubpackageMatches("**",  /* => */"a2/b2", "horse", "monkey")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun subpackages_empty() {
        assertSubpackageMatches("foo/*")
        assertSubpackageMatches("foo/**")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun subpackages_doubleStarPatternWithNamedChild() {
        assertSubpackageMatches("**/bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun subpackages_noWildcard() {
        makeEmptyPackage("sub1")
        makeEmptyPackage("sub2")
        makeEmptyPackage("sub3/deep")
        makeEmptyPackage("sub4/deeper/deeper")

        assertSubpackageMatches("sub")
        assertSubpackageMatches("sub1", "sub1")
        assertSubpackageMatches("sub2", "sub2")
        assertSubpackageMatches("sub3/deep", "sub3/deep")
        assertSubpackageMatches("sub4/deeper/deeper", "sub4/deeper/deeper")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun subpackages_zeroLevelDeep(@TestParameter withDeeperSubpackage: Boolean) {
        makeEmptyPackage("sub")
        if (withDeeperSubpackage) {
            makeEmptyPackage("sub/subOfSub")
        }

        assertSubpackageMatches("sub/*")

        // `**` is considered to matching nothing below.
        assertSubpackageMatches("sub/**", "sub")
        assertSubpackageMatches("sub/**/**", "sub")

        assertSubpackageMatches("sub/**/foo")
        assertSubpackageMatches("sub/**/foo/**")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun subpackages_oneLevelDeep() {
        makeEmptyPackage("base/sub")
        makeEmptyPackage("base/sub2")
        makeEmptyPackage("base/sub3")

        val matchingPatterns: MutableList<String?> =
            mutableListOf<String?>("base/*", "base/**", "base/**/**", "base/**/sub*", "base/**/sub*/**")

        for (pattern in matchingPatterns) {
            assertSubpackageMatches(pattern,  /* => */"base/sub", "base/sub2", "base/sub3")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun subpackages_deepRecurse() {
        makeEmptyPackage("base/sub/1")
        makeEmptyPackage("base/sub/2")
        makeEmptyPackage("base/sub2/3")
        makeEmptyPackage("base/sub2/4")
        makeEmptyPackage("base/sub3/5")
        makeEmptyPackage("base/sub3/6")

        FileSystemUtils.createEmptyFile(pkgPath.getRelative("foo/bar/BUILD"))

        // * doesn't go deep enough, so no matches
        assertSubpackageMatches("base/*")

        val matchingPatterns: MutableList<String?> =
            mutableListOf<String?>("base/**", "base/*/*", "base/*/*/**", "base/*/*/**/**", "base/**/sub*/**")

        for (pattern in matchingPatterns) {
            assertSubpackageMatches(
                pattern,
                "base/sub/1",
                "base/sub/2",
                "base/sub2/3",
                "base/sub2/4",
                "base/sub3/5",
                "base/sub3/6"
            )
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun subpackages_middleWildcard() {
        makeEmptyPackage("base/same")
        makeEmptyPackage("base/sub1/same")
        makeEmptyPackage("base/sub2/same")
        makeEmptyPackage("base/sub3/same")
        makeEmptyPackage("base/sub4/same")
        makeEmptyPackage("base/sub5/same")
        makeEmptyPackage("base/sub6/same")
        makeEmptyPackage("base/sub7/sub8/same")
        makeEmptyPackage("base/sub9/sub10/sub11/same")

        assertSubpackageMatches(
            "base/*/same",
            "base/sub1/same",
            "base/sub2/same",
            "base/sub3/same",
            "base/sub4/same",
            "base/sub5/same",
            "base/sub6/same"
        )

        assertSubpackageMatches(
            "base/**/same",
            "base/same",
            "base/sub1/same",
            "base/sub2/same",
            "base/sub3/same",
            "base/sub4/same",
            "base/sub5/same",
            "base/sub6/same",
            "base/sub7/sub8/same",
            "base/sub9/sub10/sub11/same"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun subpackages_testSymlinks() {
        val newPackagePath: Path = pkgPath.getRelative("path/to/pkg")
        makeEmptyPackage(newPackagePath)

        pkgPath.getRelative("symlinks").createDirectoryAndParents()
        FileSystemUtils.ensureSymbolicLink(pkgPath.getRelative("symlinks/deeplink"), newPackagePath)
        FileSystemUtils.ensureSymbolicLink(pkgPath.getRelative("shallowlink"), newPackagePath)

        assertSubpackageMatches("**", "a2/b2", "symlinks/deeplink", "path/to/pkg", "shallowlink")
        assertSubpackageMatches("*", "shallowlink")

        assertSubpackageMatches("symlinks/**", "symlinks/deeplink")
        assertSubpackageMatches("symlinks/*", "symlinks/deeplink")
    }

    companion object {
        protected val EVALUATION_OPTIONS: EvaluationContext? = EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setParallelism(SkyframeExecutor.DEFAULT_THREAD_COUNT)
            .setEventHandler(NullEventHandler.INSTANCE)
            .build()

        protected val PKG_ID: PackageIdentifier = PackageIdentifier.createInMainRepo("pkg")
    }
}
