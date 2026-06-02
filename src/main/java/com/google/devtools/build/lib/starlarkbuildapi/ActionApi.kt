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

/** Interface for actions in Starlark.  */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "Action",
    category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN,
    doc = ("An action created during rule analysis.<p>This object is visible for the purpose of"
            + " testing, and may be obtained from an <code>Actions</code> provider. It is normally"
            + " not necessary to access <code>Action</code> objects or their fields within a rule's"
            + " implementation function. You may instead want to see the <a"
            + " href='https://bazel.build/extending/rules#actions'>Rules page</a> for a general"
            + " discussion of how to use actions when defining custom rules, or the <a"
            + " href='../builtins/actions.html'>API reference</a> for creating actions.<p>Some"
            + " fields of this object are only applicable for certain kinds of actions. Fields that"
            + " are inapplicable are set to <code>None</code>.")
)
interface ActionApi : net.starlark.java.eval.StarlarkValue {
    @get:net.starlark.java.annot.StarlarkMethod(
        name = "mnemonic",
        structField = true,
        doc = "The mnemonic for this action."
    )
    val mnemonic: String?

    @get:net.starlark.java.annot.StarlarkMethod(
        name = "inputs",
        doc = "A set of the input files of this action.",
        structField = true
    )
    val starlarkInputs: Depset?

    @get:net.starlark.java.annot.StarlarkMethod(
        name = "outputs",
        doc = "A set of the output files of this action.",
        structField = true
    )
    val starlarkOutputs: Depset?

    @get:Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    @get:net.starlark.java.annot.StarlarkMethod(
        name = "argv",
        doc = ("For actions created by <a href=\"../builtins/actions.html#run\">ctx.actions.run()</a> or"
                + " <a href=\"../builtins/actions.html#run_shell\">ctx.actions.run_shell()</a>  an"
                + " immutable list of the arguments for the command line to be executed. Note that"
                + " for shell actions the first two arguments will be the shell path and"
                + " <code>\"-c\"</code>."),
        structField = true,
        allowReturnNones = true
    )
    val starlarkArgv: net.starlark.java.eval.Sequence<String?>?

    @get:Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    @get:net.starlark.java.annot.StarlarkMethod(
        name = "args", doc = ("A list of frozen <a href=\"../builtins/Args.html\">Args</a> objects containing"
                + " information about the action arguments. These objects contain accurate argument"
                + " information, including arguments involving expanded action output directories."
                + " However, <a href=\"../builtins/Args.html\">Args</a> objects are not readable in"
                + " the analysis phase. For a less accurate account of arguments which is available"
                + " in the analysis phase, see <a href=\"#argv\">argv</a>. <p>Note that some types of"
                + " actions do not yet support exposure of this field. For such action types, this is"
                + " <code>None</code>."), structField = true, allowReturnNones = true
    )
    val starlarkArgs: net.starlark.java.eval.Sequence<CommandLineArgsApi?>?

    @get:Throws(IOException::class, net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    @get:net.starlark.java.annot.StarlarkMethod(
        name = "content",
        doc = ("For actions created by <a"
                + " href=\"../builtins/actions.html#write\">ctx.actions.write()</a> or <a"
                + " href=\"../builtins/actions.html#expand_template\">ctx.actions.expand_template()</a>,"
                + " the contents of the file to be written, if those contents can be computed during "
                + " the analysis phase. The value is <code>None</code> if the contents cannot be"
                + " determined until the execution phase, such as when a directory in an <a"
                + " href=\"../builtins/Args.html\">Args</a> object needs to be expanded."),
        structField = true,
        allowReturnNones = true
    )
    val starlarkContent: String?

    @get:Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    @get:net.starlark.java.annot.StarlarkMethod(
        name = "substitutions", doc = ("For actions created by <a"
                + " href=\"../builtins/actions.html#expand_template\">ctx.actions.expand_template()</a>,"
                + " an immutable dict holding the substitution mapping."), structField = true, allowReturnNones = true
    )
    val starlarkSubstitutions: net.starlark.java.eval.Dict<String?, String?>?

    @get:net.starlark.java.annot.StarlarkMethod(
        name = "env",
        structField = true,
        doc = ("The 'fixed' environment variables for this action. This includes only environment"
                + " settings which are explicitly set by the action definition, and thus omits"
                + " settings which are only pre-set in the execution environment.")
    )
    val env: net.starlark.java.eval.Dict<String?, String?>?

    @get:net.starlark.java.annot.StarlarkMethod(
        name = "execution_info",
        structField = true,
        doc = ("The execution requirements for this action, set for this action specifically. This is a"
                + " dictionary that maps strings specifying execution info to arbitrary strings."
                + " This is in order to match the structure of execution info in other parts of the"
                + " code base; all relevant info is in the keyset. Returns None if this action does"
                + " not expose execution requirements."),
        allowReturnNones = true,
        enableOnlyWithFlag = BuildLanguageOptions.EXPERIMENTAL_GOOGLE_LEGACY_API
    )
    val executionInfoDict: net.starlark.java.eval.Dict<String?, String?>?
}
