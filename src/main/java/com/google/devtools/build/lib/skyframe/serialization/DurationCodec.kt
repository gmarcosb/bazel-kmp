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

/** Encodes a Duration.  */
class DurationCodec : LeafObjectCodec<java.time.Duration?>() {
    @Throws(IOException::class)
    override fun serialize(context: LeafSerializationContext?, obj: java.time.Duration, codedOut: CodedOutputStream) {
        codedOut.writeInt64NoTag(obj.getSeconds())
        codedOut.writeInt32NoTag(obj.getNano())
    }

    @Throws(IOException::class)
    override fun deserialize(context: LeafDeserializationContext?, codedIn: CodedInputStream): java.time.Duration? {
        return java.time.Duration.ofSeconds(codedIn.readInt64(), codedIn.readInt32().toLong())
    }

    override fun getEncodedClass(): java.lang.Class<java.time.Duration?> {
        return java.time.Duration::class.java
    }
}
