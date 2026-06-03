// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.shell

import com.google.common.truth.Truth
import com.google.devtools.build.lib.testutil.BlazeTestUtils
import com.google.devtools.build.lib.testutil.TestConstants
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Collections

/**
 * Unit tests for [Command]. This test will only succeed on Linux, currently, because of its
 * non-portable nature.
 */
@RunWith(JUnit4::class)
class CommandTest {
    // Platform-independent tests ----------------------------------------------
    @Before
    @Throws(java.lang.Exception::class)
    fun configureLogger() {
        // Enable all log statements to ensure there are no problems with logging code.
        java.util.logging.Logger.getLogger("com.google.devtools.build.lib.shell.Command")
            .setLevel(java.util.logging.Level.FINEST)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)  // ImmutableList is null-hostile
    fun testIllegalArgs() {
        org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
            java.lang.NullPointerException::class.java,
            org.junit.function.ThrowingRunnable { Command(null, com.google.common.collect.ImmutableMap.of<K?, V?>()) })

        org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
            java.lang.NullPointerException::class.java,
            org.junit.function.ThrowingRunnable {
                Command(
                    java.util.List.< E > of < E ? > ("/bin/true",
                    null
                ), com.google.common.collect.ImmutableMap.of<K?, V?>()).execute()
            })

        val r: Command = Command(
            com.google.common.collect.ImmutableList.of<E?>("foo"),
            com.google.common.collect.ImmutableMap.of<K?, V?>()
        )
        org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
            java.lang.NullPointerException::class.java,
            org.junit.function.ThrowingRunnable {
                r.executeAsync(
                    null as java.io.InputStream?,
                    Command.KILL_SUBPROCESS_ON_INTERRUPT
                ).get()
            })
    }

    @org.junit.Test
    fun testGetters() {
        val workingDir: java.io.File = java.io.File(".")
        val env: MutableMap<String?, String?> = Collections.singletonMap<String?, String?>("foo", "bar")
        val args: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("command")
        val command: Command = Command(args, env, workingDir, com.google.common.collect.ImmutableMap.of<K?, V?>())
        assertThat(command.getArguments()).containsExactlyElementsIn(args)
        for (key in env.keys) {
            assertThat(command.getEnvironment()).containsEntry(key, env.get(key))
        }
        assertThat(command.getWorkingDirectory()).isEqualTo(workingDir)
    }

    // Platform-dependent tests ------------------------------------------------
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSimpleCommand() {
        val command: Command = Command(
            com.google.common.collect.ImmutableList.of<E?>("ls"),
            com.google.common.collect.ImmutableMap.of<K?, V?>()
        )
        val result: CommandResult = command.execute()
        assertThat(result.terminationStatus().success()).isTrue()
        assertThat(result.getStderr()).isEmpty()
        assertThat(result.getStdout().length).isGreaterThan(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArguments() {
        val command: Command = Command(
            com.google.common.collect.ImmutableList.of<E?>("echo", "foo"),
            com.google.common.collect.ImmutableMap.of<K?, V?>()
        )
        checkSuccess(command.execute(), "foo\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonEmptyEnvironment() {
        val env: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>("FOO", "abc", "BAR", "def")
        val command: Command =
            Command(
                com.google.common.collect.ImmutableList.of<E?>("/bin/sh", "-c", "echo \$FOO \$BAR"),
                env,
                null,
                com.google.common.collect.ImmutableMap.of<K?, V?>("FOO", "not abc", "BAR", "not def")
            )
        checkSuccess(command.execute(), "abc def\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyEnvironment() {
        val command: Command =
            Command(
                com.google.common.collect.ImmutableList.of<E?>("/bin/sh", "-c", "echo \$TZ"),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                null,
                com.google.common.collect.ImmutableMap.of<K?, V?>("TZ", "not empty")
            )
        checkSuccess(command.execute(), "\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInheritedEnvironment() {
        val command: Command =
            Command(
                com.google.common.collect.ImmutableList.of<E?>("/bin/sh", "-c", "echo \$TZ"),
                null,
                null,
                com.google.common.collect.ImmutableMap.of<K?, V?>("TZ", "not empty")
            )
        checkSuccess(command.execute(), "not empty\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWorkingDir() {
        val command: Command = Command(
            com.google.common.collect.ImmutableList.of<E?>("pwd"),
            null,
            java.io.File("/"),
            com.google.common.collect.ImmutableMap.of<K?, V?>()
        )
        checkSuccess(command.execute(), "/\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStdin() {
        val command: Command = Command(
            com.google.common.collect.ImmutableList.of<E?>("grep", "bar"),
            com.google.common.collect.ImmutableMap.of<K?, V?>()
        )
        val `in`: java.io.InputStream = ByteArrayInputStream("foobarbaz".toByteArray())
        checkSuccess(
            command.executeAsync(`in`, Command.KILL_SUBPROCESS_ON_INTERRUPT).get(), "foobarbaz\n"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRawCommand() {
        val command: Command =
            Command(
                com.google.common.collect.ImmutableList.of<E?>("perl", "-e", "print 'a'x100000"),
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )
        val result: CommandResult = command.execute()
        assertThat(result.terminationStatus().success()).isTrue()
        assertThat(result.getStderr()).isEmpty()
        assertThat(result.getStdout().length).isGreaterThan(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRawCommandWithDir() {
        val command: Command = Command(
            com.google.common.collect.ImmutableList.of<E?>("pwd"),
            null,
            java.io.File("/"),
            com.google.common.collect.ImmutableMap.of<K?, V?>()
        )
        val result: CommandResult = command.execute()
        checkSuccess(result, "/\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHugeOutput() {
        val command: Command =
            Command(
                com.google.common.collect.ImmutableList.of<E?>("perl", "-e", "print 'a'x100000"),
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )
        val result: CommandResult = command.execute()
        assertThat(result.terminationStatus().success()).isTrue()
        assertThat(result.getStderr()).isEmpty()
        assertThat(result.getStdout()).hasLength(100000)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoStreamingInputForCat() {
        val command: Command = Command(
            com.google.common.collect.ImmutableList.of<E?>("/bin/cat"),
            com.google.common.collect.ImmutableMap.of<K?, V?>()
        )
        val emptyInput: ByteArrayInputStream = ByteArrayInputStream(ByteArray(0))
        val out: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val err: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val result: CommandResult =
            command.executeAsync(emptyInput, out, err, Command.KILL_SUBPROCESS_ON_INTERRUPT).get()
        assertThat(result.terminationStatus().success()).isTrue()
        Truth.assertThat(out.toString("UTF-8")).isEmpty()
        Truth.assertThat(err.toString("UTF-8")).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoInputForCat() {
        val command: Command = Command(
            com.google.common.collect.ImmutableList.of<E?>("/bin/cat"),
            com.google.common.collect.ImmutableMap.of<K?, V?>()
        )
        val result: CommandResult = command.execute()
        assertThat(result.terminationStatus().success()).isTrue()
        Truth.assertThat(String(result.getStdout(), charset("UTF-8"))).isEmpty()
        Truth.assertThat(String(result.getStderr(), charset("UTF-8"))).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testProvidedOutputStreamCapturesHelloWorld() {
        val helloWorld = "Hello, world."
        val command: Command = Command(
            com.google.common.collect.ImmutableList.of<E?>("/bin/echo", helloWorld),
            com.google.common.collect.ImmutableMap.of<K?, V?>()
        )
        val stdOut: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val stdErr: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        command.execute(stdOut, stdErr)
        Truth.assertThat(stdOut.toString("UTF-8")).isEqualTo(helloWorld + "\n")
        Truth.assertThat(stdErr.toByteArray()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAsynchronous() {
        val tempFile: java.io.File = java.io.File.createTempFile("googlecron-test", "tmp")
        tempFile.delete()
        val command: Command =
            Command(
                com.google.common.collect.ImmutableList.of<E?>("touch", tempFile.getAbsolutePath()),
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )
        val result: FutureCommandResult = command.executeAsync()
        result.get()
        Truth.assertThat(tempFile.exists()).isTrue()
        assertThat(result.isDone()).isTrue()
        tempFile.delete()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAsynchronousWithOutputStreams() {
        val helloWorld = "Hello, world."
        val command: Command = Command(
            com.google.common.collect.ImmutableList.of<E?>("/bin/echo", helloWorld),
            com.google.common.collect.ImmutableMap.of<K?, V?>()
        )
        val emptyInput: ByteArrayInputStream = ByteArrayInputStream(ByteArray(0))
        val stdOut: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val stdErr: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val result: FutureCommandResult =
            command.executeAsync(emptyInput, stdOut, stdErr,  /* killSubprocessOnInterrupt= */false)
        result.get() // Make sure the process actually finished
        Truth.assertThat(stdOut.toString("UTF-8")).isEqualTo(helloWorld + "\n")
        Truth.assertThat(stdErr.toByteArray()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTimeout() {
        // Sleep for 3 seconds, but timeout after 1 second.
        val command: Command =
            Command(
                com.google.common.collect.ImmutableList.of<E?>("sleep", "3"),
                null,
                null,
                java.time.Duration.ofSeconds(1),
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )
        val ate: AbnormalTerminationException =
            org.junit.Assert.assertThrows<T>(
                AbnormalTerminationException::class.java,
                org.junit.function.ThrowingRunnable { command.execute() })
        checkCommandElements(ate, "sleep", "3")
        checkATE(ate)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTimeoutDoesntFire() {
        val command: Command =
            Command(
                com.google.common.collect.ImmutableList.of<E?>("cat"),
                null,
                null,
                java.time.Duration.ofSeconds(2),
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )
        val `in`: java.io.InputStream =
            ByteArrayInputStream(byteArrayOf('H'.code.toByte(), 'i'.code.toByte(), '!'.code.toByte()))
        command.executeAsync(`in`, Command.KILL_SUBPROCESS_ON_INTERRUPT).get()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCommandDoesNotExist() {
        val command: Command = Command(
            com.google.common.collect.ImmutableList.of<E?>("thisisnotreal"),
            com.google.common.collect.ImmutableMap.of<K?, V?>()
        )
        val e: ExecFailedException? = org.junit.Assert.assertThrows<T?>(
            ExecFailedException::class.java,
            org.junit.function.ThrowingRunnable { command.execute() })
        checkCommandElements(e, "thisisnotreal")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoSuchCommand() {
        val command: Command = Command(
            com.google.common.collect.ImmutableList.of<E?>("thisisnotreal"),
            com.google.common.collect.ImmutableMap.of<K?, V?>()
        )
        org.junit.Assert.assertThrows<T?>(
            ExecFailedException::class.java,
            org.junit.function.ThrowingRunnable { command.execute() })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExitCodes() {
        // 0 => success
        run {
            val args: com.google.common.collect.ImmutableList<String?> =
                com.google.common.collect.ImmutableList.of<String?>("/bin/sh", "-c", "exit 0")
            val result: CommandResult = Command(args, com.google.common.collect.ImmutableMap.of<K?, V?>()).execute()
            val status: TerminationStatus = result.terminationStatus()
            assertThat(status.success()).isTrue()
            assertThat(status.exited()).isTrue()
            assertThat(status.getExitCode()).isEqualTo(0)
        }

        // Every exit value in range [1-255] is reported as such (except [129-191],
        // which map to signals).
        for (exit in intArrayOf(1, 2, 3, 127, 128, 192, 255)) {
            val args: com.google.common.collect.ImmutableList<String?> =
                com.google.common.collect.ImmutableList.of<String?>("/bin/sh", "-c", "exit " + exit)
            val e: BadExitStatusException =
                org.junit.Assert.assertThrows<T>(
                    "Should have exited with status " + exit,
                    BadExitStatusException::class.java,
                    org.junit.function.ThrowingRunnable {
                        Command(
                            args,
                            com.google.common.collect.ImmutableMap.of<K?, V?>()
                        ).execute()
                    })
            assertThat(e).hasMessageThat().isEqualTo("Process exited with status " + exit)
            checkCommandElements(e, "/bin/sh", "-c", "exit " + exit)
            val status: TerminationStatus = e.getResult().terminationStatus()
            assertThat(status.success()).isFalse()
            assertThat(status.exited()).isTrue()
            assertThat(status.getExitCode()).isEqualTo(exit)
            assertThat(status.toShortString()).isEqualTo("Exit " + exit)
        }

        // negative exit values are modulo 256:
        for (exit in intArrayOf(-1, -2, -3)) {
            val expected = 256 + exit
            val args: com.google.common.collect.ImmutableList<String?> =
                com.google.common.collect.ImmutableList.of<String?>("/bin/bash", "-c", "exit " + exit)
            val e: BadExitStatusException =
                org.junit.Assert.assertThrows<T>(
                    "Should have exited with status " + expected,
                    BadExitStatusException::class.java,
                    org.junit.function.ThrowingRunnable {
                        Command(
                            args,
                            com.google.common.collect.ImmutableMap.of<K?, V?>()
                        ).execute()
                    })
            assertThat(e).hasMessageThat().isEqualTo("Process exited with status " + expected)
            checkCommandElements(e, "/bin/bash", "-c", "exit " + exit)
            val status: TerminationStatus = e.getResult().terminationStatus()
            assertThat(status.success()).isFalse()
            assertThat(status.exited()).isTrue()
            assertThat(status.getExitCode()).isEqualTo(expected)
            assertThat(status.toShortString()).isEqualTo("Exit " + expected)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFailedWithSignal() {
        // SIGHUP, SIGINT, SIGKILL, SIGTERM
        for (signal in intArrayOf(1, 2, 9, 15)) {
            // Invoke a C++ program (killmyself.cc) that will die
            // with the specified signal.
            val killmyself =
                (BlazeTestUtils.runfilesDir()
                        + "/"
                        + TestConstants.JAVATESTS_ROOT
                        + "/com/google/devtools/build/lib/shell/killmyself")
            val args: com.google.common.collect.ImmutableList<String?> =
                com.google.common.collect.ImmutableList.of<String?>(killmyself, "" + signal)
            val e: AbnormalTerminationException =
                org.junit.Assert.assertThrows<T>(
                    "Expected signal " + signal,
                    AbnormalTerminationException::class.java,
                    org.junit.function.ThrowingRunnable {
                        Command(
                            args,
                            com.google.common.collect.ImmutableMap.of<K?, V?>()
                        ).execute()
                    })
            assertThat(e).hasMessageThat().isEqualTo("Process terminated by signal " + signal)
            checkCommandElements(e, killmyself, "" + signal)
            val status: TerminationStatus = e.getResult().terminationStatus()
            assertThat(status.success()).isFalse()
            assertThat(status.exited()).isFalse()
            assertThat(status.getTerminatingSignal()).isEqualTo(signal)

            when (signal) {
                1 -> assertThat(status.toShortString()).isEqualTo("Hangup")
                2 -> assertThat(status.toShortString()).isEqualTo("Interrupt")
                9 -> assertThat(status.toShortString()).isEqualTo("Killed")
                15 -> assertThat(status.toShortString()).isEqualTo("Terminated")
                else -> {}
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOnlyReadsPartialInput() {
        // -c == --bytes, but -c also works on Darwin.
        val command: Command = Command(
            com.google.common.collect.ImmutableList.of<E?>("head", "-c", "500"),
            com.google.common.collect.ImmutableMap.of<K?, V?>()
        )
        val out: java.io.OutputStream = java.io.ByteArrayOutputStream()
        val `in`: java.io.InputStream =
            object : java.io.InputStream() {
                override fun read(): Int {
                    return 0 // write an unbounded amount
                }
            }

        val result: CommandResult =
            command.executeAsync(`in`, out, out, Command.KILL_SUBPROCESS_ON_INTERRUPT).get()
        val status: TerminationStatus = result.terminationStatus()
        assertThat(status.success()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlushing() {
        val command: Command =
            Command( // On darwin, /bin/sh does not support -n for the echo builtin.
                com.google.common.collect.ImmutableList.of<E?>("/bin/bash", "-c", "echo -n Foo; sleep 0.1; echo Bar"),
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )
        // We run this command, passing in a special output stream that records when each flush()
        // occurs. We test that a flush occurs after writing "Foo" and that another flush occurs after
        // writing "Bar\n".
        val flushed = BooleanArray(8)
        val out: java.io.OutputStream =
            object : java.io.OutputStream() {
                private var count = 0

                @Throws(IOException::class)
                override fun write(b: Int) {
                    count++
                }

                @Throws(IOException::class)
                override fun flush() {
                    flushed[count] = true
                }
            }
        command.execute(out, java.lang.System.err)
        Truth.assertThat(flushed[0]).isFalse()
        Truth.assertThat(flushed[1]).isFalse() // 'F'
        Truth.assertThat(flushed[2]).isFalse() // 'o'
        Truth.assertThat(flushed[3]).isTrue() // 'o'   <- expect flush here.
        Truth.assertThat(flushed[4]).isFalse() // 'B'
        Truth.assertThat(flushed[5]).isFalse() // 'a'
        Truth.assertThat(flushed[6]).isFalse() // 'r'
        Truth.assertThat(flushed[7]).isTrue() // '\n'
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputStreamThrowsException() {
        val out: java.io.OutputStream =
            object : java.io.OutputStream() {
                @Throws(IOException::class)
                override fun write(b: Int) {
                    throw IOException()
                }
            }
        val command: Command = Command(
            com.google.common.collect.ImmutableList.of<E?>("/bin/echo", "foo"),
            com.google.common.collect.ImmutableMap.of<K?, V?>()
        )
        val e: AbnormalTerminationException =
            org.junit.Assert.assertThrows<T>(
                AbnormalTerminationException::class.java,
                org.junit.function.ThrowingRunnable { command.execute(out, out) })
        checkCommandElements(e, "/bin/echo", "foo")
        assertThat(e).hasMessageThat().isEqualTo("java.io.IOException")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputStreamThrowsExceptionAndCommandFails() {
        val out: java.io.OutputStream =
            object : java.io.OutputStream() {
                @Throws(IOException::class)
                override fun write(b: Int) {
                    throw IOException()
                }
            }
        val command: Command = Command(
            com.google.common.collect.ImmutableList.of<E?>("cat", "/dev/thisisnotreal"),
            com.google.common.collect.ImmutableMap.of<K?, V?>()
        )
        val e: AbnormalTerminationException =
            org.junit.Assert.assertThrows<T>(
                AbnormalTerminationException::class.java,
                org.junit.function.ThrowingRunnable { command.execute(out, out) })
        checkCommandElements(e, "cat", "/dev/thisisnotreal")
        val status: TerminationStatus = e.getResult().terminationStatus()
        // Subprocess either gets a SIGPIPE trying to write to our output stream,
        // or it exits with failure.  Both are observed, nondetermistically.
        Truth.assertThat(if (status.exited()) status.getExitCode() === 1 else status.getTerminatingSignal() === 13)
            .isTrue()
        assertWithMessage(e.getMessage())
            .that(
                e.getMessage()
                    .endsWith("also encountered an error while attempting " + "to retrieve output")
            )
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRelativePath() {
        val command: Command =
            Command(
                com.google.common.collect.ImmutableList.of<E?>("relative/path/to/binary"),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                java.io.File("/working/directory"),
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )
        assertThat(command.getArguments().get(0))
            .isEqualTo("/working/directory/relative/path/to/binary")
    }

    companion object {
        private fun checkCommandElements(e: CommandException, vararg expected: String?) {
            assertThat(e.getCommand().getArguments()).containsExactlyElementsIn(expected)
        }

        private fun checkATE(ate: AbnormalTerminationException) {
            val result: CommandResult = ate.getResult()
            assertThat(result.terminationStatus().success()).isFalse()
        }

        private fun checkSuccess(result: CommandResult, expectedOutput: String?) {
            assertThat(result.terminationStatus().success()).isTrue()
            assertThat(result.getStderr()).isEmpty()
            Truth.assertThat(String(result.getStdout())).isEqualTo(expectedOutput)
        }
    }
}
