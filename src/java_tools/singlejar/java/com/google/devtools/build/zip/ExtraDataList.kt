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
import kotlin.collections.LinkedHashMap

/**
 * A list of [ExtraData] records to be associated with a [ZipFileEntry]. Supports
 * creating the list directly from a byte array and modifying the list without reallocating the
 * underlying buffer.
 */
class ExtraDataList {
    private val entries: LinkedHashMap<Short?, ExtraData>

    /**
     * Create a new empty extra data list.
     * 
     * 
     * *NOTE:* entries in a list created this way will be backed by their own storage.
     */
    constructor() {
        entries = LinkedHashMap<Short?, ExtraData>()
    }

    constructor(other: ExtraDataList) {
        this.entries = LinkedHashMap<Short?, ExtraData>()
        this.entries.putAll(other.entries)
    }

    /**
     * Creates an extra data list from the given extra data records.
     * 
     * 
     * *NOTE:* entries in a list created this way will be backed by their own storage.
     * 
     * @param extra the extra data records
     */
    constructor(vararg extra: ExtraData) : this() {
        for (e in extra) {
            add(e)
        }
    }

    /**
     * Creates an extra data list from the entries contained in the given array.
     * 
     * 
     * *NOTE:* entries in a list created this way will be backed by the buffer. No defensive
     * copying is performed.
     * 
     * @param buffer the array containing sequential extra data entries
     */
    constructor(buffer: ByteArray) {
        require(buffer.size <= 0xffff) { "invalid extra field length" }
        entries = LinkedHashMap<Short?, ExtraData>()
        var index = 0
        while (index < buffer.size) {
            val extra = ExtraData(buffer, index)
            entries.put(extra.getId(), extra)
            index += extra.getLength()
        }
    }

    /**
     * Returns the extra data record with the specified id, or null if it does not exist.
     */
    fun get(id: Short): ExtraData? {
        return entries.get(id)
    }

    /**
     * Removes and returns the extra data record with the specified id if it exists.
     * 
     * 
     * *NOTE:* does not modify the underlying storage, only marks the record as removed.
     */
    fun remove(id: Short): ExtraData? {
        return entries.remove(id)
    }

    /**
     * Returns if the list contains an extra data record with the specified id.
     */
    fun contains(id: Short): Boolean {
        return entries.containsKey(id)
    }

    /**
     * Adds a new entry to the end of the list.
     * 
     * @throws IllegalArgumentException if adding the entry will make the list too long for the ZIP
     * format
     */
    fun add(entry: ExtraData) {
        require(getLength() + entry.getLength() <= 0xffff) { "adding entry will make the extra field be too long" }
        entries.put(entry.getId(), entry)
    }

    /**
     * Returns the overall length of the list in bytes.
     */
    fun getLength(): Int {
        var length = 0
        for (e in entries.values) {
            length += e.getLength()
        }
        return length
    }

    /**
     * Creates and returns a byte array of the extra data list.
     */
    fun getBytes(): ByteArray {
        val extra = ByteArray(getLength())
        try {
            getByteStream().read(extra)
        } catch (impossible: IOException) {
            throw AssertionError(impossible)
        }
        return extra
    }

    /**
     * Returns an input stream for reading the extra data list entries.
     */
    fun getByteStream(): InputStream {
        return object : InputStream() {
            private val itr = entries.values.iterator()
            private var entry: ExtraData? = null
            private var index = 0

            override fun read(): Int {
                if (entry == null) {
                    if (itr.hasNext()) {
                        entry = itr.next()
                        index = 0
                    } else {
                        return -1
                    }
                }
                val `val` = entry!!.getByte(index++)
                if (index >= entry!!.getLength()) {
                    entry = null
                }
                return `val`.toInt() and 0xff
            }
        }
    }

    companion object {
        const val ZIP64: Short = 0x0001
        const val EXTENDED_TIMESTAMP: Short = 0x5455

        // Some documentation says that this is actually 0x7855, but zip files do not seem to corroborate
        // this
        const val INFOZIP_UNIX_NEW: Short = 0x7875
    }
}
