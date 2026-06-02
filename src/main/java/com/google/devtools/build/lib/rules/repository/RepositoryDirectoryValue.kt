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
package com.google.devtools.build.lib.rules.repository

import com.google.devtools.build.lib.cmdline.RepositoryName

/**
 * The result of fetching a repo.
 * 
 * 
 * Note that we explicitly disable change pruning here by extending [ ]. The reason is that, after fetching a repo successfully, the resultant
 * [Success] object does not capture the newly fetched contents of the repo (note that it only
 * contains the path and some other minor metadata), which means that with change pruning, dependent
 * SkyValues would simply think the repo hasn't changed and not get re-evaluated. Without change
 * pruning, we force dependent SkyValues to be marked dirty whenever a repo is re-fetched.
 */
interface RepositoryDirectoryValue : NotComparableSkyValue {
    /**
     * Represents a successful repository lookup.
     * 
     * @param root Returns the root containing the repository's contents. This directory is guaranteed
     * to exist.
     * @param excludeFromVendoring Returns if this repo should be excluded from vendoring. The value
     * is true for local as well as configure repos.
     */
    @AutoCodec
    class Success(root: Root?, excludeFromVendoring: Boolean) : RepositoryDirectoryValue {
        val root: Root?
        val excludeFromVendoring: Boolean

        init {
            this.root = root
            this.excludeFromVendoring = excludeFromVendoring
        }
    }

    /**
     * Represents an unsuccessful repository lookup, because the repo doesn't exist.
     * 
     * @param errorMsg For an unsuccessful repository lookup, gets a detailed error message that is
     * suitable for reporting to a user.
     */
    @AutoCodec
    @kotlin.jvm.JvmRecord
    data class Failure(@kotlin.jvm.JvmField val errorMsg: String?) : RepositoryDirectoryValue

    /** The SkyKey for retrieving the local directory of an external repository.  */
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    @AutoCodec
    class Key private constructor(arg: RepositoryName?) : AbstractSkyKey<RepositoryName?>(arg) {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.REPOSITORY_DIRECTORY
        }

        val skyKeyInterner: SkyKeyInterner<Key?>
            get() = com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue.Key.Companion.interner

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            private fun create(arg: RepositoryName?): Key {
                return com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue.Key(arg)
                )
            }

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Interner
            fun intern(key: Key?): Key {
                return com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue.Key.Companion.interner.intern(
                    key
                )
            }
        }
    }

    companion object {
        /** Creates a key from the given repository name.  */
        fun key(repository: RepositoryName?): Key {
            return com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue.Key.Companion.create(
                repository
            )
        }

        @kotlin.jvm.JvmField
        val FETCH_DISABLED: Precomputed<Boolean?> = Precomputed<Boolean?>("fetch_disabled")
        const val FORCE_FETCH_DISABLED: String = ""
        @kotlin.jvm.JvmField
        val FORCE_FETCH: Precomputed<String?> = Precomputed<String?>("dependency_for_force_fetching_repository")
        @kotlin.jvm.JvmField
        val FORCE_FETCH_CONFIGURE: Precomputed<String?> =
            Precomputed<String?>("dependency_for_force_fetching_configure_repositories")
        @kotlin.jvm.JvmField
        val IS_VENDOR_COMMAND: Precomputed<Boolean?> = Precomputed<Boolean?>("is_vendor_command")
        @kotlin.jvm.JvmField
        val VENDOR_DIRECTORY: Precomputed<java.util.Optional<com.google.devtools.build.lib.vfs.Path?>?> =
            Precomputed<java.util.Optional<com.google.devtools.build.lib.vfs.Path?>?>("vendor_directory")
    }
}
