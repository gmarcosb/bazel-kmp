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
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.handler.codec.TooLongFrameException
import io.netty.handler.codec.http.*
import io.netty.handler.stream.ChunkedStream
import io.netty.handler.timeout.WriteTimeoutException
import io.netty.util.concurrent.Future
import io.netty.util.concurrent.GenericFutureListener
import io.netty.util.internal.StringUtil
import java.io.IOException

/** ChannelHandler for uploads.  */
internal class HttpUploadHandler(
    credentials: Credentials?,
    extraHttpHeaders: ImmutableList<MutableMap.MutableEntry<String?, String?>?>?
) : AbstractHttpHandler<FullHttpResponse?>(credentials, extraHttpHeaders) {
    /** the path header in the http request  */
    private var path: String? = null

    /** the size of the data being uploaded in bytes  */
    private var contentLength: Long = 0

    override fun channelRead0(ctx: ChannelHandlerContext, response: FullHttpResponse) {
        if (!response.decoderResult().isSuccess()) {
            failAndClose(IOException("Failed to parse the HTTP response."), ctx)
            return
        }

        Preconditions.checkState(userPromise != null, "response before request")
        val promise = userPromise
        userPromise = null
        // Connection reset must happen *before* completing the user promise. Otherwise there is a race
        // condition, where this handler can be reused even though it is closed.
        try {
            if (!HttpUtil.isKeepAlive(response)) {
                ctx.close()
            }
        } finally {
            if ((response.status() != HttpResponseStatus.OK) && (response.status() != HttpResponseStatus.ACCEPTED) && (response.status() != HttpResponseStatus.CREATED) && (response.status() != HttpResponseStatus.NO_CONTENT)) {
                // Supporting more than OK status to be compatible with nginx webdav.
                var errorMsg: String? = response.status().toString()
                if (response.content().readableBytes() > 0) {
                    val data = ByteArray(response.content().readableBytes())
                    response.content().readBytes(data)
                    errorMsg += "\n" + String(data, HttpUtil.getCharset(response))
                }
                promise.setFailure(HttpException(response, errorMsg, null))
            } else {
                promise.setSuccess()
            }
        }
    }

    @Throws(Exception::class)
    override fun write(ctx: ChannelHandlerContext, msg: Any?, promise: ChannelPromise) {
        Preconditions.checkState(userPromise == null, "handler can't be shared between pipelines.")
        userPromise = promise
        if (msg !is UploadCommand) {
            failAndResetUserPromise(
                IllegalArgumentException(
                    "Unsupported message type: " + StringUtil.simpleClassName(msg)
                )
            )
            return
        }
        path = constructPath(msg.uri(), msg.hash(), msg.casUpload())
        contentLength = msg.contentLength()
        val request = buildRequest(path, constructHost(msg.uri()), contentLength)
        addCredentialHeaders(request, msg.uri())
        addExtraRemoteHeaders(request)
        addUserAgentHeader(request)
        addAcceptHeaders(request)
        val body = buildBody(msg)
        ctx.writeAndFlush(request)
            .addListener(
                GenericFutureListener { f: Future<in Void?>? ->
                    if (f!!.isSuccess()) {
                        return@addListener
                    }
                    failAndClose(f.cause(), ctx)
                })
        ctx.writeAndFlush(body)
            .addListener(
                GenericFutureListener { f: Future<in Void?>? ->
                    if (f!!.isSuccess()) {
                        return@addListener
                    }
                    failAndClose(f.cause(), ctx)
                })
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, t: Throwable?) {
        if (t is WriteTimeoutException) {
            super.exceptionCaught(ctx, UploadTimeoutException(path, contentLength))
        } else if (t is TooLongFrameException) {
            super.exceptionCaught(ctx, IOException(t))
        } else {
            super.exceptionCaught(ctx, t)
        }
    }

    private fun buildRequest(path: String?, host: String?, contentLength: Long): HttpRequest {
        val request: HttpRequest = DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, path)
        request.headers().set(HttpHeaderNames.HOST, host)
        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, contentLength)
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
        return request
    }

    private fun buildBody(msg: UploadCommand): HttpChunkedInput {
        return HttpChunkedInput(ChunkedStream(msg.data()))
    }

    private fun failAndClose(t: Throwable?, ctx: ChannelHandlerContext) {
        // All resets must happen *before* completing the user promise. Otherwise there is a race
        // condition, where this handler can be reused even though it is closed.
        ctx.close()
        failAndResetUserPromise(t)
    }
}
