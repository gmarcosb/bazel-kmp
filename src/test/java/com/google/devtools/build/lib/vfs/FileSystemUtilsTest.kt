// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.vfs

import com.google.devtools.build.lib.vfs.FileSystemUtils.appendWithoutExtension
import com.google.devtools.build.lib.vfs.FileSystemUtilsTest.Companion.assertPath

/** This class tests the file system utilities.  */
@RunWith(TestParameterInjector::class)
class FileSystemUtilsTest {
    private var clock: com.google.devtools.build.lib.testutil.ManualClock? = null
    private var fileSystem: FileSystem? = null
    private var workingDir: Path? = null

    internal enum class FileType {
        FILE,
        DIRECTORY,
        SYMLINK
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun initializeFileSystem() {
        clock = com.google.devtools.build.lib.testutil.ManualClock()
        fileSystem = InMemoryFileSystem(clock, DigestHashFunction.SHA256)
        workingDir = fileSystem.getPath("/workingDir")
        workingDir.createDirectory()
    }

    var topDir: Path? = null
    var file1: Path? = null
    var file2: Path? = null
    var aDir: Path? = null
    var bDir: Path? = null
    var file3: Path? = null
    var innerDir: Path? = null
    var link1: Path? = null
    var dirLink: Path? = null
    var file4: Path? = null
    var file5: Path? = null

    /*
   * Build a directory tree that looks like:
   *   top-dir/
   *     file-1
   *     file-2
   *     a-dir/
   *       file-3
   *       inner-dir/
   *         link-1 => file-4
   *         dir-link => b-dir
   *   file-4
   */
    @Throws(IOException::class)
    private fun createTestDirectoryTree() {
        topDir = fileSystem.getPath("/top-dir")
        file1 = fileSystem.getPath("/top-dir/file-1")
        file2 = fileSystem.getPath("/top-dir/file-2")
        aDir = fileSystem.getPath("/top-dir/a-dir")
        bDir = fileSystem.getPath("/top-dir/b-dir")
        file3 = fileSystem.getPath("/top-dir/a-dir/file-3")
        innerDir = fileSystem.getPath("/top-dir/a-dir/inner-dir")
        link1 = fileSystem.getPath("/top-dir/a-dir/inner-dir/link-1")
        dirLink = fileSystem.getPath("/top-dir/a-dir/inner-dir/dir-link")
        file4 = fileSystem.getPath("/file-4")
        file5 = fileSystem.getPath("/top-dir/b-dir/file-5")

        topDir.createDirectory()
        FileSystemUtils.createEmptyFile(file1)
        FileSystemUtils.createEmptyFile(file2)
        aDir.createDirectory()
        bDir.createDirectory()
        FileSystemUtils.createEmptyFile(file3)
        innerDir.createDirectory()
        link1.createSymbolicLink(file4) // simple symlink
        dirLink.createSymbolicLink(bDir)
        FileSystemUtils.createEmptyFile(file4)
        FileSystemUtils.createEmptyFile(file5)
    }

    @Throws(IOException::class)
    private fun checkTestDirectoryTreesBelow(toPath: Path) {
        val copiedFile1: Path = toPath.getChild("file-1")
        assertThat(copiedFile1.exists()).isTrue()
        assertThat(copiedFile1.isFile()).isTrue()

        val copiedFile2: Path = toPath.getChild("file-2")
        assertThat(copiedFile2.exists()).isTrue()
        assertThat(copiedFile2.isFile()).isTrue()

        val copiedADir: Path = toPath.getChild("a-dir")
        assertThat(copiedADir.exists()).isTrue()
        assertThat(copiedADir.isDirectory()).isTrue()
        val aDirEntries: MutableCollection<Path?>? = copiedADir.getDirectoryEntries()
        Truth.assertThat(aDirEntries).hasSize(2)

        val copiedFile3: Path = copiedADir.getChild("file-3")
        assertThat(copiedFile3.exists()).isTrue()
        assertThat(copiedFile3.isFile()).isTrue()

        val copiedInnerDir: Path = copiedADir.getChild("inner-dir")
        assertThat(copiedInnerDir.exists()).isTrue()
        assertThat(copiedInnerDir.isDirectory()).isTrue()

        val copiedLink1: Path = copiedInnerDir.getChild("link-1")
        assertThat(copiedLink1.exists()).isTrue()
        assertThat(copiedLink1.isSymbolicLink()).isTrue()

        val copiedDirLink: Path = copiedInnerDir.getChild("dir-link")
        assertThat(copiedDirLink.exists()).isTrue()
        assertThat(copiedDirLink.isSymbolicLink()).isTrue()
    }

    // tests
    @org.junit.Test
    @Throws(IOException::class)
    fun testChangeModtime() {
        val file: Path = fileSystem.getPath("/my-file")
        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable { BlazeTestUtils.changeModtime(file) })
        FileSystemUtils.createEmptyFile(file)
        val prevMtime: Long = file.getLastModifiedTime()
        BlazeTestUtils.changeModtime(file)
        Truth.assertThat(prevMtime == file.getLastModifiedTime()).isFalse()
    }

