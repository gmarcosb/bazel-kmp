// Copyright 2022 The Bazel Authors. All rights reserved.
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
import com.google.devtools.build.lib.bazel.bzlmod.BazelDepGraphValue.Companion.KEY
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey
import com.google.devtools.build.lib.cmdline.RepositoryName
import com.google.devtools.build.lib.skyframe.SkyFunctions
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue

/**
 * The result of running Bazel module resolution, containing the Bazel module dependency graph
 * post-version-resolution.
 */
@AutoValue
abstract class BazelDepGraphValue : SkyValue {
    /**
     * The post-selection dep graph. Must have BFS iteration order, starting from the root module. For
     * any KEY in the returned map, it's guaranteed that `depGraph[KEY].getKey() == KEY`.
     */
    @kotlin.jvm.JvmField
    abstract val depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, com.google.devtools.build.lib.bazel.bzlmod.Module?>?

    /** A mapping from a canonical repo name to the key of the module backing it and back.  */
    @kotlin.jvm.JvmField
    abstract val canonicalRepoNameLookup: com.google.common.collect.ImmutableBiMap<RepositoryName?, ModuleKey?>?

    /** All modules in the same order as [.getDepGraph], but with limited information.  */
    @kotlin.jvm.JvmField
    abstract val abridgedModules: com.google.common.collect.ImmutableList<AbridgedModule?>?

    /**
     * All module extension usages grouped by the extension's ID and the key of the module where this
     * usage occurs. For each extension identifier ID, extensionUsagesTable[ID][moduleKey] is the
     * ModuleExtensionUsage of ID in the module keyed by moduleKey.
     */
    // Note: Equality of BazelDepGraphValue does not check for equality of the order of the rows of
    // this table, but it is tracked implicitly via the order of the abridged modules.
    abstract val extensionUsagesTable: com.google.common.collect.ImmutableTable<ModuleExtensionId?, ModuleKey?, ModuleExtensionUsage?>?
        /**
         * All module extension usages grouped by the extension's ID and the key of the module where this
         * usage occurs. For each extension identifier ID, extensionUsagesTable[ID][moduleKey] is the
         * ModuleExtensionUsage of ID in the module keyed by moduleKey.
         */
        get

    /**
     * A mapping from the ID of a module extension to a unique string that serves as its "name". This
     * is not the same as the extension's declared name, as the declared name is only unique within
     * the .bzl file, whereas this unique name is guaranteed to be unique across the workspace.
     */
    abstract fun getExtensionUniqueNames(): com.google.common.collect.ImmutableMap<ModuleExtensionId?, String?>?

    /**
     * For each module extension, a mapping from the name of the repo exported by the extension to the
     * canonical name of the repo that should override it (if any).
     */
    abstract fun getRepoOverrides(): com.google.common.collect.ImmutableTable<ModuleExtensionId?, String?, RepositoryName?>?

    /**
     * Returns the full [RepositoryMapping] for the given module, including repos from Bazel
     * module deps and module extensions.
     */
    fun getFullRepoMapping(key: ModuleKey?): com.google.devtools.build.lib.cmdline.RepositoryMapping? {
        return getRepositoryMapping(
            key,
            this.depGraph,
            this.extensionUsagesTable,
            getExtensionUniqueNames(),
            this.canonicalRepoNameLookup,
            getRepoOverrides()
        )
    }

