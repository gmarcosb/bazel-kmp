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
package com.google.devtools.build.lib.bazel.repository.decompressor

import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.vfs.DigestHashFunction
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import java.nio.charset.StandardCharsets

/** Tests for [PatchUtil].  */
@RunWith(JUnit4::class)
class PatchUtilTest {
    private val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
    private val scratch: Scratch = Scratch(fs, "/root")
    private var root: Path? = null

    @Before
    @Throws(Exception::class)
    fun createRoot() {
        root = scratch.dir("/root")
    }

    @Test
    @Throws(IOException::class, PatchFailedException::class)
    fun testAddFile() {
        val patchFile: Path =
            scratch.file(
                "/root/patchfile",
                "diff --git a/newfile b/newfile",
                "new file mode 100544",
                "index 0000000..f742c88",
                "--- /dev/null",
                "+++ b/newfile",
                "@@ -0,0 +1,2 @@",
                "+I'm a new file",
                "+hello, world",
                "-- ",
                "2.21.0.windows.1"
            )
        PatchUtil.apply(patchFile, 1, root)
        val newFile: Path = root.getRelative("newfile")
        val newFileContent = ImmutableList.of<String?>("I'm a new file", "hello, world")
        assertThat(FileSystemUtils.readLines(newFile, StandardCharsets.UTF_8)).isEqualTo(newFileContent)
        // Make sure file permission is set as specified.
        assertThat(newFile.isReadable()).isTrue()
        assertThat(newFile.isWritable()).isFalse()
        assertThat(newFile.isExecutable()).isTrue()
    }

    @Test
    @Throws(IOException::class, PatchFailedException::class)
    fun testAddOneLineFile() {
        val patchFile: Path =
            scratch.file(
                "/root/patchfile",
                "diff --git a/newfile b/newfile",
                "new file mode 100644",
                "index 0000000..f742c88",
                "--- /dev/null",
                "+++ b/newfile",
                "@@ -0,0 +1 @@",  // diff will produce such chunk header for one line file.
                "+hello, world"
            )
        PatchUtil.apply(patchFile, 1, root)
        val newFile: Path? = root.getRelative("newfile")
        val newFileContent = ImmutableList.of<String?>("hello, world")
        assertThat(FileSystemUtils.readLines(newFile, StandardCharsets.UTF_8)).isEqualTo(newFileContent)
    }

    @Test
    @Throws(IOException::class, PatchFailedException::class)
    fun testDeleteFile() {
        val oldFile: Path = scratch.file("/root/oldfile", "I'm an old file", "bye, world")
        val patchFile: Path =
            scratch.file(
                "/root/patchfile",
                "--- a/oldfile",
                "+++ /dev/null",
                "@@ -1,2 +0,0 @@",
                "-I'm an old file",
                "-bye, world"
            )
        PatchUtil.apply(patchFile, 1, root)
        assertThat(oldFile.exists()).isFalse()
    }

    @Test
    @Throws(IOException::class, PatchFailedException::class)
    fun testDeleteOneLineFile() {
        val oldFile: Path = scratch.file("/root/oldfile", "bye, world")
        val patchFile: Path =
            scratch.file(
                "/root/patchfile",
                "--- a/oldfile",
                "+++ /dev/null",
                "@@ -1 +0,0 @@",  // diff will produce such chunk header for one line file.
                "-bye, world"
            )
        PatchUtil.apply(patchFile, 1, root)
        assertThat(oldFile.exists()).isFalse()
    }

    @Test
    @Throws(IOException::class, PatchFailedException::class)
    fun testDeleteAllContentButNotFile() {
        // If newfile is not /dev/null, we don't delete the file even it's empty after patching,
        // this is the behavior of patch command line tool.
        val oldFile: Path = scratch.file("/root/oldfile", "I'm an old file", "bye, world")
        val patchFile: Path =
            scratch.file(
                "/root/patchfile",
                "--- a/oldfile",
                "+++ b/oldfile",
                "@@ -1,2 +0,0 @@",
                "-I'm an old file",
                "-bye, world"
            )
        PatchUtil.apply(patchFile, 1, root)
        assertThat(oldFile.exists()).isTrue()
        assertThat(FileSystemUtils.readLines(oldFile, StandardCharsets.UTF_8)).isEmpty()
    }

