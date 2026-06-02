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
package com.google.devtools.build.lib.bazel.repository.decompressor

import com.google.devtools.build.lib.vfs.FileSystem
import com.google.devtools.build.lib.vfs.util.FileSystems
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.function.ThrowingRunnable
import java.io.InputStream
import java.nio.charset.StandardCharsets

/** Tests decompressing archives.  */
@RunWith(JUnit4::class)
class CompressedTarFunctionTest {
    @Rule
    var folder: TemporaryFolder = TemporaryFolder()

    private var archiveDescriptor: TestArchiveDescriptor? = null

    @Before
    @Throws(Exception::class)
    fun setUpFs() {
        archiveDescriptor = TestArchiveDescriptor(ARCHIVE_NAME, "out", true)
    }

    /**
     * Test decompressing a tar.gz file with hard link file and symbolic link file inside without
     * stripping a prefix
     */
    @Test
    @Throws(Exception::class)
    fun testDecompressWithoutPrefix() {
        val outputDir: Path = decompress(archiveDescriptor!!.createDescriptorBuilder().build())

        archiveDescriptor!!.assertOutputFiles(
            outputDir,
            TestArchiveDescriptor.Companion.ROOT_FOLDER_NAME,
            TestArchiveDescriptor.Companion.INNER_FOLDER_NAME
        )
    }

    /**
     * Test decompressing a tar.gz file with hard link file and symbolic link file inside and
     * stripping a prefix
     */
    @Test
    @Throws(Exception::class)
    fun testDecompressWithPrefix() {
        val descriptorBuilder =
            archiveDescriptor!!.createDescriptorBuilder().setPrefix(TestArchiveDescriptor.Companion.ROOT_FOLDER_NAME)
        val outputDir: Path = decompress(descriptorBuilder!!.build())

        archiveDescriptor!!.assertOutputFiles(outputDir, TestArchiveDescriptor.Companion.INNER_FOLDER_NAME)
    }

    /**
     * Test decompressing a tar.gz file with hard link file and symbolic link file inside and
     * stripping the first component.
     */
    @Test
    @Throws(Exception::class)
    fun testDecompressWithStripComponents() {
        val descriptorBuilder =
            archiveDescriptor!!.createDescriptorBuilder().setStripComponents(1)
        val outputDir: Path = decompress(descriptorBuilder!!.build())

        archiveDescriptor!!.assertOutputFiles(outputDir, TestArchiveDescriptor.Companion.INNER_FOLDER_NAME)
    }

    /**
     * Test decompressing a tar.gz file, with some entries being renamed during the extraction
     * process.
     */
    @Test
    @Throws(Exception::class)
    fun testDecompressWithRenamedFiles() {
        val innerDirName =
            TestArchiveDescriptor.Companion.ROOT_FOLDER_NAME + "/" + TestArchiveDescriptor.Companion.INNER_FOLDER_NAME

        val renameFiles: HashMap<String?, String?> = HashMap<String?, String?>()
        renameFiles.put(innerDirName + "/hardLinkFile", innerDirName + "/renamedFile")
        val descriptorBuilder =
            archiveDescriptor!!.createDescriptorBuilder().setRenameFiles(renameFiles)
        val outputDir: Path = decompress(descriptorBuilder!!.build())

        val innerDir: Path = outputDir.getRelative(TestArchiveDescriptor.Companion.ROOT_FOLDER_NAME)
            .getRelative(TestArchiveDescriptor.Companion.INNER_FOLDER_NAME)
        assertThat(innerDir.getRelative("renamedFile").exists()).isTrue()
    }

    /** Test that entry renaming is applied prior to prefix stripping.  */
    @Test
    @Throws(Exception::class)
    fun testDecompressWithRenamedFilesAndPrefix() {
        val innerDirName =
            TestArchiveDescriptor.Companion.ROOT_FOLDER_NAME + "/" + TestArchiveDescriptor.Companion.INNER_FOLDER_NAME

        val renameFiles: HashMap<String?, String?> = HashMap<String?, String?>()
        renameFiles.put(innerDirName + "/hardLinkFile", innerDirName + "/renamedFile")
        val descriptorBuilder =
            archiveDescriptor!!
                .createDescriptorBuilder()
                .setPrefix(TestArchiveDescriptor.Companion.ROOT_FOLDER_NAME)!!
                .setRenameFiles(renameFiles)
        val outputDir: Path = decompress(descriptorBuilder!!.build())

        val innerDir: Path = outputDir.getRelative(TestArchiveDescriptor.Companion.INNER_FOLDER_NAME)
        assertThat(innerDir.getRelative("renamedFile").exists()).isTrue()
    }

