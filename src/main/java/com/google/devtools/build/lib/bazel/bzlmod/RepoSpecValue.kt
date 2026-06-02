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

import com.google.devtools.build.lib.bazel.bzlmod.RepoSpec
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec
import com.google.devtools.build.skyframe.SkyValue

/** The value for [RepoSpecFunction].  */
@AutoCodec
class RepoSpecValue(
    repoSpec: RepoSpec?,
    registryFileHashes: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>?
) : SkyValue {
    val repoSpec: RepoSpec?
    val registryFileHashes: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>?

    init {
        this.registryFileHashes = registryFileHashes
        this.repoSpec = repoSpec
        RepoSpec > java.util.Objects.requireNonNull<RepoSpec?>(repoSpec, "repoSpec")
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>?>(
            registryFileHashes,
            "registryFileHashes"
        )
    }

    companion object {
        fun create(
            repoSpec: RepoSpec?,
            registryFileHashes: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>?
        ): RepoSpecValue {
            return RepoSpecValue(repoSpec, registryFileHashes)
        }
    }
}
