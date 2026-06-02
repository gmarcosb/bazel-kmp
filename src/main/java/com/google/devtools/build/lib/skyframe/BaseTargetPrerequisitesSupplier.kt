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

import com.google.devtools.build.lib.analysis.ConfiguredTargetValue

/**
 * Looks up previously evaluated [ConfiguredTargetValue]s and [BuildConfigurationValue]s
 * without adding a dependency edge between them and the requesting node.
 * 
 * 
 * Mainly used by [AspectFunction] to look up the [ConfiguredTargetValue]s and [ ] of its target dependencies.
 */
interface BaseTargetPrerequisitesSupplier {
    /** Directly retrieves configured targets from Skyframe without adding a dependency edge.  */
    @Throws(java.lang.InterruptedException::class)
    fun getPrerequisite(key: ConfiguredTargetKey?): ConfiguredTargetValue?

    /** Directly retrieves configuration values from Skyframe without adding a dependency edge.  */
    @Throws(java.lang.InterruptedException::class)
    fun getPrerequisiteConfiguration(key: BuildConfigurationKey?): BuildConfigurationValue?

    /**
     * Directly retrieves unloaded toolchain contexts from Skyframe without adding a dependency edge.
     */
    @Throws(java.lang.InterruptedException::class)
    fun getUnloadedToolchainContext(key: ToolchainContextKey?): UnloadedToolchainContext?
}
