// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ActionContext

/** Tests for [DynamicSpawnStrategy].  */
@RunWith(JUnit4::class)
class DynamicSpawnStrategyTest {
    private var testRoot: Path? = null
    private var executorServiceForCleanup: ExecutorService? = null
    private var outErr: FileOutErr? = null
    private val actionKeyContext: ActionKeyContext = ActionKeyContext()

    /** Hook to implement per-test custom logic in the [MockSpawnStrategy].  */
    internal fun interface DoExec {
        @Throws(ExecException::class, java.lang.InterruptedException::class)
        fun run(self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext?)

        companion object {
            val NOTHING: DoExec =
                DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? -> }
        }
    }

    /**
     * Minimal implementation of a strategy for testing purposes.
     * 
     * 
     * All the logic in here must be applicable to all tests. If any test needs to special-case
     * some aspect of this logic, then it must extend this subclass as necessary.
     */
    private inner class MockSpawnStrategy @kotlin.jvm.JvmOverloads constructor(
        /** Identifier of this class for error reporting purposes.  */
        private val name: String?,
        /** Hook to implement per-test custom logic.  */
        private val doExecBeforeStop: DoExec = DoExec.Companion.NOTHING,
        private val doExecAfterStop: DoExec = DoExec.Companion.NOTHING,
        private val canExec: Boolean = true
    ) : SandboxedSpawnStrategy {
        /** Lazily set to the spawn passed to [.exec] as soon as that hook is invoked.  */
        @kotlin.concurrent.Volatile
        private var executedSpawn: Spawn? = null

        /** Tracks whether [.exec] completed successfully or not.  */
        private val succeeded: CountDownLatch = CountDownLatch(1)

        /** Helper to record an execution failure from within [.doExecBeforeStop].  */
        @Throws(ExecException::class)
        fun failExecution(actionExecutionContext: ActionExecutionContext) {
            try {
                FileSystemUtils.appendIsoLatin1(
                    actionExecutionContext.getFileOutErr().getOutputPath(), "action failed with " + name
                )
            } catch (e: IOException) {
                throw java.lang.IllegalStateException(e)
            }
            throw UserExecException(createFailureDetail(name + " failed to execute the Spawn"))
        }

        @Throws(ExecException::class, java.lang.InterruptedException::class)
        public override fun exec(
            spawn: Spawn,
            actionExecutionContext: ActionExecutionContext,
            stopConcurrentSpawns: SandboxedSpawnStrategy.StopConcurrentSpawns?
        ): com.google.common.collect.ImmutableList<SpawnResult?> {
            executedSpawn = spawn

            doExecBeforeStop.run(this, spawn, actionExecutionContext)
            if (stopConcurrentSpawns != null) {
                stopConcurrentSpawns.stop(0, "", outErr)
                doExecAfterStop.run(this, spawn, actionExecutionContext)
            }

            for (output in spawn.getOutputFiles()) {
                try {
                    FileSystemUtils.writeIsoLatin1(testRoot.getRelative(output.getExecPath()), name)
                } catch (e: IOException) {
                    throw java.lang.IllegalStateException(e)
                }
            }

            try {
                FileSystemUtils.appendIsoLatin1(
                    actionExecutionContext.getFileOutErr().getOutputPath(),
                    "output files written with " + name
                )
            } catch (e: IOException) {
                throw java.lang.IllegalStateException(e)
            }

            succeeded.countDown()

            return com.google.common.collect.ImmutableList.of<SpawnResult?>()
        }

        public override fun exec(
            spawn: Spawn?, actionExecutionContext: ActionExecutionContext?
        ): com.google.common.collect.ImmutableList<SpawnResult?>? {
            throw java.lang.IllegalStateException("Not expected to be called")
        }

        public override fun canExec(
            spawn: Spawn?,
            actionContextRegistry: ActionContext.ActionContextRegistry?
        ): Boolean {
            return canExec
        }

        fun getExecutedSpawn(): Spawn? {
            return executedSpawn
        }

        /** Returns true if [.exec] was called and completed successfully; does not block.  */
        fun succeeded(): Boolean {
            return succeeded.getCount() == 0L
        }
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        testRoot =
            com.google.devtools.build.lib.testutil.TestUtils.createUniqueTmpDir(com.google.devtools.build.lib.vfs.util.FileSystems.getNativeFileSystem())
        outErr = FileOutErr(testRoot.getRelative("stdout"), testRoot.getRelative("stderr"))
    }

    /**
     * Creates a new dynamic spawn strategy with different strategies for local and remote execution
     * and a default multi-threaded executor service.
     * 
     * @param localStrategy the strategy for local execution
     * @param remoteStrategy the strategy for remote execution
     * @return the constructed dynamic strategy
     * @throws AbruptExitException if creating the strategy with the given parameters fails
     */
    @Throws(AbruptExitException::class)
    private fun createSpawnStrategy(
        localStrategy: MockSpawnStrategy?, remoteStrategy: MockSpawnStrategy?
    ): StrategyAndContext {
        return createSpawnStrategyWithExecutor(
            localStrategy, remoteStrategy, Executors.newCachedThreadPool()
        )
    }

    /**
     * Creates a new dynamic spawn strategy with different strategies for local, remote, and sandboxed
     * execution.
     * 
     * 
     * TODO(jmmv): This overload should not be necessary now that we do not special-case the
     * handling of sandboxed strategies any longer. Remove once the sandbox-specific flags are gone.
     * 
     * @param localStrategy the default strategy for local execution
     * @param remoteStrategy the default strategy for remote execution
     * @param sandboxedStrategy the strategy to use when the mnemonic matches `testMnemonic`.
     * @return the constructed dynamic strategy
     * @throws AbruptExitException if creating the strategy with the given parameters fails
     */
    @Throws(AbruptExitException::class)
    private fun createSpawnStrategy(
        localStrategy: MockSpawnStrategy?,
        remoteStrategy: MockSpawnStrategy?,
        sandboxedStrategy: MockSpawnStrategy?
    ): StrategyAndContext {
        return createSpawnStrategyWithExecutor(
            localStrategy, remoteStrategy, sandboxedStrategy, Executors.newCachedThreadPool()
        )
    }

    /**
     * Creates a new dynamic spawn strategy with different strategies for local and remote execution.
     * 
     * @param localStrategy the strategy for local execution
     * @param remoteStrategy the strategy for remote execution
     * @param executorService the executor to pass to the dynamic strategy
     * @return the constructed dynamic strategy
     * @throws AbruptExitException if creating the strategy with the given parameters fails
     */
    @Throws(AbruptExitException::class)
    private fun createSpawnStrategyWithExecutor(
        localStrategy: MockSpawnStrategy?,
        remoteStrategy: MockSpawnStrategy?,
        executorService: ExecutorService
    ): StrategyAndContext {
        return createSpawnStrategyWithExecutor(localStrategy, remoteStrategy, null, executorService)
    }

    /**
     * Creates a new dynamic spawn strategy with different strategies for local, remote, and sandboxed
     * execution.
     * 
     * 
     * TODO(jmmv): This overload should not be necessary now that we do not special-case the
     * handling of sandboxed strategies any longer. Remove once the sandbox-specific flags are gone.
     * 
     * @param localStrategy the default strategy for local execution
     * @param remoteStrategy the default strategy for remote execution
     * @param sandboxedStrategy the strategy to use when the mnemonic matches `testMnemonic`.
     * @param executorService the executor to pass to the dynamic strategy
     * @return the constructed dynamic strategy
     * @throws AbruptExitException if creating the strategy with the given parameters fails
     */
    @Throws(AbruptExitException::class)
    private fun createSpawnStrategyWithExecutor(
        localStrategy: MockSpawnStrategy?,
        remoteStrategy: MockSpawnStrategy?,
        sandboxedStrategy: MockSpawnStrategy?,
        executorService: ExecutorService
    ): StrategyAndContext {
        val dynamicLocalStrategies: com.google.common.collect.ImmutableList.Builder<MutableMap.MutableEntry<String?, MutableList<String?>?>?> =
            com.google.common.collect.ImmutableList.builder<MutableMap.MutableEntry<String?, MutableList<String?>?>?>()
                .add(
                    com.google.common.collect.Maps.immutableEntry<String?, MutableList<String?>?>(
                        "",
                        com.google.common.collect.ImmutableList.of<String?>("mock-local")
                    )
                )
        val dynamicRemoteStrategies: com.google.common.collect.ImmutableList.Builder<MutableMap.MutableEntry<String?, MutableList<String?>?>?> =
            com.google.common.collect.ImmutableList.builder<MutableMap.MutableEntry<String?, MutableList<String?>?>?>()
                .add(
                    com.google.common.collect.Maps.immutableEntry<String?, MutableList<String?>?>(
                        "",
                        com.google.common.collect.ImmutableList.of<String?>("mock-remote")
                    )
                )

        if (sandboxedStrategy != null) {
            dynamicLocalStrategies.add(
                com.google.common.collect.Maps.immutableEntry<String?, MutableList<String?>?>(
                    "testMnemonic",
                    com.google.common.collect.ImmutableList.of<String?>("mock-sandboxed")
                )
            )
            dynamicRemoteStrategies.add(
                com.google.common.collect.Maps.immutableEntry<String?, MutableList<String?>?>(
                    "testMnemonic",
                    com.google.common.collect.ImmutableList.of<String?>("mock-sandboxed")
                )
            )
        }

        val options: DynamicExecutionOptions =
            com.google.devtools.common.options.Options.getDefaults<DynamicExecutionOptions>(DynamicExecutionOptions::class.java)
        options.dynamicLocalStrategy = dynamicLocalStrategies.build()
        options.dynamicRemoteStrategy = dynamicRemoteStrategies.build()
        options.internalSpawnScheduler = true
        options.localExecutionDelay = 0

        com.google.common.base.Preconditions.checkState(executorServiceForCleanup == null)
        executorServiceForCleanup = executorService

        val moduleActionContextRegistryBuilder: ModuleActionContextRegistry.Builder =
            ModuleActionContextRegistry.builder()
        val spawnStrategyRegistryBuilder: SpawnStrategyRegistry.Builder = SpawnStrategyRegistry.builder()

        spawnStrategyRegistryBuilder.registerStrategy(localStrategy, "mock-local")
        spawnStrategyRegistryBuilder.registerStrategy(remoteStrategy, "mock-remote")

        if (sandboxedStrategy != null) {
            spawnStrategyRegistryBuilder.registerStrategy(sandboxedStrategy, "mock-sandboxed")
        }

        val dynamicExecutionModule: DynamicExecutionModule = DynamicExecutionModule(executorService)
        dynamicExecutionModule.registerSpawnStrategies(spawnStrategyRegistryBuilder, options, 10, 10)

        val spawnStrategyRegistry: SpawnStrategyRegistry = spawnStrategyRegistryBuilder.build()

        moduleActionContextRegistryBuilder.register(SpawnStrategyRegistry::class.java, spawnStrategyRegistry)
        moduleActionContextRegistryBuilder.register(
            DynamicStrategyRegistry::class.java, spawnStrategyRegistry
        )
        val moduleActionContextRegistry: ModuleActionContextRegistry? =
            moduleActionContextRegistryBuilder.build()

        val executor: Executor =
            BlazeExecutor( /* fileSystem= */
                null,
                testRoot,  /* reporter= */
                null,  /* clock= */
                null,
                BugReporter.defaultInstance(),
                OptionsParser.builder()
                    .optionsClasses(com.google.common.collect.ImmutableList.of<E?>(ExecutionOptions::class.java))
                    .build(),
                moduleActionContextRegistry,
                spawnStrategyRegistry
            )

        val actionExecutionContext: ActionExecutionContext? =
            ActionsTestUtil.createContext(
                executor,  /* eventHandler= */
                null,
                actionKeyContext,
                outErr,
                SingleBuildFileCache(
                    testRoot.getPathString(),
                    PathFragment.create("dummy-output-path"),
                    testRoot.getFileSystem(),
                    SyscallCache.NO_CACHE
                ),  /* outputMetadataStore= */
                null,  /* clientEnv= */
                java.lang.System.getenv()
            )

        val dynamicStrategies: MutableList<out SpawnStrategy?> =
            spawnStrategyRegistry.getStrategies(
                newCustomSpawn("RunDynamic", com.google.common.collect.ImmutableMap.of<String?, String?>()),
                { event -> })

        val optionalContext: java.util.Optional<out SpawnStrategy?> =
            dynamicStrategies.stream().filter { c: SpawnStrategy? -> c is DynamicSpawnStrategy }.findAny()
        com.google.common.base.Preconditions.checkState(
            optionalContext.isPresent(),
            "Expected module to register a dynamic strategy"
        )

        return StrategyAndContext(optionalContext.get(), actionExecutionContext)
    }

    private class NullActionWithMnemonic(
        val mnemonic: String?,
        inputs: MutableList<Artifact?>?,
        vararg outputs: Artifact?
    ) : NullAction(inputs, *outputs)

    @org.junit.After
    @Throws(java.lang.Exception::class)
    fun tearDown() {
        if (executorServiceForCleanup != null) {
            executorServiceForCleanup.shutdownNow()
        }
        if (testRoot != null) {
            try {
                testRoot.deleteTree()
            } catch (e: FileNotFoundException) {
                // This can happen if one of the dynamic threads are still cleaning up. No big deal.
            }
        }
    }

    /** Constructs a new spawn with a custom mnemonic and execution info.  */
    private fun newCustomSpawn(
        mnemonic: String?,
        executionInfo: com.google.common.collect.ImmutableMap<String?, String?>?
    ): Spawn {
        val inputArtifact: Artifact =
            ActionsTestUtil.createArtifact(
                ArtifactRoot.asSourceRoot(Root.fromPath(testRoot)), "input.txt"
            )
        val outputArtifact: Artifact =
            ActionsTestUtil.createArtifact(
                ArtifactRoot.asSourceRoot(Root.fromPath(testRoot)), "output.txt"
            )

        val action: ActionExecutionMetadata =
            NullActionWithMnemonic(
                mnemonic,
                com.google.common.collect.ImmutableList.of<Artifact?>(inputArtifact),
                outputArtifact
            )
        return BaseSpawn(
            com.google.common.collect.ImmutableList.of<E?>(),
            com.google.common.collect.ImmutableMap.of<K?, V?>(),
            executionInfo,
            action,
            ResourceSet.create(1, 0, 0)
        )
    }

    /** Constructs a new spawn that can be run locally and remotely with arbitrary settings.  */
    private fun newDynamicSpawn(): Spawn {
        return newCustomSpawn("Null", com.google.common.collect.ImmutableMap.of<String?, String?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nonRemotableSpawnRunsLocally() {
        val localStrategy = MockSpawnStrategy("MockLocalSpawnStrategy")
        val remoteStrategy = MockSpawnStrategy("MockRemoteSpawnStrategy")
        val strategyAndContext = createSpawnStrategy(localStrategy, remoteStrategy)

        val spawn: Spawn =
            newCustomSpawn("Null", com.google.common.collect.ImmutableMap.of<String?, String?>("local", "1"))
        strategyAndContext.exec(spawn)

        assertThat(localStrategy.getExecutedSpawn()).isEqualTo(spawn)
        Truth.assertThat(localStrategy.succeeded()).isTrue()
        assertThat(remoteStrategy.getExecutedSpawn()).isNull()
        Truth.assertThat(remoteStrategy.succeeded()).isFalse()

        com.google.common.truth.Subject.contains("output files written with MockLocalSpawnStrategy")
        assertThat(outErr.outAsLatin1()).doesNotContain("MockRemoteSpawnStrategy")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun localSpawnUsesStrategyByMnemonicWithWorkerFlagDisabled() {
        val localStrategy = MockSpawnStrategy("MockLocalSpawnStrategy")
        val remoteStrategy = MockSpawnStrategy("MockRemoteSpawnStrategy")
        val sandboxedStrategy = MockSpawnStrategy("MockSandboxedSpawnStrategy")
        val strategyAndContext =
            createSpawnStrategy(localStrategy, remoteStrategy, sandboxedStrategy)

        val spawn: Spawn =
            newCustomSpawn("testMnemonic", com.google.common.collect.ImmutableMap.of<String?, String?>("local", "1"))
        strategyAndContext.exec(spawn)

        assertThat(localStrategy.getExecutedSpawn()).isNull()
        Truth.assertThat(localStrategy.succeeded()).isFalse()
        assertThat(remoteStrategy.getExecutedSpawn()).isNull()
        Truth.assertThat(remoteStrategy.succeeded()).isFalse()
        assertThat(sandboxedStrategy.getExecutedSpawn()).isEqualTo(spawn)
        Truth.assertThat(sandboxedStrategy.succeeded()).isTrue()

        com.google.common.truth.Subject.contains("output files written with MockSandboxedSpawnStrategy")
        assertThat(outErr.outAsLatin1()).doesNotContain("MockLocalSpawnStrategy")
        assertThat(outErr.outAsLatin1()).doesNotContain("MockRemoteSpawnStrategy")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun remoteSpawnUsesStrategyByMnemonic() {
        val localStrategy = MockSpawnStrategy("MockLocalSpawnStrategy")
        val remoteStrategy = MockSpawnStrategy("MockRemoteSpawnStrategy")
        val sandboxedStrategy = MockSpawnStrategy("MockSandboxedSpawnStrategy")
        val strategyAndContext =
            createSpawnStrategy(localStrategy, remoteStrategy, sandboxedStrategy)

        val spawn: Spawn =
            newCustomSpawn("testMnemonic", com.google.common.collect.ImmutableMap.of<String?, String?>("remote", "1"))
        strategyAndContext.exec(spawn)

        assertThat(localStrategy.getExecutedSpawn()).isNull()
        Truth.assertThat(localStrategy.succeeded()).isFalse()
        assertThat(remoteStrategy.getExecutedSpawn()).isNull()
        Truth.assertThat(remoteStrategy.succeeded()).isFalse()
        assertThat(sandboxedStrategy.getExecutedSpawn()).isEqualTo(spawn)
        Truth.assertThat(sandboxedStrategy.succeeded()).isTrue()

        com.google.common.truth.Subject.contains("output files written with MockSandboxedSpawnStrategy")
        assertThat(outErr.outAsLatin1()).doesNotContain("MockLocalSpawnStrategy")
        assertThat(outErr.outAsLatin1()).doesNotContain("MockRemoteSpawnStrategy")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionSucceedsIfLocalExecutionSucceedsEvenIfRemoteFailsLater() {
        val countDownLatch: CountDownLatch = CountDownLatch(2)

        val localStrategy =
            MockSpawnStrategy(
                "MockLocalSpawnStrategy",
                DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                    countDownAndWait(
                        countDownLatch
                    )
                },
                DoExec.Companion.NOTHING
            )

        val remoteStrategy =
            MockSpawnStrategy(
                "MockRemoteSpawnStrategy",
                DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                    countDownAndWait(countDownLatch)
                    java.lang.Thread.sleep(2000)
                    self!!.failExecution(actionExecutionContext)
                },
                DoExec.Companion.NOTHING
            )

        val strategyAndContext = createSpawnStrategy(localStrategy, remoteStrategy)

        val spawn: Spawn = newDynamicSpawn()
        strategyAndContext.exec(spawn)

        assertThat(localStrategy.getExecutedSpawn()).isEqualTo(spawn)
        Truth.assertThat(localStrategy.succeeded()).isTrue()
        assertThat(remoteStrategy.getExecutedSpawn()).isEqualTo(spawn)
        Truth.assertThat(remoteStrategy.succeeded()).isFalse()

        com.google.common.truth.Subject.contains("output files written with MockLocalSpawnStrategy")
        assertThat(outErr.outAsLatin1()).doesNotContain("MockRemoteSpawnStrategy")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionSucceedsIfRemoteExecutionSucceedsEvenIfLocalFailsLater() {
        val countDownLatch: CountDownLatch = CountDownLatch(2)

        val localStrategy =
            MockSpawnStrategy(
                "MockLocalSpawnStrategy",
                DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                    countDownAndWait(countDownLatch)
                    java.lang.Thread.sleep(2000)
                    self!!.failExecution(actionExecutionContext)
                },
                DoExec.Companion.NOTHING
            )

        val remoteStrategy =
            MockSpawnStrategy(
                "MockRemoteSpawnStrategy",
                DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                    countDownAndWait(
                        countDownLatch
                    )
                },
                DoExec.Companion.NOTHING
            )

        val strategyAndContext = createSpawnStrategy(localStrategy, remoteStrategy)

        val spawn: Spawn = newDynamicSpawn()
        strategyAndContext.exec(spawn)

        assertThat(localStrategy.getExecutedSpawn()).isEqualTo(spawn)
        Truth.assertThat(localStrategy.succeeded()).isFalse()
        assertThat(remoteStrategy.getExecutedSpawn()).isEqualTo(spawn)
        Truth.assertThat(remoteStrategy.succeeded()).isTrue()

        com.google.common.truth.Subject.contains("output files written with MockRemoteSpawnStrategy")
        assertThat(outErr.outAsLatin1()).doesNotContain("MockLocalSpawnStrategy")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionSucceedsIfLocalExecutionSucceedsEvenIfRemoteRunsNothing() {
        val localStrategy = MockSpawnStrategy("MockLocalSpawnStrategy")

        val remoteStrategy =
            MockSpawnStrategy("MockRemoteSpawnStrategy", DoExec.Companion.NOTHING, DoExec.Companion.NOTHING, false)

        val strategyAndContext = createSpawnStrategy(localStrategy, remoteStrategy)

        val spawn: Spawn = newDynamicSpawn()
        strategyAndContext.exec(spawn)

        assertThat(localStrategy.getExecutedSpawn()).isEqualTo(spawn)
        Truth.assertThat(localStrategy.succeeded()).isTrue()
        assertThat(remoteStrategy.getExecutedSpawn()).isNull()
        Truth.assertThat(remoteStrategy.succeeded()).isFalse()

        com.google.common.truth.Subject.contains("output files written with MockLocalSpawnStrategy")
        assertThat(outErr.outAsLatin1()).doesNotContain("MockRemoteSpawnStrategy")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionSucceedsIfRemoteExecutionSucceedsEvenIfLocalRunsNothing() {
        val localStrategy =
            MockSpawnStrategy("MockLocalSpawnStrategy", DoExec.Companion.NOTHING, DoExec.Companion.NOTHING, false)

        val remoteStrategy = MockSpawnStrategy("MockRemoteSpawnStrategy")

        val strategyAndContext = createSpawnStrategy(localStrategy, remoteStrategy)

        val spawn: Spawn = newDynamicSpawn()
        strategyAndContext.exec(spawn)

        assertThat(localStrategy.getExecutedSpawn()).isNull()
        Truth.assertThat(localStrategy.succeeded()).isFalse()
        assertThat(remoteStrategy.getExecutedSpawn()).isEqualTo(spawn)
        Truth.assertThat(remoteStrategy.succeeded()).isTrue()

        com.google.common.truth.Subject.contains("output files written with MockRemoteSpawnStrategy")
        assertThat(outErr.outAsLatin1()).doesNotContain("MockLocalSpawnStrategy")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionFailsIfLocalFailsImmediatelyEvenIfRemoteSucceedsLater() {
        val countDownLatch: CountDownLatch = CountDownLatch(2)

        val localStrategy =
            MockSpawnStrategy(
                "MockLocalSpawnStrategy",
                DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                    countDownAndWait(countDownLatch)
                    self!!.failExecution(actionExecutionContext)
                },
                DoExec.Companion.NOTHING
            )

        val remoteStrategy =
            MockSpawnStrategy(
                "MockRemoteSpawnStrategy",
                DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                    countDownAndWait(countDownLatch)
                    java.lang.Thread.sleep(2000)
                },
                DoExec.Companion.NOTHING
            )

        val strategyAndContext = createSpawnStrategy(localStrategy, remoteStrategy)

        val spawn: Spawn = newDynamicSpawn()
        val e: ExecException? = org.junit.Assert.assertThrows<T?>(
            ExecException::class.java,
            org.junit.function.ThrowingRunnable { strategyAndContext.exec(spawn) })
        assertThat(e).hasMessageThat().matches("MockLocalSpawnStrategy failed to execute the Spawn")

        assertThat(localStrategy.getExecutedSpawn()).isEqualTo(spawn)
        Truth.assertThat(localStrategy.succeeded()).isFalse()
        assertThat(remoteStrategy.getExecutedSpawn()).isEqualTo(spawn)
        Truth.assertThat(remoteStrategy.succeeded()).isFalse()

        com.google.common.truth.Subject.contains("action failed with MockLocalSpawnStrategy")
        assertThat(outErr.outAsLatin1()).doesNotContain("MockRemoteSpawnStrategy")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionFailsIfRemoteFailsImmediatelyEvenIfLocalSucceedsLater() {
        val countDownLatch: CountDownLatch = CountDownLatch(2)

        val localStrategy =
            MockSpawnStrategy(
                "MockLocalSpawnStrategy",
                DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                    countDownAndWait(countDownLatch)
                    java.lang.Thread.sleep(2000)
                },
                DoExec.Companion.NOTHING
            )

        val remoteStrategy =
            MockSpawnStrategy(
                "MockRemoteSpawnStrategy",
                DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                    countDownAndWait(countDownLatch)
                    self!!.failExecution(actionExecutionContext)
                },
                DoExec.Companion.NOTHING
            )

        val strategyAndContext = createSpawnStrategy(localStrategy, remoteStrategy)

        val spawn: Spawn = newDynamicSpawn()
        val e: ExecException? = org.junit.Assert.assertThrows<T?>(
            ExecException::class.java,
            org.junit.function.ThrowingRunnable { strategyAndContext.exec(spawn) })
        assertThat(e).hasMessageThat().matches("MockRemoteSpawnStrategy failed to execute the Spawn")

        assertThat(localStrategy.getExecutedSpawn()).isEqualTo(spawn)
        Truth.assertThat(localStrategy.succeeded()).isFalse()
        assertThat(remoteStrategy.getExecutedSpawn()).isEqualTo(spawn)
        Truth.assertThat(remoteStrategy.succeeded()).isFalse()

        com.google.common.truth.Subject.contains("action failed with MockRemoteSpawnStrategy")
        assertThat(outErr.outAsLatin1()).doesNotContain("MockLocalSpawnStrategy")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionFailsIfLocalAndRemoteFail() {
        val countDownLatch: CountDownLatch = CountDownLatch(2)

        val localStrategy =
            MockSpawnStrategy(
                "MockLocalSpawnStrategy",
                DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                    countDownAndWait(countDownLatch)
                    self!!.failExecution(actionExecutionContext)
                },
                DoExec.Companion.NOTHING
            )

        val remoteStrategy =
            MockSpawnStrategy(
                "MockRemoteSpawnStrategy",
                DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                    countDownAndWait(countDownLatch)
                    self!!.failExecution(actionExecutionContext)
                },
                DoExec.Companion.NOTHING
            )

        val strategyAndContext = createSpawnStrategy(localStrategy, remoteStrategy)

        val spawn: Spawn = newDynamicSpawn()
        val e: ExecException? = org.junit.Assert.assertThrows<T?>(
            ExecException::class.java,
            org.junit.function.ThrowingRunnable { strategyAndContext.exec(spawn) })
        assertThat(e)
            .hasMessageThat()
            .matches("Mock(Local|Remote)SpawnStrategy failed to execute the Spawn")

        assertThat(localStrategy.getExecutedSpawn()).isEqualTo(spawn)
        Truth.assertThat(localStrategy.succeeded()).isFalse()
        assertThat(remoteStrategy.getExecutedSpawn()).isEqualTo(spawn)
        Truth.assertThat(remoteStrategy.succeeded()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionFailsIfLocalAndRemoteRunNothing() {
        val localStrategy =
            MockSpawnStrategy("MockLocalSpawnStrategy", DoExec.Companion.NOTHING, DoExec.Companion.NOTHING, false)

        val remoteStrategy =
            MockSpawnStrategy("MockRemoteSpawnStrategy", DoExec.Companion.NOTHING, DoExec.Companion.NOTHING, false)

        val strategyAndContext = createSpawnStrategy(localStrategy, remoteStrategy)

        val spawn: Spawn = newDynamicSpawn()
        val e: ExecException? = org.junit.Assert.assertThrows<T?>(
            UserExecException::class.java,
            org.junit.function.ThrowingRunnable { strategyAndContext.exec(spawn) })

        // Has "No usable", followed by both dynamic_local_strategy and dynamic_remote_strategy in,
        // followed by the action's mnemonic.
        val regexMatch =
            ("[nN]o usable\\b.*\\bdynamic_local_strategy\\b.*\\bdynamic_remote_strategy\\b.*\\b"
                    + spawn.getMnemonic()
                    + "\\b")

        assertThat(e).hasMessageThat().containsMatch(regexMatch)

        assertThat(localStrategy.getExecutedSpawn()).isNull()
        Truth.assertThat(localStrategy.succeeded()).isFalse()
        assertThat(remoteStrategy.getExecutedSpawn()).isNull()
        Truth.assertThat(remoteStrategy.succeeded()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stopConcurrentSpawnsWaitForCompletion() {
        val countDownLatch: CountDownLatch = CountDownLatch(2)

        val slowCleanupFinished: AtomicBoolean = AtomicBoolean(false)
        val localStrategy =
            MockSpawnStrategy(
                "MockLocalSpawnStrategy",
                DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                    try {
                        countDownAndWait(countDownLatch)
                        // Block indefinitely waiting for the remote branch to interrupt us.
                        java.lang.Thread.sleep(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
                        TestCase.fail("Should have been interrupted")
                    } catch (e: java.lang.InterruptedException) {
                        // Wait for "long enough" hoping that the remoteStrategy will have enough time to
                        // check the value of slowCleanupFinished before we finish this sleep, in case we
                        // have a bug.
                        com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly(5, TimeUnit.SECONDS)
                        slowCleanupFinished.set(true)
                    }
                },
                DoExec.Companion.NOTHING
            )

        val remoteStrategy =
            MockSpawnStrategy(
                "MockRemoteSpawnStrategy",
                DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                    countDownAndWait(
                        countDownLatch
                    )
                },
                DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                    // This runs after we have asked the local spawn to complete and, in theory, awaited
                    // for InterruptedException to propagate. Make sure that's the case here by checking
                    // that we did indeed wait for the slow process.
                    if (!slowCleanupFinished.get()) {
                        TestCase.fail("Did not await for the local branch to do its cleanup")
                    }
                })

        val strategyAndContext = createSpawnStrategy(localStrategy, remoteStrategy)

        val spawn: Spawn = newDynamicSpawn()
        strategyAndContext.exec(spawn)

        assertThat(localStrategy.getExecutedSpawn()).isEqualTo(spawn)
        Truth.assertThat(localStrategy.succeeded()).isFalse()
        assertThat(remoteStrategy.getExecutedSpawn()).isEqualTo(spawn)
        Truth.assertThat(remoteStrategy.succeeded()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noDeadlockWithSingleThreadedExecutor() {
        val localStrategy = MockSpawnStrategy("MockLocalSpawnStrategy")
        val remoteStrategy = MockSpawnStrategy("MockRemoteSpawnStrategy")
        val strategyAndContext =
            createSpawnStrategyWithExecutor(
                localStrategy, remoteStrategy, Executors.newSingleThreadExecutor()
            )

        val spawn: Spawn = newDynamicSpawn()
        strategyAndContext.exec(spawn)

        assertThat(localStrategy.getExecutedSpawn()).isEqualTo(spawn)
        Truth.assertThat(localStrategy.succeeded()).isTrue()

        /*
     * The single-threaded executorService#invokeAny does not comply to the contract where
     * the callables are *always* called sequentially. In this case, both spawns will start
     * executing, but the local one will always succeed as it's the first to be called. The remote
     * one will then be cancelled, or is null if the local one completes before the remote one
     * starts.
     *
     * See the documentation of {@link BoundedExectorService#invokeAny(Collection)}, specifically:
     * "The following is less efficient (it goes on submitting tasks even if there is some task
     * already finished), but quite straight-forward.".
     */
        assertThat(remoteStrategy.getExecutedSpawn()).isAnyOf(spawn, null)
        Truth.assertThat(remoteStrategy.succeeded()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun interruptDuringExecutionDoesActuallyInterruptTheExecution() {
        val countDownLatch: CountDownLatch = CountDownLatch(2)

        val localStrategy =
            MockSpawnStrategy(
                "MockLocalSpawnStrategy",
                DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                    countDownAndWait(countDownLatch)
                    java.lang.Thread.sleep(60000)
                },
                DoExec.Companion.NOTHING
            )

        val remoteStrategy =
            MockSpawnStrategy(
                "MockRemoteSpawnStrategy",
                DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                    countDownAndWait(countDownLatch)
                    java.lang.Thread.sleep(60000)
                },
                DoExec.Companion.NOTHING
            )

        val strategyAndContext = createSpawnStrategy(localStrategy, remoteStrategy)

        val testThread: TestThread =
            TestThread(
                TestRunnable {
                    try {
                        val spawn: Spawn = newDynamicSpawn()
                        strategyAndContext.exec(spawn)
                    } catch (e: java.lang.InterruptedException) {
                        // This is expected.
                    }
                })
        testThread.start()
        countDownLatch.await(5, TimeUnit.SECONDS)
        testThread.interrupt()
        testThread.joinAndAssertState(5000)

        assertThat(outErr.getOutputPath().exists()).isFalse()
        assertThat(outErr.getErrorPath().exists()).isFalse()
    }

    /** Hook to validate the result of the strategy's execution.  */
    internal fun interface CheckExecResult {
        @Throws(java.lang.Exception::class)
        fun check(e: java.lang.Exception?)
    }

    /**
     * Runs a test to check that both spawns finished under various conditions before the strategy's
     * `exec` method returns control.
     * 
     * @param executionFails causes one of the branches in the execution to terminate with an
     * execution exception
     * @param interruptThread causes the strategy's execution to be interrupted while it is waiting
     * for its branches to complete
     * @param checkExecResult a lambda to validate the result of the execution. Receives null if the
     * execution completed successfully, or else the raised exception.
     */
    @Throws(java.lang.Exception::class)
    private fun assertThatStrategyWaitsForBothSpawnsToFinish(
        executionFails: Boolean, interruptThread: Boolean, checkExecResult: CheckExecResult
    ) {
        if (true) {
            // TODO(b/177406907): jmmv@: I spent *days* trying to make these tests work reliably with the
            // new dynamic spawn scheduler implementation but I keep encountering tricky race conditions
            // everywhere. I have strong reasons to believe that the races are due to inherent problems in
            // these tests, not in the actual DynamicSpawnScheduler implementation. So whatever. We should
            // revisit these as a new set of tests now that the legacy spawn scheduler has gone away.
            logger.atInfo().log("Skipping test")
            return
        }
        val stopLocal: AtomicBoolean = AtomicBoolean(false)
        val executionCanProceed: CountDownLatch = CountDownLatch(2)
        val remoteDone: CountDownLatch = CountDownLatch(1)

        val localStrategy =
            MockSpawnStrategy(
                "MockLocalSpawnStrategy",
                DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                    executionCanProceed.countDown()
                    // We cannot use a synchronization primitive to block termination of this thread
                    // because we expect to be interrupted by the remote strategy, and even in that case
                    // we want to control exactly when this finishes. We could wait for and swallow the
                    // interrupt before waiting again on a latch here... but swallowing the interrupt can
                    // lead to race conditions.
                    while (!stopLocal.get()) {
                        com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly(
                            1,
                            TimeUnit.MILLISECONDS
                        )
                    }
                    throw java.lang.InterruptedException("Local stopped")
                },
                DoExec.Companion.NOTHING
            )

        val remoteStrategy =
            MockSpawnStrategy(
                "MockRemoteSpawnStrategy",
                DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                    try {
                        // Wait until the local branch has started so that our completion causes it to be
                        // interrupted in a known location.
                        countDownAndWait(executionCanProceed)

                        if (executionFails) {
                            self!!.failExecution(actionExecutionContext)
                            throw java.lang.AssertionError("Not reachable")
                        }
                    } finally {
                        remoteDone.countDown()
                    }
                },
                DoExec.Companion.NOTHING
            )

        val strategyAndContext = createSpawnStrategy(localStrategy, remoteStrategy)
        val testThread: TestThread =
            TestThread(
                TestRunnable {
                    try {
                        val spawn: Spawn = newDynamicSpawn()
                        strategyAndContext.exec(spawn)
                        checkExecResult.check(null)
                    } catch (e: java.lang.Exception) {
                        checkExecResult.check(e)
                    }
                })
        testThread.start()
        try {
            remoteDone.await()

            // At this point, the remote branch is done and the local branch is waiting until we allow it
            // to complete later on. This is necessary to let us assert the state of the thread's
            // liveliness.
            //
            // However, note that "done" just means that our DoExec hook for remoteStrategy finished.
            // Any exception raised from within it may still be propagating up, so the interrupt below
            // races with that (and thus an InterruptedException can "win" over our own exception). There
            // is no way to handle this condition in the test other than having to acknowledge that it may
            // happen.
            if (interruptThread) {
                testThread.interrupt()
            }

            // The thread running the exec via the strategy must still be alive regardless of our
            // interrupt request (because the local branch is stuck). Wait for a little bit to ensure
            // this is true; any multi-second wait should be sufficient to catch the majority of the
            // bugs.
            testThread.join(2000)
            Truth.assertThat(testThread.isAlive()).isTrue()
        } finally {
            // Unblocking the local branch allows the strategy to collect its result and then unblock the
            // thread.
            stopLocal.set(true)
            testThread.joinAndAssertState(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun strategyWaitsForBothSpawnsToFinish() {
        assertThatStrategyWaitsForBothSpawnsToFinish( /* executionFails= */
            false,  /* interruptThread= */
            false,
            CheckExecResult { e: java.lang.Exception? ->
                if (e != null) {
                    throw java.lang.IllegalStateException("Expected exec to finish successfully", e)
                }
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun strategyWaitsForBothSpawnsToFinishEvenIfInterrupted() {
        assertThatStrategyWaitsForBothSpawnsToFinish( /* executionFails= */
            false,  /* interruptThread= */
            true,
            CheckExecResult { e: java.lang.Exception? ->
                if (e == null) {
                    TestCase.fail("No exception raised")
                } else if (e is java.lang.InterruptedException) {
                    Truth.assertThat(java.lang.Thread.currentThread().isInterrupted()).isFalse()
                } else {
                    throw e
                }
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun strategyWaitsForBothSpawnsToFinishOnFailure() {
        assertThatStrategyWaitsForBothSpawnsToFinish( /* executionFails= */
            true,  /* interruptThread= */
            false,
            CheckExecResult { e: java.lang.Exception? ->
                if (e == null) {
                    TestCase.fail("No exception raised")
                } else if (e is ExecException) {
                    Truth.assertThat(java.lang.Thread.currentThread().isInterrupted()).isFalse()
                } else {
                    throw e
                }
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun strategyWaitsForBothSpawnsToFinishOnFailureEvenIfInterrupted() {
        assertThatStrategyWaitsForBothSpawnsToFinish( /* executionFails= */
            true,  /* interruptThread= */
            true,
            CheckExecResult { e: java.lang.Exception? ->
                if (e == null) {
                    TestCase.fail("No exception raised")
                } else if (e is java.lang.InterruptedException) {
                    // See comment in strategyWaitsForBothSpawnsToFinish regarding the race between the
                    // exception we raise on failure and the interrupt. We have to handle this case even
                    // though it is supposedly rare.
                } else if (e is ExecException) {
                    Truth.assertThat(java.lang.Thread.currentThread().isInterrupted()).isTrue()
                } else {
                    throw e
                }
            })
    }

    @Throws(java.lang.Exception::class)
    private fun assertThatStrategyPropagatesException(
        localExec: DoExec, remoteExec: DoExec, expectedException: java.lang.Exception
    ) {
        com.google.common.base.Preconditions.checkArgument(
            expectedException !is java.lang.IllegalStateException,
            ("Using an IllegalStateException for testing is fragile because we use that exception "
                    + "internally in the DynamicSpawnScheduler and we cannot distinguish it from the "
                    + "test's own exception")
        )

        val localStrategy =
            MockSpawnStrategy("MockLocalSpawnStrategy", localExec, DoExec.Companion.NOTHING)
        val remoteStrategy =
            MockSpawnStrategy("MockRemoteSpawnStrategy", remoteExec, DoExec.Companion.NOTHING)
        val strategyAndContext = createSpawnStrategy(localStrategy, remoteStrategy)

        val spawn: Spawn = newDynamicSpawn()
        val e: java.lang.Exception? = org.junit.Assert.assertThrows(
            expectedException.javaClass,
            org.junit.function.ThrowingRunnable { strategyAndContext.exec(spawn) })
        Truth.assertThat(e).hasMessageThat().contains(expectedException.message)

        var executedSpawn: Spawn? = localStrategy.getExecutedSpawn()
        executedSpawn = if (executedSpawn == null) remoteStrategy.getExecutedSpawn() else executedSpawn
        assertThat(executedSpawn).isEqualTo(spawn)
        Truth.assertThat(localStrategy.succeeded()).isFalse()
        Truth.assertThat(remoteStrategy.succeeded()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun strategyPropagatesFasterLocalException() {
        val e: java.lang.RuntimeException = java.lang.IllegalArgumentException("Local spawn execution exception")
        val localExec =
            DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                throw e
            }

        val remoteExec =
            DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                java.lang.Thread.sleep(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
                throw java.lang.AssertionError("Not reachable")
            }

        assertThatStrategyPropagatesException(localExec, remoteExec, e)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun strategyPropagatesFasterRemoteException() {
        val localExec =
            DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                java.lang.Thread.sleep(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
                throw java.lang.AssertionError("Not reachable")
            }

        val e: java.lang.RuntimeException = java.lang.IllegalArgumentException("Remote spawn execution exception")
        val remoteExec =
            DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                throw e
            }

        assertThatStrategyPropagatesException(localExec, remoteExec, e)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun remoteBranchThrowsInterruptedExceptionAfterCancellation() {
        val localCanFinish: CountDownLatch = CountDownLatch(1)
        val remoteStarted: CountDownLatch = CountDownLatch(1)

        val localStrategy =
            MockSpawnStrategy(
                "MockLocalSpawnStrategy",
                DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                    remoteStarted.await()
                    localCanFinish.await()
                },
                DoExec.Companion.NOTHING
            )

        val remoteStrategy =
            MockSpawnStrategy(
                "MockRemoteSpawnStrategy",
                DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                    remoteStarted.countDown()
                    java.lang.Thread.sleep(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
                },
                DoExec.Companion.NOTHING
            )

        val strategyAndContext = createSpawnStrategy(localStrategy, remoteStrategy)

        val testThread: TestThread =
            TestThread(
                TestRunnable {
                    try {
                        val spawn: Spawn = newDynamicSpawn()
                        strategyAndContext.exec(spawn)
                    } catch (e: java.lang.InterruptedException) {
                        throw java.lang.RuntimeException(e)
                    } catch (e: ExecException) {
                        throw java.lang.RuntimeException(e)
                    }
                })
        testThread.start()

        remoteStarted.await()
        localCanFinish.countDown()
        testThread.joinAndAssertState(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)

        Truth.assertThat(localStrategy.succeeded()).isTrue()
        Truth.assertThat(remoteStrategy.succeeded()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun localBranchCancelsRemoteWhichThrowsInterruptedException() {
        val bothStarted: CountDownLatch = CountDownLatch(2)

        val localStrategy =
            MockSpawnStrategy(
                "MockLocalSpawnStrategy",
                DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                    countDownAndWait(bothStarted)
                },
                DoExec.Companion.NOTHING
            )

        val remoteStrategy =
            MockSpawnStrategy(
                "MockRemoteSpawnStrategy",
                DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                    countDownAndWait(bothStarted)
                    java.lang.Thread.sleep(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
                },
                DoExec.Companion.NOTHING
            )

        val strategyAndContext = createSpawnStrategy(localStrategy, remoteStrategy)

        val spawn: Spawn = newDynamicSpawn()
        strategyAndContext.exec(spawn)

        Truth.assertThat(localStrategy.succeeded()).isTrue()
        Truth.assertThat(remoteStrategy.succeeded()).isFalse()
        com.google.common.truth.Subject.contains("output files written with MockLocalSpawnStrategy")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun remoteFinishesFirstAndThrowsInterruptedExceptionDuringLocalCancellation() {
        val bothStarted: CountDownLatch = CountDownLatch(2)

        val localStrategy =
            MockSpawnStrategy(
                "MockLocalSpawnStrategy",
                DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                    countDownAndWait(bothStarted)
                    java.lang.Thread.sleep(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
                },
                DoExec.Companion.NOTHING
            )

        val remoteStrategy =
            MockSpawnStrategy(
                "MockRemoteSpawnStrategy",
                DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                    countDownAndWait(bothStarted)
                },
                DoExec.Companion.NOTHING
            )

        val strategyAndContext = createSpawnStrategy(localStrategy, remoteStrategy)

        val spawn: Spawn = newDynamicSpawn()
        // This should complete successfully with remote results, not throw AssertionError
        strategyAndContext.exec(spawn)

        Truth.assertThat(localStrategy.succeeded()).isFalse()
        Truth.assertThat(remoteStrategy.succeeded()).isTrue()
        com.google.common.truth.Subject.contains("output files written with MockRemoteSpawnStrategy")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun remoteBranchThrowsInterruptedExceptionBeforeStopConcurrentSpawns() {
        val remoteStarted: CountDownLatch = CountDownLatch(1)

        val localStrategy =
            MockSpawnStrategy(
                "MockLocalSpawnStrategy",
                DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                    remoteStarted.await()
                    java.lang.Thread.sleep(100) // Give remote time to fail
                },
                DoExec.Companion.NOTHING
            )

        val remoteStrategy =
            MockSpawnStrategy(
                "MockRemoteSpawnStrategy",
                DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                    remoteStarted.countDown()
                    throw java.lang.InterruptedException("Remote operation interrupted")
                },
                DoExec.Companion.NOTHING
            )

        val strategyAndContext = createSpawnStrategy(localStrategy, remoteStrategy)

        val spawn: Spawn = newDynamicSpawn()
        strategyAndContext.exec(spawn)

        Truth.assertThat(localStrategy.succeeded()).isTrue()
        Truth.assertThat(remoteStrategy.succeeded()).isFalse()
        com.google.common.truth.Subject.contains("output files written with MockLocalSpawnStrategy")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun remoteBranchInterruptedAfterLocalStartsStopConcurrentSpawns() {
        val bothStarted: CountDownLatch = CountDownLatch(2)
        val localReadyToStop: CountDownLatch = CountDownLatch(1)

        val localStrategy =
            MockSpawnStrategy(
                "MockLocalSpawnStrategy",
                DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                    countDownAndWait(
                        bothStarted
                    )
                },
                DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? -> localReadyToStop.countDown() })

        val remoteStrategy =
            MockSpawnStrategy(
                "MockRemoteSpawnStrategy",
                DoExec { self: MockSpawnStrategy?, spawn: Spawn?, actionExecutionContext: ActionExecutionContext? ->
                    countDownAndWait(bothStarted)
                    java.lang.Thread.sleep(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
                },
                DoExec.Companion.NOTHING
            )

        val strategyAndContext = createSpawnStrategy(localStrategy, remoteStrategy)

        val spawn: Spawn = newDynamicSpawn()
        strategyAndContext.exec(spawn)

        Truth.assertThat(localStrategy.succeeded()).isTrue()
        Truth.assertThat(remoteStrategy.succeeded()).isFalse()
        com.google.common.truth.Subject.contains("output files written with MockLocalSpawnStrategy")
    }

    internal class StrategyAndContext(strategy: SpawnStrategy?, context: ActionExecutionContext?) {
        @Throws(ExecException::class, java.lang.InterruptedException::class)
        fun exec(spawn: Spawn?) {
            this.strategy.exec(spawn, this.context)
        }

        val strategy: SpawnStrategy?
        val context: ActionExecutionContext?

        init {
            this.context = context
            this.strategy = strategy
            java.util.Objects.requireNonNull<Any?>(strategy, "strategy")
            java.util.Objects.requireNonNull<Any?>(context, "context")
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /** Syntactic sugar to decrease and await for a latch in a single line.  */
        @Throws(java.lang.InterruptedException::class)
        private fun countDownAndWait(countDownLatch: CountDownLatch) {
            countDownLatch.countDown()
            countDownLatch.await()
        }

        private fun createFailureDetail(message: String?): FailureDetail {
            return FailureDetail.newBuilder()
                .setMessage(message)
                .setDynamicExecution(DynamicExecution.newBuilder().setCode(Code.RUN_FAILURE))
                .build()
        }
    }
}
