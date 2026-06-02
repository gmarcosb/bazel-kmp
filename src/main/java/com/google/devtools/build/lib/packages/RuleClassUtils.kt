// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.packages.RuleClass
import com.google.devtools.build.lib.packages.RuleClassProvider
import com.google.devtools.build.lib.packages.RuleFunction
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions
import com.google.devtools.build.lib.skyframe.StarlarkBuiltinsValue
import com.google.devtools.build.lib.starlarkbuildapi.MacroFunctionApi

/** Rule class utilities.  */
object RuleClassUtils {
    /**
     * Returns the sorted list of all builtin rule classes.
     * 
     * 
     * Unlike [RuleClassProvider.getRuleClassMap], this method returns real Starlark builtins
     * instead of stub overridden native rules.
     * 
     * @param includeMacroWrappedRules if true, include rule classes for rules wrapped in macros.
     */
    fun getBuiltinRuleClasses(
        builtins: StarlarkBuiltinsValue,
        ruleClassProvider: RuleClassProvider,
        includeMacroWrappedRules: Boolean
    ): com.google.common.collect.ImmutableList<RuleClass?> {
        val nativeRuleClasses: com.google.common.collect.ImmutableMap<String?, RuleClass?> =
            ruleClassProvider.getRuleClassMap()
        // The conditional for selecting whether or not to load symbols from @_builtins is the same as
        // in PackageFunction.compileBuildFile
        if (builtins
                .starlarkSemantics
                .get<String?>(BuildLanguageOptions.Companion.EXPERIMENTAL_BUILTINS_BZL_PATH)
                .isEmpty()
        ) {
            return com.google.common.collect.ImmutableList.sortedCopyOf<RuleClass?>(
                java.util.Comparator.comparing<RuleClass?, String?>(java.util.function.Function { obj: RuleClass? -> obj.getName() }),
                nativeRuleClasses.values()
            )
        } else {
            val ruleClasses: java.util.ArrayList<RuleClass?> =
                java.util.ArrayList<RuleClass?>(builtins.predeclaredForBuild.size())
            for (entry in builtins.predeclaredForBuild.entrySet()) {
                if (entry.getValue() is RuleFunction) {
                    ruleClasses.add((entry.getValue() as RuleFunction).getRuleClass())
                } else if ((entry.getValue() is net.starlark.java.eval.StarlarkFunction
                            || entry.getValue() is MacroFunctionApi)
                    && includeMacroWrappedRules
                ) {
                    // entry.getValue() is a macro in @_builtins which overrides a native rule and wraps a
                    // instantiation of a rule target. We cannot get at that main target's rule class
                    // directly, so we attempt heuristics.
                    // Note that we do not rely on the StarlarkFunction or MacroFunction object's name because
                    // the name under which the macro was defined may not match the name under which
                    // @_builtins re-exported it.
                    if (builtins.exportedToJava.containsKey(entry.getKey() + "_rule_function")) {
                        ruleClasses.add(
                            (builtins.exportedToJava.get(entry.getKey() + "_rule_function") as RuleFunction)
                                .getRuleClass()
                        )
                    } else if (nativeRuleClasses.containsKey(entry.getKey())) {
                        ruleClasses.add(nativeRuleClasses.get(entry.getKey()))
                    }
                }
            }
            return com.google.common.collect.ImmutableList.sortedCopyOf<RuleClass?>(
                java.util.Comparator.comparing<RuleClass?, String?>(
                    java.util.function.Function { obj: RuleClass? -> obj.getName() }), ruleClasses
            )
        }
    }
}
