// Copyright 2020 The Bazel Authors. All rights reserved.
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
import net.starlark.java.eval.StarlarkValue

/**
 * Interface for C++ module maps.
 * 
 * 
 * It is not expected for this to be used externally at this time. This API is experimental and
 * subject to change, and its usage should be restricted to internal packages.
 * 
 * 
 * See javadoc for [com.google.devtools.build.lib.rules.cpp.CcModule].
 */
@StarlarkBuiltin(name = "CcModuleMap", category = DocCategory.TOP_LEVEL_MODULE, documented = false)
interface CppModuleMapApi<FileT : FileApi?> : StarlarkValue {
    @get:Throws(EvalException::class)
    @get:StarlarkMethod(name = "name", documented = false)
    val nameForStarlark: String?

    @get:Throws(EvalException::class)
    @get:StarlarkMethod(name = "file", documented = false)
    val artifactForStarlark: FileT?
}
