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
package com.google.devtools.build.lib.skyframe.config

import com.google.devtools.build.lib.analysis.config.BuildOptions

/** Unit tests for [ParsedFlagsValue].  */
@RunWith(JUnit4::class)
class ParsedFlagsValueTest {
    /** Extra options for this test.  */
    @OptionsClass
    abstract class DummyTestOptions : FragmentOptions() {
        @get:Option(
            name = "str_option",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "defVal"
        )
        abstract val strOption: String?

        @get:Option(
            name = "another_str_option",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "defVal"
        )
        abstract val anotherStrOption: String?

        @get:Option(
            name = "bool_option",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false"
        )
        abstract val boolOption: Boolean

        @get:Option(
            name = "list_option",
            converter = CommaSeparatedOptionListConverter::class,
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null"
        )
        abstract val listOption: MutableList<String?>?

        @get:Option(
            name = "null_option",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null"
        )
        abstract val nullOption: String?

        @get:Option(
            name = "accumulating_option",
            allowMultiple = true,
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null"
        )
        abstract val accumulatingOption: MutableList<String?>?

        @get:Option(
            name = "dummy_option",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "internal_default",
            implicitRequirements = ["--implicit_option=set_implicitly"]
        )
        abstract val dummyOption: String?

        @get:Option(
            name = "implicit_option",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "implicit_default"
        )
        abstract val implicitOption: String?
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun parse() {
        val flags: NativeAndStarlarkFlags? =
            NativeAndStarlarkFlags.builder()
                .optionsClasses(BUILD_CONFIG_OPTIONS)
                .nativeFlags(com.google.common.collect.ImmutableList.of<E?>("--str_option=bar", "--nobool_option"))
                .starlarkFlags(com.google.common.collect.ImmutableMap.of<K?, V?>("//custom:flag", "hello"))
                .starlarkFlagDefaults(com.google.common.collect.ImmutableMap.of<K?, V?>("//custom:flag", "default"))
                .build()

        val parsedFlags: ParsedFlagsValue = ParsedFlagsValue.parseAndCreate(flags)

        val result: OptionsParsingResult = parsedFlags.parsingResult()
        assertThat(
            result.getOptions(com.google.devtools.build.lib.skyframe.config.ParsedFlagsValueTest.DummyTestOptions::class.java)
                .getStrOption()
        ).isEqualTo("bar")
        assertThat(
            result.getOptions(com.google.devtools.build.lib.skyframe.config.ParsedFlagsValueTest.DummyTestOptions::class.java)
                .getBoolOption()
        ).isFalse()
        assertThat(result.getStarlarkOptions()).containsAtLeast("//custom:flag", "hello")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mergeWith() {
        val original: BuildOptions =
            BuildOptions.of(BUILD_CONFIG_OPTIONS, "--str_option=foo", "--bool_option")

        val flags: NativeAndStarlarkFlags? =
            NativeAndStarlarkFlags.builder()
                .optionsClasses(BUILD_CONFIG_OPTIONS)
                .nativeFlags(com.google.common.collect.ImmutableList.of<E?>("--str_option=bar", "--nobool_option"))
                .starlarkFlags(com.google.common.collect.ImmutableMap.of<K?, V?>("//custom:flag", "hello"))
                .starlarkFlagDefaults(com.google.common.collect.ImmutableMap.of<K?, V?>("//custom:flag", "default"))
                .build()
        val parsedFlags: ParsedFlagsValue = ParsedFlagsValue.parseAndCreate(flags)

        val modified: BuildOptions = parsedFlags.mergeWith(original).getOptions()

        // Ensure the original wasn't modified.
        assertThat(original.get(com.google.devtools.build.lib.skyframe.config.ParsedFlagsValueTest.DummyTestOptions::class.java))
            .isNotEqualTo(modified.get(com.google.devtools.build.lib.skyframe.config.ParsedFlagsValueTest.DummyTestOptions::class.java))

        // Check the modified values.
        assertThat(
            modified.get(com.google.devtools.build.lib.skyframe.config.ParsedFlagsValueTest.DummyTestOptions::class.java)
                .getStrOption()
        ).isEqualTo("bar")
        assertThat(
            modified.get(com.google.devtools.build.lib.skyframe.config.ParsedFlagsValueTest.DummyTestOptions::class.java)
                .getBoolOption()
        ).isFalse()
        assertThat(modified.getStarlarkOptions())
            .containsAtLeast(Label.parseCanonicalUnchecked("//custom:flag"), "hello")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mergeWith_unknownNativeFragment() {
        // Only use the basic flags.
        val original: BuildOptions? = BuildOptions.of(BUILD_CONFIG_OPTIONS)

        // Add another fragment with different flags.
        val flags: NativeAndStarlarkFlags? =
            NativeAndStarlarkFlags.builder()
                .optionsClasses(
                    com.google.common.collect.ImmutableSet.builder<java.lang.Class<out FragmentOptions?>?>()
                        .addAll(BUILD_CONFIG_OPTIONS)
                        .add(BuildOptionsTest.SecondDummyTestOptions::class.java)
                        .build()
                )
                .nativeFlags(com.google.common.collect.ImmutableList.of<E?>("--second_str_option=bar"))
                .build()
        val parsedFlags: ParsedFlagsValue = ParsedFlagsValue.parseAndCreate(flags)

        // The native flags that are unknown to the original options should not be present.
        val modified: BuildOptions = parsedFlags.mergeWith(original).getOptions()
        assertThat(modified.contains(BuildOptionsTest.SecondDummyTestOptions::class.java)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mergeWith_illegalStarlarkLabel() {
        val original: BuildOptions? = BuildOptions.of(BUILD_CONFIG_OPTIONS)

        val flags: NativeAndStarlarkFlags? =
            NativeAndStarlarkFlags.builder()
                .optionsClasses(BUILD_CONFIG_OPTIONS)
                .starlarkFlags(com.google.common.collect.ImmutableMap.of<K?, V?>("@@@", "hello"))
                .build()
        val parsedFlagsValue: ParsedFlagsValue = ParsedFlagsValue.parseAndCreate(flags)

        // BuildOptions, unlike OptionsParser, uses a Label for the key, so this is the only code path
        // that validates that a starlark flag is actually a Label.
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { parsedFlagsValue.mergeWith(original) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mergeWith_multiValueOption_nonAccumulating() {
        val original: BuildOptions? = BuildOptions.of(BUILD_CONFIG_OPTIONS, "--list_option=baz,quux")

        val flags: NativeAndStarlarkFlags? =
            NativeAndStarlarkFlags.builder()
                .optionsClasses(BUILD_CONFIG_OPTIONS)
                .nativeFlags(com.google.common.collect.ImmutableList.of<E?>("--list_option=foo,bar"))
                .build()
        val parsedFlags: ParsedFlagsValue = ParsedFlagsValue.parseAndCreate(flags)

        val modified: BuildOptions = parsedFlags.mergeWith(original).getOptions()

        assertThat(
            modified.get(com.google.devtools.build.lib.skyframe.config.ParsedFlagsValueTest.DummyTestOptions::class.java)
                .getListOption()
        ) // Because this flag does not allow multiple values the list simply overwrites the previous
            // value.
            .containsExactly("foo", "bar")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mergeWith_implicitOption() {
        val original: BuildOptions? = BuildOptions.of(BUILD_CONFIG_OPTIONS)

        val flags: NativeAndStarlarkFlags? =
            NativeAndStarlarkFlags.builder()
                .optionsClasses(BUILD_CONFIG_OPTIONS)
                .nativeFlags(com.google.common.collect.ImmutableList.of<E?>("--dummy_option=direct"))
                .build()
        val parsedFlags: ParsedFlagsValue = ParsedFlagsValue.parseAndCreate(flags)

        val modified: BuildOptions = parsedFlags.mergeWith(original).getOptions()

        assertThat(
            modified.get(com.google.devtools.build.lib.skyframe.config.ParsedFlagsValueTest.DummyTestOptions::class.java)
                .getDummyOption()
        ).isEqualTo("direct")
        assertThat(
            modified.get(com.google.devtools.build.lib.skyframe.config.ParsedFlagsValueTest.DummyTestOptions::class.java)
                .getImplicitOption()
        )
            .isEqualTo("set_implicitly")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mergeWith_accumulating() {
        val original: BuildOptions? = BuildOptions.of(BUILD_CONFIG_OPTIONS)

        val flags: NativeAndStarlarkFlags? =
            NativeAndStarlarkFlags.builder()
                .optionsClasses(BUILD_CONFIG_OPTIONS)
                .nativeFlags(
                    com.google.common.collect.ImmutableList.of<E?>(
                        "--accumulating_option=foo",
                        "--accumulating_option=bar"
                    )
                )
                .build()
        val parsedFlags: ParsedFlagsValue = ParsedFlagsValue.parseAndCreate(flags)

        val modified: BuildOptions = parsedFlags.mergeWith(original).getOptions()

        assertThat(
            modified.get(com.google.devtools.build.lib.skyframe.config.ParsedFlagsValueTest.DummyTestOptions::class.java)
                .getAccumulatingOption()
        )
            .containsExactly("foo", "bar")
            .inOrder()
    }

    // TODO: https://github.com/bazelbuild/bazel/issues/22453 - Add a test of an accumulating flag
    // with previous values when that works correctly.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mergeWith_starlark() {
        val original: BuildOptions? =
            BuildOptions.of(BUILD_CONFIG_OPTIONS).toBuilder()
                .addStarlarkOption(Label.parseCanonicalUnchecked("//custom:flag"), "direct")
                .build()

        val flags: NativeAndStarlarkFlags? =
            NativeAndStarlarkFlags.builder()
                .optionsClasses(BUILD_CONFIG_OPTIONS)
                .starlarkFlags(com.google.common.collect.ImmutableMap.of<K?, V?>("//custom:flag", "override"))
                .starlarkFlagDefaults(com.google.common.collect.ImmutableMap.of<K?, V?>("//custom:flag", "default"))
                .build()
        val parsedFlags: ParsedFlagsValue = ParsedFlagsValue.parseAndCreate(flags)

        val modified: BuildOptions = parsedFlags.mergeWith(original).getOptions()

        // Check the modified values.
        assertThat(modified.getStarlarkOptions())
            .containsAtLeast(Label.parseCanonicalUnchecked("//custom:flag"), "override")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mergeWith_starlark_resetToDefault() {
        val original: BuildOptions? =
            BuildOptions.of(BUILD_CONFIG_OPTIONS).toBuilder()
                .addStarlarkOption(Label.parseCanonicalUnchecked("//custom:flag"), "direct")
                .build()

        val flags: NativeAndStarlarkFlags? =
            NativeAndStarlarkFlags.builder()
                .optionsClasses(BUILD_CONFIG_OPTIONS)
                .starlarkFlags(com.google.common.collect.ImmutableMap.of<K?, V?>("//custom:flag", "default"))
                .starlarkFlagDefaults(com.google.common.collect.ImmutableMap.of<K?, V?>("//custom:flag", "default"))
                .build()
        val parsedFlags: ParsedFlagsValue = ParsedFlagsValue.parseAndCreate(flags)

        val modified: BuildOptions = parsedFlags.mergeWith(original).getOptions()

        // The Starlark flag should not be present since it was reset to the default value
        assertThat(modified.getStarlarkOptions())
            .doesNotContainKey(Label.parseCanonicalUnchecked("//custom:flag"))
    }

    companion object {
        private val BUILD_CONFIG_OPTIONS: com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?> =
            com.google.common.collect.ImmutableSet.of<E?>(com.google.devtools.build.lib.skyframe.config.ParsedFlagsValueTest.DummyTestOptions::class.java)
    }
}
