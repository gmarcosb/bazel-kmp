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

import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.LeafDeserializationContext
import com.google.devtools.build.skyframe.SkyKey
import com.google.protobuf.CodedInputStream
import java.io.IOException

/**
 * Context provided to [ObjectCodec] implementations with methods compatible with asynchrony.
 * 
 * 
 * The [.deserialize] signatures are defined in such a way that the context may decide when
 * to make values available.
 * 
 * 
 * The semantics of [.deserialize] can be divided into two cases.
 * 
 * 
 *  * **Acyclic**: any asynchronous activity needed for deserialization is guaranteed to have
 * completed prior to setting the value. Since there are no cycles, this is straightforward to
 * implement by bottom-up futures-chaining. This works for any acyclic backreferences by
 * allowing those backreferences to be stored as futures.
 *  * **Cyclic**: when there are object graph cycles, it means that a node has a reference to
 * one of its ancestors. In this case, during deserialization, the node will observe a
 * partially formed ancestor value, defined by [.registerInitialValue]. It's impossible
 * to guarantee that the provided value is complete due to the cycle.
 * 
 */
interface AsyncDeserializationContext : LeafDeserializationContext {
    /** Defines a way to set a field in a given object.  */
    interface FieldSetter<T> {
        /**
         * Sets a field of `obj`.
         * 
         * @param target the object that accepts the field value.
         * @param fieldValue the non-null field value.
         */
        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
        fun set(target: T?, fieldValue: Any?)
    }

    /**
     * Registers an initial value for the currently deserializing value, for use by child objects that
     * may have references to it.
     * 
     * 
     * This is a noop when memoization is disabled.
     */
    fun registerInitialValue(initialValue: Any?)

    /**
     * Parses the next object from `codedIn` and sets it in `obj` using `setter`.
     * 
     * 
     * Deserialization may complete asynchronously, for example, when the input requires a Skyframe
     * lookup to compute.
     * 
     * 
     * No value is written when the resulting value is null.
     */
    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun <T> deserialize(codedIn: CodedInputStream?, obj: T?, setter: FieldSetter<in T?>?)

    /**
     * Parses the next object from `codedIn` and writes it into `obj` at `offset`.
     * 
     * 
     * This is an overload of [.deserialize] that uses
     * an offset instead and avoids forcing the caller to perform a per-component allocation when
     * deserializing an array. It has similar behavior. The result can be written asynchronously or
     * not at all if its value was null.
     */
    @Deprecated("")
    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun deserialize(codedIn: CodedInputStream?, obj: Any?, offset: Long)

    /**
     * Similar to the `offset` based [.deserialize] above, but includes a `done`
     * callback.
     * 
     * 
     * The `done` callback is called once the assignment is complete, which is useful for
     * container codecs that perform reference counting. The `done` callback is always called,
     * even if the deserialized value is null.
     */
    @Deprecated("")
    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun deserialize(codedIn: CodedInputStream?, obj: Any?, offset: Long, done: java.lang.Runnable?)

    /**
     * Parses the next object from `codedIn` and writes it into `arr` at `index`.
     * 
     * 
     * Deserialization may complete asynchronously, for example, when the input requires a Skyframe
     * lookup to compute.
     * 
     * 
     * No write is performed when the resulting value is null.
     */
    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun deserializeArrayElement(codedIn: CodedInputStream?, arr: Array<Any?>?, index: Int)

    /**
     * Reads a value from key value storage into `obj`.
     * 
     * 
     * Reads the next fingerprint from `codedIn`, fetches the corresponding remote value and
     * deserializes it using `codec` into `obj` using `setter`.
     * 
     * 
     * This method may schedule some activities in the background.
     * 
     * 
     *  * Fetching the data bytes associated with the fingerprint from the stream.
     *  * Waiting for another concurrent read of the same data by a different caller.
     * 
     * 
     * 
     * These background activities are tracked by [ ][SharedValueDeserializationContext.readStatusFutures].
     * 
     * 
     * [DeserializationContext.deserialize] blocks until the background
     * activities are complete.
     * 
     * 
     * TODO: b/297857068 - expose an API enabling callers to release the thread if it is blocked.
     * 
     * @param distinguisher see documentation at [SerializationContext.putSharedValue]
     */
    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun <T> getSharedValue(
        codedIn: CodedInputStream?,
        distinguisher: Any?,
        codec: DeferredObjectCodec<*>?,
        obj: T?,
        setter: FieldSetter<in T?>?
    )

    /**
     * Looks up the [SkyValue] for `key`, and sets it in `parent` using `setter`.
     */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun <T> getSkyValue(key: SkyKey?, parent: T?, setter: FieldSetter<in T?>?)
}
