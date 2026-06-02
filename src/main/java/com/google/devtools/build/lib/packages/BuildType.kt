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

import com.google.devtools.build.lib.cmdline.Label

/**
 * Collection of data types that are specific to building things, i.e. not inherent to Starlark.
 * 
 * 
 * BEFORE YOU ADD A NEW TYPE: See javadoc in [Type].
 */
object BuildType {
    /**
     * The type of a label. Labels are not actually a first-class datatype in the build language, but
     * they are so frequently used in the definitions of attributes that it's worth treating them
     * specially (and providing support for resolution of relative-labels in the `convert()
    ` *  method).
     */
    @kotlin.jvm.JvmField
    @SerializationConstant
    val LABEL: com.google.devtools.build.lib.packages.Type<Label> = LabelType(LabelClass.DEPENDENCY)

    /** The type of a dictionary of [labels][.LABEL].  */
    @kotlin.jvm.JvmField
    @SerializationConstant
    val LABEL_DICT_UNARY: com.google.devtools.build.lib.packages.Type.DictType<String?, Label?> =
        com.google.devtools.build.lib.packages.Type.DictType.Companion.create<String?, Label?>(
            com.google.devtools.build.lib.packages.Type.Companion.STRING,
            LABEL
        )

    /** The type of a dictionary keyed by [labels][.LABEL] with string values.  */
    @kotlin.jvm.JvmField
    @SerializationConstant
    val LABEL_KEYED_STRING_DICT: com.google.devtools.build.lib.packages.Type.DictType<Label?, String?> =
        LabelKeyedDictType.Companion.create<String?>(com.google.devtools.build.lib.packages.Type.Companion.STRING)

    /** The type of a list of [labels][.LABEL].  */
    @kotlin.jvm.JvmField
    @SerializationConstant
    val LABEL_LIST: com.google.devtools.build.lib.packages.Type.ListType<Label?> =
        com.google.devtools.build.lib.packages.Type.ListType.Companion.create<Label?>(
            LABEL
        )

    /** The type of a dictionary of [label lists][.LABEL_LIST].  */
    @kotlin.jvm.JvmField
    @SerializationConstant
    val LABEL_LIST_DICT: com.google.devtools.build.lib.packages.Type.DictType<String?, MutableList<Label?>?> =
        com.google.devtools.build.lib.packages.Type.DictType.Companion.create<String?, MutableList<Label?>?>(
            com.google.devtools.build.lib.packages.Type.Companion.STRING,
            LABEL_LIST
        )

    /**
     * This is a label type that does not cause dependencies. It is needed because certain rules want
     * to verify the type of a target referenced by one of their attributes, but if there was a
     * dependency edge there, it would be a circular dependency.
     */
    @kotlin.jvm.JvmField
    @SerializationConstant
    val NODEP_LABEL: com.google.devtools.build.lib.packages.Type<Label?> = LabelType(LabelClass.NONDEP_REFERENCE)

    /** The type of a list of [labels][.NODEP_LABEL] that do not cause dependencies.  */
    @kotlin.jvm.JvmField
    @SerializationConstant
    val NODEP_LABEL_LIST: com.google.devtools.build.lib.packages.Type.ListType<Label?> =
        com.google.devtools.build.lib.packages.Type.ListType.Companion.create<Label?>(
            NODEP_LABEL
        )

    @kotlin.jvm.JvmField
    @SerializationConstant
    val DORMANT_LABEL: com.google.devtools.build.lib.packages.Type<Label?> =
        LabelType(LabelClass.GENQUERY_SCOPE_REFERENCE)

    @kotlin.jvm.JvmField
    @SerializationConstant
    val DORMANT_LABEL_LIST: com.google.devtools.build.lib.packages.Type.ListType<Label?> =
        com.google.devtools.build.lib.packages.Type.ListType.Companion.create<Label?>(
            DORMANT_LABEL
        )

    /**
     * This is a label type that causes dependencies, but the dependencies are NOT to be configured.
     * Does not say anything about whether the attribute of this type is itself configurable.
     * 
     * 
     * Without a special type to handle genquery.scope, configuring a genquery target ends up
     * configuring the transitive closure of genquery.scope. Since genquery rule implementation loads
     * the deps through TransitiveTargetFunction, it doesn't need them to be configured. Preventing
     * the dependencies of scope from being configured, lets us save some resources.
     */
    @kotlin.jvm.JvmField
    @SerializationConstant
    val GENQUERY_SCOPE_TYPE: com.google.devtools.build.lib.packages.Type<Label?> =
        LabelType(LabelClass.GENQUERY_SCOPE_REFERENCE)

