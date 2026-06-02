// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages

/**
 * An attribute value consisting of a concatenation (via the `+` operator for lists or the
 * `|` operator for dicts) of native types and selects, e.g:
 * 
 * <pre>
 * rule(
 * name = 'myrule',
 * deps =
 * [':defaultdep']
 * + select({
 * 'a': [':adep'],
 * 'b': [':bdep'],})
 * + select({
 * 'c': [':cdep'],
 * 'd': [':ddep'],})
 * )
</pre> * 
 */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "select",
    doc = "A selector between configuration-dependent entities.",
    documented = false
)
class SelectorList private constructor(type: java.lang.Class<*>?, elements: MutableList<Any?>) :
    net.starlark.java.eval.StarlarkValue, net.starlark.java.eval.HasBinary {
    // TODO(adonovan): combine Selector{List,Value} and BuildType.SelectorList.
    // We don't need three classes for the same concept
    private val type: java.lang.Class<*>?
    @kotlin.jvm.JvmField
    private val elements: MutableList<Any?>

    init {
        this.type = type
        this.elements = elements
    }

    /**
     * Returns an ordered list of the elements in this expression. Each element may be a native type
     * or a select.
     */
    fun getElements(): MutableList<Any?> {
        return elements
    }

    /** Returns the native type contained by this expression.  */
    fun getType(): java.lang.Class<*>? {
        return type
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun binaryOp(op: net.starlark.java.syntax.TokenKind?, that: Any, thisLeft: Boolean): SelectorList? {
        if (op == com.google.devtools.build.lib.packages.SelectorList.Companion.binaryOpToken(that)) {
            return if (thisLeft) com.google.devtools.build.lib.packages.SelectorList.Companion.concat(
                this,
                that
            ) else com.google.devtools.build.lib.packages.SelectorList.Companion.concat(that, this)
        }
        return null
    }

    override fun toString(): String {
        return net.starlark.java.eval.Starlark.repr(this, net.starlark.java.eval.StarlarkSemantics.DEFAULT)
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        printer.printList(
            elements,
            "",
            java.lang.String.format(
                " %s ",
                com.google.devtools.build.lib.packages.SelectorList.Companion.binaryOpToken(this)
            ),
            "",
            semantics
        )
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(type, elements)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is SelectorList) {
            return false
        }
        val that = other
        return this.type == that.type && this.elements == that.elements
    }

    /** The user-facing API to the `select()` callable.  */
    @com.google.devtools.build.docgen.annot.GlobalMethods(environment = [com.google.devtools.build.docgen.annot.GlobalMethods.Environment.BUILD, com.google.devtools.build.docgen.annot.GlobalMethods.Environment.BZL])
    class SelectLibrary private constructor() {
        @net.starlark.java.annot.StarlarkMethod(
            name = "select",
            doc = ("<code>select()</code> is the helper function that makes a rule attribute "
                    + "<a href=\"\${link common-definitions#configurable-attributes}\">"
                    + "configurable</a>. See "
                    + "<a href=\"\${link functions#select}\">build encyclopedia</a> for details."),
            parameters = [net.starlark.java.annot.Param(
                name = "x",
                positional = true,
                doc = ("A dict that maps configuration conditions to values. Each key is a "
                        + "<a href=\"../builtins/Label.html\">Label</a> or a label string"
                        + " that identifies a config_setting or constraint_value instance. See the"
                        + " <a href=\"https://bazel.build/extending/legacy-macros#label-resolution\">"
                        + "documentation on macros</a> for when to use a Label instead of a string."
                        + " If <code>--incompatible_resolve_select_keys_eagerly</code> is enabled,"
                        + " the keys are resolved to <code>Label</code> objects relative to the"
                        + " package of the file that contains this call to <code>select</code>.")
            ), net.starlark.java.annot.Param(
                name = "no_match_error",
                defaultValue = "''",
                doc = "Optional custom error to report if no condition matches.",
                named = true
            )],
            useStarlarkThread = true
        )
        @Throws(net.starlark.java.eval.EvalException::class)
        fun select(
            dict: net.starlark.java.eval.Dict<*, *>,
            noMatchError: String?,
            thread: net.starlark.java.eval.StarlarkThread
        ): Any {
            // If this is not null, string keys in the dict will be resolved to Labels eagerly using the
            // given context.
            var labelConverter: LabelConverter? = null
            if (thread
                    .getSemantics()
                    .getBool(BuildLanguageOptions.Companion.INCOMPATIBLE_RESOLVE_SELECT_KEYS_EAGERLY)
            ) {
                // Handle the case of an initializer.
                labelConverter = thread.getThreadLocal<LabelConverter?>(LabelConverter::class.java)
                // Handle the case of a regular BUILD thread.
                if (labelConverter == null) {
                    val targetDefinitionContext: TargetDefinitionContext? =
                        TargetDefinitionContext.Companion.fromOrNull(thread)
                    if (targetDefinitionContext != null) {
                        labelConverter = targetDefinitionContext.getLabelConverter()
                    }
                }
                // In all other cases, must be in a .bzl file.
                if (labelConverter == null) {
                    labelConverter = LabelConverter.Companion.forBzlEvaluatingThread(thread)
                }
            }
            try {
                return com.google.devtools.build.lib.packages.SelectorList.Companion.select(
                    dict,
                    noMatchError,
                    labelConverter
                )
            } catch (e: LabelSyntaxException) {
                throw net.starlark.java.eval.Starlark.errorf("invalid label in select(): %s", e.getMessage())
            }
        }

        companion object {
            val INSTANCE: SelectLibrary = SelectLibrary()
        }
    }

    companion object {
        /** Implementation of the Starlark `select()` function exposed to BUILD and .bzl files.  */
        @Throws(net.starlark.java.eval.EvalException::class, LabelSyntaxException::class)
        private fun select(
            dict: net.starlark.java.eval.Dict<*, *>, noMatchError: String?, labelConverter: LabelConverter?
        ): Any {
            if (dict.isEmpty()) {
                throw net.starlark.java.eval.Starlark.errorf(
                    "select({}) with an empty dictionary can never resolve because it includes no conditions"
                            + " to match"
                )
            }
            val selectDict: com.google.common.collect.ImmutableMap.Builder<Any?, Any?> =
                com.google.common.collect.ImmutableMap.builderWithExpectedSize<Any?, Any?>(dict.size())
            for (entry in dict.entrySet()) {
                when (entry.getKey()) {
                    -> selectDict.put(label, entry.getValue())
                    -> selectDict.put(
                        if (labelConverter != null) labelConverter.convert(labelString) else labelString,
                        entry.getValue()
                    )

                    else -> throw net.starlark.java.eval.Starlark.errorf(
                        "select: got %s for dict key, want a Label or label string",
                        net.starlark.java.eval.Starlark.type(entry.getKey())
                    )
                }
            }
            // TODO(#26281): Tighten SelectorValue to accept an ImmutableMap<Label, Object> after flipping
            //  --incompatible_resolve_select_keys_eagerly.
            return com.google.devtools.build.lib.packages.SelectorList.Companion.of(
                SelectorValue(
                    selectDict.buildOrThrow(),
                    noMatchError
                )
            )
        }

        /** Creates a "wrapper" list that consists of a single select.  */
        fun of(selector: SelectorValue): SelectorList {
            return com.google.devtools.build.lib.packages.SelectorList(
                selector.getType(),
                com.google.common.collect.ImmutableList.of<Any?>(selector)
            )
        }

        /**
         * Creates a list from the given sequence of values, which must be non-empty. Each value may be a
         * native type, a select over that type, or a selector list over that type.
         * 
         * @throws EvalException if all values don't have the same underlying type
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun of(values: Iterable<*>): SelectorList {
            com.google.common.base.Preconditions.checkArgument(!com.google.common.collect.Iterables.isEmpty(values))
            val elements: com.google.common.collect.ImmutableList.Builder<Any?> =
                com.google.common.collect.ImmutableList.builder<Any?>()
            var firstValue: Any? = null

            for (value in values) {
                if (value is SelectorList) {
                    elements.addAll(value.elements)
                } else {
                    elements.add(value)
                }
                if (firstValue == null) {
                    firstValue = value
                }
                if (!com.google.devtools.build.lib.packages.SelectorList.Companion.canConcatenate(
                        com.google.devtools.build.lib.packages.SelectorList.Companion.getNativeType(
                            firstValue
                        ), com.google.devtools.build.lib.packages.SelectorList.Companion.getNativeType(value)
                    )
                ) {
                    throw net.starlark.java.eval.Starlark.errorf(
                        "Cannot combine incompatible types (%s, %s)",
                        com.google.devtools.build.lib.packages.SelectorList.Companion.getTypeName(firstValue),
                        com.google.devtools.build.lib.packages.SelectorList.Companion.getTypeName(value)
                    )
                }
            }

            return com.google.devtools.build.lib.packages.SelectorList(
                com.google.devtools.build.lib.packages.SelectorList.Companion.getNativeType(
                    firstValue
                ), elements.build()
            )
        }

        /**
         * Wraps a single value in a `select()` where the default condition maps to the given value
         */
        fun wrapSingleValue(obj: Any): SelectorList {
            return com.google.devtools.build.lib.packages.SelectorList.Companion.of(
                SelectorValue(
                    com.google.common.collect.ImmutableMap.of<String?, Any?>(
                        com.google.devtools.build.lib.packages.BuildType.Selector.Companion.DEFAULT_CONDITION_KEY,
                        obj
                    ), ""
                )
            )
        }

        /**
         * Creates a list that concatenates two values, where each value may be a native type, a select
         * over that type, or a selector list over that type.
         * 
         * @throws EvalException if the values don't have the same underlying type
         */
        @kotlin.jvm.JvmStatic
        @Throws(net.starlark.java.eval.EvalException::class)
        fun concat(x: Any, y: Any?): SelectorList {
            return com.google.devtools.build.lib.packages.SelectorList.Companion.of(java.util.Arrays.asList<Any?>(x, y))
        }

        private fun binaryOpToken(value: Any): net.starlark.java.syntax.TokenKind {
            return if (MutableMap::class.java.isAssignableFrom(
                    com.google.devtools.build.lib.packages.SelectorList.Companion.getNativeType(
                        value
                    )
                )
            ) net.starlark.java.syntax.TokenKind.PIPE else net.starlark.java.syntax.TokenKind.PLUS
        }

        private fun getTypeName(x: Any): String? {
            if (x is SelectorList) {
                return "select of " + Depset.ElementType.of(x.type)
            } else if (x is SelectorValue) {
                return "select of " + Depset.ElementType.of(x.getType())
            } else {
                return net.starlark.java.eval.Starlark.type(x)
            }
        }

        private fun getNativeType(value: Any): java.lang.Class<*>? {
            if (value is SelectorList) {
                return value.type
            } else if (value is SelectorValue) {
                return value.getType()
            } else {
                return value.getClass()
            }
        }

        private fun isMappingType(type: java.lang.Class<*>?): Boolean {
            return MutableMap::class.java.isAssignableFrom(type)
        }

        private fun isListType(type: java.lang.Class<*>?): Boolean {
            return MutableList::class.java.isAssignableFrom(type)
        }

        private fun canConcatenate(type1: java.lang.Class<*>?, type2: java.lang.Class<*>?): Boolean {
            return type1 == type2 || (com.google.devtools.build.lib.packages.SelectorList.Companion.isMappingType(type1) && com.google.devtools.build.lib.packages.SelectorList.Companion.isMappingType(
                type2
            ))
                    || (com.google.devtools.build.lib.packages.SelectorList.Companion.isListType(type1) && com.google.devtools.build.lib.packages.SelectorList.Companion.isListType(
                type2
            ))
        }
    }
}
