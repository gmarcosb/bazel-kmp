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

import java.util.concurrent.ConcurrentHashMap

/**
 * Stores file and listing changes and versions for a given reader.
 * 
 * 
 * Some brief definitions.
 * 
 * 
 *  * [FileSystemDependencies] **node**: a (nested) set of files and listings
 * representing the dependencies of a cached value.
 *  * **client version (VC)**: synced version of the client performing cache lookups.
 *  * **max transitive source version (MTSV)**: the canonical version of a *node* equal
 * to the first version at which a node obtains its current value.
 *  * **validity horizon (VH)**: the last version where the *node* is known to be valid.
 * 
 * 
 * 
 * VC is per reader, while MTSV and VH are both per node.
 * 
 * 
 * Before calling [.matchFileChange] or [.matchListingChange], the client **must**
 * ensure the following:
 * 
 * 
 *  * All client changes are registered via the `clientFileChanges` constructor parameter.
 *  * All depot changes in the range (VH, VC] have been registered with [       ][.registerFileChange]. (This range *excludes* VH and includes VC).
 * 
 * 
 * 
 * Note that if VH ≥ VC, (VH, VC] is empty and no depot changes need to registered. Only changes
 * in the client must be considered. A special case is when the client is synced to the same version
 * as the writer of the cache entry. Then VH = VC and the range is empty.
 * 
 * <h2>Node Validity Range</h2>
 * 
 * 
 * Every [FileSystemDependencies] node has a *dynamic* range of validity. The lower
 * bound is the node's **maximum transitive source version (MTSV)**, which is the maximum version
 * at which any of the node's dependencies changed. MTSVs are canonical.
 * 
 * 
 * While the lower bound is uniquely determined, the upper bound may be unknown. For example, the
 * invalidating change may not have occurred yet. Instead, there is an increasing **validity
 * horizon (VH)**, initially equal to MTSV. It is determined by lazily probing for invalidating
 * changes. If a probe finds no invalidating changes, VH increases to the probed version. Otherwise,
 * a specific invalidating change number can be identified, which is the value returned by [ ][.matchFileChange] or [.matchListingChange]. This invalidating change number can be used to
 * update VH, marking it closed.
 * 
 * 
 * The validity range [MTSV, VH] *includes* its endpoints.
 */
internal class VersionedChanges(clientFileChanges: Iterable<String>) {
    // TODO: b/364831651 - if sorted int[] does not scale, it can be replaced with TreeSet<Integer>
    // but we expect the number of changes per entry to be small.
    private val fileChanges: ConcurrentHashMap<String?, IntArray?> = ConcurrentHashMap<String?, IntArray?>()

    /** Contains all the parent directories of [.fileChanges] for efficient lookup.  */
    private val listingChanges: ConcurrentHashMap<String?, IntArray?> = ConcurrentHashMap<String?, IntArray?>()

    init {
        for (change in clientFileChanges) {
            registerFileChange(change, CLIENT_CHANGE)
        }
    }

    val isEmpty: Boolean
        get() =// `listingChanges` is empty iff `fileChanges` is empty, so checking one is enough.
            fileChanges.isEmpty()

    @get:com.google.common.annotations.VisibleForTesting
    val fileChangesForTesting: ConcurrentHashMap<String?, IntArray?>
        get() = fileChanges

    @get:com.google.common.annotations.VisibleForTesting
    val listingChangesForTesting: ConcurrentHashMap<String?, IntArray?>
        get() = listingChanges

    /**
     * Checks for a change to `path` with at least version `validityHorizon`.
     * 
     * 
     * This method is thread safe.
     * 
     * @param validityHorizon the VH (see class description for more details) of the current node
     * being checked for invalidating changes.
     * @return the smallest version greater than `validityHorizon` if a match is found and
     * [.NO_MATCH] otherwise. Returns [.CLIENT_CHANGE] if a change in the client is
     * the only match.
     */
    fun matchFileChange(path: String?, validityHorizon: Int): Int {
        // Finds a version beyond the known validity horizon.
        return findMinimumVersionGreaterThanOrEqualTo(fileChanges.get(path), validityHorizon + 1)
    }

