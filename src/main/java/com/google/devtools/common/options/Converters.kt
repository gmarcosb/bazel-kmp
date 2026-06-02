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
package com.google.devtools.common.options

import com.github.benmanes.caffeine.cache.CaffeineSpec
import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import com.google.devtools.build.lib.util.StringEncoding
import java.util.OptionalInt
import java.util.regex.PatternSyntaxException

/** Some convenient converters used by blaze. Note: These are specific to blaze.  */
object Converters {
    private val ENABLED_REPS: com.google.common.collect.ImmutableSet<String?> =
        com.google.common.collect.ImmutableSet.of<String?>("true", "1", "yes", "t", "y")

    private val DISABLED_REPS: com.google.common.collect.ImmutableSet<String?> =
        com.google.common.collect.ImmutableSet.of<String?>("false", "0", "no", "f", "n")

    // 1:1 correspondence with UsesOnlyCoreTypes.CORE_TYPES.
    /**
     * The converters that are available to the options parser by default. These are used if the
     * `@Option` annotation does not specify its own `converter`, and its type is one of
     * the following.
     */
    val DEFAULT_CONVERTERS: com.google.common.collect.ImmutableMap<java.lang.Class<*>?, com.google.devtools.common.options.Converter<*>?> =
        com.google.common.collect.ImmutableMap.Builder<java.lang.Class<*>?, com.google.devtools.common.options.Converter<*>?>()
            .put(String::class.java, com.google.devtools.common.options.Converters.StringConverter())
            .put(Int::class.javaPrimitiveType, com.google.devtools.common.options.Converters.IntegerConverter())
            .put(Long::class.javaPrimitiveType, com.google.devtools.common.options.Converters.LongConverter())
            .put(Double::class.javaPrimitiveType, com.google.devtools.common.options.Converters.DoubleConverter())
            .put(Boolean::class.javaPrimitiveType, com.google.devtools.common.options.Converters.BooleanConverter())
            .put(
                com.google.devtools.common.options.TriState::class.java,
                com.google.devtools.common.options.Converters.TriStateConverter()
            )
            .put(java.time.Duration::class.java, com.google.devtools.common.options.Converters.DurationConverter())
            .put(java.lang.Void::class.java, com.google.devtools.common.options.Converters.VoidConverter())
            .build()

    /**
     * Join a list of words as in English. Examples: "nothing" "one" "one or two" "one and two" "one,
     * two or three". "one, two and three". The toString method of each element is used.
     */
    fun joinEnglishList(choices: Iterable<*>): String {
        val buf: java.lang.StringBuilder = java.lang.StringBuilder()
        val ii: MutableIterator<*> = choices.iterator()
        while (ii.hasNext()) {
            val choice = ii.next()
            if (buf.length() > 0) {
                buf.append(if (ii.hasNext()) ", " else " or ")
            }
            buf.append(choice)
        }
        return if (buf.length() == 0) "nothing" else buf.toString()
    }

