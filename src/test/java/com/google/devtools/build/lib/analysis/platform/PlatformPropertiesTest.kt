// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.platform

import com.google.common.collect.ImmutableMap
import com.google.devtools.build.lib.bazel.repository.decompressor.DecompressorDescriptor.Builder.build
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests of [PlatformProperties].  */
@RunWith(JUnit4::class)
class PlatformPropertiesTest {
    @Test
    @Throws(Exception::class)
    fun properties_empty() {
        val builder: PlatformProperties.Builder = PlatformProperties.builder()
        builder.setProperties(ImmutableMap.of<K?, V?>())
        val platformProperties: PlatformProperties = builder.build()

        assertThat(platformProperties).isNotNull()
        assertThat(platformProperties.properties()).isNotNull()
        assertThat(platformProperties.properties()).isEmpty()
    }

    @Test
    @Throws(Exception::class)
    fun properties_one() {
        val builder: PlatformProperties.Builder = PlatformProperties.builder()
        builder.setProperties(ImmutableMap.of<K?, V?>("elem1", "value1"))
        val platformProperties: PlatformProperties = builder.build()

        assertThat(platformProperties).isNotNull()
        assertThat(platformProperties.properties()).isNotNull()
        assertThat(platformProperties.properties()).containsExactly("elem1", "value1")
    }

    @Test
    @Throws(Exception::class)
    fun properties_parentPlatform_keep() {
        val parent: PlatformProperties? =
            PlatformProperties.builder().setProperties(ImmutableMap.of<K?, V?>("parent", "properties")).build()

        val builder: PlatformProperties.Builder = PlatformProperties.builder()
        builder.setParent(parent)
        val platformProperties: PlatformProperties = builder.build()

        assertThat(platformProperties).isNotNull()
        assertThat(platformProperties.properties()).containsExactly("parent", "properties")
    }

    @Test
    @Throws(Exception::class)
    fun properties_parentPlatform_inheritance() {
        val parent: PlatformProperties? =
            PlatformProperties.builder()
                .setProperties(
                    ImmutableMap.of<K?, V?>("p1", "keep", "p2", "delete", "p3", "parent", "p4", "del2")
                )
                .build()

        val builder: PlatformProperties.Builder = PlatformProperties.builder()
        builder.setParent(parent)
        val platformProperties: PlatformProperties =
            builder.setProperties(ImmutableMap.of<K?, V?>("p2", "", "p3", "child", "p4", "")).build()

        assertThat(platformProperties).isNotNull()
        assertThat(platformProperties.properties()).containsExactly("p1", "keep", "p3", "child")
    }
}