    /**
     * Checks for a change to a listing of `path` with at least version `validityHorizon`.
     * 
     * 
     * Parameters and return value have the same meaning as [.matchFileChange], but this
     * method is for listings instead of files.
     * 
     * 
     * This method is thread safe.
     */
    fun matchListingChange(path: String?, validityHorizon: Int): Int {
        // Finds a version beyond the known validity horizon.
        return findMinimumVersionGreaterThanOrEqualTo(listingChanges.get(path), validityHorizon + 1)
    }

    /**
     * Adds a file and change, and induces a corresponding listing change.
     * 
     * 
     * It's safe to call this concurrently with [.matchFileChange] and [ ][.matchListingChange]. However, concurrent calls to this method for the same path are not safe.
     * 
     * 
     * This is sufficient for singly-threaded updates.
     */
    fun registerFileChange(path: String, version: Int) {
        insertChange(path, version, fileChanges)
        insertChange(getParentDirectory(path), version, listingChanges)
    }

    companion object {
        /**
         * Sentinel value indicating that there was no match.
         * 
         * 
         * Most of the versioning logic here aggregates versions by taking the minimum. This choice of
         * sentinel value makes it always aggregate out when combined with non-sentinel values.
         */
        @kotlin.jvm.JvmField
        val NO_MATCH: Int = Int.Companion.MAX_VALUE

        /**
         * Sentinel version indicating a change in the client.
         * 
         * 
         * This high value makes client changes a lower priority for match than checked-in changes.
         */
        @kotlin.jvm.JvmField
        val CLIENT_CHANGE: Int = Int.Companion.MAX_VALUE - 1

        /**
         * Version indicating a match at any change.
         * 
         * 
         * Used when there is missing data and correct invalidation is impossible.
         */
        val ALWAYS_MATCH: Int = -1

        @kotlin.jvm.JvmStatic
        @com.google.common.annotations.VisibleForTesting
        fun findMinimumVersionGreaterThanOrEqualTo(versions: IntArray?, minVersion: Int): Int {
            if (versions == null) {
                return NO_MATCH
            }

            var index: Int = java.util.Arrays.binarySearch(versions, minVersion)
            if (index >= 0) {
                return minVersion // Exact match.
            }

            // If not found, binarySearch returns (-(insertion point) - 1), where the insertion point is
            // the index of the first element greater than the key.
            //
            // For example, if there is no exact match for `3` in `[1,2,4,5]`, then the insertion point is
            // 2 (index of the element `4`), and `binarySearch` will return `-(2)-1`. Given that we want to
            // return the minimum version greater than `minVersion`, we need to return the version at the
            // insertion point.
            index = -index - 1
            if (index >= versions.size) {
                return NO_MATCH // All versions earlier than minVersion.
            }
            return versions[index]
        }

        @com.google.common.annotations.VisibleForTesting
        fun insertChange(path: String?, version: Int, changes: ConcurrentHashMap<String?, IntArray?>) {
            var versions: IntArray? = changes.get(path)
            if (versions == null) {
                versions = intArrayOf(version)
            } else {
                val newVersions = insertSorted(versions, version)
                if (newVersions == versions) {
                    return  // unchanged
                }
                versions = newVersions
            }
            changes.put(path, versions)
        }

        @kotlin.jvm.JvmStatic
        @com.google.common.annotations.VisibleForTesting
        fun insertSorted(versions: IntArray, newVersion: Int): IntArray {
            var index: Int = java.util.Arrays.binarySearch(versions, newVersion)
            if (index >= 0) {
                return versions // Duplicate. Returns the original.
            }

            // If not found, binarySearch returns (-(insertion point) - 1). This calculates the correct
            // insertion point.
            index = -index - 1

            val newVersions = IntArray(versions.size + 1)
            java.lang.System.arraycopy(versions, 0, newVersions, 0, index)
            newVersions[index] = newVersion
            java.lang.System.arraycopy(versions, index, newVersions, index + 1, versions.size - index)
            return newVersions
        }

        @kotlin.jvm.JvmStatic
        @com.google.common.annotations.VisibleForTesting
        fun getParentDirectory(path: String): String {
            val directoryEnd: Int = path.lastIndexOf('/')
            if (directoryEnd == -1) {
                return ""
            }
            return path.substring(0, directoryEnd)
        }
    }
}
