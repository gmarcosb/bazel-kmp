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

import com.google.common.collect.ImmutableList
import com.google.common.net.HttpHeaders
import com.google.common.truth.Truth
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.ByteBufUtil
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.ByteArrayInputStream
import java.net.URI

/** Tests for [HttpUploadHandler].  */
@RunWith(JUnit4::class)
class HttpUploadHandlerTest {
    /**
     * Test that uploading blobs works to both the Action Cache and the CAS. Also test that the
     * handler is reusable.
     */
    @Test
    @Throws(Exception::class)
    fun uploadsShouldWork() {
        val ch =
            EmbeddedChannel(HttpUploadHandler(null, ImmutableList.of<MutableMap.MutableEntry<String?, String?>?>()))
        val statuses: Array<HttpResponseStatus?> =
            arrayOf<HttpResponseStatus>(
                HttpResponseStatus.OK,
                HttpResponseStatus.CREATED,
                HttpResponseStatus.ACCEPTED,
                HttpResponseStatus.NO_CONTENT
            )

        for (status in statuses) {
            uploadsShouldWork(true, ch, status)
            uploadsShouldWork(false, ch, status)
        }
    }

    @Throws(Exception::class)
    private fun uploadsShouldWork(casUpload: Boolean, ch: EmbeddedChannel, status: HttpResponseStatus?) {
        val data = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))
        val writePromise = ch.newPromise()
        ch.writeOneOutbound(UploadCommand(CACHE_URI, casUpload, "abcdef", data, 5), writePromise)

        val request = ch.readOutbound<HttpRequest>()
        Truth.assertThat<HttpMethod?>(request.method()).isEqualTo(HttpMethod.PUT)
        Truth.assertThat(request.headers().get(HttpHeaders.CONNECTION))
            .isEqualTo(HttpHeaderValues.KEEP_ALIVE.toString())

        val content = ch.readOutbound<HttpChunkedInput>()
        Truth.assertThat(content.readChunk(ByteBufAllocator.DEFAULT).content().readableBytes()).isEqualTo(5)

        val response: FullHttpResponse = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status)
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)

        ch.writeInbound(response)

        Truth.assertThat(writePromise.isDone()).isTrue()
        Truth.assertThat(ch.isOpen()).isTrue()
    }

    /** Test that the handler correctly supports http error codes i.e. 404 (NOT FOUND).  */
    @Test
    fun httpErrorsAreSupported() {
        val ch =
            EmbeddedChannel(HttpUploadHandler(null, ImmutableList.of<MutableMap.MutableEntry<String?, String?>?>()))
        val data = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))
        val writePromise = ch.newPromise()
        ch.writeOneOutbound(UploadCommand(CACHE_URI, true, "abcdef", data, 5), writePromise)

        val request = ch.readOutbound<HttpRequest?>()
        Truth.assertThat(request).isInstanceOf(HttpRequest::class.java)
        val content = ch.readOutbound<HttpChunkedInput?>()
        Truth.assertThat(content).isInstanceOf(HttpChunkedInput::class.java)

        val response: FullHttpResponse =
            DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN)
        response.headers().set(HttpHeaders.CONNECTION, HttpHeaderValues.CLOSE)

        ch.writeInbound(response)

        Truth.assertThat(writePromise.isDone()).isTrue()
        Truth.assertThat(writePromise.cause()).isInstanceOf(HttpException::class.java)
        HttpResponseStatus > Truth.assertThat<HttpResponseStatus?>(
            (writePromise.cause() as HttpException).response()!!.status()
        )
            .isEqualTo(HttpResponseStatus.FORBIDDEN)
        Truth.assertThat(ch.isOpen()).isFalse()
    }

    /**
     * Test that the handler correctly supports http error codes i.e. 404 (NOT FOUND) with a
     * Content-Length header.
     */
    @Test
    fun httpErrorsWithContentAreSupported() {
        val ch =
            EmbeddedChannel(HttpUploadHandler(null, ImmutableList.of<MutableMap.MutableEntry<String?, String?>?>()))
        val data = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))
        val writePromise = ch.newPromise()
        ch.writeOneOutbound(UploadCommand(CACHE_URI, true, "abcdef", data, 5), writePromise)

        val request = ch.readOutbound<HttpRequest?>()
        Truth.assertThat(request).isInstanceOf(HttpRequest::class.java)
        val content = ch.readOutbound<HttpChunkedInput?>()
        Truth.assertThat(content).isInstanceOf(HttpChunkedInput::class.java)

        val errorMsg = ByteBufUtil.writeAscii(ch.alloc(), "error message")
        val response: FullHttpResponse =
            DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND, errorMsg)
        response.headers().set(HttpHeaders.CONNECTION, HttpHeaderValues.KEEP_ALIVE)

        ch.writeInbound(response)

        Truth.assertThat(writePromise.isDone()).isTrue()
        Truth.assertThat(writePromise.cause()).isInstanceOf(HttpException::class.java)
        HttpResponseStatus > Truth.assertThat<HttpResponseStatus?>(
            (writePromise.cause() as HttpException).response()!!.status()
        )
            .isEqualTo(HttpResponseStatus.NOT_FOUND)
        Truth.assertThat(ch.isOpen()).isTrue()
    }

    companion object {
        private val CACHE_URI: URI = URI.create("http://storage.googleapis.com:80/cache-bucket")
    }
}
