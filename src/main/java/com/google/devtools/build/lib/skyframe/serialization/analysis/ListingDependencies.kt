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
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencies.AvailableFileDependencies
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencyDeserializer.ListingDependenciesOrFuture
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileSystemDependencies.FileOpDependency
import com.google.devtools.build.lib.skyframe.serialization.analysis.VersionedChanges

/** Type representing a directory listing operation.  */
internal abstract class ListingDependencies

    : FileOpDependency, ListingDependenciesOrFuture {
    internal class AvailableListingDependencies private constructor(realDirectory: AvailableFileDependencies) :
        ListingDependencies() {
        private val realDirectory: AvailableFileDependencies

        init {
            this.realDirectory = realDirectory
        }

        val isMissingData: Boolean
            get() = false

        /**
         * Determines if this listing is invalidated by anything in `changes`.
         * 
         * 
         * The caller should ensure the following.
         * 
         * 
         *  * This listing is known to be valid at `validityHorizon` (VH).
         *  * All changes over the range `(VH, VC])` are registered with `changes` before
         * calling this method. (VC is the synced version of the cache reader.)
         * 
         * 
         * 
         * See description of [VersionedChanges] for more details.
         * 
         * @return the earliest version where a matching (invalidating) change is identified, otherwise
         * [VersionedChanges.NO_MATCH].
         */
        fun findEarliestMatch(changes: VersionedChanges, validityHorizon: Int): Int {
            return changes.matchListingChange(realDirectory.resolvedPath(), validityHorizon)
        }

        fun realDirectory(): AvailableFileDependencies {
            return realDirectory
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this).add("realDirectory", realDirectory)
                .toString()
        }
    }

    /**
     * Signals missing listing data.
     * 
     * 
     * This is deliberately not a singleton to avoid a memory leak in the weak-value caches in
     * [FileDependencyDeserializer].
     */
    internal class MissingListingDependencies private constructor() : ListingDependencies() {
        val isMissingData: Boolean
            get() = true
    }

    companion object {
        fun from(realDirectory: FileDependencies): ListingDependencies {
            return when (realDirectory) {
                -> AvailableListingDependencies(availableRealDirectory)
                -> newMissingInstance()
            }
        }

        @kotlin.jvm.JvmStatic
        fun newMissingInstance(): ListingDependencies {
            return MissingListingDependencies()
        }
    }
}
