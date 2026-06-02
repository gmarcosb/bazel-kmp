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
 * Root of Type symbol hierarchy for values in the build language.
 * 
 * 
 * Type symbols are primarily used for their `convert` method, which is a kind of cast
 * operator enabling conversion from untyped (Object) references to values in the build language, to
 * typed references.
 * 
 * 
 * For example, this code type-converts a value `x` returned by the evaluator, to a
 * list of strings:
 * 
 * <pre>
 * Object x = expr.eval(env);
 * List&lt;String&gt; s = Type.STRING_LIST.convert(x);
</pre> * 
 * 
 * 
 * **BEFORE YOU ADD A NEW TYPE:**
 * 
 * 
 * We frequently get requests to create a new kind of attribute type whenever a use case doesn't
 * seem to fit into one of the existing types. This is almost always a bad idea. The most complex
 * type we currently have is probably STRING_LIST_DICT or maybe LABEL_KEYED_STRING_DICT. But no
 * matter what you support, someone will always want to add another layer of structure. It's even
 * been suggested to allow JSON or arbitrary Starlark values in attributes.
 * 
 * 
 * Adding a new type has implications for many different systems. The whole of the loading phase
 * needs to know about the type -- how to serialize it, how to format it for `bazel query`, how to
 * traverse label dependencies embedded within it. Then you need to think about how to represent
 * attribute values of that type in Starlark within a rule implementation function, and come up with
 * a good name for that type in the Starlark `attr` module. All of the tooling for formatting,
 * linting, and analyzing BUILD files may need to be updated.
 * 
 * 
 * It's usually possible to accomplish the end goal without making the target attribute grammar
 * more expressive. If it's not, that may be a sign that attributes are not the right mechanism to
 * use, and perhaps instead you should use opaque string identifiers, or labels to sub-targets with
 * more structure (think toolchains, platforms, config_setting).
 * 
 * 
 * Any new attribute type should be general-purpose and meet a high bar of usefulness (unlikely
 * since we seem to be doing fine so far without it), and not overly complicate BUILD files or rule
 * implementation functions.
 */
// TODO(adonovan): update documentation here and elsewhere to use the term
// "rule attribute values" or "valid attribute types" where appropriate,
// and not "value in the build language", which is a much broader set of
// possible Starlark values. Also link to the canonical set of valid attribute
// types, both Starlark and native.
abstract class Type<T> internal constructor() {
    /**
     * Converts a legal Starlark value x into an Java value of type T.
     * 
     * 
     * x must be directly convertible to this type. This therefore disqualifies "selector
     * expressions" of the form "{ config1: 'value1_of_orig_type', config2: 'value2_of_orig_type; }"
     * (which support configurable attributes). To handle those expressions, see [ ][com.google.devtools.build.lib.packages.BuildType.convertFromBuildLangType].
     * 
     * @param x The Starlark value to convert.
     * @param what An object whose toString method returns a description of the purpose of x.
     * Typically, it is the name of a function parameter or struct field. The method is called
     * only in case of error.
     * @param labelConverter the converter to use to convert label literals to Label objects; must be
     * non-null if parsing non-canonical label strings is required
     * @throws ConversionException if there was a problem performing the type conversion
     * @throws NullPointerException if x is null.
     */
    @Throws(ConversionException::class)
    abstract fun convert(x: Any?, what: Any?, labelConverter: LabelConverter?): T?

    /**
     * Copies a Starlark value to an immutable ones and converts label strings to Label objects.
     * 
     * 
     * All Starlark values are also type checked.
     * 
     * @param x The Starlark value to copy.
     * @param what An object whose toString method returns a description of the purpose of x.
     * Typically, it is the name of a function parameter or struct field. The method is called
     * only in case of error.
     * @param labelConverter the converter to use to convert label literals to Label objects; must be
     * non-null if parsing non-canonical label strings is required
     * @throws ConversionException if the Starlark value doesn't match the type
     */
    @Throws(ConversionException::class)
    open fun copyAndLiftStarlarkValue(
        x: Any?, what: Any?, labelConverter: LabelConverter?
    ): Any? {
        // Nones are valid as the Starlark representation of the internal null value (used for certain
        // types' default values).
        if (x === net.starlark.java.eval.Starlark.NONE) {
            return x
        }
        return convert(x, what, labelConverter)
    }

