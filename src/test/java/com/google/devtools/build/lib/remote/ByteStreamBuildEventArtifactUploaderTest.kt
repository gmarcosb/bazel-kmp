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
package com.google.devtools.build.lib.remote

import build.bazel.remote.execution.v2.Digest

/** Test for [ByteStreamBuildEventArtifactUploader].  */
@RunWith(JUnit4::class)
class ByteStreamBuildEventArtifactUploaderTest {
    @org.junit.Rule
    val rxNoGlobalErrorsRule: RxNoGlobalErrorsRule = RxNoGlobalErrorsRule()

    private val reporter: com.google.devtools.build.lib.events.Reporter =
        com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus())
    private val eventHandler: StoredEventHandler = StoredEventHandler()

    private val serviceRegistry: MutableHandlerRegistry = MutableHandlerRegistry()
    private var retryService: com.google.common.util.concurrent.ListeningScheduledExecutorService? = null

    private var server: io.grpc.Server? = null
    private var channelConnectionFactory: ChannelConnectionWithServerCapabilitiesFactory? = null

    private val fs: FileSystem =
        InMemoryFileSystem(com.google.devtools.build.lib.clock.JavaClock(), DigestHashFunction.SHA256)

    private val execRoot: Path = fs.getPath("/execroot")
    private var outputRoot: ArtifactRoot? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        reporter.addHandler(eventHandler)

        val serverName = "Server for " + this.javaClass
        server =
            InProcessServerBuilder.forName(serverName)
                .fallbackHandlerRegistry(serviceRegistry)
                .build()
                .start()
        channelConnectionFactory =
            object : ChannelConnectionWithServerCapabilitiesFactory() {
                public override fun create(): Single<ChannelConnectionWithServerCapabilities?>? {
                    return Single.just<ChannelConnectionWithServerCapabilities?>(
                        ChannelConnectionWithServerCapabilities(
                            InProcessChannelBuilder.forName(serverName).build(),
                            Single.just<T?>(ServerCapabilities.getDefaultInstance())
                        )
                    )
                }

                public override fun maxConcurrency(): Int {
                    return 100
                }
            }

        outputRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "out")
        outputRoot.getRoot().asPath().createDirectoryAndParents()

        retryService =
            com.google.common.util.concurrent.MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1))
    }

    @org.junit.After
    @Throws(java.lang.Exception::class)
    fun tearDown() {
        retryService.shutdownNow()
        retryService.awaitTermination(
            com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
        )

        server.shutdownNow()
        server.awaitTermination()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun uploadsShouldWork() {
        val numUploads = 2
        val blobsByHash: MutableMap<com.google.common.hash.HashCode?, ByteArray?> =
            HashMap<com.google.common.hash.HashCode?, ByteArray?>()
        val filesToUpload: MutableMap<Path, LocalFile?> = HashMap<Path, LocalFile?>()
        val rand: Random = Random()
        for (i in 0..<numUploads) {
            val file: Path? = fs.getPath("/file" + i)
            val blobSize: Int = rand.nextInt(100) + 1
            val blob = ByteArray(blobSize)
            rand.nextBytes(blob)
            FileSystemUtils.writeContent(file, blob)
            blobsByHash.put(com.google.common.hash.HashCode.fromString(DIGEST_UTIL.compute(file).getHash()), blob)
            filesToUpload.put(
                file, LocalFile(file, LocalFileType.OUTPUT_FILE,  /* artifactMetadata= */null)
            )
        }
        serviceRegistry.addService(MaybeFailOnceUploadService(blobsByHash))

        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { FixedBackoff(1, 0) },
                ResultClassifier { e -> Result.TRANSIENT_FAILURE },
                retryService
            )
        val refCntChannel: ReferenceCountedChannel = ReferenceCountedChannel(channelConnectionFactory)
        val combinedCache: CombinedCache = newCombinedCache(refCntChannel, retrier)
        val artifactUploader: ByteStreamBuildEventArtifactUploader = newArtifactUploader(combinedCache)

        val pathConverter: PathConverter = artifactUploader.upload(filesToUpload).get()
        for (file in filesToUpload.keys) {
            val hash: String = com.google.common.io.BaseEncoding.base16().lowerCase().encode(file.getDigest())
            val size: Long = file.getFileSize()
            val conversion: String? = pathConverter.apply(file)
            Truth.assertThat(conversion)
                .isEqualTo("bytestream://localhost/instance/blobs/" + hash + "/" + size)
        }

        artifactUploader.release()

        assertThat(combinedCache.refCnt()).isEqualTo(0)
        assertThat(refCntChannel.isShutdown()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun uploadsShouldWork_fewerPermitsThanUploads() {
        val numUploads = 2
        val blobsByHash: MutableMap<com.google.common.hash.HashCode?, ByteArray?> =
            HashMap<com.google.common.hash.HashCode?, ByteArray?>()
        val filesToUpload: MutableMap<Path, LocalFile?> = HashMap<Path, LocalFile?>()
        val rand: Random = Random()
        for (i in 0..<numUploads) {
            val file: Path? = fs.getPath("/file" + i)
            val blobSize: Int = rand.nextInt(100) + 1
            val blob = ByteArray(blobSize)
            rand.nextBytes(blob)
            FileSystemUtils.writeContent(file, blob)
            blobsByHash.put(com.google.common.hash.HashCode.fromString(DIGEST_UTIL.compute(file).getHash()), blob)
            filesToUpload.put(
                file, LocalFile(file, LocalFileType.OUTPUT_FILE,  /* artifactMetadata= */null)
            )
        }
        serviceRegistry.addService(MaybeFailOnceUploadService(blobsByHash))

        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { FixedBackoff(1, 0) },
                ResultClassifier { e -> Result.TRANSIENT_FAILURE },
                retryService
            )
        val refCntChannel: ReferenceCountedChannel = ReferenceCountedChannel(channelConnectionFactory)
        // number of permits is less than number of uploads to affirm permit is released
        val combinedCache: CombinedCache = newCombinedCache(refCntChannel, retrier)
        val artifactUploader: ByteStreamBuildEventArtifactUploader = newArtifactUploader(combinedCache)

        val pathConverter: PathConverter = artifactUploader.upload(filesToUpload).get()
        for (file in filesToUpload.keys) {
            val hash: String = com.google.common.io.BaseEncoding.base16().lowerCase().encode(file.getDigest())
            val size: Long = file.getFileSize()
            val conversion: String? = pathConverter.apply(file)
            Truth.assertThat(conversion)
                .isEqualTo("bytestream://localhost/instance/blobs/" + hash + "/" + size)
        }

        artifactUploader.release()

        assertThat(combinedCache.refCnt()).isEqualTo(0)
        assertThat(refCntChannel.isShutdown()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun directory_notUploaded() {
        val dir: Path? = fs.getPath("/dir")
        val filesToUpload: MutableMap<Path?, LocalFile?> = HashMap<Path?, LocalFile?>()
        filesToUpload.put(
            dir, LocalFile(dir, LocalFileType.OUTPUT_DIRECTORY,  /* artifactMetadata= */null)
        )
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { FixedBackoff(1, 0) },
                ResultClassifier { e -> Result.TRANSIENT_FAILURE },
                retryService
            )
        val refCntChannel: ReferenceCountedChannel = ReferenceCountedChannel(channelConnectionFactory)
        val combinedCache: CombinedCache = newCombinedCache(refCntChannel, retrier)
        val artifactUploader: ByteStreamBuildEventArtifactUploader = newArtifactUploader(combinedCache)

        val pathConverter: PathConverter = artifactUploader.upload(filesToUpload).get()
        assertThat(pathConverter.apply(dir)).isNull()
        artifactUploader.release()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun symlink_notUploaded() {
        val sym: Path? = fs.getPath("/sym")
        val filesToUpload: MutableMap<Path?, LocalFile?> = HashMap<Path?, LocalFile?>()
        filesToUpload.put(
            sym, LocalFile(sym, LocalFileType.OUTPUT_SYMLINK,  /* artifactMetadata= */null)
        )
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { FixedBackoff(1, 0) },
                ResultClassifier { e -> Result.TRANSIENT_FAILURE },
                retryService
            )
        val refCntChannel: ReferenceCountedChannel = ReferenceCountedChannel(channelConnectionFactory)
        val combinedCache: CombinedCache = newCombinedCache(refCntChannel, retrier)
        val artifactUploader: ByteStreamBuildEventArtifactUploader = newArtifactUploader(combinedCache)

        val pathConverter: PathConverter = artifactUploader.upload(filesToUpload).get()
        assertThat(pathConverter.apply(sym)).isNull()
        artifactUploader.release()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun customHashFunction_uploaded() {
        Assume.assumeNotNull(BazelHashFunctions.BLAKE3)

        val fs: FileSystem =
            InMemoryFileSystem(com.google.devtools.build.lib.clock.JavaClock(), BazelHashFunctions.BLAKE3)
        val file: Path = fs.getPath("/file")
        FileSystemUtils.createEmptyFile(file)
        val filesToUpload: MutableMap<Path?, LocalFile?> = HashMap<Path?, LocalFile?>()
        filesToUpload.put(
            file, LocalFile(file, LocalFileType.OUTPUT_FILE,  /* artifactMetadata= */null)
        )
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { FixedBackoff(1, 0) },
                ResultClassifier { e -> Result.TRANSIENT_FAILURE },
                retryService
            )
        val refCntChannel: ReferenceCountedChannel = ReferenceCountedChannel(channelConnectionFactory)
        val combinedCache: CombinedCache = newCombinedCache(refCntChannel, retrier)
        val artifactUploader: ByteStreamBuildEventArtifactUploader = newArtifactUploader(combinedCache)

        val pathConverter: PathConverter = artifactUploader.upload(filesToUpload).get()
        val hash: String = com.google.common.io.BaseEncoding.base16().lowerCase().encode(file.getDigest())
        val size: Long = file.getFileSize()
        val conversion: String? = pathConverter.apply(file)
        Truth.assertThat(conversion)
            .isEqualTo("bytestream://localhost/instance/blobs/blake3/" + hash + "/" + size)
        artifactUploader.release()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputs_uploadedIfFiles() {
        val successfulFile: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            fs.getPath("/test.file.passed")
        FileSystemUtils.createEmptyFile(successfulFile)
        val successfulDir: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            fs.getPath("/test.dir.passed")
        successfulDir.createDirectory()
        val failedFile: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            fs.getPath("/test.file.failed")
        FileSystemUtils.createEmptyFile(failedFile)
        val failedDir: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            fs.getPath("/test.dir.failed")
        failedDir.createDirectory()
        val filesToUpload: com.google.common.collect.ImmutableMap<Any?, Any?> =
            com.google.common.collect.ImmutableMap.of<Any?, Any?>(
                successfulFile,
                LocalFile(
                    successfulFile, LocalFileType.SUCCESSFUL_TEST_OUTPUT,  /* artifactMetadata= */null
                ),
                failedFile,
                LocalFile(
                    failedFile, LocalFileType.FAILED_TEST_OUTPUT,  /* artifactMetadata= */null
                ),
                successfulDir,
                LocalFile(
                    successfulDir, LocalFileType.SUCCESSFUL_TEST_OUTPUT,  /* artifactMetadata= */null
                ),
                failedDir,
                LocalFile(
                    failedDir, LocalFileType.FAILED_TEST_OUTPUT,  /* artifactMetadata= */null
                )
            )
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { FixedBackoff(1, 0) },
                ResultClassifier { e -> Result.TRANSIENT_FAILURE },
                retryService
            )
        val refCntChannel: ReferenceCountedChannel = ReferenceCountedChannel(channelConnectionFactory)
        val combinedCache: CombinedCache = newCombinedCache(refCntChannel, retrier)
        val artifactUploader: ByteStreamBuildEventArtifactUploader = newArtifactUploader(combinedCache)

        val pathConverter: PathConverter = artifactUploader.upload(filesToUpload).get()
        assertThat(pathConverter.apply(successfulFile)).isNotNull()
        assertThat(pathConverter.apply(failedFile)).isNotNull()
        assertThat(pathConverter.apply(successfulDir)).isNull()
        assertThat(pathConverter.apply(failedDir)).isNull()
        Truth.assertThat(eventHandler.getEvents()).isEmpty()
        artifactUploader.release()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun someUploadsFail_succeedsWithWarningMessages() {
        // Test that if one of multiple file uploads fails, the upload future succeeds but the
        // error is reported correctly.

        val numUploads = 10
        val blobsByHash: MutableMap<com.google.common.hash.HashCode?, ByteArray?> =
            HashMap<com.google.common.hash.HashCode?, ByteArray?>()
        val filesToUpload: MutableMap<Path?, LocalFile?> = HashMap<Path?, LocalFile?>()
        val rand: Random = Random()
        for (i in 0..<numUploads) {
            val file: Path? = fs.getPath("/file" + i)
            val blobSize: Int = rand.nextInt(100) + 1
            val blob = ByteArray(blobSize)
            rand.nextBytes(blob)
            FileSystemUtils.writeContent(file, blob)
            blobsByHash.put(com.google.common.hash.HashCode.fromString(DIGEST_UTIL.compute(file).getHash()), blob)
            filesToUpload.put(
                file, LocalFile(file, LocalFileType.OUTPUT_FILE,  /* artifactMetadata= */null)
            )
        }
        val hashOfBlobThatShouldFail = blobsByHash.keys.iterator().next().toString()
        serviceRegistry.addService(
            object : MaybeFailOnceUploadService(blobsByHash) {
                override fun write(response: StreamObserver<WriteResponse?>): StreamObserver<WriteRequest?> {
                    val delegate: StreamObserver<WriteRequest?> = super.write(response)
                    return object : StreamObserver<WriteRequest?> {
                        private var failed = false

                        override fun onNext(value: WriteRequest) {
                            if (value.getResourceName().contains(hashOfBlobThatShouldFail)) {
                                response.onError(io.grpc.Status.CANCELLED.asException())
                                failed = true
                            } else {
                                delegate.onNext(value)
                            }
                        }

                        override fun onError(t: Throwable?) {
                            delegate.onError(t)
                        }

                        override fun onCompleted() {
                            if (failed) {
                                return
                            }
                            delegate.onCompleted()
                        }
                    }
                }
            })

        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { FixedBackoff(1, 0) },
                ResultClassifier { e -> Result.TRANSIENT_FAILURE },
                retryService
            )
        val refCntChannel: ReferenceCountedChannel = ReferenceCountedChannel(channelConnectionFactory)
        val combinedCache: CombinedCache = newCombinedCache(refCntChannel, retrier)
        val artifactUploader: ByteStreamBuildEventArtifactUploader = newArtifactUploader(combinedCache)

        artifactUploader.upload(filesToUpload).get()

        Truth.assertThat(eventHandler.getEvents()).isNotEmpty()
        Truth.assertThat(eventHandler.getEvents().get(0).getMessage())
            .contains("Uploading BEP referenced local file /file")

        artifactUploader.release()

        assertThat(combinedCache.refCnt()).isEqualTo(0)
        assertThat(refCntChannel.isShutdown()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun remoteFileShouldNotBeUploaded_actionFs() {
        // Test that we don't attempt to upload remotely stored file but convert the remote path
        // to a bytestream:// URI.

        // arrange

        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { FixedBackoff(1, 0) },
                ResultClassifier { e -> Result.TRANSIENT_FAILURE },
                retryService
            )
        val refCntChannel: ReferenceCountedChannel = ReferenceCountedChannel(channelConnectionFactory)
        val combinedCache: CombinedCache? = spy(newCombinedCache(refCntChannel, retrier))
        val actionInputFetcher: RemoteActionInputFetcher? =
            Mockito.mock<RemoteActionInputFetcher?>(RemoteActionInputFetcher::class.java)
        val artifactUploader: ByteStreamBuildEventArtifactUploader = newArtifactUploader(combinedCache)

        val outputs: ActionInputMap = ActionInputMap(2)
        val artifact: Artifact = createRemoteArtifact("file1.txt", "foo", outputs)

        val remoteFs: RemoteActionFileSystem =
            RemoteActionFileSystem(
                fs,
                execRoot.asFragment(),
                outputRoot.getRoot().asPath().relativeTo(execRoot).getPathString(),
                outputs,
                actionInputFetcher
            )
        val remotePath: Path = remoteFs.getPath(artifact.getPath().getPathString())
        assertThat(remotePath.getFileSystem()).isEqualTo(remoteFs)
        val file: LocalFile =
            LocalFile(remotePath, LocalFileType.OUTPUT_FILE,  /* artifactMetadata= */null)

        // act
        val pathConverter: PathConverter =
            artifactUploader.upload(com.google.common.collect.ImmutableMap.of<K?, V?>(remotePath, file)).get()

        val metadata: FileArtifactValue = outputs.getInputMetadata(artifact)
        val digest: Digest = DigestUtil.buildDigest(metadata.getDigest(), metadata.getSize())

        // assert
        val conversion: String? = pathConverter.apply(remotePath)
        Truth.assertThat(conversion)
            .isEqualTo(
                ("bytestream://localhost/instance/blobs/"
                        + digest.getHash()
                        + "/"
                        + digest.getSizeBytes())
            )
        Mockito.verify<Any?>(combinedCache, Mockito.times(0))
            .uploadFile(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
        Mockito.verify<Any?>(combinedCache, Mockito.times(0)).uploadBlob(
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(ByteString::class.java)
        )
        Mockito.verify<Any?>(combinedCache, Mockito.times(0)).uploadBlob(
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(Blob::class.java)
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun remoteFileShouldNotBeUploaded_findMissingDigests() {
        // Test that findMissingDigests is called to check which files exist remotely
        // and that those are not uploaded.

        // arrange

        val remoteFile: Path = fs.getPath("/remote-file")
        FileSystemUtils.writeContent(remoteFile, java.nio.charset.StandardCharsets.UTF_8, "hello world")
        val remoteDigest: Digest = DIGEST_UTIL.compute(remoteFile)
        val localFile: Path = fs.getPath("/local-file")
        FileSystemUtils.writeContent(localFile, java.nio.charset.StandardCharsets.UTF_8, "foo bar")
        val localDigest: Digest = DIGEST_UTIL.compute(localFile)

        val digestQuerier: StaticMissingDigestsFinder =
            Mockito.spy<StaticMissingDigestsFinder>(
                StaticMissingDigestsFinder(
                    com.google.common.collect.ImmutableSet.of<Digest?>(
                        remoteDigest
                    )
                )
            )
        val retrier: RemoteRetrier =
            com.google.devtools.build.lib.remote.util.TestUtils.newRemoteRetrier(
                java.util.function.Supplier { FixedBackoff(1, 0) },
                ResultClassifier { e -> Result.TRANSIENT_FAILURE },
                retryService
            )
        val refCntChannel: ReferenceCountedChannel = ReferenceCountedChannel(channelConnectionFactory)
        val combinedCache: CombinedCache? = spy(newCombinedCache(refCntChannel, retrier, digestQuerier))
        Mockito.doAnswer(Answer { invocationOnMock: InvocationOnMock? ->
            com.google.common.util.concurrent.Futures.immediateFuture<Any?>(
                null
            )
        })
            .`when`<Any?>(combinedCache)
            .uploadFile(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
        val artifactUploader: ByteStreamBuildEventArtifactUploader = newArtifactUploader(combinedCache)

        // act
        val files: com.google.common.collect.ImmutableMap<Path?, LocalFile?> =
            com.google.common.collect.ImmutableMap.of<Path?, LocalFile?>(
                remoteFile,
                LocalFile(remoteFile, LocalFileType.OUTPUT_FILE,  /* artifactMetadata= */null),
                localFile,
                LocalFile(localFile, LocalFileType.OUTPUT_FILE,  /* artifactMetadata= */null)
            )
        val pathConverter: PathConverter = artifactUploader.upload(files).get()

        // assert
        Mockito.verify<StaticMissingDigestsFinder?>(digestQuerier).findMissingDigests(
            ArgumentMatchers.any<RemoteActionExecutionContext?>(),
            ArgumentMatchers.any<Iterable<Digest?>?>()
        )
        Mockito.verify<Any?>(combinedCache)
            .uploadFile(ArgumentMatchers.any<T?>(), < T > eq < T ? > (localDigest), ArgumentMatchers.any<T?>())
        com.google.common.truth.Subject.contains(remoteDigest.getHash())
        com.google.common.truth.Subject.contains(localDigest.getHash())
    }

    /** Returns a remote artifact and puts its metadata into the action input map.  */
    private fun createRemoteArtifact(
        pathFragment: String?, contents: String, inputs: ActionInputMap
    ): Artifact {
        val p: Path? = outputRoot.getRoot().asPath().getRelative(pathFragment)
        val a: Artifact = ActionsTestUtil.createArtifact(outputRoot, p)
        val b: ByteArray = contents.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        val h: com.google.common.hash.HashCode =
            com.google.common.hash.HashCode.fromString(DIGEST_UTIL.compute(b).getHash())
        val f: FileArtifactValue? =
            FileArtifactValue.createForRemoteFile(h.asBytes(), b.size,  /* locationIndex= */1)
        inputs.put(a, f)
        return a
    }

    private fun newArtifactUploader(combinedCache: CombinedCache?): ByteStreamBuildEventArtifactUploader {
        return ByteStreamBuildEventArtifactUploader(
            com.google.common.util.concurrent.MoreExecutors.directExecutor(),
            reporter,  /* verboseFailures= */
            true,
            combinedCache,  /* remoteInstanceName= */
            "",  /* remoteBytestreamUriPrefix= */
            "localhost/instance",  /* buildRequestId= */
            "none",  /* commandId= */
            "none",
            SyscallCache.NO_CACHE,
            RemoteBuildEventUploadMode.ALL
        )
    }

    private class StaticMissingDigestsFinder(knownDigests: com.google.common.collect.ImmutableSet<Digest?>) :
        MissingDigestsFinder {
        private val knownDigests: com.google.common.collect.ImmutableSet<Digest?>

        init {
            this.knownDigests = knownDigests
        }

        public override fun findMissingDigests(
            context: RemoteActionExecutionContext?, digests: Iterable<Digest?>
        ): com.google.common.util.concurrent.ListenableFuture<com.google.common.collect.ImmutableSet<Digest?>?> {
            val missingDigests: com.google.common.collect.ImmutableSet.Builder<Digest?> =
                com.google.common.collect.ImmutableSet.builder<Digest?>()
            for (digest in digests) {
                if (!knownDigests.contains(digest)) {
                    missingDigests.add(digest)
                }
            }
            return com.google.common.util.concurrent.Futures.immediateFuture<com.google.common.collect.ImmutableSet<Digest?>?>(
                missingDigests.build()
            )
        }
    }

    private class AllMissingDigestsFinder : MissingDigestsFinder {
        public override fun findMissingDigests(
            context: RemoteActionExecutionContext?, digests: Iterable<Digest?>
        ): com.google.common.util.concurrent.ListenableFuture<com.google.common.collect.ImmutableSet<Digest?>?> {
            return com.google.common.util.concurrent.Futures.immediateFuture<com.google.common.collect.ImmutableSet<Digest?>?>(
                com.google.common.collect.ImmutableSet.copyOf<Digest?>(digests)
            )
        }
    }

    companion object {
        private val DIGEST_UTIL: DigestUtil = DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256)

        private fun newCombinedCache(
            channel: ReferenceCountedChannel?, retrier: RemoteRetrier?
        ): CombinedCache {
            return newCombinedCache(channel, retrier, AllMissingDigestsFinder())
        }

        private fun newCombinedCache(
            channel: ReferenceCountedChannel?,
            retrier: RemoteRetrier?,
            missingDigestsFinder: MissingDigestsFinder
        ): CombinedCache {
            val remoteOptions: RemoteOptions =
                com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
            remoteOptions.remoteInstanceName = "instance"
            val cacheClient: GrpcCacheClient? =
                spy(
                    GrpcCacheClient(
                        channel,
                        CallCredentialsProvider.NO_CREDENTIALS,
                        remoteOptions,
                        retrier,
                        DIGEST_UTIL
                    )
                )
            Mockito.doAnswer(
                Answer { invocationOnMock: InvocationOnMock? ->
                    missingDigestsFinder.findMissingDigests(
                        invocationOnMock.getArgument<T?>(0), invocationOnMock.getArgument<T?>(1)
                    )
                })
                .`when`<Any?>(cacheClient)
                .findMissingDigests(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

            return CombinedCache(
                cacheClient,  /* diskCacheClient= */
                null,  /* symlinkTemplate= */
                null,
                DIGEST_UTIL,  /* chunkingEnabled= */
                false
            )
        }
    }
}
