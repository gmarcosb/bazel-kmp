// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.hash.Hashing
import com.google.common.io.ByteStreams
import com.google.devtools.build.lib.authandtls.StaticCredentials
import org.junit.After
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.function.ThrowingRunnable
import org.junit.rules.Timeout
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.Future

/** Tests for [HttpDownloader]  */
@RunWith(JUnit4::class)
class HttpDownloaderTest {
    @Rule
    val workingDir: TemporaryFolder = TemporaryFolder()

    @Rule
    val timeout: Timeout = Timeout(30, TimeUnit.SECONDS)

    private val eventHandler: ExtendedEventHandler? = null
    fun <ExtendedEventHandler> mock()
    private val downloadCache: DownloadCache? = null
    fun <DownloadCache> mock()

    // Scale timeouts down to make test fast.
    private val httpDownloader = HttpDownloader(0, Duration.ZERO, 8, .1f)
    private val downloadManager = DownloadManager(downloadCache, httpDownloader, httpDownloader, eventHandler)

    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val fs: JavaIoFileSystem

    init {
        fs = JavaIoFileSystem(DigestHashFunction.SHA256)
    }

    @After
    fun after() {
        executor.shutdown()
    }

    @Test
    @Throws(IOException::class, InterruptedException::class)
    fun downloadFrom1UrlOk() {
        ServerSocket(0, 1, InetAddress.getByName(null)).use { server ->
            @Suppress("unused") val possiblyIgnoredError: Future<*> =
                executor.submit<Any?>(
                    Callable {
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
                        null
                    })
            val resultingFile: Path =
                download(
                    downloadManager,
                    mutableListOf<URI?>(
                        URI.create(String.format("http://localhost:%d/foo", server.getLocalPort()))
                    ),
                    mutableMapOf<String?, MutableList<String?>?>(),
                    mutableMapOf<URI?, MutableMap<String?, MutableList<String?>?>?>(),
                    Optional.empty<Checksum?>(),
                    "testCanonicalId",
                    Optional.empty<String?>(),
                    fs.getPath(workingDir.newFile().getAbsolutePath()),
                    mutableMapOf<String?, String?>(),
                    "testRepo"
                )
            Truth.assertThat(String(FileSystemUtils.readContent(resultingFile), StandardCharsets.UTF_8))
                .isEqualTo("hello")
        }
    }

    @Test
    @Throws(IOException::class, InterruptedException::class)
    fun downloadFrom1UrlOk_specialCharInBasename() {
        ServerSocket(0, 1, InetAddress.getByName(null)).use { server ->
            @Suppress("unused") val possiblyIgnoredError: Future<*> =
                executor.submit<Any?>(
                    Callable {
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
                        null
                    })
            val resultingFile: Path =
                download(
                    downloadManager,
                    mutableListOf<URI?>(
                        URI.create(
                            String.format("http://localhost:%d/arch:ve.zip", server.getLocalPort())
                        )
                    ),
                    mutableMapOf<String?, MutableList<String?>?>(),
                    mutableMapOf<URI?, MutableMap<String?, MutableList<String?>?>?>(),
                    Optional.empty<Checksum?>(),
                    "testCanonicalId",
                    Optional.of<String?>("zip"),
                    fs.getPath(workingDir.newFolder().getAbsolutePath()),
                    mutableMapOf<String?, String?>(),
                    "testRepo"
                )

            Truth.assertThat(String(FileSystemUtils.readContent(resultingFile), StandardCharsets.UTF_8))
                .isEqualTo("hello")
            assertThat(resultingFile.asFragment().getFileExtension()).isEqualTo("zip")
            assertThat(resultingFile.asFragment().getBaseName()).doesNotContain(":")
        }
    }

    @Test
    @Throws(Exception::class)
    fun downloadFromOpaqueFileUrl_withExplicitOutput() {
        val downloader: Downloader
        Downloader > Mockito.mock<Downloader?>(Downloader::class.java)
        val downloadManager =
            DownloadManager(downloadCache, downloader, httpDownloader, eventHandler)
        val data: ByteArray = "content".toByteArray(StandardCharsets.UTF_8)
        Mockito.doAnswer(
            Answer { invocationOnMock: InvocationOnMock? ->
                val output: Path = invocationOnMock.getArgument<Path>(5, Path::class.java)
                output.getOutputStream().use { outputStream ->
                    ByteStreams.copy(ByteArrayInputStream(data), outputStream)
                }
                null
            } as Answer<Void?>)
            .`when`<Downloader?>(downloader)
            .download(TODO("Cannot convert element")) < List < URI shr ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        String > ArgumentMatchers.any<Any?>()
        Path > ArgumentMatchers.any<Any?>()
        ExtendedEventHandler > ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        String > ArgumentMatchers.eq<String?>("testRepo")


        val result: Path =
            download(
                downloadManager,
                ImmutableList.of<URI?>(URI.create("file:../vendored_lodash-4.17.21.tgz")),
                ImmutableMap.of<String?, MutableList<String?>?>(),
                ImmutableMap.of<URI?, MutableMap<String?, MutableList<String?>?>?>(),
                Optional.empty<Checksum?>(),
                "testCanonicalId",
                Optional.empty<String?>(),
                fs.getPath(workingDir.newFile().getAbsolutePath()),
                ImmutableMap.of<String?, String?>(),
                "testRepo"
            )

        Truth.assertThat(String(ByteStreams.toByteArray(result.getInputStream()), StandardCharsets.UTF_8))
            .isEqualTo("content")
    }

