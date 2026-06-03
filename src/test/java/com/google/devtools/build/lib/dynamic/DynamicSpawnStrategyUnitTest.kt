// Copyright 2020 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.dynamic

import com.google.devtools.build.lib.actions.ActionExecutionContext

/** Unit tests for [DynamicSpawnStrategy].  */
@RunWith(JUnit4::class)
class DynamicSpawnStrategyUnitTest {
    private var executorServiceForCleanup: ExecutorService? = null

    private var mockGetPostProcessingSpawn: java.util.function.Function<Spawn?, java.util.Optional<Spawn?>?>? = null
    private var reporter: ExtendedEventHandler? = null

    private var scratch: Scratch? = null
    private var execDir: Path? = null
    private var rootDir: ArtifactRoot? = null
    private var output1: Artifact? = null
    private var output2: Artifact? = null
    private val events: MutableList<DynamicExecutionFinishedEvent?> =
        java.util.ArrayList<DynamicExecutionFinishedEvent?>()

    @Before
    @Throws(IOException::class)
    fun initMocks() {
        scratch = Scratch()
        execDir = scratch.dir("/base/exec")
        rootDir = ArtifactRoot.asDerivedRoot(execDir, RootType.OUTPUT, "root")
        output1 =
            Artifact.DerivedArtifact.create(
                rootDir,
                rootDir.getExecPath().getRelative("dir/output1.txt"),
                ActionsTestUtil.NULL_ARTIFACT_OWNER
            )
        output2 =
            Artifact.DerivedArtifact.create(
                rootDir,
                rootDir.getExecPath().getRelative("dir/output2.txt"),
                ActionsTestUtil.NULL_ARTIFACT_OWNER
            )
        reporter = Mockito.mock<ExtendedEventHandler?>(ExtendedEventHandler::class.java)
        events.clear()
        Mockito.doAnswer(
            Answer { inv: InvocationOnMock? ->
                val event: Any? = inv.getArgument<Any?>(0)
                if (event is DynamicExecutionFinishedEvent) {
                    events.add(event)
                }
                null
            } as Answer<java.lang.Void?>)
            .`when`<ExtendedEventHandler?>(reporter)
            .post(ArgumentMatchers.any<Postable?>())
        mockGetPostProcessingSpawn =
            Mockito.mock<java.util.function.Function<*, *>>(java.util.function.Function::class.java)
    }

