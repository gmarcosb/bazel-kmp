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

import com.google.devtools.build.lib.skyframe.serialization.DeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.LeafDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.LeafSerializationContext
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException

/**
 * A codec that directly deserializes from the [CodedInputStream].
 * 
 * 
 * [LeafObjectCodec]s may only delegate to other [LeafObjectCodec]s and are
 * restricted from using any asynchronous features. By construction, they can only be used to
 * serialize acyclic values and are always synchronous.
 * 
 * 
 * Values using this codec will be memoized using [Object.hashCode] and [ ][Object.equals].
 */
abstract class LeafObjectCodec<T> : ObjectCodec<T?> {
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun serialize(context: SerializationContext?, obj: T?, codedOut: CodedOutputStream?) {
        serialize(context as LeafSerializationContext?, obj, codedOut)
    }

    /**
     * This has the same contract as [.serialize], but may only depend on [ ] instead of the full [SerializationContext].
     */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    abstract fun serialize(
        context: LeafSerializationContext?, obj: T?, codedOut: CodedOutputStream?
    )

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserialize(context: DeserializationContext?, codedIn: CodedInputStream?): T? {
        return deserialize(context as LeafDeserializationContext?, codedIn)
    }

    /**
     * This has the same contract as [.deserialize], but may only depend on [ ] instead of the full [DeserializationContext].
     */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    abstract fun deserialize(context: LeafDeserializationContext?, codedIn: CodedInputStream?): T?
}
