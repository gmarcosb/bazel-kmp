// Copyright 2019 The Bazel Authors. All rights reserved.
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
 * Represents the state of toolchain resolution once the specific required toolchains have been
 * determined, but before the toolchain dependencies have been resolved.
 */
@AutoValue
abstract class UnloadedToolchainContextImpl : SkyValue, UnloadedToolchainContext {
    /** Builder class to help create the [UnloadedToolchainContextImpl].  */
    @AutoValue.Builder
    interface Builder {
        /** Sets the key that identifies this context.  */
        fun setKey(key: ToolchainContextKey?): Builder?

        /** Sets the selected execution platform that these toolchains use.  */
        fun setExecutionPlatform(executionPlatform: PlatformInfo?): Builder?

        /** Sets the target platform that these toolchains generate output for.  */
        fun setTargetPlatform(targetPlatform: PlatformInfo?): Builder?

        /** Sets the toolchain types that were requested.  */
        fun setToolchainTypes(toolchainTypes: MutableSet<ToolchainTypeRequirement?>?): Builder?

        /**
         * Maps from the actual toolchain type to the resolved toolchain implementation that should be
         * used.
         */
        fun setToolchainTypeToResolved(
            toolchainTypeToResolved: com.google.common.collect.ImmutableSetMultimap<ToolchainTypeInfo?, Label?>?
        ): Builder?

        /**
         * Maps from the actual requested [Label] to the discovered [ToolchainTypeInfo].
         * 
         * 
         * Note that the key may be different from [ToolchainTypeInfo.typeLabel] if the
         * requested [Label] is an `alias`.
         */
        fun setRequestedLabelToToolchainType(
            requestedLabelToToolchainType: com.google.common.collect.ImmutableMap<Label?, ToolchainTypeInfo?>?
        ): Builder?

        /** Stores an exception that occurred during resolution of this toolchain.  */
        fun setErrorData(errorData: NoMatchingPlatformData?): Builder?

        fun build(): UnloadedToolchainContextImpl?
    }

    override fun resolvedToolchainLabels(): com.google.common.collect.ImmutableSet<Label?> {
        return toolchainTypeToResolved().inverse().keySet()
    }

    protected abstract fun toBuilder(): Builder?

    companion object {
        fun builder(key: ToolchainContextKey?): Builder {
            return Builder()
                .setKey(key)
                .setToolchainTypes(com.google.common.collect.ImmutableSet.of<E?>())
                .setRequestedLabelToToolchainType(com.google.common.collect.ImmutableMap.of<K?, V?>())
                .setToolchainTypeToResolved(com.google.common.collect.ImmutableSetMultimap.of<K?, V?>())
                .setErrorData(null)
        }
    }
}
