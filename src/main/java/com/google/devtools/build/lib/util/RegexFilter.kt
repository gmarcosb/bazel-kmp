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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable

/**
 * Handles options that specify list of included/excluded regex expressions. Validates whether
 * string is included in that filter.
 * 
 * 
 * String is considered to be included into the filter if it does not match any of the excluded
 * regex expressions and if it matches at least one included regex expression.
 */
@Immutable
class RegexFilter private constructor(
    inclusionPattern: java.util.regex.Pattern?,
    exclusionPattern: java.util.regex.Pattern?,
    originalInput: String?
) : java.util.function.Predicate<String?> {
    // Null inclusion or exclusion pattern means those patterns are not used.
    private val inclusionPattern: java.util.regex.Pattern?
    private val exclusionPattern: java.util.regex.Pattern?
    private val hashCode: Int

    private val originalInput: String?

    /**
     * Converts from a comma-separated list of regex expressions with optional -/+ prefix into the
     * RegexFilter. Commas prefixed with backslash are considered to be part of regex definition and
     * not a delimiter between separate regex expressions.
     * 
     * 
     * Order of expressions is not important. Empty entries are ignored. '-' marks an excluded
     * expression.
     */
    class RegexFilterConverter : com.google.devtools.common.options.Converter.Contextless<RegexFilter?>() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String): RegexFilter {
            if (input.startsWith("--")) {
                throw com.google.devtools.common.options.OptionsParsingException(
                    String.format(
                        "Failed to build filter: value looks like another flag (%s). Either"
                                + " escape the value with \"\\-\\-\", or pass an explicit value to the flag.",
                        input
                    )
                )
            }
            val inclusionList: MutableList<String?> = java.util.ArrayList<String?>()
            val exclusionList: MutableList<String?> = java.util.ArrayList<String?>()

            for (piece in input.split("(?<!\\\\),".toRegex()).dropLastWhile { it.isEmpty() }
                .toTypedArray()) { // Split on ',' but not on '\,'
                var piece: String = piece
                piece = piece.replace("\\,", ",")
                val isExcluded: Boolean = piece.startsWith("-")
                if (isExcluded || piece.startsWith("+")) {
                    piece = piece.substring(1)
                }
                if (piece.length > 0) {
                    (if (isExcluded) exclusionList else inclusionList).add(piece)
                }
            }

            try {
                return com.google.devtools.build.lib.util.RegexFilter(inclusionList, exclusionList, input)
            } catch (e: PatternSyntaxException) {
                throw com.google.devtools.common.options.OptionsParsingException(
                    "Failed to build valid regular expression: " + e.message
                )
            }
        }

        val typeDescription: String
            get() = ("a comma-separated list of regex expressions with prefix '-' specifying"
                    + " excluded paths")
    }

    /**
     * Constructor taking regexes directly.
     * 
     * 
     * Null `inclusionPattern` or `exclusionPattern` means that inclusion or exclusion
     * matching will not be applied, respectively.
     */
    init {
        this.inclusionPattern = inclusionPattern
        this.exclusionPattern = exclusionPattern
        this.originalInput = originalInput
        this.hashCode =
            java.util.Objects.hash(
                if (inclusionPattern == null) null else inclusionPattern.pattern(),
                if (exclusionPattern == null) null else exclusionPattern.pattern()
            )
    }

    private constructor(
        inclusions: MutableList<String?>,
        exclusions: MutableList<String?>,
        originalInput: String?
    ) : this(
        com.google.devtools.build.lib.util.RegexFilter.Companion.takeUnionOfRegexes(inclusions),
        com.google.devtools.build.lib.util.RegexFilter.Companion.takeUnionOfRegexes(exclusions),
        originalInput
    )

    /** Creates new RegexFilter using provided inclusion and exclusion path lists.  */
    constructor(inclusions: MutableList<String?>, exclusions: MutableList<String?>) : this(
        inclusions,
        exclusions,  /* originalInput= */
        null
    )

    /**
     * @return true iff given string is included (it does not match exclusion pattern (if any) and
     * matches inclusionPatter (if any)).
     */
    fun isIncluded(value: String?): Boolean {
        if (exclusionPattern != null && exclusionPattern.matcher(value).find()) {
            return false
        }
        if (inclusionPattern == null) {
            return true
        }
        return inclusionPattern.matcher(value).find()
    }

    override fun test(value: String?): Boolean {
        return isIncluded(value)
    }

    override fun toString(): String {
        val builder: java.lang.StringBuilder = java.lang.StringBuilder()
        if (inclusionPattern != null) {
            builder.append(inclusionPattern.pattern().replace(",", "\\,"))
            if (exclusionPattern != null) {
                builder.append(",")
            }
        }
        if (exclusionPattern != null) {
            builder.append("-")
            builder.append(exclusionPattern.pattern().replace(",", "\\,"))
        }
        return builder.toString()
    }

    /**
     * RegexFilter doesn't serialize cleanly: `!RegexFilter.convert(".*").toString().equals(".")`.
     * 
     * 
     * This method provides the ability to reproduce the original input string.
     */
    fun toOriginalString(): String? {
        return originalInput
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is RegexFilter) {
            return false
        }

        if ((this.exclusionPattern == null) xor (other.exclusionPattern == null)) {
            return false
        }
        if ((this.inclusionPattern == null) xor (other.inclusionPattern == null)) {
            return false
        }
        if (this.exclusionPattern != null && this.exclusionPattern.pattern() != other.exclusionPattern.pattern()) {
            return false
        }
        if (this.inclusionPattern != null && this.inclusionPattern.pattern() != other.inclusionPattern.pattern()) {
            return false
        }
        return true
    }

    override fun hashCode(): Int {
        return hashCode
    }

    companion object {
        /**
         * Converts a list of regex expressions into a single regex representing its union or null when
         * the list is empty.
         */
        private fun takeUnionOfRegexes(regexList: MutableList<String?>): java.util.regex.Pattern? {
            if (regexList.isEmpty()) {
                return null
            }
            val deduped: TreeSet<String?> = TreeSet<String?>(regexList)
            // Wraps each individual regex into an independent group, then combines them using '|' and
            // wraps the result in a non-capturing group.
            return java.util.regex.Pattern.compile(
                "(?:(?>" + com.google.common.base.Joiner.on(")|(?>").join(deduped) + "))"
            )
        }
    }
}
