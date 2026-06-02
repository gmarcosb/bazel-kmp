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
package com.google.devtools.build.lib.collect.compacthashset

import java.util.AbstractSet
import java.util.Collections
import java.util.ConcurrentModificationException

/**
 * CompactHashSet is an implementation of a Set. All optional operations (adding and removing) are
 * supported. The elements can be any objects.
 * 
 * 
 * `contains(x)`, `add(x)` and `remove(x)`, are all (expected and amortized)
 * constant time operations. Expected in the hashtable sense (depends on the hash function doing a
 * good job of distributing the elements to the buckets to a distribution not far from uniform), and
 * amortized since some operations can trigger a hash table resize.
 * 
 * 
 * Unlike `java.util.HashSet`, iteration is only proportional to the actual `size()`,
 * which is optimal, and *not* the size of the internal hashtable, which could be much larger
 * than `size()`. Furthermore, this structure only depends on a fixed number of arrays; `add(x)` operations *do not* create objects for the garbage collector to deal with, and for
 * every element added, the garbage collector will have to traverse `1.5` references on
 * average, in the marking phase, not `5.0` as in `java.util.HashSet`.
 * 
 * 
 * If there are no removals, then [iteration][.iterator] order is the same as insertion
 * order. Any removal invalidates any ordering guarantees.
 * 
 * 
 * NOTE: This is an older version of Guava's `com.google.java.common.collect.CompactHashSet`, but it outperforms the newer version on large
 * builds significantly, as it uses only 50% of cpu time in comparison.
 */
class CompactHashSet<E> private constructor(expectedSize: Int) : AbstractSet<E?>() {
    /**
     * The hashtable. Its values are indexes to both the elements and entries arrays.
     * 
     * Currently, the UNSET value means "null pointer", and any non negative value x is
     * the actual index.
     * 
     * Its size must be a power of two.
     */
    @Transient
    private var table: IntArray

    /**
     * Contains the logical entries, in the range of [0, size()). The high 32 bits of each
     * long is the smeared hash of the element, whereas the low 32 bits is the "next" pointer
     * (pointing to the next entry in the bucket chain). The pointers in [size(), entries.length)
     * are all "null" (UNSET).
     */
    @Transient
    private var entries: LongArray

    /** The elements contained in the set, in the range of [0, size()).  */
    @Transient
    private var elements: Array<Any?>

    /**
     * Keeps track of modifications of this set, to make it possible to throw
     * ConcurrentModificationException in the iterator. Note that we choose not to make this volatile,
     * so we do less of a "best effort" to track such errors, for better performance.
     */
    @Transient
    private var modCount = 0

    /**
     * When we have this many elements, resize the hashtable.
     */
    @Transient
    private var threshold: Int

    /**
     * The number of elements contained in the set.
     */
    @Transient
    private var size = 0

    /**
     * Constructs a new instance of `CompactHashSet` with the specified capacity.
     * 
     * @param expectedSize the initial capacity of this `CompactHashSet`.
     */
    init {
        com.google.common.base.Preconditions.checkArgument(expectedSize >= 0, "Initial capacity must be non-negative")
        val buckets: Int =
            com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.closedTableSize(expectedSize)
        this.table = com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.newTable(buckets)
        this.elements = arrayOfNulls<Any>(expectedSize)
        this.entries =
            com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.newEntries(expectedSize)
        this.threshold = java.lang.Math.max(
            1,
            (buckets * com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.LOAD_FACTOR).toInt()
        )
    }

    private fun hashTableMask(): Int {
        return table.length - 1
    }

    override fun add(`object`: E?): Boolean {
        val entries = this.entries
        val elements = this.elements
        val hash: Int =
            com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.smearedHash(`object`)
        val tableIndex = hash and hashTableMask()
        val newEntryIndex = this.size // current size, and pointer to the entry to be appended
        var next = table[tableIndex]
        if (next == com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.UNSET) { // uninitialized bucket
            table[tableIndex] = newEntryIndex
        } else {
            var last: Int
            var entry: Long
            do {
                last = next
                entry = entries[next]
                if (com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.getHash(entry) == hash && `object` == elements[next]) {
                    return false
                }
                next = com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.getNext(entry)
            } while (next != com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.UNSET)
            entries[last] = com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.swapNext(
                entry,
                newEntryIndex
            )
        }
        check(newEntryIndex != java.lang.Integer.MAX_VALUE) { "Cannot contain more than Integer.MAX_VALUE elements!" }
        val newSize = newEntryIndex + 1
        resizeMeMaybe(newSize)
        insertEntry(newEntryIndex, `object`, hash)
        this.size = newSize
        if (newEntryIndex >= threshold) {
            resizeTable(2 * table.length)
        }
        modCount++
        return true
    }

