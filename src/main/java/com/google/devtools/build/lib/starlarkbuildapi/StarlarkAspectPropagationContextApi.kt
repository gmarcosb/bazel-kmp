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

import com.google.devtools.build.lib.cmdline.Label

/** Interface for the context object given to aspect propagation functions.  */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "propagation_ctx",
    category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN,
    doc = ("A context object that is passed to the <code>propagation_predicate</code>,"
            + " <code>attr_aspects</code> and <code>toolchains_aspects</code> functions of"
            + " aspects. It provides access to the information needed to determine whether the"
            + " aspect should be propagated to the target and what attributes or toolchain types it"
            + " should be propagated to next.")
)
interface StarlarkAspectPropagationContextApi : net.starlark.java.eval.StarlarkValue {
    @get:Throws(net.starlark.java.eval.EvalException::class)
    @get:net.starlark.java.annot.StarlarkMethod(
        name = "attr",
        structField = true,
        doc = ("A struct to access only the public parameters of the aspect. The keys and values of the"
                + " struct are the parameters names and values.")
    )
    val attr: StructApi?

    @get:Throws(net.starlark.java.eval.EvalException::class)
    @get:net.starlark.java.annot.StarlarkMethod(
        name = "rule",
        structField = true,
        doc = "Allows access to the details of the rule."
    )
    val rule: StarlarkAspectPropagationRuleApi?

    /** Interface for the rule details provided to the aspect propagation functions.  */
    interface StarlarkAspectPropagationRuleApi : net.starlark.java.eval.StarlarkValue {
        @get:Throws(net.starlark.java.eval.EvalException::class)
        @get:net.starlark.java.annot.StarlarkMethod(
            name = "label",
            structField = true,
            doc = "The label of the target."
        )
        val label: Label?

        @get:Throws(net.starlark.java.eval.EvalException::class)
        @get:net.starlark.java.annot.StarlarkMethod(
            name = "attr",
            structField = true,
            doc = ("A struct to access the attributes of the target. Attribute names are the keys, and"
                    + " each value is a struct with <code>value</code> (the attribute's value) and"
                    + " <code>is_tool</code> (a boolean indicating if the attribute is a tool)."
                    + " Dependency attributes are represented by labels because they"
                    + " are not analyzed at this stage.")
        )
        val attr: StructApi?

        @get:Throws(net.starlark.java.eval.EvalException::class)
        @get:net.starlark.java.annot.StarlarkMethod(
            name = "qualified_kind",
            structField = true,
            doc = ("The rule kind of the target broken down into 2 fields; <code>file_label</code>: the"
                    + " label of the file containing the rule definition and <code>rule_name</code>:"
                    + " the name of the rule.")
        )
        val qualifiedKind: QualifiedRuleKindApi?
    }

    /** Interface for the qualified rule kind of the target.  */
    interface QualifiedRuleKindApi : net.starlark.java.eval.StarlarkValue {
        @get:Throws(net.starlark.java.eval.EvalException::class)
        @get:net.starlark.java.annot.StarlarkMethod(
            name = "file_label",
            structField = true,
            allowReturnNones = true,
            doc = "The label of the file containing the rule definition."
        )
        val fileLabel: Label?

        @get:Throws(net.starlark.java.eval.EvalException::class)
        @get:net.starlark.java.annot.StarlarkMethod(
            name = "rule_name",
            structField = true,
            doc = "The name of the rule."
        )
        val ruleName: String?
    }

    /**
     * Interface for the target's attribute. It can be extended to include more metadata about the
     * attribute like its annotations.
     */
    interface RuleAttributeApi : net.starlark.java.eval.StarlarkValue {
        @get:Throws(net.starlark.java.eval.EvalException::class)
        @get:net.starlark.java.annot.StarlarkMethod(
            name = "value",
            structField = true,
            doc = "The value of the attribute."
        )
        val value: Any?

        @get:Throws(net.starlark.java.eval.EvalException::class)
        @get:net.starlark.java.annot.StarlarkMethod(
            name = "is_tool",
            structField = true,
            doc = "Whether the attribute is a tool."
        )
        val isTool: Boolean
    }
}
