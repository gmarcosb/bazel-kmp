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
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.zip.ZipException

internal object CentralDirectoryFileHeader {
    const val SIGNATURE: Int = 0x02014b50
    const val FIXED_DATA_SIZE: Int = 46
    const val SIGNATURE_OFFSET: Int = 0
    const val VERSION_OFFSET: Int = 4
    const val VERSION_NEEDED_OFFSET: Int = 6
    const val FLAGS_OFFSET: Int = 8
    const val METHOD_OFFSET: Int = 10
    const val MOD_TIME_OFFSET: Int = 12
    const val CRC_OFFSET: Int = 16
    const val COMPRESSED_SIZE_OFFSET: Int = 20
    const val UNCOMPRESSED_SIZE_OFFSET: Int = 24
    const val FILENAME_LENGTH_OFFSET: Int = 28
    const val EXTRA_FIELD_LENGTH_OFFSET: Int = 30
    const val COMMENT_LENGTH_OFFSET: Int = 32
    const val DISK_START_OFFSET: Int = 34
    const val INTERNAL_ATTRIBUTES_OFFSET: Int = 36
    const val EXTERNAL_ATTRIBUTES_OFFSET: Int = 38
    const val LOCAL_HEADER_OFFSET_OFFSET: Int = 42

    /**
     * Reads a [ZipFileEntry] from the input stream, using the specified [Charset] to
     * decode the filename and comment.
     */
    @Throws(IOException::class)
    fun read(`in`: InputStream?, charset: Charset): ZipFileEntry {
        val fixedSizeData = ByteArray(FIXED_DATA_SIZE)

        if (ZipUtil.readFully(`in`, fixedSizeData) != FIXED_DATA_SIZE) {
            throw ZipException(
                "Unexpected end of file while reading Central Directory File Header."
            )
        }
        if (!ZipUtil.arrayStartsWith(fixedSizeData, ZipUtil.intToLittleEndian(SIGNATURE))) {
            throw ZipException(
                String.format(
                    "Malformed Central Directory File Header; does not start with %08x", SIGNATURE
                )
            )
        }

        val name = ByteArray(ZipUtil.getUnsignedShort(fixedSizeData, FILENAME_LENGTH_OFFSET))
        val extraField = ByteArray(ZipUtil.getUnsignedShort(fixedSizeData, EXTRA_FIELD_LENGTH_OFFSET))
        val comment = ByteArray(ZipUtil.getUnsignedShort(fixedSizeData, COMMENT_LENGTH_OFFSET))

        if (name.size > 0 && ZipUtil.readFully(`in`, name) != name.size) {
            throw ZipException(
                "Unexpected end of file while reading Central Directory File Header."
            )
        }
        if (extraField.size > 0 && ZipUtil.readFully(`in`, extraField) != extraField.size) {
            throw ZipException(
                "Unexpected end of file while reading Central Directory File Header."
            )
        }
        if (comment.size > 0 && ZipUtil.readFully(`in`, comment) != comment.size) {
            throw ZipException(
                "Unexpected end of file while reading Central Directory File Header."
            )
        }

        val extra = ExtraDataList(extraField)

        var csize = ZipUtil.getUnsignedInt(fixedSizeData, COMPRESSED_SIZE_OFFSET)
        var size = ZipUtil.getUnsignedInt(fixedSizeData, UNCOMPRESSED_SIZE_OFFSET)
        var offset = ZipUtil.getUnsignedInt(fixedSizeData, LOCAL_HEADER_OFFSET_OFFSET)
        if (csize == 0xffffffffL || size == 0xffffffffL || offset == 0xffffffffL) {
            val zip64Extra = extra.get(0x0001.toShort())
            if (zip64Extra != null) {
                var index = 0
                if (size == 0xffffffffL) {
                    size = ZipUtil.getUnsignedLong(zip64Extra.getData(), index)
                    index += 8
                }
                if (csize == 0xffffffffL) {
                    csize = ZipUtil.getUnsignedLong(zip64Extra.getData(), index)
                    index += 8
                }
                if (offset == 0xffffffffL) {
                    offset = ZipUtil.getUnsignedLong(zip64Extra.getData(), index)
                    index += 8
                }
            }
        }

        val entry = ZipFileEntry(String(name, charset))
        entry.setVersion(ZipUtil.get16(fixedSizeData, VERSION_OFFSET))
        entry.setVersionNeeded(ZipUtil.get16(fixedSizeData, VERSION_NEEDED_OFFSET))
        entry.setFlags(ZipUtil.get16(fixedSizeData, FLAGS_OFFSET))
        entry.setMethod(
            ZipFileEntry.Compression.Companion.fromValue(
                ZipUtil.get16(fixedSizeData, METHOD_OFFSET).toInt()
            )
        )
        val time = ZipUtil.dosToUnixTime(ZipUtil.get32(fixedSizeData, MOD_TIME_OFFSET))
        entry.setTime(if (ZipUtil.isValidInDos(time)) time else ZipUtil.DOS_EPOCH)
        entry.setCrc(ZipUtil.getUnsignedInt(fixedSizeData, CRC_OFFSET))
        entry.setCompressedSize(csize)
        entry.setSize(size)
        entry.setInternalAttributes(ZipUtil.get16(fixedSizeData, INTERNAL_ATTRIBUTES_OFFSET))
        entry.setExternalAttributes(ZipUtil.get32(fixedSizeData, EXTERNAL_ATTRIBUTES_OFFSET))
        entry.setLocalHeaderOffset(offset)
        entry.setExtra(extra)
        entry.setComment(String(comment, charset))

        return entry
    }

