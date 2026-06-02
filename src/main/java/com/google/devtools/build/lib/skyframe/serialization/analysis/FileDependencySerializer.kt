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
package com.google.devtools.build.lib.skyframe.serialization.analysis

import com.google.devtools.build.lib.actions.FileStateType.SYMLINK

/**
 * Records [FileKey], [DirectoryListingKey] or [AbstractNestedFileOpNodes]
 * invalidation to a remote [KeyValueWriter].
 */
internal class FileDependencySerializer(
    versionGetter: LongVersionGetter,
    graph: InMemoryGraph,
    writer: KeyValueWriter,
    executor: java.util.concurrent.Executor,
    profileCollector: ProfileCollector?
) {
    /**
     * Counters for the progress of serialization.
     * 
     * 
     * Logged in the trace profile.
     */
    internal class Counters : CounterSeriesCollector {
        @kotlin.jvm.JvmField
        @com.google.common.annotations.VisibleForTesting
        val nodesWaitingForDeps: AtomicLong = AtomicLong()

        @com.google.common.annotations.VisibleForTesting
        val nodesWaitingForUpload: AtomicLong = AtomicLong()

        @com.google.common.annotations.VisibleForTesting
        val nodesUploaded: AtomicLong = AtomicLong()

        @kotlin.jvm.JvmField
        @com.google.common.annotations.VisibleForTesting
        val nodesWithProcessingErrors: AtomicLong = AtomicLong()

        @com.google.common.annotations.VisibleForTesting
        val keyBytesWaitingForUpload: AtomicLong = AtomicLong()

        @com.google.common.annotations.VisibleForTesting
        val valueBytesWaitingForUpload: AtomicLong = AtomicLong()

        @com.google.common.annotations.VisibleForTesting
        val keyBytesUploaded: AtomicLong = AtomicLong()

        @com.google.common.annotations.VisibleForTesting
        val valueBytesUploaded: AtomicLong = AtomicLong()

        public override fun collect(
            deltaNanos: Double,
            consumer: java.util.function.BiConsumer<CounterSeriesTask?, Double?>
        ) {
            consumer.accept(
                com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencySerializer.Counters.Companion.NODES_WAITING_FOR_DEPS,
                nodesWaitingForDeps.get().toDouble()
            )
            consumer.accept(
                com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencySerializer.Counters.Companion.NODES_WAITING_FOR_UPLOAD,
                nodesWaitingForUpload.get().toDouble()
            )
            consumer.accept(
                com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencySerializer.Counters.Companion.NODES_UPLOADED,
                nodesUploaded.get().toDouble()
            )
            consumer.accept(
                com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencySerializer.Counters.Companion.NODES_WITH_PROCESSING_ERRORS,
                nodesWithProcessingErrors.get().toDouble()
            )
            consumer.accept(
                com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencySerializer.Counters.Companion.KEY_BYTES_WAITING_FOR_UPLOAD,
                keyBytesWaitingForUpload.get().toDouble()
            )
            consumer.accept(
                com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencySerializer.Counters.Companion.VALUE_BYTES_WAITING_FOR_UPLOAD,
                valueBytesWaitingForUpload.get().toDouble()
            )
            consumer.accept(
                com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencySerializer.Counters.Companion.KEY_BYTES_UPLOADED,
                keyBytesUploaded.get().toDouble()
            )
            consumer.accept(
                com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencySerializer.Counters.Companion.VALUE_BYTES_UPLOADED,
                valueBytesUploaded.get().toDouble()
            )
        }

        companion object {
            private val NODES_WAITING_FOR_DEPS: CounterSeriesTask = CounterSeriesTask(
                "Skycache: Invalidation: Nodes: Pending", "Waiting for deps", Color.RAIL_LOAD
            )
            private val NODES_WAITING_FOR_UPLOAD: CounterSeriesTask = CounterSeriesTask(
                "Skycache: Invalidation: Nodes: Pending", "Waiting for upload", Color.RAIL_LOAD
            )
            private val NODES_UPLOADED: CounterSeriesTask = CounterSeriesTask(
                "Skycache: Invalidation: Nodes: Uploaded", "Uploaded", Color.RAIL_RESPONSE
            )
            private val NODES_WITH_PROCESSING_ERRORS: CounterSeriesTask = CounterSeriesTask(
                "Skycache: Invalidation: Nodes: Processing Errors",
                "Processing Errors",
                Color.RAIL_RESPONSE
            )

            private val KEY_BYTES_WAITING_FOR_UPLOAD: CounterSeriesTask =
                CounterSeriesTask("Skycache: Invalidation: Bytes: Pending", "Key", Color.RAIL_LOAD)
            private val VALUE_BYTES_WAITING_FOR_UPLOAD: CounterSeriesTask =
                CounterSeriesTask("Skycache: Invalidation: Bytes: Pending", "Value", Color.RAIL_LOAD)
            private val KEY_BYTES_UPLOADED: CounterSeriesTask = CounterSeriesTask(
                "Skycache: Invalidation: Bytes: Uploaded", "Key", Color.RAIL_RESPONSE
            )
            private val VALUE_BYTES_UPLOADED: CounterSeriesTask = CounterSeriesTask(
                "Skycache: Invalidation: Bytes: Uploaded", "Value", Color.RAIL_RESPONSE
            )
        }
    }

    private val versionGetter: LongVersionGetter
    private val graph: InMemoryGraph
    private val writer: KeyValueWriter
    private val executor: java.util.concurrent.Executor
    @kotlin.jvm.JvmField
    val counters: Counters
    private val profileCollector: ProfileCollector?

    private val fileDataInfo: ValueOrFutureMap<com.google.devtools.build.lib.skyframe.FileKey?, FileDataInfoOrFuture?, FileDataInfo?, FutureFileDataInfo?> =
        ValueOrFutureMap<KeyT?, ValueOrFutureT?, ValueT?, FutureT?>(
            ConcurrentHashMap<Any?, Any?>(),
            java.util.function.BiFunction { key: KeyT?, consumer: java.util.function.BiConsumer<KeyT?, ValueT?>? ->
                FutureFileDataInfo(
                    key,
                    consumer
                )
            },
            java.util.function.Function { future: FutureT? -> this.populateFutureFileDataInfo(future) },
            FutureFileDataInfo::class.java
        )

    private val listingDataInfo: ValueOrFutureMap<DirectoryListingKey?, ListingDataInfoOrFuture?, ListingDataInfo?, FutureListingDataInfo?> =
        ValueOrFutureMap<KeyT?, ValueOrFutureT?, ValueT?, FutureT?>(
            ConcurrentHashMap<Any?, Any?>(),
            java.util.function.BiFunction { key: KeyT?, consumer: java.util.function.BiConsumer<KeyT?, ValueT?>? ->
                FutureListingDataInfo(
                    key,
                    consumer
                )
            },
            java.util.function.Function { future: FutureT? -> this.populateFutureListingDataInfo(future) },
            FutureListingDataInfo::class.java
        )

    init {
        this.versionGetter = versionGetter
        this.graph = graph
        this.writer = writer
        this.executor = executor
        this.counters =
            com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencySerializer.Counters()
        this.profileCollector = profileCollector
    }

    /**
     * Stores data about a `node` and its transitive dependencies in [.writer] to be used
     * for invalidation.
     * 
     * 
     * The resulting data can be embedded in reverse deps of `node` and used to invalidate
     * them by checking against a list of changed files and directory listings.
     * 
     * 
     * See comments at [FileInvalidationData] and [DirectoryListingInvalidationData]
     * for more details about the data being persisted.
     */
    fun registerDependency(node: FileOpNode): InvalidationDataInfoOrFuture? {
        when (node) {
            -> return registerDependency(file)
            -> return registerDependency(listing)
            -> return registerDependency(nested)
        }
    }

    fun registerDependency(key: com.google.devtools.build.lib.skyframe.FileKey?): FileDataInfoOrFuture? {
        return fileDataInfo.getValueOrFuture(key)
    }

    fun registerDependency(key: DirectoryListingKey?): ListingDataInfoOrFuture? {
        return listingDataInfo.getValueOrFuture(key)
    }

    /**
     * Registers a dependency on the set of transitive dependencies represented by `node`.
     * 
     * 
     * Uploads the result to the [.writer].
     */
    fun registerDependency(node: AbstractNestedFileOpNodes): NodeDataInfoOrFuture? {
        var reference: NodeDataInfoOrFuture? = node.getSerializationScratch() as NodeDataInfoOrFuture?
        if (reference != null) {
            return reference
        }

        val future: FutureNodeDataInfo?
        synchronized(node) {
            reference = node.getSerializationScratch() as NodeDataInfoOrFuture?
            if (reference != null) {
                return reference
            }

            future = FutureNodeDataInfo(node)
            node.setSerializationScratch(future)
        }

        // If this is reached, this thread owns `future` and must set its value.
        try {
            return populateFutureNodeDataInfo(future)
        } finally {
            future.verifyComplete()
        }
    }

    /**
     * Populates the [FileDataInfoOrFuture] for the given [FutureFileDataInfo].
     * 
     * 
     * This method is responsible for resolving the [FileKey] and its dependencies, and
     * uploading the resulting [FileInvalidationData] to the [.writer].
     * 
     * @param future The [FutureFileDataInfo] to populate.
     * @return The populated [FileDataInfoOrFuture].
     */
    fun populateFutureFileDataInfo(future: FutureFileDataInfo): FileDataInfoOrFuture {
        counters.nodesWaitingForDeps.incrementAndGet()

        val key: com.google.devtools.build.lib.skyframe.FileKey = future.key()
        val rootedPath: RootedPath = key.argument()
        val parentRootedPath: RootedPath?
        // Builtin files don't change.
        if ((rootedPath.getRoot().getFileSystem() is BundledFileSystem) // Assumes that the root folder doesn't change.
            || (rootedPath.getParentDirectory().also { parentRootedPath = it }) == null
        ) {
            counters.nodesWaitingForDeps.decrementAndGet()
            return future.completeWith(ConstantFileData.CONSTANT_FILE)
        }

        val nodeEntry: InMemoryNodeEntry? = graph.getIfPresent(key)
        if (nodeEntry == null) {
            counters.nodesWaitingForDeps.decrementAndGet()
            counters.nodesWithProcessingErrors.incrementAndGet()
            return future.failWith(MissingSkyframeEntryException(key))
        }
        val value: FileValue? = nodeEntry.getValue() as FileValue?
        val realRootedPath: RootedPath? = value.realRootedPath(rootedPath)

        val initialMtsv: Long
        if (value.isDirectory()) {
            // Matches the behavior of PathVersionGetter.getVersionForExistingPathInternal.
            initialMtsv = LongVersionGetter.MINIMAL
        } else {
            try {
                initialMtsv = getVersion(realRootedPath, value.exists())
            } catch (e: IOException) {
                counters.nodesWaitingForDeps.decrementAndGet()
                counters.nodesWithProcessingErrors.incrementAndGet()
                return future.failWith(e)
            }
        }
        val uploader =
            FileInvalidationDataUploader( /* rootedPath= */
                rootedPath,  /* parentRootedPath= */
                parentRootedPath,  /* realRootedPath= */
                realRootedPath,
                value.exists(),
                initialMtsv
            )
        // The following steps are performed to ensure that ancestors and ancestor symlinks are resolved
        // to compute the correct MTSV:
        // 1. Call fullyResolvePath to register all the parents of the current rootedPath as
        //    dependencies first.
        // 2. The transform() method takes the output of the first parameter (a future) and passes it to
        //    the second parameter (a function).
        // 3. The output of fullyResolvePath is Void, so the transform method is only being used as a
        //    stop to not trigger the upload of the current rootedPath till its parents have been
        //    registered.
        // 4. The uploader itself is a Function that directly returns a FileDataInfo but gets wrapped as
        //    a future by the transform method.
        // 5. The upload happens through the put() operation in the writer inside the uploader.
        val resolutionFuture: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
            fullyResolvePath(if (value.isSymlink()) value.getUnresolvedLinkTarget() else null, uploader)

        com.google.common.util.concurrent.Futures.addCallback<java.lang.Void?>(
            resolutionFuture,
            object : com.google.common.util.concurrent.FutureCallback<java.lang.Void?> {
                override fun onSuccess(result: java.lang.Void?) {
                    // If resolution is successful, `uploader` completes the process via
                    // `Futures.transform`. The counters will be handled in the `uploader` later.
                }

                override fun onFailure(t: Throwable) {
                    counters.nodesWaitingForDeps.decrementAndGet()
                    counters.nodesWithProcessingErrors.incrementAndGet()
                }
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )

        return future.completeWith(
            com.google.common.util.concurrent.Futures.transform<I?, O?>(
                resolutionFuture,
                uploader,
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        )
    }

    /**
     * Performs the upload of the [FileInvalidationData] once resolution is complete in the
     * [.apply] callback.
     */
    private inner class FileInvalidationDataUploader
        (
        rootedPath: RootedPath,
        parentRootedPath: RootedPath,
        realRootedPath: RootedPath?,
        exists: Boolean,
        initialMtsv: Long
    ) : com.google.common.base.Function<java.lang.Void?, FileInvalidationDataInfo?> {
        private val rootedPath: RootedPath
        private val parentRootedPath: RootedPath
        private val realRootedPath: RootedPath?
        private val exists: Boolean

        private val data: FileInvalidationData.Builder = FileInvalidationData.newBuilder()
        private val writeStatuses: java.util.ArrayList<WriteStatus?> = java.util.ArrayList<WriteStatus?>()
        private var mtsv: Long

        init {
            this.rootedPath = rootedPath
            this.parentRootedPath = parentRootedPath
            this.realRootedPath = realRootedPath
            this.exists = exists
            this.mtsv = initialMtsv
        }

        override fun apply(unused: java.lang.Void?): FileInvalidationDataInfo {
            val cacheKey: String = FileDependencyKeySupport.computeCacheKey(
                rootedPath.getRootRelativePath(),
                mtsv,
                FileDependencyKeySupport.FILE_KEY_DELIMITER
            )
            val keyBytes: KeyBytesProvider = getKeyBytes(cacheKey, data::setOverflowKey)
            val dataBytes: ByteArray = data.build().toByteArray()
            val keyByteCount: Long = keyBytes.toBytes().size.toLong()
            val valueByteCount = dataBytes.size.toLong() // Don't hold on to the bytes any longer than needed

            counters.nodesWaitingForDeps.decrementAndGet()
            counters.nodesWaitingForUpload.incrementAndGet()
            counters.keyBytesWaitingForUpload.addAndGet(keyByteCount)
            counters.valueBytesWaitingForUpload.addAndGet(valueByteCount)

            val writeStatus: WriteStatus = writer.put(keyBytes, dataBytes)
            writeStatus.addListener(
                java.lang.Runnable {
                    counters.nodesWaitingForUpload.decrementAndGet()
                    counters.nodesUploaded.incrementAndGet()
                    counters.keyBytesWaitingForUpload.addAndGet(-keyByteCount)
                    counters.valueBytesWaitingForUpload.addAndGet(-valueByteCount)
                    counters.keyBytesUploaded.addAndGet(keyByteCount)
                    counters.valueBytesUploaded.addAndGet(valueByteCount)
                },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
            if (profileCollector != null) {
                recordInvalidationProfile(
                    profileCollector,
                    InvalidationEntryType.FILE,
                    keyByteCount,
                    valueByteCount,
                    writeStatus
                )
            }
            writeStatuses.add(writeStatus)
            return FileInvalidationDataInfo(
                cacheKey, WriteStatuses.sparselyAggregateWriteStatuses(writeStatuses), exists, mtsv, realRootedPath
            )
        }

        /**
         * Adds information about the invalidation data of [.parentRootedPath].
         * 
         * 
         * This is called at most once but might not be called at all if [.parentRootedPath]
         * refers to a constant path.
         */
        fun addParent(parent: FileInvalidationDataInfo) {
            val parentMtsv: Long = parent.mtsv()
            if (parentMtsv != LongVersionGetter.MINIMAL) {
                data.setParentMtsv(parentMtsv)
            }
            updateMtsvIfGreater(parentMtsv)
            writeStatuses.add(parent.writeStatus())
        }

        /**
         * Incorporates information about a symlink parent.
         * 
         * 
         * If a symlink is present, it's possible that combining the symlink with [ ][.parentRootedPath] points to a file who's parent directory hasn't been resolved. Resolution
         * of that parent directory results in `parentInfo`.
         * 
         * 
         * If the symlink points to a symlink, it's possible for that symlink to point to a file
         * having a yet another parent directory that has to be resolved again. In that case this method
         * would be called again.
         */
        fun addSymlinkParentInfo(parentInfo: FileInvalidationDataInfo) {
            updateMtsvIfGreater(parentInfo.mtsv())
            writeStatuses.add(parentInfo.writeStatus())
        }

        fun updateMtsvIfGreater(version: Long) {
            if (version > mtsv) {
                mtsv = version
            }
        }

        fun addSymlinksBuilder(): Symlink.Builder {
            return data.addSymlinksBuilder()
        }
    }

    /** Resolves the parent then resolves symlinks if `unresolvedLinkTarget` is non-null.  */
    private fun fullyResolvePath(
        unresolvedLinkTarget: PathFragment?, uploader: FileInvalidationDataUploader
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        val pathResolver = PathResolver(unresolvedLinkTarget, uploader)
        when (registerDependency(FileValue.key(uploader.parentRootedPath))) {
            -> return pathResolver.apply(parentData)
            -> return com.google.common.util.concurrent.Futures.transformAsync<I?, O?>(
                futureParentData,
                pathResolver,
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        }
    }

    /**
     * Waits for [FileInvalidationDataUploader.parentRootedPath] to be resolved (signalled
     * through the [.apply] callback) and starts symlink resolution if there is a symlink.
     */
    internal inner class PathResolver private constructor(
        unresolvedLinkTarget: PathFragment?,
        uploader: FileInvalidationDataUploader
    ) : com.google.common.util.concurrent.AsyncFunction<FileDataInfo?, java.lang.Void?> {
        /** Symlink's target path.  */
        // non-null if there is a symlink
        private val unresolvedLinkTarget: PathFragment?

        private val uploader: FileInvalidationDataUploader

        init {
            this.unresolvedLinkTarget = unresolvedLinkTarget
            this.uploader = uploader
        }

        override fun apply(parentData: FileDataInfo): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
            val realParentPath: RootedPath
            when (parentData) {
                ConstantFileData.CONSTANT_FILE ->           // Assumes that BundledFileSystem does not symlink outside of BundledFileSystem.
                    realParentPath = uploader.parentRootedPath

                -> {
                    uploader.addParent(parentReference)
                    // If the parent folder doesn't exist, unresolvedLinkTarget will be null.
                    realParentPath = parentReference.realPath()
                }
            }

            if (unresolvedLinkTarget == null) {
                return com.google.common.util.concurrent.Futures.immediateVoidFuture() // No symlink processing needed.
            }

            val linkPath: com.google.devtools.build.lib.vfs.Path? // Real path to the symlink.
            if (realParentPath == uploader.parentRootedPath) {
                linkPath = uploader.rootedPath.asPath()
            } else {
                linkPath =
                    realParentPath
                        .asPath()
                        .getRelative(uploader.rootedPath.getRootRelativePath().getBaseName())
            }
            return processSymlinks(realParentPath, linkPath, unresolvedLinkTarget, uploader)
        }
    }

    /**
     * Recursively processes symlinks.
     * 
     * 
     * Requires that there are no symlink cycles (though ancestor references are benign). This is
     * assumed to hold for builds that succeed.
     * 
     * @param parentRootedPath the real parent of the symlink
     * @param linkPath the real path to the symlink
     * @param link the target path contents of the symlink
     * @param uploader original uploader instance for the path resolution encountering this symlink
     */
    private fun processSymlinks(
        parentRootedPath: RootedPath,
        linkPath: com.google.devtools.build.lib.vfs.Path?,
        link: PathFragment,
        uploader: FileInvalidationDataUploader
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        if (link.isAbsolute()) {
            if (TestType.isInTest()) {
                // Test environments may use absolute symlinks, which aren't allowed in production
                // environments with analysis caching. Skips further dependency resolution for those.
                return com.google.common.util.concurrent.Futures.immediateVoidFuture()
            }
            throw java.lang.IllegalStateException(
                java.lang.String.format("Absolute symlink not permitted: %s contained %s", linkPath, link)
            )
        }
        val symlinkData: Symlink.Builder = uploader.addSymlinksBuilder().setContents(link.getPathString())
        val linkParent: PathFragment = parentRootedPath.getRootRelativePath()
        val unresolvedTarget: PathFragment = linkParent.getRelative(link)

        // Assumes that there are no external symlinks, e.g. ones that go above root.
        com.google.common.base.Preconditions.checkArgument(
            !unresolvedTarget.containsUplevelReferences(),
            "symlink link above root for %s : %s = (%s) + (%s)",
            parentRootedPath,
            unresolvedTarget,
            linkParent,
            link
        )

        try {
            // Includes the version of the link itself in the MTSV.
            uploader.updateMtsvIfGreater(getVersion(linkPath,  /* exists= */true))
        } catch (e: IOException) {
            return com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(e)
        }

        if (unresolvedTarget.isEmpty()) {
            // It was a symlink to root. It's unclear how this ever useful, but it's not illegal. No
            // resolution required.
            return com.google.common.util.concurrent.Futures.immediateVoidFuture()
        }

        val unresolvedTargetParent: PathFragment? = unresolvedTarget.getParentDirectory()

        if (linkParent.startsWith(unresolvedTargetParent)) {
            // Any ancestor directories of the fully resolved `linkParent` are already resolved so
            // there's no further ancestor resolution here.
            return processSymlinkTarget(
                RootedPath.toRootedPath(parentRootedPath.getRoot(), unresolvedTarget), uploader
            )
        }

        val parentProcessor = SymlinkParentProcessor(parentRootedPath, link, uploader, symlinkData)

        // The parent path was changed by the link so it needs to be newly resolved.
        when (checkNotNull(
            registerDependency(
                FileValue.key(RootedPath.toRootedPath(parentRootedPath.getRoot(), unresolvedTargetParent))
            ),
            unresolvedTargetParent
        )) {
            -> return parentProcessor.apply(data)
            -> return com.google.common.util.concurrent.Futures.transformAsync<I?, O?>(
                future,
                parentProcessor,
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        }
    }

    private fun processSymlinkTarget(
        resolvedSymlinkPath: RootedPath, uploader: FileInvalidationDataUploader
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        val nodeEntry: InMemoryNodeEntry? = graph.getIfPresent(resolvedSymlinkPath)
        if (nodeEntry == null) {
            return com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(
                MissingSkyframeEntryException(resolvedSymlinkPath)
            )
        }
        val symlinkValue: FileStateValue? = nodeEntry.getValue() as FileStateValue?
        if (!symlinkValue.getType().equals(SYMLINK)) {
            // We've come full circle back to the initial, fully resolved, FileValue. So there's no
            // additional bookkeeping needed.
            return com.google.common.util.concurrent.Futures.immediateVoidFuture()
        }
        return processSymlinks(
            resolvedSymlinkPath.getParentDirectory(),
            resolvedSymlinkPath.asPath(),
            symlinkValue.getSymlinkTarget(),
            uploader
        )
    }

    /**
     * Waits for information about a symlink's real parent, signalled through the [.apply]
     * callback.
     * 
     * 
     * Once the real parent is known, syntactically combines it with the symlink target path. Then,
     * continues resolving the new path, potentially triggering recursion if the target is another
     * symlink.
     */
    private inner class SymlinkParentProcessor(
        parentPath: RootedPath,
        link: PathFragment,
        uploader: FileInvalidationDataUploader,
        symlinkData: Symlink.Builder
    ) : com.google.common.util.concurrent.AsyncFunction<FileDataInfo?, java.lang.Void?> {
        private val parentPath: RootedPath
        private val link: PathFragment
        private val uploader: FileInvalidationDataUploader
        private val symlinkData: Symlink.Builder

        init {
            this.parentPath = parentPath
            this.link = link
            this.uploader = uploader
            this.symlinkData = symlinkData
        }

        override fun apply(realParentData: FileDataInfo): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
            val resolvedParentRootedPath: RootedPath
            when (realParentData) {
                ConstantFileData.CONSTANT_FILE ->           // Assumes that symlinks in BundledFileSystem do not escape.
                    resolvedParentRootedPath = parentPath

                -> {
                    uploader.addSymlinkParentInfo(parentReference)
                    val parentMtsv: Long = parentReference.mtsv()
                    if (parentMtsv != LongVersionGetter.MINIMAL) {
                        symlinkData.setParentMtsv(parentMtsv)
                    }
                    if (!parentReference.exists()) {
                        // The parent folder doesn't exist so further resolution of the symlink is moot.
                        return com.google.common.util.concurrent.Futures.immediateVoidFuture()
                    }
                    resolvedParentRootedPath = parentReference.realPath()
                }
            }
            return processSymlinkTarget(
                RootedPath.toRootedPath(
                    resolvedParentRootedPath.getRoot(),
                    resolvedParentRootedPath.getRootRelativePath().getRelative(link.getBaseName())
                ),
                uploader
            )
        }
    }

    private fun populateFutureListingDataInfo(future: FutureListingDataInfo): ListingDataInfoOrFuture {
        counters.nodesWaitingForDeps.incrementAndGet()

        val rootedPath: RootedPath = future.key().argument()
        if (rootedPath.getRoot().getFileSystem() is BundledFileSystem) {
            counters.nodesWaitingForDeps.decrementAndGet()
            return future.completeWith(ConstantListingData.CONSTANT_LISTING) // This listing doesn't change.
        }
        val handler = ListingFileHandler(rootedPath)
        when (registerDependency(FileValue.key(rootedPath))) {
            -> return future.completeWith(handler.apply(info))
            -> return future.completeWith(
                com.google.common.util.concurrent.Futures.transformAsync<I?, O?>(
                    futureInfo,
                    handler,
                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                )
            )
        }
    }

    private inner class ListingFileHandler(rootedPath: RootedPath) :
        com.google.common.util.concurrent.AsyncFunction<FileDataInfo?, ListingDataInfo?> {
        private val rootedPath: RootedPath

        init {
            this.rootedPath = rootedPath
        }

        /**
         * Incorporates information from the file associated with the directory.
         * 
         * 
         * This code assumes that the directory exists, as does [ ][com.google.devtools.build.lib.skyframe.DirectoryListingValue.key].
         */
        override fun apply(info: FileDataInfo): com.google.common.util.concurrent.ListenableFuture<ListingDataInfo?> {
            val data: DirectoryListingInvalidationData.Builder = DirectoryListingInvalidationData.newBuilder()
            val writeStatuses: java.util.ArrayList<WriteStatus?> = java.util.ArrayList<WriteStatus?>()
            val fileMtsv: Long
            val realPath: RootedPath
            when (info) {
                ConstantFileData.CONSTANT_FILE -> {
                    realPath = rootedPath
                    fileMtsv = LongVersionGetter.MINIMAL
                }

                -> {
                    writeStatuses.add(fileInfo.writeStatus())
                    fileMtsv = fileInfo.mtsv()
                    if (fileMtsv != LongVersionGetter.MINIMAL) {
                        data.setFileMtsv(fileMtsv)
                    }
                    realPath = fileInfo.realPath()
                }
            }

            val dirMtsvFuture: com.google.common.util.concurrent.ListenableFuture<Long?> =
                com.google.common.util.concurrent.Futures.submit<Long?>(
                    java.util.concurrent.Callable { versionGetter.getDirectoryListingVersion(realPath.asPath()) } as java.util.concurrent.Callable<Long?>,
                    executor)

            return com.google.common.util.concurrent.Futures.transform<Long?, ListingDataInfo?>(
                dirMtsvFuture,
                com.google.common.base.Function { dirMtsv: Long? ->
                    val mtsv: Long = java.lang.Math.max(dirMtsv, fileMtsv)
                    val cacheKey: String =
                        FileDependencyKeySupport.computeCacheKey(
                            rootedPath.getRootRelativePath(),
                            mtsv,
                            FileDependencyKeySupport.DIRECTORY_KEY_DELIMITER
                        )
                    val keyBytes: KeyBytesProvider = getKeyBytes(cacheKey, data::setOverflowKey)
                    val dataBytes: ByteArray = data.build().toByteArray()

                    val keyByteCount: Long = keyBytes.toBytes().size.toLong()
                    val valueByteCount = dataBytes.size.toLong() // Do not hold on to the bytes

                    counters.nodesWaitingForDeps.decrementAndGet()
                    counters.nodesWaitingForUpload.incrementAndGet()
                    counters.keyBytesWaitingForUpload.addAndGet(keyByteCount)
                    counters.valueBytesWaitingForUpload.addAndGet(valueByteCount)

                    val writeStatus: WriteStatus = writer.put(keyBytes, dataBytes)
                    writeStatus.addListener(
                        java.lang.Runnable {
                            counters.nodesWaitingForUpload.decrementAndGet()
                            counters.nodesUploaded.incrementAndGet()
                            counters.keyBytesWaitingForUpload.addAndGet(-keyByteCount)
                            counters.valueBytesWaitingForUpload.addAndGet(-valueByteCount)
                            counters.keyBytesUploaded.addAndGet(keyByteCount)
                            counters.valueBytesUploaded.addAndGet(valueByteCount)
                        },
                        com.google.common.util.concurrent.MoreExecutors.directExecutor()
                    )
                    if (profileCollector != null) {
                        recordInvalidationProfile(
                            profileCollector,
                            InvalidationEntryType.LISTING,
                            keyByteCount,
                            valueByteCount,
                            writeStatus
                        )
                    }
                    writeStatuses.add(writeStatus)
                    ListingInvalidationDataInfo(
                        cacheKey, WriteStatuses.sparselyAggregateWriteStatuses(writeStatuses)
                    )
                },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        }
    }

    fun populateFutureNodeDataInfo(future: FutureNodeDataInfo): NodeDataInfoOrFuture {
        counters.nodesWaitingForDeps.incrementAndGet()

        val node: AbstractNestedFileOpNodes = future.key()
        val dependencyHandler = NodeDependencyHandler()

        // Loops through all node dependencies, registering them with the dependencyHandler. The
        // dependencyHandler triggers recursive registration, keeping track of immediate results and
        // any futures.
        for (i in 0..<node.analysisDependenciesCount()) {
            when (node.getAnalysisDependency(i)) {
                -> dependencyHandler.addFileKey(fileKey)
                -> dependencyHandler.addListingKey(listingKey)
                -> dependencyHandler.addNodeKey(nestedKeys)
            }
        }

        when (node) {
            -> {}
            -> dependencyHandler.setSourceFile(withSource.source())
        }

        val allFutures: com.google.common.collect.ImmutableList<com.google.common.util.concurrent.ListenableFuture<*>?> =
            dependencyHandler.combinedFutures
        if (allFutures.isEmpty()) {
            val result: NodeDataInfo?
            try {
                result = dependencyHandler.call()
            } catch (e: ExecutionException) {
                // Only thrown when calling Future.get, but none should be present if this is reached.
                throw java.lang.IllegalStateException("unexpected failure", e)
            } catch (e: IOException) {
                throw java.lang.IllegalStateException("unexpected failure", e)
            }
            return future.completeWith(result)
        }
        return future.completeWith(
            com.google.common.util.concurrent.Futures.whenAllComplete<Any?>(allFutures)
                .call<C?>(dependencyHandler, executor)
        )
    }

    /**
     * Accepts all the dependencies associated with a node, registers their serialization and waits
     * for processing to complete, signalled through the [.call] callback.
     * 
     * 
     * Once processing is complete and all keys are known, uploads the node value. [ ][.computeNodeBytes] defines the wire format of nodes.
     */
    internal inner class NodeDependencyHandler : java.util.concurrent.Callable<NodeDataInfo?> {
        private val fileKeys: java.util.ArrayList<String?> = java.util.ArrayList<String?>()
        private val listingKeys: java.util.ArrayList<String?> = java.util.ArrayList<String?>()
        private val nodeDependencies: java.util.ArrayList<NodeInvalidationDataInfo?> =
            java.util.ArrayList<NodeInvalidationDataInfo?>()
        private var sourceFileOrFuture: FileDataInfoOrFuture? = null

        private val writeStatusBuilder: SparseAggregateWriteStatusBuilder = SparseAggregateWriteStatusBuilder()

        private val futureFileDataInfo: java.util.ArrayList<FutureFileDataInfo> =
            java.util.ArrayList<FutureFileDataInfo>()
        private val futureListingDataInfo: java.util.ArrayList<FutureListingDataInfo> =
            java.util.ArrayList<FutureListingDataInfo>()
        private val futureNodeDataInfo: java.util.ArrayList<FutureNodeDataInfo> =
            java.util.ArrayList<FutureNodeDataInfo>()

        @Throws(ExecutionException::class, IOException::class)
        override fun call(): NodeDataInfo? {
            for (futureInfo in futureFileDataInfo) {
                addFileInfo(com.google.common.util.concurrent.Futures.getDone<V?>(futureInfo))
            }
            for (futureInfo in futureListingDataInfo) {
                addListingInfo(com.google.common.util.concurrent.Futures.getDone<V?>(futureInfo))
            }
            for (futureInfo in futureNodeDataInfo) {
                addNodeInfo(com.google.common.util.concurrent.Futures.getDone<V?>(futureInfo))
            }
            val sourceFileKey = this.sourceFileKey

            if (fileKeys.isEmpty() && listingKeys.isEmpty() && sourceFileKey == null) {
                if (nodeDependencies.isEmpty()) {
                    return ConstantNodeData.CONSTANT_NODE // None of the dependencies are relevant to invalidation.
                }
                // There are multiple ways that result could become unary here, even if `node` always has at
                // least 2 children. The following may reduce child count.
                // 1. Deduplication.
                // 2. Constant references.
                // 3. NestedFileOpNodes with the same fingerprints.
                if (nodeDependencies.size() == 1) {
                    // It ended up as a node wrapping another node. Discards the wrapper.
                    //
                    // TODO: b/364831651 - consider additional special casing for unary file or listing
                    // dependencies.
                    return nodeDependencies.get(0)
                }
            }

            // We need to deduplicate and sort these entries so that serialization is deterministic
            // and compact.
            val sortedFileKeys: MutableList<String?> = fileKeys.stream().sorted().distinct().toList()
            val sortedListingKeys: MutableList<String?> = listingKeys.stream().sorted().distinct().toList()
            val sortedNodeDependencies: MutableList<PackedFingerprint> =
                nodeDependencies.stream()
                    .map<PackedFingerprint?>(java.util.function.Function { obj: NodeInvalidationDataInfo? -> obj.cacheKey() })
                    .sorted()
                    .distinct()
                    .toList()

            val recorder: ProfileRecorder? =
                if (profileCollector == null) null else ProfileRecorder(profileCollector)
            if (recorder != null) {
                recorder.pushLocation(InvalidationEntryType.NODE)
                // We'll record the full entry size (including key) later once we have the key.
            }

            val nodeBytes =
                computeNodeBytes(
                    sortedNodeDependencies, sortedFileKeys, sortedListingKeys, sourceFileKey, recorder
                )
            var maybeCompressedBytes = nodeBytes
            if (maybeCompressedBytes.size >= COMPRESSION_NUM_BYTES_THRESHOLD) {
                maybeCompressedBytes = compressBytes(maybeCompressedBytes)
            }
            val key: PackedFingerprint = writer.fingerprint(maybeCompressedBytes)

            val keyByteCount: Long = key.toBytes().size.toLong()
            val valueByteCount =
                maybeCompressedBytes.size.toLong() // Don't hold on to the bytes any longer than needed

            counters.nodesWaitingForDeps.decrementAndGet()
            counters.nodesWaitingForUpload.incrementAndGet()
            counters.keyBytesWaitingForUpload.addAndGet(keyByteCount)
            counters.valueBytesWaitingForUpload.addAndGet(valueByteCount)

            val writeStatus: WriteStatus = writer.put(key, maybeCompressedBytes)
            writeStatus.addListener(
                java.lang.Runnable {
                    counters.nodesWaitingForUpload.decrementAndGet()
                    counters.nodesUploaded.incrementAndGet()
                    counters.keyBytesWaitingForUpload.addAndGet(-keyByteCount)
                    counters.valueBytesWaitingForUpload.addAndGet(-valueByteCount)
                    counters.keyBytesUploaded.addAndGet(keyByteCount)
                    counters.valueBytesUploaded.addAndGet(valueByteCount)
                },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )

            if (recorder != null) {
                if (maybeCompressedBytes.size != nodeBytes.size) {
                    recorder.setByteScale(maybeCompressedBytes.size.toDouble() / nodeBytes.size.toDouble())
                }
                recordKeyAndRegisterStatus(recorder, keyByteCount, valueByteCount, writeStatus)
            }

            writeStatusBuilder.add(writeStatus)
            return NodeInvalidationDataInfo(key, writeStatusBuilder.build())
        }

        private fun addFileKey(fileKey: com.google.devtools.build.lib.skyframe.FileKey?) {
            when (registerDependency(fileKey)) {
                -> addFileInfo(info)
                -> futureFileDataInfo.add(futureInfo)
            }
        }

        private fun addFileInfo(info: FileDataInfo) {
            when (info) {
                ConstantFileData.CONSTANT_FILE -> {}
                -> {
                    fileKeys.add(fileInfo.cacheKey())
                    writeStatusBuilder.add(fileInfo.writeStatus())
                }
            }
        }

        private fun addListingKey(listingKey: DirectoryListingKey?) {
            when (registerDependency(listingKey)) {
                -> addListingInfo(info)
                -> futureListingDataInfo.add(futureInfo)
            }
        }

        private fun addListingInfo(info: ListingDataInfo) {
            when (info) {
                ConstantListingData.CONSTANT_LISTING -> {}
                -> {
                    listingKeys.add(listingInfo.cacheKey())
                    writeStatusBuilder.add(listingInfo.writeStatus())
                }
            }
        }

        private fun addNodeKey(nestedKeys: AbstractNestedFileOpNodes) {
            when (registerDependency(nestedKeys)) {
                -> addNodeInfo(info)
                -> futureNodeDataInfo.add(futureInfo)
            }
        }

        private fun addNodeInfo(info: NodeDataInfo) {
            when (info) {
                ConstantNodeData.CONSTANT_NODE -> {}
                -> {
                    nodeDependencies.add(nodeInfo)
                    writeStatusBuilder.add(nodeInfo.writeStatus())
                }
            }
        }

        private fun setSourceFile(sourceFile: com.google.devtools.build.lib.skyframe.FileKey?) {
            com.google.common.base.Preconditions.checkState(
                sourceFileOrFuture == null,
                "Attempting to set source file to %s, but it was already set to %s",
                sourceFile,
                sourceFileOrFuture
            )
            this.sourceFileOrFuture = registerDependency(sourceFile)
        }

        private val combinedFutures: com.google.common.collect.ImmutableList<com.google.common.util.concurrent.ListenableFuture<*>?>
            get() {
                val combined: com.google.common.collect.ImmutableList.Builder<com.google.common.util.concurrent.ListenableFuture<*>?> =
                    com.google.common.collect.ImmutableList.builder<com.google.common.util.concurrent.ListenableFuture<*>?>()
                        .addAll(futureFileDataInfo)
                        .addAll(futureListingDataInfo)
                        .addAll(futureNodeDataInfo)
                when (sourceFileOrFuture) {
                    null -> {}
                    -> {}
                    -> combined.add(futureSource)
                }
                return combined.build()
            }

        @Throws(IOException::class)
        private fun compressBytes(nodeBytes: ByteArray?): ByteArray {
            val outputStream: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
            MagicBytes.writeMagicBytes(outputStream)
            getCompressedOutputStream(outputStream).use { compressedBytesStream ->
                compressedBytesStream.write(nodeBytes)
            }
            return outputStream.toByteArray()
        }

        @get:Throws(ExecutionException::class)
        private val sourceFileKey: String?
            get() {
                if (sourceFileOrFuture == null) {
                    return null
                }
                return when (when (sourceFileOrFuture) {
                    -> sourceInfo
                    -> com.google.common.util.concurrent.Futures.getDone<V?>(futureSourceInfo)
                }) {
                    ConstantFileData.CONSTANT_FILE -> null
                    -> {
                        writeStatusBuilder.add(fileInfo.writeStatus())
                        fileInfo.cacheKey()
                    }
                }
            }
    }

    @Throws(IOException::class)
    private fun getVersion(rootedPath: RootedPath, exists: Boolean): Long {
        return getVersion(rootedPath.asPath(), exists)
    }

    @Throws(IOException::class)
    private fun getVersion(path: com.google.devtools.build.lib.vfs.Path?, exists: Boolean): Long {
        return if (exists)
            versionGetter.getFilePathOrSymlinkVersion(path)
        else
            versionGetter.getNonexistentPathVersion(path)
    }

    private fun getKeyBytes(
        cacheKey: String,
        overflowConsumer: java.util.function.Consumer<String?>
    ): KeyBytesProvider {
        if (cacheKey.length() > FileDependencyKeySupport.MAX_KEY_LENGTH) {
            overflowConsumer.accept(cacheKey)
            return writer.fingerprint(cacheKey.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        }
        return com.google.devtools.build.lib.skyframe.serialization.StringKey(cacheKey)
    }

    companion object {
        @com.google.common.annotations.VisibleForTesting
        const val COMPRESSION_NUM_BYTES_THRESHOLD: Int = 580

        @Throws(IOException::class)
        fun getCompressedOutputStream(outputStream: java.io.OutputStream?): java.io.OutputStream {
            // The default level and the fastest level (-7) results in 35% and 19% wall time overhead when
            // not using a threshold to compress, the default level provided a 2x better compression. Since
            // we do use a threshold and there is no wall time regression, we favor the better compression
            // ratio.
            return ZstdOutputStream(outputStream, RecyclingBufferPool.INSTANCE)
        }

        /**
         * Computes a canonical byte representation of the node.
         * 
         * 
         * Logically, a node is a set of string file or listing keys, as described at [ ] and [DirectoryListingInvalidationData], respectively, and a set of
         * [NestedFileOpNodes] fingerprints. Its byte representation is specified as follows.
         * 
         * 
         *  1. The count of nested nodes, as a proto-encoded int.
         *  1. The count of file keys, as a proto-encoded int.
         *  1. The count of listing keys, as a proto-encoded int.
         *  1. A proto-encoded boolean, true if a source file key is present and false otherwise.
         *  1. Sorted and deduplicated, fingerprints of the [NestedFileOpNodes] byte
         * representations.
         *  1. Sorted and deduplicated, proto-encoded strings of the file keys.
         *  1. Sorted and deduplicated, proto-encoded strings of the listing keys.
         *  1. Proto-encoded string of the source file key, if applicable.
         * 
         * 
         * 
         * More compact formats are possible, but this reduces the complexity of the deserializer.
         */
        @com.google.common.annotations.VisibleForTesting
        fun computeNodeBytes(
            nodeDependencyFingerprints: MutableCollection<PackedFingerprint>,
            fileKeys: MutableCollection<String?>,
            listingKeys: MutableCollection<String?>,
            sourceFileKey: String?,
            recorder: ProfileRecorder?
        ): ByteArray {
            try {
                val bytesOut: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
                val codedOut: CodedOutputStream = CodedOutputStream.newInstance(bytesOut)

                if (recorder != null) {
                    recorder.pushLocation(EntryPart.VALUE)
                }
                val startValueBytes: Int = codedOut.getTotalBytesWritten()

                codedOut.writeInt32NoTag(nodeDependencyFingerprints.size())
                codedOut.writeInt32NoTag(fileKeys.size())
                codedOut.writeInt32NoTag(listingKeys.size())
                codedOut.writeBoolNoTag(sourceFileKey != null)

                val startNodeDependencyBytes: Int = codedOut.getTotalBytesWritten()
                for (fp in nodeDependencyFingerprints) {
                    fp.writeTo(codedOut)
                }
                if (recorder != null) {
                    recorder.pushLocation(NodeValuePart.NODE_DEPENDENCIES)
                    recorder.recordBytesAndPopLocation(startNodeDependencyBytes, codedOut)
                }

                val startFileKeyBytes: Int = codedOut.getTotalBytesWritten()
                for (key in fileKeys) {
                    codedOut.writeStringNoTag(key)
                }
                if (recorder != null) {
                    recorder.pushLocation(NodeValuePart.FILE_KEYS)
                    recorder.recordBytesAndPopLocation(startFileKeyBytes, codedOut)
                }

                val startListingKeyBytes: Int = codedOut.getTotalBytesWritten()
                for (key in listingKeys) {
                    codedOut.writeStringNoTag(key)
                }
                if (recorder != null) {
                    recorder.pushLocation(NodeValuePart.LISTING_KEYS)
                    recorder.recordBytesAndPopLocation(startListingKeyBytes, codedOut)
                }

                if (sourceFileKey != null) {
                    val startSourceFileBytes: Int = codedOut.getTotalBytesWritten()
                    codedOut.writeStringNoTag(sourceFileKey)
                    if (recorder != null) {
                        recorder.pushLocation(NodeValuePart.SOURCE_FILE)
                        recorder.recordBytesAndPopLocation(startSourceFileBytes, codedOut)
                    }
                }

                codedOut.flush()
                bytesOut.flush()
                if (recorder != null) {
                    // Records bytes and pops the EntryPart.VALUE.
                    recorder.recordBytesAndPopLocation(startValueBytes, codedOut)
                }

                return bytesOut.toByteArray()
            } catch (e: IOException) {
                throw java.lang.AssertionError("Unexpected IOException from ByteArrayOutputStream", e)
            }
        }

        private fun recordInvalidationProfile(
            profileCollector: ProfileCollector?,
            type: InvalidationEntryType?,
            keyByteCount: Long,
            valueByteCount: Long,
            writeStatus: WriteStatus?
        ) {
            val recorder: ProfileRecorder = ProfileRecorder(profileCollector)
            recorder.pushLocation(type)
            recorder.pushLocation(EntryPart.VALUE)
            recorder.recordBytes(valueByteCount.toInt())
            recorder.popLocation()
            recordKeyAndRegisterStatus(recorder, keyByteCount, valueByteCount, writeStatus)
        }

        private fun recordKeyAndRegisterStatus(
            recorder: ProfileRecorder, keyByteCount: Long, valueByteCount: Long, writeStatus: WriteStatus?
        ) {
            recorder.recordBytes((keyByteCount + valueByteCount).toInt())
            recorder.pushLocation(EntryPart.KEY)
            recorder.recordBytes(keyByteCount.toInt())
            recorder.popLocation() // Pop EntryPart.KEY.
            // EntryPart.VALUE is recorded already (e.g. by computeNodeBytes).
            recorder.popLocation() // Pop InvalidationType (FILE/LISTING/NODE)
            recorder.registerWriteStatus(writeStatus)
        }
    }
}
