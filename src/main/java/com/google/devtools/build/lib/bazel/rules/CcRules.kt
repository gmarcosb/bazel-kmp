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

/**
 * Rules for C++ support in Bazel.
 */
class CcRules private constructor() : RuleSet {
    public override fun init(builder: ConfiguredRuleClassProvider.Builder) {
        val bazelCcModule: BazelCcModule = BazelCcModule()
        builder.addConfigurationFragment(CppConfiguration::class.java)
        builder.addBzlToplevel("CcInfo", net.starlark.java.eval.Starlark.NONE)
        builder.addBzlToplevel("DebugPackageInfo", net.starlark.java.eval.Starlark.NONE)
        builder.addBzlToplevel("CcSharedLibraryInfo", net.starlark.java.eval.Starlark.NONE)
        builder.addBzlToplevel("CcSharedLibraryHintInfo", net.starlark.java.eval.Starlark.NONE)

        builder.addRuleDefinition(object : EmptyRule("cc_toolchain") {})
        builder.addRuleDefinition(object : EmptyRule("cc_toolchain_suite") {})
        builder.addRuleDefinition(CcToolchainAliasRule())
        builder.addRuleDefinition(CcLibcTopAlias())
        builder.addRuleDefinition(object : EmptyRule("cc_binary") {})
        builder.addRuleDefinition(object : EmptyRule("cc_shared_library") {})
        builder.addRuleDefinition(object : EmptyRule("cc_static_library") {})
        builder.addRuleDefinition(object : EmptyRule("cc_test") {})
        builder.addRuleDefinition(object : EmptyRule("cc_library") {})
        builder.addRuleDefinition(object : EmptyRule("cc_import") {})
        builder.addRuleDefinition(object : EmptyRule("fdo_profile") {})
        builder.addRuleDefinition(object : EmptyRule("fdo_prefetch_hints") {})
        builder.addRuleDefinition(object : EmptyRule("memprof_profile") {})
        builder.addRuleDefinition(object : EmptyRule("propeller_optimize") {})
        builder.addStarlarkBuiltinsInternal("cc_common", bazelCcModule)
        builder.addStarlarkBootstrap(CcBootstrap(bazelCcModule))
    }

    public override fun requires(): com.google.common.collect.ImmutableList<RuleSet?> {
        return com.google.common.collect.ImmutableList.of<E?>(CoreRules.INSTANCE, PlatformRules.INSTANCE)
    }

    companion object {
        @kotlin.jvm.JvmField
        val INSTANCE: CcRules = CcRules()
    }
}
