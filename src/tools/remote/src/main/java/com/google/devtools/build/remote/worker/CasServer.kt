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
package com.google.devtools.build.remote.worker

import com.google.devtools.build.lib.remote.util.Utils.getFromFuture

/** A basic implementation of a [ContentAddressableStorageImplBase] service.  */
internal class CasServer(cache: OnDiskBlobStoreCache) : ContentAddressableStorageImplBase() {
    private val cache: OnDiskBlobStoreCache
    private val splicedBlobs: MutableMap<Digest?, MutableList<Digest?>?> =
        ConcurrentHashMap<Digest?, MutableList<Digest?>?>()

    init {
        this.cache = cache
    }

    public override fun findMissingBlobs(
        request: FindMissingBlobsRequest, responseObserver: StreamObserver<FindMissingBlobsResponse?>
    ) {
        val response: FindMissingBlobsResponse.Builder = FindMissingBlobsResponse.newBuilder()

        for (digest in request.getBlobDigestsList()) {
            var exists = false
            try {
                exists = cache.refresh(digest)
            } catch (e: IOException) {
                responseObserver.onError(StatusUtils.internalError(e))
                return
            }
            if (!exists) {
                response.addMissingBlobDigests(digest)
            }
        }

        responseObserver.onNext(response.build())
        responseObserver.onCompleted()
    }

    public override fun batchUpdateBlobs(
        request: BatchUpdateBlobsRequest, responseObserver: StreamObserver<BatchUpdateBlobsResponse?>
    ) {
        val meta: RequestMetadata? = TracingMetadataUtils.fromCurrentContext()
        val context: RemoteActionExecutionContext? = RemoteActionExecutionContext.create(meta)

        val batchResponse: BatchUpdateBlobsResponse.Builder = BatchUpdateBlobsResponse.newBuilder()
        for (r in request.getRequestsList()) {
            val resp: BatchUpdateBlobsResponse.Response.Builder = batchResponse.addResponsesBuilder()
            try {
                val digest: Digest? = cache.getDigestUtil().compute(r.getData().toByteArray())
                getFromFuture(cache.uploadBlob(context, digest, r.getData()))
                if (!r.getDigest().equals(digest)) {
                    val err =
                        "Upload digest " + r.getDigest() + " did not match data digest: " + digest
                    resp.setStatus(StatusUtils.invalidArgumentStatus("digest", err))
                    continue
                }
                resp.getStatusBuilder().setCode(Code.OK.getNumber())
            } catch (e: java.lang.Exception) {
                resp.setStatus(StatusUtils.internalErrorStatus(e))
            }
        }
        responseObserver.onNext(batchResponse.build())
        responseObserver.onCompleted()
    }

    public override fun getTree(request: GetTreeRequest, responseObserver: StreamObserver<GetTreeResponse?>) {
        val meta: RequestMetadata? = TracingMetadataUtils.fromCurrentContext()
        val context: RemoteActionExecutionContext? = RemoteActionExecutionContext.create(meta)

        // Directories are returned in depth-first order.  We store all previously-traversed digests so
        // identical subtrees having the same digest will only be traversed and returned once.
        val seenDigests: MutableSet<Digest?> = HashSet<Digest?>()
        val pendingDigests: Deque<Digest?> = ArrayDeque<Digest?>()
        seenDigests.add(request.getRootDigest())
        pendingDigests.push(request.getRootDigest())
        val responseBuilder: GetTreeResponse.Builder = GetTreeResponse.newBuilder()
        while (!pendingDigests.isEmpty()) {
            val digest: Digest? = pendingDigests.pop()
            val directoryBytes: ByteArray?
            try {
                directoryBytes = getFromFuture(cache.downloadBlob(context, digest))
            } catch (e: CacheNotFoundException) {
                responseObserver.onError(StatusUtils.notFoundError(digest))
                return
            } catch (e: java.lang.InterruptedException) {
                responseObserver.onError(StatusUtils.interruptedError(digest))
                return
            } catch (e: java.lang.Exception) {
                logger.atWarning().withCause(e).log("Read request failed")
                responseObserver.onError(StatusUtils.internalError(e))
                return
            }
            val directory: Directory
            try {
                directory = Directory.parseFrom(directoryBytes)
            } catch (e: InvalidProtocolBufferException) {
                logger.atWarning().withCause(e).log("Failed to parse directory in tree")
                responseObserver.onError(StatusUtils.internalError(e))
                return
            }
            responseBuilder.addDirectories(directory)
            for (directoryNode in directory.getDirectoriesList()) {
                if (seenDigests.add(directoryNode.getDigest())) {
                    pendingDigests.push(directoryNode.getDigest())
                }
            }
        }
        responseObserver.onNext(responseBuilder.build())
        responseObserver.onCompleted()
    }

