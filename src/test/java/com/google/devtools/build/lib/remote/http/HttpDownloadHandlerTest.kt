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

import build.bazel.remote.execution.v2.Digest
import com.google.common.collect.ImmutableList
import com.google.common.net.HttpHeaders
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URI

/** Tests for [HttpDownloadHandler].  */
@RunWith(JUnit4::class)
class HttpDownloadHandlerTest : AbstractHttpHandlerTest() {
    /**
     * Test that downloading blobs works from both the Action Cache and the CAS. Also test that the
     * handler is reusable.
     */
    @Test
    @Throws(IOException::class)
    fun downloadShouldWork() {
        val ch =
            EmbeddedChannel(HttpDownloadHandler(null, ImmutableList.of<MutableMap.MutableEntry<String?, String?>?>()))
        downloadShouldWork(true, ch)
        downloadShouldWork(false, ch)
    }

    @Throws(IOException::class)
    private fun downloadShouldWork(casDownload: Boolean, ch: EmbeddedChannel) {
        val out: ByteArrayOutputStream = Mockito.spy<ByteArrayOutputStream>(ByteArrayOutputStream())
        val cmd = DownloadCommand(CACHE_URI, casDownload, DIGEST, out)
        val writePromise = ch.newPromise()
        ch.writeOneOutbound(cmd, writePromise)

        val request = ch.readOutbound<HttpRequest>()
        Truth.assertThat<HttpMethod?>(request.method()).isEqualTo(HttpMethod.GET)
        Truth.assertThat(request.headers().get(HttpHeaderNames.HOST)).isEqualTo(CACHE_URI.getHost())
        if (casDownload) {
            Truth.assertThat(request.uri()).isEqualTo("/cache-bucket/cas/" + DIGEST.getHash())
        } else {
            Truth.assertThat(request.uri()).isEqualTo("/cache-bucket/ac/" + DIGEST.getHash())
        }

        Truth.assertThat(writePromise.isDone()).isFalse()

        val response: HttpResponse = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        response.headers().set(HttpHeaders.CONTENT_LENGTH, 5)
        response.headers().set(HttpHeaders.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
        ch.writeInbound(response)
        val content = Unpooled.buffer()
        content.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        ch.writeInbound(DefaultLastHttpContent(content))

        Truth.assertThat(writePromise.isDone()).isTrue()
        Truth.assertThat(out.toByteArray()).isEqualTo(byteArrayOf(1, 2, 3, 4, 5))
        Mockito.verify<ByteArrayOutputStream?>(out, Mockito.never()).close()
        Truth.assertThat(ch.isActive()).isTrue()
    }

    /** Test that the handler correctly supports http error codes i.e. 404 (NOT FOUND).  */
    @Test
    @Throws(IOException::class)
    fun httpErrorsAreSupported() {
        val ch =
            EmbeddedChannel(HttpDownloadHandler(null, ImmutableList.of<MutableMap.MutableEntry<String?, String?>?>()))
        val out: ByteArrayOutputStream = Mockito.spy<ByteArrayOutputStream>(ByteArrayOutputStream())
        val cmd = DownloadCommand(CACHE_URI, true, DIGEST, out)
        val writePromise = ch.newPromise()
        ch.writeOneOutbound(cmd, writePromise)

        val response: HttpResponse =
            DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND)
        response.headers().set(HttpHeaders.HOST, "localhost")
        response.headers().set(HttpHeaders.CONTENT_LENGTH, 0)
        response.headers().set(HttpHeaders.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
        ch.writeInbound(response)
        ch.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT)
        Truth.assertThat(writePromise.isDone()).isTrue()
        Truth.assertThat(writePromise.cause()).isInstanceOf(HttpException::class.java)
        HttpResponseStatus > Truth.assertThat<HttpResponseStatus?>(
            (writePromise.cause() as HttpException).response()!!.status()
        )
            .isEqualTo(HttpResponseStatus.NOT_FOUND)
        // No data should have been written to the OutputStream and it should have been closed.
        Truth.assertThat(out.size()).isEqualTo(0)
        ByteArrayOutputStream > Mockito.verify<ByteArrayOutputStream?>(out, Mockito.never()).close()
        Truth.assertThat(ch.isOpen()).isTrue()
    }

