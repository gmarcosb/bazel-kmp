// Copyright 2026 The Bazel Authors. All rights reserved.
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

/** A basic implementation of a [FetchImplBase] service.  */
internal class FetchServer(cache: OnDiskBlobStoreCache, digestUtil: DigestUtil, tempPath: Path) : FetchImplBase() {
    private val cache: OnDiskBlobStoreCache
    private val digestUtil: DigestUtil
    private val tempPath: Path
    private val knownUrls: ConcurrentHashMap<CacheKey?, CacheValue?> = ConcurrentHashMap<CacheKey?, CacheValue?>()

    @kotlin.jvm.JvmRecord
    private data class CacheKey(val url: String?, val canonicalId: String?)

    private class CacheValue(digest: Digest?, downloadedAt: Instant?) {
        val digest: Digest?
        val downloadedAt: Instant?

        init {
            this.digest = digest
            this.downloadedAt = downloadedAt
        }
    }

    private class Qualifiers(
        expectedChecksum: com.google.devtools.build.lib.bazel.repository.downloader.Checksum?,
        globalHeaders: com.google.common.collect.ImmutableMap<String?, String?>?,
        urlSpecificHeaders: com.google.common.collect.ImmutableTable<Int?, String?, String?>?,
        canonicalId: String?
    ) {
        val expectedChecksum: com.google.devtools.build.lib.bazel.repository.downloader.Checksum?
        val globalHeaders: com.google.common.collect.ImmutableMap<String?, String?>?
        val urlSpecificHeaders: com.google.common.collect.ImmutableTable<Int?, String?, String?>?
        val canonicalId: String?

        init {
            this.expectedChecksum = expectedChecksum
            this.globalHeaders = globalHeaders
            this.urlSpecificHeaders = urlSpecificHeaders
            this.canonicalId = canonicalId
        }
    }

    public override fun fetchBlob(
        request: FetchBlobRequest, responseObserver: StreamObserver<FetchBlobResponse?>
    ) {
        if (request.getUrisCount() === 0) {
            responseObserver.onError(
                StatusUtils.invalidArgumentError("uris", "at least one URI must be provided")
            )
            return
        }

        val qualifiers: Qualifiers?
        try {
            qualifiers = parseQualifiers(request.getQualifiersList())
        } catch (e: StatusException) {
            responseObserver.onError(e)
            return
        }

        val cutoff: Instant =
            if (request.hasOldestContentAccepted())
                Instant.now()
                    .minus(java.time.Duration.ofSeconds(request.getOldestContentAccepted().getSeconds()))
            else
                Instant.MIN
        val cacheHit: java.util.Optional<Digest?> = checkCache(request.getUrisList(), qualifiers.canonicalId, cutoff)
        if (cacheHit.isPresent()) {
            responseObserver.onNext(
                FetchBlobResponse.newBuilder()
                    .setStatus(com.google.rpc.Status.newBuilder().setCode(Code.OK_VALUE).build())
                    .setUri(request.getUris(0))
                    .setBlobDigest(cacheHit.get())
                    .setDigestFunction(digestUtil.getDigestFunction())
                    .build()
            )
            responseObserver.onCompleted()
            return
        }

        val tempDownloadDir: Path
        try {
            tempPath.createDirectoryAndParents()
            tempDownloadDir = tempPath.createTempDirectory("download-")
        } catch (e: IOException) {
            responseObserver.onError(StatusUtils.internalError(e))
            return
        }
        try {
            val result = tryDownload(request, qualifiers, tempDownloadDir)

            val requestMetadata: RequestMetadata? = TracingMetadataUtils.fromCurrentContext()
            val context: RemoteActionExecutionContext? = RemoteActionExecutionContext.create(requestMetadata)
            getFromFuture(cache.uploadFile(context, result.digest, result.path))
            addToCache(result.uri, qualifiers.canonicalId, result.digest)

            responseObserver.onNext(
                FetchBlobResponse.newBuilder()
                    .setStatus(com.google.rpc.Status.newBuilder().setCode(Code.OK_VALUE).build())
                    .setUri(result.uri)
                    .setBlobDigest(result.digest)
                    .setDigestFunction(digestUtil.getDigestFunction())
                    .build()
            )
            responseObserver.onCompleted()
        } catch (e: IOException) {
            responseObserver.onNext(
                FetchBlobResponse.newBuilder()
                    .setStatus(
                        com.google.rpc.Status.newBuilder()
                            .setCode(determineCode(e).getNumber())
                            .setMessage("Failed to fetch from any URI: " + e.message)
                            .build()
                    )
                    .setUri(request.getUris(0))
                    .build()
            )
            responseObserver.onCompleted()
        } catch (e: java.lang.Exception) {
            if (e is java.lang.InterruptedException) {
                java.lang.Thread.currentThread().interrupt()
            }
            logger.atWarning().withCause(e).log("Failed to upload blob to CAS")
            responseObserver.onError(StatusUtils.internalError(e))
        } finally {
            try {
                tempDownloadDir.deleteTree()
            } catch (e: IOException) {
                logger.atWarning().withCause(e).log(
                    "Failed to delete temporary download directory %s", tempDownloadDir
                )
            }
        }
    }

