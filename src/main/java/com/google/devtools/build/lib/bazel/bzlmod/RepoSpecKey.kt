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
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey
import com.google.devtools.build.lib.skyframe.SkyFunctions
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec
import com.google.devtools.build.skyframe.SkyFunctionName
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyKey.SkyKeyInterner

/** The key for [RepoSpecFunction].  */
@AutoCodec
internal class RepoSpecKey(moduleKey: ModuleKey?, val registryUrl: String?) : SkyKey {
    override fun functionName(): SkyFunctionName {
        return SkyFunctions.REPO_SPEC
    }

    override fun getSkyKeyInterner(): SkyKeyInterner<RepoSpecKey?> {
        return interner
    }

    val moduleKey: ModuleKey?

    init {
        this.moduleKey = moduleKey
        java.util.Objects.requireNonNull<ModuleKey?>(moduleKey, "moduleKey")
        java.util.Objects.requireNonNull<String?>(registryUrl, "registryUrl")
    }

    companion object {
        private val interner: SkyKeyInterner<RepoSpecKey?> = SkyKey.newInterner<RepoSpecKey?>()

        fun of(module: InterimModule): RepoSpecKey? {
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.bazel.bzlmod.Registry?>(
                module.getRegistry(), "module must not have a non-registry override"
            )
            return create(module.getKey(), module.getRegistry().getUrl())
        }

        @AutoCodec.Instantiator
        fun create(moduleKey: ModuleKey?, registryUrl: String?): RepoSpecKey? {
            return interner.intern(RepoSpecKey(moduleKey, registryUrl))
        }
    }
}
