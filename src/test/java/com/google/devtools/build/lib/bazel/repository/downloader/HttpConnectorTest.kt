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

import com.google.common.base.Function
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.io.ByteStreams
import com.google.common.io.CharStreams
import com.google.common.truth.Truth
import com.google.devtools.build.lib.events.EventHandler
import com.google.devtools.build.lib.testutil.ManualClock
import com.google.devtools.build.lib.testutil.ManualSleeper
import org.junit.*
import org.junit.rules.ExpectedException
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Unit tests for [HttpConnector].  */
@RunWith(JUnit4::class)
class HttpConnectorTest {
    @Rule
    val thrown: ExpectedException = ExpectedException.none()

    @Rule
    val testFolder: TemporaryFolder = TemporaryFolder()

    @Rule
    val globalTimeout: Timeout = Timeout.seconds(10)

    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val clock = ManualClock()
    private val sleeper = ManualSleeper(clock)

    /** Scale timeouts down to make tests fast.  */
    private val timeoutScaling = 0.05f

    private val eventHandler: EventHandler = Mockito.mock<EventHandler>(EventHandler::class.java)
    private val proxyHelper: ProxyHelper = Mockito.mock<ProxyHelper>(ProxyHelper::class.java)
    private val connector = HttpConnector(Locale.US, eventHandler, proxyHelper, sleeper, timeoutScaling)

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
    fun localFileDownload() {
        val fileContents: ByteArray? = "this is a test".toByteArray(StandardCharsets.UTF_8)
        Truth.assertThat(
            ByteStreams.toByteArray(
                connector
                    .connect(
                        createTempFile(fileContents).toURI(),
                        com.google.common.base.Function { url: java.net.URI? -> com.google.common.collect.ImmutableMap.of<kotlin.String?, kotlin.collections.MutableList<kotlin.String?>?>() })!!
                    .getInputStream()
            )
        )
            .isEqualTo(fileContents)
    }

    @Test
    @Throws(Exception::class)
    fun badHost_throwsIOException() {
        thrown.expect(IOException::class.java)
        thrown.expectMessage("Unknown host: bad.example")
        connector.connect(
            URI.create("http://bad.example"),
            Function { url: URI? -> ImmutableMap.of<String?, MutableList<String?>?>() })
    }

    @Test
    @Throws(Exception::class)
    fun normalRequest() {
        val headers: MutableMap<String?, MutableList<String?>?> = ConcurrentHashMap<String?, MutableList<String?>?>()
        ServerSocket(0, 1, InetAddress.getByName(null)).use { server ->
            @Suppress("unused") val possiblyIgnoredError: Future<*>? =
                executor.submit<Any?>(
                    object : Callable<Any?> {
                        @Throws(Exception::class)
                        override fun call(): Any? {
                            server.accept().use { socket ->
                                HttpParser.readHttpRequest(socket.getInputStream(), headers)
                                DownloaderTestUtils.sendLines(
                                    socket,
                                    "HTTP/1.1 200 OK",
                                    "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                                    "Connection: close",
                                    "Content-Type: text/plain",
                                    "Content-Length: 5",
                                    "",
                                    "hello"
                                )
                            }
                            return null
                        }
                    })
            InputStreamReader(
                connector
                    .connect(
                        java.net.URI.create(kotlin.String.format("http://localhost:%d/boo", server.getLocalPort())),
                        com.google.common.base.Function { url: java.net.URI? ->
                            com.google.common.collect.ImmutableMap.of<kotlin.String?, kotlin.collections.MutableList<kotlin.String?>?>(
                                "Content-Encoding",
                                com.google.common.collect.ImmutableList.of<kotlin.String?>("gzip")
                            )
                        })!!
                    .getInputStream(),
                StandardCharsets.ISO_8859_1
            ).use { payload ->
                Truth.assertThat(CharStreams.toString(payload)).isEqualTo("hello")
            }
        }
        Truth.assertThat(headers).containsEntry("x-method", ImmutableList.of<String?>("GET"))
        Truth.assertThat(headers).containsEntry("x-request-uri", ImmutableList.of<String?>("/boo"))
        Truth.assertThat(headers).containsEntry("content-encoding", ImmutableList.of<String?>("gzip"))
    }

