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

import com.google.protobuf.UnknownFieldSet

/** Codec for protos.  */
class MessageLiteCodec(type: java.lang.Class<out MessageLite?>) : LeafObjectCodec<MessageLite?>() {
    private val type: java.lang.Class<out MessageLite?>?

    /** Instantiates [MessageLite.Builder] via [MessageLite.newBuilderForType].  */
    private val defaultInstance: MessageLite

    init {
        this.type = type
        try {
            val m: java.lang.reflect.Method = type.getMethod("getDefaultInstance")
            this.defaultInstance = m.invoke(null) as MessageLite
        } catch (e: java.lang.ReflectiveOperationException) {
            throw java.lang.IllegalArgumentException("Invalid proto class " + type.getCanonicalName(), e)
        }
    }

    override fun getEncodedClass(): java.lang.Class<out MessageLite?>? {
        return type
    }

    @Throws(IOException::class)
    override fun serialize(
        context: LeafSerializationContext?, message: MessageLite?, codedOut: CodedOutputStream
    ) {
        codedOut.writeMessageNoTag(message)
    }

    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    override fun deserialize(context: LeafDeserializationContext?, codedIn: CodedInputStream): MessageLite? {
        // Don't hold on to full byte array when constructing this proto.
        codedIn.enableAliasing(false)
        try {
            val builder: MessageLite.Builder = defaultInstance.newBuilderForType()
            codedIn.readMessage(builder, ExtensionRegistryLite.getEmptyRegistry())
            return builder.build()
        } catch (e: InvalidProtocolBufferException) {
            throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                "Failed to parse proto of type " + type,
                e
            )
        } finally {
            codedIn.enableAliasing(true)
        }
    }

    @Suppress("unused") // Used reflectively.
    private class MessageLiteCodecRegisterer : CodecRegisterer {
        override fun getCodecsToRegister(): com.google.common.collect.ImmutableList<ObjectCodec<*>?> {
            return com.google.common.collect.ImmutableList.of<ObjectCodec<*>?>(MessageLiteCodec(UnknownFieldSet::class.java))
        }
    }
}
