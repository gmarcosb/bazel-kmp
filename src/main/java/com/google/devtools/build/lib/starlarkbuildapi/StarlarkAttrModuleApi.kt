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

import com.google.devtools.build.lib.cmdline.Label

/**
 * The "attr" module of the Build API.
 * 
 * 
 * It exposes functions (for example, 'attr.string', 'attr.label_list', etc.) to Starlark users
 * for creating attribute definitions.
 */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "attr", category = com.google.devtools.build.docgen.annot.DocCategory.TOP_LEVEL_MODULE, doc = """
        This is a top-level module for defining the attribute schemas of a rule or aspect. Each function returns an object representing the schema of a single attribute. These objects are used as the values of the <code>attrs</code> dictionary argument of <a href="../globals/bzl.html#rule"><code>rule()</code></a>, <a href="../globals/bzl.html#aspect"><code>aspect()</code></a>, <a href="../globals/bzl.html#repository_rule"><code>repository_rule()</code></a> and <a href="../globals/bzl.html#tag_class"><code>tag_class()</code></a>. <p>See the Rules page for more on <a href="https://bazel.build/extending/rules#attributes">defining</a>
        and <a href="https://bazel.build/extending/rules#implementation_function">using</a> attributes.</p>
        
        """.trimIndent()
)
interface StarlarkAttrModuleApi : net.starlark.java.eval.StarlarkValue {
    @net.starlark.java.annot.StarlarkMethod(
        name = "int",
        doc = ("Creates a schema for an integer attribute. The value must be in the signed 32-bit range."
                + " The corresponding <a href='../builtins/ctx.html#attr'><code>ctx.attr</code></a>"
                + " attribute will be of type <a href='../core/int.html'><code>int</code></a>."),
        parameters = [net.starlark.java.annot.Param(
            name = CONFIGURABLE_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = Boolean::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Starlark.UnboundMarker::class
            )],
            defaultValue = "unbound",
            doc = CONFIGURABLE_ARG_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = DEFAULT_ARG,
            defaultValue = "0",
            doc = DEFAULT_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = DOC_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None",
            doc = DOC_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = MANDATORY_ARG,
            defaultValue = "False",
            doc = MANDATORY_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = VALUES_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = net.starlark.java.eval.StarlarkInt::class
            )],
            defaultValue = "[]",
            doc = VALUES_DOC,
            named = true,
            positional = false
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun intAttribute(
        configurable: Any?,
        defaultValue: net.starlark.java.eval.StarlarkInt?,
        doc: Any?,
        mandatory: Boolean?,
        values: net.starlark.java.eval.Sequence<*>?,
        thread: net.starlark.java.eval.StarlarkThread?
    ): Descriptor?

    @net.starlark.java.annot.StarlarkMethod(
        name = "string",
        doc = "Creates a schema for a <a href='../core/string.html#attr'>string</a> attribute.",
        parameters = [net.starlark.java.annot.Param(
            name = CONFIGURABLE_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = Boolean::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Starlark.UnboundMarker::class
            )],
            defaultValue = "unbound",
            doc = CONFIGURABLE_ARG_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = DEFAULT_ARG,
            defaultValue = "''",
            doc = DEFAULT_DOC,
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = NativeComputedDefaultApi::class
            )],
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = DOC_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None",
            doc = DOC_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = MANDATORY_ARG,
            defaultValue = "False",
            doc = MANDATORY_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = VALUES_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = String::class
            )],
            defaultValue = "[]",
            doc = VALUES_DOC,
            named = true,
            positional = false
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun stringAttribute(
        configurable: Any?,
        defaultValue: Any?,
        doc: Any?,
        mandatory: Boolean?,
        values: net.starlark.java.eval.Sequence<*>?,
        thread: net.starlark.java.eval.StarlarkThread?
    ): Descriptor?

    @net.starlark.java.annot.StarlarkMethod(
        name = "label",
        doc = ("<p>Creates a schema for a label attribute. This is a dependency attribute.</p>"
                + DEPENDENCY_ATTR_TEXT
                + "<p>In addition to ordinary source files, this kind of attribute is often used to"
                + " refer to a tool -- for example, a compiler. Such tools are considered to be"
                + " dependencies, just like source files. To avoid requiring users to specify the"
                + " tool's label every time they use the rule in their BUILD files, you can hard-code"
                + " the label of a canonical tool as the <code>default</code> value of this"
                + " attribute. If you also want to prevent users from overriding this default, you"
                + " can make the attribute private by giving it a name that starts with an"
                + " underscore. See the <a"
                + " href='https://bazel.build/extending/rules#private-attributes'>Rules</a> page"
                + " for more information."),
        parameters = [net.starlark.java.annot.Param(
            name = CONFIGURABLE_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = Boolean::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Starlark.UnboundMarker::class
            )],
            defaultValue = "unbound",
            doc = CONFIGURABLE_ARG_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = DEFAULT_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = Label::class), net.starlark.java.annot.ParamType(
                type = String::class
            ), net.starlark.java.annot.ParamType(type = LateBoundDefaultApi::class), net.starlark.java.annot.ParamType(
                type = NativeComputedDefaultApi::class
            ), net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkFunction::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None",
            named = true,
            positional = false,
            doc = (DEFAULT_DOC
                    + "Use a string or the <a"
                    + " href=\"../builtins/Label.html#Label\"><code>Label</code></a> function to"
                    + " specify a default value, for example, <code>attr.label(default ="
                    + " \"//a:b\")</code>.")
        ), net.starlark.java.annot.Param(
            name = MATERIALIZER_ARG,
            enableOnlyWithFlag = BuildLanguageOptions.EXPERIMENTAL_DORMANT_DEPS,
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkFunction::class)],
            defaultValue = "None",
            named = true,
            positional = false,
            doc = MATERALIZER_DOC
        ), net.starlark.java.annot.Param(
            name = DOC_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None",
            doc = DOC_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = EXECUTABLE_ARG,
            defaultValue = "False",
            named = true,
            positional = false,
            doc = EXECUTABLE_DOC
        ), net.starlark.java.annot.Param(
            name = ALLOW_FILES_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = Boolean::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = String::class
            ), net.starlark.java.annot.ParamType(type = net.starlark.java.eval.NoneType::class)],
            defaultValue = "None",
            named = true,
            positional = false,
            doc = ALLOW_FILES_DOC
        ), net.starlark.java.annot.Param(
            name = ALLOW_SINGLE_FILE_ARG,
            defaultValue = "None",
            named = true,
            positional = false,
            doc = ("This is similar to <code>allow_files</code>, with the restriction that the label "
                    + "must correspond to a single <a href=\"../builtins/File.html\">File</a>. "
                    + "Access it through <code>ctx.file.&lt;attribute_name&gt;</code>.")
        ), net.starlark.java.annot.Param(
            name = MANDATORY_ARG,
            defaultValue = "False",
            named = true,
            positional = false,
            doc = MANDATORY_DOC
        ), net.starlark.java.annot.Param(
            name = SKIP_VALIDATIONS_ARG,
            defaultValue = "False",
            named = true,
            positional = false,
            doc = SKIP_VALIDATIONS_ARG_DOC
        ), net.starlark.java.annot.Param(
            name = PROVIDERS_ARG,
            defaultValue = "[]",
            named = true,
            positional = false,
            doc = PROVIDERS_DOC
        ), net.starlark.java.annot.Param(
            name = FOR_DEPENDENCY_RESOLUTION_ARG,
            defaultValue = "unbound",
            named = true,
            positional = false,
            doc = FOR_DEPENDENCY_RESOLUTION_DOC
        ), net.starlark.java.annot.Param(
            name = ALLOW_RULES_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = String::class
            ), net.starlark.java.annot.ParamType(type = net.starlark.java.eval.NoneType::class)],
            defaultValue = "None",
            named = true,
            positional = false,
            doc = ALLOW_RULES_DOC
        ), net.starlark.java.annot.Param(
            name = CONFIGURATION_ARG, defaultValue = "None", named = true, positional = false, doc = (CONFIGURATION_DOC
                    + " This parameter is required if <code>executable</code> is True "
                    + "to guard against accidentally building host tools in the "
                    + "target configuration. <code>\"target\"</code> has no semantic "
                    + "effect, so don't set it when <code>executable</code> is False "
                    + "unless it really helps clarify your intentions.")
        ), net.starlark.java.annot.Param(
            name = ASPECTS_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = StarlarkAspectApi::class
            )],
            defaultValue = "[]",
            named = true,
            positional = false,
            doc = ASPECTS_ARG_DOC
        ), net.starlark.java.annot.Param(
            name = FLAGS_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = String::class
            )],
            defaultValue = "[]",
            named = true,
            positional = false,
            doc = FLAGS_DOC
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun labelAttribute(
        configurable: Any?,
        defaultValue: Any?,
        materializer: Any?,
        doc: Any?,
        executable: Boolean?,
        allowFiles: Any?,
        allowSingleFile: Any?,
        mandatory: Boolean?,
        skipValidations: Boolean?,
        providers: net.starlark.java.eval.Sequence<*>?,
        forDependencyResolution: Any?,
        allowRules: Any?,
        cfg: Any?,
        aspects: net.starlark.java.eval.Sequence<*>?,
        flags: net.starlark.java.eval.Sequence<*>?,
        thread: net.starlark.java.eval.StarlarkThread?
    ): Descriptor?

    @net.starlark.java.annot.StarlarkMethod(
        name = "dormant_label",
        documented = false,
        enableOnlyWithFlag = BuildLanguageOptions.EXPERIMENTAL_DORMANT_DEPS,
        useStarlarkThread = true,
        parameters = [net.starlark.java.annot.Param(
            name = DEFAULT_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = Label::class), net.starlark.java.annot.ParamType(
                type = String::class
            ), net.starlark.java.annot.ParamType(type = net.starlark.java.eval.NoneType::class)],
            defaultValue = "None",
            named = true,
            positional = false,
            doc = (DEFAULT_DOC
                    + "Use a string or the <a"
                    + " href=\"../builtins/Label.html#Label\"><code>Label</code></a> function to"
                    + " specify a default value, for example, <code>attr.label(default ="
                    + " \"//a:b\")</code>.")
        ), net.starlark.java.annot.Param(
            name = DOC_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None",
            doc = DOC_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = MANDATORY_ARG,
            defaultValue = "False",
            named = true,
            positional = false,
            doc = MANDATORY_DOC
        )]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun dormantLabelAttribute(
        defaultValue: Any?, doc: Any?, mandatory: Boolean?, thread: net.starlark.java.eval.StarlarkThread?
    ): Descriptor?

    @net.starlark.java.annot.StarlarkMethod(
        name = "string_list",
        doc = "Creates a schema for a list-of-strings attribute.",
        parameters = [net.starlark.java.annot.Param(
            name = MANDATORY_ARG,
            defaultValue = "False",
            doc = MANDATORY_DOC,
            named = true
        ), net.starlark.java.annot.Param(
            name = ALLOW_EMPTY_ARG,
            defaultValue = "True",
            doc = ALLOW_EMPTY_DOC,
            named = true
        ), net.starlark.java.annot.Param(
            name = CONFIGURABLE_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = Boolean::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Starlark.UnboundMarker::class
            )],
            defaultValue = "unbound",
            doc = CONFIGURABLE_ARG_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = DEFAULT_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = String::class
            ), net.starlark.java.annot.ParamType(type = NativeComputedDefaultApi::class)],
            defaultValue = "[]",
            doc = DEFAULT_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = DOC_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None",
            doc = DOC_DOC,
            named = true,
            positional = false
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun stringListAttribute(
        mandatory: Boolean?,
        allowEmpty: Boolean?,
        configurable: Any?,
        defaultValue: Any?,
        doc: Any?,
        thread: net.starlark.java.eval.StarlarkThread?
    ): Descriptor?

    @net.starlark.java.annot.StarlarkMethod(
        name = "int_list",
        doc = ("Creates a schema for a list-of-integers attribute. Each element must be in the signed"
                + " 32-bit range."),
        parameters = [net.starlark.java.annot.Param(
            name = MANDATORY_ARG,
            defaultValue = "False",
            doc = MANDATORY_DOC,
            named = true
        ), net.starlark.java.annot.Param(
            name = ALLOW_EMPTY_ARG,
            defaultValue = "True",
            doc = ALLOW_EMPTY_DOC,
            named = true
        ), net.starlark.java.annot.Param(
            name = CONFIGURABLE_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = Boolean::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Starlark.UnboundMarker::class
            )],
            defaultValue = "unbound",
            doc = CONFIGURABLE_ARG_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = DEFAULT_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = net.starlark.java.eval.StarlarkInt::class
            )],
            defaultValue = "[]",
            doc = DEFAULT_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = DOC_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None",
            doc = DOC_DOC,
            named = true,
            positional = false
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun intListAttribute(
        mandatory: Boolean?,
        allowEmpty: Boolean?,
        configurable: Any?,
        defaultValue: net.starlark.java.eval.Sequence<*>?,
        doc: Any?,
        thread: net.starlark.java.eval.StarlarkThread?
    ): Descriptor?

    @net.starlark.java.annot.StarlarkMethod(
        name = "label_list",
        doc = ("<p>Creates a schema for a list-of-labels attribute. This is a dependency attribute. "
                + "The corresponding <a href='../builtins/ctx.html#attr'><code>ctx.attr</code></a> "
                + "attribute will be of type <a href='../core/list.html'>list</a> of "
                + "<a href='../builtins/Target.html'><code>Target</code>s</a>.</p>"
                + DEPENDENCY_ATTR_TEXT),
        parameters = [net.starlark.java.annot.Param(
            name = ALLOW_EMPTY_ARG,
            defaultValue = "True",
            doc = ALLOW_EMPTY_DOC,
            named = true
        ), net.starlark.java.annot.Param(
            name = CONFIGURABLE_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = Boolean::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Starlark.UnboundMarker::class
            )],
            defaultValue = "unbound",
            doc = CONFIGURABLE_ARG_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = DEFAULT_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = Label::class
            ), net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkFunction::class)],
            defaultValue = "[]",
            named = true,
            positional = false,
            doc = (DEFAULT_DOC
                    + "Use strings or the <a"
                    + " href=\"../builtins/Label.html#Label\"><code>Label</code></a> function to"
                    + " specify default values, for example, <code>attr.label_list(default ="
                    + " [\"//a:b\", \"//a:c\"])</code>.")
        ), net.starlark.java.annot.Param(
            name = MATERIALIZER_ARG,
            enableOnlyWithFlag = BuildLanguageOptions.EXPERIMENTAL_DORMANT_DEPS,
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkFunction::class)],
            defaultValue = "None",
            named = true,
            positional = false,
            doc = MATERALIZER_DOC
        ), net.starlark.java.annot.Param(
            name = DOC_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None",
            doc = DOC_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = ALLOW_FILES_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = Boolean::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = String::class
            ), net.starlark.java.annot.ParamType(type = net.starlark.java.eval.NoneType::class)],
            defaultValue = "None",
            named = true,
            positional = false,
            doc = ALLOW_FILES_DOC
        ), net.starlark.java.annot.Param(
            name = ALLOW_RULES_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = String::class
            ), net.starlark.java.annot.ParamType(type = net.starlark.java.eval.NoneType::class)],
            defaultValue = "None",
            named = true,
            positional = false,
            doc = ALLOW_RULES_DOC
        ), net.starlark.java.annot.Param(
            name = PROVIDERS_ARG,
            defaultValue = "[]",
            named = true,
            positional = false,
            doc = PROVIDERS_DOC
        ), net.starlark.java.annot.Param(
            name = FOR_DEPENDENCY_RESOLUTION_ARG,
            defaultValue = "unbound",
            named = true,
            positional = false,
            doc = FOR_DEPENDENCY_RESOLUTION_DOC
        ), net.starlark.java.annot.Param(
            name = FLAGS_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = String::class
            )],
            defaultValue = "[]",
            named = true,
            positional = false,
            doc = FLAGS_DOC
        ), net.starlark.java.annot.Param(
            name = MANDATORY_ARG,
            defaultValue = "False",
            named = true,
            positional = false,
            doc = MANDATORY_DOC
        ), net.starlark.java.annot.Param(
            name = SKIP_VALIDATIONS_ARG,
            defaultValue = "False",
            named = true,
            positional = false,
            doc = SKIP_VALIDATIONS_ARG_DOC
        ), net.starlark.java.annot.Param(
            name = CONFIGURATION_ARG,
            defaultValue = "None",
            named = true,
            positional = false,
            doc = CONFIGURATION_DOC
        ), net.starlark.java.annot.Param(
            name = ASPECTS_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = StarlarkAspectApi::class
            )],
            defaultValue = "[]",
            named = true,
            positional = false,
            doc = ASPECTS_ARG_DOC
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun labelListAttribute(
        allowEmpty: Boolean?,
        configurable: Any?,
        defaultValue: Any?,
        materializer: Any?,
        doc: Any?,
        allowFiles: Any?,
        allowRules: Any?,
        providers: net.starlark.java.eval.Sequence<*>?,
        forDependencyResolution: Any?,
        flags: net.starlark.java.eval.Sequence<*>?,
        mandatory: Boolean?,
        skipValidations: Boolean?,
        cfg: Any?,
        aspects: net.starlark.java.eval.Sequence<*>?,
        thread: net.starlark.java.eval.StarlarkThread?
    ): Descriptor?

    @net.starlark.java.annot.StarlarkMethod(
        name = "dormant_label_list",
        enableOnlyWithFlag = BuildLanguageOptions.EXPERIMENTAL_DORMANT_DEPS,
        useStarlarkThread = true,
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = ALLOW_EMPTY_ARG,
            defaultValue = "True",
            doc = ALLOW_EMPTY_DOC,
            named = true
        ), net.starlark.java.annot.Param(
            name = DEFAULT_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = Label::class
            )],
            defaultValue = "[]",
            named = true,
            positional = false,
            doc = DEFAULT_DOC
        ), net.starlark.java.annot.Param(
            name = DOC_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None",
            doc = DOC_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = MANDATORY_ARG,
            defaultValue = "False",
            named = true,
            positional = false,
            doc = MANDATORY_DOC
        )]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun dormantLabelListAttribute(
        allowEmpty: Boolean?,
        defaultValue: Any?,
        doc: Any?,
        mandatory: Boolean?,
        thread: net.starlark.java.eval.StarlarkThread?
    ): Descriptor?

    @net.starlark.java.annot.StarlarkMethod(
        name = "string_keyed_label_dict",
        doc = ("<p>Creates a schema for an attribute whose value is a dictionary where the keys are "
                + "strings and the values are labels. This is a dependency attribute.</p>"
                + DEPENDENCY_ATTR_TEXT),
        parameters = [net.starlark.java.annot.Param(
            name = ALLOW_EMPTY_ARG,
            defaultValue = "True",
            doc = ALLOW_EMPTY_DOC,
            named = true
        ), net.starlark.java.annot.Param(
            name = CONFIGURABLE_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = Boolean::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Starlark.UnboundMarker::class
            )],
            defaultValue = "unbound",
            doc = CONFIGURABLE_ARG_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = DEFAULT_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.Dict::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.StarlarkFunction::class
            )],
            defaultValue = "{}",
            named = true,
            positional = false,
            doc = (DEFAULT_DOC
                    + "Use strings or the <a"
                    + " href=\"../builtins/Label.html#Label\"><code>Label</code></a> function to"
                    + " specify default values, for example,"
                    + " <code>attr.string_keyed_label_dict(default = {\"foo\": \"//a:b\","
                    + " \"bar\": \"//a:c\"})</code>.")
        ), net.starlark.java.annot.Param(
            name = DOC_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None",
            doc = DOC_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = ALLOW_FILES_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = Boolean::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = String::class
            ), net.starlark.java.annot.ParamType(type = net.starlark.java.eval.NoneType::class)],
            defaultValue = "None",
            named = true,
            positional = false,
            doc = ALLOW_FILES_DOC
        ), net.starlark.java.annot.Param(
            name = ALLOW_RULES_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = String::class
            ), net.starlark.java.annot.ParamType(type = net.starlark.java.eval.NoneType::class)],
            defaultValue = "None",
            named = true,
            positional = false,
            doc = ALLOW_RULES_DOC
        ), net.starlark.java.annot.Param(
            name = PROVIDERS_ARG,
            defaultValue = "[]",
            named = true,
            positional = false,
            doc = PROVIDERS_DOC
        ), net.starlark.java.annot.Param(
            name = FOR_DEPENDENCY_RESOLUTION_ARG,
            defaultValue = "unbound",
            named = true,
            positional = false,
            doc = FOR_DEPENDENCY_RESOLUTION_DOC
        ), net.starlark.java.annot.Param(
            name = FLAGS_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = String::class
            )],
            defaultValue = "[]",
            named = true,
            positional = false,
            doc = FLAGS_DOC
        ), net.starlark.java.annot.Param(
            name = MANDATORY_ARG,
            defaultValue = "False",
            named = true,
            positional = false,
            doc = MANDATORY_DOC
        ), net.starlark.java.annot.Param(
            name = CONFIGURATION_ARG,
            defaultValue = "None",
            named = true,
            positional = false,
            doc = CONFIGURATION_DOC
        ), net.starlark.java.annot.Param(
            name = ASPECTS_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = StarlarkAspectApi::class
            )],
            defaultValue = "[]",
            named = true,
            positional = false,
            doc = ASPECTS_ARG_DOC
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun stringKeyedLabelDictAttribute(
        allowEmpty: Boolean?,
        configurable: Any?,
        defaultValue: Any?,
        doc: Any?,
        allowFiles: Any?,
        allowRules: Any?,
        providers: net.starlark.java.eval.Sequence<*>?,
        forDependencyResolution: Any?,
        flags: net.starlark.java.eval.Sequence<*>?,
        mandatory: Boolean?,
        cfg: Any?,
        aspects: net.starlark.java.eval.Sequence<*>?,
        thread: net.starlark.java.eval.StarlarkThread?
    ): Descriptor?

    @net.starlark.java.annot.StarlarkMethod(
        name = "label_keyed_string_dict",
        doc = ("<p>Creates a schema for an attribute holding a dictionary, where the keys are labels "
                + "and the values are strings. This is a dependency attribute.</p>"
                + DEPENDENCY_ATTR_TEXT),
        parameters = [net.starlark.java.annot.Param(
            name = ALLOW_EMPTY_ARG,
            defaultValue = "True",
            doc = ALLOW_EMPTY_DOC,
            named = true
        ), net.starlark.java.annot.Param(
            name = CONFIGURABLE_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = Boolean::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Starlark.UnboundMarker::class
            )],
            defaultValue = "unbound",
            doc = CONFIGURABLE_ARG_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = DEFAULT_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.Dict::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.StarlarkFunction::class
            )],
            defaultValue = "{}",
            named = true,
            positional = false,
            doc = (DEFAULT_DOC
                    + "Use strings or the <a"
                    + " href=\"../builtins/Label.html#Label\"><code>Label</code></a> function to"
                    + " specify default values, for example,"
                    + " <code>attr.label_keyed_string_dict(default = {\"//a:b\": \"value\","
                    + " \"//a:c\": \"string\"})</code>.")
        ), net.starlark.java.annot.Param(
            name = DOC_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None",
            doc = DOC_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = ALLOW_FILES_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = Boolean::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = String::class
            ), net.starlark.java.annot.ParamType(type = net.starlark.java.eval.NoneType::class)],
            defaultValue = "None",
            named = true,
            positional = false,
            doc = ALLOW_FILES_DOC
        ), net.starlark.java.annot.Param(
            name = ALLOW_RULES_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = String::class
            ), net.starlark.java.annot.ParamType(type = net.starlark.java.eval.NoneType::class)],
            defaultValue = "None",
            named = true,
            positional = false,
            doc = ALLOW_RULES_DOC
        ), net.starlark.java.annot.Param(
            name = PROVIDERS_ARG,
            defaultValue = "[]",
            named = true,
            positional = false,
            doc = PROVIDERS_DOC
        ), net.starlark.java.annot.Param(
            name = FOR_DEPENDENCY_RESOLUTION_ARG,
            defaultValue = "unbound",
            named = true,
            positional = false,
            doc = FOR_DEPENDENCY_RESOLUTION_DOC
        ), net.starlark.java.annot.Param(
            name = FLAGS_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = String::class
            )],
            defaultValue = "[]",
            named = true,
            positional = false,
            doc = FLAGS_DOC
        ), net.starlark.java.annot.Param(
            name = MANDATORY_ARG,
            defaultValue = "False",
            named = true,
            positional = false,
            doc = MANDATORY_DOC
        ), net.starlark.java.annot.Param(
            name = SKIP_VALIDATIONS_ARG,
            defaultValue = "False",
            named = true,
            positional = false,
            doc = SKIP_VALIDATIONS_ARG_DOC
        ), net.starlark.java.annot.Param(
            name = CONFIGURATION_ARG,
            defaultValue = "None",
            named = true,
            positional = false,
            doc = CONFIGURATION_DOC
        ), net.starlark.java.annot.Param(
            name = ASPECTS_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = StarlarkAspectApi::class
            )],
            defaultValue = "[]",
            named = true,
            positional = false,
            doc = ASPECTS_ARG_DOC
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun labelKeyedStringDictAttribute(
        allowEmpty: Boolean?,
        configurable: Any?,
        defaultValue: Any?,
        doc: Any?,
        allowFiles: Any?,
        allowRules: Any?,
        providers: net.starlark.java.eval.Sequence<*>?,
        forDependencyResolution: Any?,
        flags: net.starlark.java.eval.Sequence<*>?,
        mandatory: Boolean?,
        skipValidations: Boolean?,
        cfg: Any?,
        aspects: net.starlark.java.eval.Sequence<*>?,
        thread: net.starlark.java.eval.StarlarkThread?
    ): Descriptor?

    @net.starlark.java.annot.StarlarkMethod(
        name = "label_list_dict",
        doc = ("<p>Creates a schema for an attribute holding a dictionary, where the keys are strings "
                + "and the values are list of labels. This is a dependency attribute.</p>"
                + DEPENDENCY_ATTR_TEXT),
        parameters = [net.starlark.java.annot.Param(
            name = ALLOW_EMPTY_ARG,
            defaultValue = "True",
            doc = ALLOW_EMPTY_DOC,
            named = true
        ), net.starlark.java.annot.Param(
            name = CONFIGURABLE_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = Boolean::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Starlark.UnboundMarker::class
            )],
            defaultValue = "unbound",
            doc = CONFIGURABLE_ARG_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = DEFAULT_ARG, defaultValue = "{}", named = true, positional = false, doc = DEFAULT_DOC
                    + """
                    Use strings or the <a href="../builtins/Label.html#Label"><code>Label</code>
                    </a> function to specify default values, for example,
                    <code>attr.label_list_dict(default = {"key1": ["//a:b", "//a:c"], "key2":
                    [Label("@my_repo//d:e")]})</code>.
                    """.trimIndent()
        ), net.starlark.java.annot.Param(
            name = DOC_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None",
            doc = DOC_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = ALLOW_FILES_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = Boolean::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = String::class
            ), net.starlark.java.annot.ParamType(type = net.starlark.java.eval.NoneType::class)],
            defaultValue = "None",
            named = true,
            positional = false,
            doc = ALLOW_FILES_DOC
        ), net.starlark.java.annot.Param(
            name = ALLOW_RULES_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = String::class
            ), net.starlark.java.annot.ParamType(type = net.starlark.java.eval.NoneType::class)],
            defaultValue = "None",
            named = true,
            positional = false,
            doc = ALLOW_RULES_DOC
        ), net.starlark.java.annot.Param(
            name = PROVIDERS_ARG,
            defaultValue = "[]",
            named = true,
            positional = false,
            doc = PROVIDERS_DOC
        ), net.starlark.java.annot.Param(
            name = FOR_DEPENDENCY_RESOLUTION_ARG,
            defaultValue = "unbound",
            named = true,
            positional = false,
            doc = FOR_DEPENDENCY_RESOLUTION_DOC
        ), net.starlark.java.annot.Param(
            name = FLAGS_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = String::class
            )],
            defaultValue = "[]",
            named = true,
            positional = false,
            doc = FLAGS_DOC
        ), net.starlark.java.annot.Param(
            name = MANDATORY_ARG,
            defaultValue = "False",
            named = true,
            positional = false,
            doc = MANDATORY_DOC
        ), net.starlark.java.annot.Param(
            name = SKIP_VALIDATIONS_ARG,
            defaultValue = "False",
            named = true,
            positional = false,
            doc = SKIP_VALIDATIONS_ARG_DOC
        ), net.starlark.java.annot.Param(
            name = CONFIGURATION_ARG,
            defaultValue = "None",
            named = true,
            positional = false,
            doc = CONFIGURATION_DOC
        ), net.starlark.java.annot.Param(
            name = ASPECTS_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = StarlarkAspectApi::class
            )],
            defaultValue = "[]",
            named = true,
            positional = false,
            doc = ASPECTS_ARG_DOC
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun labelListDictAttribute(
        allowEmpty: Boolean?,
        configurable: Any?,
        defaultValue: net.starlark.java.eval.Dict<*, *>?,
        doc: Any?,
        allowFiles: Any?,
        allowRules: Any?,
        providers: net.starlark.java.eval.Sequence<*>?,
        forDependencyResolution: Any?,
        flags: net.starlark.java.eval.Sequence<*>?,
        mandatory: Boolean?,
        skipValidations: Boolean?,
        cfg: Any?,
        aspects: net.starlark.java.eval.Sequence<*>?,
        thread: net.starlark.java.eval.StarlarkThread?
    ): Descriptor?

    @net.starlark.java.annot.StarlarkMethod(
        name = "bool",
        doc = ("Creates a schema for a boolean attribute. The corresponding <a"
                + " href='../builtins/ctx.html#attr'><code>ctx.attr</code></a> attribute will be of"
                + " type <a href='../core/bool.html'><code>bool</code></a>."),
        parameters = [net.starlark.java.annot.Param(
            name = CONFIGURABLE_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = Boolean::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Starlark.UnboundMarker::class
            )],
            defaultValue = "unbound",
            doc = CONFIGURABLE_ARG_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = DEFAULT_ARG,
            defaultValue = "False",
            named = true,
            positional = false,
            doc = DEFAULT_DOC
        ), net.starlark.java.annot.Param(
            name = DOC_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None",
            doc = DOC_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = MANDATORY_ARG,
            defaultValue = "False",
            named = true,
            positional = false,
            doc = MANDATORY_DOC
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun boolAttribute(
        configurable: Any?,
        defaultValue: Boolean?,
        doc: Any?,
        mandatory: Boolean?,
        thread: net.starlark.java.eval.StarlarkThread?
    ): Descriptor?

    @net.starlark.java.annot.StarlarkMethod(
        name = "output",
        doc = "<p>Creates a schema for an output (label) attribute.</p>" + OUTPUT_ATTR_TEXT,
        parameters = [net.starlark.java.annot.Param(
            name = DOC_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None",
            doc = DOC_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = MANDATORY_ARG,
            defaultValue = "False",
            named = true,
            positional = false,
            doc = MANDATORY_DOC
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun outputAttribute(doc: Any?, mandatory: Boolean?, thread: net.starlark.java.eval.StarlarkThread?): Descriptor?

    @net.starlark.java.annot.StarlarkMethod(
        name = "output_list",
        doc = "Creates a schema for a list-of-outputs attribute." + OUTPUT_ATTR_TEXT,
        parameters = [net.starlark.java.annot.Param(
            name = ALLOW_EMPTY_ARG,
            defaultValue = "True",
            doc = ALLOW_EMPTY_DOC,
            named = true
        ), net.starlark.java.annot.Param(
            name = DOC_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None",
            doc = DOC_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = MANDATORY_ARG,
            defaultValue = "False",
            named = true,
            positional = false,
            doc = MANDATORY_DOC
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun outputListAttribute(
        allowEmpty: Boolean?, doc: Any?, mandatory: Boolean?, thread: net.starlark.java.eval.StarlarkThread?
    ): Descriptor?

    @net.starlark.java.annot.StarlarkMethod(
        name = "string_dict",
        doc = ("Creates a schema for an attribute holding a dictionary, where the keys and values are "
                + "strings."),
        parameters = [net.starlark.java.annot.Param(
            name = ALLOW_EMPTY_ARG,
            defaultValue = "True",
            doc = ALLOW_EMPTY_DOC,
            named = true
        ), net.starlark.java.annot.Param(
            name = CONFIGURABLE_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = Boolean::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Starlark.UnboundMarker::class
            )],
            defaultValue = "unbound",
            doc = CONFIGURABLE_ARG_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = DEFAULT_ARG,
            named = true,
            positional = false,
            defaultValue = "{}",
            doc = DEFAULT_DOC
        ), net.starlark.java.annot.Param(
            name = DOC_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None",
            doc = DOC_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = MANDATORY_ARG,
            named = true,
            positional = false,
            defaultValue = "False",
            doc = MANDATORY_DOC
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun stringDictAttribute(
        allowEmpty: Boolean?,
        configurable: Any?,
        defaultValue: net.starlark.java.eval.Dict<*, *>?,
        doc: Any?,
        mandatory: Boolean?,
        thread: net.starlark.java.eval.StarlarkThread?
    ): Descriptor?

    @net.starlark.java.annot.StarlarkMethod(
        name = "string_list_dict",
        doc = ("Creates a schema for an attribute holding a dictionary, where the keys are strings and "
                + "the values are lists of strings."),
        parameters = [net.starlark.java.annot.Param(
            name = ALLOW_EMPTY_ARG,
            defaultValue = "True",
            doc = ALLOW_EMPTY_DOC,
            named = true
        ), net.starlark.java.annot.Param(
            name = CONFIGURABLE_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = Boolean::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Starlark.UnboundMarker::class
            )],
            defaultValue = "unbound",
            doc = CONFIGURABLE_ARG_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = DEFAULT_ARG,
            defaultValue = "{}",
            named = true,
            positional = false,
            doc = DEFAULT_DOC
        ), net.starlark.java.annot.Param(
            name = DOC_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None",
            doc = DOC_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = MANDATORY_ARG,
            defaultValue = "False",
            named = true,
            positional = false,
            doc = MANDATORY_DOC
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun stringListDictAttribute(
        allowEmpty: Boolean?,
        configurable: Any?,
        defaultValue: net.starlark.java.eval.Dict<*, *>?,
        doc: Any?,
        mandatory: Boolean?,
        thread: net.starlark.java.eval.StarlarkThread?
    ): Descriptor?

    @net.starlark.java.annot.StarlarkMethod(
        name = "license",
        doc = "Creates a schema for a license attribute.",
        parameters = [net.starlark.java.annot.Param(
            name = DEFAULT_ARG,
            defaultValue = "None",
            named = true,
            positional = false,
            doc = DEFAULT_DOC
        ), net.starlark.java.annot.Param(
            name = DOC_ARG,
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None",
            doc = DOC_DOC,
            named = true,
            positional = false
        ), net.starlark.java.annot.Param(
            name = MANDATORY_ARG,
            defaultValue = "False",
            named = true,
            positional = false,
            doc = MANDATORY_DOC
        )],
        disableWithFlag = BuildLanguageOptions.INCOMPATIBLE_NO_ATTR_LICENSE,
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun licenseAttribute(
        defaultValue: Any?, doc: Any?, mandatory: Boolean?, thread: net.starlark.java.eval.StarlarkThread?
    ): Descriptor?

    /** An attribute descriptor.  */
    @net.starlark.java.annot.StarlarkBuiltin(
        name = "Attribute",
        category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN,
        doc = ("Representation of a definition of an attribute. Use the <a"
                + " href=\"../toplevel/attr.html\">attr</a> module to create an Attribute. They are"
                + " only for use with a <a href=\"../globals/bzl.html#rule\">rule</a> or an <a"
                + " href=\"../globals/bzl.html#aspect\">aspect</a>.")
    )
    interface Descriptor : net.starlark.java.eval.StarlarkValue
    companion object {
        // dependency and output attributes
        val LABEL_PARAGRAPH: String =
            ("<p>This attribute contains unique <a href='../builtins/Label.html'><code>Label</code></a>"
                    + " values. If a string is supplied in place of a <code>Label</code>, it will be"
                    + " converted using the <a href='../builtins/Label.html#Label'>label constructor</a>. The"
                    + " relative parts of the label path, including the (possibly renamed) repository, are"
                    + " resolved with respect to the instantiated target's package.")

        // attr.label, attr.label_list, attr.label_keyed_string_dict
        val DEPENDENCY_ATTR_TEXT: String = (LABEL_PARAGRAPH
                + "<p>At analysis time (within the rule's implementation function), when retrieving the"
                + " attribute value from <code>ctx.attr</code>, labels are replaced by the corresponding"
                + " <a href='../builtins/Target.html'><code>Target</code></a>s. This allows you to access"
                + " the providers of the current target's dependencies.")

        // attr.output, attr.output_list
        val OUTPUT_ATTR_TEXT: String = (LABEL_PARAGRAPH
                + "<p>At analysis time, the corresponding <a"
                + " href='../builtins/File.html'><code>File</code></a> can be retrieved using <a"
                + " href='../builtins/ctx.html#outputs'><code>ctx.outputs</code></a>.")

        const val ALLOW_FILES_ARG: String = "allow_files"
        val ALLOW_FILES_DOC: String =
            ("Whether <code>File</code> targets are allowed. Can be <code>True</code>, <code>False</code> "
                    + "(default), or a list of file extensions that are allowed (for example, "
                    + "<code>[\".cc\", \".cpp\"]</code>).")

        const val ALLOW_RULES_ARG: String = "allow_rules"
        val ALLOW_RULES_DOC: String =
            ("Which rule targets (name of the classes) are allowed. This is deprecated (kept only for "
                    + "compatibility), use providers instead.")

        const val ASPECTS_ARG: String = "aspects"
        val ASPECTS_ARG_DOC: String =
            ("Aspects that should be applied to the dependency or dependencies specified by this "
                    + "attribute.")

        const val SKIP_VALIDATIONS_ARG: String = "skip_validations"
        val SKIP_VALIDATIONS_ARG_DOC: String = ("If true, validation actions of transitive dependencies from "
                + "this attribute will not run. This is a temporary mitigation and WILL be removed in "
                + "the future.")

        const val CONFIGURABLE_ARG: String = "configurable"
        val CONFIGURABLE_ARG_DOC: String =
            ("This argument can only be specified for an attribute of a symbolic macro." //
                    + "<p>If <code>"
                    + CONFIGURABLE_ARG
                    + "</code> is explicitly set to <code>False</code>, the symbolic macro attribute is"
                    + " non-configurable - in other words, it cannot take a <code>select()</code> value. If"
                    + " the <code>"
                    + CONFIGURABLE_ARG
                    + "</code> is either unbound or explicitly set to <code>True</code>, the attribute is"
                    + " configurable and can take a <code>select()</code> value." //
                    + "<p>For an attribute of a rule or aspect, <code>"
                    + CONFIGURABLE_ARG
                    + "</code> must be left unbound. Most Starlark rule attributes are always configurable,"
                    + " with the exception of <code>attr.output()</code>, <code>attr.output_list()</code>,"
                    + " and <code>attr.license()</code> rule attributes, which are always non-configurable.")

        const val CONFIGURATION_ARG: String = "cfg"

        // TODO(b/151742236): Update when new Starlark-based configuration framework is implemented.
        val CONFIGURATION_DOC: String = ("<a href=\"https://bazel.build/extending/rules#configurations\">"
                + "Configuration</a> of the attribute. It can be either <code>\"exec\"</code>, which "
                + "indicates that the dependency is built for the <code>execution platform</code>, or "
                + "<code>\"target\"</code>, which indicates that the dependency is build for the "
                + "<code>target platform</code>. A typical example of the difference is when building "
                + "mobile apps, where the <code>target platform</code> is <code>Android</code> or "
                + "<code>iOS</code> while the <code>execution platform</code> is <code>Linux</code>, "
                + "<code>macOS</code>, or <code>Windows</code>.")

        const val DEFAULT_ARG: String = "default"

        // A trailing space is required because it's often prepended to other sentences
        const val DEFAULT_DOC: String =
            "A default value to use if no value for this attribute is given when instantiating the rule."

        const val DOC_ARG: String = "doc"
        const val DOC_DOC: String =
            "A description of the attribute that can be extracted by documentation generating tools."

        const val EXECUTABLE_ARG: String = "executable"
        val EXECUTABLE_DOC: String =
            ("True if the dependency has to be executable. This means the label must refer to an "
                    + "executable file, or to a rule that outputs an executable file. Access the label "
                    + "with <code>ctx.executable.&lt;attribute_name&gt;</code>.")

        const val FLAGS_ARG: String = "flags"
        const val FLAGS_DOC: String = "Deprecated, will be removed."

        const val MANDATORY_ARG: String = "mandatory"
        const val MANDATORY_DOC: String =
            "If true, the value must be specified explicitly (even if it has a <code>default</code>)."

        const val MATERIALIZER_ARG: String = "materializer"
        val MATERALIZER_DOC: String =
            ("If set, the attribute materializes dormant dependencies from the transitive closure. The "
                    + "value of this parameter must be a functon that gets access to the values of the "
                    + "attributes of the rule that either are not dependencies or are marked as available "
                    + "for dependency resolution. It must return either a dormant dependency or a list of "
                    + "them depending on the type of the attribute")

        const val ALLOW_EMPTY_ARG: String = "allow_empty"
        const val ALLOW_EMPTY_DOC: String = "True if the attribute can be empty."

        const val FOR_DEPENDENCY_RESOLUTION_ARG: String = "for_dependency_resolution"
        val FOR_DEPENDENCY_RESOLUTION_DOC: String =
            ("If this is set, the attribute is available for materializers. Only rules marked with the"
                    + " flag of the same name are allowed to be referenced through such attributes.")

        const val PROVIDERS_ARG: String = "providers"
        val PROVIDERS_DOC: String =
            ("The providers that must be given by any dependency appearing in this attribute.<p>The format"
                    + " of this argument is a list of lists of providers -- <code>*Info</code> objects"
                    + " returned by <a href='../globals/bzl.html#provider'><code>provider()</code></a> (or in"
                    + " the case of a legacy provider, its string name). The dependency must return ALL"
                    + " providers mentioned in at least ONE of the inner lists. As a convenience, this"
                    + " argument may also be a single-level list of providers, in which case it is wrapped in"
                    + " an outer list with one element (i.e. <code>[A, B]</code> means <code>[[A,"
                    + " B]]</code>). It is NOT required that the rule of the dependency advertises those"
                    + " providers in its <code>provides</code> parameter, however, it is considered best"
                    + " practice.")

        const val ALLOW_SINGLE_FILE_ARG: String = "allow_single_file"

        const val VALUES_ARG: String = "values"
        val VALUES_DOC: String = ("The list of allowed values for the attribute. An error is raised if any other "
                + "value is given.")
    }
}
