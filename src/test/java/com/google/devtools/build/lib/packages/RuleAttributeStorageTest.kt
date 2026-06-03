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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.packages.Attribute.attr

/** Tests for [Rule]'s attribute storage behavior.  */
@RunWith(TestParameterInjector::class)
class RuleAttributeStorageTest : BuildViewTestCase() {
    private enum class ContainerSize(private val numAttrs: Int) {
        SMALL(16),
        LARGE(128)
    }

    @TestParameter
    private val containerSize: ContainerSize? = null

    private var rule: Rule? = null
    private var firstCustomAttrIndex = 0
    private var firstCustomAttr: Attribute? = null
    private var lastCustomAttrIndex = 0
    private var lastCustomAttr: Attribute? = null
    private var computedDefaultIndex = 0
    private var computedDefaultAttr: Attribute? = null
    private var lateBoundDefaultIndex = 0
    private var lateBoundDefaultAttr: Attribute? = null

    override fun createRuleClassProvider(): ConfiguredRuleClassProvider {
        val numDefaultAttrs: Int = MockRuleDefaults.DEFAULT_ATTRIBUTES.size + 1 // +1 for name.
        val numCustomAttrs = containerSize.numAttrs - numDefaultAttrs
        val exampleRule: MockRule =
            MockRule {
                MockRule.define(
                    "example_rule",
                    IntStream.range(0, numCustomAttrs)
                        .mapToObj<Any?>(
                            java.util.function.IntFunction { i: Int ->
                                // Make one attribute a computed default and one a late bound default.
                                if (i == COMPUTED_DEFAULT_OFFSET) {
                                    return@mapToObj attr("attr" + i + "_computed_default", Type.STRING)
                                        .value(
                                            object : ComputedDefault() {
                                                public override fun getDefault(rule: AttributeMap?): Any? {
                                                    return@mapToObj "computed"
                                                }
                                            })
                                }
                                if (i == LATE_BOUND_DEFAULT_OFFSET) {
                                    return@mapToObj attr(":attr" + i + "_late_bound_default", Type.STRING)
                                        .value(
                                            object :
                                                LateBoundDefault(java.lang.Void::class.java, { rule -> "late_bound" }) {
                                                public override fun resolve(
                                                    rule: Rule?, attributes: AttributeMap?, input: java.lang.Void?
                                                ): String? {
                                                    return@mapToObj "late_bound"
                                                }
                                            })
                                }
                                attr("attr" + i, Type.STRING)
                            })
                        .toArray<Attribute.Builder?> { _Dummy_.__Array__() })
            }
        val builder: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Builder().addRuleDefinition(exampleRule)
        TestRuleClassProvider.addStandardRules(builder)
        return builder.build()
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun setUpForRule() {
        scratch.file("foo/BUILD", "example_rule(name = 'example')")

        // Make a mutable copy of the rule so we can set attributes.
        val actualRule: Rule? = getTarget("//foo:example") as Rule?
        rule =
            Rule(
                actualRule.getPackageoid(),
                actualRule.getLabel(),
                actualRule.getRuleClassObject(),
                actualRule.getLocation(),
                actualRule.getInteriorCallStack()
            )

        firstCustomAttrIndex =
            rule.getRuleClassObject().getAttributeProvider().getAttributeIndex("attr0")
        firstCustomAttr = attrAt(firstCustomAttrIndex)
        lastCustomAttrIndex = rule.getRuleClassObject().getAttributeProvider().getAttributeCount() - 1
        lastCustomAttr = attrAt(lastCustomAttrIndex)
        computedDefaultIndex = firstCustomAttrIndex + COMPUTED_DEFAULT_OFFSET
        computedDefaultAttr = attrAt(computedDefaultIndex)
        lateBoundDefaultIndex = firstCustomAttrIndex + LATE_BOUND_DEFAULT_OFFSET
        lateBoundDefaultAttr = attrAt(lateBoundDefaultIndex)
    }

    @org.junit.Test
    fun attributeSettingAndRetrieval(@TestParameter frozen: Boolean) {
        rule.setAttributeValue(firstCustomAttr, "val1",  /* explicit= */true)
        rule.setAttributeValue(lastCustomAttr, "val2",  /* explicit= */true)

        if (frozen) {
            rule.freeze()
        }

        assertThat(rule.getAttrIfStored(firstCustomAttrIndex)).isEqualTo("val1")
        assertThat(rule.isAttributeValueExplicitlySpecified(firstCustomAttr)).isTrue()
        assertThat(rule.getAttrIfStored(lastCustomAttrIndex)).isEqualTo("val2")
        assertThat(rule.isAttributeValueExplicitlySpecified(lastCustomAttr)).isTrue()
    }

    @org.junit.Test
    fun indexOutOfBounds_throws(@TestParameter frozen: Boolean) {
        if (frozen) {
            rule.freeze()
        }
        org.junit.Assert.assertThrows<java.lang.IndexOutOfBoundsException?>(
            java.lang.IndexOutOfBoundsException::class.java,
            org.junit.function.ThrowingRunnable { rule.getAttrIfStored(lastCustomAttrIndex + 1) })
    }

    @org.junit.Test
    fun testForOffByOneError(@TestParameter frozen: Boolean) {
        // Set an index explicitly and check neighbouring indices don't leak that.
        rule.setAttributeValue(firstCustomAttr, "val", true)

        if (frozen) {
            rule.freeze()
        }

        assertThat(rule.getAttrIfStored(firstCustomAttrIndex - 1)).isNull()
        assertThat(rule.isAttributeValueExplicitlySpecified(attrAt(firstCustomAttrIndex - 1)))
            .isFalse()
        assertThat(rule.getAttrIfStored(firstCustomAttrIndex + 1)).isNull()
        assertThat(rule.isAttributeValueExplicitlySpecified(attrAt(firstCustomAttrIndex + 1)))
            .isFalse()
    }

    @org.junit.Test
    fun testFreezeWorks() {
        rule.setAttributeValue(firstCustomAttr, "val1",  /* explicit= */true)
        rule.setAttributeValue(lastCustomAttr, "val2",  /* explicit= */false)
        assertThat(rule.isFrozen()).isFalse()

        rule.freeze()

        assertThat(rule.isFrozen()).isTrue()
        // Double freezing is a no-op
        rule.freeze()
        // reads/explicit bits work as expected
        assertThat(rule.getAttrIfStored(firstCustomAttrIndex)).isEqualTo("val1")
        assertThat(rule.isAttributeValueExplicitlySpecified(firstCustomAttr)).isTrue()
        assertThat(rule.getAttrIfStored(lastCustomAttrIndex)).isEqualTo("val2")
        assertThat(rule.isAttributeValueExplicitlySpecified(lastCustomAttr)).isFalse()
        // writes no longer work.
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable { rule.setAttributeValue(lastCustomAttr, "different", true) })
    }

