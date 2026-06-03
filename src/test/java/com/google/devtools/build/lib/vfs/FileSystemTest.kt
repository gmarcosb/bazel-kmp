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
//
package com.google.devtools.build.lib.vfs

import com.google.devtools.build.lib.skyframe.DefaultSyscallCache

/**
 * This class handles the generic tests that any filesystem must pass.
 * 
 * 
 * Each filesystem-test should inherit from this class, thereby obtaining all the tests.
 */
@RunWith(TestParameterInjector::class)
abstract class FileSystemTest {
    private var savedTime: Long = 0
    protected var testFS: FileSystem? = null
    protected var workingDir: Path? = null

    // Some useful examples of various kinds of files (mnemonic: "x" = "eXample")
    protected var xNothing: Path? = null
    protected var xLink: Path? = null
    protected var xFile: Path? = null
    protected var xNonEmptyDirectory: Path? = null
    protected var xFileInNonEmptyDirectory: Path? = null
    protected var xEmptyDirectory: Path? = null

    @TestParameter(valuesProvider = DigestHashFunctionsProvider::class)
    var digestHashFunction: DigestHashFunction? = null

    private class DigestHashFunctionsProvider :
        com.google.testing.junit.testparameterinjector.TestParameterValuesProvider() {
        public override fun provideValues(context: com.google.testing.junit.testparameterinjector.TestParameterValuesProvider.Context?): com.google.common.collect.ImmutableList<*> {
            return DigestHashFunction.getPossibleHashFunctions().asList()
        }
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun createDirectories() {
        testFS = getFreshFileSystem(digestHashFunction)
        workingDir = testFS.getPath(this.testTmpDir)
        cleanUpWorkingDirectory(workingDir)

        // % ls -lR
        // -rw-rw-r-- xFile
        // drwxrwxr-x xNonEmptyDirectory
        // -rw-rw-r-- xNonEmptyDirectory/foo
        // drwxrwxr-x xEmptyDirectory
        xNothing = absolutize("xNothing")
        xLink = absolutize("xLink")
        xFile = absolutize("xFile")
        xNonEmptyDirectory = absolutize("xNonEmptyDirectory")
        xFileInNonEmptyDirectory = xNonEmptyDirectory.getChild("foo")
        xEmptyDirectory = absolutize("xEmptyDirectory")

        FileSystemUtils.createEmptyFile(xFile)
        xNonEmptyDirectory.createDirectory()
        FileSystemUtils.createEmptyFile(xFileInNonEmptyDirectory)
        xEmptyDirectory.createDirectory()
    }

    @org.junit.After
    @Throws(java.lang.Exception::class)
    fun destroyFileSystem() {
        destroyFileSystem(testFS)
    }

    /** Removes all stuff from the test filesystem.  */
    @Throws(IOException::class)
    protected open fun destroyFileSystem(fileSystem: FileSystem) {
        com.google.common.base.Preconditions.checkArgument(fileSystem.equals(workingDir.getFileSystem()))
        cleanUpWorkingDirectory(workingDir)
    }

    /** Returns an instance of the file system to test.  */
    @Throws(IOException::class)
    protected abstract fun getFreshFileSystem(digestHashFunction: DigestHashFunction?): FileSystem

    /** Cleans up the working directory by removing everything.  */
    @Throws(IOException::class)
    protected fun cleanUpWorkingDirectory(workingPath: Path) {
        if (workingPath.exists()) {
            removeEntireDirectory(workingPath.getPathFile().toPath()) // uses java.nio.file.Path!
        }
        workingPath.createDirectoryAndParents()
    }

    /**
     * This function removes an entire directory and all of its contents. Much like rm -rf
     * directoryToRemove
     * 
     * 
     * This method explicitly only uses Java APIs to interact with files to prevent any issues with
     * Bazel's own file systems from leaking from one test to another.
     */
    @Throws(IOException::class)
    protected fun removeEntireDirectory(directoryToRemove: Path) {
        // make sure that we do not remove anything outside the test directory
        val testDirPath: Path? = testFS.getPath(this.testTmpDir)
        if (!testFS.getPath(directoryToRemove.toAbsolutePath().toString()).startsWith(testDirPath)) {
            throw IOException("trying to remove files outside of the testdata directory")
        }
        // Some tests change permissions on directories, so override them.
        java.nio.file.Files.setPosixFilePermissions(
            directoryToRemove,
            com.google.common.collect.Sets.union<PosixFilePermission?>(
                java.nio.file.Files.getPosixFilePermissions(directoryToRemove),
                com.google.common.collect.ImmutableSet.of<PosixFilePermission?>(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE
                )
            )
        )

        val entries: Array<Path>?
        java.nio.file.Files.list(directoryToRemove).use { entriesStream ->
            entries = entriesStream.toArray<Path?> { _Dummy_.__Array__() }
        }
        for (entry in entries!!) {
            val isSymbolicLink: Boolean = java.nio.file.Files.isSymbolicLink(entry)
            if (!isSymbolicLink && java.nio.file.Files.isDirectory(entry)) {
                removeEntireDirectory(entry)
            } else {
                java.nio.file.Files.delete(entry)
            }
        }
        java.nio.file.Files.delete(directoryToRemove)
    }

    /** Recursively make directories readable/executable and files readable.  */
    @Throws(IOException::class)
    protected fun makeTreeReadable(path: Path) {
        if (path.isDirectory(Symlinks.NOFOLLOW)) {
            path.setReadable(true)
            path.setExecutable(true)
            for (entry in path.getDirectoryEntries()) {
                makeTreeReadable(entry)
            }
        } else {
            path.setReadable(true)
        }
    }

    @get:Throws(IOException::class)
    protected val testTmpDir: String
        /**
         * Returns the directory to use as the FileSystem's working directory. Canonicalized to make tests
         * hermetic against symbolic links in TEST_TMPDIR.
         */
        get() = java.io.File(com.google.devtools.build.lib.testutil.TestUtils.tmpDir()).getCanonicalPath() + "/testdir"

    /**
     * Indirection to create links so we can test FileSystems that do not support link creation. For
     * example, JavaFileSystemTest overrides this method and creates the link with an alternate
     * FileSystem.
     */
    @Throws(IOException::class)
    protected fun createSymbolicLink(link: Path?, target: Path) {
        createSymbolicLink(link, target.asFragment())
    }

    /**
     * Indirection to create links so we can test FileSystems that do not support link creation. For
     * example, JavaFileSystemTest overrides this method and creates the link with an alternate
     * FileSystem.
     */
    @Throws(IOException::class)
    protected fun createSymbolicLink(link: Path, target: PathFragment?) {
        link.createSymbolicLink(target)
    }

    /**
     * Indirection to [Path.setExecutable] on FileSystems that do not support
     * setExecutable. For example, JavaFileSystemTest overrides this method and makes the Path
     * executable with an alternate FileSystem.
     */
    @Throws(IOException::class)
    protected fun setExecutable(target: Path, mode: Boolean) {
        target.setExecutable(mode)
    }

    // TODO(bazel-team): (2011) Put in a setLastModifiedTime into the various objects
    // and clobber the current time of the object we're currently handling.
    // Otherwise testing the thing might get a little hard, depending on the clock.
    fun storeReferenceTime(timeToMark: Long) {
        savedTime = timeToMark
    }

    fun isLaterThanreferenceTime(testTime: Long): Boolean {
        return (savedTime <= testTime)
    }

    protected fun absolutize(relativePathName: String?): Path {
        return workingDir.getRelative(relativePathName)
    }

    // Here the tests begin.
    @org.junit.Test
    fun testIsFileForNonexistingPath() {
        val nonExistingPath: Path = testFS.getPath("/something/strange")
        assertThat(nonExistingPath.isFile()).isFalse()
    }

    @org.junit.Test
    fun testIsDirectoryForNonexistingPath() {
        val nonExistingPath: Path = testFS.getPath("/something/strange")
        assertThat(nonExistingPath.isDirectory()).isFalse()
    }

    @org.junit.Test
    fun testIsLinkForNonexistingPath() {
        val nonExistingPath: Path = testFS.getPath("/something/strange")
        assertThat(nonExistingPath.isSymbolicLink()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExistsForNonexistingPath() {
        val nonExistingPath: Path = testFS.getPath("/something/strange")
        assertThat(nonExistingPath.exists()).isFalse()
        assertThat(nonExistingPath.statIfFound()).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    open fun testBadPermissionsThrowsExceptionOnStatIfFound() {
        val inaccessible: Path = absolutize("inaccessible")
        inaccessible.createDirectory()
        val child: Path = inaccessible.getChild("child")
        FileSystemUtils.createEmptyFile(child)
        inaccessible.setExecutable(false)
        assertThat(child.exists()).isFalse()
        org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { child.statIfFound() })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStatIfFoundReturnsNullForChildOfNonDir() {
        val foo: Path = absolutize("foo")
        foo.createDirectory()
        val nonDir: Path = foo.getRelative("bar")
        FileSystemUtils.createEmptyFile(nonDir)
        assertThat(nonDir.getRelative("file").statIfFound()).isNull()
    }

    // The following tests check the handling of the current working directory.
    @org.junit.Test
    fun testCreatePathRelativeToWorkingDirectory() {
        val relativeCreatedPath: Path = absolutize("some-file")
        val expectedResult: Path? = workingDir.getRelative(PathFragment.create("some-file"))

        assertThat(relativeCreatedPath).isEqualTo(expectedResult)
    }

    // The following tests check the handling of the root directory
    @org.junit.Test
    fun testRootIsDirectory() {
        val rootPath: Path = testFS.getPath("/")
        assertThat(rootPath.isDirectory()).isTrue()
    }

    @org.junit.Test
    fun testRootHasNoParent() {
        val rootPath: Path = testFS.getPath("/")
        assertThat(rootPath.getParentDirectory()).isNull()
    }

    // The following functions test the creation of files/links/directories.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileExists() {
        val someFile: Path = absolutize("some-file")
        FileSystemUtils.createEmptyFile(someFile)
        assertThat(someFile.exists()).isTrue()
        assertThat(someFile.statIfFound()).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileIsFile() {
        val someFile: Path = absolutize("some-file")
        FileSystemUtils.createEmptyFile(someFile)
        assertThat(someFile.isFile()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileIsNotDirectory() {
        val someFile: Path = absolutize("some-file")
        FileSystemUtils.createEmptyFile(someFile)
        assertThat(someFile.isDirectory()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileIsNotSymbolicLink() {
        val someFile: Path = absolutize("some-file")
        FileSystemUtils.createEmptyFile(someFile)
        assertThat(someFile.isSymbolicLink()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDirectoryExists() {
        val someDirectory: Path = absolutize("some-dir")
        someDirectory.createDirectory()
        assertThat(someDirectory.exists()).isTrue()
        assertThat(someDirectory.statIfFound()).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDirectoryIsDirectory() {
        val someDirectory: Path = absolutize("some-dir")
        someDirectory.createDirectory()
        assertThat(someDirectory.isDirectory()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDirectoryIsNotFile() {
        val someDirectory: Path = absolutize("some-dir")
        someDirectory.createDirectory()
        assertThat(someDirectory.isFile()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDirectoryIsNotSymbolicLink() {
        val someDirectory: Path = absolutize("some-dir")
        someDirectory.createDirectory()
        assertThat(someDirectory.isSymbolicLink()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicFileLinkExists() {
        val someLink: Path = absolutize("some-link")
        if (testFS.supportsSymbolicLinksNatively(someLink.asFragment())) {
            someLink.createSymbolicLink(xFile)
            assertThat(someLink.exists()).isTrue()
            assertThat(someLink.statIfFound()).isNotNull()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicFileLinkIsSymbolicLink() {
        val someLink: Path = absolutize("some-link")
        if (testFS.supportsSymbolicLinksNatively(someLink.asFragment())) {
            someLink.createSymbolicLink(xFile)
            assertThat(someLink.isSymbolicLink()).isTrue()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicFileLinkIsFile() {
        val someLink: Path = absolutize("some-link")
        if (testFS.supportsSymbolicLinksNatively(someLink.asFragment())) {
            someLink.createSymbolicLink(xFile)
            assertThat(someLink.isFile()).isTrue()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicFileLinkIsNotDirectory() {
        val someLink: Path = absolutize("some-link")
        if (testFS.supportsSymbolicLinksNatively(someLink.asFragment())) {
            someLink.createSymbolicLink(xFile)
            assertThat(someLink.isDirectory()).isFalse()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicDirLinkExists() {
        val someLink: Path = absolutize("some-link")
        if (testFS.supportsSymbolicLinksNatively(someLink.asFragment())) {
            someLink.createSymbolicLink(xEmptyDirectory)
            assertThat(someLink.exists()).isTrue()
            assertThat(someLink.statIfFound()).isNotNull()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicDirLinkIsSymbolicLink() {
        val someLink: Path = absolutize("some-link")
        if (testFS.supportsSymbolicLinksNatively(someLink.asFragment())) {
            someLink.createSymbolicLink(xEmptyDirectory)
            assertThat(someLink.isSymbolicLink()).isTrue()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicDirLinkIsDirectory() {
        val someLink: Path = absolutize("some-link")
        if (testFS.supportsSymbolicLinksNatively(someLink.asFragment())) {
            someLink.createSymbolicLink(xEmptyDirectory)
            assertThat(someLink.isDirectory()).isTrue()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicDirLinkIsNotFile() {
        val someLink: Path = absolutize("some-link")
        if (testFS.supportsSymbolicLinksNatively(someLink.asFragment())) {
            someLink.createSymbolicLink(xEmptyDirectory)
            assertThat(someLink.isFile()).isFalse()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testChildOfNonDirectory() {
        val somePath: Path = absolutize("file-name")
        FileSystemUtils.createEmptyFile(somePath)
        val childOfNonDir: Path = somePath.getChild("child")
        assertThat(childOfNonDir.exists()).isFalse()
        assertThat(childOfNonDir.statIfFound()).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateDirectoryIsEmpty() {
        val newPath: Path = xEmptyDirectory.getChild("new-dir")
        newPath.createDirectory()
        assertThat(newPath.getDirectoryEntries()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateDirectoryIsOnlyChildInParent() {
        val newPath: Path = xEmptyDirectory.getChild("new-dir")
        newPath.createDirectory()
        assertThat(newPath.getParentDirectory().getDirectoryEntries()).hasSize(1)
        assertThat(newPath.getParentDirectory().getDirectoryEntries()).containsExactly(newPath)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateDirectoryAndParents() {
        val newPath: Path = absolutize("new-dir/sub/directory")
        newPath.createDirectoryAndParents()
        assertThat(newPath.isDirectory()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateDirectoryAndParentsCreatesEmptyDirectory() {
        val newPath: Path = absolutize("new-dir/sub/directory")
        newPath.createDirectoryAndParents()
        assertThat(newPath.getDirectoryEntries()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateDirectoryAndParentsIsOnlyChildInParent() {
        val newPath: Path = absolutize("new-dir/sub/directory")
        newPath.createDirectoryAndParents()
        assertThat(newPath.getParentDirectory().getDirectoryEntries()).hasSize(1)
        assertThat(newPath.getParentDirectory().getDirectoryEntries()).containsExactly(newPath)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateDirectoryAndParentsWhenAlreadyExistsSucceeds() {
        val newPath: Path = absolutize("new-dir")
        newPath.createDirectory()
        newPath.createDirectoryAndParents()
        assertThat(newPath.isDirectory()).isTrue()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testCreateDirectoryAndParentsWhenAncestorIsFile() {
        val path: Path = absolutize("somewhere/deep/in")
        path.getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.createEmptyFile(path)
        val theHierarchy: Path = path.getChild("the-hierarchy")
        org.junit.Assert.assertThrows<IOException?>(IOException::class.java, theHierarchy::createDirectoryAndParents)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testCreateDirectoryAndParentsWhenSymlinkToDir() {
        val somewhereDeepIn: Path = absolutize("somewhere/deep/in")
        somewhereDeepIn.createDirectoryAndParents()
        val realDir: Path = absolutize("real/dir")
        realDir.createDirectoryAndParents()
        assertThat(realDir.isDirectory()).isTrue()
        val theHierarchy: Path = somewhereDeepIn.getChild("the-hierarchy")
        theHierarchy.createSymbolicLink(realDir)
        assertThat(theHierarchy.isDirectory()).isTrue()
        theHierarchy.createDirectoryAndParents()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testCreateDirectoryAndParentsWhenSymlinkEmbedded() {
        val somewhereDeepIn: Path = absolutize("somewhere/deep/in")
        somewhereDeepIn.createDirectoryAndParents()
        val realDir: Path = absolutize("real/dir")
        realDir.createDirectoryAndParents()
        val the: Path = somewhereDeepIn.getChild("the")
        the.createSymbolicLink(realDir)
        val theHierarchy: Path = somewhereDeepIn.getChild("hierarchy")
        theHierarchy.createDirectoryAndParents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateDirectoryAtFileFails() {
        val newPath: Path = absolutize("file")
        FileSystemUtils.createEmptyFile(newPath)
        org.junit.Assert.assertThrows<IOException?>(IOException::class.java, newPath::createDirectoryAndParents)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateEmptyFileIsEmpty() {
        val newPath: Path = xEmptyDirectory.getChild("new-file")
        FileSystemUtils.createEmptyFile(newPath)

        assertThat(newPath.getFileSize()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateFileIsOnlyChildInParent() {
        val newPath: Path = xEmptyDirectory.getChild("new-file")
        FileSystemUtils.createEmptyFile(newPath)
        assertThat(newPath.getParentDirectory().getDirectoryEntries()).hasSize(1)
        assertThat(newPath.getParentDirectory().getDirectoryEntries()).containsExactly(newPath)
    }

    // The following functions test the behavior if errors occur during the
    // creation of files/links/directories.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateDirectoryWhereDirectoryAlreadyExists() {
        assertThat(xEmptyDirectory.createDirectory()).isFalse()
    }

    @org.junit.Test
    fun testCreateDirectoryWhereFileAlreadyExists() {
        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { xFile.createDirectory() })
        Truth.assertThat(e).hasMessageThat().isEqualTo(xFile.toString() + " (File exists)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCannotCreateDirectoryWithoutExistingParent() {
        val newPath: Path = testFS.getPath("/deep/new-dir")
        val e: FileNotFoundException? =
            org.junit.Assert.assertThrows<FileNotFoundException?>(
                FileNotFoundException::class.java,
                org.junit.function.ThrowingRunnable { newPath.createDirectory() })
        Truth.assertThat(e).hasMessageThat().endsWith(" (No such file or directory)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCannotCreateDirectoryWithReadOnlyParent() {
        xEmptyDirectory.setWritable(false)
        val xChildOfReadonlyDir: Path = xEmptyDirectory.getChild("x")
        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { xChildOfReadonlyDir.createDirectory() })
        Truth.assertThat(e).hasMessageThat().endsWith(xChildOfReadonlyDir.toString() + " (Permission denied)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCannotCreateFileWithoutExistingParent() {
        val newPath: Path? = testFS.getPath("/non-existing-dir/new-file")
        val e: FileNotFoundException? =
            org.junit.Assert.assertThrows<FileNotFoundException?>(
                FileNotFoundException::class.java,
                org.junit.function.ThrowingRunnable { FileSystemUtils.createEmptyFile(newPath) })
        Truth.assertThat(e).hasMessageThat().endsWith(" (No such file or directory)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCannotCreateFileWithReadOnlyParent() {
        xEmptyDirectory.setWritable(false)
        val xChildOfReadonlyDir: Path? = xEmptyDirectory.getChild("x")
        val e: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { FileSystemUtils.createEmptyFile(xChildOfReadonlyDir) })
        Truth.assertThat(e).hasMessageThat().endsWith(xChildOfReadonlyDir.toString() + " (Permission denied)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCannotCreateFileWithinFile() {
        val newFilePath: Path = absolutize("some-file")
        FileSystemUtils.createEmptyFile(newFilePath)
        val wrongPath: Path = absolutize("some-file/new-file")
        val e: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { FileSystemUtils.createEmptyFile(wrongPath) })
        Truth.assertThat(e).hasMessageThat().endsWith(" (Not a directory)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCannotCreateDirectoryWithinFile() {
        val newFilePath: Path = absolutize("some-file")
        FileSystemUtils.createEmptyFile(newFilePath)
        val wrongPath: Path = absolutize("some-file/new-file")
        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { wrongPath.createDirectory() })
        Truth.assertThat(e).hasMessageThat().endsWith(" (Not a directory)")
    }

    // Test directory contents
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateMultipleChildren() {
        val theDirectory: Path = absolutize("foo/")
        theDirectory.createDirectory()
        val newPath1: Path = absolutize("foo/new-file-1")
        val newPath2: Path = absolutize("foo/new-file-2")
        val newPath3: Path = absolutize("foo/new-file-3")

        FileSystemUtils.createEmptyFile(newPath1)
        FileSystemUtils.createEmptyFile(newPath2)
        FileSystemUtils.createEmptyFile(newPath3)

        assertThat(theDirectory.getDirectoryEntries()).containsExactly(newPath1, newPath2, newPath3)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetDirectoryEntriesThrowsExceptionWhenRunOnFile() {
        val ex: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { xFile.getDirectoryEntries() })
        if (ex is FileNotFoundException) {
            org.junit.Assert.fail("The method should throw an object of class IOException.")
        }
        Truth.assertThat(ex).hasMessageThat().endsWith(xFile.toString() + " (Not a directory)")
    }

    @org.junit.Test
    fun testGetDirectoryEntriesThrowsExceptionForNonexistingPath() {
        val somePath: Path = testFS.getPath("/non-existing-path")
        val x: java.lang.Exception? = org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { somePath.getDirectoryEntries() })
        Truth.assertThat(x).hasMessageThat().endsWith(somePath.toString() + " (No such file or directory)")
    }

    // Test the removal of items
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeleteDirectory() {
        assertThat(xEmptyDirectory.delete()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeleteDirectoryIsNotDirectory() {
        xEmptyDirectory.delete()
        assertThat(xEmptyDirectory.isDirectory()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeleteDirectoryParentSize() {
        val parentSize: Int = workingDir.getDirectoryEntries().size()
        xEmptyDirectory.delete()
        Truth.assertThat(parentSize - 1).isEqualTo(workingDir.getDirectoryEntries().size())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeleteFile() {
        assertThat(xFile.delete()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeleteFileIsNotFile() {
        xFile.delete()
        assertThat(xEmptyDirectory.isFile()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeleteFileParentSize() {
        val parentSize: Int = workingDir.getDirectoryEntries().size()
        xFile.delete()
        Truth.assertThat(parentSize - 1).isEqualTo(workingDir.getDirectoryEntries().size())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeleteRemovesCorrectFile() {
        val newPath1: Path? = xEmptyDirectory.getChild("new-file-1")
        val newPath2: Path = xEmptyDirectory.getChild("new-file-2")
        val newPath3: Path? = xEmptyDirectory.getChild("new-file-3")

        FileSystemUtils.createEmptyFile(newPath1)
        FileSystemUtils.createEmptyFile(newPath2)
        FileSystemUtils.createEmptyFile(newPath3)

        assertThat(newPath2.delete()).isTrue()
        assertThat(xEmptyDirectory.getDirectoryEntries()).containsExactly(newPath1, newPath3)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeleteNonExistingDir() {
        val path: Path = xEmptyDirectory.getRelative("non-existing-dir")
        assertThat(path.delete()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeleteNotADirectoryPath() {
        val path: Path = xFile.getChild("new-file")
        assertThat(path.delete()).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testDeleteDoesNotFollowSymlink() {
        val file: Path = absolutize("file")
        val symlink: Path = absolutize("symlink")

        FileSystemUtils.createEmptyFile(file)
        symlink.createSymbolicLink(file)

        assertThat(symlink.delete()).isTrue()
        assertThat(symlink.exists(Symlinks.NOFOLLOW)).isFalse()
        assertThat(file.exists()).isTrue()
    }

    // Here we test the situations where delete should throw exceptions.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeleteNonEmptyDirectoryThrowsException() {
        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { xNonEmptyDirectory.delete() })
        Truth.assertThat(e).hasMessageThat().endsWith(xNonEmptyDirectory.toString() + " (Directory not empty)")
        assertThat(xNonEmptyDirectory.isDirectory()).isTrue()
        assertThat(xFileInNonEmptyDirectory.isFile()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeleteFileWithNonWritableParentDirectoryThrowsException() {
        xNonEmptyDirectory.chmod(365)
        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { xFileInNonEmptyDirectory.delete() })
        Truth.assertThat(e).hasMessageThat().endsWith(xFileInNonEmptyDirectory.toString() + " (Permission denied)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeleteFileWithNonExecutableParentDirectoryThrowsException() {
        xNonEmptyDirectory.chmod(438)
        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { xFileInNonEmptyDirectory.delete() })
        Truth.assertThat(e).hasMessageThat().endsWith(xFileInNonEmptyDirectory.toString() + " (Permission denied)")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testDeleteTreeDeletesNonEmptyDirectory() {
        val topDir: Path = absolutize("top-dir")
        val file1: Path = absolutize("top-dir/file-1")
        val file2: Path = absolutize("top-dir/file-2")
        val aDir: Path = absolutize("top-dir/a-dir")
        val file3: Path = absolutize("top-dir/a-dir/file-3")
        val file4: Path = absolutize("file-4")

        topDir.createDirectory()
        FileSystemUtils.createEmptyFile(file1)
        FileSystemUtils.createEmptyFile(file2)
        aDir.createDirectory()
        FileSystemUtils.createEmptyFile(file3)
        FileSystemUtils.createEmptyFile(file4)

        topDir.deleteTree()
        assertThat(file4.exists()).isTrue()
        assertThat(topDir.exists()).isFalse()
        assertThat(file1.exists()).isFalse()
        assertThat(file2.exists()).isFalse()
        assertThat(aDir.exists()).isFalse()
        assertThat(file3.exists()).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testDeleteTreeDeletesFile() {
        val file: Path = absolutize("file")
        FileSystemUtils.createEmptyFile(file)

        file.deleteTree()
        assertThat(file.exists()).isFalse()
    }

    private enum class DeleteFunc {
        DELETE_TREE,
        DELETE_TREES_BELOW
    }

    @Throws(IOException::class)
    private fun doTestDeleteUnreadableDirectories(deleteFunc: DeleteFunc) {
        val topDir: Path = absolutize("top-dir")
        val aDir: Path = absolutize("top-dir/a-dir")
        val file1: Path = absolutize("top-dir/a-dir/file1")
        val file2: Path = absolutize("top-dir/a-dir/file2")
        val bDir: Path = absolutize("top-dir/b-dir")
        val file3: Path = absolutize("top-dir/b-dir/file3")

        topDir.createDirectory()
        aDir.createDirectory()
        FileSystemUtils.createEmptyFile(file1)
        FileSystemUtils.createEmptyFile(file2)
        bDir.createDirectory()
        FileSystemUtils.createEmptyFile(file3)

        try {
            aDir.setReadable(false)
            bDir.setReadable(false)
            topDir.setReadable(false)
        } catch (e: java.lang.UnsupportedOperationException) {
            // Skip testing if the file system does not support clearing the needed attributes.
            return
        }

        when (deleteFunc) {
            DeleteFunc.DELETE_TREE -> {
                topDir.deleteTree()
                assertThat(topDir.exists()).isFalse()
            }

            DeleteFunc.DELETE_TREES_BELOW -> {
                topDir.deleteTreesBelow()
                makeTreeReadable(topDir)
                assertThat(topDir.exists()).isTrue()
                assertThat(FileSystemUtils.traverseTree(topDir, { unused -> true })).isEmpty()
            }
        }
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testDeleteTreeDeletesUnreadableDirectories() {
        doTestDeleteUnreadableDirectories(DeleteFunc.DELETE_TREE)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testDeleteTreesBelowDeletesUnreadableDirectories() {
        doTestDeleteUnreadableDirectories(DeleteFunc.DELETE_TREES_BELOW)
    }

    @Throws(IOException::class)
    private fun doTestDeleteUnwritableDirectories(deleteFunc: DeleteFunc) {
        val topDir: Path = absolutize("top-dir")
        val aDir: Path = absolutize("top-dir/a-dir")
        val file1: Path = absolutize("top-dir/a-dir/file1")
        val file2: Path = absolutize("top-dir/a-dir/file2")
        val bDir: Path = absolutize("top-dir/b-dir")
        val file3: Path = absolutize("top-dir/b-dir/file3")

        topDir.createDirectory()
        aDir.createDirectory()
        FileSystemUtils.createEmptyFile(file1)
        FileSystemUtils.createEmptyFile(file2)
        bDir.createDirectory()
        FileSystemUtils.createEmptyFile(file3)

        try {
            aDir.setWritable(false)
            bDir.setWritable(false)
            topDir.setWritable(false)
        } catch (e: java.lang.UnsupportedOperationException) {
            // Skip testing if the file system does not support clearing the needed attributes.
            return
        }

        when (deleteFunc) {
            DeleteFunc.DELETE_TREE -> {
                topDir.deleteTree()
                assertThat(topDir.exists()).isFalse()
            }

            DeleteFunc.DELETE_TREES_BELOW -> {
                topDir.deleteTreesBelow()
                makeTreeReadable(topDir)
                assertThat(topDir.exists()).isTrue()
                assertThat(FileSystemUtils.traverseTree(topDir, { unused -> true })).isEmpty()
            }
        }
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testDeleteTreeDeletesUnwritableDirectories() {
        doTestDeleteUnwritableDirectories(DeleteFunc.DELETE_TREE)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testDeleteTreesBelowDeletesUnwritableDirectories() {
        doTestDeleteUnwritableDirectories(DeleteFunc.DELETE_TREES_BELOW)
    }

    @Throws(IOException::class)
    private fun doTestDeleteReadableUnexecutableDirectories(deleteFunc: DeleteFunc) {
        val topDir: Path = absolutize("top-dir")
        val aDir: Path = absolutize("top-dir/a-dir")
        val file1: Path = absolutize("top-dir/a-dir/file1")
        val file2: Path = absolutize("top-dir/a-dir/file2")
        val bDir: Path = absolutize("top-dir/b-dir")
        val file3: Path = absolutize("top-dir/b-dir/file3")

        topDir.createDirectory()
        aDir.createDirectory()
        FileSystemUtils.createEmptyFile(file1)
        FileSystemUtils.createEmptyFile(file2)
        bDir.createDirectory()
        FileSystemUtils.createEmptyFile(file3)

        try {
            aDir.setExecutable(false)
            bDir.setExecutable(false)
            topDir.setExecutable(false)
        } catch (e: java.lang.UnsupportedOperationException) {
            // Skip testing if the file system does not support clearing the needed attributes.
            return
        }

        when (deleteFunc) {
            DeleteFunc.DELETE_TREE -> {
                topDir.deleteTree()
                assertThat(topDir.exists()).isFalse()
            }

            DeleteFunc.DELETE_TREES_BELOW -> {
                topDir.deleteTreesBelow()
                makeTreeReadable(topDir)
                assertThat(topDir.exists()).isTrue()
                assertThat(FileSystemUtils.traverseTree(topDir, { unused -> true })).isEmpty()
            }
        }
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testDeleteTreeDeletesReadableUnexecutableDirectories() {
        doTestDeleteReadableUnexecutableDirectories(DeleteFunc.DELETE_TREE)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testDeleteTreesBelowDeletesReadableUnexecutableDirectories() {
        doTestDeleteReadableUnexecutableDirectories(DeleteFunc.DELETE_TREES_BELOW)
    }

    @Throws(IOException::class)
    private fun doTestDeleteUnreadableUnexecutableDirectories(deleteFunc: DeleteFunc) {
        val topDir: Path = absolutize("top-dir")
        val aDir: Path = absolutize("top-dir/a-dir")
        val file1: Path = absolutize("top-dir/a-dir/file1")
        val file2: Path = absolutize("top-dir/a-dir/file2")
        val bDir: Path = absolutize("top-dir/b-dir")
        val file3: Path = absolutize("top-dir/b-dir/file3")

        topDir.createDirectory()
        aDir.createDirectory()
        FileSystemUtils.createEmptyFile(file1)
        FileSystemUtils.createEmptyFile(file2)
        bDir.createDirectory()
        FileSystemUtils.createEmptyFile(file3)

        try {
            aDir.setReadable(false)
            aDir.setExecutable(false)
            bDir.setReadable(false)
            bDir.setExecutable(false)
            topDir.setReadable(false)
            topDir.setExecutable(false)
        } catch (e: java.lang.UnsupportedOperationException) {
            // Skip testing if the file system does not support clearing the needed attributes.
            return
        }

        when (deleteFunc) {
            DeleteFunc.DELETE_TREE -> {
                topDir.deleteTree()
                assertThat(topDir.exists()).isFalse()
            }

            DeleteFunc.DELETE_TREES_BELOW -> {
                topDir.deleteTreesBelow()
                makeTreeReadable(topDir)
                assertThat(topDir.exists()).isTrue()
                assertThat(FileSystemUtils.traverseTree(topDir, { unused -> true })).isEmpty()
            }
        }
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testDeleteTreeDeletesUnreadableUnexecutableDirectories() {
        doTestDeleteUnreadableUnexecutableDirectories(DeleteFunc.DELETE_TREE)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testDeleteTreesBelowDeletesUnreadableUnexecutableDirectories() {
        doTestDeleteUnreadableUnexecutableDirectories(DeleteFunc.DELETE_TREES_BELOW)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testDeleteTreeDoesNotFollowInnerLinks() {
        val topDir: Path = absolutize("top-dir")
        val file: Path = absolutize("file")
        val outboundLink: Path = absolutize("top-dir/outbound-link")

        topDir.createDirectory()
        FileSystemUtils.createEmptyFile(file)
        outboundLink.createSymbolicLink(file)

        topDir.deleteTree()
        assertThat(file.exists()).isTrue()
        assertThat(topDir.exists()).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testDeleteTreeDoesNotFollowTopLink() {
        val topDir: Path = absolutize("top-dir")
        val file: Path = absolutize("file")

        FileSystemUtils.createEmptyFile(file)
        topDir.createSymbolicLink(file)

        topDir.deleteTree()
        assertThat(file.exists()).isTrue()
        assertThat(topDir.exists()).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testDeleteTreesBelowDeletesContentsOnly() {
        val topDir: Path = absolutize("top-dir")
        val file: Path = absolutize("top-dir/file")
        val subdir: Path = absolutize("top-dir/subdir")

        topDir.createDirectory()
        FileSystemUtils.createEmptyFile(file)
        subdir.createDirectory()

        topDir.deleteTreesBelow()
        assertThat(topDir.exists()).isTrue()
        assertThat(file.exists()).isFalse()
        assertThat(subdir.exists()).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testDeleteTreesBelowIgnoresMissingTopDir() {
        val topDir: Path = absolutize("top-dir")

        assertThat(topDir.exists()).isFalse()
        topDir.deleteTreesBelow() // Expect no exception.
        assertThat(topDir.exists()).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testDeleteTreesBelowIgnoresNonDirectories() {
        val topFile: Path = absolutize("top-file")

        FileSystemUtils.createEmptyFile(topFile)

        assertThat(topFile.exists()).isTrue()
        topFile.deleteTreesBelow() // Expect no exception.
        assertThat(topFile.exists()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeleteTreesBelowFailsGracefullyIfTreeGoesMissing() {
        val topDir: Path = absolutize("maybe-missing-dir")
        for (i in 0..999) {
            topDir.createDirectory()
            deleteTreesBelowRaceTest(topDir, topDir)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeleteTreesBelowFailsGracefullyIfContentsGoMissing() {
        val topDir: Path = absolutize("top-dir")
        val file: Path = absolutize("top-dir/maybe-missing-file")
        for (i in 0..999) {
            topDir.createDirectory()
            FileSystemUtils.createEmptyFile(file)
            deleteTreesBelowRaceTest(topDir, file)
        }
    }

    // Test the date functions
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSetLastModifiedTime_32bit() {
        val file: Path = absolutize("file")
        FileSystemUtils.createEmptyFile(file)

        file.setLastModifiedTime(1 shl 30)
        assertThat(file.getLastModifiedTime()).isEqualTo(1 shl 30)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSetLastModifiedTime_64bit() {
        val file: Path = absolutize("file")
        FileSystemUtils.createEmptyFile(file)

        file.setLastModifiedTime(1L shl 34)
        assertThat(file.getLastModifiedTime()).isEqualTo(1L shl 34)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSetLastModifiedTimeWithSentinel() {
        val file: Path = absolutize("file")
        FileSystemUtils.createEmptyFile(file)

        // To avoid sleeping, first set the modification time to the past.
        val pastTime: Long = Instant.now().minusSeconds(1).toEpochMilli()
        file.setLastModifiedTime(pastTime)

        // Even if we get the system time before the setLastModifiedTime call, getLastModifiedTime may
        // return a time which is slightly behind. Simply check that it's greater than the past time.
        file.setLastModifiedTime(Path.NOW_SENTINEL_TIME)
        assertThat(file.getLastModifiedTime()).isGreaterThan(pastTime)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateFileChangesTimeOfDirectory() {
        storeReferenceTime(workingDir.getLastModifiedTime())
        val newPath: Path = absolutize("new-file")
        FileSystemUtils.createEmptyFile(newPath)
        Truth.assertThat(isLaterThanreferenceTime(workingDir.getLastModifiedTime())).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRemoveFileChangesTimeOfDirectory() {
        val newPath: Path = absolutize("new-file")
        FileSystemUtils.createEmptyFile(newPath)
        storeReferenceTime(workingDir.getLastModifiedTime())
        newPath.delete()
        Truth.assertThat(isLaterThanreferenceTime(workingDir.getLastModifiedTime())).isTrue()
    }

    // This test is a little bit strange, as we cannot test the progression
    // of the time directly. As the Java time and the OS time are slightly different.
    // Therefore, we first create an unrelated file to get a notion
    // of the current OS time and use that as a baseline.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateFileTimestamp() {
        val syncFile: Path = absolutize("sync-file")
        FileSystemUtils.createEmptyFile(syncFile)

        val newFile: Path = absolutize("new-file")
        storeReferenceTime(syncFile.getLastModifiedTime())
        FileSystemUtils.createEmptyFile(newFile)
        Truth.assertThat(isLaterThanreferenceTime(newFile.getLastModifiedTime())).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateDirectoryTimestamp() {
        val syncFile: Path = absolutize("sync-file")
        FileSystemUtils.createEmptyFile(syncFile)

        val newPath: Path = absolutize("new-dir")
        storeReferenceTime(syncFile.getLastModifiedTime())
        assertThat(newPath.createDirectory()).isTrue()
        Truth.assertThat(isLaterThanreferenceTime(newPath.getLastModifiedTime())).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWriteChangesModifiedTime() {
        storeReferenceTime(xFile.getLastModifiedTime())
        FileSystemUtils.writeContentAsLatin1(xFile, "abc19")
        Truth.assertThat(isLaterThanreferenceTime(xFile.getLastModifiedTime())).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetLastModifiedTimeThrowsExceptionForNonexistingPath() {
        val newPath: Path = testFS.getPath("/non-existing-dir")
        val x: FileNotFoundException? =
            org.junit.Assert.assertThrows<FileNotFoundException?>(
                FileNotFoundException::class.java,
                org.junit.function.ThrowingRunnable { newPath.getLastModifiedTime() })
        Truth.assertThat(x).hasMessageThat().endsWith(newPath.toString() + " (No such file or directory)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetLastChangeTime() {
        val file: Path = absolutize("file")
        FileSystemUtils.createEmptyFile(file)

        // Expect the change time to be only slightly behind the current time.
        Truth.assertThat<java.time.Duration?>(
            java.time.Duration.between(Instant.ofEpochMilli(file.stat().lastChangeTime), Instant.now())
        )
            .isLessThan(java.time.Duration.ofSeconds(1))
    }

    // Test file size
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileSizeThrowsExceptionForNonexistingPath() {
        val newPath: Path = testFS.getPath("/non-existing-file")
        val e: FileNotFoundException? =
            org.junit.Assert.assertThrows<FileNotFoundException?>(
                FileNotFoundException::class.java,
                org.junit.function.ThrowingRunnable { newPath.getFileSize() })
        Truth.assertThat(e).hasMessageThat().endsWith(newPath.toString() + " (No such file or directory)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileSizeAfterWrite() {
        val testData = "abc19"

        FileSystemUtils.writeContentAsLatin1(xFile, testData)
        assertThat(xFile.getFileSize()).isEqualTo(testData.length)
    }

    // Testing the input/output routines
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileWriteAndReadAsLatin1() {
        val testData = "abc19"

        FileSystemUtils.writeContentAsLatin1(xFile, testData)
        val resultData = String(FileSystemUtils.readContentAsLatin1(xFile))

        Truth.assertThat(resultData).isEqualTo(testData)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInputAndOutputStreamEOF() {
        xFile.getOutputStream().use { outStream ->
            outStream.write(1)
        }
        xFile.getInputStream().use { inStream ->
            inStream.read()
            Truth.assertThat(inStream.read()).isEqualTo(-1)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInputAndOutputStream() {
        xFile.getOutputStream().use { outStream ->
            for (i in 33..125) {
                outStream.write(i)
            }
        }
        xFile.getInputStream().use { inStream ->
            for (i in 33..125) {
                val readValue: Int = inStream.read()
                Truth.assertThat(readValue).isEqualTo(i)
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInputStreamPermissionError() {
        assertThat(xFile.exists()).isTrue()
        xFile.setReadable(false)
        org.junit.Assert.assertThrows<T?>(
            FileAccessException::class.java,
            org.junit.function.ThrowingRunnable { xFile.getInputStream() })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputStreamPermissionError() {
        assertThat(xFile.exists()).isTrue()
        xFile.setWritable(false)
        org.junit.Assert.assertThrows<T?>(
            FileAccessException::class.java,
            org.junit.function.ThrowingRunnable { xFile.getOutputStream() })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateReadWriteByteChannelWrite(@TestParameter overwrite: Boolean) {
        val text = "hello"
        val file: Path = if (overwrite) xFile else xNothing
        FileSystemUtils.writeContent(xFile, java.nio.charset.StandardCharsets.UTF_8, "goodbye") // longer than hello
        file.createReadWriteByteChannel().use { channel ->
            writeToChannelAsLatin1(channel, text)
            Truth.assertThat(channel.position()).isEqualTo(text.length)
        }
        assertThat(FileSystemUtils.readContent(file, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("hello")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateReadWriteByteChannelWriteAfterSeek() {
        xNothing.createReadWriteByteChannel().use { channel ->
            writeToChannelAsLatin1(channel, "01234567890")
            channel.position(5)
            writeToChannelAsLatin1(channel, "hello!")
            Truth.assertThat(channel.position()).isEqualTo(5 + "hello!".length)
        }
        assertThat(
            FileSystemUtils.readContent(
                xNothing,
                java.nio.charset.StandardCharsets.ISO_8859_1
            )
        ).isEqualTo("01234hello!")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateReadWriteByteChannelSeek(@TestParameter("0", "5", "12") seekPosition: Int) {
        val text = "hello there!"
        xNothing.createReadWriteByteChannel().use { channel ->
            writeToChannelAsLatin1(channel, text)
            channel.position(seekPosition.toLong())
            Truth.assertThat(channel.position()).isEqualTo(seekPosition)
            val read = readAllAsString(channel, text.length - seekPosition)
            Truth.assertThat(channel.position()).isEqualTo(text.length)
            Truth.assertThat(read).isEqualTo(text.substring(seekPosition))
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateReadWriteByteChannelSeekHole(@TestParameter write: Boolean) {
        val text1 = "goodbye"
        val text2 = "and thanks for all the fish"
        xNothing.createReadWriteByteChannel().use { channel ->
            writeToChannelAsLatin1(channel, text1)
            channel.position((text1.length + 1).toLong())
            Truth.assertThat(channel.position()).isEqualTo(text1.length + 1)
            Truth.assertThat(channel.size()).isEqualTo(text1.length)
            Truth.assertThat(channel.read(java.nio.ByteBuffer.allocate(1))).isEqualTo(-1)
            if (write) {
                writeToChannelAsLatin1(channel, text2)
                Truth.assertThat(channel.position()).isEqualTo(text1.length + 1 + text2.length)
            }
        }
        assertThat(FileSystemUtils.readContent(xNothing, java.nio.charset.StandardCharsets.ISO_8859_1))
            .isEqualTo(if (write) text1 + "\u0000" + text2 else text1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateReadWriteByteChannelSeekNegative() {
        xNothing.createReadWriteByteChannel().use { channel ->
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable { channel.position(-1) })
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateReadWriteByteChannelTruncate(
        @TestParameter("0", "5", "12", "100") truncateSize: Int
    ) {
        val text = "hello there!"
        val expectedSize: Int = min(truncateSize, text.length)
        xNothing.createReadWriteByteChannel().use { channel ->
            writeToChannelAsLatin1(channel, text)
            channel.truncate(truncateSize.toLong())
            Truth.assertThat(channel.position()).isEqualTo(expectedSize)
            Truth.assertThat(channel.size()).isEqualTo(expectedSize)
            Truth.assertThat(channel.read(java.nio.ByteBuffer.allocate(1))).isEqualTo(-1)
        }
        assertThat(FileSystemUtils.readContent(xNothing, java.nio.charset.StandardCharsets.ISO_8859_1))
            .isEqualTo(text.substring(0, expectedSize))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateReadWriteByteChannelTruncateHole(@TestParameter shrink: Boolean) {
        val text = "hello"
        xNothing.createReadWriteByteChannel().use { channel ->
            writeToChannelAsLatin1(channel, text)
            channel.position((text.length + 5).toLong())
            Truth.assertThat(channel.position()).isEqualTo(text.length + 5)
            Truth.assertThat(channel.size()).isEqualTo(text.length)
            val truncateSize = if (shrink) text.length - 1 else text.length + 1
            channel.truncate(truncateSize.toLong())
            Truth.assertThat(channel.position()).isEqualTo(truncateSize)
            Truth.assertThat(channel.size()).isEqualTo(if (shrink) text.length - 1 else text.length)
        }
        assertThat(FileSystemUtils.readContent(xNothing, java.nio.charset.StandardCharsets.ISO_8859_1))
            .isEqualTo(if (shrink) "hell" else "hello")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateReadWriteByteChannelTruncateAndSeekToErase() {
        xNothing.createReadWriteByteChannel().use { channel ->
            writeToChannelAsLatin1(channel, "hello")
            channel.truncate(("hello".length - 1).toLong())
            channel.position("hello".length.toLong())
            writeToChannelAsLatin1(channel, "world")
        }
        assertThat(
            FileSystemUtils.readContent(
                xNothing,
                java.nio.charset.StandardCharsets.ISO_8859_1
            )
        ).isEqualTo("hell\u0000world")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateReadWriteByteChannelTruncateNegative() {
        xNothing.createReadWriteByteChannel().use { channel ->
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable { channel.truncate(-1) })
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInputAndOutputStreamAppend() {
        xFile.getOutputStream().use { outStream ->
            for (i in 33..125) {
                outStream.write(i)
            }
        }
        xFile.getOutputStream(true).use { appendOut ->
            for (i in 126..154) {
                appendOut.write(i)
            }
        }
        xFile.getInputStream().use { inStream ->
            for (i in 33..154) {
                val readValue: Int = inStream.read()
                Truth.assertThat(readValue).isEqualTo(i)
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInputAndOutputStreamNoAppend() {
        xFile.getOutputStream().use { outStream ->
            outStream.write(1)
        }
        xFile.getOutputStream(false).use { noAppendOut -> }
        xFile.getInputStream().use { inStream ->
            Truth.assertThat(inStream.read()).isEqualTo(-1)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputStreamConcurrentAppend() {
        xFile.getOutputStream(true).use { s1 ->
            xFile.getOutputStream(true).use { s2 ->
                s1.write("hello".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                s2.write("world".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            }
        }
        assertThat(FileSystemUtils.readContent(xFile, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("helloworld")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetOutputStreamCreatesFile() {
        val newFile: Path = absolutize("does_not_exist_yet.txt")

        newFile.getOutputStream().use { out ->
            out.write(42)
        }
        assertThat(newFile.isFile()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputStreamThrowExceptionOnDirectory() {
        val ex: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { xEmptyDirectory.getOutputStream() })
        Truth.assertThat(ex).hasMessageThat().endsWith(xEmptyDirectory.toString() + " (Is a directory)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInputStreamThrowExceptionOnDirectory() {
        val ex: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { xEmptyDirectory.getInputStream() })
        Truth.assertThat(ex).hasMessageThat().endsWith(xEmptyDirectory.toString() + " (Is a directory)")
    }

    // Test renaming
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCanRenameFileToUnusedName() {
        xFile.renameTo(xNothing)
        assertThat(xFile.exists()).isFalse()
        assertThat(xNothing.isFile()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCanRenameFileToExistingFile(@TestParameter existingFileIsWritable: Boolean) {
        val otherFile: Path = absolutize("otherFile")
        FileSystemUtils.createEmptyFile(otherFile)
        otherFile.setWritable(existingFileIsWritable)
        xFile.renameTo(otherFile) // succeeds
        assertThat(xFile.exists()).isFalse()
        assertThat(otherFile.isFile()).isTrue()
        assertThat(otherFile.isWritable()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCanRenameFileToExistingSymlink() {
        Assume.assumeTrue(testFS.supportsSymbolicLinksNatively(xLink.asFragment()))

        val symlink: Path = absolutize("symlink")
        createSymbolicLink(symlink, PathFragment.create("something"))
        xFile.renameTo(symlink) // succeeds
        assertThat(xFile.exists()).isFalse()
        assertThat(symlink.isFile(Symlinks.NOFOLLOW)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCanRenameFileToExistingSymlinkToNonWritableFile() {
        Assume.assumeTrue(testFS.supportsSymbolicLinksNatively(xLink.asFragment()))

        val nonWritableFile: Path = absolutize("non-writable-file")
        FileSystemUtils.touchFile(nonWritableFile)
        nonWritableFile.setWritable(false)
        val symlink: Path = absolutize("symlink")
        createSymbolicLink(symlink, nonWritableFile.asFragment())
        assertThat(symlink.isWritable()).isFalse()
        xFile.renameTo(symlink) // succeeds
        assertThat(xFile.exists()).isFalse()
        assertThat(symlink.isFile(Symlinks.NOFOLLOW)).isTrue()
        assertThat(symlink.isWritable()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCanRenameSymlinkToExistingFile(@TestParameter existingFileIsWritable: Boolean) {
        Assume.assumeTrue(testFS.supportsSymbolicLinksNatively(xLink.asFragment()))

        val symlink: Path = absolutize("symlink")
        createSymbolicLink(symlink, PathFragment.create("something"))
        xFile.setWritable(existingFileIsWritable)
        symlink.renameTo(xFile) // succeeds
        assertThat(symlink.exists(Symlinks.NOFOLLOW)).isFalse()
        assertThat(xFile.isSymbolicLink()).isTrue()
        assertThat(xFile.readSymbolicLink()).isEqualTo(PathFragment.create("something"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCanRenameSymlinkToExistingSymlink() {
        Assume.assumeTrue(testFS.supportsSymbolicLinksNatively(xLink.asFragment()))

        val symlink: Path = absolutize("symlink")
        createSymbolicLink(symlink, PathFragment.create("something"))
        val otherSymlink: Path = absolutize("otherSymlink")
        createSymbolicLink(otherSymlink, PathFragment.create("other"))
        symlink.renameTo(otherSymlink) // succeeds
        assertThat(symlink.exists(Symlinks.NOFOLLOW)).isFalse()
        assertThat(otherSymlink.isSymbolicLink()).isTrue()
        assertThat(otherSymlink.readSymbolicLink()).isEqualTo(PathFragment.create("something"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCanRenameDirToExistingEmptyDir() {
        xNonEmptyDirectory.renameTo(xEmptyDirectory) // succeeds
        assertThat(xNonEmptyDirectory.exists()).isFalse()
        assertThat(xEmptyDirectory.isDirectory()).isTrue()
        assertThat(xEmptyDirectory.getDirectoryEntries()).isNotEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCantRenameDirToExistingNonEmptyDir() {
        val e: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { xEmptyDirectory.renameTo(xNonEmptyDirectory) })
        Truth.assertThat(e).hasMessageThat().containsMatch("\\((File exists|Directory not empty)\\)$")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCantRenameDirToExistingNonEmptyDirNothingChanged() {
        org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { xEmptyDirectory.renameTo(xNonEmptyDirectory) })

        assertThat(xNonEmptyDirectory.isDirectory()).isTrue()
        assertThat(xEmptyDirectory.isDirectory()).isTrue()
        assertThat(xEmptyDirectory.getDirectoryEntries()).isEmpty()
        assertThat(xNonEmptyDirectory.getDirectoryEntries()).isNotEmpty()
    }

    @org.junit.Test
    fun testCantRenameDirToExistingFile() {
        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { xEmptyDirectory.renameTo(xFile) })
        Truth.assertThat(e)
            .hasMessageThat()
            .endsWith(xEmptyDirectory.toString() + " -> " + xFile + " (Not a directory)")
    }

    @org.junit.Test
    fun testCantRenameDirToExistingFileNothingChanged() {
        org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { xEmptyDirectory.renameTo(xFile) })

        assertThat(xEmptyDirectory.isDirectory()).isTrue()
        assertThat(xFile.isFile()).isTrue()
    }

    @org.junit.Test
    fun testCantRenameFileToExistingDir() {
        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { xFile.renameTo(xEmptyDirectory) })
        Truth.assertThat(e).hasMessageThat().endsWith(xFile.toString() + " -> " + xEmptyDirectory + " (Is a directory)")
    }

    @org.junit.Test
    fun testCantRenameFileToExistingDirNothingChanged() {
        org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { xFile.renameTo(xEmptyDirectory) })

        assertThat(xEmptyDirectory.isDirectory()).isTrue()
        assertThat(xFile.isFile()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCantRenameNonExistingFile() {
        val nonExistingPath: Path = absolutize("non-existing")
        val targetPath: Path = absolutize("does-not-matter")
        val e: FileNotFoundException? =
            org.junit.Assert.assertThrows<FileNotFoundException?>(
                FileNotFoundException::class.java,
                org.junit.function.ThrowingRunnable { nonExistingPath.renameTo(targetPath) })
        Truth.assertThat(e).hasMessageThat().endsWith(" (No such file or directory)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCantRenameIntoNonExistingDir() {
        val nonExistingPath: Path = absolutize("non-existing")
        val targetPath: Path? = nonExistingPath.getChild("does-not-matter")
        val e: FileNotFoundException? =
            org.junit.Assert.assertThrows<FileNotFoundException?>(
                FileNotFoundException::class.java,
                org.junit.function.ThrowingRunnable { xFile.renameTo(targetPath) })
        Truth.assertThat(e).hasMessageThat().endsWith(" (No such file or directory)")
    }

    // Test the Paths
    @org.junit.Test
    fun testGetPathOnlyAcceptsAbsolutePath() {
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { testFS.getPath("not-absolute") })
    }

    @org.junit.Test
    fun testGetPathOnlyAcceptsAbsolutePathFragment() {
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { testFS.getPath(PathFragment.create("not-absolute")) })
    }

    // Test the access permissions
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNewFilesAreWritable() {
        assertThat(xFile.isWritable()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNewFilesAreReadable() {
        assertThat(xFile.isReadable()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNewDirsAreWritable() {
        assertThat(xEmptyDirectory.isWritable()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNewDirsAreReadable() {
        assertThat(xEmptyDirectory.isReadable()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNewDirsAreExecutable() {
        assertThat(xEmptyDirectory.isExecutable()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCannotGetReadableOnNonexistingFile() {
        val ex: FileNotFoundException? =
            org.junit.Assert.assertThrows<FileNotFoundException?>(
                FileNotFoundException::class.java,
                org.junit.function.ThrowingRunnable { xNothing.isReadable() })
        Truth.assertThat(ex).hasMessageThat().endsWith(xNothing.toString() + " (No such file or directory)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCannotSetReadableOnNonexistingFile() {
        val ex: FileNotFoundException? =
            org.junit.Assert.assertThrows<FileNotFoundException?>(
                FileNotFoundException::class.java,
                org.junit.function.ThrowingRunnable { xNothing.setReadable(false) })
        Truth.assertThat(ex).hasMessageThat().endsWith(xNothing.toString() + " (No such file or directory)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCannotGetWritableOnNonexistingFile() {
        val ex: FileNotFoundException? =
            org.junit.Assert.assertThrows<FileNotFoundException?>(
                FileNotFoundException::class.java,
                org.junit.function.ThrowingRunnable { xNothing.isWritable() })
        Truth.assertThat(ex).hasMessageThat().endsWith(xNothing.toString() + " (No such file or directory)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCannotSetWritableOnNonexistingFile() {
        val ex: FileNotFoundException? =
            org.junit.Assert.assertThrows<FileNotFoundException?>(
                FileNotFoundException::class.java,
                org.junit.function.ThrowingRunnable { xNothing.setWritable(false) })
        Truth.assertThat(ex).hasMessageThat().endsWith(xNothing.toString() + " (No such file or directory)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCannotGetExecutableOnNonexistingFile() {
        val ex: FileNotFoundException? =
            org.junit.Assert.assertThrows<FileNotFoundException?>(
                FileNotFoundException::class.java,
                org.junit.function.ThrowingRunnable { xNothing.isExecutable() })
        Truth.assertThat(ex).hasMessageThat().endsWith(xNothing.toString() + " (No such file or directory)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCannotSetExecutableOnNonexistingFile() {
        val ex: FileNotFoundException? =
            org.junit.Assert.assertThrows<FileNotFoundException?>(
                FileNotFoundException::class.java,
                org.junit.function.ThrowingRunnable { xNothing.setExecutable(true) })
        Truth.assertThat(ex).hasMessageThat().endsWith(xNothing.toString() + " (No such file or directory)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSetReadableOnFile() {
        xFile.setReadable(false)
        assertThat(xFile.isReadable()).isFalse()
        xFile.setReadable(true)
        assertThat(xFile.isReadable()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSetWritableOnFile() {
        xFile.setWritable(false)
        assertThat(xFile.isWritable()).isFalse()
        xFile.setWritable(true)
        assertThat(xFile.isWritable()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSetExecutableOnFile() {
        xFile.setExecutable(true)
        assertThat(xFile.isExecutable()).isTrue()
        xFile.setExecutable(false)
        assertThat(xFile.isExecutable()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSetExecutableOnDirectory() {
        setExecutable(xNonEmptyDirectory, false)

        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { xFileInNonEmptyDirectory.isWritable() })
        Truth.assertThat(e).hasMessageThat().endsWith(" (Permission denied)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWritingToReadOnlyFileThrowsException() {
        xFile.setWritable(false)
        val e: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable {
                    FileSystemUtils.writeContent(
                        xFile,
                        "hello, world!".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
                    )
                })
        Truth.assertThat(e).hasMessageThat().endsWith(xFile.toString() + " (Permission denied)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReadingFromUnreadableFileThrowsException() {
        FileSystemUtils.writeContent(xFile, "hello, world!".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        xFile.setReadable(false)
        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { FileSystemUtils.readContent(xFile) })
        Truth.assertThat(e).hasMessageThat().endsWith(xFile.toString() + " (Permission denied)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCannotCreateFileInReadOnlyDirectory() {
        val xNonEmptyDirectoryBar: Path? = xNonEmptyDirectory.getChild("bar")
        xNonEmptyDirectory.setWritable(false)

        val e: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { FileSystemUtils.createEmptyFile(xNonEmptyDirectoryBar) })
        Truth.assertThat(e).hasMessageThat().endsWith(xNonEmptyDirectoryBar.toString() + " (Permission denied)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCannotCreateDirectoryInReadOnlyDirectory() {
        val xNonEmptyDirectoryBar: Path = xNonEmptyDirectory.getChild("bar")
        xNonEmptyDirectory.setWritable(false)

        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { xNonEmptyDirectoryBar.createDirectory() })
        Truth.assertThat(e).hasMessageThat().endsWith(xNonEmptyDirectoryBar.toString() + " (Permission denied)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCannotMoveIntoReadOnlyDirectory() {
        val xNonEmptyDirectoryBar: Path? = xNonEmptyDirectory.getChild("bar")
        xNonEmptyDirectory.setWritable(false)

        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { xFile.renameTo(xNonEmptyDirectoryBar) })
        Truth.assertThat(e).hasMessageThat().endsWith(" (Permission denied)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCannotMoveFromReadOnlyDirectory() {
        xNonEmptyDirectory.setWritable(false)

        val e: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { xFileInNonEmptyDirectory.renameTo(xNothing) })
        Truth.assertThat(e).hasMessageThat().endsWith(" (Permission denied)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCannotDeleteInReadOnlyDirectory() {
        xNonEmptyDirectory.setWritable(false)

        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { xFileInNonEmptyDirectory.delete() })
        Truth.assertThat(e).hasMessageThat().endsWith(xFileInNonEmptyDirectory.toString() + " (Permission denied)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCannotCreatSymbolicLinkInReadOnlyDirectory() {
        val xNonEmptyDirectoryBar: Path = xNonEmptyDirectory.getChild("bar")
        xNonEmptyDirectory.setWritable(false)

        if (testFS.supportsSymbolicLinksNatively(xNonEmptyDirectoryBar.asFragment())) {
            val e: IOException? =
                org.junit.Assert.assertThrows<IOException?>(
                    IOException::class.java,
                    org.junit.function.ThrowingRunnable {
                        createSymbolicLink(
                            xNonEmptyDirectoryBar,
                            xFileInNonEmptyDirectory
                        )
                    })
            Truth.assertThat(e).hasMessageThat().endsWith(xNonEmptyDirectoryBar.toString() + " (Permission denied)")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetDigestForEmptyFile() {
        val fp: Fingerprint = Fingerprint(digestHashFunction)
        fp.addBytes(ByteArray(0))
        assertThat(fp.hexDigestAndReset())
            .isEqualTo(com.google.common.io.BaseEncoding.base16().lowerCase().encode(xFile.getDigest()))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetDigest() {
        val buffer = ByteArray(500000)
        for (i in buffer.indices) {
            buffer[i] = 1
        }
        FileSystemUtils.writeContent(xFile, buffer)
        val fp: Fingerprint = Fingerprint(digestHashFunction)
        fp.addBytes(buffer)
        assertThat(fp.hexDigestAndReset())
            .isEqualTo(com.google.common.io.BaseEncoding.base16().lowerCase().encode(xFile.getDigest()))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStatFailsFastOnNonExistingFiles() {
        org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { xNothing.stat() })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStatNullableFailsFastOnNonExistingFiles() {
        assertThat(xNothing.statNullable()).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testResolveSymlinks() {
        Assume.assumeTrue(testFS.supportsSymbolicLinksNatively(xLink.asFragment()))

        createSymbolicLink(xLink, xFile)
        FileSystemUtils.createEmptyFile(xFile)
        assertThat(testFS.resolveOneLink(xLink.asFragment())).isEqualTo(xFile.asFragment())
        assertThat(xLink.resolveSymbolicLinks()).isEqualTo(xFile)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testResolveDanglingSymlinks() {
        Assume.assumeTrue(testFS.supportsSymbolicLinksNatively(xLink.asFragment()))

        createSymbolicLink(xLink, xNothing)
        assertThat(testFS.resolveOneLink(xLink.asFragment())).isEqualTo(xNothing.asFragment())
        org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { xLink.resolveSymbolicLinks() })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testResolveNonSymlinks() {
        assertThat(testFS.resolveOneLink(xFile.asFragment())).isNull()
        assertThat(xFile.resolveSymbolicLinks()).isEqualTo(xFile)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReaddir() {
        val dir: Path = workingDir.getChild("readdir")

        Assume.assumeTrue(testFS.supportsSymbolicLinksNatively(dir.asFragment()))

        dir.getChild("dir").createDirectoryAndParents()
        FileSystemUtils.createEmptyFile(dir.getChild("file"))
        dir.getChild("file_link").createSymbolicLink(dir.getChild("file"))
        dir.getChild("dir_link").createSymbolicLink(dir.getChild("dir"))
        dir.getChild("looping_link").createSymbolicLink(dir.getChild("looping_link"))
        dir.getChild("dangling_link").createSymbolicLink(testFS.getPath("/does_not_exist"))

        assertThat(dir.getDirectoryEntries())
            .containsExactly(
                dir.getChild("file"),
                dir.getChild("dir"),
                dir.getChild("file_link"),
                dir.getChild("dir_link"),
                dir.getChild("looping_link"),
                dir.getChild("dangling_link")
            )

        assertThat(dir.readdir(Symlinks.NOFOLLOW))
            .containsExactly(
                Dirent("file", Dirent.Type.FILE),
                Dirent("dir", Dirent.Type.DIRECTORY),
                Dirent("file_link", Dirent.Type.SYMLINK),
                Dirent("dir_link", Dirent.Type.SYMLINK),
                Dirent("looping_link", Dirent.Type.SYMLINK),
                Dirent("dangling_link", Dirent.Type.SYMLINK)
            )

        assertThat(dir.readdir(Symlinks.FOLLOW))
            .containsExactly(
                Dirent("file", Dirent.Type.FILE),
                Dirent("dir", Dirent.Type.DIRECTORY),
                Dirent("file_link", Dirent.Type.FILE),
                Dirent("dir_link", Dirent.Type.DIRECTORY),
                Dirent("looping_link", Dirent.Type.UNKNOWN),
                Dirent("dangling_link", Dirent.Type.UNKNOWN)
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateHardLink_success() {
        if (!testFS.supportsHardLinksNatively(xFile.asFragment())) {
            return
        }
        xFile.createHardLink(xLink)
        assertThat(xFile.exists()).isTrue()
        assertThat(xLink.exists()).isTrue()
        assertThat(xFile.isFile()).isTrue()
        assertThat(xLink.isFile()).isTrue()
        Truth.assertThat(isHardLinked(xFile, xLink)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateHardLink_neitherOriginalNorLinkExists() {
        if (!testFS.supportsHardLinksNatively(xFile.asFragment())) {
            return
        }

        /* Neither original file nor link file exists */
        xFile.delete()
        val expected: FileNotFoundException? =
            org.junit.Assert.assertThrows<FileNotFoundException?>(
                FileNotFoundException::class.java,
                org.junit.function.ThrowingRunnable { xFile.createHardLink(xLink) })
        Truth.assertThat(expected)
            .hasMessageThat()
            .isEqualTo("File \"xFile\" linked from \"xLink\" does not exist")
        assertThat(xFile.exists()).isFalse()
        assertThat(xLink.exists()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateHardLink_originalDoesNotExistAndLinkExists() {
        if (!testFS.supportsHardLinksNatively(xFile.asFragment())) {
            return
        }

        /* link file exists and original file does not exist */
        xFile.delete()
        FileSystemUtils.createEmptyFile(xLink)

        val expected: FileNotFoundException? =
            org.junit.Assert.assertThrows<FileNotFoundException?>(
                FileNotFoundException::class.java,
                org.junit.function.ThrowingRunnable { xFile.createHardLink(xLink) })
        Truth.assertThat(expected)
            .hasMessageThat()
            .isEqualTo("File \"xFile\" linked from \"xLink\" does not exist")
        assertThat(xFile.exists()).isFalse()
        assertThat(xLink.exists()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateHardLink_bothOriginalAndLinkExist() {
        if (!testFS.supportsHardLinksNatively(xFile.asFragment())) {
            return
        }
        /* Both original file and link file exist */
        FileSystemUtils.createEmptyFile(xLink)

        val expected: FileAlreadyExistsException? =
            org.junit.Assert.assertThrows<FileAlreadyExistsException?>(
                FileAlreadyExistsException::class.java,
                org.junit.function.ThrowingRunnable { xFile.createHardLink(xLink) })
        Truth.assertThat(expected).hasMessageThat().isEqualTo("New link file \"xLink\" already exists")
        assertThat(xFile.exists()).isTrue()
        assertThat(xLink.exists()).isTrue()
        Truth.assertThat(isHardLinked(xFile, xLink)).isFalse()
    }

    @Throws(IOException::class)
    protected open fun isHardLinked(a: Path, b: Path): Boolean {
        return (testFS.stat(a.asFragment(), false).nodeId
                === testFS.stat(b.asFragment(), false).nodeId)
    }

    @org.junit.Test
    fun testGetNioPath_basic() {
        val javaPath: Path = getJavaPathOrSkipIfUnsupported(xFile)
        Truth.assertThat(java.nio.file.Files.isRegularFile(javaPath)).isTrue()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testGetNioPath_externalUtf8() {
        assumeUtf8CompatibleEncoding()

        // Simulates a Starlark string constant, which is read from a presumably UTF-8 encoded source
        // file into Bazel's internal representation.
        val utf8File: Path = absolutize(StringEncoding.unicodeToInternal("some_dir/入力_A_🌱.txt"))
        utf8File.getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContent(utf8File, java.nio.charset.StandardCharsets.UTF_8, "hello 入力_A_🌱")

        val javaPath: Path = getJavaPathOrSkipIfUnsupported(utf8File)
        Truth.assertThat(java.nio.file.Files.isRegularFile(javaPath)).isTrue()
        Truth.assertThat(java.nio.file.Files.readString(javaPath)).isEqualTo("hello 入力_A_🌱")

        // Ensure that the view of the file as a directory entry is consistent with how it was created.
        assertThat(utf8File.getParentDirectory().getDirectoryEntries()).containsExactly(utf8File)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testGetNioPath_internalUtf8() {
        assumeUtf8CompatibleEncoding()

        val dirPath: Path = absolutize("some_dir")
        dirPath.createDirectoryAndParents()

        // Create a file through Java APIs.
        val javaDirPath: Path = getJavaPathOrSkipIfUnsupported(dirPath)
        java.nio.file.Files.writeString(javaDirPath.resolve(unicodeToPlatform("入力_A_🌱.txt")), "hello 入力_A_🌱")

        // Retrieve its path through the filesystem API.
        val entries: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            dirPath.getDirectoryEntries()
        assertThat(entries).hasSize(1)
        val filePath: Any? = com.google.common.collect.Iterables.getOnlyElement<Any?>(entries)
        assertThat(filePath.exists()).isTrue()

        // Verify the file content through the Java APIs.
        val javaFilePath: Path = getJavaPathOrSkipIfUnsupported(filePath)
        Truth.assertThat(java.nio.file.Files.isRegularFile(javaFilePath)).isTrue()
        Truth.assertThat(java.nio.file.Files.readString(javaFilePath)).isEqualTo("hello 入力_A_🌱")
    }

    protected fun getJavaPathOrSkipIfUnsupported(path: Path): Path {
        val javaPath: Path? = testFS.getNioPath(path.asFragment())
        val javaFile: java.io.File? = testFS.getIoFile(path.asFragment())

        Truth.assertThat(javaPath == null).isEqualTo(javaFile == null)
        Assume.assumeTrue(javaPath != null && javaFile != null)
        Truth.assertThat(javaFile.toPath()).isEqualTo(javaPath)

        return javaPath
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateTempDirectory() {
        val tempDirs: MutableSet<Path?> = HashSet<Path?>()
        for (i in 0..9) {
            val tempDir: Path = workingDir.createTempDirectory("prefix" + i)
            assertThat(tempDir.isDirectory()).isTrue()
            assertThat(tempDir.isReadable()).isTrue()
            assertThat(tempDir.isWritable()).isTrue()
            assertThat(tempDir.getBaseName()).startsWith("prefix" + i)
            Truth.assertThat(tempDirs).doesNotContain(tempDir)
            tempDirs.add(tempDir)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTypeViaReaddirCache(
        @TestParameter(
            "BUILD", "Å", "K", "Ａ", "ａ", "０", " 𝐀", "𝐴", "𝒜", "Ⅳ", "Ⓑ", "ẞ", "ß", "Ä", "İ", "ı"
        ) entry: String
    ) {
        assumeUtf8CompatibleEncoding()

        val normalizedEntry: String =
            java.text.Normalizer.normalize(entry, java.text.Normalizer.Form.NFC)
                .uppercase()
                .lowercase()
        validateGetTypeConsistency(workingDir, entry, normalizedEntry)
        validateGetTypeConsistency(workingDir, normalizedEntry, entry)
    }

    @Throws(IOException::class)
    private fun validateGetTypeConsistency(baseDir: Path, entryToCreate: String, entryToCheck: String) {
        val dir: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            baseDir.createTempDirectory("readdir_cache-")
        val pathToCreate: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            dir.getChild(StringEncoding.unicodeToInternal(entryToCreate))
        FileSystemUtils.createEmptyFile(pathToCreate)

        val syscallCache: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            DefaultSyscallCache.newBuilder().build()
        // Prime the cache by reading the parent directory.
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            syscallCache.readdir(dir)
        Truth.assertWithMessage("expecting entry %s to exist", entryToCreate)
            .that(syscallCache.getType(pathToCreate, Symlinks.FOLLOW))
            .isNotNull()

        val pathToCheck: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            dir.getChild(StringEncoding.unicodeToInternal(entryToCheck))
        val existsWithCache = syscallCache.getType(pathToCheck, Symlinks.FOLLOW) != null
        val existsWithoutCache = pathToCheck.statIfFound() != null
        Truth.assertWithMessage("created: %s", entryToCreate)
            .withMessage("checking: %s", entryToCheck)
            .withMessage("with cache: %s", existsWithCache)
            .withMessage("w/o cache : %s", existsWithoutCache)
            .that(existsWithCache)
            .isEqualTo(existsWithoutCache)
    }

    companion object {
        /**
         * Executes [FileSystem.deleteTreesBelow] on `topDir` and tries to race its execution
         * by deleting `fileToDelete` concurrently.
         */
        @Throws(java.lang.Exception::class)
        private fun deleteTreesBelowRaceTest(topDir: Path, fileToDelete: Path) {
            val latch: CountDownLatch = CountDownLatch(2)
            val wonRace: AtomicBoolean = AtomicBoolean(false)
            val t: java.lang.Thread =
                java.lang.Thread(
                    java.lang.Runnable {
                        try {
                            latch.countDown()
                            latch.await()
                            wonRace.compareAndSet(false, fileToDelete.delete())
                        } catch (e: IOException) {
                            // Don't care.
                        } catch (e: java.lang.InterruptedException) {
                        }
                    })
            t.start()
            try {
                try {
                    latch.countDown()
                    latch.await()
                    topDir.deleteTreesBelow()
                } finally {
                    t.join()
                }
                if (!wonRace.get()) {
                    assertThat(topDir.exists()).isTrue()
                }
            } catch (e: IOException) {
                if (wonRace.get()) {
                    Truth.assertThat(e).hasMessageThat().contains(fileToDelete.toString())
                    Truth.assertThat(e).hasMessageThat().contains("No such file")
                } else {
                    throw e
                }
            }
        }

        @Throws(IOException::class)
        private fun writeToChannelAsLatin1(channel: WritableByteChannel, text: String) {
            val bytes: ByteArray = text.toByteArray(java.nio.charset.StandardCharsets.ISO_8859_1)
            val buffer: java.nio.ByteBuffer = java.nio.ByteBuffer.wrap(bytes)
            var toWrite = bytes.size
            while (toWrite > 0) {
                toWrite -= channel.write(buffer)
            }
            Truth.assertThat(toWrite).isEqualTo(0)
            Truth.assertThat(buffer.remaining()).isEqualTo(0)
        }

        @Throws(IOException::class)
        private fun readAllAsString(channel: ReadableByteChannel, expectedSize: Int): String {
            com.google.common.base.Preconditions.checkArgument(
                expectedSize >= 0,
                "negative expected size: %s",
                expectedSize
            )
            // +1 to make sure we can observe EOF -- Channel::read will always return 0 for a full buffer.
            val buffer: java.nio.ByteBuffer = java.nio.ByteBuffer.allocate(expectedSize + 1)
            var totalRead = 0
            while (true) {
                val read: Int = channel.read(buffer)
                if (read == -1) {
                    Truth.assertThat(totalRead).isEqualTo(expectedSize)
                    return String(buffer.array(), 0, expectedSize, java.nio.charset.StandardCharsets.ISO_8859_1)
                }
                totalRead += read
                Truth.assertThat(buffer.position()).isEqualTo(totalRead)
            }
        }

        protected fun unicodeToPlatform(s: String?): String {
            return StringEncoding.internalToPlatform(StringEncoding.unicodeToInternal(s))
        }

        protected fun platformToUnicode(s: String?): String {
            return StringEncoding.internalToUnicode(StringEncoding.platformToInternal(s))
        }

        protected fun assumeUtf8CompatibleEncoding() {
            val sunJnuEncoding: java.nio.charset.Charset? =
                java.nio.charset.Charset.forName(java.lang.System.getProperty("sun.jnu.encoding"))
            TruthJUnit.assume().that(
                com.google.common.collect.ImmutableList.of<java.nio.charset.Charset?>(
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.charset.StandardCharsets.ISO_8859_1
                )
            ).contains(sunJnuEncoding)
        }
    }
}
