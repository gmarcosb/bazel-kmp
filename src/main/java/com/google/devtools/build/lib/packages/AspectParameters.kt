// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.skyframe.serialization.LeafDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.LeafObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.LeafSerializationContext
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant
import com.google.devtools.build.lib.skyframe.serialization.strings.UnsafeStringCodec
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException

/**
 * Objects of this class contain values of some attributes of rules. Used for passing this
 * information to the aspects.
 */
class AspectParameters private constructor(attributes: com.google.common.collect.Multimap<String?, String?>) {
    private val attributes: com.google.common.collect.ImmutableMultimap<String?, String?>
    private val hashCode: Int

    init {
        this.attributes = com.google.common.collect.ImmutableMultimap.copyOf<String?, String?>(attributes)
        this.hashCode = java.util.Objects.hashCode(this.attributes)
    }

    /** A builder for [AspectParameters] class.  */
    class Builder {
        private val attributes: com.google.common.collect.ImmutableMultimap.Builder<String?, String?> =
            com.google.common.collect.ImmutableMultimap.builder<String?, String?>()

        /** Adds a new pair of attribute-value.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addAttribute(name: String?, value: String?): Builder {
            attributes.put(name, value)
            return this
        }

        /**
         * Creates a new instance of [AspectParameters] class.
         */
        fun build(): AspectParameters? {
            return create(attributes.build())
        }
    }

    /**
     * Returns collection of values for specified key, or an empty collection if key is missing.
     */
    fun getAttribute(key: String?): com.google.common.collect.ImmutableCollection<String?> {
        return attributes.get(key)
    }

    fun getAttributes(): com.google.common.collect.ImmutableMultimap<String?, String?> {
        return attributes
    }

    /**
     * Similar to [.getAttribute]}, but asserts that there's only one value for the provided
     * key.
     * Uses Guava's [Iterables.getOnlyElement], which may throw exceptions if there isn't
     * exactly one element.
     */
    fun getOnlyValueOfAttribute(key: String?): String? {
        return com.google.common.collect.Iterables.getOnlyElement<String?>(getAttribute(key))
    }

    fun isEmpty(): Boolean {
        return this == EMPTY
    }

    // ImmutableMultimap inherits equals from AbstractMultimap
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is AspectParameters) {
            return false
        }
        val that = other
        return this.attributes == that.attributes
    }

    override fun hashCode(): Int {
        return hashCode
    }

    override fun toString(): String {
        return attributes.toString()
    }

    /**
     * This codec causes [AspectParameters] memoization to use [Object.equals].
     * 
     * 
     * This improves determinism over memoization using reference quality, which can result in
     * different serialized representations of equivalent values.
     */
    @com.google.errorprone.annotations.Keep
    private class Codec : LeafObjectCodec<AspectParameters?>() {
        override fun getEncodedClass(): java.lang.Class<AspectParameters?> {
            return AspectParameters::class.java
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun serialize(
            context: LeafSerializationContext, obj: AspectParameters, codedOut: CodedOutputStream
        ) {
            val attributes: com.google.common.collect.ImmutableMap<String?, MutableCollection<String?>?> =
                obj.attributes.asMap()
            codedOut.writeInt32NoTag(attributes.size())
            for (entry in attributes.entrySet()) {
                context.serializeLeaf<String?>(entry.getKey(), UnsafeStringCodec.stringCodec(), codedOut)
                val values: MutableCollection<String?> = entry.getValue()
                codedOut.writeInt32NoTag(values.size())
                for (value in values) {
                    context.serializeLeaf<String?>(value, UnsafeStringCodec.stringCodec(), codedOut)
                }
            }
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun deserialize(
            context: LeafDeserializationContext, codedIn: CodedInputStream
        ): AspectParameters? {
            val size: Int = codedIn.readInt32()
            val builder: com.google.common.collect.ImmutableMultimap.Builder<String?, String?> =
                com.google.common.collect.ImmutableMultimap.builder<String?, String?>()
            for (i in 0..<size) {
                val key: String? = context.deserializeLeaf<String?>(codedIn, UnsafeStringCodec.stringCodec())
                val valuesCount: Int = codedIn.readInt32()
                for (j in 0..<valuesCount) {
                    val value: String? = context.deserializeLeaf<String?>(codedIn, UnsafeStringCodec.stringCodec())
                    builder.put(key, value)
                }
            }
            return create(builder.build())
        }
    }

    companion object {
        @kotlin.jvm.JvmField
        @SerializationConstant
        @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
        val EMPTY: AspectParameters =
            AspectParameters(com.google.common.collect.ImmutableMultimap.of<String?, String?>())

        private fun create(attributes: com.google.common.collect.ImmutableMultimap<String?, String?>): AspectParameters? {
            if (attributes.isEmpty()) {
                return EMPTY
            }
            return AspectParameters(attributes)
        }
    }
}
