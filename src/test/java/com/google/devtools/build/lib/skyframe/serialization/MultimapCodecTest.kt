// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization

import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester

/** Tests for [MultimapCodec].  */
@RunWith(JUnit4::class)
class MultimapCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultimap() {
        val linkedHashMultimap: com.google.common.collect.LinkedHashMultimap<String?, String?> =
            com.google.common.collect.LinkedHashMultimap.create<String?, String?>()
        linkedHashMultimap.put("A", "//foo:B")
        SerializationTester(
            com.google.common.collect.ImmutableMultimap.of<K?, V?>(),
            com.google.common.collect.ImmutableMultimap.of<K?, V?>("A", "//foo:A"),
            com.google.common.collect.ImmutableMultimap.builder<Any?, Any?>().putAll("B", "//foo:B1", "//foo:B2")
                .build(),
            linkedHashMultimap
        )
            .runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImmutableListMultimap() {
        SerializationTester(
            com.google.common.collect.ImmutableListMultimap.builder<Any?, Any?>().putAll("A", "a", "b", "c", "d", "e")
                .build()
        )
            .setVerificationFunction(
                VerificationFunction { deserialized, subject ->
                    assertThat(deserialized.get("A"))
                        .containsExactly("a", "b", "c", "d", "e")
                        .inOrder()
                } as VerificationFunction<com.google.common.collect.ImmutableListMultimap<String?, String?>?>)
            .runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImmutableSetMultimap() {
        SerializationTester(
            com.google.common.collect.ImmutableSetMultimap.builder<Any?, Any?>().putAll("A", "a", "b")
                .putAll("A", "b", "c").build()
        )
            .setVerificationFunction(
                VerificationFunction { deserialized, subject ->
                    assertThat(deserialized.get("A")).containsExactly(
                        "a",
                        "b",
                        "c"
                    )
                } as VerificationFunction<com.google.common.collect.ImmutableSetMultimap<String?, String?>?>)
            .runTests()
    }
}
