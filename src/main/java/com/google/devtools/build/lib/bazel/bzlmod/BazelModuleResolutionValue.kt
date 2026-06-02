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
import com.google.devtools.build.lib.bazel.bzlmod.InterimModule
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey
import com.google.devtools.build.lib.skyframe.SkyFunctions
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue

/**
 * The result of the selection process, containing both the pruned and the un-pruned dependency
 * graphs.
 */
@AutoValue
abstract class BazelModuleResolutionValue : SkyValue {
    /** Final dep graph sorted in BFS iteration order, with unused modules removed.  */
    abstract fun getResolvedDepGraph(): com.google.common.collect.ImmutableMap<ModuleKey?, com.google.devtools.build.lib.bazel.bzlmod.Module?>?

    /**
     * Un-pruned dep graph, with updated dep keys, and additionally containing the unused modules
     * which were initially discovered (and their MODULE.bazel files loaded). Does not contain modules
     * overridden by `single_version_override` or [NonRegistryOverride], only by `multiple_version_override`.
     */
    abstract fun getUnprunedDepGraph(): com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?>?

    /**
     * Hashes of files obtained (or known to be missing) from registries while performing resolution.
     */
    abstract fun getRegistryFileHashes(): com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>?

    /**
     * Selected module versions that are known to be yanked (and hence must have been explicitly
     * allowed by the user).
     */
    abstract fun getSelectedYankedVersions(): com.google.common.collect.ImmutableMap<ModuleKey?, String?>?

    companion object {
        @kotlin.jvm.JvmField
        @SerializationConstant
        val KEY: SkyKey = SkyKey { SkyFunctions.BAZEL_MODULE_RESOLUTION }

        fun create(
            resolvedDepGraph: com.google.common.collect.ImmutableMap<ModuleKey?, com.google.devtools.build.lib.bazel.bzlmod.Module?>?,
            unprunedDepGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?>?,
            registryFileHashes: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>?,
            selectedYankedVersions: com.google.common.collect.ImmutableMap<ModuleKey?, String?>?
        ): BazelModuleResolutionValue {
            return AutoValue_BazelModuleResolutionValue(
                resolvedDepGraph, unprunedDepGraph, registryFileHashes, selectedYankedVersions
            )
        }
    }
}
