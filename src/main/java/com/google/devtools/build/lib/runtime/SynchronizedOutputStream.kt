// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.query2.engine.QueryEvalResult.isEmpty
import java.io.IOException

/**
 * [OutputStream] suitably synchronized for producer-consumer use cases. The method [ ][.readAndReset] allows to read the bytes accumulated so far and simultaneously truncate
 * precisely the bytes read. Moreover, upon such a reset the amount of memory retained is reset to a
 * small constant. This is a difference with resecpt to the behaviour of the standard classes [ ] which only resets the index but keeps the array. This difference matters,
 * as we need to support output peeks without retaining this amount of memory for the rest of the
 * build.
 * 
 * 
 * This class is expected to be used with the [BuildEventStreamer].
 */
class SynchronizedOutputStream(maxBufferedLength: Int, maxChunkSize: Int, isStderr: Boolean) : java.io.OutputStream() {
    // The maximal amount of bytes we intend to store in the buffer. However,
    // the requirement that a single write be written in one go is more important,
    // so the actual size we store in this buffer can be the maximum (not the sum)
    // of this value and the amount of bytes written in a single call to the
    // {@link write(byte[] buffer, int offset, int count)} method.
    private val maxBufferedLength: Int

    private val maxChunkSizeSplitter: com.google.common.base.Splitter

    private val isStderr: Boolean

    @javax.annotation.concurrent.GuardedBy("this")
    private var buf: ByteArray

    private var count: Long

    // The event streamer that is supposed to flush stdout/stderr.
    private var streamer: BuildEventStreamer? = null

    init {
        com.google.common.base.Preconditions.checkArgument(maxChunkSize > 0)
        buf = ByteArray(64)
        count = 0
        this.maxBufferedLength = maxBufferedLength
        this.maxChunkSizeSplitter =
            com.google.common.base.Splitter.fixedLength(java.lang.Math.max(maxChunkSize, maxBufferedLength))
        this.isStderr = isStderr
    }

    fun registerStreamer(streamer: BuildEventStreamer?) {
        this.streamer = streamer
    }

    /**
     * Read the contents of the stream and simultaneously clear them. Also, reset the amount of memory
     * retained to a constant amount.
     */
    @kotlin.jvm.Synchronized
    fun readAndReset(): Iterable<String?> {
        val content = String(buf, 0, count.toInt(), java.nio.charset.StandardCharsets.UTF_8)
        buf = ByteArray(64)
        count = 0
        return if (content.isEmpty()) com.google.common.collect.ImmutableList.of<String?>() else maxChunkSizeSplitter.split(
            content
        )
    }

    @Throws(IOException::class)
    override fun write(oneByte: Int) {
        // We change the dependency with respect to that of the super class: write(int)
        // now calls write(int[], int, int) which is implemented without any dependencies.
        write(byteArrayOf(oneByte.toByte()), 0, 1)
    }

    @Throws(IOException::class)
    override fun write(buffer: ByteArray?, offset: Int, count: Int) {
        // As we base the less common write(int) on this method, we may not depend not call write(int)
        // directly or indirectly (e.g., by calling super.write(int[], int, int)).
        var shouldFlush = false
        // As we have to do the flushing outside the synchronized block, we have to expect
        // other writes to come immediately after flushing, so we have to do the check inside
        // a while loop.
        var didWrite = false
        while (!didWrite) {
            synchronized(this) {
                if ((this.count + count.toLong() < maxBufferedLength || this.count == 0L)
                    && streamer.canBufferProgressWrite(isStderr)
                ) {
                    if (this.count + count.toLong() >= buf.size.toLong()) {
                        // We need to increase the buffer; if within the permissible range range for array
                        // sizes, we at least double it, otherwise we only increase as far as needed.
                        val newsize: Long
                        if (2 * buf.size.toLong() + count < java.lang.Integer.MAX_VALUE.toLong()) {
                            newsize = 2 * buf.size.toLong() + count
                        } else {
                            newsize = this.count + count
                        }
                        val newbuf = ByteArray(newsize.toInt())
                        java.lang.System.arraycopy(buf, 0, newbuf, 0, this.count.toInt())
                        this.buf = newbuf
                    }
                    java.lang.System.arraycopy(buffer, offset, buf, this.count.toInt(), count)
                    this.count += count.toLong()
                    didWrite = true
                } else {
                    shouldFlush = true
                }
                if (this.count >= maxBufferedLength) {
                    shouldFlush = true
                }
            }
            if (shouldFlush && streamer != null) {
                streamer.flush()
                shouldFlush = false
            }
        }
    }
}
