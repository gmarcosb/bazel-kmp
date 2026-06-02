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
package com.google.devtools.build.lib.analysis.select

import com.google.common.base.Joiner
import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import com.google.devtools.build.lib.packages.Attribute.attr
import org.junit.Test

/**
 * Unit tests for [AggregatingAttributeMapper].
 */
@RunWith(JUnit4::class)
class AggregatingAttributeMapperTest : AbstractAttributeMapperTest() {
    override fun createRuleClassProvider(): ConfiguredRuleClassProvider {
        val builder: ConfiguredRuleClassProvider.Builder =
            Builder()
                .addRuleDefinition(RULE_WITH_DEFAULT)
                .addRuleDefinition(RULE_WITH_COMPUTED_DEFAULT)
        TestRuleClassProvider.addStandardRules(builder)
        return builder.build()
    }

    override fun createMapper(rule: Rule?): AbstractAttributeMapper {
        // Run AbstractAttributeMapper tests through an AggregatingAttributeMapper.
        return AggregatingAttributeMapper.of(rule)
    }

    /**
     * Tests that [AggregatingAttributeMapper.visitAttribute] returns an attribute's sole value
     * when declared directly (i.e. not as a configurable dict).
     */
    @Test
    @Throws(Exception::class)
    fun testGetPossibleValuesDirectAttribute() {
        val rule: Rule? =
            scratchRule(
                "a",
                "myrule",
                """
            load('//test_defs:foo_binary.bzl', 'foo_binary')
            foo_binary(
                name = "myrule",
                srcs = ["a.sh"],
            )
            
            """.trimIndent()
            )
        assertThat(AggregatingAttributeMapper.of(rule).visitAttribute("srcs", BuildType.LABEL_LIST))
            .containsExactly(ImmutableList.of<E?>(Label.parseCanonicalUnchecked("//a:a.sh")))
    }

    /**
     * Tests that [AggregatingAttributeMapper.visitAttribute] returns every possible value that
     * a configurable attribute can resolve to.
     */
    @Test
    @Throws(Exception::class)
    fun testGetPossibleValuesConfigurableAttribute() {
        val rule: Rule? =
            scratchRule(
                "a",
                "myrule",
                """
            load('//test_defs:foo_binary.bzl', 'foo_binary')
            foo_binary(
                name = "myrule",
                srcs = select({
                    "//conditions:a": ["a.sh"],
                    "//conditions:b": ["b.sh"],
                    "//conditions:default": ["default.sh"],
                 })
            )
            
            """.trimIndent()
            )
        assertThat(AggregatingAttributeMapper.of(rule).visitAttribute("srcs", BuildType.LABEL_LIST))
            .containsExactly(
                ImmutableList.of<E?>(Label.parseCanonicalUnchecked("//a:a.sh")),
                ImmutableList.of<E?>(Label.parseCanonicalUnchecked("//a:b.sh")),
                ImmutableList.of<E?>(Label.parseCanonicalUnchecked("//a:default.sh"))
            )
    }

    @Test
    @Throws(Exception::class)
    fun testGetPossibleValuesWithConcatenatedSelects() {
        val rule: Rule? =
            scratchRule(
                "a",
                "myrule",
                """
            load('//test_defs:foo_binary.bzl', 'foo_binary')
            foo_binary(
                name = "myrule",
                srcs = select({
                    "//conditions:a1": ["a1.sh"],
                    "//conditions:b1": ["b1.sh"],
                }) + select({
                    "//conditions:a2": ["a2.sh"],
                    "//conditions:b2": ["b2.sh"],
                }),
            )
            
            """.trimIndent()
            )
        assertThat(AggregatingAttributeMapper.of(rule).visitAttribute("srcs", BuildType.LABEL_LIST))
            .containsExactly(
                ImmutableList.of<E?>(
                    Label.parseCanonicalUnchecked("//a:a1.sh"),
                    Label.parseCanonicalUnchecked("//a:a2.sh")
                ),
                ImmutableList.of<E?>(
                    Label.parseCanonicalUnchecked("//a:a1.sh"),
                    Label.parseCanonicalUnchecked("//a:b2.sh")
                ),
                ImmutableList.of<E?>(
                    Label.parseCanonicalUnchecked("//a:b1.sh"),
                    Label.parseCanonicalUnchecked("//a:a2.sh")
                ),
                ImmutableList.of<E?>(
                    Label.parseCanonicalUnchecked("//a:b1.sh"),
                    Label.parseCanonicalUnchecked("//a:b2.sh")
                )
            )
    }