    @Test
    @Throws(IOException::class, PatchFailedException::class)
    fun testApplyToOldFile() {
        // If both oldfile and newfile exist, we should patch the old file.
        val oldFile: Path = scratch.file("/root/oldfile", "line one")
        val newFile: Path = scratch.file("/root/newfile", "line one")
        val patchFile: Path =
            scratch.file(
                "/root/patchfile",
                "--- oldfile",
                "+++ newfile",
                "@@ -1,1 +1,2 @@",
                " line one",
                "+line two"
            )
        PatchUtil.apply(patchFile, 0, root)
        val newContent = ImmutableList.of<String?>("line one", "line two")
        assertThat(FileSystemUtils.readLines(oldFile, StandardCharsets.UTF_8)).isEqualTo(newContent)
        // new file should not change
        assertThat(FileSystemUtils.readLines(newFile, StandardCharsets.UTF_8)).containsExactly("line one")
    }

    @Test
    @Throws(IOException::class, PatchFailedException::class)
    fun testApplyToNewFile() {
        // If only newfile exists, we should patch the new file.
        val newFile: Path = scratch.file("/root/newfile", "line one")
        newFile.setReadable(true)
        newFile.setWritable(true)
        newFile.setExecutable(true)
        val patchFile: Path =
            scratch.file(
                "/root/patchfile",
                "--- oldfile",
                "+++ newfile",
                "@@ -1,1 +1,2 @@",
                " line one",
                "+line two"
            )
        PatchUtil.apply(patchFile, 0, root)
        val newContent = ImmutableList.of<String?>("line one", "line two")
        assertThat(FileSystemUtils.readLines(newFile, StandardCharsets.UTF_8)).isEqualTo(newContent)
        // Make sure file permission is preserved.
        assertThat(newFile.isReadable()).isTrue()
        assertThat(newFile.isWritable()).isTrue()
        assertThat(newFile.isExecutable()).isTrue()
    }

    @Test
    @Throws(IOException::class, PatchFailedException::class)
    fun testChangeFilePermission() {
        val myFile: Path = scratch.file("/root/test.sh", "line one")
        myFile.setReadable(true)
        myFile.setWritable(true)
        myFile.setExecutable(false)
        val patchFile: Path =
            scratch.file(
                "/root/patchfile",
                "diff --git a/test.sh b/test.sh",
                "old mode 100644",
                "new mode 100755"
            )
        PatchUtil.apply(patchFile, 1, root)
        assertThat(FileSystemUtils.readLines(myFile, StandardCharsets.UTF_8)).containsExactly("line one")
        assertThat(myFile.isReadable()).isTrue()
        assertThat(myFile.isWritable()).isTrue()
        assertThat(myFile.isExecutable()).isTrue()
    }

    @Test
    @Throws(IOException::class)
    fun testTruncatedNewFileModeRaisesPatchFailed() {
        // Regression test: a "new file mode" line shorter than 18 characters
        // (the index PatchUtil.applyInternal reaches with charAt(17)) must
        // produce a declared PatchFailedException, not an uncaught
        // StringIndexOutOfBoundsException.
        val patchFile: Path = scratch.file("/root/patchfile", "diff --git a/foo b/foo", "new file mode 1")
        val expected: PatchFailedException? =
            Assert.assertThrows<PatchFailedException?>(
                PatchFailedException::class.java,
                ThrowingRunnable { PatchUtil.apply(patchFile, 1, root) })
        Truth.assertThat(expected).hasMessageThat().contains("Truncated file mode")
    }

