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

import com.google.auto.value.AutoValue
import com.google.devtools.build.lib.bazel.bzlmod.AbridgedModule
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey
import com.google.devtools.build.lib.bazel.bzlmod.SingleExtensionUsagesValue
import com.google.devtools.build.lib.cmdline.RepositoryName
import com.google.devtools.build.lib.skyframe.SkyFunctions
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec
import com.google.devtools.build.skyframe.AbstractSkyKey
import com.google.devtools.build.skyframe.SkyFunctionName
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyKey.SkyKeyInterner
import com.google.devtools.build.skyframe.SkyValue
import com.google.gson.Gson
import com.ryanharter.auto.value.gson.GenerateTypeAdapter

/**
 * The result of [SingleExtensionUsagesFunction].
 * 
 * 
 * When adding or exposing new fields to extensions, make sure to update [ ][.trimForEvaluation] as well.
 */
@AutoValue
@GenerateTypeAdapter
abstract class SingleExtensionUsagesValue : SkyValue {
    /** All usages of this extension, by the key of the module where the usage occurs.  */ // Note: Equality of SingleExtensionUsagesValue does not check for equality of the order of the
    // entries of this map, but it is tracked implicitly via the order of the abridged modules.
    abstract fun getExtensionUsages(): com.google.common.collect.ImmutableMap<ModuleKey?, ModuleExtensionUsage?>?

    /**
     * The "unique name" (see [BazelDepGraphValue.getExtensionUniqueNames]) of this extension.
     */
    abstract fun getExtensionUniqueName(): String?

    /** All [AbridgedModule]s in the dependency graph that used this extension.  */
    abstract fun getAbridgedModules(): com.google.common.collect.ImmutableList<AbridgedModule?>?

    /** The repo mappings to use for each module that used this extension.  */
    abstract fun getRepoMappings(): com.google.common.collect.ImmutableMap<ModuleKey?, com.google.devtools.build.lib.cmdline.RepositoryMapping?>?

    /** Maps an extension-local repo name to the canonical name of the repo it is overridden with.  */
    abstract fun getRepoOverrides(): com.google.common.collect.ImmutableMap<String?, RepositoryName?>?

    /**
     * Returns a new value with only the information that influences the evaluation of the extension
     * and isn't tracked elsewhere.
     */
    fun trimForEvaluation(): SingleExtensionUsagesValue {
        return create(
            com.google.common.collect.ImmutableMap.copyOf<ModuleKey?, ModuleExtensionUsage?>(
                com.google.common.collect.Maps.transformValues<ModuleKey?, ModuleExtensionUsage?, ModuleExtensionUsage?>(
                    getExtensionUsages(),
                    com.google.common.base.Function { obj: ModuleExtensionUsage? -> obj.trimForEvaluation() })
            ),
            getExtensionUniqueName(),
            getAbridgedModules(),  // repoMappings: The usage of repo mappings by the extension's implementation function is
            // tracked on the level of individual entries and all label attributes are provided as
            // `Label`, which exclusively reference canonical repository names.
            com.google.common.collect.ImmutableMap.of<ModuleKey?, com.google.devtools.build.lib.cmdline.RepositoryMapping?>(),
            getRepoOverrides()
        )
    }

    @AutoCodec
    internal class Key protected constructor(arg: ModuleExtensionId?) : AbstractSkyKey<ModuleExtensionId?>(arg) {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.SINGLE_EXTENSION_USAGES
        }

        override fun getSkyKeyInterner(): SkyKeyInterner<Key?> {
            return com.google.devtools.build.lib.bazel.bzlmod.SingleExtensionUsagesValue.Key.Companion.interner
        }

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            private fun create(arg: ModuleExtensionId?): Key? {
                return com.google.devtools.build.lib.bazel.bzlmod.SingleExtensionUsagesValue.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.bazel.bzlmod.SingleExtensionUsagesValue.Key(arg)
                )
            }

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Interner
            fun intern(key: Key?): Key? {
                return com.google.devtools.build.lib.bazel.bzlmod.SingleExtensionUsagesValue.Key.Companion.interner.intern(
                    key
                )
            }
        }
    }

    companion object {
        fun create(
            extensionUsages: com.google.common.collect.ImmutableMap<ModuleKey?, ModuleExtensionUsage?>?,
            extensionUniqueName: String?,
            abridgedModules: com.google.common.collect.ImmutableList<AbridgedModule?>?,
            repoMappings: com.google.common.collect.ImmutableMap<ModuleKey?, com.google.devtools.build.lib.cmdline.RepositoryMapping?>?,
            repoOverrides: com.google.common.collect.ImmutableMap<String?, RepositoryName?>?
        ): SingleExtensionUsagesValue {
            return AutoValue_SingleExtensionUsagesValue(
                extensionUsages, extensionUniqueName, abridgedModules, repoMappings, repoOverrides
            )
        }

        /**
         * Turns the given usages value for a particular extension into a hash that can be compared for
         * equality with another hash obtained in this way and compares equal only if the two values are
         * equivalent for the purpose of evaluating the extension.
         */
        fun hashForEvaluation(gson: Gson, usagesValue: SingleExtensionUsagesValue): ByteArray {
            return com.google.common.hash.Hashing.sha256()
                .hashUnencodedChars(gson.toJson(usagesValue.trimForEvaluation()))
                .asBytes()
        }

        fun key(id: ModuleExtensionId?): Key? {
            return com.google.devtools.build.lib.bazel.bzlmod.SingleExtensionUsagesValue.Key.Companion.create(id)
        }
    }
}
