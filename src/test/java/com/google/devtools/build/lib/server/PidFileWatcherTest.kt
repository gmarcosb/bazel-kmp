// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.server

import com.google.devtools.build.lib.vfs.DigestHashFunction
import org.junit.Assert
import org.junit.Test
import java.nio.charset.StandardCharsets

/** Tests for [PidFileWatcher].  */
@RunWith(JUnit4::class)
class PidFileWatcherTest {
    private var pidFile: Path? = null
    private var underTest: PidFileWatcher? = null

    @Before
    fun setUp() {
        val fileSystem: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
        pidFile = fileSystem.getPath("/pid")
        underTest =
            PidFileWatcher(
                pidFile,
                EXPECTED_PID,
                {
                    throw THROWN_ON_HALT
                })
    }

    @Test
    @Throws(IOException::class)
    fun testMissingPidFileHaltsProgram() {
        // Delete just in case.
        pidFile.delete()

        assertPidCheckHaltsProgram()
    }

    @Test
    @Throws(IOException::class)
    fun testEmptyPidFileCountsAsChanged() {
        FileSystemUtils.writeContent(pidFile, ByteArray(0))

        assertPidCheckHaltsProgram()
    }

    @Test
    @Throws(IOException::class)
    fun testGarbagePidFileCountsAsChanged() {
        FileSystemUtils.writeContent(pidFile, "junk".toByteArray(StandardCharsets.US_ASCII))

        assertPidCheckHaltsProgram()
    }

    @Test
    @Throws(IOException::class)
    fun testPidFileContinuesExecution() {
        FileSystemUtils.writeContent(pidFile, "42".toByteArray(StandardCharsets.US_ASCII))

        assertThat(underTest.runPidFileChecks()).isTrue()
    }

    @Test
    @Throws(IOException::class)
    fun testPidFileTrailingWhitespaceNotTolerated() {
        FileSystemUtils.writeContent(pidFile, "42\n".toByteArray(StandardCharsets.US_ASCII))

        assertPidCheckHaltsProgram()
    }

    @Test
    @Throws(IOException::class)
    fun testPidFileChangeAfterShutdownNotificationStopsWatcher() {
        FileSystemUtils.writeContent(pidFile, "42\n".toByteArray(StandardCharsets.US_ASCII))

        underTest.signalShutdown()
        assertThat(underTest.runPidFileChecks()).isFalse()
    }

    private fun assertPidCheckHaltsProgram() {
        val expected =
            Assert.assertThrows<IllegalStateException?>(IllegalStateException::class.java, underTest::runPidFileChecks)
        Truth.assertThat(expected).isSameInstanceAs(THROWN_ON_HALT)
    }

    companion object {
        private const val EXPECTED_PID = 42
        private val THROWN_ON_HALT = IllegalStateException("crash!")
    }
}
