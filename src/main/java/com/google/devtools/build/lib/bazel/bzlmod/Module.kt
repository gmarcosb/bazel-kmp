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
import com.google.devtools.build.lib.bazel.bzlmod.ModuleBase
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey
import com.google.devtools.build.lib.bazel.bzlmod.RepoSpec
import com.google.devtools.build.lib.cmdline.RepositoryName

/**
 * Represents a node in the external dependency graph.
 * 
 * 
 * In particular, it represents a specific version of a module; there can be multiple [ ]s in a dependency graph with the same name but with different versions (when there's a
 * multiple_version_override in play).
 * 
 * 
 * For the intermediate type used during module resolution, see [InterimModule].
 */
@AutoValue
abstract class Module : ModuleBase() {
    /**
     * The resolved direct dependencies of this module. The key type is the repo name of the dep, and
     * the value type is the ModuleKey ([.getKey]) of the dep.
     */
    abstract fun getDeps(): com.google.common.collect.ImmutableMap<String?, ModuleKey?>?

    /**
     * Returns a [RepositoryMapping] with only Bazel module repos and no repos from module
     * extensions. For the full mapping, see [BazelDepGraphValue.getFullRepoMapping].
     */
    fun getRepoMappingWithBazelDepsOnly(
        moduleKeyToRepositoryNames: com.google.common.collect.ImmutableMap<ModuleKey?, RepositoryName?>
    ): com.google.devtools.build.lib.cmdline.RepositoryMapping {
        val mapping: com.google.common.collect.ImmutableMap.Builder<String?, RepositoryName?> =
            com.google.common.collect.ImmutableMap.builder<String?, RepositoryName?>()
        // If this is the root module, then the main repository should be visible as `@`.
        if (getKey() == ModuleKey.Companion.ROOT) {
            mapping.put("", RepositoryName.MAIN)
        }
        // Every module should be able to reference itself as @<module repo name>.
        // If this is the root module, this perfectly falls into @<module repo name> => @
        val owner: RepositoryName? = moduleKeyToRepositoryNames.get(getKey())
        if (!getRepoName().isEmpty()) {
            mapping.put(getRepoName(), owner)
        }
        for (dep in getDeps().entrySet()) {
            // Special note: if `dep` is actually the root module, its ModuleKey would be ROOT whose
            // canonicalRepoName is the empty string. This perfectly maps to the main repo ("@").
            mapping.put(dep.getKey(), moduleKeyToRepositoryNames.get(dep.getValue()))
        }
        return com.google.devtools.build.lib.cmdline.RepositoryMapping.create(mapping.buildOrThrow(), owner)
    }

    /**
     * The repo spec for this module (information about the attributes of its repository rule). This
     * is only non-null for modules coming from registries (i.e. without non-registry overrides).
     */
    abstract fun getRepoSpec(): RepoSpec?

    /** Builder type for [Module].  */
    @AutoValue.Builder
    abstract class Builder {
        abstract fun setName(value: String?): Builder?

        abstract fun setVersion(value: com.google.devtools.build.lib.bazel.bzlmod.Version?): Builder?

        abstract fun setKey(value: ModuleKey?): Builder?

        abstract fun setRepoName(value: String?): Builder?

        abstract fun setExecutionPlatformsToRegister(value: com.google.common.collect.ImmutableList<String?>?): Builder?

        abstract fun setToolchainsToRegister(value: com.google.common.collect.ImmutableList<String?>?): Builder?

        abstract fun setDeps(value: com.google.common.collect.ImmutableMap<String?, ModuleKey?>?): Builder?

        abstract fun depsBuilder(): com.google.common.collect.ImmutableMap.Builder<String?, ModuleKey?>?

        abstract fun setFlagAliases(value: com.google.common.collect.ImmutableMap<String?, String?>?): Builder?

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addDep(depRepoName: String?, depKey: ModuleKey?): Builder {
            depsBuilder().put(depRepoName, depKey)
            return this
        }

        abstract fun setRepoSpec(value: RepoSpec?): Builder?

        abstract fun setExtensionUsages(value: com.google.common.collect.ImmutableList<ModuleExtensionUsage?>?): Builder?

        abstract fun extensionUsagesBuilder(): com.google.common.collect.ImmutableList.Builder<ModuleExtensionUsage?>?

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addExtensionUsage(value: ModuleExtensionUsage): Builder {
            extensionUsagesBuilder().add(value)
            return this
        }

        abstract fun build(): Module?
    }

    companion object {
        /** Returns a new, empty [Builder].  */
        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return Builder()
        }
    }
}
