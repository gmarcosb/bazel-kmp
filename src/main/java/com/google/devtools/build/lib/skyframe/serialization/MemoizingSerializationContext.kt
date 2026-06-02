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

import com.google.devtools.build.lib.skyframe.serialization.LeafObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.LeafSerializationContext
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec.MemoizationStrategy
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry
import com.google.devtools.build.lib.skyframe.serialization.ProfileCollector
import com.google.devtools.build.lib.skyframe.serialization.ProfileRecorder
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import com.google.protobuf.ByteString
import com.google.protobuf.CodedOutputStream
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap
import java.io.IOException
import java.util.HashSet

/**
 * [SerializationContext] with memoization tables.
 * 
 * 
 * Memoization is useful both for performance and, in the case of cyclic data structures, to help
 * avoid infinite recursion.
 * 
 * 
 * The memo table associates each value with an integer identifier.
 * 
 * 
 *  * *On the sending end:* The first time a value is to be serialized, a new id is created
 * and a mapping for it is added to the table. The id is emitted on the wire alongside the
 * value's serialized representation. If the same value surfaces again later on, instead of
 * reserializing it, we just emit a backreference consisting of its id.
 *  * *On the receiving end:* Each deserialized value is stored in the memo table along with
 * the id it was associated with. When a backreference is read, the value in the memo table is
 * returned instead of deserializing a new copy of that same value.
 * 
 * 
 * 
 * Cyclic data structures can occur either naturally in complex types, or as a result of a
 * pathological Starlark program. An example of the former is how a user-defined Starlark function
 * implicitly refers to its global frame, which in turn refers to the functions defined in that
 * frame. An example of the latter is a list that the user mutates to contain itself as an element.
 * Such pathological values in Starlark are technically allowed, but they are not useful since
 * Starlark prohibits recursive function calls. They can also expose implementation bugs in code
 * that is not expecting them (b/30310522).
 * 
 * 
 * Ideally, to handle cyclic data structures, the serializer should add the value to the memo
 * table *before* actually performing the serialization. For example, to handle the recursive
 * list `L = ["A", L]`, the serializer should do the following:
 * 
 * 
 *  1. add a memo mapping from `L` to a fresh id, `k`
 *  1. emit `k` to the wire
 *  1. serialize `L`, which means it has to
 * 
 *  1. write its length, `2`
 *  1. serialize the string `"A"`
 *  1. serialize `L`, but since this matches the entry added in Step 1, it just emits
 * `k` as a backreference
 * 
 * 
 * 
 * The problem is that on the other end of the wire, the deserializer needs to associate a value
 * with the memo entry for k before `L` has been fully formed. To solve this, we associate k
 * with a new empty list as the initial value, then allow the deserialization logic to mutate this
 * list to form `L`. It is important that this is done by mutating the initial list rather
 * than by replacing it with another list, since each backreference to k creates an actual Java
 * reference to the initial object.
 * 
 * 
 * However, this strategy does not work for all types of values. There is no way to deserialize
 * the recursive tuple `T = ("A", T)`, because tuples are immutable and therefore cannot be
 * instantiated before all of their elements have been. Rather than restrict serialization to only
 * mutable types, or add a special way for deserializers to modify seemingly immutable types, we
 * simply don't memoize immutable types until after they are fully constructed. This means that
 * `T` is not serializable. But that's okay, because objects like `T` should not even be
 * able to exist (barring a hidden API or reflection). In general, all cycles must go through at
 * least one mutable type of value.
 * 
 * 
 * Aside from mutability, there is another potential problem: One of the types' constructors may
 * enforce an invariant that is not satisfied at the time the constructor is invoked, even though it
 * may be satisfied once construction of all objects is complete. For instance, suppose a type
 * `Foo` has a constructor that takes a non-empty list. Then we would fail to deserialize
 * `L = [Foo(L)]`, since `L` is initially empty. Such a list could legally be formed by
 * putting other elements in `L` before creating `Foo`, and then later removing those
 * other elements. But there's no general way for a deserializer to know how to do that. Therefore,
 * it is the caller's responsibility to ensure the following property:
 * 
 * <blockquote>
 * 
 * For any value that is to be serialized, if the value has children that directly or indirectly
 * contain the value, then the value must be constructible even when those children are in a
 * semi-constructed state.
 * 
</blockquote> * 
 * 
 * where "semi-constructed state" means any state that can be produced by the codecs for those
 * children. Other serialization systems address this issue by providing multiple hooks for types to
 * setup their invariants, but we keep the API relatively simple.
 * 
 * 
 * Round-tripping a value through memoized serialization and deserialization is guaranteed to
 * preserve the object graph, i.e., to not duplicate a value. For mutable types, a value can only be
 * serialized and deserialized at most once because it is memoized before recursing over its
 * children. For immutable types, although a value can be serialized multiple times, upon
 * deserialization only one copy is retained in the memo table. This is conceptually similar to how
 * Python's Pickle library [
 * handles tuples](https://github.com/python/cpython/blob/3.6/Lib/pickle.py#L754), although in that case they use an abstract machine whereas we do not.
 * 
 * 
 * Wire format, as an abstract grammar:
 * 
 * <pre>`START-->NoMemoContent | `NEW_VALUE` MemoContent | `BACKREF` MemoId MemoContent-->MemoBeforeContent | MemoAfterContent NoMemoContent-->Payload MemoBeforeContent -->MemoId Payload MemoAfterContent-->Payload MemoId MemoId-->int32 `</pre>
 * 
 * where `Payload` is the serialized representation of the value. `Payload` may itself
 * contain complete memo-aware encodings of the value's children.
 */
