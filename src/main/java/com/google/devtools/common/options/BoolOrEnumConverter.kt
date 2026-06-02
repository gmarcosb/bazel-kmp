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

/**
 * Converter that can convert both the standard set of boolean string values and enumerations. If
 * there is an overlap in values, those from the underlying enumeration will be taken.
 * 
 * 
 * Note that for the flag to take one of its enum values on the command line, it must be of the
 * form "--flag=value". That is, "--flag value" and "-f value" (if the flag has a short-form of "f")
 * will result in "value" being left as residue on the command line. This maintains compatibility
 * with boolean flags where "--flag true" and "-f true" also leave "true" as residue on the command
 * line.
 */
abstract class BoolOrEnumConverter<T : Enum<T?>?>
/**
 * You *must* implement a zero-argument constructor that delegates
 * to this constructor, passing in the appropriate parameters. This
 * comes from the base [EnumConverter] class.
 * 
 * @param enumType The type of your enumeration; usually a class literal
 * like MyEnum.class
 * @param typeName The intuitive name of your enumeration, for example, the
 * type name for CompilationMode might be "compilation mode".
 * @param trueValue The enumeration value to associate with `true`.
 * @param falseValue The enumeration value to associate with `false`.
 */ protected constructor(
    enumType: java.lang.Class<T?>?,
    typeName: String?,
    private val trueValue: T?,
    private val falseValue: T?
) : com.google.devtools.common.options.EnumConverter<T?>(enumType, typeName) {
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    override fun convert(input: String?): T? {
        try {
            return super.convert(input)
        } catch (eEnum: com.google.devtools.common.options.OptionsParsingException) {
            try {
                val booleanConverter: com.google.devtools.common.options.Converters.BooleanConverter =
                    com.google.devtools.common.options.Converters.BooleanConverter()
                val value: Boolean = booleanConverter.convert(input,  /*conversionContext=*/null)
                return if (value) trueValue else falseValue
            } catch (eBoolean: com.google.devtools.common.options.OptionsParsingException) {
                // TODO(b/111883901): Rethrowing the exception from the enum converter does not report the
                // allowable boolean values.
                throw eEnum
            }
        }
    }
}
