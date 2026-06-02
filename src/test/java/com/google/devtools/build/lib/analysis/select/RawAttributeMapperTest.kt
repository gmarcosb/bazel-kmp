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

import com.google.devtools.build.lib.cmdline.Label
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable

/**
 * Unit tests for [RawAttributeMapper].
 */
@RunWith(JUnit4::class)
class RawAttributeMapperTest : AbstractAttributeMapperTest() {
    override fun createMapper(rule: Rule?): AbstractAttributeMapper {
        // Run AbstractAttributeMapper tests through a RawAttributeMapper.
        return RawAttributeMapper.of(rule)
    }

    @Throws(Exception::class)
    private fun writeSampleRule(): Rule? {
        return scratchRule(
            "x",
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
            data = [
                ":data_a",
                ":data_b",
            ],
        )
        
        """.trimIndent()
        )
    }

    @Test
    @Throws(Exception::class)
    fun testGetAttribute() {
        val rawMapper: RawAttributeMapper = RawAttributeMapper.of(writeSampleRule())
        val value: MutableList<Label?>? = rawMapper.get("data", BuildType.LABEL_LIST)
        Truth.assertThat(value).isNotNull()
        Truth.assertThat(value).containsExactly(
            Label.create("@//x", "data_a"), Label.create("@//x", "data_b")
        )

        // Configurable attribute: trying to directly access from a RawAttributeMapper throws a
        // type mismatch exception.
        val e =
            Assert.assertThrows<IllegalArgumentException?>(
                "Expected srcs lookup to fail since the returned type is a SelectorList and not a list",
                IllegalArgumentException::class.java,
                ThrowingRunnable { rawMapper.get("srcs", BuildType.LABEL_LIST) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains(
                "Unexpected configurable attribute \"srcs\" in foo_binary rule //x:myrule: "
                        + "expected list(label), is select"
            )
    }

    @Test
    @Throws(Exception::class)
    override fun testGetAttributeType() {
        val rawMapper: RawAttributeMapper = RawAttributeMapper.of(writeSampleRule())
        assertThat(rawMapper.getAttributeType("data"))
            .isEqualTo(BuildType.LABEL_LIST) // not configurable
        assertThat(rawMapper.getAttributeType("srcs")).isEqualTo(BuildType.LABEL_LIST) // configurable
    }

    @Test
    @Throws(Exception::class)
    fun testConfigurabilityCheck() {
        val rawMapper: RawAttributeMapper = RawAttributeMapper.of(writeSampleRule())
        assertThat(rawMapper.isConfigurable("data")).isFalse()
        assertThat(rawMapper.isConfigurable("srcs")).isTrue()
    }

    /**
     * Tests that RawAttributeMapper can't handle label visitation with configurable attributes.
     */
    @Test
    @Throws(Exception::class)
    fun testVisitLabels() {
        val rawMapper: RawAttributeMapper = RawAttributeMapper.of(writeSampleRule())
        val e =
            Assert.assertThrows<IllegalArgumentException?>(
                "Expected label visitation to fail since one attribute is configurable",
                IllegalArgumentException::class.java,
                ThrowingRunnable { rawMapper.visitAllLabels({ attribute, label -> }) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains(
                "Unexpected configurable attribute \"srcs\" in foo_binary rule //x:myrule: "
                        + "expected list(label), is select"
            )
    }

    @Test
    @Throws(Exception::class)
    fun testGetConfigurabilityKeys() {
        val rawMapper: RawAttributeMapper = RawAttributeMapper.of(writeSampleRule())
        assertThat(rawMapper.getConfigurabilityKeys("srcs", BuildType.LABEL_LIST))
            .containsExactly(
                Label.parseCanonical("//conditions:a"),
                Label.parseCanonical("//conditions:b"),
                Label.parseCanonical("//conditions:default")
            )
        assertThat(rawMapper.getConfigurabilityKeys("data", BuildType.LABEL_LIST)).isEmpty()
    }

    @Test
    @Throws(Exception::class)
    fun testGetMergedValues() {
        val rule: Rule? =
            scratchRule(
                "x",
                "myrule",
                """
            load('//test_defs:foo_binary.bzl', 'foo_binary')
            foo_binary(
                name = "myrule",
                srcs = select({
                    "//conditions:a": ["a.sh", "b.sh"],
                    "//conditions:b": ["b.sh", "c.sh"],
                }),
            )
            
            """.trimIndent()
            )
        val rawMapper: RawAttributeMapper = RawAttributeMapper.of(rule)
        assertThat(rawMapper.getMergedValues("srcs", BuildType.LABEL_LIST))
            .containsExactly(
                Label.parseCanonical("//x:a.sh"),
                Label.parseCanonical("//x:b.sh"),
                Label.parseCanonical("//x:c.sh")
            )
            .inOrder()
    }

    @Test
    @Throws(Exception::class)
    fun testMergedValuesWithConcatenatedSelects() {
        val rule: Rule? =
            scratchRule(
                "x",
                "myrule",
                """
            load('//test_defs:foo_binary.bzl', 'foo_binary')
            foo_binary(
                name = "myrule",
                srcs = select({
                    "//conditions:a1": ["a1.sh"],
                    "//conditions:b1": ["b1.sh", "another_b1.sh"],
                }) + select({
                    "//conditions:a2": ["a2.sh"],
                    "//conditions:b2": ["b2.sh"],
                }),
            )
            
            """.trimIndent()
            )
        val rawMapper: RawAttributeMapper = RawAttributeMapper.of(rule)
        assertThat(rawMapper.getMergedValues("srcs", BuildType.LABEL_LIST))
            .containsExactly(
                Label.parseCanonical("//x:a1.sh"),
                Label.parseCanonical("//x:b1.sh"),
                Label.parseCanonical("//x:another_b1.sh"),
                Label.parseCanonical("//x:a2.sh"),
                Label.parseCanonical("//x:b2.sh")
            )
            .inOrder()
    }
}
