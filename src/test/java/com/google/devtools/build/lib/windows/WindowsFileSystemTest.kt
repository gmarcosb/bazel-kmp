// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.windows

import com.google.devtools.build.lib.skyframe.DefaultSyscallCache

/** Unit tests for [WindowsFileSystem].  */
@RunWith(TestParameterInjector::class)
@TestSpec(supportedOs = [com.google.devtools.build.lib.util.OS.WINDOWS])
class WindowsFileSystemTest {
    @TestParameter
    var createSymbolicLinks: Boolean = false

    private var fs: WindowsFileSystem? = null
    private var scratchRoot: Path? = null
    private var testUtil: WindowsTestUtil? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createScratchDir() {
        fs = WindowsFileSystem(DigestHashFunction.SHA256, createSymbolicLinks)
        scratchRoot = com.google.devtools.build.lib.testutil.TestUtils.createUniqueTmpDir(fs)
        testUtil = WindowsTestUtil(scratchRoot.getPathString())
    }

    @org.junit.After
    @Throws(java.lang.Exception::class)
    fun destroyScratchDir() {
        scratchRoot.deleteTree()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCanWorkWithJunctionSymlinks() {
        testUtil.scratchFile("dir\\hello.txt", "hello")
        testUtil.scratchDir("non_existent")
        testUtil.createJunctions(
            com.google.common.collect.ImmutableMap.of<String?, String?>(
                "junc",
                "dir",
                "junc_bad",
                "non_existent"
            )
        )

        val juncPath: Path = testUtil.createVfsPath(fs, "junc")
        val dirPath: Path = testUtil.createVfsPath(fs, "dir")
        val juncBadPath: Path = testUtil.createVfsPath(fs, "junc_bad")
        val nonExistentPath: Path = testUtil.createVfsPath(fs, "non_existent")

        // Test junction creation.
        assertThat(juncPath.exists(Symlinks.NOFOLLOW)).isTrue()
        assertThat(dirPath.exists(Symlinks.NOFOLLOW)).isTrue()
        assertThat(juncBadPath.exists(Symlinks.NOFOLLOW)).isTrue()
        assertThat(nonExistentPath.exists(Symlinks.NOFOLLOW)).isTrue()

        // Test recognizing and dereferencing a directory junction.
        assertThat(juncPath.isSymbolicLink()).isTrue()
        assertThat(juncPath.isDirectory(Symlinks.FOLLOW)).isTrue()
        assertThat(juncPath.isDirectory(Symlinks.NOFOLLOW)).isFalse()
        assertThat(juncPath.getDirectoryEntries())
            .containsExactly(testUtil.createVfsPath(fs, "junc\\hello.txt"))

        // Test deleting a directory junction.
        assertThat(juncPath.delete()).isTrue()
        assertThat(juncPath.exists(Symlinks.NOFOLLOW)).isFalse()

        // Test recognizing a dangling directory junction.
        assertThat(nonExistentPath.delete()).isTrue()
        assertThat(nonExistentPath.exists(Symlinks.NOFOLLOW)).isFalse()
        assertThat(juncBadPath.exists(Symlinks.NOFOLLOW)).isTrue()
        // TODO(bazel-team): fix https://github.com/bazelbuild/bazel/issues/1690 and uncomment the
        // assertion below.
        // assertThat(fs.isSymbolicLink(juncBadPath)).isTrue();
        assertThat(fs.isDirectory(juncBadPath.asFragment(),  /* followSymlinks */true)).isFalse()
        assertThat(fs.isDirectory(juncBadPath.asFragment(),  /* followSymlinks */false)).isFalse()

        // Test deleting a dangling junction.
        assertThat(juncBadPath.delete()).isTrue()
        assertThat(juncBadPath.exists(Symlinks.NOFOLLOW)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMockJunctionCreation() {
        val root: String? = testUtil.scratchDir("dir").getParent().toString()
        testUtil.scratchFile("dir/file.txt", "hello")
        testUtil.createJunctions(com.google.common.collect.ImmutableMap.of<String?, String?>("junc", "dir"))
        val children: Array<String?>? = java.io.File(root + "/junc").list()
        Truth.assertThat<String?>(children).isNotNull()
        Truth.assertThat<String?>(children).hasLength(1)
        Truth.assertThat(java.util.Arrays.asList<String?>(*children)).containsExactly("file.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIsJunction() {
        val junctions: MutableMap<String?, String?> = HashMap<String?, String?>()
        junctions.put("shrtpath/a", "shrttrgt")
        junctions.put("shrtpath/b", "longtargetpath")
        junctions.put("shrtpath/c", "longta~1")
        junctions.put("longlinkpath/a", "shrttrgt")
        junctions.put("longlinkpath/b", "longtargetpath")
        junctions.put("longlinkpath/c", "longta~1")
        junctions.put("abbrev~1/a", "shrttrgt")
        junctions.put("abbrev~1/b", "longtargetpath")
        junctions.put("abbrev~1/c", "longta~1")

        val root: String? = testUtil.scratchDir("shrtpath").getParent().toAbsolutePath().toString()
        testUtil.scratchDir("longlinkpath")
        testUtil.scratchDir("abbreviated")
        testUtil.scratchDir("control/a")
        testUtil.scratchDir("control/b")
        testUtil.scratchDir("control/c")

        testUtil.scratchFile("shrttrgt/file1.txt", "hello")
        testUtil.scratchFile("longtargetpath/file2.txt", "hello")

        testUtil.createJunctions(junctions)

        assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "shrtpath/a"))).isTrue()
        assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "shrtpath/b"))).isTrue()
        assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "shrtpath/c"))).isTrue()
        assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "longlinkpath/a"))).isTrue()
        assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "longlinkpath/b"))).isTrue()
        assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "longlinkpath/c"))).isTrue()
        assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "longli~1/a"))).isTrue()
        assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "longli~1/b"))).isTrue()
        assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "longli~1/c"))).isTrue()
        assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "abbreviated/a"))).isTrue()
        assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "abbreviated/b"))).isTrue()
        assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "abbreviated/c"))).isTrue()
        assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "abbrev~1/a"))).isTrue()
        assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "abbrev~1/b"))).isTrue()
        assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "abbrev~1/c"))).isTrue()
        assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "control/a"))).isFalse()
        assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "control/b"))).isFalse()
        assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "control/c"))).isFalse()
        assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "shrttrgt/file1.txt")))
            .isFalse()
        assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "longtargetpath/file2.txt")))
            .isFalse()
        assertThat(WindowsFileSystem.isSymlinkOrJunction(Paths.get(root, "longta~1/file2.txt")))
            .isFalse()

        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable {
                WindowsFileSystem.isSymlinkOrJunction(
                    Paths.get(
                        root,
                        "non-existent"
                    )
                )
            })

        Truth.assertThat(java.util.Arrays.asList<String?>(*java.io.File(root + "/shrtpath/a").list()))
            .containsExactly("file1.txt")
        Truth.assertThat(java.util.Arrays.asList<String?>(*java.io.File(root + "/shrtpath/b").list()))
            .containsExactly("file2.txt")
        Truth.assertThat(java.util.Arrays.asList<String?>(*java.io.File(root + "/shrtpath/c").list()))
            .containsExactly("file2.txt")
        Truth.assertThat(java.util.Arrays.asList<String?>(*java.io.File(root + "/longlinkpath/a").list()))
            .containsExactly("file1.txt")
        Truth.assertThat(java.util.Arrays.asList<String?>(*java.io.File(root + "/longlinkpath/b").list()))
            .containsExactly("file2.txt")
        Truth.assertThat(java.util.Arrays.asList<String?>(*java.io.File(root + "/longlinkpath/c").list()))
            .containsExactly("file2.txt")
        Truth.assertThat(java.util.Arrays.asList<String?>(*java.io.File(root + "/abbreviated/a").list()))
            .containsExactly("file1.txt")
        Truth.assertThat(java.util.Arrays.asList<String?>(*java.io.File(root + "/abbreviated/b").list()))
            .containsExactly("file2.txt")
        Truth.assertThat(java.util.Arrays.asList<String?>(*java.io.File(root + "/abbreviated/c").list()))
            .containsExactly("file2.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIsJunctionIsTrueForDanglingJunction() {
        val helloPath: Path = testUtil.scratchFile("target\\hello.txt", "hello")
        testUtil.createJunctions(com.google.common.collect.ImmutableMap.of<String?, String?>("link", "target"))

        val linkPath: java.io.File = java.io.File(helloPath.getParent().getParent().toFile(), "link")
        Truth.assertThat(java.util.Arrays.asList<String?>(*linkPath.list())).containsExactly("hello.txt")
        assertThat(WindowsFileSystem.isSymlinkOrJunction(linkPath.toPath())).isTrue()

        Truth.assertThat(helloPath.toFile().delete()).isTrue()
        Truth.assertThat(helloPath.getParent().toFile().delete()).isTrue()
        Truth.assertThat(helloPath.getParent().toFile().exists()).isFalse()
        Truth.assertThat(java.util.Arrays.asList<String?>(*linkPath.getParentFile().list())).containsExactly("link")

        assertThat(WindowsFileSystem.isSymlinkOrJunction(linkPath.toPath())).isTrue()
        Truth.assertThat(
            java.nio.file.Files.exists(
                linkPath.toPath(), WindowsFileSystem.symlinkOpts( /* followSymlinks */false)
            )
        )
            .isTrue()
        Truth.assertThat(
            java.nio.file.Files.exists(
                linkPath.toPath(), WindowsFileSystem.symlinkOpts( /* followSymlinks */true)
            )
        )
            .isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIsJunctionHandlesFilesystemChangesCorrectly() {
        val longPath: java.io.File =
            testUtil.scratchFile("target\\helloworld.txt", "hello").toAbsolutePath().toFile()
        val shortPath: java.io.File = java.io.File(longPath.getParentFile(), "hellow~1.txt")
        assertThat(WindowsFileSystem.isSymlinkOrJunction(longPath.toPath())).isFalse()
        assertThat(WindowsFileSystem.isSymlinkOrJunction(shortPath.toPath())).isFalse()

        Truth.assertThat(longPath.delete()).isTrue()
        testUtil.createJunctions(
            com.google.common.collect.ImmutableMap.of<String?, String?>(
                "target\\helloworld.txt",
                "target"
            )
        )
        assertThat(WindowsFileSystem.isSymlinkOrJunction(longPath.toPath())).isTrue()
        assertThat(WindowsFileSystem.isSymlinkOrJunction(shortPath.toPath())).isTrue()

        Truth.assertThat(longPath.delete()).isTrue()
        Truth.assertThat(longPath.mkdir()).isTrue()
        assertThat(WindowsFileSystem.isSymlinkOrJunction(longPath.toPath())).isFalse()
        assertThat(WindowsFileSystem.isSymlinkOrJunction(shortPath.toPath())).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testShortPathResolution() {
        val shortPath = "shortp~1.res/foo/withsp~1/bar/~witht~1/hello.txt"
        val longPath = "shortpath.resolution/foo/with spaces/bar/~with tilde/hello.txt"
        testUtil.scratchFile(longPath, "hello")
        val p: Path = scratchRoot.getRelative(shortPath)
        assertThat(p.getPathString()).endsWith(longPath)
        assertThat(p).isEqualTo(scratchRoot.getRelative(shortPath))
        assertThat(p).isEqualTo(scratchRoot.getRelative(longPath))
        assertThat(scratchRoot.getRelative(shortPath)).isEqualTo(p)
        assertThat(scratchRoot.getRelative(longPath)).isEqualTo(p)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUnresolvableShortPathWhichIsThenCreated() {
        val shortPath = "unreso~1.sho/foo/will~1.exi/bar/hello.txt"
        val longPath = "unresolvable.shortpath/foo/will.exist/bar/hello.txt"
        // Assert that we can create an unresolvable path.
        val p: Path = scratchRoot.getRelative(shortPath)
        assertThat(p.getPathString()).endsWith(shortPath)
        // Assert that we can then create the whole path, and can now resolve the short form.
        testUtil.scratchFile(longPath, "hello")
        val q: Path = scratchRoot.getRelative(shortPath)
        assertThat(q.getPathString()).endsWith(longPath)
        assertThat(p).isNotEqualTo(q)
    }

    /**
     * Test the scenario when a short path resolves to different long ones over time.
     * 
     * 
     * This can happen if the user deletes a directory during the bazel server's lifetime, then
     * recreates it with the same name prefix such that the resulting directory's 8dot3 name is the
     * same as the old one's.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testShortPathResolvesToDifferentPathsOverTime() {
        val p1: Path = scratchRoot.getRelative("longpa~1")
        val p2: Path? = scratchRoot.getRelative("longpa~1")
        assertThat(p1.exists()).isFalse()
        assertThat(p1).isEqualTo(p2)

        testUtil.scratchDir("longpathnow")
        val q1: Path = scratchRoot.getRelative("longpa~1")
        assertThat(q1.exists()).isTrue()
        assertThat(q1).isEqualTo(scratchRoot.getRelative("longpathnow"))

        // Delete the original resolution of "longpa~1" ("longpathnow").
        assertThat(q1.delete()).isTrue()
        assertThat(q1.exists()).isFalse()

        // Create a directory whose 8dot3 name is also "longpa~1" but its long name is different.
        testUtil.scratchDir("longpaththen")
        val r1: Path = scratchRoot.getRelative("longpa~1")
        assertThat(r1.exists()).isTrue()
        assertThat(r1).isEqualTo(scratchRoot.getRelative("longpaththen"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicLinkToExistingFile(@TestParameter targetType: SymlinkTargetType?) {
        val linkPath: Path = scratchRoot.getRelative("link")
        val targetPath: Path = scratchRoot.getRelative("target")
        assertThat(targetPath.getParentDirectory().exists()).isTrue()
        assertThat(targetPath.getParentDirectory().isDirectory()).isTrue()
        FileSystemUtils.writeContentAsLatin1(targetPath, "hello")

        linkPath.createSymbolicLink(targetPath, targetType)

        if (createSymbolicLinks) {
            assertThat(linkPath.isSymbolicLink()).isTrue()
            assertThat(linkPath.readSymbolicLink()).isEqualTo(targetPath.asFragment())
        } else {
            assertThat(linkPath.isSymbolicLink()).isFalse()
            org.junit.Assert.assertThrows<T?>(
                NotASymlinkException::class.java,
                org.junit.function.ThrowingRunnable { linkPath.readSymbolicLink() })
        }
        assertThat(linkPath.exists()).isTrue()
        assertThat(linkPath.isFile()).isTrue()
        assertThat(
            FileSystemUtils.readContent(
                linkPath,
                java.nio.charset.StandardCharsets.ISO_8859_1
            )
        ).isEqualTo("hello")

        linkPath.delete()
        assertThat(linkPath.exists()).isFalse()
        assertThat(targetPath.exists()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicLinkToExistingDirectory(@TestParameter targetType: SymlinkTargetType?) {
        val linkPath: Path = scratchRoot.getRelative("link")
        val linkChildPath: Path = linkPath.getRelative("hello.txt")
        val targetPath: Path = scratchRoot.getRelative("target")
        val targetChildPath: Path? = targetPath.getRelative("hello.txt")
        targetPath.createDirectory()
        FileSystemUtils.writeContentAsLatin1(targetChildPath, "hello")

        linkPath.createSymbolicLink(targetPath, targetType)

        assertThat(linkPath.isSymbolicLink()).isTrue()
        assertThat(linkPath.readSymbolicLink()).isEqualTo(targetPath.asFragment())
        assertThat(linkPath.exists()).isTrue()
        assertThat(linkPath.isDirectory()).isTrue()
        assertThat(linkChildPath.exists()).isTrue()
        assertThat(linkChildPath.isFile()).isTrue()
        assertThat(
            FileSystemUtils.readContent(
                linkChildPath,
                java.nio.charset.StandardCharsets.ISO_8859_1
            )
        ).isEqualTo("hello")

        linkPath.delete()
        assertThat(linkPath.exists()).isFalse()
        assertThat(targetPath.exists()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateSymbolicLinkToNonExistingTargetOfUnspecifiedType() {
        val linkPath: Path = scratchRoot.getRelative("link")
        val targetPath: Path = scratchRoot.getRelative("target")

        linkPath.createSymbolicLink(targetPath, SymlinkTargetType.UNSPECIFIED)

        assertThat(linkPath.isSymbolicLink()).isTrue()
        assertThat(linkPath.readSymbolicLink()).isEqualTo(targetPath.asFragment())
        assertThat(linkPath.exists()).isFalse()

        // Check that a dangling symlink is preferred over a dangling junction when supported.
        // Do this by creating a target of the corresponding type and verifying that it can be accessed.
        if (createSymbolicLinks) {
            FileSystemUtils.writeContentAsLatin1(targetPath, "hello")
            assertThat(linkPath.exists()).isTrue()
            assertThat(linkPath.isFile()).isTrue()
            assertThat(
                FileSystemUtils.readContent(
                    linkPath,
                    java.nio.charset.StandardCharsets.ISO_8859_1
                )
            ).isEqualTo("hello")
        } else {
            targetPath.createDirectory()
            assertThat(linkPath.exists()).isTrue()
            assertThat(linkPath.isDirectory()).isTrue()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateSymbolicLinkToNonExistingTargetOfFileType() {
        // This is only expected to work if symlinks are enabled.
        // Otherwise, our only recourse is to create a dangling junction, which does not work for files.
        Assume.assumeTrue(createSymbolicLinks)

        val linkPath: Path = scratchRoot.getRelative("link")
        val targetPath: Path = scratchRoot.getRelative("target")

        linkPath.createSymbolicLink(targetPath, SymlinkTargetType.FILE)

        assertThat(linkPath.isSymbolicLink()).isTrue()
        assertThat(linkPath.readSymbolicLink()).isEqualTo(targetPath.asFragment())

        FileSystemUtils.writeContentAsLatin1(targetPath, "hello")

        assertThat(linkPath.exists()).isTrue()
        assertThat(linkPath.isFile()).isTrue()
        assertThat(
            FileSystemUtils.readContent(
                linkPath,
                java.nio.charset.StandardCharsets.ISO_8859_1
            )
        ).isEqualTo("hello")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateSymbolicLinkToNonExistingTargetOfDirectoryType() {
        val linkPath: Path = scratchRoot.getRelative("link")
        val linkChildPath: Path = linkPath.getRelative("hello.txt")
        val targetPath: Path = scratchRoot.getRelative("target")
        val targetChildPath: Path? = targetPath.getRelative("hello.txt")

        linkPath.createSymbolicLink(targetPath, SymlinkTargetType.DIRECTORY)

        assertThat(linkPath.isSymbolicLink()).isTrue()
        assertThat(linkPath.readSymbolicLink()).isEqualTo(targetPath.asFragment())

        targetPath.createDirectory()
        FileSystemUtils.writeContentAsLatin1(targetChildPath, "hello")

        assertThat(linkPath.exists()).isTrue()
        assertThat(linkPath.isDirectory()).isTrue()
        assertThat(linkChildPath.exists()).isTrue()
        assertThat(linkChildPath.isFile()).isTrue()
        assertThat(
            FileSystemUtils.readContent(
                linkChildPath,
                java.nio.charset.StandardCharsets.ISO_8859_1
            )
        ).isEqualTo("hello")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReadSymbolicLinkForFile() {
        val filePath: Path = scratchRoot.getRelative("file")
        FileSystemUtils.writeContentAsLatin1(filePath, "hello")

        org.junit.Assert.assertThrows<T?>(NotASymlinkException::class.java, filePath::readSymbolicLink)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReadSymbolicLinkForDirectory() {
        val dirPath: Path = scratchRoot.getRelative("dir")
        dirPath.createDirectory()

        org.junit.Assert.assertThrows<T?>(NotASymlinkException::class.java, dirPath::readSymbolicLink)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReadSymbolicLinkForNonexistentPath() {
        val nonexistentPath: Path = scratchRoot.getRelative("nonexistent")

        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            nonexistentPath::readSymbolicLink
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReadOnlyAttribute() {
        testUtil.scratchFile("dir\\hello.txt", "hello")
        testUtil.createJunctions(com.google.common.collect.ImmutableMap.of<String?, String?>("junc", "dir"))

        val dir: Path = testUtil.createVfsPath(fs, "dir")
        val file: Path = testUtil.createVfsPath(fs, "dir\\hello.txt")
        val dirViaJunction: Path = testUtil.createVfsPath(fs, "junc")
        val fileViaJunction: Path = testUtil.createVfsPath(fs, "junc\\hello.txt")

        assertWritable(dir)
        dir.setWritable(false) // no-op
        assertWritable(dir)
        dir.setWritable(true) // no-op
        assertWritable(dir)

        assertWritable(dirViaJunction)
        dirViaJunction.setWritable(false) // no-op
        assertWritable(dirViaJunction)
        dirViaJunction.setWritable(true) // no-op
        assertWritable(dirViaJunction)

        assertWritable(file)
        file.setWritable(false)
        assertNotWritable(file)
        file.setWritable(true)
        assertWritable(file)

        assertThat(fileViaJunction.isWritable()).isTrue()
        fileViaJunction.setWritable(false)
        assertNotWritable(fileViaJunction)
        fileViaJunction.setWritable(true)
        assertWritable(fileViaJunction)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTypeViaReaddirCache(
        @TestParameter(
            "BUILD", "Å", "K", "Ａ", "ａ", "０", " 𝐀", "𝐴", "𝒜", "Ⅳ", "Ⓑ", "ẞ", "ß", "Ä", "İ", "ı"
        ) entry: String
    ) {
        val normalizedEntry: String =
            java.text.Normalizer.normalize(entry, java.text.Normalizer.Form.NFC)
                .uppercase()
                .lowercase()
        validateGetTypeConsistency(scratchRoot, entry, normalizedEntry)
        validateGetTypeConsistency(scratchRoot, normalizedEntry, entry)
    }

    @Throws(IOException::class)
    private fun validateGetTypeConsistency(baseDir: Path, entryToCreate: String, entryToCheck: String) {
        baseDir.createDirectoryAndParents()
        val dir: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            baseDir.createTempDirectory("readdir_cache-")
        val pathToCreate: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            dir.getChild(StringEncoding.unicodeToInternal(entryToCreate))
        FileSystemUtils.createEmptyFile(pathToCreate)

        val syscallCache: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            DefaultSyscallCache.newBuilder().build()
        // Prime the cache by reading the parent directory.
        syscallCache.readdir(dir)
        Truth.assertWithMessage("expecting entry %s to exist", entryToCreate)
            .that(syscallCache.getType(pathToCreate, Symlinks.FOLLOW))
            .isNotNull()

        val pathToCheck: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            dir.getChild(StringEncoding.unicodeToInternal(entryToCheck))
        val existsWithCache = syscallCache.getType(pathToCheck, Symlinks.FOLLOW) != null
        val existsWithoutCache = pathToCheck.statIfFound() != null
        Truth.assertWithMessage("created : %s", entryToCreate)
            .withMessage("checking: %s", entryToCheck)
            .withMessage("with cache: %s", existsWithCache)
            .withMessage("w/o cache : %s", existsWithoutCache)
            .that(existsWithCache)
            .isEqualTo(existsWithoutCache)
    }

    companion object {
        @Throws(java.lang.Exception::class)
        private fun assertWritable(path: Path) {
            assertThat(path.isWritable()).isTrue()
            assertThat(path.stat().getPermissions()).isEqualTo(493)
        }

        @Throws(java.lang.Exception::class)
        private fun assertNotWritable(path: Path) {
            assertThat(path.isWritable()).isFalse()
            assertThat(path.stat().getPermissions()).isEqualTo(365)
        }
    }
}
