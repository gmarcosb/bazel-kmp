// Copyright 2026 The Bazel Authors. All rights reserved.
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
package net.starlark.java.eval

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import java.util.AbstractSet
import java.util.Collections

/**
 * A deeply immutable [Dict] with a custom memory-efficient implementation.
 * 
 * 
 * Construct an instance by calling [.copyOf]. Iteration order of the given map is
 * preserved.
 * 
 * 
 * Size cutoffs for the various specialized implementations were chosen using the frequency
 * distribution of dict instances from an example large build in b/507408768#comment3. Compared to
 * [ImmutableMap], additional memory savings come from:
 * 
 * 
 *  1. All sizes: no caching of collection views in [.keySet], [.values], and [       ][.entrySet].
 *  1. Size 2: dedicated [DoubletonImmutableDict].
 *  1. Sizes 3+: [ArrayImmutableDict] shares backing key and value arrays with [       ].
 *  1. Sizes 3-8: [LinearImmutableDict] uses linear search with no hash table.
 *  1. Sizes 9+: [HashImmutableDict] uses open hashing instead of entry wrappers.
 * 
 * 
 * 
 * [.equals] and [.hashCode] are order-independent and compatible with arbitrary
 * [Map] instances. All [.equals] implementations catch [ClassCastException] and
 * [NullPointerException] when calling [Map.get] on the given map. This is required by
 * the [Map.equals] contract to safely handle comparisons with arbitrary or type-restricted
 * maps where our keys might be incompatible.
 */
internal abstract class CompactImmutableDict<K, V> : net.starlark.java.eval.Dict<K?, V?>() {
    override fun mutability(): net.starlark.java.eval.Mutability? {
        return net.starlark.java.eval.Mutability.Companion.IMMUTABLE
    }

