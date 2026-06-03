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
package com.google.devtools.build.lib.windows

import com.google.devtools.build.lib.shell.ShellUtils

/** Unit tests for [WindowsProcesses].  */
@RunWith(JUnit4::class)
@TestSpec(supportedOs = [com.google.devtools.build.lib.util.OS.WINDOWS])
class WindowsProcessesTest {
    private var mockSubprocess: String? = null
    private var mockBinary: String? = null
    private var process: Long = 0

    @Before
    @Throws(java.lang.Exception::class)
    fun loadJni() {
        val runfiles: Runfiles = Runfiles.create()
        mockSubprocess =
            runfiles.rlocation(
                "io_bazel/src/test/java/com/google/devtools/build/lib/windows/MockSubprocess_deploy.jar"
            )
        mockBinary = java.lang.System.getProperty("java.home") + "\\bin\\java.exe"

        process = -1
    }

    @org.junit.After
    @Throws(java.lang.Exception::class)
    fun terminateProcess() {
        if (process != -1L) {
            WindowsProcesses.terminate(process)
            WindowsProcesses.deleteProcess(process)
            process = -1
        }
    }

    private fun mockArgs(vararg args: String?): String {
        val argv: MutableList<String?> = java.util.ArrayList<String?>()

        argv.add("-jar")
        argv.add(mockSubprocess)
        quoteArgs(argv, *args)

        return com.google.common.base.Joiner.on(" ").join(argv)
    }

    @Throws(java.lang.Exception::class)
    private fun assertNoProcessError() {
        assertThat(WindowsProcesses.processGetLastError(process)).isEmpty()
    }

