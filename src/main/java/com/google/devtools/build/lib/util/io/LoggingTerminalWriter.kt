// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util.io

import com.google.devtools.build.lib.util.io.AnsiTerminalWriter
import java.io.IOException

/**
 * An [AnsiTerminalWriter] that just generates a transcript of the events it was exposed of.
 */
class LoggingTerminalWriter @kotlin.jvm.JvmOverloads constructor(private val discardHighlight: Boolean = false) :
    AnsiTerminalWriter {
    var transcript: String? = ""
        private set

    /** Clears the stored transcript; mostly useful for testing purposes.  */
    fun reset() {
        transcript = ""
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    override fun append(text: String?): AnsiTerminalWriter {
        transcript += text
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    override fun newline(): AnsiTerminalWriter {
        if (!discardHighlight) {
            transcript += NEWLINE
        } else {
            transcript += "\n"
        }
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    override fun okStatus(): AnsiTerminalWriter {
        if (!discardHighlight) {
            transcript += OK
        }
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    override fun failStatus(): AnsiTerminalWriter {
        if (!discardHighlight) {
            transcript += FAIL
        }
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    override fun normal(): AnsiTerminalWriter {
        if (!discardHighlight) {
            transcript += NORMAL
        }
        return this
    }

    companion object {
        // Strings for recording the non-append calls
        const val NEWLINE: String = "[NL]"
        const val OK: String = "[OK]"
        const val FAIL: String = "[FAIL]"
        const val NORMAL: String = "[NORMAL]"
    }
}
