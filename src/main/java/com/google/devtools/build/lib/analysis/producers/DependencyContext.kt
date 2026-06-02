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
package com.google.devtools.build.lib.analysis.producers

import com.google.devtools.build.lib.analysis.config.ConfigConditions

/**
 * Groups together unloaded toolchain contexts and config conditions.
 * 
 * 
 * These are used together when computing dependencies.
 */
class DependencyContext(
    unloadedToolchainContexts: ToolchainCollection<UnloadedToolchainContext?>?,
    configConditions: ConfigConditions?
) {
    fun toolchainContexts(): ToolchainCollection<ToolchainContext?>? {
        if (this.unloadedToolchainContexts == null) {
            return null
        }
        return this.unloadedToolchainContexts.asToolchainContexts()
    }

    val unloadedToolchainContexts: ToolchainCollection<UnloadedToolchainContext?>?
    val configConditions: ConfigConditions?

    init {
        this.configConditions = configConditions
        this.unloadedToolchainContexts = unloadedToolchainContexts
        java.util.Objects.requireNonNull<Any?>(configConditions, "configConditions")
    }

    companion object {
        fun create(
            unloadedToolchainContexts: ToolchainCollection<UnloadedToolchainContext?>?,
            configConditions: ConfigConditions?
        ): DependencyContext {
            return DependencyContext(unloadedToolchainContexts, configConditions)
        }
    }
}
