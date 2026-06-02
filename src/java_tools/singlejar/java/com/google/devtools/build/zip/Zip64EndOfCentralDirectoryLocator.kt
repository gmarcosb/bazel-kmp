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

internal object Zip64EndOfCentralDirectoryLocator {
    const val SIGNATURE: Int = 0x07064b50
    const val FIXED_DATA_SIZE: Int = 20
    const val SIGNATURE_OFFSET: Int = 0
    const val ZIP64_EOCD_DISK_OFFSET: Int = 4
    const val ZIP64_EOCD_OFFSET_OFFSET: Int = 8
    const val DISK_NUMBER_OFFSET: Int = 16

    /**
     * Read the Zip64 end of central directory locator from the input stream and parse additional
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
                "Unexpected end of file while reading Zip64 End of Central Directory Locator."
            )
        }
        if (!ZipUtil.arrayStartsWith(fixedSizeData, ZipUtil.intToLittleEndian(SIGNATURE))) {
            throw ZipException(
                String.format(
                    "Malformed Zip64 Central Directory Locator; does not start with %08x", SIGNATURE
                )
            )
        }
        file.setZip64(true)
        file.setZip64EndOfCentralDirectoryOffset(
            ZipUtil.getUnsignedLong(fixedSizeData, ZIP64_EOCD_OFFSET_OFFSET)
        )
        return file
    }

    /**
     * Generates the raw byte data of the Zip64 end of central directory locator for the file.
     */
    fun create(file: ZipFileData): ByteArray {
        val buf = ByteArray(FIXED_DATA_SIZE)
        ZipUtil.intToLittleEndian(buf, SIGNATURE_OFFSET, SIGNATURE)
        ZipUtil.intToLittleEndian(buf, ZIP64_EOCD_DISK_OFFSET, 0)
        ZipUtil.longToLittleEndian(
            buf, ZIP64_EOCD_OFFSET_OFFSET,
            file.getZip64EndOfCentralDirectoryOffset()
        )
        ZipUtil.intToLittleEndian(buf, DISK_NUMBER_OFFSET, 1)
        return buf
    }
}
