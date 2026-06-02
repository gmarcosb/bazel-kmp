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

import java.nio.charset.Charset
import java.util.zip.ZipException
import kotlin.collections.Collection
import kotlin.collections.LinkedHashMap
import kotlin.collections.MutableCollection
import kotlin.collections.MutableMap

/**
 * A representation of a ZIP file. Contains the file comment, encoding, and entries. Also contains
 * internal information about the structure and location of ZIP file parts.
 */
internal class ZipFileData(charset: Charset) {
    private val charset: Charset
    private var comment: String?

    private var centralDirectorySize: Long = 0
    private var centralDirectoryOffset: Long = 0
    private var expectedEntries: Long = 0
    private var numEntries: Long = 0
    private val entries: MutableMap<String?, ZipFileEntry?>

    private var maybeZip64 = false
    private var isZip64 = false
    private var zip64EndOfCentralDirectoryOffset: Long = 0

    /**
     * Creates a new ZIP file with the specified charset encoding.
     */
    init {
        if (charset == null) {
            throw NullPointerException()
        }
        this.charset = charset
        comment = ""
        entries = LinkedHashMap<String?, ZipFileEntry?>()
    }

    /**
     * Returns the encoding of the file.
     */
    fun getCharset(): Charset {
        return charset
    }

    /**
     * Returns the file comment.
     */
    fun getComment(): String? {
        return comment
    }

    /**
     * Sets the file comment from the raw byte data in the file. Converts the bytes to a string using
     * the file's charset encoding.
     * 
     * @throws ZipException if the comment is longer than allowed by the ZIP format
     */
    @Throws(ZipException::class)
    fun setComment(comment: ByteArray) {
        if (comment == null) {
            throw NullPointerException()
        }
        if (comment.size > 0xffff) {
            throw ZipException(
                String.format(
                    "File comment too long. Is %d; max %d.",
                    comment.size, 0xffff
                )
            )
        }
        this.comment = fromBytes(comment)
    }

    /**
     * Sets the file comment.
     * 
     * @throws ZipException if the comment will be longer than allowed by the ZIP format when encoded
     * using the file's charset encoding
     */
    @Throws(ZipException::class)
    fun setComment(comment: String) {
        setComment(getBytes(comment)!!)
    }

    /**
     * Returns the size of the central directory in bytes.
     */
    fun getCentralDirectorySize(): Long {
        return centralDirectorySize
    }

    /**
     * Sets the size of the central directory in bytes. If the size is larger than 0xffffffff, the
     * file is set to Zip64 mode.
     * 
     * 
     * See [ZIP Format](http://www.pkware.com/documents/casestudies/APPNOTE.TXT)
     * section 4.4.23
     */
    fun setCentralDirectorySize(centralDirectorySize: Long) {
        this.centralDirectorySize = centralDirectorySize
        if (centralDirectorySize > 0xffffffffL) {
            setZip64(true)
        }
    }

    /**
     * Returns the file offset of the start of the central directory.
     */
    fun getCentralDirectoryOffset(): Long {
        return centralDirectoryOffset
    }

    /**
     * Sets the file offset of the start of the central directory. If the offset is larger than
     * 0xffffffff, the file is set to Zip64 mode.
     * 
     * 
     * See [ZIP Format](http://www.pkware.com/documents/casestudies/APPNOTE.TXT)
     * section 4.4.24
     */
    fun setCentralDirectoryOffset(offset: Long) {
        this.centralDirectoryOffset = offset
        if (centralDirectoryOffset > 0xffffffffL) {
            setZip64(true)
        }
    }

    /**
     * Returns the number of entries expected to be in the ZIP file. This value is determined from the
     * end of central directory record.
     */
    fun getExpectedEntries(): Long {
        return expectedEntries
    }

    /**
     * Sets the number of entries expected to be in the ZIP file. This value should be set by reading
     * the end of central directory record.
     * 
     * 
     * See [ZIP Format](http://www.pkware.com/documents/casestudies/APPNOTE.TXT)
     * section 4.4.22
     */
    fun setExpectedEntries(count: Long) {
        this.expectedEntries = count
        if (expectedEntries > 0xffff) {
            setZip64(true)
        }
    }

