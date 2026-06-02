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
import com.google.devtools.build.lib.authandtls.StaticCredentials
import com.google.devtools.build.lib.events.EventHandler
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.function.ThrowingRunnable
import org.junit.rules.Timeout
import java.net.URI
import java.net.URLConnection
import java.nio.charset.StandardCharsets
import java.util.*

/** Unit tests for [HttpConnectorMultiplexer].  */
@RunWith(JUnit4::class)
class HttpConnectorMultiplexerTest {
    @Rule
    val globalTimeout: Timeout = Timeout.seconds(10)

    private val stream = HttpStream(ByteArrayInputStream(TEST_DATA), TEST_URL)
    private val connector: HttpConnector = Mockito.mock<HttpConnector>(HttpConnector::class.java)
    private val connection: URLConnection? = Mockito.mock<URLConnection?>(URLConnection::class.java)
    private val eventHandler: EventHandler = Mockito.mock<EventHandler>(EventHandler::class.java)
    private val streamFactory: HttpStream.Factory = Mockito.mock<HttpStream.Factory>(HttpStream.Factory::class.java)
    private val multiplexer = HttpConnectorMultiplexer(eventHandler, connector, streamFactory)

    @Before
    @Throws(Exception::class)
    fun before() {
        Mockito.`when`<URLConnection?>(
            connector.connect(
                ArgumentMatchers.eq<URI?>(TEST_URL), ArgumentMatchers.any<Function<*, *>?>(
                    Function::class.java
                )
            )
        ).thenReturn(connection)
        Mockito.`when`<HttpStream>(
            streamFactory.create(
                ArgumentMatchers.same<URLConnection?>(connection),
                ArgumentMatchers.any<URI?>(URI::class.java),
                ArgumentMatchers.any<Optional<*>?>(Optional::class.java),
                ArgumentMatchers.any<Reconnector?>(Reconnector::class.java),
                ArgumentMatchers.any<Optional<*>?>(Optional::class.java)
            )
        )
            .thenReturn(stream)
    }

    @Test
    @Throws(Exception::class)
    fun ftpUrl_throwsIae() {
        Assert.assertThrows<IllegalArgumentException?>(
            IllegalArgumentException::class.java,
            ThrowingRunnable { multiplexer.connect(URI.create("ftp://lol.example"), Optional.empty<Checksum?>()) })
    }

    @Test
    @Throws(Exception::class)
    fun threadIsInterrupted_throwsIeProntoAndDoesNothingElse() {
        val wasInterrupted: AtomicBoolean = AtomicBoolean(true)
        val task =
            Thread(
                object : Runnable {
                    override fun run() {
                        Thread.currentThread().interrupt()
                        try {
                            val unused =
                                multiplexer.connect(URI.create("http://lol.example"), Optional.empty<Checksum?>())
                        } catch (ignored: InterruptedIOException) {
                            return
                        } catch (ignored: Exception) {
                            // ignored
                        }
                        wasInterrupted.set(false)
                    }
                })
        task.start()
        task.join()
        Truth.assertThat(wasInterrupted.get()).isTrue()
        Mockito.verifyNoInteractions(connector)
    }

    @Test
    @Throws(Exception::class)
    fun success() {
        Truth.assertThat(ByteStreams.toByteArray(multiplexer.connect(TEST_URL, DUMMY_CHECKSUM))).isEqualTo(TEST_DATA)
        Mockito.verify<HttpConnector?>(connector).connect(
            ArgumentMatchers.eq<URI?>(TEST_URL), ArgumentMatchers.any<Function<*, *>?>(
                Function::class.java
            )
        )
        Mockito.verify<HttpStream.Factory?>(streamFactory)
            .create(
                ArgumentMatchers.any<URLConnection?>(URLConnection::class.java),
                ArgumentMatchers.any<URI?>(URI::class.java),
                ArgumentMatchers.eq<Optional<Checksum?>?>(DUMMY_CHECKSUM),
                ArgumentMatchers.any<Reconnector?>(Reconnector::class.java),
                ArgumentMatchers.any<Optional<*>?>(Optional::class.java)
            )
        Mockito.verifyNoMoreInteractions(connector, streamFactory)
    }

    @Test
    @Throws(Exception::class)
    fun failure() {
        Mockito.`when`<URLConnection?>(
            connector.connect(
                ArgumentMatchers.any<URI?>(URI::class.java), ArgumentMatchers.any<Function<*, *>?>(
                    Function::class.java
                )
            )
        ).thenThrow(IOException("oops"))
        val e: IOException? =
            Assert.assertThrows<IOException?>(
                IOException::class.java,
                ThrowingRunnable { multiplexer.connect(TEST_URL, Optional.empty<Checksum?>()) })
        Truth.assertThat(e).hasMessageThat().contains("oops")
        Mockito.verify<HttpConnector?>(connector).connect(
            ArgumentMatchers.any<URI?>(URI::class.java), ArgumentMatchers.any<Function<*, *>?>(
                Function::class.java
            )
        )
        Mockito.verifyNoMoreInteractions(connector, streamFactory)
    }

