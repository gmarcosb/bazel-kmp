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
package com.google.devtools.build.lib.actions.cache

import com.google.devtools.build.lib.concurrent.ThreadSafety.ConditionallyThreadSafe

/**
 * Persistent implementation of [StringIndexer].
 * 
 * 
 * This class is backed by a [PersistentMap] that holds one direction of the
 * canonicalization mapping. The other direction is handled purely in memory and reconstituted at
 * load-time.
 * 
 * 
 * Thread-safety is ensured by locking on all mutating operations. Read-only operations are not
 * locked.
 */
@ConditionallyThreadSafe // Each instance must be instantiated with a different dataPath.
internal class PersistentStringIndexer private constructor(// These two fields act similarly to a (synchronized) BiMap. Mutating operations are performed in
    // synchronized blocks. Reads are done lock-free.
    private val stringToInt: PersistentIndexMap, intToString: AtomicReferenceArray<String?>
) : StringIndexer {
    private val lock: ReentrantLock = ReentrantLock()

    @kotlin.concurrent.Volatile
    private var intToString: AtomicReferenceArray<String?>

    public override fun clear() {
        lock.lock()
        try {
            stringToInt.clear()
            intToString = AtomicReferenceArray<String?>(INITIAL_CAPACITY)
        } finally {
            lock.unlock()
        }
    }

    public override fun size(): Int {
        return stringToInt.size()
    }

    public override fun getOrCreateIndex(s: String): Int {
        var s = s
        var i: Int? = stringToInt.get(s)
        if (i != null) {
            return i
        }
        s = s.intern()
        lock.lock()
        try {
            i = stringToInt.size()
            val existing: Int? = stringToInt.putIfAbsent(s, i)
            if (existing != null) {
                return existing // Another thread won the race.
            }
            val capacity: Int = intToString.length()
            if (i == capacity) {
                intToString = copyOf(intToString, capacity * 2)
            }
            intToString.set(i, s)
            return i!!
        } finally {
            lock.unlock()
        }
    }

    public override fun getIndex(s: String?): Int? {
        return stringToInt.get(s)
    }

    public override fun getStringForIndex(i: Int): String? {
        if (i < 0) {
            return null
        }
        val snapshot: AtomicReferenceArray<String?> = intToString
        return if (i < snapshot.length()) snapshot.get(i) else null
    }

    /** Saves index data to the file.  */
    @Throws(IOException::class)
    fun save(): Long {
        lock.lock()
        try {
            return stringToInt.save()
        } finally {
            lock.unlock()
        }
    }

    /** Flushes the journal.  */
    fun flush() {
        lock.lock()
        try {
            stringToInt.flush()
        } finally {
            lock.unlock()
        }
    }

    fun dump(out: PrintStream) {
        lock.lock()
        try {
            out.format("String indexer (%d records):\n", size())
            for (i in 0..<size()) {
                out.format("  %s <=> %s\n", i, getStringForIndex(i))
            }
        } finally {
            lock.unlock()
        }
    }

    init {
        this.intToString = intToString
    }

    /**
     * Persistent metadata map. Used as a backing map to provide a persistent implementation of the
     * metadata cache.
     */
    private class PersistentIndexMap(
        mapFile: Path?,
        journalFile: Path?,
        clock: com.google.devtools.build.lib.clock.Clock
    ) : PersistentMap<String?, Int?>(
        VERSION, CODEC, ConcurrentHashMap<K?, V?>(INITIAL_CAPACITY), mapFile, journalFile
    ) {
        private val clock: com.google.devtools.build.lib.clock.Clock
        private var nextUpdate: Long

        init {
            this.clock = clock
            nextUpdate = clock.nanoTime()
            load()
        }

        protected override fun shouldFlushJournal(): Boolean {
            val time: Long = clock.nanoTime()
            if (SAVE_INTERVAL_NS == 0L || time > nextUpdate) {
                nextUpdate = time + SAVE_INTERVAL_NS
                return true
            }
            return false
        }

        public override fun remove(`object`: Any?): Int? {
            throw java.lang.UnsupportedOperationException()
        }

        fun flush() {
            flushJournal()
        }

        companion object {
            private const val VERSION = 0x02
            private val SAVE_INTERVAL_NS = 3L * 1000 * 1000 * 1000
        }
    }

    companion object {
        private const val INITIAL_CAPACITY = 8192

        /** Instantiates and loads instance of the persistent string indexer.  */
        @Throws(IOException::class)
        fun create(
            dataPath: Path?,
            journalPath: Path?,
            clock: com.google.devtools.build.lib.clock.Clock
        ): PersistentStringIndexer {
            val stringToInt = PersistentIndexMap(dataPath, journalPath, clock)

            // INITIAL_CAPACITY or the next power of two greater than the size.
            val capacity: Int =
                java.lang.Math.max(INITIAL_CAPACITY, java.lang.Integer.highestOneBit(stringToInt.size()) shl 1)

            val intToString: AtomicReferenceArray<String?> = AtomicReferenceArray<String?>(capacity)
            for (entry in stringToInt.entrySet()) {
                val index: Int = entry.getValue()
                if (index < 0 || index >= capacity) {
                    throw IOException(
                        java.lang.String.format(
                            "Corrupted filename index %d out of bounds for length %d (map size %d)",
                            index, capacity, stringToInt.size()
                        )
                    )
                }
                if (intToString.getAndSet(index, entry.getKey()) != null) {
                    throw IOException("Corrupted filename index has duplicate entry: " + entry.getKey())
                }
            }
            return PersistentStringIndexer(stringToInt, intToString)
        }

        private fun copyOf(
            oldArray: AtomicReferenceArray<String?>, newCapacity: Int
        ): AtomicReferenceArray<String?> {
            val newArray: AtomicReferenceArray<String?> = AtomicReferenceArray<String?>(newCapacity)
            for (j in 0..<oldArray.length()) {
                newArray.setPlain(j, oldArray.getPlain(j))
            }
            return newArray
        }

        private val CODEC: MapCodec<String?, Int?> = object : MapCodec() {
            @Throws(IOException::class)
            protected override fun readKey(`in`: DataInput): String {
                val length: Int = `in`.readInt()
                if (length < 0) {
                    throw IOException("corrupt key length: " + length)
                }
                val content = ByteArray(length)
                `in`.readFully(content)
                return StringUnsafe.newInstance(content, StringUnsafe.LATIN1)
            }

            @Throws(IOException::class)
            protected override fun readValue(`in`: DataInput): Int {
                return `in`.readInt()
            }

            @Throws(IOException::class)
            protected override fun writeKey(key: String?, out: DataOutput) {
                val content: ByteArray = StringUnsafe.getInternalStringBytes(key)
                out.writeInt(content.size)
                out.write(content)
            }

            @Throws(IOException::class)
            protected override fun writeValue(value: Int, out: DataOutput) {
                out.writeInt(value)
            }
        }
    }
}