    /**
     * This is a label type that causes dependencies, but the dependencies are NOT to be configured.
     * Does not say anything about whether the attribute of this type is itself configurable.
     */
    @kotlin.jvm.JvmField
    @SerializationConstant
    val GENQUERY_SCOPE_TYPE_LIST: com.google.devtools.build.lib.packages.Type.ListType<Label?> =
        com.google.devtools.build.lib.packages.Type.ListType.Companion.create<Label?>(
            GENQUERY_SCOPE_TYPE
        )

    /**
     * The type of a license. Like Label, licenses aren't first-class, but they're important enough to
     * justify early syntax error detection.
     */
    @kotlin.jvm.JvmField
    @SerializationConstant
    val LICENSE: com.google.devtools.build.lib.packages.Type<License?> = LicenseType()

    /** The type of an output file, treated as a [.LABEL].  */
    @kotlin.jvm.JvmField
    @SerializationConstant
    val OUTPUT: com.google.devtools.build.lib.packages.Type<Label?> = OutputType()

    private val whyNotConfigurable: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.packages.Type<*>?, String?> =
        com.google.common.collect.ImmutableMap.builder<com.google.devtools.build.lib.packages.Type<*>?, String?>()
            .put(LICENSE, "loading phase license checking logic assumes non-configurable values")
            .put(OUTPUT, "output paths are part of the static graph structure")
            .buildOrThrow()

    /** The type of a list of [outputs][.OUTPUT].  */
    @kotlin.jvm.JvmField
    @SerializationConstant
    val OUTPUT_LIST: com.google.devtools.build.lib.packages.Type.ListType<Label?> =
        com.google.devtools.build.lib.packages.Type.ListType.Companion.create<Label?>(
            OUTPUT
        )

    /** The type of a TriState with values: true (x>0), false (x==0), auto (x<0).  */
    @kotlin.jvm.JvmField
    @SerializationConstant
    val TRISTATE: com.google.devtools.build.lib.packages.Type<com.google.devtools.build.lib.packages.TriState?> =
        TriStateType()

    /** Returns whether the specified type is a label type or not.  */
    fun isLabelType(type: com.google.devtools.build.lib.packages.Type<*>): Boolean {
        return type.getLabelClass() != LabelClass.NONE
    }

    /**
     * Variation of [Type.convert] that supports selector expressions for configurable
     * attributes (i.e. "{ config1: 'value1_of_orig_type', config2: 'value2_of_orig_type; }"). If x is
     * a selector expression, returns a [SelectorList] instance that contains key-mapped entries
     * of the native type. Else, returns the native type directly.
     * 
     * 
     * If `simplifyUnconditionalSelects` is true, then an unconditional select is simplified
     * to the select's value converted to a native value; and a concatenation of unconditional selects
     * (and direct values, if any) is simplified to a concatenation of the select's values and the
     * direct values converted to native values. In other words, `["//x"] + select("//conditions:default": ["//y"])` becomes `[Label("//x"), Label("//y")]`. If a
     * concatenation contains a non-unconditional select, the concatenation is not simplified.
     * 
     * 
     * Returns null iff `simplifyUnconditionalSelects` is true, `x` is `select({"//conditions:default": None})`, and the `type.getDefaultValue()` is null.
     * 
     * 
     * The caller is responsible for casting the returned value appropriately.
     */
    @Throws(ConversionException::class)
    fun <T> selectableConvert(
        type: com.google.devtools.build.lib.packages.Type<T?>,
        x: Any?,
        what: Any?,
        context: LabelConverter?,
        simplifyUnconditionalSelects: Boolean
    ): Any? {
        if (x is com.google.devtools.build.lib.packages.SelectorList) {
            val selectorListElements: MutableList<Any?> = x.getElements()
            if (!simplifyUnconditionalSelects) {
                return SelectorList<T?>(selectorListElements, what, context, type)
            }
            if (selectorListElements.size() > 1 && type.concat(com.google.common.collect.ImmutableList.of<T?>()) == null) {
                throw ConversionException(
                    java.lang.String.format("type '%s' doesn't support select concatenation", type)
                )
            }
            // Note: ArrayList, not ImmutableList, because we may insert a null into it; the default value
            // of an unconditional Selector<T> is null if the SelectorValue value is None and the native
            // type's default value is null.
            val values: java.util.ArrayList<T?> = java.util.ArrayList<T?>(selectorListElements.size())
            for (element in selectorListElements) {
                if (element is SelectorValue) {
                    val dictionary: com.google.common.collect.ImmutableMap<*, *> = element.getDictionary()
                    if (dictionary.size() != 1) {
                        // Cannot simplify: selectorValue has multiple branches.
                        return SelectorList<T?>(selectorListElements, what, context, type)
                    }
                    val selector =
                        Selector<T?>(dictionary, what, context, type, element.getNoMatchError())
                    if (!selector.isUnconditional()) {
                        // Cannot simplify: the only branch is not the default condition.
                        return SelectorList<T?>(selectorListElements, what, context, type)
                    }
                    values.add(selector.getDefault())
                } else {
                    values.add(type.convert(element, what, context))
                }
            }
            if (values.size() == 1) {
                return values.getFirst()
            } else {
                return type.concat(values)
            }
        } else {
            return type.convert(x, what, context)
        }
    }

