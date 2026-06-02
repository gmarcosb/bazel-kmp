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

import com.google.devtools.build.lib.bazel.bzlmod.RepoRuleId
import com.google.devtools.build.lib.bazel.bzlmod.RepoSpec

/** A utility class to create [RepoSpec]s for `local_repository`.  */
object LocalPathRepoSpecs {
    // TODO: wyv@ - maybe add support for new_local_repository?
    val LOCAL_REPOSITORY: RepoRuleId = RepoRuleId(
        com.google.devtools.build.lib.cmdline.Label.parseCanonicalUnchecked("@@bazel_tools//tools/build_defs/repo:local.bzl"),
        "local_repository"
    )

    @kotlin.jvm.JvmStatic
    fun create(path: String): RepoSpec {
        return RepoSpec(
            LOCAL_REPOSITORY,
            com.google.devtools.build.lib.bazel.bzlmod.AttributeValues.Companion.create(
                net.starlark.java.eval.Dict.immutableCopyOf<String?, Any?>(
                    com.google.common.collect.ImmutableMap.of<String?, String?>("path", path)
                )
            )
        )
    }
}
