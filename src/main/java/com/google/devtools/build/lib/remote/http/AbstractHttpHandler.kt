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
import com.google.common.collect.ImmutableList
import com.google.common.io.BaseEncoding
import com.google.devtools.build.lib.analysis.BlazeVersionInfo
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandler
import io.netty.channel.ChannelPromise
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpObject
import io.netty.handler.codec.http.HttpRequest
import java.net.SocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets

/** Common functionality shared by concrete classes.  */
internal abstract class AbstractHttpHandler<T : HttpObject?>(
    private val credentials: Credentials?,
    private val extraHttpHeaders: ImmutableList<MutableMap.MutableEntry<String?, String?>>
) : SimpleChannelInboundHandler<T?>(), ChannelOutboundHandler {
    protected var userPromise: ChannelPromise? = null

    protected fun failAndResetUserPromise(t: Throwable?) {
        if (userPromise != null && !userPromise!!.isDone()) {
            userPromise!!.setFailure(t)
        }
        userPromise = null
    }

    @Throws(IOException::class)
    protected fun addCredentialHeaders(request: HttpRequest, uri: URI) {
        val userInfo = uri.getUserInfo()
        if (userInfo != null) {
            val value = BaseEncoding.base64Url().encode(userInfo.getBytes(StandardCharsets.UTF_8))
            request.headers().set(HttpHeaderNames.AUTHORIZATION, "Basic " + value)
            return
        }
        if (credentials == null || !credentials.hasRequestMetadata()) {
            return
        }
        val authHeaders = credentials.getRequestMetadata(uri)
        if (authHeaders == null || authHeaders.isEmpty()) {
            return
        }
        for (entry in authHeaders.entrySet()) {
            val name: String? = entry.getKey()
            for (value in entry.getValue()) {
                request.headers().add(name, value)
            }
        }
    }

    protected fun addExtraRemoteHeaders(request: HttpRequest) {
        for (header in extraHttpHeaders) {
            request.headers().add(header.getKey(), header.getValue())
        }
    }

    protected fun addUserAgentHeader(request: HttpRequest) {
        request.headers().set(HttpHeaderNames.USER_AGENT, USER_AGENT_VALUE)
    }

    protected fun addAcceptHeaders(request: HttpRequest) {
        if (request.headers().get(HttpHeaderNames.ACCEPT) == null) {
            request.headers().add(HttpHeaderNames.ACCEPT, "*/*")
        }
    }

    protected fun constructPath(uri: URI, hash: String?, isCas: Boolean): String {
        val builder = StringBuilder()
        builder.append(uri.getPath())
        if (!uri.getPath().endsWith("/")) {
            builder.append("/")
        }
        builder.append(if (isCas) HttpCacheClient.Companion.CAS_PREFIX else HttpCacheClient.Companion.AC_PREFIX)
        builder.append(hash)
        return builder.toString()
    }

    protected fun constructHost(uri: URI): String {
        val includePort =
            (uri.getPort() > 0)
                    && ((uri.getScheme() == "http" && uri.getPort() != 80)
                    || (uri.getScheme() == "https" && uri.getPort() != 443))
        return uri.getHost() + (if (includePort) ":" + uri.getPort() else "")
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, t: Throwable?) {
        failAndResetUserPromise(t)
        ctx.fireExceptionCaught(t)
    }

    override fun bind(ctx: ChannelHandlerContext, localAddress: SocketAddress?, promise: ChannelPromise?) {
        ctx.bind(localAddress, promise)
    }

    override fun connect(
        ctx: ChannelHandlerContext,
        remoteAddress: SocketAddress?,
        localAddress: SocketAddress?,
        promise: ChannelPromise?
    ) {
        ctx.connect(remoteAddress, localAddress, promise)
    }

    override fun disconnect(ctx: ChannelHandlerContext, promise: ChannelPromise?) {
        failAndResetUserPromise(ClosedChannelException())
        ctx.disconnect(promise)
    }

    override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise?) {
        failAndResetUserPromise(ClosedChannelException())
        ctx.close(promise)
    }

    override fun deregister(ctx: ChannelHandlerContext, promise: ChannelPromise?) {
        failAndResetUserPromise(ClosedChannelException())
        ctx.deregister(promise)
    }

    override fun read(ctx: ChannelHandlerContext) {
        ctx.read()
    }

    override fun flush(ctx: ChannelHandlerContext) {
        ctx.flush()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        failAndResetUserPromise(ClosedChannelException())
        ctx.fireChannelInactive()
    }

    override fun handlerRemoved(ctx: ChannelHandlerContext?) {
        failAndResetUserPromise(IOException("handler removed"))
    }

    override fun channelUnregistered(ctx: ChannelHandlerContext) {
        failAndResetUserPromise(ClosedChannelException())
        ctx.fireChannelUnregistered()
    }

    companion object {
        private val USER_AGENT_VALUE = "bazel/" + BlazeVersionInfo.instance().getVersion()
    }
}
