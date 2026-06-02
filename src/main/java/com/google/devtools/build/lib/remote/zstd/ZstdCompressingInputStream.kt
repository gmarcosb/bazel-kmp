// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.zstd

import com.github.luben.zstd.ZstdOutputStreamNoFinalizer
import com.google.common.base.Preconditions
import java.io.InputStream

/** A [FilterInputStream] that use zstd to compress the content.  */
class ZstdCompressingInputStream internal constructor(`in`: InputStream?, size: Int) : FilterInputStream(`in`) {
    private val pis: PipedInputStream
    private var zos: ZstdOutputStreamNoFinalizer?
    private val size: Int

    constructor(`in`: InputStream?) : this(`in`, 512)

    init {
        Preconditions.checkArgument(
            size >= MIN_BUFFER_SIZE,
            String.format("The buffer size must be at least %d bytes", MIN_BUFFER_SIZE)
        )
        this.size = size
        this.pis = PipedInputStream(size)
        this.zos = ZstdOutputStreamNoFinalizer(PipedOutputStream(pis))
    }

    @Throws(IOException::class)
    private fun reFill() {
        val buf = ByteArray(size)
        val len: Int = super.read(buf, 0, max(0, size - pis.available() - MIN_BUFFER_SIZE + 1))
        if (len == -1) {
            zos.close()
            zos = null
        } else {
            zos.write(buf, 0, len)
            zos.flush()
        }
    }

    @Throws(IOException::class)
    override fun read(): Int {
        if (pis.available() == 0) {
            if (zos == null) {
                return -1
            }
            reFill()
        }
        return pis.read()
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray): Int {
        return read(b, 0, b.size)
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray?, off: Int, len: Int): Int {
        var count = 0
        var n = if (len > 0) -1 else 0
        while (count < len && (pis.available() > 0 || zos != null)) {
            if (pis.available() == 0) {
                reFill()
            }
            n = pis.read(b, count + off, len - count)
            count += max(0, n)
        }
        return if (count > 0) count else n
    }

    @Throws(IOException::class)
    override fun close() {
        if (zos != null) {
            zos.close()
        }
        `in`.close()
    }

    companion object {
        // We want the buffer to be able to contain at least:
        //   - Magic number: 4 bytes
        //   - FrameHeader 14 bytes
        //   - Block Header: 3 bytes
        //   - First block byte
        // This guarantees that we can always compress at least
        // 1 byte and write it to the pipe without blocking.
        @kotlin.jvm.JvmField
        val MIN_BUFFER_SIZE: Int = 4 + 14 + 3 + 1
    }
}
