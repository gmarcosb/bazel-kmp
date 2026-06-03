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

/**
 * Tests for the [RuleClass.Builder].
 */
@RunWith(JUnit4::class)
class RuleClassBuilderTest : PackageLoadingTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleClassBuilderBasics() {
        val ruleClassA: RuleClass =
            Builder("ruleA", RuleClassType.NORMAL, false)
                .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
                .add(attr("srcs", BuildType.LABEL_LIST).legacyAllowAnyFileType())
                .add(attr("tags", STRING_LIST))
                .add(attr("X", com.google.devtools.build.lib.packages.Type.INTEGER).mandatory())
                .build()

        assertThat(ruleClassA.getName()).isEqualTo("ruleA")
        assertThat(ruleClassA.getAttributeProvider().getAttributeCount()).isEqualTo(4)
        assertThat(ruleClassA.outputsToBindir()).isTrue()

        Truth.assertThat(ruleClassA.getAttributeProvider().getAttributeIndex("srcs") as Int).isEqualTo(1)
        assertThat(ruleClassA.getAttributeProvider().getAttributeByName("srcs"))
            .isEqualTo(ruleClassA.getAttributeProvider().getAttribute(1))

        Truth.assertThat(ruleClassA.getAttributeProvider().getAttributeIndex("tags") as Int).isEqualTo(2)
        assertThat(ruleClassA.getAttributeProvider().getAttributeByName("tags"))
            .isEqualTo(ruleClassA.getAttributeProvider().getAttribute(2))

