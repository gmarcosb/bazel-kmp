// Copyright 2022 The Bazel Authors. All rights reserved.
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

/** Template expansion dict module.  */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "TemplateDict",
    category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN,
    doc = ("An Args-like structure for use in ctx.actions.expand_template(), which allows for"
            + " deferring evaluation of values till the execution phase.")
)
interface TemplateDictApi : net.starlark.java.eval.StarlarkValue {
    @net.starlark.java.annot.StarlarkMethod(
        name = "add",
        doc = "Add a String value",
        parameters = [net.starlark.java.annot.Param(name = "key", doc = "A String key"), net.starlark.java.annot.Param(
            name = "value",
            doc = "A String value"
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun addArgument(key: String?, value: String?, thread: net.starlark.java.eval.StarlarkThread?): TemplateDictApi?

    @net.starlark.java.annot.StarlarkMethod(
        name = "add_joined",
        doc = "Add depset of values",
        parameters = [net.starlark.java.annot.Param(name = "key", doc = "A String key"), net.starlark.java.annot.Param(
            name = "values",
            allowedTypes = [net.starlark.java.annot.ParamType(type = Depset::class)],
            doc = "The depset whose items will be joined."
        ), net.starlark.java.annot.Param(
            name = "join_with",
            named = true,
            positional = false,
            doc = ("A delimiter string used to join together the strings obtained from applying "
                    + "<code>map_each</code>, in the same manner as "
                    + "<a href='../core/string.html#join'><code>string.join()</code></a>.")
        ), net.starlark.java.annot.Param(
            name = "map_each",
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkCallable::class)],
            named = true,
            positional = false,
            doc = ("A Starlark function accepting a single argument and returning either a string, "
                    + "<code>None</code>, or a list of strings. This function is applied to each "
                    + "item of the depset specified in the <code>values</code> parameter")
        ), net.starlark.java.annot.Param(
            name = "uniquify",
            named = true,
            positional = false,
            defaultValue = "False",
            doc = ("If true, duplicate strings derived from <code>values</code> will be omitted. Only "
                    + "the first occurrence of each string will remain. Usually this feature is "
                    + "not needed because depsets already omit duplicates, but it can be useful "
                    + "if <code>map_each</code> emits the same string for multiple items.")
        ), net.starlark.java.annot.Param(
            name = "format_joined",
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            named = true,
            positional = false,
            defaultValue = "None",
            doc = "An optional format string pattern applied to the joined string. "
                    + "The format string must have exactly one '%s' placeholder."
        ), net.starlark.java.annot.Param(
            name = "allow_closure",
            named = true,
            positional = false,
            defaultValue = "False",
            doc = ("If true, allows the use of closures in function parameters like "
                    + "<code>map_each</code>. Usually this isn't necessary and it risks retaining "
                    + "large analysis-phase data structures into the execution phase.")
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun addJoined(
        key: String?,
        values: Depset?,
        joinWith: String?,
        mapEach: net.starlark.java.eval.StarlarkCallable?,
        uniquify: Boolean?,
        formatJoined: Any?,
        allowClosure: Boolean?,
        thread: net.starlark.java.eval.StarlarkThread?
    ): TemplateDictApi?
}
