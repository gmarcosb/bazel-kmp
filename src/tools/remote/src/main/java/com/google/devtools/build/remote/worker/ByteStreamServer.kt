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

/** A basic implementation of a [ByteStreamImplBase] service.  */
internal class ByteStreamServer(cache: OnDiskBlobStoreCache, workPath: Path, digestUtil: DigestUtil) :
    ByteStreamImplBase() {
    private val cache: OnDiskBlobStoreCache
    private val workPath: Path
    private val digestUtil: DigestUtil

    init {
        this.cache = cache
        this.workPath = workPath
        this.digestUtil = digestUtil
    }

    public override fun read(request: ReadRequest, responseObserver: StreamObserver<ReadResponse?>) {
        val meta: RequestMetadata? = TracingMetadataUtils.fromCurrentContext()
        val context: RemoteActionExecutionContext? = RemoteActionExecutionContext.create(meta)
        val digest: Digest? = parseDigestFromResourceName(request.getResourceName())

        if (digest == null) {
            responseObserver.onError(
                StatusUtils.invalidArgumentError(
                    "resource_name",
                    "Failed parsing digest from resource_name:" + request.getResourceName()
                )
            )
        }

        try {
            // This still relies on the blob size to be small enough to fit in memory.
            // TODO(olaola): refactor to fix this if the need arises.
            val bytes: ByteArray = getFromFuture(cache.downloadBlob(context, digest))
            Chunker.builder().setInput(bytes.size, { ByteArrayInputStream(bytes) }).build().use { c ->
                while (c.hasNext()) {
                    responseObserver.onNext(ReadResponse.newBuilder().setData(c.next().getData()).build())
                }
            }
            responseObserver.onCompleted()
        } catch (e: CacheNotFoundException) {
            responseObserver.onError(StatusUtils.notFoundError(digest))
        } catch (e: java.lang.Exception) {
            logger.atWarning().withCause(e).log("Read request failed")
            responseObserver.onError(StatusUtils.internalError(e))
        }
    }

    public override fun write(responseObserver: StreamObserver<WriteResponse?>): StreamObserver<WriteRequest?> {
        val meta: RequestMetadata? = TracingMetadataUtils.fromCurrentContext()
        val context: RemoteActionExecutionContext? = RemoteActionExecutionContext.create(meta)

        val temp: Path = workPath.getRelative("upload").getRelative(UUID.randomUUID().toString())
        try {
            temp.getParentDirectory().createDirectoryAndParents()
            FileSystemUtils.createEmptyFile(temp)
        } catch (e: IOException) {
            logger.atSevere().withCause(e).log("Failed to create temporary file for upload")
            responseObserver.onError(StatusUtils.internalError(e))
            // We need to make sure that subsequent onNext or onCompleted calls don't make any further
            // calls on the responseObserver after the onError above, so we return a no-op observer.
            return NoOpStreamObserver<WriteRequest?>()
        }
        return object : StreamObserver<WriteRequest?>() {
            private var digest: Digest? = null
            private var offset: Long = 0
            private var resourceName: String? = null
            private var closed = false

            override fun onNext(request: WriteRequest) {
                if (closed) {
                    return
                }

                if (digest == null) {
                    resourceName = request.getResourceName()
                    digest = Companion.parseDigestFromResourceName(resourceName!!)
                }

                if (digest == null) {
                    responseObserver.onError(
                        StatusUtils.invalidArgumentError(
                            "resource_name",
                            "Failed parsing digest from resource_name: " + request.getResourceName()
                        )
                    )
                    closed = true
                    return
                }

                if (offset == 0L) {
                    var exists = false
                    try {
                        exists = cache.refresh(digest)
                    } catch (e: IOException) {
                        responseObserver.onError(StatusUtils.internalError(e))
                        closed = true
                        return
                    }
                    if (exists) {
                        responseObserver.onNext(
                            WriteResponse.newBuilder().setCommittedSize(digest.getSizeBytes()).build()
                        )
                        responseObserver.onCompleted()
                        closed = true
                        return
                    }
                }

                if (request.getWriteOffset() !== offset) {
                    responseObserver.onError(
                        StatusUtils.invalidArgumentError(
                            "write_offset",
                            "Expected: " + offset + ", received: " + request.getWriteOffset()
                        )
                    )
                    closed = true
                    return
                }

                if (!request.getResourceName().isEmpty()
                    && !request.getResourceName().equals(resourceName)
                ) {
                    responseObserver.onError(
                        StatusUtils.invalidArgumentError(
                            "resource_name",
                            "Expected: " + resourceName + ", received: " + request.getResourceName()
                        )
                    )
                    closed = true
                    return
                }

                val size: Long = request.getData().size()

                if (size > 0) {
                    try {
                        temp.getOutputStream(true).use { out ->
                            request.getData().writeTo(out)
                        }
                    } catch (e: IOException) {
                        responseObserver.onError(StatusUtils.internalError(e))
                        closed = true
                        return
                    }
                    offset += size
                }

                val shouldFinishWrite = offset == digest.getSizeBytes()

                if (shouldFinishWrite != request.getFinishWrite()) {
                    responseObserver.onError(
                        StatusUtils.invalidArgumentError(
                            "finish_write",
                            "Expected:" + shouldFinishWrite + ", received: " + request.getFinishWrite()
                        )
                    )
                    closed = true
                    return
                }
            }

            override fun onError(t: Throwable) {
                if (io.grpc.Status.fromThrowable(t).getCode() != io.grpc.Status.Code.CANCELLED) {
                    logger.atWarning().withCause(t).log("Write request failed remotely")
                }
                closed = true
                try {
                    temp.delete()
                } catch (e: IOException) {
                    logger.atWarning().withCause(e).log("Could not delete temp file")
                }
            }

            override fun onCompleted() {
                if (closed) {
                    return
                }

                if (digest == null || offset != digest.getSizeBytes()) {
                    responseObserver.onError(
                        StatusProto.toStatusRuntimeException(
                            com.google.rpc.Status.newBuilder()
                                .setCode(io.grpc.Status.Code.FAILED_PRECONDITION.value())
                                .setMessage("Request completed before all data was sent.")
                                .build()
                        )
                    )
                    closed = true
                    return
                }

                try {
                    val d: Digest = digestUtil.compute(temp)
                    getFromFuture(cache.uploadFile(context, d, temp))
                    try {
                        temp.delete()
                    } catch (e: IOException) {
                        logger.atWarning().withCause(e).log("Could not delete temp file")
                    }

                    if (!d.equals(digest)) {
                        responseObserver.onError(
                            StatusUtils.invalidArgumentError(
                                "resource_name",
                                "Received digest " + digest + " does not match computed digest " + d
                            )
                        )
                        closed = true
                        return
                    }

                    responseObserver.onNext(WriteResponse.newBuilder().setCommittedSize(offset).build())
                    responseObserver.onCompleted()
                } catch (e: java.lang.Exception) {
                    logger.atWarning().withCause(e).log("Write request failed")
                    responseObserver.onError(StatusUtils.internalError(e))
                    closed = true
                }
            }
        }
    }

    private class NoOpStreamObserver<T> : StreamObserver<T?> {
        override fun onNext(value: T?) {
        }

        override fun onError(t: Throwable?) {
        }

        override fun onCompleted() {
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
        fun parseDigestFromResourceName(resourceName: String): Digest? {
            try {
                val tokens: Array<String?> =
                    resourceName.split("/".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                if (tokens.size < 2) {
                    return null
                }
                val hash = tokens[tokens.size - 2]
                val size: Long = tokens[tokens.size - 1].toLong()
                return DigestUtil.buildDigest(hash, size)
            } catch (e: java.lang.NumberFormatException) {
                return null
            }
        }
    }
}
