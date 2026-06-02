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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.StarlarkThreadContext

/** Context object for a Starlark thread evaluating the REPO.bazel file.  */
class RepoThreadContext : StarlarkThreadContext({ null }) {
    private var packageArgsMap: com.google.common.collect.ImmutableMap<String?, Any?> =
        com.google.common.collect.ImmutableMap.of<String?, Any?>()
    private var repoFunctionCalled = false

    private var ignoredDirectories: com.google.common.collect.ImmutableList<String?> =
        com.google.common.collect.ImmutableList.of<String?>()
    private var ignoredDirectoriesSet = false

    fun isRepoFunctionCalled(): Boolean {
        return repoFunctionCalled
    }

    fun setPackageArgsMap(kwargs: MutableMap<String?, Any?>) {
        repoFunctionCalled = true
        this.packageArgsMap = com.google.common.collect.ImmutableMap.copyOf<String?, Any?>(kwargs)
    }

    fun getPackageArgsMap(): com.google.common.collect.ImmutableMap<String?, Any?> {
        return packageArgsMap
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    fun setIgnoredDirectories(ignoredDirectories: MutableCollection<String?>) {
        ignoredDirectoriesSet = true
        this.ignoredDirectories = com.google.common.collect.ImmutableList.copyOf<String?>(ignoredDirectories)
    }

    fun isIgnoredDirectoriesSet(): Boolean {
        return ignoredDirectoriesSet
    }

    fun getIgnoredDirectories(): com.google.common.collect.ImmutableList<String?> {
        return ignoredDirectories
    }

    companion object {
        @Throws(net.starlark.java.eval.EvalException::class)
        fun fromOrFail(thread: net.starlark.java.eval.StarlarkThread, what: String?): RepoThreadContext? {
            val context: StarlarkThreadContext? =
                thread.getThreadLocal<StarlarkThreadContext?>(StarlarkThreadContext::class.java)
            if (context is RepoThreadContext) {
                return context
            }
            throw net.starlark.java.eval.Starlark.errorf("%s can only be called from REPO.bazel", what)
        }
    }
}
