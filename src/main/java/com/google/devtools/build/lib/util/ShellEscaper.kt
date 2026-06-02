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
 * Utility class to escape strings for use with shell commands.
 * 
 * 
 * Escaped strings may safely be inserted into shell commands. Escaping is
 * only done if necessary. Strings containing only shell-neutral characters
 * will not be escaped.
 * 
 * 
 * This is a replacement for `ShellUtils.shellEscape(String)` and
 * `ShellUtils.prettyPrintArgv(java.util.List)` (see
 * [com.google.devtools.build.lib.shell.ShellUtils]). Its advantage is the use
 * of standard building blocks from the `com.google.common.base`
 * package, such as [Joiner] and [CharMatcher], making this class
 * more efficient and reliable than `ShellUtils`.
 * 
 * 
 * The behavior is slightly different though: this implementation will
 * defensively escape non-ASCII letters and digits, whereas
 * `shellEscape` does not.
 */
@Immutable
class ShellEscaper private constructor() : com.google.common.escape.Escaper() {
    /**
     * Escapes a string by adding strong (single) quotes around it if necessary.
     * 
     * 
     * A string is not escaped iff it only contains safe characters.
     * The following characters are safe:
     * 
     *  * ASCII letters and digits: [a-zA-Z0-9]
     *  * shell-neutral characters: at symbol (@), percent symbol (%),
     * dash/minus sign (-), underscore (_), plus sign (+), colon (:),
     * comma(,), period (.) and slash (/).
     * 
     * 
     * 
     * A string is escaped iff it contains at least one non-safe character.
     * Escaped strings are created by replacing every occurrence of single
     * quotes with the string '\'' and enclosing the result in a pair of
     * single quotes.
     * 
     * 
     * Examples:
     * 
     *  * "`foo`" becomes "`foo`" (remains the same)
     *  * "`+bar`" becomes "`+bar`" (remains the same)
     *  * "" becomes "{@code''}" (empty string becomes a pair of strong quotes)
     *  * "`$BAZ`" becomes "`'$BAZ'`"
     *  * "`quote'd`" becomes "`'quote'\''d'`"
     * 
     */
    override fun escape(unescaped: String): String {
        val s = unescaped.toString()
        if (s.isEmpty()) {
            // Empty string is a special case: needs to be quoted to ensure that it
            // gets treated as a separate argument.
            return "''"
        } else {
            if (SAFECHAR_MATCHER.matchesAllOf(s)) {
                return s
            }
            if (SAFECHAR_MATCHER_WITH_TILDE.matchesAllOf(s) && s.get(0) != '~') {
                return s
            }
            return "'" + STRONGQUOTE_ESCAPER.escape(s) + "'"
        }
    }

