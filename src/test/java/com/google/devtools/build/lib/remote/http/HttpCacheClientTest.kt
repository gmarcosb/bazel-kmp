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

import com.google.auth.Credentials
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.google.common.util.concurrent.MoreExecutors
import com.google.devtools.build.lib.remote.util.Utils.getFromFuture
import com.google.devtools.common.options.Options
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.unix.DomainSocketAddress
import io.netty.handler.codec.TooLongFrameException
import io.netty.handler.codec.http.*
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import org.junit.runners.Parameterized
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ConnectException
import java.net.SocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.Map
import java.util.function.IntFunction
import java.util.function.Supplier
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.collections.MutableMap

/** Tests for [HttpCacheClient].  */
@RunWith(Parameterized::class)
class HttpCacheClientTest(private val testServer: TestServer) {
    private var remoteActionExecutionContext: RemoteActionExecutionContext? = null

    internal interface TestServer {
        fun start(handler: ChannelInboundHandler?): ServerChannel

        fun stop(serverChannel: ServerChannel?)
    }

    private class InetTestServer : TestServer {
        override fun start(handler: ChannelInboundHandler?): ServerChannel? {
            return createServer(
                NioServerSocketChannel::class.java,
                IntFunction { nThreads: Int -> NioEventLoopGroup(nThreads) },
                InetSocketAddress("localhost", 0),
                handler
            )
        }

        override fun stop(serverChannel: ServerChannel) {
            try {
                serverChannel.close()
                serverChannel.closeFuture().sync()
                serverChannel.eventLoop().shutdownGracefully().sync()
            } catch (e: Exception) {
                throw IllegalStateException(e)
            }
        }
    }

    private class UnixDomainServer(
        serverChannelClass: Class<out ServerChannel?>?,
        newEventLoopGroup: IntFunction<EventLoopGroup?>
    ) : TestServer {
        // Note: this odd implementation is a workaround because we're unable to shut down and restart
        // KQueue backed implementations. See https://github.com/netty/netty/issues/7047.
        private val serverChannel: ServerChannel
        private var handler: ChannelInboundHandler? = null

        init {
            val eventLoop = newEventLoopGroup.apply(1)
            val sb =
                ServerBootstrap()
                    .group(eventLoop)
                    .channel(serverChannelClass)
                    .childHandler(
                        object : ChannelInitializer<Channel?>() {
                            override fun initChannel(ch: Channel) {
                                ch.pipeline().addLast(HttpServerCodec())
                                ch.pipeline().addLast(HttpObjectAggregator(1000))
                                ch.pipeline().addLast(Preconditions.checkNotNull<ChannelInboundHandler?>(handler))
                            }
                        })
            try {
                val actual = (sb.bind(newDomainSocketAddress()).sync().channel() as ServerChannel?)
                this.serverChannel =
                    Mockito.mock<ServerChannel>(ServerChannel::class.java, AdditionalAnswers.delegatesTo<Any?>(actual))
            } catch (e: Exception) {
                throw IllegalStateException(e)
            }
        }

        override fun start(handler: ChannelInboundHandler?): ServerChannel {
            Mockito.reset<ServerChannel?>(this.serverChannel)
            this.handler = handler
            return this.serverChannel
        }

        override fun stop(serverChannel: ServerChannel?) {
            // Note: In the tests, we expect that connecting to a closed server channel results
            // in a channel connection error. Netty doesn't seem to handle closing domain socket
            // addresses very well-- often connecting to a closed domain socket will result in a
            // read timeout instead of a connection timeout.
            //
            // This is a hack to ensure connection timeouts are "received" by the tests for this
            // dummy domain socket server. In particular, this lets the timeoutShouldWork_connect
            // test work for both inet and domain sockets.
            //
            // This is also part of the workaround for https://github.com/netty/netty/issues/7047.
            Mockito.`when`<SocketAddress?>(this.serverChannel.localAddress()).thenReturn(DomainSocketAddress(""))
            this.handler = null
        }
    }

