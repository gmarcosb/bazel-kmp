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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.skyframe.SkyFunctions
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec
import com.google.devtools.build.skyframe.SkyFunctionName
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyKey.SkyKeyInterner

/** The key for [RegistryFunction].  */
@AutoCodec
@kotlin.jvm.JvmRecord
internal data class RegistryKey(val url: String?) : SkyKey {
    override fun functionName(): SkyFunctionName {
        return SkyFunctions.REGISTRY
    }

    override fun getSkyKeyInterner(): SkyKeyInterner<RegistryKey?> {
        return com.google.devtools.build.lib.bazel.bzlmod.RegistryKey.Companion.interner
    }

    init {
        java.util.Objects.requireNonNull<String?>(url, "url")
    }

    companion object {
        private val interner: SkyKeyInterner<RegistryKey?> = SkyKey.newInterner<RegistryKey?>()

        @AutoCodec.Instantiator
        fun create(url: String?): RegistryKey? {
            return com.google.devtools.build.lib.bazel.bzlmod.RegistryKey.Companion.interner.intern(
                com.google.devtools.build.lib.bazel.bzlmod.RegistryKey(
                    url
                )
            )
        }
    }
}
