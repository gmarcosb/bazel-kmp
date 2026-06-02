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
package com.google.devtools.build.lib.runtime

import java.io.IOException

/**
 * A decorator output stream that does line buffering.
 */
class LineBufferedOutputStream @kotlin.jvm.JvmOverloads constructor(
    wrapped: java.io.OutputStream,
    bufferSize: Int = DEFAULT_BUFFER_SIZE
) : java.io.OutputStream() {
    private val wrapped: java.io.OutputStream
    private val buffer: ByteArray
    private var pos: Int

    init {
        this.wrapped = wrapped
        this.buffer = ByteArray(bufferSize)
        this.pos = 0
    }

    @Throws(IOException::class)
    private fun flushBuffer() {
        val oldPos = pos
        // Set pos to zero first so that if the write below throws, we are still in a consistent state.
        pos = 0
        wrapped.write(buffer, 0, oldPos)
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    override fun write(b: ByteArray, off: Int, inlen: Int) {
        if (inlen > buffer.size * 2) {
            // Do not buffer large writes
            if (pos > 0) {
                flushBuffer()
            }
            wrapped.write(b, off, inlen)
            return
        }

        var next = off
        while (next < off + inlen) {
            buffer[pos++] = b[next]
            if (b[next] == '\n'.code.toByte() || pos == buffer.size) {
                flushBuffer()
            }

            next++
        }
    }

    @Throws(IOException::class)
    override fun write(byteAsInt: Int) {
        val b = byteAsInt.toByte() // make sure we work with bytes in comparisons
        write(byteArrayOf(b), 0, 1)
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    override fun flush() {
        if (pos != 0) {
            wrapped.write(buffer, 0, pos)
            pos = 0
        }
        wrapped.flush()
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    override fun close() {
        flush()
        wrapped.close()
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 1024
    }
}
