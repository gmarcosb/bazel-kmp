// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.Label

/**
 * A function interface allowing rules to specify their set of implicit outputs in a more dynamic
 * way than just simple template-substitution. For example, the set of implicit outputs may be a
 * function of rule attributes.
 * 
 * 
 * In the case that attribute placeholders are configurable attributes, errors will be thrown as
 * output templates are expanded before configurable attributes are resolved.
 * 
 * 
 * In the case that attribute placeholders are invalid, the template string will be left
 * unexpanded.
 */
// TODO(http://b/69387932): refactor this entire class and all callers.
abstract class ImplicitOutputsFunction {
    /**
     * Implicit output functions for Starlark supporting key value access of expanded implicit
     * outputs.
     */
    abstract class StarlarkImplicitOutputsFunction : ImplicitOutputsFunction() {
        @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
        abstract fun calculateOutputs(
            eventHandler: EventHandler?, map: com.google.devtools.build.lib.packages.AttributeMap?
        ): com.google.common.collect.ImmutableMap<String?, String?>?

        @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
        override fun getImplicitOutputs(
            eventHandler: EventHandler?,
            map: com.google.devtools.build.lib.packages.AttributeMap?
        ): Iterable<String?> {
            return calculateOutputs(eventHandler, map).values()
        }
    }

    /** Implicit output functions executing Starlark code.  */
    class StarlarkImplicitOutputsFunctionWithCallback
        (callback: StarlarkCallbackHelper) : StarlarkImplicitOutputsFunction() {
        private val callback: StarlarkCallbackHelper

        init {
            this.callback = callback
        }

        @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
        override fun calculateOutputs(
            eventHandler: EventHandler?, map: com.google.devtools.build.lib.packages.AttributeMap
        ): com.google.common.collect.ImmutableMap<String?, String?> {
            val attrValues: MutableMap<String?, Any?> = HashMap<String?, Any?>()
            for (attrName in map.getAttributeNames()) {
                val attrType: com.google.devtools.build.lib.packages.Type<*>? = map.getAttributeType(attrName)
                // Don't include configurable attributes: we don't know which value they might take
                // since we don't yet have a build configuration.
                if (!map.isConfigurable(attrName)) {
                    val value: Any? = map.get(attrName, attrType)
                    attrValues.put(
                        com.google.devtools.build.lib.packages.Attribute.Companion.getStarlarkName(attrName),
                        com.google.devtools.build.lib.packages.Attribute.Companion.valueToStarlark(value)
                    )
                }
            }
            val attrs: net.starlark.java.eval.Structure =
                StructProvider.Companion.STRUCT.create(
                    attrValues,
                    "Attribute '%s' either doesn't exist "
                            + "or uses a select() (i.e. could have multiple values)"
                )
            try {
                val builder: com.google.common.collect.ImmutableMap.Builder<String?, String?> =
                    com.google.common.collect.ImmutableMap.builder<String?, String?>()
                for (entry in net.starlark.java.eval.Dict.cast<String?, String?>(
                    callback.call(eventHandler, attrs),
                    String::class.java,
                    String::class.java,
                    "implicit outputs function return value"
                )
                    .entrySet()) {
                    // Returns empty string only in case of invalid templates

                    val substitutions: Iterable<String?> =
                        Companion.fromTemplates(entry.getValue()).getImplicitOutputs(eventHandler, map)
                    if (com.google.common.collect.Iterables.isEmpty(substitutions)) {
                        throw net.starlark.java.eval.Starlark.errorf(
                            "For attribute '%s' in outputs: Invalid placeholder(s) in template",
                            entry.getKey()
                        )
                    }

                    builder.put(
                        entry.getKey(),
                        com.google.common.collect.Iterables.getOnlyElement<String?>(substitutions)
                    )
                }
                return builder.buildOrThrow()
            } catch (ex: java.lang.IllegalArgumentException) {
                throw net.starlark.java.eval.EvalException(ex)
            }
        }
    }

