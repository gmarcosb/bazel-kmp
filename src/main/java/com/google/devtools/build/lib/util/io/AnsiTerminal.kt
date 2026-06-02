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

import com.google.devtools.build.lib.util.io.AnsiTerminal
import java.io.IOException

/**
 * A class which encapsulates the fancy curses-type stuff that you can do using
 * standard ANSI terminal control sequences.
 */
class AnsiTerminal(out: java.io.OutputStream) {
    /**
     * An enumeration of all terminal colors, containing the escape sequences for both background and
     * foreground settings.
     */
    enum class Color(escapeSeq: String, backgroundEscapeSeq: String) {
        RED("^[31m", "^[41m"),
        GREEN("^[32m", "^[42m"),
        YELLOW("^[33m", "^[43m"),
        BLUE("^[34m", "^[44m"),
        MAGENTA("^[35m", "^[45m"),
        CYAN("^[36m", "^[46m"),
        GRAY("^[37m", "^[47m"),

        DEFAULT("^[0m", "^[0m");

        private val escapeSeq: ByteArray
        private val backgroundEscapeSeq: ByteArray

        init {
            this.escapeSeq = escapeSeq.replace('^', 27.toChar()).toByteArray(java.nio.charset.StandardCharsets.US_ASCII)
            this.backgroundEscapeSeq =
                backgroundEscapeSeq.replace('^', 27.toChar()).toByteArray(java.nio.charset.StandardCharsets.US_ASCII)
        }

        fun getEscapeSeq(): ByteArray? {
            return escapeSeq.clone()
        }

        fun getBackgroundEscapeSeq(): ByteArray? {
            return backgroundEscapeSeq.clone()
        }
    }

    private val out: java.io.OutputStream

    /**
     * Creates an AnsiTerminal object wrapping an output stream which is going to
     * be displayed in an ANSI compatible terminal or shell window.
     * 
     * @param out the output stream
     */
    init {
        this.out = out
    }

    /**
     * Moves the cursor upwards by a specified number of lines. This will not
     * cause any scrolling if it tries to move above the top of the terminal
     * window.
     */
    @Throws(IOException::class)
    fun cursorUp(numLines: Int) {
        writeBytes(ESC, ("" + numLines).toByteArray(), byteArrayOf(UP))
    }

    /**
     * Clear the current terminal line from the cursor position to the end.
     */
    @Throws(IOException::class)
    fun clearLine() {
        writeEscapeSequence(ERASE_LINE)
    }

    /**
     * Makes any text output to the terminal appear in bold.
     */
    @Throws(IOException::class)
    fun textBold() {
        writeEscapeSequence(TEXT_BOLD, SET_GRAPHICS)
    }

    /**
     * Set the color of the foreground or background of the terminal.
     * 
     * @param color one of the foreground or background color constants
     */
    @Throws(IOException::class)
    fun setTextColor(color: Color) {
        writeBytes(color.escapeSeq)
    }

    /**
     * Resets the terminal colors and fonts to defaults.
     */
    @Throws(IOException::class)
    fun resetTerminal() {
        writeEscapeSequence('0'.code.toByte(), 'm'.code.toByte())
    }

    /**
     * Makes text print on the terminal in red.
     */
    @Throws(IOException::class)
    fun textRed() {
        setTextColor(com.google.devtools.build.lib.util.io.AnsiTerminal.Color.RED)
    }

    /**
     * Makes text print on the terminal in green.
     */
    @Throws(IOException::class)
    fun textGreen() {
        setTextColor(com.google.devtools.build.lib.util.io.AnsiTerminal.Color.GREEN)
    }

    /**
     * Makes text print on the terminal in magenta.
     */
    @Throws(IOException::class)
    fun textMagenta() {
        setTextColor(com.google.devtools.build.lib.util.io.AnsiTerminal.Color.MAGENTA)
    }

    /**
     * Set the terminal title.
     */
    @Throws(IOException::class)
    fun setTitle(title: String) {
        writeBytes(SET_TERM_TITLE, title.toByteArray(), byteArrayOf(BEL))
    }

    /**
     * Writes a string to the terminal using the current font, color and cursor
     * position settings.
     * 
     * @param text the text to write
     */
    @Throws(IOException::class)
    fun writeString(text: String) {
        out.write(text.toByteArray())
    }

    /**
     * Writes a byte sequence to the terminal using the current font, color and cursor position
     * settings.
     * 
     * @param bytes the bytes to write
     */
    @Throws(IOException::class)
    fun writeBytes(bytes: ByteArray?) {
        out.write(bytes)
    }

    /**
     * Utility method for generating control sequences. Takes a collection of byte arrays, which
     * contain the components of a control sequence, concatenates them, and prints them to the
     * terminal.
     * 
     * @param stuff the byte arrays that make up the sequence to be sent to the terminal
     */
    @Throws(IOException::class)
    private fun writeBytes(vararg stuff: ByteArray) {
        for (bytes in stuff) {
            out.write(bytes)
        }
    }

    /**
     * Utility method which makes it easier to generate the control sequences for the terminal.
     * 
     * @param bytes bytes which should be prefixed with the terminal escape sequence to produce a
     * valid control sequence
     */
    @Throws(IOException::class)
    private fun writeEscapeSequence(vararg bytes: Byte) {
        writeBytes(ESC, bytes)
    }

    /** Sends a carriage return to the terminal.  */
    @Throws(IOException::class)
    fun cr() {
        writeBytes(CR)
    }

    /**
     * Flushes the underlying stream. This class does not do any buffering of its own, but the
     * underlying OutputStream may.
     */
    @Throws(IOException::class)
    fun flush() {
        out.flush()
    }

    companion object {
        private val ESC = byteArrayOf(27, '['.code.toByte())
        private const val BEL: Byte = 7
        private val UP: Byte = 'A'.code.toByte()
        private val ERASE_LINE: Byte = 'K'.code.toByte()
        private val SET_GRAPHICS: Byte = 'm'.code.toByte()
        private val TEXT_BOLD: Byte = '1'.code.toByte()
        private val SET_TERM_TITLE = byteArrayOf(27, ']'.code.toByte(), '0'.code.toByte(), ';'.code.toByte())

        var CR: ByteArray = byteArrayOf(13)
    }
}
