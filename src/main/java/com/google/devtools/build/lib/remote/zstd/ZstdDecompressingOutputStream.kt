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

import com.github.luben.zstd.ZstdInputStreamNoFinalizer
import java.io.InputStream
import java.io.OutputStream

/** An [OutputStream] that use zstd to decompress the content.  */
class ZstdDecompressingOutputStream(private val out: OutputStream) : OutputStream() {
    private var inner: ByteArrayInputStream? = null
    private val zis: ZstdInputStreamNoFinalizer

    init {
        zis =
            ZstdInputStreamNoFinalizer(
                object : InputStream() {
                    override fun read(): Int {
                        return inner.read()
                    }

                    override fun read(b: ByteArray?, off: Int, len: Int): Int {
                        return inner.read(b, off, len)
                    }
                })
                .setContinuous(true)
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
    override fun write(b: ByteArray, off: Int, len: Int) {
        inner = ByteArrayInputStream(b, off, len)
        zis.transferTo(out)
    }

    @Throws(IOException::class)
    override fun close() {
        closeShallow()
        out.close()
    }

    /**
     * Free resources related to decompression without closing the underlying [OutputStream].
     */
    @Throws(IOException::class)
    fun closeShallow() {
        zis.close()
    }
}
