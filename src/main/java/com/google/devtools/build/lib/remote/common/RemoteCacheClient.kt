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
package com.google.devtools.build.lib.remote.common

/**
 * Base class for a remote caching protocol.
 * 
 * 
 * Concurrent uploads of the same digest are deduplicated: only one network upload is performed
 * per digest at a time, and subsequent callers attach as observers to the in-flight upload.
 * Implementations provide the raw network calls via the `*Impl` methods.
 * 
 * 
 * Implementations must be thread-safe.
 */
abstract class RemoteCacheClient : MissingDigestsFinder {
    private val casUploadCache: NoResult<Digest?> = NoResult.Companion.create<Digest?>()

    @get:Throws(IOException::class)
    abstract val serverCapabilities: ServerCapabilities?

    abstract val authority: com.google.common.util.concurrent.ListenableFuture<String?>?

    /**
     * Downloads an action result for the `actionKey`.
     * 
     * @param context the context for the action.
     * @param actionKey The digest of the [Action] that generated the action result.
     * @param inlineOutErr A hint to the server to inline the stdout and stderr in the `ActionResult` message.
     * @param inlineOutputFiles A hint to the server to inline the specified output files in the
     * `ActionResult` message.
     * @return A Future representing pending download of an action result. If an action result for
     * `actionKey` cannot be found the result of the Future is `null`.
     */
    abstract fun downloadActionResult(
        context: RemoteActionExecutionContext?,
        actionKey: ActionKey?,
        inlineOutErr: Boolean,
        inlineOutputFiles: MutableSet<String?>?
    ): com.google.common.util.concurrent.ListenableFuture<ActionResult?>?

