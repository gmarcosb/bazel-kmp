// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.vfs.FileSystemUtils

/** Tests for [PersistentStringIndexer].  */
@RunWith(JUnit4::class)
class PersistentStringIndexerTest {
    private class ManualClock : com.google.devtools.build.lib.clock.Clock {
        private var currentTime = 0L

        override fun currentTimeMillis(): Long {
            throw java.lang.AssertionError("unexpected method call")
        }

        override fun nanoTime(): Long {
            return currentTime
        }

        fun advance(time: Long) {
            currentTime += time
        }
    }

    private val mappings: MutableMap<Int?, String?> = ConcurrentHashMap<Int?, String?>()
    private val scratch: Scratch = Scratch()
    private val clock: ManualClock =
        com.google.devtools.build.lib.actions.cache.PersistentStringIndexerTest.ManualClock()
    private var dataPath: Path? = null
    private var journalPath: Path? = null

    private var indexer: PersistentStringIndexer? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createIndexer() {
        val cacheRoot: Path = scratch.dir("/cache")
        dataPath = cacheRoot.getChild("test.dat")
        journalPath = cacheRoot.getChild("test.journal")
        indexer = PersistentStringIndexer.create(dataPath, journalPath, clock)
    }

    private fun assertSize(expected: Int) {
        assertThat(indexer.size()).isEqualTo(expected)
    }

    private fun assertIndex(expected: Int, s: String?) {
        val index: Int = indexer.getOrCreateIndex(s)
        Truth.assertThat(index).isEqualTo(expected)
        mappings.put(expected, s)
    }

    private fun assertContent() {
        for (i in 0..<indexer.size()) {
            if (mappings.get(i) != null) {
                Truth.assertThat(mappings).containsEntry(i, indexer.getStringForIndex(i))
            }
        }
    }

    private fun setupTestContent() {
        assertSize(0)
        assertIndex(0, "abcdefghi") // Create leafs
        assertIndex(1, "abcdefjkl")
        assertIndex(2, "abcdefmno")
        assertIndex(3, "abcdefjklpr")
        assertIndex(3, "abcdefjklpr")
        assertIndex(4, "abcdstr")
        assertIndex(5, "012345")
        assertSize(6)
        assertIndex(6, "abcdef") // Validate inner nodes
        assertIndex(7, "abcd")
        assertIndex(8, "")
        assertSize(9)
        assertContent()
    }

    /**
     * Writes lots of entries with labels "fooconcurrent[int]" at the same time. The set of labels
     * written is deterministic, but the label:index mapping is not.
     */
    @Throws(java.lang.InterruptedException::class)
    private fun writeLotsOfEntriesConcurrently(numToWrite: Int) {
        val numThreads = 10
        val synchronizerLatch: CountDownLatch = CountDownLatch(numThreads)

        val indexAdder: TestRunnable =
            TestRunnable {
                for (i in 0..<numToWrite) {
                    synchronizerLatch.countDown()
                    synchronizerLatch.await()

                    val value = "fooconcurrent" + i
                    mappings.put(indexer.getOrCreateIndex(value), value)
                }
            }

        val threads: MutableCollection<TestThread> = java.util.ArrayList<TestThread>()
        for (i in 0..<numThreads) {
            val thread: TestThread = TestThread(indexAdder)
            thread.start()
            threads.add(thread)
        }

        for (thread in threads) {
            thread.joinAndAssertState(0)
        }
    }

    @org.junit.Test
    fun returnsSameIntegerInstance() {
        val n = 1000 // Greater than the default java.lang.Integer.IntegerCache.high of 127.
        for (i in 0..<n) {
            val s: String = "a".repeat(i)
            val index: Int? = indexer.getOrCreateIndex(s)
            assertThat(indexer.getIndex(s)).isSameInstanceAs(index)
        }
    }

