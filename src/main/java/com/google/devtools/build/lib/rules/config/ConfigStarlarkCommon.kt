// Copyright 2017 The Bazel Authors. All rights reserved.
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
// limitations under the License
package com.google.devtools.build.lib.rules.config

import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement

/** Starlark namespace used to interact with Blaze's configurability APIs.  */
class ConfigStarlarkCommon : ConfigStarlarkCommonApi {
    val configFeatureFlagProviderConstructor: Provider?
        get() = ConfigFeatureFlagProvider.Companion.STARLARK_CONSTRUCTOR

    override fun createConfigFeatureFlagTransitionFactory(attribute: String?): ConfigurationTransitionApi {
        return ConfigFeatureFlagTransitionFactory(attribute)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun toolchainType(
        name: Any, mandatory: Boolean, thread: net.starlark.java.eval.StarlarkThread?
    ): StarlarkToolchainTypeRequirement {
        val label: Label?
        if (name is Label) {
            label = name
        } else if (name is String) {
            val converter: LabelConverter = LabelConverter.forBzlEvaluatingThread(thread)
            try {
                label = converter.convert(name)
            } catch (e: LabelSyntaxException) {
                throw net.starlark.java.eval.Starlark.errorf(
                    "Unable to parse toolchain_type label '%s': %s", name, e.getMessage()
                )
            }
        } else {
            throw net.starlark.java.eval.Starlark.errorf(
                "config_common.toolchain_type() takes a Label or String, and instead got a %s",
                name.javaClass.getSimpleName()
            )
        }

        return ToolchainTypeRequirement.builder(label).mandatory(mandatory).build()
    }
}
