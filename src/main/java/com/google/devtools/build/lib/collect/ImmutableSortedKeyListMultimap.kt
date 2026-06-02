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

import com.google.devtools.build.lib.collect.ImmutableSortedKeyListMultimap
import java.util.AbstractCollection
import java.util.AbstractMap
import java.util.AbstractMap.SimpleImmutableEntry
import java.util.Collections

/**
 * A immutable multimap implementation for multimaps with comparable keys. It uses a sorted array
 * and binary search to return the correct values. It's only purpose is to save memory - it consumes
 * only about half the memory of the equivalent ImmutableListMultimap. Only a few methods are
 * efficiently implemented: [.isEmpty] is O(1), [.get] and [.containsKey] are
 * O(log(n)), and [.asMap] and [.values] refer to the parent instance. All other methods
 * can take O(n) or even make a copy of the contents.
 * 
 * 
 * This implementation supports neither `null` keys nor `null` values.
 */
class ImmutableSortedKeyListMultimap<K : Comparable<K?>?, V>
private constructor(private val sortedKeys: Array<K?>, private val values: Array<MutableList<V?>?>) :
    com.google.common.collect.ListMultimap<K?, V?> {
    /**
     * A builder class for ImmutableSortedKeyListMultimap<K></K>, V> instances.
     */
    class Builder<K : Comparable<K?>?, V> internal constructor() {
        private val builderMultimap: com.google.common.collect.Multimap<K?, V?> =
            com.google.common.collect.ArrayListMultimap.create<K?, V?>()

        fun build(): ImmutableSortedKeyListMultimap<K?, V?>? {
            return copyOf<K?, V?>(builderMultimap)
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun put(key: K?, value: V?): Builder<K?, V?> {
            builderMultimap.put(
                com.google.common.base.Preconditions.checkNotNull<K?>(key),
                com.google.common.base.Preconditions.checkNotNull<V?>(value)
            )
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun putAll(key: K?, values: MutableCollection<out V?>): Builder<K?, V?> {
            val valueList: MutableCollection<V?> =
                builderMultimap.get(com.google.common.base.Preconditions.checkNotNull<K?>(key))
            for (value in values) {
                valueList.add(com.google.common.base.Preconditions.checkNotNull<V?>(value))
            }
            return this
        }

        fun putAll(key: K?, vararg values: V?): Builder<K?, V?> {
            return putAll(
                com.google.common.base.Preconditions.checkNotNull<K?>(key),
                java.util.Arrays.asList<V?>(*values)
            )
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun putAll(multimap: com.google.common.collect.Multimap<out K?, out V?>): Builder<K?, V?> {
            multimap.asMap()
                .forEach { key: K?, collectionValue: MutableCollection<V?>? -> putAll(key, collectionValue!!) }
            return this
        }
    }

    /**
     * An implementation for the Multimap.asMap method. Note that AbstractMap already provides
     * implementations for all methods except [.entrySet], but we override a few here because we
     * can do it much faster than the existing entrySet-based implementations. Also note that it
     * inherits the type parameters K and V from the parent class.
     */
    private inner class AsMap : AbstractMap<K?, MutableCollection<V?>?>() {
        override fun size(): Int {
            return sortedKeys.length
        }

        override fun containsKey(key: Any?): Boolean {
            return this@ImmutableSortedKeyListMultimap.containsKey(key)
        }

        override fun get(key: Any?): MutableCollection<V?>? {
            val index: Int = java.util.Arrays.binarySearch(sortedKeys, key)
            // Note the different semantic between Map and Multimap.
            return if (index >= 0) values[index] else null
        }

        override fun remove(key: Any?): MutableCollection<V?>? {
            throw java.lang.UnsupportedOperationException()
        }

        override fun clear() {
            throw java.lang.UnsupportedOperationException()
        }

        override fun entrySet(): MutableSet<MutableMap.MutableEntry<K?, MutableCollection<V?>?>?> {
            val builder: com.google.common.collect.ImmutableSet.Builder<MutableMap.MutableEntry<K?, MutableCollection<V?>?>?> =
                com.google.common.collect.ImmutableSet.builder<MutableMap.MutableEntry<K?, MutableCollection<V?>?>?>()
            for (i in sortedKeys.indices) {
                builder.add(SimpleImmutableEntry<K?, MutableCollection<V?>?>(sortedKeys[i], values[i]))
            }
            return builder.build()
        }
    }

    private inner class ValuesCollection : AbstractCollection<V?>() {
        override fun size(): Int {
            return this@ImmutableSortedKeyListMultimap.size()
        }

        val isEmpty: Boolean
            get() = sortedKeys.length == 0

        override fun contains(o: Any?): Boolean {
            return this@ImmutableSortedKeyListMultimap.containsValue(o)
        }

        override fun iterator(): MutableIterator<V?>? {
            if (this.isEmpty) {
                return Collections.emptyIterator<V?>()
            }
            return object : com.google.common.collect.AbstractIterator<V?>() {
                private var currentList = 0
                private var currentIndex = 0

                override fun computeNext(): V? {
                    if (currentList >= values.length) {
                        return endOfData()
                    }
                    val result = values[currentList]!!.get(currentIndex)
                    // Find the next list/index pair.
                    currentIndex++
                    if (currentIndex >= values[currentList].size()) {
                        currentIndex = 0
                        currentList++
                    }
                    return result
                }
            }
        }

        override fun remove(o: Any?): Boolean {
            throw java.lang.UnsupportedOperationException()
        }

        override fun removeAll(c: MutableCollection<*>?): Boolean {
            throw java.lang.UnsupportedOperationException()
        }

        override fun retainAll(c: MutableCollection<*>?): Boolean {
            throw java.lang.UnsupportedOperationException()
        }

        override fun clear() {
            throw java.lang.UnsupportedOperationException()
        }
    }

    override fun size(): Int {
        return com.google.common.primitives.Ints.saturatedCast(
            java.util.Arrays.stream<MutableList<V?>?>(values)
                .mapToLong(java.util.function.ToLongFunction { obj: MutableList<V?>? -> obj.size() }).sum()
        )
    }

    val isEmpty: Boolean
        get() = sortedKeys.length == 0

    override fun containsKey(key: Any?): Boolean {
        val index: Int = java.util.Arrays.binarySearch(sortedKeys, key)
        return index >= 0
    }

    override fun containsValue(value: Any?): Boolean {
        return java.util.Arrays.stream<MutableList<V?>?>(values)
            .anyMatch(java.util.function.Predicate { list: MutableList<V?>? -> list!!.contains(value) })
    }

    override fun containsEntry(key: Any?, value: Any?): Boolean {
        val index: Int = java.util.Arrays.binarySearch(sortedKeys, key)
        return index >= 0 && values[index]!!.contains(value)
    }

    override fun put(key: K?, value: V?): Boolean {
        throw java.lang.UnsupportedOperationException()
    }

    override fun remove(key: Any?, value: Any?): Boolean {
        throw java.lang.UnsupportedOperationException()
    }

    override fun putAll(key: K?, values: Iterable<out V?>): Boolean {
        throw java.lang.UnsupportedOperationException()
    }

    override fun putAll(multimap: com.google.common.collect.Multimap<out K?, out V?>): Boolean {
        throw java.lang.UnsupportedOperationException()
    }

    override fun replaceValues(key: K?, values: Iterable<out V?>): MutableList<V?> {
        throw java.lang.UnsupportedOperationException()
    }

    override fun removeAll(key: Any?): MutableList<V?> {
        throw java.lang.UnsupportedOperationException()
    }

    override fun clear() {
        throw java.lang.UnsupportedOperationException()
    }

    override fun get(key: K?): MutableList<V?> {
        val index: Int = java.util.Arrays.binarySearch(sortedKeys, key)
        return (if (index >= 0) values[index] else com.google.common.collect.ImmutableList.of<V?>())!!
    }

    override fun keySet(): MutableSet<K?> {
        return com.google.common.collect.ImmutableSet.copyOf<K?>(sortedKeys)
    }

    override fun keys(): com.google.common.collect.Multiset<K?> {
        return com.google.common.collect.ImmutableMultiset.copyOf<K?>(sortedKeys)
    }

    override fun values(): MutableCollection<V?> {
        return ValuesCollection()
    }

    override fun entries(): MutableCollection<MutableMap.MutableEntry<K?, V?>?> {
        val builder: com.google.common.collect.ImmutableList.Builder<MutableMap.MutableEntry<K?, V?>?> =
            com.google.common.collect.ImmutableList.builder<MutableMap.MutableEntry<K?, V?>?>()
        for (i in sortedKeys.indices) {
            for (value in values[i]!!) {
                builder.add(SimpleImmutableEntry<K?, V?>(sortedKeys[i], value))
            }
        }
        return builder.build()
    }

    /**
     * {@inheritDoc}
     * 
     * 
     * Note that only `get` and `containsKey` are implemented efficiently on the
     * returned map.
     */
    override fun asMap(): MutableMap<K?, MutableCollection<V?>?> {
        return com.google.devtools.build.lib.collect.ImmutableSortedKeyListMultimap.AsMap()
    }

    override fun toString(): String {
        return asMap().toString()
    }

    override fun hashCode(): Int {
        return asMap().hashCode()
    }

    override fun equals(`object`: Any?): Boolean {
        if (this === `object`) {
            return true
        }
        if (`object` is com.google.common.collect.Multimap<*, *>) {
            return asMap() == `object`.asMap()
        }
        return false
    }

    companion object {
        private val EMPTY_MULTIMAP: ImmutableSortedKeyListMultimap<*, *> = ImmutableSortedKeyListMultimap<Any?, Any?>(
            arrayOfNulls<Comparable<*>>(0), arrayOfNulls<MutableList<*>>(0)
        )

        /** Returns the empty multimap.  */
        @kotlin.jvm.JvmStatic
        fun <K : Comparable<K?>?, V> of(): ImmutableSortedKeyListMultimap<K?, V?> {
            // Safe because the multimap will never hold any elements.
            return EMPTY_MULTIMAP
        }

        fun <K : Comparable<K?>?, V> copyOf(
            data: com.google.common.collect.Multimap<K?, V?>
        ): ImmutableSortedKeyListMultimap<K?, V?>? {
            if (data.isEmpty()) {
                return EMPTY_MULTIMAP
            }
            if (data is ImmutableSortedKeyListMultimap<*, *>) {
                return data as ImmutableSortedKeyListMultimap<K?, V?>
            }
            val keySet: MutableSet<K?> = data.keySet()
            val size: Int = keySet.size()
            val sortedKeys = arrayOfNulls<Comparable<*>>(size) as Array<K?>
            var index = 0
            for (key in keySet) {
                sortedKeys[index++] = com.google.common.base.Preconditions.checkNotNull<K?>(key)
            }
            java.util.Arrays.sort(sortedKeys)
            val values = arrayOfNulls<MutableList<*>>(size) as Array<MutableList<V?>?>
            for (i in 0..<size) {
                values[i] = com.google.common.collect.ImmutableList.copyOf<V?>(data.get(sortedKeys[i]))
            }
            return ImmutableSortedKeyListMultimap<K?, V?>(sortedKeys, values)
        }

        @kotlin.jvm.JvmStatic
        fun <K : Comparable<K?>?, V> builder(): Builder<K?, V?> {
            return com.google.devtools.build.lib.collect.ImmutableSortedKeyListMultimap.Builder<K?, V?>()
        }
    }
}
