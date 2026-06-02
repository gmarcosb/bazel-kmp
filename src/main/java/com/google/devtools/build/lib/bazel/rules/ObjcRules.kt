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

import com.google.devtools.build.lib.analysis.BaseRuleClasses.EmptyRule

/** Rules for Objective-C support in Bazel.  */
class ObjcRules private constructor() : RuleSet {
    public override fun init(builder: ConfiguredRuleClassProvider.Builder) {
        builder.addConfigurationFragment(ObjcConfiguration::class.java)
        builder.addConfigurationFragment(AppleConfiguration::class.java)

        builder.addRuleDefinition(object : EmptyRule("objc_import") {})
        builder.addRuleDefinition(object : EmptyRule("objc_library") {})

        builder.addStarlarkBuiltinsInternal("apple_common", AppleStarlarkCommon())
        builder.addStarlarkBootstrap(AppleBootstrap())
    }

    public override fun requires(): com.google.common.collect.ImmutableList<RuleSet?> {
        return com.google.common.collect.ImmutableList.of<E?>(CoreRules.INSTANCE, CcRules.Companion.INSTANCE)
    }

    companion object {
        @kotlin.jvm.JvmField
        val INSTANCE: ObjcRules = ObjcRules()
    }
}
