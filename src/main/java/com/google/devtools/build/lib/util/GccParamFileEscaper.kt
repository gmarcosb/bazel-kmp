// Copyright 2019 The Bazel Authors. All rights reserved.
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
 * Utility class to escape strings for use in param files for gcc or clang.
 * 
 * 
 * Gcc and Clang interpret the following characters specially: single quote ('), double quote
 * ("), backslash (\), space ( ), tab (\t), carriage return (\r), newline (\n), form feed (\f), and
 * vertical tab (\u000B). All can be escaped by prefixing the symbol with a backslash.
 */
@Immutable
class GccParamFileEscaper : com.google.common.escape.CharEscaper() {
    override fun escape(string: String): String {
        if (string.isEmpty()) {
            // Empty string is a special case: needs to be quoted to ensure that it
            // gets treated as a separate argument.
            return "''"
        } else {
            return super.escape(string)
        }
    }

    public override fun escape(c: Char): CharArray? {
        if (!UNSAFECHAR_MATCHER.matches(c)) {
            return null
        } else {
            val result = CharArray(2)
            result[0] = '\\'
            result[1] = c
            return result
        }
    }

    companion object {
        val INSTANCE: GccParamFileEscaper = GccParamFileEscaper()

        private val AS_FUNCTION: com.google.common.base.Function<String?, String?> = INSTANCE.asFunction()

        private val UNSAFECHAR_MATCHER: com.google.common.base.CharMatcher =
            com.google.common.base.CharMatcher.anyOf("'\"\\ \t\r\n\u000c\u000B").precomputed()

        @kotlin.jvm.JvmStatic
        fun escapeString(unescaped: String): String {
            return INSTANCE.escape(unescaped)
        }

        /**
         * Transforms the input `Iterable` of unescaped strings to an `Iterable` of escaped
         * ones. The escaping is done lazily.
         */
        fun escapeAll(unescaped: Iterable<out String?>): Iterable<String?> {
            return com.google.common.collect.Iterables.transform(unescaped, AS_FUNCTION)
        }
    }
}
