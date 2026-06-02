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

/** [ObjectCodec] that uses only [AsyncDeserializationContext].  */
abstract class AsyncObjectCodec<T> : ObjectCodec<T?> {
    val strategy: MemoizationStrategy
        get() = MemoizationStrategy.MEMOIZE_BEFORE

    /** Adapter for synchronous contexts.  */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserialize(context: DeserializationContext?, codedIn: CodedInputStream?): T? {
        return deserializeAsync(context, codedIn)
    }

    /**
     * This has the same contract as [.deserialize], but narrows the `context` API to
     * methods that are compatible with async deserialization.
     */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    abstract fun deserializeAsync(context: AsyncDeserializationContext?, codedIn: CodedInputStream?): T?
}
