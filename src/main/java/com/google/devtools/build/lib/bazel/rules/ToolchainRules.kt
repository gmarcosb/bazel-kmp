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

/** Rules for toolchain support in Bazel.  */
class ToolchainRules private constructor() : RuleSet {
    public override fun init(builder: ConfiguredRuleClassProvider.Builder) {
        builder.addRuleDefinition(ToolchainTypeRule())
        builder.addRuleDefinition(GenRuleBaseRule())
        builder.addRuleDefinition(BazelGenRuleRule())
    }

    public override fun requires(): com.google.common.collect.ImmutableList<RuleSet?> {
        return com.google.common.collect.ImmutableList.of<E?>(
            CoreRules.INSTANCE,
            CcRules.Companion.INSTANCE,
            JavaRules.Companion.INSTANCE
        )
    }

    companion object {
        @kotlin.jvm.JvmField
        val INSTANCE: ToolchainRules = ToolchainRules()
    }
}
