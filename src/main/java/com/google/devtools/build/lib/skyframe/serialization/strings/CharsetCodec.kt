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
import com.google.devtools.build.lib.skyframe.serialization.strings.UnsafeStringCodec
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException

internal class CharsetCodec : LeafObjectCodec<java.nio.charset.Charset?>() {
    override fun getEncodedClass(): java.lang.Class<java.nio.charset.Charset?> {
        return java.nio.charset.Charset::class.java
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun serialize(
        context: LeafSerializationContext, charset: java.nio.charset.Charset, codedOut: CodedOutputStream?
    ) {
        context.serializeLeaf<String?>(charset.name(), UnsafeStringCodec.Companion.stringCodec(), codedOut)
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserialize(
        context: LeafDeserializationContext,
        codedIn: CodedInputStream?
    ): java.nio.charset.Charset? {
        return java.nio.charset.Charset.forName(
            context.deserializeLeaf<String?>(
                codedIn,
                UnsafeStringCodec.Companion.stringCodec()
            )
        )
    }
}
