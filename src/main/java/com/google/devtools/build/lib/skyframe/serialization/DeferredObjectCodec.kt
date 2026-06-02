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
import com.google.devtools.build.lib.skyframe.serialization.DeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec.MemoizationStrategy
import com.google.protobuf.CodedInputStream
import java.io.IOException

/**
 * [ObjectCodec] that returns a continuation when deserializing.
 * 
 * 
 * The [AsyncDeserializationContext] can defer invoking of the continuation until all
 * asynchronous dependencies are resolved.
 */
abstract class DeferredObjectCodec<T> : ObjectCodec<T?> {
    /**
     * A supplier-like object returned when deserializing with this codec.
     * 
     * 
     * Does not include any synchronization. The caller must ensure that [.call] is not
     * called until after all requested sub-values are available.
     * 
     * 
     * This interface should only be used by codec implementations and serialization code.
     */
    interface DeferredValue<T> : java.util.concurrent.Callable<T?> {
        override fun call(): T?
    }

    /**
     * A no-frills implementation of [DeferredValue] that provides a static function to set the
     * deserialized value. This is for use with `SharedValueDeserializationContext#getSharedValue`.
     */
    class SimpleDeferredValue<T> private constructor() : DeferredValue<T?> {
        private var t: T? = null

        override fun call(): T? {
            return t
        }

        companion object {
            @kotlin.jvm.JvmStatic
            fun <T> create(): SimpleDeferredValue<T?> {
                return SimpleDeferredValue<T?>()
            }

            fun <T> set(dv: SimpleDeferredValue<T?>, obj: Any?) {
                dv.t = obj as T?
            }
        }
    }

    val strategy: MemoizationStrategy
        get() = MemoizationStrategy.MEMOIZE_AFTER

    /** Implementation that adapts this codec for synchronous use.  */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserialize(context: DeserializationContext?, codedIn: CodedInputStream?): T? {
        return deserializeDeferred(context, codedIn)!!.call()
    }

    /**
     * This differs from [.deserialize] by using the narrower [ ] and returning a [DeferredValue].
     * 
     * 
     * This is used in cases where the deserialized object cannot even be constructed before the
     * children become available, which is common for immutable types.
     * 
     * 
     * [DeferredValue.call] is invoked when all child objects are available. These are
     * completely deserialized except if the child is a reference to a parent. See comment at [ ] for details.
     */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    abstract fun deserializeDeferred(
        context: AsyncDeserializationContext?, codedIn: CodedInputStream?
    ): DeferredValue<out T?>?
}
