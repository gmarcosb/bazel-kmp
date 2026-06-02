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
package com.google.devtools.build.lib.events

import com.google.devtools.build.lib.util.StringEncoding

/** An output filter for warnings.  */
interface OutputFilter {
    /** Returns true iff the given tag matches the output filter.  */
    fun showOutput(tag: String?): Boolean

    /** An output filter using regular expression matching.  */
    class RegexOutputFilter private constructor(pattern: java.util.regex.Pattern) : OutputFilter {
        private val pattern: java.util.regex.Pattern

        init {
            this.pattern = pattern
        }

        override fun showOutput(tag: String?): Boolean {
            return pattern.matcher(StringEncoding.internalToUnicode(tag)).find()
        }

        override fun toString(): String {
            return pattern.toString()
        }

        companion object {
            /** Returns an output filter for the given regex (by compiling it).  */
            @kotlin.jvm.JvmStatic
            fun forRegex(regex: String?): OutputFilter {
                return com.google.devtools.build.lib.events.OutputFilter.RegexOutputFilter(
                    java.util.regex.Pattern.compile(
                        regex
                    )
                )
            }

            /** Returns an output filter for the given pattern.  */
            fun forPattern(pattern: java.util.regex.Pattern): OutputFilter {
                return com.google.devtools.build.lib.events.OutputFilter.RegexOutputFilter(pattern)
            }
        }
    }

    companion object {
        /** An output filter that matches everything.  */
        @kotlin.jvm.JvmField
        val OUTPUT_EVERYTHING: OutputFilter = com.google.devtools.build.lib.events.OutputFilter { tag: String? -> true }

        /** An output filter that matches nothing.  */
        @kotlin.jvm.JvmField
        val OUTPUT_NOTHING: OutputFilter = com.google.devtools.build.lib.events.OutputFilter { tag: String? -> false }
    }
}
