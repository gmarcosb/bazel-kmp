// Copyright 2019 The Bazel Authors. All rights reserved.
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
/*
 * Copyright (C) 2012 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.devtools.build.lib.collect.compacthashmap

import com.google.common.base.Objects
import com.google.common.base.Preconditions
import com.google.common.primitives.Ints
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.util.*
import java.util.function.BiConsumer
import java.util.function.BiFunction
import java.util.function.Consumer

/**
 * CompactHashMap is an implementation of a Map. All optional operations (put and remove) are
 * supported. Null keys and values are supported.
 * 
 * 
 * `containsKey(k)`, `put(k, v)` and `remove(k)` are all (expected and
 * amortized) constant time operations. Expected in the hashtable sense (depends on the hash
 * function doing a good job of distributing the elements to the buckets to a distribution not far
 * from uniform), and amortized since some operations can trigger a hash table resize.
 * 
 * 
 * Unlike `java.util.HashMap`, iteration is only proportional to the actual `size()`,
 * which is optimal, and *not* the size of the internal hashtable, which could be much larger
 * than `size()`. Furthermore, this structure places significantly reduced load on the garbage
 * collector by only using a constant number of internal objects.
 * 
 * 
 * If there are no removals, then iteration order for the [.entrySet], [.keySet],
 * and [.values] views is the same as insertion order. Any removal invalidates any ordering
 * guarantees.
 * 
 * 
 * This class should not be assumed to be universally superior to `java.util.HashMap`.
 * Generally speaking, this class reduces object allocation and memory consumption at the price of
 * moderately increased constant factors of CPU. Only use this class when there is a specific reason
 * to prioritize memory over CPU.
 * 
 * @author Louis Wasserman
 */
class CompactHashMap<K, V> internal constructor(expectedSize: Int) : AbstractMap<K?, V?>() {
    /**
     * The hashtable. Its values are indexes to the keys, values, and entries arrays.
     * 
     * 
     * Currently, the UNSET value means "null pointer", and any non negative value x is the actual
     * index.
     * 
     * 
     * Its size must be a power of two.
     */
    @Transient
    private var table: IntArray?

    /**
     * Contains the logical entries, in the range of [0, size()). The high 32 bits of each long is the
     * smeared hash of the element, whereas the low 32 bits is the "next" pointer (pointing to the
     * next entry in the bucket chain). The pointers in [size(), entries.length) are all "null"
     * (UNSET).
     */
    @Transient
    private var entries: LongArray

    /**
     * The keys of the entries in the map, in the range of [0, size()). The keys in [size(),
     * keys.length) are all `null`.
     */
    @Transient
    private var keys: Array<Any?>

    /**
     * The values of the entries in the map, in the range of [0, size()). The values in [size(),
     * values.length) are all `null`.
     */
    @Transient
    private var values: Array<Any?>

    /**
     * Keeps track of modifications of this set, to make it possible to throw
     * ConcurrentModificationException in the iterator. Note that we choose not to make this volatile,
     * so we do less of a "best effort" to track such errors, for better performance.
     */
    @Transient
    private var modCount: Int

    /** The number of elements contained in the set.  */
    @Transient
    private var size = 0

    /** Returns whether arrays need to be allocated.  */
    private fun needsAllocArrays(): Boolean {
        return table == null
    }

    /** Handle lazy allocation of arrays.  */
    private fun allocArrays() {
        Preconditions.checkState(needsAllocArrays(), "Arrays already allocated")

        val expectedSize = modCount
        val buckets: Int = closedTableSize(expectedSize)
        this.table = newTable(buckets)

        this.entries = newEntries(expectedSize)
        this.keys = arrayOfNulls<Any>(expectedSize)
        this.values = arrayOfNulls<Any>(expectedSize)
    }

    private fun hashTableMask(): Int {
        return table.length - 1
    }