    // TODO(bazel-team): Check external calls (e.g. in PackageFactory), verify they always want
    // this over selectableConvert.
    /**
     * Equivalent to [.convert] where the label is `null`.
     * Useful for converting values to types that do not involve the type `LABEL`.
     */
    @Throws(ConversionException::class)
    fun convert(x: Any?, what: Any?): T? {
        return convert(x, what, null)
    }

    /**
     * Like [.convert], but converts Starlark `None` to
     * given `defaultValue`.
     */
    /**
     * Like [.convert], but converts Starlark `None` to
     * java `null`.
     */
    /**
     * Like [.convert], but converts Starlark `NONE` to java `null`.
     */
    @kotlin.jvm.JvmOverloads
    @Throws(ConversionException::class)
    fun convertOptional(
        x: Any?, what: String?, labelConverter: LabelConverter? = null, defaultValue: T? = null
    ): T? {
        if (net.starlark.java.eval.Starlark.isNullOrNone(x)) {
            return defaultValue
        }
        return convert(x, what, labelConverter)
    }

    abstract fun cast(value: Any?): T?

    abstract override fun toString(): String

    /**
     * Returns the default value for this type; may return null iff no default is defined for this
     * type.
     */
    abstract fun getDefaultValue(): T?

    /**
     * Function accepting a (potentially null) [Label] and a (potentially null) [ ] provided as context. Used by [.visitLabels].
     */
    interface LabelVisitor {
        fun visit(label: Label?, context: com.google.devtools.build.lib.packages.Attribute?)
    }

    /**
     * Invokes `visitor.visit(label, context)` for each [Label] `label` associated
     * with `value`, an instance of this [Type].
     * 
     * 
     * This is used to support reliable label visitation in [ ][com.google.devtools.build.lib.packages.AttributeMap.visitAllLabels]. To preserve that
     * reliability, every type should faithfully define its own instance of this method. In other
     * words, be careful about defining default instances in base types that get auto-inherited by
     * their children. Keep all definitions as explicit as possible.
     */
    abstract fun visitLabels(
        visitor: LabelVisitor?,
        value: T?,
        context: com.google.devtools.build.lib.packages.Attribute?
    )

    /** Classifications of labels by their usage.  */
    enum class LabelClass {
        /** Used for types which are not labels.  */
        NONE,

        /** Used for types which use labels to declare a dependency.  */
        DEPENDENCY,

        /**
         * Used for types which use labels to reference another target but do not declare a dependency,
         * in cases where doing so would cause a dependency cycle.
         */
        NONDEP_REFERENCE,

        /**
         * Used for types which declare a dependency, but the dependency should not be configured. Used
         * when the label is used only in the loading phase. e.g. genquery.scope
         */
        GENQUERY_SCOPE_REFERENCE,

        /** Used for types which use labels to declare an output path.  */
        OUTPUT,
    }

    /** Returns the class of labels contained by this type, if any.  */
    open fun getLabelClass(): LabelClass? {
        return LabelClass.NONE
    }

    /**
     * Implementation of concatenation for this type, as if by `elements[0] + ... + elements[n-1]`) for scalars or lists, or `elements[0] | ... | elements[n-1]` for dicts.
     * Returns null to indicate concatenation isn't supported.
     * 
     * 
     * This method exists to support deferred additions `select + T` for catenable types T
     * such as string, int, list, and deferred unions `select | T` for map types T.
     */
    open fun concat(elements: Iterable<T?>?): T? {
        return null
    }

    /**
     * Converts an initialized Type object into a tag set representation. This operation is only valid
     * for certain sub-Types which are guaranteed to be properly initialized.
     * 
     * @param value the actual value
     * @throws UnsupportedOperationException if the concrete type does not support tag conversion or
     * if a convertible type has no initialized value.
     */
    open fun toTagSet(value: Any?, name: String): MutableSet<String?>? {
        val msg = "Attribute " + name + " does not support tag conversion."
        throw java.lang.UnsupportedOperationException(msg)
    }

