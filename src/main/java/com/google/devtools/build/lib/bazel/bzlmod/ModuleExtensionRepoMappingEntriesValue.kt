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

import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionRepoMappingEntriesValue
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey
import com.google.devtools.build.lib.cmdline.RepositoryName
import com.google.devtools.build.lib.skyframe.SkyFunctions
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec
import com.google.devtools.build.skyframe.AbstractSkyKey
import com.google.devtools.build.skyframe.SkyFunctionName
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyKey.SkyKeyInterner
import com.google.devtools.build.skyframe.SkyValue

/** The value for [ModuleExtensionRepoMappingEntriesFunction].  */
@AutoCodec
class ModuleExtensionRepoMappingEntriesValue(
    entries: com.google.common.collect.ImmutableMap<String?, RepositoryName?>?,
    moduleKey: ModuleKey?
) : SkyValue {
    /**
     * The [com.google.devtools.build.skyframe.SkyKey] of a [ ].
     */
    @AutoCodec
    class Key protected constructor(arg: ModuleExtensionId?) : AbstractSkyKey<ModuleExtensionId?>(arg) {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.MODULE_EXTENSION_REPO_MAPPING_ENTRIES
        }

        override fun getSkyKeyInterner(): SkyKeyInterner<Key?> {
            return com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionRepoMappingEntriesValue.Key.Companion.interner
        }

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            private fun create(arg: ModuleExtensionId?): Key? {
                return com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionRepoMappingEntriesValue.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionRepoMappingEntriesValue.Key(arg)
                )
            }

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Interner
            fun intern(key: Key?): Key? {
                return com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionRepoMappingEntriesValue.Key.Companion.interner.intern(
                    key
                )
            }
        }
    }

    val entries: com.google.common.collect.ImmutableMap<String?, RepositoryName?>?
    val moduleKey: ModuleKey?

    init {
        this.moduleKey = moduleKey
        this.entries = entries
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableMap<String?, RepositoryName?>?>(
            entries,
            "entries"
        )
        ModuleKey > java.util.Objects.requireNonNull<ModuleKey?>(moduleKey, "moduleKey")
    }

    companion object {
        @AutoCodec.Instantiator
        fun create(
            entries: com.google.common.collect.ImmutableMap<String?, RepositoryName?>?, moduleKey: ModuleKey?
        ): ModuleExtensionRepoMappingEntriesValue {
            return ModuleExtensionRepoMappingEntriesValue(entries, moduleKey)
        }

        fun key(id: ModuleExtensionId?): Key? {
            return com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionRepoMappingEntriesValue.Key.Companion.create(
                id
            )
        }
    }
}
