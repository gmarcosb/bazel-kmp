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
package com.google.devtools.build.lib.starlarkbuildapi.cpp

import com.google.devtools.build.docgen.annot.DocCategory
import com.google.devtools.build.lib.cmdline.Label
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Sequence
import net.starlark.java.eval.StarlarkSemantics
import net.starlark.java.eval.StarlarkValue

/** Either libraries, flags or other files that may be passed to the linker as inputs.  */
@StarlarkBuiltin(
    name = "LinkerInput",
    category = DocCategory.BUILTIN,
    doc = "Either libraries, flags or other files that may be passed to the linker as inputs."
)
interface LinkerInputApi<FileT : FileApi?> : StarlarkValue {
    @get:Throws(EvalException::class)
    @get:StarlarkMethod(
        name = "owner",
        doc = "Returns the owner of this LinkerInput.",
        structField = true
    )
    val starlarkOwner: Label?

    @get:StarlarkMethod(
        name = "user_link_flags",
        doc = "Returns the list of user link flags passed as strings.",
        structField = true
    )
    val starlarkUserLinkFlags: Sequence<String?>?

    @StarlarkMethod(
        name = "libraries", doc = ("Returns the depset of <code>LibraryToLink</code>. May return a list but this is "
                + "deprecated. See #8118."), structField = true, useStarlarkSemantics = true
    )
    fun getStarlarkLibrariesToLink(semantics: StarlarkSemantics?): Sequence<*>?

    @get:StarlarkMethod(
        name = "additional_inputs",
        doc = "Returns the depset of additional inputs, e.g.: linker scripts.",
        structField = true
    )
    val starlarkNonCodeInputs: Sequence<FileT?>?
}
