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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.actions.ActionInputHelper

/** Unit tests for [ProcessWrapper].  */
@RunWith(JUnit4::class)
class ProcessWrapperTest {
    @org.junit.Test
    fun testProcessWrapperCommandLineBuilder_buildsWithoutOptionalArguments() {
        val commandArguments: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("echo", "hello, world")

        val expectedCommandLine: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.builder<String?>().add("/some/path").addAll(commandArguments)
                .build()

        val processWrapper: ProcessWrapper =
            ProcessWrapper(
                PathFragment.create("/some/path"),
                ActionInputHelper.fromPath("/some/path"),  /* killDelay= */
                null,  /* gracefulSigterm= */
                false
            )
        val commandLine: MutableList<String?>? = processWrapper.commandLineBuilder(commandArguments).build()

        Truth.assertThat(commandLine).containsExactlyElementsIn(expectedCommandLine).inOrder()
    }

    @org.junit.Test
    fun testProcessWrapperCommandLineBuilder_buildsWithOptionalArguments() {
        val commandArguments: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("echo", "hello, world")

        val timeout: java.time.Duration = java.time.Duration.ofSeconds(10)
        val killDelay: java.time.Duration = java.time.Duration.ofSeconds(2)
        val overrideProcessWrapperPath: PathFragment = PathFragment.create("/override/process-wrapper")
        val stdoutPath: PathFragment? = PathFragment.create("/stdout.txt")
        val stderrPath: PathFragment? = PathFragment.create("/stderr.txt")
        val statisticsPath: PathFragment? = PathFragment.create("/stats.out")

        val expectedCommandLine: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
                .add(overrideProcessWrapperPath.getPathString())
                .add("--timeout=" + timeout.toSeconds())
                .add("--kill_delay=" + killDelay.toSeconds())
                .add("--stdout=" + stdoutPath)
                .add("--stderr=" + stderrPath)
                .add("--stats=" + statisticsPath)
                .add("--graceful_sigterm")
                .addAll(commandArguments)
                .build()

        val processWrapper: ProcessWrapper =
            ProcessWrapper(
                PathFragment.create("/path/process-wrapper"),
                ActionInputHelper.fromPath("/path/process-wrapper"),
                killDelay,  /* gracefulSigterm= */
                true
            )

        val commandLine: MutableList<String?>? =
            processWrapper
                .commandLineBuilder(commandArguments)
                .overrideProcessWrapperPath(overrideProcessWrapperPath)
                .setTimeout(timeout)
                .setStdoutPath(stdoutPath)
                .setStderrPath(stderrPath)
                .setStatisticsPath(statisticsPath)
                .build()

        Truth.assertThat(commandLine).containsExactlyElementsIn(expectedCommandLine).inOrder()
    }

    @org.junit.Test
    fun testProcessWrapperCommandLineBuilder_withExecutionInfo() {
        val commandArguments: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("echo", "hello, world")

        val processWrapper: ProcessWrapper =
            ProcessWrapper(
                PathFragment.create("/some/path"),
                ActionInputHelper.fromPath("/some/path"),  /* killDelay= */
                null,  /* gracefulSigterm= */
                false
            )
        val builder: ProcessWrapper.CommandLineBuilder = processWrapper.commandLineBuilder(commandArguments)

        val expectedWithoutExecutionInfo: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.builder<String?>().add("/some/path").addAll(commandArguments)
                .build()
        assertThat(builder.build()).containsExactlyElementsIn(expectedWithoutExecutionInfo).inOrder()

        val expectedWithExecutionInfo: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
                .add("/some/path")
                .add("--graceful_sigterm")
                .addAll(commandArguments)
                .build()
        builder.addExecutionInfo(
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                ExecutionRequirements.GRACEFUL_TERMINATION,
                "1"
            )
        )
        assertThat(builder.build()).containsExactlyElementsIn(expectedWithExecutionInfo).inOrder()
    }
}
