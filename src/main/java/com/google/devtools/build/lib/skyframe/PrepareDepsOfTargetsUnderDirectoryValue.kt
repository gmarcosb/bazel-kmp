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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories

/**
 * Dummy value that is the result of [PrepareDepsOfTargetsUnderDirectoryFunction].
 * 
 * 
 * Note that even though the [PrepareDepsOfTargetsUnderDirectoryFunction] is evaluated
 * entirely because of its side effects (i.e. loading transitive dependencies of targets), this
 * value interacts safely with change pruning, despite the fact that this value is a singleton. When
 * the targets in a package change, the [PackageValue] that
 * [PrepareDepsOfTargetsUnderDirectoryFunction] depends on will be invalidated, and the
 * PrepareDeps function for that package's directory will be re-evaluated, loading any new
 * transitive dependencies. Change pruning may prevent the re-evaluation of PrepareDeps for
 * directories above that one, but they don't need to be re-run.
 */
object PrepareDepsOfTargetsUnderDirectoryValue : SkyValue {
    @SerializationConstant
    val INSTANCE: PrepareDepsOfTargetsUnderDirectoryValue = PrepareDepsOfTargetsUnderDirectoryValue()

    /** Create a prepare deps of targets under directory request.  */
    @ThreadSafe
    fun key(
        repository: RepositoryName?, rootedPath: RootedPath, excludedPaths: IgnoredSubdirectories
    ): SkyKey {
        return key(repository, rootedPath, excludedPaths, FilteringPolicies.NO_FILTER)
    }

    /**
     * Create a prepare deps of targets under directory request, specifying a filtering policy for
     * targets.
     */
    @ThreadSafe
    fun key(
        repository: RepositoryName?,
        rootedPath: RootedPath,
        excludedPaths: IgnoredSubdirectories,
        filteringPolicy: FilteringPolicy?
    ): PrepareDepsOfTargetsUnderDirectoryKey {
        return PrepareDepsOfTargetsUnderDirectoryKey.Companion.create(
            RecursivePkgKey(repository, rootedPath, excludedPaths), filteringPolicy
        )
    }

    /**
     * The argument value for [SkyKey]s of [PrepareDepsOfTargetsUnderDirectoryFunction].
     */
    @AutoCodec
    class PrepareDepsOfTargetsUnderDirectoryKey private constructor(
        recursivePkgKey: RecursivePkgKey?,
        filteringPolicy: FilteringPolicy?
    ) : SkyKey {
        private val recursivePkgKey: RecursivePkgKey
        private val filteringPolicy: FilteringPolicy

        init {
            this.recursivePkgKey = com.google.common.base.Preconditions.checkNotNull<RecursivePkgKey>(recursivePkgKey)
            this.filteringPolicy = com.google.common.base.Preconditions.checkNotNull<FilteringPolicy>(filteringPolicy)
        }

        fun getRecursivePkgKey(): RecursivePkgKey {
            return recursivePkgKey
        }

        fun getFilteringPolicy(): FilteringPolicy {
            return filteringPolicy
        }

        override fun functionName(): SkyFunctionName {
            return SkyFunctions.PREPARE_DEPS_OF_TARGETS_UNDER_DIRECTORY
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is PrepareDepsOfTargetsUnderDirectoryKey) {
                return false
            }

            return recursivePkgKey == o.recursivePkgKey
                    && filteringPolicy == o.filteringPolicy
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(recursivePkgKey, filteringPolicy)
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(PrepareDepsOfTargetsUnderDirectoryKey::class.java)
                .add("pkg-key", recursivePkgKey)
                .add("filtering policy", filteringPolicy)
                .toString()
        }

        val skyKeyInterner: SkyKeyInterner<PrepareDepsOfTargetsUnderDirectoryKey?>
            get() = interner

        companion object {
            private val interner: SkyKeyInterner<PrepareDepsOfTargetsUnderDirectoryKey?> =
                SkyKey.newInterner<PrepareDepsOfTargetsUnderDirectoryKey?>()

            fun create(
                recursivePkgKey: RecursivePkgKey?, filteringPolicy: FilteringPolicy?
            ): PrepareDepsOfTargetsUnderDirectoryKey {
                return interner.intern(
                    PrepareDepsOfTargetsUnderDirectoryKey(recursivePkgKey, filteringPolicy)
                )
            }

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Interner
            fun intern(key: PrepareDepsOfTargetsUnderDirectoryKey?): PrepareDepsOfTargetsUnderDirectoryKey {
                return interner.intern(key)
            }
        }
    }
}
