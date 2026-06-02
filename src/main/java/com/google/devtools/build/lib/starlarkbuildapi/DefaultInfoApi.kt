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

import com.google.devtools.build.lib.collect.nestedset.Depset

/** A provider that gives general information about a target's direct and transitive files.  */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "DefaultInfo", category = com.google.devtools.build.docgen.annot.DocCategory.PROVIDER, doc = """
A provider that gives general information about a target's direct and transitive files. Every rule type has this provider, even if it is not returned explicitly by the rule's implementation function.
<p>
See the <a href="https://bazel.build/extending/rules">rules</a> page for extensive guides on how to use this provider.
</p>

""".trimIndent()
)
interface DefaultInfoApi : StructApi {
    @get:net.starlark.java.annot.StarlarkMethod(
        name = "files", doc = ("A <a href='../builtins/depset.html'><code>depset</code></a> of <a"
                + " href='../builtins/File.html'><code>File</code></a> objects representing the"
                + " default outputs to build when this target is specified on the bazel command line."
                + " By default it is all predeclared outputs."), structField = true, allowReturnNones = true
    )
    val files: Depset?

    @get:net.starlark.java.annot.StarlarkMethod(
        name = "files_to_run",
        doc = ("A <a href='../providers/FilesToRunProvider.html'><code>FilesToRunProvider</code></a>"
                + " object containing information about the executable and runfiles of the target."),
        structField = true,
        allowReturnNones = true
    )
    val filesToRun: FilesToRunProviderApi<*>?

    @get:net.starlark.java.annot.StarlarkMethod(
        name = "data_runfiles",
        doc = ("runfiles descriptor describing the files that this target needs when run in the "
                + "condition that it is a <code>data</code> dependency attribute. Under most "
                + "circumstances, use the <code>default_runfiles</code> parameter instead. "
                + "See <a href='https://bazel.build/extending/rules#runfiles_features_to_avoid'>"
                + "\"runfiles features to avoid\"</a> for details. "),
        structField = true,
        allowReturnNones = true
    )
    val dataRunfiles: RunfilesApi?

    @get:net.starlark.java.annot.StarlarkMethod(
        name = "default_runfiles",
        doc = ("runfiles descriptor describing the files that this target needs when run "
                + "(via the <code>run</code> command or as a tool dependency)."),
        structField = true,
        allowReturnNones = true
    )
    val defaultRunfiles: RunfilesApi?

    /** Provider for [DefaultInfoApi].  */
    @net.starlark.java.annot.StarlarkBuiltin(name = "Provider", documented = false, doc = "")
    interface DefaultInfoApiProvider<RunfilesT : RunfilesApi?, FileT : FileApi?>
        : ProviderApi {
        @net.starlark.java.annot.StarlarkMethod(
            name = "DefaultInfo",
            doc = "The <code>DefaultInfo</code> constructor.",
            parameters = [net.starlark.java.annot.Param(
                name = "files",
                allowedTypes = [net.starlark.java.annot.ParamType(type = Depset::class), net.starlark.java.annot.ParamType(
                    type = net.starlark.java.eval.NoneType::class
                )],
                named = true,
                positional = false,
                defaultValue = "None",
                doc = ("A <a href='../builtins/depset.html'><code>depset</code></a> of <a"
                        + " href='../builtins/File.html'><code>File</code></a> objects representing"
                        + " the default outputs to build when this target is specified on the bazel"
                        + " command line. By default it is all predeclared outputs.")
            ), net.starlark.java.annot.Param(
                name = "runfiles",
                allowedTypes = [net.starlark.java.annot.ParamType(type = RunfilesApi::class), net.starlark.java.annot.ParamType(
                    type = net.starlark.java.eval.NoneType::class
                )],
                named = true,
                positional = false,
                defaultValue = "None",
                doc = """
<a href="../builtins/runfiles.html"><code>runfiles</code></a> descriptor describing the files that this target needs when run (e.g. via the <code>run</code> command or as a tool dependency for an action).

""".trimIndent()
            ), net.starlark.java.annot.Param(
                name = "data_runfiles",
                allowedTypes = [net.starlark.java.annot.ParamType(type = RunfilesApi::class), net.starlark.java.annot.ParamType(
                    type = net.starlark.java.eval.NoneType::class
                )],
                named = true,
                positional = false,
                defaultValue = "None",
                doc = (DEPRECATED_RUNFILES_PARAMETER_WARNING
                        + "runfiles descriptor describing the runfiles this target needs to run "
                        + "when it is a dependency via the <code>data</code> attribute.")
            ), net.starlark.java.annot.Param(
                name = "default_runfiles",
                allowedTypes = [net.starlark.java.annot.ParamType(type = RunfilesApi::class), net.starlark.java.annot.ParamType(
                    type = net.starlark.java.eval.NoneType::class
                )],
                named = true,
                positional = false,
                defaultValue = "None",
                doc = (DEPRECATED_RUNFILES_PARAMETER_WARNING
                        + "runfiles descriptor describing the runfiles this target needs to run "
                        + "when it is a dependency via any attribute other than the "
                        + "<code>data</code> attribute.")
            ), net.starlark.java.annot.Param(
                name = "executable",
                allowedTypes = [net.starlark.java.annot.ParamType(type = FileApi::class), net.starlark.java.annot.ParamType(
                    type = net.starlark.java.eval.NoneType::class
                )],
                named = true,
                positional = false,
                defaultValue = "None",
                doc = ("If this rule is marked <a"
                        + " href='../globals/bzl.html#rule.executable'><code>executable</code></a> or"
                        + " <a href='../globals/bzl.html#rule.test'><code>test</code></a>, this is a"
                        + " <a href='../builtins/File.html'><code>File</code></a> object representing"
                        + " the file that should be executed to run the target. By default it is the"
                        + " predeclared output <code>ctx.outputs.executable</code> but it is"
                        + " recommended to pass another file (either predeclared or not) explicitly.")
            )],
            selfCall = true,
            useStarlarkThread = true
        )
        @com.google.devtools.build.docgen.annot.StarlarkConstructor
        @Throws(net.starlark.java.eval.EvalException::class)
        fun constructor(
            files: Any?,
            runfiles: Any?,
            dataRunfiles: Any?,
            defaultRunfiles: Any?,
            executable: Any?,
            thread: net.starlark.java.eval.StarlarkThread?
        ): DefaultInfoApi?
    }

    companion object {
        val DEPRECATED_RUNFILES_PARAMETER_WARNING: String =
            ("<p><b>It is recommended that you avoid using this parameter (see "
                    + "<a href='https://bazel.build/extending/rules#runfiles_features_to_avoid'>"
                    + "\"runfiles features to avoid\"</a>)</b></p> ")
    }
}