    @CanIgnoreReturnValue
    override fun put(key: K?, value: V?): V? {
        if (needsAllocArrays()) {
            allocArrays()
        }
        val entries: LongArray = this.entries
        val keys: Array<Any?> = this.keys
        val values: Array<Any?> = this.values

        val hash: Int = smearedHash(key)
        val tableIndex = hash and hashTableMask()
        val newEntryIndex = this.size // current size, and pointer to the entry to be appended
        var next = table!![tableIndex]
        if (next == UNSET) { // uninitialized bucket
            table!![tableIndex] = newEntryIndex
        } else {
            var last: Int
            var entry: Long
            do {
                last = next
                entry = entries[next]
                if (getHash(entry) == hash && Objects.equal(key, keys[next])) {
                    val oldValue = values[next] as V?

                    values[next] = value
                    return oldValue
                }
                next = getNext(entry)
            } while (next != UNSET)
            entries[last] = swapNext(entry, newEntryIndex)
        }
        check(newEntryIndex != Integer.MAX_VALUE) { "Cannot contain more than Integer.MAX_VALUE elements!" }
        val newSize = newEntryIndex + 1
        resizeMeMaybe(newSize)
        insertEntry(newEntryIndex, key, value, hash)
        this.size = newSize
        val oldCapacity: Int = table.length
        if (needsResizing(newEntryIndex, oldCapacity)) {
            resizeTable(2 * oldCapacity)
        }
        modCount++
        return null
    }

    /**
     * Creates a fresh entry with the specified object at the specified position in the entry arrays.
     */
    private fun insertEntry(entryIndex: Int, key: K?, value: V?, hash: Int) {
        this.entries[entryIndex] = (hash.toLong() shl 32) or (NEXT_MASK and UNSET.toLong())
        this.keys[entryIndex] = key
        this.values[entryIndex] = value
    }

    /** Resizes the entries storage if necessary.  */
    private fun resizeMeMaybe(newSize: Int) {
        val entriesSize: Int = entries.length
        if (newSize > entriesSize) {
            var newCapacity = entriesSize + Math.max(1, entriesSize ushr 1)
            if (newCapacity < 0) {
                newCapacity = Integer.MAX_VALUE
            }
            if (newCapacity != entriesSize) {
                resizeEntries(newCapacity)
            }
        }
    }

    /**
     * Resizes the internal entries array to the specified capacity, which may be greater or less than
     * the current capacity.
     */
    private fun resizeEntries(newCapacity: Int) {
        this.keys = Arrays.copyOf<Any?>(keys, newCapacity)
        this.values = Arrays.copyOf<Any?>(values, newCapacity)
        var entries: LongArray = this.entries
        val oldCapacity: Int = entries.length
        entries = Arrays.copyOf(entries, newCapacity)
        if (newCapacity > oldCapacity) {
            Arrays.fill(entries, oldCapacity, newCapacity, UNSET.toLong())
        }
        this.entries = entries
    }

    private fun resizeTable(newCapacity: Int) { // newCapacity always a power of two
        val newTable: IntArray = newTable(newCapacity)
        val entries: LongArray = this.entries

        val mask: Int = newTable.length - 1
        for (i in 0..<size) {
            val oldEntry = entries[i]
            val hash: Int = getHash(oldEntry)
            val tableIndex = hash and mask
            val next = newTable[tableIndex]
            newTable[tableIndex] = i
            entries[i] = (hash.toLong() shl 32) or (NEXT_MASK and next.toLong())
        }

        this.table = newTable
    }

    private fun indexOf(key: Any?): Int {
        if (needsAllocArrays()) {
            return -1
        }
        val hash: Int = smearedHash(key)
        var next = table!![hash and hashTableMask()]
        while (next != UNSET) {
            val entry: Long = entries[next]
            if (getHash(entry) == hash && Objects.equal(key, keys[next])) {
                return next
            }
            next = getNext(entry)
        }
        return -1
    }

    override fun containsKey(key: Any?): Boolean {
        return indexOf(key) != -1
    }

    override fun get(key: Any?): V? {
        val index = indexOf(key)
        return if (index == -1) null else values[index] as V?
    }

    @CanIgnoreReturnValue
    override fun remove(key: Any?): V? {
        if (needsAllocArrays()) {
            return null
        }
        return remove(key, smearedHash(key))
    }

