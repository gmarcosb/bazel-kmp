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

import com.google.devtools.build.lib.util.OS

/**
 * This class handles the generic tests that any filesystem must pass.
 * 
 * 
 * Each filesystem-test should inherit from this class, thereby obtaining
 * all the tests.
 */
abstract class SymlinkAwareFileSystemTest : FileSystemTest() {
    protected var xLinkToFile: Path? = null
    protected var xLinkToLinkToFile: Path? = null
    protected var xLinkToDirectory: Path? = null
    protected var xDanglingLink: Path? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createSymbolicLinks() {
        // % ls -lR
        // -rw-rw-r-- xFile
        // drwxrwxr-x xNonEmptyDirectory
        // -rw-rw-r-- xNonEmptyDirectory/foo
        // drwxrwxr-x xEmptyDirectory
        // lrwxrwxr-x xLinkToFile -> xFile
        // lrwxrwxr-x xLinkToDirectory -> xEmptyDirectory
        // lrwxrwxr-x xLinkToLinkToFile -> xLinkToFile
        // lrwxrwxr-x xDanglingLink -> xNothing

        xLinkToFile = absolutize("xLinkToFile")
        xLinkToLinkToFile = absolutize("xLinkToLinkToFile")
        xLinkToDirectory = absolutize("xLinkToDirectory")
        xDanglingLink = absolutize("xDanglingLink")

        createSymbolicLink(xLinkToFile, xFile)
        createSymbolicLink(xLinkToLinkToFile, xLinkToFile)
        createSymbolicLink(xLinkToDirectory, xEmptyDirectory)
        createSymbolicLink(xDanglingLink, xNothing)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testCreateLinkToFile() {
        val newPath: Path = xEmptyDirectory.getChild("new-file")
        FileSystemUtils.createEmptyFile(newPath)

        val linkPath: Path = xEmptyDirectory.getChild("some-link")

        createSymbolicLink(linkPath, newPath)

        assertThat(linkPath.isSymbolicLink()).isTrue()

        assertThat(linkPath.isFile()).isTrue()
        assertThat(linkPath.isFile(Symlinks.NOFOLLOW)).isFalse()
        assertThat(linkPath.isFile(Symlinks.FOLLOW)).isTrue()

        assertThat(linkPath.isDirectory()).isFalse()
        assertThat(linkPath.isDirectory(Symlinks.NOFOLLOW)).isFalse()
        assertThat(linkPath.isDirectory(Symlinks.FOLLOW)).isFalse()

        if (testFS.supportsSymbolicLinksNatively(linkPath.asFragment())) {
            assertThat(linkPath.getFileSize(Symlinks.NOFOLLOW)).isEqualTo(newPath.toString().length())
            assertThat(linkPath.getFileSize()).isEqualTo(newPath.getFileSize(Symlinks.NOFOLLOW))
        }
        assertThat(linkPath.getParentDirectory().getDirectoryEntries()).hasSize(2)
        assertThat(linkPath.getParentDirectory().getDirectoryEntries()).containsExactly(
            newPath,
            linkPath
        )
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testCreateLinkToDirectory() {
        val newPath: Path = xEmptyDirectory.getChild("new-file")
        newPath.createDirectory()

        val linkPath: Path = xEmptyDirectory.getChild("some-link")

        createSymbolicLink(linkPath, newPath)

        assertThat(linkPath.isSymbolicLink()).isTrue()
        assertThat(linkPath.isFile()).isFalse()
        assertThat(linkPath.isDirectory()).isTrue()
        assertThat(linkPath.getParentDirectory().getDirectoryEntries()).hasSize(2)
        assertThat(
            linkPath.getParentDirectory().getDirectoryEntries
                ()
        ).containsExactly(newPath, linkPath)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testFileCanonicalPath() {
        var newPath: Path = absolutize("new-file")
        FileSystemUtils.createEmptyFile(newPath)
        newPath = newPath.resolveSymbolicLinks()

        val link1: Path = absolutize("some-link")
        val link2: Path = absolutize("some-link2")

        createSymbolicLink(link1, newPath)
        createSymbolicLink(link2, link1)

        assertCanonicalPathsMatch(newPath, link1, link2)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testDirectoryCanonicalPath() {
        var newPath: Path = absolutize("new-folder")
        newPath.createDirectory()
        newPath = newPath.resolveSymbolicLinks()

        val newFile: Path? = newPath.getChild("file")
        FileSystemUtils.createEmptyFile(newFile)

        val link1: Path = absolutize("some-link")
        val link2: Path = absolutize("some-link2")

        createSymbolicLink(link1, newPath)
        createSymbolicLink(link2, link1)

        val linkFile1: Path = link1.getChild("file")
        val linkFile2: Path = link2.getChild("file")

        assertCanonicalPathsMatch(newFile, linkFile1, linkFile2)
    }

    @Throws(IOException::class)
    private fun assertCanonicalPathsMatch(newPath: Path?, link1: Path, link2: Path) {
        assertThat(link1.resolveSymbolicLinks()).isEqualTo(newPath)
        assertThat(link2.resolveSymbolicLinks()).isEqualTo(newPath)
    }

    //
    //  createDirectory
    //
    @org.junit.Test
    fun testCreateDirectoryWhereDanglingSymlinkAlreadyExists() {
        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { xDanglingLink.createDirectory() })
        Truth.assertThat(e).hasMessageThat().isEqualTo(xDanglingLink.toString() + " (File exists)")
        assertThat(xDanglingLink.isSymbolicLink()).isTrue() // still a symbolic link
        assertThat(xDanglingLink.isDirectory(Symlinks.FOLLOW)).isFalse() // link still dangles
    }

    @org.junit.Test
    fun testCreateDirectoryWhereSymlinkAlreadyExists() {
        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { xLinkToDirectory.createDirectory() })
        Truth.assertThat(e).hasMessageThat().isEqualTo(xLinkToDirectory.toString() + " (File exists)")
        assertThat(xLinkToDirectory.isSymbolicLink()).isTrue() // still a symbolic link
        assertThat(xLinkToDirectory.isDirectory(Symlinks.FOLLOW)).isTrue() // link still points to dir
    }

    //  createSymbolicLink(PathFragment)
    @org.junit.Test
    @Throws(IOException::class)
    fun testCreateSymbolicLinkFromFragment() {
        val linkTargets = arrayOf<String?>(
            "foo",
            "foo/bar",
            ".",
            "..",
            "../foo",
            "../../foo",
            "../../../../../../../../../../../../../../../../../../../../../foo",
            "/foo",
            "/foo/bar",
            "/..",
            "/foo/../bar",
        )
        val linkPath: Path = absolutize("link")
        for (linkTarget in linkTargets) {
            val relative: PathFragment = PathFragment.create(linkTarget)
            linkPath.delete()
            createSymbolicLink(linkPath, relative)
            if (testFS.supportsSymbolicLinksNatively(linkPath.asFragment())) {
                assertThat(linkPath.getFileSize(Symlinks.NOFOLLOW))
                    .isEqualTo(relative.getSafePathString().length())
                assertThat(linkPath.readSymbolicLink()).isEqualTo(relative)
            }
        }
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testLinkToRootResolvesCorrectly() {
        if (OS.getCurrent() === OS.WINDOWS) {
            // This test cannot be run on Windows, it mixes "/" paths with "C:/" paths
            return
        }
        val rootPath: Path = testFS.getPath("/")

        try {
            rootPath.getChild("testDir").createDirectory()
        } catch (e: IOException) {
            // Do nothing. This is a real FS, and we don't have permission.
        }

        val linkPath: Path = absolutize("link")
        createSymbolicLink(linkPath, rootPath)

        // resolveSymbolicLinks requires an existing path:
        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable { linkPath.getRelative("test").resolveSymbolicLinks() })

        // The path may not be a symlink, neither on Darwin nor on Linux.
        var nonLinkEntry: String? = null
        for (child in testFS.getDirectoryEntries(rootPath.asFragment())) {
            val p: Path = rootPath.getChild(child)
            if (!p.isSymbolicLink() && p.isDirectory()) {
                nonLinkEntry = p.getBaseName()
                break
            }
        }

        Truth.assertThat(nonLinkEntry).isNotNull()
        val rootChild: Path? = testFS.getPath("/" + nonLinkEntry)
        assertThat(linkPath.getRelative(nonLinkEntry).resolveSymbolicLinks()).isEqualTo(rootChild)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testLinkToFragmentContainingLinkResolvesCorrectly() {
        val link1: Path = absolutize("link1")
        val link1target: PathFragment? = PathFragment.create("link2/foo")
        val link2: Path = absolutize("link2")
        val link2target: Path = xNonEmptyDirectory

        createSymbolicLink(link1, link1target) // ln -s link2/foo link1
        createSymbolicLink(link2, link2target) // ln -s xNonEmptyDirectory link2
        // link1 --> xNonEmptyDirectory/foo
        assertThat(link2target.getRelative("foo")).isEqualTo(link1.resolveSymbolicLinks())
    }

    //
    //  readSymbolicLink / resolveSymbolicLinks
    //
    @org.junit.Test
    @Throws(IOException::class)
    fun testRecursiveSymbolicLink() {
        val link: Path = absolutize("recursive-link")
        createSymbolicLink(link, link)

        if (testFS.supportsSymbolicLinksNatively(link.asFragment())) {
            val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { link.resolveSymbolicLinks() })
            Truth.assertThat(e).hasMessageThat().isEqualTo(link.toString() + " (Too many levels of symbolic links)")
        }
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testMutuallyRecursiveSymbolicLinks() {
        val link1: Path = absolutize("link1")
        val link2: Path = absolutize("link2")
        createSymbolicLink(link2, link1)
        createSymbolicLink(link1, link2)

        if (testFS.supportsSymbolicLinksNatively(link1.asFragment())) {
            val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { link1.resolveSymbolicLinks() })
            Truth.assertThat(e).hasMessageThat().isEqualTo(link1.toString() + " (Too many levels of symbolic links)")
        }
    }

    @org.junit.Test
    fun testResolveSymbolicLinksENOENT() {
        if (testFS.supportsSymbolicLinksNatively(xDanglingLink.asFragment())) {
            val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { xDanglingLink.resolveSymbolicLinks() })
            Truth.assertThat(e).hasMessageThat().endsWith(xNothing.toString() + " (No such file or directory)")
        }
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testResolveSymbolicLinksENOTDIR() {
        val badLinkTarget: Path = xFile.getChild("bad") // parent is not a directory!
        val badLink: Path = absolutize("badLink")
        if (testFS.supportsSymbolicLinksNatively(badLink.asFragment())) {
            createSymbolicLink(badLink, badLinkTarget)
            org.junit.Assert.assertThrows<IOException?>(IOException::class.java, badLink::resolveSymbolicLinks)
        }
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testResolveSymbolicLinksWithUplevelRefs() {
        if (testFS.supportsSymbolicLinksNatively(xLinkToFile.asFragment())) {
            // Create a series of links that refer to xFile as ./xFile,
            // ./../foo/xFile, ./../../bar/foo/xFile, etc.  They should all resolve
            // to xFile.
            var ancestor: Path = xFile
            var prefix = "./"
            while ((ancestor.getParentDirectory().also { ancestor = it }) != null) {
                xLinkToFile.delete()
                createSymbolicLink(xLinkToFile, PathFragment.create(prefix + xFile.relativeTo(ancestor)))
                assertThat(xLinkToFile.resolveSymbolicLinks()).isEqualTo(xFile)

                prefix += "../"
            }
        }
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testReadSymbolicLink() {
        if (testFS.supportsSymbolicLinksNatively(xDanglingLink.asFragment())) {
            assertThat(xDanglingLink.readSymbolicLink().toString()).isEqualTo(xNothing.toString())
        }

        assertThat(xLinkToFile.readSymbolicLink().toString()).isEqualTo(xFile.toString())

        assertThat(xLinkToDirectory.readSymbolicLink().toString())
            .isEqualTo(xEmptyDirectory.toString())

        val nase: NotASymlinkException? =
            org.junit.Assert.assertThrows<T?>(
                NotASymlinkException::class.java,
                org.junit.function.ThrowingRunnable { xFile.readSymbolicLink() })
        assertThat(nase).hasMessageThat().isEqualTo(xFile.toString() + " is not a symlink")

        val fnfe: FileNotFoundException? =
            org.junit.Assert.assertThrows<FileNotFoundException?>(
                FileNotFoundException::class.java,
                org.junit.function.ThrowingRunnable { xNothing.readSymbolicLink() })
        Truth.assertThat(fnfe).hasMessageThat().endsWith(xNothing.toString() + " (No such file or directory)")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testCannotCreateSymbolicLinkWithReadOnlyParent() {
        xEmptyDirectory.setWritable(false)
        val xChildOfReadonlyDir: Path = xEmptyDirectory.getChild("x")
        if (testFS.supportsSymbolicLinksNatively(xChildOfReadonlyDir.asFragment())) {
            val e: IOException? =
                org.junit.Assert.assertThrows<IOException?>(
                    IOException::class.java,
                    org.junit.function.ThrowingRunnable { xChildOfReadonlyDir.createSymbolicLink(xNothing) })
            Truth.assertThat(e).hasMessageThat().endsWith(xChildOfReadonlyDir.toString() + " (Permission denied)")
        }
    }

    //
    // createSymbolicLink
    //
    @org.junit.Test
    @Throws(IOException::class)
    fun testCanCreateDanglingLink() {
        val newPath: Path = absolutize("non-existing-dir/new-file")
        val someLink: Path = absolutize("dangling-link")
        createSymbolicLink(someLink, newPath)
        assertThat(someLink.isSymbolicLink()).isTrue()
        assertThat(someLink.exists(Symlinks.NOFOLLOW)).isTrue() // the link itself exists
        assertThat(someLink.exists()).isFalse() // ...but the referent doesn't
        if (testFS.supportsSymbolicLinksNatively(someLink.asFragment())) {
            val e: FileNotFoundException? =
                org.junit.Assert.assertThrows<FileNotFoundException?>(
                    FileNotFoundException::class.java,
                    someLink::resolveSymbolicLinks
                )
            Truth.assertThat(e)
                .hasMessageThat()
                .endsWith(newPath.getParentDirectory() + " (No such file or directory)")
        }
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testCannotCreateSymbolicLinkWithoutParent() {
        val xChildOfMissingDir: Path = xNothing.getChild("x")
        if (testFS.supportsSymbolicLinksNatively(xChildOfMissingDir.asFragment())) {
            val e: FileNotFoundException? =
                org.junit.Assert.assertThrows<FileNotFoundException?>(
                    FileNotFoundException::class.java,
                    org.junit.function.ThrowingRunnable { xChildOfMissingDir.createSymbolicLink(xFile) })
            Truth.assertThat(e).hasMessageThat().endsWith(" (No such file or directory)")
        }
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testCreateSymbolicLinkWhereNothingExists() {
        createSymbolicLink(xNothing, xFile)
        assertThat(xNothing.isSymbolicLink()).isTrue()
    }

    @org.junit.Test
    fun testCreateSymbolicLinkWhereDirectoryAlreadyExists() {
        val e: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { createSymbolicLink(xEmptyDirectory, xFile) })
        Truth.assertThat(e).hasMessageThat().endsWith(xEmptyDirectory.toString() + " (File exists)")
        assertThat(xEmptyDirectory.isDirectory(Symlinks.NOFOLLOW)).isTrue()
    }

    @org.junit.Test
    fun testCreateSymbolicLinkWhereFileAlreadyExists() {
        val e: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { createSymbolicLink(xFile, xEmptyDirectory) })
        Truth.assertThat(e).hasMessageThat().endsWith(xFile.toString() + " (File exists)")
        assertThat(xFile.isFile(Symlinks.NOFOLLOW)).isTrue()
    }

    @org.junit.Test
    fun testCreateSymbolicLinkWhereDanglingSymlinkAlreadyExists() {
        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { createSymbolicLink(xDanglingLink, xFile) })
        Truth.assertThat(e).hasMessageThat().endsWith(xDanglingLink.toString() + " (File exists)")
        assertThat(xDanglingLink.isSymbolicLink()).isTrue() // still a symbolic link
        assertThat(xDanglingLink.isDirectory()).isFalse() // link still dangles
    }

    @org.junit.Test
    fun testCreateSymbolicLinkWhereSymlinkAlreadyExists() {
        val e: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { createSymbolicLink(xLinkToDirectory, xNothing) })
        Truth.assertThat(e).hasMessageThat().endsWith(xLinkToDirectory.toString() + " (File exists)")
        assertThat(xLinkToDirectory.isSymbolicLink()).isTrue() // still a symbolic link
        assertThat(xLinkToDirectory.isDirectory()).isTrue() // link still points to dir
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testDeleteLink() {
        val newPath: Path = xEmptyDirectory.getChild("new-file")
        val someLink: Path = xEmptyDirectory.getChild("a-link")
        FileSystemUtils.createEmptyFile(newPath)
        createSymbolicLink(someLink, newPath)

        assertThat(xEmptyDirectory.getDirectoryEntries()).hasSize(2)

        assertThat(someLink.delete()).isTrue()
        assertThat(xEmptyDirectory.getDirectoryEntries()).hasSize(1)

        assertThat(xEmptyDirectory.getDirectoryEntries()).containsExactly(newPath)
    }

    // Testing the links
    @org.junit.Test
    @Throws(IOException::class)
    fun testLinkFollowedToDirectory() {
        val theDirectory: Path = absolutize("foo/")
        assertThat(theDirectory.createDirectory()).isTrue()
        val newPath1: Path = absolutize("foo/new-file-1")
        val newPath2: Path = absolutize("foo/new-file-2")
        val newPath3: Path = absolutize("foo/new-file-3")

        FileSystemUtils.createEmptyFile(newPath1)
        FileSystemUtils.createEmptyFile(newPath2)
        FileSystemUtils.createEmptyFile(newPath3)

        val linkPath: Path = absolutize("link")
        createSymbolicLink(linkPath, theDirectory)

        val resultPath1: Path = absolutize("link/new-file-1")
        val resultPath2: Path = absolutize("link/new-file-2")
        val resultPath3: Path = absolutize("link/new-file-3")
        assertThat(linkPath.getDirectoryEntries()).containsExactly(
            resultPath1, resultPath2,
            resultPath3
        )
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testDanglingLinkIsNoFile() {
        val newPath1: Path = absolutize("new-file-1")
        val newPath2: Path = absolutize("new-file-2")
        FileSystemUtils.createEmptyFile(newPath1)
        assertThat(newPath2.createDirectory()).isTrue()

        val linkPath1: Path = absolutize("link1")
        val linkPath2: Path = absolutize("link2")
        createSymbolicLink(linkPath1, newPath1)
        createSymbolicLink(linkPath2, newPath2)

        newPath1.delete()
        newPath2.delete()

        assertThat(linkPath1.isFile()).isFalse()
        assertThat(linkPath2.isDirectory()).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testWriteOnLinkChangesFile() {
        val testFile: Path = absolutize("test-file")
        FileSystemUtils.createEmptyFile(testFile)
        val testData = "abc19"

        val testLink: Path = absolutize("a-link")
        createSymbolicLink(testLink, testFile)

        FileSystemUtils.writeContentAsLatin1(testLink, testData)
        val resultData = String(FileSystemUtils.readContentAsLatin1(testFile))

        Truth.assertThat(resultData).isEqualTo(testData)
    }

    //
    // Symlink tests:
    //
    @org.junit.Test
    @Throws(IOException::class)
    fun testExistsWithSymlinks() {
        val a: Path = absolutize("a")
        val b: Path = absolutize("b")
        FileSystemUtils.createEmptyFile(b)
        createSymbolicLink(a, b) // ln -sf "b" "a"
        assertThat(a.exists()).isTrue() // = exists(FOLLOW)
        assertThat(b.exists()).isTrue() // = exists(FOLLOW)
        assertThat(a.exists(Symlinks.FOLLOW)).isTrue()
        assertThat(b.exists(Symlinks.FOLLOW)).isTrue()
        assertThat(a.exists(Symlinks.NOFOLLOW)).isTrue()
        assertThat(b.exists(Symlinks.NOFOLLOW)).isTrue()
        b.delete() // "a" is now a dangling link
        assertThat(a.exists()).isFalse() // = exists(FOLLOW)
        assertThat(b.exists()).isFalse() // = exists(FOLLOW)
        assertThat(a.exists(Symlinks.FOLLOW)).isFalse()
        assertThat(b.exists(Symlinks.FOLLOW)).isFalse()

        assertThat(a.exists(Symlinks.NOFOLLOW)).isTrue() // symlink still exists
        assertThat(b.exists(Symlinks.NOFOLLOW)).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testIsDirectoryWithSymlinks() {
        val a: Path = absolutize("a")
        val b: Path = absolutize("b")
        b.createDirectory()
        createSymbolicLink(a, b) // ln -sf "b" "a"
        assertThat(a.isDirectory()).isTrue() // = isDirectory(FOLLOW)
        assertThat(b.isDirectory()).isTrue() // = isDirectory(FOLLOW)
        assertThat(a.isDirectory(Symlinks.FOLLOW)).isTrue()
        assertThat(b.isDirectory(Symlinks.FOLLOW)).isTrue()
        assertThat(a.isDirectory(Symlinks.NOFOLLOW)).isFalse() // it's a link!
        assertThat(b.isDirectory(Symlinks.NOFOLLOW)).isTrue()
        b.delete() // "a" is now a dangling link
        assertThat(a.isDirectory()).isFalse() // = isDirectory(FOLLOW)
        assertThat(b.isDirectory()).isFalse() // = isDirectory(FOLLOW)
        assertThat(a.isDirectory(Symlinks.FOLLOW)).isFalse()
        assertThat(b.isDirectory(Symlinks.FOLLOW)).isFalse()
        assertThat(a.isDirectory(Symlinks.NOFOLLOW)).isFalse()
        assertThat(b.isDirectory(Symlinks.NOFOLLOW)).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testIsFileWithSymlinks() {
        val a: Path = absolutize("a")
        val b: Path = absolutize("b")
        FileSystemUtils.createEmptyFile(b)
        createSymbolicLink(a, b) // ln -sf "b" "a"
        assertThat(a.isFile()).isTrue() // = isFile(FOLLOW)
        assertThat(b.isFile()).isTrue() // = isFile(FOLLOW)
        assertThat(a.isFile(Symlinks.FOLLOW)).isTrue()
        assertThat(b.isFile(Symlinks.FOLLOW)).isTrue()
        assertThat(a.isFile(Symlinks.NOFOLLOW)).isFalse() // it's a link!
        assertThat(b.isFile(Symlinks.NOFOLLOW)).isTrue()
        b.delete() // "a" is now a dangling link
        assertThat(a.isFile()).isFalse() // = isFile()
        assertThat(b.isFile()).isFalse() // = isFile()
        assertThat(a.isFile()).isFalse()
        assertThat(b.isFile()).isFalse()
        assertThat(a.isFile(Symlinks.NOFOLLOW)).isFalse()
        assertThat(b.isFile(Symlinks.NOFOLLOW)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetDirectoryEntriesOnLinkToDirectory() {
        val fooAlias: Path? = xNothing.getChild("foo")
        createSymbolicLink(xNothing, xNonEmptyDirectory)
        val dirents: MutableCollection<Path?>? = xNothing.getDirectoryEntries()
        Truth.assertThat(dirents).containsExactly(fooAlias)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilesOfLinkedDirectories() {
        val child: Path = xEmptyDirectory.getChild("child")
        val aliasToChild: Path = xLinkToDirectory.getChild("child")

        assertThat(aliasToChild.exists()).isFalse()
        FileSystemUtils.createEmptyFile(child)
        assertThat(aliasToChild.exists()).isTrue()
        assertThat(aliasToChild.isFile()).isTrue()
        assertThat(aliasToChild.isDirectory()).isFalse()

        validateLinkedReferenceObeysReadOnly(child, aliasToChild)
        validateLinkedReferenceObeysExecutable(child, aliasToChild)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDirectoriesOfLinkedDirectories() {
        val childDir: Path = xEmptyDirectory.getChild("childDir")
        val linkToChildDir: Path = xLinkToDirectory.getChild("childDir")

        assertThat(linkToChildDir.exists()).isFalse()
        childDir.createDirectory()
        assertThat(linkToChildDir.exists()).isTrue()
        assertThat(linkToChildDir.isDirectory()).isTrue()
        assertThat(linkToChildDir.isFile()).isFalse()

        validateLinkedReferenceObeysReadOnly(childDir, linkToChildDir)
        validateLinkedReferenceObeysExecutable(childDir, linkToChildDir)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDirectoriesOfLinkedDirectoriesOfLinkedDirectories() {
        val childDir: Path = xEmptyDirectory.getChild("childDir")
        val linkToLinkToDirectory: Path = absolutize("xLinkToLinkToDirectory")
        createSymbolicLink(linkToLinkToDirectory, xLinkToDirectory)
        val linkToChildDir: Path = linkToLinkToDirectory.getChild("childDir")

        assertThat(linkToChildDir.exists()).isFalse()
        childDir.createDirectory()
        assertThat(linkToChildDir.exists()).isTrue()
        assertThat(linkToChildDir.isDirectory()).isTrue()
        assertThat(linkToChildDir.isFile()).isFalse()

        validateLinkedReferenceObeysReadOnly(childDir, linkToChildDir)
        validateLinkedReferenceObeysExecutable(childDir, linkToChildDir)
    }

    @Throws(IOException::class)
    private fun validateLinkedReferenceObeysReadOnly(path: Path, link: Path) {
        path.setWritable(false)
        assertThat(path.isWritable()).isFalse()
        assertThat(link.isWritable()).isFalse()
        path.setWritable(true)
        assertThat(path.isWritable()).isTrue()
        assertThat(link.isWritable()).isTrue()
        path.setWritable(false)
        assertThat(path.isWritable()).isFalse()
        assertThat(link.isWritable()).isFalse()
    }

    @Throws(IOException::class)
    private fun validateLinkedReferenceObeysExecutable(path: Path, link: Path) {
        path.setExecutable(true)
        assertThat(path.isExecutable()).isTrue()
        assertThat(link.isExecutable()).isTrue()
        path.setExecutable(false)
        assertThat(path.isExecutable()).isFalse()
        assertThat(link.isExecutable()).isFalse()
        path.setExecutable(true)
        assertThat(path.isExecutable()).isTrue()
        assertThat(link.isExecutable()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReadingFileFromLinkedDirectory() {
        val linkedTo: Path = absolutize("linkedTo")
        linkedTo.createDirectory()
        val child: Path? = linkedTo.getChild("child")
        FileSystemUtils.createEmptyFile(child)

        val outputData: ByteArray? = "This is a test".toByteArray()
        FileSystemUtils.writeContent(child, outputData)

        val link: Path = absolutize("link")
        createSymbolicLink(link, linkedTo)
        val linkedChild: Path? = link.getChild("child")
        val inputData: ByteArray? = FileSystemUtils.readContent(linkedChild)
        Truth.assertThat(inputData).isEqualTo(outputData)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreatingFileInLinkedDirectory() {
        val linkedTo: Path = absolutize("linkedTo")
        linkedTo.createDirectory()
        val child: Path? = linkedTo.getChild("child")

        val link: Path = absolutize("link")
        createSymbolicLink(link, linkedTo)
        val linkedChild: Path? = link.getChild("child")
        val outputData: ByteArray? = "This is a test".toByteArray()
        FileSystemUtils.writeContent(linkedChild, outputData)

        val inputData: ByteArray? = FileSystemUtils.readContent(child)
        Truth.assertThat(inputData).isEqualTo(outputData)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUtf8Symlink() {
        FileSystemTest.Companion.assumeUtf8CompatibleEncoding()

        val target: String? = StringEncoding.unicodeToInternal("入力_A_🌱.target")
        val link: Path = absolutize(StringEncoding.unicodeToInternal("入力_A_🌱.txt"))
        createSymbolicLink(link, PathFragment.create(target))
        assertThat(link.readSymbolicLink().toString()).isEqualTo(target)

        val javaPath: Path = getJavaPathOrSkipIfUnsupported(link)
        Truth.assertThat(
            FileSystemTest.Companion.platformToUnicode(
                java.nio.file.Files.readSymbolicLink(javaPath).toString()
            )
        )
            .isEqualTo("入力_A_🌱.target")
    }
}
