// Copyright 2025 The Bazel Authors. All rights reserved.
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
import com.google.devtools.build.lib.testutil.TestUtils
import com.google.devtools.build.lib.vfs.Dirent
import com.google.devtools.build.lib.vfs.util.FileSystems
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.function.ThrowingRunnable
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.function.Supplier

/** Tests .7z decompression.  */
@RunWith(JUnit4::class)
class SevenZDecompressorTest {
    @Rule
    var name: TestName = TestName()

    /** Provides a test filesystem descriptor for a test. NOTE: unique per individual test ONLY.  */
    @Throws(Exception::class)
    private fun archiveDescriptor(): TestArchiveDescriptor {
        return TestArchiveDescriptor(
            ARCHIVE_NAME,  /* outDirName= */
            this.javaClass.getSimpleName() + "_" + name.getMethodName(),  /* withHardLinks= */
            false
        )
    }

    /** Test decompressing a .7z file without stripping a prefix  */
    @Test
    @Throws(Exception::class)
    fun testDecompressWithoutPrefix() {
        val outputDir: Path = decompress(archiveDescriptor().createDescriptorBuilder().build())

        val fileDir: Path = outputDir.getRelative(TestArchiveDescriptor.Companion.ROOT_FOLDER_NAME)
            .getRelative(TestArchiveDescriptor.Companion.INNER_FOLDER_NAME)
        val files: ImmutableList<String?>? =
            fileDir.readdir(Symlinks.NOFOLLOW).stream().map(Dirent::getName)
                .collect(ImmutableList.toImmutableList<E?>())
        Truth.assertThat(files).contains(REGULAR_FILENAME)
        assertThat(fileDir.getRelative(REGULAR_FILENAME).getFileSize()).isNotEqualTo(0)
    }

    /** Test decompressing a .7z file and stripping a prefix.  */
    @Test
    @Throws(Exception::class)
    fun testDecompressWithPrefix() {
        val descriptorBuilder =
            archiveDescriptor().createDescriptorBuilder().setPrefix(TestArchiveDescriptor.Companion.ROOT_FOLDER_NAME)
        val outputDir: Path = decompress(descriptorBuilder!!.build())
        val fileDir: Path = outputDir.getRelative(TestArchiveDescriptor.Companion.INNER_FOLDER_NAME)

        val files: ImmutableList<String?>? =
            fileDir.readdir(Symlinks.NOFOLLOW).stream().map(Dirent::getName)
                .collect(ImmutableList.toImmutableList<E?>())
        Truth.assertThat(files).contains(REGULAR_FILENAME)
    }

    /** Test decompressing a .7z file and stripping components.  */
    @Test
    @Throws(Exception::class)
    fun testDecompressWithStripComponents() {
        val descriptorBuilder =
            archiveDescriptor().createDescriptorBuilder().setStripComponents(1)
        val outputDir: Path = decompress(descriptorBuilder!!.build())
        val fileDir: Path = outputDir.getRelative(TestArchiveDescriptor.Companion.INNER_FOLDER_NAME)

        val files: ImmutableList<String?>? =
            fileDir.readdir(Symlinks.NOFOLLOW).stream().map(Dirent::getName)
                .collect(ImmutableList.toImmutableList<E?>())
        Truth.assertThat(files).contains(REGULAR_FILENAME)
    }

    /** Test decompressing a .7z with entries being renamed during the extraction process.  */
    @Test
    @Throws(Exception::class)
    fun testDecompressWithRenamedFiles() {
        val innerDirName =
            TestArchiveDescriptor.Companion.ROOT_FOLDER_NAME + "/" + TestArchiveDescriptor.Companion.INNER_FOLDER_NAME

        val renameFiles: HashMap<String?, String?> = HashMap<String?, String?>()
        renameFiles.put(innerDirName + "/" + REGULAR_FILENAME, innerDirName + "/renamedFile")
        val descriptorBuilder =
            archiveDescriptor().createDescriptorBuilder().setRenameFiles(renameFiles)
        val outputDir: Path = decompress(descriptorBuilder!!.build())

        val fileDir: Path = outputDir.getRelative(TestArchiveDescriptor.Companion.ROOT_FOLDER_NAME)
            .getRelative(TestArchiveDescriptor.Companion.INNER_FOLDER_NAME)
        val files: MutableList<String?>? =
            fileDir.readdir(Symlinks.NOFOLLOW).stream()
                .map(Dirent::getName)
                .collect(Collectors.toCollection(Supplier { ArrayList() }))
        Truth.assertThat(files).contains("renamedFile")
        assertThat(fileDir.getRelative("renamedFile").getFileSize()).isNotEqualTo(0)
    }

