// Copyright 2017 The Bazel Authors. All rights reserved.
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
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.build.lib.exec.util.TestExecutorBuilder.build
import com.google.devtools.build.lib.packages.util.Crosstool.CcToolchainConfig.Builder.build
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Unit tests for [CommandResult].  */
@RunWith(JUnit4::class)
class CommandResultTest {
    @org.junit.Test
    fun testBuilder_withNoStderr() {
        val e: java.lang.Exception? =
            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                org.junit.function.ThrowingRunnable {
                    CommandResult.builder()
                        .setStdoutStream(CommandResult.EMPTY_OUTPUT)
                        .setTerminationStatus(TerminationStatus(0, false))
                        .build()
                })
        Truth.assertThat(e).hasMessageThat().contains("stderrStream")
    }

    @org.junit.Test
    fun testBuilder_withNoStdout() {
        val e: java.lang.Exception? =
            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                org.junit.function.ThrowingRunnable {
                    CommandResult.builder()
                        .setStderrStream(CommandResult.EMPTY_OUTPUT)
                        .setTerminationStatus(TerminationStatus(0, false))
                        .build()
                })
        Truth.assertThat(e).hasMessageThat().contains("stdoutStream")
    }

    @org.junit.Test
    fun testBuilder_withNoTerminationStatus() {
        val e: java.lang.Exception? =
            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                org.junit.function.ThrowingRunnable {
                    CommandResult.builder()
                        .setStdoutStream(CommandResult.EMPTY_OUTPUT)
                        .setStderrStream(CommandResult.EMPTY_OUTPUT)
                        .build()
                })
        Truth.assertThat(e).hasMessageThat().contains("terminationStatus")
    }

    @org.junit.Test
    fun testBuilder_withNoExecutionTime() {
        val commandResult: CommandResult =
            CommandResult.builder()
                .setStdoutStream(CommandResult.EMPTY_OUTPUT)
                .setStderrStream(CommandResult.EMPTY_OUTPUT)
                .setTerminationStatus(TerminationStatus(0, false))
                .build()
        assertThat(commandResult.wallExecutionTime()).isEmpty()
        assertThat(commandResult.userExecutionTime()).isEmpty()
        assertThat(commandResult.systemExecutionTime()).isEmpty()
    }

    @org.junit.Test
    fun testBuilder_withExecutionTime() {
        val commandResult: CommandResult =
            CommandResult.builder()
                .setStdoutStream(CommandResult.EMPTY_OUTPUT)
                .setStderrStream(CommandResult.EMPTY_OUTPUT)
                .setTerminationStatus(TerminationStatus(0, false))
                .setWallExecutionTime(java.time.Duration.ofMillis(1929))
                .setUserExecutionTime(java.time.Duration.ofMillis(1492))
                .setSystemExecutionTime(java.time.Duration.ofMillis(1787))
                .build()
        assertThat(commandResult.wallExecutionTime()).hasValue(java.time.Duration.ofMillis(1929))
        assertThat(commandResult.userExecutionTime()).hasValue(java.time.Duration.ofMillis(1492))
        assertThat(commandResult.systemExecutionTime()).hasValue(java.time.Duration.ofMillis(1787))
    }
}
