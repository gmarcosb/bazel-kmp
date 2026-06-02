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

internal object Zip64EndOfCentralDirectory {
    const val SIGNATURE: Int = 0x06064b50
    const val FIXED_DATA_SIZE: Int = 56
    const val SIGNATURE_OFFSET: Int = 0
    const val SIZE_OFFSET: Int = 4
    const val VERSION_OFFSET: Int = 12
    const val VERSION_NEEDED_OFFSET: Int = 14
    const val DISK_NUMBER_OFFSET: Int = 16
    const val CD_DISK_OFFSET: Int = 20
    const val DISK_ENTRIES_OFFSET: Int = 24
    const val TOTAL_ENTRIES_OFFSET: Int = 32
    const val CD_SIZE_OFFSET: Int = 40
    const val CD_OFFSET_OFFSET: Int = 48

    /**
     * Read the Zip64 end of central directory record from the input stream and parse additional
     * [ZipFileData] from it.
     */
    @Throws(IOException::class)
    fun read(`in`: InputStream?, file: ZipFileData): ZipFileData {
        if (file == null) {
            throw NullPointerException()
        }

        val fixedSizeData = ByteArray(FIXED_DATA_SIZE)
        if (ZipUtil.readFully(`in`, fixedSizeData) != FIXED_DATA_SIZE) {
            throw ZipException(
                "Unexpected end of file while reading Zip64 End of Central Directory Record."
            )
        }
        if (!ZipUtil.arrayStartsWith(fixedSizeData, ZipUtil.intToLittleEndian(SIGNATURE))) {
            throw ZipException(
                String.format(
                    "Malformed Zip64 End of Central Directory; does not start with %08x", SIGNATURE
                )
            )
        }
        file.setZip64(true)
        file.setCentralDirectoryOffset(ZipUtil.getUnsignedLong(fixedSizeData, CD_OFFSET_OFFSET))
        file.setExpectedEntries(ZipUtil.getUnsignedLong(fixedSizeData, TOTAL_ENTRIES_OFFSET))
        return file
    }

    /**
     * Generates the raw byte data of the Zip64 end of central directory record for the file.
     */
    fun create(file: ZipFileData): ByteArray {
        val buf = ByteArray(FIXED_DATA_SIZE)
        ZipUtil.intToLittleEndian(buf, SIGNATURE_OFFSET, SIGNATURE)
        ZipUtil.longToLittleEndian(buf, SIZE_OFFSET, (FIXED_DATA_SIZE - 12).toLong())
        ZipUtil.shortToLittleEndian(buf, VERSION_OFFSET, 0x2d.toShort())
        ZipUtil.shortToLittleEndian(buf, VERSION_NEEDED_OFFSET, 0x2d.toShort())
        ZipUtil.intToLittleEndian(buf, DISK_NUMBER_OFFSET, 0)
        ZipUtil.intToLittleEndian(buf, CD_DISK_OFFSET, 0)
        ZipUtil.longToLittleEndian(buf, DISK_ENTRIES_OFFSET, file.getNumEntries())
        ZipUtil.longToLittleEndian(buf, TOTAL_ENTRIES_OFFSET, file.getNumEntries())
        ZipUtil.longToLittleEndian(buf, CD_SIZE_OFFSET, file.getCentralDirectorySize())
        ZipUtil.longToLittleEndian(buf, CD_OFFSET_OFFSET, file.getCentralDirectoryOffset())
        return buf
    }
}
