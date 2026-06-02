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
package com.google.devtools.build.lib.rules.config

import com.google.devtools.build.lib.analysis.BaseRuleClasses

/**
 * Set of rules to specify or manipulate configuration settings.
 */
class ConfigRules private constructor() : RuleSet {
    public override fun init(builder: ConfiguredRuleClassProvider.Builder) {
        builder.addTrimmingTransitionFactory(
            ConfigFeatureFlagTaggedTrimmingTransitionFactory(BaseRuleClasses.TAGGED_TRIMMING_ATTR)
        )

        // This implementation trims all feature flags out of toolchains. This is performant assuming
        // toolchains don't need to read feature flags (which should be practically the case). We can
        // turn this into a no-op should that need ever arise (and pay the added performance cost).
        builder.setToolchainTaggedTrimmingTransition(ConfigFeatureFlagTaggedTrimmingTransition.Companion.EMPTY)

        builder.addRuleDefinition(ConfigBaseRule())
        builder.addRuleDefinition(ConfigSettingRule())
        builder.addConfigurationFragment(ConfigFeatureFlagConfiguration::class.java)

        builder.addRuleDefinition(ConfigFeatureFlagRule())
        builder.addStarlarkBootstrap(
            ConfigBootstrap(
                ConfigStarlarkCommon(), StarlarkConfig(), ConfigGlobalLibrary()
            )
        )
    }

    public override fun requires(): com.google.common.collect.ImmutableList<RuleSet?> {
        return com.google.common.collect.ImmutableList.of<E?>(
            CoreRules.Companion.INSTANCE,
            PlatformRules.Companion.INSTANCE
        )
    }

    companion object {
        @kotlin.jvm.JvmField
        val INSTANCE: ConfigRules = ConfigRules()
    }
}
