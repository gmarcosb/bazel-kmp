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

import com.google.devtools.build.lib.actions.ActionInputHelper

/** Unit tests for [Command]s that are wrapped using the `process-wrapper`.  */
@RunWith(JUnit4::class)
class CommandUsingProcessWrapperTest {
    private val fs: FileSystem = UnixFileSystem(
        DigestHashFunction.SHA256,  /* hashAttributeName= */
        "",
        NativePosixFilesServiceImpl()
    )

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCommand_echo() {
        val commandArguments: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("echo", "worker bees can leave")

        val command: Command = Command(commandArguments, java.lang.System.getenv())
        val commandResult: CommandResult = command.execute()

        assertThat(commandResult.terminationStatus().success()).isTrue()
        com.google.common.truth.Subject.contains("worker bees can leave")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testProcessWrappedCommand_echo() {
        val commandArguments: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("echo", "even drones can fly away")

        val fullCommandLine: MutableList<String?>? = processWrapper.commandLineBuilder(commandArguments).build()

        val command: Command = Command(fullCommandLine, java.lang.System.getenv())
        val commandResult: CommandResult = command.execute()

        assertThat(commandResult.terminationStatus().success()).isTrue()
        com.google.common.truth.Subject.contains("even drones can fly away")
    }

    @Throws(IOException::class, CommandException::class, java.lang.InterruptedException::class)
    private fun checkProcessWrapperStatistics(
        userTimeToSpend: java.time.Duration,
        systemTimeToSpend: java.time.Duration
    ) {
        val commandArguments: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                cpuTimeSpenderPath,
                userTimeToSpend.toSeconds().toString(),
                systemTimeToSpend.toSeconds().toString()
            )

        val outputDir: Path = com.google.devtools.build.lib.testutil.TestUtils.createUniqueTmpDir(fs)
        val statisticsFilePath: Path = outputDir.getRelative("stats.out")

        val fullCommandLine: MutableList<String?>? =
            processWrapper
                .commandLineBuilder(commandArguments)
                .setStatisticsPath(statisticsFilePath.asFragment())
                .build()

        ExecutionStatisticsTestUtil.executeCommandAndCheckStatisticsAboutCpuTimeSpent(
            userTimeToSpend, systemTimeToSpend, fullCommandLine, statisticsFilePath
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testProcessWrappedCommand_withStatistics_spendUserTime() {
        val userTimeToSpend: java.time.Duration = java.time.Duration.ofSeconds(10)
        val systemTimeToSpend: java.time.Duration = java.time.Duration.ZERO

        checkProcessWrapperStatistics(userTimeToSpend, systemTimeToSpend)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testProcessWrappedCommand_withStatistics_spendSystemTime() {
        val userTimeToSpend: java.time.Duration = java.time.Duration.ZERO
        val systemTimeToSpend: java.time.Duration = java.time.Duration.ofSeconds(10)

        checkProcessWrapperStatistics(userTimeToSpend, systemTimeToSpend)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testProcessWrappedCommand_withStatistics_spendUserAndSystemTime() {
        val userTimeToSpend: java.time.Duration = java.time.Duration.ofSeconds(10)
        val systemTimeToSpend: java.time.Duration = java.time.Duration.ofSeconds(10)

        checkProcessWrapperStatistics(userTimeToSpend, systemTimeToSpend)
    }

    companion object {
        private val processWrapper: ProcessWrapper
            get() {
                val path: PathFragment? =
                    PathFragment.create(BlazeTestUtils.runfilesDir())
                        .getRelative(TestConstants.PROCESS_WRAPPER_PATH)
                return ProcessWrapper(
                    path,
                    ActionInputHelper.fromPath(path),  /* killDelay= */
                    null,  /* gracefulSigterm= */
                    false
                )
            }

        private val cpuTimeSpenderPath: String
            get() = BlazeTestUtils.runfilesDir() + "/" + TestConstants.CPU_TIME_SPENDER_PATH
    }
}