    private fun remove(key: Any?, hash: Int): V? {
        val tableIndex = hash and hashTableMask()
        var next = table!![tableIndex]
        if (next == UNSET) { // empty bucket
            return null
        }
        var last: Int = UNSET
        do {
            if (getHash(entries[next]) == hash && Objects.equal(key, keys[next])) {
                val oldValue = values[next] as V?

                if (last == UNSET) {
                    // we need to update the root link from table[]
                    table!![tableIndex] = getNext(entries[next])
                } else {
                    // we need to update the link from the chain
                    entries[last] = swapNext(entries[last], getNext(entries[next]))
                }

                moveLastEntry(next)
                size--
                modCount++
                return oldValue
            }
            last = next
            next = getNext(entries[next])
        } while (next != UNSET)
        return null
    }

    @CanIgnoreReturnValue
    private fun removeEntry(entryIndex: Int): V? {
        return remove(keys[entryIndex], getHash(entries[entryIndex]))
    }

    /**
     * Moves the last entry in the entry array into `dstIndex`, and nulls out its old position.
     */
    private fun moveLastEntry(dstIndex: Int) {
        val srcIndex: Int = size() - 1
        if (dstIndex < srcIndex) {
            // move last entry to deleted spot
            keys[dstIndex] = keys[srcIndex]
            values[dstIndex] = values[srcIndex]
            keys[srcIndex] = null
            values[srcIndex] = null

            // move the last entry to the removed spot, just like we moved the element
            val lastEntry: Long = entries[srcIndex]
            entries[dstIndex] = lastEntry
            entries[srcIndex] = UNSET.toLong()

            // also need to update whoever's "next" pointer was pointing to the last entry place
            // reusing "tableIndex" and "next"; these variables were no longer needed
            val tableIndex: Int = getHash(lastEntry) and hashTableMask()
            var lastNext = table!![tableIndex]
            if (lastNext == srcIndex) {
                // we need to update the root pointer
                table!![tableIndex] = dstIndex
            } else {
                // we need to update a pointer in an entry
                var previous: Int
                var entry: Long
                do {
                    previous = lastNext
                    lastNext = getNext(entries[lastNext].also { entry = it })
                } while (lastNext != srcIndex)
                // here, entries[previous] points to the old entry location; update it
                entries[previous] = swapNext(entry, dstIndex)
            }
        } else {
            keys[dstIndex] = null
            values[dstIndex] = null
            entries[dstIndex] = UNSET.toLong()
        }
    }

    private fun firstEntryIndex(): Int {
        return if (isEmpty()) -1 else 0
    }

    private fun getSuccessor(entryIndex: Int): Int {
        return if (entryIndex + 1 < size) entryIndex + 1 else -1
    }

    private abstract inner class Itr<T> : MutableIterator<T?> {
        var expectedModCount: Int = modCount
        var currentIndex: Int = firstEntryIndex()
        var indexToRemove: Int = -1

        override fun hasNext(): Boolean {
            return currentIndex >= 0
        }

        abstract fun getOutput(entry: Int): T?

        override fun next(): T? {
            checkForConcurrentModification()
            if (!hasNext()) {
                throw NoSuchElementException()
            }
            indexToRemove = currentIndex
            val result = getOutput(currentIndex)
            currentIndex = getSuccessor(currentIndex)
            return result
        }

        override fun remove() {
            checkForConcurrentModification()
            Preconditions.checkState(indexToRemove >= 0, "no calls to next() since the last call to remove()")
            expectedModCount++
            removeEntry(indexToRemove)
            currentIndex = adjustAfterRemove(currentIndex)
            indexToRemove = -1
        }

        fun checkForConcurrentModification() {
            if (modCount != expectedModCount) {
                throw ConcurrentModificationException()
            }
        }
    }

    // keys/values only contains Ks/Vs
    override fun replaceAll(function: BiFunction<in K?, in V?, out V?>?) {
        Preconditions.checkNotNull(function)
        for (i in 0..<size) {
            values[i] = function!!.apply(keys[i] as K?, values[i] as V?)
        }
    }

    @Transient
    private var keySetView: MutableSet<K?>? = null

