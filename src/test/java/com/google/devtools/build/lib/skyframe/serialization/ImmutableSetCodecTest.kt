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
package com.google.devtools.build.lib.skyframe.serialization

import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester

@RunWith(JUnit4::class)
class ImmutableSetCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSingleton() {
        SerializationTester(Collections.singleton<T?>("a"), Collections.singleton<T?>(1)).runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmpty() {
        SerializationTester(Collections.emptySet<T?>(), com.google.common.collect.ImmutableSet.of<E?>()).runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultimapValueSet() {
        // Tests the serialization of the hidden type, `LinkedHashMultimap.ValueSet`. There's no way to
        // construct instances of this type directly, so instead, constructs a `LinkedHashMultimap`,
        // then extracts and tests its values.
        val source: com.google.common.collect.LinkedHashMultimap<String?, Int?> =
            com.google.common.collect.LinkedHashMultimap.create<String?, Int?>()
        source.putAll("a", com.google.common.collect.ImmutableList.of<Int?>(1, 2, 3))
        source.putAll("b", com.google.common.collect.ImmutableList.of<Int?>(4, 5, 6))
        source.putAll("c", com.google.common.collect.ImmutableList.of<Int?>(7, 8, 9))

        val map: MutableMap<String?, MutableCollection<Int?>?> = source.asMap()

        val subjects: java.util.ArrayList<MutableCollection<Int?>?> = java.util.ArrayList<MutableCollection<Int?>?>()
        for (entry in map.entrySet()) {
            val valueSet: MutableCollection<Int?>? = entry.getValue()
            // Verifies that `valueSet` is of the special hidden `LinkedHashMultimap.ValueSet` type.
            Truth.assertThat(valueSet).isInstanceOf(ImmutableSetCodec.MULTIMAP_VALUE_SET_CLASS)
            subjects.add(valueSet)
        }

        SerializationTester(com.google.common.collect.Iterables.toArray<T?>(subjects, Any::class.java)).runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPowerSetSubset() {
        val subsets: java.util.ArrayList<MutableSet<String?>?> = java.util.ArrayList<MutableSet<String?>?>()
        for (subset in com.google.common.collect.Sets.powerSet<String?>(
            com.google.common.collect.ImmutableSet.of<String?>(
                "a",
                "b",
                "c"
            )
        )) {
            if (subset.isEmpty()) {
                // The empty subset, unfortunately, does not have a stable serialized representation. The
                // first trip serializes it as a set of size 0, and the second trip serializes it as a
                // reference constant.
                continue
            }
            subsets.add(subset)
        }
        SerializationTester(com.google.common.collect.Iterables.toArray<T?>(subsets, Any::class.java)).runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSet() {
        SerializationTester(
            com.google.common.collect.ImmutableSet.of<E?>(1, 2, 3, 4, 5),
            com.google.common.collect.ImmutableSet.of<E?>("abc", "def", "ced"),
            com.google.common.collect.ImmutableSet.of<E?>(2.5e2, 3.14159)
        )
            .runTests()
    }
}