    @org.junit.Test
    fun unindexedStringReturnsNull() {
        assertThat(indexer.getIndex("absent")).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNormalOperation() {
        assertThat(dataPath.exists()).isFalse()
        assertThat(journalPath.exists()).isFalse()
        setupTestContent()
        assertThat(dataPath.exists()).isFalse()
        assertThat(journalPath.exists()).isFalse()

        clock.advance(4)
        assertIndex(9, "xyzqwerty") // This should flush journal to disk.
        assertThat(dataPath.exists()).isFalse()
        assertThat(journalPath.exists()).isTrue()

        indexer.save() // Successful save will remove journal file.
        assertThat(dataPath.exists()).isTrue()
        assertThat(journalPath.exists()).isFalse()

        // Now restore data from file and verify it.
        indexer = PersistentStringIndexer.create(dataPath, journalPath, clock)
        assertThat(journalPath.exists()).isFalse()
        clock.advance(4)
        assertSize(10)
        assertContent()
        assertThat(journalPath.exists()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJournalRecoveryWithoutMainDataFile() {
        assertThat(dataPath.exists()).isFalse()
        assertThat(journalPath.exists()).isFalse()
        setupTestContent()
        assertThat(dataPath.exists()).isFalse()
        assertThat(journalPath.exists()).isFalse()

        clock.advance(4)
        assertIndex(9, "abc1234") // This should flush journal to disk.
        assertThat(dataPath.exists()).isFalse()
        assertThat(journalPath.exists()).isTrue()

        // Now restore data from file and verify it. All data should be restored from journal;
        indexer = PersistentStringIndexer.create(dataPath, journalPath, clock)
        assertThat(dataPath.exists()).isTrue()
        assertThat(journalPath.exists()).isFalse()
        clock.advance(4)
        assertSize(10)
        assertContent()
        assertThat(journalPath.exists()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJournalRecovery() {
        assertThat(dataPath.exists()).isFalse()
        assertThat(journalPath.exists()).isFalse()
        setupTestContent()
        indexer.save()
        assertThat(dataPath.exists()).isTrue()
        assertThat(journalPath.exists()).isFalse()
        val oldDataFileLen: Long = dataPath.getFileSize()

        clock.advance(4)
        assertIndex(9, "another record") // This should flush journal to disk.
        assertSize(10)
        assertThat(dataPath.exists()).isTrue()
        assertThat(journalPath.exists()).isTrue()

        // Now restore data from file and verify it. All data should be restored from journal;
        indexer = PersistentStringIndexer.create(dataPath, journalPath, clock)
        assertThat(dataPath.exists()).isTrue()
        assertThat(journalPath.exists()).isFalse()
        assertThat(dataPath.getFileSize())
            .isGreaterThan(oldDataFileLen) // data file should have been updated
        clock.advance(4)
        assertSize(10)
        assertContent()
        assertThat(journalPath.exists()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConcurrentWritesJournalRecovery() {
        assertThat(dataPath.exists()).isFalse()
        assertThat(journalPath.exists()).isFalse()
        setupTestContent()
        indexer.save()
        assertThat(dataPath.exists()).isTrue()
        assertThat(journalPath.exists()).isFalse()
        val oldDataFileLen: Long = dataPath.getFileSize()

        val size: Int = indexer.size()
        val numToWrite = 50000
        writeLotsOfEntriesConcurrently(numToWrite)
        assertThat(journalPath.exists()).isFalse()
        clock.advance(4)
        assertIndex(size + numToWrite, "another record") // This should flush journal to disk.
        assertSize(size + numToWrite + 1)
        assertThat(dataPath.exists()).isTrue()
        assertThat(journalPath.exists()).isTrue()

        // Now restore data from file and verify it. All data should be restored from journal;
        indexer = PersistentStringIndexer.create(dataPath, journalPath, clock)
        assertThat(dataPath.exists()).isTrue()
        assertThat(journalPath.exists()).isFalse()
        assertThat(dataPath.getFileSize())
            .isGreaterThan(oldDataFileLen) // data file should have been updated
        clock.advance(4)
        assertSize(size + numToWrite + 1)
        assertContent()
        assertThat(journalPath.exists()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCorruptedJournal() {
        journalPath.getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContentAsLatin1(journalPath, "bogus content")
        val e: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable {
                    indexer = PersistentStringIndexer.create(dataPath, journalPath, clock)
                })
        Truth.assertThat(e).hasMessageThat().contains("too short: 13 bytes")

        journalPath.delete()
        setupTestContent()
        assertThat(dataPath.exists()).isFalse()
        assertThat(journalPath.exists()).isFalse()

        clock.advance(4)
        assertIndex(9, "abc1234") // This should flush journal to disk.
        assertThat(dataPath.exists()).isFalse()
        assertThat(journalPath.exists()).isTrue()

        val journalContent: ByteArray = FileSystemUtils.readContent(journalPath)

        // Restore data from file and verify it.
        indexer = PersistentStringIndexer.create(dataPath, journalPath, clock)
        assertThat(indexer.size()).isEqualTo(10)
        assertThat(dataPath.exists()).isTrue()
        assertThat(journalPath.exists()).isFalse()

        // Replace journal with a truncated copy. We should tolerate it and drop the incomplete record.
        assertThat(dataPath.delete()).isTrue()
        FileSystemUtils.writeContent(
            journalPath, java.util.Arrays.copyOf(journalContent, journalContent.size - 1)
        )
        indexer = PersistentStringIndexer.create(dataPath, journalPath, clock)
        assertThat(indexer.size()).isEqualTo(9)

        // Replace journal with a corrupted copy. We should tolerate it and drop remaining records.
        val journalCopy = journalContent.clone()
        journalCopy[95] = -2 // make the key size negative
        FileSystemUtils.writeContent(journalPath, journalCopy)
        indexer = PersistentStringIndexer.create(dataPath, journalPath, clock)
        assertThat(indexer.size()).isEqualTo(9)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDupeIndexCorruption() {
        setupTestContent()
        assertThat(dataPath.exists()).isFalse()
        assertThat(journalPath.exists()).isFalse()

        assertIndex(9, "abc1234") // This should flush journal to disk.
        indexer.save()
        assertThat(dataPath.exists()).isTrue()
        assertThat(journalPath.exists()).isFalse()

        val content: ByteArray = FileSystemUtils.readContent(dataPath)

        // We remove the data file, and instead create a corrupt journal.
        //
        // The journal has a header followed by a sequence of (String, int) pairs, where each int is a
        // unique value. The String is encoded by the length (as an int), and the int is simply encoded
        // as an int. Note that the DataOutputStream class uses big endian by default, so the low-order
        // bits are at the end.
        //
        // For the purpose of this test, we want to make the journal contain two entries with the same
        // index (which is illegal). The PersistentStringIndexer assigns int values in the usual order,
        // starting with zero, and it now contains 9 entries. We simply change the last entry to an
        // index that is guaranteed to already exist. If it is the index 1, we change it to 2, otherwise
        // we change it to 1 - in both cases, the code currently guarantees that the duplicate comes
        // earlier in the stream.
        assertThat(dataPath.delete()).isTrue()
        content[content.size - 1] = if (content[content.size - 1].toInt() == 1) 2.toByte() else 1.toByte()
        FileSystemUtils.writeContent(journalPath, content)

        val e: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable {
                    indexer = PersistentStringIndexer.create(dataPath, journalPath, clock)
                })
        Truth.assertThat(e).hasMessageThat().contains("Corrupted filename index has duplicate entry")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeferredIOFailure() {
        assertThat(dataPath.exists()).isFalse()
        assertThat(journalPath.exists()).isFalse()
        setupTestContent()
        assertThat(dataPath.exists()).isFalse()
        assertThat(journalPath.exists()).isFalse()

        // Ensure that journal cannot be saved.
        journalPath.createDirectoryAndParents()

        clock.advance(4)
        assertIndex(9, "abc1234") // This should flush journal to disk (and fail at that).
        assertThat(dataPath.exists()).isFalse()

        // Subsequent updates should succeed even though journaling is disabled at this point.
        clock.advance(4)
        assertIndex(10, "another record")
        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { indexer.save() })
        Truth.assertThat(e).hasMessageThat().contains(journalPath.getPathString() + " (Is a directory)")
    }
}
