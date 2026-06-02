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

import com.google.devtools.build.lib.bazel.bzlmod.RegistryOverride
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec

/**
 * Specifies that the module should:
 * 
 * 
 *  * be pinned to a single version,
 *  * and/or come from a specific registry (instead of the default list),
 *  * and/or use some local patches.
 * 
 * 
 * @param version The version to pin the module to. Can be empty if it shouldn't be pinned (in which
 * case it will still participate in version resolution).
 * @param patches The labels of patches to apply after retrieving per the registry.
 * @param patchCmds The patch commands to execute after retrieving per the registry. Should be a
 * list of commands.
 * @param patchStrip The number of path segments to strip from the paths in the supplied patches.
 */
@AutoCodec
class SingleVersionOverride(
    version: com.google.devtools.build.lib.bazel.bzlmod.Version?,
    registry: String?,
    patches: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.cmdline.Label?>?,
    patchCmds: com.google.common.collect.ImmutableList<String?>?,
    val patchStrip: Int
) : RegistryOverride {
    override fun getRegistry(): String? {
        return this.registry
    }

    val version: com.google.devtools.build.lib.bazel.bzlmod.Version?
    val registry: String?
    val patches: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.cmdline.Label?>?
    val patchCmds: com.google.common.collect.ImmutableList<String?>?

    init {
        this.patchCmds = patchCmds
        this.patches = patches
        this.registry = registry
        this.version = version
        java.util.Objects.requireNonNull<com.google.devtools.build.lib.bazel.bzlmod.Version?>(version, "version")
        java.util.Objects.requireNonNull<String?>(registry, "registry")
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableList<com.google.devtools.build.lib.cmdline.Label?>?>(
            patches,
            "patches"
        )
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableList<String?>?>(patchCmds, "patchCmds")
    }

    companion object {
        fun create(
            version: com.google.devtools.build.lib.bazel.bzlmod.Version?,
            registry: String?,
            patches: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.cmdline.Label?>?,
            patchCmds: com.google.common.collect.ImmutableList<String?>?,
            patchStrip: Int
        ): SingleVersionOverride {
            return SingleVersionOverride(version, registry, patches, patchCmds, patchStrip)
        }
    }
}
