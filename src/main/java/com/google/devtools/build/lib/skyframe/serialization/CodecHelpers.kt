// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException

/**
 * Helper methods for writing codecs.
 * 
 * 
 * Supports 16-bit types that not included in [CodedInputStream] and [ ].
 */
object CodecHelpers {
    @Throws(IOException::class)
    fun writeShort(codedOut: CodedOutputStream, value: Short) {
        codedOut.writeRawByte((value.toInt() shr 8).toByte())
        codedOut.writeRawByte(value.toByte())
    }

    @Throws(IOException::class)
    fun readShort(codedIn: CodedInputStream): Short {
        var buffer: Int = codedIn.readRawByte().toInt() shl 8
        buffer = buffer or (codedIn.readRawByte().toInt() and 0xFF)
        return buffer.toShort()
    }

    @Throws(IOException::class)
    fun writeChar(codedOut: CodedOutputStream, value: Char) {
        codedOut.writeRawByte((value.code shr 8).toByte())
        codedOut.writeRawByte(value.code.toByte())
    }

    @Throws(IOException::class)
    fun readChar(codedIn: CodedInputStream): Char {
        var buffer: Int = codedIn.readRawByte().toInt() shl 8
        buffer = buffer or (codedIn.readRawByte().toInt() and 0xFF)
        return buffer.toChar()
    }
}
