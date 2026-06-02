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
package com.google.devtools.build.lib.remote

import build.bazel.remote.execution.v2.ActionResult

/**
 * Provides unified access to a disk cache, remote cache, or both.
 * 
 * 
 * The cache is reference counted. Initially, the reference count is 1. Use [.retain] to
 * increase and [.release] to decrease the reference count respectively. Once the reference
 * count is reached to 0, the underlying resources will be released (after pending I/O is finished).
 * 
 * 
 * Use [.awaitTermination] to wait for pending I/O to finish. Use [.shutdownNow]
 * to cancel all pending I/O and reject new requests.
 */
@ThreadSafety.ThreadSafe
open class CombinedCache(
    remoteCacheClient: RemoteCacheClient?,
    diskCacheClient: DiskCacheClient?,
    symlinkTemplate: String?,
    digestUtil: DigestUtil?,
    chunkingEnabled: Boolean
) : io.netty.util.AbstractReferenceCounted() {
    private val closeCountDownLatch: CountDownLatch = CountDownLatch(1)

    private val virtualThreadExecutor: com.google.common.util.concurrent.ListeningExecutorService =
        com.google.common.util.concurrent.MoreExecutors.listeningDecorator(
            Executors.newThreadPerTaskExecutor(
                java.lang.Thread.ofVirtual().name("combined-cache-", 0).factory()
            )
        )

    val remoteCacheClient: RemoteCacheClient?
    protected val diskCacheClient: DiskCacheClient?
    protected val symlinkTemplate: String?
    val digestUtil: DigestUtil?
    private val chunkingEnabled: Boolean

    // Delays the initialization of the chunking support logic until first use to avoid blocking on
    // a server capabilities check at construction time.
    private inner class Chunking {
        private var config: ChunkingConfig? = null
        private var downloader: ChunkedBlobDownloader? = null
        private var uploader: ChunkedBlobUploader? = null

        @kotlin.concurrent.Volatile
        private var initialized = false

        @Throws(IOException::class)
        fun supported(): Boolean {
            if (!chunkingEnabled) {
                return false
            }
            if (remoteCacheClient !is GrpcCacheClient) {
                return false
            }
            if (!initialized) {
                synchronized(this) {
                    config = ChunkingConfig.Companion.fromServerCapabilities(this.remoteServerCapabilities)
                    if (config != null) {
                        downloader = ChunkedBlobDownloader(remoteCacheClient, this@CombinedCache, digestUtil)
                        uploader = ChunkedBlobUploader(remoteCacheClient, this@CombinedCache, config, digestUtil)
                    }
                    initialized = true
                }
            }
            return config != null
        }

        fun config(): ChunkingConfig {
            return com.google.common.base.Preconditions.checkNotNull<ChunkingConfig>(
                config,
                "must not call config() unless supported() is true"
            )
        }

        fun downloader(): ChunkedBlobDownloader {
            return com.google.common.base.Preconditions.checkNotNull<ChunkedBlobDownloader>(
                downloader,
                "must not call downloader() unless supported() is true"
            )
        }

        fun uploader(): ChunkedBlobUploader {
            return com.google.common.base.Preconditions.checkNotNull<ChunkedBlobUploader>(
                uploader,
                "must not call uploader() unless supported() is true"
            )
        }
    }

    private val chunking = Chunking()

    init {
        com.google.common.base.Preconditions.checkArgument(
            remoteCacheClient != null || diskCacheClient != null,
            "remoteCacheClient and diskCacheClient cannot be null at the same time"
        )
        this.remoteCacheClient = remoteCacheClient
        this.diskCacheClient = diskCacheClient
        this.symlinkTemplate = symlinkTemplate
        this.digestUtil = digestUtil
        this.chunkingEnabled = chunkingEnabled
    }

    @get:Throws(IOException::class)
    open val remoteCacheCapabilities: CacheCapabilities
        get() = this.remoteServerCapabilities.getCacheCapabilities()

    val remoteAuthority: com.google.common.util.concurrent.ListenableFuture<String?>?
        get() {
            if (remoteCacheClient == null) {
                return com.google.common.util.concurrent.Futures.immediateFuture<String?>("")
            }
            return remoteCacheClient.getAuthority()
        }

    @get:Throws(IOException::class)
    val remoteServerCapabilities: ServerCapabilities?
        get() {
            if (remoteCacheClient == null) {
                return ServerCapabilities.getDefaultInstance()
            }
            return remoteCacheClient.getServerCapabilities()
        }

    /**
     * Class to keep track of which cache (disk or remote) a given [cached] ActionResult comes from.
     */
    class CachedActionResult(actionResult: ActionResult?, cacheName: String?) {
        val actionResult: ActionResult?
        val cacheName: String?

        init {
            this.actionResult = actionResult
            this.cacheName = cacheName
        }

        companion object {
            fun remote(actionResult: ActionResult?): CachedActionResult? {
                if (actionResult == null) {
                    return null
                }
                return CachedActionResult(actionResult, "remote")
            }

            fun disk(actionResult: ActionResult?): CachedActionResult? {
                if (actionResult == null) {
                    return null
                }
                return CachedActionResult(actionResult, "disk")
            }
        }
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun downloadActionResult(
        context: RemoteActionExecutionContext,
        actionKey: ActionKey?,
        inlineOutErr: Boolean,
        inlineOutputFiles: MutableSet<String?>?
    ): CachedActionResult? {
        return com.google.devtools.build.lib.remote.util.Utils.getFromFuture<CachedActionResult?>(
            downloadActionResultAsync(context, actionKey, inlineOutErr, inlineOutputFiles)
        )
    }

    fun downloadActionResultAsync(
        context: RemoteActionExecutionContext,
        actionKey: ActionKey?,
        inlineOutErr: Boolean,
        inlineOutputFiles: MutableSet<String?>?
    ): com.google.common.util.concurrent.ListenableFuture<CachedActionResult?> {
        val spawnExecutionContext: SpawnExecutionContext? = context.getSpawnExecutionContext()

        var future: com.google.common.util.concurrent.ListenableFuture<CachedActionResult?> =
            com.google.common.util.concurrent.Futures.immediateFuture<CachedActionResult?>(null)

        if (diskCacheClient != null && context.getReadCachePolicy().allowDiskCache()) {
            // If Build without the Bytes is enabled, the future will likely return null
            // and fallback to remote cache because AC integrity check is enabled and referenced blobs are
            // probably missing from disk cache due to BwoB.
            //
            // TODO(chiwang): With lease service, instead of doing the integrity check against local
            // filesystem, we can check whether referenced blobs are alive in the lease service to
            // increase the cache-hit rate for disk cache.
            if (spawnExecutionContext != null) {
                spawnExecutionContext.report(SPAWN_CHECKING_DISK_CACHE_EVENT)
            }
            future =
                com.google.common.util.concurrent.Futures.transform<ActionResult?, CachedActionResult?>(
                    diskCacheClient.downloadActionResult(actionKey),
                    com.google.common.base.Function { actionResult: ActionResult? ->
                        CachedActionResult.Companion.disk(
                            actionResult
                        )
                    },
                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                )
        }

        if (remoteCacheClient != null && context.getReadCachePolicy().allowRemoteCache()) {
            future =
                com.google.common.util.concurrent.Futures.transformAsync<CachedActionResult?, CachedActionResult?>(
                    future,
                    com.google.common.util.concurrent.AsyncFunction { result: CachedActionResult? ->
                        if (result == null) {
                            if (spawnExecutionContext != null) {
                                spawnExecutionContext.report(SPAWN_CHECKING_REMOTE_CACHE_EVENT)
                            }
                            return@transformAsync com.google.common.util.concurrent.Futures.transform<ActionResult?, CachedActionResult?>(
                                downloadActionResultFromRemote(
                                    context, actionKey, inlineOutErr, inlineOutputFiles
                                ),
                                com.google.common.base.Function { actionResult: ActionResult? ->
                                    CachedActionResult.Companion.remote(
                                        actionResult
                                    )
                                },
                                com.google.common.util.concurrent.MoreExecutors.directExecutor()
                            )
                        } else {
                            return@transformAsync com.google.common.util.concurrent.Futures.immediateFuture<CachedActionResult?>(
                                result
                            )
                        }
                    },
                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                )
        }

        return future
    }

    private fun downloadActionResultFromRemote(
        context: RemoteActionExecutionContext,
        actionKey: ActionKey?,
        inlineOutErr: Boolean,
        inlineOutputFiles: MutableSet<String?>?
    ): com.google.common.util.concurrent.ListenableFuture<ActionResult?> {
        com.google.common.base.Preconditions.checkState(
            remoteCacheClient != null && context.getReadCachePolicy().allowRemoteCache()
        )
        return com.google.common.util.concurrent.Futures.transformAsync<ActionResult?, ActionResult?>(
            remoteCacheClient.downloadActionResult(context, actionKey, inlineOutErr, inlineOutputFiles),
            com.google.common.util.concurrent.AsyncFunction { actionResult: ActionResult? ->
                if (actionResult == null) {
                    return@transformAsync com.google.common.util.concurrent.Futures.immediateFuture<ActionResult?>(null)
                }
                if (diskCacheClient != null && context.getWriteCachePolicy().allowDiskCache()) {
                    return@transformAsync com.google.common.util.concurrent.Futures.transform<java.lang.Void?, ActionResult?>(
                        diskCacheClient.uploadActionResult(actionKey, actionResult),
                        com.google.common.base.Function { v: java.lang.Void? -> actionResult },
                        com.google.common.util.concurrent.MoreExecutors.directExecutor()
                    )
                }
                com.google.common.util.concurrent.Futures.immediateFuture<ActionResult?>(actionResult)
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
    }

    /**
     * Returns a set of digests that the remote cache does not know about. The returned set is
     * guaranteed to be a subset of `digests`.
     */
    fun findMissingDigests(
        context: RemoteActionExecutionContext, digests: Iterable<Digest?>
    ): com.google.common.util.concurrent.ListenableFuture<com.google.common.collect.ImmutableSet<Digest?>?> {
        if (com.google.common.collect.Iterables.isEmpty(digests)) {
            return com.google.common.util.concurrent.Futures.immediateFuture<com.google.common.collect.ImmutableSet<Digest?>?>(
                com.google.common.collect.ImmutableSet.of<Digest?>()
            )
        }

        var diskQuery: com.google.common.util.concurrent.ListenableFuture<com.google.common.collect.ImmutableSet<Digest?>?>? =
            com.google.common.util.concurrent.Futures.immediateFuture<com.google.common.collect.ImmutableSet<Digest?>?>(
                com.google.common.collect.ImmutableSet.of<Digest?>()
            )
        if (diskCacheClient != null && context.getWriteCachePolicy().allowDiskCache()) {
            diskQuery = diskCacheClient.findMissingDigests(digests)
        }

        var remoteQuery: com.google.common.util.concurrent.ListenableFuture<com.google.common.collect.ImmutableSet<Digest?>?> =
            com.google.common.util.concurrent.Futures.immediateFuture<com.google.common.collect.ImmutableSet<Digest?>?>(
                com.google.common.collect.ImmutableSet.of<Digest?>()
            )
        if (remoteCacheClient != null && context.getWriteCachePolicy().allowRemoteCache()) {
            remoteQuery = remoteCacheClient.findMissingDigests(context, digests)
        }

        val diskQueryFinal: com.google.common.util.concurrent.ListenableFuture<com.google.common.collect.ImmutableSet<Digest?>?>? =
            diskQuery
        val remoteQueryFinal: com.google.common.util.concurrent.ListenableFuture<com.google.common.collect.ImmutableSet<Digest?>?> =
            remoteQuery

        return com.google.common.util.concurrent.Futures.whenAllSucceed<com.google.common.collect.ImmutableSet<Digest?>?>(
            remoteQueryFinal,
            diskQueryFinal
        )
            .call<com.google.common.collect.ImmutableSet<Digest?>?>(
                java.util.concurrent.Callable {
                    com.google.common.collect.ImmutableSet.builder<Digest?>()
                        .addAll(remoteQueryFinal.get())
                        .addAll(diskQueryFinal.get())
                        .build()
                },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
    }

    /** Returns whether the remote action cache supports updating action results.  */
    fun remoteActionCacheSupportsUpdate(): Boolean {
        try {
            return this.remoteCacheCapabilities.getActionCacheUpdateCapabilities().getUpdateEnabled()
        } catch (ignored: IOException) {
            return false
        }
    }

    /** Upload the action result to the remote cache.  */
    fun uploadActionResult(
        context: RemoteActionExecutionContext, actionKey: ActionKey?, actionResult: ActionResult?
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        var diskCacheFuture: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.Futures.immediateVoidFuture()
        if (diskCacheClient != null && context.getWriteCachePolicy().allowDiskCache()) {
            diskCacheFuture = diskCacheClient.uploadActionResult(actionKey, actionResult)
        }

        var remoteCacheFuture: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>? =
            com.google.common.util.concurrent.Futures.immediateVoidFuture()
        if (remoteCacheClient != null && context.getWriteCachePolicy().allowRemoteCache()) {
            remoteCacheFuture = remoteCacheClient.uploadActionResult(context, actionKey, actionResult)
        }

        return com.google.common.util.concurrent.Futures.whenAllSucceed<java.lang.Void?>(
            diskCacheFuture,
            remoteCacheFuture
        )
            .call<java.lang.Void?>(
                java.util.concurrent.Callable { null },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
    }

    /**
     * Upload a local file to the remote cache.
     * 
     * 
     * Trying to upload the same file multiple times concurrently, results in only one upload being
     * performed.
     * 
     * @param context the context for the action.
     * @param digest the digest of the file.
     * @param file the file to upload.
     */
    fun uploadFile(
        context: RemoteActionExecutionContext, digest: Digest, file: com.google.devtools.build.lib.vfs.Path
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        if (digest.getSizeBytes() === 0) {
            return COMPLETED_SUCCESS
        }

        var diskCacheFuture: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.Futures.immediateVoidFuture()
        if (diskCacheClient != null && context.getWriteCachePolicy().allowDiskCache()) {
            diskCacheFuture = diskCacheClient.uploadFile(digest, file)
        }

        val chunkingSupported: Boolean
        try {
            chunkingSupported = chunking.supported()
        } catch (e: IOException) {
            return com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(e)
        }

        var remoteCacheFuture: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.Futures.immediateVoidFuture()
        if (remoteCacheClient != null && context.getWriteCachePolicy().allowRemoteCache()) {
            if (chunkingSupported && digest.getSizeBytes() > chunking.config().chunkingThreshold()) {
                remoteCacheFuture =
                    remoteCacheClient.dedupUpload(
                        digest,
                        io.reactivex.rxjava3.functions.Supplier { uploadChunked(context, digest, file) },  /* force= */
                        false
                    )
            } else {
                remoteCacheFuture = remoteCacheClient.uploadFile(context, digest, file,  /* force= */false)
            }
        }

        return com.google.common.util.concurrent.Futures.whenAllSucceed<java.lang.Void?>(
            diskCacheFuture,
            remoteCacheFuture
        )
            .call<java.lang.Void?>(
                java.util.concurrent.Callable { null },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
    }

    private fun uploadChunked(
        context: RemoteActionExecutionContext?, digest: Digest?, file: com.google.devtools.build.lib.vfs.Path
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        return virtualThreadExecutor.submit<java.lang.Void?>(
            java.util.concurrent.Callable {
                chunking.uploader().uploadChunked(context, digest, file)
                null
            })
    }

    /**
     * Uploads a sequence of bytes to the cache.
     * 
     * 
     * Trying to upload the same BLOB multiple times concurrently, results in only one upload being
     * performed.
     * 
     * @param context the context for the action.
     * @param digest the digest of the BLOB.
     * @param data the BLOB to upload.
     */
    fun uploadBlob(
        context: RemoteActionExecutionContext, digest: Digest, data: ByteString
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        return uploadBlob(
            context,
            digest,
            com.google.devtools.build.lib.remote.common.RemoteCacheClient.Blob { data.newInput() } as com.google.devtools.build.lib.remote.common.RemoteCacheClient.Blob)
    }

    /**
     * Uploads a blob to the cache from a repeatable stream supplier.
     * 
     * 
     * The supplier may be opened more than once, including concurrently when both disk and remote
     * cache writes are enabled.
     */
    fun uploadBlob(
        context: RemoteActionExecutionContext,
        digest: Digest,
        blob: com.google.devtools.build.lib.remote.common.RemoteCacheClient.Blob?
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        if (digest.getSizeBytes() === 0) {
            return COMPLETED_SUCCESS
        }

        var diskCacheFuture: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.Futures.immediateVoidFuture()
        if (diskCacheClient != null && context.getWriteCachePolicy().allowDiskCache()) {
            diskCacheFuture = diskCacheClient.uploadBlob(digest, blob)
        }

        var remoteCacheFuture: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.Futures.immediateVoidFuture()
        if (remoteCacheClient != null && context.getWriteCachePolicy().allowRemoteCache()) {
            remoteCacheFuture = remoteCacheClient.uploadBlob(context, digest, blob,  /* force= */false)
        }

        return com.google.common.util.concurrent.Futures.whenAllSucceed<java.lang.Void?>(
            diskCacheFuture,
            remoteCacheFuture
        )
            .call<java.lang.Void?>(
                java.util.concurrent.Callable { null },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
    }

    // Only for use by tests and the remote executor implementation.
    open fun downloadBlob(
        context: RemoteActionExecutionContext, digest: Digest
    ): com.google.common.util.concurrent.ListenableFuture<ByteArray?> {
        return downloadBlob(context,  /* blobName= */"",  /* execPath= */null, digest)
    }

    /**
     * Downloads a blob with content hash `digest` and stores its content in memory.
     * 
     * @return a future that completes after the download completes (succeeds / fails). If successful,
     * the content is stored in the future's `byte[]`.
     */
    fun downloadBlob(
        context: RemoteActionExecutionContext,
        blobName: String?,
        execPath: PathFragment?,
        digest: Digest
    ): com.google.common.util.concurrent.ListenableFuture<ByteArray?> {
        if (digest.getSizeBytes() === 0) {
            return EMPTY_BYTES
        }
        val bOut: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream(digest.getSizeBytes() as Int)
        val download: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
            downloadBlob(context, blobName, execPath, digest, bOut)
        return com.google.common.util.concurrent.Futures.transform<java.lang.Void?, ByteArray?>(
            download,
            com.google.common.base.Function { v: java.lang.Void? -> bOut.toByteArray() },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
    }

    private fun downloadBlob(
        context: RemoteActionExecutionContext,
        blobName: String?,
        execPath: PathFragment?,
        digest: Digest,
        out: java.io.OutputStream?
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        if (digest.getSizeBytes() === 0) {
            return COMPLETED_SUCCESS
        }
        val future: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
            downloadBlob(context, digest, out)
        return com.google.common.util.concurrent.Futures.catchingAsync<java.lang.Void?, CacheNotFoundException?>(
            future,
            CacheNotFoundException::class.java,
            com.google.common.util.concurrent.AsyncFunction { cacheNotFoundException: CacheNotFoundException? ->
                cacheNotFoundException.setExecPath(execPath)
                cacheNotFoundException.setFilename(blobName)
                com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(cacheNotFoundException)
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
    }

    fun downloadBlob(
        context: RemoteActionExecutionContext, digest: Digest, out: java.io.OutputStream?
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        var future: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(
                CacheNotFoundException(digest)
            )

        if (diskCacheClient != null && context.getReadCachePolicy().allowDiskCache()) {
            future = diskCacheClient.downloadBlob(digest, out)
        }

        if (remoteCacheClient != null && context.getReadCachePolicy().allowRemoteCache()) {
            future =
                com.google.common.util.concurrent.Futures.catchingAsync<java.lang.Void?, CacheNotFoundException?>(
                    future,
                    CacheNotFoundException::class.java,
                    com.google.common.util.concurrent.AsyncFunction { unused: CacheNotFoundException? ->
                        downloadBlobFromRemote(
                            context,
                            digest,
                            out
                        )
                    },
                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                )
        }

        return future
    }

    private fun downloadBlobFromRemote(
        context: RemoteActionExecutionContext, digest: Digest, out: java.io.OutputStream?
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        com.google.common.base.Preconditions.checkState(
            remoteCacheClient != null && context.getReadCachePolicy().allowRemoteCache()
        )

        val chunkingSupported: Boolean
        try {
            chunkingSupported = chunking.supported()
        } catch (e: IOException) {
            return com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(e)
        }

        if (chunkingSupported && digest.getSizeBytes() > chunking.config().chunkingThreshold()) {
            val chunkedDownloadFuture: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
                virtualThreadExecutor.submit<java.lang.Void?>(
                    java.util.concurrent.Callable {
                        chunking.downloader().downloadChunked(context, digest, out)
                        null
                    })
            return com.google.common.util.concurrent.Futures.catchingAsync<java.lang.Void?, CacheNotFoundException?>(
                chunkedDownloadFuture,
                CacheNotFoundException::class.java,
                com.google.common.util.concurrent.AsyncFunction { e: CacheNotFoundException? ->
                    regularDownloadBlobFromRemote(
                        context,
                        digest,
                        out
                    )
                },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        }

        return regularDownloadBlobFromRemote(context, digest, out)
    }

    private fun regularDownloadBlobFromRemote(
        context: RemoteActionExecutionContext, digest: Digest?, out: java.io.OutputStream?
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        com.google.common.base.Preconditions.checkState(
            remoteCacheClient != null && context.getReadCachePolicy().allowRemoteCache()
        )

        if (diskCacheClient != null && context.getWriteCachePolicy().allowDiskCache()) {
            val tempPath: com.google.devtools.build.lib.vfs.Path = diskCacheClient.getTempPath()
            val tempOut: LazyFileOutputStream = LazyFileOutputStream(tempPath)
            val download: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
                remoteCacheClient.downloadBlob(context, digest, tempOut)
            return cleanupTempFileOnError(
                com.google.common.util.concurrent.Futures.transformAsync<java.lang.Void?, java.lang.Void?>(
                    download,
                    com.google.common.util.concurrent.AsyncFunction { unused: java.lang.Void? ->
                        try {
                            // Fsync temp before we rename it to avoid data loss in the case of machine
                            // crashes (the OS may reorder the writes and the rename).
                            tempOut.syncIfPossible()
                            tempOut.close()
                            diskCacheClient.captureFile(
                                tempPath,
                                digest,
                                com.google.devtools.build.lib.remote.Store.CAS
                            )
                        } catch (e: IOException) {
                            return@transformAsync com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(
                                e
                            )
                        }
                        diskCacheClient.downloadBlob(digest, out)
                    },
                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                ),
                tempPath,
                tempOut
            )
        }

        return remoteCacheClient.downloadBlob(context, digest, out)
    }

    /** A reporter that reports download progresses.  */
    class DownloadProgressReporter(
        private val includeFile: Boolean,
        listener: ProgressStatusListener,
        file: String?,
        totalSize: Long
    ) {
        private val listener: ProgressStatusListener
        private val id: String?
        private val file: String?
        private val totalSize: String?
        private val downloadedBytes: AtomicLong = AtomicLong(0)

        constructor(listener: ProgressStatusListener, file: String?, totalSize: Long) : this( /* includeFile= */true,
            listener,
            file,
            totalSize
        )

        init {
            this.listener = listener
            this.id = file
            this.totalSize = com.google.devtools.build.lib.util.StringUtilities.bytesCountToDisplayString(totalSize)

            val matcher: java.util.regex.Matcher = PATTERN.matcher(file)
            this.file = matcher.replaceFirst("")
        }

        fun started() {
            reportProgress(false, false)
        }

        fun downloadedBytes(count: Int) {
            downloadedBytes.addAndGet(count.toLong())
            reportProgress(true, false)
        }

        fun finished() {
            reportProgress(true, true)
        }

        private fun reportProgress(includeBytes: Boolean, finished: Boolean) {
            val progress: String?
            if (includeBytes) {
                if (includeFile) {
                    progress =
                        java.lang.String.format(
                            "Downloading %s, %s / %s",
                            file,
                            com.google.devtools.build.lib.util.StringUtilities.bytesCountToDisplayString(downloadedBytes.get()),
                            totalSize
                        )
                } else {
                    progress =
                        java.lang.String.format(
                            "%s / %s",
                            com.google.devtools.build.lib.util.StringUtilities.bytesCountToDisplayString(downloadedBytes.get()),
                            totalSize
                        )
                }
            } else {
                if (includeFile) {
                    progress = java.lang.String.format("Downloading %s", file)
                } else {
                    progress = ""
                }
            }
            listener.onProgressStatus(SpawnProgressEvent.create(id, progress, finished))
        }

        companion object {
            private val PATTERN: java.util.regex.Pattern = java.util.regex.Pattern.compile("^bazel-out/[^/]+/[^/]+/")
        }
    }

    @Throws(IOException::class)
    fun downloadFile(
        context: RemoteActionExecutionContext,
        outputPath: String?,
        execPath: PathFragment?,
        localPath: com.google.devtools.build.lib.vfs.Path,
        digest: Digest,
        reporter: DownloadProgressReporter
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        val f: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
            downloadFile(context, localPath, digest, reporter)
        return com.google.common.util.concurrent.Futures.catchingAsync<java.lang.Void?, Throwable?>(
            f,
            Throwable::class.java,
            com.google.common.util.concurrent.AsyncFunction { throwable: Throwable? ->
                if (throwable is CacheNotFoundException) {
                    throwable.setExecPath(execPath)
                    throwable.setFilename(outputPath)
                } else if (throwable is OutputDigestMismatchException) {
                    throwable.setOutputPath(outputPath)
                    throwable.setLocalPath(localPath)
                }
                com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(throwable)
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
    }

    /**
     * Downloads a file (that is not a directory). The content is fetched from the digest.
     * 
     * 
     * Use [.downloadFile] instead for build outputs as it provides progress information and
     * correctly handles unexpected cache misses.
     */
    @Throws(IOException::class)
    fun downloadFile(
        context: RemoteActionExecutionContext, path: com.google.devtools.build.lib.vfs.Path, digest: Digest
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        return downloadFile(
            context,
            path.getPathString(),  /* execPath= */
            null,
            path,
            digest,
            DownloadProgressReporter(ProgressStatusListener.Companion.NO_ACTION, "", 0)
        )
    }

    /** Downloads a file (that is not a directory). The content is fetched from the digest.  */
    @Throws(IOException::class)
    private fun downloadFile(
        context: RemoteActionExecutionContext,
        path: com.google.devtools.build.lib.vfs.Path,
        digest: Digest,
        reporter: DownloadProgressReporter
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path?>(path.getParentDirectory())
            .createDirectoryAndParents()
        if (digest.getSizeBytes() === 0) {
            // Handle empty file locally.
            com.google.devtools.build.lib.vfs.FileSystemUtils.writeContent(path, ByteArray(0))
            return COMPLETED_SUCCESS
        }

        if (symlinkTemplate != null) {
            // Don't actually download files from the CAS. Instead, create a
            // symbolic link that points to a location where CAS objects may
            // be found. This could, for example, be a FUSE file system.
            path.createSymbolicLink(
                path.getRelative(
                    symlinkTemplate
                        .replace("{hash}", digest.getHash())
                        .replace("{size_bytes}", java.lang.String.valueOf(digest.getSizeBytes()))
                )
            )
            return COMPLETED_SUCCESS
        }

        reporter.started()
        val out: java.io.OutputStream = ReportingOutputStream(LazyFileOutputStream(path), reporter)

        val f: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> = downloadBlob(context, digest, out)
        f.addListener(
            java.lang.Runnable {
                try {
                    out.close()
                } catch (e: IOException) {
                    logger.atWarning().withCause(e).log(
                        "Unexpected exception closing output stream after downloading %s/%d to %s",
                        digest.getHash(), digest.getSizeBytes(), path
                    )
                } finally {
                    reporter.finished()
                }
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
        return f
    }

    /**
     * Download the stdout and stderr of an executed action.
     * 
     * @param context the context for the action.
     * @param result the result of the action.
     * @param outErr the [OutErr] that the stdout and stderr will be downloaded to.
     */
    fun downloadOutErr(
        context: RemoteActionExecutionContext, result: ActionResult, outErr: OutErr
    ): MutableList<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?> {
        val downloads: MutableList<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?> =
            java.util.ArrayList<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>()
        if (!result.getStdoutRaw().isEmpty()) {
            try {
                result.getStdoutRaw().writeTo(outErr.getOutputStream())
                outErr.getOutputStream().flush()
            } catch (e: IOException) {
                downloads.add(com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(e))
            }
        } else if (result.hasStdoutDigest()) {
            downloads.add(
                downloadBlob(
                    context,  /* blobName= */
                    "<stdout>",  /* execPath= */
                    null,
                    result.getStdoutDigest(),
                    outErr.getOutputStream()
                )
            )
        }
        if (!result.getStderrRaw().isEmpty()) {
            try {
                result.getStderrRaw().writeTo(outErr.getErrorStream())
                outErr.getErrorStream().flush()
            } catch (e: IOException) {
                downloads.add(com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(e))
            }
        } else if (result.hasStderrDigest()) {
            downloads.add(
                downloadBlob(
                    context,  /* blobName= */
                    "<stderr>",  /* execPath= */
                    null,
                    result.getStderrDigest(),
                    outErr.getErrorStream()
                )
            )
        }
        return downloads
    }

    fun hasRemoteCache(): Boolean {
        return remoteCacheClient != null
    }

    fun hasDiskCache(): Boolean {
        return diskCacheClient != null
    }

    override fun deallocate() {
        if (diskCacheClient != null) {
            diskCacheClient.close()
        }
        virtualThreadExecutor.shutdown()
        if (remoteCacheClient != null) {
            remoteCacheClient.shutdownUploads()
            remoteCacheClient.close()
        }

        closeCountDownLatch.countDown()
    }

    override fun touch(o: Any?): CombinedCache {
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    override fun retain(): CombinedCache {
        super.retain()
        return this
    }

    /** Waits for active network I/Os to finish.  */
    @Throws(java.lang.InterruptedException::class)
    fun awaitTermination() {
        if (remoteCacheClient != null) {
            remoteCacheClient.awaitUploadTermination()
        }
        closeCountDownLatch.await()
        virtualThreadExecutor.awaitTermination(java.lang.Long.MAX_VALUE, TimeUnit.NANOSECONDS)
    }

    /** Shuts the cache down and cancels active network I/Os.  */
    fun shutdownNow() {
        if (remoteCacheClient != null) {
            remoteCacheClient.shutdownUploadsNow()
        }
        virtualThreadExecutor.shutdownNow()
    }

    /**
     * An [OutputStream] that reports all the write operations with [ ].
     */
    private class ReportingOutputStream(out: java.io.OutputStream, reporter: DownloadProgressReporter) :
        java.io.OutputStream(), MaybePathBacked {
        private val out: java.io.OutputStream
        private val reporter: DownloadProgressReporter

        init {
            this.out = out
            this.reporter = reporter
        }

        @Throws(IOException::class)
        override fun write(b: ByteArray) {
            out.write(b)
            reporter.downloadedBytes(b.size)
        }

        @Throws(IOException::class)
        override fun write(b: ByteArray?, off: Int, len: Int) {
            out.write(b, off, len)
            reporter.downloadedBytes(len)
        }

        @Throws(IOException::class)
        override fun write(b: Int) {
            out.write(b)
            reporter.downloadedBytes(1)
        }

        @Throws(IOException::class)
        override fun flush() {
            out.flush()
        }

        @Throws(IOException::class)
        override fun close() {
            out.close()
        }

        override fun maybeGetPath(): com.google.devtools.build.lib.vfs.Path? {
            return if (out is MaybePathBacked) out.maybeGetPath() else null
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private val COMPLETED_SUCCESS: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.Futures.immediateFuture<java.lang.Void?>(null)
        private val EMPTY_BYTES: com.google.common.util.concurrent.ListenableFuture<ByteArray?> =
            com.google.common.util.concurrent.Futures.immediateFuture<ByteArray?>(ByteArray(0))
        private val SPAWN_CHECKING_DISK_CACHE_EVENT: SpawnCheckingCacheEvent? =
            SpawnCheckingCacheEvent.create("disk-cache")
        private val SPAWN_CHECKING_REMOTE_CACHE_EVENT: SpawnCheckingCacheEvent? =
            SpawnCheckingCacheEvent.create("remote-cache")

        private fun cleanupTempFileOnError(
            f: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>,
            tempPath: com.google.devtools.build.lib.vfs.Path,
            tempOut: java.io.OutputStream
        ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
            return com.google.common.util.concurrent.Futures.catchingAsync<java.lang.Void?, java.lang.Exception?>(
                f,
                java.lang.Exception::class.java,
                com.google.common.util.concurrent.AsyncFunction { rootCause: java.lang.Exception? ->
                    try {
                        tempOut.close()
                    } catch (e: IOException) {
                        rootCause.addSuppressed(e)
                    }
                    try {
                        tempPath.delete()
                    } catch (e: IOException) {
                        rootCause.addSuppressed(e)
                    }
                    com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(rootCause)
                },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        }

        fun createFailureDetail(message: String?, detailedCode: Code?): FailureDetail {
            return FailureDetail.newBuilder()
                .setMessage(message)
                .setRemoteExecution(RemoteExecution.newBuilder().setCode(detailedCode))
                .build()
        }
    }
}
