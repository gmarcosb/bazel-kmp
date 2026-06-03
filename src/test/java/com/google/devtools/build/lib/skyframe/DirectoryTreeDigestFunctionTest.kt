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

import com.google.devtools.build.lib.analysis.BlazeDirectories

@RunWith(JUnit4::class)
class DirectoryTreeDigestFunctionTest : FoundationTestCase() {
    private var differencer: RecordingDifferencer? = null
    private var skyFunctions: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>? = null
    private var evaluationContext: EvaluationContext? = null

    @Before
    fun setup() {
        differencer = SequencedRecordingDifferencer()
        evaluationContext =
            EvaluationContext.newBuilder().setParallelism(8).setEventHandler(reporter).build()
        val packageLocator: AtomicReference<PathPackageLocator?> =
            AtomicReference<PathPackageLocator?>(
                PathPackageLocator(
                    outputBase,
                    com.google.common.collect.ImmutableList.of<E?>(Root.fromPath(rootDirectory)),
                    BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
                )
            )
        val directories: BlazeDirectories =
            BlazeDirectories(
                ServerDirectories(rootDirectory, outputBase, rootDirectory),
                rootDirectory,
                AnalysisMock.get().getProductName()
            )
        val externalFilesHelper: ExternalFilesHelper? =
            ExternalFilesHelper.createForTesting(
                packageLocator,
                ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
                directories
            )

        skyFunctions =
            com.google.common.collect.ImmutableMap.builder<SkyFunctionName?, SkyFunction?>()
                .put(SkyFunctions.FILE, FileFunction(packageLocator, directories))
                .put(
                    FileStateKey.FILE_STATE,
                    FileStateFunction(
                        com.google.common.base.Suppliers.ofInstance<T?>(TimestampGranularityMonitor(com.google.devtools.build.lib.clock.BlazeClock.instance())),
                        SyscallCache.NO_CACHE,
                        externalFilesHelper
                    )
                )
                .put(SkyFunctions.PRECOMPUTED, PrecomputedFunction())
                .put(SkyFunctions.DIRECTORY_LISTING, DirectoryListingFunction())
                .put(
                    SkyFunctions.DIRECTORY_LISTING_STATE,
                    DirectoryListingStateFunction(externalFilesHelper, SyscallCache.NO_CACHE)
                )
                .put(SkyFunctions.DIRECTORY_TREE_DIGEST, DirectoryTreeDigestFunction())
                .buildOrThrow()

        PrecomputedValue.STARLARK_SEMANTICS.set(differencer, StarlarkSemantics.DEFAULT)
        PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, packageLocator.get())
    }

    @Throws(java.lang.Exception::class)
    private fun getTreeDigest(path: String?): String {
        return getTreeDigest(path, com.google.common.collect.ImmutableList.of<String?>())
    }

    @Throws(java.lang.Exception::class)
    private fun getTreeDigest(path: String?, excludes: com.google.common.collect.ImmutableList<String?>?): String {
        val rootedPath: RootedPath? =
            RootedPath.toRootedPath(Root.absoluteRoot(fileSystem), scratch.resolve(path))
        val key: SkyKey = DirectoryTreeDigestValue.key(rootedPath, rootedPath, excludes)
        val evaluator: MemoizingEvaluator = InMemoryMemoizingEvaluator(skyFunctions, differencer)
        val result: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(key), evaluationContext)
        if (result.hasError()) {
            throw result.getError().getException()
        }
        return (result.get(key) as DirectoryTreeDigestValue).hexDigest
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun basic() {
        scratch.file("a", "a")
        scratch.file("b/b", "b")
        scratch.file("c", "c")
        val oldDigest = getTreeDigest("/")

        scratch.overwriteFile("b/b", "something else")
        Truth.assertThat(getTreeDigest("/")).isNotEqualTo(oldDigest)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun basicExcludes() {
        scratch.file("a", "a")
        scratch.file("b/b", "b")
        scratch.file("c", "c")
        val excludeB = "**/b/b"
        val oldDigest = getTreeDigest("/", com.google.common.collect.ImmutableList.of<String?>(excludeB))

        scratch.overwriteFile("b/b", "something else")
        Truth.assertThat(getTreeDigest("/", com.google.common.collect.ImmutableList.of<String?>(excludeB)))
            .isEqualTo(oldDigest)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addFile() {
        scratch.file("a", "a")
        scratch.file("b/b", "b")
        scratch.file("c", "c")
        val oldDigest = getTreeDigest("/")

        scratch.file("b/d", "something else")
        val updatedDigest = getTreeDigest("/")
        Truth.assertThat(updatedDigest).isNotEqualTo(oldDigest)

        scratch.file("b/ignoredFile", "ignored")
        Truth.assertThat(getTreeDigest("/", com.google.common.collect.ImmutableList.of<String?>("**/ignoredFile")))
            .isEqualTo(updatedDigest)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun removeFile() {
        scratch.file("a", "a")
        scratch.file("b/b", "b")
        scratch.file("c", "c")
        scratch.file("ignoredFile", "ignored")
        val ignorePattern = "**/ignoredFile"
        val oldDigest = getTreeDigest("/", com.google.common.collect.ImmutableList.of<String?>(ignorePattern))

        scratch.deleteFile("ignoredFile")
        Truth.assertThat(getTreeDigest("/", com.google.common.collect.ImmutableList.of<String?>(ignorePattern)))
            .isEqualTo(oldDigest)

        scratch.deleteFile("b/b")
        Truth.assertThat(getTreeDigest("/", com.google.common.collect.ImmutableList.of<String?>(ignorePattern)))
            .isNotEqualTo(oldDigest)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun renameFile() {
        scratch.file("a", "a")
        scratch.file("b/b", "b")
        scratch.file("c", "c")
        scratch.file("ignoredFile", "ignored")
        val ignorePattern = "**/ignored*"
        val oldDigest = getTreeDigest("/", com.google.common.collect.ImmutableList.of<String?>(ignorePattern))

        scratch.deleteFile("ignoredFile")
        scratch.file("ignoredFileRenamed", "ignored")
        Truth.assertThat(getTreeDigest("/", com.google.common.collect.ImmutableList.of<String?>(ignorePattern)))
            .isEqualTo(oldDigest)

        scratch.deleteFile("b/b")
        scratch.file("b/b1", "b")
        Truth.assertThat(getTreeDigest("/", com.google.common.collect.ImmutableList.of<String?>(ignorePattern)))
            .isNotEqualTo(oldDigest)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun swapDirAndFile() {
        scratch.file("a", "a")
        scratch.file("b", "b")
        scratch.file("c/inner", "inner")
        val oldDigest = getTreeDigest("/")

        scratch.resolve("c").deleteTree()
        scratch.deleteFile("b")
        scratch.file("b/inner", "inner")
        scratch.file("c", "b")
        Truth.assertThat(getTreeDigest("/")).isNotEqualTo(oldDigest)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun changeMtime() {
        scratch.file("a", "a")
        scratch.file("b", "b")
        scratch.file("c", "c")

        val oldDigest = getTreeDigest("/")

        // We don't digest mtimes so this shouldn't affect anything.
        scratch.resolve("c").setLastModifiedTime(2024L)
        Truth.assertThat(getTreeDigest("/")).isEqualTo(oldDigest)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun symlink() {
        scratch.file("dir/a", "a")
        scratch.resolve("dir/b").createSymbolicLink(scratch.resolve("otherdir"))
        scratch.file("dir/c", "c")
        scratch.file("otherdir/b", "b")
        scratch.file("otherdir/sub/sub", "sub")
        val oldDigest = getTreeDigest("dir")

        scratch.deleteFile("dir/b")
        scratch.resolve("dir/b").createSymbolicLink(scratch.resolve("yetotherdir"))
        scratch.file("yetotherdir/crazy", "stuff")
        Truth.assertThat(getTreeDigest("dir")).isNotEqualTo(oldDigest)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun danglingSymlink() {
        scratch.file("dir/a", "a")
        scratch.resolve("dir/b").createSymbolicLink(scratch.resolve("otherdir"))
        scratch.file("dir/c", "c")
        val oldDigest = getTreeDigest("dir")

        scratch.file("otherdir/b", "b")
        Truth.assertThat(getTreeDigest("dir")).isNotEqualTo(oldDigest)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun symlinkPointingToSameContents() {
        scratch.file("dir/a", "a")
        scratch.file("dir/b/b", "b")
        scratch.file("dir/b/sub/sub", "sub")
        scratch.file("dir/c", "c")
        val oldDigest = getTreeDigest("dir")

        // replace dir/b with a symlink pointing to otherdir/, which contains the same contents.
        // this shouldn't affect the tree digest.
        scratch.resolve("dir/b").deleteTree()
        scratch.resolve("dir/b").createSymbolicLink(scratch.resolve("otherdir"))
        scratch.file("otherdir/b", "b")
        scratch.file("otherdir/sub/sub", "sub")
        Truth.assertThat(getTreeDigest("dir")).isEqualTo(oldDigest)
    }

    @org.junit.Test
    fun keyBasicExcludes() {
        val pkg: Path? = root.getRelative("pkg")
        val rootedPath: RootedPath? =
            RootedPath.toRootedPath(Root.fromPath(pkg), PathFragment.create("foo/bar"))
        val key: DirectoryTreeDigestValue.Key =
            DirectoryTreeDigestValue.key(
                rootedPath, rootedPath, com.google.common.collect.ImmutableList.of<E?>("ignoredFile", "**/*.tmp")
            )

        Truth.assertThat(Companion.excludes(key, "foo/bar/ignoredFile")).isTrue()
        Truth.assertThat(Companion.excludes(key, "foo/bar/anything.ending.in.tmp")).isTrue()
        Truth.assertThat(Companion.excludes(key, "foo/bar/anything/ending/in/file.tmp")).isTrue()
        Truth.assertThat(Companion.excludes(key, "foo/bar/notIgnored")).isFalse()
    }

    @org.junit.Test
    fun keyDifferentRoots() {
        val pkg1: Path? = root.getRelative("pkg")
        val rootedPath: RootedPath? =
            RootedPath.toRootedPath(Root.fromPath(pkg1), PathFragment.create("foo/bar"))

        val pkg2: Path? = root.getRelative("pkg2")
        val differentRoot: RootedPath? =
            RootedPath.toRootedPath(Root.fromPath(pkg2), PathFragment.create("foo/bar/ignoredFile"))

        val key: DirectoryTreeDigestValue.Key =
            DirectoryTreeDigestValue.key(
                rootedPath,
                rootedPath,
                com.google.common.collect.ImmutableList.of<E?>("ignoredFile")
            )

        Truth.assertThat(Companion.excludes(key, differentRoot)).isFalse()
    }

    @org.junit.Test
    fun keySameRoots() {
        val pkg: Path? = root.getRelative("pkg")
        val rootedPath: RootedPath? =
            RootedPath.toRootedPath(Root.fromPath(pkg), PathFragment.create("foo/bar"))
        val sameRootIgnoredFile: RootedPath? =
            RootedPath.toRootedPath(Root.fromPath(pkg), PathFragment.create("foo/bar/ignoredFile"))

        val key: DirectoryTreeDigestValue.Key =
            DirectoryTreeDigestValue.key(
                rootedPath,
                rootedPath,
                com.google.common.collect.ImmutableList.of<E?>("ignoredFile")
            )
        Truth.assertThat(Companion.excludes(key, sameRootIgnoredFile)).isTrue()
    }

    @org.junit.Test
    fun keyEmptyExcludes() {
        val pkg: Path? = root.getRelative("pkg")
        val rootedPath: RootedPath? =
            RootedPath.toRootedPath(Root.fromPath(pkg), PathFragment.create("foo/bar"))

        val key: DirectoryTreeDigestValue.Key =
            DirectoryTreeDigestValue.key(rootedPath, rootedPath, com.google.common.collect.ImmutableList.of<E?>())
        Truth.assertThat(Companion.excludes(key, "/pkg/foo/bar")).isFalse()
    }

    companion object {
        fun excludes(key: DirectoryTreeDigestValue.Key, path: String?): Boolean {
            return DirectoryTreeDigestFunction.excludes(path, key.globBase(), key.excludes(), null)
        }

        fun excludes(key: DirectoryTreeDigestValue.Key, path: RootedPath?): Boolean {
            return DirectoryTreeDigestFunction.excludes(path, key.globBase(), key.excludes(), null)
        }
    }
}
