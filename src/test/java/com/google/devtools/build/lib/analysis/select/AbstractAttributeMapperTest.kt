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

import com.google.devtools.build.lib.packages.AbstractAttributeMapper
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import kotlin.collections.ArrayList
import kotlin.collections.MutableList

/** Unit tests for classes that extend [AbstractAttributeMapper].  */
abstract class AbstractAttributeMapperTest : BuildViewTestCase() {
    protected var rule: Rule? = null
    protected var mapper: AbstractAttributeMapper? = null

    protected abstract fun createMapper(rule: Rule?): AbstractAttributeMapper

    @Before
    @Throws(Exception::class)
    fun initializeRuleAndMapper() {
        rule =
            scratchRule(
                "p",
                "myrule",
                """
            load("@rules_cc//cc:cc_binary.bzl", "cc_binary")

            cc_binary(
                name = "myrule",
                srcs = ["a", "b", "c"],
            )
            
            """.trimIndent()
            )
        mapper = createMapper(rule)
    }

    @Test
    fun testRuleProperties() {
        assertThat(mapper.getLabel().name).isEqualTo(rule.getName())
        assertThat(mapper.getLabel()).isEqualTo(rule.getLabel())
    }

    @Test
    @Throws(Exception::class)
    fun testPackageDefaultProperties() {
        // TODO: blaze-configurability-team - write some package args and test them.
        assertThat(mapper.getPackageArgs()).isEqualTo(rule.getPackageDeclarations().getPackageArgs())
    }

    @Test
    open fun testAttributeTypeChecking() {
        // Good typing:
        mapper.get("srcs", BuildType.LABEL_LIST)

        // Bad typing:
        Assert.assertThrows<IllegalArgumentException?>(
            "Expected type mismatch to trigger an exception",
            IllegalArgumentException::class.java,
            ThrowingRunnable { mapper.get("srcs", Type.BOOLEAN) })

        // Unknown attribute:
        Assert.assertThrows<IllegalArgumentException?>(
            "Expected type mismatch to trigger an exception",
            IllegalArgumentException::class.java,
            ThrowingRunnable { mapper.get("nonsense", Type.BOOLEAN) })
    }

    @Test
    @Throws(Exception::class)
    open fun testGetAttributeType() {
        assertThat(mapper.getAttributeType("srcs")).isEqualTo(BuildType.LABEL_LIST)
        assertThat(mapper.getAttributeType("nonsense")).isNull()
    }

    @Test
    fun testGetAttributeDefinition() {
        assertThat(mapper.getAttributeDefinition("srcs").name).isEqualTo("srcs")
        assertThat(mapper.getAttributeDefinition("nonsense")).isNull()
    }

    @Test
    fun testIsAttributeExplicitlySpecified() {
        assertThat(mapper.isAttributeValueExplicitlySpecified("srcs")).isTrue()
        assertThat(mapper.isAttributeValueExplicitlySpecified("deps")).isFalse()
        assertThat(mapper.isAttributeValueExplicitlySpecified("nonsense")).isFalse()
    }

    @Test
    @Throws(Exception::class)
    open fun testVisitation() {
        Truth.assertThat(getLabelsForAttribute(mapper, "srcs")).containsExactly("//p:a", "//p:b", "//p:c")
    }

    companion object {
        @Throws(InterruptedException::class)
        protected fun getLabelsForAttribute(
            attributeMap: AttributeMap, attributeName: String?
        ): MutableList<String?> {
            val labels: MutableList<String?> = ArrayList<String?>()
            attributeMap.visitLabels(attributeName, { label -> labels.add(label.toString()) })
            return labels
        }
    }
}
