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

import com.google.devtools.build.lib.skyframe.serialization.analysis.FileSystemDependencies.FileOpDependency

/**
 * Matches a set of changed files (represented by [VersionedChanges]) against the file system
 * dependencies of cached values to determine cache hits and misses.
 * 
 * 
 * This class compares file and directory listing changes with the dependencies of a cached value
 * to determine if the cached value is still valid.
 * 
 * 
 *  * A [NoMatch] result indicates a cache hit (the cached value is still valid).
 *  * A match result indicates a cache miss (the cached value is invalidated by changes).
 *  * Instances of this class cache match results and should be scoped to a specific client
 * (e.g., a build) for correctness.
 * 
 * 
 * 
 * This is driven by the [.matches] method, taking the following parameters.
 * 
 * 
 *  * **`validityHorizon`**: Represents the last known version where a cached value's
 * dependencies were valid (see [VersionedChanges] for a detailed definition).
 *  * **[FileSystemDependencies]**: Represents files and directory listings that a
 * cached value depends on.
 * 
 * 
 * 
 * **Caching and `validityHorizon`:**
 * 
 * 
 * The `validityHorizon` parameter of the [.matches] method plays a crucial role in
 * caching behavior, even though different `validityHorizon` values can be used for the same
 * [FileSystemDependencies] instance.
 * 
 * 
 * **Scenario 1: Shared `FileSystemDependencies` nodes, different `validityHorizon`s:**
 * 
 * 
 *  * Two different cached values evaluated at different versions might share the same [       ] nodes if the underlying files haven't changed between those
 * versions.
 *  * In such cases, the `matches` method might be called with different `validityHorizon` values for the same [FileSystemDependencies] object.
 *  * The existence of a newer `validityHorizon` (and the fact that the nodes are shared)
 * implies that no relevant changes occurred between the older and newer versions.
 *  * This optimization relies on tracking specific file version numbers rather than just content
 * hashes.
 * 
 * 
 * 
 * **Scenario 2: Stale vs. Up-to-Date Cached Values:**
 * 
 * 
 *  * An old, stale cached value might have overlapping file dependencies with a newer,
 * up-to-date cached value.
 *  * Staleness implies a difference in their [FileSystemDependencies] nodes, which are
 * used as cache keys. This allows for distinct cache entries despite the overlap.
 *  * The `validityHorizon` is essential here to prevent the up-to-date value from being
 * incorrectly invalidated by older changes associated with the stale value. It effectively
 * filters out changes that occurred before the up-to-date value was computed.
 * 
 * 
 * 
 * In essence, `validityHorizon` ensures correctness when dealing with potentially
 * overlapping dependencies and allows for efficient caching by reusing results when possible.
 */
internal class VersionedChangesValidator(executor: java.util.concurrent.Executor?, changes: VersionedChanges?) {
    private val fileOpMatches: FileOpMatchMemoizingLookup
    private val nestedMatches: NestedMatchMemoizingLookup

    init {
        this.fileOpMatches =
            FileOpMatchMemoizingLookup(
                executor,
                changes,
                ConcurrentHashMap<FileOpDependency?, FileOpMatchResultOrFuture?>()
            )
        this.nestedMatches =
            NestedMatchMemoizingLookup(
                executor,
                fileOpMatches,
                ConcurrentHashMap<NestedDependencies?, NestedMatchResultOrFuture?>()
            )
    }

    /** Changes in the cache reader used for invalidation.  */
    fun changes(): VersionedChanges? {
        return fileOpMatches.changes()
    }

    /**
     * Determines if there are any matching dependencies in [.changes].
     * 
     * 
     * The caller must ensure that the matching conditions required by [VersionedChanges] are
     * satisfied before calling this method. This may require performing lookups and calling [ ][VersionedChanges.registerFileChange] when earlier `validityHorizon` values are
     * discovered.
     * 
     * @param validityHorizon the latest version where `dependency` is known to be valid
     */
    fun matches(dependency: FileOpDependency?, validityHorizon: Int): FileOpMatchResultOrFuture? {
        return fileOpMatches.getValueOrFuture(dependency, validityHorizon)
    }

    /**
     * Determines if there are any matching dependencies in [.changes].
     * 
     * 
     * The caller must ensure that the matching conditions required by [VersionedChanges] are
     * satisfied before calling this method.
     * 
     * @param validityHorizon the latest version where `dependencies` is known to be valid
     */
    fun matches(dependencies: NestedDependencies?, validityHorizon: Int): NestedMatchResultOrFuture? {
        return nestedMatches.getValueOrFuture(dependencies, validityHorizon)
    }
}
