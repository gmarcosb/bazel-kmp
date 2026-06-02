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

import com.google.common.collect.ImmutableMap
import com.google.common.hash.Hashing
import com.google.common.io.ByteStreams
import com.google.devtools.build.lib.bazel.repository.cache.DownloadCache.KeyType
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.function.ThrowingRunnable
import org.junit.rules.ExpectedException
import org.junit.rules.Timeout
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.nio.charset.StandardCharsets
import java.util.Optional

/** Integration tests for [HttpStream.Factory] and friends.  */
@RunWith(JUnit4::class)
class HttpStreamTest {
    @Rule
    val thrown: ExpectedException = ExpectedException.none()

    @Rule
    val globalTimeout: Timeout = Timeout.seconds(10)

    private val connection: HttpURLConnection = Mockito.mock<HttpURLConnection>(HttpURLConnection::class.java)
    private val reconnector: Reconnector = Mockito.mock<Reconnector>(Reconnector::class.java)
    private val progress: ProgressInputStream.Factory =
        Mockito.mock<ProgressInputStream.Factory>(ProgressInputStream.Factory::class.java)
    private val streamFactory = HttpStream.Factory(progress)

    private var nRetries = 0

    @Before
    @Throws(Exception::class)
    fun before() {
        nRetries = 0

        Mockito.`when`<InputStream?>(connection.getInputStream()).thenReturn(ByteArrayInputStream(data))
        Mockito.`when`<URL?>(connection.getURL()).thenReturn(AURL.toURL())
        Mockito.`when`<InputStream>(
            progress.create(
                ArgumentMatchers.any<InputStream?>(InputStream::class.java),
                ArgumentMatchers.any<URI>(),
                ArgumentMatchers.any<URI?>(
                    URI::class.java
                ),
                ArgumentMatchers.any<OptionalLong?>()
            )
        )
            .thenAnswer(
                object : Answer<InputStream?> {
                    @Throws(Throwable::class)
                    override fun answer(invocation: InvocationOnMock): InputStream? {
                        return invocation.getArguments()[0] as InputStream?
                    }
                })
    }

    @Test
    @Throws(Exception::class)
    fun noChecksum_readsOk() {
        streamFactory.create(connection, AURL, Optional.empty<Checksum?>(), reconnector).use { stream ->
            Truth.assertThat(
                ByteStreams.toByteArray(stream)
            ).isEqualTo(data)
        }
    }

    @Test
    @Throws(Exception::class)
    fun smallDataWithValidChecksum_readsOk() {
        streamFactory.create(connection, AURL, GOOD_CHECKSUM, reconnector).use { stream ->
            Truth.assertThat(ByteStreams.toByteArray(stream)).isEqualTo(
                data
            )
        }
    }

    @Test
    @Throws(Exception::class)
    fun smallDataWithValidChecksum_timesOutInCreateRetriesOk() {
        val inputStream: InputStream? = Mockito.mock<ByteArrayInputStream?>(ByteArrayInputStream::class.java)
        val realInputStream: InputStream = ByteArrayInputStream(data)

        Mockito.doAnswer(
            Answer { invocation: InvocationOnMock? ->
                val args: Array<Any?> = invocation.getArguments()
                if (nRetries++ == 0) {
                    throw SocketTimeoutException()
                } else {
                    return@Answer realInputStream.read(args[0] as ByteArray?, args[1] as Int, args[2] as Int)
                }
            } as Answer<Int?>)
            .`when`<InputStream?>(inputStream)
            .read(ArgumentMatchers.any<ByteArray?>(), ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt())
        Mockito.`when`<URLConnection>(
            reconnector.connect(
                ArgumentMatchers.any<Throwable?>(),
                ArgumentMatchers.any<ImmutableMap<String?, MutableList<String?>?>?>()
            )
        ).thenReturn(connection)
        Mockito.`when`<InputStream?>(connection.getInputStream()).thenReturn(inputStream)
        Mockito.`when`<String?>(connection.getHeaderField("Accept-Ranges")).thenReturn("bytes")
        streamFactory.create(connection, AURL, GOOD_CHECKSUM, reconnector).use { stream ->
            Truth.assertThat(ByteStreams.toByteArray(stream)).isEqualTo(
                data
            )
        }
    }

