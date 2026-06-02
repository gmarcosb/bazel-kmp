// Copyright 2024 The Bazel Authors. All rights reserved.
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

/** Utility class to escape strings for use in param files for windows lld-link.  */
object WindowsParamFileEscaper {
    /**
     * Escapes the @argument to be suitable for lld-link. Existing double-quotes are escaped, and
     * arguments that contain whitespace are surrounded in unescaped double-quotes.
     * 
     * @see [LLVM
     * Parser Implementation](https://github.com/llvm/llvm-project/blob/4bc3b3501ff994fb3504ed2b973342821a9c8cea/llvm/lib/Support/CommandLine.cpp.L916)
     */
    @kotlin.jvm.JvmStatic
    fun escapeString(argument: String): String {
        val needsSurroundingQuotes = containsWhitespace(argument)
        val out: java.lang.StringBuilder = java.lang.StringBuilder()
        if (needsSurroundingQuotes) {
            out.append("\"")
        }
        out.append(argument.replace("\"", "\\\""))
        if (needsSurroundingQuotes) {
            out.append("\"")
        }
        return out.toString()
    }

    private val WHITESPACE_CHARACTERS: com.google.common.collect.ImmutableList<CharSequence?> =
        com.google.common.collect.ImmutableList.of<CharSequence?>(" ", "\t", "\n", "\r")

    private fun containsWhitespace(argument: String): Boolean {
        return WHITESPACE_CHARACTERS.stream().anyMatch { s: CharSequence? -> argument.contains(s) }
    }

    /** Escapes each argument in @unescaped using WindowsParamFileEscaper::escapeString.  */
    fun escapeAll(unescaped: Iterable<out String?>): Iterable<String?> {
        return com.google.common.collect.Iterables.transform(
            unescaped,
            { obj: WindowsParamFileEscaper?, argument: String -> escapeString(argument) })
    }
}
