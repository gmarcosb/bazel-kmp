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

import java.util.*

/**
 * A full representation of a ZIP file entry.
 * 
 * 
 * See [ZIP Format](http://www.pkware.com/documents/casestudies/APPNOTE.TXT) for
 * a description of the entry fields. (Section 4.3.7 and 4.4)
 */
class ZipFileEntry {
    /** Compression method for ZIP entries.  */
    enum class Compression(private val value: Short, private val feature: Feature) {
        STORED(0.toShort(), Feature.STORED),
        DEFLATED(8.toShort(), Feature.DEFLATED);

        fun getValue(): Short {
            return value
        }

        fun getMinVersion(): Short {
            return feature.getMinVersion()
        }

        fun getFeature(): Feature {
            return feature
        }

        companion object {
            fun fromValue(value: Int): Compression? {
                for (c in Compression.entries) {
                    if (c.getValue().toInt() == value) {
                        return c
                    }
                }
                return null
            }
        }
    }

    /** General purpose bit flag for ZIP entries.  */
    enum class Flag(private val bit: Int) {
        DATA_DESCRIPTOR(3);

        fun getBit(): Int {
            return bit
        }
    }

    /** Zip file features that entries may use.  */
    internal enum class Feature(private val minVersion: Short) {
        DEFAULT(0x0a.toShort()),
        STORED(0x0a.toShort()),
        DEFLATED(0x14.toShort()),
        ZIP64_SIZE(0x2d.toShort()),
        ZIP64_CSIZE(0x2d.toShort()),
        ZIP64_OFFSET(0x2d.toShort());

        fun getMinVersion(): Short {
            return minVersion
        }

        companion object {
            fun getMinRequiredVersion(featureSet: EnumSet<Feature>): Short {
                var minVersion = Feature.DEFAULT.getMinVersion()
                for (feature in featureSet) {
                    minVersion = max(minVersion, feature.getMinVersion()) as Short
                }
                return minVersion
            }
        }
    }

    private var name: String? = null
    @kotlin.jvm.JvmField
    private var time: Long = -1
    private var crc: Long = -1
    private var size: Long = -1
    private var csize: Long = -1
    private var method: Compression? = null
    private var version: Short = -1
    private var versionNeeded: Short = -1
    private var flags: Short = 0
    private var internalAttributes: Short = 0
    @kotlin.jvm.JvmField
    private var externalAttributes = 0
    private var localHeaderOffset: Long = -1
    private var extra: ExtraDataList? = null
    private var comment: String? = null

    private val featureSet: EnumSet<Feature>

    /**
     * Creates a new ZIP entry with the specified name.
     * 
     * @throws NullPointerException if the entry name is null
     */
    constructor(name: String) {
        this.featureSet = EnumSet.of<Feature?>(Feature.DEFAULT)
        setName(name)
        setMethod(Compression.STORED)
        setExtra(ExtraDataList())
    }

    /**
     * Creates a new ZIP entry with fields taken from the specified ZIP entry.
     */
    constructor(e: ZipFileEntry) {
        this.name = e.getName()
        this.time = e.getTime()
        this.crc = e.getCrc()
        this.size = e.getSize()
        this.csize = e.getCompressedSize()
        this.method = e.getMethod()
        this.version = e.getVersion()
        this.versionNeeded = e.getVersionNeeded()
        this.flags = e.getFlags()
        this.internalAttributes = e.getInternalAttributes()
        this.externalAttributes = e.getExternalAttributes()
        this.localHeaderOffset = e.getLocalHeaderOffset()
        this.extra = ExtraDataList(e.getExtra())
        this.comment = e.getComment()
        this.featureSet = EnumSet.copyOf<Feature?>(e.getFeatureSet())
    }

    /**
     * Sets the name of the entry.
     */
    fun setName(name: String) {
        if (name == null) {
            throw NullPointerException()
        }
        this.name = name
    }

    /**
     * Returns the name of the entry.
     */
    fun getName(): String {
        return name!!
    }

    /**
     * Sets the modification time of the entry.
     * 
     * @param time the entry modification time in number of milliseconds since the epoch
     */
    fun setTime(time: Long) {
        this.time = time
    }

    /**
     * Returns the modification time of the entry, or -1 if not specified.
     */
    fun getTime(): Long {
        return time
    }

    /**
     * Sets the CRC-32 checksum of the uncompressed entry data.
     * 
     * @throws IllegalArgumentException if the specified CRC-32 value is less than 0 or greater than
     * 0xFFFFFFFF
     */
    fun setCrc(crc: Long) {
        require(!(crc < 0 || crc > 0xffffffffL)) { "invalid entry crc-32" }
        this.crc = crc
    }

    /**
     * Returns the CRC-32 checksum of the uncompressed entry data, or -1 if not known.
     */
    fun getCrc(): Long {
        return crc
    }

    /**
     * Sets the uncompressed size of the entry data in bytes.
     * 
     * @throws IllegalArgumentException if the specified size is less than 0
     */
    fun setSize(size: Long) {
        require(size >= 0) { "invalid entry size" }
        if (size >= 0xffffffffL) {
            featureSet.add(Feature.ZIP64_SIZE)
        } else {
            featureSet.remove(Feature.ZIP64_SIZE)
        }
        this.size = size
    }