    @org.junit.Test
    fun allAttributesSet(@TestParameter frozen: Boolean) {
        val size: Int = rule.getRuleClassObject().getAttributeProvider().getAttributeCount()
        rule.setAttributeValue(attrAt(0), rule.getName(),  /* explicit= */true)
        for (i in 1..<size) {
            rule.setAttributeValue(attrAt(i), "value " + i, i % 2 == 0)
        }

        if (frozen) {
            rule.freeze()
        }

        for (i in 1..<size) { // Skip attribute 0 (name) which is never stored.
            assertThat(rule.getAttrIfStored(i)).isEqualTo("value " + i)
            Truth.assertWithMessage("attribute %s", i)
                .that(rule.isAttributeValueExplicitlySpecified(attrAt(i)))
                .isEqualTo(i % 2 == 0)
        }
    }

    @get:org.junit.Test
    val rawAttrValues_mutable_nullSafe: Unit
        get() {
            assertThat(rule.getRawAttrValues())
                .containsAtLeastElementsIn(
                    Collections.nCopies<T?>(
                        rule.getRuleClassObject().getAttributeProvider().getAttributeCount(), null
                    )
                )
        }

    @get:org.junit.Test
    val rawAttrValues_frozen_noNulls: Unit
        get() {
            rule.setAttributeValue(firstCustomAttr, "hi",  /* explicit= */true)
            rule.setAttributeValue(lastCustomAttr, null,  /* explicit= */false)
            rule.freeze()
            assertThat(rule.getRawAttrValues()).containsExactly("hi")
        }

