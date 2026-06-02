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
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipException

/** A utility class for reading and writing [ZipFileEntry]s from byte arrays.  */
object ZipUtil {
    /**
     * Midnight Jan 1st 1980. Uses the current time zone as the DOS format does not support time zones
     * and will always assume the current zone.
     */
    val DOS_EPOCH: Long = GregorianCalendar(1980, Calendar.JANUARY, 1, 0, 0, 0).getTimeInMillis()

    /** 23:59:59 Dec 31st 2107. The maximum date representable in DOS format.  */
    val MAX_DOS_DATE: Long = GregorianCalendar(2107, Calendar.DECEMBER, 31, 23, 59, 59).getTimeInMillis()

    /* DOS format timestamp field offsets. */
    private const val DOS_MINUTE_OFFSET = 5
    private const val DOS_HOUR_OFFSET = 11
    private const val DOS_DAY_OFFSET = 16
    private const val DOS_MONTH_OFFSET = 21
    private const val DOS_YEAR_OFFSET = 25

    /** Converts a integral value to the corresponding little endian array.  */
    private fun integerToLittleEndian(buf: ByteArray, offset: Int, value: Long, numBytes: Int): ByteArray {
        for (i in 0..<numBytes) {
            buf[i + offset] = ((value and (0xffL shl (i * 8))) shr (i * 8)).toByte()
        }
        return buf
    }

    /** Converts a short to the corresponding 2-byte little endian array.  */
    fun shortToLittleEndian(value: Short): ByteArray {
        return integerToLittleEndian(ByteArray(2), 0, value.toLong(), 2)
    }

    /** Writes a short to the buffer as a 2-byte little endian array starting at offset.  */
    fun shortToLittleEndian(buf: ByteArray, offset: Int, value: Short): ByteArray {
        return integerToLittleEndian(buf, offset, value.toLong(), 2)
    }

    /** Converts an int to the corresponding 4-byte little endian array.  */
    fun intToLittleEndian(value: Int): ByteArray {
        return integerToLittleEndian(ByteArray(4), 0, value.toLong(), 4)
    }

    /** Writes an int to the buffer as a 4-byte little endian array starting at offset.  */
    fun intToLittleEndian(buf: ByteArray, offset: Int, value: Int): ByteArray {
        return integerToLittleEndian(buf, offset, value.toLong(), 4)
    }

    /** Converts a long to the corresponding 8-byte little endian array.  */
    fun longToLittleEndian(value: Long): ByteArray {
        return integerToLittleEndian(ByteArray(8), 0, value, 8)
    }

    /** Writes a long to the buffer as a 8-byte little endian array starting at offset.  */
    fun longToLittleEndian(buf: ByteArray, offset: Int, value: Long): ByteArray {
        return integerToLittleEndian(buf, offset, value, 8)
    }

    /** Reads 16 bits in little-endian byte order from the buffer at the given offset.  */
    fun get16(source: ByteArray, offset: Int): Short {
        val a = source[offset + 0].toInt() and 0xff
        val b = source[offset + 1].toInt() and 0xff
        return ((b shl 8) or a).toShort()
    }

    /** Reads 32 bits in little-endian byte order from the buffer at the given offset.  */
    fun get32(source: ByteArray, offset: Int): Int {
        val a = source[offset + 0].toInt() and 0xff
        val b = source[offset + 1].toInt() and 0xff
        val c = source[offset + 2].toInt() and 0xff
        val d = source[offset + 3].toInt() and 0xff
        return (d shl 24) or (c shl 16) or (b shl 8) or a
    }

    /** Reads 64 bits in little-endian byte order from the buffer at the given offset.  */
    fun get64(source: ByteArray, offset: Int): Long {
        val a = source[offset + 0].toLong() and 0xffL
        val b = source[offset + 1].toLong() and 0xffL
        val c = source[offset + 2].toLong() and 0xffL
        val d = source[offset + 3].toLong() and 0xffL
        val e = source[offset + 4].toLong() and 0xffL
        val f = source[offset + 5].toLong() and 0xffL
        val g = source[offset + 6].toLong() and 0xffL
        val h = source[offset + 7].toLong() and 0xffL
        return (h shl 56) or (g shl 48) or (f shl 40) or (e shl 32) or (d shl 24) or (c shl 16) or (b shl 8) or a
    }

    /**
     * Reads an unsigned short in little-endian byte order from the buffer at the given offset.
     * Casts to an int to allow proper numerical comparison.
     */
    fun getUnsignedShort(source: ByteArray, offset: Int): Int {
        return get16(source, offset).toInt() and 0xffff
    }

