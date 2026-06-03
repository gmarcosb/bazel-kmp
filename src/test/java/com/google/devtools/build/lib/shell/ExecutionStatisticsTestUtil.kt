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

import com.google.devtools.build.lib.vfs.Path

/**
 * Utilities to assist with testing execution statistics generated via the `process-wrapper`
 * and `linux-sandbox` tools.
 */
object ExecutionStatisticsTestUtil {
    /**
     * Executes a command and checks that the execution statistics timing info for that command
     * satisfy certain constraints.
     * 
     * @param userTimeToSpend a lower bound for how much CPU user execution time was expected
     * @param systemTimeToSpend a lower bound for how much CPU system execution time was expected
     * @param fullCommandLine the command to execute, including any wrappers used (like linux-sandbox)
     * @param statisticsFilePath where the execution statistics file will be generated (to be read)
     */
    @Throws(CommandException::class, IOException::class, java.lang.InterruptedException::class)
    fun executeCommandAndCheckStatisticsAboutCpuTimeSpent(
        userTimeToSpend: java.time.Duration,
        systemTimeToSpend: java.time.Duration?,
        fullCommandLine: MutableList<String?>?,
        statisticsFilePath: Path?
    ) {
        val userTimeLowerBound: java.time.Duration? = userTimeToSpend
        val userTimeUpperBound: java.time.Duration? = userTimeToSpend.plusSeconds(9)
        val systemTimeLowerBound: java.time.Duration? = systemTimeToSpend

        // TODO(b/110456205) This check fails under very heavy load, investigate why and re-enable it
        // Duration systemTimeUpperBound = systemTimeToSpend.plusSeconds(9);
        val command: Command = Command(fullCommandLine, java.lang.System.getenv())
        val commandResult: CommandResult = command.execute()
        assertThat(commandResult.terminationStatus().success()).isTrue()

        val resourceUsage: java.util.Optional<ExecutionStatistics.ResourceUsage?>? =
            ExecutionStatistics.getResourceUsage(statisticsFilePath)
        Truth.assertThat(resourceUsage).isPresent()

        val userTime: java.time.Duration? = resourceUsage.get().getUserExecutionTime()
        Truth.assertThat<java.time.Duration?>(userTime).isAtLeast(userTimeLowerBound)
        Truth.assertThat<java.time.Duration?>(userTime).isAtMost(userTimeUpperBound)

        val systemTime: java.time.Duration? = resourceUsage.get().getSystemExecutionTime()
        Truth.assertThat<java.time.Duration?>(systemTime).isAtLeast(systemTimeLowerBound)

        // TODO(b/110456205) This check fails under very heavy load, investigate why and re-enable it
        // assertThat(systemTime).isAtMost(systemTimeUpperBound);
    }
}
