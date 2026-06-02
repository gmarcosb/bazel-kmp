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
package com.google.devtools.build.lib.skyframe.serialization

import com.google.devtools.build.lib.skyframe.serialization.DeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.ProfilerLocationProvider
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException

/**
 * Generic object serialization/deserialization. Implementations should serialize values
 * deterministically.
 */
interface ObjectCodec<T> : ProfilerLocationProvider {
    /**
     * Returns the class of the objects serialized/deserialized by this codec.
     * 
     * 
     * This is useful for automatically dispatching to the correct codec, e.g. in [ ].
     * 
     * 
     * If [T] is an interface, then this codec will never be used by the auto-registration
     * framework in [ObjectCodecRegistry] unless it is explicitly invoked or [ ][.additionalEncodedClasses] is non-empty, since the [ObjectCodecRegistry] traverses the
     * concrete class hierarchy looking for matches, and will never come to an interface.
     */
    fun getEncodedClass(): java.lang.Class<out T?>

    override fun getLocationText(): String {
        val encodedClass: java.lang.Class<*> = getEncodedClass()
        var name: String? = encodedClass.getCanonicalName()
        if (name == null) {
            name = encodedClass.getName() // anonymous classes have a name, but no canonical name
        }
        return name + "(" + getClass().getCanonicalName() + ")"
    }

    /**
     * Returns additional subtypes of `T` that may be serialized/deserialized using this codec
     * without loss of information.
     * 
     * 
     * This method is intended for when `T` has multiple concrete implementations whose
     * details are known to the codec but not to the codec dispatching mechanism. It signals that the
     * dispatcher may choose to use this codec for the subtype, rather than raise [ ].
     * 
     * 
     * If the additional subtype already has an existing codec registered with [ ][ObjectCodec.getEncodedClass], this codec will take precedence and overwrite the other codec.
     * 
     * 
     * This method should not be used if the codec's serialization and deserialization methods
     * perform their own dispatching to other codecs for subtypes of `T`.
     * 
     * 
     * `T` itself should not be included in the returned list.
     */
    fun additionalEncodedClasses(): com.google.common.collect.ImmutableSet<java.lang.Class<out T?>> {
        return com.google.common.collect.ImmutableSet.of<java.lang.Class<out T?>?>()
    }

    /**
     * Whether the codec should be considered for automatic registration by [CodecScanner].
     * 
     * 
     * In order to qualify for automatic registration, the class must have a name ending in `Codec` and have a parameterless constructor. If either of these prerequisites are not met,
     * [CodecScanner] will silently skip the codec even if this method returns `true`.
     */
    fun autoRegister(): Boolean {
        return true
    }

    /**
     * Serializes `obj`, inverse of [.deserialize].
     * 
     * @param context [SerializationContext] providing additional information to the
     * serialization process
     * @param obj the object to serialize
     * @param codedOut the [CodedOutputStream] to write this object into. Implementations need
     * not call [CodedOutputStream.flush], this should be handled by the caller.
     * @throws SerializationException on failure to serialize
     * @throws IOException on [IOException] during serialization
     */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    fun serialize(context: SerializationContext?, obj: T?, codedOut: CodedOutputStream?)

    /**
     * Deserializes from `codedIn`, inverse of [.serialize].
     * 
     * @param context [DeserializationContext] for providing additional information to the
     * deserialization process.
     * @param codedIn the [CodedInputStream] to read the serialized object from
     * @return the object deserialized from `codedIn`
     * @throws SerializationException on failure to deserialize
     * @throws IOException on [IOException] during deserialization
     */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    fun deserialize(context: DeserializationContext?, codedIn: CodedInputStream?): T?

    /**
     * Returns the memoization strategy for this codec.
     * 
     * 
     * If set to [MemoizationStrategy.MEMOIZE_BEFORE], then [ ][DeserializationContext.registerInitialValue] must be called first in the [.deserialize]
     * method, before delegating to any other codecs.
     * 
     * 
     * Implementations of this method should just return a constant, since the choice of strategy
     * is usually intrinsic to [T].
     */
    fun getStrategy(): MemoizationStrategy? {
        return MemoizationStrategy.MEMOIZE_AFTER
    }

    /** Indicates how an [ObjectCodec] is memoized.  */
    enum class MemoizationStrategy {
        /**
         * Indicates that the value is memoized before recursing to its children, so that it is
         * available to form cyclic references from its children. If this strategy is used, [ ][DeserializationContext.registerInitialValue] must be called during the [.deserialize]
         * method.
         * 
         * 
         * This should be used for all types where it is feasible to provide an initial value. Any
         * cycle that does not go through at least one `MEMOIZE_BEFORE` type of value (e.g., a
         * pathological self-referential tuple) is unserializable.
         */
        MEMOIZE_BEFORE,

        /**
         * Indicates that the value is memoized after recursing to its children, so that it cannot be
         * referred to until after it has been constructed (regardless of whether its children are still
         * under construction).
         * 
         * 
         * This is typically used for immutable types, since they cannot be created by mutating an
         * initial value.
         */
        MEMOIZE_AFTER
    }

    /**
     * Checks that `obj` is assignable to either [.getEncodedClass] or one of the [ ][.additionalEncodedClasses].
     */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun safeCast(obj: Any?): T? {
        if (obj == null) {
            return null
        }
        val type: java.lang.Class<*> = obj.getClass()
        if (getEncodedClass().isAssignableFrom(type)) {
            return obj as T
        }
        val additionalTypes: com.google.common.collect.ImmutableSet<java.lang.Class<out T?>> =
            additionalEncodedClasses()
        if (additionalTypes.contains(obj)) {
            return obj as T
        }
        for (expectedType in additionalTypes) {
            if (expectedType.isAssignableFrom(type)) {
                return obj as T
            }
        }
        throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
            ("Object "
                    + obj
                    + ") has type "
                    + type.getName()
                    + " but expected type one of "
                    + com.google.common.collect.ImmutableSet.builder<Any?>()
                .add(getEncodedClass())
                .addAll(additionalEncodedClasses())
                .build())
        )
    }
}
