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

import com.google.devtools.build.lib.util.io.AnsiTerminal
import com.google.devtools.build.lib.util.io.AnsiTerminalWriter
import java.io.IOException

/**
 * Class providing the AnsiTerminalWriter interface from a terminal while additionally counting the
 * number of written lines.
 */
class LineCountingAnsiTerminalWriter(terminal: AnsiTerminal) : AnsiTerminalWriter {
    private val terminal: AnsiTerminal

    @get:Throws(IOException::class)
    var writtenLines: Int
        private set

    init {
        this.terminal = terminal
        this.writtenLines = 0
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    override fun append(text: String): AnsiTerminalWriter {
        terminal.writeString(text)
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    override fun newline(): AnsiTerminalWriter {
        terminal.writeString(java.lang.System.lineSeparator())
        this.writtenLines++
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    override fun okStatus(): AnsiTerminalWriter {
        terminal.textGreen()
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    override fun failStatus(): AnsiTerminalWriter {
        terminal.textRed()
        terminal.textBold()
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    override fun normal(): AnsiTerminalWriter {
        terminal.resetTerminal()
        return this
    }
}
