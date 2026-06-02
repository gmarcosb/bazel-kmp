// Copyright 2017 The Bazel Authors. All rights reserved.
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
 * A value which represents the map of potential execution platforms and resolved toolchains for a
 * single toolchain type. This allows for a Skyframe cache per toolchain type.
 * 
 * @param toolchainType Returns the resolved details about the requested toolchain type.
 * @param availableToolchainLabels Returns the resolved set of toolchain labels (as [Label])
 * for the requested toolchain type, keyed by the execution platforms (as [     ]). Ordering is not preserved, if the caller cares about the order of
 * platforms it must take care of that directly.
 */
@AutoCodec
class SingleToolchainResolutionValue(
    toolchainType: ToolchainTypeInfo?,
    availableToolchainLabels: com.google.common.collect.ImmutableMap<ConfiguredTargetKey?, Label?>?
) : SkyValue {
    /** [SkyKey] implementation used for [SingleToolchainResolutionFunction].  */
    @AutoValue
    abstract class SingleToolchainResolutionKey : SkyKey {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.SINGLE_TOOLCHAIN_RESOLUTION
        }

        abstract fun configurationKey(): BuildConfigurationKey?

        abstract fun toolchainType(): ToolchainTypeRequirement?

        abstract fun toolchainTypeInfo(): ToolchainTypeInfo?

        abstract fun targetPlatformKey(): ConfiguredTargetKey?

        abstract fun availableExecutionPlatformKeys(): com.google.common.collect.ImmutableList<ConfiguredTargetKey?>?

        abstract fun debugTarget(): Boolean

        companion object {
            fun create(
                configurationKey: BuildConfigurationKey?,
                toolchainType: ToolchainTypeRequirement?,
                toolchainTypeInfo: ToolchainTypeInfo?,
                targetPlatformKey: ConfiguredTargetKey?,
                availableExecutionPlatformKeys: MutableList<ConfiguredTargetKey?>?,
                debugTarget: Boolean
            ): SingleToolchainResolutionKey {
                return AutoValue_SingleToolchainResolutionValue_SingleToolchainResolutionKey(
                    configurationKey,
                    toolchainType,
                    toolchainTypeInfo,
                    targetPlatformKey,
                    com.google.common.collect.ImmutableList.< E > copyOf < E ? > (availableExecutionPlatformKeys),
                    debugTarget
                )
            }
        }
    }

    val toolchainType: ToolchainTypeInfo?
    val availableToolchainLabels: com.google.common.collect.ImmutableMap<ConfiguredTargetKey?, Label?>?

    init {
        this.availableToolchainLabels = availableToolchainLabels
        this.toolchainType = toolchainType
        java.util.Objects.requireNonNull<Any?>(toolchainType, "toolchainType")
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableMap<ConfiguredTargetKey?, Label?>?>(
            availableToolchainLabels,
            "availableToolchainLabels"
        )
    }

    companion object {
        // A key representing the input data.
        fun key(
            configurationKey: BuildConfigurationKey?,
            toolchainType: ToolchainTypeRequirement?,
            toolchainTypeInfo: ToolchainTypeInfo?,
            targetPlatformKey: ConfiguredTargetKey?,
            availableExecutionPlatformKeys: MutableList<ConfiguredTargetKey?>?
        ): SingleToolchainResolutionKey {
            return key(
                configurationKey,
                toolchainType,
                toolchainTypeInfo,
                targetPlatformKey,
                availableExecutionPlatformKeys,
                false
            )
        }

        fun key(
            configurationKey: BuildConfigurationKey?,
            toolchainType: ToolchainTypeRequirement?,
            toolchainTypeInfo: ToolchainTypeInfo?,
            targetPlatformKey: ConfiguredTargetKey?,
            availableExecutionPlatformKeys: MutableList<ConfiguredTargetKey?>?,
            debugTarget: Boolean
        ): SingleToolchainResolutionKey {
            return SingleToolchainResolutionKey.Companion.create(
                configurationKey,
                toolchainType,
                toolchainTypeInfo,
                targetPlatformKey,
                availableExecutionPlatformKeys,
                debugTarget
            )
        }

        @com.google.common.annotations.VisibleForTesting
        fun create(
            toolchainType: ToolchainTypeInfo?,
            availableToolchainLabels: com.google.common.collect.ImmutableMap<ConfiguredTargetKey?, Label?>?
        ): SingleToolchainResolutionValue {
            return SingleToolchainResolutionValue(toolchainType, availableToolchainLabels)
        }
    }
}