    @Test
    @Throws(Exception::class)
    fun downloadFromOpaqueFileUrl_withType() {
        val downloader: Downloader
        Downloader > Mockito.mock<Downloader?>(Downloader::class.java)
        val downloadManager =
            DownloadManager(downloadCache, downloader, httpDownloader, eventHandler)
        val data: ByteArray = "content".toByteArray(StandardCharsets.UTF_8)
        Mockito.doAnswer(
            Answer { invocationOnMock: InvocationOnMock? ->
                val output: Path = invocationOnMock.getArgument<Path>(5, Path::class.java)
                output.getOutputStream().use { outputStream ->
                    ByteStreams.copy(ByteArrayInputStream(data), outputStream)
                }
                null
            } as Answer<Void?>)
            .`when`<Downloader?>(downloader)
            .download(TODO("Cannot convert element")) < List < URI shr ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        String > ArgumentMatchers.any<Any?>()
        Path > ArgumentMatchers.any<Any?>()
        ExtendedEventHandler > ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        String > ArgumentMatchers.eq<String?>("testRepo")


        val result: Path =
            download(
                downloadManager,
                ImmutableList.of<URI?>(URI.create("file:../vendored_lodash-4.17.21.tgz")),
                ImmutableMap.of<String?, MutableList<String?>?>(),
                ImmutableMap.of<URI?, MutableMap<String?, MutableList<String?>?>?>(),
                Optional.empty<Checksum?>(),
                "testCanonicalId",
                Optional.of<String?>("tgz"),
                fs.getPath(workingDir.newFolder().getAbsolutePath()),
                ImmutableMap.of<String?, String?>(),
                "testRepo"
            )

        assertThat(result.getBaseName()).isEqualTo("vendored_lodash-4.17.21.tgz")
        Truth.assertThat(String(ByteStreams.toByteArray(result.getInputStream()), StandardCharsets.UTF_8))
            .isEqualTo("content")
    }

    @Test
    @Throws(Exception::class)
    fun downloadFromOpaqueFileUrl_withEscapedQuestionMarkAndType() {
        val downloader: Downloader
        Downloader > Mockito.mock<Downloader?>(Downloader::class.java)
        val downloadManager =
            DownloadManager(downloadCache, downloader, httpDownloader, eventHandler)
        val data: ByteArray = "content".toByteArray(StandardCharsets.UTF_8)
        Mockito.doAnswer(
            Answer { invocationOnMock: InvocationOnMock? ->
                val output: Path = invocationOnMock.getArgument<Path>(5, Path::class.java)
                output.getOutputStream().use { outputStream ->
                    ByteStreams.copy(ByteArrayInputStream(data), outputStream)
                }
                null
            } as Answer<Void?>)
            .`when`<Downloader?>(downloader)
            .download(TODO("Cannot convert element")) < List < URI shr ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        String > ArgumentMatchers.any<Any?>()
        Path > ArgumentMatchers.any<Any?>()
        ExtendedEventHandler > ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        String > ArgumentMatchers.eq<String?>("testRepo")


        val result: Path =
            download(
                downloadManager,
                ImmutableList.of<URI?>(URI.create("file:../foo%3Fbar.tgz")),
                ImmutableMap.of<String?, MutableList<String?>?>(),
                ImmutableMap.of<URI?, MutableMap<String?, MutableList<String?>?>?>(),
                Optional.empty<Checksum?>(),
                "testCanonicalId",
                Optional.of<String?>("tgz"),
                fs.getPath(workingDir.newFolder().getAbsolutePath()),
                ImmutableMap.of<String?, String?>(),
                "testRepo"
            )

        assertThat(result.getBaseName()).isEqualTo("foo_bar.tgz")
        Truth.assertThat(String(ByteStreams.toByteArray(result.getInputStream()), StandardCharsets.UTF_8))
            .isEqualTo("content")
    }

    @Test
    fun getCandidateFileNames_opaqueFileUrlWithEscapedQuestionMark() {
        assertThat(
            DownloadManager.getCandidateFileNames(
                URI.create("file:../foo%3Fbar.tgz"), fs.getPath("/tmp/foo_bar.tgz")
            )
        )
            .containsExactly("foo?bar.tgz", "foo_bar.tgz")
    }

