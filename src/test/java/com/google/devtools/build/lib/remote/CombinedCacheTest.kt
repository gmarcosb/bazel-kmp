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

import com.google.devtools.build.lib.remote.util.Utils.getFromFuture

/** Tests for [CombinedCache].  */
@RunWith(JUnit4::class)
class CombinedCacheTest {
    @org.junit.Rule
    val rxNoGlobalErrorsRule: RxNoGlobalErrorsRule = RxNoGlobalErrorsRule()

    private var metadata: RequestMetadata? = null
    private var remoteActionExecutionContext: RemoteActionExecutionContext? = null
    private var fs: FileSystem? = null
    private var execRoot: Path? = null
    var artifactRoot: ArtifactRoot? = null
    private val digestUtil: DigestUtil = DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256)
    private val merkleTreeComputer: MerkleTreeComputer = MerkleTreeComputer(
        digestUtil,  /* remoteExecutionCache= */
        null,
        "buildRequestId",
        "commandId",
        TestConstants.WORKSPACE_NAME
    )
    private var fakeFileCache: com.google.devtools.build.lib.remote.FakeActionInputFileCache? = null

    private var retryService: com.google.common.util.concurrent.ListeningScheduledExecutorService? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        metadata = TracingMetadataUtils.buildMetadata("none", "none", "action-id", null)
        val spawn: Spawn =
            SimpleSpawn(
                FakeOwner("foo", "bar", "//dummy:label"),  /* arguments= */
                com.google.common.collect.ImmutableList.of<E?>(),  /* environment= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* executionInfo= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* inputs= */
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),  /* outputs= */
                com.google.common.collect.ImmutableSet.of<E?>(),
                ResourceSet.ZERO
            )
        val spawnExecutionContext: SpawnExecutionContext? =
            Mockito.mock<SpawnExecutionContext?>(SpawnExecutionContext::class.java)
        remoteActionExecutionContext =
            RemoteActionExecutionContext.create(spawn, spawnExecutionContext, metadata)
        fs = InMemoryFileSystem(com.google.devtools.build.lib.clock.JavaClock(), DigestHashFunction.SHA256)
        execRoot = fs.getPath("/execroot/main")
        execRoot.createDirectoryAndParents()
        fakeFileCache = com.google.devtools.build.lib.remote.FakeActionInputFileCache(execRoot)
        artifactRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "outputs")
        artifactRoot.getRoot().asPath().createDirectoryAndParents()
        retryService =
            com.google.common.util.concurrent.MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1))
    }

    @org.junit.After
    @Throws(java.lang.InterruptedException::class)
    fun afterEverything() {
        retryService.shutdownNow()
        retryService.awaitTermination(
            com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS,
            TimeUnit.SECONDS
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDownloadEmptyBlobAndFile() {
        // Test that downloading an empty BLOB/file does not try to perform a download.

        // arrange

        val file: Path = fs.getPath("/execroot/file")
        val combinedCache: InMemoryCombinedCache = newCombinedCache()
        val emptyDigest: Digest? = digestUtil.compute(ByteArray(0))

        // act and assert
        assertThat(getFromFuture(combinedCache.downloadBlob(remoteActionExecutionContext, emptyDigest)))
            .isEmpty()

        file.getOutputStream().use { out ->
            getFromFuture(combinedCache.downloadFile(remoteActionExecutionContext, file, emptyDigest))
        }
        assertThat(file.exists()).isTrue()
        assertThat(file.getFileSize()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadActionResult_reportsSpawnCheckingCacheEvent() {
        val combinedCache: InMemoryCombinedCache = newCombinedCache()
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            combinedCache.downloadActionResult(
                remoteActionExecutionContext,
                digestUtil.asActionKey(digestUtil.computeAsUtf8("key")),  /* inlineOutErr= */
                false,  /* inlineOutputFiles= */
                com.google.common.collect.ImmutableSet.of<E?>()
            )

        Mockito.verify<T?>(remoteActionExecutionContext.getSpawnExecutionContext())
            .report(SpawnCheckingCacheEvent.create("remote-cache"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadFile_cancelled_cancelDownload() {
        // Test that if a download future is cancelled, the download itself is also cancelled.

        // arrange

        val remoteCacheClient: RemoteCacheClient? = Mockito.mock<RemoteCacheClient?>(RemoteCacheClient::class.java)
        val future: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
        // Return a future that never completes
        Mockito.doAnswer(Answer { invocationOnMock: InvocationOnMock? -> future }).`when`<Any?>(remoteCacheClient)
            .downloadBlob(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
        val remoteCache: CombinedCache = newCombinedCache(remoteCacheClient)
        val digest: Digest = fakeFileCache.createScratchInput(ActionInputHelper.fromPath("file"), "content")
        val file: Path? = execRoot.getRelative("file")

        // act
        val download: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
            remoteCache.downloadFile(remoteActionExecutionContext, file, digest)
        download.cancel( /* mayInterruptIfRunning= */true)

        // assert
        Truth.assertThat(future.isCancelled()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutErr_empty_doNotPerformDownload() {
        // Test that downloading empty stdout/stderr does not try to perform a download.

        val combinedCache: InMemoryCombinedCache = newCombinedCache()
        val emptyDigest: Digest? = digestUtil.compute(ByteArray(0))
        val result: ActionResult.Builder = ActionResult.newBuilder()
        result.setStdoutDigest(emptyDigest)
        result.setStderrDigest(emptyDigest)

        waitForBulkTransfer(
            combinedCache.downloadOutErr(
                remoteActionExecutionContext,
                result.build(),
                FileOutErr(execRoot.getRelative("stdout"), execRoot.getRelative("stderr"))
            )
        )

        Truth.assertThat(combinedCache.getNumSuccessfulDownloads()).isEqualTo(0)
        Truth.assertThat(combinedCache.getNumFailedDownloads()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDownloadFileWithSymlinkTemplate() {
        // Test that when a symlink template is provided, we don't actually download files to disk.
        // Instead, a symbolic link should be created that points to a location where the file may
        // actually be found. That location could, for example, be backed by a FUSE file system that
        // exposes the Content Addressable Storage.

        // arrange

        val cas: ConcurrentMap<Digest?, ByteArray?> = ConcurrentHashMap<Digest?, ByteArray?>()

        val helloDigest: Digest? = digestUtil.computeAsUtf8("hello-contents")
        cas.put(helloDigest, "hello-contents".toByteArray(java.nio.charset.StandardCharsets.UTF_8))

        val file: Path = fs.getPath("/execroot/symlink-to-file")
        val combinedCache: InMemoryCombinedCache =
            newCombinedCache(cas, digestUtil, "/home/alice/cas/{hash}-{size_bytes}")

        // act
        getFromFuture(combinedCache.downloadFile(remoteActionExecutionContext, file, helloDigest))

        // assert
        assertThat(file.isSymbolicLink()).isTrue()
        assertThat(file.readSymbolicLink())
            .isEqualTo(
                PathFragment.create(
                    "/home/alice/cas/a378b939ad2e1d470a9a28b34b0e256b189e85cb236766edc1d46ec3b6ca82e5-14"
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun upload_emptyBlobAndFile_doNotPerformUpload() {
        // Test that uploading an empty BLOB/file does not try to perform an upload.
        val combinedCache: InMemoryCombinedCache = newCombinedCache()
        val emptyDigest: Digest = fakeFileCache.createScratchInput(ActionInputHelper.fromPath("file"), "")
        val file: Path? = execRoot.getRelative("file")

        getFromFuture(
            combinedCache.uploadBlob(remoteActionExecutionContext, emptyDigest, ByteString.EMPTY)
        )
        assertThat(
            getFromFuture(
                combinedCache.findMissingDigests(
                    remoteActionExecutionContext, com.google.common.collect.ImmutableSet.of<E?>(emptyDigest)
                )
            )
        )
            .containsExactly(emptyDigest)

        getFromFuture(combinedCache.uploadFile(remoteActionExecutionContext, emptyDigest, file))
        assertThat(
            getFromFuture(
                combinedCache.findMissingDigests(
                    remoteActionExecutionContext, com.google.common.collect.ImmutableSet.of<E?>(emptyDigest)
                )
            )
        )
            .containsExactly(emptyDigest)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun upload_deduplicationWorks() {
        val remoteCacheClient: RemoteCacheClient? = Mockito.spy<InMemoryCacheClient?>(InMemoryCacheClient())
        val times: AtomicInteger = AtomicInteger(0)
        Mockito.doAnswer(
            Answer { invocationOnMock: InvocationOnMock? ->
                times.incrementAndGet()
                com.google.common.util.concurrent.SettableFuture.create<Any?>()
            })
            .`when`<Any?>(remoteCacheClient)
            .uploadBlobImpl(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
        val combinedCache: CombinedCache = newCombinedCache(remoteCacheClient)
        val digest: Digest = fakeFileCache.createScratchInput(ActionInputHelper.fromPath("file"), "content")
        val file: Path? = execRoot.getRelative("file")

        val unused1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            combinedCache.uploadFile(remoteActionExecutionContext, digest, file)
        val unused2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            combinedCache.uploadFile(remoteActionExecutionContext, digest, file)

        Truth.assertThat(times.get()).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun upload_failedUploads_doNotDeduplicate() {
        val failRequest: AtomicBoolean = AtomicBoolean(true)
        val remoteCacheClient: RemoteCacheClient? = Mockito.spy<InMemoryCacheClient?>(InMemoryCacheClient())
        Mockito.doAnswer(
            Answer { invocationOnMock: InvocationOnMock? ->
                if (failRequest.getAndSet(false)) {
                    return@doAnswer com.google.common.util.concurrent.Futures.immediateFailedFuture<Any?>(IOException("Failed"))
                }
                invocationOnMock.callRealMethod()
            })
            .`when`<Any?>(remoteCacheClient)
            .uploadBlobImpl(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
        val combinedCache: CombinedCache = newCombinedCache(remoteCacheClient)
        val digest: Digest = fakeFileCache.createScratchInput(ActionInputHelper.fromPath("file"), "content")
        val file: Path? = execRoot.getRelative("file")
        assertThat(
            getFromFuture(
                combinedCache.findMissingDigests(
                    remoteActionExecutionContext, com.google.common.collect.ImmutableList.of<E?>(digest)
                )
            )
        )
            .containsExactly(digest)

        var thrown: java.lang.Exception? = null
        try {
            getFromFuture(combinedCache.uploadFile(remoteActionExecutionContext, digest, file))
        } catch (e: IOException) {
            thrown = e
        }
        Truth.assertThat(thrown).isNotNull()
        Truth.assertThat(thrown).isInstanceOf(IOException::class.java)
        getFromFuture(combinedCache.uploadFile(remoteActionExecutionContext, digest, file))

        assertThat(
            getFromFuture(
                combinedCache.findMissingDigests(
                    remoteActionExecutionContext, com.google.common.collect.ImmutableList.of<E?>(digest)
                )
            )
        )
            .isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ensureInputsPresent_missingInputs_exceptionHasLostInputs() {
        val cacheProtocol: RemoteCacheClient? = Mockito.spy<InMemoryCacheClient?>(InMemoryCacheClient())
        val remoteCache: RemoteExecutionCache = spy(newRemoteExecutionCache(cacheProtocol))
        remoteActionExecutionContext = RemoteActionExecutionContext.create(metadata)
        remoteCache.setRemotePathChecker(
            { context, path ->
                com.google.common.util.concurrent.Futures.immediateFuture<V?>(
                    !path.relativeTo(execRoot).equals(PathFragment.create("foo"))
                )
            })

        val path: Path = execRoot.getRelative("foo")
        FileSystemUtils.writeContentAsLatin1(path, "bar")
        val inputs: SortedMap<PathFragment?, Path?> = TreeMap<PathFragment?, Path?>()
        inputs.put(PathFragment.create("foo"), path)
        val merkleTree: @NotNull Uploadable = merkleTreeComputer.buildForFiles(inputs)
        path.delete()

        val e: T =
            org.junit.Assert.assertThrows<T>(
                BulkTransferException::class.java,
                org.junit.function.ThrowingRunnable {
                    remoteCache.ensureInputsPresent(
                        remoteActionExecutionContext,
                        merkleTree,
                        com.google.common.collect.ImmutableMap.of<K?, V?>(),
                        false,
                        DefaultRemotePathResolver(execRoot)
                    )
                })
        assertThat(e.getLostArtifacts(ActionInputHelper::fromPath).byDigest())
            .containsExactly(
                DigestUtil.toString(digestUtil.computeAsUtf8("bar")),
                ActionInputHelper.fromPath("foo")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ensureInputsPresent_sharedMissingDigest_exceptionsHaveOwnLostInputs() {
        val cacheProtocol: RemoteCacheClient? = Mockito.spy<InMemoryCacheClient?>(InMemoryCacheClient())
        val remoteCache: RemoteExecutionCache = spy(newRemoteExecutionCache(cacheProtocol))

        val findMissingDigestsCalls: CountDownLatch = CountDownLatch(2)
        Mockito.doAnswer(
            Answer { invocationOnMock: InvocationOnMock? ->
                findMissingDigestsCalls.countDown()
                invocationOnMock.callRealMethod()
            })
            .`when`<Any?>(cacheProtocol)
            .findMissingDigests(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

        val missingInputAvailable: com.google.common.util.concurrent.SettableFuture<Boolean?> =
            com.google.common.util.concurrent.SettableFuture.create<Boolean?>()
        val remotePathChecked: CountDownLatch = CountDownLatch(1)
        remoteCache.setRemotePathChecker(
            { context, path ->
                val execPath: PathFragment = path.relativeTo(execRoot)
                if (execPath.equals(PathFragment.create("outputs/foo"))
                    || execPath.equals(PathFragment.create("outputs/bar"))
                ) {
                    remotePathChecked.countDown()
                    return@setRemotePathChecker missingInputAvailable
                }
                com.google.common.util.concurrent.Futures.immediateFuture<V?>(true)
            })

        val foo: Artifact = ActionsTestUtil.createArtifact(artifactRoot, "foo")
        val bar: Artifact = ActionsTestUtil.createArtifact(artifactRoot, "bar")
        val digest: Digest = fakeFileCache.createScratchInput(foo, "same")
        assertThat(fakeFileCache.createScratchInput(bar, "same")).isEqualTo(digest)

        val fooSpawn: Spawn = SpawnBuilder().withInput(foo).build()
        val fooContext: FakeSpawnExecutionContext =
            FakeSpawnExecutionContext(
                fooSpawn,
                fakeFileCache,
                execRoot,
                FileOutErr(execRoot.getRelative("stdout"), execRoot.getRelative("stderr")),
                com.google.common.collect.ImmutableClassToInstanceMap.of<ActionContext?>(),  /* actionFileSystem= */
                null
            )
        val fooRemoteContext: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RemoteActionExecutionContext.create(fooSpawn, fooContext, metadata)
        val fooTree: Uploadable? =
            merkleTreeComputer.buildForSpawn(
                fooSpawn,
                com.google.common.collect.ImmutableSet.of<PathFragment>(),  /* scrubber= */
                null,
                fooContext,
                RemotePathResolver.createDefault(execRoot),
                MerkleTreeComputer.BlobPolicy.KEEP_AND_REUPLOAD
            ) as Uploadable?

        val barSpawn: Spawn = SpawnBuilder().withInput(bar).build()
        val barContext: FakeSpawnExecutionContext =
            FakeSpawnExecutionContext(
                barSpawn,
                fakeFileCache,
                execRoot,
                FileOutErr(execRoot.getRelative("stdout"), execRoot.getRelative("stderr")),
                com.google.common.collect.ImmutableClassToInstanceMap.of<ActionContext?>(),  /* actionFileSystem= */
                null
            )
        val barRemoteContext: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RemoteActionExecutionContext.create(barSpawn, barContext, metadata)
        val barTree: Uploadable? =
            merkleTreeComputer.buildForSpawn(
                barSpawn,
                com.google.common.collect.ImmutableSet.of<PathFragment>(),  /* scrubber= */
                null,
                barContext,
                RemotePathResolver.createDefault(execRoot),
                MerkleTreeComputer.BlobPolicy.KEEP_AND_REUPLOAD
            ) as Uploadable?

        val fooFailure: AtomicReference<Throwable?> = AtomicReference<Throwable?>()
        val fooThread: java.lang.Thread =
            java.lang.Thread(
                java.lang.Runnable {
                    try {
                        remoteCache.ensureInputsPresent(
                            fooRemoteContext,
                            fooTree,
                            com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* force= */
                            false,
                            RemotePathResolver.createDefault(execRoot)
                        )
                    } catch (t: Throwable) {
                        if (t is java.lang.InterruptedException) {
                            java.lang.Thread.currentThread().interrupt()
                        }
                        fooFailure.set(t)
                    }
                })
        fooThread.start()
        Truth.assertThat(
            remotePathChecked.await(
                com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )
        ).isTrue()

        val barFailure: AtomicReference<Throwable?> = AtomicReference<Throwable?>()
        val barThread: java.lang.Thread =
            java.lang.Thread(
                java.lang.Runnable {
                    try {
                        remoteCache.ensureInputsPresent(
                            barRemoteContext,
                            barTree,
                            com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* force= */
                            false,
                            RemotePathResolver.createDefault(execRoot)
                        )
                    } catch (t: Throwable) {
                        if (t is java.lang.InterruptedException) {
                            java.lang.Thread.currentThread().interrupt()
                        }
                        barFailure.set(t)
                    }
                })
        barThread.start()
        Truth.assertThat(
            findMissingDigestsCalls.await(
                com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )
        )
            .isTrue()

        missingInputAvailable.set(false)
        fooThread.join()
        barThread.join()

        Truth.assertThat(fooFailure.get()).isInstanceOf(BulkTransferException::class.java)
        assertThat(
            (fooFailure.get() as BulkTransferException)
                .getLostArtifacts({ execPath -> if (execPath.equals(foo.getExecPath())) foo else null })
                .byDigest()
        )
            .containsExactly(DigestUtil.toString(digest), foo)

        Truth.assertThat(barFailure.get()).isInstanceOf(BulkTransferException::class.java)
        assertThat(
            (barFailure.get() as BulkTransferException)
                .getLostArtifacts({ execPath -> if (execPath.equals(bar.getExecPath())) bar else null })
                .byDigest()
        )
            .containsExactly(DigestUtil.toString(digest), bar)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ensureInputsPresent_interruptedDuringUploadBlobs_cancelInProgressUploadTasks() {
        // arrange
        val cacheProtocol: RemoteCacheClient = Mockito.spy<InMemoryCacheClient>(InMemoryCacheClient())
        val remoteCache: RemoteExecutionCache = spy(newRemoteExecutionCache(cacheProtocol))
        remoteActionExecutionContext = RemoteActionExecutionContext.create(metadata)

        val futures: Deque<com.google.common.util.concurrent.SettableFuture<java.lang.Void?>> =
            ConcurrentLinkedDeque<com.google.common.util.concurrent.SettableFuture<java.lang.Void?>>()
        val uploadBlobCalls: CountDownLatch = CountDownLatch(2)
        Mockito.doAnswer(
            Answer { invocationOnMock: InvocationOnMock? ->
                val future: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
                    com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
                futures.add(future)
                uploadBlobCalls.countDown()
                future
            })
            .`when`<Any?>(cacheProtocol)
            .uploadBlobImpl(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<Any?>() as Blob?
            )
        Mockito.doAnswer(
            Answer { invocationOnMock: InvocationOnMock? ->
                val future: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
                    com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
                futures.add(future)
                uploadBlobCalls.countDown()
                future
            })
            .`when`<Any?>(cacheProtocol)
            .uploadBlobImpl(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

        val path: Path? = execRoot.getRelative("foo")
        FileSystemUtils.writeContentAsLatin1(path, "bar")
        val inputs: SortedMap<PathFragment?, Path?> = TreeMap<PathFragment?, Path?>()
        inputs.put(PathFragment.create("foo"), path)
        val merkleTree: @NotNull Uploadable = merkleTreeComputer.buildForFiles(inputs)

        val ensureInputsPresentReturned: CountDownLatch = CountDownLatch(1)
        val thread: java.lang.Thread =
            java.lang.Thread(
                java.lang.Runnable {
                    try {
                        remoteCache.ensureInputsPresent(
                            remoteActionExecutionContext,
                            merkleTree,
                            com.google.common.collect.ImmutableMap.of<K?, V?>(),
                            false,  /* remotePathResolver= */
                            null
                        )
                    } catch (ignored: IOException) {
                        // ignored
                    } catch (ignored: java.lang.InterruptedException) {
                    } finally {
                        ensureInputsPresentReturned.countDown()
                    }
                })

        // act
        thread.start()
        uploadBlobCalls.await()
        Truth.assertThat(futures).hasSize(2)
        assertThat(cacheProtocol.getInProgressUploads()).isNotEmpty()

        thread.interrupt()
        ensureInputsPresentReturned.await()

        // assert
        assertThat(cacheProtocol.getInProgressUploads()).isEmpty()
        assertThat(cacheProtocol.getFinishedUploads()).isEmpty()
        for (future in futures) {
            Truth.assertThat(future.isCancelled()).isTrue()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ensureInputsPresent_multipleConsumers_interruptedOneDuringFindMissingBlobs_keepAndFinishInProgressUploadTasks() {
        // arrange
        val cacheProtocol: RemoteCacheClient = Mockito.spy<InMemoryCacheClient>(InMemoryCacheClient())
        val remoteCache: RemoteExecutionCache = newRemoteExecutionCache(cacheProtocol)
        remoteActionExecutionContext = RemoteActionExecutionContext.create(metadata)

        val findMissingDigestsFuture: com.google.common.util.concurrent.SettableFuture<com.google.common.collect.ImmutableSet<Digest?>?> =
            com.google.common.util.concurrent.SettableFuture.create<com.google.common.collect.ImmutableSet<Digest?>?>()
        val findMissingDigestsCalled: CountDownLatch = CountDownLatch(1)
        Mockito.doAnswer(
            Answer { invocationOnMock: InvocationOnMock? ->
                findMissingDigestsCalled.countDown()
                findMissingDigestsFuture
            })
            .`when`<Any?>(cacheProtocol)
            .findMissingDigests(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
        val futures: Deque<com.google.common.util.concurrent.SettableFuture<java.lang.Void?>> =
            ConcurrentLinkedDeque<com.google.common.util.concurrent.SettableFuture<java.lang.Void?>>()
        val uploadBlobCalls: CountDownLatch = CountDownLatch(2)
        Mockito.doAnswer(
            Answer { invocationOnMock: InvocationOnMock? ->
                val future: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
                    com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
                futures.add(future)
                uploadBlobCalls.countDown()
                future
            })
            .`when`<Any?>(cacheProtocol)
            .uploadBlobImpl(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<Any?>() as Blob?
            )
        Mockito.doAnswer(
            Answer { invocationOnMock: InvocationOnMock? ->
                val future: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
                    com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
                futures.add(future)
                uploadBlobCalls.countDown()
                future
            })
            .`when`<Any?>(cacheProtocol)
            .uploadBlobImpl(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

        val path: Path? = execRoot.getRelative("foo")
        FileSystemUtils.writeContentAsLatin1(path, "bar")
        val inputs: SortedMap<PathFragment?, Path?> = TreeMap<PathFragment?, Path?>()
        inputs.put(PathFragment.create("foo"), path)
        val merkleTree: @NotNull Uploadable = merkleTreeComputer.buildForFiles(inputs)

        val ensureInputsPresentReturned: CountDownLatch = CountDownLatch(2)
        val ensureInterrupted: CountDownLatch = CountDownLatch(1)
        val work: java.lang.Runnable =
            java.lang.Runnable {
                try {
                    remoteCache.ensureInputsPresent(
                        remoteActionExecutionContext,
                        merkleTree,
                        com.google.common.collect.ImmutableMap.of<K?, V?>(),
                        false,  /* remotePathResolver= */
                        null
                    )
                } catch (ignored: IOException) {
                    // ignored
                } catch (e: java.lang.InterruptedException) {
                    ensureInterrupted.countDown()
                } finally {
                    ensureInputsPresentReturned.countDown()
                }
            }
        val thread1: java.lang.Thread = java.lang.Thread(work)
        val thread2: java.lang.Thread = java.lang.Thread(work)
        thread1.start()
        thread2.start()
        findMissingDigestsCalled.await()

        // act
        thread1.interrupt()
        ensureInterrupted.await()
        findMissingDigestsFuture.set(com.google.common.collect.ImmutableSet.copyOf(merkleTree.allDigests()))

        uploadBlobCalls.await()
        Truth.assertThat(futures).hasSize(2)

        // assert
        assertThat(cacheProtocol.getInProgressUploads()).hasSize(2)
        assertThat(cacheProtocol.getFinishedUploads()).isEmpty()
        for (future in futures) {
            Truth.assertThat(future.isCancelled()).isFalse()
        }

        for (future in futures) {
            future.set(null)
        }
        ensureInputsPresentReturned.await()
        assertThat(cacheProtocol.getInProgressUploads()).isEmpty()
        assertThat(cacheProtocol.getFinishedUploads()).hasSize(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ensureInputsPresent_multipleConsumers_interruptedOneDuringUploadBlobs_keepInProgressUploadTasks() {
        // arrange
        val cacheProtocol: RemoteCacheClient = Mockito.spy<InMemoryCacheClient>(InMemoryCacheClient())
        val remoteCache: RemoteExecutionCache = spy(newRemoteExecutionCache(cacheProtocol))
        remoteActionExecutionContext = RemoteActionExecutionContext.create(metadata)

        val uploadFutures: MutableMap<Digest?, com.google.common.util.concurrent.SettableFuture<java.lang.Void?>> =
            com.google.common.collect.Maps.newConcurrentMap<Digest?, com.google.common.util.concurrent.SettableFuture<java.lang.Void?>?>()
        // 3 unique file digests + 2 unique directory blob digests = 5 uploads total.
        val uploadCalls: CountDownLatch = CountDownLatch(5)
        Mockito.doAnswer(
            Answer { invocationOnMock: InvocationOnMock? ->
                val digest: Digest? = invocationOnMock.getArgument<Digest?>(1, Digest::class.java)
                val future: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
                    com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
                uploadFutures.put(digest, future)
                uploadCalls.countDown()
                future
            })
            .`when`<Any?>(cacheProtocol)
            .uploadBlobImpl(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<Any?>() as Blob?
            )

        val foo: Path? = execRoot.getRelative("foo")
        FileSystemUtils.writeContentAsLatin1(foo, "foo")
        val bar: Path? = execRoot.getRelative("bar")
        FileSystemUtils.writeContentAsLatin1(bar, "bar")
        val qux: Path? = execRoot.getRelative("qux")
        FileSystemUtils.writeContentAsLatin1(qux, "qux")
        val fooDigest: Digest? = digestUtil.computeAsUtf8("foo")
        val barDigest: Digest? = digestUtil.computeAsUtf8("bar")
        val quxDigest: Digest? = digestUtil.computeAsUtf8("qux")

        val input1: SortedMap<PathFragment?, Path?> = TreeMap<PathFragment?, Path?>()
        input1.put(PathFragment.create("foo"), foo)
        input1.put(PathFragment.create("bar"), bar)
        val merkleTree1: @NotNull Uploadable = merkleTreeComputer.buildForFiles(input1)

        val input2: SortedMap<PathFragment?, Path?> = TreeMap<PathFragment?, Path?>()
        input2.put(PathFragment.create("bar"), bar)
        input2.put(PathFragment.create("qux"), qux)
        val merkleTree2: @NotNull Uploadable = merkleTreeComputer.buildForFiles(input2)

        val ensureInputsPresentReturned: CountDownLatch = CountDownLatch(2)
        val ensureInterrupted: CountDownLatch = CountDownLatch(1)
        val thread1: java.lang.Thread =
            java.lang.Thread(
                java.lang.Runnable {
                    try {
                        remoteCache.ensureInputsPresent(
                            remoteActionExecutionContext,
                            merkleTree1,
                            com.google.common.collect.ImmutableMap.of<K?, V?>(),
                            false,  /* remotePathResolver= */
                            null
                        )
                    } catch (ignored: IOException) {
                        // ignored
                    } catch (e: java.lang.InterruptedException) {
                        ensureInterrupted.countDown()
                    } finally {
                        ensureInputsPresentReturned.countDown()
                    }
                })
        val thread2: java.lang.Thread =
            java.lang.Thread(
                java.lang.Runnable {
                    try {
                        remoteCache.ensureInputsPresent(
                            remoteActionExecutionContext,
                            merkleTree2,
                            com.google.common.collect.ImmutableMap.of<K?, V?>(),
                            false,  /* remotePathResolver= */
                            null
                        )
                    } catch (ignored: java.lang.InterruptedException) {
                        // ignored
                    } catch (ignored: IOException) {
                    } finally {
                        ensureInputsPresentReturned.countDown()
                    }
                })

        // act
        thread1.start()
        thread2.start()
        uploadCalls.await()
        Truth.assertThat(uploadFutures).hasSize(5)
        assertThat(cacheProtocol.getInProgressUploads()).hasSize(5)

        thread1.interrupt()
        ensureInterrupted.await()

        // assert
        assertThat(cacheProtocol.getInProgressUploads()).hasSize(3)
        assertThat(cacheProtocol.getFinishedUploads()).isEmpty()
        // foo is only in tree1, so interrupting thread1 cancels it; bar is shared and qux is only in
        // tree2, so both are kept.
        Truth.assertThat(uploadFutures.get(fooDigest).isCancelled()).isTrue()
        Truth.assertThat(uploadFutures.get(barDigest).isCancelled()).isFalse()
        Truth.assertThat(uploadFutures.get(quxDigest).isCancelled()).isFalse()

        for (future in uploadFutures.values) {
            future.set(null)
        }
        ensureInputsPresentReturned.await()
        assertThat(cacheProtocol.getInProgressUploads()).isEmpty()
        assertThat(cacheProtocol.getFinishedUploads()).hasSize(3)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ensureInputsPresent_uploadFailed_propagateErrors() {
        val cacheProtocol: RemoteCacheClient? = Mockito.spy<InMemoryCacheClient?>(InMemoryCacheClient())
        remoteActionExecutionContext = RemoteActionExecutionContext.create(metadata)
        Mockito.doAnswer(Answer { invocationOnMock: InvocationOnMock? ->
            com.google.common.util.concurrent.Futures.immediateFailedFuture<Any?>(
                IOException("upload failed")
            )
        })
            .`when`<Any?>(cacheProtocol)
            .uploadBlobImpl(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<Any?>() as Blob?
            )
        Mockito.doAnswer(Answer { invocationOnMock: InvocationOnMock? ->
            com.google.common.util.concurrent.Futures.immediateFailedFuture<Any?>(
                IOException("upload failed")
            )
        })
            .`when`<Any?>(cacheProtocol)
            .uploadBlobImpl(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
        val remoteCache: RemoteExecutionCache = spy(newRemoteExecutionCache(cacheProtocol))
        val path: Path? = execRoot.getRelative("foo")
        FileSystemUtils.writeContentAsLatin1(path, "bar")
        val inputs: SortedMap<PathFragment?, Path?> =
            com.google.common.collect.ImmutableSortedMap.of(PathFragment.create("foo"), path)
        val merkleTree: @NotNull Uploadable = merkleTreeComputer.buildForFiles(inputs)

        val e: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable {
                    remoteCache.ensureInputsPresent(
                        remoteActionExecutionContext,
                        merkleTree,
                        com.google.common.collect.ImmutableMap.of<K?, V?>(),
                        false,  /* remotePathResolver= */
                        null
                    )
                })

        Truth.assertThat(e).hasMessageThat().contains("upload failed")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun shutdownNow_cancelInProgressUploads() {
        val remoteCacheClient: RemoteCacheClient = Mockito.spy<InMemoryCacheClient>(InMemoryCacheClient())
        // Return a future that never completes
        Mockito.doAnswer(Answer { invocationOnMock: InvocationOnMock? -> com.google.common.util.concurrent.SettableFuture.create<Any?>() })
            .`when`<Any?>(remoteCacheClient)
            .uploadBlobImpl(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
        val combinedCache: CombinedCache = newCombinedCache(remoteCacheClient)
        val digest: Digest = fakeFileCache.createScratchInput(ActionInputHelper.fromPath("file"), "content")
        val file: Path? = execRoot.getRelative("file")

        val upload: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
            combinedCache.uploadFile(remoteActionExecutionContext, digest, file)
        com.google.common.truth.Subject.contains(digest)
        combinedCache.shutdownNow()

        Truth.assertThat(upload.isCancelled()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun uploadFile_chunkedUpload_deduplicatesRemoteUpload() {
        // Spy on a real GrpcCacheClient so that final methods on the RemoteCacheClient base class
        // (e.g. dedupUpload, uploadFile) execute their real implementations against a properly
        // initialized casUploadCache.
        val grpcCacheClient: GrpcCacheClient =
            spy(
                GrpcCacheClient(
                    < T > mock < T ? > (ReferenceCountedChannel::class.java),
                < T > mock < T ? > (CallCredentialsProvider::class.java),
        com.google.devtools.common.options.Options.getDefaults<O?>(RemoteOptions::class.java),
        <T > mock<T?>(RemoteRetrier::class.java),
        digestUtil))
        Mockito.doAnswer(Answer { unused: InvocationOnMock? -> chunkingCapabilities() }).`when`<Any?>(grpcCacheClient)
            .getServerCapabilities()
        Mockito.doAnswer(Answer { unused: InvocationOnMock? ->
            com.google.common.util.concurrent.Futures.immediateFuture<com.google.common.collect.ImmutableSet<Any?>?>(
                com.google.common.collect.ImmutableSet.of<Any?>()
            )
        })
            .`when`<Any?>(grpcCacheClient)
            .findMissingDigests(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

        val spliceStarted: CountDownLatch = CountDownLatch(1)
        val spliceFuture: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
        Mockito.doAnswer(
            Answer { unused: InvocationOnMock? ->
                spliceStarted.countDown()
                spliceFuture
            })
            .`when`<Any?>(grpcCacheClient)
            .spliceBlob(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

        val combinedCache: CombinedCache =
            CombinedCache(
                grpcCacheClient,  /* diskCacheClient= */
                null,  /* symlinkTemplate= */
                null,
                digestUtil,  /* chunkingEnabled= */
                true
            )
        val data = ByteArray(8192)
        val file: Path = execRoot.getRelative("chunked-output")
        file.getOutputStream().use { out ->
            out.write(data)
        }
        val digest: Digest? = digestUtil.compute(data)

        try {
            val firstUpload: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>? =
                combinedCache.uploadFile(remoteActionExecutionContext, digest, file)
            Truth.assertThat(spliceStarted.await(1, TimeUnit.SECONDS)).isTrue()

            val secondUpload: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>? =
                combinedCache.uploadFile(remoteActionExecutionContext, digest, file)

            assertThat(grpcCacheClient.getUploadSubscriberCount(digest)).isEqualTo(2)
            Mockito.verify<Any?>(grpcCacheClient)
                .findMissingDigests(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
            Mockito.verify<Any?>(grpcCacheClient)
                .spliceBlob(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

            spliceFuture.set(null)
            getFromFuture(firstUpload)
            getFromFuture(secondUpload)
        } finally {
            combinedCache.release()
        }
    }

    private fun newCombinedCache(): InMemoryCombinedCache {
        return InMemoryCombinedCache(digestUtil)
    }

    private fun newCombinedCache(
        casEntries: MutableMap<Digest?, ByteArray?>?, digestUtil: DigestUtil?, symlinkTemplate: String?
    ): InMemoryCombinedCache {
        return InMemoryCombinedCache(casEntries, digestUtil, symlinkTemplate)
    }

    private fun newCombinedCache(remoteCacheClient: RemoteCacheClient?): CombinedCache {
        return CombinedCache(
            remoteCacheClient,  /* diskCacheClient= */
            null,  /* symlinkTemplate= */
            null,
            digestUtil,  /* chunkingEnabled= */
            false
        )
    }

    private fun newRemoteExecutionCache(remoteCacheClient: RemoteCacheClient?): RemoteExecutionCache {
        return RemoteExecutionCache(
            remoteCacheClient,  /* diskCacheClient= */
            null,  /* symlinkTemplate= */
            null,
            digestUtil,  /* chunkingEnabled= */
            false
        )
    }

    companion object {
        private fun chunkingCapabilities(): ServerCapabilities {
            return ServerCapabilities.newBuilder()
                .setCacheCapabilities(
                    CacheCapabilities.newBuilder()
                        .setFastCdc2020Params(
                            FastCdc2020Params.newBuilder().setAvgChunkSizeBytes(1024).build()
                        )
                )
                .build()
        }
    }
}
