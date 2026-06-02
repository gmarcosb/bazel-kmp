// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.blackbox.framework

import com.google.common.collect.Lists
import com.google.common.truth.Truth
import com.google.common.util.concurrent.MoreExecutors
import com.google.devtools.build.lib.util.OS
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.*
import java.util.stream.Collectors

/** Test of [ProcessRunner]  */
@RunWith(JUnit4::class)
class ProcessRunnerTest {
    private var directory: Path? = null
    private var path: Path? = null

    @Before
    @Throws(Exception::class)
    fun setUp() {
        directory = Files.createTempDirectory(javaClass.getSimpleName())
        path = Files.createTempFile(directory, "script", if (isWindows) ".bat" else "")
        Truth.assertThat(Files.exists(path)).isTrue()
        Truth.assertThat(path!!.toFile().setExecutable(true)).isTrue()
        path!!.toFile().deleteOnExit()
        directory!!.toFile().deleteOnExit()
    }

    @Test
    @Throws(Exception::class)
    fun testSuccess() {
        Files.write(path, createScriptText( /* exit code */0,  /* output */"Hello!",  /* error */null))

        val parameters = createBuilder()!!.build()
        val result = ProcessRunner(parameters, executorService).runSynchronously()

        Truth.assertThat(result.exitCode()).isEqualTo(0)
        Truth.assertThat(result.outString()).isEqualTo("Hello!")
        Truth.assertThat(result.errString()).isEmpty()
    }

    @Test
    @Throws(Exception::class)
    fun testFailureWithCode() {
        Files.write(
            path, createScriptText( /* exit code */124,  /* output */null,  /* error */"Failure")
        )

        val parameters =
            createBuilder()!!.setExpectedExitCode(124).setExpectedEmptyError(false).build()
        val result = ProcessRunner(parameters, executorService).runSynchronously()

        Truth.assertThat(result.exitCode()).isEqualTo(124)
        Truth.assertThat(result.outString()).isEmpty()
        Truth.assertThat(result.errString()).isEqualTo("Failure")
    }

    @Test
    @Throws(Exception::class)
    fun testFailure() {
        Files.write(
            path, createScriptText( /* exit code */124,  /* output */null,  /* error */"Failure")
        )

        val parameters =
            createBuilder()!!.setExpectedToFail(true).setExpectedEmptyError(false).build()
        val result = ProcessRunner(parameters, executorService).runSynchronously()

        Truth.assertThat(result.exitCode()).isEqualTo(124)
        Truth.assertThat(result.outString()).isEmpty()
        Truth.assertThat(result.errString()).isEqualTo("Failure")
    }

    @Test
    @Throws(Exception::class)
    fun testTimeout() {
        // Windows script to sleep 5 seconds, so that we can test timeout.
        // This script finds PowerShell using %systemroot% variable, which we assume is always
        // defined. It passes some standard parameters like input and output formats,
        // important part is the Command parameter, which actually calls Sleep from PowerShell.
        val windowsScript =
            ("%systemroot%\\system32\\cmd.exe /C \"start /I /B powershell"
                    + " -Version 3.0 -NoLogo -Sta -NoProfile -InputFormat Text -OutputFormat Text"
                    + " -NonInteractive -Command \"\"&PowerShell Sleep 5\"")
        Files.write(path, mutableSetOf<String?>(if (isWindows) windowsScript else "read smthg"))

        val parameters =
            createBuilder()!!
                .setExpectedExitCode(-1)
                .setExpectedEmptyError(false)
                .setTimeoutMillis(100)
                .build()
        try {
            ProcessRunner(parameters, executorService).runSynchronously()
            Truth.assertThat(false).isTrue()
        } catch (e: TimeoutException) {
            // ignore
        }
    }

    @Test
    @Throws(Exception::class)
    fun testRedirect() {
        Files.write(
            path,
            createScriptText( /* exit code */
                12,  /* output */
                Lists.newArrayList<String?>("Info", "Multi", "line"),  /* error */
                mutableListOf<String?>("Failure")
            )
        )

        val out = directory!!.resolve("out.txt")
        val err = directory!!.resolve("err.txt")

        try {
            val parameters =
                createBuilder()!!
                    .setExpectedExitCode(12)
                    .setExpectedEmptyError(false)
                    .setRedirectOutput(out)
                    .setRedirectError(err)
                    .build()
            val result = ProcessRunner(parameters, executorService).runSynchronously()

            Truth.assertThat(result.exitCode()).isEqualTo(12)
            Truth.assertThat(result.outString()).isEqualTo("Info\nMulti\nline")
            Truth.assertThat(result.errString()).isEqualTo("Failure")
        } finally {
            Files.delete(out)
            Files.delete(err)
        }
    }

    private fun createBuilder(): ProcessParameters.Builder? {
        return ProcessParameters.Companion.builder()
            .setWorkingDirectory(directory!!.toFile())
            .setName(path!!.toAbsolutePath().toString())
    }

    companion object {
        private var executorService: ExecutorService? = null

        @BeforeClass
        fun setUpExecutor() {
            // we need only two threads to schedule reading from output and error streams
            executorService =
                MoreExecutors.getExitingExecutorService(
                    Executors.newFixedThreadPool(2) as ThreadPoolExecutor, 1, TimeUnit.SECONDS
                )
        }

        @AfterClass
        fun tearDownExecutor() {
            MoreExecutors.shutdownAndAwaitTermination(executorService, 5, TimeUnit.SECONDS)
        }

        private fun createScriptText(
            exitCode: Int, output: String?, error: String?
        ): MutableList<String?> {
            return Companion.createScriptText(
                exitCode,
                if (output != null) mutableListOf<String?>(output) else null,
                if (error != null) mutableListOf<String?>(error) else null
            )
        }

        private fun createScriptText(
            exitCode: Int, output: MutableList<String?>?, error: MutableList<String?>?
        ): MutableList<String?> {
            val text: MutableList<String?> = Lists.newArrayList<String?>()
            if (isWindows) {
                text.add("@echo off")
            }
            text.addAll(echoStrings(output, ""))
            text.addAll(echoStrings(error, if (isWindows) ">&2" else " 1>&2"))
            text.add((if (isWindows) "exit /b " else "exit ") + exitCode)
            return text
        }

        private fun echoStrings(input: MutableList<String?>?, redirect: String?): MutableList<String?> {
            if (input == null) {
                return mutableListOf<String?>()
            }
            val quote = if (isWindows) "" else "\""
            return input
                .stream()
                .map<String?> { s: String? -> String.format("echo %s%s%s%s", quote, s, quote, redirect) }
                .collect(Collectors.toList())
        }

        private val isWindows: Boolean
            get() = OS.WINDOWS == OS.getCurrent()
    }
}
