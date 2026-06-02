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

import java.io.IOException

/**
 * This stream maintains a buffer, which it flushes upon encountering bytes
 * that might be new line characters. This stream implements [.close]
 * as [.flush].
 */
internal abstract class LineFlushingOutputStream : java.io.OutputStream() {
    /**
     * The buffer containing the characters that have not been flushed yet.
     */
    protected val buffer: ByteArray = ByteArray(BUFFER_LENGTH)

    /**
     * The length of the buffer that's actually used.
     */
    protected var len: Int = 0

    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    override fun write(b: ByteArray, off: Int, inlen: Int) {
        var off = off
        var inlen = inlen
        if (len == BUFFER_LENGTH) {
            flush()
        }
        var charsInLine = 0
        while (inlen > charsInLine) {
            val sawNewline = (b[off + charsInLine] == NEWLINE)
            charsInLine++
            if (sawNewline || len + charsInLine == BUFFER_LENGTH) {
                java.lang.System.arraycopy(b, off, buffer, len, charsInLine)
                len += charsInLine
                off += charsInLine
                inlen -= charsInLine
                flush()
                charsInLine = 0
            }
        }
        java.lang.System.arraycopy(b, off, buffer, len, charsInLine)
        len += charsInLine
    }

    @Throws(IOException::class)
    override fun write(byteAsInt: Int) {
        val b = byteAsInt.toByte() // make sure we work with bytes in comparisons
        write(byteArrayOf(b), 0, 1)
    }

    /**
     * Close is implemented as [.flush]. Client code must close the
     * underlying output stream itself in case that's desired.
     */
    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    override fun close() {
        flush()
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    override fun flush() {
        flushingHook() // The point of using a hook is to make it synchronized.
    }

    /**
     * The implementing class must define this method, which must at least flush
     * the bytes in `buffer[0] - buffer[len - 1]`, and reset `len=0`.
     * 
     * Don't forget to synchronized the implementation of this method on whatever
     * underlying object it writes to!
     */
    @Throws(IOException::class)
    protected abstract fun flushingHook()

    companion object {
        const val BUFFER_LENGTH: Int = 8192
        protected var NEWLINE: Byte = '\n'.code.toByte()
    }
}