    /**
     * Creates a fresh entry with the specified object at the specified position in the entry arrays.
     */
    private fun insertEntry(entryIndex: Int, `object`: E?, hash: Int) {
        this.entries[entryIndex] =
            (hash.toLong() shl 32) or (com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.NEXT_MASK and com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.UNSET.toLong())
        this.elements[entryIndex] = `object`
    }

    /**
     * Returns currentSize + 1, after resizing the entries storage if necessary.
     */
    private fun resizeMeMaybe(newSize: Int) {
        val entriesSize: Int = entries.length
        if (newSize > entriesSize) {
            var newCapacity: Int = entriesSize + java.lang.Math.max(1, entriesSize ushr 1)
            if (newCapacity < 0) {
                newCapacity = java.lang.Integer.MAX_VALUE
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
        this.elements = java.util.Arrays.copyOf<Any?>(elements, newCapacity)
        var entries = this.entries
        val oldSize: Int = entries.length
        entries = java.util.Arrays.copyOf(entries, newCapacity)
        if (newCapacity > oldSize) {
            java.util.Arrays.fill(
                entries,
                oldSize,
                newCapacity,
                com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.UNSET.toLong()
            )
        }
        this.entries = entries
    }

    private fun resizeTable(newCapacity: Int) { // newCapacity always a power of two
        val oldTable = table
        val oldCapacity: Int = oldTable.length
        if (oldCapacity >= com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.MAXIMUM_CAPACITY) {
            threshold = java.lang.Integer.MAX_VALUE
            return
        }
        val newThreshold: Int =
            1 + (newCapacity * com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.LOAD_FACTOR).toInt()
        val newTable: IntArray =
            com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.newTable(newCapacity)
        val entries = this.entries

        val mask: Int = newTable.length - 1
        for (i in 0..<size) {
            val oldEntry = entries[i]
            val hash: Int =
                com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.getHash(oldEntry)
            val tableIndex = hash and mask
            val next = newTable[tableIndex]
            newTable[tableIndex] = i
            entries[i] =
                (hash.toLong() shl 32) or (com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.NEXT_MASK and next.toLong())
        }

        this.threshold = newThreshold
        this.table = newTable
    }

    override fun contains(`object`: Any?): Boolean {
        val hash: Int =
            com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.smearedHash(`object`)
        var next = table[hash and hashTableMask()]
        while (next != com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.UNSET) {
            val entry = entries[next]
            if (com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.getHash(entry) == hash && `object` == elements[next]) {
                return true
            }
            next = com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.getNext(entry)
        }
        return false
    }

    override fun remove(`object`: Any?): Boolean {
        return remove(
            `object`,
            com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.smearedHash(`object`)
        )
    }

    private fun remove(`object`: Any?, hash: Int): Boolean {
        val tableIndex = hash and hashTableMask()
        var next = table[tableIndex]
        if (next == com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.UNSET) {
            return false
        }
        var last: Int = com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.UNSET
        do {
            if (com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.getHash(entries[next]) == hash && `object` == elements[next]) {
                if (last == com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.UNSET) {
                    // we need to update the root link from table[]
                    table[tableIndex] =
                        com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.getNext(entries[next])
                } else {
                    // we need to update the link from the chain
                    entries[last] =
                        com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.swapNext(
                            entries[last],
                            com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.getNext(
                                entries[next]
                            )
                        )
                }

                moveEntry(next)
                size--
                modCount++
                return true
            }
            last = next
            next = com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.getNext(entries[next])
        } while (next != com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.UNSET)
        return false
    }

    /**
     * Moves the last entry in the entry array into `dstIndex`, and nulls out its old position.
     */
    private fun moveEntry(dstIndex: Int) {
        val srcIndex = size() - 1
        if (dstIndex < srcIndex) {
            // move last entry to deleted spot
            elements[dstIndex] = elements[srcIndex]
            elements[srcIndex] = null

            // move the last entry to the removed spot, just like we moved the element
            val lastEntry = entries[srcIndex]
            entries[dstIndex] = lastEntry
            entries[srcIndex] =
                com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.UNSET.toLong()

            // also need to update whoever's "next" pointer was pointing to the last entry place
            // reusing "tableIndex" and "next"; these variables were no longer needed
            val tableIndex: Int =
                com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.getHash(lastEntry) and hashTableMask()
            var lastNext = table[tableIndex]
            if (lastNext == srcIndex) {
                // we need to update the root pointer
                table[tableIndex] = dstIndex
            } else {
                // we need to update a pointer in an entry
                var previous: Int
                var entry: Long
                do {
                    previous = lastNext
                    lastNext =
                        com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.getNext(entries[lastNext].also {
                            entry = it
                        })
                } while (lastNext != srcIndex)
                // here, entries[previous] points to the old entry location; update it
                entries[previous] =
                    com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.swapNext(
                        entry,
                        dstIndex
                    )
            }
        } else {
            elements[dstIndex] = null
            entries[dstIndex] =
                com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.UNSET.toLong()
        }
    }

    override fun iterator(): MutableIterator<E?> {
        return object : MutableIterator<E?> {
            var expectedModCount: Int = modCount
            var nextCalled: Boolean = false
            var index: Int = 0

            override fun hasNext(): Boolean {
                return index < size
            }

            override fun next(): E? {
                checkForConcurrentModification()
                if (!hasNext()) {
                    throw java.util.NoSuchElementException()
                }
                nextCalled = true
                return elements[index++] as E?
            }

            override fun remove() {
                checkForConcurrentModification()
                com.google.common.base.Preconditions.checkState(
                    nextCalled,
                    "no calls to next() since the last call to remove()"
                )
                expectedModCount++
                index--
                this@CompactHashSet.remove(
                    elements[index],
                    com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.getHash(entries[index])
                )
                nextCalled = false
            }

            fun checkForConcurrentModification() {
                if (modCount != expectedModCount) {
                    throw ConcurrentModificationException()
                }
            }
        }
    }

    override fun size(): Int {
        return size
    }

    val isEmpty: Boolean
        get() = size == 0

    override fun toArray(): Array<Any?> {
        return java.util.Arrays.copyOf<Any?>(elements, size)
    }

    override fun <T> toArray(a: Array<T?>): Array<T?> {
        var a = a
        if (a.length < size) {
            a = java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), size) as Array<T?>
        }
        java.lang.System.arraycopy(elements, 0, a, 0, size)
        return a
    }