    /**
     * Converts the build-language-typed `buildLangValue` to a native value via [ ][BuildType.selectableConvert]. Canonicalizes the value's order if it is a [List] type and
     * `attr.isOrderIndependent()` returns `true`.
     * 
     * 
     * Returns null iff `simplifyUnconditionalSelects` is true, `buildLangValue` is
     * `select({"//conditions:default": None})`, and `attr.getType().getDefaultValue()` is
     * null.
     * 
     * 
     * Throws [ConversionException] if the conversion fails, or if `buildLangValue` is
     * a selector expression but `attr.isConfigurable()` is `false`.
     */
    @Throws(ConversionException::class)
    fun convertFromBuildLangType(
        ruleClass: String?,
        attr: com.google.devtools.build.lib.packages.Attribute,
        buildLangValue: Any?,
        labelConverter: LabelConverter?,
        listInterner: com.google.common.collect.Interner<com.google.common.collect.ImmutableList<*>?>,
        simplifyUnconditionalSelects: Boolean
    ): Any? {
        if ((buildLangValue is com.google.devtools.build.lib.packages.SelectorList)
            && !attr.isConfigurable()
        ) {
            throw ConversionException(
                java.lang.String.format("attribute \"%s\" is not configurable", attr.getName())
            )
        }

        var converted =
            selectableConvert(
                attr.getType(),
                buildLangValue,
                AttributeConversionContext(attr.getName(), ruleClass),
                labelConverter,
                simplifyUnconditionalSelects
            )

        if (converted is MutableList<*>) {
            if (attr.isOrderIndependent()) {
                val list = converted as MutableList<out Comparable<*>?>
                converted = com.google.common.collect.Ordering.natural<Comparable<*>?>().sortedCopy(list)
            }
            // It's common for multiple rule instances in the same package to have the same value for some
            // attributes. As a concrete example, consider a package having several 'java_test' instances,
            // each with the same exact 'tags' attribute value.
            converted = listInterner.intern(com.google.common.collect.ImmutableList.copyOf(converted as MutableList<*>))
        }

        return converted
    }

    /** Copies a Starlark SelectorList converting label strings to Label objects.  */
    @Throws(ConversionException::class)
    private fun <T> copyAndLiftSelectorList(
        type: com.google.devtools.build.lib.packages.Type<T?>,
        x: com.google.devtools.build.lib.packages.SelectorList,
        what: Any?,
        context: LabelConverter?
    ): Any {
        val elements: MutableList<Any?> = x.getElements()
        try {
            if (elements.size() > 1 && type.concat(com.google.common.collect.ImmutableList.of<T?>()) == null) {
                throw ConversionException(
                    java.lang.String.format("type '%s' doesn't support select concatenation", type)
                )
            }

            val builder: com.google.common.collect.ImmutableList.Builder<Any?> =
                com.google.common.collect.ImmutableList.builder<Any?>()
            for (elem in elements) {
                val newMap: com.google.common.collect.ImmutableMap.Builder<Label?, Any?> =
                    com.google.common.collect.ImmutableMap.builder<Label?, Any?>()
                if (elem is SelectorValue) {
                    for (entry in (elem as SelectorValue).getDictionary().entrySet()) {
                        val key: Label? = LABEL.convert(entry.getKey(), what, context)
                        newMap.put(
                            key,
                            if (entry.getValue() === net.starlark.java.eval.Starlark.NONE)
                                net.starlark.java.eval.Starlark.NONE
                            else
                                type.copyAndLiftStarlarkValue(
                                    entry.getValue(), SelectBranchMessage(what, key), context
                                )
                        )
                    }
                    builder.add(
                        SelectorValue(
                            newMap.buildKeepingLast(), (elem as SelectorValue).getNoMatchError()
                        )
                    )
                } else {
                    val directValue: Any = type.copyAndLiftStarlarkValue(elem, what, context)
                    builder.add(directValue)
                }
            }
            return com.google.devtools.build.lib.packages.SelectorList.Companion.of(builder.build())
        } catch (e: net.starlark.java.eval.EvalException) {
            throw ConversionException(e.getMessage())
        }
    }

