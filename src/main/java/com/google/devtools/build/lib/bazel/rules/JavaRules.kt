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

/** Rules for Java support in Bazel.  */
class JavaRules private constructor() : RuleSet {
    public override fun init(builder: ConfiguredRuleClassProvider.Builder) {
        builder.addConfigurationFragment(JavaConfiguration::class.java)

        builder.addRuleDefinition(JavaPluginsFlagAliasRule())

        builder.addRuleDefinition(ExtraActionRule())
        builder.addRuleDefinition(ActionListenerRule())

        builder.addBzlToplevel("java_common", net.starlark.java.eval.Starlark.NONE)
        builder.addStarlarkBuiltinsInternal(
            "java_common_internal_do_not_use", JavaStarlarkCommon(BazelJavaSemantics.Companion.INSTANCE)
        )
    }

    public override fun requires(): com.google.common.collect.ImmutableList<RuleSet?> {
        return com.google.common.collect.ImmutableList.of<E?>(CoreRules.INSTANCE, CcRules.Companion.INSTANCE)
    }

    companion object {
        @kotlin.jvm.JvmField
        val INSTANCE: JavaRules = JavaRules()
    }
}
