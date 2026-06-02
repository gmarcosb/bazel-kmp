// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.testutil.TestUtils
import com.google.devtools.build.lib.vfs.FileSystem
import com.google.devtools.build.lib.vfs.util.FileSystems
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.function.ThrowingRunnable
import java.io.File
import java.nio.charset.StandardCharsets

/** Tests decompressing archives.  */
@RunWith(JUnit4::class)
class ArFunctionTest {
    @Rule
    var folder: TemporaryFolder = TemporaryFolder()

    @Test
    @Throws(Exception::class)
    fun testDecompress() {
        val outputDir: Path = decompress(createDescriptorBuilder().build())

        assertThat(outputDir.exists()).isTrue()
        val firstFile: Path = outputDir.getRelative(FIRST_FILE_NAME)
        assertThat(firstFile.exists()).isTrue()
        // There are 20 bytes in the content "this is test file 1"
        assertThat(firstFile.getFileSize()).isEqualTo(20)
        assertThat(firstFile.isSymbolicLink()).isFalse()

        val secondFile: Path = outputDir.getRelative(SECOND_FILE_NAME)
        assertThat(secondFile.exists()).isTrue()
        // There are 20 bytes in the content "this is the second test file"
        assertThat(secondFile.getFileSize()).isEqualTo(29)
        assertThat(secondFile.isSymbolicLink()).isFalse()
    }

    /**
     * Test decompressing an ar file, with some entries being renamed during the extraction process.
     */
    @Test
    @Throws(Exception::class)
    fun testDecompressWithRenamedFiles() {
        val renameFiles: HashMap<String?, String?> = HashMap<String?, String?>()
        renameFiles.put("archived_first.txt", "renamed_file.txt")
        val descriptorBuilder =
            createDescriptorBuilder().setRenameFiles(renameFiles)
        val outputDir: Path = decompress(descriptorBuilder!!.build())

        assertThat(outputDir.exists()).isTrue()
        val renamedFile: Path = outputDir.getRelative("renamed_file.txt")
        assertThat(renamedFile.exists()).isTrue()
    }

    @Throws(Exception::class)
    private fun decompress(descriptor: DecompressorDescriptor): Path {
        return ArFunction().decompress(descriptor)
    }

    @Test
    @Throws(IOException::class)
    fun testDecompressArWithUpLevelReference() {
        val fs: FileSystem = FileSystems.getNativeFileSystem()
        val arFile = folder.newFile("malicious.ar")
        ArArchiveOutputStream(FileOutputStream(arFile)).use { aos ->
            val entry: ArArchiveEntry = ArArchiveEntry("../foo", 3)
            aos.putArchiveEntry(entry)
            aos.write("bar".toByteArray(StandardCharsets.UTF_8))
            aos.closeArchiveEntry()
        }
        val arPath: Path = fs.getPath(arFile.getAbsolutePath())

        val descriptor =
            DecompressorDescriptor.builder()
                .setArchivePath(arPath)!!
                .setDestinationPath(arPath.getParentDirectory().getRelative("out"))!!
                .build()
        val thrown: IOException? =
            Assert.assertThrows<IOException?>(IOException::class.java, ThrowingRunnable { decompress(descriptor) })
        Truth.assertThat(thrown).hasMessageThat().contains("path is escaping the destination directory")
    }

    @Throws(IOException::class)
    private fun createDescriptorBuilder(): DecompressorDescriptor.Builder {
        val testFS: FileSystem = FileSystems.getNativeFileSystem()

        // do not rely on TestConstants.JAVATESTS_ROOT end with slash, but ensure separators
        // are not duplicated
        val path: String =
            (TestConstants.JAVATESTS_ROOT + PATH_TO_TEST_ARCHIVE + ARCHIVE_NAME).replace("//", "/")
        val tarballPath: Path? = testFS.getPath(Runfiles.create().rlocation(path))

        val workingDir: Path = testFS.getPath(File(TestUtils.tmpDir()).getCanonicalPath())
        val outDir: Path? = workingDir.getRelative("out")

        return DecompressorDescriptor.builder().setDestinationPath(outDir)!!.setArchivePath(tarballPath)!!
    }

    companion object {
        /*
   * .ar archive created with ar cr test_files.ar archived_first.txt archived_second.md
   * The files contain short UTF-8 encoded strings.
   */
        private const val ARCHIVE_NAME = "test_files.ar"
        private const val PATH_TO_TEST_ARCHIVE = "/com/google/devtools/build/lib/bazel/repository/decompressor/"
        private const val FIRST_FILE_NAME = "archived_first.txt"
        private const val SECOND_FILE_NAME = "archived_second.md"
    }
}