    /**
     * Ensures that this `CompactHashSet` has the smallest representation in memory,
     * given its current size.
     */
    fun trimToSize() {
        val size = this.size
        if (size < entries.length) {
            resizeEntries(size)
        }
        // size / loadFactor gives the table size of the appropriate load factor,
        // but that may not be a power of two. We floor it to a power of two by
        // keeping its highest bit. But the smaller table may have a load factor
        // larger than what we want; then we want to go to the next power of 2 if we can
        var minimumTableSize: Int = java.lang.Math.max(
            1,
            java.lang.Integer.highestOneBit((size / com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.LOAD_FACTOR).toInt())
        )
        if (minimumTableSize < com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.MAXIMUM_CAPACITY) {
            val load = size.toDouble() / minimumTableSize
            if (load > com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.LOAD_FACTOR) {
                minimumTableSize = minimumTableSize shl 1 // increase to next power if possible
            }
        }

        if (minimumTableSize < table.length) {
            resizeTable(minimumTableSize)
        }
    }

    override fun clear() {
        modCount++
        java.util.Arrays.fill(elements, 0, size, null)
        java.util.Arrays.fill(
            table,
            com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.UNSET
        )
        java.util.Arrays.fill(
            entries,
            com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.UNSET.toLong()
        )
        this.size = 0
    }

    companion object {
        // TODO(bazel-team): cache all field accesses in local vars
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
            return com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.C2 * java.lang.Integer.rotateLeft(
                hashCode * com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.C1,
                15
            )
        }

