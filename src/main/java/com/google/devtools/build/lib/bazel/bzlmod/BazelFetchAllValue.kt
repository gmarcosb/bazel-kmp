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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.bazel.bzlmod.BazelFetchAllValue
import com.google.devtools.build.lib.cmdline.RepositoryName
import com.google.devtools.build.lib.skyframe.SkyFunctions
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec
import com.google.devtools.build.skyframe.AbstractSkyKey
import com.google.devtools.build.skyframe.SkyFunctionName
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyKey.SkyKeyInterner
import com.google.devtools.build.skyframe.SkyValue

/**
 * Empty result of running Bazel fetch all dependencies, to indicate that all repos have been
 * fetched successfully.
 */
class BazelFetchAllValue(reposToVendor: com.google.common.collect.ImmutableList<RepositoryName?>?) : SkyValue {
    /** Key type for BazelFetchAllValue.  */
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    @AutoCodec
    class Key private constructor(arg: Boolean?) : AbstractSkyKey<Boolean?>(arg) {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.BAZEL_FETCH_ALL
        }

        override fun getSkyKeyInterner(): SkyKeyInterner<Key?> {
            return com.google.devtools.build.lib.bazel.bzlmod.BazelFetchAllValue.Key.Companion.interner
        }

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            private fun create(arg: Boolean?): Key? {
                return com.google.devtools.build.lib.bazel.bzlmod.BazelFetchAllValue.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.bazel.bzlmod.BazelFetchAllValue.Key(
                        arg
                    )
                )
            }

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Interner
            fun intern(key: Key?): Key? {
                return com.google.devtools.build.lib.bazel.bzlmod.BazelFetchAllValue.Key.Companion.interner.intern(key)
            }
        }
    }

    val reposToVendor: com.google.common.collect.ImmutableList<RepositoryName?>?

    init {
        this.reposToVendor = reposToVendor
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableList<RepositoryName?>?>(
            reposToVendor,
            "reposToVendor"
        )
    }

    companion object {
        /** Creates a key from the given repository name.  */
        fun key(configureEnabled: Boolean?): Key? {
            return com.google.devtools.build.lib.bazel.bzlmod.BazelFetchAllValue.Key.Companion.create(configureEnabled)
        }

        fun create(reposToVendor: com.google.common.collect.ImmutableList<RepositoryName?>?): BazelFetchAllValue {
            return BazelFetchAllValue(reposToVendor)
        }
    }
}
