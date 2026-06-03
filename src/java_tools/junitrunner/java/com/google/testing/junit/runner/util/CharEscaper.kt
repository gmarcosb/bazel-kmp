// Copyright 2016 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.junit.runner.util

import com.google.testing.junit.runner.junit4.JUnit4Bazel.runner
import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder.get
import java.util.Collections

/**
 * An escaper that uses an array to quickly look up replacement characters for a given `char`
 * value. An additional safe range is provided that determines whether `char` values without
 * specific replacements are to be considered safe and left unescaped or should be escaped in a
 * general way.
 */
abstract class CharEscaper(
    replacementMap: MutableMap<Char?, String?>, // The first character in the safe range.
    private val safeMin: Char, // The last character in the safe range.
    private val safeMax: Char
) {
    // The replacement array.
    private val replacements: Array<CharArray?>

    // The number of elements in the replacement array.
    private val replacementsLength: Int

    fun escape(s: String): String {
        if (s == null) {
            throw java.lang.NullPointerException()
        }
        for (i in 0..<s.length) {
            val c = s.get(i)
            if ((c.code < replacementsLength && replacements[c.code] != null) || c > safeMax || c < safeMin) {
                return escapeSlow(s, i)
            }
        }
        return s
    }

    fun escape(c: Char): CharArray? {
        if (c.code < replacementsLength) {
            val chars = replacements[c.code]
            if (chars != null) {
                return chars
            }
        }
        if (c >= safeMin && c <= safeMax) {
            return null
        }
        return escapeUnsafe(c)
    }

    init {
        this.replacements =
            com.google.testing.junit.runner.util.CharEscaper.Companion.createReplacementArray(replacementMap)
        this.replacementsLength = replacements.size
    }

    /**
     * Returns the escaped form of a given literal string, starting at the given index. This method is
     * called by the [.escape] method when it discovers that escaping is required.
     * 
     * @param s the literal string to be escaped
     * @param index the index to start escaping from
     * @return the escaped form of `string`
     * @throws NullPointerException if `string` is null
     */
    fun escapeSlow(s: String, index: Int): String {
        var index = index
        val slen = s.length

        // Get a destination buffer and setup some loop variables.
        var dest: CharArray = com.google.testing.junit.runner.util.CharEscaper.Companion.DEST_TL.get()
        var destSize = dest.size
        var destIndex = 0
        var lastEscape = 0

        // Loop through the rest of the string, replacing when needed into the
        // destination buffer, which gets grown as needed as well.
        while (index < slen) {
            // Get a replacement for the current character.
            val r = escape(s.get(index))

            // If no replacement is needed, just continue.
            if (r == null) {
                index++
                continue
            }

            val rlen = r.size
            val charsSkipped = index - lastEscape

            // This is the size needed to add the replacement, not the full size
            // needed by the string. We only regrow when we absolutely must, and
            // when we do grow, grow enough to avoid excessive growing. Grow.
            val sizeNeeded = destIndex + charsSkipped + rlen
            if (destSize < sizeNeeded) {
                destSize =
                    sizeNeeded + com.google.testing.junit.runner.util.CharEscaper.Companion.DEST_PAD_MULTIPLIER * (slen - index)
                dest = com.google.testing.junit.runner.util.CharEscaper.Companion.growBuffer(dest, destIndex, destSize)
            }

            // If we have skipped any characters, we need to copy them now.
            if (charsSkipped > 0) {
                s.toCharArray(dest, destIndex, lastEscape, index)
                destIndex += charsSkipped
            }

            // Copy the replacement string into the dest buffer as needed.
            if (rlen > 0) {
                java.lang.System.arraycopy(r, 0, dest, destIndex, rlen)
                destIndex += rlen
            }
            lastEscape = index + 1
            index++
        }

        // Copy leftover characters if there are any.
        val charsLeft = slen - lastEscape
        if (charsLeft > 0) {
            val sizeNeeded = destIndex + charsLeft
            if (destSize < sizeNeeded) {
                // Regrow and copy, expensive! No padding as this is the final copy.

                dest =
                    com.google.testing.junit.runner.util.CharEscaper.Companion.growBuffer(dest, destIndex, sizeNeeded)
            }
            s.toCharArray(dest, destIndex, lastEscape, slen)
            destIndex = sizeNeeded
        }
        return String(dest, 0, destIndex)
    }

    abstract fun escapeUnsafe(c: Char): CharArray?

    companion object {
        // The multiplier for padding to use when growing the escape buffer.
        private const val DEST_PAD_MULTIPLIER = 2

        /**
         * A thread-local destination buffer to keep us from creating new buffers. The starting size is
         * 1024 characters.
         */
        private val DEST_TL: java.lang.ThreadLocal<CharArray> = object : java.lang.ThreadLocal<CharArray?>() {
            override fun initialValue(): CharArray {
                return CharArray(1024)
            }
        }

        /**
         * Helper method to grow the character buffer as needed, this only happens once in a while so it's
         * ok if it's in a method call. If the index passed in is 0 then no copying will be done.
         */
        private fun growBuffer(dest: CharArray?, index: Int, size: Int): CharArray {
            val copy = CharArray(size)
            if (index > 0) {
                java.lang.System.arraycopy(dest, 0, copy, 0, index)
            }
            return copy
        }

        private fun createReplacementArray(map: MutableMap<Char?, String?>): Array<CharArray?> {
            if (map == null) {
                throw java.lang.NullPointerException()
            }
            if (map.isEmpty()) {
                return Array<CharArray?>(0) { CharArray(0) }
            }
            val max: Char = Collections.max<Char?>(map.keys)
            val replacements = arrayOfNulls<CharArray>(max.code + 1)
            for (c in map.keys) {
                replacements[c.code] = map.get(c).toCharArray()
            }
            return replacements
        }
    }
}

