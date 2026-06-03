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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.util.OptionsUtils.PathFragmentConverter

/** Test for [OptionsUtils].  */
@RunWith(TestParameterInjector::class)
class OptionsUtilsTest {
    @OptionsClass
    abstract class IntrospectionExample : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "alpha",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "alpha"
        )
        abstract val alpha: String?

        @get:com.google.devtools.common.options.Option(
            name = "beta",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "beta"
        )
        abstract val beta: String?

        @get:com.google.devtools.common.options.Option(
            name = "gamma",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "gamma"
        )
        abstract val gamma: String?

        @get:com.google.devtools.common.options.Option(
            name = "delta",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "delta"
        )
        abstract val delta: String?

        @get:com.google.devtools.common.options.Option(
            name = "echo",
            metadataTags = [OptionMetadataTag.HIDDEN],
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "echo"
        )
        abstract val echo: String?
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun asStringOfExplicitOptions() {
        val parser: OptionsParser =
            OptionsParser.builder()
                .optionsClasses(com.google.devtools.build.lib.util.OptionsUtilsTest.IntrospectionExample::class.java)
                .build()
        parser.parse("--alpha=no", "--gamma=no", "--echo=no")
        assertThat(OptionsUtils.asShellEscapedString(parser)).isEqualTo("--alpha=no --gamma=no")
        assertThat(OptionsUtils.asArgumentList(parser))
            .containsExactly("--alpha=no", "--gamma=no")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun asStringOfExplicitOptionsCorrectSortingByPriority() {
        val parser: OptionsParser =
            OptionsParser.builder()
                .optionsClasses(com.google.devtools.build.lib.util.OptionsUtilsTest.IntrospectionExample::class.java)
                .build()
        parser.parse(PriorityCategory.COMMAND_LINE, null, mutableListOf<String?>("--alpha=no"))
        parser.parse(PriorityCategory.COMPUTED_DEFAULT, null, mutableListOf<String?>("--beta=no"))
        assertThat(OptionsUtils.asShellEscapedString(parser)).isEqualTo("--beta=no --alpha=no")
        assertThat(OptionsUtils.asArgumentList(parser))
            .containsExactly("--beta=no", "--alpha=no")
            .inOrder()
    }

    @OptionsClass
    abstract class BooleanOpts : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "b_one",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "true"
        )
        abstract val bOne: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "b_two",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false"
        )
        abstract val bTwo: Boolean
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun asStringOfExplicitOptionsWithBooleans() {
        var parser: OptionsParser = OptionsParser.builder().optionsClasses(BooleanOpts::class.java).build()
        parser.parse(PriorityCategory.COMMAND_LINE, null, mutableListOf<String?>("--b_one", "--nob_two"))
        assertThat(OptionsUtils.asShellEscapedString(parser)).isEqualTo("--b_one --nob_two")
        assertThat(OptionsUtils.asArgumentList(parser))
            .containsExactly("--b_one", "--nob_two")
            .inOrder()

        parser = OptionsParser.builder().optionsClasses(BooleanOpts::class.java).build()
        parser.parse(PriorityCategory.COMMAND_LINE, null, mutableListOf<String?>("--b_one=true", "--b_two=0"))
        Truth.assertThat(parser.getOptions<BooleanOpts?>(BooleanOpts::class.java).getBOne()).isTrue()
        Truth.assertThat(parser.getOptions<BooleanOpts?>(BooleanOpts::class.java).getBTwo()).isFalse()
        assertThat(OptionsUtils.asShellEscapedString(parser)).isEqualTo("--b_one --nob_two")
        assertThat(OptionsUtils.asArgumentList(parser))
            .containsExactly("--b_one", "--nob_two")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun asStringOfExplicitOptionsMultipleOptionsAreMultipleTimes() {
        val parser: OptionsParser =
            OptionsParser.builder()
                .optionsClasses(com.google.devtools.build.lib.util.OptionsUtilsTest.IntrospectionExample::class.java)
                .build()
        parser.parse(PriorityCategory.COMMAND_LINE, null, mutableListOf<String?>("--alpha=one"))
        parser.parse(PriorityCategory.COMMAND_LINE, null, mutableListOf<String?>("--alpha=two"))
        assertThat(OptionsUtils.asShellEscapedString(parser)).isEqualTo("--alpha=one --alpha=two")
        assertThat(OptionsUtils.asArgumentList(parser))
            .containsExactly("--alpha=one", "--alpha=two")
            .inOrder()
    }

    private fun fragment(string: String?): PathFragment {
        return PathFragment.create(string)
    }

    @Throws(java.lang.Exception::class)
    private fun convert(input: String?): MutableList<PathFragment?> {
        return PathFragmentListConverter().convert(input)
    }

    @Throws(java.lang.Exception::class)
    private fun convertOne(input: String?): PathFragment {
        return PathFragmentConverter().convert(input)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun emptyStringYieldsEmptyList() {
        Truth.assertThat(convert("")).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun lonelyDotYieldsLonelyDot() {
        Truth.assertThat(convert(".")).containsExactly(fragment("."))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun converterSkipsEmptyStrings() {
        Truth.assertThat(convert("foo::bar:")).containsExactly(fragment("foo"), fragment("bar")).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multiplePaths() {
        Truth.assertThat(convert("~/foo:foo:/bar/baz:.:/tmp/bang"))
            .containsExactly(
                fragment(java.lang.System.getProperty("user.home") + "/foo"),
                fragment("foo"),
                fragment("/bar/baz"),
                fragment("."),
                fragment("/tmp/bang")
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun singlePath() {
        assertThat(convertOne("foo")).isEqualTo(fragment("foo"))
        assertThat(convertOne("foo/bar/baz")).isEqualTo(fragment("foo/bar/baz"))
        assertThat(convertOne("~/foo")).isEqualTo(fragment(java.lang.System.getProperty("user.home") + "/foo"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun absolutePathFragmentConverter_convertsAbsolutePath(
        @TestParameter("/", "/dir/file") path: String?
    ) {
        val converter: OptionsUtils.AbsolutePathFragmentConverter =
            AbsolutePathFragmentConverter()
        assertThat(converter.convert(path)).isEqualTo(PathFragment.create(path))
    }

    @org.junit.Test
    fun absolutePathFragmentConverter_failsForRelativePath() {
        val converter: OptionsUtils.AbsolutePathFragmentConverter =
            AbsolutePathFragmentConverter()

        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { converter.convert("relative/path") })

        Truth.assertThat(e).hasMessageThat().isEqualTo("Not an absolute path: 'relative/path'")
    }
}
