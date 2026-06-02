// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization.analysis

import com.google.devtools.build.lib.skyframe.serialization.proto.DataType.DATA_TYPE_ANALYSIS_NODE

/**
 * Supports uploading a selected set of [SkyKey]s to a [FingerprintValueService].
 * 
 * 
 * Execution is encapsulated via the [.uploadSelection] method.
 * 
 * 
 * Each persisted entry consists of the following:
 * 
 * 
 *  * **Key**: formatted as `fingerprint(<version stamp>, <serialized SkyKey>)`.
 *  * **Invalidation Data and Value**: formatted as `<invalidation data>, <serialized       SkyValue>`. The `invalidation data` consists of a [DataType] number followed by
 * a corresponding key, if it is not [DATA_TYPE_EMPTY].
 * 
 * 
 * 
 * Note that both `<invalidation data>` and `<serialized SkyValue>` are intended to
 * have relatively small immediate representations. When there is a large amount of data, it will be
 * expressed via references (e.g., keys to a other [FingerprintValueService] entries).
 */
internal class SelectedEntrySerializer private constructor(
    graph: InMemoryGraph,
    codecs: ObjectCodecs,
    frontierVersion: FrontierNodeVersion,
    fingerprintValueService: FingerprintValueService,
    fileOpNodes: FileOpNodeMemoizingLookup,
    fileDependencySerializer: FileDependencySerializer,
    writeStatuses: SerializationStatus,
    eventBus: com.google.common.eventbus.EventBus,
    profileCollector: ProfileCollector?,
    serializationStats: SerializationStats
) : java.util.function.Consumer<SkyKey?> {
    /**
     * Counters for the progress of serialization.
     * 
     * 
     * Logged in the trace profile.
     */
    private class Counters : CounterSeriesCollector {
        private val entriesWaitingForKeyBytes: AtomicLong = AtomicLong()
        private val entriesWaitingForValueBytes: AtomicLong = AtomicLong()
        private val entriesWaitingForInvalidationInfo: AtomicLong = AtomicLong()
        private val entriesWaitingForInvalidationBytes: AtomicLong = AtomicLong()
        private val entriesWaitingForUpload: AtomicLong = AtomicLong()
        private val entriesUploaded: AtomicLong = AtomicLong()
        private val keyBytesWaitingForUpload: AtomicLong = AtomicLong()
        private val valueBytesWaitingForUpload: AtomicLong = AtomicLong()
        private val keyBytesUploaded: AtomicLong = AtomicLong()
        private val valueBytesUploaded: AtomicLong = AtomicLong()

        public override fun collect(
            deltaNanos: Double,
            consumer: java.util.function.BiConsumer<CounterSeriesTask?, Double?>
        ) {
            consumer.accept(
                com.google.devtools.build.lib.skyframe.serialization.analysis.SelectedEntrySerializer.Counters.Companion.ENTRIES_WAITING_FOR_KEY_BYTES,
                entriesWaitingForKeyBytes.get().toDouble()
            )
            consumer.accept(
                com.google.devtools.build.lib.skyframe.serialization.analysis.SelectedEntrySerializer.Counters.Companion.ENTRIES_WAITING_FOR_VALUE_BYTES,
                entriesWaitingForValueBytes.get().toDouble()
            )
            consumer.accept(
                com.google.devtools.build.lib.skyframe.serialization.analysis.SelectedEntrySerializer.Counters.Companion.ENTRIES_WAITING_FOR_INVALIDATION_INFO,
                entriesWaitingForInvalidationInfo.get().toDouble()
            )
            consumer.accept(
                com.google.devtools.build.lib.skyframe.serialization.analysis.SelectedEntrySerializer.Counters.Companion.ENTRIES_WAITING_FOR_INVALIDATION_BYTES,
                entriesWaitingForInvalidationBytes.get().toDouble()
            )
            consumer.accept(
                com.google.devtools.build.lib.skyframe.serialization.analysis.SelectedEntrySerializer.Counters.Companion.ENTRIES_WAITING_FOR_UPLOAD,
                entriesWaitingForUpload.get().toDouble()
            )
            consumer.accept(
                com.google.devtools.build.lib.skyframe.serialization.analysis.SelectedEntrySerializer.Counters.Companion.ENTRIES_UPLOADED,
                entriesUploaded.get().toDouble()
            )
            consumer.accept(
                com.google.devtools.build.lib.skyframe.serialization.analysis.SelectedEntrySerializer.Counters.Companion.KEY_BYTES_WAITING_FOR_UPLOAD,
                keyBytesWaitingForUpload.get().toDouble()
            )
            consumer.accept(
                com.google.devtools.build.lib.skyframe.serialization.analysis.SelectedEntrySerializer.Counters.Companion.KEY_BYTES_UPLOADED,
                keyBytesUploaded.get().toDouble()
            )
            consumer.accept(
                com.google.devtools.build.lib.skyframe.serialization.analysis.SelectedEntrySerializer.Counters.Companion.VALUE_BYTES_WAITING_FOR_UPLOAD,
                valueBytesWaitingForUpload.get().toDouble()
            )
            consumer.accept(
                com.google.devtools.build.lib.skyframe.serialization.analysis.SelectedEntrySerializer.Counters.Companion.VALUE_BYTES_UPLOADED,
                valueBytesUploaded.get().toDouble()
            )
        }

        companion object {
            private val ENTRIES_WAITING_FOR_KEY_BYTES: CounterSeriesTask = CounterSeriesTask(
                "Skycache: SkyValues: Waiting for key bytes", "SkyValues", Color.RAIL_LOAD
            )

            private val ENTRIES_WAITING_FOR_VALUE_BYTES: CounterSeriesTask = CounterSeriesTask(
                "Skycache: SkyValues: Waiting for value bytes", "SkyValues", Color.RAIL_LOAD
            )

            private val ENTRIES_WAITING_FOR_INVALIDATION_INFO: CounterSeriesTask = CounterSeriesTask(
                "Skycache: SkyValues: Waiting for invalidation info", "SkyValues", Color.RAIL_LOAD
            )

            private val ENTRIES_WAITING_FOR_INVALIDATION_BYTES: CounterSeriesTask = CounterSeriesTask(
                "Skycache: SkyValues: Waiting for invalidation bytes", "SkyValues", Color.RAIL_LOAD
            )

            private val ENTRIES_WAITING_FOR_UPLOAD: CounterSeriesTask = CounterSeriesTask(
                "Skycache: SkyValues: Waiting for upload", "SkyValues", Color.RAIL_LOAD
            )
            private val ENTRIES_UPLOADED: CounterSeriesTask =
                CounterSeriesTask("Skycache: SkyValues: Uploaded", "SkyValues", Color.RAIL_RESPONSE)

            private val KEY_BYTES_WAITING_FOR_UPLOAD: CounterSeriesTask =
                CounterSeriesTask("Skycache: SkyValue bytes: Pending", "Key", Color.RAIL_LOAD)

            private val VALUE_BYTES_WAITING_FOR_UPLOAD: CounterSeriesTask =
                CounterSeriesTask("Skycache: SkyValue bytes: Pending", "Value", Color.RAIL_LOAD)

            private val KEY_BYTES_UPLOADED: CounterSeriesTask =
                CounterSeriesTask("Skycache: SkyValue bytes: Uploaded", "Key", Color.RAIL_RESPONSE)

            private val VALUE_BYTES_UPLOADED: CounterSeriesTask =
                CounterSeriesTask("Skycache: SkyValue bytes: Uploaded", "Value", Color.RAIL_RESPONSE)
        }
    }

    internal class SerializationStats {
        private val analysisNodes: AtomicLong = AtomicLong(0)
        private val executionNodes: AtomicLong = AtomicLong(0)

        fun registerAnalysisNode() {
            analysisNodes.incrementAndGet()
        }

        fun registerExecutionNode() {
            executionNodes.incrementAndGet()
        }

        fun analysisNodes(): Long {
            return analysisNodes.get()
        }

        fun executionNodes(): Long {
            return executionNodes.get()
        }
    }

    private val graph: InMemoryGraph
    private val codecs: ObjectCodecs
    private val frontierVersion: FrontierNodeVersion

    private val fingerprintValueService: FingerprintValueService

    private val fileOpNodes: FileOpNodeMemoizingLookup
    private val fileDependencySerializer: FileDependencySerializer

    private val writeStatuses: SerializationStatus

    private val eventBus: com.google.common.eventbus.EventBus
    private val profileCollector: ProfileCollector?
    private val serializationStats: SerializationStats

    init {
        this.graph = graph
        this.codecs = codecs
        this.frontierVersion = frontierVersion
        this.fingerprintValueService = fingerprintValueService
        this.fileOpNodes = fileOpNodes
        this.fileDependencySerializer = fileDependencySerializer
        this.writeStatuses = writeStatuses
        this.eventBus = eventBus
        this.profileCollector = profileCollector
        this.serializationStats = serializationStats
    }

    override fun accept(key: SkyKey) {
        // TODO: b/371508153 - only upload nodes that were freshly computed by this invocation and
        // unaffected by local, un-submitted changes.
        try {
            when (key) {
                -> {
                    serializationStats.registerAnalysisNode()
                    uploadEntry(actionLookupKey, actionLookupKey)
                }

                -> {
                    serializationStats.registerExecutionNode()
                    uploadEntry(lookupData, checkNotNull(lookupData.getActionLookupKey(), lookupData))
                }

                -> {
                    // This case handles the subclasses of DerivedArtifact. DerivedArtifact itself will show
                    // up here as ActionLookupData.
                    serializationStats.registerExecutionNode()
                    uploadEntry(artifact, checkNotNull(artifact.getArtifactOwner(), artifact))
                }

                else -> throw java.lang.AssertionError("Unexpected selected type: " + key.getCanonicalName())
            }
            eventBus.post(SerializedNodeEvent(key))
        } catch (e: MissingSkyframeEntryException) {
            writeStatuses.notifyWriteFailure(e)
        }
    }

    /**
     * Uploads the entry associated with `key` persisting alongside it, the file dependencies
     * associated with `dependencyKey`.
     */
    @Throws(MissingSkyframeEntryException::class)
    private fun uploadEntry(key: SkyKey, dependencyKey: ActionLookupKey?) {
        if (writeStatuses.hasError()) {
            writeStatuses.selectedEntryDone()
            return
        }

        val nodeEntry: InMemoryNodeEntry? = graph.getIfPresent(key)
        if (nodeEntry == null) {
            // TODO: b/400460727 - add some coverage for this code path
            throw MissingSkyframeEntryException(key)
        }

        writeStatuses.counters.entriesWaitingForKeyBytes.incrementAndGet()
        writeStatuses.counters.entriesWaitingForValueBytes.incrementAndGet()
        writeStatuses.counters.entriesWaitingForInvalidationInfo.incrementAndGet()

        // Keys are always stored as fingerprints so their detailed profiles are omitted.
        val keyResultTask: AsyncSerializationTask =
            codecs.serializeMemoizedAsync(fingerprintValueService, key,  /* profileCollector= */null)
        fingerprintValueService.getExecutor().execute(keyResultTask)
        val valueResultTask: AsyncSerializationTask =
            codecs.serializeMemoizedAsync(
                fingerprintValueService, nodeEntry.getValue(), profileCollector
            )
        fingerprintValueService.getExecutor().execute(valueResultTask)

        keyResultTask.addListener(
            java.lang.Runnable { writeStatuses.counters.entriesWaitingForKeyBytes.decrementAndGet() },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
        valueResultTask.addListener(
            java.lang.Runnable { writeStatuses.counters.entriesWaitingForValueBytes.decrementAndGet() },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )

        FileOpNodeProcessor(keyResultTask, valueResultTask, isExecutionValue(key), dependencyKey)
            .run()
    }

    private inner class FileOpNodeProcessor(
        keyResultTask: AsyncSerializationTask,
        valueResultTask: AsyncSerializationTask,
        isExecutionValue: Boolean,
        dependencyKey: ActionLookupKey?
    ) : com.google.common.util.concurrent.FutureCallback<FileOpNodeOrEmpty?>, java.lang.Runnable {
        private val keyResultTask: AsyncSerializationTask
        private val valueResultTask: AsyncSerializationTask
        private val isExecutionValue: Boolean
        private val dependencyKey: ActionLookupKey?

        init {
            this.keyResultTask = keyResultTask
            this.valueResultTask = valueResultTask
            this.isExecutionValue = isExecutionValue
            this.dependencyKey = dependencyKey
        }

        override fun run() {
            when (fileOpNodes.computeNode(dependencyKey)) {
                -> onSuccess(nodeOrEmpty)
                -> com.google.common.util.concurrent.Futures.addCallback<V?>(
                    future,
                    this,
                    fingerprintValueService.getExecutor()
                )
            }
        }

        override fun onSuccess(nodeOrEmpty: FileOpNodeOrEmpty?) {
            try {
                writeStatuses.counters.entriesWaitingForInvalidationInfo.decrementAndGet()
                writeStatuses.counters.entriesWaitingForInvalidationBytes.incrementAndGet()

                val futureDataInfo: com.google.common.util.concurrent.ListenableFuture<InvalidationDataInfo?> =
                    when (nodeOrEmpty) {
                        -> when (fileDependencySerializer.registerDependency(node)) {
                            -> com.google.common.util.concurrent.Futures.whenAllSucceed<SerializationResult<ByteString?>?>(
                                keyResultTask,
                                valueResultTask
                            )
                                .call<InvalidationDataInfo?>(
                                    java.util.concurrent.Callable { dataInfo },
                                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                                )

                            -> com.google.common.util.concurrent.Futures.whenAllSucceed<V?>(
                                keyResultTask,
                                valueResultTask,
                                futureFile
                            )
                                .call<C?>(java.util.concurrent.Callable {
                                    com.google.common.util.concurrent.Futures.getDone<V?>(
                                        futureFile
                                    )
                                }, com.google.common.util.concurrent.MoreExecutors.directExecutor())

                            -> com.google.common.util.concurrent.Futures.whenAllSucceed<V?>(
                                keyResultTask,
                                valueResultTask,
                                futureListing
                            )
                                .call<C?>(java.util.concurrent.Callable {
                                    com.google.common.util.concurrent.Futures.getDone<V?>(
                                        futureListing
                                    )
                                }, com.google.common.util.concurrent.MoreExecutors.directExecutor())

                            -> com.google.common.util.concurrent.Futures.whenAllSucceed<V?>(
                                keyResultTask,
                                valueResultTask,
                                futureNode
                            )
                                .call<C?>(java.util.concurrent.Callable {
                                    com.google.common.util.concurrent.Futures.getDone<V?>(
                                        futureNode
                                    )
                                }, com.google.common.util.concurrent.MoreExecutors.directExecutor())
                        }

                        EmptyFileOpNode.EMPTY_FILE_OP_NODE -> com.google.common.util.concurrent.Futures.whenAllSucceed<SerializationResult<ByteString?>?>(
                            keyResultTask,
                            valueResultTask
                        ).call<InvalidationDataInfo?>(
                            java.util.concurrent.Callable { null },
                            com.google.common.util.concurrent.MoreExecutors.directExecutor()
                        )
                    }
                com.google.common.util.concurrent.Futures.addCallback<InvalidationDataInfo?>(
                    futureDataInfo,
                    InvalidationDataInfoHandler(),
                    fingerprintValueService.getExecutor()
                )
            } catch (t: Throwable) {
                onFailure(t)
            }
        }

        override fun onFailure(t: Throwable) {
            writeStatuses.counters.entriesWaitingForInvalidationInfo.decrementAndGet()
            writeStatuses.notifyWriteFailure(t)
        }

        private inner class InvalidationDataInfoHandler

            : com.google.common.util.concurrent.FutureCallback<InvalidationDataInfo?> {
            /**
             * Saves the entry for to the [FingerprintValueService].
             * 
             * 
             * The entry includes both value and the associated invalidation data. More precisely, it
             * consists of the following components.
             * 
             * 
             *  1. If `dataInfo` is null, the invalidation data is the [DATA_TYPE_EMPTY]
             * value only.
             *  1. Otherwise when `dataInfo` is non-null, the invalidation data starts with a type
             * value, [DATA_TYPE_FILE], [DATA_TYPE_LISTING], [       ] or [DATA_TYPE_EXECUTION_NODE], depending on `dataInfo`'s and [FileOpNodeProcessor.entry]'s type. The invalidation data cache
             * key follows the type value.
             *  1. The [.entry]'s value bytes.
             * 
             */
            override fun onSuccess(dataInfo: InvalidationDataInfo?) {
                try {
                    val bytesOut: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
                    val codedOut: CodedOutputStream = CodedOutputStream.newInstance(bytesOut)

                    val keyResult: SerializationResult<ByteString?>?
                    val valueResult: SerializationResult<ByteString?>?
                    try {
                        keyResult =
                            com.google.common.util.concurrent.Futures.getDone<SerializationResult<ByteString?>?>(
                                keyResultTask
                            )
                        valueResult =
                            com.google.common.util.concurrent.Futures.getDone<SerializationResult<ByteString?>?>(
                                valueResultTask
                            )
                    } catch (e: ExecutionException) {
                        throw java.lang.IllegalStateException(
                            "should have succeeded as part of the FutureCombiner", e
                        )
                    }
                    writeStatuses.addWriteStatus(valueResult.getFutureToBlockWritesOn())
                    writeStatuses.addWriteStatus(keyResult.getFutureToBlockWritesOn())

                    when (dataInfo) {
                        ConstantFileData.CONSTANT_FILE, ConstantListingData.CONSTANT_LISTING, ConstantNodeData.CONSTANT_NODE, null -> codedOut.writeEnumNoTag(
                            DATA_TYPE_EMPTY.getNumber()
                        )

                        -> {
                            codedOut.writeEnumNoTag(DATA_TYPE_FILE.getNumber())
                            codedOut.writeStringNoTag(file.cacheKey())
                            writeStatuses.addWriteStatus(file.writeStatus())
                        }

                        -> {
                            codedOut.writeEnumNoTag(DATA_TYPE_LISTING.getNumber())
                            codedOut.writeStringNoTag(listing.cacheKey())
                            writeStatuses.addWriteStatus(listing.writeStatus())
                        }

                        -> {
                            codedOut.writeEnumNoTag(
                                (if (isExecutionValue) DATA_TYPE_EXECUTION_NODE else DATA_TYPE_ANALYSIS_NODE)
                                    .getNumber()
                            )
                            node.cacheKey().writeTo(codedOut)
                            writeStatuses.addWriteStatus(node.writeStatus())
                        }
                    }
                    codedOut.writeRawBytes(valueResult.getObject())
                    codedOut.flush()

                    val versionedKey: PackedFingerprint =
                        fingerprintValueService.fingerprint(
                            frontierVersion.concat(keyResult.getObject().toByteArray())
                        )
                    val entryBytes: ByteArray = bytesOut.toByteArray()

                    // Put this in a separate variable so that we don't close over potentially large byte
                    // arrays
                    val keyByteCount: Long = versionedKey.toBytes().size.toLong()
                    val valueByteCount = entryBytes.size.toLong()
                    writeStatuses.counters.entriesWaitingForInvalidationBytes.decrementAndGet()
                    writeStatuses.counters.entriesWaitingForUpload.incrementAndGet()
                    writeStatuses.counters.keyBytesWaitingForUpload.addAndGet(keyByteCount)
                    writeStatuses.counters.valueBytesWaitingForUpload.addAndGet(valueByteCount)

                    val putStatus: WriteStatus = fingerprintValueService.put(versionedKey, entryBytes)
                    valueResultTask.registerWriteStatus(putStatus)

                    putStatus.addListener(
                        java.lang.Runnable {
                            writeStatuses.counters.entriesWaitingForUpload.decrementAndGet()
                            writeStatuses.counters.keyBytesWaitingForUpload.addAndGet(-keyByteCount)
                            writeStatuses.counters.valueBytesWaitingForUpload.addAndGet(-valueByteCount)
                            var shouldUpdateCounts: Boolean
                            try {
                                // Avoids updating counts if the writes are marked as duplicates. Note that
                                // duplicate detection is not ordinarily enabled.
                                shouldUpdateCounts =
                                    com.google.common.util.concurrent.Futures.getDone<Boolean?>(putStatus)
                            } catch (e: ExecutionException) {
                                // This error is propagated to the main control flow via `writeStatuses`.
                                shouldUpdateCounts = false
                            }
                            if (shouldUpdateCounts) {
                                writeStatuses.counters.entriesUploaded.incrementAndGet()
                                writeStatuses.counters.keyBytesUploaded.addAndGet(keyByteCount)
                                writeStatuses.counters.valueBytesUploaded.addAndGet(valueByteCount)
                            }
                        },
                        com.google.common.util.concurrent.MoreExecutors.directExecutor()
                    )

                    writeStatuses.addWriteStatus(putStatus)

                    // IMPORTANT: when this completes, no more write statuses can be added.
                    writeStatuses.selectedEntryDone()
                } catch (t: Throwable) {
                    onFailure(t)
                }
            }

            override fun onFailure(t: Throwable) {
                writeStatuses.counters.entriesWaitingForInvalidationBytes.decrementAndGet()
                writeStatuses.notifyWriteFailure(t)
            }
        }
    }

    class SerializationStatus private constructor(fileDependencySerializerCounters: com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencySerializer.Counters?) :
        QuiescingFuture<com.google.common.collect.ImmutableList<Throwable?>?>(com.google.common.util.concurrent.MoreExecutors.directExecutor()),
        com.google.common.util.concurrent.FutureCallback<Any?>, CounterSeriesCollector {
        private val semaphore: Semaphore = Semaphore(MAX_PENDING_SKYVALUES)
        private val errors: ConcurrentLinkedQueue<Throwable?> = ConcurrentLinkedQueue<Throwable?>()
        private val fileDependencySerializerCounters: com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencySerializer.Counters?
        private val counters: Counters =
            com.google.devtools.build.lib.skyframe.serialization.analysis.SelectedEntrySerializer.Counters()

        init {
            this.fileDependencySerializerCounters = fileDependencySerializerCounters

            Profiler.instance().registerCounterSeriesCollector(this)
            Profiler.instance().registerCounterSeriesCollector(counters)
            Profiler.instance().registerCounterSeriesCollector(fileDependencySerializerCounters)
        }

        private fun selectedEntryStarting() {
            increment()
        }

        private fun selectedEntryDone() {
            decrement()
        }

        private fun notifyAllStarted() {
            decrement()
        }

        private fun notifyWriteFailure(t: Throwable?) {
            errors.add(t)
            decrement()
        }

        private fun hasError(): Boolean {
            return !errors.isEmpty()
        }

        val value: com.google.common.collect.ImmutableList<Throwable?>
            get() {
                Profiler.instance().unregisterCounterSeriesCollector(this)
                Profiler.instance().unregisterCounterSeriesCollector(counters)
                Profiler.instance().unregisterCounterSeriesCollector(fileDependencySerializerCounters)
                return com.google.common.collect.ImmutableList.copyOf<Throwable?>(errors)
            }

        private fun addWriteStatus(writeStatus: com.google.common.util.concurrent.ListenableFuture<*>?) {
            if (writeStatus == null) {
                return
            }
            increment()
            com.google.common.util.concurrent.Futures.addCallback(
                writeStatus,
                this as com.google.common.util.concurrent.FutureCallback<Any?>,
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        }

        /**
         * Implementation of [<].
         * 
         */
        @Deprecated("only for use via {@link #addWriteStatus}")  // only called via addWriteStatus
        override fun onSuccess(unused: Any?) {
            decrement()
        }

        /**
         * Implementation of [<].
         * 
         */
        @Deprecated("only for use via {@link #addWriteStatus}")  // only called via addWriteStatus
        override fun onFailure(t: Throwable) {
            notifyWriteFailure(t)
        }

        public override fun collect(
            deltaNanos: Double,
            consumer: java.util.function.BiConsumer<CounterSeriesTask?, Double?>
        ) {
            // This should really be a method on StatsCollector but that means that serialization must
            // depend on profiler, and profiler transitively depends on serialization because it depends
            // on
            // common/options, which has EnvVar, which is marked as @AutoCodec.

            consumer.accept(
                BYTES_WAITING_FOR_FUTURE_PUTS,
                SharedValueSerializationContext.COUNTERS.getBytesWaitingForFuturePuts().toDouble()
            )
            consumer.accept(
                BYTES_WAITING_FOR_UPLOAD,
                SharedValueSerializationContext.COUNTERS.getBytesWaitingForUpload().toDouble()
            )
            consumer.accept(
                BYTES_UPLOADED, SharedValueSerializationContext.COUNTERS.getBytesUploaded().toDouble()
            )
            consumer.accept(
                OBJECTS_WAITING_FOR_SERIALIZATION,
                SharedValueSerializationContext.COUNTERS.getObjectsWaitingForSerialization().toDouble()
            )
            consumer.accept(
                OBJECTS_WAITING_FOR_FUTURE_PUTS,
                SharedValueSerializationContext.COUNTERS.getObjectsWaitingForFuturePuts().toDouble()
            )
            consumer.accept(
                OBJECTS_WAITING_FOR_UPLOAD,
                SharedValueSerializationContext.COUNTERS.getObjectsWaitingForUpload().toDouble()
            )
            consumer.accept(
                OBJECTS_UPLOADED, SharedValueSerializationContext.COUNTERS.getObjectsUploaded().toDouble()
            )
        }

        companion object {
            private val BYTES_WAITING_FOR_FUTURE_PUTS: CounterSeriesTask = CounterSeriesTask(
                "Skycache: Serialization: Bytes: Pending", "Waiting for future puts", Color.RAIL_LOAD
            )
            private val BYTES_WAITING_FOR_UPLOAD: CounterSeriesTask = CounterSeriesTask(
                "Skycache: Serialization: Bytes: Pending", "Waiting for upload", Color.RAIL_LOAD
            )
            private val BYTES_UPLOADED: CounterSeriesTask = CounterSeriesTask(
                "Skycache: Serialization: Bytes: Uploaded", "Written", Color.RAIL_RESPONSE
            )
            private val OBJECTS_WAITING_FOR_SERIALIZATION: CounterSeriesTask = CounterSeriesTask(
                "Skycache: Serialization: Objects: Pending",
                "Waiting for serialization",
                Color.RAIL_LOAD
            )
            private val OBJECTS_WAITING_FOR_FUTURE_PUTS: CounterSeriesTask = CounterSeriesTask(
                "Skycache: Serialization: Objects: Pending",
                "Waiting for future puts",
                Color.RAIL_LOAD
            )
            private val OBJECTS_WAITING_FOR_UPLOAD: CounterSeriesTask = CounterSeriesTask(
                "Skycache: Serialization: Objects: Pending", "Waiting for upload", Color.RAIL_LOAD
            )
            private val OBJECTS_UPLOADED: CounterSeriesTask = CounterSeriesTask(
                "Skycache: Serialization: Objects: Uploaded", "done", Color.RAIL_RESPONSE
            )
        }
    }

    companion object {
        // Chosen completely arbitrarily and the first attempt worked out quite well
        private const val MAX_PENDING_SKYVALUES = 10000

        /** Uploads the entries of `selection` to `fingerprintValueService`.  */
        @Throws(java.lang.InterruptedException::class)
        fun uploadSelection(
            graph: InMemoryGraph,
            versionGetter: LongVersionGetter?,
            codecs: ObjectCodecs,
            frontierVersion: FrontierNodeVersion,
            selection: com.google.common.collect.ImmutableSet<SkyKey?>,
            fingerprintValueService: FingerprintValueService,
            fileInvalidationWriter: KeyValueWriter?,
            eventBus: com.google.common.eventbus.EventBus,
            profileCollector: ProfileCollector?,
            serializationStats: SerializationStats
        ): QuiescingFuture<com.google.common.collect.ImmutableList<Throwable?>?> {
            val fileOpNodes: FileOpNodeMemoizingLookup =
                FileOpNodeMemoizingLookup(fingerprintValueService.getExecutor(), graph)
            val fileDependencySerializer: FileDependencySerializer =
                FileDependencySerializer(
                    versionGetter,
                    graph,
                    fileInvalidationWriter,
                    fingerprintValueService.getExecutor(),
                    profileCollector
                )
            val writeStatuses = SerializationStatus(fileDependencySerializer.getCounters())
            val serializer =
                SelectedEntrySerializer(
                    graph,
                    codecs,
                    frontierVersion,
                    fingerprintValueService,
                    fileOpNodes,
                    fileDependencySerializer,
                    writeStatuses,
                    eventBus,
                    profileCollector,
                    serializationStats
                )

            // A topological sort prevents the antipattern where one serializes a high level node, walks
            // its whole transitive closure, then serializes lower level nodes, thus revisiting the
            // transitive closure again.
            //
            // This doesn't help a lot when one only serializes a frontier or a small active set (since
            // the majority of the Skyframe graph is not serialized), but does help when serializing a
            // lot of nodes.
            val sortedSelection: com.google.common.collect.ImmutableList<SkyKey> = sortTopologically(selection, graph)

            for (selectedKey in sortedSelection) {
                // We acquire the semaphore here and not in the Runnable so as not to starve the thread pool.
                writeStatuses.semaphore.acquire()
                writeStatuses.selectedEntryStarting()
                fingerprintValueService
                    .getExecutor()
                    .execute(
                        java.lang.Runnable {
                            try {
                                serializer.accept(selectedKey)
                            } catch (t: Throwable) {
                                writeStatuses.notifyWriteFailure(t) // Propagates uncaught exceptions.
                            } finally {
                                writeStatuses.semaphore.release()
                            }
                        })
            }

            writeStatuses.notifyAllStarted()
            return writeStatuses
        }

        private fun isExecutionValue(key: SkyKey?): Boolean {
            // TODO: b/439060530: consider whether this is correct for ActionTemplateExpansionValue keys.
            return key !is ActionLookupKey
        }

        /** Sorts `selection` topologically based on the edges in `graph`.  */
        private fun sortTopologically(
            selection: com.google.common.collect.ImmutableSet<SkyKey?>, graph: InMemoryGraph
        ): com.google.common.collect.ImmutableList<SkyKey> {
            val result: java.util.ArrayList<SkyKey?> = java.util.ArrayList<SkyKey?>(selection.size())
            val visited: MutableSet<SkyKey?> =
                com.google.common.collect.Sets.newHashSetWithExpectedSize<SkyKey?>(selection.size())
            for (key in selection) {
                if (visited.add(key)) {
                    dfs(key, selection, graph, visited, result)
                }
            }
            return com.google.common.collect.ImmutableList.copyOf<SkyKey?>(result)
        }

        private fun dfs(
            key: SkyKey?,
            selection: com.google.common.collect.ImmutableSet<SkyKey?>,
            graph: InMemoryGraph,
            visited: MutableSet<SkyKey?>,
            result: MutableList<SkyKey?>
        ) {
            val entry: InMemoryNodeEntry? = graph.getIfPresent(key)
            if (entry == null) {
                return
            }

            for (dep in entry.getDirectDeps()) {
                // This is suboptimal when "selection" is non-contiguous, but makes it possible to avoid
                // visiting the whole Skyframe graph when serializing just a frontier
                if (selection.contains(dep) && visited.add(dep)) {
                    dfs(dep, selection, graph, visited, result)
                }
            }

            result.add(key)
        }
    }
}
