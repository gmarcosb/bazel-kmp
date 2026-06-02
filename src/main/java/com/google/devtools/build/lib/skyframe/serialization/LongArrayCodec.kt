// Copyright 2020 The Bazel Authors. All rights reserved.
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

internal class LongArrayCodec : LeafObjectCodec<LongArray?>() {
    override fun getEncodedClass(): java.lang.Class<out LongArray?> {
        return LongArray::class.java
    }

    @Throws(IOException::class)
    override fun serialize(context: LeafSerializationContext?, obj: LongArray, codedOut: CodedOutputStream) {
        codedOut.writeInt32NoTag(obj.size)
        for (l in obj) {
            codedOut.writeInt64NoTag(l)
        }
    }

    @Throws(IOException::class)
    override fun deserialize(context: LeafDeserializationContext?, codedIn: CodedInputStream): LongArray {
        val result = LongArray(codedIn.readInt32())
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        return result
    }
}
