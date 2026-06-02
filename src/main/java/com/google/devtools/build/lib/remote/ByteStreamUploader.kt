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

import build.bazel.remote.execution.v2.Digest

/**
 * A client implementing the `Write` method of the `ByteStream` gRPC service.
 * 
 * 
 * The uploader supports reference counting to easily be shared between components with different
 * lifecyles. After instantiation the reference count is `1`.
 * 
 * 
 * See [ReferenceCounted] for more information on reference counting.
 */
internal class ByteStreamUploader(
    instanceName: String?,
    channel: ReferenceCountedChannel,
    callCredentialsProvider: CallCredentialsProvider,
    callTimeoutSecs: Long,
    retrier: RemoteRetrier,
    maximumOpenFiles: Int,
    digestFunction: DigestFunction.Value
) {
    private val instanceName: String?
    private val channel: ReferenceCountedChannel
    private val callCredentialsProvider: CallCredentialsProvider
    private val callTimeoutSecs: Long
    private val retrier: RemoteRetrier
    private val digestFunction: DigestFunction.Value
    private val queryWriteStatusImplemented: AtomicBoolean = AtomicBoolean(true)

    private val openedFilePermits: Semaphore?

    /**
     * Creates a new instance.
     * 
     * @param instanceName the instance name to be prepended to resource name of the `Write`
     * call. See the `ByteStream` service definition for details
     * @param channel the [io.grpc.Channel] to use for calls
     * @param callCredentialsProvider the credentials provider to use for authentication.
     * @param callTimeoutSecs the timeout in seconds after which a `Write` gRPC call must be
     * complete. The timeout resets between retries
     * @param retrier the [RemoteRetrier] whose backoff strategy to use for retry timings.
     */
    init {
        com.google.common.base.Preconditions.checkArgument(callTimeoutSecs > 0, "callTimeoutSecs must be gt 0.")
        this.instanceName = instanceName
        this.channel = channel
        this.callCredentialsProvider = callCredentialsProvider
        this.callTimeoutSecs = callTimeoutSecs
        this.retrier = retrier
        this.openedFilePermits = if (maximumOpenFiles != -1) Semaphore(maximumOpenFiles) else null
        this.digestFunction = digestFunction
    }

    /**
     * Uploads a BLOB asynchronously to the remote `ByteStream` service. The call returns
     * immediately and one can listen to the returned future for the success/failure of the upload.
     * 
     * 
     * Uploads are retried according to the specified [RemoteRetrier]. Retrying is
     * transparent to the user of this API.
     * 
     * 
     * Trying to upload the same BLOB multiple times concurrently, results in only one upload being
     * performed. This is transparent to the user of this API.
     * 
     * @param digest the [Digest] of the data to upload.
     * @param chunker the data to upload. Callers are responsible for closing the [Chunker].
     */
    fun uploadBlobAsync(
        context: RemoteActionExecutionContext, digest: Digest, chunker: Chunker
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        return com.google.common.util.concurrent.Futures.catchingAsync<java.lang.Void?, StatusRuntimeException?>(
            startAsyncUpload(context, digest, chunker),
            StatusRuntimeException::class.java,
            com.google.common.util.concurrent.AsyncFunction { sre: StatusRuntimeException? ->
                com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(
                    IOException(sre)
                )
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
    }

    private fun buildUploadResourceName(
        instanceName: String?, uuid: UUID?, digest: Digest, compressed: Boolean
    ): String? {
        var resourceName: String?

        if (DigestUtil.isOldStyleDigestFunction(digestFunction)) {
            val template =
                if (compressed) "uploads/%s/compressed-blobs/zstd/%s/%d" else "uploads/%s/blobs/%s/%d"
            resourceName = format(template, uuid, digest.getHash(), digest.getSizeBytes())
        } else {
            val template =
                if (compressed) "uploads/%s/compressed-blobs/zstd/%s/%s/%d" else "uploads/%s/blobs/%s/%s/%d"
            resourceName =
                format(
                    template,
                    uuid,
                    com.google.common.base.Ascii.toLowerCase(digestFunction.getValueDescriptor().getName()),
                    digest.getHash(),
                    digest.getSizeBytes()
                )
        }
        if (!com.google.common.base.Strings.isNullOrEmpty(instanceName)) {
            resourceName = instanceName + "/" + resourceName
        }
        return resourceName
    }

    /** Starts a file upload and returns a future representing the upload.  */
    private fun startAsyncUpload(
        context: RemoteActionExecutionContext, digest: Digest, chunker: Chunker
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        try {
            chunker.reset()
        } catch (e: IOException) {
            return com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(e)
        }

        if (chunker.getUncompressedSize() != digest.getSizeBytes()) {
            return com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(
                java.lang.IllegalStateException(
                    java.lang.String.format(
                        "Expected chunker size of %d, got %d",
                        digest.getSizeBytes(), chunker.getUncompressedSize()
                    )
                )
            )
        }

        val uploadId: UUID = UUID.randomUUID()
        val resourceName =
            buildUploadResourceName(instanceName, uploadId, digest, chunker.isCompressed())
        if (openedFilePermits != null) {
            try {
                openedFilePermits.acquire()
            } catch (e: java.lang.InterruptedException) {
                return com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(
                    java.lang.InterruptedException(
                        "Unexpected interrupt while acquiring open file permit. Original error message: "
                                + e.getMessage()
                    )
                )
            }
        }
        val newUpload =
            AsyncUpload(
                context,
                channel,
                callCredentialsProvider,
                callTimeoutSecs,
                retrier,
                resourceName,
                chunker
            )
        val currUpload: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> = newUpload.start()
        currUpload.addListener(
            java.lang.Runnable {
                if (openedFilePermits != null) {
                    openedFilePermits.release()
                }
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
        return currUpload
    }

    /**
     * Signal that the blob already exists on the server, so upload should complete early but
     * successfully.
     */
    private class AlreadyExists : java.lang.Exception()

    private inner class AsyncUpload(
        context: RemoteActionExecutionContext,
        channel: ReferenceCountedChannel,
        callCredentialsProvider: CallCredentialsProvider,
        callTimeoutSecs: Long,
        retrier: Retrier,
        resourceName: String?,
        chunker: Chunker
    ) : com.google.common.util.concurrent.AsyncCallable<Long?> {
        private val context: RemoteActionExecutionContext
        private val channel: ReferenceCountedChannel
        private val callCredentialsProvider: CallCredentialsProvider
        private val callTimeoutSecs: Long
        private val retrier: Retrier?
        private val resourceName: String?
        private val chunker: Chunker
        private val progressiveBackoff: ProgressiveBackoff

        private var lastCommittedOffset: Long = -1

        init {
            this.context = context
            this.channel = channel
            this.callCredentialsProvider = callCredentialsProvider
            this.callTimeoutSecs = callTimeoutSecs
            this.retrier = retrier
            this.progressiveBackoff = ProgressiveBackoff(java.util.function.Supplier { retrier.newBackoff() })
            this.resourceName = resourceName
            this.chunker = chunker
        }

        fun start(): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
            return com.google.common.util.concurrent.Futures.catching<java.lang.Void?, AlreadyExists?>(
                com.google.common.util.concurrent.Futures.transformAsync<Long?, java.lang.Void?>(
                    com.google.devtools.build.lib.remote.util.Utils.refreshIfUnauthenticatedAsync<Long?>(
                        com.google.common.util.concurrent.AsyncCallable {
                            retrier.executeAsync<Long?>(
                                this,
                                progressiveBackoff
                            )
                        }, callCredentialsProvider
                    ),
                    com.google.common.util.concurrent.AsyncFunction { committedSize: Long? ->
                        try {
                            checkCommittedSize(committedSize!!)
                        } catch (e: IOException) {
                            return@transformAsync com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(
                                e
                            )
                        }
                        com.google.common.util.concurrent.Futures.immediateVoidFuture()
                    },
                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                ),
                AlreadyExists::class.java,
                com.google.common.base.Function { ae: AlreadyExists? -> null },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        }

        /** Check the committed_size the server returned makes sense after a successful full upload.  */
        @Throws(IOException::class)
        fun checkCommittedSize(committedSize: Long) {
            val expected: Long = chunker.getOffset()

            if (committedSize == expected) {
                // Both compressed and uncompressed uploads can succeed with this result.
                return
            }

            if (chunker.isCompressed()) {
                if (committedSize == -1L) {
                    // Returned early, blob already available.
                    return
                }

                throw IOException(
                    java.lang.String.format(
                        "compressed write incomplete: committed_size %d is neither -1 nor total %d - %s",
                        committedSize, expected, resourceName
                    )
                )
            }

            // Uncompressed upload failed.
            throw IOException(
                java.lang.String.format(
                    "write incomplete: committed_size %d for %d total - %s",
                    committedSize, expected, resourceName
                )
            )
        }

        /**
         * Make one attempt to upload. If this is the first attempt, uploading starts from the beginning
         * of the blob. On later attempts, the server is queried to see at which offset upload should
         * resume. The final committed size from the server is returned on success.
         */
        override fun call(): com.google.common.util.concurrent.ListenableFuture<Long?> {
            val firstAttempt = lastCommittedOffset == -1L
            return com.google.common.util.concurrent.Futures.transformAsync<Long?, Long?>(
                if (firstAttempt) com.google.common.util.concurrent.Futures.immediateFuture<Long?>(0L) else query(),
                com.google.common.util.concurrent.AsyncFunction { committedSize: Long? ->
                    if (!firstAttempt) {
                        if (committedSize!! > lastCommittedOffset) {
                            // We have made progress on this upload in the last request. Reset the backoff so
                            // that this request has a full deck of retries
                            progressiveBackoff.reset()
                        }
                    }
                    lastCommittedOffset = committedSize!!
                    upload(committedSize)
                },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        }

        fun bsFutureStub(channel: io.grpc.Channel?): ByteStreamFutureStub {
            return ByteStreamGrpc.newFutureStub(channel)
                .withInterceptors(
                    TracingMetadataUtils.attachMetadataInterceptor(context.getRequestMetadata())
                )
                .withCallCredentials(callCredentialsProvider.callCredentials)
                .withDeadlineAfter(callTimeoutSecs, TimeUnit.SECONDS)
        }

        fun bsAsyncStub(channel: io.grpc.Channel?): ByteStreamStub {
            return ByteStreamGrpc.newStub(channel)
                .withInterceptors(
                    TracingMetadataUtils.attachMetadataInterceptor(context.getRequestMetadata())
                )
                .withCallCredentials(callCredentialsProvider.callCredentials)
                .withDeadlineAfter(callTimeoutSecs, TimeUnit.SECONDS)
        }

        fun query(): com.google.common.util.concurrent.ListenableFuture<Long?> {
            if (!queryWriteStatusImplemented.get()) {
                // Without server support for QueryWriteStatus, we have no choice but to restart the entire
                // upload.
                return com.google.common.util.concurrent.Futures.immediateFuture<Long?>(0L)
            }
            val committedSizeFuture: com.google.common.util.concurrent.ListenableFuture<Long?> =
                com.google.common.util.concurrent.Futures.transformAsync<Any?, Long?>(
                    channel.withChannelFuture<Any?>(
                        com.google.devtools.build.lib.remote.ReferenceCountedChannel.IOFunction { channel: io.grpc.Channel? ->
                            bsFutureStub(channel)
                                .queryWriteStatus(
                                    QueryWriteStatusRequest.newBuilder()
                                        .setResourceName(resourceName)
                                        .build()
                                )
                        }),
                    com.google.common.util.concurrent.AsyncFunction { r: Any? ->
                        if (r.getComplete())
                            com.google.common.util.concurrent.Futures.immediateFailedFuture<Any?>(AlreadyExists())
                        else
                            com.google.common.util.concurrent.Futures.immediateFuture<V?>(r.getCommittedSize())
                    },
                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                )
            return com.google.common.util.concurrent.Futures.catchingAsync<Long?, java.lang.Exception?>(
                committedSizeFuture,
                java.lang.Exception::class.java,
                com.google.common.util.concurrent.AsyncFunction { e: java.lang.Exception? ->
                    val status: io.grpc.Status = io.grpc.Status.fromThrowable(e)
                    if (status.getCode() == io.grpc.Status.Code.UNIMPLEMENTED) {
                        queryWriteStatusImplemented.set(false)
                        return@catchingAsync com.google.common.util.concurrent.Futures.immediateFuture<Long?>(0L)
                    }
                    com.google.common.util.concurrent.Futures.immediateFailedFuture<Long?>(e)
                },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        }

        fun upload(pos: Long): com.google.common.util.concurrent.ListenableFuture<Long?>? {
            return channel.withChannelFuture<Long?>(
                com.google.devtools.build.lib.remote.ReferenceCountedChannel.IOFunction { channel: io.grpc.Channel? ->
                    val uploadResult: com.google.common.util.concurrent.SettableFuture<Long?> =
                        com.google.common.util.concurrent.SettableFuture.create<Long?>()
                    bsAsyncStub(channel).write(
                        com.google.devtools.build.lib.remote.ByteStreamUploader.Writer(
                            resourceName,
                            chunker,
                            pos,
                            uploadResult
                        )
                    )
                    uploadResult
                })
        }
    }

    private class Writer
        (
        private val resourceName: String?,
        chunker: Chunker,
        pos: Long,
        uploadResult: com.google.common.util.concurrent.SettableFuture<Long?>
    ) : ClientResponseObserver<WriteRequest?, WriteResponse?>, java.lang.Runnable {
        private val chunker: Chunker
        private val pos: Long
        private val uploadResult: com.google.common.util.concurrent.SettableFuture<Long?>
        private var committedSize: Long = -1
        private var requestObserver: ClientCallStreamObserver<WriteRequest?>? = null
        private var first = true
        private var finishedWriting = false

        init {
            this.chunker = chunker
            this.pos = pos
            this.uploadResult = uploadResult
        }

        override fun beforeStart(requestObserver: ClientCallStreamObserver<WriteRequest?>) {
            this.requestObserver = requestObserver
            uploadResult.addListener(
                java.lang.Runnable {
                    if (uploadResult.isCancelled()) {
                        requestObserver.cancel("cancelled by user", null)
                    }
                },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
            requestObserver.setOnReadyHandler(this)
        }

        override fun run() {
            while (requestObserver.isReady()) {
                val request: WriteRequest.Builder = WriteRequest.newBuilder()
                if (first) {
                    first = false
                    if (!seekChunker()) {
                        return
                    }
                    // Resource name only needs to be set on the first write for each file.
                    request.setResourceName(resourceName)
                }
                val chunk: com.google.devtools.build.lib.remote.Chunker.Chunk
                try {
                    chunk = chunker.next()
                } catch (e: IOException) {
                    requestObserver.cancel("Failed to read next chunk.", e)
                    return
                }
                val isLastChunk: Boolean = !chunker.hasNext()
                requestObserver.onNext(
                    request
                        .setData(chunk.getData())
                        .setWriteOffset(chunk.getOffset())
                        .setFinishWrite(isLastChunk)
                        .build()
                )
                if (isLastChunk) {
                    requestObserver.onCompleted()
                    finishedWriting = true
                    return
                }
            }
        }

        fun seekChunker(): Boolean {
            try {
                chunker.seek(pos)
            } catch (e: IOException) {
                try {
                    chunker.reset()
                } catch (resetException: IOException) {
                    e.addSuppressed(resetException)
                }
                val tooManyOpenFilesError = "Too many open files"
                if (com.google.common.base.Ascii.toLowerCase(e.getMessage())
                        .contains(com.google.common.base.Ascii.toLowerCase(tooManyOpenFilesError))
                ) {
                    val newMessage =
                        ("An IOException was thrown because the process opened too many files. We recommend"
                                + " setting --bep_maximum_open_remote_upload_files flag to a number lower than"
                                + " your system default (run 'ulimit -a' for *nix-based operating systems)."
                                + " Original error message: "
                                + e.getMessage())
                    e = IOException(newMessage, e)
                }
                uploadResult.setException(e)
                requestObserver.cancel("failed to seek chunk", e)
                return false
            }
            return true
        }

        override fun onNext(response: WriteResponse) {
            committedSize = response.getCommittedSize()
        }

        override fun onCompleted() {
            if (finishedWriting) {
                uploadResult.set(committedSize)
            } else {
                // Server completed succesfully before we finished writing all the data, meaning the blob
                // already exists. The server is supposed to set committed_size to the size of the blob (for
                // uncompressed uploads) or -1 (for compressed uploads), but we do not verify this.
                requestObserver.cancel("server has returned early", null)
                uploadResult.setException(AlreadyExists())
            }
        }

        override fun onError(t: Throwable) {
            requestObserver.cancel("failed", t)
            uploadResult.setException(
                if (io.grpc.Status.fromThrowable(t)
                        .getCode() == io.grpc.Status.Code.ALREADY_EXISTS
                ) AlreadyExists() else t
            )
        }
    }

    @com.google.common.annotations.VisibleForTesting
    fun getOpenedFilePermits(): Semaphore? {
        return openedFilePermits
    }
}
