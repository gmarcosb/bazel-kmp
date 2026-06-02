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

/**
 * Unit tests for [NonconfigurableAttributeMapper].
 */
@RunWith(JUnit4::class)
class NonconfigurableAttributeMapperTest : AbstractAttributeMapperTest() {
    override fun createMapper(rule: Rule?): AbstractAttributeMapper {
        return NonconfigurableAttributeMapper.of(rule)
    }

    @Test
    @Throws(Exception::class)
    fun testGetNonconfigurableAttribute() {
        val rule: Rule? =
            scratchRule(
                "x",
                "myrule",
                """
            load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
            cc_binary(
                name = "myrule",
                srcs = ["a", "b", "c"],
                linkstatic = 1,
                deprecation = "this rule is deprecated!",
            )
            
            """.trimIndent()
            )

        assertThat(NonconfigurableAttributeMapper.of(rule).get("deprecation", Type.STRING))
            .isEqualTo("this rule is deprecated!")
    }

    @Test
    fun testGetConfigurableAttribute() {
        val e =
            Assert.assertThrows<IllegalStateException?>(
                "Expected NonconfigurableAttributeMapper to fail on a configurable attribute type",
                IllegalStateException::class.java,
                ThrowingRunnable { NonconfigurableAttributeMapper.of(rule).get("linkstatic", Type.BOOLEAN) })
        Truth.assertThat(e)
            .hasMessageThat()
            .isEqualTo("Attribute 'linkstatic' is potentially configurable - not allowed here")
    }

    @Test
    fun testGet_nonexistentAttribute() {
        val e =
            Assert.assertThrows<IllegalArgumentException?>(
                "Expected NonconfigurableAttributeMapper to fail on nonexistent attribute name",
                IllegalArgumentException::class.java,
                ThrowingRunnable { NonconfigurableAttributeMapper.of(rule).get("nonexistent-attr", Type.STRING) })
        Truth.assertThat(e).hasMessageThat().contains("No such attribute nonexistent-attr in cc_binary")
    }

    @Test
    override fun testAttributeTypeChecking() {
        // Don't test: fails due to srcs being nonconfigurable
    }

    @Test
    override fun testVisitation() {
        // Don't test: fails due to srcs being nonconfigurable
    }
}
