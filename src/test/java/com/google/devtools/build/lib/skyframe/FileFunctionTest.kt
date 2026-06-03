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

import com.google.devtools.build.lib.skyframe.SkyframeExecutor.DEFAULT_THREAD_COUNT

/** Tests for [FileFunction].  */
@RunWith(JUnit4::class)
class FileFunctionTest {
    private var fs: InMemoryFileSystem? = null
    private var pkgRoot: Root? = null
    private var outputBase: Path? = null
    private var outputBaseRoot: Root? = null
    private var pkgLocator: PathPackageLocator? = null
    private var fastDigest = false
    private var manualClock: com.google.devtools.build.lib.testutil.ManualClock? = null
    private var differencer: RecordingDifferencer? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createFsAndRoot() {
        fastDigest = true
        manualClock = com.google.devtools.build.lib.testutil.ManualClock()
        createFsAndRoot(com.google.devtools.build.lib.skyframe.FileFunctionTest.CustomInMemoryFs(manualClock))
    }

    @Throws(IOException::class)
    private fun createFsAndRoot(fs: CustomInMemoryFs) {
        this.fs = fs
        pkgRoot = Root.fromPath(fs.getPath("/root"))
        outputBase = fs.getPath("/output_base")
        outputBaseRoot = Root.fromPath(outputBase)
        pkgLocator =
            PathPackageLocator(
                outputBase,
                com.google.common.collect.ImmutableList.of<E?>(pkgRoot),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
            )
        pkgRoot.asPath().createDirectoryAndParents()
    }

    private fun makeEvaluator(): MemoizingEvaluator {
        return makeEvaluator(ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS)
    }