    @Test
    @Throws(IOException::class)
    fun testTruncatedNewModeRaisesPatchFailed() {
        // Regression test: a "new mode" line shorter than 13 characters
        // (the index PatchUtil.applyInternal reaches with charAt(12)) must
        // produce a declared PatchFailedException, not an uncaught
        // StringIndexOutOfBoundsException.
        val patchFile: Path = scratch.file("/root/patchfile", "diff --git a/foo b/foo", "new mode 1")
        val expected: PatchFailedException? =
            Assert.assertThrows<PatchFailedException?>(
                PatchFailedException::class.java,
                ThrowingRunnable { PatchUtil.apply(patchFile, 1, root) })
        Truth.assertThat(expected).hasMessageThat().contains("Truncated file mode")
    }

    @Test
    @Throws(IOException::class, PatchFailedException::class)
    fun testGitFormatPatching() {
        val foo: Path =
            scratch.file(
                "/root/foo.cc",
                "#include <stdio.h>",
                "",
                "void main(){",
                "  printf(\"Hello foo\");",
                "}"
            )
        val bar: Path = scratch.file("/root/bar.cc", "void lib(){", "  printf(\"Hello bar\");", "}")
        val patchFile: Path =
            scratch.file(
                "/root/patchfile",
                "From d205551eab3350afdb380f90ef83442ffcc0e22b Mon Sep 17 00:00:00 2001",
                "From: Yun Peng <pcloudy@google.com>",
                "Date: Thu, 6 Jun 2019 11:34:08 +0200",
                "Subject: [PATCH] 2",
                "",
                "---",
                " bar.cc | 2 +-",
                " foo.cc | 1 +",
                " 2 files changed, 2 insertions(+), 1 deletion(-)",
                "",
                "diff --git a/bar.cc b/bar.cc",
                "index e77137b..36dc9ab 100644",
                "--- a/bar.cc",
                "+++ b/bar.cc",
                "@@ -1,3 +1,3 @@",
                " void lib(){",
                "-  printf(\"Hello bar\");",
                "+  printf(\"Hello patch\");",
                " }",
                "diff --git a/foo.cc b/foo.cc",
                "index f3008f9..ec4aaa0 100644",
                "--- a/foo.cc",
                "+++ b/foo.cc",
                "@@ -2,4 +2,5 @@",
                " ",
                " void main(){",
                "   printf(\"Hello foo\");",
                "+  printf(\"Hello from patch\");",
                " }",
                "-- ",
                "2.21.0.windows.1",
                "",
                ""
            )
        PatchUtil.apply(patchFile, 1, root)
        val newFoo =
            ImmutableList.of<String?>(
                "#include <stdio.h>",
                "",
                "void main(){",
                "  printf(\"Hello foo\");",
                "  printf(\"Hello from patch\");",
                "}"
            )
        val newBar =
            ImmutableList.of<String?>("void lib(){", "  printf(\"Hello patch\");", "}")
        assertThat(FileSystemUtils.readLines(foo, StandardCharsets.UTF_8)).isEqualTo(newFoo)
        assertThat(FileSystemUtils.readLines(bar, StandardCharsets.UTF_8)).isEqualTo(newBar)
    }

    @Test
    @Throws(IOException::class, PatchFailedException::class)
    fun testGitFormatRenaming() {
        val foo: Path =
            scratch.file(
                "/root/foo.cc",
                "#include <stdio.h>",
                "",
                "void main(){",
                "  printf(\"Hello foo\");",
                "}"
            )
        val bar: Path = scratch.file("/root/bar.cc", "void lib(){", "  printf(\"Hello bar\");", "}")
        val patchFile: Path =
            scratch.file(
                "/root/patchfile",
                "diff --git a/bar.cc b/bar.cpp",
                "similarity index 61%",
                "rename from bar.cc",
                "rename to bar.cpp",
                "index e77137b..9e35ee4 100644",
                "--- a/bar.cc",
                "+++ b/bar.cpp",
                "@@ -1,3 +1,4 @@",
                " void lib(){",
                "   printf(\"Hello bar\");",
                "+  printf(\"Hello cpp\");",
                " }",
                "diff --git a/foo.cc b/foo.cpp",
                "similarity index 100%",
                "rename from foo.cc",
                "rename to foo.cpp"
            )
        PatchUtil.apply(patchFile, 1, root)
        val newFoo =
            ImmutableList.of<String?>("#include <stdio.h>", "", "void main(){", "  printf(\"Hello foo\");", "}")
        val newBar =
            ImmutableList.of<String?>(
                "void lib(){", "  printf(\"Hello bar\");", "  printf(\"Hello cpp\");", "}"
            )
        val fooCpp: Path? = root.getRelative("foo.cpp")
        val barCpp: Path? = root.getRelative("bar.cpp")
        assertThat(foo.exists()).isFalse()
        assertThat(bar.exists()).isFalse()
        assertThat(FileSystemUtils.readLines(fooCpp, StandardCharsets.UTF_8)).isEqualTo(newFoo)
        assertThat(FileSystemUtils.readLines(barCpp, StandardCharsets.UTF_8)).isEqualTo(newBar)
    }

