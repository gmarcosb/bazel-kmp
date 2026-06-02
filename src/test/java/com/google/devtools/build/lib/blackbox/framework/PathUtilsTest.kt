// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.blackbox.framework

import com.google.common.truth.Truth
import com.google.devtools.build.lib.util.OS
import com.google.devtools.build.lib.util.StringUtilities
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Test for [PathUtils].  */
@RunWith(JUnit4::class)
class PathUtilsTest {
    @Test
    @Throws(IOException::class)
    fun testDeleteTree() {
        val directory = Files.createTempDirectory("test")

        val subDir: Path = subDir(directory, "subdir")
        write(subDir, "file1.txt", "Hello!")
        write(subDir, "file2.txt", "I am here!")

        val innerDir: Path = subDir(subDir, "inner")
        write(innerDir, "inner.txt", "Deep")

        subDir(subDir, "empty")

        PathUtils.deleteTree(directory)

        Truth.assertThat(Files.exists(directory)).isFalse()
    }

    @Test
    @Throws(IOException::class)
    fun testCopyTree() {
        val source = Files.createTempDirectory("source")
        val target = Files.createTempDirectory("target")

        try {
            val subDir: Path = subDir(source, "subdir")
            write(subDir, "file1.txt", "Hello!")
            write(subDir, "file2.txt", "I am here!")

            val innerDir: Path = subDir(subDir, "inner")
            write(innerDir, "inner.txt", "Deep")

            subDir(subDir, "empty")

            PathUtils.copyTree(source, target)

            val targetSubdir: Path = resolveDirectory(target, "subdir")
            val targetInner: Path = resolveDirectory(targetSubdir, "inner")
            resolveDirectory(targetSubdir, "empty")

            assertFileExists(targetSubdir, "file1.txt", "Hello!")
            assertFileExists(targetSubdir, "file2.txt", "I am here!")

            assertFileExists(targetInner, "inner.txt", "Deep")
        } finally {
            PathUtils.deleteTree(source)
            PathUtils.deleteTree(target)
        }
    }

    @Test
    @Throws(IOException::class)
    fun testResolve() {
        val directory = Files.createTempDirectory("test")
        try {
            val expected = directory.resolve("subdir").resolve("inner").resolve("file.txt")
            val resolved = PathUtils.resolve(directory, "subdir", "inner", "file.txt")
            // can not use assertThat here, because Path implements Iterable and there is ambiguity
            // in overloaded methods resolution between assertThat(T) and assertThat(Iterable<T>)
            Assert.assertEquals(expected, resolved)
            Truth.assertThat(resolved.getFileName().toString()).isEqualTo("file.txt")
        } finally {
            PathUtils.deleteTree(directory)
        }
    }

    @Test
    @Throws(IOException::class)
    fun testCreateFile() {
        val directory = Files.createTempDirectory("test")
        try {
            val resolved = PathUtils.resolve(directory, "a", "b", "c")
            Truth.assertThat(Files.exists(resolved)).isFalse()

            PathUtils.createFile(resolved)

            Truth.assertThat(Files.exists(resolved)).isTrue()
            Truth.assertThat(Files.isRegularFile(resolved)).isTrue()
        } finally {
            PathUtils.deleteTree(directory)
        }
    }

    @Test
    @Throws(IOException::class)
    fun testCreateFileChained() {
        val directory = Files.createTempDirectory("test")
        try {
            val resolved = PathUtils.resolve(directory, "a", "b", "c")
            Truth.assertThat(Files.exists(resolved)).isFalse()

            val created = PathUtils.createFile(directory, "a/b/c")
            Truth.assertThat(Files.exists(created)).isTrue()
            Truth.assertThat(Files.isRegularFile(created)).isTrue()
            // can not use assertThat here, because Path implements Iterable and there is ambiguity
            // in overloaded methods resolution between assertThat(T) and assertThat(Iterable<T>)
            Assert.assertEquals(resolved, created)
        } finally {
            PathUtils.deleteTree(directory)
        }
    }