    /**
     * For ListType objects, returns the type of the elements of the list; for all other types,
     * returns null. (This non-obvious implementation strategy is necessitated by the wildcard capture
     * rules of the Java type system, which disallow conversion from Type{List{ELEM}} to
     * Type{List{?}}.)
     */
    open fun getListElementType(): Type<*>? {
        return null
    }

    /**
     * ConversionException is thrown when a type conversion fails; it contains an explanatory error
     * message.
     */
    class ConversionException : net.starlark.java.eval.EvalException {
        /** Constructs a conversion error. Throws NullPointerException if value is null.  */
        internal constructor(expected: String?, value: Any?, what: Any?) : super(
            message(
                expected,
                com.google.common.base.Preconditions.checkNotNull<Any?>(value),
                what
            )
        )

        /** Constructs a conversion error. Throws NullPointerException if value is null.  */
        internal constructor(type: Type<*>, value: Any?, what: Any?) : super(
            message(
                java.lang.String.format("value of type '%s'", type.toString()),
                com.google.common.base.Preconditions.checkNotNull<Any?>(value),
                what
            )
        )

        constructor(message: String?) : super(message)

        companion object {
            private fun message(expected: String?, value: Any, what: Any?): String {
                val printer: net.starlark.java.eval.Printer = net.starlark.java.eval.Printer()
                printer.append("expected ").append(expected)
                if (what != null) {
                    printer.append(" for ").append(what.toString())
                }
                printer.append(", but got ")
                printer.repr(value, net.starlark.java.eval.StarlarkSemantics.DEFAULT)
                printer.append(" (").append(net.starlark.java.eval.Starlark.type(value)).append(")")
                return printer.toString()
            }
        }
    }

    /********************************************************************
     * *
     * Subclasses                            *
     * *
     */
    // A Starlark integer in the signed 32-bit range (like Java int).
    private class IntegerType : Type<net.starlark.java.eval.StarlarkInt?>() {
        override fun cast(value: Any?): net.starlark.java.eval.StarlarkInt? {
            // This cast will fail if passed a java.lang.Integer,
            // as it is not a legal Starlark value. Use StarlarkInt.
            return value as net.starlark.java.eval.StarlarkInt?
        }

        override fun getDefaultValue(): net.starlark.java.eval.StarlarkInt {
            return net.starlark.java.eval.StarlarkInt.of(0)
        }

        override fun visitLabels(
            visitor: LabelVisitor?,
            value: net.starlark.java.eval.StarlarkInt?,
            context: com.google.devtools.build.lib.packages.Attribute?
        ) {
        }

        override fun toString(): String {
            return "int"
        }

        @Throws(ConversionException::class)
        override fun convert(x: Any?, what: Any?, labelConverter: LabelConverter?): net.starlark.java.eval.StarlarkInt {
            if (x is net.starlark.java.eval.StarlarkInt) {
                try {
                    x.toIntUnchecked() // assert signed 32-bit
                } catch (ex: java.lang.IllegalArgumentException) {
                    val prefix = if (what != null) ("for " + what + ", ") else ""
                    throw ConversionException(
                        java.lang.String.format("%sgot %s, want value in signed 32-bit range", prefix, x)
                    )
                }
                return x
            }
            require(!x is Int) { "Integer is not a legal Starlark value" }
            throw ConversionException(this, x, what)
        }

        override fun concat(elements: Iterable<net.starlark.java.eval.StarlarkInt?>): net.starlark.java.eval.StarlarkInt {
            var sum: net.starlark.java.eval.StarlarkInt = net.starlark.java.eval.StarlarkInt.of(0)
            for (elem in elements) {
                sum = net.starlark.java.eval.StarlarkInt.add(sum, elem)
            }
            // Perform narrowing conversion to ensure that the result
            // remains in the signed 32-bit range. This means that
            // s=select(0x7fffffff); s+s may yield a negative result.
            return net.starlark.java.eval.StarlarkInt.of(sum.truncateToInt())
        }
    }

