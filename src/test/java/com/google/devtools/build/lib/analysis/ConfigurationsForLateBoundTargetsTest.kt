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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.packages.Attribute.attr

/**
 * Tests <target></target>, sourceConfig> -> <dep></dep>, depConfig> relationships over latebound attributes.
 * 
 * 
 * Ideally these tests would be in [ ]. But that's a Skyframe test
 * (ConfiguredTargetFunction is a Skyframe function). And the Skyframe library doesn't know anything
 * about latebound attributes. So we need to place these properly under the analysis package.
 */
@RunWith(JUnit4::class)
class ConfigurationsForLateBoundTargetsTest : AnalysisTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun setupCustomLateBoundRules() {
        val builder: ConfiguredRuleClassProvider.Builder = Builder()
        TestRuleClassProvider.addStandardRules(builder)
        builder.addRuleDefinition(LateBoundSplitUtil.RULE_WITH_TEST_FRAGMENT)
        builder.addConfigurationFragment(com.google.devtools.build.lib.analysis.LateBoundSplitUtil.TestFragment::class.java)
        builder.addRuleDefinition(LATE_BOUND_DEP_RULE)
        useRuleClassProvider(builder.build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun lateBoundAttributeInTargetConfiguration() {
        scratch.file(
            "foo/BUILD",
            """
        rule_with_latebound_attr(
            name = "foo",
        )

        rule_with_test_fragment(
            name = "latebound_dep",
        )
        
        """.trimIndent()
        )
        update("//foo:foo")
        assertThat(getConfiguredTarget("//foo:foo", getTargetConfiguration())).isNotNull()
        val dep: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(
                SkyframeExecutorTestUtils.getExistingConfiguredTargets(
                    skyframeExecutor, Label.parseCanonical("//foo:latebound_dep")
                )
            )
        assertThat(getConfiguration(dep)).isNotEqualTo(getTargetConfiguration())
        Truth.assertThat(LateBoundSplitUtil.getOptions(getConfiguration(dep)).getFooFlag())
            .isEqualTo("PATCHED!")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun lateBoundAttributeInExecConfiguration() {
        scratch.file(
            "foo/BUILD",
            """
        genrule(
            name = "gen",
            srcs = [],
            outs = ["gen.out"],
            cmd = "echo hi > ${'$'}@",
            tools = [":foo"],
        )

        rule_with_latebound_attr(
            name = "foo",
        )

        rule_with_test_fragment(
            name = "latebound_dep",
        )
        
        """.trimIndent()
        )
        update("//foo:gen")
        assertThat(getConfiguredTarget("//foo:foo", getExecConfiguration())).isNotNull()
        // TODO(b/203203933) Fix LateboundDefault-s to return exec configuration
        val deps: com.google.common.collect.ImmutableList<ConfiguredTarget?> =
            com.google.common.collect.ImmutableList.copyOf<ConfiguredTarget?>(
                SkyframeExecutorTestUtils.getExistingConfiguredTargets(
                    skyframeExecutor, Label.parseCanonical("//foo:latebound_dep")
                )
            )
        Truth.assertThat(deps).hasSize(1)
        Truth.assertThat(
            deps.stream()
                .allMatch(java.util.function.Predicate { d: ConfiguredTarget? -> getConfiguration(d).isExecConfiguration() })
        ).isTrue()
    }

    companion object {
        private val CHANGE_FOO_FLAG_TRANSITION_FACTORY: TransitionFactory<AttributeTransitionData?> =
            object : TransitionFactory() {
                public override fun create(unused: AttributeTransitionData?): ConfigurationTransition? {
                    return object : PatchTransition() {
                        public override fun requiresOptionFragments(): com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?> {
                            return com.google.common.collect.ImmutableSet.of<E?>(com.google.devtools.build.lib.analysis.LateBoundSplitUtil.TestOptions::class.java)
                        }

                        public override fun patch(
                            options: BuildOptionsView,
                            eventHandler: com.google.devtools.build.lib.events.EventHandler?
                        ): BuildOptions {
                            val toOptions: BuildOptionsView = options.clone()
                            toOptions.get(com.google.devtools.build.lib.analysis.LateBoundSplitUtil.TestOptions::class.java)
                                .setFooFlag("PATCHED!")
                            return toOptions.underlying()
                        }
                    }
                }

                public override fun transitionType(): TransitionType {
                    return TransitionType.ATTRIBUTE
                }
            }

        /** Rule definition with a latebound dependency.  */
        private val LATE_BOUND_DEP_RULE: RuleDefinition = MockRule {
            MockRule.define(
                "rule_with_latebound_attr",
                MockRuleCustomBehavior { builder: RuleClass.Builder?, env: RuleDefinitionEnvironment? ->
                    builder
                        .add(
                            attr(":latebound_attr", LABEL)
                                .value(
                                    Attribute.LateBoundDefault.fromConstantForTesting(
                                        Label.parseCanonicalUnchecked("//foo:latebound_dep")
                                    )
                                )
                                .cfg(CHANGE_FOO_FLAG_TRANSITION_FACTORY)
                        )
                        .requiresConfigurationFragments(com.google.devtools.build.lib.analysis.LateBoundSplitUtil.TestFragment::class.java)
                })
        } as MockRule
    }
}
