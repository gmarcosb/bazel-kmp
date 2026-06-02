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
package com.google.devtools.build.docgen

import com.google.devtools.build.docgen.DocgenConsts.RuleType

/** A test class for RuleDocumentation.  */
@RunWith(JUnit4::class)
class RuleDocumentationTest {
    private fun checkAttributeForRule(
        rule: RuleDocumentation, attr: RuleDocumentationAttribute, isCommonAttribute: Boolean
    ) {
        rule.addAttribute(attr)
        val signature: String = rule.getAttributeSignature()
        val sb: java.lang.StringBuilder = java.lang.StringBuilder()
        if (isCommonAttribute) {
            sb.append("<a href=\"common-definitions.html#")
        } else {
            sb.append("<a href=\"#")
        }
        sb.append(attr.getGeneratedInRule(rule.getRuleName())).append(".")
        sb.append(attr.getAttributeName()).append("\">").append(attr.getAttributeName()).append("</a>")
        assertContains(signature, sb.toString())
    }

    @org.junit.Test
    @Throws(BuildEncyclopediaDocException::class)
    fun testVariableSubstitution() {
        val ruleDoc: RuleDocumentation =
            createRuleDocumentation(
                "rule",
                "OTHER",
                "FOO",
                com.google.common.base.Joiner.on("\n").join(arrayOf<String>("x", "\${VAR}", "z"))
            )
        ruleDoc.addDocVariable("VAR", "y")
        assertThat(ruleDoc.getHtmlDocumentation()).isEqualTo("x\ny\nz")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSignatureContainsCommonAttribute() {
        val licensesAttr: RuleDocumentationAttribute =
            RuleDocumentationAttribute.createCommon("licenses", "common", "attribute doc")
        checkAttributeForRule(
            createRuleDocumentation("java_binary", "BINARY", "JAVA"), licensesAttr, true
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInheritedAttributeGeneratesSignature() {
        val runtimeDepsAttr: RuleDocumentationAttribute =
            RuleDocumentationAttribute.create(
                com.google.devtools.build.docgen.testutil.TestData.TestRule::class.java,
                "runtime_deps",
                "attribute doc",
                "Test.java",
                0,
                NO_FLAGS
            )
        checkAttributeForRule(
            createRuleDocumentation("java_binary", "BINARY", "JAVA"), runtimeDepsAttr, false
        )
        checkAttributeForRule(
            createRuleDocumentation("java_library", "LIBRARY", "JAVA"), runtimeDepsAttr, false
        )
    }

    @org.junit.Test
    @Throws(BuildEncyclopediaDocException::class)
    fun testRuleDocFlagSubstitution() {
        val ruleDoc: RuleDocumentation =
            RuleDocumentation(
                "rule",
                "OTHER",
                "FOO",
                "x",
                "Test.java",
                0,
                "https://example.com/src/Test.java",
                com.google.common.collect.ImmutableSet.of<E?>("DEPRECATED"),
                ""
            )
        ruleDoc.addDocVariable("VAR", "y")
        assertThat(ruleDoc.getHtmlDocumentation()).isEqualTo("x")
    }

    @org.junit.Test
    @Throws(BuildEncyclopediaDocException::class)
    fun testCommandLineDocumentation() {
        val ruleDoc: RuleDocumentation =
            createRuleDocumentation(
                "foo_binary",
                "OTHER",
                "FOO",
                com.google.common.base.Joiner.on("\n").join(arrayOf<String>("x", "y", "z", "\${VAR}"))
            )
        ruleDoc.addDocVariable("VAR", "w")
        val attributeDoc: RuleDocumentationAttribute? =
            RuleDocumentationAttribute.create(
                com.google.devtools.build.docgen.testutil.TestData.TestRule::class.java,
                "srcs",
                "attribute doc",
                "Test.java",
                0,
                NO_FLAGS
            )
        ruleDoc.addAttribute(attributeDoc)
        assertThat(ruleDoc.getCommandLineDocumentation()).isEqualTo("\nx\ny\nz\n\n")
    }

    @org.junit.Test
    @Throws(BuildEncyclopediaDocException::class)
    fun testCreateExceptions() {
        val ruleDoc: RuleDocumentation =
            RuleDocumentation(
                "foo_binary",
                "OTHER",
                "FOO",
                "",
                "Foo.java",
                10,
                "https://example.com/src/Foo.java",
                NO_FLAGS,
                ""
            )
        val e: BuildEncyclopediaDocException? = ruleDoc.createException("msg")
        assertThat(e).hasMessageThat().isEqualTo("Error in Foo.java:10: msg")
    }

    @org.junit.Test
    @Throws(BuildEncyclopediaDocException::class)
    fun getSourceUrl() {
        val ruleDoc: RuleDocumentation =
            RuleDocumentation(
                "foo_binary",
                "OTHER",
                "FOO",
                "",
                "Foo.java",
                10,
                "https://example.com/src/Foo.java",
                NO_FLAGS,
                ""
            )
        assertThat(ruleDoc.getSourceUrl()).isEqualTo("https://example.com/src/Foo.java")
    }

    @org.junit.Test
    @Throws(BuildEncyclopediaDocException::class)
    fun testEquals() {
        // Expect equality purely based on name
        assertThat(
            RuleDocumentation(
                "rule",
                "BINARY",
                "FOO",
                "x",
                "Foo.java",
                0,
                "https://example.com/src/Foo.java",
                NO_FLAGS,
                ""
            )
        )
            .isEqualTo(
                RuleDocumentation(
                    "rule",
                    "OTHER",
                    "BAR",
                    "y",
                    "Bar.java",
                    10,
                    "https://example.com/src/Bar.java",
                    com.google.common.collect.ImmutableSet.of<E?>("DEPRECATED"),
                    "Blah blah blah"
                )
            )
    }

    @org.junit.Test
    @Throws(BuildEncyclopediaDocException::class)
    fun testNotEquals() {
        // Expect inequality purely based on name
        assertThat(
            createRuleDocumentation("rule1", "OTHER", "FOO", "x")
                .equals(createRuleDocumentation("rule2", "OTHER", "FOO", "x"))
        )
            .isFalse()
    }

    @org.junit.Test
    @Throws(BuildEncyclopediaDocException::class)
    fun testCompareTo() {
        assertThat(
            createRuleDocumentation("rule1", "OTHER", "FOO", "x")
                .compareTo(createRuleDocumentation("rule2", "OTHER", "FOO", "x"))
        )
            .isEqualTo(-1)
    }

    @org.junit.Test
    @Throws(BuildEncyclopediaDocException::class)
    fun testHashCode() {
        assertThat(createRuleDocumentation("rule", "OTHER", "FOO", "y").hashCode())
            .isEqualTo(createRuleDocumentation("rule", "OTHER", "FOO", "x").hashCode())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleTypeIsOtherForGenericRules() {
        assertThat(
            RuleDocumentation(
                "rule",
                "BINARY",
                "FOO",
                "y",
                "Test.java",
                0,
                "https://example.com/src/Test.java",
                com.google.common.collect.ImmutableSet.of<E?>("GENERIC_RULE"),
                ""
            )
                .getRuleType()
        )
            .isEqualTo(RuleType.OTHER)
        assertThat(
            RuleDocumentation(
                "rule",
                null,
                "FOO",
                "y",
                "Test.java",
                0,
                "https://example.com/src/Test.java",
                com.google.common.collect.ImmutableSet.of<E?>("GENERIC_RULE"),
                ""
            )
                .getRuleType()
        )
            .isEqualTo(RuleType.OTHER)
    }

    companion object {
        private val NO_FLAGS: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>()

        private fun assertContains(base: String, value: String?) {
            Truth.assertWithMessage("%s is expected to contain %s", base, value)
                .that(base.contains(value))
                .isTrue()
        }

        @Throws(BuildEncyclopediaDocException::class)
        private fun createRuleDocumentation(
            ruleName: String?, ruleType: String?, ruleFamily: String?, htmlDocumentation: String?
        ): RuleDocumentation {
            return RuleDocumentation(
                ruleName,
                ruleType,
                ruleFamily,
                htmlDocumentation,
                "Test.java",
                0,
                "https://example.com/src/Test.java",
                NO_FLAGS,
                ""
            )
        }

        @Throws(BuildEncyclopediaDocException::class)
        private fun createRuleDocumentation(
            ruleName: String?, ruleType: String?, ruleFamily: String?
        ): RuleDocumentation {
            return createRuleDocumentation(ruleName, ruleType, ruleFamily, "")
        }
    }
}