    @Test
    @Throws(IOException::class, InterruptedException::class)
    fun downloadFrom2UrlsFirstOk() {
        ServerSocket(0, 1, InetAddress.getByName(null)).use { server1 ->
            ServerSocket(0, 1, InetAddress.getByName(null)).use { server2 ->
                @Suppress("unused") val possiblyIgnoredError: Future<*> =
                    executor.submit<Any?>(
                        Callable {
                            while (!executor.isShutdown()) {
                                server1.accept().use { socket ->
                                    readHttpRequest(socket.getInputStream())
                                    DownloaderTestUtils.sendLines(
                                        socket,
                                        "HTTP/1.1 200 OK",
                                        "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                                        "Connection: close",
                                        "Content-Type: text/plain",
                                        "",
                                        "content1"
                                    )
                                }
                            }
                            null
                        })
                @Suppress("unused") val possiblyIgnoredError2: Future<*> =
                    executor.submit<Any?>(
                        Callable {
                            while (!executor.isShutdown()) {
                                server2.accept().use { socket ->
                                    readHttpRequest(socket.getInputStream())
                                    DownloaderTestUtils.sendLines(
                                        socket,
                                        "HTTP/1.1 200 OK",
                                        "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                                        "Connection: close",
                                        "Content-Type: text/plain",
                                        "",
                                        "content2"
                                    )
                                }
                            }
                            null
                        })

                val urls: MutableList<URI?> = ArrayList<URI?>(2)
                urls.add(URI.create(String.format("http://localhost:%d/foo", server1.getLocalPort())))
                urls.add(URI.create(String.format("http://localhost:%d/foo", server2.getLocalPort())))

                val resultingFile: Path =
                    download(
                        downloadManager,
                        urls,
                        mutableMapOf<String?, MutableList<String?>?>(),
                        mutableMapOf<URI?, MutableMap<String?, MutableList<String?>?>?>(),
                        Optional.empty<Checksum?>(),
                        "testCanonicalId",
                        Optional.empty<String?>(),
                        fs.getPath(workingDir.newFile().getAbsolutePath()),
                        mutableMapOf<String?, String?>(),
                        "testRepo"
                    )
                Truth.assertThat(String(FileSystemUtils.readContent(resultingFile), StandardCharsets.UTF_8))
                    .isEqualTo("content1")
            }
        }
    }

    @Ignore("b/182150157")
    @Test
    @Throws(IOException::class, InterruptedException::class)
    fun downloadFrom2UrlsFirstSocketTimeoutOnBodyReadSecondOk() {
        ServerSocket(0, 1, InetAddress.getByName(null)).use { server1 ->
            ServerSocket(0, 1, InetAddress.getByName(null)).use { server2 ->
                @Suppress("unused") val possiblyIgnoredError: Future<*> =
                    executor.submit<Any?>(
                        Callable {
                            val socket: Socket = server1.accept()
                            readHttpRequest(socket.getInputStream())

                            DownloaderTestUtils.sendLines(
                                socket,
                                "HTTP/1.1 200 OK",
                                "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                                "Connection: close",
                                "Content-Type: text/plain",
                                "",
                                "content1"
                            )
                            null
                        })
                @Suppress("unused") val possiblyIgnoredError2: Future<*> =
                    executor.submit<Any?>(
                        Callable {
                            while (!executor.isShutdown()) {
                                server2.accept().use { socket ->
                                    readHttpRequest(socket.getInputStream())
                                    DownloaderTestUtils.sendLines(
                                        socket,
                                        "HTTP/1.1 200 OK",
                                        "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                                        "Connection: close",
                                        "Content-Type: text/plain",
                                        "",
                                        "content2"
                                    )
                                }
                            }
                            null
                        })

                val urls: MutableList<URI?> = ArrayList<URI?>(2)
                urls.add(URI.create(String.format("http://localhost:%d/foo", server1.getLocalPort())))
                urls.add(URI.create(String.format("http://localhost:%d/foo", server2.getLocalPort())))

                val resultingFile: Path =
                    download(
                        downloadManager,
                        urls,
                        mutableMapOf<String?, MutableList<String?>?>(),
                        mutableMapOf<URI?, MutableMap<String?, MutableList<String?>?>?>(),
                        Optional.empty<Checksum?>(),
                        "testCanonicalId",
                        Optional.empty<String?>(),
                        fs.getPath(workingDir.newFile().getAbsolutePath()),
                        mutableMapOf<String?, String?>(),
                        "testRepo"
                    )
                Truth.assertThat(String(FileSystemUtils.readContent(resultingFile), StandardCharsets.UTF_8))
                    .isEqualTo("content2")
            }
        }
    }

