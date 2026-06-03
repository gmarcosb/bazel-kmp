// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.remote.worker.http

import com.google.common.annotations.VisibleForTesting
import com.google.common.flogger.GoogleLogger
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.*
import io.netty.util.CharsetUtil
import java.io.IOException
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * An abstract HTTP REST cache that convert HTTP requests to abstract methods [ ][.readFromCache] and [.writeToCache].
 */
abstract class AbstractHttpCacheServerHandler

    : SimpleChannelInboundHandler<FullHttpRequest?>() {
    override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        if (!request.decoderResult().isSuccess()) {
            sendError(ctx, request, HttpResponseStatus.BAD_REQUEST)
            return
        }

        if (request.method() == HttpMethod.GET) {
            handleGet(ctx, request)
        } else if (request.method() == HttpMethod.PUT) {
            handlePut(ctx, request)
        } else {
            sendError(ctx, request, HttpResponseStatus.METHOD_NOT_ALLOWED)
        }
    }

    private fun handleGet(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        if (!isUriValid(request.uri())) {
            sendError(ctx, request, HttpResponseStatus.BAD_REQUEST)
            return
        }

        val contents: ByteArray?
        try {
            contents = readFromCache(request.uri())
        } catch (e: IOException) {
            logger.atSevere().withCause(e).log()
            sendError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR)
            return
        }

        if (contents == null) {
            sendError(ctx, request, HttpResponseStatus.NOT_FOUND)
            return
        }

        val response: FullHttpResponse =
            DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(contents)
            )
        HttpUtil.setContentLength(response, contents.size.toLong())
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/octet-stream")
        val lastContentFuture = ctx.writeAndFlush(response)

        if (!HttpUtil.isKeepAlive(request)) {
            lastContentFuture.addListener(ChannelFutureListener.CLOSE)
        }
    }

    @Throws(IOException::class)
    protected abstract fun readFromCache(uri: String?): ByteArray?

    @Throws(IOException::class)
    protected abstract fun writeToCache(uri: String?, content: ByteArray?)

    private fun handlePut(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        if (!request.decoderResult().isSuccess()) {
            sendError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR)
            return
        }
        if (!isUriValid(request.uri())) {
            sendError(ctx, request, HttpResponseStatus.BAD_REQUEST)
            return
        }

        val contentBytes = ByteArray(request.content().readableBytes())
        request.content().readBytes(contentBytes)
        try {
            writeToCache(request.uri(), contentBytes)
        } catch (e: IOException) {
            logger.atSevere().withCause(e).log()
            sendError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR)
            return
        }

        val response: FullHttpResponse =
            DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT)
        val lastContentFuture = ctx.writeAndFlush(response)

        if (!HttpUtil.isKeepAlive(request)) {
            lastContentFuture.addListener(ChannelFutureListener.CLOSE)
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private val URI_PATTERN: Pattern = Pattern.compile("^/?(.*/)?(ac/|cas/)([a-f0-9]{64})$")

        @VisibleForTesting
        fun isUriValid(uri: String?): Boolean {
            val matcher: Matcher = URI_PATTERN.matcher(uri)
            return matcher.matches()
        }

        private fun sendError(
            ctx: ChannelHandlerContext, request: FullHttpRequest?, status: HttpResponseStatus
        ) {
            val data = Unpooled.copiedBuffer(status.reasonPhrase() + "\r\n", CharsetUtil.UTF_8)
            val response: FullHttpResponse = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, data)
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8")
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, data.readableBytes())
            val future = ctx.writeAndFlush(response)

            if (!HttpUtil.isKeepAlive(request)) {
                future.addListener(ChannelFutureListener.CLOSE)
            }
        }
    }
}