    @org.junit.Test
    fun getRawAttrValues_unmodifiable(@TestParameter frozen: Boolean) {
        rule.setAttributeValue(firstCustomAttr, "hi",  /* explicit= */true)

        if (frozen) {
            rule.freeze()
        }

        val it: MutableIterator<Any?> = rule.getRawAttrValues().iterator()
        it.next()
        org.junit.Assert.assertThrows<java.lang.UnsupportedOperationException?>(
            java.lang.UnsupportedOperationException::class.java,
            org.junit.function.ThrowingRunnable { it.remove() })
    }

    /** Regression test for b/269593252.  */
    @org.junit.Test
    fun boundaryOfFrozenContainer() {
        val ruleName: String? = rule.getName()
        rule.setAttributeValue(attrAt(0), ruleName,  /* explicit= */true)
        rule.setAttributeValue(lastCustomAttr, "last",  /* explicit= */true)

        rule.freeze()

        assertThat(rule.getAttr("name")).isEqualTo(ruleName)
        assertThat(rule.isAttributeValueExplicitlySpecified("name")).isTrue()
        assertThat(rule.getAttrIfStored(lastCustomAttrIndex)).isEqualTo("last")
        assertThat(rule.isAttributeValueExplicitlySpecified(lastCustomAttr)).isTrue()
    }

    @org.junit.Test
    fun nameNotStoredAsRawAttr(@TestParameter frozen: Boolean) {
        val ruleName: String? = rule.getName()
        rule.setAttributeValue(attrAt(0), ruleName,  /* explicit= */true)

        if (frozen) {
            rule.freeze()
        }

        assertThat(rule.getAttrIfStored(0)).isNull()
        assertThat(rule.getRawAttrValues()).doesNotContain(ruleName)
        assertThat(rule.getAttr("name")).isEqualTo(ruleName)
        assertThat(rule.isAttributeValueExplicitlySpecified("name")).isTrue()
    }

    @org.junit.Test
    fun explicitDefaultValue_stored(@TestParameter frozen: Boolean) {
        rule.setAttributeValue(firstCustomAttr, STRING_DEFAULT,  /* explicit= */true)

        if (frozen) {
            rule.freeze()
        }

        assertThat(rule.getAttrIfStored(firstCustomAttrIndex)).isNotNull()
        assertThat(rule.isAttributeValueExplicitlySpecified(firstCustomAttr)).isTrue()
    }

    @org.junit.Test
    fun nonExplicitDefaultValue_mutable_stored() {
        rule.setAttributeValue(firstCustomAttr, STRING_DEFAULT,  /* explicit= */false)

        assertThat(rule.getAttrIfStored(firstCustomAttrIndex)).isNotNull()
        assertThat(rule.isAttributeValueExplicitlySpecified(firstCustomAttr)).isFalse()
    }

    @org.junit.Test
    fun nonExplicitDefaultValue_frozen_notStored() {
        rule.setAttributeValue(firstCustomAttr, STRING_DEFAULT,  /* explicit= */false)

        rule.freeze()

        assertThat(rule.getAttrIfStored(firstCustomAttrIndex)).isNull()
        assertThat(rule.isAttributeValueExplicitlySpecified(firstCustomAttr)).isFalse()
    }

    @org.junit.Test
    fun computedDefault_mutable_stored() {
        val computedDefault: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            computedDefaultAttr.getDefaultValue(null)
        assertThat(computedDefaultAttr.hasComputedDefault()).isTrue()
        assertThat(computedDefault).isInstanceOf(ComputedDefault::class.java)

        rule.setAttributeValue(computedDefaultAttr, computedDefault,  /* explicit= */false)

        assertThat(rule.getAttrIfStored(computedDefaultIndex)).isEqualTo(computedDefault)
        assertThat(rule.getAttr(computedDefaultAttr.name)).isEqualTo(computedDefault)
        assertThat(rule.isAttributeValueExplicitlySpecified(computedDefaultAttr)).isFalse()
    }

    @org.junit.Test
    fun computedDefault_frozen_notStored() {
        val computedDefault: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            computedDefaultAttr.getDefaultValue(null)
        assertThat(computedDefaultAttr.hasComputedDefault()).isTrue()
        assertThat(computedDefault).isInstanceOf(ComputedDefault::class.java)

        rule.setAttributeValue(computedDefaultAttr, computedDefault,  /* explicit= */false)
        rule.freeze()

        assertThat(rule.getAttrIfStored(computedDefaultIndex)).isNull()
        assertThat(rule.getAttr(computedDefaultAttr.name)).isEqualTo(computedDefault)
        assertThat(rule.isAttributeValueExplicitlySpecified(computedDefaultAttr)).isFalse()
    }

