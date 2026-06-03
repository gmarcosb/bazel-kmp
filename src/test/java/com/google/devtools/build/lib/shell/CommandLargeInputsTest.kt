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
import com.google.devtools.build.lib.shell.TestUtil
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.ByteArrayInputStream
import java.util.Random

/** Tests the command class with large inputs  */
@RunWith(JUnit4::class)
class CommandLargeInputsTest {
    @Before
    @Throws(java.lang.Exception::class)
    fun configureLogger() {
        // enable all log statements to ensure there are no problems with
        // logging code
        java.util.logging.Logger.getLogger("com.google.devtools.build.lib.shell.Command")
            .setLevel(java.util.logging.Level.FINEST)
    }

    private val randomBytes: ByteArray
        get() {
            val randomBytes: ByteArray
            val rand: Random = Random(-0x21524111)
            randomBytes = ByteArray(10000)
            rand.nextBytes(randomBytes)
            return randomBytes
        }

    private val allByteValues: ByteArray
        get() {
            val allByteValues = ByteArray(Byte.Companion.MAX_VALUE - Byte.Companion.MIN_VALUE)
            for (i in allByteValues.indices) {
                allByteValues[i] = (i + Byte.Companion.MIN_VALUE).toByte()
            }
            return allByteValues
        }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCatRandomBinaryToOutputStream() {
        val command: Command = Command(com.google.common.collect.ImmutableList.of<E?>("cat"), java.lang.System.getenv())
        val randomBytes = this.randomBytes
        val `in`: ByteArrayInputStream = ByteArrayInputStream(randomBytes)

        val result: CommandResult = command.executeAsync(`in`, Command.KILL_SUBPROCESS_ON_INTERRUPT).get()
        assertThat(result.terminationStatus().getRawExitCode()).isEqualTo(0)
        TestUtil.assertArrayEquals(randomBytes, result.getStdout())
        assertThat(result.getStderr()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCatRandomBinaryToErrorStream() {
        val command: Command =
            Command(
                com.google.common.collect.ImmutableList.of<E?>("/bin/sh", "-c", "cat >&2"),
                java.lang.System.getenv()
            )
        val randomBytes = this.randomBytes
        val `in`: ByteArrayInputStream = ByteArrayInputStream(randomBytes)

        val result: CommandResult = command.executeAsync(`in`, Command.KILL_SUBPROCESS_ON_INTERRUPT).get()
        assertThat(result.terminationStatus().getRawExitCode()).isEqualTo(0)
        TestUtil.assertArrayEquals(randomBytes, result.getStderr())
        assertThat(result.getStdout()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCatRandomBinaryFromInputStreamToOutputStream() {
        val command: Command = Command(com.google.common.collect.ImmutableList.of<E?>("cat"), java.lang.System.getenv())
        val out: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val err: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val randomBytes = this.randomBytes
        val `in`: ByteArrayInputStream = ByteArrayInputStream(randomBytes)

        val result: CommandResult =
            command.executeAsync(`in`, out, err, Command.KILL_SUBPROCESS_ON_INTERRUPT).get()
        assertThat(result.terminationStatus().getRawExitCode()).isEqualTo(0)
        Truth.assertThat(err.toByteArray()).isEmpty()
        TestUtil.assertArrayEquals(randomBytes, out.toByteArray())
        assertOutAndErrNotAvailable(result)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCatRandomBinaryFromInputStreamToErrorStream() {
        val command: Command =
            Command(
                com.google.common.collect.ImmutableList.of<E?>("/bin/sh", "-c", "cat >&2"),
                java.lang.System.getenv()
            )
        val out: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val err: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val randomBytes = this.randomBytes
        val `in`: ByteArrayInputStream = ByteArrayInputStream(randomBytes)

        val result: CommandResult =
            command.executeAsync(`in`, out, err, Command.KILL_SUBPROCESS_ON_INTERRUPT).get()
        assertThat(result.terminationStatus().getRawExitCode()).isEqualTo(0)
        Truth.assertThat(out.toByteArray()).isEmpty()
        TestUtil.assertArrayEquals(randomBytes, err.toByteArray())
        assertOutAndErrNotAvailable(result)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStdoutInterleavedWithStdErr() {
        val command: Command =
            Command(
                com.google.common.collect.ImmutableList.of<E?>(
                    "/bin/bash",
                    "-c",
                    "for i in $( seq 0 999); do (echo OUT\$i >&1) && (echo ERR\$i  >&2); done"
                ),
                java.lang.System.getenv()
            )
        val out: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val err: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        command.execute(out, err)
        val expectedOut: java.lang.StringBuilder = java.lang.StringBuilder()
        val expectedErr: java.lang.StringBuilder = java.lang.StringBuilder()
        for (i in 0..999) {
            expectedOut.append("OUT").append(i).append("\n")
            expectedErr.append("ERR").append(i).append("\n")
        }
        Truth.assertThat(out.toString("UTF-8")).isEqualTo(expectedOut.toString())
        Truth.assertThat(err.toString("UTF-8")).isEqualTo(expectedErr.toString())
    }

    private fun assertOutAndErrNotAvailable(result: CommandResult) {
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable { result.getStdout() })
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable { result.getStderr() })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCatAllByteValues() {
        val command: Command = Command(com.google.common.collect.ImmutableList.of<E?>("cat"), java.lang.System.getenv())
        val allByteValues = this.allByteValues
        val `in`: ByteArrayInputStream = ByteArrayInputStream(allByteValues)

        val result: CommandResult = command.executeAsync(`in`, Command.KILL_SUBPROCESS_ON_INTERRUPT).get()
        assertThat(result.terminationStatus().getRawExitCode()).isEqualTo(0)
        assertThat(result.getStderr()).isEmpty()
        TestUtil.assertArrayEquals(allByteValues, result.getStdout())
    }
}
