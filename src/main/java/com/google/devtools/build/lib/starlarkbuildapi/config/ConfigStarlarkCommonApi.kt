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
package com.google.devtools.build.lib.starlarkbuildapi.config

import com.google.devtools.build.lib.cmdline.Label

/** Helper utility containing functions regarding configurations.ss  */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "config_common",
    category = com.google.devtools.build.docgen.annot.DocCategory.TOP_LEVEL_MODULE,
    doc = "Functions for Starlark to interact with Blaze's configurability APIs."
)
interface ConfigStarlarkCommonApi : net.starlark.java.eval.StarlarkValue {
    @get:net.starlark.java.annot.StarlarkMethod(
        name = "FeatureFlagInfo",
        doc = "The key used to retrieve the provider containing config_feature_flag's value.",
        structField = true
    )
    val configFeatureFlagProviderConstructor: ProviderApi?

    @net.starlark.java.annot.StarlarkMethod(
        name = "config_feature_flag_transition",
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = "attribute",
            positional = true,
            named = false,
            doc = "string corresponding to rule attribute to read"
        )]
    )
    fun createConfigFeatureFlagTransitionFactory(attribute: String?): ConfigurationTransitionApi?

    @net.starlark.java.annot.StarlarkMethod(
        name = "toolchain_type",
        doc = "Declare a rule's dependency on a toolchain type.",
        parameters = [net.starlark.java.annot.Param(
            name = "name",
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = Label::class
            )],
            named = false,
            doc = "The toolchain type that is required."
        ), net.starlark.java.annot.Param(
            name = "mandatory",
            allowedTypes = [net.starlark.java.annot.ParamType(type = Boolean::class)],
            named = true,
            positional = false,
            defaultValue = "True",
            doc = "Whether the toolchain type is mandatory or optional."
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun toolchainType(
        name: Any?, mandatory: Boolean, thread: net.starlark.java.eval.StarlarkThread?
    ): StarlarkToolchainTypeRequirement?
}