    /** Implicit output functions using a simple an output map.  */
    class StarlarkImplicitOutputsFunctionWithMap
        (outputMap: com.google.common.collect.ImmutableMap<String?, String?>) : StarlarkImplicitOutputsFunction() {
        private val outputMap: com.google.common.collect.ImmutableMap<String?, String?>

        init {
            this.outputMap = outputMap
        }

        @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
        override fun calculateOutputs(
            eventHandler: EventHandler?, map: com.google.devtools.build.lib.packages.AttributeMap?
        ): com.google.common.collect.ImmutableMap<String?, String?> {
            val builder: com.google.common.collect.ImmutableMap.Builder<String?, String?> =
                com.google.common.collect.ImmutableMap.builder<String?, String?>()
            for (entry in outputMap.entrySet()) {
                // Empty iff invalid placeholders present.
                val outputsFunction =
                    fromUnsafeTemplates(com.google.common.collect.ImmutableList.of<String?>(entry.getValue()))
                val substitutions = outputsFunction.getImplicitOutputs(eventHandler, map)
                if (com.google.common.collect.Iterables.isEmpty(substitutions)) {
                    throw net.starlark.java.eval.Starlark.errorf(
                        "For attribute '%s' in outputs: Invalid placeholder(s) in template", entry.getKey()
                    )
                }

                builder.put(entry.getKey(), com.google.common.collect.Iterables.getOnlyElement<String?>(substitutions))
            }
            return builder.buildOrThrow()
        }
    }

    /**
     * Implicit output functions which can not throw an EvalException.
     */
    abstract class SafeImplicitOutputsFunction : ImplicitOutputsFunction() {
        @Throws(net.starlark.java.eval.EvalException::class)
        abstract override fun getImplicitOutputs(
            eventHandler: EventHandler?,
            map: com.google.devtools.build.lib.packages.AttributeMap?
        ): Iterable<String?>

        companion object {
            /** The implicit output function that returns no files.  */
            @kotlin.jvm.JvmField
            @SerializationConstant
            val NONE: SafeImplicitOutputsFunction = object : SafeImplicitOutputsFunction() {
                override fun getImplicitOutputs(
                    eventHandler: EventHandler?,
                    rule: com.google.devtools.build.lib.packages.AttributeMap?
                ): Iterable<String?> {
                    return Collections.emptyList<String?>()
                }
            }
        }
    }

    /**
     * An interface to objects that can retrieve rule attributes.
     */
    interface AttributeValueGetter {
        /**
         * Returns the value(s) of attribute "attr" in "rule", or empty set if attribute unknown.
         * 
         * @throws EvalException if the getter does not support attributes of the given type
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun get(rule: com.google.devtools.build.lib.packages.AttributeMap?, attr: String?): MutableSet<String?>
    }

    /**
     * Given a newly-constructed Rule instance (with attributes populated), returns the list of output
     * files that this rule produces implicitly.
     */
    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    abstract fun getImplicitOutputs(
        eventHandler: EventHandler?,
        rule: com.google.devtools.build.lib.packages.AttributeMap?
    ): Iterable<String?>

    private class TemplateImplicitOutputsFunction(templates: Iterable<String>) : SafeImplicitOutputsFunction() {
        private val templates: Iterable<String>

        init {
            this.templates = templates
        }

        // TODO(bazel-team): parse the templates already here
        @Throws(net.starlark.java.eval.EvalException::class)
        override fun getImplicitOutputs(
            eventHandler: EventHandler?,
            rule: com.google.devtools.build.lib.packages.AttributeMap?
        ): Iterable<String?> {
            val result: com.google.common.collect.ImmutableSet.Builder<String?> =
                com.google.common.collect.ImmutableSet.Builder<String?>()
            for (template in templates) {
                val substitutions: MutableList<String?> = substitutePlaceholderIntoTemplate(template, rule)
                if (substitutions.isEmpty()) {
                    continue
                }
                result.addAll(substitutions)
            }

            return result.build()
        }

        override fun toString(): String {
            return com.google.devtools.build.lib.util.StringUtil.joinEnglishList(templates)
        }
    }

    private class UnsafeTemplatesImplicitOutputsFunction(templates: Iterable<String>) : ImplicitOutputsFunction() {
        private val templates: Iterable<String>

        init {
            this.templates = templates
        }

        // TODO(bazel-team): parse the templates already here
        @Throws(net.starlark.java.eval.EvalException::class)
        override fun getImplicitOutputs(
            eventHandler: EventHandler?,
            rule: com.google.devtools.build.lib.packages.AttributeMap
        ): Iterable<String?> {
            val result: com.google.common.collect.ImmutableSet.Builder<String?> =
                com.google.common.collect.ImmutableSet.Builder<String?>()
            for (template in templates) {
                val substitutions: MutableList<String?> =
                    substitutePlaceholderIntoUnsafeTemplate(
                        template,
                        rule,
                        AttributeValueGetter { rule: com.google.devtools.build.lib.packages.AttributeMap?, attrName: String? ->
                            Companion.attributeValues(
                                rule,
                                attrName!!
                            )
                        })
                if (substitutions.isEmpty()) {
                    continue
                }
                result.addAll(substitutions)
            }

            return result.build()
        }

