// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.toolchains

import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement

/**
 * [SkyKey] implementation used for [ToolchainResolutionFunction] to produce [ ] instances.
 */
@AutoValue
abstract class ToolchainContextKey : SkyKey {
    override fun functionName(): SkyFunctionName {
        return SkyFunctions.TOOLCHAIN_RESOLUTION
    }

    val skyKeyInterner: SkyKeyInterner<*>
        get() = interner

    abstract fun configurationKey(): BuildConfigurationKey?

    abstract fun toolchainTypes(): com.google.common.collect.ImmutableSet<ToolchainTypeRequirement?>?

    abstract fun execConstraintLabels(): com.google.common.collect.ImmutableSet<Label?>?

    abstract fun forceExecutionPlatform(): java.util.Optional<Label?>?

    abstract fun debugTarget(): Boolean

    /** Builder for [ToolchainContextKey].  */
    @AutoValue.Builder
    abstract class Builder {
        abstract fun configurationKey(key: BuildConfigurationKey?): Builder?

        abstract fun toolchainTypes(toolchainTypes: com.google.common.collect.ImmutableSet<ToolchainTypeRequirement?>?): Builder?

        abstract fun toolchainTypes(vararg toolchainTypes: ToolchainTypeRequirement?): Builder?

        abstract fun execConstraintLabels(execConstraintLabels: com.google.common.collect.ImmutableSet<Label?>?): Builder?

        abstract fun execConstraintLabels(vararg execConstraintLabels: Label?): Builder?

        abstract fun debugTarget(flag: Boolean): Builder?

        abstract fun forceExecutionPlatform(execPlatform: Label?): Builder?

        fun build(): ToolchainContextKey {
            return interner.intern(autoBuild())
        }

        abstract fun autoBuild(): ToolchainContextKey?
    }

    companion object {
        private val interner: SkyKeyInterner<ToolchainContextKey?> = SkyKey.newInterner<ToolchainContextKey?>()

        /** Returns a new [Builder].  */
        @kotlin.jvm.JvmStatic
        fun key(): Builder {
            return Builder()
                .toolchainTypes(com.google.common.collect.ImmutableSet.of<E?>())
                .execConstraintLabels(com.google.common.collect.ImmutableSet.of<E?>())
                .debugTarget(false)!!
        }
    }
}
