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
package com.google.devtools.build.lib.collect.nestedset

import com.google.common.flogger.GoogleLogger
import com.google.devtools.build.lib.bugreport.BugReporter
import com.google.devtools.build.lib.collect.nestedset.NestedSetSerializationCache
import com.google.devtools.build.lib.skyframe.serialization.DeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore
import com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint
import com.google.devtools.build.lib.skyframe.serialization.PutOperation
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import com.google.devtools.build.lib.skyframe.serialization.SerializationDependencyProvider
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.SettableWriteStatus
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.WriteStatus
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException

/**
 * Supports association between fingerprints and NestedSet contents. A single NestedSetStore
 * instance should be globally available across a single process.
 * 
 * 
 * Maintains the fingerprint → contents side of the bimap by decomposing nested Object[]'s.
 * 
 * 
 * For example, suppose the NestedSet A can be drawn as:
 * 
 * <pre>
 * A
 * /  \
 * B   C
 * / \
 * D  E
</pre> * 
 * 
 * 
 * Then, in memory, A = [[D, E], C]. To store the NestedSet, we would rely on the fingerprint
 * value FPb = fingerprint([D, E]) and write
 * 
 * <pre>`A -> fingerprint(FPb, C)`</pre>
 * 
 * 
 * On retrieval, A will be reconstructed by first retrieving A using its fingerprint, and then
 * recursively retrieving B using its fingerprint.
 */