    companion object {
        @kotlin.jvm.JvmField
        @SerializationConstant
        val KEY: SkyKey = SkyKey { SkyFunctions.BAZEL_DEP_GRAPH }

        fun create(
            depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, com.google.devtools.build.lib.bazel.bzlmod.Module?>?,
            canonicalRepoNameLookup: com.google.common.collect.ImmutableMap<RepositoryName?, ModuleKey?>?,
            abridgedModules: com.google.common.collect.ImmutableList<AbridgedModule?>?,
            extensionUsagesTable: com.google.common.collect.ImmutableTable<ModuleExtensionId?, ModuleKey?, ModuleExtensionUsage?>?,
            extensionUniqueNames: com.google.common.collect.ImmutableMap<ModuleExtensionId?, String?>?,
            repoOverrides: com.google.common.collect.ImmutableTable<ModuleExtensionId?, String?, RepositoryName?>?
        ): BazelDepGraphValue {
            return AutoValue_BazelDepGraphValue(
                depGraph,
                com.google.common.collect.ImmutableBiMap.< K, V > copyOf<K?, V?>(canonicalRepoNameLookup),
                abridgedModules,
                extensionUsagesTable,
                extensionUniqueNames,
                repoOverrides
            )
        }

        @kotlin.jvm.JvmStatic
        fun createEmptyDepGraph(): BazelDepGraphValue {
            val root: com.google.devtools.build.lib.bazel.bzlmod.Module =
                com.google.devtools.build.lib.bazel.bzlmod.Module.Companion.builder()
                    .setName("")
                    .setVersion(com.google.devtools.build.lib.bazel.bzlmod.Version.Companion.EMPTY)
                    .setRepoName("")
                    .setKey(ModuleKey.Companion.ROOT)
                    .setExtensionUsages(com.google.common.collect.ImmutableList.of<ModuleExtensionUsage?>())
                    .setExecutionPlatformsToRegister(com.google.common.collect.ImmutableList.of<String?>())
                    .setToolchainsToRegister(com.google.common.collect.ImmutableList.of<String?>())
                    .setFlagAliases(com.google.common.collect.ImmutableMap.of<String?, String?>())
                    .build()

            val emptyDepGraph: com.google.common.collect.ImmutableMap<ModuleKey?, com.google.devtools.build.lib.bazel.bzlmod.Module?> =
                com.google.common.collect.ImmutableMap.of<ModuleKey?, com.google.devtools.build.lib.bazel.bzlmod.Module?>(
                    ModuleKey.Companion.ROOT,
                    root
                )
            val canonicalRepoNameLookup: com.google.common.collect.ImmutableMap<RepositoryName?, ModuleKey?> =
                com.google.common.collect.ImmutableMap.of<RepositoryName?, ModuleKey?>(
                    RepositoryName.MAIN,
                    ModuleKey.Companion.ROOT
                )

            return create(
                emptyDepGraph,
                canonicalRepoNameLookup,
                com.google.common.collect.ImmutableList.of<AbridgedModule?>(),
                com.google.common.collect.ImmutableTable.of<ModuleExtensionId?, ModuleKey?, ModuleExtensionUsage?>(),
                com.google.common.collect.ImmutableMap.of<ModuleExtensionId?, String?>(),
                com.google.common.collect.ImmutableTable.of<ModuleExtensionId?, String?, RepositoryName?>()
            )
        }

        fun getRepositoryMapping(
            key: ModuleKey?,
            depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, com.google.devtools.build.lib.bazel.bzlmod.Module?>,
            extensionUsagesTable: com.google.common.collect.ImmutableTable<ModuleExtensionId?, ModuleKey?, ModuleExtensionUsage?>,
            extensionUniqueNames: com.google.common.collect.ImmutableMap<ModuleExtensionId?, String?>,
            canonicalRepoNameLookup: com.google.common.collect.ImmutableBiMap<RepositoryName?, ModuleKey?>,
            repoOverrides: com.google.common.collect.ImmutableTable<ModuleExtensionId?, String?, RepositoryName?>
        ): com.google.devtools.build.lib.cmdline.RepositoryMapping? {
            val mapping: com.google.common.collect.ImmutableMap.Builder<String?, RepositoryName?> =
                com.google.common.collect.ImmutableMap.builder<String?, RepositoryName?>()
            for (extIdAndUsage in extensionUsagesTable.column(key).entrySet()) {
                val extensionId: ModuleExtensionId? = extIdAndUsage.getKey()
                val usage: ModuleExtensionUsage = extIdAndUsage.getValue()
                val repoNamePrefix = extensionUniqueNames.get(extensionId) + "+"
                for (proxy in usage.getProxies()) {
                    for (entry in proxy.getImports().entrySet()) {
                        val defaultCanonicalRepoName: RepositoryName? =
                            RepositoryName.createUnvalidated(repoNamePrefix + entry.getValue())
                        mapping.put(
                            entry.getKey(),
                            repoOverrides
                                .row(extensionId)
                                .getOrDefault(entry.getValue(), defaultCanonicalRepoName)
                        )
                    }
                }
            }
            return depGraph
                .get(key)
                .getRepoMappingWithBazelDepsOnly(canonicalRepoNameLookup.inverse())
                .withAdditionalMappings(mapping.buildOrThrow())
        }
    }
}
