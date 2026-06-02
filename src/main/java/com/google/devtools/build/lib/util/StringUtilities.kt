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

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Various utility methods operating on strings.
 */
object StringUtilities {
    private val NEWLINE_JOINER: com.google.common.base.Joiner = com.google.common.base.Joiner.on('\n')

    private val CONTROL_CHAR_ESCAPER: com.google.common.escape.Escaper = com.google.common.escape.CharEscaperBuilder()
        .addEscape('\r', "\\r")
        .addEscapes(
            charArrayOf(
                0.toChar(),
                1.toChar(),
                2.toChar(),
                3.toChar(),
                4.toChar(),
                5.toChar(),
                6.toChar(),
                7.toChar(),
                8.toChar(),
                9.toChar(),
                10.toChar(),
                11.toChar(),
                12.toChar(),  /*13=\r*/
                14.toChar(),
                15.toChar(),
                16.toChar(),
                17.toChar(),
                18.toChar(),
                19.toChar(),
                20.toChar(),
                21.toChar(),
                22.toChar(),
                23.toChar(),
                24.toChar(),
                25.toChar(),
                26.toChar(),
                27.toChar(),
                28.toChar(),
                29.toChar(),
                30.toChar(),
                31.toChar(),
                127.toChar()
            ), "<?>"
        )
        .toEscaper()

    /**
     * Java doesn't have multiline string literals, so having to join a bunch
     * of lines is a very common problem. So, here's a static method that we
     * can static import in such situations.
     */
    @kotlin.jvm.JvmStatic
    fun joinLines(vararg lines: String?): String {
        return com.google.devtools.build.lib.util.StringUtilities.NEWLINE_JOINER.join(lines)
    }

    /**
     * A corollary to [.joinLines] for collections.
     */
    fun joinLines(lines: MutableCollection<String?>): String {
        return com.google.devtools.build.lib.util.StringUtilities.NEWLINE_JOINER.join(lines)
    }

    /**
     * Replaces all occurrences of 'literal' in 'input' with 'replacement'.
     * Like [String.replaceAll] but for literal Strings
     * instead of regular expression patterns.
     * 
     * @param input the input String
     * @param literal the literal String to replace in 'input'.
     * @param replacement the replacement String to replace 'literal' in 'input'.
     * @return the 'input' String with all occurrences of 'literal' replaced with
     * 'replacement'.
     */
    @kotlin.jvm.JvmStatic
    fun replaceAllLiteral(
        input: String, literal: String,
        replacement: String
    ): String? {
        val literalLength = literal.length
        if (literalLength == 0) {
            return input
        }
        val result: java.lang.StringBuilder = java.lang.StringBuilder(
            input.length + replacement.length
        )
        var start = 0
        var index = 0

        while ((input.indexOf(literal, start).also { index = it }) >= 0) {
            result.append(input, start, index)
            result.append(replacement)
            start = index + literalLength
        }
        result.append(input.substring(start))
        return result.toString()
    }

    // TODO(tjgq): Unify prettyPrintBytes and bytesCountToDisplayString.
    /**
     * Returns an easy-to-read string approximation of a number of bytes, e.g. "21MB". Note, these are
     * IEEE units, i.e. decimal not binary powers.
     */
    @kotlin.jvm.JvmStatic
    fun prettyPrintBytes(bytes: Long): String {
        if (bytes < 1E4) {  // up to 10KB
            return bytes.toString() + "B"
        } else if (bytes < 1E7) {  // up to 10MB
            return ((bytes / 1E3).toInt()).toString() + "KB"
        } else if (bytes < 1E11) {  // up to 100GB
            return ((bytes / 1E6).toInt()).toString() + "MB"
        } else {
            return ((bytes / 1E9).toInt()).toString() + "GB"
        }
    }

    private val UNITS: com.google.common.collect.ImmutableList<String?> =
        com.google.common.collect.ImmutableList.of<String?>("KiB", "MiB", "GiB", "TiB")

    // Format as single digit decimal number.
    private val BYTE_COUNT_FORMAT: DecimalFormat = DecimalFormat("0.0", DecimalFormatSymbols(Locale.US))

    /**
     * Converts the number of bytes to a human readable string, e.g. 1024 -> 1 KiB.
     * 
     * 
     * Negative numbers are not allowed.
     */
    @kotlin.jvm.JvmStatic
    fun bytesCountToDisplayString(bytes: Long): String? {
        com.google.common.base.Preconditions.checkArgument(bytes >= 0)

        if (bytes < 1024) {
            return bytes.toString() + " B"
        }

        var unitIndex = 0
        var value = bytes
        while ((unitIndex + 1) < com.google.devtools.build.lib.util.StringUtilities.UNITS.size && value >= (1 shl 20)) {
            value = value shr 10
            unitIndex++
        }

        return String.format(
            "%s %s",
            com.google.devtools.build.lib.util.StringUtilities.BYTE_COUNT_FORMAT.format(value / 1024.0),
            com.google.devtools.build.lib.util.StringUtilities.UNITS.get(unitIndex)
        )
    }

    /**
     * Replace control characters with visible strings.
     * @return the sanitized string.
     */
    @kotlin.jvm.JvmStatic
    fun sanitizeControlChars(message: String): String {
        return com.google.devtools.build.lib.util.StringUtilities.CONTROL_CHAR_ESCAPER.escape(message)
    }

    /** Capitalize the first character of a string, assuming ASCII charset.  */
    fun capitalize(s: String): String {
        return com.google.common.base.Ascii.toUpperCase(s.substring(0, 1)) + s.substring(1)
    }
}
