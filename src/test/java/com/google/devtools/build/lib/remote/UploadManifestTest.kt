// Copyright 2021 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.util.StringEncoding.unicodeToInternal

/** Tests for [UploadManifest].  */
@RunWith(TestParameterInjector::class)
class UploadManifestTest {
    private val digestUtil: DigestUtil = DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256)

    private var execRoot: Path? = null
    private var remotePathResolver: RemotePathResolver? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        val fs: FileSystem =
            InMemoryFileSystem(com.google.devtools.build.lib.clock.JavaClock(), DigestHashFunction.SHA256)
        execRoot = fs.getPath("/execroot")
        execRoot.createDirectoryAndParents()

        remotePathResolver = DefaultRemotePathResolver(execRoot)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionResult_absoluteFileSymlinkAsFile() {
        val result: ActionResult.Builder = ActionResult.newBuilder()
        val link: Path = execRoot.getRelative("link")
        val target: Path? = execRoot.getRelative("target")
        FileSystemUtils.writeContent(target, byteArrayOf(1, 2, 3, 4, 5))
        link.createSymbolicLink(target)

        val um: UploadManifest =
            UploadManifest(
                digestUtil, remotePathResolver, result,  /* allowAbsoluteSymlinks= */false
            )
        um.addFiles(com.google.common.collect.ImmutableList.of<E?>(link))
        val digest: Digest? = digestUtil.compute(target)
        assertThat(um.getDigestToFile()).containsExactly(digest, link)

        val expectedResult: ActionResult.Builder = ActionResult.newBuilder()
        expectedResult.addOutputFilesBuilder().setPath("link").setDigest(digest).setIsExecutable(true)
        assertThat(result.build()).isEqualTo(expectedResult.build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionResult_absoluteDirectorySymlinkAsDirectory() {
        val result: ActionResult.Builder = ActionResult.newBuilder()
        val dir: Path = execRoot.getRelative("dir")
        dir.createDirectory()
        val foo: Path? = execRoot.getRelative("dir/foo")
        FileSystemUtils.writeContent(foo, byteArrayOf(1, 2, 3, 4, 5))
        val link: Path = execRoot.getRelative("link")
        link.createSymbolicLink(dir)

        val um: UploadManifest =
            UploadManifest(
                digestUtil, remotePathResolver, result,  /* allowAbsoluteSymlinks= */false
            )
        um.addFiles(com.google.common.collect.ImmutableList.of<E?>(link))
        val digest: Digest? = digestUtil.compute(foo)
        assertThat(um.getDigestToFile()).containsExactly(digest, execRoot.getRelative("link/foo"))

        val tree: Tree? =
            Tree.newBuilder()
                .setRoot(
                    Directory.newBuilder()
                        .addFiles(
                            FileNode.newBuilder()
                                .setName("foo")
                                .setDigest(digest)
                                .setIsExecutable(true)
                        )
                )
                .build()
        val treeDigest: Digest? = digestUtil.compute(tree)

        val expectedResult: ActionResult.Builder = ActionResult.newBuilder()
        expectedResult
            .addOutputDirectoriesBuilder()
            .setPath("link")
            .setTreeDigest(treeDigest)
            .setIsTopologicallySorted(true)
        assertThat(result.build()).isEqualTo(expectedResult.build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionResult_relativeFileSymlinkAsSymlink() {
        val result: ActionResult.Builder = ActionResult.newBuilder()
        val link: Path = execRoot.getRelative("link")
        val target: Path = execRoot.getRelative("target")
        FileSystemUtils.writeContent(target, byteArrayOf(1, 2, 3, 4, 5))
        link.createSymbolicLink(target.relativeTo(execRoot))

        val um: UploadManifest =
            UploadManifest(
                digestUtil, remotePathResolver, result,  /* allowAbsoluteSymlinks= */false
            )
        um.addFiles(com.google.common.collect.ImmutableList.of<E?>(link))
        assertThat(um.getDigestToFile()).isEmpty()

        val expectedResult: ActionResult.Builder = ActionResult.newBuilder()
        expectedResult.addOutputFileSymlinksBuilder().setPath("link").setTarget("target")
        expectedResult.addOutputSymlinksBuilder().setPath("link").setTarget("target")
        assertThat(result.build()).isEqualTo(expectedResult.build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionResult_relativeDirectorySymlinkAsSymlink() {
        val result: ActionResult.Builder = ActionResult.newBuilder()
        val dir: Path = execRoot.getRelative("dir")
        dir.createDirectory()
        val file: Path? = execRoot.getRelative("dir/foo")
        FileSystemUtils.writeContent(file, byteArrayOf(1, 2, 3, 4, 5))
        val link: Path = execRoot.getRelative("link")
        link.createSymbolicLink(dir.relativeTo(execRoot))

        val um: UploadManifest =
            UploadManifest(
                digestUtil, remotePathResolver, result,  /* allowAbsoluteSymlinks= */false
            )
        um.addFiles(com.google.common.collect.ImmutableList.of<E?>(link))
        assertThat(um.getDigestToFile()).isEmpty()

        val expectedResult: ActionResult.Builder = ActionResult.newBuilder()
        expectedResult.addOutputDirectorySymlinksBuilder().setPath("link").setTarget("dir")
        expectedResult.addOutputSymlinksBuilder().setPath("link").setTarget("dir")
        assertThat(result.build()).isEqualTo(expectedResult.build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionResult_noAllowAbsoluteSymlinks_absoluteDanglingSymlinkError(
        @TestParameter looping: Boolean
    ) {
        val result: ActionResult.Builder? = ActionResult.newBuilder()
        val link: Path = execRoot.getRelative("link")
        val target: Path = execRoot.getRelative("target")
        link.createSymbolicLink(target)
        if (looping) {
            target.createSymbolicLink(link)
        }

        val um: UploadManifest =
            UploadManifest(
                digestUtil, remotePathResolver, result,  /* allowAbsoluteSymlinks= */false
            )
        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { um.addFiles(com.google.common.collect.ImmutableList.of<E?>(link)) })
        Truth.assertThat(e).hasMessageThat().contains("absolute")
        Truth.assertThat(e).hasMessageThat().contains("/execroot/link")
        Truth.assertThat(e).hasMessageThat().contains("target")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionResult_allowAbsoluteSymlinks_absoluteDanglingSymlinkAsSymlink(
        @TestParameter looping: Boolean
    ) {
        val result: ActionResult.Builder = ActionResult.newBuilder()
        val link: Path = execRoot.getRelative("link")
        val target: Path = execRoot.getRelative("target")
        link.createSymbolicLink(target)
        if (looping) {
            target.createSymbolicLink(link)
        }

        val um: UploadManifest =
            UploadManifest(
                digestUtil, remotePathResolver, result,  /* allowAbsoluteSymlinks= */true
            )
        um.addFiles(com.google.common.collect.ImmutableList.of<E?>(link))
        assertThat(um.getDigestToFile()).isEmpty()

        val expectedResult: ActionResult.Builder = ActionResult.newBuilder()
        expectedResult.addOutputFileSymlinksBuilder().setPath("link").setTarget("/execroot/target")
        expectedResult.addOutputSymlinksBuilder().setPath("link").setTarget("/execroot/target")
        assertThat(result.build()).isEqualTo(expectedResult.build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionResult_relativeDanglingSymlinkAsSymlink(@TestParameter looping: Boolean) {
        val result: ActionResult.Builder = ActionResult.newBuilder()
        val link: Path = execRoot.getRelative("link")
        val target: Path = execRoot.getRelative("target")
        link.createSymbolicLink(target.relativeTo(link.getParentDirectory()))
        if (looping) {
            target.createSymbolicLink(link.relativeTo(target.getParentDirectory()))
        }

        val um: UploadManifest =
            UploadManifest(
                digestUtil, remotePathResolver, result,  /* allowAbsoluteSymlinks= */false
            )
        um.addFiles(com.google.common.collect.ImmutableList.of<E?>(link))
        assertThat(um.getDigestToFile()).isEmpty()

        val expectedResult: ActionResult.Builder = ActionResult.newBuilder()
        expectedResult.addOutputFileSymlinksBuilder().setPath("link").setTarget("target")
        expectedResult.addOutputSymlinksBuilder().setPath("link").setTarget("target")
        assertThat(result.build()).isEqualTo(expectedResult.build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionResult_absoluteFileSymlinkInDirectoryAsFile() {
        val result: ActionResult.Builder = ActionResult.newBuilder()
        val dir: Path = execRoot.getRelative("dir")
        dir.createDirectory()
        val target: Path? = execRoot.getRelative("target")
        FileSystemUtils.writeContent(target, byteArrayOf(1, 2, 3, 4, 5))
        val link: Path = execRoot.getRelative("dir/link")
        link.createSymbolicLink(target)

        val um: UploadManifest =
            UploadManifest(
                digestUtil, remotePathResolver, result,  /* allowAbsoluteSymlinks= */false
            )
        um.addFiles(com.google.common.collect.ImmutableList.of<E?>(dir))
        val digest: Digest? = digestUtil.compute(target)
        assertThat(um.getDigestToFile()).containsExactly(digest, link)

        val tree: Tree? =
            Tree.newBuilder()
                .setRoot(
                    Directory.newBuilder()
                        .addFiles(
                            FileNode.newBuilder()
                                .setName("link")
                                .setDigest(digest)
                                .setIsExecutable(true)
                        )
                )
                .build()
        val treeDigest: Digest? = digestUtil.compute(tree)

        val expectedResult: ActionResult.Builder = ActionResult.newBuilder()
        expectedResult
            .addOutputDirectoriesBuilder()
            .setPath("dir")
            .setTreeDigest(treeDigest)
            .setIsTopologicallySorted(true)
        assertThat(result.build()).isEqualTo(expectedResult.build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionResult_absoluteDirectorySymlinkInDirectoryAsDirectory() {
        val result: ActionResult.Builder = ActionResult.newBuilder()
        val dir: Path = execRoot.getRelative("dir")
        dir.createDirectory()
        val bardir: Path = execRoot.getRelative("bardir")
        bardir.createDirectory()
        val foo: Path? = execRoot.getRelative("bardir/foo")
        FileSystemUtils.writeContent(foo, byteArrayOf(1, 2, 3, 4, 5))
        val link: Path = execRoot.getRelative("dir/link")
        link.createSymbolicLink(bardir)

        val um: UploadManifest =
            UploadManifest(
                digestUtil, remotePathResolver, result,  /* allowAbsoluteSymlinks= */false
            )
        um.addFiles(com.google.common.collect.ImmutableList.of<E?>(dir))
        val digest: Digest? = digestUtil.compute(foo)
        assertThat(um.getDigestToFile()).containsExactly(digest, execRoot.getRelative("dir/link/foo"))

        val barDir: Directory? =
            Directory.newBuilder()
                .addFiles(FileNode.newBuilder().setName("foo").setDigest(digest).setIsExecutable(true))
                .build()
        val barDigest: Digest? = digestUtil.compute(barDir)
        val tree: Tree? =
            Tree.newBuilder()
                .setRoot(
                    Directory.newBuilder()
                        .addDirectories(
                            DirectoryNode.newBuilder().setName("link").setDigest(barDigest)
                        )
                )
                .addChildren(barDir)
                .build()
        val treeDigest: Digest? = digestUtil.compute(tree)

        val expectedResult: ActionResult.Builder = ActionResult.newBuilder()
        expectedResult
            .addOutputDirectoriesBuilder()
            .setPath("dir")
            .setTreeDigest(treeDigest)
            .setIsTopologicallySorted(true)
        assertThat(result.build()).isEqualTo(expectedResult.build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionResult_relativeFileSymlinkInDirectoryAsSymlink() {
        val result: ActionResult.Builder = ActionResult.newBuilder()
        val dir: Path = execRoot.getRelative("dir")
        dir.createDirectory()
        val target: Path? = execRoot.getRelative("target")
        FileSystemUtils.writeContent(target, byteArrayOf(1, 2, 3, 4, 5))
        val link: Path = execRoot.getRelative("dir/link")
        link.createSymbolicLink(PathFragment.create("../target"))

        val um: UploadManifest =
            UploadManifest(
                digestUtil, remotePathResolver, result,  /* allowAbsoluteSymlinks= */false
            )
        um.addFiles(com.google.common.collect.ImmutableList.of<E?>(dir))
        assertThat(um.getDigestToFile()).isEmpty()

        val tree: Tree? =
            Tree.newBuilder()
                .setRoot(
                    Directory.newBuilder()
                        .addSymlinks(SymlinkNode.newBuilder().setName("link").setTarget("../target"))
                )
                .build()
        val treeDigest: Digest? = digestUtil.compute(tree)

        val expectedResult: ActionResult.Builder = ActionResult.newBuilder()
        expectedResult
            .addOutputDirectoriesBuilder()
            .setPath("dir")
            .setTreeDigest(treeDigest)
            .setIsTopologicallySorted(true)
        assertThat(result.build()).isEqualTo(expectedResult.build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionResult_relativeDirectorySymlinkInDirectoryAsSymlink() {
        val result: ActionResult.Builder = ActionResult.newBuilder()
        val dir: Path = execRoot.getRelative("dir")
        dir.createDirectory()
        val bardir: Path = execRoot.getRelative("bardir")
        bardir.createDirectory()
        val foo: Path? = execRoot.getRelative("bardir/foo")
        FileSystemUtils.writeContent(foo, byteArrayOf(1, 2, 3, 4, 5))
        val link: Path = execRoot.getRelative("dir/link")
        link.createSymbolicLink(PathFragment.create("../bardir"))

        val um: UploadManifest =
            UploadManifest(
                digestUtil, remotePathResolver, result,  /* allowAbsoluteSymlinks= */false
            )
        um.addFiles(com.google.common.collect.ImmutableList.of<E?>(dir))
        assertThat(um.getDigestToFile()).isEmpty()

        val tree: Tree? =
            Tree.newBuilder()
                .setRoot(
                    Directory.newBuilder()
                        .addSymlinks(SymlinkNode.newBuilder().setName("link").setTarget("../bardir"))
                )
                .build()
        val treeDigest: Digest? = digestUtil.compute(tree)

        val expectedResult: ActionResult.Builder = ActionResult.newBuilder()
        expectedResult
            .addOutputDirectoriesBuilder()
            .setPath("dir")
            .setTreeDigest(treeDigest)
            .setIsTopologicallySorted(true)
        assertThat(result.build()).isEqualTo(expectedResult.build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionResult_allowAbsoluteSymlinks_absoluteDanglingSymlinkInDirectoryAsSymlink(
        @TestParameter looping: Boolean
    ) {
        val result: ActionResult.Builder = ActionResult.newBuilder()
        val dir: Path = execRoot.getRelative("dir")
        dir.createDirectory()
        val target: Path = execRoot.getRelative("target")
        val link: Path = execRoot.getRelative("dir/link")
        link.createSymbolicLink(target)
        if (looping) {
            target.createSymbolicLink(link)
        }

        val um: UploadManifest =
            UploadManifest(
                digestUtil, remotePathResolver, result,  /* allowAbsoluteSymlinks= */true
            )
        um.addFiles(com.google.common.collect.ImmutableList.of<E?>(dir))
        assertThat(um.getDigestToFile()).isEmpty()

        val tree: Tree? =
            Tree.newBuilder()
                .setRoot(
                    Directory.newBuilder()
                        .addSymlinks(
                            SymlinkNode.newBuilder().setName("link").setTarget("/execroot/target")
                        )
                )
                .build()
        val treeDigest: Digest? = digestUtil.compute(tree)

        val expectedResult: ActionResult.Builder = ActionResult.newBuilder()
        expectedResult
            .addOutputDirectoriesBuilder()
            .setPath("dir")
            .setTreeDigest(treeDigest)
            .setIsTopologicallySorted(true)
        assertThat(result.build()).isEqualTo(expectedResult.build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionResult_noAllowAbsoluteSymlinks_absoluteDanglingSymlinkInDirectoryError(
        @TestParameter looping: Boolean
    ) {
        val result: ActionResult.Builder? = ActionResult.newBuilder()
        val dir: Path = execRoot.getRelative("dir")
        dir.createDirectory()
        val target: Path = execRoot.getRelative("target")
        val link: Path = execRoot.getRelative("dir/link")
        link.createSymbolicLink(target)
        if (looping) {
            target.createSymbolicLink(link)
        }

        val um: UploadManifest =
            UploadManifest(
                digestUtil, remotePathResolver, result,  /* allowAbsoluteSymlinks= */false
            )
        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { um.addFiles(com.google.common.collect.ImmutableList.of<E?>(link)) })
        Truth.assertThat(e).hasMessageThat().contains("absolute")
        Truth.assertThat(e).hasMessageThat().contains("/execroot/dir/link")
        Truth.assertThat(e).hasMessageThat().contains("target")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionResult_relativeDanglingSymlinkInDirectoryAsSymlink(
        @TestParameter looping: Boolean
    ) {
        val result: ActionResult.Builder = ActionResult.newBuilder()
        val dir: Path = execRoot.getRelative("dir")
        dir.createDirectory()
        val target: Path = execRoot.getRelative("target")
        val link: Path = execRoot.getRelative("dir/link")
        // target.relativeTo(link.getParentDirectory()) does not work because relativeTo refuses to
        // create uplevel references.
        link.createSymbolicLink(PathFragment.create("../target"))
        if (looping) {
            target.createSymbolicLink(link.relativeTo(target.getParentDirectory()))
        }

        val um: UploadManifest =
            UploadManifest(
                digestUtil, remotePathResolver, result,  /* allowAbsoluteSymlinks= */false
            )
        um.addFiles(com.google.common.collect.ImmutableList.of<E?>(dir))
        assertThat(um.getDigestToFile()).isEmpty()

        val tree: Tree? =
            Tree.newBuilder()
                .setRoot(
                    Directory.newBuilder()
                        .addSymlinks(SymlinkNode.newBuilder().setName("link").setTarget("../target"))
                )
                .build()
        val treeDigest: Digest? = digestUtil.compute(tree)

        val expectedResult: ActionResult.Builder = ActionResult.newBuilder()
        expectedResult
            .addOutputDirectoriesBuilder()
            .setPath("dir")
            .setTreeDigest(treeDigest)
            .setIsTopologicallySorted(true)
        assertThat(result.build()).isEqualTo(expectedResult.build())
    }

    // Tests to verify that files with an unsupported type (collectively, "special files") are
    // rejected. We must use mocks since Bazel's filesystems don't support their creation.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionResult_specialFileError() {
        val result: ActionResult.Builder? = ActionResult.newBuilder()
        val dir: Path = createDirectoryWithSpecialFile("dir", "special")
        val special: Path = dir.getChild("special")

        val um: UploadManifest =
            UploadManifest(
                digestUtil, remotePathResolver, result,  /* allowAbsoluteSymlinks= */false
            )
        val e: UserExecException? =
            org.junit.Assert.assertThrows<T?>(
                UserExecException::class.java,
                org.junit.function.ThrowingRunnable { um.addFiles(com.google.common.collect.ImmutableList.of<E?>(special)) })
        assertThat(e).hasMessageThat().contains("special file")
        assertThat(e).hasMessageThat().contains("dir/special")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionResult_specialFileInDirectoryError() {
        val result: ActionResult.Builder? = ActionResult.newBuilder()
        val dir: Path = createDirectoryWithSpecialFile("dir", "special")

        val um: UploadManifest =
            UploadManifest(
                digestUtil, remotePathResolver, result,  /* allowAbsoluteSymlinks= */false
            )
        val e: UserExecException? =
            org.junit.Assert.assertThrows<T?>(
                UserExecException::class.java,
                org.junit.function.ThrowingRunnable { um.addFiles(com.google.common.collect.ImmutableList.of<E?>(dir)) })
        assertThat(e).hasMessageThat().contains("special file")
        assertThat(e).hasMessageThat().contains("dir/special")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionResult_followSymlinks_absoluteSymlinkToSpecialFileError() {
        val result: ActionResult.Builder? = ActionResult.newBuilder()
        val dir: Path = createDirectoryWithSymlinkToSpecialFile("dir", "link", "special")
        val link: Path = dir.getChild("link")

        val um: UploadManifest =
            UploadManifest(
                digestUtil, remotePathResolver, result,  /* allowAbsoluteSymlinks= */false
            )
        val e: UserExecException? =
            org.junit.Assert.assertThrows<T?>(
                UserExecException::class.java,
                org.junit.function.ThrowingRunnable { um.addFiles(com.google.common.collect.ImmutableList.of<E?>(link)) })
        assertThat(e).hasMessageThat().contains("special file")
        assertThat(e).hasMessageThat().contains("dir/link")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionResult_followSymlinks_absoluteSymlinkToSpecialFileInDirectoryError() {
        val result: ActionResult.Builder? = ActionResult.newBuilder()
        val dir: Path = createDirectoryWithSymlinkToSpecialFile("dir", "link", "special")

        val um: UploadManifest =
            UploadManifest(
                digestUtil, remotePathResolver, result,  /* allowAbsoluteSymlinks= */false
            )
        val e: UserExecException? =
            org.junit.Assert.assertThrows<T?>(
                UserExecException::class.java,
                org.junit.function.ThrowingRunnable { um.addFiles(com.google.common.collect.ImmutableList.of<E?>(dir)) })
        assertThat(e).hasMessageThat().contains("special file")
        assertThat(e).hasMessageThat().contains("dir/link")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionResult_topologicallySortedAndDeduplicatedTree() {
        // Create 5^3 identical files named "dir/%d/%d/%d/file".
        val dir: Path = execRoot.getRelative("dir")
        dir.createDirectory()
        val fileContents = byteArrayOf(1, 2, 3, 4, 5)
        val childrenPerDirectory = 5
        for (a in 0..<childrenPerDirectory) {
            val pathA: Path = dir.getRelative(java.lang.Integer.toString(a))
            pathA.createDirectory()
            for (b in 0..<childrenPerDirectory) {
                val pathB: Path = pathA.getRelative(java.lang.Integer.toString(b))
                pathB.createDirectory()
                for (c in 0..<childrenPerDirectory) {
                    val pathC: Path = pathB.getRelative(java.lang.Integer.toString(c))
                    pathC.createDirectory()
                    val file: Path? = pathC.getRelative("file")
                    FileSystemUtils.writeContent(file, fileContents)
                }
            }
        }

        val result: ActionResult.Builder = ActionResult.newBuilder()
        val um: UploadManifest =
            UploadManifest(
                digestUtil, remotePathResolver, result,  /* allowAbsoluteSymlinks= */false
            )
        um.addFiles(com.google.common.collect.ImmutableList.of<E?>(dir))

        // Even though we constructed 1 + 5 + 5^2 + 5^3 directories, the resulting
        // Tree message should only contain four unique instances. The directories
        // should also be topologically sorted.
        val children: MutableList<Directory?> = java.util.ArrayList<Directory?>()
        var root: Directory =
            Directory.newBuilder()
                .addFiles(
                    FileNode.newBuilder()
                        .setName("file")
                        .setDigest(digestUtil.compute(fileContents))
                        .setIsExecutable(true)
                )
                .build()
        for (depth in 0..2) {
            val b: Directory.Builder = Directory.newBuilder()
            val parentDigest: Digest? = digestUtil.compute(root.toByteArray())
            for (i in 0..<childrenPerDirectory) {
                b.addDirectories(
                    DirectoryNode.newBuilder().setName(java.lang.Integer.toString(i)).setDigest(parentDigest)
                )
            }
            children.add(0, root)
            root = b.build()
        }
        val tree: Tree? = Tree.newBuilder().setRoot(root).addAllChildren(children).build()
        val treeDigest: Digest? = digestUtil.compute(tree)

        val expectedResult: ActionResult.Builder = ActionResult.newBuilder()
        expectedResult
            .addOutputDirectoriesBuilder()
            .setPath("dir")
            .setTreeDigest(treeDigest)
            .setIsTopologicallySorted(true)
        assertThat(result.build()).isEqualTo(expectedResult.build())
    }

    @Throws(IOException::class)
    private fun createSpecialFile(execPath: String?): Path {
        val special: Path = Mockito.mock<Path>(Path::class.java)
        Mockito.`when`<T?>(special.toString()).thenReturn(execPath)
        Mockito.`when`<T?>(special.statIfFound(Symlinks.NOFOLLOW)).thenReturn(SPECIAL_FILE_STATUS)
        Mockito.`when`<T?>(special.relativeTo(execRoot))
            .thenReturn(execRoot.getRelative(execPath).relativeTo(execRoot))

        return special
    }

    @Throws(IOException::class)
    private fun createSymlinkToSpecialFile(execPath: String?, target: String?): Path {
        val link: Path = Mockito.mock<Path>(Path::class.java)
        Mockito.`when`<T?>(link.toString()).thenReturn(execPath)
        Mockito.`when`<T?>(link.statIfFound(Symlinks.NOFOLLOW)).thenReturn(SYMLINK_FILE_STATUS)
        Mockito.`when`<T?>(link.statIfFound(Symlinks.FOLLOW)).thenReturn(SPECIAL_FILE_STATUS)
        Mockito.`when`<T?>(link.readSymbolicLink()).thenReturn(PathFragment.create(target))

        return link
    }

    @Throws(IOException::class)
    private fun createDirectoryWithSpecialFile(dirExecPath: String?, specialName: String?): Path {
        val special: Path = createSpecialFile(dirExecPath + "/" + specialName)

        val dir: Path = Mockito.mock<Path>(Path::class.java)
        Mockito.`when`<T?>(dir.toString()).thenReturn(dirExecPath)
        Mockito.`when`<T?>(dir.statIfFound(Symlinks.NOFOLLOW)).thenReturn(DIR_FILE_STATUS)
        Mockito.`when`<T?>(dir.readdir(Symlinks.NOFOLLOW))
            .thenReturn(com.google.common.collect.ImmutableList.of<E?>(Dirent(specialName, Dirent.Type.UNKNOWN)))
        Mockito.`when`<T?>(dir.getChild(specialName)).thenReturn(special)

        return dir
    }

    @Throws(IOException::class)
    private fun createDirectoryWithSymlinkToSpecialFile(
        dirExecPath: String?, linkName: String?, specialName: String?
    ): Path {
        val unusedSpecial: Path = createSpecialFile(dirExecPath + "/" + specialName)
        val link: Path =
            createSymlinkToSpecialFile(
                dirExecPath + "/" + linkName,
                execRoot.getRelative(dirExecPath).getRelative(specialName).getPathString()
            )

        val dir: Path = Mockito.mock<Path>(Path::class.java)
        Mockito.`when`<T?>(dir.toString()).thenReturn(dirExecPath)
        Mockito.`when`<T?>(dir.statIfFound(Symlinks.NOFOLLOW)).thenReturn(DIR_FILE_STATUS)
        Mockito.`when`<T?>(dir.readdir(Symlinks.NOFOLLOW))
            .thenReturn(com.google.common.collect.ImmutableList.of<E?>(Dirent(linkName, Dirent.Type.SYMLINK)))
        Mockito.`when`<T?>(dir.getChild(linkName)).thenReturn(link)

        return dir
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionResult_preserveExecutableBit_executableFile() {
        val result: ActionResult.Builder = ActionResult.newBuilder()
        val file: Path = execRoot.getRelative("file")
        FileSystemUtils.writeContent(file, byteArrayOf(1, 2, 3, 4, 5))
        file.setExecutable(true)

        val um: UploadManifest =
            UploadManifest(
                digestUtil,
                remotePathResolver,
                result,  /* allowAbsoluteSymlinks= */
                false,  /* preserveExecutableBit= */
                true
            )
        um.addFiles(com.google.common.collect.ImmutableList.of<E?>(file))
        val digest: Digest? = digestUtil.compute(file)
        assertThat(um.getDigestToFile()).containsExactly(digest, file)

        val expectedResult: ActionResult.Builder = ActionResult.newBuilder()
        expectedResult.addOutputFilesBuilder().setPath("file").setDigest(digest).setIsExecutable(true)
        assertThat(result.build()).isEqualTo(expectedResult.build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionResult_preserveExecutableBit_nonExecutableFile() {
        val result: ActionResult.Builder = ActionResult.newBuilder()
        val file: Path = execRoot.getRelative("file")
        FileSystemUtils.writeContent(file, byteArrayOf(1, 2, 3, 4, 5))
        file.setExecutable(false)

        val um: UploadManifest =
            UploadManifest(
                digestUtil,
                remotePathResolver,
                result,  /* allowAbsoluteSymlinks= */
                false,  /* preserveExecutableBit= */
                true
            )
        um.addFiles(com.google.common.collect.ImmutableList.of<E?>(file))
        val digest: Digest? = digestUtil.compute(file)
        assertThat(um.getDigestToFile()).containsExactly(digest, file)

        val expectedResult: ActionResult.Builder = ActionResult.newBuilder()
        expectedResult.addOutputFilesBuilder().setPath("file").setDigest(digest).setIsExecutable(false)
        assertThat(result.build()).isEqualTo(expectedResult.build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionResult_preserveExecutableBit_mixedFilesInDirectory() {
        val result: ActionResult.Builder = ActionResult.newBuilder()
        val dir: Path = execRoot.getRelative("dir")
        dir.createDirectory()
        val executableFile: Path = execRoot.getRelative("dir/executable")
        FileSystemUtils.writeContent(executableFile, byteArrayOf(1, 2, 3))
        executableFile.setExecutable(true)
        val nonExecutableFile: Path = execRoot.getRelative("dir/nonexecutable")
        FileSystemUtils.writeContent(nonExecutableFile, byteArrayOf(4, 5, 6))
        nonExecutableFile.setExecutable(false)

        val um: UploadManifest =
            UploadManifest(
                digestUtil,
                remotePathResolver,
                result,  /* allowAbsoluteSymlinks= */
                false,  /* preserveExecutableBit= */
                true
            )
        um.addFiles(com.google.common.collect.ImmutableList.of<E?>(dir))

        val executableDigest: Digest? = digestUtil.compute(executableFile)
        val nonExecutableDigest: Digest? = digestUtil.compute(nonExecutableFile)
        assertThat(um.getDigestToFile())
            .containsExactly(executableDigest, executableFile, nonExecutableDigest, nonExecutableFile)

        val tree: Tree? =
            Tree.newBuilder()
                .setRoot(
                    Directory.newBuilder()
                        .addFiles(
                            FileNode.newBuilder()
                                .setName("executable")
                                .setDigest(executableDigest)
                                .setIsExecutable(true)
                        )
                        .addFiles(
                            FileNode.newBuilder()
                                .setName("nonexecutable")
                                .setDigest(nonExecutableDigest)
                                .setIsExecutable(false)
                        )
                )
                .build()
        val treeDigest: Digest? = digestUtil.compute(tree)

        val expectedResult: ActionResult.Builder = ActionResult.newBuilder()
        expectedResult
            .addOutputDirectoriesBuilder()
            .setPath("dir")
            .setTreeDigest(treeDigest)
            .setIsTopologicallySorted(true)
        assertThat(result.build()).isEqualTo(expectedResult.build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionResult_unicodeDirectoryTree() {
        // Verify that non-ASCII names in directory trees are converted from Bazel's internal
        // encoding to Unicode in the protobuf messages (FileNode, DirectoryNode, SymlinkNode).
        val result: ActionResult.Builder = ActionResult.newBuilder()
        val dir: Path = execRoot.getRelative(unicodeToInternal("dïr"))
        dir.createDirectory()
        val subdir: Path = execRoot.getRelative(unicodeToInternal("dïr/sübdïr"))
        subdir.createDirectory()
        val file: Path? = execRoot.getRelative(unicodeToInternal("dïr/sübdïr/fïlé"))
        FileSystemUtils.writeContent(file, byteArrayOf(1, 2, 3, 4, 5))
        val link: Path = execRoot.getRelative(unicodeToInternal("dïr/lïnk"))
        link.createSymbolicLink(PathFragment.create(unicodeToInternal("../tàrgét")))

        val um: UploadManifest =
            UploadManifest(
                digestUtil, remotePathResolver, result,  /* allowAbsoluteSymlinks= */false
            )
        um.addFiles(com.google.common.collect.ImmutableList.of<E?>(dir))
        val fileDigest: Digest? = digestUtil.compute(file)

        // Build the expected tree with Unicode names.
        val subdirDir: Directory? =
            Directory.newBuilder()
                .addFiles(
                    FileNode.newBuilder().setName("fïlé").setDigest(fileDigest).setIsExecutable(true)
                )
                .build()
        val subdirDigest: Digest? = digestUtil.compute(subdirDir)
        val rootDir: Directory? =
            Directory.newBuilder()
                .addDirectories(DirectoryNode.newBuilder().setName("sübdïr").setDigest(subdirDigest))
                .addSymlinks(SymlinkNode.newBuilder().setName("lïnk").setTarget("../tàrgét"))
                .build()
        val tree: Tree? = Tree.newBuilder().setRoot(rootDir).addChildren(subdirDir).build()
        val treeDigest: Digest? = digestUtil.compute(tree)

        val expectedResult: ActionResult.Builder = ActionResult.newBuilder()
        expectedResult
            .addOutputDirectoriesBuilder()
            .setPath("dïr")
            .setTreeDigest(treeDigest)
            .setIsTopologicallySorted(true)
        assertThat(result.build()).isEqualTo(expectedResult.build())
    }

    companion object {
        private val SPECIAL_FILE_STATUS: FileStatus = object : FileStatus() {
            val isFile: Boolean
                get() = true

            val isDirectory: Boolean
                get() = false

            val isSymbolicLink: Boolean
                get() = false

            val isSpecialFile: Boolean
                get() = true

            val size: Long
                get() = 0

            val lastModifiedTime: Long
                get() = 0

            val lastChangeTime: Long
                get() = 0

            val nodeId: Long
                get() = 0
        }

        private val DIR_FILE_STATUS: FileStatus = object : FileStatus() {
            val isFile: Boolean
                get() = false

            val isDirectory: Boolean
                get() = true

            val isSymbolicLink: Boolean
                get() = false

            val isSpecialFile: Boolean
                get() = false

            val size: Long
                get() = 0

            val lastModifiedTime: Long
                get() = 0

            val lastChangeTime: Long
                get() = 0

            val nodeId: Long
                get() = 0
        }

        private val SYMLINK_FILE_STATUS: FileStatus = object : FileStatus() {
            val isFile: Boolean
                get() = false

            val isDirectory: Boolean
                get() = false

            val isSymbolicLink: Boolean
                get() = true

            val isSpecialFile: Boolean
                get() = false

            val size: Long
                get() = 0

            val lastModifiedTime: Long
                get() = 0

            val lastChangeTime: Long
                get() = 0

            val nodeId: Long
                get() = 0
        }
    }
}