    override fun keySet(): MutableSet<K?> {
        return (if (keySetView == null) createKeySet().also { keySetView = it } else keySetView)!!
    }

    private fun createKeySet(): MutableSet<K?> {
        return CompactHashMap.KeySetView()
    }

    internal inner class KeySetView : AbstractSet<K?>() {
        override fun size(): Int {
            return size
        }

        override fun toArray(): Array<Any?> {
            if (needsAllocArrays()) {
                return arrayOfNulls<Any>(0)
            }
            return Arrays.copyOf<Any?>(keys, size)
        }

        override fun remove(o: Any?): Boolean {
            val index = indexOf(o)
            if (index == -1) {
                return false
            } else {
                removeEntry(index)
                return true
            }
        }

        override fun iterator(): MutableIterator<K?> {
            return keySetIterator()
        }

        override fun spliterator(): Spliterator<K?> {
            if (needsAllocArrays()) {
                return Spliterators.spliterator<K?>(arrayOfNulls<Any>(0), Spliterator.DISTINCT or Spliterator.ORDERED)
            }
            return Spliterators.spliterator<K?>(keys, 0, size, Spliterator.DISTINCT or Spliterator.ORDERED)
        }

        override fun contains(o: Any?): Boolean {
            return containsKey(o)
        }

        // keys contains only Ks
        override fun forEach(action: Consumer<in K?>?) {
            Preconditions.checkNotNull(action)
            var i = firstEntryIndex()
            while (i >= 0) {
                action!!.accept(keys[i] as K?) // unchecked
                i = getSuccessor(i)
            }
        }
    }

    private fun keySetIterator(): MutableIterator<K?> {
        return object : Itr<K?>() {
            override fun getOutput(entry: Int): K? {
                return keys[entry] as K?
            }
        }
    }

    // keys/values contains only Ks/Vs
    override fun forEach(action: BiConsumer<in K?, in V?>?) {
        Preconditions.checkNotNull(action)
        var i = firstEntryIndex()
        while (i >= 0) {
            action!!.accept(keys[i] as K?, values[i] as V?)
            i = getSuccessor(i)
        }
    }

    @Transient
    private var entrySetView: MutableSet<MutableMap.MutableEntry<K?, V?>?>? = null

    override fun entrySet(): MutableSet<MutableMap.MutableEntry<K?, V?>?> {
        return (if (entrySetView == null) createEntrySet().also { entrySetView = it } else entrySetView)!!
    }

    private fun createEntrySet(): MutableSet<MutableMap.MutableEntry<K?, V?>?> {
        return CompactHashMap.EntrySetView()
    }

    internal inner class EntrySetView : AbstractSet<MutableMap.MutableEntry<K?, V?>?>() {
        override fun size(): Int {
            return size
        }

        override fun iterator(): MutableIterator<MutableMap.MutableEntry<K?, V?>?> {
            return entrySetIterator()
        }

        override fun contains(o: Any?): Boolean {
            if (o is MutableMap.MutableEntry<*, *>) {
                val index = indexOf(o.getKey())
                return index != -1 && Objects.equal(values[index], o.getValue())
            }
            return false
        }

        override fun remove(o: Any?): Boolean {
            if (o is MutableMap.MutableEntry<*, *>) {
                val index = indexOf(o.getKey())
                if (index != -1 && Objects.equal(values[index], o.getValue())) {
                    removeEntry(index)
                    return true
                }
            }
            return false
        }
    }

    private fun entrySetIterator(): MutableIterator<MutableMap.MutableEntry<K?, V?>?> {
        return object : Itr<MutableMap.MutableEntry<K?, V?>?>() {
            override fun getOutput(entry: Int): MutableMap.MutableEntry<K?, V?> {
                return CompactHashMap.MapEntry(entry)
            }
        }
    }