    public override fun fetchDirectory(
        request: FetchDirectoryRequest?, responseObserver: StreamObserver<FetchDirectoryResponse?>
    ) {
        // FetchDirectory is not used by Bazel's GrpcRemoteDownloader client.
        responseObserver.onError(
            io.grpc.Status.UNIMPLEMENTED
                .withDescription("FetchDirectory is not implemented")
                .asRuntimeException()
        )
    }

    init {
        this.cache = cache
        this.digestUtil = digestUtil
        this.tempPath = tempPath
    }

    private fun checkCache(
        uris: Iterable<String?>, canonicalId: String?, cutoff: Instant
    ): java.util.Optional<Digest?> {
        for (uri in uris) {
            val cacheValue: CacheValue? =
                knownUrls.get(com.google.devtools.build.remote.worker.FetchServer.CacheKey(uri, canonicalId))
            if (cacheValue != null && cacheValue.downloadedAt.isAfter(cutoff)) {
                return java.util.Optional.of<Digest?>(cacheValue.digest)
            }
        }
        return java.util.Optional.empty<Digest?>()
    }

    private fun addToCache(uri: String?, canonicalId: String?, digest: Digest?) {
        knownUrls.put(
            com.google.devtools.build.remote.worker.FetchServer.CacheKey(uri, canonicalId),
            CacheValue(digest, Instant.now())
        )
    }

    private class DownloadResult(val uri: String?, path: Path?, digest: Digest?) {
        val path: Path?
        val digest: Digest?

        init {
            this.path = path
            this.digest = digest
        }
    }

    @Throws(IOException::class)
    private fun tryDownload(
        request: FetchBlobRequest, qualifiers: Qualifiers, tempDownloadDir: Path
    ): DownloadResult {
        var lastException: IOException? = null

        for (i in 0..<request.getUrisCount()) {
            val uri: String? = request.getUris(i)
            val downloadPath: Path = tempDownloadDir.getChild("attempt_" + i)
            try {
                val out: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    downloadPath.getOutputStream()
                val digestOut: DigestOutputStream =
                    DigestOutputStream(
                        downloadPath.getFileSystem().getDigestFunction().getHashFunction(), out
                    )
                val maybeChecksumOut: Any =
                    if (qualifiers.expectedChecksum != null)
                        HashOutputStream(digestOut, qualifiers.expectedChecksum)
                    else
                        digestOut
                maybeChecksumOut.use {
                    val headers: LinkedHashMap<String?, String?> =
                        LinkedHashMap<String?, String?>(qualifiers.globalHeaders)
                    headers.putAll(qualifiers.urlSpecificHeaders.row(i))
                    fetchFromUrl(
                        uri,
                        headers,
                        java.time.Duration.ofSeconds(request.getTimeout().getSeconds()),
                        maybeChecksumOut
                    )
                    return DownloadResult(uri, downloadPath, digestOut.digest())
                }
            } catch (e: IOException) {
                try {
                    downloadPath.delete()
                } catch (ex: IOException) {
                    logger.atWarning().withCause(ex).log(
                        "Failed to delete partially downloaded file %s", downloadPath
                    )
                }
                lastException = e
                logger.atFine().withCause(e).log("Failed to fetch from %s", uri)
            }
        }

        throw if (lastException != null) lastException else IOException("No URIs to fetch")
    }

    private fun determineCode(lastException: IOException?): Code? {
        return when (lastException) {
            -> Code.DEADLINE_EXCEEDED
            -> Code.NOT_FOUND
            -> Code.ABORTED
            null -> Code.UNKNOWN
        }
    }

