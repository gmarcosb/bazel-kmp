// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.PackageIdentifier

/** [SkyValue] corresponding to the computation result of the [GlobsFunction].  */
class GlobsValue(matches: com.google.common.collect.ImmutableSet<PathFragment?>) : SkyValue {
    // TODO: b/290998109 - Storing the matches seem unnecessary except for tests. Consider only
    // storing `matches` when testing.
    private val matches: com.google.common.collect.ImmutableSet<PathFragment?>

    init {
        this.matches = matches
    }

    fun getMatches(): com.google.common.collect.ImmutableSet<PathFragment?> {
        return matches
    }

    override fun equals(other: Any?): Boolean {
        if (other === this) {
            return true
        }
        if (other !is GlobsValue) {
            return false
        }

        return getMatches() == other.getMatches()
    }

    override fun hashCode(): Int {
        return matches.hashCode()
    }

    /**
     * Representation of individual glob inside a package, including its expression and Globber
     * operation type.
     */
    class GlobRequest private constructor(val pattern: String, globOperation: Globber.Operation) {
        private val globOperation: Globber.Operation

        fun getGlobOperation(): Operation {
            return globOperation
        }

        init {
            this.globOperation = globOperation
        }

        override fun toString(): String {
            return String.format("GlobRequest: %s %s", pattern, globOperation)
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj !is GlobRequest) {
                return false
            }

            return pattern == obj.pattern && globOperation.equals(obj.globOperation)
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(pattern, globOperation)
        }

        companion object {
            /**
             * Creates [GlobRequest] object iff pattern is a valid glob expression.
             * 
             * 
             * @throws InvalidGlobPatternException if the pattern is not valid.
             */
            @Throws(InvalidGlobPatternException::class)
            fun create(pattern: String, globOperation: Globber.Operation): GlobRequest {
                if (pattern.indexOf('?') != -1) {
                    throw InvalidGlobPatternException(pattern, "wildcard ? forbidden")
                }

                val error: String? = UnixGlob.checkPatternForError(pattern)
                if (error != null) {
                    throw InvalidGlobPatternException(pattern, error)
                }
                return GlobRequest(pattern, globOperation)
            }
        }
    }

    /**
     * [SkyKey] type for [GlobsValue], serving as the input to [GlobsFunction].
     * 
     * 
     * Expects all glob expressions inside [Key.globRequests] are valid, as indicated by
     * `UnixGlob#checkPatternForError`.
     */
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    @AutoCodec
    class Key private constructor(
        packageIdentifier: PackageIdentifier,
        packageRoot: Root,
        globRequests: com.google.common.collect.ImmutableSet<GlobRequest?>
    ) : SkyKey {
        private val packageIdentifier: PackageIdentifier
        private val packageRoot: Root
        private val globRequests: com.google.common.collect.ImmutableSet<GlobRequest?>

        init {
            this.packageIdentifier = packageIdentifier
            this.packageRoot = packageRoot
            this.globRequests = globRequests
        }

        /**
         * Returns the package that "owns" all globs.
         * 
         * 
         * The globs evaluation code ensures that the boundaries of this package are not crossed.
         */
        fun getPackageIdentifier(): PackageIdentifier {
            return packageIdentifier
        }

        /** Returns the package root of [.packageIdentifier].  */
        fun getPackageRoot(): Root {
            return packageRoot
        }

        /**
         * Returns an [ImmutableSet] containing all globs inside the package, including each glob
         * expression and operation.
         */
        fun getGlobRequests(): com.google.common.collect.ImmutableSet<GlobRequest?> {
            return globRequests
        }

        override fun skipsBatchPrefetch(): Boolean {
            return true
        }

        override fun functionName(): SkyFunctionName {
            return SkyFunctions.GLOBS
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj !is Key) {
                return false
            }
            return packageIdentifier.equals(obj.packageIdentifier)
                    && packageRoot == obj.packageRoot
                    && globRequests == obj.globRequests
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(packageIdentifier, packageRoot, globRequests)
        }

        override fun toString(): String {
            return String.format(
                "<GlobsKey packageRoot = %s, packageIdentifier = %s, globRequests = [%s]>",
                packageRoot,
                packageIdentifier,
                globRequests.stream().map<String?> { obj: GlobRequest? -> obj.toString() }.sorted()
                    .collect(Collectors.joining(","))
            )
        }

        val skyKeyInterner: SkyKeyInterner<Key?>
            get() = com.google.devtools.build.lib.skyframe.GlobsValue.Key.Companion.interner

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            private fun create(
                packageIdentifier: PackageIdentifier,
                packageRoot: Root,
                globRequests: com.google.common.collect.ImmutableSet<GlobRequest?>
            ): Key {
                return com.google.devtools.build.lib.skyframe.GlobsValue.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.skyframe.GlobsValue.Key(
                        packageIdentifier,
                        packageRoot,
                        globRequests
                    )
                )
            }

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Interner
            fun intern(key: Key?): Key {
                return com.google.devtools.build.lib.skyframe.GlobsValue.Key.Companion.interner.intern(key)
            }
        }
    }

    companion object {
        /**
         * Returns the interned [GlobsValue.Key] object which contains all glob deps of a package.
         * 
         * @param packageIdentifier packageId the name of the owner package (must be an existing package)
         * @param packageRoot the package root of `packageId`
         * @param globRequests container of all glob expressions and types of Globber operations, all
         * input glob expressions are expected to be valid.
         */
        fun key(
            packageIdentifier: PackageIdentifier,
            packageRoot: Root,
            globRequests: com.google.common.collect.ImmutableSet<GlobRequest?>
        ): Key {
            return com.google.devtools.build.lib.skyframe.GlobsValue.Key.Companion.create(
                packageIdentifier,
                packageRoot,
                globRequests
            )
        }
    }
}
