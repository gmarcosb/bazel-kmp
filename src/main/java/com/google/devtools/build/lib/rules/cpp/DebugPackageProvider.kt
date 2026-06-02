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
 * Provides the binary artifact and its associated .dwp files, if fission is enabled. If Fission
 * ({@url https://gcc.gnu.org/wiki/DebugFission}) is not enabled, the dwp file will be null.
 */
class DebugPackageProvider private constructor(starlarkInfo: StarlarkInfo) {
    private val starlarkInfo: StarlarkInfo

    init {
        this.starlarkInfo = starlarkInfo
    }

    @get:Throws(RuleErrorException::class)
    val targetLabel: Label
        /** Returns the label for the *_binary target.  */
        get() {
            try {
                return starlarkInfo.getValue("target_label", Label::class.java)
            } catch (e: net.starlark.java.eval.EvalException) {
                throw RuleErrorException(e)
            }
        }

    @get:Throws(RuleErrorException::class)
    val strippedArtifact: Artifact
        /** Returns the stripped file (the explicit ".stripped" target).  */
        get() {
            try {
                return starlarkInfo.getNoneableValue("stripped_file", Artifact::class.java)
            } catch (e: net.starlark.java.eval.EvalException) {
                throw RuleErrorException(e)
            }
        }

    @get:Throws(RuleErrorException::class)
    val unstrippedArtifact: Artifact
        /** Returns the unstripped file (the default executable target).  */
        get() {
            try {
                return starlarkInfo.getValue("unstripped_file", Artifact::class.java)
            } catch (e: net.starlark.java.eval.EvalException) {
                throw RuleErrorException(e)
            }
        }

    @get:Throws(RuleErrorException::class)
    val dwpArtifact: Artifact?
        /** Returns the .dwp file (for fission builds) or null if --fission=no.  */
        get() {
            try {
                return starlarkInfo.getNoneableValue("dwp_file", Artifact::class.java)
            } catch (e: net.starlark.java.eval.EvalException) {
                throw RuleErrorException(e)
            }
        }

    /** Provider class for [DebugPackageProvider] objects.  */
    class RulesCcProvider : StarlarkProviderWrapper<DebugPackageProvider?>(
        BzlLoadValue.keyForBuild(
            Label.parseCanonicalUnchecked(
                CppSemantics.Companion.RULES_CC_PREFIX + "cc/private:debug_package_info.bzl"
            )
        ),
        "DebugPackageInfo"
    ) {
        @Throws(RuleErrorException::class)
        public override fun wrap(value: Info?): DebugPackageProvider {
            return DebugPackageProvider(value as StarlarkInfo?)
        }
    }

    companion object {
        val RULES_CC_PROVIDER: RulesCcProvider =
            com.google.devtools.build.lib.rules.cpp.DebugPackageProvider.RulesCcProvider()

        @Throws(RuleErrorException::class)
        fun get(target: TransitiveInfoCollection): DebugPackageProvider {
            return target.get(RULES_CC_PROVIDER)
        }
    }
}
