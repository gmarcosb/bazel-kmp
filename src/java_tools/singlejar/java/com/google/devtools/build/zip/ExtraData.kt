// Copyright 2014 The Bazel Authors. All rights reserved.
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

import java.util.*

/**
 * A holder class for extra data in a ZIP entry.
 */
class ExtraData {
    private val index: Int
    private val buffer: ByteArray

    /**
     * Creates a new [ExtraData] record with the specified id and data.
     * 
     * @param id the ID tag for this extra data record
     * @param data the data payload for this extra data record
     */
    constructor(id: Short, data: ByteArray) {
        require(data.size <= 0xffff) {
            String.format(
                "Data is too long. Is %d; max %d",
                data.size, 0xffff
            )
        }
        index = 0
        buffer = ByteArray(FIXED_DATA_SIZE + data.size)
        ZipUtil.shortToLittleEndian(buffer, ID_OFFSET, id)
        ZipUtil.shortToLittleEndian(buffer, LENGTH_OFFSET, data.size.toShort())
        System.arraycopy(data, 0, buffer, FIXED_DATA_SIZE, data.size)
    }

    /**
     * Creates a new [ExtraData] record using the buffer as the backing data store.
     * 
     * 
     * *NOTE:* does not perform any defensive copying. Any modification to the buffer will
     * alter the extra data record and can make it invalid.
     * 
     * @param buffer the array containing the extra data record
     * @param index the index where the extra data record is located
     * @throws IllegalArgumentException if buffer does not contain a well formed extra data record
     * at index
     */
    internal constructor(buffer: ByteArray, index: Int) {
        require(index < buffer.size) { "index past end of buffer" }
        require(buffer.size - index >= FIXED_DATA_SIZE) { "incomplete extra data entry in buffer" }
        val length = ZipUtil.getUnsignedShort(buffer, index + LENGTH_OFFSET)
        require(buffer.size - index - FIXED_DATA_SIZE >= length) { "incomplete extra data entry in buffer" }
        this.buffer = buffer
        this.index = index
    }

    /** Returns the Id of the extra data record.  */
    fun getId(): Short {
        return ZipUtil.get16(buffer, index + ID_OFFSET)
    }

    /** Returns the total length of the extra data record in bytes.  */
    fun getLength(): Int {
        return getDataLength() + FIXED_DATA_SIZE
    }

    /** Returns the length of the data payload of the extra data record in bytes.  */
    fun getDataLength(): Int {
        return ZipUtil.getUnsignedShort(buffer, index + LENGTH_OFFSET)
    }

    /** Returns a byte array copy of the data payload.  */
    fun getData(): ByteArray? {
        return Arrays.copyOfRange(buffer, index + FIXED_DATA_SIZE, index + getLength())
    }

    /** Returns a byte array copy of the entire record.  */
    fun getBytes(): ByteArray? {
        return Arrays.copyOfRange(buffer, index, index + getLength())
    }

    /** Returns the byte at index from the entire record.  */
    fun getByte(index: Int): Byte {
        return buffer[this.index + index]
    }

    companion object {
        const val ID_OFFSET: Int = 0
        const val LENGTH_OFFSET: Int = 2
        const val FIXED_DATA_SIZE: Int = 4
    }
}