    @org.junit.Test
    fun testCommonAncestor() {
        assertThat(commonAncestor(topDir, topDir)).isEqualTo(topDir)
        assertThat(commonAncestor(file1, file3)).isEqualTo(topDir)
        assertThat(commonAncestor(file1, dirLink)).isEqualTo(topDir)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testRelativePath() {
        createTestDirectoryTree()
        assertThat(
            relativePath(PathFragment.create("/top-dir"), PathFragment.create("/top-dir/file-1"))
                .getPathString()
        )
            .isEqualTo("file-1")
        assertThat(
            relativePath(PathFragment.create("/top-dir"), PathFragment.create("/top-dir"))
                .getPathString()
        )
            .isEqualTo("")
        assertThat(
            relativePath(
                PathFragment.create("/top-dir"),
                PathFragment.create("/top-dir/a-dir/inner-dir/dir-link")
            )
                .getPathString()
        )
            .isEqualTo("a-dir/inner-dir/dir-link")
        assertThat(
            relativePath(PathFragment.create("/top-dir"), PathFragment.create("/file-4"))
                .getPathString()
        )
            .isEqualTo("../file-4")
        assertThat(
            relativePath(
                PathFragment.create("/top-dir/a-dir/inner-dir"), PathFragment.create("/file-4")
            )
                .getPathString()
        )
            .isEqualTo("../../../file-4")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRemoveExtension_strings() {
        assertThat(removeExtension("foo.c")).isEqualTo("foo")
        assertThat(removeExtension("a/foo.c")).isEqualTo("a/foo")
        assertThat(removeExtension("a.b/foo")).isEqualTo("a.b/foo")
        assertThat(removeExtension("foo")).isEqualTo("foo")
        assertThat(removeExtension("foo.")).isEqualTo("foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRemoveExtension_paths() {
        assertPath("/foo", removeExtension(fileSystem.getPath("/foo.c")))
        assertPath("/a/foo", removeExtension(fileSystem.getPath("/a/foo.c")))
        assertPath("/a.b/foo", removeExtension(fileSystem.getPath("/a.b/foo")))
        assertPath("/foo", removeExtension(fileSystem.getPath("/foo")))
        assertPath("/foo", removeExtension(fileSystem.getPath("/foo.")))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReplaceExtension_path() {
        assertPath(
            "/foo/bar.baz",
            FileSystemUtils.replaceExtension(fileSystem.getPath("/foo/bar"), ".baz")
        )
        assertPath(
            "/foo/bar.baz",
            FileSystemUtils.replaceExtension(fileSystem.getPath("/foo/bar.cc"), ".baz")
        )
        assertPath("/foo.baz", FileSystemUtils.replaceExtension(fileSystem.getPath("/foo/"), ".baz"))
        assertPath(
            "/foo.baz",
            FileSystemUtils.replaceExtension(fileSystem.getPath("/foo.cc/"), ".baz")
        )
        assertPath("/foo.baz", FileSystemUtils.replaceExtension(fileSystem.getPath("/foo"), ".baz"))
        assertPath("/foo.baz", FileSystemUtils.replaceExtension(fileSystem.getPath("/foo.cc"), ".baz"))
        assertPath("/.baz", FileSystemUtils.replaceExtension(fileSystem.getPath("/.cc"), ".baz"))
        assertThat(FileSystemUtils.replaceExtension(fileSystem.getPath("/"), ".baz")).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReplaceExtension_pathFragment() {
        assertPath(
            "foo/bar.baz",
            FileSystemUtils.replaceExtension(PathFragment.create("foo/bar"), ".baz")
        )
        assertPath(
            "foo/bar.baz",
            FileSystemUtils.replaceExtension(PathFragment.create("foo/bar.cc"), ".baz")
        )
        assertPath(
            "/foo/bar.baz",
            FileSystemUtils.replaceExtension(PathFragment.create("/foo/bar"), ".baz")
        )
        assertPath(
            "/foo/bar.baz",
            FileSystemUtils.replaceExtension(PathFragment.create("/foo/bar.cc"), ".baz")
        )
        assertPath("foo.baz", FileSystemUtils.replaceExtension(PathFragment.create("foo/"), ".baz"))
        assertPath("foo.baz", FileSystemUtils.replaceExtension(PathFragment.create("foo.cc/"), ".baz"))
        assertPath("/foo.baz", FileSystemUtils.replaceExtension(PathFragment.create("/foo/"), ".baz"))
        assertPath(
            "/foo.baz",
            FileSystemUtils.replaceExtension(PathFragment.create("/foo.cc/"), ".baz")
        )
        assertPath("foo.baz", FileSystemUtils.replaceExtension(PathFragment.create("foo"), ".baz"))
        assertPath("foo.baz", FileSystemUtils.replaceExtension(PathFragment.create("foo.cc"), ".baz"))
        assertPath("/foo.baz", FileSystemUtils.replaceExtension(PathFragment.create("/foo"), ".baz"))
        assertPath(
            "/foo.baz",
            FileSystemUtils.replaceExtension(PathFragment.create("/foo.cc"), ".baz")
        )
        assertPath(".baz", FileSystemUtils.replaceExtension(PathFragment.create(".cc"), ".baz"))
        assertThat(FileSystemUtils.replaceExtension(PathFragment.create("/"), ".baz")).isNull()
        assertThat(FileSystemUtils.replaceExtension(PathFragment.create(""), ".baz")).isNull()
        assertPath(
            "foo/bar.baz",
            FileSystemUtils.replaceExtension(PathFragment.create("foo/bar.pony"), ".baz", ".pony")
        )
        assertPath(
            "foo/bar.baz",
            FileSystemUtils.replaceExtension(PathFragment.create("foo/bar"), ".baz", "")
        )
        assertThat(FileSystemUtils.replaceExtension(PathFragment.create(""), ".baz", ".pony")).isNull()
        assertThat(
            FileSystemUtils.replaceExtension(
                PathFragment.create("foo/bar.pony"), ".baz", ".unicorn"
            )
        )
            .isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAppendWithoutExtension() {
        assertPath(
            "libfoo-src.jar",
            appendWithoutExtension(PathFragment.create("libfoo.jar"), "-src")
        )
        assertPath(
            "foo/libfoo-src.jar",
            appendWithoutExtension(PathFragment.create("foo/libfoo.jar"), "-src")
        )
        assertPath(
            "java/com/google/foo/libfoo-src.jar",
            appendWithoutExtension(PathFragment.create("java/com/google/foo/libfoo.jar"), "-src")
        )
        assertPath(
            "libfoo.bar-src.jar",
            appendWithoutExtension(PathFragment.create("libfoo.bar.jar"), "-src")
        )
        assertPath(
            "libfoo-src",
            appendWithoutExtension(PathFragment.create("libfoo"), "-src")
        )
        assertPath(
            "libfoo-src.jar",
            appendWithoutExtension(PathFragment.create("libfoo.jar/"), "-src")
        )
        assertPath(
            "libfoo.src.jar",
            appendWithoutExtension(PathFragment.create("libfoo.jar"), ".src")
        )
        assertThat(appendWithoutExtension(PathFragment.create("/"), "-src")).isNull()
        assertThat(appendWithoutExtension(PathFragment.create(""), "-src")).isNull()
    }

    @org.junit.Test
    fun testGetWorkingDirectory() {
        val userDir: String? = java.lang.System.getProperty("user.dir")

        assertThat(fileSystem.getPath(java.lang.System.getProperty("user.dir", "/")))
            .isEqualTo(FileSystemUtils.getWorkingDirectory(fileSystem))

        java.lang.System.setProperty("user.dir", "/blah/blah/blah")
        assertThat(fileSystem.getPath("/blah/blah/blah"))
            .isEqualTo(FileSystemUtils.getWorkingDirectory(fileSystem))

        java.lang.System.setProperty("user.dir", userDir)
    }

    @org.junit.Test
    fun testResolveRelativeToFilesystemWorkingDir() {
        val relativePath: PathFragment? = PathFragment.create("relative/path")
        assertThat(workingDir.getRelative(relativePath))
            .isEqualTo(workingDir.getRelative(relativePath))

        val absolutePath: PathFragment? = PathFragment.create("/absolute/path")
        assertThat(workingDir.getRelative(absolutePath)).isEqualTo(fileSystem.getPath(absolutePath))
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testTouchFileCreatesFile() {
        createTestDirectoryTree()
        val nonExistingFile: Path = fileSystem.getPath("/previously-non-existing")
        assertThat(nonExistingFile.exists()).isFalse()
        touchFile(nonExistingFile)

        assertThat(nonExistingFile.exists()).isTrue()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testTouchFileAdjustsFileTime() {
        createTestDirectoryTree()
        val testFile: Path = file4
        val oldTime: Long = testFile.getLastModifiedTime()
        testFile.setLastModifiedTime(42)
        touchFile(testFile)

        assertThat(testFile.getLastModifiedTime()).isAtLeast(oldTime)
    }

    // The kind of filesystem to use for the copyFile tests.
    // This is required to test both the fast and slow paths.
    internal enum class CopyFileSystem {
        IN_MEMORY,
        ON_DISK;

        @Throws(IOException::class)
        fun root(): Path {
            return when (this) {
                CopyFileSystem.IN_MEMORY -> InMemoryFileSystem(DigestHashFunction.SHA256).getPath("/")
                CopyFileSystem.ON_DISK -> com.google.devtools.build.lib.testutil.TestUtils.createUniqueTmpDir(
                    com.google.devtools.build.lib.vfs.util.FileSystems.getNativeFileSystem(DigestHashFunction.SHA256)
                )
            }
        }
    }

    // The state of the source path for a copy or move operation.
    internal enum class SourceState {
        // The source path is a regular file.
        FILE,

        // The source path is a symbolic link to an existing file.
        SYMLINK;

        @Throws(IOException::class)
        fun create(source: Path, contents: String?, perms: Int, lastModifiedTime: Long) {
            when (this) {
                SourceState.FILE -> {
                    FileSystemUtils.writeContent(source, java.nio.charset.StandardCharsets.UTF_8, contents)
                    source.chmod(perms)
                    source.setLastModifiedTime(lastModifiedTime)
                }

                SourceState.SYMLINK -> {
                    val actualSource: Path = source.getParentDirectory().getChild("actual-source")
                    source.createSymbolicLink(actualSource.asFragment())
                    FileSystemUtils.writeContent(actualSource, java.nio.charset.StandardCharsets.UTF_8, contents)
                    actualSource.chmod(perms)
                    actualSource.setLastModifiedTime(lastModifiedTime)
                }
            }
        }
    }

    // The state of the target path for a copy or move operation.
    internal enum class TargetState {
        // The target path does not exist.
        MISSING,

        // The target path is a regular file.
        FILE,

        // The target path is a symbolic link to an existing file.
        SYMLINK,

        // The target path is a dangling symbolic link.
        DANGLING_SYMLINK;

        @Throws(IOException::class)
        fun create(target: Path, contents: String?) {
            when (this) {
                TargetState.MISSING -> {}
                TargetState.FILE -> FileSystemUtils.writeContentAsLatin1(target, contents)
                TargetState.SYMLINK -> {
                    val actualTarget: Path = target.getParentDirectory().getChild("actual-target")
                    target.createSymbolicLink(actualTarget.asFragment())
                    FileSystemUtils.writeContent(actualTarget, java.nio.charset.StandardCharsets.UTF_8, contents)
                }

                TargetState.DANGLING_SYMLINK -> target.createSymbolicLink(PathFragment.create("actual-target"))
            }
        }
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testCopyFile(
        @TestParameter fs: CopyFileSystem,
        @TestParameter sourceState: SourceState,
        @TestParameter targetState: TargetState
    ) {
        val root: Path = fs.root()
        val source: Path = root.getRelative("source")
        val target: Path = root.getRelative("target")

        sourceState.create(source, "hello world", 365, 12345L)

        targetState.create(target, "bad contents")

        copyFile(source, target)

        assertThat(target.isFile(Symlinks.NOFOLLOW)).isTrue()
        assertThat(
            FileSystemUtils.readContent(
                target,
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).isEqualTo("hello world")
        assertThat(target.stat().getPermissions()).isEqualTo(365)
        assertThat(target.getLastModifiedTime()).isEqualTo(12345L)
        assertThat(source.exists()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCopyFileWithSourceDirectoryFails(@TestParameter fs: CopyFileSystem) {
        val root: Path = fs.root()
        val source: Path = root.getRelative("source")
        val target: Path? = root.getRelative("target")

        source.createDirectory()

        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { copyFile(source, target) })
        Truth.assertThat(e).hasMessageThat().contains("don't know how to copy")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCopyFileIntoUnwritableDirectoryFails(@TestParameter fs: CopyFileSystem) {
        // Windows has no concept of read-only directories.
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.WINDOWS)

        val root: Path = fs.root()
        val source: Path? = root.getRelative("source")
        val target: Path = root.getRelative("subdir/target")

        FileSystemUtils.createEmptyFile(source)
        target.getParentDirectory().createDirectory()
        FileSystemUtils.createEmptyFile(target)
        target.getParentDirectory().setWritable(false)

        org.junit.Assert.assertThrows<T?>(
            FileAccessException::class.java,
            org.junit.function.ThrowingRunnable { copyFile(source, target) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCopyFileOntoNonEmptyDirectoryFails(@TestParameter fs: CopyFileSystem) {
        val root: Path = fs.root()
        val source: Path? = root.getRelative("source")
        val target: Path = root.getRelative("target")

        FileSystemUtils.createEmptyFile(source)
        target.createDirectory()
        FileSystemUtils.createEmptyFile(target.getChild("foo"))

        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { copyFile(source, target) })
        Truth.assertThat(e).hasMessageThat().contains("(Directory not empty)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCopyFileWithNonExistingSourceFails(@TestParameter fs: CopyFileSystem) {
        val root: Path = fs.root()
        val source: Path? = root.getRelative("/source")
        val target: Path? = root.getRelative("/target")

        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable { copyFile(source, target) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCopyFileWithDanglingSymlinkSourceFails(@TestParameter fs: CopyFileSystem) {
        val root: Path = fs.root()
        val source: Path = root.getRelative("source")
        val target: Path? = root.getRelative("target")

        source.createSymbolicLink(PathFragment.create("does-not-exist"))

        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable { copyFile(source, target) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCopyFileWithNonExistingTargetParentFails(@TestParameter fs: CopyFileSystem) {
        val root: Path = fs.root()
        val source: Path? = root.getRelative("source")
        val target: Path? = root.getRelative("subdir/target")

        FileSystemUtils.createEmptyFile(source)

        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable { copyFile(source, target) })
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testMoveFileSameDevice(@TestParameter targetState: TargetState) {
        val source: Path = fileSystem.getPath("/source")
        val target: Path = fileSystem.getPath("/target")
        FileSystemUtils.writeContent(source, java.nio.charset.StandardCharsets.UTF_8, "hello world")

        targetState.create(target, "bad contents")

        assertThat(moveFile(source, target)).isEqualTo(MoveResult.FILE_MOVED)

        // TODO(tjgq): Check that the inode number did not change.
        assertThat(target.isFile(Symlinks.NOFOLLOW)).isTrue()
        assertThat(
            FileSystemUtils.readContent(
                target,
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).isEqualTo("hello world")
        assertThat(source.exists()).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testMoveSymbolicLinkSameDevice(@TestParameter targetState: TargetState) {
        val source: Path = fileSystem.getPath("/source")
        val target: Path = fileSystem.getPath("/target")

        source.createSymbolicLink(PathFragment.create("/some/path"))
        targetState.create(target, "bad contents")

        moveFile(source, target)

        // TODO(tjgq): Check that the inode number did not change.
        assertThat(target.isSymbolicLink()).isTrue()
        assertThat(target.readSymbolicLink()).isEqualTo(PathFragment.create("/some/path"))
        assertThat(source.exists(Symlinks.NOFOLLOW)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMoveFileAcrossDevices(@TestParameter targetState: TargetState) {
        val fs: FileSystem = MultipleDeviceFS()
        val source: Path = fs.getPath("/fs1/source")
        source.getParentDirectory().createDirectoryAndParents()
        val target: Path = fs.getPath("/fs2/target")
        target.getParentDirectory().createDirectoryAndParents()

        FileSystemUtils.writeContent(source, java.nio.charset.StandardCharsets.UTF_8, "hello world")
        source.chmod(365)
        source.setLastModifiedTime(12345L)

        targetState.create(target, "bad contents")

        assertThat(FileSystemUtils.moveFile(source, target)).isEqualTo(MoveResult.FILE_COPIED)

        assertThat(target.isFile(Symlinks.NOFOLLOW)).isTrue()
        assertThat(
            FileSystemUtils.readContent(
                target,
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).isEqualTo("hello world")
        assertThat(target.stat().getPermissions()).isEqualTo(365)
        assertThat(target.getLastModifiedTime()).isEqualTo(12345L)
        assertThat(source.exists()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMoveSymlinkAcrossDevices(@TestParameter targetState: TargetState) {
        val fs: FileSystem = MultipleDeviceFS()
        val source: Path = fs.getPath("/fs1/source")
        source.getParentDirectory().createDirectoryAndParents()
        val target: Path = fs.getPath("/fs2/target")
        target.getParentDirectory().createDirectoryAndParents()

        source.createSymbolicLink(PathFragment.create("/some/path"))

        targetState.create(target, "bad contents")

        moveFile(source, target)

        assertThat(target.isSymbolicLink()).isTrue()
        assertThat(target.readSymbolicLink()).isEqualTo(PathFragment.create("/some/path"))
        assertThat(source.exists(Symlinks.NOFOLLOW)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMoveFileFromNonWritableDirectoryFails() {
        // Windows has no concept of read-only directories.
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.WINDOWS)

        val source: Path = fileSystem.getPath("/source")
        val target: Path = fileSystem.getPath("/subdir/target")

        FileSystemUtils.createEmptyFile(source)
        target.getParentDirectory().createDirectory()
        source.getParentDirectory().setWritable(false)

        org.junit.Assert.assertThrows<T?>(
            FileAccessException::class.java,
            org.junit.function.ThrowingRunnable { moveFile(source, target) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMoveFileIntoNonWritableDirectoryFails() {
        // Windows has no concept of read-only directories.
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.WINDOWS)

        val source: Path? = fileSystem.getPath("/source")
        val target: Path = fileSystem.getPath("/subdir/target")

        FileSystemUtils.createEmptyFile(source)
        target.getParentDirectory().createDirectory()
        target.getParentDirectory().setWritable(false)

        org.junit.Assert.assertThrows<T?>(
            FileAccessException::class.java,
            org.junit.function.ThrowingRunnable { moveFile(source, target) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMoveFileOntoNonEmptyDirectoryFails() {
        val source: Path? = fileSystem.getPath("/source")
        val target: Path = fileSystem.getPath("/target")

        FileSystemUtils.createEmptyFile(source)
        target.createDirectory()
        FileSystemUtils.createEmptyFile(target.getChild("foo"))

        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { moveFile(source, target) })
        Truth.assertThat(e).hasMessageThat().contains("(Directory not empty)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMoveFileWithNonExistingSourceFails() {
        val source: Path? = fileSystem.getPath("/source")
        val target: Path? = fileSystem.getPath("/target")

        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable { moveFile(source, target) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMoveFileWithNonExistingTargetParentFails() {
        val source: Path? = fileSystem.getPath("/source")
        val target: Path? = fileSystem.getPath("/subdir/target")

        FileSystemUtils.writeContent(source, java.nio.charset.StandardCharsets.UTF_8, "hello world")

        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable { moveFile(source, target) })
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testReadWithKnownFileSize() {
        createTestDirectoryTree()
        val str = "this is a test of readContentWithLimit method"
        FileSystemUtils.writeContent(file1, java.nio.charset.StandardCharsets.ISO_8859_1, str)

        assertThat(FileSystemUtils.readWithKnownFileSize(file1, str.length))
            .isEqualTo(str.toByteArray(java.nio.charset.StandardCharsets.ISO_8859_1))
        org.junit.Assert.assertThrows<T?>(
            FileSystemUtils.LongReadIOException::class.java,
            org.junit.function.ThrowingRunnable { FileSystemUtils.readWithKnownFileSize(file1, str.length - 1) })
        org.junit.Assert.assertThrows<T?>(
            FileSystemUtils.ShortReadIOException::class.java,
            org.junit.function.ThrowingRunnable { FileSystemUtils.readWithKnownFileSize(file1, str.length + 1) })
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testAppend() {
        createTestDirectoryTree()
        FileSystemUtils.writeIsoLatin1(file1, "nobody says ")
        FileSystemUtils.writeIsoLatin1(file1, "mary had")
        FileSystemUtils.appendIsoLatin1(file1, "a little lamb")
        Truth.assertThat(String(FileSystemUtils.readContentAsLatin1(file1)))
            .isEqualTo("mary had\na little lamb\n")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testCopyTreesBelow() {
        createTestDirectoryTree()
        val toPath: Path = fileSystem.getPath("/copy-here")
        toPath.createDirectory()

        FileSystemUtils.copyTreesBelow(topDir, toPath)
        checkTestDirectoryTreesBelow(toPath)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testCopyTreesBelowToSubtree() {
        createTestDirectoryTree()
        val expected: java.lang.IllegalArgumentException? =
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable { FileSystemUtils.copyTreesBelow(topDir, aDir) })
        Truth.assertThat(expected).hasMessageThat().isEqualTo("/top-dir/a-dir is a subdirectory of /top-dir")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testCopyFileAsDirectoryTree() {
        createTestDirectoryTree()
        val expected: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { FileSystemUtils.copyTreesBelow(file1, aDir) })
        Truth.assertThat(expected).hasMessageThat().isEqualTo("/top-dir/file-1 (Not a directory)")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testCopyTreesBelowToFile() {
        createTestDirectoryTree()
        val copyDir: Path? = fileSystem.getPath("/my-dir")
        val copySubDir: Path = fileSystem.getPath("/my-dir/subdir")
        copySubDir.createDirectoryAndParents()
        val expected: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { FileSystemUtils.copyTreesBelow(copyDir, file4) })
        Truth.assertThat(expected).hasMessageThat().isEqualTo("/file-4 (Not a directory)")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testCopyTreesBelowFromUnexistingDir() {
        createTestDirectoryTree()

        val unexistingDir: Path? = fileSystem.getPath("/unexisting-dir")
        val expected: FileNotFoundException? =
            org.junit.Assert.assertThrows<FileNotFoundException?>(
                FileNotFoundException::class.java,
                org.junit.function.ThrowingRunnable { FileSystemUtils.copyTreesBelow(unexistingDir, aDir) })
        Truth.assertThat(expected).hasMessageThat().isEqualTo("/unexisting-dir (No such file or directory)")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testTraverseTree() {
        createTestDirectoryTree()

        val paths: MutableCollection<Path?>? = traverseTree(topDir, { p -> !p.getPathString().contains("a-dir") })
        Truth.assertThat(paths).containsExactly(file1, file2, bDir, file5)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testTraverseTreeDeep() {
        createTestDirectoryTree()

        val paths: MutableCollection<Path?>? = traverseTree(topDir, { ignored -> true })
        Truth.assertThat(paths).containsExactly(
            aDir,
            file3,
            innerDir,
            link1,
            file1,
            file2,
            dirLink,
            bDir,
            file5
        )
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testTraverseTreeLinkDir() {
        // Use a new little tree for this test:
        //  top-dir/
        //    dir-link2 => linked-dir
        //  linked-dir/
        //    file
        topDir = fileSystem.getPath("/top-dir")
        val dirLink2: Path = fileSystem.getPath("/top-dir/dir-link2")
        val linkedDir: Path = fileSystem.getPath("/linked-dir")
        val linkedDirFile: Path? = fileSystem.getPath("/top-dir/dir-link2/file")

        topDir.createDirectory()
        linkedDir.createDirectory()
        dirLink2.createSymbolicLink(linkedDir) // simple symlink
        FileSystemUtils.createEmptyFile(linkedDirFile) // created through the link

        // traverseTree doesn't follow links:
        var paths: MutableCollection<Path?>? = traverseTree(topDir, { ignored -> true })
        Truth.assertThat(paths).containsExactly(dirLink2)

        paths = traverseTree(linkedDir, { ignored -> true })
        Truth.assertThat(paths).containsExactly(fileSystem.getPath("/linked-dir/file"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWriteIsoLatin1() {
        val file: Path? = fileSystem.getPath("/does/not/exist/yet.txt")
        FileSystemUtils.writeIsoLatin1(file, "Line 1", "Line 2", "Line 3")
        val expected = "Line 1\nLine 2\nLine 3\n"
        val actual = String(FileSystemUtils.readContentAsLatin1(file))
        Truth.assertThat(actual).isEqualTo(expected)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWriteLinesAs() {
        val file: Path? = fileSystem.getPath("/does/not/exist/yet.txt")
        FileSystemUtils.writeLinesAs(file, java.nio.charset.StandardCharsets.UTF_8, "\u00F6") // an oe umlaut
        val expected = byteArrayOf(0xC3.toByte(), 0xB6.toByte(), 0x0A) //"\u00F6\n";
        val actual: ByteArray? = FileSystemUtils.readContent(file)
        Truth.assertThat(actual).isEqualTo(expected)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUpdateContent() {
        val file: Path = fileSystem.getPath("/test.txt")

        clock.advanceMillis(1000)

        val content = byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 23, 42)
        FileSystemUtils.maybeUpdateContent(file, content)
        var actual: ByteArray? = FileSystemUtils.readContent(file)
        Truth.assertThat(actual).isEqualTo(content)
        var stat: FileStatus = file.stat()
        assertThat(stat.lastChangeTime).isEqualTo(1000)
        assertThat(stat.lastModifiedTime).isEqualTo(1000)

        clock.advanceMillis(1000)

        // Update with same contents; should not write anything.
        FileSystemUtils.maybeUpdateContent(file, content)
        Truth.assertThat(actual).isEqualTo(content)
        stat = file.stat()
        assertThat(stat.lastChangeTime).isEqualTo(1000)
        assertThat(stat.lastModifiedTime).isEqualTo(1000)

        clock.advanceMillis(1000)

        // Update with different contents; file should be rewritten.
        content[0] = 'b'.code.toByte()
        file.chmod(256) // Protect the file to ensure we can rewrite it.
        FileSystemUtils.maybeUpdateContent(file, content)
        actual = FileSystemUtils.readContent(file)
        Truth.assertThat(actual).isEqualTo(content)
        stat = file.stat()
        assertThat(stat.lastChangeTime).isEqualTo(3000)
        assertThat(stat.lastModifiedTime).isEqualTo(3000)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetFileSystem() {
        val mountTable: Path? = fileSystem.getPath("/proc/mounts")
        FileSystemUtils.writeIsoLatin1(
            mountTable,
            "/dev/sda1 / ext2 blah 0 0",
            "/dev/mapper/_dev_sda6 /usr/local/google ext3 blah 0 0",
            "devshm /dev/shm tmpfs blah 0 0",
            "/dev/fuse /fuse/mnt fuse blah 0 0",
            "mtvhome22.nfs:/vol/mtvhome22/johndoe /home/johndoe nfs blah 0 0",
            "/dev/foo /foo dummy_foo blah 0 0",
            "/dev/foobar /foobar dummy_foobar blah 0 0",
            "proc proc proc rw,noexec,nosuid,nodev 0 0"
        )
        var path: Path = fileSystem.getPath("/usr/local/google/_blaze")
        path.createDirectoryAndParents()
        assertThat(FileSystemUtils.getFileSystem(path)).isEqualTo("ext3")

        // Should match the root "/"
        path = fileSystem.getPath("/usr/local/tmp")
        path.createDirectoryAndParents()
        assertThat(FileSystemUtils.getFileSystem(path)).isEqualTo("ext2")

        // Make sure we don't consider /foobar matches /foo
        path = fileSystem.getPath("/foo")
        path.createDirectoryAndParents()
        assertThat(FileSystemUtils.getFileSystem(path)).isEqualTo("dummy_foo")
        path = fileSystem.getPath("/foobar")
        path.createDirectoryAndParents()
        assertThat(FileSystemUtils.getFileSystem(path)).isEqualTo("dummy_foobar")

        path = fileSystem.getPath("/dev/shm/blaze")
        path.createDirectoryAndParents()
        assertThat(FileSystemUtils.getFileSystem(path)).isEqualTo("tmpfs")

        val fusePath: Path = fileSystem.getPath("/fuse/mnt/tmp")
        fusePath.createDirectoryAndParents()
        assertThat(FileSystemUtils.getFileSystem(fusePath)).isEqualTo("fuse")

        // Create a symlink and make sure it gives the file system of the symlink target.
        path = fileSystem.getPath("/usr/local/google/_blaze/out")
        path.createSymbolicLink(fusePath)
        assertThat(FileSystemUtils.getFileSystem(path)).isEqualTo("fuse")

        // Non existent path should return "unknown"
        path = fileSystem.getPath("/does/not/exist")
        assertThat(FileSystemUtils.getFileSystem(path)).isEqualTo("unknown")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStartsWithAnySuccess() {
        val a: PathFragment? = PathFragment.create("a")
        assertThat(
            FileSystemUtils.startsWithAny(
                a, java.util.Arrays.asList<T?>(PathFragment.create("b"), PathFragment.create("a"))
            )
        )
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStartsWithAnyNotFound() {
        val a: PathFragment? = PathFragment.create("a")
        assertThat(
            FileSystemUtils.startsWithAny(
                a, java.util.Arrays.asList<T?>(PathFragment.create("b"), PathFragment.create("c"))
            )
        )
            .isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReadLines() {
        val file: Path? = fileSystem.getPath("/test.txt")
        FileSystemUtils.writeContent(file, java.nio.charset.StandardCharsets.ISO_8859_1, "a\nb")
        assertThat(FileSystemUtils.readLinesAsLatin1(file)).containsExactly("a", "b").inOrder()

        FileSystemUtils.writeContent(file, java.nio.charset.StandardCharsets.ISO_8859_1, "a\rb")
        assertThat(FileSystemUtils.readLinesAsLatin1(file)).containsExactly("a", "b").inOrder()

        FileSystemUtils.writeContent(file, java.nio.charset.StandardCharsets.ISO_8859_1, "a\r\nb")
        assertThat(FileSystemUtils.readLinesAsLatin1(file)).containsExactly("a", "b").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnsureSymbolicLinkCreatesNewLink() {
        val target: PathFragment? = PathFragment.create("/b")
        val link: Path = fileSystem.getPath("/a")
        FileSystemUtils.ensureSymbolicLink(link, target)
        assertThat(link.isSymbolicLink()).isTrue()
        assertThat(link.readSymbolicLink()).isEqualTo(target)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnsureSymbolicLinkReplacesExistingLink() {
        val target: PathFragment? = PathFragment.create("/b")
        val link: Path = fileSystem.getPath("/a")
        link.createSymbolicLink(PathFragment.create("/c"))
        FileSystemUtils.ensureSymbolicLink(link, target)
        assertThat(link.isSymbolicLink()).isTrue()
        assertThat(link.readSymbolicLink()).isEqualTo(target)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnsureSymbolicLinkKeepsUpToDateLink() {
        val target: PathFragment? = PathFragment.create("/b")
        val link: Path = fileSystem.getPath("/a")
        link.createSymbolicLink(target)
        FileSystemUtils.ensureSymbolicLink(link, target)
        assertThat(link.isSymbolicLink()).isTrue()
        assertThat(link.readSymbolicLink()).isEqualTo(target)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnsureSymbolicLinkCreatesParentDirectories() {
        val target: PathFragment? = PathFragment.create("/b")
        val link: Path = fileSystem.getPath("/a/b/c")
        FileSystemUtils.ensureSymbolicLink(link, target)
        assertThat(link.isSymbolicLink()).isTrue()
        assertThat(link.readSymbolicLink()).isEqualTo(target)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnsureSymbolicLinkFailsForExistingDirectory() {
        val target: PathFragment? = PathFragment.create("/b")
        val link: Path = fileSystem.getPath("/a")
        link.createDirectory()
        org.junit.Assert.assertThrows<T?>(
            NotASymlinkException::class.java,
            org.junit.function.ThrowingRunnable { FileSystemUtils.ensureSymbolicLink(link, target) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnsureSymbolicLinkFailsForExistingFile() {
        val target: PathFragment? = PathFragment.create("/b")
        val link: Path? = fileSystem.getPath("/a")
        FileSystemUtils.createEmptyFile(link)
        org.junit.Assert.assertThrows<T?>(
            NotASymlinkException::class.java,
            org.junit.function.ThrowingRunnable { FileSystemUtils.ensureSymbolicLink(link, target) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateHardLinkForFile_success() {
        /* Original file exists and link file does not exist */

        val originalPath: Path = workingDir.getRelative("original")
        val linkPath: Path = workingDir.getRelative("link")
        FileSystemUtils.createEmptyFile(originalPath)
        FileSystemUtils.createHardLink(linkPath, originalPath)
        assertThat(originalPath.exists()).isTrue()
        assertThat(linkPath.exists()).isTrue()
        assertThat(fileSystem.stat(linkPath.asFragment(), false).nodeId)
            .isEqualTo(fileSystem.stat(originalPath.asFragment(), false).nodeId)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateHardLinkForEmptyDirectory_success() {
        val originalDir: Path = workingDir.getRelative("originalDir")
        val linkPath: Path = workingDir.getRelative("link")

        originalDir.createDirectoryAndParents()

        /* Original directory is empty, no link to be created. */
        FileSystemUtils.createHardLink(linkPath, originalDir)
        assertThat(linkPath.exists()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateHardLinkForNonEmptyDirectory_success() {
        /* Test when original path is a directory */

        val originalDir: Path = workingDir.getRelative("originalDir")
        val linkPath: Path = workingDir.getRelative("link")
        val originalPath1: Path = originalDir.getRelative("original1")
        val originalPath2: Path = originalDir.getRelative("original2")
        val originalPath3: Path = originalDir.getRelative("original3")
        val linkPath1: Path = linkPath.getRelative("original1")
        val linkPath2: Path = linkPath.getRelative("original2")
        val linkPath3: Path = linkPath.getRelative("original3")

        originalDir.createDirectoryAndParents()
        FileSystemUtils.createEmptyFile(originalPath1)
        FileSystemUtils.createEmptyFile(originalPath2)
        FileSystemUtils.createEmptyFile(originalPath3)

        /* Three link files created under linkPath */
        FileSystemUtils.createHardLink(linkPath, originalDir)
        assertThat(linkPath.exists()).isTrue()
        assertThat(linkPath1.exists()).isTrue()
        assertThat(linkPath2.exists()).isTrue()
        assertThat(linkPath3.exists()).isTrue()
        assertThat(fileSystem.stat(linkPath1.asFragment(), false).nodeId)
            .isEqualTo(fileSystem.stat(originalPath1.asFragment(), false).nodeId)
        assertThat(fileSystem.stat(linkPath2.asFragment(), false).nodeId)
            .isEqualTo(fileSystem.stat(originalPath2.asFragment(), false).nodeId)
        assertThat(fileSystem.stat(linkPath3.asFragment(), false).nodeId)
            .isEqualTo(fileSystem.stat(originalPath3.asFragment(), false).nodeId)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRenameToleratingConcurrentCreation_success() {
        val source: Path = fileSystem.getPath("/source")
        val target: Path = fileSystem.getPath("/target")
        target.getParentDirectory().createDirectoryAndParents()

        FileSystemUtils.writeContent(source, java.nio.charset.StandardCharsets.UTF_8, "hello world")

        renameToleratingConcurrentCreation(source, target)

        assertThat(source.exists()).isFalse()
        assertThat(target.exists()).isTrue()
        assertThat(
            FileSystemUtils.readContent(
                target,
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).isEqualTo("hello world")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRenameToleratingConcurrentCreation_sourceDoesNotExist() {
        val source: Path? = fileSystem.getPath("/source")
        val target: Path = fileSystem.getPath("/target")
        target.getParentDirectory().createDirectoryAndParents()

        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable { renameToleratingConcurrentCreation(source, target) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRenameToleratingConcurrentCreation_targetParentDoesNotExist() {
        val source: Path? = fileSystem.getPath("/source")
        val target: Path? = fileSystem.getPath("/nonexistent/target")

        FileSystemUtils.writeContent(source, java.nio.charset.StandardCharsets.UTF_8, "hello world")

        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable { renameToleratingConcurrentCreation(source, target) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRenameToleratingConcurrentCreation_toleratesFileAccessExceptionOnWindows() {
        val fs: FileSystem = FileAccessExceptionOnRenameFs()
        val source: Path = fs.getPath("/source")
        val target: Path = fs.getPath("/target")
        source.getParentDirectory().createDirectoryAndParents()
        target.getParentDirectory().createDirectoryAndParents()

        FileSystemUtils.writeContent(source, java.nio.charset.StandardCharsets.UTF_8, "hello world")
        FileSystemUtils.writeContent(target, java.nio.charset.StandardCharsets.UTF_8, "existing content")

        if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS) {
            renameToleratingConcurrentCreation(source, target)

            assertThat(source.exists()).isFalse()
            assertThat(target.exists()).isTrue()
            assertThat(
                FileSystemUtils.readContent(
                    target,
                    java.nio.charset.StandardCharsets.UTF_8
                )
            ).isEqualTo("existing content")
        } else {
            org.junit.Assert.assertThrows<T?>(
                FileAccessException::class.java,
                org.junit.function.ThrowingRunnable { renameToleratingConcurrentCreation(source, target) })

            assertThat(source.exists()).isTrue()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRenameToleratingConcurrentCreation_throwsIfTargetDoesNotExistAfterException() {
        val fs: FileSystem = FileAccessExceptionOnRenameFs()
        val source: Path = fs.getPath("/source")
        val target: Path = fs.getPath("/target")
        source.getParentDirectory().createDirectoryAndParents()
        target.getParentDirectory().createDirectoryAndParents()

        FileSystemUtils.writeContent(source, java.nio.charset.StandardCharsets.UTF_8, "hello world")

        org.junit.Assert.assertThrows<T?>(
            FileAccessException::class.java,
            org.junit.function.ThrowingRunnable { renameToleratingConcurrentCreation(source, target) })

        assertThat(source.exists()).isTrue()
    }

    /** A file system that throws FileAccessException on rename, simulating Windows behavior.  */
    internal class FileAccessExceptionOnRenameFs : InMemoryFileSystem(DigestHashFunction.SHA256) {
        @Throws(IOException::class)
        public override fun renameTo(source: PathFragment?, target: PathFragment?) {
            throw FileAccessException("Access denied (simulated Windows behavior)")
        }
    }

    internal class MultipleDeviceFS : InMemoryFileSystem(DigestHashFunction.SHA256) {
        @Throws(IOException::class)
        public override fun renameTo(source: PathFragment, target: PathFragment) {
            if (!source.startsWith(target.subFragment(0, 1))) {
                throw IOException("EXDEV")
            }
            super.renameTo(source, target)
        }
    }

    companion object {
        private fun assertPath(expected: String?, actual: PathFragment) {
            assertThat(actual.getPathString()).isEqualTo(expected)
        }

        private fun assertPath(expected: String?, actual: Path) {
            assertThat(actual.getPathString()).isEqualTo(expected)
        }
    }
}
