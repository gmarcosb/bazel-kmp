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
package com.google.devtools.build.lib.rules.platform

import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.actions.ActionConflictException

/** Defines a toolchain that can be used by rules.  */
class Toolchain : RuleConfiguredTargetFactory {
    @Throws(InterruptedException::class, RuleErrorException::class, ActionConflictException::class)
    public override fun create(ruleContext: RuleContext): ConfiguredTarget? {
        val toolchainType: ToolchainTypeInfo? =
            PlatformProviderUtils.toolchainType(
                ruleContext.getPrerequisite(ToolchainRule.Companion.TOOLCHAIN_TYPE_ATTR)
            )
        val execConstraints: ImmutableList<ConstraintValueInfo?> =
            PlatformProviderUtils.constraintValues(
                ruleContext.getPrerequisites(ToolchainRule.Companion.EXEC_COMPATIBLE_WITH_ATTR)
            )
        val targetConstraints: ImmutableList<ConstraintValueInfo?> =
            PlatformProviderUtils.constraintValues(
                ruleContext.getPrerequisites(ToolchainRule.Companion.TARGET_COMPATIBLE_WITH_ATTR)
            )
        val targetSettings: ImmutableList<ConfigMatchingProvider?>? =
            ruleContext.getPrerequisites(ToolchainRule.Companion.TARGET_SETTING_ATTR).stream()
                .map({ target -> target.getProvider(ConfigMatchingProvider::class.java) })
                .collect(ImmutableList.toImmutableList<E?>())
        val resolvedToolchainLabel: Label? =
            ruleContext.attributes().get(ToolchainRule.Companion.TOOLCHAIN_ATTR, BuildType.NODEP_LABEL)
        val targetToExecConstraints: Boolean =
            ruleContext
                .attributes()
                .get(ToolchainRule.Companion.USE_TARGET_PLATFORM_CONSTRAINTS_ATTR, Type.BOOLEAN)
        if (targetToExecConstraints && !(execConstraints.isEmpty() && targetConstraints.isEmpty())) {
            ruleContext.attributeError(
                ToolchainRule.Companion.USE_TARGET_PLATFORM_CONSTRAINTS_ATTR,
                "Cannot set use_target_platform_constraints to True and also set exec_compatible_with or "
                        + "target_compatible_with"
            )
            return null
        }

        val registeredToolchain: DeclaredToolchainInfo?
        try {
            val registeredToolchainBuilder: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                DeclaredToolchainInfo.builder()
                    .toolchainType(toolchainType)
                    .addTargetSettings(targetSettings)
                    .resolvedToolchainLabel(resolvedToolchainLabel)
                    .targetLabel(ruleContext.getLabel())
            if (targetToExecConstraints) {
                registeredToolchain = registeredToolchainBuilder.buildWithTargetToExecConstraints()
            } else {
                registeredToolchain =
                    registeredToolchainBuilder
                        .addExecConstraints(execConstraints)
                        .addTargetConstraints(targetConstraints)
                        .build()
            }
        } catch (e: DeclaredToolchainInfo.DuplicateConstraintException) {
            if (e.execConstraintsException() != null) {
                ruleContext.attributeError(
                    ToolchainRule.Companion.EXEC_COMPATIBLE_WITH_ATTR, e.execConstraintsException().getMessage()
                )
            }
            if (e.targetConstraintsException() != null) {
                ruleContext.attributeError(
                    ToolchainRule.Companion.TARGET_COMPATIBLE_WITH_ATTR, e.targetConstraintsException().getMessage()
                )
            }
            // One of the above must have been non-null, so we just return early.
            return null
        }

        return RuleConfiguredTargetBuilder(ruleContext)
            .addProvider(RunfilesProvider::class.java, RunfilesProvider.EMPTY)
            .addProvider(FileProvider::class.java, FileProvider.EMPTY)
            .addProvider(FilesToRunProvider::class.java, FilesToRunProvider.EMPTY)
            .addProvider(registeredToolchain)
            .build()
    }
}
