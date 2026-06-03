// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages

import com.google.common.truth.Truth
import com.google.devtools.build.lib.packages.SnapshottableBiMapTest
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.AbstractMap

/** Tests for [SnapshottableBiMap].  */
@RunWith(JUnit4::class)
class SnapshottableBiMapTest {
    // Dummy value type for maps under test. AutoValue for correct hash/equals behavior.
    @kotlin.jvm.JvmRecord
    internal data class Value(val name: String?, val tracked: Boolean) {
        init {
            java.util.Objects.requireNonNull<String?>(name, "name")
        }

        companion object {
            fun trackedOf(name: String?): Value {
                return com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value(name, true)
            }

            fun untrackedOf(name: String?): Value {
                return com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value(name, false)
            }

            fun track(value: Value): Boolean {
                return value.tracked
            }
        }
    }

    @org.junit.Test
    fun containsInsertedEntries() {
        val map: SnapshottableBiMap<String?, Value?> = SnapshottableBiMap({ value: Value ->
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.track(value)
        })
        verifyBiMapIsEmpty<Any?, Any?>(map)
        val a: Value = com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("a")
        val b: Value = com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.untrackedOf("b")
        val c: Value = com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("c")
        val z: Value = com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("z")

        map.put("a", a)
        verifyBiMapSizeAndContentsInOrder<String?, Value?>(map, "a", a)

        map.put("b", b)
        verifyBiMapSizeAndContentsInOrder<String?, Value?>(map, "a", a, "b", b)

        map.put("c", c)
        verifyBiMapSizeAndContentsInOrder<String?, Value?>(map, "a", a, "b", b, "c", c)

        // verify that the map's various contains*() methods don't always return true.
        verifyMapDoesNotContainEntry<String?, Value?>(map, "z", z)
    }

    @org.junit.Test
    fun put_replacesEntries() {
        val map: SnapshottableBiMap<String?, Value?> = SnapshottableBiMap({ value: Value ->
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.track(value)
        })
        val trackedA: Value =
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("a")
        val replaceA: Value =
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("replace a")
        val untrackedB: Value =
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.untrackedOf("b")
        val replaceB: Value =
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.untrackedOf("b")

        map.put("a", trackedA)
        map.put("a", replaceA)
        map.put("b", untrackedB)
        map.put("b", replaceB)
        verifyBiMapSizeAndContentsInOrder<String?, Value?>(map, "a", replaceA, "b", replaceB)
    }

