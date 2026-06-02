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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories

/**
 * This value represents the result of looking up all the packages under a given package path root,
 * starting at a given directory.
 */
@Immutable
@ThreadSafe
class RecursivePkgValue private constructor(packages: NestedSet<String?>?, hasErrors: Boolean) : SkyValue {
    private val packages: NestedSet<String?>?
    private val hasErrors: Boolean

    init {
        this.packages = packages
        this.hasErrors = hasErrors
    }

    fun getPackages(): NestedSet<String?>? {
        return packages
    }

    fun hasErrors(): Boolean {
        return hasErrors
    }

    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    @AutoCodec
    internal class Key private constructor(
        repositoryName: RepositoryName?,
        rootedPath: RootedPath?,
        excludedPaths: IgnoredSubdirectories?
    ) : RecursivePkgSkyKey(repositoryName, rootedPath, excludedPaths) {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.RECURSIVE_PKG
        }

        val skyKeyInterner: SkyKeyInterner<Key?>
            get() = com.google.devtools.build.lib.skyframe.RecursivePkgValue.Key.Companion.interner

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            private fun create(
                repositoryName: RepositoryName?, rootedPath: RootedPath?, excludedPaths: IgnoredSubdirectories?
            ): Key {
                return com.google.devtools.build.lib.skyframe.RecursivePkgValue.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.skyframe.RecursivePkgValue.Key(
                        repositoryName,
                        rootedPath,
                        excludedPaths
                    )
                )
            }

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Interner
            fun intern(key: Key?): Key {
                return com.google.devtools.build.lib.skyframe.RecursivePkgValue.Key.Companion.interner.intern(key)
            }
        }
    }

    companion object {
        @SerializationConstant
        val EMPTY: RecursivePkgValue =
            RecursivePkgValue(NestedSetBuilder.< String > emptySet < kotlin . String ? > (Order.STABLE_ORDER), false)

        fun create(packages: NestedSetBuilder<String?>, hasErrors: Boolean): RecursivePkgValue? {
            if (packages.isEmpty() && !hasErrors) {
                return EMPTY
            }
            return RecursivePkgValue(packages.build(), hasErrors)
        }

        /** Create a transitive package lookup request.  */
        @ThreadSafe
        fun key(
            repositoryName: RepositoryName?, rootedPath: RootedPath?, excludedPaths: IgnoredSubdirectories?
        ): Key {
            return com.google.devtools.build.lib.skyframe.RecursivePkgValue.Key.Companion.create(
                repositoryName,
                rootedPath,
                excludedPaths
            )
        }
    }
}
