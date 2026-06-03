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
import java.util.HashMap

/**
 * Utility class for dealing with escaping XML content and attributes.
 */
object XmlEscapers {
    private val MIN_ASCII_CONTROL_CHAR = 0x00.toChar()
    private val MAX_ASCII_CONTROL_CHAR = 0x1F.toChar()

    fun xmlContentEscaper(): com.google.testing.junit.runner.util.CharEscaper {
        return com.google.testing.junit.runner.util.XmlEscapers.XML_CONTENT_ESCAPER
    }

    fun xmlAttributeEscaper(): com.google.testing.junit.runner.util.CharEscaper {
        return com.google.testing.junit.runner.util.XmlEscapers.XML_ATTRIBUTE_ESCAPER
    }

    private val XML_CONTENT_ESCAPER: com.google.testing.junit.runner.util.CharEscaper
    private val XML_ATTRIBUTE_ESCAPER: com.google.testing.junit.runner.util.CharEscaper

    init {
        val builder: Builder = com.google.testing.junit.runner.util.XmlEscapers.Builder.Companion.builder()
        builder.setSafeRange(java.lang.Character.MIN_VALUE, '\uFFFD')
        builder.setUnsafeReplacement("\uFFFD")

        var c: Char = com.google.testing.junit.runner.util.XmlEscapers.MIN_ASCII_CONTROL_CHAR
        while (c <= com.google.testing.junit.runner.util.XmlEscapers.MAX_ASCII_CONTROL_CHAR) {
            if (c != '\t' && c != '\n' && c != '\r') {
                builder.addEscape(c, "\uFFFD")
            }
            c++
        }

        builder.addEscape('&', "&amp;")
        builder.addEscape('<', "&lt;")
        builder.addEscape('>', "&gt;")
        com.google.testing.junit.runner.util.XmlEscapers.XML_CONTENT_ESCAPER = builder.build()
        builder.addEscape('\'', "&apos;")
        builder.addEscape('"', "&quot;")
        builder.addEscape('\t', "&#x9;")
        builder.addEscape('\n', "&#xA;")
        builder.addEscape('\r', "&#xD;")
        com.google.testing.junit.runner.util.XmlEscapers.XML_ATTRIBUTE_ESCAPER = builder.build()
    }

    /**
     * A builder for CharEscaper.
     */
    internal class Builder  // The constructor is exposed via the builder() method above.
    private constructor() {
        private val replacementMap: MutableMap<Char?, String?> = HashMap<Char?, String?>()
        private var safeMin: Char = java.lang.Character.MIN_VALUE
        private var safeMax: Char = java.lang.Character.MAX_VALUE
        private var unsafeReplacement: String? = null

        /**
         * Sets the safe range of characters for the escaper. Characters in this range that have no
         * explicit replacement are considered 'safe' and remain unescaped in the output. If `safeMax < safeMin` then the safe range is empty.
         * 
         * @return the builder instance
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setSafeRange(safeMin: Char, safeMax: Char): Builder {
            this.safeMin = safeMin
            this.safeMax = safeMax
            return this
        }

        /**
         * Sets the replacement string for any characters outside the 'safe' range that have no explicit
         * replacement. If `unsafeReplacement` is `null` then no replacement will occur, if
         * it is `""` then the unsafe characters are removed from the output.
         * 
         * @return the builder instance
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setUnsafeReplacement(unsafeReplacement: String?): Builder {
            this.unsafeReplacement = unsafeReplacement
            return this
        }

        /**
         * Adds a replacement string for the given input character. The specified character will be
         * replaced by the given string whenever it occurs in the input, irrespective of whether it lies
         * inside or outside the 'safe' range.
         * 
         * @return the builder instance
         * @throws NullPointerException if `replacement` is null
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addEscape(c: Char, replacement: String): Builder {
            if (replacement == null) {
                throw java.lang.NullPointerException()
            }
            // This can replace an existing character (the builder is re-usable).
            replacementMap.put(c, replacement)
            return this
        }

        /**
         * Returns a new CharEscaper based on the current state of the builder.
         */
        fun build(): com.google.testing.junit.runner.util.CharEscaper {
            return object : com.google.testing.junit.runner.util.CharEscaper(replacementMap, safeMin, safeMax) {
                private val replacementChars: CharArray? =
                    if (unsafeReplacement != null) unsafeReplacement.toCharArray() else null

                override fun escapeUnsafe(c: Char): CharArray? {
                    return replacementChars
                }
            }
        }

        companion object {
            fun builder(): Builder {
                return com.google.testing.junit.runner.util.XmlEscapers.Builder()
            }
        }
    }
}

