// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.skyframe.serialization.LeafDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.LeafObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.LeafSerializationContext
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException

/**
 * Specialized [ObjectCodec] for storing singleton values. Values serialize to a supplied
 * representation, which is useful for debugging and is used to verify the serialized representation
 * during deserialization.
 */
class SingletonCodec<T> private constructor(value: T?, mnemonic: String) : LeafObjectCodec<T?>() {
    private val value: T?
    private val mnemonic: ByteArray

    init {
        this.value =
            com.google.common.base.Preconditions.checkNotNull<T?>(value, "SingletonCodec cannot represent null")
        this.mnemonic = mnemonic.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    }

    override fun getEncodedClass(): java.lang.Class<T?> {
        return value.getClass() as java.lang.Class<T?>
    }

    @Throws(IOException::class)
    override fun serialize(context: LeafSerializationContext?, t: T?, codedOut: CodedOutputStream) {
        codedOut.writeByteArrayNoTag(mnemonic)
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserialize(context: LeafDeserializationContext?, codedIn: CodedInputStream): T? {
        // Get ByteBuffer instead of raw bytes, as it may be a direct view of the data and not a copy,
        // which is much more efficient.
        val readMnemonic: java.nio.ByteBuffer = codedIn.readByteBuffer()
        if (!bytesEqual(mnemonic, readMnemonic)) {
            throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                "Failed to decode singleton " + value + " expected " + java.util.Arrays.toString(mnemonic)
            )
        }
        return value
    }

    companion object {
        /**
         * Create instance wrapping the singleton `value`. Will serialize to the byte array
         * representation of `mnemonic`. On deserialization if `mnemonic` matches the
         * serialized data then `value` is returned.
         */
        fun <T> of(value: T?, mnemonic: String): SingletonCodec<T?> {
            return SingletonCodec<T?>(value, mnemonic)
        }

        private fun bytesEqual(expected: ByteArray, buffer: java.nio.ByteBuffer): Boolean {
            if (buffer.remaining() != expected.size) {
                return false
            }

            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */

            return true
        }
    }
}

