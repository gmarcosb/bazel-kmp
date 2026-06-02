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
//
package com.google.devtools.build.lib.vfs.inmemoryfs

import com.google.common.base.Preconditions
import com.google.common.math.IntMath
import com.google.devtools.build.lib.clock.Clock
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.*
import javax.annotation.concurrent.GuardedBy

/**
 * InMemoryFileInfo manages file contents by storing them entirely in memory.
 */
@ThreadSafe
class InMemoryFileInfo internal constructor(clock: Clock?) : FileInfo(clock) {
    // A byte array storing the file contents, possibly with extra unused bytes at the end.
    @GuardedBy("this")
    private var content: ByteArray

    // The file size.
    @GuardedBy("this")
    private var size: Int

    init {
        // New files start out empty.
        content = ByteArray(MIN_SIZE)
        size = 0
    }

    @kotlin.jvm.Synchronized
    override fun getSize(): Long {
        return size.toLong()
    }

    override fun getxattr(name: String?): ByteArray? {
        return null
    }

    override fun getFastDigest(): ByteArray? {
        return null
    }

    override fun getInputStream(): InputStream {
        return Channels.newInputStream(
            InMemoryByteChannel( /* readable= */
                true,  /* writable= */
                false,  /* append= */
                false,  /* truncate= */
                false
            )
        )
    }

    override fun getOutputStream(append: Boolean): OutputStream {
        return Channels.newOutputStream(
            InMemoryByteChannel( /* readable= */
                false,  /* writable= */
                true,  /* append= */
                append,  /* truncate= */
                !append
            )
        )
    }

    override fun createReadWriteByteChannel(): SeekableByteChannel {
        return InMemoryByteChannel( /* readable= */
            true,  /* writable= */true,  /* append= */false,  /* truncate= */true
        )
    }

    /**
     * A [SeekableByteChannel] manipulating the contents of the parent [InMemoryFileInfo]
     * instance.
     * 
     * 
     * Supports concurrent operations, possibly through multiple channels.
     */
    private inner class InMemoryByteChannel(
        private val readable: Boolean,
        private val writable: Boolean,
        private val append: Boolean,
        truncate: Boolean
    ) : SeekableByteChannel {
        private var closed = false
        private var position = 0

        init {
            if (truncate) {
                synchronized(this@InMemoryFileInfo) {
                    size = 0
                }
            }
        }

        @Throws(IOException::class)
        fun ensureOpen() {
            if (closed) {
                throw ClosedChannelException()
            }
        }

        fun ensureReadable() {
            if (!readable) {
                throw NonReadableChannelException()
            }
        }

        fun ensureWritable() {
            if (!writable) {
                throw NonWritableChannelException()
            }
        }

        @Throws(IOException::class)
        fun checkSize(size: Long): Int {
            if (size > MAX_SIZE) {
                throw IOException("InMemoryFileSystem does not support files larger than 1GB")
            }
            return size.toInt()
        }

        fun maybeGrow(newSize: Int) {
            synchronized(this@InMemoryFileInfo) {
                if (newSize <= content.size) {
                    return
                }
                content = content.copyOf(IntMath.ceilingPowerOfTwo(newSize))
            }
        }

        @get:kotlin.jvm.Synchronized
        val isOpen: Boolean
            get() = !closed

        @kotlin.jvm.Synchronized
        override fun close() {
            closed = true
        }

        @kotlin.jvm.Synchronized
        @Throws(IOException::class)
        override fun read(dst: ByteBuffer): Int {
            ensureOpen()
            ensureReadable()
            synchronized(this@InMemoryFileInfo) {
                if (position >= size) {
                    // End of file.
                    return -1
                }
                val len: Int = min(dst.remaining(), size - position)
                if (len == 0) {
                    return 0
                }
                dst.put(content, position, len)
                position += len
                return len
            }
        }

        @kotlin.jvm.Synchronized
        @Throws(IOException::class)
        override fun write(src: ByteBuffer): Int {
            ensureOpen()
            ensureWritable()
            synchronized(this@InMemoryFileInfo) {
                if (append) {
                    position = size
                }
                val len = src.remaining()
                if (len == 0) {
                    // Zero write should not cause hole to be filled below.
                    return 0
                }
                val newSize = checkSize(max(size, position.toLong() + len))
                maybeGrow(newSize)
                if (position > size) {
                    // Fill hole left by previous seek, as it's not guaranteed to have been freshly allocated.
                    Arrays.fill(content, size, position, 0.toByte())
                }
                src.get(content, position, len)
                position += len
                size = newSize
                markModificationTime()
                return len
            }
        }

        @kotlin.jvm.Synchronized
        @Throws(IOException::class)
        override fun position(): Long {
            ensureOpen()
            return position.toLong()
        }

        @kotlin.jvm.Synchronized
        @Throws(IOException::class)
        override fun position(newPosition: Long): SeekableByteChannel {
            Preconditions.checkArgument(newPosition >= 0, "new position must be non-negative: %s", newPosition)
            ensureOpen()
            position = checkSize(newPosition)
            return this
        }

        @kotlin.jvm.Synchronized
        @Throws(IOException::class)
        override fun size(): Long {
            ensureOpen()
            synchronized(this@InMemoryFileInfo) {
                return size.toLong()
            }
        }

        @kotlin.jvm.Synchronized
        @Throws(IOException::class)
        override fun truncate(newSize: Long): SeekableByteChannel {
            Preconditions.checkArgument(newSize >= 0, "new size must be non-negative: %s", newSize)
            ensureOpen()
            ensureWritable()
            val truncatedSize = checkSize(newSize)
            synchronized(this@InMemoryFileInfo) {
                if (truncatedSize < size) {
                    size = truncatedSize
                    markModificationTime()
                }
                if (position > truncatedSize) {
                    position = truncatedSize
                }
                return this
            }
        }
    }

    companion object {
        // The minimum storage size, to avoid small reallocations.
        private const val MIN_SIZE = 32

        // The maximum file size. For simplicity, use the largest power of two representable as an int.
        private val MAX_SIZE = 1 shl 30
    }
}
