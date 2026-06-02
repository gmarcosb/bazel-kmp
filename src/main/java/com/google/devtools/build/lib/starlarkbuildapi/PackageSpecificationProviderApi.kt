// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.Label

/** Provider which describes a set of transitive package specifications used in package groups.  */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "PackageSpecificationInfo",
    doc = "Information about transitive package specifications used in package groups.",
    category = com.google.devtools.build.docgen.annot.DocCategory.PROVIDER
)
interface PackageSpecificationProviderApi : StructApi {
    @net.starlark.java.annot.StarlarkMethod(
        name = "contains",
        doc = "Checks if a target exists in a package group.",
        parameters = [net.starlark.java.annot.Param(
            name = "target",
            positional = true,
            doc = "A target which is checked if it exists inside the package group.",
            allowedTypes = [net.starlark.java.annot.ParamType(type = Label::class), net.starlark.java.annot.ParamType(
                type = String::class
            )]
        )]
    )
    @Throws(net.starlark.java.eval.EvalException::class, LabelSyntaxException::class)
    fun targetInAllowlist(target: Any?): Boolean
}
