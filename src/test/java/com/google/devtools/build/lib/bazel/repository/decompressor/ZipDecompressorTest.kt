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
import java.nio.charset.StandardCharsets

/**
 * Tests for [ZipDecompressor].
 */
@RunWith(JUnit4::class)
class ZipDecompressorTest {
    @Rule
    var folder: TemporaryFolder = TemporaryFolder()

    @Throws(IOException::class)
    private fun createZipFile(entryName: String, content: String): Path {
        val fs: FileSystem = FileSystems.getNativeFileSystem()
        val zipFile = folder.newFile("malicious.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            val entry: ZipEntry = ZipEntry(entryName)
            zos.putNextEntry(entry)
            zos.write(content.toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()
        }
        return fs.getPath(zipFile.getAbsolutePath())
    }

    /**
     * Test decompressing a tar.gz file with hard link file and symbolic link file inside without
     * stripping a prefix
     */
    @Test
    @Throws(Exception::class)
    fun testDecompressWithoutPrefix() {
        val archiveDescriptor =
            TestArchiveDescriptor(ARCHIVE_NAME, "out/inner", false)
        val outputDir: Path = decompress(archiveDescriptor.createDescriptorBuilder().build())

        archiveDescriptor.assertOutputFiles(
            outputDir,
            TestArchiveDescriptor.Companion.ROOT_FOLDER_NAME,
            TestArchiveDescriptor.Companion.INNER_FOLDER_NAME
        )
    }

    /**
     * Test decompressing a zip file with hard link file and symbolic link file inside and stripping a
     * prefix
     */
    @Test
    @Throws(Exception::class)
    fun testDecompressWithPrefix() {
        val archiveDescriptor = TestArchiveDescriptor(ARCHIVE_NAME, "out", false)
        val descriptorBuilder =
            archiveDescriptor.createDescriptorBuilder().setPrefix(TestArchiveDescriptor.Companion.ROOT_FOLDER_NAME)
        val outputDir: Path = decompress(descriptorBuilder!!.build())

        archiveDescriptor.assertOutputFiles(outputDir, TestArchiveDescriptor.Companion.INNER_FOLDER_NAME)
    }

    /**
     * Test decompressing a zip file with hard link file and symbolic link file inside and stripping a
     * component
     */
    @Test
    @Throws(Exception::class)
    fun testDecompressWithStripComponents() {
        val archiveDescriptor = TestArchiveDescriptor(ARCHIVE_NAME, "out", false)
        val descriptorBuilder =
            archiveDescriptor.createDescriptorBuilder().setStripComponents(1)
        val outputDir: Path = decompress(descriptorBuilder!!.build())

        archiveDescriptor.assertOutputFiles(outputDir, TestArchiveDescriptor.Companion.INNER_FOLDER_NAME)
    }

    /**
     * Test decompressing a zip file, with some entries being renamed during the extraction process.
     */
    @Test
    @Throws(Exception::class)
    fun testDecompressWithRenamedFiles() {
        val archiveDescriptor = TestArchiveDescriptor(ARCHIVE_NAME, "out", false)
        val innerDirName =
            TestArchiveDescriptor.Companion.ROOT_FOLDER_NAME + "/" + TestArchiveDescriptor.Companion.INNER_FOLDER_NAME

        val renameFiles: HashMap<String?, String?> = HashMap<String?, String?>()
        renameFiles.put(innerDirName + "/hardLinkFile", innerDirName + "/renamedFile")
        val descriptorBuilder =
            archiveDescriptor.createDescriptorBuilder().setRenameFiles(renameFiles)
        val outputDir: Path = decompress(descriptorBuilder!!.build())

        val innerDir: Path = outputDir.getRelative(TestArchiveDescriptor.Companion.ROOT_FOLDER_NAME)
            .getRelative(TestArchiveDescriptor.Companion.INNER_FOLDER_NAME)
        assertThat(innerDir.getRelative("renamedFile").exists()).isTrue()
    }

    /** Test that entry renaming is applied prior to prefix stripping.  */
    @Test
    @Throws(Exception::class)
    fun testDecompressWithRenamedFilesAndPrefix() {
        val archiveDescriptor = TestArchiveDescriptor(ARCHIVE_NAME, "out", false)
        val innerDirName =
            TestArchiveDescriptor.Companion.ROOT_FOLDER_NAME + "/" + TestArchiveDescriptor.Companion.INNER_FOLDER_NAME

        val renameFiles: HashMap<String?, String?> = HashMap<String?, String?>()
        renameFiles.put(innerDirName + "/hardLinkFile", innerDirName + "/renamedFile")
        val descriptorBuilder =
            archiveDescriptor
                .createDescriptorBuilder()
                .setPrefix(TestArchiveDescriptor.Companion.ROOT_FOLDER_NAME)!!
                .setRenameFiles(renameFiles)
        val outputDir: Path = decompress(descriptorBuilder!!.build())

        val innerDir: Path = outputDir.getRelative(TestArchiveDescriptor.Companion.INNER_FOLDER_NAME)
        assertThat(innerDir.getRelative("renamedFile").exists()).isTrue()
    }

    /** Test that entry renaming is applied prior to stripping components.  */
    @Test
    @Throws(Exception::class)
    fun testDecompressWithRenamedFilesAndStripComponents() {
        val archiveDescriptor = TestArchiveDescriptor(ARCHIVE_NAME, "out", false)
        val innerDirName =
            TestArchiveDescriptor.Companion.ROOT_FOLDER_NAME + "/" + TestArchiveDescriptor.Companion.INNER_FOLDER_NAME

        val renameFiles: HashMap<String?, String?> = HashMap<String?, String?>()
        renameFiles.put(innerDirName + "/hardLinkFile", innerDirName + "/renamedFile")
        val descriptorBuilder =
            archiveDescriptor
                .createDescriptorBuilder()
                .setStripComponents(1)!!
                .setRenameFiles(renameFiles)
        val outputDir: Path = decompress(descriptorBuilder!!.build())

        val innerDir: Path = outputDir.getRelative(TestArchiveDescriptor.Companion.INNER_FOLDER_NAME)
        assertThat(innerDir.getRelative("renamedFile").exists()).isTrue()
    }

    @Throws(Exception::class)
    private fun decompress(descriptor: DecompressorDescriptor?): Path {
        return ZipDecompressor.INSTANCE.decompress(descriptor)
    }

    @Test
    @Throws(Exception::class)
    fun testGetPermissions() {
        var permissions = ZipDecompressor.getPermissions(FILE_ATTRIBUTE, "foo/bar")
        Truth.assertThat(permissions).isEqualTo(FILE)
        permissions = ZipDecompressor.getPermissions(EXECUTABLE_ATTRIBUTE, "foo/bar")
        Truth.assertThat(permissions).isEqualTo(EXECUTABLE)
        permissions = ZipDecompressor.getPermissions(DIRECTORY_ATTRIBUTE, "foo/bar")
        Truth.assertThat(permissions).isEqualTo(DIRECTORY)
    }

    @Test
    @Throws(Exception::class)
    fun testWindowsPermissions() {
        var permissions =
            ZipDecompressor.getPermissions(ZipDecompressor.WINDOWS_FILE_ATTRIBUTE_DIRECTORY, "foo/bar")
        Truth.assertThat(permissions).isEqualTo(DIRECTORY)
        permissions =
            ZipDecompressor.getPermissions(ZipDecompressor.WINDOWS_FILE_ATTRIBUTE_ARCHIVE, "foo/bar")
        Truth.assertThat(permissions).isEqualTo(EXECUTABLE)
        permissions =
            ZipDecompressor.getPermissions(ZipDecompressor.WINDOWS_FILE_ATTRIBUTE_NORMAL, "foo/bar")
        Truth.assertThat(permissions).isEqualTo(EXECUTABLE)
    }

    @Test
    @Throws(Exception::class)
    fun testDirectoryWithRegularFilePermissions() {
        val permissions = ZipDecompressor.getPermissions(FILE, "foo/bar/")
        Truth.assertThat(permissions).isEqualTo(16877)
    }

    @Test
    @Throws(IOException::class)
    fun testDecompressZipWithUpLevelReference() {
        val zipFile: Path = createZipFile("../foo", "bar")
        val descriptor: DecompressorDescriptor? =
            DecompressorDescriptor.builder()
                .setArchivePath(zipFile)!!
                .setDestinationPath(zipFile.getParentDirectory().getRelative("out"))!!
                .build()
        val thrown: IOException? =
            Assert.assertThrows<IOException?>(IOException::class.java, ThrowingRunnable { decompress(descriptor) })
        Truth.assertThat(thrown).hasMessageThat().contains("path is escaping the destination directory")
    }

    companion object {
        private const val FILE = 33188
        private const val EXECUTABLE = 33261
        private const val DIRECTORY = 16877

        // External attributes hold the permissions in the higher-order bits, so the input int has to be
        // shifted.
        private val FILE_ATTRIBUTE: Int = FILE shl 16
        private val EXECUTABLE_ATTRIBUTE: Int = EXECUTABLE shl 16
        private val DIRECTORY_ATTRIBUTE: Int = DIRECTORY shl 16

        private const val ARCHIVE_NAME = "test_decompress_archive.zip"
    }
}
