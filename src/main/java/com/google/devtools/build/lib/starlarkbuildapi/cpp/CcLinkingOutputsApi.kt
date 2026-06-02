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
import com.google.devtools.build.lib.starlarkbuildapi.FileApi
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Sequence
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.eval.StarlarkValue

/** Interface for a structured representation of the linking outputs of a C++ rule.  */
@StarlarkBuiltin(
    name = "CcLinkingOutputs",
    category = DocCategory.BUILTIN,
    documented = true,
    doc = "Helper class containing CC compilation outputs."
)
interface CcLinkingOutputsApi<FileT : FileApi?> : StarlarkValue {
    @get:StarlarkMethod(
        name = "library_to_link",
        structField = true,
        allowReturnNones = true,
        doc = "<code>LibraryToLink</code> for including these outputs in further linking.",
        documented = true
    )
    val libraryToLink: LibraryToLinkApi?

    @get:StarlarkMethod(
        name = "executable",
        structField = true,
        allowReturnNones = true,
        doc = "Represents the linked executable.",
        documented = true
    )
    val executable: FileT?

    @StarlarkMethod(name = "all_lto_artifacts", documented = false, useStarlarkThread = true)
    @Throws(EvalException::class)
    fun getAllLtoArtifactsForStarlark(thread: StarlarkThread?): Sequence<*>?
}