    /**
     * Generates the raw byte data of the central directory file header for the ZipEntry. Uses the
     * specified [ZipFileData] to encode the file name and comment.
     * @throws ZipException
     */
    @Throws(ZipException::class)
    fun create(entry: ZipFileEntry, file: ZipFileData, allowZip64: Boolean): ByteArray {
        if (allowZip64) {
            addZip64Extra(entry)
        } else {
            entry.getExtra().remove(0x0001.toShort())
        }
        val name = file.getBytes(entry.getName())
        val extra = entry.getExtra().getBytes()
        val comment = if (entry.getComment() != null)
            file.getBytes(entry.getComment())
        else
            byteArrayOf()

        val buf = ByteArray(FIXED_DATA_SIZE + name.size + extra.size + comment.size)

        fillFixedSizeData(buf, entry, name.size, extra.size, comment.size, allowZip64)
        System.arraycopy(name, 0, buf, FIXED_DATA_SIZE, name.size)
        System.arraycopy(extra, 0, buf, FIXED_DATA_SIZE + name.size, extra.size)
        System.arraycopy(
            comment, 0, buf, FIXED_DATA_SIZE + name.size + extra.size,
            comment.size
        )

        return buf
    }

    /**
     * Writes the central directory file header for the ZipEntry to an output stream. Uses the
     * specified [ZipFileData] to encode the file name and comment.
     */
    @Throws(IOException::class)
    fun write(
        entry: ZipFileEntry, file: ZipFileData, allowZip64: Boolean, buf: ByteArray?,
        stream: OutputStream
    ): Int {
        var buf = buf
        if (buf == null || buf.size < FIXED_DATA_SIZE) {
            buf = ByteArray(FIXED_DATA_SIZE)
        }

        val extra = ExtraDataList(entry.getExtra())
        if (allowZip64) {
            addZip64Extra(entry)
        } else {
            extra.remove(0x0001.toShort())
        }

        extra.remove(ExtraDataList.Companion.EXTENDED_TIMESTAMP)
        extra.remove(ExtraDataList.Companion.INFOZIP_UNIX_NEW)

        val name: ByteArray = entry.getName().toByteArray(file.getCharset())
        val extraBytes = extra.getBytes()
        val comment = if (entry.getComment() != null)
            entry.getComment().toByteArray(file.getCharset())
        else
            byteArrayOf()

        fillFixedSizeData(buf, entry, name.size, extraBytes.size, comment.size, allowZip64)
        stream.write(buf, 0, FIXED_DATA_SIZE)
        stream.write(name)
        stream.write(extraBytes)
        stream.write(comment)

        return FIXED_DATA_SIZE + name.size + extraBytes.size + comment.size
    }