    private fun makeEvaluator(externalFileAction: ExternalFileAction?): MemoizingEvaluator {
        val pkgLocatorRef: AtomicReference<PathPackageLocator?> = AtomicReference<PathPackageLocator?>(pkgLocator)
        val directories: BlazeDirectories =
            BlazeDirectories(
                ServerDirectories(pkgRoot.asPath(), outputBase, outputBase),
                pkgRoot.asPath(),
                TestConstants.PRODUCT_NAME
            )
        val externalFilesHelper: ExternalFilesHelper? =
            ExternalFilesHelper.createForTesting(pkgLocatorRef, externalFileAction, directories)
        differencer = SequencedRecordingDifferencer()
        val evaluator: MemoizingEvaluator =
            InMemoryMemoizingEvaluator(
                com.google.common.collect.ImmutableMap.builder<SkyFunctionName?, SkyFunction?>()
                    .put(
                        FileStateKey.FILE_STATE,
                        FileStateFunction(
                            com.google.common.base.Suppliers.ofInstance<T?>(
                                TimestampGranularityMonitor(com.google.devtools.build.lib.clock.BlazeClock.instance())
                            ),
                            SyscallCache.NO_CACHE,
                            externalFilesHelper
                        )
                    )
                    .put(
                        FileSymlinkCycleUniquenessFunction.NAME,
                        FileSymlinkCycleUniquenessFunction()
                    )
                    .put(
                        FileSymlinkInfiniteExpansionUniquenessFunction.NAME,
                        FileSymlinkInfiniteExpansionUniquenessFunction()
                    )
                    .put(SkyFunctions.FILE, FileFunction(pkgLocatorRef, directories))
                    .put(SkyFunctions.PACKAGE, PackageFunction.newBuilder().build())
                    .put(
                        SkyFunctions.PACKAGE_LOOKUP,
                        PackageLookupFunction(
                            AtomicReference<V?>(com.google.common.collect.ImmutableSet.of<Any?>()),
                            CrossRepositoryLabelViolationStrategy.ERROR,
                            BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
                        )
                    )
                    .put(SkyFunctions.LOCAL_REPOSITORY_LOOKUP, LocalRepositoryLookupFunction())
                    .put(
                        SkyFunctions.REPOSITORY_DIRECTORY,
                        RepositoryFetchFunction(
                            com.google.common.collect.ImmutableMap::of,
                            com.google.common.collect.ImmutableMap::of,
                            directories,
                            LocalRepoContentsCache()
                        )
                    )
                    .put(
                        SkyFunctions.REPOSITORY_MAPPING,
                        object : SkyFunction() {
                            public override fun compute(skyKey: SkyKey?, env: Environment?): SkyValue {
                                return RepositoryMappingValue.VALUE_FOR_EMPTY_ROOT_MODULE
                            }
                        })
                    .put(
                        RepoDefinitionValue.REPO_DEFINITION,
                        object : SkyFunction() {
                            public override fun compute(skyKey: SkyKey?, env: Environment?): SkyValue {
                                return RepoDefinitionValue.NOT_FOUND
                            }
                        })
                    .build(),
                differencer
            )
        PrecomputedValue.BUILD_ID.set(differencer, UUID.randomUUID())
        PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, pkgLocator)
        RepoDefinitionFunction.REPOSITORY_OVERRIDES.set(
            differencer,
            com.google.common.collect.ImmutableMap.of<K?, V?>()
        )
        RepositoryDirectoryValue.FETCH_DISABLED.set(differencer, false)
        RepositoryDirectoryValue.FORCE_FETCH.set(
            differencer, RepositoryDirectoryValue.FORCE_FETCH_DISABLED
        )
        RepositoryDirectoryValue.VENDOR_DIRECTORY.set(differencer, java.util.Optional.empty<T?>())
        PrecomputedValue.STARLARK_SEMANTICS.set(differencer, StarlarkSemantics.DEFAULT)
        return evaluator
    }

    @Throws(java.lang.InterruptedException::class)
    private fun valueForPath(path: Path?): FileValue {
        return valueForPathHelper(pkgRoot, path, makeEvaluator())
    }

    @Throws(java.lang.InterruptedException::class)
    private fun valueForPathOutsidePkgRoot(path: Path?): FileValue {
        return valueForPathHelper(Root.absoluteRoot(fs), path, makeEvaluator())
    }

    @Throws(java.lang.InterruptedException::class)
    private fun valueForRootedPath(rootedPath: RootedPath?): FileValue {
        return valueForRootedPathHelper(rootedPath, makeEvaluator())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileValueHashCodeAndEqualsContract() {
        val pathA: Path = file("a", "a")
        val pathB: Path = file("b", "b")
        val pathC: Path = symlink("c", "a")
        val pathD: Path = directory("d")
        val pathDA: Path = file("d/a", "da")
        val pathE: Path = symlink("e", "d")
        val pathF: Path = symlink("f", "a")

        val valueA1: FileValue = valueForPathOutsidePkgRoot(pathA)
        val valueA2: FileValue = valueForPathOutsidePkgRoot(pathA)
        val valueB1: FileValue = valueForPathOutsidePkgRoot(pathB)
        val valueB2: FileValue = valueForPathOutsidePkgRoot(pathB)
        val valueC1: FileValue = valueForPathOutsidePkgRoot(pathC)
        val valueC2: FileValue = valueForPathOutsidePkgRoot(pathC)
        val valueD1: FileValue = valueForPathOutsidePkgRoot(pathD)
        val valueD2: FileValue = valueForPathOutsidePkgRoot(pathD)
        val valueDA1: FileValue = valueForPathOutsidePkgRoot(pathDA)
        val valueDA2: FileValue = valueForPathOutsidePkgRoot(pathDA)
        val valueE1: FileValue = valueForPathOutsidePkgRoot(pathE)
        val valueE2: FileValue = valueForPathOutsidePkgRoot(pathE)
        val valueF1: FileValue = valueForPathOutsidePkgRoot(pathF)
        val valueF2: FileValue = valueForPathOutsidePkgRoot(pathF)

        EqualsTester()
            .addEqualityGroup(valueA1, valueA2)
            .addEqualityGroup(
                valueB1,
                valueB2
            ) // Both 'f' and 'c' are transitively symlinks to 'a', so all of these FileValues ought to be
            // equal.
            .addEqualityGroup(valueC1, valueC2, valueF1, valueF2)
            .addEqualityGroup(valueD1, valueD2)
            .addEqualityGroup(valueDA1, valueDA2)
            .addEqualityGroup(valueE1, valueE2)
            .testEquals()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIsDirectory() {
        assertThat(valueForPath(file("a")).isDirectory()).isFalse()
        assertThat(valueForPath(path("nonexistent")).isDirectory()).isFalse()
        assertThat(valueForPath(directory("dir")).isDirectory()).isTrue()

        assertThat(valueForPath(symlink("sa", "a")).isDirectory()).isFalse()
        assertThat(valueForPath(symlink("smissing", "missing")).isDirectory()).isFalse()
        assertThat(valueForPath(symlink("sdir", "dir")).isDirectory()).isTrue()
        assertThat(valueForPath(symlink("ssdir", "sdir")).isDirectory()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIsFile() {
        assertThat(valueForPath(file("a")).isFile()).isTrue()
        assertThat(valueForPath(path("nonexistent")).isFile()).isFalse()
        assertThat(valueForPath(directory("dir")).isFile()).isFalse()

        assertThat(valueForPath(symlink("sa", "a")).isFile()).isTrue()
        assertThat(valueForPath(symlink("smissing", "missing")).isFile()).isFalse()
        assertThat(valueForPath(symlink("sdir", "dir")).isFile()).isFalse()
        assertThat(valueForPath(symlink("ssfile", "sa")).isFile()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSimpleIndependentFiles() {
        file("a")
        file("b")

        val seenFiles: MutableSet<RootedPath?> = com.google.common.collect.Sets.newHashSet<RootedPath?>()
        seenFiles.addAll(getFilesSeenAndAssertValueChangesIfContentsOfFileChanges("a", false, "b"))
        seenFiles.addAll(getFilesSeenAndAssertValueChangesIfContentsOfFileChanges("b", false, "a"))
        Truth.assertThat(seenFiles).containsExactly(rootedPath("a"), rootedPath("b"), rootedPath(""))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSimpleSymlink() {
        symlink("a", "b")
        file("b")

        assertValueChangesIfContentsOfFileChanges("a", false, "b")
        assertValueChangesIfContentsOfFileChanges("b", true, "a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitiveSymlink() {
        symlink("a", "b")
        symlink("b", "c")
        file("c")

        assertValueChangesIfContentsOfFileChanges("a", false, "b")
        assertValueChangesIfContentsOfFileChanges("a", false, "c")
        assertValueChangesIfContentsOfFileChanges("b", true, "a")
        assertValueChangesIfContentsOfFileChanges("c", true, "b")
        assertValueChangesIfContentsOfFileChanges("c", true, "a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileUnderBrokenDirectorySymlink() {
        symlink("a", "b/c")
        symlink("b", "d")
        assertValueChangesIfContentsOfDirectoryChanges("b", true, "a/e")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileUnderDirectorySymlink() {
        symlink("a", "b/c")
        symlink("b", "d")
        file("d/c/e")
        assertValueChangesIfContentsOfDirectoryChanges("b", true, "a/e")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinkInDirectory() {
        symlink("a/aa", "ab")
        file("a/ab")

        assertValueChangesIfContentsOfFileChanges("a/aa", false, "a/ab")
        assertValueChangesIfContentsOfFileChanges("a/ab", true, "a/aa")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRelativeSymlink() {
        symlink("a/aa/aaa", "../ab/aba")
        file("a/ab/aba")
        assertValueChangesIfContentsOfFileChanges("a/ab/aba", true, "a/aa/aaa")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoubleRelativeSymlink() {
        symlink("a/b/c/d", "../../e/f")
        file("a/e/f")
        assertValueChangesIfContentsOfFileChanges("a/e/f", true, "a/b/c/d")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExternalRelativeSymlink() {
        symlink("a", "../outside")
        file("b")
        file("../outside")
        val seenFiles: MutableSet<RootedPath?> = com.google.common.collect.Sets.newHashSet<RootedPath?>()
        seenFiles.addAll(getFilesSeenAndAssertValueChangesIfContentsOfFileChanges("b", false, "a"))
        seenFiles.addAll(
            getFilesSeenAndAssertValueChangesIfContentsOfFileChanges("../outside", true, "a")
        )
        Truth.assertThat(seenFiles)
            .containsExactly(
                rootedPath("a"),
                rootedPath(""),
                RootedPath.toRootedPath(Root.absoluteRoot(fs), PathFragment.create("/")),
                RootedPath.toRootedPath(Root.absoluteRoot(fs), PathFragment.create("/outside"))
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAbsoluteSymlink() {
        symlink("a", "/absolute")
        file("b")
        file("/absolute")
        val seenFiles: MutableSet<RootedPath?> = com.google.common.collect.Sets.newHashSet<RootedPath?>()
        seenFiles.addAll(getFilesSeenAndAssertValueChangesIfContentsOfFileChanges("b", false, "a"))
        seenFiles.addAll(
            getFilesSeenAndAssertValueChangesIfContentsOfFileChanges("/absolute", true, "a")
        )
        Truth.assertThat(seenFiles)
            .containsExactly(
                rootedPath("a"),
                rootedPath(""),
                RootedPath.toRootedPath(Root.absoluteRoot(fs), PathFragment.create("/")),
                RootedPath.toRootedPath(Root.absoluteRoot(fs), PathFragment.create("/absolute"))
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAbsoluteSymlinkToExternal() {
        val externalPath: String =
            outputBase
                .getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION)
                .getRelative("a/b")
                .getPathString()
        symlink("a", externalPath)
        file("b")
        file(externalPath)
        val seenFiles: MutableSet<RootedPath?> = com.google.common.collect.Sets.newHashSet<RootedPath?>()
        seenFiles.addAll(getFilesSeenAndAssertValueChangesIfContentsOfFileChanges("b", false, "a"))
        seenFiles.addAll(
            getFilesSeenAndAssertValueChangesIfContentsOfFileChanges(externalPath, true, "a")
        )
        Truth.assertThat(seenFiles)
            .containsExactly(
                rootedPath("a"),
                rootedPath(""),
                rootedPath("/output_base"),
                rootedPath("/output_base/external"),
                rootedPath("/output_base/external/a"),
                rootedPath("/output_base/external/a/b")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinkAsAncestor() {
        file("a/b/c/d")
        symlink("f", "a/b/c")
        assertValueChangesIfContentsOfFileChanges("a/b/c/d", true, "f/d")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinkAsAncestorNested() {
        file("a/b/c/d")
        symlink("f", "a/b")
        assertValueChangesIfContentsOfFileChanges("a/b/c/d", true, "f/c/d")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTwoSymlinksInAncestors() {
        file("a/aa/aaa/aaaa")
        symlink("b/ba/baa", "../../a/aa")
        symlink("c/ca", "../b/ba")

        assertValueChangesIfContentsOfFileChanges("c/ca", true, "c/ca/baa/aaa/aaaa")
        assertValueChangesIfContentsOfFileChanges("b/ba/baa", true, "c/ca/baa/aaa/aaaa")
        assertValueChangesIfContentsOfFileChanges("a/aa/aaa/aaaa", true, "c/ca/baa/aaa/aaaa")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSelfReferencingSymlink() {
        symlink("a", "a")
        assertError("a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMutuallyReferencingSymlinks() {
        symlink("a", "b")
        symlink("b", "a")
        assertError("a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRecursiveNestingSymlink() {
        symlink("a/a", "../a")
        file("b")
        assertNoError("a/a/a/a/b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSimpleUnboundedAncestorSymlinkExpansionChainReported() {
        symlink("a/a", "../a")
        val v: FileValue = valueForPath(path("a/a"))
        assertThat(v.unboundedAncestorSymlinkExpansionChain())
            .containsExactly(rootedPath("a/a"), rootedPath("a"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBrokenSymlink() {
        symlink("a", "b")
        val seenFiles: MutableSet<RootedPath?> = com.google.common.collect.Sets.newHashSet<RootedPath?>()
        seenFiles.addAll(getFilesSeenAndAssertValueChangesIfContentsOfFileChanges("b", true, "a"))
        seenFiles.addAll(getFilesSeenAndAssertValueChangesIfContentsOfFileChanges("a", false, "b"))
        Truth.assertThat(seenFiles).containsExactly(rootedPath("a"), rootedPath("b"), rootedPath(""))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBrokenDirectorySymlink() {
        symlink("a", "b")
        file("c")

        assertValueChangesIfContentsOfDirectoryChanges("a", true, "a/aa")
        // This just creates the directory "b", which doesn't change the value for "a/aa", since "a/aa"
        // still has real path "b/aa" and still doesn't exist.
        assertValueChangesIfContentsOfDirectoryChanges("b", false, "a/aa")
        assertValueChangesIfContentsOfFileChanges("c", false, "a/aa")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTraverseIntoVirtualNonDirectory() {
        file("dir/a")
        symlink("vdir", "dir")
        // The following evaluation should not throw IOExceptions.
        assertNoError("vdir/a/aa/aaa")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileCreation() {
        val a: FileValue = valueForPath(path("file"))
        val p: Path = file("file")
        val b: FileValue = valueForPath(p)
        assertThat(a.equals(b)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyFile() {
        val digest = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        createFsAndRoot(
            object : CustomInMemoryFs(manualClock) {
                override fun getFastDigest(path: PathFragment?): ByteArray {
                    return digest
                }
            })
        val p: Path = file("file")
        p.setLastModifiedTime(0L)
        val a: FileValue = valueForPath(p)
        p.setLastModifiedTime(1L)
        assertThat(valueForPath(p)).isEqualTo(a)
        FileSystemUtils.writeContentAsLatin1(p, "content")
        // Same digest, but now non-empty.
        assertThat(valueForPath(p)).isNotEqualTo(a)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUnreadableFileWithNoFastDigest() {
        val p: Path = file("unreadable")
        p.chmod(0)
        p.setLastModifiedTime(0L)

        val value: FileValue = valueForPath(p)
        assertThat(value.exists()).isTrue()
        assertThat(value.getDigest()).isNull()

        p.setLastModifiedTime(10L)
        assertThat(valueForPath(p)).isNotEqualTo(value)

        p.setLastModifiedTime(0L)
        assertThat(valueForPath(p)).isEqualTo(value)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUnreadableFileWithFastDigest() {
        val expectedDigest = byteArrayOf(1, 2, 3, 4)

        createFsAndRoot(
            object : CustomInMemoryFs(manualClock) {
                override fun getFastDigest(path: PathFragment): ByteArray? {
                    return if (path.getBaseName().equals("unreadable")) expectedDigest else null
                }
            })

        val p: Path = file("unreadable")
        p.chmod(0)

        val value: FileValue = valueForPath(p)
        assertThat(value.exists()).isTrue()
        assertThat(value.getDigest()).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileModificationDigest() {
        fastDigest = true
        val p: Path = file("file")
        val a: FileValue = valueForPath(p)
        FileSystemUtils.writeContentAsLatin1(p, "goop")
        val b: FileValue = valueForPath(p)
        assertThat(a.equals(b)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testModTimeVsDigest() {
        val p: Path = file("somefile", "fizzley")

        fastDigest = true
        val aMd5: FileValue = valueForPath(p)
        fastDigest = false
        val aModTime: FileValue = valueForPath(p)
        assertThat(aModTime).isNotEqualTo(aMd5)
        EqualsTester().addEqualityGroup(aMd5).addEqualityGroup(aModTime).testEquals()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileDeletion() {
        val p: Path = file("file")
        val a: FileValue = valueForPath(p)
        p.delete()
        val b: FileValue = valueForPath(p)
        assertThat(a.equals(b)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileTypeChange() {
        var p: Path = file("file")
        val a: FileValue = valueForPath(p)
        p.delete()
        p = symlink("file", "foo")
        val b: FileValue = valueForPath(p)
        p.delete()
        pkgRoot.getRelative("file").createDirectoryAndParents()
        val c: FileValue = valueForPath(p)
        assertThat(a.equals(b)).isFalse()
        assertThat(b.equals(c)).isFalse()
        assertThat(a.equals(c)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinkTargetChange() {
        var p: Path = symlink("symlink", "foo")
        val a: FileValue = valueForPath(p)
        p.delete()
        p = symlink("symlink", "bar")
        val b: FileValue = valueForPath(p)
        assertThat(b).isNotEqualTo(a)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinkTargetContentsChangeCTime() {
        fastDigest = false
        val fooPath: Path = file("foo")
        FileSystemUtils.writeContentAsLatin1(fooPath, "foo")
        val p: Path = symlink("symlink", "foo")
        val a: FileValue = valueForPath(p)
        manualClock.advanceMillis(1)
        fooPath.chmod(365)
        manualClock.advanceMillis(1)
        val b: FileValue = valueForPath(p)
        assertThat(b).isNotEqualTo(a)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinkTargetContentsChangeDigest() {
        fastDigest = true
        val fooPath: Path = file("foo")
        FileSystemUtils.writeContentAsLatin1(fooPath, "foo")
        val p: Path = symlink("symlink", "foo")
        val a: FileValue = valueForPath(p)
        FileSystemUtils.writeContentAsLatin1(fooPath, "bar")
        val b: FileValue = valueForPath(p)
        assertThat(b).isNotEqualTo(a)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRealPath() {
        file("file")
        directory("directory")
        file("directory/file")
        symlink("directory/link", "file")
        symlink("directory/doublelink", "link")
        symlink("directory/parentlink", "../file")
        symlink("directory/doubleparentlink", "../link")
        symlink("link", "file")
        symlink("deadlink", "missing_file")
        symlink("dirlink", "directory")
        symlink("doublelink", "link")
        symlink("doubledirlink", "dirlink")

        checkRealPath("file")
        checkRealPath("link")
        checkRealPath("doublelink")

        for (dir in arrayOf<String>("directory", "dirlink", "doubledirlink")) {
            checkRealPath(dir)
            checkRealPath(dir + "/file")
            checkRealPath(dir + "/link")
            checkRealPath(dir + "/doublelink")
            checkRealPath(dir + "/parentlink")
        }

        assertRealPath("missing", "missing")
        assertRealPath("deadlink", "missing_file")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRealPathRelativeSymlink() {
        directory("dir")
        symlink("dir/link", "../dir2")
        directory("dir2")
        symlink("dir2/filelink", "../dest")
        file("dest")

        checkRealPath("dir/link/filelink")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinkAcrossPackageRoots() {
        val otherPkgRoot: Path? = fs.getPath("/other_root")
        pkgLocator =
            PathPackageLocator(
                outputBase,
                com.google.common.collect.ImmutableList.of<E?>(pkgRoot, Root.fromPath(otherPkgRoot)),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
            )
        symlink("a", "/other_root/b")
        assertValueChangesIfContentsOfFileChanges("/other_root/b", true, "a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilesOutsideRootIsReEvaluated() {
        val file: Path = file("/outsideroot")
        val evaluator: MemoizingEvaluator = makeEvaluator()
        val key: SkyKey = skyKey("/outsideroot")
        var result: EvaluationResult<SkyValue?>
        result = evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(key), EVALUATION_OPTIONS)
        if (result.hasError()) {
            org.junit.Assert.fail(java.lang.String.format("Evaluation error for %s: %s", key, result.getError()))
        }
        val oldValue: FileValue = result.get(key) as FileValue
        assertThat(oldValue.exists()).isTrue()

        file.delete()
        differencer.invalidate(com.google.common.collect.ImmutableList.of<E?>(fileStateSkyKey("/outsideroot")))
        result = evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(key), EVALUATION_OPTIONS)
        if (result.hasError()) {
            org.junit.Assert.fail(java.lang.String.format("Evaluation error for %s: %s", key, result.getError()))
        }
        val newValue: FileValue = result.get(key) as FileValue
        assertThat(newValue).isNotSameInstanceAs(oldValue)
        assertThat(newValue.exists()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilesOutsideRootWhenExternalAssumedNonExistentAndImmutable() {
        file("/outsideroot")

        val evaluator: MemoizingEvaluator =
            makeEvaluator(ExternalFileAction.ASSUME_NON_EXISTENT_AND_IMMUTABLE_FOR_EXTERNAL_PATHS)
        val key: SkyKey = skyKey("/outsideroot")
        val result: EvaluationResult<SkyValue?> =
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(key), EVALUATION_OPTIONS)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val value: FileValue = result.get(key) as FileValue
        assertThat(value).isNotNull()
        assertThat(value.exists()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAbsoluteSymlinksToFilesOutsideRootWhenExternalAssumedNonExistentAndImmutable() {
        file("/outsideroot")
        symlink("a", "/outsideroot")

        val evaluator: MemoizingEvaluator =
            makeEvaluator(ExternalFileAction.ASSUME_NON_EXISTENT_AND_IMMUTABLE_FOR_EXTERNAL_PATHS)
        val key: SkyKey = skyKey("a")
        val result: EvaluationResult<SkyValue?> =
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(key), EVALUATION_OPTIONS)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val value: FileValue = result.get(key) as FileValue
        assertThat(value).isNotNull()
        assertThat(value.exists()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAbsoluteSymlinksReferredByInternalFilesToFilesOutsideRootWhenExternalAssumedNonExistentAndImmutable() {
        file("/outsideroot/src/foo/bar")
        symlink("/root/src", "/outsideroot/src")

        val evaluator: MemoizingEvaluator =
            makeEvaluator(ExternalFileAction.ASSUME_NON_EXISTENT_AND_IMMUTABLE_FOR_EXTERNAL_PATHS)
        val key: SkyKey = skyKey("/root/src/foo/bar")
        val result: EvaluationResult<SkyValue?> =
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(key), EVALUATION_OPTIONS)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val value: FileValue = result.get(key) as FileValue
        assertThat(value).isNotNull()
        assertThat(value.exists()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRelativeSymlinksToFilesOutsideRootWhenExternalAssumedNonExistentAndImmutable() {
        file("../outsideroot")
        symlink("a", "../outsideroot")
        val evaluator: MemoizingEvaluator =
            makeEvaluator(ExternalFileAction.ASSUME_NON_EXISTENT_AND_IMMUTABLE_FOR_EXTERNAL_PATHS)
        val key: SkyKey = skyKey("a")
        val result: EvaluationResult<SkyValue?> =
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(key), EVALUATION_OPTIONS)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val value: FileValue = result.get(key) as FileValue
        assertThat(value).isNotNull()
        assertThat(value.exists()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAbsoluteSymlinksBackIntoSourcesOkWhenExternalDisallowed() {
        val file: Path = file("insideroot")
        symlink("a", file.getPathString())

        val evaluator: MemoizingEvaluator =
            makeEvaluator(ExternalFileAction.ASSUME_NON_EXISTENT_AND_IMMUTABLE_FOR_EXTERNAL_PATHS)
        val key: SkyKey = skyKey("a")
        val result: EvaluationResult<SkyValue?> =
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(key), EVALUATION_OPTIONS)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val value: FileValue = result.get(key) as FileValue
        assertThat(value).isNotNull()
        assertThat(value.exists()).isTrue()
        assertThat(
            value.realRootedPath(key.argument() as RootedPath?).getRootRelativePath().getPathString()
        )
            .isEqualTo("insideroot")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSize() {
        val file: Path = file("file")
        val fileSize = 20
        FileSystemUtils.writeContentAsLatin1(file, "a".repeat(fileSize))
        assertThat(valueForPath(file).getSize()).isEqualTo(fileSize)
        val dir: Path = directory("directory")
        file(dir.getChild("child").getPathString())
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable { valueForPath(dir).getSize() })
        val nonexistent: Path? = fs.getPath("/root/noexist")
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable { valueForPath(nonexistent).getSize() })
        val fileSymlink: Path = symlink("link", "/root/file")
        // Symlink stores size of target, not link.
        assertThat(valueForPath(fileSymlink).getSize()).isEqualTo(fileSize)
        assertThat(fileSymlink.delete()).isTrue()

        val rootDirSymlink: Path = symlink("link", "/root/directory")
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable { valueForPath(rootDirSymlink).getSize() })
        assertThat(rootDirSymlink.delete()).isTrue()

        val noExistSymlink: Path = symlink("link", "/root/noexist")
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable { valueForPath(noExistSymlink).getSize() })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDigest() {
        val digestCalls: AtomicInteger = AtomicInteger(0)
        var expectedCalls = 0
        fs =
            object : CustomInMemoryFs(manualClock) {
                @Throws(IOException::class)
                public override fun getDigest(path: PathFragment?): ByteArray {
                    digestCalls.incrementAndGet()
                    return super.getDigest(path)
                }
            }
        pkgRoot = Root.fromPath(fs.getPath("/root"))
        val file: Path = file("file")
        FileSystemUtils.writeContentAsLatin1(file, "a".repeat(20))
        val digest: ByteArray? = file.getDigest()
        expectedCalls++
        Truth.assertThat(digestCalls.get()).isEqualTo(expectedCalls)
        var value: FileValue = valueForPath(file)
        expectedCalls++
        Truth.assertThat(digestCalls.get()).isEqualTo(expectedCalls)
        assertThat(value.getDigest()).isEqualTo(digest)
        // Digest is cached -- no filesystem access.
        Truth.assertThat(digestCalls.get()).isEqualTo(expectedCalls)
        fastDigest = false
        digestCalls.set(0)
        value = valueForPath(file)
        // No new digest calls.
        Truth.assertThat(digestCalls.get()).isEqualTo(0)
        assertThat(value.getDigest()).isNull()
        Truth.assertThat(digestCalls.get()).isEqualTo(0)
        fastDigest = true
        val dir: Path = directory("directory")
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable { assertThat(valueForPath(dir).getDigest()).isNull() })
        Truth.assertThat(digestCalls.get()).isEqualTo(0) // No digest calls made for directory.
        val nonexistent: Path? = fs.getPath("/root/noexist")
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable { assertThat(valueForPath(nonexistent).getDigest()).isNull() })
        Truth.assertThat(digestCalls.get()).isEqualTo(0) // No digest calls made for nonexistent file.
        val symlink: Path = symlink("link", "/root/file")
        value = valueForPath(symlink)
        Truth.assertThat(digestCalls.get()).isEqualTo(1)
        // Symlink stores digest of target, not link.
        assertThat(value.getDigest()).isEqualTo(digest)
        Truth.assertThat(digestCalls.get()).isEqualTo(1)
        digestCalls.set(0)
        assertThat(symlink.delete()).isTrue()
        // Symlink stores digest of target, not link, for directories too.
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable {
                assertThat(
                    valueForPath(
                        symlink(
                            "link",
                            "/root/directory"
                        )
                    ).getDigest()
                ).isNull()
            })
        Truth.assertThat(digestCalls.get()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoesntStatChildIfParentDoesntExist() {
        val fs = this.fs as CustomInMemoryFs
        // Our custom filesystem says "a" does not exist, so FileFunction shouldn't bother trying to
        // think about "a/b". Test for this by having a stat of "a/b" fail with an io error, and
        // observing that we don't encounter the error.
        fs.stubStat(path("a"), null)
        fs.stubStatError(path("a/b"), IOException("ouch!"))
        assertThat(valueForPath(path("a/b")).exists()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilesystemInconsistencies_getFastDigest() {
        val fs = this.fs as CustomInMemoryFs
        file("a")
        // Our custom filesystem says "a/b" exists but "a" does not exist.
        fs.stubFastDigestError(path("a"), IOException("nope"))
        val evaluator: MemoizingEvaluator = makeEvaluator()
        val skyKey: SkyKey = skyKey("a")
        val result: EvaluationResult<FileValue?> =
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(skyKey), EVALUATION_OPTIONS)
        assertThat(result.hasError()).isTrue()
        val errorInfo: ErrorInfo = result.getError(skyKey)
        assertThat(errorInfo.getException()).isInstanceOf(InconsistentFilesystemException::class.java)
        assertThat(errorInfo.getException()).hasMessageThat().contains("encountered error 'nope'")
        assertThat(errorInfo.getException()).hasMessageThat().contains("/root/a is no longer a file")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilesystemInconsistencies_getFastDigestAndIsReadableFailure() {
        createFsAndRoot(
            object : CustomInMemoryFs(manualClock) {
                @Throws(IOException::class)
                public override fun isReadable(path: PathFragment): Boolean {
                    if (path.getBaseName().equals("unreadable")) {
                        throw IOException("isReadable failed")
                    }
                    return super.isReadable(path)
                }
            })

        val p: Path = file("unreadable")
        p.chmod(0)

        val evaluator: MemoizingEvaluator = makeEvaluator()
        val skyKey: SkyKey = skyKey("unreadable")
        val result: EvaluationResult<FileValue?> =
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(skyKey), EVALUATION_OPTIONS)
        assertThat(result.hasError()).isTrue()
        val errorInfo: ErrorInfo = result.getError(skyKey)
        assertThat(errorInfo.getException()).isInstanceOf(InconsistentFilesystemException::class.java)
        assertThat(errorInfo.getException())
            .hasMessageThat()
            .contains("encountered error 'isReadable failed'")
        assertThat(errorInfo.getException())
            .hasMessageThat()
            .contains("/root/unreadable is no longer a file")
    }

    @Throws(java.lang.Exception::class)
    private fun runTestSymlinkCycle(ancestorCycle: Boolean, startInCycle: Boolean) {
        symlink("a", "b")
        symlink("b", "c")
        symlink("c", "d")
        symlink("d", "e")
        symlink("e", "c")
        // We build multiple keys at once to make sure the cycle is reported exactly once.
        val startToCycleMap: MutableMap<RootedPath?, com.google.common.collect.ImmutableList<RootedPath?>?> =
            com.google.common.collect.ImmutableMap.builder<RootedPath?, com.google.common.collect.ImmutableList<RootedPath?>?>()
                .put(
                    rootedPath("a"),
                    com.google.common.collect.ImmutableList.of<RootedPath?>(
                        rootedPath("c"),
                        rootedPath("d"),
                        rootedPath("e")
                    )
                )
                .put(
                    rootedPath("b"),
                    com.google.common.collect.ImmutableList.of<RootedPath?>(
                        rootedPath("c"),
                        rootedPath("d"),
                        rootedPath("e")
                    )
                )
                .put(
                    rootedPath("d"),
                    com.google.common.collect.ImmutableList.of<RootedPath?>(
                        rootedPath("d"),
                        rootedPath("e"),
                        rootedPath("c")
                    )
                )
                .put(
                    rootedPath("e"),
                    com.google.common.collect.ImmutableList.of<RootedPath?>(
                        rootedPath("e"),
                        rootedPath("c"),
                        rootedPath("d")
                    )
                )
                .put(
                    rootedPath("a/some/descendant"),
                    com.google.common.collect.ImmutableList.of<RootedPath?>(
                        rootedPath("c"),
                        rootedPath("d"),
                        rootedPath("e")
                    )
                )
                .put(
                    rootedPath("b/some/descendant"),
                    com.google.common.collect.ImmutableList.of<RootedPath?>(
                        rootedPath("c"),
                        rootedPath("d"),
                        rootedPath("e")
                    )
                )
                .put(
                    rootedPath("d/some/descendant"),
                    com.google.common.collect.ImmutableList.of<RootedPath?>(
                        rootedPath("d"),
                        rootedPath("e"),
                        rootedPath("c")
                    )
                )
                .put(
                    rootedPath("e/some/descendant"),
                    com.google.common.collect.ImmutableList.of<RootedPath?>(
                        rootedPath("e"),
                        rootedPath("c"),
                        rootedPath("d")
                    )
                )
                .buildOrThrow()
        val startToPathToCycleMap: MutableMap<RootedPath?, com.google.common.collect.ImmutableList<RootedPath?>?> =
            com.google.common.collect.ImmutableMap.builder<RootedPath?, com.google.common.collect.ImmutableList<RootedPath?>?>()
                .put(
                    rootedPath("a"),
                    com.google.common.collect.ImmutableList.of<RootedPath?>(rootedPath("a"), rootedPath("b"))
                )
                .put(rootedPath("b"), com.google.common.collect.ImmutableList.of<RootedPath?>(rootedPath("b")))
                .put(rootedPath("d"), com.google.common.collect.ImmutableList.of<RootedPath?>())
                .put(rootedPath("e"), com.google.common.collect.ImmutableList.of<RootedPath?>())
                .put(
                    rootedPath("a/some/descendant"),
                    com.google.common.collect.ImmutableList.of<RootedPath?>(rootedPath("a"), rootedPath("b"))
                )
                .put(
                    rootedPath("b/some/descendant"),
                    com.google.common.collect.ImmutableList.of<RootedPath?>(rootedPath("b"))
                )
                .put(rootedPath("d/some/descendant"), com.google.common.collect.ImmutableList.of<RootedPath?>())
                .put(rootedPath("e/some/descendant"), com.google.common.collect.ImmutableList.of<RootedPath?>())
                .buildOrThrow()
        val keys: com.google.common.collect.ImmutableList<SkyKey>?
        if (ancestorCycle && startInCycle) {
            keys = com.google.common.collect.ImmutableList.of<SkyKey>(
                skyKey("d/some/descendant"),
                skyKey("e/some/descendant")
            )
        } else if (ancestorCycle) {
            keys = com.google.common.collect.ImmutableList.of<SkyKey>(
                skyKey("a/some/descendant"),
                skyKey("b/some/descendant")
            )
        } else if (startInCycle) {
            keys = com.google.common.collect.ImmutableList.of<SkyKey>(skyKey("d"), skyKey("e"))
        } else {
            keys = com.google.common.collect.ImmutableList.of<SkyKey>(skyKey("a"), skyKey("b"))
        }
        val eventHandler: StoredEventHandler = StoredEventHandler()
        val evaluator: MemoizingEvaluator = makeEvaluator()
        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(true)
                .setParallelism(DEFAULT_THREAD_COUNT)
                .setEventHandler(eventHandler)
                .build()
        val result: EvaluationResult<FileValue?> = evaluator.evaluate(keys, evaluationContext)
        assertThat(result.hasError()).isTrue()
        for (key in keys) {
            val errorInfo: ErrorInfo = result.getError(key)
            // FileFunction detects symlink cycles explicitly.
            assertThat(errorInfo.getCycleInfo()).isEmpty()
            val fsce: FileSymlinkCycleException = errorInfo.getException() as FileSymlinkCycleException
            val start: RootedPath? = key.argument() as RootedPath?
            assertThat(fsce.getPathToCycle())
                .containsExactlyElementsIn(startToPathToCycleMap.get(start))
                .inOrder()
            assertThat(fsce.getCycle()).containsExactlyElementsIn(startToCycleMap.get(start)).inOrder()
        }
        // Check that the unique cycle was reported exactly once.
        Truth.assertThat(eventHandler.getEvents()).hasSize(1)
        Truth.assertThat(
            com.google.common.collect.Iterables.getOnlyElement<com.google.devtools.build.lib.events.Event?>(
                eventHandler.getEvents()
            ).getMessage()
        )
            .contains("circular symlinks detected")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinkCycle_ancestorCycle_startInCycle() {
        runTestSymlinkCycle( /* ancestorCycle= */true,  /* startInCycle= */true)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinkCycle_ancestorCycle_startOutOfCycle() {
        runTestSymlinkCycle( /* ancestorCycle= */true,  /* startInCycle= */false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinkCycle_regularCycle_startInCycle() {
        runTestSymlinkCycle( /* ancestorCycle= */false,  /* startInCycle= */true)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinkCycle_regularCycle_startOutOfCycle() {
        runTestSymlinkCycle( /* ancestorCycle= */false,  /* startInCycle= */false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSerialization() {
        fs = FsUtils.TEST_FILESYSTEM
        pkgRoot = Root.absoluteRoot(fs)
        val a: FileValue = valueForPath(fs.getPath("/"))
        val tmp: Path? = fs.getPath("/file.txt")
        FileSystemUtils.writeContentAsLatin1(tmp, "test contents")
        val b: FileValue = valueForPath(tmp)
        com.google.common.base.Preconditions.checkState(b.isFile())
        val c: FileValue = valueForPath(fs.getPath("/does/not/exist"))
        val serializationTester: SerializationTester = SerializationTester(a, b, c).makeMemoizing()
        FsUtils.addDependencies(serializationTester)
        serializationTester.runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileStateEquality() {
        file("a")
        symlink("b1", "a")
        symlink("b2", "a")
        symlink("b3", "zzz")
        directory("d1")
        directory("d2")
        val file: SkyKey = fileStateSkyKey("a")
        val symlink1: SkyKey = fileStateSkyKey("b1")
        val symlink2: SkyKey = fileStateSkyKey("b2")
        val symlink3: SkyKey = fileStateSkyKey("b3")
        val missing1: SkyKey = fileStateSkyKey("c1")
        val missing2: SkyKey = fileStateSkyKey("c2")
        val directory1: SkyKey = fileStateSkyKey("d1")
        val directory2: SkyKey = fileStateSkyKey("d2")
        val keys: com.google.common.collect.ImmutableList<SkyKey?> =
            com.google.common.collect.ImmutableList.of<SkyKey?>(
                file, symlink1, symlink2, symlink3, missing1, missing2, directory1, directory2
            )

        val evaluator: MemoizingEvaluator = makeEvaluator()
        val result: EvaluationResult<SkyValue?> = evaluator.evaluate(keys, EVALUATION_OPTIONS)

        EqualsTester()
            .addEqualityGroup(result.get(file))
            .addEqualityGroup(result.get(symlink1), result.get(symlink2))
            .addEqualityGroup(result.get(symlink3))
            .addEqualityGroup(result.get(missing1), result.get(missing2))
            .addEqualityGroup(result.get(directory1), result.get(directory2))
            .testEquals()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinkToPackagePathBoundary() {
        val path: Path = path("this/is/a/path")
        FileSystemUtils.ensureSymbolicLink(path, pkgRoot.asPath())
        assertNoError("this/is/a/path")
    }

    @Throws(java.lang.Exception::class)
    private fun runTestSimpleInfiniteSymlinkExpansion(
        symlinkToAncestor: Boolean, absoluteSymlink: Boolean
    ) {
        val otherPath: Path = path("other")
        val otherRootedPath: RootedPath? = RootedPath.toRootedPath(pkgRoot, pkgRoot.relativize(otherPath))
        val ancestorPath: Path = path("a")
        val ancestorRootedPath: RootedPath =
            RootedPath.toRootedPath(pkgRoot, pkgRoot.relativize(ancestorPath))
        FileSystemUtils.ensureSymbolicLink(otherPath, ancestorPath)
        val intermediatePath: Path = path("inter")
        val intermediateRootedPath: RootedPath? =
            RootedPath.toRootedPath(pkgRoot, pkgRoot.relativize(intermediatePath))
        val descendantPath: Path = path("a/b/c/d/e")
        val descendantRootedPath: RootedPath =
            RootedPath.toRootedPath(pkgRoot, pkgRoot.relativize(descendantPath))
        if (symlinkToAncestor) {
            FileSystemUtils.ensureSymbolicLink(descendantPath, intermediatePath)
            if (absoluteSymlink) {
                FileSystemUtils.ensureSymbolicLink(intermediatePath, ancestorPath)
            } else {
                FileSystemUtils.ensureSymbolicLink(
                    intermediatePath, ancestorRootedPath.getRootRelativePath()
                )
            }
        } else {
            FileSystemUtils.ensureSymbolicLink(ancestorPath, intermediatePath)
            if (absoluteSymlink) {
                FileSystemUtils.ensureSymbolicLink(intermediatePath, descendantPath)
            } else {
                FileSystemUtils.ensureSymbolicLink(
                    intermediatePath, descendantRootedPath.getRootRelativePath()
                )
            }
        }
        val eventHandler: StoredEventHandler = StoredEventHandler()
        val evaluator: MemoizingEvaluator = makeEvaluator()
        val ancestorPathKey: SkyKey? = FileValue.key(ancestorRootedPath)
        val descendantPathKey: SkyKey? = FileValue.key(descendantRootedPath)
        val otherPathKey: SkyKey? = FileValue.key(otherRootedPath)
        val keys: com.google.common.collect.ImmutableList<SkyKey?>?
        val errorKeys: com.google.common.collect.ImmutableList<SkyKey?>?
        val expectedChain: com.google.common.collect.ImmutableList<RootedPath?>?
        if (symlinkToAncestor) {
            keys = com.google.common.collect.ImmutableList.of<SkyKey?>(descendantPathKey, otherPathKey)
            errorKeys = com.google.common.collect.ImmutableList.of<SkyKey?>(descendantPathKey)
            expectedChain =
                com.google.common.collect.ImmutableList.of<RootedPath?>(
                    descendantRootedPath,
                    intermediateRootedPath,
                    ancestorRootedPath
                )
        } else {
            keys = com.google.common.collect.ImmutableList.of<SkyKey?>(ancestorPathKey, otherPathKey)
            errorKeys = keys
            expectedChain =
                com.google.common.collect.ImmutableList.of<RootedPath?>(
                    ancestorRootedPath,
                    intermediateRootedPath,
                    descendantRootedPath
                )
        }

        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(true)
                .setParallelism(DEFAULT_THREAD_COUNT)
                .setEventHandler(eventHandler)
                .build()
        val result: EvaluationResult<FileValue?> = evaluator.evaluate(keys, evaluationContext)
        if (symlinkToAncestor) {
            assertThat(result.hasError()).isFalse()
        } else {
            assertThat(result.hasError()).isTrue()
            for (key in errorKeys) {
                val errorInfo: ErrorInfo = result.getError(key)
                // FileFunction detects infinite symlink expansion explicitly.
                assertThat(errorInfo.getCycleInfo()).isEmpty()
                val fsiee: FileSymlinkInfiniteExpansionException =
                    errorInfo.getException() as FileSymlinkInfiniteExpansionException
                assertThat(fsiee).hasMessageThat().contains("Infinite symlink expansion")
                assertThat(fsiee.getChain()).containsExactlyElementsIn(expectedChain).inOrder()
            }
            // Check that the unique symlink expansion error was reported exactly once.
            Truth.assertThat(eventHandler.getEvents()).hasSize(1)
            Truth.assertThat(
                com.google.common.collect.Iterables.getOnlyElement<com.google.devtools.build.lib.events.Event?>(
                    eventHandler.getEvents()
                ).getMessage()
            )
                .contains("infinite symlink expansion detected")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInfiniteSymlinkExpansion_absoluteSymlinkToDescendant() {
        runTestSimpleInfiniteSymlinkExpansion( /* symlinkToAncestor= */
            false,  /* absoluteSymlink= */true
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInfiniteSymlinkExpansion_relativeSymlinkToDescendant() {
        runTestSimpleInfiniteSymlinkExpansion( /* symlinkToAncestor= */
            false,  /* absoluteSymlink= */false
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInfiniteSymlinkExpansion_absoluteSymlinkToAncestor() {
        runTestSimpleInfiniteSymlinkExpansion( /* symlinkToAncestor= */
            true,  /* absoluteSymlink= */true
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInfiniteSymlinkExpansion_relativeSymlinkToAncestor() {
        runTestSimpleInfiniteSymlinkExpansion( /* symlinkToAncestor= */
            true,  /* absoluteSymlink= */false
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInfiniteSymlinkExpansion_symlinkToReferrerToAncestor() {
        symlink("d", "a")
        directory("a/b")
        symlink("a/b/c", "../../d/b")
        symlink("e", "a/b/c")
        val fPath: Path = symlink("f", "e")

        val rootedPathF: RootedPath? = RootedPath.toRootedPath(pkgRoot, pkgRoot.relativize(fPath))
        val keyF: SkyKey = FileValue.key(rootedPathF)

        val eventHandler: StoredEventHandler = StoredEventHandler()
        val evaluator: MemoizingEvaluator = makeEvaluator()
        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(true)
                .setParallelism(DEFAULT_THREAD_COUNT)
                .setEventHandler(eventHandler)
                .build()
        val result: EvaluationResult<FileValue?> =
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(keyF), evaluationContext)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val e: FileValue = result.get(keyF)
        assertThat(e.pathToUnboundedAncestorSymlinkExpansionChain())
            .containsExactly(rootedPath("f"), rootedPath("e"))
            .inOrder()
        assertThat(e.unboundedAncestorSymlinkExpansionChain())
            .containsExactly(rootedPath("a/b/c"), rootedPath("d/b"), rootedPath("a/b"))
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInfiniteSymlinkExpansion_symlinkToReferrerToAncestor_levelsOfDirectorySymlinks() {
        symlink("dir1/a", "../dir2")
        symlink("dir2/b", "../dir1")

        val rootedPathDir1AB: RootedPath? =
            RootedPath.toRootedPath(pkgRoot, pkgRoot.relativize(path("dir1/a/b")))
        val keyDir1AB: SkyKey = FileValue.key(rootedPathDir1AB)

        val eventHandler: StoredEventHandler = StoredEventHandler()
        val evaluator: MemoizingEvaluator = makeEvaluator()
        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(true)
                .setParallelism(DEFAULT_THREAD_COUNT)
                .setEventHandler(eventHandler)
                .build()
        val result: EvaluationResult<FileValue?>? =
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(keyDir1AB), evaluationContext)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testChildOfNonexistentParent() {
        val ancestor: Path = directory("this/is/an/ancestor")
        val parent: Path = ancestor.getChild("parent")
        val child: Path? = parent.getChild("child")
        assertThat(valueForPath(parent).exists()).isFalse()
        assertThat(valueForPath(child).exists()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInjectionOverIOException() {
        val fs = this.fs as CustomInMemoryFs
        val foo: Path = file("foo")
        val fooKey: SkyKey = skyKey("foo")
        fs.stubStatError(foo, IOException("bork"))
        val evaluator: MemoizingEvaluator = makeEvaluator()
        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(true)
                .setParallelism(1)
                .setEventHandler(NullEventHandler.INSTANCE)
                .build()
        var result: EvaluationResult<FileValue?> =
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(fooKey), evaluationContext)
        val errorInfoSubject: ErrorInfoSubject =
            EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasErrorEntryForKeyThat(fooKey)
        errorInfoSubject.isTransient()
        errorInfoSubject.hasExceptionThat().hasMessageThat().isEqualTo("bork")
        fs.stubbedStatErrors.remove(foo.asFragment())
        differencer.inject(
            fileStateSkyKey("foo"),
            Delta.justNew(
                FileStateValue.create(
                    RootedPath.toRootedPath(pkgRoot, foo),
                    SyscallCache.NO_CACHE,
                    TimestampGranularityMonitor(com.google.devtools.build.lib.clock.BlazeClock.instance())
                )
            )
        )
        result = evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(fooKey), evaluationContext)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        assertThat(result.get(fooKey).exists()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultipleLevelsOfDirectorySymlinks_clean() {
        symlink("a/b/c", "../c")
        val abcd: Path = path("a/b/c/d")
        symlink("a/c/d", "../d")
        assertThat(valueForPath(abcd).isSymlink()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultipleLevelsOfDirectorySymlinks_incremental() {
        val evaluator: MemoizingEvaluator = makeEvaluator()

        symlink("a/b/c", "../c")
        val acd: Path = directory("a/c/d")
        val abcd: Path = path("a/b/c/d")

        var abcdFileValue: FileValue = valueForPathHelper(pkgRoot, abcd, evaluator)
        assertThat(abcdFileValue.isDirectory()).isTrue()
        assertThat(abcdFileValue.isSymlink()).isFalse()

        acd.delete()
        symlink("a/c/d", "../d")
        differencer.invalidate(com.google.common.collect.ImmutableList.of<E?>(fileStateSkyKey("a/c/d")))

        abcdFileValue = valueForPathHelper(pkgRoot, abcd, evaluator)

        assertThat(abcdFileValue.isSymlink()).isTrue()
    }

    @Throws(java.lang.Exception::class)
    private fun checkRealPath(pathString: String?) {
        val realPath: Path? = pkgRoot.getRelative(pathString).resolveSymbolicLinks()
        assertRealPath(pathString, pkgRoot.relativize(realPath).toString())
    }

    @Throws(java.lang.Exception::class)
    private fun assertRealPath(pathString: String?, expectedRealPathString: String?) {
        val evaluator: MemoizingEvaluator = makeEvaluator()
        val key: SkyKey = skyKey(pathString)
        val result: EvaluationResult<SkyValue?> =
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(key), EVALUATION_OPTIONS)
        if (result.hasError()) {
            org.junit.Assert.fail(java.lang.String.format("Evaluation error for %s: %s", key, result.getError()))
        }
        val fileValue: FileValue = result.get(key) as FileValue
        assertThat(fileValue.realRootedPath(key.argument() as RootedPath?).asPath().toString())
            .isEqualTo(pkgRoot.getRelative(expectedRealPathString).toString())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLogicalChainDuringResolution_directory_simpleSymlink() {
        symlink("a", "b")
        symlink("b", "c")
        directory("c")
        val rootedPath: RootedPath = rootedPath("a")
        val fileValue: FileValue = valueForRootedPath(rootedPath)
        assertThat(fileValue).isInstanceOf(SymlinkFileValueWithStoredChain::class.java)
        assertThat(fileValue.getUnresolvedLinkTarget()).isEqualTo(PathFragment.create("b"))
        assertThat(fileValue.logicalChainDuringResolution(rootedPath))
            .containsExactly(rootedPath("a"), rootedPath("b"), rootedPath("c"))
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLogicalChainDuringResolution_directory_simpleAncestorSymlink() {
        symlink("a", "b")
        symlink("b", "c")
        directory("c/d")
        val rootedPath: RootedPath = rootedPath("a/d")
        val fileValue: FileValue = valueForRootedPath(rootedPath)
        assertThat(fileValue).isInstanceOf(DifferentRealPathFileValueWithStoredChain::class.java)
        assertThat(fileValue.realRootedPath(rootedPath)).isEqualTo(rootedPath("c/d"))
        assertThat(fileValue.logicalChainDuringResolution(rootedPath))
            .containsExactly(rootedPath("a/d"), rootedPath("b/d"), rootedPath("c/d"))
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLogicalChainDuringResolution_file_simpleSymlink() {
        symlink("a", "b")
        symlink("b", "c")
        file("c")
        val rootedPath: RootedPath = rootedPath("a")
        val fileValue: FileValue = valueForRootedPath(rootedPath)
        assertThat(fileValue).isInstanceOf(SymlinkFileValueWithoutStoredChain::class.java)
        assertThat(fileValue.getUnresolvedLinkTarget()).isEqualTo(PathFragment.create("b"))
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable { fileValue.logicalChainDuringResolution(rootedPath) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLogicalChainDuringResolution_file_simpleAncestorSymlink() {
        symlink("a", "b")
        symlink("b", "c")
        file("c/d")
        val rootedPath: RootedPath = rootedPath("a/d")
        val fileValue: FileValue = valueForRootedPath(rootedPath)
        assertThat(fileValue).isInstanceOf(DifferentRealPathFileValueWithoutStoredChain::class.java)
        assertThat(fileValue.realRootedPath(rootedPath)).isEqualTo(rootedPath("c/d"))
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable { fileValue.logicalChainDuringResolution(rootedPath) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLogicalChainDuringResolution_complicated() {
        symlink("a", "b")
        symlink("b", "c")
        directory("c")
        symlink("c/d", "../e/f")
        symlink("e", "g")
        directory("g")
        symlink("g/f", "../h")
        directory("h")
        val rootedPath: RootedPath = rootedPath("a/d")
        val fileValue: FileValue = valueForRootedPath(rootedPath)
        assertThat(fileValue).isInstanceOf(DifferentRealPathFileValueWithStoredChain::class.java)
        assertThat(fileValue.realRootedPath(rootedPath)).isEqualTo(rootedPath("h"))
        assertThat(fileValue.logicalChainDuringResolution(rootedPath))
            .containsExactly(
                rootedPath("a/d"),
                rootedPath("b/d"),
                rootedPath("c/d"),
                rootedPath("e/f"),
                rootedPath("g/f"),
                rootedPath("h")
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileAccessException() {
        val fs = this.fs as CustomInMemoryFs
        val foo: Path = file("foo")
        val fae: FileAccessException = FileAccessException("nope")
        fs.stubStatError(foo, fae)
        val skyKey: SkyKey = skyKey("foo")
        val evaluator: MemoizingEvaluator = makeEvaluator()
        val result: EvaluationResult<FileValue?> =
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(skyKey), EVALUATION_OPTIONS)
        assertThat(result.hasError()).isTrue()
        val errorInfoSubject: ErrorInfoSubject =
            EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasErrorEntryForKeyThat(skyKey)
        errorInfoSubject.isTransient()
        errorInfoSubject.hasExceptionThat().isSameInstanceAs(fae)
    }

    /**
     * Changes the contents of the FileValue for the given file in some way e.g.
     * 
     * 
     *  * If it's a regular file, the contents will be changed.
     *  * If it's a non-existent file, it will be created.
     * 
     * and then returns the file(s) changed paired with a callback to undo the change. Not
     * meant to be called directly by tests.
     */
    @Throws(java.lang.Exception::class)
    private fun changeFile(fileStringToChange: String): Pair<com.google.common.collect.ImmutableList<String?>?, java.lang.Runnable?> {
        val fileToChange: Path = path(fileStringToChange)
        if (fileToChange.exists()) {
            val oldContents: ByteArray? = FileSystemUtils.readContent(fileToChange)
            fileToChange.getOutputStream( /* append= */true).use { outputStream ->
                outputStream.write(byteArrayOf(42.toByte()), 0, 1)
            }
            return Pair.of(
                com.google.common.collect.ImmutableList.of<E?>(fileStringToChange),
                makeWriteFileContentCallback(fileToChange, oldContents)
            )
        } else {
            val filesTouched: com.google.common.collect.ImmutableList<String?> = filesTouchedIfTouched(fileToChange)
            file(fileStringToChange, "new stuff")
            return Pair.of(
                com.google.common.collect.ImmutableList.< E > copyOf < E ? > (filesTouched),
                makeDeletePathCallback(fileToChange)
            )
        }
    }

    /**
     * Changes the contents of the FileValue for the given directory in some way e.g.
     * 
     * 
     *  * If it exists, the directory will be deleted.
     *  * If it doesn't exist, the directory will be created.
     * 
     * and then returns the file(s) changed paired with a callback to undo the change. Not
     * meant to be called directly by tests.
     */
    @Throws(java.lang.Exception::class)
    private fun changeDirectory(directoryStringToChange: String): Pair<com.google.common.collect.ImmutableList<String?>?, java.lang.Runnable?> {
        val directoryToChange: Path = path(directoryStringToChange)
        if (directoryToChange.exists()) {
            directoryToChange.delete()
            return Pair.of(
                com.google.common.collect.ImmutableList.of<E?>(directoryStringToChange),
                makeCreateDirectoryCallback(directoryToChange)
            )
        } else {
            directoryToChange.createDirectory()
            return Pair.of(
                com.google.common.collect.ImmutableList.of<E?>(directoryStringToChange),
                makeDeletePathCallback(directoryToChange)
            )
        }
    }

    /**
     * Performs filesystem operations to change the file or directory denoted by `changedPathString` and returns the file(s) changed paired with a callback to undo the change.
     * Not meant to be called directly by tests.
     * 
     * @param isSupposedToBeFile whether the path denoted by the given string is supposed to be a file
     * or a directory. This is needed is the path doesn't exist yet, and so the filesystem doesn't
     * know.
     */
    @Throws(java.lang.Exception::class)
    private fun change(
        changedPathString: String, isSupposedToBeFile: Boolean
    ): Pair<com.google.common.collect.ImmutableList<String?>?, java.lang.Runnable?> {
        val changedPath: Path = path(changedPathString)
        if (changedPath.isSymbolicLink()) {
            val filesTouched: com.google.common.collect.ImmutableList<String?> = filesTouchedIfTouched(changedPath)
            val oldTarget: PathFragment = changedPath.readSymbolicLink()
            FileSystemUtils.ensureSymbolicLink(changedPath, oldTarget.getChild("__different_target__"))
            return Pair.of(filesTouched, makeSymlinkCallback(changedPath, oldTarget))
        } else if (isSupposedToBeFile) {
            return changeFile(changedPathString)
        } else {
            return changeDirectory(changedPathString)
        }
    }

    /**
     * Asserts that if the contents of `changedPathString` changes, then the FileValue
     * corresponding to `pathString` will change. Not meant to be called directly by tests.
     */
    @Throws(java.lang.Exception::class)
    private fun assertValueChangesIfContentsOfFileChanges(
        changedPathString: String, changes: Boolean, pathString: String?
    ) {
        getFilesSeenAndAssertValueChangesIfContentsOfFileChanges(
            changedPathString, changes, pathString
        )
    }

    /**
     * Asserts that if the contents of `changedPathString` changes, then the FileValue
     * corresponding to `pathString` will change. Returns the paths of all files seen.
     */
    @Throws(java.lang.Exception::class)
    private fun getFilesSeenAndAssertValueChangesIfContentsOfFileChanges(
        changedPathString: String, changes: Boolean, pathString: String?
    ): MutableSet<RootedPath?> {
        return assertChangesIfChanges(changedPathString, true, changes, pathString)
    }

    /**
     * Asserts that if the directory `changedPathString` changes, then the FileValue
     * corresponding to `pathString` will change.
     */
    @Throws(java.lang.Exception::class)
    private fun assertValueChangesIfContentsOfDirectoryChanges(
        changedPathString: String, changes: Boolean, pathString: String?
    ) {
        assertChangesIfChanges(changedPathString, false, changes, pathString)
    }

    /**
     * Asserts that if the contents of `changedPathString` changes, then the FileValue
     * corresponding to `pathString` will change. Returns the paths of all files seen. Not meant
     * to be called directly by tests.
     */
    @Throws(java.lang.Exception::class)
    private fun assertChangesIfChanges(
        changedPathString: String, isFile: Boolean, changes: Boolean, pathString: String?
    ): MutableSet<RootedPath?> {
        val evaluator: MemoizingEvaluator = makeEvaluator()
        val key: SkyKey = skyKey(pathString)
        var result: EvaluationResult<SkyValue?>
        result = evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(key), EVALUATION_OPTIONS)
        if (result.hasError()) {
            org.junit.Assert.fail(java.lang.String.format("Evaluation error for %s: %s", key, result.getError()))
        }
        val oldValue: SkyValue? = result.get(key)

        val changeResult: Pair<com.google.common.collect.ImmutableList<String?>?, java.lang.Runnable?> =
            change(changedPathString, isFile)
        val changedPathStrings: com.google.common.collect.ImmutableList<String?> = changeResult.first
        val undoCallback: java.lang.Runnable = changeResult.second
        differencer.invalidate(
            changedPathStrings.stream().map<SkyKey?> { pathString: String? -> this.fileStateSkyKey(pathString) }
                .collect(Collectors.toList()))

        result = evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(key), EVALUATION_OPTIONS)
        if (result.hasError()) {
            org.junit.Assert.fail(java.lang.String.format("Evaluation error for %s: %s", key, result.getError()))
        }

        val newValue: SkyValue = result.get(key)
        Truth.assertWithMessage(
            "Changing the contents of %s %s should%s change the value for file %s.",
            if (isFile) "file" else "directory", changedPathString, if (changes) "" else " not", pathString
        )
            .that(changes != newValue.equals(oldValue))
            .isTrue()

        // Restore the original file.
        undoCallback.run()
        return filesSeen(evaluator)
    }

    /**
     * Asserts that trying to construct a FileValue for `path` succeeds. Returns the paths of
     * all files seen.
     */
    @Throws(java.lang.Exception::class)
    private fun assertNoError(pathString: String) {
        val evaluator: MemoizingEvaluator = makeEvaluator()
        val key: SkyKey = skyKey(pathString)
        val result: EvaluationResult<FileValue?>
        result = evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(key), EVALUATION_OPTIONS)
        Truth.assertWithMessage(
            "Did not expect error while evaluating %s, got %s", pathString, result.get(key)
        )
            .that(result.hasError())
            .isFalse()
    }

    /**
     * Asserts that trying to construct a FileValue for `path` fails. Returns the paths of all
     * files seen.
     */
    @Throws(java.lang.Exception::class)
    private fun assertError(pathString: String) {
        val evaluator: MemoizingEvaluator = makeEvaluator()
        val key: SkyKey = skyKey(pathString)
        val result: EvaluationResult<FileValue?>
        result = evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(key), EVALUATION_OPTIONS)
        Truth.assertWithMessage("Expected error while evaluating %s, got %s", pathString, result.get(key))
            .that(result.hasError())
            .isTrue()
        Truth.assertThat(
            !result.getError().getCycleInfo().isEmpty() || result.getError().getException() != null
        )
            .isTrue()
    }

    @Throws(java.lang.Exception::class)
    private fun file(fileName: String?): Path {
        val path: Path = path(fileName)
        path.getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.createEmptyFile(path)
        return path
    }

    @Throws(java.lang.Exception::class)
    private fun file(fileName: String?, contents: String?): Path {
        val path: Path = path(fileName)
        path.getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContentAsLatin1(path, contents)
        return path
    }

    @Throws(java.lang.Exception::class)
    private fun directory(directoryName: String?): Path {
        val path: Path = path(directoryName)
        path.createDirectoryAndParents()
        return path
    }

    @Throws(java.lang.Exception::class)
    private fun symlink(link: String?, target: String?): Path {
        val path: Path = path(link)
        path.getParentDirectory().createDirectoryAndParents()
        path.createSymbolicLink(PathFragment.create(target))
        return path
    }

    private fun path(rootRelativePath: String?): Path {
        return pkgRoot.getRelative(PathFragment.create(rootRelativePath))
    }

    private fun rootedPath(pathString: String?): RootedPath {
        val roots: com.google.common.collect.ImmutableList<Root?> =
            com.google.common.collect.ImmutableList.builder<Root?>()
                .addAll(pkgLocator.getPathEntries())
                .add(outputBaseRoot)
                .build()
        return RootedPath.toRootedPathMaybeUnderRoot(path(pathString), roots)
    }

    private fun skyKey(pathString: String?): SkyKey {
        return FileValue.key(rootedPath(pathString))
    }

    private fun fileStateSkyKey(pathString: String?): SkyKey {
        return FileStateValue.key(rootedPath(pathString))
    }

    private open inner class CustomInMemoryFs(manualClock: com.google.devtools.build.lib.testutil.ManualClock) :
        InMemoryFileSystem(manualClock, DigestHashFunction.SHA256) {
        private val stubbedStats: MutableMap<PathFragment?, FileStatus> =
            com.google.common.collect.Maps.newHashMap<PathFragment?, FileStatus>()
        private val stubbedStatErrors: MutableMap<PathFragment?, IOException?> =
            com.google.common.collect.Maps.newHashMap<PathFragment?, IOException?>()
        private val stubbedFastDigestErrors: MutableMap<PathFragment?, IOException?> =
            com.google.common.collect.Maps.newHashMap<PathFragment?, IOException?>()

        fun stubFastDigestError(path: Path, error: IOException?) {
            stubbedFastDigestErrors.put(path.asFragment(), error)
        }

        @Throws(IOException::class)
        public override fun getFastDigest(path: PathFragment?): ByteArray? {
            if (stubbedFastDigestErrors.containsKey(path)) {
                throw stubbedFastDigestErrors.get(path)
            }
            return if (fastDigest) getDigest(path) else null
        }

        fun stubStat(path: Path, stubbedResult: FileStatus?) {
            stubbedStats.put(path.asFragment(), stubbedResult)
        }

        fun stubStatError(path: Path, error: IOException?) {
            stubbedStatErrors.put(path.asFragment(), error)
        }

        @Throws(IOException::class)
        public override fun statIfFound(path: PathFragment, followSymlinks: Boolean): FileStatus {
            if (stubbedStatErrors.containsKey(path)) {
                throw stubbedStatErrors.get(path)
            }
            if (stubbedStats.containsKey(path)) {
                return stubbedStats.get(path)
            }
            return super.statIfFound(path, followSymlinks)
        }
    }

    companion object {
        private val EVALUATION_OPTIONS: EvaluationContext? = EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setParallelism(DEFAULT_THREAD_COUNT)
            .setEventHandler(NullEventHandler.INSTANCE)
            .build()

        @Throws(java.lang.InterruptedException::class)
        private fun valueForPathHelper(root: Root, path: Path?, evaluator: MemoizingEvaluator): FileValue {
            return valueForRootedPathHelper(
                RootedPath.toRootedPath(root, root.relativize(path)), evaluator
            )
        }

        @Throws(java.lang.InterruptedException::class)
        private fun valueForRootedPathHelper(
            rootedPath: RootedPath?, evaluator: MemoizingEvaluator
        ): FileValue {
            val key: SkyKey = FileValue.key(rootedPath)
            val result: EvaluationResult<FileValue?> =
                evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(key), EVALUATION_OPTIONS)
            assertThat(result.hasError()).isFalse()
            return result.get(key)
        }

        private fun filesSeen(graph: MemoizingEvaluator): MutableSet<RootedPath?> {
            return graph.getValues().keySet().stream()
                .filter(SkyFunctionName.functionIs(FileStateKey.FILE_STATE))
                .map(SkyKey::argument)
                .map({ obj: Any? -> RootedPath::class.java.cast(obj) })
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<E?>())
        }

        /**
         * Returns a callback that, when executed, deletes the given path. Not meant to be called directly
         * by tests.
         */
        private fun makeDeletePathCallback(toDelete: Path): java.lang.Runnable {
            return java.lang.Runnable {
                try {
                    toDelete.delete()
                } catch (e: IOException) {
                    e.printStackTrace()
                    org.junit.Assert.fail(e.message)
                }
            }
        }

        /**
         * Returns a callback that, when executed, writes the given bytes to the given file path. Not
         * meant to be called directly by tests.
         */
        private fun makeWriteFileContentCallback(toChange: Path, contents: ByteArray?): java.lang.Runnable {
            return java.lang.Runnable {
                try {
                    toChange.getOutputStream().use { outputStream ->
                        outputStream.write(contents)
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                    org.junit.Assert.fail(e.message)
                }
            }
        }

        /**
         * Returns a callback that, when executed, creates the given directory path. Not meant to be
         * called directly by tests.
         */
        private fun makeCreateDirectoryCallback(toCreate: Path): java.lang.Runnable {
            return java.lang.Runnable {
                try {
                    toCreate.createDirectory()
                } catch (e: IOException) {
                    e.printStackTrace()
                    org.junit.Assert.fail(e.message)
                }
            }
        }

        /**
         * Returns a callback that, when executed, makes `toLink` a symlink to `toTarget`. Not
         * meant to be called directly by tests.
         */
        private fun makeSymlinkCallback(toLink: Path?, toTarget: PathFragment?): java.lang.Runnable {
            return java.lang.Runnable {
                try {
                    FileSystemUtils.ensureSymbolicLink(toLink, toTarget)
                } catch (e: IOException) {
                    e.printStackTrace()
                    org.junit.Assert.fail(e.message)
                }
            }
        }

        /** Returns the files that would be changed/created if `path` were to be changed/created.  */
        private fun filesTouchedIfTouched(path: Path): com.google.common.collect.ImmutableList<String?> {
            var path: Path = path
            val filesToBeTouched: MutableList<String?> = com.google.common.collect.Lists.newArrayList<String?>()
            do {
                filesToBeTouched.add(path.getPathString())
                path = path.getParentDirectory()
            } while (!path.exists())
            return com.google.common.collect.ImmutableList.copyOf<String?>(filesToBeTouched)
        }
    }
}
