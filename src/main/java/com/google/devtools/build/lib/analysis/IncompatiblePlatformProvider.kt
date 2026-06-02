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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.analysis.ConfiguredTarget
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo
import com.google.devtools.build.lib.packages.BuiltinProvider
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec
import com.google.devtools.build.lib.starlarkbuildapi.platform.IncompatiblePlatformProviderApi

/**
 * Provider instance for the `target_compatible_with` attribute.
 * 
 * 
 * The presence of this provider is used to indicate that a target is incompatible with the
 * current platform. Any target that provides this will automatically be excluded from [ ]'s list of configured targets.
 * 
 * 
 * This provider is able to keep track of _why_ the corresponding target is considered
 * incompatible. If the target is incompatible because the target platform didn't satisfy one of the
 * constraints in target_compatible_with, then all the relevant constraints are accessible via
 * `getConstraintsResponsibleForIncompatibility()`. On the other hand, if the corresponding
 * target is incompatible because one of its dependencies is incompatible, then all the incompatible
 * dependencies are available via `getTargetResponsibleForIncompatibility()`.
 * 
 * @param targetPlatform Returns the target platform of the target that was incompatible.
 * @param targetsResponsibleForIncompatibility Returns the incompatible dependencies that caused
 * this provider to be present.
 * 
 * This may be null. If it is null, then `getConstraintsResponsibleForIncompatibility()` is guaranteed to be non-null. It will have at
 * least one element in it if it is not null.
 * @param constraintsResponsibleForIncompatibility Returns the constraints that the target platform
 * didn't satisfy.
 * 
 * This may be null. If it is null, then `getTargetsResponsibleForIncompatibility()` is
 * guaranteed to be non-null. It will have at least one element in it if it is not null.
 * 
 * The list is sorted based on the stringified label of each constraint.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
@AutoCodec
class IncompatiblePlatformProvider(
    targetPlatform: com.google.devtools.build.lib.cmdline.Label?,
    targetsResponsibleForIncompatibility: com.google.common.collect.ImmutableList<ConfiguredTarget?>?,
    constraintsResponsibleForIncompatibility: com.google.common.collect.ImmutableList<ConstraintValueInfo?>?
) : com.google.devtools.build.lib.packages.Info, IncompatiblePlatformProviderApi {
    override fun getProvider(): BuiltinProvider<IncompatiblePlatformProvider?> {
        return PROVIDER
    }

    override fun isImmutable(): Boolean {
        return true // immutable and Starlark-hashable
    }

    val targetPlatform: com.google.devtools.build.lib.cmdline.Label?
    val targetsResponsibleForIncompatibility: com.google.common.collect.ImmutableList<ConfiguredTarget?>?
    val constraintsResponsibleForIncompatibility: com.google.common.collect.ImmutableList<ConstraintValueInfo?>?

    init {
        this.targetPlatform = targetPlatform
        this.targetsResponsibleForIncompatibility = targetsResponsibleForIncompatibility
        this.constraintsResponsibleForIncompatibility = constraintsResponsibleForIncompatibility
    }

    companion object {
        /** Name used in Starlark for accessing this provider.  */
        const val STARLARK_NAME: String = "IncompatiblePlatformProvider"

        /** Provider singleton constant.  */
        @kotlin.jvm.JvmField
        val PROVIDER: BuiltinProvider<IncompatiblePlatformProvider?> =
            object : BuiltinProvider<IncompatiblePlatformProvider?>(
                STARLARK_NAME, IncompatiblePlatformProvider::class.java
            ) {}

        fun incompatibleDueToTargets(
            targetPlatform: com.google.devtools.build.lib.cmdline.Label?,
            targetsResponsibleForIncompatibility: com.google.common.collect.ImmutableList<ConfiguredTarget?>?
        ): IncompatiblePlatformProvider {
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<ConfiguredTarget?>?>(
                targetsResponsibleForIncompatibility
            )
            com.google.common.base.Preconditions.checkArgument(!targetsResponsibleForIncompatibility.isEmpty())
            return IncompatiblePlatformProvider(
                targetPlatform, targetsResponsibleForIncompatibility, null
            )
        }

        fun incompatibleDueToConstraints(
            targetPlatform: com.google.devtools.build.lib.cmdline.Label?,
            constraints: com.google.common.collect.ImmutableList<ConstraintValueInfo?>?
        ): IncompatiblePlatformProvider {
            var constraints: com.google.common.collect.ImmutableList<ConstraintValueInfo?>? = constraints
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<ConstraintValueInfo?>?>(
                constraints
            )
            com.google.common.base.Preconditions.checkArgument(!constraints.isEmpty())

            // Deduplicate and sort the list of incompatible constraints. Doing it here means that everyone
            // inspecting this provider doesn't have to deal with it.
            constraints =
                constraints.stream()
                    .sorted(
                        java.util.Comparator.comparing<ConstraintValueInfo?, com.google.devtools.build.lib.cmdline.Label?>(
                            java.util.function.Function { obj: ConstraintValueInfo? -> obj.label() })
                    )
                    .distinct()
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<ConstraintValueInfo?>())

            return IncompatiblePlatformProvider(targetPlatform, null, constraints)
        }
    }
}
