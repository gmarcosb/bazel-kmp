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

import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe

/**
 * Instances of this class are multiplexers, which redirect multiple
 * output streams into a single output stream with tagging so it can be
 * de-multiplexed into multiple streams as needed. This allows us to
 * use one connection for multiple streams, but more importantly it avoids
 * multiple threads or select etc. on the receiving side: A client on the other
 * end of a networking connection can simply read the tagged lines and then act
 * on them within a sigle thread.
 * 
 * The format of the tagged output stream is reasonably simple:
 * 
 *  1. 
 * Marker byte indicating whether that chunk is for stdout (1), stderr (2) or the control
 * stream (3).
 * 
 *  1. 
 * 4 bytes indicating the length of the chunk in high-endian format.
 * 
 *  1. 
 * The payload (as many bytes as the length field before)
 * 
 * >
 * 
 * 
 */
@ThreadSafe
class StreamMultiplexer(multiplexed: java.io.OutputStream) {
    private val mutex = Any()
    private val multiplexed: java.io.OutputStream

    init {
        this.multiplexed = multiplexed
    }

    private inner class MarkingStream(private val markerByte: Byte) : LineFlushingOutputStream() {
        @Throws(IOException::class)
        override fun flushingHook() {
            synchronized(mutex) {
                if (len == 0) {
                    multiplexed.flush()
                    return
                }
                multiplexed.write(markerByte.toInt())
                multiplexed.write((len shr 24) and 0xff)
                multiplexed.write((len shr 16) and 0xff)
                multiplexed.write((len shr 8) and 0xff)
                multiplexed.write(len and 0xff)
                multiplexed.write(buffer, 0, len)
                multiplexed.flush()
            }
            len = 0
        }
    }

    /**
     * Create a stream that will tag its contributions into the multiplexed stream
     * with the marker '1', which means 'stdout'. Each newline byte leads
     * to a forced automatic flush. Also, this stream never closes the underlying
     * stream it delegates to - calling its `close()` method is equivalent
     * to calling `flush`.
     */
    fun createStdout(): java.io.OutputStream {
        return MarkingStream(STDOUT_MARKER)
    }

    /**
     * Like [.createStdout], except it tags with the marker '2' to
     * indicate 'stderr'.
     */
    fun createStderr(): java.io.OutputStream {
        return MarkingStream(STDERR_MARKER)
    }

    /**
     * Like [.createStdout], except it tags with the marker '3' to
     * indicate control flow..
     */
    fun createControl(): java.io.OutputStream {
        return MarkingStream(CONTROL_MARKER)
    }

    companion object {
        const val STDOUT_MARKER: Byte = 1
        const val STDERR_MARKER: Byte = 2
        const val CONTROL_MARKER: Byte = 3
    }
}