    @Throws(Exception::class)
    private fun createHttpBlobStore(
        serverChannel: ServerChannel,
        timeoutSeconds: Int,
        remoteVerifyDownloads: Boolean,
        extraHttpHeaders: ImmutableList<MutableMap.MutableEntry<String?, String?>?>?,
        creds: Credentials?,
        authAndTlsOptions: AuthAndTLSOptions?,
        optRetrier: Optional<RemoteRetrier>
    ): HttpCacheClient {
        val socketAddress = serverChannel.localAddress()
        val retrier: RemoteRetrier =
            optRetrier.orElseGet(
                Supplier {
                    val retryScheduler =
                        MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1))
                    RemoteRetrier(
                        { RemoteRetrier.RETRIES_DISABLED },
                        { e -> Result.SUCCESS },
                        retryScheduler,
                        Retrier.ALLOW_ALL_CALLS
                    )
                })
        if (socketAddress is DomainSocketAddress) {
            val uri = URI("http://localhost")
            return HttpCacheClient.create(
                socketAddress,
                uri,
                timeoutSeconds,  /* remoteMaxConnections= */
                0,
                remoteVerifyDownloads,
                extraHttpHeaders,
                DIGEST_UTIL,
                retrier,
                creds,
                authAndTlsOptions
            )
        } else if (socketAddress is InetSocketAddress) {
            val uri = URI("http://localhost:" + socketAddress.getPort())
            return HttpCacheClient.create(
                uri,
                timeoutSeconds,  /* remoteMaxConnections= */
                0,
                remoteVerifyDownloads,
                extraHttpHeaders,
                DIGEST_UTIL,
                retrier,
                creds,
                authAndTlsOptions
            )
        } else {
            throw IllegalStateException(
                "unsupported socket address class " + socketAddress.javaClass
            )
        }
    }

    @Throws(Exception::class)
    private fun createHttpBlobStore(
        serverChannel: ServerChannel,
        timeoutSeconds: Int,
        remoteVerifyDownloads: Boolean,
        creds: Credentials?,
        authAndTlsOptions: AuthAndTLSOptions?,
        optRetrier: Optional<RemoteRetrier>
    ): HttpCacheClient {
        return createHttpBlobStore(
            serverChannel,
            timeoutSeconds,
            remoteVerifyDownloads,
            ImmutableList.of<MutableMap.MutableEntry<String?, String?>?>(),
            creds,
            authAndTlsOptions,
            optRetrier
        )
    }

    @Throws(Exception::class)
    private fun createHttpBlobStore(
        serverChannel: ServerChannel,
        timeoutSeconds: Int,
        creds: Credentials?,
        authAndTlsOptions: AuthAndTLSOptions?
    ): HttpCacheClient {
        return createHttpBlobStore(
            serverChannel,
            timeoutSeconds,  /* remoteVerifyDownloads= */
            true,
            creds,
            authAndTlsOptions,
            Optional.empty<RemoteRetrier?>()
        )
    }

    @Before
    @Throws(Exception::class)
    fun setUp() {
        remoteActionExecutionContext =
            RemoteActionExecutionContext.create(
                < T > mock < T ? > (Spawn::class.java),
        <T > mock<T?>(SpawnExecutionContext::class.java),
        TracingMetadataUtils.buildMetadata(
            "none", "none", Digest.getDefaultInstance().getHash(), null
        ))
    }

    @Test
    @Throws(Exception::class)
    fun testUpload() {
        var server: ServerChannel? = null
        try {
            val cacheContents: ConcurrentHashMap<String?, ByteArray?> = ConcurrentHashMap<String?, ByteArray?>()
            server = testServer.start(InMemoryHttpCacheServerHandler(cacheContents))

            val blobStore =
                createHttpBlobStore(
                    server,  /* timeoutSeconds= */
                    1,  /* creds= */
                    null,
                    Options.getDefaults<O?>(AuthAndTLSOptions::class.java)
                )

            val data: ByteString = ByteString.copyFrom("foo bar", StandardCharsets.UTF_8)
            val digest: Digest = DIGEST_UTIL.compute(data.toByteArray())
            blobStore.uploadBlob(remoteActionExecutionContext, digest, data,  /* force= */false).get()

            Truth.assertThat(cacheContents).hasSize(1)
            val cacheKey = "/cas/" + digest.getHash()
            Truth.assertThat(cacheContents).containsKey(cacheKey)
            Truth.assertThat(cacheContents.get(cacheKey)).isEqualTo(data.toByteArray())
        } finally {
            testServer.stop(server)
        }
    }

    @Test(timeout = 30000)
    @Throws(Exception::class)
    fun connectTimeout() {
        val server = testServer.start(object : ChannelInboundHandlerAdapter() {})
        testServer.stop(server)

        val credentials = newCredentials()
        val authAndTlsOptions: AuthAndTLSOptions? = Options.getDefaults<O?>(AuthAndTLSOptions::class.java)
        val blobStore =
            createHttpBlobStore(server,  /* timeoutSeconds= */1, credentials, authAndTlsOptions)
        Assert.assertThrows<ConnectException?>(
            ConnectException::class.java,
            ThrowingRunnable {
                getFromFuture(
                    blobStore.downloadBlob(
                        remoteActionExecutionContext, DIGEST, ByteArrayOutputStream()
                    )
                )
            })
    }

    @Test(timeout = 30000)
    @Throws(Exception::class)
    fun uploadTimeout() {
        var server: ServerChannel? = null
        try {
            server =
                testServer.start(
                    object : SimpleChannelInboundHandler<FullHttpRequest?>() {
                        override fun channelRead0(
                            channelHandlerContext: ChannelHandlerContext?, fullHttpRequest: FullHttpRequest?
                        ) {
                            // Don't respond and force a client timeout.
                        }
                    })

            val credentials = newCredentials()
            val authAndTlsOptions: AuthAndTLSOptions? = Options.getDefaults<O?>(AuthAndTLSOptions::class.java)
            val blobStore =
                createHttpBlobStore(server,  /* timeoutSeconds= */1, credentials, authAndTlsOptions)
            val data: ByteArray = "File Contents".toByteArray(StandardCharsets.US_ASCII)
            Assert.assertThrows<UploadTimeoutException?>(
                UploadTimeoutException::class.java,
                ThrowingRunnable {
                    getFromFuture(
                        blobStore.uploadBlob(
                            remoteActionExecutionContext,
                            DIGEST_UTIL.compute(data),
                            ByteString.copyFrom(data),  /* force= */
                            false
                        )
                    )
                })
        } finally {
            testServer.stop(server)
        }
    }

    @Test(timeout = 30000)
    @Throws(Exception::class)
    fun downloadTimeout() {
        var server: ServerChannel? = null
        try {
            server =
                testServer.start(
                    object : SimpleChannelInboundHandler<FullHttpRequest?>() {
                        override fun channelRead0(
                            channelHandlerContext: ChannelHandlerContext?, fullHttpRequest: FullHttpRequest?
                        ) {
                            // Don't respond and force a client timeout.
                        }
                    })

            val credentials = newCredentials()
            val authAndTlsOptions: AuthAndTLSOptions? = Options.getDefaults<O?>(AuthAndTLSOptions::class.java)
            val blobStore =
                createHttpBlobStore(server,  /* timeoutSeconds= */1, credentials, authAndTlsOptions)
            Assert.assertThrows<DownloadTimeoutException?>(
                DownloadTimeoutException::class.java,
                ThrowingRunnable {
                    getFromFuture(
                        blobStore.downloadBlob(
                            remoteActionExecutionContext, DIGEST, ByteArrayOutputStream()
                        )
                    )
                })
        } finally {
            testServer.stop(server)
        }
    }

    @Test
    @Throws(Exception::class)
    fun uploadResponseTooLarge() {
        var server: ServerChannel? = null
        try {
            server =
                testServer.start(
                    object : SimpleChannelInboundHandler<FullHttpRequest?>() {
                        override fun channelRead0(
                            channelHandlerContext: ChannelHandlerContext, request: FullHttpRequest?
                        ) {
                            val longMessage =
                                channelHandlerContext.alloc().buffer(50000).writerIndex(50000)
                            val response =
                                DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_1,
                                    HttpResponseStatus.INTERNAL_SERVER_ERROR,
                                    longMessage
                                )
                            channelHandlerContext
                                .writeAndFlush(response)
                                .addListener(ChannelFutureListener.CLOSE)
                        }
                    })

            val credentials = newCredentials()
            val authAndTlsOptions: AuthAndTLSOptions? = Options.getDefaults<O?>(AuthAndTLSOptions::class.java)
            val blobStore =
                createHttpBlobStore(server,  /* timeoutSeconds= */1, credentials, authAndTlsOptions)
            val data: ByteString = ByteString.copyFrom("File Contents", StandardCharsets.US_ASCII)
            val e: IOException =
                Assert.assertThrows<IOException>(
                    IOException::class.java,
                    ThrowingRunnable {
                        getFromFuture(
                            blobStore.uploadBlob(
                                remoteActionExecutionContext,
                                DIGEST_UTIL.compute(data.toByteArray()),
                                data,  /* force= */
                                false
                            )
                        )
                    })
            Truth.assertThat(e.cause).isInstanceOf(TooLongFrameException::class.java)
        } finally {
            testServer.stop(server)
        }
    }

    @Test
    @Throws(Exception::class)
    fun testDownloadFailsOnDigestMismatch() {
        // Test that the download fails when a blob/file has a different content hash than expected.

        var server: ServerChannel? = null
        try {
            server =
                testServer.start(
                    object : SimpleChannelInboundHandler<FullHttpRequest?>() {
                        override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest?) {
                            val data = ctx.alloc().buffer()
                            ByteBufUtil.writeUtf8(data, "bar")
                            val response =
                                DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK, data
                                )
                            HttpUtil.setContentLength(response, data.readableBytes().toLong())

                            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
                        }
                    })

            val credentials = newCredentials()
            val authAndTlsOptions: AuthAndTLSOptions? = Options.getDefaults<O?>(AuthAndTLSOptions::class.java)
            val blobStore =
                createHttpBlobStore(
                    server,  /* timeoutSeconds= */
                    1,  /* remoteVerifyDownloads= */
                    true,
                    credentials,
                    authAndTlsOptions,
                    Optional.empty<RemoteRetrier?>()
                )
            val fooDigest: Digest = DIGEST_UTIL.compute("foo".toByteArray(StandardCharsets.UTF_8))
            ByteArrayOutputStream().use { out ->
                val e: IOException? =
                    Assert.assertThrows<IOException?>(
                        IOException::class.java,
                        ThrowingRunnable {
                            getFromFuture(
                                blobStore.downloadBlob(remoteActionExecutionContext, fooDigest, out)
                            )
                        })
                Truth.assertThat(e).hasMessageThat().contains(fooDigest.getHash())
                Truth.assertThat(e).hasMessageThat().contains(DIGEST_UTIL.computeAsUtf8("bar").getHash())
            }
        } finally {
            testServer.stop(server)
        }
    }

    @Test
    @Throws(Exception::class)
    fun testDisablingDigestVerification() {
        // Test that when digest verification is disabled a corrupted download works.

        var server: ServerChannel? = null
        try {
            server =
                testServer.start(
                    object : SimpleChannelInboundHandler<FullHttpRequest?>() {
                        override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest?) {
                            val data = ctx.alloc().buffer()
                            ByteBufUtil.writeUtf8(data, "bar")
                            val response =
                                DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK, data
                                )
                            HttpUtil.setContentLength(response, data.readableBytes().toLong())

                            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
                        }
                    })

            val credentials = newCredentials()
            val authAndTlsOptions: AuthAndTLSOptions? = Options.getDefaults<O?>(AuthAndTLSOptions::class.java)
            val blobStore =
                createHttpBlobStore(
                    server,  /* timeoutSeconds= */
                    1,  /* remoteVerifyDownloads= */
                    false,
                    credentials,
                    authAndTlsOptions,
                    Optional.empty<RemoteRetrier?>()
                )
            val fooDigest: Digest = DIGEST_UTIL.compute("foo".toByteArray(StandardCharsets.UTF_8))
            ByteArrayOutputStream().use { out ->
                getFromFuture(blobStore.downloadBlob(remoteActionExecutionContext, fooDigest, out))
                Truth.assertThat(out.toByteArray()).isEqualTo("bar".toByteArray(StandardCharsets.UTF_8))
            }
        } finally {
            testServer.stop(server)
        }
    }

    @Test
    @Throws(Exception::class)
    fun partialDownloadFailsWithoutRetry() {
        var server: ServerChannel? = null
        try {
            val chunk1: ByteBuf = Unpooled.wrappedBuffer("File ".toByteArray(StandardCharsets.US_ASCII))
            val chunk2: ByteBuf = Unpooled.wrappedBuffer("Contents".toByteArray(StandardCharsets.US_ASCII))
            server = testServer.start(IntermittentFailureHandler(chunk1, chunk2))
            val credentials = newCredentials()
            val authAndTlsOptions: AuthAndTLSOptions? = Options.getDefaults<O?>(AuthAndTLSOptions::class.java)

            val blobStore =
                createHttpBlobStore(server,  /* timeoutSeconds= */1, credentials, authAndTlsOptions)
            Assert.assertThrows<ClosedChannelException?>(
                ClosedChannelException::class.java,
                ThrowingRunnable {
                    getFromFuture(
                        blobStore.downloadBlob(
                            remoteActionExecutionContext, DIGEST, ByteArrayOutputStream()
                        )
                    )
                })
        } finally {
            testServer.stop(server)
        }
    }

    @Test
    @Throws(Exception::class)
    fun partialDownloadSucceedsWithRetry() {
        var server: ServerChannel? = null
        try {
            val chunk1: ByteBuf = Unpooled.wrappedBuffer("File ".toByteArray(StandardCharsets.US_ASCII))
            // Replace first chunk to test that the client skips the redundant prefix on retry.
            val chunk1Attempt2: ByteBuf = Unpooled.wrappedBuffer("abcde".toByteArray(StandardCharsets.US_ASCII))
            val chunk2: ByteBuf = Unpooled.wrappedBuffer("Contents".toByteArray(StandardCharsets.US_ASCII))
            server = testServer.start(IntermittentFailureHandler(chunk1, chunk1Attempt2, chunk2))
            val credentials = newCredentials()
            val authAndTlsOptions: AuthAndTLSOptions? = Options.getDefaults<O?>(AuthAndTLSOptions::class.java)

            val retryScheduler =
                MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1))
            val retrier: RemoteRetrier =
                RemoteRetrier(
                    { ZeroBackoff(1) },
                    { e ->
                        if (e is ClosedChannelException)
                            Result.TRANSIENT_FAILURE
                        else
                            Result.PERMANENT_FAILURE
                    },
                    retryScheduler,
                    Retrier.ALLOW_ALL_CALLS
                )
            val blobStore =
                createHttpBlobStore(
                    server,  /* timeoutSeconds= */
                    1,  /* remoteVerifyDownloads= */
                    false,
                    credentials,
                    authAndTlsOptions,
                    Optional.of<RemoteRetrier?>(retrier)
                )

            val download = ByteArrayOutputStream()
            getFromFuture(blobStore.downloadBlob(remoteActionExecutionContext, DIGEST, download))
            Truth.assertThat(download.toByteArray())
                .isEqualTo("File Contents".toByteArray(StandardCharsets.US_ASCII))
        } finally {
            testServer.stop(server)
        }
    }

    @Test
    @Throws(Exception::class)
    fun actionResultRetryReadsFromStart() {
        var server: ServerChannel? = null
        try {
            val builder1: ActionResult.Builder = ActionResult.newBuilder()
            builder1
                .addOutputFilesBuilder()
                .setPath("attempt1/filename")
                .setDigest(DIGEST_UTIL.computeAsUtf8("digest1"))
                .setIsExecutable(true)
            val action1: ActionResult = builder1.build()
            val buffer1 = ByteArrayOutputStream()
            action1.writeTo(buffer1)
            val splitAt = buffer1.size() / 2
            val chunk1 = Unpooled.copiedBuffer(buffer1.toByteArray(), 0, splitAt)

            // Replace first chunk to test that the client starts a fresh ActionResult download on retry.
            val builder2: ActionResult.Builder = ActionResult.newBuilder()
            builder2
                .addOutputFilesBuilder()
                .setPath("attempt2/filename")
                .setDigest(DIGEST_UTIL.computeAsUtf8("digest2"))
                .setIsExecutable(false)
            val action2: ActionResult = builder2.build()
            val buffer2 = ByteArrayOutputStream()
            action2.writeTo(buffer2)
            val chunk1Attempt2 = Unpooled.copiedBuffer(buffer2.toByteArray(), 0, splitAt)
            val chunk2 =
                Unpooled.copiedBuffer(buffer2.toByteArray(), splitAt, buffer2.size() - splitAt)

            server = testServer.start(IntermittentFailureHandler(chunk1, chunk1Attempt2, chunk2))
            val credentials = newCredentials()
            val authAndTlsOptions: AuthAndTLSOptions? = Options.getDefaults<O?>(AuthAndTLSOptions::class.java)

            val retryScheduler =
                MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1))
            val retrier: RemoteRetrier =
                RemoteRetrier(
                    { ZeroBackoff(1) },
                    { e ->
                        if (e is ClosedChannelException)
                            Result.TRANSIENT_FAILURE
                        else
                            Result.PERMANENT_FAILURE
                    },
                    retryScheduler,
                    Retrier.ALLOW_ALL_CALLS
                )
            val blobStore =
                createHttpBlobStore(
                    server,  /* timeoutSeconds= */
                    1,  /* remoteVerifyDownloads= */
                    false,
                    credentials,
                    authAndTlsOptions,
                    Optional.of<RemoteRetrier?>(retrier)
                )

            val actionResult: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                getFromFuture(
                    blobStore.downloadActionResult(
                        remoteActionExecutionContext,
                        ActionKey(DIGEST),  /* inlineOutErr= */
                        false,  /* inlineOutputFiles= */
                        ImmutableSet.of<String?>()
                    )
                )
            assertThat(actionResult).isEqualTo(action2)
        } finally {
            testServer.stop(server)
        }
    }

    @Test
    @Throws(Exception::class)
    fun expiredAuthTokensShouldBeRetried_get() {
        expiredAuthTokensShouldBeRetried_get(
            NotAuthorizedHandler.ErrorType.UNAUTHORIZED
        )
        expiredAuthTokensShouldBeRetried_get(
            NotAuthorizedHandler.ErrorType.INVALID_TOKEN
        )
    }

    @Throws(Exception::class)
    private fun expiredAuthTokensShouldBeRetried_get(
        errorType: NotAuthorizedHandler.ErrorType
    ) {
        var server: ServerChannel? = null
        try {
            server = testServer.start(NotAuthorizedHandler(errorType))

            val credentials = newCredentials()
            val authAndTlsOptions: AuthAndTLSOptions? = Options.getDefaults<O?>(AuthAndTLSOptions::class.java)
            val blobStore =
                createHttpBlobStore(server,  /* timeoutSeconds= */1, credentials, authAndTlsOptions)
            val out: ByteArrayOutputStream = Mockito.spy<ByteArrayOutputStream>(ByteArrayOutputStream())
            getFromFuture(blobStore.downloadBlob(remoteActionExecutionContext, DIGEST, out))
            Truth.assertThat(out.toString(StandardCharsets.US_ASCII.name())).isEqualTo("File Contents")
            Mockito.verify<Credentials?>(credentials, Mockito.times(1)).refresh()
            Mockito.verify<Credentials?>(credentials, Mockito.times(2)).getRequestMetadata(
                ArgumentMatchers.any<URI?>(
                    URI::class.java
                )
            )
            Mockito.verify<Credentials?>(credentials, Mockito.times(2)).hasRequestMetadata()
            // The caller is responsible to the close the stream.
            Mockito.verify<ByteArrayOutputStream?>(out, Mockito.never()).close()
            Mockito.verifyNoMoreInteractions(credentials)
        } finally {
            testServer.stop(server)
        }
    }

    @Test
    @Throws(Exception::class)
    fun expiredAuthTokensShouldBeRetried_put() {
        expiredAuthTokensShouldBeRetried_put(
            NotAuthorizedHandler.ErrorType.UNAUTHORIZED
        )
        expiredAuthTokensShouldBeRetried_put(
            NotAuthorizedHandler.ErrorType.INVALID_TOKEN
        )
    }

    @Throws(Exception::class)
    private fun expiredAuthTokensShouldBeRetried_put(
        errorType: NotAuthorizedHandler.ErrorType
    ) {
        var server: ServerChannel? = null
        try {
            server = testServer.start(NotAuthorizedHandler(errorType))

            val credentials = newCredentials()
            val authAndTlsOptions: AuthAndTLSOptions? = Options.getDefaults<O?>(AuthAndTLSOptions::class.java)
            val blobStore =
                createHttpBlobStore(server,  /* timeoutSeconds= */1, credentials, authAndTlsOptions)
            val data: ByteArray = "File Contents".toByteArray(StandardCharsets.US_ASCII)
            blobStore
                .uploadBlob(
                    remoteActionExecutionContext,
                    DIGEST_UTIL.compute(data),
                    ByteString.copyFrom(data),  /* force= */
                    false
                )
                .get()
            Mockito.verify<Credentials?>(credentials, Mockito.times(1)).refresh()
            Mockito.verify<Credentials?>(credentials, Mockito.times(2)).getRequestMetadata(
                ArgumentMatchers.any<URI?>(
                    URI::class.java
                )
            )
            Mockito.verify<Credentials?>(credentials, Mockito.times(2)).hasRequestMetadata()
            Mockito.verifyNoMoreInteractions(credentials)
        } finally {
            testServer.stop(server)
        }
    }

    @Test
    fun errorCodesThatShouldNotBeRetried_get() {
        errorCodeThatShouldNotBeRetried_get(
            NotAuthorizedHandler.ErrorType.INSUFFICIENT_SCOPE
        )
        errorCodeThatShouldNotBeRetried_get(
            NotAuthorizedHandler.ErrorType.INVALID_REQUEST
        )
    }

    private fun errorCodeThatShouldNotBeRetried_get(
        errorType: NotAuthorizedHandler.ErrorType
    ) {
        var server: ServerChannel? = null
        try {
            server = testServer.start(NotAuthorizedHandler(errorType))

            val credentials = newCredentials()
            val authAndTlsOptions: AuthAndTLSOptions? = Options.getDefaults<O?>(AuthAndTLSOptions::class.java)
            val blobStore =
                createHttpBlobStore(server,  /* timeoutSeconds= */1, credentials, authAndTlsOptions)
            getFromFuture(
                blobStore.downloadBlob(
                    remoteActionExecutionContext, DIGEST, ByteArrayOutputStream()
                )
            )
            Assert.fail("Exception expected.")
        } catch (e: Exception) {
            Truth.assertThat(e).isInstanceOf(HttpException::class.java)
            Truth.assertThat<HttpResponseStatus?>((e as HttpException).response()!!.status())
                .isEqualTo(HttpResponseStatus.UNAUTHORIZED)
        } finally {
            testServer.stop(server)
        }
    }

    @Test
    fun errorCodesThatShouldNotBeRetried_put() {
        errorCodeThatShouldNotBeRetried_put(
            NotAuthorizedHandler.ErrorType.INSUFFICIENT_SCOPE
        )
        errorCodeThatShouldNotBeRetried_put(
            NotAuthorizedHandler.ErrorType.INVALID_REQUEST
        )
    }

    private fun errorCodeThatShouldNotBeRetried_put(
        errorType: NotAuthorizedHandler.ErrorType
    ) {
        var server: ServerChannel? = null
        try {
            server = testServer.start(NotAuthorizedHandler(errorType))

            val credentials = newCredentials()
            val authAndTlsOptions: AuthAndTLSOptions? = Options.getDefaults<O?>(AuthAndTLSOptions::class.java)
            val blobStore =
                createHttpBlobStore(server,  /* timeoutSeconds= */1, credentials, authAndTlsOptions)
            val oneByte = byteArrayOf(0)
            getFromFuture(
                blobStore.uploadBlob(
                    remoteActionExecutionContext,
                    DIGEST_UTIL.compute(oneByte),
                    ByteString.copyFrom(oneByte),  /* force= */
                    false
                )
            )
            Assert.fail("Exception expected.")
        } catch (e: Exception) {
            Truth.assertThat(e).isInstanceOf(HttpException::class.java)
            Truth.assertThat<HttpResponseStatus?>((e as HttpException).response()!!.status())
                .isEqualTo(HttpResponseStatus.UNAUTHORIZED)
        } finally {
            testServer.stop(server)
        }
    }

    @Throws(Exception::class)
    private fun newCredentials(): Credentials {
        val credentials: Credentials
        Credentials > Mockito.mock<Credentials?>(Credentials::class.java)
        Boolean > Mockito.`when`<Boolean?>(credentials.hasRequestMetadata()).thenReturn(true)
        val headers: MutableMap<String?, MutableList<String?>?> = HashMap<String?, MutableList<String?>?>()
        headers.put(
            "Authorization",
            TODO("Cannot convert element")
        )<String> mutableListOf < kotlin . String ? > ("Bearer invalidToken")

        Mockito.`when`<Boolean?>(credentials.getRequestMetadata(TODO("Cannot convert element"))<URI> ArgumentMatchers . any < java . net . URI ? > (URI::class.java))
        thenReturn(headers)
        Mockito.doAnswer(
            Answer { mock: InvocationOnMock? ->
                val headers2: MutableMap<String?, MutableList<String?>?> = HashMap<String?, MutableList<String?>?>()
                headers2.put(
                    "Authorization",
                    TODO("Cannot convert element")
                )<String> mutableListOf < kotlin . String ? > ("Bearer validToken")

                Mockito.`when`<Boolean?>(credentials.getRequestMetadata(TODO("Cannot convert element"))<URI> ArgumentMatchers . any < java . net . URI ? > (URI::class.java))
                thenReturn(headers2)
                null
            })
            .`when`<Credentials?>(credentials)
            .refresh()
        return credentials
    }

    /**
     * [ChannelHandler] that on the first request responds with a 401 UNAUTHORIZED status code,
     * which the client is expected to retry once with a new authentication token.
     */
    @ChannelHandler.Sharable
    internal class NotAuthorizedHandler(private val errorType: ErrorType) :
        SimpleChannelInboundHandler<FullHttpRequest?>() {
        internal enum class ErrorType {
            UNAUTHORIZED,
            INVALID_TOKEN,
            INSUFFICIENT_SCOPE,
            INVALID_REQUEST
        }

        private var messageCount = 0

        override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest) {
            if (messageCount == 0) {
                if ("Bearer invalidToken" != request.headers().get(HttpHeaderNames.AUTHORIZATION)) {
                    ctx.writeAndFlush(
                        DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR
                        )
                    )
                        .addListener(ChannelFutureListener.CLOSE)
                    return
                }
                val response: FullHttpResponse
                if (errorType == ErrorType.UNAUTHORIZED) {
                    response =
                        DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED)
                } else {
                    response =
                        DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED)
                    response
                        .headers()
                        .set(
                            HttpHeaderNames.WWW_AUTHENTICATE,
                            ("Bearer realm=\"localhost\","
                                    + "error=\""
                                    + errorType.name.lowercase()
                                    + "\","
                                    + "error_description=\"The access token expired\"")
                        )
                }
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
                messageCount++
            } else if (messageCount == 1) {
                if ("Bearer validToken" != request.headers().get(HttpHeaderNames.AUTHORIZATION)) {
                    ctx.writeAndFlush(
                        DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR
                        )
                    )
                        .addListener(ChannelFutureListener.CLOSE)
                    return
                }
                val content = ctx.alloc().buffer()
                content.writeCharSequence("File Contents", StandardCharsets.US_ASCII)
                val response: FullHttpResponse =
                    DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content)
                HttpUtil.setKeepAlive(response, true)
                HttpUtil.setContentLength(response, content.readableBytes().toLong())
                ctx.writeAndFlush(response)
                messageCount++
            } else {
                // No third message expected.
                ctx.writeAndFlush(
                    DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR
                    )
                )
                    .addListener(ChannelFutureListener.CLOSE)
            }
        }
    }

    /**
     * [ChannelHandler] that on the first request returns a partial response and then closes the
     * stream, and on any further requests returns a full response.
     */
    @ChannelHandler.Sharable
    internal class IntermittentFailureHandler(
        private val attempt1Chunk1: ByteBuf?,
        private val attempt2Chunk1: ByteBuf?,
        private val attempt2Chunk2: ByteBuf?
    ) : SimpleChannelInboundHandler<FullHttpRequest?>() {
        private var messageCount = 0

        constructor(chunk1: ByteBuf, chunk2: ByteBuf?) : this(chunk1.copy(), chunk1, chunk2)

        override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest?) {
            val response =
                DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
            response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
            ctx.write(response)
            if (messageCount == 0) {
                ctx.writeAndFlush(DefaultHttpContent(attempt1Chunk1))
                    .addListener(ChannelFutureListener.CLOSE)
            } else {
                ctx.writeAndFlush(DefaultHttpContent(attempt2Chunk1))
                ctx.writeAndFlush(DefaultLastHttpContent(attempt2Chunk2))
                    .addListener(ChannelFutureListener.CLOSE)
            }
            ++messageCount
        }
    }

    @Test
    @Throws(Exception::class)
    fun extraCacheHeaders() {
        var server: ServerChannel? = null
        try {
            val remoteOptions: RemoteOptions = Options.getDefaults<O>(RemoteOptions::class.java)
            remoteOptions.remoteHeaders = ImmutableList.of<MutableMap.MutableEntry<String?, String?>?>(
                Map.entry<String?, String?>("CommonKey1", "CommonValue1"),
                Map.entry<String?, String?>("CommonKey2", "CommonValue2")
            )
            remoteOptions.remoteCacheHeaders = ImmutableList.of<MutableMap.MutableEntry<String?, String?>?>(
                Map.entry<String?, String?>("CacheKey1", "CacheValue1"),
                Map.entry<String?, String?>("CacheKey2", "CacheValue2")
            )
            remoteOptions.remoteExecHeaders = ImmutableList.of<MutableMap.MutableEntry<String?, String?>?>(
                Map.entry<String?, String?>("ExecKey1", "ExecValue1"),
                Map.entry<String?, String?>("ExecKey2", "ExecValue2")
            )

            server =
                testServer.start(
                    object : SimpleChannelInboundHandler<FullHttpRequest?>() {
                        override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest) {
                            Truth.assertThat(request.headers().get("CommonKey1")).isEqualTo("CommonValue1")
                            Truth.assertThat(request.headers().get("CommonKey2")).isEqualTo("CommonValue2")
                            Truth.assertThat(request.headers().get("CacheKey1")).isEqualTo("CacheValue1")
                            Truth.assertThat(request.headers().get("CacheKey2")).isEqualTo("CacheValue2")
                            Truth.assertThat(request.headers().get("ExecKey1")).isNull()
                            Truth.assertThat(request.headers().get("ExecKey2")).isNull()

                            val content = ctx.alloc().buffer()
                            content.writeCharSequence("File Contents", StandardCharsets.US_ASCII)
                            val response: FullHttpResponse =
                                DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content
                                )
                            HttpUtil.setContentLength(response, content.readableBytes().toLong())
                            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
                        }
                    })

            val blobStore =
                createHttpBlobStore(
                    server,  /* timeoutSeconds= */
                    1,  /* remoteVerifyDownloads= */
                    true,
                    CombinedCacheClientFactory.effectiveHeaders(remoteOptions),
                    newCredentials(),
                    Options.getDefaults<O?>(AuthAndTLSOptions::class.java),  /* optRetrier= */
                    Optional.empty<RemoteRetrier?>()
                )

            val out = ByteArrayOutputStream()
            getFromFuture(blobStore.downloadBlob(remoteActionExecutionContext, DIGEST, out))
            Truth.assertThat(out.toString(StandardCharsets.US_ASCII)).isEqualTo("File Contents")
        } finally {
            testServer.stop(server)
        }
    }

    companion object {
        private val DIGEST_UTIL: DigestUtil = DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256)
        private val DIGEST: Digest = DIGEST_UTIL.computeAsUtf8("File Contents")

        private fun createServer(
            serverChannelClass: Class<out ServerChannel?>?,
            newEventLoopGroup: IntFunction<EventLoopGroup?>,
            socketAddress: SocketAddress?,
            handler: ChannelHandler?
        ): ServerChannel? {
            val eventLoop = newEventLoopGroup.apply(1)
            val sb =
                ServerBootstrap()
                    .group(eventLoop)
                    .channel(serverChannelClass)
                    .childHandler(
                        object : ChannelInitializer<Channel?>() {
                            override fun initChannel(ch: Channel) {
                                ch.pipeline().addLast(HttpServerCodec())
                                ch.pipeline().addLast(HttpObjectAggregator(1000))
                                ch.pipeline().addLast(handler)
                            }
                        })
            try {
                return (sb.bind(socketAddress).sync().channel() as ServerChannel?)
            } catch (e: Exception) {
                throw IllegalStateException(e)
            }
        }

        private fun newDomainSocketAddress(): DomainSocketAddress {
            try {
                val file = File.createTempFile("bazel", ".sock", File("/tmp"))
                file.delete()
                return DomainSocketAddress(file.getAbsoluteFile())
            } catch (e: Exception) {
                throw IllegalStateException(e)
            }
        }

        @Parameterized.Parameters
        fun createInputValues(): MutableList<Array<Any?>?> {
            val parameters =
                ArrayList<Array<Any?>?>(Arrays.asList<Array<Any?>?>(*arrayOf<Array<Any?>?>(arrayOf<Any?>(InetTestServer()))))

            if (Epoll.isAvailable()) {
                parameters.add(
                    arrayOf<Any>(
                        UnixDomainServer(
                            EpollServerDomainSocketChannel::class.java,
                            IntFunction { nThreads: Int -> EpollEventLoopGroup(nThreads) })
                    )
                )
            }

            if (KQueue.isAvailable()) {
                parameters.add(
                    arrayOf<Any>(
                        UnixDomainServer(
                            KQueueServerDomainSocketChannel::class.java,
                            IntFunction { nThreads: Int -> KQueueEventLoopGroup(nThreads) })
                    )
                )
            }

            return parameters
        }
    }
}
