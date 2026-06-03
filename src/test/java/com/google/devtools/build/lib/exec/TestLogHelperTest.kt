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
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.vfs.Path

/** Test for TestLogHelper.  */
@RunWith(JUnit4::class)
class TestLogHelperTest : FoundationTestCase() {
    @org.junit.Test
    fun testShouldOutputTestLog() {
        assertThat(TestLogHelper.shouldOutputTestLog(ExecutionOptions.TestOutputFormat.ALL, true))
            .isTrue()
        assertThat(TestLogHelper.shouldOutputTestLog(ExecutionOptions.TestOutputFormat.ALL, false))
            .isTrue()
        assertThat(TestLogHelper.shouldOutputTestLog(ExecutionOptions.TestOutputFormat.ERRORS, false))
            .isTrue()
        assertThat(TestLogHelper.shouldOutputTestLog(ExecutionOptions.TestOutputFormat.ERRORS, true))
            .isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testFormatEmptyTestLog() {
        val logPath: Path = scratch.file("/test.log", "")
        val result = getTestLog(logPath, "testFormatEmptyTestLog")
        Truth.assertThat(result)
            .isEqualTo(
                ("==================== Test output for testFormatEmptyTestLog:\n"
                        + "\n"
                        + "================================================================================\n")
            )
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testFormatTestLogWithHeader() {
        val logPath: Path? =
            scratch.file("/test.log", "Header", TestLogHelper.HEADER_DELIMITER, "Empty line")
        val result = getTestLog(logPath, "testFormatTestLogWithHeader")
        Truth.assertThat(result)
            .isEqualTo(
                ("==================== Test output for testFormatTestLogWithHeader:\n"
                        + "Empty line\n"
                        + "================================================================================\n")
            )
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testFormatTestLogWithNoHeader() {
        val logPath: Path = scratch.file("/test.log", "Line 1", "Line 2")
        val result = getTestLog(logPath, "testFormatTestLogWithNoHeader")
        Truth.assertThat(result)
            .isEqualTo(
                ("==================== Test output for testFormatTestLogWithNoHeader:\n"
                        + "Line 1\n"
                        + "Line 2\n"
                        + "================================================================================\n")
            )
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testFormatTestLogTooLarge() {
        val logPath: Path = scratch.file("/test.log", ByteArray(1000))

        val bytesOut: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        TestLogHelper.writeTestLog(logPath, "myTest", bytesOut,  /*maxTestOutputBytes=*/10)
        Truth.assertThat(bytesOut.toString(java.nio.charset.StandardCharsets.ISO_8859_1))
            .isEqualTo(
                ("==================== Test output for myTest:\n"
                        + "Test log too large (1000 > 10), skipping...\n"
                        + "================================================================================\n")
            )
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testFormatTestLog0ByteMax0ByteFilePrintsNothing() {
        val logPath: Path = scratch.file("/test.log", ByteArray(0))

        val bytesOut: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        TestLogHelper.writeTestLog(logPath, "myTest", bytesOut,  /*maxTestOutputBytes=*/0)
        Truth.assertThat(bytesOut.toString(java.nio.charset.StandardCharsets.ISO_8859_1))
            .isEqualTo(
                "==================== Test output for myTest:\n"
                        + "================================================================================\n"
            )
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testFormatTestLogMaxBytesIncludesHeader() {
        val logPath: Path? = scratch.file(
            "/test.log",
            TestLogHelper.HEADER_DELIMITER.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)
        )

        val testLogHeaderLength: Int = TestLogHelper.HEADER_DELIMITER.length()
        val bytesOut: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        TestLogHelper.writeTestLog(
            logPath, "myTest", bytesOut,  /*maxTestOutputBytes=*/testLogHeaderLength - 1
        )
        Truth.assertThat(bytesOut.toString(java.nio.charset.StandardCharsets.ISO_8859_1))
            .isEqualTo(
                ("==================== Test output for myTest:\n"
                        + String.format(
                    "Test log too large (%d > %d), skipping...\n",
                    testLogHeaderLength, testLogHeaderLength - 1
                ) + "================================================================================\n")
            )
    }

    @Throws(IOException::class)
    private fun getTestLog(path: Path?, name: String?): String? {
        val output: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        TestLogHelper.writeTestLog(path, name, output,  /*maxTestOutputBytes=*/-1)
        return output.toString(java.nio.charset.StandardCharsets.ISO_8859_1)
    }
}
