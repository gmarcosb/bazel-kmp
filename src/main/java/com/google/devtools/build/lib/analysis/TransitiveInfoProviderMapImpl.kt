// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.analysis.TransitiveInfoProviderEffectiveClassHelper
import com.google.devtools.build.lib.analysis.TransitiveInfoProviderMap
import com.google.devtools.build.lib.analysis.TransitiveInfoProviderMapImpl
import com.google.devtools.build.lib.analysis.TransitiveInfoProviderMapImpl.ValueSharingCodec
import com.google.devtools.build.lib.analysis.TransitiveInfoProviderMapImpl.ValueSharingCodec.DeferredKeysCodec
import com.google.devtools.build.lib.collect.ImmutableSharedKeyMap
import com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec.DeferredValue
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException

/**
 * Implementation of [TransitiveInfoProvider] that uses [ImmutableSharedKeyMap]. For
 * memory efficiency, inheritance is used instead of aggregation as an implementation detail.
 */
class TransitiveInfoProviderMapImpl private constructor(keys: Array<Any?>, values: Array<Any?>) :
    ImmutableSharedKeyMap<Any?, Any?>(keys, values), TransitiveInfoProviderMap {
    public override fun <P : com.google.devtools.build.lib.analysis.TransitiveInfoProvider?> getProvider(providerClass: java.lang.Class<P?>?): P? {
        val effectiveClass: java.lang.Class<out com.google.devtools.build.lib.analysis.TransitiveInfoProvider?>? =
            TransitiveInfoProviderEffectiveClassHelper.get<P?>(providerClass)
        return get(effectiveClass) as P?
    }

    public override fun get(key: com.google.devtools.build.lib.packages.Provider.Key?): com.google.devtools.build.lib.packages.Info? {
        return super.get(key) as com.google.devtools.build.lib.packages.Info?
    }

    public override fun get(legacyKey: String?): Any? {
        return super.get(legacyKey)
    }

    val providerCount: Int
        get() = size()

    override fun getProviderKeyAt(i: Int): Any? {
        return keyAt(i)
    }

    override fun getProviderInstanceAt(i: Int): Any? {
        return valueAt(i)
    }

    // TODO: b/359437873 - generate with @AutoCodec.
    private class ValueSharingCodec

        : DeferredObjectCodec<TransitiveInfoProviderMapImpl?>() {
        override fun autoRegister(): Boolean {
            return false
        }

        val encodedClass: java.lang.Class<TransitiveInfoProviderMapImpl?>
            get() = TransitiveInfoProviderMapImpl::class.java

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun serialize(
            context: SerializationContext, obj: TransitiveInfoProviderMapImpl, codedOut: CodedOutputStream?
        ) {
            context.putSharedValue<Array<Any?>?>(
                obj.getKeys(),  /* distinguisher= */null, DeferredKeysCodec.Companion.INSTANCE, codedOut
            )
            context.serialize(obj.getValuesAsArray(), codedOut)
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream?
        ): DeferredValue<TransitiveInfoProviderMapImpl?> {
            val builder: DeserializationBuilder =
                com.google.devtools.build.lib.analysis.TransitiveInfoProviderMapImpl.DeserializationBuilder()
            context.getSharedValue<DeserializationBuilder?>(
                codedIn,  /* distinguisher= */
                null,
                DeferredKeysCodec.Companion.INSTANCE,
                builder,
                AsyncDeserializationContext.FieldSetter { builder: DeserializationBuilder?, value: Any? ->
                    com.google.devtools.build.lib.analysis.TransitiveInfoProviderMapImpl.DeserializationBuilder.Companion.setKeys(
                        builder,
                        value
                    )
                })
            context.deserialize<DeserializationBuilder?>(
                codedIn,
                builder,
                AsyncDeserializationContext.FieldSetter { builder: DeserializationBuilder?, value: Any? ->
                    com.google.devtools.build.lib.analysis.TransitiveInfoProviderMapImpl.DeserializationBuilder.Companion.setValues(
                        builder,
                        value
                    )
                })
            return builder
        }

        private class DeferredKeysCodec : DeferredObjectCodec<Array<Any?>?>() {
            val encodedClass: java.lang.Class<Array<Any?>?>
                get() = Array<Any>::class.java

            override fun autoRegister(): Boolean {
                return false
            }

            @Throws(
                com.google.devtools.build.lib.skyframe.serialization.SerializationException::class,
                IOException::class
            )
            override fun serialize(context: SerializationContext, obj: Array<Any?>, codedOut: CodedOutputStream) {
                val length = obj.size
                codedOut.writeInt32NoTag(length)
                for (i in 0..<length) {
                    context.serialize(obj[i], codedOut)
                }
            }

            @Throws(
                com.google.devtools.build.lib.skyframe.serialization.SerializationException::class,
                IOException::class
            )
            override fun deserializeDeferred(
                context: AsyncDeserializationContext, codedIn: CodedInputStream
            ): DeferredValue<Array<Any?>?> {
                val length: Int = codedIn.readInt32()
                val values = arrayOfNulls<Any>(length)
                for (i in 0..<length) {
                    context.deserialize<Array<Any?>?>(
                        codedIn,
                        values,
                        com.google.devtools.build.lib.analysis.TransitiveInfoProviderMapImpl.ValueSharingCodec.DeferredKeysCodec.ArrayFieldSetter(
                            i
                        )
                    )
                }
                return DeferredValue { values }
            }

            private class ArrayFieldSetter
                (private val index: Int) : AsyncDeserializationContext.FieldSetter<Array<Any?>?> {
                override fun set(array: Array<Any?>, value: Any?) {
                    array[index] = value
                }
            }

            companion object {
                private val INSTANCE = DeferredKeysCodec()
            }
        }

        companion object {
            private val INSTANCE = ValueSharingCodec()
        }
    }

    @com.google.errorprone.annotations.Keep // used reflectively
    private class Codec : DeferredObjectCodec<TransitiveInfoProviderMapImpl?>() {
        val encodedClass: java.lang.Class<TransitiveInfoProviderMapImpl?>
            get() = TransitiveInfoProviderMapImpl::class.java

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun serialize(
            context: SerializationContext, obj: TransitiveInfoProviderMapImpl, codedOut: CodedOutputStream?
        ) {
            context.serialize(obj.getKeys(), codedOut)
            context.serialize(obj.getValuesAsArray(), codedOut)
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream?
        ): DeferredValue<TransitiveInfoProviderMapImpl?> {
            val builder: DeserializationBuilder =
                com.google.devtools.build.lib.analysis.TransitiveInfoProviderMapImpl.DeserializationBuilder()
            context.deserialize<DeserializationBuilder?>(
                codedIn,
                builder,
                AsyncDeserializationContext.FieldSetter { builder: DeserializationBuilder?, value: Any? ->
                    com.google.devtools.build.lib.analysis.TransitiveInfoProviderMapImpl.DeserializationBuilder.Companion.setKeys(
                        builder,
                        value
                    )
                })
            context.deserialize<DeserializationBuilder?>(
                codedIn,
                builder,
                AsyncDeserializationContext.FieldSetter { builder: DeserializationBuilder?, value: Any? ->
                    com.google.devtools.build.lib.analysis.TransitiveInfoProviderMapImpl.DeserializationBuilder.Companion.setValues(
                        builder,
                        value
                    )
                })
            return builder
        }
    }

    private class DeserializationBuilder

        : DeferredValue<TransitiveInfoProviderMapImpl?> {
        private var keys: Array<Any?>
        private var values: Array<Any?>

        override fun call(): TransitiveInfoProviderMapImpl {
            return TransitiveInfoProviderMapImpl(keys, values)
        }

        companion object {
            private fun setKeys(builder: DeserializationBuilder, value: Any?) {
                builder.keys = value as Array<Any?>
            }

            private fun setValues(builder: DeserializationBuilder, value: Any?) {
                builder.values = value as Array<Any?>
            }
        }
    }

    companion object {
        @SerializationConstant
        @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
        val EMPTY_TRANSITIVE_INFO_PROVIDER_MAP: TransitiveInfoProviderMapImpl = TransitiveInfoProviderMapImpl(
            arrayOfNulls<Any>(0), arrayOfNulls<Any>(0)
        )

        fun empty(): TransitiveInfoProviderMapImpl {
            return EMPTY_TRANSITIVE_INFO_PROVIDER_MAP
        }

        fun create(map: MutableMap<Any?, Any?>): TransitiveInfoProviderMapImpl? {
            val count = map.size
            if (count == 0) {
                return empty()
            }
            val keys = arrayOfNulls<Any>(count)
            val values = arrayOfNulls<Any>(count)
            var i = 0
            for (entry in map.entries) {
                keys[i] = entry.key
                values[i] = entry.value
                ++i
            }
            com.google.common.base.Preconditions.checkArgument(keys.size == values.size)
            return TransitiveInfoProviderMapImpl(keys, values)
        }

        @kotlin.jvm.JvmStatic
        fun valueSharingCodec(): ValueSharingCodec {
            return ValueSharingCodec.Companion.INSTANCE
        }
    }
}
