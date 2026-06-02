// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules

import com.google.devtools.build.lib.packages.Attribute.attr

/**
 * Native implementation of label setting and flags.
 * 
 * 
 * While most build settings are completely defined in starlark, we're natively defining
 * label-typed ones because:
 * 
 * 
 *  * they're essentially special Alias targets
 *  * we don't have a known use case where you'd want to manipulate a label-typed build setting
 * in its implementation section.
 * 
 * 
 * 
 * Once we do have (2), we can consider switching over to starlark implementation. The dangers
 * there involve the implementation function returning a label we've never seen before in the build.
 * And since label-typed build settings actually return the providers of the targets they point to,
 * we'd have to be able to load and configure potentially arbitrary labels on the fly. This is not
 * possible today and could easily introduce large performance issues.
 */
object LabelBuildSettings {
    private const val NONCONFIGURABLE_ATTRIBUTE_REASON = "part of a rule class that *triggers* configurable behavior"

    // TODO(b/65746853): find a way to do this without passing the entire BuildConfigurationValue
    private val ACTUAL: LabelLateBoundDefault<BuildConfigurationValue?>? =
        LabelLateBoundDefault.fromTargetConfigurationWithRuleBasedDefault(
            BuildConfigurationValue::class.java,
            { rule ->  // RawAttributeMapper means this attribute can't be select()able (which it isn't).
                RawAttributeMapper.of(rule)
                    .get(STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME, NODEP_LABEL)
            },
            { rule, attributes, configuration ->
                if (rule == null || configuration == null) {
                    return@fromTargetConfigurationWithRuleBasedDefault attributes.get(
                        STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME,
                        NODEP_LABEL
                    )
                }
                val commandLineValue: Any? =
                    configuration.getOptions().getStarlarkOptions().get(rule.getLabel())
                if (commandLineValue == null) {
                    return@fromTargetConfigurationWithRuleBasedDefault attributes.get(
                        STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME,
                        NODEP_LABEL
                    )
                }
                com.google.common.base.Preconditions.checkState(
                    commandLineValue is Label,
                    "the value of %s should have been converted to a label already, but its type is %s",
                    rule.getLabel(),
                    net.starlark.java.eval.Starlark.type(commandLineValue)
                )
                commandLineValue as Label
            })

    private fun buildRuleClass(builder: RuleClass.Builder, flag: Boolean): RuleClass {
        return builder
            .removeAttribute("licenses")
            .removeAttribute("distribs")
            .removeAttribute(":action_listener")
            .add(attr(":alias", LABEL).value(ACTUAL))
            .add(
                attr("scope", STRING)
                    .value("universal")
                    .nonconfigurable(NONCONFIGURABLE_ATTRIBUTE_REASON)
            )
            .add(attr("on_leave_scope", NODEP_LABEL).nonconfigurable(NONCONFIGURABLE_ATTRIBUTE_REASON))
            .setBuildSetting(BuildSetting.create(flag, NODEP_LABEL))
            .canHaveAnyProvider()
            .toolchainResolutionMode(ToolchainResolutionMode.DISABLED)
            .build()
    }

    /** Rule definition of label_setting.  */
    class LabelBuildSettingRule : AbstractAliasRule("label_setting") {
        public override fun build(builder: RuleClass.Builder, environment: RuleDefinitionEnvironment?): RuleClass {
            return buildRuleClass(builder,  /* flag= */false)
        }
    }

    /** Rule definition of label_flag  */
    class LabelBuildFlagRule : AbstractAliasRule("label_flag") {
        public override fun build(builder: RuleClass.Builder, environment: RuleDefinitionEnvironment?): RuleClass {
            return buildRuleClass(builder,  /* flag= */true)
        }
    }
}
