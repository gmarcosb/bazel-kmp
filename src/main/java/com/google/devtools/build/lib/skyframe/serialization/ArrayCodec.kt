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

import com.google.devtools.build.lib.skyframe.serialization.ArrayProcessor
import com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.AsyncObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException

/** [ObjectCodec] for arrays of an arbitrary component type.  */
open class ArrayCodec<T> private constructor(
    componentType: java.lang.Class<T?>?,
    arrayType: java.lang.Class<Array<T?>?>?
) : AsyncObjectCodec<Array<T?>?>() {
    /** Codec for `Object[]`.  */
    internal class ObjectArrayCodec : ArrayCodec<Any?>(Any::class.java, Array<Any>::class.java)

    private val componentType: java.lang.Class<T?>?
    private val arrayType: java.lang.Class<Array<T?>?>?

    init {
        this.componentType = componentType
        this.arrayType = arrayType
    }

    val encodedClass: java.lang.Class<Array<T?>?>?
        get() = arrayType

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun serialize(context: SerializationContext, obj: Array<T?>, codedOut: CodedOutputStream) {
        codedOut.writeInt32NoTag(obj.size)
        try {
            for (item in obj) {
                context.serialize(item, codedOut)
            }
        } catch (e: java.lang.StackOverflowError) {
            // TODO(janakr): figure out if we need to handle this better and handle it better if so.
            throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                "StackOverflow serializing array",
                e
            )
        }
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserializeAsync(context: AsyncDeserializationContext, codedIn: CodedInputStream): Array<T?> {
        val result = java.lang.reflect.Array.newInstance(componentType, codedIn.readInt32()) as Array<T?>
        context.registerInitialValue(result)
        try {
            ArrayProcessor.Companion.deserializeObjectArray(context, codedIn, result, result.size)
        } catch (e: java.lang.StackOverflowError) {
            // TODO(janakr): figure out if we need to handle this better and handle it better if so.
            throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                "StackOverflow deserializing array",
                e
            )
        }
        return result
    }

    companion object {
        /** Creates a codec for arrays of the given component type.  */
        fun <T> forComponentType(componentType: java.lang.Class<T?>?): ArrayCodec<T?> {
            val arrayType: java.lang.Class<Array<T?>?> =
                java.lang.reflect.Array.newInstance(componentType, 0).getClass() as java.lang.Class<Array<T?>?>
            return ArrayCodec<T?>(componentType, arrayType)
        }
    }
}
