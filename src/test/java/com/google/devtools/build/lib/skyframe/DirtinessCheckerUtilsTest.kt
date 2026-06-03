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
//
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.FileStateValue

/** Tests for [DirtinessCheckerUtils].  */
@RunWith(TestParameterInjector::class)
class DirtinessCheckerUtilsTest {
    private val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
    private val pkgRoot: Path = fs.getPath("/testroot")
    private val srcRoot: Root = Root.fromPath(pkgRoot)
    private val outputBase: Path = fs.getPath("/outputroot/user/outputBase")
    private val pkgLocator: AtomicReference<PathPackageLocator?> = AtomicReference<PathPackageLocator?>(
        PathPackageLocator(
            outputBase,
            com.google.common.collect.ImmutableList.of<E?>(srcRoot),
            BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
        )
    )
    private val directories: BlazeDirectories = BlazeDirectories(
        ServerDirectories(pkgRoot, outputBase, outputBase.getParentDirectory()),
        pkgRoot,
        TestConstants.PRODUCT_NAME
    )
    private val externalFilesHelper: ExternalFilesHelper? = ExternalFilesHelper.createForTesting(
        pkgLocator,
        ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
        directories
    )

    @org.junit.Test
    fun missingDiffChecker_matchesInsideRoot() {
        assertThat(
            createMissingDiffChecker()
                .applies(RootedPath.toRootedPath(srcRoot, PathFragment.create("bar")))
        )
            .isTrue()
    }

