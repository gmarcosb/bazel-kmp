// Copyright 2020 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.util.io.OutErr

/** Tests for [StreamedTestOutput].  */
@RunWith(JUnit4::class)
class StreamedTestOutputTest {
    private val fileSystem: InMemoryFileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)

    @org.junit.Test
    @Throws(IOException::class)
    fun testEmptyFile() {
        val watchedPath: Path? = fileSystem.getPath("/myfile")
        FileSystemUtils.writeContent(watchedPath, ByteArray(0))

        val out: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val err: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        StreamedTestOutput(OutErr.create(out, err), fileSystem.getPath("/myfile")).use { underTest -> }
        Truth.assertThat(out.toByteArray()).isEmpty()
        Truth.assertThat(err.toByteArray()).isEmpty()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testNoHeaderOutputsEntireFile() {
        val watchedPath: Path? = fileSystem.getPath("/myfile")
        FileSystemUtils.writeContent(watchedPath, java.nio.charset.StandardCharsets.UTF_8, "random\nlines\n")

        val out: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val err: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        StreamedTestOutput(OutErr.create(out, err), fileSystem.getPath("/myfile")).use { underTest -> }
        Truth.assertThat(out.toString(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("random\nlines\n")
        Truth.assertThat(err.toString(java.nio.charset.StandardCharsets.UTF_8)).isEmpty()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testOnlyOutputsContentsAfterHeaderWhenPresent() {
        val watchedPath: Path? = fileSystem.getPath("/myfile")
        FileSystemUtils.writeLinesAs(
            watchedPath,
            java.nio.charset.StandardCharsets.UTF_8,
            "ignored",
            "lines",
            TestLogHelper.HEADER_DELIMITER,
            "included",
            "lines"
        )

        val out: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val err: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        StreamedTestOutput(OutErr.create(out, err), fileSystem.getPath("/myfile")).use { underTest -> }
        Truth.assertThat(out.toString(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("included\nlines\n")
        Truth.assertThat(err.toString(java.nio.charset.StandardCharsets.UTF_8)).isEmpty()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testWatcherDoneAfterClose() {
        val watchedPath: Path? = fileSystem.getPath("/myfile")
        FileSystemUtils.writeLinesAs(
            watchedPath, java.nio.charset.StandardCharsets.UTF_8, TestLogHelper.HEADER_DELIMITER, "x".repeat(10 shl 20)
        )
        val underTest: StreamedTestOutput =
            StreamedTestOutput(
                OutErr.create(
                    com.google.common.io.ByteStreams.nullOutputStream(),
                    com.google.common.io.ByteStreams.nullOutputStream()
                ),
                fileSystem.getPath("/myfile")
            )
        underTest.close()
        assertThat(underTest.getFileWatcher().isAlive()).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testInterruptWaitsForWatcherToClose() {
        val watchedPath: Path? = fileSystem.getPath("/myfile")
        FileSystemUtils.writeLinesAs(
            watchedPath, java.nio.charset.StandardCharsets.UTF_8, TestLogHelper.HEADER_DELIMITER, "x".repeat(10 shl 20)
        )

        val underTest: StreamedTestOutput =
            StreamedTestOutput(
                OutErr.create(
                    com.google.common.io.ByteStreams.nullOutputStream(),
                    com.google.common.io.ByteStreams.nullOutputStream()
                ),
                fileSystem.getPath("/myfile")
            )
        try {
            java.lang.Thread.currentThread().interrupt()
            underTest.close()
            assertThat(underTest.getFileWatcher().isAlive()).isFalse()
        } finally {
            // Both checks that the interrupt bit was reset and clears it for later tests.
            Truth.assertThat(java.lang.Thread.interrupted()).isTrue()
        }
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testOutputsFileWithHeaderRegardlessOfInterrupt() {
        val watchedPath: Path? = fileSystem.getPath("/myfile")
        FileSystemUtils.writeContent(watchedPath, java.nio.charset.StandardCharsets.UTF_8, "blahblahblah")

        val out: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val err: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val underTest: StreamedTestOutput =
            StreamedTestOutput(OutErr.create(out, err), fileSystem.getPath("/myfile"))
        try {
            java.lang.Thread.currentThread().interrupt()
            underTest.close()
            assertThat(underTest.getFileWatcher().isAlive()).isFalse()
        } finally {
            // Both checks that the interrupt bit was reset and clears it for later tests.
            Truth.assertThat(java.lang.Thread.interrupted()).isTrue()
        }

        Truth.assertThat(out.toString(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("blahblahblah")
        Truth.assertThat(err.toString(java.nio.charset.StandardCharsets.UTF_8)).isEmpty()
    }
}
