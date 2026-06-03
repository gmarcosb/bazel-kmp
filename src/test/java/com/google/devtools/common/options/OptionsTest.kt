// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.common.options

import Converter.Contextless
import OptionFilters.OptionEffectTag
import com.google.common.testing.EqualsTester
import com.google.common.truth.Truth
import com.google.devtools.common.options.Converter.Contextless
import com.google.devtools.common.options.OptionDocumentationCategory
import com.google.devtools.common.options.OptionEffectTag
import com.google.devtools.common.options.OptionsBase
import com.google.devtools.common.options.OptionsClass
import com.google.devtools.common.options.OptionsParsingException
import com.google.devtools.common.options.OptionsTest
import com.google.devtools.common.options.OptionsUsage
import com.google.devtools.common.options.testing.ConverterTester.addEqualityGroup
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.net.MalformedURLException

/**
 * Test for [Options].
 */
@RunWith(JUnit4::class)
class OptionsTest {
    @OptionsClass
    abstract class HttpOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "host",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "www.google.com",
            help = "The URL at which the server will be running."
        )
        abstract val host: String?

        @get:com.google.devtools.common.options.Option(
            name = "port",
            abbrev = 'p',
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "80",
            help = "The port at which the server will be running."
        )
        abstract val port: Int

        @get:com.google.devtools.common.options.Option(
            name = "debug",
            abbrev = 'd',
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false",
            help = "debug"
        )
        abstract val isDebugging: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "tristate",
            abbrev = 't',
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "auto",
            help = "tri-state option returning auto by default"
        )
        abstract val triState: com.google.devtools.common.options.TriState?

        @get:com.google.devtools.common.options.Option(
            name = "special",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null",
            expansion = ["--host=special.google.com", "--port=8080"]
        )
        abstract val special: java.lang.Void?
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun paragraphFill() {
        // TODO(bazel-team): don't include trailing space after last word in line.
        val input = "The quick brown fox jumps over the lazy dog."

        Truth.assertThat(OptionsUsage.paragraphFill(input, 2, 13))
            .isEqualTo("  The quick \n  brown fox \n  jumps over \n  the lazy \n" + "  dog.")
        Truth.assertThat(OptionsUsage.paragraphFill(input, 3, 19))
            .isEqualTo("   The quick brown \n   fox jumps over \n   the lazy dog.")

        val input2 = "The quick brown fox jumps\nAnother paragraph."
        Truth.assertThat(OptionsUsage.paragraphFill(input2, 2, 23))
            .isEqualTo("  The quick brown fox \n  jumps\n  Another paragraph.")
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun getsDefaults() {
        val options: com.google.devtools.common.options.Options<HttpOptions?> =
            com.google.devtools.common.options.Options.parse<HttpOptions?>(HttpOptions::class.java, *NO_ARGS)
        val remainingArgs: Array<String?>? = options.remainingArgs
        val webFlags: HttpOptions = options.options

        Truth.assertThat(webFlags.host).isEqualTo("www.google.com")
        Truth.assertThat(webFlags.port).isEqualTo(80)
        Truth.assertThat(webFlags.isDebugging).isFalse()
        Truth.assertThat<com.google.devtools.common.options.TriState?>(webFlags.triState)
            .isEqualTo(com.google.devtools.common.options.TriState.AUTO)
        Truth.assertThat<String?>(remainingArgs).hasLength(0)
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun objectMethods() {
        val args = arrayOf<String?>("--host", "foo", "--port", "80")
        val left: HttpOptions =
            com.google.devtools.common.options.Options.parse<HttpOptions?>(HttpOptions::class.java, *args).options
        val likeLeft: HttpOptions =
            com.google.devtools.common.options.Options.parse<HttpOptions?>(HttpOptions::class.java, *args).options
        val rightArgs = arrayOf<String?>("--host", "other", "--port", "90")
        val right: HttpOptions =
            com.google.devtools.common.options.Options.parse<HttpOptions?>(HttpOptions::class.java, *rightArgs).options

        val toString: String? = left.toString()
        // Don't rely on Set.toString iteration order:
        Truth.assertThat(toString)
            .startsWith("com.google.devtools.common.options.OptionsTest" + "\$HttpOptions{")
        Truth.assertThat(toString).contains("host=foo")
        Truth.assertThat(toString).contains("port=80")
        Truth.assertThat(toString).endsWith("}")

        EqualsTester().addEqualityGroup(left).testEquals()
        Truth.assertThat(left.toString()).isEqualTo(likeLeft.toString())
        Truth.assertThat(left).isEqualTo(likeLeft)
        Truth.assertThat(likeLeft).isEqualTo(left)
        Truth.assertThat(left).isNotEqualTo(right)
        Truth.assertThat(right).isNotEqualTo(left)
        Truth.assertThat(left).isNotNull()
        Truth.assertThat(likeLeft).isNotNull()
        Truth.assertThat(likeLeft.hashCode()).isEqualTo(likeLeft.hashCode())
        Truth.assertThat(likeLeft.hashCode()).isEqualTo(left.hashCode())
        // Strictly speaking this is not required for hashCode to be correct,
        // but a good hashCode should be different at least for some values. So,
        // we're making sure that at least this particular pair of inputs yields
        // different values.
        Truth.assertThat(left.hashCode()).isNotEqualTo(right.hashCode())
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun equals() {
        val args = arrayOf<String?>("--host", "foo", "--port", "80")
        val options1: HttpOptions? =
            com.google.devtools.common.options.Options.parse<HttpOptions?>(HttpOptions::class.java, *args).options

        val args2 = arrayOf<String?>("-p", "80", "--host", "foo")
        val options2: HttpOptions? =
            com.google.devtools.common.options.Options.parse<HttpOptions?>(HttpOptions::class.java, *args2).options
        // Order/abbreviations shouldn't matter.
        Truth.assertThat(options1).isEqualTo(options2)

        // Explicitly setting a default shouldn't matter.
        assertThat(
            com.google.devtools.common.options.Options.parse<HttpOptions?>(
                HttpOptions::class.java,
                "--port",
                "80"
            ).options
        )
            .isEqualTo(com.google.devtools.common.options.Options.parse<HttpOptions?>(HttpOptions::class.java).options)

        assertThat(
            com.google.devtools.common.options.Options.parse<HttpOptions?>(
                HttpOptions::class.java,
                "--port",
                "3"
            ).options
        )
            .isNotEqualTo(com.google.devtools.common.options.Options.parse<HttpOptions?>(HttpOptions::class.java).options)
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun getsFlagsProvidedInArguments() {
        val args = arrayOf<String?>(
            "--host", "google.com",
            "-p", "8080",  // short form
            "--debug"
        )
        val options: com.google.devtools.common.options.Options<HttpOptions?> =
            com.google.devtools.common.options.Options.parse<HttpOptions?>(HttpOptions::class.java, *args)
        val remainingArgs: Array<String?>? = options.remainingArgs
        val webFlags: HttpOptions = options.options

        Truth.assertThat(webFlags.host).isEqualTo("google.com")
        Truth.assertThat(webFlags.port).isEqualTo(8080)
        Truth.assertThat(webFlags.isDebugging).isTrue()
        Truth.assertThat<String?>(remainingArgs).hasLength(0)
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun getsFlagsProvidedWithEquals() {
        val args = arrayOf<String?>(
            "--host=google.com",
            "--port=8080",
            "--debug"
        )
        val options: com.google.devtools.common.options.Options<HttpOptions?> =
            com.google.devtools.common.options.Options.parse<HttpOptions?>(HttpOptions::class.java, *args)
        val remainingArgs: Array<String?>? = options.remainingArgs
        val webFlags: HttpOptions = options.options

        Truth.assertThat(webFlags.host).isEqualTo("google.com")
        Truth.assertThat(webFlags.port).isEqualTo(8080)
        Truth.assertThat(webFlags.isDebugging).isTrue()
        Truth.assertThat<String?>(remainingArgs).hasLength(0)
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun booleanNo() {
        val options: com.google.devtools.common.options.Options<HttpOptions?> =
            com.google.devtools.common.options.Options.parse<HttpOptions?>(
                HttpOptions::class.java,
                *arrayOf<String>("--nodebug", "--notristate")
            )
        val webFlags: HttpOptions = options.options
        Truth.assertThat(webFlags.isDebugging).isFalse()
        Truth.assertThat<com.google.devtools.common.options.TriState?>(webFlags.triState)
            .isEqualTo(com.google.devtools.common.options.TriState.NO)
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun booleanAbbrevMinus() {
        val options: com.google.devtools.common.options.Options<HttpOptions?> =
            com.google.devtools.common.options.Options.parse<HttpOptions?>(
                HttpOptions::class.java,
                *arrayOf<String>("-d-", "-t-")
            )
        val webFlags: HttpOptions = options.options
        Truth.assertThat(webFlags.isDebugging).isFalse()
        Truth.assertThat<com.google.devtools.common.options.TriState?>(webFlags.triState)
            .isEqualTo(com.google.devtools.common.options.TriState.NO)
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun boolean0() {
        val options: com.google.devtools.common.options.Options<HttpOptions?> =
            com.google.devtools.common.options.Options.parse<HttpOptions?>(
                HttpOptions::class.java,
                *arrayOf<String>("--debug=0", "--tristate=0")
            )
        val webFlags: HttpOptions = options.options
        Truth.assertThat(webFlags.isDebugging).isFalse()
        Truth.assertThat<com.google.devtools.common.options.TriState?>(webFlags.triState)
            .isEqualTo(com.google.devtools.common.options.TriState.NO)
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun boolean1() {
        val options: com.google.devtools.common.options.Options<HttpOptions?> =
            com.google.devtools.common.options.Options.parse<HttpOptions?>(
                HttpOptions::class.java,
                *arrayOf<String>("--debug=1", "--tristate=1")
            )
        val webFlags: HttpOptions = options.options
        Truth.assertThat(webFlags.isDebugging).isTrue()
        Truth.assertThat<com.google.devtools.common.options.TriState?>(webFlags.triState)
            .isEqualTo(com.google.devtools.common.options.TriState.YES)
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun retainsStuffThatsNotOptions() {
        val args = arrayOf<String?>("these", "aint", "options")
        val options: com.google.devtools.common.options.Options<HttpOptions?> =
            com.google.devtools.common.options.Options.parse<HttpOptions?>(HttpOptions::class.java, *args)
        val remainingArgs: Array<String?> = options.remainingArgs
        Truth.assertThat(java.util.Arrays.asList<String?>(*remainingArgs))
            .isEqualTo(java.util.Arrays.asList<String?>(*args))
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun retainsStuffThatsNotComplexOptions() {
        val args = arrayOf<String?>(
            "--host", "google.com",
            "notta",
            "--port=8080",
            "option",
            "--debug=true"
        )
        val notoptions = arrayOf<String?>("notta", "option")
        val options: com.google.devtools.common.options.Options<HttpOptions?> =
            com.google.devtools.common.options.Options.parse<HttpOptions?>(HttpOptions::class.java, *args)
        val remainingArgs: Array<String?> = options.remainingArgs
        Truth.assertThat(java.util.Arrays.asList<String?>(*remainingArgs))
            .isEqualTo(java.util.Arrays.asList<String?>(*notoptions))
    }

    @org.junit.Test
    fun wontParseUnknownOptions() {
        val args = arrayOf<String?>("--unknown", "--other=23", "--options")
        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    com.google.devtools.common.options.Options.parse<HttpOptions?>(
                        HttpOptions::class.java,
                        *args
                    )
                })
        Truth.assertThat(e).hasMessageThat().isEqualTo("Unrecognized option: --unknown")
    }

    @org.junit.Test
    fun requiresOptionValue() {
        val args = arrayOf<String?>("--port")
        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    com.google.devtools.common.options.Options.parse<HttpOptions?>(
                        HttpOptions::class.java,
                        *args
                    )
                })
        Truth.assertThat(e).hasMessageThat().isEqualTo("Expected value after --port")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun handlesDuplicateOptions_full() {
        val args = arrayOf<String?>("--port=80", "--port", "81")
        val options: com.google.devtools.common.options.Options<HttpOptions?> =
            com.google.devtools.common.options.Options.parse<HttpOptions?>(HttpOptions::class.java, *args)
        val webFlags: HttpOptions = options.options
        Truth.assertThat(webFlags.port).isEqualTo(81)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun handlesDuplicateOptions_abbrev() {
        val args = arrayOf<String?>("--port=80", "-p", "81")
        val options: com.google.devtools.common.options.Options<HttpOptions?> =
            com.google.devtools.common.options.Options.parse<HttpOptions?>(HttpOptions::class.java, *args)
        val webFlags: HttpOptions = options.options
        Truth.assertThat(webFlags.port).isEqualTo(81)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun duplicateOptionsOkWithSameValues() {
        // These would throw OptionsParsingException if they failed.
        com.google.devtools.common.options.Options.parse<HttpOptions?>(
            HttpOptions::class.java,
            "--port=80",
            "--port",
            "80"
        )
        com.google.devtools.common.options.Options.parse<HttpOptions?>(HttpOptions::class.java, "--port=80", "-p", "80")
    }

    @get:org.junit.Test
    val isPickyAboutBooleanValues: Unit
        get() {
            val e: OptionsParsingException? =
                org.junit.Assert.assertThrows<OptionsParsingException?>(
                    OptionsParsingException::class.java,
                    org.junit.function.ThrowingRunnable {
                        com.google.devtools.common.options.Options.parse<HttpOptions?>(
                            HttpOptions::class.java,
                            *arrayOf<String>("--debug=not_a_boolean")
                        )
                    })
            Truth.assertThat(e)
                .hasMessageThat()
                .isEqualTo(
                    "While parsing option --debug=not_a_boolean: " + "\'not_a_boolean\' is not a boolean"
                )
        }

    @get:org.junit.Test
    val isPickyAboutBooleanNos: Unit
        get() {
            val e: OptionsParsingException? =
                org.junit.Assert.assertThrows<OptionsParsingException?>(
                    OptionsParsingException::class.java,
                    org.junit.function.ThrowingRunnable {
                        com.google.devtools.common.options.Options.parse<HttpOptions?>(
                            HttpOptions::class.java,
                            *arrayOf<String>("--nodebug=1")
                        )
                    })
            Truth.assertThat(e).hasMessageThat().isEqualTo("Unexpected value after boolean option: --nodebug=1")
        }

    @org.junit.Test
    fun usageForBuiltinTypesNoExpansion() {
        val usage: String? = com.google.devtools.common.options.Options.getUsage(HttpOptions::class.java)
        // We can't rely on the option ordering.
        Truth.assertThat(usage)
            .contains("  --[no]debug [-d] (a boolean; default: \"false\")\n" + "    debug")
        Truth.assertThat(usage)
            .contains(
                "  --host (a string; default: \"www.google.com\")\n"
                        + "    The URL at which the server will be running."
            )
        Truth.assertThat(usage)
            .contains(
                "  --port [-p] (an integer; default: \"80\")\n"
                        + "    The port at which the server will be running."
            )
        Truth.assertThat(usage)
            .contains(
                "  --[no]tristate [-t] (a tri-state (auto, yes, no); default: \"auto\")\n"
                        + "    tri-state option returning auto by default"
            )
    }

    @org.junit.Test
    fun usageForStaticExpansion() {
        val usage: String? = com.google.devtools.common.options.Options.getUsage(HttpOptions::class.java)
        Truth.assertThat(usage)
            .contains("  --special\n      Expands to: --host=special.google.com --port=8080")
    }

    @OptionsClass
    abstract class NullTestOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "host",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null",
            help = "The URL at which the server will be running."
        )
        abstract val host: String?

        @get:com.google.devtools.common.options.Option(
            name = "none",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null",
            expansion = ["--host=www.google.com"],
            help = "An expanded option."
        )
        abstract val none: java.lang.Void?
    }

    @org.junit.Test
    fun usageForNullDefault() {
        val usage: String? =
            com.google.devtools.common.options.Options.getUsage(com.google.devtools.common.options.OptionsTest.NullTestOptions::class.java)
        Truth.assertThat(usage)
            .contains(
                "  --host (a string; default: see description)\n"
                        + "    The URL at which the server will be running."
            )
        Truth.assertThat(usage)
            .contains(
                "  --none\n" + "    An expanded option.\n" + "      Expands to: --host=www.google.com"
            )
    }

    class MyURLConverter : Contextless<java.net.URL?>() {
        @Throws(OptionsParsingException::class)
        override fun convert(input: String): java.net.URL {
            try {
                return java.net.URL(input)
            } catch (e: MalformedURLException) {
                throw OptionsParsingException(
                    ("Could not convert '" + input + "': "
                            + e.message)
                )
            }
        }

        val typeDescription: String
            get() = "a url"
    }

    @OptionsClass
    abstract class UsesCustomConverter : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "url",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "http://www.google.com/",
            converter = MyURLConverter::class
        )
        abstract val url: java.net.URL?
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun customConverter() {
        val options: com.google.devtools.common.options.Options<UsesCustomConverter?> =
            com.google.devtools.common.options.Options.parse<UsesCustomConverter?>(
                UsesCustomConverter::class.java,
                *arrayOfNulls<String>(0)
            )
        val expected: java.net.URL = java.net.URL("http://www.google.com/")
        assertThat(options.options.getUrl()).isEqualTo(expected)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun customConverterThrowsException() {
        val args = arrayOf<String?>("--url=a_malformed:url")
        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    com.google.devtools.common.options.Options.parse<UsesCustomConverter?>(
                        UsesCustomConverter::class.java,
                        *args
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .isEqualTo(
                ("While parsing option --url=a_malformed:url: "
                        + "Could not convert 'a_malformed:url': "
                        + "no protocol: a_malformed:url")
            )
    }

    @org.junit.Test
    fun usageWithCustomConverter() {
        Truth.assertThat(com.google.devtools.common.options.Options.getUsage(UsesCustomConverter::class.java))
            .isEqualTo("  --url (a url; default: \"http://www.google.com/\")\n")
    }

    @org.junit.Test
    fun unknownBooleanOptionNegativeForm() {
        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    com.google.devtools.common.options.Options.parse<HttpOptions?>(
                        HttpOptions::class.java,
                        *arrayOf<String>("--no-debug")
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .isEqualTo("Unrecognized option: --no-debug (did you mean '--nodebug'?)")
    }

    @org.junit.Test
    fun unknownOption() {
        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    com.google.devtools.common.options.Options.parse<HttpOptions?>(
                        HttpOptions::class.java,
                        *arrayOf<String>("--pert")
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .isEqualTo("Unrecognized option: --pert (did you mean '--port'?)")
    }

    @org.junit.Test
    fun unknownBooleanOptionPositiveForm() {
        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    com.google.devtools.common.options.Options.parse<HttpOptions?>(
                        HttpOptions::class.java,
                        *arrayOf<String>("--dbg")
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .isEqualTo("Unrecognized option: --dbg (did you mean '--debug'?)")
    }

    @OptionsClass
    abstract class J : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "j",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null"
        )
        abstract val string: String?
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nullDefaultForReferenceTypeOption() {
        val options: J = com.google.devtools.common.options.Options.parse<J?>(J::class.java, *NO_ARGS).options
        Truth.assertThat(options.string).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nullIsNotInterpretedSpeciallyExceptAsADefaultValue() {
        val options: HttpOptions =
            com.google.devtools.common.options.Options.parse<HttpOptions?>(
                HttpOptions::class.java,
                *arrayOf<String>("--host", "null")
            ).options
        Truth.assertThat(options.host).isEqualTo("null")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nonDecimalRadicesForIntegerOptions() {
        val options: com.google.devtools.common.options.Options<HttpOptions?> =
            com.google.devtools.common.options.Options.parse<HttpOptions?>(
                HttpOptions::class.java,
                *arrayOf<String>("--port", "0x51")
            )
        assertThat(options.options.getPort()).isEqualTo(81)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun expansionOptionSimple() {
        val options: com.google.devtools.common.options.Options<HttpOptions?> =
            com.google.devtools.common.options.Options.parse<HttpOptions?>(
                HttpOptions::class.java,
                *arrayOf<String>("--special")
            )
        assertThat(options.options.getHost()).isEqualTo("special.google.com")
        assertThat(options.options.getPort()).isEqualTo(8080)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun expansionOptionOverride() {
        val options: com.google.devtools.common.options.Options<HttpOptions?> =
            com.google.devtools.common.options.Options.parse<HttpOptions?>(
                HttpOptions::class.java,
                *arrayOf<String>("--port=90", "--special", "--host=foo")
            )
        assertThat(options.options.getHost()).isEqualTo("foo")
        assertThat(options.options.getPort()).isEqualTo(8080)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun expansionOptionEquals() {
        val options1: com.google.devtools.common.options.Options<HttpOptions?> =
            com.google.devtools.common.options.Options.parse<HttpOptions?>(
                HttpOptions::class.java,
                *arrayOf<String>("--host=special.google.com", "--port=8080")
            )
        val options2: com.google.devtools.common.options.Options<HttpOptions?> =
            com.google.devtools.common.options.Options.parse<HttpOptions?>(
                HttpOptions::class.java,
                *arrayOf<String>("--special")
            )
        assertThat(options1.options).isEqualTo(options2.options)
    }

    /** Dummy options base class.  */
    @OptionsClass
    abstract class FooOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "foo",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false"
        )
        abstract val foo: Boolean
    }

    /** Dummy options derived class.  */
    @OptionsClass
    abstract class BazOptions : FooOptions() {
        @get:com.google.devtools.common.options.Option(
            name = "bar",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "true"
        )
        abstract val bar: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "baz",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "5"
        )
        abstract val baz: Int
    }

    @org.junit.Test
    fun convertToMap_basic() {
        val foo: FooOptions? =
            com.google.devtools.common.options.Options.getDefaults<FooOptions?>(FooOptions::class.java)
        Truth.assertThat(com.google.devtools.common.options.Options.toMap<FooOptions?>(foo))
            .containsExactly("foo", false)
    }

    @org.junit.Test
    fun convertToMap_inheritance() {
        // Static type is base class, dynamic type is derived. We still get the derived fields.
        val foo: FooOptions? =
            com.google.devtools.common.options.Options.getDefaults<BazOptions?>(BazOptions::class.java)
        Truth.assertThat(com.google.devtools.common.options.Options.toMap<FooOptions?>(foo))
            .containsExactly("bar", true, "baz", 5, "foo", false).inOrder()
    }

    /**
     * Dummy options class for checking alphabetizing.
     * 
     * 
     * Note that field name order differs from option name order.
     */
    @OptionsClass
    abstract class AlphaOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "c",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "0"
        )
        abstract val v: Int

        @get:com.google.devtools.common.options.Option(
            name = "d",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "0"
        )
        abstract val w: Int

        @get:com.google.devtools.common.options.Option(
            name = "a",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "0"
        )
        abstract val x: Int

        @get:com.google.devtools.common.options.Option(
            name = "e",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "0"
        )
        abstract val y: Int

        @get:com.google.devtools.common.options.Option(
            name = "b",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "0"
        )
        abstract val z: Int
    }

    @org.junit.Test
    fun convertToMap_alphabeticalOrder() {
        val alpha: AlphaOptions? =
            com.google.devtools.common.options.Options.getDefaults<AlphaOptions?>(AlphaOptions::class.java)
        Truth.assertThat(com.google.devtools.common.options.Options.toMap<AlphaOptions?>(alpha))
            .containsExactly("a", 0, "b", 0, "c", 0, "d", 0, "e", 0)
            .inOrder()
    }

    companion object {
        private val NO_ARGS = arrayOf<String?>()
    }
}