    @Ignore("b/182150157")
    @Test
    @Throws(IOException::class, InterruptedException::class)
    fun downloadFrom2UrlsBothSocketTimeoutDuringBodyRead() {
        ServerSocket(0, 1, InetAddress.getByName(null)).use { server1 ->
            ServerSocket(0, 1, InetAddress.getByName(null)).use { server2 ->
                @Suppress("unused") val possiblyIgnoredError: Future<*> =
                    executor.submit<Any?>(
                        Callable {
                            val socket: Socket = server1.accept()
                            readHttpRequest(socket.getInputStream())

                            DownloaderTestUtils.sendLines(
                                socket,
                                "HTTP/1.1 200 OK",
                                "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                                "Connection: close",
                                "Content-Type: text/plain",
                                "",
                                "content1"
                            )
                            null
                        })
                @Suppress("unused") val possiblyIgnoredError2: Future<*> =
                    executor.submit<Any?>(
                        Callable {
                            val socket: Socket = server1.accept()
                            readHttpRequest(socket.getInputStream())

                            DownloaderTestUtils.sendLines(
                                socket,
                                "HTTP/1.1 200 OK",
                                "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                                "Connection: close",
                                "Content-Type: text/plain",
                                "",
                                "content2"
                            )
                            null
                        })

                val urls: MutableList<URI?> = ArrayList<URI?>(2)
                urls.add(URI.create(String.format("http://localhost:%d/foo", server1.getLocalPort())))
                urls.add(URI.create(String.format("http://localhost:%d/foo", server2.getLocalPort())))

                val outputFile: Path = fs.getPath(workingDir.newFile().getAbsolutePath())
                try {
                    download(
                        downloadManager,
                        urls,
                        mutableMapOf<String?, MutableList<String?>?>(),
                        mutableMapOf<URI?, MutableMap<String?, MutableList<String?>?>?>(),
                        Optional.empty<Checksum?>(),
                        "testCanonicalId",
                        Optional.empty<String?>(),
                        outputFile,
                        mutableMapOf<String?, String?>(),
                        "testRepo"
                    )
                    Assert.fail("Should have thrown")
                } catch (expected: IOException) {
                    Truth.assertThat<Throwable?>(expected.getSuppressed()).hasLength(2)

                    for (suppressed in expected.getSuppressed()) {
                        Truth.assertThat(suppressed).isInstanceOf(IOException::class.java)
                        Truth.assertThat(suppressed).hasCauseThat().isInstanceOf(SocketTimeoutException::class.java)
                    }
                }
            }
        }
    }

    @Test
    @Throws(IOException::class, InterruptedException::class)
    fun downloadFrom2UrlsFirstTlsErrorSecondOk() {
        ServerSocket(0, 1, InetAddress.getByName(null)).use { server1 ->
            ServerSocket(0, 1, InetAddress.getByName(null)).use { server2 ->
                val server1Future: Future<*> =
                    executor.submit<Any?>(
                        Callable {
                            server1.accept().use { socket ->
                                // Write garbage to trigger SSL handshake failure on client
                                socket.getOutputStream().write("Not SSL".toByteArray(StandardCharsets.UTF_8))
                            }
                            null
                        })
                val server2Future: Future<*> =
                    executor.submit<Any?>(
                        Callable {
                            server2.accept().use { socket ->
                                readHttpRequest(socket.getInputStream())
                                DownloaderTestUtils.sendLines(
                                    socket,
                                    "HTTP/1.1 200 OK",
                                    "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                                    "Connection: close",
                                    "Content-Type: text/plain",
                                    "",
                                    "content2"
                                )
                            }
                            null
                        })

                val urls: MutableList<URI?> = ArrayList<URI?>(2)
                // Use https for the first one to trigger SSL handshake
                urls.add(URI.create(String.format("https://localhost:%d/foo", server1.getLocalPort())))
                urls.add(URI.create(String.format("http://localhost:%d/foo", server2.getLocalPort())))

                val resultingFile: Path = fs.getPath(workingDir.newFile().getAbsolutePath())

                httpDownloader.download(
                    urls,
                    ImmutableMap.of<String?, MutableList<String?>?>(),
                    StaticCredentials.EMPTY,
                    Optional.empty<Checksum?>(),
                    "testCanonicalId",
                    resultingFile,
                    eventHandler,
                    ImmutableMap.of<String?, String?>(),
                    Optional.empty<String?>(),
                    "testRepo"
                )

                try {
                    server1Future.get()
                    server2Future.get()
                } catch (e: ExecutionException) {
                    throw IOException(e.cause)
                }
                Truth.assertThat(String(FileSystemUtils.readContent(resultingFile), StandardCharsets.UTF_8))
                    .isEqualTo("content2")
            }
        }
    }

    @Test
    @Throws(IOException::class, InterruptedException::class)
    fun downloadOneUrl_ok() {
        ServerSocket(0, 1, InetAddress.getByName(null)).use { server ->
            @Suppress("unused") val possiblyIgnoredError: Future<*> =
                executor.submit<Any?>(
                    Callable {
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
                        null
                    })
            val destination: Path = fs.getPath(workingDir.newFile().getAbsolutePath())
            httpDownloader.download(
                mutableListOf<URI>(
                    URI.create(String.format("http://localhost:%d/foo", server.getLocalPort()))
                ),
                mutableMapOf<String?, MutableList<String?>?>(),
                StaticCredentials.EMPTY,
                Optional.empty<Checksum?>(),
                "testCanonicalId",
                destination,
                eventHandler,
                mutableMapOf<String?, String?>(),
                Optional.empty<String?>(),
                "context"
            )
            Truth.assertThat(String(FileSystemUtils.readContent(destination), StandardCharsets.UTF_8))
                .isEqualTo("hello")
        }
    }