    @Throws(java.lang.Exception::class)
    private fun assertNoStreamError(stream: Long) {
        assertThat(WindowsProcesses.streamGetLastError(stream)).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoesNotQuoteSimpleArg() {
        Truth.assertThat(quoteArgs("x", "a")).containsExactly("x", "a").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testQuotesEmptyArg() {
        Truth.assertThat(quoteArgs("x", "")).containsExactly("x", "\"\"").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testQuotesArgWithSpace() {
        Truth.assertThat(quoteArgs("x", "a b")).containsExactly("x", "\"a b\"").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoesNotQuoteArgWithBackslash() {
        Truth.assertThat(quoteArgs("x", "a\\b")).containsExactly("x", "a\\b").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoesNotQuoteArgWithSingleQuote() {
        Truth.assertThat(quoteArgs("x", "a'b")).containsExactly("x", "a'b").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testQuotesArgWithDoubleQuote() {
        Truth.assertThat(quoteArgs("x", "a\"b", "y")).containsExactly("x", "\"a\\\"b\"", "y").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOneShot() {
        process =
            WindowsProcesses.createProcess(mockBinary, mockArgs("Ia0", "Oa"), null, null, null, null)
        assertNoProcessError()

        val input: ByteArray = "HELLO".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        val output = ByteArray(5)
        assertThat(WindowsProcesses.writeStdin(process, input, 0, 5)).isEqualTo(5)
        WindowsProcesses.closeStdin(process)
        assertNoProcessError()
        readStdout(output, 0, 5)
        assertNoStreamError(WindowsProcesses.getStdout(process))
        Truth.assertThat(String(output, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("HELLO")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testChunks() {
        val args: MutableList<String?> = java.util.ArrayList<String?>()
        for (i in 0..99) {
            args.add("Ia3")
            args.add("Oa")
        }

        process =
            WindowsProcesses.createProcess(
                mockBinary, mockArgs(*args.toArray<String?>(arrayOf<String?>())), null, null, null, null
            )
        for (i in 0..99) {
            val input: ByteArray = String.format("%03d", i).toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            Truth.assertThat(input.size).isEqualTo(3)
            assertThat(WindowsProcesses.writeStdin(process, input, 0, 3)).isEqualTo(3)
            assertNoProcessError()
            val output = ByteArray(3)
            Truth.assertThat(readStdout(output, 0, 3)).isEqualTo(3)
            Truth.assertThat(String(output, java.nio.charset.StandardCharsets.UTF_8).toInt()).isEqualTo(i)
        }
    }

    private fun readStdout(output: ByteArray?, offset: Int, length: Int): Int {
        return WindowsProcesses.readStream(
            WindowsProcesses.getStdout(process), output, offset, length
        )
    }

    private fun readStderr(output: ByteArray?, offset: Int, length: Int): Int {
        return WindowsProcesses.readStream(
            WindowsProcesses.getStderr(process), output, offset, length
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExitCode() {
        process =
            WindowsProcesses.createProcess(mockBinary, mockArgs("X42"), null, null, null, null)
        assertThat(WindowsProcesses.waitFor(process, -1)).isEqualTo(0)
        assertThat(WindowsProcesses.getExitCode(process)).isEqualTo(42)
        assertNoProcessError()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPartialRead() {
        process =
            WindowsProcesses.createProcess(
                mockBinary, mockArgs("O-HELLO"), null, null, null, null
            )
        val one = ByteArray(2)
        val two = ByteArray(3)

        Truth.assertThat(readStdout(one, 0, 2)).isEqualTo(2)
        assertNoStreamError(WindowsProcesses.getStdout(process))
        Truth.assertThat(readStdout(two, 0, 3)).isEqualTo(3)
        assertNoStreamError(WindowsProcesses.getStdout(process))

        Truth.assertThat(String(one, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("HE")
        Truth.assertThat(String(two, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("LLO")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAvailable_givesBytesFromLiveProcess() {
        process =
            WindowsProcesses.createProcess(mockBinary, mockArgs("O-HELLOWRLD"), null, null, null, null)
        val one = ByteArray(2)
        val two = ByteArray(3)

        val stdout: Long = WindowsProcesses.getStdout(process)
        // Need to wait until the process has posted its data before we can check available()
        Truth.assertThat(readStdout(one, 0, 2)).isEqualTo(2)
        assertNoStreamError(stdout)
        assertThat(WindowsProcesses.streamBytesAvailable(stdout)).isEqualTo(7)
        assertNoStreamError(stdout)

        Truth.assertThat(readStdout(two, 0, 3)).isEqualTo(3)
        assertNoStreamError(stdout)
        assertThat(WindowsProcesses.streamBytesAvailable(stdout)).isEqualTo(4)
        assertNoStreamError(stdout)

        WindowsProcesses.closeStream(stdout)
        assertThat(WindowsProcesses.streamBytesAvailable(stdout)).isEqualTo(0)
        assertThat(WindowsProcesses.streamGetLastError(stdout)).isEmpty()

        Truth.assertThat(String(one, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("HE")
        Truth.assertThat(String(two, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("LLO")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAvailable_doesNotFailOnDeadProcess() {
        process = WindowsProcesses.createProcess(mockBinary, mockArgs("X42"), null, null, null, null)
        val stdout: Long = WindowsProcesses.getStdout(process)
        assertThat(WindowsProcesses.waitFor(process, -1)).isEqualTo(0)
        assertThat(WindowsProcesses.getExitCode(process)).isEqualTo(42)
        // Windows allows streams to be read after the process has died.
        assertThat(WindowsProcesses.streamBytesAvailable(stdout)).isAtLeast(0)
        assertThat(WindowsProcesses.streamGetLastError(stdout)).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArrayOutOfBounds() {
        process =
            WindowsProcesses.createProcess(mockBinary, mockArgs("O-oob"), null, null, null, null)
        val buf = ByteArray(3)
        Truth.assertThat(readStdout(buf, -1, 3)).isEqualTo(-1)
        Truth.assertThat(readStdout(buf, 0, 5)).isEqualTo(-1)
        Truth.assertThat(readStdout(buf, 4, 1)).isEqualTo(-1)
        Truth.assertThat(readStdout(buf, 2, -1)).isEqualTo(-1)
        Truth.assertThat(readStdout(buf, Int.Companion.MAX_VALUE, 2)).isEqualTo(-1)
        Truth.assertThat(readStdout(buf, 2, Int.Companion.MAX_VALUE)).isEqualTo(-1)
        Truth.assertThat(readStderr(buf, -1, 3)).isEqualTo(-1)
        Truth.assertThat(readStderr(buf, 0, 5)).isEqualTo(-1)
        Truth.assertThat(readStderr(buf, 4, 1)).isEqualTo(-1)
        Truth.assertThat(readStderr(buf, 2, -1)).isEqualTo(-1)
        Truth.assertThat(readStderr(buf, Int.Companion.MAX_VALUE, 2)).isEqualTo(-1)
        Truth.assertThat(readStderr(buf, 2, Int.Companion.MAX_VALUE)).isEqualTo(-1)
        assertThat(WindowsProcesses.writeStdin(process, buf, -1, 3)).isEqualTo(-1)
        assertThat(WindowsProcesses.writeStdin(process, buf, 0, 5)).isEqualTo(-1)
        assertThat(WindowsProcesses.writeStdin(process, buf, 4, 1)).isEqualTo(-1)
        assertThat(WindowsProcesses.writeStdin(process, buf, 2, -1)).isEqualTo(-1)
        assertThat(WindowsProcesses.writeStdin(process, buf, Int.Companion.MAX_VALUE, 2))
            .isEqualTo(-1)
        assertThat(WindowsProcesses.writeStdin(process, buf, 2, Int.Companion.MAX_VALUE))
            .isEqualTo(-1)

        Truth.assertThat(readStdout(buf, 0, 3)).isEqualTo(3)
        Truth.assertThat(String(buf, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("oob")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOffsetedOps() {
        process =
            WindowsProcesses.createProcess(mockBinary, mockArgs("Ia3", "Oa"), null, null, null, null)
        val input: ByteArray = "01234".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        val output: ByteArray = "abcde".toByteArray(java.nio.charset.StandardCharsets.UTF_8)

        assertThat(WindowsProcesses.writeStdin(process, input, 1, 3)).isEqualTo(3)
        assertNoProcessError()
        val rv = readStdout(output, 1, 3)
        assertNoProcessError()
        Truth.assertThat(rv).isEqualTo(3)

        Truth.assertThat(String(output, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("a123e")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParallelStdoutAndStderr() {
        process =
            WindowsProcesses.createProcess(
                mockBinary,
                mockArgs(
                    "O-out1", "E-err1", "O-out2", "E-err2", "E-err3", "O-out3", "E-err4", "O-out4"
                ),
                null,
                null,
                null,
                null
            )
        assertNoProcessError()

        val buf = ByteArray(4)
        Truth.assertThat(readStdout(buf, 0, 4)).isEqualTo(4)
        Truth.assertThat(String(buf, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("out1")
        Truth.assertThat(readStderr(buf, 0, 4)).isEqualTo(4)
        Truth.assertThat(String(buf, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("err1")

        Truth.assertThat(readStderr(buf, 0, 4)).isEqualTo(4)
        Truth.assertThat(String(buf, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("err2")
        Truth.assertThat(readStdout(buf, 0, 4)).isEqualTo(4)
        Truth.assertThat(String(buf, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("out2")

        Truth.assertThat(readStdout(buf, 0, 4)).isEqualTo(4)
        Truth.assertThat(String(buf, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("out3")
        Truth.assertThat(readStderr(buf, 0, 4)).isEqualTo(4)
        Truth.assertThat(String(buf, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("err3")

        Truth.assertThat(readStderr(buf, 0, 4)).isEqualTo(4)
        Truth.assertThat(String(buf, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("err4")
        Truth.assertThat(readStdout(buf, 0, 4)).isEqualTo(4)
        Truth.assertThat(String(buf, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("out4")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecutableNotFound() {
        process =
            WindowsProcesses.createProcess(
                "ThisExecutableDoesNotExist", "TheseArgsDontMatter", null, null, null, null
            )
        com.google.common.truth.Subject.contains("The system cannot find the file specified.")
        val buf = ByteArray(1)
        Truth.assertThat(readStdout(buf, 0, 1)).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReadingAndWritingAfterTermination() {
        process =
            WindowsProcesses.createProcess(mockBinary, mockArgs("X42"), null, null, null, null)
        val buf = ByteArray(1)
        Truth.assertThat(readStdout(buf, 0, 1)).isEqualTo(0)
        Truth.assertThat(readStderr(buf, 0, 1)).isEqualTo(0)
        assertThat(WindowsProcesses.writeStdin(process, buf, 0, 1)).isEqualTo(-1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNewEnvironmentVariables() {
        val data: ByteArray =
            "ONE=one\u0000TWO=twotwo\u0000\u0000".toByteArray(java.nio.charset.StandardCharsets.UTF_16LE)
        process =
            WindowsProcesses.createProcess(
                mockBinary, mockArgs("O\$ONE", "O\$TWO"), data, null, null, null
            )
        assertNoProcessError()
        var buf = ByteArray(3)
        Truth.assertThat(readStdout(buf, 0, 3)).isEqualTo(3)
        Truth.assertThat(String(buf, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("one")
        buf = ByteArray(6)
        Truth.assertThat(readStdout(buf, 0, 6)).isEqualTo(6)
        Truth.assertThat(String(buf, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("twotwo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoZeroInEnvBuffer() {
        val data: ByteArray = "clown".toByteArray(java.nio.charset.StandardCharsets.UTF_16LE)
        process = WindowsProcesses.createProcess(mockBinary, mockArgs(), data, null, null, null)
        assertThat(WindowsProcesses.processGetLastError(process)).isNotEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMissingFinalDoubleZeroInEnvBuffer() {
        val data: ByteArray = "FOO=bar\u0000".toByteArray(java.nio.charset.StandardCharsets.UTF_16LE)
        process = WindowsProcesses.createProcess(mockBinary, mockArgs(), data, null, null, null)
        assertThat(WindowsProcesses.processGetLastError(process)).isNotEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOneByteEnvBuffer() {
        val data: ByteArray = "a".toByteArray(java.nio.charset.StandardCharsets.UTF_16LE)
        process = WindowsProcesses.createProcess(mockBinary, mockArgs(), data, null, null, null)
        assertThat(WindowsProcesses.processGetLastError(process)).isNotEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOneZeroEnvBuffer() {
        val data: ByteArray = "\u0000".toByteArray(java.nio.charset.StandardCharsets.UTF_16LE)
        process = WindowsProcesses.createProcess(mockBinary, mockArgs(), data, null, null, null)
        assertThat(WindowsProcesses.processGetLastError(process)).isNotEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTwoZerosInEnvBuffer() {
        val data: ByteArray = "\u0000\u0000".toByteArray(java.nio.charset.StandardCharsets.UTF_16LE)
        process = WindowsProcesses.createProcess(mockBinary, mockArgs(), data, null, null, null)
        assertThat(WindowsProcesses.processGetLastError(process)).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRedirect() {
        val stdoutFile = java.lang.System.getenv("TEST_TMPDIR") + "\\stdout_redirect"
        val stderrFile = java.lang.System.getenv("TEST_TMPDIR") + "\\stderr_redirect"

        process =
            WindowsProcesses.createProcess(
                mockBinary, mockArgs("O-one", "E-two"), null, null, stdoutFile, stderrFile
            )
        Truth.assertThat(process).isGreaterThan(0L)
        assertNoProcessError()
        assertThat(WindowsProcesses.waitFor(process, -1)).isEqualTo(0)
        WindowsProcesses.getExitCode(process)
        assertNoProcessError()
        val stdout: ByteArray = java.nio.file.Files.readAllBytes(Paths.get(stdoutFile))
        val stderr: ByteArray = java.nio.file.Files.readAllBytes(Paths.get(stderrFile))
        Truth.assertThat(String(stdout, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("one")
        Truth.assertThat(String(stderr, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("two")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRedirectToSameFile() {
        val file = java.lang.System.getenv("TEST_TMPDIR") + "\\captured_"

        process =
            WindowsProcesses.createProcess(
                mockBinary, mockArgs("O-one", "E-two"), null, null, file, file
            )
        Truth.assertThat(process).isGreaterThan(0L)
        assertNoProcessError()
        assertThat(WindowsProcesses.waitFor(process, -1)).isEqualTo(0)
        WindowsProcesses.getExitCode(process)
        assertNoProcessError()
        val bytes: ByteArray = java.nio.file.Files.readAllBytes(Paths.get(file))
        Truth.assertThat(String(bytes, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("onetwo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReadingFromRedirectedStreams() {
        val stdoutFile = java.lang.System.getenv("TEST_TMPDIR") + "\\captured_stdout"
        val stderrFile = java.lang.System.getenv("TEST_TMPDIR") + "\\captured_stderr"

        process =
            WindowsProcesses.createProcess(
                mockBinary, mockArgs("O-one", "E-two"), null, null, stdoutFile, stderrFile
            )
        assertNoProcessError()
        val buf = ByteArray(1)
        Truth.assertThat(readStdout(buf, 0, 1)).isEqualTo(0)
        Truth.assertThat(readStderr(buf, 0, 1)).isEqualTo(0)
        WindowsProcesses.waitFor(process, -1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRedirectedErrorStream() {
        process =
            WindowsProcesses.createProcess(
                mockBinary, mockArgs("O-one", "E-two"), null, null, null, null, true
            )
        assertNoProcessError()
        val buf = ByteArray(6)
        Truth.assertThat(readStdout(buf, 0, 3)).isEqualTo(3)
        Truth.assertThat(readStdout(buf, 3, 3)).isEqualTo(3)
        Truth.assertThat(String(buf, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("onetwo")
        Truth.assertThat(readStderr(buf, 0, 1)).isEqualTo(0)
        WindowsProcesses.waitFor(process, -1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAppendToExistingFile() {
        val stdoutFile = java.lang.System.getenv("TEST_TMPDIR") + "\\stdout_atef"
        val stderrFile = java.lang.System.getenv("TEST_TMPDIR") + "\\stderr_atef"
        val stdout: Path = Paths.get(stdoutFile)
        val stderr: Path = Paths.get(stderrFile)
        java.nio.file.Files.write(stdout, "out1".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        java.nio.file.Files.write(stderr, "err1".toByteArray(java.nio.charset.StandardCharsets.UTF_8))

        process =
            WindowsProcesses.createProcess(
                mockBinary, mockArgs("O-out2", "E-err2"), null, null, stdoutFile, stderrFile
            )
        assertNoProcessError()
        WindowsProcesses.waitFor(process, -1)
        WindowsProcesses.getExitCode(process)
        assertNoProcessError()
        val stdoutBytes: ByteArray = java.nio.file.Files.readAllBytes(Paths.get(stdoutFile))
        val stderrBytes: ByteArray = java.nio.file.Files.readAllBytes(Paths.get(stderrFile))
        Truth.assertThat(String(stdoutBytes, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("out1out2")
        Truth.assertThat(String(stderrBytes, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("err1err2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCwd() {
        val dir1 = java.lang.System.getenv("TEST_TMPDIR") + "/dir1"
        java.io.File(dir1).mkdir()

        process =
            WindowsProcesses.createProcess(mockBinary, mockArgs("O."), null, dir1, null, null)
        assertNoProcessError()
        val buf = ByteArray(1024) // Windows MAX_PATH is 260, but whatever
        val len = readStdout(buf, 0, 1024)
        assertNoProcessError()
        Truth.assertThat(String(buf, 0, len, java.nio.charset.StandardCharsets.UTF_8).replace("\\", "/"))
            .isEqualTo(dir1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTimeout() {
        process =
            WindowsProcesses.createProcess(
                mockBinary, mockArgs("W5", "X0"), null, null, null, null
            )
        assertThat(WindowsProcesses.waitFor(process, 1000)).isEqualTo(1)
    }

    companion object {
        private fun quoteArgs(argv: MutableList<String?>, vararg args: String?): MutableList<String?> {
            for (arg in args) {
                argv.add(ShellUtils.windowsEscapeArg(arg))
            }
            return argv
        }

        private fun quoteArgs(vararg args: String?): MutableList<String?> {
            val argv: MutableList<String?> = java.util.ArrayList<String?>()
            return quoteArgs(argv, *args)
        }
    }
}
