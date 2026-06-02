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

/** Encodes a HashCode.  */
class HashCodeCodec : LeafObjectCodec<com.google.common.hash.HashCode?>() {
    @Throws(IOException::class)
    override fun serialize(
        context: LeafSerializationContext?,
        obj: com.google.common.hash.HashCode,
        codedOut: CodedOutputStream
    ) {
        codedOut.writeByteArrayNoTag(obj.asBytes())
    }

    @Throws(IOException::class)
    override fun deserialize(
        context: LeafDeserializationContext?,
        codedIn: CodedInputStream
    ): com.google.common.hash.HashCode {
        return com.google.common.hash.HashCode.fromBytes(codedIn.readByteArray())
    }

    override fun getEncodedClass(): java.lang.Class<com.google.common.hash.HashCode?> {
        return com.google.common.hash.HashCode::class.java
    }
}