    @Test
    @Throws(Exception::class)
    fun smallDataWithValidChecksum_timesOutInCreateRepeatedly() {
        val inputStream: InputStream? = Mockito.mock<ByteArrayInputStream?>(ByteArrayInputStream::class.java)

        Mockito.doAnswer(
            Answer { invocation: InvocationOnMock? ->
                ++nRetries
                throw SocketTimeoutException()
            } as Answer<Int?>)
            .`when`<InputStream?>(inputStream)
            .read(ArgumentMatchers.any<ByteArray?>(), ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt())
        Mockito.`when`<URLConnection>(
            reconnector.connect(
                ArgumentMatchers.any<Throwable?>(),
                ArgumentMatchers.any<ImmutableMap<String?, MutableList<String?>?>?>()
            )
        ).thenReturn(connection)
        Mockito.`when`<InputStream?>(connection.getInputStream()).thenReturn(inputStream)
        Mockito.`when`<String?>(connection.getHeaderField("Accept-Ranges")).thenReturn("bytes")
        thrown.expect(SocketTimeoutException::class.java)

        try {
            val unused = streamFactory.create(connection, AURL, GOOD_CHECKSUM, reconnector)
        } catch (e: Exception) {
            Truth.assertThat(nRetries).isGreaterThan(3) // RetryingInputStream.MAX_RESUMES
            throw e
        }
    }

    @Test
    @Throws(Exception::class)
    fun smallDataWithInvalidChecksum_throwsIOExceptionInCreatePhase() {
        thrown.expect(IOException::class.java)
        thrown.expectMessage("Checksum")
        streamFactory.create(connection, AURL, BAD_CHECKSUM, reconnector)
    }

    @Test
    @Throws(Exception::class)
    fun bigDataWithValidChecksum_readsOk() {
        // at google, we know big data
        val bigData = ByteArray(HttpStream.PRECHECK_BYTES + 70001)
        randoCalrissian.nextBytes(bigData)
        Mockito.`when`<InputStream?>(connection.getInputStream()).thenReturn(ByteArrayInputStream(bigData))
        streamFactory.create(
            connection,
            AURL,
            makeChecksum(Hashing.sha256().hashBytes(bigData).toString()),
            reconnector
        ).use { stream ->
            Truth.assertThat(ByteStreams.toByteArray(stream)).isEqualTo(bigData)
        }
    }

    @Test
    @Throws(Exception::class)
    fun bigDataWithInvalidChecksum_throwsIOExceptionAfterCreateOnEof() {
        // the probability of this test flaking is 8.6361686e-78
        val bigData = ByteArray(HttpStream.PRECHECK_BYTES + 70001)
        randoCalrissian.nextBytes(bigData)
        Mockito.`when`<InputStream?>(connection.getInputStream()).thenReturn(ByteArrayInputStream(bigData))
        streamFactory.create(connection, AURL, BAD_CHECKSUM, reconnector).use { stream ->
            thrown.expect(IOException::class.java)
            thrown.expectMessage("Checksum")
            ByteStreams.exhaust(stream)
            Assert.fail("Should have thrown error before close()")
        }
    }

    @Test
    @Throws(Exception::class)
    fun bigDataTruncated_throwsExpectedError() {
        val bigData = ByteArray(HttpStream.PRECHECK_BYTES + 70001)
        randoCalrissian.nextBytes(bigData)
        Mockito.`when`<String?>(connection.getHeaderField("Content-Length"))
            .thenReturn((bigData.size + 1).toString())
        Mockito.`when`<InputStream?>(connection.getInputStream()).thenReturn(ByteArrayInputStream(bigData))

        val thrown =
            Assert.assertThrows<ContentLengthMismatchException>(
                ContentLengthMismatchException::class.java,
                ThrowingRunnable {
                    streamFactory.create(
                        connection,
                        AURL,
                        makeChecksum(Hashing.sha256().hashBytes(bigData).toString()),
                        reconnector
                    ).use { stream ->
                        ByteStreams.exhaust(stream)
                    }
                })

        Truth.assertThat(thrown.actualSize).isEqualTo(bigData.size)
        Truth.assertThat(thrown.expectedSize).isEqualTo(bigData.size + 1)
    }

