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

import com.google.devtools.build.lib.bazel.BazelStartupOptionsModule.Options

/** Tests for [CommandLineEvent]'s construction of the command lines.  */
@RunWith(JUnit4::class)
class CommandLineEventTest {
    private fun checkCommandLineSectionLabels(line: CommandLine) {
        assertThat(line.getSectionsCount()).isEqualTo(5)

        assertThat(line.getSections(0).getSectionLabel()).isEqualTo("executable")
        assertThat(line.getSections(1).getSectionLabel()).isEqualTo("startup options")
        assertThat(line.getSections(2).getSectionLabel()).isEqualTo("command")
        assertThat(line.getSections(3).getSectionLabel()).isEqualTo("command options")
        assertThat(line.getSections(4).getSectionLabel()).isEqualTo("residual")
    }

    @org.junit.Test
    fun testMostlyEmpty_originalCommandLine() {
        val fakeStartupOptions: OptionsParser =
            OptionsParser.builder().optionsClasses(BlazeServerStartupOptions::class.java).build()
        val fakeCommandOptions: OptionsParser =
            OptionsParser.builder().optionsClasses(com.google.devtools.common.options.TestOptions::class.java).build()

        val line: CommandLine =
            OriginalCommandLineEvent(
                "testblaze",
                fakeStartupOptions,
                "someCommandName",
                fakeCommandOptions.getResidue(),
                true,
                java.util.ArrayList<E?>(),
                com.google.common.collect.ImmutableSortedMap.of<K?, V?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                java.util.Optional.of<T?>(com.google.common.collect.ImmutableList.of<Any?>())
            )
                .asStreamProto(null)
                .getStructuredCommandLine()

        assertThat(line.getCommandLineLabel()).isEqualTo("original")
        checkCommandLineSectionLabels(line)
        assertThat(line.getSections(0).getChunkList().getChunk(0)).isEqualTo("testblaze")
        assertThat(line.getSections(1).getOptionList().getOptionCount()).isEqualTo(0)
        assertThat(line.getSections(2).getChunkList().getChunk(0)).isEqualTo("someCommandName")
        assertThat(line.getSections(3).getOptionList().getOptionCount()).isEqualTo(0)
        assertThat(line.getSections(4).getChunkList().getChunkCount()).isEqualTo(0)
    }

