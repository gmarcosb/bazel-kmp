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
package com.google.devtools.build.lib.remote

import com.google.devtools.build.lib.vfs.DigestHashFunction

/** Tests for [PathCanonicalizer].  */
@RunWith(JUnit4::class)
class PathCanonicalizerTest {
    // Test outline:
    // 1. Set up the filesystem state by calling createSymlink, createNonSymlink or deleteTree.
    // 2. Call assertSuccess or assertFailure to check for successful resolution or failure.
    // On Windows, absolute paths start with a drive letter, e.g. C:/, instead of / as in Unix.
    // To avoid test duplication, when the tests run on Windows, Unix-style absolute paths passed to
    // the above methods will have a C: automatically prepended to them.
    private val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)

    private val canonicalizer: PathCanonicalizer =
        PathCanonicalizer({ pathFragment: PathFragment? -> this.resolve(pathFragment) })

    @Throws(IOException::class)
    private fun resolve(pathFragment: PathFragment?): PathFragment? {
        val path: Path = fs.getPath(pathFragment)
        try {
            return path.readSymbolicLink()
        } catch (e: NotASymlinkException) {
            return null
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRoot() {
        assertSuccess("/", "/")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAlreadyCanonical() {
        createNonSymlink("/a/b")
        assertSuccess("/a/b", "/a/b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAbsoluteSymlinkToFile() {
        createSymlink("/a/b", "/c/d")
        createNonSymlink("/c/d")
        assertSuccess("/a/b", "/c/d")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAbsoluteSymlinkToDirectory() {
        createSymlink("/a/b", "/d/e")
        createNonSymlink("/d/e/c")
        assertSuccess("/a/b/c", "/d/e/c")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAbsoluteSymlinkToDifferentDrive() {
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS)
        createSymlink("C:/a/b", "D:/e/f")
        createNonSymlink("D:/e/f/c")
        assertSuccess("C:/a/b/c", "D:/e/f/c")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRelativeSymlinkToFileInSameDirectory() {
        createSymlink("/a/b", "c")
        createNonSymlink("/a/c")
        assertSuccess("/a/b", "/a/c")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRelativeSymlinkToFileInDirectoryBelow() {
        createSymlink("/a/b", "c/d")
        createNonSymlink("/a/c/d")
        assertSuccess("/a/b", "/a/c/d")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRelativeSymlinkToFileInDirectoryAbove() {
        createSymlink("/a/b/c", "../d/e")
        createNonSymlink("/a/d/e")
        assertSuccess("/a/b/c", "/a/d/e")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRelativeSymlinkToRoot() {
        createSymlink("/a/b/c", "../../d")
        createNonSymlink("/d")
        assertSuccess("/a/b/c", "/d")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRelativeSymlinkWithTooManyUplevelReferences() {
        createSymlink("/a/b", "../../d")
        createNonSymlink("/d/c")
        assertSuccess("/a/b/c", "/d/c")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultipleSymlinks() {
        createSymlink("/a", "/b")
        createSymlink("/b/c", "/d")
        createSymlink("/d/e", "/f")
        createNonSymlink("/f")
        assertSuccess("/a/c/e", "/f")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReplayCanonical() {
        createNonSymlink("/a/b/c")
        assertSuccess("/a/b/c", "/a/b/c")
        assertSuccess("/a/b/c", "/a/b/c")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReplaySymlink() {
        createSymlink("/a/b", "/d")
        createNonSymlink("/d/c")
        assertSuccess("/a/b/c", "/d/c")
        assertSuccess("/a/b/c", "/d/c")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDistinguishPathsWithCommonPrefix() {
        createSymlink("/a/b", "/d")
        createNonSymlink("/d/c")
        createNonSymlink("/a/e")
        assertSuccess("/a/b/c", "/d/c")
        assertSuccess("/a/e", "/a/e")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDistinguishPathsWithDifferentDriveLetter() {
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS)
        createSymlink("C:/a/b", "D:/d")
        createNonSymlink("D:/d/c")
        createNonSymlink("D:/a/b/c")
        assertSuccess("C:/a/b/c", "D:/d/c")
        assertSuccess("D:/a/b/c", "D:/a/b/c")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testClearAndReplaceWithSymlink() {
        createNonSymlink("/a/b/c")
        assertSuccess("/a/b/c", "/a/b/c")
        deleteTree("/a/b")
        createSymlink("/a/b", "/d")
        createNonSymlink("/d/c")
        assertSuccess("/a/b/c", "/d/c")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testClearAndReplaceWithNonSymlink() {
        createSymlink("/a/b", "/d")
        createNonSymlink("/d/c")
        assertSuccess("/a/b/c", "/d/c")
        deleteTree("/a/b")
        createNonSymlink("/a/b/c")
        assertSuccess("/a/b/c", "/a/b/c")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testClearSymlinkAndDoNotReplace() {
        createSymlink("/a/b", "/d")
        createNonSymlink("/d/c")
        assertSuccess("/a/b/c", "/d/c")
        deleteTree("/a/b")
        assertFailure<FileNotFoundException?>(FileNotFoundException::class.java, "/a/b/c")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testClearNonSymlinkAndDoNotReplace() {
        createNonSymlink("/a/b/c")
        assertSuccess("/a/b/c", "/a/b/c")
        deleteTree("/a/b")
        assertFailure<FileNotFoundException?>(FileNotFoundException::class.java, "/a/b/c")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testClearUnknownPathDescendingFromSymlink() {
        createSymlink("/a/b", "/d")
        createNonSymlink("/d")
        assertSuccess("/a/b", "/d")
        deleteTree("/a/b/c")
        assertSuccess("/a/b", "/d")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testClearUnknownPathDescendingFromNonSymlink() {
        createNonSymlink("/a/b")
        assertSuccess("/a/b", "/a/b")
        deleteTree("/a/b/c")
        assertSuccess("/a/b", "/a/b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testClearPathDescendingThroughSymlinkInvalidatesIt() {
        createSymlink("/a/b", "/d")
        createNonSymlink("/d/c")
        assertSuccess("/a/b/c", "/d/c")

        fs.getPath(pathFragment("/a/b")).delete()
        createNonSymlink("/a/b/c")
        canonicalizer.clearPrefix(pathFragment("/a/b/c"))

        assertSuccess("/a/b/c", "/a/b/c")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinkSelfLoop() {
        createSymlink("/a/b", "/a/b")
        assertFailure<T?>(FileSymlinkLoopException::class.java, "/a/b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinkMutualLoop() {
        createSymlink("/a/b", "/c/d")
        createSymlink("/c/d", "/a/b")
        assertFailure<T?>(FileSymlinkLoopException::class.java, "/a/b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinkChainTooLong() {
        for (i in 0..<FileSystem.MAX_SYMLINKS + 1) {
            createSymlink(String.format("/%s", i), String.format("/%s", i + 1))
        }
        assertFailure<T?>(FileSymlinkLoopException::class.java, "/0")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileNotFound() {
        assertFailure<FileNotFoundException?>(FileNotFoundException::class.java, "/a/b")
        createNonSymlink("/a/b")
        assertSuccess("/a/b", "/a/b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmpty() {
        assertFailure<java.lang.IllegalArgumentException?>(java.lang.IllegalArgumentException::class.java, "")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonAbsolute() {
        assertFailure<java.lang.IllegalArgumentException?>(java.lang.IllegalArgumentException::class.java, "a/b")
    }

    @Throws(java.lang.Exception::class)
    private fun createSymlink(linkPathStr: String, targetPathStr: String) {
        val linkPath: Path = fs.getPath(pathFragment(linkPathStr))
        linkPath.getParentDirectory().createDirectoryAndParents()
        linkPath.createSymbolicLink(pathFragment(targetPathStr))
    }

    @Throws(java.lang.Exception::class)
    private fun createNonSymlink(pathStr: String) {
        val path: Path = fs.getPath(pathFragment(pathStr))
        path.getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContent(path, java.nio.charset.StandardCharsets.UTF_8, "")
    }

    @Throws(java.lang.Exception::class)
    private fun deleteTree(pathStr: String) {
        canonicalizer.clearPrefix(pathFragment(pathStr))
        fs.getPath(pathFragment(pathStr)).deleteTree()
    }

    @Throws(java.lang.Exception::class)
    private fun assertSuccess(input: String, output: String) {
        assertThat(canonicalizer.resolveSymbolicLinks(pathFragment(input)))
            .isEqualTo(pathFragment(output))
    }

    @Throws(java.lang.Exception::class)
    private fun <T : Throwable?> assertFailure(exceptionClass: java.lang.Class<T?>, input: String) {
        org.junit.Assert.assertThrows<T?>(exceptionClass, org.junit.function.ThrowingRunnable {
            canonicalizer.resolveSymbolicLinks(
                pathFragment(input)
            )
        })
    }

    companion object {
        private fun pathFragment(pathStr: String): PathFragment {
            var pathStr = pathStr
            if (pathStr.startsWith("/") && com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS) {
                pathStr = "C:" + pathStr
            }
            return PathFragment.create(pathStr)
        }
    }
}
