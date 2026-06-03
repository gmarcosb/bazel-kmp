// Copyright 2016 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.coverage

import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder.get

/**
 * Abstracts bit field operations.
 */
class BitField @kotlin.jvm.JvmOverloads constructor(bytes: ByteArray = ByteArray(0)) {
    private var bytes: ByteArray

    init {
        this.bytes = bytes.clone()
    }

    /**
     * Returns a copy of the underlying byte array.
     * 
     * @return byte array copy
     */
    fun getBytes(): ByteArray {
        return bytes.clone()
    }

    /**
     * Sets or clears a bit at the given index.
     * 
     * @param index bit index
     */
    fun setBit(index: Int) {
        setBit(index, true)
    }

    /**
     * Sets or clears a bit at the given index.
     * 
     * @param index bit index
     */
    private fun setBit(index: Int, isSet: Boolean) {
        val byteIndex = index / 8
        val newByteSize = byteIndex + 1
        if (bytes.size < newByteSize) {
            bytes = bytes.copyOf(newByteSize)
        }

        val bitIndex = index % 8
        val mask = 1 shl bitIndex

        if (isSet) {
            bytes[byteIndex] = (bytes[byteIndex].toInt() or mask).toByte()
        } else {
            bytes[byteIndex] = (bytes[byteIndex].toInt() and mask.inv()).toByte()
        }
    }

    /**
     * Clears a bit at the given index
     * 
     * @param index bit index
     */
    fun clearBit(index: Int) {
        setBit(index, false)
    }

    /**
     * Checks whether a bit at the given index is set.
     * 
     * @param index bit index
     * @return true if set, false otherwise
     */
    fun isBitSet(index: Int): Boolean {
        val byteIndex = index / 8

        if (byteIndex >= bytes.size) {
            return false
        }

        val bitIndex = index % 8
        val mask = 1 shl bitIndex
        return (bytes[byteIndex].toInt() and mask) != 0
    }

    /** Performs a non-destructive bit-wise "and" of this bit field with another one.  */
    fun and(other: BitField): BitField {
        val size: Int = min(bytes.size, other.bytes.size)
        val result = ByteArray(size)

        for (i in 0..<size) {
            result[i] = (bytes[i].toInt() and other.bytes[i].toInt()).toByte()
        }

        return com.google.testing.coverage.BitField(result)
    }

    /**
     * Performs a non-destructive bit-wise merge of this bit field and another one.
     * 
     * @param other the other bit field
     * @return this bit field
     */
    fun or(other: BitField): BitField {
        val largerArray: ByteArray?
        val smallerArray: ByteArray?
        if (bytes.size < other.bytes.size) {
            largerArray = other.bytes
            smallerArray = bytes
        } else {
            largerArray = bytes
            smallerArray = other.bytes
        }

        // Start out with a copy of the larger of the two arrays.
        val result: ByteArray = largerArray.copyOf(largerArray.size)

        for (i in smallerArray.indices) {
            result[i] = result[i].toInt() or smallerArray[i].toInt()
        }

        return com.google.testing.coverage.BitField(result)
    }

    /**
     * Compares two bit fields for equality.
     * 
     * @param obj another object
     * @return true if the other object is a bit field with the same bits set
     */
    override fun equals(obj: Any?): Boolean {
        if (obj === this) {
            return true
        }
        if (obj !is BitField) {
            return false
        }
        return bytes.contentEquals(obj.bytes)
    }

    /**
     * Compare a BitField object with an array of bytes
     * 
     * @param other a byte array to compare to
     * @return true if the underlying byte array is equal to the given byte array
     */
    fun equals(other: ByteArray?): Boolean {
        return bytes.contentEquals(other)
    }

    override fun hashCode(): Int {
        return bytes.contentHashCode()
    }

    fun countBitsSet(): Int {
        var count = 0
        for (b in bytes) {
            // JAVA doesn't have the concept of unsigned byte; need to & with 255
            // to avoid exception of IndexOutOfBoundException when b < 0.
            count += com.google.testing.coverage.BitField.Companion.BIT_COUNT_LOOKUP[0xFF and b.toInt()]
        }
        return count
    }

    fun not(): BitField {
        val invertedBytes = ByteArray(bytes.size)
        for (i in bytes.indices) {
            invertedBytes[i] = bytes[i].inv().toByte()
        }
        return com.google.testing.coverage.BitField(invertedBytes)
    }

    fun sizeInBits(): Int {
        return bytes.size * 8
    }

    fun any(): Boolean {
        for (i in bytes.indices) {
            if (bytes[i] != 0.toByte()) {
                return true
            }
        }
        return false
    }

    companion object {
        private val BIT_COUNT_LOOKUP = IntArray(256)

        init {
            com.google.testing.coverage.BitField.Companion.BIT_COUNT_LOOKUP[0] = 0
            com.google.testing.coverage.BitField.Companion.BIT_COUNT_LOOKUP[1] = 1
            var i = 2
            while (i < 256) {
                val count: Int = com.google.testing.coverage.BitField.Companion.BIT_COUNT_LOOKUP[i / 2]
                com.google.testing.coverage.BitField.Companion.BIT_COUNT_LOOKUP[i] = count
                com.google.testing.coverage.BitField.Companion.BIT_COUNT_LOOKUP[i + 1] = count + 1
                i += 2
            }
        }
    }
}
