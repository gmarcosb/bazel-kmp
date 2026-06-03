// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.testing.common

import com.google.devtools.build.lib.testing.common.DirectoryListingHelper.directory

/** Unit tests for [com.google.devtools.build.lib.testing.common.DirectoryListingHelper].  */
@RunWith(JUnit4::class)
class DirectoryListingHelperTest {
    private val scratch: Scratch = Scratch()
    private val root: Path? = scratch.getFileSystem().getPath("/")

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun leafDirectoryEntries_emptyDirectory_returnsEmptyList() {
        assertThat(leafDirectoryEntries(root)).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun leafDirectoryEntries_returnsFile() {
        scratch.file("/file")
        assertThat(leafDirectoryEntries(root)).containsExactly(file("file"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun leafDirectoryEntries_fileInSubfolders_returnsFileOnly() {
        scratch.file("/dir1/dir2/file")
        assertThat(leafDirectoryEntries(root)).containsExactly(file("dir1/dir2/file"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun leafDirectoryEntries_returnsEmptyDirectory() {
        scratch.dir("/dir")
        assertThat(leafDirectoryEntries(root)).containsExactly(directory("dir"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun leafDirectoryEntries_mixedEmptyDirectoriesAndFiles_returnsAllEntries() {
        scratch.dir("/dir/empty1")
        scratch.dir("/dir/subdir/empty2")
        scratch.file("/dir2/file3")
        scratch.file("/dir2/file4")

        assertThat(leafDirectoryEntries(root))
            .containsExactly(
                directory("dir/empty1"),
                directory("dir/subdir/empty2"),
                file("dir2/file3"),
                file("dir2/file4")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun leafDirectoryEntries_returnsEntriesUnderProvidedPathOnly() {
        scratch.file("/dir/file1")
        scratch.file("/dir2/file2")
        val dir: Path = scratch.dir("/dir")

        assertThat(leafDirectoryEntries(dir)).containsExactly(file("file1"))
    }

    @org.junit.Test
    fun leafDirectoryEntries_missingDirectory_fails() {
        val nonexistent: Path? = scratch.getFileSystem().getPath("/nonexistent")
        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable { leafDirectoryEntries(nonexistent) })
    }
}
