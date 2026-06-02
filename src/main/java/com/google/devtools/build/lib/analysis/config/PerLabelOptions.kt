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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.actions.Artifact

/**
 * Models options that can be added to a command line when a label matches a given [ ].
 */
class PerLabelOptions(regexFilter: com.google.devtools.build.lib.util.RegexFilter, optionsList: MutableList<String?>) {
    /** The filter used to match labels  */
    private val regexFilter: com.google.devtools.build.lib.util.RegexFilter

    /** The list of options to add when the filter matches a label  */
    @kotlin.jvm.JvmField
    private val optionsList: MutableList<String?>

    /**
     * Converts a String to a [PerLabelOptions] object. The syntax of the string is `regex_filter@option_1,option_2,...,option_n`. Where regex_filter stands for the String
     * representation of a [RegexFilter], and `option_1` to `option_n` stand for
     * arbitrary command line options. If an option contains a comma it has to be quoted with a
     * backslash. Options can contain @. Only the first @ is used to split the string.
     */
    open class PerLabelOptionsConverter : com.google.devtools.common.options.Converter.Contextless<PerLabelOptions?>() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String): PerLabelOptions? {
            val atIndex: Int = input.indexOf('@')
            val converter: RegexFilterConverter = RegexFilterConverter()
            if (atIndex < 0) {
                return PerLabelOptions(converter.convert(input), com.google.common.collect.ImmutableList.of<String?>())
            } else {
                val filterPiece: String = input.substring(0, atIndex)
                val optionsPiece: String = input.substring(atIndex + 1)
                val optionsList: MutableList<String?> = java.util.ArrayList<String?>()
                for (option in optionsPiece.split("(?<!\\\\),".toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray()) { // Split on ',' but not on '\,'
                    if (option != null && !option.trim { it <= ' ' }.isEmpty()) {
                        optionsList.add(option.replace("\\,", ","))
                    }
                }
                return PerLabelOptions(converter.convert(filterPiece), optionsList)
            }
        }

        override fun starlarkConvertible(): Boolean {
            return true
        }

        override fun reverseForStarlark(converted: Any?): String? {
            val typedValue = converted as PerLabelOptions
            return String.format(
                "%s@%s",
                typedValue.getRegexFilter().toOriginalString(),
                java.lang.String.join(",", typedValue.getOptions())
            )
        }

        override fun getTypeDescription(): String? {
            return ("a comma-separated list of regex expressions with prefix '-' specifying"
                    + " excluded paths followed by an @ and a comma separated list of options")
        }
    }

    init {
        this.regexFilter = regexFilter
        this.optionsList = optionsList
    }

    /**
     * @return true if the given label is matched by the [RegexFilter].
     */
    fun isIncluded(label: com.google.devtools.build.lib.cmdline.Label): Boolean {
        return regexFilter.isIncluded(label.toString())
    }

    /**
     * @return true if the execution path (which includes the base name of the file)
     * of the given file is matched by the [RegexFilter].
     */
    fun isIncluded(artifact: Artifact): Boolean {
        return regexFilter.isIncluded(artifact.getExecPathString())
    }

    /**
     * Returns the list of options to add to a command line.
     */
    fun getOptions(): MutableList<String?> {
        return optionsList
    }

    fun getRegexFilter(): com.google.devtools.build.lib.util.RegexFilter {
        return regexFilter
    }

    override fun toString(): String {
        return regexFilter.toString() + " Options: " + optionsList
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is PerLabelOptions) {
            return false
        }
        return this.regexFilter == other.regexFilter
                && this.optionsList == other.optionsList
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(regexFilter, optionsList)
    }
}
