// Copyright 2016 The Bazel Authors. All rights reserved.
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
 * The value computed by [CollectPackagesUnderDirectoryFunction]. Contains a mapping for all
 * its non-excluded directories to whether there are packages or error messages beneath them.
 * 
 * 
 * This value is used by [ ][com.google.devtools.build.lib.pkgcache.RecursivePackageProvider.streamPackagesUnderDirectory] to
 * help it traverse the graph and find the set of packages under a directory, recursively by [ ] which computes a value for a directory by aggregating
 * results calculated from its subdirectories, and by [ ] which uses this value to find transitive targets to
 * load.
 * 
 * 
 * Note that even though the [CollectPackagesUnderDirectoryFunction] is evaluated in part
 * because of its side-effects (i.e. loading transitive dependencies of targets), this value
 * interacts safely with change pruning, despite the fact that this value is a lossy representation
 * of the packages beneath a directory (i.e. it doesn't care **which** packages are under a
 * directory, just whether there are any). When the targets in a package change, the [ ] that [CollectPackagesUnderDirectoryFunction] depends on will be invalidated,
 * and the PrepareDeps function for that package's directory will be reevaluated, loading any new
 * transitive dependencies. Change pruning may prevent the reevaluation of PrepareDeps for
 * directories above that one, but they don't need to be re-run.
 */
