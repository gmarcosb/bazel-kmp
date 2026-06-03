// Copyright 2017 The Bazel Authors. All rights reserved.
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

import build.bazel.remote.execution.v2.ActionResult

/** Tests for [RemoteSpawnCache].  */
@RunWith(JUnit4::class)
class RemoteSpawnCacheTest {
    private var fs: FileSystem? = null
    private var digestUtil: DigestUtil? = null
    private var execRoot: Path? = null
    private var tempPathGenerator: TempPathGenerator? = null
    private var simpleSpawn: SimpleSpawn? = null
    private var simplePolicy: SpawnExecutionContext? = null
    private var successfulResult: ActionResult? = null

    @org.mockito.Mock
    private val combinedCache: CombinedCache? = null
    private var outErr: FileOutErr? = null

    private var eventHandler: StoredEventHandler = StoredEventHandler()

    private var reporter: com.google.devtools.build.lib.events.Reporter? = null
    private var remotePathResolver: RemotePathResolver? = null

    private fun createSuccessfulResult(spawn: Spawn): ActionResult {
        val result: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ActionResult.newBuilder().setExitCode(0)
        for (output in spawn.getOutputFiles()) {
            if (spawn.isMandatoryOutput(output)) {
                result.addOutputFiles(
                    OutputFile.newBuilder()
                        .setPath(spawn.getPathMapper().getMappedExecPathString(output))
                        .setDigest(digestUtil.computeAsUtf8("content"))
                )
            }
        }
        return result.build()
    }

    private fun createRemoteSpawnCache(): RemoteSpawnCache {
        return remoteSpawnCacheWithOptions(com.google.devtools.common.options.Options.getDefaults<O?>(RemoteOptions::class.java))
    }

    private fun remoteSpawnCacheWithOptions(options: RemoteOptions?): RemoteSpawnCache {
        return remoteSpawnCacheWithOptions(
            options,
            com.google.devtools.common.options.Options.getDefaults<O?>(ExecutionOptions::class.java)
        )
    }

    private fun remoteSpawnCacheWithOptions(
        options: RemoteOptions?, executionOptions: ExecutionOptions?
    ): RemoteSpawnCache {
        val service: RemoteExecutionService? =
            spy(
                RemoteExecutionService(
                    reporter,  /* verboseFailures= */
                    true,
                    execRoot,
                    remotePathResolver,
                    BUILD_REQUEST_ID,
                    COMMAND_ID,
                    TestConstants.WORKSPACE_NAME,
                    digestUtil,
                    options,
                    executionOptions,
                    combinedCache,
                    null,
                    tempPathGenerator,  /* captureCorruptedOutputsDir= */
                    null,
                    DUMMY_REMOTE_OUTPUT_CHECKER,
                    < T > mock < T ? > (OutputService::class.java),
                com.google.common.collect.Sets.newConcurrentHashSet<E?>()
            ))
        return RemoteSpawnCache(options,  /* verboseFailures= */true, service, digestUtil)
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        fs = InMemoryFileSystem(com.google.devtools.build.lib.clock.JavaClock(), DigestHashFunction.SHA256)
        digestUtil = DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256)
        execRoot = fs.getPath("/exec/root")
        execRoot.createDirectoryAndParents()
        tempPathGenerator = TempPathGenerator(fs.getPath("/execroot/_tmp/actions/remote"))
        val fakeFileCache: com.google.devtools.build.lib.remote.FakeActionInputFileCache =
            com.google.devtools.build.lib.remote.FakeActionInputFileCache(execRoot)
        simpleSpawn = simpleSpawnWithExecutionInfo(com.google.common.collect.ImmutableMap.of<String?, String?>())
        successfulResult = createSuccessfulResult(simpleSpawn)

