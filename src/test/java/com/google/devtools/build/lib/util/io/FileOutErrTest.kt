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
package com.google.devtools.build.lib.util.io

import com.google.devtools.build.lib.util.io.FileOutErr.FileRecordingOutputStream
import org.junit.Assert
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** Tests [OutErr].  */
@RunWith(JUnit4::class)
class FileOutErrTest {
    private var fs: FileSystem? = null

    @Before
    fun setUp() {
        fs = InMemoryFileSystem(DigestHashFunction.SHA256)
    }

    private fun newFileRecordingOutputStream(path: String?): FileRecordingOutputStream {
        val outputFile: Path? = fs.getPath(path)
        return FileRecordingOutputStream(outputFile)
    }

    @Test
    @Throws(Exception::class)
    fun testFileRecordingOutputStream_doesNotExistByDefault() {
        val os: FileRecordingOutputStream = newFileRecordingOutputStream("/some-file.txt")

        assertThat(os.hasRecordedOutput()).isFalse()
        assertThat(os.getRecordedOutput()).isEmpty()
        assertThat(os.getRecordedOutputSize()).isEqualTo(0)

        val recorder = ByteArrayOutputStream()
        os.dumpOut(recorder)
        Truth.assertThat(recorder.toByteArray()).isEmpty()

        // Existence and error checks must come last to ensure previous calls have no side-effects.
        assertThat(os.getFileUnsafe().exists()).isFalse()
        assertThat(os.hadError()).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun testFileRecordingOutputStream_createOutOfBandAsEmpty() {
        val os: FileRecordingOutputStream = newFileRecordingOutputStream("/some-file.txt")
        val path: Path = os.getFile()
        path.getOutputStream().close()

        assertThat(os.hasRecordedOutput()).isFalse()
        assertThat(os.getRecordedOutput()).isEmpty()
        assertThat(os.getRecordedOutputSize()).isEqualTo(0)

        val recorder = ByteArrayOutputStream()
        os.dumpOut(recorder)
        Truth.assertThat(recorder.toByteArray()).isEmpty()

        // Existence and error checks must come last to ensure previous calls have no side-effects.
        assertThat(os.getFileUnsafe().exists()).isTrue()
        assertThat(os.hadError()).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun testFileRecordingOutputStream_createOutOfBandWithContents() {
        val os: FileRecordingOutputStream = newFileRecordingOutputStream("/some-file.txt")
        val path: Path = os.getFile()
        val data: ByteArray = "12345".toByteArray(StandardCharsets.ISO_8859_1)
        path.getOutputStream().use { writer ->
            writer.write(data)
        }
        assertThat(os.hasRecordedOutput()).isTrue()
        assertThat(os.getRecordedOutput()).isEqualTo(data)
        assertThat(os.getRecordedOutputSize()).isEqualTo(data.size)

        val recorder = ByteArrayOutputStream()
        os.dumpOut(recorder)
        Truth.assertThat(recorder.toByteArray()).isEqualTo(data)

        // Existence and error checks must come last to ensure previous calls have no side-effects.
        assertThat(os.getFileUnsafe().exists()).isTrue()
        assertThat(os.hadError()).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun testFileRecordingOutputStream_write() {
        val os: FileRecordingOutputStream = newFileRecordingOutputStream("/some-file.txt")
        val data: ByteArray = "12345".toByteArray(StandardCharsets.ISO_8859_1)
        try {
            os.write(data)
        } finally {
            os.close()
        }

        assertThat(os.hasRecordedOutput()).isTrue()
        assertThat(os.getRecordedOutput()).isEqualTo(data)
        assertThat(os.getRecordedOutputSize()).isEqualTo(data.size)

        val recorder = ByteArrayOutputStream()
        os.dumpOut(recorder)
        Truth.assertThat(recorder.toByteArray()).isEqualTo(data)

        // Existence and error checks must come last to ensure previous calls have no side-effects.
        assertThat(os.getFileUnsafe().exists()).isTrue()
        assertThat(os.hadError()).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun testFileRecordingOutputStream_clearAfterCreation() {
        val os: FileRecordingOutputStream = newFileRecordingOutputStream("/some-file.txt")
        val path: Path = os.getFile()
        try {
            os.write("12345".toByteArray(StandardCharsets.ISO_8859_1))
        } finally {
            os.close()
        }

        assertThat(path.exists()).isTrue()
        assertThat(os.getRecordedOutputSize()).isGreaterThan(0)
        assertThat(os.hadError()).isFalse()
        os.clear()
        assertThat(path.exists()).isFalse()
        assertThat(os.getRecordedOutputSize()).isEqualTo(0)
        assertThat(os.hadError()).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun testFileRecordingOutputStream_errorDuringSizeCheck() {
        fs.getPath("/dir").createDirectory()
        val os: FileRecordingOutputStream = newFileRecordingOutputStream("/dir/some-file.txt")
        val path: Path = os.getFile()
        path.getOutputStream().close()
        fs.getPath("/dir").setReadable(false)
        fs.getPath("/dir").setExecutable(false)

        val expected: IOException = Assert.assertThrows<IOException>(IOException::class.java, os::getRecordedOutputSize)
        Truth.assertThat(expected.toString()).contains("Permission denied")

        val recorder = ByteArrayOutputStream()
        os.dumpOut(recorder)
        Truth.assertThat(String(recorder.toByteArray(), StandardCharsets.ISO_8859_1))
            .contains("Permission denied")

        // Restore directory permissions so our existence check works.
        fs.getPath("/dir").setReadable(true)
        fs.getPath("/dir").setExecutable(true)
        // Existence and error checks must come last to ensure previous calls have no side-effects.
        assertThat(os.getFileUnsafe().exists()).isTrue()
        assertThat(os.hadError()).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun testFileRecordingOutputStream_errorDuringRead() {
        val os: FileRecordingOutputStream = newFileRecordingOutputStream("/some-file.txt")
        val path: Path = os.getFile()
        path.getOutputStream().close()
        path.setReadable(false)

        val error = String(os.getRecordedOutput(), StandardCharsets.ISO_8859_1)
        // The error message comes from the system so we cannot be too specific about what we look for.
        Truth.assertThat(error).contains("Permission denied")
        assertThat(os.getRecordedOutputSize()).isGreaterThan(0)

        val recorder = ByteArrayOutputStream()
        os.dumpOut(recorder)
        Truth.assertThat(recorder.toByteArray())
            .isEqualTo((error + "\n" + error).toByteArray(StandardCharsets.ISO_8859_1))

        // Existence and error checks must come last to ensure previous calls have no side-effects.
        assertThat(os.getFileUnsafe().exists()).isTrue()
        assertThat(os.hadError()).isTrue()
    }
}
