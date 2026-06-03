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
// limitations under the License
package com.google.devtools.build.lib.rules.config

import com.google.common.collect.ImmutableMap
import com.google.devtools.build.lib.packages.Attribute.attr

/** Rule introducing a transition to set feature flags for itself and dependencies.  */
class FeatureFlagSetterRule : RuleDefinition, RuleConfiguredTargetFactory {
    public override fun build(builder: RuleClass.Builder, env: RuleDefinitionEnvironment?): RuleClass {
        return builder
            .requiresConfigurationFragments(ConfigFeatureFlagConfiguration::class.java)
            .cfg(ConfigFeatureFlagTransitionFactory("flag_values"))
            .add(attr("deps", LABEL_LIST).allowedFileTypes())
            .add(attr("exports_setting", LABEL).allowedRuleClasses("config_setting").allowedFileTypes())
            .add(
                attr("exports_flag", LABEL)
                    .allowedRuleClasses("config_feature_flag")
                    .allowedFileTypes()
            )
            .add(
                attr("flag_values", LABEL_KEYED_STRING_DICT)
                    .allowedRuleClasses("config_feature_flag")
                    .allowedFileTypes()
                    .nonconfigurable("used in RuleTransitionFactory")
                    .value(ImmutableMap.of<Label?, String?>())
            )
            .build()
    }

    val metadata: Metadata
        get() = RuleDefinition.Metadata.builder()
            .name("feature_flag_setter")
            .ancestors(BaseRuleClasses.NativeBuildRule::class.java)
            .factoryClass(FeatureFlagSetterRule::class.java)
            .build()

    @Throws(InterruptedException::class, RuleErrorException::class, ActionConflictException::class)
    public override fun create(ruleContext: RuleContext): ConfiguredTarget? {
        val exportedFlag: TransitiveInfoCollection? = ruleContext.getPrerequisite("exports_flag")
        val exportedFlagProvider: ConfigFeatureFlagProvider? =
            if (exportedFlag != null) ConfigFeatureFlagProvider.fromTarget(exportedFlag) else null

        val exportedSetting: TransitiveInfoCollection? = ruleContext.getPrerequisite("exports_setting")
        val exportedSettingProvider: ConfigMatchingProvider? =
            if (exportedSetting != null) exportedSetting.getProvider(ConfigMatchingProvider::class.java) else null

        val builder: RuleConfiguredTargetBuilder =
            RuleConfiguredTargetBuilder(ruleContext)
                .setFilesToBuild(
                    PrerequisiteArtifacts.nestedSet(
                        ruleContext.getRulePrerequisitesCollection(), "deps"
                    )
                )
                .addProvider(RunfilesProvider::class.java, RunfilesProvider.EMPTY)
        if (exportedFlagProvider != null) {
            builder.addNativeDeclaredProvider(exportedFlagProvider)
        }
        if (exportedSettingProvider != null) {
            builder.addProvider(ConfigMatchingProvider::class.java, exportedSettingProvider)
        }
        return builder.build()
    }
}
