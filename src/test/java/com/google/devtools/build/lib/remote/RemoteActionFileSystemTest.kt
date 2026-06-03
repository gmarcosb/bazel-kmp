// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote

import com.google.devtools.build.lib.actions.ActionExecutionMetadata

/** Tests for [RemoteActionFileSystem]  */
@RunWith(TestParameterInjector::class)
class RemoteActionFileSystemTest : RemoteActionFileSystemTestBase() {
    private val inputFetcher: RemoteActionInputFetcher? =
        Mockito.mock<RemoteActionInputFetcher?>(RemoteActionInputFetcher::class.java)
    private val fs: SpiedFileSystem = SpiedFileSystem.createInMemorySpy()
    private val execRoot: Path = fs.getPath("/exec")
    private val sourceRoot: ArtifactRoot? = ArtifactRoot.asSourceRoot(Root.fromPath(execRoot))
    private val outputRoot: ArtifactRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, RELATIVE_OUTPUT_PATH)

    internal enum class FilesystemTestParam {
        LOCAL,
        REMOTE;

        fun getFilesystem(actionFs: RemoteActionFileSystem): FileSystem {
            return when (this) {
                FilesystemTestParam.LOCAL -> actionFs.getLocalFileSystem()
                FilesystemTestParam.REMOTE -> actionFs.getRemoteOutputTree()
            }
        }
    }

    @Before
    @Throws(IOException::class)
    fun setUp() {
        outputRoot.getRoot().asPath().createDirectoryAndParents()
    }

    @Throws(IOException::class)
    override fun createActionFileSystem(
        inputs: ActionInputMap?, outputs: Iterable<Artifact?>?
    ): RemoteActionFileSystem {
        Mockito.doReturn(DUMMY_REMOTE_OUTPUT_CHECKER).`when`<Any?>(inputFetcher).getRemoteOutputChecker()
        val remoteActionFileSystem: RemoteActionFileSystem =
            RemoteActionFileSystem(
                fs, execRoot.asFragment(), RELATIVE_OUTPUT_PATH, inputs, inputFetcher
            )
        remoteActionFileSystem.updateContext(< T > mock < T ? > (ActionExecutionMetadata::class.java))
        remoteActionFileSystem.createDirectoryAndParents(outputRoot.getRoot().asPath().asFragment())
        return remoteActionFileSystem
    }

    override fun getLocalFileSystem(actionFs: FileSystem): FileSystem {
        return (actionFs as RemoteActionFileSystem).getLocalFileSystem()
    }

    override fun getRemoteFileSystem(actionFs: FileSystem): FileSystem {
        return (actionFs as RemoteActionFileSystem).getRemoteOutputTree()
    }

    override fun getOutputPath(outputRootRelativePath: String?): PathFragment {
        return outputRoot.getRoot().asPath().getRelative(outputRootRelativePath).asFragment()
    }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val inputStream_forLocalSourceFile: Unit
        get() {
            // arrange
            val artifact: Artifact = ActionsTestUtil.createArtifact(sourceRoot, "src")
            val actionFs: FileSystem = createActionFileSystem()
            writeLocalFile(actionFs, artifact.getPath().asFragment(), "local contents")

            // act
            val actionFsPath: Path = actionFs.getPath(artifact.getPath().asFragment())
            val contents: String? =
                FileSystemUtils.readContent(actionFsPath, java.nio.charset.StandardCharsets.UTF_8)

            // assert
            assertThat(actionFsPath.getFileSystem()).isSameInstanceAs(actionFs)
            Truth.assertThat(contents).isEqualTo("local contents")
        }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetInputStream_forLocalOutputFile() {
        // arrange
        val artifact: Artifact = ActionsTestUtil.createArtifact(outputRoot, "src")
        val actionFs: FileSystem = createActionFileSystem()
        writeLocalFile(actionFs, artifact.getPath().asFragment(), "local contents")

        // act
        val actionFsPath: Path = actionFs.getPath(artifact.getPath().asFragment())
        val contents: String? = FileSystemUtils.readContent(actionFsPath, java.nio.charset.StandardCharsets.UTF_8)

        // assert
        assertThat(actionFsPath.getFileSystem()).isSameInstanceAs(actionFs)
        Truth.assertThat(contents).isEqualTo("local contents")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetInputStream_forRemoteOutputFile() {
        // arrange
        val inputs: ActionInputMap = ActionInputMap(1)
        val artifact: Artifact = createRemoteArtifact("remote-file", "remote contents", inputs)
        val actionFs: FileSystem = createActionFileSystem(inputs)
        Mockito.doAnswer(mockPrefetchFile(artifact.getPath(), "remote contents"))
            .`when`<Any?>(inputFetcher)
            .prefetchFiles(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.argThat<T?>(ArgumentMatcher { arg: T? ->
                    arg.get().equals(com.google.common.collect.ImmutableList.of<E?>(artifact))
                }),
                ArgumentMatchers.any<T?>(),
                < T > eq < T ? > (Priority.CRITICAL),
        <T > eq<T?>(Reason.INPUTS))

        // act
        val actionFsPath: Path = actionFs.getPath(artifact.getPath().asFragment())
        val contents: String? = FileSystemUtils.readContent(actionFsPath, java.nio.charset.StandardCharsets.UTF_8)

        // assert
        assertThat(actionFsPath.getFileSystem()).isSameInstanceAs(actionFs)
        Truth.assertThat(contents).isEqualTo("remote contents")
        Mockito.verify<Any?>(inputFetcher)
            .prefetchFiles(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.argThat<T?>(ArgumentMatcher { arg: T? ->
                    arg.get().equals(com.google.common.collect.ImmutableList.of<E?>(artifact))
                }),
                ArgumentMatchers.any<T?>(),
                < T > eq < T ? > (Priority.CRITICAL),
        <T > eq<T?>(Reason.INPUTS))
        Mockito.verifyNoMoreInteractions(inputFetcher)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun statAndExists_fromInputArtifactData_file() {
        val inputs: ActionInputMap = ActionInputMap(1)
        val artifact: Artifact = createLocalArtifact("local-file", "local contents", inputs)
        val path: PathFragment? = artifact.getPath().asFragment()
        val metadata: FileArtifactValue =
            com.google.common.base.Preconditions.checkNotNull<T>(inputs.getInputMetadata(artifact))
        val actionFs: RemoteActionFileSystem = createActionFileSystem(inputs) as RemoteActionFileSystem

        assertThat(actionFs.exists(path,  /* followSymlinks= */true)).isTrue()

        val st: FileStatus = actionFs.stat(path,  /* followSymlinks= */true)
        assertThat(st.isFile).isTrue()
        assertThat(st).isInstanceOf(FileStatusWithDigest::class.java)
        assertThat((st as FileStatusWithDigest).digest).isEqualTo(metadata.getDigest())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun statAndExists_fromInputArtifactData_treeSubDir() {
        val inputs: ActionInputMap = ActionInputMap(1)
        val tree: SpecialArtifact =
            createLocalTreeArtifact(
                "tree",
                com.google.common.collect.ImmutableMap.of<String?, String?>("subdir/file", ""),
                inputs
            )
        val path: PathFragment? = tree.getPath().getChild("subdir").asFragment()
        val actionFs: RemoteActionFileSystem = createActionFileSystem(inputs) as RemoteActionFileSystem

        assertThat(actionFs.exists(path,  /* followSymlinks= */true)).isTrue()

        val st: FileStatus = actionFs.stat(path,  /* followSymlinks= */true)
        assertThat(st.isDirectory).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun statAndExists_fromRemoteOutputTree() {
        val actionFs: RemoteActionFileSystem = createActionFileSystem() as RemoteActionFileSystem
        val artifact: Artifact = ActionsTestUtil.createArtifact(outputRoot, "out")
        val path: PathFragment? = artifact.getPath().asFragment()
        val metadata: FileArtifactValue =
            injectRemoteFile(actionFs, artifact.getPath().asFragment(), "remote contents")

        assertThat(actionFs.exists(path,  /* followSymlinks= */true)).isTrue()

        val st: FileStatus = actionFs.stat(path,  /* followSymlinks= */true)
        assertThat(st.isFile).isTrue()
        assertThat(st).isInstanceOf(FileStatusWithDigest::class.java)
        assertThat((st as FileStatusWithDigest).digest).isEqualTo(metadata.getDigest())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun statAndExists_fromLocalFilesystem() {
        val actionFs: RemoteActionFileSystem = createActionFileSystem() as RemoteActionFileSystem
        val artifact: Artifact = ActionsTestUtil.createArtifact(outputRoot, "out")
        val path: PathFragment? = artifact.getPath().asFragment()
        writeLocalFile(actionFs, artifact.getPath().asFragment(), "local contents")

        assertThat(actionFs.exists(path)).isTrue()

        val st: FileStatus = actionFs.stat(path,  /* followSymlinks= */true)
        assertThat(st.isFile).isTrue()
        assertThat(st.size).isEqualTo("local contents".toByteArray(java.nio.charset.StandardCharsets.UTF_8).size)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun statAndExists_followSymlinks(
        @TestParameter from: FilesystemTestParam, @TestParameter to: FilesystemTestParam
    ) {
        val actionFs: RemoteActionFileSystem = createActionFileSystem() as RemoteActionFileSystem
        val fromFs: FileSystem = from.getFilesystem(actionFs)
        val toFs: FileSystem = to.getFilesystem(actionFs)

        val linkPath: PathFragment = getOutputPath("sym")
        val targetPath: PathFragment = getOutputPath("target")
        fromFs.getPath(linkPath).createSymbolicLink(execRoot.getRelative(targetPath).asFragment())

        assertThat(actionFs.exists(linkPath,  /* followSymlinks= */false)).isTrue()
        assertThat(actionFs.exists(linkPath,  /* followSymlinks= */true)).isFalse()
        assertThat(actionFs.stat(linkPath,  /* followSymlinks= */false).isSymbolicLink()).isTrue()
        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable { actionFs.stat(linkPath,  /* followSymlinks= */true) })

        if (toFs.equals(actionFs.getLocalFileSystem())) {
            writeLocalFile(actionFs, targetPath, "content")
        } else {
            injectRemoteFile(actionFs, targetPath, "content")
        }

        assertThat(actionFs.exists(linkPath,  /* followSymlinks= */false)).isTrue()
        assertThat(actionFs.stat(linkPath,  /* followSymlinks= */false).isSymbolicLink()).isTrue()
        assertThat(actionFs.exists(linkPath,  /* followSymlinks= */true)).isTrue()
        assertThat(actionFs.stat(linkPath,  /* followSymlinks= */true).isFile()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun statAndExists_notFound() {
        val actionFs: RemoteActionFileSystem = createActionFileSystem() as RemoteActionFileSystem
        val path: PathFragment = getOutputPath("does_not_exist")

        assertThat(actionFs.exists(path)).isFalse()

        assertThat(actionFs.statIfFound(path,  /* followSymlinks= */true)).isNull()

        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable { actionFs.stat(path,  /* followSymlinks= */true) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun statAndExists_isNotDirectory() {
        val actionFs: RemoteActionFileSystem = createActionFileSystem() as RemoteActionFileSystem
        val nonDirPath: PathFragment = getOutputPath("non_dir")
        val path: PathFragment? = nonDirPath.getChild("file")

        writeLocalFile(actionFs, nonDirPath, "content")

        assertThat(actionFs.exists(path)).isFalse()

        assertThat(actionFs.statIfFound(path,  /* followSymlinks= */true)).isNull()

        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable { actionFs.stat(path,  /* followSymlinks= */true) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun statAndExists_danglingSymlink_notFound() {
        val actionFs: RemoteActionFileSystem = createActionFileSystem() as RemoteActionFileSystem
        val path: PathFragment = getOutputPath("sym")

        actionFs.getPath(path).createSymbolicLink(PathFragment.create("/does_not_exist"))

        assertThat(actionFs.exists(path)).isFalse()

        assertThat(actionFs.statIfFound(path,  /* followSymlinks= */true)).isNull()

        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable { actionFs.stat(path,  /* followSymlinks= */true) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun delete_deleteSymlink() {
        val actionFs: RemoteActionFileSystem = createActionFileSystem() as RemoteActionFileSystem

        val linkPath: PathFragment = getOutputPath("link")
        val targetPath: PathFragment = getOutputPath("target")
        actionFs.getPath(linkPath).createSymbolicLink(execRoot.getRelative(targetPath).asFragment())
        writeLocalFile(actionFs, targetPath, "content")

        assertThat(actionFs.delete(linkPath)).isTrue()
        assertThat(actionFs.exists(linkPath,  /* followSymlinks= */false)).isFalse()
        assertThat(actionFs.exists(targetPath,  /* followSymlinks= */false)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun delete_followSymlinks(
        @TestParameter from: FilesystemTestParam, @TestParameter to: FilesystemTestParam
    ) {
        val actionFs: RemoteActionFileSystem = createActionFileSystem() as RemoteActionFileSystem
        val fromFs: FileSystem = from.getFilesystem(actionFs)
        val toFs: FileSystem = to.getFilesystem(actionFs)

        val dirLinkPath: PathFragment = getOutputPath("dirLink")
        val dirTargetPath: PathFragment = getOutputPath("dirTarget")
        fromFs
            .getPath(dirLinkPath)
            .createSymbolicLink(execRoot.getRelative(dirTargetPath).asFragment())
        actionFs.getPath(dirTargetPath).createDirectoryAndParents()

        val naivePath: PathFragment? = dirLinkPath.getChild("file")
        val canonicalPath: PathFragment? = dirTargetPath.getChild("file")

        if (toFs.equals(actionFs.getLocalFileSystem())) {
            writeLocalFile(actionFs, canonicalPath, "content")
        } else {
            injectRemoteFile(actionFs, canonicalPath, "content")
        }

        assertThat(actionFs.delete(naivePath)).isTrue()
        assertThat(actionFs.exists(naivePath,  /* followSymlinks= */false)).isFalse()
        assertThat(actionFs.exists(canonicalPath,  /* followSymlinks= */false)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun delete_invalidatesResolveSymbolicLinksCache() {
        val actionFs: RemoteActionFileSystem = createActionFileSystem() as RemoteActionFileSystem
        val linkPath: PathFragment = getOutputPath("sym")
        val targetPath: PathFragment = getOutputPath("target")

        actionFs.getPath(linkPath).getParentDirectory().createDirectoryAndParents()
        actionFs.getPath(linkPath).createSymbolicLink(targetPath)
        writeLocalFile(actionFs, targetPath, "content")

        assertThat(actionFs.getPath(linkPath).resolveSymbolicLinks())
            .isEqualTo(actionFs.getPath(targetPath))

        assertThat(actionFs.delete(linkPath)).isTrue()
        writeLocalFile(actionFs, linkPath, "content")

        assertThat(actionFs.getPath(linkPath).resolveSymbolicLinks())
            .isEqualTo(actionFs.getPath(linkPath))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun setLastModifiedTime_forRemoteOutputTree() {
        val actionFs: RemoteActionFileSystem = createActionFileSystem() as RemoteActionFileSystem
        val artifact: Artifact = ActionsTestUtil.createArtifact(outputRoot, "out")
        val path: PathFragment? = artifact.getPath().asFragment()
        injectRemoteFile(actionFs, artifact.getPath().asFragment(), "remote contents")

        actionFs.getPath(path).setLastModifiedTime(1234567890)
        assertThat(actionFs.getPath(path).getLastModifiedTime()).isEqualTo(1234567890)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun setLastModifiedTime_forLocalFilesystem() {
        val actionFs: RemoteActionFileSystem = createActionFileSystem() as RemoteActionFileSystem
        val artifact: Artifact = ActionsTestUtil.createArtifact(outputRoot, "out")
        val path: PathFragment? = artifact.getPath().asFragment()
        writeLocalFile(actionFs, artifact.getPath().asFragment(), "local contents")

        actionFs.getPath(path).setLastModifiedTime(1234567890)
        assertThat(actionFs.getPath(path).getLastModifiedTime()).isEqualTo(1234567890)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun setLastModifiedTime_followSymlinks(
        @TestParameter from: FilesystemTestParam, @TestParameter to: FilesystemTestParam
    ) {
        val actionFs: RemoteActionFileSystem = createActionFileSystem() as RemoteActionFileSystem
        val fromFs: FileSystem = from.getFilesystem(actionFs)
        val toFs: FileSystem = to.getFilesystem(actionFs)

        val linkPath: PathFragment = getOutputPath("sym")
        val targetPath: PathFragment = getOutputPath("target")
        fromFs.getPath(linkPath).createSymbolicLink(execRoot.getRelative(targetPath).asFragment())

        if (toFs.equals(actionFs.getLocalFileSystem())) {
            writeLocalFile(actionFs, targetPath, "content")
        } else {
            injectRemoteFile(actionFs, targetPath, "content")
        }

        actionFs.getPath(linkPath).setLastModifiedTime(1234567890)
        assertThat(actionFs.getPath(linkPath).getLastModifiedTime()).isEqualTo(1234567890)
        assertThat(actionFs.getPath(targetPath).getLastModifiedTime()).isEqualTo(1234567890)
    }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val digest_fromInputArtifactData_forLocalArtifact: Unit
        get() {
            val inputs: ActionInputMap = ActionInputMap(1)
            val artifact: Artifact = createRemoteArtifact("file", "local contents", inputs)
            val path: PathFragment? = artifact.getPath().asFragment()
            val actionFs: RemoteActionFileSystem = createActionFileSystem(inputs) as RemoteActionFileSystem

            // Verify that we don't fall back to a slow digest.
            Mockito.reset<SpiedFileSystem?>(fs)
            assertThat(actionFs.getFastDigest(path)).isEqualTo(getDigest("local contents"))
            Mockito.verify<SpiedFileSystem?>(fs, Mockito.never()).getDigest(ArgumentMatchers.any<T?>())

            assertThat(actionFs.getDigest(path)).isEqualTo(getDigest("local contents"))
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val digest_fromInputArtifactData_forRemoteArtifact: Unit
        get() {
            val inputs: ActionInputMap = ActionInputMap(1)
            val artifact: Artifact = createRemoteArtifact("file", "remote contents", inputs)
            val path: PathFragment? = artifact.getPath().asFragment()
            val actionFs: RemoteActionFileSystem = createActionFileSystem(inputs) as RemoteActionFileSystem

            // Verify that we don't fall back to a slow digest.
            Mockito.reset<SpiedFileSystem?>(fs)
            assertThat(actionFs.getFastDigest(path)).isEqualTo(getDigest("remote contents"))
            Mockito.verify<SpiedFileSystem?>(fs, Mockito.never()).getDigest(ArgumentMatchers.any<T?>())

            assertThat(actionFs.getDigest(path)).isEqualTo(getDigest("remote contents"))
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val digest_fromRemoteOutputTree: Unit
        get() {
            val actionFs: RemoteActionFileSystem = createActionFileSystem() as RemoteActionFileSystem
            val artifact: Artifact = ActionsTestUtil.createArtifact(outputRoot, "out")
            val path: PathFragment? = artifact.getPath().asFragment()
            injectRemoteFile(actionFs, artifact.getPath().asFragment(), "remote contents")

            assertThat(actionFs.getFastDigest(path)).isEqualTo(getDigest("remote contents"))
            assertThat(actionFs.getDigest(path)).isEqualTo(getDigest("remote contents"))
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val digest_fromLocalFilesystem: Unit
        get() {
            val actionFs: RemoteActionFileSystem = createActionFileSystem() as RemoteActionFileSystem
            val artifact: Artifact = ActionsTestUtil.createArtifact(outputRoot, "out")
            val path: PathFragment? = artifact.getPath().asFragment()
            writeLocalFile(actionFs, artifact.getPath().asFragment(), "local contents")

            assertThat(actionFs.getFastDigest(path)).isNull()
            assertThat(actionFs.getDigest(path)).isEqualTo(getDigest("local contents"))
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val digest_notFound: Unit
        get() {
            val actionFs: RemoteActionFileSystem = createActionFileSystem() as RemoteActionFileSystem
            val artifact: Artifact = ActionsTestUtil.createArtifact(outputRoot, "out")
            val path: PathFragment? = artifact.getPath().asFragment()

            org.junit.Assert.assertThrows<FileNotFoundException?>(
                FileNotFoundException::class.java,
                org.junit.function.ThrowingRunnable { actionFs.getFastDigest(path) })
            org.junit.Assert.assertThrows<FileNotFoundException?>(
                FileNotFoundException::class.java,
                org.junit.function.ThrowingRunnable { actionFs.getDigest(path) })
        }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getDigest_followSymlinks(
        @TestParameter from: FilesystemTestParam, @TestParameter to: FilesystemTestParam
    ) {
        val actionFs: RemoteActionFileSystem = createActionFileSystem() as RemoteActionFileSystem
        val fromFs: FileSystem = from.getFilesystem(actionFs)
        val toFs: FileSystem = to.getFilesystem(actionFs)

        val linkPath: PathFragment = getOutputPath("sym")
        val targetPath: PathFragment = getOutputPath("target")
        fromFs.getPath(linkPath).createSymbolicLink(execRoot.getRelative(targetPath).asFragment())

        if (toFs.equals(actionFs.getLocalFileSystem())) {
            writeLocalFile(actionFs, targetPath, "content")
            assertThat(actionFs.getFastDigest(linkPath)).isNull()
        } else {
            injectRemoteFile(actionFs, targetPath, "content")
            assertThat(actionFs.getFastDigest(linkPath)).isEqualTo(getDigest("content"))
        }

        assertThat(actionFs.getDigest(linkPath)).isEqualTo(getDigest("content"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun readdir_fromRemoteOutputTree() {
        val actionFs: RemoteActionFileSystem = createActionFileSystem() as RemoteActionFileSystem
        injectRemoteFile(actionFs, getOutputPath("dir/out1"), "contents1")
        injectRemoteFile(actionFs, getOutputPath("dir/out2"), "contents2")
        injectRemoteFile(actionFs, getOutputPath("dir/subdir/out3"), "contents3")
        val dirPath: PathFragment = getOutputPath("dir")

        assertReaddir(
            actionFs,
            dirPath,  /* followSymlinks= */
            true,
            Dirent("out1", Dirent.Type.FILE),
            Dirent("out2", Dirent.Type.FILE),
            Dirent("subdir", Dirent.Type.DIRECTORY)
        )

        assertReaddirThrows(actionFs, getOutputPath("dir/out1"),  /* followSymlinks= */true)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun readdir_fromLocalFilesystem() {
        val actionFs: RemoteActionFileSystem = createActionFileSystem() as RemoteActionFileSystem
        writeLocalFile(actionFs, getOutputPath("dir/file"), "contents")
        writeLocalFile(actionFs, getOutputPath("dir/subdir/file"), "contents")
        val dirPath: PathFragment = getOutputPath("dir")

        assertReaddir(
            actionFs,
            dirPath,  /* followSymlinks= */
            true,
            Dirent("file", Dirent.Type.FILE),
            Dirent("subdir", Dirent.Type.DIRECTORY)
        )

        assertReaddirThrows(actionFs, getOutputPath("dir/out1"),  /* followSymlinks= */true)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun readdir_fromInputArtifactData() {
        val inputs: ActionInputMap = ActionInputMap(1)
        createLocalTreeArtifact(
            "tree",
            com.google.common.collect.ImmutableMap.of<String?, String?>("file", "", "dir/subdir/subfile", ""),
            inputs
        )
        val actionFs: RemoteActionFileSystem = createActionFileSystem(inputs) as RemoteActionFileSystem

        assertReaddir(
            actionFs,
            getOutputPath("tree"),  /* followSymlinks= */
            true,
            Dirent("dir", Dirent.Type.DIRECTORY),
            Dirent("file", Dirent.Type.FILE)
        )

        assertReaddir(
            actionFs,
            getOutputPath("tree/dir"),  /* followSymlinks= */
            true,
            Dirent("subdir", Dirent.Type.DIRECTORY)
        )

        assertReaddir(
            actionFs,
            getOutputPath("tree/dir/subdir"),  /* followSymlinks= */
            true,
            Dirent("subfile", Dirent.Type.FILE)
        )

        assertReaddirThrows(
            actionFs, getOutputPath("tree/dir/subdir/subfile"),  /* followSymlinks= */true
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun readdir_fromInputArtifactData_emptyDir() {
        val inputs: ActionInputMap = ActionInputMap(1)
        createLocalTreeArtifact("tree", com.google.common.collect.ImmutableMap.of<String?, String?>(), inputs)
        val actionFs: RemoteActionFileSystem = createActionFileSystem(inputs) as RemoteActionFileSystem

        assertReaddir(actionFs, getOutputPath("tree"),  /* followSymlinks= */true)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun readdir_fromRemoteOutputTreeAndLocalFilesystem() {
        val inputs: ActionInputMap = ActionInputMap(1)
        val actionFs: RemoteActionFileSystem = createActionFileSystem(inputs) as RemoteActionFileSystem
        writeLocalFile(actionFs, getOutputPath("dir/out1"), "contents1")
        injectRemoteFile(actionFs, getOutputPath("dir/out2"), "contents2")
        val dirPath: PathFragment = getOutputPath("dir")

        assertReaddir(
            actionFs,
            dirPath,  /* followSymlinks= */
            true,
            Dirent("out1", Dirent.Type.FILE),
            Dirent("out2", Dirent.Type.FILE)
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun readdir_fromRemoteOutputTreeAndLocalFilesystem_emptyDir() {
        val inputs: ActionInputMap = ActionInputMap(1)
        val actionFs: RemoteActionFileSystem = createActionFileSystem(inputs) as RemoteActionFileSystem
        val dirPath: PathFragment = getOutputPath("dir")
        actionFs.getRemoteOutputTree().getPath(dirPath).createDirectoryAndParents()
        actionFs.getLocalFileSystem().getPath(dirPath).createDirectoryAndParents()

        assertReaddir(actionFs, dirPath,  /* followSymlinks= */true)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun readdir_followSymlinks_forDirectory(
        @TestParameter from: FilesystemTestParam, @TestParameter to: FilesystemTestParam
    ) {
        val actionFs: RemoteActionFileSystem = createActionFileSystem() as RemoteActionFileSystem
        val fromFs: FileSystem = from.getFilesystem(actionFs)
        val toFs: FileSystem = to.getFilesystem(actionFs)

        val linkPath: PathFragment = getOutputPath("sym")
        val targetPath: PathFragment = getOutputPath("dir")
        val childPath: PathFragment = getOutputPath("dir/child")

        fromFs.getPath(linkPath).createSymbolicLink(execRoot.getRelative(targetPath).asFragment())
        toFs.getPath(targetPath).createDirectory()

        if (toFs.equals(actionFs.getLocalFileSystem())) {
            writeLocalFile(actionFs, childPath, "content")
        } else {
            injectRemoteFile(actionFs, childPath, "content")
        }

        assertReaddir(
            actionFs, linkPath,  /* followSymlinks= */false, Dirent("child", Dirent.Type.FILE)
        )
        assertReaddir(
            actionFs, linkPath,  /* followSymlinks= */true, Dirent("child", Dirent.Type.FILE)
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun readdir_followSymlinks_forDirectoryEntries(
        @TestParameter from: FilesystemTestParam, @TestParameter to: FilesystemTestParam
    ) {
        val actionFs: RemoteActionFileSystem = createActionFileSystem() as RemoteActionFileSystem
        val fromFs: FileSystem = from.getFilesystem(actionFs)
        val toFs: FileSystem = to.getFilesystem(actionFs)

        val dirPath: PathFragment = getOutputPath("dir")
        val fileLinkPath: PathFragment = getOutputPath("dir/file_sym")
        val fileTargetPath: PathFragment = getOutputPath("file_target")
        val dirLinkPath: PathFragment = getOutputPath("dir/dir_sym")
        val dirTargetPath: PathFragment = getOutputPath("dir_target")
        val loopingLinkPath: PathFragment = getOutputPath("dir/looping_sym")
        val danglingLinkPath: PathFragment = getOutputPath("dir/dangling_sym")

        fromFs.getPath(dirPath).createDirectory()
        fromFs
            .getPath(fileLinkPath)
            .createSymbolicLink(execRoot.getRelative(fileTargetPath).asFragment())
        fromFs
            .getPath(dirLinkPath)
            .createSymbolicLink(execRoot.getRelative(dirTargetPath).asFragment())
        fromFs
            .getPath(loopingLinkPath)
            .createSymbolicLink(execRoot.getRelative(loopingLinkPath).asFragment())
        fromFs.getPath(danglingLinkPath).createSymbolicLink(PathFragment.create("/does_not_exist"))

        if (toFs.equals(actionFs.getLocalFileSystem())) {
            writeLocalFile(actionFs, fileTargetPath, "content")
            actionFs.getLocalFileSystem().getPath(dirTargetPath).createDirectoryAndParents()
        } else {
            injectRemoteFile(actionFs, fileTargetPath, "content")
            actionFs.getRemoteOutputTree().createDirectoryAndParents(dirTargetPath)
        }

        assertReaddir(
            actionFs,
            dirPath,  /* followSymlinks= */
            false,
            Dirent("file_sym", Dirent.Type.SYMLINK),
            Dirent("dir_sym", Dirent.Type.SYMLINK),
            Dirent("looping_sym", Dirent.Type.SYMLINK),
            Dirent("dangling_sym", Dirent.Type.SYMLINK)
        )
        assertReaddir(
            actionFs,
            dirPath,  /* followSymlinks= */
            true,
            Dirent("file_sym", Dirent.Type.FILE),
            Dirent("dir_sym", Dirent.Type.DIRECTORY),
            Dirent("looping_sym", Dirent.Type.UNKNOWN),
            Dirent("dangling_sym", Dirent.Type.UNKNOWN)
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun readdir_nonDirectory() {
        val actionFs: RemoteActionFileSystem = createActionFileSystem() as RemoteActionFileSystem
        val artifact: Artifact = ActionsTestUtil.createArtifact(outputRoot, "dir/out")
        val path: PathFragment? = artifact.getPath().getParentDirectory().asFragment()

        writeLocalFile(actionFs, path, "content")

        assertReaddirThrows(actionFs, path,  /* followSymlinks= */true)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun readdir_notFound() {
        val actionFs: RemoteActionFileSystem = createActionFileSystem() as RemoteActionFileSystem
        val artifact: Artifact = ActionsTestUtil.createArtifact(outputRoot, "dir/out")
        val path: PathFragment? = artifact.getPath().getParentDirectory().asFragment()

        assertReaddirThrows(actionFs, path,  /* followSymlinks= */true)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun permissions_followSymlinks(
        @TestParameter from: FilesystemTestParam, @TestParameter to: FilesystemTestParam
    ) {
        val actionFs: RemoteActionFileSystem = createActionFileSystem() as RemoteActionFileSystem
        val fromFs: FileSystem = from.getFilesystem(actionFs)
        val toFs: FileSystem = to.getFilesystem(actionFs)

        val linkPath: PathFragment = getOutputPath("sym")
        val targetPath: PathFragment = getOutputPath("target")
        fromFs.getPath(linkPath).createSymbolicLink(execRoot.getRelative(targetPath).asFragment())

        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable { actionFs.chmod(linkPath, 511) })

        if (toFs.equals(actionFs.getLocalFileSystem())) {
            writeLocalFile(actionFs, targetPath, "content")
        } else {
            injectRemoteFile(actionFs, targetPath, "content")
        }

        // For a remote file, permissions are always 0777.
        val isRemote: Boolean = toFs.equals(actionFs.getRemoteOutputTree())

        assertThat(actionFs.getPath(linkPath).isReadable()).isTrue()
        assertThat(actionFs.getPath(linkPath).isWritable()).isTrue()
        assertThat(actionFs.getPath(linkPath).isExecutable()).isEqualTo(isRemote)

        actionFs.getPath(linkPath).chmod(73)
        assertThat(actionFs.getPath(linkPath).isReadable()).isEqualTo(isRemote)
        assertThat(actionFs.getPath(linkPath).isWritable()).isEqualTo(isRemote)
        assertThat(actionFs.getPath(linkPath).isExecutable()).isTrue()

        actionFs.getPath(linkPath).setReadable(true)
        actionFs.getPath(linkPath).setWritable(true)
        actionFs.getPath(linkPath).setExecutable(false)
        assertThat(actionFs.getPath(linkPath).isReadable()).isTrue()
        assertThat(actionFs.getPath(linkPath).isWritable()).isTrue()
        assertThat(actionFs.getPath(linkPath).isExecutable()).isEqualTo(isRemote)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun readSymbolicLink_fromLocalFilesystem() {
        val actionFs: RemoteActionFileSystem = createActionFileSystem() as RemoteActionFileSystem
        val filePath: PathFragment = getOutputPath("file")
        val linkPath: PathFragment = getOutputPath("sym")
        val targetPath: PathFragment? = PathFragment.create("/some/path")
        actionFs.getLocalFileSystem().getPath(linkPath).createSymbolicLink(targetPath)
        writeLocalFile(actionFs, filePath, "contents")

        assertThat(actionFs.readSymbolicLink(linkPath)).isEqualTo(targetPath)

        org.junit.Assert.assertThrows<T?>(
            NotASymlinkException::class.java,
            org.junit.function.ThrowingRunnable { actionFs.readSymbolicLink(filePath) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun readSymbolicLink_fromRemoteFilesystem() {
        val actionFs: RemoteActionFileSystem = createActionFileSystem() as RemoteActionFileSystem
        val filePath: PathFragment = getOutputPath("file")
        val linkPath: PathFragment = getOutputPath("sym")
        val targetPath: PathFragment? = PathFragment.create("/some/path")
        actionFs.getRemoteOutputTree().getPath(linkPath).createSymbolicLink(targetPath)
        injectRemoteFile(actionFs, filePath, "contents")

        assertThat(actionFs.readSymbolicLink(linkPath)).isEqualTo(targetPath)

        org.junit.Assert.assertThrows<T?>(
            NotASymlinkException::class.java,
            org.junit.function.ThrowingRunnable { actionFs.readSymbolicLink(filePath) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun readSymbolicLink_fromInputArtifactData_regularFile() {
        val inputs: ActionInputMap = ActionInputMap(1)
        val artifact: Artifact = createRemoteArtifact("file", "contents", inputs)
        val actionFs: RemoteActionFileSystem = createActionFileSystem(inputs) as RemoteActionFileSystem

        org.junit.Assert.assertThrows<T?>(
            NotASymlinkException::class.java,
            org.junit.function.ThrowingRunnable { actionFs.readSymbolicLink(artifact.getPath().asFragment()) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun readSymbolicLink_fromInputArtifactData_treeSubDir() {
        val inputs: ActionInputMap = ActionInputMap(1)
        val tree: SpecialArtifact =
            createRemoteTreeArtifact(
                "tree",
                com.google.common.collect.ImmutableMap.of<String?, String?>("subdir/file", ""),
                inputs
            )
        val actionFs: RemoteActionFileSystem = createActionFileSystem(inputs) as RemoteActionFileSystem

        org.junit.Assert.assertThrows<T?>(
            NotASymlinkException::class.java,
            org.junit.function.ThrowingRunnable { actionFs.readSymbolicLink(tree.getPath().asFragment()) })

        org.junit.Assert.assertThrows<T?>(
            NotASymlinkException::class.java,
            org.junit.function.ThrowingRunnable {
                actionFs.readSymbolicLink(
                    tree.getPath().getRelative("subdir").asFragment()
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun readSymbolicLink_fromInputArtifactData_unresolvedSymlink() {
        val inputs: ActionInputMap = ActionInputMap(1)
        val actionFs: RemoteActionFileSystem = createActionFileSystem(inputs) as RemoteActionFileSystem

        val symlink: Artifact = ActionsTestUtil.createUnresolvedSymlinkArtifact(outputRoot, "symlink")
        val targetPath: PathFragment? = PathFragment.create("/some/path")
        // Create symlink on the filesystem so we can digest it, then delete it to verify that its
        // presence in the ActionInputMap is sufficient for readSymbolicLink to work. Note that this is
        // an unrealistic scenario, as symlinks are always materialized even when produced remotely.
        val symlinkPath: Path = getLocalFileSystem(actionFs).getPath(symlink.getPath().getPathString())
        symlinkPath.createSymbolicLink(targetPath)
        inputs.put(symlink, FileArtifactValue.createForUnresolvedSymlink(symlinkPath))
        symlinkPath.delete()

        assertThat(actionFs.readSymbolicLink(getOutputPath("symlink"))).isEqualTo(targetPath)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun readSymbolicLink_notFound() {
        val actionFs: RemoteActionFileSystem = createActionFileSystem() as RemoteActionFileSystem
        val linkPath: PathFragment = getOutputPath("sym")

        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable { actionFs.readSymbolicLink(linkPath) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createSymbolicLink_localFileArtifact() {
        // arrange
        val inputs: ActionInputMap = ActionInputMap(1)
        val localArtifact: Artifact = createLocalArtifact("local-file", "local contents", inputs)
        val outputArtifact: Artifact = ActionsTestUtil.createArtifact(outputRoot, "out")
        val actionFs: FileSystem = createActionFileSystem(inputs)

        // act
        val linkPath: PathFragment? = outputArtifact.getPath().asFragment()
        val targetPath: PathFragment? = localArtifact.getPath().asFragment()
        val symlinkActionFs: Path = actionFs.getPath(linkPath)
        symlinkActionFs.createSymbolicLink(actionFs.getPath(targetPath))

        // assert
        assertThat(symlinkActionFs.getFileSystem()).isSameInstanceAs(actionFs)
        assertThat(symlinkActionFs.readSymbolicLink()).isEqualTo(targetPath)
        assertThat(getLocalFileSystem(actionFs).getPath(linkPath).readSymbolicLink())
            .isEqualTo(targetPath)
        assertThat(getLocalFileSystem(actionFs).getPath(linkPath).readSymbolicLink())
            .isEqualTo(targetPath)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createSymbolicLink_remoteFileArtifact() {
        // arrange
        val inputs: ActionInputMap = ActionInputMap(1)
        val remoteArtifact: Artifact = createRemoteArtifact("remote-file", "remote contents", inputs)
        val outputArtifact: Artifact = ActionsTestUtil.createArtifact(outputRoot, "out")
        val actionFs: FileSystem = createActionFileSystem(inputs)

        // act
        val linkPath: PathFragment? = outputArtifact.getPath().asFragment()
        val targetPath: PathFragment? = remoteArtifact.getPath().asFragment()
        val symlinkActionFs: Path = actionFs.getPath(linkPath)
        symlinkActionFs.createSymbolicLink(actionFs.getPath(targetPath))

        // assert
        assertThat(symlinkActionFs.getFileSystem()).isSameInstanceAs(actionFs)
        assertThat(symlinkActionFs.readSymbolicLink()).isEqualTo(targetPath)
        assertThat(outputArtifact.getPath().readSymbolicLink()).isEqualTo(targetPath)
        assertThat(getLocalFileSystem(actionFs).getPath(linkPath).readSymbolicLink())
            .isEqualTo(targetPath)
        assertThat(getLocalFileSystem(actionFs).getPath(linkPath).readSymbolicLink())
            .isEqualTo(targetPath)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createSymbolicLink_localTreeArtifact() {
        // arrange
        val inputs: ActionInputMap = ActionInputMap(1)
        val contentMap: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>("foo", "foo contents", "bar", "bar contents")
        val localArtifact: Artifact = createLocalTreeArtifact("remote-dir", contentMap, inputs)
        val outputArtifact: SpecialArtifact =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "out")
        val actionFs: FileSystem = createActionFileSystem(inputs)

        // act
        val linkPath: PathFragment? = outputArtifact.getPath().asFragment()
        val targetPath: PathFragment? = localArtifact.getPath().asFragment()
        val symlinkActionFs: Path = actionFs.getPath(linkPath)
        symlinkActionFs.createSymbolicLink(actionFs.getPath(targetPath))

        // assert
        assertThat(symlinkActionFs.getFileSystem()).isSameInstanceAs(actionFs)
        assertThat(symlinkActionFs.readSymbolicLink()).isEqualTo(targetPath)
        assertThat(getLocalFileSystem(actionFs).getPath(linkPath).readSymbolicLink())
            .isEqualTo(targetPath)
        assertThat(getLocalFileSystem(actionFs).getPath(linkPath).readSymbolicLink())
            .isEqualTo(targetPath)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createSymbolicLink_remoteTreeArtifact() {
        // arrange
        val inputs: ActionInputMap = ActionInputMap(1)
        val contentMap: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>("foo", "foo contents", "bar", "bar contents")
        val remoteArtifact: Artifact = createRemoteTreeArtifact("remote-dir", contentMap, inputs)
        val outputArtifact: SpecialArtifact =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "out")
        val actionFs: FileSystem = createActionFileSystem(inputs)

        // act
        val linkPath: PathFragment? = outputArtifact.getPath().asFragment()
        val targetPath: PathFragment? = remoteArtifact.getPath().asFragment()
        val symlinkActionFs: Path = actionFs.getPath(linkPath)
        symlinkActionFs.createSymbolicLink(actionFs.getPath(targetPath))

        // assert
        assertThat(symlinkActionFs.getFileSystem()).isSameInstanceAs(actionFs)
        assertThat(symlinkActionFs.readSymbolicLink()).isEqualTo(targetPath)
        assertThat(getLocalFileSystem(actionFs).getPath(linkPath).readSymbolicLink())
            .isEqualTo(targetPath)
        assertThat(getLocalFileSystem(actionFs).getPath(linkPath).readSymbolicLink())
            .isEqualTo(targetPath)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createSymbolicLink_unresolvedSymlink() {
        // arrange
        val inputs: ActionInputMap = ActionInputMap(1)
        val outputArtifact: SpecialArtifact =
            ActionsTestUtil.createUnresolvedSymlinkArtifact(outputRoot, "out")
        val actionFs: FileSystem = createActionFileSystem(inputs)
        val targetPath: PathFragment? = PathFragment.create("some/path")

        // act
        val linkPath: PathFragment? = outputArtifact.getPath().asFragment()
        val symlinkActionFs: Path = actionFs.getPath(linkPath)
        symlinkActionFs.createSymbolicLink(targetPath)

        // assert
        assertThat(symlinkActionFs.getFileSystem()).isSameInstanceAs(actionFs)
        assertThat(symlinkActionFs.readSymbolicLink()).isEqualTo(targetPath)
        assertThat(getLocalFileSystem(actionFs).getPath(linkPath).readSymbolicLink())
            .isEqualTo(targetPath)
        assertThat(getLocalFileSystem(actionFs).getPath(linkPath).readSymbolicLink())
            .isEqualTo(targetPath)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createAndReadSymbolicLink_followSymlinks(@TestParameter from: FilesystemTestParam) {
        // createSymbolicLink writes to both the local and remote filesystem, so it makes no sense to
        // parameterize on the symlink's destination filesystem.
        val actionFs: RemoteActionFileSystem = createActionFileSystem() as RemoteActionFileSystem
        val fromFs: FileSystem = from.getFilesystem(actionFs)

        val parentLinkPath: PathFragment = getOutputPath("parent_link")
        val parentTargetPath: PathFragment = getOutputPath("parent_target")
        fromFs
            .getPath(parentLinkPath)
            .createSymbolicLink(execRoot.getRelative(parentTargetPath).asFragment())
        actionFs.getPath(parentTargetPath).createDirectoryAndParents()

        val linkPath: PathFragment = getOutputPath("parent_target/link")
        val targetPath: PathFragment? = PathFragment.create("/some/path")
        actionFs.getPath(linkPath).createSymbolicLink(targetPath)

        assertThat(actionFs.getPath(linkPath).readSymbolicLink()).isEqualTo(targetPath)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolveSymbolicLinks(
        @TestParameter a: FilesystemTestParam, @TestParameter b: FilesystemTestParam
    ) {
        val actionFs: RemoteActionFileSystem = createActionFileSystem() as RemoteActionFileSystem
        val aFs: FileSystem = a.getFilesystem(actionFs)
        val bFs: FileSystem = b.getFilesystem(actionFs)

        // /a
        //  |- asub
        //  |  `- afile
        //  `- abssym -> /b/bsub
        //  `- relsym -> asub
        // /b
        //  `- bsub
        //     `- bfile
        aFs.getPath(getOutputPath("a/asub")).createDirectoryAndParents()
        aFs.getPath(getOutputPath("a/abssym")).createSymbolicLink(getOutputPath("b/bsub"))
        aFs.getPath(getOutputPath("a/relsym")).createSymbolicLink(PathFragment.create("asub"))
        if (aFs.equals(actionFs.getLocalFileSystem())) {
            writeLocalFile(actionFs, getOutputPath("a/asub/afile"), "content")
        } else {
            injectRemoteFile(actionFs, getOutputPath("a/asub/afile"), "content")
        }

        bFs.getPath(getOutputPath("b/bsub")).createDirectoryAndParents()
        if (bFs.equals(actionFs.getLocalFileSystem())) {
            writeLocalFile(actionFs, getOutputPath("b/bsub/bfile"), "content")
        } else {
            injectRemoteFile(actionFs, getOutputPath("b/bsub/bfile"), "content")
        }

        assertThat(actionFs.getPath(getOutputPath("a/relsym/afile")).resolveSymbolicLinks())
            .isEqualTo(actionFs.getPath(getOutputPath("a/asub/afile")))

        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable {
                actionFs.getPath(getOutputPath("a/bsub/nofile")).resolveSymbolicLinks()
            })

        assertThat(actionFs.getPath(getOutputPath("a/abssym/bfile")).resolveSymbolicLinks())
            .isEqualTo(actionFs.getPath(getOutputPath("b/bsub/bfile")))

        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable {
                actionFs.getPath(getOutputPath("b/bsub/nofile")).resolveSymbolicLinks()
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun renameTo_onlyLocalFile_renameLocalFile() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")
        writeLocalFile(actionFs, path, "local-content")
        val newPath: PathFragment = getOutputPath("file-new")

        actionFs.renameTo(path, newPath)

        assertThat(actionFs.exists(path)).isFalse()
        assertThat(actionFs.exists(newPath)).isTrue()
        assertThat(getLocalFileSystem(actionFs).exists(path)).isFalse()
        assertThat(getLocalFileSystem(actionFs).exists(newPath)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun renameTo_moveSymlink() {
        val actionFs: FileSystem = createActionFileSystem()
        val oldLinkPath: PathFragment = getOutputPath("oldLink")
        val newLinkPath: PathFragment = getOutputPath("newLink")
        val targetPath: PathFragment = getOutputPath("target")
        actionFs.getPath(oldLinkPath).createSymbolicLink(execRoot.getRelative(targetPath).asFragment())
        writeLocalFile(actionFs, targetPath, "content")

        actionFs.renameTo(oldLinkPath, newLinkPath)

        assertThat(actionFs.getPath(oldLinkPath).exists(Symlinks.NOFOLLOW)).isFalse()
        assertThat(actionFs.getPath(newLinkPath).exists(Symlinks.NOFOLLOW)).isTrue()
        assertThat(actionFs.getPath(newLinkPath).readSymbolicLink()).isEqualTo(targetPath)
        assertThat(actionFs.getPath(targetPath).exists()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun renameTo_followSymlinks(
        @TestParameter from: FilesystemTestParam, @TestParameter to: FilesystemTestParam
    ) {
        val actionFs: RemoteActionFileSystem = createActionFileSystem() as RemoteActionFileSystem
        val fromFs: FileSystem = from.getFilesystem(actionFs)
        val toFs: FileSystem = to.getFilesystem(actionFs)

        val srcDirLinkPath: PathFragment = getOutputPath("srcDirLink")
        val srcDirTargetPath: PathFragment = getOutputPath("srcDirTarget")
        val naiveSrcPath: PathFragment? = srcDirLinkPath.getChild("oldFile")
        val canonicalSrcPath: PathFragment? = srcDirTargetPath.getChild("oldFile")

        val dstDirLinkPath: PathFragment = getOutputPath("dstDirLink")
        val dstDirTargetPath: PathFragment = getOutputPath("dstDirTarget")
        val naiveDstPath: PathFragment? = dstDirLinkPath.getChild("newFile")
        val canonicalDstPath: PathFragment? = dstDirTargetPath.getChild("newFile")

        actionFs.getPath(srcDirTargetPath).createDirectoryAndParents()
        actionFs.getPath(dstDirTargetPath).createDirectoryAndParents()

        fromFs
            .getPath(srcDirLinkPath)
            .createSymbolicLink(execRoot.getRelative(srcDirTargetPath).asFragment())
        fromFs
            .getPath(dstDirLinkPath)
            .createSymbolicLink(execRoot.getRelative(dstDirTargetPath).asFragment())

        if (toFs.equals(actionFs.getLocalFileSystem())) {
            writeLocalFile(actionFs, canonicalSrcPath, "content")
        } else {
            injectRemoteFile(actionFs, canonicalSrcPath, "content")
        }

        actionFs.renameTo(naiveSrcPath, naiveDstPath)

        assertThat(actionFs.exists(naiveSrcPath)).isFalse()
        assertThat(actionFs.exists(canonicalSrcPath)).isFalse()
        assertThat(actionFs.exists(naiveDstPath)).isTrue()
        assertThat(actionFs.exists(canonicalDstPath)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun renameTo_invalidatesResolveSymbolicLinksCache() {
        val actionFs: RemoteActionFileSystem = createActionFileSystem() as RemoteActionFileSystem
        val linkPath: PathFragment = getOutputPath("sym")
        val targetPath: PathFragment = getOutputPath("target")
        val renamedPath: PathFragment = getOutputPath("renamed")

        actionFs.getPath(linkPath).getParentDirectory().createDirectoryAndParents()
        actionFs.getPath(linkPath).createSymbolicLink(targetPath)
        writeLocalFile(actionFs, targetPath, "content")

        assertThat(actionFs.getPath(linkPath).resolveSymbolicLinks())
            .isEqualTo(actionFs.getPath(targetPath))

        actionFs.renameTo(linkPath, renamedPath)
        writeLocalFile(actionFs, linkPath, "content")

        assertThat(actionFs.getPath(linkPath).resolveSymbolicLinks())
            .isEqualTo(actionFs.getPath(linkPath))
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    override fun injectRemoteFile(
        actionFs: FileSystem, path: PathFragment?, content: String
    ): FileArtifactValue {
        val digest = getDigest(content)
        val size: Int = com.google.common.base.Utf8.encodedLength(content)
        (actionFs as RemoteActionFileSystem)
            .injectRemoteFile(path, digest, size,  /* expirationTime= */null)
        return FileArtifactValue.createForRemoteFileWithMaterializationData(
            digest, size,  /* locationIndex= */1,  /* expirationTime= */null
        )
    }

    @Throws(IOException::class)
    override fun writeLocalFile(actionFs: FileSystem, path: PathFragment?, content: String?) {
        val localFs: FileSystem = getLocalFileSystem(actionFs)
        localFs.getPath(path).getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContent(localFs.getPath(path), java.nio.charset.StandardCharsets.UTF_8, content)
    }

    /** Returns a remote artifact and puts its metadata into the action input map.  */
    private fun createRemoteArtifact(
        pathFragment: String?, content: String, inputs: ActionInputMap
    ): Artifact {
        val a: Artifact = ActionsTestUtil.createArtifact(outputRoot, pathFragment)
        val f: FileArtifactValue? =
            FileArtifactValue.createForRemoteFileWithMaterializationData(
                getDigest(content),
                com.google.common.base.Utf8.encodedLength(content),  /* locationIndex= */
                1,  /* expirationTime= */
                null
            )
        inputs.put(a, f)
        return a
    }

    /** Returns a remote tree artifact and puts its metadata into the action input map.  */
    private fun createRemoteTreeArtifact(
        pathFragment: String?, contentMap: MutableMap<String?, String?>, inputs: ActionInputMap
    ): SpecialArtifact {
        val a: SpecialArtifact =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, pathFragment)
        inputs.putTreeArtifact(a, createRemoteTreeArtifactValue(a, contentMap))
        return a
    }

    private fun createRemoteTreeArtifactValue(
        a: SpecialArtifact?, contentMap: MutableMap<String?, String?>
    ): TreeArtifactValue {
        val builder: TreeArtifactValue.Builder = TreeArtifactValue.newBuilder(a)
        for (entry in contentMap.entries) {
            val child: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(a, entry.key)
            val content: String = entry.value!!
            val childMeta: FileArtifactValue? =
                FileArtifactValue.createForRemoteFileWithMaterializationData(
                    getDigest(content),
                    com.google.common.base.Utf8.encodedLength(content),  /* locationIndex= */
                    0,  /* expirationTime= */
                    null
                )
            builder.putChild(child, childMeta)
        }
        return builder.build()
    }

    /** Returns a local artifact and puts its metadata into the action input map.  */
    @Throws(IOException::class)
    private fun createLocalArtifact(pathFragment: String?, contents: String?, inputs: ActionInputMap): Artifact {
        val p: Path? = outputRoot.getRoot().asPath().getRelative(pathFragment)
        FileSystemUtils.writeContent(p, java.nio.charset.StandardCharsets.UTF_8, contents)
        val a: Artifact = ActionsTestUtil.createArtifact(outputRoot, p)
        val path: Path = a.getPath()
        // Caution: there's a race condition between stating the file and computing the
        // digest. We need to stat first, since we're using the stat to detect changes.
        // We follow symlinks here to be consistent with getDigest.
        inputs.put(
            a,
            FileArtifactValue.createFromStat(path, path.stat(Symlinks.FOLLOW), SyscallCache.NO_CACHE)
        )
        return a
    }

    /** Returns a local tree artifact and puts its metadata into the action input map.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    private fun createLocalTreeArtifact(
        pathFragment: String?, contentMap: MutableMap<String?, String?>, inputs: ActionInputMap
    ): SpecialArtifact {
        val dir: Path = outputRoot.getRoot().asPath().getRelative(pathFragment)
        dir.createDirectoryAndParents()
        for (entry in contentMap.entries) {
            val child: Path = dir.getRelative(entry.key)
            child.getParentDirectory().createDirectoryAndParents()
            FileSystemUtils.writeContent(child, entry.value.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        }
        val a: SpecialArtifact =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, pathFragment)
        inputs.putTreeArtifact(a, createLocalTreeArtifactValue(a, contentMap))
        return a
    }

    private fun getDigest(content: String?): ByteArray {
        return fs.getDigestFunction().getHashFunction().hashString(content, java.nio.charset.StandardCharsets.UTF_8)
            .asBytes()
    }

    companion object {
        private val DUMMY_REMOTE_OUTPUT_CHECKER: RemoteOutputChecker =
            RemoteOutputChecker("build", RemoteOutputsMode.MINIMAL, com.google.common.collect.ImmutableList.of<E?>())

        private const val RELATIVE_OUTPUT_PATH = "out"

        private fun mockPrefetchFile(
            path: Path?,
            contents: String?
        ): Answer<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?> {
            return Answer { invocationOnMock: InvocationOnMock? ->
                FileSystemUtils.writeContent(path, java.nio.charset.StandardCharsets.UTF_8, contents)
                com.google.common.util.concurrent.Futures.immediateVoidFuture()
            }
        }

        @Throws(java.lang.Exception::class)
        private fun assertReaddir(
            actionFs: RemoteActionFileSystem,
            dirPath: PathFragment?,
            followSymlinks: Boolean,
            vararg expected: Dirent?
        ) {
            assertThat(actionFs.readdir(dirPath, followSymlinks)).containsExactlyElementsIn(expected)
            assertThat(actionFs.getDirectoryEntries(dirPath))
                .containsExactlyElementsIn(
                    java.util.Arrays.stream<Dirent?>(expected).map<Any?>(Dirent::getName)
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
                )
        }

        private fun assertReaddirThrows(
            actionFs: RemoteActionFileSystem, dirPath: PathFragment?, followSymlinks: Boolean
        ) {
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { actionFs.readdir(dirPath, followSymlinks) })
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { actionFs.getDirectoryEntries(dirPath) })
        }

        @Throws(IOException::class)
        private fun createLocalTreeArtifactValue(
            a: SpecialArtifact, contentMap: MutableMap<String?, String?>
        ): TreeArtifactValue {
            val builder: TreeArtifactValue.Builder = TreeArtifactValue.newBuilder(a)
            for (name in contentMap.keys) {
                val path: Path = a.getPath().getRelative(name)
                val child: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(a, name)
                val childMeta: FileArtifactValue? =
                    FileArtifactValue.createFromStat(path, path.stat(Symlinks.FOLLOW), SyscallCache.NO_CACHE)
                builder.putChild(child, childMeta)
            }
            return builder.build()
        }
    }
}