    /**
     * Copies a Starlark value to immutable ones and converts label strings to Label objects.
     * 
     * 
     * `attrOwner` is the name of the rule or macro on which the attribute is defined, e.g.
     * "cc_library".
     * 
     * 
     * All Starlark values are also type checked.
     * 
     * 
     * In comparison to [.convertFromBuildLangType] unordered attributes are not
     * canonicalized or interned.
     * 
     * 
     * Use the function before passing the values to initializers.
     * 
     * @throws ConversionException if the `starlarkValue` doesn't match the type of attr or if
     * `starlarkValue` is a selector expression but `attr.isConfigurable()` is `false`.
     */
    @Throws(ConversionException::class)
    fun copyAndLiftStarlarkValue(
        attrOwner: String?,
        attr: com.google.devtools.build.lib.packages.Attribute,
        starlarkValue: Any?,
        labelConverter: LabelConverter?
    ): Any {
        if (starlarkValue is com.google.devtools.build.lib.packages.SelectorList) {
            if (!attr.isConfigurable()) {
                throw ConversionException(
                    java.lang.String.format("attribute \"%s\" is not configurable", attr.getName())
                )
            }
            return copyAndLiftSelectorList(
                attr.getType(),
                starlarkValue as com.google.devtools.build.lib.packages.SelectorList,
                AttributeConversionContext(attr.getName(), attrOwner),
                labelConverter
            )
        } else {
            return attr.getType()
                .copyAndLiftStarlarkValue(
                    starlarkValue,
                    AttributeConversionContext(attr.getName(), attrOwner),
                    labelConverter
                )
        }
    }

    /**
     * If the given attribute type is non-configurable, returns the reason why. Otherwise, returns
     * `null`.
     */
    fun maybeGetNonConfigurableReason(type: com.google.devtools.build.lib.packages.Type<*>?): String? {
        return whyNotConfigurable.get(type)
    }

    /**
     * A pair of an attribute name and owner, with a toString that includes both.
     * 
     * 
     * This is used to defer stringifying this information until needed for an error message, so as
     * to avoid generating unnecessary garbage.
     */
    private class AttributeConversionContext
    /**
     * Constructs a new context object from a pair of strings.
     * 
     * @param attrName an attribute name, such as "deps"
     * @param attrOwner a rule or macro on which the attribute is defined, e.g. "cc_library"
     */(private val attrName: String?, private val attrOwner: String?) {
        override fun toString(): String {
            return java.lang.String.format("attribute '%s' of '%s'", attrName, attrOwner)
        }
    }

    private class LabelType(labelClass: LabelClass?) : com.google.devtools.build.lib.packages.Type<Label?>() {
        private val labelClass: LabelClass?

        init {
            this.labelClass = labelClass
        }

        override fun cast(value: Any?): Label? {
            return value as Label?
        }

        override fun getDefaultValue(): Label? {
            return null // Labels have no default value
        }

        override fun visitLabels(
            visitor: com.google.devtools.build.lib.packages.Type.LabelVisitor,
            value: Label?,
            context: com.google.devtools.build.lib.packages.Attribute?
        ) {
            visitor.visit(value, context)
        }

        override fun toString(): String {
            return "label"
        }

        override fun getLabelClass(): LabelClass? {
            return labelClass
        }

        @Throws(ConversionException::class)
        override fun convert(x: Any?, what: Any?, labelConverter: LabelConverter?): Label? {
            if (x is Label) {
                return x as Label?
            }
            if (x !is String) {
                throw ConversionException(com.google.devtools.build.lib.packages.Type.Companion.STRING, x, what)
            }
            try {
                if (labelConverter == null) {
                    return Label.parseCanonical(x)
                }
                return labelConverter.convert(x)
            } catch (e: LabelSyntaxException) {
                throw ConversionException(
                    "invalid label '" + x + "' in " + what + ": " + e.getMessage()
                )
            }
        }
    }