    /** Test that entry renaming is applied prior to component stripping.  */
    @Test
    @Throws(Exception::class)
    fun testDecompressWithRenamedFilesAndStripComponents() {
        val innerDirName =
            TestArchiveDescriptor.Companion.ROOT_FOLDER_NAME + "/" + TestArchiveDescriptor.Companion.INNER_FOLDER_NAME

        val renameFiles: HashMap<String?, String?> = HashMap<String?, String?>()
        renameFiles.put(innerDirName + "/hardLinkFile", innerDirName + "/renamedFile")
        val descriptorBuilder =
            archiveDescriptor!!
                .createDescriptorBuilder()
                .setStripComponents(1)!!
                .setRenameFiles(renameFiles)
        val outputDir: Path = decompress(descriptorBuilder!!.build())

        val innerDir: Path = outputDir.getRelative(TestArchiveDescriptor.Companion.INNER_FOLDER_NAME)
        assertThat(innerDir.getRelative("renamedFile").exists()).isTrue()
    }

    @Throws(Exception::class)
    private fun decompress(descriptor: DecompressorDescriptor): Path {
        return object : CompressedTarFunction() {
            @Throws(IOException::class)
            override fun getDecompressorStream(compressedInputStream: BufferedInputStream): InputStream? {
                return GZIPInputStream(compressedInputStream)
            }
        }.decompress(descriptor)
    }

    @Test
    @Throws(Exception::class)
    fun testDecompressTarWithUpLevelReference() {
        val fs: FileSystem = FileSystems.getNativeFileSystem()
        val tarGzFile = folder.newFile("malicious.tar.gz")
        FileOutputStream(tarGzFile).use { fos ->
            GzipCompressorOutputStream(fos).use { gzos ->
                TarArchiveOutputStream(gzos).use { tos ->
                    val entry: TarArchiveEntry = TarArchiveEntry("../foo")
                    entry.setSize(3)
                    tos.putArchiveEntry(entry)
                    tos.write("bar".toByteArray(StandardCharsets.UTF_8))
                    tos.closeArchiveEntry()
                }
            }
        }
        val tarGzPath: Path = fs.getPath(tarGzFile.getAbsolutePath())

        val descriptor =
            DecompressorDescriptor.builder()
                .setArchivePath(tarGzPath)!!
                .setDestinationPath(tarGzPath.getParentDirectory().getRelative("out"))!!
                .build()
        val thrown: IOException? =
            Assert.assertThrows<IOException?>(IOException::class.java, ThrowingRunnable { decompress(descriptor) })
        Truth.assertThat(thrown).hasMessageThat().contains("path is escaping the destination directory")
    }

    @Test
    @Throws(Exception::class)
    fun testDecompressTarWithSymlinkEscape() {
        val fs: FileSystem = FileSystems.getNativeFileSystem()
        val tarGzFile = folder.newFile("malicious_symlink.tar.gz")
        FileOutputStream(tarGzFile).use { fos ->
            GzipCompressorOutputStream(fos).use { gzos ->
                TarArchiveOutputStream(gzos).use { tos ->
                    val entry: TarArchiveEntry = TarArchiveEntry("link", TarArchiveEntry.LF_SYMLINK)
                    entry.setLinkName("../foo")
                    entry.setIds(0, 0)
                    entry.setNames("user", "group")
                    tos.putArchiveEntry(entry)
                    tos.closeArchiveEntry()
                }
            }
        }
        val tarGzPath: Path = fs.getPath(tarGzFile.getAbsolutePath())

        val descriptor =
            DecompressorDescriptor.builder()
                .setArchivePath(tarGzPath)!!
                .setDestinationPath(tarGzPath.getParentDirectory().getRelative("out"))!!
                .build()
        val thrown: IOException? =
            Assert.assertThrows<IOException?>(IOException::class.java, ThrowingRunnable { decompress(descriptor) })
        Truth.assertThat(thrown)
            .hasMessageThat()
            .contains("Tar entries cannot refer to files outside of their directory")
    }

    companion object {
        /* Tarball, created by "tar -czf <ARCHIVE_NAME> <files...>" */
        private const val ARCHIVE_NAME = "test_decompress_archive.tar.gz"
    }
}
