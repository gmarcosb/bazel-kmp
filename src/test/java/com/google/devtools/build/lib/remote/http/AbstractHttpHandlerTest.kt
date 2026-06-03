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
import com.google.common.collect.Maps
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpRequest
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URI

/** Tests for [AbstractHttpHandlerTest].  */
@RunWith(JUnit4::class)
abstract class AbstractHttpHandlerTest {
    @Test
    @Throws(Exception::class)
    fun basicAuthShouldWork() {
        val uri = URI("http://user:password@does.not.exist/foo")
        val ch =
            EmbeddedChannel(HttpDownloadHandler(null, ImmutableList.of<MutableMap.MutableEntry<String?, String?>?>()))
        val cmd = DownloadCommand(uri, true, DIGEST, ByteArrayOutputStream())
        val writePromise = ch.newPromise()
        ch.writeOneOutbound(cmd, writePromise)

        val request = ch.readOutbound<HttpRequest>()
        Truth.assertThat(request.headers().get(HttpHeaderNames.AUTHORIZATION))
            .isEqualTo("Basic dXNlcjpwYXNzd29yZA==")
    }

    @Test
    @Throws(Exception::class)
    fun basicAuthShouldNotEnabled() {
        val uri = URI("http://does.not.exist/foo")
        val ch =
            EmbeddedChannel(HttpDownloadHandler(null, ImmutableList.of<MutableMap.MutableEntry<String?, String?>?>()))
        val cmd = DownloadCommand(uri, true, DIGEST, ByteArrayOutputStream())
        val writePromise = ch.newPromise()
        ch.writeOneOutbound(cmd, writePromise)

        val request = ch.readOutbound<HttpRequest>()
        Truth.assertThat(request.headers().contains(HttpHeaderNames.AUTHORIZATION)).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun hostDoesntIncludePortHttp() {
        val uri = URI("http://does.not.exist/foo")
        val ch =
            EmbeddedChannel(HttpDownloadHandler(null, ImmutableList.of<MutableMap.MutableEntry<String?, String?>?>()))
        val cmd = DownloadCommand(uri, true, DIGEST, ByteArrayOutputStream())
        val writePromise = ch.newPromise()
        ch.writeOneOutbound(cmd, writePromise)

        val request = ch.readOutbound<HttpRequest>()
        Truth.assertThat(request.headers().get(HttpHeaderNames.HOST)).isEqualTo("does.not.exist")
    }

    @Test
    @Throws(Exception::class)
    fun hostDoesntIncludePortHttps() {
        val uri = URI("https://does.not.exist/foo")
        val ch =
            EmbeddedChannel(HttpDownloadHandler(null, ImmutableList.of<MutableMap.MutableEntry<String?, String?>?>()))
        val cmd = DownloadCommand(uri, true, DIGEST, ByteArrayOutputStream())
        val writePromise = ch.newPromise()
        ch.writeOneOutbound(cmd, writePromise)

        val request = ch.readOutbound<HttpRequest>()
        Truth.assertThat(request.headers().get(HttpHeaderNames.HOST)).isEqualTo("does.not.exist")
    }

    @Test
    @Throws(Exception::class)
    fun hostDoesIncludePort() {
        val uri = URI("http://does.not.exist:8080/foo")
        val ch =
            EmbeddedChannel(HttpDownloadHandler(null, ImmutableList.of<MutableMap.MutableEntry<String?, String?>?>()))
        val cmd = DownloadCommand(uri, true, DIGEST, ByteArrayOutputStream())
        val writePromise = ch.newPromise()
        ch.writeOneOutbound(cmd, writePromise)

        val request = ch.readOutbound<HttpRequest>()
        Truth.assertThat(request.headers().get(HttpHeaderNames.HOST)).isEqualTo("does.not.exist:8080")
    }

    @Test
    @Throws(Exception::class)
    fun headersDoIncludeUserAgent() {
        val uri = URI("http://does.not.exist:8080/foo")
        val ch =
            EmbeddedChannel(
                HttpDownloadHandler( /* credentials= */null,
                    ImmutableList.of<MutableMap.MutableEntry<String?, String?>?>()
                )
            )
        val cmd =
            DownloadCommand(uri,  /* casDownload= */true, DIGEST, ByteArrayOutputStream())
        val writePromise = ch.newPromise()
        ch.writeOneOutbound(cmd, writePromise)

        val request = ch.readOutbound<HttpRequest>()
        Truth.assertThat(request.headers().get(HttpHeaderNames.USER_AGENT)).isEqualTo("bazel/")
    }

    @Test
    @Throws(Exception::class)
    fun extraHeadersAreIncluded() {
        val uri = URI("http://does.not.exist:8080/foo")
        val remoteHeaders =
            ImmutableList.of<MutableMap.MutableEntry<String?, String?>?>(
                Maps.immutableEntry<String?, String?>("key1", "value1"),
                Maps.immutableEntry<String?, String?>("key2", "value2")
            )

        val ch =
            EmbeddedChannel(HttpDownloadHandler( /* credentials= */null, remoteHeaders))
        val cmd =
            DownloadCommand(uri,  /* casDownload= */true, DIGEST, ByteArrayOutputStream())
        val writePromise = ch.newPromise()
        ch.writeOneOutbound(cmd, writePromise)

        val request = ch.readOutbound<HttpRequest>()
        Truth.assertThat(request.headers().get("key1")).isEqualTo("value1")
        Truth.assertThat(request.headers().get("key2")).isEqualTo("value2")
        Truth.assertThat(request.headers().get(HttpHeaderNames.ACCEPT)).isEqualTo("*/*")
    }

    @Test
    @Throws(Exception::class)
    fun extraHeadersOverridesDefaultAccept() {
        val uri = URI("http://does.not.exist:8080/foo")
        val remoteHeaders =
            ImmutableList.of<MutableMap.MutableEntry<String?, String?>?>(
                Maps.immutableEntry<String?, String?>("key1", "value1"),
                Maps.immutableEntry<String?, String?>("key2", "value2"),
                Maps.immutableEntry<String?, String?>("Accept", "application/octet-stream")
            )

        val ch =
            EmbeddedChannel(HttpDownloadHandler( /* credentials= */null, remoteHeaders))
        val cmd =
            DownloadCommand(uri,  /* casDownload= */true, DIGEST, ByteArrayOutputStream())
        val writePromise = ch.newPromise()
        ch.writeOneOutbound(cmd, writePromise)

        val request = ch.readOutbound<HttpRequest>()
        Truth.assertThat(request.headers().get("key1")).isEqualTo("value1")
        Truth.assertThat(request.headers().get("key2")).isEqualTo("value2")
        Truth.assertThat(request.headers().get(HttpHeaderNames.ACCEPT)).isEqualTo("application/octet-stream")
    }

    @Test
    @Throws(Exception::class)
    fun multipleExtraHeadersAreSupported() {
        val uri = URI("http://does.not.exist:8080/foo")
        val remoteHeaders =
            ImmutableList.of<MutableMap.MutableEntry<String?, String?>?>(
                Maps.immutableEntry<String?, String?>("key", "value1"),
                Maps.immutableEntry<String?, String?>("key", "value2")
            )

        val ch =
            EmbeddedChannel(HttpDownloadHandler( /* credentials= */null, remoteHeaders))
        val cmd =
            DownloadCommand(uri,  /* casDownload= */true, DIGEST, ByteArrayOutputStream())
        val writePromise = ch.newPromise()
        ch.writeOneOutbound(cmd, writePromise)

        val request = ch.readOutbound<HttpRequest>()
        Truth.assertThat(request.headers().getAll("key")).isEqualTo(mutableListOf<String?>("value1", "value2"))
    }

    companion object {
        private val DIGEST_UTIL: DigestUtil = DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256)
        private val DIGEST: Digest = DIGEST_UTIL.computeAsUtf8("foo")
    }
}
