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

import com.google.devtools.common.options.Converters.BooleanConverter

/**
 * Converter that can convert both the standard set of boolean string values and enumerations. If
 * there is an overlap in values, those from the underlying enumeration will be taken.
 *
 * Note that for the flag to take one of its enum values on the command line, it must be of the
 * form "--flag=value". That is, "--flag value" and "-f value" (if the flag has a short-form of "f")
 * will result in "value" being left as residue on the command line. This maintains compatibility
 * with boolean flags where "--flag true" and "-f true" also leave "true" as residue on the command
 * line.
 */
abstract class BoolOrEnumConverter<T : Enum<T>>(
  enumType: Class<T>,
  typeName: String,
  private val trueValue: T,
  private val falseValue: T
) : EnumConverter<T>(enumType, typeName) {

  @Throws(OptionsParsingException::class)
  override fun convert(input: String?): T {
    return try {
      super.convert(input)
    } catch (eEnum: OptionsParsingException) {
      try {
        val booleanConverter = BooleanConverter()
        val value = booleanConverter.convert(input, /* conversionContext= */ null)
        if (value) trueValue else falseValue
      } catch (eBoolean: OptionsParsingException) {
        // TODO(b/111883901): Rethrowing the exception from the enum converter does not report the
        // allowable boolean values.
        throw eEnum
      }
    }
  }
}
