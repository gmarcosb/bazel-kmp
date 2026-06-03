// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.collect

import com.google.common.testing.EqualsTester
import com.google.common.truth.Truth
import com.google.common.truth.TruthJUnit
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import org.junit.runner.RunWith

/** Tests for [ImmutableSharedKeyMap].  */
@RunWith(TestParameterInjector::class)
class ImmutableSharedKeyMapTest {
    private enum class CreationMode {
        BUILDER {
            override fun <K, V> createFrom(map: com.google.common.collect.ImmutableMap<K?, V?>): ImmutableSharedKeyMap<K?, V?> {
                val builder: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    ImmutableSharedKeyMap.< K, V>builder<K?, V?>()
                map.forEach(builder::put)
                return builder.build()
            }
        },
        COPY_OF {
            override fun <K, V> createFrom(map: com.google.common.collect.ImmutableMap<K?, V?>?): ImmutableSharedKeyMap<K?, V?> {
                return ImmutableSharedKeyMap.copyOf(map)
            }
        };

        abstract fun <K, V> createFrom(map: com.google.common.collect.ImmutableMap<K?, V?>?): ImmutableSharedKeyMap<K?, V?>
    }

    @TestParameter
    private val creationMode: CreationMode? = null

    private fun <K, V> createFrom(map: com.google.common.collect.ImmutableMap<K?, V?>?): ImmutableSharedKeyMap<K?, V?> {
        return creationMode!!.createFrom<K?, V?>(map)
    }

    @org.junit.Test
    fun testBasicFunctionality() {
        val valueA = Any()
        val valueB = Any()
        val immutableMap: com.google.common.collect.ImmutableMap<String?, Any?> =
            com.google.common.collect.ImmutableMap.of<String?, Any?>("a", valueA, "b", valueB)
        val map: ImmutableSharedKeyMap<String?, Any?> = createFrom<String?, Any?>(immutableMap)

        assertThat(map.get("a")).isSameInstanceAs(valueA)
        assertThat(map.get("b")).isSameInstanceAs(valueB)
        assertThat(map.get("c")).isNull()

        // Verify that we can find all items both by iteration and indexing
        val iterationCopy: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
            com.google.common.collect.ImmutableMap.builder<String?, Any?>()
        for (key in map) {
            iterationCopy.put(key, map.get(key))
        }
        Truth.assertThat(iterationCopy.buildOrThrow()).isEqualTo(immutableMap)

        val arrayIterationCopy: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
            com.google.common.collect.ImmutableMap.builder<String?, Any?>()
        for (i in 0..<map.size()) {
            arrayIterationCopy.put(map.keyAt(i), map.valueAt(i))
        }
        Truth.assertThat(arrayIterationCopy.buildOrThrow()).isEqualTo(immutableMap)
    }

    @org.junit.Test
    fun testEquality() {
        val emptyMap: ImmutableSharedKeyMap<String?, Any?> =
            createFrom<Any?, Any?>(com.google.common.collect.ImmutableMap.of<Any?, Any?>())

        val valueA = Any()
        val valueB = Any()

        val map: ImmutableSharedKeyMap<String?, Any?> =
            createFrom<String?, Any?>(
                com.google.common.collect.ImmutableMap.of<String?, Any?>(
                    "a",
                    valueA,
                    "b",
                    valueB
                )
            )

        // Two identically ordered maps are equal
        val exactCopy: ImmutableSharedKeyMap<String?, Any?> =
            createFrom<String?, Any?>(
                com.google.common.collect.ImmutableMap.of<String?, Any?>(
                    "a",
                    valueA,
                    "b",
                    valueB
                )
            )

        // The map is order sensitive, so different insertion orders aren't equal
        val oppositeOrderMap: ImmutableSharedKeyMap<String?, Any?> =
            createFrom<String?, Any?>(
                com.google.common.collect.ImmutableMap.of<String?, Any?>(
                    "b",
                    valueB,
                    "a",
                    valueA
                )
            )

        val valueC = Any()
        val biggerMap: ImmutableSharedKeyMap<String?, Any?> =
            createFrom<String?, Any?>(
                com.google.common.collect.ImmutableMap.of<String?, Any?>(
                    "a",
                    valueA,
                    "b",
                    valueB,
                    "c",
                    valueC
                )
            )

        EqualsTester()
            .addEqualityGroup(emptyMap)
            .addEqualityGroup(map, exactCopy)
            .addEqualityGroup(oppositeOrderMap)
            .addEqualityGroup(biggerMap)
            .testEquals()
    }

    @org.junit.Test
    fun duplicateKeyPassedToBuilder_throws() {
        // This test only makes sense with the builder since copyOf takes a map which is duplicate-free.
        TruthJUnit.assume().that<CreationMode?>(creationMode).isEqualTo(CreationMode.BUILDER)

        val valueA = Any()
        val valueB = Any()
        val valueC = Any()
        val map: ImmutableSharedKeyMap.Builder<String?, Any?> =
            ImmutableSharedKeyMap.< String, Object>builder<kotlin.String?, kotlin.Any?>()
        .put("key", valueA)
            .put("key", valueB)
            .put("key", valueC)

        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            map::build
        )
    }

    private class SameHashCodeClass {
        override fun hashCode(): Int {
            return 0
        }
    }

    @org.junit.Test
    fun twoKeysWithTheSameHashCode() {
        val keyA = SameHashCodeClass()
        val keyB = SameHashCodeClass()
        val valueA = Any()
        val valueB = Any()
        val map: ImmutableSharedKeyMap<SameHashCodeClass?, Any?> =
            createFrom<SameHashCodeClass?, Any?>(
                com.google.common.collect.ImmutableMap.of<SameHashCodeClass?, Any?>(
                    keyA,
                    valueA,
                    keyB,
                    valueB
                )
            )
        assertThat(map.get(keyA)).isSameInstanceAs(valueA)
        assertThat(map.get(keyB)).isSameInstanceAs(valueB)
    }
}