    @Throws(IOException::class)
    private fun fetchFromUrl(
        urlString: String?,
        headers: SequencedMap<String?, String?>,
        timeout: java.time.Duration,
        out: java.io.OutputStream?
    ) {
        val connection: java.net.HttpURLConnection
        try {
            connection = java.net.URI(urlString).toURL().openConnection() as java.net.HttpURLConnection
        } catch (e: URISyntaxException) {
            throw IOException("Invalid URI: " + urlString, e)
        }
        val timeoutMillis = if (timeout == java.time.Duration.ZERO) 30000 else timeout.toMillis().toInt()
        try {
            connection.setRequestMethod("GET")
            connection.setConnectTimeout(timeoutMillis)
            connection.setReadTimeout(timeoutMillis)
            headers.forEach { (key: String?, value: String?) -> connection.setRequestProperty(key, value) }

            val responseCode: Int = connection.getResponseCode()
            if (responseCode != java.net.HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP request failed with status " + responseCode)
            }

            connection.getInputStream().use { `in` ->
                `in`.transferTo(out)
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private const val QUALIFIER_CANONICAL_ID = "bazel.canonical_id"
        private const val QUALIFIER_CHECKSUM_SRI = "checksum.sri"
        private const val QUALIFIER_HTTP_HEADER_PREFIX = "http_header:"
        private const val QUALIFIER_HTTP_HEADER_URL_PREFIX = "http_header_url:"

        @Throws(StatusException::class)
        private fun parseQualifiers(qualifiersList: Iterable<Qualifier>): Qualifiers {
            var expectedChecksum: com.google.devtools.build.lib.bazel.repository.downloader.Checksum? = null
            val globalHeaders: com.google.common.collect.ImmutableMap.Builder<String?, String?> =
                com.google.common.collect.ImmutableMap.builder<String?, String?>()
            val urlSpecificHeaders: com.google.common.collect.ImmutableTable.Builder<Int?, String?, String?> =
                com.google.common.collect.ImmutableTable.builder<Int?, String?, String?>()
            var canonicalId: String? = null

            for (qualifier in qualifiersList) {
                val name: String = qualifier.getName()
                val value: String = qualifier.getValue()

                if (name == QUALIFIER_CANONICAL_ID) {
                    canonicalId = value
                } else if (name == QUALIFIER_CHECKSUM_SRI) {
                    try {
                        expectedChecksum =
                            com.google.devtools.build.lib.bazel.repository.downloader.Checksum.fromSubresourceIntegrity(
                                value
                            )
                    } catch (e: InvalidChecksumException) {
                        throw StatusUtils.invalidArgumentError(
                            "qualifiers",
                            "invalid '%s' qualifier: %s".formatted(QUALIFIER_CHECKSUM_SRI, e.message)
                        )
                    }
                } else if (name.startsWith(QUALIFIER_HTTP_HEADER_URL_PREFIX)) {
                    // Format: http_header_url:<url_index>:<header_name>
                    val remainder: String = name.substring(QUALIFIER_HTTP_HEADER_URL_PREFIX.length)
                    val colonIndex: Int = remainder.indexOf(':')
                    if (colonIndex > 0) {
                        try {
                            val urlIndex: Int = remainder.substring(0, colonIndex).toInt()
                            val headerName: String = remainder.substring(colonIndex + 1)
                            urlSpecificHeaders.put(urlIndex, headerName, value)
                        } catch (e: java.lang.NumberFormatException) {
                            throw StatusUtils.invalidArgumentError(
                                "qualifiers",
                                "invalid '%s' qualifier: %s"
                                    .formatted(QUALIFIER_HTTP_HEADER_URL_PREFIX, e.message)
                            )
                        }
                    }
                } else if (name.startsWith(QUALIFIER_HTTP_HEADER_PREFIX)) {
                    val headerName: String = name.substring(QUALIFIER_HTTP_HEADER_PREFIX.length)
                    globalHeaders.put(headerName, value)
                } else {
                    throw StatusUtils.invalidArgumentError(
                        "qualifiers", "unknown qualifier: '%s'".formatted(name)
                    )
                }
            }

            return Qualifiers(
                expectedChecksum,
                globalHeaders.buildOrThrow(),
                urlSpecificHeaders.buildOrThrow(),
                canonicalId
            )
        }
    }
}
