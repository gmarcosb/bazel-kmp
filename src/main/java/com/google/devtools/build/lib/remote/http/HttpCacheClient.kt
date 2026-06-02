// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.http

import build.bazel.remote.execution.v2.ActionCacheUpdateCapabilities
import com.google.auth.Credentials
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.google.common.io.Closeables
import com.google.common.util.concurrent.*
import com.google.devtools.build.lib.remote.common.RemoteCacheClient
import com.google.devtools.build.lib.remote.util.Utils
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelOption
import io.netty.channel.ChannelPipeline
import io.netty.channel.EventLoopGroup
import io.netty.channel.pool.ChannelPool
import io.netty.channel.pool.ChannelPoolHandler
import io.netty.channel.pool.FixedChannelPool
import io.netty.channel.pool.SimpleChannelPool
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.channel.unix.DomainSocketAddress
import io.netty.handler.codec.http.*
import io.netty.handler.ssl.*
import io.netty.handler.stream.ChunkedWriteHandler
import io.netty.handler.timeout.ReadTimeoutException
import io.netty.handler.timeout.WriteTimeoutException
import io.netty.util.concurrent.Future
import io.netty.util.concurrent.GenericFutureListener
import io.netty.util.concurrent.Promise
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketAddress
import java.net.URI
import java.util.*
import java.util.function.BiFunction
import java.util.function.Function
import java.util.regex.Pattern
import javax.annotation.concurrent.GuardedBy

/**
 * Implementation of [RemoteCacheClient] that can talk to a HTTP/1.1 backend.
 * 
 * 
 * Blobs (Binary large objects) are uploaded using the `PUT` method. Action cache blobs are
 * stored under the path `/ac/base16-key`. CAS (Content Addressable Storage) blobs are stored
 * under the path `/cas/base16-key`. Valid status codes for a successful upload are 200 (OK),
 * 201 (CREATED), 202 (ACCEPTED) and 204 (NO CONTENT). It's recommended to return 200 (OK) on
 * success. The other status codes are supported to be compatibility with the nginx webdav module
 * and may be removed in the future.
 * 
 * 
 * Blobs are downloaded using the `GET` method at the paths they were stored at. A status
 * code of 200 should be followed by the content of the blob. The status codes 404 (NOT FOUND) and
 * 204 (NO CONTENT) indicate that no cache entry exists. It's recommended to return 404 (NOT FOUND)
 * as the 204 (NO CONTENT) status code is only supported for compatibility with the nginx webdav
 * module.
 * 
 * 
 * TLS is supported and enabled automatically when using HTTPS as the URI scheme.
 * 
 * 
 * Uploads do not use `Expect: 100-CONTINUE` headers, as this would incur an additional
 * roundtrip for every upload and with little practical value as we would expect most uploads to be
 * accepted.
 * 
 * 
 * The implementation currently does not support transfer encoding chunked.
 */