    @Test
    @Throws(Exception::class)
    fun bigDataOverflowed_throwsExpectedError() {
        val bigData = ByteArray(HttpStream.PRECHECK_BYTES + 70001)
        randoCalrissian.nextBytes(bigData)
        Mockito.`when`<String?>(connection.getHeaderField("Content-Length"))
            .thenReturn((bigData.size - 1).toString())
        Mockito.`when`<InputStream?>(connection.getInputStream()).thenReturn(ByteArrayInputStream(bigData))

        val thrown =
            Assert.assertThrows<ContentLengthMismatchException>(
                ContentLengthMismatchException::class.java,
                ThrowingRunnable {
                    streamFactory.create(
                        connection,
                        AURL,
                        makeChecksum(Hashing.sha256().hashBytes(bigData).toString()),
                        reconnector
                    ).use { stream ->
                        ByteStreams.exhaust(stream)
                    }
                })

        Truth.assertThat(thrown.actualSize).isEqualTo(bigData.size)
        Truth.assertThat(thrown.expectedSize).isEqualTo(bigData.size - 1)
    }

    @Test
    @Throws(Exception::class)
    fun httpServerSaidGzippedButNotGzipped_throwsZipExceptionInCreate() {
        Mockito.`when`<URL?>(connection.getURL()).thenReturn(AURL.toURL())
        Mockito.`when`<String?>(connection.getContentEncoding()).thenReturn("gzip")
        thrown.expect(ZipException::class.java)
        streamFactory.create(connection, AURL, Optional.empty<Checksum?>(), reconnector)
    }

    @Test
    @Throws(Exception::class)
    fun javascriptGzippedInTransit_automaticallyGunzips() {
        Mockito.`when`<URL?>(connection.getURL()).thenReturn(AURL.toURL())
        Mockito.`when`<String?>(connection.getContentEncoding()).thenReturn("x-gzip")
        Mockito.`when`<InputStream?>(connection.getInputStream()).thenReturn(ByteArrayInputStream(gzipData(data)))
        streamFactory.create(connection, AURL, Optional.empty<Checksum?>(), reconnector).use { stream ->
            Truth.assertThat(
                ByteStreams.toByteArray(stream)
            ).isEqualTo(data)
        }
    }

    @Test
    @Throws(Exception::class)
    fun serverSaysTarballPathIsGzipped_doesntAutomaticallyGunzip() {
        val gzData: ByteArray = gzipData(data)
        Mockito.`when`<URL?>(connection.getURL()).thenReturn(URI.create("http://doodle.example/foo.tar.gz").toURL())
        Mockito.`when`<String?>(connection.getContentEncoding()).thenReturn("gzip")
        Mockito.`when`<InputStream?>(connection.getInputStream()).thenReturn(ByteArrayInputStream(gzData))
        streamFactory.create(connection, AURL, Optional.empty<Checksum?>(), reconnector).use { stream ->
            Truth.assertThat(
                ByteStreams.toByteArray(stream)
            ).isEqualTo(gzData)
        }
    }