    private class BooleanType : Type<Boolean?>() {
        override fun cast(value: Any?): Boolean? {
            return value as Boolean?
        }

        override fun getDefaultValue(): Boolean {
            return false
        }

        override fun visitLabels(
            visitor: LabelVisitor?,
            value: Boolean?,
            context: com.google.devtools.build.lib.packages.Attribute?
        ) {
        }

        override fun toString(): String {
            return "boolean"
        }

        // Conversion to boolean must also tolerate integers of 0 and 1 only.
        @Throws(ConversionException::class)
        override fun convert(x: Any, what: Any?, labelConverter: LabelConverter?): Boolean {
            if (x is Boolean) {
                return x
            }
            try {
                val xAsInteger: Int =
                    com.google.devtools.build.lib.packages.Type.Companion.INTEGER.convert(x, what, labelConverter)
                        .toIntUnchecked()
                if (xAsInteger == 0) {
                    return false
                } else if (xAsInteger == 1) {
                    return true
                }
            } catch (unused: ConversionException) {
                // Fall through to the `throw` below to display the correct type name.
                // No need to keep the previous exception, the stack trace is less important than the actual
                // error message showing allowed values for conversion.
            }
            throw ConversionException("one of [False, True, 0, 1]", x, what)
        }

        /** Booleans attributes are converted to tags based on their names.  */
        override fun toTagSet(value: Any?, name: String): MutableSet<String?> {
            if (value == null) {
                val msg = "Illegal tag conversion from null on Attribute " + name + "."
                throw java.lang.IllegalStateException(msg)
            }
            val tag = if (value as Boolean) name else "no" + name
            return com.google.common.collect.ImmutableSet.of<String?>(tag)
        }
    }

    private class StringType(internString: Boolean) : Type<String?>() {
        private val internString: Boolean

        init {
            this.internString = internString
        }

        override fun cast(value: Any?): String? {
            return value as String?
        }

        override fun getDefaultValue(): String {
            return ""
        }

        override fun visitLabels(
            visitor: LabelVisitor?,
            value: String?,
            context: com.google.devtools.build.lib.packages.Attribute?
        ) {
        }

        override fun toString(): String {
            return "string"
        }

        @Throws(ConversionException::class)
        override fun convert(x: Any?, what: Any?, labelConverter: LabelConverter?): String? {
            if (x !is String) {
                throw ConversionException(this, x, what)
            }
            return if (internString) x.intern() else x
        }

        override fun concat(elements: Iterable<String?>): String {
            return com.google.common.base.Joiner.on("").join(elements)
        }

        /** A String is representable as a set containing its value.  */
        override fun toTagSet(value: Any?, name: String): MutableSet<String?> {
            if (value == null) {
                val msg = "Illegal tag conversion from null on Attribute " + name + "."
                throw java.lang.IllegalStateException(msg)
            }
            return com.google.common.collect.ImmutableSet.of<String?>(value as String)
        }
    }

