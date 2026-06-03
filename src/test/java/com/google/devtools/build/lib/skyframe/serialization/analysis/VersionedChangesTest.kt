// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization.analysis

import com.google.devtools.build.lib.skyframe.serialization.analysis.VersionedChanges.CLIENT_CHANGE

@RunWith(JUnit4::class)
class VersionedChangesTest {
    @org.junit.Test
    fun clientFileChange_matchesFiles() {
        val changes: VersionedChanges = VersionedChanges(com.google.common.collect.ImmutableList.of<E?>("abc", "def"))

        assertThat(changes.matchFileChange("abc", 0)).isEqualTo(CLIENT_CHANGE)
        assertThat(changes.matchFileChange("def", 0)).isEqualTo(CLIENT_CHANGE)
    }

    @org.junit.Test
    fun clientFileChange_matchesListing() {
        val changes: VersionedChanges = VersionedChanges(com.google.common.collect.ImmutableList.of<E?>("abc/def"))

        assertThat(changes.matchFileChange("abc/def", 0)).isEqualTo(CLIENT_CHANGE)
        assertThat(changes.matchListingChange("abc/def", 0)).isEqualTo(NO_MATCH)
        assertThat(changes.matchFileChange("abc", 0)).isEqualTo(NO_MATCH)
        assertThat(changes.matchListingChange("abc", 0)).isEqualTo(CLIENT_CHANGE)
    }

    @org.junit.Test
    fun registerChange_matches() {
        val changes: VersionedChanges = VersionedChanges(com.google.common.collect.ImmutableList.of<E?>())
        changes.registerFileChange("abc/def", 10)

        assertThat(changes.matchFileChange("abc/def", 10)).isEqualTo(NO_MATCH)
        assertThat(changes.matchFileChange("abc/def", 9)).isEqualTo(10)

        assertThat(changes.matchListingChange("abc", 10)).isEqualTo(NO_MATCH)
        assertThat(changes.matchListingChange("abc", 9)).isEqualTo(10)
    }

    @org.junit.Test
    fun findMinimumVersionGreaterThanOrEqualTo_exactMatch() {
        val versions = intArrayOf(2, 4, 6, 8, 10)
        assertThat(VersionedChanges.findMinimumVersionGreaterThanOrEqualTo(versions, 6)).isEqualTo(6)
    }

    @org.junit.Test
    fun findMinimumVersionGreaterThanOrEqualTo_noMatchLarger() {
        val versions = intArrayOf(2, 4, 6, 8, 10)
        assertThat(VersionedChanges.findMinimumVersionGreaterThanOrEqualTo(versions, 12))
            .isEqualTo(NO_MATCH)
    }

    @org.junit.Test
    fun findMinimumVersionGreaterThanOrEqualTo_noMatchSmaller() {
        val versions = intArrayOf(2, 4, 6, 8, 10)
        assertThat(VersionedChanges.findMinimumVersionGreaterThanOrEqualTo(versions, 1)).isEqualTo(2)
    }

    @org.junit.Test
    fun findMinimumVersionGreaterThanOrEqualTo_inBetweenMatch() {
        val versions = intArrayOf(2, 4, 6, 8, 10)
        assertThat(VersionedChanges.findMinimumVersionGreaterThanOrEqualTo(versions, 7)).isEqualTo(8)
    }

    @org.junit.Test
    fun findMinimumVersionGreaterThanOrEqualTo_emptyArray() {
        val versions = intArrayOf()
        assertThat(VersionedChanges.findMinimumVersionGreaterThanOrEqualTo(versions, 5))
            .isEqualTo(NO_MATCH)
    }

    @org.junit.Test
    fun findMinimumVersionGreaterThanOrEqualTo_nullArray() {
        assertThat(VersionedChanges.findMinimumVersionGreaterThanOrEqualTo(null, 5))
            .isEqualTo(NO_MATCH)
    }

    @org.junit.Test
    fun findMinimumVersionGreaterThanOrEqualTo_firstMatch() {
        val versions = intArrayOf(2, 4, 6, 8, 10)
        assertThat(VersionedChanges.findMinimumVersionGreaterThanOrEqualTo(versions, 2)).isEqualTo(2)
    }

    @org.junit.Test
    fun findMinimumVersionGreaterThanOrEqualTo_lastMatch() {
        val versions = intArrayOf(2, 4, 6, 8, 10)
        assertThat(VersionedChanges.findMinimumVersionGreaterThanOrEqualTo(versions, 10)).isEqualTo(10)
    }

    @org.junit.Test
    fun findMinimumVersionGreaterThanOrEqualTo_veryLargeMinVersion() {
        val versions = intArrayOf(2, 4, 6, 8, 10)
        assertThat(VersionedChanges.findMinimumVersionGreaterThanOrEqualTo(versions, java.lang.Integer.MAX_VALUE))
            .isEqualTo(NO_MATCH)
    }

