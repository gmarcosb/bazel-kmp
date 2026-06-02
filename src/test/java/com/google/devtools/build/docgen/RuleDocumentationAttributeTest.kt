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

import com.google.devtools.build.lib.packages.Attribute

/**
 * A test class for RuleDocumentationAttribute.
 */
@RunWith(JUnit4::class)
class RuleDocumentationAttributeTest {
    @org.junit.Test
    fun testDirectChild() {
        val attr1: RuleDocumentationAttribute =
            RuleDocumentationAttribute.create(IntermediateRule::class.java, "", "", "Test.java", 0, NO_FLAGS)
        assertThat(
            attr1.getDefinitionClassAncestryLevel(
                com.google.devtools.build.docgen.testutil.TestData.TestRule::class.java,
                null
            )
        ).isEqualTo(1)
    }

    @org.junit.Test
    fun testTransitiveChild() {
        val attr2: RuleDocumentationAttribute =
            RuleDocumentationAttribute.create(BaseRule::class.java, "", "", "Test.java", 0, NO_FLAGS)
        assertThat(
            attr2.getDefinitionClassAncestryLevel(
                com.google.devtools.build.docgen.testutil.TestData.TestRule::class.java,
                null
            )
        ).isEqualTo(2)
    }

    @org.junit.Test
    fun testClassIsNotChild() {
        val attr2: RuleDocumentationAttribute =
            RuleDocumentationAttribute.create(IntermediateRule::class.java, "", "", "Test.java", 0, NO_FLAGS)
        assertThat(attr2.getDefinitionClassAncestryLevel(BaseRule::class.java, null)).isEqualTo(-1)
    }

    @org.junit.Test
    fun testClassIsSame() {
        val attr3: RuleDocumentationAttribute =
            RuleDocumentationAttribute.create(
                com.google.devtools.build.docgen.testutil.TestData.TestRule::class.java,
                "",
                "",
                "Test.java",
                0,
                NO_FLAGS
            )
        assertThat(
            attr3.getDefinitionClassAncestryLevel(
                com.google.devtools.build.docgen.testutil.TestData.TestRule::class.java,
                null
            )
        ).isEqualTo(0)
    }

    @org.junit.Test
    fun testHasFlags() {
        val attr: RuleDocumentationAttribute =
            RuleDocumentationAttribute.create(
                com.google.devtools.build.docgen.testutil.TestData.TestRule::class.java,
                "",
                "",
                "Test.java",
                0,
                com.google.common.collect.ImmutableSet.of<String?>("SOME_FLAG")
            )
        assertThat(attr.hasFlag("SOME_FLAG")).isTrue()
    }

    @org.junit.Test
    fun testCompareTo() {
        assertThat(
            RuleDocumentationAttribute.create(
                com.google.devtools.build.docgen.testutil.TestData.TestRule::class.java,
                "a",
                "",
                "Test.java",
                0,
                NO_FLAGS
            )
                .compareTo(
                    RuleDocumentationAttribute.create(
                        com.google.devtools.build.docgen.testutil.TestData.TestRule::class.java,
                        "b",
                        "",
                        "Test.java",
                        0,
                        NO_FLAGS
                    )
                )
        )
            .isEqualTo(-1)
    }

    @org.junit.Test
    fun testCompareToWithPriorityAttributeName() {
        assertThat(
            RuleDocumentationAttribute.create(
                com.google.devtools.build.docgen.testutil.TestData.TestRule::class.java,
                "a",
                "",
                "Test.java",
                0,
                NO_FLAGS
            )
                .compareTo(
                    RuleDocumentationAttribute.create(
                        com.google.devtools.build.docgen.testutil.TestData.TestRule::class.java,
                        "name",
                        "",
                        "Test.java",
                        0,
                        NO_FLAGS
                    )
                )
        )
            .isEqualTo(1)
    }

    @org.junit.Test
    fun testEquals() {
        assertThat(
            RuleDocumentationAttribute.create(
                IntermediateRule::class.java, "a", "", "Test.java", 0, NO_FLAGS
            )
        )
            .isEqualTo(
                RuleDocumentationAttribute.create(
                    com.google.devtools.build.docgen.testutil.TestData.TestRule::class.java,
                    "a",
                    "",
                    "Test.java",
                    0,
                    NO_FLAGS
                )
            )
    }