    @Test
    @Throws(IOException::class, InterruptedException::class)
    fun downloadOneUrl_notFound() {
        ServerSocket(0, 1, InetAddress.getByName(null)).use { server ->
            @Suppress("unused") val possiblyIgnoredError: Future<*> =
                executor.submit<Any?>(
                    Callable {
                        server.accept().use { socket ->
                            readHttpRequest(socket.getInputStream())
                            DownloaderTestUtils.sendLines(
                                socket,
                                "HTTP/1.1 404 Not Found",
                                "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                                "Connection: close",
                                "Content-Type: text/plain",
                                "Content-Length: 5",
                                "",
                                ""
                            )
                        }
                        null
                    })
            Assert.assertThrows<IOException?>(
                IOException::class.java,
                ThrowingRunnable {
                    httpDownloader.download(
                        mutableListOf<URI>(
                            URI.create(String.format("http://localhost:%d/foo", server.getLocalPort()))
                        ),
                        mutableMapOf<String?, MutableList<String?>?>(),
                        StaticCredentials.EMPTY,
                        Optional.empty<Checksum?>(),
                        "testCanonicalId",
                        fs.getPath(workingDir.newFile().getAbsolutePath()),
                        eventHandler,
                        mutableMapOf<String?, String?>(),
                        Optional.empty<String?>(),
                        "context"
                    )
                })
        }
    }

    @Test
    @Throws(IOException::class, InterruptedException::class)
    fun downloadTwoUrls_firstNotFoundAndSecondOk() {
        ServerSocket(0, 1, InetAddress.getByName(null)).use { server1 ->
            ServerSocket(0, 1, InetAddress.getByName(null)).use { server2 ->
                @Suppress("unused") val possiblyIgnoredError: Future<*> =
                    executor.submit<Any?>(
                        Callable {
                            server1.accept().use { socket ->
                                readHttpRequest(socket.getInputStream())
                                DownloaderTestUtils.sendLines(
                                    socket,
                                    "HTTP/1.1 404 Not Found",
                                    "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                                    "Connection: close",
                                    "Content-Type: text/plain",
                                    "Content-Length: 5",
                                    "",
                                    ""
                                )
                            }
                            null
                        })
                @Suppress("unused") val possiblyIgnoredError2: Future<*> =
                    executor.submit<Any?>(
                        Callable {
                            while (!executor.isShutdown()) {
                                server2.accept().use { socket ->
                                    readHttpRequest(socket.getInputStream())
                                    DownloaderTestUtils.sendLines(
                                        socket,
                                        "HTTP/1.1 200 OK",
                                        "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                                        "Connection: close",
                                        "Content-Type: text/plain",
                                        "",
                                        "content2"
                                    )
                                }
                            }
                            null
                        })

                val urls: MutableList<URI?> = ArrayList<URI?>(2)
                urls.add(URI.create(String.format("http://localhost:%d/foo", server1.getLocalPort())))
                urls.add(URI.create(String.format("http://localhost:%d/foo", server2.getLocalPort())))

                val destination: Path = fs.getPath(workingDir.newFile().getAbsolutePath())
                httpDownloader.download(
                    urls,
                    mutableMapOf<String?, MutableList<String?>?>(),
                    StaticCredentials.EMPTY,
                    Optional.empty<Checksum?>(),
                    "testCanonicalId",
                    destination,
                    eventHandler,
                    mutableMapOf<String?, String?>(),
                    Optional.empty<String?>(),
                    "context"
                )
                Truth.assertThat(String(FileSystemUtils.readContent(destination), StandardCharsets.UTF_8))
                    .isEqualTo("content2")
            }
        }
    }

    @Test
    @Throws(IOException::class, InterruptedException::class)
    fun downloadAndReadOneUrl_ok() {
        ServerSocket(0, 1, InetAddress.getByName(null)).use { server ->
            @Suppress("unused") val possiblyIgnoredError: Future<*> =
                executor.submit<Any?>(
                    Callable {
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
                        null
                    })
            Truth.assertThat(
                String(
                    httpDownloader.downloadAndReadOneUrl(
                        URI.create(String.format("http://localhost:%d/foo", server.getLocalPort())),
                        StaticCredentials.EMPTY,
                        Optional.empty<Checksum?>(),
                        eventHandler,
                        mutableMapOf<String?, String?>()
                    ),
                    StandardCharsets.UTF_8
                )
            )
                .isEqualTo("hello")
        }
    }

    @Test
    @Throws(IOException::class, InterruptedException::class)
    fun downloadAndReadOneUrl_notFound() {
        ServerSocket(0, 1, InetAddress.getByName(null)).use { server ->
            @Suppress("unused") val possiblyIgnoredError: Future<*> =
                executor.submit<Any?>(
                    Callable {
                        server.accept().use { socket ->
                            readHttpRequest(socket.getInputStream())
                            DownloaderTestUtils.sendLines(
                                socket,
                                "HTTP/1.1 404 Not Found",
                                "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                                "Connection: close",
                                "Content-Type: text/plain",
                                "Content-Length: 5",
                                "",
                                ""
                            )
                        }
                        null
                    })
            Assert.assertThrows<IOException?>(
                IOException::class.java,
                ThrowingRunnable {
                    httpDownloader.downloadAndReadOneUrl(
                        URI.create(String.format("http://localhost:%d/foo", server.getLocalPort())),
                        StaticCredentials.EMPTY,
                        Optional.empty<Checksum?>(),
                        eventHandler,
                        mutableMapOf<String?, String?>()
                    )
                })
        }
    }

