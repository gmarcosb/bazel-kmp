// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.profiler.output

import java.io.PrintStream

/**
 * Utility function for writing text data to a [PrintStream].
 */
abstract class TextPrinter protected constructor(out: PrintStream) {
    protected val out: PrintStream

    init {
        this.out = out
    }

    protected fun printLn() {
        out.println()
    }

    /** newline and print the Object  */
    protected fun lnPrint(text: Any?) {
        out.println()
        out.print(text)
    }

    /** newline and print the formatted text  */
    protected fun lnPrintf(format: String?, vararg args: Any?) {
        out.println()
        out.printf(format, *args)
    }

    companion object {
        protected const val THREE_COLUMN_FORMAT: String = "%-49s %10s %8s"

        /**
         * Represents a double value as either "N/A" if it is NaN, or as a percentage with "%.2f%%".
         * @param relativeValue is assumed to be a ratio of two values and will be multiplied with 100
         * for output
         */
        fun prettyPercentage(relativeValue: Double): String? {
            if (java.lang.Double.isNaN(relativeValue)) {
                return "N/A"
            }
            return java.lang.String.format("%.2f%%", relativeValue * 100)
        }
    }
}