    /**
     * Returns the chunk digests for a blob that was previously stored via spliceBlob. Clients use
     * this to download large blobs in smaller pieces.
     */
    public override fun splitBlob(
        request: SplitBlobRequest, responseObserver: StreamObserver<SplitBlobResponse?>
    ) {
        val blobDigest: Digest? = request.getBlobDigest()

        val chunkDigests: MutableList<Digest?>? = splicedBlobs.get(blobDigest)
        if (chunkDigests == null) {
            responseObserver.onError(StatusUtils.notFoundError(blobDigest))
            return
        }
        responseObserver.onNext(
            SplitBlobResponse.newBuilder()
                .addAllChunkDigests(chunkDigests)
                .setChunkingFunction(ChunkingFunction.Value.FAST_CDC_2020)
                .build()
        )
        responseObserver.onCompleted()
    }

    /**
     * Stores a mapping from a blob digest to the list of chunk digests that compose it.
     * 
     * 
     * All chunks must already exist in the CAS. The concatenated chunks are verified to match the
     * expected blob digest before storing the mapping.
     */
    public override fun spliceBlob(
        request: SpliceBlobRequest, responseObserver: StreamObserver<SpliceBlobResponse?>
    ) {
        val meta: RequestMetadata? = TracingMetadataUtils.fromCurrentContext()
        val context: RemoteActionExecutionContext? = RemoteActionExecutionContext.create(meta)

        val blobDigest: Digest? = request.getBlobDigest()
        val chunkDigests: MutableList<Digest?> = request.getChunkDigestsList()

        try {
            // Verify all chunks exist in the cache.
            for (chunkDigest in chunkDigests) {
                if (!cache.refresh(chunkDigest)) {
                    responseObserver.onError(StatusUtils.notFoundError(chunkDigest))
                    return
                }
            }

            val digestOut: DigestOutputStream =
                cache.getDigestUtil().newDigestOutputStream(java.io.OutputStream.nullOutputStream())
            for (chunkDigest in chunkDigests) {
                val chunkData: ByteArray? = getFromFuture(cache.downloadBlob(context, chunkDigest))
                digestOut.write(chunkData)
            }
            val computedDigest: Digest = digestOut.digest()
            if (!computedDigest.equals(blobDigest)) {
                val err =
                    "Splice digest " + blobDigest + " did not match computed digest: " + computedDigest
                responseObserver.onError(StatusUtils.invalidArgumentError("blob_digest", err))
                return
            }

            // Record the blob-to-chunks mapping for splitBlob lookups.
            splicedBlobs.put(blobDigest, java.util.ArrayList<Digest?>(chunkDigests))

            responseObserver.onNext(SpliceBlobResponse.newBuilder().setBlobDigest(blobDigest).build())
            responseObserver.onCompleted()
        } catch (e: CacheNotFoundException) {
            responseObserver.onError(StatusUtils.notFoundError(e.getMissingDigest()))
        } catch (e: java.lang.InterruptedException) {
            responseObserver.onError(StatusUtils.interruptedError(blobDigest))
        } catch (e: java.lang.Exception) {
            logger.atWarning().withCause(e).log("SpliceBlob request failed")
            responseObserver.onError(StatusUtils.internalError(e))
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
        val MAX_BATCH_SIZE_BYTES: Long = (1024 * 1024 * 4).toLong()
    }
}