    @Test
    @Throws(IOException::class, InvalidChecksumException::class, InterruptedException::class)
    fun downloadAndReadOneUrl_checksumProvided() {
        ServerSocket(0, 1, InetAddress.getByName(null)).use { server ->
            @Suppress("unused") val possiblyIgnoredError: Future<*> =
                executor.submit<Any?>(
                    Callable {
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
                        null
                    })
            Truth.assertThat(
                String(
                    httpDownloader.downloadAndReadOneUrl(
                        URI.create(String.format("http://localhost:%d/foo", server.getLocalPort())),
                        StaticCredentials.EMPTY,
                        Optional.of<T?>(
                            Checksum.fromString(
                                DownloadCache.KeyType.SHA256,
                                Hashing.sha256().hashString("hello", StandardCharsets.UTF_8).toString()
                            )
                        ),
                        eventHandler,
                        ImmutableMap.of<String?, String?>()
                    ),
                    StandardCharsets.UTF_8
                )
            )
                .isEqualTo("hello")
        }
    }

    @Test
    @Throws(IOException::class)
    fun downloadAndReadOneUrl_checksumMismatch() {
        ServerSocket(0, 1, InetAddress.getByName(null)).use { server ->
            @Suppress("unused") val possiblyIgnoredError: Future<*> =
                executor.submit<Any?>(
                    Callable {
                        server.accept().use { socket ->
                            readHttpRequest(socket.getInputStream())
                            DownloaderTestUtils.sendLines(
                                socket,
                                "HTTP/1.1 200 OK",
                                "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                                "Connection: close",
                                "Content-Type: text/plain",
                                "Content-Length: 9",
                                "",
                                "malicious"
                            )
                        }
                        null
                    })
            val e =
                Assert.assertThrows<UnrecoverableHttpException?>(
                    UnrecoverableHttpException::class.java,
                    ThrowingRunnable {
                        httpDownloader.downloadAndReadOneUrl(
                            URI.create(String.format("http://localhost:%d/foo", server.getLocalPort())),
                            StaticCredentials.EMPTY,
                            Optional.of<T?>(
                                Checksum.fromString(
                                    DownloadCache.KeyType.SHA256,
                                    Hashing.sha256().hashUnencodedChars("hello").toString()
                                )
                            ),
                            eventHandler,
                            ImmutableMap.of<String?, String?>()
                        )
                    })
            Truth.assertThat(e).hasMessageThat().contains("Checksum was")
        }
    }

    @Test
    @Throws(Exception::class)
    fun download_contentLengthMismatch_propagateErrorIfNotRetry() {
        val downloader: Downloader
        Downloader > Mockito.mock<Downloader?>(Downloader::class.java)
        val httpDownloader: HttpDownloader
        HttpDownloader > Mockito.mock<HttpDownloader?>(HttpDownloader::class.java)
        val downloadManager =
            DownloadManager(downloadCache, downloader, httpDownloader, eventHandler)
        // do not retry
        downloadManager.setRetries(0)
        val times: AtomicInteger = AtomicInteger(0)
        val data: ByteArray = "content".toByteArray(StandardCharsets.UTF_8)
        Mockito.doAnswer(
            Answer { invocationOnMock: InvocationOnMock? ->
                times.getAndIncrement()
                throw ContentLengthMismatchException(0, data.size.toLong())
            } as Answer<Void?>)
            .`when`<Downloader?>(downloader)
            .download(TODO("Cannot convert element")) < List < URI shr ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        String > ArgumentMatchers.any<Any?>()
        Path > ArgumentMatchers.any<Any?>()
        ExtendedEventHandler > ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        String > ArgumentMatchers.eq<String?>("testRepo")


        ContentLengthMismatchException > Assert.assertThrows<ContentLengthMismatchException?>(
            ContentLengthMismatchException::class.java,
            ThrowingRunnable {
                download(
                    downloadManager,
                    ImmutableList.of<URI?>(URI.create("http://localhost")),
                    mutableMapOf<String?, MutableList<String?>?>(),
                    ImmutableMap.of<URI?, MutableMap<String?, MutableList<String?>?>?>(),
                    Optional.empty<Checksum?>(),
                    "testCanonicalId",
                    Optional.empty<String?>(),
                    fs.getPath(workingDir.newFile().getAbsolutePath()),
                    ImmutableMap.of<String?, String?>(),
                    "testRepo"
                )
            })

        Truth.assertThat(times.get()).isEqualTo(1)
    }

