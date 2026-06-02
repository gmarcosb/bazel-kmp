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
package com.google.devtools.build.lib.collect.nestedset

import com.github.benmanes.caffeine.cache.Caffeine
import com.google.devtools.build.lib.collect.nestedset.NestedSet
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder
import com.google.devtools.build.lib.collect.nestedset.NestedSetStore
import com.google.devtools.build.lib.skyframe.serialization.DeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint
import com.google.devtools.build.lib.skyframe.serialization.PutOperation
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import com.google.devtools.build.zip.ZipReader.entries
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException
import java.util.concurrent.ExecutionException

/** Codec for [NestedSet] that uses the [NestedSetStore].  */
class NestedSetCodecWithStore(nestedSetStore: NestedSetStore) : ObjectCodec<NestedSet<*>?> {
    private enum class NestedSetSize {
        EMPTY,  // distinguished empty node; size = 0, depth = 0
        LEAF,  // a single element; size = 1, depth = 1
        NONLEAF // more than one element; size > 1, depth > 1
    }

    private val nestedSetStore: NestedSetStore

    /**
     * Used to preserve the invariant that if NestedSets inside two different objects are
     * reference-equal, they will continue to be reference-equal after deserialization.
     * 
     * 
     * Suppose NestedSet N is contained in objects A and B. If A is deserialized and then B is
     * deserialized, then when we create N inside B, we will use the version already created inside A.
     * This depends on the fact that NestedSets with the same underlying Object[] children and order
     * are equal, and that we have a cache of children Object[] that will contain N's children field
     * as long as it is in memory.
     * 
     * 
     * If A and B are created, then B is serialized and deserialized while A remains in memory, the
     * first serialization will put N into this interner, and so the deserialization will reuse it.
     */
    private val interner: com.github.benmanes.caffeine.cache.Cache<EqualsWrapper?, NestedSet<*>?> =
        Caffeine.newBuilder().weakValues().build<EqualsWrapper?, NestedSet<*>?>()

    /** Creates a NestedSetCodecWithStore that will use the given [NestedSetStore].  */
    init {
        this.nestedSetStore = nestedSetStore
    }

