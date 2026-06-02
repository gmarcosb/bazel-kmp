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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.analysis.ConfiguredTarget

/** Information describing C++ toolchain derived from CROSSTOOL file.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class CcToolchainConfigInfo internal constructor(actual: StarlarkInfo) {
    private val actual: StarlarkInfo

    init {
        this.actual = actual
    }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val actionConfigs: com.google.common.collect.ImmutableList<ActionConfig?>
        get() {
            val execOs: com.google.devtools.build.lib.util.OS = com.google.devtools.build.lib.util.OS.valueOf(
                actual.getValue(
                    "_exec_os_DO_NOT_USE",
                    String::class.java
                )
            )
            val actionConfigBuilder: com.google.common.collect.ImmutableList.Builder<ActionConfig?> =
                com.google.common.collect.ImmutableList.builder<ActionConfig?>()
            for (actionConfig in net.starlark.java.eval.Sequence.cast<T?>(
                actual.getValue("_action_configs_DO_NOT_USE"), StarlarkInfo::class.java, "_action_configs"
            )) {
                actionConfigBuilder.add(CcToolchainFeaturesLib.actionConfigFromStarlark(actionConfig, execOs))
            }
            return actionConfigBuilder.build()
        }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val features: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Feature?>
        get() {
            val featureBuilder: com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Feature?> =
                com.google.common.collect.ImmutableList.builder<com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Feature?>()
            for (feature in net.starlark.java.eval.Sequence.cast<T?>(
                actual.getValue("_features_DO_NOT_USE"),
                StarlarkInfo::class.java,
                "_features"
            )) {
                featureBuilder.add(CcToolchainFeaturesLib.featureFromStarlark(feature))
            }
            return featureBuilder.build()
        }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val artifactNamePatterns: ArtifactNamePatternMapper?
        get() {
            val artifactNamePatternBuilder: com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ArtifactNamePatternMapper.Builder =
                com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ArtifactNamePatternMapper.Builder()
            for (artifactNamePattern in net.starlark.java.eval.Sequence.cast<T?>(
                actual.getValue("_artifact_name_patterns_DO_NOT_USE"),
                StarlarkInfo::class.java,
                "_artifact_name_patterns"
            )) {
                CcToolchainFeaturesLib.artifactNamePatternFromStarlark(
                    artifactNamePattern,
                    ArtifactNamePatternAdder { category: ArtifactCategory?, prefix: String?, extension: String? ->
                        artifactNamePatternBuilder.addOverride(
                            category,
                            prefix,
                            extension
                        )
                    })
            }
            return artifactNamePatternBuilder.build()
        }

    /** Provider class for [CcToolchainConfigInfo] objects.  */
    class Provider private constructor() : StarlarkProviderWrapper<CcToolchainConfigInfo?>(
        BzlLoadValue.keyForBuild(
            Label.parseCanonicalUnchecked(
                "//third_party/bazel_rules/rules_cc/cc/private/toolchain_config:cc_toolchain_config_info.bzl"
            )
        ),
        "CcToolchainConfigInfo"
    ) {
        @Throws(RuleErrorException::class)
        public override fun wrap(value: Info?): CcToolchainConfigInfo {
            return CcToolchainConfigInfo(value as StarlarkInfo?)
        }
    }

    /** Provider class for [CcToolchainConfigInfo] objects.  */
    class RulesCcProvider private constructor() : StarlarkProviderWrapper<CcToolchainConfigInfo?>(
        BzlLoadValue.keyForBuild(
            Label.parseCanonicalUnchecked(
                "@rules_cc+//cc/private/toolchain_config:cc_toolchain_config_info.bzl"
            )
        ),
        "CcToolchainConfigInfo"
    ) {
        @Throws(RuleErrorException::class)
        public override fun wrap(value: Info?): CcToolchainConfigInfo {
            return CcToolchainConfigInfo(value as StarlarkInfo?)
        }
    }

    companion object {
        /** Singleton provider instance for [CcToolchainConfigInfo].  */
        val PROVIDER: Provider = com.google.devtools.build.lib.rules.cpp.CcToolchainConfigInfo.Provider()

        val RULES_CC_PROVIDER: RulesCcProvider =
            com.google.devtools.build.lib.rules.cpp.CcToolchainConfigInfo.RulesCcProvider()

        @Throws(RuleErrorException::class)
        fun get(target: ConfiguredTarget): CcToolchainConfigInfo? {
            var info: CcToolchainConfigInfo? = target.get(PROVIDER)
            if (info == null) {
                info = target.get(RULES_CC_PROVIDER)
            }
            return info
        }
    }
}
