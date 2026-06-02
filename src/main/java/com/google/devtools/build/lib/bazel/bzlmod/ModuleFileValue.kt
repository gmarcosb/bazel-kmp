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

import com.google.devtools.build.lib.bazel.bzlmod.InterimModule
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey
import com.google.devtools.build.lib.cmdline.RepositoryName
import com.google.devtools.build.lib.skyframe.SkyFunctions
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec
import com.google.devtools.build.lib.vfs.PathFragment
import com.google.devtools.build.skyframe.SkyFunctionName
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyKey.SkyKeyInterner
import com.google.devtools.build.skyframe.SkyValue

/** The result of [ModuleFileFunction].  */
interface ModuleFileValue : SkyValue {
    /**
     * The module resulting from the module file evaluation. Note that the name and version of this
     * module might not match the one in the requesting [SkyKey] in certain circumstances (for
     * example, for the root module, or when non-registry overrides are in play.
     */
    fun module(): InterimModule?

    /**
     * Hashes of files obtained (or known to be missing) from registries while obtaining this module
     * file.
     */
    fun registryFileHashes(): com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>?

    /** The [ModuleFileValue] for non-root modules.  */
    @AutoCodec
    class NonRootModuleFileValue(
        module: InterimModule?,
        registryFileHashes: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>?
    ) : ModuleFileValue {
        val module: InterimModule?
        val registryFileHashes: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>?

        init {
            this.module = module
            this.registryFileHashes = registryFileHashes
        }
    }

    /**
     * The [ModuleFileValue] for the root module, containing additional information about
     * overrides.
     * 
     * @param overrides The overrides specified by the evaluated module file. The key is the module
     * name and the value is the override itself.
     * @param nonRegistryOverrideCanonicalRepoToModuleName A mapping from a canonical repo name to the
     * name of the module. Only works for modules with non-registry overrides.
     * @param nonRegistryOverrideModuleToRepoName A mapping from a module name to the repo name as
     * used by the root module. Only works for modules with non-registry overrides.
     * @param moduleFilePaths The set of relative paths to the root MODULE.bazel file itself and all
     * its transitive includes.
     */
    @AutoCodec
    class RootModuleFileValue(
        module: InterimModule?,
        overrides: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.bazel.bzlmod.ModuleOverride?>?,
        nonRegistryOverrideCanonicalRepoToModuleName: com.google.common.collect.ImmutableMap<RepositoryName?, String?>?,
        nonRegistryOverrideModuleToRepoName: com.google.common.collect.ImmutableMap<String?, String?>?,
        moduleFilePaths: com.google.common.collect.ImmutableSet<PathFragment?>?
    ) : ModuleFileValue {
        override fun registryFileHashes(): com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?> {
            // The root module is not obtained from a registry.
            return com.google.common.collect.ImmutableMap.of<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>()
        }

        val module: InterimModule?
        val overrides: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.bazel.bzlmod.ModuleOverride?>?
        val nonRegistryOverrideCanonicalRepoToModuleName: com.google.common.collect.ImmutableMap<RepositoryName?, String?>?
        val nonRegistryOverrideModuleToRepoName: com.google.common.collect.ImmutableMap<String?, String?>?
        val moduleFilePaths: com.google.common.collect.ImmutableSet<PathFragment?>?

        init {
            this.module = module
            this.overrides = overrides
            this.nonRegistryOverrideCanonicalRepoToModuleName = nonRegistryOverrideCanonicalRepoToModuleName
            this.nonRegistryOverrideModuleToRepoName = nonRegistryOverrideModuleToRepoName
            this.moduleFilePaths = moduleFilePaths
        }
    }

    /** [SkyKey] for [ModuleFileValue] computation.  */
    @AutoCodec
    class Key(moduleKey: ModuleKey?) : SkyKey {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.MODULE_FILE
        }

        override fun getSkyKeyInterner(): SkyKeyInterner<Key?> {
            return com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue.Key.Companion.interner
        }

        val moduleKey: ModuleKey?

        init {
            this.moduleKey = moduleKey
        }

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            @AutoCodec.Instantiator
            fun create(moduleKey: ModuleKey?): Key? {
                return com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue.Key(
                        moduleKey
                    )
                )
            }
        }
    }

    companion object {
        fun key(moduleKey: ModuleKey?): Key? {
            return com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue.Key.Companion.create(moduleKey)
        }

        @kotlin.jvm.JvmField
        val KEY_FOR_ROOT_MODULE: Key? = key(ModuleKey.Companion.ROOT)
    }
}
