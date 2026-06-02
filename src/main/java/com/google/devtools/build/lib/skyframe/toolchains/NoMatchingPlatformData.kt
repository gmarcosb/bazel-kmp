// Copyright 2022 The Bazel Authors. All rights reserved.
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

/** Contains information related to missing execution platform.  */
@AutoValue
abstract class NoMatchingPlatformData {
    abstract fun toolchainTypes(): com.google.common.collect.ImmutableSet<ToolchainTypeRequirement?>?

    abstract fun availableExecutionPlatformKeys(): com.google.common.collect.ImmutableList<ConfiguredTargetKey?>?

    abstract fun targetPlatformKey(): ConfiguredTargetKey?

    @AutoValue.Builder
    internal abstract class Builder {
        abstract fun setToolchainTypes(toolchainTypes: com.google.common.collect.ImmutableSet<ToolchainTypeRequirement?>?): Builder?

        abstract fun setAvailableExecutionPlatformKeys(
            availableExecutionPlatformKeys: com.google.common.collect.ImmutableList<ConfiguredTargetKey?>?
        ): Builder?

        abstract fun setTargetPlatformKey(targetPlatformKey: ConfiguredTargetKey?): Builder?

        abstract fun build(): NoMatchingPlatformData?
    }

    fun formatError(): String? {
        if (this.toolchainTypes().isEmpty()) {
            return java.lang.String.format(
                "Unable to find an execution platform for target platform %s"
                        + " from available execution platforms [%s]",
                this.targetPlatformKey().getLabel(),
                this.availableExecutionPlatformKeys().stream()
                    .map<Any?>(java.util.function.Function { key: ConfiguredTargetKey? -> key.getLabel().toString() })
                    .collect(Collectors.joining(", "))
            )
        }
        return java.lang.String.format(
            "Unable to find an execution platform for toolchains [%s] and target platform %s"
                    + " from available execution platforms [%s]",
            this.toolchainTypes().stream()
                .map<Any?>(ToolchainTypeRequirement::toolchainType)
                .map<Any?>(Label::toString)
                .collect(Collectors.joining(", ")),
            this.targetPlatformKey().getLabel(),
            this.availableExecutionPlatformKeys().stream()
                .map<Any?>(java.util.function.Function { key: ConfiguredTargetKey? -> key.getLabel().toString() })
                .collect(Collectors.joining(", "))
        )
    }

    companion object {
        fun builder(): Builder {
            return Builder()
        }
    }
}