    @org.junit.Test
    fun put_nonUniqueValue_illegal() {
        val map: SnapshottableBiMap<String?, Value?> = SnapshottableBiMap({ value: Value ->
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.track(value)
        })
        val tracked: Value =
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("a")
        val untracked: Value =
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.untrackedOf("b")

        map.put("a", tracked)
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { map.put("aa", tracked) })
        map.put("b", untracked)
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { map.put("bb", untracked) })
    }

    @org.junit.Test
    fun put_replacingUntrackedWithTracked_legal() {
        val map: SnapshottableBiMap<String?, Value?> = SnapshottableBiMap({ value: Value ->
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.track(value)
        })
        val tracked: Value =
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("a")
        val untracked: Value =
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.untrackedOf("A")

        map.getTrackedSnapshot() // start tracking
        map.put("a", untracked)
        map.put("a", tracked)
        verifyBiMapSizeAndContentsInOrder<String?, Value?>(map, "a", tracked)
    }

    @org.junit.Test
    fun put_replacingTrackedWithUntracked_illegal() {
        val map: SnapshottableBiMap<String?, Value?> = SnapshottableBiMap({ value: Value ->
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.track(value)
        })
        val tracked: Value =
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("a")
        val untracked: Value =
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.untrackedOf("A")

        map.getTrackedSnapshot() // start tracking
        map.put("a", tracked)
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { map.put("a", untracked) })
    }

    @org.junit.Test
    @Suppress("deprecation") // test verifying that deprecated methods don't work
    fun deletions_unsupported() {
        val map: SnapshottableBiMap<String?, Value?> = SnapshottableBiMap({ value: Value ->
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.track(value)
        })
        val value: Value = com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("a")
        val replacement: Value =
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("replacement a")

        map.put("a", value)
        verifyMapDoesNotAllowDeletions<Any?, Any?>(map)
        Companion.verifyMapDoesNotAllowDeletions<K?, V?>(map.inverse())
        org.junit.Assert.assertThrows<java.lang.UnsupportedOperationException?>(
            java.lang.UnsupportedOperationException::class.java,
            org.junit.function.ThrowingRunnable { map.forcePut("a", replacement) })
        org.junit.Assert.assertThrows<java.lang.UnsupportedOperationException?>(
            java.lang.UnsupportedOperationException::class.java,
            org.junit.function.ThrowingRunnable { map.inverse().forcePut(value, "aa") })
    }

    @get:org.junit.Test
    val underlyingBiMap_returnsBiMapSupportingRemove: Unit
        get() {
            val map: SnapshottableBiMap<String?, Value?> =
                SnapshottableBiMap({ value: Value ->
                    com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.track(value)
                })
            val a: Value =
                com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("a")
            val b: Value =
                com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.untrackedOf("b")
            val c: Value =
                com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("c")

            map.put("a", a)
            map.put("b", b)
            map.put("c", c)
            val underlying: com.google.common.collect.BiMap<String?, Value?> =
                map.getUnderlyingBiMap()
            verifyBiMapSizeAndContentsInOrder<String?, Value?>(
                underlying,
                "a",
                a,
                "b",
                b,
                "c",
                c
            )

            underlying.remove("a")
            verifyBiMapSizeAndContentsInOrder<String?, Value?>(
                underlying,
                "b",
                b,
                "c",
                c
            )
        }

    @org.junit.Test
    fun snapshot_containsExpectedEntries() {
        val map: SnapshottableBiMap<String?, Value?> = SnapshottableBiMap({ value: Value ->
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.track(value)
        })
        val trackedA: Value =
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("a")
        val untrackedB: Value =
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.untrackedOf("b")
        val trackedC: Value =
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("c")
        val z: Value = com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("z")

        val snapshot0: MutableMap<String?, Value?>? = map.getTrackedSnapshot()
        verifyMapIsEmpty<String?, Value?>(snapshot0)

        map.put("a", trackedA)
        val snapshot1: MutableMap<String?, Value?> = map.getTrackedSnapshot()
        verifyMapIsEmpty<String?, Value?>(snapshot0)
        verifyMapSizeAndContentsInOrder<String?, Value?>(snapshot1, "a", trackedA)

        map.put("b", untrackedB)
        val snapshot2: MutableMap<String?, Value?> = map.getTrackedSnapshot()
        verifyMapIsEmpty<String?, Value?>(snapshot0)
        verifyMapSizeAndContentsInOrder<String?, Value?>(snapshot1, "a", trackedA)
        verifyMapSizeAndContentsInOrder<String?, Value?>(snapshot2, "a", trackedA) // b is untracked

        map.put("c", com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("c"))
        val snapshot3: MutableMap<String?, Value?> = map.getTrackedSnapshot()
        verifyMapIsEmpty<String?, Value?>(snapshot0)
        verifyMapSizeAndContentsInOrder<String?, Value?>(snapshot1, "a", trackedA) // c was added after snapshot
        verifyMapSizeAndContentsInOrder<String?, Value?>(snapshot2, "a", trackedA)
        verifyMapSizeAndContentsInOrder<String?, Value?>(snapshot3, "a", trackedA, "c", trackedC)

        // verify that a snapshot's various contains*() methods don't always return true.
        verifyMapDoesNotContainEntry<String?, Value?>(snapshot1, "z", z)
        verifyMapDoesNotContainEntry<String?, Value?>(snapshot2, "z", z)
        verifyMapDoesNotContainEntry<String?, Value?>(snapshot3, "z", z)
    }

    @org.junit.Test
    fun snapshot_isUnmodifiable() {
        val map: SnapshottableBiMap<String?, Value?> = SnapshottableBiMap({ value: Value ->
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.track(value)
        })
        map.put("a", com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("a"))
        map.put("b", com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.untrackedOf("b"))
        map.put("c", com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("c"))
        val snapshot: MutableMap<String?, Value?> = map.getTrackedSnapshot()

        verifyMapDoesNotAllowDeletions<String?, Value?>(snapshot)
        org.junit.Assert.assertThrows<java.lang.UnsupportedOperationException?>(
            java.lang.UnsupportedOperationException::class.java,
            org.junit.function.ThrowingRunnable {
                snapshot.put(
                    "a",
                    com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("replace a")
                )
            })
        org.junit.Assert.assertThrows<java.lang.UnsupportedOperationException?>(
            java.lang.UnsupportedOperationException::class.java,
            org.junit.function.ThrowingRunnable {
                snapshot.put(
                    "d",
                    com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("d")
                )
            })
    }

    @org.junit.Test
    fun snapshot_containsReplacementsPerformedBeforeSnapshotCreation() {
        val map: SnapshottableBiMap<String?, Value?> = SnapshottableBiMap({ value: Value ->
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.track(value)
        })
        val trackedA: Value =
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("a")
        val replacementA: Value =
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("replacement a")
        val untrackedB: Value =
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.untrackedOf("b")
        val replacementB: Value =
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("replacement b")

        map.put("a", trackedA)
        map.put("b", untrackedB)
        verifyMapSizeAndContentsInOrder<String?, Value?>(map, "a", trackedA, "b", untrackedB)
        map.put("a", replacementA)
        map.put("b", replacementB)
        verifyMapSizeAndContentsInOrder<String?, Value?>(map, "a", replacementA, "b", replacementB)

        val snapshot: MutableMap<String?, Value?>? = map.getTrackedSnapshot()
        verifyMapSizeAndContentsInOrder<String?, Value?>(snapshot, "a", replacementA, "b", replacementB)
    }

    @org.junit.Test
    fun snapshot_afterReplacingEntryInSnapshot_containsReplacement() {
        val map: SnapshottableBiMap<String?, Value?> = SnapshottableBiMap({ value: Value ->
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.track(value)
        })
        val original: Value =
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("a")
        val replacement: Value =
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("replacement a")

        map.put("a", original)
        val snapshot: MutableMap<String?, Value?>? = map.getTrackedSnapshot()
        verifyMapSizeAndContentsInOrder<String?, Value?>(snapshot, "a", original)

        map.put("a", replacement)
        verifyMapSizeAndContentsInOrder<String?, Value?>(snapshot, "a", replacement)
    }

    @org.junit.Test
    fun snapshot_afterReplacingEntryNotInSnapshot_doesNotContainReplacement() {
        val map: SnapshottableBiMap<String?, Value?> = SnapshottableBiMap({ value: Value ->
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.track(value)
        })
        val untrackedA: Value =
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.untrackedOf("a")
        val replacementA: Value =
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("replacement a")
        val trackedB: Value =
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("b")
        val replacementB: Value =
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("replacement b")

        map.put("a", untrackedA)
        val snapshot: MutableMap<String?, Value?>? = map.getTrackedSnapshot()
        verifyMapIsEmpty<String?, Value?>(snapshot)

        map.put("a", replacementA)
        map.put("b", trackedB)
        map.put("b", replacementB)
        verifyMapSizeAndContentsInOrder<String?, Value?>(map, "a", replacementA, "b", replacementB)
        verifyMapIsEmpty<String?, Value?>(snapshot)
    }

    @org.junit.Test
    fun snapshot_containsReplacementEntries_inOriginalKeyInsertionOrder() {
        val map: SnapshottableBiMap<String?, Value?> = SnapshottableBiMap({ value: Value ->
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.track(value)
        })
        val a: Value = com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("a")
        val b: Value = com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("b")
        val replaceB: Value =
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("replacement b")
        val c: Value = com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("c")
        val replaceC: Value =
            com.google.devtools.build.lib.packages.SnapshottableBiMapTest.Value.Companion.trackedOf("replacement c")

        map.put("a", a)
        map.put("b", b)
        map.put("c", c)

        val snapshot: MutableMap<String?, Value?>? = map.getTrackedSnapshot()
        verifyMapSizeAndContentsInOrder<String?, Value?>(snapshot, "a", a, "b", b, "c", c)

        map.put("c", replaceC)
        map.put("b", replaceB)
        verifyMapSizeAndContentsInOrder<String?, Value?>(snapshot, "a", a, "b", replaceB, "c", replaceC)
    }

    companion object {
        private fun <E> verifyCollectionSizeAndContentsInOrder(
            collection: MutableCollection<E?>?, expected: MutableCollection<E?>
        ) {
            // Exhaustive testing of a collection's methods; we cannot rely on a minimal usual set of JUnit
            // helpers because we want to verify that the collection has valid Collection semantics.
            if (expected.isEmpty()) {
                Truth.assertThat(collection).isEmpty()
            } else {
                Truth.assertThat(collection).isNotEmpty()
            }
            Truth.assertThat(collection).hasSize(expected.size)
            Truth.assertThat(collection).containsExactlyElementsIn(expected).inOrder()
            for (entry in expected) {
                // JUnit's containsExactlyElementsIn iterates over the collection under test, but doesn't call
                // its contains() method.
                Truth.assertThat(collection).contains(entry)
            }
        }

        private fun <K, V> verifyMapSizeAndContentsInOrder(map: MutableMap<K?, V?>?, expectedMap: MutableMap<K?, V?>) {
            // Exhaustive testing of a map's methods; we cannot rely on a minimal usual set of JUnit helpers
            // because we want to verify that the map has valid Map semantics.
            if (expectedMap.isEmpty()) {
                Truth.assertThat(map).isEmpty()
            } else {
                Truth.assertThat(map).isNotEmpty()
            }

            Truth.assertThat(map).hasSize(expectedMap.size)
            Truth.assertThat(map).containsExactlyEntriesIn(expectedMap).inOrder()

            for (entry in expectedMap.entries) {
                Truth.assertThat(map!!.containsKey(entry.key))
                    .isTrue() // JUnit's containsKey implementation does not explicitly call map.containsKey
                Truth.assertThat(map.containsValue(entry.value)).isTrue()
            }

            Companion.verifyCollectionSizeAndContentsInOrder<MutableMap.MutableEntry<K?, V?>?>(
                map!!.entries,
                expectedMap.entries
            )
            verifyCollectionSizeAndContentsInOrder<K?>(map.keys, expectedMap.keys)
            verifyCollectionSizeAndContentsInOrder<V?>(map.values, expectedMap.values)
        }

        // test-only convenience vararg transformation
        private fun <K, V> verifyMapSizeAndContentsInOrder(
            map: MutableMap<K?, V?>?, key0: K?, value0: V?, vararg rest: Any?
        ) {
            val expectedBuilder: com.google.common.collect.ImmutableMap.Builder<K?, V?> =
                com.google.common.collect.ImmutableMap.builder<K?, V?>()
            expectedBuilder.put(key0, value0)
            com.google.common.base.Preconditions.checkArgument(
                rest.size % 2 == 0, "rest must be a flattened list of key-value pairs"
            )
            var i = 0
            while (i < rest.size) {
                expectedBuilder.put(rest[i] as K?, rest[i + 1] as V?)
                i += 2
            }
            val expectedMap: MutableMap<K?, V?> = expectedBuilder.build()
            verifyMapSizeAndContentsInOrder<K?, V?>(map, expectedMap)
        }

        private fun <K, V> verifyMapDoesNotContainEntry(map: MutableMap<K?, V?>, key: K?, value: V?) {
            val entry: MutableMap.MutableEntry<K?, V?> = AbstractMap.SimpleEntry<K?, V?>(key, value)

            // Exhaustive testing of a map's methods; we cannot rely on a minimal usual set of JUnit helpers
            // because we want to verify that the map has valid Map semantics.
            Truth.assertThat(map.containsKey(key))
                .isFalse() // JUnit's containsKey implementation does not explicitly call map.containsKeys
            Truth.assertThat(map.containsValue(value)).isFalse()
            Truth.assertThat(map.entries).doesNotContain(entry)
            Truth.assertThat(map.keys).doesNotContain(key)
            Truth.assertThat(map.values).doesNotContain(value)
        }

        private fun <K, V> verifyMapIsEmpty(map: MutableMap<K?, V?>?) {
            verifyMapSizeAndContentsInOrder<K?, V?>(map, com.google.common.collect.ImmutableMap.of<K?, V?>())
        }

        private fun <E> verifyIteratorDoesNotAllowDeletions(iterator: MutableIterator<E?>) {
            while (iterator.hasNext()) {
                iterator.next()
                org.junit.Assert.assertThrows<java.lang.UnsupportedOperationException?>(
                    java.lang.UnsupportedOperationException::class.java,
                    org.junit.function.ThrowingRunnable { iterator.remove() })
            }
        }

        private fun <K, V> verifyMapDoesNotAllowDeletions(map: MutableMap<K?, V?>) {
            for (entry in map.entries) {
                val key = entry.key
                val value = entry.value
                org.junit.Assert.assertThrows<java.lang.UnsupportedOperationException?>(
                    java.lang.UnsupportedOperationException::class.java,
                    org.junit.function.ThrowingRunnable { map.remove(key) })
                org.junit.Assert.assertThrows<java.lang.UnsupportedOperationException?>(
                    java.lang.UnsupportedOperationException::class.java,
                    org.junit.function.ThrowingRunnable { map.keys.remove(key) })
                org.junit.Assert.assertThrows<java.lang.UnsupportedOperationException?>(
                    java.lang.UnsupportedOperationException::class.java,
                    org.junit.function.ThrowingRunnable { map.values.remove(value) })
                org.junit.Assert.assertThrows<java.lang.UnsupportedOperationException?>(
                    java.lang.UnsupportedOperationException::class.java,
                    org.junit.function.ThrowingRunnable { map.entries.remove(entry) })
            }

            verifyIteratorDoesNotAllowDeletions<K?>(map.keys.iterator())
            verifyIteratorDoesNotAllowDeletions<V?>(map.values.iterator())
            verifyIteratorDoesNotAllowDeletions<MutableMap.MutableEntry<K?, V?>?>(map.entries.iterator())

            org.junit.Assert.assertThrows<java.lang.UnsupportedOperationException?>(
                java.lang.UnsupportedOperationException::class.java,
                org.junit.function.ThrowingRunnable { map.clear() })
        }

        // test-only convenience vararg transformation
        private fun <K, V> verifyBiMapSizeAndContentsInOrder(
            bimap: com.google.common.collect.BiMap<K?, V?>, key0: K?, value0: V?, vararg rest: Any?
        ) {
            val expectedBuilder: com.google.common.collect.ImmutableBiMap.Builder<K?, V?> =
                com.google.common.collect.ImmutableBiMap.builder<K?, V?>()
            expectedBuilder.put(key0, value0)
            com.google.common.base.Preconditions.checkArgument(
                rest.size % 2 == 0, "rest must be a flattened list of key-value pairs"
            )
            var i = 0
            while (i < rest.size) {
                expectedBuilder.put(rest[i] as K?, rest[i + 1] as V?)
                i += 2
            }
            val expectedBiMap: com.google.common.collect.BiMap<K?, V?> = expectedBuilder.buildOrThrow()
            verifyMapSizeAndContentsInOrder<K?, V?>(bimap, expectedBiMap)
            verifyMapSizeAndContentsInOrder<V?, K?>(bimap.inverse(), expectedBiMap.inverse())
        }

        private fun <K, V> verifyBiMapIsEmpty(bimap: com.google.common.collect.BiMap<K?, V?>) {
            verifyMapSizeAndContentsInOrder<K?, V?>(bimap, com.google.common.collect.ImmutableMap.of<K?, V?>())
            verifyMapSizeAndContentsInOrder<V?, K?>(
                bimap.inverse(),
                com.google.common.collect.ImmutableMap.of<V?, K?>()
            )
        }
    }
}