    /**
     * Reads an unsigned int in little-endian byte order from the buffer at the given offset.
     * Casts to a long to allow proper numerical comparison.
     */
    fun getUnsignedInt(source: ByteArray, offset: Int): Long {
        return get32(source, offset).toLong() and 0xffffffffL
    }

    /**
     * Reads an unsigned long in little-endian byte order from the buffer at the given offset.
     * Performs bounds checking to see if the unsigned long will be properly represented in Java's
     * signed value.
     */
    @Throws(ZipException::class)
    fun getUnsignedLong(source: ByteArray, offset: Int): Long {
        val result = get64(source, offset)
        if (result < 0) {
            throw ZipException(
                "The requested unsigned long value is too large for Java's signed"
                        + "values. This Zip file is unsupported"
            )
        }
        return result
    }

    /** Checks if the unix timestamp is representable as a valid DOS timestamp.
     * 
     * 
     * See [ZIP Format](http://www.pkware.com/documents/casestudies/APPNOTE.TXT) for
     * a general description of the date a time fields (Section 4.4.6) and
     * [DOS date
     * format](https://msdn.microsoft.com/en-us/library/windows/desktop/ms724247.aspx) for a detailed description of the format.
     */
    fun isValidInDos(timeMillis: Long): Boolean {
        val time = Calendar.getInstance()
        time.setTimeInMillis(timeMillis)
        val minTime = Calendar.getInstance()
        minTime.setTimeInMillis(DOS_EPOCH)
        val maxTime = Calendar.getInstance()
        maxTime.setTimeInMillis(MAX_DOS_DATE)
        return (!time.before(minTime) && !time.after(maxTime))
    }

    /** Converts a unix timestamp into a 32-bit DOS timestamp.  */
    fun unixToDosTime(timeMillis: Long): Int {
        val time = Calendar.getInstance()
        time.setTimeInMillis(timeMillis)

        if (!isValidInDos(timeMillis)) {
            val df: DateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
            throw IllegalArgumentException(
                String.format(
                    "%s is not representable in the DOS time"
                            + " format. It must be in the range %s to %s", df.format(time.getTime()),
                    df.format(Date(DOS_EPOCH)), df.format(Date(MAX_DOS_DATE))
                )
            )
        }

        var dos = time.get(Calendar.SECOND) / 2
        dos = dos or (time.get(Calendar.MINUTE) shl DOS_MINUTE_OFFSET)
        dos = dos or (time.get(Calendar.HOUR_OF_DAY) shl DOS_HOUR_OFFSET)
        dos = dos or (time.get(Calendar.DAY_OF_MONTH) shl DOS_DAY_OFFSET)
        dos = dos or ((time.get(Calendar.MONTH) + 1) shl DOS_MONTH_OFFSET)
        dos = dos or ((time.get(Calendar.YEAR) - 1980) shl DOS_YEAR_OFFSET)
        return dos
    }

    /** Converts a 32-bit DOS timestamp into a unix timestamp.  */
    fun dosToUnixTime(timestamp: Int): Long {
        val time = Calendar.getInstance()
        time.clear()
        time.set(Calendar.SECOND, (timestamp and 0x1f) * 2)
        time.set(Calendar.MINUTE, (timestamp shr DOS_MINUTE_OFFSET) and 0x3f)
        time.set(Calendar.HOUR_OF_DAY, (timestamp shr DOS_HOUR_OFFSET) and 0x1f)
        time.set(Calendar.DAY_OF_MONTH, (timestamp shr DOS_DAY_OFFSET) and 0x1f)
        time.set(Calendar.MONTH, ((timestamp shr DOS_MONTH_OFFSET) and 0x0f) - 1)
        time.set(Calendar.YEAR, ((timestamp shr DOS_YEAR_OFFSET) and 0x7f) + 1980)
        return time.getTimeInMillis()
    }

    /** Checks if array starts with target.  */
    fun arrayStartsWith(array: ByteArray?, target: ByteArray?): Boolean {
        if (array == null) {
            return false
        }
        if (target == null) {
            return true
        }
        if (target.size > array.size) {
            return false
        }
        for (i in target.indices) {
            if (array[i] != target[i]) {
                return false
            }
        }
        return true
    }

    /** Read from the input stream into the array until it is full.  */
    @Throws(IOException::class)
    fun readFully(`in`: InputStream, b: ByteArray): Int {
        return readFully(`in`, b, 0, b.size)
    }

    /** Read from the input stream into the array starting at off until len bytes have been read.  */
    @Throws(IOException::class)
    fun readFully(`in`: InputStream, b: ByteArray?, off: Int, len: Int): Int {
        if (len < 0) {
            throw IndexOutOfBoundsException()
        }
        var n = 0
        while (n < len) {
            val count = `in`.read(b, off + n, len - n)
            if (count < 0) {
                return n
            }
            n += count
        }
        return n
    }
}
