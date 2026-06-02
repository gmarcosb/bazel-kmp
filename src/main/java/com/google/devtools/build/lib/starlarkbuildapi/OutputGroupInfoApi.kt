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

import com.google.devtools.build.lib.starlarkbuildapi.core.ProviderApi
import com.google.devtools.build.lib.starlarkbuildapi.core.StructApi

/** Interface for an info object that indicates what output groups a rule has.  */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "OutputGroupInfo",
    category = com.google.devtools.build.docgen.annot.DocCategory.PROVIDER,
    doc = ("A provider that indicates what output groups a rule has.<br>See <a"
            + " href=\"https://bazel.build/extending/rules#requesting_output_files\">"
            + "Requesting output files</a> for more information.")
)
interface OutputGroupInfoApi : StructApi {
    /** Provider for [OutputGroupInfoApi].  */
    @net.starlark.java.annot.StarlarkBuiltin(name = "Provider", documented = false, doc = "")
    interface OutputGroupInfoApiProvider : ProviderApi {
        @net.starlark.java.annot.StarlarkMethod(
            name = "OutputGroupInfo",
            doc = ("Instantiate this provider with <br><pre class=language-python>OutputGroupInfo(group1 ="
                    + " &lt;files&gt;, group2 = &lt;files&gt;...)</pre>See <a"
                    + " href=\"https://bazel.build/extending/rules#requesting_output_files\">"
                    + "Requesting output files </a> for more information."),
            extraKeywords = net.starlark.java.annot.Param(
                name = "kwargs",
                defaultValue = "{}",
                doc = "Dictionary of arguments."
            ),
            selfCall = true
        )
        @com.google.devtools.build.docgen.annot.StarlarkConstructor
        @Throws(net.starlark.java.eval.EvalException::class)
        fun constructor(kwargs: net.starlark.java.eval.Dict<String?, Any?>?): OutputGroupInfoApi?
    }
}