    /** A type to support dictionary attributes.  */
    open class DictType<KeyT, ValueT> internal constructor(
        keyType: Type<KeyT?>,
        valueType: Type<ValueT?>,
        labelClass: LabelClass?
    ) : Type<MutableMap<KeyT?, ValueT?>?>() {
        @kotlin.jvm.JvmField
        private val keyType: Type<KeyT?>
        @kotlin.jvm.JvmField
        private val valueType: Type<ValueT?>

        private val empty: MutableMap<KeyT?, ValueT?> = com.google.common.collect.ImmutableMap.of<KeyT?, ValueT?>()

        private val labelClass: LabelClass?

        override fun visitLabels(
            visitor: LabelVisitor?,
            value: MutableMap<KeyT?, ValueT?>,
            context: com.google.devtools.build.lib.packages.Attribute?
        ) {
            if (labelClass != LabelClass.NONE) {
                for (entry in value.entrySet()) {
                    keyType.visitLabels(visitor, entry.getKey(), context)
                    valueType.visitLabels(visitor, entry.getValue(), context)
                }
            }
        }

        init {
            this.keyType = keyType
            this.valueType = valueType
            this.labelClass = labelClass
        }

        fun getKeyType(): Type<KeyT?> {
            return keyType
        }

        fun getValueType(): Type<ValueT?> {
            return valueType
        }

        override fun getLabelClass(): LabelClass? {
            return labelClass
        }

        override fun cast(value: Any?): MutableMap<KeyT?, ValueT?>? {
            return value as MutableMap<KeyT?, ValueT?>?
        }

        override fun toString(): String {
            return "dict(" + keyType + ", " + valueType + ")"
        }

        @Throws(ConversionException::class)
        override fun convert(x: Any?, what: Any?, labelConverter: LabelConverter?): MutableMap<KeyT?, ValueT?> {
            if (x !is MutableMap<*, *>) {
                throw ConversionException(this, x, what)
            }
            val o = x
            // It's possible that #convert() calls transform non-equal keys into equal ones so we can't
            // just use ImmutableMap.Builder() here (that throws on collisions).
            val result: LinkedHashMap<KeyT?, ValueT?> = LinkedHashMap<KeyT?, ValueT?>()
            for (elem in o.entrySet()) {
                result.put(
                    keyType.convert(elem.getKey(), "dict key element", labelConverter),
                    valueType.convert(elem.getValue(), "dict value element", labelConverter)
                )
            }
            return com.google.common.collect.ImmutableMap.copyOf<KeyT?, ValueT?>(result)
        }

        @Throws(ConversionException::class)
        override fun copyAndLiftStarlarkValue(
            x: Any?, what: Any?, labelConverter: LabelConverter?
        ): Any? {
            if (x !is MutableMap<*, *>) {
                throw ConversionException(this, x, what)
            }
            val o = x
            // It's possible that #convert() calls transform non-equal keys into equal ones so we can't
            // just use ImmutableMap.Builder() here (that throws on collisions).
            val result: LinkedHashMap<Any?, Any?> = LinkedHashMap<Any?, Any?>()
            for (elem in o.entrySet()) {
                result.put(
                    keyType.copyAndLiftStarlarkValue(elem.getKey(), "dict key element", labelConverter),
                    valueType.copyAndLiftStarlarkValue(
                        elem.getValue(), "dict value element", labelConverter
                    )
                )
            }
            return net.starlark.java.eval.Dict.immutableCopyOf<Any?, Any?>(result)
        }


        override fun concat(iterable: Iterable<MutableMap<KeyT?, ValueT?>>): MutableMap<KeyT?, ValueT?> {
            val builder: com.google.common.collect.ImmutableMap.Builder<KeyT?, ValueT?> =
                com.google.common.collect.ImmutableMap.builder<KeyT?, ValueT?>()
            for (map in iterable) {
                builder.putAll(map)
            }
            return builder.buildKeepingLast()
        }

        override fun getDefaultValue(): MutableMap<KeyT?, ValueT?> {
            return empty
        }

        companion object {
            fun <KEY, VALUE> create(
                keyType: Type<KEY?>, valueType: Type<VALUE?>
            ): DictType<KEY?, VALUE?> {
                val keyLabelClass = keyType.getLabelClass()
                val valueLabelClass = valueType.getLabelClass()
                com.google.common.base.Preconditions.checkArgument(
                    keyLabelClass == LabelClass.NONE || valueLabelClass == LabelClass.NONE || keyLabelClass == valueLabelClass,
                    ("A DictType's keys and values must be the same class of label if both contain labels, "
                            + "but the key type %s contains %s labels, while "
                            + "the value type %s contains %s labels."),
                    keyType,
                    keyLabelClass,
                    valueType,
                    valueLabelClass
                )
                val labelClass = if (keyLabelClass != LabelClass.NONE) keyLabelClass else valueLabelClass

                return com.google.devtools.build.lib.packages.Type.DictType<KEY?, VALUE?>(
                    keyType,
                    valueType,
                    labelClass
                )
            }
        }
    }