    /**
     * Returns the uncompressed size of the entry data, or -1 if not known.
     */
    fun getSize(): Long {
        return size
    }

    /**
     * Sets the size of the compressed entry data in bytes.
     * 
     * @throws IllegalArgumentException if the specified size is less than 0
     */
    fun setCompressedSize(csize: Long) {
        require(csize >= 0) { "invalid entry size" }
        if (csize >= 0xffffffffL) {
            featureSet.add(Feature.ZIP64_CSIZE)
        } else {
            featureSet.remove(Feature.ZIP64_CSIZE)
        }
        this.csize = csize
    }

    /**
     * Returns the size of the compressed entry data, or -1 if not known. In the case of a stored
     * entry, the compressed size will be the same as the uncompressed size of the entry.
     */
    fun getCompressedSize(): Long {
        return csize
    }

    /**
     * Sets the compression method for the entry.
     */
    fun setMethod(method: Compression) {
        if (method == null) {
            throw NullPointerException()
        }
        if (this.method != null) {
            featureSet.remove(this.method!!.getFeature())
        }
        this.method = method
        featureSet.add(this.method!!.getFeature())
    }

    /**
     * Returns the compression method of the entry.
     */
    fun getMethod(): Compression? {
        return method
    }

    /**
     * Sets the made by version for the entry.
     */
    fun setVersion(version: Short) {
        this.version = version
    }

    /**
     * Returns the made by version of the entry, accounting for assigned version and feature set.
     */
    fun getVersion(): Short {
        return max(version, Feature.Companion.getMinRequiredVersion(featureSet)) as Short
    }

    /**
     * Sets the version needed to extract the entry.
     */
    fun setVersionNeeded(versionNeeded: Short) {
        this.versionNeeded = versionNeeded
    }

    /**
     * Returns the version needed to extract the entry, accounting for assigned version and feature
     * set.
     */
    fun getVersionNeeded(): Short {
        return max(versionNeeded, Feature.Companion.getMinRequiredVersion(featureSet)) as Short
    }

    /**
     * Sets the general purpose bit flags for the entry.
     */
    fun setFlags(flags: Short) {
        this.flags = flags
    }

    /**
     * Sets or clears the specified bit of the general purpose bit flags.
     * 
     * @param flag the flag to set or clear
     * @param set whether the flag is to be set or cleared
     */
    fun setFlag(flag: Flag, set: Boolean) {
        val mask = (1 shl flag.getBit()).toShort()
        if (set) {
            flags = flags.toInt() or mask.toInt()
        } else {
            flags = (flags.toInt() and mask.inv()).toShort()
        }
    }

    /**
     * Returns the general purpose bit flags of the entry.
     * 
     * 
     * See [ZIP Format](http://www.pkware.com/documents/casestudies/APPNOTE.TXT)
     * section 4.4.4.
     */
    fun getFlags(): Short {
        return flags
    }

    /**
     * Sets the internal file attributes of the entry.
     */
    fun setInternalAttributes(internalAttributes: Short) {
        this.internalAttributes = internalAttributes
    }

    /**
     * Returns the internal file attributes of the entry.
     */
    fun getInternalAttributes(): Short {
        return internalAttributes
    }

    /**
     * Sets the external file attributes of the entry.
     */
    fun setExternalAttributes(externalAttributes: Int) {
        this.externalAttributes = externalAttributes
    }

    /**
     * Returns the external file attributes of the entry.
     */
    fun getExternalAttributes(): Int {
        return externalAttributes
    }

    /**
     * Sets the file offset, in bytes, of the location of the local file header for the entry.
     * 
     * 
     * See [ZIP Format](http://www.pkware.com/documents/casestudies/APPNOTE.TXT)
     * section 4.4.16
     * 
     * @throws IllegalArgumentException if the specified local header offset is less than 0
     */
    fun setLocalHeaderOffset(localHeaderOffset: Long) {
        require(localHeaderOffset >= 0) { "invalid local header offset" }
        if (localHeaderOffset >= 0xffffffffL) {
            featureSet.add(Feature.ZIP64_OFFSET)
        } else {
            featureSet.remove(Feature.ZIP64_OFFSET)
        }
        this.localHeaderOffset = localHeaderOffset
    }

    /**
     * Returns the file offset of the local header of the entry.
     */
    fun getLocalHeaderOffset(): Long {
        return localHeaderOffset
    }

    /**
     * Sets the optional extra field data for the entry.
     * 
     * @throws IllegalArgumentException if the length of the specified extra field data is greater
     * than 0xFFFF bytes
     */
    fun setExtra(extra: ExtraDataList) {
        if (extra == null) {
            throw NullPointerException()
        }
        require(extra.getLength() <= 0xffff) { "invalid extra field length" }
        this.extra = extra
    }

    /**
     * Returns the extra field data for the entry.
     */
    fun getExtra(): ExtraDataList? {
        return extra
    }

    /**
     * Sets the optional comment string for the entry.
     */
    fun setComment(comment: String?) {
        this.comment = comment
    }

    /**
     * Returns the comment string for the entry, or null if none.
     */
    fun getComment(): String? {
        return comment
    }

    /**
     * Returns the feature set that this entry uses.
     */
    fun getFeatureSet(): EnumSet<Feature> {
        return featureSet
    }

    override fun toString(): String {
        return "ZipFileEntry[" + name + "]"
    }
}
