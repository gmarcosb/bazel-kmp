// Copyright 2019 The Bazel Authors. All rights reserved.
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

/**
 * Wrapper for [FeatureConfiguration], [CppConfiguration], and [BuildOptions].
 * 
 * 
 * Instances are created in Starlark by cc_common.configure_features(ctx, cc_toolchain), and
 * passed around pretending to be [FeatureConfiguration]. Then when the need arises, we get
 * the [CppConfiguration] and [BuildOptions] from it and use it in times when
 * configuration of cc_toolchain is different than configuration of the rule depending on it.
 */
// TODO(b/129045294): Remove once cc_toolchain has target configuration.
class FeatureConfigurationForStarlark private constructor(featureConfiguration: FeatureConfiguration?) :
    FeatureConfigurationApi {
    private val featureConfiguration: FeatureConfiguration

    init {
        this.featureConfiguration =
            com.google.common.base.Preconditions.checkNotNull<FeatureConfiguration>(featureConfiguration)
    }

    fun getFeatureConfiguration(): FeatureConfiguration {
        return featureConfiguration
    }

    override fun str(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        printer.append("<FeatureConfiguration(")
        printer.append("ENABLED:")
        printer.append(com.google.common.base.Joiner.on(", ").join(featureConfiguration.getEnabledFeatureNames()))
        printer.append(";REQUESTED:")
        printer.append(com.google.common.base.Joiner.on(", ").join(featureConfiguration.getRequestedFeatures()))
        printer.append(")>")
    }

    override fun debugPrint(printer: net.starlark.java.eval.Printer, thread: net.starlark.java.eval.StarlarkThread?) {
        printer.append("<FeatureConfiguration(")
        printer.append(com.google.common.base.Joiner.on(", ").join(featureConfiguration.getEnabledFeatureNames()))
        printer.append(")>")
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "is_requested",
        parameters = [net.starlark.java.annot.Param(name = "feature")],
        documented = false,
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun isRequested(feature: String?, thread: net.starlark.java.eval.StarlarkThread?): Boolean {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return featureConfiguration.getRequestedFeatures().contains(feature)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "is_enabled",
        parameters = [net.starlark.java.annot.Param(name = "feature")],
        documented = false,
        useStarlarkThread = true
    ) // TODO(b/339328480): collect all feature names in a single location
    @Throws(net.starlark.java.eval.EvalException::class)
    fun isEnabled(feature: String?, thread: net.starlark.java.eval.StarlarkThread?): Boolean {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return featureConfiguration.isEnabled(feature)
    }

    companion object {
        fun from(featureConfiguration: FeatureConfiguration?): FeatureConfigurationForStarlark {
            return FeatureConfigurationForStarlark(featureConfiguration)
        }
    }
}
