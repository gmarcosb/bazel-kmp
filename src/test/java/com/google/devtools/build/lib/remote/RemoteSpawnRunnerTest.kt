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

/** Tests for [com.google.devtools.build.lib.remote.RemoteSpawnRunner]  */
@RunWith(JUnit4::class)
class RemoteSpawnRunnerTest {
    @org.junit.Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    @org.mockito.Mock
    private val remoteOutputChecker: RemoteOutputChecker? = null // download nothing by default.

    private val reporter: com.google.devtools.build.lib.events.Reporter =
        com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus())
    private var retryService: com.google.common.util.concurrent.ListeningScheduledExecutorService? = null

    private var fs: FileSystem? = null
    private var execRoot: Path? = null
    private var artifactRoot: ArtifactRoot? = null
    private var tempPathGenerator: TempPathGenerator? = null
    private var logDir: Path? = null
    private var digestUtil: DigestUtil? = null
    private var fakeFileCache: com.google.devtools.build.lib.remote.FakeActionInputFileCache? = null
    private var outErr: FileOutErr? = null

    private var remoteOptions: RemoteOptions? = null

    @org.mockito.Mock
    private val cache: RemoteExecutionCache? = null

    @org.mockito.Mock
    private val executor: RemoteExecutionClient? = null

    @org.mockito.Mock
    private val localRunner: SpawnRunner? = null

    private val remoteExecutorCapabilities: ServerCapabilities? = ServerCapabilities.newBuilder()
        .setLowApiVersion(ApiVersion.low.toSemVer())
        .setHighApiVersion(ApiVersion.high.toSemVer())
        .setExecutionCapabilities(ExecutionCapabilities.newBuilder().setExecEnabled(true).build())
        .build()

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        digestUtil = DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256)
        fs = InMemoryFileSystem(com.google.devtools.build.lib.clock.JavaClock(), DigestHashFunction.SHA256)
        execRoot = fs.getPath("/exec/root")
        execRoot.createDirectoryAndParents()
        artifactRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "outputs")
        artifactRoot.getRoot().asPath().createDirectoryAndParents()
        tempPathGenerator = TempPathGenerator(fs.getPath("/execroot/_tmp/actions/remote"))
        logDir = fs.getPath("/server-logs")
        fakeFileCache = com.google.devtools.build.lib.remote.FakeActionInputFileCache(execRoot)

        val stdout: Path = fs.getPath("/tmp/stdout")
        val stderr: Path = fs.getPath("/tmp/stderr")
        stdout.getParentDirectory().createDirectoryAndParents()
        stderr.getParentDirectory().createDirectoryAndParents()
        outErr = FileOutErr(stdout, stderr)

        remoteOptions = com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)

        retryService =
            com.google.common.util.concurrent.MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1))
        Mockito.`when`<T?>(cache.hasRemoteCache()).thenReturn(true)
        Mockito.doReturn(remoteExecutorCapabilities).`when`<Any?>(cache).getRemoteServerCapabilities()
        Mockito.`when`<T?>(executor.getServerCapabilities()).thenReturn(remoteExecutorCapabilities)
        Mockito.`when`<T?>(cache.remoteActionCacheSupportsUpdate()).thenReturn(true)
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
    fun nonCachableSpawnsShouldNotBeCached_remote() {
        // Test that if a spawn is marked "NO_CACHE" then it's not fetched from a remote cache.
        // It should be executed remotely, but marked non-cacheable to remote execution, so that
        // the action result is not saved in the remote cache.

        remoteOptions.remoteAcceptCached = true
        remoteOptions.remoteLocalFallback = false
        remoteOptions.remoteUploadLocalResults = true
        remoteOptions.remoteResultCachePriority = 1
        remoteOptions.remoteExecutionPriority = 2

        val runner: RemoteSpawnRunner = newSpawnRunner()
        val service: RemoteExecutionService? = runner.getRemoteExecutionService()

        val succeeded: ExecuteResponse? =
            ExecuteResponse.newBuilder()
                .setResult(ActionResult.newBuilder().setExitCode(0).build())
                .build()
        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenReturn(succeeded)

        val spawn: Spawn = simpleSpawnWithExecutionInfo(NO_CACHE)
        val policy: SpawnExecutionContext = getSpawnContext(spawn)

        runner.exec(spawn, policy)

        val requestCaptor: ArgumentCaptor<ExecuteRequest?> =
            ArgumentCaptor.forClass<ExecuteRequest?, ExecuteRequest?>(ExecuteRequest::class.java)
        Mockito.verify<Any?>(executor)
            .executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                requestCaptor.capture(),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        assertThat(requestCaptor.getValue().getSkipCacheLookup()).isTrue()
        assertThat(requestCaptor.getValue().getResultsCachePolicy().getPriority()).isEqualTo(1)
        assertThat(requestCaptor.getValue().getExecutionPolicy().getPriority()).isEqualTo(2)

        // TODO(olaola): verify that the uploaded action has the doNotCache set.
        Mockito.verify<Any?>(service, Mockito.never()).lookupCache(ArgumentMatchers.any<T?>())
        Mockito.verify<Any?>(service, Mockito.never()).uploadOutputs(
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>()
        )
        Mockito.verifyNoMoreInteractions(localRunner)
    }

    private fun getSpawnContext(spawn: Spawn?): FakeSpawnExecutionContext {
        val fakeLocalStrategy: AbstractSpawnStrategy =
            object : AbstractSpawnStrategy(
                localRunner,
                com.google.devtools.common.options.Options.getDefaults<O?>(ExecutionOptions::class.java)
            ) {}
        val actionContextRegistry: com.google.common.collect.ClassToInstanceMap<ActionContext?> =
            com.google.common.collect.ImmutableClassToInstanceMap.of<B?, RemoteLocalFallbackRegistry?>(
                RemoteLocalFallbackRegistry::class.java,
                RemoteLocalFallbackRegistry { spawnInput -> fakeLocalStrategy })

        val actionInputFetcher: RemoteActionInputFetcher =
            RemoteActionInputFetcher(
                com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus()),
                "none",
                "none",
                cache,
                execRoot,
                tempPathGenerator,
                remoteOutputChecker,
                ActionOutputDirectoryHelper.createForTesting(),
                OutputPermissions.READONLY
            )

        val actionFileSystem: RemoteActionFileSystem =
            RemoteActionFileSystem(
                fs,
                execRoot.asFragment(),
                artifactRoot.getRoot().asPath().relativeTo(execRoot).getPathString(),
                ActionInputMap(0),
                actionInputFetcher
            )

        return FakeSpawnExecutionContext(
            spawn, fakeFileCache, execRoot, outErr, actionContextRegistry, actionFileSystem
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nonCachableSpawnsShouldNotBeCached_localFallback() {
        // Test that if a non-cachable spawn is executed locally due to the local fallback,
        // that its result is not uploaded to the remote cache.

        remoteOptions.remoteAcceptCached = true
        remoteOptions.remoteLocalFallback = true
        remoteOptions.remoteUploadLocalResults = true

        val runner: RemoteSpawnRunner = newSpawnRunner()

        // Throw an IOException to trigger the local fallback.
        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenThrow(IOException::class.java)

        val spawn: Spawn = simpleSpawnWithExecutionInfo(NO_CACHE)
        val policy: SpawnExecutionContext = getSpawnContext(spawn)

        runner.exec(spawn, policy)

        Mockito.verify<Any?>(localRunner).exec(spawn, policy)
        Mockito.verify<Any?>(cache).getRemoteServerCapabilities()
        Mockito.verify<Any?>(cache).ensureInputsPresent(
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.anyBoolean(),
            ArgumentMatchers.any<T?>()
        )
        Mockito.verify<Any?>(cache, Mockito.atLeastOnce()).hasRemoteCache()
        Mockito.verify<Any?>(cache, Mockito.atLeastOnce()).hasDiskCache()
        Mockito.verifyNoMoreInteractions(cache)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cachableSpawnsShouldBeCached_localFallback() {
        // Test that if a cacheable spawn is executed locally due to the local fallback,
        // that its result is uploaded to the remote cache.

        remoteOptions.remoteAcceptCached = true
        remoteOptions.remoteLocalFallback = true
        remoteOptions.remoteUploadLocalResults = true

        val runner: RemoteSpawnRunner = spy(newSpawnRunner())
        val service: RemoteExecutionService? = runner.getRemoteExecutionService()
        Mockito.doNothing().`when`<Any?>(service).uploadOutputs(
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>()
        )

        // Throw an IOException to trigger the local fallback.
        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenThrow(IOException::class.java)

        val res: SpawnResult? =
            Builder()
                .setStatus(Status.SUCCESS)
                .setExitCode(0)
                .setRunnerName("test")
                .build()
        Mockito.`when`<T?>(
            localRunner.exec(
                ArgumentMatchers.any<T?>(Spawn::class.java),
                ArgumentMatchers.any<T?>(SpawnExecutionContext::class.java)
            )
        ).thenReturn(res)

        val spawn: Spawn = newSimpleSpawn()
        val policy: SpawnExecutionContext = getSpawnContext(spawn)

        val result: SpawnResult = runner.exec(spawn, policy)
        assertThat(result.exitCode()).isEqualTo(0)
        assertThat(result.status()).isEqualTo(Status.SUCCESS)
        Mockito.verify<Any?>(localRunner).exec(< T > eq < T ? > (spawn), <T>eq<T?>(policy))
        Mockito.verify<Any?>(runner)
            .execLocallyAndUpload(ArgumentMatchers.any<T?>(), < T > eq < T ? > (spawn), <T>eq<T?>(policy),  /* uploadLocalResults= */ArgumentMatchers.eq(true))
        Mockito.verify<Any?>(service)
            .uploadOutputs(ArgumentMatchers.any<T?>(), < T > eq < T ? > (res), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun failedLocalActionShouldNotBeUploaded() {
        // Test that the outputs of a locally executed action that failed are not uploaded.

        remoteOptions.remoteLocalFallback = true
        remoteOptions.remoteUploadLocalResults = true

        val runner: RemoteSpawnRunner = spy(newSpawnRunner())
        val service: RemoteExecutionService? = runner.getRemoteExecutionService()

        // Throw an IOException to trigger the local fallback.
        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenThrow(IOException::class.java)

        val spawn: Spawn = newSimpleSpawn()
        val policy: SpawnExecutionContext = getSpawnContext(spawn)

        val res: SpawnResult = Mockito.mock<SpawnResult>(SpawnResult::class.java)
        Mockito.`when`<T?>(res.exitCode()).thenReturn(1)
        Mockito.`when`<T?>(res.status()).thenReturn(Status.EXECUTION_FAILED)
        Mockito.`when`<T?>(localRunner.exec(< T > eq < T ? > (spawn), < T > eq < T ? > (policy))).thenReturn(res)

        assertThat(runner.exec(spawn, policy)).isSameInstanceAs(res)

        Mockito.verify<Any?>(localRunner).exec(< T > eq < T ? > (spawn), <T>eq<T?>(policy))
        Mockito.verify<Any?>(runner)
            .execLocallyAndUpload(ArgumentMatchers.any<T?>(), < T > eq < T ? > (spawn), <T>eq<T?>(policy),  /* uploadLocalResults= */ArgumentMatchers.eq(true))
        Mockito.verify<Any?>(service, Mockito.never()).uploadOutputs(
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun treatFailedCachedActionAsCacheMiss_local() {
        // Test that bazel treats failed cache action as a cache miss and attempts to execute action
        // locally

        remoteOptions.remoteLocalFallback = true
        remoteOptions.remoteUploadLocalResults = true

        val failedAction: CachedActionResult? =
            CachedActionResult.remote(ActionResult.newBuilder().setExitCode(1).build())
        Mockito.`when`<T?>(
            cache.downloadActionResult(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ActionKey::class.java),  /* inlineOutErr= */
                ArgumentMatchers.eq(false),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<Any?>())
        ))
        .thenReturn(failedAction)

        val runner: RemoteSpawnRunner = spy(newSpawnRunner())
        val service: RemoteExecutionService? = runner.getRemoteExecutionService()
        // Throw an IOException to trigger the local fallback.
        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenThrow(IOException::class.java)
        Mockito.doNothing().`when`<Any?>(service).uploadOutputs(
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>()
        )

        val spawn: Spawn = newSimpleSpawn()
        val policy: SpawnExecutionContext = getSpawnContext(spawn)

        val succeeded: SpawnResult? =
            Builder()
                .setStatus(Status.SUCCESS)
                .setExitCode(0)
                .setRunnerName("test")
                .build()
        Mockito.`when`<T?>(localRunner.exec(< T > eq < T ? > (spawn), < T > eq < T ? > (policy))).thenReturn(succeeded)

        val result: SpawnResult? = runner.exec(spawn, policy)

        Mockito.verify<Any?>(localRunner).exec(< T > eq < T ? > (spawn), <T>eq<T?>(policy))
        Mockito.verify<Any?>(runner)
            .execLocallyAndUpload(ArgumentMatchers.any<T?>(), < T > eq < T ? > (spawn), <T>eq<T?>(policy),  /* uploadLocalResults= */ArgumentMatchers.eq(true))
        Mockito.verify<Any?>(service)
            .uploadOutputs(ArgumentMatchers.any<T?>(), < T > eq < T ? > (result), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
        Mockito.verify<Any?>(service, Mockito.never())
            .downloadOutputs(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun remoteLocalFallback_buildRemoteActionFailure() {
        remoteOptions.remoteLocalFallback = true

        val runner: RemoteSpawnRunner = spy(newSpawnRunner())
        val service: RemoteExecutionService? = runner.getRemoteExecutionService()
        doThrow(RemoteExecutionCapabilitiesException(IOException("capabilities failed")))
            .`when`<Any?>(service)
            .buildRemoteAction(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

        val spawn: Spawn = newSimpleSpawn()
        val policy: SpawnExecutionContext = getSpawnContext(spawn)

        val localResult: SpawnResult? =
            Builder()
                .setExitCode(0)
                .setStatus(Status.SUCCESS)
                .setRunnerName("local")
                .build()
        Mockito.`when`<T?>(localRunner.exec(spawn, policy)).thenReturn(localResult)

        val result: SpawnResult? = runner.exec(spawn, policy)

        assertThat(result).isEqualTo(localResult)
        Mockito.verify<Any?>(localRunner).exec(spawn, policy)
        Mockito.verify<Any?>(service, Mockito.never()).uploadOutputs(
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildRemoteActionFailure_noLocalFallback_shouldThrow() {
        remoteOptions.remoteLocalFallback = false

        val runner: RemoteSpawnRunner = spy(newSpawnRunner())
        val service: RemoteExecutionService? = runner.getRemoteExecutionService()
        doThrow(RemoteExecutionCapabilitiesException(IOException("capabilities failed")))
            .`when`<Any?>(service)
            .buildRemoteAction(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

        val spawn: Spawn = newSimpleSpawn()
        val policy: SpawnExecutionContext = getSpawnContext(spawn)

        org.junit.Assert.assertThrows<T?>(
            ExecException::class.java,
            org.junit.function.ThrowingRunnable { runner.exec(spawn, policy) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun treatFailedCachedActionAsCacheMiss_remote() {
        // Test that bazel treats failed cache action as a cache miss and attempts to execute action
        // remotely

        val failedAction: CachedActionResult? =
            CachedActionResult.remote(ActionResult.newBuilder().setExitCode(1).build())
        Mockito.`when`<T?>(
            cache.downloadActionResult(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ActionKey::class.java),  /* inlineOutErr= */
                ArgumentMatchers.eq(false),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<Any?>())
        ))
        .thenReturn(failedAction)

        val runner: RemoteSpawnRunner = newSpawnRunner()
        val service: RemoteExecutionService? = runner.getRemoteExecutionService()

        val succeeded: ExecuteResponse? =
            ExecuteResponse.newBuilder()
                .setResult(ActionResult.newBuilder().setExitCode(0).build())
                .build()
        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenReturn(succeeded)
        val spawn: Spawn = newSimpleSpawn()
        val policy: SpawnExecutionContext = getSpawnContext(spawn)

        runner.exec(spawn, policy)

        Mockito.verify<Any?>(service)
            .executeRemotely(ArgumentMatchers.any<T?>(), ArgumentMatchers.eq(false), ArgumentMatchers.any<T?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun treatCachedActionWithMissingOutputAsCacheMiss_duringRemoteCacheCheck() {
        // Test that bazel treats a cached action with missing mandatory outputs as a cache miss and
        // attempts to execute the action remotely.

        val runner: RemoteSpawnRunner = newSpawnRunner()
        val service: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            runner.getRemoteExecutionService()
        val actionWithoutOutputs: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            CachedActionResult.remote(ActionResult.getDefaultInstance())
        doReturn(RemoteActionResult.createFromCache(actionWithoutOutputs))
            .`when`<Any?>(service)
            .lookupCache(ArgumentMatchers.any<T?>(RemoteAction::class.java))

        val output: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ActionsTestUtil.createArtifactWithExecPath(
                artifactRoot, PathFragment.create("outputs/out")
            )
        val successfulResponse: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ExecuteResponse.newBuilder()
                .setResult(
                    ActionResult.newBuilder()
                        .setExitCode(0)
                        .addOutputFiles(
                            OutputFile.newBuilder()
                                .setPath(output.getExecPathString())
                                .setDigest(digestUtil.computeAsUtf8("content"))
                        )
                        .build()
                )
                .build()
        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenReturn(successfulResponse)
        val spawn: Spawn = newSimpleSpawn(output)
        val spawnExecutionContext: FakeSpawnExecutionContext = getSpawnContext(spawn)

        val result: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            runner.exec(spawn, spawnExecutionContext)
        assertThat(result.status()).isEqualTo(Status.SUCCESS)

        Mockito.verify<Any?>(service)
            .executeRemotely(ArgumentMatchers.any<T?>(), ArgumentMatchers.eq(false), ArgumentMatchers.any<T?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun treatCachedActionWithMissingOutputAsCacheMiss_duringRemoteExecution() {
        // Test that bazel treats a cached execute result with missing mandatory outputs as a cache miss
        // and reattempts to execute the action remotely, this time ignoring cached results.

        val runner: RemoteSpawnRunner = newSpawnRunner()
        val service: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            runner.getRemoteExecutionService()
        // Ensure that the initial cache lookup doesn't already return a result with missing outputs -
        // this case is covered by the previous test.
        Mockito.doReturn(null).`when`<Any?>(service).lookupCache(ArgumentMatchers.any<T?>(RemoteAction::class.java))

        val actionWithoutOutputs: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            CachedActionResult.remote(ActionResult.getDefaultInstance())
        Mockito.`when`<T?>(
            cache.downloadActionResult(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ActionKey::class.java),  /* inlineOutErr= */
                ArgumentMatchers.eq(false),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<Any?>())
        ))
        .thenReturn(actionWithoutOutputs)

        val output: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ActionsTestUtil.createArtifactWithExecPath(
                artifactRoot, PathFragment.create("outputs/out")
            )
        val responseWithoutOutput: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ExecuteResponse.newBuilder()
                .setResult(ActionResult.newBuilder().setExitCode(0).build())
                .setCachedResult(true)
                .build()
        val responseWithOutput: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ExecuteResponse.newBuilder()
                .setResult(
                    ActionResult.newBuilder()
                        .setExitCode(0)
                        .addOutputFiles(
                            OutputFile.newBuilder()
                                .setPath(output.getExecPathString())
                                .setDigest(digestUtil.computeAsUtf8("content"))
                        )
                        .build()
                )
                .build()
        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenAnswer(
                Answer { answer: InvocationOnMock? ->
                    if (answer.getArgument<ExecuteRequest?>(1, ExecuteRequest::class.java).getSkipCacheLookup())
                        responseWithOutput
                    else
                        responseWithoutOutput
                })
        val spawn: Spawn = newSimpleSpawn(output)
        val spawnExecutionContext: FakeSpawnExecutionContext = getSpawnContext(spawn)

        val result: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            runner.exec(spawn, spawnExecutionContext)
        assertThat(result.status()).isEqualTo(Status.SUCCESS)

        // The first execution attempt hits the cache and returns the result with missing outputs, the
        // second attempt forcibly re-executes the action.
        Mockito.verify<Any?>(service)
            .executeRemotely(ArgumentMatchers.any<T?>(), ArgumentMatchers.eq(true), ArgumentMatchers.any<T?>())
        Mockito.verify<Any?>(service)
            .executeRemotely(ArgumentMatchers.any<T?>(), ArgumentMatchers.eq(false), ArgumentMatchers.any<T?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noRemoteExecutorFallbackFails() {
        // Errors from the fallback runner should be propagated out of the remote runner.

        remoteOptions.remoteUploadLocalResults = true
        remoteOptions.remoteLocalFallback = true

        val runner: RemoteSpawnRunner = newSpawnRunner()
        // Trigger local fallback
        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenThrow(IOException())

        val spawn: Spawn = newSimpleSpawn()
        val policy: SpawnExecutionContext = getSpawnContext(spawn)

        Mockito.`when`<T?>(
            cache.downloadActionResult(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ActionKey::class.java),  /* inlineOutErr= */
                ArgumentMatchers.eq(false),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<Any?>())
        ))
        .thenReturn(null)

        val err: IOException = IOException("local execution error")
        Mockito.`when`<T?>(localRunner.exec(< T > eq < T ? > (spawn), < T > eq < T ? > (policy))).thenThrow(err)

        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { runner.exec(spawn, policy) })
        Truth.assertThat(e).isSameInstanceAs(err)

        Mockito.verify<Any?>(localRunner).exec(< T > eq < T ? > (spawn), <T>eq<T?>(policy))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun remoteCacheErrorFallbackFails() {
        // Errors from the fallback runner should be propagated out of the remote runner.

        remoteOptions.remoteUploadLocalResults = true
        remoteOptions.remoteLocalFallback = true

        val runner: RemoteSpawnRunner = newSpawnRunner()
        // Trigger local fallback
        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenThrow(IOException())

        val spawn: Spawn = newSimpleSpawn()
        val policy: SpawnExecutionContext = getSpawnContext(spawn)

        Mockito.`when`<T?>(
            cache.downloadActionResult(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ActionKey::class.java),  /* inlineOutErr= */
                ArgumentMatchers.eq(false),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<Any?>())
        ))
        .thenThrow(IOException())

        val err: IOException = IOException("local execution error")
        Mockito.`when`<T?>(localRunner.exec(< T > eq < T ? > (spawn), < T > eq < T ? > (policy))).thenThrow(err)

        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { runner.exec(spawn, policy) })
        Truth.assertThat(e).isSameInstanceAs(err)

        Mockito.verify<Any?>(localRunner).exec(< T > eq < T ? > (spawn), <T>eq<T?>(policy))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLocalFallbackFailureRemoteExecutorFailure() {
        remoteOptions.remoteLocalFallback = true

        val runner: RemoteSpawnRunner = newSpawnRunner()

        Mockito.`when`<T?>(
            cache.downloadActionResult(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ActionKey::class.java),  /* inlineOutErr= */
                ArgumentMatchers.eq(false),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<Any?>())
        ))
        .thenReturn(null)
        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenThrow(IOException())

        val spawn: Spawn = newSimpleSpawn()
        val policy: SpawnExecutionContext = getSpawnContext(spawn)

        val err: IOException = IOException("local execution error")
        Mockito.`when`<T?>(localRunner.exec(< T > eq < T ? > (spawn), < T > eq < T ? > (policy))).thenThrow(err)

        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { runner.exec(spawn, policy) })
        Truth.assertThat(e).isSameInstanceAs(err)

        Mockito.verify<Any?>(localRunner).exec(< T > eq < T ? > (spawn), <T>eq<T?>(policy))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHumanReadableServerLogsSavedForFailingAction() {
        val runner: RemoteSpawnRunner = newSpawnRunner()
        val service: RemoteExecutionService? = runner.getRemoteExecutionService()
        val logDigest: Digest? = digestUtil.computeAsUtf8("bla")
        val logPath: Path? = logDir.getRelative(SIMPLE_ACTION_ID).getRelative("logname")
        val resp: ExecuteResponse? =
            ExecuteResponse.newBuilder()
                .putServerLogs(
                    "logname", LogFile.newBuilder().setHumanReadable(true).setDigest(logDigest).build()
                )
                .setResult(ActionResult.newBuilder().setExitCode(31).build())
                .build()
        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenReturn(resp)
        val completed: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
        completed.set(null)
        Mockito.`when`<T?>(cache.downloadFile(ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java), < T > eq < T ? > (logPath), < T > eq < T ? > (logDigest)))
        .thenReturn(completed)

        val spawn: Spawn = newSimpleSpawn()
        val policy: SpawnExecutionContext = getSpawnContext(spawn)

        val res: SpawnResult = runner.exec(spawn, policy)
        assertThat(res.status()).isEqualTo(Status.NON_ZERO_EXIT)

        Mockito.verify<Any?>(executor)
            .executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        Mockito.verify<Any?>(service)
            .maybeDownloadServerLogs(ArgumentMatchers.any<T?>(), < T > eq < T ? > (resp), <T>eq<T?>(logDir))
        Mockito.verify<Any?>(cache)
            .downloadFile(ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java), < T > eq < T ? > (logPath), <T>eq<T?>(logDigest))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHumanReadableServerLogsSavedForFailingActionWithSiblingRepositoryLayout() {
        val runner: RemoteSpawnRunner = newSpawnRunner(SiblingRepositoryLayoutResolver(execRoot))
        val service: RemoteExecutionService? = runner.getRemoteExecutionService()
        val logDigest: Digest? = digestUtil.computeAsUtf8("bla")
        val logPath: Path? =
            logDir
                .getRelative("e0a5a3561464123504c1240b3587779cdfd6adee20f72aa136e388ecfd570c12")
                .getRelative("logname")
        val resp: ExecuteResponse? =
            ExecuteResponse.newBuilder()
                .putServerLogs(
                    "logname", LogFile.newBuilder().setHumanReadable(true).setDigest(logDigest).build()
                )
                .setResult(ActionResult.newBuilder().setExitCode(31).build())
                .build()
        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenReturn(resp)
        val completed: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
        completed.set(null)
        Mockito.`when`<T?>(cache.downloadFile(ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java), < T > eq < T ? > (logPath), < T > eq < T ? > (logDigest)))
        .thenReturn(completed)

        val spawn: Spawn = newSimpleSpawn()
        val policy: SpawnExecutionContext = getSpawnContext(spawn)

        val res: SpawnResult = runner.exec(spawn, policy)
        assertThat(res.status()).isEqualTo(Status.NON_ZERO_EXIT)

        Mockito.verify<Any?>(executor)
            .executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        Mockito.verify<Any?>(service)
            .maybeDownloadServerLogs(ArgumentMatchers.any<T?>(), < T > eq < T ? > (resp), <T>eq<T?>(logDir))
        Mockito.verify<Any?>(cache)
            .downloadFile(ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java), < T > eq < T ? > (logPath), <T>eq<T?>(logDigest))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHumanReadableServerLogsSavedForFailingActionWithStatus() {
        val runner: RemoteSpawnRunner = newSpawnRunner()
        val service: RemoteExecutionService? = runner.getRemoteExecutionService()
        val logDigest: Digest? = digestUtil.computeAsUtf8("bla")
        val logPath: Path? = logDir.getRelative(SIMPLE_ACTION_ID).getRelative("logname")
        val timeoutStatus: com.google.rpc.Status? =
            com.google.rpc.Status.newBuilder().setCode(Code.DEADLINE_EXCEEDED.getNumber()).build()
        val resp: ExecuteResponse =
            ExecuteResponse.newBuilder()
                .putServerLogs(
                    "logname", LogFile.newBuilder().setHumanReadable(true).setDigest(logDigest).build()
                )
                .setStatus(timeoutStatus)
                .build()
        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenThrow(IOException(ExecutionStatusException(resp.getStatus(), resp)))
        val completed: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
        completed.set(null)
        Mockito.`when`<T?>(cache.downloadFile(ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java), < T > eq < T ? > (logPath), < T > eq < T ? > (logDigest)))
        .thenReturn(completed)

        val spawn: Spawn = newSimpleSpawn()
        val policy: SpawnExecutionContext = getSpawnContext(spawn)

        val res: SpawnResult = runner.exec(spawn, policy)
        assertThat(res.status()).isEqualTo(Status.TIMEOUT)

        Mockito.verify<Any?>(executor)
            .executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        Mockito.verify<Any?>(service)
            .maybeDownloadServerLogs(ArgumentMatchers.any<T?>(), < T > eq < T ? > (resp), <T>eq<T?>(logDir))
        Mockito.verify<Any?>(cache)
            .downloadFile(ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java), < T > eq < T ? > (logPath), <T>eq<T?>(logDigest))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonHumanReadableServerLogsNotSaved() {
        // arrange
        val runner: RemoteSpawnRunner = newSpawnRunner()
        val service: RemoteExecutionService? = runner.getRemoteExecutionService()

        val logDigest: Digest? = digestUtil.computeAsUtf8("bla")
        val result: ActionResult? = ActionResult.newBuilder().setExitCode(31).build()
        val resp: ExecuteResponse? =
            ExecuteResponse.newBuilder()
                .putServerLogs("logname", LogFile.newBuilder().setDigest(logDigest).build())
                .setResult(result)
                .build()
        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenReturn(resp)

        val spawn: Spawn = newSimpleSpawn()
        val policy: FakeSpawnExecutionContext = getSpawnContext(spawn)

        // act
        val res: SpawnResult = runner.exec(spawn, policy)

        // asset
        assertThat(res.status()).isEqualTo(Status.NON_ZERO_EXIT)

        Mockito.verify<Any?>(executor)
            .executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        Mockito.verify<Any?>(service)
            .maybeDownloadServerLogs(ArgumentMatchers.any<T?>(), < T > eq < T ? > (resp), <T>eq<T?>(logDir))
        Mockito.verify<Any?>(cache, Mockito.never())
            .downloadFile(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(Path::class.java),
                ArgumentMatchers.any<T?>(Digest::class.java)
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testServerLogsNotSavedForSuccessfulAction() {
        val runner: RemoteSpawnRunner = newSpawnRunner()
        val service: RemoteExecutionService? = runner.getRemoteExecutionService()

        val logDigest: Digest? = digestUtil.computeAsUtf8("bla")
        val result: ActionResult? = ActionResult.newBuilder().setExitCode(0).build()
        val resp: ExecuteResponse? =
            ExecuteResponse.newBuilder()
                .putServerLogs(
                    "logname", LogFile.newBuilder().setHumanReadable(true).setDigest(logDigest).build()
                )
                .setResult(result)
                .build()
        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenReturn(resp)

        val spawn: Spawn = newSimpleSpawn()
        val policy: FakeSpawnExecutionContext = getSpawnContext(spawn)

        val res: SpawnResult = runner.exec(spawn, policy)
        assertThat(res.status()).isEqualTo(Status.SUCCESS)

        Mockito.verify<Any?>(executor)
            .executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        Mockito.verify<Any?>(service)
            .downloadOutputs(ArgumentMatchers.any<T?>(), eq(RemoteActionResult.createFromResponse(resp)))
        Mockito.verify<Any?>(service)
            .maybeDownloadServerLogs(ArgumentMatchers.any<T?>(), < T > eq < T ? > (resp), <T>eq<T?>(logDir))
        Mockito.verify<Any?>(cache, Mockito.never())
            .downloadFile(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(Path::class.java),
                ArgumentMatchers.any<T?>(Digest::class.java)
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cacheDownloadFailureTriggersRemoteExecution() {
        // If downloading a cached action fails, remote execution should be tried.

        // arrange

        val runner: RemoteSpawnRunner = newSpawnRunner()
        val service: RemoteExecutionService? = runner.getRemoteExecutionService()

        val cachedResult: CachedActionResult? =
            CachedActionResult.remote(ActionResult.newBuilder().setExitCode(0).build())
        Mockito.`when`<T?>(
            cache.downloadActionResult(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ActionKey::class.java),  /* inlineOutErr= */
                ArgumentMatchers.eq(false),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<Any?>())
        ))
        .thenReturn(cachedResult)
        val downloadFailure: java.lang.Exception =
            BulkTransferException(CacheNotFoundException(Digest.getDefaultInstance()))
        Mockito.doThrow(downloadFailure)
            .`when`<Any?>(service)
            .downloadOutputs(ArgumentMatchers.any<T?>(), eq(RemoteActionResult.createFromCache(cachedResult)))
        val execResult: ActionResult? = ActionResult.newBuilder().setExitCode(31).build()
        val succeeded: ExecuteResponse? = ExecuteResponse.newBuilder().setResult(execResult).build()
        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenReturn(succeeded)
        Mockito.doReturn(null)
            .`when`<Any?>(service)
            .downloadOutputs(ArgumentMatchers.any<T?>(), eq(RemoteActionResult.createFromResponse(succeeded)))

        val spawn: Spawn = newSimpleSpawn()

        val policy: SpawnExecutionContext = getSpawnContext(spawn)

        // act
        val res: SpawnResult = runner.exec(spawn, policy)

        // assert
        assertThat(res.status()).isEqualTo(Status.NON_ZERO_EXIT)
        assertThat(res.exitCode()).isEqualTo(31)

        Mockito.verify<Any?>(executor)
            .executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resultsDownloadFailureTriggersRemoteExecutionWithSkipCacheLookup() {
        // If downloading an action result fails, remote execution should be retried
        // with skip cache lookup enabled

        // arrange

        val runner: RemoteSpawnRunner = newSpawnRunner()
        val service: RemoteExecutionService? = runner.getRemoteExecutionService()

        Mockito.`when`<T?>(
            cache.downloadActionResult(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ActionKey::class.java),  /* inlineOutErr= */
                ArgumentMatchers.eq(false),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<Any?>())
        ))
        .thenReturn(null)
        val cachedResult: ActionResult? = ActionResult.newBuilder().setExitCode(0).build()
        val execResult: ActionResult? = ActionResult.newBuilder().setExitCode(31).build()
        val cachedResponse: ExecuteResponse? =
            ExecuteResponse.newBuilder().setResult(cachedResult).setCachedResult(true).build()
        val executedResponse: ExecuteResponse? = ExecuteResponse.newBuilder().setResult(execResult).build()
        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenReturn(cachedResponse)
            .thenReturn(executedResponse)
        val downloadFailure: java.lang.Exception =
            BulkTransferException(CacheNotFoundException(Digest.getDefaultInstance()))
        Mockito.doThrow(downloadFailure)
            .`when`<Any?>(service)
            .downloadOutputs(ArgumentMatchers.any<T?>(), eq(RemoteActionResult.createFromResponse(cachedResponse)))
        Mockito.doReturn(null)
            .`when`<Any?>(service)
            .downloadOutputs(ArgumentMatchers.any<T?>(), eq(RemoteActionResult.createFromResponse(executedResponse)))

        val spawn: Spawn = newSimpleSpawn()

        val policy: SpawnExecutionContext = getSpawnContext(spawn)

        // act
        val res: SpawnResult = runner.exec(spawn, policy)

        // assert
        assertThat(res.status()).isEqualTo(Status.NON_ZERO_EXIT)
        assertThat(res.exitCode()).isEqualTo(31)

        val requestCaptor: ArgumentCaptor<ExecuteRequest?> =
            ArgumentCaptor.forClass<ExecuteRequest?, ExecuteRequest?>(ExecuteRequest::class.java)
        Mockito.verify<Any?>(executor, Mockito.times(2))
            .executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                requestCaptor.capture(),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        val requests: MutableList<ExecuteRequest?> = requestCaptor.getAllValues()
        // first request should have been executed without skip cache lookup
        assertThat(requests.get(0).getSkipCacheLookup()).isFalse()
        // second should have been executed with skip cache lookup
        assertThat(requests.get(1).getSkipCacheLookup()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRemoteExecutionTimeout() {
        // If remote execution times out the SpawnResult status should be TIMEOUT.

        remoteOptions.remoteLocalFallback = false

        val runner: RemoteSpawnRunner = newSpawnRunner()
        val service: RemoteExecutionService? = runner.getRemoteExecutionService()

        val cachedResult: ActionResult? = ActionResult.newBuilder().setExitCode(0).build()
        Mockito.`when`<T?>(
            cache.downloadActionResult(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ActionKey::class.java),  /* inlineOutErr= */
                ArgumentMatchers.eq(false),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<Any?>())
        ))
        .thenReturn(null)
        val resp: ExecuteResponse =
            ExecuteResponse.newBuilder()
                .setResult(cachedResult)
                .setStatus(
                    com.google.rpc.Status.newBuilder()
                        .setCode(Code.DEADLINE_EXCEEDED.getNumber())
                        .build()
                )
                .build()
        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenThrow(IOException(ExecutionStatusException(resp.getStatus(), resp)))

        val spawn: Spawn = newSimpleSpawn()

        val policy: SpawnExecutionContext = getSpawnContext(spawn)

        val res: SpawnResult = runner.exec(spawn, policy)
        assertThat(res.status()).isEqualTo(Status.TIMEOUT)

        Mockito.verify<Any?>(executor)
            .executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        Mockito.verify<Any?>(service)
            .downloadOutputs(ArgumentMatchers.any<T?>(), eq(RemoteActionResult.createFromResponse(resp)))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRemoteExecutionTimeoutTimings() {
        // If remote execution times out the SpawnResult should still have the start and wall times
        // reported correctly.

        remoteOptions.remoteLocalFallback = false

        val runner: RemoteSpawnRunner = newSpawnRunner()
        val service: RemoteExecutionService? = runner.getRemoteExecutionService()

        val oneSecond: com.google.protobuf.Duration? = Durations.fromMillis(1000)
        val executionStart: Timestamp = Timestamp.getDefaultInstance()
        val executionCompleted: Timestamp? = Timestamps.add(executionStart, oneSecond)
        val executedMetadata: ExecutedActionMetadata? =
            ExecutedActionMetadata.newBuilder()
                .setExecutionStartTimestamp(executionStart)
                .setExecutionCompletedTimestamp(executionCompleted)
                .build()

        val cachedResult: ActionResult? =
            ActionResult.newBuilder().setExitCode(0).setExecutionMetadata(executedMetadata).build()
        Mockito.`when`<T?>(
            cache.downloadActionResult(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ActionKey::class.java),  /* inlineOutErr= */
                ArgumentMatchers.eq(false),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<Any?>())
        ))
        .thenReturn(null)
        val resp: ExecuteResponse =
            ExecuteResponse.newBuilder()
                .setResult(cachedResult)
                .setStatus(
                    com.google.rpc.Status.newBuilder()
                        .setCode(Code.DEADLINE_EXCEEDED.getNumber())
                        .build()
                )
                .build()
        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenThrow(IOException(ExecutionStatusException(resp.getStatus(), resp)))

        val spawn: Spawn = newSimpleSpawn()

        val policy: SpawnExecutionContext = getSpawnContext(spawn)

        val res: SpawnResult = runner.exec(spawn, policy)
        assertThat(res.status()).isEqualTo(Status.TIMEOUT)
        assertThat(res.getWallTimeInMs()).isEqualTo(1000)
        assertThat(res.getStartTime())
            .isEqualTo(Instant.ofEpochSecond(executionStart.getSeconds(), executionStart.getNanos()))

        Mockito.verify<Any?>(executor)
            .executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        Mockito.verify<Any?>(service)
            .downloadOutputs(ArgumentMatchers.any<T?>(), eq(RemoteActionResult.createFromResponse(resp)))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRemoteExecutionTimeoutDoesNotTriggerFallback() {
        // If remote execution times out the SpawnResult status should be TIMEOUT, regardess of local
        // fallback option.

        remoteOptions.remoteLocalFallback = true

        val runner: RemoteSpawnRunner = newSpawnRunner()
        val service: RemoteExecutionService? = runner.getRemoteExecutionService()

        val cachedResult: ActionResult? = ActionResult.newBuilder().setExitCode(0).build()
        Mockito.`when`<T?>(
            cache.downloadActionResult(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ActionKey::class.java),  /* inlineOutErr= */
                ArgumentMatchers.eq(false),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<Any?>())
        ))
        .thenReturn(null)
        val resp: ExecuteResponse =
            ExecuteResponse.newBuilder()
                .setResult(cachedResult)
                .setStatus(
                    com.google.rpc.Status.newBuilder()
                        .setCode(Code.DEADLINE_EXCEEDED.getNumber())
                        .build()
                )
                .build()
        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenThrow(IOException(ExecutionStatusException(resp.getStatus(), resp)))

        val spawn: Spawn = newSimpleSpawn()

        val policy: SpawnExecutionContext = getSpawnContext(spawn)

        val res: SpawnResult = runner.exec(spawn, policy)
        assertThat(res.status()).isEqualTo(Status.TIMEOUT)

        Mockito.verify<Any?>(executor)
            .executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        Mockito.verify<Any?>(service)
            .downloadOutputs(ArgumentMatchers.any<T?>(), eq(RemoteActionResult.createFromResponse(resp)))
        Mockito.verify<Any?>(localRunner, Mockito.never()).exec(< T > eq < T ? > (spawn), <T>eq<T?>(policy))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRemoteExecutionCommandFailureDoesNotTriggerFallback() {
        remoteOptions.remoteLocalFallback = true

        val runner: RemoteSpawnRunner = newSpawnRunner()
        val service: RemoteExecutionService? = runner.getRemoteExecutionService()

        Mockito.`when`<T?>(
            cache.downloadActionResult(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ActionKey::class.java),  /* inlineOutErr= */
                ArgumentMatchers.eq(false),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<Any?>())
        ))
        .thenReturn(null)
        val failed: ExecuteResponse? =
            ExecuteResponse.newBuilder()
                .setResult(ActionResult.newBuilder().setExitCode(33).build())
                .build()
        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenReturn(failed)

        val spawn: Spawn = newSimpleSpawn()

        val policy: SpawnExecutionContext = getSpawnContext(spawn)

        val res: SpawnResult = runner.exec(spawn, policy)
        assertThat(res.status()).isEqualTo(Status.NON_ZERO_EXIT)
        assertThat(res.exitCode()).isEqualTo(33)

        Mockito.verify<Any?>(executor)
            .executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        Mockito.verify<Any?>(service)
            .downloadOutputs(ArgumentMatchers.any<T?>(), eq(RemoteActionResult.createFromResponse(failed)))
        Mockito.verify<Any?>(localRunner, Mockito.never()).exec(< T > eq < T ? > (spawn), <T>eq<T?>(policy))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExitCode_executorfailure() {
        // If we get a failure due to the remote cache not working, the exit code should be
        // ExitCode.REMOTE_ERROR.

        remoteOptions.remoteLocalFallback = false

        val runner: RemoteSpawnRunner = newSpawnRunner()

        Mockito.`when`<T?>(
            cache.downloadActionResult(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ActionKey::class.java),  /* inlineOutErr= */
                ArgumentMatchers.eq(false),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<Any?>())
        ))
        .thenReturn(null)
        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenThrow(IOException("reasons"))

        val spawn: Spawn = newSimpleSpawn()
        val policy: SpawnExecutionContext = getSpawnContext(spawn)

        val result: SpawnResult = runner.exec(spawn, policy)
        assertThat(result.exitCode()).isEqualTo(ExitCode.REMOTE_ERROR.numericExitCode)
        com.google.common.truth.Subject.contains("reasons")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExitCode_executionfailure() {
        // If we get a failure due to the remote executor not working, the exit code should be
        // ExitCode.REMOTE_ERROR.

        remoteOptions.remoteLocalFallback = false

        val runner: RemoteSpawnRunner = newSpawnRunner()

        Mockito.`when`<T?>(
            cache.downloadActionResult(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ActionKey::class.java),  /* inlineOutErr= */
                ArgumentMatchers.eq(false),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<Any?>())
        ))
        .thenThrow(IOException("reasons"))

        val spawn: Spawn = newSimpleSpawn()
        val policy: SpawnExecutionContext = getSpawnContext(spawn)

        val result: SpawnResult = runner.exec(spawn, policy)
        assertThat(result.exitCode()).isEqualTo(ExitCode.REMOTE_ERROR.numericExitCode)
        com.google.common.truth.Subject.contains("reasons")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExitCode_remoteMessage() {
        remoteOptions.remoteLocalFallback = false

        val runner: RemoteSpawnRunner = newSpawnRunner()

        val cause: ExecutionStatusException =
            ExecutionStatusException(
                com.google.rpc.Status.getDefaultInstance(),
                ExecuteResponse.newBuilder().setMessage("beep and indeed boop").build()
            )

        Mockito.`when`<T?>(
            cache.downloadActionResult(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ActionKey::class.java),  /* inlineOutErr= */
                ArgumentMatchers.eq(false),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<Any?>())
        ))
        .thenReturn(null)
        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenThrow(IOException("reasons", cause))

        val spawn: Spawn = newSimpleSpawn()
        val policy: SpawnExecutionContext = getSpawnContext(spawn)

        val result: SpawnResult = runner.exec(spawn, policy)
        assertThat(result.exitCode()).isEqualTo(ExitCode.REMOTE_ERROR.numericExitCode)
        com.google.common.truth.Subject.contains("beep and indeed boop")
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
        val remoteExecutionService: RemoteExecutionService =
            RemoteExecutionService(
                reporter,  /* verboseFailures= */
                true,
                execRoot,
                RemotePathResolver.createDefault(execRoot),
                "build-req-id",
                "command-id",
                TestConstants.WORKSPACE_NAME,
                digestUtil,
                remoteOptions,
                executionOptions,
                cache,
                executor,
                tempPathGenerator,  /* captureCorruptedOutputsDir= */
                null,
                remoteOutputChecker,
                < T > mock < T ? > (OutputService::class.java),
        com.google.common.collect.Sets.newConcurrentHashSet<E?>())
        val runner: RemoteSpawnRunner =
            RemoteSpawnRunner(
                remoteOptions,  /* verboseFailures= */
                true,  /* cmdlineReporter= */
                null,
                retryService,
                logDir,
                remoteExecutionService,
                digestUtil
            )

        val succeeded: ExecuteResponse? =
            ExecuteResponse.newBuilder()
                .setResult(ActionResult.newBuilder().setExitCode(0).build())
                .build()
        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenReturn(succeeded)

        val args: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("--foo", "--bar")
        val input: ParamFileActionInput =
            ParamFileActionInput(
                PathFragment.create("out/param_file"), args, ParameterFileType.UNQUOTED
            )
        val spawn: Spawn =
            SimpleSpawn(
                FakeOwner("foo", "bar", "//dummy:label"),  /* arguments= */
                com.google.common.collect.ImmutableList.of<E?>(),  /* environment= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* executionInfo= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* inputs= */
                NestedSetBuilder.create(Order.STABLE_ORDER, input),  /* outputs= */
                com.google.common.collect.ImmutableSet.of<ActionInput?>(),
                ResourceSet.ZERO
            )
        val policy: SpawnExecutionContext = getSpawnContext(spawn)
        val res: SpawnResult = runner.exec(spawn, policy)
        assertThat(res.status()).isEqualTo(Status.SUCCESS)
        val paramFile: Path = execRoot.getRelative("out/param_file")
        assertThat(paramFile.exists()).isTrue()
        paramFile.getInputStream().use { inputStream ->
            Truth.assertThat<String?>(
                String(
                    com.google.common.io.ByteStreams.toByteArray(inputStream),
                    java.nio.charset.StandardCharsets.UTF_8
                ).split("\n")
            )
                .asList()
                .containsExactly("--foo", "--bar")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDownloadMinimalOnCacheHit() {
        // arrange
        remoteOptions.remoteOutputsMode = RemoteOutputsMode.MINIMAL

        val succeededAction: ActionResult? = ActionResult.newBuilder().setExitCode(0).build()
        val actionResult: RemoteActionResult? =
            RemoteActionResult.createFromCache(CachedActionResult.remote(succeededAction))

        val runner: RemoteSpawnRunner = newSpawnRunner()
        val service: RemoteExecutionService? = runner.getRemoteExecutionService()
        Mockito.doReturn(actionResult).`when`<Any?>(service).lookupCache(ArgumentMatchers.any<T?>())
        Mockito.doReturn(null).`when`<Any?>(service)
            .downloadOutputs(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

        val spawn: Spawn = newSimpleSpawn()
        val policy: SpawnExecutionContext = getSpawnContext(spawn)

        // act
        val result: SpawnResult = runner.exec(spawn, policy)
        assertThat(result.exitCode()).isEqualTo(0)
        assertThat(result.status()).isEqualTo(Status.SUCCESS)

        // assert
        Mockito.verify<Any?>(service).downloadOutputs(ArgumentMatchers.any<T?>(), < T > eq < T ? > (actionResult))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDownloadMinimalOnCacheMiss() {
        // arrange
        remoteOptions.remoteOutputsMode = RemoteOutputsMode.MINIMAL

        val succeededAction: ActionResult? = ActionResult.newBuilder().setExitCode(0).build()
        val succeeded: ExecuteResponse? = ExecuteResponse.newBuilder().setResult(succeededAction).build()
        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenReturn(succeeded)

        val runner: RemoteSpawnRunner = newSpawnRunner()
        val service: RemoteExecutionService? = runner.getRemoteExecutionService()
        Mockito.doReturn(null).`when`<Any?>(service)
            .downloadOutputs(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

        val spawn: Spawn = newSimpleSpawn()
        val policy: FakeSpawnExecutionContext = getSpawnContext(spawn)

        // act
        val result: SpawnResult = runner.exec(spawn, policy)
        assertThat(result.exitCode()).isEqualTo(0)
        assertThat(result.status()).isEqualTo(Status.SUCCESS)

        // assert
        Mockito.verify<Any?>(executor)
            .executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        Mockito.verify<Any?>(service)
            .downloadOutputs(ArgumentMatchers.any<T?>(), eq(RemoteActionResult.createFromResponse(succeeded)))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDownloadMinimalIoError() {
        // arrange
        remoteOptions.remoteOutputsMode = RemoteOutputsMode.MINIMAL

        val succeededAction: ActionResult? = ActionResult.newBuilder().setExitCode(0).build()
        val cachedActionResult: RemoteActionResult? =
            RemoteActionResult.createFromCache(CachedActionResult.remote(succeededAction))
        val downloadFailure: IOException = IOException("downloadMinimal failed")

        val runner: RemoteSpawnRunner = newSpawnRunner()
        val service: RemoteExecutionService? = runner.getRemoteExecutionService()

        doReturn(RemoteActionResult.createFromCache(CachedActionResult.remote(succeededAction)))
            .`when`<Any?>(service)
            .lookupCache(ArgumentMatchers.any<T?>())
        Mockito.doThrow(downloadFailure).`when`<Any?>(service)
            .downloadOutputs(ArgumentMatchers.any<T?>(), < T > eq < T ? > (cachedActionResult))

        val spawn: Spawn = newSimpleSpawn()
        val policy: FakeSpawnExecutionContext = getSpawnContext(spawn)

        // act
        val result: SpawnResult = runner.exec(spawn, policy)
        assertThat(result.getFailureMessage()).isEqualTo(downloadFailure.getMessage())

        // assert
        Mockito.verify<Any?>(service).downloadOutputs(ArgumentMatchers.any<T?>(), < T > eq < T ? > (cachedActionResult))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDigest() {
        val runner: RemoteSpawnRunner = newSpawnRunner()
        val service: RemoteExecutionService? = runner.getRemoteExecutionService()

        val resp: ExecuteResponse? =
            ExecuteResponse.newBuilder()
                .setResult(ActionResult.newBuilder().setExitCode(0).build())
                .build()
        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenReturn(resp)

        val spawn: Spawn = newSimpleSpawn()
        val policy: FakeSpawnExecutionContext = getSpawnContext(spawn)

        val res: SpawnResult = runner.exec(spawn, policy)
        assertThat(res.status()).isEqualTo(Status.SUCCESS)

        val requestCaptor: ArgumentCaptor<RemoteAction?> =
            ArgumentCaptor.forClass<RemoteAction?, RemoteAction?>(RemoteAction::class.java)

        Mockito.verify<Any?>(service)
            .executeRemotely(
                requestCaptor.capture(),
                ArgumentMatchers.anyBoolean(),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )

        assertThat(policy.getDigest())
            .isEqualTo(digestUtil.asSpawnLogProto(requestCaptor.getValue().getActionKey()))

        assertThat(res.getDigest())
            .isEqualTo(digestUtil.asSpawnLogProto(requestCaptor.getValue().getActionKey()))
    }

    @org.junit.Test
    fun accountingDisabledWithoutWorker() {
        val spawnMetrics: SpawnMetrics.Builder? = Mockito.mock<SpawnMetrics.Builder?>(SpawnMetrics.Builder::class.java)
        RemoteSpawnRunner.spawnMetricsAccounting(
            spawnMetrics, ExecutedActionMetadata.getDefaultInstance()
        )
        Mockito.verifyNoMoreInteractions(spawnMetrics)
    }

    @org.junit.Test
    fun accountingAddsDurationsForStages() {
        val builder: SpawnMetrics.Builder =
            SpawnMetrics.Builder.forRemoteExec()
                .setQueueTimeInMs(1 * 1000)
                .setSetupTimeInMs(2 * 1000)
                .setExecutionWallTimeInMs(2 * 1000)
                .setProcessOutputsTimeInMs(2 * 1000)
        val queued: Timestamp? = Timestamp.getDefaultInstance()
        val oneSecond: com.google.protobuf.Duration? = Durations.fromMillis(1000)
        val workerStart: Timestamp? = Timestamps.add(queued, oneSecond)
        val executionStart: Timestamp? = Timestamps.add(workerStart, oneSecond)
        val executionCompleted: Timestamp? = Timestamps.add(executionStart, oneSecond)
        val outputUploadStart: Timestamp? = Timestamps.add(executionCompleted, oneSecond)
        val outputUploadComplete: Timestamp? = Timestamps.add(outputUploadStart, oneSecond)
        val executedMetadata: ExecutedActionMetadata? =
            ExecutedActionMetadata.newBuilder()
                .setWorker("test worker")
                .setQueuedTimestamp(queued)
                .setWorkerStartTimestamp(workerStart)
                .setExecutionStartTimestamp(executionStart)
                .setExecutionCompletedTimestamp(executionCompleted)
                .setOutputUploadStartTimestamp(outputUploadStart)
                .setOutputUploadCompletedTimestamp(outputUploadComplete)
                .build()
        RemoteSpawnRunner.spawnMetricsAccounting(builder, executedMetadata)
        val spawnMetrics: SpawnMetrics = builder.build()
        // remote queue time is accumulated
        assertThat(spawnMetrics.queueTimeInMs()).isEqualTo(2 * 1000L)
        // setup time is substituted
        assertThat(spawnMetrics.setupTimeInMs()).isEqualTo(1 * 1000L)
        // execution time is unspecified, assume substituted
        assertThat(spawnMetrics.executionWallTimeInMs()).isEqualTo(1 * 1000L)
        // ProcessOutputs time is unspecified, assume substituted
        assertThat(spawnMetrics.processOutputsTimeInMs()).isEqualTo(1 * 1000L)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun shouldReportCheckingCacheBeforeScheduling() {
        // Prepare a faked/mocked remote SpawnExecutionContext.
        val runner: RemoteSpawnRunner = newSpawnRunner()
        val service: RemoteExecutionService? = runner.getRemoteExecutionService()
        val succeeded: ExecuteResponse? =
            ExecuteResponse.newBuilder()
                .setResult(ActionResult.newBuilder().setExitCode(0).build())
                .build()

        val spawn: Spawn = newSimpleSpawn()
        val policy: SpawnExecutionContext = Mockito.mock<SpawnExecutionContext>(SpawnExecutionContext::class.java)
        Mockito.`when`<Any?>(policy.timeout).thenReturn(java.time.Duration.ZERO)

        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenAnswer(
                Answer { invocationOnMock: InvocationOnMock? ->
                    val receiver: OperationObserver = invocationOnMock.getArgument<OperationObserver>(2)
                    receiver.onNext(Operation.getDefaultInstance())
                    succeeded
                })

        Mockito.doReturn(null).`when`<Any?>(service)
            .downloadOutputs(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

        // Run the faked spawn.
        val res: SpawnResult = runner.exec(spawn, policy)

        // Verify expected behavior with mocked remote SpawnExecutionContext.
        assertThat(res.status()).isEqualTo(Status.SUCCESS)
        Mockito.verify<Any?>(executor)
            .executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        val reportOrder: InOrder = Mockito.inOrder(policy)
        reportOrder.verify<Any?>(policy, Mockito.times(1)).report(SpawnCheckingCacheEvent.create("remote"))
        reportOrder.verify<Any?>(policy, Mockito.times(1)).report(SpawnSchedulingEvent.create("remote"))
        reportOrder.verify<Any?>(policy, Mockito.times(1)).report(SpawnExecutingEvent.create("remote"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun shouldReportExecutingStatusWithoutMetadata() {
        // arrange
        val runner: RemoteSpawnRunner = newSpawnRunner()
        val service: RemoteExecutionService? = runner.getRemoteExecutionService()
        val succeeded: ExecuteResponse? =
            ExecuteResponse.newBuilder()
                .setResult(ActionResult.newBuilder().setExitCode(0).build())
                .build()

        val spawn: Spawn = newSimpleSpawn()
        val policy: SpawnExecutionContext = Mockito.mock<SpawnExecutionContext>(SpawnExecutionContext::class.java)
        Mockito.`when`<Any?>(policy.timeout).thenReturn(java.time.Duration.ZERO)

        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenAnswer(
                Answer { invocationOnMock: InvocationOnMock? ->
                    val receiver: OperationObserver = invocationOnMock.getArgument<OperationObserver>(2)
                    Mockito.verify<Any?>(policy, Mockito.never()).report(SpawnExecutingEvent.create("remote"))
                    receiver.onNext(Operation.getDefaultInstance())
                    succeeded
                })

        Mockito.doReturn(null).`when`<Any?>(service)
            .downloadOutputs(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

        // act
        val res: SpawnResult = runner.exec(spawn, policy)
        assertThat(res.status()).isEqualTo(Status.SUCCESS)

        // assert
        Mockito.verify<Any?>(executor)
            .executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        val reportOrder: InOrder = Mockito.inOrder(policy)
        reportOrder.verify<Any?>(policy, Mockito.times(1)).report(SpawnSchedulingEvent.create("remote"))
        reportOrder.verify<Any?>(policy, Mockito.times(1)).report(SpawnExecutingEvent.create("remote"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun shouldReportExecutingStatusAfterGotExecutingStageFromMetadata() {
        // arrange
        val runner: RemoteSpawnRunner = newSpawnRunner()
        val service: RemoteExecutionService? = runner.getRemoteExecutionService()
        val succeeded: ExecuteResponse? =
            ExecuteResponse.newBuilder()
                .setResult(ActionResult.newBuilder().setExitCode(0).build())
                .build()

        val spawn: Spawn = newSimpleSpawn()
        val policy: SpawnExecutionContext = Mockito.mock<SpawnExecutionContext>(SpawnExecutionContext::class.java)
        Mockito.`when`<Any?>(policy.timeout).thenReturn(java.time.Duration.ZERO)

        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenAnswer(
                Answer { invocationOnMock: InvocationOnMock? ->
                    val receiver: OperationObserver = invocationOnMock.getArgument<OperationObserver>(2)
                    val queued: Operation? =
                        Operation.newBuilder()
                            .setMetadata(
                                Any.pack(
                                    ExecuteOperationMetadata.newBuilder().setStage(Value.QUEUED).build()
                                )
                            )
                            .build()
                    receiver.onNext(queued)
                    Mockito.verify<Any?>(policy, Mockito.never()).report(SpawnExecutingEvent.create("remote"))

                    val executing: Operation? =
                        Operation.newBuilder()
                            .setMetadata(
                                Any.pack(
                                    ExecuteOperationMetadata.newBuilder()
                                        .setStage(Value.EXECUTING)
                                        .build()
                                )
                            )
                            .build()
                    receiver.onNext(executing)
                    succeeded
                })

        Mockito.doReturn(null).`when`<Any?>(service)
            .downloadOutputs(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

        // act
        val res: SpawnResult = runner.exec(spawn, policy)
        assertThat(res.status()).isEqualTo(Status.SUCCESS)

        // assert
        Mockito.verify<Any?>(executor)
            .executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        val reportOrder: InOrder = Mockito.inOrder(policy)
        reportOrder.verify<Any?>(policy, Mockito.times(1)).report(SpawnSchedulingEvent.create("remote"))
        reportOrder.verify<Any?>(policy, Mockito.times(1)).report(SpawnExecutingEvent.create("remote"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun shouldIgnoreInvalidMetadata() {
        // arrange
        val runner: RemoteSpawnRunner = newSpawnRunner()
        val service: RemoteExecutionService? = runner.getRemoteExecutionService()
        val succeeded: ExecuteResponse? =
            ExecuteResponse.newBuilder()
                .setResult(ActionResult.newBuilder().setExitCode(0).build())
                .build()

        val spawn: Spawn = newSimpleSpawn()
        val policy: SpawnExecutionContext = Mockito.mock<SpawnExecutionContext>(SpawnExecutionContext::class.java)
        Mockito.`when`<Any?>(policy.timeout).thenReturn(java.time.Duration.ZERO)

        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenAnswer(
                Answer { invocationOnMock: InvocationOnMock? ->
                    val receiver: OperationObserver = invocationOnMock.getArgument<OperationObserver>(2)
                    val operation: Operation? =
                        Operation.newBuilder()
                            .setMetadata( // Anything that is not ExecutionOperationMetadata
                                Any.pack(Operation.getDefaultInstance())
                            )
                            .build()
                    receiver.onNext(operation)
                    succeeded
                })

        Mockito.doReturn(null).`when`<Any?>(service)
            .downloadOutputs(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

        // act
        val res: SpawnResult = runner.exec(spawn, policy)
        assertThat(res.status()).isEqualTo(Status.SUCCESS)

        // assert
        Mockito.verify<Any?>(executor)
            .executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        val reportOrder: InOrder = Mockito.inOrder(policy)
        reportOrder.verify<Any?>(policy, Mockito.times(1)).report(SpawnSchedulingEvent.create("remote"))
        reportOrder.verify<Any?>(policy, Mockito.times(1)).report(SpawnExecutingEvent.create("remote"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun shouldReportExecutingStatusIfNoExecutingStatusFromMetadata() {
        // arrange
        val runner: RemoteSpawnRunner = newSpawnRunner()
        val service: RemoteExecutionService? = runner.getRemoteExecutionService()
        val succeeded: ExecuteResponse? =
            ExecuteResponse.newBuilder()
                .setResult(ActionResult.newBuilder().setExitCode(0).build())
                .build()

        val spawn: Spawn = newSimpleSpawn()
        val policy: SpawnExecutionContext = Mockito.mock<SpawnExecutionContext>(SpawnExecutionContext::class.java)
        Mockito.`when`<Any?>(policy.timeout).thenReturn(java.time.Duration.ZERO)

        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenAnswer(
                Answer { invocationOnMock: InvocationOnMock? ->
                    val receiver: OperationObserver = invocationOnMock.getArgument<OperationObserver>(2)
                    val completed: Operation? =
                        Operation.newBuilder()
                            .setMetadata(
                                Any.pack(
                                    ExecuteOperationMetadata.newBuilder()
                                        .setStage(Value.COMPLETED)
                                        .build()
                                )
                            )
                            .build()
                    receiver.onNext(completed)
                    succeeded
                })
        Mockito.doReturn(null).`when`<Any?>(service)
            .downloadOutputs(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

        // act
        val res: SpawnResult = runner.exec(spawn, policy)
        assertThat(res.status()).isEqualTo(Status.SUCCESS)

        // assert
        Mockito.verify<Any?>(executor)
            .executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        val reportOrder: InOrder = Mockito.inOrder(policy)
        reportOrder.verify<Any?>(policy, Mockito.times(1)).report(SpawnSchedulingEvent.create("remote"))
        reportOrder.verify<Any?>(policy, Mockito.times(1)).report(SpawnExecutingEvent.create("remote"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun shouldReportExecutingStatusEvenNoOperationFromServer() {
        // arrange
        val runner: RemoteSpawnRunner = newSpawnRunner()
        val service: RemoteExecutionService? = runner.getRemoteExecutionService()
        val succeeded: ExecuteResponse? =
            ExecuteResponse.newBuilder()
                .setResult(ActionResult.newBuilder().setExitCode(0).build())
                .build()

        val spawn: Spawn = newSimpleSpawn()
        val policy: SpawnExecutionContext = Mockito.mock<SpawnExecutionContext>(SpawnExecutionContext::class.java)
        Mockito.`when`<Any?>(policy.timeout).thenReturn(java.time.Duration.ZERO)

        Mockito.`when`<T?>(
            executor.executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        )
            .thenReturn(succeeded)
        Mockito.doReturn(null).`when`<Any?>(service)
            .downloadOutputs(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

        // act
        val res: SpawnResult = runner.exec(spawn, policy)
        assertThat(res.status()).isEqualTo(Status.SUCCESS)

        // assert
        Mockito.verify<Any?>(executor)
            .executeRemotely(
                ArgumentMatchers.any<T?>(RemoteActionExecutionContext::class.java),
                ArgumentMatchers.any<T?>(ExecuteRequest::class.java),
                ArgumentMatchers.any<T?>(OperationObserver::class.java)
            )
        val reportOrder: InOrder = Mockito.inOrder(policy)
        reportOrder.verify<Any?>(policy, Mockito.times(1)).report(SpawnSchedulingEvent.create("remote"))
        reportOrder.verify<Any?>(policy, Mockito.times(1)).report(SpawnExecutingEvent.create("remote"))
    }

    private fun newSpawnRunner(): RemoteSpawnRunner {
        return newSpawnRunner(executor, RemotePathResolver.createDefault(execRoot))
    }

    private fun newSpawnRunner(remotePathResolver: RemotePathResolver?): RemoteSpawnRunner {
        return newSpawnRunner(executor, remotePathResolver)
    }

    private fun newSpawnRunner(
        executor: RemoteExecutionClient?, remotePathResolver: RemotePathResolver?
    ): RemoteSpawnRunner {
        val service: RemoteExecutionService? =
            spy(
                RemoteExecutionService(
                    reporter,  /* verboseFailures= */
                    true,
                    execRoot,
                    remotePathResolver,
                    "build-req-id",
                    "command-id",
                    TestConstants.WORKSPACE_NAME,
                    digestUtil,
                    remoteOptions,
                    com.google.devtools.common.options.Options.getDefaults<O?>(ExecutionOptions::class.java),
                    cache,
                    executor,
                    tempPathGenerator,  /* captureCorruptedOutputsDir= */
                    null,
                    remoteOutputChecker,
                    < T > mock < T ? > (OutputService::class.java),
                com.google.common.collect.Sets.newConcurrentHashSet<E?>()
            ))

        return RemoteSpawnRunner(
            remoteOptions,  /* verboseFailures= */
            false,
            reporter,
            retryService,
            logDir,
            service,
            digestUtil
        )
    }

    companion object {
        private val NO_CACHE: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>(ExecutionRequirements.NO_CACHE, "")

        // The action key of the Spawn returned by newSimpleSpawn().
        private const val SIMPLE_ACTION_ID = "31aea267dc597b047a9b6993100415b6406f82822318dc8988e4164a535b51ee"

        private fun newSimpleSpawn(vararg outputs: Artifact?): Spawn {
            return simpleSpawnWithExecutionInfo(com.google.common.collect.ImmutableMap.of<String?, String?>(), *outputs)
        }

        private fun simpleSpawnWithExecutionInfo(
            executionInfo: com.google.common.collect.ImmutableMap<String?, String?>?, vararg outputs: Artifact?
        ): SimpleSpawn {
            return SimpleSpawn(
                FakeOwner("foo", "bar", "//dummy:label"),  /* arguments= */
                com.google.common.collect.ImmutableList.of<E?>(),  /* environment= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* executionInfo= */
                executionInfo,  /* inputs= */
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),  /* outputs= */
                com.google.common.collect.ImmutableSet.< E > copyOf < E ? > (outputs),
                ResourceSet.ZERO
            )
        }
    }
}
