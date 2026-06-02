// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.repository.downloader

import com.google.common.io.ByteStreams
import com.google.devtools.build.lib.bazel.repository.cache.DownloadCache.KeyType
import com.google.devtools.build.lib.testutil.ManualClock
import org.junit.After
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.function.ThrowingRunnable
import org.junit.rules.Timeout
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Optional
import java.util.concurrent.Callable
import java.util.concurrent.Future

/** Black box integration tests for [HttpConnectorMultiplexer].  */
@RunWith(JUnit4::class)
class HttpConnectorMultiplexerIntegrationTest {
    @Rule
    val globalTimeout: Timeout = Timeout.seconds(20)

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val proxyHelper: ProxyHelper = Mockito.mock<ProxyHelper>(ProxyHelper::class.java)
    private val eventHandler: ExtendedEventHandler =
        Mockito.mock<ExtendedEventHandler>(ExtendedEventHandler::class.java)
    private val clock = ManualClock()
    private val sleeper: Sleeper = Mockito.mock<Sleeper>(Sleeper::class.java)
    private val locale: Locale = Locale.US
    private val connector = HttpConnector(locale, eventHandler, proxyHelper, sleeper, 0.15f)
    private val progressInputStreamFactory = ProgressInputStream.Factory(locale, clock, eventHandler)
    private val httpStreamFactory = HttpStream.Factory(progressInputStreamFactory)
    private val multiplexer = HttpConnectorMultiplexer(eventHandler, connector, httpStreamFactory)

    @Before
    @Throws(Exception::class)
    fun before() {
        Mockito.`when`<ProxyInfo>(proxyHelper.createProxyIfNeeded(ArgumentMatchers.any<URI?>(URI::class.java)))
            .thenReturn(ProxyInfo.NO_PROXY)
    }

    @After
    @Throws(Exception::class)
    fun after() {
        executor.shutdown()
    }

    @Test
    @Throws(Exception::class)
    fun successWithRetry() {
        val barrier: CyclicBarrier = CyclicBarrier(2)
        ServerSocket(0, 1, InetAddress.getByName(null)).use { server ->
            @Suppress("unused") val unused: Future<*>? =
                executor.submit<Any?>(
                    Callable {
                        barrier.await()
                        for (status in mutableListOf<String?>("503 MELTDOWN", "500 ERROR", "200 OK")) {
                            server.accept().use { socket ->
                                readHttpRequest(socket.getInputStream())
                                DownloaderTestUtils.sendLines(
                                    socket,
                                    "HTTP/1.1 " + status,
                                    "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                                    "Connection: close",
                                    "",
                                    "hello"
                                )
                            }
                        }
                        null
                    })
            barrier.await()
            multiplexer.connect(
                URI.create(String.format("http://localhost:%d", server.getLocalPort())),
                HELLO_SHA256
            ).use { stream ->
                Truth.assertThat(ByteStreams.toByteArray(stream)).isEqualTo(
                    "hello".toByteArray(
                        StandardCharsets.US_ASCII
                    )
                )
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun captivePortal_isAvoided() {
        val barrier: CyclicBarrier = CyclicBarrier(2)
        ServerSocket(0, 1, InetAddress.getByName(null)).use { server ->
            @Suppress("unused") val unused: Future<*>? =
                executor.submit<Any?>(
                    Callable {
                        barrier.await()
                        server.accept().use { socket ->
                            readHttpRequest(socket.getInputStream())
                            DownloaderTestUtils.sendLines(
                                socket,
                                "HTTP/1.1 200 OK",
                                "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                                "Connection: close",
                                "",
                                "Never gonna give you up etc."
                            )
                        }
                        null
                    })
            barrier.await()
            val e: IOException? =
                Assert.assertThrows<IOException?>(
                    IOException::class.java,
                    ThrowingRunnable {
                        multiplexer.connect(
                            URI.create(String.format("http://localhost:%d", server.getLocalPort())),
                            HELLO_SHA256
                        )
                    })
            Truth.assertThat(e).hasMessageThat().containsMatch("Checksum was .+ but wanted")
        }
    }

    @Test
    @Throws(Exception::class)
    fun retryButKeepsFailing() {
        val barrier: CyclicBarrier = CyclicBarrier(2)
        ServerSocket(0, 1, InetAddress.getByName(null)).use { server ->
            @Suppress("unused") val unused: Future<*>? =
                executor.submit<Any?>(
                    Callable {
                        barrier.await()
                        while (true) {
                            server.accept().use { socket ->
                                readHttpRequest(socket.getInputStream())
                                DownloaderTestUtils.sendLines(
                                    socket,
                                    "HTTP/1.1 503 MELTDOWN",
                                    "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                                    "Connection: close",
                                    "",
                                    ""
                                )
                            }
                        }
                    })
            barrier.await()
            val e: IOException? =
                Assert.assertThrows<IOException?>(
                    IOException::class.java,
                    ThrowingRunnable {
                        multiplexer.connect(
                            URI.create(String.format("http://localhost:%d", server.getLocalPort())),
                            HELLO_SHA256
                        )
                    })
            Truth.assertThat(e).hasMessageThat().contains("GET returned 503 MELTDOWN")
        }
    }

    companion object {
        private fun makeChecksum(string: String?): Optional<Checksum?> {
            try {
                return Optional.of<T?>(Checksum.fromString(KeyType.SHA256, string!!))
            } catch (e: InvalidChecksumException) {
                throw IllegalStateException(e)
            }
        }

        private val HELLO_SHA256: Optional<Checksum?> =
            makeChecksum("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")
    }
}
