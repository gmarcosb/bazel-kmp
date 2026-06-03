// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.util.StringEncoding

/** A test for [RegexPatternConverter]  */
@RunWith(TestParameterInjector::class)
class RegexPatternConverterTest {
    @org.junit.Test
    fun consistentEqualsAndHashCodeForSamePattern() {
        ConverterTester(RegexPatternConverter::class.java,  /* conversionContext= */null)
            .addEqualityGroup("")
            .addEqualityGroup(".*")
            .addEqualityGroup("[^\\s]+")
            .testConvert()
    }

    @org.junit.Test
    fun comparisonBasedOnInputOnly() {
        val regex = "a"
        val semanticallyTheSame = "[a]"

        ConverterTester(RegexPatternConverter::class.java,  /* conversionContext= */null)
            .addEqualityGroup(regex)
            .addEqualityGroup(semanticallyTheSame)
            .testConvert()
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun createsProperPattern() {
        val converter: RegexPatternConverter = RegexPatternConverter()
        for (regex in arrayOf<String>("", ".*", "\\s*(\\w+)", "prefix (suffix1|suffix2)")) {
            // We are not testing {@link Pattern} itself -- the assumption is that if {@link
            // Pattern#pattern} returns the proper string, we created the right pattern.
            Truth.assertThat(converter.convert(regex).regexPattern().pattern()).isEqualTo(regex)
        }
    }

    @org.junit.Test
    fun throwsForWrongPattern() {
        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { RegexPatternConverter().convert("{") })
        Truth.assertThat(e).hasMessageThat().startsWith("Not a valid regular expression:")
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun unicodeLiteral() {
        // Options passed on the command line are passed to convertes in the internal encoding.
        val regex: RegexPatternOption = RegexPatternConverter().convert(StringEncoding.unicodeToInternal("äöüÄÖÜß🌱"))
        Truth.assertThat(regex.regexPattern().matcher("äöüÄÖÜß🌱").matches()).isTrue()
        Truth.assertThat(regex.matcher().test(StringEncoding.unicodeToInternal("äöüÄÖÜß🌱"))).isTrue()
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun unicodeLiteral_caseInsensitive() {
        // Options passed on the command line are passed to convertes in the internal encoding.
        val regex: RegexPatternOption =
            RegexPatternConverter().convert(StringEncoding.unicodeToInternal("(?ui)äöüÄÖÜß🌱"))
        Truth.assertThat(regex.regexPattern().matcher("ÄÖÜäöüß🌱").matches()).isTrue()
        Truth.assertThat(regex.matcher().test(StringEncoding.unicodeToInternal("ÄÖÜäöüß🌱"))).isTrue()
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun unicodeLiteral_suffix() {
        // Options passed on the command line are passed to convertes in the internal encoding.
        val regex: RegexPatternOption =
            RegexPatternConverter().convert(StringEncoding.unicodeToInternal(".*äöüÄÖÜß🌱"))
        Truth.assertThat(regex.regexPattern().matcher("äöüäöüÄÖÜß🌱").matches()).isTrue()
        Truth.assertThat(regex.matcher().test(StringEncoding.unicodeToInternal("äöüäöüÄÖÜß🌱"))).isTrue()
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun unicodeClass() {
        // Options passed on the command line are passed to convertes in the internal encoding.
        val regex: RegexPatternOption =
            RegexPatternConverter()
                .convert(StringEncoding.unicodeToInternal("\\p{L}{7}\\p{IsEmoji}"))
        Truth.assertThat(regex.regexPattern().matcher("äöüÄÖÜß🌱").matches()).isTrue()
        Truth.assertThat(regex.matcher().test(StringEncoding.unicodeToInternal("äöüÄÖÜß🌱"))).isTrue()
    }
}
