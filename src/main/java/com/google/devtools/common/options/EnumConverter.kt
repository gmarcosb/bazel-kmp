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

import com.google.common.base.Ascii
import com.google.errorprone.annotations.CanIgnoreReturnValue

/**
 * A converter superclass for converters that parse enums.
 *
 * Just subclass this class, creating a zero argument constructor that calls [EnumConverter].
 *
 * This class compares the input string to the string returned by the toString() method of each
 * enum member in a case-insensitive way. Usually, this is the name of the symbol, but beware if you
 * override toString()!
 */
abstract class EnumConverter<T : Enum<T>>(
  val enumType: Class<T>,
  val typeName: String
) : Converter.Contextless<T>() {

  init {
    checkUniqueCaseInsensitiveStringRepresentation(enumType)
  }

  companion object {
    @CanIgnoreReturnValue
    private fun <T : Enum<T>> checkUniqueCaseInsensitiveStringRepresentation(
      enumType: Class<T>
    ): Class<T> {
      val enumConstants = HashMap<String, Enum<*>>()
      for (value in enumType.enumConstants) {
        val key = Ascii.toLowerCase(value.toString())
        if (enumConstants.containsKey(key)) {
          throw IllegalArgumentException(
            String.format(
              "Enum type %s values %s and %s collide in their case-insensitive string representation '%s'",
              enumType.name, enumConstants[key]!!.name, value.name, key
            )
          )
        }
        enumConstants[key] = value
      }
      return enumType
    }
  }

  /** Implements [Converter.convert]. */
  @Throws(OptionsParsingException::class)
  override fun convert(input: String?): T {
    for (value in enumType.enumConstants) {
      if (Ascii.equalsIgnoreCase(value.toString(), input)) {
        return value
      }
    }
    throw OptionsParsingException(
      "Not a valid %s: '%s' (should be %s)".format(typeName, input, getTypeDescription())
    )
  }

  /** Implements [getTypeDescription]. */
  override fun getTypeDescription(): String {
    return Ascii.toLowerCase(
      Converters.joinEnglishList(enumType.enumConstants.toList())
    )
  }

  override fun starlarkConvertible(): Boolean = true

  override fun reverseForStarlark(converted: Any?): String {
    require(enumType.isInstance(converted))
    return Ascii.toLowerCase(converted.toString())
  }
}