    /**
     * Dictionary type specialized for label keys, which is able to detect collisions caused by the
     * fact that labels have multiple equivalent representations in Starlark code.
     */
    private class LabelKeyedDictType<ValueT>(valueType: com.google.devtools.build.lib.packages.Type<ValueT?>?) :
        com.google.devtools.build.lib.packages.Type.DictType<Label?, ValueT?>(
            LABEL, valueType, LabelClass.DEPENDENCY
        ) {
        @Throws(ConversionException::class)
        override fun convert(x: Any?, what: Any?, labelConverter: LabelConverter?): MutableMap<Label?, ValueT?> {
            val result: MutableMap<Label?, ValueT?> = super.convert(x, what, labelConverter)
            // The input is known to be a map because super.convert succeeded; otherwise, a
            // ConversionException would have been thrown.
            val input = x as MutableMap<*, *>

            if (input.size() == result.size()) {
                // No collisions found. Exit early.
                return result
            }
            // Look for collisions in order to produce a nicer error message.
            val convertedFrom: MutableMap<Label?, MutableList<Any?>?> = LinkedHashMap<Label?, MutableList<Any?>?>()
            for (original in input.keySet()) {
                val label: Label? = LABEL.convert(original, what, labelConverter)
                convertedFrom.computeIfAbsent(
                    label,
                    java.util.function.Function { k: Label? -> java.util.ArrayList<Any?>() }).add(original)
            }
            val errorMessage: net.starlark.java.eval.Printer = net.starlark.java.eval.Printer()
            errorMessage.append("duplicate labels")
            if (what != null) {
                errorMessage.append(" in ").append(what.toString())
            }
            errorMessage.append(':')
            var isFirstEntry = true
            for (entry in convertedFrom.entrySet()) {
                if (entry.getValue().size() == 1) {
                    continue
                }
                if (isFirstEntry) {
                    isFirstEntry = false
                } else {
                    errorMessage.append(',')
                }
                errorMessage.append(' ')
                errorMessage.append(entry.getKey().getCanonicalForm())
                errorMessage.append(" (as ")
                errorMessage.repr(entry.getValue(), net.starlark.java.eval.StarlarkSemantics.DEFAULT)
                errorMessage.append(')')
            }
            throw ConversionException(errorMessage.toString())
        }

        companion object {
            fun <ValueT> create(valueType: com.google.devtools.build.lib.packages.Type<ValueT?>): LabelKeyedDictType<ValueT?> {
                com.google.common.base.Preconditions.checkArgument(
                    valueType.getLabelClass() == LabelClass.NONE
                            || valueType.getLabelClass() == LabelClass.DEPENDENCY,
                    "Values associated with label keys must not be labels themselves."
                )
                return LabelKeyedDictType<ValueT?>(valueType)
            }
        }
    }

    /**
     * Like Label, LicenseType is a derived type, which is declared specially in order to allow syntax
     * validation. It represents the licenses, as described in [License].
     */
    class LicenseType : com.google.devtools.build.lib.packages.Type<License?>() {
        override fun cast(value: Any?): License? {
            return value as License?
        }

        @Throws(ConversionException::class)
        override fun convert(x: Any?, what: Any?, labelConverter: LabelConverter?): License? {
            try {
                val licenseStrings: MutableList<String?> =
                    com.google.devtools.build.lib.packages.Types.STRING_LIST.convert(x, what)
                return License.Companion.parseLicense(licenseStrings)
            } catch (e: LicenseParsingException) {
                throw ConversionException(e.getMessage())
            }
        }

        @Throws(ConversionException::class)
        override fun copyAndLiftStarlarkValue(
            x: Any?, what: Any?, labelConverter: LabelConverter?
        ): Any? {
            return com.google.devtools.build.lib.packages.Types.STRING_LIST.copyAndLiftStarlarkValue(
                x,
                what,
                labelConverter
            )
        }

        override fun getDefaultValue(): License? {
            return License.Companion.NO_LICENSE
        }

        override fun visitLabels(
            visitor: com.google.devtools.build.lib.packages.Type.LabelVisitor?,
            value: License?,
            context: com.google.devtools.build.lib.packages.Attribute?
        ) {
        }

        override fun toString(): String {
            return "license"
        }
    }

    private class OutputType : com.google.devtools.build.lib.packages.Type<Label?>() {
        override fun cast(value: Any?): Label? {
            return value as Label?
        }

        override fun getDefaultValue(): Label? {
            return null
        }

        override fun visitLabels(
            visitor: com.google.devtools.build.lib.packages.Type.LabelVisitor,
            value: Label?,
            context: com.google.devtools.build.lib.packages.Attribute?
        ) {
            visitor.visit(value, context)
        }

        override fun getLabelClass(): LabelClass {
            return LabelClass.OUTPUT
        }

        override fun toString(): String {
            return "output"
        }

        @Throws(ConversionException::class)
        override fun convert(x: Any?, what: Any?, labelConverter: LabelConverter): Label {
            val result: Label = LABEL.convert(x, what, labelConverter)
            if (!result.getPackageIdentifier().equals(labelConverter.getBasePackage())) {
                throw ConversionException("label '" + x + "' is not in the current package")
            }
            return result
        }
    }

