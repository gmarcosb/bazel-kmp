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

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import java.util.HashMap

/**
 * A converter superclass for converters that parse enums.
 * 
 * 
 * Just subclass this class, creating a zero argument constructor that calls [ ][.EnumConverter].
 * 
 * 
 * This class compares the input string to the string returned by the toString() method of each
 * enum member in a case-insensitive way. Usually, this is the name of the symbol, but beware if you
 * override toString()!
 */
abstract class EnumConverter<T : Enum<T?>?> protected constructor(
    enumType: java.lang.Class<T?>,
    protected val typeName: String?
) : com.google.devtools.common.options.Converter.Contextless<T?>() {
    private val enumType: java.lang.Class<T?>

    /**
     * Creates a new enum converter. You *must* implement a zero-argument constructor that delegates
     * to this constructor, passing in the appropriate parameters.
     * 
     * @param enumType The type of your enumeration; usually a class literal like MyEnum.class. All
     * enum constants of the given type must have unique case-insensitive toString() values.
     * @param typeName The intuitive name of your enumeration, for example, the type name for
     * CompilationMode might be "compilation mode".
     */
    init {
        this.enumType =
            com.google.devtools.common.options.EnumConverter.Companion.checkUniqueCaseInsensitiveStringRepresentation<T?>(
                enumType
            )
    }

    /** Implements [Converter.convert].  */
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    override fun convert(input: String): T? {
        for (value in enumType.getEnumConstants()) {
            if (com.google.common.base.Ascii.equalsIgnoreCase(value.toString(), input)) {
                return value
            }
        }
        throw com.google.devtools.common.options.OptionsParsingException(
            "Not a valid %s: '%s' (should be %s)".formatted(typeName, input, getTypeDescription())
        )
    }

    /** Implements [.getTypeDescription].  */
    override fun getTypeDescription(): String {
        return com.google.common.base.Ascii.toLowerCase(
            com.google.devtools.common.options.Converters.joinEnglishList(java.util.Arrays.asList<T?>(*enumType.getEnumConstants()))
        )
    }

    override fun starlarkConvertible(): Boolean {
        return true
    }

    override fun reverseForStarlark(converted: Any?): String {
        com.google.common.base.Preconditions.checkArgument(enumType.isInstance(converted))
        return com.google.common.base.Ascii.toLowerCase(converted.toString())
    }

    fun getEnumType(): java.lang.Class<T?> {
        return enumType
    }

    companion object {
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        private fun <T : Enum<T?>?> checkUniqueCaseInsensitiveStringRepresentation(
            enumType: java.lang.Class<T?>
        ): java.lang.Class<T?> {
            val enumConstants: HashMap<String?, Enum<*>?> = HashMap<String?, Enum<*>?>()
            for (value in enumType.getEnumConstants()) {
                val key: String = com.google.common.base.Ascii.toLowerCase(value.toString())
                require(!enumConstants.containsKey(key)) {
                    java.lang.String.format(
                        "Enum type %s values %s and %s collide in their case-insensitive string"
                                + " representation '%s'",
                        enumType.getName(), enumConstants.get(key).name(), value.name(), key
                    )
                }
                enumConstants.put(key, value)
            }
            return enumType
        }
    }
}
