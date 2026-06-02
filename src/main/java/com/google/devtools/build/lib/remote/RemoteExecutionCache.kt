// Copyright 2019 The Bazel Authors. All rights reserved.
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

/** A [CombinedCache] with additional functionality needed for remote execution.  */
open class RemoteExecutionCache(
    remoteCacheClient: RemoteCacheClient?,
    diskCacheClient: DiskCacheClient?,
    symlinkTemplate: String?,
    digestUtil: DigestUtil?,
    chunkingEnabled: Boolean
) : CombinedCache(
    com.google.common.base.Preconditions.checkNotNull<RemoteCacheClient?>(remoteCacheClient),
    diskCacheClient,
    symlinkTemplate,
    digestUtil,
    chunkingEnabled
), MerkleTreeUploader {
    /**
     * An interface used to check whether a given [Path] is available without contacting the
     * remote cache, i.e., it is present on the local disk, perhaps after being downloaded from the
     * disk cache.
     */
    interface RemotePathChecker {
        fun isAvailableLocally(
            context: RemoteActionExecutionContext?,
            path: com.google.devtools.build.lib.vfs.Path?
        ): com.google.common.util.concurrent.ListenableFuture<Boolean?>?
    }

    /**
     * Deduplicates concurrent `findMissingDigests` queries for the same digest across
     * overlapping [.ensureInputsPresent] invocations. Results reported as "present" stay cached
     * until explicitly invalidated, but a "missing" result is invalidated as soon as the triggered
     * upload attempt terminates.
     */
    private val findMissingCache: AsyncTaskCache<Digest?, Boolean?> =
        AsyncTaskCache.Companion.create<Digest?, Boolean?>()

    private var remotePathChecker: RemotePathChecker = object : RemotePathChecker {
        override fun isAvailableLocally(
            context: RemoteActionExecutionContext, path: com.google.devtools.build.lib.vfs.Path
        ): com.google.common.util.concurrent.ListenableFuture<Boolean?> {
            val fs: com.google.devtools.build.lib.vfs.FileSystem? = path.getFileSystem()
            if (fs !is RemoteActionFileSystem) {
                return com.google.common.util.concurrent.Futures.immediateFuture<Boolean?>(true)
            }
            // If the file is available in the disk cache, we can attempt to download it from there.
            var downloadFromDiskCache: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
                com.google.common.util.concurrent.Futures.immediateVoidFuture()
            if (context.getReadCachePolicy().allowDiskCache()) {
                downloadFromDiskCache =
                    com.google.common.util.concurrent.Futures.catchingAsync<java.lang.Void?, IOException?>(
                        fs.downloadIfRemote(path.asFragment()),
                        IOException::class.java,
                        com.google.common.util.concurrent.AsyncFunction { e: IOException? ->
                            logger.atWarning().withCause(e).log(
                                "Failed to download %s", path.getPathString()
                            )
                            com.google.common.util.concurrent.Futures.immediateVoidFuture()
                        },
                        com.google.common.util.concurrent.MoreExecutors.directExecutor()
                    )
            }
            return com.google.common.util.concurrent.Futures.transform<java.lang.Void?, Boolean?>(
                downloadFromDiskCache,
                com.google.common.base.Function { unused: java.lang.Void? ->
                    fs.getHostFileSystem().exists(path.asFragment())
                },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        }
    }

    @com.google.common.annotations.VisibleForTesting
    fun setRemotePathChecker(remotePathChecker: RemotePathChecker) {
        this.remotePathChecker = remotePathChecker
    }

    /**
     * Ensures that the tree structure of the inputs, the input files themselves, and the command are
     * available in the remote cache, such that the tree can be reassembled and executed on another
     * machine given the root digest.
     * 
     * 
     * The cache may check whether files or parts of the tree structure are already present, and do
     * not need to be uploaded again.
     * 
     * 
     * Note that this method is only required for remote execution, not for caching itself.
     * However, remote execution uses a cache to store input files, and that may be a separate
     * end-point from the executor itself, so the functionality lives here.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun ensureInputsPresent(
        context: RemoteActionExecutionContext?,
        merkleTree: Uploadable,
        additionalInputs: MutableMap<Digest?, Message?>,
        force: Boolean,
        remotePathResolver: RemotePathResolver?
    ) {
        val uploads: Flowable<com.google.devtools.build.lib.remote.util.RxUtils.TransferResult?> =
            createUploadTasks(context, merkleTree, additionalInputs, force, remotePathResolver)
                .flatMapPublisher<com.google.devtools.build.lib.remote.util.RxUtils.TransferResult?>(
                    io.reactivex.rxjava3.functions.Function { result: MutableList<UploadTask?>? ->
                        Flowable.using<com.google.devtools.build.lib.remote.util.RxUtils.TransferResult?, MutableList<UploadTask>?>(
                            io.reactivex.rxjava3.functions.Supplier { result },
                            io.reactivex.rxjava3.functions.Function { uploadTasks: MutableList<UploadTask>? ->
                                findMissingBlobs(context, uploadTasks!!)
                                    .flatMapPublisher<com.google.devtools.build.lib.remote.util.RxUtils.TransferResult?>(
                                        io.reactivex.rxjava3.functions.Function { uploadTasks: MutableList<UploadTask?> ->
                                            this.waitForUploadTasks(uploadTasks)
                                        })
                            },
                            io.reactivex.rxjava3.functions.Consumer { uploadTasks: MutableList<UploadTask>? ->
                                for (uploadTask in uploadTasks!!) {
                                    val d: Disposable? = uploadTask.disposable.getAndSet(null)
                                    if (d != null) {
                                        d.dispose()
                                    }
                                }
                            })
                    })

        try {
            RxUtils.mergeBulkTransfer(uploads).blockingAwait()
        } catch (e: java.lang.RuntimeException) {
            val cause: Throwable? = e.getCause()
            if (cause != null) {
                com.google.common.base.Throwables.throwIfInstanceOf<java.lang.InterruptedException?>(
                    cause,
                    java.lang.InterruptedException::class.java
                )
                com.google.common.base.Throwables.throwIfInstanceOf<IOException?>(cause, IOException::class.java)
            }
            throw e
        }
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun ensureInputsPresent(
        context: RemoteActionExecutionContext?,
        merkleTree: Uploadable,
        force: Boolean,
        remotePathResolver: RemotePathResolver?
    ) {
        ensureInputsPresent(
            context,
            merkleTree,
            com.google.common.collect.ImmutableMap.of<Digest?, Message?>(),
            force,
            remotePathResolver
        )
    }

    override fun uploadFile(
        context: RemoteActionExecutionContext?,
        remotePathResolver: RemotePathResolver?,
        digest: Digest?,
        path: com.google.devtools.build.lib.vfs.Path,
        force: Boolean
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        return com.google.common.util.concurrent.Futures.transformAsync<Boolean?, java.lang.Void?>(
            remotePathChecker.isAvailableLocally(context, path),
            com.google.common.util.concurrent.AsyncFunction { isAvailableLocally: Boolean? ->
                if (!isAvailableLocally!!) {
                    // If we get here, the remote input was determined to exist in the remote or disk
                    // cache at some point before action execution, but reported to be missing when
                    // querying the remote for missing action inputs; possibly because it was evicted in
                    // the interim.
                    if (remotePathResolver != null) {
                        throw CacheNotFoundException(
                            digest, remotePathResolver.localPathToExecPath(path.asFragment())
                        )
                    } else {
                        // This path should only be taken for RemoteRepositoryRemoteExecutor, which has no
                        // way to handle lost inputs.
                        throw CacheNotFoundException(digest, path.getPathString())
                    }
                }
                remoteCacheClient.uploadFile(context, digest, path, force)
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
    }

    override fun uploadVirtualActionInput(
        context: RemoteActionExecutionContext?, digest: Digest?, virtualActionInput: VirtualActionInput?
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        return remoteCacheClient.uploadBlob(
            context, digest, VirtualActionInputBlob(virtualActionInput),  /* force= */false
        )
    }

    private class VirtualActionInputBlob(virtualActionInput: VirtualActionInput?) :
        com.google.devtools.build.lib.remote.common.RemoteCacheClient.Blob {
        override fun get(): java.io.InputStream {
            // Avoid materializing and retaining VirtualActionInput.getBytes() during the upload. This
            // can result in high memory usage with many parallel actions with large virtual inputs. Limit
            // this memory usage to the fixed buffer size by using a piped stream.
            val pipedIn: PipedInputStream = PipedInputStream(Chunker.Companion.getDefaultChunkSize())
            val pipedOut: PipedOutputStream?
            try {
                pipedOut = PipedOutputStream(pipedIn)
            } catch (e: IOException) {
                throw java.lang.IllegalStateException(
                    "PipedOutputStream constructor is not expected to throw", e
                )
            }
            // Note that while Piped{Input,Output}Stream are not directly I/O-bound, bytes read from
            // pipedIn are sent out via gRPC before more bytes are read. As a result, pipedOut is expected
            // to block frequently enough to make virtual threads suitable here.
            val unused: java.util.concurrent.Future<*>? =
                VIRTUAL_ACTION_INPUT_PIPE_EXECUTOR.submit(
                    java.lang.Runnable {
                        try {
                            pipedOut.use {
                                virtualActionInput.writeTo(pipedOut)
                            }
                        } catch (e: IOException) {
                            // Since VirtualActionInput#writeTo only throws when pipedOut does, this means
                            // that the reader has closed pipedIn early, perhaps due to interruption. Since
                            // the reader is gone, there is no way to propagate this exception back.
                        }
                    })
            return pipedIn
        }

        val virtualActionInput: VirtualActionInput?

        init {
            this.virtualActionInput = virtualActionInput
        }

        companion object {
            private val VIRTUAL_ACTION_INPUT_PIPE_EXECUTOR: ExecutorService = Executors.newThreadPerTaskExecutor(
                java.lang.Thread.ofVirtual().name("virtual-action-input-pipe-", 0).factory()
            )
        }
    }

    override fun uploadBlob(
        context: RemoteActionExecutionContext?, digest: Digest?, data: ByteArray
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        return remoteCacheClient.uploadBlob(
            context,
            digest,
            com.google.devtools.build.lib.remote.common.RemoteCacheClient.Blob { ByteArrayInputStream(data) },  /* force= */
            false
        )
    }

    private fun uploadBlob(
        context: RemoteActionExecutionContext?,
        digest: Digest?,
        merkleTree: Uploadable,
        additionalInputs: MutableMap<Digest?, Message?>,
        remotePathResolver: RemotePathResolver?,
        force: Boolean
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>? {
        val upload: java.util.Optional<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?> =
            merkleTree.upload(this, context, remotePathResolver, digest, force)
        if (upload.isPresent()) {
            return upload.get()
        }

        val message: Message? = additionalInputs.get(digest)
        if (message != null) {
            return remoteCacheClient.uploadBlob(context, digest, message.toByteString(), force)
        }

        return com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(
            IOException(
                java.lang.String.format(
                    "findMissingDigests returned a missing digest that has not been requested: %s",
                    digest
                )
            )
        )
    }

    internal class UploadTask {
        var digest: Digest? = null
        var disposable: AtomicReference<Disposable?>? = null
        var continuation: SingleEmitter<Boolean?>? = null
        var completion: Completable? = null
    }

    private fun createUploadTasks(
        context: RemoteActionExecutionContext?,
        merkleTree: Uploadable,
        additionalInputs: MutableMap<Digest?, Message?>,
        force: Boolean,
        remotePathResolver: RemotePathResolver?
    ): Single<MutableList<UploadTask?>?>? {
        val allDigests: Iterable<Digest?> =
            com.google.common.collect.Iterables.concat<Digest?>(merkleTree.allDigests(), additionalInputs.keySet())
        if (com.google.common.collect.Iterables.isEmpty(allDigests)) {
            return Single.just<MutableList<UploadTask?>?>(com.google.common.collect.ImmutableList.of<UploadTask?>())
        }
        return Single.< T, U>using<T?, U?>(
        io.reactivex.rxjava3.functions.Supplier { Profiler.instance().profile("collect digests") },
        io.reactivex.rxjava3.functions.Function { ignored: U? ->
            Flowable.fromIterable<Digest?>(allDigests)
                .flatMapMaybe<UploadTask?>(
                    io.reactivex.rxjava3.functions.Function { digest: Digest? ->
                        maybeCreateUploadTask(
                            context,
                            merkleTree,
                            additionalInputs,
                            digest,
                            force,
                            remotePathResolver
                        )
                    })
                .collect<com.google.common.collect.ImmutableList<UploadTask?>?, Any?>(TODO("Cannot convert element"))<Object> com . google . common . collect . ImmutableList . toImmutableList < kotlin . Any ? > ()
        })
        SilentCloseable::close
    }

    private fun maybeCreateUploadTask(
        context: RemoteActionExecutionContext?,
        merkleTree: Uploadable,
        additionalInputs: MutableMap<Digest?, Message?>,
        digest: Digest?,
        force: Boolean,
        remotePathResolver: RemotePathResolver?
    ): Maybe<UploadTask?>? {
        return Maybe.create<UploadTask?>(
            MaybeOnSubscribe { emitter: MaybeEmitter<UploadTask?>? ->
                val completion: AsyncSubject<java.lang.Void?> = AsyncSubject.create<java.lang.Void?>()
                val uploadTask = UploadTask()
                uploadTask.digest = digest
                uploadTask.disposable = AtomicReference<Disposable?>()
                uploadTask.completion = Completable.fromObservable<java.lang.Void?>(completion)
                val upload: Completable =
                    findMissingCache
                        .execute(
                            digest,
                            Single.create<Boolean?>(
                                SingleOnSubscribe { continuation: SingleEmitter<Boolean?>? ->
                                    uploadTask.continuation = continuation
                                    emitter.onSuccess(uploadTask)
                                }),  /* onAlreadyRunning= */
                            io.reactivex.rxjava3.functions.Action { emitter.onSuccess(uploadTask) },  /* onAlreadyFinished= */
                            io.reactivex.rxjava3.functions.Action { emitter.onSuccess(uploadTask) },
                            force
                        )
                        .flatMapCompletable(
                            io.reactivex.rxjava3.functions.Function { shouldUpload: Boolean? ->
                                if (!shouldUpload!!) {
                                    return@flatMapCompletable Completable.complete()
                                }
                                RxFutures.toCompletable(
                                    io.reactivex.rxjava3.functions.Supplier {
                                        uploadBlob(
                                            context,
                                            uploadTask.digest,
                                            merkleTree,
                                            additionalInputs,
                                            remotePathResolver,
                                            force
                                        )
                                    },
                                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                                ) // On success, the digest is now present remotely: replace the cached
                                    // "missing" answer with "present" so late callers (or the next
                                    // ensureInputsPresent invocation) skip both findMissingDigests and
                                    // the upload path. On failure, invalidate so a subsequent caller
                                    // (e.g., after action rewinding) re-queries the remote.
                                    .doOnComplete(io.reactivex.rxjava3.functions.Action {
                                        findMissingCache.put(
                                            digest,
                                            false
                                        )
                                    })
                                    .doOnError(io.reactivex.rxjava3.functions.Consumer { t: Throwable? ->
                                        findMissingCache.invalidate(
                                            digest
                                        )
                                    })
                            })
                upload.subscribe(
                    object : CompletableObserver() {
                        override fun onSubscribe(d: Disposable) {
                            uploadTask.disposable.set(d)
                        }

                        override fun onComplete() {
                            completion.onComplete()
                        }

                        override fun onError(e: Throwable) {
                            completion.onError(e)
                        }
                    })
            })
    }

    private fun findMissingBlobs(
        context: RemoteActionExecutionContext?, uploadTasks: MutableList<UploadTask>
    ): Single<MutableList<UploadTask?>?>? {
        return Single.using<T?, U?>(
            io.reactivex.rxjava3.functions.Supplier { Profiler.instance().profile("findMissingDigests") },
            io.reactivex.rxjava3.functions.Function { ignored: U? ->
                Single.fromObservable<MutableList<UploadTask?>?>(
                    io.reactivex.rxjava3.core.Observable.fromSingle<MutableList<UploadTask?>?>(
                        RxFutures.toSingle<com.google.common.collect.ImmutableSet<Digest?>?>(
                            io.reactivex.rxjava3.functions.Supplier {
                                val digestsToQuery: com.google.common.collect.ImmutableList<Digest?> =
                                    uploadTasks.stream()
                                        .filter(java.util.function.Predicate { uploadTask: UploadTask? -> uploadTask!!.continuation != null })
                                        .map<Any?>(java.util.function.Function { uploadTask: UploadTask? -> uploadTask!!.digest })
                                        .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
                                if (digestsToQuery.isEmpty()) {
                                    return@toSingle com.google.common.util.concurrent.Futures.immediateFuture<com.google.common.collect.ImmutableSet<Digest?>?>(
                                        com.google.common.collect.ImmutableSet.of<Digest?>()
                                    )
                                }
                                remoteCacheClient.findMissingDigests(
                                    context, digestsToQuery
                                )
                            },
                            com.google.common.util.concurrent.MoreExecutors.directExecutor()
                        )
                            .map<MutableList<UploadTask?>?>(
                                io.reactivex.rxjava3.functions.Function { missingDigests: com.google.common.collect.ImmutableSet<Digest?>? ->
                                    for (uploadTask in uploadTasks) {
                                        if (uploadTask.continuation != null) {
                                            uploadTask.continuation.onSuccess(
                                                missingDigests.contains(uploadTask.digest)
                                            )
                                        }
                                    }
                                    uploadTasks
                                })
                    ) // Use AsyncSubject so that if downstream is disposed, the
                        // findMissingDigests call is not cancelled (because it may be needed by
                        // other threads).
                        .subscribeWith<AsyncSubject<MutableList<UploadTask?>?>>(AsyncSubject.create<Any?>())
                )
            },
            SilentCloseable::close
        )
    }

    private fun waitForUploadTasks(uploadTasks: MutableList<UploadTask?>): Flowable<com.google.devtools.build.lib.remote.util.RxUtils.TransferResult?>? {
        return Flowable.using<T?, D?>(
            io.reactivex.rxjava3.functions.Supplier { Profiler.instance().profile("upload") },
            io.reactivex.rxjava3.functions.Function { ignored: D? ->
                Flowable.fromIterable<UploadTask?>(uploadTasks)
                    .flatMapSingle<com.google.devtools.build.lib.remote.util.RxUtils.TransferResult?>(io.reactivex.rxjava3.functions.Function { uploadTask: UploadTask? ->
                        RxUtils.toTransferResult(
                            uploadTask!!.completion
                        )
                    })
            },
            SilentCloseable::close
        )
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    }
}
