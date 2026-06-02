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
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.zip.ZipException

/**
 * This class implements an output stream filter for writing files in the ZIP file format. It does
 * not perform its own compression and so allows writing of already compressed file data.
 */
class ZipWriter @kotlin.jvm.JvmOverloads constructor(
    out: OutputStream?,
    charset: Charset?,
    allowZip64: Boolean = false
) : OutputStream() {
    private val stream: CountingOutputStream
    private val zipData: ZipFileData
    private val allowZip64: Boolean
    private var writingPrefix = false
    private var entry: ZipFileEntry? = null
    private var bytesWritten: Long = 0
    private var finished: Boolean

    /**
     * Creates a new raw ZIP output stream.
     * 
     * @param out the actual output stream
     * @param charset the [Charset] to be used to encode the entry names and comments
     * @param allowZip64 whether the output Zip should be allowed to use Zip64 extensions
     */
    /**
     * Creates a new raw ZIP output stream.
     * 
     * @param out the actual output stream
     * @param charset the [Charset] to be used to encode the entry names and comments
     */
    init {
        this.stream = CountingOutputStream(out)
        this.zipData = ZipFileData(charset)
        this.allowZip64 = allowZip64
        this.finished = false
    }

    /**
     * Sets the ZIP file comment.
     * 
     * @param comment the ZIP file comment
     * @throws ZipException if the comment is longer than 0xffff bytes
     */
    @Throws(ZipException::class)
    fun setComment(comment: String?) {
        zipData.setComment(comment)
    }

    /**
     * Configures the stream to write prefix file data.
     * 
     * @throws ZipException if other contents have already been written to the output stream
     */
    @Throws(ZipException::class)
    fun startPrefixFile() {
        checkNotFinished()
        if (!zipData.getEntries().isEmpty() || entry != null) {
            throw ZipException("Cannot add a prefix file after the zip contents have been started.")
        }
        writingPrefix = true
    }

    /** Closes the prefix file and positions the output stream to write ZIP entries.  */
    fun endPrefixFile() {
        checkNotFinished()
        writingPrefix = false
    }

    /**
     * Begins writing a new ZIP file entry and positions the stream to the start of the entry data.
     * Closes the current entry if still active.
     * 
     * 
     * *NOTE:* No defensive copying is performed on e. The local header offset and flags
     * will be modified.
     * 
     * @param e the ZIP entry to be written
     * @throws IOException if an I/O error occurred
     */
    @Throws(IOException::class)
    fun putNextEntry(e: ZipFileEntry) {
        checkNotFinished()
        writingPrefix = false
        if (entry != null) {
            finishEntry()
        }
        startEntry(e)
    }

    /**
     * Closes the current ZIP entry and positions the stream for writing the next entry.
     * 
     * @throws ZipException if a ZIP format exception occurred
     * @throws IOException if an I/O error occurred
     */
    @Throws(IOException::class)
    fun closeEntry() {
        checkNotFinished()
        if (entry != null) {
            finishEntry()
        }
    }

    @Throws(IOException::class)
    override fun write(b: Int) {
        val buf = ByteArray(1)
        buf[0] = (b and 0xff).toByte()
        write(buf)
    }

    @Throws(IOException::class)
    override fun write(b: ByteArray) {
        write(b, 0, b.size)
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    override fun write(b: ByteArray?, off: Int, len: Int) {
        checkNotFinished()
        if (entry == null && !writingPrefix) {
            throw ZipException(
                "Cannot write zip contents without first setting a ZipEntry or"
                        + " starting a prefix file."
            )
        }
        stream.write(b, off, len)
        bytesWritten += len.toLong()
    }

    /**
     * Finishes writing the contents of the ZIP output stream without closing the underlying stream.
     * Use this method when applying multiple filters in succession to the same output stream.
     * 
     * @throws ZipException if a ZIP file error has occurred
     * @throws IOException if an I/O exception has occurred
     */
    @Throws(IOException::class)
    fun finish() {
        checkNotFinished()
        if (entry != null) {
            finishEntry()
        }
        writeCentralDirectory()
        finished = true
    }

    @Throws(IOException::class)
    override fun close() {
        if (!finished) {
            finish()
        }
        stream.close()
    }

    /**
     * Writes the local file header for the ZIP entry and positions the stream to the start of the
     * entry data.
     * 
     * @param e the ZIP entry for which to write the local file header
     * @throws ZipException if a ZIP file error has occurred
     * @throws IOException if an I/O exception has occurred
     */
    @Throws(IOException::class)
    private fun startEntry(e: ZipFileEntry) {
        require(e.getTime() != -1L) { "Zip entry last modified time must be set" }
        require(e.getCrc() != -1L) { "Zip entry CRC-32 must be set" }
        require(e.getSize() != -1L) { "Zip entry uncompressed size must be set" }
        require(e.getCompressedSize() != -1L) { "Zip entry compressed size must be set" }
        bytesWritten = 0
        entry = e
        entry!!.setFlag(ZipFileEntry.Flag.DATA_DESCRIPTOR, false)
        entry!!.setLocalHeaderOffset(stream.getCount())
        stream.write(LocalFileHeader.create(entry, zipData, allowZip64))
    }

    /**
     * Closes the current ZIP entry and positions the stream for writing the next entry. Checks that
     * the amount of data written matches the compressed size indicated by the ZipEntry.
     * 
     * @throws ZipException if a ZIP file error has occurred
     * @throws IOException if an I/O exception has occurred
     */
    @Throws(IOException::class)
    private fun finishEntry() {
        if (entry!!.getCompressedSize() != bytesWritten) {
            throw ZipException(
                String.format(
                    "Number of bytes written for the entry %s (%d) does not"
                            + " match the reported compressed size (%d).", entry!!.getName(), bytesWritten,
                    entry!!.getCompressedSize()
                )
            )
        }
        zipData.addEntry(entry)
        entry = null
    }

    /**
     * Writes the ZIP file's central directory.
     * 
     * @throws ZipException if a ZIP file error has occurred
     * @throws IOException if an I/O exception has occurred
     */
    @Throws(IOException::class)
    private fun writeCentralDirectory() {
        zipData.setCentralDirectoryOffset(stream.getCount())
        CentralDirectory.write(zipData, allowZip64, stream)
    }

    /** Checks that the ZIP file has not been finished yet.  */
    private fun checkNotFinished() {
        check(!finished)
    }
}