class NestedSetStore @com.google.common.annotations.VisibleForTesting internal constructor(
    fingerprintValueStore: FingerprintValueStore?,
    executor: java.util.concurrent.Executor?,
    nestedSetCache: NestedSetSerializationCache?,
    cacheContextFn: java.util.function.Function<SerializationDependencyProvider?, *>?
) {
    private val fingerprintValueStore: FingerprintValueStore
    private val executor: java.util.concurrent.Executor
    private val nestedSetCache: NestedSetSerializationCache
    private val cacheContextFn: java.util.function.Function<SerializationDependencyProvider?, *>

    /**
     * Creates a NestedSetStore with the provided [FingerprintValueStore] and executor for
     * deserialization.
     * 
     * 
     * Takes a function that produces a caching context object from a [ ]. The context should work as described in [ ] to disambiguate different contents that have the same serialized
     * representation. If a one-to-one correspondence between contents and serialized representation
     * is guaranteed, use [.NO_CONTEXT], which uses a constant object for the cache context.
     */
    constructor(
        fingerprintValueStore: FingerprintValueStore?,
        executor: java.util.concurrent.Executor?,
        bugReporter: BugReporter?,
        cacheContextFn: java.util.function.Function<SerializationDependencyProvider?, *>?
    ) : this(
        fingerprintValueStore,
        executor,
        NestedSetSerializationCache(bugReporter),
        cacheContextFn
    )

    init {
        this.fingerprintValueStore =
            com.google.common.base.Preconditions.checkNotNull<FingerprintValueStore>(fingerprintValueStore)
        this.executor = com.google.common.base.Preconditions.checkNotNull<java.util.concurrent.Executor>(executor)
        this.nestedSetCache =
            com.google.common.base.Preconditions.checkNotNull<NestedSetSerializationCache>(nestedSetCache)
        this.cacheContextFn = com.google.common.base.Preconditions.checkNotNull(cacheContextFn)
    }

    /**
     * Computes and returns the fingerprint for the given [NestedSet] contents using the given
     * [SerializationContext], while also associating the contents with the computed fingerprint
     * in the store. Recursively does the same for all transitive `Object[]` members of the
     * provided contents.
     * 
     * 
     * We wish to compute a fingerprint for each array only once. However, this is not currently
     * enforced, due to the check-then-act race below, where we check [ ][NestedSetSerializationCache.fingerprintForContents] and then, significantly later, call [ ][NestedSetSerializationCache.putIfAbsent]. It is not straightforward to solve this with a
     * typical cache loader because the fingerprint computation is recursive, and cache loaders must
     * not attempt to update the cache while loading a result. Even if we duplicate fingerprint
     * computation, only one thread will end up calling [FingerprintValueStore.put] (the one
     * that wins the race to [NestedSetSerializationCache.putIfAbsent]).
     */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    fun computeFingerprintAndStore(
        contents: Array<Any?>, serializationContext: SerializationContext
    ): PutOperation {
        return computeFingerprintAndStore(
            contents, serializationContext, cacheContextFn.apply(serializationContext)
        )
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    private fun computeFingerprintAndStore(
        contents: Array<Any?>, serializationContext: SerializationContext, cacheContext: Any?
    ): PutOperation {
        val priorFingerprint: PutOperation? = nestedSetCache.fingerprintForContents(contents)
        if (priorFingerprint != null) {
            return priorFingerprint
        }

        // For every fingerprint computation, we need to use a new memoization table.  This is required
        // to guarantee that the same child will always have the same fingerprint - otherwise,
        // differences in memoization context could cause part of a child to be memoized in one
        // fingerprinting but not in the other.  We expect this clearing of memoization state to be a
        // major source of extra work over the naive serialization approach.  The same value may have to
        // be serialized many times across separate fingerprintings.
        val newSerializationContext: SerializationContext = serializationContext.getFreshContext()
        val byteArrayOutputStream: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val codedOutputStream: CodedOutputStream = CodedOutputStream.newInstance(byteArrayOutputStream)

        val futureBuilder: com.google.common.collect.ImmutableList.Builder<WriteStatus?> =
            com.google.common.collect.ImmutableList.builder<WriteStatus?>()
        try {
            codedOutputStream.writeInt32NoTag(contents.length)
            for (child in contents) {
                if (child is Array<Any>) {
                    val fingerprintComputationResult: PutOperation =
                        computeFingerprintAndStore(child as Array<Any?>, serializationContext, cacheContext)
                    futureBuilder.add(fingerprintComputationResult.writeStatus)
                    newSerializationContext.serialize(
                        fingerprintComputationResult.fingerprint, codedOutputStream
                    )
                } else {
                    newSerializationContext.serialize(child, codedOutputStream)
                }
            }
            codedOutputStream.flush()
        } catch (e: IOException) {
            throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                "Could not serialize NestedSet contents",
                e
            )
        }

        val serializedBytes: ByteArray = byteArrayOutputStream.toByteArray()
        // TODO: b/368012715 - reconsider use of md5.
        val fingerprint: PackedFingerprint =
            PackedFingerprint.fromBytes(com.google.common.hash.Hashing.md5().hashBytes(serializedBytes).asBytes())
        val localWriteFuture: SettableWriteStatus = SettableWriteStatus()
        futureBuilder.add(localWriteFuture)

        // If this is a NestedSet<NestedSet>, serialization of the contents will itself have writes.
        val innerWriteFutures: WriteStatus? = newSerializationContext.createFutureToBlockWritingOn()
        if (innerWriteFutures != null) {
            futureBuilder.add(innerWriteFutures)
        }

        val result: PutOperation =
            PutOperation(fingerprint, WriteStatuses.aggregateWriteStatuses(futureBuilder.build()))

        val existingResult: PutOperation? = nestedSetCache.putIfAbsent(contents, result, cacheContext)
        if (existingResult != null) {
            return existingResult // Another thread won the fingerprint computation race.
        }

        // This fingerprint was not cached previously, so we must ensure that it is written to storage.
        localWriteFuture.completeWith(fingerprintValueStore.put(fingerprint, serializedBytes))
        return result
    }

    /**
     * Retrieves and deserializes the NestedSet contents associated with the given fingerprint.
     * 
     * 
     * We wish to only do one deserialization per fingerprint. This is enforced by the [ ][.nestedSetCache], which is responsible for returning the actual contents or the canonical
     * future that will contain the results of the deserialization. If that future is not owned by the
     * current call of this method, it doesn't have to do anything further.
     * 
     * 
     * The return value is either an `Object[]` or a `ListenableFuture<Object[]>`,
     * which may be completed with a [MissingFingerprintValueException].
     */
    @Throws(IOException::class)
    fun getContentsAndDeserialize(
        fingerprint: PackedFingerprint?, deserializationContext: DeserializationContext
    ): Any {
        return getContentsAndDeserialize(
            fingerprint, deserializationContext, cacheContextFn.apply(deserializationContext)
        )
    }

    // All callers will test on type and check return value if it's a future.
    @Throws(IOException::class)
    private fun getContentsAndDeserialize(
        fingerprint: PackedFingerprint?,
        deserializationContext: DeserializationContext,
        cacheContext: Any?
    ): Any {
        val future: com.google.common.util.concurrent.SettableFuture<Array<Any?>?> =
            com.google.common.util.concurrent.SettableFuture.create<Array<Any?>?>()
        val contents: Any? = nestedSetCache.putFutureIfAbsent(fingerprint, future, cacheContext)
        if (contents != null) {
            return contents
        }
        val retrieved: com.google.common.util.concurrent.ListenableFuture<ByteArray?> =
            fingerprintValueStore.get(fingerprint)
        val fetchStopwatch: com.google.common.base.Stopwatch = com.google.common.base.Stopwatch.createStarted()
        future.setFuture(
            com.google.common.util.concurrent.Futures.transformAsync<ByteArray?, Array<Any?>?>(
                retrieved,
                com.google.common.util.concurrent.AsyncFunction { bytes: ByteArray? ->
                    com.google.common.base.Preconditions.checkNotNull<ByteArray?>(bytes)
                    val fetchDuration: java.time.Duration = fetchStopwatch.elapsed()
                    if (FETCH_FROM_STORAGE_LOGGING_THRESHOLD.compareTo(fetchDuration) < 0) {
                        logger.atInfo().log(
                            "NestedSet fetch took: %dms, size: %dB",
                            fetchDuration.toMillis(), bytes.length
                        )
                    }

                    val codedIn: CodedInputStream = CodedInputStream.newInstance(bytes)
                    val numberOfElements: Int = codedIn.readInt32()
                    val newDeserializationContext: DeserializationContext =
                        deserializationContext.getFreshContext()

                    // The elements of this list are futures for the deserialized values of these
                    // NestedSet contents. For direct members, the futures complete immediately and yield
                    // an Object. For transitive members (fingerprints), the futures complete with the
                    // underlying fetch, and yield Object[]s.
                    val deserializationFutures: com.google.common.collect.ImmutableList.Builder<com.google.common.util.concurrent.ListenableFuture<*>?> =
                        com.google.common.collect.ImmutableList.builderWithExpectedSize<com.google.common.util.concurrent.ListenableFuture<*>?>(
                            numberOfElements
                        )
                    for (i in 0..<numberOfElements) {
                        val deserializedElement: Any? = newDeserializationContext.deserialize<Any?>(codedIn)
                        if (deserializedElement is PackedFingerprint) {
                            val innerContents =
                                getContentsAndDeserialize(
                                    deserializedElement, deserializationContext, cacheContext
                                )
                            deserializationFutures.add(maybeWrapInFuture(innerContents))
                        } else {
                            deserializationFutures.add(
                                com.google.common.util.concurrent.Futures.immediateFuture<Any?>(
                                    deserializedElement
                                )
                            )
                        }
                    }
                    com.google.common.util.concurrent.Futures.transform<MutableList<Any?>?, Array<Any?>?>(
                        com.google.common.util.concurrent.Futures.allAsList<Any?>(deserializationFutures.build()),
                        com.google.common.base.Function { obj: MutableList<Any?>? -> obj.toArray() },
                        executor
                    )
                },
                executor
            )
        )
        return future
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
        private val FETCH_FROM_STORAGE_LOGGING_THRESHOLD: java.time.Duration = java.time.Duration.ofSeconds(5)

        @kotlin.jvm.JvmField
        val NO_CONTEXT: java.util.function.Function<SerializationDependencyProvider?, *> =
            java.util.function.Function { ctx: SerializationDependencyProvider? -> "" }

        /** Creates a NestedSetStore with an in-memory storage backend and no caching context.  */
        @kotlin.jvm.JvmStatic
        fun inMemory(): NestedSetStore {
            return NestedSetStore(
                FingerprintValueStore.inMemoryStore(),
                com.google.common.util.concurrent.MoreExecutors.directExecutor(),
                BugReporter.Companion.defaultInstance(),
                NO_CONTEXT
            )
        }

        private fun maybeWrapInFuture(contents: Any?): com.google.common.util.concurrent.ListenableFuture<Array<Any?>?>? {
            if (contents is Array<Any>) {
                return com.google.common.util.concurrent.Futures.immediateFuture<Array<Any?>?>(contents as Array<Any?>)
            }
            return contents as com.google.common.util.concurrent.ListenableFuture<Array<Any?>?>?
        }
    }
}
