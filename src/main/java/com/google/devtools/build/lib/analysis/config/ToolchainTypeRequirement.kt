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
package com.google.devtools.build.lib.analysis.config

import com.google.auto.value.AutoBuilder
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec
import com.google.devtools.build.lib.starlarkbuildapi.config.StarlarkToolchainTypeRequirement

/**
 * Describes a requirement on a specific toolchain type.
 * 
 * @param toolchainType Returns the label of the toolchain type that is requested.
 * @param mandatory Returns whether the toolchain type is mandatory or optional. An optional
 * toolchain type which cannot be found will be skipped, but a mandatory toolchain type which
 * cannot be found will stop the build with an error.
 * @param ignoreIfInvalid Returns whether the toolchain type should be ignored if it is found to be
 * invalid. This should only be used for internally-generated requirements, not user-generated.
 */
@AutoCodec
@kotlin.jvm.JvmRecord
data class ToolchainTypeRequirement(
    toolchainType: com.google.devtools.build.lib.cmdline.Label?,
    mandatory: Boolean,
    ignoreIfInvalid: Boolean
) : StarlarkToolchainTypeRequirement {
    /** Returns a new Builder to copy this ToolchainTypeRequirement.  */
    fun toBuilder(): Builder {
        return AutoBuilder_ToolchainTypeRequirement_Builder(this)
    }

    /** A builder for a new [ToolchainTypeRequirement].  */
    @AutoBuilder
    interface Builder {
        /** Sets the toolchain type.  */
        fun toolchainType(toolchainType: com.google.devtools.build.lib.cmdline.Label?): Builder?

        /** Sets whether the toolchain type is mandatory.  */
        fun mandatory(mandatory: Boolean): Builder?

        fun ignoreIfInvalid(ignore: Boolean): Builder?

        /** Returns the newly built [ToolchainTypeRequirement].  */
        fun build(): ToolchainTypeRequirement?
    }

    val toolchainType: com.google.devtools.build.lib.cmdline.Label?
    val mandatory: Boolean
    val ignoreIfInvalid: Boolean

    init {
        this.ignoreIfInvalid = ignoreIfInvalid
        this.mandatory = mandatory
        this.toolchainType = toolchainType
        java.util.Objects.requireNonNull<com.google.devtools.build.lib.cmdline.Label?>(toolchainType, "toolchainType")
    }

    companion object {
        /** Returns a new [ToolchainTypeRequirement].  */
        fun create(toolchainType: com.google.devtools.build.lib.cmdline.Label?): ToolchainTypeRequirement? {
            return builder(toolchainType).build()
        }

        /** Returns a builder for a new [ToolchainTypeRequirement].  */
        fun builder(toolchainType: com.google.devtools.build.lib.cmdline.Label?): Builder {
            return AutoBuilder_ToolchainTypeRequirement_Builder()
                .toolchainType(toolchainType)
                .mandatory(true)
                .ignoreIfInvalid(false)
        }

        /**
         * Returns the ToolchainTypeRequirement with the strictest restriction, or else the first.
         * Mandatory toolchain type requirements are stricter than optional.
         */
        fun strictest(
            first: ToolchainTypeRequirement, second: ToolchainTypeRequirement
        ): ToolchainTypeRequirement {
            com.google.common.base.Preconditions.checkArgument(
                first.toolchainType == second.toolchainType,
                "Cannot use strictest() for two instances with different type labels."
            )
            if (first.mandatory) {
                return first
            }
            if (second.mandatory) {
                return second
            }
            return first
        }
    }
}
