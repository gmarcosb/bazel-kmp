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

import java.io.*
import java.nio.channels.Channels
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.ZipException

/**
 * A ZIP file reader.
 * 
 * 
 * This class provides entry data in the form of [ZipFileEntry], which provides more detail
 * about the entry than the JDK equivalent [ZipEntry]. In addition to providing
 * [InputStream]s for entries, similar to JDK [ZipFile.getInputStream], it
 * also provides access to the raw byte entry data via [.getRawInputStream].
 * 
 * 
 * Using the raw access capabilities allows for more efficient ZIP file processing, such as
 * merging, by not requiring each entry's data to be decompressed when read.
 * 
 * 
 * *NOTE:* The entries are read from the central directory. If the entry is not listed
 * there, it will not be returned from [.entries] or [.getEntry].
 */
class ZipReader @kotlin.jvm.JvmOverloads constructor(
    file: File,
    charset: Charset = StandardCharsets.UTF_8,
    strictEntries: Boolean = false
) : Closeable, AutoCloseable {
    private val file: File
    private val `in`: RandomAccessFile
    private val zipData: ZipFileData

    /**
     * Opens a zip file for raw acceess.
     * 
     * @param file the zip file
     * @param charset the charset to use to decode the entry names and comments
     * @param strictEntries force parsing to use the number of entries recorded in the end of
     * central directory as the correct value, not as an estimate
     * @throws ZipException if a ZIP format error has occurred
     * @throws IOException if an I/O error has occurred
     */
    /**
     * Opens a zip file for raw acceess.
     * 
     * @param file the zip file
     * @param charset the charset to use to decode the entry names and comments
     * @throws ZipException if a ZIP format error has occurred
     * @throws IOException if an I/O error has occurred
     */
    /**
     * Opens a zip file for raw acceess.
     * 
     * 
     * The UTF-8 charset is used to decode the entry names and comments.
     * 
     * @param file the zip file
     * @throws ZipException if a ZIP format error has occurred
     * @throws IOException if an I/O error has occurred
     */
    init {
        if (file == null || charset == null) {
            throw NullPointerException()
        }
        this.file = file
        this.`in` = RandomAccessFile(file, "r")
        this.zipData = ZipFileData(charset)
        readCentralDirectory(strictEntries)
    }

    /**
     * Returns the zip file's name.
     */
    fun getFilename(): String? {
        return file.getName()
    }

    /**
     * Returns the ZIP file comment.
     */
    fun getComment(): String? {
        return zipData.getComment()
    }

    /**
     * Returns a collection of the ZIP file entries.
     */
    fun entries(): MutableCollection<ZipFileEntry?>? {
        return zipData.getEntries()
    }

    /**
     * Returns the ZIP file entry for the specified name, or null if not found.
     */
    fun getEntry(name: String?): ZipFileEntry? {
        return zipData.getEntry(name)
    }

    /**
     * Returns the number of entries in the ZIP file.
     */
    fun size(): Long {
        return zipData.getNumEntries()
    }

    /**
     * Returns an input stream for reading the contents of the specified ZIP file entry.
     * 
     * 
     * Closing this ZIP file will, in turn, close all input streams that have been returned by
     * invocations of this method.
     * 
     * @param entry the ZIP file entry
     * @return the input stream for reading the contents of the specified zip file entry
     * @throws ZipException if a ZIP format error has occurred
     * @throws IOException if an I/O error has occurred
     */
    @Throws(IOException::class)
    fun getInputStream(entry: ZipFileEntry): InputStream {
        if (zipData.getEntry(entry.getName()) != entry) {
            throw ZipException(
                String.format(
                    "Zip file '%s' does not contain the requested entry '%s'.", file.getName(),
                    entry.getName()
                )
            )
        }
        return ZipEntryInputStream(this, entry,  /* raw */false)
    }

    /**
     * Returns an input stream for reading the raw contents of the specified ZIP file entry.
     * 
     * 
     * *NOTE:* No inflating will take place; The data read from the input stream will be
     * the exact byte content of the ZIP file entry on disk.
     * 
     * 
     * Closing this ZIP file will, in turn, close all input streams that have been returned by
     * invocations of this method.
     * 
     * @param entry the ZIP file entry
     * @return the input stream for reading the contents of the specified zip file entry
     * @throws ZipException if a ZIP format error has occurred
     * @throws IOException if an I/O error has occurred
     */
    @Throws(IOException::class)
    fun getRawInputStream(entry: ZipFileEntry): InputStream {
        if (zipData.getEntry(entry.getName()) != entry) {
            throw ZipException(
                String.format(
                    "Zip file '%s' does not contain the requested entry '%s'.", file.getName(),
                    entry.getName()
                )
            )
        }
        return ZipEntryInputStream(this, entry,  /* raw */true)
    }

    /**
     * Closes the ZIP file.
     * 
     * 
     * Closing this ZIP file will close all of the input streams previously returned by invocations
     * of the [.getRawInputStream] method.
     */
    @Throws(IOException::class)
    override fun close() {
        `in`.close()
    }

    /**
     * Finds, reads and parses ZIP file entries from the central directory.
     * 
     * @param strictEntries force parsing to use the number of entries recorded in the end of
     * central directory as the correct value, not as an estimate
     * @throws ZipException if a ZIP format error has occurred
     * @throws IOException if an I/O error has occurred
     */
    @Throws(IOException::class)
    private fun readCentralDirectory(strictEntries: Boolean) {
        val eocdLocation = findEndOfCentralDirectoryRecord()
        var stream = getStreamAt(eocdLocation)
        EndOfCentralDirectoryRecord.read(stream, zipData)

        if (zipData.isMaybeZip64()) {
            try {
                stream = getStreamAt(eocdLocation - Zip64EndOfCentralDirectoryLocator.FIXED_DATA_SIZE)
                Zip64EndOfCentralDirectoryLocator.read(stream, zipData)

                stream = getStreamAt(zipData.getZip64EndOfCentralDirectoryOffset())
                Zip64EndOfCentralDirectory.read(stream, zipData)
            } catch (e: ZipException) {
                // expected if not in Zip64 format
            }
        }

        if (zipData.isZip64() || strictEntries) {
            // If in Zip64 format or using strict entry numbers, use the parsed information as is to read
            // the central directory file headers.
            readCentralDirectoryFileHeaders(
                zipData.getExpectedEntries(),
                zipData.getCentralDirectoryOffset()
            )
        } else {
            // If not in Zip64 format, compute central directory offset by end of central directory record
            // offset and central directory size to allow reading large non-compliant Zip32 directories.
            val centralDirectoryOffset = eocdLocation - zipData.getCentralDirectorySize()
            // If the lower 4 bytes match, the above calculation is correct; otherwise fallback to
            // reported offset.
            if (centralDirectoryOffset.toInt() == zipData.getCentralDirectoryOffset().toInt()) {
                readCentralDirectoryFileHeaders(centralDirectoryOffset)
            } else {
                readCentralDirectoryFileHeaders(
                    zipData.getExpectedEntries(),
                    zipData.getCentralDirectoryOffset()
                )
            }
        }
    }

    /**
     * Looks for the target sub array in the buffer scanning backwards starting at offset. Returns the
     * index where the target is found or -1 if not found.
     * 
     * @param target the sub array to find
     * @param buffer the array to scan
     * @param offset the index of where to begin scanning
     * @return the index of target within buffer or -1 if not found
     */
    private fun scanBackwards(target: ByteArray, buffer: ByteArray, offset: Int): Int {
        val start: Int = min(offset, buffer.size - target.size)
        for (i in start downTo 0) {
            for (j in target.indices) {
                if (buffer[i + j] != target[j]) {
                    break
                } else if (j == target.size - 1) {
                    return i
                }
            }
        }
        return -1
    }

    /**
     * Finds the file offset of the end of central directory record.
     * 
     * @return the file offset of the end of central directory record
     * @throws ZipException if a ZIP format error has occurred
     * @throws IOException if an I/O error has occurred
     */
    @Throws(IOException::class)
    private fun findEndOfCentralDirectoryRecord(): Long {
        val signature = ZipUtil.intToLittleEndian(EndOfCentralDirectoryRecord.SIGNATURE)
        val buffer = ByteArray(min(64, `in`.length()) as Int)
        var readLength = buffer.size
        if (readLength < EndOfCentralDirectoryRecord.FIXED_DATA_SIZE) {
            throw ZipException(
                String.format(
                    "Zip file '%s' is malformed. It does not contain an end"
                            + " of central directory record.", file.getName()
                )
            )
        }

        var offset = `in`.length() - buffer.size
        while (offset >= 0) {
            `in`.seek(offset)
            `in`.readFully(buffer, 0, readLength)
            var signatureLocation = scanBackwards(signature, buffer, buffer.size)
            while (signatureLocation != -1) {
                val eocdSize = `in`.length() - offset - signatureLocation
                if (eocdSize >= EndOfCentralDirectoryRecord.FIXED_DATA_SIZE) {
                    val commentLength = ZipUtil.getUnsignedShort(
                        buffer, signatureLocation
                                + EndOfCentralDirectoryRecord.COMMENT_LENGTH_OFFSET
                    )
                    val readCommentLength = eocdSize - EndOfCentralDirectoryRecord.FIXED_DATA_SIZE
                    if (commentLength.toLong() == readCommentLength) {
                        return offset + signatureLocation
                    }
                }
                signatureLocation = scanBackwards(signature, buffer, signatureLocation - 1)
            }
            readLength = buffer.size - 3
            buffer[buffer.size - 3] = buffer[0]
            buffer[buffer.size - 2] = buffer[1]
            buffer[buffer.size - 1] = buffer[2]
            offset -= readLength.toLong()
        }
        throw ZipException(
            String.format(
                "Zip file '%s' is malformed. It does not contain an end"
                        + " of central directory record.", file.getName()
            )
        )
    }

    /**
     * Reads and parses ZIP file entries from the central directory.
     * 
     * @param count the number of entries in the central directory
     * @param fileOffset the file offset of the start of the central directory
     * @throws ZipException if a ZIP format error has occurred
     * @throws IOException if an I/O error has occurred
     */
    @Throws(IOException::class)
    private fun readCentralDirectoryFileHeaders(count: Long, fileOffset: Long) {
        val centralDirectory = getStreamAt(fileOffset)
        for (i in 0..<count) {
            val entry = CentralDirectoryFileHeader.read(centralDirectory, zipData.getCharset())
            zipData.addEntry(entry)
        }
    }

    /**
     * Reads and parses ZIP file entries from the central directory.
     * 
     * @param fileOffset the file offset of the start of the central directory
     * @throws ZipException if a ZIP format error has occurred
     * @throws IOException if an I/O error has occurred
     */
    @Throws(IOException::class)
    private fun readCentralDirectoryFileHeaders(fileOffset: Long) {
        val centralDirectory = CountingInputStream(getStreamAt(fileOffset))
        while (centralDirectory.getCount() < zipData.getCentralDirectorySize()) {
            val entry = CentralDirectoryFileHeader.read(centralDirectory, zipData.getCharset())
            zipData.addEntry(entry)
        }
    }

    /**
     * Returns a new [InputStream] positioned at fileOffset.
     * 
     * @throws IOException if an I/O error has occurred
     */
    @Throws(IOException::class)
    fun getStreamAt(fileOffset: Long): InputStream {
        return BufferedInputStream(Channels.newInputStream(`in`.getChannel().position(fileOffset)))
    }
}
