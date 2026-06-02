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

import com.github.luben.zstd.RecyclingBufferPool

/**
 * A [SerializationContext] that supports both memoization and shared subobjects.
 * 
 * 
 * Sharing subobjects occurs by means of uploading them asynchronously to a [ ][.fingerprintValueService] store. The status of these uploads may be observed through [ ][.createFutureToBlockWritingOn] and [SerializationResult.getFutureToBlockWritesOn].
 */
abstract class SharedValueSerializationContext private constructor(
    codecRegistry: ObjectCodecRegistry?,
    dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>?,
    fingerprintValueService: FingerprintValueService
) : MemoizingSerializationContext(codecRegistry, dependencies) {
    /**
     * Counters for the progress of serialization.
     * 
     * 
     * This is intended to be saved in the trace profile, but cannot itself be a `CounterSeriesCollector`. See `SelectedEntrySerializer.SerializationStatus.collect()` to
     * see why.
     */
    class Counters private constructor() {
        private val bytesWaitingForFuturePuts: AtomicLong = AtomicLong()
        private val bytesWaitingForUpload: AtomicLong = AtomicLong()
        private val bytesUploaded: AtomicLong = AtomicLong()
        private val objectsWaitingForSerialization: AtomicLong = AtomicLong()
        private val objectsWaitingForFuturePuts: AtomicLong = AtomicLong()
        private val objectsWaitingForUpload: AtomicLong = AtomicLong()
        private val objectsUploaded: AtomicLong = AtomicLong()

        fun getBytesWaitingForFuturePuts(): Long {
            return bytesWaitingForFuturePuts.get()
        }

        fun getBytesWaitingForUpload(): Long {
            return bytesWaitingForUpload.get()
        }

        fun getBytesUploaded(): Long {
            return bytesUploaded.get()
        }

        fun getObjectsWaitingForSerialization(): Long {
            return objectsWaitingForSerialization.get()
        }

        fun getObjectsWaitingForFuturePuts(): Long {
            return objectsWaitingForFuturePuts.get()
        }

        fun getObjectsWaitingForUpload(): Long {
            return objectsWaitingForUpload.get()
        }

        fun getObjectsUploaded(): Long {
            return objectsUploaded.get()
        }
    }

    val fingerprintValueService: FingerprintValueService

    /**
     * Futures that represent writes to remote storage.
     * 
     * 
     * For consistency, the serialized bytes should not be published for other consumers until
     * these writes complete.
     */
    // lazily initialized
    private var futuresToBlockWritingOn: java.util.ArrayList<WriteStatus?>? = null

    /**
     * Futures that mark deferred data bytes when using [.putSharedValue].
     * 
     * 
     * The serialized byte representation contains placeholders until these futures complete.
     */
    // lazily initialized
    private var futurePuts: java.util.ArrayList<FuturePut>? = null

    init {
        this.fingerprintValueService = fingerprintValueService
    }

    /**
     * Serializes `subject` to bytes.
     * 
     * 
     * The returned `byte[]` may contain placeholder values, requiring resolution of [ ].
     */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    private fun serializeToBytes(subject: Any?): ByteArray {
        val bytesOut: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val codedOut: CodedOutputStream = CodedOutputStream.newInstance(bytesOut)
        try {
            serialize(subject, codedOut)
            codedOut.flush()
        } catch (e: IOException) {
            throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                "Failed to serialize: " + subject,
                e
            )
        }
        if (getProfileRecorder() != null) {
            getProfileRecorder().checkStackEmpty(subject)
        }
        return bytesOut.toByteArray()
    }

    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    override fun <T> putSharedValue(
        child: T?,
        distinguisher: Any?,
        codec: DeferredObjectCodec<T?>,
        codedOut: CodedOutputStream
    ) {
        val putOperation: com.google.common.util.concurrent.SettableFuture<PutOperation?> =
            com.google.common.util.concurrent.SettableFuture.create<PutOperation?>()
        val previous: Any? =
            fingerprintValueService.getOrClaimPutOperation(child, distinguisher, putOperation)
        if (previous != null) {
            // Uses the previous result and discards `putOperation`.
            if (previous is com.google.common.util.concurrent.ListenableFuture<*>) {
                // HINT: this is the only place where `FuturePut`s originate. The other caller of
                // `recordFuturePut` propagates them transitively.
                val inflight: com.google.common.util.concurrent.ListenableFuture<PutOperation?> =
                    previous as com.google.common.util.concurrent.ListenableFuture<PutOperation?>
                recordFuturePut(inflight, codedOut)
                return
            }
            (previous as PackedFingerprint).writeTo(codedOut)
            return
        }

        try {
            putOwnedSharedValue<T?>(child, codec, codedOut, putOperation)
        } catch (e: com.google.devtools.build.lib.skyframe.serialization.SerializationException) {
            putOperation.setException(e)
            throw e
        } catch (e: IOException) {
            putOperation.setException(e)
            throw e
        } catch (e: java.lang.RuntimeException) {
            // `putOperation` must always be set to avoid deadlock-like behaviors in its consumers.
            putOperation.setException(e)
            throw e
        } catch (e: java.lang.Error) {
            putOperation.setException(e)
            throw e
        }
    }

    /**
     * Implementation of [.putSharedValue] when it owns the `putOperation`.
     * 
     * 
     * This method does the following.
     * 
     * 
     *  * Uploads `child`'s bytes into [.fingerprintValueService].
     *  * Sets the result of the upload in `putOperation`.
     *  * Writes the fingerprint of `child`'s serialized bytes (or a placeholder) to `codedOut`.
     *  * Adds either a [.futuresToBlockWritingOn] entry on [.futurePuts] entry.
     * 
     * 
     * 
     * There are two cases, in particular.
     * 
     * 
     *  * *Serializing `child` causes recursive calls to [.putSharedValue] and some
     * of those recursive calls are still being processed in a different thread*: this
     * results in placeholders being added to the `codedOut` and [.futurePuts]
     * entries to be recorded.
     *  * *Any other case, for example, serializing `child` does not recursively call
     * [.putSharedValue] or if it does, the fingerprints are either already available or
     * owned by this thread*: here, the serialized bytes of `child` are computed in
     * the calling thread and become immediately available. The status of uploading the
     * serialized `child` is added to [.futuresToBlockWritingOn].
     * 
     * 
     * 
     * When entries are added to [.futurePuts], the caller must ensure that for every entry,
     * the associated fingerprint must be written to the output bytes at [FuturePut.offset]. to
     * update placeholders present in the serialized bytes.
     */
    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    private fun <T> putOwnedSharedValue(
        child: T?,
        codec: DeferredObjectCodec<T?>,
        codedOut: CodedOutputStream,
        putOperation: com.google.common.util.concurrent.SettableFuture<PutOperation?>
    ) {
        COUNTERS.objectsWaitingForSerialization.incrementAndGet()

        var childBytes: ByteArray // bytes of serialized child, maybe containing placeholders
        // This is initially populated with the `futuresToWriteBlockingOn` of the child.
        val childWriteStatuses: java.util.ArrayList<WriteStatus?>?
        // Each entry of the following list corresponds to a `putSharedValue` call made by serialization
        // of `child`. Used to fill-in `childBytes` placeholders.
        val childFuturePuts: java.util.ArrayList<FuturePut>?
        val childRecorder: ProfileRecorder?
        run {
            // Serializes `child` with a fresh context, populating the above variables.
            val childStream: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
            val childCodedOut: CodedOutputStream = CodedOutputStream.newInstance(childStream)
            val childContext = getFreshContext()

            childRecorder = childContext.getProfileRecorder()
            if (childRecorder == null) {
                codec.serialize(childContext, child, childCodedOut)
            } else {
                childRecorder.pushLocation(codec)
                codec.serialize(childContext, child, childCodedOut)
                childRecorder.recordBytesAndPopLocation( /* startBytes= */0, childCodedOut)
            }

            childCodedOut.flush()
            childBytes = childStream.toByteArray()
            childWriteStatuses =
                if (childContext.futuresToBlockWritingOn == null)
                    java.util.ArrayList<WriteStatus?>()
                else
                    childContext.futuresToBlockWritingOn
            childFuturePuts = childContext.futurePuts
        }

        if (childFuturePuts == null) {
            // There are no deferred bytes so `childBytes` is complete. Starts the upload.

            val uncompressedLength = childBytes.size
            childBytes = maybeCompressBytes(childBytes)
            val childBytesCount = childBytes.size // Do not hold on to the bytes
            if (childRecorder != null && childBytesCount != uncompressedLength) {
                childRecorder.setByteScale(childBytesCount.toDouble() / uncompressedLength)
            }

            COUNTERS.objectsWaitingForSerialization.decrementAndGet()
            COUNTERS.objectsWaitingForUpload.incrementAndGet()
            COUNTERS.bytesWaitingForUpload.addAndGet(childBytesCount.toLong())

            val fingerprint: PackedFingerprint = fingerprintValueService.fingerprint(childBytes)
            fingerprint.writeTo(codedOut) // Writes only the fingerprint to the stream.
            val writeStatus: WriteStatus = fingerprintValueService.put(fingerprint, childBytes)
            if (childRecorder != null) {
                childRecorder.registerWriteStatus(writeStatus)
            }
            writeStatus.addListener(
                java.lang.Runnable {
                    COUNTERS.objectsWaitingForUpload.decrementAndGet()
                    COUNTERS.objectsUploaded.incrementAndGet()
                    COUNTERS.bytesWaitingForUpload.addAndGet(-childBytesCount.toLong())
                    COUNTERS.bytesUploaded.addAndGet(childBytesCount.toLong())
                },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
            childWriteStatuses.add(writeStatus)

            val aggregateWriteStatus: WriteStatus? = WriteStatuses.sparselyAggregateWriteStatuses(childWriteStatuses)
            putOperation.set(PutOperation(fingerprint, aggregateWriteStatus))
            addFutureToBlockWritingOn(aggregateWriteStatus)
            return
        }

        val upload =
            UploadOnceFuturePutsResolve(
                fingerprintValueService,
                childWriteStatuses,
                childBytes,
                childFuturePuts,
                childRecorder
            )

        putOperation.setFuture(upload)
        recordFuturePut(upload, codedOut)
    }

    private class UploadOnceFuturePutsResolve(
        fingerprintValueService: FingerprintValueService,
        childWriteStatuses: MutableList<WriteStatus?>,
        childBytes: ByteArray,
        childFuturePuts: MutableCollection<FuturePut>,
        childRecorder: ProfileRecorder?
    ) : QuiescingFuture<PutOperation?>(fingerprintValueService.getExecutor()), FuturePutBuffer {
        private val fingerprintValueService: FingerprintValueService
        private val childRecorder: ProfileRecorder?

        private var childBytes: ByteArray?
        private val childWriteStatuses: SparseAggregateWriteStatusBuilder

        init {
            this.fingerprintValueService = fingerprintValueService
            this.childRecorder = childRecorder
            this.childWriteStatuses = SparseAggregateWriteStatusBuilder().addAll(childWriteStatuses)
            this.childBytes = childBytes
            FuturePutBuffer.Companion.register(this, childFuturePuts)

            COUNTERS.objectsWaitingForSerialization.decrementAndGet()
            COUNTERS.objectsWaitingForFuturePuts.incrementAndGet()
            COUNTERS.bytesWaitingForFuturePuts.addAndGet(childBytes.size.toLong())

            decrement() // signal ready
        }

        protected override fun getValue(): PutOperation {
            // All placeholders are filled-in. Starts the upload.
            val uncompressedLength = childBytes!!.size
            val maybeCompressedBytes = Companion.maybeCompressBytes(childBytes!!)
            val childBytesCount = maybeCompressedBytes.size // Do not hold on to the array
            if (childRecorder != null && childBytesCount != uncompressedLength) {
                childRecorder.setByteScale(childBytesCount.toDouble() / uncompressedLength)
            }

            COUNTERS.objectsWaitingForFuturePuts.decrementAndGet()
            COUNTERS.objectsWaitingForUpload.incrementAndGet()
            COUNTERS.bytesWaitingForFuturePuts.addAndGet(-childBytes!!.size.toLong())
            COUNTERS.bytesWaitingForUpload.addAndGet(childBytesCount.toLong())

            val fingerprint: PackedFingerprint = fingerprintValueService.fingerprint(maybeCompressedBytes)
            val writeStatus: WriteStatus = fingerprintValueService.put(fingerprint, maybeCompressedBytes)
            if (childRecorder != null) {
                childRecorder.registerWriteStatus(writeStatus)
            }
            writeStatus.addListener(
                java.lang.Runnable {
                    COUNTERS.objectsWaitingForUpload.decrementAndGet()
                    COUNTERS.objectsUploaded.incrementAndGet()
                    COUNTERS.bytesWaitingForUpload.addAndGet(-childBytesCount.toLong())
                    COUNTERS.bytesUploaded.addAndGet(childBytesCount.toLong())
                },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
            childWriteStatuses.add(writeStatus)
            childBytes = null // Do not hold on to the bytes longer than needed
            return PutOperation(fingerprint, childWriteStatuses.build())
        }

        protected override fun doneWithError() {
            // All FuturePuts are done, but some of them had errors. Reports any write errors that would
            // otherwise be ignored by the caller due to the primary error.
            com.google.common.util.concurrent.Futures.addCallback<Boolean?>(
                childWriteStatuses.build(),
                FutureHelpers.FAILURE_REPORTING_CALLBACK,
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        }

        override fun incrementTaskCount() {
            increment()
        }

        override fun acceptPutOperation(put: PutOperation, offset: Int) {
            put.fingerprint.copyTo(childBytes, offset)
            childWriteStatuses.add(put.writeStatus)
            decrement()
        }

        override fun notifyFailedPutOperation(t: Throwable?) {
            notifyException(t)
        }
    }

    // to narrow the return type
    abstract override fun getFreshContext(): SharedValueSerializationContext

    override fun addFutureToBlockWritingOn(future: WriteStatus?) {
        if (futuresToBlockWritingOn == null) {
            futuresToBlockWritingOn = java.util.ArrayList<WriteStatus?>()
        }
        futuresToBlockWritingOn.add(future)
    }

    override fun createFutureToBlockWritingOn(): WriteStatus? {
        if (futurePuts == null) {
            if (futuresToBlockWritingOn == null) {
                return null
            }
            return WriteStatuses.aggregateWriteStatuses(futuresToBlockWritingOn)
        }

        val aggregate: AggregateWriteStatusBuilder = AggregateWriteStatusBuilder()
        if (futuresToBlockWritingOn != null) {
            aggregate.addAll(futuresToBlockWritingOn)
        }
        for (futurePut in futurePuts) {
            aggregate.add(
                com.google.common.util.concurrent.Futures.transformAsync<PutOperation?, Boolean?>(
                    futurePut.getFuturePut(),
                    PutOperation::writeStatus,
                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                )
            )
        }
        return aggregate.build()
    }

    /**
     * Records a deferred fingerprint value to be written to the serialized bytes.
     * 
     * 
     * Adds the corresponding entry to [.futurePuts] and inserts placeholder bytes into
     * `codedOut`.
     */
    @Throws(IOException::class)
    private fun recordFuturePut(
        futurePut: com.google.common.util.concurrent.ListenableFuture<PutOperation?>?, codedOut: CodedOutputStream
    ) {
        if (futurePuts == null) {
            futurePuts = java.util.ArrayList<FuturePut>()
        }
        futurePuts.add(FuturePut(codedOut.getTotalBytesWritten(), futurePut))
        // Adds a placeholder for the real fingerprint to be filled in after the future completes.
        fingerprintValueService.fingerprintPlaceholder().writeTo(codedOut)
    }

    private class SerializationTask
        (
        context: SharedValueSerializationContext,
        subject: Any?,
        private val allowSparseAggregation: Boolean
    ) : QuiescingFutureTask<SerializationResult<ByteString?>?>(com.google.common.util.concurrent.MoreExecutors.directExecutor()),
        AsyncSerializationTask, FuturePutBuffer {
        private var context: SharedValueSerializationContext?
        private var subject: Any?

        private val topLevelProfileRecorder: ProfileRecorder?

        private var bytes: ByteArray?

        private var childWriteStatuses: WriteStatusBuilder? = null

        init {
            this.context = context
            this.subject = subject
            this.topLevelProfileRecorder = context.getProfileRecorder()
        }

        override fun registerWriteStatus(status: WriteStatus?) {
            if (topLevelProfileRecorder != null) {
                topLevelProfileRecorder.registerWriteStatus(status)
            }
        }

        protected override fun arrangeSubtasks() {
            try {
                try {
                    bytes = context!!.serializeToBytes(subject)
                } catch (e: com.google.devtools.build.lib.skyframe.serialization.SerializationException) {
                    notifyException(e)
                    return
                }
                val futurePuts: java.util.ArrayList<FuturePut>? = context!!.futurePuts
                val futuresToBlockWritingOn: java.util.ArrayList<WriteStatus?>? = context!!.futuresToBlockWritingOn

                if (futurePuts == null && futuresToBlockWritingOn == null) {
                    return
                }

                childWriteStatuses =
                    if (allowSparseAggregation)
                        SparseAggregateWriteStatusBuilder()
                    else
                        AggregateWriteStatusBuilder()
                if (futuresToBlockWritingOn != null) {
                    childWriteStatuses.addAll(futuresToBlockWritingOn)
                }

                if (futurePuts != null) {
                    FuturePutBuffer.Companion.register(this, futurePuts)
                }
            } finally {
                this.context = null
                this.subject = null
            }
        }

        protected override fun getValue(): SerializationResult<ByteString?> {
            try {
                val result: ByteString = ByteString.copyFrom(bytes)
                if (childWriteStatuses == null) {
                    return SerializationResult.Companion.createWithoutFuture<ByteString?>(result)
                }
                return SerializationResult.Companion.create<ByteString?>(result, childWriteStatuses.build())
            } finally {
                bytes = null
                childWriteStatuses = null
            }
        }

        protected override fun doneWithError() {
            if (childWriteStatuses != null) {
                com.google.common.util.concurrent.Futures.addCallback<Boolean?>(
                    childWriteStatuses.build(),
                    FutureHelpers.FAILURE_REPORTING_CALLBACK,
                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                )
            }
        }

        override fun incrementTaskCount() {
            increment()
        }

        override fun acceptPutOperation(put: PutOperation, offset: Int) {
            put.fingerprint.copyTo(bytes, offset)
            childWriteStatuses.add(put.writeStatus)
            decrement()
        }

        override fun notifyFailedPutOperation(t: Throwable?) {
            notifyException(t)
        }
    }

    /**
     * Shared interface for handling [FuturePut]s.
     * 
     * 
     * Both [SerializationTask] and [UploadOnceFuturePutsResolve] implement this
     * interface.
     */
    private interface FuturePutBuffer {
        /** Increments the task count of the owner.  */
        fun incrementTaskCount()

        fun acceptPutOperation(put: PutOperation?, offset: Int)

        fun notifyFailedPutOperation(t: Throwable?)

        companion object {
            /** Helper for registering [FuturePut]s.  */
            fun register(buffer: FuturePutBuffer, futurePuts: MutableCollection<FuturePut>) {
                for (futurePut in futurePuts) {
                    buffer.incrementTaskCount()
                    com.google.common.util.concurrent.Futures.addCallback<PutOperation?>(
                        futurePut.getFuturePut(),
                        PutStartedCallback(buffer, futurePut.offset),
                        com.google.common.util.concurrent.MoreExecutors.directExecutor()
                    )
                }
            }
        }
    }

    private class PutStartedCallback(private val buffer: FuturePutBuffer, private val offset: Int) :
        com.google.common.util.concurrent.FutureCallback<PutOperation?> {
        override fun onSuccess(put: PutOperation?) {
            buffer.acceptPutOperation(put, offset)
        }

        override fun onFailure(t: Throwable) {
            buffer.notifyFailedPutOperation(t)
        }
    }

    /**
     * A tuple consisting of a stream offset and a pending [PutOperation].
     * 
     * 
     * When [PutOperation] becomes available, its [PutOperation.fingerprint] should be
     * copied to the output bytes starting at [.offset].
     */
    private class FuturePut(
        /** Where the fingerprint should be written when it becomes available.  */
        private val offset: Int, futurePut: com.google.common.util.concurrent.ListenableFuture<PutOperation?>?
    ) {
        /**
         * A [PutOperation] that may have not yet started.
         * 
         * 
         * This arises when associated bytes could be mid-computation in a different thread. The
         * future completes when those bytes are known and its asynchronous write to the [ ][.fingerprintValueService] has started. [PutOperation.writeStatus] provides the status
         * of that write.
         */
        private val futurePut: com.google.common.util.concurrent.ListenableFuture<PutOperation?>?

        init {
            this.futurePut = futurePut
        }

        fun getFuturePut(): com.google.common.util.concurrent.ListenableFuture<PutOperation?>? {
            return futurePut
        }
    }

    private class SharedValueSerializationContextImpl
        (
        codecRegistry: ObjectCodecRegistry?,
        dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>?,
        fingerprintValueService: FingerprintValueService
    ) : SharedValueSerializationContext(codecRegistry, dependencies, fingerprintValueService) {
        override fun getFreshContext(): SharedValueSerializationContext {
            return SharedValueSerializationContextImpl(
                getCodecRegistry(), getDependencies(), fingerprintValueService
            )
        }

        public override fun getProfileRecorder(): ProfileRecorder? {
            return null
        }
    }

    private class SharedValueSerializationProfilingContext
        (
        codecRegistry: ObjectCodecRegistry?,
        dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>?,
        fingerprintValueService: FingerprintValueService,
        profileCollector: ProfileCollector?
    ) : SharedValueSerializationContext(codecRegistry, dependencies, fingerprintValueService) {
        private val profileRecorder: ProfileRecorder

        init {
            this.profileRecorder = ProfileRecorder(profileCollector)
        }

        override fun getFreshContext(): SharedValueSerializationContext {
            return SharedValueSerializationProfilingContext(
                getCodecRegistry(),
                getDependencies(),
                fingerprintValueService,
                profileRecorder.getProfileCollector()
            )
        }

        public override fun getProfileRecorder(): ProfileRecorder {
            return profileRecorder
        }
    }

    companion object {
        val COUNTERS: Counters =
            com.google.devtools.build.lib.skyframe.serialization.SharedValueSerializationContext.Counters()

        /** Size of serialized shared value after which we will compress the node.  */
        const val COMPRESSION_THRESHOLD_IN_BYTES: Int = 1024

        @com.google.common.annotations.VisibleForTesting // private
        fun createForTesting(
            codecRegistry: ObjectCodecRegistry?,
            dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>?,
            fingerprintValueService: FingerprintValueService
        ): SharedValueSerializationContext {
            return create(
                codecRegistry, dependencies, fingerprintValueService,  /* profileCollector= */null
            )
        }

        /**
         * Serializes `subject` and returns a result that may have an associated future.
         * 
         * 
         * This method may block when the serialization of a shared child is performed by another
         * thread. It blocks until the fingerprints of such shared children become available. However,
         * this method does not block on uploads to the [FingerprintValueService]. The status of the
         * upload is provided by [SerializationResult.getFutureToBlockWritesOn].
         */
        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
        fun serializeToResult(
            codecRegistry: ObjectCodecRegistry?,
            dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>?,
            fingerprintValueService: FingerprintValueService,
            subject: Any?
        ): SerializationResult<ByteString?>? {
            val task =
                SerializationTask(
                    create(
                        codecRegistry, dependencies, fingerprintValueService,  /* profileCollector= */null
                    ),
                    subject,  /* allowSparseAggregation= */
                    false
                )
            task.run()
            return FutureHelpers.waitForSerializationFuture<SerializationResult<ByteString?>?>(task)
        }

        fun serializeToResultAsync(
            codecRegistry: ObjectCodecRegistry?,
            dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>?,
            fingerprintValueService: FingerprintValueService,
            subject: Any?,
            profileCollector: ProfileCollector?
        ): AsyncSerializationTask {
            return SerializationTask(
                create(codecRegistry, dependencies, fingerprintValueService, profileCollector),
                subject,  /* allowSparseAggregation= */
                true
            )
        }

        private fun maybeCompressBytes(childBytes: ByteArray): ByteArray {
            if (childBytes.size > COMPRESSION_THRESHOLD_IN_BYTES) {
                val outputStream: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
                outputStream.write(1.toByte().toInt())
                try {
                    ZstdOutputStream(outputStream, RecyclingBufferPool.INSTANCE).use { zstdOutputStream ->
                        zstdOutputStream.write(childBytes)
                        zstdOutputStream.flush()
                        return outputStream.toByteArray()
                    }
                } catch (e: IOException) {
                    BugReporter.defaultInstance().sendBugReport(e)
                    // Falls back onto uncompressed data.
                }
            }
            val newChildBytes = ByteArray(childBytes.size + 1)
            newChildBytes[0] = 0.toByte()
            java.lang.System.arraycopy(childBytes, 0, newChildBytes, 1, childBytes.size)
            return newChildBytes
        }

        private fun create(
            codecRegistry: ObjectCodecRegistry?,
            dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>?,
            fingerprintValueService: FingerprintValueService,
            profileCollector: ProfileCollector?
        ): SharedValueSerializationContext {
            return if (profileCollector == null)
                SharedValueSerializationContextImpl(
                    codecRegistry, dependencies, fingerprintValueService
                )
            else
                SharedValueSerializationProfilingContext(
                    codecRegistry, dependencies, fingerprintValueService, profileCollector
                )
        }
    }
}
