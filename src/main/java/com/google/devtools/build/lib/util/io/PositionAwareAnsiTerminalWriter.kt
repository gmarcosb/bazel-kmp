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
 * Wrap an [AnsiTerminalWriter] into one that is aware of the position
 * within the current line. Newline characters, which presumably are supposed
 * to end a line, are translated into calls to the [AnsiTerminalWriter.newline]
 * method.
 */
class PositionAwareAnsiTerminalWriter(terminalWriter: AnsiTerminalWriter) : AnsiTerminalWriter {
    private val terminalWriter: AnsiTerminalWriter
    var position: Int
        private set

    init {
        this.terminalWriter = terminalWriter
        this.position = 0
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    override fun append(text: String): AnsiTerminalWriter {
        var i = 0
        while (i < text.length) {
            val next: Int = text.indexOf('\n', i)
            if (next == -1) {
                terminalWriter.append(text.substring(i))
                position += text.length - i
                i = text.length
            } else {
                terminalWriter.append(text.substring(i, next))
                terminalWriter.newline()
                i = next + 1
                position = 0
            }
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
