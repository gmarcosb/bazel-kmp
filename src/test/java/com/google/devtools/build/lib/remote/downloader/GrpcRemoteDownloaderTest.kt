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
package com.google.devtools.build.lib.remote.downloader

import com.google.auth.Credentials
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.io.ByteStreams
import com.google.common.util.concurrent.ListeningScheduledExecutorService
import com.google.common.util.concurrent.MoreExecutors
import com.google.devtools.build.lib.bazel.repository.downloader.Checksum
import com.google.devtools.build.lib.clock.BlazeClock
import com.google.devtools.build.lib.remote.util.Utils.getFromFuture
import com.google.devtools.build.lib.testutil.ManualClock
import com.google.devtools.build.lib.testutil.TestUtils
import com.google.devtools.common.options.Options
import io.grpc.Server
import org.junit.After
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.*
import java.util.function.Supplier
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.MutableMap

/** Tests for [GrpcRemoteDownloader].  */
@RunWith(JUnit4::class)
class GrpcRemoteDownloaderTest {
    private val serviceRegistry: MutableHandlerRegistry = MutableHandlerRegistry()
    private val fakeServerName = "fake server for " + javaClass
    private val eventHandler: StoredEventHandler = StoredEventHandler()
    private val remoteOptions: RemoteOptions = Options.getDefaults<O>(RemoteOptions::class.java)
    private var fakeServer: Server? = null
    private var context: RemoteActionExecutionContext? = null
    private var retryService: ListeningScheduledExecutorService? = null

