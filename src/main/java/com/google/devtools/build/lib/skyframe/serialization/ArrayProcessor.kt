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
import com.google.devtools.build.lib.skyframe.serialization.CodecHelpers
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException

/**
 * Stateless class that encodes and decodes arrays that may be multi-dimensional.
 * 
 * 
 * Clients should obtain instances using [.forType].
 */
interface ArrayProcessor {
    /**
     * Serializes an array.
     * 
     * @param type the type of the array. Can be a multidimensional array type.
     * @param arr the array instance to serialize.
     */
    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun serialize(
        context: SerializationContext?, codedOut: CodedOutputStream?, type: java.lang.Class<*>?, arr: Any?
    )

    /**
     * Deserializes an array of type `arrayType` from `codedIn`.
     * 
     * @return the array object. [Object] is the most specific common type ancestor of `Object[]` and `int[]`.
     */
    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun deserialize(
        context: AsyncDeserializationContext?, codedIn: CodedInputStream?, arrayType: java.lang.Class<*>?
    ): Any?

    /**
     * Implements common functionality for handling multi-dimensional arrays.
     * 
     * 
     * Subclasses handle the base arrays by implementing [.serializeArrayData] and [ ][.deserializeArrayData].
     */
    class PrimitiveArrayProcessor : ArrayProcessor {
        @Throws(IOException::class)
        override fun serialize(
            context: SerializationContext?, codedOut: CodedOutputStream, type: java.lang.Class<*>, arr: Any?
        ) {
            // The first field is a tag indicating either null or the size of the array to come.
            //   * 0 for null; or
            //   * length + 1 otherwise.
            // -1 could make sense for nulls, but nulls are fairly common and -1 has a 10 byte signed
            // integer representation. Offsetting the length like this could overflow for a length of
            // Integer.MAX_INT, but it's impossible to serialize an array of that length anyway.
            //
            // Immediately below, a tag is written for null. When non-null, the tag depends on the length
            // of the array, which is easier to obtain after the array has been cast to its array type. So
            // if it's not a nested array, the tag is written by subclasses.
            if (arr == null) {
                codedOut.writeInt32NoTag(0)
                return
            }

            val componentType: java.lang.Class<*> = type.getComponentType()
            if (componentType.isArray()) {
                val subarrays = arr as Array<Any?>
                codedOut.writeInt32NoTag(subarrays.size + 1)
                for (subarray in subarrays) {
                    serialize(context, codedOut, componentType, subarray)
                }
                return
            }

            serializeArrayData(codedOut, arr)
        }

        @Throws(IOException::class)
        abstract fun serializeArrayData(codedOut: CodedOutputStream?, untypedArr: Any?)

        @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
        override fun deserialize(
            context: AsyncDeserializationContext?, codedIn: CodedInputStream, arrayType: java.lang.Class<*>
        ): Any? {
            return deserialize(codedIn, arrayType)
        }

        /** Primitive arrays can be deserialized without an [AsyncDeserializationContext].  */
        @Throws(IOException::class)
        private fun deserialize(codedIn: CodedInputStream, arrayType: java.lang.Class<*>): Any? {
            var length: Int = codedIn.readInt32()
            if (length == 0) {
                return null // It was null.
            }
            length-- // Shifts the length back. It was shifted to allow 0 to be used for null.

            val componentType: java.lang.Class<*> = arrayType.getComponentType()
            if (!componentType.isArray()) {
                return deserializeArrayData(codedIn, length)
            }

            val arr = java.lang.reflect.Array.newInstance(componentType, length) as Array<Any?>
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            return arr
        }

        @Throws(IOException::class)
        abstract fun deserializeArrayData(codedIn: CodedInputStream?, length: Int): Any?
    }

