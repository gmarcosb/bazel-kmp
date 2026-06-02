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
package com.google.devtools.build.lib.collect.nestedset

import com.google.devtools.build.lib.collect.nestedset.DigestMap
import com.google.devtools.build.lib.util.BytesSink
import com.google.devtools.build.lib.util.Fingerprint
import com.google.devtools.build.lib.vfs.DigestHashFunction
import com.google.devtools.build.lib.vfs.DigestHashFunction.DigestLength
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.locks.StampedLock

/**
 * Map of key -> [digest bytes].
 * 
 * 
 * This class uses a single array of keys and a big single block of bytes. To read/store digests
 * we index straight into the byte array. This is more memory-efficient and uses less GC than a
 * corresponding Map<Object></Object>, byte[]>.
 * 
 * 
 * Keys use reference equality.
 * 
 * 
 * Reading is lock free. During writes a read lock is taken. If we need to resize the table, a
 * write lock is taken to flush all the readers and writers before the table is resized.
 */
internal class DigestMap(digestHashFunction: DigestHashFunction, initialSize: Int) {
    private val digestLength: DigestLength
    private val readWriteLock: StampedLock = StampedLock()

    internal class Table(val tableSize: Int, digestLength: Int) {
        val nextResize: Int
        val keys: AtomicReferenceArray<Any?>
        val bytes: ByteArray

        init {
            this.nextResize = getNextResize(tableSize)
            this.keys = AtomicReferenceArray<Any?>(tableSize)
            this.bytes = ByteArray(tableSize * digestLength)
        }
    }

    @kotlin.concurrent.Volatile
    private var table: Table
    private val allocatedSlots: AtomicInteger

    init {
        com.google.common.base.Preconditions.checkArgument(
            initialSize > 0 && (initialSize and (initialSize - 1)) == 0,
            "initialSize must be a power of 2 greater than 0"
        )
        this.digestLength = digestHashFunction.getDigestLength()
        this.table = com.google.devtools.build.lib.collect.nestedset.DigestMap.Table(
            initialSize,
            digestLength.getDigestMaximumLength()
        )
        this.allocatedSlots = AtomicInteger()
    }

    /** Finds the digest for the corresponding key and adds it to the passed fingerprint.  */
    fun readDigest(key: Any?, bytesSink: BytesSink): Boolean {
        val table = this.table // Read once for duration of method
        val index = findKey(table, key)
        if (index >= 0) {
            val offset: Int = index * this.digestLength.getDigestMaximumLength()
            val digestLength: Int = this.digestLength.getDigestLength(table.bytes, offset)
            bytesSink.acceptBytes(table.bytes, offset, digestLength)
            return true
        }
        return false
    }

    /**
     * Inserts a digest for the corresponding key, then immediately reads it into another fingerprint.
     * 
     * 
     * This is equivalent to `digestMap.insertDigest(key, digest.digestAndReset()); digestMap.readDigest(key, readTo);` but it will be faster.
     * 
     * @param key The key to insert.
     * @param digest The fingerprint to insert. This will reset the fingerprint instance.
     * @param readTo A fingerprint to read the just-added fingerprint into.
     */
    fun insertAndReadDigest(key: Any?, digest: Fingerprint, readTo: BytesSink) {
        // Check if we have to resize the table first and do that under write lock
        // We assume that we are going to insert an item. If we do not do this, multiple
        // threads could race and all think they do not need to resize, then some get stuck
        // trying to insert the item.
        var table = this.table
        if (allocatedSlots.incrementAndGet() >= table.nextResize) {
            val resizeLock: Long = readWriteLock.writeLock()
            try {
                // Guard against race to make sure only one thread resizes
                if (table === this.table) {
                    resizeTableWriteLocked()
                }
            } finally {
                readWriteLock.unlockWrite(resizeLock)
            }
        }
        val index: Int
        val stamp: Long = readWriteLock.readLock()
        try {
            table = this.table // Grab the table again under read lock
            index = insertKey(table, key, digest)
        } finally {
            readWriteLock.unlockRead(stamp)
        }
        // This can be done outside of the read lock since the slot is immutable once inserted
        val offset: Int = index * this.digestLength.getDigestMaximumLength()
        val digestLength: Int = this.digestLength.getDigestLength(table.bytes, offset)
        readTo.acceptBytes(table.bytes, offset, digestLength)
    }

