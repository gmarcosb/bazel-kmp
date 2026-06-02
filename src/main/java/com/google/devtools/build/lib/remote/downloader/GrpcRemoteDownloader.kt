// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.downloader


import build.bazel.remote.asset.v1.FetchBlobRequest
import com.google.auth.Credentials
import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Strings
import com.google.devtools.build.lib.bazel.repository.downloader.Checksum
import com.google.devtools.build.lib.clock.BlazeClock
import com.google.devtools.build.lib.events.Event
import com.google.devtools.build.lib.remote.ReferenceCountedChannel
import com.google.devtools.build.lib.remote.util.Utils
import com.google.devtools.build.lib.vfs.Path
import io.grpc.Channel
import java.io.OutputStream
import java.lang.String
import java.net.URI
import java.time.Duration
import java.util.*
import java.util.function.Predicate
import kotlin.Any
import kotlin.Boolean
import kotlin.RuntimeException
import kotlin.toString

/**
 * A Downloader implementation that uses Bazel's Remote Execution APIs to delegate downloads of
 * external files to a remote service.
 * 
 * 
 * See https://github.com/bazelbuild/remote-apis for more details on the exact capabilities and
 * semantics of the Remote Execution API.
 */
class GrpcRemoteDownloader(
    private val buildRequestId: String?,
    private val commandId: String?,
    channel: ReferenceCountedChannel,
    credentials: Optional<CallCredentials?>,
    retrier: RemoteRetrier,
    cacheClient: RemoteCacheClient,
    digestFunction: DigestFunction.Value?,
    options: RemoteOptions,
    verboseFailures: Boolean,
    httpDownloader: Downloader,
    remoteDownloaderLocalFallback: Boolean
) : AutoCloseable, Downloader {
    private val channel: ReferenceCountedChannel
    private val credentials: Optional<CallCredentials?>
    private val retrier: RemoteRetrier
    private val cacheClient: RemoteCacheClient
    private val digestFunction: DigestFunction.Value?
    private val options: RemoteOptions
    private val verboseFailures: Boolean
    private val httpDownloader: Downloader
    private val remoteDownloaderLocalFallback: Boolean

    private val closed: AtomicBoolean = AtomicBoolean()

    init {
        this.channel = channel
        this.credentials = credentials
        this.retrier = retrier
        this.cacheClient = cacheClient
        this.digestFunction = digestFunction
        this.options = options
        this.verboseFailures = verboseFailures
        this.httpDownloader = httpDownloader
        this.remoteDownloaderLocalFallback = remoteDownloaderLocalFallback
    }

    override fun close() {
        if (closed.getAndSet(true)) {
            return
        }
        cacheClient.close()
        channel.release()
    }

    @Throws(IOException::class, InterruptedException::class)
    override fun download(
        urls: MutableList<URI>,
        headers: MutableMap<String?, MutableList<String?>?>,
        credentials: Credentials,
        checksum: Optional<Checksum?>,
        canonicalId: String?,
        destination: Path,
        eventHandler: ExtendedEventHandler,
        clientEnv: MutableMap<String?, String?>?,
        type: Optional<String?>?,
        context: String?
    ) {
        // file: URLs can't use the gRPC downloader.
        if (urls.stream().anyMatch(Predicate { url: URI? -> url!!.getScheme() == "file" })) {
            httpDownloader.download(
                urls,
                headers,
                credentials,
                checksum,
                canonicalId,
                destination,
                eventHandler,
                clientEnv,
                type,
                context
            )
            return
        }
        val metadata: RequestMetadata? =
            TracingMetadataUtils.buildMetadata(
                buildRequestId,
                commandId,
                "remote_downloader",  /* mnemonic= */
                null,  /* label= */
                context,  /* configurationId= */
                null
            )
        val remoteActionExecutionContext: RemoteActionExecutionContext =
            RemoteActionExecutionContext.Companion.create(metadata)

        val request: FetchBlobRequest =
            newFetchBlobRequest(
                options.getRemoteInstanceName(),
                options.getRemoteDownloaderPropagateCredentials(),
                urls,
                checksum,
                canonicalId,
                digestFunction,
                headers,
                credentials
            )
        var eventUri: String? = urls.getFirst().toString()
        try {
            val response: FetchBlobResponse =
                retrier.execute<FetchBlobResponse, RuntimeException?>(
                    RetryableCallable {
                        channel.withChannelBlocking<Any?>(
                            ReferenceCountedChannel.IOFunction { channel: Channel? ->
                                fetchBlockingStub(remoteActionExecutionContext, channel)
                                    .fetchBlob(request)
                            })
                    })
            if (!response.getUri().isEmpty()) {
                eventUri = response.getUri()
            }
            if (response.getStatus().getCode() === Code.OK_VALUE) {
                eventHandler.post(FetchEvent(eventUri, FetchId.Downloader.GRPC,  /* success= */true))
            } else {
                throw StatusProto.toStatusRuntimeException(response.getStatus())
            }
            val blobDigest: Digest? = response.getBlobDigest()

            val unused =
                retrier.execute<Any?, RuntimeException?>(
                    RetryableCallable {
                        try {
                            newOutputStream(destination, checksum).use { out ->
                                Utils.getFromFuture<Void?>(
                                    cacheClient.downloadBlob(remoteActionExecutionContext, blobDigest, out)
                                )
                            }
                        } catch (e: OutputDigestMismatchException) {
                            e.setOutputPath(destination.getPathString())
                            throw e
                        }
                        null
                    })
        } catch (e: StatusRuntimeException) {
            eventHandler.post(FetchEvent(eventUri, FetchId.Downloader.GRPC,  /* success= */false))
            if (!remoteDownloaderLocalFallback) {
                if (e is StatusRuntimeException) {
                    throw IOException(e)
                }
                throw e
            }
            eventHandler.handle(
                Event.warn("Remote Cache: " + Utils.grpcAwareErrorMessage(e, verboseFailures))
            )
            httpDownloader.download(
                urls,
                headers,
                credentials,
                checksum,
                canonicalId,
                destination,
                eventHandler,
                clientEnv,
                type,
                context
            )
        } catch (e: IOException) {
            eventHandler.post(FetchEvent(eventUri, FetchId.Downloader.GRPC, false))
            if (!remoteDownloaderLocalFallback) {
                if (e is StatusRuntimeException) {
                    throw IOException(e)
                }
                throw e
            }
            eventHandler.handle(
                Event.warn("Remote Cache: " + Utils.grpcAwareErrorMessage(e, verboseFailures))
            )
            httpDownloader.download(
                urls,
                headers,
                credentials,
                checksum,
                canonicalId,
                destination,
                eventHandler,
                clientEnv,
                type,
                context
            )
        }
    }

    private fun fetchBlockingStub(
        context: RemoteActionExecutionContext, channel: Channel?
    ): FetchBlockingStub {
        return FetchGrpc.newBlockingStub(channel)
            .withInterceptors(
                TracingMetadataUtils.attachMetadataInterceptor(context.getRequestMetadata())
            )
            .withInterceptors(TracingMetadataUtils.newDownloaderHeadersInterceptor(options))
            .withCallCredentials(credentials.orElse(null))
            .withDeadlineAfter(options.getRemoteTimeout().toSeconds(), TimeUnit.SECONDS)
    }

    @Throws(IOException::class)
    private fun newOutputStream(destination: Path, checksum: Optional<Checksum?>): OutputStream {
        var out = destination.getOutputStream()
        if (checksum.isPresent()) {
            out = HashOutputStream(out, checksum.get())
        }
        return out
    }

    @VisibleForTesting
    fun getChannel(): ReferenceCountedChannel {
        return channel
    }

    companion object {
        // The `Qualifier::name` field uses well-known string keys to attach arbitrary
        // key-value metadata to download requests. These are the qualifier names
        // supported by Bazel.
        private const val QUALIFIER_CHECKSUM_SRI = "checksum.sri"
        private const val QUALIFIER_CANONICAL_ID = "bazel.canonical_id"

        // The `:` character is not permitted in an HTTP header name. So, we use it to
        // delimit the qualifier prefix which denotes an HTTP header qualifer from the
        // header name itself.
        private const val QUALIFIER_HTTP_HEADER_PREFIX = "http_header:"

        // Same as HTTP_HEADER_PREFIX, but only apply for a specific URL.
        // The index starts from 0 and corresponds to the URL index in the request.
        // Server should prefer using the URL-specific header value over the generic header
        // value when both are present.
        private const val QUALIFIER_HTTP_HEADER_URL_PREFIX = "http_header_url:"

        @VisibleForTesting
        @Throws(IOException::class)
        fun newFetchBlobRequest(
            instanceName: String?,
            remoteDownloaderPropagateCredentials: Boolean,
            urls: MutableList<URI>,
            checksum: Optional<Checksum?>,
            canonicalId: String?,
            digestFunction: DigestFunction.Value?,
            headers: MutableMap<String?, MutableList<String?>?>,
            credentials: Credentials
        ): FetchBlobRequest {
            val requestBuilder: FetchBlobRequest.Builder =
                FetchBlobRequest.newBuilder()
                    .setInstanceName(instanceName)
                    .setDigestFunction(digestFunction)
            for (i in urls.indices) {
                val url = urls.get(i)
                requestBuilder.addUris(url.toString())

                if (!remoteDownloaderPropagateCredentials) {
                    continue
                }

                val metadata = credentials.getRequestMetadata(url)
                for (entry in metadata.entrySet()) {
                    for (value in entry.getValue()) {
                        requestBuilder.addQualifiers(
                            Qualifier.newBuilder()
                                .setName(QUALIFIER_HTTP_HEADER_URL_PREFIX + i + ":" + entry.getKey())
                                .setValue(value)
                                .build()
                        )
                    }
                }
            }

            if (checksum.isPresent()) {
                requestBuilder.addQualifiers(
                    Qualifier.newBuilder()
                        .setName(QUALIFIER_CHECKSUM_SRI)
                        .setValue(checksum.get().toSubresourceIntegrity())
                        .build()
                )
            } else {
                // If no checksum is provided, never accept cached content.
                // Timestamp is offset by an hour to account for clock skew.
                requestBuilder.setOldestContentAccepted(
                    Timestamps.fromMillis(
                        BlazeClock.instance().now()!!.plus(Duration.ofHours(1)).toEpochMilli()
                    )
                )
            }

            if (!Strings.isNullOrEmpty(canonicalId)) {
                requestBuilder.addQualifiers(
                    Qualifier.newBuilder().setName(QUALIFIER_CANONICAL_ID).setValue(canonicalId).build()
                )
            }

            for (entry in headers.entrySet()) {
                // https://www.rfc-editor.org/rfc/rfc9110.html#name-field-order permits
                // merging the field-values with a comma.
                requestBuilder.addQualifiers(
                    Qualifier.newBuilder()
                        .setName(QUALIFIER_HTTP_HEADER_PREFIX + entry.getKey())
                        .setValue(String.join(",", entry.getValue()))
                        .build()
                )
            }

            return requestBuilder.build()
        }
    }
}