    @Before
    @Throws(Exception::class)
    fun setUp() {
        // Use a mutable service registry for later registering the service impl for each test case.
        fakeServer =
            InProcessServerBuilder.forName(fakeServerName)
                .fallbackHandlerRegistry(serviceRegistry)
                .directExecutor()
                .build()
                .start()
        val metadata: RequestMetadata? =
            TracingMetadataUtils.buildMetadata(
                "none",
                "none",
                DIGEST_UTIL.asActionKey(Digest.getDefaultInstance()).digest().getHash(),
                null
            )
        context = RemoteActionExecutionContext.create(metadata)

        retryService = MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1))

        BlazeClock.setClock(clock)
    }

    @After
    @Throws(Exception::class)
    fun tearDown() {
        retryService!!.shutdownNow()
        retryService!!.awaitTermination(
            TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
        )

        fakeServer!!.shutdownNow()
        fakeServer!!.awaitTermination()
    }

    @Throws(IOException::class)
    private fun newDownloader(cacheClient: RemoteCacheClient): GrpcRemoteDownloader {
        return newDownloader(cacheClient, Mockito.mock<Downloader?>(Downloader::class.java) /* allowFallback= */)
    }

    @Throws(IOException::class)
    private fun newDownloader(
        cacheClient: RemoteCacheClient, httpDownloader: Downloader
    ): GrpcRemoteDownloader {
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                Supplier { ExponentialBackoff(remoteOptions) },
                RemoteRetrier.EXPERIMENTAL_GRPC_RESULT_CLASSIFIER,
                retryService
            )
        val channel: ReferenceCountedChannel =
            ReferenceCountedChannel(
                object : ChannelConnectionWithServerCapabilitiesFactory() {
                    public override fun create(): Single<ChannelConnectionWithServerCapabilities?>? {
                        val ch: ManagedChannel? =
                            InProcessChannelBuilder.forName(fakeServerName).directExecutor().build()
                        return Single.just<ChannelConnectionWithServerCapabilities?>(
                            ChannelConnectionWithServerCapabilities(
                                ch, Single.just<T?>(ServerCapabilities.getDefaultInstance())
                            )
                        )
                    }

                    public override fun maxConcurrency(): Int {
                        return 100
                    }
                })
        return GrpcRemoteDownloader(
            "none",
            "none",
            channel.retain(),
            Optional.empty<CallCredentials?>(),
            retrier,
            cacheClient,
            DIGEST_UTIL.getDigestFunction(),
            remoteOptions,  /* verboseFailures= */
            false,
            httpDownloader,
            remoteOptions.remoteDownloaderLocalFallback
        )
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun downloadBlob(downloader: GrpcRemoteDownloader, url: URI, checksum: Optional<Checksum?>): ByteArray {
        val urls = ImmutableList.of<URI?>(url)

        val canonicalId = ""
        val clientEnv: MutableMap<String?, String?> = ImmutableMap.of<String?, String?>()

        val scratch: Scratch = Scratch()
        val destination: Path = scratch.resolve("output file path")
        downloader.download(
            urls,
            ImmutableMap.of<String?, MutableList<String?>?>(),
            StaticCredentials.EMPTY,
            checksum,
            canonicalId,
            destination,
            eventHandler,
            clientEnv,
            Optional.empty<String?>(),
            "context"
        )

        destination.getInputStream().use { `in` ->
            return ByteStreams.toByteArray(`in`)
        }
    }

    @Test
    @Throws(Exception::class)
    fun testDownload() {
        val content: ByteArray = "example content".toByteArray(StandardCharsets.UTF_8)
        val contentDigest: Digest? = DIGEST_UTIL.compute(content)

        serviceRegistry.addService(
            object : FetchImplBase() {
                public override fun fetchBlob(
                    request: FetchBlobRequest?, responseObserver: StreamObserver<FetchBlobResponse?>
                ) {
                    assertThat(request)
                        .isEqualTo(
                            FetchBlobRequest.newBuilder()
                                .setDigestFunction(DIGEST_UTIL.getDigestFunction())
                                .setOldestContentAccepted(
                                    Timestamps.fromMillis(clock.advance(Duration.ofHours(1)))
                                )
                                .addUris("http://example.com/content.txt")
                                .build()
                        )
                    responseObserver.onNext(
                        FetchBlobResponse.newBuilder().setBlobDigest(contentDigest).build()
                    )
                    responseObserver.onCompleted()
                }
            })

        val cacheClient: RemoteCacheClient = InMemoryCacheClient()
        val downloader = newDownloader(cacheClient)

        getFromFuture(
            cacheClient.uploadBlob(
                context, contentDigest, ByteString.copyFrom(content),  /* force= */false
            )
        )
        val downloaded =
            downloadBlob(
                downloader, URI.create("http://example.com/content.txt"), Optional.empty<Checksum?>()
            )

        Truth.assertThat(downloaded).isEqualTo(content)
        Truth.assertThat(eventHandler.getPosts())
            .contains(
                FetchEvent(
                    "http://example.com/content.txt", FetchId.Downloader.GRPC,  /* success= */true
                )
            )
    }

    @Test
    @Throws(Exception::class)
    fun testDownloadFallback() {
        remoteOptions.remoteDownloaderLocalFallback = true
        val content: ByteArray = "example content".toByteArray(StandardCharsets.UTF_8)
        serviceRegistry.addService(
            object : FetchImplBase() {
                public override fun fetchBlob(
                    request: FetchBlobRequest?, responseObserver: StreamObserver<FetchBlobResponse?>
                ) {
                    responseObserver.onError(IOException("io error"))
                }
            })
        val cacheClient: RemoteCacheClient = InMemoryCacheClient()
        val fallbackDownloader: Downloader
        Downloader > Mockito.mock<Downloader?>(Downloader::class.java)
        Mockito.doAnswer(
            Answer { invocation: InvocationOnMock? ->
                val urls: MutableList<URI?> = invocation.getArgument<MutableList<URI?>>(0)
                if (urls == ImmutableList.of<URI?>(URI.create("http://example.com/content.txt"))) {
                    val output: Path? = invocation.getArgument<Path?>(5)
                    FileSystemUtils.writeContent(output, content)
                }
                null
            })
            .`when`<Downloader?>(fallbackDownloader)
            .download(TODO("Cannot convert element")) < List < URI shr ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        Credentials > ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        String > ArgumentMatchers.any<Any?>()
        Path > ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        String > ArgumentMatchers.eq<String?>("context")

        val downloader = newDownloader(cacheClient, fallbackDownloader)

        val downloaded =
            downloadBlob(
                downloader, URI.create("http://example.com/content.txt"), Optional.empty<Checksum?>()
            )

        Truth.assertThat(downloaded).isEqualTo(content)
        Truth.assertThat(eventHandler.getPosts())
            .containsExactly(
                FetchEvent(
                    "http://example.com/content.txt", FetchId.Downloader.GRPC,  /* success= */false
                )
            )
    }

    @Test
    @Throws(Exception::class)
    fun testFileUrl() {
        val fileUrl = URI.create("file:///my/local/file")
        val content: ByteArray = "example content".toByteArray(StandardCharsets.UTF_8)
        serviceRegistry.addService(
            object : FetchImplBase() {
                public override fun fetchBlob(
                    request: FetchBlobRequest?, responseObserver: StreamObserver<FetchBlobResponse?>
                ) {
                    responseObserver.onError(IOException("io error"))
                }
            })
        val cacheClient: InMemoryCacheClient = InMemoryCacheClient()
        val fallbackDownloader: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */?
        Downloader > Mockito.mock<Downloader?>(Downloader::class.java)
        Mockito.doAnswer(
            Answer { invocation: InvocationOnMock? ->
                val urls: MutableList<URI?> = invocation.getArgument<MutableList<URI?>>(0)
                if (urls == ImmutableList.of<URI?>(fileUrl)) {
                    val output: Path? = invocation.getArgument<Path?>(5)
                    FileSystemUtils.writeContent(output, content)
                }
                null
            })
            .`when`<Downloader?>(fallbackDownloader)
            .download(TODO("Cannot convert element")) < List < URI shr ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        Credentials > ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        String > ArgumentMatchers.any<Any?>()
        Path > ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        String > ArgumentMatchers.eq<String?>("context")

        val downloader = newDownloader(cacheClient, fallbackDownloader)

        val downloaded = downloadBlob(downloader, fileUrl, Optional.empty<Checksum?>())

        Truth.assertThat(downloaded).isEqualTo(content)
        Truth.assertThat(eventHandler.getPosts()).isEmpty()
    }

    @Test
    @Throws(Exception::class)
    fun testStatusHandling() {
        serviceRegistry.addService(
            object : FetchImplBase() {
                public override fun fetchBlob(
                    request: FetchBlobRequest?, responseObserver: StreamObserver<FetchBlobResponse?>
                ) {
                    assertThat(request)
                        .isEqualTo(
                            FetchBlobRequest.newBuilder()
                                .setDigestFunction(DIGEST_UTIL.getDigestFunction())
                                .setOldestContentAccepted(
                                    Timestamps.fromMillis(clock.advance(Duration.ofHours(1)))
                                )
                                .addUris("http://example.com/content.txt")
                                .build()
                        )
                    responseObserver.onNext(
                        FetchBlobResponse.newBuilder()
                            .setStatus(
                                Status.newBuilder()
                                    .setCode(Code.PERMISSION_DENIED_VALUE)
                                    .setMessage("permission denied")
                                    .build()
                            )
                            .setUri("http://example.com/other.txt")
                            .build()
                    )
                    responseObserver.onCompleted()
                }
            })
        val cacheClient: RemoteCacheClient = InMemoryCacheClient()
        val downloader = newDownloader(cacheClient,  /* httpDownloader= */null)
        // Add a cache entry for the empty Digest to verify that the implementation checks the status
        // before fetching the digest.
        getFromFuture(
            cacheClient.uploadBlob(
                context, Digest.getDefaultInstance(), ByteString.EMPTY,  /* force= */false
            )
        )

        val exception: IOException? =
            Assert.assertThrows<IOException?>(
                IOException::class.java,
                ThrowingRunnable {
                    downloadBlob(
                        downloader, URI.create("http://example.com/content.txt"), Optional.empty<Checksum?>()
                    )
                })
        Truth.assertThat(exception).hasMessageThat().contains("permission denied")
        Truth.assertThat(eventHandler.getPosts())
            .containsExactly(
                FetchEvent(
                    "http://example.com/other.txt", FetchId.Downloader.GRPC,  /* success= */false
                )
            )
    }

    @Test
    @Throws(Exception::class)
    fun testPropagateChecksum() {
        val content: ByteArray = "example content".toByteArray(StandardCharsets.UTF_8)
        val contentDigest: Digest = DIGEST_UTIL.compute(content)

        serviceRegistry.addService(
            object : FetchImplBase() {
                public override fun fetchBlob(
                    request: FetchBlobRequest?, responseObserver: StreamObserver<FetchBlobResponse?>
                ) {
                    assertThat(request)
                        .isEqualTo(
                            FetchBlobRequest.newBuilder()
                                .setDigestFunction(DIGEST_UTIL.getDigestFunction())
                                .addUris("http://example.com/content.txt")
                                .addQualifiers(
                                    Qualifier.newBuilder()
                                        .setName("checksum.sri")
                                        .setValue("sha256-ot7ke6YmiSXal3UKt0K69n8C4vtUziPUmftmpbAiKQM=")
                                )
                                .build()
                        )
                    responseObserver.onNext(
                        FetchBlobResponse.newBuilder().setBlobDigest(contentDigest).build()
                    )
                    responseObserver.onCompleted()
                }
            })

        val cacheClient: RemoteCacheClient = InMemoryCacheClient()
        val downloader = newDownloader(cacheClient)

        getFromFuture(
            cacheClient.uploadBlob(
                context, contentDigest, ByteString.copyFrom(content),  /* force= */false
            )
        )
        val downloaded =
            downloadBlob(
                downloader,
                URI.create("http://example.com/content.txt"),
                Optional.of<T?>(Checksum.fromString(KeyType.SHA256, contentDigest.getHash()))
            )

        Truth.assertThat(downloaded).isEqualTo(content)
    }

    @Test
    @Throws(Exception::class)
    fun testRejectChecksumMismatch() {
        val content: ByteArray = "example content".toByteArray(StandardCharsets.UTF_8)
        val contentDigest: Digest = DIGEST_UTIL.compute(content)

        serviceRegistry.addService(
            object : FetchImplBase() {
                public override fun fetchBlob(
                    request: FetchBlobRequest?, responseObserver: StreamObserver<FetchBlobResponse?>
                ) {
                    assertThat(request)
                        .isEqualTo(
                            FetchBlobRequest.newBuilder()
                                .setDigestFunction(DIGEST_UTIL.getDigestFunction())
                                .addUris("http://example.com/content.txt")
                                .addQualifiers(
                                    Qualifier.newBuilder()
                                        .setName("checksum.sri")
                                        .setValue("sha256-ot7ke6YmiSXal3UKt0K69n8C4vtUziPUmftmpbAiKQM=")
                                )
                                .build()
                        )
                    responseObserver.onNext(
                        FetchBlobResponse.newBuilder().setBlobDigest(contentDigest).build()
                    )
                    responseObserver.onCompleted()
                }
            })

        val cacheClient: RemoteCacheClient = InMemoryCacheClient()
        val downloader = newDownloader(cacheClient)

        getFromFuture(
            cacheClient.uploadBlob(
                context, contentDigest, ByteString.copyFromUtf8("wrong content"),  /* force= */false
            )
        )

        val e: IOException? =
            Assert.assertThrows<UnrecoverableHttpException?>(
                UnrecoverableHttpException::class.java,
                ThrowingRunnable {
                    downloadBlob(
                        downloader,
                        URI.create("http://example.com/content.txt"),
                        Optional.of<T?>(Checksum.fromString(KeyType.SHA256, contentDigest.getHash()))
                    )
                })

        Truth.assertThat(e).hasMessageThat().contains(contentDigest.getHash())
        Truth.assertThat(e).hasMessageThat().contains(DIGEST_UTIL.computeAsUtf8("wrong content").getHash())
    }

    @Test
    @Throws(Exception::class)
    fun testFetchBlobRequest() {
        val request: FetchBlobRequest? =
            GrpcRemoteDownloader.newFetchBlobRequest(
                "instance name",
                false,
                ImmutableList.of<E?>(
                    URI.create("http://example.com/a"),
                    URI.create("http://example.com/b"),
                    URI.create("file:/not/limited/to/http")
                ),
                Optional.of<Checksum?>(
                    Checksum.fromSubresourceIntegrity(
                        "sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
                    )
                ),
                "canonical ID",
                DIGEST_UTIL.getDigestFunction(),
                ImmutableMap.of<K?, V?>(
                    "Authorization", ImmutableList.of<E?>("Basic Zm9vOmJhcg=="),
                    "X-Custom-Token", ImmutableList.of<E?>("foo", "bar")
                ),
                StaticCredentials.EMPTY
            )

        assertThat(request)
            .isEqualTo(
                FetchBlobRequest.newBuilder()
                    .setInstanceName("instance name")
                    .setDigestFunction(DIGEST_UTIL.getDigestFunction())
                    .addUris("http://example.com/a")
                    .addUris("http://example.com/b")
                    .addUris("file:/not/limited/to/http")
                    .addQualifiers(
                        Qualifier.newBuilder()
                            .setName("checksum.sri")
                            .setValue("sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
                    )
                    .addQualifiers(
                        Qualifier.newBuilder().setName("bazel.canonical_id").setValue("canonical ID")
                    )
                    .addQualifiers(
                        Qualifier.newBuilder()
                            .setName("http_header:Authorization")
                            .setValue("Basic Zm9vOmJhcg==")
                    )
                    .addQualifiers(
                        Qualifier.newBuilder()
                            .setName("http_header:X-Custom-Token")
                            .setValue("foo,bar")
                    )
                    .build()
            )
    }

    @Test
    @Throws(Exception::class)
    fun testFetchBlobRequest_withCredentialsPropagation() {
        val shouldPropagateCredentials = true
        val url = URI.create("http://example.com/a")

        val credentials: Credentials
        Credentials > Mockito.mock<Credentials?>(Credentials::class.java)
        Boolean > Mockito.`when`<Boolean?>(credentials.hasRequestMetadata()).thenReturn(true)
        val headers: MutableMap<String?, MutableList<String?>?> = HashMap<String?, MutableList<String?>?>()
        headers.put("CredKey", TODO("Cannot convert element"))<String> mutableListOf < kotlin . String ? > ("CredValue")

        Mockito.`when`<MutableMap<String?, MutableList<String?>?>?>(credentials.getRequestMetadata(url))
            .thenReturn(headers)

        val request: FetchBlobRequest? =
            GrpcRemoteDownloader.newFetchBlobRequest(
                "instance name",
                shouldPropagateCredentials,
                ImmutableList.of<E?>(url),
                Optional.of<Checksum?>(
                    Checksum.fromSubresourceIntegrity(
                        "sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
                    )
                ),
                "canonical ID",
                DIGEST_UTIL.getDigestFunction(),
                ImmutableMap.of<K?, V?>(),
                credentials
            )

        assertThat(request)
            .isEqualTo(
                FetchBlobRequest.newBuilder()
                    .setInstanceName("instance name")
                    .setDigestFunction(DIGEST_UTIL.getDigestFunction())
                    .addUris("http://example.com/a")
                    .addQualifiers(
                        Qualifier.newBuilder()
                            .setName("http_header_url:0:CredKey")
                            .setValue("CredValue")
                    )
                    .addQualifiers(
                        Qualifier.newBuilder()
                            .setName("checksum.sri")
                            .setValue("sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
                    )
                    .addQualifiers(
                        Qualifier.newBuilder().setName("bazel.canonical_id").setValue("canonical ID")
                    )
                    .build()
            )
    }

    @Test
    @Throws(Exception::class)
    fun testFetchBlobRequest_withoutCredentialsPropagation() {
        val shouldPropagateCredentials = false
        val url = URI.create("http://example.com/a")

        val credentials: Credentials
        Credentials > Mockito.mock<Credentials?>(Credentials::class.java)
        Boolean > Mockito.`when`<Boolean?>(credentials.hasRequestMetadata()).thenReturn(true)
        val headers: MutableMap<String?, MutableList<String?>?> = HashMap<String?, MutableList<String?>?>()
        headers.put("CredKey", ImmutableList.of<String?>("CredValue"))
        Mockito.`when`<MutableMap<String?, MutableList<String?>?>?>(credentials.getRequestMetadata(url))
            .thenReturn(headers)

        val request: FetchBlobRequest? =
            GrpcRemoteDownloader.newFetchBlobRequest(
                "instance name",
                shouldPropagateCredentials,
                ImmutableList.of<E?>(url),
                Optional.of<Checksum?>(
                    Checksum.fromSubresourceIntegrity(
                        "sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
                    )
                ),
                "canonical ID",
                DIGEST_UTIL.getDigestFunction(),
                ImmutableMap.of<K?, V?>(),
                credentials
            )

        assertThat(request)
            .isEqualTo(
                FetchBlobRequest.newBuilder()
                    .setInstanceName("instance name")
                    .setDigestFunction(DIGEST_UTIL.getDigestFunction())
                    .addUris("http://example.com/a")
                    .addQualifiers(
                        Qualifier.newBuilder()
                            .setName("checksum.sri")
                            .setValue("sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
                    )
                    .addQualifiers(
                        Qualifier.newBuilder().setName("bazel.canonical_id").setValue("canonical ID")
                    )
                    .build()
            )
    }

    @Test
    @Throws(Exception::class)
    fun testFetchBlobRequest_withoutChecksum() {
        val request: FetchBlobRequest? =
            GrpcRemoteDownloader.newFetchBlobRequest(
                "instance name",
                false,
                ImmutableList.of<E?>(URI.create("http://example.com/")),
                Optional.empty<Checksum?>(),
                "canonical ID",
                DIGEST_UTIL.getDigestFunction(),
                ImmutableMap.of<K?, V?>(),
                StaticCredentials.EMPTY
            )

        assertThat(request)
            .isEqualTo(
                FetchBlobRequest.newBuilder()
                    .setInstanceName("instance name")
                    .setDigestFunction(DIGEST_UTIL.getDigestFunction())
                    .setOldestContentAccepted(Timestamps.fromMillis(clock.advance(Duration.ofHours(1))))
                    .addUris("http://example.com/")
                    .addQualifiers(
                        Qualifier.newBuilder().setName("bazel.canonical_id").setValue("canonical ID")
                    )
                    .build()
            )
    }

    companion object {
        private val clock = ManualClock()

        private val DIGEST_UTIL: DigestUtil = DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256)
    }
}
