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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.vfs.Path

/** Unit tests for [PersistentMap].  */
@RunWith(JUnit4::class)
class PersistentMapTest {
    private class PersistentStringMap(map: ConcurrentMap<String?, String?>?, mapFile: Path?, journalFile: Path?) :
        PersistentMap<String?, String?>(0x0, CODEC, map, mapFile, journalFile) {
        var flushJournal: Boolean = true
        var keepJournal: Boolean = false

        init {
            load()
        }

        protected override fun shouldFlushJournal(): Boolean {
            return flushJournal
        }

        protected override fun shouldKeepJournal(): Boolean {
            return keepJournal
        }

        companion object {
            private val CODEC: MapCodec<String?, String?> = object : MapCodec<String?, String?>() {
                @Throws(IOException::class)
                protected override fun readKey(`in`: DataInput): String? {
                    return `in`.readUTF()
                }

                @Throws(IOException::class)
                protected override fun readValue(`in`: DataInput): String? {
                    return `in`.readUTF()
                }

                @Throws(IOException::class)
                protected override fun writeKey(key: String?, out: DataOutput) {
                    out.writeUTF(key)
                }

                @Throws(IOException::class)
                protected override fun writeValue(value: String?, out: DataOutput) {
                    out.writeUTF(value)
                }
            }
        }
    }

    private val scratch: Scratch = Scratch()

    private var map: PersistentStringMap? = null
    private var mapFile: Path? = null
    private var journalFile: Path? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createFiles() {
        val root: Path = scratch.dir("/tmp")
        mapFile = root.getChild("map.txt")
        journalFile = root.getChild("journal.txt")
        createMap()
    }

