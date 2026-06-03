// Copyright 2025 The Bazel Authors. All rights reserved.
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

/** Tests for [ImmutableEntryCodec].  */
@RunWith(JUnit4::class)
class ImmutableEntryCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringStringEntry_roundTripsSuccessfully() {
        val original: MutableMap.MutableEntry<String?, String?> =
            com.google.common.collect.Maps.immutableEntry<String?, String?>("foo", "bar")
        SerializationTester(original)
            .setVerificationFunction(
                { `in`, out ->
                    assertThat(out).isEqualTo(`in`)
                    // Verify it's the same specific class type.
                    assertThat(out.getClass()).isEqualTo(`in`.getClass())
                })
            .runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun roundTripsSuccessfully() {
        SerializationTester(
            com.google.common.collect.Maps.immutableEntry<K?, V?>(123, "baz"),
            com.google.common.collect.Maps.immutableEntry<K?, V?>(null, "value"),
            com.google.common.collect.Maps.immutableEntry<K?, V?>("key", null),
            com.google.common.collect.Maps.immutableEntry<K?, V?>(null, null)
        )
            .runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNestedEntry_roundTripsSuccessfully() {
        val original: MutableMap.MutableEntry<String?, MutableMap.MutableEntry<Int?, String?>?>?
        TODO(
            """
            |Cannot convert element
            |With text:
            |String, Map.Entry<Integer, String>>immutableEntry("outer", <Integer, String>immutableEntry(1, "inner")
            """.trimMargin()
        )

        SerializationTester(original)
            .setVerificationFunction(
                { `in`, out ->
                    assertThat(out).isEqualTo(`in`)
                    assertThat(out.getClass()).isEqualTo(`in`.getClass())
                    Truth.assertThat((out as MutableMap.MutableEntry<*, *>).getValue().getClass())
                        .isEqualTo((`in` as MutableMap.MutableEntry<*, *>).getValue().getClass())
                })
            .runTests()
    }
}
