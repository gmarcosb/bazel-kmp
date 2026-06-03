// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.common.collect.testing.google.UnmodifiableCollectionTests
import com.google.common.testing.EqualsTester
import com.google.common.truth.Truth
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.AbstractMap.SimpleImmutableEntry

/**
 * A test for [ImmutableSortedKeyListMultimap]. Started out as a copy of
 * ImmutableListMultimapTest.
 */
@RunWith(JUnit4::class)
class ImmutableSortedKeyListMultimapTest {
    @org.junit.Test
    fun builderPutAllIterable() {
        val builder
                : ImmutableSortedKeyListMultimap.Builder<String?, Int?> = ImmutableSortedKeyListMultimap.builder()
        builder.putAll("foo", mutableListOf<T?>(1, 2, 3))
        builder.putAll("bar", mutableListOf<T?>(4, 5))
        builder.putAll("foo", mutableListOf<T?>(6, 7))
        val multimap: com.google.common.collect.Multimap<String?, Int?>? = builder.build()
        Truth.assertThat(multimap).valuesForKey("foo").containsExactly(1, 2, 3, 6, 7).inOrder()
        Truth.assertThat(multimap).valuesForKey("bar").containsExactly(4, 5).inOrder()
        Truth.assertThat(multimap).hasSize(7)
    }

    @org.junit.Test
    fun builderPutAllVarargs() {
        val builder
                : ImmutableSortedKeyListMultimap.Builder<String?, Int?> = ImmutableSortedKeyListMultimap.builder()
        builder.putAll("foo", 1, 2, 3)
        builder.putAll("bar", 4, 5)
        builder.putAll("foo", 6, 7)
        val multimap: com.google.common.collect.Multimap<String?, Int?>? = builder.build()
        Truth.assertThat(multimap).valuesForKey("foo").containsExactly(1, 2, 3, 6, 7).inOrder()
        Truth.assertThat(multimap).valuesForKey("bar").containsExactly(4, 5).inOrder()
        Truth.assertThat(multimap).hasSize(7)
    }

    @org.junit.Test
    fun builderPutAllMultimap() {
        val toPut: com.google.common.collect.Multimap<String?, Int?> =
            com.google.common.collect.LinkedListMultimap.create<String?, Int?>()
        toPut.put("foo", 1)
        toPut.put("bar", 4)
        toPut.put("foo", 2)
        toPut.put("foo", 3)
        val moreToPut: com.google.common.collect.Multimap<String?, Int?> =
            com.google.common.collect.LinkedListMultimap.create<String?, Int?>()
        moreToPut.put("foo", 6)
        moreToPut.put("bar", 5)
        moreToPut.put("foo", 7)
        val builder
                : ImmutableSortedKeyListMultimap.Builder<String?, Int?> = ImmutableSortedKeyListMultimap.builder()
        builder.putAll(toPut)
        builder.putAll(moreToPut)
        val multimap: com.google.common.collect.Multimap<String?, Int?>? = builder.build()
        Truth.assertThat(multimap).valuesForKey("foo").containsExactly(1, 2, 3, 6, 7).inOrder()
        Truth.assertThat(multimap).valuesForKey("bar").containsExactly(4, 5).inOrder()
        Truth.assertThat(multimap).hasSize(7)
    }

    @org.junit.Test
    fun builderPutAllWithDuplicates() {
        val builder
                : ImmutableSortedKeyListMultimap.Builder<String?, Int?> = ImmutableSortedKeyListMultimap.builder()
        builder.putAll("foo", 1, 2, 3)
        builder.putAll("bar", 4, 5)
        builder.putAll("foo", 1, 6, 7)
        val multimap: ImmutableSortedKeyListMultimap<String?, Int?>? = builder.build()
        assertThat(multimap).valuesForKey("foo").containsExactly(1, 2, 3, 1, 6, 7).inOrder()
        assertThat(multimap).valuesForKey("bar").containsExactly(4, 5).inOrder()
        assertThat(multimap).hasSize(8)
    }