class HttpCacheClient private constructor(
    newEventLoopGroup: Function<Int?, EventLoopGroup>,
    channelClass: Class<out Channel?>?,
    uri: URI,
    timeoutSeconds: Int,
    remoteMaxConnections: Int,
    verifyDownloads: Boolean,
    extraHttpHeaders: ImmutableList<MutableMap.MutableEntry<String?, String?>?>?,
    digestUtil: DigestUtil,
    retrier: RemoteRetrier,
    creds: Credentials?,
    authAndTlsOptions: AuthAndTLSOptions,
    socketAddress: SocketAddress?
) : RemoteCacheClient() {
    private val eventLoop: EventLoopGroup
    private val channelPool: ChannelPool
    private val uri: URI
    private val timeoutSeconds: Int
    private val extraHttpHeaders: ImmutableList<MutableMap.MutableEntry<String?, String?>?>?
    private val useTls: Boolean
    private val verifyDownloads: Boolean
    private val digestUtil: DigestUtil
    private val retrier: RemoteRetrier

    private val closeLock = Any()

    @GuardedBy("closeLock")
    private var isClosed = false

    private val credentialsLock = Any()

    @GuardedBy("credentialsLock")
    private val creds: Credentials?

    @GuardedBy("credentialsLock")
    private var lastRefreshTime: Long = 0

    init {
        var uri = uri
        var socketAddress = socketAddress
        useTls = uri.getScheme() == "https"
        if (uri.getPort() == -1) {
            val port = if (useTls) 443 else 80
            uri =
                URI(
                    uri.getScheme(),
                    uri.getUserInfo(),
                    uri.getHost(),
                    port,
                    uri.getPath(),
                    uri.getQuery(),
                    uri.getFragment()
                )
        }
        this.uri = uri
        if (socketAddress == null) {
            socketAddress = InetSocketAddress(uri.getHost(), uri.getPort())
        }

        val sslCtx: SslContext? = if (useTls) createSSLContext(authAndTlsOptions) else null
        val port = uri.getPort()
        val hostname = uri.getHost()
        this.eventLoop = newEventLoopGroup.apply(2)
        val clientBootstrap =
            Bootstrap()
                .channel(channelClass)
                .option<Int?>(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000 * timeoutSeconds)
                .group(eventLoop)
                .remoteAddress(socketAddress)

        val channelPoolHandler: ChannelPoolHandler =
            object : ChannelPoolHandler() {
                override fun channelReleased(ch: Channel?) {}

                override fun channelAcquired(ch: Channel?) {}

                override fun channelCreated(ch: Channel) {
                    val p = ch.pipeline()
                    if (sslCtx != null) {
                        val engine: SSLEngine = sslCtx.newEngine(ch.alloc(), hostname, port)
                        engine.setUseClientMode(true)
                        if (authAndTlsOptions.tlsClientCertificate != null
                            && authAndTlsOptions.tlsClientKey != null
                        ) {
                            engine.setNeedClientAuth(true)
                        }
                        p.addFirst("ssl-handler", SslHandler(engine))
                    }
                }
            }
        if (remoteMaxConnections > 0) {
            channelPool = FixedChannelPool(clientBootstrap, channelPoolHandler, remoteMaxConnections)
        } else {
            channelPool = SimpleChannelPool(clientBootstrap, channelPoolHandler)
        }
        this.creds = creds
        this.timeoutSeconds = timeoutSeconds
        this.extraHttpHeaders = extraHttpHeaders
        this.verifyDownloads = verifyDownloads
        this.digestUtil = digestUtil
        this.retrier = retrier
    }

    private fun acquireUploadChannel(): Promise<Channel?> {
        val channelReady = eventLoop.next().newPromise<Channel?>()
        channelPool
            .acquire()
            .addListener(
                GenericFutureListener { channelAcquired: Future<Channel>? ->
                    if (!channelAcquired!!.isSuccess()) {
                        channelReady.setFailure(channelAcquired.cause())
                        return@addListener
                    }
                    try {
                        val channel = channelAcquired.getNow()
                        val pipeline = channel.pipeline()

                        if (!isChannelPipelineEmpty(pipeline)) {
                            channelReady.setFailure(
                                IllegalStateException("Channel pipeline is not empty.")
                            )
                            return@addListener
                        }

                        pipeline.addFirst(
                            "timeout-handler",
                            IdleTimeoutHandler(timeoutSeconds.toLong(), WriteTimeoutException.INSTANCE)
                        )
                        pipeline.addLast(HttpResponseDecoder())
                        // The 10KiB limit was chosen arbitrarily. We only expect HTTP servers to respond
                        // with an error message in the body, and that should always be less than 10KiB. If
                        // the response is larger than 10KiB, HttpUploadHandler will catch the
                        // TooLongFrameException that HttpObjectAggregator throws and convert it to an
                        // IOException.
                        pipeline.addLast(HttpObjectAggregator(10 * 1024))
                        pipeline.addLast(HttpRequestEncoder())
                        pipeline.addLast(ChunkedWriteHandler())
                        synchronized(credentialsLock) {
                            pipeline.addLast(HttpUploadHandler(creds, extraHttpHeaders))
                        }

                        if (!channel.eventLoop().inEventLoop()) {
                            // If addLast is called outside an event loop, then it doesn't complete until the
                            // event loop is run again. In that case, a message sent to the last handler gets
                            // delivered to the last non-pending handler, which will most likely end up
                            // throwing UnsupportedMessageTypeException. Therefore, we only complete the
                            // promise in the event loop.
                            channel.eventLoop().execute(Runnable { channelReady.setSuccess(channel) })
                        } else {
                            channelReady.setSuccess(channel)
                        }
                    } catch (t: Throwable) {
                        channelReady.setFailure(t)
                    }
                })
        return channelReady
    }

    private fun releaseUploadChannel(ch: Channel) {
        if (ch.isOpen()) {
            try {
                ch.pipeline().remove<IdleTimeoutHandler?>(IdleTimeoutHandler::class.java)
                ch.pipeline().remove<HttpResponseDecoder?>(HttpResponseDecoder::class.java)
                ch.pipeline().remove<HttpObjectAggregator?>(HttpObjectAggregator::class.java)
                ch.pipeline().remove<HttpRequestEncoder?>(HttpRequestEncoder::class.java)
                ch.pipeline().remove<ChunkedWriteHandler?>(ChunkedWriteHandler::class.java)
                ch.pipeline().remove<HttpUploadHandler?>(HttpUploadHandler::class.java)
            } catch (e: NoSuchElementException) {
                // If the channel is in the process of closing but not yet closed, some handlers could have
                // been removed and would cause NoSuchElement exceptions to be thrown. Because handlers are
                // removed in reverse-order, if we get a NoSuchElement exception, the following handlers
                // should have been removed.
            }
        }
        channelPool.release(ch)
    }

    private fun acquireDownloadChannel(): Future<Channel?> {
        val channelReady = eventLoop.next().newPromise<Channel?>()
        channelPool
            .acquire()
            .addListener(
                GenericFutureListener { channelAcquired: Future<Channel>? ->
                    if (!channelAcquired!!.isSuccess()) {
                        channelReady.setFailure(channelAcquired.cause())
                        return@addListener
                    }
                    try {
                        val channel = channelAcquired.getNow()
                        val pipeline = channel.pipeline()

                        if (!isChannelPipelineEmpty(pipeline)) {
                            channelReady.setFailure(
                                IllegalStateException("Channel pipeline is not empty.")
                            )
                            return@addListener
                        }
                        pipeline.addFirst(
                            "timeout-handler",
                            IdleTimeoutHandler(timeoutSeconds.toLong(), ReadTimeoutException.INSTANCE)
                        )
                        pipeline.addLast(HttpClientCodec())
                        pipeline.addLast("inflater", HttpContentDecompressor())
                        synchronized(credentialsLock) {
                            pipeline.addLast(HttpDownloadHandler(creds, extraHttpHeaders))
                        }

                        if (!channel.eventLoop().inEventLoop()) {
                            // If addLast is called outside an event loop, then it doesn't complete until the
                            // event loop is run again. In that case, a message sent to the last handler gets
                            // delivered to the last non-pending handler, which will most likely end up
                            // throwing UnsupportedMessageTypeException. Therefore, we only complete the
                            // promise in the event loop.
                            channel.eventLoop().execute(Runnable { channelReady.setSuccess(channel) })
                        } else {
                            channelReady.setSuccess(channel)
                        }
                    } catch (t: Throwable) {
                        channelReady.setFailure(t)
                    }
                })

        return channelReady
    }

    private fun releaseDownloadChannel(ch: Channel) {
        if (ch.isOpen()) {
            // The channel might have been closed due to an error, in which case its pipeline
            // has already been cleared. Closed channels can't be reused.
            try {
                ch.pipeline().remove<IdleTimeoutHandler?>(IdleTimeoutHandler::class.java)
                ch.pipeline().remove<HttpClientCodec?>(HttpClientCodec::class.java)
                ch.pipeline().remove<HttpContentDecompressor?>(HttpContentDecompressor::class.java)
                ch.pipeline().remove<HttpDownloadHandler?>(HttpDownloadHandler::class.java)
            } catch (e: NoSuchElementException) {
                // If the channel is in the process of closing but not yet closed, some handlers could have
                // been removed and would cause NoSuchElement exceptions to be thrown. Because handlers are
                // removed in reverse-order, if we get a NoSuchElement exception, the following handlers
                // should have been removed.
            }
        }
        channelPool.release(ch)
    }

    private fun isChannelPipelineEmpty(pipeline: ChannelPipeline): Boolean {
        return (pipeline.first() == null)
                || (useTls
                && "ssl-handler" == pipeline.firstContext().name()
                && pipeline.first() === pipeline.last())
    }

    override fun downloadBlob(
        context: RemoteActionExecutionContext?, digest: Digest, out: OutputStream
    ): ListenableFuture<Void?> {
        val digestOut =
            if (verifyDownloads) digestUtil.newDigestOutputStream(out) else null
        val casBytesDownloaded: AtomicLong = AtomicLong()
        return Futures.transformAsync<Void?, Void?>(
            retrier.executeAsync<Void?>(
                AsyncCallable {
                    get(
                        digest,
                        if (digestOut != null) digestOut else out,
                        Optional.of<AtomicLong?>(casBytesDownloaded)
                    )
                }),
            AsyncFunction { v: Void? ->
                try {
                    if (digestOut != null) {
                        Utils.verifyBlobContents(digest, digestOut.digest())
                    }
                    out.flush()
                    return@transformAsync Futures.immediateFuture<Void?>(null)
                } catch (e: IOException) {
                    return@transformAsync Futures.immediateFailedFuture<Void?>(e)
                }
            },
            MoreExecutors.directExecutor()
        )
    }

    private fun get(
        digest: Digest?, out: OutputStream, casBytesDownloaded: Optional<AtomicLong?>
    ): ListenableFuture<Void?> {
        val dataWritten: AtomicBoolean = AtomicBoolean()
        val wrappedOut: OutputStream =
            object : OutputStream() {
                // OutputStream.close() does nothing, which is what we want to ensure that the
                // OutputStream can't be closed somewhere in the Netty pipeline, so that we can support
                // retries. The OutputStream is closed in the finally block below.
                @Throws(IOException::class)
                override fun write(b: ByteArray?, offset: Int, length: Int) {
                    dataWritten.set(true)
                    if (casBytesDownloaded.isPresent()) {
                        casBytesDownloaded.get().addAndGet(length.toLong())
                    }
                    out.write(b, offset, length)
                }

                @Throws(IOException::class)
                override fun write(b: Int) {
                    dataWritten.set(true)
                    if (casBytesDownloaded.isPresent()) {
                        casBytesDownloaded.get().incrementAndGet()
                    }
                    out.write(b)
                }

                @Throws(IOException::class)
                override fun flush() {
                    out.flush()
                }
            }
        var offset: Long = 0
        if (casBytesDownloaded.isPresent()) {
            offset = casBytesDownloaded.get().get()
        }
        val downloadCmd =
            DownloadCommand(uri, casBytesDownloaded.isPresent(), digest, wrappedOut, offset)
        val outerF = SettableFuture.create<Void?>()
        acquireDownloadChannel()
            .addListener(
                GenericFutureListener { channelPromise: Future<Channel>? ->
                    if (!channelPromise!!.isSuccess()) {
                        outerF.setException(channelPromise.cause())
                        return@addListener
                    }
                    val ch = channelPromise.getNow()
                    ch.writeAndFlush(downloadCmd)
                        .addListener(
                            GenericFutureListener { f: Future<in Void?>? ->
                                try {
                                    if (f!!.isSuccess()) {
                                        outerF.set(null)
                                    } else {
                                        val cause = f.cause()
                                        // cause can be of type HttpException, because Netty uses
                                        // Unsafe.throwException to
                                        // re-throw a checked exception that hasn't been declared in the method
                                        // signature.
                                        if (cause is HttpException) {
                                            val response = cause.response()
                                            if (!dataWritten.get() && authTokenExpired(response)) {
                                                // The error is due to an auth token having expired. Let's try
                                                // again.
                                                try {
                                                    refreshCredentials()
                                                    getAfterCredentialRefresh(downloadCmd, outerF)
                                                    return@addListener
                                                } catch (e: IOException) {
                                                    cause.addSuppressed(e)
                                                } catch (e: RuntimeException) {
                                                    logger.atWarning().withCause(e).log("Unexpected exception")
                                                    cause.addSuppressed(e)
                                                }
                                            } else if (cacheMiss(response.status())) {
                                                outerF.setException(CacheNotFoundException(digest))
                                                return@addListener
                                            }
                                        }
                                        outerF.setException(cause)
                                    }
                                } finally {
                                    releaseDownloadChannel(ch)
                                }
                            })
                })
        return outerF
    }

    private fun getAfterCredentialRefresh(cmd: DownloadCommand, outerF: SettableFuture<Void?>) {
        acquireDownloadChannel()
            .addListener(
                GenericFutureListener { channelPromise: Future<Channel>? ->
                    if (!channelPromise!!.isSuccess()) {
                        outerF.setException(channelPromise.cause())
                        return@addListener
                    }
                    val ch = channelPromise.getNow()
                    ch.writeAndFlush(cmd)
                        .addListener(
                            GenericFutureListener { f: Future<in Void?>? ->
                                try {
                                    if (f!!.isSuccess()) {
                                        outerF.set(null)
                                    } else {
                                        val cause = f.cause()
                                        if (cause is HttpException) {
                                            val response = cause.response()
                                            if (cacheMiss(response.status())) {
                                                outerF.setException(CacheNotFoundException(cmd.digest()))
                                                return@addListener
                                            }
                                        }
                                        outerF.setException(cause)
                                    }
                                } finally {
                                    releaseDownloadChannel(ch)
                                }
                            })
                })
    }

    val serverCapabilities: ServerCapabilities
        get() {
            val cacheCapabilities: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                CacheCapabilities.newBuilder()
                    .setActionCacheUpdateCapabilities(
                        ActionCacheUpdateCapabilities.newBuilder().setUpdateEnabled(true).build()
                    )
                    .setSymlinkAbsolutePathStrategy(SymlinkAbsolutePathStrategy.Value.ALLOWED)
                    .build()
            return ServerCapabilities.newBuilder().setCacheCapabilities(cacheCapabilities).build()
        }

    val authority: ListenableFuture<String?>
        get() = Futures.immediateFuture<String?>("")

    override fun downloadActionResult(
        context: RemoteActionExecutionContext?,
        actionKey: ActionKey,
        inlineOutErr: Boolean,
        inlineOutputFiles: MutableSet<String?>?
    ): ListenableFuture<ActionResult?>? {
        return retrier.executeAsync<ActionResult?>(
            AsyncCallable {
                Utils.downloadAsActionResult(
                    actionKey,
                    BiFunction { digest: Digest?, out: OutputStream? ->
                        get(
                            digest, out!!,  /* casBytesDownloaded= */
                            Optional.empty<AtomicLong?>()
                        )
                    })
            })
    }

    private fun uploadAsync(
        key: String?, length: Long, `in`: InputStream, casUpload: Boolean
    ): ListenableFuture<Void?> {
        val wrappedIn: InputStream =
            object : FilterInputStream(`in`) {
                override fun close() {
                    // Ensure that the InputStream can't be closed somewhere in the Netty
                    // pipeline, so that we can support retries. The InputStream is closed in
                    // the listener block below.
                }
            }
        val upload = UploadCommand(uri, casUpload, key, wrappedIn, length)
        val result = SettableFuture.create<Void?>()
        acquireUploadChannel()
            .addListener(
                GenericFutureListener { channelPromise: Future<Channel>? ->
                    if (!channelPromise!!.isSuccess()) {
                        result.setException(channelPromise.cause())
                        return@addListener
                    }
                    val ch = channelPromise.getNow()
                    ch.writeAndFlush(upload)
                        .addListener(
                            GenericFutureListener { f: Future<in Void?>? ->
                                releaseUploadChannel(ch)
                                if (f!!.isSuccess()) {
                                    result.set(null)
                                } else {
                                    val cause = f.cause()
                                    if (cause is HttpException) {
                                        val response = cause.response()
                                        try {
                                            // If the error is due to an expired auth token and we can reset
                                            // the input stream, then try again.
                                            if (authTokenExpired(response) && reset(`in`)) {
                                                try {
                                                    refreshCredentials()
                                                    uploadAfterCredentialRefresh(upload, result)
                                                } catch (e: IOException) {
                                                    result.setException(e)
                                                } catch (e: RuntimeException) {
                                                    logger.atWarning().withCause(e).log("Unexpected exception")
                                                    result.setException(e)
                                                }
                                            } else {
                                                result.setException(cause)
                                            }
                                        } catch (e: IOException) {
                                            result.setException(e)
                                        }
                                    } else {
                                        result.setException(cause)
                                    }
                                }
                            })
                })
        result.addListener(Runnable { Closeables.closeQuietly(`in`) }, MoreExecutors.directExecutor())
        return result
    }

    private fun uploadAfterCredentialRefresh(upload: UploadCommand?, result: SettableFuture<Void?>) {
        acquireUploadChannel()
            .addListener(
                GenericFutureListener { channelPromise: Future<Channel>? ->
                    if (!channelPromise!!.isSuccess()) {
                        result.setException(channelPromise.cause())
                        return@addListener
                    }
                    val ch = channelPromise.getNow()
                    ch.writeAndFlush(upload)
                        .addListener(
                            GenericFutureListener { f: Future<in Void?>? ->
                                releaseUploadChannel(ch)
                                if (f!!.isSuccess()) {
                                    result.set(null)
                                } else {
                                    result.setException(f.cause())
                                }
                            })
                })
    }

    override fun uploadBlobImpl(
        context: RemoteActionExecutionContext?, digest: Digest, blob: RemoteCacheClient.Blob
    ): ListenableFuture<Void?>? {
        return retrier.executeAsync<Void?>(
            AsyncCallable {
                uploadAsync(
                    digest.getHash(), digest.getSizeBytes(), blob.get(),  /* casUpload= */true
                )
            })
    }

    override fun findMissingDigests(
        context: RemoteActionExecutionContext?, digests: Iterable<Digest?>
    ): ListenableFuture<ImmutableSet<Digest?>?> {
        return Futures.immediateFuture<ImmutableSet<Digest?>?>(ImmutableSet.copyOf<Digest?>(digests))
    }

    @Throws(IOException::class)
    private fun reset(`in`: InputStream): Boolean {
        if (`in`.markSupported()) {
            `in`.reset()
            return true
        }
        if (`in` is FileInputStream) {
            // FileInputStream does not support reset().
            `in`.getChannel().position(0)
            return true
        }
        return false
    }

    override fun uploadActionResult(
        context: RemoteActionExecutionContext?, actionKey: ActionKey, actionResult: ActionResult
    ): ListenableFuture<Void?> {
        val serialized: ByteString = actionResult.toByteString()
        return uploadAsync(
            actionKey.digest.getHash(),
            serialized.size().toLong(),
            serialized.newInput(),  /* casUpload= */
            false
        )
    }

    /**
     * It's safe to suppress this warning because all methods on Netty futures return `this`. So
     * we are not ignoring anything.
     */
    override fun close() {
        synchronized(closeLock) {
            if (isClosed) {
                return
            }
            isClosed = true

            // Clear interrupted status to prevent failure to close, indicated with #14787
            val wasInterrupted = Thread.interrupted()
            try {
                channelPool.close()
            } catch (e: RuntimeException) {
                if (e.getCause() is InterruptedException) {
                    Thread.currentThread().interrupt()
                } else {
                    throw e
                }
            } finally {
                if (wasInterrupted) {
                    Thread.currentThread().interrupt()
                }
            }
            eventLoop.shutdownGracefully()
        }
    }

    private fun cacheMiss(status: HttpResponseStatus): Boolean {
        // Supporting NO_CONTENT for nginx webdav compatibility.
        return status == HttpResponseStatus.NOT_FOUND
                || status == HttpResponseStatus.NO_CONTENT
    }

    /** See https://tools.ietf.org/html/rfc6750#section-3.1  */
    private fun authTokenExpired(response: HttpResponse): Boolean {
        synchronized(credentialsLock) {
            if (creds == null) {
                return false
            }
        }
        val values = response.headers().getAllAsString(HttpHeaderNames.WWW_AUTHENTICATE)
        val value = java.lang.String.join(",", values)
        if (value != null && value.startsWith("Bearer")) {
            return INVALID_TOKEN_ERROR.matcher(value).find()
        } else {
            return response.status() == HttpResponseStatus.UNAUTHORIZED
        }
    }

    @Throws(IOException::class)
    private fun refreshCredentials() {
        synchronized(credentialsLock) {
            val now = System.currentTimeMillis()
            // Call creds.refresh() at most once per second. The one second was arbitrarily chosen, as
            // a small enough value that we don't expect to interfere with actual token lifetimes, but
            // it should just make sure that potentially hundreds of threads don't call this method
            // at the same time.
            if ((now - lastRefreshTime) > TimeUnit.SECONDS.toMillis(1)) {
                lastRefreshTime = now
                creds!!.refresh()
            }
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        const val AC_PREFIX: String = "ac/"
        const val CAS_PREFIX: String = "cas/"
        private val INVALID_TOKEN_ERROR: Pattern = Pattern.compile("\\s*error\\s*=\\s*\"?invalid_token\"?")

        @Throws(Exception::class)
        fun create(
            uri: URI,
            timeoutSeconds: Int,
            remoteMaxConnections: Int,
            verifyDownloads: Boolean,
            extraHttpHeaders: ImmutableList<MutableMap.MutableEntry<String?, String?>?>?,
            digestUtil: DigestUtil,
            retrier: RemoteRetrier,
            creds: Credentials?,
            authAndTlsOptions: AuthAndTLSOptions
        ): HttpCacheClient {
            return HttpCacheClient(
                Function { nThreads: Int? -> NioEventLoopGroup(nThreads) },
                NioSocketChannel::class.java,
                uri,
                timeoutSeconds,
                remoteMaxConnections,
                verifyDownloads,
                extraHttpHeaders,
                digestUtil,
                retrier,
                creds,
                authAndTlsOptions,
                null
            )
        }

        @Throws(Exception::class)
        fun create(
            domainSocketAddress: DomainSocketAddress?,
            uri: URI,
            timeoutSeconds: Int,
            remoteMaxConnections: Int,
            verifyDownloads: Boolean,
            extraHttpHeaders: ImmutableList<MutableMap.MutableEntry<String?, String?>?>?,
            digestUtil: DigestUtil,
            retrier: RemoteRetrier,
            creds: Credentials?,
            authAndTlsOptions: AuthAndTLSOptions
        ): HttpCacheClient {
            if (KQueue.isAvailable()) {
                return HttpCacheClient(
                    Function { nThreads: Int? -> KQueueEventLoopGroup(nThreads) },
                    KQueueDomainSocketChannel::class.java,
                    uri,
                    timeoutSeconds,
                    remoteMaxConnections,
                    verifyDownloads,
                    extraHttpHeaders,
                    digestUtil,
                    retrier,
                    creds,
                    authAndTlsOptions,
                    domainSocketAddress
                )
            } else if (Epoll.isAvailable()) {
                return HttpCacheClient(
                    Function { nThreads: Int? -> EpollEventLoopGroup(nThreads) },
                    EpollDomainSocketChannel::class.java,
                    uri,
                    timeoutSeconds,
                    remoteMaxConnections,
                    verifyDownloads,
                    extraHttpHeaders,
                    digestUtil,
                    retrier,
                    creds,
                    authAndTlsOptions,
                    domainSocketAddress
                )
            } else {
                throw Exception("Unix domain sockets are unsupported on this platform")
            }
        }

        @Throws(IOException::class)
        private fun createSSLContext(authAndTlsOptions: AuthAndTLSOptions): SslContext {
            // OpenSsl gives us a > 2x speed improvement on fast networks, but requires netty tcnative
            // to be there which is not available on all platforms and environments.
            val sslProvider = if (OpenSsl.isAvailable()) SslProvider.OPENSSL else SslProvider.JDK
            var sslContextBuilder = SslContextBuilder.forClient().sslProvider(sslProvider)

            // Root CA certificate
            if (authAndTlsOptions.tlsCertificate != null) {
                sslContextBuilder =
                    sslContextBuilder.trustManager(File(authAndTlsOptions.tlsCertificate))
            }

            // Optional client TLS authentication
            if (authAndTlsOptions.tlsClientCertificate != null
                && authAndTlsOptions.tlsClientKey != null
            ) {
                sslContextBuilder =
                    sslContextBuilder.keyManager(
                        File(authAndTlsOptions.tlsClientCertificate),
                        File(authAndTlsOptions.tlsClientKey)
                    )
            }

            return sslContextBuilder.build()
        }
    }
}
