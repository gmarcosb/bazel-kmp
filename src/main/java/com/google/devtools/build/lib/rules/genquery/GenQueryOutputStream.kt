// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.genquery

import com.google.devtools.build.lib.util.Fingerprint
import com.google.protobuf.ByteString
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * [OutputStream] implementation optimized for [GenQuery] by (optionally) compressing
 * query results on the fly. Produces [GenQueryResult]s which are preferred for storing the
 * output of [GenQuery]'s underlying queries.
 * 
 * 
 * The produced [GenQueryResult]s can also be in gzipped compressed format if the genquery
 * definition explicitly sets `compressed_output` parameter to `True`.
 */
internal class GenQueryOutputStream(private val compressedOutputRequested: Boolean) : java.io.OutputStream() {
    /**
     * Encapsulates the output of a [GenQuery]'s query. CPU and memory overhead of individual
     * methods depends on the underlying content and settings.
     */
    internal interface GenQueryResult {
        @kotlin.jvm.JvmField
        @get:Throws(IOException::class)
        val bytes: ByteString?

        /**
         * Adds the query output to the supplied [Fingerprint]. Equivalent to `fingerprint.addBytes(genQueryResult.getBytes())`, but potentially more efficient.
         * 
         * 
         * A boolean indicating whether the query output is compressed or not is added to the
         * supplied [Fingerprint] first.
         */
        fun fingerprint(fingerprint: Fingerprint?)

        /**
         * Returns the size of the output. This must be a constant-time operation for all
         * implementations.
         */
        fun size(): Int

        /**
         * Writes the query output to the provided [OutputStream]. Equivalent to `genQueryResult.getBytes().writeTo(out)`, but potentially more efficient.
         */
        @Throws(IOException::class)
        fun writeTo(out: java.io.OutputStream?)
    }

    private var bytesWritten = 0
    private var outputWasCompressed = false
    private var closed = false
    private var bytesOut: ByteString.Output = ByteString.newOutput()
    private var out: java.io.OutputStream? = null

    init {
        if (compressedOutputRequested) {
            this.out = GZIPOutputStream(bytesOut, GZIP_BYTES_BUFFER)
            this.outputWasCompressed = true
        } else {
            this.out = bytesOut
            this.outputWasCompressed = false
        }
    }

    @Throws(IOException::class)
    override fun write(b: Int) {
        maybeStartCompression(1)
        out.write(b)
        bytesWritten += 1
    }

    @Throws(IOException::class)
    override fun write(bytes: ByteArray) {
        write(bytes, 0, bytes.size)
    }

    @Throws(IOException::class)
    override fun write(bytes: ByteArray?, off: Int, len: Int) {
        maybeStartCompression(len)
        out.write(bytes, off, len)
        bytesWritten += len
    }

    @Throws(IOException::class)
    override fun flush() {
        out.flush()
    }

    @Throws(IOException::class)
    override fun close() {
        out.close()
        closed = true
    }

    val result: GenQueryResult
        get() {
            com.google.common.base.Preconditions.checkState(closed, "Must be closed")
            return if (!outputWasCompressed || compressedOutputRequested)
                SimpleResult(bytesOut.toByteString())
            else
                CompressedResultWithDecompressedOutput(bytesOut.toByteString(), bytesWritten)
        }

    @Throws(IOException::class)
    private fun maybeStartCompression(additionalBytes: Int) {
        if (outputWasCompressed) {
            return
        }

        if (!compressedOutputRequested && bytesWritten + additionalBytes < COMPRESSION_THRESHOLD) {
            return
        }

        val compressedBytesOut: ByteString.Output = ByteString.newOutput()
        val gzipOut: GZIPOutputStream = GZIPOutputStream(compressedBytesOut, GZIP_BYTES_BUFFER)
        bytesOut.writeTo(gzipOut)
        bytesOut = compressedBytesOut
        out = gzipOut
        outputWasCompressed = true
    }

    /**
     * Used when input and output GenQuery result data are in the same format, so no decompression or
     * other data transformation is needed.
     */
    @com.google.common.annotations.VisibleForTesting
    internal class SimpleResult(data: ByteString) : GenQueryResult {
        private val data: ByteString

        init {
            this.data = data
        }

        override fun getBytes(): ByteString {
            return data
        }

        override fun size(): Int {
            return data.size()
        }

        override fun fingerprint(fingerprint: Fingerprint) {
            fingerprint.addBytes(data)
        }

        @Throws(IOException::class)
        override fun writeTo(out: java.io.OutputStream?) {
            data.writeTo(out)
        }
    }

    /** Used when input GenQuery result is in compressed format and output should be decompressed.  */
    @com.google.common.annotations.VisibleForTesting
    internal class CompressedResultWithDecompressedOutput(compressedData: ByteString, size: Int) : GenQueryResult {
        private val compressedData: ByteString
        private val size: Int

        init {
            this.compressedData = compressedData
            this.size = size
        }

        @Throws(IOException::class)
        override fun getBytes(): ByteString? {
            val out: ByteString.Output = ByteString.newOutput(size)
            GZIPInputStream(compressedData.newInput()).use { gzipIn ->
                com.google.common.io.ByteStreams.copy(gzipIn, out)
            }
            return out.toByteString()
        }

        override fun size(): Int {
            return size
        }

        @Throws(IOException::class)
        override fun writeTo(out: java.io.OutputStream) {
            GZIPInputStream(compressedData.newInput()).use { gzipIn ->
                com.google.common.io.ByteStreams.copy(gzipIn, out)
            }
        }

        override fun fingerprint(fingerprint: Fingerprint) {
            try {
                GZIPInputStream(compressedData.newInput()).use { gzipIn ->
                    val chunk = ByteArray(4092)
                    var bytesRead: Int
                    while ((gzipIn.read(chunk).also { bytesRead = it }) > 0) {
                        fingerprint.addBytes(chunk, 0, bytesRead)
                    }
                }
            } catch (e: IOException) {
                // Unexpected, everything should be in memory!
                throw java.lang.IllegalStateException("Unexpected IOException", e)
            }
        }
    }

    companion object {
        /**
         * When compression is enabled, the threshold at which the stream will switch to compressing
         * output. The value of this constant is arbitrary but effective.
         * 
         * 
         * If genquery definition explicitly sets `compressed_output` parameter to `True`,
         * the stream will be compressed regardless of whether its size reaches this threshold.
         */
        private val COMPRESSION_THRESHOLD = 1 shl 20

        private const val GZIP_BYTES_BUFFER = 8192
    }
}
