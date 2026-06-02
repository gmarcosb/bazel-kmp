// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.cmdline.Label

/** Tests for [BuildOptionDetails].  */
@RunWith(JUnit4::class)
class BuildOptionDetailsTest {
    /** Instantiates the given options classes, parsing the given options as well.  */
    @Throws(java.lang.Exception::class)
    fun parseOptions(
        optionsClasses: Iterable<out java.lang.Class<out FragmentOptions?>?>, vararg options: String?
    ): Iterable<FragmentOptions?> {
        val optionsParser: OptionsParser =
            OptionsParser.builder().optionsClasses(optionsClasses).allowResidue(false).build()
        optionsParser.parse(options)
        val output: com.google.common.collect.ImmutableList.Builder<FragmentOptions?> =
            com.google.common.collect.ImmutableList.Builder<FragmentOptions?>()
        for (optionsClass in optionsClasses) {
            output.add(optionsParser.getOptions(optionsClass))
        }
        return output.build()
    }

    /** Example converter for working with options with converters.  */
    class Optionalizer : Contextless<com.google.common.base.Optional<String?>?>() {
        public override fun convert(input: String): com.google.common.base.Optional<String?> {
            if ("" == input) {
                return com.google.common.base.Optional.absent<String?>()
            }
            return com.google.common.base.Optional.of<String?>(input)
        }

        public override fun getTypeDescription(): String {
            return "a string"
        }
    }

    /** Example options class for testing options lookup.  */
    @OptionsClass
    abstract class Options : FragmentOptions() {
        @Option(
            name = "boolean_option",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "true"
        )
        abstract fun getBooleanOption(): Boolean

        @Option(
            name = "convertible_option",
            converter = Optionalizer::class,
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = ""
        )
        abstract fun getConvertibleOption(): com.google.common.base.Optional<String?>?

        @Option(
            name = "null_default",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null"
        )
        abstract fun getNullDefault(): String?

        @Option(
            name = "late_bound_default",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null"
        )
        abstract fun getLateBoundDefault(): String?

        @Option(
            name = "multi_option",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null",
            allowMultiple = true
        )
        abstract fun getMultiOption(): MutableList<String?>?

        @Option(
            name = "internal option",
            defaultValue = "secret",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            metadataTags = [OptionMetadataTag.INTERNAL]
        )
        abstract fun getInternalOption(): String?

        @Option(
            name = "internal multi option",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null",
            metadataTags = [OptionMetadataTag.INTERNAL],
            allowMultiple = true
        )
        abstract fun getInternalMultiOption(): MutableList<String?>?
    }