    val encodedClass: java.lang.Class<NestedSet<*>?>
        get() =// Compiler doesn't like cast from Class<NestedSet> -> Class<NestedSet<T>>, but it
            // does allow what we see below. Type is lost at runtime anyway, so while gross this works.
            (NestedSet::class.java as java.lang.Class<*>) as java.lang.Class<NestedSet<*>?>

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun serialize(context: SerializationContext, obj: NestedSet<*>, codedOut: CodedOutputStream) {
        context.serialize(obj.getOrder(), codedOut)
        if (obj.isEmpty()) {
            // If the NestedSet is empty, it needs to be assigned to the EMPTY_CHILDREN constant on
            // deserialization.
            codedOut.writeEnumNoTag(NestedSetSize.EMPTY.ordinal())
        } else if (obj.isSingleton()) {
            // If the NestedSet is a singleton, we serialize directly as an optimization.
            codedOut.writeEnumNoTag(NestedSetSize.LEAF.ordinal())
            context.serialize(obj.getChildren(), codedOut)
        } else {
            codedOut.writeEnumNoTag(NestedSetSize.NONLEAF.ordinal())
            codedOut.writeInt32NoTag(obj.getApproxDepth())
            val fingerprintComputationResult: PutOperation =
                nestedSetStore.computeFingerprintAndStore(obj.getChildren() as Array<Any?>?, context)
            context.addFutureToBlockWritingOn(fingerprintComputationResult.writeStatus)
            fingerprintComputationResult.fingerprint.writeTo(codedOut)
        }
        interner.put(EqualsWrapper(obj), obj)
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserialize(context: DeserializationContext, codedIn: CodedInputStream): NestedSet<*>? {
        val order: com.google.devtools.build.lib.collect.nestedset.Order? =
            context.deserialize<com.google.devtools.build.lib.collect.nestedset.Order?>(codedIn)
        val nestedSetSize: NestedSetSize = NestedSetSize.entries[codedIn.readEnum()]
        when (nestedSetSize) {
            NestedSetSize.EMPTY -> {
                return NestedSetBuilder.Companion.emptySet<Any?>(order)
            }

            NestedSetSize.LEAF -> {
                val contents: Any? = context.deserialize<Any?>(codedIn)
                return intern(order,  /* depth= */1, contents)
            }

            NestedSetSize.NONLEAF -> {
                val depth: Int = codedIn.readInt32()
                val fingerprint: PackedFingerprint = PackedFingerprint.readFrom(codedIn)
                return intern(order, depth, nestedSetStore.getContentsAndDeserialize(fingerprint, context))
            }
        }
        throw java.lang.IllegalStateException("NestedSet size " + nestedSetSize + " not known")
    }

    /**
     * Morally, NestedSets are compared using reference equality, to avoid the cost of unrolling them.
     * However, when deserializing NestedSet, we don't want to end up with two sets that "should" be
     * reference-equal, but are not. Since our codec implementation caches the underlying [ ][NestedSet.getChildren] Object[], two nested sets that should be the same will have equal
     * underlying [NestedSet.getChildren], so we can use that for an equality check.
     * 
     * 
     * We also would like to prevent the existence of two equal NestedSets in a single JVM, in
     * which one NestedSet contains an Object[] and the other contains a ListenableFuture<Object></Object>[]>
     * for the same contents. This can happen if a NestedSet is serialized, and then deserialized with
     * a call to storage for those contents. In order to guarantee that only one NestedSet exists for
     * a given Object[], the interner checks the doneness of any ListenableFuture, and if done, holds
     * on only to the "materialized" NestedSet, with the Object[] as its children object.
     * 
     * 
     * Note that singleton NestedSets' underlying children are not cached, but we must still
     * enforce equality for them. To do that, we use the #hashCode and #equals of the [ ][NestedSet.getChildren]. When that field is an Object[], this is just identity hash code and
     * reference equality, but when it is something else (like an Artifact), we will do an actual
     * equality comparison. This may make some singleton NestedSets reference-equal where they were
     * not before. This should be ok as long as the contained object properly implements equality.
     */
    private fun intern(
        order: com.google.devtools.build.lib.collect.nestedset.Order?,
        depth: Int,
        contents: Any?
    ): NestedSet<*>? {
        val result: NestedSet<*>?
        if (contents is com.google.common.util.concurrent.ListenableFuture<*>) {
            result = NestedSet.Companion.withFuture<Any?>(
                order,
                depth,
                contents as com.google.common.util.concurrent.ListenableFuture<Array<Any?>?>
            )
        } else {
            result = NestedSet.Companion.forDeserialization<Any?>(order, depth, contents)
        }
        return interner.get(EqualsWrapper(result), java.util.function.Function { unused: EqualsWrapper? -> result })
    }

    private class EqualsWrapper(nestedSet: NestedSet<*>) {
        private val order: com.google.devtools.build.lib.collect.nestedset.Order
        private val children: Any

        init {
            // Unwrap the fields we need so that we don't strongly retain the NestedSet.
            this.order = nestedSet.getOrder()
            this.children = nestedSet.children
        }

        override fun hashCode(): Int {
            var childrenHashCode: Int
            if (children is com.google.common.util.concurrent.ListenableFuture<*> && (children as com.google.common.util.concurrent.ListenableFuture<*>).isDone()) {
                try {
                    childrenHashCode =
                        com.google.common.util.concurrent.Futures.getDone(children as com.google.common.util.concurrent.ListenableFuture<*>)
                            .hashCode()
                } catch (e: ExecutionException) {
                    // If the future failed, we can treat it as unequal to all non-future NestedSet instances
                    // (using the hashCode of the Future object) and hide the exception until the NestedSet is
                    // truly needed (i.e. unrolled). Note that NestedSetStore already attaches a listener to
                    // this future that sends a bug report if it fails.
                    childrenHashCode = children.hashCode()
                }
            } else {
                childrenHashCode = children.hashCode()
            }

            return 37 * order.hashCode() + childrenHashCode
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj !is EqualsWrapper) {
                return false
            }

            // Both sets contain Object[] or both sets contain ListenableFuture<Object[]>
            if (this.order == obj.order && this.children == obj.children) {
                return true
            }

            // One set contains Object[], while the other contains ListenableFuture<Object[]>
            if (this.children is com.google.common.util.concurrent.ListenableFuture<*> && obj.children is Array<Any>) {
                return deserializingAndMaterializedSetsAreEqual(
                    obj.children as Array<Any?>,
                    this.children as com.google.common.util.concurrent.ListenableFuture<Array<Any?>?>
                )
            } else if (obj.children is com.google.common.util.concurrent.ListenableFuture<*> && this.children is Array<Any>) {
                return deserializingAndMaterializedSetsAreEqual(
                    this.children as Array<Any?>,
                    obj.children as com.google.common.util.concurrent.ListenableFuture<Array<Any?>?>
                )
            } else {
                return false
            }
        }

        companion object {
            private fun deserializingAndMaterializedSetsAreEqual(
                contents: Array<Any?>?, contentsFuture: com.google.common.util.concurrent.ListenableFuture<Array<Any?>?>
            ): Boolean {
                if (!contentsFuture.isDone()) {
                    return false
                }

                try {
                    return com.google.common.util.concurrent.Futures.getDone<Array<Any?>?>(contentsFuture) == contents
                } catch (e: ExecutionException) {
                    return false // Treat a failure to fetch as unequal to a non-future NestedSet.
                }
            }
        }
    }
}