    @org.junit.Test
    fun testHashCode() {
        assertThat(
            RuleDocumentationAttribute.create(
                IntermediateRule::class.java, "a", "", "Test.java", 0, NO_FLAGS
            )
                .hashCode()
        )
            .isEqualTo(
                RuleDocumentationAttribute.create(
                    com.google.devtools.build.docgen.testutil.TestData.TestRule::class.java,
                    "a",
                    "",
                    "Test.java",
                    0,
                    NO_FLAGS
                )
                    .hashCode()
            )
    }

    @org.junit.Test
    @Throws(BuildEncyclopediaDocException::class)
    fun synopsis_stringAttribute() {
        val defaultValue = "9"
        val attribute: Attribute? = Attribute.attr("foo_version", Type.STRING).value(defaultValue).build()
        val attributeDoc: RuleDocumentationAttribute =
            RuleDocumentationAttribute.create(
                com.google.devtools.build.docgen.testutil.TestData.TestRule::class.java,
                "testrule",
                "",
                "Test.java",
                0,
                NO_FLAGS
            )
                .copyAndUpdateFrom(attribute)
        val doc: String? = attributeDoc.getSynopsis()
        Truth.assertThat(doc).isEqualTo("String; default is <code>\"" + defaultValue + "\"</code>")
    }

    @org.junit.Test
    @Throws(BuildEncyclopediaDocException::class)
    fun synopsis_stringAttribute_fromProto() {
        val defaultValue = "9"
        val attributeInfo: AttributeInfo? =
            AttributeInfo.newBuilder()
                .setName("foo_version")
                .setType(AttributeType.STRING)
                .setDefaultValue(Starlark.repr(defaultValue, StarlarkSemantics.DEFAULT))
                .build()
        val attributeDoc: RuleDocumentationAttribute =
            RuleDocumentationAttribute.createFromAttributeInfo(attributeInfo, "//:test.bzl", NO_FLAGS)
        assertThat(attributeDoc.getSynopsis())
            .isEqualTo("String; default is <code>\"" + defaultValue + "\"</code>")
    }

    @org.junit.Test
    @Throws(BuildEncyclopediaDocException::class)
    fun synopsis_integerAttribute() {
        val defaultValue: StarlarkInt? = StarlarkInt.of(384)
        val attribute: Attribute? = Attribute.attr("bar_limit", Type.INTEGER)
            .value(defaultValue).build()
        val attributeDoc: RuleDocumentationAttribute =
            RuleDocumentationAttribute.create(
                com.google.devtools.build.docgen.testutil.TestData.TestRule::class.java,
                "testrule",
                "",
                "Test.java",
                0,
                NO_FLAGS
            )
                .copyAndUpdateFrom(attribute)
        val doc: String? = attributeDoc.getSynopsis()
        Truth.assertThat(doc).isEqualTo("Integer; default is <code>" + defaultValue + "</code>")
    }

    @org.junit.Test
    @Throws(BuildEncyclopediaDocException::class)
    fun synopsis_integerAttribute_fromProto() {
        val defaultValue = 384
        val attributeInfo: AttributeInfo? =
            AttributeInfo.newBuilder()
                .setName("bar_limit")
                .setType(AttributeType.INT)
                .setDefaultValue(Starlark.repr(defaultValue, StarlarkSemantics.DEFAULT))
                .build()
        val attributeDoc: RuleDocumentationAttribute =
            RuleDocumentationAttribute.createFromAttributeInfo(attributeInfo, "//:test.bzl", NO_FLAGS)
        assertThat(attributeDoc.getSynopsis())
            .isEqualTo("Integer; default is <code>" + defaultValue + "</code>")
    }