    @Test
    @Throws(IOException::class, PatchFailedException::class)
    fun testMatchWithOffset() {
        val foo: Path =
            scratch.file(
                "/root/foo.cc",
                "#include <stdio.h>",
                "",
                "void main(){",
                "  printf(\"Hello foo\");",
                "}"
            )
        val patchFile: Path =
            scratch.file(
                "/root/patchfile",
                "diff --git a/foo.cc b/foo.cc",
                "index f3008f9..ec4aaa0 100644",
                "--- a/foo.cc",
                "+++ b/foo.cc",
                "@@ -6,4 +6,5 @@",  // Should match with offset -4, original is "@@ -2,4 +2,5 @@"
                " ",
                " void main(){",
                "   printf(\"Hello foo\");",
                "+  printf(\"Hello from patch\");",
                " }"
            )
        PatchUtil.apply(patchFile, 1, root)
        val newFoo =
            ImmutableList.of<String?>(
                "#include <stdio.h>",
                "",
                "void main(){",
                "  printf(\"Hello foo\");",
                "  printf(\"Hello from patch\");",
                "}"
            )
        assertThat(FileSystemUtils.readLines(foo, StandardCharsets.UTF_8)).isEqualTo(newFoo)
    }

    @Test // regression test for https://github.com/bazelbuild/bazel/issues/17897#issuecomment-1749389613
    @Throws(IOException::class, PatchFailedException::class)
    fun testMultipleChunks() {
        val foo: Path =
            scratch.file(
                "/root/foo",
                "1",
                "2",
                "3",
                "4",
                "5",
                "6",
                "7",
                "8",
                "9",
                "10",
                "11",
                "12",
                "13",
                "14",
                "15",
                "16",
                "17",
                "18"
            )
        val patchFile: Path =
            scratch.file(
                "/root/patchfile",
                "diff --git a/foo b/foo",
                "index c20ab12..b83bdb1 100644",
                "--- a/foo",
                "+++ b/foo",
                "@@ -1,12 +1,5 @@",
                " 1",
                " 2",
                "-3",
                "-4",
                "-5",
                "-6",
                "-7",
                "-8",
                "-9",
                " 10",
                " 11",
                " 12",
                "@@ -15,4 +8,7 @@",
                " 15",
                " 16",
                " 17",
                "+a",
                "+b",
                "+c",
                " 18"
            )
        PatchUtil.apply(patchFile, 1, root)
        val newFoo =
            ImmutableList.of<String?>(
                "1", "2", "10", "11", "12", "13", "14", "15", "16", "17", "a", "b", "c", "18"
            )
        assertThat(FileSystemUtils.readLines(foo, StandardCharsets.UTF_8)).isEqualTo(newFoo)
    }

    @Test
    @Throws(IOException::class, PatchFailedException::class)
    fun testMultipleChunksWithDifferentOffset() {
        val foo: Path =
            scratch.file("/root/foo", "1", "3", "4", "5", "6", "7", "8", "9", "10", "11", "13", "14")
        val patchFile: Path =
            scratch.file(
                "/root/patchfile",
                "diff --git a/foo b/foo",
                "index c20ab12..b83bdb1 100644",
                "--- a/foo",
                "+++ b/foo",
                "@@ -3,4 +3,5 @@",  // Should match with offset -2, original is "@@ -1,4 +1,5 @@"
                " 1",
                "+2",
                " 3",
                " 4",
                " 5",
                "@@ -4,5 +5,6 @@",  // Should match with offset 4, original is "@@ -8,4 +9,5 @@"
                " 9",
                " 10",
                " 11",
                "+12",
                " 13",
                " 14"
            )
        PatchUtil.apply(patchFile, 1, root)
        val newFoo =
            ImmutableList.of<String?>("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14")
        assertThat(FileSystemUtils.readLines(foo, StandardCharsets.UTF_8)).isEqualTo(newFoo)
    }

