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

import com.google.devtools.build.lib.testutil.TestUtils
import com.google.devtools.build.lib.vfs.FileSystem
import com.google.devtools.build.lib.vfs.util.FileSystems
import org.junit.Rule
import org.junit.Test
import org.junit.runners.Parameterized
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Arrays

/**
 * Tests "non-core" .gz decompression code. For the "core" decompression, see
 * CompressedFunctionTest.
 */
@RunWith(Enclosed::class)
class GzFunctionTest {
    @Rule
    var name: TestName = TestName()

    @RunWith(Parameterized::class)
    class FileNameTest(// The filename as stored in the Gzip metadata.
        private val metadataFileName: String?, // The expected filename when uncompressed.
        private val expectedUncompressedFilename: String?
    ) {
        @Rule
        var name: TestName = TestName()

        /**
         * If the Gzip metadata parameters have an original filename, it will be used as the
         * uncompressed filename.
         */
        @Test
        @Throws(IOException::class)
        fun uncompressesOriginalFilename() {
            val parameters: GzipParameters = GzipParameters()
            parameters.setFileName(metadataFileName)
            val testGzipFile: Path = createTestGzipFile(name.getMethodName(), ARCHIVE_NAME, parameters)

            val fn = GzFunction()
            fn.getDecompressorStream(
                BufferedInputStream(Files.newInputStream(testGzipFile), 32)
            ).use { decompressorStream ->
                val uncompressedFilename = fn.getUncompressedFileName(decompressorStream, ARCHIVE_NAME)
                Truth.assertThat(uncompressedFilename).isEqualTo(expectedUncompressedFilename)
            }
        }

        companion object {
            const val ARCHIVE_NAME: String = "archive.txt.gz"
            const val BASE_NAME: String = "archive.txt"

            @Parameterized.Parameters
            fun data(): MutableList<Array<Any?>?> {
                return Arrays.asList<Array<Any?>?>(
                    *arrayOf<Array<Any?>?>(
                        arrayOf<Any?>("originalFilename", "originalFilename"),
                        arrayOf<Any?>(null, BASE_NAME),
                        arrayOf<Any?>("   ", BASE_NAME),
                        arrayOf<Any?>("fake/path/to/originalFilename", "originalFilename"),
                        arrayOf<Any?>("../../path/to/originalFilename", "originalFilename"),
                    )
                )
            }
        }
    }

    @RunWith(JUnit4::class)
    class FileAttributes {
        @Rule
        var name: TestName = TestName()

        @Test
        @Throws(IOException::class)
        fun setLastModifiedTime() {
            // TODO(pcloudy): Fix this test in Blaze.
            Assume.assumeFalse(
                "Skipping setLastModifiedTime test in Blaze environment.", TestConstants.PRODUCT_NAME == "blaze"
            )
            val testFs: FileSystem = FileSystems.getNativeFileSystem()
            val tmpDir: Path = TestUtils.createUniqueTmpDir(testFs)
            val testFile: File = File(tmpDir.getPathFile(), "test_file")
            Truth.assertThat(testFile.createNewFile()).isTrue()

            // Set the modified time gzip metadata.
            val testDate: GregorianCalendar = GregorianCalendar(2000, Calendar.FEBRUARY, 14, 3, 7, 14)
            val parameters: GzipParameters = GzipParameters()
            // Expects unix time in seconds.
            val unixTimeSeconds: Long = testDate.getTimeInMillis() / 1000
            parameters.setModificationTime(unixTimeSeconds)

            // Create the gzip file with the above metadata.
            val testGzipFile: Path = createTestGzipFile(name.getMethodName(), "test.txt.gz", parameters)
            val fn = GzFunction()
            fn.getDecompressorStream(
                BufferedInputStream(Files.newInputStream(testGzipFile), 32)
            ).use { decompressorStream ->
                // Calling set attributes will set the modified time according to the metadata.
                fn.setFileAttributes(decompressorStream, testFs.getPath(testFile.getCanonicalPath()))
            }
            // There was an error in Apache Commons Compress where the time was improperly divided by
            // 1000, thus losing 3 digits of precision. This replicates that wrong behavior until we
            // upgrade the Apache Commons Compress library to 1.28. At which point, you can replace
            // hackExpectedTimeSeconds below with unixTimeSeconds.
            // See https://github.com/apache/commons-compress/pull/624
            // NOTE: 1.28 also has an issue - see https://github.com/bazelbuild/bazel/issues/28454
            // so cleaning up this hack will need to wait until 1.29 is released.
            val hackExpectedTimeSeconds = unixTimeSeconds / 1000 * 1000
            // Time should be in epoch milliseconds.
            Truth.assertThat(testFile.lastModified()).isEqualTo(hackExpectedTimeSeconds * 1000)
        }
    }

    companion object {
        /**
         * Creates a simple gzip file.
         * 
         * @param testName used as a directory name in the [TestUtils.tmpDir] where the gzip file
         * will be placed.
         * @param fileName the name of the gzip file to write
         * @param parameters gzip-specific metadata parameters
         * @return Path to the gzip file
         */
        @Throws(IOException::class)
        private fun createTestGzipFile(
            testName: String?, fileName: String?, parameters: GzipParameters
        ): Path {
            val tmpDir: String? = Path.of(TestUtils.tmpDir()).resolve(testName).toString()
            Path.of(tmpDir).toFile().mkdirs()
            val compressedFile: Path = Path.of(tmpDir, fileName)
            val outputStream = Files.newOutputStream(compressedFile)

            GzipCompressorOutputStream(outputStream, parameters).use { compressedOutputStream ->
                compressedOutputStream.write("test file contents\n".toByteArray(StandardCharsets.UTF_8))
                compressedOutputStream.finish()
            }
            return compressedFile
        }
    }
}
