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
package com.google.devtools.build.lib.starlarkbuildapi

import com.google.devtools.build.lib.starlarkbuildapi.FileRootApi

/** Interface for a configuration object which holds information about the build environment.  */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "configuration",
    category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN,
    doc = ("This object holds information about the environment in which the build is running. See the"
            + " <a href='https://bazel.build/extending/rules#configurations'>Rules page</a> for"
            + " more on the general concept of configurations.")
)
interface BuildConfigurationApi : net.starlark.java.eval.StarlarkValue {
    @get:Deprecated("")
    @get:net.starlark.java.annot.StarlarkMethod(name = "bin_dir", structField = true, documented = false)
    val binDir: FileRootApi?

    @get:Deprecated("")
    @get:net.starlark.java.annot.StarlarkMethod(name = "genfiles_dir", structField = true, documented = false)
    val genfilesDir: FileRootApi?

    @get:net.starlark.java.annot.StarlarkMethod(
        name = "host_path_separator",
        structField = true,
        doc = "Returns the separator for PATH environment variable, which is ':' on Unix."
    )
    val hostPathSeparator: String?

    @get:Deprecated("")
    @get:net.starlark.java.annot.StarlarkMethod(
        name = "default_shell_env",
        structField = true,
        doc = ("A dictionary representing the static local shell environment. It maps variables "
                + "to their values (strings).")
    )
    val localShellEnvironment: com.google.common.collect.ImmutableMap<String?, String?>?

    @get:net.starlark.java.annot.StarlarkMethod(
        name = "test_env",
        structField = true,
        doc = ("A dictionary containing user-specified test environment variables and their values, as"
                + " set by the <code>--test_env</code> options. DO NOT USE! This is not the complete"
                + " environment!")
    )
    val testEnv: com.google.common.collect.ImmutableMap<String?, String?>?

    @get:net.starlark.java.annot.StarlarkMethod(
        name = "coverage_enabled",
        structField = true,
        doc = ("A boolean that tells whether code coverage is enabled for this run. Note that this does"
                + " not compute whether a specific rule should be instrumented for code coverage data"
                + " collection. For that, see the <a"
                + " href=\"../builtins/ctx.html#coverage_instrumented\"><code>ctx.coverage_instrumented</code></a>"
                + " function.")
    )
    val isCodeCoverageEnabled: Boolean

    @get:net.starlark.java.annot.StarlarkMethod(
        name = "short_id", structField = true, doc = """
          A short identifier for this configuration understood by the <code>config</code> and </code>query</code> subcommands. <p>Use this to distinguish different configurations for the same target in a way that is friendly to humans and tool usage, for example in an aspect used by an IDE. Keep in mind the following caveats: <ul> <li>The value may differ across Bazel versions, including patch releases. <li>The value encodes the value of <b>every</b> flag, including those that aren't otherwise relevant for the current target and may thus invalidate caches more frequently. </ul>
          
          """.trimIndent()
    )
    val shortId: String?

    @net.starlark.java.annot.StarlarkMethod(name = "stamp_binaries", documented = false, useStarlarkThread = true)
    @Throws(net.starlark.java.eval.EvalException::class)
    fun stampBinariesForStarlark(thread: net.starlark.java.eval.StarlarkThread?): Boolean

    @get:net.starlark.java.annot.StarlarkMethod(
        name = "is_tool_configuration",
        doc = "Returns true when building in the tool (exec) configuration."
    )
    val isToolConfiguration: Boolean

    @net.starlark.java.annot.StarlarkMethod(
        name = "has_separate_genfiles_directory",
        documented = false,
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun hasSeparateGenfilesDirectoryForStarlark(thread: net.starlark.java.eval.StarlarkThread?): Boolean

    @net.starlark.java.annot.StarlarkMethod(
        name = "is_sibling_repository_layout",
        documented = false,
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun isSiblingRepositoryLayoutForStarlark(thread: net.starlark.java.eval.StarlarkThread?): Boolean

    @net.starlark.java.annot.StarlarkMethod(name = "runfiles_enabled", documented = false, useStarlarkThread = true)
    @Throws(net.starlark.java.eval.EvalException::class)
    fun runfilesEnabledForStarlark(thread: net.starlark.java.eval.StarlarkThread?): Boolean

    @net.starlark.java.annot.StarlarkMethod(name = "disabled_features", documented = false, useStarlarkThread = true)
    @Throws(net.starlark.java.eval.EvalException::class)
    fun getDisabledFeatures(thread: net.starlark.java.eval.StarlarkThread?): net.starlark.java.eval.StarlarkSet<String?>?
}
