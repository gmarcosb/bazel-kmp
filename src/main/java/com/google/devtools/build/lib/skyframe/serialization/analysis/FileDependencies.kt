// Copyright 2024 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencies
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencyDeserializer.FileDependenciesOrFuture
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileSystemDependencies.FileOpDependency
import com.google.devtools.build.lib.skyframe.serialization.analysis.VersionedChanges

/**
 * Representation of a set of file names that could invalidate a given value.
 * 
 * 
 * Most values can be associated with some set of input files, represented in this nested way to
 * facilitate sharing between values. So given a set of changed files, invalidation is performed by
 * calling [.findEarliestMatch] on an instance and all transitively reachable instances via
 * [.getDependencyCount] and [.getDependency]. If any matches are encountered, the
 * associated value is invalidated.
 */
internal abstract class FileDependencies

    : FileOpDependency, FileDependenciesOrFuture {
    /**
     * Finds the earliest version where any contained path matches a change in `changes`.
     * 
     * 
     * The caller must ensure the following.
     * 
     * 
     *  * All the paths within are known to be valid at `validityHorizon` (VH).
     *  * All changes over the range `(VH, VC]` are registered with `changes` before
     * calling this method. (VC is the synced version of the cache reader.)
     * 
     * 
     * 
     * See description of [VersionedChanges] for more details.
     * 
     * 
     * NOTE: this does not match anything from [.getDependency].
     * 
     * @return the earliest version where a matching (invalidating) change is identified, otherwise
     * [VersionedChanges.NO_MATCH].
     */
    abstract fun findEarliestMatch(changes: VersionedChanges?, validityHorizon: Int): Int

    // non-sealed for test fakes
    internal abstract class AvailableFileDependencies : FileDependencies() {
        @kotlin.jvm.JvmField
        abstract val dependencyCount: Int

        abstract fun getDependency(index: Int): AvailableFileDependencies?

        /**
         * The real path associated with this node after resolution.
         * 
         * 
         * This is used by [FileDependencyDeserializer] to retrieve resolved parent paths but
         * isn't directly used by invalidation.
         */
        abstract fun resolvedPath(): String?

        @get:com.google.common.annotations.VisibleForTesting
        abstract val allResolvedPathsForTesting: com.google.common.collect.ImmutableList<String?>?
    }

    /**
     * Signals missing data in the nested set of dependencies.
     * 
     * 
     * This is deliberately not a singleton to avoid a memory leak in the weak-value caches in
     * [FileDependencyDeserializer].
     */
    internal class MissingFileDependencies private constructor() : FileDependencies() {
        val isMissingData: Boolean
            get() = true

        override fun findEarliestMatch(changes: VersionedChanges?, validityHorizon: Int): Int {
            // Missing data means there's no way to prove that a cache value is valid. Returning
            // ALWAYS_MATCH signals a cache miss.
            return VersionedChanges.ALWAYS_MATCH
        }
    }

    internal class Builder private constructor(firstResolvedPath: String?) {
        private val paths: java.util.ArrayList<String?> = java.util.ArrayList<String?>()
        private val dependencies: java.util.ArrayList<AvailableFileDependencies?> =
            java.util.ArrayList<AvailableFileDependencies?>()

        /**
         * At least one resolved path is required.
         * 
         * 
         * The last path added is treated as the overall [ ][AvailableFileDependencies.resolvedPath] of the instance. The `firstResolvedPath`
         * argument is the [AvailableFileDependencies.resolvedPath] if it's the only path.
         */
        init {
            paths.add(firstResolvedPath)
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addPath(path: String?): Builder {
            paths.add(path)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addDependency(dependency: AvailableFileDependencies?): Builder {
            dependencies.add(dependency)
            return this
        }

        fun build(): FileDependencies {
            if (paths.size() == 1) {
                val dependenciesSize: Int = dependencies.size()
                if (dependenciesSize == 0) {
                    return SingleResolvedPath(paths.get(0))
                }
                if (dependenciesSize == 1) {
                    return SingleResolvedPathAndDependency(paths.get(0), dependencies.get(0))
                }
            }
            return MultiplePaths(
                com.google.common.collect.ImmutableList.copyOf<String?>(paths),
                com.google.common.collect.ImmutableList.copyOf<AvailableFileDependencies?>(dependencies)
            )
        }
    }

    // The implementations here exist to reduce indirection and memory use.
    private class SingleResolvedPath(private val resolvedPath: String) : AvailableFileDependencies() {
        val isMissingData: Boolean
            get() = false

        override fun findEarliestMatch(changes: VersionedChanges, validityHorizon: Int): Int {
            return changes.matchFileChange(resolvedPath, validityHorizon)
        }

        override fun getDependencyCount(): Int {
            return 0
        }

        override fun getDependency(index: Int): AvailableFileDependencies? {
            throw java.lang.IndexOutOfBoundsException(this.toString() + " " + index)
        }

        override fun resolvedPath(): String {
            return resolvedPath
        }

        override fun getAllResolvedPathsForTesting(): com.google.common.collect.ImmutableList<String?> {
            return com.google.common.collect.ImmutableList.of<String?>(resolvedPath)
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this).add("resolvedPath", resolvedPath).toString()
        }
    }

    private class SingleResolvedPathAndDependency(
        private val resolvedPath: String,
        private val dependency: AvailableFileDependencies?
    ) : AvailableFileDependencies() {
        val isMissingData: Boolean
            get() = false

        override fun findEarliestMatch(changes: VersionedChanges, validityHorizon: Int): Int {
            return changes.matchFileChange(resolvedPath, validityHorizon)
        }

        override fun getDependencyCount(): Int {
            return 1
        }

        override fun getDependency(index: Int): AvailableFileDependencies? {
            if (index != 0) {
                throw java.lang.IndexOutOfBoundsException(this.toString() + " " + index)
            }
            return dependency
        }

        override fun resolvedPath(): String {
            return resolvedPath
        }

        override fun getAllResolvedPathsForTesting(): com.google.common.collect.ImmutableList<String?> {
            return com.google.common.collect.ImmutableList.of<String?>(resolvedPath)
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("resolvedPath", resolvedPath)
                .add("dependency", dependency)
                .toString()
        }
    }

    private class MultiplePaths(
        resolvedPaths: com.google.common.collect.ImmutableList<String?>,
        dependencies: com.google.common.collect.ImmutableList<AvailableFileDependencies>
    ) : AvailableFileDependencies() {
        private val resolvedPaths: com.google.common.collect.ImmutableList<String?>
        private val dependencies: com.google.common.collect.ImmutableList<AvailableFileDependencies>

        init {
            this.resolvedPaths = resolvedPaths
            this.dependencies = dependencies
        }

        val isMissingData: Boolean
            get() = false

        override fun findEarliestMatch(changes: VersionedChanges, validityHorizon: Int): Int {
            var minMatch: Int = VersionedChanges.NO_MATCH
            for (element in resolvedPaths) {
                val result: Int = changes.matchFileChange(element, validityHorizon)
                if (result < minMatch) {
                    minMatch = result
                }
            }
            return minMatch
        }

        override fun getDependencyCount(): Int {
            return dependencies.size()
        }

        override fun getDependency(index: Int): AvailableFileDependencies {
            return dependencies.get(index)
        }

        override fun resolvedPath(): String? {
            return com.google.common.collect.Iterables.getLast<String?>(resolvedPaths)
        }

        override fun getAllResolvedPathsForTesting(): com.google.common.collect.ImmutableList<String?> {
            return resolvedPaths
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("resolvedPaths", resolvedPaths)
                .add("dependencies", dependencies)
                .toString()
        }
    }

    companion object {
        @kotlin.jvm.JvmStatic
        fun builder(firstResolvedPath: String?): Builder {
            return com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencies.Builder(
                firstResolvedPath
            )
        }

        @kotlin.jvm.JvmStatic
        fun newMissingInstance(): FileDependencies {
            return MissingFileDependencies()
        }
    }
}
