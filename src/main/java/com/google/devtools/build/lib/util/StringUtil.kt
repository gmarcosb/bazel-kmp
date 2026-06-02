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

import java.util.Locale

/** Various utility methods operating on strings.  */
object StringUtil {
    /**
     * IEEE-style threshold for using thousands separators. Numbers with 5+ digits (>= 10,000) get
     * comma formatting for readability.
     */
    private const val IEEE_THOUSANDS_SEPARATOR_THRESHOLD = 10000

    /**
     * Formats a count using IEEE-style thousands separators. Numbers >= 10,000 (5+ digits) are
     * formatted with commas; smaller numbers are returned as plain strings.
     * 
     * 
     * Examples:
     * 
     * 
     *  * 999 → "999"
     *  * 9999 → "9999"
     *  * 10000 → "10,000"
     *  * 12345 → "12,345"
     * 
     */
    fun formatCount(count: Long): String? {
        if (count >= com.google.devtools.build.lib.util.StringUtil.IEEE_THOUSANDS_SEPARATOR_THRESHOLD) {
            return String.format(Locale.ENGLISH, "%,d", count)
        }
        return count.toString()
    }

    /**
     * Creates a comma-separated list of words as in English with the given last-separator and quotes.
     * 
     * 
     * Example with lastSeparator="then", quote="'", oxfordComma=false: ["a", "b", "c"] → "'a', 'b'
     * then 'c'".
     */
    /**
     * Creates a comma-separated list of words as in English.
     * 
     * 
     * Examples:
     * 
     * 
     *  * ["a"] → "a"
     *  * ["a", "b"] → "a or b"
     *  * ["a", "b", "c"] → "a, b, or c"
     * 
     */
    /**
     * Creates a comma-separated list of words as in English with the given last-separator.
     * 
     * 
     * Example with lastSeparator="and": ["a", "b", "c"] → "a, b, and c".
     */
    @kotlin.jvm.JvmOverloads
    fun joinEnglishList(
        choices: Iterable<*>, lastSeparator: String? = "or", quote: String? = "", oxfordComma: Boolean = true
    ): String {
        val buf: java.lang.StringBuilder = java.lang.StringBuilder()
        var numChoicesSeen = 0
        val ii: MutableIterator<*> = choices.iterator()
        while (ii.hasNext()) {
            val choice = ii.next()
            if (buf.length > 0) {
                if (ii.hasNext() || (oxfordComma && numChoicesSeen >= 2)) {
                    buf.append(",")
                }
                if (!ii.hasNext()) {
                    buf.append(" ").append(lastSeparator)
                }
                buf.append(" ")
            }
            buf.append(quote).append(choice).append(quote)
            numChoicesSeen++
        }
        return if (buf.length == 0) "nothing" else buf.toString()
    }

    /**
     * Creates a comma-separated list of singe-quoted words as in English.
     * 
     * 
     * Examples:
     * 
     * 
     *  * ["a"] → "'a'""
     *  * ["a", "b"] → "'a' or 'b'"
     *  * ["a", "b", "c"] → "'a', 'b', or 'c'"
     * 
     */
    fun joinEnglishListSingleQuoted(choices: Iterable<*>): String {
        return com.google.devtools.build.lib.util.StringUtil.joinEnglishList(
            choices,
            "or",
            "'",  /* oxfordComma= */
            true
        )
    }

    /**
     * Lists items up to a given limit, then prints how many were omitted.
     */
    fun listItemsWithLimit(
        appendTo: java.lang.StringBuilder, limit: Int,
        items: MutableCollection<*>
    ): java.lang.StringBuilder {
        com.google.common.base.Preconditions.checkState(limit > 0)
        com.google.common.base.Joiner.on(", ")
            .appendTo(appendTo, com.google.common.collect.Iterables.limit(items, limit))
        if (items.size > limit) {
            appendTo.append(" ...(omitting ")
                .append(items.size - limit)
                .append(" more item(s))")
        }
        return appendTo
    }

    /**
     * Returns the ordinal representation of the number.
     */
    fun ordinal(number: Int): String {
        when (number) {
            1 -> return "1st"
            2 -> return "2nd"
            3 -> return "3rd"
            else -> return number.toString() + "th"
        }
    }
}