    /** Test that entry renaming is applied prior to prefix stripping.  */
    @Test
    @Throws(Exception::class)
    fun testDecompressWithRenamedFilesAndPrefix() {
        val innerDirName =
            TestArchiveDescriptor.Companion.ROOT_FOLDER_NAME + "/" + TestArchiveDescriptor.Companion.INNER_FOLDER_NAME

        val renameFiles: HashMap<String?, String?> = HashMap<String?, String?>()
        renameFiles.put(innerDirName + "/" + REGULAR_FILENAME, innerDirName + "/renamedFile")
        val descriptorBuilder =
            archiveDescriptor()
                .createDescriptorBuilder()
                .setPrefix(TestArchiveDescriptor.Companion.ROOT_FOLDER_NAME)!!
                .setRenameFiles(renameFiles)
        val outputDir: Path = decompress(descriptorBuilder!!.build())

        val fileDir: Path = outputDir.getRelative(TestArchiveDescriptor.Companion.INNER_FOLDER_NAME)
        val files: ImmutableList<String?>? =
            fileDir.readdir(Symlinks.NOFOLLOW).stream().map(Dirent::getName)
                .collect(ImmutableList.toImmutableList<E?>())
        Truth.assertThat(files).contains("renamedFile")
        assertThat(fileDir.getRelative("renamedFile").getFileSize()).isNotEqualTo(0)
    }

    /** Test that entry renaming is applied prior to stripping components.  */
    @Test
    @Throws(Exception::class)
    fun testDecompressWithRenamedFilesAndStripComponents() {
        val innerDirName =
            TestArchiveDescriptor.Companion.ROOT_FOLDER_NAME + "/" + TestArchiveDescriptor.Companion.INNER_FOLDER_NAME

        val renameFiles: HashMap<String?, String?> = HashMap<String?, String?>()
        renameFiles.put(innerDirName + "/" + REGULAR_FILENAME, innerDirName + "/renamedFile")
        val descriptorBuilder =
            archiveDescriptor()
                .createDescriptorBuilder()
                .setStripComponents(1)!!
                .setRenameFiles(renameFiles)
        val outputDir: Path = decompress(descriptorBuilder!!.build())

        val fileDir: Path = outputDir.getRelative(TestArchiveDescriptor.Companion.INNER_FOLDER_NAME)
        val files: ImmutableList<String?>? =
            fileDir.readdir(Symlinks.NOFOLLOW).stream().map(Dirent::getName)
                .collect(ImmutableList.toImmutableList<E?>())
        Truth.assertThat(files).contains("renamedFile")
        assertThat(fileDir.getRelative("renamedFile").getFileSize()).isNotEqualTo(0)
    }

    /** Test decompressing a .7z file where everything is stripped  */
    @Test
    @Throws(Exception::class)
    fun testDecompressStripAllComponents() {
        val outputDir: Path =
            decompress(archiveDescriptor().createDescriptorBuilder().setStripComponents(1000)!!.build())

        assertThat(outputDir.exists()).isFalse()
    }

    private var archiveDir: File? = null
    private var extractionDir: File? = null