abstract class CollectPackagesUnderDirectoryValue internal constructor(subdirectoryTransitivelyContainsPackagesOrErrors: com.google.common.collect.ImmutableList<RootedPath?>?) :
    SkyValue {
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    protected val subdirectoryTransitivelyContainsPackagesOrErrors: com.google.common.collect.ImmutableList<RootedPath?>

    init {
        this.subdirectoryTransitivelyContainsPackagesOrErrors =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<RootedPath?>>(
                subdirectoryTransitivelyContainsPackagesOrErrors
            )
    }

    /** Represents a successfully loaded package or a directory without a BUILD file.  */
    class NoErrorCollectPackagesUnderDirectoryValue
    private constructor(
        private val isDirectoryPackage: Boolean,
        subdirectoryTransitivelyContainsPackagesOrErrors: com.google.common.collect.ImmutableList<RootedPath?>?
    ) : CollectPackagesUnderDirectoryValue(subdirectoryTransitivelyContainsPackagesOrErrors) {
        override fun isDirectoryPackage(): Boolean {
            return isDirectoryPackage
        }

        override fun getErrorMessage(): String? {
            return null
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(
                isDirectoryPackage, getSubdirectoryTransitivelyContainsPackagesOrErrors()
            )
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is NoErrorCollectPackagesUnderDirectoryValue) {
                return false
            }
            return this.isDirectoryPackage == o.isDirectoryPackage
                    && this.getSubdirectoryTransitivelyContainsPackagesOrErrors() == o.getSubdirectoryTransitivelyContainsPackagesOrErrors()
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("isDirectoryPackage", isDirectoryPackage)
                .add(
                    "subdirectoryTransitivelyContainsPackagesOrErrors",
                    getSubdirectoryTransitivelyContainsPackagesOrErrors()
                )
                .toString()
        }

        companion object {
            @kotlin.jvm.JvmField
            @SerializationConstant
            val EMPTY: NoErrorCollectPackagesUnderDirectoryValue = NoErrorCollectPackagesUnderDirectoryValue(
                false,
                com.google.common.collect.ImmutableList.of<RootedPath?>()
            )
        }
    }

    /** Represents a directory with a BUILD file that failed to load.  */
    private class ErrorCollectPackagesUnderDirectoryValue
        (
        errorMessage: String?,
        subdirectoryTransitivelyContainsPackagesOrErrors: com.google.common.collect.ImmutableList<RootedPath?>?
    ) : CollectPackagesUnderDirectoryValue(subdirectoryTransitivelyContainsPackagesOrErrors) {
        private val errorMessage: String

        init {
            this.errorMessage = com.google.common.base.Preconditions.checkNotNull<String>(errorMessage)
        }

        override fun isDirectoryPackage(): Boolean {
            return false
        }

        override fun getErrorMessage(): String {
            return errorMessage
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(errorMessage, getSubdirectoryTransitivelyContainsPackagesOrErrors())
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is ErrorCollectPackagesUnderDirectoryValue) {
                return false
            }
            return this.errorMessage == o.errorMessage
                    && this.getSubdirectoryTransitivelyContainsPackagesOrErrors() == o.getSubdirectoryTransitivelyContainsPackagesOrErrors()
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("errorMessage", errorMessage)
                .add(
                    "subdirectoryTransitivelyContainsPackagesOrErrors",
                    getSubdirectoryTransitivelyContainsPackagesOrErrors()
                )
                .toString()
        }
    }

    /**
     * Returns whether there is a BUILD file in this directory that can be loaded as a package. If
     * this returns `true`, then [.getErrorMessage] returns `null`.
     */
    @kotlin.jvm.JvmField
    abstract val isDirectoryPackage: Boolean

    /**
     * Returns an error describing why the BUILD file in this directory cannot be loaded as a package,
     * if there is one and it can't be. Otherwise returns `null`. If this returns non-`null`, then [.isDirectoryPackage] returns `false`.
     */
    @kotlin.jvm.JvmField
    abstract val errorMessage: String?

    /**
     * Returns an [ImmutableList] describing the RootedPath of each immediate subdirectory of
     * this directory that contains any packages, or BUILD files that couldn't be loaded, in or
     * beneath that subdirectory.
     */
    fun getSubdirectoryTransitivelyContainsPackagesOrErrors(): com.google.common.collect.ImmutableList<RootedPath?> {
        return subdirectoryTransitivelyContainsPackagesOrErrors
    }


    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    @AutoCodec
    internal class Key private constructor(
        repositoryName: RepositoryName?,
        rootedPath: RootedPath?,
        excludedPaths: IgnoredSubdirectories?
    ) : RecursivePkgSkyKey(repositoryName, rootedPath, excludedPaths) {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.COLLECT_PACKAGES_UNDER_DIRECTORY
        }

        val skyKeyInterner: SkyKeyInterner<Key?>
            get() = com.google.devtools.build.lib.skyframe.CollectPackagesUnderDirectoryValue.Key.Companion.interner

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            fun create(
                repositoryName: RepositoryName?, rootedPath: RootedPath?, excludedPaths: IgnoredSubdirectories?
            ): Key {
                return com.google.devtools.build.lib.skyframe.CollectPackagesUnderDirectoryValue.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.skyframe.CollectPackagesUnderDirectoryValue.Key(
                        repositoryName,
                        rootedPath,
                        excludedPaths
                    )
                )
            }

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Interner
            fun intern(key: Key?): Key {
                return com.google.devtools.build.lib.skyframe.CollectPackagesUnderDirectoryValue.Key.Companion.interner.intern(
                    key
                )
            }
        }
    }

    companion object {
        /**
         * Constructs a [CollectPackagesUnderDirectoryValue] for a directory with a BUILD file that
         * failed to load as a package.
         */
        fun ofError(
            errorMessage: String?,
            subdirectoryTransitivelyContainsPackagesOrErrors: com.google.common.collect.ImmutableList<RootedPath?>?
        ): CollectPackagesUnderDirectoryValue {
            com.google.common.base.Preconditions.checkNotNull<String?>(errorMessage, "errorMessage")
            return ErrorCollectPackagesUnderDirectoryValue(
                errorMessage, subdirectoryTransitivelyContainsPackagesOrErrors
            )
        }

        /**
         * Constructs a [CollectPackagesUnderDirectoryValue] for a directory without a BUILD file or
         * that has a BUILD file that successfully loads as a package.
         */
        fun ofNoError(
            isDirectoryPackage: Boolean,
            subdirectoryTransitivelyContainsPackagesOrErrors: com.google.common.collect.ImmutableList<RootedPath?>
        ): CollectPackagesUnderDirectoryValue {
            if (!isDirectoryPackage && subdirectoryTransitivelyContainsPackagesOrErrors.isEmpty()) {
                return NoErrorCollectPackagesUnderDirectoryValue.Companion.EMPTY
            }
            return NoErrorCollectPackagesUnderDirectoryValue(
                isDirectoryPackage, subdirectoryTransitivelyContainsPackagesOrErrors
            )
        }

        /** Create a collect packages under directory request.  */
        @ThreadSafe
        fun key(
            repository: RepositoryName?, rootedPath: RootedPath?, excludedPaths: IgnoredSubdirectories?
        ): SkyKey {
            return com.google.devtools.build.lib.skyframe.CollectPackagesUnderDirectoryValue.Key.Companion.create(
                repository,
                rootedPath,
                excludedPaths
            )
        }
    }
}
