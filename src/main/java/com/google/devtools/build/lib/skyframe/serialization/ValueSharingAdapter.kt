// Copyright 2025 The Bazel Authors. All rights reserved.
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
import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec.DeferredValue
import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec.SimpleDeferredValue
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException

/** Converts an existing [DeferredObjectCodec] into a shared value codec.  */
class ValueSharingAdapter<T>(baseCodec: DeferredObjectCodec<T?>) : DeferredObjectCodec<T?>() {
    private val baseCodec: DeferredObjectCodec<T?>

    init {
        this.baseCodec = baseCodec
    }

    override fun autoRegister(): Boolean {
        return false
    }

    override fun getEncodedClass(): java.lang.Class<out T?>? {
        return baseCodec.getEncodedClass()
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun serialize(context: SerializationContext, obj: T?, codedOut: CodedOutputStream?) {
        context.putSharedValue<T?>(obj,  /* distinguisher= */null, baseCodec, codedOut)
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserializeDeferred(
        context: AsyncDeserializationContext, codedIn: CodedInputStream?
    ): DeferredValue<T?> {
        val builder: SimpleDeferredValue<T?> = SimpleDeferredValue.Companion.create<T?>()
        context.getSharedValue<SimpleDeferredValue<T?>?>(
            codedIn,  /* distinguisher= */
            null,
            baseCodec,
            builder,
            com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext.FieldSetter { dv: T?, obj: Any? ->
                SimpleDeferredValue.Companion.set(
                    dv,
                    obj
                )
            })
        return builder
    }
}
