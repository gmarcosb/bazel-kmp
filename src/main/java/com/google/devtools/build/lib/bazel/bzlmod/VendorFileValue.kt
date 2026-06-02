// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.cmdline.RepositoryName
import com.google.devtools.build.lib.skyframe.SkyFunctions
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue

/** Represent the parsed VENDOR.bazel file  */
class VendorFileValue(
    ignoredRepos: com.google.common.collect.ImmutableList<RepositoryName?>?,
    pinnedRepos: com.google.common.collect.ImmutableList<RepositoryName?>?
) : SkyValue {
    val ignoredRepos: com.google.common.collect.ImmutableList<RepositoryName?>?
    val pinnedRepos: com.google.common.collect.ImmutableList<RepositoryName?>?

    init {
        this.pinnedRepos = pinnedRepos
        this.ignoredRepos = ignoredRepos
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableList<RepositoryName?>?>(
            ignoredRepos,
            "ignoredRepos"
        )
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableList<RepositoryName?>?>(
            pinnedRepos,
            "pinnedRepos"
        )
    }

    companion object {
        @kotlin.jvm.JvmField
        @SerializationConstant
        val KEY: SkyKey = SkyKey { SkyFunctions.VENDOR_FILE }

        fun create(
            ignoredRepos: com.google.common.collect.ImmutableList<RepositoryName?>?,
            pinnedRepos: com.google.common.collect.ImmutableList<RepositoryName?>?
        ): VendorFileValue {
            return VendorFileValue(ignoredRepos, pinnedRepos)
        }
    }
}