    /**
     * Test that the handler correctly supports http error codes i.e. 404 (NOT FOUND) with a
     * Content-Length header.
     */
    @Test
    @Throws(IOException::class)
    fun httpErrorsWithContentAreSupported() {
        val ch =
            EmbeddedChannel(HttpDownloadHandler(null, ImmutableList.of<MutableMap.MutableEntry<String?, String?>?>()))
        val out: ByteArrayOutputStream = Mockito.spy<ByteArrayOutputStream>(ByteArrayOutputStream())
        val cmd = DownloadCommand(CACHE_URI, true, DIGEST, out)
        val writePromise = ch.newPromise()
        ch.writeOneOutbound(cmd, writePromise)

        val response: HttpResponse =
            DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND)
        val errorMessage = ByteBufUtil.writeAscii(ch.alloc(), "Error message")
        response.headers().set(HttpHeaders.HOST, "localhost")
        response
            .headers()
            .set(HttpHeaders.CONTENT_LENGTH, errorMessage.readableBytes().toString())
        response.headers().set(HttpHeaders.CONNECTION, HttpHeaderValues.CLOSE)

        ch.writeInbound(response)
        // The promise must not be done because we haven't received the error message yet.
        Truth.assertThat(writePromise.isDone()).isFalse()

        ch.writeInbound(DefaultHttpContent(errorMessage))
        ch.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT)
        Truth.assertThat(writePromise.isDone()).isTrue()
        Truth.assertThat(writePromise.cause()).isInstanceOf(HttpException::class.java)
        HttpResponseStatus > Truth.assertThat<HttpResponseStatus?>(
            (writePromise.cause() as HttpException).response()!!.status()
        )
            .isEqualTo(HttpResponseStatus.NOT_FOUND)
        // No data should have been written to the OutputStream and it should have been closed.
        Truth.assertThat(out.size()).isEqualTo(0)
        ByteArrayOutputStream > Mockito.verify<ByteArrayOutputStream?>(out, Mockito.never()).close()
        Truth.assertThat(ch.isOpen()).isFalse()
    }

    /** Test that the handler correctly supports downloads at an offset, e.g. on retry.  */
    @Test
    @Throws(IOException::class)
    fun downloadAtOffsetShouldWork() {
        val ch =
            EmbeddedChannel(HttpDownloadHandler(null, ImmutableList.of<MutableMap.MutableEntry<String?, String?>?>()))
        val out: ByteArrayOutputStream = Mockito.spy<ByteArrayOutputStream>(ByteArrayOutputStream())
        val cmd = DownloadCommand(CACHE_URI, true, DIGEST, out, 2)
        val writePromise = ch.newPromise()
        ch.writeOneOutbound(cmd, writePromise)

        val response: HttpResponse = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        response.headers().set(HttpHeaders.CONTENT_LENGTH, 5)
        response.headers().set(HttpHeaders.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
        ch.writeInbound(response)
        val content = Unpooled.buffer()
        content.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        ch.writeInbound(DefaultLastHttpContent(content))

        Truth.assertThat(writePromise.isDone()).isTrue()
        Truth.assertThat(out.toByteArray()).isEqualTo(byteArrayOf(3, 4, 5))
        ByteArrayOutputStream > Mockito.verify<ByteArrayOutputStream?>(out, Mockito.never()).close()
        Truth.assertThat(ch.isActive()).isTrue()
    }

    /** Test that the handler correctly supports chunked downloads at an offset, e.g. on retry.  */
    @Test
    @Throws(IOException::class)
    fun chunkedDownloadAtOffsetShouldWork() {
        val ch =
            EmbeddedChannel(HttpDownloadHandler(null, ImmutableList.of<MutableMap.MutableEntry<String?, String?>?>()))
        val out: ByteArrayOutputStream = Mockito.spy<ByteArrayOutputStream>(ByteArrayOutputStream())
        val cmd = DownloadCommand(CACHE_URI, true, DIGEST, out, 3)
        val writePromise = ch.newPromise()
        ch.writeOneOutbound(cmd, writePromise)

        val response: HttpResponse = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        response.headers().set(HttpHeaders.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
        response.headers().set(HttpHeaders.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
        ch.writeInbound(response)
        val content1 = Unpooled.buffer()
        content1.writeBytes(byteArrayOf(1, 2))
        ch.writeInbound(DefaultHttpContent(content1))
        val content2 = Unpooled.buffer()
        content2.writeBytes(byteArrayOf(3, 4))
        ch.writeInbound(DefaultHttpContent(content2))
        val content3 = Unpooled.buffer()
        content3.writeBytes(byteArrayOf(5))
        ch.writeInbound(DefaultLastHttpContent(content3))

        Truth.assertThat(writePromise.isDone()).isTrue()
        Truth.assertThat(out.toByteArray()).isEqualTo(byteArrayOf(4, 5))
        ByteArrayOutputStream > Mockito.verify<ByteArrayOutputStream?>(out, Mockito.never()).close()
        Truth.assertThat(ch.isActive()).isTrue()
    }

    companion object {
        private val CACHE_URI: URI = URI.create("http://storage.googleapis.com:80/cache-bucket")
        private val DIGEST_UTIL: DigestUtil = DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256)
        private val DIGEST: Digest = DIGEST_UTIL.computeAsUtf8("foo")
    }
}