    /**
     * Holds an ordered collection of [Selector]s. This is used to support `attr = rawValue + select(...) + select(...) + ..."` syntax. For consistency's sake, raw values are
     * stored as selects with only a default condition.
     */
    // TODO(adonovan): merge with packages.Selector{List,Value}.
    // We don't need three classes for the same concept.
    class SelectorList<T> : net.starlark.java.eval.StarlarkValue {
        private val originalType: com.google.devtools.build.lib.packages.Type<T?>?
        @kotlin.jvm.JvmField
        private val elements: MutableList<Selector<T?>>

        @com.google.common.annotations.VisibleForTesting
        internal constructor(
            x: MutableList<Any?>,
            what: Any?,
            context: LabelConverter?,
            originalType: com.google.devtools.build.lib.packages.Type<T?>
        ) {
            if (x.size() > 1 && originalType.concat(com.google.common.collect.ImmutableList.of<T?>()) == null) {
                throw ConversionException(
                    java.lang.String.format("type '%s' doesn't support select concatenation", originalType)
                )
            }

            val builder: com.google.common.collect.ImmutableList.Builder<Selector<T?>?> =
                com.google.common.collect.ImmutableList.builder<Selector<T?>?>()
            for (elem in x) {
                if (elem is SelectorValue) {
                    builder.add(
                        Selector<T?>(
                            (elem as SelectorValue).getDictionary(), what,
                            context, originalType, (elem as SelectorValue).getNoMatchError()
                        )
                    )
                } else {
                    val directValue: T? = originalType.convert(elem, what, context)
                    builder.add(
                        Selector<T?>(
                            com.google.common.collect.ImmutableMap.of<String?, T?>(
                                com.google.devtools.build.lib.packages.BuildType.Selector.Companion.DEFAULT_CONDITION_KEY,
                                directValue
                            ),
                            what, context, originalType
                        )
                    )
                }
            }
            this.originalType = originalType
            this.elements = builder.build()
        }

        internal constructor(
            elements: MutableList<Selector<T?>?>,
            originalType: com.google.devtools.build.lib.packages.Type<T?>?
        ) {
            this.elements = com.google.common.collect.ImmutableList.copyOf<Selector<T?>?>(elements)
            this.originalType = originalType
        }

        /**
         * Returns a syntactically order-preserved list of all values and selectors for this attribute.
         */
        fun getSelectors(): MutableList<Selector<T?>> {
            return elements
        }

        /**
         * Returns the native Type for this attribute (i.e. what this would be if it wasn't a selector
         * list).
         */
        fun getOriginalType(): com.google.devtools.build.lib.packages.Type<T?>? {
            return originalType
        }

        /** Returns the labels of all configurability keys across all selects in this expression.  */
        fun getKeyLabels(): MutableSet<Label?> {
            val keys: com.google.common.collect.ImmutableSet.Builder<Label?> =
                com.google.common.collect.ImmutableSet.builder<Label?>()
            for (selector in elements) {
                selector.forEach(
                    SelectorEntryConsumer { label: Label?, value: T? ->
                        if (!com.google.devtools.build.lib.packages.BuildType.Selector.Companion.isDefaultConditionLabel(
                                label
                            )
                        ) {
                            keys.add(label)
                        }
                    })
            }
            return keys.build()
        }

        override fun toString(): String {
            return net.starlark.java.eval.Starlark.repr(this, net.starlark.java.eval.StarlarkSemantics.DEFAULT)
        }

        override fun repr(
            printer: net.starlark.java.eval.Printer,
            semantics: net.starlark.java.eval.StarlarkSemantics?
        ) {
            // Convert to a lib.packages.SelectorList to guarantee consistency with callers that serialize
            // directly on that type.
            printer.repr(com.google.devtools.build.lib.packages.Attribute.Companion.valueToStarlark(this), semantics)
        }
    }

    /** Lazy string message to pass as the `what` when converting a select branch value.  */
    private class SelectBranchMessage(private val what: Any?, key: Label?) {
        private val key: Label?

        init {
            this.key = key
        }

        override fun toString(): String {
            return java.lang.String.format("each branch in select expression of %s (including '%s')", what, key)
        }
    }

    /**
     * Represents the entries in a single select expression (in the order they were initially
     * specified). Contains the configurability pattern (label) and value (objects of the attribute's
     * native type) of each entry.
     */
    class Selector<T> {
        private val originalType: com.google.devtools.build.lib.packages.Type<T?>?

        private val labels: Array<Label?>

        // Can contain nulls, when an entry maps to None and the Type<T> has a null getDefaultValue().
        private val values: Array<T?>

        private val conditionsWithDefaultValues: MutableSet<Label?>
        private val noMatchError: String?
        private val defaultConditionPos: Int

