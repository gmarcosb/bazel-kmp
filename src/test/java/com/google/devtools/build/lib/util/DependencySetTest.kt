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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.vfs.FileSystem

@RunWith(JUnit4::class)
class DependencySetTest {
    private val scratch: Scratch = Scratch()
    private val fileSystem: FileSystem = scratch.getFileSystem()
    private val root: Path = scratch.resolve("/")

    private fun newDependencySet(): DependencySet {
        return DependencySet(root)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dotDParser_simple() {
        val file1: Path = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.cc")
        val file2: Path? = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.h")
        val filename = "hello.o"
        val dotd: Path = scratch.file(
            "/tmp/foo.d",
            filename + ": \\",
            " " + file1 + " \\",
            " " + file2 + " "
        )
        val depset: DependencySet = newDependencySet().read(dotd)
        assertThat(depset.getDependencies()).containsExactlyElementsIn(
            com.google.common.collect.Sets.newHashSet<E?>(
                file1,
                file2
            )
        )
        Truth.assertThat(filename).isEqualTo(depset.outputFileName)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dotDParser_simple_crlf() {
        val file1: Path = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.cc")
        val file2: Path? = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.h")
        val filename = "hello.o"
        val dotd: Path = scratch.file(
            "/tmp/foo.d",
            filename + ": \\\r",
            " " + file1 + " \\\r",
            " " + file2 + " "
        )
        val depset: DependencySet = newDependencySet().read(dotd)
        assertThat(depset.getDependencies()).containsExactlyElementsIn(
            com.google.common.collect.Sets.newHashSet<E?>(
                file1,
                file2
            )
        )
        Truth.assertThat(filename).isEqualTo(depset.outputFileName)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dotDParser_simple_cr() {
        val file1: Path = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.cc")
        val file2: Path? = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.h")
        val filename = "hello.o"
        val dotd: Path =
            scratch.file("/tmp/foo.d", filename + ": \\\r " + file1 + " \\\r " + file2 + " ")
        val depset: DependencySet = newDependencySet().read(dotd)
        assertThat(depset.getDependencies()).containsExactlyElementsIn(
            com.google.common.collect.Sets.newHashSet<E?>(
                file1,
                file2
            )
        )
        Truth.assertThat(filename).isEqualTo(depset.outputFileName)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dotDParser_leading_crlf() {
        val file1: Path = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.cc")
        val file2: Path? = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.h")
        val filename = "hello.o"
        val dotd: Path =
            scratch.file(
                "/tmp/foo.d",
                "\r\n" + filename + ": \\\r\n " + file1 + " \\\r\n " + file2 + " "
            )
        val depset: DependencySet = newDependencySet().read(dotd)
        assertThat(depset.getDependencies()).containsExactlyElementsIn(
            com.google.common.collect.Sets.newHashSet<E?>(
                file1,
                file2
            )
        )
        Truth.assertThat(filename).isEqualTo(depset.outputFileName)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dotDParser_oddFormatting() {
        val file1: Path = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.cc")
        val file2: Path? = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.h")
        val file3: Path? = fileSystem.getPath("/usr/local/blah/blah/genhello/other.h")
        val file4: Path? = fileSystem.getPath("/usr/local/blah/blah/genhello/onemore.h")
        val filename = "hello.o"
        val dotd: Path = scratch.file(
            "/tmp/foo.d",
            filename + ": " + file1 + " \\",
            " " + file2 + "\\",
            " " + file3 + " " + file4
        )
        val depset: DependencySet = newDependencySet().read(dotd)
        assertThat(depset.getDependencies())
            .containsExactlyElementsIn(com.google.common.collect.Sets.newHashSet<E?>(file1, file2, file3, file4))
        Truth.assertThat(filename).isEqualTo(depset.outputFileName)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dotDParser_relativeFilenames() {
        val file1: Path = root.getRelative("hello.cc")
        val file2: Path = root.getRelative("hello.h")
        val filename = "hello.o"
        val dotd: Path = scratch.file(
            "/tmp/foo.d",
            filename + ": \\",
            " " + file1.relativeTo(root) + " \\",
            " " + file2.relativeTo(root) + " "
        )
        val depset: DependencySet = newDependencySet().read(dotd)
        assertThat(depset.getDependencies()).containsExactlyElementsIn(
            com.google.common.collect.Sets.newHashSet<E?>(
                file1,
                file2
            )
        )
        Truth.assertThat(filename).isEqualTo(depset.outputFileName)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dotDParser_escapeDollar() {
        val dotd: Path =
            scratch.file(
                "/tmp/foo.d",
                "hello.o: \\",
                " /usr/local/blah/$\$blah/$\$hello.cc \\",
                " /usr/local/blah/blah/hel$$$\$lo.h \\",
                " /usr/local/blah/$\$blah/hello.h"
            )

        val expected: MutableSet<Path?> =
            com.google.common.collect.Sets.newHashSet<E?>(
                fileSystem.getPath("/usr/local/blah/\$blah/\$hello.cc"),
                fileSystem.getPath("/usr/local/blah/blah/hel$\$lo.h"),
                fileSystem.getPath("/usr/local/blah/\$blah/hello.h")
            )

        assertThat(newDependencySet().read(dotd).getDependencies()).containsExactlyElementsIn(expected)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dotDParser_emptyFile() {
        val dotd: Path = scratch.file("/tmp/empty.d")
        val depset: DependencySet = newDependencySet().read(dotd)
        val headers: MutableCollection<Path?> = depset.getDependencies()
        if (!headers.isEmpty()) {
            org.junit.Assert.fail("Not empty: " + headers.size + " " + headers)
        }
        assertThat(depset.outputFileName).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dotDParser_multipleTargets() {
        val file1: Path = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.cc")
        val file2: Path? = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.h")
        val dotd: Path = scratch.file(
            "/tmp/foo.d",
            "hello.o: \\",
            " " + file1,
            "hello2.o: \\",
            " " + file2
        )
        assertThat(newDependencySet().read(dotd).getDependencies())
            .containsExactlyElementsIn(com.google.common.collect.Sets.newHashSet<E?>(file1, file2))
    }

    /*
   * Regression test: if gcc fails to execute remotely, and we retry locally, then the behavior
   * of gcc's DEPENDENCIES_OUTPUT option is to append, not overwrite, the .d file. As a result,
   * during retry, a second stanza is written to the file.
   *
   * We handle this by merging all of the stanzas.
   */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dotDParser_duplicateStanza() {
        val file1: Path? = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.cc")
        val file2: Path? = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.h")
        val file3: Path? = fileSystem.getPath("/usr/local/blah/blah/genhello/other.h")
        val dotd: Path = scratch.file(
            "/tmp/foo.d",
            "hello.o: \\",
            " " + file1 + " \\",
            " " + file2 + " ",
            "hello.o: \\",
            " " + file1 + " \\",
            " " + file3 + " "
        )
        assertThat(newDependencySet().read(dotd).getDependencies())
            .containsExactly(file1, file1, file2, file3)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dotDParser_errorOnNoTrailingNewline() {
        val file1: Path? = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.cc")
        val dotd: Path = scratch.file("/tmp/foo.d")
        FileSystemUtils.writeContent(
            dotd, ("hello.o: \\\n " + file1).toByteArray(java.nio.charset.Charset.forName("UTF-8"))
        )
        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { newDependencySet().read(dotd) })
        Truth.assertThat(e).hasMessageThat().contains("File does not end in a newline")
    }

    /*
   * Test compatibility with --config=nvcc, which writes an extra space before the colon.
   */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dotDParser_spaceBeforeColon() {
        val file1: Path = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.cc")
        val file2: Path? = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.h")
        val filename = "hello.o"
        val dotd: Path = scratch.file(
            "/tmp/foo.d",
            filename + " : \\",
            " " + file1 + " \\",
            " " + file2 + " "
        )
        val depset: DependencySet = newDependencySet().read(dotd)
        assertThat(depset.getDependencies()).containsExactlyElementsIn(
            com.google.common.collect.Sets.newHashSet<E?>(
                file1,
                file2
            )
        )
        Truth.assertThat(filename).isEqualTo(depset.outputFileName)
    }

    /*
   * Bug-for-bug compatibility with --config=msvc, which writes malformed .d files.
   */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dotDParser_missingBackslash() {
        val file1: Path? = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.cc")
        val file2: Path? = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.h")
        val filename = "hello.o"
        val dotd: Path = scratch.file(
            "/tmp/foo.d",
            filename + ": ",
            " " + file1 + " \\",
            " " + file2 + " "
        )
        val depset: DependencySet = newDependencySet().read(dotd)
        assertThat(depset.getDependencies()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun writeSet() {
        val file1: Path? = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.cc")
        val file2: Path? = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.h")
        val file3: Path? = fileSystem.getPath("/usr/local/blah/blah/genhello/other.h")
        val filename = "/usr/local/blah/blah/genhello/hello.o"

        val depSet1: DependencySet = newDependencySet()
        depSet1.addDependencies(com.google.common.collect.ImmutableList.of<E?>(file1, file2, file3))
        depSet1.outputFileName = filename

        val outfile: Path = scratch.resolve(filename)
        val dotd: Path = scratch.resolve("/usr/local/blah/blah/genhello/hello.d")
        dotd.getParentDirectory().createDirectoryAndParents()
        depSet1.write(outfile, ".d")

        val dotdContents = String(FileSystemUtils.readContentAsLatin1(dotd))
        val expected =
            ("usr/local/blah/blah/genhello/hello.o:  \\\n"
                    + "  /usr/local/blah/blah/genhello/hello.cc \\\n"
                    + "  /usr/local/blah/blah/genhello/hello.h \\\n"
                    + "  /usr/local/blah/blah/genhello/other.h\n")
        Truth.assertThat(dotdContents).isEqualTo(expected)
        assertThat(depSet1.outputFileName).isEqualTo(filename)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun writeReadSet() {
        val filename = "/usr/local/blah/blah/genhello/hello.d"
        val file1: Path? = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.cc")
        val file2: Path? = fileSystem.getPath("/usr/local/blah/blah/genhello/hello.h")
        val file3: Path? = fileSystem.getPath("/usr/local/blah/blah/genhello/other.h")
        val depSet1: DependencySet = newDependencySet()
        depSet1.addDependencies(com.google.common.collect.ImmutableList.of<E?>(file1, file2, file3))
        depSet1.outputFileName = filename

        val dotd: Path = scratch.resolve(filename)
        dotd.getParentDirectory().createDirectoryAndParents()
        depSet1.write(dotd, ".d")

        val depSet2: DependencySet = newDependencySet().read(dotd)
        assertThat(depSet2).isEqualTo(depSet1)
        // due to how pic.d files are written, absolute paths are changed into relatives
        Truth.assertThat("/" + depSet2.outputFileName).isEqualTo(depSet1.outputFileName)
    }
}
