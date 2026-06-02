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

import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.LeafObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.LeafSerializationContext
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry.CodecDescriptor
import com.google.devtools.build.lib.skyframe.serialization.ProfileRecorder
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.WriteStatus
import com.google.protobuf.CodedOutputStream
import java.io.IOException

/**
 * API provided to [ObjectCodec.serialize] implementations.
 * 
 * 
 * Implementations may be stateful or stateless. The [ImmutableSerializationContext] is
 * thread safe and it has rather flexible usage.
 * 
 * 
 * The two stateful contexts, [MemoizingSerializationContext] and [ ] are tightly coupled to the output bytes. Deserializing memoized
 * streams requires the deserializer to know all the previously serialized values. In practice, it
 * only makes sense to tie the lifetime of a [CodedOutputStream] to the lifetime of a [ ].
 */
abstract class SerializationContext internal constructor(
    codecRegistry: ObjectCodecRegistry,
    dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>
) : LeafSerializationContext {
    private val codecRegistry: ObjectCodecRegistry
    private val dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>

    init {
        this.codecRegistry = codecRegistry
        this.dependencies = dependencies
    }

    /** Serializes `obj` into `codedOut`.  */
    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun serialize(`object`: Any?, codedOut: CodedOutputStream) {
        if (writeIfNullOrConstant(`object`, codedOut)) {
            return
        }
        val descriptor: CodecDescriptor = codecRegistry.getCodecDescriptorForObject(`object`)
        val castCodec: ObjectCodec<Any?>? = descriptor.codec as ObjectCodec<Any?>?
        val recorder: ProfileRecorder? = getProfileRecorder()
        if (recorder == null) {
            serializeImpl(descriptor, castCodec, `object`, codedOut)
            return
        }
        val startBytes: Int = codedOut.getTotalBytesWritten()
        recorder.pushLocation(castCodec)
        serializeImpl(descriptor, castCodec, `object`, codedOut)
        recorder.recordBytesAndPopLocation(startBytes, codedOut)
    }

    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    private fun serializeImpl(
        descriptor: CodecDescriptor,
        castCodec: ObjectCodec<Any?>?,
        `object`: Any?,
        codedOut: CodedOutputStream
    ) {
        if (writeBackReferenceIfMemoized(`object`, codedOut, castCodec is LeafObjectCodec<*>)) {
            return
        }
        codedOut.writeSInt32NoTag(descriptor.tag)
        serializeWithCodec(castCodec, `object`, codedOut)
    }

    /**
     * Serializes `child` with `codec` into a key-value store.
     * 
     * 
     * This globally memoizes `child` by *reference*.
     * 
     * 
     * NOTE: This is only supported by [SharedValueSerializationContext].
     * 
     * @param child *non-null* object to be serialized
     * @param distinguisher an optional distinguisher see [     ]
     */
    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    open fun <T> putSharedValue(
        child: T?,
        distinguisher: Any?,
        codec: DeferredObjectCodec<T?>?,
        codedOut: CodedOutputStream?
    ) {
        throw java.lang.UnsupportedOperationException()
    }

    override fun <T> getDependency(type: java.lang.Class<T?>): T? {
        return com.google.common.base.Preconditions.checkNotNull<T?>(
            dependencies.getInstance<T?>(type),
            "Missing dependency of type %s",
            type
        )
    }

    // TODO: b/297857068 - only the NestedSetCodecWithStore and HeaderInfoCodec call the following
    // 3 methods. Delete or hide them when they are no longer needed.
    /**
     * Returns a copy of the context with reset state.
     * 
     * 
     * This is useful in determining a canonical serialized representation of a subgraph when
     * memoization is enabled. Codecs should typically not need to call this.
     */
    abstract fun getFreshContext(): SerializationContext?

    /**
     * Registers a [ListenableFuture] that must complete successfully before the serialized
     * bytes generated using this context can be written remotely.
     * 
     * 
     * NOTE: This is only supported by [SharedValueSerializationContext].
     */
    open fun addFutureToBlockWritingOn(future: WriteStatus?) {
        throw java.lang.UnsupportedOperationException()
    }

    /**
     * Creates a future that succeeds when all futures stored in this context via [ ][.addFutureToBlockWritingOn] have succeeded, or null if no such futures were stored.
     * 
     * 
     * NOTE: This is only supported by [SharedValueSerializationContext] and only used by
     * [com.google.devtools.build.lib.collect.nestedset.NestedSetStore].
     */
    open fun createFutureToBlockWritingOn(): WriteStatus? {
        throw java.lang.UnsupportedOperationException()
    }

    /**
     * Adds an explicitly allowed class for this serialization context, which must be a memoizing
     * context. Must be called by any codec that transitively serializes an object whose codec calls
     * [.checkClassExplicitlyAllowed].
     * 
     * 
     * Normally called by codecs for [com.google.devtools.build.skyframe.SkyValue] subclasses
     * that know they may encounter an object that is expensive to serialize, like [ ] and [ ] or [ ] and [ ].
     * 
     * 
     * In case of an unexpected failure from [.checkClassExplicitlyAllowed], it should first
     * be determined if the inclusion of the expensive object is legitimate, before it is whitelisted
     * using this method.
     */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    abstract fun addExplicitlyAllowedClass(allowedClass: java.lang.Class<*>?)

    /**
     * Asserts during serialization that the encoded class of this codec has been explicitly
     * whitelisted for serialization (using [.addExplicitlyAllowedClass]). Codecs for objects
     * that are expensive to serialize and that should only be encountered in a limited number of
     * types of [com.google.devtools.build.skyframe.SkyValue]s should call this method to check
     * that the object is being serialized as part of an expected [ ], like [ ] inside [ ].
     */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    abstract fun <T> checkClassExplicitlyAllowed(allowedClass: java.lang.Class<T?>?, objectForDebugging: T?)

    // The following methods are abstract to allow different behaviors depending on whether
    // memoization is enabled.
    /**
     * Serializes `obj` using `codec` into `codedOut`.
     * 
     * 
     * In contrast to [.serialize], this does not handle nulls,
     * reference constants or backreferences.
     */
    @com.google.errorprone.annotations.ForOverride
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    abstract fun serializeWithCodec(
        codec: ObjectCodec<Any?>?, obj: Any?, codedOut: CodedOutputStream?
    )

    /**
     * Attempts to serialize `obj` as a backreference to an already serialized object.
     * 
     * 
     * Never succeeds if memoization is disabled.
     * 
     * @param isLeafType true if the codec used for `obj` would be an instance of [     ]
     * @return true if `obj` was serialized to `codedOut` as a backreference
     */
    @com.google.errorprone.annotations.ForOverride
    @Throws(IOException::class)
    abstract fun writeBackReferenceIfMemoized(
        obj: Any?, codedOut: CodedOutputStream?, isLeafType: Boolean
    ): Boolean

    abstract fun isMemoizing(): Boolean

    @Throws(IOException::class)
    fun writeIfNullOrConstant(`object`: Any?, codedOut: CodedOutputStream): Boolean {
        if (`object` == null) {
            codedOut.writeSInt32NoTag(0)
            return true
        }
        val tag: Int? = codecRegistry.maybeGetTagForConstant(`object`)
        if (tag != null) {
            codedOut.writeSInt32NoTag(tag)
            return true
        }
        return false
    }

    fun getCodecRegistry(): ObjectCodecRegistry {
        return codecRegistry
    }

    fun getDependencies(): com.google.common.collect.ImmutableClassToInstanceMap<Any?> {
        return dependencies
    }

    abstract fun getProfileRecorder(): ProfileRecorder?
}
