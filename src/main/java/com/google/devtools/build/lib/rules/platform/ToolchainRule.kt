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
import com.google.devtools.build.lib.packages.Attribute.attr

/** Rule definition for [Toolchain].  */
class ToolchainRule : RuleDefinition {
    public override fun build(builder: RuleClass.Builder, env: RuleDefinitionEnvironment?): RuleClass {
        return builder
            .advertiseProvider(DeclaredToolchainInfo::class.java)
            .override(
                attr("tags", Types.STRING_LIST) // No need to show up in ":all", etc. target patterns.
                    .value(ImmutableList.of<E?>("manual"))
                    .nonconfigurable("low-level attribute, used in platform configuration")
            )
            .removeAttribute(":action_listener")
            .exemptFromConstraintChecking("this rule *defines* a constraint")
            .toolchainResolutionMode(ToolchainResolutionMode.DISABLED) /* <!-- #BLAZE_RULE(toolchain).ATTRIBUTE(toolchain_type) -->
        The label of a <code>toolchain_type</code> target that represents the role that this
        toolchain serves.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */

            .add(
                attr(TOOLCHAIN_TYPE_ATTR, BuildType.LABEL)
                    .mandatory()
                    .allowedFileTypes(FileTypeSet.NO_FILE)
                    .allowedRuleClasses("toolchain_type")
                    .mandatoryProviders(ToolchainTypeInfo.PROVIDER.id())
                    .nonconfigurable("part of toolchain configuration")
            ) /* <!-- #BLAZE_RULE(toolchain).ATTRIBUTE(exec_compatible_with) -->
        A list of <code>constraint_value</code>s that must be satisfied by an execution platform in
        order for this toolchain to be selected for a target building on that platform.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
            .add(
                attr(EXEC_COMPATIBLE_WITH_ATTR, BuildType.LABEL_LIST)
                    .mandatoryProviders(ConstraintValueInfo.PROVIDER.id())
                    .allowedFileTypes(FileTypeSet.NO_FILE)
                    .nonconfigurable("part of toolchain configuration")
            ) /* <!-- #BLAZE_RULE(toolchain).ATTRIBUTE(target_compatible_with) -->
        A list of <code>constraint_value</code>s that must be satisfied by the target platform in
        order for this toolchain to be selected for a target building for that platform.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
            .add(
                attr(TARGET_COMPATIBLE_WITH_ATTR, BuildType.LABEL_LIST)
                    .mandatoryProviders(ConstraintValueInfo.PROVIDER.id())
                    .allowedFileTypes(FileTypeSet.NO_FILE)
                    .nonconfigurable("part of toolchain configuration")
            ) /* <!-- #BLAZE_RULE(toolchain).ATTRIBUTE(use_target_platform_constraints) -->
        If <code>True</code>, this toolchain behaves as if its <code>exec_compatible_with</code> and
        <code>target_compatible_with</code> constraints are set to those of the current target
        platform. <code>exec_compatible_with</code> and <code>target_compatible_with</code> must not
        be set in that case.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
            .add(
                attr(USE_TARGET_PLATFORM_CONSTRAINTS_ATTR, Type.BOOLEAN)
                    .value(false)
                    .nonconfigurable("part of toolchain configuration")
            ) /* <!-- #BLAZE_RULE(toolchain).ATTRIBUTE(target_settings) -->
        A list of <code>config_setting</code>s that must be satisfied by the target configuration
        in order for this toolchain to be selected during toolchain resolution.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
            .add(
                attr(TARGET_SETTING_ATTR, BuildType.LABEL_LIST)
                    .allowedRuleClasses("config_setting")
                    .allowedFileTypes(FileTypeSet.NO_FILE)
            ) /* <!-- #BLAZE_RULE(toolchain).ATTRIBUTE(toolchain) -->
        The target representing the actual tool or tool suite that is made available when this
        toolchain is selected.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
            // This needs to not introduce a dependency so that we can load the toolchain only if it is
            // needed.
            .add(attr(TOOLCHAIN_ATTR, BuildType.NODEP_LABEL).mandatory())
            .build()
    }

    val metadata: RuleDefinition.Metadata
        get() = RuleDefinition.Metadata.builder()
            .name(RULE_NAME)
            .ancestors(BaseRuleClasses.NativeBuildRule::class.java)
            .factoryClass(Toolchain::class.java)
            .build()

    companion object {
        const val RULE_NAME: String = "toolchain"
        const val TOOLCHAIN_TYPE_ATTR: String = "toolchain_type"
        const val EXEC_COMPATIBLE_WITH_ATTR: String = "exec_compatible_with"
        const val TARGET_COMPATIBLE_WITH_ATTR: String = "target_compatible_with"
        const val TARGET_SETTING_ATTR: String = "target_settings"
        const val TOOLCHAIN_ATTR: String = "toolchain"
        const val USE_TARGET_PLATFORM_CONSTRAINTS_ATTR: String = "use_target_platform_constraints"
    }
} /*<!-- #BLAZE_RULE (NAME = toolchain, FAMILY = Platforms and Toolchains)[GENERIC_RULE] -->

<p>This rule declares a specific toolchain's type and constraints so that it can be selected
during toolchain resolution. See the
<a href="https://bazel.build/docs/toolchains">Toolchains</a> page for more
details.

<!-- #END_BLAZE_RULE -->*/
