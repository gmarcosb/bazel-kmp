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

/** Unit tests for [WindowsSubprocess].  */
@RunWith(JUnit4::class)
@TestSpec(supportedOs = [com.google.devtools.build.lib.util.OS.WINDOWS])
class WindowsSubprocessTest {
    private var mockSubprocess: String? = null
    private var mockBinary: String? = null
    private var process: Subprocess? = null
    private var runfiles: Runfiles? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun loadJni() {
        runfiles = Runfiles.create()
        mockSubprocess =
            runfiles.rlocation(
                "io_bazel/src/test/java/com/google/devtools/build/lib/windows/MockSubprocess_deploy.jar"
            )
        mockBinary = java.lang.System.getProperty("java.home") + "\\bin\\java.exe"

        process = null
    }

    @org.junit.After
    @Throws(java.lang.Exception::class)
    fun terminateProcess() {
        if (process != null) {
            process.destroy()
            process.close()
            process = null
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSystemRootIsSetByDefault() {
        val subprocessBuilder: SubprocessBuilder =
            SubprocessBuilder(java.lang.System.getenv(), WindowsSubprocessFactory.INSTANCE)
        subprocessBuilder.setWorkingDirectory(java.io.File("."))
        subprocessBuilder.setArgv(
            com.google.common.collect.ImmutableList.of<E?>(
                mockBinary,
                "-jar",
                mockSubprocess,
                "O\$SYSTEMROOT"
            )
        )
        subprocessBuilder.setEnv(com.google.common.collect.ImmutableMap.of<K?, V?>())
        process = subprocessBuilder.start()
        process.waitFor()
        assertThat(process.exitValue()).isEqualTo(0)

        val buf: ByteArray = process.inputStream.readAllBytes()
        Truth.assertThat(String(buf, java.nio.charset.StandardCharsets.UTF_8).trim { it <= ' ' })
            .isEqualTo(java.lang.System.getenv("SYSTEMROOT").trim { it <= ' ' })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSystemDriveIsSetByDefault() {
        val subprocessBuilder: SubprocessBuilder =
            SubprocessBuilder(java.lang.System.getenv(), WindowsSubprocessFactory.INSTANCE)
        subprocessBuilder.setWorkingDirectory(java.io.File("."))
        subprocessBuilder.setArgv(
            com.google.common.collect.ImmutableList.of<E?>(mockBinary, "-jar", mockSubprocess, "O\$SYSTEMDRIVE")
        )
        subprocessBuilder.setEnv(com.google.common.collect.ImmutableMap.of<K?, V?>())
        process = subprocessBuilder.start()
        process.waitFor()
        assertThat(process.exitValue()).isEqualTo(0)

        val buf: ByteArray = process.inputStream.readAllBytes()
        Truth.assertThat(String(buf, java.nio.charset.StandardCharsets.UTF_8).trim { it <= ' ' })
            .isEqualTo(java.lang.System.getenv("SYSTEMDRIVE").trim { it <= ' ' })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSystemRootIsSet() {
        val subprocessBuilder: SubprocessBuilder =
            SubprocessBuilder(java.lang.System.getenv(), WindowsSubprocessFactory.INSTANCE)
        subprocessBuilder.setWorkingDirectory(java.io.File("."))
        subprocessBuilder.setArgv(
            com.google.common.collect.ImmutableList.of<E?>(
                mockBinary,
                "-jar",
                mockSubprocess,
                "O\$SYSTEMROOT"
            )
        )
        // Case shouldn't matter on Windows
        subprocessBuilder.setEnv(com.google.common.collect.ImmutableMap.of<K?, V?>("SystemRoot", "C:\\MySystemRoot"))
        process = subprocessBuilder.start()
        process.waitFor()
        assertThat(process.exitValue()).isEqualTo(0)

        val buf: ByteArray = process.inputStream.readAllBytes()
        Truth.assertThat(String(buf, java.nio.charset.StandardCharsets.UTF_8).trim { it <= ' ' })
            .isEqualTo("C:\\MySystemRoot")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSystemDriveIsSet() {
        val subprocessBuilder: SubprocessBuilder =
            SubprocessBuilder(java.lang.System.getenv(), WindowsSubprocessFactory.INSTANCE)
        subprocessBuilder.setWorkingDirectory(java.io.File("."))
        subprocessBuilder.setArgv(
            com.google.common.collect.ImmutableList.of<E?>(mockBinary, "-jar", mockSubprocess, "O\$SYSTEMDRIVE")
        )
        // Case shouldn't matter on Windows
        subprocessBuilder.setEnv(com.google.common.collect.ImmutableMap.of<K?, V?>("SystemDrive", "X:"))
        process = subprocessBuilder.start()
        process.waitFor()
        assertThat(process.exitValue()).isEqualTo(0)

        val buf: ByteArray = process.inputStream.readAllBytes()
        Truth.assertThat(String(buf, java.nio.charset.StandardCharsets.UTF_8).trim { it <= ' ' }).isEqualTo("X:")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyEnvironment() {
        // Check only that TZ was not inherited instead of verifying the entire environment.
        Truth.assertThat(com.google.common.base.Strings.nullToEmpty(java.lang.System.getenv("TZ"))).isNotEmpty()
        val subprocessBuilder: SubprocessBuilder =
            SubprocessBuilder(java.lang.System.getenv(), WindowsSubprocessFactory.INSTANCE)
        subprocessBuilder.setWorkingDirectory(java.io.File("."))
        subprocessBuilder.setArgv(
            com.google.common.collect.ImmutableList.of<E?>(
                mockBinary,
                "-jar",
                mockSubprocess,
                "O\$TZ"
            )
        )
        subprocessBuilder.setEnv(com.google.common.collect.ImmutableMap.of<K?, V?>())
        process = subprocessBuilder.start()
        process.waitFor()
        assertThat(process.exitValue()).isEqualTo(0)

        val buf: ByteArray = process.inputStream.readAllBytes()
        Truth.assertThat(String(buf, java.nio.charset.StandardCharsets.UTF_8).trim { it <= ' ' }).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonEmptyEnvironment() {
        // Check only that TZ was not inherited instead of verifying the entire environment.
        Truth.assertThat(com.google.common.base.Strings.nullToEmpty(java.lang.System.getenv("TZ"))).isNotEmpty()
        val subprocessBuilder: SubprocessBuilder =
            SubprocessBuilder(java.lang.System.getenv(), WindowsSubprocessFactory.INSTANCE)
        subprocessBuilder.setWorkingDirectory(java.io.File("."))
        subprocessBuilder.setArgv(
            com.google.common.collect.ImmutableList.of<E?>(
                mockBinary,
                "-jar",
                mockSubprocess,
                "O\$FOO",
                "O\$BAR",
                "O\$TZ"
            )
        )
        subprocessBuilder.setEnv(com.google.common.collect.ImmutableMap.of<K?, V?>("FOO", "abc", "BAR", "def"))
        process = subprocessBuilder.start()
        process.waitFor()
        assertThat(process.exitValue()).isEqualTo(0)

        val buf: ByteArray = process.inputStream.readAllBytes()
        Truth.assertThat(String(buf, java.nio.charset.StandardCharsets.UTF_8).trim { it <= ' ' }).isEqualTo("abcdef")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInheritedEnvironment() {
        // Check only that TZ was inherited instead of verifying the entire environment.
        Truth.assertThat(com.google.common.base.Strings.nullToEmpty(java.lang.System.getenv("TZ"))).isNotEmpty()
        val subprocessBuilder: SubprocessBuilder =
            SubprocessBuilder(java.lang.System.getenv(), WindowsSubprocessFactory.INSTANCE)
        subprocessBuilder.setWorkingDirectory(java.io.File("."))
        subprocessBuilder.setArgv(
            com.google.common.collect.ImmutableList.of<E?>(
                mockBinary,
                "-jar",
                mockSubprocess,
                "O\$TZ"
            )
        )
        process = subprocessBuilder.start()
        process.waitFor()
        assertThat(process.exitValue()).isEqualTo(0)

        val buf: ByteArray = process.inputStream.readAllBytes()
        Truth.assertThat(String(buf, java.nio.charset.StandardCharsets.UTF_8).trim { it <= ' ' })
            .isEqualTo(java.lang.System.getenv("TZ"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStreamAvailable_zeroAfterClose() {
        val subprocessBuilder: SubprocessBuilder =
            SubprocessBuilder(java.lang.System.getenv(), WindowsSubprocessFactory.INSTANCE)
        subprocessBuilder.setWorkingDirectory(java.io.File("."))
        subprocessBuilder.setArgv(
            com.google.common.collect.ImmutableList.of<E?>(
                mockBinary,
                "-jar",
                mockSubprocess,
                "OHELLO"
            )
        )
        process = subprocessBuilder.start()
        val inputStream: java.io.InputStream = process.inputStream
        // We don't know if the process has already written to the pipe
        Truth.assertThat(inputStream.available()).isAnyOf(0, 5)
        process.waitFor()
        // Windows allows streams to be read after the process has died.
        Truth.assertThat(inputStream.available()).isAnyOf(0, 5)
        inputStream.close()
        Truth.assertThat(
            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                org.junit.function.ThrowingRunnable { inputStream.available() })
        )
            .hasMessageThat()
            .contains("Stream already closed")
    }

    /**
     * An argument and its command-line-escaped counterpart.
     * 
     * 
     * Such escaping ensures that Bazel correctly forwards arguments to subprocesses.
     */
    private class ArgPair(val original: String?, val escaped: String?)

    /** Asserts that a subprocess correctly receives command line arguments.  */
    @Throws(java.lang.Exception::class)
    private fun assertSubprocessReceivesArgsAsIntended(vararg args: ArgPair) {
        // Look up the path of the printarg.exe utility.
        val printArgExe: String? =
            runfiles.rlocation(
                "io_bazel/src/test/java/com/google/devtools/build/lib/windows/printarg.exe"
            )
        Truth.assertThat(printArgExe).isNotEmpty()

        for (arg in args) {
            // Assert that the command-line encoding logic works as intended.
            assertThat(ShellUtils.windowsEscapeArg(arg.original)).isEqualTo(arg.escaped)

            // Create a separate subprocess just for this argument.
            val subprocessBuilder: SubprocessBuilder =
                SubprocessBuilder(java.lang.System.getenv(), WindowsSubprocessFactory.INSTANCE)
            subprocessBuilder.setWorkingDirectory(java.io.File("."))
            subprocessBuilder.setArgv(com.google.common.collect.ImmutableList.of<E?>(printArgExe, arg.original))
            process = subprocessBuilder.start()
            process.waitFor()
            assertThat(process.exitValue()).isEqualTo(0)

            // The subprocess printed its argv[1] in parentheses, e.g. (foo).
            // Assert that it printed exactly the *original* argument in parentheses.
            val buf: ByteArray = process.inputStream.readAllBytes()
            val actual: String = String(buf, java.nio.charset.StandardCharsets.UTF_8).trim { it <= ' ' }
            Truth.assertThat(actual).isEqualTo("(" + arg.original + ")")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubprocessReceivesArgsAsIntended() {
        assertSubprocessReceivesArgsAsIntended(
            ArgPair("", "\"\""),
            ArgPair(" ", "\" \""),
            ArgPair("\"", "\"\\\"\""),
            ArgPair("\"\\", "\"\\\"\\\\\""),
            ArgPair("\\", "\\"),
            ArgPair("\\\"", "\"\\\\\\\"\""),
            ArgPair("with space", "\"with space\""),
            ArgPair("with^caret", "with^caret"),
            ArgPair("space ^caret", "\"space ^caret\""),
            ArgPair("caret^ space", "\"caret^ space\""),
            ArgPair("with\"quote", "\"with\\\"quote\""),
            ArgPair("with\\backslash", "with\\backslash"),
            ArgPair("one\\ backslash and \\space", "\"one\\ backslash and \\space\""),
            ArgPair("two\\\\backslashes", "two\\\\backslashes"),
            ArgPair("two\\\\ backslashes \\\\and space", "\"two\\\\ backslashes \\\\and space\""),
            ArgPair("one\\\"x", "\"one\\\\\\\"x\""),
            ArgPair("two\\\\\"x", "\"two\\\\\\\\\\\"x\""),
            ArgPair("a \\ b", "\"a \\ b\""),
            ArgPair("a \\\" b", "\"a \\\\\\\" b\""),
            ArgPair("A", "A"),
            ArgPair("\"a\"", "\"\\\"a\\\"\""),
            ArgPair("B C", "\"B C\""),
            ArgPair("\"b c\"", "\"\\\"b c\\\"\""),
            ArgPair("D\"E", "\"D\\\"E\""),
            ArgPair("\"d\"e\"", "\"\\\"d\\\"e\\\"\""),
            ArgPair("C:\\F G", "\"C:\\F G\""),
            ArgPair("\"C:\\f g\"", "\"\\\"C:\\f g\\\"\""),
            ArgPair("C:\\H\"I", "\"C:\\H\\\"I\""),
            ArgPair("\"C:\\h\"i\"", "\"\\\"C:\\h\\\"i\\\"\""),
            ArgPair("C:\\J\\\"K", "\"C:\\J\\\\\\\"K\""),
            ArgPair("\"C:\\j\\\"k\"", "\"\\\"C:\\j\\\\\\\"k\\\"\""),
            ArgPair("C:\\L M ", "\"C:\\L M \""),
            ArgPair("\"C:\\l m \"", "\"\\\"C:\\l m \\\"\""),
            ArgPair("C:\\N O\\", "\"C:\\N O\\\\\""),
            ArgPair("\"C:\\n o\\\"", "\"\\\"C:\\n o\\\\\\\"\""),
            ArgPair("C:\\P Q\\ ", "\"C:\\P Q\\ \""),
            ArgPair("\"C:\\p q\\ \"", "\"\\\"C:\\p q\\ \\\"\""),
            ArgPair("C:\\R\\S\\", "C:\\R\\S\\"),
            ArgPair("C:\\R x\\S\\", "\"C:\\R x\\S\\\\\""),
            ArgPair("\"C:\\r\\s\\\"", "\"\\\"C:\\r\\s\\\\\\\"\""),
            ArgPair("\"C:\\r x\\s\\\"", "\"\\\"C:\\r x\\s\\\\\\\"\""),
            ArgPair("C:\\T U\\W\\", "\"C:\\T U\\W\\\\\""),
            ArgPair("\"C:\\t u\\w\\\"", "\"\\\"C:\\t u\\w\\\\\\\"\"")
        )
    }
}
