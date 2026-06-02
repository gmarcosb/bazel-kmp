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
package com.google.devtools.build.lib.bazel.rules

import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider

/**
 * A set of generic rules that provide miscellaneous capabilities to Bazel.
 */
class GenericRules private constructor() : RuleSet {
    public override fun init(builder: ConfiguredRuleClassProvider.Builder) {
        builder.addRuleDefinition(EnvironmentRule())

        builder.addRuleDefinition(AliasRule())
        builder.addRuleDefinition(FilegroupRule())
        builder.addRuleDefinition(TestSuiteRule())
        GenQueryRule.register(builder)
        builder.addRuleDefinition(LabelBuildSettingRule())
        builder.addRuleDefinition(LabelBuildFlagRule())
        builder.addRuleDefinition(StarlarkDocExtractRule())

        // TODO(#11437): It'd be nice to hide this definition behind a static helper, but the most apt
        // place would be as a static method of InternalModule.java in lib.packages, and that package
        // can't accept a ConfiguredRuleClassProvider.Builder. The alternative is to use a Bootstrap,
        // but that idiom should probably be deprecated.
        builder.addBzlToplevel(
            "_builtins_dummy",
            net.starlark.java.eval.FlagGuardedValue.onlyWhenExperimentalFlagIsTrue(
                BuildLanguageOptions.EXPERIMENTAL_BUILTINS_DUMMY, "original value"
            )
        )
    }

    public override fun requires(): com.google.common.collect.ImmutableList<RuleSet?> {
        return com.google.common.collect.ImmutableList.of<E?>(CoreRules.INSTANCE)
    }

    companion object {
        @kotlin.jvm.JvmField
        val INSTANCE: GenericRules = GenericRules()
    }
}
