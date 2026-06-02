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

import com.google.devtools.build.lib.starlarkbuildapi.StarlarkRuleContextApi
import com.google.devtools.build.lib.starlarkbuildapi.core.StructApi
import com.google.devtools.build.lib.starlarkbuildapi.platform.ExecGroupCollectionApi
import com.google.devtools.build.lib.starlarkbuildapi.platform.ToolchainContextApi

/** Interface for a type containing information about the attributes of a rule.  */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "rule_attributes",
    category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN,
    doc = "Information about attributes of a rule an aspect is applied to."
)
interface StarlarkAttributesCollectionApi : net.starlark.java.eval.StarlarkValue {
    @get:Throws(net.starlark.java.eval.EvalException::class)
    @get:net.starlark.java.annot.StarlarkMethod(
        name = "attr",
        structField = true,
        doc = StarlarkRuleContextApi.Companion.ATTR_DOC
    )
    val attr: StructApi?

    @get:Throws(net.starlark.java.eval.EvalException::class)
    @get:net.starlark.java.annot.StarlarkMethod(
        name = "executable",
        structField = true,
        doc = StarlarkRuleContextApi.Companion.EXECUTABLE_DOC
    )
    val executable: StructApi?

    @get:Throws(net.starlark.java.eval.EvalException::class)
    @get:net.starlark.java.annot.StarlarkMethod(
        name = "file",
        structField = true,
        doc = StarlarkRuleContextApi.Companion.FILE_DOC
    )
    val file: StructApi?

    @get:Throws(net.starlark.java.eval.EvalException::class)
    @get:net.starlark.java.annot.StarlarkMethod(
        name = "files",
        structField = true,
        doc = StarlarkRuleContextApi.Companion.FILES_DOC
    )
    val files: StructApi?

    @get:Throws(net.starlark.java.eval.EvalException::class)
    @get:net.starlark.java.annot.StarlarkMethod(
        name = "kind",
        structField = true,
        doc = "The kind of a rule, such as 'cc_library'"
    )
    val ruleClassName: String?

    @net.starlark.java.annot.StarlarkMethod(
        name = "toolchains",
        structField = true,
        doc = "Toolchains for the default exec group of the rule the aspect is applied to."
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun toolchains(): ToolchainContextApi?

    @net.starlark.java.annot.StarlarkMethod(
        name = "exec_groups",
        structField = true,
        doc = ("A collection of the execution groups available for the rule the aspect is applied to,"
                + " indexed by their names.")
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun execGroups(): ExecGroupCollectionApi?

    @net.starlark.java.annot.StarlarkMethod(
        name = "var",
        structField = true,
        doc = "Dictionary (String to String) of configuration variables."
    )
    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    fun `var`(): net.starlark.java.eval.Dict<String?, String?>?
}
