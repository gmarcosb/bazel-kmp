// Copyright 2020 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.runtime.BlazeOptionHandler.BAD_OPTION_TAG

/** Tests --flag_alias functionality in [BlazeOptionHandler].  */
@RunWith(JUnit4::class)
class FlagAliasTest {
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
                ClientOptions::class.java,
                CoreOptions::class.java
            )

        val helper: BlazeOptionHandlerTestHelper =
            BlazeOptionHandlerTestHelper(
                optionsClasses,  /* allowResidue= */
                true,  /* aliasFlag= */
                CoreOptionConverters.BLAZE_ALIASING_FLAG,  /* skipStarlarkPrefixes= */
                true
            )
        eventHandler = helper.getEventHandler()
        parser = helper.getOptionsParser()
        optionHandler = helper.getOptionHandler()
    }

    @org.junit.Test
    fun useAliasWithNonStarlarkFlag() {
        val args: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "build",
                "--rc_source=/somewhere/.blazerc",
                "--flag_alias=foo=bar"
            )
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(args, eventHandler, com.google.common.collect.ImmutableList.builder<E?>())
        Truth.assertThat(eventHandler.getEvents())
            .contains(
                com.google.devtools.build.lib.events.Event.error(
                    "While parsing option --flag_alias=foo=bar: --flag_alias only supports Starlark"
                            + " build settings."
                )
            )
    }

    @org.junit.Test
    fun useAliasWithValueAssignment() {
        val args: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "build",
                "--rc_source=/somewhere/.blazerc",
                "--flag_alias=foo=//bar=7"
            )
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(args, eventHandler, com.google.common.collect.ImmutableList.builder<E?>())
        Truth.assertThat(eventHandler.getEvents())
            .contains(
                com.google.devtools.build.lib.events.Event.error(
                    "While parsing option --flag_alias=foo=//bar=7: --flag_alias does not support flag"
                            + " value assignment."
                )
            )
    }

    @org.junit.Test
    fun useAliasWithInvalidName() {
        val args: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "build",
                "--rc_source=/somewhere/.blazerc",
                "--flag_alias=bad\$foo=//bar"
            )
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(args, eventHandler, com.google.common.collect.ImmutableList.builder<E?>())
        Truth.assertThat(eventHandler.getEvents())
            .contains(
                com.google.devtools.build.lib.events.Event.error(
                    "While parsing option --flag_alias=bad\$foo=//bar: bad\$foo should only consist of"
                            + " word characters to be a valid alias name."
                )
            )
    }

    @org.junit.Test
    fun useAliasWithoutEqualsInValue() {
        val args: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "build",
                "--rc_source=/somewhere/.blazerc",
                "--flag_alias=foo"
            )
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(args, eventHandler, com.google.common.collect.ImmutableList.builder<E?>())
        Truth.assertThat(eventHandler.getEvents())
            .contains(
                com.google.devtools.build.lib.events.Event.error(
                    "While parsing option --flag_alias=foo: Flag alias definitions must be in"
                            + " the form of a 'name=label' assignment"
                )
            )
    }

    @org.junit.Test
    fun useAliasWithoutEqualsInArg() {
        val args: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "build",
                "--rc_source=/somewhere/.blazerc",
                "--flag_alias",
                "foo=//bar"
            )
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(args, eventHandler, com.google.common.collect.ImmutableList.builder<E?>())
        Truth.assertThat(eventHandler.hasErrors()).isFalse()
    }

    @org.junit.Test
    fun useAliasWithBooleanSyntax() {
        val args: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "build", "--rc_source=/somewhere/.blazerc", "--flag_alias=foo=//bar", "--foo"
            )
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(args, eventHandler, com.google.common.collect.ImmutableList.builder<E?>())
        Truth.assertThat(parser.getSkippedArgs()).contains("--//bar")
    }

    @org.junit.Test
    fun useAliasWithNoBooleanSyntax() {
        val args: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "build", "--rc_source=/somewhere/.blazerc", "--flag_alias=foo=//bar", "--nofoo"
            )
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(args, eventHandler, com.google.common.collect.ImmutableList.builder<E?>())
        Truth.assertThat(eventHandler.getEvents())
            .contains(
                com.google.devtools.build.lib.events.Event.error("--nofoo :: Unrecognized option: --nofoo")
                    .withTag(BAD_OPTION_TAG)
            )
    }

    @org.junit.Test
    fun lastRepeatMappingTakesPrecedence() {
        val args: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "build",
                "--rc_source=/somewhere/.blazerc",
                "--flag_alias=foo=//bar",
                "--foo",
                "--flag_alias=foo=//baz",
                "--foo"
            )
        val expectedSkippedArgs: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("--//bar", "--//baz")
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(args, eventHandler, com.google.common.collect.ImmutableList.builder<E?>())
        Truth.assertThat(parser.getSkippedArgs()).isEqualTo(expectedSkippedArgs)
    }

    @org.junit.Test
    fun setAliasInRcFile_useInRcFile() {
        val args: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "build",
                "--default_override=0:build=--flag_alias=foo=//bar",
                "--default_override=0:build=--foo",
                "--rc_source=/somewhere/.blazerc"
            )
        val expectedSkippedArgs: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("--//bar")
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(args, eventHandler, com.google.common.collect.ImmutableList.builder<E?>())
        Truth.assertThat(parser.getSkippedArgs()).isEqualTo(expectedSkippedArgs)
    }

    @org.junit.Test
    fun setAliasInRcFile_useOnCommandLine() {
        val args: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "build",
                "--default_override=0:build=--flag_alias=foo=//bar",
                "--rc_source=/somewhere/.blazerc",
                "--foo"
            )
        val expectedSkippedArgs: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("--//bar")
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(args, eventHandler, com.google.common.collect.ImmutableList.builder<E?>())
        Truth.assertThat(parser.getSkippedArgs()).isEqualTo(expectedSkippedArgs)
    }

    @org.junit.Test
    fun setAliasOnCommandLine_useOnCommandLine() {
        val args: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "build", "--rc_source=/somewhere/.blazerc", "--flag_alias=foo=//bar", "--foo=7"
            )
        val expectedSkippedArgs: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("--//bar=7")
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(args, eventHandler, com.google.common.collect.ImmutableList.builder<E?>())
        Truth.assertThat(parser.getSkippedArgs()).isEqualTo(expectedSkippedArgs)
    }

    // Regression test for b/172453517
    @org.junit.Test
    fun aliasLogicSkipsNonDoubleDashArgs() {
        val args: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("build", "--rc_source=/somewhere/.blazerc", "-=")
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(args, eventHandler, com.google.common.collect.ImmutableList.builder<E?>())
        Truth.assertThat(eventHandler.getEvents())
            .contains(
                com.google.devtools.build.lib.events.Event.error("-= :: Unrecognized option: -=")
                    .withTag(BAD_OPTION_TAG)
            )
    }

    @org.junit.Test
    fun setAliasOnCommandLine_useInRcFile() {
        val args: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "build",
                "--default_override=0:build=--foo=7",
                "--rc_source=/somewhere/.blazerc",
                "--flag_alias=foo=//bar"
            )
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(args, eventHandler, com.google.common.collect.ImmutableList.builder<E?>())
        Truth.assertThat(eventHandler.getEvents())
            .contains(
                com.google.devtools.build.lib.events.Event.error("--foo=7 :: Unrecognized option: --foo=7")
                    .withTag(BAD_OPTION_TAG)
            )
    }

    @org.junit.Test
    fun useAliasBeforeSettingOnCommandLine() {
        val args: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "build", "--rc_source=/somewhere/.blazerc", "--foo=7", "--flag_alias=foo=//bar"
            )
        val unused: DetailedExitCode? =
            optionHandler.parseOptions(args, eventHandler, com.google.common.collect.ImmutableList.builder<E?>())
        Truth.assertThat(eventHandler.getEvents())
            .contains(
                com.google.devtools.build.lib.events.Event.error("--foo=7 :: Unrecognized option: --foo=7")
                    .withTag(BAD_OPTION_TAG)
            )
    }
}