        private fun smearedHash(o: Any?): Int {
            return com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.smear(if (o == null) 0 else o.hashCode())
        }

        private val MAX_TABLE_SIZE: Int = com.google.common.primitives.Ints.MAX_POWER_OF_TWO

        private fun closedTableSize(expectedEntries: Int): Int {
            // Get the recommended table size.
            // Round down to the nearest power of 2.
            var expectedEntries = expectedEntries
            expectedEntries = java.lang.Math.max(expectedEntries, 2)
            var tableSize: Int = java.lang.Integer.highestOneBit(expectedEntries)
            // Check to make sure that we will not exceed the maximum load factor.
            if (expectedEntries > (com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.LOAD_FACTOR * tableSize).toInt()) {
                tableSize = tableSize shl 1
                return if (tableSize > 0) tableSize else com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.MAX_TABLE_SIZE
            }
            return tableSize
        }

        /** Creates an empty `CompactHashSet` instance.  */
        fun <E> create(): CompactHashSet<E?> {
            return com.google.devtools.build.lib.collect.compacthashset.CompactHashSet<E?>(com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.DEFAULT_SIZE)
        }

        /**
         * Creates a *mutable* `CompactHashSet` instance containing the elements
         * of the given collection in unspecified order.
         * 
         * @param collection the elements that the set should contain
         * @return a new `CompactHashSet` containing those elements (minus duplicates)
         */
        fun <E> create(collection: MutableCollection<out E?>): CompactHashSet<E?> {
            val set: CompactHashSet<E?> =
                com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.createWithExpectedSize<E?>(
                    collection.size()
                )
            set.addAll(collection)
            return set
        }

        /**
         * Creates a *mutable* `CompactHashSet` instance containing the given
         * elements in unspecified order.
         * 
         * @param elements the elements that the set should contain
         * @return a new `CompactHashSet` containing those elements (minus duplicates)
         */
        @kotlin.jvm.JvmStatic
        @java.lang.SafeVarargs
        fun <E> create(vararg elements: E?): CompactHashSet<E?> {
            val set: CompactHashSet<E?> =
                com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.createWithExpectedSize<E?>(
                    elements.length
                )
            Collections.addAll<E?>(set, *elements)
            return set
        }

        /**
         * Creates a `CompactHashSet` instance, with a high enough "initial capacity"
         * that it *should* hold `expectedSize` elements without growth.
         * 
         * @param expectedSize the number of elements you expect to add to the returned set
         * @return a new, empty `CompactHashSet` with enough capacity to hold `expectedSize` elements without resizing
         * @throws IllegalArgumentException if `expectedSize` is negative
         */
        @kotlin.jvm.JvmStatic
        fun <E> createWithExpectedSize(expectedSize: Int): CompactHashSet<E?> {
            return com.google.devtools.build.lib.collect.compacthashset.CompactHashSet<E?>(expectedSize)
        }

        private val MAXIMUM_CAPACITY = 1 shl 30

        private const val LOAD_FACTOR = 1.0f

        /**
         * Bitmask that selects the low 32 bits.
         */
        private val NEXT_MASK = (1L shl 32) - 1

        /**
         * Bitmask that selects the high 32 bits.
         */
        private val HASH_MASK: Long =
            com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.NEXT_MASK.inv()

        // TODO(bazel-team): decide default size
        private const val DEFAULT_SIZE = 3

        private val UNSET = -1

        private fun newTable(size: Int): IntArray {
            val array = IntArray(size)
            java.util.Arrays.fill(
                array,
                com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.UNSET
            )
            return array
        }

        private fun newEntries(size: Int): LongArray {
            val array = LongArray(size)
            java.util.Arrays.fill(
                array,
                com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.UNSET.toLong()
            )
            return array
        }

        private fun getHash(entry: Long): Int {
            return (entry ushr 32).toInt()
        }

        /**
         * Returns the index, or UNSET if the pointer is "null"
         */
        private fun getNext(entry: Long): Int {
            return entry.toInt()
        }

        /**
         * Returns a new entry value by changing the "next" index of an existing entry
         */
        private fun swapNext(entry: Long, newNext: Int): Long {
            return (com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.HASH_MASK and entry) or (com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.NEXT_MASK and newNext.toLong())
        }
    }
}
