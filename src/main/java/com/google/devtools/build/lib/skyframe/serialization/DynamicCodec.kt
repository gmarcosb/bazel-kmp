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

import com.google.common.flogger.GoogleLogger
import com.google.devtools.build.lib.skyframe.serialization.ArrayProcessor
import com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.AsyncObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.CodecHelpers
import com.google.devtools.build.lib.skyframe.serialization.DynamicCodec
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import com.google.devtools.build.lib.unsafe.UnsafeProvider
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException
import java.util.Collections
import java.util.LinkedHashMap

/** A codec that serializes arbitrary types.  */
// TODO: b/331765692 - clean this up
class DynamicCodec private constructor(type: java.lang.Class<*>, handlers: Array<FieldHandler>) :
    AsyncObjectCodec<Any?>() {
    private val type: java.lang.Class<*>
    private val handlers: Array<FieldHandler>

    constructor(type: java.lang.Class<*>) : this(type, getFieldHandlers(type))

    init {
        this.type = type
        this.handlers = handlers
    }

    override fun getEncodedClass(): java.lang.Class<*> {
        return type
    }

    @Throws(
        com.google.devtools.build.lib.skyframe.serialization.SerializationException::class,
        IOException::class
    )  // Want the full stack trace.
    override fun serialize(context: SerializationContext?, obj: Any, codedOut: CodedOutputStream?) {
        for (handler in handlers) {
            try {
                handler.serialize(context, codedOut, obj)
            } catch (e: com.google.devtools.build.lib.skyframe.serialization.SerializationException) {
                logger.atSevere().withCause(e).log(
                    "Unserializable object and superclass: %s %s", obj, obj.getClass().getSuperclass()
                )
                e.addTrail(type)
                throw e
            }
        }
    }

    @Throws(
        com.google.devtools.build.lib.skyframe.serialization.SerializationException::class,
        IOException::class
    )  // Want the full stack trace.
    override fun deserializeAsync(context: AsyncDeserializationContext, codedIn: CodedInputStream?): Any {
        val instance: Any
        try {
            instance = UnsafeProvider.unsafe().allocateInstance(type)
        } catch (e: java.lang.ReflectiveOperationException) {
            throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                "Could not instantiate object of type: " + type,
                e
            )
        }
        context.registerInitialValue(instance)

        for (handler in handlers) {
            try {
                handler.deserialize(context, codedIn, instance)
            } catch (e: com.google.devtools.build.lib.skyframe.serialization.SerializationException) {
                logger.atSevere().withCause(e).log(
                    "Failed to deserialize object with superclass: %s %s",
                    instance, instance.getClass().getSuperclass()
                )
                e.addTrail(type)
                throw e
            }
        }
        return instance
    }

    /** Handles serialization of a field.  */
    interface FieldHandler {
        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        fun serialize(context: SerializationContext?, codedOut: CodedOutputStream?, obj: Any?)

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        fun deserialize(context: AsyncDeserializationContext?, codedIn: CodedInputStream?, obj: Any?)
    }

    private class BooleanHandler(private val offset: Long) : FieldHandler {
        // TODO: b/386384684 - remove Unsafe usage
        @Throws(IOException::class)
        override fun serialize(context: SerializationContext?, codedOut: CodedOutputStream, obj: Any?) {
            codedOut.writeBoolNoTag(UnsafeProvider.unsafe().getBoolean(obj, offset))
        }

        // TODO: b/386384684 - remove Unsafe usage
        @Throws(IOException::class)
        override fun deserialize(
            context: AsyncDeserializationContext?, codedIn: CodedInputStream, obj: Any?
        ) {
            UnsafeProvider.unsafe().putBoolean(obj, offset, codedIn.readBool())
        }
    }

    private class ByteHandler(private val offset: Long) : FieldHandler {
        // TODO: b/386384684 - remove Unsafe usage
        @Throws(IOException::class)
        override fun serialize(context: SerializationContext?, codedOut: CodedOutputStream, obj: Any?) {
            codedOut.writeRawByte(UnsafeProvider.unsafe().getByte(obj, offset))
        }

        // TODO: b/386384684 - remove Unsafe usage
        @Throws(IOException::class)
        override fun deserialize(
            context: AsyncDeserializationContext?, codedIn: CodedInputStream, obj: Any?
        ) {
            UnsafeProvider.unsafe().putByte(obj, offset, codedIn.readRawByte())
        }
    }

    private class ShortHandler(private val offset: Long) : FieldHandler {
        // TODO: b/386384684 - remove Unsafe usage
        @Throws(IOException::class)
        override fun serialize(context: SerializationContext?, codedOut: CodedOutputStream, obj: Any?) {
            CodecHelpers.writeShort(codedOut, UnsafeProvider.unsafe().getShort(obj, offset))
        }

        // TODO: b/386384684 - remove Unsafe usage
        @Throws(IOException::class)
        override fun deserialize(
            context: AsyncDeserializationContext?, codedIn: CodedInputStream, obj: Any?
        ) {
            UnsafeProvider.unsafe().putShort(obj, offset, CodecHelpers.readShort(codedIn))
        }
    }

    private class CharHandler(private val offset: Long) : FieldHandler {
        // TODO: b/386384684 - remove Unsafe usage
        @Throws(IOException::class)
        override fun serialize(context: SerializationContext?, codedOut: CodedOutputStream, obj: Any?) {
            CodecHelpers.writeChar(codedOut, UnsafeProvider.unsafe().getChar(obj, offset))
        }

        // TODO: b/386384684 - remove Unsafe usage
        @Throws(IOException::class)
        override fun deserialize(
            context: AsyncDeserializationContext?, codedIn: CodedInputStream, obj: Any?
        ) {
            UnsafeProvider.unsafe().putChar(obj, offset, CodecHelpers.readChar(codedIn))
        }
    }

    private class IntHandler(private val offset: Long) : FieldHandler {
        // TODO: b/386384684 - remove Unsafe usage
        @Throws(IOException::class)
        override fun serialize(context: SerializationContext?, codedOut: CodedOutputStream, obj: Any?) {
            codedOut.writeInt32NoTag(UnsafeProvider.unsafe().getInt(obj, offset))
        }

        // TODO: b/386384684 - remove Unsafe usage
        @Throws(IOException::class)
        override fun deserialize(
            context: AsyncDeserializationContext?, codedIn: CodedInputStream, obj: Any?
        ) {
            UnsafeProvider.unsafe().putInt(obj, offset, codedIn.readInt32())
        }
    }

    private class LongHandler(private val offset: Long) : FieldHandler {
        // TODO: b/386384684 - remove Unsafe usage
        @Throws(IOException::class)
        override fun serialize(context: SerializationContext?, codedOut: CodedOutputStream, obj: Any?) {
            codedOut.writeInt64NoTag(UnsafeProvider.unsafe().getLong(obj, offset))
        }

        // TODO: b/386384684 - remove Unsafe usage
        @Throws(IOException::class)
        override fun deserialize(
            context: AsyncDeserializationContext?, codedIn: CodedInputStream, obj: Any?
        ) {
            UnsafeProvider.unsafe().putLong(obj, offset, codedIn.readInt64())
        }
    }

    private class FloatHandler(private val offset: Long) : FieldHandler {
        // TODO: b/386384684 - remove Unsafe usage
        @Throws(IOException::class)
        override fun serialize(context: SerializationContext?, codedOut: CodedOutputStream, obj: Any?) {
            codedOut.writeFloatNoTag(UnsafeProvider.unsafe().getFloat(obj, offset))
        }

        // TODO: b/386384684 - remove Unsafe usage
        @Throws(IOException::class)
        override fun deserialize(
            context: AsyncDeserializationContext?, codedIn: CodedInputStream, obj: Any?
        ) {
            UnsafeProvider.unsafe().putFloat(obj, offset, codedIn.readFloat())
        }
    }

    private class DoubleHandler(private val offset: Long) : FieldHandler {
        // TODO: b/386384684 - remove Unsafe usage
        @Throws(IOException::class)
        override fun serialize(context: SerializationContext?, codedOut: CodedOutputStream, obj: Any?) {
            codedOut.writeDoubleNoTag(UnsafeProvider.unsafe().getDouble(obj, offset))
        }

        // TODO: b/386384684 - remove Unsafe usage
        @Throws(IOException::class)
        override fun deserialize(
            context: AsyncDeserializationContext?, codedIn: CodedInputStream, obj: Any?
        ) {
            UnsafeProvider.unsafe().putDouble(obj, offset, codedIn.readDouble())
        }
    }

    private class ObjectHandler(type: java.lang.Class<*>, offset: Long) : FieldHandler,
        com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext.FieldSetter<Any?> {
        private val type: java.lang.Class<*>
        private val offset: Long

        init {
            this.type = type
            this.offset = offset
        }

        // TODO: b/386384684 - remove Unsafe usage
        @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
        override fun serialize(context: SerializationContext, codedOut: CodedOutputStream?, obj: Any?) {
            context.serialize(UnsafeProvider.unsafe().getObject(obj, offset), codedOut)
        }

        @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
        override fun deserialize(
            context: AsyncDeserializationContext, codedIn: CodedInputStream?, obj: Any?
        ) {
            context.deserialize<Any?>(
                codedIn,
                obj,
                this as com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext.FieldSetter<Any?>
            )
        }

        // TODO: b/386384684 - remove Unsafe usage
        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
        override fun set(target: Any?, fieldValue: Any) {
            if (!type.isInstance(fieldValue)) {
                throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                    ("Field "
                            + fieldValue
                            + " was not instance of "
                            + type
                            + " (was "
                            + fieldValue.getClass()
                            + ")")
                )
            }
            UnsafeProvider.unsafe().putObject(target, offset, fieldValue)
        }
    }

    private class ArrayHandler(type: java.lang.Class<*>?, offset: Long) : FieldHandler {
        private val arrayProcessor: ArrayProcessor
        private val type: java.lang.Class<*>?
        private val offset: Long

        init {
            this.arrayProcessor = ArrayProcessor.Companion.forType(type)
            this.type = type
            this.offset = offset
        }

        // TODO: b/386384684 - remove Unsafe usage
        @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
        override fun serialize(context: SerializationContext?, codedOut: CodedOutputStream?, obj: Any?) {
            arrayProcessor.serialize(context, codedOut, type, UnsafeProvider.unsafe().getObject(obj, offset))
        }

        // TODO: b/386384684 - remove Unsafe usage
        @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
        override fun deserialize(
            context: AsyncDeserializationContext?, codedIn: CodedInputStream?, obj: Any?
        ) {
            UnsafeProvider.unsafe().putObject(obj, offset, arrayProcessor.deserialize(context, codedIn, type))
        }
    }

    private class FieldComparator : java.util.Comparator<java.lang.reflect.Field?> {
        override fun compare(f1: java.lang.reflect.Field, f2: java.lang.reflect.Field): Int {
            val classCompare: Int =
                f1.getDeclaringClass().getName().compareTo(f2.getDeclaringClass().getName())
            if (classCompare != 0) {
                return classCompare
            }
            return f1.getName().compareTo(f2.getName())
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /** Creates a codec instance with custom handlers for specified fields.  */
        fun createWithOverrides(
            type: java.lang.Class<*>, overrides: MutableMap<java.lang.reflect.Field?, FieldHandler?>
        ): DynamicCodec {
            val handlers: LinkedHashMap<java.lang.reflect.Field?, FieldHandler> = getFieldHandlerMap(type)
            for (override in overrides.entrySet()) {
                val previous: FieldHandler = handlers.put(override.getKey(), override.getValue())
                requireNotNull(previous) {
                    java.lang.String.format(
                        "An override was specified for %s but no such field was present in the default"
                                + " dynamic codec for %s.",
                        override.getKey(), type
                    )
                }
            }
            return DynamicCodec(
                type,
                handlers.values().toArray<FieldHandler?>(java.util.function.IntFunction { _Dummy_.__Array__() })
            )
        }

        /**
         * Computes the default [FieldHandler]s that would be used for the given type.
         * 
         * 
         * The entries are ordered by [FieldComparator] for determinism. The returned value is a
         * fresh copy that the caller may freely modify.
         */
        // type communicates fixed ordering
        fun <T> getFieldHandlerMap(type: java.lang.Class<T?>?): LinkedHashMap<java.lang.reflect.Field?, FieldHandler> {
            val handlers: LinkedHashMap<java.lang.reflect.Field?, FieldHandler> =
                LinkedHashMap<java.lang.reflect.Field?, FieldHandler>()
            for (field in getSerializableFields<T?>(type)) {
                handlers.put(field, getHandlerForField(field))
            }
            return handlers
        }

        private fun <T> getFieldHandlers(type: java.lang.Class<T?>?): Array<FieldHandler> {
            val fields: MutableList<java.lang.reflect.Field> = getSerializableFields<T?>(type)

            val handlers: Array<FieldHandler> = arrayOfNulls<FieldHandler>(fields.size())
            var i = 0
            for (field in fields) {
                handlers[i++] = getHandlerForField(field)
            }
            return handlers
        }

        private fun <T> getSerializableFields(type: java.lang.Class<T?>?): MutableList<java.lang.reflect.Field> {
            val fields: java.util.ArrayList<java.lang.reflect.Field> = java.util.ArrayList<java.lang.reflect.Field>()
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            // NB: it's tempting to try to simplify this by ordering by offset, but it looks like offsets
            // are not guaranteed to be stable, which is needed for deterministic serialization.
            Collections.sort<java.lang.reflect.Field?>(
                fields,
                com.google.devtools.build.lib.skyframe.serialization.DynamicCodec.FieldComparator()
            )
            return fields
        }

        // TODO: b/386384684 - remove Unsafe usage
        private fun getHandlerForField(field: java.lang.reflect.Field): FieldHandler {
            val offset: Long = UnsafeProvider.unsafe().objectFieldOffset(field)
            val fieldType: java.lang.Class<*> = field.getType()
            if (fieldType.isPrimitive()) {
                if (fieldType == Boolean::class.javaPrimitiveType) {
                    return BooleanHandler(offset)
                } else if (fieldType == Byte::class.javaPrimitiveType) {
                    return ByteHandler(offset)
                } else if (fieldType == Short::class.javaPrimitiveType) {
                    return ShortHandler(offset)
                } else if (fieldType == Char::class.javaPrimitiveType) {
                    return CharHandler(offset)
                } else if (fieldType == Int::class.javaPrimitiveType) {
                    return IntHandler(offset)
                } else if (fieldType == Long::class.javaPrimitiveType) {
                    return LongHandler(offset)
                } else if (fieldType == Float::class.javaPrimitiveType) {
                    return FloatHandler(offset)
                } else if (fieldType == Double::class.javaPrimitiveType) {
                    return DoubleHandler(offset)
                } else {
                    throw java.lang.UnsupportedOperationException(
                        "Unexpected primitive field type " + fieldType + " for " + field.getDeclaringClass()
                    )
                }
            } else if (fieldType.isArray()) {
                return ArrayHandler(fieldType, offset)
            }
            return ObjectHandler(fieldType, offset)
        }
    }
}