    /**
     * Uploads an action result for the `actionKey`.
     * 
     * @param context the context for the action.
     * @param actionKey The digest of the [Action] that generated the action result.
     * @param actionResult The action result to associate with the `actionKey`.
     * @return A Future representing pending completion of the upload.
     */
    abstract fun uploadActionResult(
        context: RemoteActionExecutionContext?, actionKey: ActionKey?, actionResult: ActionResult?
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?

    /**
     * Downloads a BLOB for the given `digest` and writes it to `out`.
     * 
     * 
     * It's the callers responsibility to close `out`.
     * 
     * @param context the context for the action.
     * @return A Future representing pending completion of the download. If a BLOB for `digest`
     * does not exist in the cache the Future fails with a [CacheNotFoundException].
     */
    abstract fun downloadBlob(
        context: RemoteActionExecutionContext?, digest: Digest?, out: java.io.OutputStream?
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?

    /**
     * A supplier for the data comprising a BLOB.
     * 
     * 
     * As blobs can be large and may need to be kept in memory, consumers should call [.get]
     * as late as possible.
     */
    fun interface Blob {
        /** Get an input stream for the blob's data. Can be called multiple times.  */
        @Throws(IOException::class)
        fun get(): java.io.InputStream?

        /** An optional human-readable description of the blob's source.  */
        fun description(): String? {
            return null
        }
    }

    /**
     * Uploads a `file` BLOB to the CAS.
     * 
     * 
     * Concurrent uploads of the same digest are deduplicated. If `force` is true an upload
     * that has already finished is re-executed.
     * 
     * @param context the context for the action.
     * @param digest The digest of the file.
     * @param file The file to upload.
     */
    fun uploadFile(
        context: RemoteActionExecutionContext?,
        digest: Digest?,
        file: com.google.devtools.build.lib.vfs.Path?,
        force: Boolean
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        return uploadBlob(
            context,
            digest,
            object : Blob {
                override fun get(): java.io.InputStream {
                    return LazyFileInputStream(file)
                }

                override fun description(): String? {
                    return "file " + file
                }
            },
            force
        )
    }

    /**
     * Uploads a blob to the CAS.
     * 
     * 
     * Concurrent uploads of the same digest are deduplicated. If `force` is true an upload
     * that has already finished is re-executed.
     * 
     * @param context the context for the action.
     * @param digest The digest of the blob.
     * @param blob A supplier for the blob to upload. May be called multiple times, but is closed by
     * the implementation after the upload is complete.
     */
    fun uploadBlob(
        context: RemoteActionExecutionContext?, digest: Digest?, blob: Blob?, force: Boolean
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        return RxFutures.toListenableFuture(
            casUploadCache.execute(
                digest,
                RxFutures.toCompletable(io.reactivex.rxjava3.functions.Supplier {
                    uploadBlobImpl(
                        context,
                        digest,
                        blob
                    )
                }, com.google.common.util.concurrent.MoreExecutors.directExecutor()),
                force
            )
        )
    }

    /**
     * Uploads an in-memory BLOB to the CAS.
     * 
     * 
     * Concurrent uploads of the same digest are deduplicated. If `force` is true an upload
     * that has already finished is re-executed.
     * 
     * @param context the context for the action.
     * @param digest The digest of the blob.
     * @param data The BLOB to upload.
     */
    fun uploadBlob(
        context: RemoteActionExecutionContext?, digest: Digest?, data: ByteString, force: Boolean
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        return uploadBlob(
            context,
            digest,
            com.google.devtools.build.lib.remote.common.RemoteCacheClient.Blob { data.newInput() } as Blob,
            force)
    }

    /**
     * Performs the actual network upload. Called by the deduplicating [.uploadBlob] wrappers.
     * 
     * 
     * Callers should use [.uploadBlob] instead.
     */
    @com.google.common.annotations.VisibleForTesting
    abstract fun uploadBlobImpl(
        context: RemoteActionExecutionContext?, digest: Digest?, blob: Blob?
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?

    /**
     * Registers a blob as the concatenation of the given chunks via SpliceBlob RPC.
     * 
     * 
     * This is used for CDC (Content-Defined Chunking) uploads. After uploading all chunks,
     * SpliceBlob is called to register the blob with the given digest as the concatenation of the
     * chunks.
     * 
     * @param context the context for the action.
     * @param blobDigest The digest of the complete blob.
     * @param chunkDigests The digests of the chunks that make up the blob, in order.
     * @return A future representing pending completion of the splice operation, or null if SpliceBlob
     * is not supported by this cache client.
     */
    open fun spliceBlob(
        context: RemoteActionExecutionContext?, blobDigest: Digest?, chunkDigests: MutableList<Digest?>?
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>? {
        return null
    }

    /**
     * Deduplicates an upload by digest using the same cache as [.uploadFile] and [ ][.uploadBlob]. For use by callers that perform their own upload logic but want to share the
     * dedup state with the regular upload paths (e.g. chunked uploads).
     */
    fun dedupUpload(
        digest: Digest?,
        upload: io.reactivex.rxjava3.functions.Supplier<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>?,
        force: Boolean
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        return RxFutures.toListenableFuture(
            casUploadCache.execute(
                digest,
                RxFutures.toCompletable(upload, com.google.common.util.concurrent.MoreExecutors.directExecutor()),
                force
            )
        )
    }

    val inProgressUploads: com.google.common.collect.ImmutableSet<Digest?>
        /** Returns the digests currently being uploaded.  */
        get() = casUploadCache.getInProgressTasks()

    val finishedUploads: com.google.common.collect.ImmutableSet<Digest?>
        /** Returns the digests for which an upload has finished successfully.  */
        get() = casUploadCache.getFinishedTasks()

    /** Returns the number of subscribers waiting for an in-progress upload of `digest`.  */
    fun getUploadSubscriberCount(digest: Digest?): Int {
        return casUploadCache.getSubscriberCount(digest)
    }

    /** Stops accepting new uploads.  */
    fun shutdownUploads() {
        casUploadCache.shutdown()
    }

    /** Waits for in-progress uploads to finish.  */
    @Throws(java.lang.InterruptedException::class)
    fun awaitUploadTermination() {
        casUploadCache.awaitTermination()
    }

    /** Cancels in-progress uploads.  */
    fun shutdownUploadsNow() {
        casUploadCache.shutdownNow()
    }

    /** Close resources associated with the remote cache.  */
    abstract fun close()
}
