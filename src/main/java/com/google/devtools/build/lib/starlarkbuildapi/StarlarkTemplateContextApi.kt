// Copyright 2025 The Bazel Authors. All rights reserved.
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

/**
 * Context object that is passed to the ctx.actions.map_directory implementation to allow for the
 * creation of actions.
 */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "template_ctx",
    category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN,
    doc = "A context object that is passed to the action template expansion function."
)
interface StarlarkTemplateContextApi : net.starlark.java.eval.StarlarkValue {
    @net.starlark.java.annot.StarlarkMethod(
        name = "declare_file",
        doc = ("Declares that implementation creates a file with the given filename within the specified"
                + " directory.<p>Remember that in addition to declaring a file, you must separately"
                + " create an action that emits the file. Creating that action will require passing"
                + " the returned <code>File</code> object to the action's construction"
                + " function."),
        parameters = [net.starlark.java.annot.Param(
            name = "filename",
            doc = "The relative path of the file within the directory."
        ), net.starlark.java.annot.Param(
            name = "directory",
            doc = "The directory in which the file should be created.",
            named = true,
            positional = false
        )]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun declareFile(filename: String?, directory: FileApi?): FileApi?

    @net.starlark.java.annot.StarlarkMethod(
        name = "declare_subdirectory",
        doc = ("Declares that implementation creates a subdirectory with the given name within the"
                + " specified directory.<p>Remember that in addition to declaring a subdirectory,"
                + " you must separately create an action that emits the subdirectory. Creating that"
                + " action will require passing the returned <code>File</code> object to the"
                + " action's construction function."),
        parameters = [net.starlark.java.annot.Param(
            name = "subdirectory",
            doc = "The relative path of the subdirectory within the directory."
        ), net.starlark.java.annot.Param(
            name = "directory",
            doc = "The directory in which the subdirectory should be created.",
            named = true,
            positional = false
        )]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun declareSubdirectory(subdirectory: String?, directory: FileApi?): FileApi?

    @net.starlark.java.annot.StarlarkMethod(
        name = "args",
        doc = "Returns an Args object that can be used to build memory-efficient command lines.",
        useStarlarkThread = true
    )
    fun args(thread: net.starlark.java.eval.StarlarkThread?): CommandLineArgsApi?

    @net.starlark.java.annot.StarlarkMethod(
        name = "run",
        doc = "Creates an action that runs an executable.",
        parameters = [net.starlark.java.annot.Param(
            name = "outputs",
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = FileApi::class
            )],
            named = true,
            positional = false,
            doc = "List of the output files of the action."
        ), net.starlark.java.annot.Param(
            name = "inputs",
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = FileApi::class
            ), net.starlark.java.annot.ParamType(type = Depset::class)],
            defaultValue = "[]",
            named = true,
            positional = false,
            doc = "List or depset of the input files of the action."
        ), net.starlark.java.annot.Param(
            name = "executable",
            allowedTypes = [net.starlark.java.annot.ParamType(type = FileApi::class), net.starlark.java.annot.ParamType(
                type = String::class
            ), net.starlark.java.annot.ParamType(type = FilesToRunProviderApi::class)],
            named = true,
            positional = false,
            doc = "The executable file to be called by the action."
        ), net.starlark.java.annot.Param(
            name = "tools",
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.Sequence::class), net.starlark.java.annot.ParamType(
                type = Depset::class
            ), net.starlark.java.annot.ParamType(type = net.starlark.java.eval.NoneType::class)],
            defaultValue = "None",
            named = true,
            positional = false,
            doc = StarlarkActionFactoryApi.Companion.TOOLS_ARG_DOC
        ), net.starlark.java.annot.Param(
            name = "arguments",
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.Sequence::class)],
            defaultValue = "[]",
            named = true,
            positional = false,
            doc = ("Command line arguments of the action. "
                    + "Must be a list of strings or "
                    + "<a href=\"#args\"><code>actions.args()</code></a> objects.")
        ), net.starlark.java.annot.Param(
            name = "progress_message",
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            named = true,
            positional = false,
            defaultValue = "None",
            doc = "Progress message to show to the user during the build."
        )]
    )
    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    fun run(
        outputs: net.starlark.java.eval.Sequence<*>?,
        inputs: Any?,
        executableUnchecked: Any?,
        toolsUnchecked: Any?,
        arguments: net.starlark.java.eval.Sequence<*>?,
        progressMessage: Any?
    )
}
