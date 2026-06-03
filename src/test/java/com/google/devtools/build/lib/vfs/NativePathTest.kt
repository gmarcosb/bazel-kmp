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

import com.google.devtools.build.lib.vfs.UnixGlob.FilesystemOps

/** Tests for [Path] in combination with the native file system for the current platform.  */
@RunWith(JUnit4::class)
class NativePathTest {
    private var fs: FileSystem? = null
    private var aDirectory: java.io.File? = null
    private var aFile: java.io.File? = null
    private var anotherFile: java.io.File? = null
    private var tmpDir: java.io.File? = null

    protected val nativeFileSystem: FileSystem
        get() = com.google.devtools.build.lib.vfs.util.FileSystems.getNativeFileSystem()

    @Before
    @Throws(java.lang.Exception::class)
    fun createFiles() {
        fs = this.nativeFileSystem
        tmpDir = java.io.File(com.google.devtools.build.lib.testutil.TestUtils.tmpDir(), "tmpDir")
        tmpDir.mkdirs()
        aDirectory = java.io.File(tmpDir, "a_directory")
        aDirectory.mkdirs()
        aFile = java.io.File(tmpDir, "a_file")
        FileOutputStream(aFile).close()
        anotherFile = java.io.File(aDirectory, "another_file.txt")
        FileOutputStream(anotherFile).close()
    }

    @org.junit.Test
    fun testExists() {
        assertThat(fs.getPath(aDirectory.getPath()).exists()).isTrue()
        assertThat(fs.getPath(aFile.getPath()).exists()).isTrue()
        assertThat(fs.getPath("/does/not/exist").exists()).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testDirectoryEntriesForDirectory() {
        assertThat(fs.getPath(tmpDir.getPath()).getDirectoryEntries()).containsExactly(
            fs.getPath(tmpDir.getPath() + "/a_file"),
            fs.getPath(tmpDir.getPath() + "/a_directory")
        )
    }

    @org.junit.Test
    fun testDirectoryEntriesForFileThrowsException() {
        org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { fs.getPath(aFile.getPath()).getDirectoryEntries() })
    }

    @org.junit.Test
    fun testIsFileIsTrueForFile() {
        assertThat(fs.getPath(aFile.getPath()).isFile()).isTrue()
    }

    @org.junit.Test
    fun testIsFileIsFalseForDirectory() {
        assertThat(fs.getPath(aDirectory.getPath()).isFile()).isFalse()
    }

    @org.junit.Test
    fun testBaseName() {
        assertThat(fs.getPath("/foo/base").getBaseName()).isEqualTo("base")
    }

    @org.junit.Test
    fun testBaseNameRunsAfterDotDotInterpretation() {
        assertThat(fs.getPath("/base/foo/..").getBaseName()).isEqualTo("base")
    }

    @org.junit.Test
    fun testIsDirectory() {
        assertThat(fs.getPath(aDirectory.getPath()).isDirectory()).isTrue()
        assertThat(fs.getPath(aFile.getPath()).isDirectory()).isFalse()
        assertThat(fs.getPath("/does/not/exist").isDirectory()).isFalse()
    }

