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
package com.google.devtools.build.lib.starlarkbuildapi

import com.google.devtools.build.lib.collect.nestedset.Depset

/** An interface for a set of runfiles.  */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "runfiles", category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN, doc = """
A container of information regarding a set of files required at runtime by an executable. This object should be passed via <a href="../providers/DefaultInfo.html"><code>DefaultInfo</code></a> in order to tell the build system about the runfiles needed by the outputs produced by the rule.
<p>
    See <a href="https://bazel.build/extending/rules#runfiles">runfiles guide</a> for details. </p>

""".trimIndent()
)
interface RunfilesApi : net.starlark.java.eval.StarlarkValue {
    @get:net.starlark.java.annot.StarlarkMethod(
        name = "files",
        doc = "Returns the set of runfiles as files.",
        structField = true
    )
    val artifactsForStarlark: Depset?

    @get:net.starlark.java.annot.StarlarkMethod(
        name = "symlinks",
        doc = "Returns the set of symlinks.",
        structField = true
    )
    val symlinksForStarlark: Depset?

    @get:net.starlark.java.annot.StarlarkMethod(
        name = "root_symlinks",
        doc = "Returns the set of root symlinks.",
        structField = true
    )
    val rootSymlinksForStarlark: Depset?

    @get:net.starlark.java.annot.StarlarkMethod(
        name = "empty_filenames",
        doc = "Returns names of empty files to create.",
        structField = true
    )
    val emptyFilenamesForStarlark: Depset?

    @net.starlark.java.annot.StarlarkMethod(
        name = "merge",
        doc = """
Returns a new runfiles object that includes all the contents of this one and the argument. <p>
<i>Note:</i> When you have many runfiles objects to merge, use <a href="#merge_all"><code>merge_all()</code></a> rather than calling <code>merge</code> in a loop. This avoids constructing deep depset structures which can cause build failures. </p>

""".trimIndent(),
        parameters = [net.starlark.java.annot.Param(
            name = "other",
            positional = true,
            named = false,
            doc = "The runfiles object to merge into this."
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun merge(other: RunfilesApi?, thread: net.starlark.java.eval.StarlarkThread?): RunfilesApi?

    @net.starlark.java.annot.StarlarkMethod(
        name = "merge_all",
        doc = """
Returns a new runfiles object that includes all the contents of this one and of the runfiles objects in the argument.

""".trimIndent(),
        parameters = [net.starlark.java.annot.Param(
            name = "other",
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = RunfilesApi::class
            )],
            positional = true,
            named = false,
            doc = "The sequence of runfiles objects to merge into this."
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun mergeAll(
        sequence: net.starlark.java.eval.Sequence<*>?,
        thread: net.starlark.java.eval.StarlarkThread?
    ): RunfilesApi?
}
