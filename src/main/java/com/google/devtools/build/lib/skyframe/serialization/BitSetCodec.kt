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
import com.google.devtools.build.lib.skyframe.serialization.LongArrayCodec
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException
import java.util.BitSet

internal class BitSetCodec : LeafObjectCodec<BitSet?>() {
    val encodedClass: java.lang.Class<out BitSet?>
        get() = BitSet::class.java

    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    override fun serialize(context: LeafSerializationContext, obj: BitSet, codedOut: CodedOutputStream?) {
        val data: LongArray? = obj.toLongArray()
        context.serializeLeaf<LongArray?>(data, DELEGATE, codedOut)
    }

    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    override fun deserialize(context: LeafDeserializationContext, codedIn: CodedInputStream?): BitSet {
        return BitSet.valueOf(context.deserializeLeaf<LongArray?>(codedIn, DELEGATE))
    }

    companion object {
        private val DELEGATE: LongArrayCodec = LongArrayCodec()
    }
}