    internal inner class MapEntry(private var lastKnownIndex: Int) : MutableMap.MutableEntry<K?, V?> {
        val key: K?

        init {
            this.key = keys[lastKnownIndex] as K?
        }

        private fun updateLastKnownIndex() {
            if (lastKnownIndex == -1 || lastKnownIndex >= size() || !Objects.equal(key, keys[lastKnownIndex])) {
                lastKnownIndex = indexOf(key)
            }
        }

        val value: V?
            get() {
                updateLastKnownIndex()
                return if (lastKnownIndex == -1) null else values[lastKnownIndex] as V?
            }

        override fun setValue(value: V?): V? {
            updateLastKnownIndex()
            if (lastKnownIndex == -1) {
                put(key, value)
                return null
            } else {
                val old = values[lastKnownIndex] as V?
                values[lastKnownIndex] = value
                return old
            }
        }

        override fun equals(`object`: Any?): Boolean {
            if (`object` is MutableMap.MutableEntry<*, *>) {
                return Objects.equal(this.key, `object`.getKey())
                        && Objects.equal(this.value, `object`.getValue())
            }
            return false
        }

        override fun hashCode(): Int {
            val k = key
            val v = this.value
            return (if (k == null) 0 else k.hashCode()) xor (if (v == null) 0 else v.hashCode())
        }

        /** Returns a string representation of the form `{key}={value}`.  */
        override fun toString(): String {
            return key.toString() + "=" + this.value
        }
    }

    override fun size(): Int {
        return size
    }

    override fun isEmpty(): Boolean {
        return size == 0
    }

    override fun containsValue(value: Any?): Boolean {
        for (i in 0..<size) {
            if (Objects.equal(value, values[i])) {
                return true
            }
        }
        return false
    }

    @Transient
    private var valuesView: MutableCollection<V?>? = null

    /**
     * Constructs a new instance of `CompactHashMap` with the specified capacity.
     * 
     * @param expectedSize the initial capacity of this `CompactHashMap`.
     */
    init {
        Preconditions.checkArgument(expectedSize >= 0, "Expected size must be non-negative")
        this.modCount = Math.max(1, expectedSize) // Save expectedSize for use in allocArrays()
    }

    override fun values(): MutableCollection<V?> {
        return (if (valuesView == null) createValues().also { valuesView = it } else valuesView)!!
    }

    private fun createValues(): MutableCollection<V?> {
        return CompactHashMap.ValuesView()
    }

    internal inner class ValuesView : AbstractCollection<V?>() {
        override fun size(): Int {
            return size
        }

        override fun iterator(): MutableIterator<V?> {
            return valuesIterator()
        }

        // values contains only Vs
        override fun forEach(action: Consumer<in V?>?) {
            Preconditions.checkNotNull(action)
            var i = firstEntryIndex()
            while (i >= 0) {
                action!!.accept(values[i] as V?)
                i = getSuccessor(i)
            }
        }

        override fun spliterator(): Spliterator<V?> {
            if (needsAllocArrays()) {
                return Spliterators.spliterator<V?>(arrayOfNulls<Any>(0), Spliterator.ORDERED)
            }
            return Spliterators.spliterator<V?>(values, 0, size, Spliterator.ORDERED)
        }

        override fun toArray(): Array<Any?> {
            if (needsAllocArrays()) {
                return arrayOfNulls<Any>(0)
            }
            return Arrays.copyOf<Any?>(values, size)
        }
    }

    private fun valuesIterator(): MutableIterator<V?> {
        return object : Itr<V?>() {
            override fun getOutput(entry: Int): V? {
                return values[entry] as V?
            }
        }
    }

    override fun clear() {
        if (needsAllocArrays()) {
            return
        }
        modCount++
        Arrays.fill(keys, 0, size, null)
        Arrays.fill(values, 0, size, null)
        Arrays.fill(table, UNSET)
        Arrays.fill(entries, 0, size, UNSET.toLong())
        this.size = 0
    }