    fun setUpTestDirectories() {
        // Create an "archives" directory to hold the .7z archive and an "extracted" directory where the
        // extraction will occur.
        val tmpDir: String? =
            Path.of(TestUtils.tmpDir()).resolve(name.getMethodName()).toString()
        archiveDir = Path.of(tmpDir).resolve("archives").toFile()
        Truth.assertThat(archiveDir!!.mkdirs()).isTrue()
        extractionDir = Path.of(tmpDir).resolve("extracted").toFile()
        Truth.assertThat(extractionDir!!.mkdirs()).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun test7zFileModificationDate() {
        setUpTestDirectories()

        // Create a test archive.
        val sevenZOutput: SevenZOutputFile =
            SevenZOutputFile(File(archiveDir!!.getPath(), ARCHIVE_NAME))

        // A regular entry with modification date set to 2000/02/14.
        val entry: SevenZArchiveEntry =
            sevenZOutput.createArchiveEntry(
                File(TestUtils.tmpDirFile(), "test_file"),
                "root_folder/another_folder/regularFile"
            )
        val testDate: GregorianCalendar = GregorianCalendar(2000, Calendar.FEBRUARY, 14, 3, 7, 14)
        entry.setLastModifiedDate(testDate.getTime())
        sevenZOutput.putArchiveEntry(entry)
        sevenZOutput.write(
            "regular test file contents with modification date 2000/02/14\n".toByteArray(StandardCharsets.UTF_8)
        )
        sevenZOutput.closeArchiveEntry()

        // An entry that has no modification date (shouldn't crash on this).
        val entryWithNoModifiedDate: SevenZArchiveEntry =
            sevenZOutput.createArchiveEntry(
                File(TestUtils.tmpDirFile(), "test_file"),
                "root_folder/another_folder/fileNoModificationDate"
            )
        entryWithNoModifiedDate.setLastModifiedDate(null)
        sevenZOutput.putArchiveEntry(entryWithNoModifiedDate)
        sevenZOutput.write("entry has no modification date\n".toByteArray(StandardCharsets.UTF_8))
        sevenZOutput.closeArchiveEntry()
        sevenZOutput.finish()

        val testFs: FileSystem = FileSystems.getNativeFileSystem()
        val descriptor: DecompressorDescriptor.Builder =
            DecompressorDescriptor.builder()
                .setDestinationPath(testFs.getPath(extractionDir!!.getCanonicalPath()))
                .setArchivePath(
                    testFs.getPath(archiveDir.getCanonicalPath())
                        .getRelative(SevenZDecompressorTest.Companion.ARCHIVE_NAME)
                )!!

        // Decompression should not crash and set the correct modification date.
        val outputDir: Path = decompress(descriptor.build())

        val fileDir: Path = outputDir.getRelative(TestArchiveDescriptor.Companion.ROOT_FOLDER_NAME)
            .getRelative(TestArchiveDescriptor.Companion.INNER_FOLDER_NAME)
        val files: ImmutableList<String?>? =
            fileDir.readdir(Symlinks.NOFOLLOW).stream().map(Dirent::getName)
                .collect(ImmutableList.toImmutableList<E?>())

        Truth.assertThat(files).containsExactly("fileNoModificationDate", "regularFile")
        assertThat(fileDir.getRelative("regularFile").getLastModifiedTime())
            .isEqualTo(testDate.getTimeInMillis())
    }

    /** Check that we throw when handling nameless 7z entries.  */
    @Test
    @Throws(Exception::class)
    fun test7zEntriesWithNoNameThrows() {
        setUpTestDirectories()
        // Create a test archive.
        val sevenZOutput: SevenZOutputFile =
            SevenZOutputFile(File(archiveDir!!.getPath(), ARCHIVE_NAME))

        val entryWithNoName: SevenZArchiveEntry =
            sevenZOutput.createArchiveEntry(File(TestUtils.tmpDirFile(), "test_file"), "")
        sevenZOutput.putArchiveEntry(entryWithNoName)
        sevenZOutput.write("entry without a name\n".toByteArray(StandardCharsets.UTF_8))
        sevenZOutput.closeArchiveEntry()
        val entryWithNoName2: SevenZArchiveEntry =
            sevenZOutput.createArchiveEntry(File(TestUtils.tmpDirFile(), "test_file"), "")
        sevenZOutput.putArchiveEntry(entryWithNoName2)
        sevenZOutput.write("entry without a name2\n".toByteArray(StandardCharsets.UTF_8))
        sevenZOutput.closeArchiveEntry()

        sevenZOutput.finish()
        val testFs: FileSystem = FileSystems.getNativeFileSystem()
        val descriptor: DecompressorDescriptor.Builder =
            DecompressorDescriptor.builder()
                .setDestinationPath(testFs.getPath(extractionDir!!.getCanonicalPath()))
                .setArchivePath(
                    testFs.getPath(archiveDir.getCanonicalPath())
                        .getRelative(SevenZDecompressorTest.Companion.ARCHIVE_NAME)
                )!!

        val e: IOException? = Assert.assertThrows<IOException?>(
            IOException::class.java,
            ThrowingRunnable { decompress(descriptor.build()) })
        Truth.assertThat(e).hasMessageThat().isEqualTo("7z archive contains unnamed entry")
    }

    @Test
    @Throws(Exception::class)
    fun testDecompress7zWithUpLevelReference() {
        setUpTestDirectories()
        // Create a test archive.
        val sevenZOutput: SevenZOutputFile =
            SevenZOutputFile(File(archiveDir!!.getPath(), ARCHIVE_NAME))

        val entry: SevenZArchiveEntry =
            sevenZOutput.createArchiveEntry(File(TestUtils.tmpDirFile(), "test_file"), "../foo")
        sevenZOutput.putArchiveEntry(entry)
        sevenZOutput.write("bar".toByteArray(StandardCharsets.UTF_8))
        sevenZOutput.closeArchiveEntry()
        sevenZOutput.finish()

        val testFs: FileSystem = FileSystems.getNativeFileSystem()
        val descriptor =
            DecompressorDescriptor.builder()
                .setDestinationPath(testFs.getPath(extractionDir!!.getCanonicalPath()))!!
                .setArchivePath(
                    testFs.getPath(archiveDir!!.getCanonicalPath())
                        .getRelative(SevenZDecompressorTest.Companion.ARCHIVE_NAME)
                )!!
                .build()

        val thrown: IOException? =
            Assert.assertThrows<IOException?>(IOException::class.java, ThrowingRunnable { decompress(descriptor) })
        Truth.assertThat(thrown).hasMessageThat().contains("path is escaping the destination directory")
    }

    @Throws(Exception::class)
    private fun decompress(descriptor: DecompressorDescriptor): Path {
        return SevenZDecompressor().decompress(descriptor)
    }

    companion object {
        /**
         * .7z file, created with one file:
         * 
         * 
         *  * root_folder/another_folder/regularFile
         * 
         * 
         * Compressed with command "7zz a test_decompress_archive.7z root_folder"
         */
        private const val ARCHIVE_NAME = "test_decompress_archive.7z"

        private const val REGULAR_FILENAME = "regularFile"
    }
}
