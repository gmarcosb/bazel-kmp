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

import com.google.devtools.build.lib.util.DetailedExitCode

/**
 * Tests [BlazeOptionHandler].
 * 
 * 
 * Avoids testing anything that is controlled by the [BlazeCommandDispatcher], for
 * isolation. As a part of this, this test intentionally avoids testing how errors and informational
 * messages are logged, to minimize dependence on the ui, and only checks for the existence of these
 * messages.
 */
@RunWith(JUnit4::class)
class BlazeOptionHandlerTest {
    private var eventHandler: StoredEventHandler? = null
    private var parser: OptionsParser? = null
    private var optionHandler: BlazeOptionHandler? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        val optionsClasses: com.google.common.collect.ImmutableList<java.lang.Class<out OptionsBase?>?> =
            com.google.common.collect.ImmutableList.of<E?>(
                com.google.devtools.common.options.TestOptions::class.java,
                CommonCommandOptions::class.java,
                ClientOptions::class.java
            )

        val helper: BlazeOptionHandlerTestHelper =
            BlazeOptionHandlerTestHelper(
                optionsClasses,  /* allowResidue= */
                true,  /* aliasFlag= */
                null,  /* skipStarlarkPrefixes= */
                true
            )
        eventHandler = helper.getEventHandler()
        parser = helper.getOptionsParser()
        optionHandler = helper.getOptionHandler()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructureRcOptionsAndConfigs_argumentless() {
        val structuredRc: com.google.common.collect.ListMultimap<String?, RcChunkOfArgs?>? =
            BlazeOptionHandler.structureRcOptionsAndConfigs(
                eventHandler,
                mutableListOf<T?>("rc1", "rc2"),
                mutableListOf<T?>(),
                com.google.common.collect.ImmutableSet.of<E?>("build", "c1")
            )
        Truth.assertThat(structuredRc).isEmpty()
        Truth.assertThat(eventHandler.isEmpty()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructureRcOptionsAndConfigs_configOnly() {
        BlazeOptionHandler.structureRcOptionsAndConfigs(
            eventHandler,
            mutableListOf<T?>("rc1", "rc2"),
            java.util.Arrays.< T > asList < T ? > (OptionOverride(0, "build:none", "a")),
            com.google.common.collect.ImmutableSet.of<E?>("build")
        )
        Truth.assertThat(eventHandler.isEmpty()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructureRcOptionsAndConfigs_emptyConfig() {
        val structuredRc: com.google.common.collect.ListMultimap<String?, RcChunkOfArgs?>? =
            BlazeOptionHandler.structureRcOptionsAndConfigs(
                eventHandler,
                java.util.Arrays.< T > asList < T ? > ("rc1"),
                java.util.Arrays.< T > asList < T ? > (OptionOverride(0, "common:foo", "")),
                com.google.common.collect.ImmutableSet.of<E?>("build")
            )
        Truth.assertThat(structuredRc)
            .containsExactly("common:foo", RcChunkOfArgs("rc1", com.google.common.collect.ImmutableList.of<E?>()))
        Truth.assertThat(eventHandler.isEmpty()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructureRcOptionsAndConfigs_invalidCommand() {
        BlazeOptionHandler.structureRcOptionsAndConfigs(
            eventHandler,
            mutableListOf<T?>("rc1", "rc2"),
            java.util.Arrays.< T > asList < T ? > (OptionOverride(0, "c1", "a")),
            com.google.common.collect.ImmutableSet.of<E?>("build")
        )
        Truth.assertThat(eventHandler.getEvents())
            .contains(
                com.google.devtools.build.lib.events.Event.warn("while reading option defaults file 'rc1':\n  invalid command name 'c1'.")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructureRcOptionsAndConfigs_twoRcs() {
        val structuredRc: com.google.common.collect.ListMultimap<String?, RcChunkOfArgs?>? =
            BlazeOptionHandler.structureRcOptionsAndConfigs(
                eventHandler,
                mutableListOf<T?>("rc1", "rc2"),
                java.util.Arrays.asList<T?>(
                    OptionOverride(0, "build", "a"),
                    OptionOverride(0, "build:config", "b"),
                    OptionOverride(1, "common", "c"),
                    OptionOverride(1, "build", "d"),
                    OptionOverride(1, "build", "e"),
                    OptionOverride(1, "c1:other", "f"),
                    OptionOverride(1, "c1:other", "g")
                ),
                com.google.common.collect.ImmutableSet.of<E?>("build", "c1")
            )
        Truth.assertThat(structuredRc).isEqualTo(structuredArgsFrom2SimpleRcsWithOnlyResidue())
        Truth.assertThat(eventHandler.isEmpty()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructureRcOptionsAndConfigs_importedRcs() {
        val structuredRc: com.google.common.collect.ListMultimap<String?, RcChunkOfArgs?>? =
            BlazeOptionHandler.structureRcOptionsAndConfigs(
                eventHandler,
                mutableListOf<T?>("rc1", "rc2"),
                java.util.Arrays.asList<T?>(
                    OptionOverride(0, "build", "a"),
                    OptionOverride(0, "build:config", "b"),
                    OptionOverride(1, "common", "c"),
                    OptionOverride(1, "build", "d"),
                    OptionOverride(1, "build", "e"),
                    OptionOverride(1, "c1:other", "f"),
                    OptionOverride(1, "c1:other", "g"),
                    OptionOverride(0, "build", "h")
                ),
                com.google.common.collect.ImmutableSet.of<E?>("build", "c1")
            )
        Truth.assertThat(structuredRc).isEqualTo(structuredArgsFromImportedRcsWithOnlyResidue())
        Truth.assertThat(eventHandler.isEmpty()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructureRcOptionsAndConfigs_badOverrideIndex() {
        val structuredRc: com.google.common.collect.ListMultimap<String?, RcChunkOfArgs?>? =
            BlazeOptionHandler.structureRcOptionsAndConfigs(
                eventHandler,
                mutableListOf<T?>("rc1", "rc2"),
                java.util.Arrays.asList<T?>(
                    OptionOverride(0, "build", "a"),
                    OptionOverride(0, "build:config", "b"),
                    OptionOverride(2, "c4:other", "z"),
                    OptionOverride(-1, "c3:other", "q"),
                    OptionOverride(1, "common", "c"),
                    OptionOverride(1, "build", "d"),
                    OptionOverride(1, "build", "e"),
                    OptionOverride(1, "c1:other", "f"),
                    OptionOverride(1, "c1:other", "g")
                ),
                com.google.common.collect.ImmutableSet.of<E?>("build", "c1")
            )
        Truth.assertThat(structuredRc).isEqualTo(structuredArgsFrom2SimpleRcsWithOnlyResidue())
        Truth.assertThat(eventHandler.getEvents())
            .containsAtLeast(
                com.google.devtools.build.lib.events.Event.warn("inconsistency in generated command line args. Ignoring bogus argument\n"),
                com.google.devtools.build.lib.events.Event.warn("inconsistency in generated command line args. Ignoring bogus argument\n")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParseRcOptions_empty() {
        optionHandler.parseRcOptions(eventHandler, com.google.common.collect.ArrayListMultimap.create<K?, V?>())
        Truth.assertThat(eventHandler.getEvents()).isEmpty()
        Truth.assertThat(parser.getResidue()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParseRcOptions_flatRcs_residue() {
        optionHandler.parseRcOptions(eventHandler, structuredArgsFrom2SimpleRcsWithOnlyResidue())
        Truth.assertThat(eventHandler.getEvents()).isEmpty()
        Truth.assertThat(parser.getResidue()).containsExactly("c", "a", "d", "e").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParseRcOptions_flatRcs_flags() {
        optionHandler.parseRcOptions(eventHandler, structuredArgsFrom2SimpleRcsWithFlags())
        Truth.assertThat(eventHandler.getEvents()).isEmpty()
        val options: com.google.devtools.common.options.TestOptions? =
            parser.getOptions<O?>(com.google.devtools.common.options.TestOptions::class.java)
        Truth.assertThat(options).isNotNull()
        Truth.assertThat(options.getTestMultipleString()).containsExactly("common", "foo", "bar").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParseRcOptions_importedRcs_residue() {
        optionHandler.parseRcOptions(eventHandler, structuredArgsFromImportedRcsWithOnlyResidue())
        Truth.assertThat(eventHandler.getEvents()).isEmpty()
        Truth.assertThat(parser.getResidue()).containsExactly("c", "a", "d", "e", "h").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandConfigOptions_configless() {
        optionHandler.expandConfigOptions(eventHandler, structuredArgsFrom2SimpleRcsWithOnlyResidue())
        Truth.assertThat(parser.getResidue()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandConfigOptions_withConfig() {
        parser.parse("--config=config")
        optionHandler.expandConfigOptions(eventHandler, structuredArgsFrom2SimpleRcsWithOnlyResidue())
        Truth.assertThat(parser.getResidue()).containsExactly("b")
        assertThat(optionHandler.rcfileNotes)
            .containsExactly("Found applicable config definition build:config in file rc1: b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandConfigOptions_withPlatformSpecificConfigEnabled() {
        parser.parse("--enable_platform_specific_config")
        optionHandler.expandConfigOptions(eventHandler, structuredArgsForDifferentPlatforms())
        when (com.google.devtools.build.lib.util.OS.getCurrent()) {
            com.google.devtools.build.lib.util.OS.LINUX -> Truth.assertThat(parser.getResidue())
                .containsExactly("command_linux")

            com.google.devtools.build.lib.util.OS.DARWIN -> Truth.assertThat(parser.getResidue())
                .containsExactly("command_macos")

            com.google.devtools.build.lib.util.OS.WINDOWS -> Truth.assertThat(parser.getResidue())
                .containsExactly("command_windows")

            com.google.devtools.build.lib.util.OS.FREEBSD -> Truth.assertThat(parser.getResidue())
                .containsExactly("command_freebsd")

            com.google.devtools.build.lib.util.OS.OPENBSD -> Truth.assertThat(parser.getResidue())
                .containsExactly("command_openbsd")

            else -> Truth.assertThat(parser.getResidue()).isEmpty()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandConfigOptions_withPlatformSpecificConfigEnabledInConfig() {
        // --enable_platform_specific_config itself will affect the selecting of config sections.
        // Because Bazel expands config sections recursively, we want to make sure it's fine to enable
        // --enable_platform_specific_config via another config section.
        parser.parse("--config=platform_config")
        optionHandler.expandConfigOptions(eventHandler, structuredArgsForDifferentPlatforms())
        when (com.google.devtools.build.lib.util.OS.getCurrent()) {
            com.google.devtools.build.lib.util.OS.LINUX -> Truth.assertThat(parser.getResidue())
                .containsExactly("command_linux")

            com.google.devtools.build.lib.util.OS.DARWIN -> Truth.assertThat(parser.getResidue())
                .containsExactly("command_macos")

            com.google.devtools.build.lib.util.OS.WINDOWS -> Truth.assertThat(parser.getResidue())
                .containsExactly("command_windows")

            com.google.devtools.build.lib.util.OS.FREEBSD -> Truth.assertThat(parser.getResidue())
                .containsExactly("command_freebsd")

            com.google.devtools.build.lib.util.OS.OPENBSD -> Truth.assertThat(parser.getResidue())
                .containsExactly("command_openbsd")

            else -> Truth.assertThat(parser.getResidue()).isEmpty()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandConfigOptions_withPlatformSpecificConfigEnabledWhenNothingSpecified() {
        parser.parse("--enable_platform_specific_config")
        optionHandler.parseRcOptions(eventHandler, com.google.common.collect.ArrayListMultimap.create<K?, V?>())
        Truth.assertThat(eventHandler.getEvents()).isEmpty()
        Truth.assertThat(parser.getResidue()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandConfigOptions_withConfigForUnapplicableCommand() {
        parser.parse("--config=other")
        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    optionHandler.expandConfigOptions(
                        eventHandler, structuredArgsFrom2SimpleRcsWithOnlyResidue()
                    )
                })
        Truth.assertThat(parser.getResidue()).isEmpty()
        assertThat(optionHandler.rcfileNotes).isEmpty()
        Truth.assertThat(e).hasMessageThat().contains("Config value 'other' is not defined in any .rc file")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandConfigOptions_skippedArgsOrderPreserved() {
        val rcContent: com.google.common.collect.ImmutableListMultimap<String?, RcChunkOfArgs?> =
            com.google.common.collect.ImmutableListMultimap.of<String?, RcChunkOfArgs?>(
                "build:config1",
                RcChunkOfArgs("rc1", com.google.common.collect.ImmutableList.of<E?>("--//f=2", "--//f=3")),
                "build:config2b",
                RcChunkOfArgs("rc1", com.google.common.collect.ImmutableList.of<E?>("--//f=6")),
                "build:config2",
                RcChunkOfArgs(
                    "rc1",
                    com.google.common.collect.ImmutableList.of<E?>("--config=config2a", "--config=config2b")
                ),
                "build:config2a",
                RcChunkOfArgs("rc1", com.google.common.collect.ImmutableList.of<E?>("--//f=5"))
            )
        parser.parse(
            "--test_multiple_string=1",
            "--//f=1",
            "--test_multiple_string=2",
            "--config=config1",
            "--test_multiple_string=3",
            "--//f=4",
            "--test_multiple_string=4",
            "--config=config2",
            "--test_multiple_string=5",
            "--//f=7",
            "--test_multiple_string=6"
        )
        optionHandler.expandConfigOptions(eventHandler, rcContent)

        Truth.assertThat(parser.getSkippedArgs())
            .containsExactly(
                "--//f=1", "--//f=2", "--//f=3", "--//f=4", "--//f=5", "--//f=6", "--//f=7"
            )
            .inOrder()

        // Verify that the order of non-skipped args is as expected and that skipped args are not
        // reported as parsed.
        Truth.assertThat(
            parser.asListOfCanonicalOptions().stream()
                .map<String?> { obj: ParsedOptionDescription? -> obj.getCanonicalForm() }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>()))
            .containsExactly(
                "--test_multiple_string=1",
                "--test_multiple_string=2",
                "--config=config1",
                "--test_multiple_string=3",
                "--test_multiple_string=4",
                "--config=config2",
                "--config=config2a",
                "--config=config2b",
                "--test_multiple_string=5",
                "--test_multiple_string=6"
            )
            .inOrder()
        Truth.assertThat(
            parser.asCompleteListOfParsedOptions().stream()
                .map<String?> { obj: ParsedOptionDescription? -> obj.getCanonicalForm() }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>()))
            .containsExactly(
                "--test_multiple_string=1",
                "--test_multiple_string=2",
                "--config=config1",
                "--test_multiple_string=3",
                "--test_multiple_string=4",
                "--config=config2",
                "--config=config2a",
                "--config=config2b",
                "--test_multiple_string=5",
                "--test_multiple_string=6"
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUndefinedConfig() {
        parser.parse("--config=invalid")
        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    optionHandler.expandConfigOptions(
                        eventHandler,
                        com.google.common.collect.ArrayListMultimap.create<K?, V?>()
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Config value 'invalid' is not defined in any .rc file")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandConfigOptions_emptyConfig() {
        parser.parse("--config=empty")
        val rcContent: com.google.common.collect.ListMultimap<String?, RcChunkOfArgs?> =
            com.google.common.collect.ArrayListMultimap.create<String?, RcChunkOfArgs?>()
        rcContent.put("common:empty", RcChunkOfArgs("rc1", com.google.common.collect.ImmutableList.of<E?>()))

        optionHandler.expandConfigOptions(eventHandler, rcContent)

        Truth.assertThat(eventHandler.getEvents()).isEmpty()
        Truth.assertThat(parser.getResidue()).isEmpty()
        assertThat(optionHandler.rcfileNotes)
            .containsExactly("Found applicable config definition common:empty in file rc1: ")
    }

    @org.junit.Test
    fun testParseOptions_argless() {
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(
                com.google.common.collect.ImmutableList.of<E?>("build"),
                eventHandler,
                com.google.common.collect.ImmutableList.builder<E?>()
            )
        Truth.assertThat(eventHandler.getEvents()).isEmpty()
        Truth.assertThat(parser.getResidue()).isEmpty()
        assertThat(optionHandler.rcfileNotes).isEmpty()
    }

    @org.junit.Test
    fun testParseOptions_residue() {
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(
                com.google.common.collect.ImmutableList.of<E?>("build", "res"),
                eventHandler,
                com.google.common.collect.ImmutableList.builder<E?>()
            )
        Truth.assertThat(eventHandler.getEvents()).isEmpty()
        Truth.assertThat(parser.getResidue()).contains("res")
        assertThat(optionHandler.rcfileNotes).isEmpty()
    }

    @org.junit.Test
    fun testParseOptions_explicitOption() {
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(
                com.google.common.collect.ImmutableList.of<E?>("build", "--test_multiple_string=explicit"),
                eventHandler,
                com.google.common.collect.ImmutableList.builder<E?>()
            )
        Truth.assertThat(eventHandler.getEvents()).isEmpty()
        Truth.assertThat(parser.getResidue()).isEmpty()
        assertThat(optionHandler.rcfileNotes).isEmpty()
        val options: com.google.devtools.common.options.TestOptions? =
            parser.getOptions<O?>(com.google.devtools.common.options.TestOptions::class.java)
        Truth.assertThat(options).isNotNull()
        Truth.assertThat(options.getTestMultipleString()).containsExactly("explicit")
    }

    @org.junit.Test
    fun testParseOptions_rcOption() {
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(
                com.google.common.collect.ImmutableList.of<E?>(
                    "build",
                    "--default_override=0:build=--test_multiple_string=rc_a",
                    "--default_override=0:build=--test_multiple_string=rc_b",
                    "--rc_source=/somewhere/.blazerc"
                ),
                eventHandler,
                com.google.common.collect.ImmutableList.builder<E?>()
            )
        Truth.assertThat(eventHandler.getEvents()).isEmpty()
        Truth.assertThat(parser.getResidue()).isEmpty()
        // Check that multiple options in the same rc chunk are collapsed into 1 announce_rc entry.
        assertThat(optionHandler.rcfileNotes)
            .containsExactly(
                "Reading rc options for 'build' from /somewhere/.blazerc:\n"
                        + "  'build' options: --test_multiple_string=rc_a --test_multiple_string=rc_b"
            )
        val options: com.google.devtools.common.options.TestOptions? =
            parser.getOptions<O?>(com.google.devtools.common.options.TestOptions::class.java)
        Truth.assertThat(options).isNotNull()
        Truth.assertThat(options.getTestMultipleString()).containsExactly("rc_a", "rc_b")
    }

    @org.junit.Test
    fun testParseOptions_multipleRcs() {
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(
                com.google.common.collect.ImmutableList.of<E?>(
                    "build",
                    "--default_override=0:build=--test_multiple_string=rc1_a",
                    "--default_override=1:build=--test_multiple_string=rc2",
                    "--default_override=0:build=--test_multiple_string=rc1_b",
                    "--rc_source=/somewhere/.blazerc",
                    "--rc_source=/some/other/.blazerc"
                ),
                eventHandler,
                com.google.common.collect.ImmutableList.builder<E?>()
            )
        Truth.assertThat(eventHandler.getEvents()).isEmpty()
        Truth.assertThat(parser.getResidue()).isEmpty()
        assertThat(optionHandler.rcfileNotes)
            .containsExactly(
                "Reading rc options for 'build' from /somewhere/.blazerc:\n"
                        + "  'build' options: --test_multiple_string=rc1_a",
                "Reading rc options for 'build' from /some/other/.blazerc:\n"
                        + "  'build' options: --test_multiple_string=rc2",
                "Reading rc options for 'build' from /somewhere/.blazerc:\n"
                        + "  'build' options: --test_multiple_string=rc1_b"
            )
        val options: com.google.devtools.common.options.TestOptions? =
            parser.getOptions<O?>(com.google.devtools.common.options.TestOptions::class.java)
        Truth.assertThat(options).isNotNull()
        Truth.assertThat(options.getTestMultipleString()).containsExactly("rc1_a", "rc2", "rc1_b").inOrder()
    }

    @org.junit.Test
    fun testParseOptions_multipleRcsWithMultipleCommands() {
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(
                com.google.common.collect.ImmutableList.of<E?>(
                    "build",
                    "--default_override=0:build=--test_multiple_string=rc1_a",
                    "--default_override=1:build=--test_multiple_string=rc2",
                    "--default_override=1:common=--test_multiple_string=rc2_common",
                    "--default_override=0:build=--test_multiple_string=rc1_b",
                    "--default_override=0:common=--test_multiple_string=rc1_common",
                    "--rc_source=/somewhere/.blazerc",
                    "--rc_source=/some/other/.blazerc"
                ),
                eventHandler,
                com.google.common.collect.ImmutableList.builder<E?>()
            )
        Truth.assertThat(eventHandler.getEvents()).isEmpty()
        Truth.assertThat(parser.getResidue()).isEmpty()
        assertThat(optionHandler.rcfileNotes)
            .containsExactly(
                "Reading rc options for 'build' from /some/other/.blazerc:\n"
                        + "  Inherited 'common' options: --test_multiple_string=rc2_common",
                "Reading rc options for 'build' from /somewhere/.blazerc:\n"
                        + "  Inherited 'common' options: --test_multiple_string=rc1_common",
                "Reading rc options for 'build' from /somewhere/.blazerc:\n"
                        + "  'build' options: --test_multiple_string=rc1_a",
                "Reading rc options for 'build' from /some/other/.blazerc:\n"
                        + "  'build' options: --test_multiple_string=rc2",
                "Reading rc options for 'build' from /somewhere/.blazerc:\n"
                        + "  'build' options: --test_multiple_string=rc1_b"
            )
        val options: com.google.devtools.common.options.TestOptions? =
            parser.getOptions<O?>(com.google.devtools.common.options.TestOptions::class.java)
        Truth.assertThat(options).isNotNull()
        Truth.assertThat(options.getTestMultipleString())
            .containsExactly("rc2_common", "rc1_common", "rc1_a", "rc2", "rc1_b")
            .inOrder()
    }

    @org.junit.Test
    fun testParseOptions_rcOptionAndExplicit() {
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(
                com.google.common.collect.ImmutableList.of<E?>(
                    "build",
                    "--default_override=0:build=--test_multiple_string=rc",
                    "--rc_source=/somewhere/.blazerc",
                    "--test_multiple_string=explicit"
                ),
                eventHandler,
                com.google.common.collect.ImmutableList.builder<E?>()
            )
        Truth.assertThat(eventHandler.getEvents()).isEmpty()
        Truth.assertThat(parser.getResidue()).isEmpty()
        assertThat(optionHandler.rcfileNotes)
            .containsExactly(
                "Reading rc options for 'build' from /somewhere/.blazerc:\n"
                        + "  'build' options: --test_multiple_string=rc"
            )
        val options: com.google.devtools.common.options.TestOptions? =
            parser.getOptions<O?>(com.google.devtools.common.options.TestOptions::class.java)
        Truth.assertThat(options).isNotNull()
        Truth.assertThat(options.getTestMultipleString()).containsExactly("rc", "explicit").inOrder()
    }

    @org.junit.Test
    fun testParseOptions_multiCommandRcOptionAndExplicit() {
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(
                com.google.common.collect.ImmutableList.of<E?>(
                    "build",
                    "--default_override=0:build=--test_multiple_string=rc_build_1",
                    "--default_override=0:common=--test_multiple_string=rc_common",
                    "--default_override=0:build=--test_multiple_string=rc_build_2",
                    "--rc_source=/somewhere/.blazerc",
                    "--test_multiple_string=explicit"
                ),
                eventHandler,
                com.google.common.collect.ImmutableList.builder<E?>()
            )
        Truth.assertThat(eventHandler.getEvents()).isEmpty()
        Truth.assertThat(parser.getResidue()).isEmpty()
        assertThat(optionHandler.rcfileNotes)
            .containsExactly(
                "Reading rc options for 'build' from /somewhere/.blazerc:\n"
                        + "  Inherited 'common' options: --test_multiple_string=rc_common",
                ("Reading rc options for 'build' from /somewhere/.blazerc:\n"
                        + "  'build' options: --test_multiple_string=rc_build_1"
                        + " --test_multiple_string=rc_build_2")
            )
        val options: com.google.devtools.common.options.TestOptions? =
            parser.getOptions<O?>(com.google.devtools.common.options.TestOptions::class.java)
        Truth.assertThat(options).isNotNull()
        Truth.assertThat(options.getTestMultipleString())
            .containsExactly("rc_common", "rc_build_1", "rc_build_2", "explicit")
            .inOrder()
    }

    @org.junit.Test
    fun testParseOptions_multipleRcsWithMultipleCommandsPlusExplicitOption() {
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(
                com.google.common.collect.ImmutableList.of<E?>(
                    "build",
                    "--default_override=0:build=--test_multiple_string=rc1_a",
                    "--default_override=1:build=--test_multiple_string=rc2",
                    "--test_multiple_string=explicit",
                    "--default_override=1:common=--test_multiple_string=rc2_common",
                    "--default_override=0:build=--test_multiple_string=rc1_b",
                    "--default_override=0:common=--test_multiple_string=rc1_common",
                    "--rc_source=/somewhere/.blazerc",
                    "--rc_source=/some/other/.blazerc"
                ),
                eventHandler,
                com.google.common.collect.ImmutableList.builder<E?>()
            )
        Truth.assertThat(eventHandler.getEvents()).isEmpty()
        Truth.assertThat(parser.getResidue()).isEmpty()
        assertThat(optionHandler.rcfileNotes)
            .containsExactly(
                "Reading rc options for 'build' from /some/other/.blazerc:\n"
                        + "  Inherited 'common' options: --test_multiple_string=rc2_common",
                "Reading rc options for 'build' from /somewhere/.blazerc:\n"
                        + "  Inherited 'common' options: --test_multiple_string=rc1_common",
                "Reading rc options for 'build' from /somewhere/.blazerc:\n"
                        + "  'build' options: --test_multiple_string=rc1_a",
                "Reading rc options for 'build' from /some/other/.blazerc:\n"
                        + "  'build' options: --test_multiple_string=rc2",
                "Reading rc options for 'build' from /somewhere/.blazerc:\n"
                        + "  'build' options: --test_multiple_string=rc1_b"
            )
        val options: com.google.devtools.common.options.TestOptions? =
            parser.getOptions<O?>(com.google.devtools.common.options.TestOptions::class.java)
        Truth.assertThat(options).isNotNull()
        Truth.assertThat(options.getTestMultipleString())
            .containsExactly("rc2_common", "rc1_common", "rc1_a", "rc2", "rc1_b", "explicit")
            .inOrder()
    }

    @org.junit.Test
    fun testParseOptions_explicitConfig() {
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(
                com.google.common.collect.ImmutableList.of<E?>(
                    "build",
                    "--default_override=0:build=--test_multiple_string=rc",
                    "--default_override=0:build:conf=--test_multiple_string=config",
                    "--rc_source=/somewhere/.blazerc",
                    "--test_multiple_string=explicit",
                    "--config=conf"
                ),
                eventHandler,
                com.google.common.collect.ImmutableList.builder<E?>()
            )
        Truth.assertThat(eventHandler.getEvents()).isEmpty()
        Truth.assertThat(parser.getResidue()).isEmpty()
        assertThat(optionHandler.rcfileNotes)
            .containsExactly(
                "Reading rc options for 'build' from /somewhere/.blazerc:\n"
                        + "  'build' options: --test_multiple_string=rc",
                "Found applicable config definition build:conf in file /somewhere/.blazerc: "
                        + "--test_multiple_string=config"
            )

        // "config" is expanded from --config=conf, which occurs last.
        val options: com.google.devtools.common.options.TestOptions? =
            parser.getOptions<O?>(com.google.devtools.common.options.TestOptions::class.java)
        Truth.assertThat(options).isNotNull()
        Truth.assertThat(options.getTestMultipleString())
            .containsExactly("rc", "explicit", "config")
            .inOrder()
    }

    @org.junit.Test
    fun testParseOptions_explicitEmptyConfig() {
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(
                com.google.common.collect.ImmutableList.of<E?>(
                    "build",
                    "--default_override=0:common:empty=",
                    "--rc_source=/somewhere/.blazerc",
                    "--test_multiple_string=explicit",
                    "--config=empty"
                ),
                eventHandler,
                com.google.common.collect.ImmutableList.builder<E?>()
            )
        Truth.assertThat(eventHandler.getEvents()).isEmpty()
        Truth.assertThat(parser.getResidue()).isEmpty()
        assertThat(optionHandler.rcfileNotes)
            .containsExactly(
                "Found applicable config definition common:empty in file /somewhere/.blazerc: "
            )

        val options: com.google.devtools.common.options.TestOptions? =
            parser.getOptions<O?>(com.google.devtools.common.options.TestOptions::class.java)
        Truth.assertThat(options).isNotNull()
        Truth.assertThat(options.getTestMultipleString()).containsExactly("explicit")
    }

    @org.junit.Test
    fun testParseOptions_rcSpecifiedConfig() {
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(
                com.google.common.collect.ImmutableList.of<E?>(
                    "build",
                    "--default_override=0:build=--config=conf",
                    "--default_override=0:build=--test_multiple_string=rc",
                    "--default_override=0:build:conf=--test_multiple_string=config",
                    "--rc_source=/somewhere/.blazerc",
                    "--test_multiple_string=explicit"
                ),
                eventHandler,
                com.google.common.collect.ImmutableList.builder<E?>()
            )
        Truth.assertThat(eventHandler.getEvents()).isEmpty()
        Truth.assertThat(parser.getResidue()).isEmpty()
        assertThat(optionHandler.rcfileNotes)
            .containsExactly(
                "Reading rc options for 'build' from /somewhere/.blazerc:\n"
                        + "  'build' options: --config=conf --test_multiple_string=rc",
                "Found applicable config definition build:conf in file /somewhere/.blazerc: "
                        + "--test_multiple_string=config"
            )

        // "config" is expanded from --config=conf, which occurs before the explicit mention of "rc".
        val options: com.google.devtools.common.options.TestOptions? =
            parser.getOptions<O?>(com.google.devtools.common.options.TestOptions::class.java)
        Truth.assertThat(options).isNotNull()
        Truth.assertThat(options.getTestMultipleString())
            .containsExactly("config", "rc", "explicit")
            .inOrder()
    }

    @org.junit.Test
    fun testParseOptions_recursiveConfig() {
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(
                com.google.common.collect.ImmutableList.of<E?>(
                    "build",
                    "--default_override=0:build=--config=conf",
                    "--default_override=0:build=--test_multiple_string=rc",
                    "--default_override=0:build:other=--test_multiple_string=other",
                    "--default_override=0:build:conf=--test_multiple_string=config1",
                    "--default_override=0:build:conf=--config=other",
                    "--default_override=0:common:other=--test_multiple_string=othercommon",
                    "--rc_source=/somewhere/.blazerc",
                    "--test_multiple_string=explicit"
                ),
                eventHandler,
                com.google.common.collect.ImmutableList.builder<E?>()
            )
        Truth.assertThat(eventHandler.getEvents()).isEmpty()
        Truth.assertThat(parser.getResidue()).isEmpty()
        assertThat(optionHandler.rcfileNotes)
            .containsExactly(
                "Reading rc options for 'build' from /somewhere/.blazerc:\n"
                        + "  'build' options: --config=conf --test_multiple_string=rc",
                "Found applicable config definition build:conf in file /somewhere/.blazerc: "
                        + "--test_multiple_string=config1 --config=other",
                "Found applicable config definition common:other in file /somewhere/.blazerc: "
                        + "--test_multiple_string=othercommon",
                "Found applicable config definition build:other in file /somewhere/.blazerc: "
                        + "--test_multiple_string=other"
            )

        // The 2nd config, --config=other, is added by --config=conf after conf adds its own value.
        val options: com.google.devtools.common.options.TestOptions? =
            parser.getOptions<O?>(com.google.devtools.common.options.TestOptions::class.java)
        Truth.assertThat(options).isNotNull()
        Truth.assertThat(options.getTestMultipleString())
            .containsExactly("config1", "othercommon", "other", "rc", "explicit")
            .inOrder()
    }

    @org.junit.Test
    fun testParseOptions_recursiveConfigWithDifferentTokens() {
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(
                com.google.common.collect.ImmutableList.of<E?>(
                    "build",
                    "--default_override=0:build=--test_multiple_string=rc",
                    "--default_override=0:build:other=--test_multiple_string=other",
                    "--default_override=0:build:conf=--test_multiple_string=config1",
                    "--default_override=0:build:conf=--config",
                    "--default_override=0:build:conf=other",
                    "--rc_source=/somewhere/.blazerc",
                    "--config=conf"
                ),
                eventHandler,
                com.google.common.collect.ImmutableList.builder<E?>()
            )

        Truth.assertThat(eventHandler.getEvents())
            .containsExactly(
                com.google.devtools.build.lib.events.Event.error(
                    ("In file /somewhere/.blazerc, the definition of config conf expands to another "
                            + "config that either has no value or is not in the form --config=value. For "
                            + "recursive config definitions, please do not provide the value in a "
                            + "separate token, such as in the form '--config value'.")
                )
            )
    }

    @org.junit.Test
    fun testParseOptions_complexConfigOrder() {
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(
                com.google.common.collect.ImmutableList.of<E?>(
                    "build",
                    "--default_override=0:build=--test_multiple_string=rc1",
                    "--default_override=0:build=--config=foo",
                    "--default_override=0:build=--test_multiple_string=rc2",
                    "--default_override=0:common:baz=--test_multiple_string=baz1",
                    "--default_override=0:build:baz=--test_multiple_string=baz2",
                    "--default_override=0:common:foo=--test_multiple_string=foo1",
                    "--default_override=0:common:foo=--config=bar",
                    "--default_override=0:build:foo=--test_multiple_string=foo3",
                    "--default_override=0:common:foo=--test_multiple_string=foo2",
                    "--default_override=0:build:foo=--test_multiple_string=foo4",
                    "--default_override=0:common:bar=--test_multiple_string=bar1",
                    "--default_override=0:build:bar=--test_multiple_string=bar2",
                    "--rc_source=/somewhere/.blazerc",
                    "--test_multiple_string=explicit1",
                    "--config=baz",
                    "--test_multiple_string=explicit2"
                ),
                eventHandler,
                com.google.common.collect.ImmutableList.builder<E?>()
            )
        Truth.assertThat(eventHandler.getEvents()).isEmpty()
        Truth.assertThat(parser.getResidue()).isEmpty()
        assertThat(optionHandler.rcfileNotes)
            .containsExactly(
                "Reading rc options for 'build' from /somewhere/.blazerc:\n  'build' options: "
                        + "--test_multiple_string=rc1 --config=foo --test_multiple_string=rc2",
                "Found applicable config definition common:foo in file /somewhere/.blazerc: "
                        + "--test_multiple_string=foo1 --config=bar --test_multiple_string=foo2",
                "Found applicable config definition common:bar in file /somewhere/.blazerc: "
                        + "--test_multiple_string=bar1",
                "Found applicable config definition build:bar in file /somewhere/.blazerc: "
                        + "--test_multiple_string=bar2",
                "Found applicable config definition build:foo in file /somewhere/.blazerc: "
                        + "--test_multiple_string=foo3 --test_multiple_string=foo4",
                "Found applicable config definition common:baz in file /somewhere/.blazerc: "
                        + "--test_multiple_string=baz1",
                "Found applicable config definition build:baz in file /somewhere/.blazerc: "
                        + "--test_multiple_string=baz2"
            )
        val options: com.google.devtools.common.options.TestOptions? =
            parser.getOptions<O?>(com.google.devtools.common.options.TestOptions::class.java)
        Truth.assertThat(options).isNotNull()
        Truth.assertThat(options.getTestMultipleString())
            .containsExactly(
                "rc1",
                "foo1",
                "bar1",
                "bar2",
                "foo2",
                "foo3",
                "foo4",
                "rc2",
                "explicit1",
                "baz1",
                "baz2",
                "explicit2"
            )
            .inOrder()
    }

    @org.junit.Test
    fun testParseOptions_repeatSubConfig() {
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(
                com.google.common.collect.ImmutableList.of<E?>(
                    "build",
                    "--default_override=0:build=--config=foo",
                    "--default_override=0:build=--test_multiple_string=rc",
                    "--default_override=0:build:foo=--test_multiple_string=foo",
                    "--default_override=0:build:foo=--config=bar",
                    "--default_override=0:build:foo=--config=bar",
                    "--default_override=0:build:bar=--test_multiple_string=bar",
                    "--rc_source=/somewhere/.blazerc",
                    "--test_multiple_string=explicit"
                ),
                eventHandler,
                com.google.common.collect.ImmutableList.builder<E?>()
            )
        Truth.assertThat(parser.getResidue()).isEmpty()
        Truth.assertThat(eventHandler.getEvents())
            .containsExactly(
                com.google.devtools.build.lib.events.Event.warn(
                    "The following configs were expanded more than once: [bar]. For repeatable flags, "
                            + "repeats are counted twice and may lead to unexpected behavior."
                )
            )
        assertThat(optionHandler.rcfileNotes)
            .containsExactly(
                "Reading rc options for 'build' from /somewhere/.blazerc:\n"
                        + "  'build' options: --config=foo --test_multiple_string=rc",
                "Found applicable config definition build:foo in file /somewhere/.blazerc: "
                        + "--test_multiple_string=foo --config=bar --config=bar",
                "Found applicable config definition build:bar in file /somewhere/.blazerc: "
                        + "--test_multiple_string=bar",
                "Found applicable config definition build:bar in file /somewhere/.blazerc: "
                        + "--test_multiple_string=bar"
            )
        val options: com.google.devtools.common.options.TestOptions? =
            parser.getOptions<O?>(com.google.devtools.common.options.TestOptions::class.java)
        Truth.assertThat(options).isNotNull()
        // Bar is repeated, since it was included twice.
        Truth.assertThat(options.getTestMultipleString())
            .containsExactly("foo", "bar", "bar", "rc", "explicit")
            .inOrder()
    }

    @org.junit.Test
    fun testParseOptions_repeatConfig() {
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(
                com.google.common.collect.ImmutableList.of<E?>(
                    "build",
                    "--default_override=0:build:foo=--test_multiple_string=foo",
                    "--default_override=0:build:foo=--config=bar",
                    "--default_override=0:build:bar=--test_multiple_string=bar",
                    "--default_override=0:build:baz=--test_multiple_string=baz",
                    "--rc_source=/somewhere/.blazerc",
                    "--config=foo",
                    "--config=baz",
                    "--config=foo",
                    "--config=bar"
                ),
                eventHandler,
                com.google.common.collect.ImmutableList.builder<E?>()
            )
        Truth.assertThat(parser.getResidue()).isEmpty()
        Truth.assertThat(eventHandler.getEvents())
            .containsExactly(
                com.google.devtools.build.lib.events.Event.warn(
                    "The following configs were expanded more than once: [foo, bar]. For repeatable "
                            + "flags, repeats are counted twice and may lead to unexpected behavior."
                )
            )
        assertThat(optionHandler.rcfileNotes)
            .containsExactly(
                "Found applicable config definition build:foo in file /somewhere/.blazerc: "
                        + "--test_multiple_string=foo --config=bar",
                "Found applicable config definition build:bar in file /somewhere/.blazerc: "
                        + "--test_multiple_string=bar",
                "Found applicable config definition build:baz in file /somewhere/.blazerc: "
                        + "--test_multiple_string=baz",
                "Found applicable config definition build:foo in file /somewhere/.blazerc: "
                        + "--test_multiple_string=foo --config=bar",
                "Found applicable config definition build:bar in file /somewhere/.blazerc: "
                        + "--test_multiple_string=bar",
                "Found applicable config definition build:bar in file /somewhere/.blazerc: "
                        + "--test_multiple_string=bar"
            )
        val options: com.google.devtools.common.options.TestOptions? =
            parser.getOptions<O?>(com.google.devtools.common.options.TestOptions::class.java)
        Truth.assertThat(options).isNotNull()
        // Bar is repeated, since it was included twice.
        Truth.assertThat(options.getTestMultipleString())
            .containsExactly("foo", "bar", "baz", "foo", "bar", "bar")
            .inOrder()
    }

    @org.junit.Test
    fun testParseOptions_configCycleLength1() {
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(
                com.google.common.collect.ImmutableList.of<E?>(
                    "build",
                    "--default_override=0:build=--config=foo",
                    "--default_override=0:build=--test_multiple_string=rc",
                    "--default_override=0:build:foo=--test_multiple_string=foo",
                    "--default_override=0:build:foo=--config=foo",
                    "--rc_source=/somewhere/.blazerc",
                    "--test_multiple_string=explicit"
                ),
                eventHandler,
                com.google.common.collect.ImmutableList.builder<E?>()
            )
        Truth.assertThat(eventHandler.getEvents())
            .contains(
                com.google.devtools.build.lib.events.Event.error(
                    "Config expansion has a cycle: config value foo expands to itself, see "
                            + "inheritance chain [foo]"
                )
            )
    }

    @org.junit.Test
    fun testParseOptions_configCycleLength2() {
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(
                com.google.common.collect.ImmutableList.of<E?>(
                    "build",
                    "--default_override=0:build=--config=foo",
                    "--default_override=0:build=--test_multiple_string=rc",
                    "--default_override=0:build:foo=--test_multiple_string=foo",
                    "--default_override=0:build:foo=--config=bar",
                    "--default_override=0:build:bar=--test_multiple_string=bar",
                    "--default_override=0:build:bar=--config=foo",
                    "--rc_source=/somewhere/.blazerc",
                    "--test_multiple_string=explicit"
                ),
                eventHandler,
                com.google.common.collect.ImmutableList.builder<E?>()
            )
        Truth.assertThat(eventHandler.getEvents())
            .contains(
                com.google.devtools.build.lib.events.Event.error(
                    "Config expansion has a cycle: config value foo expands to itself, see "
                            + "inheritance chain [foo, bar]"
                )
            )
    }

    @org.junit.Test
    fun testParseOptions_recursiveConfigWasAlreadyPresent() {
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(
                com.google.common.collect.ImmutableList.of<E?>(
                    "build",
                    "--default_override=0:build=--config=other",
                    "--default_override=0:build=--config=conf",
                    "--default_override=0:build=--test_multiple_string=rc",
                    "--default_override=0:build:other=--test_multiple_string=other",
                    "--default_override=0:build:conf=--test_multiple_string=config1",
                    "--default_override=0:build:conf=--config=other",
                    "--default_override=0:common:other=--test_multiple_string=othercommon",
                    "--rc_source=/somewhere/.blazerc",
                    "--test_multiple_string=explicit"
                ),
                eventHandler,
                com.google.common.collect.ImmutableList.builder<E?>()
            )
        Truth.assertThat(parser.getResidue()).isEmpty()
        Truth.assertThat(eventHandler.getEvents())
            .containsExactly(
                com.google.devtools.build.lib.events.Event.warn(
                    "The following configs were expanded more than once: [other]. For repeatable "
                            + "flags, repeats are counted twice and may lead to unexpected behavior."
                )
            )

        // The 2nd config, --config=other, is expanded twice at the same time as --config=conf,
        // both initially present. The "common" definition is therefore first. other is expanded twice.
        assertThat(optionHandler.rcfileNotes)
            .containsExactly(
                "Reading rc options for 'build' from /somewhere/.blazerc:\n"
                        + "  'build' options: --config=other --config=conf --test_multiple_string=rc",
                "Found applicable config definition common:other in file /somewhere/.blazerc: "
                        + "--test_multiple_string=othercommon",
                "Found applicable config definition build:other in file /somewhere/.blazerc: "
                        + "--test_multiple_string=other",
                "Found applicable config definition build:conf in file /somewhere/.blazerc: "
                        + "--test_multiple_string=config1 --config=other",
                "Found applicable config definition common:other in file /somewhere/.blazerc: "
                        + "--test_multiple_string=othercommon",
                "Found applicable config definition build:other in file /somewhere/.blazerc: "
                        + "--test_multiple_string=other"
            )
        val options: com.google.devtools.common.options.TestOptions? =
            parser.getOptions<O?>(com.google.devtools.common.options.TestOptions::class.java)
        Truth.assertThat(options).isNotNull()
        Truth.assertThat(options.getTestMultipleString())
            .containsExactly(
                "othercommon", "other", "config1", "othercommon", "other", "rc", "explicit"
            )
            .inOrder()
    }

    @org.junit.Test
    fun testParseOptions_longChain() {
        val args: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
                .add("build")
                .addAll(GREEK_ALPHABET_CHAIN)
                .add("--rc_source=/somewhere/.blazerc")
                .add("--config=alpha")
                .build()

        val unused: DetailedExitCode? =
            optionHandler.parseOptions(args, eventHandler, com.google.common.collect.ImmutableList.builder<E?>())
        Truth.assertThat(parser.getResidue()).isEmpty()
        assertThat(optionHandler.rcfileNotes)
            .containsExactly(
                "Found applicable config definition build:alpha in file /somewhere/.blazerc: "
                        + "--test_multiple_string=alpha --config=beta",
                "Found applicable config definition build:beta in file /somewhere/.blazerc: "
                        + "--test_multiple_string=beta --config=gamma",
                "Found applicable config definition build:gamma in file /somewhere/.blazerc: "
                        + "--test_multiple_string=gamma --config=delta",
                "Found applicable config definition build:delta in file /somewhere/.blazerc: "
                        + "--test_multiple_string=delta --config=epsilon",
                "Found applicable config definition build:epsilon in file /somewhere/.blazerc: "
                        + "--test_multiple_string=epsilon --config=zeta",
                "Found applicable config definition build:zeta in file /somewhere/.blazerc: "
                        + "--test_multiple_string=zeta --config=eta",
                "Found applicable config definition build:eta in file /somewhere/.blazerc: "
                        + "--test_multiple_string=eta --config=theta",
                "Found applicable config definition build:theta in file /somewhere/.blazerc: "
                        + "--test_multiple_string=theta --config=iota",
                "Found applicable config definition build:iota in file /somewhere/.blazerc: "
                        + "--test_multiple_string=iota --config=kappa",
                "Found applicable config definition build:kappa in file /somewhere/.blazerc: "
                        + "--test_multiple_string=kappa --config=lambda",
                "Found applicable config definition build:lambda in file /somewhere/.blazerc: "
                        + "--test_multiple_string=lambda --config=mu",
                "Found applicable config definition build:mu in file /somewhere/.blazerc: "
                        + "--test_multiple_string=mu"
            )
        val options: com.google.devtools.common.options.TestOptions? =
            parser.getOptions<O?>(com.google.devtools.common.options.TestOptions::class.java)
        Truth.assertThat(options).isNotNull()
        Truth.assertThat(options.getTestMultipleString())
            .containsExactly(
                "alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta", "iota", "kappa",
                "lambda", "mu"
            )
            .inOrder()
        // Expect only one warning, we don't want multiple warnings for the same chain.
        Truth.assertThat(eventHandler.getEvents())
            .containsExactly(
                com.google.devtools.build.lib.events.Event.warn(
                    ("There is a recursive chain of configs 12 configs long: [alpha, beta, gamma, "
                            + "delta, epsilon, zeta, eta, theta, iota, kappa, lambda, mu]. This seems "
                            + "excessive, and might be hiding errors.")
                )
            )
    }

    @org.junit.Test
    fun testParseOptions_2LongChains() {
        val args: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
                .add("build")
                .addAll(GREEK_ALPHABET_CHAIN)
                .add("--rc_source=/somewhere/.blazerc")
                .add("--config=alpha")
                .add("--config=gamma")
                .build()

        val unused: DetailedExitCode? =
            optionHandler.parseOptions(args, eventHandler, com.google.common.collect.ImmutableList.builder<E?>())
        Truth.assertThat(parser.getResidue()).isEmpty()

        // Expect the second --config=gamma to have started a second chain, and get warnings about both.
        val options: com.google.devtools.common.options.TestOptions? =
            parser.getOptions<O?>(com.google.devtools.common.options.TestOptions::class.java)
        Truth.assertThat(options).isNotNull()
        Truth.assertThat(options.getTestMultipleString())
            .containsExactly(
                "alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta", "iota", "kappa",
                "lambda", "mu", "gamma", "delta", "epsilon", "zeta", "eta", "theta", "iota", "kappa",
                "lambda", "mu"
            )
            .inOrder()
        Truth.assertThat(eventHandler.getEvents())
            .containsExactly(
                com.google.devtools.build.lib.events.Event.warn(
                    ("There is a recursive chain of configs 12 configs long: [alpha, beta, gamma, "
                            + "delta, epsilon, zeta, eta, theta, iota, kappa, lambda, mu]. This seems "
                            + "excessive, and might be hiding errors.")
                ),
                com.google.devtools.build.lib.events.Event.warn(
                    ("There is a recursive chain of configs 10 configs long: [gamma, delta, epsilon, "
                            + "zeta, eta, theta, iota, kappa, lambda, mu]. This seems excessive, "
                            + "and might be hiding errors.")
                ),
                com.google.devtools.build.lib.events.Event.warn(
                    ("The following configs were expanded more than once: [gamma, delta, epsilon, zeta, "
                            + "eta, theta, iota, kappa, lambda, mu]. For repeatable flags, repeats are "
                            + "counted twice and may lead to unexpected behavior.")
                )
            )
    }

    @org.junit.Test
    fun testWarningFlag() {
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(
                com.google.common.collect.ImmutableList.of<E?>(
                    "build",
                    "--unconditional_warning",
                    "You are forcing this warning to print for no apparent reason"
                ),
                eventHandler,
                com.google.common.collect.ImmutableList.builder<E?>()
            )
        Truth.assertThat(eventHandler.getEvents())
            .containsExactly(
                com.google.devtools.build.lib.events.Event.warn("You are forcing this warning to print for no apparent reason")
            )
        Truth.assertThat(parser.getResidue()).isEmpty()
        assertThat(optionHandler.rcfileNotes).isEmpty()
    }

    @org.junit.Test
    fun testWarningFlag_byConfig_notTriggered() {
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(
                com.google.common.collect.ImmutableList.of<E?>(
                    "build",
                    "--default_override=0:build:conf=--unconditional_warning="
                            + "config \"conf\" is deprecated, please stop using!",
                    "--rc_source=/somewhere/.blazerc"
                ),
                eventHandler,
                com.google.common.collect.ImmutableList.builder<E?>()
            )
        Truth.assertThat(eventHandler.getEvents()).isEmpty()
        Truth.assertThat(parser.getResidue()).isEmpty()
        assertThat(optionHandler.rcfileNotes).isEmpty()
    }

    @org.junit.Test
    fun testWarningFlag_byConfig_triggered() {
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(
                com.google.common.collect.ImmutableList.of<E?>(
                    "build",
                    "--config=conf",
                    "--default_override=0:build:conf=--unconditional_warning="
                            + "config \"conf\" is deprecated, please stop using!",
                    "--rc_source=/somewhere/.blazerc"
                ),
                eventHandler,
                com.google.common.collect.ImmutableList.builder<E?>()
            )
        Truth.assertThat(eventHandler.getEvents())
            .containsExactly(com.google.devtools.build.lib.events.Event.warn("config \"conf\" is deprecated, please stop using!"))
        Truth.assertThat(parser.getResidue()).isEmpty()
        assertThat(optionHandler.rcfileNotes)
            .containsExactly(
                "Found applicable config definition build:conf in file /somewhere/.blazerc: "
                        + "--unconditional_warning=config \"conf\" is deprecated, please stop using!"
            )
    }

    @org.junit.Test
    fun testConfigAfterExplicit() {
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(
                com.google.common.collect.ImmutableList.of<E?>(
                    "build",
                    "--test_string=explicitValue",
                    "--config=conf",
                    "--default_override=0:build:conf=--test_string=fromConf",
                    "--rc_source=/somewhere/.blazerc"
                ),
                eventHandler,
                com.google.common.collect.ImmutableList.builder<E?>()
            )
        val parseResult: com.google.devtools.common.options.TestOptions? =
            parser.getOptions<O?>(com.google.devtools.common.options.TestOptions::class.java)
        // In the in-place expansion, the config's expansion has precedence, but issues a warning since
        // users might not know that their explicit value was overridden.
        Truth.assertThat(eventHandler.getEvents())
            .containsExactly(
                com.google.devtools.build.lib.events.Event.warn(
                    ("option '--config=conf' (source command line options) was expanded and now "
                            + "overrides the explicit option --test_string=explicitValue with "
                            + "--test_string=fromConf")
                )
            )
        Truth.assertThat(parseResult.getTestString()).isEqualTo("fromConf")
        assertThat(optionHandler.rcfileNotes)
            .containsExactly(
                "Found applicable config definition build:conf in file /somewhere/.blazerc: "
                        + "--test_string=fromConf"
            )
    }

    @org.junit.Test
    fun testExplicitOverridesConfig() {
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(
                com.google.common.collect.ImmutableList.of<E?>(
                    "build",
                    "--config=conf",
                    "--test_string=explicitValue",
                    "--default_override=0:build:conf=--test_string=fromConf",
                    "--rc_source=/somewhere/.blazerc"
                ),
                eventHandler,
                com.google.common.collect.ImmutableList.builder<E?>()
            )
        val parseResult: com.google.devtools.common.options.TestOptions? =
            parser.getOptions<O?>(com.google.devtools.common.options.TestOptions::class.java)
        Truth.assertThat(eventHandler.getEvents()).isEmpty()
        Truth.assertThat(parseResult.getTestString()).isEqualTo("explicitValue")
        assertThat(optionHandler.rcfileNotes)
            .containsExactly(
                "Found applicable config definition build:conf in file /somewhere/.blazerc: "
                        + "--test_string=fromConf"
            )
    }

    companion object {
        private fun structuredArgsFrom2SimpleRcsWithOnlyResidue(): com.google.common.collect.ListMultimap<String?, RcChunkOfArgs?> {
            val structuredArgs: com.google.common.collect.ListMultimap<String?, RcChunkOfArgs?> =
                com.google.common.collect.ArrayListMultimap.create<String?, RcChunkOfArgs?>()
            // first add all lines of rc1, then rc2, to simulate a simple, import free, 2 rc file setup.
            structuredArgs.put("build", RcChunkOfArgs("rc1", com.google.common.collect.ImmutableList.of<E?>("a")))
            structuredArgs.put(
                "build:config",
                RcChunkOfArgs("rc1", com.google.common.collect.ImmutableList.of<E?>("b"))
            )

            structuredArgs.put("common", RcChunkOfArgs("rc2", com.google.common.collect.ImmutableList.of<E?>("c")))
            structuredArgs.put("build", RcChunkOfArgs("rc2", com.google.common.collect.ImmutableList.of<E?>("d", "e")))
            structuredArgs.put(
                "c1:other",
                RcChunkOfArgs("rc2", com.google.common.collect.ImmutableList.of<E?>("f", "g"))
            )
            return structuredArgs
        }

        private fun structuredArgsFrom2SimpleRcsWithFlags(): com.google.common.collect.ListMultimap<String?, RcChunkOfArgs?> {
            val structuredArgs: com.google.common.collect.ListMultimap<String?, RcChunkOfArgs?> =
                com.google.common.collect.ArrayListMultimap.create<String?, RcChunkOfArgs?>()
            structuredArgs.put(
                "build",
                RcChunkOfArgs("rc1", com.google.common.collect.ImmutableList.of<E?>("--test_multiple_string=foo"))
            )
            structuredArgs.put(
                "build:config",
                RcChunkOfArgs("rc1", com.google.common.collect.ImmutableList.of<E?>("--test_multiple_string=config"))
            )

            structuredArgs.put(
                "common",
                RcChunkOfArgs("rc2", com.google.common.collect.ImmutableList.of<E?>("--test_multiple_string=common"))
            )
            structuredArgs.put(
                "build",
                RcChunkOfArgs("rc2", com.google.common.collect.ImmutableList.of<E?>("--test_multiple_string=bar"))
            )
            structuredArgs.put(
                "c1:other",
                RcChunkOfArgs("rc2", com.google.common.collect.ImmutableList.of<E?>("--test_multiple_string=other"))
            )
            return structuredArgs
        }

        private fun structuredArgsFromImportedRcsWithOnlyResidue(): com.google.common.collect.ListMultimap<String?, RcChunkOfArgs?> {
            val structuredArgs: com.google.common.collect.ListMultimap<String?, RcChunkOfArgs?> =
                com.google.common.collect.ArrayListMultimap.create<String?, RcChunkOfArgs?>()
            // first add all lines of rc1, then rc2, but then jump back to 1 as if rc2 was loaded in an
            // import statement halfway through rc1.
            structuredArgs.put("build", RcChunkOfArgs("rc1", com.google.common.collect.ImmutableList.of<E?>("a")))
            structuredArgs.put(
                "build:config",
                RcChunkOfArgs("rc1", com.google.common.collect.ImmutableList.of<E?>("b"))
            )

            structuredArgs.put("common", RcChunkOfArgs("rc2", com.google.common.collect.ImmutableList.of<E?>("c")))
            structuredArgs.put("build", RcChunkOfArgs("rc2", com.google.common.collect.ImmutableList.of<E?>("d", "e")))
            structuredArgs.put(
                "c1:other",
                RcChunkOfArgs("rc2", com.google.common.collect.ImmutableList.of<E?>("f", "g"))
            )

            structuredArgs.put("build", RcChunkOfArgs("rc1", com.google.common.collect.ImmutableList.of<E?>("h")))
            return structuredArgs
        }

        private fun structuredArgsForDifferentPlatforms(): com.google.common.collect.ListMultimap<String?, RcChunkOfArgs?> {
            val structuredArgs: com.google.common.collect.ListMultimap<String?, RcChunkOfArgs?> =
                com.google.common.collect.ArrayListMultimap.create<String?, RcChunkOfArgs?>()
            structuredArgs.put(
                "build:linux",
                RcChunkOfArgs("rc1", com.google.common.collect.ImmutableList.of<E?>("command_linux"))
            )
            structuredArgs.put(
                "build:windows", RcChunkOfArgs("rc1", com.google.common.collect.ImmutableList.of<E?>("command_windows"))
            )
            structuredArgs.put(
                "build:macos",
                RcChunkOfArgs("rc1", com.google.common.collect.ImmutableList.of<E?>("command_macos"))
            )
            structuredArgs.put(
                "build:freebsd", RcChunkOfArgs("rc1", com.google.common.collect.ImmutableList.of<E?>("command_freebsd"))
            )
            structuredArgs.put(
                "build:openbsd", RcChunkOfArgs("rc1", com.google.common.collect.ImmutableList.of<E?>("command_openbsd"))
            )
            structuredArgs.put(
                "build:platform_config",
                RcChunkOfArgs(
                    "rc1",
                    com.google.common.collect.ImmutableList.of<E?>("--enable_platform_specific_config")
                )
            )
            return structuredArgs
        }

        private val GREEK_ALPHABET_CHAIN: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "--default_override=0:build:alpha=--test_multiple_string=alpha",
                "--default_override=0:build:alpha=--config=beta",
                "--default_override=0:build:beta=--test_multiple_string=beta",
                "--default_override=0:build:beta=--config=gamma",
                "--default_override=0:build:gamma=--test_multiple_string=gamma",
                "--default_override=0:build:gamma=--config=delta",
                "--default_override=0:build:delta=--test_multiple_string=delta",
                "--default_override=0:build:delta=--config=epsilon",
                "--default_override=0:build:epsilon=--test_multiple_string=epsilon",
                "--default_override=0:build:epsilon=--config=zeta",
                "--default_override=0:build:zeta=--test_multiple_string=zeta",
                "--default_override=0:build:zeta=--config=eta",
                "--default_override=0:build:eta=--test_multiple_string=eta",
                "--default_override=0:build:eta=--config=theta",
                "--default_override=0:build:theta=--test_multiple_string=theta",
                "--default_override=0:build:theta=--config=iota",
                "--default_override=0:build:iota=--test_multiple_string=iota",
                "--default_override=0:build:iota=--config=kappa",
                "--default_override=0:build:kappa=--test_multiple_string=kappa",
                "--default_override=0:build:kappa=--config=lambda",
                "--default_override=0:build:lambda=--test_multiple_string=lambda",
                "--default_override=0:build:lambda=--config=mu",
                "--default_override=0:build:mu=--test_multiple_string=mu"
            )
    }
}