    @org.junit.Test
    fun testListNonExistingDirectoryThrowsException() {
        org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { fs.getPath("/does/not/exist").getDirectoryEntries() })
    }

    private fun assertPathSet(actual: MutableCollection<Path>, vararg expected: String?) {
        val actualStrings: MutableList<String?> =
            com.google.common.collect.Lists.newArrayListWithCapacity<String?>(actual.size)

        for (path in actual) {
            actualStrings.add(path.getPathString())
        }

        Truth.assertThat(actualStrings).containsExactlyElementsIn(java.util.Arrays.asList<String?>(*expected))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlob() {
        val textFiles: MutableCollection<Path?>? =
            Builder(fs.getPath(tmpDir.getPath()), FilesystemOps.DIRECT)
                .addPattern("*/*.txt")
                .globInterruptible()
        Truth.assertThat(textFiles).hasSize(1)
        val onlyFile: Path? = textFiles!!.iterator().next()
        assertThat(onlyFile).isEqualTo(fs.getPath(anotherFile.getPath()))

        val onlyFiles: MutableCollection<Path> =
            Builder(fs.getPath(tmpDir.getPath()), FilesystemOps.DIRECT)
                .addPattern("*")
                .setPathDiscriminator(
                    TestUnixGlobPathDiscriminator(
                        java.util.function.Predicate { p: Path? -> true },
                        java.util.function.BiPredicate { p: Path?, isDir: Boolean? -> !isDir!! })
                )
                .globInterruptible()
        assertPathSet(onlyFiles, aFile.getPath())

        val directoriesToo: MutableCollection<Path> =
            Builder(fs.getPath(tmpDir.getPath()), FilesystemOps.DIRECT)
                .addPattern("*")
                .setPathDiscriminator(
                    TestUnixGlobPathDiscriminator(
                        java.util.function.Predicate { p: Path? -> true },
                        java.util.function.BiPredicate { p: Path?, isDir: Boolean? -> true })
                )
                .globInterruptible()
        assertPathSet(directoriesToo, aFile.getPath(), aDirectory.getPath())
    }

    @org.junit.Test
    fun testGetRelative() {
        val relative: Path? = fs.getPath("/foo").getChild("bar")
        val expected: Path? = fs.getPath("/foo/bar")
        assertThat(relative).isEqualTo(expected)
    }

    @org.junit.Test
    fun testEqualsAndHash() {
        val path: Path = fs.getPath("/foo/bar")
        val equalPath: Path? = fs.getPath("/foo/bar")
        val differentPath: Path? = fs.getPath("/foo/bar/baz")
        val differentType = Any()

        EqualsTester().addEqualityGroup(path, equalPath).testEquals()
        assertThat(path.equals(differentPath)).isFalse()
        assertThat(path.equals(differentType)).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testLatin1ReadAndWrite() {
        val allLatin1Chars = CharArray(256)
        for (i in 0..255) {
            allLatin1Chars[i] = i.toChar()
        }
        val path: Path? = fs.getPath(aFile.getPath())
        val latin1String = String(allLatin1Chars)
        FileSystemUtils.writeContentAsLatin1(path, latin1String)
        val fileContent = String(FileSystemUtils.readContentAsLatin1(path))
        Truth.assertThat(latin1String).isEqualTo(fileContent)
    }

    /**
     * Verify that the encoding implemented by [ ][com.google.devtools.build.lib.vfs.FileSystemUtils.writeContentAsLatin1] really is
     * 8859-1 (latin1).
     */
    @org.junit.Test
    @Throws(IOException::class)
    fun testVerifyLatin1() {
        val allLatin1Chars = CharArray(256)
        for (i in 0..255) {
            allLatin1Chars[i] = i.toChar()
        }
        val path: Path? = fs.getPath(aFile.getPath())
        val latin1String = String(allLatin1Chars)
        FileSystemUtils.writeContentAsLatin1(path, latin1String)
        val bytes: ByteArray = FileSystemUtils.readContent(path)
        Truth.assertThat(latin1String).isEqualTo(String(bytes, charset("ISO-8859-1")))
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testBytesReadAndWrite() {
        val bytes = byteArrayOf(
            -0x21524111.toByte(), (-0x21524111.toByte().toInt() shr 8).toByte(),
            (-0x21524111.toByte().toInt() shr 16).toByte(), (-0x21524111.toByte().toInt() shr 24).toByte()
        )
        val path: Path? = fs.getPath(aFile.getPath())
        FileSystemUtils.writeContent(path, bytes)
        val content: ByteArray = FileSystemUtils.readContent(path)
        Truth.assertThat(content).hasLength(bytes.size)
        for (i in bytes.indices) {
            Truth.assertThat<Byte?>(content[i]).isEqualTo(bytes[i])
        }
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testInputOutputStreams() {
        val path: Path = fs.getPath(aFile.getPath())
        path.getOutputStream().use { out ->
            for (i in 0..255) {
                out.write(i)
            }
        }
        path.getInputStream().use { `in` ->
            for (i in 0..255) {
                Truth.assertThat(`in`.read()).isEqualTo(i)
            }
            Truth.assertThat(`in`.read()).isEqualTo(-1)
        }
    }
}