    companion object {
        // A partial copy of com.google.common.collect.Hashing.
        private const val C1 = -0x3361d2af
        private const val C2 = 0x1b873593

        /*
   * This method was rewritten in Java from an intermediate step of the Murmur hash function in
   * http://code.google.com/p/smhasher/source/browse/trunk/MurmurHash3.cpp, which contained the
   * following header:
   *
   * MurmurHash3 was written by Austin Appleby, and is placed in the public domain. The author
   * hereby disclaims copyright to this source code.
   */
        private fun smear(hashCode: Int): Int {
            return C2 * Integer.rotateLeft(hashCode * C1, 15)
        }

        private fun smearedHash(o: Any?): Int {
            return smear(if (o == null) 0 else o.hashCode())
        }

        private val MAX_TABLE_SIZE = Ints.MAX_POWER_OF_TWO

        private fun closedTableSize(expectedEntries: Int): Int {
            // Get the recommended table size.
            // Round down to the nearest power of 2.
            var expectedEntries = expectedEntries
            expectedEntries = Math.max(expectedEntries, 2)
            var tableSize = Integer.highestOneBit(expectedEntries)
            // Check to make sure that we will not exceed the maximum load factor.
            if (expectedEntries > (LOAD_FACTOR * tableSize).toInt()) {
                tableSize = tableSize shl 1
                return if (tableSize > 0) tableSize else MAX_TABLE_SIZE
            }
            return tableSize
        }

        private fun needsResizing(size: Int, tableSize: Int): Boolean {
            return size > LOAD_FACTOR * tableSize && tableSize < MAX_TABLE_SIZE
        }

        /*
   * TODO: Make this a drop-in replacement for j.u. versions, actually drop them in, and test the
   * world. Figure out what sort of space-time tradeoff we're actually going to get here with the
   * *Map variants. Followon optimizations, such as using 16-bit indices for small collections, will
   * take more work to implement. This class is particularly hard to benchmark, because the benefit
   * is not only in less allocation, but also having the GC do less work to scan the heap because of
   * fewer references, which is particularly hard to quantify.
   */
        /** Creates an empty `CompactHashMap` instance.  */
        @kotlin.jvm.JvmStatic
        fun <K, V> create(): CompactHashMap<K?, V?> {
            return CompactHashMap<K?, V?>(DEFAULT_SIZE)
        }

        /**
         * Creates a `CompactHashMap` instance, with a high enough "initial capacity" that it
         * *should* hold `expectedSize` elements without growth.
         * 
         * @param expectedSize the number of elements you expect to add to the returned set
         * @return a new, empty `CompactHashMap` with enough capacity to hold `expectedSize`
         * elements without resizing
         * @throws IllegalArgumentException if `expectedSize` is negative
         */
        @kotlin.jvm.JvmStatic
        fun <K, V> createWithExpectedSize(expectedSize: Int): CompactHashMap<K?, V?> {
            return CompactHashMap<K?, V?>(expectedSize)
        }

        private const val LOAD_FACTOR = 1.0f

        /** Bitmask that selects the low 32 bits.  */
        private val NEXT_MASK = (1L shl 32) - 1

        /** Bitmask that selects the high 32 bits.  */
        private val HASH_MASK: Long = NEXT_MASK.inv()

        // TODO(bazel-team): decide default size
        private const val DEFAULT_SIZE = 3

        // used to indicate blank table entries
        private val UNSET = -1

        private fun newTable(size: Int): IntArray {
            val array = IntArray(size)
            Arrays.fill(array, UNSET)
            return array
        }

        private fun newEntries(size: Int): LongArray {
            val array = LongArray(size)
            Arrays.fill(array, UNSET.toLong())
            return array
        }

        private fun getHash(entry: Long): Int {
            return (entry ushr 32).toInt()
        }

        /** Returns the index, or UNSET if the pointer is "null"  */
        private fun getNext(entry: Long): Int {
            return entry.toInt()
        }

        /** Returns a new entry value by changing the "next" index of an existing entry  */
        private fun swapNext(entry: Long, newNext: Int): Long {
            return (HASH_MASK and entry) or (NEXT_MASK and newNext.toLong())
        }

        /**
         * Updates the index an iterator is pointing to after a call to remove: returns the index of the
         * entry that should be looked at after a removal on indexRemoved, with indexBeforeRemove as the
         * index that *was* the next entry that would be looked at.
         */
        private fun adjustAfterRemove(indexBeforeRemove: Int): Int {
            return indexBeforeRemove - 1
        }
    }
}