    @org.junit.Test
    fun builderPutWithDuplicates() {
        val builder
                : ImmutableSortedKeyListMultimap.Builder<String?, Int?> = ImmutableSortedKeyListMultimap.builder()
        builder.putAll("foo", 1, 2, 3)
        builder.putAll("bar", 4, 5)
        builder.put("foo", 1)
        val multimap: ImmutableSortedKeyListMultimap<String?, Int?>? = builder.build()
        assertThat(multimap).valuesForKey("foo").containsExactly(1, 2, 3, 1).inOrder()
        assertThat(multimap).valuesForKey("bar").containsExactly(4, 5).inOrder()
        assertThat(multimap).hasSize(6)
    }

    @org.junit.Test
    fun builderPutAllMultimapWithDuplicates() {
        val toPut: com.google.common.collect.Multimap<String?, Int?> =
            com.google.common.collect.LinkedListMultimap.create<String?, Int?>()
        toPut.put("foo", 1)
        toPut.put("bar", 4)
        toPut.put("foo", 2)
        toPut.put("foo", 1)
        toPut.put("bar", 5)
        val moreToPut: com.google.common.collect.Multimap<String?, Int?> =
            com.google.common.collect.LinkedListMultimap.create<String?, Int?>()
        moreToPut.put("foo", 6)
        moreToPut.put("bar", 4)
        moreToPut.put("foo", 7)
        moreToPut.put("foo", 2)
        val builder
                : ImmutableSortedKeyListMultimap.Builder<String?, Int?> = ImmutableSortedKeyListMultimap.builder()
        builder.putAll(toPut)
        builder.putAll(moreToPut)
        val multimap: com.google.common.collect.Multimap<String?, Int?>? = builder.build()
        Truth.assertThat(multimap).valuesForKey("foo").containsExactly(1, 2, 1, 6, 7, 2).inOrder()
        Truth.assertThat(multimap).valuesForKey("bar").containsExactly(4, 5, 4).inOrder()
        Truth.assertThat(multimap).hasSize(9)
    }