    @Test
    @Throws(IOException::class)
    fun testRewriteExistingFile() {
        val directory = Files.createTempDirectory("test")
        try {
            val file = PathUtils.createFile(directory, "file.txt")
            Truth.assertThat(Files.exists(file)).isTrue()
            Truth.assertThat(Files.isRegularFile(file)).isTrue()

            PathUtils.writeFile(file, "Variant1")
            val contents1 = PathUtils.readFile(file)
            Truth.assertThat(contents1).hasSize(1)
            Truth.assertThat(contents1.get(0)).isEqualTo("Variant1")

            PathUtils.writeFile(file, "Variant2")
            val contents2 = PathUtils.readFile(file)
            Truth.assertThat(contents2).hasSize(1)
            Truth.assertThat(contents2.get(0)).isEqualTo("Variant2")
        } finally {
            PathUtils.deleteTree(directory)
        }
    }

    @Test
    @Throws(IOException::class)
    fun testReadWriteAppend() {
        val directory = Files.createTempDirectory("test")
        try {
            val file = PathUtils.createFile(directory, "file.txt")
            PathUtils.writeFile(file, "line 1")
            Truth.assertThat(Files.exists(file)).isTrue()

            val linesBefore = PathUtils.readFile(file)
            Truth.assertThat(linesBefore).hasSize(1)
            Truth.assertThat(linesBefore.get(0)).isEqualTo("line 1")

            PathUtils.append(file, "line 2")

            val lines = PathUtils.readFile(file)
            Truth.assertThat(lines).hasSize(2)
            Truth.assertThat(lines.get(0)).isEqualTo("line 1")
            Truth.assertThat(lines.get(1)).isEqualTo("line 2")
        } finally {
            PathUtils.deleteTree(directory)
        }
    }

    @Test
    @Throws(IOException::class)
    fun testReplaceWithSymlinkContents() {
        // do not run the test for windows
        if (!OS.isPosixCompatible()) {
            return
        }

        val directory = Files.createTempDirectory("test")
        try {
            val target = PathUtils.createFile(directory, "source.txt")
            PathUtils.writeFile(target, "Target contents")
            val link = directory.resolve("link.txt")
            Files.createSymbolicLink(link, target)

            Truth.assertThat(Files.exists(link)).isTrue()
            Truth.assertThat(Files.isSymbolicLink(link)).isTrue()

            PathUtils.replaceWithSymlinkContents(link)
            Truth.assertThat(Files.isSymbolicLink(link)).isFalse()

            val lines = PathUtils.readFile(link)
            Truth.assertThat(lines).hasSize(1)
            Truth.assertThat(lines.get(0)).isEqualTo("Target contents")
        } finally {
            PathUtils.deleteTree(directory)
        }
    }

    companion object {
        @Throws(IOException::class)
        private fun assertFileExists(directory: Path, fileName: String?, text: String?) {
            val file = directory.resolve(fileName)
            Truth.assertThat(Files.exists(file)).isTrue()
            Truth.assertThat(Files.isRegularFile(file)).isTrue()
            Truth.assertThat(StringUtilities.joinLines(Files.readAllLines(file))).isEqualTo(text)
        }

        private fun resolveDirectory(directory: Path, directoryName: String?): Path {
            val subDir = directory.resolve(directoryName)
            Truth.assertThat(Files.exists(subDir)).isTrue()
            Truth.assertThat(Files.isDirectory(subDir)).isTrue()
            return subDir
        }

        @Throws(IOException::class)
        private fun subDir(directory: Path, directoryName: String?): Path {
            val subDir = directory.resolve(directoryName)
            Files.createDirectories(subDir)
            subDir.toFile().deleteOnExit()
            return subDir
        }

        @Throws(IOException::class)
        private fun write(directory: Path, fileName: String?, text: String?) {
            val file = directory.resolve(fileName)
            write(file, text)
        }

        @Throws(IOException::class)
        private fun write(path: Path, text: String?) {
            Truth.assertThat(path.toFile().createNewFile()).isTrue()
            Files.write(path, mutableSetOf<String?>(text))
            path.toFile().deleteOnExit()
        }
    }
}