    @Test
    @Throws(Exception::class)
    fun download_contentLengthMismatch_retries() {
        val downloader: Downloader
        Downloader > Mockito.mock<Downloader?>(Downloader::class.java)
        val httpDownloader: HttpDownloader
        HttpDownloader > Mockito.mock<HttpDownloader?>(HttpDownloader::class.java)
        val retries = 5
        val downloadManager =
            DownloadManager(downloadCache, downloader, httpDownloader, eventHandler)
        downloadManager.setRetries(retries)
        val times: AtomicInteger = AtomicInteger(0)
        val data: ByteArray = "content".toByteArray(StandardCharsets.UTF_8)
        Mockito.doAnswer(
            Answer { invocationOnMock: InvocationOnMock? ->
                if (times.getAndIncrement() < 3) {
                    throw ContentLengthMismatchException(0, data.size.toLong())
                }
                val output: Path = invocationOnMock.getArgument<Path>(5, Path::class.java)
                output.getOutputStream().use { outputStream ->
                    ByteStreams.copy(ByteArrayInputStream(data), outputStream)
                }
                null
            } as Answer<Void?>)
            .`when`<Downloader?>(downloader)
            .download(TODO("Cannot convert element")) < List < URI shr ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        String > ArgumentMatchers.any<Any?>()
        Path > ArgumentMatchers.any<Any?>()
        ExtendedEventHandler > ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        String > ArgumentMatchers.eq<String?>("testRepo")


        val result: Path =
            download(
                downloadManager,
                ImmutableList.of<URI?>(URI.create("http://localhost")),
                ImmutableMap.of<String?, MutableList<String?>?>(),
                ImmutableMap.of<URI?, MutableMap<String?, MutableList<String?>?>?>(),
                Optional.empty<Checksum?>(),
                "testCanonicalId",
                Optional.empty<String?>(),
                fs.getPath(workingDir.newFile().getAbsolutePath()),
                ImmutableMap.of<String?, String?>(),
                "testRepo"
            )

        Truth.assertThat(times.get()).isEqualTo(4)
        val content = String(ByteStreams.toByteArray(result.getInputStream()), StandardCharsets.UTF_8)
        Truth.assertThat(content).isEqualTo("content")
    }