        override fun toString(): String {
            return com.google.devtools.build.lib.util.StringUtil.joinEnglishList(templates)
        }
    }

    private class FunctionCombinationImplicitOutputsFunction
        (functions: Iterable<SafeImplicitOutputsFunction>) : SafeImplicitOutputsFunction() {
        private val functions: Iterable<SafeImplicitOutputsFunction>

        init {
            this.functions = functions
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun getImplicitOutputs(
            eventHandler: EventHandler?,
            rule: com.google.devtools.build.lib.packages.AttributeMap?
        ): Iterable<String?> {
            val result: MutableCollection<String?> = LinkedHashSet<String?>()
            for (function in functions) {
                com.google.common.collect.Iterables.addAll<String?>(
                    result,
                    function.getImplicitOutputs(eventHandler, rule)
                )
            }
            return result
        }

        override fun toString(): String {
            return com.google.devtools.build.lib.util.StringUtil.joinEnglishList(functions)
        }
    }

    @kotlin.jvm.JvmRecord
    internal data class ParsedTemplate(template: String?, formatStr: String?, attributeNames: MutableList<String?>?) {
        @Throws(net.starlark.java.eval.EvalException::class)
        fun substituteAttributes(
            attributeMap: com.google.devtools.build.lib.packages.AttributeMap?, attributeGetter: AttributeValueGetter
        ): com.google.common.collect.ImmutableList<String?> {
            if (this.attributeNames!!.isEmpty()) {
                return com.google.common.collect.ImmutableList.of<String?>(this.template)
            }

            val values: MutableList<MutableSet<String?>?> =
                com.google.common.collect.Lists.newArrayListWithCapacity<MutableSet<String?>?>(this.attributeNames.size())
            for (placeholder in this.attributeNames) {
                val attrValues = attributeGetter.get(attributeMap, placeholder)
                if (attrValues.isEmpty()) {
                    return com.google.common.collect.ImmutableList.of<String?>()
                }
                values.add(attrValues)
            }
            val out: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.Builder<String?>()
            for (combination in com.google.common.collect.Sets.cartesianProduct<String?>(values)) {
                out.add(java.lang.String.format(this.formatStr, *combination.toArray()))
            }
            return out.build()
        }

        val template: String?
        val formatStr: String?
        val attributeNames: MutableList<String?>?

        init {
            this.attributeNames = attributeNames
            this.formatStr = formatStr
            this.template = template
            java.util.Objects.requireNonNull<String?>(template, "template")
            java.util.Objects.requireNonNull<String?>(formatStr, "formatStr")
            java.util.Objects.requireNonNull<MutableList<String?>?>(attributeNames, "attributeNames")
        }

        companion object {
            @kotlin.jvm.JvmStatic
            fun parse(rawTemplate: String): ParsedTemplate {
                var placeholders: MutableList<String?> = java.util.ArrayList<String?>()
                val formatStr = createPlaceholderSubstitutionFormatString(rawTemplate, placeholders)
                if (placeholders.isEmpty()) {
                    placeholders = com.google.common.collect.ImmutableList.of<String?>()
                }
                return ParsedTemplate(rawTemplate, formatStr, placeholders)
            }
        }
    }

    companion object {
        private val PERCENT_ESCAPER: com.google.common.escape.Escaper =
            com.google.common.escape.Escapers.builder().addEscape('%', "%%").build()

        /**
         * A convenience wrapper for [.fromTemplates].
         */
        @kotlin.jvm.JvmStatic
        fun fromTemplates(vararg templates: String?): SafeImplicitOutputsFunction {
            return Companion.fromTemplates(java.util.Arrays.asList<String?>(*templates))
        }

        /**
         * The implicit output function that generates files based on a set of template substitutions
         * using rule attribute values.
         * 
         * 
         * This is not, actually, safe, and any use of configurable attributes will cause a hard
         * failure.
         * 
         * @param templates The templates used to construct the name of the implicit output file target.
         * The substring "%{foo}" will be replaced by the value of the attribute "foo". If multiple
         * %{} substrings exist, the cross-product of them is generated.
         */
        fun fromTemplates(templates: Iterable<String>): SafeImplicitOutputsFunction {
            return TemplateImplicitOutputsFunction(templates)
        }

        /**
         * The implicit output function that generates files based on a set of template substitutions
         * using rule attribute values.
         * 
         * 
         * This is not, actually, safe, and any use of configurable attributes will cause a hard
         * failure.
         * 
         * @param templates The templates used to construct the name of the implicit output file target.
         * The substring "%{foo}" will be replaced by the value of the attribute "foo". If multiple
         * %{} substrings exist, the cross-product of them is generated.
         */
        // It would be nice to unify this with fromTemplates above, but that's not possible because
        // substitutePlaceholderIntoUnsafeTemplate can throw an exception.
        private fun fromUnsafeTemplates(templates: Iterable<String>): ImplicitOutputsFunction {
            return UnsafeTemplatesImplicitOutputsFunction(templates)
        }

        /** A convenience wrapper for [.fromFunctions].  */
        @kotlin.jvm.JvmStatic
        fun fromFunctions(
            vararg functions: SafeImplicitOutputsFunction?
        ): SafeImplicitOutputsFunction {
            return Companion.fromFunctions(java.util.Arrays.asList<SafeImplicitOutputsFunction?>(*functions))
        }

        /**
         * The implicit output function that generates files based on a set of template substitutions
         * using rule attribute values.
         * 
         * @param functions The functions used to construct the name of the implicit output file target.
         * The substring "%{name}" will be replaced by the actual name of the rule, the substring
         * "%{srcs}" will be replaced by the name of each source file without its extension. If
         * multiple %{} substrings exist, the cross-product of them is generated.
         */
        fun fromFunctions(
            functions: Iterable<SafeImplicitOutputsFunction>
        ): SafeImplicitOutputsFunction {
            return FunctionCombinationImplicitOutputsFunction(functions)
        }

        /**
         * Coerces attribute "attrName" of the specified rule into a sequence of strings. Helper function
         * for [.fromTemplates].
         * 
         * @throws EvalException if outputs templates don't support attributes of the given type.
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        private fun attributeValues(
            rule: com.google.devtools.build.lib.packages.AttributeMap,
            attrName: String
        ): com.google.common.collect.ImmutableSet<String?> {
            if (attrName == "dirname") {
                val dir: PathFragment? = PathFragment.create(rule.getLabel().name).getParentDirectory()
                return if (dir.isEmpty()) com.google.common.collect.ImmutableSet.of<String?>("") else com.google.common.collect.ImmutableSet.of<String?>(
                    dir.getPathString() + "/"
                )
            } else if (attrName == "basename") {
                return com.google.common.collect.ImmutableSet.of<String?>(
                    PathFragment.create(rule.getLabel().name).getBaseName()
                )
            }

            val attrType: com.google.devtools.build.lib.packages.Type<*>? = rule.getAttributeType(attrName)
            if (attrType == null) {
                return com.google.common.collect.ImmutableSet.of<String?>()
            }
            // String attributes and lists are easy.
            if (com.google.devtools.build.lib.packages.Type.Companion.STRING === attrType) {
                return com.google.common.collect.ImmutableSet.of<String?>(
                    rule.get<String?>(
                        attrName,
                        com.google.devtools.build.lib.packages.Type.Companion.STRING
                    )
                )
            } else if (com.google.devtools.build.lib.packages.Type.Companion.STRING_NO_INTERN === attrType) {
                return com.google.common.collect.ImmutableSet.of<String?>(
                    rule.get<String?>(
                        attrName,
                        com.google.devtools.build.lib.packages.Type.Companion.STRING_NO_INTERN
                    )
                )
            } else if (com.google.devtools.build.lib.packages.Types.STRING_LIST === attrType) {
                return com.google.common.collect.ImmutableSet.copyOf<String?>(
                    rule.get<MutableList<String?>?>(
                        attrName,
                        com.google.devtools.build.lib.packages.Types.STRING_LIST
                    )
                )
            } else if (BuildType.LABEL === attrType) {
                // Labels are most often used to change the extension,
                // e.g. %.foo -> %.java, so we return the basename w/o extension.
                val label: Label? = rule.get<Label?>(attrName, BuildType.LABEL)
                return com.google.common.collect.ImmutableSet.of<E?>(
                    com.google.devtools.build.lib.vfs.FileSystemUtils.removeExtension(
                        label.name
                    )
                )
            } else if (BuildType.LABEL_LIST === attrType) {
                // Labels are most often used to change the extension,
                // e.g. %.foo -> %.java, so we return the basename w/o extension.
                return rule.get<MutableList<Label?>?>(attrName, BuildType.LABEL_LIST).stream()
                    .map<Any?>(java.util.function.Function { label: Label? ->
                        com.google.devtools.build.lib.vfs.FileSystemUtils.removeExtension(
                            label.name
                        )
                    })
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())
            } else if (BuildType.OUTPUT === attrType) {
                val out: Label? = rule.get<Label?>(attrName, BuildType.OUTPUT)
                return com.google.common.collect.ImmutableSet.of<String?>(out.name)
            } else if (BuildType.OUTPUT_LIST === attrType) {
                return rule.get<MutableList<Label?>?>(attrName, BuildType.OUTPUT_LIST).stream()
                    .map<Any?>(Label::getName)
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())
            }
            throw net.starlark.java.eval.Starlark.errorf(
                "For attribute '%s' in outputs: Attributes of type %s cannot be used in an outputs"
                        + " substitution template",
                attrName, attrType
            )
        }

        /**
         * Collects all named placeholders from the template while replacing them with %s.
         * 
         * 
         * Example: for `template` "%{name}_%{locales}.foo", it will return "%s_%s.foo" and
         * store "name" and "locales" in `placeholders`.
         * 
         * 
         * Incomplete placeholders are treated like text: for "a-%{x}-%{y" this method returns
         * "a-%s-%%{y" and stores "x" in `placeholders`.
         * 
         * @param template a string with placeholders of the format %{...}
         * @param placeholders a collection to collect placeholders into; may contain duplicates if not a
         * Set
         * @return a format string for [String.format], created from the template string with every
         * placeholder replaced by %s
         */
        fun createPlaceholderSubstitutionFormatString(
            template: String,
            placeholders: MutableCollection<String?>
        ): String? {
            return createPlaceholderSubstitutionFormatStringRecursive(
                template, placeholders,
                java.lang.StringBuilder()
            )
        }

        private fun createPlaceholderSubstitutionFormatStringRecursive(
            template: String,
            placeholders: MutableCollection<String?>, formatBuilder: java.lang.StringBuilder
        ): String? {
            val start: Int = template.indexOf("%{")
            if (start < 0) {
                return formatBuilder.append(PERCENT_ESCAPER.escape(template)).toString()
            }

            val end: Int = template.indexOf('}'.code, start + 2)
            if (end < 0) {
                return formatBuilder.append(PERCENT_ESCAPER.escape(template)).toString()
            }

            formatBuilder.append(PERCENT_ESCAPER.escape(template.substring(0, start))).append("%s")
            placeholders.add(template.substring(start + 2, end))
            return createPlaceholderSubstitutionFormatStringRecursive(
                template.substring(end + 1),
                placeholders, formatBuilder
            )
        }

        /**
         * Substitutes attribute-placeholders in a template string, producing all possible combinations.
         * 
         * @param template the template string, may contain named placeholders for rule attributes, like
         * `%{name}` or `%{deps}`
         * @param rule the rule whose attributes the placeholders correspond to
         * @param attributeGetter a helper for fetching attribute values
         * @return all possible combinations of the attributes referenced by the placeholders, substituted
         * into the template; empty if any of the placeholders expands to no values
         */
        /**
         * Given a template string, replaces all placeholders of the form %{...} with the values from
         * attributeSource. If there are multiple placeholders, then the output is the cross product of
         * substitutions.
         */
        @kotlin.jvm.JvmOverloads
        @Throws(net.starlark.java.eval.EvalException::class)
        fun substitutePlaceholderIntoTemplate(
            template: String,
            rule: com.google.devtools.build.lib.packages.AttributeMap?,
            attributeGetter: AttributeValueGetter = AttributeValueGetter { rule: com.google.devtools.build.lib.packages.AttributeMap?, attrName: String? ->
                Companion.attributeValues(
                    rule,
                    attrName!!
                )
            }
        ): com.google.common.collect.ImmutableList<String?> {
            // Parse the template to get the attribute names and format string.
            val parsedTemplate = ParsedTemplate.Companion.parse(template)

            // Return the substituted strings.
            return parsedTemplate.substituteAttributes(rule, attributeGetter)
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun substitutePlaceholderIntoUnsafeTemplate(
            unsafeTemplate: String,
            rule: com.google.devtools.build.lib.packages.AttributeMap,
            attributeGetter: AttributeValueGetter
        ): com.google.common.collect.ImmutableList<String?> {
            // Parse the template to get the attribute names and format string.
            val parsedTemplate = ParsedTemplate.Companion.parse(unsafeTemplate)

            // Make sure all attributes are valid.
            for (placeholder in parsedTemplate.attributeNames!!) {
                if (rule.isConfigurable(placeholder)) {
                    throw net.starlark.java.eval.Starlark.errorf(
                        "Attribute %s is configurable and cannot be used in outputs", placeholder
                    )
                }
            }

            // Return the substituted strings.
            return parsedTemplate.substituteAttributes(rule, attributeGetter)
        }
    }
}
