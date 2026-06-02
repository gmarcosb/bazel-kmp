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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.analysis.ResolvedToolchainData
import com.google.devtools.build.lib.analysis.ToolchainContext
import com.google.devtools.build.lib.analysis.platform.ToolchainTypeInfo

/**
 * Interface for resolved toolchains data.
 * 
 * 
 * This interface is used to provide toolchain data to Starlark. This data can be the [ ] provider as in [ResolvedToolchainContext] for the aspect/rule own
 * toolchains, or it can be collection of aspects providers evaluated on the aspect's base target's
 * toolchains as in [AspectBaseTargetResolvedToolchainContext].
 */
interface ResolvedToolchainsDataInterface<T : ResolvedToolchainData?>
    : ToolchainContext {
    /** Returns a description of the target being used, for error messaging.  */
    fun targetDescription(): String?

    /** Returns the map from requested [Label] to toolchain type provider.  */
    fun requestedToolchainTypeLabels(): com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, ToolchainTypeInfo?>?

    /**
     * Returns the toolchain data for the given type, or `null` if the toolchain type was not
     * required in this context.
     */
    fun forToolchainType(toolchainTypeLabel: com.google.devtools.build.lib.cmdline.Label?): T?
}
