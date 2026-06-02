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
import com.google.devtools.build.lib.collect.nestedset.Depset
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.eval.StarlarkValue

/** Interface for a structured representation of the compilation outputs of a C++ rule.  */
@StarlarkBuiltin(
    name = "CcCompilationOutputs",
    category = DocCategory.BUILTIN,
    documented = true,
    doc = "Helper class containing CC compilation outputs."
)
interface CcCompilationOutputsApi<FileT : FileApi?> : StarlarkValue {
    @get:Throws(EvalException::class)
    @get:StarlarkMethod(
        name = "objects",
        doc = "Non-PIC object files.",
        documented = true,
        structField = true
    )
    val starlarkObjects: Sequence<FileT?>?

    @get:Throws(EvalException::class)
    @get:StarlarkMethod(
        name = "pic_objects",
        doc = "PIC object files.",
        documented = true,
        structField = true
    )
    val starlarkPicObjects: Sequence<FileT?>?

    @StarlarkMethod(name = "temps", documented = false, useStarlarkThread = true)
    @Throws(EvalException::class)
    fun getStarlarkTemps(thread: StarlarkThread?): Depset?

    @get:Throws(EvalException::class)
    @get:StarlarkMethod(name = "_header_tokens", documented = false, structField = true)
    val starlarkHeaderTokens: Sequence<FileT?>?

    @get:Throws(EvalException::class)
    @get:StarlarkMethod(name = "_module_files", documented = false, structField = true)
    val starlarkModuleFiles: Sequence<FileT?>?

    @get:Throws(EvalException::class)
    @get:StarlarkMethod(
        name = "_lto_compilation_context",
        documented = false,
        structField = true
    )
    val ltoCompilationContextForStarlark: Any?

    @get:Throws(EvalException::class)
    @get:StarlarkMethod(name = "_dwo_files", documented = false, structField = true)
    val starlarkDwoFiles: Sequence<FileT?>?

    @get:Throws(EvalException::class)
    @get:StarlarkMethod(name = "_pic_dwo_files", documented = false, structField = true)
    val starlarkPicDwoFiles: Sequence<FileT?>?

    @get:Throws(EvalException::class)
    @get:StarlarkMethod(name = "_gcno_files", documented = false, structField = true)
    val starlarkGcnoFiles: Sequence<FileT?>?

    @get:Throws(EvalException::class)
    @get:StarlarkMethod(name = "_pic_gcno_files", documented = false, structField = true)
    val starlarkPicGcnoFiles: Sequence<FileT?>?
}
