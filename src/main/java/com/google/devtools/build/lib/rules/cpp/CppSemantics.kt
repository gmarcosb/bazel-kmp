// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.RuleContext

/** Pluggable C++ compilation semantics.  */
interface CppSemantics : net.starlark.java.eval.StarlarkValue {
    /** No-op in Bazel  */
    @net.starlark.java.annot.StarlarkMethod(
        name = "validate_layering_check_features",
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = "ctx",
            named = true
        ), net.starlark.java.annot.Param(
            name = "cc_toolchain",
            named = true
        ), net.starlark.java.annot.Param(name = "unsupported_features", named = true)]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun validateLayeringCheckFeaturesForStarlark(
        ruleContext: StarlarkRuleContext,
        ccToolchainInfo: Info,
        unsupportedFeatures: net.starlark.java.eval.Sequence<*>?
    ) {
        try {
            validateLayeringCheckFeatures(
                ruleContext.getRuleContext(),
                ruleContext.getAspectDescriptor(),
                CcToolchainProvider.Companion.wrap(ccToolchainInfo),
                com.google.common.collect.ImmutableSet.copyOf<String?>(
                    net.starlark.java.eval.Sequence.cast<String?>(
                        unsupportedFeatures,
                        String::class.java,
                        "unsupported_features"
                    )
                )
            )
        } catch (e: RuleErrorException) {
            throw net.starlark.java.eval.EvalException(e)
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    fun validateLayeringCheckFeatures(
        ruleContext: RuleContext?,
        aspectDescriptor: AspectDescriptor?,
        ccToolchain: CcToolchainProvider?,
        unsupportedFeatures: com.google.common.collect.ImmutableSet<String?>?
    )

    companion object {
        // Transformed by Copybara on export
        const val RULES_CC_PREFIX: String = "@rules_cc+//"
    }
}
