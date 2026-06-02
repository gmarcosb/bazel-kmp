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
import java.util.zip.ZipException

internal object LocalFileHeader {
    const val SIGNATURE: Int = 0x04034b50
    const val FIXED_DATA_SIZE: Int = 30
    const val SIGNATURE_OFFSET: Int = 0
    const val VERSION_OFFSET: Int = 4
    const val FLAGS_OFFSET: Int = 6
    const val METHOD_OFFSET: Int = 8
    const val MOD_TIME_OFFSET: Int = 10
    const val CRC_OFFSET: Int = 14
    const val COMPRESSED_SIZE_OFFSET: Int = 18
    const val UNCOMPRESSED_SIZE_OFFSET: Int = 22
    const val FILENAME_LENGTH_OFFSET: Int = 26
    const val EXTRA_FIELD_LENGTH_OFFSET: Int = 28
    const val VARIABLE_DATA_OFFSET: Int = 30

    /**
     * Generates the raw byte data of the local file header for the [ZipFileEntry]. Uses the
     * specified [ZipFileData] to encode the file name and comment.
     * @throws IOException
     */
    @Throws(IOException::class)
    fun create(entry: ZipFileEntry, file: ZipFileData, allowZip64: Boolean): ByteArray {
        val name: ByteArray = entry.getName().toByteArray(file.getCharset())

        // We don't do a defensive copy here so that later, when we write the central directory entry,
        // the changes we make here take effect.
        // TODO(bazel-team): This seems like a bug. Investigate.
        val extra = entry.getExtra()

        val features = entry.getFeatureSet()
        var size = entry.getSize().toInt()
        var csize = entry.getCompressedSize().toInt()

        if (features.contains(ZipFileEntry.Feature.ZIP64_SIZE) || features.contains(ZipFileEntry.Feature.ZIP64_CSIZE)) {
            if (!allowZip64) {
                throw ZipException(
                    String.format(
                        "Writing an entry of size %d(%d) without Zip64"
                                + " extensions is not supported.", entry.getSize(), entry.getCompressedSize()
                    )
                )
            }
            extra.remove(0x0001.toShort())
            var extraSize = 0
            if (features.contains(ZipFileEntry.Feature.ZIP64_SIZE)) {
                size = -1
                extraSize += 8
            }
            if (features.contains(ZipFileEntry.Feature.ZIP64_CSIZE)) {
                csize = -1
                extraSize += 8
            }
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
            extra.add(ExtraData(zip64Extra, 0))
        } else {
            extra.remove(0x0001.toShort())
        }

        extra.remove(ExtraDataList.Companion.EXTENDED_TIMESTAMP)
        extra.remove(ExtraDataList.Companion.INFOZIP_UNIX_NEW)

        val buf = ByteArray(FIXED_DATA_SIZE + name.size + extra.getLength())
        ZipUtil.intToLittleEndian(buf, SIGNATURE_OFFSET, SIGNATURE)
        ZipUtil.shortToLittleEndian(buf, VERSION_OFFSET, entry.getVersionNeeded())
        ZipUtil.shortToLittleEndian(buf, FLAGS_OFFSET, entry.getFlags())
        ZipUtil.shortToLittleEndian(buf, METHOD_OFFSET, entry.getMethod().getValue())
        ZipUtil.intToLittleEndian(buf, MOD_TIME_OFFSET, ZipUtil.unixToDosTime(entry.getTime()))
        ZipUtil.intToLittleEndian(buf, CRC_OFFSET, (entry.getCrc() and 0xffffffffL).toInt())
        ZipUtil.intToLittleEndian(buf, COMPRESSED_SIZE_OFFSET, csize)
        ZipUtil.intToLittleEndian(buf, UNCOMPRESSED_SIZE_OFFSET, size)
        ZipUtil.shortToLittleEndian(buf, FILENAME_LENGTH_OFFSET, name.size.toShort())
        ZipUtil.shortToLittleEndian(buf, EXTRA_FIELD_LENGTH_OFFSET, extra.getLength().toShort())
        System.arraycopy(name, 0, buf, FIXED_DATA_SIZE, name.size)
        ZipUtil.readFully(extra.getByteStream(), buf, FIXED_DATA_SIZE + name.size, extra.getLength())

        return buf
    }
}
