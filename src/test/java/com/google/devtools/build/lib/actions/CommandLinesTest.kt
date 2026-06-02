// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.actions.CommandLines.ExpandedCommandLines

/** Tests for [CommandLines].  */
@RunWith(TestParameterInjector::class)
class CommandLinesTest {
    private val inputMetadataProvider: InputMetadataProvider? = null
    private val execPath: PathFragment? = PathFragment.create("output.txt")

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun expand_simpleCommandLine_returnsCorrectCommandLine() {
        val commandLines: CommandLines =
            CommandLines.builder()
                .addCommandLine(CommandLine.of(com.google.common.collect.ImmutableList.of<E?>("--foo", "--bar")))
                .build()

        val expanded: ExpandedCommandLines =
            commandLines.expand(inputMetadataProvider, execPath, NO_LIMIT, PathMapper.NOOP, 0)

        assertThat(commandLines.allArguments()).containsExactly("--foo", "--bar").inOrder()
        assertThat(expanded.arguments()).containsExactly("--foo", "--bar").inOrder()
        assertThat(expanded.getParamFiles()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun expand_commandLineFromArguments_returnsCorrectCommandLine() {
        val commandLines: CommandLines =
            CommandLines.of(com.google.common.collect.ImmutableList.of<E?>("--foo", "--bar"))

        val expanded: ExpandedCommandLines =
            commandLines.expand(inputMetadataProvider, execPath, NO_LIMIT, PathMapper.NOOP, 0)

        assertThat(commandLines.allArguments()).containsExactly("--foo", "--bar").inOrder()
        assertThat(expanded.arguments()).containsExactly("--foo", "--bar").inOrder()
        assertThat(expanded.getParamFiles()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun expand_concatCommandLines_returnsConcatenatedArguments() {
        val commandLines: CommandLines =
            CommandLines.concat(
                CommandLine.of(com.google.common.collect.ImmutableList.of<E?>("--before")),
                CommandLines.of(com.google.common.collect.ImmutableList.of<E?>("--foo", "--bar"))
            )

        val expanded: ExpandedCommandLines =
            commandLines.expand(inputMetadataProvider, execPath, NO_LIMIT, PathMapper.NOOP, 0)

        assertThat(commandLines.allArguments()).containsExactly("--before", "--foo", "--bar")
        assertThat(expanded.arguments()).containsExactly("--before", "--foo", "--bar")
        assertThat(expanded.getParamFiles()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun expand_paramFileUseAlways_returnsCommandLineWithParamFile() {
        val commandLines: CommandLines =
            CommandLines.builder()
                .addCommandLine(
                    CommandLine.of(com.google.common.collect.ImmutableList.of<E?>("--foo", "--bar")),
                    ParamFileInfo.builder(ParameterFileType.UNQUOTED).setUseAlways(true).build()
                )
                .build()

        val expanded: ExpandedCommandLines =
            commandLines.expand(inputMetadataProvider, execPath, NO_LIMIT, PathMapper.NOOP, 0)

        assertThat(commandLines.allArguments()).containsExactly("--foo", "--bar").inOrder()
        assertThat(expanded.arguments()).containsExactly("@output.txt-0.params")
        assertThat(expanded.getParamFiles()).hasSize(1)
        assertThat(expanded.getParamFiles().get(0).getArguments())
            .containsExactly("--foo", "--bar")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun expand_paramFileCommandWithinLimits_returnsNoParamFile() {
        val commandLines: CommandLines =
            CommandLines.builder()
                .addCommandLine(
                    CommandLine.of(com.google.common.collect.ImmutableList.of<E?>("--foo", "--bar")),
                    ParamFileInfo.builder(ParameterFileType.UNQUOTED).setUseAlways(false).build()
                )
                .build()

        // Set max length to longer than command line, no param file needed
        val expanded: ExpandedCommandLines =
            commandLines.expand(inputMetadataProvider, execPath, NO_LIMIT, PathMapper.NOOP, 0)

        assertThat(expanded.arguments()).containsExactly("--foo", "--bar").inOrder()
        assertThat(expanded.getParamFiles()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun expand_paramFileCommandOverLimits_returnsParamFile() {
        val commandLines: CommandLines =
            CommandLines.builder()
                .addCommandLine(
                    CommandLine.of(com.google.common.collect.ImmutableList.of<E?>("--foo", "--bar")),
                    ParamFileInfo.builder(ParameterFileType.UNQUOTED).setUseAlways(false).build()
                )
                .build()

        // Set max length to 0, spill to param file is forced
        val expanded: ExpandedCommandLines =
            commandLines.expand(
                inputMetadataProvider, execPath, CommandLineLimits(0), PathMapper.NOOP, 0
            )

        assertThat(expanded.arguments()).containsExactly("@output.txt-0.params")
        assertThat(expanded.getParamFiles()).hasSize(1)
        assertThat(expanded.getParamFiles().get(0).getArguments())
            .containsExactly("--foo", "--bar")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun expand_mixOfCommandLinesAndParamFiles_returnsCorrectCommandLines() {
        val commandLines: CommandLines =
            CommandLines.builder()
                .addCommandLine(CommandLine.of(com.google.common.collect.ImmutableList.of<E?>("a", "b")))
                .addCommandLine(
                    CommandLine.of(com.google.common.collect.ImmutableList.of<E?>("c", "d")),
                    ParamFileInfo.builder(ParameterFileType.UNQUOTED).setUseAlways(true).build()
                )
                .addCommandLine(CommandLine.of(com.google.common.collect.ImmutableList.of<E?>("e", "f")))
                .addCommandLine(
                    CommandLine.of(com.google.common.collect.ImmutableList.of<E?>("g", "h")),
                    ParamFileInfo.builder(ParameterFileType.UNQUOTED).setUseAlways(true).build()
                )
                .build()

        val expanded: ExpandedCommandLines =
            commandLines.expand(inputMetadataProvider, execPath, NO_LIMIT, PathMapper.NOOP, 0)

        assertThat(commandLines.allArguments()).containsExactly("a", "b", "c", "d", "e", "f", "g", "h")
        assertThat(expanded.arguments())
            .containsExactly("a", "b", "@output.txt-0.params", "e", "f", "@output.txt-1.params")
        assertThat(expanded.getParamFiles()).hasSize(2)
        assertThat(expanded.getParamFiles().get(0).getArguments()).containsExactly("c", "d").inOrder()
        assertThat(expanded.getParamFiles().get(0).getExecPathString())
            .isEqualTo("output.txt-0.params")
        assertThat(expanded.getParamFiles().get(1).getArguments()).containsExactly("g", "h").inOrder()
        assertThat(expanded.getParamFiles().get(1).getExecPathString())
            .isEqualTo("output.txt-1.params")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun expand_commandsWithParamFilesSecondExceedsLimits_returnsParamFileForSecondOnly() {
        val commandLines: CommandLines =
            CommandLines.builder()
                .addCommandLine(
                    CommandLine.of(com.google.common.collect.ImmutableList.of<E?>("a", "b")),
                    ParamFileInfo.builder(ParameterFileType.UNQUOTED).setUseAlways(false).build()
                )
                .addCommandLine(
                    CommandLine.of(com.google.common.collect.ImmutableList.of<E?>("c", "d")),
                    ParamFileInfo.builder(ParameterFileType.UNQUOTED).setUseAlways(false).build()
                )
                .build()

        val expanded: ExpandedCommandLines =
            commandLines.expand(
                inputMetadataProvider, execPath, CommandLineLimits(4), PathMapper.NOOP, 0
            )

        assertThat(commandLines.allArguments()).containsExactly("a", "b", "c", "d").inOrder()
        assertThat(expanded.arguments()).containsExactly("a", "b", "@output.txt-0.params").inOrder()
        assertThat(expanded.getParamFiles()).hasSize(1)
        assertThat(expanded.getParamFiles().get(0).getArguments()).containsExactly("c", "d").inOrder()
    }

    /** Filtering of flag and positional arguments with flagsOnly.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun expand_flagsOnly_movesOnlyDashDashPrefixedFlagsToParamFile() {
        val commandLines: CommandLines =
            CommandLines.builder()
                .addCommandLine(
                    CommandLine.of(com.google.common.collect.ImmutableList.of<E?>("--a", "1", "--b=c", "-2")),
                    ParamFileInfo.builder(ParameterFileType.UNQUOTED)
                        .setUseAlways(true)
                        .setFlagsOnly(true)
                        .build()
                )
                .build()

        val expanded: ExpandedCommandLines =
            commandLines.expand(
                inputMetadataProvider, execPath, CommandLineLimits(4), PathMapper.NOOP, 0
            )
        assertThat(commandLines.allArguments()).containsExactly("--a", "1", "--b=c", "-2")
        assertThat(expanded.arguments()).containsExactly("1", "-2", "@output.txt-0.params")
        assertThat(expanded.getParamFiles()).hasSize(1)
        assertThat(expanded.getParamFiles().get(0).getArguments())
            .containsExactly("--a", "--b=c")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun expand_onlyExecutableArgProcessedForPathMapping(
        @TestParameter("0", "1", "2", "3") numNonExecutableArgs: Int,
        @TestParameter normalizedExecutablePath: Boolean,
        @TestParameter mappableNonExecutablePath: Boolean
    ) {
        val builder: CommandLines.Builder = CommandLines.builder()
        val executableArg =
            if (normalizedExecutablePath)
                "bazel-out/k8-fastbuild/bin/my_binary"
            else
                "bazel-out/some/path/../my_binary"
        val nonExecutableArg =
            if (mappableNonExecutablePath) "bazel-out/k8-fastbuild/bin/unrelated" else "hello/../world"
        builder.addSingleArgument(executableArg)
        for (i in 0..<numNonExecutableArgs) {
            builder.addSingleArgument(nonExecutableArg)
        }
        val commandLines: CommandLines = builder.build()
        val pathMapper: PathMapper =
            PathMapper { execPath ->
                if (execPath.startsWith(PathFragment.create("bazel-out")))
                    PathFragment.create("mapped").getRelative(execPath)
                else
                    execPath
            }

        val expectedExecutableArg =
            if (normalizedExecutablePath) "mapped/bazel-out/k8-fastbuild/bin/my_binary" else executableArg
        val expectedArgs: Iterable<String?> =
            com.google.common.collect.Iterables.concat<String?>(
                com.google.common.collect.ImmutableList.of<String?>(expectedExecutableArg),
                Collections.nCopies<String?>(numNonExecutableArgs, nonExecutableArg)
            )
        assertThat(commandLines.allArguments(pathMapper))
            .containsExactlyElementsIn(expectedArgs)
            .inOrder()
    }

    companion object {
        private val NO_LIMIT: CommandLineLimits = CommandLineLimits(10000)
    }
}
