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
 * Wrap an [AnsiTerminalWriter] into one that breaks lines to use
 * at most a first given number of columns of the terminal. In this way,
 * all line breaks are predictable, even if we only have a lower bound
 * on the number of columns of the underlying terminal. To simplify copy
 * and paste of the terminal output, a continuation character is written
 * into the last usable column when we break a line. Additionally, newline
 * characters are translated into calls to the [AnsiTerminalWriter.newline]
 * method.
 */
class LineWrappingAnsiTerminalWriter @kotlin.jvm.JvmOverloads constructor(
    terminalWriter: AnsiTerminalWriter,
    width: Int,
    continuationCharacter: Char = '\\'
) : AnsiTerminalWriter {
    private val terminalWriter: AnsiTerminalWriter
    private val width: Int
    private val continuationCharacter: Char
    private var position: Int

    init {
        this.terminalWriter = terminalWriter
        this.width = width
        this.continuationCharacter = continuationCharacter
        this.position = 0
    }

    @Throws(IOException::class)
    private fun appendChar(c: Char) {
        if (c == '\n') {
            terminalWriter.newline()
            position = 0
        } else if (position + 1 < width) {
            terminalWriter.append(c.toString())
            position++
        } else {
            // The last usable character of the line was already been written,
            // hence we have to start a continuation before writing the symbol.
            terminalWriter.append(continuationCharacter.toString())
            terminalWriter.newline()
            terminalWriter.append(c.toString())
            position = 1
        }
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    override fun append(text: String): AnsiTerminalWriter {
        for (i in 0..<text.length) {
            appendChar(text.get(i))
        }
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    override fun newline(): AnsiTerminalWriter {
        terminalWriter.newline()
        position = 0
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    override fun okStatus(): AnsiTerminalWriter {
        terminalWriter.okStatus()
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    override fun failStatus(): AnsiTerminalWriter {
        terminalWriter.failStatus()
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    override fun normal(): AnsiTerminalWriter {
        terminalWriter.normal()
        return this
    }
}