    @Test
    @Throws(Exception::class)
    fun testHeaderComputationFunction() {
        val baseHeaders =
            ImmutableMap.of<String?, MutableList<String?>?>(
                "Accept-Encoding",
                ImmutableList.of<String?>("gzip"),
                "User-Agent",
                ImmutableList.of<String?>("Bazel/testing")
            )
        val additionalHeaders =
            ImmutableMap.of<URI?, MutableMap<String?, MutableList<String?>?>?>(
                URI("http://hosting.example.com/user/foo/file.txt"),
                ImmutableMap.of<String?, MutableList<String?>?>(
                    "Authentication",
                    ImmutableList.of<String?>("Zm9vOmZvb3NlY3JldA==")
                )
            )

        val headerFunction =
            HttpConnectorMultiplexer.getHeaderFunction(
                baseHeaders, StaticCredentials(additionalHeaders), eventHandler
            )

        // Unrelated URL
        Truth.assertThat(headerFunction.apply(URI.create("http://example.org/some/path/file.txt")))
            .containsExactly(
                "Accept-Encoding",
                ImmutableList.of<String?>("gzip"),
                "User-Agent",
                ImmutableList.of<String?>("Bazel/testing")
            )

        // With auth headers
        Truth.assertThat(headerFunction.apply(URI.create("http://hosting.example.com/user/foo/file.txt")))
            .containsExactly(
                "Accept-Encoding",
                ImmutableList.of<String?>("gzip"),
                "User-Agent",
                ImmutableList.of<String?>("Bazel/testing"),
                "Authentication",
                ImmutableList.of<String?>("Zm9vOmZvb3NlY3JldA==")
            )

        // Other hosts
        Truth.assertThat(headerFunction.apply(URI.create("http://hosting2.example.com/user/foo/file.txt")))
            .containsExactly(
                "Accept-Encoding",
                ImmutableList.of<String?>("gzip"),
                "User-Agent",
                ImmutableList.of<String?>("Bazel/testing")
            )
        Truth.assertThat(headerFunction.apply(URI.create("http://sub.hosting.example.com/user/foo/file.txt")))
            .containsExactly(
                "Accept-Encoding",
                ImmutableList.of<String?>("gzip"),
                "User-Agent",
                ImmutableList.of<String?>("Bazel/testing")
            )
        Truth.assertThat(headerFunction.apply(URI.create("http://example.com/user/foo/file.txt")))
            .containsExactly(
                "Accept-Encoding",
                ImmutableList.of<String?>("gzip"),
                "User-Agent",
                ImmutableList.of<String?>("Bazel/testing")
            )
        Truth.assertThat(
            headerFunction.apply(
                URI.create("http://hosting.example.com.evil.example/user/foo/file.txt")
            )
        )
            .containsExactly(
                "Accept-Encoding",
                ImmutableList.of<String?>("gzip"),
                "User-Agent",
                ImmutableList.of<String?>("Bazel/testing")
            )

        // Verify that URL-specific headers overwrite
        val annonAuth =
            ImmutableMap.of<String?, MutableList<String?>?>(
                "Authentication",
                ImmutableList.of<String?>("YW5vbnltb3VzOmZvb0BleGFtcGxlLm9yZw==")
            )
        val combinedHeaders =
            HttpConnectorMultiplexer.getHeaderFunction(
                annonAuth, StaticCredentials(additionalHeaders), eventHandler
            )
        Truth.assertThat(combinedHeaders.apply(URI.create("http://hosting.example.com/user/foo/file.txt")))
            .containsExactly("Authentication", ImmutableList.of<String?>("Zm9vOmZvb3NlY3JldA=="))
        Truth.assertThat(combinedHeaders.apply(URI.create("http://unreleated.example.org/user/foo/file.txt")))
            .containsExactly(
                "Authentication", ImmutableList.of<String?>("YW5vbnltb3VzOmZvb0BleGFtcGxlLm9yZw==")
            )
    }

    companion object {
        private val TEST_URL: URI = URI.create("http://test.example")
        private val TEST_DATA: ByteArray = "test_data".toByteArray(StandardCharsets.UTF_8)

        private fun makeChecksum(string: String?): Optional<Checksum?> {
            try {
                return Optional.of<T?>(Checksum.fromString(KeyType.SHA256, string!!))
            } catch (e: InvalidChecksumException) {
                throw IllegalStateException(e)
            }
        }

        private val DUMMY_CHECKSUM: Optional<Checksum?> =
            makeChecksum("abcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcd")
    }
}