        val stdout: Path = fs.getPath("/tmp/stdout")
        val stderr: Path = fs.getPath("/tmp/stderr")
        stdout.getParentDirectory().createDirectoryAndParents()
        stderr.getParentDirectory().createDirectoryAndParents()
        outErr = FileOutErr(stdout, stderr)
        reporter = com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus())
        eventHandler = StoredEventHandler()
        reporter.addHandler(eventHandler)

        remotePathResolver = RemotePathResolver.createDefault(execRoot)
        simplePolicy = createSpawnExecutionContext(simpleSpawn, execRoot, fakeFileCache, outErr)

        fakeFileCache.createScratchInput(simpleSpawn.getInputFiles().getSingleton(), "xyz")

        Mockito.`when`<T?>(combinedCache.hasRemoteCache()).thenReturn(true)
        Mockito.`when`<T?>(combinedCache.remoteActionCacheSupportsUpdate()).thenReturn(true)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cacheHit() {
        // arrange
        val cache: RemoteSpawnCache = createRemoteSpawnCache()
        val service: RemoteExecutionService? = cache.getRemoteExecutionService()
        val actionKeyCaptor: ArgumentCaptor<ActionKey?> =
            ArgumentCaptor.forClass<ActionKey?, ActionKey?>(ActionKey::class.java)
        Mockito.`when`<T?>(
            combinedCache.downloadActionResult(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                actionKeyCaptor.capture(),  /* inlineOutErr= */
                ArgumentMatchers.eq(false),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<Any?>())
        ))
        .thenAnswer(
            object : Answer<CachedActionResult?>() {
                override fun answer(invocation: InvocationOnMock): CachedActionResult {
                    val context: RemoteActionExecutionContext = invocation.getArgument<RemoteActionExecutionContext>(0)
                    val meta: RequestMetadata = context.getRequestMetadata()
                    assertThat(meta.getCorrelatedInvocationsId()).isEqualTo(BUILD_REQUEST_ID)
                    assertThat(meta.getToolInvocationId()).isEqualTo(COMMAND_ID)
                    return CachedActionResult.remote(successfulResult)
                }
            })
        Mockito.doAnswer(
            Answer { invocation: InvocationOnMock? ->
                val action: RemoteAction = invocation.getArgument<RemoteAction>(0)
                val context: RemoteActionExecutionContext = action.getRemoteActionExecutionContext()
                val meta: RequestMetadata = context.getRequestMetadata()
                assertThat(meta.getCorrelatedInvocationsId()).isEqualTo(BUILD_REQUEST_ID)
                assertThat(meta.getToolInvocationId()).isEqualTo(COMMAND_ID)
                null
            } as Answer<java.lang.Void?>)
            .`when`<Any?>(service)
            .downloadOutputs(
                ArgumentMatchers.any<T?>(),
                eq(RemoteActionResult.createFromCache(CachedActionResult.remote(successfulResult)))
            )

        // act
        val entry: CacheHandle = cache.lookup(simpleSpawn, simplePolicy)
        assertThat(entry.hasResult()).isTrue()
        val result: SpawnResult = entry.result

        // assert
        // All other methods on RemoteActionCache have side effects, so we verify all of them.
        assertThat(simplePolicy.digest)
            .isEqualTo(digestUtil.asSpawnLogProto(actionKeyCaptor.getValue()))
        Mockito.verify<Any?>(service)
            .downloadOutputs(
                ArgumentMatchers.any<T?>(),
                eq(RemoteActionResult.createFromCache(CachedActionResult.remote(successfulResult)))
            )
        Mockito.verify<Any?>(service, Mockito.never()).uploadOutputs(
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>()
        )
        assertThat(result.getDigest())
            .isEqualTo(digestUtil.asSpawnLogProto(actionKeyCaptor.getValue()))
        assertThat(result.setupSuccess()).isTrue()
        assertThat(result.exitCode()).isEqualTo(0)
        assertThat(result.isCacheHit()).isTrue()
        // We expect the CachedLocalSpawnRunner to _not_ write to outErr at all.
        assertThat(outErr.hasRecordedOutput()).isFalse()
        assertThat(outErr.hasRecordedStderr()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cacheMiss() {
        val cache: RemoteSpawnCache = createRemoteSpawnCache()
        val service: RemoteExecutionService? = cache.getRemoteExecutionService()
        val actionKeyCaptor: ArgumentCaptor<ActionKey?> =
            ArgumentCaptor.forClass<ActionKey?, ActionKey?>(ActionKey::class.java)
        Mockito.`when`<T?>(
            combinedCache.downloadActionResult(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                actionKeyCaptor.capture(),
                ArgumentMatchers.anyBoolean(),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<Any?>())
        ))
        .thenReturn(null)

        val entry: CacheHandle = cache.lookup(simpleSpawn, simplePolicy)

        assertThat(simplePolicy.digest)
            .isEqualTo(digestUtil.asSpawnLogProto(actionKeyCaptor.getValue()))
        assertThat(entry.hasResult()).isFalse()
        val result: SpawnResult? =
            Builder()
                .setExitCode(0)
                .setStatus(Status.SUCCESS)
                .setRunnerName("test")
                .build()
        Mockito.doNothing().`when`<Any?>(service).uploadOutputs(
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>()
        )
        entry.store(result)
        Mockito.verify<Any?>(service).uploadOutputs(
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noCacheSpawns() {
        // Checks that spawns satisfying Spawns.mayBeCached=false are not looked up in the cache
        // (even if it is a local cache) and that the results/artifacts are not uploaded to the cache.

        val withLocalCache: RemoteOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        withLocalCache.diskCache = PathFragment.create("/etc/something/cache/here")
        for (remoteOptions in com.google.common.collect.ImmutableList.of<E>(
            com.google.devtools.common.options.Options.getDefaults<O?>(
                RemoteOptions::class.java
            ), withLocalCache
        )) {
            var diskCacheClient: DiskCacheClient? = null
            var remoteCacheClient: RemoteCacheClient? = null
            if (remoteOptions === withLocalCache) {
                diskCacheClient = Mockito.mock<DiskCacheClient?>(DiskCacheClient::class.java)
            } else {
                remoteCacheClient = Mockito.mock<RemoteCacheClient?>(RemoteCacheClient::class.java)
            }

            val remoteSpawnCache: RemoteSpawnCache = remoteSpawnCacheWithOptions(remoteOptions)
            for (requirement in com.google.common.collect.ImmutableList.of<Any?>(
                ExecutionRequirements.NO_CACHE,
                ExecutionRequirements.LOCAL
            )) {
                val uncacheableSpawn: SimpleSpawn =
                    simpleSpawnWithExecutionInfo(
                        com.google.common.collect.ImmutableMap.of<String?, String?>(
                            requirement,
                            ""
                        )
                    )
                val entry: CacheHandle = remoteSpawnCache.lookup(uncacheableSpawn, simplePolicy)
                Mockito.verify<T?>(remoteSpawnCache.getRemoteExecutionService(), Mockito.never())
                    .lookupCache(ArgumentMatchers.any<T?>())
                assertThat(simplePolicy.digest).isNull()
                assertThat(entry.hasResult()).isFalse()
                val result: SpawnResult? =
                    Builder()
                        .setExitCode(0)
                        .setStatus(Status.SUCCESS)
                        .setRunnerName("test")
                        .build()
                entry.store(result)
                if (remoteOptions === withLocalCache) {
                    Mockito.verifyNoMoreInteractions(diskCacheClient)
                } else {
                    Mockito.verifyNoMoreInteractions(remoteCacheClient)
                }
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noRemoteCacheSpawns_remoteCache() {
        // Checks that spawns satisfying Spawns.mayBeCachedRemotely=false are not looked up in the
        // remote cache, and that the results/artifacts are not uploaded to the remote cache.

        val remoteCacheOptions: RemoteOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        remoteCacheOptions.remoteCache = "https://somecache.com"
        val remoteCacheClient: RemoteCacheClient? = Mockito.mock<RemoteCacheClient?>(RemoteCacheClient::class.java)
        val remoteSpawnCache: RemoteSpawnCache = remoteSpawnCacheWithOptions(remoteCacheOptions)
        for (requirement in com.google.common.collect.ImmutableList.of<Any?>(
            ExecutionRequirements.NO_CACHE,
            ExecutionRequirements.LOCAL,
            ExecutionRequirements.NO_REMOTE_CACHE,
            ExecutionRequirements.NO_REMOTE
        )) {
            val uncacheableSpawn: SimpleSpawn = simpleSpawnWithExecutionInfo(
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    requirement,
                    ""
                )
            )
            val entry: CacheHandle = remoteSpawnCache.lookup(uncacheableSpawn, simplePolicy)
            Mockito.verify<Any?>(combinedCache, Mockito.never())
                .downloadActionResult(
                    ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                    ArgumentMatchers.any<T?>(ActionKey::class.java),
                    ArgumentMatchers.anyBoolean(),
                    ArgumentMatchers.any<MutableSet<String?>?>()
                )
            assertThat(simplePolicy.digest).isNull()
            assertThat(entry.hasResult()).isFalse()
            val result: SpawnResult? =
                Builder()
                    .setExitCode(0)
                    .setStatus(Status.SUCCESS)
                    .setRunnerName("test")
                    .build()
            entry.store(result)
            Mockito.verifyNoMoreInteractions(remoteCacheClient)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noRemoteCacheSpawns_combinedCache() {
        // Checks that spawns satisfying Spawns.mayBeCachedRemotely=false are not looked up in the
        // remote cache, and that the results/artifacts are not uploaded to the remote cache.
        // The disk cache part of a combined cache is considered as a local cache hence spawns tagged
        // with NO_REMOTE can sill hit it.
        val remoteOptions: RemoteOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        remoteOptions.remoteCache = "https://somecache.com"
        remoteOptions.diskCache = PathFragment.create("/etc/something/cache/here")
        val remoteSpawnCache: RemoteSpawnCache = remoteSpawnCacheWithOptions(remoteOptions)
        val remoteCacheClient: RemoteCacheClient? = Mockito.mock<RemoteCacheClient?>(RemoteCacheClient::class.java)

        for (requirement in com.google.common.collect.ImmutableList.of<Any?>(
            ExecutionRequirements.NO_CACHE,
            ExecutionRequirements.LOCAL
        )) {
            val uncacheableSpawn: SimpleSpawn = simpleSpawnWithExecutionInfo(
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    requirement,
                    ""
                )
            )
            val entry: CacheHandle = remoteSpawnCache.lookup(uncacheableSpawn, simplePolicy)
            Mockito.verify<Any?>(combinedCache, Mockito.never())
                .downloadActionResult(
                    ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                    ArgumentMatchers.any<T?>(ActionKey::class.java),  /* inlineOutErr= */
                    ArgumentMatchers.eq(false),  /* inlineOutputFiles= */
                    < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<E?>()))
            assertThat(simplePolicy.digest).isNull()
            assertThat(entry.hasResult()).isFalse()
            val result: SpawnResult? =
                Builder()
                    .setExitCode(0)
                    .setStatus(Status.SUCCESS)
                    .setRunnerName("test")
                    .build()
            entry.store(result)
            Mockito.verifyNoMoreInteractions(remoteCacheClient)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noRemoteCacheStillUsesLocalCache() {
        val remoteOptions: RemoteOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        remoteOptions.diskCache = PathFragment.create("/etc/something/cache/here")
        Mockito.`when`<T?>(combinedCache.hasRemoteCache()).thenReturn(false)
        Mockito.`when`<T?>(combinedCache.hasDiskCache()).thenReturn(true)
        val cache: RemoteSpawnCache = remoteSpawnCacheWithOptions(remoteOptions)
        val actionKeyCaptor: ArgumentCaptor<ActionKey?> =
            ArgumentCaptor.forClass<ActionKey?, ActionKey?>(ActionKey::class.java)
        Mockito.`when`<T?>(
            combinedCache.downloadActionResult(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                actionKeyCaptor.capture(),
                ArgumentMatchers.anyBoolean(),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<Any?>())
        ))
        .thenReturn(null)
        val cacheableSpawn: SimpleSpawn =
            simpleSpawnWithExecutionInfo(
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    ExecutionRequirements.NO_REMOTE_CACHE,
                    ""
                )
            )

        cache.lookup(cacheableSpawn, simplePolicy)

        assertThat(simplePolicy.digest)
            .isEqualTo(digestUtil.asSpawnLogProto(actionKeyCaptor.getValue()))
        Mockito.verify<Any?>(combinedCache)
            .downloadActionResult(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ActionKey::class.java),  /* inlineOutErr= */
                ArgumentMatchers.eq(false),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<E?>()))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noRemoteExecStillUsesCache() {
        val cache: RemoteSpawnCache = createRemoteSpawnCache()
        val cacheableSpawn: SimpleSpawn =
            simpleSpawnWithExecutionInfo(
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    ExecutionRequirements.NO_REMOTE_EXEC,
                    ""
                )
            )
        val actionKeyCaptor: ArgumentCaptor<ActionKey?> =
            ArgumentCaptor.forClass<ActionKey?, ActionKey?>(ActionKey::class.java)
        Mockito.`when`<T?>(
            combinedCache.downloadActionResult(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                actionKeyCaptor.capture(),
                ArgumentMatchers.anyBoolean(),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<Any?>())
        ))
        .thenReturn(null)

        cache.lookup(cacheableSpawn, simplePolicy)

        assertThat(simplePolicy.digest)
            .isEqualTo(digestUtil.asSpawnLogProto(actionKeyCaptor.getValue()))
        Mockito.verify<Any?>(combinedCache)
            .downloadActionResult(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ActionKey::class.java),  /* inlineOutErr= */
                ArgumentMatchers.eq(false),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<E?>()))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun failedActionsAreNotUploaded() {
        // Only successful action results are uploaded to the remote cache.
        val cache: RemoteSpawnCache = createRemoteSpawnCache()
        val service: RemoteExecutionService? = cache.getRemoteExecutionService()
        val entry: CacheHandle = cache.lookup(simpleSpawn, simplePolicy)
        Mockito.verify<Any?>(combinedCache)
            .downloadActionResult(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ActionKey::class.java),  /* inlineOutErr= */
                ArgumentMatchers.eq(false),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<E?>()))
        assertThat(entry.hasResult()).isFalse()
        val result: SpawnResult? =
            Builder()
                .setExitCode(1)
                .setStatus(Status.NON_ZERO_EXIT)
                .setFailureDetail(
                    FailureDetail.newBuilder()
                        .setSpawn(FailureDetails.Spawn.newBuilder().setCode(Code.NON_ZERO_EXIT))
                        .build()
                )
                .setRunnerName("test")
                .build()
        entry.store(result)
        Mockito.verify<Any?>(service, Mockito.never()).uploadOutputs(
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun printWarningIfDownloadFails() {
        val cache: RemoteSpawnCache = createRemoteSpawnCache()
        val service: RemoteExecutionService? = cache.getRemoteExecutionService()
        Mockito.doThrow(IOException(io.grpc.Status.UNAVAILABLE.asRuntimeException()))
            .`when`<Any?>(combinedCache)
            .downloadActionResult(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ActionKey::class.java),  /* inlineOutErr= */
                ArgumentMatchers.eq(false),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<E?>()))

        val entry: CacheHandle = cache.lookup(simpleSpawn, simplePolicy)
        assertThat(entry.hasResult()).isFalse()
        val result: SpawnResult? =
            Builder()
                .setExitCode(0)
                .setStatus(Status.SUCCESS)
                .setRunnerName("test")
                .build()

        Mockito.doNothing().`when`<Any?>(service).uploadOutputs(
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>()
        )
        entry.store(result)
        Mockito.verify<Any?>(service)
            .uploadOutputs(ArgumentMatchers.any<T?>(), < T > eq < T ? > (result), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

        Truth.assertThat(eventHandler.getEvents()).hasSize(1)
        val evt: com.google.devtools.build.lib.events.Event = eventHandler.getEvents().get(0)
        Truth.assertThat<com.google.devtools.build.lib.events.EventKind?>(evt.getKind())
            .isEqualTo(com.google.devtools.build.lib.events.EventKind.WARNING)
        Truth.assertThat(evt.getMessage()).contains("UNAVAILABLE")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun orphanedCachedResultIgnored() {
        val cache: RemoteSpawnCache = createRemoteSpawnCache()
        val service: RemoteExecutionService? = cache.getRemoteExecutionService()
        val digest: Digest? = digestUtil.computeAsUtf8("bla")
        val actionResult: ActionResult? =
            ActionResult.newBuilder()
                .addOutputFiles(OutputFile.newBuilder().setPath("/random/file").setDigest(digest))
                .build()
        Mockito.`when`<T?>(
            combinedCache.downloadActionResult(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ActionKey::class.java),  /* inlineOutErr= */
                ArgumentMatchers.eq(false),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<Any?>())
        ))
        .thenAnswer(
            object : Answer<CachedActionResult?>() {
                override fun answer(invocation: InvocationOnMock): CachedActionResult {
                    val context: RemoteActionExecutionContext = invocation.getArgument<RemoteActionExecutionContext>(0)
                    val meta: RequestMetadata = context.getRequestMetadata()
                    assertThat(meta.getCorrelatedInvocationsId()).isEqualTo(BUILD_REQUEST_ID)
                    assertThat(meta.getToolInvocationId()).isEqualTo(COMMAND_ID)
                    return CachedActionResult.remote(actionResult)
                }
            })
        doThrow(CacheNotFoundException(digest))
            .`when`<Any?>(service)
            .downloadOutputs(
                ArgumentMatchers.any<T?>(),
                eq(RemoteActionResult.createFromCache(CachedActionResult.remote(actionResult)))
            )

        val entry: CacheHandle = cache.lookup(simpleSpawn, simplePolicy)
        assertThat(entry.hasResult()).isFalse()
        val result: SpawnResult? =
            Builder()
                .setExitCode(0)
                .setStatus(Status.SUCCESS)
                .setRunnerName("test")
                .build()

        Mockito.doNothing().`when`<Any?>(service).uploadOutputs(
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>()
        )
        entry.store(result)
        Mockito.verify<Any?>(service)
            .uploadOutputs(ArgumentMatchers.any<T?>(), < T > eq < T ? > (result), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
        Truth.assertThat(eventHandler.getEvents()).isEmpty() // no warning is printed.
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun failedCacheActionAsCacheMiss() {
        val cache: RemoteSpawnCache = createRemoteSpawnCache()
        val actionResult: ActionResult? = ActionResult.newBuilder().setExitCode(1).build()
        Mockito.`when`<T?>(
            combinedCache.downloadActionResult(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ActionKey::class.java),  /* inlineOutErr= */
                ArgumentMatchers.eq(false),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<Any?>())
        ))
        .thenReturn(CachedActionResult.remote(actionResult))

        val entry: CacheHandle = cache.lookup(simpleSpawn, simplePolicy)

        assertThat(entry.hasResult()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDownloadMinimal() {
        // arrange
        val remoteOptions: RemoteOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        remoteOptions.remoteOutputsMode = RemoteOutputsMode.MINIMAL
        val cache: RemoteSpawnCache = remoteSpawnCacheWithOptions(remoteOptions)

        Mockito.`when`<T?>(
            combinedCache.downloadActionResult(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(),  /* inlineOutErr= */
                ArgumentMatchers.eq(false),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<Any?>())
        ))
        .thenReturn(CachedActionResult.remote(successfulResult))
        Mockito.doReturn(null).`when`<T?>(cache.getRemoteExecutionService())
            .downloadOutputs(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

        // act
        val cacheHandle: CacheHandle = cache.lookup(simpleSpawn, simplePolicy)

        // assert
        assertThat(cacheHandle.hasResult()).isTrue()
        assertThat(cacheHandle.result.exitCode()).isEqualTo(0)
        Mockito.verify<T?>(cache.getRemoteExecutionService())
            .downloadOutputs(
                ArgumentMatchers.any<T?>(),
                eq(RemoteActionResult.createFromCache(CachedActionResult.remote(successfulResult)))
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDownloadMinimalIoError() {
        // arrange
        val remoteOptions: RemoteOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        remoteOptions.remoteOutputsMode = RemoteOutputsMode.MINIMAL
        val cache: RemoteSpawnCache = remoteSpawnCacheWithOptions(remoteOptions)

        val downloadFailure: IOException = IOException("downloadMinimal failed")

        Mockito.`when`<T?>(
            combinedCache.downloadActionResult(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(),  /* inlineOutErr= */
                ArgumentMatchers.eq(false),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<Any?>())
        ))
        .thenReturn(CachedActionResult.remote(successfulResult))
        Mockito.doThrow(downloadFailure)
            .`when`<T?>(cache.getRemoteExecutionService())
            .downloadOutputs(
                ArgumentMatchers.any<T?>(),
                eq(RemoteActionResult.createFromCache(CachedActionResult.remote(successfulResult)))
            )

        // act
        val cacheHandle: CacheHandle = cache.lookup(simpleSpawn, simplePolicy)

        // assert
        assertThat(cacheHandle.hasResult()).isFalse()
        Mockito.verify<T?>(cache.getRemoteExecutionService())
            .downloadOutputs(
                ArgumentMatchers.any<T?>(),
                eq(RemoteActionResult.createFromCache(CachedActionResult.remote(successfulResult)))
            )
        Truth.assertThat(eventHandler.getEvents().size()).isEqualTo(1)
        val evt: com.google.devtools.build.lib.events.Event = eventHandler.getEvents().get(0)
        Truth.assertThat<com.google.devtools.build.lib.events.EventKind?>(evt.getKind())
            .isEqualTo(com.google.devtools.build.lib.events.EventKind.WARNING)
        Truth.assertThat(evt.getMessage()).contains(downloadFailure.getMessage())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun pathMappedActionIsDeduplicated() {
        // arrange
        val cache: RemoteSpawnCache = createRemoteSpawnCache()

        val firstSpawn: SimpleSpawn = simplePathMappedSpawn("k8-fastbuild")
        val firstFakeFileCache: com.google.devtools.build.lib.remote.FakeActionInputFileCache =
            com.google.devtools.build.lib.remote.FakeActionInputFileCache(execRoot)
        firstFakeFileCache.createScratchInput(firstSpawn.getInputFiles().getSingleton(), "xyz")
        val firstPolicy: SpawnExecutionContext =
            createSpawnExecutionContext(firstSpawn, execRoot, firstFakeFileCache, outErr)

        val secondSpawn: SimpleSpawn = simplePathMappedSpawn("k8-opt")
        val secondFakeFileCache: com.google.devtools.build.lib.remote.FakeActionInputFileCache =
            com.google.devtools.build.lib.remote.FakeActionInputFileCache(execRoot)
        secondFakeFileCache.createScratchInput(secondSpawn.getInputFiles().getSingleton(), "xyz")
        val secondPolicy: SpawnExecutionContext =
            createSpawnExecutionContext(secondSpawn, execRoot, secondFakeFileCache, outErr)

        val remoteExecutionService: RemoteExecutionService? = cache.getRemoteExecutionService()
        Mockito.doCallRealMethod().`when`<Any?>(remoteExecutionService)
            .waitForAndReuseOutputs(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
        // Simulate a very slow upload to the remote cache to ensure that the second spawn is
        // deduplicated rather than a cache hit. This is a slight hack, but also avoid introducing
        // concurrency to this test.
        val onUploadComplete: AtomicReference<java.lang.Runnable?> = AtomicReference<java.lang.Runnable?>()
        Mockito.doAnswer(
            Answer { invocationOnMock: InvocationOnMock? ->
                onUploadComplete.set(invocationOnMock.getArgument<java.lang.Runnable?>(2))
                null
            })
            .`when`<Any?>(remoteExecutionService)
            .uploadOutputs(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )

        cache.lookup(firstSpawn, firstPolicy).use { firstCacheHandle ->
            FileSystemUtils.writeContent(
                fs.getPath("/exec/root/bazel-bin/k8-fastbuild/bin/output"),
                java.nio.charset.StandardCharsets.UTF_8,
                "hello"
            )
            firstCacheHandle.store(
                Builder()
                    .setExitCode(0)
                    .setStatus(Status.SUCCESS)
                    .setRunnerName("test")
                    .build()
            )
        }
        val secondCacheHandle: CacheHandle = cache.lookup(secondSpawn, secondPolicy)

        // assert
        assertThat(secondCacheHandle.hasResult()).isTrue()
        assertThat(secondCacheHandle.result.getRunnerName()).isEqualTo("deduplicated")
        assertThat(
            FileSystemUtils.readContent(
                fs.getPath("/exec/root/bazel-bin/k8-opt/bin/output"), java.nio.charset.StandardCharsets.UTF_8
            )
        )
            .isEqualTo("hello")
        assertThat(secondCacheHandle.willStore()).isFalse()
        onUploadComplete.get().run()
        assertThat(cache.getInFlightExecutionsSize()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun pathMappedActionIsDeduplicatedWithSpawnOutputModification() {
        // arrange
        val cache: RemoteSpawnCache = createRemoteSpawnCache()

        val firstExecutionOwner: ActionExecutionMetadata =
            object : FakeOwner("Mnemonic", "Progress Message", "//dummy:label") {
                public override fun mayModifySpawnOutputsAfterExecution(): Boolean {
                    return true
                }
            }
        val firstSpawn: SimpleSpawn = simplePathMappedSpawn("k8-fastbuild", firstExecutionOwner)
        val firstFakeFileCache: com.google.devtools.build.lib.remote.FakeActionInputFileCache =
            com.google.devtools.build.lib.remote.FakeActionInputFileCache(execRoot)
        firstFakeFileCache.createScratchInput(firstSpawn.getInputFiles().getSingleton(), "xyz")
        val firstPolicy: SpawnExecutionContext =
            createSpawnExecutionContext(firstSpawn, execRoot, firstFakeFileCache, outErr)

        val secondSpawn: SimpleSpawn = simplePathMappedSpawn("k8-opt")
        val secondFakeFileCache: com.google.devtools.build.lib.remote.FakeActionInputFileCache =
            com.google.devtools.build.lib.remote.FakeActionInputFileCache(execRoot)
        secondFakeFileCache.createScratchInput(secondSpawn.getInputFiles().getSingleton(), "xyz")
        val secondPolicy: SpawnExecutionContext =
            createSpawnExecutionContext(secondSpawn, execRoot, secondFakeFileCache, outErr)

        val remoteExecutionService: RemoteExecutionService? = cache.getRemoteExecutionService()
        val enteredWaitForAndReuseOutputs: CountDownLatch = CountDownLatch(1)
        val completeWaitForAndReuseOutputs: CountDownLatch = CountDownLatch(1)
        val enteredUploadOutputs: CountDownLatch = CountDownLatch(1)
        val spawnsThatWaitedForOutputReuse: MutableSet<Spawn> = ConcurrentHashMap.newKeySet<Spawn?>()
        Mockito.doAnswer(
            Answer { invocation: InvocationOnMock? ->
                spawnsThatWaitedForOutputReuse.add(
                    (invocation.getArgument<Any?>(0) as RemoteAction).getSpawn()
                )
                enteredWaitForAndReuseOutputs.countDown()
                completeWaitForAndReuseOutputs.await()
                invocation.callRealMethod() as SpawnResult?
            } as Answer<SpawnResult?>)
            .`when`<Any?>(remoteExecutionService)
            .waitForAndReuseOutputs(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
        // Simulate a very slow upload to the remote cache to ensure that the second spawn is
        // deduplicated rather than a cache hit. This is a slight hack, but also avoids introducing
        // more concurrency to this test.
        val onUploadComplete: AtomicReference<java.lang.Runnable?> = AtomicReference<java.lang.Runnable?>()
        Mockito.doAnswer(
            Answer { invocation: InvocationOnMock? ->
                enteredUploadOutputs.countDown()
                onUploadComplete.set(invocation.getArgument<java.lang.Runnable?>(2))
                null
            } as Answer<java.lang.Void?>)
            .`when`<Any?>(remoteExecutionService)
            .uploadOutputs(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )

        // act
        // Simulate the first spawn writing to the output, but delay its completion.
        val firstCacheHandle: CacheHandle = cache.lookup(firstSpawn, firstPolicy)
        FileSystemUtils.writeContent(
            fs.getPath("/exec/root/bazel-bin/k8-fastbuild/bin/output"), java.nio.charset.StandardCharsets.UTF_8, "hello"
        )

        // Start the second spawn and wait for it to deduplicate against the first one.
        val secondCacheHandleRef: AtomicReference<CacheHandle> = AtomicReference<CacheHandle>()
        val lookupSecondSpawn: java.lang.Thread =
            java.lang.Thread(
                java.lang.Runnable {
                    try {
                        secondCacheHandleRef.set(cache.lookup(secondSpawn, secondPolicy))
                    } catch (e: java.lang.InterruptedException) {
                        throw java.lang.IllegalStateException(e)
                    } catch (e: IOException) {
                        throw java.lang.IllegalStateException(e)
                    } catch (e: ExecException) {
                        throw java.lang.IllegalStateException(e)
                    }
                })
        lookupSecondSpawn.start()
        enteredWaitForAndReuseOutputs.await()

        // Complete the first spawn and immediately corrupt its outputs.
        val completeFirstSpawn: java.lang.Thread =
            java.lang.Thread(
                java.lang.Runnable {
                    try {
                        firstCacheHandle.store(
                            Builder()
                                .setExitCode(0)
                                .setStatus(Status.SUCCESS)
                                .setRunnerName("test")
                                .build()
                        )
                        FileSystemUtils.writeContent(
                            fs.getPath("/exec/root/bazel-bin/k8-fastbuild/bin/output"),
                            java.nio.charset.StandardCharsets.UTF_8,
                            "corrupted"
                        )
                    } catch (e: IOException) {
                        throw java.lang.IllegalStateException(e)
                    } catch (e: ExecException) {
                        throw java.lang.IllegalStateException(e)
                    } catch (e: java.lang.InterruptedException) {
                        throw java.lang.IllegalStateException(e)
                    }
                })
        completeFirstSpawn.start()
        // Make it more likely to detect races by waiting for the first spawn to (fake) upload its
        // outputs.
        enteredUploadOutputs.await()

        // Let the second spawn complete its output reuse.
        completeWaitForAndReuseOutputs.countDown()
        lookupSecondSpawn.join()
        val secondCacheHandle: CacheHandle = secondCacheHandleRef.get()

        completeFirstSpawn.join()

        // assert
        Truth.assertThat(spawnsThatWaitedForOutputReuse).containsExactly(secondSpawn)
        assertThat(secondCacheHandle.hasResult()).isTrue()
        assertThat(secondCacheHandle.result.getRunnerName()).isEqualTo("deduplicated")
        assertThat(
            FileSystemUtils.readContent(
                fs.getPath("/exec/root/bazel-bin/k8-opt/bin/output"), java.nio.charset.StandardCharsets.UTF_8
            )
        )
            .isEqualTo("hello")
        assertThat(secondCacheHandle.willStore()).isFalse()
        onUploadComplete.get().run()
        assertThat(cache.getInFlightExecutionsSize()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun pathMappedActionWithInMemoryOutputIsDeduplicated() {
        // arrange
        val cache: RemoteSpawnCache = createRemoteSpawnCache()

        val firstSpawn: SimpleSpawn = simplePathMappedSpawn("k8-fastbuild")
        val firstFakeFileCache: com.google.devtools.build.lib.remote.FakeActionInputFileCache =
            com.google.devtools.build.lib.remote.FakeActionInputFileCache(execRoot)
        firstFakeFileCache.createScratchInput(firstSpawn.getInputFiles().getSingleton(), "xyz")
        val firstPolicy: SpawnExecutionContext =
            createSpawnExecutionContext(firstSpawn, execRoot, firstFakeFileCache, outErr)

        val secondSpawn: SimpleSpawn = simplePathMappedSpawn("k8-opt")
        val secondFakeFileCache: com.google.devtools.build.lib.remote.FakeActionInputFileCache =
            com.google.devtools.build.lib.remote.FakeActionInputFileCache(execRoot)
        secondFakeFileCache.createScratchInput(secondSpawn.getInputFiles().getSingleton(), "xyz")
        val secondPolicy: SpawnExecutionContext =
            createSpawnExecutionContext(secondSpawn, execRoot, secondFakeFileCache, outErr)

        val remoteExecutionService: RemoteExecutionService? = cache.getRemoteExecutionService()
        Mockito.doCallRealMethod().`when`<Any?>(remoteExecutionService)
            .waitForAndReuseOutputs(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
        // Simulate a very slow upload to the remote cache to ensure that the second spawn is
        // deduplicated rather than a cache hit. This is a slight hack, but also avoid introducing
        // concurrency to this test.
        val onUploadComplete: AtomicReference<java.lang.Runnable?> = AtomicReference<java.lang.Runnable?>()
        Mockito.doAnswer(
            Answer { invocationOnMock: InvocationOnMock? ->
                onUploadComplete.set(invocationOnMock.getArgument<java.lang.Runnable?>(2))
                null
            })
            .`when`<Any?>(remoteExecutionService)
            .uploadOutputs(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )

        cache.lookup(firstSpawn, firstPolicy).use { firstCacheHandle ->
            firstCacheHandle.store(
                Builder()
                    .setExitCode(0)
                    .setStatus(Status.SUCCESS)
                    .setRunnerName("test")
                    .setInMemoryOutput(
                        firstSpawn.getOutputFiles().getFirst(), ByteString.copyFromUtf8("in-memory")
                    )
                    .build()
            )
        }
        val secondCacheHandle: CacheHandle = cache.lookup(secondSpawn, secondPolicy)

        // assert
        val inMemoryOutput: ActionInput = secondSpawn.getOutputFiles().getFirst()
        assertThat(secondCacheHandle.hasResult()).isTrue()
        assertThat(secondCacheHandle.result.getRunnerName()).isEqualTo("deduplicated")
        assertThat(secondCacheHandle.result.getInMemoryOutput(inMemoryOutput).toStringUtf8())
            .isEqualTo("in-memory")
        assertThat(execRoot.getRelative(inMemoryOutput.getExecPath()).exists()).isFalse()
        assertThat(secondCacheHandle.willStore()).isFalse()
        onUploadComplete.get().run()
        assertThat(cache.getInFlightExecutionsSize()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun deduplicatedActionWithNonZeroExitCodeIsACacheMiss() {
        // arrange
        val cache: RemoteSpawnCache = createRemoteSpawnCache()

        val firstSpawn: SimpleSpawn = simplePathMappedSpawn("k8-fastbuild")
        val firstFakeFileCache: com.google.devtools.build.lib.remote.FakeActionInputFileCache =
            com.google.devtools.build.lib.remote.FakeActionInputFileCache(execRoot)
        firstFakeFileCache.createScratchInput(firstSpawn.getInputFiles().getSingleton(), "xyz")
        val firstPolicy: SpawnExecutionContext =
            createSpawnExecutionContext(firstSpawn, execRoot, firstFakeFileCache, outErr)

        val secondSpawn: SimpleSpawn = simplePathMappedSpawn("k8-opt")
        val secondFakeFileCache: com.google.devtools.build.lib.remote.FakeActionInputFileCache =
            com.google.devtools.build.lib.remote.FakeActionInputFileCache(execRoot)
        secondFakeFileCache.createScratchInput(secondSpawn.getInputFiles().getSingleton(), "xyz")
        val secondPolicy: SpawnExecutionContext =
            createSpawnExecutionContext(secondSpawn, execRoot, secondFakeFileCache, outErr)

        val remoteExecutionService: RemoteExecutionService? = cache.getRemoteExecutionService()
        Mockito.doCallRealMethod().`when`<Any?>(remoteExecutionService)
            .waitForAndReuseOutputs(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

        cache.lookup(firstSpawn, firstPolicy).use { firstCacheHandle ->
            FileSystemUtils.writeContent(
                fs.getPath("/exec/root/bazel-bin/k8-fastbuild/bin/output"),
                java.nio.charset.StandardCharsets.UTF_8,
                "hello"
            )
            firstCacheHandle.store(
                Builder()
                    .setExitCode(1)
                    .setStatus(Status.NON_ZERO_EXIT)
                    .setFailureDetail(
                        FailureDetail.newBuilder()
                            .setMessage("test spawn failed")
                            .setSpawn(
                                FailureDetails.Spawn.newBuilder()
                                    .setCode(FailureDetails.Spawn.Code.NON_ZERO_EXIT)
                            )
                            .build()
                    )
                    .setRunnerName("test")
                    .build()
            )
        }
        Mockito.verify<Any?>(remoteExecutionService, Mockito.never()).uploadOutputs(
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>()
        )
        val secondCacheHandle: CacheHandle = cache.lookup(secondSpawn, secondPolicy)

        // assert
        assertThat(secondCacheHandle.hasResult()).isFalse()
        assertThat(secondCacheHandle.willStore()).isTrue()
        secondCacheHandle.close()
        assertThat(cache.getInFlightExecutionsSize()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun deduplicatedActionWithMissingOutputIsACacheMiss() {
        // arrange
        val cache: RemoteSpawnCache = createRemoteSpawnCache()

        val firstSpawn: SimpleSpawn = simplePathMappedSpawn("k8-fastbuild")
        val firstFakeFileCache: com.google.devtools.build.lib.remote.FakeActionInputFileCache =
            com.google.devtools.build.lib.remote.FakeActionInputFileCache(execRoot)
        firstFakeFileCache.createScratchInput(firstSpawn.getInputFiles().getSingleton(), "xyz")
        val firstPolicy: SpawnExecutionContext =
            createSpawnExecutionContext(firstSpawn, execRoot, firstFakeFileCache, outErr)

        val secondSpawn: SimpleSpawn = simplePathMappedSpawn("k8-opt")
        val secondFakeFileCache: com.google.devtools.build.lib.remote.FakeActionInputFileCache =
            com.google.devtools.build.lib.remote.FakeActionInputFileCache(execRoot)
        secondFakeFileCache.createScratchInput(secondSpawn.getInputFiles().getSingleton(), "xyz")
        val secondPolicy: SpawnExecutionContext =
            createSpawnExecutionContext(secondSpawn, execRoot, secondFakeFileCache, outErr)

        val remoteExecutionService: RemoteExecutionService? = cache.getRemoteExecutionService()
        Mockito.doCallRealMethod().`when`<Any?>(remoteExecutionService)
            .waitForAndReuseOutputs(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
        // Simulate a very slow upload to the remote cache to ensure that the second spawn is
        // deduplicated rather than a cache hit. This is a slight hack, but also avoid introducing
        // concurrency to this test.
        val onUploadComplete: AtomicReference<java.lang.Runnable?> = AtomicReference<java.lang.Runnable?>()
        Mockito.doAnswer(
            Answer { invocationOnMock: InvocationOnMock? ->
                onUploadComplete.set(invocationOnMock.getArgument<java.lang.Runnable?>(2))
                null
            })
            .`when`<Any?>(remoteExecutionService)
            .uploadOutputs(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )

        cache.lookup(firstSpawn, firstPolicy).use { firstCacheHandle ->
            // Do not create the output.
            firstCacheHandle.store(
                Builder()
                    .setExitCode(0)
                    .setStatus(Status.SUCCESS)
                    .setRunnerName("test")
                    .build()
            )
        }
        val secondCacheHandle: CacheHandle = cache.lookup(secondSpawn, secondPolicy)

        // assert
        assertThat(secondCacheHandle.hasResult()).isFalse()
        assertThat(secondCacheHandle.willStore()).isTrue()
        onUploadComplete.get().run()
        assertThat(cache.getInFlightExecutionsSize()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun pathMappedActionWithCacheHitRemovesInFlightExecution() {
        // arrange
        val cache: RemoteSpawnCache = createRemoteSpawnCache()

        val spawn: SimpleSpawn = simplePathMappedSpawn("k8-fastbuild")
        val fakeFileCache: com.google.devtools.build.lib.remote.FakeActionInputFileCache =
            com.google.devtools.build.lib.remote.FakeActionInputFileCache(execRoot)
        fakeFileCache.createScratchInput(spawn.getInputFiles().getSingleton(), "xyz")
        val policy: SpawnExecutionContext =
            createSpawnExecutionContext(spawn, execRoot, fakeFileCache, outErr)

        val remoteExecutionService: RemoteExecutionService? = cache.getRemoteExecutionService()
        Mockito.doReturn(
            RemoteActionResult.createFromCache(
                CachedActionResult.remote(createSuccessfulResult(spawn))
            )
        )
            .`when`<Any?>(remoteExecutionService)
            .lookupCache(ArgumentMatchers.any<T?>())
        Mockito.doReturn(null).`when`<Any?>(remoteExecutionService)
            .downloadOutputs(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

        cache.lookup(spawn, policy).use { cacheHandle ->
            com.google.common.base.Preconditions.checkState(cacheHandle.hasResult())
        }
        // assert
        assertThat(cache.getInFlightExecutionsSize()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun pathMappedActionNotUploadedRemovesInFlightExecution() {
        // arrange
        val cache: RemoteSpawnCache = createRemoteSpawnCache()

        val spawn: SimpleSpawn = simplePathMappedSpawn("k8-fastbuild")
        val fakeFileCache: com.google.devtools.build.lib.remote.FakeActionInputFileCache =
            com.google.devtools.build.lib.remote.FakeActionInputFileCache(execRoot)
        fakeFileCache.createScratchInput(spawn.getInputFiles().getSingleton(), "xyz")
        val policy: SpawnExecutionContext =
            createSpawnExecutionContext(spawn, execRoot, fakeFileCache, outErr)

        val remoteExecutionService: RemoteExecutionService? = cache.getRemoteExecutionService()
        Mockito.doCallRealMethod()
            .`when`<Any?>(remoteExecutionService)
            .commitResultAndDecideWhetherToUpload(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

        cache.lookup(spawn, policy).use { cacheHandle ->
            cacheHandle.store(
                Builder()
                    .setExitCode(1)
                    .setStatus(Status.NON_ZERO_EXIT)
                    .setFailureDetail(
                        FailureDetail.newBuilder()
                            .setMessage("test spawn failed")
                            .setSpawn(
                                FailureDetails.Spawn.newBuilder()
                                    .setCode(FailureDetails.Spawn.Code.NON_ZERO_EXIT)
                            )
                            .build()
                    )
                    .setRunnerName("test")
                    .build()
            )
        }
        // assert
        assertThat(cache.getInFlightExecutionsSize()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun pathMappedActionWithCacheIoExceptionRemovesInFlightExecution() {
        // arrange
        val cache: RemoteSpawnCache = createRemoteSpawnCache()

        val spawn: SimpleSpawn = simplePathMappedSpawn("k8-fastbuild")
        val fakeFileCache: com.google.devtools.build.lib.remote.FakeActionInputFileCache =
            com.google.devtools.build.lib.remote.FakeActionInputFileCache(execRoot)
        fakeFileCache.createScratchInput(spawn.getInputFiles().getSingleton(), "xyz")
        val policy: SpawnExecutionContext =
            createSpawnExecutionContext(spawn, execRoot, fakeFileCache, outErr)

        val remoteExecutionService: RemoteExecutionService? = cache.getRemoteExecutionService()
        Mockito.doReturn(
            RemoteActionResult.createFromCache(
                CachedActionResult.remote(ActionResult.getDefaultInstance())
            )
        )
            .`when`<Any?>(remoteExecutionService)
            .lookupCache(ArgumentMatchers.any<T?>())
        Mockito.doThrow(IOException()).`when`<Any?>(remoteExecutionService)
            .downloadOutputs(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

        cache.lookup(spawn, policy).use { cacheHandle ->
            com.google.common.base.Preconditions.checkState(!cacheHandle.hasResult())
        }
        // assert
        assertThat(cache.getInFlightExecutionsSize()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun pathMappedActionWithCacheCredentialHelperExceptionRemovesInFlightExecution() {
        // arrange
        val cache: RemoteSpawnCache = createRemoteSpawnCache()

        val spawn: SimpleSpawn = simplePathMappedSpawn("k8-fastbuild")
        val fakeFileCache: com.google.devtools.build.lib.remote.FakeActionInputFileCache =
            com.google.devtools.build.lib.remote.FakeActionInputFileCache(execRoot)
        fakeFileCache.createScratchInput(spawn.getInputFiles().getSingleton(), "xyz")
        val policy: SpawnExecutionContext =
            createSpawnExecutionContext(spawn, execRoot, fakeFileCache, outErr)

        val remoteExecutionService: RemoteExecutionService? = cache.getRemoteExecutionService()
        Mockito.doReturn(
            RemoteActionResult.createFromCache(
                CachedActionResult.remote(createSuccessfulResult(spawn))
            )
        )
            .`when`<Any?>(remoteExecutionService)
            .lookupCache(ArgumentMatchers.any<T?>())
        Mockito.doThrow(CredentialHelperException("credential helper failed"))
            .`when`<Any?>(remoteExecutionService)
            .downloadOutputs(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

        // act
        org.junit.Assert.assertThrows<T?>(
            ExecException::class.java,
            org.junit.function.ThrowingRunnable { cache.lookup(spawn, policy).close() })

        // assert
        assertThat(cache.getInFlightExecutionsSize()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMaterializeParamFiles() {
        testParamFilesAreMaterializedForFlag("--materialize_param_files")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMaterializeParamFilesIsImpliedBySubcommands() {
        testParamFilesAreMaterializedForFlag("--subcommands")
    }

    @Throws(java.lang.Exception::class)
    private fun testParamFilesAreMaterializedForFlag(flag: String?) {
        val remoteOptions: RemoteOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(RemoteOptions::class.java)
        val executionOptions: ExecutionOptions? =
            com.google.devtools.common.options.Options.parse(ExecutionOptions::class.java, flag).options
        val cache: RemoteSpawnCache = remoteSpawnCacheWithOptions(remoteOptions, executionOptions)

        val args: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("--foo", "--bar")
        val input: CommandLines.ParamFileActionInput =
            ParamFileActionInput(
                PathFragment.create("out/param_file"), args, ParameterFile.ParameterFileType.UNQUOTED
            )
        val spawn: Spawn =
            SimpleSpawn(
                FakeOwner("foo", "bar", "//dummy:label"),  /* arguments= */
                com.google.common.collect.ImmutableList.of<E?>(),  /* environment= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* executionInfo= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* inputs= */
                NestedSetBuilder.create(Order.STABLE_ORDER, input),  /* outputs= */
                com.google.common.collect.ImmutableSet.of<E?>(),
                ResourceSet.ZERO
            )
        val paramFile: Path = execRoot.getRelative("out/param_file")

        val success: ActionResult? = ActionResult.newBuilder().setExitCode(0).build()
        Mockito.`when`<T?>(
            combinedCache.downloadActionResult(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(),  /* inlineOutErr= */
                ArgumentMatchers.eq(false),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<Any?>())
        ))
        .thenReturn(CachedActionResult.remote(success))
        Mockito.doReturn(null).`when`<T?>(cache.getRemoteExecutionService())
            .downloadOutputs(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

        val policy: SpawnExecutionContext =
            createSpawnExecutionContext(
                spawn, execRoot, com.google.devtools.build.lib.remote.FakeActionInputFileCache(execRoot), outErr
            )
        cache.lookup(spawn, policy).use { secondCacheHandle ->
            assertThat(secondCacheHandle.hasResult()).isTrue()
            assertThat(paramFile.exists()).isTrue()
            paramFile.getInputStream().use { inputStream ->
                Truth.assertThat<String?>(
                    String(
                        com.google.common.io.ByteStreams.toByteArray(inputStream),
                        java.nio.charset.StandardCharsets.UTF_8
                    )
                        .split("\n")
                )
                    .asList()
                    .containsExactly("--foo", "--bar")
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun missingMandatoryOutputs_noCacheHit() {
        // Test that an AC which misses mandatory outputs is correctly ignored.
        // arrange
        val cache: RemoteSpawnCache = createRemoteSpawnCache()
        Mockito.`when`<T?>(
            combinedCache.downloadActionResult(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(),  /* inlineOutErr= */
                ArgumentMatchers.eq(false),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<Any?>())
        ))
        .thenReturn(CachedActionResult.remote(ActionResult.getDefaultInstance()))

        // act
        val cacheHandle: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            cache.lookup(simpleSpawn, simplePolicy)

        // assert
        assertThat(cacheHandle.hasResult()).isFalse()
        assertThat(cacheHandle.willStore()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildRemoteActionFailure_localFallback() {
        val remoteOptions: RemoteOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        remoteOptions.remoteLocalFallback = true
        remoteOptions.remoteLocalFallbackForRemoteCache = true
        remoteOptions.remoteAcceptCached = true

        val cache: RemoteSpawnCache = remoteSpawnCacheWithOptions(remoteOptions)
        val service: RemoteExecutionService? = cache.getRemoteExecutionService()
        doThrow(RemoteExecutionCapabilitiesException(IOException("capabilities failed")))
            .`when`<Any?>(service)
            .buildRemoteAction(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

        val handle: CacheHandle = cache.lookup(simpleSpawn, simplePolicy)

        assertThat(handle.hasResult()).isFalse()
        assertThat(handle.willStore()).isFalse()
        assertThat(handle).isEqualTo(SpawnCache.NO_RESULT_NO_STORE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildRemoteActionFailure_noLocalFallback_shouldThrow() {
        val remoteOptions: RemoteOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        remoteOptions.remoteLocalFallback = false
        remoteOptions.remoteAcceptCached = true

        val cache: RemoteSpawnCache = remoteSpawnCacheWithOptions(remoteOptions)
        val service: RemoteExecutionService? = cache.getRemoteExecutionService()
        doThrow(RemoteExecutionCapabilitiesException(IOException("capabilities failed")))
            .`when`<Any?>(service)
            .buildRemoteAction(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

        org.junit.Assert.assertThrows<T?>(
            ExecException::class.java,
            org.junit.function.ThrowingRunnable { cache.lookup(simpleSpawn, simplePolicy) })
    }

    companion object {
        private val DUMMY_REMOTE_OUTPUT_CHECKER: RemoteOutputChecker =
            RemoteOutputChecker("build", RemoteOutputsMode.MINIMAL, com.google.common.collect.ImmutableList.of<E?>())

        private const val BUILD_REQUEST_ID = "build-req-id"
        private const val COMMAND_ID = "command-id"

        private fun createSpawnExecutionContext(
            spawn: Spawn?,
            execRoot: Path?,
            fakeFileCache: com.google.devtools.build.lib.remote.FakeActionInputFileCache,
            outErr: FileOutErr
        ): SpawnExecutionContext {
            return object : SpawnExecutionContext() {
                private var digest: com.google.devtools.build.lib.exec.Protos.Digest? = null

                val id: Int
                    get() = 0

                public override fun setDigest(digest: com.google.devtools.build.lib.exec.Protos.Digest?) {
                    com.google.common.base.Preconditions.checkState(this.digest == null)
                    this.digest = digest
                }

                public override fun getDigest(): com.google.devtools.build.lib.exec.Protos.Digest? {
                    return digest
                }

                public override fun prefetchInputs(): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
                    return com.google.common.util.concurrent.Futures.immediateVoidFuture()
                }

                public override fun lockOutputFiles(exitCode: Int, errorMessage: String?, outErr: FileOutErr?) {}

                public override fun speculating(): Boolean {
                    return false
                }

                val inputMetadataProvider: InputMetadataProvider
                    get() = fakeFileCache

                val pathResolver: ArtifactPathResolver
                    get() = ArtifactPathResolver.forExecRoot(execRoot)

                val timeout: java.time.Duration
                    get() = java.time.Duration.ZERO

                val fileOutErr: FileOutErr
                    get() = outErr

                public override fun getInputMapping(
                    baseDirectory: PathFragment?, willAccessRepeatedly: Boolean
                ): SortedMap<PathFragment?, ActionInput?> {
                    return SpawnInputExpander().getInputMapping(spawn, fakeFileCache, baseDirectory)
                }

                public override fun report(progress: ProgressStatus?) {}

                val isRewindingEnabled: Boolean
                    get() = false

                public override fun checkForLostInputs() {}

                public override fun <T : ActionContext?> getContext(identifyingType: java.lang.Class<T?>?): T? {
                    throw java.lang.UnsupportedOperationException()
                }

                val actionFileSystem: FileSystem?
                    get() = null

                val clientEnv: com.google.common.collect.ImmutableMap<String?, String?>
                    get() = com.google.common.collect.ImmutableMap.of<String?, String?>()
            }
        }

        private fun simpleSpawnWithExecutionInfo(
            executionInfo: com.google.common.collect.ImmutableMap<String?, String?>?
        ): SimpleSpawn {
            return SimpleSpawn(
                FakeOwner("Mnemonic", "Progress Message", "//dummy:label"),
                com.google.common.collect.ImmutableList.of<E?>("/bin/echo", "Hi!"),
                com.google.common.collect.ImmutableMap.of<K?, V?>("VARIABLE", "value"),
                executionInfo,  /* inputs= */
                NestedSetBuilder.create(
                    Order.STABLE_ORDER, ActionInputHelper.fromPath("input")
                ),  /* outputs= */
                com.google.common.collect.ImmutableSet.of<E?>(ActionInputHelper.fromPath("/random/file")),
                ResourceSet.ZERO
            )
        }

        private fun simplePathMappedSpawn(configSegment: String?): SimpleSpawn {
            return simplePathMappedSpawn(
                configSegment, FakeOwner("Mnemonic", "Progress Message", "//dummy:label")
            )
        }

        private fun simplePathMappedSpawn(
            configSegment: String?, owner: ActionExecutionMetadata?
        ): SimpleSpawn {
            val inputPath = "bazel-bin/%s/bin/input"
            val outputPath = "bazel-bin/%s/bin/output"
            return SimpleSpawn(
                owner,
                com.google.common.collect.ImmutableList.of<E?>(
                    "cp",
                    inputPath.formatted("cfg"),
                    outputPath.formatted("cfg")
                ),
                com.google.common.collect.ImmutableMap.of<K?, V?>("VARIABLE", "value"),
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    ExecutionRequirements.SUPPORTS_PATH_MAPPING,
                    ""
                ),  /* inputs= */
                NestedSetBuilder.create(
                    Order.STABLE_ORDER, ActionInputHelper.fromPath(inputPath.formatted(configSegment))
                ),  /* tools= */
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),  /* outputs= */
                com.google.common.collect.ImmutableSet.of<E?>(
                    ActionInputHelper.fromPath(outputPath.formatted(configSegment))
                ),  /* mandatoryOutputs= */
                null,
                ResourceSet.ZERO,
                { execPath -> execPath.subFragment(0, 1).getRelative("cfg").getRelative(execPath.subFragment(2)) })
        }
    }
}