    /**
     * Given a large number of selects, we expect better than the naive exponential performance from
     * evaluating select1 x select2 x select3 x ...
     */
    @Test
    @Throws(Exception::class)
    fun testGetPossibleValuesWithManySelects() {
        val pattern = " + select({'//conditions:a1': '%c', '//conditions:a2': '%s'})"
        val ruleDef = StringBuilder()
        ruleDef.append("genrule(name = 'gen', srcs = [], outs = ['gen.out'], cmd = ''")
        for (c in "abcdefghijklmnopqrstuvwxyz".toCharArray()) {
            ruleDef.append(String.format(pattern, c, c.uppercaseChar()))
        }
        ruleDef.append(")")
        val rule: Rule? = scratchRule("a", "gen", ruleDef.toString())
        // Naive evaluation would visit 2^26 cases and either overflow memory or timeout the test.
        assertThat(AggregatingAttributeMapper.of(rule).visitAttribute("cmd", Type.STRING))
            .containsExactly("abcdefghijklmnopqrstuvwxyz", "ABCDEFGHIJKLMNOPQRSTUVWXYZ")
    }

    @Test
    @Throws(Exception::class)
    fun testGetPossibleValuesWithMultipleSelectsWithOverlappingConditions() {
        val rule: Rule? =
            scratchRule(
                "a",
                "myrule",
                """
            load('//test_defs:foo_binary.bzl', 'foo_binary')
            foo_binary(
                name = "myrule",
                # Even though this combination seems invalid it's
                # allowed due to select specialization.
                srcs = select({
                    "//conditions:x": ["x1.sh"],
                }) + select({
                    "//conditions:y": ["y1.sh"],
                }) + select({
                    "//conditions:x": ["x2.sh"],
                    "//conditions:y": ["y2.sh"],
                    "//conditions:z": ["z2.sh"],
                })
            )
            
            """.trimIndent()
            )
        assertThat(AggregatingAttributeMapper.of(rule).visitAttribute("srcs", BuildType.LABEL_LIST))
            .containsExactly(
                ImmutableList.of<E?>(
                    Label.parseCanonicalUnchecked("//a:x1.sh"),
                    Label.parseCanonicalUnchecked("//a:y1.sh"),
                    Label.parseCanonicalUnchecked("//a:x2.sh")
                ),
                ImmutableList.of<E?>(
                    Label.parseCanonicalUnchecked("//a:x1.sh"),
                    Label.parseCanonicalUnchecked("//a:y1.sh"),
                    Label.parseCanonicalUnchecked("//a:y2.sh")
                ),
                ImmutableList.of<E?>(
                    Label.parseCanonicalUnchecked("//a:x1.sh"),
                    Label.parseCanonicalUnchecked("//a:y1.sh"),
                    Label.parseCanonicalUnchecked("//a:z2.sh")
                )
            )
    }

    /**
     * Tests that, on rule visitation, [AggregatingAttributeMapper] visits *every* possible
     * value in a configurable attribute (including configuration key labels).
     */
    @Test
    @Throws(Exception::class)
    fun testVisitationConfigurableAttribute() {
        val rule: Rule? =
            scratchRule(
                "a",
                "myrule",
                """
            load('//test_defs:foo_binary.bzl', 'foo_binary')
            foo_binary(
                name = "myrule",
                srcs = select({
                    "//conditions:a": ["a.sh"],
                    "//conditions:b": ["b.sh"],
                    "//conditions:default": ["default.sh"],
                }),
            )
            
            """.trimIndent()
            )

        Truth.assertThat(
            AbstractAttributeMapperTest.Companion.getLabelsForAttribute(
                AggregatingAttributeMapper.of(rule),
                "srcs"
            )
        )
            .containsExactlyElementsIn(
                ImmutableList.of<String?>(
                    "//a:a.sh", "//a:b.sh", "//a:default.sh", "//conditions:a", "//conditions:b"
                )
            )
    }

    @Test
    @Throws(Exception::class)
    fun testGetReachableLabels() {
        val rule: Rule? =
            scratchRule(
                "x",
                "main",
                """
            load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
            cc_binary(
                name = "main",
                srcs = select({
                    "//conditions:a": ["a.cc"],
                    "//conditions:b": ["b.cc"],
                }) + [
                    "always.cc",
                ] + select({
                    "//conditions:c": ["c.cc"],
                    "//conditions:d": ["d.cc"],
                    "//conditions:default": ["default.cc"],
                }),
            )
            
            """.trimIndent()
            )

        val valueLabels: ImmutableList<Label?> =
            ImmutableList.of<E?>(
                Label.parseCanonicalUnchecked("//x:a.cc"),
                Label.parseCanonicalUnchecked("@//x:b.cc"),
                Label.parseCanonicalUnchecked("//x:always.cc"),
                Label.parseCanonicalUnchecked("@//x:c.cc"),
                Label.parseCanonicalUnchecked("//x:d.cc"),
                Label.parseCanonicalUnchecked("@//x:default.cc")
            )
        val keyLabels: ImmutableList<Label?> =
            ImmutableList.of<E?>(
                Label.parseCanonicalUnchecked("@//conditions:a"),
                Label.parseCanonicalUnchecked("@//conditions:b"),
                Label.parseCanonicalUnchecked("@//conditions:c"),
                Label.parseCanonicalUnchecked("@//conditions:d")
            )

        val mapper: AggregatingAttributeMapper = AggregatingAttributeMapper.of(rule)
        assertThat(mapper.getReachableLabels("srcs", true))
            .containsExactlyElementsIn(Iterables.< T > concat < T ? > (valueLabels, keyLabels))
        assertThat(mapper.getReachableLabels("srcs", false)).containsExactlyElementsIn(valueLabels)
    }