        /** Creates a new Selector with a custom error message for when no conditions match.  */
        /** Creates a new Selector using the default error message when no conditions match.  */
        @kotlin.jvm.JvmOverloads
        internal constructor(
            x: com.google.common.collect.ImmutableMap<*, *>,
            what: Any?,
            context: LabelConverter?,
            originalType: com.google.devtools.build.lib.packages.Type<T?>,
            noMatchError: String? = ""
        ) {
            this.originalType = originalType
            val labels: Array<Label?> = arrayOfNulls<Label>(x.size())
            val values = arrayOfNulls<Any>(x.size()) as Array<T?>
            val defaultValuesBuilder: com.google.common.collect.ImmutableSet.Builder<Label?> =
                com.google.common.collect.ImmutableSet.builder<Label?>()
            var pos = 0
            var defaultConditionPos = -1
            for (entry in x.entrySet()) {
                val key: Label = LABEL.convert(entry.getKey(), what, context)
                labels[pos] = key
                val value: T?
                if (entry.getValue() === net.starlark.java.eval.Starlark.NONE) {
                    // { "//condition": None } is the same as not setting the value.
                    value = originalType.getDefaultValue()
                    defaultValuesBuilder.add(key)
                } else {
                    val selectBranch: Any? = if (what == null) null else SelectBranchMessage(what, key)
                    value = originalType.convert(entry.getValue(), selectBranch, context)
                }
                if (key.equals(com.google.devtools.build.lib.packages.BuildType.Selector.Companion.DEFAULT_CONDITION_LABEL)) {
                    defaultConditionPos = pos
                }
                values[pos] = value
                pos++
            }
            this.labels = labels
            this.values = values
            this.noMatchError = noMatchError
            this.conditionsWithDefaultValues = defaultValuesBuilder.build()
            this.defaultConditionPos = defaultConditionPos
        }

        /**
         * Create a new Selector from raw values. Defensive copies of the supplied arrays are *not*
         * made, so it is imperative that they are not modified following construction.
         */
        internal constructor(
            labels: Array<Label?>,
            values: Array<T?>,
            originalType: com.google.devtools.build.lib.packages.Type<T?>?,
            noMatchError: String?,
            conditionsWithDefaultValues: com.google.common.collect.ImmutableSet<Label?>,
            defaultConditionPos: Int
        ) {
            this.labels = labels
            this.values = values
            this.originalType = originalType
            this.noMatchError = noMatchError
            this.conditionsWithDefaultValues = conditionsWithDefaultValues
            this.defaultConditionPos = defaultConditionPos
        }

        fun hasDefault(): Boolean {
            return defaultConditionPos >= 0
        }

        /** Returns the value to use when none of the attribute's selection keys match.  */
        fun getDefault(): T? {
            return if (defaultConditionPos < 0) null else values[defaultConditionPos]
        }

        /**
         * Returns a new [ArrayList] containing all the values in the entries of this [ ], in the same order they were initially specified.
         * 
         * 
         * Prefer using [.forEach] since that makes no allocations.
         */
        fun valuesCopy(): java.util.ArrayList<T?> {
            // N.B. We can't use ImmutableList since we can have null values.
            val result: java.util.ArrayList<T?> =
                com.google.common.collect.Lists.newArrayListWithCapacity<T?>(getNumEntries())
            forEach(SelectorEntryConsumer { label: Label?, value: T? -> result.add(value) })
            return result
        }

        /**
         * Returns a new [LinkedHashMap] representing the branches of this [Selector], in
         * the same order they were initially specified.
         * 
         * 
         * Prefer using [.forEach] since that makes no allocations.
         */
        fun mapCopy(): LinkedHashMap<Label?, T?> {
            // N.B. We can't use ImmutableMap since we can have null values. But we also want to respect
            // the ordering of our original map, so we use LinkedHashMap instead of HashMap.
            val result: LinkedHashMap<Label?, T?> =
                com.google.common.collect.Maps.newLinkedHashMapWithExpectedSize<Label?, T?>(getNumEntries())
            forEach(SelectorEntryConsumer { key: Label?, value: T? -> result.put(key, value) })
            return result
        }

        /** Consumer for [.forEach].  */
        interface SelectorEntryConsumer<T> {
            fun accept(conditionLabel: Label?, value: T?)
        }

        /**
         * Passes each entry to the provided `consumer`, in the same order they were initially
         * specified.
         */
        fun forEach(consumer: SelectorEntryConsumer<T?>) {
            for (i in labels.indices) {
                consumer.accept(labels[i], values[i])
            }
        }

        /** Consumer for [.forEachExceptionally].  */
        internal interface ExceptionalSelectorEntryConsumer<T, E1 : java.lang.Exception?, E2 : java.lang.Exception?> {
            @Throws(E1::class, E2::class)
            fun accept(conditionLabel: Label?, value: T?)
        }