    @org.junit.Test
    fun findMinimumVersionGreaterThanOrEqualTo_negativeMinVersion() {
        val versions = intArrayOf(-2, 2, 4, 6, 8, 10)
        assertThat(VersionedChanges.findMinimumVersionGreaterThanOrEqualTo(versions, -5)).isEqualTo(-2)
    }

    @org.junit.Test
    fun insertChange_newPath() {
        val changes: ConcurrentHashMap<String?, IntArray?> = ConcurrentHashMap<String?, IntArray?>()
        VersionedChanges.insertChange("file1.txt", 1, changes)
        Truth.assertThat(changes.get("file1.txt")).isEqualTo(intArrayOf(1))
    }

    @org.junit.Test
    fun insertChange_existingPath_insertNewVersion() {
        val changes: ConcurrentHashMap<String?, IntArray?> = createChangesMap("file1.txt", 1, 3, 5)
        VersionedChanges.insertChange("file1.txt", 4, changes)
        Truth.assertThat(changes.get("file1.txt")).isEqualTo(intArrayOf(1, 3, 4, 5))
    }

    @org.junit.Test
    fun insertChange_existingPath_duplicateVersion() {
        val changes: ConcurrentHashMap<String?, IntArray?> = createChangesMap("file1.txt", 1, 3, 5)
        VersionedChanges.insertChange("file1.txt", 3, changes)
        Truth.assertThat(changes.get("file1.txt")).isEqualTo(intArrayOf(1, 3, 5))
    }

    @org.junit.Test
    fun insertChange_multiplePaths() {
        val changes: ConcurrentHashMap<String?, IntArray?> = ConcurrentHashMap<String?, IntArray?>()
        VersionedChanges.insertChange("file1.txt", 2, changes)
        VersionedChanges.insertChange("file2.txt", 1, changes)
        VersionedChanges.insertChange("file1.txt", 1, changes)
        VersionedChanges.insertChange("file2.txt", 3, changes)

        Truth.assertThat(changes.get("file1.txt")).isEqualTo(intArrayOf(1, 2))
        Truth.assertThat(changes.get("file2.txt")).isEqualTo(intArrayOf(1, 3))
    }

    @org.junit.Test
    fun insertSorted_emptyArray() {
        val result: IntArray? = VersionedChanges.insertSorted(intArrayOf(), 5)
        Truth.assertThat(result).isEqualTo(intArrayOf(5))
    }

    @org.junit.Test
    fun insertSorted_insertAtBeginning() {
        val result: IntArray? = VersionedChanges.insertSorted(intArrayOf(2, 4, 6), 1)
        Truth.assertThat(result).isEqualTo(intArrayOf(1, 2, 4, 6))
    }

    @org.junit.Test
    fun insertSorted_insertInMiddle() {
        val result: IntArray? = VersionedChanges.insertSorted(intArrayOf(2, 4, 6), 3)
        Truth.assertThat(result).isEqualTo(intArrayOf(2, 3, 4, 6))
    }

    @org.junit.Test
    fun insertSorted_insertAtEnd() {
        val result: IntArray? = VersionedChanges.insertSorted(intArrayOf(2, 4, 6), 7)
        Truth.assertThat(result).isEqualTo(intArrayOf(2, 4, 6, 7))
    }

    @org.junit.Test
    fun insertSorted_duplicate() {
        val original = intArrayOf(2, 4, 6)
        val result: IntArray? = VersionedChanges.insertSorted(original, 4)
        Truth.assertThat(result).isSameInstanceAs(original)
    }

    @get:org.junit.Test
    val parentDirectory_validPath: Unit
        get() {
            assertThat(VersionedChanges.getParentDirectory("a/b/c.txt")).isEqualTo("a/b")
        }

    @get:org.junit.Test
    val parentDirectory_rootPath: Unit
        get() {
            assertThat(VersionedChanges.getParentDirectory("/")).isEmpty()
        }

    @get:org.junit.Test
    val parentDirectory_emptyPath: Unit
        get() {
            assertThat(VersionedChanges.getParentDirectory("")).isEmpty()
        }

    @get:org.junit.Test
    val parentDirectory_noSlash: Unit
        get() {
            assertThat(VersionedChanges.getParentDirectory("file.txt")).isEmpty()
        }

    companion object {
        private fun createChangesMap(path: String?, vararg versions: Int): ConcurrentHashMap<String?, IntArray?> {
            val changes: ConcurrentHashMap<String?, IntArray?> = ConcurrentHashMap<String?, IntArray?>()
            changes.put(path, versions)
            return changes
        }
    }
}