        Truth.assertThat(ruleClassA.getAttributeProvider().getAttributeIndex("X") as Int).isEqualTo(3)
        assertThat(ruleClassA.getAttributeProvider().getAttributeByName("X"))
            .isEqualTo(ruleClassA.getAttributeProvider().getAttribute(3))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleClassBuilderTestIsBinary() {
        val ruleClassA: RuleClass =
            Builder("rule_test", RuleClassType.TEST, false)
                .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
                .add(attr("tags", STRING_LIST))
                .add(attr("size", STRING).value("medium"))
                .add(attr("timeout", STRING))
                .add(attr("flaky", BOOLEAN).value(false))
                .add(attr("shard_count", INTEGER).value(StarlarkInt.of(-1)))
                .add(attr("local", BOOLEAN))
                .build()
        assertThat(ruleClassA.outputsToBindir()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleClassBuilderGenruleIsNotBinary() {
        val ruleClassA: RuleClass =
            Builder("ruleA", RuleClassType.NORMAL, false)
                .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
                .setOutputToGenfiles()
                .add(attr("tags", STRING_LIST))
                .build()
        assertThat(ruleClassA.outputsToBindir()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleClassTestNameValidity() {
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { Builder("ruleA", RuleClassType.TEST, false).build() })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleClassNormalNameValidity() {
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { Builder("ruleA_test", RuleClassType.NORMAL, false).build() })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDuplicateAttribute() {
        val builder: RuleClass.Builder =
            Builder("ruleA", RuleClassType.NORMAL, false).add(attr("a", STRING))
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable { builder.add(attr("a", STRING)) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPropertiesOfAbstractRuleClass() {
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable {
                Builder(
                    "\$ruleA",
                    RuleClassType.ABSTRACT,
                    false
                ).setOutputToGenfiles()
            })

        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable {
                Builder("\$ruleB", RuleClassType.ABSTRACT, false)
                    .setImplicitOutputsFunction(null)
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDuplicateInheritedAttribute() {
        val a: RuleClass? =
            Builder("ruleA", RuleClassType.NORMAL, false)
                .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
                .add(attr("a", STRING).value("A"))
                .add(attr("tags", STRING_LIST))
                .build()
        val b: RuleClass? =
            Builder("ruleB", RuleClassType.NORMAL, false)
                .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
                .add(attr("a", STRING).value("B"))
                .add(attr("tags", STRING_LIST))
                .build()
        val e: java.lang.IllegalArgumentException? =
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable { Builder("ruleC", RuleClassType.NORMAL, false, a, b).build() })
        Truth.assertThat(e)
            .hasMessageThat()
            .isEqualTo("Attribute a is inherited multiple times in ruleC ruleclass")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRemoveAttribute() {
        val a: RuleClass? =
            Builder("rule", RuleClassType.NORMAL, false)
                .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
                .add(attr("a", STRING))
                .add(attr("b", STRING))
                .add(attr("tags", STRING_LIST))
                .build()
        val builder: RuleClass.Builder =
            Builder("c", RuleClassType.NORMAL, false, a)
                .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
        val c: RuleClass = builder.removeAttribute("a").add(attr("a", INTEGER)).removeAttribute("b").build()
        assertThat(c.getAttributeProvider().hasAttr("a", STRING)).isFalse()
        assertThat(c.getAttributeProvider().hasAttr("a", INTEGER)).isTrue()
        assertThat(c.getAttributeProvider().hasAttr("b", STRING)).isFalse()

        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable { builder.removeAttribute("c") })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRequiredToolchainsAreInherited() {
        val mockToolchainType: Label? = Label.parseCanonicalUnchecked("//mock_toolchain_type")
        val parent: RuleClass? =
            Builder("\$parent", RuleClassType.ABSTRACT, false)
                .add(attr("tags", STRING_LIST))
                .addToolchainTypes(ToolchainTypeRequirement.create(mockToolchainType))
                .build()
        val child: RuleClass? =
            Builder("child", RuleClassType.NORMAL, false, parent)
                .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
                .add(attr("attr", STRING))
                .build()

        assertThat(child).hasToolchainType(mockToolchainType)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecGroupsAreInherited() {
        val mockToolchainType: Label? = Label.parseCanonicalUnchecked("//mock_toolchain_type")
        val mockConstraint: Label = Label.parseCanonicalUnchecked("//mock_constraint")
        val parentGroup: DeclaredExecGroup =
            DeclaredExecGroup.builder()
                .addToolchainType(ToolchainTypeRequirement.create(mockToolchainType))
                .execCompatibleWith(com.google.common.collect.ImmutableSet.of<E?>(mockConstraint))
                .build()
        val childGroup: DeclaredExecGroup =
            DeclaredExecGroup.builder()
                .toolchainTypes(com.google.common.collect.ImmutableSet.of<E?>())
                .execCompatibleWith(com.google.common.collect.ImmutableSet.of<E?>())
                .build()
        val parent: RuleClass? =
            Builder("\$parent", RuleClassType.ABSTRACT, false)
                .add(attr("tags", STRING_LIST))
                .addExecGroups(com.google.common.collect.ImmutableMap.of<K?, V?>("group", parentGroup), false)
                .build()
        val child: RuleClass =
            Builder("child", RuleClassType.NORMAL, false, parent)
                .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
                .add(attr("attr", STRING))
                .addExecGroups(com.google.common.collect.ImmutableMap.of<K?, V?>("child-group", childGroup), false)
                .build()
        assertThat(child.getDeclaredExecGroups().get("group")).isEqualTo(parentGroup)
        assertThat(child.getDeclaredExecGroups().get("child-group")).isEqualTo(childGroup)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDuplicateExecGroupsThatInheritFromRuleIsOk() {
        val a: RuleClass? =
            Builder("ruleA", RuleClassType.NORMAL, false)
                .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
                .addExecGroups(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "blueberry",
                        DeclaredExecGroup.COPY_FROM_DEFAULT
                    ), false
                )
                .add(attr("tags", STRING_LIST))
                .addToolchainTypes(
                    ToolchainTypeRequirement.create(Label.parseCanonicalUnchecked("//some/toolchain"))
                )
                .build()
        val b: RuleClass? =
            Builder("ruleB", RuleClassType.NORMAL, false)
                .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
                .addExecGroups(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "blueberry",
                        DeclaredExecGroup.COPY_FROM_DEFAULT
                    ), false
                )
                .add(attr("tags", STRING_LIST))
                .addToolchainTypes(
                    ToolchainTypeRequirement.create(
                        Label.parseCanonicalUnchecked("//some/other/toolchain")
                    )
                )
                .build()
        val c: RuleClass =
            Builder("\$ruleC", RuleClassType.ABSTRACT, false, a, b)
                .addToolchainTypes(
                    ToolchainTypeRequirement.create(
                        Label.parseCanonicalUnchecked("//actual/toolchain/we/care/about")
                    )
                )
                .build()
        assertThat(c.getDeclaredExecGroups()).containsKey("blueberry")
        val blueberry: DeclaredExecGroup? = c.getDeclaredExecGroups().get("blueberry")
        assertThat(blueberry).copiesFromDefault()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDuplicateExecGroupsThrowsError() {
        val a: RuleClass? =
            Builder("ruleA", RuleClassType.NORMAL, false)
                .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
                .addExecGroups(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "blueberry",
                        DeclaredExecGroup.builder()
                            .addToolchainType(
                                ToolchainTypeRequirement.create(
                                    Label.parseCanonicalUnchecked("//some/toolchain")
                                )
                            )
                            .execCompatibleWith(com.google.common.collect.ImmutableSet.of<E?>())
                            .build()
                    ),
                    false
                )
                .add(attr("tags", STRING_LIST))
                .build()
        val b: RuleClass? =
            Builder("ruleB", RuleClassType.NORMAL, false)
                .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
                .addExecGroups(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "blueberry",
                        DeclaredExecGroup.builder()
                            .toolchainTypes(com.google.common.collect.ImmutableSet.of<E?>())
                            .execCompatibleWith(com.google.common.collect.ImmutableSet.of<E?>())
                            .build()
                    ),
                    false
                )
                .add(attr("tags", STRING_LIST))
                .build()
        val e: java.lang.IllegalArgumentException? =
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable { Builder("ruleC", RuleClassType.NORMAL, false, a, b).build() })
        Truth.assertThat(e)
            .hasMessageThat()
            .isEqualTo(
                "An execution group named 'blueberry' is inherited multiple times with different"
                        + " requirements in ruleC ruleclass"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDuplicateExecGroupsOverwrite() {
        val mockToolchainType: Label? = Label.parseCanonicalUnchecked("//mock_toolchain_type")
        val mockConstraint: Label = Label.parseCanonicalUnchecked("//mock_constraint")
        val parentGroup: DeclaredExecGroup =
            DeclaredExecGroup.builder()
                .addToolchainType(ToolchainTypeRequirement.create(mockToolchainType))
                .execCompatibleWith(com.google.common.collect.ImmutableSet.of<E?>(mockConstraint))
                .build()
        val childGroup: DeclaredExecGroup =
            DeclaredExecGroup.builder()
                .toolchainTypes(com.google.common.collect.ImmutableSet.of<E?>())
                .execCompatibleWith(com.google.common.collect.ImmutableSet.of<E?>())
                .build()
        val parent: RuleClass? =
            Builder("\$parent", RuleClassType.ABSTRACT, false)
                .add(attr("tags", STRING_LIST))
                .addExecGroups(com.google.common.collect.ImmutableMap.of<K?, V?>("group", parentGroup), false)
                .build()
        val child: RuleClass =
            Builder("child", RuleClassType.NORMAL, false, parent)
                .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
                .add(attr("attr", STRING))
                .addExecGroups(com.google.common.collect.ImmutableMap.of<K?, V?>("group", childGroup), true)
                .build()
        assertThat(child.getDeclaredExecGroups().get("group")).isEqualTo(childGroup)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBasicRuleNamePredicates() {
        val abcdef: com.google.common.base.Predicate<String?> = nothingBut("abc", "def").asPredicateOfRuleClass()
        Truth.assertThat(abcdef.test("abc")).isTrue()
        Truth.assertThat(abcdef.test("def")).isTrue()
        Truth.assertThat(abcdef.test("ghi")).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTwoRuleNamePredicateFactoriesEquivalent() {
        val a: RuleClassNamePredicate = nothingBut("abc", "def")
        val b: RuleClassNamePredicate =
            RuleClassNamePredicate.only(com.google.common.collect.ImmutableList.of<E?>("abc", "def"))
        assertThat(a.asPredicateOfRuleClass()).isEqualTo(b.asPredicateOfRuleClass())
        assertThat(a.asPredicateOfRuleClassObject()).isEqualTo(b.asPredicateOfRuleClassObject())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEverythingButRuleNamePredicates() {
        val abcdef: com.google.common.base.Predicate<String?> = allBut("abc", "def").asPredicateOfRuleClass()
        Truth.assertThat(abcdef.test("abc")).isFalse()
        Truth.assertThat(abcdef.test("def")).isFalse()
        Truth.assertThat(abcdef.test("ghi")).isTrue()
    }

    @org.junit.Test
    fun testRuleClassNamePredicateIntersection() {
        // two positives intersect iff they contain any of the same items
        assertThat(nothingBut("abc", "def").consideredOverlapping(nothingBut("abc"))).isTrue()
        assertThat(nothingBut("abc", "def").consideredOverlapping(nothingBut("ghi"))).isFalse()

        // negatives are never considered to overlap...
        assertThat(allBut("abc", "def").consideredOverlapping(allBut("abc", "def"))).isFalse()
        assertThat(allBut("abc", "def").consideredOverlapping(allBut("ghi", "jkl"))).isFalse()

        assertThat(allBut("abc", "def").consideredOverlapping(nothingBut("abc", "def"))).isFalse()
        assertThat(nothingBut("abc", "def").consideredOverlapping(allBut("abc", "def"))).isFalse()

        assertThat(allBut("abc", "def").consideredOverlapping(nothingBut("abc"))).isFalse()
        assertThat(allBut("abc").consideredOverlapping(nothingBut("abc", "def"))).isFalse()
    }

    private fun nothingBut(vararg excludedRuleClasses: String?): RuleClassNamePredicate {
        return RuleClassNamePredicate.only(excludedRuleClasses)
    }

    private fun allBut(vararg excludedRuleClasses: String?): RuleClassNamePredicate {
        return RuleClassNamePredicate.allExcept(excludedRuleClasses)
    }

    companion object {
        private val DUMMY_CONFIGURED_TARGET_FACTORY: RuleClass.ConfiguredTargetFactory<Any?, Any?, java.lang.Exception?> =
            object : ConfiguredTargetFactory<Any?, Any?, java.lang.Exception?>() {
                @Throws(
                    java.lang.InterruptedException::class,
                    RuleErrorException::class,
                    ActionConflictException::class
                )
                public override fun create(ruleContext: Any?): Any? {
                    throw java.lang.IllegalStateException()
                }
            }
    }
}
