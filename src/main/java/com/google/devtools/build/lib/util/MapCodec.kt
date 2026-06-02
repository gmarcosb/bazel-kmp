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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.util.MapCodec
import com.google.devtools.build.lib.util.io.RecordOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.IOException

/** Converts map entries between their in-memory and on-disk representations.  */
abstract class MapCodec<K, V> {
    /** Exception thrown when persisted data read back from disk is in an incompatible format.  */
    class IncompatibleFormatException(message: String?) : IOException(message)

    /** Reads a key from a [DataInput].  */
    @Throws(IOException::class)
    protected abstract fun readKey(`in`: DataInput?): K?

    /** Reads a value from a [DataInput].  */
    @Throws(IOException::class)
    protected abstract fun readValue(`in`: DataInput?): V?

    /** Writes a key into a [DataOutput].  */
    @Throws(IOException::class)
    protected abstract fun writeKey(key: K?, out: DataOutput?)

    /** Writes a value into a [DataOutput].  */
    @Throws(IOException::class)
    protected abstract fun writeValue(value: V?, out: DataOutput?)

    /**
     * A key/value pair representing the presence or absence of a map entry.
     * 
     * @param key the entry key
     * @param value the entry value, or null if the entry is absent
     */
    @kotlin.jvm.JvmRecord
    data class Entry<K, V>(val key: K?, val value: V?)

    /**
     * Creates a new reader.
     * 
     * 
     * The file contents are eagerly read into memory, under the assumption that they will be
     * iterated to completion in short order.
     * 
     * @param path the path to the file to read
     * @param version the expected version number
     * @throws IncompatibleFormatException if the on-disk data is in an incompatible format
     * @throws IOException if data corruption is detected or some other I/O error occurs
     */
    @Throws(IOException::class)
    fun createReader(path: com.google.devtools.build.lib.vfs.Path, version: Long): Reader {
        val size: Long = path.getFileSize()
        if (size < MIN_MAPFILE_SIZE) {
            throw IncompatibleFormatException("%s is too short: %s bytes".formatted(path, size))
        }
        if (size > MAX_ARRAY_SIZE) {
            throw IncompatibleFormatException("%s is too long: %s bytes".formatted(path, size))
        }

        // Read the whole file upfront as a performance optimization (minimize syscalls).
        val bytes: ByteArray
        path.getInputStream().use { `in` ->
            bytes = `in`.readAllBytes()
        }
        val `in`: DataInputStream = DataInputStream(ByteArrayInputStream(bytes))

        if (`in`.readLong() != MAGIC.toLong()) {
            // Not a PersistentMap.
            throw IncompatibleFormatException("Bad magic number")
        }
        val persistedVersion: Long = `in`.readLong()
        if (persistedVersion != version) {
            // Incompatible version.
            throw IncompatibleFormatException(
                "Incompatible version: want %d, got %d".formatted(version, persistedVersion)
            )
        }

        return com.google.devtools.build.lib.util.MapCodec.Reader(`in`)
    }

    /** Reads key/value pairs from a [DataInputStream].  */
    inner class Reader private constructor(`in`: DataInputStream) : java.lang.AutoCloseable {
        private val `in`: DataInputStream

        init {
            this.`in` = `in`
        }

        /** Closes the reader, releasing associated resources and rendering it unusable.  */
        @Throws(IOException::class)
        override fun close() {
            `in`.close()
        }

        /**
         * Reads an [Entry].
         * 
         * @return the entry, or null if there are no more entries.
         */
        @Throws(IOException::class)
        fun readEntry(): Entry<K?, V?>? {
            if (`in`.available() == 0) {
                return null
            }
            if (`in`.readUnsignedByte() != ENTRY_MAGIC) {
                throw IOException("Corrupted entry separator")
            }
            val key = readKey(`in`)
            val hasValue: Boolean = `in`.readBoolean()
            val value = if (hasValue) readValue(`in`) else null
            return com.google.devtools.build.lib.util.MapCodec.Entry<K?, V?>(key, value)
        }
    }

    /**
     * Creates a new writer.
     * 
     * @param path the path to the file to write
     * @param version the version number to write
     * @param overwrite whether to overwrite an existing file instead of appending to it
     * @throws IOException if an I/O error occurs
     */
    @Throws(IOException::class)
    fun createWriter(path: com.google.devtools.build.lib.vfs.Path, version: Int, overwrite: Boolean): Writer {
        val append = !overwrite && path.exists()
        val recordOut: RecordOutputStream = RecordOutputStream(path.getOutputStream(append))
        val dataOut: DataOutputStream = DataOutputStream(recordOut)
        if (!append) {
            dataOut.writeLong(MAGIC.toLong())
            dataOut.writeLong(version.toLong())
            recordOut.finishRecord()
        }
        return com.google.devtools.build.lib.util.MapCodec.Writer(recordOut, dataOut)
    }

    /**
     * Writes key/value pairs to a [DataOutputStream] backed by a [RecordOutputStream].
     * 
     * 
     * In a best-effort attempt to prevent data corruption in the event of an abrupt exit, use a
     * [RecordOutputStream] instead of a [BufferedOutputStream] to ensure that only
     * complete records are ever written to the underlying unbuffered [OutputStream]. While this
     * can still be defeated by partial writes, experiments suggest they're rather unlikely for small
     * buffer sizes.
     */
    inner class Writer private constructor(recordOut: RecordOutputStream, dataOut: DataOutputStream) :
        java.lang.AutoCloseable {
        private val recordOut: RecordOutputStream
        private val dataOut: DataOutputStream

        init {
            this.recordOut = recordOut
            this.dataOut = dataOut
        }

        /** Flushes the writer, forcing any pending writes to be written to disk.  */
        @Throws(IOException::class)
        fun flush() {
            recordOut.flush()
        }

        /** Closes the writer, releasing associated resources and rendering it unusable.  */
        @Throws(IOException::class)
        override fun close() {
            recordOut.close()
        }

        /**
         * Writes a key/value pair.
         * 
         * @param key the key to write.
         * @param value the value to write, or null to write a tombstone.
         */
        @Throws(IOException::class)
        fun writeEntry(key: K?, value: V?) {
            dataOut.writeByte(ENTRY_MAGIC)
            writeKey(key, dataOut)
            val hasValue = value != null
            dataOut.writeBoolean(hasValue)
            if (hasValue) {
                writeValue(value, dataOut)
            }
            recordOut.finishRecord()
        }
    }

    companion object {
        private const val MAGIC = 0x20071105
        private const val MIN_MAPFILE_SIZE = 16
        private val MAX_ARRAY_SIZE = Int.Companion.MAX_VALUE - 8
        private const val ENTRY_MAGIC = 0xfe
    }
}
