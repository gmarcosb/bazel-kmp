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
import com.google.common.base.Joiner
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.collect.Lists
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.handler.codec.compression.Zstd
import io.netty.handler.codec.http.*
import io.netty.handler.timeout.ReadTimeoutException
import io.netty.util.AsciiString
import io.netty.util.concurrent.Future
import io.netty.util.concurrent.GenericFutureListener
import io.netty.util.internal.StringUtil
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/** ChannelHandler for downloads.  */
internal class HttpDownloadHandler(
    credentials: Credentials?,
    extraHttpHeaders: ImmutableList<MutableMap.MutableEntry<String?, String?>?>?
) : AbstractHttpHandler<HttpObject?>(credentials, extraHttpHeaders) {
    private var out: OutputStream? = null
    private var keepAlive = HttpVersion.HTTP_1_1.isKeepAliveDefault()
    private var downloadSucceeded = false
    private var response: HttpResponse? = null

    private var bytesReceived: Long = 0
    private var contentLength: Long = -1

    /** the path header in the http request  */
    private var path: String? = null

    /** the bytes to skip in a full or chunked response  */
    private var skipBytes: Long = 0

    @Throws(Exception::class)
    override fun channelRead0(ctx: ChannelHandlerContext, msg: HttpObject) {
        if (!msg.decoderResult().isSuccess()) {
            failAndClose(IOException("Failed to parse the HTTP response."), ctx)
            return
        }
        if (msg !is HttpResponse && msg !is HttpContent) {
            failAndClose(
                IllegalArgumentException(
                    "Unsupported message type: " + StringUtil.simpleClassName(msg)
                ),
                ctx
            )
            return
        }
        Preconditions.checkState(userPromise != null, "response before request")

        if (msg is HttpResponse) {
            response = msg
            if (response!!.protocolVersion() != HttpVersion.HTTP_1_1) {
                val error =
                    HttpException(
                        response, "HTTP version 1.1 is required, was: " + response!!.protocolVersion(), null
                    )
                failAndClose(error, ctx)
                return
            }
            val contentLengthSet = HttpUtil.isContentLengthSet(response)
            if (!contentLengthSet && !HttpUtil.isTransferEncodingChunked(response)) {
                val error =
                    HttpException(
                        response, "Missing 'Content-Length' or 'Transfer-Encoding: chunked' header", null
                    )
                failAndClose(error, ctx)
                return
            }

            if (contentLengthSet) {
                contentLength = HttpUtil.getContentLength(response)
            }
            downloadSucceeded = response!!.status() == HttpResponseStatus.OK
            if (!downloadSucceeded) {
                out = ByteArrayOutputStream()
            }
            keepAlive = HttpUtil.isKeepAlive(msg)
        }

        if (msg is HttpContent) {
            Preconditions.checkState(response != null, "content before headers")

            val content = msg.content()
            var readableBytes = content.readableBytes()
            if (skipBytes > 0) {
                val skipNow: Int
                if (skipBytes < readableBytes) {
                    // readableBytes is an int, meaning skipBytes < readableBytes <= INT_MAX.
                    // So, this conversion is safe.
                    skipNow = skipBytes.toInt()
                } else {
                    skipNow = readableBytes
                }
                content.readerIndex(content.readerIndex() + skipNow)
                skipBytes -= skipNow.toLong()
                readableBytes = readableBytes - skipNow
            }
            content.readBytes(out, readableBytes)
            bytesReceived += readableBytes.toLong()
            if (msg is LastHttpContent) {
                if (downloadSucceeded) {
                    succeedAndReset(ctx)
                } else {
                    var errorMsg = response!!.status().toString() + "\n"
                    errorMsg +=
                        String(
                            (out as ByteArrayOutputStream).toByteArray(), HttpUtil.getCharset(response)
                        )
                    out.close()
                    val error = HttpException(response, errorMsg, null)
                    failAndReset(error, ctx)
                }
            }
        }
    }

    @Throws(Exception::class)
    override fun write(ctx: ChannelHandlerContext, msg: Any?, promise: ChannelPromise) {
        Preconditions.checkState(userPromise == null, "handler can't be shared between pipelines.")
        userPromise = promise
        if (msg !is DownloadCommand) {
            failAndResetUserPromise(
                IllegalArgumentException(
                    "Unsupported message type: " + StringUtil.simpleClassName(msg)
                )
            )
            return
        }
        out = msg.out()
        path = constructPath(msg.uri(), msg.digest().getHash(), msg.casDownload())
        skipBytes = msg.offset()
        val request = buildRequest(path, constructHost(msg.uri()))
        addCredentialHeaders(request, msg.uri())
        addExtraRemoteHeaders(request)
        addUserAgentHeader(request)
        addAcceptHeaders(request)
        ctx.writeAndFlush(request)
            .addListener(
                GenericFutureListener { f: Future<in Void?>? ->
                    if (!f!!.isSuccess()) {
                        failAndClose(f.cause(), ctx)
                    }
                })
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, t: Throwable?) {
        if (t is ReadTimeoutException) {
            super.exceptionCaught(ctx, DownloadTimeoutException(path, bytesReceived, contentLength))
        } else {
            super.exceptionCaught(ctx, t)
        }
    }

    private fun buildRequest(path: String?, host: String?): HttpRequest {
        val httpRequest: HttpRequest =
            DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path)
        httpRequest.headers().set(HttpHeaderNames.HOST, host)
        httpRequest.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
        httpRequest.headers().set(HttpHeaderNames.ACCEPT_ENCODING, ACCEPT_ENCODING)
        return httpRequest
    }

    private fun succeedAndReset(ctx: ChannelHandlerContext) {
        // All resets must happen *before* completing the user promise. Otherwise there is a race
        // condition, where this handler can be reused even though it is closed. In addition, if reset
        // calls ctx.close(), then that triggers a call to AbstractHttpHandler.channelInactive, which
        // attempts to close the user promise.
        val promise = userPromise
        userPromise = null
        try {
            reset(ctx)
        } finally {
            promise.setSuccess()
        }
    }

    private fun failAndClose(t: Throwable?, ctx: ChannelHandlerContext) {
        val promise = userPromise
        userPromise = null
        try {
            ctx.close()
        } finally {
            promise.setFailure(t)
        }
    }

    private fun failAndReset(t: Throwable?, ctx: ChannelHandlerContext) {
        val promise = userPromise
        userPromise = null
        try {
            reset(ctx)
        } finally {
            promise.setFailure(t)
        }
    }

    private fun reset(ctx: ChannelHandlerContext) {
        try {
            if (!keepAlive) {
                ctx.close()
            }
        } finally {
            out = null
            keepAlive = HttpVersion.HTTP_1_1.isKeepAliveDefault()
            downloadSucceeded = false
            response = null
        }
    }

    companion object {
        private val ACCEPT_ENCODING: String = acceptEncoding

        private val acceptEncoding: String
            get() {
                val acceptEncoding =
                    Lists.newArrayList<AsciiString?>(
                        HttpHeaderValues.GZIP,
                        HttpHeaderValues.DEFLATE,
                        HttpHeaderValues.SNAPPY
                    )
                if (Zstd.isAvailable()) {
                    acceptEncoding.add(HttpHeaderValues.ZSTD)
                }
                return Joiner.on(",").join(acceptEncoding)
            }
    }
}