    /** Additional options class for testing options lookup.  */
    @OptionsClass
    abstract class MoreOptions : FragmentOptions() {
        @Option(
            name = "other_option",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = ""
        )
        abstract fun getOtherOption(): String?
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getOptionClass_ReturnsClassOfPresentOptions() {
        val details: BuildOptionDetails =
            BuildOptionDetails.forOptionsForTesting(
                parseOptions(
                    com.google.common.collect.ImmutableList.of<java.lang.Class<Options?>?>(
                        com.google.devtools.build.lib.analysis.config.BuildOptionDetailsTest.Options::class.java
                    )
                )
            )
        assertThat(details.getOptionClass("boolean_option")).isEqualTo(com.google.devtools.build.lib.analysis.config.BuildOptionDetailsTest.Options::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getOptionClass_SelectsCorrectClassWhenMultipleArePresent() {
        val details: BuildOptionDetails =
            BuildOptionDetails.forOptionsForTesting(
                parseOptions(
                    com.google.common.collect.ImmutableList.of<java.lang.Class<out Any?>?>(
                        com.google.devtools.build.lib.analysis.config.BuildOptionDetailsTest.Options::class.java,
                        com.google.devtools.build.lib.analysis.config.BuildOptionDetailsTest.MoreOptions::class.java
                    )
                )
            )
        assertThat(details.getOptionClass("boolean_option")).isEqualTo(com.google.devtools.build.lib.analysis.config.BuildOptionDetailsTest.Options::class.java)
        assertThat(details.getOptionClass("other_option")).isEqualTo(com.google.devtools.build.lib.analysis.config.BuildOptionDetailsTest.MoreOptions::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getOptionClass_ReturnsNullIfOptionsClassIsNotPartOfOptionDetails() {
        val details: BuildOptionDetails =
            BuildOptionDetails.forOptionsForTesting(
                parseOptions(
                    com.google.common.collect.ImmutableList.of<java.lang.Class<Options?>?>(
                        com.google.devtools.build.lib.analysis.config.BuildOptionDetailsTest.Options::class.java
                    )
                )
            )
        assertThat(details.getOptionClass("other_option")).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getOptionClass_SelectsCorrectClassEvenWhenValueIsNull() {
        val details: BuildOptionDetails =
            BuildOptionDetails.forOptionsForTesting(
                parseOptions(
                    com.google.common.collect.ImmutableList.of<java.lang.Class<Options?>?>(
                        com.google.devtools.build.lib.analysis.config.BuildOptionDetailsTest.Options::class.java
                    )
                )
            )
        assertThat(details.getOptionClass("null_default")).isEqualTo(com.google.devtools.build.lib.analysis.config.BuildOptionDetailsTest.Options::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getOptionClass_ReturnsNullWhenOptionIsUndefined() {
        val details: BuildOptionDetails =
            BuildOptionDetails.forOptionsForTesting(
                parseOptions(
                    com.google.common.collect.ImmutableList.of<java.lang.Class<Options?>?>(
                        com.google.devtools.build.lib.analysis.config.BuildOptionDetailsTest.Options::class.java
                    )
                )
            )
        assertThat(details.getOptionClass("undefined_option")).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getOptionClass_ReturnsNullIfOptionIsInternal() {
        val details: BuildOptionDetails =
            BuildOptionDetails.forOptionsForTesting(
                parseOptions(
                    com.google.common.collect.ImmutableList.of<java.lang.Class<Options?>?>(
                        com.google.devtools.build.lib.analysis.config.BuildOptionDetailsTest.Options::class.java
                    )
                )
            )
        assertThat(details.getOptionClass("internal option")).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getOptionValue_ReturnsDefaultValueIfNotSet() {
        val details: BuildOptionDetails =
            BuildOptionDetails.forOptionsForTesting(
                parseOptions(
                    com.google.common.collect.ImmutableList.of<java.lang.Class<Options?>?>(
                        com.google.devtools.build.lib.analysis.config.BuildOptionDetailsTest.Options::class.java
                    )
                )
            )
        assertThat(details.getOptionValue("boolean_option")).isEqualTo(true)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getOptionValue_ReturnsCommandLineValueIfSet() {
        val details: BuildOptionDetails =
            BuildOptionDetails.forOptionsForTesting(
                parseOptions(
                    com.google.common.collect.ImmutableList.of<E?>(com.google.devtools.build.lib.analysis.config.BuildOptionDetailsTest.Options::class.java),
                    "--noboolean_option"
                )
            )
        assertThat(details.getOptionValue("boolean_option")).isEqualTo(false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getOptionValue_ReturnsEmptyListForUnspecifiedMultiOptions() {
        val details: BuildOptionDetails =
            BuildOptionDetails.forOptionsForTesting(
                parseOptions(
                    com.google.common.collect.ImmutableList.of<E?>(com.google.devtools.build.lib.analysis.config.BuildOptionDetailsTest.Options::class.java),
                    "--noboolean_option"
                )
            )
        assertThat(details.getOptionValue("multi_option")).isEqualTo(com.google.common.collect.ImmutableList.of<String?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getOptionValue_ReturnsListOfValuesForSpecifiedMultiOptions() {
        val details: BuildOptionDetails =
            BuildOptionDetails.forOptionsForTesting(
                parseOptions(
                    com.google.common.collect.ImmutableList.of<java.lang.Class<Options?>?>(com.google.devtools.build.lib.analysis.config.BuildOptionDetailsTest.Options::class.java),
                    "--multi_option=one",
                    "--multi_option=2",
                    "--multi_option=iii"
                )
            )
        assertThat(details.getOptionValue("multi_option"))
            .isEqualTo(com.google.common.collect.ImmutableList.of<String?>("one", "2", "iii"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getOptionValue_DrawsValuesFromAllOptionsClasses() {
        val details: BuildOptionDetails =
            BuildOptionDetails.forOptionsForTesting(
                parseOptions(
                    com.google.common.collect.ImmutableList.of<E?>(
                        com.google.devtools.build.lib.analysis.config.BuildOptionDetailsTest.Options::class.java,
                        com.google.devtools.build.lib.analysis.config.BuildOptionDetailsTest.MoreOptions::class.java
                    ), "--other_option=set"
                )
            )
        assertThat(details.getOptionValue("other_option")).isEqualTo("set")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getOptionValue_UsesConvertersIfSpecified() {
        val details: BuildOptionDetails =
            BuildOptionDetails.forOptionsForTesting(
                parseOptions(
                    com.google.common.collect.ImmutableList.of<E?>(com.google.devtools.build.lib.analysis.config.BuildOptionDetailsTest.Options::class.java),
                    "--convertible_option=Set"
                )
            )
        assertThat(details.getOptionValue("convertible_option")).isEqualTo(com.google.common.base.Optional.of<String?>("Set"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getOptionValue_UsesConvertersForDefaultsIfSpecified() {
        val details: BuildOptionDetails =
            BuildOptionDetails.forOptionsForTesting(
                parseOptions(
                    com.google.common.collect.ImmutableList.of<java.lang.Class<Options?>?>(
                        com.google.devtools.build.lib.analysis.config.BuildOptionDetailsTest.Options::class.java
                    )
                )
            )
        assertThat(details.getOptionValue("convertible_option")).isEqualTo(com.google.common.base.Optional.absent<String?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getOptionValue_ReturnsNullIfOptionIsNotDefined() {
        val details: BuildOptionDetails =
            BuildOptionDetails.forOptionsForTesting(
                parseOptions(
                    com.google.common.collect.ImmutableList.of<java.lang.Class<Options?>?>(
                        com.google.devtools.build.lib.analysis.config.BuildOptionDetailsTest.Options::class.java
                    )
                )
            )
        assertThat(details.getOptionValue("undefined_option")).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getOptionValue_ReturnsNullIfOptionIsInternal() {
        val details: BuildOptionDetails =
            BuildOptionDetails.forOptionsForTesting(
                parseOptions(
                    com.google.common.collect.ImmutableList.of<java.lang.Class<Options?>?>(
                        com.google.devtools.build.lib.analysis.config.BuildOptionDetailsTest.Options::class.java
                    )
                )
            )
        assertThat(details.getOptionValue("internal option")).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getOptionValue_ReturnsNullIfOptionIsDefinedInNonIncludedOptionsClass() {
        val details: BuildOptionDetails =
            BuildOptionDetails.forOptionsForTesting(
                parseOptions(
                    com.google.common.collect.ImmutableList.of<java.lang.Class<Options?>?>(
                        com.google.devtools.build.lib.analysis.config.BuildOptionDetailsTest.Options::class.java
                    )
                )
            )
        assertThat(details.getOptionValue("other_option")).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getOptionValue_ReturnsNullIfOptionDefaultValueIsNull() {
        val details: BuildOptionDetails =
            BuildOptionDetails.forOptionsForTesting(
                parseOptions(
                    com.google.common.collect.ImmutableList.of<java.lang.Class<Options?>?>(
                        com.google.devtools.build.lib.analysis.config.BuildOptionDetailsTest.Options::class.java
                    )
                )
            )
        assertThat(details.getOptionValue("null_option")).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun allowsMultipleValues_ReturnsFalseForUndefinedOption() {
        val details: BuildOptionDetails =
            BuildOptionDetails.forOptionsForTesting(
                parseOptions(
                    com.google.common.collect.ImmutableList.of<java.lang.Class<Options?>?>(
                        com.google.devtools.build.lib.analysis.config.BuildOptionDetailsTest.Options::class.java
                    )
                )
            )
        assertThat(details.allowsMultipleValues("undefined_option")).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun allowsMultipleValues_ReturnsFalseForNonMultiOption() {
        val details: BuildOptionDetails =
            BuildOptionDetails.forOptionsForTesting(
                parseOptions(
                    com.google.common.collect.ImmutableList.of<java.lang.Class<Options?>?>(
                        com.google.devtools.build.lib.analysis.config.BuildOptionDetailsTest.Options::class.java
                    )
                )
            )
        assertThat(details.allowsMultipleValues("boolean_option")).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun allowsMultipleValues_ReturnsFalseForInternalNonMultiOption() {
        val details: BuildOptionDetails =
            BuildOptionDetails.forOptionsForTesting(
                parseOptions(
                    com.google.common.collect.ImmutableList.of<java.lang.Class<Options?>?>(
                        com.google.devtools.build.lib.analysis.config.BuildOptionDetailsTest.Options::class.java
                    )
                )
            )
        assertThat(details.allowsMultipleValues("internal option")).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun allowsMultipleValues_ReturnsFalseForInternalMultiOption() {
        val details: BuildOptionDetails =
            BuildOptionDetails.forOptionsForTesting(
                parseOptions(
                    com.google.common.collect.ImmutableList.of<java.lang.Class<Options?>?>(
                        com.google.devtools.build.lib.analysis.config.BuildOptionDetailsTest.Options::class.java
                    )
                )
            )
        assertThat(details.allowsMultipleValues("internal multi option")).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun allowsMultipleValues_ReturnsTrueForMultiOption() {
        val details: BuildOptionDetails =
            BuildOptionDetails.forOptionsForTesting(
                parseOptions(
                    com.google.common.collect.ImmutableList.of<java.lang.Class<Options?>?>(
                        com.google.devtools.build.lib.analysis.config.BuildOptionDetailsTest.Options::class.java
                    )
                )
            )
        assertThat(details.allowsMultipleValues("multi_option")).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkOptions() {
        val details: BuildOptionDetails =
            BuildOptionDetails.forOptions(
                parseOptions(com.google.common.collect.ImmutableList.of<java.lang.Class<Options?>?>(com.google.devtools.build.lib.analysis.config.BuildOptionDetailsTest.Options::class.java)),
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    Label.parseCanonicalUnchecked("//test:setting"),
                    "value"
                )
            )
        assertThat(details.getOptionValue(Label.parseCanonicalUnchecked("//test:setting")))
            .isEqualTo("value")
    }
}