    @Test
    @Throws(Exception::class)
    fun download_contentLengthMismatchWithOtherErrors_retries() {
        val downloader: Downloader
        Downloader > Mockito.mock<Downloader?>(Downloader::class.java)
        val httpDownloader: HttpDownloader
        HttpDownloader > Mockito.mock<HttpDownloader?>(HttpDownloader::class.java)
        val retries = 5
        val downloadManager =
            DownloadManager(downloadCache, downloader, httpDownloader, eventHandler)
        downloadManager.setRetries(retries)
        val times: AtomicInteger = AtomicInteger(0)
        val data: ByteArray = "content".toByteArray(StandardCharsets.UTF_8)
        Mockito.doAnswer(
            Answer { invocationOnMock: InvocationOnMock? ->
                if (times.getAndIncrement() < 3) {
                    val e: IOException = IOException()
                    e.addSuppressed(ContentLengthMismatchException(0, data.size.toLong()))
                    e.addSuppressed(IOException())
                    throw e
                }
                val output: Path = invocationOnMock.getArgument<Path>(5, Path::class.java)
                output.getOutputStream().use { outputStream ->
                    ByteStreams.copy(ByteArrayInputStream(data), outputStream)
                }
                null
            } as Answer<Void?>)
            .`when`<Downloader?>(downloader)
            .download(TODO("Cannot convert element")) < List < URI shr ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        String > ArgumentMatchers.any<Any?>()
        Path > ArgumentMatchers.any<Any?>()
        ExtendedEventHandler > ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        String > ArgumentMatchers.eq<String?>("testRepo")


        val result: Path =
            download(
                downloadManager,
                ImmutableList.of<URI?>(URI.create("http://localhost")),
                ImmutableMap.of<String?, MutableList<String?>?>(),
                ImmutableMap.of<URI?, MutableMap<String?, MutableList<String?>?>?>(),
                Optional.empty<Checksum?>(),
                "testCanonicalId",
                Optional.empty<String?>(),
                fs.getPath(workingDir.newFile().getAbsolutePath()),
                ImmutableMap.of<String?, String?>(),
                "testRepo"
            )

        Truth.assertThat(times.get()).isEqualTo(4)
        val content = String(result.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
        Truth.assertThat(content).isEqualTo("content")
    }

    @Test
    @Throws(Exception::class)
    fun download_socketException_retries() {
        val downloader: Downloader
        Downloader > Mockito.mock<Downloader?>(Downloader::class.java)
        val httpDownloader: HttpDownloader
        HttpDownloader > Mockito.mock<HttpDownloader?>(HttpDownloader::class.java)
        val retries = 5
        val downloadManager =
            DownloadManager(downloadCache, downloader, httpDownloader, eventHandler)
        downloadManager.setRetries(retries)
        val times: AtomicInteger = AtomicInteger(0)
        val data: ByteArray = "content".toByteArray(StandardCharsets.UTF_8)
        Mockito.doAnswer(
            Answer { invocationOnMock: InvocationOnMock? ->
                if (times.getAndIncrement() < 3) {
                    throw SocketException("Connection reset")
                }
                val output: Path = invocationOnMock.getArgument<Path>(5, Path::class.java)
                output.getOutputStream().use { outputStream ->
                    ByteStreams.copy(ByteArrayInputStream(data), outputStream)
                }
                null
            } as Answer<Void?>)
            .`when`<Downloader?>(downloader)
            .download(TODO("Cannot convert element")) < List < URI shr ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        String > ArgumentMatchers.any<Any?>()
        Path > ArgumentMatchers.any<Any?>()
        ExtendedEventHandler > ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        String > ArgumentMatchers.eq<String?>("testRepo")


        val result: Path =
            download(
                downloadManager,
                ImmutableList.of<URI?>(URI.create("http://localhost")),
                ImmutableMap.of<String?, MutableList<String?>?>(),
                ImmutableMap.of<URI?, MutableMap<String?, MutableList<String?>?>?>(),
                Optional.empty<Checksum?>(),
                "testCanonicalId",
                Optional.empty<String?>(),
                fs.getPath(workingDir.newFile().getAbsolutePath()),
                ImmutableMap.of<String?, String?>(),
                "testRepo"
            )

        Truth.assertThat(times.get()).isEqualTo(4)
        val content = String(ByteStreams.toByteArray(result.getInputStream()), StandardCharsets.UTF_8)
        Truth.assertThat(content).isEqualTo("content")
    }

    @Test
    @Throws(Exception::class)
    fun download_socketExceptionWithOtherErrors_retries() {
        val downloader: Downloader
        Downloader > Mockito.mock<Downloader?>(Downloader::class.java)
        val httpDownloader: HttpDownloader
        HttpDownloader > Mockito.mock<HttpDownloader?>(HttpDownloader::class.java)
        val retries = 5
        val downloadManager =
            DownloadManager(downloadCache, downloader, httpDownloader, eventHandler)
        downloadManager.setRetries(retries)
        val times: AtomicInteger = AtomicInteger(0)
        val data: ByteArray = "content".toByteArray(StandardCharsets.UTF_8)
        Mockito.doAnswer(
            Answer { invocationOnMock: InvocationOnMock? ->
                if (times.getAndIncrement() < 3) {
                    val e: IOException = IOException()
                    e.addSuppressed(SocketException("Connection reset"))
                    e.addSuppressed(IOException())
                    throw e
                }
                val output: Path = invocationOnMock.getArgument<Path>(5, Path::class.java)
                output.getOutputStream().use { outputStream ->
                    ByteStreams.copy(ByteArrayInputStream(data), outputStream)
                }
                null
            } as Answer<Void?>)
            .`when`<Downloader?>(downloader)
            .download(TODO("Cannot convert element")) < List < URI shr ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        String > ArgumentMatchers.any<Any?>()
        Path > ArgumentMatchers.any<Any?>()
        ExtendedEventHandler > ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        String > ArgumentMatchers.eq<String?>("testRepo")


        val result: Path =
            download(
                downloadManager,
                ImmutableList.of<URI?>(URI.create("http://localhost")),
                ImmutableMap.of<String?, MutableList<String?>?>(),
                ImmutableMap.of<URI?, MutableMap<String?, MutableList<String?>?>?>(),
                Optional.empty<Checksum?>(),
                "testCanonicalId",
                Optional.empty<String?>(),
                fs.getPath(workingDir.newFile().getAbsolutePath()),
                ImmutableMap.of<String?, String?>(),
                "testRepo"
            )

        Truth.assertThat(times.get()).isEqualTo(4)
        val content = String(ByteStreams.toByteArray(result.getInputStream()), StandardCharsets.UTF_8)
        Truth.assertThat(content).isEqualTo("content")
    }

    @Throws(IOException::class, InterruptedException::class)
    fun download(
        downloadManager: DownloadManager,
        originalUrls: MutableList<URI?>,
        headers: MutableMap<String?, MutableList<String?>?>?,
        authHeaders: MutableMap<URI?, MutableMap<String?, MutableList<String?>?>?>?,
        checksum: Optional<Checksum?>,
        canonicalId: String?,
        type: Optional<String?>,
        output: Path,
        clientEnv: MutableMap<String?, String?>?,
        context: String?
    ): Path {
        val downloadPhaser: Phaser = Phaser()
        Executors.newVirtualThreadPerTaskExecutor().use { executorService ->
            val future: Future<Path?>? =
                downloadManager.startDownload(
                    executorService,
                    originalUrls,
                    headers,
                    authHeaders,
                    checksum,
                    canonicalId,
                    type,
                    output,
                    clientEnv,
                    context,
                    downloadPhaser,  /* mayHardlink= */
                    true
                )
            val downloadedPath: Path = downloadManager.finalizeDownload(future)
            // Should not be in the download phase.
            Truth.assertThat(downloadPhaser.getPhase()).isNotEqualTo(0)
            return downloadedPath
        }
    }
}