    @Test
    @Throws(IOException::class)
    fun testFailedToGetFileName() {
        scratch.file(
            "/root/foo.cc", "#include <stdio.h>", "", "void main(){", "  printf(\"Hello foo\");", "}"
        )
        val patchFile: Path =
            scratch.file(
                "/root/patchfile",
                "diff --git a/foo.cc b/foo.cc",
                "index f3008f9..ec4aaa0 100644",
                "--- a/foo.cc",
                "+++ b/foo.cc",
                "@@ -2,4 +2,5 @@",
                " ",
                " void main(){",
                "   printf(\"Hello foo\");",
                "+  printf(\"Hello from patch\");",
                " }"
            )
        val expected: PatchFailedException? =
            Assert.assertThrows<PatchFailedException?>(
                PatchFailedException::class.java,
                ThrowingRunnable { PatchUtil.apply(patchFile, 2, root) }) // strip=2 is wrong
        Truth.assertThat(expected)
            .hasMessageThat()
            .contains("Cannot determine file name with strip = 2 at line 3:\n--- a/foo.cc")
    }

    @Test
    fun testPatchFileNotFound() {
        val expected: PatchFailedException? =
            Assert.assertThrows<PatchFailedException?>(
                PatchFailedException::class.java,
                ThrowingRunnable { PatchUtil.apply(root.getRelative("patchfile"), 1, root) })
        Truth.assertThat(expected).hasMessageThat().contains("Cannot find patch file: /root/patchfile")
    }

    @Test
    @Throws(IOException::class)
    fun testCannotFindFileToPatch() {
        val patchFile: Path =
            scratch.file(
                "/root/patchfile",
                "diff --git a/foo.cc b/foo.cc",
                "index f3008f9..ec4aaa0 100644",
                "--- a/foo.cc",
                "+++ /dev/null",
                "@@ -2,4 +2,5 @@",
                " ",
                " void main(){",
                "   printf(\"Hello foo\");",
                "+  printf(\"Hello from patch\");",
                " }"
            )
        val expected: PatchFailedException? =
            Assert.assertThrows<PatchFailedException?>(
                PatchFailedException::class.java,
                ThrowingRunnable { PatchUtil.apply(patchFile, 1, root) })
        Truth.assertThat(expected)
            .hasMessageThat()
            .contains(
                "Cannot find file to patch (near line 3), old file name (foo.cc) doesn't exist, "
                        + "new file name is not specified."
            )
    }

    @Test
    @Throws(IOException::class)
    fun testCannotRenameFile() {
        val patchFile: Path =
            scratch.file(
                "/root/patchfile",
                "diff --git a/bar.cc b/bar.cpp",
                "similarity index 61%",
                "rename from bar.cc",
                "rename to bar.cpp",
                "index e77137b..9e35ee4 100644",
                "--- a/bar.cc",
                "+++ b/bar.cpp",
                "@@ -1,3 +1,4 @@",
                " void lib(){",
                "   printf(\"Hello bar\");",
                "+  printf(\"Hello cpp\");",
                " }",
                "diff --git a/foo.cc b/foo.cpp",
                "similarity index 100%",
                "rename from foo.cc",
                "rename to foo.cpp"
            )

        var expected: PatchFailedException?
        expected = Assert.assertThrows<PatchFailedException?>(
            PatchFailedException::class.java,
            ThrowingRunnable { PatchUtil.apply(patchFile, 1, root) })
        Truth.assertThat(expected)
            .hasMessageThat()
            .contains("Cannot rename file (near line 6), old file name (bar.cc) doesn't exist.")

        scratch.file("/root/bar.cc", "void lib(){", "  printf(\"Hello bar\");", "}")
        scratch.file("/root/foo.cc")
        scratch.file("/root/foo.cpp")

        expected = Assert.assertThrows<PatchFailedException?>(
            PatchFailedException::class.java,
            ThrowingRunnable { PatchUtil.apply(patchFile, 1, root) })
        Truth.assertThat(expected)
            .hasMessageThat()
            .contains("Cannot rename file (near line 17), new file name (foo.cpp) already exists.")
    }

