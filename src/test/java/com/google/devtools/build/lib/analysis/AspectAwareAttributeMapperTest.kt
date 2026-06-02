// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget

/**
 * Unit tests for [AspectAwareAttributeMapper].
 */
@RunWith(JUnit4::class)
class AspectAwareAttributeMapperTest : BuildViewTestCase() {
    private var rule: Rule? = null
    private var aspectAttributes: com.google.common.collect.ImmutableMap<String?, Attribute?>? = null
    private var mapper: AspectAwareAttributeMapper? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createMapper() {
        val ctad: ConfiguredTargetAndData =
            scratchConfiguredTargetAndData(
                "foo",
                "myrule",
                """
            load("@rules_cc//cc:cc_binary.bzl", "cc_binary")

            # Needed to avoid select() being eliminated as trivial.
            config_setting(
                name = "config",
                values = {"define": "pi=3"},
            )

            cc_binary(
                name = "myrule",
                srcs = [":a.cc"],
                linkstatic = select({
                    ":config": 1,
                    "//conditions:default": 1,
                }),
            )
            
            """.trimIndent()
            )

        val ct: RuleConfiguredTarget = ctad.getConfiguredTarget() as RuleConfiguredTarget
        rule = ctad.getTargetForTesting() as Rule
        val aspectAttr: Attribute = Builder<Label?>("fromaspect", BuildType.LABEL)
            .allowedFileTypes(FileTypeSet.ANY_FILE)
            .build()
        aspectAttributes = com.google.common.collect.ImmutableMap.of<String?, Attribute?>(aspectAttr.name, aspectAttr)
        mapper =
            AspectAwareAttributeMapper(
                ConfiguredAttributeMapper.of(
                    rule,
                    ct.getConfigConditions(),
                    ct.getConfigurationChecksum(),  /*alwaysSucceed=*/
                    false
                ),
                aspectAttributes
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getName() {
        assertThat(mapper.getLabel().getName()).isEqualTo(rule.getName())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getLabel() {
        assertThat(mapper.getLabel()).isEqualTo(rule.getLabel())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getRuleAttributeValue() {
        assertThat(mapper.get("srcs", BuildType.LABEL_LIST))
            .containsExactly(Label.parseCanonical("//foo:a.cc"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getAspectAttributeValue() {
        org.junit.Assert.assertThrows<java.lang.UnsupportedOperationException?>(
            java.lang.UnsupportedOperationException::class.java,
            org.junit.function.ThrowingRunnable { mapper.get("fromaspect", BuildType.LABEL) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getAspectValueWrongType() {
        val e: java.lang.IllegalArgumentException? =
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable { mapper.get("fromaspect", BuildType.LABEL_LIST) })
        Truth.assertThat(e)
            .hasMessageThat()
            .isEqualTo("attribute fromaspect has type label, not expected type list(label)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getMissingAttributeValue() {
        val e: java.lang.IllegalArgumentException? =
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable { mapper.get("noexist", BuildType.LABEL) })
        Truth.assertThat(e)
            .hasMessageThat()
            .matches(
                "no attribute 'noexist' in either cc_binary //foo:myrule \\([^)]+\\) or its aspects"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun isConfigurable() {
        assertThat(mapper.isConfigurable("linkstatic")).isTrue()
        assertThat(mapper.isConfigurable("fromaspect")).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getAttributeNames() {
        assertThat(mapper.getAttributeNames()).containsAtLeast("srcs", "linkstatic", "fromaspect")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getAttributeType() {
        assertThat(mapper.getAttributeType("srcs")).isEqualTo(BuildType.LABEL_LIST)
        assertThat(mapper.getAttributeType("fromaspect")).isEqualTo(BuildType.LABEL)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getAttributeDefinition() {
        assertThat(mapper.getAttributeDefinition("srcs").getName()).isEqualTo("srcs")
        assertThat(mapper.getAttributeDefinition("fromaspect").getName()).isEqualTo("fromaspect")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun has() {
        assertThat(mapper.has("srcs")).isTrue()
        assertThat(mapper.has("fromaspect")).isTrue()
    }
}


