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
package com.google.devtools.build.lib.rules.config

import com.google.common.truth.Subject
import com.google.devtools.build.lib.actions.ActionLookupKey
import com.google.devtools.common.options.Option
import org.junit.Test

/** Tests for [ConfigSetting].  */
@RunWith(TestParameterInjector::class)
class ConfigSettingTest : BuildViewTestCase() {
    /** Extra options for this test.  */
    @OptionsClass
    abstract class DummyTestOptions : FragmentOptions() {
        @get:Option(
            name = "internal_option",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "super secret",
            metadataTags = [OptionMetadataTag.INTERNAL]
        )
        abstract val internalOption: String?

        @get:Option(
            name = "allow_multiple_option",
            defaultValue = "null",
            converter = CommaSeparatedOptionListConverter::class,
            allowMultiple = true,
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP]
        )
        abstract val allowMultipleOption: MutableList<String?>?

        @get:Option(
            name = "new_option_name",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "",
            oldName = "old_option_name"
        )
        abstract val optionWithOldName: String?

        @get:Option(
            name = "non_configurable_option",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "non-configurable",
            metadataTags = [OptionMetadataTag.NON_CONFIGURABLE]
        )
        abstract val nonConfigurableOption: String?
    }

    /** Test fragment.  */
    @RequiresOptions(options = [DummyTestOptions::class])
    class DummyTestOptionsFragment(buildOptions: BuildOptions?) : Fragment() {
        private val buildOptions: BuildOptions?

        init {
            this.buildOptions = buildOptions
        }

        // Getter required to satisfy AutoCodec.
        fun getBuildOptions(): BuildOptions? {
            return buildOptions
        }
    }

    override fun createRuleClassProvider(): ConfiguredRuleClassProvider {
        val builder: ConfiguredRuleClassProvider.Builder = Builder()
        TestRuleClassProvider.addStandardRules(builder)
        builder.addRuleDefinition(FeatureFlagSetterRule())
        builder.addConfigurationFragment(DummyTestOptionsFragment::class.java)
        return builder.build()
    }

    @Throws(Exception::class)
    private fun writeSimpleExample() {
        scratch.file(
            "pkg/BUILD",
            """
        config_setting(
            name = "foo",
            values = {
                "compilation_mode": "dbg",
                "stamp": "1",
            },
        )
        
        """.trimIndent()
        )
    }

    @Throws(Exception::class)
    private fun getConfigMatchingProvider(label: String?): ConfigMatchingProvider {
        return getConfiguredTarget(label).getProvider(ConfigMatchingProvider::class.java)
    }

    @Throws(Exception::class)
    private fun getConfigMatchingProviderResultAsBoolean(label: String?): Boolean {
        return forceConvertMatchResult(getConfigMatchingProvider(label).result())
    }

    /** Checks the behavior of [ConfigSetting.isUnderToolsPackage].  */
    @Test
    @Throws(Exception::class)
    fun isUnderToolsPackage() {
        val toolsRepo: RepositoryName? = RepositoryName.create("tools")
        // Subpackage of the tools package.
        assertThat(
            ConfigSetting.isUnderToolsPackage(
                Label.parseCanonicalUnchecked("@tools//tools/subpkg:foo"), toolsRepo
            )
        )
            .isTrue()
        // The tools package itself.
        assertThat(
            ConfigSetting.isUnderToolsPackage(
                Label.parseCanonicalUnchecked("@tools//tools:foo"), toolsRepo
            )
        )
            .isTrue()
        // The tools repo, but wrong package.
        assertThat(
            ConfigSetting.isUnderToolsPackage(
                Label.parseCanonicalUnchecked("@tools//nottools:foo"), toolsRepo
            )
        )
            .isFalse()
        // Not even the tools repo.
        assertThat(
            ConfigSetting.isUnderToolsPackage(
                Label.parseCanonicalUnchecked("@nottools//nottools:foo"), toolsRepo
            )
        )
            .isFalse()
        // A tools package but in the wrong repo.
        assertThat(
            ConfigSetting.isUnderToolsPackage(
                Label.parseCanonicalUnchecked("@nottools//tools:foo"), toolsRepo
            )
        )
            .isFalse()
    }

    /**
     * Tests that a config_setting only matches build configurations where *all* of
     * its flag specifications match.
     */
    @Test
    @Throws(Exception::class)
    fun matchingCriteria() {
        writeSimpleExample()

        // First flag mismatches:
        useConfiguration("-c", "opt", "--stamp")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//pkg:foo")).isFalse()

        // Second flag mismatches:
        useConfiguration("-c", "dbg", "--nostamp")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//pkg:foo")).isFalse()

        // Both flags mismatch:
        useConfiguration("-c", "opt", "--nostamp")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//pkg:foo")).isFalse()

        // Both flags match:
        useConfiguration("-c", "dbg", "--stamp")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//pkg:foo")).isTrue()
    }

    /**
     * Tests that [ConfigMatchingProvider.label] is correct.
     */
    @Test
    @Throws(Exception::class)
    fun labelGetter() {
        writeSimpleExample()
        assertThat(getConfigMatchingProvider("//pkg:foo").label())
            .isEqualTo(Label.parseCanonical("//pkg:foo"))
    }

    /**
     * Tests that rule analysis fails on unknown options.
     */
    @Test
    @Throws(Exception::class)
    fun unknownOption() {
        checkError(
            "foo", "badoption",
            "unknown option: 'not_an_option'",
            "config_setting(",
            "    name = 'badoption',",
            "    values = {'not_an_option': 'bar'})"
        )
    }

    /**
     * Tests that rule analysis fails on internal options.
     */
    @Test
    @Throws(Exception::class)
    fun internalOption() {
        checkError(
            "foo", "badoption",
            "unknown option: 'internal_option'",
            "config_setting(",
            "    name = 'badoption',",
            "    values = {'internal_option': 'bar'})"
        )
    }

    @Test
    @Throws(Exception::class)
    fun oldNameReference() {
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "match",
            values = {"old_option_name": "foo"},
        )
        
        """.trimIndent()
        )
        assertThat(getConfiguredTarget("//test:match")).isNotNull()
        assertNoEvents()
    }

    /**
     * Tests that rule analysis fails on invalid option values.
     */
    @Test
    @Throws(Exception::class)
    fun invalidOptionValue() {
        checkError(
            "foo", "badvalue",
            "Not a valid compilation mode: 'baz'",
            "config_setting(",
            "    name = 'badvalue',",
            "    values = {'compilation_mode': 'baz'})"
        )
    }

    /**
     * Tests that when the first option is valid but the config_setting doesn't match,
     * remaining options are still validity-checked.
     */
    @Test
    @Throws(Exception::class)
    fun invalidOptionFartherDown() {
        checkError(
            "foo", "badoption",
            "unknown option: 'not_an_option'",
            "config_setting(",
            "    name = 'badoption',",
            "    values = {",
            "        'compilation_mode': 'opt',",
            "        'not_an_option': 'bar',",
            "    })"
        )
    }

    /** Tests that None is not specifiable for a key's value.  */
    @Test
    @Throws(Exception::class)
    fun noneValueInSetting() {
        checkError(
            "foo",
            "none",
            "ERROR /workspace/foo/BUILD:1:15: //foo:none: "
                    + "expected value of type 'string' for dict value element, but got None (NoneType)",
            "config_setting(",
            "    name = 'none',",
            "    values = {\"none_value\": None})"
        )
    }

    /**
     * Tests that *some* settings (values or flag_values) must be specified.
     */
    @Test
    @Throws(Exception::class)
    fun emptySettings() {
        checkError(
            "foo",
            "empty",
            "in config_setting rule //foo:empty: "
                    + "Either values, flag_values or constraint_values must be specified and non-empty",
            "config_setting(",
            "    name = 'empty',",
            "    values = {})"
        )
    }

    /**
     * Tests matching on multi-value attributes with key=value entries (e.g. --define).
     */
    @Test
    @Throws(Exception::class)
    fun multiValueDict() {
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "match",
            values = {
                "define": "foo=bar",
            },
        )
        
        """.trimIndent()
        )

        useConfiguration("")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
        useConfiguration("--define", "foo=bar")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue()
        useConfiguration("--define", "foo=baz")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
        useConfiguration("--define", "foo=bar", "--define", "bar=baz")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue()
        useConfiguration("--define", "foo=bar", "--define", "bar=baz", "--define", "foo=nope")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
        useConfiguration("--define", "foo=nope", "--define", "bar=baz", "--define", "foo=bar")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun invalidDefineProducesError() {
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "match",
            values = {
                "define": "foo",  # Value should be "foo=<something>".
            },
        )
        
        """.trimIndent()
        )

        checkError(
            "//test:match", "Variable definitions must be in the form of a 'name=value' assignment"
        )
    }

    @Test
    @Throws(Exception::class)
    fun multipleDefines() {
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "match",
            define_values = {
                "foo1": "bar",
                "foo2": "baz",
            },
        )
        
        """.trimIndent()
        )

        useConfiguration("")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
        useConfiguration("--define", "foo1=bar")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
        useConfiguration("--define", "foo2=baz")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
        useConfiguration("--define", "foo1=bar", "--define", "foo2=baz")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue()
    }

    /**
     * Tests that for a multi-value dictionary, `values = { 'key': 'value' }` always refers
     * to a single map entry. Fancy syntax like `values = { 'key': 'value=1,key2=value2' }`
     * doesn't get around that.
     * 
     * 
     * This just verifies existing behavior, not explicitly desired behavior. We could enhance
     * options parsing to support multi-value settings if anyone ever wanted that.
     */
    @Test
    @Throws(Exception::class)
    fun multiValueDictSettingAlwaysSingleEntry() {
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "match",
            values = {
                "define": "foo=bar,baz=bat",
            },
        )
        
        """.trimIndent()
        )

        useConfiguration("")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
        useConfiguration("--define", "foo=bar")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
        useConfiguration("--define", "foo=bar", "--define", "baz=bat")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
        useConfiguration("--define", "foo=bar,baz=bat")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue()
        useConfiguration("--define", "makethis=a_superset", "--define", "foo=bar,baz=bat")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun definesCrossAttributes() {
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "match",
            define_values = {
                "b": "d",
            },
            values = {
                "define": "a=c",
            },
        )
        
        """.trimIndent()
        )

        useConfiguration("")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
        useConfiguration("--define", "a=c")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
        useConfiguration("--define", "b=d")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
        useConfiguration("--define", "a=c", "--define", "b=d")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue()
    }

    /**
     * Tests matching on multi-value attributes against single expected values: the actual list must
     * contain the expected value.
     */
    @Test
    @Throws(Exception::class)
    fun multiValueListSingleExpectedValue() {
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "match",
            values = {
                "copt": "-Dfoo",
            },
        )
        
        """.trimIndent()
        )

        useConfiguration("")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
        useConfiguration("--copt", "-Dfoo")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue()
        useConfiguration("--copt", "-Dbar")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
        useConfiguration("--copt", "-Dfoo", "--copt", "-Dbar")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue()
        useConfiguration("--copt", "-Dbar", "--copt", "-Dfoo")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue()
    }

    /**
     * Tests matching on multi-value flags against multiple expected values: the actual list must
     * contain all expected values (and possibly more).
     * 
     * 
     * This only works for flags that can parse multiple values in the same entry. Not all flags do
     * this: this varies according to each flag's definition. For example "--copt=a,b" produces a
     * single entry ["a,b"], while "--extra_platforms=a,b" produces ["a", "b"].
     */
    @Test
    @TestParameters(
        "{flags: [''], matchExpected: false}" // No flag set
        ,
        "{flags: ['--allow_multiple_option', 'one'], matchExpected: false}",
        "{flags: ['--allow_multiple_option', 'two'], matchExpected: false}",
        ("{flags: ['--allow_multiple_option', 'one', '--allow_multiple_option', 'two'], "
                + "matchExpected: true}"),
        ("{flags: ['--allow_multiple_option', 'two', '--allow_multiple_option', 'one'], "
                + "matchExpected: true}"),
        "{flags: ['--allow_multiple_option', 'one,two'], matchExpected: true}",
        "{flags: ['--allow_multiple_option', 'two,one'], matchExpected: true}",
        ("{flags: ['--allow_multiple_option', 'ten', '--allow_multiple_option', 'two', "
                + "'--allow_multiple_option', 'three', '--allow_multiple_option', 'one'], "
                + "matchExpected: true}")
    )
    @Throws(Exception::class)
    fun multiValueListMultipleExpectedValues(flags: MutableList<String?>, matchExpected: Boolean) {
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "match",
            values = {
                "allow_multiple_option": "one,two",  # This produces ["one", "two"]
            },
        )
        
        """.trimIndent()
        )

        useConfiguration(*flags.toArray<String?>(arrayOfNulls<String>(flags.size())))
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isEqualTo(matchExpected)
    }

    @Test
    @Throws(Exception::class)
    fun flagWithOldName_NoMatch() {
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "match",
            values = {
                "old_option_name": "different_setting",
            },
        )
        
        """.trimIndent()
        )
        useConfiguration("--new_option_name=is_set")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun flagWithOldName_Match() {
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "match",
            values = {
                "old_option_name": "is_set",
            },
        )
        
        """.trimIndent()
        )
        useConfiguration("--new_option_name=is_set")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue()
    }

    /**
     * Tests multi-value flags that don't support multiple values ****in the same instance**. See
     * comments on [.multiValueListMultipleExpectedValues] for details.
     ** */
    @Test
    @Throws(Exception::class)
    fun multiValueListSingleValueThatLooksLikeMultiple() {
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "match",
            values = {
                "copt": "one,two",  # This produces ["one,two"]
            },
        )
        
        """.trimIndent()
        )

        useConfiguration("")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
        useConfiguration("--copt", "one")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
        useConfiguration("--copt", "two")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
        useConfiguration("--copt", "one", "--copt", "two")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
        useConfiguration("--copt", "one,two", "--copt", "one")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue()
        useConfiguration("--copt", "two,one", "--copt", "one")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun selectForDefaultGrteTop() {
        scratchConfiguredTarget(
            "a",
            "a",
            "config_setting(name='cs', values={'grte_top': 'default'})",
            "filegroup(name='a', srcs=select({':cs': []}))"
        )
    }

    @Test
    @Throws(Exception::class)
    fun requiredConfigFragmentMatcher() {
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "match",
            values = {
                "copt": "-Dfoo",
                "javacopt": "-Dbar",
            },
        )
        
        """.trimIndent()
        )

        val target: Rule? = getTarget("//test:match") as Rule?
        assertThat(target.getRuleClassObject().getOptionReferenceFunction().apply(target))
            .containsExactly("copt", "javacopt")
    }

    @Test
    @Throws(Exception::class)
    fun matchesIfFlagValuesAndValuesBothMatch() {
        useConfiguration("--copt=-Dright", "--enforce_transitive_configs_for_config_feature_flag")
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "match",
            flag_values = {
                ":flag": "right",
            },
            transitive_configs = [":flag"],
            values = {
                "copt": "-Dright",
            },
        )

        config_feature_flag(
            name = "flag",
            allowed_values = ["right"],
            default_value = "right",
        )
        
        """.trimIndent()
        )
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun matchesIfFlagValuesMatchAndValuesAreEmpty() {
        useConfiguration("--copt=-Dright", "--enforce_transitive_configs_for_config_feature_flag")
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "match",
            flag_values = {
                ":flag": "right",
            },
            transitive_configs = [":flag"],
            values = {},
        )

        config_feature_flag(
            name = "flag",
            allowed_values = ["right"],
            default_value = "right",
        )
        
        """.trimIndent()
        )
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun matchesIfValuesMatchAndFlagValuesAreEmpty() {
        useConfiguration("--copt=-Dright")
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "match",
            flag_values = {},
            values = {
                "copt": "-Dright",
            },
        )
        
        """.trimIndent()
        )
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun doesNotMatchIfNeitherFlagValuesNorValuesMatches() {
        useConfiguration("--copt=-Dright", "--enforce_transitive_configs_for_config_feature_flag")
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "match",
            flag_values = {
                ":flag": "wrong",
            },
            transitive_configs = [":flag"],
            values = {
                "copt": "-Dwrong",
            },
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )
        
        """.trimIndent()
        )
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun doesNotMatchIfFlagValuesDoNotMatchAndValuesAreEmpty() {
        useConfiguration("--enforce_transitive_configs_for_config_feature_flag")
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "match",
            flag_values = {
                ":flag": "wrong",
            },
            transitive_configs = [":flag"],
            values = {},
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )
        
        """.trimIndent()
        )
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun doesNotMatchIfFlagValuesDoNotMatchButValuesDo() {
        useConfiguration("--copt=-Dright", "--enforce_transitive_configs_for_config_feature_flag")
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "match",
            flag_values = {
                ":flag": "wrong",
            },
            transitive_configs = [":flag"],
            values = {
                "copt": "-Dright",
            },
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )
        
        """.trimIndent()
        )
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun doesNotMatchIfValuesDoNotMatchAndFlagValuesAreEmpty() {
        useConfiguration("--copt=-Dright")
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "match",
            flag_values = {},
            values = {
                "copt": "-Dwrong",
            },
        )
        
        """.trimIndent()
        )
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun doesNotMatchIfValuesDoNotMatchButFlagValuesDo() {
        useConfiguration("--copt=-Dright", "--enforce_transitive_configs_for_config_feature_flag")
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "match",
            flag_values = {
                ":flag": "right",
            },
            transitive_configs = [":flag"],
            values = {
                "copt": "-Dwrong",
            },
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )
        
        """.trimIndent()
        )
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun doesNotMatchIfEvenOneFlagValueDoesNotMatch() {
        useConfiguration("--enforce_transitive_configs_for_config_feature_flag")
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "match",
            flag_values = {
                ":flag": "right",
                ":flag2": "bad",
            },
            transitive_configs = [
                ":flag",
                ":flag2",
            ],
            values = {},
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )

        config_feature_flag(
            name = "flag2",
            allowed_values = [
                "good",
                "bad",
            ],
            default_value = "good",
        )
        
        """.trimIndent()
        )
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun matchesIfNonDefaultIsSpecifiedAndFlagValueIsThatValue() {
        useConfiguration("--enforce_transitive_configs_for_config_feature_flag")
        scratch.file(
            "test/BUILD",
            """
        feature_flag_setter(
            name = "setter",
            exports_setting = ":match",
            flag_values = {":flag": "actual"},
            transitive_configs = [":flag"],
        )

        config_setting(
            name = "match",
            flag_values = {
                ":flag": "actual",
            },
            transitive_configs = [":flag"],
            values = {},
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "default",
                "actual",
            ],
            default_value = "default",
        )
        
        """.trimIndent()
        )
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:setter")).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun doesNotMatchIfDefaultIsSpecifiedAndFlagValueIsNotDefault() {
        useConfiguration("--enforce_transitive_configs_for_config_feature_flag")
        scratch.file(
            "test/BUILD",
            """
        feature_flag_setter(
            name = "setter",
            exports_setting = ":match",
            flag_values = {":flag": "actual"},
            transitive_configs = [":flag"],
        )

        config_setting(
            name = "match",
            flag_values = {
                ":flag": "default",
            },
            transitive_configs = [":flag"],
            values = {},
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "default",
                "actual",
            ],
            default_value = "default",
        )
        
        """.trimIndent()
        )
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:setter")).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun doesNotRefineSettingWithSameValuesAndSameFlagValuesAndSameConstraintValues() {
        useConfiguration(
            "--copt=-Dright",
            "--javacopt=-Dgood",
            "--enforce_transitive_configs_for_config_feature_flag"
        )
        scratch.file(
            "test/BUILD",
            """
        constraint_setting(name = "setting_a")

        constraint_value(
            name = "value_a",
            constraint_setting = "setting_a",
        )

        config_setting(
            name = "refined",
            constraint_values = [
                ":value_a",
            ],
            flag_values = {
                ":flag": "right",
                ":flag2": "good",
            },
            transitive_configs = [
                ":flag",
                ":flag2",
            ],
            values = {
                "copt": "-Dright",
                "javacopt": "-Dgood",
            },
        )

        config_setting(
            name = "other",
            constraint_values = [
                ":value_a",
            ],
            flag_values = {
                ":flag": "right",
                ":flag2": "good",
            },
            transitive_configs = [
                ":flag",
                ":flag2",
            ],
            values = {
                "copt": "-Dright",
                "javacopt": "-Dgood",
            },
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )

        config_feature_flag(
            name = "flag2",
            allowed_values = [
                "good",
                "bad",
            ],
            default_value = "good",
        )
        
        """.trimIndent()
        )
        assertThat(
            getConfigMatchingProvider("//test:refined")
                .refines(getConfigMatchingProvider("//test:other"))
        )
            .isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun doesNotRefineSettingWithDifferentValuesAndSameFlagValuesAndSameConstraintValues() {
        useConfiguration(
            "--copt=-Dright",
            "--javacopt=-Dgood",
            "--enforce_transitive_configs_for_config_feature_flag"
        )
        scratch.file(
            "test/BUILD",
            """
        constraint_setting(name = "setting_a")

        constraint_value(
            name = "value_a",
            constraint_setting = "setting_a",
        )

        config_setting(
            name = "refined",
            constraint_values = [
                ":value_a",
            ],
            flag_values = {
                ":flag": "right",
                ":flag2": "good",
            },
            transitive_configs = [
                ":flag",
                ":flag2",
            ],
            values = {
                "javacopt": "-Dgood",
            },
        )

        config_setting(
            name = "other",
            constraint_values = [
                ":value_a",
            ],
            flag_values = {
                ":flag": "right",
                ":flag2": "good",
            },
            transitive_configs = [
                ":flag",
                ":flag2",
            ],
            values = {
                "copt": "-Dright",
            },
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )

        config_feature_flag(
            name = "flag2",
            allowed_values = [
                "good",
                "bad",
            ],
            default_value = "good",
        )
        
        """.trimIndent()
        )
        assertThat(
            getConfigMatchingProvider("//test:refined")
                .refines(getConfigMatchingProvider("//test:other"))
        )
            .isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun doesNotRefineSettingWithSameValuesAndSameConstraintValuesAndDifferentFlagValues() {
        useConfiguration(
            "--copt=-Dright",
            "--javacopt=-Dgood",
            "--enforce_transitive_configs_for_config_feature_flag"
        )
        scratch.file(
            "test/BUILD",
            """
        constraint_setting(name = "setting_a")

        constraint_value(
            name = "value_a",
            constraint_setting = "setting_a",
        )

        config_setting(
            name = "refined",
            constraint_values = [
                ":value_a",
            ],
            flag_values = {
                ":flag": "right",
            },
            transitive_configs = [":flag"],
            values = {
                "copt": "-Dright",
                "javacopt": "-Dgood",
            },
        )

        config_setting(
            name = "other",
            constraint_values = [
                ":value_a",
            ],
            flag_values = {
                ":flag2": "good",
            },
            transitive_configs = [":flag2"],
            values = {
                "copt": "-Dright",
                "javacopt": "-Dgood",
            },
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )

        config_feature_flag(
            name = "flag2",
            allowed_values = [
                "good",
                "bad",
            ],
            default_value = "good",
        )
        
        """.trimIndent()
        )
        assertThat(
            getConfigMatchingProvider("//test:refined")
                .refines(getConfigMatchingProvider("//test:other"))
        )
            .isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun doesNotRefineSettingWithDifferentValuesAndDifferentFlagValuesAndDifferentConstraintValues() {
        useConfiguration(
            "--copt=-Dright",
            "--javacopt=-Dgood",
            "--enforce_transitive_configs_for_config_feature_flag"
        )
        scratch.file(
            "test/BUILD",
            """
        constraint_setting(name = "setting_a")

        constraint_value(
            name = "value_a",
            constraint_setting = "setting_a",
        )

        constraint_setting(name = "setting_b")

        constraint_value(
            name = "value_b",
            constraint_setting = "setting_b",
        )

        config_setting(
            name = "refined",
            constraint_values = [
                ":value_a",
            ],
            flag_values = {
                ":flag": "right",
            },
            transitive_configs = [":flag"],
            values = {
                "copt": "-Dright",
            },
        )

        config_setting(
            name = "other",
            constraint_values = [
                ":value_b",
            ],
            flag_values = {
                ":flag2": "good",
            },
            transitive_configs = [":flag2"],
            values = {
                "javacopt": "-Dgood",
            },
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )

        config_feature_flag(
            name = "flag2",
            allowed_values = [
                "good",
                "bad",
            ],
            default_value = "good",
        )
        
        """.trimIndent()
        )
        assertThat(
            getConfigMatchingProvider("//test:refined")
                .refines(getConfigMatchingProvider("//test:other"))
        )
            .isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun doesNotRefineSettingWithDifferentValuesAndSubsetFlagValuesAndSubsetConstraintValues() {
        useConfiguration(
            "--copt=-Dright",
            "--javacopt=-Dgood",
            "--enforce_transitive_configs_for_config_feature_flag"
        )
        scratch.file(
            "test/BUILD",
            """
        constraint_setting(name = "setting_a")

        constraint_value(
            name = "value_a",
            constraint_setting = "setting_a",
        )

        constraint_setting(name = "setting_b")

        constraint_value(
            name = "value_b",
            constraint_setting = "setting_b",
        )

        config_setting(
            name = "refined",
            constraint_values = [
                ":value_a",
                ":value_b",
            ],
            flag_values = {
                ":flag": "right",
                ":flag2": "good",
            },
            transitive_configs = [
                ":flag",
                ":flag2",
            ],
            values = {
                "copt": "-Dright",
            },
        )

        config_setting(
            name = "other",
            constraint_values = [
                ":value_a",
            ],
            flag_values = {
                ":flag": "right",
            },
            transitive_configs = [":flag"],
            values = {
                "javacopt": "-Dgood",
            },
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )

        config_feature_flag(
            name = "flag2",
            allowed_values = [
                "good",
                "bad",
            ],
            default_value = "good",
        )
        
        """.trimIndent()
        )
        assertThat(
            getConfigMatchingProvider("//test:refined")
                .refines(getConfigMatchingProvider("//test:other"))
        )
            .isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun doesNotRefineSettingWithSubsetValuesAndSubsetFlagValuesAndDifferentConstraintValues() {
        useConfiguration(
            "--copt=-Dright",
            "--javacopt=-Dgood",
            "--enforce_transitive_configs_for_config_feature_flag"
        )
        scratch.file(
            "test/BUILD",
            """
        constraint_setting(name = "setting_a")

        constraint_value(
            name = "value_a",
            constraint_setting = "setting_a",
        )

        constraint_setting(name = "setting_b")

        constraint_value(
            name = "value_b",
            constraint_setting = "setting_b",
        )

        config_setting(
            name = "refined",
            constraint_values = [
                ":value_a",
            ],
            flag_values = {
                ":flag": "right",
                ":flag2": "good",
            },
            transitive_configs = [
                ":flag",
                ":flag2",
            ],
            values = {
                "copt": "-Dright",
                "javacopt": "-Dgood",
            },
        )

        config_setting(
            name = "other",
            constraint_values = [
                ":value_b",
            ],
            flag_values = {
                ":flag": "right",
                ":flag2": "good",
            },
            transitive_configs = [
                ":flag",
                ":flag2",
            ],
            values = {
                "copt": "-Dright",
            },
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )

        config_feature_flag(
            name = "flag2",
            allowed_values = [
                "good",
                "bad",
            ],
            default_value = "good",
        )
        
        """.trimIndent()
        )
        assertThat(
            getConfigMatchingProvider("//test:refined")
                .refines(getConfigMatchingProvider("//test:other"))
        )
            .isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun doesNotRefineSettingWithSubsetValuesAndSubsetConstraintValuesAndDifferentFlagValues() {
        useConfiguration(
            "--copt=-Dright",
            "--javacopt=-Dgood",
            "--enforce_transitive_configs_for_config_feature_flag"
        )
        scratch.file(
            "test/BUILD",
            """
        constraint_setting(name = "setting_a")

        constraint_value(
            name = "value_a",
            constraint_setting = "setting_a",
        )

        constraint_setting(name = "setting_b")

        constraint_value(
            name = "value_b",
            constraint_setting = "setting_b",
        )

        config_setting(
            name = "refined",
            constraint_values = [
                ":value_a",
                ":value_b",
            ],
            flag_values = {
                ":flag": "right",
            },
            transitive_configs = [":flag"],
            values = {
                "copt": "-Dright",
                "javacopt": "-Dgood",
            },
        )

        config_setting(
            name = "other",
            constraint_values = [
                ":value_a",
            ],
            flag_values = {
                ":flag2": "good",
            },
            transitive_configs = [":flag2"],
            values = {
                "copt": "-Dright",
            },
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )

        config_feature_flag(
            name = "flag2",
            allowed_values = [
                "good",
                "bad",
            ],
            default_value = "good",
        )
        
        """.trimIndent()
        )
        assertThat(
            getConfigMatchingProvider("//test:refined")
                .refines(getConfigMatchingProvider("//test:other"))
        )
            .isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun doesNotRefineSettingIfThereIsNoOverlap() {
        useConfiguration(
            "--copt=-Dright",
            "--javacopt=-Dgood",
            "--enforce_transitive_configs_for_config_feature_flag"
        )
        scratch.file(
            "test/BUILD",
            """
        constraint_setting(name = "setting_a")

        constraint_value(
            name = "value_a",
            constraint_setting = "setting_a",
        )

        config_setting(
            name = "configA",
            flag_values = {
                ":flag": "right",
            },
            transitive_configs = [":flag"],
        )

        config_setting(
            name = "configB",
            constraint_values = [
                ":value_a",
            ],
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )
        
        """.trimIndent()
        )
        assertThat(
            getConfigMatchingProvider("//test:configA")
                .refines(getConfigMatchingProvider("//test:configB"))
        )
            .isFalse()
        assertThat(
            getConfigMatchingProvider("//test:configB")
                .refines(getConfigMatchingProvider("//test:configA"))
        )
            .isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun refinesSettingWithSubsetValuesAndSubsetConstraintValuesAndSameFlagValues() {
        useConfiguration(
            "--copt=-Dright",
            "--javacopt=-Dgood",
            "--enforce_transitive_configs_for_config_feature_flag"
        )
        scratch.file(
            "test/BUILD",
            """
        constraint_setting(name = "setting_a")

        constraint_value(
            name = "value_a",
            constraint_setting = "setting_a",
        )

        constraint_setting(name = "setting_b")

        constraint_value(
            name = "value_b",
            constraint_setting = "setting_b",
        )

        config_setting(
            name = "refined",
            constraint_values = [
                ":value_a",
                ":value_b",
            ],
            flag_values = {
                ":flag": "right",
                ":flag2": "good",
            },
            transitive_configs = [
                ":flag",
                ":flag2",
            ],
            values = {
                "copt": "-Dright",
                "javacopt": "-Dgood",
            },
        )

        config_setting(
            name = "other",
            constraint_values = [
                ":value_b",
            ],
            flag_values = {
                ":flag": "right",
                ":flag2": "good",
            },
            transitive_configs = [
                ":flag",
                ":flag2",
            ],
            values = {
                "copt": "-Dright",
            },
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )

        config_feature_flag(
            name = "flag2",
            allowed_values = [
                "good",
                "bad",
            ],
            default_value = "good",
        )
        
        """.trimIndent()
        )
        assertThat(
            getConfigMatchingProvider("//test:refined")
                .refines(getConfigMatchingProvider("//test:other"))
        )
            .isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun refinesSettingWithSameValuesAndSubsetFlagValuesAndSubsetConstraintValues() {
        useConfiguration(
            "--copt=-Dright",
            "--javacopt=-Dgood",
            "--enforce_transitive_configs_for_config_feature_flag"
        )
        scratch.file(
            "test/BUILD",
            """
        constraint_setting(name = "setting_a")

        constraint_value(
            name = "value_a",
            constraint_setting = "setting_a",
        )

        constraint_setting(name = "setting_b")

        constraint_value(
            name = "value_b",
            constraint_setting = "setting_b",
        )

        config_setting(
            name = "refined",
            constraint_values = [
                ":value_a",
                ":value_b",
            ],
            flag_values = {
                ":flag": "right",
                ":flag2": "good",
            },
            transitive_configs = [
                ":flag",
                ":flag2",
            ],
            values = {
                "copt": "-Dright",
                "javacopt": "-Dgood",
            },
        )

        config_setting(
            name = "other",
            constraint_values = [
                ":value_a",
            ],
            flag_values = {
                ":flag": "right",
            },
            transitive_configs = [":flag"],
            values = {
                "copt": "-Dright",
                "javacopt": "-Dgood",
            },
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )

        config_feature_flag(
            name = "flag2",
            allowed_values = [
                "good",
                "bad",
            ],
            default_value = "good",
        )
        
        """.trimIndent()
        )
        assertThat(
            getConfigMatchingProvider("//test:refined")
                .refines(getConfigMatchingProvider("//test:other"))
        )
            .isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun refinesSettingWithSubsetValuesAndSubsetFlagValuesAndConstraintValues() {
        useConfiguration(
            "--copt=-Dright",
            "--javacopt=-Dgood",
            "--enforce_transitive_configs_for_config_feature_flag"
        )
        scratch.file(
            "test/BUILD",
            """
        constraint_setting(name = "setting_a")

        constraint_value(
            name = "value_a",
            constraint_setting = "setting_a",
        )

        constraint_setting(name = "setting_b")

        constraint_value(
            name = "value_b",
            constraint_setting = "setting_b",
        )

        config_setting(
            name = "refined",
            constraint_values = [
                ":value_a",
                ":value_b",
            ],
            flag_values = {
                ":flag": "right",
                ":flag2": "good",
            },
            transitive_configs = [
                ":flag",
                ":flag2",
            ],
            values = {
                "copt": "-Dright",
                "javacopt": "-Dgood",
            },
        )

        config_setting(
            name = "other",
            constraint_values = [
                ":value_a",
            ],
            flag_values = {
                ":flag": "right",
            },
            transitive_configs = [":flag"],
            values = {
                "copt": "-Dright",
            },
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )

        config_feature_flag(
            name = "flag2",
            allowed_values = [
                "good",
                "bad",
            ],
            default_value = "good",
        )
        
        """.trimIndent()
        )
        assertThat(
            getConfigMatchingProvider("//test:refined")
                .refines(getConfigMatchingProvider("//test:other"))
        )
            .isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun refinesSettingWithSubsetConstraintValues() {
        scratch.file(
            "test/BUILD",
            """
        constraint_setting(name = "setting_a")

        constraint_value(
            name = "value_a",
            constraint_setting = "setting_a",
        )

        constraint_setting(name = "setting_b")

        constraint_value(
            name = "value_b",
            constraint_setting = "setting_b",
        )

        constraint_setting(name = "setting_c")

        constraint_value(
            name = "value_c",
            constraint_setting = "setting_c",
        )

        platform(
            name = "refined_platform",
            constraint_values = [
                ":value_a",
                ":value_b",
                ":value_c",
            ],
        )

        platform(
            name = "other_platform",
            constraint_values = [
                ":value_a",
                ":value_b",
            ],
        )

        config_setting(
            name = "refined",
            constraint_values = [
                ":value_a",
                ":value_b",
                ":value_c",
            ],
        )

        config_setting(
            name = "other",
            constraint_values = [
                ":value_a",
                ":value_b",
            ],
        )
        
        """.trimIndent()
        )
        useConfiguration("--platforms=//test:refined_platform")
        assertThat(
            getConfigMatchingProvider("//test:refined")
                .refines(getConfigMatchingProvider("//test:other"))
        )
            .isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun matchesAliasedFlagsInFlagValues() {
        useConfiguration("--enforce_transitive_configs_for_config_feature_flag")
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "alias_matcher",
            flag_values = {
                ":alias": "right",
            },
            transitive_configs = [":flag"],
        )

        alias(
            name = "alias",
            actual = "flag",
            transitive_configs = [":flag"],
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )
        
        """.trimIndent()
        )
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:alias_matcher")).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun aliasedFlagsAreCountedInRefining() {
        useConfiguration("--enforce_transitive_configs_for_config_feature_flag")
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "refined",
            flag_values = {
                ":alias": "right",
                ":flag2": "good",
            },
            transitive_configs = [
                ":flag",
                ":flag2",
            ],
        )

        config_setting(
            name = "other",
            flag_values = {
                ":flag": "right",
            },
            transitive_configs = [":flag"],
        )

        alias(
            name = "alias",
            actual = "flag",
            transitive_configs = [":flag"],
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )

        config_feature_flag(
            name = "flag2",
            allowed_values = [
                "good",
                "bad",
            ],
            default_value = "good",
        )
        
        """.trimIndent()
        )
        assertThat(
            getConfigMatchingProvider("//test:refined")
                .refines(getConfigMatchingProvider("//test:other"))
        )
            .isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun referencingSameFlagViaMultipleAliasesFails() {
        useConfiguration("--enforce_transitive_configs_for_config_feature_flag")
        checkError(
            "test",
            "multialias",
            "in flag_values attribute of config_setting rule //test:multialias: "
                    + "flag '//test:direct' referenced multiple times as ['//test:alias', '//test:direct']",
            "config_setting(",
            "    name = 'multialias',",
            "    flag_values = {",
            "        ':alias': 'right',",
            "        ':direct': 'right',",
            "    },",
            "    transitive_configs = [':direct'],",
            ")",
            "alias(",
            "    name = 'alias',",
            "    actual = 'direct',",
            "    transitive_configs = [':direct'],",
            ")",
            "config_feature_flag(",
            "    name = 'direct',",
            "    allowed_values = ['right', 'wrong'],",
            "    default_value = 'right',",
            ")"
        )
    }

    @Test
    @Throws(Exception::class)
    fun requiresValidValueForFlagValues() {
        useConfiguration("--enforce_transitive_configs_for_config_feature_flag")
        checkError(
            "test",
            "invalid_flag",
            ("in flag_values attribute of config_setting rule //test:invalid_flag: "
                    + "error while parsing user-defined configuration values: "
                    + "'invalid' is not a valid value for '//test:flag'"),
            "config_setting(",
            "    name = 'invalid_flag',",
            "    flag_values = {",
            "        ':flag': 'invalid',",
            "    },",
            "    transitive_configs = [':flag'])",
            "config_feature_flag(",
            "    name = 'flag',",
            "    allowed_values = ['right', 'valid'],",
            "    default_value = 'valid',",
            ")"
        )
    }

    @Test
    @Throws(Exception::class)
    fun usesAliasLabelWhenReportingErrorInFlagValues() {
        useConfiguration("--enforce_transitive_configs_for_config_feature_flag")
        checkError(
            "test",
            "invalid_flag",
            ("in flag_values attribute of config_setting rule //test:invalid_flag: "
                    + "error while parsing user-defined configuration values: "
                    + "'invalid' is not a valid value for '//test:alias'"),
            "config_setting(",
            "    name = 'invalid_flag',",
            "    flag_values = {",
            "        ':alias': 'invalid',",
            "    },",
            "    transitive_configs = [':flag'])",
            "alias(",
            "    name = 'alias',",
            "    actual = ':flag',",
            "    transitive_configs = [':flag'],",
            ")",
            "config_feature_flag(",
            "    name = 'flag',",
            "    allowed_values = ['right', 'valid'],",
            "    default_value = 'valid',",
            ")"
        )
    }

    @Test
    @Throws(Exception::class)
    fun buildsettings_matchesFromDefault() {
        scratch.file(
            "test/build_settings.bzl",
            """
        def _impl(ctx):
            return []

        string_flag = rule(implementation = _impl, build_setting = config.string(flag = True))
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "string_flag")

        config_setting(
            name = "match",
            flag_values = {
                ":cheese": "parmesan",
            },
        )

        string_flag(
            name = "cheese",
            build_setting_default = "parmesan",
        )
        
        """.trimIndent()
        )
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun buildsettings_matchesFromCommandLine() {
        scratch.file(
            "test/build_settings.bzl",
            """
        def _impl(ctx):
            return []

        string_flag = rule(implementation = _impl, build_setting = config.string(flag = True))
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "string_flag")

        config_setting(
            name = "match",
            flag_values = {
                ":cheese": "gouda",
            },
        )

        string_flag(
            name = "cheese",
            build_setting_default = "parmesan",
        )
        
        """.trimIndent()
        )

        useConfiguration("--//test:cheese=gouda")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue()
    }

    /**
     * Regression test to ensure that non-String typed build setting values are being properly
     * converted from Strings to their real type.
     */
    @Test
    @Throws(Exception::class)
    fun buildsettings_convertedType() {
        scratch.file(
            "test/build_settings.bzl",
            """
        def _impl(ctx):
            return []

        bool_flag = rule(implementation = _impl, build_setting = config.bool(flag = True))
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "bool_flag")

        config_setting(
            name = "match",
            flag_values = {
                ":cheese": "True",
            },
        )

        bool_flag(
            name = "cheese",
            build_setting_default = True,
        )
        
        """.trimIndent()
        )
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun buildsettings_doesntMatch() {
        scratch.file(
            "test/build_settings.bzl",
            """
        def _impl(ctx):
            return []

        string_flag = rule(implementation = _impl, build_setting = config.string(flag = True))
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "string_flag")

        config_setting(
            name = "match",
            flag_values = {
                ":cheese": "parmesan",
            },
        )

        string_flag(
            name = "cheese",
            build_setting_default = "parmesan",
        )
        
        """.trimIndent()
        )

        useConfiguration("--//test:cheese=gouda")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun buildsettings_badType() {
        scratch.file(
            "test/build_settings.bzl",
            """
        def _impl(ctx):
            return []

        int_flag = rule(implementation = _impl, build_setting = config.int(flag = True))
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "int_flag")

        config_setting(
            name = "match",
            flag_values = {
                ":wishes": "gouda",
            },
        )

        int_flag(
            name = "wishes",
            build_setting_default = 3,
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//test:match")
        assertContainsEvent("'gouda' cannot be converted to //test:wishes type int")
    }

    @Test
    @Throws(Exception::class)
    fun buildsettings_allowMultipleWorks() {
        scratch.file(
            "test/build_settings.bzl",
            """
        def _impl(ctx):
            return []

        string_flag = rule(
            implementation = _impl,
            build_setting = config.string(flag = True, allow_multiple = True),
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "string_flag")

        config_setting(
            name = "match",
            flag_values = {
                ":cheese": "pepperjack",
            },
        )

        string_flag(
            name = "cheese",
            build_setting_default = "gouda",
        )
        
        """.trimIndent()
        )
        useConfiguration("--//test:cheese=pepperjack", "--//test:cheese=brie")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun buildsettings_repeatableWorks() {
        scratch.file(
            "test/build_settings.bzl",
            """
        def _impl(ctx):
            return []

        string_list_flag = rule(
            implementation = _impl,
            build_setting = config.string_list(flag = True, repeatable = True),
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "string_list_flag")

        config_setting(
            name = "match",
            flag_values = {
                ":cheese": "pepperjack",
            },
        )

        string_list_flag(
            name = "cheese",
            build_setting_default = ["gouda"],
        )
        
        """.trimIndent()
        )

        useConfiguration("--//test:cheese=pepperjack", "--//test:cheese=pepperjack=brie")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun buildsettings_repeatableWithoutFlagErrors() {
        scratch.file(
            "test/build_settings.bzl",
            """
        def _impl(ctx):
            return []

        string_list_setting = rule(
            implementation = _impl,
            build_setting = config.string_list(repeatable = True),
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "string_list_setting")

        string_list_setting(
            name = "cheese",
            build_setting_default = ["gouda"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//test:cheese")
        assertContainsEvent("'repeatable' can only be set for a setting with 'flag = True'")
    }

    @Test
    @Throws(Exception::class)
    fun notBuildSettingOrFeatureFlag() {
        scratch.file(
            "test/rules.bzl",
            """
        def _impl(ctx):
            return DefaultInfo()

        default_info_rule = rule(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "default_info_rule")

        config_setting(
            name = "match",
            flag_values = {
                ":cheese": "gouda",
            },
        )

        default_info_rule(name = "cheese")
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//test:match")
        assertContainsEvent(
            "flag_values keys must be build settings or feature flags and //test:cheese is not"
        )
    }

    @Test
    @Throws(Exception::class)
    fun buildsettingsMatch_featureFlagsMatch() {
        useConfiguration("--enforce_transitive_configs_for_config_feature_flag")

        scratch.file(
            "test/build_settings.bzl",
            """
        def _impl(ctx):
            return []

        string_flag = rule(implementation = _impl, build_setting = config.string(flag = True))
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "string_flag")

        config_setting(
            name = "match",
            flag_values = {
                ":cheese": "parmesan",
                ":flag": "right",
            },
            transitive_configs = [":flag"],
        )

        string_flag(
            name = "cheese",
            build_setting_default = "parmesan",
        )

        config_feature_flag(
            name = "flag",
            allowed_values = ["right"],
            default_value = "right",
        )
        
        """.trimIndent()
        )
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun buildsettingsMatch_featureFlagsDontMatch() {
        useConfiguration("--enforce_transitive_configs_for_config_feature_flag")

        scratch.file(
            "test/build_settings.bzl",
            """
        def _impl(ctx):
            return []

        string_flag = rule(implementation = _impl, build_setting = config.string(flag = True))
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "string_flag")

        config_setting(
            name = "match",
            flag_values = {
                ":cheese": "parmesan",
                ":flag": "wrong",
            },
            transitive_configs = [":flag"],
        )

        string_flag(
            name = "cheese",
            build_setting_default = "parmesan",
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "right",
                "wrong",
            ],
            default_value = "right",
        )
        
        """.trimIndent()
        )
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun buildsettingsDontMatch_featureFlagsMatch() {
        useConfiguration("--enforce_transitive_configs_for_config_feature_flag")

        scratch.file(
            "test/build_settings.bzl",
            """
        def _impl(ctx):
            return []

        string_flag = rule(implementation = _impl, build_setting = config.string(flag = True))
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "string_flag")

        config_setting(
            name = "match",
            flag_values = {
                ":cheese": "gouda",
                ":flag": "right",
            },
            transitive_configs = [":flag"],
        )

        string_flag(
            name = "cheese",
            build_setting_default = "parmesan",
        )

        config_feature_flag(
            name = "flag",
            allowed_values = ["right"],
            default_value = "right",
        )
        
        """.trimIndent()
        )
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun constraintValue() {
        scratch.file(
            "test/BUILD",
            """
        constraint_setting(name = "notable_building")

        constraint_value(
            name = "empire_state",
            constraint_setting = "notable_building",
        )

        constraint_value(
            name = "space_needle",
            constraint_setting = "notable_building",
        )

        platform(
            name = "new_york_platform",
            constraint_values = [":empire_state"],
        )

        platform(
            name = "seattle_platform",
            constraint_values = [":space_needle"],
        )

        config_setting(
            name = "match",
            constraint_values = [":empire_state"],
        )
        
        """.trimIndent()
        )

        useConfiguration("--experimental_platforms=//test:new_york_platform")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue()
        useConfiguration("--experimental_platforms=//test:seattle_platform")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
        useConfiguration("")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun multipleConstraintValues() {
        scratch.file(
            "test/BUILD",
            """
        constraint_setting(name = "notable_building")

        constraint_value(
            name = "empire_state",
            constraint_setting = "notable_building",
        )

        constraint_setting(name = "museum")

        constraint_value(
            name = "cloisters",
            constraint_setting = "museum",
        )

        constraint_setting(name = "theme_park")

        constraint_value(
            name = "coney_island",
            constraint_setting = "theme_park",
        )

        platform(
            name = "manhattan_platform",
            constraint_values = [
                ":empire_state",
                ":cloisters",
            ],
        )

        platform(
            name = "museum_platform",
            constraint_values = [":cloisters"],
        )

        platform(
            name = "new_york_platform",
            constraint_values = [
                ":empire_state",
                ":cloisters",
                ":coney_island",
            ],
        )

        config_setting(
            name = "match",
            constraint_values = [
                ":empire_state",
                ":cloisters",
            ],
        )
        
        """.trimIndent()
        )
        useConfiguration("--experimental_platforms=//test:manhattan_platform")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue()
        useConfiguration("--experimental_platforms=//test:museum_platform")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
        useConfiguration("--experimental_platforms=//test:new_york_platform")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun definesAndConstraints() {
        scratch.file(
            "test/BUILD",
            """
        constraint_setting(name = "notable_building")

        constraint_value(
            name = "empire_state",
            constraint_setting = "notable_building",
        )

        constraint_value(
            name = "space_needle",
            constraint_setting = "notable_building",
        )

        platform(
            name = "new_york_platform",
            constraint_values = [":empire_state"],
        )

        platform(
            name = "seattle_platform",
            constraint_values = [":space_needle"],
        )

        config_setting(
            name = "match",
            constraint_values = [":empire_state"],
            define_values = {
                "b": "d",
            },
            values = {
                "define": "a=c",
            },
        )
        
        """.trimIndent()
        )

        useConfiguration(
            "--experimental_platforms=//test:new_york_platform", "--define", "a=c", "--define", "b=d"
        )
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue()
        useConfiguration("--experimental_platforms=//test:new_york_platform")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
        useConfiguration("--define", "a=c")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
        useConfiguration("--define", "a=c", "--experimental_platforms=//test:new_york_platform")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isFalse()
    }

    /**
     * Tests that a config_setting doesn't allow a constraint_values list with more than one
     * constraint value per constraint setting.
     */
    @Test
    @Throws(Exception::class)
    fun multipleValuesPerSetting() {
        checkError(
            "foo",
            "bad",
            ("in config_setting rule //foo:bad: "
                    + "Duplicate constraint values detected: "
                    + "constraint_setting //foo:notable_building has "
                    + "[//foo:empire_state, //foo:space_needle], "
                    + "constraint_setting //foo:museum has "
                    + "[//foo:moma, //foo:sam]"),
            "constraint_setting(name = 'notable_building')",
            "constraint_value(name = 'empire_state', constraint_setting = 'notable_building')",
            "constraint_value(name = 'space_needle', constraint_setting = 'notable_building')",
            "constraint_value(name = 'peace_arch', constraint_setting = 'notable_building')",
            "constraint_setting(name = 'museum')",
            "constraint_value(name = 'moma', constraint_setting = 'museum')",
            "constraint_value(name = 'sam', constraint_setting = 'museum')",
            "config_setting(",
            "    name = 'bad',",
            "    constraint_values = [",
            "        ':empire_state',",
            "        ':space_needle',",
            "        ':moma',",
            "        ':sam',",
            "    ],",
            ");"
        )
    }

    @Test
    @Throws(Exception::class)
    fun notAConstraintValue() {
        checkError(
            "test",
            "match",
            "//test:what_am_i is not a constraint_value",
            "genrule(",
            "    name = 'what_am_i',",
            "    srcs = [],",
            "    outs = ['the_answer'],",
            "    cmd = 'echo an eternal enigma > $@')",
            "config_setting(",
            "    name = 'match',",
            "    constraint_values = [':what_am_i'],",
            ")"
        )
    }

    @Throws(Exception::class)
    private fun getLicenses(label: String?): MutableSet<LicenseType?>? {
        val rule: Rule? = getTarget(label) as Rule?
        // There are two interfaces for retrieving a rule's license: from the Rule object and by
        // directly reading the "licenses" attribute. For config_setting both of these should always
        // be NONE. This method checks consistency between them.
        val fromRule: MutableSet<LicenseType?>? = rule.getLicense().getLicenseTypes()
        val fromAttribute: MutableSet<LicenseType?>? =
            RawAttributeMapper.of(rule).get("licenses", BuildType.LICENSE).getLicenseTypes()
        Truth.assertThat(fromRule).containsExactlyElementsIn(fromAttribute)
        return fromRule
    }

    /** Tests that default license behavior is unaffected.  */
    @Test
    @Throws(Exception::class)
    fun licensesDefault() {
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "match",
            values = {
                "copt": "-Dfoo",
            },
        )
        
        """.trimIndent()
        )

        useConfiguration("--copt", "-Dfoo")
        Truth.assertThat(getLicenses("//test:match")).containsExactly(LicenseType.NONE)
    }

    /** Tests that third-party doesn't require a license from config_setting.  */
    @Test
    @Throws(Exception::class)
    fun thirdPartyLicenseRequirement() {
        scratch.file(
            "third_party/test/BUILD",
            """
        config_setting(
            name = "match",
            values = {
                "copt": "-Dfoo",
            },
        )
        
        """.trimIndent()
        )

        useConfiguration("--copt", "-Dfoo")
        Truth.assertThat(getLicenses("//third_party/test:match")).containsExactly(LicenseType.NONE)
    }

    /** Tests that package-wide licenses are ignored by config_setting.  */
    @Test
    @Throws(Exception::class)
    fun packageLicensesIgnored() {
        scratch.file(
            "test/BUILD",
            """
        licenses(["restricted"])

        config_setting(
            name = "match",
            values = {
                "copt": "-Dfoo",
            },
        )
        
        """.trimIndent()
        )

        useConfiguration("--copt", "-Dfoo")
        Truth.assertThat(getLicenses("//test:match")).containsExactly(LicenseType.NONE)
    }

    /** Tests that rule-specific licenses are ignored by config_setting.  */
    @Test
    @Throws(Exception::class)
    fun ruleLicensesUsed() {
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "match",
            licenses = ["restricted"],
            values = {
                "copt": "-Dfoo",
            },
        )
        
        """.trimIndent()
        )

        useConfiguration("--copt", "-Dfoo")
        Truth.assertThat(getLicenses("//test:match")).containsExactly(LicenseType.NONE)
    }

    @Test
    @Throws(Exception::class)
    fun aliasedStarlarkFlag() {
        scratch.file(
            "test/flagdef.bzl",
            """
        def _impl(ctx):
            return []

        my_flag = rule(
            implementation = _impl,
            build_setting = config.string(flag = True),
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/BUILD",
            """
        load("//test:flagdef.bzl", "my_flag")

        my_flag(
            name = "flag",
            build_setting_default = "default",
        )

        alias(
            name = "alias",
            actual = ":flag",
        )

        config_setting(
            name = "alias_setting",
            flag_values = {":alias": "specified"},
        )
        
        """.trimIndent()
        )

        // Expect config_setting on an alias to pass completely through the alias to the underlying
        // flag it references. This means aliases model which flags trigger config_setting matches. This
        // keeps config_seting in sync with actual builds: if someone builds with --//foo:alias=1,
        // both the user and config_setting interpret it the same way even when the underlying flag
        // changes.
        useConfiguration("--//test:flag=specified")
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:alias_setting")).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun labelStarlarkFlag() {
        scratch.file(
            "test/BUILD",
            """

        label_flag(
            name = "my_flag",
            build_setting_default = "other_target",
        )

        genrule(
            name = "other_target",
            srcs = [],
            outs = ["other_target"],
            cmd = "echo other_target",
        )

        config_setting(
            name = "my_setting",
            flag_values = {":my_flag": "//test:other_target"},
        )
        
        """.trimIndent()
        )

        // While label_flag is technically an alias, we can't treat it the same way as a normal alias:
        // label_flag is by definition a flag, and therefore a valid config_setting input. But the
        // target it refers to isn't necessarily a flag (and for most practical uses won't be a flag).
        // So it doesn't make sense to treat it like an alias(), where config_setting setting matches
        // against the reference's value. So config_setting treats a label_flag's value like any
        // normal flag value and compares against it directly.
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:my_setting")).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun simpleStarlarkFlag() {
        scratch.file(
            "test/flagdef.bzl",
            """
        def _impl(ctx):
            return []

        my_flag = rule(
            implementation = _impl,
            build_setting = config.string(flag = True),
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:flagdef.bzl", "my_flag")

        my_flag(
            name = "flag",
            build_setting_default = "actual_flag_value",
        )

        config_setting(
            name = "matches",
            flag_values = {
                ":flag": "actual_flag_value",
            },
        )

        config_setting(
            name = "doesntmatch",
            flag_values = {
                ":flag": "other_flag_value",
            },
        )
        
        """.trimIndent()
        )
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:matches")).isTrue()
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:doesntmatch")).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun starlarkListFlagSingleValue() {
        // When a list-typed Starlark flag has value ["foo"], the config_setting's expected value "foo"
        // must match exactly.
        scratch.file(
            "test/flagdef.bzl",
            """
        def _impl(ctx):
            return []

        my_flag = rule(
            implementation = _impl,
            build_setting = config.string_list(flag = True),
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:flagdef.bzl", "my_flag")

        my_flag(
            name = "one_value_flag",
            build_setting_default = ["one"],
        )

        config_setting(
            name = "matches",
            flag_values = {
                ":one_value_flag": "one",
            },
        )

        config_setting(
            name = "doesntmatch",
            flag_values = {
                ":one_value_flag": "other",
            },
        )
        
        """.trimIndent()
        )
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:matches")).isTrue()
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:doesntmatch")).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun starlarkListFlagMultiValue() {
        // When a list-typed Starlark flag has value ["foo", "bar"], the config_setting's expected
        // value "foo" must match *any* entry in the list.
        scratch.file(
            "test/flagdef.bzl",
            """
        def _impl(ctx):
            return []

        my_flag = rule(
            implementation = _impl,
            build_setting = config.string_list(flag = True),
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:flagdef.bzl", "my_flag")

        my_flag(
            name = "two_value_flag",
            build_setting_default = [
                "one",
                "two",
            ],
        )

        config_setting(
            name = "matches_one",
            flag_values = {
                ":two_value_flag": "one",
            },
        )

        config_setting(
            name = "matches_two",
            flag_values = {
                ":two_value_flag": "two",
            },
        )

        config_setting(
            name = "doesntmatch",
            flag_values = {
                ":two_value_flag": "other",
            },
        )
        
        """.trimIndent()
        )
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:matches_one")).isTrue()
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:matches_two")).isTrue()
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:doesntmatch")).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun canOnlyMatchSingleValueInMultiValueFlags(@TestParameter repeatable: Boolean) {
        scratch.file(
            "test/build_settings.bzl",
            java.lang.String.format(
                """
            def _impl(ctx):
                return []

            string_list_flag = rule(
                implementation = _impl,
                build_setting = config.string_list(flag = True, repeatable = %s),
            )
            
            """.trimIndent(),
                if (repeatable) "True" else "False"
            )
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "string_list_flag")

        string_list_flag(
            name = "gouda",
            build_setting_default = ["smoked"],
        )

        config_setting(
            name = "match",
            flag_values = {
                ":gouda": "smoked,fresh",
            },
        )

        filegroup(
            name = "fg",
            srcs = select({
                ":match": [],
            }),
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler) // expect errors
        assertThat(getConfiguredTarget("//test:fg")).isNull()
        assertContainsEvent(
            "\"smoked,fresh\" not a valid value for flag //test:gouda. "
                    + "Only single, exact values are allowed"
        )
    }

    @Test
    @Throws(Exception::class)
    fun singleValueThatLooksLikeMultiValueIsOkay() {
        scratch.file(
            "test/build_settings.bzl",
            """
        def _impl(ctx):
            return []

        string_flag = rule(
            implementation = _impl,
            build_setting = config.string(flag = True),
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "string_flag")

        string_flag(
            name = "gouda",
            build_setting_default = "smoked,fresh",
        )

        config_setting(
            name = "match",
            flag_values = {
                ":gouda": "smoked,fresh",
            },
        )

        filegroup(
            name = "fg",
            srcs = select({
                ":match": [],
            }),
        )
        
        """.trimIndent()
        )
        assertThat(getConfiguredTarget("//test:fg")).isNotNull()
        assertNoEvents()
    }

    @Test
    @Throws(Exception::class)
    fun labelInValuesError() {
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "match",
            values = {"//foo:bar": "value"},
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler) // expect errors
        assertThat(getConfiguredTarget("//test:match")).isNull()
        assertContainsEvent(
            ("in values attribute of config_setting rule //test:match: '//foo:bar' is"
                    + " not a valid setting name, but appears to be a label. Did you mean to place it in"
                    + " flag_values instead?")
        )
    }

    @Test
    @TestParameters("{flag: cpu}", "{flag: host_cpu}", "{flag: crosstool_top}")
    @Throws(Exception::class)
    fun selectOnDeprecatedFlagEmitsWarning(flag: String?) {
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "match",
            values = {
              "%s": "//foo",
            },
        )
        
        """
                .trimIndent()
                .formatted(flag)
        )
        // empty --incompatible_disable_select_on to get the warning.
        useConfiguration("--incompatible_disable_select_on=")
        assertThat(getConfiguredTarget("//test:match")).isNotNull()
        assertContainsEvent(
            "select() on %s is deprecated. Use platform constraints instead".formatted(flag)
        )
    }

    @Test
    @TestParameters("{flag: cpu}", "{flag: host_cpu}", "{flag: crosstool_top}")
    @Throws(Exception::class)
    fun selectOnDisabledFlagFails(flag: String?) {
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "match",
            values = {
              "%s": "//foo",
            },
        )
        
        """
                .trimIndent()
                .formatted(flag)
        )
        useConfiguration("--incompatible_disable_select_on=%s".formatted(flag))
        reporter.removeHandler(failFastHandler)
        assertThat(getConfiguredTarget("//test:match")).isNull()
        assertContainsEvent(
            "in values attribute of config_setting rule //test:match: error while parsing configuration"
                    + " settings: select() on '%s' is not allowed. Use platform constraints instead"
                .formatted(flag)
        )
    }

    @Test
    @Throws(Exception::class)
    fun selectDisabledOnNonPlatformsFlag() {
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "match",
            values = {
              "compilation_mode": "opt",
            },
        )
        
        """.trimIndent()
        )
        useConfiguration("--incompatible_disable_select_on=compilation_mode")
        reporter.removeHandler(failFastHandler)
        assertThat(getConfiguredTarget("//test:match")).isNull()
        assertContainsEvent(
            "in values attribute of config_setting rule //test:match: error while parsing configuration"
                    + " settings: select() on 'compilation_mode' is not allowed."
        )
        assertDoesNotContainEvent("Use platform constraints instead")
    }

    @Test // If --foo has oldName --old_foo, let disabling flag selection have fine-grained control over
    // which name is permitted. For example, if we want to force all config_settings to use the
    // new name, we could set --incompatible_disable-select_on=old_foo.
    @TestParameters(
        ("{configSettingName: new_option_name, disabledName: new_option_name, expectSuccess:"
                + " false}"),
        ("{configSettingName: old_option_name, disabledName: old_option_name,"
                + " expectSuccess: false}"),
        "{configSettingName: new_option_name, disabledName: old_option_name," + " expectSuccess: true}",
        "{configSettingName: old_option_name, disabledName: new_option_name," + " expectSuccess: true}"
    )
    @Throws(Exception::class)
    fun selectOnDisabledFlagwithOldName(
        configSettingName: String?, disabledName: String?, expectSuccess: Boolean
    ) {
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "match",
            values = {
              "%s": "//foo",
            },
        )
        
        """
                .trimIndent()
                .formatted(configSettingName)
        )
        useConfiguration("--incompatible_disable_select_on=%s".formatted(disabledName))
        reporter.removeHandler(failFastHandler)

        if (expectSuccess) {
            assertThat(getConfiguredTarget("//test:match")).isNotNull()
            assertNoEvents()
        } else {
            assertThat(getConfiguredTarget("//test:match")).isNull()
            assertContainsEvent(
                ("in values attribute of config_setting rule //test:match: error while parsing"
                        + " configuration"
                        + " settings: select() on '%s' is not allowed.".formatted(disabledName))
            )
        }
    }

    @Test
    @TestParameters( // Alias --nativeform to --//test:myflag, config_setting matches --nativeform on default value:
        """
{
  valuesAttr: '"nativeform": "parmesan"',
  flagValuesAttr: '',
  config: ['--flag_alias=nativeform=//test:myflag'],
  expectMatch: true,
  expectedError: ''
}
"""
            .trimIndent() // Alias --nativeform to --//test:myflag, config_setting doesn't match --nativeform because it
        // expects a different value:
        , """
{
  valuesAttr: '"nativeform": "other_expected_value"',
  flagValuesAttr: '',
  config: ['--flag_alias=nativeform=//test:myflag'],
  expectMatch: false,
  expectedError: ''
}
"""
            .trimIndent() // Alias --nativeform to --//test:doesnt_exist, config_setting on --nativeform fails because the
        // target doesn't exist.
        , """
{
  valuesAttr: '"nativeform": "parmesan"',
  flagValuesAttr: '',
  config: ['--flag_alias=nativeform=//test:doesnt_exist'],
  expectMatch: false,
  expectedError: "target 'doesnt_exist' not declared"
}
"""
            .trimIndent() // Alias --nativeform not set, config_setting on --nativeform errors.
        , """
{
  valuesAttr: '"nativeform": "parmesan"',
  flagValuesAttr: '',
  config: [],
  expectMatch: false,
  expectedError: "unknown option: 'nativeform'"
}
"""
            .trimIndent() // config_setting reads both alias and actual flags and matches because their value is the same.
        , """
{
  valuesAttr: '"nativeform": "parmesan"',
  flagValuesAttr: '"//test:myflag": "parmesan"',
  config: ['--flag_alias=nativeform=//test:myflag'],
  expectMatch: true,
  expectedError: ""
}
"""
            .trimIndent() // config_setting reads both alias and actual flags and errors because of mismatching values.
        , """
{
  valuesAttr: '"nativeform": "parmesan"',
  flagValuesAttr: '"//test:myflag": "other_value"',
  config: ['--flag_alias=nativeform=//test:myflag'],
  expectMatch: false,
  expectedError: "Conflicting flag value expectations"
}
"""
            .trimIndent()
    )
    @Throws(Exception::class)
    fun flagAlias(
        valuesAttr: String?,
        flagValuesAttr: String?,
        config: MutableList<String?>,
        expectMatch: Boolean,
        expectedError: String
    ) {
        scratch.file(
            "test/build_settings.bzl",
            """
        def _impl(ctx):
            return []
        string_flag = rule(implementation = _impl, build_setting = config.string(flag = True))
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "string_flag")
        config_setting(
            name = "match",
            values = {%s},
            flag_values = {%s}
        )
        string_flag(
            name = "myflag",
            build_setting_default = "parmesan",
        )
        
        """
                .trimIndent()
                .formatted(valuesAttr, flagValuesAttr)
        )

        reporter.removeHandler(failFastHandler)
        useConfiguration(*config.toArray<String?>(arrayOfNulls<String>(0)))

        if (expectedError.isEmpty()) {
            Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isEqualTo(expectMatch)
        } else {
            assertThat(getConfiguredTarget("//test:match")).isNull()
            assertContainsEvent(expectedError)
        }
    }

    @Test
    @Throws(Exception::class)
    fun nonConfigurableOption() {
        checkError(
            "foo",
            "non_configurable_option",
            "select() on 'non_configurable_option' is not allowed.",
            """
        config_setting(
            name = "non_configurable_option",
            values = {"non_configurable_option": "foo"},
        )
        
        """.trimIndent()
        )
    }

    @Test
    @Throws(Exception::class)
    fun stringSet_singleValue_works(@TestParameter flagDefault: Boolean) {
        val matchValue = if (flagDefault) "default_value" else "cmd_val"
        scratch.file(
            "test/build_settings.bzl",
            """
        def _impl(ctx):
            return []

        string_set_flag = rule(
            implementation = _impl,
            build_setting = config.string_set(flag = True),
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            java.lang.String.format(
                """
            load("//test:build_settings.bzl", "string_set_flag")

            config_setting(
                name = "match",
                flag_values = {
                    ":my_flag": "%s",
                },
            )

            config_setting(
                name = "no_match",
                flag_values = {
                    ":my_flag": "another_value",
                },
            )

            string_set_flag(
                name = "my_flag",
                build_setting_default = set(["default_value"]),
            )
            
            """.trimIndent(),
                matchValue
            )
        )

        if (!flagDefault) {
            useConfiguration("--//test:my_flag=cmd_val")
        }
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match")).isTrue()
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:no_match")).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun stringSet_multipleValues_works(
        @TestParameter("default", "multiple", "repeatable") valuesSrc: String
    ) {
        val defaultValue = if (valuesSrc == "default") "'v2', 'v1', 'v3'" else "'default_value'"
        scratch.file(
            "test/build_settings.bzl",
            java.lang.String.format(
                """
            def _impl(ctx):
                return []

            string_set_flag = rule(
                implementation = _impl,
                build_setting = config.string_set(flag = True, repeatable = %s),
            )
            
            """.trimIndent(),
                if (valuesSrc == "repeatable") "True" else "False"
            )
        )
        scratch.file(
            "test/BUILD",
            java.lang.String.format(
                """
            load("//test:build_settings.bzl", "string_set_flag")

            config_setting(
                name = "match_2",
                flag_values = {
                    ":my_flag": "v2",
                },
            )

            config_setting(
                name = "match_3",
                flag_values = {
                    ":my_flag": "v3",
                },
            )

            config_setting(
                name = "match_4",
                flag_values = {
                    ":my_flag": "v4",
                },
            )

            string_set_flag(
                name = "my_flag",
                build_setting_default = set([%s]),
            )
            
            """.trimIndent(),
                defaultValue
            )
        )

        if (valuesSrc == "multiple") {
            useConfiguration("--//test:my_flag=v2,v1,v3")
        } else if (valuesSrc == "repeatable") {
            useConfiguration("--//test:my_flag=v3", "--//test:my_flag=v1", "--//test:my_flag=v2")
        }
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match_2")).isTrue()
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match_3")).isTrue()
        Truth.assertThat(getConfigMatchingProviderResultAsBoolean("//test:match_4")).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun canOnlyMatchSingleValueWithSetFlags(@TestParameter repeatable: Boolean) {
        scratch.file(
            "test/build_settings.bzl",
            java.lang.String.format(
                """
            def _impl(ctx):
                return []

            string_set_flag = rule(
                implementation = _impl,
                build_setting = config.string_set(flag = True, repeatable = %s),
            )
            
            """.trimIndent(),
                if (repeatable) "True" else "False"
            )
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "string_set_flag")

        string_set_flag(
            name = "my_flag",
            build_setting_default = set(["default_value"]),
        )

        config_setting(
            name = "match",
            flag_values = {
                ":my_flag": "v1,v2",
            },
        )

        filegroup(
            name = "fg",
            srcs = select({
                ":match": [],
            }),
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler) // expect errors
        assertThat(getConfiguredTarget("//test:fg")).isNull()
        assertContainsEvent(
            "\"v1,v2\" not a valid value for flag //test:my_flag. "
                    + "Only single, exact values are allowed"
        )
    }

    @Test
    @Throws(Exception::class)
    fun stampNotInSelectValues_stampSettingMarkerNotApplied(@TestParameter stampFlag: Boolean) {
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "match",
            values = {"compilation_mode": "opt"},
        )
        
        """.trimIndent()
        )

        useConfiguration("--stamp=" + stampFlag)

        val key: ActionLookupKey? = getConfiguredTarget("//test:match").getLookupKey()
        val node: NodeEntry =
            getSkyframeExecutor().getEvaluator().getExistingEntryAtCurrentlyEvaluatingVersion(key)
        assertThat(node.getDirectDeps()).doesNotContain(PrecomputedValue.STAMP_SETTING_MARKER.getKey())
    }

    @Test
    @Throws(Exception::class)
    fun stampInSelectValues_stampSettingMarkerAppliedIfStampFlag(
        @TestParameter stampFlag: Boolean
    ) {
        scratch.file(
            "test/BUILD",
            """
        config_setting(
            name = "match",
            values = {"stamp": "False"},
        )
        
        """.trimIndent()
        )

        useConfiguration("--stamp=" + stampFlag)

        val key: ActionLookupKey? = getConfiguredTarget("//test:match").getLookupKey()
        val node: NodeEntry =
            getSkyframeExecutor().getEvaluator().getExistingEntryAtCurrentlyEvaluatingVersion(key)
        if (stampFlag) {
            Subject.contains(PrecomputedValue.STAMP_SETTING_MARKER.getKey())
        } else {
            assertThat(node.getDirectDeps())
                .doesNotContain(PrecomputedValue.STAMP_SETTING_MARKER.getKey())
        }
    }

    companion object {
        private fun forceConvertMatchResult(result: ConfigMatchingProvider.MatchResult?): Boolean {
            if (result is Match) {
                return true
            } else if (result is NoMatch) {
                return false
            }
            throw IllegalStateException("Unexpected MatchResult: " + result)
        }
    }
}
