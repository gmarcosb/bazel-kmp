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
package com.google.devtools.build.lib.skyframe.serialization.strings

import com.google.devtools.build.lib.skyframe.serialization.LeafDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.LeafObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.LeafSerializationContext
import com.google.devtools.build.lib.unsafe.StringUnsafe
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException

/**
 * A high-performance [ObjectCodec] for [String] objects specialized for Strings in
 * JDK9+, where a String can be represented as a byte array together with a single byte (0 or 1) for
 * Latin-1 or UTF16 encoding.
 */
class UnsafeStringCodec : LeafObjectCodec<String?>() {
    override fun getEncodedClass(): java.lang.Class<String?> {
        return String::class.java
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun serialize(context: LeafSerializationContext?, obj: String?, codedOut: CodedOutputStream) {
        val coder: Byte = StringUnsafe.getCoder(obj)
        val value: ByteArray = StringUnsafe.getByteArray(obj)
        // Optimize for the case that coder == 0, in which case we can just write the length here,
        // potentially using just one byte. If coder != 0, we'll use 4 bytes, but that's vanishingly
        // rare.
        if (coder.toInt() == 0) {
            codedOut.writeInt32NoTag(value.size)
        } else if (coder.toInt() == 1) {
            codedOut.writeInt32NoTag(-value.size)
        } else {
            throw com.google.devtools.build.lib.skyframe.serialization.SerializationException("Unexpected coder value: " + coder + " for " + obj)
        }
        codedOut.writeRawBytes(value)
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserialize(context: LeafDeserializationContext?, codedIn: CodedInputStream): String? {
        var length: Int = codedIn.readInt32()
        val coder: Byte
        if (length >= 0) {
            coder = 0
        } else {
            coder = 1
            length = -length
        }
        val value: ByteArray? = codedIn.readRawBytes(length)
        return StringUnsafe.newInstance(value, coder)
    }

    companion object {
        /**
         * An instance to use for delegation by other codecs.
         * 
         * 
         * The default constructor is left intact to allow the usual codec registration mechanisms to
         * work.
         */
        private val INSTANCE = UnsafeStringCodec()

        @kotlin.jvm.JvmStatic
        fun stringCodec(): UnsafeStringCodec {
            return INSTANCE
        }
    }
}