    @org.junit.Test
    fun builderPutNullKey() {
        val toPut: com.google.common.collect.Multimap<String?, Int?> =
            com.google.common.collect.LinkedListMultimap.create<String?, Int?>()
        toPut.put("foo", null)
        val builder
                : ImmutableSortedKeyListMultimap.Builder<String?, Int?> = ImmutableSortedKeyListMultimap.builder()
        org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
            java.lang.NullPointerException::class.java,
            org.junit.function.ThrowingRunnable { builder.put(null, 1) })
        org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
            java.lang.NullPointerException::class.java,
            org.junit.function.ThrowingRunnable { builder.putAll(null, mutableListOf<T?>(1, 2, 3)) })
        org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
            java.lang.NullPointerException::class.java,
            org.junit.function.ThrowingRunnable { builder.putAll(null, 1, 2, 3) })
        org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
            java.lang.NullPointerException::class.java,
            org.junit.function.ThrowingRunnable { builder.putAll(toPut) })
    }

    @org.junit.Test
    fun builderPutNullValue() {
        val toPut: com.google.common.collect.Multimap<String?, Int?> =
            com.google.common.collect.LinkedListMultimap.create<String?, Int?>()
        toPut.put(null, 1)
        val builder
                : ImmutableSortedKeyListMultimap.Builder<String?, Int?> = ImmutableSortedKeyListMultimap.builder()
        org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
            java.lang.NullPointerException::class.java,
            org.junit.function.ThrowingRunnable { builder.put("foo", null) })
        org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
            java.lang.NullPointerException::class.java,
            org.junit.function.ThrowingRunnable { builder.putAll("foo", mutableListOf<T?>(1, null, 3)) })
        org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
            java.lang.NullPointerException::class.java,
            org.junit.function.ThrowingRunnable { builder.putAll("foo", 1, null, 3) })
        org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
            java.lang.NullPointerException::class.java,
            org.junit.function.ThrowingRunnable { builder.putAll(toPut) })
    }

    @org.junit.Test
    fun copyOf() {
        val input: com.google.common.collect.ListMultimap<String?, Int?> =
            com.google.common.collect.ArrayListMultimap.create<String?, Int?>()
        input.put("foo", 1)
        input.put("bar", 2)
        input.put("foo", 3)
        val multimap: com.google.common.collect.Multimap<String?, Int?>? = ImmutableSortedKeyListMultimap.copyOf(input)
        Truth.assertThat(input).isEqualTo(multimap)
        Truth.assertThat(multimap).isEqualTo(input)
    }

    @org.junit.Test
    fun copyOfWithDuplicates() {
        val input: com.google.common.collect.ListMultimap<String?, Int?> =
            com.google.common.collect.ArrayListMultimap.create<String?, Int?>()
        input.put("foo", 1)
        input.put("bar", 2)
        input.put("foo", 3)
        input.put("foo", 1)
        val multimap: com.google.common.collect.Multimap<String?, Int?>? = ImmutableSortedKeyListMultimap.copyOf(input)
        Truth.assertThat(input).isEqualTo(multimap)
        Truth.assertThat(multimap).isEqualTo(input)
    }

    @org.junit.Test
    fun copyOfEmpty() {
        val input: com.google.common.collect.ListMultimap<String?, Int?> =
            com.google.common.collect.ArrayListMultimap.create<String?, Int?>()
        val multimap: com.google.common.collect.Multimap<String?, Int?>? = ImmutableSortedKeyListMultimap.copyOf(input)
        Truth.assertThat(input).isEqualTo(multimap)
        Truth.assertThat(multimap).isEqualTo(input)
    }

    @org.junit.Test
    fun copyOfImmutableListMultimap() {
        val multimap: com.google.common.collect.Multimap<String?, Int?> = createMultimap()
        assertThat(ImmutableSortedKeyListMultimap.copyOf(multimap)).isSameInstanceAs(multimap)
    }

    @org.junit.Test
    fun copyOfNullKey() {
        val input: com.google.common.collect.ListMultimap<String?, Int?> =
            com.google.common.collect.ArrayListMultimap.create<String?, Int?>()
        input.put(null, 1)
        org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
            java.lang.NullPointerException::class.java,
            org.junit.function.ThrowingRunnable { ImmutableSortedKeyListMultimap.copyOf(input) })
    }

    @org.junit.Test
    fun copyOfNullValue() {
        val input: com.google.common.collect.ListMultimap<String?, Int?> =
            com.google.common.collect.ArrayListMultimap.create<String?, Int?>()
        input.putAll("foo", mutableListOf<Int?>(1, null, 3))
        org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
            java.lang.NullPointerException::class.java,
            org.junit.function.ThrowingRunnable { ImmutableSortedKeyListMultimap.copyOf(input) })
    }

    @org.junit.Test
    fun emptyMultimapReads() {
        val multimap: com.google.common.collect.Multimap<String?, Int?> = ImmutableSortedKeyListMultimap.of()
        Truth.assertThat(multimap).doesNotContainKey("foo")
        Truth.assertThat(multimap.containsValue(1)).isFalse()
        Truth.assertThat(multimap).doesNotContainEntry("foo", 1)
        Truth.assertThat(multimap.entries()).isEmpty()
        Truth.assertThat(multimap == com.google.common.collect.ArrayListMultimap.create<Any?, Any?>()).isTrue()
        Truth.assertThat(multimap).valuesForKey("foo").isEqualTo(mutableListOf<Any?>())
        Truth.assertThat(multimap.hashCode()).isEqualTo(0)
        Truth.assertThat(multimap).isEmpty()
        Truth.assertThat(multimap.keys()).isEqualTo(com.google.common.collect.HashMultiset.create<Any?>())
        Truth.assertThat(multimap).isEmpty()
        Truth.assertThat(multimap).isEmpty()
        Truth.assertThat(multimap).isEmpty()
        Truth.assertThat(multimap.toString()).isEqualTo("{}")
    }

    @org.junit.Test
    fun emptyMultimapWrites() {
        val multimap: com.google.common.collect.Multimap<String?, Int?> = ImmutableSortedKeyListMultimap.of()
        UnmodifiableCollectionTests.assertMultimapIsUnmodifiable<String?, Int?>(
            multimap, "foo", 1
        )
    }

    private fun createMultimap(): com.google.common.collect.Multimap<String?, Int?> {
        return ImmutableSortedKeyListMultimap.< String, Integer>builder<kotlin.String?, Int?>()
        .put("foo", 1).put("bar", 2).put("foo", 3).build()
    }

    @org.junit.Test
    fun multimapReads() {
        val multimap: com.google.common.collect.Multimap<String?, Int?> = createMultimap()
        Truth.assertThat(multimap).containsKey("foo")
        Truth.assertThat(multimap).doesNotContainKey("cat")
        Truth.assertThat(multimap.containsValue(1)).isTrue()
        Truth.assertThat(multimap.containsValue(5)).isFalse()
        Truth.assertThat(multimap).containsEntry("foo", 1)
        Truth.assertThat(multimap).doesNotContainEntry("cat", 1)
        Truth.assertThat(multimap).doesNotContainEntry("foo", 5)
        Truth.assertThat(multimap.entries()).isNotEmpty()
        Truth.assertThat(multimap).hasSize(3)
        Truth.assertThat(multimap).isNotEmpty()
        Truth.assertThat(multimap.toString()).isEqualTo("{bar=[2], foo=[1, 3]}")
    }

    @org.junit.Test
    fun multimapWrites() {
        val multimap: com.google.common.collect.Multimap<String?, Int?> = createMultimap()
        UnmodifiableCollectionTests.assertMultimapIsUnmodifiable<String?, Int?>(
            multimap, "bar", 2
        )
    }

    @org.junit.Test
    fun multimapEquals() {
        val multimap: com.google.common.collect.Multimap<String?, Int?> = createMultimap()
        val arrayListMultimap
                : com.google.common.collect.Multimap<String?, Int?> =
            com.google.common.collect.ArrayListMultimap.create<String?, Int?>()
        arrayListMultimap.putAll("foo", mutableListOf<Int?>(1, 3))
        arrayListMultimap.put("bar", 2)

        EqualsTester()
            .addEqualityGroup(
                multimap, createMultimap(), arrayListMultimap,
                ImmutableSortedKeyListMultimap.< String, Integer > builder<String?, Int?>()
                    .put("bar", 2).put("foo", 1).put("foo", 3).build()
            )
            .addEqualityGroup(
                ImmutableSortedKeyListMultimap.< String, Integer > builder<String?, Int?>()
                    .put("bar", 2).put("foo", 3).put("foo", 1).build()
            )
            .addEqualityGroup(
                ImmutableSortedKeyListMultimap.< String, Integer > builder<String?, Int?>()
                    .put("foo", 2).put("foo", 3).put("foo", 1).build()
            )
            .addEqualityGroup(
                ImmutableSortedKeyListMultimap.< String, Integer > builder<String?, Int?>()
                    .put("bar", 2).put("foo", 3).build()
            )
            .testEquals()
    }

    @org.junit.Test
    fun asMap() {
        val builder
                : ImmutableSortedKeyListMultimap.Builder<String?, Int?> = ImmutableSortedKeyListMultimap.builder()
        builder.putAll("foo", mutableListOf<T?>(1, 2, 3))
        builder.putAll("bar", mutableListOf<T?>(4, 5))
        val map: MutableMap<String?, MutableCollection<Int?>?>? = builder.build().asMap()
        Truth.assertThat(map).containsEntry("foo", mutableListOf<Int?>(1, 2, 3))
        Truth.assertThat(map).containsEntry("bar", mutableListOf<Int?>(4, 5))
        Truth.assertThat(map).hasSize(2)
        Truth.assertThat(map).containsKey("foo")
        Truth.assertThat(map).containsKey("bar")
        Truth.assertThat(map).doesNotContainKey("notfoo")
    }

    @org.junit.Test
    fun asMapEntries() {
        val builder
                : ImmutableSortedKeyListMultimap.Builder<String?, Int?> = ImmutableSortedKeyListMultimap.builder()
        builder.putAll("foo", mutableListOf<T?>(1, 2, 3))
        builder.putAll("bar", mutableListOf<T?>(4, 5))
        val set: MutableSet<MutableMap.MutableEntry<String?, MutableCollection<Int?>?>?>? =
            builder.build().asMap().entrySet()
        val other: MutableSet<MutableMap.MutableEntry<String?, MutableCollection<Int?>?>?> =
            com.google.common.collect.ImmutableSet.builder<MutableMap.MutableEntry<String?, MutableCollection<Int?>?>?>()
                .add(SimpleImmutableEntry<String?, MutableCollection<Int?>?>("foo", mutableListOf<Int?>(1, 2, 3)))
                .add(SimpleImmutableEntry<String?, MutableCollection<Int?>?>("bar", mutableListOf<Int?>(4, 5)))
                .build()
        Truth.assertThat(set).isEqualTo(other)
    }
}