    /** Standard converter for booleans. Accepts common shorthands/synonyms.  */
    class BooleanConverter : com.google.devtools.common.options.Converter.Contextless<Boolean?>() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String?): Boolean {
            var input = input
            if (input == null) {
                return false
            }
            input = com.google.common.base.Ascii.toLowerCase(input)
            if (com.google.devtools.common.options.Converters.ENABLED_REPS.contains(input)) {
                return true
            }
            if (com.google.devtools.common.options.Converters.DISABLED_REPS.contains(input)) {
                return false
            }
            throw com.google.devtools.common.options.OptionsParsingException("'" + input + "' is not a boolean")
        }

        override fun getTypeDescription(): String {
            return "a boolean"
        }
    }

    /** Standard converter for Strings.  */
    class StringConverter : com.google.devtools.common.options.Converter.Contextless<String?>() {
        override fun convert(input: String?): String? {
            return input
        }

        override fun getTypeDescription(): String {
            return "a string"
        }
    }

    /**
     * Converter that treats an empty string as `null`, and passes any other value through
     * unchanged. Useful for optional flags that have `defaultValue = "null"` so that an empty
     * value on the command line resets the flag to its unset state instead of being interpreted as a
     * literal empty string.
     */
    class EmptyToNullStringConverter : com.google.devtools.common.options.Converter.Contextless<String?>() {
        override fun convert(input: String): String? {
            if (input.isEmpty()) {
                return null
            }
            return input
        }

        override fun getTypeDescription(): String {
            return "a string; empty to unset"
        }
    }

    /** Standard converter for integers.  */
    class IntegerConverter : com.google.devtools.common.options.Converter.Contextless<Int?>() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String): Int? {
            try {
                return java.lang.Integer.decode(input)
            } catch (e: java.lang.NumberFormatException) {
                throw com.google.devtools.common.options.OptionsParsingException("'" + input + "' is not an int", e)
            }
        }

        override fun getTypeDescription(): String {
            return "an integer"
        }
    }

    /** Standard converter for longs.  */
    class LongConverter : com.google.devtools.common.options.Converter.Contextless<Long?>() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String): Long? {
            try {
                return java.lang.Long.decode(input)
            } catch (e: java.lang.NumberFormatException) {
                throw com.google.devtools.common.options.OptionsParsingException("'" + input + "' is not a long", e)
            }
        }

        override fun getTypeDescription(): String {
            return "a long integer"
        }
    }

    /** Standard converter for doubles.  */
    class DoubleConverter : com.google.devtools.common.options.Converter.Contextless<Double?>() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String): Double {
            try {
                return java.lang.Double.parseDouble(input)
            } catch (e: java.lang.NumberFormatException) {
                throw com.google.devtools.common.options.OptionsParsingException("'" + input + "' is not a double", e)
            }
        }

        override fun getTypeDescription(): String {
            return "a double"
        }
    }

    /** Standard converter for TriState values.  */
    class TriStateConverter :
        com.google.devtools.common.options.EnumConverter<com.google.devtools.common.options.TriState?>(
            com.google.devtools.common.options.TriState::class.java,
            "tri-state (auto, yes, no) option value"
        ) {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String?): com.google.devtools.common.options.TriState {
            var input = input
            if (input == null) {
                return com.google.devtools.common.options.TriState.AUTO
            }
            input = com.google.common.base.Ascii.toLowerCase(input)
            if (input == "auto") {
                return com.google.devtools.common.options.TriState.AUTO
            }
            if (com.google.devtools.common.options.Converters.ENABLED_REPS.contains(input)) {
                return com.google.devtools.common.options.TriState.YES
            }
            if (com.google.devtools.common.options.Converters.DISABLED_REPS.contains(input)) {
                return com.google.devtools.common.options.TriState.NO
            }
            throw com.google.devtools.common.options.OptionsParsingException(
                "Not a valid %s: '%s' (should be auto or a boolean)".formatted(typeName, input)
            )
        }

        override fun getTypeDescription(): String {
            return "a tri-state (auto, yes, no)"
        }
    }

    /**
     * Standard "converter" for Void. Should not actually be invoked. For instance, expansion flags
     * are usually Void-typed and do not invoke the converter.
     */
    class VoidConverter : com.google.devtools.common.options.Converter.Contextless<java.lang.Void?>() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String?): java.lang.Void? {
            if (input == null || input == "null") {
                return null // expected input, return is unused so null is fine.
            }
            throw com.google.devtools.common.options.OptionsParsingException("'" + input + "' unexpected")
        }

        override fun getTypeDescription(): String {
            return ""
        }
    }

    /** Standard converter for the [java.time.Duration] type.  */
    class DurationConverter : com.google.devtools.common.options.Converter.Contextless<java.time.Duration?>() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String?): java.time.Duration? {
            // To be compatible with the previous parser, '0' doesn't need a unit.
            if ("0" == input) {
                return java.time.Duration.ZERO
            }
            val m: java.util.regex.Matcher =
                com.google.devtools.common.options.Converters.DurationConverter.Companion.DURATION_REGEX.matcher(input)
            if (!m.matches()) {
                throw com.google.devtools.common.options.OptionsParsingException("Illegal duration '" + input + "'.")
            }
            val duration: Long = java.lang.Long.parseLong(m.group(1))
            val unit: String = m.group(2)
            when (unit) {
                "d" -> return java.time.Duration.ofDays(duration)
                "h" -> return java.time.Duration.ofHours(duration)
                "m" -> return java.time.Duration.ofMinutes(duration)
                "s" -> return java.time.Duration.ofSeconds(duration)
                "ms" -> return java.time.Duration.ofMillis(duration)
                "ns" -> return java.time.Duration.ofNanos(duration)
                else -> throw java.lang.IllegalStateException(
                    "This must not happen. Did you update the regex without the switch case?"
                )
            }
        }

        override fun getTypeDescription(): String {
            return "An immutable length of time."
        }

        companion object {
            private val DURATION_REGEX: java.util.regex.Pattern =
                java.util.regex.Pattern.compile("^([0-9]+)(d|h|m|s|ms|ns)$")
        }
    }

    /** Converter for a list of options, separated by some separator character.  */
    open class SeparatedOptionListConverter
    protected constructor(
        separator: Char,
        private val separatorDescription: String?,
        private val allowEmptyValues: Boolean
    ) : com.google.devtools.common.options.Converter.Contextless<com.google.common.collect.ImmutableList<String?>?>() {
        private val splitter: com.google.common.base.Splitter

        init {
            this.splitter = com.google.common.base.Splitter.on(separator)
        }

        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String): com.google.common.collect.ImmutableList<String?> {
            val result: com.google.common.collect.ImmutableList<String?> =
                if (input.isEmpty()) com.google.common.collect.ImmutableList.of<String?>() else com.google.common.collect.ImmutableList.copyOf<String?>(
                    splitter.split(input)
                )
            if (!allowEmptyValues && result.contains("")) {
                // If the list contains exactly the empty string, it means an empty value was passed and we
                // should instead return an empty list.
                if (result.size() == 1) {
                    return com.google.common.collect.ImmutableList.of<String?>()
                }

                throw com.google.devtools.common.options.OptionsParsingException(
                    "Empty values are not allowed as part of this " + getTypeDescription()
                )
            }
            return result
        }

        override fun getTypeDescription(): String {
            return separatorDescription + "-separated list of options"
        }
    }

    /**
     * Converter for options separated by some separator character, where order and count do not
     * matter, i.e. semantically it is a set, not a list.
     */
    open class SeparatedOptionSetConverter protected constructor(
        separator: Char,
        private val separatorDescription: String?,
        allowEmptyValues: Boolean
    ) : SeparatedOptionListConverter(
        separator,
        separatorDescription, allowEmptyValues
    ) {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String): com.google.common.collect.ImmutableList<String?> {
            val result: com.google.common.collect.ImmutableList<String?> = super.convert(input)
            return result.stream().distinct().sorted()
                .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())
        }

        override fun getTypeDescription(): String {
            return separatorDescription + "-separated set of options"
        }
    }

    /**
     * Converter for comma separated values, where
     *  * order and multiplicity preserved
     *  * empty values are preserved
     */
    class CommaSeparatedOptionListConverter : SeparatedOptionListConverter(',', "comma", true)

    /**
     * Converter for comma separated values, where
     *  * order and multiplicity preserved
     *  * empty values are filtered out
     */
    class CommaSeparatedNonEmptyOptionListConverter

        : SeparatedOptionListConverter(',', "comma", false)

    /**
     * Converter for colon separated values, where
     *  * order and multiplicity preserved
     *  * empty values are preserved
     */
    class ColonSeparatedOptionListConverter : SeparatedOptionListConverter(':', "colon", true)

    /**
     * Converter for colon separated values, where
     *  * order and multiplicity are assumed to not matter
     *  * empty values are preserved
     */
    class CommaSeparatedOptionSetConverter : SeparatedOptionSetConverter(',', "comma", true)

    /** Converter for [Level].  */
    class LogLevelConverter : com.google.devtools.common.options.Converter.Contextless<java.util.logging.Level?>() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String): java.util.logging.Level {
            try {
                val level: Int = java.lang.Integer.parseInt(input)
                return com.google.devtools.common.options.Converters.LogLevelConverter.Companion.LEVELS.get(level)
            } catch (e: java.lang.NumberFormatException) {
                throw com.google.devtools.common.options.OptionsParsingException("Not a log level: " + input, e)
            } catch (e: java.lang.ArrayIndexOutOfBoundsException) {
                throw com.google.devtools.common.options.OptionsParsingException("Not a log level: " + input, e)
            }
        }

        override fun getTypeDescription(): String {
            return "0 <= an integer <= " + (com.google.devtools.common.options.Converters.LogLevelConverter.Companion.LEVELS.size() - 1)
        }

        companion object {
            @kotlin.jvm.JvmField
            val LEVELS: com.google.common.collect.ImmutableList<java.util.logging.Level> =
                com.google.common.collect.ImmutableList.of<java.util.logging.Level?>(
                    java.util.logging.Level.OFF,
                    java.util.logging.Level.SEVERE,
                    java.util.logging.Level.WARNING,
                    java.util.logging.Level.INFO,
                    java.util.logging.Level.FINE,
                    java.util.logging.Level.FINER,
                    java.util.logging.Level.FINEST
                )
        }
    }

    /** Checks whether a string is part of a set of strings.  */
    class StringSetConverter(vararg values: String?) :
        com.google.devtools.common.options.Converter.Contextless<String?>() {
        // TODO(bazel-team): if this class never actually contains duplicates, we could s/List/Set/
        // here.
        private val values: com.google.common.collect.ImmutableList<String?>

        init {
            this.values = com.google.common.collect.ImmutableList.copyOf<String?>(values)
        }

        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String?): String? {
            if (values.contains(input)) {
                return input
            }

            throw com.google.devtools.common.options.OptionsParsingException("Not one of " + values)
        }

        override fun getTypeDescription(): String {
            return com.google.devtools.common.options.Converters.joinEnglishList(values)
        }
    }

    /** Checks whether a string is a valid regex pattern and compiles it.  */
    class RegexPatternConverter :
        com.google.devtools.common.options.Converter.Contextless<com.google.devtools.common.options.RegexPatternOption?>() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String?): com.google.devtools.common.options.RegexPatternOption {
            try {
                return com.google.devtools.common.options.RegexPatternOption.Companion.create(
                    java.util.regex.Pattern.compile(
                        StringEncoding.internalToUnicode(input),
                        java.util.regex.Pattern.DOTALL
                    )
                )
            } catch (e: PatternSyntaxException) {
                throw com.google.devtools.common.options.OptionsParsingException("Not a valid regular expression: " + e.getMessage())
            }
        }

        override fun getTypeDescription(): String {
            return "a valid Java regular expression"
        }
    }

    /** Checks whether an integer is in the given range.  */
    open class RangeConverter(val minValue: Int, val maxValue: Int) :
        com.google.devtools.common.options.Converter.Contextless<Int?>() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String): Int {
            try {
                val value: Int = java.lang.Integer.parseInt(input)
                if (value < minValue) {
                    throw com.google.devtools.common.options.OptionsParsingException("'" + input + "' should be >= " + minValue)
                } else if (value < minValue || value > maxValue) {
                    throw com.google.devtools.common.options.OptionsParsingException("'" + input + "' should be <= " + maxValue)
                }
                return value
            } catch (e: java.lang.NumberFormatException) {
                throw com.google.devtools.common.options.OptionsParsingException("'" + input + "' is not an int", e)
            }
        }

        override fun getTypeDescription(): String {
            if (minValue == java.lang.Integer.MIN_VALUE) {
                if (maxValue == java.lang.Integer.MAX_VALUE) {
                    return "an integer"
                } else {
                    return "an integer, <= " + maxValue
                }
            } else if (maxValue == java.lang.Integer.MAX_VALUE) {
                return "an integer, >= " + minValue
            } else {
                return ("an integer in "
                        + (if (minValue < 0) "(" + minValue + ")" else minValue)
                        + "-"
                        + maxValue
                        + " range")
            }
        }
    }

    /**
     * A converter for variable assignments from the parameter list of a blaze command invocation.
     * Assignments are expected to have the form "name=value", where names and values are defined to
     * be as permissive as possible.
     */
    class AssignmentConverter :
        com.google.devtools.common.options.Converter.Contextless<MutableMap.MutableEntry<String?, String?>?>() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String): MutableMap.MutableEntry<String?, String?> {
            val pos: Int = input.indexOf("=")
            if (pos <= 0) {
                throw com.google.devtools.common.options.OptionsParsingException(
                    "Variable definitions must be in the form of a 'name=value' assignment"
                )
            }
            val name: String = input.substring(0, pos)
            val value: String = input.substring(pos + 1)
            return com.google.common.collect.Maps.immutableEntry<String?, String?>(name, value)
        }

        override fun getTypeDescription(): String {
            return "a 'name=value' assignment"
        }
    }

    /** A converter for for assignments from a string value to a float value.  */
    class StringToDoubleAssignmentConverter

        : com.google.devtools.common.options.Converter.Contextless<MutableMap.MutableEntry<String?, Double?>?>() {
        @Throws(
            com.google.devtools.common.options.OptionsParsingException::class,
            java.lang.NumberFormatException::class
        )
        override fun convert(input: String): MutableMap.MutableEntry<String?, Double?> {
            val stringEntry: MutableMap.MutableEntry<String?, String?> =
                com.google.devtools.common.options.Converters.StringToDoubleAssignmentConverter.Companion.baseConverter.convert(
                    input
                )
            return com.google.common.collect.Maps.immutableEntry<String?, Double?>(
                stringEntry.getKey(),
                java.lang.Double.parseDouble(stringEntry.getValue())
            )
        }

        override fun getTypeDescription(): String {
            return "a named float, 'name=value'"
        }

        companion object {
            private val baseConverter: AssignmentConverter =
                com.google.devtools.common.options.Converters.AssignmentConverter()
        }
    }

    /**
     * Base converter for assignments from a value to a list of values. Both the key type as well as
     * the type for all instances in the list of values are processed via passed converters.
     */
    abstract class AssignmentToListOfValuesConverter<K, V>
        (
        keyConverter: com.google.devtools.common.options.Converter<K?>,
        valueConverter: com.google.devtools.common.options.Converter<V?>,
        allowEmptyKeys: AllowEmptyKeys?
    ) : com.google.devtools.common.options.Converter<MutableMap.MutableEntry<K?, MutableList<V?>?>?> {
        /** Whether to allow keys in the assignment to be empty (i.e. just a list of values)  */
        enum class AllowEmptyKeys {
            YES,
            NO
        }

        private val keyConverter: com.google.devtools.common.options.Converter<K?>
        private val valueConverter: com.google.devtools.common.options.Converter<V?>
        private val allowEmptyKeys: AllowEmptyKeys?

        init {
            this.keyConverter = keyConverter
            this.valueConverter = valueConverter
            this.allowEmptyKeys = allowEmptyKeys
        }

        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String, conversionContext: Any?): MutableMap.MutableEntry<K?, MutableList<V?>?> {
            val pos: Int = input.indexOf("=")
            if (allowEmptyKeys == com.google.devtools.common.options.Converters.AssignmentToListOfValuesConverter.AllowEmptyKeys.NO && pos <= 0) {
                throw com.google.devtools.common.options.OptionsParsingException(
                    "Must be in the form of a 'key=value[,value]' assignment"
                )
            }

            val key = if (pos <= 0) "" else input.substring(0, pos)
            var values: MutableList<String?> =
                com.google.devtools.common.options.Converters.AssignmentToListOfValuesConverter.Companion.SPLITTER.splitToList(
                    input.substring(pos + 1)
                )
            if (values.contains("")) {
                // If the list contains exactly the empty string, it means an empty value was passed and we
                // should instead return an empty list.
                if (values.size() == 1) {
                    values = com.google.common.collect.ImmutableList.of<String?>()
                } else {
                    throw com.google.devtools.common.options.OptionsParsingException(
                        "Variable definitions must not contain empty strings or leading / trailing commas"
                    )
                }
            }
            val convertedValues: com.google.common.collect.ImmutableList.Builder<V?> =
                com.google.common.collect.ImmutableList.builder<V?>()
            for (value in values) {
                convertedValues.add(valueConverter.convert(value, conversionContext))
            }
            return com.google.common.collect.Maps.immutableEntry<K?, MutableList<V?>?>(
                keyConverter.convert(key, conversionContext), convertedValues.build()
            )
        }

        companion object {
            private val SPLITTER: com.google.common.base.Splitter = com.google.common.base.Splitter.on(',')
        }
    }

    /**
     * A converter for variable assignments from the parameter list of a blaze command invocation.
     * Assignments are expected to have the form `[name=]value1[,..,valueN]`, where names and
     * values are defined to be as permissive as possible. If no name is provided, "" is used.
     */
    class StringToStringListConverter

        : AssignmentToListOfValuesConverter<String?, String?>(
        com.google.devtools.common.options.Converters.StringConverter(),
        com.google.devtools.common.options.Converters.StringConverter(),
        com.google.devtools.common.options.Converters.AssignmentToListOfValuesConverter.AllowEmptyKeys.YES
    ) {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        fun convert(input: String): MutableMap.MutableEntry<String?, MutableList<String?>?> {
            return convert(input,  /* conversionContext= */null)
        }

        override fun getTypeDescription(): String {
            return "a '[name=]value1[,..,valueN]' assignment"
        }
    }

    /** A [Converter] for [HelpVerbosity].  */
    class HelpVerbosityConverter :
        com.google.devtools.common.options.EnumConverter<com.google.devtools.common.options.HelpVerbosity?>(
            com.google.devtools.common.options.HelpVerbosity::class.java,
            "--help_verbosity setting"
        )

    /**
     * A converter to check whether an integer denoting a percentage is in a valid range: [0, 100].
     */
    class PercentageConverter : RangeConverter(0, 100)

    /** Same as [PercentageConverter] but also supports being unset.  */
    class OptionalPercentageConverter : com.google.devtools.common.options.Converter.Contextless<OptionalInt?>() {
        override fun getTypeDescription(): String {
            return "an integer"
        }

        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String): OptionalInt {
            return if (input == com.google.devtools.common.options.Converters.OptionalPercentageConverter.Companion.UNSET)
                OptionalInt.empty()
            else
                OptionalInt.of(
                    com.google.devtools.common.options.Converters.OptionalPercentageConverter.Companion.PERCENTAGE_CONVERTER.convert(
                        input
                    )
                )
        }

        companion object {
            const val UNSET: String = "-1"
            private val PERCENTAGE_CONVERTER: PercentageConverter =
                com.google.devtools.common.options.Converters.PercentageConverter()
        }
    }

    /**
     * A [Converter] for [com.github.benmanes.caffeine.cache.CaffeineSpec]. The spec may
     * be empty, in which case this converter returns null.
     */
    class CaffeineSpecConverter : com.google.devtools.common.options.Converter.Contextless<CaffeineSpec?>() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(spec: String): CaffeineSpec {
            try {
                return CaffeineSpec.parse(spec)
            } catch (e: java.lang.IllegalArgumentException) {
                throw com.google.devtools.common.options.OptionsParsingException(
                    "Failed to parse CaffeineSpec: " + e.getMessage(),
                    e
                )
            }
        }

        override fun getTypeDescription(): String {
            return "Converts to a CaffeineSpec, or null if the input is empty"
        }
    }

    /** A [Converter] for a size in bytes with an optional multiplier suffix.  */
    class ByteSizeConverter : com.google.devtools.common.options.Converter.Contextless<Long?>() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String?): Long {
            val m: java.util.regex.Matcher =
                com.google.devtools.common.options.Converters.ByteSizeConverter.Companion.PATTERN.matcher(input)
            if (!m.matches()) {
                throw com.google.devtools.common.options.OptionsParsingException("Invalid size: " + input)
            }
            try {
                var value: Long = java.lang.Long.parseLong(m.group("value"))
                val mult: String = m.group("multiplier")
                if (!mult.isEmpty()) {
                    value = java.lang.Math.multiplyExact(
                        value,
                        com.google.devtools.common.options.Converters.ByteSizeConverter.Companion.MULTIPLIER_MAP.get(
                            mult
                        ) as Long
                    )
                }
                return value
            } catch (e: java.lang.NumberFormatException) {
                throw com.google.devtools.common.options.OptionsParsingException("Invalid size: " + input, e)
            } catch (e: java.lang.ArithmeticException) {
                throw com.google.devtools.common.options.OptionsParsingException("Invalid size: " + input, e)
            }
        }

        override fun getTypeDescription(): String {
            return "a size in bytes, optionally followed by a K, M, G or T multiplier"
        }

        companion object {
            private val PATTERN: java.util.regex.Pattern =
                java.util.regex.Pattern.compile("(?<value>[0-9]+)(?<multiplier>[KMGT]?)")

            private val MULTIPLIER_MAP: com.google.common.collect.ImmutableMap<String?, Long?> =
                com.google.common.collect.ImmutableMap.of<String?, Long?>(
                    "K",
                    1024L,
                    "M",
                    1024L * 1024L,
                    "G",
                    1024L * 1024L * 1024L,
                    "T",
                    1024L * 1024L * 1024L * 1024L
                )
        }
    }
}
