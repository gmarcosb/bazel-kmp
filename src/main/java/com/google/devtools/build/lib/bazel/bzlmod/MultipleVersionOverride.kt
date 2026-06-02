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
 * Specifies that the module should still come from a registry, but multiple versions of it should
 * be allowed to coexist.
 * 
 * @param versions The versions of this module that should coexist.
 */
@AutoCodec
class MultipleVersionOverride(
    versions: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.bazel.bzlmod.Version?>?,
    val registry: String?
) : RegistryOverride {
    override fun getRegistry(): String? {
        return this.registry
    }

    val versions: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.bazel.bzlmod.Version?>?

    init {
        this.versions = versions
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableList<com.google.devtools.build.lib.bazel.bzlmod.Version?>?>(
            versions,
            "versions"
        )
        java.util.Objects.requireNonNull<String?>(registry, "registry")
    }

    companion object {
        fun create(
            versions: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.bazel.bzlmod.Version?>?,
            registry: String?
        ): MultipleVersionOverride {
            return MultipleVersionOverride(versions, registry)
        }
    }
}