    @org.junit.After
    @Throws(java.lang.InterruptedException::class)
    fun stopExecutorService() {
        if (executorServiceForCleanup != null) {
            executorServiceForCleanup.shutdown()
            Truth.assertThat(
                executorServiceForCleanup.awaitTermination(
                    com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS
                )
            )
                .isTrue()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exec_remoteOnlySpawn_doesNotExecLocalPostProcessingSpawn() {
        val spawn: Spawn = SpawnBuilder().withOwnerPrimaryOutput(output1).build()

        val dynamicSpawnStrategy: DynamicSpawnStrategy =
            createDynamicSpawnStrategy(
                ExecutionPolicy.REMOTE_EXECUTION_ONLY, mockGetPostProcessingSpawn
            )
        Mockito.`when`<java.util.Optional<Spawn?>?>(mockGetPostProcessingSpawn.apply(spawn))
            .thenReturn(java.util.Optional.empty<Spawn?>())
        val local: SandboxedSpawnStrategy = createMockSpawnStrategy()
        val remote: SandboxedSpawnStrategy = createMockSpawnStrategy()
        val remoteSpawnCaptor: ArgumentCaptor<Spawn?> = ArgumentCaptor.forClass<Spawn?, Spawn?>(Spawn::class.java)
        Mockito.`when`<T?>(
            remote.exec(
                remoteSpawnCaptor.capture(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(com.google.common.collect.ImmutableList.of<E?>(SUCCESSFUL_SPAWN_RESULT))
        val actionExecutionContext: ActionExecutionContext = createMockActionExecutionContext(local, remote)

        val results: com.google.common.collect.ImmutableList<SpawnResult?> =
            dynamicSpawnStrategy.exec(spawn, actionExecutionContext)

        Truth.assertThat(results).containsExactly(SUCCESSFUL_SPAWN_RESULT)
        Mockito.verify<Any?>(local, Mockito.never())
            .exec(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
        Truth.assertThat(remoteSpawnCaptor.getAllValues()).containsExactly(spawn)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exec_remoteOnlySpawn_noneCanExec_fails() {
        val spawn: Spawn =
            SpawnBuilder().withMnemonic("TheThing").withOwnerPrimaryOutput(output1).build()
        val dynamicSpawnStrategy: DynamicSpawnStrategy =
            createDynamicSpawnStrategy(
                ExecutionPolicy.REMOTE_EXECUTION_ONLY, mockGetPostProcessingSpawn
            )
        Mockito.`when`<java.util.Optional<Spawn?>?>(mockGetPostProcessingSpawn.apply(spawn))
            .thenReturn(java.util.Optional.empty<Spawn?>())
        val local: SandboxedSpawnStrategy = createMockSpawnStrategy()
        val remote: SandboxedSpawnStrategy = createMockSpawnStrategy(false)
        val actionExecutionContext: ActionExecutionContext = createMockActionExecutionContext(local, remote)

        val thrown: UserExecException? =
            org.junit.Assert.assertThrows<T?>(
                UserExecException::class.java,
                org.junit.function.ThrowingRunnable { dynamicSpawnStrategy.exec(spawn, actionExecutionContext) })
        assertThat(thrown)
            .hasMessageThat()
            .isEqualTo(
                "Spawn is not executable in local: No usable dynamic_remote_strategy found (and local"
                        + " execution disabled) for action TheThing. "
            )
        assertThat(thrown).hasMessageThat().doesNotContain("dynamic_local_strategy")
        Mockito.verifyNoInteractions(local)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exec_localOnlySpawn_runsLocalPostProcessingSpawn() {
        val spawn: Spawn = SpawnBuilder("command").withOwnerPrimaryOutput(output1).build()
        val postProcessingSpawn: Spawn =
            SpawnBuilder("extra_command").withOwnerPrimaryOutput(output2).build()
        val dynamicSpawnStrategy: DynamicSpawnStrategy =
            createDynamicSpawnStrategy(
                ExecutionPolicy.LOCAL_EXECUTION_ONLY, mockGetPostProcessingSpawn
            )
        Mockito.`when`<java.util.Optional<Spawn?>?>(mockGetPostProcessingSpawn.apply(spawn))
            .thenReturn(java.util.Optional.of<Spawn?>(postProcessingSpawn))
        val local: SandboxedSpawnStrategy = createMockSpawnStrategy()
        val localSpawnCaptor: ArgumentCaptor<Spawn?> = ArgumentCaptor.forClass<Spawn?, Spawn?>(Spawn::class.java)
        Mockito.`when`<T?>(
            local.exec(
                localSpawnCaptor.capture(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(com.google.common.collect.ImmutableList.of<E?>(SUCCESSFUL_SPAWN_RESULT))
        val remote: SandboxedSpawnStrategy = createMockSpawnStrategy()
        val actionExecutionContext: ActionExecutionContext = createMockActionExecutionContext(local, remote)

        val results: com.google.common.collect.ImmutableList<SpawnResult?> =
            dynamicSpawnStrategy.exec(spawn, actionExecutionContext)

        Truth.assertThat(results).containsExactly(SUCCESSFUL_SPAWN_RESULT, SUCCESSFUL_SPAWN_RESULT)
        Mockito.verifyNoInteractions(remote)
        Truth.assertThat(localSpawnCaptor.getAllValues())
            .containsExactly(spawn, postProcessingSpawn)
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exec_localOnlySpawn_noneCanExec_fails() {
        val spawn: Spawn =
            SpawnBuilder().withMnemonic("ThisMnemonic1").withOwnerPrimaryOutput(output1).build()
        val postProcessingSpawn: Spawn =
            SpawnBuilder().withMnemonic("ThatMnemonic2").withOwnerPrimaryOutput(output2).build()

        val dynamicSpawnStrategy: DynamicSpawnStrategy =
            createDynamicSpawnStrategy(
                ExecutionPolicy.LOCAL_EXECUTION_ONLY, mockGetPostProcessingSpawn
            )
        Mockito.`when`<java.util.Optional<Spawn?>?>(mockGetPostProcessingSpawn.apply(spawn))
            .thenReturn(java.util.Optional.of<Spawn?>(postProcessingSpawn))
        val local: SandboxedSpawnStrategy = createMockSpawnStrategy(false)
        val remote: SandboxedSpawnStrategy = createMockSpawnStrategy()
        val actionExecutionContext: ActionExecutionContext = createMockActionExecutionContext(local, remote)

        val thrown: UserExecException? =
            org.junit.Assert.assertThrows<T?>(
                UserExecException::class.java,
                org.junit.function.ThrowingRunnable { dynamicSpawnStrategy.exec(spawn, actionExecutionContext) })
        assertThat(thrown)
            .hasMessageThat()
            .isEqualTo(
                ("Spawn is not executable in local: No usable dynamic_local_strategy found (and remote"
                        + " execution disabled) for action ThisMnemonic1. Post-Processing Spawn is not"
                        + " executable in local: No usable dynamic_local_strategy found (and remote"
                        + " execution disabled) for action ThatMnemonic2. ")
            )
        assertThat(thrown).hasMessageThat().doesNotContain("dynamic_remote_strategy")
        Mockito.verifyNoInteractions(remote)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exec_localOnlySpawnWithNonExecutablePostProcessingSpawn_doesNotExecLocalSpawn() {
        val spawn: Spawn =
            SpawnBuilder().withMnemonic("ThisMnemonic1").withOwnerPrimaryOutput(output1).build()
        val postProcessingSpawn: Spawn =
            SpawnBuilder().withMnemonic("ThatMnemonic2").withOwnerPrimaryOutput(output2).build()

        val dynamicSpawnStrategy: DynamicSpawnStrategy =
            createDynamicSpawnStrategy(
                ExecutionPolicy.LOCAL_EXECUTION_ONLY, mockGetPostProcessingSpawn
            )
        Mockito.`when`<java.util.Optional<Spawn?>?>(mockGetPostProcessingSpawn.apply(spawn))
            .thenReturn(java.util.Optional.of<Spawn?>(postProcessingSpawn))

        val local: SandboxedSpawnStrategy = createMockSpawnStrategy()

        val actionExecutionContext: ActionExecutionContext =
            Mockito.mock<ActionExecutionContext>(ActionExecutionContext::class.java)
        Mockito.`when`<T?>(actionExecutionContext.getFileOutErr()).thenReturn(TestFileOutErr())
        Mockito.`when`<T?>(actionExecutionContext.getContext(DynamicStrategyRegistry::class.java))
            .thenReturn(
                object : DynamicStrategyRegistry() {
                    public override fun getDynamicSpawnActionContexts(
                        spawn: Spawn, dynamicMode: DynamicMode?
                    ): com.google.common.collect.ImmutableList<SandboxedSpawnStrategy?> {
                        if (spawn.getMnemonic().equals("ThisMnemonic1")) {
                            return com.google.common.collect.ImmutableList.of<SandboxedSpawnStrategy?>(local)
                        }
                        return com.google.common.collect.ImmutableList.of<SandboxedSpawnStrategy?>()
                    }

                    public override fun notifyUsedDynamic(actionContextRegistry: ActionContextRegistry?) {}
                })
        Mockito.`when`<T?>(actionExecutionContext.withFileOutErr(ArgumentMatchers.any<T?>()))
            .thenReturn(actionExecutionContext)

        val thrown: UserExecException? =
            org.junit.Assert.assertThrows<T?>(
                UserExecException::class.java,
                org.junit.function.ThrowingRunnable { dynamicSpawnStrategy.exec(spawn, actionExecutionContext) })

        assertThat(thrown)
            .hasMessageThat()
            .isEqualTo(
                "Post-Processing Spawn is not executable in local: No usable dynamic_local_strategy"
                        + " found (and remote execution disabled) for action ThatMnemonic2. "
            )
        assertThat(thrown).hasMessageThat().doesNotContain("dynamic_remote_strategy")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exec_failedLocalSpawn_doesNotExecLocalPostProcessingSpawn() {
        testExecFailedLocalSpawnDoesNotExecLocalPostProcessingSpawn(
            Builder()
                .setRunnerName("test")
                .setStatus(Status.TIMEOUT)
                .setExitCode(SpawnResult.POSIX_TIMEOUT_EXIT_CODE)
                .setFailureDetail(FAILURE_DETAIL)
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exec_nonZeroExitCodeLocalSpawn_doesNotExecLocalPostProcessingSpawn() {
        testExecFailedLocalSpawnDoesNotExecLocalPostProcessingSpawn(
            Builder()
                .setRunnerName("test")
                .setStatus(Status.EXECUTION_FAILED)
                .setExitCode(123)
                .setFailureDetail(FAILURE_DETAIL)
                .build()
        )
    }

    @Throws(java.lang.Exception::class)
    private fun testExecFailedLocalSpawnDoesNotExecLocalPostProcessingSpawn(failedResult: SpawnResult) {
        val spawn: Spawn = SpawnBuilder().withOwnerPrimaryOutput(output1).build()
        val postProcessingSpawn: Spawn = createMockSpawn()

        val dynamicSpawnStrategy: DynamicSpawnStrategy =
            createDynamicSpawnStrategy(
                ExecutionPolicy.LOCAL_EXECUTION_ONLY, mockGetPostProcessingSpawn
            )
        Mockito.`when`<java.util.Optional<Spawn?>?>(mockGetPostProcessingSpawn.apply(spawn))
            .thenReturn(java.util.Optional.of<Spawn?>(postProcessingSpawn))
        val local: SandboxedSpawnStrategy = createMockSpawnStrategy()
        val localSpawnCaptor: ArgumentCaptor<Spawn?> = ArgumentCaptor.forClass<Spawn?, Spawn?>(Spawn::class.java)
        Mockito.`when`<T?>(
            local.exec(
                localSpawnCaptor.capture(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(com.google.common.collect.ImmutableList.of<E?>(failedResult))
        val remote: SandboxedSpawnStrategy = createMockSpawnStrategy()
        val actionExecutionContext: ActionExecutionContext = createMockActionExecutionContext(local, remote)

        val results: com.google.common.collect.ImmutableList<SpawnResult?> =
            dynamicSpawnStrategy.exec(spawn, actionExecutionContext)

        Truth.assertThat(results).containsExactly(failedResult)
        Truth.assertThat(localSpawnCaptor.getAllValues()).containsExactly(spawn)
        Mockito.verify<Any?>(remote, Mockito.never())
            .exec(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
        Mockito.verifyNoInteractions(postProcessingSpawn)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exec_runAnywhereSpawn_runsLocalPostProcessingSpawn() {
        val spawn: Spawn = SpawnBuilder().withOwnerPrimaryOutput(output1).build()
        val postProcessingSpawn: Spawn =
            SpawnBuilder("extra_command").withOwnerPrimaryOutput(output2).build()
        val dynamicSpawnStrategy: DynamicSpawnStrategy =
            createDynamicSpawnStrategy(ExecutionPolicy.ANYWHERE, mockGetPostProcessingSpawn)
        Mockito.`when`<java.util.Optional<Spawn?>?>(mockGetPostProcessingSpawn.apply(spawn))
            .thenReturn(java.util.Optional.of<Spawn?>(postProcessingSpawn))
        val local: SandboxedSpawnStrategy = createMockSpawnStrategy()
        // Make sure that local execution does not win the race before remote starts.
        val remoteStarted: Semaphore = Semaphore(0)
        // Only the first spawn should be able to stop the concurrent remote execution (get the output
        // lock).
        Mockito.`when`<T?>(
            local.exec(< T > eq < T ? > (spawn),
            ArgumentMatchers.any<T?>(),  /* stopConcurrentSpawns= */
            ArgumentMatchers.isNotNull<T?>()
        ))
        .thenAnswer(
            Answer { invocation: InvocationOnMock? ->
                remoteStarted.acquire()
                val stopConcurrentSpawns: StopConcurrentSpawns = invocation.getArgument<StopConcurrentSpawns>(2)
                stopConcurrentSpawns.stop(0, "", null)
                com.google.common.collect.ImmutableList.of<Any?>(SUCCESSFUL_SPAWN_RESULT)
            })
        Mockito.`when`<T?>(
            local.exec(< T > eq < T ? > (postProcessingSpawn),
            ArgumentMatchers.any<T?>(),  /* stopConcurrentSpawns= */
            ArgumentMatchers.isNull<T?>()
        ))
        .thenReturn(com.google.common.collect.ImmutableList.of<E?>(SUCCESSFUL_SPAWN_RESULT))
        val remote: SandboxedSpawnStrategy = createMockSpawnStrategy()
        Mockito.`when`<T?>(
            remote.exec(< T > eq < T ? > (spawn),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>()
        ))
        .thenAnswer(
            Answer { invocation: InvocationOnMock? ->
                remoteStarted.release()
                java.lang.Thread.sleep(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
                throw java.lang.AssertionError("Timed out waiting for interruption")
            })
        val actionExecutionContext: ActionExecutionContext = createMockActionExecutionContext(local, remote)
        Mockito.`when`<T?>(actionExecutionContext.getEventHandler()).thenReturn(reporter)

        val results: com.google.common.collect.ImmutableList<SpawnResult?> =
            dynamicSpawnStrategy.exec(spawn, actionExecutionContext)

        Truth.assertThat(results).containsExactly(SUCCESSFUL_SPAWN_RESULT, SUCCESSFUL_SPAWN_RESULT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exec_runAnywhereSpawn_localWins() {
        val spawn: Spawn = SpawnBuilder().withOwnerPrimaryOutput(output1).build()
        val dynamicSpawnStrategy: DynamicSpawnStrategy =
            createDynamicSpawnStrategy(ExecutionPolicy.ANYWHERE, mockGetPostProcessingSpawn)
        Mockito.`when`<java.util.Optional<Spawn?>?>(mockGetPostProcessingSpawn.apply(ArgumentMatchers.any<Spawn?>()))
            .thenReturn(java.util.Optional.empty<Spawn?>())
        val local: SandboxedSpawnStrategy = createMockSpawnStrategy("local")
        val remote: SandboxedSpawnStrategy = createMockSpawnStrategy("remote")
        // Make sure that local execution does not win the race before remote starts.
        val remoteStarted: Semaphore = Semaphore(0)
        val remoteDone: Semaphore = Semaphore(0)
        Mockito.`when`<T?>(
            remote.exec(< T > eq < T ? > (spawn),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.isNotNull<T?>()
        ))
        .thenAnswer(
            Answer { invocation: InvocationOnMock? ->
                remoteStarted.release()
                remoteDone.acquire()
                val stopConcurrentSpawns: StopConcurrentSpawns = invocation.getArgument<StopConcurrentSpawns>(2)
                stopConcurrentSpawns.stop(0, "", null)
                com.google.common.collect.ImmutableList.of<Any?>(SUCCESSFUL_REMOTE_SPAWN_RESULT)
            })
        Mockito.`when`<T?>(
            local.exec(< T > eq < T ? > (spawn),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.isNotNull<T?>()
        ))
        .thenAnswer(
            Answer { invocation: InvocationOnMock? ->
                remoteStarted.acquire()
                val stopConcurrentSpawns: StopConcurrentSpawns = invocation.getArgument<StopConcurrentSpawns>(2)
                stopConcurrentSpawns.stop(0, "", null)
                com.google.common.collect.ImmutableList.of<Any?>(SUCCESSFUL_LOCAL_SPAWN_RESULT)
            })
        val actionExecutionContext: ActionExecutionContext = createMockActionExecutionContext(local, remote)
        Mockito.`when`<T?>(actionExecutionContext.getEventHandler()).thenReturn(reporter)

        val results: com.google.common.collect.ImmutableList<SpawnResult?> =
            dynamicSpawnStrategy.exec(spawn, actionExecutionContext)

        Truth.assertThat(results).containsExactly(SUCCESSFUL_LOCAL_SPAWN_RESULT)
        Truth.assertThat(events).hasSize(1)
        assertThat(events.get(0).getWinnerBranchType()).isEqualTo(DynamicMode.LOCAL)
        Truth.assertThat(events.get(0).remoteBranchName).isEqualTo("remote")
        Truth.assertThat(events.get(0).localBranchName).isEqualTo("local")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exec_runAnywhereSpawn_remoteWins() {
        val spawn: Spawn = SpawnBuilder().withOwnerPrimaryOutput(output1).build()
        val dynamicSpawnStrategy: DynamicSpawnStrategy =
            createDynamicSpawnStrategy(ExecutionPolicy.ANYWHERE, mockGetPostProcessingSpawn)
        Mockito.`when`<java.util.Optional<Spawn?>?>(mockGetPostProcessingSpawn.apply(ArgumentMatchers.any<Spawn?>()))
            .thenReturn(java.util.Optional.empty<Spawn?>())
        val local: SandboxedSpawnStrategy = createMockSpawnStrategy("local")
        val remote: SandboxedSpawnStrategy = createMockSpawnStrategy("remote")
        // Make sure that local execution does not win the race before remote starts.
        val remoteStarted: Semaphore = Semaphore(0)
        val localDone: Semaphore = Semaphore(0)
        Mockito.`when`<T?>(
            remote.exec(< T > eq < T ? > (spawn),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.isNotNull<T?>()
        ))
        .thenAnswer(
            Answer { invocation: InvocationOnMock? ->
                remoteStarted.release()
                val stopConcurrentSpawns: StopConcurrentSpawns = invocation.getArgument<StopConcurrentSpawns>(2)
                stopConcurrentSpawns.stop(0, "", null)
                com.google.common.collect.ImmutableList.of<Any?>(SUCCESSFUL_REMOTE_SPAWN_RESULT)
            })
        Mockito.`when`<T?>(
            local.exec(< T > eq < T ? > (spawn),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.isNotNull<T?>()
        ))
        .thenAnswer(
            Answer { invocation: InvocationOnMock? ->
                remoteStarted.acquire()
                localDone.acquire()
                val stopConcurrentSpawns: StopConcurrentSpawns = invocation.getArgument<StopConcurrentSpawns>(2)
                stopConcurrentSpawns.stop(0, "", null)
                com.google.common.collect.ImmutableList.of<Any?>(SUCCESSFUL_LOCAL_SPAWN_RESULT)
            })
        val actionExecutionContext: ActionExecutionContext = createMockActionExecutionContext(local, remote)
        Mockito.`when`<T?>(actionExecutionContext.getEventHandler()).thenReturn(reporter)

        val results: com.google.common.collect.ImmutableList<SpawnResult?> =
            dynamicSpawnStrategy.exec(spawn, actionExecutionContext)

        Truth.assertThat(results).containsExactly(SUCCESSFUL_REMOTE_SPAWN_RESULT)
        Truth.assertThat(events).hasSize(1)
        assertThat(events.get(0).getWinnerBranchType()).isEqualTo(DynamicMode.REMOTE)
        Truth.assertThat(events.get(0).remoteBranchName).isEqualTo("remote")
        Truth.assertThat(events.get(0).localBranchName).isEqualTo("local")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exec_runAnywhereSpawn_allowsIgnoringFailure() {
        val spawn: Spawn = SpawnBuilder().withOwnerPrimaryOutput(output1).build()
        com.google.common.base.Preconditions.checkState(
            executorServiceForCleanup == null,
            "Creating the DynamicSpawnStrategy twice in the same test is not supported."
        )
        executorServiceForCleanup = Executors.newCachedThreadPool()
        val dynamicSpawnStrategy: DynamicSpawnStrategy =
            DynamicSpawnStrategy(
                executorServiceForCleanup,
                com.google.devtools.common.options.Options.getDefaults<DynamicExecutionOptions?>(DynamicExecutionOptions::class.java),
                java.util.function.Function { ignored: Spawn? -> ExecutionPolicy.ANYWHERE },
                java.util.function.Function { ignored: Spawn? -> java.util.Optional.empty<Spawn>() },
                10,
                10,
                IgnoreFailureCheck { s, context, exitCode, errorMsg, outErr, isLocal -> isLocal && errorMsg.contains("Ignorable") })
        val local: SandboxedSpawnStrategy = createMockSpawnStrategy()
        // Make sure that local execution does not win the race before remote starts.
        val remoteStarted: Semaphore = Semaphore(0)
        // Only the first spawn should be able to stop the concurrent remote execution (get the output
        // lock).
        Mockito.`when`<T?>(
            local.exec(< T > eq < T ? > (spawn),
            ArgumentMatchers.any<T?>(),  /* stopConcurrentSpawns= */
            ArgumentMatchers.isNotNull<T?>()
        ))
        .thenAnswer(
            Answer { invocation: InvocationOnMock? ->
                remoteStarted.acquire()
                val stopConcurrentSpawns: StopConcurrentSpawns = invocation.getArgument<StopConcurrentSpawns>(2)
                stopConcurrentSpawns.stop(1, "Ignorable failure", null)
                com.google.common.collect.ImmutableList.of<Any?>(SUCCESSFUL_SPAWN_RESULT, SUCCESSFUL_SPAWN_RESULT)
            })
        val remote: SandboxedSpawnStrategy = createMockSpawnStrategy()
        Mockito.`when`<T?>(
            remote.exec(< T > eq < T ? > (spawn),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>()
        ))
        .thenAnswer(
            Answer { invocation: InvocationOnMock? ->
                remoteStarted.release()
                java.lang.Thread.sleep(10)
                com.google.common.collect.ImmutableList.of<Any?>(SUCCESSFUL_SPAWN_RESULT)
            })
        val actionExecutionContext: ActionExecutionContext = createMockActionExecutionContext(local, remote)
        Mockito.`when`<T?>(actionExecutionContext.getEventHandler()).thenReturn(reporter)

        val results: com.google.common.collect.ImmutableList<SpawnResult?> =
            dynamicSpawnStrategy.exec(spawn, actionExecutionContext)

        Truth.assertThat(results).containsExactly(SUCCESSFUL_SPAWN_RESULT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exec_runAnywhereSpawn_notAlwaysIgnoringFailure() {
        val spawn: Spawn = SpawnBuilder().withOwnerPrimaryOutput(output1).build()
        com.google.common.base.Preconditions.checkState(
            executorServiceForCleanup == null,
            "Creating the DynamicSpawnStrategy twice in the same test is not supported."
        )
        executorServiceForCleanup = Executors.newCachedThreadPool()
        val dynamicSpawnStrategy: DynamicSpawnStrategy =
            DynamicSpawnStrategy(
                executorServiceForCleanup,
                com.google.devtools.common.options.Options.getDefaults<DynamicExecutionOptions?>(DynamicExecutionOptions::class.java),
                java.util.function.Function { ignored: Spawn? -> ExecutionPolicy.ANYWHERE },
                java.util.function.Function { ignored: Spawn? -> java.util.Optional.empty<Spawn>() },
                10,
                10,
                IgnoreFailureCheck { s, context, exitCode, errorMsg, outErr, isLocal -> isLocal && errorMsg.contains("Ignorable") })
        val local: SandboxedSpawnStrategy = createMockSpawnStrategy()
        // Make sure that local execution does not win the race before remote starts.
        val remoteStarted: Semaphore = Semaphore(0)
        val localDone: Semaphore = Semaphore(0)
        // Only the first spawn should be able to stop the concurrent remote execution (get the output
        // lock).
        Mockito.`when`<T?>(
            local.exec(< T > eq < T ? > (spawn),
            ArgumentMatchers.any<T?>(),  /* stopConcurrentSpawns= */
            ArgumentMatchers.isNotNull<T?>()
        ))
        .thenAnswer(
            Answer { invocation: InvocationOnMock? ->
                remoteStarted.acquire()
                val stopConcurrentSpawns: StopConcurrentSpawns = invocation.getArgument<StopConcurrentSpawns>(2)
                stopConcurrentSpawns.stop(1, "Not an ignorable failure", null)
                com.google.common.collect.ImmutableList.of<Any?>(SUCCESSFUL_SPAWN_RESULT, SUCCESSFUL_SPAWN_RESULT)
            })
        val remote: SandboxedSpawnStrategy = createMockSpawnStrategy()
        Mockito.`when`<T?>(
            remote.exec(< T > eq < T ? > (spawn),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>()
        ))
        .thenAnswer(
            Answer { invocation: InvocationOnMock? ->
                remoteStarted.release()
                localDone.acquire()
                com.google.common.collect.ImmutableList.of<Any?>(SUCCESSFUL_SPAWN_RESULT)
            })
        val actionExecutionContext: ActionExecutionContext = createMockActionExecutionContext(local, remote)
        Mockito.`when`<T?>(actionExecutionContext.getEventHandler()).thenReturn(reporter)

        val results: com.google.common.collect.ImmutableList<SpawnResult?> =
            dynamicSpawnStrategy.exec(spawn, actionExecutionContext)
        localDone.release()
        Truth.assertThat(results).containsExactly(SUCCESSFUL_SPAWN_RESULT, SUCCESSFUL_SPAWN_RESULT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exec_runAnywhereSpawn_excludeTools_onlyRemote() {
        val spawn: Spawn =
            SpawnBuilder()
                .withMnemonic("TheThing")
                .withOwnerPrimaryOutput(output1)
                .withProgressMessage("Building the thing")
                .setBuiltForToolConfiguration(true)
                .build()
        val options: DynamicExecutionOptions =
            com.google.devtools.common.options.Options.getDefaults<DynamicExecutionOptions>(DynamicExecutionOptions::class.java)
        options.excludeTools = true
        options.localExecutionDelay = 0
        val dynamicSpawnStrategy: DynamicSpawnStrategy =
            createDynamicSpawnStrategy(
                ExecutionPolicy.ANYWHERE,
                java.util.function.Function { s: Spawn? -> java.util.Optional.empty<Spawn?>() },
                options
            )

        val local: SandboxedSpawnStrategy = createMockSpawnStrategy()
        val remote: SandboxedSpawnStrategy = createMockSpawnStrategy()
        Mockito.`when`<T?>(
            remote.exec(< T > eq < T ? > (spawn),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>()
        ))
        .thenReturn(com.google.common.collect.ImmutableList.of<E?>(SUCCESSFUL_SPAWN_RESULT))
        val actionExecutionContext: ActionExecutionContext = createMockActionExecutionContext(local, remote)
        Mockito.`when`<T?>(actionExecutionContext.getEventHandler()).thenReturn(reporter)

        val spawnResults: com.google.common.collect.ImmutableList<SpawnResult?>? =
            dynamicSpawnStrategy.maybeExecuteNonDynamically(spawn, actionExecutionContext)

        Truth.assertWithMessage("Should have been executed remote-only").that(spawnResults).isNotNull()
        Truth.assertThat(spawnResults).containsExactly(SUCCESSFUL_SPAWN_RESULT)
        Mockito.verify<Any?>(local, Mockito.never())
            .exec(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun waitBranches_givesDebugOutputIfBothCancelled() {
        val spawn: Spawn =
            SpawnBuilder()
                .withOwnerPrimaryOutput(SourceArtifact(rootDir, PathFragment.create("/foo"), null))
                .build()
        val local: SandboxedSpawnStrategy = createMockSpawnStrategy()
        val remote: SandboxedSpawnStrategy = createMockSpawnStrategy()
        val actionExecutionContext: ActionExecutionContext = createMockActionExecutionContext(local, remote)
        val strategyThatCancelled: AtomicReference<DynamicMode?> = AtomicReference<DynamicMode?>()
        val options: DynamicExecutionOptions? =
            com.google.devtools.common.options.Options.getDefaults<DynamicExecutionOptions?>(DynamicExecutionOptions::class.java)
        val localBranch: LocalBranch =
            LocalBranch(
                actionExecutionContext, spawn, strategyThatCancelled, options, null, null, null
            )
        val remoteBranch: RemoteBranch =
            RemoteBranch(actionExecutionContext, spawn, strategyThatCancelled, options, null, null)
        localBranch.prepareFuture(remoteBranch)
        remoteBranch.prepareFuture(localBranch)
        localBranch.cancel()
        remoteBranch.cancel()
        val error: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable {
                    DynamicSpawnStrategy.waitBranches(
                        localBranch,
                        remoteBranch,
                        spawn,
                        com.google.devtools.common.options.Options.getDefaults<O?>(DynamicExecutionOptions::class.java),
                        actionExecutionContext
                    )
                })
        Truth.assertThat(error).hasMessageThat().contains("Neither branch of /foo completed.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exec_runAnywhereSpawn_localCantExec_runsRemote() {
        val spawn: Spawn = SpawnBuilder().withOwnerPrimaryOutput(output1).build()
        val postProcessingSpawn: Spawn = createMockSpawn()

        val dynamicSpawnStrategy: DynamicSpawnStrategy =
            createDynamicSpawnStrategy(ExecutionPolicy.ANYWHERE, mockGetPostProcessingSpawn)
        Mockito.`when`<java.util.Optional<Spawn?>?>(mockGetPostProcessingSpawn.apply(spawn))
            .thenReturn(java.util.Optional.of<Spawn?>(postProcessingSpawn))
        val local: SandboxedSpawnStrategy = createMockSpawnStrategy(false)
        val remote: SandboxedSpawnStrategy = createMockSpawnStrategy()
        Mockito.`when`<T?>(
            remote.exec(< T > eq < T ? > (spawn),
            ArgumentMatchers.any<T?>(),
            ArgumentMatchers.any<T?>()
        ))
        .thenAnswer(
            Answer { invocation: InvocationOnMock? ->
                val stopConcurrentSpawns: StopConcurrentSpawns? = invocation.getArgument<StopConcurrentSpawns?>(2)
                if (stopConcurrentSpawns != null) {
                    stopConcurrentSpawns.stop(0, "", null)
                }
                com.google.common.collect.ImmutableList.of<Any?>(SUCCESSFUL_SPAWN_RESULT)
            })
        val actionExecutionContext: ActionExecutionContext = createMockActionExecutionContext(local, remote)

        val results: com.google.common.collect.ImmutableList<SpawnResult?> =
            dynamicSpawnStrategy.exec(spawn, actionExecutionContext)

        Truth.assertThat(results).containsExactly(SUCCESSFUL_SPAWN_RESULT)
        // Never runs anything as it says it can't execute anything at all.
        Mockito.verify<Any?>(local, Mockito.never())
            .exec(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
        Mockito.verifyNoInteractions(postProcessingSpawn)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exec_runAnywhereSpawn_remoteCantExec_runsLocal() {
        val spawn: Spawn = SpawnBuilder().withOwnerPrimaryOutput(output1).build()
        val postProcessingSpawn: Spawn =
            SpawnBuilder("extra_command").withOwnerPrimaryOutput(output2).build()
        val dynamicSpawnStrategy: DynamicSpawnStrategy =
            createDynamicSpawnStrategy(ExecutionPolicy.ANYWHERE, mockGetPostProcessingSpawn)
        Mockito.`when`<java.util.Optional<Spawn?>?>(mockGetPostProcessingSpawn.apply(spawn))
            .thenReturn(java.util.Optional.of<Spawn?>(postProcessingSpawn))
        val local: SandboxedSpawnStrategy = createMockSpawnStrategy()
        val localSpawnCaptor: ArgumentCaptor<Spawn?> = ArgumentCaptor.forClass<Spawn?, Spawn?>(Spawn::class.java)
        Mockito.`when`<T?>(
            local.exec(
                localSpawnCaptor.capture(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenAnswer(
                Answer { invocation: InvocationOnMock? ->
                    val stopConcurrentSpawns: StopConcurrentSpawns? = invocation.getArgument<StopConcurrentSpawns?>(2)
                    if (stopConcurrentSpawns != null) {
                        stopConcurrentSpawns.stop(0, "", null)
                    }
                    com.google.common.collect.ImmutableList.of<Any?>(SUCCESSFUL_SPAWN_RESULT)
                })
        val remote: SandboxedSpawnStrategy = createMockSpawnStrategy(false)
        val actionExecutionContext: ActionExecutionContext = createMockActionExecutionContext(local, remote)

        val results: com.google.common.collect.ImmutableList<SpawnResult?> =
            dynamicSpawnStrategy.exec(spawn, actionExecutionContext)

        Truth.assertThat(results).containsExactly(SUCCESSFUL_SPAWN_RESULT, SUCCESSFUL_SPAWN_RESULT)
        Truth.assertThat(localSpawnCaptor.getAllValues())
            .containsExactly(spawn, postProcessingSpawn)
            .inOrder()
        Mockito.verify<Any?>(remote, Mockito.never())
            .exec(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exec_runAnywhereSpawn_noneCanExec_fails() {
        val spawn: Spawn =
            SpawnBuilder().withMnemonic("ThisMnemonic1").withOwnerPrimaryOutput(output1).build()
        val postProcessingSpawn: Spawn =
            SpawnBuilder().withMnemonic("ThatMnemonic2").withOwnerPrimaryOutput(output2).build()

        val dynamicSpawnStrategy: DynamicSpawnStrategy =
            createDynamicSpawnStrategy(ExecutionPolicy.ANYWHERE, mockGetPostProcessingSpawn)
        Mockito.`when`<java.util.Optional<Spawn?>?>(mockGetPostProcessingSpawn.apply(spawn))
            .thenReturn(java.util.Optional.of<Spawn?>(postProcessingSpawn))
        val local: SandboxedSpawnStrategy = createMockSpawnStrategy(false)
        val remote: SandboxedSpawnStrategy = createMockSpawnStrategy(false)
        val actionExecutionContext: ActionExecutionContext = createMockActionExecutionContext(local, remote)

        val thrown: UserExecException? =
            org.junit.Assert.assertThrows<T?>(
                UserExecException::class.java,
                org.junit.function.ThrowingRunnable { dynamicSpawnStrategy.exec(spawn, actionExecutionContext) })
        assertThat(thrown)
            .hasMessageThat()
            .isEqualTo(
                ("Spawn is not executable in local: No usable dynamic_local_strategy or"
                        + " dynamic_remote_strategy found for action ThisMnemonic1. Post-Processing Spawn"
                        + " is not executable in local: No usable dynamic_local_strategy or"
                        + " dynamic_remote_strategy found for action ThatMnemonic2. ")
            )
    }

    private fun createDynamicSpawnStrategy(
        executionPolicy: ExecutionPolicy?,
        getPostProcessingSpawnForLocalExecution: java.util.function.Function<Spawn?, java.util.Optional<Spawn?>?>
    ): DynamicSpawnStrategy {
        return createDynamicSpawnStrategy(
            executionPolicy,
            getPostProcessingSpawnForLocalExecution,
            com.google.devtools.common.options.Options.getDefaults<DynamicExecutionOptions?>(DynamicExecutionOptions::class.java)
        )
    }

    private fun createDynamicSpawnStrategy(
        executionPolicy: ExecutionPolicy?,
        getPostProcessingSpawnForLocalExecution: java.util.function.Function<Spawn?, java.util.Optional<Spawn?>?>,
        options: DynamicExecutionOptions
    ): DynamicSpawnStrategy {
        com.google.common.base.Preconditions.checkState(
            executorServiceForCleanup == null,
            "Creating the DynamicSpawnStrategy twice in the same test is not supported."
        )
        executorServiceForCleanup = Executors.newCachedThreadPool()
        return DynamicSpawnStrategy(
            executorServiceForCleanup,
            options,
            java.util.function.Function { ignored: Spawn? -> executionPolicy },
            getPostProcessingSpawnForLocalExecution,
            10,
            10,
            null
        )
    }

    companion object {
        private val SUCCESSFUL_SPAWN_RESULT: SpawnResult =
            Builder().setRunnerName("test").setStatus(Status.SUCCESS).build()
        private val SUCCESSFUL_LOCAL_SPAWN_RESULT: SpawnResult =
            Builder().setRunnerName("local").setStatus(Status.SUCCESS).build()
        private val SUCCESSFUL_REMOTE_SPAWN_RESULT: SpawnResult =
            Builder().setRunnerName("remote").setStatus(Status.SUCCESS).build()
        private val FAILURE_DETAIL: FailureDetail? =
            FailureDetail.newBuilder().setExecution(Execution.getDefaultInstance()).build()

        private fun createMockActionExecutionContext(
            localStrategy: SandboxedSpawnStrategy, remoteStrategy: SandboxedSpawnStrategy
        ): ActionExecutionContext {
            val actionExecutionContext: ActionExecutionContext =
                Mockito.mock<ActionExecutionContext>(ActionExecutionContext::class.java)
            Mockito.`when`<T?>(actionExecutionContext.getFileOutErr()).thenReturn(TestFileOutErr())
            Mockito.`when`<T?>(actionExecutionContext.getContext(DynamicStrategyRegistry::class.java))
                .thenReturn(
                    object : DynamicStrategyRegistry() {
                        public override fun getDynamicSpawnActionContexts(
                            spawn: Spawn?, dynamicMode: DynamicMode
                        ): com.google.common.collect.ImmutableList<SandboxedSpawnStrategy?> {
                            when (dynamicMode) {
                                LOCAL -> return com.google.common.collect.ImmutableList.of<SandboxedSpawnStrategy?>(
                                    localStrategy
                                )

                                REMOTE -> return com.google.common.collect.ImmutableList.of<SandboxedSpawnStrategy?>(
                                    remoteStrategy
                                )
                            }
                            throw java.lang.AssertionError("Unexpected mode: " + dynamicMode)
                        }

                        public override fun notifyUsedDynamic(actionContextRegistry: ActionContextRegistry?) {}
                    })
            Mockito.`when`<T?>(actionExecutionContext.withFileOutErr(ArgumentMatchers.any<T?>()))
                .thenReturn(actionExecutionContext)
            return actionExecutionContext
        }

        private fun createMockSpawn(): Spawn {
            return Mockito.mock<Spawn>(Spawn::class.java)
        }

        @Throws(java.lang.InterruptedException::class, ExecException::class)
        private fun createMockSpawnStrategy(): SandboxedSpawnStrategy {
            return createMockSpawnStrategy(true)
        }

        @Throws(java.lang.InterruptedException::class, ExecException::class)
        private fun createMockSpawnStrategy(canExec: Boolean): SandboxedSpawnStrategy {
            val strategy: SandboxedSpawnStrategy =
                Mockito.mock<SandboxedSpawnStrategy>(SandboxedSpawnStrategy::class.java)
            Mockito.`when`<T?>(strategy.canExec(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
                .thenReturn(canExec)
            Mockito.`when`<T?>(strategy.exec(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
                .thenThrow(java.lang.UnsupportedOperationException::class.java)
            return strategy
        }

        @Throws(java.lang.InterruptedException::class, ExecException::class)
        private fun createMockSpawnStrategy(name: String?): SandboxedSpawnStrategy {
            val strategy: SandboxedSpawnStrategy =
                Mockito.mock<SandboxedSpawnStrategy>(SandboxedSpawnStrategy::class.java)
            Mockito.`when`<T?>(strategy.canExec(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
                .thenReturn(true)
            Mockito.`when`<T?>(strategy.toString()).thenReturn(name)
            Mockito.`when`<T?>(strategy.exec(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
                .thenThrow(java.lang.UnsupportedOperationException::class.java)
            return strategy
        }
    }
}
