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
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase.ActionExecutionContextBuilder.build
import com.google.devtools.build.lib.analysis.util.ScratchAttributeWriter.write
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase.write
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.build.lib.exec.util.TestExecutorBuilder.build
import com.google.devtools.build.lib.packages.util.Crosstool.CcToolchainConfig.Builder.build
import org.junit.Assume
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.IOException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Tests of the interaction of Thread.interrupt and Command.execute.
 * 
 * 
 * Read http://www.ibm.com/developerworks/java/library/j-jtp05236/ for background material.
 * 
 * 
 * NOTE: This test is dependent on thread timings. Under extreme machine load it's possible that
 * this test could fail spuriously or intermittently. In that case, adjust the timing constants to
 * increase the tolerance.
 */
@RunWith(JUnit4::class)
class InterruptibleTest {
    private val mainThread: java.lang.Thread = java.lang.Thread.currentThread()

    // Interrupt main thread after 1 second.  Hopefully by then /bin/sleep
    // should be running.
    private val interrupter: java.lang.Thread = java.lang.Thread(
        java.lang.Runnable {
            try {
                java.lang.Thread.sleep(1000) // 1 sec
            } catch (e: java.lang.InterruptedException) {
                throw java.lang.IllegalStateException("Unexpected interrupt!")
            }
            mainThread.interrupt()
        })

    private var command: Command? = null
    private var tmpDir: Path? = null

    @Before
    @Throws(IOException::class)
    fun startInterrupter() {
        java.lang.Thread.interrupted() // side effect: clear interrupted status
        Truth.assertWithMessage("Unexpected interruption!").that(mainThread.isInterrupted()).isFalse()

        // We interrupt after 1 sec, so this gives us plenty of time for the library to notice the
        // subprocess exit.
        tmpDir = java.nio.file.Files.createTempDirectory("script_outs")
        val dirString = tmpDir.toString() + "/"
        val script: Path =
            java.nio.file.Files.createTempFile(
                "script",
                ".sh",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxrwx"))
            )
        java.nio.file.Files.write(
            script,
            com.google.common.collect.ImmutableList.of<String?>(
                "echo start", "sleep 20", "touch " + dirString + "endfile", "echo end >&2"
            )
        )
        this.command =
            Command(com.google.common.collect.ImmutableList.of<E?>(script.toString()), java.lang.System.getenv())

        interrupter.start()
    }

    @org.junit.After
    @Throws(java.lang.Exception::class)
    fun waitForInterrupter() {
        interrupter.join()
        java.lang.Thread.interrupted() // Clear interrupted status, or else other tests may fail.
    }

    /**
     * Test that interrupting a thread in an "uninterruptible" Command.execute marks the thread as
     * interrupted, and does not terminate the subprocess.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun uninterruptibleCommandRunsToCompletion() {
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.WINDOWS)

        val result: CommandResult =
            command.executeAsync(Command.NO_INPUT, Command.CONTINUE_SUBPROCESS_ON_INTERRUPT).get()
        assertThat(result.terminationStatus().success()).isTrue()
        Truth.assertThat(String(result.getStdout(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("start\n")
        Truth.assertThat(String(result.getStderr(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("end\n")
        Truth.assertThat(java.nio.file.Files.exists(tmpDir.resolve("endfile"))).isTrue()

        // The interrupter thread should have exited about 1000ms ago.
        Truth.assertWithMessage("Interrupter thread is still alive!").that(interrupter.isAlive()).isFalse()

        // The interrupter thread should have set the main thread's interrupt flag.
        Truth.assertWithMessage("Main thread was not interrupted during command execution!")
            .that(mainThread.isInterrupted())
            .isTrue()
    }

    /**
     * Test that interrupting a thread in an "interruptible" Command.execute does terminate the
     * subprocess and throws an [InterruptedException].
     */
    @org.junit.Test
    @Throws(CommandException::class)
    fun interruptibleCommandIsInterrupted() {
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.WINDOWS)
        val result: FutureCommandResult = command.executeAsync()
        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            result::get
        )
        Truth.assertThat(java.nio.file.Files.exists(tmpDir.resolve("endfile"))).isFalse()
        assertThat(result.isDone()).isTrue()
    }
}
