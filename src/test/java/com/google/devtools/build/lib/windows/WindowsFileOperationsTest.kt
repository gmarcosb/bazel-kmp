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

import com.google.common.truth.Truth
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.build.lib.testutil.TestSpec
import com.google.devtools.build.lib.windows.util.WindowsTestUtil
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.build
import net.starlark.java.syntax.Location.file
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Path
import java.util.HashMap

/** Unit tests for [WindowsFileOperations].  */
@RunWith(JUnit4::class)
@TestSpec(supportedOs = [com.google.devtools.build.lib.util.OS.WINDOWS])
class WindowsFileOperationsTest {
    private var scratchRoot: String? = null
    private var testUtil: WindowsTestUtil? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        scratchRoot = java.io.File(java.lang.System.getenv("TEST_TMPDIR"), "x").getAbsolutePath()
        testUtil = WindowsTestUtil(scratchRoot)
        cleanupScratchDir()
    }

    @org.junit.After
    @Throws(java.lang.Exception::class)
    fun cleanupScratchDir() {
        testUtil.deleteAllUnder("")
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
    fun testSymlinkCreation() {
        val helloFile: java.io.File = testUtil.scratchFile("file.txt", "hello").toFile()
        val symlinkFile: java.io.File = java.io.File(scratchRoot, "symlink")
        testUtil.createSymlinks(com.google.common.collect.ImmutableMap.of<String?, String?>("symlink", "file.txt"))

        assertThat(WindowsFileOperations.isSymlinkOrJunction(symlinkFile.toString())).isTrue()
        Truth.assertThat(symlinkFile.exists()).isTrue()

        // Assert deleting the symlink does not remove the target file.
        assertThat(WindowsFileOperations.deletePath(symlinkFile.toString())).isTrue()
        Truth.assertThat(helloFile.exists()).isTrue()
        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable { WindowsFileOperations.isSymlinkOrJunction(symlinkFile.toString()) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinkCreationFailsForDirectory() {
        testUtil.scratchDir("dir").toFile()

        try {
            testUtil.createSymlinks(com.google.common.collect.ImmutableMap.of<String?, String?>("symlink", "dir"))
            org.junit.Assert.fail("Expected to throw: Symlinks to a directory should fail.")
        } catch (e: IOException) {
            Truth.assertThat(e).hasMessageThat().contains("target is a directory")
        }
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

        assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\shrtpath\\a")).isTrue()
        assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\shrtpath\\b")).isTrue()
        assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\shrtpath\\c")).isTrue()
        assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\longlinkpath\\a")).isTrue()
        assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\longlinkpath\\b")).isTrue()
        assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\longlinkpath\\c")).isTrue()
        assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\longli~1\\a")).isTrue()
        assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\longli~1\\b")).isTrue()
        assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\longli~1\\c")).isTrue()
        assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\abbreviated\\a")).isTrue()
        assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\abbreviated\\b")).isTrue()
        assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\abbreviated\\c")).isTrue()
        assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\abbrev~1\\a")).isTrue()
        assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\abbrev~1\\b")).isTrue()
        assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\abbrev~1\\c")).isTrue()
        assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\control\\a")).isFalse()
        assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\control\\b")).isFalse()
        assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\control\\c")).isFalse()
        assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\shrttrgt\\file1.txt")).isFalse()
        assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\longtargetpath\\file2.txt"))
            .isFalse()
        assertThat(WindowsFileOperations.isSymlinkOrJunction(root + "\\longta~1\\file2.txt")).isFalse()
        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable { WindowsFileOperations.isSymlinkOrJunction(root + "\\non-existent") })
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
        assertThat(WindowsFileOperations.isSymlinkOrJunction(linkPath.getAbsolutePath())).isTrue()

        Truth.assertThat(helloPath.toFile().delete()).isTrue()
        Truth.assertThat(helloPath.getParent().toFile().delete()).isTrue()
        Truth.assertThat(helloPath.getParent().toFile().exists()).isFalse()
        Truth.assertThat(java.util.Arrays.asList<String?>(*linkPath.getParentFile().list())).containsExactly("link")

        assertThat(WindowsFileOperations.isSymlinkOrJunction(linkPath.getAbsolutePath())).isTrue()
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
        val helloFile: java.io.File =
            testUtil.scratchFile("target\\helloworld.txt", "hello").toAbsolutePath().toFile()

        // Assert that a file is identified as not a junction.
        val longPath: String? = helloFile.getAbsolutePath()
        val shortPath: String? = java.io.File(helloFile.getParentFile(), "hellow~1.txt").getAbsolutePath()
        assertThat(WindowsFileOperations.isSymlinkOrJunction(longPath)).isFalse()
        assertThat(WindowsFileOperations.isSymlinkOrJunction(shortPath)).isFalse()

        // Assert that after deleting the file and creating a junction with the same path, it is
        // identified as a junction.
        Truth.assertThat(helloFile.delete()).isTrue()
        testUtil.createJunctions(
            com.google.common.collect.ImmutableMap.of<String?, String?>(
                "target\\helloworld.txt",
                "target"
            )
        )
        assertThat(WindowsFileOperations.isSymlinkOrJunction(longPath)).isTrue()
        assertThat(WindowsFileOperations.isSymlinkOrJunction(shortPath)).isTrue()

        // Assert that after deleting the file and creating a directory with the same path, it is
        // identified as not a junction.
        Truth.assertThat(helloFile.delete()).isTrue()
        Truth.assertThat(helloFile.mkdir()).isTrue()
        assertThat(WindowsFileOperations.isSymlinkOrJunction(longPath)).isFalse()
        assertThat(WindowsFileOperations.isSymlinkOrJunction(shortPath)).isFalse()
    }
}
