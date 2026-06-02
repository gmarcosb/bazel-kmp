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

import com.github.luben.zstd.ZstdOutputStream
import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.testutil.TestUtils
import com.google.devtools.build.lib.vfs.util.FileSystems
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.function.ThrowingRunnable
import org.junit.runners.Parameterized
import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.collections.MutableList

@RunWith(Parameterized::class)
class CompressedFunctionTest(private val clazz: Class<*>, private val compressedFileName: String?) {
    @Rule
    var name: TestName = TestName()

    private var archiveDir: File? = null
    private var extractionDir: File? = null
    private var testFs: FileSystem? = null

    @Before
    @Throws(IOException::class, CompressorException::class)
    fun setUp() {
        // Create an "archives" directory to hold compressed files and an "extracted" directory where
        // the extraction will occur.
        val tmpDir: String? = Paths.get(TestUtils.tmpDir()).resolve(name.getMethodName()).toString()
        archiveDir = Paths.get(tmpDir).resolve("archives").toFile()
        Truth.assertThat(archiveDir!!.mkdirs()).isTrue()
        extractionDir = Paths.get(tmpDir).resolve("extracted").toFile()
        Truth.assertThat(extractionDir!!.mkdirs()).isTrue()

        val out =
            Files.newOutputStream(
                Path.of(archiveDir!!.getPath()).resolve(compressedFileName)
            )
        val os: OutputStream?
        if (clazz == Bz2Function::class.java) {
            os = BZip2CompressorOutputStream(out)
        } else if (clazz == GzFunction::class.java) {
            os = GzipCompressorOutputStream(out)
        } else if (clazz == XzFunction::class.java) {
            os = XZCompressorOutputStream(out)
        } else if (clazz == ZstFunction::class.java) {
            os = ZstdOutputStream(out)
        } else {
            throw IllegalArgumentException("Unknown compressor class passed: " + clazz)
        }
        os.write(("test compressed " + compressedFileName + " file contents\n").toByteArray(StandardCharsets.UTF_8))
        os!!.close()

        testFs = FileSystems.getNativeFileSystem()
    }

    /** Basic decompression. Verifies that the uncompressed file name and contents are correct.  */
    @Test
    @Throws(Exception::class)
    fun testDecompress() {
        val descriptor: DecompressorDescriptor.Builder =
            DecompressorDescriptor.builder()
                .setDestinationPath(testFs.getPath(extractionDir!!.getCanonicalPath()))
                .setArchivePath(
                    testFs.getPath(archiveDir.getCanonicalPath()).getRelative(compressedFileName)
                )!!

        val fileDir: Path = decompress(descriptor.build())
        val files: ImmutableList<String?>? =
            fileDir.readdir(Symlinks.NOFOLLOW).stream().map(Dirent::getName)
                .collect(ImmutableList.toImmutableList<E?>())

        Truth.assertThat(files).containsExactly(EXTRACTED_FILE_NAME)
        val pathFile: File = fileDir.getRelative(EXTRACTED_FILE_NAME).getPathFile()
        Truth.assertThat(Files.readString(pathFile.toPath()))
            .contains("test compressed " + compressedFileName + " file contents\n")
    }

    /**
     * Prefixes are ignored, so setting one will not throw and everything still works as the regular
     * decompression.
     */
    @Test
    @Throws(Exception::class)
    fun testDecompressWithPrefixIsIgnored() {
        val descriptor: DecompressorDescriptor.Builder =
            DecompressorDescriptor.builder()
                .setDestinationPath(testFs.getPath(extractionDir!!.getCanonicalPath()))!!
                .setPrefix("archive")
                .setArchivePath(
                    testFs.getPath(archiveDir.getCanonicalPath()).getRelative(compressedFileName)
                )!!

        val fileDir: Path = decompress(descriptor.build())
        val files: ImmutableList<String?>? =
            fileDir.readdir(Symlinks.NOFOLLOW).stream().map(Dirent::getName)
                .collect(ImmutableList.toImmutableList<E?>())

        Truth.assertThat(files).containsExactly(EXTRACTED_FILE_NAME)
        val pathFile: File = fileDir.getRelative(EXTRACTED_FILE_NAME).getPathFile()
        Truth.assertThat(Files.readString(pathFile.toPath()))
            .contains("test compressed " + compressedFileName + " file contents\n")
    }

    /** Test renaming the single compressed file.  */
    @Test
    @Throws(Exception::class)
    fun testDecompressWithRenamedFiles() {
        val testFs: FileSystem = FileSystems.getNativeFileSystem()
        val renameFiles: HashMap<String?, String?> = HashMap<String?, String?>()
        renameFiles.put(EXTRACTED_FILE_NAME, "renamedFile")
        val descriptor: DecompressorDescriptor.Builder =
            DecompressorDescriptor.builder()
                .setDestinationPath(testFs.getPath(extractionDir!!.getCanonicalPath()))!!
                .setRenameFiles(renameFiles)
                .setArchivePath(
                    testFs.getPath(archiveDir.getCanonicalPath()).getRelative(compressedFileName)
                )!!

        val fileDir: Path = decompress(descriptor.build())
        val files: ImmutableList<String?>? =
            fileDir.readdir(Symlinks.NOFOLLOW).stream().map(Dirent::getName)
                .collect(ImmutableList.toImmutableList<E?>())

        Truth.assertThat(files).containsExactly("renamedFile")
        val pathFile: File = fileDir.getRelative("renamedFile").getPathFile()
        Truth.assertThat(Files.readString(pathFile.toPath()))
            .contains("test compressed " + compressedFileName + " file contents\n")
    }

    @Throws(Exception::class)
    private fun decompress(descriptor: DecompressorDescriptor?): Path {
        return (clazz.getConstructor().newInstance() as DecompressorValue.Decompressor).decompress(descriptor)
    }

    /** Test renaming the single compressed file to something that escapes.  */
    @Test
    @Throws(Exception::class)
    fun testDecompressWithRenamedFileEscape() {
        val testFs: FileSystem = FileSystems.getNativeFileSystem()
        val renameFiles: HashMap<String?, String?> = HashMap<String?, String?>()
        renameFiles.put(EXTRACTED_FILE_NAME, "../escaped.txt")
        val descriptor: DecompressorDescriptor.Builder =
            DecompressorDescriptor.builder()
                .setDestinationPath(testFs.getPath(extractionDir!!.getCanonicalPath()))!!
                .setRenameFiles(renameFiles)
                .setArchivePath(
                    testFs.getPath(archiveDir.getCanonicalPath()).getRelative(compressedFileName)
                )!!

        val thrown: IOException? = Assert.assertThrows<IOException?>(
            IOException::class.java,
            ThrowingRunnable { decompress(descriptor.build()) })
        Truth.assertThat(thrown).hasMessageThat().contains("path is escaping the destination directory")
    }

    companion object {
        const val EXTRACTED_FILE_NAME: String = "archive.txt"

        @Parameterized.Parameters
        fun data(): MutableList<Array<Any?>?> {
            return Arrays.asList<Array<Any?>?>(
                *arrayOf<Array<Any?>?>(
                    arrayOf<Any?>(Bz2Function::class.java, EXTRACTED_FILE_NAME + ".bz2"),
                    arrayOf<Any?>(GzFunction::class.java, EXTRACTED_FILE_NAME + ".gz"),
                    arrayOf<Any?>(XzFunction::class.java, EXTRACTED_FILE_NAME + ".xz"),
                    arrayOf<Any?>(ZstFunction::class.java, EXTRACTED_FILE_NAME + ".zst"),
                )
            )
        }
    }
}
