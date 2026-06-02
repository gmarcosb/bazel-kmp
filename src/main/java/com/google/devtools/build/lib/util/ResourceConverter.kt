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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.actions.LocalHostCapacity

/**
 * Converter for options that configure Bazel's resource usage.
 * 
 * 
 * The option can take either a value or one of the keywords `auto`, `HOST_CPUS`, or
 * `HOST_RAM`, followed by an optional operator in the form `[-|*]<float>`.
 * 
 * 
 * If a keyword is passed, the converter returns the keyword's value in the [.keywords]
 * map, scaled by the operation that follows if there is one. All values, explicit and derived, are
 * adjusted for validity.
 * 
 * 
 * The supplier of the auto value, and, optionally, a max or min allowed value (inclusive), are
 * passed to the constructor.
 */
abstract class ResourceConverter<T>
    (
    keywords: com.google.common.collect.ImmutableMap<String, java.util.function.Supplier<T?>?>,
    minValue: T?,
    maxValue: T?
) : com.google.devtools.common.options.Converter.Contextless<T?>() where T : Number?, T : Comparable<T?>? {
    /** Resource converter for assignments.  */
    class AssignmentConverter :
        com.google.devtools.common.options.Converter.Contextless<MutableMap.MutableEntry<String?, Double?>?>() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String): MutableMap.MutableEntry<String?, Double?> {
            val s: MutableMap.MutableEntry<String?, String?> =
                com.google.devtools.build.lib.util.ResourceConverter.AssignmentConverter.Companion.assignment.convert(
                    input
                )
            return java.util.Map.entry<String?, Double?>(
                s.key,
                com.google.devtools.build.lib.util.ResourceConverter.AssignmentConverter.Companion.resource.convert(s.value)
            )
        }

        val typeDescription: String
            get() = "a named double, 'name=value', where value is " + com.google.devtools.build.lib.util.ResourceConverter.AssignmentConverter.Companion.resource.getTypeDescription()

        companion object {
            private val assignment: com.google.devtools.common.options.Converters.AssignmentConverter =
                com.google.devtools.common.options.Converters.AssignmentConverter()
            private val resource: DoubleConverter =
                com.google.devtools.build.lib.util.ResourceConverter.DoubleConverter(
                    com.google.common.collect.ImmutableMap.of<String?, java.util.function.Supplier<Double?>?>(
                        HOST_CPUS_KEYWORD,
                        java.util.function.Supplier { HOST_CPUS_SUPPLIER.get().toDouble() },
                        HOST_RAM_KEYWORD,
                        java.util.function.Supplier { HOST_RAM_SUPPLIER.get().toDouble() }),
                    0.0,
                    Double.Companion.MAX_VALUE
                )
        }
    }

    /** Resource converter for integers.  */
    open class IntegerConverter(
        keywords: com.google.common.collect.ImmutableMap<String, java.util.function.Supplier<Int?>?>,
        minValue: Int,
        maxValue: Int
    ) : ResourceConverter<Int?>(keywords, minValue, maxValue) {
        constructor(auto: java.util.function.Supplier<Int?>, minValue: Int, maxValue: Int) : this(
            com.google.common.collect.ImmutableMap.of<String?, java.util.function.Supplier<Int?>?>(
                AUTO_KEYWORD,
                auto,
                HOST_CPUS_KEYWORD,
                HOST_CPUS_SUPPLIER,
                HOST_RAM_KEYWORD,
                HOST_RAM_SUPPLIER
            ),
            minValue,
            maxValue
        )

        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String): Int? {
            return if (com.google.common.primitives.Ints.tryParse(input) != null)
                checkAndLimit(
                    com.google.devtools.build.lib.util.ResourceConverter.IntegerConverter.Companion.converter.convert(
                        input
                    )
                )
            else
                checkAndLimit(java.lang.Math.round(convertKeyword(input)).toInt())
        }

        companion object {
            private val converter: com.google.devtools.common.options.Converters.IntegerConverter =
                com.google.devtools.common.options.Converters.IntegerConverter()
        }
    }

    /** Resource converter for doubles.  */
    class DoubleConverter(
        keywords: com.google.common.collect.ImmutableMap<String, java.util.function.Supplier<Double?>?>,
        minValue: Double,
        maxValue: Double
    ) : ResourceConverter<Double?>(keywords, minValue, maxValue) {
        constructor(auto: java.util.function.Supplier<Double?>, minValue: Double, maxValue: Double) : this(
            com.google.common.collect.ImmutableMap.of<String?, java.util.function.Supplier<Double?>?>(
                AUTO_KEYWORD, auto,
                HOST_CPUS_KEYWORD, java.util.function.Supplier { HOST_CPUS_SUPPLIER.get().toDouble() },
                HOST_RAM_KEYWORD, java.util.function.Supplier { HOST_RAM_SUPPLIER.get().toDouble() }),
            minValue,
            maxValue
        )

        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String): Double? {
            return if (com.google.common.primitives.Doubles.tryParse(input) != null)
                checkAndLimit(
                    com.google.devtools.build.lib.util.ResourceConverter.DoubleConverter.Companion.converter.convert(
                        input
                    )
                )
            else
                convertKeyword(input)
        }

        companion object {
            private val converter: com.google.devtools.common.options.Converters.DoubleConverter =
                com.google.devtools.common.options.Converters.DoubleConverter()
        }
    }

    private val keywords: com.google.common.collect.ImmutableMap<String, java.util.function.Supplier<T?>?>

    private val validInputPattern: java.util.regex.Pattern

    @kotlin.jvm.JvmField
    protected val minValue: T?

    @kotlin.jvm.JvmField
    protected val maxValue: T?

    /**
     * Constructs a ResourceConverter for options that take keywords other than the default set.
     * 
     * @param keywords a map of keyword to the suppliers of their values
     */
    init {
        this.keywords = keywords
        this.validInputPattern =
            java.util.regex.Pattern.compile(
                String.format(
                    "(?<keyword>%s)(?<expression>[%s][0-9]?(?:.[0-9]+)?)?",
                    java.lang.String.join("|", this.keywords.keys), java.lang.String.join("", OPERATORS.keys)
                )
            )
        this.minValue = minValue
        this.maxValue = maxValue
    }

    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    fun convertKeyword(input: String?): Double {
        val matcher: java.util.regex.Matcher = validInputPattern.matcher(input)
        if (matcher.matches()) {
            val resourceSupplier: java.util.function.Supplier<T?>? = keywords.get(matcher.group("keyword"))
            if (resourceSupplier != null) {
                return applyOperator(matcher.group("expression"), resourceSupplier)
            }
        }
        throw com.google.devtools.common.options.OptionsParsingException(
            String.format(
                "Parameter '%s' does not follow correct syntax. This flag takes %s.",
                input, this.typeDescription
            )
        )
    }

    /** Applies function designated in `expression` ([-|*]<float>) to value. </float> */
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    private fun applyOperator(expression: String?, firstOperandSupplier: java.util.function.Supplier<T?>): Double {
        if (expression == null) {
            return firstOperandSupplier.get().toDouble()
        }
        for (operator in OPERATORS.entries) {
            if (expression.startsWith(operator.key)) {
                val secondOperand: Float
                try {
                    secondOperand = expression.substring(operator.key.length).toFloat()
                } catch (e: java.lang.NumberFormatException) {
                    throw com.google.devtools.common.options.OptionsParsingException(
                        String.format("'%s is not a float", expression.substring(operator.key.length)),
                        e
                    )
                }
                return operator
                    .value
                    .applyAsDouble(firstOperandSupplier.get().toDouble(), secondOperand.toDouble())
            }
        }
        // This should never happen because we've checked for a valid operator already.
        throw com.google.devtools.common.options.OptionsParsingException(
            String.format("Parameter value '%s' does not contain a valid operator.", expression)
        )
    }

    /**
     * Checks validity of a resource value against min/max constraints. Implementations may choose to
     * either raise an exception on out-of-bounds values, or adjust them to within the constraints.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    open fun checkAndLimit(value: T?): T? {
        if (value!!.compareTo(minValue) < 0) {
            throw com.google.devtools.common.options.OptionsParsingException(
                String.format(
                    "Value '(%f)' must be at least %f.", value.toDouble(), minValue!!.toDouble()
                )
            )
        }
        if (value.compareTo(maxValue) > 0) {
            throw com.google.devtools.common.options.OptionsParsingException(
                String.format(
                    "Value '(%f)' cannot be greater than %f.",
                    value.toDouble(), maxValue!!.toDouble()
                )
            )
        }
        return value
    }

    val typeDescription: String
        get() {
            val firstKeyword: String = keywords.keys.iterator().next()
            return ("an integer, or a keyword (\""
                    + java.lang.String.join("\", \"", keywords.keys)
                    + "\"), optionally followed by an operation ([-|*]<float>) eg. \""
                    + firstKeyword
                    + "\", \""
                    + HOST_CPUS_KEYWORD
                    + "*.5\"")
        }

    companion object {
        const val AUTO_KEYWORD: String = "auto"
        const val HOST_CPUS_KEYWORD: String = "HOST_CPUS"
        const val HOST_RAM_KEYWORD: String = "HOST_RAM"

        @kotlin.jvm.JvmField
        val HOST_CPUS_SUPPLIER: java.util.function.Supplier<Int?> =
            java.util.function.Supplier { ceil(LocalHostCapacity.getLocalHostCapacity().getCpuUsage()) as Int }
        val HOST_RAM_SUPPLIER: java.util.function.Supplier<Int?> =
            java.util.function.Supplier { ceil(LocalHostCapacity.getLocalHostCapacity().getMemoryMb()) as Int }

        private val OPERATORS: com.google.common.collect.ImmutableMap<String?, DoubleBinaryOperator?> =
            com.google.common.collect.ImmutableMap.builder<String?, DoubleBinaryOperator?>()
                .put("-", DoubleBinaryOperator { l: Double, r: Double -> l - r })
                .put("*", DoubleBinaryOperator { l: Double, r: Double -> l * r })
                .build()

        /** Description of the accepted inputs to the converter.  */
        @kotlin.jvm.JvmField
        val FLAG_SYNTAX: String = ("an integer, or a keyword (\""
                + AUTO_KEYWORD
                + "\", \""
                + HOST_CPUS_KEYWORD
                + "\", \""
                + HOST_RAM_KEYWORD
                + "\"), optionally followed by an operation ([-|*]<float>) eg. \""
                + AUTO_KEYWORD
                + "\", \""
                + HOST_CPUS_KEYWORD
                + "*.5\"")
    }
}
