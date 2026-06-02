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

import com.google.devtools.build.lib.starlarkbuildapi.core.ProviderApi
import com.google.devtools.build.lib.starlarkbuildapi.core.StructApi

/** The provider returned from Materialize Rules to materialize dependencies.  */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "MaterializedDepsInfo",
    category = com.google.devtools.build.docgen.annot.DocCategory.PROVIDER,
    doc = "The provider returned from materializer rules to materialize dependencies."
)
interface MaterializedDepsInfoApi : StructApi {
    @get:net.starlark.java.annot.StarlarkMethod(
        name = "deps",
        doc = "The list of dependencies. These may be ConfiguredTarget or DormantDependency objects.",
        structField = true
    )
    val deps: com.google.common.collect.ImmutableList<*>?

    /** Provider for [MaterializedDepsInfoApi] objects.  */
    @net.starlark.java.annot.StarlarkBuiltin(name = "Provider", documented = false, doc = "")
    interface Provider : ProviderApi {
        @net.starlark.java.annot.StarlarkMethod(
            name = NAME,
            selfCall = true,
            doc = "The <code>MaterializedDepsInfo</code> constructor.",
            documented = false,
            parameters = [net.starlark.java.annot.Param(
                name = "deps",
                positional = true,
                named = true,
                allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.Sequence::class)]
            )]
        )
        @com.google.devtools.build.docgen.annot.StarlarkConstructor
        @Throws(net.starlark.java.eval.EvalException::class)
        fun materializedDepsInfo(dependencies: net.starlark.java.eval.Sequence<*>?): MaterializedDepsInfoApi?
    }

    companion object {
        /** The global provider name.  */
        const val NAME: String = "MaterializedDepsInfo"
    }
}
