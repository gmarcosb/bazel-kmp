// Copyright 2025 The Bazel Authors. All rights reserved.
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
 * A buffered output stream that only flushes its buffer at record boundaries.
 * 
 * 
 * The [.finishRecord] method marks the current position as the end of a complete record.
 * Whenever a flush occurs (either explicitly via [.flush] or implicitly via [.write] or
 * [.close]), the internal buffer is only flushed up to the last recorded position, with any
 * following bytes remaining in the internal buffer. The internal buffer starts at 4KB but grows to
 * accommodate the largest record seen so far.
 * 
 * 
 * This is intended as a best-effort attempt to prevent incomplete records from being written to
 * disk in the event of an abrupt exit. It isn't completely safe since partial underlying writes are
 * still possible, but experiments suggest that they're very unlikely for small buffer sizes.
 */
class RecordOutputStream(out: java.io.OutputStream) : java.io.OutputStream() {
    private val out: java.io.OutputStream
    private var buf = ByteArray(4096)
    private var writeOff = 0
    private var flushOff = 0

    init {
        this.out = out
    }

    /** Marks the current position as the end of a complete record.  */
    fun finishRecord() {
        flushOff = writeOff
    }

    @Throws(IOException::class)
    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    @Throws(IOException::class)
    override fun write(b: ByteArray) {
        write(b, 0, b.size)
    }

    @Throws(IOException::class)
    override fun write(b: ByteArray?, off: Int, len: Int) {
        if (len > buf.size - writeOff) {
            // First try to make space by flushing.
            flush()
            if (len > buf.size - writeOff) {
                // If the buffer is too small to fit a single record, grow it to the next power of two.
                buf = buf.copyOf(com.google.common.math.IntMath.ceilingPowerOfTwo(writeOff + len))
            }
        }
        java.lang.System.arraycopy(b, off, buf, writeOff, len)
        writeOff += len
    }

    @Throws(IOException::class)
    override fun flush() {
        if (flushOff > 0) {
            out.write(buf, 0, flushOff)
            // TODO(tjgq): Consider using a ring buffer to avoid this copy.
            java.lang.System.arraycopy(buf, flushOff, buf, 0, writeOff - flushOff)
            writeOff -= flushOff
            flushOff = 0
        }
    }

    @Throws(IOException::class)
    override fun close() {
        flush()
        out.close()
    }
}
