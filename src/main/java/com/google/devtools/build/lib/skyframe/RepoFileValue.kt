// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.RepositoryName

/** Contains information about the REPO.bazel file at the root of a repo.  */
@AutoCodec
class RepoFileValue(
    packageArgsMap: com.google.common.collect.ImmutableMap<String?, Any?>?,
    ignoredDirectories: com.google.common.collect.ImmutableList<String?>?
) : SkyValue {
    /** Key type for [RepoFileValue].  */
    class Key private constructor(repoName: RepositoryName?) : AbstractSkyKey<RepositoryName?>(repoName) {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.REPO_FILE
        }
    }

    val packageArgsMap: com.google.common.collect.ImmutableMap<String?, Any?>?
    val ignoredDirectories: com.google.common.collect.ImmutableList<String?>?

    init {
        this.ignoredDirectories = ignoredDirectories
        this.packageArgsMap = packageArgsMap
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableMap<String?, Any?>?>(
            packageArgsMap,
            "packageArgsMap"
        )
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableList<String?>?>(
            ignoredDirectories,
            "ignoredDirectories"
        )
    }

    companion object {
        val EMPTY: RepoFileValue = of(
            com.google.common.collect.ImmutableMap.of<String?, Any?>(),
            com.google.common.collect.ImmutableList.of<String?>()
        )

        fun of(
            packageArgsMap: com.google.common.collect.ImmutableMap<String?, Any?>?,
            ignoredDirectories: com.google.common.collect.ImmutableList<String?>?
        ): RepoFileValue {
            return RepoFileValue(packageArgsMap, ignoredDirectories)
        }

        fun key(repoName: RepositoryName?): Key {
            return com.google.devtools.build.lib.skyframe.RepoFileValue.Key(repoName)
        }
    }
}