    @Test
    @Throws(Exception::class)
    fun testVisitationWithDefaultValues() {
        val rule: Rule? =
            scratchRule(
                "a",
                "myrule",
                "rule_with_default(name = 'myrule',",
                "    attribute = select({",
                "        '//conditions:a': None,",
                "    }))"
            )

        Truth.assertThat(
            AbstractAttributeMapperTest.Companion.getLabelsForAttribute(
                AggregatingAttributeMapper.of(rule),
                "attribute"
            )
        )
            .containsExactly("//conditions:a", "//default:value")
    }

    @Test
    @Throws(Exception::class)
    fun testGetReachableLabelsWithDefaultValues() {
        val rule: Rule? =
            scratchRule(
                "a",
                "myrule",
                """
            rule_with_default(
                name = "myrule",
                attribute = select({
                    "//conditions:a": None,
                }),
            )
            
            """.trimIndent()
            )

        val mapper: AggregatingAttributeMapper = AggregatingAttributeMapper.of(rule)
        assertThat(mapper.getReachableLabels("attribute", true))
            .containsExactly(
                Label.parseCanonicalUnchecked("//default:value"),
                Label.parseCanonicalUnchecked("//conditions:a")
            )
    }

    @Test
    @Throws(Exception::class)
    fun testComputedDefaultWithConfigurableDeps() {
        val rule: Rule? =
            scratchRule(
                "x",
                "bb",
                """
            rule_with_computed_defaults(
                name = "bb",
                configurable1 = select({":a": "of", ":b": "from"}),
                configurable2 = select({":a": "this", ":b": "the"}),
                nonconfigurable = "bottom",
            )
            
            """.trimIndent()
            )
        assertThat(
            AggregatingAttributeMapper.of(rule)
                .visitAttribute("\$computed_default_with_configurable_deps", STRING)
        )
            .containsExactly("of this bottom", "from this bottom", "of the bottom", "from the bottom")
    }

    @Test
    @Throws(Exception::class)
    fun testComputedDefaultWithoutConfigurableDeps() {
        val rule: Rule? =
            scratchRule(
                "x",
                "bb",
                """
            rule_with_computed_defaults(
                name = "bb",
                nonconfigurable = "swim up",
            )
            
            """.trimIndent()
            )
        assertThat(
            AggregatingAttributeMapper.of(rule)
                .visitAttribute("\$computed_default_without_configurable_deps", STRING)
        )
            .containsExactly("swim up")
    }

    companion object {
        private val RULE_WITH_DEFAULT: MockRule = MockRule {
            MockRule.Companion.define(
                "rule_with_default",
                MockRuleCustomBehavior { builder: RuleClass.Builder?, env: RuleDefinitionEnvironment? ->
                    builder.add(
                        attr("attribute", BuildType.LABEL)
                            .value(Label.parseCanonicalUnchecked("//default:value"))
                            .allowedFileTypes()
                    )
                })
        }

        private val RULE_WITH_COMPUTED_DEFAULT: MockRule = MockRule {
            MockRule.Companion.define(
                "rule_with_computed_defaults",
                MockRuleCustomBehavior { builder: RuleClass.Builder?, env: RuleDefinitionEnvironment? ->
                    builder
                        .add(attr("configurable1", STRING))
                        .add(attr("configurable2", STRING))
                        .add(attr("nonconfigurable", STRING).nonconfigurable("that's the point"))
                        .add(
                            attr("\$computed_default_with_configurable_deps", STRING)
                                .value(
                                    object : ComputedDefault("configurable1", "configurable2") {
                                        public override fun getDefault(rule: AttributeMap): Any {
                                            return@define Joiner.on(" ")
                                                .join(
                                                    rule.get("configurable1", STRING),
                                                    rule.get("configurable2", STRING),
                                                    rule.get("nonconfigurable", STRING)
                                                )
                                        }
                                    })
                        )
                        .add(
                            attr("\$computed_default_without_configurable_deps", STRING)
                                .value(
                                    object : ComputedDefault() {
                                        public override fun getDefault(rule: AttributeMap): Any {
                                            return@define rule.get("nonconfigurable", STRING)
                                        }
                                    })
                        )
                })
        }
    }
}