    /** A parent class for collection types (ListType, SetType).  */
    private abstract class CollectionType<T : Iterable<ElemT?>?, ElemT>(elemType: Type<ElemT?>, empty: T?) :
        Type<T?>() {
        val elemType: Type<ElemT?>

        private val empty: T?

        init {
            this.elemType = elemType
            this.empty = empty
        }

        override fun cast(value: Any?): T? {
            return value as T?
        }

        override fun getListElementType(): Type<ElemT?> {
            return elemType
        }

        override fun getLabelClass(): LabelClass? {
            return elemType.getLabelClass()
        }

        override fun getDefaultValue(): T? {
            return empty
        }

        /**
         * A collection is representable as a tag set as the contents of itself expressed as Strings. So
         * a `Iterable<String>` is effectively converted to a `Set<String>`.
         */
        override fun toTagSet(items: Any?, name: String): MutableSet<String?> {
            if (items == null) {
                val msg = "Illegal tag conversion from null on Attribute" + name + "."
                throw java.lang.IllegalStateException(msg)
            }
            val tags: MutableSet<String?> = LinkedHashSet<String?>()
            val itemsAsListofElem = items as Iterable<ElemT?>
            for (element in itemsAsListofElem) {
                tags.add(element.toString())
            }
            return tags
        }

        /**
         * Provides a [.toString] description of the context of the value in a collection being
         * converted. This is preferred over a raw string to avoid uselessly constructing strings which
         * are never used. This class is mutable (the index is updated).
         */
        internal class ConversionContext(what: Any?) {
            private val what: Any?
            private var index = 0

            init {
                this.what = what
            }

            fun update(index: Int) {
                this.index = index
            }

            override fun toString(): String {
                return "element " + index + " of " + what
            }
        }
    }

    /** A type for lists of a given element type  */
    class ListType<ElemT> private constructor(elemType: Type<ElemT?>) :
        CollectionType<MutableList<ElemT?>?, ElemT?>(elemType, com.google.common.collect.ImmutableList.of<ElemT?>()) {
        override fun visitLabels(
            visitor: LabelVisitor?,
            value: MutableList<ElemT?>,
            context: com.google.devtools.build.lib.packages.Attribute?
        ) {
            if (elemType.getLabelClass() == LabelClass.NONE) {
                return
            }

            // Hot code path. Optimize for lists with O(1) access to avoid iterator garbage.
            if (value is RandomAccess) {
                for (i in value.indices) {
                    elemType.visitLabels(visitor, value.get(i), context)
                }
            } else {
                for (elem in value) {
                    elemType.visitLabels(visitor, elem, context)
                }
            }
        }

        override fun toString(): String {
            return "list(" + elemType + ")"
        }

        @Throws(ConversionException::class)
        override fun convert(x: Any, what: Any?, labelConverter: LabelConverter?): MutableList<ElemT?> {
            val iterable: Iterable<*>?

            if (x is Iterable<*>) {
                iterable = x
            } else if (x is Depset) {
                iterable = (x as Depset).toList()
            } else {
                throw ConversionException(this, x, what)
            }

            var index = 0
            val result: MutableList<ElemT?> =
                java.util.ArrayList<ElemT?>(com.google.common.collect.Iterables.size(iterable))
            val conversionContext = ConversionContext(what)
            for (elem in iterable!!) {
                conversionContext.update(index)
                val converted = elemType.convert(elem, conversionContext, labelConverter)
                if (converted != null) {
                    result.add(converted)
                } else {
                    // shouldn't happen but it does, rarely
                    val message =
                        ("Converting a list with a null element: "
                                + "element "
                                + index
                                + " of "
                                + what
                                + " in "
                                + labelConverter)
                    LoggingUtil.logToRemote(java.util.logging.Level.WARNING, message, ConversionException(message))
                }
                ++index
            }
            return result
        }

        @Throws(ConversionException::class)
        override fun copyAndLiftStarlarkValue(
            x: Any, what: Any?, labelConverter: LabelConverter?
        ): Any? {
            return net.starlark.java.eval.StarlarkList.immutableCopyOf<ElemT?>(convert(x, what, labelConverter))
        }

        override fun concat(elements: Iterable<MutableList<ElemT?>>): MutableList<ElemT?> {
            val builder: com.google.common.collect.ImmutableList.Builder<ElemT?> =
                com.google.common.collect.ImmutableList.builder<ElemT?>()
            for (list in elements) {
                builder.addAll(list)
            }
            return builder.build()
        }

        companion object {
            fun <E> create(elemType: Type<E?>): ListType<E?> {
                return com.google.devtools.build.lib.packages.Type.ListType<E?>(elemType)
            }
        }
    }

