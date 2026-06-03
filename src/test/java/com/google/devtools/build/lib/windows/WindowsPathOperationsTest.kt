// Copyright 2017 The Bazel Authors. All rights reserved.
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
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.IOException
import java.nio.file.Paths

/** Tests for [WindowsPathOperations].  */
@RunWith(JUnit4::class)
@TestSpec(supportedOs = [com.google.devtools.build.lib.util.OS.WINDOWS])
class WindowsPathOperationsTest {
    private var scratchRoot: String? = null
    private var testUtil: WindowsTestUtil? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        scratchRoot = Paths.get(java.lang.System.getenv("TEST_TMPDIR")).toAbsolutePath().toString()
        testUtil = WindowsTestUtil(scratchRoot)
        cleanupScratchDir()
    }

    @org.junit.After
    @Throws(java.lang.Exception::class)
    fun cleanupScratchDir() {
        testUtil.deleteAllUnder("")
    }

    @org.junit.Test
    fun testShortNameMatcher() {
        assertThat(WindowsPathOperations.isShortPath("abc")).isFalse() // no ~ in the name
        assertThat(WindowsPathOperations.isShortPath("abc~")).isFalse() // no number after the ~
        assertThat(WindowsPathOperations.isShortPath("~abc")).isFalse() // no ~ followed by number
        assertThat(WindowsPathOperations.isShortPath("too_long_path")).isFalse() // too long for 8dot3
        assertThat(WindowsPathOperations.isShortPath("too_long_path~1"))
            .isFalse() // too long for 8dot3
        assertThat(WindowsPathOperations.isShortPath("abcd~1234")).isFalse() // too long for 8dot3
        assertThat(WindowsPathOperations.isShortPath("h~1")).isTrue()
        assertThat(WindowsPathOperations.isShortPath("h~12")).isTrue()
        assertThat(WindowsPathOperations.isShortPath("h~12.")).isTrue()
        assertThat(WindowsPathOperations.isShortPath("h~12.a")).isTrue()
        assertThat(WindowsPathOperations.isShortPath("h~12.abc")).isTrue()
        assertThat(WindowsPathOperations.isShortPath("h~123456")).isTrue()
        assertThat(WindowsPathOperations.isShortPath("hellow~1")).isTrue()
        assertThat(WindowsPathOperations.isShortPath("hellow~1.")).isTrue()
        assertThat(WindowsPathOperations.isShortPath("hellow~1.a")).isTrue()
        assertThat(WindowsPathOperations.isShortPath("hellow~1.abc")).isTrue()
        assertThat(WindowsPathOperations.isShortPath("hello~1.abcd")).isFalse() // too long for 8dot3
        assertThat(WindowsPathOperations.isShortPath("hellow~1.abcd")).isFalse() // too long for 8dot3
        assertThat(WindowsPathOperations.isShortPath("hello~12")).isTrue()
        assertThat(WindowsPathOperations.isShortPath("hello~12.")).isTrue()
        assertThat(WindowsPathOperations.isShortPath("hello~12.a")).isTrue()
        assertThat(WindowsPathOperations.isShortPath("hello~12.abc")).isTrue()
        assertThat(WindowsPathOperations.isShortPath("hello~12.abcd")).isFalse() // too long for 8dot3
        assertThat(WindowsPathOperations.isShortPath("hellow~12")).isFalse() // too long for 8dot3
        assertThat(WindowsPathOperations.isShortPath("hellow~12.")).isFalse() // too long for 8dot3
        assertThat(WindowsPathOperations.isShortPath("hellow~12.a")).isFalse() // too long for 8dot3
        assertThat(WindowsPathOperations.isShortPath("hellow~12.ab")).isFalse() // too long for 8dot3
        assertThat(WindowsPathOperations.isShortPath("~h~1")).isTrue()
        assertThat(WindowsPathOperations.isShortPath("~h~1.")).isTrue()
        assertThat(WindowsPathOperations.isShortPath("~h~1.a")).isTrue()
        assertThat(WindowsPathOperations.isShortPath("~h~1.abc")).isTrue()
        assertThat(WindowsPathOperations.isShortPath("~h~1.abcd")).isFalse() // too long for 8dot3
        assertThat(WindowsPathOperations.isShortPath("~h~12")).isTrue()
        assertThat(WindowsPathOperations.isShortPath("~h~12~1")).isTrue()
        assertThat(WindowsPathOperations.isShortPath("~h~12~1.")).isTrue()
        assertThat(WindowsPathOperations.isShortPath("~h~12~1.a")).isTrue()
        assertThat(WindowsPathOperations.isShortPath("~h~12~1.abc")).isTrue()
        assertThat(WindowsPathOperations.isShortPath("~h~12~1.abcd")).isFalse() // too long for 8dot3
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetLongPath() {
        val foo: java.io.File = testUtil.scratchDir("foo").toAbsolutePath().toFile()
        Truth.assertThat(foo.exists()).isTrue()
        assertThat(WindowsPathOperations.getLongPath(foo.getAbsolutePath())).endsWith("foo")

        val longPath = foo.getAbsolutePath() + "\\will.exist\\helloworld.txt"
        val shortPath = foo.getAbsolutePath() + "\\will~1.exi\\hellow~1.txt"

        // Assert that the long path resolution fails for non-existent file.
        Truth.assertThat(
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { WindowsPathOperations.getLongPath(longPath) })
        )
            .hasMessageThat()
            .contains("GetLongPathName")
        Truth.assertThat(
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { WindowsPathOperations.getLongPath(shortPath) })
        )
            .hasMessageThat()
            .contains("GetLongPathName")

        // Create the file, assert that long path resolution works and is correct.
        var helloFile: java.io.File =
            testUtil.scratchFile("foo/will.exist/helloworld.txt", "hello").toAbsolutePath().toFile()
        Truth.assertThat(helloFile.getAbsolutePath()).isEqualTo(longPath)
        Truth.assertThat(helloFile.exists()).isTrue()
        Truth.assertThat(java.io.File(longPath).exists()).isTrue()
        Truth.assertThat(java.io.File(shortPath).exists()).isTrue()
        assertThat(WindowsPathOperations.getLongPath(longPath)).endsWith("will.exist/helloworld.txt")
        assertThat(WindowsPathOperations.getLongPath(shortPath)).endsWith("will.exist/helloworld.txt")

        // Delete the file and the directory, assert that long path resolution fails for them.
        Truth.assertThat(helloFile.delete()).isTrue()
        Truth.assertThat(helloFile.getParentFile().delete()).isTrue()

        Truth.assertThat(
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { WindowsPathOperations.getLongPath(longPath) })
        )
            .hasMessageThat()
            .contains("GetLongPathName")

        Truth.assertThat(
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { WindowsPathOperations.getLongPath(shortPath) })
        )
            .hasMessageThat()
            .contains("GetLongPathName")

        // Create the directory and file with different names, but same 8dot3 names, assert that the
        // resolution is still correct.
        helloFile =
            testUtil
                .scratchFile("foo/will.exist_again/hellowelt.txt", "hello")
                .toAbsolutePath()
                .toFile()
        Truth.assertThat(helloFile.exists()).isTrue()
        Truth.assertThat(java.io.File(shortPath).exists()).isTrue()
        assertThat(WindowsPathOperations.getLongPath(shortPath))
            .endsWith("will.exist_again/hellowelt.txt")
        assertThat(WindowsPathOperations.getLongPath(foo.toString() + "\\will.exist_again\\hellowelt.txt"))
            .endsWith("will.exist_again/hellowelt.txt")

        Truth.assertThat(
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { WindowsPathOperations.getLongPath(longPath) })
        )
            .hasMessageThat()
            .contains("GetLongPathName")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetLongPathForFileSymlink() {
        testUtil.scratchFile("target.txt", "hello")
        testUtil.createSymlinks(
            com.google.common.collect.ImmutableMap.of<String?, String?>(
                "verylongname.txt",
                "target.txt"
            )
        )

        assertThat(WindowsPathOperations.getLongPath(Paths.get(scratchRoot, "verylo~1.txt").toString()))
            .endsWith("verylongname.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetLongPathForDirectoryJunction() {
        testUtil.createJunctions(
            com.google.common.collect.ImmutableMap.of<String?, String?>(
                "verylongname.dir",
                "target.dir"
            )
        )

        assertThat(WindowsPathOperations.getLongPath(Paths.get(scratchRoot, "verylo~1.dir").toString()))
            .endsWith("verylongname.dir")

        testUtil.scratchDir("target.dir")

        assertThat(WindowsPathOperations.getLongPath(Paths.get(scratchRoot, "verylo~1.dir").toString()))
            .endsWith("verylongname.dir")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetLongPathForIntermediateDirectoryJunction() {
        testUtil.createJunctions(
            com.google.common.collect.ImmutableMap.of<String?, String?>(
                "verylongname.dir",
                "target.dir"
            )
        )

        Truth.assertThat(
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable {
                    WindowsPathOperations.getLongPath(
                        Paths.get(scratchRoot, "verylo~1.dir/file.txt").toString()
                    )
                })
        )
            .hasMessageThat()
            .contains("GetLongPathName")

        testUtil.scratchFile("target.dir/file.txt", "hello")

        assertThat(
            WindowsPathOperations.getLongPath(
                Paths.get(scratchRoot, "verylo~1.dir/file.txt").toString()
            )
        )
            .endsWith("verylongname.dir/file.txt")
    }
}