    @Test
    @Throws(Exception::class)
    fun threadInterrupted_haltsReadingAndThrowsInterrupt() {
        val wasInterrupted: AtomicBoolean = AtomicBoolean()
        val thread =
            Thread(
                object : Runnable {
                    override fun run() {
                        try {
                            streamFactory.create(connection, AURL, Optional.empty<Checksum?>(), reconnector)
                                .use { stream ->
                                    stream.read()
                                    Thread.currentThread().interrupt()
                                    stream.read()
                                    Assert.fail()
                                }
                        } catch (expected: InterruptedIOException) {
                            wasInterrupted.set(true)
                        } catch (ignored: IOException) {
                            // ignored
                        }
                    }
                })
        thread.start()
        thread.join()
        Truth.assertThat(wasInterrupted.get()).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun tarballHasNoFormatAndTypeIsGzipped_doesntAutomaticallyGunzip() {
        val gzData: ByteArray = gzipData(data)
        Mockito.`when`<URL?>(connection.getURL()).thenReturn(URI.create("http://doodle.example/foo").toURL())
        Mockito.`when`<String?>(connection.getContentEncoding()).thenReturn("gzip")
        Mockito.`when`<InputStream?>(connection.getInputStream()).thenReturn(ByteArrayInputStream(gzData))
        streamFactory.create(connection, AURL, Optional.empty<Checksum?>(), reconnector, Optional.of<String>("tgz"))
            .use { stream ->
                Truth.assertThat(
                    ByteStreams.toByteArray(stream)
                ).isEqualTo(gzData)
            }
    }

    @Test
    @Throws(Exception::class)
    fun tarballHasNoFormatAndTypeIsGzippedAndHasMultipleExtensions_doesntAutomaticallyGunzip() {
        // Similar to tarballHasNoFormatAndTypeIsGzipped_doesntAutomaticallyGunzip but also
        // checks if the private method typeIsGZIP can handle separation of file extensions.
        val gzData: ByteArray = gzipData(data)
        Mockito.`when`<URL?>(connection.getURL()).thenReturn(URI.create("http://doodle.example/foo").toURL())
        Mockito.`when`<String?>(connection.getContentEncoding()).thenReturn("gzip")
        Mockito.`when`<InputStream?>(connection.getInputStream()).thenReturn(ByteArrayInputStream(gzData))
        streamFactory.create(
            connection, AURL, Optional.empty<Checksum?>(), reconnector, Optional.of<String>("tar.gz")
        ).use { stream ->
            Truth.assertThat(
                ByteStreams.toByteArray(stream)
            ).isEqualTo(gzData)
        }
    }

    @Test
    @Throws(Exception::class)
    fun tarballHasNoFormatAndTypeIsNotGzipped_automaticallyGunzip() {
        Mockito.`when`<URL?>(connection.getURL()).thenReturn(URI.create("http://doodle.example/foo").toURL())
        Mockito.`when`<String?>(connection.getContentEncoding()).thenReturn("gzip")
        Mockito.`when`<InputStream?>(connection.getInputStream()).thenReturn(ByteArrayInputStream(gzipData(data)))
        streamFactory.create(connection, AURL, Optional.empty<Checksum?>(), reconnector, Optional.of<String>("tar"))
            .use { stream ->
                Truth.assertThat(
                    ByteStreams.toByteArray(stream)
                ).isEqualTo(data)
            }
    }

    companion object {
        private val randoCalrissian: Random = Random()
        private val data: ByteArray = "hello".toByteArray(StandardCharsets.UTF_8)

        private fun makeChecksum(string: String?): Optional<Checksum?> {
            try {
                return Optional.of<T?>(Checksum.fromString(KeyType.SHA256, string!!))
            } catch (e: InvalidChecksumException) {
                throw IllegalStateException(e)
            }
        }

        private val GOOD_CHECKSUM: Optional<Checksum?> =
            makeChecksum("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")
        private val BAD_CHECKSUM: Optional<Checksum?> =
            makeChecksum("0000000000000000000000000000000000000000000000000000000000000000")
        private val AURL: URI = URI.create("http://doodle.example")

        @Throws(IOException::class)
        private fun gzipData(bytes: ByteArray): ByteArray {
            val baos = ByteArrayOutputStream()
            ByteArrayInputStream(bytes).use { input ->
                GZIPOutputStream(baos).use { output ->
                    ByteStreams.copy(input, output)
                }
            }
            return baos.toByteArray()
        }
    }
}
