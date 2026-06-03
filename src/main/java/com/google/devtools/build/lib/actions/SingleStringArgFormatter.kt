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
package com.google.devtools.build.lib.actions

/**
 * Implementation of a formatter that supports only a single '%s'
 * 
 * 
 * This implementation is used in command line item expansions that use formatting. We use a
 * custom implementation to improve performance and avoid GC.
 */
object SingleStringArgFormatter {
    /**
     * Returns true if the format string is a valid single-arg formatter.
     * 
     * 
     * Requirements are:
     * 
     * 
     *  * Contains exactly one '%s'.
     *  * Each occurrence of '%' is either '%s' or '%%' (escape sequence).
     * 
     */
    fun isValid(formatStr: String): Boolean {
        return formattedLengthOrInvalid(formatStr) != -1
    }

    /**
     * Calculates the format specifier's contribution to the length of a string created by calling
     * [.format], without actually applying any formatting.
     * 
     * 
     * For a typical format specifier with no escape characters, returns `formatStr.length() - 2`, since the `%s` gets replaced during formatting. The result may differ if the format
     * specifier contains escape characters.
     * 
     * 
     * For all valid format specifiers, the following holds:
     * 
     * <pre>`format(formatStr, subject).length() == formatSpecifierLength(formatStr) + subject.length() `</pre>
     * 
     * @throws IllegalArgumentException if the format string is invalid.
     */
    fun formattedLength(formatStr: String): Int {
        val length = formattedLengthOrInvalid(formatStr)
        if (length == -1) {
            throw invalidFormatString(formatStr)
        }
        return length
    }

    /** Returns the formatted length or `-1` if invalid.  */
    private fun formattedLengthOrInvalid(formatStr: String): Int {
        var length = 0
        val n: Int = formatStr.length()
        var idx = 0
        var found = false

        while (idx < n) {
            val next: Int = formatStr.indexOf('%'.code, idx)
            if (next == -1) {
                length += n - idx
                break
            }
            if (next == n - 1) {
                return -1 // Terminating '%'.
            }
            when (formatStr.charAt(next + 1)) {
                's' -> {
                    if (found) {
                        return -1 // Multiple '%s'.
                    }
                    length += next - idx
                    found = true
                }

                '%' -> length += next + 1 - idx
                else -> {
                    return -1 // Illegal sequence.
                }
            }
            idx = next + 2
        }

        return if (found) length else -1
    }

    /**
     * Returns the equivalent result of `String.format(formatStr, subject)`, under the
     * assumption that the format string contains a single %s.
     * 
     * 
     * Use [.isValid] to validate the format string.
     * 
     * @throws IllegalArgumentException if the format string is invalid.
     */
    @kotlin.jvm.JvmStatic
    fun format(formatStr: String, subject: String): String {
        val sb: java.lang.StringBuilder = java.lang.StringBuilder(formatStr.length() + subject.length() - 2)
        val n: Int = formatStr.length()
        var idx = 0
        var found = false

        while (idx < n) {
            val next: Int = formatStr.indexOf('%'.code, idx)
            if (next == -1) {
                sb.append(formatStr, idx, n)
                break
            }
            if (next == n - 1) {
                throw invalidFormatString(formatStr) // Terminating '%'.
            }
            when (formatStr.charAt(next + 1)) {
                's' -> {
                    if (found) {
                        throw invalidFormatString(formatStr) // Multiple '%s'.
                    }
                    sb.append(formatStr, idx, next).append(subject)
                    found = true
                }

                '%' -> sb.append(formatStr, idx, next + 1)
                else -> throw invalidFormatString(formatStr) // Illegal sequence.
            }
            idx = next + 2
        }

        if (!found) {
            throw invalidFormatString(formatStr) // No '%s'.
        }
        return sb.toString()
    }

    private fun invalidFormatString(formatStr: String?): java.lang.IllegalArgumentException {
        return java.lang.IllegalArgumentException(
            "Expected format string with single '%s', found: " + formatStr
        )
    }
}
