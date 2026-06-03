// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.worker

import com.google.devtools.build.lib.vfs.DigestHashFunction

/** Tests for [ErrorMessage].  */
@RunWith(JUnit4::class)
class ErrorMessageTest {
    var logText: String = "\n\nabcdefghijklmnopqrstuvwxyz\n\n"

    @org.junit.Test
    fun testEmptyErrorMessage() {
        val errorMessage: ErrorMessage = ErrorMessage.builder().build()
        assertThat(errorMessage.toString()).isEqualTo("Unknown error")
    }

    @org.junit.Test
    fun testSimpleErrorMessage() {
        val errorMessage: ErrorMessage = ErrorMessage.builder().message("Error").build()
        assertThat(errorMessage.toString()).isEqualTo("Error")
    }

    @org.junit.Test
    fun testErrorMessageWithLogText() {
        val errorMessage: ErrorMessage =
            ErrorMessage.builder().message("Error with log text").logText(logText).build()
        assertThat(errorMessage.toString())
            .isEqualTo(
                ("Error with log text\n\n"
                        + "---8<---8<--- Start of log ---8<---8<---\n"
                        + "abcdefghijklmnopqrstuvwxyz\n"
                        + "---8<---8<--- End of log ---8<---8<---")
            )
    }

    @org.junit.Test
    fun testErrorMessageWithEmptyLogText() {
        val errorMessage: ErrorMessage =
            ErrorMessage.builder().message("Error with log text").logText("").build()
        assertThat(errorMessage.toString())
            .isEqualTo(
                ("Error with log text\n\n"
                        + "---8<---8<--- Start of log ---8<---8<---\n"
                        + "(empty)\n"
                        + "---8<---8<--- End of log ---8<---8<---")
            )
    }

    @org.junit.Test
    fun testErrorMessageWithTruncatedLogText() {
        val errorMessage: ErrorMessage =
            ErrorMessage.builder()
                .message("Error with log text")
                .logText(logText)
                .logSizeLimit(13)
                .build()
        assertThat(errorMessage.toString())
            .isEqualTo(
                ("Error with log text\n\n"
                        + "---8<---8<--- Start of log snippet ---8<---8<---\n"
                        + "[... truncated ...]\n"
                        + "nopqrstuvwxyz\n"
                        + "---8<---8<--- End of log snippet, 13 chars omitted ---8<---8<---")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testErrorMessageWithLogFile() {
        val fs: InMemoryFileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
        val logFile: Path? = fs.getPath("/log.txt")
        FileSystemUtils.writeContent(logFile, java.nio.charset.StandardCharsets.UTF_8, logText)
        val errorMessage: ErrorMessage =
            ErrorMessage.builder().message("Error with log file").logFile(logFile).build()
        assertThat(errorMessage.toString())
            .isEqualTo(
                ("Error with log file\n\n"
                        + "---8<---8<--- Start of log, file at /log.txt ---8<---8<---\n"
                        + "abcdefghijklmnopqrstuvwxyz\n"
                        + "---8<---8<--- End of log ---8<---8<---")
            )
    }

    @org.junit.Test
    fun testErrorMessageWithUnreadableLogFile() {
        val fs: InMemoryFileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
        // This file does not exist.
        val logFile: Path? = fs.getPath("/nope.txt")
        val errorMessage: ErrorMessage =
            ErrorMessage.builder().message("Error with log file").logFile(logFile).build()
        assertThat(errorMessage.toString())
            .startsWith(
                ("Error with log file\n\n"
                        + "---8<---8<--- Start of log, file at /nope.txt ---8<---8<---\n"
                        + "ERROR: IOException while trying to read log file:\n"
                        + "java.io.FileNotFoundException: /nope.txt (No such file or directory)")
            )
        assertThat(errorMessage.toString()).endsWith("---8<---8<--- End of log ---8<---8<---")
    }

    @org.junit.Test
    fun testErrorMessageWithException() {
        val errorMessage: ErrorMessage =
            ErrorMessage.builder()
                .message("An exception occurred.")
                .exception(java.lang.IllegalStateException("Hello World"))
                .build()
        assertThat(errorMessage.toString())
            .startsWith(
                ("An exception occurred.\n\n"
                        + "---8<---8<--- Exception details ---8<---8<---\n"
                        + "java.lang.IllegalStateException: Hello World")
            )
    }
}
