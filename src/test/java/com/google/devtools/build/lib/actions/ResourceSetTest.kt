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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.actions.ResourceSet.ResourceSetConverter

/** Tests for [ResourceSet].  */
@RunWith(JUnit4::class)
class ResourceSetTest {
    private var converter: ResourceSetConverter? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createConverter() {
        converter = ResourceSetConverter()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConverterParsesExpectedFormat() {
        val resources: ResourceSet = converter.convert("1,0.5,2")
        assertThat(resources.getMemoryMb()).isWithin(0.01).of(1.0)
        assertThat(resources.getCpuUsage()).isWithin(0.01).of(0.5)
        assertThat(resources.getLocalTestCount()).isEqualTo(java.lang.Integer.MAX_VALUE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConverterThrowsWhenGivenInsufficientInputs() {
        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable { converter.convert("0,0,") })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConverterThrowsWhenGivenTooManyInputs() {
        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable { converter.convert("0,0,0,") })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConverterThrowsWhenGivenNegativeInputs() {
        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable { converter.convert("-1,0,0") })
    }

    @org.junit.Test
    fun withResourceOverrides_noArgs_returnsSameInstance() {
        val base: ResourceSet = ResourceSet.createWithRamCpu(100, 1)
        assertThat(base.withResourceOverrides()).isSameInstanceAs(base)
    }

    @org.junit.Test
    fun withResourceOverrides_allEmpty_returnsSameInstance() {
        val base: ResourceSet = ResourceSet.createWithRamCpu(100, 1)
        assertThat(
            base.withResourceOverrides(
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )
        )
            .isSameInstanceAs(base)
    }

    @org.junit.Test
    fun withResourceOverrides_overridesExistingResource() {
        val base: ResourceSet = ResourceSet.createWithRamCpu(100, 1)
        val result: ResourceSet =
            base.withResourceOverrides(com.google.common.collect.ImmutableMap.of<K?, V?>("cpu", 4.0))
        assertThat(result.getCpuUsage()).isEqualTo(4.0)
        assertThat(result.getMemoryMb()).isEqualTo(100.0)
    }

    @org.junit.Test
    fun withResourceOverrides_addsNewResource() {
        val base: ResourceSet = ResourceSet.createWithRamCpu(100, 1)
        val result: ResourceSet =
            base.withResourceOverrides(com.google.common.collect.ImmutableMap.of<K?, V?>("gpu", 2.0))
        assertThat(result.get("gpu")).isEqualTo(2.0)
        assertThat(result.getCpuUsage()).isEqualTo(1.0)
        assertThat(result.getMemoryMb()).isEqualTo(100.0)
    }

    @org.junit.Test
    fun withResourceOverrides_laterOverrideWins() {
        val base: ResourceSet = ResourceSet.createWithRamCpu(100, 1)
        val result: ResourceSet =
            base.withResourceOverrides(
                com.google.common.collect.ImmutableMap.of<K?, V?>("cpu", 2.0),
                com.google.common.collect.ImmutableMap.of<K?, V?>("cpu", 8.0)
            )
        assertThat(result.getCpuUsage()).isEqualTo(8.0)
    }

    @org.junit.Test
    fun withResourceOverrides_mergesAcrossOverrides() {
        val base: ResourceSet = ResourceSet.createWithRamCpu(100, 1)
        val result: ResourceSet =
            base.withResourceOverrides(
                com.google.common.collect.ImmutableMap.of<K?, V?>("cpu", 4.0, "gpu", 1.0),
                com.google.common.collect.ImmutableMap.of<K?, V?>("memory", 2000.0)
            )
        assertThat(result.getCpuUsage()).isEqualTo(4.0)
        assertThat(result.getMemoryMb()).isEqualTo(2000.0)
        assertThat(result.get("gpu")).isEqualTo(1.0)
    }

    @org.junit.Test
    fun withResourceOverrides_preservesLocalTestCount() {
        val base: ResourceSet = ResourceSet.create(100, 1, 5)
        val result: ResourceSet =
            base.withResourceOverrides(com.google.common.collect.ImmutableMap.of<K?, V?>("cpu", 4.0))
        assertThat(result.getLocalTestCount()).isEqualTo(5)
    }

    @org.junit.Test
    fun withResourceOverrides_skipsEmptyAmongNonEmpty() {
        val base: ResourceSet = ResourceSet.createWithRamCpu(100, 1)
        val result: ResourceSet =
            base.withResourceOverrides(
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>("cpu", 4.0),
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )
        assertThat(result.getCpuUsage()).isEqualTo(4.0)
    }
}
