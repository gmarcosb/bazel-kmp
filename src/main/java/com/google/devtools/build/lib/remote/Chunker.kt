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
package com.google.devtools.build.lib.remote

import com.google.devtools.build.lib.remote.Chunker
import com.google.devtools.build.lib.remote.common.RemoteCacheClient
import com.google.devtools.build.lib.remote.zstd.ZstdCompressingInputStream
import com.google.protobuf.ByteString
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.PushbackInputStream

/**
 * Splits a data source into one or more [Chunk]s of at most `chunkSize` bytes.
 * 
 * 
 * After a data source has been fully consumed, that is until [.hasNext] returns `false`, the chunker closes the underlying data source (i.e. file) itself. However, in case of
 * error or when a data source does not get fully consumed, a user must call [.reset]
 * manually.
 * 
 * 
 * This class should not be extended - it's only non-final for testing.
 */
open class Chunker internal constructor(
    blob: com.google.devtools.build.lib.remote.common.RemoteCacheClient.Blob?,
    val uncompressedSize: Long,
    private val chunkSize: Int,
    val isCompressed: Boolean
) : java.lang.AutoCloseable {
    /** A piece of a byte[] blob.  */
    class Chunk private constructor(data: ByteString, offset: Long) {
        val offset: Long
        private val data: ByteString

        init {
            this.data = data
            this.offset = offset
        }

        fun getData(): ByteString {
            return data
        }

        override fun equals(o: Any?): Boolean {
            if (o === this) {
                return true
            }
            if (o !is Chunk) {
                return false
            }
            return o.offset == offset && o.data == data
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(offset, data)
        }
    }

    private val blob: com.google.devtools.build.lib.remote.common.RemoteCacheClient.Blob
    private val emptyChunk: Chunk

    @com.google.common.annotations.VisibleForTesting
    protected var data: ChunkerInputStream? = null
    var offset: Long = 0
        private set
    private var chunkCache: ByteArray?

    // Set to true on the first call to next(). This is so that the Chunker can open its data source
    // lazily on the first call to next(), as opposed to opening it in the constructor or on reset().
    private var initialized = false

    init {
        this.blob =
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.remote.common.RemoteCacheClient.Blob>(
                blob
            )
        this.emptyChunk = com.google.devtools.build.lib.remote.Chunker.Chunk(ByteString.EMPTY, 0)
    }

    /**
     * Reset the [Chunker] state to when it was newly constructed.
     * 
     * 
     * Closes any open resources (file handles, ...).
     */
    @Throws(IOException::class)
    fun reset() {
        closeInput()
        offset = 0
        initialized = false
    }

    /**
     * Seek to an offset in the source stream.
     * 
     * 
     * May close and reopen resources in order to seek to an earlier offset.
     * 
     * @param toOffset the offset from beginning of the source stream. If the source stream is
     * compressed, it refers to the offset in the uncompressed form to align with `write_offset`
     * in REAPI.
     */
    @Throws(IOException::class)
    fun seek(toOffset: Long) {
        // For compressed stream, we need to reinitialize the stream since the offset refers to the
        // uncompressed form.
        if (initialized && uncompressedSize > 0 && toOffset >= offset && !this.isCompressed) {
            com.google.common.io.ByteStreams.skipFully(data, toOffset - offset)
            offset = toOffset
        } else {
            reset()
            initialize(toOffset)
        }
        if (uncompressedSize > 0 && data!!.finished()) {
            closeInput()
        }
    }

    /**
     * Returns `true` if a subsequent call to [.next] returns a [Chunk] object;
     */
    fun hasNext(): Boolean {
        return data != null || !initialized
    }

    /** Closes the input stream and reset chunk cache  */
    @Throws(IOException::class)
    private fun closeInput() {
        if (data != null) {
            data.close()
            data = null
        }
        chunkCache = null
    }

    @Throws(IOException::class)
    override fun close() {
        reset()
    }

    /** Attempts reading at most a full chunk and stores it in the chunkCache buffer  */
    @Throws(IOException::class)
    private fun read(): Int {
        var count = 0
        while (count < chunkCache!!.size) {
            val c: Int = data.read(chunkCache, count, chunkCache!!.size - count)
            if (c < 0) {
                break
            }
            count += c
        }
        return count
    }

    /**
     * Returns the next [Chunk] or throws a [NoSuchElementException] if no data is left.
     * 
     * 
     * Always call [.hasNext] before calling this method.
     * 
     * 
     * Zero byte inputs are treated special. Instead of throwing a [NoSuchElementException]
     * on the first call to [.next], a [Chunk] with an empty [ByteString] is
     * returned.
     */
    @Throws(IOException::class)
    fun next(): Chunk? {
        if (!hasNext()) {
            throw java.util.NoSuchElementException()
        }

        maybeInitialize()

        if (uncompressedSize == 0L) {
            closeInput()
            return emptyChunk
        }

        if (data!!.finished()) {
            chunkCache = null
            data = null
            throw java.util.NoSuchElementException()
        }

        if (chunkCache == null) {
            // If the output is compressed we can't know how many bytes there are yet to read,
            // so we allocate the whole chunkSize, otherwise we try to compute the smallest possible value
            // The cast to int is safe, because the return value is capped at chunkSize.
            val cacheSize = if (this.isCompressed) chunkSize else java.lang.Math.min(
                uncompressedSize - this.offset,
                chunkSize.toLong()
            ).toInt()
            // Lazily allocate it in order to save memory on small data.
            // 1) bytesToRead < chunkSize: There will only ever be one next() call.
            // 2) bytesToRead == chunkSize: chunkCache will be set to its biggest possible value.
            // 3) bytestoRead > chunkSize: Not possible, due to Math.min above.
            chunkCache = ByteArray(cacheSize)
        }

        val offsetBefore = offset

        val bytesRead = read()

        val blob: ByteString = ByteString.copyFrom(chunkCache, 0, bytesRead)

        // This has to happen after actualSize has been updated
        // or the guard in getActualSize won't work.
        offset += bytesRead.toLong()
        if (data!!.finished()) {
            closeInput()
        }

        return com.google.devtools.build.lib.remote.Chunker.Chunk(blob, offsetBefore)
    }

    @Throws(IOException::class)
    private fun maybeInitialize() {
        if (initialized) {
            return
        }
        initialize(0)
    }

    @Throws(IOException::class)
    private fun initialize(srcPos: Long) {
        com.google.common.base.Preconditions.checkState(!initialized)
        com.google.common.base.Preconditions.checkState(data == null)
        com.google.common.base.Preconditions.checkState(offset == 0L)
        com.google.common.base.Preconditions.checkState(chunkCache == null)
        try {
            val src: java.io.InputStream = blob.get()
            com.google.common.io.ByteStreams.skipFully(src, srcPos)
            data =
                if (this.isCompressed)
                    ChunkerInputStream(ZstdCompressingInputStream(src))
                else
                    ChunkerInputStream(src)
        } catch (e: java.lang.RuntimeException) {
            if (e.getCause() != null) {
                com.google.common.base.Throwables.throwIfInstanceOf<IOException?>(e.getCause(), IOException::class.java)
                com.google.common.base.Throwables.throwIfUnchecked(e.getCause())
            }
            throw e
        }
        offset = srcPos
        initialized = true
    }

    /** Builder class for the Chunker  */
    open class Builder {
        private var chunkSize = defaultChunkSize
        protected var size: Long = 0
        private var compressed = false
        protected var inputStream: com.google.devtools.build.lib.remote.common.RemoteCacheClient.Blob? = null

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setInput(size: Long, `in`: com.google.devtools.build.lib.remote.common.RemoteCacheClient.Blob?): Builder {
            com.google.common.base.Preconditions.checkState(inputStream == null)
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.remote.common.RemoteCacheClient.Blob?>(
                `in`
            )
            this.size = size
            inputStream = `in`
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @com.google.common.annotations.VisibleForTesting
        open fun setInput(data: ByteArray): Builder? {
            com.google.common.base.Preconditions.checkState(inputStream == null)
            size = data.size.toLong()
            this.inputStream =
                com.google.devtools.build.lib.remote.common.RemoteCacheClient.Blob? { ByteArrayInputStream(data) }
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setCompressed(compressed: Boolean): Builder {
            this.compressed = compressed
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setChunkSize(chunkSize: Int): Builder {
            this.chunkSize = chunkSize
            return this
        }

        fun build(): Chunker {
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.remote.common.RemoteCacheClient.Blob?>(
                inputStream
            )
            return Chunker(inputStream, size, chunkSize, compressed)
        }
    }

    internal class ChunkerInputStream(`in`: java.io.InputStream?) : PushbackInputStream(`in`) {
        @Throws(IOException::class)
        fun finished(): Boolean {
            val c: Int = super.read()
            if (c == -1) {
                return true
            }
            super.unread(c)
            return false
        }
    }

    companion object {
        var defaultChunkSize: Int = 1024 * 16
            private set

        /** This method must only be called in tests!  */
        @com.google.common.annotations.VisibleForTesting
        fun setDefaultChunkSizeForTesting(value: Int) {
            defaultChunkSize = value
        }

        fun builder(): Builder {
            return com.google.devtools.build.lib.remote.Chunker.Builder()
        }
    }
}
