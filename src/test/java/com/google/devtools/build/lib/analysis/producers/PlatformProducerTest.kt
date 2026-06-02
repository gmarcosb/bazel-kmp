// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.producers

import com.google.common.collect.ImmutableMap
import com.google.devtools.build.lib.analysis.platform.PlatformValue
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable

/**
 * Tests of [PlatformProducer].
 * 
 * 
 * Implicitly provides test coverage for [ ].
 */
@RunWith(JUnit4::class)
class PlatformProducerTest : ProducerTestCase() {
    @Test
    @Throws(Exception::class)
    fun basicLookup() {
        scratch.overwriteFile(
            "lookup/BUILD",
            """
        constraint_setting(name = "setting1")

        constraint_value(
            name = "value1",
            constraint_setting = ":setting1",
        )

        platform(
            name = "basic",
            constraint_values = [":value1"],
            flags = ["--cpu=fast"],
        )
        
        """.trimIndent()
        )

        val platformLabel: Label? = Label.parseCanonicalUnchecked("//lookup:basic")
        val result: PlatformValue = fetch(platformLabel,  /* flagAliasMappings= */ImmutableMap.of<String?, Label?>())

        assertThat(result).isNotNull()
        assertThat(result.platformInfo().label()).isEqualTo(platformLabel)
        assertThat(result.parsedFlags().get().parsingResult().canonicalize())
            .containsExactly("--cpu=fast")
    }

    @Test
    @Throws(Exception::class)
    fun alias() {
        scratch.overwriteFile(
            "lookup/BUILD",
            """
        constraint_setting(name = "setting1")

        constraint_value(
            name = "value1",
            constraint_setting = ":setting1",
        )

        platform(
            name = "basic",
            constraint_values = [":value1"],
            flags = ["--cpu=fast"],
        )

        alias(
            name = "alias",
            actual = ":basic",
        )
        
        """.trimIndent()
        )

        val platformLabel: Label? = Label.parseCanonicalUnchecked("//lookup:alias")
        val result: PlatformValue = fetch(platformLabel,  /* flagAliasMappings= */ImmutableMap.of<String?, Label?>())

        assertThat(result).isNotNull()
        assertThat(result.platformInfo().label())
            .isEqualTo(Label.parseCanonicalUnchecked("//lookup:basic"))
        assertThat(result.parsedFlags().get().parsingResult().canonicalize())
            .containsExactly("--cpu=fast")
    }

    @Test
    @Throws(Exception::class)
    fun invalidPlatformError() {
        scratch.overwriteFile(
            "lookup/BUILD",
            """
        filegroup(
            name = "basic",
        )
        
        """.trimIndent()
        )

        val platformLabel: Label? = Label.parseCanonicalUnchecked("//lookup:basic")
        Assert.assertThrows<T?>(
            InvalidPlatformException::class.java,
            ThrowingRunnable { fetch(platformLabel,  /* flagAliasMappings= */ImmutableMap.of<String?, Label?>()) })
    }

    @Test
    @Throws(Exception::class)
    fun optionsParsingError() {
        scratch.overwriteFile(
            "lookup/BUILD",
            """
        platform(
            name = "basic",
            flags = ["--//starlark:flag=does_not_exist"],
        )
        
        """.trimIndent()
        )

        val platformLabel: Label? = Label.parseCanonicalUnchecked("//lookup:basic")
        Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            ThrowingRunnable { fetch(platformLabel,  /* flagAliasMappings= */ImmutableMap.of<String?, Label?>()) })
    }

    @Test
    @Throws(Exception::class)
    fun flagAliasUsesCanonicalFlag() {
        scratch.overwriteFile(
            "starlark/flags.bzl",
            """
        string_flag = rule(implementation = lambda ctx: [], build_setting = config.string(flag = True))
        bool_flag = rule(implementation = lambda ctx: [], build_setting = config.bool(flag = True))
        
        """.trimIndent()
        )
        scratch.overwriteFile(
            "starlark/BUILD",
            """
        load("//starlark:flags.bzl", "string_flag", "bool_flag")
        string_flag(
            name = "actual1",
            build_setting_default = "value1",
        )
        bool_flag(
            name = "actual2",
            build_setting_default = False,
        )
        bool_flag(
            name = "actual3",
            build_setting_default = True,
        )

        

        """.trimIndent()
        )
        scratch.overwriteFile(
            "lookup/BUILD",
            """
        constraint_setting(name = "setting1")
        constraint_value(
            name = "value1",
            constraint_setting = ":setting1",
        )
        platform(
            name = "basic",
            constraint_values = [":value1"],
            flags = [
                "--aliasname1=fast",
                "--aliasname2",
                "--noaliasname3",
                "--compilation_mode=opt",
            ],
        )
        
        """.trimIndent()
        )

        val result: PlatformValue =
            fetch(
                Label.parseCanonicalUnchecked("//lookup:basic"),  /* flagAliasMappings= */
                ImmutableMap.of<K?, V?>(
                    "aliasname1", Label.parseCanonicalUnchecked("//starlark:actual1"),
                    "aliasname2", Label.parseCanonicalUnchecked("//starlark:actual2"),
                    "aliasname3", Label.parseCanonicalUnchecked("//starlark:actual3")
                )
            )

        // Native flags:
        assertThat(result.parsedFlags().get().parsingResult().canonicalize())
            .containsExactly("--compilation_mode=opt")
        // Starlark flags:
        assertThat(result.parsedFlags().get().parsingResult().getStarlarkOptions())
            .containsExactly(
                "//starlark:actual1", "fast", "//starlark:actual2", true, "//starlark:actual3", false
            )
    }

    @Throws(InvalidPlatformException::class, OptionsParsingException::class, InterruptedException::class)
    private fun fetch(platformLabel: Label?, flagAliasMappings: ImmutableMap<String?, Label?>?): PlatformValue {
        val sink = PlatformInfoSink()
        val producer: PlatformProducer =
            PlatformProducer(platformLabel, flagAliasMappings, sink, StateMachine.DONE)
        val success = executeProducer(producer)
        if (sink.platformValue != null) {
            Truth.assertThat(success).isTrue()
            return sink.platformValue
        } else {
            Truth.assertThat(success).isFalse() // Error comes from a Skyframe dep.
            if (sink.platformInfoError != null) {
                throw sink.platformInfoError
            } else {
                throw sink.optionsParsingError
            }
        }
    }

    /** Receiver for platform info from [PlatformProducer].  */
    private class PlatformInfoSink : PlatformProducer.ResultSink {
        private var platformValue: PlatformValue? = null
        private var platformInfoError: InvalidPlatformException? = null
        private var optionsParsingError: OptionsParsingException? = null

        public override fun acceptPlatformValue(value: PlatformValue?) {
            this.platformValue = value
        }

        public override fun acceptPlatformInfoError(error: InvalidPlatformException?) {
            this.platformInfoError = error
        }

        public override fun acceptOptionsParsingError(error: OptionsParsingException?) {
            this.optionsParsingError = error
        }
    }
}