    /**
     * Returns the number of entries actually in the ZIP file. This value is derived from the number
     * of times [.addEntry] was called.
     * 
     * 
     * *NOTE:* This value should be used rather than getting the size from the
     * [Collection] returned from [.getEntries], because the value may be too large to
     * be properly represented by an int.
     */
    fun getNumEntries(): Long {
        return numEntries
    }

    /**
     * Sets the number of entries actually in the ZIP file. If the value is larger than 0xffff, the
     * file is set to Zip64 mode.
     */
    private fun setNumEntries(numEntries: Long) {
        this.numEntries = numEntries
        if (numEntries > 0xffff) {
            setZip64(true)
        }
    }

    /**
     * Returns a collection of all entries in the ZIP file.
     */
    fun getEntries(): MutableCollection<ZipFileEntry?> {
        return entries.values
    }

    /**
     * Returns the entry with the given name, or null if it does not exist.
     */
    fun getEntry(name: String?): ZipFileEntry? {
        return entries.get(name)
    }

    /**
     * Adds an entry to the ZIP file. If this causes the actual number of entries to exceed
     * 0xffffffff, or if the file requires Zip64 features, the file is set to Zip64 mode.
     */
    fun addEntry(entry: ZipFileEntry) {
        entries.put(entry.getName(), entry)
        setNumEntries(numEntries + 1)
        if (entry.getFeatureSet().contains(ZipFileEntry.Feature.ZIP64_SIZE)
            || entry.getFeatureSet().contains(ZipFileEntry.Feature.ZIP64_CSIZE)
            || entry.getFeatureSet().contains(ZipFileEntry.Feature.ZIP64_OFFSET)
        ) {
            setZip64(true)
        }
    }

    /**
     * Returns if the file may be in Zip64 mode. This is true if any of the values in the end of
     * central directory record are -1.
     * 
     * 
     * See [ZIP Format](http://www.pkware.com/documents/casestudies/APPNOTE.TXT)
     * section 4.4.19 - 4.4.24
     */
    fun isMaybeZip64(): Boolean {
        return maybeZip64
    }

    /**
     * Set if the file may be in Zip64 mode. This is true if any of the values in the end of
     * central directory record are -1.
     * 
     * 
     * See [ZIP Format](http://www.pkware.com/documents/casestudies/APPNOTE.TXT)
     * section 4.4.19 - 4.4.24
     */
    fun setMaybeZip64(maybeZip64: Boolean) {
        this.maybeZip64 = maybeZip64
    }

    /**
     * Returns if the file is in Zip64 mode. This is true if any of a number of fields exceed the
     * maximum value.
     * 
     * 
     * See [ZIP Format](http://www.pkware.com/documents/casestudies/APPNOTE.TXT) for
     * details
     */
    fun isZip64(): Boolean {
        return isZip64
    }

    /**
     * Set if the file is in Zip64 mode. This is true if any of a number of fields exceed the maximum
     * value.
     * 
     * 
     * See [ZIP Format](http://www.pkware.com/documents/casestudies/APPNOTE.TXT) for
     * details
     */
    fun setZip64(isZip64: Boolean) {
        this.isZip64 = isZip64
        setMaybeZip64(true)
    }

    /**
     * Returns the file offset of the Zip64 end of central directory record. The record is only
     * present if [.isZip64] returns true.
     */
    fun getZip64EndOfCentralDirectoryOffset(): Long {
        return zip64EndOfCentralDirectoryOffset
    }

    /**
     * Sets the file offset of the Zip64 end of central directory record and sets the file to Zip64
     * mode.
     */
    fun setZip64EndOfCentralDirectoryOffset(offset: Long) {
        this.zip64EndOfCentralDirectoryOffset = offset
        setZip64(true)
    }

    /**
     * Returns the byte representation of the specified string using the file's charset encoding.
     */
    fun getBytes(string: String): ByteArray? {
        return string.toByteArray(charset)
    }

    /**
     * Returns the string represented by the specified byte array using the file's charset encoding.
     */
    fun fromBytes(bytes: ByteArray): String {
        return String(bytes, charset)
    }
}
