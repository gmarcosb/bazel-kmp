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

import java.util.Collections
import java.util.EnumSet
import java.util.LinkedHashSet

/**
 * Converter that translates a string of the form "value1,value2,-value3,value4" into a
 * corresponding set of allowed Enum values.
 * 
 * 
 * Values preceded by '-' are excluded from this set. So "value1,-value2,value3" translates to
 * the set [EnumType.value1, EnumType.value3].
 * 
 * 
 * If *all* values are exclusions (e.g. "-value1,-value2,-value3"), the returned set contains all
 * values for the Enum type *except* those specified.
 */
internal open class EnumFilterConverter<E : Enum<E?>?>(typeClass: java.lang.Class<E?>, userFriendlyName: String) :
    com.google.devtools.common.options.Converter.Contextless<MutableSet<E?>?>() {
    private val allowedValues: MutableSet<String?> = LinkedHashSet<String?>()
    private val typeClass: java.lang.Class<E?>
    private val prettyEnumName: String

    /**
     * Constructor.
     * 
     * @param typeClass this should be E.class (Java generics can't infer that directly)
     * @param userFriendlyName a user-friendly description of this enum type
     */
    init {
        this.typeClass = typeClass
        this.prettyEnumName = userFriendlyName
        for (value in EnumSet.allOf<E?>(typeClass)) {
            allowedValues.add(value.name())
        }
    }

    /**
     * Returns the set of allowed values for the option.
     * 
     * 
     * Implements [Converter.convert].
     */
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    override fun convert(input: String): MutableSet<E?>? {
        if (input.isEmpty()) {
            return Collections.emptySet<E?>()
        }
        var includedSet: EnumSet<E?> = EnumSet.noneOf<E?>(typeClass)
        val excludedSet: EnumSet<E?> = EnumSet.noneOf<E?>(typeClass)
        for (value in input.split(",", -1)) {
            val excludeFlag: Boolean = value.startsWith("-")
            val s: String? = (if (excludeFlag) value.substring(1) else value).toUpperCase()
            if (!allowedValues.contains(s)) {
                throw com.google.devtools.common.options.OptionsParsingException(
                    "Invalid " + prettyEnumName + " filter '" + value +
                            "' in the input '" + input + "'"
                )
            }
            (if (excludeFlag) excludedSet else includedSet).add(java.lang.Enum.valueOf<E?>(typeClass, s))
        }
        if (includedSet.isEmpty()) {
            includedSet = EnumSet.complementOf<E?>(excludedSet)
        } else {
            includedSet.removeAll(excludedSet)
        }
        if (includedSet.isEmpty()) {
            throw com.google.devtools.common.options.OptionsParsingException(
                java.lang.Character.toUpperCase(prettyEnumName.charAt(0)).toString() + prettyEnumName.substring(1) +
                        " filter '" + input + "' definition cannot match any tests"
            )
        }
        return includedSet
    }

    /**
     * Implements [.getTypeDescription].
     */
    override fun getTypeDescription(): String {
        return ("comma-separated list of values: "
                + com.google.devtools.build.lib.util.StringUtil.joinEnglishList(allowedValues).toLowerCase())
    }
}
