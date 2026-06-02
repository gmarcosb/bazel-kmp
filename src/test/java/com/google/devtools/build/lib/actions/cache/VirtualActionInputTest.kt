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
package com.google.devtools.build.lib.actions.cache

import com.google.devtools.build.lib.vfs.DigestHashFunction.SHA256

@RunWith(TestParameterInjector::class)
class VirtualActionInputTest {
    @org.junit.Rule
    var tempFolder: TemporaryFolder = TemporaryFolder()

    enum class FileSystemType {
        IN_MEMORY,
        JAVA,
        NATIVE;

        fun getFileSystem(): FileSystem {
            return when (this) {
                FileSystemType.IN_MEMORY -> InMemoryFileSystem(SHA256)
                FileSystemType.JAVA -> JavaIoFileSystem(SHA256)
                FileSystemType.NATIVE -> com.google.devtools.build.lib.vfs.util.FileSystems.getNativeFileSystem()
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAtomicallyWriteRelativeTo(@TestParameter fileSystemType: FileSystemType) {
        val fs: FileSystem = fileSystemType.getFileSystem()
        val execRoot: Path = fs.getPath(tempFolder.getRoot().getPath())

        val outputFile: Path = execRoot.getRelative("some/file")
        val input: VirtualActionInput =
            createVirtualActionInput(
                outputFile.relativeTo(execRoot).getPathString(), "hello"
            )

        val digest: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            input.atomicallyWriteRelativeTo(execRoot)

        assertThat(outputFile.getParentDirectory().readdir(Symlinks.NOFOLLOW))
            .containsExactly(Dirent("file", Dirent.Type.FILE))
        assertThat(
            FileSystemUtils.readLines(
                outputFile,
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).containsExactly("hello")
        assertThat(outputFile.isExecutable()).isTrue()
        assertThat(digest).isEqualTo(
            SHA256.getHashFunction().hashString("hello", java.nio.charset.StandardCharsets.UTF_8).asBytes()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAtomicallyWriteRelativeTo_concurrentRead(
        @TestParameter fileSystemType: FileSystemType
    ) {
        val fs: FileSystem = fileSystemType.getFileSystem()
        val execRoot: Path = fs.getPath(tempFolder.getRoot().getPath())

        val outputFile: Path = execRoot.getRelative("some/file")
        val input: VirtualActionInput =
            createVirtualActionInput(
                outputFile.relativeTo(execRoot).getPathString(), "hello"
            )

        input.atomicallyWriteRelativeTo(execRoot)
        val digest: ByteArray?
        val bytes: ByteArray?
        outputFile.getInputStream().use { `in` ->
            digest = input.atomicallyWriteRelativeTo(execRoot)
            bytes = `in`.readAllBytes()
        }
        assertThat(outputFile.getParentDirectory().readdir(Symlinks.NOFOLLOW))
            .containsExactly(Dirent("file", Dirent.Type.FILE))
        assertThat(
            FileSystemUtils.readLines(
                outputFile,
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).containsExactly("hello")
        assertThat(outputFile.isExecutable()).isTrue()
        Truth.assertThat(digest)
            .isEqualTo(SHA256.getHashFunction().hashString("hello", java.nio.charset.StandardCharsets.UTF_8).asBytes())
        Truth.assertThat(bytes).isEqualTo("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
}
