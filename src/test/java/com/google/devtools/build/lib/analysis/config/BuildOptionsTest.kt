// Copyright 2009 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.config.BuildOptions.MapBackedChecksumCache

/**
 * A test for [BuildOptions].
 * 
 * 
 * Currently this tests native options and Starlark options completely separately since these two
 * types of options do not interact. In the future when we begin to migrate native options to
 * Starlark options, the format of this test class will need to accommodate that overlap.
 */
@RunWith(TestParameterInjector::class)
class BuildOptionsTest {
    /** Extra options for this test.  */
    @OptionsClass
    abstract class DummyTestOptions : FragmentOptions() {
        @Option(
            name = "str_option",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "defVal"
        )
        abstract fun getStrOption(): String?

        @Option(
            name = "another_str_option",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "defVal"
        )
        abstract fun getAnotherStrOption(): String?

        @Option(
            name = "bool_option",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false"
        )
        abstract fun getBoolOption(): Boolean

        @Option(
            name = "list_option",
            converter = CommaSeparatedOptionListConverter::class,
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null"
        )
        abstract fun getListOption(): MutableList<String?>?

        @Option(
            name = "null_option",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null"
        )
        abstract fun getNullOption(): String?

        @Option(
            name = "accumulating_option",
            allowMultiple = true,
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null"
        )
        abstract fun getAccumulatingOption(): MutableList<String?>?

        @Option(
            name = "dummy_option",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "internal_default",
            implicitRequirements = ["--implicit_option=set_implicitly"]
        )
        abstract fun getDummyOption(): String?

        @Option(
            name = "implicit_option",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "implicit_default"
        )
        abstract fun getImplicitOption(): String?
    }

    /** Extra options for this test.  */
    @OptionsClass
    abstract class SecondDummyTestOptions : FragmentOptions() {
        @Option(
            name = "second_str_option",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "defVal"
        )
        abstract fun getStrOption(): String?
    }