    override fun updateIteratorCount(delta: Int): Boolean {
        return false
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun putEntry(key: K?, value: V?) {
        throw immutable()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun <K2 : K?, V2 : V?> putEntries(map: MutableMap<K2?, V2?>?) {
        throw immutable()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun clearEntries() {
        throw immutable()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun pop(key: Any?, defaultValue: Any?, thread: net.starlark.java.eval.StarlarkThread?): Any? {
        throw immutable()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun popitem(): net.starlark.java.eval.Tuple? {
        if (isEmpty()) {
            throw net.starlark.java.eval.Starlark.Companion.errorf("popitem: empty dictionary")
        }
        throw immutable()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun setdefault(key: K?, defaultValue: V?): V? {
        throw immutable()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    private fun immutable(): net.starlark.java.eval.EvalException? {
        net.starlark.java.eval.Starlark.Companion.checkMutable(this)
        throw java.lang.IllegalStateException()
    }

    /** Specialized singleton implementation for an empty dict.  */
    private class EmptyImmutableDict<K, V> : CompactImmutableDict<K?, V?>() {
        override fun values0(thread: net.starlark.java.eval.StarlarkThread): net.starlark.java.eval.StarlarkList<*>? {
            return net.starlark.java.eval.StarlarkList.Companion.newList<Any?>(thread.mutability())
        }

        override fun items(thread: net.starlark.java.eval.StarlarkThread): net.starlark.java.eval.StarlarkList<*>? {
            return net.starlark.java.eval.StarlarkList.Companion.newList<Any?>(thread.mutability())
        }

        override fun keys(thread: net.starlark.java.eval.StarlarkThread): net.starlark.java.eval.StarlarkList<*>? {
            return net.starlark.java.eval.StarlarkList.Companion.newList<Any?>(thread.mutability())
        }

        override fun iterator(): MutableIterator<K?>? {
            return Collections.emptyIterator<K?>()
        }

        override fun size(): Int {
            return 0
        }

        override fun containsKey(key: Any?): Boolean {
            return false
        }

        override fun containsValue(value: Any?): Boolean {
            return false
        }

        override fun get(key: Any?): V? {
            return null
        }

        override fun keySet(): com.google.common.collect.ImmutableSet<K?> {
            return com.google.common.collect.ImmutableSet.of<K?>()
        }

        override fun values(): com.google.common.collect.ImmutableList<V?> {
            return com.google.common.collect.ImmutableList.of<V?>()
        }

        override fun entrySet(): com.google.common.collect.ImmutableSet<MutableMap.MutableEntry<K?, V?>?> {
            return com.google.common.collect.ImmutableSet.of<MutableMap.MutableEntry<K?, V?>?>()
        }

        override fun forEach(action: java.util.function.BiConsumer<in K?, in V?>?) {
            com.google.common.base.Preconditions.checkNotNull(action)
        }

        override fun hashCode(): Int {
            return 0
        }

        override fun equals(o: Any?): Boolean {
            if (o === this) {
                return true
            }
            return o is MutableMap<*, *> && o.isEmpty()
        }

        companion object {
            val INSTANCE: EmptyImmutableDict<*, *> =
                net.starlark.java.eval.CompactImmutableDict.EmptyImmutableDict<Any?, Any?>()
        }
    }

    /** Specialized implementation for a dict of size 1.  */
    private class SingletonImmutableDict<K, V>(private val k: K?, private val v: V?) : CompactImmutableDict<K?, V?>() {
        override fun values0(thread: net.starlark.java.eval.StarlarkThread): net.starlark.java.eval.StarlarkList<*>? {
            return net.starlark.java.eval.StarlarkList.Companion.wrap<Any?>(thread.mutability(), arrayOf<Any?>(v))
        }

        override fun items(thread: net.starlark.java.eval.StarlarkThread): net.starlark.java.eval.StarlarkList<*>? {
            return net.starlark.java.eval.StarlarkList.Companion.wrap<Any?>(
                thread.mutability(),
                arrayOf<Any?>(net.starlark.java.eval.Tuple.Companion.pair(k, v))
            )
        }

        override fun keys(thread: net.starlark.java.eval.StarlarkThread): net.starlark.java.eval.StarlarkList<*>? {
            return net.starlark.java.eval.StarlarkList.Companion.wrap<Any?>(thread.mutability(), arrayOf<Any?>(k))
        }

        override fun iterator(): MutableIterator<K?> {
            return com.google.common.collect.Iterators.singletonIterator<K?>(k)
        }

        override fun size(): Int {
            return 1
        }

        override fun containsKey(key: Any?): Boolean {
            return k == key
        }

        override fun containsValue(value: Any?): Boolean {
            return v == value
        }

        override fun get(key: Any?): V? {
            return if (k == key) v else null
        }

        override fun keySet(): com.google.common.collect.ImmutableSet<K?> {
            return com.google.common.collect.ImmutableSet.of<K?>(k)
        }

        override fun values(): com.google.common.collect.ImmutableList<V?> {
            return com.google.common.collect.ImmutableList.of<V?>(v)
        }

        override fun entrySet(): com.google.common.collect.ImmutableSet<MutableMap.MutableEntry<K?, V?>?> {
            return com.google.common.collect.ImmutableSet.of<MutableMap.MutableEntry<K?, V?>?>(
                com.google.common.collect.Maps.immutableEntry<K?, V?>(
                    k,
                    v
                )
            )
        }

        override fun forEach(action: java.util.function.BiConsumer<in K?, in V?>) {
            action.accept(k, v)
        }

        override fun hashCode(): Int {
            return k!!.hashCode() xor v!!.hashCode()
        }

        override fun equals(o: Any?): Boolean {
            if (o === this) {
                return true
            }
            if (o !is MutableMap<*, *>) {
                return false
            }
            if (o.size() != 1) {
                return false
            }
            try {
                return v == o.get(k)
            } catch (unused: java.lang.ClassCastException) {
                return false
            } catch (unused: java.lang.NullPointerException) {
                return false
            }
        }
    }

    /** Specialized implementation for a dict of size 2.  */
    private class DoubletonImmutableDict<K, V>(
        private val k1: K?,
        private val v1: V?,
        private val k2: K?,
        private val v2: V?
    ) : CompactImmutableDict<K?, V?>() {
        override fun values0(thread: net.starlark.java.eval.StarlarkThread): net.starlark.java.eval.StarlarkList<*>? {
            return net.starlark.java.eval.StarlarkList.Companion.wrap<Any?>(thread.mutability(), arrayOf<Any?>(v1, v2))
        }

        override fun items(thread: net.starlark.java.eval.StarlarkThread): net.starlark.java.eval.StarlarkList<*>? {
            return net.starlark.java.eval.StarlarkList.Companion.wrap<Any?>(
                thread.mutability(),
                arrayOf<Any?>(
                    net.starlark.java.eval.Tuple.Companion.pair(k1, v1),
                    net.starlark.java.eval.Tuple.Companion.pair(k2, v2)
                )
            )
        }

        override fun keys(thread: net.starlark.java.eval.StarlarkThread): net.starlark.java.eval.StarlarkList<*>? {
            return net.starlark.java.eval.StarlarkList.Companion.wrap<Any?>(thread.mutability(), arrayOf<Any?>(k1, k2))
        }

        override fun iterator(): MutableIterator<K?> {
            return com.google.common.collect.Iterators.forArray<K?>(k1, k2)
        }

        override fun size(): Int {
            return 2
        }

        override fun containsKey(key: Any?): Boolean {
            return k1 == key || k2 == key
        }

        override fun containsValue(value: Any?): Boolean {
            return v1 == value || v2 == value
        }

        override fun get(key: Any?): V? {
            if (k1 == key) {
                return v1
            }
            if (k2 == key) {
                return v2
            }
            return null
        }

        override fun keySet(): com.google.common.collect.ImmutableSet<K?> {
            return com.google.common.collect.ImmutableSet.of<K?>(k1, k2)
        }

        override fun values(): com.google.common.collect.ImmutableList<V?> {
            return com.google.common.collect.ImmutableList.of<V?>(v1, v2)
        }

        override fun entrySet(): com.google.common.collect.ImmutableSet<MutableMap.MutableEntry<K?, V?>?> {
            return com.google.common.collect.ImmutableSet.of<MutableMap.MutableEntry<K?, V?>?>(
                com.google.common.collect.Maps.immutableEntry<K?, V?>(
                    k1,
                    v1
                ), com.google.common.collect.Maps.immutableEntry<K?, V?>(k2, v2)
            )
        }

        override fun forEach(action: java.util.function.BiConsumer<in K?, in V?>) {
            action.accept(k1, v1)
            action.accept(k2, v2)
        }

        override fun hashCode(): Int {
            return (k1!!.hashCode() xor v1!!.hashCode()) + (k2!!.hashCode() xor v2!!.hashCode())
        }

        override fun equals(o: Any?): Boolean {
            if (o === this) {
                return true
            }
            if (o !is MutableMap<*, *>) {
                return false
            }
            if (o.size() != 2) {
                return false
            }
            try {
                return v1 == o.get(k1) && v2 == o.get(k2)
            } catch (unused: java.lang.ClassCastException) {
                return false
            } catch (unused: java.lang.NullPointerException) {
                return false
            }
        }
    }

    /** Partial implementation based on parallel key-value arrays.  */
    private abstract class ArrayImmutableDict<K, V>(val ks: Array<K?>, val vs: Array<V?>) :
        CompactImmutableDict<K?, V?>() {
        override fun values0(thread: net.starlark.java.eval.StarlarkThread): net.starlark.java.eval.StarlarkList<*>? {
            return net.starlark.java.eval.StarlarkList.Companion.wrap<Any?>(thread.mutability(), vs.clone())
        }

        override fun items(thread: net.starlark.java.eval.StarlarkThread): net.starlark.java.eval.StarlarkList<*>? {
            val items = arrayOfNulls<Any>(ks.size)
            for (i in ks.indices) {
                items[i] = net.starlark.java.eval.Tuple.Companion.pair(ks[i], vs[i])
            }
            return net.starlark.java.eval.StarlarkList.Companion.wrap<Any?>(thread.mutability(), items)
        }

        override fun keys(thread: net.starlark.java.eval.StarlarkThread): net.starlark.java.eval.StarlarkList<*>? {
            return net.starlark.java.eval.StarlarkList.Companion.wrap<Any?>(thread.mutability(), ks.clone())
        }

        override fun iterator(): MutableIterator<K?> {
            return com.google.common.collect.Iterators.forArray<K?>(*ks)
        }

        override fun size(): Int {
            return ks.size
        }

        override fun containsValue(value: Any?): Boolean {
            if (value == null) {
                return false
            }
            for (v in vs) {
                if (v == value) {
                    return true
                }
            }
            return false
        }

        override fun keySet(): MutableSet<K?> {
            return object : AbstractSet<K?>() {
                override fun iterator(): MutableIterator<K?> {
                    return com.google.common.collect.Iterators.forArray<K?>(*ks)
                }

                override fun size(): Int {
                    return ks.size
                }

                override fun contains(o: Any?): Boolean {
                    return containsKey(o)
                }
            }
        }

        override fun values(): net.starlark.java.eval.StarlarkList<V?> {
            return net.starlark.java.eval.RegularImmutableStarlarkList<V?>(vs)
        }

        override fun entrySet(): MutableSet<MutableMap.MutableEntry<K?, V?>?> {
            return object : AbstractSet<MutableMap.MutableEntry<K?, V?>?>() {
                override fun iterator(): MutableIterator<MutableMap.MutableEntry<K?, V?>?> {
                    return object : MutableIterator<MutableMap.MutableEntry<K?, V?>?> {
                        private var i = 0

                        override fun hasNext(): Boolean {
                            return i < ks.size
                        }

                        override fun next(): MutableMap.MutableEntry<K?, V?> {
                            if (!hasNext()) {
                                throw java.util.NoSuchElementException()
                            }
                            val e: MutableMap.MutableEntry<K?, V?> =
                                com.google.common.collect.Maps.immutableEntry<K?, V?>(ks[i], vs[i])
                            i++
                            return e
                        }
                    }
                }

                override fun size(): Int {
                    return ks.size
                }

                override fun contains(o: Any?): Boolean {
                    if (o !is MutableMap.MutableEntry<*, *> || o.getValue() == null) {
                        return false
                    }
                    return o.getValue() == get(o.getKey())
                }
            }
        }

        override fun forEach(action: java.util.function.BiConsumer<in K?, in V?>) {
            for (i in ks.indices) {
                action.accept(ks[i], vs[i])
            }
        }

        override fun hashCode(): Int {
            var h = 0
            for (i in ks.indices) {
                h += (ks[i]!!.hashCode() xor vs[i]!!.hashCode())
            }
            return h
        }

        override fun equals(o: Any?): Boolean {
            if (o === this) {
                return true
            }
            if (o !is MutableMap<*, *>) {
                return false
            }
            if (o.size() != ks.size) {
                return false
            }
            try {
                for (i in ks.indices) {
                    if (vs[i] != o.get(ks[i])) {
                        return false
                    }
                }
                return true
            } catch (unused: java.lang.ClassCastException) {
                return false
            } catch (unused: java.lang.NullPointerException) {
                return false
            }
        }
    }

    /**
     * Implementation for small dicts where linear search is expected to perform just as well as a
     * hash table.
     */
    private class LinearImmutableDict<K, V>(ks: Array<K?>, vs: Array<V?>) : ArrayImmutableDict<K?, V?>(ks, vs) {
        override fun containsKey(key: Any?): Boolean {
            if (key == null) {
                return false
            }
            for (k in ks) {
                if (key == k) {
                    return true
                }
            }
            return false
        }

        override fun get(key: Any?): V? {
            if (key == null) {
                return null
            }
            for (i in ks.indices) {
                if (key == ks[i]) {
                    return vs[i]
                }
            }
            return null
        }
    }

    /** Open hash table implementation.  */
    private class HashImmutableDict<K, V>(ks: Array<K?>, vs: Array<V?>) : ArrayImmutableDict<K?, V?>(ks, vs) {
        // Values are the index of the corresponding element in ks and vs, or -1 for empty.
        private val table: IntArray

        init {
            val n = ks.size
            val tableSize = n * 2 // 0.5 load factor.
            val table = IntArray(tableSize)
            java.util.Arrays.fill(table, -1)

            for (i in 0..<n) {
                var idx: Int = net.starlark.java.eval.CompactImmutableDict.HashImmutableDict.Companion.getTableIndex(
                    ks[i],
                    tableSize
                )
                while (table[idx] != -1) {
                    if (++idx == tableSize) {
                        idx = 0
                    }
                }
                table[idx] = i
            }
            this.table = table
        }

        fun getTableIndex(k: Any): Int {
            return net.starlark.java.eval.CompactImmutableDict.HashImmutableDict.Companion.getTableIndex(k, table.size)
        }

        override fun containsKey(key: Any?): Boolean {
            return get(key) != null
        }

        override fun get(key: Any?): V? {
            if (key == null) {
                return null
            }
            var tableIdx = getTableIndex(key)
            var kvIdx: Int
            while ((table[tableIdx].also { kvIdx = it }) != -1) {
                if (key == ks[kvIdx]) {
                    return vs[kvIdx]
                }
                if (++tableIdx == table.size) {
                    tableIdx = 0
                }
            }
            return null
        }

        companion object {
            private fun getTableIndex(k: Any, tableSize: Int): Int {
                var hash = k.hashCode()
                hash = hash xor (hash ushr 16)
                return (hash and 0x7fffffff) % tableSize
            }
        }
    }

    companion object {
        fun <K, V> empty(): CompactImmutableDict<K?, V?> {
            return net.starlark.java.eval.CompactImmutableDict.EmptyImmutableDict.Companion.INSTANCE as CompactImmutableDict<K?, V?>
        }

        /**
         * Creates an immutable, compact version of the given map.
         * 
         * 
         * Callers are responsible for ensuring that all keys are [ hashable][Starlark.checkHashable] and all values are [valid][Starlark.checkValid] starlark objects, which
         * implies that they are non-null.
         */
        fun <K, V> copyOf(m: MutableMap<out K?, out V?>): CompactImmutableDict<K?, V?>? {
            if (m is CompactImmutableDict<*, *>) {
                return m as CompactImmutableDict<K?, V?>
            }
            val size: Int = m.size()
            return when (size) {
                0 -> net.starlark.java.eval.CompactImmutableDict.Companion.empty<K?, V?>()
                1 -> {
                    val e: MutableMap.MutableEntry<out K?, out V?> = m.entrySet().iterator().next()
                    net.starlark.java.eval.CompactImmutableDict.SingletonImmutableDict<K?, V?>(e.getKey(), e.getValue())
                }

                2 -> {
                    val it: MutableIterator<MutableMap.MutableEntry<K?, V?>> = m.entrySet().iterator()
                    val e1: MutableMap.MutableEntry<out K?, out V?> = it.next()
                    val e2: MutableMap.MutableEntry<out K?, out V?> = it.next()
                    net.starlark.java.eval.CompactImmutableDict.DoubletonImmutableDict<K?, V?>(
                        e1.getKey(),
                        e1.getValue(),
                        e2.getKey(),
                        e2.getValue()
                    )
                }

                else -> {
                    val ks = arrayOfNulls<Any>(size) as Array<K?>
                    val vs = arrayOfNulls<Any>(size) as Array<V?>
                    var i = 0
                    for (e in m.entrySet()) {
                        ks[i] = e.getKey()
                        vs[i] = e.getValue()
                        i++
                    }
                    if (size <= 8) net.starlark.java.eval.CompactImmutableDict.LinearImmutableDict<K?, V?>(
                        ks,
                        vs
                    ) else net.starlark.java.eval.CompactImmutableDict.HashImmutableDict<K?, V?>(ks, vs)
                }
            }
        }
    }
}
