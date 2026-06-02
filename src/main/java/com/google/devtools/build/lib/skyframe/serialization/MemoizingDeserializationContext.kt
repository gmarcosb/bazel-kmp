// Copyright 2024 The Bazel Authors. All rights reserved.
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
import com.google.devtools.build.lib.skyframe.serialization.LeafDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.LeafObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec.MemoizationStrategy
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecs
import com.google.protobuf.ByteString
import com.google.protobuf.CodedInputStream
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import java.io.IOException
import java.util.ArrayDeque
import java.util.Deque

/**
 * [DeserializationContext] that performs memoization, see [ ] for the protocol description.
 */
internal abstract class MemoizingDeserializationContext(
    registry: ObjectCodecRegistry?,
    dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>?
) : DeserializationContext(registry, dependencies) {
    private val memoTable: Int2ObjectOpenHashMap<Any> = Int2ObjectOpenHashMap<Any>()
    private var tagForMemoizedBefore = -1
    private val memoizedBeforeStackForSanityChecking: Deque<Any?> = ArrayDeque<Any?>()

    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    override fun <T> deserializeLeaf(codedIn: CodedInputStream, codec: LeafObjectCodec<T?>): T? {
        val tag: Int = codedIn.readSInt32()
        if (tag == 0) {
            return null
        }
        val maybeConstant: Any? = maybeGetConstantByTag(tag)
        if (maybeConstant != null) {
            return codec.safeCast(maybeConstant)
        }
        if (tag < -1) {
            // Subtracts 2 to undo the corresponding operation in SerializationContext.serializeLeaf.
            return codec.safeCast(getMemoizedBackReference(-tag - 2))
        }
        com.google.common.base.Preconditions.checkState(tag == -1, "Unexpected tag for immediate value; %s", tag)
        val value: T? = codec.deserialize(this as LeafDeserializationContext, codedIn)
        memoize(memoTable.size(), value)
        return value
    }

    override fun registerInitialValue(initialValue: Any?) {
        com.google.common.base.Preconditions.checkState(
            tagForMemoizedBefore != -1,
            "Not called with memoize before: %s",
            initialValue
        )
        val tag = tagForMemoizedBefore
        tagForMemoizedBefore = -1
        // Replaces the INITIAL_VALUE_PLACEHOLDER with the actual initial value.
        com.google.common.base.Preconditions.checkState(memoTable.put(tag, initialValue) === INITIAL_VALUE_PLACEHOLDER)
        memoizedBeforeStackForSanityChecking.addLast(initialValue)
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    override fun getMemoizedBackReference(memoIndex: Int): Any {
        val value: Any = memoTable.get(memoIndex)
        if (value == null) {
            throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                "got backreference " + memoIndex + " without corresponding entry"
            )
        }
        com.google.common.base.Preconditions.checkState(
            value !== INITIAL_VALUE_PLACEHOLDER,
            "Backreference prior to registerInitialValue: %s",
            memoIndex
        )
        return value
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserializeAndMaybeMemoize(codec: ObjectCodec<*>, codedIn: CodedInputStream): Any? {
        com.google.common.base.Preconditions.checkState(
            tagForMemoizedBefore == -1,
            "non-null memoized-before tag %s (%s)",
            tagForMemoizedBefore,
            codec
        )
        return when (codec.getStrategy()) {
            MemoizationStrategy.MEMOIZE_BEFORE -> deserializeMemoBeforeContent(codec, codedIn)
            MemoizationStrategy.MEMOIZE_AFTER -> deserializeMemoAfterContent(codec, codedIn)
        }
    }

    /**
     * Deserializes from `codedIn` using `codec`.
     * 
     * 
     * This extension point allows the implementation to optionally handle read futures and surface
     * [DeferredValue]s, which are possible for [SharedValueDeserializationContext].
     * 
     * 
     * This can return either a deserialized value or a [DeferredValue]. A [ ] is only possible for [SharedValueDeserializationContext].
     */
    @com.google.errorprone.annotations.ForOverride
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    abstract fun deserializeAndMaybeHandleDeferredValues(
        codec: ObjectCodec<*>?, codedIn: CodedInputStream?
    ): Any?

    /**
     * Corresponds to MemoBeforeContent in the abstract grammar.
     * 
     * 
     * May return a deserialized value or a [ListenableFuture]. The [ListenableFuture]
     * is only possible for [SharedValueDeserializationContext].
     */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    private fun deserializeMemoBeforeContent(codec: ObjectCodec<*>?, codedIn: CodedInputStream?): Any? {
        val tag: Int = memoTable.size()
        // During serialization, the top-level object is the first object to be memoized regardless of
        // the codec implementation. During deserialization, the top-level object only becomes
        // available after `registerInitialValue` is called and some codecs may perform deserialization
        // operations prior to `registerInitialValue`. To keep the tags in sync with the size of
        // the `memoTable`, adds a placeholder for the top-level object.
        memoTable.put(tag, INITIAL_VALUE_PLACEHOLDER)
        this.tagForMemoizedBefore = tag
        // `codec` is never a `DeferredObjectCodec` because those are `MEMOIZE_AFTER` so this is always
        // the deserialized value instance and never a `DeferredValue`.
        val value = deserializeAndMaybeHandleDeferredValues(codec, codedIn)
        val initial: Any? = memoizedBeforeStackForSanityChecking.removeLast()
        if (value !== initial) {
            // This indicates a bug in the particular codec subclass.
            throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                java.lang.String.format(
                    "codec did not return the initial instance: %s but was %s with codec %s",
                    value, initial, codec
                )
            )
        }

        val combinedValue = combineValueWithReadFutures(value)
        if (combinedValue !== value) {
            // If the combined value is different, it means that it is a ListenableFuture and there are
            // are read futures for this value. The (partial) value for `tag` will already be memoized by
            // `registerInitialValue` at this point.
            //
            // Any backreferences to the existing entry would be from cyclic children, which
            // tautologically need to tolerate incomplete values anyway. However, any subsequent
            // backreferences will observe the ListenableFuture and process it so that only complete
            // values are consumed.
            updateMemoEntry(tag, combinedValue)
            return combinedValue
        }
        return value
    }

    /**
     * Corresponds to MemoAfterContent in the abstract grammar.
     * 
     * 
     * May return either a deserialized value or a [ListenableFuture]. The [ ] is only possible for [SharedValueDeserializationContext].
     */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    private fun deserializeMemoAfterContent(codec: ObjectCodec<*>?, codedIn: CodedInputStream): Any? {
        val value =
            combineValueWithReadFutures(deserializeAndMaybeHandleDeferredValues(codec, codedIn))
        val tag: Int = codedIn.readInt32()
        // If deserializing the children caused the parent object itself to be deserialized due to
        // a cycle, then there's now a memo entry for the parent. Reuse that object, discarding
        // the one we were trying to construct here, so as to avoid creating duplicate objects in
        // the object graph.
        val cyclicallyCreatedObject: Any? = memoTable.get(tag)
        if (cyclicallyCreatedObject != null) {
            return cyclicallyCreatedObject
        }
        memoize(tag, value)
        return value
    }

    /**
     * Incorporates read futures in the context together with `value`.
     * 
     * 
     * May return the deserialized value or a [ListenableFuture] that wraps the deserialized
     * value. The [ListenableFuture] is only possible for [ ].
     */
    @com.google.errorprone.annotations.ForOverride
    abstract fun combineValueWithReadFutures(value: Any?): Any?

    /**
     * Adds a new id → object maplet to the memo table.
     * 
     * 
     * It is an error if the value is already be present.
     */
    private fun memoize(id: Int, value: Any?) {
        val prev: Any? = memoTable.put(id, com.google.common.base.Preconditions.checkNotNull<Any?>(value))
        require(prev == null) {
            java.lang.String.format(
                "Tried to memoize id %s to object '%s', when it is already memoized to object"
                        + " '%s'",
                id, value, prev
            )
        }
    }

    private fun updateMemoEntry(id: Int, newValue: Any?) {
        val prev: Any = memoTable.put(id, newValue)
        com.google.common.base.Preconditions.checkState(
            prev != null,
            "Tried to update id %s but there was no previous entry",
            id
        )
    }

    private class MemoizingDeserializationContextImpl
        (registry: ObjectCodecRegistry?, dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>?) :
        MemoizingDeserializationContext(registry, dependencies) {
        override fun getFreshContext(): MemoizingDeserializationContext {
            return MemoizingDeserializationContextImpl(getRegistry(), getDependencies())
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun deserializeAndMaybeHandleDeferredValues(codec: ObjectCodec<*>, codedIn: CodedInputStream?): Any? {
            return codec.safeCast(codec.deserialize(this, codedIn))
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun combineValueWithReadFutures(value: Any?): Any? {
            return value
        }
    }

    private class PlaceholderValue
    companion object {
        /**
         * A placeholder that keeps the size of [.memoTable] consistent with the numbering of its
         * contents.
         */
        private val INITIAL_VALUE_PLACEHOLDER = PlaceholderValue()

        @com.google.common.annotations.VisibleForTesting // private
        fun createForTesting(
            registry: ObjectCodecRegistry?, dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>?
        ): MemoizingDeserializationContext {
            return MemoizingDeserializationContextImpl(registry, dependencies)
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
        fun deserializeMemoized(
            codecRegistry: ObjectCodecRegistry?,
            dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>?,
            bytes: ByteString
        ): Any? {
            return ObjectCodecs.Companion.deserializeStreamFully(
                bytes.newCodedInput(),
                MemoizingDeserializationContextImpl(codecRegistry, dependencies)
            )
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
        fun deserializeMemoized(
            codecRegistry: ObjectCodecRegistry?,
            dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>?,
            bytes: ByteArray
        ): Any? {
            return ObjectCodecs.Companion.deserializeStreamFully(
                CodedInputStream.newInstance(bytes),
                MemoizingDeserializationContextImpl(codecRegistry, dependencies)
            )
        }
    }
}