    @Test
    @Throws(Exception::class)
    fun serverError_retriesConnect() {
        ServerSocket(0, 1, InetAddress.getByName(null)).use { server ->
            @Suppress("unused") val possiblyIgnoredError: Future<*>? =
                executor.submit<Any?>(
                    object : Callable<Any?> {
                        @Throws(Exception::class)
                        override fun call(): Any? {
                            server.accept().use { socket ->
                                readHttpRequest(socket.getInputStream())
                                DownloaderTestUtils.sendLines(
                                    socket,
                                    "HTTP/1.1 500 Incredible Catastrophe",
                                    "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                                    "Connection: close",
                                    "Content-Type: text/plain",
                                    "Content-Length: 8",
                                    "",
                                    "nononono"
                                )
                            }
                            server.accept().use { socket ->
                                readHttpRequest(socket.getInputStream())
                                DownloaderTestUtils.sendLines(
                                    socket,
                                    "HTTP/1.1 200 OK",
                                    "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                                    "Connection: close",
                                    "Content-Type: text/plain",
                                    "Content-Length: 5",
                                    "",
                                    "hello"
                                )
                            }
                            return null
                        }
                    })
            InputStreamReader(
                connector
                    .connect(
                        java.net.URI.create(kotlin.String.format("http://localhost:%d", server.getLocalPort())),
                        com.google.common.base.Function { url: java.net.URI? -> com.google.common.collect.ImmutableMap.of<kotlin.String?, kotlin.collections.MutableList<kotlin.String?>?>() })!!
                    .getInputStream(),
                StandardCharsets.ISO_8859_1
            ).use { payload ->
                Truth.assertThat(CharStreams.toString(payload)).isEqualTo("hello")
                Truth.assertThat(clock.currentTimeMillis()).isGreaterThan(50L)
                Truth.assertThat(clock.currentTimeMillis()).isLessThan(150L)
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun connectionRefused_retries() {
        val port: Int

        ServerSocket(0, 1, InetAddress.getByName(null)).use { server ->
            port = server.getLocalPort()
        }
        val server = AtomicReference<ServerSocket?>()

        try {
            // Schedule server socket to be started only after retry to simulate connection retry.
            sleeper.scheduleRunnable(
                Runnable {
                    try {
                        server.set(ServerSocket(port, 1, InetAddress.getByName(null)))
                    } catch (e: IOException) {
                        throw RuntimeException(e)
                    }
                    @Suppress("unused") val possiblyIgnoredError: Future<*>? =
                        executor.submit<Any?>(
                            Callable {
                                while (!executor.isShutdown()) {
                                    server.get()!!.accept().use { socket ->
                                        readHttpRequest(socket.getInputStream())
                                        DownloaderTestUtils.sendLines(
                                            socket,
                                            "HTTP/1.1 200 OK",
                                            "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                                            "Connection: close",
                                            "Content-Type: text/plain",
                                            "Content-Length: 5",
                                            "",
                                            "hello"
                                        )
                                    }
                                }
                                null
                            })
                },
                1
            )

            InputStreamReader(
                connector
                    .connect(
                        java.net.URI.create(kotlin.String.format("http://localhost:%d", port)),
                        com.google.common.base.Function { url: java.net.URI? -> com.google.common.collect.ImmutableMap.of<kotlin.String?, kotlin.collections.MutableList<kotlin.String?>?>() })!!
                    .getInputStream(),
                StandardCharsets.ISO_8859_1
            ).use { payload ->
                Truth.assertThat(CharStreams.toString(payload)).isEqualTo("hello")
            }
        } finally {
            val serverSocket = server.get()

            if (serverSocket != null) {
                serverSocket.close()
            }
        }
    }

    // Deactivated due to https://github.com/bazelbuild/bazel/issues/9380.
    @Ignore
    @Throws(Exception::class)
    fun socketTimeout_retries() {
        ServerSocket(0, 1, InetAddress.getByName(null)).use { server ->
            @Suppress("unused") val possiblyIgnoredError: Future<*>? =
                executor.submit<Any?>(
                    Callable {
                        server.accept().use { socket -> }
                        // Schedule proper HTTP response once client retries.
                        sleeper.scheduleRunnable(
                            Runnable {
                                @Suppress("unused") val possiblyIgnoredError2 =
                                    executor.submit(
                                        Runnable {
                                            while (!executor.isShutdown()) {
                                                try {
                                                    server.accept().use { socket ->
                                                        readHttpRequest(socket.getInputStream())
                                                        DownloaderTestUtils.sendLines(
                                                            socket,
                                                            "HTTP/1.1 200 OK",
                                                            "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                                                            "Connection: close",
                                                            "Content-Type: text/plain",
                                                            "Content-Length: 5",
                                                            "",
                                                            "hello"
                                                        )
                                                    }
                                                } catch (e: IOException) {
                                                    throw RuntimeException(e)
                                                }
                                            }
                                        })
                            },
                            1
                        )
                        null
                    })
            InputStreamReader(
                connector
                    .connect(
                        java.net.URI.create(kotlin.String.format("http://localhost:%d", server.getLocalPort())),
                        com.google.common.base.Function { url: java.net.URI? -> com.google.common.collect.ImmutableMap.of<kotlin.String?, kotlin.collections.MutableList<kotlin.String?>?>() })!!
                    .getInputStream(),
                StandardCharsets.ISO_8859_1
            ).use { payload ->
                Truth.assertThat(CharStreams.toString(payload)).isEqualTo("hello")
                Truth.assertThat(clock.currentTimeMillis()).isEqualTo(1)
            }
        }
    }

    /**
     * It is important part of [HttpConnector] contract to not throw raw [ ] because it extends [InterruptedIOException] and [ ] relies on [InterruptedIOException] to only be thrown
     * when actual interruption happened.
     */
    @Test
    @Throws(Exception::class)
    fun socketTimeout_throwsIOExceptionInsteadOfSocketTimeoutException() {
        ServerSocket(0, 1, InetAddress.getByName(null)).use { server ->
            @Suppress("unused") val possiblyIgnoredError: Future<*>? =
                executor.submit<Any?>(
                    Callable {
                        server.accept().use { socket -> }
                        null
                    })
            try {
                InputStreamReader(
                    connector
                        .connect(
                            java.net.URI.create(kotlin.String.format("http://localhost:%d", server.getLocalPort())),
                            com.google.common.base.Function { url: java.net.URI? -> com.google.common.collect.ImmutableMap.of<kotlin.String?, kotlin.collections.MutableList<kotlin.String?>?>() })!!
                        .getInputStream(),
                    StandardCharsets.ISO_8859_1
                ).use { payload ->
                    Assert.fail("Should have thrown")
                }
            } catch (expected: IOException) {
                if (expected.cause != null) {
                    // SocketTimeoutException gets wrapped in an IOException and rethrown.
                    Truth.assertThat(expected).hasCauseThat().isInstanceOf(SocketTimeoutException::class.java)
                    Truth.assertThat(expected).hasCauseThat().hasMessageThat().ignoringCase().contains("timed out")
                } else {
                    // For windows, it is possible that the thrown exception is a ConnectException (no cause)
                    // from {@code HttpURLConnection.connect()} rather than a SocketTimeoutException from
                    // {@code HttpURLConnection.getResponseCode()}. In the former case, we expect the
                    // SocketTimeoutException to have already occurred but gets suppressed within the final
                    // ConnectException (upon max retry).
                    val suppressed = ImmutableList.copyOf<Throwable?>(expected.getSuppressed())
                    val ste =
                        suppressed.stream().filter { t: Throwable? -> t is SocketTimeoutException }.findFirst()
                    Truth.assertThat(ste).isPresent()
                    Truth.assertThat(ste.get()).isInstanceOf(SocketTimeoutException::class.java)
                    Truth.assertThat(ste.get()).hasMessageThat().ignoringCase().contains("timed out")
                }
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun permanentError_doesNotRetryAndThrowsIOException() {
        ServerSocket(0, 1, InetAddress.getByName(null)).use { server ->
            @Suppress("unused") val possiblyIgnoredError: Future<*>? =
                executor.submit<Any?>(
                    object : Callable<Any?> {
                        @Throws(Exception::class)
                        override fun call(): Any? {
                            server.accept().use { socket ->
                                readHttpRequest(socket.getInputStream())
                                DownloaderTestUtils.sendLines(
                                    socket,
                                    "HTTP/1.1 401 Unauthorized",
                                    "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                                    "Connection: close",
                                    "Content-Type: text/plain",
                                    "Content-Length: 0",
                                    "",
                                    ""
                                )
                            }
                            return null
                        }
                    })
            thrown.expect(IOException::class.java)
            thrown.expectMessage("401 Unauthorized")
            connector.connect(
                URI.create(String.format("http://localhost:%d", server.getLocalPort())),
                Function { url: URI? -> ImmutableMap.of<String?, MutableList<String?>?>() })
        }
    }

    @Test
    @Throws(Exception::class)
    fun permanentErrorNotFound_doesNotRetryAndThrowsFileNotFoundException() {
        ServerSocket(0, 1, InetAddress.getByName(null)).use { server ->
            @Suppress("unused") val possiblyIgnoredError: Future<*>? =
                executor.submit<Any?>(
                    object : Callable<Any?> {
                        @Throws(Exception::class)
                        override fun call(): Any? {
                            server.accept().use { socket ->
                                readHttpRequest(socket.getInputStream())
                                DownloaderTestUtils.sendLines(
                                    socket,
                                    "HTTP/1.1 404 Not Found",
                                    "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                                    "Connection: close",
                                    "Content-Type: text/plain",
                                    "Content-Length: 0",
                                    "",
                                    ""
                                )
                            }
                            return null
                        }
                    })
            thrown.expect(FileNotFoundException::class.java)
            thrown.expectMessage("404 Not Found")
            connector.connect(
                URI.create(String.format("http://localhost:%d", server.getLocalPort())),
                Function { url: URI? -> ImmutableMap.of<String?, MutableList<String?>?>() })
        }
    }

    @Test
    @Throws(Exception::class)
    fun permanentError_consumesPayloadBeforeReturningn() {
        val barrier = CyclicBarrier(2)
        val consumed = AtomicBoolean()
        try {
            ServerSocket(0, 1, InetAddress.getByName(null)).use { server ->
                @Suppress("unused") val possiblyIgnoredError: Future<*>? =
                    executor.submit<Any?>(
                        object : Callable<Any?> {
                            @Throws(Exception::class)
                            override fun call(): Any? {
                                try {
                                    server.accept().use { socket ->
                                        readHttpRequest(socket.getInputStream())
                                        DownloaderTestUtils.sendLines(
                                            socket,
                                            "HTTP/1.1 501 Oh No",
                                            "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                                            "Connection: close",
                                            "Content-Type: text/plain",
                                            "Content-Length: 1",
                                            "",
                                            "b"
                                        )
                                        consumed.set(true)
                                    }
                                } finally {
                                    barrier.await()
                                }
                                return null
                            }
                        })
                connector.connect(
                    URI.create(String.format("http://localhost:%d", server.getLocalPort())),
                    Function { url: URI? -> ImmutableMap.of<String?, MutableList<String?>?>() })
                Assert.fail()
            }
        } catch (ignored: IOException) {
            // ignored
        } finally {
            barrier.await()
        }
        Truth.assertThat(consumed.get()).isTrue()
        Truth.assertThat(clock.currentTimeMillis()).isEqualTo(0L)
    }

    @Test
    @Throws(Exception::class)
    fun always500_givesUpEventually() {
        val tries = AtomicInteger()
        ServerSocket(0, 1, InetAddress.getByName(null)).use { server ->
            @Suppress("unused") val possiblyIgnoredError: Future<*>? =
                executor.submit<Any?>(
                    object : Callable<Any?> {
                        @Throws(Exception::class)
                        override fun call(): Any? {
                            while (true) {
                                server.accept().use { socket ->
                                    readHttpRequest(socket.getInputStream())
                                    DownloaderTestUtils.sendLines(
                                        socket,
                                        "HTTP/1.1 500 Oh My",
                                        "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                                        "Connection: close",
                                        "Content-Type: text/plain",
                                        "Content-Length: 0",
                                        "",
                                        ""
                                    )
                                    tries.incrementAndGet()
                                }
                            }
                        }
                    })
            thrown.expect(IOException::class.java)
            thrown.expectMessage("500 Oh My")
            try {
                val unused =
                    connector.connect(
                        URI.create(String.format("http://localhost:%d", server.getLocalPort())),
                        Function { url: URI? -> ImmutableMap.of<String?, MutableList<String?>?>() })
            } finally {
                Truth.assertThat(tries.get()).isGreaterThan(2)
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun serverSays403_clientRetriesAnyway() {
        val tries = AtomicInteger()
        ServerSocket(0, 1, InetAddress.getByName(null)).use { server ->
            @Suppress("unused") val possiblyIgnoredError: Future<*>? =
                executor.submit<Any?>(
                    object : Callable<Any?> {
                        @Throws(Exception::class)
                        override fun call(): Any? {
                            while (true) {
                                server.accept().use { socket ->
                                    readHttpRequest(socket.getInputStream())
                                    DownloaderTestUtils.sendLines(
                                        socket,
                                        "HTTP/1.1 403 Forbidden",
                                        "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                                        "Connection: close",
                                        "Content-Type: text/plain",
                                        "Content-Length: 0",
                                        "",
                                        ""
                                    )
                                    tries.incrementAndGet()
                                }
                            }
                        }
                    })
            thrown.expect(IOException::class.java)
            thrown.expectMessage("403 Forbidden")
            try {
                val unused =
                    connector.connect(
                        URI.create(String.format("http://localhost:%d", server.getLocalPort())),
                        Function { url: URI? -> ImmutableMap.of<String?, MutableList<String?>?>() })
            } finally {
                Truth.assertThat(tries.get()).isGreaterThan(2)
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun pathRedirect_301() {
        redirectToDifferentPath_works("301")
    }

    @Test
    @Throws(Exception::class)
    fun serverRedirect_301() {
        redirectToDifferentServer_works("301")
    }

    /*
   * Also tests behavior for 302 and 307 codes.
   */
    @Test
    @Throws(Exception::class)
    fun pathRedirect_303() {
        redirectToDifferentPath_works("303")
    }

    @Test
    @Throws(Exception::class)
    fun serverRedirects_303() {
        redirectToDifferentServer_works("303")
    }

    @Throws(Exception::class)
    fun redirectToDifferentPath_works(code: String) {
        val redirectCode = "HTTP/1.1 " + code + " Redirect"
        val headers1: MutableMap<String?, MutableList<String?>?> = ConcurrentHashMap<String?, MutableList<String?>?>()
        val headers2: MutableMap<String?, MutableList<String?>?> = ConcurrentHashMap<String?, MutableList<String?>?>()
        ServerSocket(0, 1, InetAddress.getByName(null)).use { server ->
            @Suppress("unused") val possiblyIgnoredError: Future<*>? =
                executor.submit<Any?>(
                    object : Callable<Any?> {
                        @Throws(Exception::class)
                        override fun call(): Any? {
                            server.accept().use { socket ->
                                HttpParser.readHttpRequest(socket.getInputStream(), headers1)
                                DownloaderTestUtils.sendLines(
                                    socket,
                                    redirectCode,
                                    "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                                    "Connection: close",
                                    "Location: /doodle.tar.gz",
                                    "Content-Length: 0",
                                    "",
                                    ""
                                )
                            }
                            server.accept().use { socket ->
                                HttpParser.readHttpRequest(socket.getInputStream(), headers2)
                                DownloaderTestUtils.sendLines(
                                    socket,
                                    "HTTP/1.1 200 OK",
                                    "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                                    "Connection: close",
                                    "Content-Type: text/plain",
                                    "Content-Length: 0",
                                    "",
                                    ""
                                )
                            }
                            return null
                        }
                    })
            val connection =
                connector.connect(
                    URI.create(String.format("http://localhost:%d", server.getLocalPort())),
                    Function { url: URI? -> ImmutableMap.of<String?, MutableList<String?>?>() })
            Truth.assertThat(connection!!.getURL())
                .isEqualTo(
                    URI.create(String.format("http://localhost:%d/doodle.tar.gz", server.getLocalPort()))
                        .toURL()
                )
            connection.getInputStream().use { input ->
                Truth.assertThat(ByteStreams.toByteArray(input)).isEmpty()
            }
        }
        Truth.assertThat(headers1).containsEntry("x-request-uri", ImmutableList.of<String?>("/"))
        Truth.assertThat(headers2).containsEntry("x-request-uri", ImmutableList.of<String?>("/doodle.tar.gz"))
    }

    @Throws(Exception::class)
    fun redirectToDifferentServer_works(code: String) {
        val redirectCode = "HTTP/1.1 " + code + " Redirect"
        val basic1 = "Basic b25lOmZpcnN0c2VjcmV0"
        val basic2 = "Basic dHdvOnNlY29uZHNlY3JldA=="
        val headers1: MutableMap<String?, MutableList<String?>?> = ConcurrentHashMap<String?, MutableList<String?>?>()
        val headers2: MutableMap<String?, MutableList<String?>?> = ConcurrentHashMap<String?, MutableList<String?>?>()
        ServerSocket(0, 1, InetAddress.getByName(null)).use { server1 ->
            ServerSocket(0, 1, InetAddress.getByName(null)).use { server2 ->
                @Suppress("unused") val possiblyIgnoredError: Future<*>? =
                    executor.submit<Any?>(
                        object : Callable<Any?> {
                            @Throws(Exception::class)
                            override fun call(): Any? {
                                server1.accept().use { socket ->
                                    HttpParser.readHttpRequest(socket.getInputStream(), headers1)
                                    DownloaderTestUtils.sendLines(
                                        socket,
                                        redirectCode,
                                        "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                                        "Connection: close",
                                        String.format(
                                            "Location: http://localhost:%d/doodle.tar.gz", server2.getLocalPort()
                                        ),
                                        "Content-Length: 0",
                                        "",
                                        ""
                                    )
                                }
                                return null
                            }
                        })
                @Suppress("unused") val possiblyIgnoredError1: Future<*>? =
                    executor.submit<Any?>(
                        object : Callable<Any?> {
                            @Throws(Exception::class)
                            override fun call(): Any? {
                                server2.accept().use { socket ->
                                    HttpParser.readHttpRequest(socket.getInputStream(), headers2)
                                    DownloaderTestUtils.sendLines(
                                        socket,
                                        "HTTP/1.1 200 OK",
                                        "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                                        "Connection: close",
                                        "Content-Type: text/plain",
                                        "Content-Length: 5",
                                        "",
                                        "hello"
                                    )
                                }
                                return null
                            }
                        })
                // Header function that provides different auth headers for
                // the two servers.
                val authHeaders: Function<URI?, ImmutableMap<String?, MutableList<String?>?>?> =
                    object : Function<URI?, ImmutableMap<String?, MutableList<String?>?>?> {
                        override fun apply(url: URI): ImmutableMap<String?, MutableList<String?>?> {
                            if (url.getPort() == server1.getLocalPort()) {
                                return ImmutableMap.of<String?, MutableList<String?>?>(
                                    "Authentication",
                                    ImmutableList.of<String?>(basic1)
                                )
                            } else if (url.getPort() == server2.getLocalPort()) {
                                return ImmutableMap.of<String?, MutableList<String?>?>(
                                    "Authentication",
                                    ImmutableList.of<String?>(basic2)
                                )
                            } else {
                                return ImmutableMap.of<String?, MutableList<String?>?>()
                            }
                        }
                    }
                val connection =
                    connector.connect(
                        URI.create(String.format("http://localhost:%d", server1.getLocalPort())),
                        authHeaders
                    )
                Truth.assertThat(connection!!.getURL())
                    .isEqualTo(
                        URI.create(String.format("http://localhost:%d/doodle.tar.gz", server2.getLocalPort()))
                            .toURL()
                    )
                connection.getInputStream().use { input ->
                    Truth.assertThat(ByteStreams.toByteArray(input)).isEqualTo(
                        "hello".toByteArray(
                            StandardCharsets.US_ASCII
                        )
                    )
                }
                // Verify that the correct form of authentication is used for each server.
                Truth.assertThat(headers1).containsEntry("authentication", ImmutableList.of<String?>(basic1))
                Truth.assertThat(headers2).containsEntry("authentication", ImmutableList.of<String?>(basic2))
            }
        }
    }

    @Throws(IOException::class)
    private fun createTempFile(fileContents: ByteArray?): File {
        val temp = testFolder.newFile()
        FileOutputStream(temp).use { outputStream ->
            outputStream.write(fileContents)
        }
        return temp
    }
}
