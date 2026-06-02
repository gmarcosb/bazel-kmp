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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import java.io.IOException

/**
 * Common methods to encode and decode varints and varlongs into ByteBuffers and
 * arrays.
 */
object VarInt {
    /**
     * Maximum encoded size of 32-bit positive integers (in bytes)
     */
    const val MAX_VARINT_SIZE: Int = 5

    /**
     * maximum encoded size of 64-bit longs, and negative 32-bit ints (in bytes)
     */
    const val MAX_VARLONG_SIZE: Int = 10

    /** Returns the encoding size in bytes of its input value.
     * @param i the integer to be measured
     * @return the encoding size in bytes of its input value
     */
    fun varIntSize(i: Int): Int {
        var i = i
        var result = 0
        do {
            result++
            i = i ushr 7
        } while (i != 0)
        return result
    }

    /**
     * Reads a varint from the current position of the given ByteBuffer and returns the decoded value
     * as 32 bit integer.
     * 
     * 
     * The position of the buffer is advanced to the first byte after the decoded varint.
     * 
     * @param src the ByteBuffer to get the var int from
     * @return The integer value of the decoded varint
     */
    fun getVarInt(src: java.nio.ByteBuffer): Int {
        var tmp: Int
        if ((src.get().also { tmp = it }) >= 0) {
            return tmp
        }
        var result = tmp and 0x7f
        if ((src.get().also { tmp = it }) >= 0) {
            result = result or (tmp shl 7)
        } else {
            result = result or ((tmp and 0x7f) shl 7)
            if ((src.get().also { tmp = it }) >= 0) {
                result = result or (tmp shl 14)
            } else {
                result = result or ((tmp and 0x7f) shl 14)
                if ((src.get().also { tmp = it }) >= 0) {
                    result = result or (tmp shl 21)
                } else {
                    result = result or ((tmp and 0x7f) shl 21)
                    result = result or ((src.get().also { tmp = it }) shl 28)
                    while (tmp < 0) {
                        // We get into this loop only in the case of overflow.
                        // By doing this, we can call getVarInt() instead of
                        // getVarLong() when we only need an int.
                        tmp = src.get().toInt()
                    }
                }
            }
        }
        return result
    }

    /**
     * Encodes an integer in a variable-length encoding, 7 bits per byte, into a destination byte[],
     * following the protocol buffer convention.
     * 
     * @param v the int value to write to sink
     * @param sink the sink buffer to write to
     * @param offset the offset within sink to begin writing
     * @return the updated offset after writing the varint
     */
    fun putVarInt(v: Int, sink: ByteArray, offset: Int): Int {
        var v = v
        var offset = offset
        do {
            // Encode next 7 bits + terminator bit
            val bits = v and 0x7F
            v = v ushr 7
            val b = (bits + (if (v != 0) 0x80 else 0)).toByte()
            sink[offset++] = b
        } while (v != 0)
        return offset
    }

    /**
     * Encodes an integer in a variable-length encoding, 7 bits per byte, and writes it to the given
     * OutputStream.
     * 
     * @param v the value to encode
     * @param outputStream the OutputStream to write to
     */
    @Throws(IOException::class)
    fun putVarInt(v: Int, outputStream: java.io.OutputStream) {
        val bytes = ByteArray(varIntSize(v))
        putVarInt(v, bytes, 0)
        outputStream.write(bytes)
    }

    /**
     * Returns the encoding size in bytes of its input value.
     * 
     * @param v the long to be measured
     * @return the encoding size in bytes of a given long value.
     */
    fun varLongSize(v: Long): Int {
        var v = v
        var result = 0
        do {
            result++
            v = v ushr 7
        } while (v != 0L)
        return result
    }

    /**
     * Reads an up to 64 bit long varint from the current position of the
     * given ByteBuffer and returns the decoded value as long.
     * 
     * 
     * The position of the buffer is advanced to the first byte after the
     * decoded varint.
     * 
     * @param src the ByteBuffer to get the var int from
     * @return The integer value of the decoded long varint
     */
    fun getVarLong(src: java.nio.ByteBuffer): Long {
        var tmp: Long
        if ((src.get().also { tmp = it }) >= 0) {
            return tmp
        }
        var result = tmp and 0x7fL
        if ((src.get().also { tmp = it }) >= 0) {
            result = result or (tmp shl 7)
        } else {
            result = result or ((tmp and 0x7fL) shl 7)
            if ((src.get().also { tmp = it }) >= 0) {
                result = result or (tmp shl 14)
            } else {
                result = result or ((tmp and 0x7fL) shl 14)
                if ((src.get().also { tmp = it }) >= 0) {
                    result = result or (tmp shl 21)
                } else {
                    result = result or ((tmp and 0x7fL) shl 21)
                    if ((src.get().also { tmp = it }) >= 0) {
                        result = result or (tmp shl 28)
                    } else {
                        result = result or ((tmp and 0x7fL) shl 28)
                        if ((src.get().also { tmp = it }) >= 0) {
                            result = result or (tmp shl 35)
                        } else {
                            result = result or ((tmp and 0x7fL) shl 35)
                            if ((src.get().also { tmp = it }) >= 0) {
                                result = result or (tmp shl 42)
                            } else {
                                result = result or ((tmp and 0x7fL) shl 42)
                                if ((src.get().also { tmp = it }) >= 0) {
                                    result = result or (tmp shl 49)
                                } else {
                                    result = result or ((tmp and 0x7fL) shl 49)
                                    if ((src.get().also { tmp = it }) >= 0) {
                                        result = result or (tmp shl 56)
                                    } else {
                                        result = result or ((tmp and 0x7fL) shl 56)
                                        result = result or ((src.get().toLong()) shl 63)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return result
    }

    /**
     * Encodes a long integer in a variable-length encoding, 7 bits per byte, to a
     * ByteBuffer sink.
     * @param v the value to encode
     * @param sink the ByteBuffer to add the encoded value
     */
    fun putVarLong(v: Long, sink: java.nio.ByteBuffer) {
        var v = v
        while (true) {
            val bits = (v.toInt()) and 0x7f
            v = v ushr 7
            if (v == 0L) {
                sink.put(bits.toByte())
                return
            }
            sink.put((bits or 0x80).toByte())
        }
    }

    @Throws(IOException::class)
    fun putVarLong(v: Long, outputStream: java.io.OutputStream) {
        val bytes = ByteArray(varLongSize(v))
        val sink: java.nio.ByteBuffer = java.nio.ByteBuffer.wrap(bytes)
        VarInt.putVarLong(v, sink)
        outputStream.write(bytes)
    }
}