    @Test
    @Throws(IOException::class)
    fun testPatchOutsideOfRepository() {
        val patchFile: Path =
            scratch.file(
                "/root/patchfile",
                "diff --git a/foo.cc b/foo.cc",
                "index f3008f9..ec4aaa0 100644",
                "--- a/../other_root/foo.cc",
                "+++ b/../other_root/foo.cc",
                "@@ -2,4 +2,5 @@",
                " ",
                " void main(){",
                "   printf(\"Hello foo\");",
                "+  printf(\"Hello from patch\");",
                " }"
            )
        val expected: PatchFailedException? =
            Assert.assertThrows<PatchFailedException?>(
                PatchFailedException::class.java,
                ThrowingRunnable { PatchUtil.apply(patchFile, 1, root) })
        Truth.assertThat(expected)
            .hasMessageThat()
            .contains(
                "Cannot patch file outside of external repository (/root), "
                        + "file path = \"../other_root/foo.cc\" at line 3"
            )
    }

    @Test
    @Throws(IOException::class)
    fun testChunkDoesNotMatch() {
        scratch.file(
            "/root/foo.cc", "line1", "line2", "line3", "line4", "line5", "line6", "line7", "line8"
        )
        val patchFile: Path =
            scratch.file(
                "/root/patchfile",
                "diff --git a/foo.cc b/foo.cc",
                "index f3008f9..ec4aaa0 100644",
                "--- a/foo.cc",
                "+++ b/foo.cc",
                "@@ -1,8 +1,9 @@",
                " line1",
                " line2",
                " line3",
                " WRONG",  // Should be "line4", in the middle so fuzz can't help
                " ALSO WRONG",  // Should be "line5"
                " line6",
                "+inserted",
                " line7",
                " line8"
            )
        val expected: PatchFailedException? =
            Assert.assertThrows<PatchFailedException?>(
                PatchFailedException::class.java,
                ThrowingRunnable { PatchUtil.apply(patchFile, 1, root) })
        Truth.assertThat(expected)
            .hasMessageThat()
            .contains(
                "in patch applied to /root/foo.cc: could not apply patch due to"
                        + " CONTENT_DOES_NOT_MATCH_TARGET"
            )
    }

    @Test
    @Throws(IOException::class)
    fun testUnexpectedContextLine() {
        scratch.file(
            "/root/foo.cc", "#include <stdio.h>", "", "void main(){", "  printf(\"Hello foo\");", "}"
        )
        val patchFile: Path =
            scratch.file(
                "/root/patchfile",
                "diff --git a/foo.cc b/foo.cc",
                "index f3008f9..ec4aaa0 100644",
                "--- a/foo.cc",
                "+++ b/foo.cc",
                "@@ -2,4 +2,5 @@",
                " ",
                " void main(){",
                "   printf(\"Hello foo\");",
                "+  printf(\"Hello from patch\");",
                "+",  // Adding this line will cause the chunk body not matching the header "@@ -2,4 +2,5
                // @@"
                " }"
            )
        val expected: PatchFailedException? =
            Assert.assertThrows<PatchFailedException?>(
                PatchFailedException::class.java,
                ThrowingRunnable { PatchUtil.apply(patchFile, 1, root) })
        Truth.assertThat(expected)
            .hasMessageThat()
            .contains("Wrong chunk detected near line 11:  }, does not expect a context line here.")
    }