    @org.junit.Test
    fun testMostlyEmpty_canonicalCommandLine() {
        val fakeStartupOptions: OptionsParser =
            OptionsParser.builder().optionsClasses(BlazeServerStartupOptions::class.java).build()
        val fakeCommandOptions: OptionsParser =
            OptionsParser.builder().optionsClasses(com.google.devtools.common.options.TestOptions::class.java).build()

        val line: CommandLine =
            CanonicalCommandLineEvent(
                "testblaze",
                fakeStartupOptions,
                "someCommandName",
                fakeCommandOptions.getResidue(),
                false,
                com.google.common.collect.ImmutableSortedMap.of<K?, V?>(),
                com.google.common.collect.ImmutableSortedMap.of<K?, V?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                fakeCommandOptions.asListOfCanonicalOptions(),  /* replaceable= */
                false
            )
                .asStreamProto(null)
                .getStructuredCommandLine()

        assertThat(line.getCommandLineLabel()).isEqualTo("canonical")
        checkCommandLineSectionLabels(line)

        assertThat(line.getSections(0).getChunkList().getChunk(0)).isEqualTo("testblaze")
        assertThat(line.getSections(1).getOptionList().getOptionCount()).isEqualTo(1)
        assertThat(line.getSections(1).getOptionList().getOption(0).getCombinedForm())
            .isEqualTo("--ignore_all_rc_files")
        assertThat(line.getSections(2).getChunkList().getChunk(0)).isEqualTo("someCommandName")
        assertThat(line.getSections(3).getOptionList().getOptionCount()).isEqualTo(0)
        assertThat(line.getSections(4).getChunkList().getChunkCount()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testActiveBazelrcs_originalCommandLine() {
        val fakeStartupOptions: OptionsParser =
            OptionsParser.builder()
                .optionsClasses(BlazeServerStartupOptions::class.java, Options::class.java)
                .build()
        fakeStartupOptions.parse("--bazelrc=/some/path", "--bazelrc", "/some/other/path")
        val fakeCommandOptions: OptionsParser =
            OptionsParser.builder().optionsClasses(com.google.devtools.common.options.TestOptions::class.java).build()

        val line: CommandLine =
            OriginalCommandLineEvent(
                "testblaze",
                fakeStartupOptions,
                "someCommandName",
                fakeCommandOptions.getResidue(),
                false,
                fakeCommandOptions.asListOfCanonicalOptions(),
                com.google.common.collect.ImmutableSortedMap.of<K?, V?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                java.util.Optional.empty<T?>()
            )
                .asStreamProto(null)
                .getStructuredCommandLine()

        assertThat(line.getCommandLineLabel()).isEqualTo("original")
        checkCommandLineSectionLabels(line)

        // Expect the provided rc-related startup options are correctly listed
        assertThat(line.getSections(0).getChunkList().getChunk(0)).isEqualTo("testblaze")
        assertThat(line.getSections(1).getOptionList().getOptionCount()).isEqualTo(2)
        assertThat(line.getSections(1).getOptionList().getOption(0).getCombinedForm())
            .isEqualTo("--bazelrc=/some/path")
        assertThat(line.getSections(1).getOptionList().getOption(1).getCombinedForm())
            .isEqualTo("--bazelrc /some/other/path")
        assertThat(line.getSections(2).getChunkList().getChunk(0)).isEqualTo("someCommandName")
        assertThat(line.getSections(3).getOptionList().getOptionCount()).isEqualTo(0)
        assertThat(line.getSections(4).getChunkList().getChunkCount()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testPassedInBazelrcs_originalCommandLine() {
        val fakeStartupOptions: OptionsParser =
            OptionsParser.builder()
                .optionsClasses(BlazeServerStartupOptions::class.java, Options::class.java)
                .build()
        val fakeCommandOptions: OptionsParser =
            OptionsParser.builder().optionsClasses(com.google.devtools.common.options.TestOptions::class.java).build()

        val line: CommandLine =
            OriginalCommandLineEvent(
                "testblaze",
                fakeStartupOptions,
                "someCommandName",
                fakeCommandOptions.getResidue(),
                false,
                fakeCommandOptions.asListOfCanonicalOptions(),
                com.google.common.collect.ImmutableSortedMap.of<K?, V?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                java.util.Optional.of<T?>(
                    com.google.common.collect.ImmutableList.of<E?>(
                        Pair.of("", "--bazelrc=/some/path"),
                        Pair.of("", "--master_bazelrc"),
                        Pair.of("", "--bazelrc=/some/other/path"),
                        Pair.of("", "--invocation_policy=notARealPolicy")
                    )
                )
            )
                .asStreamProto(null)
                .getStructuredCommandLine()

        assertThat(line.getCommandLineLabel()).isEqualTo("original")
        checkCommandLineSectionLabels(line)

        // Expect the provided rc-related startup options are correctly listed
        assertThat(line.getSections(0).getChunkList().getChunk(0)).isEqualTo("testblaze")
        assertThat(line.getSections(1).getOptionList().getOptionCount()).isEqualTo(4)
        assertThat(line.getSections(1).getOptionList().getOption(0).getCombinedForm())
            .isEqualTo("--bazelrc=/some/path")
        assertThat(line.getSections(1).getOptionList().getOption(1).getCombinedForm())
            .isEqualTo("--master_bazelrc")
        assertThat(line.getSections(1).getOptionList().getOption(2).getCombinedForm())
            .isEqualTo("--bazelrc=/some/other/path")
        assertThat(line.getSections(1).getOptionList().getOption(3).getCombinedForm())
            .isEqualTo("--invocation_policy=notARealPolicy")
        assertThat(line.getSections(2).getChunkList().getChunk(0)).isEqualTo("someCommandName")
        assertThat(line.getSections(3).getOptionList().getOptionCount()).isEqualTo(0)
        assertThat(line.getSections(4).getChunkList().getChunkCount()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testBazelrcs_canonicalCommandLine() {
        val fakeStartupOptions: OptionsParser =
            OptionsParser.builder()
                .optionsClasses(BlazeServerStartupOptions::class.java, Options::class.java)
                .build()
        fakeStartupOptions.parse("--bazelrc=/some/path", "--bazelrc", "/some/other/path")
        val fakeCommandOptions: OptionsParser =
            OptionsParser.builder().optionsClasses(com.google.devtools.common.options.TestOptions::class.java).build()

        val line: CommandLine =
            CanonicalCommandLineEvent(
                "testblaze",
                fakeStartupOptions,
                "someCommandName",
                fakeCommandOptions.getResidue(),
                false,
                com.google.common.collect.ImmutableSortedMap.of<K?, V?>(),
                com.google.common.collect.ImmutableSortedMap.of<K?, V?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                fakeCommandOptions.asListOfCanonicalOptions(),  /* replaceable= */
                false
            )
                .asStreamProto(null)
                .getStructuredCommandLine()

        assertThat(line.getCommandLineLabel()).isEqualTo("canonical")
        checkCommandLineSectionLabels(line)

        // Expect the provided rc-related startup options are removed and replaced with the
        // rc-prevention options.
        assertThat(line.getSections(0).getChunkList().getChunk(0)).isEqualTo("testblaze")
        assertThat(line.getSections(1).getOptionList().getOptionCount()).isEqualTo(1)
        assertThat(line.getSections(1).getOptionList().getOption(0).getCombinedForm())
            .isEqualTo("--ignore_all_rc_files")
        assertThat(line.getSections(2).getChunkList().getChunk(0)).isEqualTo("someCommandName")
        assertThat(line.getSections(3).getOptionList().getOptionCount()).isEqualTo(0)
        assertThat(line.getSections(4).getChunkList().getChunkCount()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testOptionsAtVariousPriorities_originalCommandLine() {
        val fakeStartupOptions: OptionsParser =
            OptionsParser.builder().optionsClasses(BlazeServerStartupOptions::class.java).build()
        val fakeCommandOptions: OptionsParser =
            OptionsParser.builder().optionsClasses(com.google.devtools.common.options.TestOptions::class.java).build()
        fakeCommandOptions.parse(
            PriorityCategory.COMMAND_LINE,
            "command line",
            com.google.common.collect.ImmutableList.of<String?>("--test_string=foo", "--test_multiple_string=bar")
        )
        fakeCommandOptions.parse(
            PriorityCategory.INVOCATION_POLICY,
            "fake invocation policy",
            com.google.common.collect.ImmutableList.of<String?>("--expanded_c=2")
        )
        fakeCommandOptions.parse(
            PriorityCategory.RC_FILE,
            "fake rc file",
            com.google.common.collect.ImmutableList.of<String?>("--test_multiple_string=baz")
        )

        val line: CommandLine =
            OriginalCommandLineEvent(
                "testblaze",
                fakeStartupOptions,
                "someCommandName",
                fakeCommandOptions.getResidue(),
                false,
                fakeCommandOptions.asListOfCanonicalOptions(),
                com.google.common.collect.ImmutableSortedMap.of<K?, V?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                java.util.Optional.of<T?>(com.google.common.collect.ImmutableList.of<Any?>())
            )
                .asStreamProto(null)
                .getStructuredCommandLine()

        assertThat(line.getCommandLineLabel()).isEqualTo("original")
        checkCommandLineSectionLabels(line)

        assertThat(line.getSections(0).getChunkList().getChunk(0)).isEqualTo("testblaze")
        assertThat(line.getSections(1).getOptionList().getOptionCount()).isEqualTo(0)
        assertThat(line.getSections(2).getChunkList().getChunk(0)).isEqualTo("someCommandName")
        // Expect the rc file options and invocation policy options to not be listed with the explicit
        // command line options.
        assertThat(line.getSections(3).getOptionList().getOptionCount()).isEqualTo(2)
        assertThat(line.getSections(3).getOptionList().getOption(0).getCombinedForm())
            .isEqualTo("--test_string=foo")
        assertThat(line.getSections(3).getOptionList().getOption(1).getCombinedForm())
            .isEqualTo("--test_multiple_string=bar")
        assertThat(line.getSections(4).getChunkList().getChunkCount()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testOptionsAtVariousPriorities_canonicalCommandLine() {
        val fakeStartupOptions: OptionsParser =
            OptionsParser.builder().optionsClasses(BlazeServerStartupOptions::class.java).build()
        val fakeCommandOptions: OptionsParser =
            OptionsParser.builder().optionsClasses(com.google.devtools.common.options.TestOptions::class.java).build()
        fakeCommandOptions.parse(
            PriorityCategory.COMMAND_LINE,
            "command line",
            com.google.common.collect.ImmutableList.of<String?>("--test_string=foo", "--test_multiple_string=bar")
        )
        fakeCommandOptions.parse(
            PriorityCategory.INVOCATION_POLICY,
            "fake invocation policy",
            com.google.common.collect.ImmutableList.of<String?>("--expanded_c=2")
        )
        fakeCommandOptions.parse(
            PriorityCategory.RC_FILE,
            "fake rc file",
            com.google.common.collect.ImmutableList.of<String?>("--test_multiple_string=baz")
        )

        val line: CommandLine =
            CanonicalCommandLineEvent(
                "testblaze",
                fakeStartupOptions,
                "someCommandName",
                com.google.common.collect.ImmutableList.of<E?>(),
                false,
                com.google.common.collect.ImmutableSortedMap.of<K?, V?>(),
                com.google.common.collect.ImmutableSortedMap.of<K?, V?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                fakeCommandOptions.asListOfCanonicalOptions(),  /* replaceable= */
                false
            )
                .asStreamProto(null)
                .getStructuredCommandLine()

        assertThat(line.getCommandLineLabel()).isEqualTo("canonical")
        checkCommandLineSectionLabels(line)

        assertThat(line.getSections(0).getChunkList().getChunk(0)).isEqualTo("testblaze")
        assertThat(line.getSections(1).getOptionList().getOptionCount()).isEqualTo(1)
        assertThat(line.getSections(2).getChunkList().getChunk(0)).isEqualTo("someCommandName")
        // In the canonical line, expect the options in priority order.
        assertThat(line.getSections(3).getOptionList().getOptionCount()).isEqualTo(4)
        assertThat(line.getSections(3).getOptionList().getOption(0).getCombinedForm())
            .isEqualTo("--test_multiple_string=baz")
        assertThat(line.getSections(3).getOptionList().getOption(1).getCombinedForm())
            .isEqualTo("--test_string=foo")
        assertThat(line.getSections(3).getOptionList().getOption(2).getCombinedForm())
            .isEqualTo("--test_multiple_string=bar")
        assertThat(line.getSections(3).getOptionList().getOption(3).getCombinedForm())
            .isEqualTo("--expanded_c=2")
        assertThat(line.getSections(4).getChunkList().getChunkCount()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testExpansionOption_originalCommandLine() {
        val fakeStartupOptions: OptionsParser =
            OptionsParser.builder().optionsClasses(BlazeServerStartupOptions::class.java).build()
        val fakeCommandOptions: OptionsParser =
            OptionsParser.builder().optionsClasses(com.google.devtools.common.options.TestOptions::class.java).build()
        fakeCommandOptions.parse(
            PriorityCategory.COMMAND_LINE,
            "command line",
            com.google.common.collect.ImmutableList.of<String?>("--test_expansion")
        )

        val line: CommandLine =
            OriginalCommandLineEvent(
                "testblaze",
                fakeStartupOptions,
                "someCommandName",
                fakeCommandOptions.getResidue(),
                false,
                fakeCommandOptions.asListOfExplicitOptions(),
                fakeCommandOptions.getExplicitCommandLineStarlarkOptions(),
                fakeCommandOptions.getStarlarkOptionsAllowingMultiple(),
                java.util.Optional.empty<T?>()
            )
                .asStreamProto(null)
                .getStructuredCommandLine()

        assertThat(line.getCommandLineLabel()).isEqualTo("original")
        checkCommandLineSectionLabels(line)

        assertThat(line.getSections(0).getChunkList().getChunk(0)).isEqualTo("testblaze")
        assertThat(line.getSections(1).getOptionList().getOptionCount()).isEqualTo(0)
        assertThat(line.getSections(2).getChunkList().getChunk(0)).isEqualTo("someCommandName")
        // Expect the rc file option to not be listed with the explicit command line options.
        assertThat(line.getSections(3).getOptionList().getOptionCount()).isEqualTo(1)
        assertThat(line.getSections(3).getOptionList().getOption(0).getCombinedForm())
            .isEqualTo("--test_expansion")
        assertThat(line.getSections(4).getChunkList().getChunkCount()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testExpansionOption_canonicalCommandLine() {
        val fakeStartupOptions: OptionsParser =
            OptionsParser.builder().optionsClasses(BlazeServerStartupOptions::class.java).build()
        val fakeCommandOptions: OptionsParser =
            OptionsParser.builder().optionsClasses(com.google.devtools.common.options.TestOptions::class.java).build()
        fakeCommandOptions.parse(
            PriorityCategory.COMMAND_LINE,
            "command line",
            com.google.common.collect.ImmutableList.of<String?>("--test_expansion")
        )

        val line: CommandLine =
            CanonicalCommandLineEvent(
                "testblaze",
                fakeStartupOptions,
                "someCommandName",
                com.google.common.collect.ImmutableList.of<E?>(),
                false,
                com.google.common.collect.ImmutableSortedMap.of<K?, V?>(),
                com.google.common.collect.ImmutableSortedMap.of<K?, V?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                fakeCommandOptions.asListOfCanonicalOptions(),  /* replaceable= */
                false
            )
                .asStreamProto(null)
                .getStructuredCommandLine()

        assertThat(line.getCommandLineLabel()).isEqualTo("canonical")
        checkCommandLineSectionLabels(line)

        assertThat(line.getSections(0).getChunkList().getChunk(0)).isEqualTo("testblaze")
        assertThat(line.getSections(1).getOptionList().getOptionCount()).isEqualTo(1)
        assertThat(line.getSections(2).getChunkList().getChunk(0)).isEqualTo("someCommandName")

        assertThat(line.getSections(3).getOptionList().getOptionCount()).isEqualTo(4)
        assertThat(line.getSections(3).getOptionList().getOption(0).getCombinedForm())
            .isEqualTo("--noexpanded_a")
        assertThat(line.getSections(3).getOptionList().getOption(1).getCombinedForm())
            .isEqualTo("--expanded_b=false")
        assertThat(line.getSections(3).getOptionList().getOption(2).getCombinedForm())
            .isEqualTo("--expanded_c 42")
        assertThat(line.getSections(3).getOptionList().getOption(3).getCombinedForm())
            .isEqualTo("--expanded_d bar")
        assertThat(line.getSections(4).getChunkList().getChunkCount()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testOptionWithImplicitRequirement_originalCommandLine() {
        val fakeStartupOptions: OptionsParser =
            OptionsParser.builder().optionsClasses(BlazeServerStartupOptions::class.java).build()
        val fakeCommandOptions: OptionsParser =
            OptionsParser.builder().optionsClasses(com.google.devtools.common.options.TestOptions::class.java).build()
        fakeCommandOptions.parse(
            PriorityCategory.COMMAND_LINE,
            "command line",
            com.google.common.collect.ImmutableList.of<String?>("--test_implicit_requirement=foo")
        )

        val line: CommandLine =
            OriginalCommandLineEvent(
                "testblaze",
                fakeStartupOptions,
                "someCommandName",
                com.google.common.collect.ImmutableList.of<E?>(),
                false,
                fakeCommandOptions.asListOfCanonicalOptions(),
                com.google.common.collect.ImmutableSortedMap.of<K?, V?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                java.util.Optional.of<T?>(com.google.common.collect.ImmutableList.of<Any?>())
            )
                .asStreamProto(null)
                .getStructuredCommandLine()

        assertThat(line.getCommandLineLabel()).isEqualTo("original")
        checkCommandLineSectionLabels(line)

        assertThat(line.getSections(0).getChunkList().getChunk(0)).isEqualTo("testblaze")
        assertThat(line.getSections(1).getOptionList().getOptionCount()).isEqualTo(0)
        assertThat(line.getSections(2).getChunkList().getChunk(0)).isEqualTo("someCommandName")
        assertThat(line.getSections(3).getOptionList().getOptionCount()).isEqualTo(1)
        assertThat(line.getSections(3).getOptionList().getOption(0).getCombinedForm())
            .isEqualTo("--test_implicit_requirement=foo")
        assertThat(line.getSections(4).getChunkList().getChunkCount()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testOptionWithImplicitRequirement_canonicalCommandLine() {
        val fakeStartupOptions: OptionsParser =
            OptionsParser.builder().optionsClasses(BlazeServerStartupOptions::class.java).build()
        val fakeCommandOptions: OptionsParser =
            OptionsParser.builder().optionsClasses(com.google.devtools.common.options.TestOptions::class.java).build()
        fakeCommandOptions.parse(
            PriorityCategory.COMMAND_LINE,
            "command line",
            com.google.common.collect.ImmutableList.of<String?>("--test_implicit_requirement=foo")
        )

        val line: CommandLine =
            CanonicalCommandLineEvent(
                "testblaze",
                fakeStartupOptions,
                "someCommandName",
                com.google.common.collect.ImmutableList.of<E?>(),
                false,
                com.google.common.collect.ImmutableSortedMap.of<K?, V?>(),
                com.google.common.collect.ImmutableSortedMap.of<K?, V?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                fakeCommandOptions.asListOfCanonicalOptions(),  /* replaceable= */
                false
            )
                .asStreamProto(null)
                .getStructuredCommandLine()

        assertThat(line.getCommandLineLabel()).isEqualTo("canonical")
        checkCommandLineSectionLabels(line)

        // Unlike expansion flags, implicit requirements are not listed separately.
        assertThat(line.getSections(0).getChunkList().getChunk(0)).isEqualTo("testblaze")
        assertThat(line.getSections(1).getOptionList().getOptionCount()).isEqualTo(1)
        assertThat(line.getSections(2).getChunkList().getChunk(0)).isEqualTo("someCommandName")
        assertThat(line.getSections(3).getOptionList().getOptionCount()).isEqualTo(1)
        assertThat(line.getSections(3).getOptionList().getOption(0).getCombinedForm())
            .isEqualTo("--test_implicit_requirement=foo")
        assertThat(line.getSections(4).getChunkList().getChunkCount()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testDefaultToolCommandLine() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(CommonCommandOptions::class.java).build()
        val event: ToolCommandLineEvent = parser.getOptions<O?>(CommonCommandOptions::class.java).getToolCommandLine()
        // Test that the actual default value is an empty command line.
        assertThat(event.asStreamProto(null).getStructuredCommandLine())
            .isEqualTo(CommandLine.getDefaultInstance())
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testLabelessParsingOfCompiledToolCommandLine() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(CommonCommandOptions::class.java).build()
        val original: CommandLine =
            CommandLine.newBuilder().addSections(CommandLineSection.getDefaultInstance()).build()
        parser.parse(
            "--experimental_tool_command_line=" + com.google.common.io.BaseEncoding.base64()
                .encode(original.toByteArray())
        )

        val event: ToolCommandLineEvent = parser.getOptions<O?>(CommonCommandOptions::class.java).getToolCommandLine()
        val id: StructuredCommandLineId = event.getEventId().getStructuredCommandLine()
        val line: CommandLine = event.asStreamProto(null).getStructuredCommandLine()

        assertThat(id.getCommandLineLabel()).isEqualTo("tool")
        assertThat(line.getSectionsCount()).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testParsingOfCompiledToolCommandLine() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(CommonCommandOptions::class.java).build()
        val original: CommandLine =
            CommandLine.newBuilder()
                .setCommandLineLabel("something meaningful")
                .addSections(
                    CommandLineSection.newBuilder()
                        .setSectionLabel("command")
                        .setChunkList(ChunkList.newBuilder().addChunk("aCommand"))
                )
                .addSections(
                    CommandLineSection.newBuilder()
                        .setSectionLabel("someArguments")
                        .setChunkList(ChunkList.newBuilder().addChunk("arg1").addChunk("arg2"))
                )
                .addSections(
                    CommandLineSection.newBuilder()
                        .setSectionLabel("someOptions")
                        .setOptionList(OptionList.getDefaultInstance())
                )
                .build()
        parser.parse(
            "--experimental_tool_command_line=" + com.google.common.io.BaseEncoding.base64()
                .encode(original.toByteArray())
        )

        val event: ToolCommandLineEvent = parser.getOptions<O?>(CommonCommandOptions::class.java).getToolCommandLine()
        val id: StructuredCommandLineId = event.getEventId().getStructuredCommandLine()
        val line: CommandLine = event.asStreamProto(null).getStructuredCommandLine()

        assertThat(id.getCommandLineLabel()).isEqualTo("tool")
        assertThat(line.getCommandLineLabel()).isEqualTo("something meaningful")
        assertThat(line.getSectionsCount()).isEqualTo(3)
        assertThat(line.getSections(0).getSectionTypeCase()).isEqualTo(SectionTypeCase.CHUNK_LIST)
        assertThat(line.getSections(0).getChunkList().getChunkCount()).isEqualTo(1)
        assertThat(line.getSections(0).getChunkList().getChunk(0)).isEqualTo("aCommand")
        assertThat(line.getSections(1).getSectionTypeCase()).isEqualTo(SectionTypeCase.CHUNK_LIST)
        assertThat(line.getSections(1).getChunkList().getChunkCount()).isEqualTo(2)
        assertThat(line.getSections(1).getChunkList().getChunk(0)).isEqualTo("arg1")
        assertThat(line.getSections(1).getChunkList().getChunk(1)).isEqualTo("arg2")
        assertThat(line.getSections(2).getSectionTypeCase()).isEqualTo(SectionTypeCase.OPTION_LIST)
        assertThat(line.getSections(2).getOptionList().getOptionCount()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testSimpleStringToolCommandLine() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(CommonCommandOptions::class.java).build()
        parser.parse("--experimental_tool_command_line=The quick brown fox jumps over the lazy dog")

        val event: ToolCommandLineEvent = parser.getOptions<O?>(CommonCommandOptions::class.java).getToolCommandLine()
        val id: StructuredCommandLineId = event.getEventId().getStructuredCommandLine()
        val line: CommandLine = event.asStreamProto(null).getStructuredCommandLine()

        assertThat(id.getCommandLineLabel()).isEqualTo("tool")
        assertThat(line.getCommandLineLabel()).isEqualTo("tool")
        assertThat(line.getSectionsCount()).isEqualTo(1)
        assertThat(line.getSections(0).getSectionTypeCase()).isEqualTo(SectionTypeCase.CHUNK_LIST)
        assertThat(line.getSections(0).getChunkList().getChunk(0))
            .isEqualTo("The quick brown fox jumps over the lazy dog")
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun redactedResidual_includesTarget_originalCommandLine() {
        val fakeStartupOptions: OptionsParser =
            OptionsParser.builder().optionsClasses(BlazeServerStartupOptions::class.java).build()
        val fakeCommandOptions: OptionsParser =
            OptionsParser.builder().optionsClasses(BuildEventProtocolOptions::class.java).build()
        fakeCommandOptions.parse("--experimental_run_bep_event_include_residue=false")
        fakeCommandOptions.setResidue(
            com.google.common.collect.ImmutableList.of<String?>("//some:target", "--sensitive_arg"),
            com.google.common.collect.ImmutableList.of<String?>()
        )

        val line: CommandLine =
            OriginalCommandLineEvent(
                "testblaze",
                fakeStartupOptions,
                "run",
                fakeCommandOptions.getResidue(),
                false,
                fakeCommandOptions.asListOfCanonicalOptions(),
                com.google.common.collect.ImmutableSortedMap.of<K?, V?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                java.util.Optional.of<T?>(com.google.common.collect.ImmutableList.of<Any?>())
            )
                .asStreamProto(null)
                .getStructuredCommandLine()

        assertThat(line.getCommandLineLabel()).isEqualTo("original")
        checkCommandLineSectionLabels(line)
        assertThat(line.getSections(0).getChunkList().getChunk(0)).isEqualTo("testblaze")
        assertThat(line.getSections(1).getOptionList().getOptionCount()).isEqualTo(0)
        assertThat(line.getSections(2).getChunkList().getChunk(0)).isEqualTo("run")
        assertThat(line.getSections(3).getOptionList().getOptionCount()).isEqualTo(1)
        assertThat(line.getSections(4).getChunkList().getChunkCount()).isEqualTo(2)
        assertThat(line.getSections(4).getChunkList().getChunk(0)).isEqualTo("//some:target")
        assertThat(line.getSections(4).getChunkList().getChunk(1)).isEqualTo("REDACTED")
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun redactedResidual_includesTarget_canonicalCommandLine() {
        val fakeStartupOptions: OptionsParser =
            OptionsParser.builder().optionsClasses(BlazeServerStartupOptions::class.java).build()
        val fakeCommandOptions: OptionsParser =
            OptionsParser.builder().optionsClasses(BuildEventProtocolOptions::class.java).build()
        fakeCommandOptions.parse("--experimental_run_bep_event_include_residue=false")
        fakeCommandOptions.setResidue(
            com.google.common.collect.ImmutableList.of<String?>("//some:target", "--sensitive_arg"),
            com.google.common.collect.ImmutableList.of<String?>()
        )

        val line: CommandLine =
            CanonicalCommandLineEvent(
                "testblaze",
                fakeStartupOptions,
                "run",
                fakeCommandOptions.getResidue(),
                false,
                com.google.common.collect.ImmutableSortedMap.of<K?, V?>(),
                com.google.common.collect.ImmutableSortedMap.of<K?, V?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                fakeCommandOptions.asListOfCanonicalOptions(),  /* replaceable= */
                false
            )
                .asStreamProto(null)
                .getStructuredCommandLine()

        assertThat(line.getCommandLineLabel()).isEqualTo("canonical")
        checkCommandLineSectionLabels(line)
        assertThat(line.getSections(0).getChunkList().getChunk(0)).isEqualTo("testblaze")
        assertThat(line.getSections(1).getOptionList().getOptionCount()).isEqualTo(1)
        assertThat(line.getSections(1).getOptionList().getOption(0).getCombinedForm())
            .isEqualTo("--ignore_all_rc_files")
        assertThat(line.getSections(2).getChunkList().getChunk(0)).isEqualTo("run")
        assertThat(line.getSections(3).getOptionList().getOptionCount()).isEqualTo(1)
        assertThat(line.getSections(4).getChunkList().getChunkCount()).isEqualTo(2)
        assertThat(line.getSections(4).getChunkList().getChunk(0)).isEqualTo("//some:target")
        assertThat(line.getSections(4).getChunkList().getChunk(1)).isEqualTo("REDACTED")
    }
}