    /**
     * Write the fixed size data portion for the specified ZIP entry to the buffer.
     * @throws ZipException
     */
    @Throws(ZipException::class)
    private fun fillFixedSizeData(
        buf: ByteArray?, entry: ZipFileEntry, nameLength: Int,
        extraLength: Int, commentLength: Int, allowZip64: Boolean
    ) {
        if (!allowZip64 && entry.getFeatureSet().contains(ZipFileEntry.Feature.ZIP64_CSIZE)) {
            throw ZipException(
                String.format(
                    "Writing an entry with compressed size %d without"
                            + " Zip64 extensions is not supported.", entry.getCompressedSize()
                )
            )
        }
        if (!allowZip64 && entry.getFeatureSet().contains(ZipFileEntry.Feature.ZIP64_SIZE)) {
            throw ZipException(
                String.format(
                    "Writing an entry of size %d without"
                            + " Zip64 extensions is not supported.", entry.getSize()
                )
            )
        }
        if (!allowZip64 && entry.getFeatureSet().contains(ZipFileEntry.Feature.ZIP64_OFFSET)) {
            throw ZipException(
                String.format(
                    "Writing an entry with local header offset %d without"
                            + " Zip64 extensions is not supported.", entry.getLocalHeaderOffset()
                )
            )
        }
        val csize = (if (entry.getFeatureSet().contains(ZipFileEntry.Feature.ZIP64_CSIZE))
            -1
        else
            entry.getCompressedSize()).toInt()
        val size = (if (entry.getFeatureSet().contains(ZipFileEntry.Feature.ZIP64_SIZE))
            -1
        else
            entry.getSize()).toInt()
        val offset = (if (entry.getFeatureSet().contains(ZipFileEntry.Feature.ZIP64_OFFSET))
            -1
        else
            entry.getLocalHeaderOffset()).toInt()
        ZipUtil.intToLittleEndian(buf, SIGNATURE_OFFSET, SIGNATURE)
        ZipUtil.shortToLittleEndian(buf, VERSION_OFFSET, entry.getVersion())
        ZipUtil.shortToLittleEndian(buf, VERSION_NEEDED_OFFSET, entry.getVersionNeeded())
        ZipUtil.shortToLittleEndian(buf, FLAGS_OFFSET, entry.getFlags())
        ZipUtil.shortToLittleEndian(buf, METHOD_OFFSET, entry.getMethod().getValue())
        ZipUtil.intToLittleEndian(buf, MOD_TIME_OFFSET, ZipUtil.unixToDosTime(entry.getTime()))
        ZipUtil.intToLittleEndian(buf, CRC_OFFSET, (entry.getCrc() and 0xffffffffL).toInt())
        ZipUtil.intToLittleEndian(buf, COMPRESSED_SIZE_OFFSET, csize)
        ZipUtil.intToLittleEndian(buf, UNCOMPRESSED_SIZE_OFFSET, size)
        ZipUtil.shortToLittleEndian(buf, FILENAME_LENGTH_OFFSET, (nameLength and 0xffff).toShort())
        ZipUtil.shortToLittleEndian(buf, EXTRA_FIELD_LENGTH_OFFSET, (extraLength and 0xffff).toShort())
        ZipUtil.shortToLittleEndian(buf, COMMENT_LENGTH_OFFSET, (commentLength and 0xffff).toShort())
        ZipUtil.shortToLittleEndian(buf, DISK_START_OFFSET, 0.toShort())
        ZipUtil.shortToLittleEndian(buf, INTERNAL_ATTRIBUTES_OFFSET, entry.getInternalAttributes())
        ZipUtil.intToLittleEndian(buf, EXTERNAL_ATTRIBUTES_OFFSET, entry.getExternalAttributes())
        ZipUtil.intToLittleEndian(buf, LOCAL_HEADER_OFFSET_OFFSET, offset)
    }

    /**
     * Update the extra data fields to contain a Zip64 extended information field if required
     */
    private fun addZip64Extra(entry: ZipFileEntry) {
        val features = entry.getFeatureSet()
        val extra = entry.getExtra()
        var extraSize = 0
        if (features.contains(ZipFileEntry.Feature.ZIP64_SIZE)) {
            extraSize += 8
        }
        if (features.contains(ZipFileEntry.Feature.ZIP64_CSIZE)) {
            extraSize += 8
        }
        if (features.contains(ZipFileEntry.Feature.ZIP64_OFFSET)) {
            extraSize += 8
        }
        if (extraSize > 0) {
            extra.remove(0x0001.toShort())
            val zip64Extra = ByteArray(ExtraData.Companion.FIXED_DATA_SIZE + extraSize)
            ZipUtil.shortToLittleEndian(zip64Extra, ExtraData.Companion.ID_OFFSET, 0x0001.toShort())
            ZipUtil.shortToLittleEndian(zip64Extra, ExtraData.Companion.LENGTH_OFFSET, extraSize.toShort())
            var offset: Int = ExtraData.Companion.FIXED_DATA_SIZE
            if (features.contains(ZipFileEntry.Feature.ZIP64_SIZE)) {
                ZipUtil.longToLittleEndian(zip64Extra, offset, entry.getSize())
                offset += 8
            }
            if (features.contains(ZipFileEntry.Feature.ZIP64_CSIZE)) {
                ZipUtil.longToLittleEndian(zip64Extra, offset, entry.getCompressedSize())
                offset += 8
            }
            if (features.contains(ZipFileEntry.Feature.ZIP64_OFFSET)) {
                ZipUtil.longToLittleEndian(zip64Extra, offset, entry.getLocalHeaderOffset())
            }
            extra.add(ExtraData(zip64Extra, 0))
        }
    }
}
