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
package com.google.devtools.build.lib.rules.cpp


import com.google.devtools.build.lib.analysis.BaseRuleClasses

/**
 * Dummy rule definition for cc_toolchain_alias rule. To make sure that the rule can be overridden
 * from Starlark builtins.
 */
class CcToolchainAliasRule : RuleDefinition {
    public override fun build(builder: RuleClass.Builder, env: RuleDefinitionEnvironment?): RuleClass {
        return builder
            .build()
    }

    val metadata: Metadata
        get() = Metadata.builder()
            .name("cc_toolchain_alias")
            .factoryClass(BaseRuleClasses.EmptyRuleConfiguredTargetFactory::class.java)
            .ancestors(BaseRuleClasses.NativeBuildRule::class.java)
            .build()
}
