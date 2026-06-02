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

import com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.DeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec.MemoizationStrategy
import com.google.protobuf.CodedInputStream
import java.io.IOException

/** Codec variant that interns the deserialization result.  */
abstract class InterningObjectCodec<T> : ObjectCodec<T?> {
    override fun getStrategy(): MemoizationStrategy {
        // There is no fixed reference to an interned object until after it has been constructed and
        // passes through the interner. Therefore this is always MEMOIZE_AFTER.
        return MemoizationStrategy.MEMOIZE_AFTER
    }

    /**
     * Adapter for synchronous contexts.
     * 
     * 
     * Deserializes using [.deserializeInterned] then calls [.intern] on the result.
     */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserialize(context: DeserializationContext?, codedIn: CodedInputStream?): T? {
        return intern(deserializeInterned(context, codedIn))
    }

    /** Performs the deserialization work.  */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    abstract fun deserializeInterned(
        context: AsyncDeserializationContext?, codedIn: CodedInputStream?
    ): T?

    /** Interns the result of [.deserializeInterned].  */
    abstract fun intern(value: T?): T?
}
