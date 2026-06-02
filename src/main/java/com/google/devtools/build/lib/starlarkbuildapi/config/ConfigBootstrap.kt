// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.starlarkbuildapi.config

import com.google.devtools.build.lib.starlarkbuildapi.config.ConfigGlobalLibraryApi
import com.google.devtools.build.lib.starlarkbuildapi.config.ConfigStarlarkCommonApi
import com.google.devtools.build.lib.starlarkbuildapi.config.StarlarkConfigApi

/**
 * A [Bootstrap] for config-related libraries of the build API.
 */
class ConfigBootstrap(
    configStarlarkCommonApi: ConfigStarlarkCommonApi?,
    starlarkConfigApi: StarlarkConfigApi?,
    configGlobalLibrary: ConfigGlobalLibraryApi?
) : com.google.devtools.build.lib.starlarkbuildapi.core.Bootstrap {
    private val configStarlarkCommonApi: ConfigStarlarkCommonApi?
    private val starlarkConfigApi: StarlarkConfigApi?
    private val configGlobalLibrary: ConfigGlobalLibraryApi?

    init {
        this.configStarlarkCommonApi = configStarlarkCommonApi
        this.starlarkConfigApi = starlarkConfigApi
        this.configGlobalLibrary = configGlobalLibrary
    }

    override fun addBindingsToBuilder(builder: com.google.common.collect.ImmutableMap.Builder<String?, Any?>) {
        builder.put("config_common", configStarlarkCommonApi)
        builder.put("config", starlarkConfigApi)
        net.starlark.java.eval.Starlark.addMethods(builder, configGlobalLibrary)
    }
}
