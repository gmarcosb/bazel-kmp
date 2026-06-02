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
import java.util.zip.ZipException

internal object EndOfCentralDirectoryRecord {
    const val SIGNATURE: Int = 0x06054b50
    const val FIXED_DATA_SIZE: Int = 22
    const val SIGNATURE_OFFSET: Int = 0
    const val DISK_NUMBER_OFFSET: Int = 4
    const val CD_DISK_OFFSET: Int = 6
    const val DISK_ENTRIES_OFFSET: Int = 8
    const val TOTAL_ENTRIES_OFFSET: Int = 10
    const val CD_SIZE_OFFSET: Int = 12
    const val CD_OFFSET_OFFSET: Int = 16
    const val COMMENT_LENGTH_OFFSET: Int = 20

    /**
     * Read the end of central directory record from the input stream and parse [ZipFileData]
     * from it.
     */
    @Throws(IOException::class)
    fun read(`in`: InputStream?, file: ZipFileData): ZipFileData {
        if (file == null) {
            throw NullPointerException()
        }

        val fixedSizeData = ByteArray(FIXED_DATA_SIZE)
        if (ZipUtil.readFully(`in`, fixedSizeData) != FIXED_DATA_SIZE) {
            throw ZipException(
                "Unexpected end of file while reading End of Central Directory Record."
            )
        }
        if (!ZipUtil.arrayStartsWith(fixedSizeData, ZipUtil.intToLittleEndian(SIGNATURE))) {
            throw ZipException(
                String.format(
                    "Malformed End of Central Directory Record; does not start with %08x", SIGNATURE
                )
            )
        }

        val comment = ByteArray(ZipUtil.getUnsignedShort(fixedSizeData, COMMENT_LENGTH_OFFSET))
        if (comment.size > 0 && ZipUtil.readFully(`in`, comment) != comment.size) {
            throw ZipException(
                "Unexpected end of file while reading End of Central Directory Record."
            )
        }
        val diskNumber = ZipUtil.get16(fixedSizeData, DISK_NUMBER_OFFSET)
        val centralDirectoryDisk = ZipUtil.get16(fixedSizeData, CD_DISK_OFFSET)
        val entriesOnDisk = ZipUtil.get16(fixedSizeData, DISK_ENTRIES_OFFSET)
        val totalEntries = ZipUtil.get16(fixedSizeData, TOTAL_ENTRIES_OFFSET)
        val centralDirectorySize = ZipUtil.get32(fixedSizeData, CD_SIZE_OFFSET)
        val centralDirectoryOffset = ZipUtil.get32(fixedSizeData, CD_OFFSET_OFFSET)
        if (diskNumber.toInt() == -1 || centralDirectoryDisk.toInt() == -1 || entriesOnDisk.toInt() == -1 || totalEntries.toInt() == -1 || centralDirectorySize == -1 || centralDirectoryOffset == -1) {
            file.setMaybeZip64(true)
        }
        file.setComment(comment)
        file.setCentralDirectorySize(ZipUtil.getUnsignedInt(fixedSizeData, CD_SIZE_OFFSET))
        file.setCentralDirectoryOffset(ZipUtil.getUnsignedInt(fixedSizeData, CD_OFFSET_OFFSET))
        file.setExpectedEntries(ZipUtil.getUnsignedShort(fixedSizeData, TOTAL_ENTRIES_OFFSET).toLong())
        return file
    }

    /**
     * Generates the raw byte data of the end of central directory record for the specified
     * [ZipFileData].
     * @throws ZipException if the file comment is too long
     */
    @Throws(ZipException::class)
    fun create(file: ZipFileData, allowZip64: Boolean): ByteArray {
        val comment = file.getBytes(file.getComment())

        val buf = ByteArray(FIXED_DATA_SIZE + comment.size)

        // Allow writing of Zip file without Zip64 extensions for large archives as a special case
        // since many reading implementations can handle this.
        val numEntries = (if (file.getNumEntries() > 0xffff && allowZip64)
            -1
        else
            file.getNumEntries()).toShort()
        val cdSize = (if (file.getCentralDirectorySize() > 0xffffffffL && allowZip64)
            -1
        else
            file.getCentralDirectorySize()).toInt()
        val cdOffset = (if (file.getCentralDirectoryOffset() > 0xffffffffL && allowZip64)
            -1
        else
            file.getCentralDirectoryOffset()).toInt()
        ZipUtil.intToLittleEndian(buf, SIGNATURE_OFFSET, SIGNATURE)
        ZipUtil.shortToLittleEndian(buf, DISK_NUMBER_OFFSET, 0.toShort())
        ZipUtil.shortToLittleEndian(buf, CD_DISK_OFFSET, 0.toShort())
        ZipUtil.shortToLittleEndian(buf, DISK_ENTRIES_OFFSET, numEntries)
        ZipUtil.shortToLittleEndian(buf, TOTAL_ENTRIES_OFFSET, numEntries)
        ZipUtil.intToLittleEndian(buf, CD_SIZE_OFFSET, cdSize)
        ZipUtil.intToLittleEndian(buf, CD_OFFSET_OFFSET, cdOffset)
        ZipUtil.shortToLittleEndian(buf, COMMENT_LENGTH_OFFSET, comment.size.toShort())
        System.arraycopy(comment, 0, buf, FIXED_DATA_SIZE, comment.size)

        return buf
    }
}