    /** A type for sets of a given element type  */
    class SetType<ElemT> private constructor(elemType: Type<ElemT?>) :
        CollectionType<MutableSet<ElemT?>?, ElemT?>(elemType, com.google.common.collect.ImmutableSet.of<ElemT?>()) {
        override fun visitLabels(
            visitor: LabelVisitor?,
            value: MutableSet<ElemT?>,
            context: com.google.devtools.build.lib.packages.Attribute?
        ) {
            if (elemType.getLabelClass() == LabelClass.NONE) {
                return
            }

            for (elem in value) {
                elemType.visitLabels(visitor, elem, context)
            }
        }

        override fun toString(): String {
            return "set(" + elemType + ")"
        }

        @Throws(ConversionException::class)
        override fun convert(x: Any?, what: Any?, labelConverter: LabelConverter?): MutableSet<ElemT?> {
            if (x !is MutableSet<*>) {
                throw ConversionException(this, x, what)
            }

            val set = x

            var index = 0
            val result: MutableSet<ElemT?> =
                com.google.common.collect.Sets.newLinkedHashSetWithExpectedSize<ElemT?>(set.size())
            val conversionContext = ConversionContext(what)
            for (elem in set) {
                conversionContext.update(index)
                val converted = elemType.convert(elem, conversionContext, labelConverter)
                if (converted != null) {
                    result.add(converted)
                } else {
                    // shouldn't happen but it does, rarely
                    val message =
                        ("Converting a set with a null element: "
                                + "element "
                                + index
                                + " of "
                                + what
                                + " in "
                                + labelConverter)
                    LoggingUtil.logToRemote(java.util.logging.Level.WARNING, message, ConversionException(message))
                }
                ++index
            }
            return result
        }

        @Throws(ConversionException::class)
        override fun copyAndLiftStarlarkValue(
            x: Any?, what: Any?, labelConverter: LabelConverter?
        ): Any? {
            return net.starlark.java.eval.StarlarkSet.immutableCopyOf<ElemT?>(convert(x, what, labelConverter))
        }

        override fun concat(elements: Iterable<MutableSet<ElemT?>>): MutableSet<ElemT?> {
            val builder: com.google.common.collect.ImmutableSet.Builder<ElemT?> =
                com.google.common.collect.ImmutableSet.builder<ElemT?>()
            for (set in elements) {
                builder.addAll(set)
            }
            return builder.build()
        }

        companion object {
            fun <E> create(elemType: Type<E?>): SetType<E?> {
                return com.google.devtools.build.lib.packages.Type.SetType<E?>(elemType)
            }
        }
    }

    companion object {
        /** The type of a Starlark integer in the signed 32-bit range.  */
        @kotlin.jvm.JvmField
        @SerializationConstant
        val INTEGER: Type<net.starlark.java.eval.StarlarkInt?> =
            com.google.devtools.build.lib.packages.Type.IntegerType()

        /** The type of a string which interns the instance with String#intern.  */
        @kotlin.jvm.JvmField
        @SerializationConstant
        val STRING: Type<String?> = com.google.devtools.build.lib.packages.Type.StringType( /* internString= */true)

        /**
         * The type of a string which does not intern the string instance.
         * 
         * 
         * When there is only one string instance created in blaze, interning it introduces memory
         * overhead. So for attribute whose string value tends to not duplicate (for example rule name),
         * it is preferable not to intern such string values.
         */
        @kotlin.jvm.JvmField
        @SerializationConstant
        val STRING_NO_INTERN: Type<String?> =
            com.google.devtools.build.lib.packages.Type.StringType( /* internString= */false)

        /** The type of a boolean.  */
        @kotlin.jvm.JvmField
        @SerializationConstant
        val BOOLEAN: Type<Boolean?> = com.google.devtools.build.lib.packages.Type.BooleanType()
    }
}
