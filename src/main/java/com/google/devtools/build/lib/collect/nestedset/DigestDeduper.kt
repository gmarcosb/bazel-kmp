// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.collect.nestedset

import com.google.devtools.build.lib.util.BytesSink
import com.google.devtools.build.lib.util.Fingerprint
import java.nio.ByteOrder

/**
 * A fixed-size deduplicator for digests.
 * 
 * 
 * This class is not thread safe.
 */
internal class DigestDeduper(maxSize: Int, private val digestLength: Int) {
    /**
     * Bytes indicating presence of data.
     * 
     * 
     * The first bit of the i-th byte is 1 iff the slot is occupied. The remaining bits in the byte
     * are the last 7 bits of the corresponding digest. This allows efficient probing, relying mostly
     * on just the control bytes.
     */
    private val control: ByteArray

    private val data: ByteArray

    /**
     * Bit mask that helps implement the modulo operation.
     * 
     * 
     * The size is always a power of 2, and this mask has a value of size - 1.
     */
    private val sizeMask: Int

    internal class DigestReference : BytesSink {
        private var buffer: ByteArray?
        private var offset = 0
        private var length = 0

        override fun acceptBytes(buffer: ByteArray?, offset: Int, length: Int) {
            com.google.common.base.Preconditions.checkArgument(length >= 4, "length=%s < 4", length)
            this.buffer = buffer
            this.offset = offset
            this.length = length
        }

        /**
         * Clears the reference.
         * 
         * 
         * The purpose of this method is to allow the client to avoid retaining [.buffer]
         * longer than necessary.
         */
        fun clear() {
            this.buffer = null
            this.offset = 0
            this.length = 0
        }

        fun hash(): Int {
            // Interprets the last 4 bytes of the digest as an integer. Since it is a digest, it should be
            // uniformly distributed.
            return INT_HANDLE.get(buffer, offset + length - 4) as Int
        }

        fun controlByte(): Byte {
            return (buffer!![offset + length - 1].toInt() or CONTROL_BIT.toInt()).toByte()
        }

        fun equalsBytesAt(thatBuffer: ByteArray, thatOffset: Int): Boolean {
            // `length` might be less than `digestLength`. In such cases, the prefix contains a length
            // specifier that is implicitly matched by the following comparison.
            for (i in 0..<length) {
                if (buffer!![offset + i] != thatBuffer[thatOffset + i]) {
                    return false
                }
            }
            return true
        }

        fun copyTo(dest: ByteArray?, destOffset: Int) {
            java.lang.System.arraycopy(buffer, offset, dest, destOffset, length)
        }

        fun addTo(fingerprint: Fingerprint) {
            fingerprint.addBytes(buffer, offset, length)
        }
    }

    init {
        val sizeBits = sizeBitsFor(maxSize)
        val size = 1 shl sizeBits
        this.sizeMask = size - 1
        this.control = ByteArray(size)
        this.data = ByteArray(size * digestLength)
    }

    /**
     * Adds `digest` to this deduper.
     * 
     * @return true if the digest was added and false if it was a duplicate
     */
    fun add(digest: DigestReference): Boolean {
        val digestControlByte = digest.controlByte()
        var candidateSlot = digest.hash()
        while (true) {
            candidateSlot = candidateSlot and sizeMask // fast modulo
            val controlByte = control[candidateSlot].toInt()
            if (controlByte == 0.toByte().toInt()) {
                // The slot was empty. Adds `digest` to the slot.
                control[candidateSlot] = digestControlByte
                digest.copyTo(data, candidateSlot * digestLength)
                return true
            }
            if (controlByte == digestControlByte.toInt()) { // likely match
                if (digest.equalsBytesAt(data, candidateSlot * digestLength)) {
                    return false // It was a duplicate.
                }
            }
            candidateSlot++
        }
    }

    companion object {
        // Creates a VarHandle using generic type int[].class, telling it we want to read 'int' values.
        private val INT_HANDLE: java.lang.invoke.VarHandle =
            java.lang.invoke.MethodHandles.byteArrayViewVarHandle(IntArray::class.java, ByteOrder.nativeOrder())

        /** Mask for setting the top bit of the control byte to 1.  */
        private val CONTROL_BIT = 0x80.toByte()

        @kotlin.jvm.JvmStatic
        @com.google.common.annotations.VisibleForTesting
        fun sizeBitsFor(maxSize: Int): Int {
            com.google.common.base.Preconditions.checkArgument(maxSize > 0, "maxSize=%s not >0", maxSize)
            // 1. Calculate the minimum capacity required to satisfy the 0.75 load factor.
            // Formula: ceil(maxSize / 0.75)  =>  ceil((maxSize * 4) / 3)
            // Integer ceiling division trick: (A + B - 1) / B
            val minCapacity = (maxSize * 4 + 2) / 3

            // 2. Find the smallest power of 2 >= minCapacity
            // If minCapacity is already a power of 2, this returns minCapacity.
            // If not, it returns the next power of 2.
            return 32 - java.lang.Integer.numberOfLeadingZeros(minCapacity - 1)
        }
    }
}
