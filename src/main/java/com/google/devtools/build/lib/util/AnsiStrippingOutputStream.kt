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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.util.AnsiStrippingOutputStream
import java.io.IOException

/**
 * A pass-thru [OutputStream] that strips ANSI control codes.
 */
class AnsiStrippingOutputStream(output: java.io.OutputStream) : java.io.OutputStream() {
    // The idea is straightforward: the regexp for ANSI control codes is
    // \x1b\[[;0-9]*[a-zA-Z] . Implementing it as a stream is a little ugly,
    // though.
    private enum class State {
        NORMAL,
        AFTER_ESCAPE,
        PARAMETER,
    }

    private var outputBuffer: ByteArray?
    private var outputBufferPos = 0

    private val escapeCodeBuffer: ByteArray
    private var escapeCodeBufferPos: Int
    private val output: java.io.OutputStream
    private var state: State

    init {
        this.output = output
        escapeCodeBuffer = ByteArray(ESCAPE_BUFFER_LENGTH)
        escapeCodeBufferPos = 0
        state = com.google.devtools.build.lib.util.AnsiStrippingOutputStream.State.NORMAL
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    override fun write(b: Int) {
        // As per the contract of OutputStream.write(int)
        val array = byteArrayOf((b and 0xff).toByte())
        write(array, 0, 1)
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    override fun write(b: ByteArray, off: Int, len: Int) {
        var i = 0
        if (state == com.google.devtools.build.lib.util.AnsiStrippingOutputStream.State.NORMAL) {
            // Avoid outputBuffer allocation entirely if that's possible

            while ((i < len) && (b[off + i].toInt() != 0x1b)) {
                i++
            }
            if (i == len) {
                output.write(b, off, len)
                return
            }
        }

        // In the worst case, the contents of the escape buffer and the contents
        // of the input buffer are both copied to the output, so the length of the
        // output buffer should be the sum of the length of both these buffers.
        outputBuffer = ByteArray(len + ESCAPE_BUFFER_LENGTH)
        java.lang.System.arraycopy(b, off, outputBuffer, 0, i)
        outputBufferPos = i

        while (i < len) {
            processByte(b[off + i])
            i++
        }

        try {
            output.write(outputBuffer, 0, outputBufferPos)
        } finally {
            outputBuffer = null // Make it possible to garbage collect the array
        }
    }

    private fun processByte(b: Byte) {
        when (state) {
            com.google.devtools.build.lib.util.AnsiStrippingOutputStream.State.NORMAL -> {
                check(escapeCodeBufferPos == 0)
                if (b.toInt() == 0x1b) {
                    state = com.google.devtools.build.lib.util.AnsiStrippingOutputStream.State.AFTER_ESCAPE
                    addByteToEscapeBuffer(b)
                } else {
                    dumpByte(b)
                }
            }

            com.google.devtools.build.lib.util.AnsiStrippingOutputStream.State.AFTER_ESCAPE -> if (b == '['.code.toByte()) {
                state = com.google.devtools.build.lib.util.AnsiStrippingOutputStream.State.PARAMETER
                addByteToEscapeBuffer(b)
            } else if (b.toInt() == 0x1b) {
                dumpEscapeBuffer()
                state = com.google.devtools.build.lib.util.AnsiStrippingOutputStream.State.AFTER_ESCAPE
                addByteToEscapeBuffer(b)
            } else {
                dumpEscapeBuffer()
                dumpByte(b)
                state = com.google.devtools.build.lib.util.AnsiStrippingOutputStream.State.NORMAL
            }

            com.google.devtools.build.lib.util.AnsiStrippingOutputStream.State.PARAMETER -> if ((b >= '0'.code.toByte() && b <= '9'.code.toByte()) || b == ';'.code.toByte()) {
                // Parameter continues
                addByteToEscapeBuffer(b)
            } else if ((b >= 'a'.code.toByte() && b <= 'z'.code.toByte()) || (b >= 'A'.code.toByte() && b <= 'Z'.code.toByte())) {
                // Found a control sequence, discard it and revert to normal state
                discardEscapeBuffer()
                state = com.google.devtools.build.lib.util.AnsiStrippingOutputStream.State.NORMAL
            } else if (b.toInt() == 0x1b) {
                // Another escape sequence begins immediately after, and this is
                // an illegal escape sequence
                dumpEscapeBuffer()
                state = com.google.devtools.build.lib.util.AnsiStrippingOutputStream.State.AFTER_ESCAPE
                addByteToEscapeBuffer(b)
            } else {
                // Illegal control sequence, output it
                dumpEscapeBuffer()
                state = com.google.devtools.build.lib.util.AnsiStrippingOutputStream.State.NORMAL
            }
        }
    }

    private fun addByteToEscapeBuffer(b: Byte) {
        escapeCodeBuffer[escapeCodeBufferPos++] = b
        if (escapeCodeBufferPos == ESCAPE_BUFFER_LENGTH) {
            // Buffer full. Assume that no sane code emits an ANSI control code this
            // long and revert to normal state.
            dumpEscapeBuffer()
            state = com.google.devtools.build.lib.util.AnsiStrippingOutputStream.State.NORMAL
        }
    }

    private fun discardEscapeBuffer() {
        escapeCodeBufferPos = 0
    }

    private fun dumpByte(b: Byte) {
        outputBuffer!![outputBufferPos++] = b
    }

    private fun dumpEscapeBuffer() {
        java.lang.System.arraycopy(
            escapeCodeBuffer, 0,
            outputBuffer, outputBufferPos, escapeCodeBufferPos
        )
        outputBufferPos += escapeCodeBufferPos
        escapeCodeBufferPos = 0
    }

    @Throws(IOException::class)
    override fun flush() {
        output.flush()
    }

    @Throws(IOException::class)
    override fun close() {
        output.close()
    }

    companion object {
        private const val ESCAPE_BUFFER_LENGTH = 128
    }
}
