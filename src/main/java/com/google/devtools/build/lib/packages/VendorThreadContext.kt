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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.RepositoryName

/** Context object for a Starlark thread evaluating the VENDOR.bazel file.  */
class VendorThreadContext {
    private val ignoredRepos: MutableList<RepositoryName?> = java.util.ArrayList<RepositoryName?>()
    private val pinnedRepos: MutableList<RepositoryName?> = java.util.ArrayList<RepositoryName?>()

    fun storeInThread(thread: net.starlark.java.eval.StarlarkThread) {
        thread.setThreadLocal<VendorThreadContext?>(VendorThreadContext::class.java, this)
    }

    fun getIgnoredRepos(): com.google.common.collect.ImmutableList<RepositoryName?> {
        return com.google.common.collect.ImmutableList.copyOf<RepositoryName?>(ignoredRepos)
    }

    fun getPinnedRepos(): com.google.common.collect.ImmutableList<RepositoryName?> {
        return com.google.common.collect.ImmutableList.copyOf<RepositoryName?>(pinnedRepos)
    }

    fun addIgnoredRepo(repoName: RepositoryName?) {
        ignoredRepos.add(repoName)
    }

    fun addPinnedRepo(repoName: RepositoryName?) {
        pinnedRepos.add(repoName)
    }

    companion object {
        @Throws(net.starlark.java.eval.EvalException::class)
        fun fromOrFail(thread: net.starlark.java.eval.StarlarkThread, what: String?): VendorThreadContext {
            val context: VendorThreadContext =
                thread.getThreadLocal<VendorThreadContext>(VendorThreadContext::class.java)
            if (context == null) {
                throw net.starlark.java.eval.Starlark.errorf("%s can only be called from VENDOR.bazel", what)
            }
            return context
        }
    }
}
