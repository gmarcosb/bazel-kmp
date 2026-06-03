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

import com.google.devtools.build.lib.sandbox.LinuxSandboxCommandLineBuilder

/** Unit tests for [Command]s that are run using the `linux-sandbox`.  */
@RunWith(JUnit4::class)
class CommandUsingLinuxSandboxTest {
    private var testFS: FileSystem? = null
    private var runfilesDir: Path? = null

    @Before
    fun createFileSystem() {
        testFS =
            UnixFileSystem(
                DigestHashFunction.SHA256,  /* hashAttributeName= */
                "",
                NativePosixFilesServiceImpl()
            )
        runfilesDir = testFS.getPath(BlazeTestUtils.runfilesDir())
    }

    private val linuxSandboxPath: Path
        get() = runfilesDir.getRelative(TestConstants.LINUX_SANDBOX_PATH)

    private val cpuTimeSpenderPath: Path
        get() = runfilesDir.getRelative(TestConstants.CPU_TIME_SPENDER_PATH)

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCommand_echo() {
        val commandArguments: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("echo", "colorless green ideas")

        val command: Command = Command(commandArguments, java.lang.System.getenv())
        val commandResult: CommandResult = command.execute()

        assertThat(commandResult.terminationStatus().success()).isTrue()
        com.google.common.truth.Subject.contains("colorless green ideas")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinuxSandboxedCommand_echo() {
        // TODO(b/62588075) Currently no linux-sandbox tool support in Windows.
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.WINDOWS)
        // TODO(b/62588075) Currently no linux-sandbox tool support in MacOS.
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.DARWIN)

        val commandArguments: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("echo", "sleep furiously")

        val fullCommandLine: MutableList<String?>? =
            LinuxSandboxCommandLineBuilder.commandLineBuilder(this.linuxSandboxPath)
                .buildForCommand(commandArguments)

        val command: Command = Command(fullCommandLine, java.lang.System.getenv())
        val commandResult: CommandResult = command.execute()

        assertThat(commandResult.terminationStatus().success()).isTrue()
        com.google.common.truth.Subject.contains("sleep furiously")
    }

    @Throws(IOException::class, CommandException::class, java.lang.InterruptedException::class)
    private fun checkLinuxSandboxStatistics(
        userTimeToSpend: java.time.Duration,
        systemTimeToSpend: java.time.Duration
    ) {
        val commandArguments: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<E?>(
                this.cpuTimeSpenderPath.getPathString(),
                userTimeToSpend.toSeconds().toString(),
                systemTimeToSpend.toSeconds().toString()
            )

        val outputDir: Path = com.google.devtools.build.lib.testutil.TestUtils.createUniqueTmpDir(testFS)
        val statisticsFilePath: Path? = outputDir.getRelative("stats.out")

        val fullCommandLine: MutableList<String?>? =
            LinuxSandboxCommandLineBuilder.commandLineBuilder(this.linuxSandboxPath)
                .setStatisticsPath(statisticsFilePath)
                .buildForCommand(commandArguments)

        ExecutionStatisticsTestUtil.executeCommandAndCheckStatisticsAboutCpuTimeSpent(
            userTimeToSpend, systemTimeToSpend, fullCommandLine, statisticsFilePath
        )
    }

    @org.junit.Test
    @Throws(CommandException::class, IOException::class, java.lang.InterruptedException::class)
    fun testLinuxSandboxedCommand_withStatistics_spendUserTime() {
        // TODO(b/62588075) Currently no linux-sandbox tool support in Windows.
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.WINDOWS)
        // TODO(b/62588075) Currently no linux-sandbox tool support in MacOS.
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.DARWIN)

        val userTimeToSpend: java.time.Duration = java.time.Duration.ofSeconds(10)
        val systemTimeToSpend: java.time.Duration = java.time.Duration.ZERO

        checkLinuxSandboxStatistics(userTimeToSpend, systemTimeToSpend)
    }

    @org.junit.Test
    @Throws(CommandException::class, IOException::class, java.lang.InterruptedException::class)
    fun testLinuxSandboxedCommand_withStatistics_spendSystemTime() {
        // TODO(b/62588075) Currently no linux-sandbox tool support in Windows.
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.WINDOWS)
        // TODO(b/62588075) Currently no linux-sandbox tool support in MacOS.
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.DARWIN)

        val userTimeToSpend: java.time.Duration = java.time.Duration.ZERO
        val systemTimeToSpend: java.time.Duration = java.time.Duration.ofSeconds(10)

        checkLinuxSandboxStatistics(userTimeToSpend, systemTimeToSpend)
    }

    @org.junit.Test
    @Throws(CommandException::class, IOException::class, java.lang.InterruptedException::class)
    fun testLinuxSandboxedCommand_withStatistics_spendUserAndSystemTime() {
        // TODO(b/62588075) Currently no linux-sandbox tool support in Windows.
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.WINDOWS)
        // TODO(b/62588075) Currently no linux-sandbox tool support in MacOS.
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.DARWIN)

        val userTimeToSpend: java.time.Duration = java.time.Duration.ofSeconds(10)
        val systemTimeToSpend: java.time.Duration = java.time.Duration.ofSeconds(10)

        checkLinuxSandboxStatistics(userTimeToSpend, systemTimeToSpend)
    }
}