    @org.junit.Test
    fun missingDiffChecker_doesntMatchIfRootDoesntMatch() {
        assertThat(
            createMissingDiffChecker()
                .applies(RootedPath.toRootedPath(Root.absoluteRoot(fs), pkgRoot.asFragment()))
        )
            .isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun check_usesSyscallCache_andReturnsNewValue(
        @TestParameter externalChecker: Boolean, @TestParameter internalFile: Boolean
    ) {
        val spyCache: SyscallCache? = spy(SyscallCache.NO_CACHE)
        val rootedPath: RootedPath = if (internalFile) makeInternalRootedPath() else makeExternalRootedPath()
        val path: Path? = rootedPath.asPath()
        val underTest: SkyValueDirtinessChecker =
            if (externalChecker)
                ExternalDirtinessChecker(
                    externalFilesHelper,
                    EnumSet.of(
                        ExternalFilesHelper.FileType.INTERNAL,
                        ExternalFilesHelper.FileType.EXTERNAL_OTHER
                    )
                )
            else
                createMissingDiffChecker()

        val shouldCheck: Boolean = underTest.applies(rootedPath)
        Truth.assertThat(shouldCheck).isEqualTo(externalChecker || internalFile)

        Assume.assumeTrue("Missing diff checker doesn't apply to external files", shouldCheck)

        assertThat(underTest.check(rootedPath, null,  /* oldMtsv= */null, spyCache, null))
            .isEqualTo(
                SkyValueDirtinessChecker.DirtyResult.dirtyWithNewValue(
                    FileStateValue.NONEXISTENT_FILE_STATE_NODE
                )
            )

        Mockito.verify<Any?>(spyCache).getType(path, Symlinks.NOFOLLOW)
        Mockito.verify<Any?>(spyCache).statIfFound(path, Symlinks.NOFOLLOW)
        Mockito.verifyNoMoreInteractions(spyCache)
    }

    private fun makeInternalRootedPath(): RootedPath {
        return RootedPath.toRootedPath(srcRoot, PathFragment.create("srcfile"))
    }

    private fun makeExternalRootedPath(): RootedPath {
        return RootedPath.toRootedPath(Root.absoluteRoot(fs), PathFragment.create("/extfile"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun skipsSyscallCacheForRepoFile_andDoesntReturnNewValue(
        @TestParameter externalChecker: Boolean
    ) {
        val externalFilesHelper: ExternalFilesHelper? = this.externalFilesHelper
        val rootedPath: RootedPath? =
            RootedPath.toRootedPath(
                Root.fromPath(outputBase),
                LabelConstants.EXTERNAL_REPOSITORY_LOCATION.getChild("extrepofile")
            )
        val underTest: SkyValueDirtinessChecker =
            if (externalChecker)
                ExternalDirtinessChecker(
                    externalFilesHelper, EnumSet.< E > of < E ? > (ExternalFilesHelper.FileType.EXTERNAL_REPO)
                )
            else
                MissingDiffDirtinessChecker(com.google.common.collect.ImmutableSet.of<E?>(srcRoot))

        val shouldCheck: Boolean = underTest.applies(rootedPath)
        Truth.assertThat(shouldCheck).isEqualTo(externalChecker)

        Assume.assumeTrue("Missing diff checker doesn't apply to external files", shouldCheck)

        val mockCache: SyscallCache? = Mockito.mock<SyscallCache?>(SyscallCache::class.java)

        assertThat(underTest.check(rootedPath, null,  /* oldMtsv= */null, mockCache, null))
            .isEqualTo(SkyValueDirtinessChecker.DirtyResult.dirty())

        Mockito.verifyNoInteractions(mockCache)
    }

    @org.junit.Test
    fun externalDiffChecker_doesntMatchType() {
        val underTest: DirtinessCheckerUtils.ExternalDirtinessChecker =
            ExternalDirtinessChecker(
                externalFilesHelper, EnumSet.< E > of < E ? > (ExternalFilesHelper.FileType.EXTERNAL_REPO)
            )

        assertThat(
            underTest.applies(
                RootedPath.toRootedPath(Root.absoluteRoot(fs), PathFragment.create("/file"))
            )
        )
            .isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun missingDiffDirtinessCheckers_nullMaxTransitiveSourceVersionForNewValue() {
        val key: SkyKey? = Mockito.mock<SkyKey?>(SkyKey::class.java)
        val value: SkyValue? = Mockito.mock<SkyValue?>(SkyValue::class.java)
        val underTest: DirtinessCheckerUtils.MissingDiffDirtinessChecker = createMissingDiffChecker()

        assertThat(underTest.getMaxTransitiveSourceVersionForNewValue(key, value)).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun externalDirtinessCheckers_nullMaxTransitiveSourceVersionForNewValue() {
        val key: SkyKey? = Mockito.mock<SkyKey?>(SkyKey::class.java)
        val value: SkyValue? = Mockito.mock<SkyValue?>(SkyValue::class.java)
        val underTest: DirtinessCheckerUtils.ExternalDirtinessChecker =
            ExternalDirtinessChecker(
                externalFilesHelper, EnumSet.< E > of < E ? > (ExternalFilesHelper.FileType.EXTERNAL_REPO)
            )

        assertThat(underTest.getMaxTransitiveSourceVersionForNewValue(key, value)).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unionDirtinessChecker_nullMaxTransitiveSourceVersionForNewValue() {
        val rootedPath: RootedPath = makeInternalRootedPath()
        val key: SkyKey? = FileStateValue.key(rootedPath)
        val value: SkyValue? = FileStateValue.create(rootedPath, SyscallCache.NO_CACHE,  /* tsgm= */null)
        val underTest: UnionDirtinessChecker =
            UnionDirtinessChecker(
                com.google.common.collect.ImmutableList.of<E?>(
                    createMissingDiffChecker(),
                    ExternalDirtinessChecker(
                        externalFilesHelper, EnumSet.< E > of < E ? > (ExternalFilesHelper.FileType.EXTERNAL_REPO)
                    )
                )
            )

        assertThat(underTest.getMaxTransitiveSourceVersionForNewValue(key, value)).isNull()
    }

    private fun createMissingDiffChecker(): DirtinessCheckerUtils.MissingDiffDirtinessChecker {
        return MissingDiffDirtinessChecker(com.google.common.collect.ImmutableSet.of<E?>(srcRoot))
    }
}