    companion object {
        // Note: extending Escaper may seem desirable, but is in fact harmful.
        // The class would then need to implement escape(Appendable), returning an Appendable
        // that escapes everything it receives. In case of shell escaping, we most often join
        // string parts on spaces, using a Joiner. Spaces are escaped characters. Using the
        // Appendable returned by escape(Appendable) would escape these spaces too, which
        // is unwanted.
        val INSTANCE: ShellEscaper = ShellEscaper()

        private val AS_FUNCTION: com.google.common.base.Function<String?, String?> = INSTANCE.asFunction()

        private val SPACE_JOINER: com.google.common.base.Joiner = com.google.common.base.Joiner.on(' ')
        private val STRONGQUOTE_ESCAPER: com.google.common.escape.Escaper =
            com.google.common.escape.CharEscaperBuilder().addEscape('\'', "'\\''").toEscaper()
        private val SAFECHAR_MATCHER: com.google.common.base.CharMatcher =
            com.google.common.base.CharMatcher.anyOf("@%-_+:,./")
                .or(
                    com.google.common.base.CharMatcher.inRange(
                        '0',
                        '9'
                    )
                ) // We can't use CharMatcher.javaLetterOrDigit(),
                .or(com.google.common.base.CharMatcher.inRange('a', 'z')) // that would also accept non-ASCII digits and
                .or(com.google.common.base.CharMatcher.inRange('A', 'Z')) // letters.
                .precomputed()
        private val SAFECHAR_MATCHER_WITH_TILDE: com.google.common.base.CharMatcher =
            SAFECHAR_MATCHER.or(com.google.common.base.CharMatcher.`is`('~')).precomputed()

        @kotlin.jvm.JvmStatic
        fun escapeString(unescaped: String): String {
            return INSTANCE.escape(unescaped)
        }

        /**
         * Transforms the input `Iterable` of unescaped strings to an
         * `Iterable` of escaped ones. The escaping is done lazily.
         */
        fun escapeAll(unescaped: Iterable<out String?>): Iterable<String?> {
            return com.google.common.collect.Iterables.transform(unescaped, AS_FUNCTION)
        }

        /**
         * Escapes all strings in `argv` individually and joins them on single spaces into `out`. The result is appended directly into `out`, without adding a separator.
         * 
         * 
         * This method works as if by invoking [.escapeJoinAll]
         * with `Joiner.on(' ')`.
         * 
         * @param out what the result will be appended to
         * @param argv the strings to escape and join
         * @return the same reference as `out`, now containing the joined, escaped fragments
         * @throws IOException if an I/O error occurs while appending
         */
        @Throws(IOException::class)
        fun escapeJoinAll(out: java.lang.Appendable?, argv: Iterable<out String?>): java.lang.Appendable {
            return SPACE_JOINER.appendTo<java.lang.Appendable>(out, escapeAll(argv))
        }

        /**
         * Escapes all strings in `argv` individually and joins them into `out` using the
         * specified [Joiner]. The result is appended directly into `out`, without adding a
         * separator.
         * 
         * 
         * The resulting strings are the same as if escaped one by one using [ ][.escapeString].
         * 
         * 
         * Example: if the joiner is `Joiner.on('|')`, then the input `["abc", "de'f"]`
         * will be escaped as "`abc|'de'\''f'`". If `out` initially contains "`123`",
         * then the returned `Appendable` will contain "`123abc|'de'\''f'`".
         * 
         * @param out what the result will be appended to
         * @param argv the strings to escape and join
         * @param joiner the [Joiner] to use to join the escaped strings
         * @return the same reference as `out`, now containing the joined, escaped fragments
         * @throws IOException if an I/O error occurs while appending
         */
        @Throws(IOException::class)
        fun escapeJoinAll(
            out: java.lang.Appendable?, argv: Iterable<out String?>, joiner: com.google.common.base.Joiner
        ): java.lang.Appendable {
            return joiner.appendTo<java.lang.Appendable>(out, escapeAll(argv))
        }

        /**
         * Escapes all strings in `argv` individually and joins them on
         * single spaces, then returns the resulting string.
         * 
         * 
         * This method works as if by invoking
         * [.escapeJoinAll] with `Joiner.on(' ')`.
         * 
         * 
         * Example: `["abc", "de'f"]` will be escaped and joined as
         * "abc 'de'\''f'".
         * 
         * @param argv the strings to escape and join
         * @return the string of escaped and joined input elements
         */
        fun escapeJoinAll(argv: Iterable<out String?>): String {
            return SPACE_JOINER.join(escapeAll(argv))
        }

        /**
         * Escapes all strings in `argv` individually and joins them using
         * the specified [Joiner], then returns the resulting string.
         * 
         * 
         * The resulting strings are the same as if escaped one by one using
         * [.escapeString].
         * 
         * 
         * Example: if the joiner is `Joiner.on('|')`, then the input
         * `["abc", "de'f"]` will be escaped and joined as "abc|'de'\''f'".
         * 
         * @param argv the strings to escape and join
         * @param joiner the [Joiner] to use to join the escaped strings
         * @return the string of escaped and joined input elements
         */
        fun escapeJoinAll(argv: Iterable<out String?>, joiner: com.google.common.base.Joiner): String {
            return joiner.join(escapeAll(argv))
        }
    }
}
