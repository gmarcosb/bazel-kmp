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

import com.google.devtools.build.lib.collect.CompactImmutableMap
import com.google.devtools.build.lib.collect.ImmutableSharedKeyMap

/**
 * Provides a memory-efficient map when the key sets are likely to be shared between multiple
 * instances of this class.
 * 
 * 
 * This class is appropriate where it is expected that a lot of the key sets will be the same.
 * These key sets are shared and an offset table of indices is computed. Each map instance thus
 * contains only a reference to the shared offset table, and a plain array of instances.
 * 
 * 
 * The map is sensitive to insertion order. Two maps with different insertion orders are *not*
 * considered equal, and will not share keys.
 * 
 * 
 * This class explicitly does *not* implement the Map interface, as use of that would lead to a
 * lot of GC churn.
 */
@javax.annotation.concurrent.Immutable
class ImmutableSharedKeyMap<K, V> protected constructor(keys: Array<Any?>, values: Array<Any>) :
    CompactImmutableMap<K?, V?> {
    private val offsetTable: OffsetTable<K?>

    // If size is 1, this is the value itself.
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    protected val values: Any

    private class OffsetTable<K>(val keys: Array<Any?>) {
        // Keep a map around to speed up get lookups for larger maps.
        // We make this value lazy to avoid computing for values that end up being thrown away
        // during interning anyway (the majority).
        @kotlin.concurrent.Volatile
        private var indexMap: com.google.common.collect.ImmutableMap<K?, Int?>? = null

        fun initIndexMap() {
            if (indexMap == null) {
                synchronized(this) {
                    if (indexMap == null) {
                        val builder: com.google.common.collect.ImmutableMap.Builder<K?, Int?> =
                            com.google.common.collect.ImmutableMap.builder<K?, Int?>()
                        for (i in keys.indices) {
                            val key = keys[i] as K?
                            builder.put(key, i)
                        }
                        this.indexMap = builder.buildOrThrow()
                    }
                }
            }
        }

        fun offsetForKey(key: K?): Int {
            return indexMap.getOrDefault(key, -1)
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is OffsetTable<*>) {
                return false
            }
            return java.util.Arrays.equals(this.keys, o.keys)
        }

        override fun hashCode(): Int {
            return java.util.Arrays.hashCode(keys)
        }
    }

    init {
        com.google.common.base.Preconditions.checkArgument(keys.length == values.length)
        this.offsetTable = createOffsetTable<K?>(keys)
        if (values.length == 1) {
            this.values = values[0]
        } else {
            this.values = values
        }
    }

    override fun get(key: K?): V? {
        val offset = offsetTable.offsetForKey(key)
        if (offset == -1) {
            return null
        }
        val size: Int = offsetTable.keys.length
        if (size == 1) {
            return values as V?
        }
        return (values as Array<Any?>?)!![offset] as V?
    }

    override fun size(): Int {
        return offsetTable.keys.length
    }

    override fun keyAt(index: Int): K? {
        return offsetTable.keys[index] as K?
    }

    override fun valueAt(index: Int): V? {
        val size: Int = offsetTable.keys.length
        if (size == 1) {
            com.google.common.base.Preconditions.checkElementIndex(index, 1)
            return values as V?
        }
        return (values as Array<Any?>?)!![index] as V?
    }

    @get:com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    @get:Deprecated("")
    val keys: Array<Any?>
        /** Do not use! Present only for serialization. (Annotated as @Deprecated just to prevent use.)  */
        get() = offsetTable.keys

    @get:com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    @get:Deprecated("")
    val valuesAsArray: Array<Any?>?
        /** Do not use! Present only for serialization. (Annotated as @Deprecated just to prevent use.)  */
        get() {
            val size: Int = offsetTable.keys.length
            if (size == 1) {
                return arrayOf<Any?>(values)
            }
            return values as Array<Any?>?
        }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o == null || getClass() != o.getClass()) {
            return false
        }
        val that = o as ImmutableSharedKeyMap<*, *>
        if (offsetTable !== that.offsetTable) {
            return false
        }
        val size: Int = offsetTable.keys.length
        if (size == 1) {
            return values == that.values
        }
        return java.util.Arrays.equals(values as Array<Any?>?, that.values as Array<Any?>?)
    }

    override fun hashCode(): Int {
        val size: Int = offsetTable.keys.length
        if (size == 1) {
            return java.util.Objects.hash(offsetTable, values)
        }
        return java.util.Objects.hash(offsetTable, java.util.Arrays.hashCode(values as Array<Any?>?))
    }

    /** Builder for [ImmutableSharedKeyMap].  */
    class Builder<K, V> private constructor() {
        private val entries: MutableList<Any?> = java.util.ArrayList<Any?>()

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun put(key: K?, value: V?): Builder<K?, V?> {
            entries.add(key)
            entries.add(value)
            return this
        }

        fun build(): ImmutableSharedKeyMap<K?, V?> {
            val count: Int = entries.size() / 2
            val keys = arrayOfNulls<Any>(count)
            val values: Array<Any> = arrayOfNulls<Any>(count)
            var entryIndex = 0
            for (i in 0..<count) {
                keys[i] = entries.get(entryIndex++)
                values[i] = entries.get(entryIndex++)!!
            }
            return ImmutableSharedKeyMap<K?, V?>(keys, values)
        }
    }

    companion object {
        private val offsetTables: com.google.common.collect.Interner<OffsetTable<*>?> =
            com.google.devtools.build.lib.concurrent.BlazeInterners.newWeakInterner<OffsetTable<*>?>()

        private fun <K> createOffsetTable(keys: Array<Any?>): OffsetTable<K?> {
            val offsetTable = OffsetTable<K?>(keys)
            val internedTable = offsetTables.intern(offsetTable) as OffsetTable<K?>
            internedTable.initIndexMap()
            return internedTable
        }

        /**
         * Creates an [ImmutableSharedKeyMap] directly from an [ImmutableMap].
         * 
         * 
         * This is a more efficient alternative to using a [Builder] when the input is already in
         * the form of an [ImmutableMap].
         * 
         * 
         * This method could accept a more general type of [java.util.Map], but it is
         * intentionally overly strict to ensure that copies are only made from a type with a meaningful
         * iteration order (and because there is no current use case for other types of maps).
         */
        fun <K, V> copyOf(map: com.google.common.collect.ImmutableMap<K?, V?>): ImmutableSharedKeyMap<K?, V?> {
            return ImmutableSharedKeyMap<K?, V?>(map.keySet().toArray(), map.values().toArray())
        }

        @kotlin.jvm.JvmStatic
        fun <K, V> builder(): Builder<K?, V?> {
            return com.google.devtools.build.lib.collect.ImmutableSharedKeyMap.Builder<K?, V?>()
        }
    }
}