    @org.junit.Test
    @Throws(BuildEncyclopediaDocException::class)
    fun synopsis_labelListAttribute() {
        val attribute: Attribute? = Attribute.attr("some_labels", BuildType.LABEL_LIST)
            .allowedRuleClasses("foo_rule")
            .allowedFileTypes()
            .build()
        val attributeDoc: RuleDocumentationAttribute =
            RuleDocumentationAttribute.create(
                com.google.devtools.build.docgen.testutil.TestData.TestRule::class.java,
                "testrule",
                "",
                "Test.java",
                0,
                NO_FLAGS
            )
                .copyAndUpdateFrom(attribute)
        val doc: String? = attributeDoc.getSynopsis()
        Truth.assertThat(doc)
            .isEqualTo(
                "List of <a href=\"\${link build-ref#labels}\">labels</a>; default is <code>[]</code>"
            )
    }

    @org.junit.Test
    @Throws(BuildEncyclopediaDocException::class)
    fun synopsis_labelListAttribute_fromProto() {
        val attributeInfo: AttributeInfo? =
            AttributeInfo.newBuilder()
                .setName("some_labels")
                .setType(AttributeType.LABEL_LIST)
                .setDefaultValue(
                    Starlark.repr(
                        com.google.common.collect.ImmutableList.of<Any?>(),
                        StarlarkSemantics.DEFAULT
                    )
                )
                .build()
        val attributeDoc: RuleDocumentationAttribute =
            RuleDocumentationAttribute.createFromAttributeInfo(attributeInfo, "//:test.bzl", NO_FLAGS)
        assertThat(attributeDoc.getSynopsis())
            .isEqualTo(
                "List of <a href=\"\${link build-ref#labels}\">labels</a>; default is <code>[]</code>"
            )
    }

    @org.junit.Test
    @Throws(BuildEncyclopediaDocException::class)
    fun synopsis_mandatoryAttribute() {
        val attribute: Attribute? =
            Attribute.attr("baz_labels", BuildType.LABEL)
                .mandatory()
                .allowedFileTypes(CppFileTypes.CPP_HEADER)
                .build()
        val attributeDoc: RuleDocumentationAttribute =
            RuleDocumentationAttribute.create(
                com.google.devtools.build.docgen.testutil.TestData.TestRule::class.java,
                "testrule",
                "",
                "Test.java",
                0,
                NO_FLAGS
            )
                .copyAndUpdateFrom(attribute)
        val doc: String? = attributeDoc.getSynopsis()
        Truth.assertThat(doc).isEqualTo("<a href=\"\${link build-ref#labels}\">Label</a>; required")
    }

    @org.junit.Test
    @Throws(BuildEncyclopediaDocException::class)
    fun synopsis_mandatoryAttribute_fromProto() {
        val attributeInfo: AttributeInfo? =
            AttributeInfo.newBuilder()
                .setName("baz_labels")
                .setType(AttributeType.LABEL)
                .setMandatory(true)
                .build()
        val attributeDoc: RuleDocumentationAttribute =
            RuleDocumentationAttribute.createFromAttributeInfo(attributeInfo, "//:test.bzl", NO_FLAGS)
        assertThat(attributeDoc.getSynopsis())
            .isEqualTo("<a href=\"\${link build-ref#labels}\">Label</a>; required")
    }

    @org.junit.Test
    @Throws(BuildEncyclopediaDocException::class)
    fun testSynopsisWithRuleLinkExpander() {
        val attribute: Attribute? = Attribute.attr("baz_labels", BuildType.LABEL)
            .mandatory()
            .allowedFileTypes(CppFileTypes.CPP_HEADER)
            .build()
        val attributeDoc: RuleDocumentationAttribute =
            RuleDocumentationAttribute.create(
                com.google.devtools.build.docgen.testutil.TestData.TestRule::class.java,
                "testrule",
                "",
                "Test.java",
                0,
                NO_FLAGS
            )
                .copyAndUpdateFrom(attribute)

        val linkMap: DocLinkMap =
            DocLinkMap(
                "",
                com.google.common.collect.ImmutableMap.of<K?, V?>("build-ref", "THE_REF.html"),
                "https://example.com/",
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )
        val expander: RuleLinkExpander = RuleLinkExpander(false, linkMap)
        attributeDoc.setRuleLinkExpander(expander)

        val doc: String? = attributeDoc.getSynopsis()
        Truth.assertThat(doc).isEqualTo("<a href=\"THE_REF.html#labels\">Label</a>; required")
    }

    companion object {
        private val NO_FLAGS: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>()
    }
}