    @org.junit.Test
    fun optionSetCaching() {
        val a: BuildOptions =
            BuildOptions.of(
                BUILD_CONFIG_OPTIONS,
                OptionsParser.builder().optionsClasses(BUILD_CONFIG_OPTIONS).build()
            )
        val b: BuildOptions =
            BuildOptions.of(
                BUILD_CONFIG_OPTIONS,
                OptionsParser.builder().optionsClasses(BUILD_CONFIG_OPTIONS).build()
            )
        // The cache keys of the OptionSets must be equal even if these are
        // different objects, if they were created with the same options (no options in this case).
        assertThat(b.toString()).isEqualTo(a.toString())
        assertThat(b.checksum()).isEqualTo(a.checksum())
        assertThat(a).isEqualTo(b)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun optionsEquality() {
        val options1: Array<String?> = arrayOf<String>("--str_option=foo")
        val options2: Array<String?> = arrayOf<String>("--str_option=bar")
        // Distinct instances with the same values are equal:
        assertThat(BuildOptions.of(BUILD_CONFIG_OPTIONS, options1))
            .isEqualTo(BuildOptions.of(BUILD_CONFIG_OPTIONS, options1))
        // Same fragments, different values aren't equal:
        assertThat(
            BuildOptions.of(BUILD_CONFIG_OPTIONS, options1)
                .equals(BuildOptions.of(BUILD_CONFIG_OPTIONS, options2))
        )
            .isFalse()
        // Same values, different fragments aren't equal:
        assertThat(
            BuildOptions.of(BUILD_CONFIG_OPTIONS, options1)
                .equals(
                    BuildOptions.of(
                        com.google.common.collect.ImmutableList.of<E?>(
                            com.google.devtools.build.lib.analysis.config.BuildOptionsTest.DummyTestOptions::class.java,
                            SecondDummyTestOptions::class.java
                        ),
                        options1
                    )
                )
        )
            .isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun serialization(@TestParameter useSharedValues: Boolean) {
        val tester: SerializationTester =
            SerializationTester(
                BuildOptions.of(makeOptionsClassBuilder().build(), "--str_option=foo"),
                BuildOptions.of(makeOptionsClassBuilder().build(), "--str_option=bar"),
                BuildOptions.of(makeOptionsClassBuilder().add(SecondDummyTestOptions::class.java).build()),
                BuildOptions.of(
                    makeOptionsClassBuilder().add(SecondDummyTestOptions::class.java).build(),
                    "--str_option=foo",
                    "--second_str_option=baz",
                    "--another_str_option=bar"
                ),
                BuildOptions.builder()
                    .addStarlarkOption(Label.parseCanonicalUnchecked("//custom:flag"), "hello")
                    .build()
            )

        if (useSharedValues) {
            tester.makeMemoizingAndAllowFutureBlocking(true)
            tester.addCodec(BuildOptions.valueSharingCodec())
        } else {
            tester.addDependency(OptionsChecksumCache::class.java, MapBackedChecksumCache())
        }

        tester.runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun serialize_primeFails_throws() {
        val failToPrimeCache: OptionsChecksumCache =
            object : OptionsChecksumCache() {
                public override fun getOptions(checksum: String?): BuildOptions? {
                    throw java.lang.UnsupportedOperationException()
                }

                public override fun prime(options: BuildOptions?): Boolean {
                    return false
                }
            }
        val options: BuildOptions? = BuildOptions.of(BUILD_CONFIG_OPTIONS)
        val codecs: ObjectCodecs =
            ObjectCodecs(
                com.google.common.collect.ImmutableClassToInstanceMap.of<B?, T?>(
                    OptionsChecksumCache::class.java,
                    failToPrimeCache
                )
            )
        org.junit.Assert.assertThrows<T?>(
            SerializationException::class.java,
            org.junit.function.ThrowingRunnable { codecs.serialize(options) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun deserialize_unprimedCache_throws() {
        val options: BuildOptions = BuildOptions.of(BUILD_CONFIG_OPTIONS)

        val codecs: ObjectCodecs =
            ObjectCodecs(
                com.google.common.collect.ImmutableClassToInstanceMap.of<B?, T?>(
                    OptionsChecksumCache::class.java, MapBackedChecksumCache()
                )
            )
        val bytes: ByteString? = codecs.serialize(options)
        Truth.assertThat(bytes).isNotNull()

        // Different checksum cache than the one used for serialization, and it has not been primed.
        val notPrimed: ObjectCodecs =
            ObjectCodecs(
                com.google.common.collect.ImmutableClassToInstanceMap.of<B?, T?>(
                    OptionsChecksumCache::class.java, MapBackedChecksumCache()
                )
            )
        val e: java.lang.Exception? = org.junit.Assert.assertThrows<T?>(
            SerializationException::class.java,
            org.junit.function.ThrowingRunnable { notPrimed.deserialize(bytes) })
        Truth.assertThat(e).hasMessageThat().contains(options.checksum())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun deserialize_primedCache_returnsPrimedInstance() {
        val options: BuildOptions? = BuildOptions.of(BUILD_CONFIG_OPTIONS)

        val codecs: ObjectCodecs =
            ObjectCodecs(
                com.google.common.collect.ImmutableClassToInstanceMap.of<B?, T?>(
                    OptionsChecksumCache::class.java, MapBackedChecksumCache()
                )
            )
        val bytes: ByteString? = codecs.serialize(options)
        Truth.assertThat(bytes).isNotNull()

        // Different checksum cache than the one used for serialization, but it has been primed.
        val checksumCache: OptionsChecksumCache = MapBackedChecksumCache()
        assertThat(checksumCache.prime(options)).isTrue()
        val primed: ObjectCodecs =
            ObjectCodecs(
                com.google.common.collect.ImmutableClassToInstanceMap.of<B?, T?>(
                    OptionsChecksumCache::class.java,
                    checksumCache
                )
            )
        assertThat(primed.deserialize(bytes)).isSameInstanceAs(options)
    }

    @org.junit.Test
    fun testMultiValueOptionImmutability() {
        val options: BuildOptions =
            BuildOptions.of(
                BUILD_CONFIG_OPTIONS,
                OptionsParser.builder().optionsClasses(BUILD_CONFIG_OPTIONS).build()
            )
        val dummyTestOptions: DummyTestOptions =
            options.get(com.google.devtools.build.lib.analysis.config.BuildOptionsTest.DummyTestOptions::class.java)
        org.junit.Assert.assertThrows<java.lang.UnsupportedOperationException?>(
            java.lang.UnsupportedOperationException::class.java,
            org.junit.function.ThrowingRunnable { dummyTestOptions.getAccumulatingOption()!!.add("foo") })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun parsingResultMatch() {
        val original: BuildOptions =
            BuildOptions.of(BUILD_CONFIG_OPTIONS, "--str_option=foo", "--bool_option")

        val matchingParser: OptionsParser =
            OptionsParser.builder().optionsClasses(BUILD_CONFIG_OPTIONS).build()
        matchingParser.parse("--str_option=foo", "--bool_option")

        val notMatchingParser: OptionsParser =
            OptionsParser.builder().optionsClasses(BUILD_CONFIG_OPTIONS).build()
        notMatchingParser.parse("--str_option=foo", "--nobool_option")

        assertThat(original.matches(matchingParser)).isTrue()
        assertThat(original.matches(notMatchingParser)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun parsingResultMatchStarlark() {
        val original: BuildOptions =
            BuildOptions.builder()
                .addStarlarkOption(Label.parseCanonicalUnchecked("//custom:flag"), "hello")
                .build()

        val matchingParser: OptionsParser =
            OptionsParser.builder().optionsClasses(BUILD_CONFIG_OPTIONS).build()
        matchingParser.setStarlarkOptions(
            com.google.common.collect.ImmutableMap.of<K?, V?>("//custom:flag", "hello"),
            com.google.common.collect.ImmutableSet.of<E?>()
        )

        val notMatchingParser: OptionsParser =
            OptionsParser.builder().optionsClasses(BUILD_CONFIG_OPTIONS).build()
        notMatchingParser.setStarlarkOptions(
            com.google.common.collect.ImmutableMap.of<K?, V?>("//custom:flag", "foo"),
            com.google.common.collect.ImmutableSet.of<E?>()
        )

        assertThat(original.matches(matchingParser)).isTrue()
        assertThat(original.matches(notMatchingParser)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun parsingResultMatchMissingFragment() {
        val original: BuildOptions = BuildOptions.of(BUILD_CONFIG_OPTIONS, "--str_option=foo")

        val fragmentClasses: com.google.common.collect.ImmutableList<java.lang.Class<out FragmentOptions?>?> =
            com.google.common.collect.ImmutableList.of<E?>(
                com.google.devtools.build.lib.analysis.config.BuildOptionsTest.DummyTestOptions::class.java,
                SecondDummyTestOptions::class.java
            )

        val parser: OptionsParser = OptionsParser.builder().optionsClasses(fragmentClasses).build()
        parser.parse("--str_option=foo", "--second_str_option=bar")

        assertThat(original.matches(parser)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun parsingResultMatchEmptyNativeMatch() {
        val original: BuildOptions = BuildOptions.of(BUILD_CONFIG_OPTIONS, "--str_option=foo")

        val fragmentClasses: com.google.common.collect.ImmutableList<java.lang.Class<out FragmentOptions?>?> =
            com.google.common.collect.ImmutableList.of<E?>(
                com.google.devtools.build.lib.analysis.config.BuildOptionsTest.DummyTestOptions::class.java,
                SecondDummyTestOptions::class.java
            )

        val parser: OptionsParser = OptionsParser.builder().optionsClasses(fragmentClasses).build()
        parser.parse("--second_str_option=bar")

        assertThat(original.matches(parser)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun parsingResultMatchEmptyNativeMatchWithStarlark() {
        val original: BuildOptions =
            BuildOptions.builder()
                .addStarlarkOption(Label.parseCanonicalUnchecked("//custom:flag"), "hello")
                .build()

        val fragmentClasses: com.google.common.collect.ImmutableList<java.lang.Class<out FragmentOptions?>?> =
            com.google.common.collect.ImmutableList.builder<java.lang.Class<out FragmentOptions?>?>()
                .add(com.google.devtools.build.lib.analysis.config.BuildOptionsTest.DummyTestOptions::class.java)
                .add(SecondDummyTestOptions::class.java)
                .build()

        val parser: OptionsParser = OptionsParser.builder().optionsClasses(fragmentClasses).build()
        parser.parse("--second_str_option=bar")
        parser.setStarlarkOptions(
            com.google.common.collect.ImmutableMap.of<K?, V?>("//custom:flag", "hello"),
            com.google.common.collect.ImmutableSet.of<E?>()
        )

        assertThat(original.matches(parser)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun parsingResultMatchStarlarkOptionMissing() {
        val original: BuildOptions =
            BuildOptions.builder()
                .addStarlarkOption(Label.parseCanonicalUnchecked("//custom:flag1"), "hello")
                .build()

        val parser: OptionsParser = OptionsParser.builder().optionsClasses(BUILD_CONFIG_OPTIONS).build()
        parser.setStarlarkOptions(
            com.google.common.collect.ImmutableMap.of<K?, V?>("//custom:flag2", "foo"),
            com.google.common.collect.ImmutableSet.of<E?>()
        )

        assertThat(original.matches(parser)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun parsingResultMatchNullOption() {
        val original: BuildOptions = BuildOptions.of(BUILD_CONFIG_OPTIONS)

        val parser: OptionsParser = OptionsParser.builder().optionsClasses(BUILD_CONFIG_OPTIONS).build()
        parser.parse("--null_option=foo") // Note: null_option is null by default.

        assertThat(original.matches(parser)).isFalse()
    }

    @org.junit.Test
    fun nativeOptionsOrderedLexicographically() {
        val options1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Options.getDefaults(com.google.devtools.build.lib.analysis.config.BuildOptionsTest.DummyTestOptions::class.java)
        val options2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Options.getDefaults(SecondDummyTestOptions::class.java)

        val forward: BuildOptions =
            BuildOptions.builder().addFragmentOptions(options1).addFragmentOptions(options2).build()
        val backward: BuildOptions =
            BuildOptions.builder().addFragmentOptions(options2).addFragmentOptions(options1).build()

        assertThat(forward.getFragmentClasses())
            .isInOrder(BuildOptions.LEXICAL_FRAGMENT_OPTIONS_COMPARATOR)
        assertThat(backward.getFragmentClasses())
            .isInOrder(BuildOptions.LEXICAL_FRAGMENT_OPTIONS_COMPARATOR)
        assertThat(forward.getNativeOptions()).containsExactly(options1, options2).inOrder()
        assertThat(backward.getNativeOptions()).containsExactly(options1, options2).inOrder()
    }

    @org.junit.Test
    fun starlarkOptionsOrderedByLabel() {
        val label1: Label? = Label.parseCanonicalUnchecked("//pkg:option1")
        val label2: Label? = Label.parseCanonicalUnchecked("//pkg:option2")

        val forward: BuildOptions =
            BuildOptions.builder()
                .addStarlarkOption(label1, true)
                .addStarlarkOption(label2, false)
                .build()
        val backward: BuildOptions =
            BuildOptions.builder()
                .addStarlarkOption(label2, false)
                .addStarlarkOption(label1, true)
                .build()
        assertThat(forward.getStarlarkOptions()).containsExactly(label1, true, label2, false).inOrder()
        assertThat(backward.getStarlarkOptions())
            .containsExactly(label1, true, label2, false)
            .inOrder()
        assertThat(backward).isEqualTo(forward)
        assertThat(backward.checksum()).isEqualTo(forward.checksum())
    }

    @org.junit.Test
    fun listAndSetAreDifferent() {
        val label: Label? = Label.parseCanonicalUnchecked("//pkg:option")

        val optionsWithList: BuildOptions =
            BuildOptions.builder()
                .addStarlarkOption(label, com.google.common.collect.Lists.< E > newArrayList < E ? > ("a")).build()
        val optionsWithSet: BuildOptions =
            BuildOptions.builder()
                .addStarlarkOption(label, LinkedHashSet<E?>(com.google.common.collect.ImmutableList.of<E?>("a")))
                .build()

        assertThat(optionsWithList).isNotEqualTo(optionsWithSet)
        assertThat(optionsWithList.checksum()).isNotEqualTo(optionsWithSet.checksum())
    }

    @org.junit.Test
    fun emptyListDifferentFromListWithEmptyString() {
        val label: Label? = Label.parseCanonicalUnchecked("//pkg:option")

        val emptyListOptions: BuildOptions =
            BuildOptions.builder().addStarlarkOption(label, java.util.ArrayList<E?>()).build()
        val emptyStringListOptions: BuildOptions =
            BuildOptions.builder()
                .addStarlarkOption(label, com.google.common.collect.Lists.< E > newArrayList < E ? > ("")).build()

        assertThat(emptyListOptions).isNotEqualTo(emptyStringListOptions)
        assertThat(emptyListOptions.checksum()).isNotEqualTo(emptyStringListOptions.checksum())
    }

    @org.junit.Test
    fun emptySetDifferentFromSetWithEmptyString() {
        val label: Label? = Label.parseCanonicalUnchecked("//pkg:option")

        val emptySetOptions: BuildOptions =
            BuildOptions.builder().addStarlarkOption(label, LinkedHashSet<E?>()).build()
        val emptyStringSetOptions: BuildOptions =
            BuildOptions.builder()
                .addStarlarkOption(label, LinkedHashSet<E?>(com.google.common.collect.ImmutableList.of<E?>("")))
                .build()

        assertThat(emptySetOptions).isNotEqualTo(emptyStringSetOptions)
        assertThat(emptySetOptions.checksum()).isNotEqualTo(emptyStringSetOptions.checksum())
    }

    companion object {
        private val BUILD_CONFIG_OPTIONS: com.google.common.collect.ImmutableList<java.lang.Class<out FragmentOptions?>?> =
            com.google.common.collect.ImmutableList.of<E?>(com.google.devtools.build.lib.analysis.config.BuildOptionsTest.DummyTestOptions::class.java)

        private fun makeOptionsClassBuilder(): com.google.common.collect.ImmutableList.Builder<java.lang.Class<out FragmentOptions?>?> {
            return com.google.common.collect.ImmutableList.builder<java.lang.Class<out FragmentOptions?>?>().addAll(
                BUILD_CONFIG_OPTIONS
            )
        }
    }
}
