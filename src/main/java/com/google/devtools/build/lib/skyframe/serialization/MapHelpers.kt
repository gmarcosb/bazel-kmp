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
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Helpers for [Map] serialization.  */
internal object MapHelpers {
    /**
     * Serializes the map's entries.
     * 
     * 
     * Note: this does not include the map's size.
     */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    fun serializeMapEntries(context: SerializationContext, map: MutableMap<*, *>, codedOut: CodedOutputStream?) {
        for (next in map.entrySet()) {
            val entry = next as MutableMap.MutableEntry<*, *>
            context.serialize(entry.getKey(), codedOut)
            try {
                context.serialize(entry.getValue(), codedOut)
            } catch (e: com.google.devtools.build.lib.skyframe.serialization.SerializationException) {
                throw com.google.devtools.build.lib.skyframe.serialization.SerializationException.Companion.propagate(
                    java.lang.String.format(
                        "Exception while serializing value of type %s for key '%s'",
                        entry.getValue().getClass().getName(), entry.getKey()
                    ),
                    e
                )
            } catch (e: IOException) {
                throw com.google.devtools.build.lib.skyframe.serialization.SerializationException.Companion.propagate(
                    java.lang.String.format(
                        "Exception while serializing value of type %s for key '%s'",
                        entry.getValue().getClass().getName(), entry.getKey()
                    ),
                    e
                )
            }
        }
    }

    /**
     * Deserializes map entries into the given `keys` and `values`.
     * 
     * 
     * There's no direct indication of when the deserialization is complete so this should be used
     * with a [DeferredObjectCodec].
     */
    // TODO: b/386384684 - remove Unsafe usage
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    fun deserializeMapEntries(
        context: AsyncDeserializationContext, codedIn: CodedInputStream?, keys: Array<Any?>, values: Array<Any?>
    ) {
        val size = keys.size
        com.google.common.base.Preconditions.checkArgument(values.size == size, "%s %s", keys.size, values.size)
        var offset: Long = sun.misc.Unsafe.ARRAY_OBJECT_BASE_OFFSET.toLong()
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
    }

    /**
     * Populates the map entries with a `done` callback.
     * 
     * 
     * The `done` callback is called when all the entries have been deserialized. The `keys` are fully deserialized when the `done` callback is called. The `values`
     * references will all be available but they might only be partially deserialized.
     */
    // TODO: b/386384684 - remove Unsafe usage
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    fun deserializeMapEntries(
        context: AsyncDeserializationContext,
        codedIn: CodedInputStream?,
        keys: Array<Any?>,
        values: Array<Any?>,
        done: java.lang.Runnable
    ) {
        val size = keys.size
        com.google.common.base.Preconditions.checkArgument(values.size == size, "%s %s", keys.size, values.size)
        val countDown = ReferenceCounter(2 * size, done)
        var offset: Long = sun.misc.Unsafe.ARRAY_OBJECT_BASE_OFFSET.toLong()
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
    }

    private class ReferenceCounter(size: Int, done: java.lang.Runnable) : java.lang.Runnable {
        private val remaining: AtomicInteger
        private val done: java.lang.Runnable

        init {
            this.remaining = AtomicInteger(size)
            this.done = done
        }

        override fun run() {
            if (remaining.decrementAndGet() == 0) {
                done.run()
            }
        }
    }
}