        /**
         * Passes each entry to the provided `consumer`, in the same order they were initially
         * specified.
         */
        @Throws(E1::class, E2::class)
        fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?> forEachExceptionally(
            consumer: ExceptionalSelectorEntryConsumer<T?, E1?, E2?>
        ) {
            for (i in labels.indices) {
                consumer.accept(labels[i], values[i])
            }
        }

        /** Returns the number of entries.  */
        fun getNumEntries(): Int {
            return labels.size
        }

        /**
         * Returns the native Type for this attribute (i.e. what this would be if it wasn't a selector
         * expression).
         */
        fun getOriginalType(): com.google.devtools.build.lib.packages.Type<T?>? {
            return originalType
        }

        /**
         * Returns true if this selector has the structure: {"//conditions:default": ...}. That means
         * all values are always chosen.
         */
        fun isUnconditional(): Boolean {
            return labels.size == 1 && defaultConditionPos >= 0
        }

        /**
         * Returns true if an explicit value is set for the given condition, vs. { "//condition": None }
         * which means revert to the default.
         */
        fun isValueSet(condition: Label?): Boolean {
            return !conditionsWithDefaultValues.contains(condition)
        }

        /**
         * Returns a custom error message for this select when no condition matches, or an empty string
         * if no such message is declared.
         */
        fun getNoMatchError(): String? {
            return noMatchError
        }

        companion object {
            /** Value to use when none of an attribute's selection criteria match.  */
            @com.google.common.annotations.VisibleForTesting
            const val DEFAULT_CONDITION_KEY: String = "//conditions:default"

            val DEFAULT_CONDITION_LABEL: Label =
                Label.parseCanonicalUnchecked(com.google.devtools.build.lib.packages.BuildType.Selector.Companion.DEFAULT_CONDITION_KEY)

            /**
             * Returns true for the default condition label, which is not intended to map to an actual
             * target.
             */
            fun isDefaultConditionLabel(label: Label?): Boolean {
                return com.google.devtools.build.lib.packages.BuildType.Selector.Companion.DEFAULT_CONDITION_LABEL.equals(
                    label
                )
            }
        }
    }

    /**
     * A TriState value is like a boolean attribute whose default value may be distinguished from
     * either of the possible explicitly assigned values. TriState attributes may be assigned the
     * values 0 (NO), 1 (YES), or None (AUTO). TriState is deprecated; use attr.int(values=[-1, 0, 1])
     * instead.
     */
    private class TriStateType :
        com.google.devtools.build.lib.packages.Type<com.google.devtools.build.lib.packages.TriState?>() {
        override fun cast(value: Any?): com.google.devtools.build.lib.packages.TriState? {
            return value as com.google.devtools.build.lib.packages.TriState?
        }

        override fun getDefaultValue(): com.google.devtools.build.lib.packages.TriState? {
            return com.google.devtools.build.lib.packages.TriState.AUTO
        }

        override fun visitLabels(
            visitor: com.google.devtools.build.lib.packages.Type.LabelVisitor?,
            value: com.google.devtools.build.lib.packages.TriState?,
            context: com.google.devtools.build.lib.packages.Attribute?
        ) {
        }

        override fun toString(): String {
            return "tristate"
        }

        @Throws(ConversionException::class)
        override fun convert(
            x: Any,
            what: Any?,
            labelConverter: LabelConverter?
        ): com.google.devtools.build.lib.packages.TriState {
            if (x is com.google.devtools.build.lib.packages.TriState) {
                return x as com.google.devtools.build.lib.packages.TriState
            }
            if (x is Boolean) {
                // TODO(adonovan): re-enable this under flag control; see b/116691720.
                // throw new ConversionException(this, x,
                //   "rule attribute (tristate is being replaced by "
                //       + "attr.int(values=[-1, 0, 1]), and it no longer accepts Boolean values; "
                //       + "instead, use 0 or 1, or None for the default)");
                return if (x) com.google.devtools.build.lib.packages.TriState.YES else com.google.devtools.build.lib.packages.TriState.NO
            }
            val xAsInteger: Int =
                com.google.devtools.build.lib.packages.Type.Companion.INTEGER.convert(x, what, labelConverter)
                    .toIntUnchecked()
            if (xAsInteger == -1) {
                return com.google.devtools.build.lib.packages.TriState.AUTO
            } else if (xAsInteger == 1) {
                return com.google.devtools.build.lib.packages.TriState.YES
            } else if (xAsInteger == 0) {
                return com.google.devtools.build.lib.packages.TriState.NO
            }
            throw ConversionException(this, x, "TriState values is not one of [-1, 0, 1]")
        }
    }
}