// TODO(brandjon): Maybe make this more robust against a pathological cycle of immutable objects, so
// that instead of failing with a stack overflow, we detect the cycle and throw
// SerializationException. This requires just a little extra memo tracking for the MEMOIZE_AFTER
// case.
internal abstract class MemoizingSerializationContext(
    codecRegistry: ObjectCodecRegistry?,
    dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>?
) : SerializationContext(codecRegistry, dependencies) {
    private val table: Reference2IntOpenHashMap<Any?> = Reference2IntOpenHashMap<Any?>()

    /** Table for types serialized with [LeafObjectCodec], using value-based equality.  */
    private val leafTable: Object2IntOpenHashMap<Any?> = Object2IntOpenHashMap<Any?>()

    private val explicitlyAllowedClasses: MutableSet<java.lang.Class<*>?> = HashSet<java.lang.Class<*>?>()

    init {
        table.defaultReturnValue(NO_VALUE)
        leafTable.defaultReturnValue(NO_VALUE)
    }

    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    override fun <T> serializeLeaf(
        obj: T?, codec: LeafObjectCodec<T?>, codedOut: CodedOutputStream
    ) {
        val recorder: ProfileRecorder? = getProfileRecorder()
        if (recorder == null) {
            serializeLeafImpl<T?>(obj, codec, codedOut)
            return
        }
        val startBytes: Int = codedOut.getTotalBytesWritten()
        recorder.pushLocation(codec)
        serializeLeafImpl<T?>(obj, codec, codedOut)
        recorder.recordBytesAndPopLocation(startBytes, codedOut)
    }

    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    private fun <T> serializeLeafImpl(
        obj: T?, codec: LeafObjectCodec<T?>, codedOut: CodedOutputStream
    ) {
        if (writeIfNullOrConstant(obj, codedOut)) {
            return
        }
        val maybePrevious = getMemoizedIndex(obj,  /* isLeafType= */true)
        if (maybePrevious != NO_VALUE) {
            // There was a previous entry. Writes a backreference, subtracting 2 to avoid 0 (which
            // indicates null), and -1 (which indicates an immediate value).
            codedOut.writeSInt32NoTag(-maybePrevious - 2)
            return
        }
        // A new entry was added, emits -1 to signal an immediate value, then serializes the value.
        codedOut.writeSInt32NoTag(-1)
        codec.serialize(this as LeafSerializationContext, obj, codedOut)
        // By necessity, a LeafCodec is treated like MEMOIZE_AFTER because when deserializing, the
        // value will only be available as a backreference after its deserialization is complete.
        val unusedId = memoize(obj,  /* isLeafType= */true)
    }

    override fun addExplicitlyAllowedClass(allowedClass: java.lang.Class<*>?) {
        explicitlyAllowedClasses.add(allowedClass)
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    override fun <T> checkClassExplicitlyAllowed(allowedClass: java.lang.Class<T?>?, objectForDebugging: T?) {
        if (!explicitlyAllowedClasses.contains(allowedClass)) {
            throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                (allowedClass
                    .toString() + " not explicitly allowed (allowed classes were: "
                        + explicitlyAllowedClasses
                        + ") and object is "
                        + objectForDebugging)
            )
        }
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun serializeWithCodec(codec: ObjectCodec<Any?>, obj: Any?, codedOut: CodedOutputStream) {
        when (codec.getStrategy()) {
            MemoizationStrategy.MEMOIZE_BEFORE -> {
                // Deserialization can determine the value of the tag from the size of its memo table so
                // the tag does not need to be written to the stream.
                memoize(obj,  /* isLeafType= */false) // LeafObjectCodec is always MEMOIZE_AFTER.
                codec.serialize(this, obj, codedOut)
            }

            MemoizationStrategy.MEMOIZE_AFTER -> {
                codec.serialize(this, obj, codedOut)
                val isLeafType = codec is LeafObjectCodec<*>
                // If serializing the children caused the parent object itself to be serialized due to a
                // cycle, then there's now a memo entry for the parent. Don't overwrite it with a new id.
                val cylicallyCreatedId = getMemoizedIndex(obj, isLeafType)
                val id = if (cylicallyCreatedId != NO_VALUE) cylicallyCreatedId else memoize(obj, isLeafType)
                codedOut.writeInt32NoTag(id)
            }
        }
    }

    @Throws(IOException::class)
    override fun writeBackReferenceIfMemoized(
        obj: Any?, codedOut: CodedOutputStream, isLeafType: Boolean
    ): Boolean {
        val memoizedIndex = getMemoizedIndex(obj, isLeafType)
        if (memoizedIndex == NO_VALUE) {
            return false
        }
        // Subtracts 1 so it will be negative and not collide with null.
        codedOut.writeSInt32NoTag(-memoizedIndex - 1)
        return true
    }

    override fun isMemoizing(): Boolean {
        return true
    }

    /**
     * If the value is already memoized, return its on-the-wire id; otherwise returns [ ][.NO_VALUE].
     */
    private fun getMemoizedIndex(value: Any?, isLeafType: Boolean): Int {
        return if (isLeafType) leafTable.getInt(value) else table.getInt(value)
    }

    /**
     * Adds a new value to the memo table and returns its id.
     * 
     * 
     * `value` must not already be present.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue // may be called for side effect
    private fun memoize(value: Any?, isLeafType: Boolean): Int {
        // Ids count sequentially from 0.
        val newId: Int = table.size() + leafTable.size()
        val maybePrevious: Int = if (isLeafType) leafTable.put(value, newId) else table.put(value, newId)
        com.google.common.base.Preconditions.checkState(
            maybePrevious == NO_VALUE,
            "Memoized object '%s' multiple times",
            value
        )
        return newId
    }

    /**
     * This mainly exists to restrict use of [MemoizingSerializationContext]'s constructor.
     * 
     * 
     * It's also slightly cleaner for [SharedValueSerializationContext] to not inherit the
     * implementation of [.getFreshContext].
     */
    private class MemoizingSerializationContextImpl
        (
        codecRegistry: ObjectCodecRegistry?,
        dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>?
    ) : MemoizingSerializationContext(codecRegistry, dependencies) {
        override fun getFreshContext(): MemoizingSerializationContext {
            return MemoizingSerializationContextImpl(getCodecRegistry(), getDependencies())
        }

        public override fun getProfileRecorder(): ProfileRecorder? {
            return null
        }
    }

    private class MemoizingSerializationProfilingContext
        (
        codecRegistry: ObjectCodecRegistry?,
        dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>?,
        profileCollector: ProfileCollector?
    ) : MemoizingSerializationContext(codecRegistry, dependencies) {
        private val profileRecorder: ProfileRecorder

        init {
            this.profileRecorder = ProfileRecorder(profileCollector)
        }

        override fun getFreshContext(): MemoizingSerializationContext {
            return MemoizingSerializationProfilingContext(
                getCodecRegistry(), getDependencies(), profileRecorder.getProfileCollector()
            )
        }

        public override fun getProfileRecorder(): ProfileRecorder {
            return profileRecorder
        }
    }

    companion object {
        private val NO_VALUE = -1

        @com.google.common.annotations.VisibleForTesting // private
        fun createForTesting(
            codecRegistry: ObjectCodecRegistry?,
            dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>?
        ): MemoizingSerializationContext {
            return MemoizingSerializationContextImpl(codecRegistry, dependencies)
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
        fun serializeToBytes(
            codecRegistry: ObjectCodecRegistry?,
            dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>?,
            subject: Any?,
            outputCapacity: Int,
            bufferCapacity: Int,
            profileCollector: ProfileCollector?
        ): ByteArray? {
            val bytesOut: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream(outputCapacity)
            serializeToStream(
                codecRegistry, dependencies, subject, bytesOut, bufferCapacity, profileCollector
            )
            return bytesOut.toByteArray()
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
        fun serializeToByteString(
            codecRegistry: ObjectCodecRegistry?,
            dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>?,
            subject: Any?,
            outputCapacity: Int,
            bufferCapacity: Int
        ): ByteString? {
            val bytesOut: ByteString.Output = ByteString.newOutput(outputCapacity)
            serializeToStream(
                codecRegistry,
                dependencies,
                subject,
                bytesOut,
                bufferCapacity,  /* profileCollector= */
                null
            )
            return bytesOut.toByteString()
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
        private fun serializeToStream(
            codecRegistry: ObjectCodecRegistry?,
            dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>?,
            subject: Any?,
            output: java.io.OutputStream,
            bufferCapacity: Int,
            profileCollector: ProfileCollector?
        ) {
            val codedOut: CodedOutputStream = CodedOutputStream.newInstance(output, bufferCapacity)
            val context =
                if (profileCollector == null)
                    MemoizingSerializationContextImpl(codecRegistry, dependencies)
                else
                    MemoizingSerializationProfilingContext(
                        codecRegistry, dependencies, profileCollector
                    )
            try {
                context.serialize(subject, codedOut)
                codedOut.flush()
            } catch (e: IOException) {
                throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                    "Failed to serialize " + subject,
                    e
                )
            }
            if (profileCollector != null) {
                context.getProfileRecorder().checkStackEmpty(subject)
                context.getProfileRecorder().onSuccess(true)
            }
        }
    }
}
