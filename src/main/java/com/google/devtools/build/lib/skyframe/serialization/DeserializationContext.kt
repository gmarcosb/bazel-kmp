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

import com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry
import com.google.devtools.build.lib.unsafe.UnsafeProvider
import com.google.devtools.build.skyframe.SkyKey
import com.google.protobuf.CodedInputStream
import java.io.IOException

/**
 * Stateful class for providing additional context to a single deserialization "session". This class
 * is thread-safe so long as [.deserializer] is null. If it is not null, this class is not
 * thread-safe and should only be accessed on a single thread for deserializing one serialized
 * object (that may contain other serialized objects inside it).
 */
abstract class DeserializationContext internal constructor(
    registry: ObjectCodecRegistry,
    dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>
) : AsyncDeserializationContext {
    private val registry: ObjectCodecRegistry
    private val dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>

    init {
        this.registry = registry
        this.dependencies = dependencies
    }

    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun <T> deserialize(codedIn: CodedInputStream): T? {
        return makeSynchronous(processTagAndDeserialize(codedIn)) as T?
    }

    /**
     * Deserializes into `parent` using `setter`.
     * 
     * 
     * This allows custom processing of the deserialized object.
     */
    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    override fun <T> deserialize(
        codedIn: CodedInputStream,
        parent: T?,
        setter: com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext.FieldSetter<in T?>
    ) {
        val value = deserialize<Any?>(codedIn)
        if (value == null) {
            return
        }
        setter.set(parent, value)
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Throws(
        IOException::class,
        com.google.devtools.build.lib.skyframe.serialization.SerializationException::class
    )  // TODO: b/331765692 - delete this
    override fun deserialize(codedIn: CodedInputStream, parent: Any?, offset: Long) {
        UnsafeProvider.unsafe().putObject(parent, offset, deserialize<Any?>(codedIn))
    }

    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    override fun deserialize(codedIn: CodedInputStream, parent: Any?, offset: Long, done: java.lang.Runnable) {
        deserialize(codedIn, parent, offset)
        done.run()
    }

    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    override fun deserializeArrayElement(codedIn: CodedInputStream, arr: Array<Any?>, index: Int) {
        val value = deserialize<Any?>(codedIn)
        if (value == null) {
            return
        }
        arr[index] = value
    }

    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    override fun <T> getSharedValue(
        codedIn: CodedInputStream?,
        distinguisher: Any?,
        codec: DeferredObjectCodec<*>?,
        parent: T?,
        setter: com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext.FieldSetter<in T?>?
    ) {
        throw java.lang.UnsupportedOperationException()
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    override fun <T> getSkyValue(
        key: SkyKey?,
        parent: T?,
        setter: com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext.FieldSetter<in T?>?
    ) {
        throw java.lang.UnsupportedOperationException("Only supported by SharedValueDeserializationContext")
    }

    override fun <T> getDependency(type: java.lang.Class<T?>): T? {
        return com.google.common.base.Preconditions.checkNotNull<T?>(
            dependencies.getInstance<T?>(type),
            "Missing dependency of type %s",
            type
        )
    }

    /** Returns a copy of the context with reset state.  */ // TODO: b/297857068 - Only the NestedSetCodecWithStore requires this method. Delete it when it is
    // no longer needed.
    abstract val freshContext: DeserializationContext?
        /** Returns a copy of the context with reset state.  */
        get

    fun getRegistry(): ObjectCodecRegistry {
        return registry
    }

    fun getDependencies(): com.google.common.collect.ImmutableClassToInstanceMap<Any?> {
        return dependencies
    }

    /**
     * Deserializes from `codedIn` using `codec`.
     * 
     * 
     * This extension point allows the implementation optionally apply memoization logic.
     * 
     * 
     * Returns either a deserialized value or a [ListenableFuture]. A [ ] is only possible for [SharedValueDeserializationContext].
     */
    @com.google.errorprone.annotations.ForOverride
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    abstract fun deserializeAndMaybeMemoize(codec: ObjectCodec<*>?, codedIn: CodedInputStream?): Any?

    /**
     * Reads the tag and uses its value to deserialize the next value.
     * 
     * 
     *  * null, if the value was null;
     *  * a [ListenableFuture] that produces the value; or
     *  * the value directly.
     * 
     * 
     * 
     * [ListenableFuture] is only possible for [SharedValueDeserializationContext].
     */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    fun processTagAndDeserialize(codedIn: CodedInputStream): Any? {
        val tag: Int = codedIn.readSInt32()
        if (tag == 0) {
            return null
        }
        if (tag < 0) {
            // Subtracts 1 to undo transformation from SerializationContext to avoid null.
            return getMemoizedBackReference(-tag - 1)
        }
        val constant: Any? = registry.maybeGetConstantByTag(tag)
        if (constant != null) {
            return constant
        }
        // Performs deserialization using the specified codec.
        return deserializeAndMaybeMemoize(registry.getCodecDescriptorByTag(tag).codec, codedIn)
    }

    fun maybeGetConstantByTag(tag: Int): Any? {
        return registry.maybeGetConstantByTag(tag)
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    abstract fun getMemoizedBackReference(memoIndex: Int): Any?

    /**
     * Returns the result value.
     * 
     * 
     * In the [SharedValueDeserializationContext], [ ][DeserializationContext.deserializeAndMaybeMemoize] may produce futures. This method is
     * overridden to unwrap them.
     */
    @com.google.errorprone.annotations.ForOverride
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    open fun makeSynchronous(obj: Any?): Any? {
        return obj
    }
}
