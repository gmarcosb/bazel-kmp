// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.starlarkbuildapi

import com.google.devtools.build.lib.starlarkbuildapi.core.ProviderApi
import com.google.devtools.build.lib.starlarkbuildapi.core.StructApi

/**
 * Provider containing any additional environment variables for use when running executables, either
 * in test actions or when executed via the run command.
 */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "RunEnvironmentInfo",
    category = com.google.devtools.build.docgen.annot.DocCategory.PROVIDER,
    doc = ("A provider that can be returned from executable rules to control the environment in"
            + " which their executable is executed.")
)
interface RunEnvironmentInfoApi : StructApi {
    @get:net.starlark.java.annot.StarlarkMethod(
        name = "environment",
        doc = ("A map of string keys and values that represent environment variables and their values."
                + " These will be made available when the target that returns this provider is"
                + " executed, either as a test or via the run command."),
        structField = true
    )
    val environment: MutableMap<String?, String?>?

    @get:net.starlark.java.annot.StarlarkMethod(
        name = "inherited_environment",
        doc = ("A sequence of names of environment variables. These variables are made available with"
                + " their current value taken from the shell environment when the target that returns"
                + " this provider is executed, either as a test or via the run command. If a variable"
                + " is contained in both <code>environment</code> and"
                + " <code>inherited_environment</code>, the value inherited from the shell"
                + " environment will take precedence if set. This is most useful for test rules,"
                + " which run with a hermetic environment under <code>bazel test</code> and can"
                + " use this mechanism to non-hermetically include a variable from the outer"
                + " environment. By contrast, <code>bazel run</code> already forwards the outer"
                + " environment. Note, though, that it may be surprising for an otherwise hermetic"
                + " test to hardcode a non-hermetic dependency on the environment, and that this may"
                + " even accidentally expose sensitive information. Prefer setting the test"
                + " environment explicitly with the <code>--test_env</code> flag, and even then"
                + " prefer to avoid using this flag and instead populate the environment explicitly."),
        structField = true
    )
    val inheritedEnvironment: MutableList<String?>?

    /** Provider for [RunEnvironmentInfoApi].  */
    @net.starlark.java.annot.StarlarkBuiltin(
        name = "Provider",
        category = com.google.devtools.build.docgen.annot.DocCategory.PROVIDER,
        documented = false,
        doc = ""
    )
    interface RunEnvironmentInfoApiProvider : ProviderApi {
        @net.starlark.java.annot.StarlarkMethod(
            name = "RunEnvironmentInfo", doc = "", documented = false, parameters = [net.starlark.java.annot.Param(
                name = "environment",
                defaultValue = "{}",
                named = true,
                positional = true,
                doc = ("A map of string keys and values that represent environment variables and their"
                        + " values. These will be made available when the target that returns this"
                        + " provider is executed, either as a test or via the run command.")
            ), net.starlark.java.annot.Param(
                name = "inherited_environment",
                allowedTypes = [net.starlark.java.annot.ParamType(
                    type = net.starlark.java.eval.Sequence::class,
                    generic1 = String::class
                )],
                defaultValue = "[]",
                named = true,
                positional = true,
                doc = ("A sequence of names of environment variables. These variables are made "
                        + " available with their current value taken from the shell environment"
                        + " when the target that returns this provider is executed, either as a"
                        + " test or via the run command. If a variable is contained in both <code>"
                        + "environment</code> and <code>inherited_environment</code>, the value"
                        + " inherited from the shell environment will take precedence if set.")
            )], selfCall = true
        )
        @com.google.devtools.build.docgen.annot.StarlarkConstructor
        @Throws(net.starlark.java.eval.EvalException::class)
        fun constructor(
            environment: net.starlark.java.eval.Dict<*, *>?,  // <String, String> expected
            inheritedEnvironment: net.starlark.java.eval.Sequence<*>? /* <String> expected */
        ): RunEnvironmentInfoApi?
    }
}
