// Copyright 2016 The Bazel Authors. All rights reserved.
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

import build.bazel.remote.execution.v2.ActionCacheGrpc

/** A RemoteActionCache implementation that uses gRPC calls to a remote cache server.  */
@ThreadSafe
class GrpcCacheClient @com.google.common.annotations.VisibleForTesting constructor(
    channel: ReferenceCountedChannel,
    callCredentialsProvider: CallCredentialsProvider,
    options: RemoteOptions,
    retrier: RemoteRetrier,
    digestUtil: DigestUtil
) : RemoteCacheClient(), MissingDigestsFinder {
    private val callCredentialsProvider: CallCredentialsProvider
    private val channel: ReferenceCountedChannel
    private val options: RemoteOptions
    private val digestUtil: DigestUtil
    private val retrier: RemoteRetrier
    private val uploader: ByteStreamUploader
    private val maxMissingBlobsDigestsPerMessage: Int

    private val closed: AtomicBoolean = AtomicBoolean()

    fun shouldVerifyDownloads(): Boolean {
        return options.getRemoteVerifyDownloads()
    }

    init {
        this.callCredentialsProvider = callCredentialsProvider
        this.channel = channel
        this.options = options
        this.digestUtil = digestUtil
        this.retrier = retrier
        this.uploader =
            ByteStreamUploader(
                options.getRemoteInstanceName(),
                channel,
                callCredentialsProvider,
                options.getRemoteTimeout().toSeconds(),
                retrier,
                options.getMaximumOpenFiles(),
                digestUtil.getDigestFunction()
            )
        maxMissingBlobsDigestsPerMessage = computeMaxMissingBlobsDigestsPerMessage()
        com.google.common.base.Preconditions.checkState(
            maxMissingBlobsDigestsPerMessage > 0, "Error: gRPC message size too small."
        )
    }

    private fun computeMaxMissingBlobsDigestsPerMessage(): Int {
        val overhead: Int =
            FindMissingBlobsRequest.newBuilder()
                .setInstanceName(options.getRemoteInstanceName())
                .setDigestFunction(digestUtil.getDigestFunction())
                .build()
                .getSerializedSize()
        val tagSize: Int =
            (FindMissingBlobsRequest.newBuilder()
                .addBlobDigests(Digest.getDefaultInstance())
                .build()
                .getSerializedSize()
                    - FindMissingBlobsRequest.getDefaultInstance().getSerializedSize())
        // We assume all non-empty digests have the same size. This is true for fixed-length hashes.
        val digestSize: Int = digestUtil.compute(byteArrayOf(1)).getSerializedSize() + tagSize
        return (options.getMaxOutboundMessageSize() - overhead) / digestSize
    }

    private fun casFutureStub(
        context: RemoteActionExecutionContext, channel: io.grpc.Channel?
    ): ContentAddressableStorageFutureStub {
        return ContentAddressableStorageGrpc.newFutureStub(channel)
            .withInterceptors(
                TracingMetadataUtils.attachMetadataInterceptor(context.getRequestMetadata()),
                NetworkTimeInterceptor(java.util.function.Supplier { context.getNetworkTime() })
            )
            .withCallCredentials(callCredentialsProvider.callCredentials)
            .withDeadlineAfter(options.getRemoteTimeout().toSeconds(), TimeUnit.SECONDS)
    }

    private fun bsAsyncStub(context: RemoteActionExecutionContext, channel: io.grpc.Channel?): ByteStreamStub {
        return ByteStreamGrpc.newStub(channel)
            .withInterceptors(
                TracingMetadataUtils.attachMetadataInterceptor(context.getRequestMetadata()),
                NetworkTimeInterceptor(java.util.function.Supplier { context.getNetworkTime() })
            )
            .withCallCredentials(callCredentialsProvider.callCredentials)
            .withDeadlineAfter(options.getRemoteTimeout().toSeconds(), TimeUnit.SECONDS)
    }

    private fun acFutureStub(
        context: RemoteActionExecutionContext, channel: io.grpc.Channel?
    ): ActionCacheFutureStub {
        return ActionCacheGrpc.newFutureStub(channel)
            .withInterceptors(
                TracingMetadataUtils.attachMetadataInterceptor(context.getRequestMetadata()),
                NetworkTimeInterceptor(java.util.function.Supplier { context.getNetworkTime() })
            )
            .withCallCredentials(callCredentialsProvider.callCredentials)
            .withDeadlineAfter(options.getRemoteTimeout().toSeconds(), TimeUnit.SECONDS)
    }

    /**
     * Registers a blob as the concatenation of previously uploaded chunks via the SpliceBlob RPC. All
     * chunks must already be present in the CAS.
     * 
     * @return a future that completes when the splice is acknowledged, or null if chunking is not
     * enabled
     */
    override fun spliceBlob(
        context: RemoteActionExecutionContext, blobDigest: Digest?, chunkDigests: MutableList<Digest?>?
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>? {
        if (!options.getExperimentalRemoteCacheChunking()) {
            return null
        }
        val request: SpliceBlobRequest? =
            SpliceBlobRequest.newBuilder()
                .setInstanceName(options.getRemoteInstanceName())
                .setBlobDigest(blobDigest)
                .addAllChunkDigests(chunkDigests)
                .setDigestFunction(digestUtil.getDigestFunction())
                .setChunkingFunction(ChunkingFunction.Value.FAST_CDC_2020)
                .build()
        return com.google.common.util.concurrent.Futures.catchingAsync<java.lang.Void?, StatusRuntimeException?>(
            com.google.common.util.concurrent.Futures.transform<Any?, Any?>(
                com.google.devtools.build.lib.remote.util.Utils.refreshIfUnauthenticatedAsync<Any?>(
                    com.google.common.util.concurrent.AsyncCallable {
                        retrier.executeAsync<Any?>(
                            com.google.common.util.concurrent.AsyncCallable {
                                channel.withChannelFuture<Any?>(
                                    com.google.devtools.build.lib.remote.ReferenceCountedChannel.IOFunction { ch: io.grpc.Channel? ->
                                        casFutureStub(
                                            context,
                                            ch
                                        ).spliceBlob(request)
                                    })
                            })
                    },
                    callCredentialsProvider
                ),
                com.google.common.base.Function { unused: Any? -> null },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            ),
            StatusRuntimeException::class.java,
            com.google.common.util.concurrent.AsyncFunction { e: StatusRuntimeException? ->
                com.google.common.util.concurrent.Futures.immediateFailedFuture<Any?>(
                    IOException(e)
                )
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
    }

    /**
     * Queries the server for chunk information about a blob using the SplitBlob RPC.
     * 
     * @return a future with the split blob response, or null if chunking is not enabled
     */
    fun splitBlob(
        context: RemoteActionExecutionContext, digest: Digest?
    ): com.google.common.util.concurrent.ListenableFuture<SplitBlobResponse?>? {
        if (!options.getExperimentalRemoteCacheChunking()) {
            return null
        }
        val request: SplitBlobRequest? =
            SplitBlobRequest.newBuilder()
                .setInstanceName(options.getRemoteInstanceName())
                .setBlobDigest(digest)
                .setDigestFunction(digestUtil.getDigestFunction())
                .setChunkingFunction(ChunkingFunction.Value.FAST_CDC_2020)
                .build()
        return com.google.common.util.concurrent.Futures.catchingAsync<SplitBlobResponse?, StatusRuntimeException?>(
            com.google.devtools.build.lib.remote.util.Utils.refreshIfUnauthenticatedAsync<Any?>(
                com.google.common.util.concurrent.AsyncCallable {
                    retrier.executeAsync<Any?>(
                        com.google.common.util.concurrent.AsyncCallable {
                            channel.withChannelFuture<Any?>(
                                com.google.devtools.build.lib.remote.ReferenceCountedChannel.IOFunction { ch: io.grpc.Channel? ->
                                    casFutureStub(
                                        context,
                                        ch
                                    ).splitBlob(request)
                                })
                        })
                },
                callCredentialsProvider
            ),
            StatusRuntimeException::class.java,
            com.google.common.util.concurrent.AsyncFunction { e: StatusRuntimeException? ->
                if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND)
                    com.google.common.util.concurrent.Futures.immediateFailedFuture<Any?>(CacheNotFoundException(digest))
                else
                    com.google.common.util.concurrent.Futures.immediateFailedFuture<Any?>(IOException(e))
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
    }

    override fun close() {
        if (closed.getAndSet(true)) {
            return
        }
        channel.release()
    }

    override fun findMissingDigests(
        context: RemoteActionExecutionContext, digests: Iterable<Digest?>
    ): com.google.common.util.concurrent.ListenableFuture<com.google.common.collect.ImmutableSet<Digest?>?> {
        if (com.google.common.collect.Iterables.isEmpty(digests)) {
            return com.google.common.util.concurrent.Futures.immediateFuture<com.google.common.collect.ImmutableSet<Digest?>?>(
                com.google.common.collect.ImmutableSet.of<Digest?>()
            )
        }
        // Need to potentially split the digests into multiple requests.
        val requestBuilder: FindMissingBlobsRequest.Builder =
            FindMissingBlobsRequest.newBuilder()
                .setInstanceName(options.getRemoteInstanceName())
                .setDigestFunction(digestUtil.getDigestFunction())
        val getMissingDigestCalls: MutableList<com.google.common.util.concurrent.ListenableFuture<FindMissingBlobsResponse?>> =
            java.util.ArrayList<com.google.common.util.concurrent.ListenableFuture<FindMissingBlobsResponse?>>()
        for (digest in digests) {
            requestBuilder.addBlobDigests(digest)
            if (requestBuilder.getBlobDigestsCount() === maxMissingBlobsDigestsPerMessage) {
                getMissingDigestCalls.add(getMissingDigests(context, requestBuilder.build()))
                requestBuilder.clearBlobDigests()
            }
        }

        if (requestBuilder.getBlobDigestsCount() > 0) {
            getMissingDigestCalls.add(getMissingDigests(context, requestBuilder.build()))
        }

        val success: com.google.common.util.concurrent.ListenableFuture<com.google.common.collect.ImmutableSet<Digest?>?> =
            com.google.common.util.concurrent.Futures.whenAllSucceed<FindMissingBlobsResponse?>(getMissingDigestCalls)
                .call<com.google.common.collect.ImmutableSet<Digest?>?>(
                    java.util.concurrent.Callable {
                        val result: com.google.common.collect.ImmutableSet.Builder<Digest?> =
                            com.google.common.collect.ImmutableSet.builder<Digest?>()
                        for (callFuture in getMissingDigestCalls) {
                            result.addAll(callFuture.get().getMissingBlobDigestsList())
                        }
                        result.build()
                    },
                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                )

        val requestMetadata: RequestMetadata = context.getRequestMetadata()
        return com.google.common.util.concurrent.Futures.catchingAsync<com.google.common.collect.ImmutableSet<Digest?>?, java.lang.RuntimeException?>(
            success,
            java.lang.RuntimeException::class.java,
            com.google.common.util.concurrent.AsyncFunction { e: java.lang.RuntimeException? ->
                com.google.common.util.concurrent.Futures.immediateFailedFuture<com.google.common.collect.ImmutableSet<Digest?>?>(
                    IOException(
                        java.lang.String.format(
                            "findMissingBlobs(%d) for %s: %s",
                            requestBuilder.getBlobDigestsCount(),
                            requestMetadata.getActionId(),
                            e.getMessage()
                        ),
                        e
                    )
                )
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
    }

    private fun getMissingDigests(
        context: RemoteActionExecutionContext, request: FindMissingBlobsRequest?
    ): com.google.common.util.concurrent.ListenableFuture<FindMissingBlobsResponse?> {
        return com.google.devtools.build.lib.remote.util.Utils.refreshIfUnauthenticatedAsync<FindMissingBlobsResponse?>(
            com.google.common.util.concurrent.AsyncCallable {
                retrier.executeAsync<Any?>(
                    com.google.common.util.concurrent.AsyncCallable {
                        channel.withChannelFuture<Any?>(
                            com.google.devtools.build.lib.remote.ReferenceCountedChannel.IOFunction { channel: io.grpc.Channel? ->
                                casFutureStub(
                                    context,
                                    channel
                                ).findMissingBlobs(request)
                            })
                    })
            },
            callCredentialsProvider
        )
    }

    private fun handleStatus(download: com.google.common.util.concurrent.ListenableFuture<ActionResult?>): com.google.common.util.concurrent.ListenableFuture<ActionResult?> {
        return com.google.common.util.concurrent.Futures.catchingAsync<ActionResult?, StatusRuntimeException?>(
            download,
            StatusRuntimeException::class.java,
            com.google.common.util.concurrent.AsyncFunction { sre: StatusRuntimeException? ->
                if (sre.getStatus()
                        .getCode() == io.grpc.Status.Code.NOT_FOUND // Return null to indicate that it was a cache miss.
                )
                    com.google.common.util.concurrent.Futures.immediateFuture<ActionResult?>(null)
                else
                    com.google.common.util.concurrent.Futures.immediateFailedFuture<ActionResult?>(IOException(sre))
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
    }

    @get:Throws(IOException::class)
    val serverCapabilities: ServerCapabilities?
        get() = channel.getServerCapabilities()

    val authority: com.google.common.util.concurrent.ListenableFuture<String?>?
        get() = channel.withChannelFuture<String?>(com.google.devtools.build.lib.remote.ReferenceCountedChannel.IOFunction { ch: io.grpc.Channel? ->
            com.google.common.util.concurrent.Futures.immediateFuture<String?>(
                ch.authority()
            )
        })

    override fun downloadActionResult(
        context: RemoteActionExecutionContext,
        actionKey: ActionKey,
        inlineOutErr: Boolean,
        inlineOutputFiles: MutableSet<String?>?
    ): com.google.common.util.concurrent.ListenableFuture<ActionResult?> {
        val request: GetActionResultRequest? =
            GetActionResultRequest.newBuilder()
                .setInstanceName(options.getRemoteInstanceName())
                .setDigestFunction(digestUtil.getDigestFunction())
                .setActionDigest(actionKey.digest)
                .setInlineStderr(inlineOutErr)
                .setInlineStdout(inlineOutErr)
                .addAllInlineOutputFiles(inlineOutputFiles)
                .build()
        return com.google.devtools.build.lib.remote.util.Utils.refreshIfUnauthenticatedAsync<ActionResult?>(
            com.google.common.util.concurrent.AsyncCallable {
                retrier.executeAsync<ActionResult?>(
                    com.google.common.util.concurrent.AsyncCallable {
                        handleStatus(
                            channel.withChannelFuture<ActionResult?>(
                                com.google.devtools.build.lib.remote.ReferenceCountedChannel.IOFunction { channel: io.grpc.Channel? ->
                                    acFutureStub(
                                        context,
                                        channel
                                    ).getActionResult(request)
                                })
                        )
                    })
            },
            callCredentialsProvider
        )
    }

    override fun uploadActionResult(
        context: RemoteActionExecutionContext, actionKey: ActionKey, actionResult: ActionResult?
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        val upload: com.google.common.util.concurrent.ListenableFuture<ActionResult?> =
            com.google.devtools.build.lib.remote.util.Utils.refreshIfUnauthenticatedAsync<ActionResult?>(
                com.google.common.util.concurrent.AsyncCallable {
                    retrier.executeAsync<Any?>(
                        com.google.common.util.concurrent.AsyncCallable {
                            com.google.common.util.concurrent.Futures.catchingAsync<Any?, StatusRuntimeException?>(
                                channel.withChannelFuture<Any?>(
                                    com.google.devtools.build.lib.remote.ReferenceCountedChannel.IOFunction { channel: io.grpc.Channel? ->
                                        acFutureStub(context, channel)
                                            .updateActionResult(
                                                UpdateActionResultRequest.newBuilder()
                                                    .setInstanceName(options.getRemoteInstanceName())
                                                    .setDigestFunction(digestUtil.getDigestFunction())
                                                    .setActionDigest(actionKey.digest)
                                                    .setActionResult(actionResult)
                                                    .build()
                                            )
                                    }),
                                StatusRuntimeException::class.java,
                                com.google.common.util.concurrent.AsyncFunction { sre: StatusRuntimeException? ->
                                    com.google.common.util.concurrent.Futures.immediateFailedFuture<Any?>(
                                        IOException(sre)
                                    )
                                },
                                com.google.common.util.concurrent.MoreExecutors.directExecutor()
                            )
                        })
                },
                callCredentialsProvider
            )

        return com.google.common.util.concurrent.Futures.transform<ActionResult?, java.lang.Void?>(
            upload,
            com.google.common.base.Function { ac: ActionResult? -> null },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
    }

    override fun downloadBlob(
        context: RemoteActionExecutionContext, digest: Digest, out: java.io.OutputStream
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        var out: java.io.OutputStream = out
        if (digest.getSizeBytes() === 0) {
            return com.google.common.util.concurrent.Futures.immediateVoidFuture()
        }

        var digestSupplier: java.util.function.Supplier<Digest?>? = null
        if (options.getRemoteVerifyDownloads()) {
            val digestOut: com.google.devtools.build.lib.remote.util.DigestOutputStream =
                digestUtil.newDigestOutputStream(out)
            digestSupplier = java.util.function.Supplier { digestOut.digest() }
            out = digestOut
        }

        return downloadBlob(context, digest, com.google.common.io.CountingOutputStream(out), digestSupplier)
    }

    private fun downloadBlob(
        context: RemoteActionExecutionContext,
        digest: Digest,
        out: com.google.common.io.CountingOutputStream,
        digestSupplier: java.util.function.Supplier<Digest?>?
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        val progressiveBackoff: ProgressiveBackoff =
            ProgressiveBackoff(java.util.function.Supplier { retrier.newBackoff() })
        val downloadFuture: com.google.common.util.concurrent.ListenableFuture<Long?> =
            com.google.devtools.build.lib.remote.util.Utils.refreshIfUnauthenticatedAsync<Long?>(
                com.google.common.util.concurrent.AsyncCallable {
                    retrier.executeAsync<Long?>(
                        com.google.common.util.concurrent.AsyncCallable {
                            channel.withChannelFuture<Long?>(
                                com.google.devtools.build.lib.remote.ReferenceCountedChannel.IOFunction { channel: io.grpc.Channel? ->
                                    requestRead(
                                        context,
                                        progressiveBackoff,
                                        digest,
                                        out,
                                        digestSupplier,
                                        channel
                                    )
                                })
                        },
                        progressiveBackoff
                    )
                },
                callCredentialsProvider
            )

        return com.google.common.util.concurrent.Futures.catchingAsync<java.lang.Void?, StatusRuntimeException?>(
            com.google.common.util.concurrent.Futures.transform<Long?, java.lang.Void?>(
                downloadFuture,
                com.google.common.base.Function { bytesWritten: Long? -> null },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            ),
            StatusRuntimeException::class.java,
            com.google.common.util.concurrent.AsyncFunction { e: StatusRuntimeException? ->
                com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(
                    IOException(e)
                )
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
    }

    private fun requestRead(
        context: RemoteActionExecutionContext,
        progressiveBackoff: ProgressiveBackoff,
        digest: Digest,
        rawOut: com.google.common.io.CountingOutputStream,
        digestSupplier: java.util.function.Supplier<Digest?>?,
        channel: io.grpc.Channel?
    ): com.google.common.util.concurrent.ListenableFuture<Long?> {
        val compressed = shouldCompress(digest)
        val resourceName =
            getResourceName(
                options.getRemoteInstanceName(), digest, compressed, digestUtil.getDigestFunction()
            )
        val future: com.google.common.util.concurrent.SettableFuture<Long?> =
            com.google.common.util.concurrent.SettableFuture.create<Long?>()
        val out: java.io.OutputStream?
        try {
            out = if (compressed) ZstdDecompressingOutputStream(rawOut) else rawOut
        } catch (e: IOException) {
            return com.google.common.util.concurrent.Futures.immediateFailedFuture<Long?>(e)
        }
        bsAsyncStub(context, channel)
            .read(
                ReadRequest.newBuilder()
                    .setResourceName(resourceName)
                    .setReadOffset(rawOut.getCount())
                    .build(),
                object : ClientResponseObserver<ReadRequest?, ReadResponse?>() {
                    @kotlin.concurrent.Volatile
                    private var requestStream: ClientCallStreamObserver<ReadRequest?>? = null

                    override fun beforeStart(requestStream: ClientCallStreamObserver<ReadRequest?>) {
                        this.requestStream = requestStream
                        future.addListener(
                            java.lang.Runnable {
                                if (future.isCancelled()) {
                                    requestStream.cancel("canceled by user", null)
                                }
                            },
                            com.google.common.util.concurrent.MoreExecutors.directExecutor()
                        )
                    }

                    override fun onNext(readResponse: ReadResponse) {
                        val data: ByteString = readResponse.getData()
                        try {
                            data.writeTo(out)
                        } catch (e: IOException) {
                            // The output stream was likely closed due to cancellation (e.g. dynamic execution
                            // choosing the local branch).
                            if (requestStream != null) {
                                requestStream.cancel("output stream closed", e)
                            }
                            future.setException(e)
                            return
                        }
                        // reset the stall backoff because we've made progress or been kept alive
                        progressiveBackoff.reset()
                    }

                    override fun onError(t: Throwable) {
                        if (rawOut.getCount() == digest.getSizeBytes()) {
                            // If the file was fully downloaded, it doesn't matter if there was an
                            // error at
                            // the end of the stream.
                            logger.atInfo().withCause(t).log(
                                "ignoring error because file was fully received"
                            )
                            onCompleted()
                            return
                        }
                        releaseOut()
                        val status: io.grpc.Status = io.grpc.Status.fromThrowable(t)
                        if (status.getCode() == io.grpc.Status.Code.NOT_FOUND) {
                            future.setException(CacheNotFoundException(digest))
                        } else {
                            future.setException(t)
                        }
                    }

                    override fun onCompleted() {
                        try {
                            try {
                                out.flush()
                            } finally {
                                releaseOut()
                            }
                            if (digestSupplier != null) {
                                com.google.devtools.build.lib.remote.util.Utils.verifyBlobContents(
                                    digest,
                                    digestSupplier.get()
                                )
                            }
                        } catch (e: IOException) {
                            future.setException(e)
                        } catch (e: java.lang.RuntimeException) {
                            logger.atWarning().withCause(e).log("Unexpected exception")
                            future.setException(e)
                        }
                        future.set(rawOut.getCount())
                    }

                    fun releaseOut() {
                        if (out is ZstdDecompressingOutputStream) {
                            try {
                                (out as ZstdDecompressingOutputStream).closeShallow()
                            } catch (e: IOException) {
                                logger.atWarning().withCause(e).log("failed to cleanly close output stream")
                            }
                        }
                    }
                })
        return future
    }

    override fun uploadBlobImpl(
        context: RemoteActionExecutionContext?,
        digest: Digest,
        blob: com.google.devtools.build.lib.remote.common.RemoteCacheClient.Blob
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        return com.google.common.util.concurrent.Futures.catchingAsync<java.lang.Void?, IOException?>(
            uploadChunker(
                context,
                digest,
                Chunker.Companion.builder()
                    .setInput(digest.getSizeBytes(), blob)
                    .setCompressed(shouldCompress(digest))
                    .build()
            ),
            IOException::class.java,
            com.google.common.util.concurrent.AsyncFunction { e: IOException? ->
                val cause: Throwable? = e.getCause()
                if (cause !is StatusRuntimeException) {
                    return@catchingAsync com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(
                        e
                    )
                }
                val code: io.grpc.Status.Code? = cause.getStatus().getCode()
                val blobDescription: String? = blob.description()
                // INVALID_ARGUMENT is returned in case of a digest mismatch, which can hint at concurrent
                // modifications to the blob's source. Print it to help the user debug such issues.
                // https://github.com/bazelbuild/bazel/blob/ec36eacc31678ecf4b5c25f9ab7ab166330aff28/third_party/remoteapis/build/bazel/remote/execution/v2/remote_execution.proto#L283-L286
                if (code == io.grpc.Status.Code.INVALID_ARGUMENT && blobDescription != null) {
                    return@catchingAsync com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(
                        IOException(
                            "while uploading %s: %s".formatted(blobDescription, e.getMessage()), e
                        )
                    )
                }
                com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(e)
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
    }

    fun uploadChunker(
        context: RemoteActionExecutionContext?, digest: Digest, chunker: Chunker
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        val f: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
            uploader.uploadBlobAsync(context, digest, chunker)
        f.addListener(
            java.lang.Runnable {
                try {
                    chunker.close()
                } catch (e: IOException) {
                    logger.atWarning().withCause(e).log(
                        "failed to close chunker uploading %s/%d", digest.getHash(), digest.getSizeBytes()
                    )
                }
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
        return f
    }

    fun getRetrier(): Retrier {
        return this.retrier
    }

    private fun shouldCompress(digest: Digest): Boolean {
        return options.getCacheCompression()
                && digest.getSizeBytes() >= options.getCacheCompressionThreshold()
    }

    fun getChannel(): ReferenceCountedChannel {
        return channel
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /** Returns true if 'options.getRemoteCache()' uses 'grpc' or an empty scheme  */
        fun isRemoteCacheOptions(options: RemoteOptions): Boolean {
            if (com.google.common.base.Strings.isNullOrEmpty(options.getRemoteCache())) {
                return false
            }
            // TODO(ishikhman): add proper URI validation/parsing for remote options
            return !(com.google.common.base.Ascii.toLowerCase(options.getRemoteCache()).startsWith("http://")
                    || com.google.common.base.Ascii.toLowerCase(options.getRemoteCache()).startsWith("https://"))
        }

        fun getResourceName(
            instanceName: String, digest: Digest, compressed: Boolean, digestFunction: DigestFunction.Value
        ): String {
            var resourceName = ""
            if (!instanceName.isEmpty()) {
                resourceName += instanceName + "/"
            }
            resourceName += if (compressed) "compressed-blobs/zstd/" else "blobs/"
            if (!DigestUtil.isOldStyleDigestFunction(digestFunction)) {
                resourceName += com.google.common.base.Ascii.toLowerCase(
                    digestFunction.getValueDescriptor().getName()
                ) + "/"
            }
            return resourceName + DigestUtil.toString(digest)
        }
    }
}
