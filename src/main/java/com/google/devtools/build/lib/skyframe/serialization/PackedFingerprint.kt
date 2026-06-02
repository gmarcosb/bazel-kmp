// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization

import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueService
import com.google.devtools.build.lib.skyframe.serialization.KeyBytesProvider
import com.google.devtools.build.lib.skyframe.serialization.LeafDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.LeafObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.LeafSerializationContext
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException
import java.nio.ByteOrder

/**
 * A compact in-memory representation of a 128-bit fingerprint.
 * 
 * 
 * A wrapper around the bytes in unavoidable because a `byte[]` doesn't implement
 * values-equality. Storing the bytes in longs is more direct and consumes less memory.
 * 
 * @param lo the lower 64-bits of the fingerprint
 * @param hi the upper 64-bits of the fingerprint
 */
@kotlin.jvm.JvmRecord
data class PackedFingerprint(val lo: Long, val hi: Long) : KeyBytesProvider, Comparable<PackedFingerprint?> {
    /** Produces the `byte[]` representation of this fingerprint.  */
    override fun toBytes(): ByteArray {
        val result = ByteArray(BYTES)
        copyTo(result, 0)
        return result
    }

    /** Concatenates `bytes` to the `byte[]` representation of this fingerprint.  */
    override fun concat(bytes: ByteArray): ByteArray {
        val result = ByteArray(BYTES + bytes.size)
        copyTo(result, 0)
        java.lang.System.arraycopy(bytes, 0, result, 16, bytes.size)
        return result
    }

    /** Copies the fingerprint bytes to `bytes` starting at the given `offset`.  */
    fun copyTo(bytes: ByteArray, offset: Int) {
        LONG_ARRAY_HANDLE.set(bytes, offset, lo)
        LONG_ARRAY_HANDLE.set(bytes, offset + 8, hi)
    }

    /** Writes fingerprint data to `codedOut` such that it can be read by [.readFrom].  */
    @Throws(IOException::class)
    fun writeTo(codedOut: CodedOutputStream) {
        codedOut.writeFixed64NoTag(lo)
        codedOut.writeFixed64NoTag(hi)
    }

    override fun hashCode(): Int {
        return lo.toInt()
    }

    override fun compareTo(o: PackedFingerprint): Int {
        val result: Int = java.lang.Long.compare(hi, o.hi)
        if (result == 0) {
            return java.lang.Long.compare(lo, o.lo)
        }
        return result
    }

    @com.google.errorprone.annotations.Keep
    private class Codec : LeafObjectCodec<PackedFingerprint?>() {
        override fun getEncodedClass(): java.lang.Class<PackedFingerprint?> {
            return PackedFingerprint::class.java
        }

        @Throws(IOException::class)
        override fun serialize(
            context: LeafSerializationContext?, obj: PackedFingerprint, codedOut: CodedOutputStream
        ) {
            obj.writeTo(codedOut)
        }

        @Throws(IOException::class)
        override fun deserialize(
            context: LeafDeserializationContext?, codedIn: CodedInputStream
        ): PackedFingerprint {
            return readFrom(codedIn)
        }
    }

    companion object {
        /** Number of bytes in the serialized representation of a fingerprint.  */
        const val BYTES: Int = 16

        /**
         * Constructs a fingerprint directly from `bytes`.
         * 
         * @throws IllegalArgumentException if `bytes` is not length [.BYTES].
         */
        @kotlin.jvm.JvmStatic
        fun fromBytes(bytes: ByteArray): PackedFingerprint {
            com.google.common.base.Preconditions.checkArgument(bytes.size == BYTES, bytes.size)
            return PackedFingerprint(
                LONG_ARRAY_HANDLE.get(bytes, 0) as Long, LONG_ARRAY_HANDLE.get(bytes, 8) as Long
            )
        }

        /** Reads a fingerprint from `codedIn` that was written by [.writeTo].  */
        @Throws(IOException::class)
        fun readFrom(codedIn: CodedInputStream): PackedFingerprint {
            return PackedFingerprint(codedIn.readFixed64(), codedIn.readFixed64())
        }

        @kotlin.jvm.JvmStatic
        @com.google.common.annotations.VisibleForTesting
        fun getFingerprintForTesting(key: String): PackedFingerprint? {
            return FingerprintValueService.Companion.NONPROD_FINGERPRINTER.fingerprint(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        }

        private val LONG_ARRAY_HANDLE: java.lang.invoke.VarHandle =
            java.lang.invoke.MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.nativeOrder())
    }
}
