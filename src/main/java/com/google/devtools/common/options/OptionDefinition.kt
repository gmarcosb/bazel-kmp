// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.common.options

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get

/** Everything the [OptionsParser] needs to know about how an option is defined.  */
abstract class OptionDefinition protected constructor(optionAnnotation: com.google.devtools.common.options.Option) :
    Comparable<OptionDefinition?> {
    protected val optionAnnotation: com.google.devtools.common.options.Option

    @kotlin.concurrent.Volatile
    private var converter: com.google.devtools.common.options.Converter<*>? = null

    @kotlin.concurrent.Volatile
    private var defaultValue: Any? = null

    init {
        this.optionAnnotation = optionAnnotation
    }

    /** Returns the declaring [OptionsBase] class that owns this option.  */
    abstract fun <C : com.google.devtools.common.options.OptionsBase?> getDeclaringClass(baseClass: java.lang.Class<C?>?): java.lang.Class<out C?>?

    /**
     * Returns the raw value of the option. Use [.getValue] if possible to correctly handle
     * default values.
     */
    abstract fun getRawValue(optionsBase: com.google.devtools.common.options.OptionsBase?): Any?

    /** Returns the value of this option, taking default values into account.  */
    fun getValue(optionsBase: com.google.devtools.common.options.OptionsBase?): Any? {
        var value = getRawValue(optionsBase)
        if (value == null && !isSpecialNullDefault()) {
            value = getUnparsedDefaultValue()
        }
        return value
    }

    /**
     * Returns the value of this option as a boolean. If the option is not boolean-typed, throws an
     * IllegalStateException.
     */
    fun getBooleanValue(optionsBase: com.google.devtools.common.options.OptionsBase?): Boolean {
        // Check for primitive boolean first, as it's more common.
        check(!(!getType().isAssignableFrom(java.lang.Boolean.TYPE) && !getType().isAssignableFrom(Boolean::class.java))) {
            ("Option "
                    + getOptionName()
                    + " is not a boolean, has type "
                    + getType().getCanonicalName())
        }
        return getValue(optionsBase) == java.lang.Boolean.TRUE
    }

    /** Sets the value for this option.  */
    abstract fun setValue(optionsBase: com.google.devtools.common.options.OptionsBase?, value: Any?)

    /** Returns whether this option is deprecated.  */
    abstract fun isDeprecated(): Boolean

    /** Returns the name of this option.  */
    fun getOptionName(): String? {
        return optionAnnotation.name
    }

    /** Returns a one-character abbreviation for this option, if any.  */
    fun getAbbreviation(): Char {
        return optionAnnotation.abbrev
    }

    /** Returns the help test for this option.  */
    fun getHelpText(): String? {
        return optionAnnotation.help
    }

    /** Returns a short description of the expected type of this option.  */
    fun getValueTypeHelpText(): String? {
        return optionAnnotation.valueHelp
    }

    /**
     * Returns the default value of this option, with no conversion performed. Should only be used by
     * the parser.
     */
    fun getUnparsedDefaultValue(): String? {
        return optionAnnotation.defaultValue
    }

    /**
     * Returns the deprecated option category.
     * 
     */
    @Deprecated("Use {@link #getDocumentationCategory} instead")
    fun getOptionCategory(): String? {
        return optionAnnotation.category
    }

    /** Returns the option category.  */
    fun getDocumentationCategory(): com.google.devtools.common.options.OptionDocumentationCategory? {
        return optionAnnotation.documentationCategory
    }

    /** Returns data about the intended effects of this option.  */
    fun getOptionEffectTags(): Array<com.google.devtools.common.options.OptionEffectTag?>? {
        return optionAnnotation.effectTags
    }

    /** Returns metadata about this option.  */
    fun getOptionMetadataTags(): Array<com.google.devtools.common.options.OptionMetadataTag?>? {
        return optionAnnotation.metadataTags
    }

    /** Returns a converter to use for this option.  */
    fun getProvidedConverter(): java.lang.Class<out com.google.devtools.common.options.Converter<*>?> {
        return optionAnnotation.converter
    }

    /** Returns whether this option allows multiple instances to be combined into a list.  */
    fun allowsMultiple(): Boolean {
        return optionAnnotation.allowMultiple
    }

    /** Returns any options which are added if this option is present.  */
    fun getOptionExpansion(): Array<String?>? {
        return optionAnnotation.expansion
    }

    /** Returns additional options that need to be implicitly added for this option.  */
    fun getImplicitRequirements(): Array<String?>? {
        return optionAnnotation.implicitRequirements
    }

    /** Returns a deprecation warning for this option, if one is present.  */
    fun getDeprecationWarning(): String? {
        return optionAnnotation.deprecationWarning
    }

    /** Returns the old name for this option, if one is present.  */
    fun getOldOptionName(): String? {
        return optionAnnotation.oldName
    }

    /** Returns a warning to use with this option if the old name is specified.  */
    fun getOldNameWarning(): Boolean {
        return optionAnnotation.oldNameWarning
    }

    /** The type of the optionDefinition.  */
    abstract fun getType(): java.lang.Class<*>?

    /** Whether this field has type Void.  */
    fun isVoidField(): Boolean {
        return getType() == java.lang.Void::class.java
    }

    // TODO: blaze-configurability - try to remove special handling for defaults
    fun isSpecialNullDefault(): Boolean {
        return getUnparsedDefaultValue() == com.google.devtools.common.options.OptionDefinition.Companion.SPECIAL_NULL_DEFAULT_VALUE && !getType().isPrimitive()
    }

    /** Returns whether the arg is an expansion option.  */
    fun isExpansionOption(): Boolean {
        return getOptionExpansion().length > 0
    }

    /** Returns whether the arg is an expansion option.  */
    fun hasImplicitRequirements(): Boolean {
        return (getImplicitRequirements().length > 0)
    }

    /**
     * For an option that does not use [Option.allowMultiple], returns its type. For an option
     * that does use it, asserts that the type is a `List<T>` and returns its element type
     * `T`.
     */
    fun getFieldSingularType(): java.lang.reflect.Type? {
        var type: java.lang.reflect.Type? = getSingularType()
        if (allowsMultiple()) {
            // The validity of the converter is checked at compile time. We know the type to be
            // List<singularType>.
            val pfieldType: java.lang.reflect.ParameterizedType = type as java.lang.reflect.ParameterizedType
            type = pfieldType.getActualTypeArguments()[0]
        }
        return type
    }

    protected abstract fun getSingularType(): java.lang.reflect.Type?

    /** Returns the [Converter] that will be used for this option.  */
    fun getConverter(): com.google.devtools.common.options.Converter<*> {
        if (converter != null) {
            return converter
        }

        synchronized(this) {
            if (converter != null) {
                return converter
            }
            val converterClass:  // Converter itself has a type argument
                    java.lang.Class<out com.google.devtools.common.options.Converter<*>?> = getProvidedConverter()
            if (converterClass == com.google.devtools.common.options.Converter::class.java) {
                // No converter provided, use the default one.
                val type: java.lang.reflect.Type? = getFieldSingularType()
                converter = com.google.devtools.common.options.Converters.DEFAULT_CONVERTERS.get(type)
            } else {
                try {
                    // Instantiate the given Converter class.
                    val constructor: java.lang.reflect.Constructor<*> = converterClass.getDeclaredConstructor()
                    constructor.setAccessible(true)
                    converter = constructor.newInstance() as com.google.devtools.common.options.Converter<*>?
                } catch (e: java.lang.SecurityException) {
                    // This indicates an error in the Converter, and should be discovered the first time it is
                    // used.
                    throw com.google.devtools.common.options.ConstructionException(
                        java.lang.String.format("Error in the provided converter for option %s", getMemberName()), e
                    )
                } catch (e: java.lang.IllegalArgumentException) {
                    throw com.google.devtools.common.options.ConstructionException(
                        java.lang.String.format("Error in the provided converter for option %s", getMemberName()), e
                    )
                } catch (e: java.lang.ReflectiveOperationException) {
                    throw com.google.devtools.common.options.ConstructionException(
                        java.lang.String.format("Error in the provided converter for option %s", getMemberName()), e
                    )
                }
            }
            return converter
        }
    }

    /**
     * Returns whether a field should be considered as boolean.
     * 
     * 
     * Can be used for usage help and controlling whether the "no" prefix is allowed.
     */
    fun usesBooleanValueSyntax(): Boolean {
        return getType() == Boolean::class.javaPrimitiveType
                || getType() == com.google.devtools.common.options.TriState::class.java
                || getConverter() is com.google.devtools.common.options.BoolOrEnumConverter<*>
                || getConverter() is com.google.devtools.common.options.BooleanStyleOption
    }

    /**
     * Returns whether an option requires a value when instantiated, or instead can be present without
     * an explicit value.
     */
    fun requiresValue(): Boolean {
        return !isVoidField() && !usesBooleanValueSyntax()
    }

    /** Returns the evaluated default value for this option.  */
    fun getDefaultValue(conversionContext: Any?): Any? {
        if (defaultValue != null) {
            return defaultValue
        }

        synchronized(this) {
            if (defaultValue != null) {
                return defaultValue
            }
            if (isSpecialNullDefault()) {
                return if (allowsMultiple()) com.google.common.collect.ImmutableList.of<Any?>() else null
            }

            val converter: com.google.devtools.common.options.Converter<*> = getConverter()
            val defaultValueAsString = getUnparsedDefaultValue()
            try {
                val convertedDefaultValue: Any = converter.convert(defaultValueAsString, conversionContext)
                defaultValue =
                    if (allowsMultiple())
                        com.google.devtools.common.options.OptionDefinition.Companion.maybeWrapMultipleDefaultValue(
                            convertedDefaultValue
                        )
                    else
                        convertedDefaultValue
            } catch (e: com.google.devtools.common.options.OptionsParsingException) {
                throw com.google.devtools.common.options.ConstructionException(
                    java.lang.String.format(
                        "OptionsParsingException while retrieving the default value for %s: %s",
                        getMemberName(), e.getMessage()
                    ),
                    e
                )
            }
            return defaultValue
        }
    }

    /** Returns the name of the member (field or method) that defines this option.  */
    abstract fun getMemberName(): String?

    override fun compareTo(o: OptionDefinition): Int {
        return getOptionName()!!.compareTo(o.getOptionName()!!)
    }

    companion object {
        /**
         * A special value used to specify an absence of default value.
         * 
         * @see Option.defaultValue
         */
        const val SPECIAL_NULL_DEFAULT_VALUE: String = "null"

        /** An ordering relation for options that orders by the option name.  */
        val BY_OPTION_NAME: java.util.Comparator<OptionDefinition?>? =
            java.util.Comparator.comparing<OptionDefinition?, String?>(java.util.function.Function { obj: OptionDefinition? -> obj!!.getOptionName() })

        /**
         * An ordering relation for options that first groups together options of the same category, then
         * sorts by name within the category.
         */
        val BY_CATEGORY: java.util.Comparator<OptionDefinition?>? =
            java.util.Comparator.comparing<OptionDefinition?, String?>(java.util.function.Function { obj: OptionDefinition? -> obj!!.getOptionCategory() })
                .thenComparing(com.google.devtools.common.options.OptionDefinition.Companion.BY_OPTION_NAME)

        /** Returns all options fields of the given options class, in alphabetic order.  */
        fun getOptionDefinitions(
            optionsClass: java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?
        ): com.google.common.collect.ImmutableList<out OptionDefinition?>? {
            return com.google.devtools.common.options.IsolatedOptionsData.Companion.getAllOptionDefinitionsForClass(
                optionsClass
            )
        }

        /**
         * Two option definitions are considered equivalent for parsing if they result in the same control
         * flow through [OptionsParserImpl.identifyOptionAndPossibleArgument]. This is crucial to
         * ensure that the beginning of the next option can be determined unambiguously when parsing with
         * fallback data.
         * 
         * 
         * Examples:
         * 
         * 
         *  * Both `query` and `cquery` have a `--output` option, but the options
         * accept different sets of values (e.g. `cquery` has `--output=files`, but
         * `query` doesn't. However, since both options accept a string value, they parse
         * equivalently as far as [OptionsParserImpl.identifyOptionAndPossibleArgument] is
         * concerned - potential failures due to unsupported values occur after parsing, during
         * value conversion. There is no ambiguity in how many command-line arguments are consumed
         * depending on which option definition is used.
         *  * If the hypothetical `foo` command also had a `--output` option, but it were
         * boolean-valued, then the two option definitions would **not** be equivalent for
         * parsing: The command line `--output --copt=foo` would parse as `{"output":       "--copt=foo"}` for the `cquery` command, but as `{"output": true, "copt":       "foo"}` for the `foo` command, thus resulting in parsing ambiguities between the
         * two commands.
         * 
         */
        fun equivalentForParsing(
            definition: OptionDefinition, otherDefinition: OptionDefinition
        ): Boolean {
            if (definition == otherDefinition) {
                return true
            }
            return (definition.usesBooleanValueSyntax() == otherDefinition.usesBooleanValueSyntax())
                    && (definition.getType() == java.lang.Void::class.java == (otherDefinition.getType() == java.lang.Void::class.java))
                    && (com.google.common.collect.ImmutableList.copyOf<com.google.devtools.common.options.OptionMetadataTag?>(
                definition.getOptionMetadataTags()
            )
                .contains(com.google.devtools.common.options.OptionMetadataTag.INTERNAL)
                    == com.google.common.collect.ImmutableList.copyOf<com.google.devtools.common.options.OptionMetadataTag?>(
                otherDefinition.getOptionMetadataTags()
            )
                .contains(com.google.devtools.common.options.OptionMetadataTag.INTERNAL))
        }

        /**
         * Wraps a converted default value into a [List] if the converter doesn't do it on its own.
         * 
         * 
         * This is to make sure multiple ([Option.allowMultiple]) options' default values are
         * always converted to a list representation.
         */
        // Not an unchecked cast - there's an explicit type check before it
        protected fun maybeWrapMultipleDefaultValue(convertedDefaultValue: Any): MutableList<Any?> {
            if (convertedDefaultValue is MutableList<*>) {
                return convertedDefaultValue as MutableList<Any?>
            } else {
                return java.util.Arrays.asList<Any?>(convertedDefaultValue)
            }
        }
    }
}
