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
package com.google.devtools.build.lib.util.io

import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible

/**
 * Allows to print "colored" strings by parsing predefined string keywords,
 * which, depending on the useColor value are either replaced with ANSI terminal
 * coloring sequences (as defined by the [AnsiTerminal] class) or stripped.
 * 
 * Supported keywords are defined by the enum [AnsiTerminalPrinter.Mode].
 * Following keywords are supported:
 * INFO  - switches color to green.
 * ERROR - switches color to bold red.
 * WARNING - switches color to magenta.
 * NORMAL - resets terminal to the default state.
 * 
 * Each keyword is starts with prefix "{#" followed by the enum constant name
 * and suffix "#}". Keywords should not be inserted manually - provided enum
 * constants should be used instead.
 */
@ThreadCompatible
class AnsiTerminalPrinter(out: java.io.OutputStream, private var useColor: Boolean) {
    /**
     * List of supported coloring modes for the [AnsiTerminalPrinter].
     */
    enum class Mode {
        INFO,  // green
        ERROR,  // bold red
        WARNING,  // magenta
        DEFAULT; // default color

        override fun toString(): String {
            return MODE_PREFIX + name + MODE_SUFFIX
        }
    }

    private val stream: java.io.OutputStream
    private val writer: PrintWriter
    private val terminal: AnsiTerminal
    private var lastMode: Mode? = com.google.devtools.build.lib.util.io.AnsiTerminalPrinter.Mode.DEFAULT

    /**
     * Creates new instance using provided OutputStream and sets coloring logic
     * for that instance.
     */
    init {
        terminal = AnsiTerminal(out)
        writer = PrintWriter(out, true)
        stream = out
    }

    /**
     * Writes the specified string to the output stream while injecting coloring
     * sequences when appropriate mode keyword is found and flushes.
     * 
     * List of supported mode keywords is defined by the enum [Mode].
     * 
     * See class documentation for details.
     */
    fun print(str: String?) {
        for (part in PATTERN.split(str)) {
            var part: String = part
            val index: Int = part.indexOf(MODE_SUFFIX)
            // Mode name will contain at least one character, so suffix index
            // must be at least 1. If it isn't then there is no match.
            if (index > 1) {
                for (mode in MODES) {
                    if (index == mode.name.length && part.startsWith(mode.name)) {
                        setupTerminal(mode)
                        part = part.substring(index + MODE_SUFFIX.length)
                        break
                    }
                }
            }
            writer.print(part)
            writer.flush()
        }
    }

    fun printLn(str: String?) {
        print(str + "\n")
    }

    val outputStream: java.io.OutputStream
        /**
         * Returns the underlying OutputStream.
         */
        get() = stream

    /**
     * Injects coloring escape sequences if output should be colored and mode
     * has been changed.
     */
    private fun setupTerminal(mode: Mode?) {
        if (!useColor) {
            return
        }
        try {
            if (lastMode != mode) {
                terminal.resetTerminal()
                lastMode = mode
                if (mode == com.google.devtools.build.lib.util.io.AnsiTerminalPrinter.Mode.DEFAULT) {
                    return  // Terminal is already reset - nothing else to do.
                } else if (mode == com.google.devtools.build.lib.util.io.AnsiTerminalPrinter.Mode.INFO) {
                    terminal.textGreen()
                } else if (mode == com.google.devtools.build.lib.util.io.AnsiTerminalPrinter.Mode.ERROR) {
                    terminal.textRed()
                    terminal.textBold()
                } else if (mode == com.google.devtools.build.lib.util.io.AnsiTerminalPrinter.Mode.WARNING) {
                    terminal.textMagenta()
                }
            }
        } catch (e: IOException) {
            // AnsiTerminal state is now considered to be inconsistent - coloring
            // should be disabled to prevent future use of AnsiTerminal instance.
            logger.atWarning().withCause(e).log("Disabling coloring due to exception")
            useColor = false
        }
    }

    companion object {
        private const val MODE_PREFIX = "{#"
        private const val MODE_SUFFIX = "#}"

        // Mode pattern must match MODE_PREFIX and do lookahead for the rest of the
        // mode string.
        private const val MODE_PATTERN = "\\{\\#(?=[A-Z]+\\#\\})"

        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
        private val MODES: EnumSet<Mode> =
            EnumSet.allOf<Mode?>(com.google.devtools.build.lib.util.io.AnsiTerminalPrinter.Mode::class.java)
        private val PATTERN: java.util.regex.Pattern = java.util.regex.Pattern.compile(MODE_PATTERN)
    }
}