    companion object {
        fun forType(type: java.lang.Class<*>): ArrayProcessor {
            com.google.common.base.Preconditions.checkArgument(type.isArray(), "%s is not an array", type)
            val processor: ArrayProcessor
            val baseType: java.lang.Class<*> = resolveBaseArrayType(type)
            if (baseType.isPrimitive()) {
                if (baseType == Boolean::class.javaPrimitiveType) {
                    processor = BOOLEAN_ARRAY_PROCESSOR
                } else if (baseType == Byte::class.javaPrimitiveType) {
                    processor = BYTE_ARRAY_PROCESSOR
                } else if (baseType == Short::class.javaPrimitiveType) {
                    processor = SHORT_ARRAY_PROCESSOR
                } else if (baseType == Char::class.javaPrimitiveType) {
                    processor = CHAR_ARRAY_PROCESSOR
                } else if (baseType == Int::class.javaPrimitiveType) {
                    processor = INT_ARRAY_PROCESSOR
                } else if (baseType == Long::class.javaPrimitiveType) {
                    processor = LONG_ARRAY_PROCESSOR
                } else if (baseType == Float::class.javaPrimitiveType) {
                    processor = FLOAT_ARRAY_PROCESSOR
                } else if (baseType == Double::class.javaPrimitiveType) {
                    processor = DOUBLE_ARRAY_PROCESSOR
                } else {
                    throw java.lang.UnsupportedOperationException(
                        "Unexpected primitive field type " + baseType + " for " + type
                    )
                }
            } else {
                processor = OBJECT_ARRAY_PROCESSOR
            }
            return processor
        }

        // This method should be marked private, but that's not supported in Java 8.
        fun resolveBaseArrayType(arrayType: java.lang.Class<*>): java.lang.Class<*> {
            val componentType: java.lang.Class<*> = arrayType.getComponentType()
            if (componentType.isArray()) {
                return resolveBaseArrayType(componentType)
            }
            return componentType
        }

        val BOOLEAN_ARRAY_PROCESSOR: PrimitiveArrayProcessor = object : PrimitiveArrayProcessor() {
            @Throws(IOException::class)
            override fun serializeArrayData(codedOut: CodedOutputStream, untypedArr: Any?) {
                val values = untypedArr as BooleanArray
                codedOut.writeInt32NoTag(values.size + 1)
                for (value in values) {
                    codedOut.writeBoolNoTag(value)
                }
            }

            @Throws(IOException::class)
            override fun deserializeArrayData(codedIn: CodedInputStream, length: Int): BooleanArray {
                val values = BooleanArray(length)
                /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
                return values
            }
        }

        val BYTE_ARRAY_PROCESSOR: PrimitiveArrayProcessor = object : PrimitiveArrayProcessor() {
            @Throws(IOException::class)
            override fun serializeArrayData(codedOut: CodedOutputStream, untypedArr: Any?) {
                val values = untypedArr as ByteArray
                val length = values.size
                codedOut.writeInt32NoTag(length + 1)
                if (length > 0) {
                    codedOut.writeRawBytes(values)
                }
            }

            @Throws(IOException::class)
            override fun deserializeArrayData(codedIn: CodedInputStream, length: Int): ByteArray? {
                return codedIn.readRawBytes(length)
            }
        }

        val SHORT_ARRAY_PROCESSOR: PrimitiveArrayProcessor = object : PrimitiveArrayProcessor() {
            @Throws(IOException::class)
            override fun serializeArrayData(codedOut: CodedOutputStream, untypedArr: Any?) {
                val values = untypedArr as ShortArray
                codedOut.writeInt32NoTag(values.size + 1)
                for (value in values) {
                    CodecHelpers.writeShort(codedOut, value)
                }
            }

            @Throws(IOException::class)
            override fun deserializeArrayData(codedIn: CodedInputStream, length: Int): ShortArray {
                val values = ShortArray(length)
                /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
                return values
            }
        }

        val CHAR_ARRAY_PROCESSOR: PrimitiveArrayProcessor = object : PrimitiveArrayProcessor() {
            @Throws(IOException::class)
            override fun serializeArrayData(codedOut: CodedOutputStream, untypedArr: Any?) {
                val values = untypedArr as CharArray
                codedOut.writeInt32NoTag(values.size + 1)
                for (value in values) {
                    CodecHelpers.writeChar(codedOut, value)
                }
            }

            @Throws(IOException::class)
            override fun deserializeArrayData(codedIn: CodedInputStream, length: Int): CharArray {
                val values = CharArray(length)
                /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
                return values
            }
        }

        val INT_ARRAY_PROCESSOR: PrimitiveArrayProcessor = object : PrimitiveArrayProcessor() {
            @Throws(IOException::class)
            override fun serializeArrayData(codedOut: CodedOutputStream, untypedArr: Any?) {
                val values = untypedArr as IntArray
                codedOut.writeInt32NoTag(values.size + 1)
                for (value in values) {
                    codedOut.writeInt32NoTag(value)
                }
            }

            @Throws(IOException::class)
            override fun deserializeArrayData(codedIn: CodedInputStream, length: Int): IntArray {
                val values = IntArray(length)
                /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
                return values
            }
        }

        val LONG_ARRAY_PROCESSOR: PrimitiveArrayProcessor = object : PrimitiveArrayProcessor() {
            @Throws(IOException::class)
            override fun serializeArrayData(codedOut: CodedOutputStream, untypedArr: Any?) {
                val values = untypedArr as LongArray
                codedOut.writeInt32NoTag(values.size + 1)
                for (value in values) {
                    codedOut.writeInt64NoTag(value)
                }
            }

            @Throws(IOException::class)
            override fun deserializeArrayData(codedIn: CodedInputStream, length: Int): LongArray {
                val values = LongArray(length)
                /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
                return values
            }
        }

        val FLOAT_ARRAY_PROCESSOR: PrimitiveArrayProcessor = object : PrimitiveArrayProcessor() {
            @Throws(IOException::class)
            override fun serializeArrayData(codedOut: CodedOutputStream, untypedArr: Any?) {
                val values = untypedArr as FloatArray
                codedOut.writeInt32NoTag(values.size + 1)
                for (value in values) {
                    codedOut.writeFloatNoTag(value)
                }
            }

            @Throws(IOException::class)
            override fun deserializeArrayData(codedIn: CodedInputStream, length: Int): FloatArray {
                val values = FloatArray(length)
                /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
                return values
            }
        }

        val DOUBLE_ARRAY_PROCESSOR: PrimitiveArrayProcessor = object : PrimitiveArrayProcessor() {
            @Throws(IOException::class)
            override fun serializeArrayData(codedOut: CodedOutputStream, untypedArr: Any?) {
                val values = untypedArr as DoubleArray
                codedOut.writeInt32NoTag(values.size + 1)
                for (value in values) {
                    codedOut.writeDoubleNoTag(value)
                }
            }

            @Throws(IOException::class)
            override fun deserializeArrayData(codedIn: CodedInputStream, length: Int): DoubleArray {
                val values = DoubleArray(length)
                /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
                return values
            }
        }

        /**
         * Handles possibly nested arrays of `Object` or any type derived from `Object`.
         * 
         * 
         * This processor observes the nesting level of the array by reflective operations on the
         * `type` parameter passed into its methods. It similarly uses reflective calls to create
         * nested arrays of appropriate type and nesting level.
         * 
         * 
         * Finally, at the leaf level, it uses the `(Ser|Deser)ializationContext` to apply codecs
         * to the base array components. The [DeserializationContext] must be an [ ].
         */
        val OBJECT_ARRAY_PROCESSOR: ArrayProcessor = object : ArrayProcessor {
            // Special case uses `Array.newInstance` to also create leaf-level arrays. Unlike the
            // `PrimitiveArrayProcessor`, the unnested arrays depend on serialization contexts.
            @Throws(
                IOException::class,
                com.google.devtools.build.lib.skyframe.serialization.SerializationException::class
            )
            override fun serialize(
                context: SerializationContext, codedOut: CodedOutputStream, type: java.lang.Class<*>, arr: Any?
            ) {
                // Tagging works exactly the same as PrimitiveArrayProcessor.serialize: 0 for null;
                // 1 + length otherwise. See comment there for more details.
                if (arr == null) {
                    codedOut.writeInt32NoTag(0)
                    return
                }

                val componentType: java.lang.Class<*> = type.getComponentType()
                if (componentType.isArray()) {
                    val subarrays = arr as Array<Any?>
                    codedOut.writeInt32NoTag(subarrays.size + 1)
                    for (subarray in subarrays) {
                        serialize(context, codedOut, componentType, subarray)
                    }
                    return
                }

                serializeObjectArray(context, codedOut, arr)
            }

            @Throws(
                IOException::class,
                com.google.devtools.build.lib.skyframe.serialization.SerializationException::class
            )
            override fun deserialize(
                context: AsyncDeserializationContext, codedIn: CodedInputStream, arrayType: java.lang.Class<*>
            ): Any? {
                var length: Int = codedIn.readInt32()
                if (length == 0) {
                    return null // It was null.
                }
                length-- // Shifts the length back. It was shifted to allow 0 to be used for null.

                val componentType: java.lang.Class<*> = arrayType.getComponentType()
                val arr = java.lang.reflect.Array.newInstance(componentType, length) as Array<Any?>

                if (length > 0) {
                    if (componentType.isArray()) {
                        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
                    } else {
                        deserializeObjectArray(context, codedIn, arr, length)
                    }
                }
                return arr
            }
        }

        /** Serializes an object array using the given `context` to the `codedOut` stream.  */
        @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
        fun serializeObjectArray(
            context: SerializationContext, codedOut: CodedOutputStream, untypedArr: Any?
        ) {
            val values = untypedArr as Array<Any?>
            codedOut.writeInt32NoTag(values.size + 1)
            for (obj in values) {
                context.serialize(obj, codedOut)
            }
        }

        /**
         * Deserializes `length` objects into `arr`.
         * 
         * 
         * Partially deserialized values may be visible to the caller.
         */
        @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
        fun deserializeObjectArray(
            context: AsyncDeserializationContext, codedIn: CodedInputStream?, arr: Array<Any?>?, length: Int
        ) {
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        }
    }
}