    @org.junit.Test
    fun lateBoundDefault_mutable_stored() {
        val lateBoundDefault: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            lateBoundDefaultAttr.getLateBoundDefault()

        rule.setAttributeValue(lateBoundDefaultAttr, lateBoundDefault,  /* explicit= */false)

        assertThat(rule.getAttrIfStored(lateBoundDefaultIndex)).isEqualTo(lateBoundDefault)
        assertThat(rule.getAttr(lateBoundDefaultAttr.name)).isEqualTo(lateBoundDefault)
        assertThat(rule.isAttributeValueExplicitlySpecified(lateBoundDefaultAttr)).isFalse()
    }

    @org.junit.Test
    fun lateBoundDefault_frozen_notStored() {
        val lateBoundDefault: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            lateBoundDefaultAttr.getLateBoundDefault()

        rule.setAttributeValue(lateBoundDefaultAttr, lateBoundDefault,  /* explicit= */false)
        rule.freeze()

        assertThat(rule.getAttrIfStored(lateBoundDefaultIndex)).isNull()
        assertThat(rule.getAttr(lateBoundDefaultAttr.name)).isEqualTo(lateBoundDefault)
        assertThat(rule.isAttributeValueExplicitlySpecified(lateBoundDefaultAttr)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun incompatibleSimplifyUnconditionalSelectsInRuleAttrs() {
        setBuildLanguageOptions("--incompatible_simplify_unconditional_selects_in_rule_attrs=true")
        scratch.file(
            "x/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")

        cc_binary(
            name = "simplifiable_single_select",
            srcs = select({"//conditions:default": ["unconditional.cc"]})
        )

        cc_binary(
            name = "simplifiable_concat_of_selects",
            srcs = ["direct.cc"] + select({"//conditions:default": ["unconditional.cc"]})
        )

        cc_binary(
            name = "non_simplifiable",
            srcs = ["direct.cc"] + select({
                "//conditions:default": ["default.cc"],
                "//conditions:a": ["other.c"],
            })
        )
        
        """.trimIndent()
        )
        val simplifiableSingleSelect: Rule? = getTarget("//x:simplifiable_single_select") as Rule?
        assertThat(
            BuildType.LABEL_LIST.cast(
                simplifiableSingleSelect.getAttr("srcs", BuildType.LABEL_LIST)
            )
        )
            .containsExactly(Label.parseCanonicalUnchecked("//x:unconditional.cc"))

        val simplifiableConcatOfSelects: Rule? = getTarget("//x:simplifiable_concat_of_selects") as Rule?
        assertThat(
            BuildType.LABEL_LIST.cast(
                simplifiableConcatOfSelects.getAttr("srcs", BuildType.LABEL_LIST)
            )
        )
            .containsExactly(
                Label.parseCanonicalUnchecked("//x:direct.cc"),
                Label.parseCanonicalUnchecked("//x:unconditional.cc")
            )
            .inOrder()

        val nonSimplifiable: Rule? = getTarget("//x:non_simplifiable") as Rule?
        assertThat(nonSimplifiable.getAttr("srcs", BuildType.LABEL_LIST))
            .isInstanceOf(BuildType.SelectorList::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noIncompatibleSimplifyUnconditionalSelectsInRuleAttrs() {
        setBuildLanguageOptions("--incompatible_simplify_unconditional_selects_in_rule_attrs=false")
        scratch.file(
            "x/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")

        cc_binary(
            name = "simplifiable",
            srcs = select({"//conditions:default": ["unconditional.cc"]})
        )
        
        """.trimIndent()
        )
        val simplifiable: Rule? = getTarget("//x:simplifiable") as Rule?
        assertThat(simplifiable.getAttr("srcs", BuildType.LABEL_LIST))
            .isInstanceOf(BuildType.SelectorList::class.java)
    }

    private fun attrAt(attrIndex: Int): Attribute {
        return rule.getRuleClassObject().getAttributeProvider().getAttribute(attrIndex)
    }

    companion object {
        private val STRING_DEFAULT: String? = Type.STRING.getDefaultValue()
        private const val COMPUTED_DEFAULT_OFFSET = 1
        private const val LATE_BOUND_DEFAULT_OFFSET = 2
    }
}