    val maxDigestLength: Int
        get() = digestLength.getDigestMaximumLength()

    // Inserts a key into the passed table and returns the index.
    // We're not relying on thread scheduler for correctness
    private fun insertKey(table: Table, key: Any?, digest: Fingerprint): Int {
        val hash = hash(key)
        var index = hash and (table.tableSize - 1)
        while (true) {
            val currentKey: Any? = table.keys.get(index)
            if (currentKey == null) {
                if (!table.keys.compareAndSet(index, null, INSERTION_IN_PROGRESS)) {
                    // We raced to insert a key in a free slot, retry this slot in case it's the same key.
                    // Failure to do so could lead to a double insertion.
                    continue
                }
                digest.digestAndReset(
                    table.bytes,
                    index * digestLength.getDigestMaximumLength(),
                    digestLength.getDigestMaximumLength()
                )
                table.keys.set(index, key)
                return index
            } else if (currentKey === key) {
                // Key is already present, give back the slot allocation
                allocatedSlots.decrementAndGet()
                return index
            } else if (currentKey === INSERTION_IN_PROGRESS) {
                // We are in the progress of inserting an item in this slot, but we don't yet know
                // what the item is. Since it could be an insertion of ourselves we need to wait
                // until done to avoid double insertion. We yield the thread in case the other
                // thread is stuck between insertion and completion.
                java.lang.Thread.yield()
                continue
            }
            index = probe(index, table.tableSize)
        }
    }

    private fun resizeTableWriteLocked() {
        val digestSize: Int = this.digestLength.getDigestMaximumLength()
        val oldTable = this.table
        val newTable: Table =
            com.google.devtools.build.lib.collect.nestedset.DigestMap.Table(oldTable.tableSize * 2, digestSize)
        for (i in 0..<oldTable.tableSize) {
            val key: Any? = oldTable.keys.get(i)
            if (key != null) {
                val newIndex = firstFreeIndex(newTable.keys, newTable.tableSize, key)
                newTable.keys.set(newIndex, key)
                java.lang.System.arraycopy(
                    oldTable.bytes, i * digestSize, newTable.bytes, newIndex * digestSize, digestSize
                )
            }
        }
        this.table = newTable
    }

    companion object {
        private val INSERTION_IN_PROGRESS = Any()
        private fun findKey(table: Table, key: Any?): Int {
            val hash = hash(key)
            var index = hash and (table.tableSize - 1)
            while (true) {
                val currentKey: Any? = table.keys.get(index)
                if (currentKey === key) {
                    return index
                } else if (currentKey == null) {
                    return -1
                }
                index = probe(index, table.tableSize)
            }
        }

        private fun firstFreeIndex(keys: AtomicReferenceArray<Any?>, tableSize: Int, key: Any?): Int {
            val hash = hash(key)
            var index = hash and (tableSize - 1)
            while (true) {
                val currentKey: Any? = keys.get(index)
                if (currentKey == null) {
                    return index
                }
                index = probe(index, tableSize)
            }
        }

        private fun hash(key: Any?): Int {
            return smear(java.lang.System.identityHashCode(key))
        }

        private fun probe(index: Int, tableSize: Int): Int {
            return (index + 1) and (tableSize - 1)
        }

        private fun getNextResize(newTableSize: Int): Int {
            // 75% load
            return (newTableSize * 3) / 4
        }

        /*
   * This method was rewritten in Java from an intermediate step of the Murmur hash function in
   * http://code.google.com/p/smhasher/source/browse/trunk/MurmurHash3.cpp, which contained the
   * following header:
   *
   * MurmurHash3 was written by Austin Appleby, and is placed in the public domain. The author
   * hereby disclaims copyright to this source code.
   */
        private fun smear(hashCode: Int): Int {
            return 0x1b873593 * java.lang.Integer.rotateLeft(hashCode * -0x3361d2af, 15)
        }
    }
}
