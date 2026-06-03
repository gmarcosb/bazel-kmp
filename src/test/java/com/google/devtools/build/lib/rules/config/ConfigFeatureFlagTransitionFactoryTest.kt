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
package com.google.devtools.build.lib.rules.config

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider
import org.junit.Test

/** Tests for the ConfigFeatureFlagTransitionFactory.  */
@RunWith(JUnit4::class)
class ConfigFeatureFlagTransitionFactoryTest : BuildViewTestCase() {
    override fun createRuleClassProvider(): ConfiguredRuleClassProvider {
        val builder: ConfiguredRuleClassProvider.Builder =
            Builder().addRuleDefinition(FeatureFlagSetterRule())
        TestRuleClassProvider.addStandardRules(builder)
        return builder.build()
    }

    @Test
    @Throws(Exception::class)
    fun emptyTransition_returnsOriginalOptionsIfFragmentNotPresent() {
        val rule: Rule = scratchRule("a", "empty", "feature_flag_setter(name = 'empty', flag_values = {})")
        val transition: PatchTransition =
            ConfigFeatureFlagTransitionFactory("flag_values")
                .create(RuleTransitionData.create(rule, null, ""))

        val original: BuildOptions = optionsWithoutFlagFragment
        val converted: BuildOptions? =
            transition.patch(
                BuildOptionsView(original, transition.requiresOptionFragments()), eventCollector
            )

        assertThat(converted).isSameInstanceAs(original)
        assertThat(original.contains(ConfigFeatureFlagOptions::class.java)).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun populatedTransition_returnsOriginalOptionsIfFragmentNotPresent() {
        val rule: Rule =
            scratchRule(
                "a",
                "flag_setter_a",
                "feature_flag_setter(",
                "    name = 'flag_setter_a',",
                "    flag_values = {':flag': 'a'})",
                "config_feature_flag(",
                "    name = 'flag',",
                "    allowed_values = ['a', 'b'],",
                "    default_value = 'a')"
            )
        val transition: PatchTransition =
            ConfigFeatureFlagTransitionFactory("flag_values")
                .create(RuleTransitionData.create(rule, null, ""))

        val original: BuildOptions = optionsWithoutFlagFragment
        val converted: BuildOptions? =
            transition.patch(
                BuildOptionsView(original, transition.requiresOptionFragments()), eventCollector
            )

        assertThat(converted).isSameInstanceAs(original)
        assertThat(original.contains(ConfigFeatureFlagOptions::class.java)).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun emptyTransition_returnsClearedOptionsIfFragmentPresent() {
        val rule: Rule = scratchRule("a", "empty", "feature_flag_setter(name = 'empty', flag_values = {})")
        val transition: PatchTransition =
            ConfigFeatureFlagTransitionFactory("flag_values")
                .create(RuleTransitionData.create(rule, null, ""))
        val originalFlagMap: MutableMap<Label?, String?> =
            ImmutableMap.of<K?, V?>(Label.parseCanonical("//a:flag"), "value")

        val original: BuildOptions = getOptionsWithFlagFragment(originalFlagMap)
        val converted: BuildOptions =
            transition.patch(
                BuildOptionsView(original, transition.requiresOptionFragments()), eventCollector
            )

        assertThat(converted).isNotSameInstanceAs(original)
        assertThat(original.getStarlarkOptions())
            .containsExactlyEntriesIn(convertToFeatureFlagValues(originalFlagMap))
        assertThat(converted.getStarlarkOptions()).isEmpty()
    }

    @Test
    @Throws(Exception::class)
    fun populatedTransition_setsOptionsAndClearsNonPresentOptionsIfFragmentPresent() {
        val rule: Rule =
            scratchRule(
                "a",
                "flag_setter_a",
                "feature_flag_setter(",
                "    name = 'flag_setter_a',",
                "    flag_values = {':flag': 'a'})",
                "config_feature_flag(",
                "    name = 'flag',",
                "    allowed_values = ['a', 'b'],",
                "    default_value = 'a')"
            )
        val transition: PatchTransition =
            ConfigFeatureFlagTransitionFactory("flag_values")
                .create(RuleTransitionData.create(rule, null, ""))
        val originalFlagMap: MutableMap<Label?, String?> =
            ImmutableMap.of<K?, V?>(Label.parseCanonical("//a:old"), "value")
        val expectedFlagMap: MutableMap<Label?, String?> =
            ImmutableMap.of<K?, V?>(Label.parseCanonical("//a:flag"), "a")

        val original: BuildOptions = getOptionsWithFlagFragment(originalFlagMap)
        val converted: BuildOptions =
            transition.patch(
                BuildOptionsView(original, transition.requiresOptionFragments()), eventCollector
            )

        assertThat(converted).isNotSameInstanceAs(original)
        assertThat(original.getStarlarkOptions())
            .containsExactlyEntriesIn(convertToFeatureFlagValues(originalFlagMap))
        assertThat(converted.getStarlarkOptions())
            .containsExactlyEntriesIn(convertToFeatureFlagValues(expectedFlagMap))
    }

    @Test
    @Throws(Exception::class)
    fun transition_equalsTester() {
        scratch.file(
            "a/BUILD",
            """
        filegroup(
            name = "not_a_flagsetter",
            srcs = [],
        )

        feature_flag_setter(
            name = "empty",
            flag_values = {},
        )

        feature_flag_setter(
            name = "empty2",
            flag_values = {},
        )

        feature_flag_setter(
            name = "flag_setter_a",
            flag_values = {":flag": "a"},
        )

        feature_flag_setter(
            name = "flag_setter_a2",
            flag_values = {":flag": "a"},
        )

        feature_flag_setter(
            name = "flag_setter_b",
            flag_values = {":flag": "b"},
        )

        feature_flag_setter(
            name = "flag2_setter",
            flag_values = {":flag2": "a"},
        )

        feature_flag_setter(
            name = "both_setter",
            flag_values = {
                ":flag": "a",
                ":flag2": "a",
            },
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "a",
                "b",
            ],
            default_value = "a",
        )

        config_feature_flag(
            name = "flag2",
            allowed_values = [
                "a",
                "b",
            ],
            default_value = "a",
        )
        
        """.trimIndent()
        )

        val nonflag: Rule? = getTarget("//a:not_a_flagsetter") as Rule?
        val empty: Rule? = getTarget("//a:empty") as Rule?
        val empty2: Rule? = getTarget("//a:empty2") as Rule?
        val flagSetterA: Rule? = getTarget("//a:flag_setter_a") as Rule?
        val flagSetterA2: Rule? = getTarget("//a:flag_setter_a2") as Rule?
        val flagSetterB: Rule? = getTarget("//a:flag_setter_b") as Rule?
        val flag2Setter: Rule? = getTarget("//a:flag2_setter") as Rule?
        val bothSetter: Rule? = getTarget("//a:both_setter") as Rule?

        val factory: ConfigFeatureFlagTransitionFactory =
            ConfigFeatureFlagTransitionFactory("flag_values")
        val factory2: ConfigFeatureFlagTransitionFactory =
            ConfigFeatureFlagTransitionFactory("flag_values")

        EqualsTester()
            .addEqualityGroup( // transition for non flags target
                factory.create(RuleTransitionData.create(nonflag, null, "")), NoTransition.INSTANCE
            )
            .addEqualityGroup( // transition with empty map
                factory.create(
                    RuleTransitionData.create(
                        empty,
                        null,
                        ""
                    )
                ),  // transition produced by same factory on same rule
                factory.create(
                    RuleTransitionData.create(
                        empty,
                        null,
                        ""
                    )
                ),  // transition produced by similar factory on same rule
                factory2.create(
                    RuleTransitionData.create(
                        empty,
                        null,
                        ""
                    )
                ),  // transition produced by same factory on similar rule
                factory.create(
                    RuleTransitionData.create(
                        empty2,
                        null,
                        ""
                    )
                ),  // transition produced by similar factory on similar rule
                factory2.create(RuleTransitionData.create(empty2, null, ""))
            )
            .addEqualityGroup( // transition with flag -> a
                factory.create(RuleTransitionData.create(flagSetterA, null, "")),  // same map, different rule
                factory.create(RuleTransitionData.create(flagSetterA2, null, "")),  // same map, different factory
                factory2.create(RuleTransitionData.create(flagSetterA, null, ""))
            )
            .addEqualityGroup( // transition with flag set to different value
                factory.create(RuleTransitionData.create(flagSetterB, null, ""))
            )
            .addEqualityGroup( // transition with different flag set to same value
                factory.create(RuleTransitionData.create(flag2Setter, null, ""))
            )
            .addEqualityGroup( // transition with more flags set
                factory.create(RuleTransitionData.create(bothSetter, null, ""))
            )
            .testEquals()
    }

    @Test
    @Throws(Exception::class)
    fun factory_equalsTester() {
        EqualsTester()
            .addEqualityGroup(
                ConfigFeatureFlagTransitionFactory("flag_values"),
                ConfigFeatureFlagTransitionFactory("flag_values")
            )
            .addEqualityGroup(ConfigFeatureFlagTransitionFactory("other_flag_values"))
            .testEquals()
    }

    companion object {
        @get:Throws(Exception::class)
        private val optionsWithoutFlagFragment: BuildOptions
            get() = BuildOptions.of(ImmutableList.of<E?>(CoreOptions::class.java))

        @Throws(Exception::class)
        private fun getOptionsWithFlagFragment(values: MutableMap<Label?, String?>?): BuildOptions {
            return FeatureFlagValue.replaceFlagValues(
                BuildOptions.of(ImmutableList.of<E?>(CoreOptions::class.java, ConfigFeatureFlagOptions::class.java)),
                values
            )
        }

        @Throws(Exception::class)
        private fun convertToFeatureFlagValues(values: MutableMap<Label?, String?>?): ImmutableMap<Label?, Any?> {
            return getOptionsWithFlagFragment(values).getStarlarkOptions()
        }
    }
}
