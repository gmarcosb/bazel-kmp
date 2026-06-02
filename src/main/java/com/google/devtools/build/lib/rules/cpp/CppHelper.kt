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

import com.google.devtools.build.lib.actions.Artifact

/**
 * Helper class for functionality shared by cpp related rules.
 * 
 * 
 * This class can be used only after the loading phase.
 */
object CppHelper {
    val OBJS: PathFragment? = PathFragment.create("_objs")

    /** Returns C++ toolchain, using toolchain resolution  */
    @Throws(RuleErrorException::class)
    fun getToolchain(ruleContext: RuleContext): CcToolchainProvider? {
        var toolchainInfo: ToolchainInfo? =
            ruleContext.getToolchainInfo(Label.parseCanonicalUnchecked("//tools/cpp:toolchain_type"))
        if (toolchainInfo == null) {
            toolchainInfo =
                ruleContext.getToolchainInfo(
                    Label.parseCanonicalUnchecked("@bazel_tools//tools/cpp:toolchain_type")
                )
        }
        if (toolchainInfo == null) {
            throw ruleContext.throwWithRuleError(
                "Unable to find a CC toolchain using toolchain resolution. Did you properly set"
                        + " --platforms?"
            )
        }
        try {
            return CcToolchainProvider.Companion.wrap(toolchainInfo.getValue("cc") as Info?)
        } catch (e: net.starlark.java.eval.EvalException) {
            // There is not actually any reason for toolchainInfo.getValue to throw an exception.
            throw ruleContext.throwWithRuleError(
                "Unexpected eval exception from toolchainInfo.getValue('cc')"
            )
        }
    }

    /** Returns the directory where object files are created.  */
    private fun getObjDirectory(ruleLabel: Label?, siblingRepositoryLayout: Boolean): PathFragment {
        return AnalysisUtils.getUniqueDirectory(ruleLabel, OBJS, siblingRepositoryLayout)
    }

    // LINT.IfChange
    /** Returns whether binaries must be compiled with position independent code.  */
    fun usePicForBinaries(
        cppConfiguration: CppConfiguration, featureConfiguration: FeatureConfiguration
    ): Boolean {
        return cppConfiguration.forcePic()
                || (CcToolchainProvider.Companion.usePicForDynamicLibraries(cppConfiguration, featureConfiguration)
                && (cppConfiguration.getCompilationMode() !== CompilationMode.OPT
                || featureConfiguration.isEnabled(CppRuleClasses.PREFER_PIC_FOR_OPT_BINARIES)))
    }

    // LINT.ThenChange(//src/main/starlark/builtins_bzl/common/cc/cc_helper_internal.bzl)
    fun getCompileOutputArtifact(
        actionConstructionContext: ActionConstructionContext,
        label: Label,
        outputName: String?,
        config: BuildConfigurationValue
    ): Artifact {
        val objectDir: PathFragment = getObjDirectory(label, config.isSiblingRepositoryLayout())
        return actionConstructionContext.getDerivedArtifact(
            objectDir.getRelative(outputName), config.getBinDirectory(label.getRepository())
        )
    }
}