    @Throws(java.lang.Exception::class)
    private fun createMap() {
        val map: ConcurrentMap<String?, String?> = ConcurrentHashMap<String?, String?>()
        this.map = PersistentStringMap(map, mapFile, journalFile)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun map() {
        createMap()
        map.put("foo", "bar")
        map.put("baz", "bang")
        Truth.assertThat(map).containsEntry("foo", "bar")
        Truth.assertThat(map).containsEntry("baz", "bang")
        Truth.assertThat(map).hasSize(2)
        val size: Long = map.save()
        Truth.assertThat(size).isEqualTo(mapFile.getFileSize())
        Truth.assertThat(map).containsEntry("foo", "bar")
        Truth.assertThat(map).containsEntry("baz", "bang")
        Truth.assertThat(map).hasSize(2)

        createMap() // create a new map
        Truth.assertThat(map).containsEntry("foo", "bar")
        Truth.assertThat(map).containsEntry("baz", "bang")
        Truth.assertThat(map).hasSize(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun putIfAbsent() {
        createMap()
        assertThat(map.putIfAbsent("foo", "bar")).isNull()
        assertThat(map.putIfAbsent("foo", "ignored")).isEqualTo("bar")
        assertThat(map.putIfAbsent("baz", "bang")).isNull()
        assertThat(map.putIfAbsent("baz", "ignored")).isEqualTo("bang")
        Truth.assertThat(map).containsEntry("foo", "bar")
        Truth.assertThat(map).containsEntry("baz", "bang")
        Truth.assertThat(map).hasSize(2)
        val size: Long = map.save()
        Truth.assertThat(size).isEqualTo(mapFile.getFileSize())
        Truth.assertThat(map).containsEntry("foo", "bar")
        Truth.assertThat(map).containsEntry("baz", "bang")
        Truth.assertThat(map).hasSize(2)

        createMap() // create a new map
        Truth.assertThat(map).containsEntry("foo", "bar")
        Truth.assertThat(map).containsEntry("baz", "bang")
        Truth.assertThat(map).hasSize(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun remove() {
        createMap()
        map.put("foo", "bar")
        map.put("baz", "bang")
        val size: Long = map.save()
        Truth.assertThat(size).isEqualTo(mapFile.getFileSize())
        assertThat(journalFile.exists()).isFalse()
        map.remove("foo")
        Truth.assertThat(map).hasSize(1)
        assertThat(journalFile.exists()).isTrue()
        createMap() // create a new map
        Truth.assertThat(map).hasSize(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun clear() {
        createMap()
        map.put("foo", "bar")
        map.put("baz", "bang")
        map.save()
        assertThat(mapFile.exists()).isTrue()
        assertThat(journalFile.exists()).isFalse()
        map.clear()
        Truth.assertThat(map).isEmpty()
        assertThat(mapFile.exists()).isTrue()
        assertThat(journalFile.exists()).isFalse()
        createMap() // create a new map
        Truth.assertThat(map).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noFlushJournal() {
        createMap()
        map.put("foo", "bar")
        map.put("baz", "bang")
        map.save()
        assertThat(journalFile.exists()).isFalse()
        // prevent flushing the journal
        map!!.flushJournal = false
        // remove an entry
        map.remove("foo")
        Truth.assertThat(map).hasSize(1)
        // no journal file written
        assertThat(journalFile.exists()).isFalse()
        createMap() // create a new map
        // both entries are still in the map on disk
        Truth.assertThat(map).hasSize(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun keepJournal() {
        createMap()
        map.put("foo", "bar")
        map.put("baz", "bang")
        map.save()
        assertThat(journalFile.exists()).isFalse()

        // Keep the journal through the save.
        map!!.flushJournal = false
        map!!.keepJournal = true

        // remove an entry
        map.remove("foo")
        Truth.assertThat(map).hasSize(1)
        // no journal file written
        assertThat(journalFile.exists()).isFalse()

        val size: Long = map.save()
        Truth.assertThat(map).hasSize(1)
        // The journal must be serialized on save(), even if !flushJournal.
        assertThat(journalFile.exists()).isTrue()
        Truth.assertThat(size).isEqualTo(journalFile.getFileSize() + mapFile.getFileSize())

        map.load()
        Truth.assertThat(map).hasSize(1)
        assertThat(journalFile.exists()).isTrue()

        createMap() // create a new map
        Truth.assertThat(map).hasSize(1)

        map!!.keepJournal = false
        map.save()
        Truth.assertThat(map).hasSize(1)
        assertThat(journalFile.exists()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun keepJournalWithMultipleSaves() {
        createMap()
        map.put("foo", "bar")
        map.put("baz", "bang")
        map.save()
        map!!.flushJournal = false
        map!!.keepJournal = true
        map.remove("foo")
        Truth.assertThat(map).hasSize(1)
        map.save()
        map.remove("baz")
        map.save()
        Truth.assertThat(map).isEmpty()
        // Ensure recreating the map loads the correct state.
        createMap()
        Truth.assertThat(map).isEmpty()
        assertThat(journalFile.exists()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleJournalUpdates() {
        createMap()
        map.put("foo", "bar")
        map.save()
        assertThat(journalFile.exists()).isFalse()
        // add an entry
        map.put("baz", "bang")
        Truth.assertThat(map).hasSize(2)
        // journal file written
        assertThat(journalFile.exists()).isTrue()
        createMap() // create a new map
        // both entries are still in the map on disk
        Truth.assertThat(map).hasSize(2)
        // add another entry
        map.put("baz2", "bang2")
        Truth.assertThat(map).hasSize(3)
        // journal file written
        assertThat(journalFile.exists()).isTrue()
        createMap() // create a new map
        // all three entries are still in the map on disk
        Truth.assertThat(map).hasSize(3)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun concurrentOperations() {
        createMap()
        map!!.keepJournal = true
        val numIters = 1000
        val fooPutter: TestThread =
            TestThread(
                TestRunnable {
                    for (i in 0..<numIters) {
                        map.put("foo", "bar" + i)
                        map.remove("baz")
                    }
                })
        val bazPutter: TestThread =
            TestThread(
                TestRunnable {
                    for (i in 0..<numIters) {
                        map.put("baz", "bar" + i)
                        map.remove("noexist")
                    }
                })
        fooPutter.start()
        bazPutter.start()
        fooPutter.joinAndAssertState(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
        bazPutter.joinAndAssertState(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
        map.save()
        assertThat(journalFile.exists()).isTrue()
        createMap()
        Truth.assertThat(map).containsEntry("foo", "bar" + (numIters - 1))
        val bazValue: String? = map.get("baz")
        if (bazValue != null) {
            Truth.assertThat(bazValue).isEqualTo("bar" + (numIters - 1))
        }
    }
}
