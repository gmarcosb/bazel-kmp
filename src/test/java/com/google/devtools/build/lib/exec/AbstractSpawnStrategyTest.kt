// Copyright 2017 The Bazel Authors. All Rights Reserved.
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
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.actions.ActionExecutionContext

/** Tests for [BlazeExecutor].  */
@RunWith(JUnit4::class)
class AbstractSpawnStrategyTest {
    private class TestedSpawnStrategy(spawnRunner: SpawnRunner?) : AbstractSpawnStrategy(
        spawnRunner,
        com.google.devtools.common.options.Options.getDefaults<O?>(ExecutionOptions::class.java)
    )

    private val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
    private val execRoot: Path? = fs.getPath("/execroot")

    @org.mockito.Mock
    private val spawnRunner: SpawnRunner? = null

    @org.mockito.Mock
    private val actionExecutionContext: ActionExecutionContext? = null
    private var eventHandler: StoredEventHandler? = null
    private val clock: com.google.devtools.build.lib.testutil.ManualClock =
        com.google.devtools.build.lib.testutil.ManualClock()

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        eventHandler = StoredEventHandler()
        Mockito.`when`<T?>(actionExecutionContext.getEventHandler()).thenReturn(eventHandler)
        Mockito.`when`<T?>(actionExecutionContext.getClock()).thenReturn(clock)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testZeroExit() {
        Mockito.`when`<T?>(actionExecutionContext.getContext(< T > eq < T ? > (SpawnCache::class.java))).thenReturn(SpawnCache.NO_CACHE)
        Mockito.`when`<T?>(actionExecutionContext.getExecRoot()).thenReturn(execRoot)
        val spawnResult: SpawnResult? =
            Builder().setStatus(Status.SUCCESS).setRunnerName("test").build()
        Mockito.`when`<T?>(
            spawnRunner.exec(
                ArgumentMatchers.any<T?>(Spawn::class.java),
                ArgumentMatchers.any<T?>(SpawnExecutionContext::class.java)
            )
        )
            .thenReturn(spawnResult)

        val spawnResults: MutableList<SpawnResult?>? =
            TestedSpawnStrategy(spawnRunner).exec(SIMPLE_SPAWN, actionExecutionContext)

        Truth.assertThat(spawnResults).containsExactly(spawnResult)

        // Must only be called exactly once.
        Mockito.verify<Any?>(spawnRunner).exec(
            ArgumentMatchers.any<T?>(Spawn::class.java),
            ArgumentMatchers.any<T?>(SpawnExecutionContext::class.java)
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEventPosting() {
        Mockito.`when`<T?>(actionExecutionContext.getContext(< T > eq < T ? > (SpawnCache::class.java))).thenReturn(SpawnCache.NO_CACHE)
        Mockito.`when`<T?>(actionExecutionContext.getExecRoot()).thenReturn(execRoot)
        val spawnResult: SpawnResult? =
            Builder().setStatus(Status.SUCCESS).setRunnerName("test").build()
        val beforeTime: Instant? = Instant.ofEpochMilli(clock.currentTimeMillis())
        Mockito.doAnswer(
            Answer { invocation: InvocationOnMock? ->
                clock.advanceMillis(1)
                spawnResult
            })
            .`when`<Any?>(spawnRunner)
            .exec(
                ArgumentMatchers.any<T?>(Spawn::class.java),
                ArgumentMatchers.any<T?>(SpawnExecutionContext::class.java)
            )

        val spawnResults: com.google.common.collect.ImmutableList<SpawnResult?>? =
            TestedSpawnStrategy(spawnRunner).exec(SIMPLE_SPAWN, actionExecutionContext)

        Truth.assertThat(spawnResults).containsExactly(spawnResult)
        // Must only be called exactly once.
        Mockito.verify<Any?>(spawnRunner).exec(
            ArgumentMatchers.any<T?>(Spawn::class.java),
            ArgumentMatchers.any<T?>(SpawnExecutionContext::class.java)
        )
        Truth.assertThat(eventHandler.getPosts()).hasSize(1)
        val event: SpawnExecutedEvent = eventHandler.getPosts().get(0) as SpawnExecutedEvent
        assertThat(event.getStartTimeInstant()).isEqualTo(beforeTime)
        assertThat(event.getSpawnResult()).isEqualTo(spawnResult)
        assertThat(event.getExitCode()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonZeroExit() {
        Mockito.`when`<T?>(actionExecutionContext.getContext(< T > eq < T ? > (SpawnCache::class.java))).thenReturn(SpawnCache.NO_CACHE)
        Mockito.`when`<T?>(actionExecutionContext.getExecRoot()).thenReturn(execRoot)
        val result: SpawnResult? =
            Builder()
                .setStatus(Status.NON_ZERO_EXIT)
                .setExitCode(1)
                .setFailureDetail(NON_ZERO_EXIT_DETAILS)
                .setRunnerName("test")
                .build()
        Mockito.`when`<T?>(
            spawnRunner.exec(
                ArgumentMatchers.any<T?>(Spawn::class.java),
                ArgumentMatchers.any<T?>(SpawnExecutionContext::class.java)
            )
        ).thenReturn(result)

        val e: SpawnExecException =
            org.junit.Assert.assertThrows<T>(
                SpawnExecException::class.java,
                org.junit.function.ThrowingRunnable { // Ignoring the List<SpawnResult> return value.
                    TestedSpawnStrategy(spawnRunner).exec(SIMPLE_SPAWN, actionExecutionContext)
                })
        assertThat(e.getSpawnResult()).isSameInstanceAs(result)
        // Must only be called exactly once.
        Mockito.verify<Any?>(spawnRunner).exec(
            ArgumentMatchers.any<T?>(Spawn::class.java),
            ArgumentMatchers.any<T?>(SpawnExecutionContext::class.java)
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCacheHit() {
        val cache: SpawnCache = Mockito.mock<SpawnCache>(SpawnCache::class.java)
        val spawnResult: SpawnResult? =
            Builder().setStatus(Status.SUCCESS).setRunnerName("test").build()
        Mockito.`when`<T?>(
            cache.lookup(
                ArgumentMatchers.any<T?>(Spawn::class.java),
                ArgumentMatchers.any<T?>(SpawnExecutionContext::class.java)
            )
        )
            .thenReturn(SpawnCache.success(spawnResult))
        Mockito.`when`<T?>(actionExecutionContext.getContext(< T > eq < T ? > (SpawnCache::class.java))).thenReturn(cache)
        Mockito.`when`<T?>(actionExecutionContext.getExecRoot()).thenReturn(execRoot)

        val spawnResults: MutableList<SpawnResult?>? =
            TestedSpawnStrategy(spawnRunner).exec(SIMPLE_SPAWN, actionExecutionContext)
        Truth.assertThat(spawnResults).containsExactly(spawnResult)
        Mockito.verify<Any?>(spawnRunner, Mockito.never()).exec(
            ArgumentMatchers.any<T?>(Spawn::class.java),
            ArgumentMatchers.any<T?>(SpawnExecutionContext::class.java)
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCacheMiss() {
        val cache: SpawnCache = Mockito.mock<SpawnCache>(SpawnCache::class.java)
        val entry: CacheHandle = Mockito.mock<CacheHandle>(CacheHandle::class.java)
        Mockito.`when`<T?>(
            cache.lookup(
                ArgumentMatchers.any<T?>(Spawn::class.java),
                ArgumentMatchers.any<T?>(SpawnExecutionContext::class.java)
            )
        ).thenReturn(entry)
        Mockito.`when`<T?>(entry.hasResult()).thenReturn(false)
        Mockito.`when`<T?>(entry.willStore()).thenReturn(true)

        Mockito.`when`<T?>(actionExecutionContext.getContext(< T > eq < T ? > (SpawnCache::class.java))).thenReturn(cache)
        Mockito.`when`<T?>(actionExecutionContext.getExecRoot()).thenReturn(execRoot)
        val spawnResult: SpawnResult? =
            Builder().setStatus(Status.SUCCESS).setRunnerName("test").build()
        Mockito.`when`<T?>(
            spawnRunner.exec(
                ArgumentMatchers.any<T?>(Spawn::class.java),
                ArgumentMatchers.any<T?>(SpawnExecutionContext::class.java)
            )
        )
            .thenReturn(spawnResult)

        val spawnResults: MutableList<SpawnResult?>? =
            TestedSpawnStrategy(spawnRunner).exec(SIMPLE_SPAWN, actionExecutionContext)

        Truth.assertThat(spawnResults).containsExactly(spawnResult)

        // Must only be called exactly once.
        Mockito.verify<Any?>(spawnRunner).exec(
            ArgumentMatchers.any<T?>(Spawn::class.java),
            ArgumentMatchers.any<T?>(SpawnExecutionContext::class.java)
        )
        Mockito.verify<Any?>(entry).store(< T > eq < T ? > (spawnResult))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExec_whenLocalCaches_usesNoCache() {
        Mockito.`when`<T?>(spawnRunner.handlesCaching()).thenReturn(true)

        val cache: SpawnCache? = Mockito.mock<SpawnCache?>(SpawnCache::class.java)

        Mockito.`when`<T?>(actionExecutionContext.getContext(< T > eq < T ? > (SpawnCache::class.java))).thenReturn(cache)
        Mockito.`when`<T?>(actionExecutionContext.getExecRoot()).thenReturn(execRoot)
        val spawnResult: SpawnResult? =
            Builder().setStatus(Status.SUCCESS).setRunnerName("test").build()
        Mockito.`when`<T?>(
            spawnRunner.exec(
                ArgumentMatchers.any<T?>(Spawn::class.java),
                ArgumentMatchers.any<T?>(SpawnExecutionContext::class.java)
            )
        )
            .thenReturn(spawnResult)

        val spawnResults: MutableList<SpawnResult?>? =
            TestedSpawnStrategy(spawnRunner).exec(SIMPLE_SPAWN, actionExecutionContext)

        Truth.assertThat(spawnResults).containsExactly(spawnResult)

        // Must only be called exactly once.
        Mockito.verify<Any?>(spawnRunner).exec(
            ArgumentMatchers.any<T?>(Spawn::class.java),
            ArgumentMatchers.any<T?>(SpawnExecutionContext::class.java)
        )
        Mockito.verifyNoInteractions(cache)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExec_usefulCacheInDynamicExecution() {
        Mockito.`when`<T?>(spawnRunner.handlesCaching()).thenReturn(false)

        val cache: SpawnCache = Mockito.mock<SpawnCache>(SpawnCache::class.java)
        Mockito.`when`<T?>(cache.usefulInDynamicExecution()).thenReturn(true)
        val entry: CacheHandle = Mockito.mock<CacheHandle>(CacheHandle::class.java)
        Mockito.`when`<T?>(
            cache.lookup(
                ArgumentMatchers.any<T?>(Spawn::class.java),
                ArgumentMatchers.any<T?>(SpawnExecutionContext::class.java)
            )
        ).thenReturn(entry)
        Mockito.`when`<T?>(entry.hasResult()).thenReturn(false)
        Mockito.`when`<T?>(entry.willStore()).thenReturn(true)

        Mockito.`when`<T?>(actionExecutionContext.getContext(< T > eq < T ? > (SpawnCache::class.java))).thenReturn(cache)
        Mockito.`when`<T?>(actionExecutionContext.getExecRoot()).thenReturn(execRoot)
        val spawnResult: SpawnResult? =
            Builder().setStatus(Status.SUCCESS).setRunnerName("test").build()
        Mockito.`when`<T?>(
            spawnRunner.exec(
                ArgumentMatchers.any<T?>(Spawn::class.java),
                ArgumentMatchers.any<T?>(SpawnExecutionContext::class.java)
            )
        )
            .thenReturn(spawnResult)

        val spawnResults: MutableList<SpawnResult?>? =
            TestedSpawnStrategy(spawnRunner)
                .exec(SIMPLE_SPAWN, actionExecutionContext, { exitCode, errorMessage, outErr -> })

        Truth.assertThat(spawnResults).containsExactly(spawnResult)

        // Must only be called exactly once.
        Mockito.verify<Any?>(spawnRunner).exec(
            ArgumentMatchers.any<T?>(Spawn::class.java),
            ArgumentMatchers.any<T?>(SpawnExecutionContext::class.java)
        )
        Mockito.verify<Any?>(entry).store(< T > eq < T ? > (spawnResult))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExec_nonUsefulCacheInDynamicExecution() {
        Mockito.`when`<T?>(spawnRunner.handlesCaching()).thenReturn(false)

        val cache: SpawnCache = Mockito.mock<SpawnCache>(SpawnCache::class.java)
        Mockito.`when`<T?>(cache.usefulInDynamicExecution()).thenReturn(false)

        Mockito.`when`<T?>(actionExecutionContext.getContext(< T > eq < T ? > (SpawnCache::class.java))).thenReturn(cache)
        Mockito.`when`<T?>(actionExecutionContext.getExecRoot()).thenReturn(execRoot)
        val spawnResult: SpawnResult? =
            Builder().setStatus(Status.SUCCESS).setRunnerName("test").build()
        Mockito.`when`<T?>(
            spawnRunner.exec(
                ArgumentMatchers.any<T?>(Spawn::class.java),
                ArgumentMatchers.any<T?>(SpawnExecutionContext::class.java)
            )
        )
            .thenReturn(spawnResult)

        val spawnResults: MutableList<SpawnResult?>? =
            TestedSpawnStrategy(spawnRunner)
                .exec(SIMPLE_SPAWN, actionExecutionContext, { exitCode, errorMessage, outErr -> })

        Truth.assertThat(spawnResults).containsExactly(spawnResult)

        // Must only be called exactly once.
        Mockito.verify<Any?>(spawnRunner).exec(
            ArgumentMatchers.any<T?>(Spawn::class.java),
            ArgumentMatchers.any<T?>(SpawnExecutionContext::class.java)
        )
        Mockito.verify<Any?>(cache).usefulInDynamicExecution()
        Mockito.verifyNoMoreInteractions(cache)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCacheMissWithNonZeroExit() {
        val cache: SpawnCache = Mockito.mock<SpawnCache>(SpawnCache::class.java)
        val entry: CacheHandle = Mockito.mock<CacheHandle>(CacheHandle::class.java)
        Mockito.`when`<T?>(
            cache.lookup(
                ArgumentMatchers.any<T?>(Spawn::class.java),
                ArgumentMatchers.any<T?>(SpawnExecutionContext::class.java)
            )
        ).thenReturn(entry)
        Mockito.`when`<T?>(entry.hasResult()).thenReturn(false)
        Mockito.`when`<T?>(entry.willStore()).thenReturn(true)

        Mockito.`when`<T?>(actionExecutionContext.getContext(< T > eq < T ? > (SpawnCache::class.java))).thenReturn(cache)
        Mockito.`when`<T?>(actionExecutionContext.getExecRoot()).thenReturn(execRoot)
        val result: SpawnResult? =
            Builder()
                .setStatus(Status.NON_ZERO_EXIT)
                .setExitCode(1)
                .setFailureDetail(NON_ZERO_EXIT_DETAILS)
                .setRunnerName("test")
                .build()
        Mockito.`when`<T?>(
            spawnRunner.exec(
                ArgumentMatchers.any<T?>(Spawn::class.java),
                ArgumentMatchers.any<T?>(SpawnExecutionContext::class.java)
            )
        ).thenReturn(result)

        val e: SpawnExecException =
            org.junit.Assert.assertThrows<T>(
                SpawnExecException::class.java,
                org.junit.function.ThrowingRunnable { // Ignoring the List<SpawnResult> return value.
                    TestedSpawnStrategy(spawnRunner).exec(SIMPLE_SPAWN, actionExecutionContext)
                })
        assertThat(e.getSpawnResult()).isSameInstanceAs(result)
        // Must only be called exactly once.
        Mockito.verify<Any?>(spawnRunner).exec(
            ArgumentMatchers.any<T?>(Spawn::class.java),
            ArgumentMatchers.any<T?>(SpawnExecutionContext::class.java)
        )
        Mockito.verify<Any?>(entry).store(< T > eq < T ? > (result))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExec_callsLogSpawn() {
        val actionFs: FileSystem? = Mockito.mock<FileSystem?>(FileSystem::class.java)
        val inputMetadataProvider: InputMetadataProvider? =
            Mockito.mock<InputMetadataProvider?>(InputMetadataProvider::class.java)
        val spawnLogContext: SpawnLogContext? = Mockito.mock<SpawnLogContext?>(SpawnLogContext::class.java)

        val spawnResult: SpawnResult? =
            Builder().setStatus(Status.SUCCESS).setRunnerName("test").build()

        Mockito.`when`<T?>(actionExecutionContext.getContext(< T > eq < T ? > (SpawnCache::class.java))).thenReturn(SpawnCache.NO_CACHE)
        Mockito.`when`<T?>(actionExecutionContext.getContext(< T > eq < T ? > (SpawnLogContext::class.java))).thenReturn(spawnLogContext)
        Mockito.`when`<T?>(actionExecutionContext.getExecRoot()).thenReturn(execRoot)
        Mockito.`when`<T?>(actionExecutionContext.getActionFileSystem()).thenReturn(actionFs)
        Mockito.`when`<T?>(actionExecutionContext.getInputMetadataProvider()).thenReturn(inputMetadataProvider)

        Mockito.`when`<T?>(
            spawnRunner.exec(
                ArgumentMatchers.any<T?>(Spawn::class.java),
                ArgumentMatchers.any<T?>(SpawnExecutionContext::class.java)
            )
        )
            .thenReturn(spawnResult)

        val spawnResults: com.google.common.collect.ImmutableList<SpawnResult?>? =
            TestedSpawnStrategy(spawnRunner).exec(SIMPLE_SPAWN, actionExecutionContext)
        Truth.assertThat(spawnResults).containsExactly(spawnResult)

        Mockito.verify<Any?>(spawnLogContext)
            .logSpawn(
                < T > eq < T ? > (SIMPLE_SPAWN),
        <T > eq<T?>(inputMetadataProvider),
        ArgumentMatchers.any<java.util.function.Supplier<SortedMap<PathFragment?, ActionInput?>?>?>(),
        <T > eq<T?>(actionFs),
        <T > eq<T?>(java.time.Duration.ZERO),
        <T > eq<T?>(spawnResult))
    }

    companion object {
        private val NON_ZERO_EXIT_DETAILS: FailureDetail? = FailureDetail.newBuilder()
            .setSpawn(FailureDetails.Spawn.newBuilder().setCode(Code.NON_ZERO_EXIT))
            .build()

        private val SIMPLE_SPAWN: Spawn = SpawnBuilder("/bin/echo", "Hi!").withEnvironment("VARIABLE", "value").build()
    }
}
