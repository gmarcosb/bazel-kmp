// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.bazel.bzlmod.YankedVersionsValue
import com.google.devtools.build.lib.skyframe.SkyFunctions
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec
import com.google.devtools.build.skyframe.SkyFunctionName
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyKey.SkyKeyInterner
import com.google.devtools.build.skyframe.SkyValue

/** A class holding information about the versions of a particular module that have been yanked.  */
@AutoCodec
class YankedVersionsValue(yankedVersions: java.util.Optional<com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.bazel.bzlmod.Version?, String?>?>?) :
    SkyValue {
    /** The key for [YankedVersionsFunction].  */
    @AutoCodec
    @kotlin.jvm.JvmRecord
    internal data class Key(val moduleName: String?, val registryUrl: String?) : SkyKey {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.YANKED_VERSIONS
        }

        override fun getSkyKeyInterner(): SkyKeyInterner<Key?> {
            return com.google.devtools.build.lib.bazel.bzlmod.YankedVersionsValue.Key.Companion.interner
        }

        init {
            java.util.Objects.requireNonNull<String?>(moduleName, "moduleName")
            java.util.Objects.requireNonNull<String?>(registryUrl, "registryUrl")
        }

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            @AutoCodec.Instantiator
            fun create(moduleName: String?, registryUrl: String?): Key? {
                return com.google.devtools.build.lib.bazel.bzlmod.YankedVersionsValue.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.bazel.bzlmod.YankedVersionsValue.Key(
                        moduleName,
                        registryUrl
                    )
                )
            }
        }
    }

    val yankedVersions: java.util.Optional<com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.bazel.bzlmod.Version?, String?>?>?

    init {
        this.yankedVersions = yankedVersions
        java.util.Objects.requireNonNull<java.util.Optional<com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.bazel.bzlmod.Version?, String?>?>?>(
            yankedVersions,
            "yankedVersions"
        )
    }

    companion object {
        /** A value representing a module without yanked versions.  */
        val NONE_YANKED: YankedVersionsValue = create(
            java.util.Optional.of<com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.bazel.bzlmod.Version?, String?>?>(
                com.google.common.collect.ImmutableMap.of<com.google.devtools.build.lib.bazel.bzlmod.Version?, String?>()
            )
        )

        fun create(yankedVersions: java.util.Optional<com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.bazel.bzlmod.Version?, String?>?>?): YankedVersionsValue {
            return YankedVersionsValue(yankedVersions)
        }
    }
}