    @Test
    @Throws(IOException::class, PatchFailedException::class)
    fun testMatchWithFuzz() {
        val foo: Path =
            scratch.file(
                "/root/foo.cc",
                "#include <stdio.h>",
                "",
                "void main(){",
                "  printf(\"Hello foo\");",
                "}"
            )
        val patchFile: Path =
            scratch.file(
                "/root/patchfile",
                "diff --git a/foo.cc b/foo.cc",
                "index f3008f9..ec4aaa0 100644",
                "--- a/foo.cc",
                "+++ b/foo.cc",
                "@@ -2,4 +2,5 @@",
                " ",
                " void main(){",
                "   printf(\"Hello foo\");",
                "+  printf(\"Hello from patch\");",
                " WRONG CONTEXT LINE"
            ) // Last context line doesn't match, but fuzz can drop it
        PatchUtil.apply(patchFile, 1, root)
        val newFoo =
            ImmutableList.of<String?>(
                "#include <stdio.h>",
                "",
                "void main(){",
                "  printf(\"Hello foo\");",
                "  printf(\"Hello from patch\");",
                "}"
            )
        assertThat(FileSystemUtils.readLines(foo, StandardCharsets.UTF_8)).isEqualTo(newFoo)
    }

    @Test
    @Throws(IOException::class)
    fun testMissingContextLine() {
        scratch.file(
            "/root/foo.cc", "#include <stdio.h>", "", "void main(){", "  printf(\"Hello foo\");", "}"
        )
        val patchFile: Path =
            scratch.file(
                "/root/patchfile",
                "diff --git a/foo.cc b/foo.cc",
                "index f3008f9..ec4aaa0 100644",
                "--- a/foo.cc",
                "+++ b/foo.cc",
                "@@ -2,4 +2,5 @@",
                " ",
                " void main(){",
                "   printf(\"Hello foo\");",
                "+  printf(\"Hello from patch\");"
            )
        val expected: PatchFailedException? =
            Assert.assertThrows<PatchFailedException?>(
                PatchFailedException::class.java,
                ThrowingRunnable { PatchUtil.apply(patchFile, 1, root) })
        Truth.assertThat(expected).hasMessageThat().contains("Expecting more chunk line at line 10")
    }

    @Test
    @Throws(IOException::class)
    fun testMissingChunkHeader() {
        scratch.file(
            "/root/foo.cc", "#include <stdio.h>", "", "void main(){", "  printf(\"Hello foo\");", "}"
        )
        val patchFile: Path =
            scratch.file(
                "/root/patchfile",
                "diff --git a/foo.cc b/foo.cc",
                "index f3008f9..ec4aaa0 100644",
                "--- a/foo.cc",
                "+++ b/foo.cc",  // Missing @@ -l,s +l,s @@ line
                " ",
                " void main(){",
                "   printf(\"Hello foo\");",
                "+  printf(\"Hello from patch\");",
                " }"
            )
        val expected: PatchFailedException? =
            Assert.assertThrows<PatchFailedException?>(
                PatchFailedException::class.java,
                ThrowingRunnable { PatchUtil.apply(patchFile, 1, root) })
        Truth.assertThat(expected)
            .hasMessageThat()
            .contains("Looks like a unified diff at line 3, but no patch chunk was found.")
    }

    @Test
    @Throws(IOException::class)
    fun testMissingPreludeLines() {
        val patchFile: Path =
            scratch.file(
                "/root/patchfile",
                "diff --git a/foo.cc b/foo.cc",
                "index f3008f9..ec4aaa0 100644",  // Missing "--- a/foo.cc",
                // Missing "+++ b/foo.cc",
                "@@ -2,4 +2,5 @@",
                " ",
                " void main(){",
                "   printf(\"Hello foo\");",
                "+  printf(\"Hello from patch\");",
                " }"
            )
        val expected: PatchFailedException? =
            Assert.assertThrows<PatchFailedException?>(
                PatchFailedException::class.java,
                ThrowingRunnable { PatchUtil.apply(patchFile, 1, root) })
        Truth.assertThat(expected)
            .hasMessageThat()
            .contains("The patch content must start with ---/+++ prelude lines at line 3")
    }
}
