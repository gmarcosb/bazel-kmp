// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.zip

import java.io.IOException
import java.io.InputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipException

/** An input stream for reading the file data of a ZIP file entry.  */
internal class ZipEntryInputStream(zipReader: ZipReader, zipEntry: ZipFileEntry, raw: Boolean) : InputStream() {
    private var stream: InputStream
    private var rem: Long = 0

    /**
     * Opens an input stream for reading at the beginning of the ZIP file entry's content.
     * 
     * @param zipReader the backing ZIP reader for this InputStream
     * @param zipEntry the ZIP file entry to open the input stream for
     * @param raw if the entry should be opened for raw read mode
     * @throws ZipException if a ZIP format error has occurred
     * @throws IOException if an I/O error has occurred
     */
    init {
        stream = zipReader.getStreamAt(zipEntry.getLocalHeaderOffset())

        val fileHeader = ByteArray(LocalFileHeader.FIXED_DATA_SIZE)
        ZipUtil.readFully(stream, fileHeader)

        if (!ZipUtil.arrayStartsWith(
                fileHeader,
                ZipUtil.intToLittleEndian(LocalFileHeader.SIGNATURE)
            )
        ) {
            throw ZipException(
                String.format(
                    "The file '%s' is not a correctly formatted zip file: "
                            + "Expected a File Header at file offset %d, but was not present.",
                    zipReader.getFilename(), zipEntry.getLocalHeaderOffset()
                )
            )
        }

        val nameLength = ZipUtil.getUnsignedShort(
            fileHeader,
            LocalFileHeader.FILENAME_LENGTH_OFFSET
        )
        val extraFieldLength = ZipUtil.getUnsignedShort(
            fileHeader,
            LocalFileHeader.EXTRA_FIELD_LENGTH_OFFSET
        )
        ZipUtil.readFully(stream, ByteArray(nameLength + extraFieldLength))
        if (raw) {
            rem = zipEntry.getCompressedSize()
        } else {
            rem = zipEntry.getSize()
        }
        if (!raw && zipEntry.getMethod() == ZipFileEntry.Compression.DEFLATED) {
            stream = InflaterInputStream(stream, Inflater(true), INFLATER_BUFFER_BYTES)
        }
    }

    @Throws(IOException::class)
    override fun available(): Int {
        return min(rem, Int.Companion.MAX_VALUE) as Int
    }

    @Throws(IOException::class)
    override fun close() {
    }

    @kotlin.jvm.Synchronized
    override fun mark(readlimit: Int) {
    }

    override fun markSupported(): Boolean {
        return false
    }

    @Throws(IOException::class)
    override fun read(): Int {
        val b = ByteArray(1)
        if (read(b, 0, 1) == 1) {
            return b[0].toInt() and 0xff
        } else {
            return -1
        }
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray): Int {
        return read(b, 0, b.size)
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray?, off: Int, len: Int): Int {
        var len = len
        if (rem == 0L) {
            return -1
        }
        if (len > rem) {
            len = available()
        }
        len = stream.read(b, off, len)
        rem -= len.toLong()
        return len
    }

    @Throws(IOException::class)
    override fun skip(n: Long): Long {
        var n = n
        if (n > rem) {
            n = rem
        }
        n = stream.skip(n)
        rem -= n
        return n
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    override fun reset() {
        throw IOException("Reset is not supported on this type of stream.")
    }

    companion object {
        private const val INFLATER_BUFFER_BYTES = 8192
    }
}
